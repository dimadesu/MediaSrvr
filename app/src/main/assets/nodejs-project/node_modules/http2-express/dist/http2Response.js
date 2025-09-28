"use strict";

var _require = require('http2'),
  Http2ServerResponse = _require.Http2ServerResponse;
var createHttp2Response = function createHttp2Response(response) {
  var http2Response = Object.create(Http2ServerResponse.prototype);

  // Copying properties and descriptors from response to http2Response.
  Object.getOwnPropertyNames(response).forEach(function (key) {
    var descriptor = Object.getOwnPropertyDescriptor(response, key);
    if (descriptor) {
      Object.defineProperty(http2Response, key, descriptor);
    }
  });

  // Copy symbols from response to http2Response.
  Object.getOwnPropertySymbols(response).forEach(function (symbol) {
    var descriptor = Object.getOwnPropertyDescriptor(response, symbol);
    if (descriptor) {
      Object.defineProperty(http2Response, symbol, descriptor);
    }
  });
  return http2Response;
};
module.exports = createHttp2Response;