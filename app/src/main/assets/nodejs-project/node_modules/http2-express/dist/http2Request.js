"use strict";

/* eslint-disable dot-notation */
var _require = require('http2'),
  Http2ServerRequest = _require.Http2ServerRequest;
var createHttp2Request = function createHttp2Request(request) {
  var _Object$getOwnPropert;
  var http2Request = Object.create(Http2ServerRequest.prototype);

  // Copying properties and descriptors from request to http2Request.
  Object.getOwnPropertyNames(request).forEach(function (key) {
    var descriptor = Object.getOwnPropertyDescriptor(request, key);
    if (descriptor) {
      Object.defineProperty(http2Request, key, descriptor);
    }
  });

  // Retain symbols, if any, from request.
  Object.getOwnPropertySymbols(request).forEach(function (symbol) {
    var descriptor = Object.getOwnPropertyDescriptor(request, symbol);
    if (descriptor) {
      Object.defineProperty(http2Request, symbol, descriptor);
    }
  });
  var requestHostName = (_Object$getOwnPropert = Object.getOwnPropertyDescriptor(http2Request, 'hostname')) === null || _Object$getOwnPropert === void 0 ? void 0 : _Object$getOwnPropert.get;

  // Redefine hostname property with custom getter.
  if (requestHostName) {
    Object.defineProperty(http2Request, 'hostname', {
      get: function get() {
        if (!this.headers['host'] && this.authority) {
          this.headers['host'] = this.authority;
        }
        return requestHostName.call(this);
      }
    });
  }
  return http2Request;
};
module.exports = createHttp2Request;