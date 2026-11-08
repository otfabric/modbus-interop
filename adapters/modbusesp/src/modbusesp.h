/* SPDX-License-Identifier: MIT */
/*
 * The modbus-esp8266 classes the adapter uses.
 *
 * ModbusTCPTemplate<SERVER, CLIENT> is the library's Modbus TCP engine over
 * any Arduino-style server and client class; the library itself instantiates
 * it as ModbusTCP (WiFiServer/WiFiClient, ModbusTCP.h) and ModbusEthernet
 * (EthernetServer/EthernetClient, ModbusEthernet.h), and its ClearCore
 * example declares
 *   class ModbusEthernet : public ModbusAPI<ModbusTCPTemplate<EthernetServer, EthernetClient>> {};
 * for another network library. ModbusPosix below is the same declaration
 * for the socket classes of arduino/posix_net.h.
 *
 * ModbusRTU (ModbusRTU.h) is the library's RTU engine over any Arduino
 * Stream; the adapter binds it to a TCP connection.
 */
#pragma once

#include "adapter.h"

#include <ModbusAPI.h>
#include <ModbusRTU.h>
#include <ModbusTCPTemplate.h>

class ModbusPosix : public ModbusAPI<ModbusTCPTemplate<PosixServer, PosixClient>> {};

/* The transports of contract 1.2 the library has: Modbus TCP and RTU (here
   on a TCP connection). It has no Modbus over UDP. */
enum transport_t { TRANSPORT_TCP, TRANSPORT_RTU_OVER_TCP };
int parse_transport(const char *text, transport_t *out);
