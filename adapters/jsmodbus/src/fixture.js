// SPDX-License-Identifier: MIT
//
// Fixture loading and validation: the rules of
// fixtures/schema/modbus-fixture.schema.json and scripts/validate-fixtures.py,
// written out by hand so that the image needs no JSON Schema package.

'use strict'

const fs = require('fs')
const path = require('path')

const TABLES = ['coils', 'discreteInputs', 'holdingRegisters', 'inputRegisters']
const NAME = /^[a-z0-9][a-z0-9-]*$/
const PRINTABLE = /^[\x20-\x7e]*$/
const ROOT_KEYS = new Set(['schemaVersion', 'name', 'description', 'unitId', 'deviceIdentification',
  'reportServerId', 'fileRecords', 'fifoQueues', ...TABLES])
const MAX_FILE_RECORDS = 10000
const MAX_FIFO_VALUES = 31

class FixtureError extends Error {}

const isInt = (v) => Number.isInteger(v)
const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v)
const keysAre = (o, keys) => {
  const have = Object.keys(o).sort()
  const want = [...keys].sort()
  return have.length === want.length && have.every((k, i) => k === want[i])
}

function checkBlock (table, block, wantBool) {
  if (!isObject(block)) throw new FixtureError(`${table}: must be an object`)
  if (!keysAre(block, ['address', 'values'])) throw new FixtureError(`${table}: must have exactly 'address' and 'values'`)
  const { address, values } = block
  if (!isInt(address) || address < 0 || address > 65535) throw new FixtureError(`${table}: address must be an integer 0..65535`)
  if (!Array.isArray(values) || values.length < 1 || values.length > 65536) {
    throw new FixtureError(`${table}: values must be an array of 1..65536 elements`)
  }
  values.forEach((v, i) => {
    if (wantBool) {
      if (typeof v !== 'boolean') throw new FixtureError(`${table}: values[${i}] is not a boolean`)
    } else if (!isInt(v) || v < 0 || v > 65535) {
      throw new FixtureError(`${table}: values[${i}] is not an integer 0..65535`)
    }
  })
  const end = address + values.length - 1
  if (end > 65535) throw new FixtureError(`${table}: block ends at ${end}, past address 65535`)
  return { address, values: values.slice(), end: address + values.length }
}

function checkIdentification (ident) {
  if (!isObject(ident) || !keysAre(ident, ['objects'])) {
    throw new FixtureError("deviceIdentification: must be an object with exactly 'objects'")
  }
  const objects = ident.objects
  if (!Array.isArray(objects) || objects.length < 3) {
    throw new FixtureError('deviceIdentification: objects must be an array of at least 3 elements')
  }
  const result = new Map()
  const ids = []
  objects.forEach((obj, i) => {
    if (!isObject(obj) || !keysAre(obj, ['id', 'value'])) {
      throw new FixtureError(`deviceIdentification: objects[${i}] must have exactly 'id' and 'value'`)
    }
    const { id, value } = obj
    if (!isInt(id) || id < 0 || id > 255) throw new FixtureError(`deviceIdentification: objects[${i}].id must be an integer 0..255`)
    if (typeof value !== 'string' || value.length < 1 || value.length > 240 || !PRINTABLE.test(value)) {
      throw new FixtureError(`deviceIdentification: objects[${i}].value must be 1..240 printable ASCII characters`)
    }
    ids.push(id)
    result.set(id, value)
  })
  for (let i = 1; i < ids.length; i++) {
    if (ids[i] <= ids[i - 1]) throw new FixtureError('deviceIdentification: object ids must be unique and ascending')
  }
  for (const basic of [0, 1, 2]) {
    if (!result.has(basic)) throw new FixtureError(`deviceIdentification: basic object ${basic} is missing`)
  }
  for (const id of ids) {
    if (id >= 7 && id <= 127) throw new FixtureError(`deviceIdentification: object id ${id} is reserved`)
  }
  return result
}

function registers (where, values, low, high) {
  if (!Array.isArray(values) || values.length < low || values.length > high) {
    throw new FixtureError(`${where}: must be an array of ${low}..${high} elements`)
  }
  values.forEach((v, i) => {
    if (!isInt(v) || v < 0 || v > 65535) throw new FixtureError(`${where}[${i}] is not an integer 0..65535`)
  })
  return values.slice()
}

function checkServerId (value) {
  if (!isObject(value) || !keysAre(value, ['serverId', 'runIndicator'])) {
    throw new FixtureError("reportServerId: must be an object with exactly 'serverId' and 'runIndicator'")
  }
  if (!isInt(value.serverId) || value.serverId < 0 || value.serverId > 255) {
    throw new FixtureError('reportServerId: serverId must be an integer 0..255')
  }
  if (typeof value.runIndicator !== 'boolean') throw new FixtureError('reportServerId: runIndicator must be a boolean')
  return { serverId: value.serverId, runIndicator: value.runIndicator }
}

function checkFiles (value) {
  if (!Array.isArray(value) || value.length === 0) throw new FixtureError('fileRecords: must be an array of at least 1 element')
  const result = new Map()
  value.forEach((item, i) => {
    if (!isObject(item) || !keysAre(item, ['file', 'records'])) {
      throw new FixtureError(`fileRecords[${i}]: must have exactly 'file' and 'records'`)
    }
    if (!isInt(item.file) || item.file < 1 || item.file > 65535) throw new FixtureError(`fileRecords[${i}].file must be an integer 1..65535`)
    if (result.has(item.file)) throw new FixtureError('fileRecords: file numbers must be unique')
    result.set(item.file, registers(`fileRecords[${i}].records`, item.records, 1, MAX_FILE_RECORDS))
  })
  return result
}

function checkFifos (value) {
  if (!Array.isArray(value) || value.length === 0) throw new FixtureError('fifoQueues: must be an array of at least 1 element')
  const result = new Map()
  value.forEach((item, i) => {
    if (!isObject(item) || !keysAre(item, ['address', 'values'])) {
      throw new FixtureError(`fifoQueues[${i}]: must have exactly 'address' and 'values'`)
    }
    if (!isInt(item.address) || item.address < 0 || item.address > 65535) {
      throw new FixtureError(`fifoQueues[${i}].address must be an integer 0..65535`)
    }
    if (result.has(item.address)) throw new FixtureError('fifoQueues: addresses must be unique')
    result.set(item.address, registers(`fifoQueues[${i}].values`, item.values, 0, MAX_FIFO_VALUES))
  })
  return result
}

/** Load and validate a fixture; throws FixtureError. */
function load (file) {
  let doc
  try {
    doc = JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (e) {
    throw new FixtureError(`${file}: ${e.message}`)
  }
  if (!isObject(doc)) throw new FixtureError('the fixture must be a JSON object')
  const unknown = Object.keys(doc).filter((k) => !ROOT_KEYS.has(k)).sort()
  if (unknown.length) throw new FixtureError(`unknown field(s): ${unknown.join(', ')}`)
  if (doc.schemaVersion !== '1.0') throw new FixtureError('schemaVersion must be "1.0"')
  if (typeof doc.name !== 'string' || !NAME.test(doc.name)) throw new FixtureError('name must match ^[a-z0-9][a-z0-9-]*$')
  if ('description' in doc && typeof doc.description !== 'string') throw new FixtureError('description must be a string')
  if (!isInt(doc.unitId) || doc.unitId < 1 || doc.unitId > 247) throw new FixtureError('unitId must be an integer 1..247')
  let directory
  try {
    directory = path.basename(path.dirname(fs.realpathSync(file)))
  } catch (e) {
    throw new FixtureError(`${file}: ${e.message}`)
  }
  if (doc.name !== directory) throw new FixtureError(`name '${doc.name}' is not the directory name '${directory}'`)

  const fixture = {
    name: doc.name,
    unitId: doc.unitId,
    tables: {},
    objects: null, // Map id -> string, ascending; null: no device identification
    serverId: null,
    files: new Map(),
    fifos: new Map()
  }
  for (const table of TABLES) {
    if (table in doc) fixture.tables[table] = checkBlock(table, doc[table], table === 'coils' || table === 'discreteInputs')
  }
  if ('deviceIdentification' in doc) fixture.objects = checkIdentification(doc.deviceIdentification)
  if ('reportServerId' in doc) fixture.serverId = checkServerId(doc.reportServerId)
  if ('fileRecords' in doc) fixture.files = checkFiles(doc.fileRecords)
  if ('fifoQueues' in doc) fixture.fifos = checkFifos(doc.fifoQueues)
  return fixture
}

/** Highest category the fixture has objects for: 1 basic, 2 regular, 3 extended. */
function conformityLevel (objects) {
  const ids = [...objects.keys()]
  if (ids.some((i) => i >= 0x80)) return 3
  if (ids.some((i) => i >= 3 && i <= 6)) return 2
  return 1
}

module.exports = { load, conformityLevel, FixtureError }
