/* SPDX-License-Identifier: MIT */
/*
 * The part of the Arduino core API that modbus-esp8266 uses, for a POSIX host.
 *
 * modbus-esp8266 is written against the Arduino core: millis(), micros(),
 * the bit macros, String, IPAddress, Print, Stream, Client and Server. On a
 * board the core and its network library (WiFi, Ethernet) provide them; this
 * file and posix_net.cpp provide them over the C library and BSD sockets, so
 * the library's own, unmodified Modbus code runs on Linux.
 *
 * Nothing here knows Modbus. PosixClient and PosixServer are the SERVER and
 * CLIENT the library's ModbusTCPTemplate is instantiated with, exactly as its
 * ModbusEthernet.h does with EthernetServer/EthernetClient and its ClearCore
 * example with the ClearCore Ethernet classes; PosixClient is also the Stream
 * ModbusRTU is bound to for RTU frames on a TCP connection.
 *
 * The semantics follow the Arduino core (cores/arduino/Stream.cpp,
 * IPAddress.h) and the Ethernet library: Stream::readBytes waits up to the
 * stream timeout (1000 ms by default) for each byte, read() returns -1 when
 * nothing is buffered, Client objects are handles that may be copied and
 * share one connection, connected() stays true while received bytes are
 * still unread, and Server::accept() returns a connected client or an
 * invalid one without blocking.
 */
#pragma once

#include <math.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <memory>
#include <string>

typedef uint8_t byte;
typedef bool boolean;

unsigned long millis();
unsigned long micros();
void delay(unsigned long ms);
void delayMicroseconds(unsigned int us);
void yield();

#define bitRead(value, bit) (((value) >> (bit)) & 0x01)
#define bitSet(value, bit) ((value) |= (1UL << (bit)))
#define bitClear(value, bit) ((value) &= ~(1UL << (bit)))
#define lowByte(w) ((uint8_t) ((w) & 0xff))
#define highByte(w) ((uint8_t) ((w) >> 8))

/* AVR keeps constant tables in flash; a host has one address space. */
#define PROGMEM
#define pgm_read_word(addr) (*(const unsigned short *) (addr))

/* A host has no GPIO. The library drives a pin only when it is given one (an
   RS-485 transmit-enable pin to ModbusRTU::begin); the adapter gives none. */
#define LOW 0x0
#define HIGH 0x1
#define INPUT 0x0
#define OUTPUT 0x1
void pinMode(uint8_t pin, uint8_t mode);
void digitalWrite(uint8_t pin, uint8_t value);

class String {
  public:
    String(const char *text = "") : s_(text ? text : "") {}
    const char *c_str() const { return s_.c_str(); }
    unsigned int length() const { return (unsigned int) s_.size(); }

  private:
    std::string s_;
};

/* Arduino's IPAddress: four bytes in network order; converts to the uint32_t
   those bytes make in memory (as in the ESP and Ethernet cores). */
class IPAddress {
  public:
    IPAddress() : addr_(0) {}
    IPAddress(uint8_t a, uint8_t b, uint8_t c, uint8_t d)
    {
        uint8_t bytes[4] = {a, b, c, d};
        memcpy(&addr_, bytes, 4);
    }
    IPAddress(uint32_t address) : addr_(address) {}
    operator uint32_t() const { return addr_; }
    bool operator==(const IPAddress &other) const { return addr_ == other.addr_; }
    bool operator!=(const IPAddress &other) const { return addr_ != other.addr_; }
    uint8_t operator[](int i) const { return reinterpret_cast<const uint8_t *>(&addr_)[i]; }

  private:
    uint32_t addr_;
};

class Print {
  public:
    virtual ~Print() {}
    virtual size_t write(uint8_t byte) = 0;
    virtual size_t write(const uint8_t *buffer, size_t size)
    {
        size_t n = 0;
        while (size-- && write(*buffer++))
            n++;
        return n;
    }
    virtual void flush() {}
};

class Stream : public Print {
  public:
    virtual int available() = 0;
    virtual int read() = 0;
    virtual int peek() = 0;
    void setTimeout(unsigned long ms) { timeout_ = ms; }
    unsigned long getTimeout() const { return timeout_; }
    size_t readBytes(char *buffer, size_t length);
    size_t readBytes(uint8_t *buffer, size_t length) { return readBytes((char *) buffer, length); }

  protected:
    int timedRead();
    unsigned long timeout_ = 1000;
};

class Client : public Stream {
  public:
    virtual int connect(IPAddress ip, uint16_t port) = 0;
    virtual int connect(const char *host, uint16_t port) = 0;
    virtual uint8_t connected() = 0;
    virtual void stop() = 0;
    virtual operator bool() = 0;
};

class Server : public Print {
  public:
    virtual void begin() = 0;
};

#include "posix_net.h"
