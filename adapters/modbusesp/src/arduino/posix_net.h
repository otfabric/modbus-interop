/* SPDX-License-Identifier: MIT */
/*
 * Arduino-style Client and Server over BSD sockets (TCP, IPv4), the role the
 * WiFi and Ethernet libraries play on a board. Included by Arduino.h.
 */
#pragma once

#include <memory>

struct PosixConnection;

class PosixClient : public Client {
  public:
    PosixClient() {}
    explicit PosixClient(std::shared_ptr<PosixConnection> connection) : c_(std::move(connection)) {}

    int connect(IPAddress ip, uint16_t port) override;
    int connect(const char *host, uint16_t port) override;
    uint8_t connected() override;
    void stop() override;
    operator bool() override;

    int available() override;
    int read() override;
    int peek() override;
    size_t write(uint8_t byte) override;
    size_t write(const uint8_t *buffer, size_t size) override;
    /* Sends what was written (see PosixConnection in arduino.cpp). */
    void flush() override;

    IPAddress remoteIP();
    uint16_t remotePort();

  private:
    std::shared_ptr<PosixConnection> c_;
};

class PosixServer : public Server {
  public:
    explicit PosixServer(uint16_t port) : port_(port) {}
    ~PosixServer() override;
    void begin() override;
    /* The next connection that is waiting, or an invalid client. Never blocks. */
    PosixClient accept();
    PosixClient available() { return accept(); }
    /* Print: a board's server writes to every client; the library never
       calls it. */
    size_t write(uint8_t) override { return 0; }

  private:
    uint16_t port_;
    int fd_ = -1;
};

/* Settings of the platform layer, as a board's network setup would make them. */
namespace posix_net {
/* The address PosixServer::begin() binds to (dotted IPv4). Default 0.0.0.0. */
void set_bind_address(const char *address);
/* The bound on PosixClient::connect(). Default 5000 ms. */
void set_connect_timeout_ms(unsigned long ms);
/* errno of the last PosixServer::begin() that failed, 0 if the last one
   succeeded. Server::begin() returns nothing on Arduino. */
int last_begin_error();
/* Waits up to ms for any socket of this process to become readable (or for
   a signal). The loop of an application calls it between task() calls,
   where a sketch calls delay() or yield(). */
void wait_for_io(int ms);
/* An IPv4 address for a host name or literal: what WiFi.hostByName() is on
   a board. INADDR_NONE if it does not resolve. */
IPAddress resolve(const char *host);
} // namespace posix_net
