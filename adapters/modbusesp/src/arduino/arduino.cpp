/* SPDX-License-Identifier: MIT */
/*
 * The Arduino core functions and the socket Client/Server of Arduino.h.
 * Platform layer only: nothing in this file parses or builds Modbus frames.
 */
#include "Arduino.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>

#include <set>
#include <vector>

// ---------------------------------------------------------------------------
// Time
// ---------------------------------------------------------------------------

static uint64_t monotonic_us()
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t) ts.tv_sec * 1000000u + (uint64_t) ts.tv_nsec / 1000u;
}

static const uint64_t start_us = monotonic_us();

/* Like on a board, both wrap: unsigned long is 64 bits on this host, but the
   library stores timestamps in uint32_t and subtracts, so they are reduced
   to 32 bits as on a 32-bit core. */
unsigned long millis() { return (uint32_t) ((monotonic_us() - start_us) / 1000u); }
unsigned long micros() { return (uint32_t) (monotonic_us() - start_us); }

void delay(unsigned long ms)
{
    struct timespec ts = {(time_t) (ms / 1000), (long) (ms % 1000) * 1000000L};
    nanosleep(&ts, nullptr);
}

void delayMicroseconds(unsigned int us)
{
    struct timespec ts = {(time_t) (us / 1000000), (long) (us % 1000000) * 1000L};
    nanosleep(&ts, nullptr);
}

void yield() {}

void pinMode(uint8_t, uint8_t) {}
void digitalWrite(uint8_t, uint8_t) {}

// ---------------------------------------------------------------------------
// Stream (cores/arduino/Stream.cpp)
// ---------------------------------------------------------------------------

int Stream::timedRead()
{
    unsigned long started = millis();
    do {
        int c = read();
        if (c >= 0)
            return c;
        posix_net::wait_for_io(1);
    } while (millis() - started < timeout_);
    return -1;
}

size_t Stream::readBytes(char *buffer, size_t length)
{
    size_t count = 0;
    while (count < length) {
        int c = timedRead();
        if (c < 0)
            break;
        *buffer++ = (char) c;
        count++;
    }
    return count;
}

// ---------------------------------------------------------------------------
// Sockets
// ---------------------------------------------------------------------------

namespace {
// Every socket of the process, for wait_for_io(). Never destroyed: static
// Modbus objects may close their sockets during exit.
std::set<int> &open_fds = *new std::set<int>();
// Connections with written bytes not yet handed to the kernel.
std::set<struct PosixConnection *> &pending = *new std::set<struct PosixConnection *>();
std::string bind_address = "0.0.0.0";
unsigned long connect_timeout_ms = 5000;
int begin_error = 0;

void set_nonblocking(int fd) { fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK); }

int open_socket()
{
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd >= 0)
        open_fds.insert(fd);
    return fd;
}

void close_socket(int fd)
{
    if (fd >= 0) {
        open_fds.erase(fd);
        close(fd);
    }
}
} // namespace

/* One TCP connection, shared by every PosixClient handle that refers to it.
   It is closed by stop() or when the last handle goes away (as WiFiClient).

   Written bytes go to a transmit buffer, as on a board (a UART's TX buffer,
   the TCP send buffer of the WiFi or Ethernet chip), and leave in one piece:
   on flush(), on the next read, availability or connection check of the
   same client, and in wait_for_io(), i.e. before the application loop waits.
   Without it ModbusRTU's rawSend(), which writes the address, the PDU and the
   two CRC bytes with four calls, would become four TCP segments, and a gap
   between them longer than the RTU inter-frame time (1750 us) splits the
   frame at the receiver. */
struct PosixConnection {
    int fd = -1;
    bool eof = false;
    std::vector<uint8_t> buffer; // received, not yet read
    size_t head = 0;
    std::vector<uint8_t> out; // written, not yet sent
    IPAddress ip;
    uint16_t port = 0;

    ~PosixConnection()
    {
        push();
        pending.erase(this);
        close_socket(fd);
    }

    /* Hands the transmit buffer to the kernel. */
    void push()
    {
        size_t sent = 0;

        pending.erase(this);
        while (fd >= 0 && sent < out.size()) {
            ssize_t n = send(fd, out.data() + sent, out.size() - sent, MSG_NOSIGNAL);
            if (n > 0) {
                sent += (size_t) n;
                continue;
            }
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
                struct pollfd p = {fd, POLLOUT, 0};
                if (poll(&p, 1, 1000) == 1)
                    continue;
            }
            eof = true;
            break;
        }
        out.clear();
    }

    size_t buffered() const { return buffer.size() - head; }

    /* Moves what the socket has into the buffer, without waiting. */
    void fill()
    {
        push();
        if (fd < 0 || eof)
            return;
        for (;;) {
            uint8_t chunk[4096];
            ssize_t n = recv(fd, chunk, sizeof(chunk), MSG_DONTWAIT);
            if (n > 0) {
                if (head > 0 && head == buffer.size()) {
                    buffer.clear();
                    head = 0;
                }
                buffer.insert(buffer.end(), chunk, chunk + n);
                continue;
            }
            if (n == 0 || (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR))
                eof = true;
            return;
        }
    }
};

static std::shared_ptr<PosixConnection> wrap(int fd, const struct sockaddr_in &peer)
{
    auto c = std::make_shared<PosixConnection>();
    int one = 1;

    c->fd = fd;
    c->ip = IPAddress((uint32_t) peer.sin_addr.s_addr);
    c->port = ntohs(peer.sin_port);
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    set_nonblocking(fd);
    return c;
}

int PosixClient::connect(IPAddress ip, uint16_t port)
{
    struct sockaddr_in peer;
    struct pollfd p;
    int fd, error = 0;
    socklen_t len = sizeof(error);

    stop();
    memset(&peer, 0, sizeof(peer));
    peer.sin_family = AF_INET;
    peer.sin_port = htons(port);
    peer.sin_addr.s_addr = (uint32_t) ip;
    fd = open_socket();
    if (fd < 0)
        return 0;
    set_nonblocking(fd);
    if (::connect(fd, (struct sockaddr *) &peer, sizeof(peer)) != 0) {
        if (errno != EINPROGRESS) {
            close_socket(fd);
            return 0;
        }
        p.fd = fd;
        p.events = POLLOUT;
        if (poll(&p, 1, (int) connect_timeout_ms) != 1 ||
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) != 0 || error != 0) {
            close_socket(fd);
            return 0;
        }
    }
    c_ = wrap(fd, peer);
    return 1;
}

int PosixClient::connect(const char *host, uint16_t port)
{
    IPAddress ip = posix_net::resolve(host);
    if ((uint32_t) ip == INADDR_NONE)
        return 0;
    return connect(ip, port);
}

uint8_t PosixClient::connected()
{
    if (!c_ || c_->fd < 0)
        return 0;
    c_->fill();
    return (!c_->eof || c_->buffered() > 0) ? 1 : 0;
}

void PosixClient::stop()
{
    if (c_) {
        c_->push();
        close_socket(c_->fd);
        c_->fd = -1;
        c_->eof = true;
        c_->buffer.clear();
        c_->head = 0;
    }
    c_.reset();
}

PosixClient::operator bool() { return c_ && c_->fd >= 0; }

int PosixClient::available()
{
    if (!c_)
        return 0;
    c_->fill();
    return (int) c_->buffered();
}

int PosixClient::read()
{
    if (available() == 0)
        return -1;
    return c_->buffer[c_->head++];
}

int PosixClient::peek()
{
    if (available() == 0)
        return -1;
    return c_->buffer[c_->head];
}

size_t PosixClient::write(uint8_t byte) { return write(&byte, 1); }

size_t PosixClient::write(const uint8_t *buffer, size_t size)
{
    if (!c_ || c_->fd < 0 || c_->eof)
        return 0;
    c_->out.insert(c_->out.end(), buffer, buffer + size);
    pending.insert(c_.get());
    return size;
}

void PosixClient::flush()
{
    if (c_)
        c_->push();
}

IPAddress PosixClient::remoteIP() { return c_ ? c_->ip : IPAddress(); }
uint16_t PosixClient::remotePort() { return c_ ? c_->port : 0; }

PosixServer::~PosixServer() { close_socket(fd_); }

void PosixServer::begin()
{
    struct sockaddr_in local;
    int one = 1;

    close_socket(fd_);
    fd_ = open_socket();
    begin_error = 0;
    if (fd_ < 0) {
        begin_error = errno;
        return;
    }
    setsockopt(fd_, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    memset(&local, 0, sizeof(local));
    local.sin_family = AF_INET;
    local.sin_port = htons(port_);
    if (inet_pton(AF_INET, bind_address.c_str(), &local.sin_addr) != 1) {
        begin_error = EINVAL;
    } else if (bind(fd_, (struct sockaddr *) &local, sizeof(local)) != 0 || listen(fd_, 64) != 0) {
        begin_error = errno;
    }
    if (begin_error != 0) {
        close_socket(fd_);
        fd_ = -1;
        return;
    }
    set_nonblocking(fd_);
}

PosixClient PosixServer::accept()
{
    struct sockaddr_in peer;
    socklen_t len = sizeof(peer);
    int fd;

    if (fd_ < 0)
        return PosixClient();
    fd = accept4(fd_, (struct sockaddr *) &peer, &len, SOCK_CLOEXEC);
    if (fd < 0)
        return PosixClient();
    open_fds.insert(fd);
    return PosixClient(wrap(fd, peer));
}

namespace posix_net {
void set_bind_address(const char *address) { bind_address = address; }
void set_connect_timeout_ms(unsigned long ms) { connect_timeout_ms = ms; }
int last_begin_error() { return begin_error; }

void wait_for_io(int ms)
{
    std::vector<struct pollfd> fds;
    while (!pending.empty())
        (*pending.begin())->push();
    for (int fd : open_fds)
        fds.push_back({fd, POLLIN, 0});
    if (fds.empty()) {
        delay((unsigned long) ms);
        return;
    }
    poll(fds.data(), fds.size(), ms);
}

IPAddress resolve(const char *host)
{
    struct addrinfo hints, *found = nullptr;
    struct in_addr literal;
    uint32_t address = INADDR_NONE;

    if (inet_pton(AF_INET, host, &literal) == 1)
        return IPAddress((uint32_t) literal.s_addr);
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, nullptr, &hints, &found) == 0 && found != nullptr) {
        address = (uint32_t) ((struct sockaddr_in *) found->ai_addr)->sin_addr.s_addr;
        freeaddrinfo(found);
    }
    return IPAddress(address);
}
} // namespace posix_net
