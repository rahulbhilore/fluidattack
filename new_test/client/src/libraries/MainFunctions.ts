/* eslint-disable no-console */
import _ from "underscore";
import $ from "jquery";
import browser from "browser-detect";
import Immutable from "immutable";
import { PrimitiveType } from "react-intl";

export const APPLE_DEVICE = "apple_device";

/**
 * Set of useful functions
 */
const MainFunctions = {
  isHidden(element: HTMLElement): boolean {
    return element ? element.offsetParent === null : true;
  },

  offset(element: HTMLElement) {
    const rect = element.getBoundingClientRect();
    return {
      top: rect.top + window.screenY,
      left: rect.left + window.scrollX
    };
  },

  inViewport(
    element: HTMLElement,
    container: HTMLElement | Window | undefined
  ): boolean {
    if (this.isHidden(element)) {
      return false;
    }

    let top;
    let left;
    let bottom;
    let right;

    if (typeof container === "undefined" || container instanceof Window) {
      top = window.scrollY;
      left = window.scrollX;
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

  getExtensionFromName(name: string): string {
    return this.parseObjectName(name).extension;
  },

  parseObjectName(name: string): { name: string; extension: string } {
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

  getShortcutDownloadName(shortcutEntity: {
    name?: string;
    filename?: string;
    shortcutInfo: { type?: string; mimeType?: string };
  }): string {
    const name = shortcutEntity.name || shortcutEntity.filename || "";
    if (shortcutEntity.shortcutInfo.type === "folder") {
      return name;
    }

    return `${name}.${this.getExtensionFromName(
      shortcutEntity.shortcutInfo.mimeType || name || ""
    )}`;
  },

  shrinkNameWithExtension(name = "", maxAllowedLength = 30): string {
    if (name?.length > 0 && name.length > maxAllowedLength) {
      const { name: onlyName, extension } = this.parseObjectName(".");
      return MainFunctions.shrinkString(onlyName, maxAllowedLength) + extension;
    }
    return name;
  },

  guid(prefix?: string) {
    return `CS_${
      prefix ? `${prefix}_` : ""
    }${this.s4()}${this.s4()}-${this.s4()}-${this.s4()}-${this.s4()}-${this.s4()}${this.s4()}${this.s4()}`;
  },

  s4(): string {
    return Math.floor((1 + Math.random()) * 0x10000)
      .toString(16)
      .substring(1);
  },

  /**
   * TODO: remove
   * @deprecated
   * Attach jQuery listener for event {event} that happens on {elem}
   */
  attachJQueryListener(
    elem: JQuery<HTMLElement>,
    event: string,
    fun: () => void
  ): void {
    _.each(elem, (el, key) => {
      if (
        !$.data(elem[key], "events") ||
        !$.data(elem[key], "events").length ||
        !$.data(elem[key], "events")[event] ||
        !$.data(elem[key], "events")[event].length
      )
        elem.on(event, fun);
      else if ($.data(elem[key], "events")[event]) {
        let flag = true;
        _.each($.data(elem[key], "events")[event], funElem => {
          if (funElem.name === fun.name) flag = false;
        });
        if (flag) elem.on(event, fun);
      }
    });
  },

  /**
   * @deprecated
   * Detach {event} jQuery listener from {elem}
   */
  detachJQueryListeners(elem: JQuery<HTMLElement>, event: string) {
    _.each(elem, (el, key) => {
      if (event in ($.data(elem[key], "events") || {})) {
        elem.off(event);
      }
    });
  },

  /**
   * @deprecated
   * Returns GET param value with specified {name}
   */
  QueryString(
    name?: string
  ): string | { [key: string]: string | Array<string> } | Array<string> {
    const queryString: { [key: string]: string | Array<string> } = {};
    const query = window.location.search.substring(1);
    const vars = query.split("&");
    for (let i = 0; i < vars.length; i += 1) {
      const pair = [vars[i].split(/=(.+)?/)[0], vars[i].split(/=(.+)?/)[1]];
      let qs = queryString[pair[0]];
      if (typeof qs === "undefined") {
        qs = decodeURIComponent(pair[1]);
      } else if (typeof qs === "string") {
        qs = [qs, decodeURIComponent(pair[1])];
      } else if (Array.isArray(qs)) {
        qs.push(decodeURIComponent(pair[1]));
      }
    }
    if (!name) {
      return queryString;
    }
    return queryString[name] || "";
  },

  /**
   * @deprecated
   * Get current page URL
   */
  detectPageType(): string {
    const route = location.pathname.replace("/", "");
    if (route.includes("resources")) {
      return route;
    }
    if (route.includes("trash") || route.includes("search")) {
      return route.substring(0, route.lastIndexOf("/"));
    }
    return route === ""
      ? "index"
      : route.substring(0, route.indexOf("/")) || route;
  },

  /**
   * Shrinks string up to maxLength
   */
  shrinkString(stringToShrink: string, maxLength = 30): string {
    if (stringToShrink && stringToShrink.length > maxLength) {
      return `${stringToShrink.slice(0, maxLength)}...`;
    }
    return stringToShrink;
  },

  /**
   * Safe url decode
   */
  URLDecode(str: string): string {
    return decodeURIComponent(`${str}`.replace(/\+/g, "%20"));
  },

  /**
   * @deprecated
   * Get storage name for end user from service storage name (e.g. gdrive -> Google Drive)
   */
  serviceStorageNameToEndUser(storageName: string) {
    const storageObject = _.find(
      // @ts-ignore
      window.ARESKudoConfigObject.storage_features,
      storage => storage.name.toLowerCase() === storageName.toLowerCase()
    );
    if (storageObject) return storageObject.displayName;
    return storageName;
  },

  /**
   * @deprecated
   * Get storage name for end user from service storage name (e.g. Google Drive -> gdrive)
   */
  endUserStorageNameToService(storageName: string) {
    const storageObject = _.find(
      // @ts-ignore
      window.ARESKudoConfigObject.storage_features,
      storage => storage.displayName.toLowerCase() === storageName.toLowerCase()
    );
    if (storageObject) return storageObject.name;
    return storageName;
  },

  /**
   * TODO: move into a separate class
   * Get storage name for end user from service storage name (e.g. GD -> Google Drive)
   */
  storageCodeToServiceName(storageCode: string): string {
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
   */
  serviceNameToStorageCode(serviceName: string): string {
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
   */
  serviceNameToUserName(serviceName: string): string {
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
   */
  forceBooleanType(pseudoBoolean: string | boolean): boolean {
    try {
      const unifiedString = pseudoBoolean.toString().toLowerCase().trim();
      return unifiedString === "true";
    } catch (ex) {
      return false;
    }
  },

  /**
   * @description Converts url into absolute path url if it's not already
   */
  forceFullURL(urlToValidate: string): string {
    try {
      // @ts-ignore
      const url = new URL(urlToValidate);
    } catch (err) {
      return location.origin + urlToValidate;
    }

    return urlToValidate;
  },

  /**
   * @description Set classes specified in styleSet, removes all other classes applied
   */
  setBodyStyles(styleSet: Array<string>) {
    const { body } = document;
    body.className = styleSet.join(" ");
  },

  /**
   * @description Converts variable to JSON if possible
   */
  convertToJSON(data: string): object {
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

  recursiveObjectFormat(
    targetObject: { [key: string]: unknown },
    formatFunction: (val: unknown) => unknown
  ) {
    Object.keys(targetObject).forEach(key => {
      if (typeof targetObject[key] === "object") {
        targetObject[key] = MainFunctions.recursiveObjectFormat(
          targetObject[key] as { [key: string]: unknown },
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

  isExternalServiceRoot: (
    isFiles: boolean,
    objectId: string,
    storageType: string
  ) => {
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

  parseObjectId(objectId: string) {
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

  encapsulateObjectId(
    storageType: string,
    accountId: string,
    objectId: string
  ) {
    if (storageType.length > 0 && accountId.length > 0) {
      const { objectId: finalObjectId } = this.parseObjectId(objectId);
      return `${storageType}+${accountId}+${finalObjectId}`;
    }
    return objectId;
  },

  compactObject(obj: { [key: string]: unknown }) {
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
   * @link https://stackoverflow.com/a/46008856/9709961
   */
  setDeep(
    obj: { [key: string]: unknown },
    path: Array<string>,
    value: unknown,
    isRecursive: boolean = false
  ) {
    let level = 0;

    path.reduce((a, b) => {
      level += 1;

      if (isRecursive && typeof a[b] === "undefined" && level !== path.length) {
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

  getA11yHandler(handlerFunction: (e: React.UIEvent<unknown>) => void) {
    return {
      onClick: handlerFunction,
      onKeyDown: (event: React.KeyboardEvent<unknown>) => {
        // ensure enter pressed
        if (event.key === "Enter") {
          handlerFunction(event);
        }
      }
    };
  },

  /**
   * Updates document.body.className
   */
  updateBodyClasses(
    classesToAdd: string[] = [],
    classesToRemove: string[] = []
  ): string {
    if (!Array.isArray(classesToAdd) || !Array.isArray(classesToRemove)) {
      throw new Error("Incorrect arguments for updateBodyClasses");
    }
    const { className } = document.body;
    const splitClasses = className.split(" ");
    const uniqueClasses = new Set([...splitClasses, ...classesToAdd]);
    classesToRemove.forEach(singleClass => {
      if (uniqueClasses.has(singleClass)) {
        uniqueClasses.delete(singleClass);
      }
    });
    const newClasses = Array.from(uniqueClasses.values()).map(singleClass =>
      singleClass.trim()
    );
    const finalClassName = newClasses.join(" ");
    document.body.className = finalClassName;
    return finalClassName;
  },

  /**
   * Replaces / in btoa with _
   */
  btoaForURLEncoding(encodedString: string) {
    return encodedString.replace("/", "_");
  },

  /**
   * Replaces _ with /. Reverse of btoaForURLEncoding
   */
  atobForURLEncoding(encodedString: string) {
    return encodedString.replace("_", "/");
  },

  safeJSONParse(jsonString: string): { [key: string]: unknown } {
    let answer: { [key: string]: unknown } = {};
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

  formatBytes(bytes: number, decimals = 2) {
    if (bytes === 0) return "0 B";

    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ["B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB"];

    const i = Math.floor(Math.log(bytes) / Math.log(1024));

    return `${parseFloat((bytes / 1024 ** i).toFixed(dm))} ${sizes[i]}`;
  },

  areObjectIdsEqual(objectId: string, compareObjectId: string) {
    // for empty ones - false
    if (!objectId || !compareObjectId) return false;
    // full equality, no need to check further
    if (objectId === compareObjectId) return true;
    const { objectId: pureId } = this.parseObjectId(objectId);
    const { objectId: secondPureId } = this.parseObjectId(compareObjectId);
    return pureId === secondPureId;
  },

  downloadBlobAsFile(blob: Blob, fileName: string) {
    const a = document.createElement("a");
    a.setAttribute("style", "display: none");
    document.body.appendChild(a);
    a.href = window.URL.createObjectURL(blob);
    a.download = fileName;
    a.click();
    a.remove();
  },

  mirrorKeys(objWithKeys: { [key: string]: unknown }): {
    [key: string]: string;
  } {
    const returnObj: {
      [key: string]: string;
    } = {};
    Object.keys(objWithKeys).forEach(key => {
      returnObj[key] = key;
    });
    return returnObj;
  },

  injectIntoString(
    stringToModify: string,
    injectObject: Record<string, PrimitiveType>
  ) {
    if (!stringToModify || typeof stringToModify !== "string") return "";
    if (!injectObject) return stringToModify;
    if (injectObject instanceof Array) {
      return stringToModify.replace(
        /({\d})/g,
        i => injectObject[i.replace(/{/, "").replace(/}/, "")]?.toString() || ""
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
        return injectObject[key]?.toString() || "";
      });
    }
    return stringToModify;
  },

  /**
   * For large screen android tablets and for apple devices
   * browser-detect library sometimes returns wrong results about mobile device.
   * So additional check must solve this problem until library not fixed.
   */
  isMobileDevice(): boolean {
    const { mobile, os } = browser();
    if (mobile) return true;

    const isAndroid = /android/i.test(os || "");
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

  capitalizeString(str: string) {
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
  convertStringToBinary(string: string) {
    const codeUnits = new Uint16Array(string.length);
    for (let i = 0; i < codeUnits.length; i += 1) {
      codeUnits[i] = string.charCodeAt(i);
    }
    return new TextDecoder().decode(codeUnits.buffer);
  },

  doesElementHaveScroll<K extends keyof HTMLElementTagNameMap>(
    querySelector: K
  ): boolean {
    const foundDomEntities = document.querySelectorAll(querySelector);
    if (foundDomEntities.length > 0) {
      const entityToCheck = foundDomEntities[0];
      return entityToCheck.scrollHeight > entityToCheck.offsetHeight;
    }
    return false;
  },

  calculateTimeout(text: string) {
    const wpm = 200; // words per minute
    const wps = wpm / 60; // words per second
    const userReactTime = 2; // time for user to react to message in seconds
    return (Math.ceil(text.split(" ").length / wps) + userReactTime) * 1000;
  },

  getStringHashCode(str: string) {
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

  splitArrayIntoChunks<K>(
    originalArray: Array<K>,
    chunkSize = 10
  ): Array<Array<K>> {
    const chunks = [];
    for (let i = 0; i < originalArray.length; i += chunkSize) {
      chunks.push(originalArray.slice(i, i + chunkSize));
    }
    return chunks;
  },

  splitImmutableMapIntoChunks<K, V>(
    originalMap: Immutable.Map<K, V>,
    chunkSize = 10
  ): Array<Immutable.Map<K, V>> {
    const chunks = [];
    let chunk: Immutable.Map<K, V> = Immutable.Map();
    // eslint-disable-next-line
    for (const key of originalMap.keys()) {
      if (originalMap.has(key) && typeof originalMap.get(key) !== "undefined") {
        chunk = chunk.set(key, originalMap.get(key) as V);
        if (chunk.size >= chunkSize) {
          chunks.push(chunk);
          chunk = Immutable.Map();
        }
      }
    }
    if (chunk.size > 0) {
      chunks.push(chunk);
    }
    return chunks;
  },

  getErrorMessage(err: unknown): string {
    if (typeof err !== "undefined" && err !== null) {
      if (err instanceof Error && err.message) {
        return err.message;
      }
      // TODO: try to generify. Maybe use axios?
      const possiblyTypedError = err as { data?: { message?: string } };
      if (possiblyTypedError.data && possiblyTypedError.data.message) {
        return possiblyTypedError.data.message;
      }
    }
    return "";
  },

  /**
   * Initiates file download with passed data
   */
  finalizeDownload(data: ArrayBuffer, name: string) {
    return this.downloadBlobAsFile(new Blob([data]), name);
  },

  /**
   * Checks if there's an active text selection and we should show OS context menu or Kudo context
   * @param {Event} e
   * @returns {boolean} true if there's a text selection
   */
  isSelection(e: Event): boolean {
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
    if (!e.target) return false;
    if (e.target instanceof Element) {
      const isSelectionInChild = e.target.contains(
        selection.anchorNode.parentNode
      );
      return hasSelection && isSelectionInChild;
    }
    return false;
  },

  async waitForTimeout(timeout: number): Promise<void> {
    return new Promise(resolve => {
      setTimeout(() => {
        resolve();
      }, timeout);
    });
  }
};

export default MainFunctions;
