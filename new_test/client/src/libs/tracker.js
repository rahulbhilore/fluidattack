//! TrackJS JavaScript error monitoring agent.
//! COPYRIGHT (c) 2017 ALL RIGHTS RESERVED
//! See License at https://trackjs.com/terms/
(function(k, n, m) {
  "use awesome";

  if (k.trackJs)
    k.console && k.console.warn && k.console.warn("TrackJS global conflict");
  else {
    let p = function(a, b, c, d, e) {
      this.util = a;
      this.onError = b;
      this.onFault = c;
      this.options = e;
      e.enabled && this.initialize(d);
    };
    p.prototype = {
      initialize(a) {
        f.forEach(
          ["EventTarget", "Node", "XMLHttpRequest"],
          function(b) {
            f.has(a, `${b}.prototype.addEventListener`) &&
              ((b = a[b].prototype),
              b.hasOwnProperty("addEventListener") &&
                (this.wrapAndCatch(b, "addEventListener", 1),
                this.wrapRemoveEventListener(b)));
          },
          this
        );
        this.wrapAndCatch(a, "setTimeout", 0);
        this.wrapAndCatch(a, "setInterval", 0);
      },
      wrapAndCatch(a, b, c) {
        const d = this;
        const e = a[b];
        f.isWrappableFunction(e) &&
          (a[b] = function() {
            try {
              const g = Array.prototype.slice.call(arguments);
              const l = g[c];
              let h;
              let y;
              if (d.options.bindStack)
                try {
                  throw Error();
                } catch (k) {
                  (y = k.stack), (h = d.util.isoNow());
                }
              const F = function() {
                try {
                  if (f.isObject(l)) return l.handleEvent(...arguments);
                  if (f.isFunction(l)) return l.apply(this, arguments);
                } catch (a) {
                  throw (d.onError("catch", a, { bindTime: h, bindStack: y }),
                  f.wrapError(a));
                }
              };
              if (
                b === "addEventListener" &&
                (this._trackJsEvt || (this._trackJsEvt = new q()),
                this._trackJsEvt.get(g[0], l, g[2]))
              )
                return;
              try {
                l &&
                  (f.isWrappableFunction(l) ||
                    f.isWrappableFunction(l.handleEvent)) &&
                  ((g[c] = F),
                  b === "addEventListener" &&
                    this._trackJsEvt.add(g[0], l, g[2], g[c]));
              } catch (k) {
                return e.apply(this, arguments);
              }
              return e.apply(this, g);
            } catch (k) {
              (a[b] = e), d.onFault(k);
            }
          });
      },
      wrapRemoveEventListener(a) {
        if (
          a &&
          a.removeEventListener &&
          this.util.hasFunction(a.removeEventListener, "call")
        ) {
          const b = a.removeEventListener;
          a.removeEventListener = function(a, d, e) {
            if (this._trackJsEvt) {
              const g = this._trackJsEvt.get(a, d, e);
              if (g)
                return this._trackJsEvt.remove(a, d, e), b.call(this, a, g, e);
            }
            return b.call(this, a, d, e);
          };
        }
      }
    };
    var q = function() {
      this.events = [];
    };
    q.prototype = {
      add(a, b, c, d) {
        this.indexOf(a, b, c) <= -1 &&
          ((c = this.getEventOptions(c)),
          this.events.push([a, b, c.capture, c.once, c.passive, d]));
      },
      get(a, b, c) {
        a = this.indexOf(a, b, c);
        return a >= 0 ? this.events[a][5] : m;
      },
      getEventOptions(a) {
        const b = { capture: !1, once: !1, passive: !1 };
        return f.isBoolean(a)
          ? f.defaults({}, { capture: a }, b)
          : f.defaults({}, a, b);
      },
      indexOf(a, b, c) {
        c = this.getEventOptions(c);
        for (let d = 0; d < this.events.length; d++) {
          const e = this.events[d];
          if (
            e[0] === a &&
            e[1] === b &&
            e[2] === c.capture &&
            e[3] === c.once &&
            e[4] === c.passive
          )
            return d;
        }
        return -1;
      },
      remove(a, b, c) {
        a = this.indexOf(a, b, c);
        a >= 0 && this.events.splice(a, 1);
      }
    };
    const t = function(a) {
      this.initCurrent(a);
    };
    t.prototype = {
      current: {},
      initOnly: {
        cookie: !0,
        enabled: !0,
        token: !0,
        callback: { enabled: !0 },
        console: { enabled: !0 },
        navigation: { enabled: !0 },
        network: { enabled: !0, fetch: !0 },
        visitor: { enabled: !0 },
        window: { enabled: !0, promise: !0 }
      },
      defaults: {
        application: "",
        cookie: !1,
        dedupe: !0,
        enabled: !0,
        errorURL: "https://capture.trackjs.com/capture",
        errorNoSSLURL: "http://capture.trackjs.com/capture",
        faultURL: "https://usage.trackjs.com/fault.gif",
        onError() {
          return !0;
        },
        serialize(a) {
          function b(a) {
            let c = `<${a.tagName.toLowerCase()}`;
            a = a.attributes || [];
            for (let b = 0; b < a.length; b++)
              c += ` ${a[b].name}="${a[b].value}"`;
            return `${c}>`;
          }
          if (a === "") return "Empty String";
          if (a === m) return "undefined";
          if (
            f.isString(a) ||
            f.isNumber(a) ||
            f.isBoolean(a) ||
            f.isFunction(a)
          )
            return `${a}`;
          if (f.isElement(a)) return b(a);
          let c;
          try {
            c = JSON.stringify(a, (a, c) =>
              c === m
                ? "undefined"
                : f.isNumber(c) && isNaN(c)
                ? "NaN"
                : f.isError(c)
                ? { name: c.name, message: c.message, stack: c.stack }
                : f.isElement(c)
                ? b(c)
                : c
            );
          } catch (e) {
            c = "";
            for (const d in a)
              a.hasOwnProperty(d) && (c += `,"${d}":"${a[d]}"`);
            c = c ? `{${c.replace(",", "")}}` : "Unserializable Object";
          }
          return c
            .replace(/"undefined"/g, "undefined")
            .replace(/"NaN"/g, "NaN");
        },
        sessionId: "",
        token: "",
        userId: "",
        version: "",
        callback: { enabled: !0, bindStack: !1 },
        console: {
          enabled: !0,
          display: !0,
          error: !0,
          warn: !1,
          watch: ["log", "debug", "info", "warn", "error"]
        },
        navigation: { enabled: !0 },
        network: { enabled: !0, error: !0, fetch: !0 },
        visitor: { enabled: !0 },
        usageURL: "https://usage.trackjs.com/usage.gif",
        window: { enabled: !0, promise: !0 }
      },
      initCurrent(a) {
        if (this.validate(a, this.defaults, "config", {}))
          return (this.current = f.defaultsDeep({}, a, this.defaults)), !0;
        this.current = f.defaultsDeep({}, this.defaults);
        console.log("init current config", this.current);
        return !1;
      },
      setCurrent(a) {
        return this.validate(a, this.defaults, "config", this.initOnly)
          ? ((this.current = f.defaultsDeep({}, a, this.current)), !0)
          : !1;
      },
      validate(a, b, c, d) {
        let e = !0;
        c = c || "";
        d = d || {};
        for (const g in a) {
          if (a.hasOwnProperty(g)) {
            if (b.hasOwnProperty(g)) {
              const f = typeof b[g];
              f !== typeof a[g]
                ? (console.warn(`${c}.${g}: property must be type ${f}.`),
                  (e = !1))
                : Object.prototype.toString.call(a[g]) !== "[object Array]" ||
                  this.validateArray(a[g], b[g], `${c}.${g}`)
                ? Object.prototype.toString.call(a[g]) === "[object Object]"
                  ? (e = this.validate(a[g], b[g], `${c}.${g}`, d[g]))
                  : d.hasOwnProperty(g) &&
                    (console.warn(
                      `${c}.${g}: property cannot be set after load.`
                    ),
                    (e = !1))
                : (e = !1);
            } else console.warn(`${c}.${g}: property not supported.`), (e = !1);
          }
        }
        return e;
      },
      validateArray(a, b, c) {
        let d = !0;
        c = c || "";
        for (let e = 0; e < a.length; e++)
          f.contains(b, a[e]) ||
            (console.warn(`${c}[${e}]: invalid value: ${a[e]}.`), (d = !1));
        return d;
      }
    };
    const u = function(a, b, c, d, e, g, f) {
      this.util = a;
      this.log = b;
      this.onError = c;
      this.onFault = d;
      this.serialize = e;
      f.enabled && (g.console = this.wrapConsoleObject(g.console, f));
    };
    u.prototype = {
      wrapConsoleObject(a, b) {
        a = a || {};
        const c = a.log || function() {};
        const d = this;
        let e;
        for (e = 0; e < b.watch.length; e++) {
          (function(e) {
            const l = a[e] || c;
            a[e] = function() {
              try {
                const a = Array.prototype.slice.call(arguments);
                d.log.add("c", {
                  timestamp: d.util.isoNow(),
                  severity: e,
                  message: d.serialize(a.length === 1 ? a[0] : a)
                });
                if (b[e]) {
                  if (f.isError(a[0]) && a.length === 1)
                    d.onError("console", a[0]);
                  else {
                    try {
                      throw Error(d.serialize(a.length === 1 ? a[0] : a));
                    } catch (c) {
                      d.onError("console", c);
                    }
                  }
                }
                b.display &&
                  (d.util.hasFunction(l, "apply") ? l.apply(this, a) : l(a[0]));
              } catch (c) {
                d.onFault(c);
              }
            };
          })(b.watch[e]);
        }
        return a;
      },
      report() {
        return this.log.all("c");
      }
    };
    const v = function(a, b, c, d, e) {
      this.config = a;
      this.util = b;
      this.log = c;
      this.window = d;
      this.document = e;
      this.correlationId = this.token = null;
      this.initialize();
    };
    v.prototype = {
      initialize() {
        this.token = this.getCustomerToken();
        this.correlationId = this.getCorrelationId();
      },
      getCustomerToken() {
        if (this.config.current.token) return this.config.current.token;
        const a = this.document.getElementsByTagName("script");
        return a[a.length - 1].getAttribute("data-token");
      },
      getCorrelationId() {
        let a;
        if (!this.config.current.cookie) return this.util.uuid();
        try {
          (a = this.document.cookie.replace(
            /(?:(?:^|.*;\s*)TrackJS\s*\=\s*([^;]*).*$)|^.*$/,
            "$1"
          )),
            a ||
              ((a = this.util.uuid()),
              (this.document.cookie = `TrackJS=${a}; expires=Fri, 31 Dec 9999 23:59:59 GMT; path=/`));
        } catch (b) {
          a = this.util.uuid();
        }
        return a;
      },
      report() {
        return {
          application: this.config.current.application,
          correlationId: this.correlationId,
          sessionId: this.config.current.sessionId,
          token: this.token,
          userId: this.config.current.userId,
          version: this.config.current.version
        };
      }
    };
    const w = function(a) {
      this.loadedOn = new Date().getTime();
      this.window = a;
    };
    w.prototype = {
      discoverDependencies() {
        let a;
        const b = {};
        this.window.jQuery &&
          this.window.jQuery.fn &&
          this.window.jQuery.fn.jquery &&
          (b.jQuery = this.window.jQuery.fn.jquery);
        this.window.jQuery &&
          this.window.jQuery.ui &&
          this.window.jQuery.ui.version &&
          (b.jQueryUI = this.window.jQuery.ui.version);
        this.window.angular &&
          this.window.angular.version &&
          this.window.angular.version.full &&
          (b.angular = this.window.angular.version.full);
        for (a in this.window)
          if (
            a !== "_trackJs" &&
            a !== "_trackJS" &&
            a !== "_trackjs" &&
            a !== "webkitStorageInfo" &&
            a !== "webkitIndexedDB" &&
            a !== "top" &&
            a !== "parent" &&
            a !== "frameElement"
          )
            try {
              if (this.window[a]) {
                const c =
                  this.window[a].version ||
                  this.window[a].Version ||
                  this.window[a].VERSION;
                typeof c === "string" && (b[a] = c);
              }
            } catch (d) {}
        return b;
      },
      report() {
        return {
          age: new Date().getTime() - this.loadedOn,
          dependencies: this.discoverDependencies(),
          userAgent: this.window.navigator.userAgent,
          viewportHeight: this.window.document.documentElement.clientHeight,
          viewportWidth: this.window.document.documentElement.clientWidth
        };
      }
    };
    const z = function(a) {
      this.util = a;
      this.appender = [];
      this.maxLength = 30;
    };
    z.prototype = {
      all(a) {
        const b = [];
        let c;
        let d;
        for (d = 0; d < this.appender.length; d++)
          (c = this.appender[d]) && c.category === a && b.push(c.value);
        return b;
      },
      clear() {
        this.appender.length = 0;
      },
      truncate() {
        this.appender.length > this.maxLength &&
          (this.appender = this.appender.slice(
            Math.max(this.appender.length - this.maxLength, 0)
          ));
      },
      add(a, b) {
        const c = this.util.uuid();
        this.appender.push({ key: c, category: a, value: b });
        this.truncate();
        return c;
      },
      get(a, b) {
        let c;
        let d;
        for (d = 0; d < this.appender.length; d++)
          if (((c = this.appender[d]), c.category === a && c.key === b))
            return c.value;
        return !1;
      }
    };
    const A = function(a, b) {
      this.log = a;
      this.options = b;
      b.enabled && this.watch();
    };
    A.prototype = {
      isCompatible(a) {
        a = a || k;
        return (
          !f.has(a, "chrome.app.runtime") &&
          f.has(a, "addEventListener") &&
          f.has(a, "history.pushState")
        );
      },
      record(a, b, c) {
        this.log.add("h", {
          type: a,
          from: f.truncate(b, 250),
          to: f.truncate(c, 250),
          on: f.isoNow()
        });
      },
      report() {
        return this.log.all("h");
      },
      watch() {
        if (this.isCompatible()) {
          const a = this;
          let b = f.getLocationURL().relative;
          k.addEventListener(
            "popstate",
            () => {
              const c = f.getLocationURL().relative;
              a.record("popState", b, c);
              b = c;
            },
            !0
          );
          f.forEach(["pushState", "replaceState"], c => {
            f.patch(
              history,
              c,
              d =>
                function() {
                  b = f.getLocationURL().relative;
                  const e = d.apply(this, arguments);
                  const g = f.getLocationURL().relative;
                  a.record(c, b, g);
                  b = g;
                  return e;
                }
            );
          });
        }
      }
    };
    const B = function(a, b, c, d, e, g) {
      this.util = a;
      this.log = b;
      this.onError = c;
      this.onFault = d;
      this.window = e;
      this.options = g;
      g.enabled && this.initialize(e);
    };
    B.prototype = {
      initialize(a) {
        a.XMLHttpRequest &&
          this.util.hasFunction(a.XMLHttpRequest.prototype.open, "apply") &&
          this.watchNetworkObject(a.XMLHttpRequest);
        a.XDomainRequest &&
          this.util.hasFunction(a.XDomainRequest.prototype.open, "apply") &&
          this.watchNetworkObject(a.XDomainRequest);
        this.options.fetch &&
          f.isWrappableFunction(a.fetch) &&
          this.watchFetch();
      },
      watchFetch() {
        const a = this.log;
        const b = this.options;
        const c = this.onError;
        f.patch(
          k,
          "fetch",
          d =>
            function(e, g) {
              const l = e instanceof Request ? e : new Request(e, g);
              const h = d.apply(k, arguments);
              h._tjsId = a.add("n", {
                type: "fetch",
                startedOn: f.isoNow(),
                method: l.method,
                url: l.url
              });
              return h
                .then(d => {
                  const e = a.get("n", h._tjsId);
                  e &&
                    (f.defaults(e, {
                      completedOn: f.isoNow(),
                      statusCode: d.status,
                      statusText: d.statusText
                    }),
                    b.error &&
                      d.status >= 400 &&
                      c(
                        "ajax",
                        `${e.statusCode} ${e.statusText}: ${e.method} ${e.url}`
                      ));
                  return d;
                })
                .catch(d => {
                  const e = a.get("n", h._tjsId);
                  e &&
                    (f.defaults(e, {
                      completedOn: f.isoNow(),
                      statusCode: 0,
                      statusText: (d || "").toString()
                    }),
                    b.error && c("ajax", d));
                  throw d;
                });
            }
        );
      },
      watchNetworkObject(a) {
        const b = this;
        const c = a.prototype.open;
        const d = a.prototype.send;
        a.prototype.open = function(a, b) {
          const d = (b || "").toString();
          d.indexOf("localhost:0") < 0 &&
            (this._trackJs = { method: a, url: d });
          return c.apply(this, arguments);
        };
        a.prototype.send = function() {
          try {
            if (!this._trackJs) return d.apply(this, arguments);
            this._trackJs.logId = b.log.add("n", {
              type: "xhr",
              startedOn: b.util.isoNow(),
              method: this._trackJs.method,
              url: this._trackJs.url
            });
            b.listenForNetworkComplete(this);
          } catch (a) {
            b.onFault(a);
          }
          return d.apply(this, arguments);
        };
        return a;
      },
      listenForNetworkComplete(a) {
        const b = this;
        b.window.ProgressEvent &&
          a.addEventListener &&
          a.addEventListener(
            "readystatechange",
            () => {
              a.readyState === 4 && b.finalizeNetworkEvent(a);
            },
            !0
          );
        a.addEventListener
          ? a.addEventListener(
              "load",
              () => {
                b.finalizeNetworkEvent(a);
                b.checkNetworkFault(a);
              },
              !0
            )
          : setTimeout(() => {
              try {
                const c = a.onload;
                a.onload = function() {
                  b.finalizeNetworkEvent(a);
                  b.checkNetworkFault(a);
                  typeof c === "function" &&
                    b.util.hasFunction(c, "apply") &&
                    c.apply(a, arguments);
                };
                const d = a.onerror;
                a.onerror = function() {
                  b.finalizeNetworkEvent(a);
                  b.checkNetworkFault(a);
                  typeof oldOnError === "function" && d.apply(a, arguments);
                };
              } catch (e) {
                b.onFault(e);
              }
            }, 0);
      },
      finalizeNetworkEvent(a) {
        if (a._trackJs) {
          const b = this.log.get("n", a._trackJs.logId);
          b &&
            ((b.completedOn = this.util.isoNow()),
            (b.statusCode = a.status == 1223 ? 204 : a.status),
            (b.statusText = a.status == 1223 ? "No Content" : a.statusText));
        }
      },
      checkNetworkFault(a) {
        if (this.options.error && a.status >= 400 && a.status != 1223) {
          const b = a._trackJs || {};
          this.onError(
            "ajax",
            `${a.status} ${a.statusText}: ${b.method} ${b.url}`
          );
        }
      },
      report() {
        return this.log.all("n");
      }
    };
    const x = function(a, b) {
      this.util = a;
      this.config = b;
      this.disabled = !1;
      this.throttleStats = {
        attemptCount: 0,
        throttledCount: 0,
        lastAttempt: new Date().getTime()
      };
      (k.JSON && k.JSON.stringify) || (this.disabled = !0);
    };
    x.prototype = {
      errorEndpoint(a) {
        let b = this.config.current.errorURL;
        this.util.testCrossdomainXhr() ||
          k.location.protocol.indexOf("https") !== -1 ||
          (b = this.config.current.errorNoSSLURL);
        return `${b}?token=${a}`;
      },
      usageEndpoint(a) {
        return this.appendObjectAsQuery(a, this.config.current.usageURL);
      },
      trackerFaultEndpoint(a) {
        return this.appendObjectAsQuery(a, this.config.current.faultURL);
      },
      appendObjectAsQuery(a, b) {
        b += "?";
        for (const c in a) {
          a.hasOwnProperty(c) &&
            (b += `${encodeURIComponent(c)}=${encodeURIComponent(a[c])}&`);
        }
        return b;
      },
      getCORSRequest(a, b) {
        let c;
        this.util.testCrossdomainXhr()
          ? ((c = new k.XMLHttpRequest()),
            c.open(a, b),
            c.setRequestHeader("Content-Type", "text/plain"))
          : typeof k.XDomainRequest !== "undefined"
          ? ((c = new k.XDomainRequest()), c.open(a, b))
          : (c = null);
        return c;
      },
      sendTrackerFault(a) {
        this.throttle(a) || (new Image().src = this.trackerFaultEndpoint(a));
      },
      sendUsage(a) {
        new Image().src = this.usageEndpoint(a);
      },
      sendError(a, b) {
        const c = this;
        if (!this.disabled && !this.throttle(a)) {
          try {
            const d = this.getCORSRequest("POST", this.errorEndpoint(b));
            d.onreadystatechange = function() {
              d.readyState === 4 && d.status !== 200 && (c.disabled = !0);
            };
            d._trackJs = m;
            d.send(k.JSON.stringify(a));
          } catch (e) {
            throw ((this.disabled = !0), e);
          }
        }
      },
      throttle(a) {
        const b = new Date().getTime();
        this.throttleStats.attemptCount++;
        if (this.throttleStats.lastAttempt + 1e3 >= b) {
          if (
            ((this.throttleStats.lastAttempt = b),
            this.throttleStats.attemptCount > 10)
          )
            return this.throttleStats.throttledCount++, !0;
        } else {
          (a.throttled = this.throttleStats.throttledCount),
            (this.throttleStats.attemptCount = 0),
            (this.throttleStats.lastAttempt = b),
            (this.throttleStats.throttledCount = 0);
        }
        return !1;
      }
    };
    var f = (function() {
      function a(c, d, e, g) {
        e = e || !1;
        g = g || 0;
        f.forEach(d, d => {
          f.forEach(f.keys(d), f => {
            d[f] === null || d[f] === m
              ? (c[f] = d[f])
              : e && g < 10 && b(d[f]) === "[object Object]"
              ? ((c[f] = c[f] || {}), a(c[f], [d[f]], e, g + 1))
              : c.hasOwnProperty(f) || (c[f] = d[f]);
          });
        });
        return c;
      }
      function b(a) {
        return Object.prototype.toString.call(a);
      }
      return {
        addEventListenerSafe(a, b, e, f) {
          a.addEventListener
            ? a.addEventListener(b, e, f)
            : a.attachEvent && a.attachEvent(`on${b}`, e);
        },
        afterDocumentLoad(a) {
          let b = !1;
          n.readyState === "complete"
            ? f.defer(a)
            : (f.addEventListenerSafe(n, "readystatechange", () => {
                n.readyState !== "complete" || b || (f.defer(a), (b = !0));
              }),
              setTimeout(() => {
                b || (f.defer(a), (b = !0));
              }, 1e4));
        },
        bind(a, b) {
          return function() {
            return a.apply(b, Array.prototype.slice.call(arguments));
          };
        },
        contains(a, b) {
          let e;
          for (e = 0; e < a.length; e++) if (a[e] === b) return !0;
          return !1;
        },
        defaults(c) {
          return a(c, Array.prototype.slice.call(arguments, 1), !1);
        },
        defaultsDeep(c) {
          return a(c, Array.prototype.slice.call(arguments, 1), !0);
        },
        defer(a, b) {
          setTimeout(() => {
            a.apply(b);
          });
        },
        forEach(a, b, e) {
          if (a.forEach) return a.forEach(b, e);
          for (let f = 0; f < a.length; ) b.call(e, a[f], f, a), f++;
        },
        getLocation() {
          return k.location.toString().replace(/ /g, "%20");
        },
        getLocationURL() {
          return f.parseURL(f.getLocation());
        },
        has(a, b) {
          for (let e = b.split("."), f = a, l = 0; l < e.length; l++)
            if (f[e[l]]) f = f[e[l]];
            else return !1;
          return !0;
        },
        hasFunction(a, b) {
          try {
            return !!a[b];
          } catch (e) {
            return !1;
          }
        },
        isArray(a) {
          return b(a) === "[object Array]";
        },
        isBoolean(a) {
          return (
            typeof a === "boolean" ||
            (f.isObject(a) && b(a) === "[object Boolean]")
          );
        },
        isBrowserIE(a) {
          a = a || k.navigator.userAgent;
          const b = a.match(/Trident\/([\d.]+)/);
          return b && b[1] === "7.0"
            ? 11
            : (a = a.match(/MSIE ([\d.]+)/))
            ? parseInt(a[1], 10)
            : !1;
        },
        isBrowserSupported() {
          const a = this.isBrowserIE();
          return !a || a >= 8;
        },
        isError(a) {
          if (!f.isObject(a)) return !1;
          const d = b(a);
          return (
            d === "[object Error]" ||
            d === "[object DOMException]" ||
            (f.isString(a.name) && f.isString(a.message))
          );
        },
        isElement(a) {
          return f.isObject(a) && a.nodeType === 1;
        },
        isFunction(a) {
          return !(!a || typeof a !== "function");
        },
        isNumber(a) {
          return (
            typeof a === "number" ||
            (f.isObject(a) && b(a) === "[object Number]")
          );
        },
        isObject(a) {
          return !(!a || typeof a !== "object");
        },
        isString(a) {
          return (
            typeof a === "string" ||
            (!f.isArray(a) && f.isObject(a) && b(a) === "[object String]")
          );
        },
        isWrappableFunction(a) {
          return this.isFunction(a) && this.hasFunction(a, "apply");
        },
        isoNow() {
          const a = new Date();
          return a.toISOString
            ? a.toISOString()
            : `${a.getUTCFullYear()}-${this.pad(
                a.getUTCMonth() + 1
              )}-${this.pad(a.getUTCDate())}T${this.pad(
                a.getUTCHours()
              )}:${this.pad(a.getUTCMinutes())}:${this.pad(
                a.getUTCSeconds()
              )}.${String((a.getUTCMilliseconds() / 1e3).toFixed(3)).slice(
                2,
                5
              )}Z`;
        },
        keys(a) {
          if (!f.isObject(a)) return [];
          const b = [];
          let e;
          for (e in a) a.hasOwnProperty(e) && b.push(e);
          return b;
        },
        noop() {},
        pad(a) {
          a = String(a);
          a.length === 1 && (a = `0${a}`);
          return a;
        },
        parseURL(a) {
          let b = a.match(
            /^(([^:\/?#]+):)?(\/\/([^\/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?$/
          );
          if (!b) return {};
          b = {
            protocol: b[2],
            host: b[4],
            path: b[5],
            query: b[6],
            hash: b[8]
          };
          b.origin = `${b.protocol || ""}://${b.host || ""}`;
          b.relative = (b.path || "") + (b.query || "") + (b.hash || "");
          b.href = a;
          return b;
        },
        patch(a, b, e) {
          a[b] = e(a[b] || f.noop);
        },
        testCrossdomainXhr() {
          return "withCredentials" in new XMLHttpRequest();
        },
        truncate(a, b) {
          if (a.length <= b) return a;
          const e = a.length - b;
          return `${a.substr(0, b)}...{${e}}`;
        },
        uuid() {
          return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, a => {
            const b = (16 * Math.random()) | 0;
            return (a == "x" ? b : (b & 3) | 8).toString(16);
          });
        },
        wrapError(a) {
          if (a.innerError) return a;
          const b = Error(`TrackJS Caught: ${a.message || a}`);
          b.description = `TrackJS Caught: ${a.description}`;
          b.file = a.file;
          b.line = a.line || a.lineNumber;
          b.column = a.column || a.columnNumber;
          b.stack = a.stack;
          b.innerError = a;
          return b;
        }
      };
    })();
    const C = function(a, b, c, d, e, f) {
      this.util = a;
      this.log = b;
      this.onError = c;
      this.onFault = d;
      this.options = f;
      this.document = e;
      f.enabled && this.initialize(e);
    };
    C.prototype = {
      initialize(a) {
        const b = this.util.bind(this.onDocumentClicked, this);
        const c = this.util.bind(this.onInputChanged, this);
        a.addEventListener
          ? (a.addEventListener("click", b, !0),
            a.addEventListener("blur", c, !0))
          : a.attachEvent &&
            (a.attachEvent("onclick", b), a.attachEvent("onfocusout", c));
      },
      onDocumentClicked(a) {
        try {
          const b = this.getElementFromEvent(a);
          b &&
            b.tagName &&
            (this.isDescribedElement(b, "a") ||
            this.isDescribedElement(b, "button") ||
            this.isDescribedElement(b, "input", ["button", "submit"])
              ? this.writeVisitorEvent(b, "click")
              : this.isDescribedElement(b, "input", ["checkbox", "radio"]) &&
                this.writeVisitorEvent(b, "input", b.value, b.checked));
        } catch (c) {
          this.onFault(c);
        }
      },
      onInputChanged(a) {
        try {
          const b = this.getElementFromEvent(a);
          if (b && b.tagName) {
            if (this.isDescribedElement(b, "textarea"))
              this.writeVisitorEvent(b, "input", b.value);
            else if (
              this.isDescribedElement(b, "select") &&
              b.options &&
              b.options.length
            )
              this.onSelectInputChanged(b);
            else {
              this.isDescribedElement(b, "input") &&
                !this.isDescribedElement(b, "input", [
                  "button",
                  "submit",
                  "hidden",
                  "checkbox",
                  "radio"
                ]) &&
                this.writeVisitorEvent(b, "input", b.value);
            }
          }
        } catch (c) {
          this.onFault(c);
        }
      },
      onSelectInputChanged(a) {
        if (a.multiple)
          for (let b = 0; b < a.options.length; b++)
            a.options[b].selected &&
              this.writeVisitorEvent(a, "input", a.options[b].value);
        else
          a.selectedIndex >= 0 &&
            a.options[a.selectedIndex] &&
            this.writeVisitorEvent(
              a,
              "input",
              a.options[a.selectedIndex].value
            );
      },
      writeVisitorEvent(a, b, c, d) {
        this.getElementType(a) === "password" && (c = m);
        this.log.add("v", {
          timestamp: this.util.isoNow(),
          action: b,
          element: {
            tag: a.tagName.toLowerCase(),
            attributes: this.getElementAttributes(a),
            value: this.getMetaValue(c, d)
          }
        });
      },
      getElementFromEvent(a) {
        return a.target || n.elementFromPoint(a.clientX, a.clientY);
      },
      isDescribedElement(a, b, c) {
        if (a.tagName.toLowerCase() !== b.toLowerCase()) return !1;
        if (!c) return !0;
        a = this.getElementType(a);
        for (b = 0; b < c.length; b++) if (c[b] === a) return !0;
        return !1;
      },
      getElementType(a) {
        return (a.getAttribute("type") || "").toLowerCase();
      },
      getElementAttributes(a) {
        for (var b = {}, c = 0; c < a.attributes.length; c++)
          a.attributes[c].name.toLowerCase() !== "value" &&
            (b[a.attributes[c].name] = a.attributes[c].value);
        return b;
      },
      getMetaValue(a, b) {
        return a === m
          ? m
          : {
              length: a.length,
              pattern: this.matchInputPattern(a),
              checked: b
            };
      },
      matchInputPattern(a) {
        return a === ""
          ? "empty"
          : /^[a-z0-9!#$%&'*+=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(
              a
            )
          ? "email"
          : /^(0?[1-9]|[12][0-9]|3[01])[\/\-](0?[1-9]|1[012])[\/\-]\d{4}$/.test(
              a
            ) ||
            /^(\d{4}[\/\-](0?[1-9]|1[012])[\/\-]0?[1-9]|[12][0-9]|3[01])$/.test(
              a
            )
          ? "date"
          : /^(?:(?:\+?1\s*(?:[.-]\s*)?)?(?:\(\s*([2-9]1[02-9]|[2-9][02-8]1|[2-9][02-8][02-9])\s*\)|([2-9]1[02-9]|[2-9][02-8]1|[2-9][02-8][02-9]))\s*(?:[.-]\s*)?)?([2-9]1[02-9]|[2-9][02-9]1|[2-9][02-9]{2})\s*(?:[.-]\s*)?([0-9]{4})(?:\s*(?:#|x\.?|ext\.?|extension)\s*(\d+))?$/.test(
              a
            )
          ? "usphone"
          : /^\s*$/.test(a)
          ? "whitespace"
          : /^\d*$/.test(a)
          ? "numeric"
          : /^[a-zA-Z]*$/.test(a)
          ? "alpha"
          : /^[a-zA-Z0-9]*$/.test(a)
          ? "alphanumeric"
          : "characters";
      },
      report() {
        return this.log.all("v");
      }
    };
    const D = function(a, b, c, d, e) {
      this.onError = a;
      this.onFault = b;
      this.serialize = c;
      e.enabled && this.watchWindowErrors(d);
      e.promise && this.watchPromiseErrors(d);
    };
    D.prototype = {
      watchPromiseErrors(a) {
        const b = this;
        a.addEventListener
          ? a.addEventListener("unhandledrejection", a => {
              a = a || {};
              a = a.detail ? a.detail.reason : a.reason;
              if (a !== m) {
                if (!f.isError(a))
                  try {
                    throw Error(b.serialize(a));
                  } catch (d) {
                    a = d;
                  }
                b.onError("promise", a);
              }
            })
          : (a.onunhandledrejection = function(a) {
              b.onError("promise", a);
            });
      },
      watchWindowErrors(a) {
        const b = this;
        f.patch(
          a,
          "onerror",
          a =>
            function(d, e, f, l, h) {
              try {
                (h = h || {}),
                  (h.message = h.message || b.serialize(d)),
                  (h.name = h.name || "Error"),
                  (h.line = h.line || parseInt(f, 10) || null),
                  (h.column = h.column || parseInt(l, 10) || null),
                  Object.prototype.toString.call(d) !== "[object Event]" || e
                    ? (h.file = h.file || b.serialize(e))
                    : (h.file = (d.target || {}).src),
                  b.onError("window", h);
              } catch (k) {
                b.onFault(k);
              }
              a.apply(this, arguments);
            }
        );
      }
    };
    const E = function(a, b, c, d, e, g, l, h, k, m, n, p, q, x, t, u, v) {
      try {
        if (
          ((this.window = t),
          (this.document = u),
          (this.util = f),
          (this.onError = this.util.bind(this.onError, this)),
          (this.onFault = this.util.bind(this.onFault, this)),
          (this.serialize = this.util.bind(this.serialize, this)),
          (this.config = new d(a)),
          (this.transmitter = new n(this.util, this.config)),
          (this.log = new h(this.util)),
          (this.api = new b(
            this.config,
            this.util,
            this.onError,
            this.serialize
          )),
          (this.metadata = new k(this.serialize)),
          (this.environment = new l(this.window)),
          (this.customer = new g(
            this.config,
            this.util,
            this.log,
            this.window,
            this.document
          )),
          this.customer.token &&
            ((this.apiConsoleWatcher = new e(
              this.util,
              this.log,
              this.onError,
              this.onFault,
              this.serialize,
              this.api,
              this.config.defaults.console
            )),
            this.config.current.enabled &&
              ((this.windowConsoleWatcher = new e(
                this.util,
                this.log,
                this.onError,
                this.onFault,
                this.serialize,
                this.window,
                this.config.current.console
              )),
              this.util.isBrowserSupported())))
        ) {
          this.callbackWatcher = new c(
            this.util,
            this.onError,
            this.onFault,
            this.window,
            this.config.current.callback
          );
          this.visitorWatcher = new p(
            this.util,
            this.log,
            this.onError,
            this.onFault,
            this.document,
            this.config.current.visitor
          );
          this.navigationWatcher = new v(
            this.log,
            this.config.current.navigation
          );
          this.networkWatcher = new m(
            this.util,
            this.log,
            this.onError,
            this.onFault,
            this.window,
            this.config.current.network
          );
          this.windowWatcher = new q(
            this.onError,
            this.onFault,
            this.serialize,
            this.window,
            this.config.current.window
          );
          const r = this;
          f.afterDocumentLoad(() => {
            r.transmitter.sendUsage({
              token: r.customer.token,
              correlationId: r.customer.correlationId,
              application: r.config.current.application,
              x: r.util.uuid()
            });
          });
        }
      } catch (w) {
        this.onFault(w);
      }
    };
    E.prototype = {
      reveal() {
        if (this.customer.token)
          return (
            (this.api.addMetadata = this.metadata.addMetadata),
            (this.api.removeMetadata = this.metadata.removeMetadata),
            this.api
          );
        this.config.current.enabled &&
          this.window.console &&
          this.window.console.warn &&
          this.window.console.warn("TrackJS could not find a token");
        return m;
      },
      onError: (function() {
        let a;
        let b = !1;
        return function(c, d, e) {
          if (f.isBrowserSupported() && this.config.current.enabled) {
            try {
              if (
                ((e = e || { bindStack: null, bindTime: null, force: !1 }),
                (d && f.isError(d)) ||
                  (d = { name: "Error", message: this.serialize(d, e.force) }),
                d.message.indexOf("TrackJS Caught") === -1)
              ) {
                if (b && d.message.indexOf("Script error") !== -1) b = !1;
                else {
                  const g = f.defaultsDeep(
                    {},
                    {
                      bindStack: e.bindStack,
                      bindTime: e.bindTime,
                      column: d.column || d.columnNumber,
                      console: this.windowConsoleWatcher.report(),
                      customer: this.customer.report(),
                      entry: c,
                      environment: this.environment.report(),
                      file: d.file || d.fileName,
                      line: d.line || d.lineNumber,
                      message: d.message,
                      metadata: this.metadata.report(),
                      nav: this.navigationWatcher.report(),
                      network: this.networkWatcher.report(),
                      url: (k.location || "").toString(),
                      stack: d.stack,
                      timestamp: this.util.isoNow(),
                      visitor: this.visitorWatcher.report(),
                      version: "2.8.5"
                    }
                  );
                  if (!e.force)
                    try {
                      if (!this.config.current.onError(g, d)) return;
                    } catch (m) {
                      g.console.push({
                        timestamp: this.util.isoNow(),
                        severity: "error",
                        message: m.message
                      });
                      const l = this;
                      setTimeout(() => {
                        l.onError("catch", m, { force: !0 });
                      }, 0);
                    }
                  if (this.config.current.dedupe) {
                    const h = (g.message + g.stack).substr(0, 1e4);
                    if (h === a) return;
                    a = h;
                  }
                  this.log.clear();
                  setTimeout(() => {
                    b = !1;
                  });
                  b = !0;
                  this.transmitter.sendError(g, this.customer.token);
                }
              }
            } catch (m) {
              this.onFault(m);
            }
          }
        };
      })(),
      onFault(a) {
        const b = this.transmitter || new x();
        a = a || {};
        a = {
          token: this.customer.token,
          file: a.file || a.fileName,
          msg: a.message || "unknown",
          stack: (a.stack || "unknown").substr(0, 500),
          url: this.window.location,
          v: "2.8.5",
          h: "6d337e9db22126de34c9035a3cd0b11c81d3a48f",
          x: this.util.uuid()
        };
        b.sendTrackerFault(a);
      },
      serialize(a, b) {
        if (this.config.current.serialize && !b)
          try {
            return this.config.current.serialize(a);
          } catch (c) {
            this.onError("catch", c, { force: !0 });
          }
        return this.config.defaults.serialize(a);
      }
    };
    p = new E(
      k._trackJs || k._trackJS || k._trackjs || {},
      (a, b, c, d) => ({
        attempt(a, d) {
          try {
            const f = Array.prototype.slice.call(arguments, 2);
            return a.apply(d || this, f);
          } catch (h) {
            throw (c("catch", h), b.wrapError(h));
          }
        },
        configure(b) {
          return a.setCurrent(b);
        },
        track(a) {
          const b = d(a);
          a = a || {};
          if (!a.stack)
            try {
              throw Error(b);
            } catch (f) {
              a = f;
            }
          c("direct", a);
        },
        watch(a, d) {
          return function() {
            try {
              const f = Array.prototype.slice.call(arguments, 0);
              return a.apply(d || this, f);
            } catch (h) {
              throw (c("catch", h), b.wrapError(h));
            }
          };
        },
        watchAll(a) {
          const d = Array.prototype.slice.call(arguments, 1);
          let f;
          for (f in a)
            typeof a[f] === "function" &&
              (b.contains(d, f) ||
                (function() {
                  const d = a[f];
                  a[f] = function() {
                    try {
                      const a = Array.prototype.slice.call(arguments, 0);
                      return d.apply(this, a);
                    } catch (e) {
                      throw (c("catch", e), b.wrapError(e));
                    }
                  };
                })());
          return a;
        },
        hash: "6d337e9db22126de34c9035a3cd0b11c81d3a48f",
        version: "2.8.5"
      }),
      p,
      t,
      u,
      v,
      w,
      z,
      a => {
        const b = {};
        return {
          addMetadata(a, d) {
            b[a] = d;
          },
          removeMetadata(a) {
            delete b[a];
          },
          report() {
            const c = [];
            let d;
            for (d in b)
              b.hasOwnProperty(d) && c.push({ key: d, value: a(b[d]) });
            return c;
          },
          store: b
        };
      },
      B,
      x,
      C,
      D,
      q,
      k,
      n,
      A
    );
    k.trackJs = p.reveal();
  }
})(window, document);
