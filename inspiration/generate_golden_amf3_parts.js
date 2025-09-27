#!/usr/bin/env node
// Generate AMF3 golden parts using Node-Media-Server AMF helpers in the submodule.
// Writes files to inspiration/golden/

const fs = require('fs');
const path = require('path');
const core = require('./Node-Media-Server/src/node_core_amf.js');

const outDir = path.join(__dirname, 'golden');
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

function writeHex(name, buf) {
  const hex = Buffer.from(buf).toString('hex');
  fs.writeFileSync(path.join(outDir, name + '.hex'), hex);
  console.log('Wrote', name + '.hex', hex.length / 2, 'bytes');
}

const tests = [
  { name: 'amf3_int_1', value: 1 },
  { name: 'amf3_int_negative', value: -1 },
  { name: 'amf3_int_large', value: 1234567 },
  { name: 'amf3_double_pi', value: 3.14159 },
  { name: 'amf3_string_hello', value: 'hello' },
  { name: 'amf3_true', value: true },
  { name: 'amf3_false', value: false },
  { name: 'amf3_null', value: null },
  { name: 'amf3_undefined', value: undefined },
  { name: 'amf3_array_simple', value: [1, 'a', 2] },
  { name: 'amf3_object_simple', value: { a: 1, b: 'x' } },
  { name: 'amf3_bytearray', value: Buffer.from([0x01,0x02,0x03]) },
  { name: 'amf3_xml', value: { __amf3_xml__: '<root/>' } },
];

let succeeded = 0;
let failed = 0;

tests.forEach(t => {
  try {
    // Try amf3EncodeOne first
    let buf;
    if (typeof core.amf3EncodeOne === 'function') {
      buf = core.amf3EncodeOne(t.value);
    } else if (typeof core.amf3Encode === 'function') {
      buf = core.amf3Encode([t.value]);
    } else {
      throw new Error('No AMF3 encode function available');
    }
    if (!buf) throw new Error('Encoder returned undefined');
    writeHex(t.name, buf);
    succeeded++;
  } catch (e) {
    console.warn('Failed to encode', t.name, '-', e.message);
    failed++;
  }
});

console.log('Done. succeeded=', succeeded, 'failed=', failed);