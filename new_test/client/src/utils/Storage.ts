import _ from "underscore";

function setCookie(name: string, value?: string, options?: CookieOptions) {
  if (!value) return;
  const encodedValue = encodeURIComponent(value);

  let updatedCookie = `${name}=${encodedValue}`;

  Object.keys(options || {}).forEach((propName: keyof CookieOptions) => {
    updatedCookie += `; ${propName}`;
    if (options) {
      const propValue = options[propName];
      if (propValue !== true) {
        updatedCookie += `=${propValue}`;
      }
    }
  });

  document.cookie = updatedCookie;
}

function getCookieValue(name: string) {
  const matches = document.cookie.match(
    new RegExp(
      // eslint-disable-next-line no-useless-escape
      `(?:^|; )${name.replace(/([\.$?*|{}\(\)\[\]\\\/\+^])/g, "\\$1")}=([^;]*)`
    )
  );
  return matches ? decodeURIComponent(matches[1]) : undefined;
}

function getCookieDomain() {
  const { origin = "" } = location;
  const splitOrigin = origin.split(".");
  if (splitOrigin.length >= 2) {
    return `.${splitOrigin[splitOrigin.length - 2]}.${
      splitOrigin[splitOrigin.length - 1]
    }`;
  }
  return ".graebert.com";
}

function isDevEnv() {
  return (
    process.env.NODE_ENV === "development" ||
    // @ts-ignore
    import.meta.env.MODE === "testing"
  );
}

export default class Storage {
  static isLocalStorageSupported = Storage.getStorageSupport();

  static isSessionStorageSupported = Storage.getSessionStorageSupport();

  /**
   * Check what storage is supported.
   * @return {boolean}
   */
  static getStorageSupport(): boolean {
    let lsSupport = true;
    try {
      localStorage.setItem("test", "test");
      localStorage.removeItem("test");
      lsSupport = true;
    } catch (e) {
      lsSupport = false;
    }
    return lsSupport;
  }

  /**
   * Check if session storage is supported.
   * @return {boolean}
   */
  static getSessionStorageSupport(): boolean {
    let ssSupport = true;
    try {
      sessionStorage.setItem("test", "test");
      sessionStorage.removeItem("test");
      ssSupport = true;
    } catch (e) {
      ssSupport = false;
    }
    return ssSupport;
  }

  static deleteValue(key: string, forceToUseCookie: boolean = false) {
    if (
      Storage.isLocalStorageSupported &&
      key !== "sessionId" &&
      forceToUseCookie !== true
    ) {
      localStorage.removeItem(key);
    } else {
      const isDev = isDevEnv();
      if (isDev) {
        document.cookie = `${key}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/;`;
      } else {
        document.cookie = `${key}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; SameSite=None; Secure; domain=${getCookieDomain()}`;
      }
    }
  }

  static getItem(
    key: string,
    forceToUseCookie: boolean = false
  ): string | null {
    // for session cookie handling
    if (key === "sessionId" && Storage.getItem("sessionDeleted") === "true") {
      return null;
    }
    if (
      Storage.isLocalStorageSupported &&
      (key !== "sessionId" || isDevEnv()) &&
      forceToUseCookie !== true
    ) {
      return localStorage.getItem(key);
    }
    return getCookieValue(key) || null;
  }

  static setItem(
    key: string,
    value: string,
    forceToUseCookie: boolean = false
  ): boolean {
    try {
      const isDev = isDevEnv();
      if (
        Storage.isLocalStorageSupported &&
        (key !== "sessionId" || isDev) &&
        forceToUseCookie !== true
      ) {
        localStorage.setItem(key, value);
        return true;
      }
      const cookieExpirationTime = new Date(
        Date.now() + 365 * 24 * 60 * 60 * 1000
      );
      if (isDev) {
        setCookie(key, value, { expires: cookieExpirationTime, path: "/" });
      } else {
        setCookie(key, value, {
          expires: cookieExpirationTime,
          path: "/",
          sameSite: "None",
          secure: true,
          domain: getCookieDomain()
        });
      }
      return true;
    } catch (ex: unknown) {
      console.error(`Error trying to store ${key}:${value}`);
      return false;
    }
  }

  static store(
    key: string,
    value?: string,
    forceToUseCookies: boolean = false
  ) {
    if (value) {
      return Storage.setItem(key, value, forceToUseCookies);
    }

    return Storage.getItem(key, forceToUseCookies);
  }

  /**
   * Clear all info in user's storage except language settings
   */
  static clearStorage() {
    const sessionId = getCookieValue("sessionId");
    if (Storage.isLocalStorageSupported) {
      // save some values that should exist even if user logs out
      const {
        lang,
        locale,
        latestNotify,
        loadTimestamp,
        EULAAccepted,
        CPAccepted,
        lastViewedVersion,
        boxRedirect
      } = localStorage;
      localStorage.clear();
      // sessionId, token and other info has to be removed from cookies!
      const cookies = document.cookie.split(";");
      for (let i = 0; i < cookies.length; i += 1) {
        const cookie = cookies[i];
        const eqPos = cookie.indexOf("=");
        const name = (eqPos > -1 ? cookie.substr(0, eqPos) : cookie).trim();
        Storage.deleteValue(name, true);
      }
      localStorage.setItem("boxRedirect", boxRedirect);
      localStorage.setItem("lang", lang);
      localStorage.setItem("locale", locale);
      localStorage.setItem("loadTimestamp", loadTimestamp);
      localStorage.setItem("latestNotify", latestNotify);
      localStorage.setItem("EULAAccepted", EULAAccepted);
      localStorage.setItem("CPAccepted", CPAccepted);
      localStorage.setItem("lastViewedVersion", lastViewedVersion);
    } else {
      const lang = getCookieValue("lang");
      const locale = getCookieValue("locale");
      const loadTimestamp = getCookieValue("loadTimestamp");
      const latestNotify = getCookieValue("latestNotify");
      const EULAAccepted = getCookieValue("EULAAccepted");
      const CPAccepted = getCookieValue("CPAccepted");
      const lastViewedVersion = getCookieValue("lastViewedVersion");
      const boxRedirect = getCookieValue("boxRedirect");

      const cookies = document.cookie.split(";");
      for (let i = 0; i < cookies.length; i += 1) {
        const cookie = cookies[i];
        const eqPos = cookie.indexOf("=");
        const name = eqPos > -1 ? cookie.substr(0, eqPos) : cookie;
        Storage.deleteValue(name);
      }

      const isDev = isDevEnv();
      const cookieExpirationTime = new Date(
        Date.now() + 365 * 24 * 60 * 60 * 1000
      );

      const options = {
        expires: cookieExpirationTime,
        path: "/",
        secure: !isDev
      };

      setCookie("lang", lang, options);
      setCookie("locale", locale, options);
      setCookie("latestNotify", latestNotify, options);
      setCookie("loadTimestamp", loadTimestamp, options);
      setCookie("EULAAccepted", EULAAccepted, options);
      setCookie("CPAccepted", CPAccepted, options);
      setCookie("lastViewedVersion", lastViewedVersion, options);
      setCookie("boxRedirect", boxRedirect, options);
    }
    if (sessionId) {
      Storage.setItem("sessionDeleted", "true");
    }
  }

  static setSessionStorageItems(items: { [key: string]: string }) {
    try {
      Object.entries(items).forEach(([key, value]) => {
        window.sessionStorage.setItem(key, value);
      });
    } catch (ex: unknown) {
      console.error(`Cannot set session storage items:`, items, ex);
    }
  }

  static getSessionStorageItem(key: string): string | boolean {
    try {
      const value = window.sessionStorage.getItem(key);
      if (value === undefined || value === null) {
        return false;
      }
      return JSON.parse(value);
    } catch (ex: unknown) {
      console.error(`Cannot get session storage item: ${key}`, ex);
    }
    return false;
  }

  static deleteSessionStorageItems(keys: Array<string>) {
    try {
      keys.forEach(key => window.sessionStorage.removeItem(key));
    } catch (ex: unknown) {
      console.error(`Cannot remove session storage items: ${keys}`, ex);
    }
  }

  static clearSessionStorage() {
    try {
      if (window.sessionStorage.length > 0) {
        window.sessionStorage.clear();
      }
    } catch (ex: unknown) {
      console.error(`Cannot clear session storage`, ex);
    }
  }

  static unsafeStoreSessionId(sessionId: string) {
    if (isDevEnv()) {
      // eslint-disable-next-line no-console
      console.warn(
        "Saving sessionId from client-side. Shouldn't be used in production"
      );
      Storage.setItem("sessionId", sessionId);
    }
  }
}
