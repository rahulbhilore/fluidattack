import browser from "browser-detect";
import React, { useCallback, useEffect, useState } from "react";
import _ from "underscore";
import DateFnsUtils from "@date-io/date-fns";
import { CssBaseline } from "@material-ui/core";
import {
  ThemeProvider,
  createTheme,
  StylesProvider,
  createGenerateClassName
} from "@material-ui/core/styles";
import { MuiPickersUtilsProvider } from "@material-ui/pickers";
import { AdapterDateFns } from "@mui/x-date-pickers/AdapterDateFns";
import { LocalizationProvider } from "@mui/x-date-pickers/LocalizationProvider"; // date-fns
import { SnackbarProvider } from "notistack";
import { useIntl } from "react-intl";

// have to use date-fns instead of moment js here
// because of internalization bugs:
// https://github.com/mui-org/material-ui-pickers/issues/1297
// https://github.com/mui-org/material-ui-pickers/issues/1205
import englishLocale from "date-fns/locale/en-US";
import japaneseLocale from "date-fns/locale/ja";
import chineseHansLocale from "date-fns/locale/zh-CN";
import chineseHantLocale from "date-fns/locale/zh-TW";
import germanLocale from "date-fns/locale/de";
import koreanLocale from "date-fns/locale/ko";
import polishLocale from "date-fns/locale/pl";
import ApplicationActions from "../actions/ApplicationActions";
import ModalActions from "../actions/ModalActions";
import UserInfoActions from "../actions/UserInfoActions";
import WebsocketActions from "../actions/WebsocketActions";
import * as ModalConstants from "../constants/ModalConstants";
import MainFunctions from "../libraries/MainFunctions";
import ApplicationStore, { CONFIG_LOADED } from "../stores/ApplicationStore";
import ModalStore from "../stores/ModalStore";
import UserInfoStore, {
  INFO_UPDATE,
  LOGOUT,
  TIMEZONE_UPDATE_REQUIRED
} from "../stores/UserInfoStore";
import WebsocketStore, {
  CHANGE_EVENT,
  USER_SOCKET
} from "../stores/WebsocketStore";
import Storage from "../utils/Storage";
import Modal from "./Modal/Modal";

import FilesListActions from "../actions/FilesListActions";
import baseTheme from "../constants/appConstants/Material/MaterialThemes";
import FilesListStore from "../stores/FilesListStore";
import "../utils/PerformanceTest";
import ContextMenu from "./ContextMenu/ContextMenu";
import Header from "./Header/Header";
import SnackbarUtils, {
  SnackbarUtilsConfigurator
} from "./Notifications/Snackbars/SnackController";
import PrivacyPolicyPage from "./Pages/PrivacyPolicyPage/PrivacyPolicyPage";
import { LanguageCode } from "../utils/languages";
import ShortcutContextProvider from "./ShortcutsContextProvider";
import InstallationBanner from "./InstallationBanner/InstallationBanner";
import A3 from "./A3";

const generateClassName = createGenerateClassName({
  productionPrefix: "old_mui"
});

const OLD_THEME = createTheme(baseTheme);
type ObjectCreatedType = {
  type: "objectCreated";
  isFolder: boolean;
  parentId: string;
  itemId: string;
  id: string;
};
type WSEvent =
  | ObjectCreatedType
  | {
      type: string;
      id: string;
    };

const safeZoneRoutes = ["index", "notify"];

type AvailableIntlLocales =
  | Exclude<LanguageCode, "cn" | "zh">
  | "zh-Hant"
  | "zh-Hans";

const locales: Record<AvailableIntlLocales, Locale> = {
  en: englishLocale,
  de: germanLocale,
  ja: japaneseLocale,
  ko: koreanLocale,
  pl: polishLocale,
  "zh-Hans": chineseHansLocale,
  "zh-Hant": chineseHantLocale
};

type Props = {
  children: React.ReactNode;
};
export default function AppEntryComponent({ children }: Props) {
  const { locale } = useIntl();

  const onConfigLoaded = useCallback(() => {
    const stylesheetName = ApplicationStore.getApplicationSetting("styleSheet");
    document.body.classList.add(stylesheetName);
  }, []);

  const checkCookieAndVersion = useCallback(() => {
    const isCommander =
      location.pathname.includes("commander") ||
      location.pathname.includes("/app/") ||
      window.navigator.userAgent.includes("ARES Commander");

    if (isCommander) return;

    // show cookie
    const areCookiesAccepted =
      isCommander || Storage.getItem("CPAccepted") === "true";
    if (!areCookiesAccepted) {
      SnackbarUtils.cookieAccept();
    }

    // show tour
    const revision = ApplicationStore.getApplicationSetting("revision");
    const release = revision.split(".", 2).join(".");

    const SHOULD_SHOW_NEW_VERSION = false;
    if (
      SHOULD_SHOW_NEW_VERSION &&
      Storage.getItem("lastViewedVersion") !== release
    ) {
      SnackbarUtils.newKudoVersion(revision);
    }
  }, [location.pathname, window.navigator.userAgent]);

  const offlineHandler = useCallback(() => {
    SnackbarUtils.offline();
  }, []);

  const triggerTimezoneUpdate = useCallback(() => {
    const { preferences } = UserInfoStore.getUserInfo();
    const { timezoneOffset } = preferences;
    // we should convert JS offset to Java-style (different sign + in ms)
    const newOffset = new Date().getTimezoneOffset() * -1 * 60 * 1000;
    if (timezoneOffset !== newOffset) {
      // We should extend current preferences to make sure we don't mess up preferences
      // XENON-42517
      UserInfoActions.modifyUserInfo(
        { preferences: _.extend(preferences, { timezoneOffset: newOffset }) },
        true,
        true
      );
    }
    UserInfoStore.removeChangeListener(
      TIMEZONE_UPDATE_REQUIRED,
      triggerTimezoneUpdate
    );
  }, []);

  const [websocketId, setWebsocketId] = useState<string | null>(null);

  const clearWebSocket = useCallback(() => {
    UserInfoStore.removeChangeListener(LOGOUT, clearWebSocket);
    if (websocketId && websocketId.length > 0) {
      WebsocketActions.disconnect(websocketId, true);
    }
    setWebsocketId(null);
  }, [websocketId]);

  const onUserWebSocketMessage = useCallback(
    (event: WSEvent) => {
      const isFilesPage = MainFunctions.detectPageType() === "files";
      if (event.type === "connectionClosed" && event.id === websocketId) {
        clearWebSocket();
      } else if (event.id === websocketId) {
        const message =
          WebsocketStore.getConnectionInfo(websocketId).lastMessage;
        if (message) {
          switch (message.messageType) {
            case "logout":
              if (MainFunctions.detectPageType() !== "file") {
                ApplicationActions.changePage(
                  ApplicationStore.getApplicationSetting("UIPrefix"),
                  "AEC_WS_logout"
                );
              }
              break;
            case "storageSwitch":
            case "storageRemove":
            case "storageAdd":
            case "optionsChanged":
              UserInfoActions.getUserInfo();
              UserInfoActions.getUserStorages();
              // no need to reload folder content - it'll be handled by FileLoader
              if (
                ModalStore.isDialogOpen() === true &&
                ModalStore.getCurrentInfo().currentDialog ===
                  ModalConstants.PERMISSIONS
              ) {
                SnackbarUtils.alertError({
                  id: "storageChangedAndDialogWasClosed"
                });
                ModalActions.hide();
              }
              break;
            case "objectCreated":
              if (isFilesPage === true) {
                const {
                  storage,
                  accountId,
                  _id: currentFolderId
                } = FilesListStore.getCurrentFolder();
                const { storageType, storageId } =
                  MainFunctions.parseObjectId(currentFolderId);
                const eventData = event as ObjectCreatedType;
                const type = eventData.isFolder ? "folder" : "file";
                if (currentFolderId === eventData.parentId) {
                  FilesListActions.getObjectInfo(
                    eventData.itemId,
                    type,
                    {}
                  ).then(data => {
                    FilesListActions.addEntity(
                      eventData.isFolder
                        ? FilesListStore.parseFolderData(
                            data,
                            storageType || storage,
                            storageId || accountId,
                            Date.now()
                          )
                        : FilesListStore.parseFileData(
                            data,
                            storageType || storage,
                            storageId || accountId,
                            Date.now()
                          ),
                      true
                    );
                  });
                }
              }
              break;
            case "objectDeleted":
            case "accessRemoved":
              if (isFilesPage === true) {
                FilesListActions.deleteEntity(message.itemId);
              }
              break;
            case "objectRenamed":
              // TODO
              // FilesListStore.getTreeData(currentObjectId)
              // if (isFilesPage === true && FilesListStore.getCurrentFolder()) {
              //   FilesListActions.updateEntityInfo(message.itemId);
              // }
              break;
            case "accessGranted":
              // TODO
              break;
            case "languageSwitch":
              UserInfoActions.getUserInfo();
              break;
            default:
              break;
          }
        }
      }
    },
    [websocketId]
  );

  const setUpWebSocket = useCallback(() => {
    if (websocketId === null) {
      const sessionId = Storage.getItem("sessionId");
      if ((sessionId || "").length > 0) {
        UserInfoStore.addChangeListener(LOGOUT, clearWebSocket);
        UserInfoStore.removeChangeListener(INFO_UPDATE, setUpWebSocket);
        const userWebsocketURL =
          ApplicationStore.getApplicationSetting("userWebsocketURL") || "";
        if (userWebsocketURL.length > 0) {
          const socketId = WebsocketActions.connect(
            userWebsocketURL,
            USER_SOCKET
          );
          setWebsocketId(socketId);
          WebsocketStore.addEventListener(CHANGE_EVENT, onUserWebSocketMessage);
        }
      }
    }
  }, [websocketId]);

  useEffect(() => {
    ApplicationStore.addChangeListener(CONFIG_LOADED, onConfigLoaded);
    // we have to load storages config regardless
    UserInfoActions.getStoragesConfiguration();
    window.addEventListener("storage", e => {
      if (e.key === "isLoggedIn") {
        Storage.setItem("noStoreError", "true");
        // reload, because we need to be sure that cookies are correct
        location.reload();
      }
    });
    if (
      Storage.getItem("sessionId") &&
      !safeZoneRoutes.includes(MainFunctions.detectPageType()) &&
      !MainFunctions.QueryString("token").length &&
      !location.href.includes("external")
    ) {
      UserInfoActions.getUserInfo();
      UserInfoActions.getUserStorages();
    }

    UserInfoStore.addChangeListener(INFO_UPDATE, setUpWebSocket);
    UserInfoStore.addChangeListener(
      TIMEZONE_UPDATE_REQUIRED,
      triggerTimezoneUpdate
    );
    const browserData = browser();
    MainFunctions.updateBodyClasses([browserData.name || "Unknown"]);

    // add listener to show offline snackbar
    window.addEventListener("offline", offlineHandler);

    // check if cookies were accepted
    checkCookieAndVersion();
    return () => {
      ApplicationStore.removeChangeListener(CONFIG_LOADED, onConfigLoaded);
      window.removeEventListener("offline", offlineHandler);
      UserInfoStore.removeChangeListener(INFO_UPDATE, setUpWebSocket);
      UserInfoStore.removeChangeListener(
        TIMEZONE_UPDATE_REQUIRED,
        triggerTimezoneUpdate
      );
    };
  }, []);

  const isApp = location.pathname.includes("/app/");
  return (
    <StylesProvider generateClassName={generateClassName}>
      <ThemeProvider theme={OLD_THEME}>
        <SnackbarProvider disableWindowBlurListener maxSnack={5}>
          <CssBaseline />
          <MuiPickersUtilsProvider
            utils={DateFnsUtils}
            locale={locales[locale as AvailableIntlLocales]}
          >
            <LocalizationProvider dateAdapter={AdapterDateFns}>
              <InstallationBanner />
              <ShortcutContextProvider>
                {!isApp ? <Header /> : null}
                {!isApp ? <ContextMenu /> : null}
                <Modal />
                {children}
                {!isApp ? <PrivacyPolicyPage /> : null}
                {!isApp ? <A3 /> : null}
                <SnackbarUtilsConfigurator />
              </ShortcutContextProvider>
            </LocalizationProvider>
          </MuiPickersUtilsProvider>
          <span id="AK_sw_check_span" />
        </SnackbarProvider>
      </ThemeProvider>
    </StylesProvider>
  );
}
