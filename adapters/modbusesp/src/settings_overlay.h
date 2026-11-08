/* SPDX-License-Identifier: MIT */
/*
 * Compile-time settings of modbus-esp8266 for this adapter.
 *
 * The library is configured through src/ModbusSettings.h, its documented
 * "compile time settings" (documentation/README.md). That file #defines
 * almost every setting unconditionally, so a -D on the command line cannot
 * change one: the library expects a user to edit the file. The adapter does
 * not edit it (the upstream tree is built byte for byte as fetched). This
 * header is given to every translation unit with -include: it includes
 * ModbusSettings.h first, which is then not included again (#pragma once),
 * and redefines the settings listed below. The effect is that of editing
 * those lines of ModbusSettings.h, and nothing else.
 *
 * Changed, and why:
 *   MODBUSIP_MAXFRAME 200 -> 253   The largest PDU of the protocol is 253
 *       bytes (MODBUS Application Protocol V1.1b3, 4.1). With 200 the library
 *       drops every frame whose PDU is longer: as a server it answers it with
 *       exception 4, as a client it never sees the response of a read of more
 *       than 98 registers or 1568 coils.
 *   MODBUSIP_MAX_CLIENTS 4 -> 16   The container contract asks for at least
 *       16 simultaneous connections (the ESP32 default of the library is 8).
 *   MODBUSIP_UNIQUE_CLIENTS off    With it, a second connection from the same
 *       IP address closes the first one; the contract's simultaneous
 *       connections may come from one host.
 *   MODBUSIP_TIMEOUT, MODBUSRTU_TIMEOUT 1000 ms -> --timeout-ms   The
 *       library's own response timeout, so that a client operation waits as
 *       long as its --timeout-ms says. Both are only ever compared with an
 *       elapsed time, so they may name a variable.
 *
 * Everything else is the library's default, including MODBUS_GLOBAL_REGS
 * (one register set shared by all Modbus objects of the process).
 */
#pragma once

#include "ModbusSettings.h"

#include <stdint.h>

extern uint32_t modbusesp_timeout_ms;

#undef MODBUSIP_MAXFRAME
#define MODBUSIP_MAXFRAME 253

#undef MODBUSIP_MAX_CLIENTS
#define MODBUSIP_MAX_CLIENTS 16

#undef MODBUSIP_UNIQUE_CLIENTS

#undef MODBUSIP_TIMEOUT
#define MODBUSIP_TIMEOUT modbusesp_timeout_ms

#undef MODBUSRTU_TIMEOUT
#define MODBUSRTU_TIMEOUT modbusesp_timeout_ms
