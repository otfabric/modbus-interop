// SPDX-License-Identifier: MIT
//
// The `server` command: a fixture device on jsmodbus' ModbusTCPServer
// (--transport tcp) or one ModbusRTUServer per TCP connection
// (--transport rtu-over-tcp).
//
// The library decodes every request, frames every response and encodes the
// response bodies. The server is created without its four tables, which is
// the library's documented way ("set the buffer options to undefined to use
// the events") to hand each decoded request to the application with the
// callback that sends the response. Device answers in those event handlers:
// it applies the rules of docs/FIXTURES.md and then answers with the
// library's response body classes (the reads are cut from the device's
// tables by the library's fromRequest methods) or the library's exception
// body. See README.md.

'use strict'

const fs = require('fs')
const net = require('net')
const path = require('path')
const Modbus = require('jsmodbus')
const { ADAPTER, emit, stderr } = require('./common')
const { load, conformityLevel, FixtureError } = require('./fixture')

const R = Modbus.responses
const NO_TABLES = { coils: undefined, discrete: undefined, holding: undefined, input: undefined }

const FC = { READ_COILS: 1, READ_DISCRETE_INPUTS: 2, READ_HOLDING: 3, READ_INPUT: 4, WRITE_COIL: 5, WRITE_REGISTER: 6,
  WRITE_COILS: 15, WRITE_REGISTERS: 16, DEVICE_ID: 43 }
const EX = { ILLEGAL_FUNCTION: 1, ILLEGAL_ADDRESS: 2, ILLEGAL_VALUE: 3, DEVICE_FAILURE: 4, GATEWAY_TARGET: 11 }
// The event the library emits for each function when the table is absent.
const EVENTS = ['readCoils', 'readDiscreteInputs', 'readHoldingRegisters', 'readInputRegisters', 'writeSingleCoil',
  'writeSingleRegister', 'writeMultipleCoils', 'writeMultipleRegisters', 'readDeviceIdentification']
// Room for objects in one Read Device Identification response: a PDU has at
// most 253 bytes, 7 of them before the first object.
const DEVICE_ID_ROOM = 253 - 7

class Reject extends Error {
  constructor (code) {
    super(`exception ${code}`)
    this.code = code
  }
}

/** A table of the fixture in the layout of the library: bits LSB first, registers big-endian. */
class Table {
  constructor (block, bits) {
    this.first = block.address
    this.end = block.end // address after the last element
    if (bits) {
      this.buffer = Buffer.alloc(Math.ceil(this.end / 8))
      block.values.forEach((v, i) => this.setBit(this.first + i, v))
    } else {
      this.buffer = Buffer.alloc(this.end * 2)
      block.values.forEach((v, i) => this.buffer.writeUInt16BE(v, (this.first + i) * 2))
    }
  }

  contains (address, count) {
    return count >= 1 && address >= this.first && address + count <= this.end
  }

  setBit (address, on) {
    const i = address >> 3
    const mask = 1 << (address & 7)
    this.buffer[i] = on ? (this.buffer[i] | mask) : (this.buffer[i] & ~mask)
  }
}

class Device {
  constructor (fixture, transport) {
    this.fixture = fixture
    this.transport = transport
    const t = fixture.tables
    this.coils = t.coils ? new Table(t.coils, true) : null
    this.discrete = t.discreteInputs ? new Table(t.discreteInputs, true) : null
    this.holding = t.holdingRegisters ? new Table(t.holdingRegisters, false) : null
    this.input = t.inputRegisters ? new Table(t.inputRegisters, false) : null
    this.objects = fixture.objects
    this.conformity = fixture.objects ? 0x80 | conformityLevel(fixture.objects) : 0
    this.Response = transport === 'rtu-over-tcp' ? Modbus.ModbusRTUResponse : Modbus.ModbusTCPResponse
  }

  /** Registers the device on a library server whose tables are absent. */
  attach (server) {
    for (const event of EVENTS) server.on(event, (request, cb) => this.handle(request, cb))
  }

  handle (request, cb) {
    const body = request.body
    const event = { event: 'request', unitId: request.unitId, function: body.fc }
    let answer
    try {
      if (request.unitId !== this.fixture.unitId) {
        // rtu-over-tcp: only the addressed device answers, and nobody
        // answers a broadcast (Modbus over Serial Line V1.02, 2.2).
        if (this.transport === 'rtu-over-tcp') return
        throw new Reject(EX.GATEWAY_TARGET)
      }
      answer = this.serve(body, event)
    } catch (e) {
      if (!(e instanceof Reject)) {
        stderr(`modbus-interop: request ${body.fc}: ${e && e.stack ? e.stack : e}`)
        e = new Reject(EX.DEVICE_FAILURE)
      }
      answer = new R.ExceptionResponseBody(body.fc, e.code)
      event.exception = e.code
    } finally {
      // describe() fills in what the request carried, whatever the outcome.
      this.describe(body, event)
    }
    if (event.exception === undefined) event.exception = null
    cb(this.Response.fromRequest(request, answer).createPayload())
    emit(event)
  }

  describe (body, event) {
    switch (body.fc) {
      case FC.READ_COILS: case FC.READ_DISCRETE_INPUTS: case FC.READ_HOLDING: case FC.READ_INPUT:
        event.address = body.start
        event.quantity = body.count
        break
      case FC.WRITE_COIL:
        event.address = body.address
        event.quantity = 1
        event.bits = [body.value === 0xFF00]
        break
      case FC.WRITE_REGISTER:
        event.address = body.address
        event.quantity = 1
        event.registers = [body.value]
        break
      case FC.WRITE_COILS:
        event.address = body.address
        event.quantity = body.quantity
        event.bits = body.valuesAsArray.slice(0, body.quantity).map(Boolean)
        break
      case FC.WRITE_REGISTERS:
        event.address = body.address
        event.quantity = body.quantity
        event.registers = body.valuesAsArray.slice()
        break
    }
  }

  serve (body, event) {
    const table = (t) => { if (!t) throw new Reject(EX.ILLEGAL_ADDRESS); return t }
    const within = (t, address, count) => { if (!table(t).contains(address, count)) throw new Reject(EX.ILLEGAL_ADDRESS); return t }
    const quantity = (q, max) => { if (q < 1 || q > max) throw new Reject(EX.ILLEGAL_VALUE) }
    switch (body.fc) {
      case FC.READ_COILS:
        quantity(body.count, 2000)
        return R.ReadCoilsResponseBody.fromRequest(body, within(this.coils, body.start, body.count).buffer)
      case FC.READ_DISCRETE_INPUTS:
        quantity(body.count, 2000)
        return R.ReadDiscreteInputsResponseBody.fromRequest(body, within(this.discrete, body.start, body.count).buffer)
      case FC.READ_HOLDING:
        quantity(body.count, 125)
        return R.ReadHoldingRegistersResponseBody.fromRequest(body, within(this.holding, body.start, body.count).buffer)
      case FC.READ_INPUT:
        quantity(body.count, 125)
        return R.ReadInputRegistersResponseBody.fromRequest(body, within(this.input, body.start, body.count).buffer)
      case FC.WRITE_COIL:
        // The library's decoder has already turned the value into on (0xFF00)
        // or off (anything else): an invalid value cannot be seen here.
        within(this.coils, body.address, 1).setBit(body.address, body.value === 0xFF00)
        return R.WriteSingleCoilResponseBody.fromRequest(body)
      case FC.WRITE_REGISTER:
        within(this.holding, body.address, 1).buffer.writeUInt16BE(body.value, body.address * 2)
        return R.WriteSingleRegisterResponseBody.fromRequest(body)
      case FC.WRITE_COILS: {
        quantity(body.quantity, 1968)
        // The decoder keeps the data bytes of the frame; the byte count must
        // be the one of the quantity.
        if (body.valuesAsBuffer.length !== Math.ceil(body.quantity / 8)) throw new Reject(EX.ILLEGAL_VALUE)
        const coils = within(this.coils, body.address, body.quantity)
        body.valuesAsArray.slice(0, body.quantity).forEach((v, i) => coils.setBit(body.address + i, v))
        return R.WriteMultipleCoilsResponseBody.fromRequest(body)
      }
      case FC.WRITE_REGISTERS:
        // The decoder derives the quantity from the byte count and drops the
        // quantity field of the frame.
        quantity(body.quantity, 123)
        if (body.valuesAsBuffer.length !== body.quantity * 2) throw new Reject(EX.ILLEGAL_VALUE)
        body.valuesAsBuffer.copy(within(this.holding, body.address, body.quantity).buffer, body.address * 2)
        return R.WriteMultipleRegistersResponseBody.fromRequest(body)
      case FC.DEVICE_ID:
        return this.identify(body)
    }
    throw new Reject(EX.ILLEGAL_FUNCTION)
  }

  /** FC43/14. The library has checked the MEI type (14) and the read code (1..4). */
  identify (body) {
    if (!this.objects) throw new Reject(EX.ILLEGAL_FUNCTION)
    const code = body.readDeviceIdCode
    const entry = (id) => ({ id, value: Buffer.from(this.objects.get(id), 'latin1') })
    if (code === 4) {
      // Individual access: the one object, which must exist.
      if (!this.objects.has(body.objectId)) throw new Reject(EX.ILLEGAL_ADDRESS)
      return new R.ReadDeviceIdentificationResponseBody(code, this.conformity, 0, 0, [entry(body.objectId)])
    }
    const last = code === 1 ? 2 : code === 2 ? 6 : 255
    const ids = [...this.objects.keys()].filter((id) => id <= last)
    // An object id the category does not have restarts the stream at 0 (6.21).
    let start = body.objectId
    if (!ids.includes(start)) start = 0
    const objects = []
    let room = DEVICE_ID_ROOM
    let next = 0
    for (const id of ids.filter((i) => i >= start)) {
      const e = entry(id)
      if (2 + e.value.length > room) { next = id; break }
      room -= 2 + e.value.length
      objects.push(e)
    }
    return new R.ReadDeviceIdentificationResponseBody(code, this.conformity, next ? 0xFF : 0, next, objects)
  }
}

function writeReady (file) {
  const dir = path.dirname(file)
  if (dir) fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(file, 'ready\n')
}

function run (opts) {
  let fixture
  try {
    fixture = load(opts.fixture)
  } catch (e) {
    if (!(e instanceof FixtureError)) throw e
    stderr(`modbus-interop: invalid fixture: ${e.message}`)
    return Promise.resolve(4)
  }
  const device = new Device(fixture, opts.transport)
  const netServer = new net.Server()
  const sockets = new Set()

  const onSocket = (socket) => {
    const peer = `${socket.remoteAddress}:${socket.remotePort}`
    sockets.add(socket)
    emit({ event: 'connection-opened', peer })
    socket.on('error', (e) => stderr(`modbus-interop: ${peer}: ${e.message}`))
    socket.on('close', () => {
      sockets.delete(socket)
      emit({ event: 'connection-closed', peer })
    })
  }

  if (opts.transport === 'rtu-over-tcp') {
    // ModbusRTUServer serves one stream; a TCP connection is one.
    netServer.on('connection', (socket) => {
      onSocket(socket)
      device.attach(new Modbus.server.RTU(socket, NO_TABLES))
    })
  } else {
    const server = new Modbus.server.TCP(netServer, NO_TABLES)
    device.attach(server)
    server.on('connection', (client) => onSocket(client.socket))
  }

  return new Promise((resolve) => {
    netServer.once('error', (e) => {
      stderr(`modbus-interop: cannot listen on ${opts.bindAddress}:${opts.port}: ${e.message}`)
      resolve(1)
    })
    netServer.listen({ port: opts.port, host: opts.bindAddress, backlog: 128 }, () => {
      netServer.removeAllListeners('error')
      netServer.on('error', (e) => stderr(`modbus-interop: ${e.message}`))
      try {
        writeReady(opts.readyFile)
      } catch (e) {
        stderr(`modbus-interop: cannot write the readiness file ${opts.readyFile}: ${e.message}`)
        netServer.close()
        resolve(1)
        return
      }
      emit({ event: 'ready', adapter: ADAPTER, address: `${opts.bindAddress}:${opts.port}`, fixture: fixture.name, unitId: fixture.unitId })
      let stopping = false
      const stop = () => {
        if (stopping) return
        stopping = true
        try { fs.unlinkSync(opts.readyFile) } catch (e) { /* already gone */ }
        const done = () => { emit({ event: 'stopped' }); resolve(0) }
        const timer = setTimeout(done, 3000)
        netServer.close(() => { clearTimeout(timer); setImmediate(done) })
        for (const s of sockets) s.destroy()
      }
      process.on('SIGTERM', stop)
      process.on('SIGINT', stop)
    })
  })
}

module.exports = { run }
