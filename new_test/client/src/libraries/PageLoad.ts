/* eslint-disable no-console */
import { Buffer } from "buffer";
import * as yup from "yup";
import { browserHistory } from "react-router";
import Tracker from "../utils/Tracker";
import UtmTracker from "../utils/UtmTracker.js";
import Storage from "../utils/Storage";
import MainFunctions from "./MainFunctions";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ApplicationConstants from "../constants/ApplicationConstants";
import {
  languagesToLocales,
  normalizeLocaleAndLang,
  LanguageCode,
  defaultLocale
} from "../utils/languages";
import English from "../assets/translations/en.json";
import UserInfoActions from "../actions/UserInfoActions";
import { flattenMessages } from "../utils/flattenMessages";

const isPathnameSafe = (pathname: string) => {
  const safeRoutes = [
    "index",
    "signup",
    "notify",
    "privacy",
    "terms",
    "open",
    "licenses",
    "file/external"
  ];
  return safeRoutes.some(route => pathname.includes(route));
};
export type KudoConfig = {
  revision: string;
  debug: boolean;
  api: string;
  editorURL: string;
  server: string;
  xeLicensesBase: string;
  oauthURL: string;
  ssoURL: string;
  customerPortalURL: string;
  licensingServerURL: string;
  // @deprecated
  vendor: string;
  // @deprecated
  product: string;
  // eslint-disable-next-line camelcase
  features_enabled: {
    changeURL: boolean;
    editor: boolean;
    fileFilter: boolean;
    intercom: boolean;
  };
  gaurl?: string;
  ga?: string;
};
export interface INestedMessages {
  [key: string]: string | INestedMessages;
}
// Define a TypeScript type called KeyPaths that takes in an object type T as its generic type parameter.
// KeyPaths is defined as a mapped type, which produces a new type by iterating over the keys of T.
type KeyPaths<T extends INestedMessages> = {
  // For each key K in T, create a mapped type that checks whether the value of that key T[K] extends INestedMessages,
  // i.e., whether the value is an object that has string keys and unknown values.
  [K in keyof T]: T[K] extends INestedMessages
    ? // If the value of the key is an object, create a string literal type that represents the path to that object.
      // The path is constructed by combining the current key K with a dot separator and the key paths of the nested object T[K].
      // The & string is used to ensure that TypeScript recognizes this string literal as a string.

      // @ts-ignore
      `${K & string}.${KeyPaths<T[K]> & string}`
    : // If the value of the key is not an object, simply return the key name K.
      K;
  // Finally, index the KeyPaths type by keyof T, which returns a union of all the key paths in the object type T.
  // This means that KeyPaths<T> is a union of all the possible key paths in the object type T.
}[keyof T];

export type TranslationKey = KeyPaths<typeof English>;

export type LoadResult = {
  userLanguage: LanguageCode;
  isEnforced: boolean;
  translations: Record<TranslationKey, string>;
  endPage: string | boolean;
};

let timer = 0;

const isCommander = navigator.userAgent.toLowerCase().includes("commander");
const logging = (message: string, ...rest: Array<string | number | null>) => {
  if (isCommander) {
    console.log("[ ACL AK BOOT]", message, JSON.stringify(rest));
  } else {
    console.info.apply(null, [`[ AK BOOT ] ${message}`, ...rest]);
  }
};

const PageLoad = {
  /**
   * @description Initialize page settings
   */
  initPage(): Promise<LoadResult> {
    timer = Date.now();
    logging("boot started", timer);
    const browserCheckResults = MainFunctions.checkBrowser();
    if (browserCheckResults.status === false) return Promise.reject();
    return new Promise(resolve => {
      this.preloadCorrectConfig().then(config => {
        logging("config is loaded");
        this.setObjectAssignPolyfill();
        logging("objectAssign is polyfilled");

        UtmTracker.initializeTracker();
        try {
          if (MainFunctions.forceBooleanType(config.debug) === true) {
            Tracker.configureTrackJS(config.revision);
          }
          if (config.ga) {
            Tracker.configureGoogleAnalytics(config.ga, config.gaurl);
          }
          if (config.features_enabled.intercom) {
            Tracker.configureIntercom();
          }
        } catch (ex) {
          console.error("Error on configuring trackers");
          console.error(ex);
        }
        logging("Tracking systems are initialized");

        const { userLanguage, isEnforced } = this.loadLanguageSettings();

        logging(
          `User language is detected as: ${userLanguage}. isEnforced: ${isEnforced}`
        );

        let pageType = location.pathname.substring(1);
        pageType = pageType.substring(0, pageType.indexOf("/"));
        if (pageType === "") {
          pageType = "index";
        }

        logging("pageType:", pageType);

        this.setPageStyles(pageType);
        logging("page styles are set");

        const { session, newSessionRequest } =
          this.checkIfNewSessionIsRequired();

        logging(
          "session info:",
          session,
          `new session required: ${newSessionRequest}`
        );

        import(
          /* webpackChunkName: "[request]" */ `../assets/translations/${userLanguage}.json`
        ).then(async translationsResponse => {
          // With switch to Vite "delete" isn't recognized as key in JSON
          // https://graebert.atlassian.net/browse/XENON-60089
          const translations = flattenMessages(
            translationsResponse.default || English
          );
          // save messages to config object

          // for backwards compatibility only
          // this is something we must remove
          // @ts-ignore
          window.ARESKudoConfigObject = config;
          // @ts-ignore
          window.ARESKudoConfigObject.defaultheaders = JSON.stringify([
            { name: "sessionId", value: session },
            { name: "locale", value: Storage.store("locale") }
          ]);
          this.checkBoxToken(config.api).then(isURLChanged => {
            if (!isURLChanged) {
              this.handleGenericEnter(config, session, newSessionRequest).then(
                endPage => {
                  if (endPage !== false) {
                    this.removeInitialPageLoadingElements();
                  }
                  logging("boot ended in ", Date.now() - timer, "ms");
                  resolve({
                    userLanguage,
                    isEnforced,
                    translations,
                    endPage
                  });
                }
              );
            } else {
              this.removeInitialPageLoadingElements();
              resolve({
                userLanguage,
                isEnforced,
                translations,
                endPage: "/file"
              });
            }
          });
        });

        return true;
      });
    });
  },

  handleGenericEnter(
    config: KudoConfig,
    session: string | null,
    newSessionRequest: boolean
  ): Promise<string | boolean> {
    return new Promise(resolve => {
      const { pathname } = location;
      const isIndexPage = pathname === "" || pathname === "/";
      const params = new URLSearchParams(location.search);

      const googleCode = params.get("code") || "";
      const SSOCode = params.get("state") || "";
      logging("Checking redirect parameters from pathname: ", pathname);
      if (isIndexPage && SSOCode.length > 0) {
        // this sometimes happens when sso redirects to the index page instead of /notify
        // let's force it here
        logging("Forced redirect for sso code");
        resolve(`/notify/?mode=account&type=sso&state=${SSOCode}`);
        browserHistory.push(`/notify/?mode=account&type=sso&state=${SSOCode}`);
      } else if (pathname === "/notify" && SSOCode.length > 0) {
        logging("Received SSO login");
        resolve(`/notify/?mode=account&type=sso&state=${SSOCode}`);
      } else if (isIndexPage && googleCode.length > 0) {
        // if google code has been passed - we should render index page
        // as there is google sign in handler in /js/components/pages/IndexPage/IndexPage.jsx
        Storage.unsafeStoreSessionId(session || "");
        resolve("index");
      } else if (session && session.length >= 27) {
        // page w\session
        fetch(`${config.api}/auth`, {
          headers: {
            "Content-Type": "application/json",
            sessionId: session,
            locale: Storage.getItem("locale") || "",
            new: newSessionRequest === true ? "true" : "false"
          }
        })
          .then(resp => {
            if (resp.ok) {
              return resp.json();
            }
            return Promise.reject(resp);
          })
          .then(data => {
            // If sessionId is valid
            Storage.unsafeStoreSessionId(data.sessionId);
            if (isIndexPage) {
              // For index page - redirect to files page
              browserHistory.replace(`/files`);
              resolve(`/files`);
            } else {
              // For all other pages - just open them
              const commanderRedirect = params.get("p") || "";
              const encodedRedirect = params.get("bredirect") || "";
              const simpleRedirect = params.get("redirect") || "";
              logging(
                `Checking redirects in priority: `,
                commanderRedirect,
                encodedRedirect,
                simpleRedirect,
                pathname
              );
              // Do we need to add check for pageType validity?
              // probably not as react-router will just use fallback page - index page
              if (pathname.includes("notify")) {
                logging("Opening notification page");
              } else if (commanderRedirect.length) {
                logging("Using commander redirect - push", commanderRedirect);
                // redirect from commander has the highest priority
                browserHistory.push(commanderRedirect);
              } else if (encodedRedirect.length) {
                try {
                  let decodedRedirect = atob(
                    MainFunctions.atobForURLEncoding(encodedRedirect)
                  );
                  if (decodedRedirect.includes("%")) {
                    // means that it is pre urlencoded
                    decodedRedirect = decodeURIComponent(decodedRedirect);
                  }
                  logging(
                    "Trying to use encoded redirect - push",
                    decodedRedirect
                  );
                  browserHistory.push(decodedRedirect);
                } catch (ex) {
                  logging(
                    `Incorrect encoding for redirect param ${encodedRedirect}`
                  );
                }
              } else if (simpleRedirect.length) {
                try {
                  browserHistory.push(decodeURIComponent(simpleRedirect));
                } catch (ex) {
                  logging(
                    `Incorrect URI encoding for redirect param ${simpleRedirect}`
                  );
                }
              }
              // just use pathname - don't do anything
              resolve(pathname);
            }
          })
          .catch((err: Response) => {
            this.handleNoSessionRedirects(config).then(resolve);
          });
      } else {
        this.handleNoSessionRedirects(config).then(resolve);
      }
    });
  },

  async handleNoSessionRedirects(config: KudoConfig) {
    const { pathname, href } = location;
    const isIndexPage = pathname === "" || pathname === "/";
    const params = new URLSearchParams(location.search);
    const token = params.get("token") || "";
    const isPublicLinkOpen =
      (token || href.includes("external")) &&
      pathname.includes("file") &&
      !pathname.includes("files");
    const currentPageUrl =
      (location.pathname.substring(location.pathname.indexOf("/") + 1) ||
        "files") + location.search;
    const existingSessionId = Storage.getItem("sessionId");
    Storage.clearStorage();
    const pathnameSafety = isPathnameSafe(pathname);
    logging(
      `check if pathname is safe for pathname: ${pathname} - ${pathnameSafety}`
    );
    if (
      (pathnameSafety === false || isIndexPage === true) &&
      isPublicLinkOpen === false
    ) {
      logging(`Redirecting to SSO`);
      if (isIndexPage === true) {
        UserInfoActions.loginWithSSO();
      } else {
        UserInfoActions.loginWithSSO(pathname, false);
      }
      return false;
    }
    if ((existingSessionId || "").length > 0) {
      // ensure logout
      await UserInfoActions.logout();
    }
    if (isPublicLinkOpen) {
      logging(`Opening public link`);
      return pathname;
    }

    if (pathnameSafety === false && isIndexPage === false) {
      logging(`Redirecting to login for auth`);
      // open page that requires authentication
      const endPage = `/?bredirect=${btoa(currentPageUrl)}`;
      browserHistory.push(endPage);
      Storage.store("error", "notLoggedInOrSessionExpired");
      return endPage;
    }
    logging(`Opening safe page`);
    return pathname;
  },

  removeInitialPageLoadingElements() {
    // remove initial css
    if (document.getElementById("loadingCSS")) {
      (document.getElementById("loadingCSS") as HTMLLinkElement).disabled =
        true;
    }
    const loadingScreen = document.getElementById("loadingScreen");
    if (loadingScreen !== null) {
      loadingScreen.style.display = "none";
    }
  },
  // TODO: remove
  setPageStyles(pageType: string) {
    const pageBodyClasses = {
      init: ["index", "signup", "notify", "privacy", "terms"],
      trial: ["trial"]
    };
    Object.keys(pageBodyClasses).forEach(
      (className: keyof typeof pageBodyClasses) => {
        if (pageBodyClasses[className].includes(pageType)) {
          MainFunctions.updateBodyClasses([className], []);
        } else {
          MainFunctions.updateBodyClasses([], [className]);
        }
      }
    );
  },

  checkIfNewSessionIsRequired() {
    // check if new sessionId has to be received.
    // This happens if press "open AK" from AC
    const session = Storage.getItem("sessionId");
    const query = new URLSearchParams(location.search);
    const sessionQuery = `${query.get("sid")}` || "";

    if (sessionQuery && sessionQuery.length >= 27) {
      // WB-1602 - delete query param
      query.delete("sid");
      browserHistory.replace({
        ...location,
        search: query.toString().length > 0 ? `?${query.toString()}` : ""
      });
      Storage.deleteValue("sessionDeleted");

      return { session: sessionQuery, newSessionRequest: true };
    }
    return { newSessionRequest: false, session };
  },

  loadLanguageSettings() {
    // language support check
    // If language is supported - leave it, otherwise - set it to english
    // preload language settings
    let isEnforced = false;
    let { language: userLanguage } = normalizeLocaleAndLang(
      Storage.getItem("lang") || navigator.language
    );
    const query = new URLSearchParams(location.search);
    if (query.has("lang")) {
      const { language: enforcedLanguage } = normalizeLocaleAndLang(
        query.get("lang") || ""
      );
      userLanguage = enforcedLanguage;
      isEnforced = true;
    }
    Storage.setItem("lang", userLanguage);
    Storage.setItem("locale", languagesToLocales[userLanguage]);
    return { userLanguage, isEnforced };
  },

  validateConfig(config: KudoConfig): Promise<KudoConfig> {
    return new Promise((resolve, reject) => {
      if (!config) {
        // AK cannot work without proper config object
        reject(new Error("No config!"));
      } else {
        // Check that api param in config is a URL.
        // If not - transform it to be.
        // Useful for "short" values in config - e.g. "/api"
        // will be transformed to "https://{host.com}/api"
        // TODO: add validation for other options
        const validationSchema = yup.string().url();
        validationSchema
          .isValid(config.api)
          .catch(() => {
            config.api = location.origin + config.api;
          })
          .finally(() => {
            resolve(config);
          });
      }
    });
  },

  setObjectAssignPolyfill() {
    // in some old browsers Object.assign isn't a function,
    // so we have to polyfill it
    // turn off eslint just to not mess up with the polyfill
    if (typeof Object.assign !== "function") {
      Object.assign = (target: object, ...rest: object[]) => {
        if (target == null) {
          throw new TypeError("Cannot convert undefined or null to object");
        }
        const objTarget = Object(target);
        for (let index = 0; index < rest.length; index += 1) {
          const source = rest[index];
          if (source != null) {
            Object.keys(source).forEach((key: keyof typeof source) => {
              objTarget[key] = source[key];
            });
          }
        }
        return target;
      };
    }
  },

  /**
   * @description load config from config file
   */
  preloadCorrectConfig(): Promise<KudoConfig> {
    return new Promise((resolve, reject) => {
      const folder = "/";
      const xhttp = new XMLHttpRequest();
      // eslint-disable-next-line func-names
      xhttp.onreadystatechange = function () {
        if (this.readyState === 4 && this.status === 200) {
          let data: KudoConfig;
          try {
            data = JSON.parse(this.responseText);
          } catch (exception) {
            // this is a critical error, so we'll keep it as alert
            // eslint-disable-next-line no-alert
            alert("Invalid configuration file!");
            reject(new Error("Invalid configuration file!"));
            return;
          }
          // Save timestamp to "hide" notification page from history
          // See XENON-25344, rev. 3705
          Storage.setItem("loadTimestamp", Date.now().toString());
          PageLoad.validateConfig(data).then(validatedConfig => {
            // let's dispatch this config right away.
            AppDispatcher.dispatch({
              actionType: ApplicationConstants.APP_CONFIGURATION_LOADED,
              applicationInfo: validatedConfig
            });
            resolve(validatedConfig);
          });
        }
      };
      xhttp.open("GET", `${folder}configs/config.json`, true);
      xhttp.send();
    });
  },

  /**
   * For Box - we have to try to retrieve auth codes as soon as possible
   * See https://graebert.atlassian.net/browse/XENON-41160
   */
  checkBoxToken(apiURL: string): Promise<boolean> {
    return new Promise(resolve => {
      if (
        location.pathname &&
        location.pathname.length > 0 &&
        location.search &&
        location.search.length > 0 &&
        location.pathname.includes("file/external") &&
        location.search.includes("fileId=") &&
        location.search.includes("type=box") &&
        location.search.includes("auth_code=")
      ) {
        logging("Going to save Box connection data");
        const params = new URLSearchParams(location.search);
        const authCode = params.get("auth_code");
        const boxUserId = params.get("userId");
        Storage.setItem(
          "boxRedirect",
          Buffer.from(location.pathname + location.search).toString("base64")
        );
        const headers: HeadersInit = {
          "Content-Type": "application/json",
          locale: Storage.getItem("locale") || defaultLocale
        };
        if ((Storage.getItem("sessionId") || "").length > 0) {
          headers.sessionId = Storage.getItem("sessionId") || "";
        }
        fetch(`${apiURL}/files/external/box`, {
          method: "POST",
          headers,
          body: JSON.stringify({ authCode, boxUserId })
        })
          .then(resp => resp.json())
          .then(response => {
            logging("Box response: ", JSON.stringify(response));
            resolve(false);
          });
      } else {
        resolve(false);
      }
    });
  }
};
export default PageLoad;
