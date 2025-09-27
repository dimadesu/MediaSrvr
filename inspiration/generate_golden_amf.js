#!/usr/bin/env node
// Generate golden AMF byte hex files using the Node-Media-Server AMF helpers in this repo.
// Usage: node inspiration/generate_golden_amf.js

const fs = require("fs");
const path = require("path");

const core = require("./Node-Media-Server/src/node_core_amf.js");

const outDir = path.join(__dirname, "golden");
if (!fs.existsSync(outDir)) fs.mkdirSync(outDir, { recursive: true });

function writeHex(name, buf) {
  const hex = Buffer.from(buf).toString("hex");
  fs.writeFileSync(path.join(outDir, name + ".hex"), hex);
  console.log("Wrote", name + ".hex", hex.length / 2, "bytes");
}

// _result AMF0 connect
const connectResult0 = core.encodeAmf0Cmd({
  cmd: "_result",
  transId: 1.0,
  cmdObj: null,
  info: {
    level: "status",
    code: "NetConnection.Connect.Success",
    description: "Connection succeeded.",
  },
});
writeHex("connect_result_amf0", connectResult0);

// _result AMF3 connect (cmd as AMF0 string + AMF3 values)
const enc3 = Buffer.concat([
  core.amf0encString("_result"),
  core.amf3encInteger(1),
  core.amf3encObject({ fmsVer: "FMS/3,5,7,7009", capabilities: 31 }),
  core.amf3encObject({
    level: "status",
    code: "NetConnection.Connect.Success",
    description: "Connection succeeded.",
  }),
]);
writeHex("connect_result_amf3", enc3);

// createStream result AMF0
const createStream0 = core.amf0Encode(["\u0000"]); // placeholder
// Instead produce: ['_result', transId, null, streamId]
const cs0 = Buffer.concat([
  core.amf0EncodeOne("_result"),
  core.amf0EncodeOne(2.0),
  core.amf0EncodeOne(null),
  core.amf0EncodeOne(42.0),
]);
writeHex("create_stream_result_amf0", cs0);

// onStatus AMF0
const onStatus0 = core.encodeAmf0Cmd({
  cmd: "onStatus",
  transId: 0.0,
  cmdObj: null,
  info: { level: "status", code: "NetStream.Play.Start" },
});
writeHex("onstatus_amf0", onStatus0);

// publish AMF0
const publish0 = core.encodeAmf0Cmd({
  cmd: "publish",
  transId: 0.0,
  cmdObj: null,
  streamName: "mystream",
  type: "live",
});
writeHex("publish_amf0", publish0);

console.log("Done. Golden files are in", outDir);
