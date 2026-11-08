// SPDX-License-Identifier: MIT
//
// The `client` command: one bounded operation with jsmodbus' ModbusTCPClient
// (--transport tcp) or ModbusRTUClient on a TCP stream (--transport
// rtu-over-tcp).
//
// Requests are encoded, framed, matched and decoded by the library, and its
// request timeout bounds every wait for a response. The adapter connects the
// socket (bounded by --connect-timeout-ms), turns the library's result or
// UserRequestError into the result document, and checks what the library
// does not: that a response holds what the request asked for and that a
// write response echoes the request. See README.md.

'use strict'

const net = require('net')
const Modbus = require('jsmodbus')
const { ADAPTER, SCHEMA_VERSION, emit } = require('./common')

const CATEGORIES = { basic: 1, regular: 2, extended: 3 }
const MORE_FOLLOWS = 0xFF
const MAX_IDENTIFICATION_REQUESTS = 256 // one per object at worst: the stream cannot loop forever

class Failure extends Error {
  constructor (code, message) {
    super(message)
    this.code = code
  }
}

class DeviceException extends Error {
  constructor (fn, code) {
    super(`exception response ${code} (function ${fn})`)
    this.fn = fn
    this.code = code
  }
}

class Session {
  constructor (params) {
    this.p = params
    this.connected = false
    this.requests = 0
    this.responses = 0
    this.received = 0 // bytes received since the last request was sent
    this.socket = new net.Socket()
    // The library's own listeners come first: they are registered when the
    // client is created, here, before the adapter's.
    if (params.transport === 'rtu-over-tcp') {
      // ModbusRTUClient goes online on the 'open' event of a serial port (or
      // when `isOpen` is set). A net.Socket has 'connect' instead.
      this.client = new Modbus.client.RTU(this.socket, params.unitId, params.timeoutMs)
      this.socket.on('connect', () => this.socket.emit('open'))
    } else {
      this.client = new Modbus.client.TCP(this.socket, params.unitId, params.timeoutMs)
    }
    this.socket.on('data', (d) => { this.received += d.length })
    this.socket.on('error', (e) => { this.lastError = e })
    this.lost = new Promise((resolve) => this.socket.on('close', () => { this.closed = true; resolve() }))
    // A runtime error in the library's 'data' handler (it decodes there)
    // fails the pending request instead of the process.
    this.crashed = new Promise((resolve) => { this.crash = resolve })
    process.on('uncaughtException', (e) => this.crash(e))
  }

  connect () {
    const { host, port, connectTimeoutMs } = this.p
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.socket.destroy()
        reject(new Failure('connect-failed', `no connection to ${host}:${port} within ${connectTimeoutMs} ms`))
      }, connectTimeoutMs)
      const onError = (e) => {
        clearTimeout(timer)
        reject(new Failure('connect-failed', `cannot connect to ${host}:${port}: ${e.message}`))
      }
      this.socket.once('error', onError)
      this.socket.connect({ host, port }, () => {
        clearTimeout(timer)
        this.socket.removeListener('error', onError)
        this.socket.setNoDelay(true)
        this.connected = true
        resolve()
      })
    })
  }

  close () {
    this.socket.destroy()
  }

  /** Sends one request with `call` and returns the body of the normal response. */
  async exchange (fn, call) {
    if (this.closed) throw new Failure('connection-lost', 'the connection is closed')
    this.received = 0
    this.requests++
    const LOST = {}
    const pending = call()
    // The race below may leave it behind: its later rejection is not news.
    pending.catch(() => {})
    let result
    try {
      result = await Promise.race([
        pending,
        this.lost.then(() => LOST),
        this.crashed.then((e) => { throw new Failure('invalid-response', `the library failed on the response: ${e && e.message}`) })
      ])
    } catch (e) {
      throw this.failure(fn, e)
    }
    if (result === LOST) {
      throw new Failure('connection-lost', 'the connection closed before a response arrived')
    }
    const body = result && result.response && result.response.body
    if (!body) throw new Failure('failed', 'the library returned no response')
    if (body.fc !== fn) throw new Failure('invalid-response', `response has function ${body.fc}, request was function ${fn}`)
    this.responses++
    return body
  }

  /** The library's rejection as a contract error. */
  failure (fn, e) {
    if (e instanceof Failure) return e
    if (Modbus.errors.isUserRequestError(e)) {
      switch (e.err) {
        case 'ModbusException': {
          const body = e.response && e.response.body
          if (body.fc !== fn) {
            return new Failure('invalid-response', `exception response for function ${body.fc}, request was function ${fn}`)
          }
          return new DeviceException(body.fc + 0x80, body.code)
        }
        case 'Timeout':
          // The library drops a frame with another unit identifier, and
          // waits for more data on a frame it cannot decode: both end as a
          // timeout. Bytes that did arrive tell these apart from a device
          // that stayed silent.
          if (this.received) {
            return new Failure('invalid-response',
              `${this.received} byte(s) arrived but jsmodbus did not accept them as the response: ${e.message}`)
          }
          if (this.closed) return new Failure('connection-lost', `the connection closed during the operation: ${e.message}`)
          return new Failure('timeout', `no response within ${this.p.timeoutMs} ms`)
        case 'OutOfSync': case 'Protocol': case 'crcMismatch':
          return new Failure('invalid-response', `${e.err}: ${e.message}`)
        case 'Offline':
          return new Failure('connection-lost', `${e.err}: ${e.message}`)
        default:
          return new Failure('failed', `${e.err}: ${e.message}`)
      }
    }
    return new Failure('failed', `${e && e.name}: ${e && e.message}`)
  }

  check (condition, message) {
    if (!condition) {
      this.responses--
      throw new Failure('invalid-response', message)
    }
  }

  async perform (operation) {
    const p = this.p
    const c = this.client
    switch (operation) {
      case 'read-coils': case 'read-discrete-inputs': {
        const fn = operation === 'read-coils' ? 1 : 2
        const body = await this.exchange(fn, () => fn === 1 ? c.readCoils(p.address, p.quantity) : c.readDiscreteInputs(p.address, p.quantity))
        // The library hands out whole bytes.
        this.check(body.numberOfBytes === Math.ceil(p.quantity / 8), `${body.numberOfBytes} bytes for a quantity of ${p.quantity}`)
        return { function: fn, bits: Array.from(body.valuesAsArray).slice(0, p.quantity).map(Boolean) }
      }
      case 'read-holding-registers': case 'read-input-registers': {
        const fn = operation === 'read-holding-registers' ? 3 : 4
        const body = await this.exchange(fn, () => fn === 3 ? c.readHoldingRegisters(p.address, p.quantity) : c.readInputRegisters(p.address, p.quantity))
        const registers = Array.from(body.values)
        this.check(registers.length === p.quantity, `${registers.length} registers for a quantity of ${p.quantity}`)
        return { function: fn, registers }
      }
      case 'write-single-coil': {
        const body = await this.exchange(5, () => c.writeSingleCoil(p.address, p.value))
        // The library decodes the value as on (0xFF00) or off (anything else).
        this.check(body.address === p.address && body.value === p.value, 'the response does not echo the request')
        return { function: 5, address: body.address, value: body.value }
      }
      case 'write-single-register': {
        const body = await this.exchange(6, () => c.writeSingleRegister(p.address, p.value))
        this.check(body.address === p.address && body.value === p.value, 'the response does not echo the request')
        return { function: 6, address: body.address, value: body.value }
      }
      case 'write-multiple-coils': {
        const body = await this.exchange(15, () => c.writeMultipleCoils(p.address, p.values))
        this.check(body.start === p.address && body.quantity === p.values.length, 'the response does not echo the request')
        return { function: 15, address: body.start, quantity: body.quantity }
      }
      case 'write-multiple-registers': {
        const body = await this.exchange(16, () => c.writeMultipleRegisters(p.address, p.values))
        this.check(body.start === p.address && body.quantity === p.values.length, 'the response does not echo the request')
        return { function: 16, address: body.start, quantity: body.quantity }
      }
      case 'read-device-identification':
        return this.readDeviceIdentification()
    }
    throw new Failure('failed', `operation ${operation} is not implemented`)
  }

  /** FC43/14, stream access from object 0, following "more follows". */
  async readDeviceIdentification () {
    const code = CATEGORIES[this.p.category]
    const objects = []
    let conformity = 0
    let objectId = 0
    for (let i = 0; i < MAX_IDENTIFICATION_REQUESTS; i++) {
      const oid = objectId
      const body = await this.exchange(43, () => this.client.readDeviceIdentification(code, oid))
      this.check(body.meiType === 0x0E, `the response has MEI type ${body.meiType}`)
      this.check(body.readDeviceIdCode === code, `the response is for read code ${body.readDeviceIdCode}, request was ${code}`)
      conformity = body.conformityLevel
      for (const o of body.objects) objects.push({ id: o.id, value: o.value.toString('latin1') })
      if (body.moreFollows !== MORE_FOLLOWS) {
        objects.sort((a, b) => a.id - b.id)
        return { function: 43, conformityLevel: conformity, objects }
      }
      this.check(body.nextObjectId > objectId, '"more follows" does not advance')
      objectId = body.nextObjectId
    }
    throw new Failure('invalid-response', 'the device never ends the object stream')
  }
}

async function run (operation, params) {
  const started = process.hrtime.bigint()
  const session = new Session(params)
  let response = null
  let exception = null
  let error = null
  try {
    await session.connect()
    for (let i = 0; i < params.repeat; i++) {
      const current = await session.perform(operation)
      if (response !== null && JSON.stringify(current) !== JSON.stringify(response) && error === null) {
        error = { code: 'failed', message: 'the responses to the repeated request differ' }
      }
      response = current
    }
  } catch (e) {
    if (e instanceof DeviceException) {
      exception = { function: e.fn, code: e.code }
      error = { code: 'exception', message: `the device answered with exception ${e.code}` }
    } else if (e instanceof Failure) {
      error = { code: e.code, message: e.message }
    } else {
      error = { code: 'failed', message: `${e && e.name}: ${e && e.message}` }
    }
  } finally {
    session.close()
  }
  emit({
    schemaVersion: SCHEMA_VERSION,
    adapter: ADAPTER,
    operation,
    ok: error === null,
    error,
    connected: session.connected,
    unitId: params.unitId,
    requests: session.requests,
    responses: session.responses,
    // After a failure under --repeat: the last normal response, if any.
    response: exception ? null : response,
    exception,
    elapsedMs: Math.round(Number(process.hrtime.bigint() - started) / 1000) / 1000
  })
  if (error === null) return 0
  return error.code === 'connect-failed' ? 3 : 1
}

module.exports = { run }
