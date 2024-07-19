import EventEmitter from "events";
import _ from "underscore";
import { browserHistory } from "react-router";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as ApplicationConstants from "../constants/ApplicationConstants";
import ApplicationActions from "../actions/ApplicationActions";
import MainFunctions from "../libraries/MainFunctions";
import Storage from "../utils/Storage";
import ColorSchemes from "../constants/appConstants/Material/ColorSchemes";

const minConfigCheckModifier = 5;
const appSettingsList = [
  "revision",
  "domain",
  "debug",
  "apiURL",
  "editorURL",
  "server",
  "oauthURL",
  "ssoURL",
  "customerPortalURL",
  "licensingServerURL",
  "userWebsocketURL",
  "viewType",
  "defaultTitle",
  "UIEventTime",
  "UIPrefix",
  "trial",
  "featuresEnabled",
  "storagesSettings",
  "customization",
  "vendor",
  "styleSheet",
  "product",
  "locale",
  "language",
  "xeLicensesBase",
  "a3url"
];
let revisionChangesTimer = minConfigCheckModifier;
let revisionTimer = null;

export const UPDATE = "UPDATE";
export const CONFIG_LOADED = "CONFIG_LOADED";
export const TOGGLE_TERMS = "TOGGLE_TERMS";
export const COLOR_SCHEME_CHANGED = "COLOR_SCHEME_CHANGED";
export const SIDEBAR_SWITCHED = "SIDEBAR_SWITCHED";
export const SEARCH_SWITCHED = "SEARCH_SWITCHED";
export const USER_MENU_SWITCHED = "USER_MENU_SWITCHED";
export const DRAWING_MENU_SWITCHED = "DRAWING_MENU_SWITCHED";
export const SWITCH_OLD_RESOURCES = "SWITCH_OLD_RESOURCES";

/**
 * @class
 * @extends EventEmitter
 * @classdesc Stores application state (editor url, revision, etc.)
 */
class ApplicationStore extends EventEmitter {
  /**
   * @constructor
   */
  constructor() {
    super();
    this.applicationConfigWasLoaded = false;
    this.revision = "0";
    this.debug = false;
    this.apiURL = "/api";
    this.editorURL = "/editor";
    this.xeLicensesBase = "/licenses";
    this.server = "/";
    this.oauthURL = "/oauth";
    this.ssoURL = "https://sso.dev.graebert.com";
    this.UIPrefix = "/";
    this.customerPortalURL = "";
    this.domain = "";
    this.licensingServerURL = "";
    this.viewType = "blockTable";
    this.defaultTitle = "ARES Kudo";
    this.UIEventTime = 200;
    this.trial = false;
    this.a3url = "";
    this.featuresEnabled = {
      changeURL: true,
      editor: true,
      independentLogin: true,
      lazyLoad: false,
      fileFilter: true,
      intercom: false,
      revitAll: false,
      revitAdmins: false,
      companiesAll: false,
      companiesAdmin: false,
      commentsAll: false,
      commentsAdmin: false
    };
    this.vendor = "Graebert GmbH";
    this.styleSheet = "Graebert";
    this.product = "ARES Kudo";
    this.userWebsocketURL = "";
    this.customization = {
      logoURL: {
        initial: "initial/kudo-logo-default.png",
        login: null,
        smallLogo: "images/kudo-logo-small.svg",
        logoPic1: "",
        logoPic2: "",
        logoPic3: ""
      },
      useMultipleImagesForLogo: false,
      showAvatar: false,
      showResetPasswordButton: false,
      showFeedbackButton: false,
      showCreateAccountButton: false,
      showLoginFields: false,
      showLearnMore: false,
      showDSLoginButton: false,
      publicLinksEnabled: true,
      externalStoragesAvailable: true,
      showLinksInIndexFooter: false,
      showEULAInsteadOfPP: false,
      feedbackLink: "",
      learnMoreURL: "https://www.graebert.com/cad-software/ares-kudo/",
      privacyPolicyLink:
        "https://customer-portal-testing.graebert.com/about/privacypolicy",
      EULALink: "/EULA/SW/eula.htm",
      backgroundURL: "/initial/bg.jpg",
      termsOfUse: "/termsofuse.html",
      showEULA: false,
      navButtonsPosition: {
        filename: "left",
        switchEditorIcon: "left",
        shareIcon: "left",
        changeURLIcon: "left",
        searchField: "right"
      },
      showHelpButtonInNavBar: false,
      quickTourVideo: "",
      buyURL: "",
      showFooterOnFileLoaderPage: false,
      DSLink: "",
      DS3DExpLogo: "",
      showCompliance: false
    };
    this.storagesSettings = [];
    this.language = Storage.store("lang");
    this.locale = Storage.store("locale");
    this.colorScheme = ColorSchemes.getActiveScheme();
    this.sidebarState = false;
    this.searchCollapseState = false;
    this.userMenuState = false;
    this.drawingMenuState = false;

    ApplicationActions.loadApplicationConfiguration();
    // load revision number to see if it has changed
    setTimeout(() => {
      ApplicationActions.loadRevisionNumber();
    }, 2000);
    ApplicationStore.initializeLogger();
    ApplicationStore.setRevisionCheckTimer();
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));

    this.useOldResources = true;
  }

  /**
   * @method
   * @private
   * @description Make logger actions available through regular console (for testing)
   */
  static initializeLogger() {
    // init logger
    window.reloadApp = ApplicationActions.triggerApplicationReload;
    window.spamDummyEvents = () => {
      for (let i = 0; i < 100; i += 1) {
        AppDispatcher.dispatch({
          actionType: "DUMMY_EVENT",
          number: Math.random()
        });
      }
    };
  }

  /**
   * @method
   * @private
   * @description Set exponential timer that will check for configuration update
   */
  static setRevisionCheckTimer() {
    revisionTimer = setTimeout(
      () => {
        ApplicationActions.loadRevisionNumber();
        revisionChangesTimer += 1;
        clearTimeout(revisionTimer);
        ApplicationStore.setRevisionCheckTimer();
      },
      2 ** revisionChangesTimer * 1000
    );
  }

  sessionExpiredHandler = () => {
    Storage.clearStorage();
    if (
      MainFunctions.detectPageType() !== "index" &&
      ((!MainFunctions.QueryString("token") &&
        location.href.indexOf("external") === -1) ||
        MainFunctions.detectPageType() !== "file") &&
      (Storage.store("extend") !== "true" ||
        MainFunctions.detectPageType() !== "trial")
    ) {
      const { Intercom } = window;
      if (this.featuresEnabled.intercom === true && Intercom) {
        Intercom("shutdown");
        Intercom("boot", {
          app_id: "dzoczj6l",
          created_at: Date.now(),
          sitename: location.hostname
        });
      }
      Storage.store("error", "notLoggedInOrSessionExpired");
      const { UIPrefix } = this;
      const currentPageUrl =
        location.pathname.substr(location.pathname.indexOf(UIPrefix) + 1) +
        location.search;
      ApplicationActions.changePage(
        `${UIPrefix}?redirect=${encodeURIComponent(currentPageUrl)}`,
        "AS_SessionExpired"
      );
    }
  };

  /**
   * @private
   * @method
   * @description Save external config file info as application configuration
   * @param newApplicationInfo {{revision:string}}
   */
  parseExternalConfig(newApplicationInfo) {
    this.revision = newApplicationInfo.revision.toString();
    this.debug = MainFunctions.forceBooleanType(newApplicationInfo.debug);
    this.apiURL = MainFunctions.forceFullURL(newApplicationInfo.api);
    this.editorURL = MainFunctions.forceFullURL(newApplicationInfo.editor);
    this.server = MainFunctions.forceFullURL(newApplicationInfo.server);
    this.xeLicensesBase = MainFunctions.forceFullURL(
      newApplicationInfo.xeLicensesBase
    );
    this.oauthURL = MainFunctions.forceFullURL(newApplicationInfo.oauth);
    this.ssoURL = MainFunctions.forceFullURL(newApplicationInfo.ssoURL);
    this.UIPrefix = newApplicationInfo.UIPrefix;
    this.customerPortalURL = MainFunctions.forceFullURL(
      newApplicationInfo.customerPortalURL
    );
    this.licensingServerURL = MainFunctions.forceFullURL(
      newApplicationInfo.licensingServerURL
    );
    this.viewType = newApplicationInfo.viewType;
    this.defaultTitle = newApplicationInfo.defaultTitle || "ARES Kudo";
    this.UIEventTime = newApplicationInfo.UIEventTime || 200;
    this.trial = MainFunctions.forceBooleanType(newApplicationInfo.trial);
    this.vendor = newApplicationInfo.vendor || "Graebert GmbH";
    this.styleSheet = newApplicationInfo.styleSheet || "Graebert";
    this.userWebsocketURL = newApplicationInfo.userWebSocketURL || "";
    this.product = newApplicationInfo.product || "ARES Kudo";
    this.domain = newApplicationInfo.domain || "";
    this.a3url = newApplicationInfo.a3url || "";
    this.featuresEnabled = {
      changeURL: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.change_url
      ),
      editor: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.editor
      ),
      independentLogin: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.independent_login
      ),
      lazyLoad: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.lazy_load
      ),
      fileFilter: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.file_filter
      ),
      revitAll: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.revitAll
      ),
      companiesAll: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.companiesAll
      ),
      companiesAdmin: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.companiesAdmin
      ),
      revitAdmins: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.revitAdmins
      ),
      commentsAll: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.commentsAll
      ),
      commentsAdmin: MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.commentsAdmin
      )
    };
    if (
      !Object.prototype.hasOwnProperty.call(this.featuresEnabled, "intercom")
    ) {
      this.featuresEnabled.intercom = MainFunctions.forceBooleanType(
        newApplicationInfo.features_enabled.intercom
      );
    }
    this.customization = {
      logoURL: {
        initial:
          newApplicationInfo.customization.logoURL.initial ||
          "initial/kudo-logo-default.png",
        login: newApplicationInfo.customization.logoURL.login || null,
        smallLogo:
          newApplicationInfo.customization.logoURL.smallLogo ||
          "images/kudo-logo-small.svg",
        logoPic1: newApplicationInfo.customization.logoURL.logoPic1 || "",
        logoPic2: newApplicationInfo.customization.logoURL.logoPic2 || "",
        logoPic3: newApplicationInfo.customization.logoURL.logoPic3 || ""
      },
      useMultipleImagesForLogo: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.useMultipleImagesForLogo
      ),
      showAvatar: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showAvatar
      ),
      showResetPasswordButton: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showResetPasswordButton
      ),
      showCreateAccountButton: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showCreateAccountButton
      ),
      showFeedbackButton: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showFeedbackButton
      ),
      feedbackLink: newApplicationInfo.customization.feedbackLink || "",
      learnMoreURL:
        newApplicationInfo.customization.learnMoreURL ||
        "https://www.graebert.com/cad-software/ares-kudo/",
      showLoginFields: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showLoginFields
      ),
      showLearnMore: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showLearnMore
      ),
      showDSLoginButton: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showDSLoginButton
      ),
      publicLinksEnabled: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.publicLinksEnabled
      ),
      externalStoragesAvailable: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.externalStoragesAvailable
      ),
      showLinksInIndexFooter: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showLinksInIndexFooter
      ),
      showEULAInsteadOfPP: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showEULAInsteadOfPP
      ),
      privacyPolicyLink:
        newApplicationInfo.customization.privacyPolicyLink ||
        "https://customer-portal-testing.graebert.com/about/privacypolicy",
      EULALink:
        newApplicationInfo.customization.EULALink || "/EULA/SW/eula.htm",
      backgroundURL:
        newApplicationInfo.customization.backgroundURL || "/initial/bg.jpg",
      termsOfUse:
        newApplicationInfo.customization.termsOfUse || "/termsofuse.html",
      showEULA: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showEULA
      ),
      navButtonsPosition: {
        filename:
          newApplicationInfo.customization.navButtonsPosition.filename ||
          "left",
        switchEditorIcon:
          newApplicationInfo.customization.navButtonsPosition
            .switchEditorIcon || "left",
        shareIcon:
          newApplicationInfo.customization.navButtonsPosition.shareIcon ||
          "left",
        changeURLIcon:
          newApplicationInfo.customization.navButtonsPosition.changeURLIcon ||
          "left",
        searchField:
          newApplicationInfo.customization.navButtonsPosition.searchField ||
          "right"
      },
      showHelpButtonInNavBar: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showHelpButtonInNavBar
      ),
      quickTourVideo: newApplicationInfo.customization.quickTourVideo || "",
      buyURL: newApplicationInfo.customization.buyURL || "",
      showFooterOnFileLoaderPage:
        newApplicationInfo.customization.showFooterOnFileLoaderPage || false,
      DSLink: newApplicationInfo.customization.DSLink || "",
      DS3DExpLogo: newApplicationInfo.customization.DS3DExpLogo || "",
      showCompliance: MainFunctions.forceBooleanType(
        newApplicationInfo.customization.showCompliance
      )
    };
    this.storagesSettings = _.flatten(
      _.map(newApplicationInfo.storage_features, storageInfo => {
        if (
          MainFunctions.forceBooleanType(
            storageInfo.isIndependentVersionOnly
          ) === true &&
          MainFunctions.forceBooleanType(
            newApplicationInfo.features_enabled.independent_login
          ) === false
        ) {
          return null;
        }
        return {
          name: storageInfo.name,
          displayName: storageInfo.displayName,
          active: MainFunctions.forceBooleanType(storageInfo.active),
          adminOnly: MainFunctions.forceBooleanType(storageInfo.adminOnly),
          profileOption: MainFunctions.forceBooleanType(
            storageInfo.profileOption
          )
        };
      })
    );
    // backward compatibility
    window.ARESKudoConfigObject = _.defaults(this, window.ARESKudoConfigObject);
  }

  /**
   * @method
   * @public
   * @description Returns application setting
   */
  getApplicationSettingsObject() {
    return this;
  }

  /**
   * @method
   * @public
   * @description Returns application setting
   * @param settingName {string}
   */
  getApplicationSetting(settingName) {
    if (appSettingsList.indexOf(settingName) > -1) {
      return this[settingName];
    }
    return null;
  }

  /**
   * @private
   * @method
   * @description Check if revision has changed and app has to be updated
   * @param newApplicationInfo {{revision:string}}
   */
  checkIfUpdateIsRequired(newApplicationInfo) {
    if (this.applicationConfigWasLoaded === false) {
      this.applicationConfigWasLoaded = true;
      this.parseExternalConfig(newApplicationInfo);
    } else if (
      newApplicationInfo.revision !== this.revision &&
      newApplicationInfo.debug === false
    ) {
      if (MainFunctions.detectPageType() === "file") {
        setTimeout(() => {
          this.checkIfUpdateIsRequired(newApplicationInfo);
        }, 10000);
      } else {
        // check if sw is good enough
        // ModalActions.requestUIReload();
      }
    }
  }

  /**
   * @private
   * @method
   * @description Check if revision has changed and app has to be updated
   * @param newRevisionNumber {string}
   */
  checkIfRevisionChange(newRevisionNumber) {
    if (
      this.applicationConfigWasLoaded === true &&
      newRevisionNumber !== this.revision &&
      this.debug === false &&
      process.env.NODE_ENV !== "development"
    ) {
      if (MainFunctions.detectPageType() === "file") {
        // ignore for 10s to not break the file work
        setTimeout(() => {
          this.checkIfRevisionChange(newRevisionNumber);
        }, 10000);
      } else {
        // check if sw is good enough
        // ModalActions.requestUIReload();
      }
    }
  }

  /**
   * @method
   * @private
   * @description Changes the page (overlay over browserHistory.push())
   * @param newPage {string}
   */
  static changePage(newPage) {
    browserHistory.push(newPage);
  }

  isConfigLoaded() {
    return this.applicationConfigWasLoaded;
  }

  getCurrentColorScheme() {
    return this.colorScheme;
  }

  getUserMenuState() {
    return this.userMenuState;
  }

  getSearchInputState() {
    return this.searchCollapseState;
  }

  getDrawingMenuState() {
    return this.drawingMenuState;
  }

  getOldResourcesUsage() {
    if (this.useOldResources) {
      return {
        templates: true,
        pTemplates: true,
        fonts: true,
        cFonts: true,
        blocks: true
      };
    }

    return {
      templates: false,
      pTemplates: false,
      fonts: false,
      cFonts: false,
      blocks: false
    };
  }

  /**
   * @description Handles action sent by calling ApplicationActions methods.
   * @private
   * @param action {{actionType: string}}
   */
  handleAction(action) {
    if (action.actionType.indexOf(ApplicationConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case ApplicationConstants.APP_CONFIGURATION_LOADED:
          this.checkIfUpdateIsRequired(action.applicationInfo);
          this.emitEvent(CONFIG_LOADED);
          break;
        case ApplicationConstants.APP_REVISION_LOADED:
          this.checkIfRevisionChange(action.revision);
          this.emitEvent(CONFIG_LOADED);
          break;
        case ApplicationConstants.APP_CHANGE_PAGE:
          ApplicationStore.changePage(action.newPage);
          this.emitEvent(UPDATE);
          break;
        case ApplicationConstants.APP_CHANGE_LANGUAGE:
          if (this.language !== action.lang || this.locale !== action.locale) {
            this.language = action.lang;
            this.locale = action.locale;
            Storage.store("lang", this.language);
            Storage.store("locale", this.locale);
            this.emitEvent(UPDATE);
          }
          break;
        case ApplicationConstants.APP_TOGGLE_TERMS:
          this.emitEvent(TOGGLE_TERMS);
          break;
        case ApplicationConstants.APP_UPDATE:
          this.emitEvent(UPDATE);
          break;
        case ApplicationConstants.APP_COLOR_SCHEME_CHANGED:
          this.colorScheme = action.scheme;
          ColorSchemes.saveNewScheme(action.scheme);
          this.emitEvent(COLOR_SCHEME_CHANGED);
          break;
        case ApplicationConstants.APP_SIDEBAR_SWITCH:
          if (_.isNull(action.state)) {
            this.sidebarState = !this.sidebarState;
          } else {
            this.sidebarState = action.state;
          }

          this.emitEvent(SIDEBAR_SWITCHED);
          break;
        case ApplicationConstants.APP_SEARCH_COLLAPSE_SWITCH:
          if (_.isNull(action.state)) {
            this.searchCollapseState = !this.searchCollapseState;
          } else {
            this.searchCollapseState = action.state;
          }

          this.emitEvent(SEARCH_SWITCHED);
          break;
        case ApplicationConstants.APP_USER_MENU_SWITCH:
          if (_.isNull(action.state) || !action.state) {
            this.userMenuState = !this.userMenuState;
          } else {
            this.userMenuState = action.state;
          }

          this.emitEvent(USER_MENU_SWITCHED);
          break;
        case ApplicationConstants.APP_DRAWING_MENU_SWITCH:
          if (_.isNull(action.state) || !action.state) {
            this.drawingMenuState = !this.drawingMenuState;
          } else {
            this.drawingMenuState = action.state;
          }

          this.emitEvent(DRAWING_MENU_SWITCHED);
          break;
        case ApplicationConstants.APP_SWITCH_OLD_RESOURCES: {
          if (action.isOld) {
            this.useOldResources = true;
          } else {
            this.useOldResources = false;
          }

          this.emitEvent(SWITCH_OLD_RESOURCES);
          break;
        }
        default:
          break;
      }
    }
  }

  /**
   * @private
   * @param eventType {string}
   */
  emitEvent(eventType) {
    this.emit(eventType);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  addChangeListener(eventType, callback) {
    this.on(eventType, callback);
    if (
      eventType === CONFIG_LOADED &&
      this.applicationConfigWasLoaded === true
    ) {
      callback();
    }
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

ApplicationStore.dispatchToken = null;
const applicationStore = new ApplicationStore();
applicationStore.setMaxListeners(0);

export default applicationStore;
