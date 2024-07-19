import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as WebsocketConstants from "../constants/WebsocketConstants";
import * as ModalConstants from "../constants/ModalConstants";
import WebsocketActions from "../actions/WebsocketActions";
import ModalActions from "../actions/ModalActions";
import UserInfoActions from "../actions/UserInfoActions";
import MainFunctions from "../libraries/MainFunctions";
import Storage from "../utils/Storage";
import Logger from "../utils/Logger";
import UserInfoStore from "./UserInfoStore";

import FilesListStore from "./FilesListStore";
import FilesListActions from "../actions/FilesListActions";
import XenonConnectionActions from "../actions/XenonConnectionActions";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";
import ApplicationActions from "../actions/ApplicationActions";
import ModalStore from "./ModalStore";

export const FILE_SOCKET = "fileSocket";
export const USER_SOCKET = "userSocket";
export const CHANGE_EVENT = "change";
export const SESSION_REQUEST_APPROVED = "SESSION_REQUEST_APPROVED";
export const NEW_THUMBNAIL = "NEW_THUMBNAIL";
export const RESOURCE_UPDATED = "RESOURCE_UPDATED";
export const RESOURCE_DELETED = "RESOURCE_DELETED";
export const STORAGE_ADD_ERROR = "STORAGE_ADD_ERROR";
export const STORAGE_DISABLED = "STORAGE_DISABLED";

const DOWNGRADE_REQUEST_FRESH_TIME = 5000;

class WebsocketStore extends EventEmitter {
  constructor() {
    super();
    this.connections = {};
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  getConnectionInfo(socketId) {
    return this.connections[socketId];
  }

  getMessage(socketId, message) {
    let messageData = {};
    if (typeof message === "object") {
      messageData = message;
    } else {
      try {
        messageData = JSON.parse(message);
      } catch (Exception) {
        throw new Error(Exception.toString());
      }
    }

    const currentConnection = this.connections[socketId];
    currentConnection.lastMessage = messageData;
    Logger.addEntry("WS", "HANDLE_MESSAGE", messageData);
    const { isConnected } = currentConnection;

    const fileId = FilesListStore.getCurrentFile()._id;

    let downgradeSessionObj =
      FilesListStore.getCurrentFile().downgradeSessionInfo;
    if (downgradeSessionObj === undefined) {
      downgradeSessionObj = {
        xSessionId: "",
        downgradeReq: false,
        updateTime: null
      };
    }
    const isViewOnly = FilesListStore.getCurrentFile().viewFlag || false;
    const isVersion = location.search.indexOf("versionId") > -1;
    const doDowngrade =
      FilesListStore.getCurrentFile().drawingSessionId ===
      messageData.xSessionId;
    const doLogout = doDowngrade;
    const thisXSession =
      FilesListStore.getCurrentFile().drawingSessionId ===
      messageData.xSessionId;
    const idDowngradeReq = downgradeSessionObj.downgradeReq;
    const isDowngradeReqFresh =
      +new Date() - +downgradeSessionObj.updateTime <
      DOWNGRADE_REQUEST_FRESH_TIME;
    const isLogoutReq = UserInfoStore.isLogoutPending();
    const currentFileToken = MainFunctions.QueryString("token");
    switch (messageData.messageType) {
      case "newVersion":
        if (isConnected === true && isVersion === false) {
          FilesListActions.setViewingLatestVersion(false);
          if (messageData.force === true) {
            if (isViewOnly) {
              ModalActions.drawingUpdated();
            } else {
              FilesListActions.reloadDrawing(false, true, false);
            }
          } else if (
            isViewOnly === true &&
            doDowngrade === false &&
            (!idDowngradeReq || (idDowngradeReq && !isDowngradeReqFresh))
          ) {
            // we don't want to show this message to an applicant with accepted session
            const lastRequestTtl = FilesListStore.getLastEditRequest();

            if (
              lastRequestTtl &&
              lastRequestTtl > new Date().getTime() / 1000
            ) {
              // check that in next 2 seconds we get sessionDowngraded message
              // if no message received then we need to show "drawingUpdated" modal
              const timeout = setTimeout(() => {
                ModalActions.drawingUpdated();
              }, 2000);

              const handleEvent = () => {
                if (timeout) clearTimeout(timeout);
                this.removeEventListener(SESSION_REQUEST_APPROVED, handleEvent);
              };

              // wait for message
              this.addEventListener(SESSION_REQUEST_APPROVED, handleEvent);
            } else {
              ModalActions.drawingUpdated();
            }
          }
        }

        this.emitEvent(CHANGE_EVENT, {
          type: "newVersion",
          id: socketId,
          author: messageData.author
        });
        break;
      case "recentsUpdated":
        FilesListActions.loadRecentFiles();
        break;
      case "newThumbnail":
        this.emitEvent(NEW_THUMBNAIL, {
          fileId: messageData.fileId,
          thumbnail: messageData.thumbnail
        });
        break;
      case "sessionRequested":
        if (
          MainFunctions.detectPageType() === "file" &&
          isViewOnly === false &&
          isConnected === true &&
          thisXSession === true
        ) {
          // request from same user but different session
          if (messageData.isMySession && messageData.isMySession === true) {
            // save this session, downgrade current with specified applicant
            FilesListActions.downgradeThisSessionForOwned(
              FilesListStore.getCurrentFile()._id,
              messageData.xSessionId,
              messageData.requestXSessionId
            );
          }
          // regular request
          else {
            SnackbarUtils.sessionRequest(
              FilesListStore.getCurrentFile()._id,
              messageData.username,
              messageData.xSessionId,
              messageData.requestXSessionId,
              messageData.ttl
            );
          }
        }
        break;
      case "sessionDenied":
        if (
          isViewOnly === true &&
          isConnected === true &&
          messageData.requestXSessionId ===
            FilesListStore.getCurrentFile().drawingSessionId
        ) {
          SnackbarUtils.error("drawingSessionDenied");
        }
        break;
      case "sessionDowngrade":
        if (isConnected === true) {
          // free edit spot for this session
          // there was fresh request on server
          if (
            isViewOnly === true &&
            messageData.mode === "edit" &&
            messageData.applicantXSession &&
            messageData.applicantXSession ===
              FilesListStore.getCurrentFile().drawingSessionId
          ) {
            if (MainFunctions.QueryString("token").length > 0) {
              ApplicationActions.changePage(
                `/file/${encodeURIComponent(fileId)}`
              );
            } else {
              // close pending UPGRADE_FILE_SESSION modal
              ModalActions.hideByType(ModalConstants.UPGRADE_FILE_SESSION);

              // reload to the latest version if needed and then switch mode
              this.emitEvent(SESSION_REQUEST_APPROVED);
              SnackbarUtils.ok("drawingSessionAllowed");
              FilesListStore.removeLastEditRequest();
              if (
                FilesListStore.getCurrentFile().viewingLatestVersion === true
              ) {
                FilesListActions.reloadDrawing(false);
              } else {
                XenonConnectionActions.postMessage({ messageName: "reopen" });
                setTimeout(() => {
                  FilesListActions.reloadDrawing(false);
                }, 2000);
              }
            }
          }
          // this session has to be downgraded
          else if (isViewOnly === false && doDowngrade === true) {
            SnackbarUtils.closeSessionRequest();
            FilesListActions.saveFileViewFlag(true);
            FilesListActions.saveEditingUserId(UserInfoStore.getUserInfo("id"));
            FilesListActions.reloadDrawing(true, false, false);
            ModalActions.downgradeDrawingSession();
          }

          // there may be free spot for edit
          FilesListActions.checkEditPermission(
            "drawingSessionForEditAvailable"
          );
        }
        /* this.emitEvent(eventType, {socketType}) */
        break;
      case "sessionDeleted":
        if (isViewOnly && isConnected && messageData.mode === "edit") {
          FilesListActions.checkEditPermission(
            "drawingSessionForEditAvailable"
          );
        } else if (isConnected && isLogoutReq && doLogout) {
          UserInfoActions.logout();
        }
        break;
      case "deleted": {
        currentConnection.lastMessage.messageType = "deleted";
        // https://graebert.atlassian.net/browse/WB-231
        if (messageData.parentDeleted) {
          const { folderId } = FilesListStore.getCurrentFile();
          const { storageType, storageId } =
            MainFunctions.parseObjectId(folderId);
          FilesListActions.saveFile({
            ...FilesListStore.getCurrentFile(),
            deleted: true,
            folderId: MainFunctions.encapsulateObjectId(
              storageType,
              storageId,
              "-1"
            )
          });
        }
        WebsocketActions.disconnect(socketId, false);
        break;
      }
      case "deshared":
        this.emitEvent(CHANGE_EVENT, {
          type: "deshared",
          socketType: this.connections[socketId].type,
          id: socketId,
          userId: messageData.userId,
          username: messageData.username,
          collaborators: messageData.collaborators,
          wasFileModified: FilesListStore.getCurrentFile().isModified || false
        });
        break;
      case "shared": {
        if (MainFunctions.detectPageType() !== "file") return;

        const { storageType } = MainFunctions.parseObjectId(fileId);

        const storageEmails =
          storageType === "SF"
            ? [UserInfoStore.getUserInfo("email")]
            : UserInfoStore.getConnectedStorageEmails(
                MainFunctions.storageCodeToServiceName(storageType)
              );

        const { collaborators, username } = messageData;
        const collaborator = collaborators.find(({ email }) =>
          storageEmails.includes(email)
        );

        if (!collaborator) return;

        if (collaborator.role === "view") {
          FilesListActions.saveInitialViewOnly(true);
          // downgrade this session and reload
          if (isViewOnly === false) {
            const { isModified: wasFileModified = false } =
              FilesListStore.getCurrentFile();
            this.emitEvent(CHANGE_EVENT, {
              type: "accessGranted",
              id: socketId
            });
            SnackbarUtils.closeSessionRequest();
            FilesListActions.saveFileViewFlag(true);
            FilesListActions.reloadDrawing(true, true, true);
            ModalActions.editPermissionSwitched(wasFileModified);
          }
        } else if (collaborator.role === "edit") {
          FilesListActions.saveInitialViewOnly(false);
          FilesListActions.checkEditPermission("editAccessWasGranted");
        }

        break;
      }
      case "objectCreated":
        this.emitEvent(CHANGE_EVENT, {
          type: "objectCreated",
          socketType: this.connections[socketId].type,
          id: socketId,
          itemId: messageData.itemId,
          parentId: messageData.parentId,
          storageType: messageData.storageType,
          isFolder: messageData.isFolder
        });
        break;
      case "sharedLinkOff":
        this.emitEvent(CHANGE_EVENT, {
          type: "sharedLinkOff",
          socketType: this.connections[socketId].type,
          id: socketId,
          linkOwnerIdentity: messageData.linkOwnerIdentity
        });
        break;
      case "logout":
        WebsocketActions.disconnect(socketId, false);
        this.emitEvent(CHANGE_EVENT, { id: socketId });
        break;
      case "commentsUpdate":
      case "markupsUpdate":
        this.emitEvent(CHANGE_EVENT, {
          type: "commentsUpdate",
          socketType: this.connections[socketId].type,
          id: socketId,
          userId: messageData.userId,
          xSessionId: messageData.xSessionId,
          author: messageData.author
        });
        break;
      case "exportStateUpdated":
        if (
          isViewOnly === true &&
          isConnected === true &&
          doDowngrade === true &&
          !idDowngradeReq &&
          currentFileToken === messageData.token
        ) {
          // show dialog only if user is viewer
          SnackbarUtils.alertInfo({ id: "exportStateUpdate" });

          XenonConnectionActions.postMessage({
            messageName: "exportStateUpdated",
            exportStateUpdate: messageData.export
          });
        }
        break;
      case "resourceUpdated":
        this.emitEvent(RESOURCE_UPDATED, { data: messageData });
        break;
      case "resourceDeleted":
        this.emitEvent(RESOURCE_DELETED, { data: messageData });
        break;
      case "accessGranted":
        // drawing changes delegated to file socket "shared" message
        break;
      case "storageAddError":
        if (
          messageData.storageType === "nextcloud" &&
          ModalStore.isDialogOpen() === true &&
          ModalStore.getCurrentInfo().currentDialog ===
            ModalConstants.NEXTCLOUD_CONNECT
        ) {
          SnackbarUtils.alertError({ id: "nextCloudIntegrationFail" });
          this.emitEvent(STORAGE_ADD_ERROR);
        }
        break;
      case "storageAdd":
        if (
          ModalStore.isDialogOpen() === true &&
          ModalStore.getCurrentInfo().currentDialog ===
            ModalConstants.NEXTCLOUD_CONNECT
        ) {
          SnackbarUtils.alertOk({ id: "nextCloudIntegrationSuccess" });
          ModalActions.hide();
        }
        UserInfoActions.getUserInfo();
        UserInfoActions.getUserStorages();
        break;
      case "storageDisabled": {
        const { storageType } = messageData;
        SnackbarUtils.alertInfo({
          id: "storageHasBeenDisabled",
          storage: MainFunctions.serviceNameToUserName(storageType)
        });
        UserInfoActions.disableStorage((storageType || "").toLowerCase());
        UserInfoActions.getUserStorages();
        this.emit(STORAGE_DISABLED, (storageType || "").toLowerCase());
        break;
      }
      case "storageEnabled": {
        const { storageType } = messageData;
        SnackbarUtils.alertInfo({
          id: "storageHasBeenEnabled",
          storage: MainFunctions.serviceNameToUserName(storageType)
        });
        UserInfoActions.getUserStorages();
        break;
      }
      case "sampleUsageUpdated": {
        const { storageType, externalId, usage, quota } = messageData;
        UserInfoActions.updateSampleUsage(
          storageType,
          externalId,
          usage,
          quota
        );
        break;
      }
      default:
        this.emitEvent(CHANGE_EVENT, { id: socketId });
        break;
    }
  }

  closeWebSocket(socketId, clearState) {
    const currentSocket = this.connections[socketId];
    if (
      currentSocket &&
      currentSocket.socket !== null &&
      currentSocket.isConnected === true
    ) {
      currentSocket.socket.close();
      // clear data if it's not cleared yet
      currentSocket.socket = null;
      currentSocket.isConnected = false;
      clearTimeout(currentSocket.keepAliveTimer);
      currentSocket.keepAliveTimer = null;
      if (clearState === true) {
        currentSocket.lastMessage = null;
      }
    }
  }

  keepSocketAlive(socketId) {
    const currentSocket = this.connections[socketId];
    const timeout = 25000; // 25 seconds
    clearTimeout(currentSocket.keepAliveTimer);
    currentSocket.keepAliveTimer = null;
    if (currentSocket && currentSocket.isConnected === true) {
      if (currentSocket.socket.readyState === WebSocket.OPEN) {
        currentSocket.socket.send(`keepAliveSocket&time=${Date.now()}`);
      }
      currentSocket.keepAliveTimer = setTimeout(() => {
        this.keepSocketAlive(socketId);
      }, timeout);
    }
  }

  handleAction(action) {
    switch (action.actionType) {
      case WebsocketConstants.INIT_CONNECTION: {
        const endURL = MainFunctions.injectIntoString(action.url, [
          Storage.store("sessionId")
        ]);
        if (
          Object.values(this.connections).find(
            connection => endURL === connection?.socket?.url
          )
        )
          break;
        const newSocketId = action.socketId;
        this.connections[newSocketId] = {
          socket: new WebSocket(endURL),
          keepAliveTimer: null,
          isConnected: false,
          lastMessage: null,
          type: action.socketType
        };
        const currentConnection = this.connections[newSocketId];
        currentConnection.socket.onopen = () => {
          currentConnection.isConnected = true;
          this.keepSocketAlive(newSocketId);
          this.emitEvent(CHANGE_EVENT, {
            type: "newSocket",
            id: newSocketId,
            socketType: currentConnection.type
          });
          Logger.addEntry("WS", `connected to ws ${action.socketType}`);
        };
        currentConnection.socket.onclose = event => {
          WebsocketActions.handleClose(newSocketId, event);
        };
        currentConnection.socket.onmessage = event => {
          WebsocketActions.handleMessage(newSocketId, event.data);
        };
        currentConnection.socket.onerror = event => {
          WebsocketActions.handleError(newSocketId, event);
        };
        break;
      }
      case WebsocketConstants.SEND_MESSAGE:
        if (
          this.connections[action.socketId].socket !== null &&
          this.connections[action.socketId].isConnected === true
        ) {
          this.connections[action.socketId].socket.send(action.message);
          this.emitEvent(CHANGE_EVENT, {
            type: "messageSent",
            id: action.socketId
          });
        }
        break;
      case WebsocketConstants.CLOSE_CONNECTION:
        this.closeWebSocket(action.socketId, action.clearState);
        this.emitEvent(CHANGE_EVENT, {
          type: "connectionClosed",
          id: action.socketId
        });
        break;
      case WebsocketConstants.HANDLE_MESSAGE:
        if (
          this.connections[action.socketId].socket !== null &&
          this.connections[action.socketId].isConnected === true
        ) {
          this.getMessage(action.socketId, action.message);
        }
        break;
      case WebsocketConstants.HANDLE_ERROR:
        this.closeWebSocket(action.socketId, false);
        Logger.addEntry("ERROR", "WS", "HANDLE_ERROR", action.error);
        this.emitEvent(CHANGE_EVENT, {
          type: "connectionClosed",
          id: action.socketId
        });
        break;
      case WebsocketConstants.HANDLE_CLOSE:
        this.closeWebSocket(action.socketId, false);
        Logger.addEntry("WS", `connection closed for ws ${action.socketId}`);
        this.emitEvent(CHANGE_EVENT, {
          type: "connectionClosed",
          id: action.socketId
        });
        break;
      case WebsocketConstants.CLEAR_STATE: {
        const currentConnectionObject = this.connections[action.socketId];
        currentConnectionObject.isConnected = false;
        currentConnectionObject.lastMessage = null;
        currentConnectionObject.socket = null;
        clearTimeout(currentConnectionObject.keepAliveTimer);
        currentConnectionObject.keepAliveTimer = null;
        break;
      }
      case WebsocketConstants.AWAIT_FOR_NEW_VERSION: {
        const timeout = setTimeout(() => {
          action.reject();
        }, 60000);

        const handleEvent = ({ type }) => {
          if (type === "newVersion") {
            setTimeout(() => {
              action.resolve();
            }, 1000);
            if (timeout) clearTimeout(timeout);
            this.removeEventListener(CHANGE_EVENT, handleEvent);
          }
        };

        // wait till save is done
        this.addEventListener(CHANGE_EVENT, handleEvent);
        break;
      }
      default:
        break;
    }
    return true;
  }

  emitEvent(eventType, eventData) {
    this.emit(eventType, eventData);
  }

  addEventListener(eventType, callback) {
    this.on(eventType, callback);
  }

  removeEventListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

WebsocketStore.dispatchToken = null;

const store = new WebsocketStore();
store.setMaxListeners(0);

export default store;
