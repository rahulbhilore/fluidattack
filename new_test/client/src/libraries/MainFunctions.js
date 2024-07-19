/* eslint-disable no-console */
import _ from "underscore";
import $ from "jquery";
import browser from "browser-detect";
import Immutable from "immutable";
import Tracker from "../utils/Tracker";

export const APPLE_DEVICE = "apple_device";

/**
 * Set of useful functions
 */
const MainFunctions = {
  /**
   * Check if element is hidden from user
   * @param element {HTMLElement}
   * @returns {boolean} - true if is hidden
   */
  isHidden(element) {
    return element ? element.offsetParent === null : true;
  },

  /**
   * Return DOM element's offset
   * @param element {HTMLElement}
   * @returns {{top: number, left: number}}
   */
  offset(element) {
    const rect = element.getBoundingClientRect();
    return {
      top: rect.top + window.pageYOffset,
      left: rect.left + window.pageXOffset
    };
  },

  /**
   * Check if element is in container
   * @param element {HTMLElement}
   * @param container {HTMLElement|Window}
   * @returns {boolean}
   */
  inViewport(element, container) {
    if (this.isHidden(element)) {
      return false;
    }

    let top;
    let left;
    let bottom;
    let right;

    if (typeof container === "undefined" || container === window) {
      top = window.pageYOffset;
      left = window.pageXOffset;
      bottom = top + window.innerHeight;
      right = left + window.innerWidth;
    } else {
      const containerOffset = this.offset(container);

      top = containerOffset.top;
      left = containerOffset.left;
      bottom = top + container.offsetHeight;
      right = left + container.offsetWidth;
    }
    const elementOffset = this.offset(element);
    return (
      top < elementOffset.top + element.offsetHeight &&
      bottom > elementOffset.top &&
      left < elementOffset.left + element.offsetWidth &&
      right > elementOffset.left
    );
  },

  /**
   * Get extension from object name
   * @param name {String}
   * @returns {String}
   */
  getExtensionFromName(name) {
    return this.parseObjectName(name).extension;
  },

  /**
   * Parse name: get extension and name
   * @param name {String}
   */
  parseObjectName(name) {
    const lastDot = name.lastIndexOf(".");

    if (name && lastDot > -1) {
      return {
        name: name.substring(0, lastDot),
        extension: name.substring(lastDot + 1).toLowerCase()
      };
    }

    return {
      name,
      extension: ""
    };
  },

  getShortcutDownloadName(shortcutEntity) {
    const name = shortcutEntity.name || shortcutEntity.filename;
    if (shortcutEntity.shortcutInfo.type === "folder") {
      return name;
    }

    return `${name}.${this.getExtensionFromName(
      shortcutEntity.shortcutInfo.mimeType
    )}`;
  },

  shrinkNameWithExtension(name = "") {
    const maxAllowedLength = 30;
    if (name?.length > 0 && name.length > maxAllowedLength) {
      if (name.includes(".")) {
        const extension = name.substr(name.lastIndexOf(".") + 1) || "";
        const nameOnly = name.substr(0, name.lastIndexOf("."));
        return (
          MainFunctions.shrinkString(nameOnly, maxAllowedLength) + extension
        );
      }
      return MainFunctions.shrinkString(name, maxAllowedLength);
    }
    return name;
  },

  /**
   * Generate Client-side guid
   * @returns {string}
   */
  guid(prefix = undefined) {
    return `CS_${
      prefix ? `${prefix}_` : ""
    }${this.s4()}${this.s4()}-${this.s4()}-${this.s4()}-${this.s4()}-${this.s4()}${this.s4()}${this.s4()}`;
  },

  /**
   * @private
   * Generate guid part
   * @returns {string}
   */
  s4() {
    return Math.floor((1 + Math.random()) * 0x10000)
      .toString(16)
      .substring(1);
  },

  /**
   * TODO: remove
   * @deprecated
   * Attach jQuery listener for event {event} that happens on {elem}
   * @param elem {HTMLElement}
   * @param event {string}
   * @param fun {function}
   */
  attachJQueryListener(elem, event, fun) {
    _.each(elem, (el, key) => {
      if (
        !$._data(elem[key], "events") ||
        !$._data(elem[key], "events").length ||
        !$._data(elem[key], "events")[event] ||
        !$._data(elem[key], "events")[event].length
      )
        elem.on(event, fun);
      else if ($._data(elem[key], "events")[event]) {
        let flag = true;
        _.each($._data(elem[key], "events")[event], funElem => {
          if (funElem.name === fun.name) flag = false;
        });
        if (flag) elem.on(event, fun);
      }
    });
  },

  /**
   * Detach {event} jQuery listener from {elem}
   * @param elem {HTMLElement}
   * @param event {Event}
   */
  detachJQueryListeners(elem, event) {
    _.each(elem, (el, key) => {
      if (event in ($._data(elem[key], "events") || {})) {
        elem.off(event);
      }
    });
  },

  /**
   * TODO: rename
   * Returns GET param value with specified {name}
   * @param [name] {String}
   * @returns {String|Object}
   */
  QueryString(name) {
    const queryString = {};
    const query = window.location.search.substring(1);
    const vars = query.split("&");
    for (let i = 0; i < vars.length; i += 1) {
      const pair = [vars[i].split(/=(.+)?/)[0], vars[i].split(/=(.+)?/)[1]];
      if (typeof queryString[pair[0]] === "undefined") {
        queryString[pair[0]] = decodeURIComponent(pair[1]);
      } else if (typeof queryString[pair[0]] === "string") {
        queryString[pair[0]] = [
          queryString[pair[0]],
          decodeURIComponent(pair[1])
        ];
      } else {
        queryString[pair[0]].push(decodeURIComponent(pair[1]));
      }
    }
    if (!name) {
      return queryString;
    }
    return queryString[name] || "";
  },

  /**
   * Get current page URL
   * @returns {string}
   */
  detectPageType() {
    // TODO: REWRITE and review usages
    const { UIPrefix = "" } = window.ARESKudoConfigObject || {};
    const route = location.pathname.replace(UIPrefix, "");
    if (route.includes("resources")) {
      return route;
    }
    if (route.includes("trash") || route.includes("search")) {
      return route.substr(0, route.lastIndexOf("/"));
    }
    return route === ""
      ? "index"
      : route.substr(0, route.indexOf("/")) || route;
  },

  /**
   * Shrinks string up to maxLength
   * @param stringToShrink {String}
   * @param [maxLength] {number}
   * @returns {String}
   */
  shrinkString(stringToShrink, maxLength = 30) {
    if (stringToShrink && stringToShrink.length > maxLength) {
      return `${stringToShrink.slice(0, maxLength)}...`;
    }
    return stringToShrink;
  },

  /**
   * Save url decode
   * @param str {String}
   * @returns {string}
   */
  URLDecode(str) {
    return decodeURIComponent(`${str}`.replace(/\+/g, "%20"));
  },

  /**
   * Get storage name for end user from service storage name (e.g. gdrive -> Google Drive)
   * @param storageName {String}
   * @returns {string}
   */
  serviceStorageNameToEndUser(storageName) {
    const storageObject = _.find(
      window.ARESKudoConfigObject.storage_features,
      storage => storage.name.toLowerCase() === storageName.toLowerCase()
    );
    if (storageObject) return storageObject.displayName;
    return storageName;
  },

  /**
   * Get storage name for end user from service storage name (e.g. Google Drive -> gdrive)
   * @param storageName {String}
   */
  endUserStorageNameToService(storageName) {
    const storageObject = _.find(
      window.ARESKudoConfigObject.storage_features,
      storage => storage.displayName.toLowerCase() === storageName.toLowerCase()
    );
    if (storageObject) return storageObject.name;
    return storageName;
  },

  /**
   * Get storage name for end user from service storage name (e.g. GD -> Google Drive)
   * @param storageCode {String}
   */
  storageCodeToServiceName(storageCode) {
    const storageCodeString = (storageCode || "").toUpperCase();
    switch (storageCodeString) {
      case "FL":
        return "internal";
      case "BX":
        return "box";
      case "DB":
        return "dropbox";
      case "GD":
        return "gdrive";
      case "TR":
        return "trimble";
      case "OS":
        return "onshape";
      case "OSDEV":
        return "onshapedev";
      case "OSSTAGING":
        return "onshapestaging";
      case "OD":
        return "onedrive";
      case "ODB":
        return "onedrivebusiness";
      case "SP":
        return "sharepoint";
      case "WD":
        return "webdav";
      case "NC":
        return "nextcloud";
      case "SF":
        return "samples";
      case "HC":
        return "hancom";
      case "HCS":
        return "hancomstg";
      default:
        if (
          storageCodeString.startsWith("WD") ||
          storageCodeString.includes("WEBDAV")
        ) {
          return "webdav";
        }

        if (
          storageCodeString.startsWith("NC") ||
          storageCodeString.includes("NEXTCLOUD")
        ) {
          return "nextcloud";
        }

        return "";
    }
  },

  /**
   * Get storage code from service storage name (e.g. gdrive->GD)
   * @param storageCode {String}
   */
  serviceNameToStorageCode(serviceName) {
    const serviceNameString = (serviceName || "").toLowerCase();
    switch (serviceNameString) {
      case "internal":
        return "FL";
      case "box":
        return "BX";
      case "dropbox":
        return "DB";
      case "gdrive":
        return "GD";
      case "trimble":
        return "TR";
      case "onshape":
        return "OS";
      case "onshapedev":
        return "OSDEV";
      case "onshapestaging":
        return "OSSTAGING";
      case "onedrive":
        return "OD";
      case "onedrivebusiness":
        return "ODB";
      case "sharepoint":
        return "SP";
      case "webdav":
        return "WD";
      case "nextcloud":
        return "NC";
      case "samples":
        return "SF";
      case "hancom":
        return "HC";
      case "hancomstg":
        return "HCS";
      default:
        if (
          serviceNameString.startsWith("wd") ||
          serviceNameString.includes("webdav")
        ) {
          return "WD";
        }

        if (
          serviceNameString.startsWith("nc") ||
          serviceNameString.includes("nextcloud")
        ) {
          return "NC";
        }

        return "";
    }
  },

  /**
   * Get user-friendly name from service storage name (e.g. gdrive->Google Drive).
   * @param storageCode {String}
   */
  serviceNameToUserName(serviceName) {
    const serviceNameString = (serviceName || "").toLowerCase();
    switch (serviceNameString) {
      case "internal":
        return "Storage";
      case "box":
        return "Box";
      case "dropbox":
        return "Dropbox";
      case "gdrive":
        return "Google Drive";
      case "trimble":
        return "Trimble Connect";
      case "onshape":
        return "Onshape";
      case "onshapedev":
        return "Onshape Development";
      case "onshapestaging":
        return "Onshape Staging";
      case "onedrive":
        return "Onedrive";
      case "onedrivebusiness":
        return "Onedrive for Business";
      case "sharepoint":
        return "Sharepoint";
      case "webdav":
        return "WebDAV";
      case "nextcloud":
        return "Nextcloud";
      case "samples":
        return "ARES Kudo Drive";
      case "hancom":
        return "Hancom";
      case "hancomstg":
        return "Hancom staging";
      default:
        if (
          serviceNameString.startsWith("wd") ||
          serviceNameString.includes("webdav")
        ) {
          return "WebDAV";
        }

        if (
          serviceNameString.startsWith("nc") ||
          serviceNameString.includes("nextcloud")
        ) {
          return "Nextcloud";
        }

        return "";
    }
  },

  /**
   * @description Converts boolean or boolean-like string into boolean
   * @param pseudoBoolean {string|Boolean}
   * @param [allowNonBooleanValues] {Boolean}
   * @return {Boolean}
   */
  forceBooleanType(pseudoBoolean, allowNonBooleanValues) {
    try {
      const unifiedString = pseudoBoolean.toString().toLowerCase().trim();
      if (allowNonBooleanValues === true) {
        if (unifiedString === "true") {
          return true;
        }
        if (unifiedString === "false") {
          return false;
        }
        return pseudoBoolean;
      }
      return unifiedString === "true";
    } catch (ex) {
      return false;
    }
  },

  /**
   * @description Converts url into absolute path url if it's not already
   * @param urlToValidate {string}
   * @return {string}
   */
  forceFullURL(urlToValidate) {
    let url;

    try {
      // eslint-disable-next-line no-unused-vars
      url = new URL(urlToValidate);
    } catch (err) {
      return location.origin + urlToValidate;
    }

    return urlToValidate;
  },

  trackOutboundLink(
    eventCategory,
    eventAction,
    eventLabel,
    url,
    isNewWindow,
    isReplace
  ) {
    if (isNewWindow === true) {
      Tracker.sendGAEvent(
        eventCategory || "ARESKudo",
        eventAction || "redirect",
        eventLabel || url
      );
      window.open(url, "_blank", "noopener,noreferrer");
    } else if (window.ga && window.ga.loaded === true) {
      // need to check in case of any blockers - e.g. firefox no track feature
      Tracker.sendGAEvent(
        eventCategory || "ARESKudo",
        eventAction || "redirect",
        eventLabel || url,
        undefined,
        {
          transport: "beacon",
          hitCallback() {
            if (isReplace === true) {
              document.location.replace(url);
            } else {
              document.location = url;
            }
          }
        }
      );
    } else if (isReplace === true) {
      document.location.replace(url);
    } else {
      document.location = url;
    }
  },

  /**
   * @description Set classes specified in styleSet, removes all other classes applied
   * @param styleSet {[string]} - list of styles
   */
  setBodyStyles(styleSet) {
    const { body } = document;
    body.className = styleSet.join(" ");
  },

  /**
   * @description Converts variable to JSON if possible
   * @param data {*}
   * @returns {{}}
   */
  convertToJSON(data) {
    let result = {};
    if (typeof data === "object") {
      return data;
    }
    try {
      result = JSON.parse(data);
    } catch (Exception) {
      result = {
        [typeof data]: data
      };
    }
    return result;
  },

  recursiveObjectFormat(targetObject, formatFunction) {
    Object.keys(targetObject).forEach(key => {
      if (typeof targetObject[key] === "object") {
        targetObject[key] = MainFunctions.recursiveObjectFormat(
          targetObject[key],
          formatFunction
        );
      } else {
        targetObject[key] = formatFunction(targetObject[key]);
      }
    });
    return targetObject;
  },

  getUserPosition() {
    return new Promise((resolve, reject) => {
      if ("geolocation" in navigator) {
        // check if geolocation is supported/enabled on current browser
        navigator.geolocation.getCurrentPosition(
          position => {
            // for when getting location is a success
            resolve(position);
            console.log(
              "latitude",
              position.coords.latitude,
              "longitude",
              position.coords.longitude
            );
          },
          errorMessage => {
            reject(errorMessage);
            // for when getting location results in an error
            console.error(
              "An error has occured while retrieving location",
              errorMessage
            );
          }
        );
      } else {
        reject(new Error("geolocation isn't supported"));
        // geolocation is not supported
        // get your location some other way
        console.log("geolocation is not enabled on this browser");
      }
    });
  },

  isExternalServiceRoot: (isFiles, objectId, storageType) => {
    let isServiceRoot =
      isFiles &&
      objectId === "-1" &&
      (storageType === "GD" ||
        storageType === "OD" ||
        storageType === "ODB" ||
        storageType === "SP" ||
        storageType === "SF");
    if (
      !isServiceRoot &&
      storageType === "SP" &&
      isFiles &&
      objectId.startsWith("ST")
    ) {
      isServiceRoot = true;
    }

    return isServiceRoot;
  },

  parseObjectId(objectId) {
    if (objectId) {
      const parts = objectId.split("+");
      if (parts.length < 3) {
        // in case of objectId like "SF+1234567asdf"
        // used for sharing links in editing mode (XENON-50724)
        if (parts.length === 2 && this.storageCodeToServiceName(parts[0])) {
          return {
            storageType: parts[0] || "",
            storageId: "",
            objectId: parts[1] || ""
          };
        }
        return {
          storageType: parts[2] || "",
          storageId: parts[1] || "",
          objectId: parts[0] || ""
        };
      }
      return {
        storageType: parts[0] || "",
        storageId: parts[1] || "",
        objectId: parts[2] || ""
      };
    }
    return {
      storageType: "",
      storageId: "",
      objectId: ""
    };
  },

  encapsulateObjectId(storageType, accountId, objectId) {
    if (storageType.length > 0 && accountId.length > 0) {
      const { objectId: finalObjectId } = this.parseObjectId(objectId);
      return `${storageType}+${accountId}+${finalObjectId}`;
    }
    return objectId;
  },

  compactObject(obj) {
    const clone = _.clone(obj);
    _.each(clone, (value, key) => {
      if (!value) {
        delete clone[key];
      }
    });
    return clone;
  },

  /**
   * Dynamically sets a deeply nested value in an object.
   * Optionally "bores" a path to it if its undefined.
   * @function
   * @param {!object} obj  - The object which contains the value you want to change/set.
   * @param {!array} path  - The array representation of path to the value you want to change/set.
   * @param {!any} value - The value you want to set it to.
   * @param {boolean} setrecursively - If true, will set value of non-existing path as well.
   * @link https://stackoverflow.com/a/46008856/9709961
   */
  setDeep(obj, path, value, setrecursively = false) {
    let level = 0;

    path.reduce((a, b) => {
      level += 1;

      if (
        setrecursively &&
        typeof a[b] === "undefined" &&
        level !== path.length
      ) {
        a[b] = {};
        return a[b];
      }

      if (level === path.length) {
        a[b] = value;
        return value;
      }
      return a[b];
    }, obj);
  },

  getA11yHandler(handlerFunction) {
    return {
      onClick: handlerFunction,
      onKeyDown: event => {
        // ensure enter pressed
        if (event.keycode === 13) {
          handlerFunction(event);
        }
      }
    };
  },

  /**
   * Updates document.body.className
   * @function
   * @param {string[]} classesToAdd - classes that has to be added to body
   * @param {string[]} classesToRemove - classes that has to be removed from body
   * @returns {string}
   */
  updateBodyClasses(classesToAdd = [], classesToRemove = []) {
    if (!Array.isArray(classesToAdd) || !Array.isArray(classesToRemove)) {
      throw new Error("Incorrect arguments for updateBodyClasses");
    }
    let { className } = document.body;
    className = className.split(" ");
    className = _.uniq(className.concat(classesToAdd));
    classesToRemove.forEach(singleClass => {
      if (className.includes(singleClass)) {
        className.splice(className.indexOf(singleClass), 1);
      }
    });
    className = className.map(singleClass => singleClass.trim());
    className = className.join(" ");
    document.body.className = className;
    return className;
  },

  /**
   * Replaces / in btoa with _
   */
  btoaForURLEncoding(encodedString) {
    return encodedString.replace("/", "_");
  },

  /**
   * Replaces _ with /. Reverse of btoaForURLEncoding
   */
  atobForURLEncoding(encodedString) {
    return encodedString.replace("_", "/");
  },

  safeJSONParse(jsonString) {
    let answer = {};
    try {
      answer = JSON.parse(jsonString);
    } catch (ignore) {
      // ignore
    }
    return answer;
  },

  checkBrowser() {
    let version = 11;
    if (navigator.appName === "Microsoft Internet Explorer") {
      // currently IE in all versions have bugs that prevent proper work of Kudo
      const ua = window.navigator.userAgent;

      const msie = ua.indexOf("MSIE ");
      if (msie > 0) {
        // IE 10 or older => return version number
        version = parseInt(ua.substring(msie + 5, ua.indexOf(".", msie)), 10);
      }
      return {
        version,
        status: false,
        name: "Internet Explorer"
      };
    }
    if (
      navigator.userAgent.indexOf("Win") > -1 &&
      navigator.userAgent.indexOf("Safari") > -1 &&
      navigator.userAgent.indexOf("Chrome") === -1
    ) {
      // Safari for Windows disabled - XENON-21811
      return {
        version,
        status: false,
        name: "Safari"
      };
    }
    if (
      navigator.appVersion.indexOf("Trident/") > -1 ||
      navigator.appVersion.indexOf("Edge") > -1
    ) {
      // UPD: Edge has been also disabled: XENON-22291
      return {
        version,
        status: navigator.appVersion.indexOf("Trident/") === -1,
        name:
          navigator.appVersion.indexOf("Trident/") === -1
            ? "Microsoft Edge"
            : "Internet Explorer"
      };
    }
    // assume that all non-IE browsers are allowed
    return {
      version,
      status: true,
      name: "NA"
    };
  },

  formatBytes(bytes, decimals = 2) {
    if (bytes === 0) return "0 B";

    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ["B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"];

    const i = Math.floor(Math.log(bytes) / Math.log(1024));

    return `${parseFloat((bytes / 1024 ** i).toFixed(dm))} ${sizes[i]}`;
  },

  areObjectIdsEqual(objectId, compareObjectId) {
    // for empty ones - false
    if (!objectId || !compareObjectId) return false;
    // full equality, no need to check further
    if (objectId === compareObjectId) return true;
    const { objectId: pureId } = this.parseObjectId(objectId);
    const { objectId: secondPureId } = this.parseObjectId(compareObjectId);
    return pureId === secondPureId;
  },

  downloadBlobAsFile(blob, fileName) {
    const a = document.createElement("a");
    a.style = "display: none";
    document.body.appendChild(a);
    a.href = window.URL.createObjectURL(blob);
    a.download = fileName;
    a.click();
    a.remove();
  },

  mirrorKeys(objWithKeys) {
    const returnObj = {};
    Object.keys(objWithKeys).forEach(key => {
      returnObj[key] = key;
    });
    return returnObj;
  },

  injectIntoString(stringToModify, injectObject) {
    if (!stringToModify || typeof stringToModify !== "string") return "";
    if (!injectObject) return stringToModify;
    if (injectObject instanceof Array) {
      return stringToModify.replace(
        /({\d})/g,
        i => injectObject[i.replace(/{/, "").replace(/}/, "")]
      );
    }
    if (injectObject instanceof Object) {
      if (Object.keys(injectObject).length === 0) {
        return stringToModify;
      }
      return stringToModify.replace(/({([^}]+)})/g, i => {
        const key = i.replace(/{/, "").replace(/}/, "");
        if (!injectObject[key]) {
          return i;
        }
        return injectObject[key];
      });
    }
    return stringToModify;
  },

  /**
   * For large screen android tablets and for apple devices
   * browser-detect library sometimes returns wrong results about mobile device.
   * So additional check must solve this problem until library not fixed.
   * @returns {boolean}
   */
  isMobileDevice() {
    const { mobile, os } = browser();
    if (mobile) return true;

    const isAndroid = /android/i.test(os);
    if (isAndroid) return true;

    // https://developer.apple.com/forums/thread/119186
    // check for apple mobile devices with safari ver 12 or less
    const userAgent = window.navigator.userAgent.toLowerCase();
    const isMobileAppleDevice = /iphone|ipod|ipad/.test(userAgent);
    if (isMobileAppleDevice) return true;

    // check for apple mobile devices with safari ver 13 or above
    const isAppleDevice = /mac/.test(userAgent);
    return isAppleDevice && window.navigator?.maxTouchPoints > 2;
  },

  capitalizeString(str) {
    return str.charAt(0).toUpperCase() + str.slice(1);
  },

  isInIframe() {
    try {
      return window.self !== window.top;
    } catch (e) {
      return true;
    }
  },
  // convert a Unicode string to a string in which
  // each 16-bit unit occupies only one byte
  convertStringToBinary(string) {
    const codeUnits = new Uint16Array(string.length);
    for (let i = 0; i < codeUnits.length; i += 1) {
      codeUnits[i] = string.charCodeAt(i);
    }
    return String.fromCharCode(...new Uint8Array(codeUnits.buffer));
  },

  doesElementHaveScroll(querySelector) {
    const foundDomEntities = document.querySelectorAll(querySelector);
    if (foundDomEntities.length > 0) {
      const entityToCheck = foundDomEntities[0];
      return entityToCheck.scrollHeight > entityToCheck.offsetHeight;
    }
    return false;
  },

  calculateTimeout(text) {
    const wpm = 200; // words per minute
    const wps = wpm / 60; // words per second
    const userReactTime = 2; // time for user to react to message in seconds
    return (Math.ceil(text.split(" ").length / wps) + userReactTime) * 1000;
  },

  getStringHashCode(str) {
    let hash = 0;
    if (str.length === 0) return hash;
    for (let i = 0; i < str.length; i += 1) {
      const chr = str.charCodeAt(i);
      // eslint-disable-next-line no-bitwise
      hash = (hash << 5) - hash + chr;
      // eslint-disable-next-line no-bitwise
      hash |= 0; // Convert to 32bit integer
    }
    return hash;
  },

  isDirectoryUploadSupported() {
    return (
      "directory" in document.createElement("input") ||
      "webkitdirectory" in document.createElement("input")
    );
  },

  splitArrayIntoChunks(originalArray, chunkSize = 10) {
    const chunks = [];
    for (let i = 0; i < originalArray.length; i += chunkSize) {
      chunks.push(originalArray.slice(i, i + chunkSize));
    }
    return chunks;
  },

  splitImmutableMapIntoChunks(originalMap, chunkSize = 10) {
    const chunks = [];
    let chunk = new Immutable.Map();
    // eslint-disable-next-line no-restricted-syntax, guard-for-in
    for (const key of originalMap.keys()) {
      console.log(key);
      chunk = chunk.set(key, originalMap.get(key));
      if (chunk.size >= chunkSize) {
        chunks.push(chunk);
        chunk = new Immutable.Map();
      }
    }
    if (chunk.size > 0) {
      chunks.push(chunk);
    }
    return chunks;
  },

  getErrorMessage(err) {
    let errorMessage;
    if (err) {
      if (err.message) {
        errorMessage = err.message;
      } else if (err.data && err.data.message) {
        errorMessage = err.data.message;
      }
    }
    return errorMessage;
  },

  /**
   * Initiates file download with passed data
   * @param data {ArrayBuffer}
   * @param name {string}
   */
  finalizeDownload(data, name) {
    const url = window.URL.createObjectURL(new Blob([data]));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
  },

  /**
   * Checks if there's an active text selection and we should show OS context menu or Kudo context
   * @param {Event} e
   * @returns {boolean} true if there's a text selection
   */
  isSelection(e) {
    // WB-507
    const selection = window.getSelection();
    if (!selection) return false;
    // range if some text is selected
    const hasSelection = selection.type === "Range";
    if (!e) return hasSelection;
    if (
      selection.type === "None" ||
      !selection.anchorNode ||
      !selection.anchorNode.parentNode
    )
      return false;
    const isSelectionInChild = e.target.contains(
      selection.anchorNode.parentNode
    );
    return hasSelection && isSelectionInChild;
  }
};

export default MainFunctions;
