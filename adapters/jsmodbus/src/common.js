// SPDX-License-Identifier: MIT
'use strict'

const fs = require('fs')

const ADAPTER = 'jsmodbus'
const SCHEMA_VERSION = '1.0'

// stdout carries JSON only, one document per line. Node writes to files and
// pipes synchronously on Linux, so nothing is lost when the process exits
// right after a write.
function emit (document) {
  process.stdout.write(JSON.stringify(document) + '\n')
}

function stderr (line) {
  try { fs.writeSync(2, line + '\n') } catch (e) { /* nowhere to report it */ }
}

module.exports = { ADAPTER, SCHEMA_VERSION, emit, stderr }
