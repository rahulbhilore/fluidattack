/* eslint-disable no-console */
import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as XenonConnectionConstants from "../constants/XenonConnectionConstants";
import XenonConnectionActions from "../actions/XenonConnectionActions";
import ModalActions from "../actions/ModalActions";

import FilesListStore from "./FilesListStore";
import FilesListActions from "../actions/FilesListActions";
import ApplicationActions from "../actions/ApplicationActions";
import applicationStore from "./ApplicationStore";
import Logger from "../utils/Logger";
import Storage from "../utils/Storage";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";
import conflictingReasons from "../constants/appConstants/ConflictingFileReasons";
import userInfoStore from "./UserInfoStore";
import { A3_IFRAME_ID } from "../components/A3";

const CHANGE_EVENT = "change";
export const SWITCH_EDITOR_MODE = "SWITCH_EDITOR_MODE";
export const STEP_PROGRESSOR = "STEP_PROGRESSOR";
export const STOP_PROGRESSOR = "STOP_PROGRESSOR";
export const DRAWING_MODIFIED = "DRAWING_MODIFIED";

const MIN_DELAY_PROGRESSOR = 100;

type DispatchedAction = {
  actionType: string;
  message?: string;
  origin?: string;
};

class XenonConnectionStore extends EventEmitter {
  listener: ((ev: MessageEvent) => void) | null;

  currentState: {
    connected: boolean;
    lastMessage: object | null;
    lastMessageStamp: number;
  };

  dispatcherIndex: string;

  constructor() {
    super();
    this.listener = null;
    this.currentState = {
      connected: false,
      lastMessage: null,
      lastMessageStamp: Date.now()
    };
    this.dispatcherIndex = AppDispatcher.register(this.handleAction.bind(this));
  }

  getCurrentState() {
    return this.currentState;
  }

  getMessage(message: string, origin: string) {
    let messageData: Record<string, string> = {};
    if (typeof message === "object") {
      messageData = message;
    } else if (message.indexOf("{") === -1) {
      // actually ignore message (e.g. setImmediate message)
    } else {
      try {
        messageData = JSON.parse(message);
      } catch (Exception) {
        // let's just ignore this for now
        return;
        // throw new Error(Exception.toString());
      }
    }
    // ignore empty messages
    if (Object.keys(messageData).length === 0) return;
    // ignore dev messages
    if (messageData.source && messageData.source.includes("devtool")) {
      return;
    }
    // DK: not sure if we need to add special handling for other cases
    if (origin.includes("aichat") && origin.includes("graebert.com")) {
      // AI chat - pass into Xenon
      // this implementation guarantees that it's sent only to Xenon
      XenonConnectionActions.postMessage(JSON.stringify(messageData));
      return;
    }
    if (
      Object.prototype.hasOwnProperty.call(messageData, "type") &&
      messageData.type?.startsWith("A3_")
    ) {
      // message from Xenon - send to A3
      (
        document.getElementById(A3_IFRAME_ID) as HTMLIFrameElement
      )?.contentWindow?.postMessage(messageData, "*");
      return;
    }
    this.currentState.lastMessage = messageData;
    Logger.addEntry(
      "XE_MESSAGE",
      Date.now() - this.currentState.lastMessageStamp,
      messageData.messageName,
      `from ${origin}`
    );
    // ignore progressors coming in fast maner
    if (
      messageData.messageName === "stepProgressor" &&
      Date.now() - this.currentState.lastMessageStamp <= MIN_DELAY_PROGRESSOR
    ) {
      return;
    }
    this.currentState.lastMessageStamp = Date.now();
    if (messageData.name === "xetest") {
      console.info("[XE_TEST_RESULT]", messageData);
      if (!window._globalXeTestLogs) {
        window._globalXeTestLogs = [];
      }
      window._globalXeTestLogs.push(messageData);
      if (!window._downloadXeTestLogs) {
        window._downloadXeTestLogs = () => {
          const dataStr = `data:text/json;charset=utf-8,${encodeURIComponent(
            JSON.stringify(window._globalXeTestLogs || [])
          )}`;
          const downloadAnchorNode = document.createElement("a");
          downloadAnchorNode.setAttribute("href", dataStr);
          downloadAnchorNode.setAttribute("download", `xelogs.json`);
          document.body.appendChild(downloadAnchorNode); // required for firefox
          downloadAnchorNode.click();
          downloadAnchorNode.remove();
        };
      }
    }
    switch (messageData.messageName) {
      case "showMessageBubble":
        if ((messageData.message || "").length > 0) {
          SnackbarUtils.alertInfo(messageData.message);
        }
        this.emitChange();
        break;
      case "connectionLost":
        ModalActions.connectionLost();
        this.emitChange();
        break;
      case "errorReload":
        SnackbarUtils.alertWarning({ id: "pageWillBeReloaded" });
        FilesListActions.reloadDrawing(false);
        this.emitChange();
        break;
      case "handleSession": {
        const msgData = JSON.parse(messageData.message);
        switch (msgData.action) {
          case "switchEditorMode":
            this.emit(SWITCH_EDITOR_MODE, msgData);
            break;
          case "saveDrawingSessionId":
            FilesListActions.saveDrawingSessionId(msgData.fileSessionId);
            break;
          case "saveEditingUserId":
            FilesListActions.saveEditingUserId(msgData.editingUserId);
            break;
          case "inactiveUser":
            ModalActions.showMessageInfo(msgData);
            break;
          default:
            break;
        }
        break;
      }
      case "saveAs": {
        const msgData = JSON.parse(messageData.message);
        const { filterArray, saveFilterIndex, commandName, caption } = msgData;
        ModalActions.saveAs(
          FilesListStore.getCurrentFile()._id,
          filterArray,
          saveFilterIndex < 0 ? 0 : saveFilterIndex,
          commandName,
          caption
        );
        break;
      }
      case "chooseFolder": {
        ModalActions.chooseFolder();
        break;
      }
      case "exportFile":
      case "exportFileDwgCompare":
      case "exportLayerState": {
        const msgData = JSON.parse(messageData.message);
        const { filterArray, saveFilterIndex } = msgData;
        const { messageName } = messageData;
        ModalActions.exportFile(
          FilesListStore.getCurrentFile()._id,
          filterArray,
          saveFilterIndex,
          messageName
        );
        break;
      }
      case "chooseBlockLibrary": {
        ModalActions.chooseBlockLibrary();
        break;
      }
      case "showChooseDialog":
        try {
          const msgData = JSON.parse(messageData.message);
          const { commandType, attachmentType, filter, wtObjectId } = msgData;
          if (commandType === "import" && !wtObjectId) {
            SnackbarUtils.alertError({ id: "internalError" });
            return;
          }
          ModalActions.showChooseDialog(
            commandType,
            attachmentType,
            filter,
            wtObjectId
          );
          this.emitChange();
        } catch (error) {
          SnackbarUtils.alertError({ id: "internalError" });
        }
        break;
      case "openXRefFileInNewTab": {
        let referenceType = "dwg";
        let isHandled = false;
        try {
          referenceType =
            JSON.parse(messageData.message)?.message?.referenceType || "dwg";
          const objectId = JSON.parse(messageData.message)?.message?.strFileId;
          const targetApp = userInfoStore.findApp(referenceType);
          if (targetApp === "pdf") {
            // TODO: have some proper opening function for pdf
            const oReq = new XMLHttpRequest();
            oReq.open(
              "GET",
              `${applicationStore.getApplicationSetting(
                "apiURL"
              )}/files/${objectId}/data`,
              true
            );
            oReq.setRequestHeader(
              "sessionId",
              (Storage.getItem("sessionId") as string) || ""
            );
            oReq.setRequestHeader("open", "true");
            oReq.responseType = "arraybuffer";
            oReq.onload = () => {
              if (oReq.status !== 200) {
                try {
                  SnackbarUtils.error(
                    String.fromCharCode.apply(
                      null,
                      new Uint8Array(oReq.response)
                    )
                  );
                } catch (Exception) {
                  SnackbarUtils.alertError({
                    id: "unsuccessfulDownload",
                    name: "unknown.pdf",
                    type: "file"
                  });
                }
              } else {
                const blob = new Blob([oReq.response], {
                  type: "application/pdf"
                });
                // noinspection JSUnresolvedVariable
                const fileURL = URL.createObjectURL(blob);
                window.open(fileURL, "_blank", "noopener,noreferrer");
                URL.revokeObjectURL(fileURL);
              }
            };
            oReq.send();
            isHandled = true;
          } else if (targetApp === "images" || referenceType === "img") {
            isHandled = true;
            FilesListActions.getObjectInfo(objectId, "file", {})
              .then(info => {
                const { filename } = info;
                ModalActions.showImage(filename, objectId);
              })
              .catch(err => {
                ModalActions.showImage("unknown", objectId);
              });
          } else {
            isHandled = true;
            window.open(`/file/${objectId}`, "_blank", "noopener,noreferrer");
          }
          break;
        } catch (error) {
          // in case of error - we'll try to handle it as dwg
          isHandled = false;
        }
        if (!isHandled) {
          window.open(
            `${location.origin}/file/${messageData.message}`,
            "_blank",
            "noopener,noreferrer"
          );
        }
        break;
      }
      case "glTestResults":
        this.emitChange();
        break;
      case "returnToFiles": {
        const UIPrefix = applicationStore.getApplicationSetting("UIPrefix");
        ApplicationActions.changePage(`${UIPrefix}files/-1`);
        break;
      }
      case "openSaveAs": {
        if ((messageData.message || "").length > 0) {
          const fileId = messageData.message;
          window.open(
            `${location.origin}/file/${fileId}`,
            "_blank",
            "noopener,noreferrer"
          );
        }
        break;
      }
      case "stepProgressor": {
        let percentage = parseFloat(messageData.messageParam || "0");
        if (percentage > 99) {
          percentage = 99;
        }
        this.emit(STEP_PROGRESSOR, percentage.toFixed(0));
        break;
      }
      case "stopProgressor":
        this.emit(STOP_PROGRESSOR);
        break;
      case "drawingModifiedEvent":
        this.emit(DRAWING_MODIFIED);
        break;
      case "conflictingFileCreated": {
        const msgData = JSON.parse(messageData.message);
        const { conflictingFileId, fileName, conflictingFileReason } = msgData;
        const { name } = FilesListStore.getCurrentFile();
        ModalActions.openConflictingFile(name, conflictingFileId, fileName);
        if (conflictingFileReason !== conflictingReasons.UNSHARED_OR_DELETED) {
          FilesListActions.reloadDrawing(true);
        }
        FilesListActions.setConflictingReason(conflictingFileReason);
        break;
      }
      case "BLOCKS_PALETTE_OPEN_MANAGEMENT_UI": {
        window.open("/resources/blocks", "_blank", "noopener,noreferrer");
        break;
      }
      case "BLOCKS_PALETTE_OPEN_HELP": {
        window.open(
          "/help/index.htm#t=blocklib%2Fhlpid_block_blocklibrary.htm",
          "_blank",
          "noopener,noreferrer"
        );
        break;
      }
      case "shareWithNewUsers": {
        const msgData = JSON.parse(messageData.message);
        const { fileName, fileId } = msgData;
        ModalActions.shareManagement(fileId, fileName);
        break;
      }
      default:
        this.emitChange();
        break;
    }
  }

  sendMessage(message: string) {
    const frame = document.getElementById("draw") as HTMLIFrameElement | null;
    if (frame) {
      const innerDocument = frame.contentWindow;
      if (innerDocument) {
        if (typeof innerDocument.postMessage === "function") {
          innerDocument.postMessage(
            message,
            Storage.getItem("currentURL") || ""
          );
        } else {
          console.error(
            "XENON CONNECTION",
            "POST_MESSAGE",
            "postMessage is not a function"
          );
        }
      } else {
        console.error(
          "XENON CONNECTION",
          "POST_MESSAGE",
          "no inner document in frame"
        );
      }
    } else {
      console.error("XENON CONNECTION", "POST_MESSAGE", "no frame on page");
    }
    this.emitChange();
  }

  handleAction(action: DispatchedAction) {
    switch (action.actionType) {
      case XenonConnectionConstants.XE_INIT_CONNECTION:
        if (!this.listener) {
          this.listener = event => {
            XenonConnectionActions.onMessage(event.data, event.origin);
          };
          window.addEventListener("message", this.listener);
        }
        this.currentState.connected = true;
        break;
      case XenonConnectionConstants.XE_POST_MESSAGE:
        if (action.message) this.sendMessage(action.message);
        break;
      case XenonConnectionConstants.XE_CLOSE_CONNECTION:
        if (this.listener) {
          window.removeEventListener("message", this.listener);
          this.listener = null;
          this.currentState.connected = false;
          this.emitChange();
        }
        break;
      case XenonConnectionConstants.XE_GET_MESSAGE:
        if (action.message && action.origin)
          this.getMessage(action.message, action.origin);
        break;
      default:
        break;
    }
    return true;
  }

  emitChange() {
    this.emit(CHANGE_EVENT);
  }

  addChangeListener(callback: () => void) {
    this.on(CHANGE_EVENT, callback);
  }

  removeChangeListener(callback: () => void) {
    this.removeListener(CHANGE_EVENT, callback);
  }

  addEventListener(eventType: string, callback: () => void) {
    this.on(eventType, callback);
  }

  removeEventListener(eventType: string, callback: () => void) {
    this.removeListener(eventType, callback);
  }
}

export default new XenonConnectionStore();
