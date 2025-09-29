const fs = require("fs");
const path = require("path");
const NodeMediaServer = require("node-media-server");

// Log file path inside the node project directory
const LOG_FILENAME = "nms.log";
const LOG_PATH = path.join(__dirname, LOG_FILENAME);

function appendLog(level, msg) {
  try {
    const line = String(msg) + "\n";
    fs.appendFile(LOG_PATH, line, (err) => {
      /* swallow */
    });
  } catch (e) {}
}

// Wrap console methods to capture logs to file
["log", "info", "warn", "error", "debug"].forEach((m) => {
  const orig = console[m] || console.log;
  console[m] = function () {
    try {
      const args = Array.prototype.slice
        .call(arguments)
        .map((a) => (typeof a === "object" ? JSON.stringify(a) : String(a)))
        .join(" ");
      appendLog(m, args);
    } catch (e) {}
    orig.apply(console, arguments);
  };
});

// Optionally clear previous log on start
try {
  fs.writeFileSync(LOG_PATH, "");
} catch (e) {}

const config = {
  rtmp: {
    port: 1935,
    chunk_size: 60000,
    gop_cache: true,
    ping: 30,
    ping_timeout: 60,
  },
};

var nms = new NodeMediaServer(config);
nms.run();
