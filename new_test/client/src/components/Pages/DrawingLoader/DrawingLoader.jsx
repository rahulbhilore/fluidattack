import React, { Component } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import { injectIntl, FormattedMessage } from "react-intl";
import Grid from "@material-ui/core/Grid";
import * as CompatibilityCheck from "../../../libraries/CompabilityCheck";
import XenonConnectionStore, {
  SWITCH_EDITOR_MODE,
  STOP_PROGRESSOR,
  DRAWING_MODIFIED
} from "../../../stores/XenonConnectionStore";
import WebsocketStore, {
  CHANGE_EVENT,
  FILE_SOCKET
} from "../../../stores/WebsocketStore";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import WebsocketActions from "../../../actions/WebsocketActions";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGES_UPDATE
} from "../../../stores/UserInfoStore";
import UserInfoActions from "../../../actions/UserInfoActions";
import ApplicationActions from "../../../actions/ApplicationActions";
import ApplicationStore from "../../../stores/ApplicationStore";
import MainFunctions from "../../../libraries/MainFunctions";
import Storage from "../../../utils/Storage";
import ModalActions from "../../../actions/ModalActions";
import ModalStore, { CHANGE } from "../../../stores/ModalStore";
import Tracker, { AK_GA } from "../../../utils/Tracker";
import Requests from "../../../utils/Requests";
import Logger from "../../../utils/Logger";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import FilesListStore, {
  CURRENT_FILE_INFO_UPDATED,
  DRAWING_RELOAD
} from "../../../stores/FilesListStore";
import FilesListActions from "../../../actions/FilesListActions";
import AccessToken from "./AccessToken";
import OpenTimeTracker from "./OpenTimeTracker";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Comments from "./Comments/Comments";
import ToolbarSpacer from "../../ToolbarSpacer";
import Loader from "../../Loader";
import ErrorMessage from "./ErrorMessage";
import FetchAbortsControl, { INFO } from "../../../utils/FetchAbortsControl";
import AppDispatcher from "../../../dispatcher/AppDispatcher";
import * as FilesListConstants from "../../../constants/FilesListConstants";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import conflictingReasons from "../../../constants/appConstants/ConflictingFileReasons";
import SessionError from "./SessionError";

let retryCount = 0;
let globalRetryCount = 0;
const openTimeTracker = new OpenTimeTracker();
let frameOpenTime = 0;
let workStartTime = 0;
const MAX_RETRY = 3;

class DrawingLoader extends Component {
  static handleIframeClick() {
    if (
      document.getElementById("nav-dropdown") &&
      document.getElementById("nav-dropdown").getAttribute("aria-expanded") ===
        "true"
    ) {
      document.getElementById("nav-dropdown").click();
    }
  }

  static onDrawingModified() {
    FilesListStore.setCurrentFileIsModified(true);
  }

  static sendSaveEvent() {
    if (document.hidden) {
      FilesListActions.saveFileInXenon();
    }
  }

  static doLogout() {
    ApplicationStore.sessionExpiredHandler();
  }

  static checkIfForceEdit = () =>
    UserInfoStore.getUserInfo("isAdmin") &&
    Storage.getItem("alwaysEdit") === "true";

  static propTypes = {
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired,
    params: PropTypes.shape({ fileId: PropTypes.string.isRequired }).isRequired,
    location: PropTypes.shape({
      query: PropTypes.shape({ versionId: PropTypes.string })
    }).isRequired
  };

  constructor(props) {
    super(props);
    this.state = this.getInitialState();
  }

  getInitialState() {
    openTimeTracker.start();

    const { params } = this.props;
    const sheetId = MainFunctions.QueryString("sheetId");
    const token = MainFunctions.QueryString("token") || "";
    const isTokenAccess = token.length > 0;
    const isExternal = params.fileId === "external";
    const isUserInfoLoaded =
      UserInfoStore.getUserInfo("isLoggedIn") &&
      UserInfoStore.getUserInfo("isFullInfo");
    const isFreeAccount = UserInfoStore.isFreeAccount();
    const isVersion = MainFunctions.QueryString("versionId").length > 0;
    const isBoxIframe =
      MainFunctions.isInIframe() &&
      document.referrer &&
      document.referrer.includes("box");
    const isViewOnly =
      isBoxIframe ||
      (isUserInfoLoaded && isFreeAccount) ||
      isVersion ||
      isTokenAccess ||
      (MainFunctions.QueryString("access") === "view" && isExternal);
    const { valid: isXenonAllowed, reason: compatibilityIssues } =
      CompatibilityCheck.shouldXenonRun();
    // if xenon cannot run - load is finished
    const isLoadFinished = isXenonAllowed === false;
    return {
      // we have to put it into state because we change it for "external" files
      fileId: params.fileId,
      isTokenAccess,
      isViewOnly,
      isExternal,
      isUserInfoLoaded,
      isEditorToShow: false,
      isXenonAllowed,
      isFileInfoLoaded: false,
      isGLTestPassed: false,
      isLoadFinished,
      errorMessage: "",
      sheetId,
      token,
      compatibilityIssues,
      unloadAndLogout: false,
      isIntercomEventSent: false,
      socketId: "",
      isPasswordRequired: false,
      isLinkExpired: false,
      renderElement: null,
      hasDirectFileAccess: false,
      versionId: MainFunctions.QueryString("versionId"),
      actionButtons: null,
      conflictingReason: null,
      isFileDeleted: false
    };
  }

  componentDidMount() {
    this.addInternalListeners();
    this.getRenderElement();
    document.title = `${window.ARESKudoConfigObject.defaultTitle} | ${
      FilesListStore.getCurrentFile().name
    }`;
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { versionId } = this.state;
    if (MainFunctions.QueryString("versionId") !== versionId) return true;
    const { fileId: newFileId } = nextProps.params;
    const { params } = this.props;
    const { fileId: oldFileId } = params;
    if (newFileId !== oldFileId) return true;

    const oldState = JSON.stringify(
      _.omit(this.state, "isIntercomEventSent", "socketId")
    );
    const newState = JSON.stringify(
      _.omit(nextState, "isIntercomEventSent", "socketId")
    );
    return oldState !== newState;
  }

  componentDidUpdate(prevProps) {
    const { fileId: oldFileId } = prevProps.params;
    const { params } = this.props;
    const { fileId: newFileId } = params;
    const { versionId } = this.state;
    if (
      newFileId !== oldFileId ||
      MainFunctions.QueryString("versionId") !== versionId
    ) {
      this.loadNewDrawing();
    }
  }

  componentWillUnmount() {
    const { fileId } = this.state;
    const eventData = {
      fileId,
      fileName: FilesListStore.getCurrentFile().name,
      openedFor: Date.now() - workStartTime
    };
    Tracker.sendIntercomEvent("file-closed", eventData);
    this.removeInternalListeners();
    this.disconnectFileListeners();
    // just in case if we still have sticky alert shown
    SnackbarUtils.closeStickyAlert();
  }

  /**
   * Loads new drawing (basically removes all old stuff and reinitialize it)
   */
  loadNewDrawing = () => {
    this.removeInternalListeners();
    this.disconnectFileListeners();
    this.setState(this.getInitialState(), () => {
      this.addInternalListeners();
      this.getRenderElement();
    });
  };

  // connects listeners - WS, Xenon's postMessages etc.
  connectFileListeners = () => {
    // save token in cookies for WS connection
    const { isTokenAccess, token } = this.state;
    if (isTokenAccess) {
      Storage.setItem("token", token, true);
    }

    // Connect file's WS
    const wsFileId = FilesListStore.getCurrentFile().wsId;
    // fallback if websocket url isn't defined in config
    const apiURLWithoutProtocol = (
      window.ARESKudoConfigObject.api || window.ARESKudoConfigObject.apiURL
    )
      .replace(/^https?:\/\//i, "")
      .replace(/\/api/i, "");
    let fileWebsocketURL = "";

    if (window.ARESKudoConfigObject.fileWebSocketURL.length > 0) {
      fileWebsocketURL =
        window.ARESKudoConfigObject.fileWebSocketURL + wsFileId;
    } else {
      fileWebsocketURL = `wss://${apiURLWithoutProtocol}/wsfile/${wsFileId}`;
    }
    WebsocketActions.connect(fileWebsocketURL, FILE_SOCKET);

    // if Xenon iframe is actually loaded - we care about postmessages and websocket
    XenonConnectionStore.addChangeListener(this.onXenonMessageLog);
    XenonConnectionActions.connect();

    // connect to API file websocket
    WebsocketStore.addEventListener(CHANGE_EVENT, this.onWebsocketMessageLog);
  };

  // removes connected listeners
  // @see connectFileListeners
  disconnectFileListeners = () => {
    XenonConnectionActions.disconnect();
    XenonConnectionStore.removeChangeListener(this.onXenonMessageLog);

    Storage.deleteValue("token", true);
    WebsocketStore.removeEventListener(
      CHANGE_EVENT,
      this.onWebsocketMessageLog
    );

    const { socketId } = this.state;
    WebsocketActions.disconnect(socketId, true);
  };

  // adds listeners for progressor, user's info etc.
  addInternalListeners = () => {
    // ignore progressor for now as it's not implemented
    XenonConnectionStore.addEventListener(STOP_PROGRESSOR, this.trackStop);

    const { fileId } = this.state;

    window.getDevLink = () =>
      `editor?server=${encodeURIComponent(
        "https://fluorine-master-prod-latest-ue1.dev.graebert.com"
      )}&debug=true&file=${encodeURIComponent(
        fileId
      )}&sessionId=admin-session-id&locale=en_gb`;

    window._xeUpgrade = () => {
      XenonConnectionActions.postMessage({
        messageName: "stateChanged",
        state: "edit"
      });
    };

    window._xeDowngrade = () => {
      XenonConnectionActions.postMessage({
        messageName: "stateChanged",
        state: "view"
      });
    };

    window.onbeforeunload = this.onReload;

    this.checkParameters();

    const { isUserInfoLoaded } = this.state;
    const doesSessionIdExist = Storage.getItem("sessionId") !== null;
    FilesListStore.addEventListener(DRAWING_RELOAD, this.reloadIframe);
    FilesListStore.addEventListener(
      CURRENT_FILE_INFO_UPDATED,
      this.onCurrentFileUpdated
    );
    if (doesSessionIdExist) {
      XenonConnectionStore.addEventListener(
        SWITCH_EDITOR_MODE,
        this.setEditorMode
      );
      XenonConnectionStore.addEventListener(
        DRAWING_MODIFIED,
        DrawingLoader.onDrawingModified
      );
      XenonConnectionStore.addEventListener(
        STOP_PROGRESSOR,
        this.listenForModifications
      );
      if (!isUserInfoLoaded) {
        UserInfoStore.addChangeListener(INFO_UPDATE, this.initializeFile);
        UserInfoActions.getUserInfo();
        UserInfoActions.getUserStorages();
      } else {
        this.initializeFile();
      }
      document.addEventListener(
        "visibilitychange",
        DrawingLoader.sendSaveEvent,
        false
      );
    } else {
      this.initializeFile();
    }
  };

  onCurrentFileUpdated = () => {
    const { conflictingReason = null } = FilesListStore.getCurrentFile();
    const { conflictingReason: stateReason } = this.state;
    if (stateReason !== conflictingReason) {
      this.setState({ conflictingReason });
      if (conflictingReason !== null) {
        if (conflictingReason !== conflictingReasons.UNSHARED_OR_DELETED) {
          this.reloadIframe(true, true);
        } else {
          this.getRenderElement();
        }
      }
    }
  };

  listenForModifications = () => {
    const { isViewOnly } = this.state;
    if (!isViewOnly) {
      XenonConnectionActions.postMessage({
        messageName: "eventRegister",
        eventName: "drawingModifiedEvent"
      });
    }
  };

  // removes non-file specific listeners
  // @see addInternalListeners
  removeInternalListeners = () => {
    UserInfoStore.removeChangeListener(
      STORAGES_UPDATE,
      this.onStorageListUpdate
    );
    FilesListStore.removeEventListener(DRAWING_RELOAD, this.reloadIframe);
    XenonConnectionStore.removeEventListener(
      SWITCH_EDITOR_MODE,
      this.setEditorMode
    );
    XenonConnectionStore.removeEventListener(
      DRAWING_MODIFIED,
      DrawingLoader.onDrawingModified
    );
    XenonConnectionStore.removeEventListener(
      STOP_PROGRESSOR,
      this.listenForModifications
    );
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.initializeFile);
    // progressor won't be shown - just use for stats
    XenonConnectionStore.removeEventListener(STOP_PROGRESSOR, this.trackStop);
    document.removeEventListener(
      "visibilitychange",
      DrawingLoader.sendSaveEvent,
      false
    );
    delete window.getDevLink;
    delete window._xeUpgrade;
    delete window._xeDowngrade;

    ModalStore.removeListener(CHANGE, this.onOpenGdriveTempFileModalClose);
    FilesListStore.addEventListener(
      CURRENT_FILE_INFO_UPDATED,
      this.onCurrentFileUpdated
    );
  };

  trackStop = () => {
    workStartTime = Date.now();
    openTimeTracker.logEvent("progressor stoped");
    const { fileId } = this.state;
    const eventData = {
      fileId,
      fileName: FilesListStore.getCurrentFile().name,
      fullOpenTime: workStartTime - openTimeTracker.startTime,
      xenonOpenTime: workStartTime - frameOpenTime
    };
    Tracker.sendIntercomEvent("file-open-finish", eventData);
  };

  /**
   * Logout timer as fallback for broken WS connection
   * Will logout automatically after 5 seconds.
   * If it happens - WS has to be checked!
   * @link https://graebert.atlassian.net/browse/XENON-30729
   * @throws Error
   */
  setLogoutTimer = () => {
    setTimeout(() => {
      Logger.addEntry(
        "ERROR",
        "Logout timer expired! This should be avoided! Check DrawingLoader->setLogoutTimer()"
      );
      WebsocketStore.removeEventListener(
        CHANGE_EVENT,
        this.onWebsocketMessageLog
      );
      DrawingLoader.doLogout();
      throw new Error("Logout timer expired! This should be avoided!");
    }, 5000);
  };

  reloadIframe = (
    isViewOnly,
    isForced = false,
    isSessionUpdateRequired = true
  ) => {
    const token = MainFunctions.QueryString("token") || "";

    if (UserInfoStore.isLogoutPending()) {
      if (token) {
        DrawingLoader.doLogout();
        location.reload();
        return;
      }
      this.setState({ unloadAndLogout: true }, () => {
        this.setLogoutTimer();
        this.getRenderElement();
      });
    } else {
      const { isEditorToShow, isFileInfoLoaded, currentUserFileId } =
        this.state;
      this.setState(
        {
          isLoadFinished: isEditorToShow && isFileInfoLoaded,
          isViewOnly,
          token,
          isTokenAccess: token.length > 0
        },
        () => {
          if (isEditorToShow && isFileInfoLoaded && !isForced) {
            XenonConnectionActions.postMessage({
              messageName: "stateChanged",
              state: isViewOnly ? "view" : "edit",
              isSessionUpdateRequired
            });
          } else {
            this.initializeFile();
            this.getRenderElement(currentUserFileId);
          }
        }
      );
    }
  };

  setEditorMode = msgData => {
    if ((msgData.message || "").length > 0) {
      SnackbarUtils.alertWarning(msgData.message);
    }
    const viewOnly = msgData.viewOnly === "true";
    const reloadRequired = msgData.reloadRequired === "true";
    FilesListActions.saveFileViewFlag(viewOnly);
    if (reloadRequired) {
      this.setState({ isLoadFinished: false, isViewOnly: viewOnly }, () => {
        this.initializeFile();
        this.getRenderElement();
      });
    }
  };

  loadExternalFile = () => {
    const type = MainFunctions.QueryString("type");
    if (type === "box") {
      const authCode = MainFunctions.QueryString("auth_code");
      const fileId = MainFunctions.QueryString("fileId");
      if (authCode && fileId) {
        const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
        const fullFileId = `BX+${authCode}+${fileId}+0`;
        this.setState({ fileId: fullFileId }, () => {
          const params = _.extend(
            _.omit(
              MainFunctions.QueryString(),
              "fileId",
              "userId",
              "type",
              "auth_code"
            ),
            { external: true }
          );
          const formedParams = Object.entries(params)
            .map(([key, val]) => `${key}=${val}`)
            .join("&");
          ApplicationActions.changePage(
            `${UIPrefix}file/${fullFileId}?${formedParams}`
          );
          this.initializeFile();
          this.getRenderElement();
        });
      }
    } else if (type === "gdrive") {
      const accountId = MainFunctions.QueryString("userId");
      const fileId = MainFunctions.QueryString("fileId");
      const { isUserInfoLoaded } = this.state;
      const doesSessionIdExist = Storage.getItem("sessionId") !== null;
      if (doesSessionIdExist && !isUserInfoLoaded) {
        // wait for login
        // handled already, so no need to trigger anything
      } else if (isUserInfoLoaded) {
        // ideally we'd want to get this data from userInfoStore
        UserInfoActions.getUserStorages()
          .then(userStorages => {
            let isConnected = false;
            const { gdrive } = userStorages;
            if (gdrive && gdrive.length > 0) {
              const account = gdrive.find(a => a.gdrive_id === accountId);
              if (account) {
                isConnected = true;
                // open regularly
                ApplicationActions.changePage(
                  `/file/GD+${accountId}+${fileId}`
                );
              }
            }
            return isConnected;
          })
          .then(isConnected => {
            if (!isConnected) {
              ModalActions.promptToConnectAccount(accountId, fileId);
              ModalStore.addListener(CHANGE, this.onGDriveAccountNotConnected);
            }
          });
      } else {
        ModalActions.openGdriveTempFile(
          MainFunctions.QueryString("userId"),
          MainFunctions.QueryString("fileId")
        );
        ModalStore.addListener(CHANGE, this.onOpenGdriveTempFileModalClose);
        this.setState({
          isLoadFinished: true
        });
      }
    } else {
      UserInfoActions.getUserStorages();
      UserInfoStore.addChangeListener(
        STORAGES_UPDATE,
        this.onStorageListUpdate
      );
    }
  };

  onGDriveAccountNotConnected = () => {
    if (!ModalStore.isDialogOpen()) {
      ModalStore.removeListener(CHANGE, this.onGDriveAccountNotConnected);

      this.setState(
        {
          isLoadFinished: true,
          errorMessage: "connectGDriveToOpenFile",
          actionButtons: (
            <Grid item xs={12}>
              <KudoButton
                useRightPadding
                isDisabled={false}
                onClick={() => {
                  ApplicationActions.changePage("/files");
                }}
              >
                <FormattedMessage id="return" />
              </KudoButton>
              <KudoButton
                isDisabled={false}
                onClick={() => {
                  UserInfoActions.connectStorage(
                    "gdrive",
                    location.pathname + location.search
                  );
                }}
              >
                <FormattedMessage id="connect" />
              </KudoButton>
            </Grid>
          )
        },
        () => {
          this.getRenderElement();
        }
      );
    }
  };

  onOpenGdriveTempFileModalClose = () => {
    ModalStore.removeListener(CHANGE, this.onOpenGdriveTempFileModalClose);

    this.setState(
      {
        errorMessage: "loginToKudoToOpenFile",
        actionButtons: (
          <KudoButton
            isDisabled={false}
            onClick={() => {
              UserInfoActions.loginWithSSO(location.pathname + location.search);
            }}
            styles={{
              button: {
                marginLeft: "10px",
                marginTop: "-5px"
              }
            }}
          >
            <FormattedMessage id="login" />
          </KudoButton>
        )
      },
      () => {
        this.getRenderElement();
      }
    );
  };

  onStorageListUpdate = () => {
    const type = MainFunctions.QueryString("type");
    const accountId = MainFunctions.QueryString("userId");
    if (type && accountId) {
      const storages = UserInfoStore.getStoragesInfo();
      const account = _.find(
        storages[type],
        accountEntry => accountEntry[`${type}_id`] === accountId
      );
      if (account) {
        let shortStorageName = "";
        switch (type) {
          case "gdrive":
            shortStorageName = "GD";
            break;
          case "box":
          default:
            shortStorageName = "BX";
            break;
        }
        const fileId = MainFunctions.QueryString("fileId");
        const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
        const fullFileId = `${shortStorageName}+${
          account[`${type}_id`]
        }+${fileId}`;
        this.setState({ fileId: fullFileId }, () => {
          const params = _.extend(
            _.omit(
              MainFunctions.QueryString(),
              "fileId",
              "userId",
              "type",
              "auth_code"
            ),
            { external: true }
          );
          const formedParams = Object.entries(params)
            .map(([key, val]) => `${key}=${val}`)
            .join("&");
          ApplicationActions.changePage(
            `${UIPrefix}file/${fullFileId}?${formedParams}`
          );
          this.initializeFile();
          this.getRenderElement();
        });
      } else {
        ModalActions.connectStorageRequest(type);
      }
    }
  };

  getSessionDetails = () =>
    new Promise((resolve, reject) => {
      const { fileId } = this.state;
      Requests.sendGenericRequest(
        `/files/${encodeURIComponent(fileId)}/session`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(response => {
          if (
            response.data.results &&
            _.isArray(response.data.results) &&
            response.data.results.length > 0
          ) {
            // ignore Commander sessions.
            const editors =
              _.filter(
                response.data.results,
                sessionInfo =>
                  sessionInfo.device !== "COMMANDER" &&
                  sessionInfo.mode === "edit"
              ) || [];
            if (_.isArray(editors)) {
              const userId = UserInfoStore.getUserInfo("id");
              if (editors.length > 0) {
                const editorIsMeObj = _.findWhere(editors, { userId });
                if (editorIsMeObj !== undefined) {
                  FilesListActions.saveEditingUserId(userId);
                  reject(new SessionError("0", 0, editorIsMeObj._id)); // opened by self in edit mode
                } else {
                  const [mainEditor] = editors;
                  FilesListActions.saveEditingUserId(mainEditor.userId);
                  reject(new SessionError("1", 1, mainEditor._id)); // opened by others in edit mode
                }
              } else {
                // no edit session exists
                resolve();
              }
            } else {
              SnackbarUtils.alertError({ id: "errorGettingSessionDetails" });
              reject(new SessionError("2", 2, "")); // error on getting details
            }
          } else {
            // no session exists
            resolve();
          }
        })
        .catch(() => {
          reject(new SessionError("2", 2, "")); // error on getting details
        });
    });

  checkPasswordForPublicLink = () =>
    new Promise(resolve => {
      const { isTokenAccess, token, fileId } = this.state;
      if (isTokenAccess) {
        const password =
          Storage.store(btoa(`password_${fileId}_${token}`)) || ""; // here password is ha1
        if (password.length > 0) {
          FilesListActions.getNonceForPublicLinkPassword()
            .then(({ nonce, realm, opaque }) => {
              const passwordHeader = FilesListActions.generatePasswordHeader({
                realm,
                nonce,
                opaque,
                ha1: password
              });
              resolve(passwordHeader);
            })
            .catch(() => {
              resolve("");
            });
        } else {
          resolve("");
        }
      } else {
        resolve("");
      }
    });

  getFileDetails = () => {
    openTimeTracker.logEvent("getFileDetails started");
    // reset refresh/viewOnly flag
    Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);

    const {
      isTokenAccess,
      token,
      isExternal,
      isViewOnly,
      fileId,
      isPasswordRequired,
      versionId
    } = this.state;

    this.checkPasswordForPublicLink().then(password => {
      FilesListActions.getObjectInfo(fileId, "file", {
        isExternal,
        token,
        versionId,
        password
      })
        .then(fileInfo => {
          openTimeTracker.logEvent("getFileDetails received response");

          const { editingUserId } = FilesListStore.getCurrentFile();

          FilesListActions.saveFile(_.extend(fileInfo, { editingUserId }));
          this.setState({ fileId: fileInfo._id });

          // if we were showing password error, but now it's fine
          if (password.length > 0 && isPasswordRequired) {
            this.setState({
              isPasswordRequired: false
            });
          }

          this.connectFileListeners();

          // if xenon cannot be run (compatibility issues) - we won't even load file details
          if (isTokenAccess) {
            FilesListActions.saveFileDirectAccessFlag(false);
            Tracker.sendGAEvent(
              AK_GA.category,
              AK_GA.actions.drawingOpen,
              "file opened by token"
            );

            if (!editingUserId)
              FilesListActions.saveEditingUserId(fileInfo.creatorId);

            const isLoggedIn = Storage.getItem("sessionId") !== null;

            if (isLoggedIn) {
              const abortTimer = setTimeout(() => {
                FetchAbortsControl.abortAllSignalsByType(INFO);
                this.setState(
                  {
                    isViewOnly: true,
                    isFileInfoLoaded: true,
                    isLoadFinished: true,
                    isEditorToShow: true
                  },
                  () => {
                    this.getRenderElement();
                    FilesListActions.saveFileViewFlag(true);
                  }
                );
              }, 5000);

              const { storageType, objectId } =
                MainFunctions.parseObjectId(fileId);

              if (!isExternal && (!versionId || !versionId.length)) {
                const specialFileId = `${storageType}+${objectId}`;
                FilesListActions.getObjectInfo(specialFileId, "file", {})
                  .then(file => {
                    clearTimeout(abortTimer);
                    // if user logged in and info request is success
                    // check permissions and set view flag based on them
                    let isViewOnlyFlag = true;

                    const currentUserEmail = UserInfoStore.getUserInfo("email");

                    if (file.isOwner) isViewOnlyFlag = false;

                    const isFreeAccount = UserInfoStore.isFreeAccount();

                    if (
                      !isFreeAccount &&
                      isViewOnlyFlag &&
                      storageType === "SF"
                    ) {
                      file.share.editor.forEach(elem => {
                        if (elem.email === currentUserEmail)
                          isViewOnlyFlag = false;
                      });
                    } else if (!isFreeAccount && isViewOnlyFlag) {
                      const storageName =
                        MainFunctions.storageCodeToServiceName(storageType);

                      const connectedStorages =
                        UserInfoStore.getStoragesInfo()[storageName];

                      if (!connectedStorages) isViewOnlyFlag = false;
                      else {
                        const connectedStoragesUserNames =
                          connectedStorages.map(
                            elem => elem[`${storageName}_username`]
                          );

                        if (isViewOnlyFlag) {
                          file.share.editor.forEach(elem => {
                            if (
                              isViewOnlyFlag &&
                              connectedStoragesUserNames.indexOf(elem.email) >
                                -1
                            )
                              isViewOnlyFlag = false;
                          });
                          // https://graebert.atlassian.net/browse/XENON-51647
                          // For TC files aren't shared, so share is empty.
                          // Some storages do not return share info, but they have reliable viewOnly flag
                          if (
                            isViewOnlyFlag &&
                            !file.viewOnly &&
                            (storageType === "TR" ||
                              storageType === "SP" ||
                              storageType === "NC" ||
                              storageType === "OS" ||
                              storageType === "ODB")
                          ) {
                            isViewOnlyFlag = false;
                          }
                        }
                      }
                    }

                    if (fileInfo.deleted === true) {
                      SnackbarUtils.alertWarning({ id: "fileHasBeenDeleted" });
                      this.setState(
                        {
                          isFileDeleted: true,
                          isLoadFinished: true,
                          isFileInfoLoaded: true
                        },
                        () => {
                          this.getRenderElement();
                        }
                      );
                      return;
                    }

                    // XENON-50766
                    if (!isViewOnlyFlag && storageType === "BX") {
                      isViewOnlyFlag = MainFunctions.isInIframe();
                    }

                    if (isFreeAccount) isViewOnlyFlag = true;

                    FilesListActions.saveFileDirectAccessFlag(true);
                    FilesListActions.saveFileViewFlag(isViewOnlyFlag);
                    this.setState(
                      {
                        isViewOnly: isViewOnlyFlag,
                        isFileInfoLoaded: true,
                        isLoadFinished: true,
                        isEditorToShow: true,
                        hasDirectFileAccess: true,
                        currentUserFileId: file._id
                      },
                      () => {
                        this.getRenderElement(file._id || specialFileId);
                      }
                    );
                  })
                  .catch(() => {
                    clearTimeout(abortTimer);
                    // if info request was failed by some reason set view only mode
                    this.setState(
                      {
                        isViewOnly: true,
                        isFileInfoLoaded: true,
                        isLoadFinished: true,
                        isEditorToShow: true
                      },
                      () => {
                        this.getRenderElement();
                        FilesListActions.saveFileViewFlag(true);
                      }
                    );
                  });
              }
            } else {
              this.setState(
                {
                  isViewOnly: true,
                  isFileInfoLoaded: true,
                  isLoadFinished: true,
                  isEditorToShow: true
                },
                () => {
                  this.getRenderElement();
                  FilesListActions.saveFileViewFlag(true);
                }
              );
            }
          } else {
            if (fileInfo.deleted === true) {
              SnackbarUtils.alertWarning({ id: "fileHasBeenDeleted" });
              this.setState(
                {
                  isFileDeleted: true,
                  isLoadFinished: true,
                  isFileInfoLoaded: true
                },
                () => {
                  this.getRenderElement();
                }
              );
              return;
            }

            let viewOnlyFlag = !DrawingLoader.checkIfForceEdit() && isViewOnly;
            const fileExtension = MainFunctions.getExtensionFromName(
              fileInfo.filename
            );
            if (
              MainFunctions.QueryString("access") === "view" ||
              MainFunctions.QueryString("versionId").length > 0 ||
              UserInfoStore.isFreeAccount() === true ||
              fileExtension === "rvt" ||
              fileExtension === "rfa" ||
              MainFunctions.isMobileDevice()
            ) {
              // forced viewOnly:
              // external,
              // revit
              // or is free account
              viewOnlyFlag = true;
            } else if (!isViewOnly) {
              // if state was already viewOnly - it'd come for a reason (reload,free account etc.)
              // getting viewOnly from file
              viewOnlyFlag = !!fileInfo.viewOnly;
            }
            // if it's viewOnly - save this info and open for view
            if (!DrawingLoader.checkIfForceEdit() && viewOnlyFlag) {
              this.setState(
                {
                  isViewOnly: viewOnlyFlag,
                  isFileInfoLoaded: true,
                  isLoadFinished: true,
                  isEditorToShow: true
                },
                () => {
                  this.getRenderElement();
                  // let's get active sessions to receive current editor's id
                  setTimeout(() => {
                    this.getSessionDetails();
                  }, 2000);

                  FilesListActions.saveFileViewFlag(viewOnlyFlag);
                }
              );
            } else if (
              !DrawingLoader.checkIfForceEdit() &&
              UserInfoStore.getUserInfo("options").editor === false
            ) {
              // editing has been disabled for the user, so file should be opened as view only
              this.setState(
                {
                  isViewOnly: true,
                  isFileInfoLoaded: true,
                  isLoadFinished: true,
                  isEditorToShow: true
                },
                () => {
                  this.getRenderElement();
                  FilesListActions.saveFileViewFlag(true);
                }
              );
            } else {
              // editing is allowed
              FilesListActions.saveEditingUserId(
                UserInfoStore.getUserInfo("id")
              );
              this.setState(
                {
                  isViewOnly: false,
                  isFileInfoLoaded: true,
                  isLoadFinished: true,
                  isEditorToShow: true
                },
                () => {
                  FilesListActions.saveFileViewFlag(false);
                  this.getRenderElement();
                }
              );
            }
          }
        })
        .catch(err => {
          if (err.code === 403) {
            if (isTokenAccess) {
              // check if password is required
              const { errorCode } = err.data;
              if (
                errorCode === 10 ||
                errorCode === 11 ||
                errorCode === 12 ||
                errorCode === 2
              ) {
                Storage.deleteValue(btoa(`password_${fileId}_${token}`));
                ModalActions.passwordRequiredForLink(fileId, token);
                this.setState(
                  {
                    isPasswordRequired: true
                  },
                  () => {
                    this.getRenderElement();
                  }
                );
              } else if (errorCode === 1) {
                this.setState(
                  {
                    isLinkExpired: true
                  },
                  () => {
                    this.getRenderElement();
                  }
                );
              } else {
                // public access has been canceled or incorrect token provided or file has been deleted or storage has been deleted from AK etc.
                Tracker.sendGAEvent(
                  AK_GA.category,
                  AK_GA.actions.drawingOpen,
                  "public access has been canceled or incorrect token provided"
                );
              }
              // Removed alert - see XENON-16339
              // SnackbarUtils.alertError({ id: "noPublicAccessToFileOrIncorrectToken" });
              this.setState(
                {
                  isLoadFinished: true,
                  isFileInfoLoaded: false,
                  isEditorToShow: false
                },
                () => {
                  this.getRenderElement();
                }
              );
            } else {
              Tracker.sendGAEvent(
                AK_GA.category,
                AK_GA.actions.drawingOpen,
                "user doesn't have access to file"
              );
              // if user doesn't have access to file
              // Request access dialog has been disabled - see XENON-12094
              // ModalActions.requestAccess(self.props.currentFile);
              ApplicationActions.changePage(
                `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
              );
              SnackbarUtils.alertWarning({ id: "noAccessToFile" });
            }
          } else if (err.code === 404) {
            // file not found
            if (!isTokenAccess) {
              Tracker.sendGAEvent(
                AK_GA.category,
                AK_GA.actions.drawingOpen,
                "file not found (regular access)"
              );
              // if token wasn't passed - return user to it's list of files
              ApplicationActions.changePage(
                `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
              );
            } else {
              Tracker.sendGAEvent(
                AK_GA.category,
                AK_GA.actions.drawingOpen,
                "file not found (token access)"
              );
            }
            this.setState(
              {
                isLoadFinished: true,
                isFileInfoLoaded: false,
                isEditorToShow: false,
                errorMessage: "fileNotExist"
              },
              () => {
                this.getRenderElement();
              }
            );
            SnackbarUtils.alertError({ id: "fileNotExist" });
          } else {
            ApplicationActions.changePage(
              `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
            );
            SnackbarUtils.alertError(err.text);
            this.setState(
              {
                isLoadFinished: true,
                isFileInfoLoaded: false,
                isEditorToShow: false,
                errorMessage: err.text
              },
              () => {
                this.getRenderElement();
              }
            );
          }
        });
    });
  };

  getForceGetEditFunction = () =>
    new Promise((resolve, reject) => {
      this.getSessionDetails()
        .then(() => {
          resolve(true);
        })
        .catch(err => {
          // opened by self
          if (err.code === 0) {
            const { editSessionId } = err;
            const forceGetEdit = () => {
              if (editSessionId) {
                const { fileId } = this.state;
                const headers = {
                  xSessionId: editSessionId,
                  sessionId: Storage.getItem("sessionId"),
                  downgrade: true
                };
                Requests.sendGenericRequest(
                  `/files/${fileId}/session`,
                  RequestsMethods.PUT,
                  headers,
                  undefined,
                  ["*"]
                )
                  .then(response => {
                    if (response.data.checkinFailed === true) {
                      SnackbarUtils.info("checkInFailed");
                      return;
                    }
                    this.initializeFile();
                  })
                  .catch(downgradeErr => {
                    SnackbarUtils.error(downgradeErr.text);
                  });
              } else {
                this.initializeFile();
              }
            };
            resolve(forceGetEdit);
          } else {
            reject();
          }
        });
    });

  retryForLockedFile = () => {
    if (!location.pathname.includes("file/")) {
      // if it's not file page - let's clear
      Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);
      clearTimeout(window.retryTimeout);
      delete window.retryTimeout;
    } else if (DrawingLoader.checkIfForceEdit()) {
      this.getForceGetEditFunction()
        .then(forceGetEdit => {
          Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);
          clearTimeout(window.retryTimeout);
          delete window.retryTimeout;
          if (forceGetEdit === true) {
            this.initializeFile();
          } else {
            forceGetEdit();
          }
        })
        .catch(err => {
          console.log(err);
          // this.initializeFile();
        });
    } else {
      retryCount += 1;
      if (retryCount > MAX_RETRY) {
        globalRetryCount += 1;
        SnackbarUtils.closeStickyAlert();
        retryCount = -1;
        const onViewOnly = () => {
          // just open as view only as user has agreed to do so
          Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);
          this.setState(
            {
              isViewOnly: true
            },
            () => {
              this.initializeFile();
              this.getRenderElement();
            }
          );
        };
        const onAction = () => {
          // keep retrying until we're all good
          // same as onWait at least for now
          // so no point in creating another function
          this.initializeFile();
        };
        if (globalRetryCount >= 2) {
          this.getForceGetEditFunction()
            .then(forceGetEdit => {
              if (forceGetEdit === true) {
                this.initializeFile();
              } else {
                ModalActions.confirmWaitOrReload(
                  // force get edit
                  forceGetEdit,
                  // if closed - open as view only
                  onViewOnly,
                  onViewOnly,
                  true
                );
              }
            })
            .catch(() => {
              onViewOnly();
            });
        } else {
          ModalActions.confirmWaitOrReload(
            onAction,
            onAction,
            onViewOnly,
            false
          );
        }
      } else {
        this.getSessionDetails()
          .then(this.getFileDetails)
          .catch(err => {
            if (err.message === "0") {
              // opened by self -> retry
              if (!SnackbarUtils.isStickyAlertOpen()) {
                SnackbarUtils.alertInfo({ id: "waitingForSessionClose" }, true);
              }

              window.retryTimeout = setTimeout(() => {
                this.initializeFile();
              }, 1000);
            } else {
              // other person is editing the file or
              // there is an error receiving sessions
              // -> open for view only
              this.setState({ isViewOnly: true }, () => {
                this.getFileDetails();
                this.getRenderElement();
              });
              Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);
              clearTimeout(window.retryTimeout);
              delete window.retryTimeout;
            }
          });
      }
    }
  };

  initializeFile = () => {
    openTimeTracker.logEvent("initializeFile started");
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.initializeFile);

    const { isLoadFinished, isXenonAllowed, fileId, isUserInfoLoaded } =
      this.state;
    const isUserInfoLoadedNow =
      UserInfoStore.getUserInfo("isLoggedIn") &&
      UserInfoStore.getUserInfo("isFullInfo");

    if (!isUserInfoLoaded && isUserInfoLoadedNow) {
      this.setState({ isUserInfoLoaded: true });
    }
    if (!isXenonAllowed || isLoadFinished) {
      return false;
    }

    if (fileId === "external") {
      this.loadExternalFile();
    } else if (
      Storage.isSessionStorageSupported === true &&
      Storage.getSessionStorageItem("viewFlag") === false &&
      Storage.getSessionStorageItem("refreshed") === true
    ) {
      if (!MainFunctions.isMobileDevice()) {
        this.retryForLockedFile();
      } else {
        Storage.deleteSessionStorageItems(["refreshed", "viewFlag"]);
        clearTimeout(window.retryTimeout);
        delete window.retryTimeout;
      }
    } else {
      this.getFileDetails();
      if (Storage.getItem("sessionId") !== null) {
        this.getSessionDetails().catch(() => null);
      }
    }
    return true;
  };

  /**
   * Validates all parameters supplied
   */
  checkParameters = () => {
    const { isTokenAccess, fileId } = this.state;
    new Promise((resolve, reject) => {
      if (fileId === "external") {
        // if it's external integration
        const type = MainFunctions.QueryString("type");
        if (type === "box") {
          // for box - authCode and fileId is mandatory
          const authCode = MainFunctions.QueryString("auth_code");
          const boxFileId = MainFunctions.QueryString("fileId");
          if (authCode.length > 0 && boxFileId.length > 0) {
            resolve();
          } else {
            reject(new Error("URLIsInvalid"));
          }
        } else if (type.length > 0) {
          // for other integrations (e.g. google) no need to check yet
          resolve();
        } else {
          // if integration type isn't set - it's incorrect
          reject(new Error("URLIsInvalid"));
        }
      } else if (
        !fileId ||
        typeof fileId !== "string" ||
        fileId.length < "FL+_+".length
      ) {
        reject(new Error("incorrectFileId"));
      } else if (Storage.getItem("sessionId") !== null) {
        // logged in user - only fileId is required
        if (fileId) {
          resolve();
        } else {
          reject(new Error("URLIsInvalid"));
        }
      } else if (isTokenAccess) {
        // if token is provided - this is public link
        resolve();
      }
    })
      .then(() => {
        openTimeTracker.logEvent("checkParams");
      })
      .catch(err => {
        this.setState(
          {
            isLoadFinished: true,
            isFileInfoLoaded: false,
            isEditorToShow: false,
            errorMessage: err.getMessage
          },
          () => {
            this.getRenderElement();
          }
        );
      });
  };

  onReload = () => {
    const { isViewOnly } = this.state;
    const viewFlag =
      isViewOnly || FilesListStore.getCurrentFile().viewFlag === true;
    Storage.setSessionStorageItems({ viewFlag, refreshed: true });
  };

  getRenderElement = (specialFileId = null) => {
    const {
      sheetId,
      token,
      isViewOnly,
      isEditorToShow,
      isXenonAllowed,
      isFileInfoLoaded,
      isLoadFinished,
      compatibilityIssues,
      unloadAndLogout,
      isTokenAccess,
      conflictingReason,
      errorMessage,
      fileId,
      isIntercomEventSent,
      isPasswordRequired,
      isLinkExpired,
      versionId,
      hasDirectFileAccess,
      isExternal,
      actionButtons,
      isFileDeleted
    } = this.state;
    const fileData = {
      fileId,
      fileName: FilesListStore.getCurrentFile().name,
      isDeleted: !!isFileDeleted,
      isOpened: false,
      isErrored: false,
      isViewOnly: false,
      isOpenedByPublicLink: isTokenAccess,
      publicToken: token,
      issue: ""
    };
    let elementToRender = null;
    if (isLoadFinished) {
      document.title = `${window.ARESKudoConfigObject.defaultTitle} | ${
        FilesListStore.getCurrentFile().name
      }`;
    }
    if (!isXenonAllowed) {
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.actions.drawingOpen,
        "compatibility check failed"
      );
      fileData.isErrored = true;
      fileData.issue = `Compatibility check failed:${compatibilityIssues.join(
        ", "
      )}`;
      const messages = compatibilityIssues.map(reason => (
        <FormattedMessage key={reason.id} id={reason.id} values={reason} />
      ));
      if (
        CompatibilityCheck.isBrowserIsChrome() &&
        CompatibilityCheck.isWebGLSupported().valid === false
      )
        messages.push(
          <FormattedMessage
            id="chromeWebGLmessage"
            values={{ br: IntlTagValues.br }}
          />
        );
      elementToRender = <ErrorMessage messages={messages} />;
    } else if (!isLoadFinished) {
      elementToRender = (
        <ErrorMessage>
          <Loader />
        </ErrorMessage>
      );
    } else if (UserInfoStore.isLogoutPending() && unloadAndLogout) {
      elementToRender = (
        <ErrorMessage messages={[<FormattedMessage id="loggingOut" />]} />
      );
    } else if (
      isFileDeleted ||
      conflictingReason === conflictingReasons.UNSHARED_OR_DELETED
    ) {
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.actions.drawingOpen,
        "drawing has been deleted"
      );
      fileData.isDeleted = true;
      fileData.issue = "File is deleted";
      fileData.isErrored = true;
      elementToRender = (
        <ErrorMessage
          messages={[<FormattedMessage id="relatedFileIsDeleted" />]}
        />
      );
    } else if (errorMessage.length > 0) {
      fileData.issue = errorMessage;
      fileData.isErrored = true;
      elementToRender = (
        <ErrorMessage messages={[<FormattedMessage id={errorMessage} />]}>
          {actionButtons}
        </ErrorMessage>
      );
    } else if (isPasswordRequired) {
      fileData.isOpened = false;
      fileData.issue = "Password access";
      elementToRender = (
        <ErrorMessage
          messages={[
            <FormattedMessage id="passwordAccessDrawingPageMessage" />
          ]}
        />
      );
    } else if (isLinkExpired) {
      fileData.isOpened = false;
      fileData.issue = "Expired link";
      elementToRender = (
        <ErrorMessage
          messages={[<FormattedMessage id="expiredLinkDrawingPageMessage" />]}
        />
      );
    } else if (isEditorToShow && isFileInfoLoaded) {
      openTimeTracker.logEvent("frame opened");
      frameOpenTime = Date.now();

      const password = Storage.store(btoa(`password_${fileId}_${token}`)) || ""; // here password is ha1
      return new Promise(() => {
        const baseParameters = {
          server: ApplicationStore.getApplicationSetting("server"),
          token,
          password,
          isViewOnly,
          fileName: fileData.fileName,
          sessionId: Storage.getItem("sessionId"),
          versionId,
          debug: ApplicationStore.getApplicationSetting("debug"),
          locale: Storage.store("locale"),
          oldUI: UserInfoStore.getUserInfo("preferences")?.useOldUI === true
        };

        if (sheetId && sheetId.length) {
          baseParameters.sheetId = sheetId;
        }

        if (isExternal) {
          delete baseParameters.token;
          if (!baseParameters.sessionId) {
            delete baseParameters.sessionId;
          }
        }

        if (!isViewOnly || hasDirectFileAccess) delete baseParameters.token;

        if (!baseParameters.sessionId) delete baseParameters.sessionId;

        Storage.store("baseEditorParameters", JSON.stringify(baseParameters));
        Storage.store("currentFileId", fileId);
        AccessToken.create(specialFileId || fileId, baseParameters)
          .then(link => {
            Storage.store("currentURL", link);
            Tracker.sendGAEvent(
              AK_GA.category,
              AK_GA.actions.drawingOpen,
              "iframe opened"
            );
            fileData.isOpened = true;
            if (!isIntercomEventSent) {
              Tracker.sendIntercomEvent("file-open", fileData);
              this.setState({ isIntercomEventSent: true });
            }
            this.setState({
              renderElement: (
                /* eslint-disable */
                <iframe
                  style={{
                    width: "100%",
                    height: "calc(100% - 50px)",
                    border: "none",
                  }}
                  id="draw"
                  title="editor"
                  src={link}
                  onClick={this.handleIframeClick}
                  allow="microphone; clipboard-read; clipboard-write"
                />
                /* eslint-enable */
              )
            });
          })
          .catch(err => {
            fileData.isOpened = false;
            fileData.issue = err;
            if (!isIntercomEventSent) {
              Tracker.sendIntercomEvent("file-open", fileData);
              this.setState({ isIntercomEventSent: true });
            }
          });
      });
    } else {
      fileData.isErrored = true;
      fileData.issue = "Incorrect token or no public access";
      elementToRender = (
        <ErrorMessage
          messages={[
            <FormattedMessage id="unableToOpenFileUsingLink" />,
            <FormattedMessage id="possibleReasons" />,
            <ul style={{ textAlign: "left" }}>
              <li>
                <FormattedMessage id="fileIsDeletedOrNotPublic" />
              </li>
              <li>
                <FormattedMessage id="linkOwnerHasNoAccessToTheFileAnymore" />
              </li>
              <li>
                <FormattedMessage
                  id="linkOwnerHasDeletedStorage"
                  values={{
                    product: ApplicationStore.getApplicationSetting("product")
                  }}
                />
              </li>
              <li>
                <FormattedMessage id="linkOwnerHasNoLicenseAnymore" />
              </li>
            </ul>
          ]}
        />
      );
    }
    if (!isIntercomEventSent && fileData.isErrored) {
      Tracker.sendIntercomEvent("file-open", fileData);
      this.setState({ isIntercomEventSent: true });
    }
    this.setState({ renderElement: elementToRender });
    return elementToRender;
  };

  onXenonMessageLog = () => {
    const message = XenonConnectionStore.getCurrentState().lastMessage;
    if (message && message.messageName === "glTestResults") {
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.actions.drawingOpen,
        `webgl test ${
          message.data.isTestPassed === true ? "passed" : "failed"
        }`,
        message.data.avgTime
      );
      if (
        message.data.isTestPassed === true ||
        Storage.store("noGLWarning") === "true"
      ) {
        this.setState(
          {
            isGLTestPassed: true
          },
          () => {
            this.getRenderElement();
          }
        );
      }
    }
  };

  onWebsocketMessageLog = event => {
    const connection = WebsocketStore.getConnectionInfo(event.id);
    if (connection) {
      const isConnected = connection.isConnected === true;
      const lastMessage = connection.lastMessage
        ? connection.lastMessage.messageType
        : null;

      const { unloadAndLogout, socketId, fileId, isTokenAccess, isViewOnly } =
        this.state;
      if (
        event.type === "newSocket" &&
        event.socketType === FILE_SOCKET &&
        socketId !== event.id
      ) {
        this.setState({ socketId: event.id });
      } else if (
        event.socketType === FILE_SOCKET &&
        event.type === "deshared" &&
        UserInfoStore.getUserInfo("isLoggedIn")
      ) {
        const { storageType } = MainFunctions.parseObjectId(fileId);

        let shouldDisableUser;
        if (storageType === "SF")
          shouldDisableUser = event.collaborators.includes(
            UserInfoStore.getUserInfo("id")
          );
        else {
          const storageName =
            MainFunctions.storageCodeToServiceName(storageType);
          const { storageId } = FilesListStore.findCurrentStorage();
          const storageEmail = UserInfoStore.findStorageEmail(
            storageName,
            storageId
          );
          shouldDisableUser = event.collaborators.includes(storageEmail);
        }

        if (shouldDisableUser) {
          this.disconnectFileListeners();

          AppDispatcher.dispatch({
            actionType: FilesListConstants.CURRENT_FILE_DESHARED
          });

          const messages = [
            <FormattedMessage
              id="unsharedBy"
              values={{
                username: event.username
              }}
            />
          ];

          if (event.wasFileModified) {
            messages.push(
              <FormattedMessage id="changesSavedToConflictingFile" />
            );
          }

          this.setState({
            renderElement: <ErrorMessage messages={messages} />
          });

          SnackbarUtils.alertWarning({
            id: "unsharedBy",
            username: event.username
          });
          // downgrade editor's token session
          if (!isViewOnly) {
            // downgrade on server
            const headers = {
              xSessionId: FilesListStore.getCurrentFile().drawingSessionId,
              sessionId: Storage.getItem("sessionId"),
              downgrade: true
            };
            AppDispatcher.dispatch({
              actionType: FilesListConstants.CURRENT_FILE_DESHARED
            });
            Requests.sendGenericRequest(
              `/files/${encodeURIComponent(fileId)}/session`,
              RequestsMethods.PUT,
              headers,
              undefined,
              ["*"]
            ).finally(() => {
              FilesListActions.saveFileViewFlag(true);
              FilesListActions.reloadDrawing(true);
            });
          }
        }
      } else if (
        event.socketType === FILE_SOCKET &&
        event.type === "sharedLinkOff" &&
        isTokenAccess &&
        event.linkOwnerIdentity ===
          FilesListStore.getCurrentFile().linkOwnerIdentity
      ) {
        this.disconnectFileListeners();

        const messages = [
          <FormattedMessage
            id="sharedLinkDisabled"
            values={{ strong: IntlTagValues.strong, br: IntlTagValues.br }}
          />
        ];

        if (!isViewOnly) {
          // downgrade on server
          const headers = {
            xSessionId: FilesListStore.getCurrentFile().drawingSessionId,
            sessionId: Storage.getItem("sessionId"),
            downgrade: true
          };
          Requests.sendGenericRequest(
            `/files/${encodeURIComponent(fileId)}/session`,
            RequestsMethods.PUT,
            headers,
            undefined,
            ["*"]
          ).catch(err => {
            SnackbarUtils.alertError(err.text);
          });

          const { storageType } = MainFunctions.parseObjectId(fileId);
          const storageName =
            MainFunctions.storageCodeToServiceName(storageType);

          messages.push(
            <FormattedMessage
              id="fileIsAccessibleWithStorage"
              values={{
                storageName,
                strong: IntlTagValues.strong
              }}
            />
          );
        }

        // to update file header
        AppDispatcher.dispatch({
          actionType: FilesListConstants.CURRENT_FILE_DESHARED
        });

        const elementToRender = <ErrorMessage messages={messages} />;
        this.setState({ renderElement: elementToRender });
      }
      if (isConnected === false) {
        if (lastMessage === "deleted") {
          const elementToRender = (
            <ErrorMessage
              messages={[<FormattedMessage id="fileHasBeenDeleted" />]}
            />
          );
          this.setState({ renderElement: elementToRender });
        } else if (lastMessage === "logout" && !unloadAndLogout) {
          DrawingLoader.doLogout();
          WebsocketActions.clearState(event.id);
        }
      }
    }
  };

  render() {
    const { renderElement, fileId, isExternal } = this.state;
    return (
      <main style={{ flexGrow: 1 }}>
        <ToolbarSpacer />
        {!isExternal ? <Comments fileId={fileId} /> : null}
        {renderElement}
      </main>
    );
  }
}

export default injectIntl(DrawingLoader);
