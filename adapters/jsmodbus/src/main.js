// SPDX-License-Identifier: MIT
//
// Command line of the adapter: the container contract
// (docs/CONTAINER_CONTRACT.md).

'use strict'

const fs = require('fs')
const path = require('path')
const { ADAPTER, SCHEMA_VERSION, emit, stderr } = require('./common')

const FIXTURES_DIR = '/fixtures'
const BUILD_INFO = path.join(__dirname, '..', 'build-info.json')
const NUMBER = /^(0[xX][0-9a-fA-F]+|[0-9]+)$/
const FIXTURE_NAME = /^[a-z0-9][a-z0-9-]*$/
const TRUE = new Set(['true', '1', 'on'])
const FALSE = new Set(['false', '0', 'off'])

// The operations jsmodbus has a client method for.
const CLIENT_OPERATIONS = [
  'read-coils', 'read-discrete-inputs', 'read-holding-registers', 'read-input-registers',
  'write-single-coil', 'write-single-register', 'write-multiple-coils', 'write-multiple-registers',
  'read-device-identification'
]
// Every operation of the contract: the others are a usage error here.
const ALL_OPERATIONS = new Set([...CLIENT_OPERATIONS, 'mask-write-register', 'read-write-multiple-registers',
  'diagnostics', 'report-server-id', 'read-file-record', 'write-file-record', 'read-fifo-queue', 'raw'])
const TRANSPORTS = { server: ['tcp', 'rtu-over-tcp'], client: ['tcp', 'rtu-over-tcp'] }

const USAGE = `usage:
  modbus-interop server [--fixture PATH] [--bind-address ADDR] [--port N] [--ready-file PATH] [--transport T]
  modbus-interop client <operation> --host HOST [--port N] [--unit-id N] [--transport T]
                        [--connect-timeout-ms N] [--timeout-ms N] [--repeat N] [operation flags]
  modbus-interop print-capabilities
  modbus-interop print-fixture [NAME]
  modbus-interop version
transports: ${TRANSPORTS.server.join(', ')}
operations: ${CLIENT_OPERATIONS.join(', ')}`

class UsageError extends Error {}

function number (flag, text, low, high) {
  if (!NUMBER.test(text)) throw new UsageError(`${flag}: '${text}' is not a number`)
  const value = /^0[xX]/.test(text) ? parseInt(text.slice(2), 16) : parseInt(text, 10)
  if (!(value >= low && value <= high)) throw new UsageError(`${flag}: ${value} is not within ${low}..${high}`)
  return value
}

function boolean (flag, text) {
  const t = text.toLowerCase()
  if (TRUE.has(t)) return true
  if (FALSE.has(t)) return false
  throw new UsageError(`${flag}: '${text}' is not true/false, 1/0 or on/off`)
}

/** `--name value` pairs; anything else is a usage error. A flag given twice: the last one counts. */
function flags (args, known) {
  const result = {}
  for (let i = 0; i < args.length; i += 2) {
    const name = args[i]
    if (!name.startsWith('--') || !known.has(name.slice(2))) throw new UsageError(`unknown flag or argument '${name}'`)
    if (i + 1 >= args.length) throw new UsageError(`${name} needs a value`)
    result[name.slice(2)] = args[i + 1]
  }
  return result
}

function required (given, name) {
  if (!(name in given)) throw new UsageError(`--${name} is required`)
  return given[name]
}

function valueList (flag, text, convert, low, high) {
  const items = text ? text.split(',') : []
  if (items.length < low || items.length > high) throw new UsageError(`${flag}: ${items.length} values, allowed are ${low}..${high}`)
  return items.map((item) => convert(flag, item))
}

const register = (flag, text) => number(flag, text, 0, 65535)

function transport (given, role) {
  const t = given.transport === undefined ? 'tcp' : given.transport
  if (!TRANSPORTS[role].includes(t)) throw new UsageError(`--transport ${t}: this adapter has ${TRANSPORTS[role].join(', ')}`)
  return t
}

function buildInfo () {
  try {
    return JSON.parse(fs.readFileSync(BUILD_INFO, 'utf8'))
  } catch (e) {
    return {}
  }
}

function upstreamVersion () {
  return require('jsmodbus/package.json').version
}

function capabilities () {
  const info = buildInfo()
  return {
    schemaVersion: SCHEMA_VERSION,
    adapter: ADAPTER,
    adapterVersion: info.adapterVersion || 'dev',
    protocol: 'modbus-tcp',
    upstream: {
      name: 'jsmodbus',
      version: upstreamVersion(),
      revision: info.upstreamRevision || 'unknown',
      license: 'MIT',
      language: 'JavaScript',
      independentEngine: true
    },
    roles: { server: true, client: true },
    features: {
      // Device, in the library's request events.
      serverUnitIdFiltering: true,
      // The library's decoder drops a read of more than 2000 bits or
      // registers without an answer and stalls the connection (README).
      serverQuantityCheck: false,
      // The library has no FC22, FC23, FC08, FC17, FC20, FC21 or FC24.
      serverMaskWrite: false,
      serverReadWriteMultiple: false,
      serverDeviceIdentification: true,
      requestEvents: true,
      connectionEvents: true,
      clientMaskWrite: false,
      clientReadWriteMultiple: false,
      clientDeviceIdentification: true,
      // No public way to send a PDU the library has no request class for.
      clientRaw: false,
      clientRepeat: true,
      // A response with another unit identifier is dropped by the library,
      // and the request times out.
      clientUnitIdCheck: false,
      serverDiagnostics: false,
      serverReportServerId: false,
      serverFileRecord: false,
      serverFifoQueue: false,
      clientDiagnostics: false,
      clientReportServerId: false,
      clientReadFileRecord: false,
      clientWriteFileRecord: false,
      clientFifoQueue: false,
      clientRawAnyResponse: false,
      serverIllegalFunction: false,
      serverRejectedWriteUnchanged: true,
      clientLargestRegisterWrites: false,
      serverHighestAddress: true,
      // The RTU server frames FC16 by its capped byte count (README, finding 7).
      serverLargestRegisterWrites: false
    },
    serverFunctions: [1, 2, 3, 4, 5, 6, 15, 16, 43],
    clientOperations: CLIENT_OPERATIONS,
    transports: TRANSPORTS
  }
}

function commandServer (args) {
  const given = flags(args, new Set(['fixture', 'bind-address', 'port', 'ready-file', 'transport']))
  const opts = {
    transport: transport(given, 'server'),
    fixture: given.fixture !== undefined ? given.fixture : `${FIXTURES_DIR}/baseline/fixture.json`,
    bindAddress: given['bind-address'] !== undefined ? given['bind-address'] : '0.0.0.0',
    port: number('--port', given.port !== undefined ? given.port : '1502', 1, 65535),
    readyFile: given['ready-file'] !== undefined ? given['ready-file'] : '/run/modbus-interop/ready'
  }
  if (!opts.fixture || !opts.bindAddress || !opts.readyFile) throw new UsageError('empty value')
  return require('./server').run(opts)
}

const SPECIFIC = {
  'read-coils': ['address', 'quantity'],
  'read-discrete-inputs': ['address', 'quantity'],
  'read-holding-registers': ['address', 'quantity'],
  'read-input-registers': ['address', 'quantity'],
  'write-single-coil': ['address', 'value'],
  'write-single-register': ['address', 'value'],
  'write-multiple-coils': ['address', 'values'],
  'write-multiple-registers': ['address', 'values'],
  'read-device-identification': ['category']
}

/** Everything is validated before any network access. */
function clientParams (operation, args) {
  const common = ['host', 'port', 'unit-id', 'connect-timeout-ms', 'timeout-ms', 'repeat', 'transport']
  const given = flags(args, new Set([...common, ...SPECIFIC[operation]]))
  const host = required(given, 'host')
  if (!host) throw new UsageError('--host is empty')
  const p = {
    transport: transport(given, 'client'),
    host,
    port: number('--port', given.port ?? '1502', 1, 65535),
    unitId: number('--unit-id', given['unit-id'] ?? '1', 0, 255),
    connectTimeoutMs: number('--connect-timeout-ms', given['connect-timeout-ms'] ?? '5000', 1, 3600000),
    timeoutMs: number('--timeout-ms', given['timeout-ms'] ?? '5000', 1, 3600000),
    repeat: number('--repeat', given.repeat ?? '1', 1, 1000)
  }
  const address = () => number('--address', required(given, 'address'), 0, 65535)
  switch (operation) {
    case 'read-coils': case 'read-discrete-inputs':
      p.address = address()
      p.quantity = number('--quantity', required(given, 'quantity'), 1, 2000)
      break
    case 'read-holding-registers': case 'read-input-registers':
      p.address = address()
      p.quantity = number('--quantity', required(given, 'quantity'), 1, 125)
      break
    case 'write-single-coil':
      p.address = address()
      p.value = boolean('--value', required(given, 'value'))
      break
    case 'write-single-register':
      p.address = address()
      p.value = register('--value', required(given, 'value'))
      break
    case 'write-multiple-coils':
      p.address = address()
      p.values = valueList('--values', required(given, 'values'), boolean, 1, 1968)
      break
    case 'write-multiple-registers':
      p.address = address()
      p.values = valueList('--values', required(given, 'values'), register, 1, 123)
      break
    case 'read-device-identification': {
      const category = given.category !== undefined ? given.category : 'basic'
      if (!['basic', 'regular', 'extended'].includes(category)) {
        throw new UsageError(`--category: '${category}' is not basic, regular or extended`)
      }
      p.category = category
      break
    }
  }
  return p
}

function commandClient (args) {
  if (!args.length) throw new UsageError('client needs an operation')
  const operation = args[0]
  if (!ALL_OPERATIONS.has(operation)) throw new UsageError(`unknown operation '${operation}'`)
  if (!CLIENT_OPERATIONS.includes(operation)) {
    throw new UsageError(`operation '${operation}': jsmodbus has no client method for it`)
  }
  const params = clientParams(operation, args.slice(1))
  return require('./client').run(operation, params)
}

function commandPrintFixture (args) {
  if (args.length > 1) throw new UsageError('print-fixture takes at most one name')
  const name = args.length ? args[0] : 'baseline'
  if (!FIXTURE_NAME.test(name)) {
    stderr(`modbus-interop: no fixture named '${name}'`)
    return 1
  }
  let data
  try {
    data = fs.readFileSync(path.join(FIXTURES_DIR, name, 'fixture.json'))
  } catch (e) {
    stderr(`modbus-interop: no fixture named '${name}': ${e.message}`)
    return 1
  }
  process.stdout.write(data)
  return 0
}

async function main (argv) {
  try {
    if (!argv.length) throw new UsageError('a command is required')
    const [command, ...args] = argv
    switch (command) {
      case 'server': return await commandServer(args)
      case 'client': return await commandClient(args)
      case 'print-capabilities':
        if (args.length) throw new UsageError('print-capabilities takes no arguments')
        emit(capabilities())
        return 0
      case 'print-fixture': return commandPrintFixture(args)
      case 'version': {
        if (args.length) throw new UsageError('version takes no arguments')
        const info = buildInfo()
        process.stdout.write(`modbus-interop ${ADAPTER} ${info.adapterVersion || 'dev'} ` +
          `(jsmodbus ${upstreamVersion()}, ${info.upstreamRevision || 'unknown'})\n`)
        return 0
      }
    }
    throw new UsageError(`unknown command '${command}'`)
  } catch (e) {
    if (!(e instanceof UsageError)) throw e
    stderr(`modbus-interop: ${e.message}`)
    stderr(USAGE)
    return 2
  }
}

main(process.argv.slice(2)).then(
  (code) => process.exit(code),
  (e) => {
    stderr(`modbus-interop: ${e && e.stack ? e.stack : e}`)
    process.exit(1)
  })
