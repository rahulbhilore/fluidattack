function checkBrowser() {
  var version = 11;
  if (navigator.appName === "Microsoft Internet Explorer") {
    // currently IE in all versions have bugs that prevent proper work of Kudo
    var ua = window.navigator.userAgent;

    var msie = ua.indexOf("MSIE ");
    if (msie > 0) {
      // IE 10 or older => return version number
      version = parseInt(ua.substring(msie + 5, ua.indexOf(".", msie)), 10);
    }
    return {
      version: version,
      status: false,
      name: "Internet Explorer"
    };
  } else if (
    navigator.platform.indexOf("Win") > -1 &&
    navigator.userAgent.indexOf("Safari") > -1 &&
    navigator.userAgent.indexOf("Chrome") === -1
  ) {
    // Safari for Windows disabled - XENON-21811
    return {
      version: version,
      status: false,
      name: "Safari for Windows"
    };
  } else if (
    navigator.appVersion.indexOf("Trident/") > -1 ||
    navigator.appVersion.indexOf("Edge") > -1
  ) {
    // UPD: Edge has been also disabled: XENON-22291
    return {
      version: version,
      status: navigator.appVersion.indexOf("Trident/") === -1,
      name:
        navigator.appVersion.indexOf("Trident/") === -1
          ? "Microsoft Edge"
          : "Internet Explorer"
    };
  }
  // assume that all non-IE browsers are allowed
  return {
    version: version,
    status: true,
    name: "this browser"
  };
}

window.browserCheckResults = checkBrowser();

if (window.browserCheckResults.status === false) {
  var loader = document.getElementById("loader");
  loader.parentNode.removeChild(loader);
  var loadingScreenDiv = document.getElementById("loadingScreen");
  var messageSubPart = "Please try to use Chrome, Firefox or Safari.";
  if (window.browserCheckResults.name === "Safari for Windows") {
    messageSubPart = "Please use Chrome or Firefox.";
  }
  var scripts = document.getElementsByTagName("script");
  var imgURL = scripts[scripts.length - 1].getAttribute("data-img");
  loadingScreenDiv.innerHTML =
    "<img src='" + (imgURL ||
    "/initial/kudo-logo-default.png") +
      "' /><p>Sorry, " +
      window.browserCheckResults.name +
      " is not supported. <br/> " +
      messageSubPart +
      "</p>";
  loadingScreenDiv.style.width = "400px";
  loadingScreenDiv.style.height = "300px";
  loadingScreenDiv.style.marginTop = "-150px";
  loadingScreenDiv.style.marginLeft = "-200px";
  loadingScreenDiv.style.top = "50%";
  loadingScreenDiv.style.left = "50%";
  loadingScreenDiv.style.position = "absolute";
}
