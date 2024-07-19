import EventEmitter from "events";
import _ from "underscore";
import browser from "browser-detect";
import { DateTime } from "luxon";
import { normalizeLocaleAndLang } from "../utils/languages";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as UserInfoConstants from "../constants/UserInfoConstants";
import UserInfoActions from "../actions/UserInfoActions";
import MainFunctions from "../libraries/MainFunctions";
import ApplicationStore from "./ApplicationStore";
import ApplicationActions from "../actions/ApplicationActions";
import Storage from "../utils/Storage";
import UtmTracker from "../utils/UtmTracker";

import FilesListActions from "../actions/FilesListActions";
import Tracker from "../utils/Tracker";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";

export const LOGIN = "LOGIN";
export const LOGOUT = "LOGOUT";
export const SIGNUP = "SIGNUP";
export const RESET_PASSWORD = "RESET_PASSWORD";
export const INFO_UPDATE = "INFO_UPDATE";
export const TRIAL_EXTEND = "TRIAL_EXTEND";
export const STORAGES_UPDATE = "STORAGES_UPDATE";
export const STORAGES_CONFIG_UPDATE = "STORAGES_CONFIG_UPDATE";
export const STORAGE_SWITCH = "STORAGE_SWITCH";
export const NOTIFICATION_UPDATE = "NOTIFICATION_UPDATE";
export const RECENT_FILES_UPDATE = "RECENT_FILES_UPDATE";
export const RECENT_FILES_SWITCH_UPDATE = "RECENT_FILES_SWITCH_UPDATE";
export const COMPANY_INFO_UPDATE = "COMPANY_INFO_UPDATE";
export const STORAGE_REDIRECT_CHECK_READY = "STORAGE_REDIRECT_CHECK_READY";
export const TIMEZONE_UPDATE_REQUIRED = "TIMEZONE_UPDATE_REQUIRED";
export const INTERCOM_RELAUNCHED = "INTERCOM_RELAUNCHED";

const defaultStorageConfig = {
  capabilities: {
    share: {
      file: false,
      folder: false,
      publicLink: false
    },
    open: {
      drawingMimeTypeFilter: false
    },
    trash: {
      // it's better to have it false by default
      isAvailable: false
    }
  }
};

export const supportedApps = {
  xenon: ["dwg", "dwt", "dxf", "dws", "dwf"],
  fluorine: ["folder"],
  images: ["bmp", "jpg", "png", "jpeg", "gif", "webp"],
  pdf: ["pdf"]
};

export const extToIcons = {
  drawing: supportedApps.xenon,
  image: ["jpg", "jpeg", "svg", "png", "bmp", "gif", "webp"],
  pdf: ["pdf"],
  text: ["doc", "docx", "txt", "odt"]
};

/**
 * @class
 * @extends EventEmitter
 * @classdesc Stores application state (editor url, revision, etc.)
 */
class UserInfoStore extends EventEmitter {
  /**
   * @constructor
   */
  constructor() {
    super();
    this.user = {
      isLoggedIn: false,
      isFullInfo: false,
      isTrialShown: false,
      notificationBarShowed: -1,
      isNotificationToShow: false,
      id: "",
      graebertId: "",
      graebertHash: "",
      enabled: true,
      name: "",
      surname: "",
      email: "",
      username: "",
      intercomAppId: "dzoczj6l",
      googleAccount: false,
      fileFilter: "allFiles",
      licenseType: "",
      expirationDate: null,
      options: {},
      storage: {
        type: "samples",
        name: "",
        id: 0,
        email: "",
        otherInfo: {}
      },
      showRecent: false,
      isRecentFilesSwitchUpdated: false,
      preferences: {},
      company: {
        name: "",
        id: "",
        isAdmin: false
      },
      isAdmin: false,
      compliance: {},
      utms: {}
    };
    this.intercom = {
      isConnected: false,
      data: {}
    };
    this.lastRequest = {
      code: null,
      data: null
    };
    this.storagesInfo = {};
    this.activeStorageConfig = defaultStorageConfig;
    this.storagesData = {};
    this.removedAccounts = [];
    this.logoutRequest = false;
    this.companyInfo = {};
    this.storagesOrder = [
      "GDRIVE",
      "DROPBOX",
      "ONSHAPE",
      "ONEDRIVE",
      "WEBDAV",
      "NEXTCLOUD",
      "BOX",
      "ONEDRIVEBUSINESS",
      "TRIMBLE",
      "HANCOM"
    ];
    this.storagesConfig = {};
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  handleRedirectConditions(redirectURL) {
    const { isTrialShown, licenseType } = this.user;
    if (!isTrialShown && licenseType.toLowerCase() === "trial") {
      let finalRedirect = MainFunctions.QueryString("bredirect");
      if (!finalRedirect) {
        finalRedirect = btoa(MainFunctions.QueryString("redirect"));
      }
      ApplicationActions.changePage(
        `${ApplicationStore.getApplicationSetting("UIPrefix")}trial${
          finalRedirect ? `?bredirect=${finalRedirect}` : ""
        }`,
        "UIS_HRC"
      );
    } else if (redirectURL) {
      ApplicationActions.changePage(redirectURL, "UIS_HRC");
    } else {
      // this will take care of redirect to storages/files
      this.validateUserStorage(true);
    }
  }

  clearInfoOnLogout() {
    this.user = {
      isLoggedIn: false,
      isFullInfo: false,
      isTrialShown: false,
      notificationBarShowed: -1,
      isNotificationToShow: false,
      id: "",
      graebertId: "",
      graebertHash: "",
      enabled: true,
      name: "",
      surname: "",
      email: "",
      username: "",
      googleAccount: false,
      fileFilter: "allFiles",
      licenseType: "",
      expirationDate: null,
      options: {},
      storage: {
        type: "internal",
        name: "",
        id: 0,
        email: "",
        otherInfo: {}
      },
      showRecent: false,
      isRecentFilesSwitchUpdated: false,
      preferences: {},
      company: {
        name: "",
        id: "",
        isAdmin: false
      },
      isAdmin: false,
      compliance: {},
      utms: {},
      hasCommanderLicense: false,
      hasTouchLicense: false
    };
    this.intercom = {
      isConnected: false,
      data: {}
    };
    this.lastRequest = {
      code: null,
      data: null
    };
    this.storagesInfo = {};
    this.activeStorageConfig = defaultStorageConfig;
    this.storagesData = {};
    this.removedAccounts = [];
    this.companyInfo = {};
  }

  formIntercomData() {
    const browserData = browser();
    let intercomBaseData = {
      storage: this.user.storage.type,
      user_id: this.user.id,
      app_id: this.user.intercomAppId,
      session_id: Storage.store("sessionId"),
      LAST_KUDO_BROWSER: `${browserData.name} ${browserData.versionNumber}`
    };
    // by this point - UtmTracker.utmsObject already contains object
    if (this.user.utms && UtmTracker.utmsObject) {
      intercomBaseData = _.extend(intercomBaseData, UtmTracker.utmsObject);
    }
    if (this.user.graebertId) {
      intercomBaseData.user_id = this.user.graebertId || this.user.id;
      intercomBaseData.user_hash = this.user.graebertHash || "";
      return intercomBaseData;
    }
    let name = `${this.user.name || ""} ${this.user.surname || ""}`;
    if (name.length < 3) {
      // eslint-disable-next-line no-console
      console.error("Name for Intercom isn't set");
      name = "";
    }
    intercomBaseData.name = name;
    intercomBaseData.email = this.user.email;
    return intercomBaseData;
  }

  refreshIntercomInfo() {
    const { Intercom } = window;
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled").intercom ===
        true &&
      Intercom &&
      this.user.isLoggedIn &&
      this.user.isFullInfo &&
      (this.intercom.isConnected === false ||
        !_.isEqual(this.intercom.data, this.formIntercomData()))
    ) {
      const intercomData = this.formIntercomData();
      this.intercom.isConnected = true;
      this.intercom.data = intercomData;
      Intercom("shutdown");
      Intercom("boot", {
        app_id: this.user.intercomAppId,
        created_at: new Date().getTime(),
        sitename: location.hostname
      });
      Intercom("update", intercomData);
      this.emitEvent(INTERCOM_RELAUNCHED);
    }
  }

  findStorageEmail(storageType, accountId) {
    if (this.storagesInfo) {
      const accountInfo =
        _.findWhere(this.storagesInfo[storageType], {
          [`${storageType}_id`]: accountId
        }) || {};
      return accountInfo[`${storageType}_username`] || "";
    }
    return "";
  }

  getConnectedStorageEmails(storageType) {
    return this.storagesInfo
      ? this.storagesInfo[storageType].map(
          info => info[`${storageType}_username`]
        )
      : [];
  }

  updateUserInfo(newInfo) {
    const oldOptions = _.clone(this.user.options);
    const oldFullInfoFlag = this.user.isFullInfo;
    const oldUTMS = _.clone(this.user.utms);
    const storageType =
      newInfo.storageType ||
      (newInfo.storage
        ? newInfo.storage.storageType || newInfo.storage.type
        : "internal");
    this.user = _.extend(
      this.user,
      MainFunctions.compactObject({
        isLoggedIn: newInfo.isLoggedIn,
        isFullInfo: newInfo.isFullInfo,
        isTrialShown: MainFunctions.forceBooleanType(newInfo.isTrialShown),
        notificationBarShowed: newInfo.notificationBarShowed || -1,
        id: newInfo._id || newInfo.id,
        graebertId: newInfo.graebertId || "",
        graebertHash: newInfo.graebertIdHash || "",
        enabled: newInfo.enabled,
        name: newInfo.name,
        surname: newInfo.surname,
        email: newInfo.email || "",
        username: newInfo.username,
        intercomAppId: newInfo.intercomAppId || "dzoczj6l",
        googleAccount: !!newInfo.googleAccount,
        fileFilter: newInfo.fileFilter || "allFiles",
        licenseType: newInfo.licenseType || this.user.licenseType || "",
        expirationDate:
          newInfo.licenseExpirationDate ||
          newInfo.expirationDate ||
          this.user.expirationDate ||
          0,
        options: MainFunctions.recursiveObjectFormat(
          newInfo.options || {},
          prop => MainFunctions.forceBooleanType(prop, true)
        ),
        isAdmin: newInfo.isAdmin || false,
        company: {
          name: newInfo.company ? newInfo.company.company_name || "" : "",
          id: newInfo.company ? newInfo.company.id || "" : "",
          isAdmin: newInfo.company
            ? MainFunctions.forceBooleanType(newInfo.company.isAdmin)
            : false
        },
        preferences: newInfo.preferences || {
          preferences_display: {
            graphicswinmodelbackgrndcolor: "Black"
          },
          frequencyOfGettingEmailNotifications: "Hourly"
        },
        compliance: MainFunctions.recursiveObjectFormat(
          newInfo.compliance || {},
          prop => MainFunctions.forceStringType(prop, true)
        ),
        utms: newInfo.utms || {},
        hasCommanderLicense: MainFunctions.forceBooleanType(
          newInfo.hasCommanderLicense
        ),
        hasTouchLicense: MainFunctions.forceBooleanType(newInfo.hasTouchLicense)
      })
    );

    // revit (XENON-29177 && XENON-29176)
    const isRevitSupportedGlobally =
      ApplicationStore.getApplicationSetting("featuresEnabled").revitAll;
    const isRevitSupportedByAdmins =
      ApplicationStore.getApplicationSetting("featuresEnabled").revitAdmins;
    if (
      isRevitSupportedGlobally === true ||
      (this.user.isAdmin === true && isRevitSupportedByAdmins === true)
    ) {
      if (supportedApps.xenon.indexOf("rvt") === -1) {
        supportedApps.xenon.push("rvt");
      }
      if (supportedApps.xenon.indexOf("rfa") === -1) {
        supportedApps.xenon.push("rfa");
      }
      if (extToIcons.drawing.indexOf("rvt") === -1) {
        extToIcons.drawing.push("rvt");
      }
      if (extToIcons.drawing.indexOf("rfa") === -1) {
        extToIcons.drawing.push("rfa");
      }
    }

    if (
      Object.prototype.hasOwnProperty.call(newInfo, "utms") &&
      !_.isEqual(newInfo.utms, oldUTMS) &&
      Object.keys(newInfo.utms).length > 0
    ) {
      UtmTracker.updateInfoFromUserObject(newInfo.utms);
    }

    if (Object.prototype.hasOwnProperty.call(newInfo, "storage")) {
      if (Object.keys(newInfo.storage).length === 0) {
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting("UIPrefix")}storages`,
          "UIS_UUI"
        );
      } else {
        let hasStorageChanged = false;
        const storageEmail =
          newInfo.storage.email ||
          this.findStorageEmail(
            storageType.toLowerCase(),
            newInfo.storage.id || 0
          ) ||
          newInfo.email;

        let { otherInfo } = this.user.storage;

        // if number of "additional" parameters differs from saved - update it
        // otherwise - leave as is (this is for TC)
        if (
          !_.isEqual(
            Object.keys(this.user.storage.otherInfo),
            Object.keys(newInfo.storage.additional || {})
          )
        ) {
          otherInfo = newInfo.storage.additional || {};
          hasStorageChanged = true;
        } else if (
          storageType.toLowerCase() !== this.user.storage.type.toLowerCase() ||
          newInfo.storage.id !== this.user.storage.id
        ) {
          hasStorageChanged = true;
        }

        if (newInfo.storage?.usage)
          _.extend(otherInfo, {
            usage: newInfo.storage.usage
          });

        this.user.storage = {
          type: storageType.toLowerCase(),
          name: storageEmail,
          id: newInfo.storage.id || 0,
          email: storageEmail,
          otherInfo
        };
        this.activeStorageConfig =
          this.storagesConfig[this.user.storage.type.toUpperCase()] ||
          defaultStorageConfig;
        if (hasStorageChanged === true) {
          this.emitEvent(STORAGE_SWITCH);
        }
        this.validateUserStorage();
      }
    }

    this.updateRecentFlag(MainFunctions.forceBooleanType(newInfo.showRecent));
    this.updateNotificationStatus();

    if (
      newInfo.isLoggedIn === true &&
      Object.prototype.hasOwnProperty.call(newInfo, "locale") &&
      normalizeLocaleAndLang(newInfo.locale, true).locale !==
        Storage.store("locale")
    ) {
      const { locale, language } = normalizeLocaleAndLang(newInfo.locale);
      ApplicationActions.changeLanguage(language, locale);
    } else {
      // if storages were loaded before userInfo - filter them
      if (
        newInfo.isLoggedIn === true &&
        Object.keys(this.storagesData).length > 0 &&
        this.isDummyStoragesInfo === false &&
        (_.isEqual(newInfo.options, oldOptions) === false ||
          oldFullInfoFlag !== newInfo.isFullInfo)
      ) {
        this.filterStoragesByOptions();
        this.emitEvent(STORAGES_UPDATE);
      } else if (this.isDummyStoragesInfo !== false) {
        this.isDummyStoragesInfo = true;
        this.storagesInfo = {
          [this.user.storage.type]: [
            {
              [`${this.user.storage.type}_id`]: this.user.storage.id,
              [`${this.user.storage.type}_username`]: this.user.storage.email
            }
          ]
        };
      }
      if (ApplicationStore.getApplicationSetting("debug") !== true) {
        if (this.user.id && this.user.isLoggedIn) {
          Tracker.setGAProperty("userId", this.user.id);
          if (window.trackJs && window.trackJs.configure) {
            window.trackJs.configure({ userId: this.user.id });
          }
        }
      }
      this.refreshIntercomInfo();
    }
    if (this.user.isLoggedIn === true) {
      Storage.store("isLoggedIn", "true");
    }
  }

  updateRecentFlag(showRecent) {
    const oldRecentFlag = _.clone(this.user.showRecent);
    if (oldRecentFlag !== showRecent) {
      this.user.showRecent = showRecent;
      this.emitEvent(RECENT_FILES_UPDATE);
    }
  }

  updateNotificationStatus() {
    const oldNotificationStatus = _.clone(this.user.isNotificationToShow);
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled")
        .independentLogin === true
    ) {
      this.user.isNotificationToShow = false;
    } else if (this.isFreeAccount()) {
      this.user.isNotificationToShow = true;
    } else if (
      this.user.licenseType.toLowerCase().trim() === "trial" &&
      Storage.store("trialBarHidden") !== "true"
    ) {
      this.user.isNotificationToShow = true;
    } else {
      this.user.isNotificationToShow = false;
    }
    if (oldNotificationStatus !== this.user.isNotificationToShow) {
      this.emitEvent(NOTIFICATION_UPDATE);
    }
  }

  isTrialAccount() {
    const licenseType = this.user.licenseType.toLowerCase().trim();
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled")
        .independentLogin === true
    ) {
      return false;
    }
    return licenseType === "trial";
  }

  isFreeAccount() {
    const licenseType = this.user.licenseType.toLowerCase().trim();
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled")
        .independentLogin === true ||
      this.isPerpetualAccount()
    ) {
      return false;
    }
    if (licenseType === "free") {
      return true;
    }
    return this.user.expirationDate <= Date.now();
  }

  isPerpetualAccount() {
    return this.user.licenseType.toLowerCase().trim() === "perpetual";
  }

  filterStoragesByOptions() {
    if (this.user.isLoggedIn === true) {
      this.storagesInfo = _.mapObject(this.storagesInfo, (value, key) => {
        if (
          this.user.options &&
          this.user.options.storages &&
          this.user.options.storages[key] === false
        ) {
          return [];
        }
        const searchParam = { name: key };
        if (this.user.isFullInfo && this.user.isAdmin === false) {
          searchParam.adminOnly = false;
        }
        if (
          _.findWhere(
            ApplicationStore.getApplicationSetting("storagesSettings"),
            searchParam
          )
        ) {
          return value;
        }
        return [];
      });
      _.each(this.removedAccounts, accountData => {
        this.removeStorage(accountData.type, accountData.id);
      });
      this.emitEvent(STORAGES_UPDATE);
    }
  }

  getUserInfo(prop) {
    if (!prop) return this.user;
    if (!Object.prototype.hasOwnProperty.call(this.user, prop)) return "";
    return this.user[prop];
  }

  areEmailNotificationsAvailable() {
    const isKudo = ApplicationStore.getApplicationSetting("product")
      .toLowerCase()
      .includes("kudo");
    if (!isKudo) {
      return false;
    }
    const { isLoggedIn, isFullInfo, options } = this.user;
    if (!isLoggedIn || !isFullInfo) return false;
    if (
      Object.keys(options).length > 0 &&
      Object.prototype.hasOwnProperty.call(options, "email_notifications") ===
        true &&
      options.email_notifications === true
    ) {
      return true;
    }
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled")
        .independentLogin === true
    ) {
      return true;
    }
    return false;
  }

  areCommentingSettingsAvailable() {
    const { isLoggedIn, isFullInfo } = this.user;
    if (!isLoggedIn || !isFullInfo) return false;
    const isKudo = ApplicationStore.getApplicationSetting("product")
      .toLowerCase()
      .includes("kudo");
    if (!isKudo) {
      return false;
    }
    return true;
  }

  getSpecificStorageInfo(storage, accountId) {
    return _.find(
      this.storagesInfo[storage],
      accountInfo => accountInfo[`${storage}_id`] === accountId
    );
  }

  getStoragesInfo() {
    return this.storagesInfo;
  }

  getStoragesData() {
    return this.storagesData;
  }

  getLastRequest() {
    return this.lastRequest;
  }

  saveLastRequest(requestData, responseCode) {
    this.lastRequest = {
      code: responseCode,
      data: requestData
    };
  }

  saveLogoutRequest(logoutReq) {
    this.logoutRequest = logoutReq;
  }

  isLogoutPending() {
    return this.logoutRequest;
  }

  /**
   * @description validate that the storage is actually available for the user
   * @link https://graebert.atlassian.net/browse/XENON-29723
   * @param [forceRedirect] {boolean}
   */
  validateUserStorage(forceRedirect) {
    const { type, id } = this.user.storage;
    const storagesList = this.storagesInfo;
    const isStoragesLoaded = Object.keys(this.storagesData).length > 0;
    const isExternal = location.href.indexOf("external") !== -1;
    const currentPage = MainFunctions.detectPageType();
    if (
      // No need to check if user is already on storages page
      currentPage !== "storages" &&
      // Ignore redirect for VO links
      (MainFunctions.QueryString("token") || "").length === 0 &&
      // have to be sure that user's info is loaded, so the active storage is ok
      this.user.isFullInfo === true &&
      // check for storages
      isStoragesLoaded === true &&
      // No need to check if link is external
      !isExternal &&
      // for people with trial page to be shown - ignore this check fully
      // https://graebert.atlassian.net/browse/XENON-29948
      (this.user.isTrialShown === true ||
        this.user.licenseType.toLowerCase() !== "trial") &&
      // check if active storage is ok or not
      (Object.prototype.hasOwnProperty.call(storagesList, type) === false ||
        storagesList[type].length === 0 ||
        _.pluck(storagesList[type], `${type}_id`).indexOf(id) === -1)
    ) {
      const firstAvailableStorage = _.findKey(
        this.storagesInfo,
        val => val.length > 0
      );
      if (!firstAvailableStorage) {
        SnackbarUtils.alertInfo({ id: "connectToOneOfTheStorages" });
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting("UIPrefix")}storages`,
          "UIS_VUS"
        );
        return;
      }
      const accountId =
        this.storagesInfo[firstAvailableStorage][0][
          `${firstAvailableStorage}_id`
        ];
      UserInfoActions.switchToStorage(firstAvailableStorage, accountId);
    }
    if (forceRedirect === true) {
      ApplicationActions.changePage(
        `${ApplicationStore.getApplicationSetting("UIPrefix")}files`,
        "UIS_VUS"
      );
    }
  }

  getStoragesConfig() {
    return this.storagesConfig;
  }

  getStorageConfig(storageName) {
    if (Object.keys(this.storagesConfig || {}).length === 0) {
      return defaultStorageConfig;
    }
    if (
      Object.prototype.hasOwnProperty.call(this.storagesConfig, storageName)
    ) {
      return this.storagesConfig[storageName];
    }
    return this.activeStorageConfig;
  }

  getStoragesOrder() {
    return this.storagesOrder;
  }

  getStoragesOrderingFunction() {
    const order = this.storagesOrder;
    return ({ name: aName }, { name: bName }) => {
      const aIndex = order.indexOf(aName.toUpperCase());
      const bIndex = order.indexOf(bName.toUpperCase());
      if (aIndex === -1 && bIndex === -1) {
        // compare name strings if doesn't work
        return aName - bName;
      }
      if (aIndex === -1) return 1;
      if (bIndex === -1) return -1;
      return aIndex - bIndex;
    };
  }

  updateStoragesConfig(config) {
    if (Object.prototype.hasOwnProperty.call(config, "order")) {
      this.storagesOrder = config.order;
    }
    if (Object.prototype.hasOwnProperty.call(config, "info")) {
      this.storagesConfig = config.info;
      if (this.user.isFullInfo) {
        this.activeStorageConfig =
          this.storagesConfig[this.user.storage.type.toUpperCase()] ||
          defaultStorageConfig;
      }
    }
  }

  saveUserStorages(storagesData) {
    this.storagesData = storagesData;
    this.storagesInfo = _.chain(storagesData)
      .pick(_.isArray)
      .pick((value, key) => {
        if (
          this.user.options &&
          this.user.options.storages &&
          this.user.options.storages[key] === false
        ) {
          return false;
        }
        const searchParam = { name: key };
        if (this.user.isFullInfo && this.user.isAdmin === false) {
          searchParam.adminOnly = false;
        }
        return !!_.findWhere(
          ApplicationStore.getApplicationSetting("storagesSettings"),
          searchParam
        );
      })
      .value();

    _.each(this.removedAccounts, accountData => {
      this.removeStorage(accountData.type, accountData.id);
    });
    if (!this.user.storage.email || "") {
      this.user.storage.email =
        (_.find(
          storagesData[this.user.storage.type],
          connectedAccount =>
            connectedAccount[`${this.user.storage.type}_id`] ===
            this.user.storage.id
        ) || {})[`${this.user.storage.type}_username`] ||
        this.user.storage.email;
    }
    this.isDummyStoragesInfo = false;
    this.user.storage.name = this.user.storage.email;
    this.activeStorageConfig =
      this.storagesConfig[this.user.storage.type.toUpperCase()] ||
      defaultStorageConfig;
    this.emit(STORAGE_REDIRECT_CHECK_READY);
    this.validateUserStorage();
  }

  checkIfStorageIsCurrent(storageType, accountId, additional) {
    // if additional isn't empty - just trigger change
    return (
      this.user.storage.type.toLowerCase() !== storageType.toLowerCase() ||
      this.user.storage.id === accountId ||
      Object.keys(_.omit(additional || {}, "username")).length === 0
    );
  }

  removeStorage(storageType, accountId) {
    // ignore webdav because it doesn't have unique id and connection mechanism
    // @link https://graebert.atlassian.net/browse/XENON-26913
    if (storageType !== "webdav") {
      this.removedAccounts.push({ type: storageType, id: accountId });
    }
    this.storagesInfo[storageType] = _.filter(
      this.storagesInfo[storageType],
      accountData => accountData[`${storageType}_id`] !== accountId
    );
  }

  updateCompanyInfo(companyInfo) {
    this.companyInfo = {
      id: companyInfo.id || "",
      name: companyInfo.name || "",
      options: MainFunctions.recursiveObjectFormat(
        companyInfo.options || {},
        prop => MainFunctions.forceBooleanType(prop, true)
      )
    };
  }

  getCompanyInfo() {
    return this.companyInfo;
  }

  handleLogout(serverResponse) {
    const { independentLogin } =
      ApplicationStore.getApplicationSetting("featuresEnabled");
    const { pathname, href } = location;
    const token = MainFunctions.QueryString("token") || "";
    const isPublicLinkOpen =
      (token || href.includes("external")) &&
      pathname.includes("file") &&
      !pathname.includes("files");
    if (!isPublicLinkOpen) {
      if (
        Object.keys(serverResponse).length > 0 &&
        Object.prototype.hasOwnProperty.call(serverResponse.data, "nameId") &&
        Object.prototype.hasOwnProperty.call(
          serverResponse.data,
          "sessionIndex"
        )
      ) {
        location.href = `${ApplicationStore.getApplicationSetting(
          "oauthURL"
        )}?type=solidworks&mode=logout&nameId=${
          serverResponse.data.nameId
        }&sessionIndex=${
          serverResponse.data.sessionIndex
        }&url=${encodeURIComponent(
          location.origin + ApplicationStore.getApplicationSetting("UIPrefix")
        )}`;
      } else if (!independentLogin) {
        const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
        const domain = ApplicationStore.getApplicationSetting("domain");
        const UIUrl = `${location.origin}${UIPrefix}notify?mode=account&type=sso`;
        const signUpURL = `${location.origin}${UIPrefix}`;
        location.href = `${ApplicationStore.getApplicationSetting(
          "ssoURL"
        )}/api/logout?encoding=b64&app_name=${encodeURIComponent(
          "ARES Kudo"
        )}&email=${this.getUserInfo("email")}&redirect_url=${btoa(
          UIUrl
        )}&sign_up_redirect=${btoa(signUpURL)}${
          domain.length > 0 ? `&domain=${domain}` : ""
        }`;
      } else {
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting("UIPrefix")}`,
          "UIS_Logout"
        );
      }
    }
    Storage.store("isLoggedIn", "false");
    this.saveLogoutRequest(false);
    const { Intercom } = window;
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled").intercom ===
        true &&
      Intercom &&
      this.intercom.isConnected === true
    ) {
      Intercom("shutdown");
      Intercom("boot", {
        app_id: "dzoczj6l",
        created_at: new Date().getTime(),
        sitename: location.hostname
      });
    }
    this.clearInfoOnLogout();
  }

  /**
   * @description Handles action sent by calling ApplicationActions methods.
   * @private
   * @param action {{actionType: string}}
   */
  handleAction(action) {
    if (action.actionType.indexOf(UserInfoConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case UserInfoConstants.USER_LOGIN_SUCCESS: {
          this.saveLastRequest(action.data, action.code);
          let newUserInfo = { isLoggedIn: true, isFullInfo: false };
          if (
            action.data.licenseType &&
            action.data.licenseType === "TRIAL" &&
            action.data.expirationDate
          ) {
            newUserInfo = _.extend(newUserInfo, {
              licenseType: "TRIAL",
              expirationDate: action.data.expirationDate
            });
          }
          this.updateUserInfo(newUserInfo);
          this.emitEvent(LOGIN);
          break;
        }
        case UserInfoConstants.USER_EXTEND_TRIAL_SUCCESS:
          this.saveLastRequest(action.data, action.code);
          this.emitEvent(TRIAL_EXTEND);
          break;
        case UserInfoConstants.USER_LOGIN_FAIL:
          Storage.store("isLoggedIn", "false");
          this.saveLastRequest(action.data, action.code);
          this.updateUserInfo({ isLoggedIn: false, isFullInfo: false });
          this.emitEvent(LOGIN);
          break;
        case UserInfoConstants.USER_INFO_UPDATE_SUCCESS:
          this.updateUserInfo(
            _.extend(action.userInfo.results[0], {
              isLoggedIn: true,
              isFullInfo: true
            })
          );
          this.emitEvent(TIMEZONE_UPDATE_REQUIRED);
          this.emitEvent(INFO_UPDATE);
          break;
        case UserInfoConstants.USER_STORAGES_UPDATE_SUCCESS:
          this.saveUserStorages(action.storagesData);
          this.emitEvent(STORAGES_UPDATE);
          break;
        case UserInfoConstants.USER_SWITCH_STORAGE_CHECK:
          if (
            this.checkIfStorageIsCurrent(
              action.storageType,
              action.accountId,
              action.additional
            )
          ) {
            UserInfoActions.changeStorage(
              action.storageType,
              action.accountId,
              action.additional,
              action.callback
            );
            if (
              action.storageType === this.user.storage.type &&
              action.accountId === this.user.storage.id &&
              !_.isEqual(action.additional, this.user.storage.otherInfo)
            ) {
              this.emitEvent(STORAGE_SWITCH);
            }
          } else if (action.callback) {
            action.callback();
          }
          break;
        case UserInfoConstants.USER_SWITCH_STORAGE_SUCCESS:
          this.user.storage.type = action.storageType;
          this.user.storage.id = action.accountId;
          this.user.storage.email = this.findStorageEmail(
            action.storageType,
            action.accountId
          );
          if (action.additional)
            this.user.storage.otherInfo = action.additional;

          this.activeStorageConfig =
            this.storagesConfig[this.user.storage.type.toUpperCase()] ||
            defaultStorageConfig;
          this.refreshIntercomInfo();
          this.emitEvent(STORAGE_SWITCH);
          break;
        case UserInfoConstants.USER_UPDATE_INFO_SUCCESS:
          if (action.isUpdateUnnecessary !== true) {
            UserInfoActions.getUserInfo();
          }
          break;
        case UserInfoConstants.USER_UPDATE_INFO:
          this.updateUserInfo(_.extend(_.clone(this.user), action.newInfo));
          this.emitEvent(INFO_UPDATE);
          break;
        case UserInfoConstants.USER_REMOVE_STORAGE:
          this.removeStorage(action.type, action.id);
          this.emitEvent(STORAGES_UPDATE);
          break;
        case UserInfoConstants.USER_SIGNUP_SUCCESS:
        case UserInfoConstants.USER_SIGNUP_FAIL:
          this.emitEvent(SIGNUP);
          break;
        case UserInfoConstants.USER_RESET_PASSWORD_SUCCESS:
        case UserInfoConstants.USER_RESET_PASSWORD_FAIL:
          this.updateUserInfo(
            _.extend(this.user, {
              isLoggedIn: !!action.sessionId,
              isFullInfo: false
            })
          );
          if (action.sessionId) {
            Storage.unsafeStoreSessionId(action.sessionId);
          }
          this.emitEvent(RESET_PASSWORD);
          break;
        case UserInfoConstants.USER_LOGOUT_SUCCESS:
          this.handleLogout(action.answer);

          this.emitEvent(LOGOUT);
          break;
        case UserInfoConstants.USER_LOGOUT_INITIATED:
          this.saveLogoutRequest(action.logoutRequest);
          FilesListActions.reloadDrawing(true);
          break;
        case UserInfoConstants.USER_COMPANY_INFO_GET_SUCCESS:
          this.updateCompanyInfo(action.companyInfo);
          this.emitEvent(COMPANY_INFO_UPDATE);
          break;
        case UserInfoConstants.USER_STORAGES_CONFIG_SUCCESS:
          this.updateStoragesConfig(action.config);
          this.emitEvent(STORAGES_CONFIG_UPDATE);
          break;
        case UserInfoConstants.USER_STORAGE_DISABLED:
          this.disableStorage(action.storageType);
          this.emitEvent(STORAGES_UPDATE);
          break;
        case UserInfoConstants.SAMPLE_USAGE_UPDATED:
          this.updateSampleUsage(
            action.storageType,
            action.externalId,
            action.usage,
            action.quota
          );
          this.emitEvent(STORAGES_UPDATE);
          break;
        case UserInfoConstants.USER_RECENT_FILES_SWITCH_UPDATE:
          this.user.isRecentFilesSwitchUpdated = action.state;
          this.emitEvent(RECENT_FILES_SWITCH_UPDATE);
          break;
        default:
          break;
      }
    }
  }

  disableStorage(storageType) {
    const withoutDisabledStorage = {};
    Object.keys(this.storagesData).forEach(key => {
      if (!key.startsWith(storageType)) {
        withoutDisabledStorage[key] = this.storagesData[key];
      }
    });
    this.saveUserStorages(withoutDisabledStorage);
  }

  updateSampleUsage(storageType, externalId, usage, quota) {
    const storages = this.storagesData;
    const lowerStorageType = storageType.toLowerCase();
    const storageAccounts = storages[lowerStorageType];
    if (storageAccounts !== undefined && storageAccounts.length > 0) {
      const index = storageAccounts.findIndex(
        object => object[`${lowerStorageType}_id`] === externalId
      );
      if (index !== -1) {
        storageAccounts[index].quota = quota;
        storageAccounts[index].usage = usage;
        storages[lowerStorageType] = storageAccounts;
        this.saveUserStorages(storages);
      }
    }
  }

  isFeatureAllowedByStorage(storage, featureBlock, featureName) {
    let storageName = storage;
    if (!storageName) {
      storageName = this.user.storage ? this.user.storage.type : undefined;
    }
    if (!storageName) return false;
    storageName = storageName.toUpperCase().trim();
    if (
      !Object.prototype.hasOwnProperty.call(this.storagesConfig, storageName)
    ) {
      return false;
    }
    try {
      return (
        this.storagesConfig[storageName].capabilities[featureBlock][
          featureName
        ] === undefined ||
        !!this.storagesConfig[storageName].capabilities[featureBlock][
          featureName
        ]
      );
    } catch (ex) {
      return false;
    }
  }

  findApp(ext, mimeType) {
    const validatedExtension = (ext || "").toLowerCase();
    if (
      validatedExtension !== "folder" &&
      typeof this.activeStorageConfig.capabilities.open
        .drawingMimeTypeFilter === "string"
    ) {
      if (
        mimeType ===
        this.activeStorageConfig.capabilities.open.drawingMimeTypeFilter
      ) {
        return "xenon";
      }
    }
    return _.findKey(
      supportedApps,
      extensionsArray => extensionsArray.indexOf(ext) > -1
    );
  }

  /**
   * @param ext {String}
   * @param mimeType {String}
   * @returns {boolean} true if ext is supported, false otherwise
   */
  // eslint-disable-next-line class-methods-use-this
  extensionSupported(ext) {
    const validatedExtension = (ext || "").toLowerCase();
    // if (
    //   this.user.storage &&
    //   this.user.storage.type &&
    //   this.activeStorageConfig &&
    //   typeof this.activeStorageConfig.capabilities.open
    //     .drawingMimeTypeFilter === "string" &&
    //   validatedExtension !== "folder"
    // ) {
    //   return (
    //     mimeType ===
    //     this.activeStorageConfig.capabilities.open.drawingMimeTypeFilter
    //   );
    // }
    return !!_.find(
      supportedApps,
      extensionsArray => extensionsArray.indexOf(validatedExtension) > -1
    );
  }

  getIconClassName(ext, objType, fileName, mimeType) {
    if (
      mimeType &&
      this.user.storage &&
      this.user.storage.type &&
      this.activeStorageConfig &&
      typeof this.activeStorageConfig.capabilities.open
        .drawingMimeTypeFilter === "string" &&
      objType !== "folder"
    ) {
      return mimeType ===
        this.activeStorageConfig.capabilities.open.drawingMimeTypeFilter
        ? "drawing"
        : "file";
    }
    let validatedExtension = ext || "";
    if (!validatedExtension && fileName && fileName.length) {
      validatedExtension = MainFunctions.getExtensionFromName(fileName) || "";
    }
    validatedExtension = ext.toLowerCase();
    if (ext) {
      return _.findKey(extToIcons, value => value.indexOf(ext) > -1) || objType;
    }
    return objType;
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
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }

  hasTrialEnded() {
    if (!Storage.store("sessionId")) return true;
    return (this.getUserInfo("expirationDate") || 0) <= new Date().getTime();
  }

  getDaysLeftAmount() {
    if (this.hasTrialEnded()) return 0;

    const endTrialTime = DateTime.fromMillis(
      this.getUserInfo("expirationDate") || DateTime.now()
    )
      .toUTC()
      .toMillis();
    const currentTime = DateTime.now().toUTC().toMillis();
    return Math.floor(
      +((endTrialTime - currentTime) / (1000 * 60 * 60 * 24)).toFixed(2)
    );
  }
}

UserInfoStore.dispatchToken = null;
const userInfoStore = new UserInfoStore();
userInfoStore.setMaxListeners(0);

export default userInfoStore;
