import browser from "browser-detect";
import _ from "underscore";
import MainFunctions from "../libraries/MainFunctions";

export const AK_GA = {
  category: "ARESKudo",
  actions: {
    drawingOpen: "drawing-open",
    login: "login",
    trial: "trial",
    compatibility: "compatibility",
    storage: "storage"
  },
  labels: {
    authenticationAnswer: "authenticationAnswer",
    checkBrowser: "checkBrowser",
    checkWebGL: "checkWebGL",
    addingStorage: "addingStorage",
    storageCount: "storageCount",
    kudoInApp: "Source:KudoInApp"
  }
};

const Tracker = {
  configureIntercom() {
    // DK: not sure we actually need this, but let's leave it for now
    const browserCheckResults = MainFunctions.checkBrowser();
    // intercom isn't supported in IE9-
    if (browserCheckResults.version > 9) {
      /* eslint-disable */
      // configure intercom
      (function () {
        const w = window;
        const ic = w.Intercom;
        if (typeof ic === "function") {
          // @ts-ignore
          delete w.Intercom;
        }
        const d = document;
        var i = function () {
          // @ts-ignore
          i.c(arguments);
        };
        // @ts-ignore
        i.q = [];
        // @ts-ignore
        i.c = function (args) {
          // @ts-ignore
          i.q.push(args);
        };
        // @ts-ignore
        w.Intercom = i;
        const s = d.createElement("script");
        s.type = "text/javascript";
        s.async = true;
        s.src = "https://widget.intercom.io/widget/dzoczj6l";
        const x = d.getElementsByTagName("head")[0];
        x.appendChild(s);
      })();
      /* eslint-enable */
      const browserData = browser();
      window.Intercom("boot", {
        app_id: "dzoczj6l",
        created_at: new Date().getTime(),
        sitename: location.hostname,
        LAST_KUDO_BROWSER: `${browserData.name} ${browserData.versionNumber}`
      });
    }
  },

  configureTrackJS(version: string) {
    window._trackJs = {
      token: "c489b5cc15a741e380bb02fa63d51743",
      version,
      enabled: !window.location.host.includes("localhost"),
      application: "fluorine"
    };
    const scrTrack = document.createElement("script");
    scrTrack.async = true;
    scrTrack.type = "text/javascript";
    document.body.appendChild(scrTrack);
    scrTrack.src = `/libs/tracker.js`;
  },

  configureGoogleAnalytics(gaCode: string, gaurl?: string) {
    /* eslint-disable */
    (function (i, s, o, g, r, a, m) {
      // @ts-ignore
      i.GoogleAnalyticsObject = r;
      // @ts-ignore
      (i[r] =
        // @ts-ignore
        i[r] ||
        function () {
          // @ts-ignore
          (i[r].q = i[r].q || []).push(arguments);
        }),
        // @ts-ignore
        (i[r].l = 1 * new Date());
      // @ts-ignore
      (a = s.createElement(o)), (m = s.getElementsByTagName(o)[0]);
      // @ts-ignore
      a.async = 1;
      // @ts-ignore
      a.src = g;
      // @ts-ignore
      m.parentNode.insertBefore(a, m);
    })(
      window,
      document,
      "script",
      gaurl || "https://www.google-analytics.com/analytics.js",
      "ga"
    );
    /* eslint-enable */
    const { ga } = window;
    if (ga) {
      ga("create", gaCode, "auto", {
        allowLinker: true
      });
      ga("require", "linker");
      ga("linker:autoLink", [/^(?!support\.)(.*)graebert\.com/gi], false, true);
    }
  },

  /**
   * Proxy for GA
   * @param {string} eventCategory
   * @param {string} eventAction
   * @param {string} [eventLabel]
   * @param {string} [eventValue]
   */
  sendGAEvent(
    eventCategory: string,
    eventAction: string,
    eventLabel: string = "",
    eventValue: string = ""
  ) {
    try {
      let category = eventCategory;
      if (_.isFunction(eventCategory)) {
        category = eventCategory();
      } else if (_.isString(eventCategory)) {
        category = eventCategory;
      }

      const { ga } = window;
      if (ga) {
        ga(
          "send",
          "event",
          category || "ARESKudo",
          eventAction,
          eventLabel,
          eventValue
        );
      }
    } catch (ex) {
      // eslint-disable-next-line no-console
      console.error(`Exception happened in sendGAEvent:${ex}`);
    }
  },

  setGAProperty(propName: string, propValue: string) {
    const { ga } = window;
    if (propName && propValue && ga) {
      ga("set", propName, propValue);
    }
  },

  sendIntercomEvent(eventName: string, eventData: object) {
    const { Intercom } = window;
    if (Intercom) {
      Intercom("trackEvent", eventName, eventData);
    }
  },

  /**
   * @description Tracks out bound links
   * @param {string} eventCategory
   * @param {string} eventAction
   * @param {string} eventLabel
   * @param {string} url
   * @param {boolean} isNewWindow
   * @param {boolean} isReplace
   */
  trackOutboundLink(
    eventCategory: string,
    eventAction: string,
    eventLabel: string,
    url: string,
    isNewWindow: boolean,
    isReplace: boolean
  ) {
    if (isNewWindow) {
      this.sendGAEvent(
        eventCategory || "ARESKudo",
        eventAction || "redirect",
        eventLabel || url
      );
      window.open(url, "_blank", "noopener,noreferrer");
    } else if (
      // TODO: An another way may be to check if the ga object is loaded
      (window.ga as UniversalAnalytics.ga & { loaded: boolean })?.loaded
    ) {
      // need to check in case of any blockers - e.g. firefox no track feature
      this.sendGAEvent(
        eventCategory || "ARESKudo",
        eventAction || "redirect",
        eventLabel || url,
        undefined
        // BD: Why was the object below being sent as 4th parameter earlier?
        // {
        //   transport: "beacon",
        //   hitCallback() {
        //     if (isReplace === true) {
        //       document.location.replace(url);
        //     } else {
        //       document.location = url;
        //     }
        //   }
        // }
      );
    } else if (isReplace) {
      document.location.replace(url);
    } else {
      document.location = url;
    }
  }
};

export default Tracker;
