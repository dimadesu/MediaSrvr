const { isHttp2Request, setPoweredBy } = require("./util");

const initHttp2Express = (app) => (req, res, next) => {
  setPoweredBy(app, res);
  req.res = res;
  res.req = req;
  req.next = next;

  if (isHttp2Request(req)) {
    Object.setPrototypeOf(req, app.http2Request);
    Object.setPrototypeOf(res, app.http2Response);
  } else {
    Object.setPrototypeOf(req, app.request);
    Object.setPrototypeOf(res, app.response);
  }

  res.locals = res.locals || Object.create(null);
  next();
};

module.exports = initHttp2Express;
