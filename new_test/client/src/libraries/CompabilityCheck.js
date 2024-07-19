import _ from "underscore";
import MainFunctions from "./MainFunctions";
import Tracker, { AK_GA } from "../utils/Tracker";

export function isBrowserSupported() {
  if (MainFunctions.QueryString("decomp") === "browser") {
    return {
      valid: false,
      reason: [
        { id: "browserIsNotSupported" },
        { id: "OperaIsNotSupported" },
        { id: "IEIsNotSupported" }
      ]
    };
  }
  if (navigator.appName === "Microsoft Internet Explorer") {
    Tracker.sendGAEvent(
      AK_GA.category,
      AK_GA.actions.compatibility,
      AK_GA.labels.checkBrowser,
      "Internet Explorer"
    );
    // currently IE in all versions have bugs that prevent proper work of Kudo
    return {
      valid: false,
      reason: [{ id: "browserIsNotSupported" }, { id: "IEIsNotSupported" }]
    };
  }
  if (navigator.userAgent.indexOf("Opera/") !== -1) {
    // Opera 15+ has OPR/ in UA. Prior versions has Opera/ in UA
    // refer http://www.javascriptkit.com/javatutors/navigator.shtml
    Tracker.sendGAEvent(
      AK_GA.category,
      AK_GA.actions.compatibility,
      AK_GA.labels.checkBrowser,
      "Opera"
    );
    return {
      valid: false,
      reason: [{ id: "browserIsNotSupported" }, { id: "OperaIsNotSupported" }]
    };
  }
  if (navigator.appName === "Netscape") {
    // / in IE 11 the navigator.appVersion says 'trident'
    // / in Edge the navigator.appVersion does not say trident
    let GABrowserName = "Edge";
    if (navigator.appName.toLowerCase().includes("trident")) {
      GABrowserName = "IE 11";
    }
    Tracker.sendGAEvent(
      AK_GA.category,
      AK_GA.actions.compatibility,
      AK_GA.labels.checkBrowser,
      GABrowserName
    );
    return {
      valid: navigator.appVersion.indexOf("Trident") === -1,
      reason: [{ id: "browserIsNotSupported" }, { id: "IEIsNotSupported" }]
    };
  }
  Tracker.sendGAEvent(
    AK_GA.category,
    AK_GA.actions.compatibility,
    AK_GA.labels.checkBrowser,
    "Compatible browser"
  );
  // assume that all other browsers are allowed
  return { valid: true, reason: [{ id: "" }] };
}

export function isBrowserIsChrome() {
  // code source - http://stackoverflow.com/questions/4565112/javascript-how-to-find-out-if-the-user-browser-is-chrome/13348618#13348618
  const isChromium = window.chrome;

  const winNav = window.navigator;

  const vendorName = winNav.vendor;

  const isOpera = winNav.userAgent.indexOf("OPR") > -1;

  const isIEedge = winNav.userAgent.indexOf("Edge") > -1;

  const isIOSChrome = winNav.userAgent.match("CriOS");
  if (isIOSChrome) {
    return true;
  }
  return (
    isChromium !== null &&
    isChromium !== undefined &&
    vendorName === "Google Inc." &&
    isOpera === false &&
    isIEedge === false
  );
}

export function isWebGLSupported(sendEvent) {
  const reason = [
    { id: "webGLIsNotSupported" },
    {
      id: "visitSiteToEnableWebGL",
      website: "https://get.webgl.org",
      link: "https://get.webgl.org"
    }
  ];
  try {
    let canvas = document.createElement("canvas");
    canvas.width = 100;
    canvas.height = 100;

    let gl = canvas.getContext("webgl");

    if (!gl) {
      gl = canvas.getContext("experimental-webgl");
    }

    const bIsWebGl = !!gl;

    gl = null;
    canvas = null;
    if (MainFunctions.QueryString("decomp") === "webgl") {
      return { valid: false, reason };
    }
    if (sendEvent !== false) {
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.actions.compatibility,
        AK_GA.labels.checkWebGL,
        bIsWebGl ? 1 : 0
      );
    }
    return { valid: bIsWebGl, reason };
  } catch (Exception) {
    // eslint-disable-next-line no-console
    console.error(Exception);
    return { valid: false, reason };
  }
}

export function shouldXenonRun() {
  const results = [isBrowserSupported(), isWebGLSupported()];
  return {
    valid: !!_.every(results, testResult => testResult.valid === true),
    reason: (
      _.find(results, testResult => testResult.valid === false) || {
        reason: [{ id: "" }]
      }
    ).reason
  };
}
