/* eslint-disable func-names */
/* eslint-disable no-underscore-dangle */
const EventEmitter = require('events');

const createHttp2Request = require('./http2Request');
const createHttp2Response = require('./http2Response');
const initHttp2Express = require('./initHttp2Express');
const { setPoweredBy, isHttp2Request } = require('./util');

const createHttp2Express = (express) => {
  const {
    application,
    request,
    response,
    Router,
    query
  } = express;

  const isExpress5 = !application.lazyrouter;

  if (isExpress5) {
    const originalInit = application.init;
    application.init = function init() {
      originalInit.call(this);
      // at this point we can use the router
      this.router.use((req, res, next) => {
        setPoweredBy(this, res);
        next();
      });
    }
  } else {
    application.lazyrouter = function lazyrouter() {
      if (!this._router) {
        this._router = new Router({
          caseSensitive: this.enabled('case sensitive routing'),
          strict: this.enabled('strict routing')
        });
        this._router.use(query(this.get('query parser fn')));
        this._router.use(initHttp2Express(this));
      }
    };
  }

  let oneshotRequestOverride = null;
  let oneshotResponseOverride = null;

  const app = function (req, res, next) {
    if (isExpress5 && isHttp2Request(req)) {
      // In Express 5, setting the prototype of req/res is done synchronously in the app.handle function,
      // so this is the right place to change the value of app.request/app.response.
      oneshotRequestOverride = app.http2Request;
      oneshotResponseOverride = app.http2Response;
    }
    app.handle(req, res, next);
  };

  // Copying properties and descriptors from EventEmitter.prototype to app.
  Object.getOwnPropertyNames(EventEmitter.prototype).forEach((key) => {
    const descriptor = Object.getOwnPropertyDescriptor(EventEmitter.prototype, key);
    if (descriptor) {
      Object.defineProperty(app, key, descriptor);
    }
  });

  // Copy symbols from EventEmitter.prototype to app.
  Object.getOwnPropertySymbols(EventEmitter.prototype).forEach((symbol) => {
    const descriptor = Object.getOwnPropertyDescriptor(EventEmitter.prototype, symbol);
    if (descriptor) {
      Object.defineProperty(app, symbol, descriptor);
    }
  });

  // Copying properties from application to app.
  Object.getOwnPropertyNames(application).forEach((key) => {
    const descriptor = Object.getOwnPropertyDescriptor(application, key);
    if (descriptor) {
      Object.defineProperty(app, key, descriptor);
    }
  });

  // Copy symbols from application to app.
  Object.getOwnPropertySymbols(application).forEach((symbol) => {
    const descriptor = Object.getOwnPropertyDescriptor(application, symbol);
    if (descriptor) {
      Object.defineProperty(app, symbol, descriptor);
    }
  });

  response.push = () => {};

  app._request = Object.create(request, {
    app: {
      configurable: true,
      enumerable: true,
      writable: true,
      value: app
    }
  });

  app._response = Object.create(response, {
    app: {
      configurable: true,
      enumerable: true,
      writable: true,
      value: app
    }
  });

  const http2Request = createHttp2Request(request);
  const http2Response = createHttp2Response(response);

  app.http2Request = Object.create(http2Request, {
    app: {
      configurable: true,
      enumerable: true,
      writable: true,
      value: app
    }
  });

  app.http2Response = Object.create(http2Response, {
    app: {
      configurable: true,
      enumerable: true,
      writable: true,
      value: app
    }
  });

  // Expose the prototype that will get set on requests.
  Object.defineProperty(app, 'request', {
    configurable: true,
    enumerable: true,
    get() {
      if (oneshotRequestOverride) {
        const req = oneshotRequestOverride;
        oneshotRequestOverride = null;
        return req;
      }
      return app._request;
    },
    set: (val) => {
      app._request = val;
    }
  });

  // Expose the prototype that will get set on responses.
  Object.defineProperty(app, 'response', {
    configurable: true,
    enumerable: true,
    get() {
      if (oneshotResponseOverride) {
        const res = oneshotResponseOverride;
        oneshotResponseOverride = null;
        return res;
      }
      return app._response;
    },
    set: (val) => {
      app._response = val;
    }
  });

  app.init();
  return app;
};

module.exports = createHttp2Express;
