import { EventEmitter } from "events";
import { browserHistory } from "react-router";
import _ from "underscore";
import mime from "mime";
import assign from "object-assign";
import { sortByType } from "../utils/FileSort";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import AppDispatcher from "../dispatcher/AppDispatcher";
import UserInfoActions from "../actions/UserInfoActions";
import ApplicationActions from "../actions/ApplicationActions";
import ApplicationStore from "./ApplicationStore";
import MainFunctions from "../libraries/MainFunctions.ts";
import UserInfoStore from "./UserInfoStore";
import Storage from "../utils/Storage";
import newRequests from "../utils/Requests";
import ModalActions from "../actions/ModalActions";
import * as FilesListConstants from "../constants/FilesListConstants";
import SmartTableStore from "./SmartTableStore";
import FilesListActions from "../actions/FilesListActions";
import Logger from "../utils/Logger";
import Timer from "../utils/Timer";
import UploadError from "../components/Toolbar/UploadError";
import ProcessActions from "../actions/ProcessActions";
import Processes from "../constants/appConstants/Processes";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";
import XenonConnectionActions from "../actions/XenonConnectionActions";
import WebsocketActions from "../actions/WebsocketActions";
import ProcessStore from "./ProcessStore";
import * as IntlTagValues from "../constants/appConstants/IntlTagValues";
import FetchAbortsControl from "../utils/FetchAbortsControl";

let db = null;

function initializeDB(openDB) {
  return openDB("ARES_Kudo_master", 1, {
    upgrade(newDb) {
      newDb.createObjectStore("objects", {
        keyPath: "fullId"
      });
      newDb.createObjectStore("content", {
        keyPath: "fullId"
      });
    }
  });
}

const shouldUseIndexedDB =
  window.indexedDB && !navigator.userAgent.toLowerCase().includes("commander");

if (shouldUseIndexedDB) {
  import("idb").then(({ openDB }) => {
    db = initializeDB(openDB);
  });
}

const CHANGE_EVENT = "change";
const XREF_OPEN_EVENT = "xrefOpen";
const XREF_CHANGE_FOLDER_EVENT = "xrefChangeFolder";
export const DRAWING_RELOAD = "DRAWING_RELOAD";
const RECENT_FILES_LOAD = "RECENT_FILES_LOAD";
const SEARCH = "SEARCH";
export const PATH_LOADED = "PATH_LOADED";
export const MODE_UPDATED = "MODE_UPDATED";
export const CONTENT_LOADED = "CONTENT_LOADED";
export const CONTENT_UPDATED = "CONTENT_UPDATED";
export const CHECK_NEW_FILES_PAGE = "CHECK_NEW_FILES_PAGE";
export const SORT_REQUIRED = "SORT_REQUIRED";
export const ENTITY_RENAME_MODE = "ENTITY_RENAME_MODE";
export const CURRENT_FOLDER_INFO_UPDATED = "CURRENT_FOLDER_INFO_UPDATED";
export const CURRENT_FILE_INFO_UPDATED = "CURRENT_FILE_INFO_UPDATED";
export const CURRENT_FILE_DESHARED = "CURRENT_FILE_DESHARED";
export const STORAGE_RECONNECT_REQUIRED = "STORAGE_RECONNECT_REQUIRED";
export const UPLOAD_FINISHED = "UPLOAD_FINISHED";
export const BROWSER = "browser";
export const TRASH = "trash";

let currentFolder = {
  _id: "-1",
  name: "~",
  storage: null,
  accountId: null,
  mode: BROWSER,
  viewOnly: false,
  full: true
};
let currentTrashFolder = {
  _id: "-1",
  name: "~",
  viewOnly: false
};
let currentFile = {};
let folderPath = [currentFolder];
let trashFolderPath = [currentTrashFolder];
let files = [];
let parents = [];
let mode = BROWSER;
let recentFiles = [];
let searchResults = {};
let lastRemovedRecentData = {};
let searchQuery = null;
const filesTree = {};
const cacheInvalidateTime = 6 * 60 * 60 * 1000; // 6 hours
const tokens = {};
const uploads = {};
const MAX_SINGLE_FILE_UPLOAD_SIZE = 150 * 1024 * 1024; // WB-60

function getFullObjectId(pseudoId, storageType, storageId, storageCode) {
  if (
    pseudoId.indexOf("+") === -1 ||
    pseudoId.indexOf("+") === pseudoId.lastIndexOf("+")
  ) {
    Logger.addEntry(
      "WARNING",
      `Supplied non-encapsulated id to FilesListStore:${pseudoId}`
    );
    // temporary solution!
    if (storageCode && storageCode.length <= 3) {
      return `${storageCode}+${storageId}+${pseudoId}`;
    }
    if (storageType.length > 0 && storageId.length > 0) {
      return `${MainFunctions.serviceNameToStorageCode(
        storageType
      )}+${storageId}+${pseudoId}`;
    }
    Logger.addEntry("ERROR", `Cannot encapsulate id ${pseudoId}!`);
    return pseudoId;
  }
  return pseudoId;
}

// TODO: check necessity
// TODO: seems it`s can be deleted ++
function saveFiles(results) {
  files = results;
  _.each(results, elem => {
    parents.push(elem.parent);
  });
}

const FilesListStore = assign({}, EventEmitter.prototype, {
  /**
   * @public
   * @param storageType
   * @param storageId
   */
  prepareOpen(storageType, storageId) {
    return new Promise(resolve => {
      const activeStorageInfo = UserInfoStore.getUserInfo("storage");
      const isSwitchRequired =
        storageType !== activeStorageInfo.type ||
        (storageId !== activeStorageInfo.id &&
          activeStorageInfo.type !== "internal");
      if (
        storageType &&
        isSwitchRequired &&
        (storageId || storageType.toLowerCase() === "internal")
      ) {
        const storagesInfo = UserInfoStore.getStoragesInfo();

        if (_.isEmpty(storagesInfo)) {
          resolve();
          return;
        }

        currentFolder.wsId = "preparing";

        if (Object.prototype.hasOwnProperty.call(storagesInfo, storageType)) {
          if (storageType.toLowerCase() === "internal") {
            // Hack for Fluorine
            // For some reason there is fluorine_id in storages info
            // (GET /integration/accounts)
            // But accountId isn't in fileIds ???
            UserInfoActions.switchToStorage(storageType, storageId, {}, () => {
              resolve();
            });
          } else if (
            _.find(
              storagesInfo[storageType],
              storageObject => storageObject[`${storageType}_id`] === storageId
            ) !== undefined
          ) {
            UserInfoActions.switchToStorage(storageType, storageId, {}, () => {
              resolve();
            });
          } else {
            UserInfoActions.connectStorage(storageType);
            resolve();
          }
        } else {
          UserInfoActions.connectStorage(storageType);
          resolve();
        }
      } else resolve();
    });
  },

  /**
   * @public
   * Handles click on name field in table
   */
  open(id, storageType, storageId, forceFileOpen, entity) {
    return new Promise((resolve, reject) => {
      // Just open forced file
      if (forceFileOpen) {
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting(
            "UIPrefix"
          )}file/${getFullObjectId(id, storageType || "", storageId || "")}`
        );

        this.prepareOpen(storageType, storageId);

        resolve();
        return;
      }

      // entity not found
      if (!entity) {
        const err = new Error(`Entity with id ${id} not found in table`);
        Logger.addEntry("ERROR", `${err.toString()} ${err.stack}`);
        reject();
        return;
      }

      const {
        isShortcut,
        type: entityType,
        name: entityName,
        mimeType: entityMimeType
      } = entity;

      let shortcutType = "";
      let shortcutTargetId = "";
      let shortcutMimeType = "";
      if (isShortcut) {
        ({
          shortcutInfo: {
            type: shortcutType,
            targetId: shortcutTargetId,
            mimeType: shortcutMimeType
          }
        } = entity);
      }

      const type = isShortcut ? shortcutType : entityType;
      const extensionString = isShortcut ? shortcutMimeType : entityName;
      const fullOpenId = getFullObjectId(
        isShortcut ? shortcutTargetId : id,
        storageType || "",
        storageId || ""
      );

      const app = UserInfoStore.findApp(
        type === "folder"
          ? "folder"
          : MainFunctions.getExtensionFromName(extensionString),
        entityMimeType
      );

      // target is folder
      if (type === "folder") {
        const {
          storageType: parsedStorageType,
          storageId: parsedStorageId,
          objectId: parsedObjectId
        } = MainFunctions.parseObjectId(fullOpenId);

        // update currentFolder with default data or stored one if present
        const folderToOpen = files.find(
          object =>
            object._id === fullOpenId && object.parent === currentFolder._id
        );
        if (folderToOpen) {
          this.updateCurrentFolder(folderToOpen);
        } else {
          this.setCurrentFolder({
            storage: parsedStorageType || storageType,
            account: parsedStorageId || storageId,
            objectId: parsedObjectId,
            mode: currentFolder.mode
          });
        }

        if (isShortcut) {
          // full path reload, shortcut may have target in completely different place
          FilesListActions.loadPath(parsedObjectId, "folder");
        } else {
          // just update path
          FilesListActions.updatePath(parsedObjectId, entity.name);
        }

        ApplicationActions.changePage(
          `/files/${parsedStorageType || storageType}/${
            parsedStorageId || storageId
          }/${parsedObjectId}`
        );
        resolve();
      }

      // target should be opened in xenon
      else if (app === "xenon") {
        if (FilesListStore.getCurrentState() !== TRASH) {
          ApplicationActions.changePage(
            `${ApplicationStore.getApplicationSetting(
              "UIPrefix"
            )}file/${fullOpenId}`
          );
          resolve();
        }
      }

      // target is image
      else if (app === "images") {
        ModalActions.showImage(entity.name, fullOpenId);
        resolve();
      }

      // target is pdf
      else if (app === "pdf") {
        ProcessActions.start(fullOpenId, Processes.OPENING);
        const oReq = new XMLHttpRequest();
        oReq.open(
          "GET",
          `${window.ARESKudoConfigObject.api}/files/${fullOpenId}/data`,
          true
        );
        oReq.setRequestHeader("sessionId", Storage.store("sessionId"));

        oReq.setRequestHeader("open", "true");
        oReq.responseType = "arraybuffer";
        oReq.onload = () => {
          ProcessActions.end(fullOpenId);
          if (oReq.status !== 200) {
            try {
              SnackbarUtils.error(
                String.fromCharCode.apply(null, new Uint8Array(oReq.response))
              );
              reject();
            } catch (Exception) {
              const { _id: currentFolderId } = currentFolder;
              const { objectId } = MainFunctions.parseObjectId(currentFolderId);

              const currentElement = filesTree[storageType][storageId][mode][
                objectId
              ].content.find(
                elem =>
                  elem.id ===
                  SmartTableStore.getSelectedRowsByTableType("files")[0]
              );
              SnackbarUtils.alertError({
                id: "unsuccessfulDownload",
                name: currentElement.name,
                type: "file"
              });
              reject();
            }
          } else {
            const blob = new Blob([oReq.response], {
              type: "application/pdf"
            });
            // noinspection JSUnresolvedVariable
            const fileURL = URL.createObjectURL(blob);
            window.open(fileURL, "_blank", "noopener,noreferrer");
            FilesListActions.loadRecentFiles();
            URL.revokeObjectURL(fileURL);
            resolve();
          }
        };
        oReq.send();
      }
    });
  },

  /**
   * @public
   * Returns info about current storage
   * @returns Storage code
   */
  findCurrentStorage(objectId) {
    function prepareReturn(storageType, storageId) {
      // DK: I don't think we should check this - it regresses public links

      // const storageName = MainFunctions.storageCodeToServiceName(storageType);

      // const storagesInfo = UserInfoStore.getStoragesInfo();
      // const currentStorages = storagesInfo?.[storageName];
      // if (!currentStorages) return null;

      // const confirmedStorage = currentStorages.find(
      //   elem => elem[`${storageName}_id`] === storageId
      // );
      // if (!confirmedStorage) return null;

      return {
        storageType,
        storageId
      };
    }
    if (objectId && objectId.length > 0) {
      const { storageType, storageId } = MainFunctions.parseObjectId(objectId);
      if (storageType && storageType.length > 0) {
        return prepareReturn(storageType, storageId);
      }
    }

    if (currentFile?.id || currentFile?._id) {
      const { storageType, storageId } = MainFunctions.parseObjectId(
        currentFile.id || currentFile._id
      );
      if (storageType && storageType.length > 0) {
        return prepareReturn(storageType, storageId);
      }
    }
    if (currentFolder?.accountId && currentFolder?.storage) {
      if (
        currentFolder?.accountId.length > 0 &&
        currentFolder?.storage.length > 0
      ) {
        return prepareReturn(currentFolder.storage, currentFolder.accountId);
      }
    }
    if (currentFolder?.id || currentFolder?._id) {
      const { storageType, storageId } = MainFunctions.parseObjectId(
        currentFolder.id || currentFolder._id
      );
      if (storageType && storageType.length > 0) {
        return prepareReturn(storageType, storageId);
      }
    }
    const userStorageInfo = UserInfoStore.getUserInfo("storage");
    const storage = userStorageInfo.type;
    const accountId = userStorageInfo.id;

    return prepareReturn(
      MainFunctions.serviceNameToStorageCode(storage),
      accountId
    );
  },

  /**
   * @public
   * Handles click on name field in table
   */
  openXrefFolder(id) {
    FilesListStore.emitXrefOpen(id);
  },

  /**
   * @public
   * @param _mode
   */
  setMode(_mode) {
    mode = _mode;
  },

  /**
   * @public
   * @param storage
   * @param account
   * @param objectId
   * @param newMode
   */
  setCurrentFolder({ storage, account, objectId, mode: newMode }) {
    currentFolder = {
      _id: objectId,
      name: null,
      storage,
      accountId: account,
      mode: newMode,
      viewOnly: false,
      full: true
    };
    if (newMode) {
      this.setMode(newMode);
    }
  },

  /**
   * @public
   * @param isSoftReset
   */
  resetStore(isSoftReset) {
    if (!isSoftReset) {
      mode = BROWSER;
      currentFile = "";
    }
    currentFolder = {
      _id: "-1",
      name: "~",
      storage: null,
      accountId: null,
      mode: BROWSER,
      viewOnly: false,
      full: true
    };
    currentTrashFolder = {
      _id: "-1",
      name: "~",
      viewOnly: false
    };
    folderPath = [currentFolder];
    trashFolderPath = [currentTrashFolder];
    files = [];
    parents = [];
  },

  /**
   * Check if target folder is the parent of selected
   * @public
   * @param target {String} - object to check
   * @returns {boolean}
   */
  isParentOfSelected(target) {
    const selectedRowId =
      SmartTableStore.getSelectedRowsByTableType("files")[0];

    if (!selectedRowId) return false;

    const { _id: currentFolderId } = currentFolder;
    const { storageId, storageType } = FilesListStore.findCurrentStorage();
    const { objectId } = MainFunctions.parseObjectId(currentFolderId);

    const currentElement = filesTree[storageType][storageId][mode][
      objectId
    ].content.find(elem => elem.id === selectedRowId);

    return !!_.findWhere(currentElement, { parent: target });
  },

  getObjectInfoInCurrentFolder(targetObjectId) {
    const { _id: currentFolderId } = currentFolder;
    const { storageId, storageType } = FilesListStore.findCurrentStorage();
    const { objectId } = MainFunctions.parseObjectId(currentFolderId);
    return filesTree[storageType][storageId][mode][objectId].content.find(
      elem => elem.id === targetObjectId
    );
  },

  /**
   * Check if target folder has entity with name
   * @public
   * @param folderId
   * @param name
   * @param type {"file"|"folder"}
   * @returns {boolean}
   */
  isInFolder(folderId, name, type) {
    const { storageId, storageType, objectId } =
      MainFunctions.parseObjectId(folderId);

    return !!filesTree[storageType][storageId][mode][objectId].content.find(
      elem =>
        elem.name.toLowerCase() === name.toLowerCase() && elem.type === type
    );
  },

  /**
   * @public
   * @returns {boolean}
   */
  isAnyShared() {
    const selectedElements =
      SmartTableStore.getSelectedRowsByTableType("files");

    return _.any(
      files,
      fileElement =>
        !!(
          selectedElements.find(elem => elem.id === fileElement.id) &&
          !fileElement.isOwner
        )
    );
  },

  /**
   * @public
   * @param fileInfo
   * @param preserveXenonConstants
   */
  saveCurrentFile(fileInfo, preserveXenonConstants) {
    window._KUDOcurrentFileId = fileInfo._id || fileInfo.id;
    if (preserveXenonConstants) {
      const {
        drawingSessionId,
        downgradeSessionInfo,
        editingUserId,
        viewFlag,
        linkOwnerIdentity,
        initialViewOnly,
        viewingLatestVersion,
        lastEditRequest,
        hasDirectAccess = false
      } = currentFile;
      const isExport = currentFile.export;
      currentFile = _.extend(fileInfo, {
        name: fileInfo.filename,
        drawingSessionId,
        downgradeSessionInfo,
        editingUserId,
        viewFlag,
        isModified: false,
        isExport,
        ignoreSettingModifyFlag: false,
        linkOwnerIdentity: linkOwnerIdentity || fileInfo.linkOwnerIdentity,
        initialViewOnly: fileInfo.viewOnly && initialViewOnly,
        viewingLatestVersion,
        lastEditRequest,
        hasDirectAccess
      });
    } else {
      currentFile = _.extend(fileInfo, {
        name: fileInfo.filename,
        isModified: false,
        ignoreSettingModifyFlag: false,
        linkOwnerIdentity: fileInfo.linkOwnerIdentity,
        initialViewOnly: fileInfo.viewOnly,
        viewingLatestVersion: true,
        lastEditRequest: fileInfo.lastEditRequest,
        hasDirectAccess: fileInfo.hasDirectAccess || false
      });
    }
  },

  /**
   * @public
   */
  resetCurrentFileInfo() {
    currentFile = {
      id: null,
      name: "",
      viewOnly: false,
      isModified: false,
      ignoreSettingModifyFlag: false,
      deshared: false,
      linkOwnerIdentity: null,
      initialViewOnly: false,
      viewingLatestVersion: true,
      lastEditRequest: undefined
    };
  },

  /**
   * @public
   * @param viewFlag
   */
  setCurrentFileViewFlag(viewFlag) {
    currentFile.viewFlag = viewFlag;
  },

  /**
   * @public
   * @param isModified
   */
  setCurrentFileIsModified(isModified = true) {
    if (isModified && currentFile.ignoreSettingModifyFlag) {
      currentFile.ignoreSettingModifyFlag = false;
      return;
    }

    currentFile.isModified = isModified;
  },

  setLastEditRequest(ttl) {
    currentFile.lastEditRequest = ttl;
  },

  removeLastEditRequest() {
    currentFile.lastEditRequest = undefined;
  },

  getLastEditRequest() {
    return currentFile.lastEditRequest;
  },

  setCurrentFileDeshared() {
    currentFile.deshared = true;
  },

  ignoreSettingModifiedFlagOneTime() {
    currentFile.ignoreSettingModifyFlag = true;
  },

  /**
   * folderId - id of current folder
   * folderName - it's name
   * @returns {{_id: string, name: string, viewOnly: boolean}}
   */
  getCurrentFolder() {
    return currentFolder;
  },

  /**
   * @public
   * @returns {{viewOnly: boolean, name: string, _id: string}}
   */
  getCurrentTrashFolder() {
    return currentTrashFolder;
  },

  /**
   * @public
   * @returns {string}
   */
  getCurrentState() {
    return mode;
  },

  /**
   * id,name,viewOnly
   * @returns {object}
   */
  getCurrentFile() {
    return currentFile;
  },

  /**
   * @public
   * @returns {[{mode: string, accountId: null, viewOnly: boolean, name: string, _id: string, storage: null, full: boolean}]}
   */
  getFolderPath() {
    return folderPath;
  },

  /**
   * @public
   * @returns {[{viewOnly: boolean, name: string, _id: string}]}
   */
  getTrashFolderPath() {
    return trashFolderPath;
  },

  /**
   * @public
   * @param id
   */
  saveDrawingSessionId(id) {
    currentFile.drawingSessionId = id;
  },

  /**
   * @public
   * @param sessionInfo
   */
  saveDowngradeSessionInfo(sessionInfo) {
    currentFile.downgradeSessionInfo = sessionInfo;
  },

  /**
   * @public
   * @param userId
   */
  saveEditingUserId(userId) {
    currentFile.editingUserId = userId;
  },

  /**
   * @public
   * @param recentData
   */
  saveLastRemovedRecentData(recentData) {
    lastRemovedRecentData = recentData;
  },

  /**
   * @public
   * @param loadedRecentFiles
   */
  saveRecentFiles(loadedRecentFiles) {
    recentFiles = loadedRecentFiles.filter(
      recentFile =>
        (recentFile.filename || "").length > 0 &&
        (recentFile.fileId || "").length > 0
    );
  },

  /**
   * @public
   * @param fileIdToRemove
   */
  removeRecentFile(fileIdToRemove) {
    recentFiles = recentFiles.filter(({ fileId }) => fileId !== fileIdToRemove);
  },

  /**
   * @public
   * @param storage
   * @param loadedSearchResults
   */
  saveSearchResults(storage, loadedSearchResults) {
    searchResults[storage] = loadedSearchResults;
  },

  /**
   * @public
   */
  clearSearchResults() {
    searchResults = {};
  },

  /**
   * @public
   * @returns {{}}
   */
  getSearchResults() {
    return searchResults;
  },

  /**
   * @public
   * @returns {[]}
   */
  getRecentFiles() {
    return recentFiles;
  },

  /**
   * @public
   * @param fileData
   * @returns {Promise<unknown>}
   */
  formatFile(fileData) {
    return new Promise(resolve => {
      resolve(
        _.extend(fileData, {
          id: fileData._id,
          name: fileData.filename || fileData.name || "",
          mimeType: fileData.type,
          type: "file",
          full: true,
          parent: fileData.folderId,
          actions: { edit: { name: false } }
        })
      );
    });
  },

  /**
   * @public
   * @param folderData
   * @returns {Promise<unknown>}
   */
  formatFolder(folderData) {
    return new Promise(resolve => {
      resolve(
        _.extend(folderData, {
          id: folderData._id,
          name: folderData.name || "",
          externalType: folderData.externalType || folderData.type || "folder",
          type: "folder",
          full: true,
          actions: { edit: { name: false } }
        })
      );
    });
  },

  /**
   * @public
   * @param filesRawData
   * @param groupByParent
   * @returns {Promise<unknown>}
   */
  formatFiles(filesRawData, groupByParent) {
    return new Promise(resolve => {
      filesRawData
        .reduce(
          (file, fileObject) =>
            file.then(results =>
              FilesListStore.formatFile(fileObject).then(result =>
                results.concat(result)
              )
            ),
          Promise.resolve([])
        )
        .then(finalResults => {
          if (groupByParent === true) {
            resolve(_.groupBy(finalResults, "parent"));
          } else {
            resolve(finalResults);
          }
        });
    });
  },

  /**
   * @public
   * @param foldersRawData
   * @param groupByParent
   * @returns {Promise<unknown>}
   */
  formatFolders(foldersRawData, groupByParent) {
    return new Promise(resolve => {
      foldersRawData
        .reduce(
          (folder, folderObject) =>
            folder.then(results =>
              FilesListStore.formatFolder(folderObject).then(result =>
                results.concat(result)
              )
            ),
          Promise.resolve([])
        )
        .then(finalResults => {
          if (groupByParent === true) {
            resolve(_.groupBy(finalResults, "parent"));
          } else {
            resolve(finalResults);
          }
        });
    });
  },

  /**
   * @public
   * @param name
   * @param parentId
   * @param body
   * @param currentId
   * @returns {Promise<unknown>}
   */
  createFileInCurrentDirectory(name, parentId, body, currentId) {
    uploads[currentId] = {
      ids: {
        folder: [],
        file: []
      },
      uploads: [],
      isDirectory: false,
      isCanceled: false
    };
    ProcessActions.start(currentId, Processes.PREPARING);
    FilesListStore.emit(SORT_REQUIRED, currentId, mode);
    // eslint-disable-next-line no-use-before-define
    return createFile(
      null,
      currentId,
      name,
      parentId,
      body,
      currentId,
      currentFolder._id,
      true
    );
  },

  /**
   * @private
   * @param eventType {string}
   */
  emitEvent(eventType, payLoad) {
    this.emit(eventType, payLoad);
  },

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  addEventListener(eventType, callback) {
    this.on(eventType, callback);
  },

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeEventListener(eventType, callback) {
    this.removeListener(eventType, callback);
  },

  /**
   * @public
   */
  emitChange() {
    this.emit(CHANGE_EVENT);
  },

  /**
   * @param {function} callback
   */
  addChangeListener(callback) {
    this.on(CHANGE_EVENT, callback);
  },

  /**
   * @param {function} callback
   */
  removeChangeListener(callback) {
    this.removeListener(CHANGE_EVENT, callback);
  },

  /**
   * @public
   * @param folderId
   */
  emitXrefOpen(folderId) {
    this.emit(XREF_OPEN_EVENT, folderId);
  },

  /**
   * @public
   * @param {function} callback
   */
  addXrefOpenListener(callback) {
    this.on(XREF_OPEN_EVENT, callback);
  },

  /**
   * @public
   * @param {function} callback
   */
  removeXrefOpenListener(callback) {
    this.removeListener(XREF_OPEN_EVENT, callback);
  },

  /**
   * @public
   * @param name
   * @param id
   * @param viewOnly
   */
  emitXrefChangeFolder(name, id, viewOnly) {
    this.emit(XREF_CHANGE_FOLDER_EVENT, name, id, viewOnly);
  },

  /**
   * @public
   * @param {function} callback
   */
  addXrefChangeFolderListener(callback) {
    this.on(XREF_CHANGE_FOLDER_EVENT, callback);
  },

  /**
   * @public
   * @param {function} callback
   */
  removeXrefChangeFolderListener(callback) {
    this.removeListener(XREF_CHANGE_FOLDER_EVENT, callback);
  },

  /**
   * @public
   * @param folderInfo
   */
  updateCurrentFolder(folderInfo) {
    if (mode === TRASH) {
      currentTrashFolder = _.extend(currentTrashFolder, folderInfo, {
        full: true
      });
    } else {
      currentFolder = _.extend(currentFolder, folderInfo, { full: true });
    }
  },

  /**
   * @param objectInfo
   * @returns {Promise<void|*>}
   */
  async cacheObjectInfo(objectInfo) {
    if (shouldUseIndexedDB) {
      return (await db).put("objects", objectInfo);
    }
    return Promise.resolve();
  },

  /**
   * @param objectId
   * @returns {Promise<void|*>}
   */
  async retrieveObjectInfo(objectId) {
    if (shouldUseIndexedDB) {
      return (await db).get("objects", objectId);
    }
    return Promise.resolve();
  },

  /**
   * @param content
   * @returns {Promise<void|*>}
   */
  async cacheFolderContent(content) {
    if (shouldUseIndexedDB) {
      return (await db).put("content", content);
    }
    return Promise.resolve();
  },

  /**
   * @param content
   * @returns {Promise<void|*>}
   */
  async retrieveFolderContent(content) {
    if (shouldUseIndexedDB) {
      return (await db).get("content", content);
    }
    return Promise.resolve();
  },

  parseShortcutData(shortcutInfo, storage, accountId, updateHash) {
    return _.extend(shortcutInfo, {
      fullId: getFullObjectId(shortcutInfo._id, "", accountId, storage),
      storage,
      accountId,
      id: shortcutInfo._id,
      type: "file",
      isShortcut: true,
      parent: shortcutInfo.parentId,
      full: true,
      updateHash,
      actions: { edit: { name: false } }
    });
  },

  parseFileData(fileInfo, storage, accountId, updateHash) {
    return _.extend(fileInfo, {
      fullId: getFullObjectId(fileInfo._id, "", accountId, storage),
      storage,
      accountId,
      id: fileInfo._id,
      name: fileInfo.filename,
      mimeType: fileInfo.type,
      type: "file",
      isShortcut: false,
      externalType: fileInfo.type || "file",
      parent: fileInfo.folderId,
      full: true,
      updateHash,
      actions: { edit: { name: false } }
    });
  },

  parseFolderData(folderInfo, storage, accountId, updateHash) {
    return _.extend(folderInfo, {
      fullId: getFullObjectId(folderInfo._id, "", accountId, storage),
      storage,
      accountId,
      id: folderInfo._id,
      type: "folder",
      externalType: folderInfo.type || "folder",
      full: true,
      updateHash,
      actions: { edit: { name: false } }
    });
  },

  getUploadingFiles(storage, accountId, folderId) {
    return (
      filesTree[storage][accountId][mode][folderId] || { content: [] }
    ).content.filter(object => object.id.startsWith("CS_UPLOAD_"));
  },

  /**
   * @private
   * @param {string} storage
   * @param {string} accountId
   * @param {string} folderId
   * @param {{files:[],folders:[],shortcuts:[]}} results
   * @param {string} [pageToken]
   * @param {string} [nextPageToken]
   * @param {string} [fileFilter]
   */
  setFileTree(
    storage,
    accountId,
    folderId,
    results,
    pageToken,
    nextPageToken,
    fileFilter
  ) {
    if (Object.prototype.hasOwnProperty.call(filesTree, storage) === false) {
      filesTree[storage] = {};
    }
    if (
      Object.prototype.hasOwnProperty.call(filesTree[storage], accountId) ===
      false
    ) {
      filesTree[storage][accountId] = {};
    }
    if (
      Object.prototype.hasOwnProperty.call(
        filesTree[storage][accountId],
        mode
      ) === false
    ) {
      filesTree[storage][accountId][mode] = {};
    }

    const previousData = filesTree[storage][accountId][mode][folderId] || {};

    const t = new Timer("NEWFILES: updateFileTree");
    const updateHash = Date.now();

    const formattedShortcuts = (results.shortcuts || []).map(shortcutInfo =>
      this.parseShortcutData(shortcutInfo, storage, accountId, updateHash)
    );

    const formattedFiles = results.files.map(fileInfo =>
      this.parseFileData(fileInfo, storage, accountId, updateHash)
    );

    const formattedFolders = results.folders.map(folderInfo =>
      this.parseFolderData(folderInfo, storage, accountId, updateHash)
    );

    formattedFiles.forEach(singleFile => {
      this.cacheObjectInfo(singleFile);
    });
    formattedFolders.forEach(singleFolder => {
      this.cacheObjectInfo(singleFolder);
    });
    formattedShortcuts.forEach(singleShortcut => {
      this.cacheObjectInfo(singleShortcut);
    });

    const previousIdSet = new Set();
    if (pageToken && previousData?.content) {
      previousData.content.map(o => previousIdSet.add(o._id || o.id));
    }

    const mergedData = formattedFiles
      .concat(formattedFolders)
      .concat(formattedShortcuts)
      .filter(o => !pageToken || !previousIdSet.has(o._id || o.id));

    // backward compatibility
    saveFiles(mergedData);

    // apetrenko: until we implement similar to Gdrive upload system XENON-64787
    mergedData.push(...this.getUploadingFiles(storage, accountId, folderId));

    filesTree[storage][accountId][mode][folderId] = {
      updatedAt: Date.now(),
      content:
        pageToken && previousData
          ? previousData.content.concat(mergedData)
          : mergedData,
      tokens: (previousData.tokens || []).concat(nextPageToken)
    };

    if (fileFilter) {
      filesTree[storage][accountId][mode][folderId][btoa(fileFilter)] = {
        updatedAt: Date.now(),
        content:
          pageToken && previousData
            ? previousData.content.concat(mergedData)
            : mergedData,
        tokens: (previousData.tokens || []).concat(nextPageToken)
      };
    }

    Logger.addEntry("NEWFILES", "filesTree", filesTree);
    const fullObjectId = `${storage}+${accountId}+${folderId}`;
    Logger.addEntry("NEWFILES", "setFileTree emit");
    this.emit(CONTENT_LOADED, fullObjectId, mode);

    // save to idb
    const contentData = {
      content: _.pluck(
        filesTree[storage][accountId][mode][folderId].content,
        "fullId"
      ),
      fullId: fileFilter
        ? `${storage}+${accountId}+${mode}+${btoa(fileFilter)}+${folderId}`
        : `${storage}+${accountId}+${mode}+${folderId}`,
      _serverUpdateTime: new Date(),
      storage,
      accountId,
      _id: folderId
    };
    Logger.addEntry("NEWFILES", "saving folder content to idb:", contentData);
    // update info about folder content in the idb
    this.cacheFolderContent(contentData).then(() => {
      t.log();
    });
  },

  /**
   * Updates the filesTree using data from Indexed DB's data
   * @private
   */
  cacheFileTree({ _id, content, storage, accountId }, fileFilter) {
    const t = new Timer("NEWFILES: update content from cache");
    const contentPromises = content.map(objId =>
      this.retrieveObjectInfo(objId)
    );
    Promise.all(contentPromises).then(contentData => {
      const data = _.groupBy(contentData, "type");
      if (Object.prototype.hasOwnProperty.call(filesTree, storage) === false) {
        filesTree[storage] = {};
      }
      if (
        Object.prototype.hasOwnProperty.call(filesTree[storage], accountId) ===
        false
      ) {
        filesTree[storage][accountId] = {};
      }
      if (
        Object.prototype.hasOwnProperty.call(
          filesTree[storage][accountId],
          mode
        ) === false
      ) {
        filesTree[storage][accountId][mode] = {};
      }

      // apetrenko: until we implement similar to Gdrive upload system XENON-64787
      const uploadingFiles = this.getUploadingFiles(storage, accountId, _id);

      filesTree[storage][accountId][mode][_id] = {
        updatedAt: null,
        content: (data.file || [])
          .concat(data.folder || [])
          .concat(uploadingFiles)
      };
      if (fileFilter) {
        filesTree[storage][accountId][mode][_id][btoa(fileFilter)] = {
          content: (data.file || [])
            .concat(data.folder || [])
            .concat(uploadingFiles)
        };
      }
      const fullObjectId = `${storage}+${accountId}+${_id}`;
      t.log();
      Logger.addEntry("NEWFILES", "cacheFileTree emit");
      this.emit(CONTENT_LOADED, fullObjectId, mode);
    });
  },

  /**
   * @public
   * @param folderURL
   * @param token
   * @param baseHeaders
   * @param storage
   * @param accountId
   * @param folderId
   * @param fileFilter
   * @returns {Promise<unknown>}
   */
  loadPage(
    folderURL,
    token,
    baseHeaders,
    { storage, accountId, folderId, fileFilter }
  ) {
    return new Promise(resolve => {
      const headers = baseHeaders;
      headers.pageToken = token;
      newRequests
        .sendGenericRequest(folderURL, "GET", headers, undefined, ["*"])
        .then(response => {
          const { results, pageToken: nextPageToken } = response.data;
          this.setFileTree(
            storage,
            accountId,
            folderId,
            results,
            token,
            nextPageToken,
            fileFilter
          );
          if (nextPageToken) {
            resolve(nextPageToken);
          } else {
            resolve("");
          }
        })
        .catch(() => {
          resolve("");
        });
    });
  },

  /**
   * @public
   * @param folderURL
   * @param initialToken
   * @param baseHeaders
   * @param setTreeParameters
   * @returns {Promise<void>}
   */
  async iterateFolder(folderURL, initialToken, baseHeaders, setTreeParameters) {
    let nextToken = initialToken;
    while (nextToken.length > 0) {
      // It makes sense for us to use await here.
      // See example in official eslint docs:
      // https://eslint.org/docs/rules/no-await-in-loop#when-not-to-use-it-58
      // eslint-disable-next-line no-await-in-loop
      nextToken = await this.loadPage(
        folderURL,
        nextToken,
        baseHeaders,
        setTreeParameters
      );
    }
  },

  // TODO: refactor
  /**
   * @public
   * @param storage
   * @param accountId
   * @param folderId
   * @param fileFilter
   * @param isIsolated
   * @param recursive
   * @param usePageToken
   * @returns {Promise<unknown>}
   */
  fetchFolderContent(
    storage,
    accountId,
    folderId,
    fileFilter,
    { isIsolated, recursive, usePageToken }
  ) {
    const t = new Timer("NEWFILES: update content from server");
    return new Promise(resolve => {
      const fullObjectId = `${storage}+${accountId}+${folderId}`;
      let isNetworkDataEmitted = false;

      const folderKeyParameters = [
        storage,
        accountId,
        mode,
        fileFilter ? btoa(fileFilter) : null,
        folderId
      ];
      if (storage === "TR") {
        const trimbleRegion =
          UserInfoStore.getUserInfo("storage")?.otherInfo?.server;
        folderKeyParameters.push(btoa(trimbleRegion));
      }
      const cacheKey = folderKeyParameters.filter(v => !!v).join("+");
      if (
        !usePageToken ||
        !this.doesNextPageExist(storage, accountId, folderId, fileFilter)
      ) {
        this.retrieveFolderContent(cacheKey).then(cachedResponse => {
          if (cachedResponse) {
            Logger.addEntry(
              "NEWFILES",
              "cached data check:",
              cachedResponse,
              cachedResponse._serverUpdateTime
            );
          } else {
            Logger.addEntry("NEWFILES", "No cache found");
          }
          if (
            cachedResponse &&
            cachedResponse._serverUpdateTime >=
              Date.now() - cacheInvalidateTime &&
            !isNetworkDataEmitted
          ) {
            Logger.addEntry("NEWFILES", "set from cache", cachedResponse);
            this.cacheFileTree(cachedResponse, fileFilter);
          }
        });
      }

      let url = `/folders/${fullObjectId}`;
      if (mode === TRASH) {
        url = `/trash/folder/${fullObjectId}`;
      }

      const headers = newRequests.getDefaultUserHeaders();
      if (fileFilter) {
        headers.fileFilter = fileFilter;
      }
      let pageToken = null;
      if (usePageToken && tokens[cacheKey]) {
        pageToken = tokens[cacheKey];
        headers.pageToken = pageToken;
        delete tokens[cacheKey];
      }

      newRequests
        .sendGenericRequest(url, "GET", headers, undefined, ["*"])
        .then(response => {
          const { results, pageToken: nextPageToken } = response.data;
          if (nextPageToken && !isIsolated) {
            Logger.addEntry(
              "NEWFILES",
              "PAGINATION",
              `received ${nextPageToken}`
            );
            tokens[cacheKey] = nextPageToken;
          } else if (nextPageToken && isIsolated && recursive) {
            this.iterateFolder(url, nextPageToken, headers, {
              storage,
              accountId,
              folderId,
              fileFilter
            });
          }
          isNetworkDataEmitted = true;
          this.setFileTree(
            storage,
            accountId,
            folderId,
            results,
            pageToken,
            nextPageToken,
            fileFilter
          );
          t.log();
          resolve({ folders: _.pluck(results.folders, "_id") });
          this.emitEvent(
            FilesListConstants.GET_FOLDER_CONTENT_SUCCESS,
            results
          );
        })
        .catch(({ code, text }) => {
          if (code === 400) {
            // 400 isn't always reconnect! Should distinguish them somehow!
            // storages reconnect
            // UserInfoActions.reconnectStorage(
            //   MainFunctions.storageCodeToServiceName(storage)
            // );
            // Not sure about this, but excluding webdav storage to avoid showing network WD errors
            if (storage !== "WD") {
              SnackbarUtils.alertError(text);
            }
            this.emitEvent(STORAGE_RECONNECT_REQUIRED, storage);
          } else if (!location.pathname.includes("storages")) {
            SnackbarUtils.alertError(text);
            // const tableId = TableStore.getFocusedTable();
            // if (TableStore.isTableRegistered(tableId)) {
            //   let newTableInfo = TableStore.getTable(tableId);
            //   newTableInfo = _.extend(newTableInfo, {
            //     results: [],
            //     loading: false
            //   });
            //   TableActions.saveConfiguration(tableId, newTableInfo);
            // }
            if (code === 412) {
              currentFolder.isError = true;
              FilesListStore.emitChange();
            }
            if (code === 404) {
              ApplicationActions.changePage(
                `/files/${currentFolder.storage}/${currentFolder.accountId}/-1`
              );
            }
          }

          Logger.addEntry("ERROR", "Folder content update went wrong", text);
          this.emitEvent(FilesListConstants.GET_FOLDER_CONTENT_FAIL, text);
        });
    });
  },

  fetchTrashFilesCount() {
    return new Promise((resolve, reject) => {
      const { type, id } = UserInfoStore.getUserInfo("storage");
      const storageType = MainFunctions.serviceNameToStorageCode(type);

      const trashPath = `/trash/folder/${storageType}+${id}+-1`;

      const headers = newRequests.getDefaultUserHeaders();
      headers.fileFilter = "allFiles";

      newRequests
        .sendGenericRequest(trashPath, "GET", headers, undefined, ["*"])
        .then(response => {
          const { data } = response;
          const { number } = data;
          currentTrashFolder.allEntitiesNumber = number;
          this.emitEvent(
            FilesListConstants.GET_TRASH_FILES_COUNT_SUCCESS,
            number
          );
          resolve(number);
        })
        .catch(error => {
          this.emitEvent(FilesListConstants.GET_TRASH_FILES_COUNT_FAIL, error);
          reject(error);
        });
    });
  },

  /**
   * @public
   * @param storage
   * @param accountId
   * @param folderId
   * @param fileFilter
   * @returns {boolean}
   */
  doesNextPageExist(storage, accountId, folderId, fileFilter) {
    const folderKeyParameters = [
      storage,
      accountId,
      mode,
      fileFilter ? btoa(fileFilter) : null,
      folderId
    ];
    if (storage === "TR") {
      const trimbleRegion =
        UserInfoStore.getUserInfo("storage")?.otherInfo?.server;
      folderKeyParameters.push(btoa(trimbleRegion));
    }
    const cacheKey = folderKeyParameters.filter(v => !!v).join("+");
    return Object.prototype.hasOwnProperty.call(tokens, cacheKey);
  },

  /**
   * Get the folder content for specified objectId
   * @public
   * @param {string} objectId
   * @param {string} fileFilter
   * @returns {[]} folder content
   */
  getTreeData(objectId, fileFilter) {
    const {
      storageType,
      storageId,
      objectId: folderId
    } = MainFunctions.parseObjectId(objectId);
    if (fileFilter) {
      try {
        // convert back to JS just in case
        return filesTree[storageType][storageId][mode][folderId][
          btoa(fileFilter)
        ].content.sort((a, b) => sortByType(a, b));
      } catch (ignore) {
        // just ignore this
      }
    }
    try {
      return filesTree[storageType][storageId][mode][folderId].content.sort(
        (a, b) => sortByType(a, b)
      );
    } catch (ex) {
      Logger.addEntry("ERROR", ex);
      return [];
    }
  },

  /**
   * @public
   * @param targetId
   * @param name
   */
  updatePath(targetId, name) {
    const index = _.findIndex(
      folderPath,
      ({ _id }) => MainFunctions.parseObjectId(_id).objectId === targetId
    );
    if (index > -1) {
      // going back
      folderPath = folderPath.slice(0, index + 1);
    } else {
      // going forward - just push
      const newEntry = _.clone(folderPath[folderPath.length - 1]);
      // get storageType and Id from previous entry
      const { storageType, storageId } = MainFunctions.parseObjectId(
        newEntry._id
      );
      newEntry._id = MainFunctions.encapsulateObjectId(
        storageType,
        storageId,
        targetId
      );
      newEntry.name = name;
      folderPath.push(newEntry);
    }
  },

  // TODO: is it needed?
  /**
   * @public
   * @param objectInfo
   * @param permissionName
   * @returns {null|*}
   */
  checkPermission(objectInfo, permissionName) {
    if (
      objectInfo &&
      permissionName &&
      Object.prototype.hasOwnProperty.call(objectInfo, "permissions") &&
      Object.prototype.hasOwnProperty.call(
        objectInfo.permissions,
        permissionName
      )
    ) {
      return objectInfo.permissions[permissionName];
    }
    return null;
  },

  getSearchQuery() {
    return searchQuery;
  },

  setSearchQuery(newQuery) {
    searchQuery = newQuery;
  }
});

FilesListStore.setMaxListeners(0);

function findEntitiesByName(name) {
  const { _id: currentFolderId } = currentFolder;
  const { storageId, storageType, objectId } =
    MainFunctions.parseObjectId(currentFolderId);

  try {
    return filesTree[storageType][storageId][mode][objectId].content.filter(
      entity => entity.name === name
    );
  } catch (ignore) {
    // ignore
  }
  return [];
}

function findEntityById(id) {
  const { _id: currentFolderId } = currentFolder;
  const { storageId, storageType, objectId } =
    MainFunctions.parseObjectId(currentFolderId);

  try {
    return filesTree[storageType][storageId][mode][objectId].content.filter(
      entity => entity._id === id || entity.id === id
    )[0];
  } catch (ignore) {
    // ignore
  }
  return [];
}

/**
 * Function adds entity to object list, returns TRUE if succeed
 * @private
 * @param entityData
 * @return {boolean}
 */
function addEntity(entityData) {
  const currentTimestamp = Date.now();
  const { _id: currentFolderId } = currentFolder;
  const { storageId, storageType, objectId } =
    MainFunctions.parseObjectId(currentFolderId);

  let currentElement = null;
  try {
    currentElement = filesTree[storageType][storageId][mode][
      objectId
    ].content.find(elem => elem.id === (entityData._id || entityData.id));
  } catch (ignore) {
    return false;
  }

  if (currentElement) return false;

  filesTree[storageType][storageId][mode][objectId].content.push(
    _.defaults(entityData, {
      creationDate: currentTimestamp,
      updateDate: currentTimestamp,
      isOwner: true,
      owner: "",
      changer: "",
      ownerId: "",
      actions: {
        edit: {
          name: false,
          comment: false
        }
      },
      lastUpdate: currentTimestamp
    })
  );

  return true;
}

/**
 * @private
 * @param entityId
 * @param newEntityData
 * @param specificParent
 * @param omitProperties
 */
function modifyEntity(entityId, newEntityData, specificParent, omitProperties) {
  const { storageId, storageType } = FilesListStore.findCurrentStorage();
  const { objectId } = MainFunctions.parseObjectId(
    specificParent || currentFolder._id
  );

  let currentElement = null;
  try {
    currentElement = filesTree[storageType][storageId][mode][
      objectId
    ].content.find(elem => elem.id === entityId);
  } catch (ignore) {
    return;
  }

  if (!currentElement) return;

  const updateHash = Date.now();

  currentElement = _.extend(_.extend(currentElement, newEntityData), {
    lastUpdate: updateHash,
    updateHash
  });

  if (omitProperties) {
    _.each(omitProperties, propertyName => {
      currentElement[propertyName] = null;
    });
  }
}

function addItemToContentCache(
  currentFolderId,
  storageType,
  storageId,
  elementId
) {
  const { objectId: folderId } = MainFunctions.parseObjectId(currentFolderId);

  // save new cloned entity to folder cache
  FilesListStore.retrieveFolderContent(
    `${storageType}+${storageId}+${mode}+${folderId}`
  ).then(cacheContent => {
    if (!cacheContent) return;
    cacheContent.content.push(`${storageType}+${storageId}+${elementId}`);
    FilesListStore.cacheFolderContent(cacheContent);
  });
}

/**
 * @private
 * @param entityId
 */
function deleteEntity(entityId) {
  const { _id: currentFolderId } = currentFolder;
  const { storageId, storageType } = FilesListStore.findCurrentStorage();
  const { objectId } = MainFunctions.parseObjectId(currentFolderId);

  let currentElementIndex = -1;
  try {
    currentElementIndex = filesTree[storageType][storageId][mode][
      objectId
    ].content.findIndex(elem => elem.id.endsWith(entityId));
  } catch (ex) {
    return;
  }

  if (currentElementIndex === -1) return;

  filesTree[storageType][storageId][mode][objectId].content.splice(
    currentElementIndex,
    1
  );
}

/**
 * @private deletes all entities in current trash folder
 */
function deleteTrashEntities() {
  const { _id: currentFolderId } = currentFolder;
  const { storageId, storageType } = FilesListStore.findCurrentStorage();
  const { objectId } = MainFunctions.parseObjectId(currentFolderId);

  if (filesTree[storageType][storageId][mode][objectId].content) {
    filesTree[storageType][storageId][mode][objectId].content = [];
  }
}

/**
 * @private
 * @param entities - ids of objects to remove from cache
 */
function removeEntitiesFromCache(entities = []) {
  const { storageId, storageType } = FilesListStore.findCurrentStorage();
  const { objectId } = MainFunctions.parseObjectId(currentFolder._id);

  entities.forEach(entityId => deleteEntity(entityId));
  FilesListStore.retrieveFolderContent(
    `${storageType}+${storageId}+${mode}+${objectId}`
  ).then(dbEntry => {
    if (dbEntry) {
      dbEntry.content = dbEntry.content.filter(
        objId => !entities.includes(objId)
      );
      FilesListStore.cacheFolderContent(dbEntry);
    }
  });
}

/**
 * @private
 * @param suffix
 * @param entity
 * @param pureName
 * @returns {Promise<unknown>}
 */
function renameObjectIfNecessary(suffix, entity, pureName) {
  const modifiedEntity = entity;
  return new Promise(resolve => {
    if (suffix > 0) {
      let finalName = pureName;
      if (entity.type === "file" && entity.name.lastIndexOf(".") > -1) {
        // include extension back
        finalName += entity.name.substr(entity.name.lastIndexOf("."));
      }

      // we have to rename first because of webdav issue (XENON-30435)
      // do we have to show something if rename is unsuccessful?
      newRequests
        .sendGenericRequest(
          `/${entity.type}s/${entity.id}`,
          RequestsMethods.PUT,
          newRequests.getDefaultUserHeaders(),
          {
            [entity.type === "folder" ? "folderName" : "fileName"]: finalName
          }
        )
        .then(answer => {
          const { data } = answer;
          modifyEntity(entity.id, {
            name: finalName,
            fileName: finalName,
            folderName: finalName,
            id: data._id || entity.id,
            _id: data._id || entity.id
          });
          modifiedEntity.id = data._id || entity.id;
          modifiedEntity._id = data._id || entity.id;
          resolve(modifiedEntity);
        })
        .catch(err => {
          Logger.addEntry("ERROR", err);
          resolve(modifiedEntity);
        });
    } else {
      resolve(modifiedEntity);
    }
  });
}

/**
 * Move selected entities to target folder
 * @private
 * @param targetId {String} - id of folder where we should move entities to
 * @param entities {Array} - array of entities for move
 */
function moveToFolder(targetId, entities) {
  if (entities.length === 0) return;
  entities.forEach(entityId => {
    ProcessActions.start(entityId, Processes.MOVE);
  });

  const currentForlerOnMoveBeginMoment = currentFolder;
  newRequests
    .sendGenericRequest(
      `/folders/${targetId}`,
      RequestsMethods.GET,
      newRequests.getDefaultUserHeaders(),
      undefined,
      ["*"],
      false,
      true
    )
    .then(response => {
      const answer = response.data;
      const entitiesInTargetFolder = answer.results;
      const namesOfEntitiesInTargetFolder = {
        files: entitiesInTargetFolder.files
          .map(({ filename }) =>
            filename.toLowerCase().replace(/\.[^/.]+$/, "")
          )
          .concat(
            (entitiesInTargetFolder.shortcuts || []).map(({ filename }) =>
              filename.toLowerCase()
            )
          ),
        folders: entitiesInTargetFolder.folders.map(({ name }) =>
          name.toLowerCase()
        )
      };

      const { _id: currentFolderId } = currentForlerOnMoveBeginMoment;
      const entitiesInFolder = FilesListStore.getTreeData(currentFolderId);
      const movePromises = entities.map(
        entityId =>
          new Promise((resolve, reject) => {
            const entity = entitiesInFolder.find(elem => elem._id === entityId);

            // each entity should have it's name
            if ((entity.name || "").length) {
              // get name only (without extension)
              let pureName = entity.name;

              // if it is file then we suppose that everything after last '.' is an extension
              if (
                entity.type === "file" &&
                !entity.isShortcut &&
                pureName.lastIndexOf(".") > -1
              ) {
                // take all except extension
                pureName = pureName.substr(0, pureName.lastIndexOf("."));
              }

              let nameToCheck = pureName.toLowerCase();
              // index of current suffix. 0 if it's not required
              let currentSuffix = 0;

              // check if entity with this name is in target folder
              // if not - we can move it safely.
              while (
                namesOfEntitiesInTargetFolder[`${entity.type}s`].indexOf(
                  nameToCheck
                ) > -1
              ) {
                // if there are any entities wiFth the same names - modify name.
                if (currentSuffix > 0) {
                  // if suffix was added already - get name without it
                  pureName = pureName.substr(0, pureName.lastIndexOf("("));
                }

                // increase currentSuffix
                currentSuffix += 1;

                // add suffix to name
                pureName = `${pureName}(${currentSuffix})`;
                nameToCheck = pureName.toLowerCase();
              }

              renameObjectIfNecessary(currentSuffix, entity, pureName).then(
                modifiedEntity => {
                  newRequests
                    .sendGenericRequest(
                      `/${modifiedEntity.type}s/${modifiedEntity.id}`,
                      RequestsMethods.PUT,
                      newRequests.getDefaultUserHeaders(),
                      {
                        [modifiedEntity.type === "folder"
                          ? "parentId"
                          : "folderId"]: targetId
                      },
                      ["*"],
                      false,
                      true
                    )
                    .then(() => {
                      ProcessActions.end(modifiedEntity.id, "move");
                      ProcessActions.end(entity.id, "move");
                      // remove different ids (initial and new one) - just in case
                      deleteEntity(entity.id);
                      deleteEntity(modifiedEntity.id);
                      FilesListStore.emit(
                        CONTENT_UPDATED,
                        currentFolder._id,
                        mode
                      );

                      if (
                        currentFolder._id !== currentForlerOnMoveBeginMoment._id
                      ) {
                        const { storageType, storageId, objectId } =
                          MainFunctions.parseObjectId(currentFolder._id);
                        FilesListActions.getFolderContent(
                          storageType,
                          storageId,
                          objectId,
                          false,
                          {
                            isIsolated: false,
                            recursive: false,
                            usePageToken: false
                          }
                        );
                      }
                      resolve();
                    })
                    .catch(err => {
                      reject(err.text);
                      SnackbarUtils.alertError(err.text);
                      ProcessActions.end(modifiedEntity.id, "move");
                      ProcessActions.end(entity.id, "move");
                    });
                }
              );
            } else reject(new Error("No name provided"));
          })
      );
      Promise.all(movePromises).then(() => {
        SnackbarUtils.alertOk({ id: "entitiesMovedToTarget" });
        removeEntitiesFromCache(entities);
      });
    })
    .catch(err => {
      SnackbarUtils.alertError(err.text);
      entities.forEach(entityId => {
        ProcessActions.end(entityId, "move");
      });
    });
}

/**
 * Changes file mode (browser/viewer/trash)
 * @private
 * @param entityName {String}
 * @param entityId {String}
 * @param viewOnly {Boolean}
 * @param type {string}
 * @param [historyUpdate] {Boolean}
 */
function toggleMode(entityName, entityId, viewOnly, type, historyUpdate) {
  if (type === "viewer") {
    const ext = MainFunctions.getExtensionFromName(entityName);
    if (UserInfoStore.findApp(ext, null) === "xenon") {
      mode = type;
      currentFile = {
        id: entityId,
        name: entityName,
        viewOnly,
        isModified: false,
        ignoreSettingModifyFlag: false
      };
      if (entityId && historyUpdate !== false) {
        browserHistory.push(
          `${window.ARESKudoConfigObject.UIPrefix}file/${entityId}`
        );
      }
    }
  } else if (type === BROWSER || type === TRASH) {
    mode = type;
    FilesListStore.emitEvent(MODE_UPDATED);
    FilesListStore.emitChange();
  }
  // TableActions.removeSelection();
}

/**
 * Saves path to current folder into browser history
 * @private
 * @param path {Array}
 * @param path._id {String}
 * @param path.name {String}
 * @param path.viewOnly {Boolean}
 */
function savePath(path) {
  let lastFolder = path[path.length - 1];
  lastFolder = {
    _id: lastFolder._id,
    name: lastFolder.name,
    viewOnly: lastFolder.viewOnly || false,
    full: lastFolder._id.includes("-1")
  };
  const normalizedPath = path.map(objInfo => {
    objInfo.viewOnly = objInfo.viewOnly || false;
    return objInfo;
  });
  if (mode === BROWSER) {
    folderPath = normalizedPath;
    const isShare = location.pathname.indexOf("share") > -1;
    const shareId = isShare
      ? location.pathname.substr(
          location.pathname.indexOf("share") + "share/".length
        ) || ""
      : "";
    if (isShare === true && shareId.length > 0) {
      browserHistory.push(
        `${window.ARESKudoConfigObject.UIPrefix}files/${
          folderPath[folderPath.length - 1]._id
        }/share/${shareId}`
      );
    }
  } else if (mode === TRASH) {
    currentTrashFolder = lastFolder;
    trashFolderPath = normalizedPath;
  }
  FilesListStore.emitEvent(PATH_LOADED);
}

/**
 * Updates entity (file/folder)
 * @private
 * @param data {object} - id of entity to update
 */
function updateEntity(data, forceType) {
  const { _id } = data;
  let { parent } = data;

  if (!parent) parent = currentFolder._id;

  let { storageId, storageType, objectId } =
    MainFunctions.parseObjectId(parent);

  let fileTreeElement = null;
  try {
    fileTreeElement = filesTree[storageType][storageId][mode][objectId];
  } catch (err) {
    // eslint-disable-next-line no-console
    console.log(`Error on entity update`, err);
  }
  if (!fileTreeElement) {
    ({ storageId, storageType, objectId } = MainFunctions.parseObjectId(
      currentFolder._id
    ));
    if (!storageId || !storageType) return;
    fileTreeElement = filesTree[storageType][storageId][mode][objectId];
  }
  let originalEntity = fileTreeElement.content.find(elem => elem._id === _id);

  if (!originalEntity) return;

  originalEntity = _.extend(_.extend(originalEntity, data), {
    name: data.filename || data.foldername || data.name,
    isClientSideData: false,
    isShortcut: Object.prototype.hasOwnProperty.call(data, "shortcutInfo"),
    ...(forceType ? { type: forceType.slice(0, -1) } : {})
  });

  originalEntity.full = true;

  FilesListStore.cacheObjectInfo(
    _.extend(originalEntity, {
      fullId: originalEntity._id || originalEntity.id
    })
  );
}

function checkIfCanceled(realParentId) {
  return (
    !uploads ||
    !uploads[realParentId] ||
    uploads[realParentId].isCanceled === true
  );
}

function addToCancelledUploads(canceledUploads, name) {
  if (canceledUploads !== null && !canceledUploads.includes(name)) {
    canceledUploads.push(name);
  }
}

/**
 * @private
 * @param realParentId
 * @param name
 * @param parentId
 * @param body
 * @param currentId
 * @param currentFolderId
 * @param updateUsage - update usage and send ws notification (true/false)
 * @returns {Promise<unknown>}
 */
function createFile(
  canceledUploads,
  realParentId,
  name,
  parentId,
  body,
  currentId,
  currentFolderId,
  updateUsage,
  uploadRequestId,
  presignedUploadId
) {
  return new Promise((resolve, reject) => {
    if (!name || !parentId || !realParentId) {
      reject(new Error("File cannot be created. Check createFile function"));
    }
    if (checkIfCanceled(realParentId)) {
      if (presignedUploadId) {
        const headers = newRequests.getDefaultUserHeaders();
        headers.presignedUploadId = presignedUploadId;
        newRequests.sendGenericRequest(
          `/files/upload/cancel`,
          RequestsMethods.PUT,
          headers,
          undefined,
          ["*"]
        );
      }
      addToCancelledUploads(canceledUploads, name);
      reject(new Error("uploadCanceled"));
      return;
    }
    const fd = new FormData();
    let headers = [
      {
        name: "sessionId",
        value: Storage.store("sessionId")
      },
      {
        name: "locale",
        value: Storage.store("locale")
      },
      {
        name: "updateUsage",
        value: updateUsage
      }
    ];
    if (parentId !== "-1") {
      headers = [
        ...headers,
        {
          name: "folderId",
          value: parentId
        }
      ];
    }
    if (uploadRequestId) {
      headers = [
        ...headers,
        {
          name: "uploadRequestId",
          value: uploadRequestId
        }
      ];
    }

    if (presignedUploadId) {
      headers = [
        ...headers,
        {
          name: "presignedUploadId",
          value: presignedUploadId
        }
      ];
    } else {
      ProcessActions.start(currentId, Processes.UPLOADING);
      FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
    }

    // IA: XENON-50254: File object has property webkitRelativePath and firefox correctly
    // paste data in it (Chrome not paste data in it) but FormData object on POST request wrongly create filename from
    // webkitRelativePath if it present, so better to set filename directly
    fd.append(0, body, name);

    const uploadedItem = uploads[realParentId].uploads.find(
      upload => upload.currentId === currentId
    );
    const controller = new AbortController();
    if (uploadedItem) {
      if (!uploadedItem.abortFunction) {
        uploadedItem.abortFunction = controller;
        uploads[realParentId].uploads.splice(
          uploads[realParentId].uploads.indexOf(uploadedItem),
          1,
          uploadedItem
        );
      }
    } else {
      uploads[realParentId].uploads.push({
        currentId,
        abortFunction: controller
      });
    }

    let timer = Date.now();
    newRequests.uploadFile(
      `${window.ARESKudoConfigObject.api}/files`,
      presignedUploadId ? null : fd,
      headers,
      response => {
        if (response?.name === "CanceledError") {
          addToCancelledUploads(canceledUploads, name);
          reject(new Error("uploadCanceled"));
          return;
        }
        let endProcess = true;
        if (response.status === 413) {
          reject(
            new UploadError({
              code: 413,
              name: "EntityTooLarge",
              objectName: name,
              message: "Entity is too large"
            })
          );
        } else if (response.status === 429) {
          reject(
            new UploadError({
              code: 429,
              name: "RateLimitError",
              objectName: name,
              message:
                MainFunctions.safeJSONParse(response.error || response.data)
                  ?.message || "Please retry later"
            })
          );
        } else if (response.status === 403) {
          try {
            const jsonMessage = JSON.parse(response.error || response.data);
            if (jsonMessage.errorId === "KD1") {
              reject(
                new UploadError({
                  code: response.status,
                  name: "NoQuotaLeftForUpload",
                  objectName: name,
                  message: "cannotUploadFilesKudoDriveIsFull"
                })
              );
            }
          } catch (ex) {
            reject(
              new UploadError({
                code: response.status,
                name: "UnknownError",
                objectName: name,
                message: response.error?.message || response.data?.message
              })
            );
          }
        } else if (response.status !== 200) {
          let errMessage =
            response.data?.message ||
            response.error?.message ||
            "Unknown error";
          try {
            const jsonAnswer = JSON.parse(response.data || response.error);
            errMessage = jsonAnswer.errorMessage || jsonAnswer.message;
            // eslint-disable-next-line no-console
            console.error(jsonAnswer);
          } catch (ex) {
            // ignore
          }
          reject(
            new UploadError({
              code: response.status,
              name: "UnknownError",
              objectName: name,
              message: errMessage
            })
          );
        } else {
          if (response && response.data.uploadToken) {
            endProcess = false;
          }
          resolve(response.data);
        }
        if (parentId === currentFolderId && endProcess) {
          ProcessActions.end(currentId);
        }
      },
      controller,
      progressEvt => {
        if (
          !progressEvt.event ||
          !progressEvt.event.lengthComputable ||
          progressEvt.event.lengthComputable !== true
        ) {
          return;
        }
        const loaded = progressEvt.loaded / progressEvt.total;
        if (loaded === 1) {
          // to remove percentage
          ProcessActions.end(currentId);
          ProcessActions.start(currentId, Processes.FINISHING_UPLOAD);
          FilesListStore.emit(SORT_REQUIRED, currentFolder._id, mode);
        } else if (loaded > 0) {
          if (Date.now() - timer >= 100) {
            const percentage = (loaded * 100).toFixed(2);
            ProcessActions.step(currentId, percentage);
            timer = Date.now();
          }
        }
      },
      currentId
    );
  });
}

/**
 * @private
 * @param uploadId
 * @param name
 * @param parentId
 * @returns {Promise<unknown>}
 */
function createDirectory(
  canceledUploads,
  uploadId,
  topLevelParent,
  name,
  parentId
) {
  return new Promise((resolve, reject) => {
    if (uploads[topLevelParent].isCanceled === true) {
      addToCancelledUploads(canceledUploads, name);
      reject(new Error("uploadCanceled"));
      return;
    }
    if (!name || !parentId) {
      reject(
        new Error("Folder cannot be created. Check createDirectory function")
      );
    }
    const data = { name };
    if (parentId !== "-1") {
      data.parentId = parentId;
    }
    if (uploads[topLevelParent]) {
      if (!uploads[topLevelParent].folders) {
        uploads[topLevelParent].folders = [uploadId];
      } else {
        uploads[topLevelParent].folders.push(uploadId);
      }
    }
    newRequests
      .sendGenericRequest(
        "/folders",
        "POST",
        newRequests.getDefaultUserHeaders(),
        data,
        ["*"],
        false,
        false,
        uploadId
      )
      .then(answer => {
        resolve(answer);
      })
      .catch(err => {
        if (err.text === "Abort") {
          addToCancelledUploads(canceledUploads, name);
          reject(new Error("uploadCanceled"));
          return;
        }
        reject(err.text || err.message);
      });
  });
}

/**
 * @private
 * @param uploadId
 */
function cancelEntityUpload(uploadId) {
  if (uploads[uploadId].isDirectory) {
    uploads[uploadId].isCanceled = true;
    ProcessActions.end(uploadId);
    ProcessActions.start(uploadId, Processes.UPLOAD_CANCELED);
    uploads[uploadId].uploads.forEach(upload => {
      if (upload.abortFunction) {
        upload.abortFunction.abort();
      }
      const process = ProcessStore.getProcess(upload.currentId);
      if (process && process.url) {
        FetchAbortsControl.abortSpecificSignal(process.url);
      }
    });
    if (uploads[uploadId].folders) {
      uploads[uploadId].folders.forEach(folderIdParam => {
        FetchAbortsControl.abortSpecificSignalByActionId(folderIdParam);
      });
    }

    const forcedIdList = {
      files: [],
      folders: [uploads[uploadId].topParentFolderId]
    };

    setTimeout(() => {
      FilesListActions.deleteSelected(forcedIdList, false).then(() => {
        ProcessActions.end(uploadId);
        deleteEntity(uploadId);
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
        setTimeout(() => {
          FilesListActions.eraseObjects(forcedIdList);
        }, 5000);
      });
    }, 2000);
  } else {
    const upload = uploads[uploadId].uploads[0];
    if (upload && upload.abortFunction) {
      upload.abortFunction.abort();
    }
    const process = ProcessStore.getProcess(uploadId);
    if (process && process.url) {
      FetchAbortsControl.abortSpecificSignal(process.url);
    }
    ProcessActions.end(uploadId);
    ProcessActions.start(uploadId, Processes.UPLOAD_CANCELED);
    delete uploads[uploadId];
    setTimeout(() => {
      ProcessActions.end(uploadId);
      deleteEntity(uploadId);
      FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
    }, 2000);
  }
}

function readDirectoryEntries(directory) {
  return new Promise(resolve => {
    const directoryReader = directory.createReader();
    directoryReader.readEntries(entries => {
      resolve(entries);
    });
  });
}

function getObjectMetadata(entry) {
  return new Promise(resolve => {
    if (entry.getMetadata) {
      entry.getMetadata(metadata => {
        resolve(metadata);
      });
    } else if (
      window.FileSystemFileEntry &&
      entry instanceof window.FileSystemFileEntry
    ) {
      entry.file(
        f => {
          resolve({ size: f.size });
        },
        () => {
          resolve(entry);
        }
      );
    } else {
      resolve(entry);
    }
  });
}

async function processUploadItem(
  item,
  parent,
  initialFolderId,
  storageType,
  skippedUploads
) {
  let finalItem = item;
  if (item instanceof DataTransferItem) {
    finalItem = item.webkitGetAsEntry();
  }
  const isFile = finalItem instanceof File || finalItem.isFile;
  const uuid = MainFunctions.guid("UPLOAD");
  const mimeType = mime.getType(finalItem.name);
  if (isFile) {
    const itemMetadata = await getObjectMetadata(finalItem);
    const itemSize = itemMetadata.size;
    if (itemSize > MAX_SINGLE_FILE_UPLOAD_SIZE) {
      skippedUploads.push({
        name: finalItem.name,
        size: itemSize
      });
      return null;
    }
    return {
      uuid,
      data: {
        item: finalItem,
        parent,
        size: itemSize,
        type: "file",
        mimeType
      },
      children: null
    };
  }
  const newItems = await readDirectoryEntries(finalItem);
  let subItems = await Promise.all(
    newItems.map(newItem =>
      processUploadItem(
        newItem,
        uuid,
        initialFolderId,
        storageType,
        skippedUploads
      )
    )
  );
  subItems = subItems.filter(i => i !== null);
  const totalSubFolders = subItems.filter(
    subItem => subItem.data.type === "folder"
  ).length;
  const subFiles = subItems.filter(subItem => subItem.data.type === "file");
  const totalSubFilesSize = subFiles.reduce((m, v) => m + v.data.size, 0);
  const statsFromSubItems = subItems
    .map(subItem => subItem.data.stats)
    .filter(stat => stat);
  const totalSubItemsStats = {
    folders: statsFromSubItems.reduce((m, v) => m + v.folders, 0) || 0,
    files: statsFromSubItems.reduce((m, v) => m + v.files, 0) || 0,
    filesSize: statsFromSubItems.reduce((m, v) => m + v.filesSize, 0) || 0
  };
  const statFiles = totalSubItemsStats.files + subFiles.length;
  const folders = totalSubItemsStats.folders + totalSubFolders;
  const filesSize = totalSubItemsStats.filesSize + totalSubFilesSize;
  const avgFileSize = Math.floor(filesSize / statFiles);
  const fakeFolderSize = avgFileSize * folders + filesSize;
  return {
    uuid,
    data: {
      item: finalItem,
      parent,
      type: "folder",
      stats: {
        folders,
        files: statFiles,
        filesSize,
        avgFileSize,
        fakeFolderSize
      }
    },
    children: subItems
  };
}

async function generateUploadTree(
  items,
  initialFolderId,
  storageType,
  skippedUploads
) {
  let treeItems = await Promise.all(
    [...Array.from(items)].map(item =>
      processUploadItem(
        item,
        initialFolderId,
        initialFolderId,
        storageType,
        skippedUploads
      )
    )
  );
  treeItems = treeItems.filter(i => i !== null);
  return {
    [initialFolderId]: {
      uuid: initialFolderId,
      data: null,
      children: treeItems
    }
  };
}

function updateAllDescendants(parent, newId) {
  const oldId = parent.uuid;

  const { children } = parent;
  if (Array.isArray(children)) {
    const childrenToProcess = [...children];
    while (childrenToProcess?.length > 0) {
      const child = childrenToProcess.shift();
      child.data.topLevelParent = newId;
      if (child.data.parent === oldId) {
        child.data.parent = newId;
      }
      childrenToProcess.push(...(child.children || []));
    }
  }
  parent.uuid = newId;
  parent.data.topLevelParent = newId;
}

function endProcessInterval(intervalWindowName) {
  clearInterval(window[intervalWindowName]);
  delete window[intervalWindowName];
}

function handlePresignedAsyncUpload(
  canceledUploads,
  returnedData,
  itemToUpload,
  currentFolderId
) {
  return new Promise((resolve, reject) => {
    if (!returnedData || !returnedData.uploadToken) {
      ProcessActions.start(itemToUpload.uuid, Processes.FINISHING_UPLOAD);
      FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
      resolve(returnedData);
      return;
    }
    const { uploadToken } = returnedData;
    const intervalName = `uploadTimer_${uploadToken}`;
    let existingCheckRequest = false;
    const headers = newRequests.getDefaultUserHeaders();
    headers.uploadToken = uploadToken;
    let isFileDownloaded = false;
    window[intervalName] = setInterval(() => {
      if (checkIfCanceled(itemToUpload.data.topLevelParent)) {
        endProcessInterval(intervalName);
        newRequests.sendGenericRequest(
          `/files/upload/cancel`,
          RequestsMethods.PUT,
          headers,
          undefined,
          ["*"]
        );
        addToCancelledUploads(canceledUploads, itemToUpload.data.item.name);
        reject(new Error("uploadCanceled"));
        return;
      }
      if (!existingCheckRequest) {
        existingCheckRequest = true;
        newRequests
          .sendGenericRequest(
            `/files/upload/check`,
            RequestsMethods.GET,
            headers,
            undefined,
            ["*"]
          )
          .then(response => {
            if (response.code === 202) {
              const result = response.data;
              if (
                !isFileDownloaded &&
                result.isDownloaded &&
                result.isDownloaded === true
              ) {
                ProcessActions.start(
                  itemToUpload.uuid,
                  Processes.FINISHING_UPLOAD
                );
                FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
                isFileDownloaded = true;
              }
            } else if (response.code === 200) {
              endProcessInterval(intervalName);
              resolve(response.data);
            }
            existingCheckRequest = false;
          })
          .catch(err => {
            endProcessInterval(intervalName);
            reject(err);
          });
      }
    }, 1200);
  });
}

function handleFinishedUpload(
  uploadedItem,
  currentFolderId,
  returnedData,
  uploadTree,
  isFile
) {
  if (uploadedItem.uuid === uploadedItem.data.topLevelParent) {
    // top level - update info
    if (checkIfCanceled(uploadedItem.uuid)) {
      return;
    }
    if (!returnedData) {
      // if data is null - some error has happened
      deleteEntity(uploadedItem.uuid);
      FilesListStore.emit(CONTENT_UPDATED, currentFolderId, mode);
    } else {
      let entityId = "";
      let entityType = isFile ? "file" : "folder";
      if (isFile) {
        const createdFolder = returnedData.createdFolder || false;
        const folderId = returnedData.folderId || returnedData.fileId;
        entityId = returnedData.fileId || "";
        if (createdFolder && FilesListStore.getCurrentState() === "browser") {
          entityType = "folder";
          entityId = folderId;
        }
      } else {
        entityId = returnedData.folderId;
      }
      uploads[entityId] = JSON.parse(
        JSON.stringify(uploads[uploadedItem.uuid])
      );
      delete uploads[uploadedItem.uuid];
      uploads[entityId].topParentFolderId = entityId;
      ProcessActions.end(uploadedItem.uuid);
      if (isFile) {
        ProcessActions.start(entityId, Processes.UPLOAD_COMPLETE, entityId);
        FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
        setTimeout(() => {
          ProcessActions.end(entityId);
          FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
        }, 5000);
      } else {
        ProcessActions.start(entityId, Processes.UPLOADING, entityId, {
          uploadId: entityId
        });
      }

      modifyEntity(
        uploadedItem.uuid,
        { id: entityId, _id: entityId },
        uploadedItem.data.parent
      );
      FilesListStore.emit(CONTENT_UPDATED, currentFolderId, mode);
      FilesListActions.updateEntityInfo(entityId, `${entityType}s`);
      const topParent = uploadTree[currentFolderId].children.find(
        item => item.uuid === uploadedItem.data.topLevelParent
      );
      updateAllDescendants(topParent, entityId);
    }
  } else {
    const parentInfo = _.find(
      FilesListStore.getTreeData(currentFolderId),
      elem => elem._id === uploadedItem.data.topLevelParent
    );
    if (parentInfo && !checkIfCanceled(uploadedItem.data.topLevelParent)) {
      const topParent = uploadTree[currentFolderId].children.find(
        item => item.uuid === uploadedItem.data.topLevelParent
      );
      if (topParent.data.type === "folder") {
        if (!topParent.data.stats.processed) {
          topParent.data.stats.processed = {
            files: 0,
            folders: 0,
            filesSize: 0
          };
        }
        const { processed } = topParent.data.stats;
        processed[isFile ? "files" : "folders"] += 1;
        if (isFile) {
          processed.filesSize += uploadedItem.data.size;
        }
        const { avgFileSize, fakeFolderSize } = topParent.data.stats;
        let percentage =
          ((processed.filesSize + processed.folders * avgFileSize) /
            fakeFolderSize) *
          100;
        if (percentage >= 100) {
          ProcessActions.end(topParent.uuid);
          ProcessActions.start(
            topParent.uuid,
            Processes.UPLOAD_COMPLETE,
            topParent.uuid
          );

          FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
          setTimeout(() => {
            ProcessActions.end(topParent.uuid);
            FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
          }, 5000);
        } else {
          if (percentage > 99) {
            percentage = 99.99;
          }
          ProcessActions.step(
            uploadedItem.data.topLevelParent,
            percentage.toFixed(2)
          );
        }
      }
    }
  }
}

function handleUploadError(
  unsuccessfulUploads,
  itemToUpload,
  currentFolderId,
  uploadTree,
  err
) {
  unsuccessfulUploads.push({
    name: itemToUpload.data.item.name,
    error: err?.message || "Unknown error"
  });
  handleFinishedUpload(itemToUpload, currentFolderId, null, uploadTree, true);
}

function checkAndDoPresignedUpload(
  canceledUploads,
  itemToUpload,
  fileBody,
  actionId,
  parentId,
  currentFolderId
) {
  return new Promise((resolve, reject) => {
    // Check if the file is large (> 5 MB), then upload it via presigned url else we do normal upload
    if (itemToUpload.data.size / (1024 * 1024) > 5) {
      if (checkIfCanceled(itemToUpload.data.topLevelParent)) {
        addToCancelledUploads(canceledUploads, itemToUpload.data.item.name);
        reject(new Error("uploadCanceled"));
        return;
      }
      uploads[itemToUpload.data.topLevelParent].uploads.push({
        currentId: actionId,
        abortFunction: null
      });
      // generate and return presigned url
      FilesListActions.generatePreSignedUrl(
        itemToUpload.data.item.name,
        itemToUpload.data.mimeType,
        null,
        "file"
      )
        .then(result => {
          if (checkIfCanceled(itemToUpload.data.topLevelParent)) {
            addToCancelledUploads(canceledUploads, itemToUpload.data.item.name);
            reject(new Error("uploadCanceled"));
            return;
          }
          ProcessActions.start(actionId, Processes.UPLOADING, actionId, {
            url: result.presignedUrl
          });
          if (parentId === currentFolderId) {
            FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
          }
          // upload large file using presigned url
          FilesListActions.uploadFileUsingPresignedUrl(
            result.presignedUrl,
            fileBody,
            itemToUpload.data.mimeType
          )
            .then(() => {
              if (parentId === currentFolderId) {
                const { storageType } = FilesListStore.findCurrentStorage();
                if (storageType === "SF") {
                  ProcessActions.start(actionId, Processes.FINISHING_UPLOAD);
                  FilesListStore.emit(SORT_REQUIRED, currentFolderId, mode);
                }
              }
              resolve(result.presignedUploadId);
            })
            .catch(err => {
              if (err.name && err.name === "AbortError") {
                addToCancelledUploads(
                  canceledUploads,
                  itemToUpload.data.item.name
                );
              }
              reject(err);
            });
        })
        .catch(err => {
          if (err.name && err.name === "AbortError") {
            addToCancelledUploads(canceledUploads, itemToUpload.data.item.name);
          }
          reject(err);
        });
    } else {
      resolve(null);
    }
  });
}

function processUploadQueue(
  uploadQueue,
  currentFolderId,
  uploadTree,
  unsuccessfulUploads,
  canceledUploads,
  updateUsage,
  uploadRequestId
) {
  const processingPromises = [];
  while (uploadQueue.length > 0) {
    const itemToUpload = uploadQueue.pop();
    if (!itemToUpload.data.topLevelParent) {
      itemToUpload.data.topLevelParent = itemToUpload.uuid;
    }
    uploads[itemToUpload.uuid] = {
      ids: {
        folder: [],
        file: []
      },
      uploads: [],
      isDirectory: itemToUpload.data.type !== "file",
      uploadToParentId: currentFolderId,
      topParentFolderId: itemToUpload.data.topLevelParent,
      isCanceled: false
    };
    if (itemToUpload.data.parent === currentFolderId) {
      const { uuid: newEntityId } = itemToUpload;
      const newEntity = {
        parentId: currentFolderId,
        id: newEntityId,
        _id: newEntityId,
        name: itemToUpload.data.item.name,
        type: itemToUpload.data.type,
        isClientSideData: true
      };
      addEntity(newEntity);
      const currentMode = mode;
      FilesListStore.emit(CONTENT_UPDATED, currentFolderId, currentMode);
      ProcessActions.start(newEntityId, Processes.PREPARING, newEntityId, {
        uploadId: newEntityId,
        uploadToParentId: currentFolderId
      });
      FilesListStore.emit(SORT_REQUIRED, currentFolderId, currentMode);
    }
    if (itemToUpload.data.type === "file") {
      processingPromises.push(
        new Promise(resolve => {
          FilesListActions.getFileBody(itemToUpload.data.item)
            .then(fileBody => {
              checkAndDoPresignedUpload(
                canceledUploads,
                itemToUpload,
                fileBody,
                itemToUpload.uuid,
                itemToUpload.data.parent,
                currentFolderId
              )
                .then(presignedUploadId => {
                  createFile(
                    canceledUploads,
                    itemToUpload.data.topLevelParent,
                    itemToUpload.data.item.name,
                    itemToUpload.data.parent,
                    fileBody,
                    itemToUpload.uuid,
                    currentFolderId,
                    updateUsage, // Do not update usage for multiple files, it will updated together at the end
                    uploadRequestId,
                    presignedUploadId
                  )
                    .then(returnedData => {
                      handlePresignedAsyncUpload(
                        canceledUploads,
                        returnedData,
                        itemToUpload,
                        currentFolderId,
                        uploadRequestId
                      )
                        .then(result => {
                          handleFinishedUpload(
                            itemToUpload,
                            currentFolderId,
                            result,
                            uploadTree,
                            true
                          );
                          resolve();
                        })
                        .catch(err => {
                          handleUploadError(
                            unsuccessfulUploads,
                            itemToUpload,
                            currentFolderId,
                            uploadTree,
                            err
                          );
                          resolve();
                        });
                    })
                    .catch(err => {
                      handleUploadError(
                        unsuccessfulUploads,
                        itemToUpload,
                        currentFolderId,
                        uploadTree,
                        err
                      );
                      resolve();
                    });
                })
                .catch(err => {
                  handleUploadError(
                    unsuccessfulUploads,
                    itemToUpload,
                    currentFolderId,
                    uploadTree,
                    err
                  );
                  resolve();
                });
            })
            .catch(err => {
              handleUploadError(
                unsuccessfulUploads,
                itemToUpload,
                currentFolderId,
                uploadTree,
                err
              );
              resolve();
            });
        })
      );
    } else {
      processingPromises.push(
        new Promise(resolve => {
          const { uuid: oldId } = itemToUpload;
          createDirectory(
            canceledUploads,
            oldId,
            itemToUpload.data.topLevelParent,
            itemToUpload.data.item.name,
            itemToUpload.data.parent
          )
            .then(({ data: directoryData }) => {
              handleFinishedUpload(
                itemToUpload,
                currentFolderId,
                directoryData,
                uploadTree,
                false
              );
              resolve(
                itemToUpload.children.map(child => ({
                  ...child,
                  data: {
                    ...child.data,
                    parent: directoryData.folderId,
                    topLevelParent: itemToUpload.data.topLevelParent
                  }
                }))
              );
            })
            .catch(err => {
              handleUploadError(
                unsuccessfulUploads,
                itemToUpload,
                currentFolderId,
                uploadTree,
                err
              );
              resolve();
            });
        })
      );
    }
  }
  return processingPromises;
}

function countFilesSize(uploadTree) {
  return Object.values(uploadTree)[0].children.reduce(
    (sum, entity) =>
      entity.data.type === "folder"
        ? sum + (entity.data.stats.filesSize || 0)
        : sum + (entity.data.size || 0),
    0
  );
}

function countFilesAmount(uploadTree) {
  return Object.values(uploadTree)[0].children.length;
}

function beginOrEndMultipleUploadRequest(begin, uploadRequestId) {
  return new Promise(resolve => {
    const headers = newRequests.getDefaultUserHeaders();
    headers.begin = begin;
    if (begin === false) {
      headers.uploadRequestId = uploadRequestId;
    }
    newRequests
      .sendGenericRequest(
        `/upload/request`,
        RequestsMethods.PUT,
        headers,
        undefined,
        ["*"]
      )
      .then(response => {
        const result = response.data;
        if (begin && result) {
          resolve(result.uploadRequestId);
        } else {
          resolve();
        }
      })
      .catch(response => {
        if (response.code !== 200) {
          SnackbarUtils.alertError(response.text);
        }
        resolve();
      });
  });
}

function getUploadErrors(unsuccessfulUploads, canceledUploads) {
  const val = _.chain(unsuccessfulUploads)
    .filter(uploadFilter => !canceledUploads.includes(uploadFilter.name))
    .map(uploadEntry => ({
      id: "uploadingFileError",
      name: uploadEntry.name,
      error: uploadEntry.error
    }))
    .value();
  return val;
}

/**
 * @private
 * @param entities
 */
async function uploadEntities(entities) {
  let items = [];
  if (entities instanceof FileList || entities instanceof File) {
    // if it's already FileList or File - this is regular upload, not drag'n'drop
    items = entities;
  } else {
    ({ items } = entities.dataTransfer);
  }
  const skippedUploads = [];
  const currentFolderId = FilesListStore.getCurrentFolder()._id;
  const { storageType } = FilesListStore.findCurrentStorage();
  const uploadTree = await generateUploadTree(
    items,
    currentFolderId,
    storageType,
    skippedUploads
  );

  const MAX_UPLOAD_AMOUNT = 50;
  if (countFilesAmount(uploadTree) > MAX_UPLOAD_AMOUNT) {
    SnackbarUtils.alertError([
      {
        id: "tooManyFilesForUpload",
        number: MAX_UPLOAD_AMOUNT
      },
      { id: "pleaseTryUploadingSeparately" }
    ]);
    return;
  }
  const MAX_MULTIPLE_ENTITIES_UPLOAD_SIZE = 500 * 1024 * 1024; // WB-60
  const totalFilesSize = countFilesSize(uploadTree);
  if (totalFilesSize > MAX_MULTIPLE_ENTITIES_UPLOAD_SIZE) {
    SnackbarUtils.alertError([
      { id: "EntitiesAreTooBigForUpload", size: "500 Mb" },
      { id: "pleaseTryUploadingSeparately" }
    ]);
    return;
  }
  if (storageType === "SF") {
    const samplesData =
      UserInfoStore.getStoragesData()?.[
        MainFunctions.storageCodeToServiceName(storageType)
      ];
    const { quota, usage } = _.first(samplesData);
    if (quota && usage) {
      const percentage = Math.ceil((usage / quota) * 100);
      if (percentage > 98) {
        SnackbarUtils.alertError({ id: "cannotUploadFilesKudoDriveIsFull" });
        return;
      }
    }

    if (quota - usage < totalFilesSize) {
      SnackbarUtils.alertError({ id: "cannotUploadFilesKudoNotEnoughSpace" });
      return;
    }
  }

  const duplicates = uploadTree[currentFolderId].children.filter(({ data }) =>
    FilesListStore.isInFolder(currentFolderId, data.item.name, data.type)
  );

  if (
    duplicates.length &&
    uploadTree[currentFolderId].children.length === duplicates.length
  ) {
    SnackbarUtils.alertError({
      id: "duplicateNameUpload",
      duplicates: duplicates.map(obj => obj.data.item.name).join("\r\n")
    });
    return;
  }

  const uploadQueue = uploadTree[currentFolderId].children.filter(
    ({ data }) =>
      !FilesListStore.isInFolder(currentFolderId, data.item.name, data.type)
  );

  const finalUploadLength = uploadQueue.length;
  const unsuccessfulUploads = [];
  const canceledUploads = [];
  let processingPromises = [];
  const noOfFilesToUpload = uploadQueue.filter(
    item => item.data.type === "file"
  ).length;
  const singleFileUpload = noOfFilesToUpload === 1 && finalUploadLength === 1; // single file upload
  if (singleFileUpload && totalFilesSize > MAX_SINGLE_FILE_UPLOAD_SIZE) {
    SnackbarUtils.alertError([{ id: "fileIsTooBigForUpload", size: "150 Mb" }]);
    return;
  }
  const updateUsageNow = singleFileUpload;
  let uploadRequestId;
  if (!updateUsageNow && storageType === "SF") {
    uploadRequestId = await beginOrEndMultipleUploadRequest(true, null);
  }
  while (uploadQueue.length > 0) {
    processingPromises = processUploadQueue(
      uploadQueue,
      currentFolderId,
      uploadTree,
      unsuccessfulUploads,
      canceledUploads,
      updateUsageNow,
      uploadRequestId
    ).map(prom =>
      prom.then(v => {
        if (v) {
          uploadQueue.push(...v);
        }
      })
    );
    // eslint-disable-next-line no-await-in-loop
    await Promise.all(processingPromises);
  }

  if (!updateUsageNow && uploadRequestId && storageType === "SF") {
    await beginOrEndMultipleUploadRequest(false, uploadRequestId);
  }

  const directChildren = uploadTree[currentFolderId].children;
  const directFilesChildren = directChildren.filter(
    child => child.data.type === "file"
  ).length;

  const directChildFoldersStats = directChildren
    .filter(child => child.data.type === "folder")
    .map(child => child.data.stats);

  const entitiesCounter = {
    files: directFilesChildren,
    folders: directChildFoldersStats.length
  };
  directChildFoldersStats.forEach(stat => {
    entitiesCounter.files += stat.files;
    entitiesCounter.folders += stat.folders;
  });

  let messageEntityType = "";
  if (entitiesCounter.files > 0 && entitiesCounter.folders > 0) {
    messageEntityType = "entities";
  } else if (entitiesCounter.files > 1) {
    messageEntityType = "files";
  } else if (entitiesCounter.folders > 1) {
    messageEntityType = "folders";
  } else if (entitiesCounter.files === 1) {
    messageEntityType = "file";
  } else if (entitiesCounter.folders === 1) {
    messageEntityType = "folder";
  }
  if (unsuccessfulUploads.length > 0) {
    const uploadErrors = getUploadErrors(unsuccessfulUploads, canceledUploads);
    if (uploadErrors && uploadErrors.length > 0) {
      if (
        unsuccessfulUploads.length ===
        entitiesCounter.files + entitiesCounter.folders
      ) {
        SnackbarUtils.alertError(uploadErrors);
      } else {
        SnackbarUtils.alertWarning(uploadErrors);
      }
    }
  } else if (messageEntityType !== "" && finalUploadLength > 0) {
    if (duplicates.length) {
      SnackbarUtils.alertWarning([
        {
          id: "notAllEntitiesWereUploaded",
          type: messageEntityType
        },
        {
          id: "duplicateNameUpload",
          duplicates: duplicates.map(obj => obj.data.item.name).join("\r\n")
        }
      ]);
    } else if (skippedUploads.length > 0) {
      const skippedUploadsError = [
        {
          id: "fileIsTooBigForUpload",
          size: "150 Mb"
        }
      ];
      const skippedFiles = [];
      skippedUploads.forEach(file => {
        skippedFiles.push(
          `- ${file.name || "Unknown"} | size: ${Math.floor(
            file.size / (1024 * 1024)
          )} mb\n`
        );
      });
      skippedUploadsError.push({
        id: "largeFilesNotUploaded",
        skippedFiles
      });
      SnackbarUtils.alertWarning(skippedUploadsError);
    } else {
      SnackbarUtils.alertOk({
        id:
          entitiesCounter.files + entitiesCounter.folders === 1
            ? "successfulUploadSingle"
            : "successfulUploadMultiple",
        type: messageEntityType
      });
    }
  }
  FilesListStore.emitEvent(UPLOAD_FINISHED);
}

function handleDownloadError(objects) {
  objects.forEach(o => {
    ProcessActions.modify(
      typeof o === "string" ? o : o.id,
      Processes.DOWNLOAD_FAILED
    );
  });
  setTimeout(() => {
    objects.forEach(o => {
      ProcessActions.end(typeof o === "string" ? o : o.id);
    });
  }, 2000);
}

// just to make sure we add only array buffers in the list not the object
function isArrayBuffer(response) {
  try {
    if (response.data !== undefined && response.data.byteLength > 0) {
      return true;
    }
  } catch (err) {
    // Ignore the error for json object
  }
  return false;
}

function addDataToBuffer(childResponse, buffers) {
  const updatedBuffers = [...buffers];
  const downloadedPart = childResponse.headers.get("downloadedPart");
  if (downloadedPart !== undefined && downloadedPart !== null) {
    updatedBuffers.splice(downloadedPart - 1, 0, childResponse.data);
  } else {
    updatedBuffers.push(childResponse.data);
  }
  return updatedBuffers;
}

function handleSubDownloads(
  storageType,
  headers,
  objects,
  zipName,
  downloadToken,
  isMultipleDownload,
  shortcutOriginalIds = []
) {
  let noOfConcurrentDownloads = 0;
  let partToDownload = 1;
  let isDownloadSuccess = false;
  let isFinalDownload = false; // used just in case if we don't send partToDownload or its value found to be 0 on server
  let errorOccurred = false;
  let buffers = [];
  headers.token = downloadToken;
  const intervalName = `downloadTimer_${downloadToken}`;
  let folderId;
  if (objects.length > 0) {
    if (!isMultipleDownload) {
      folderId = objects[0].id;
    }
  } else {
    SnackbarUtils.alertError({ id: "NoItemsToDownload" });
    return;
  }
  window[intervalName] = setInterval(() => {
    if (
      noOfConcurrentDownloads >= 0 &&
      noOfConcurrentDownloads < 2 &&
      !isDownloadSuccess &&
      !isFinalDownload
    ) {
      const requestPromise = new Promise((resolve, reject) => {
        let timeout = 0;
        if (noOfConcurrentDownloads === 1) {
          timeout = 150;
        }
        setTimeout(() => {
          noOfConcurrentDownloads += 1;
          headers.partToDownload = partToDownload;
          partToDownload += 1;
          newRequests
            .sendGenericRequest(
              isMultipleDownload
                ? `/files/download`
                : `/folders/${folderId}/download`,
              isMultipleDownload ? RequestsMethods.POST : RequestsMethods.GET,
              headers,
              undefined,
              ["*"]
            )
            .then(childResponse => {
              if (errorOccurred) {
                resolve();
              }
              if (childResponse.code === 200) {
                resolve(childResponse);
                isDownloadSuccess = true;
              } else if (childResponse.code % 100 !== 2) {
                if (!isMultipleDownload) {
                  const { _id: currentFolderId } = currentFolder;
                  const { objectId } =
                    MainFunctions.parseObjectId(currentFolderId);
                  const { storageId } = FilesListStore.findCurrentStorage();
                  const currentElement = filesTree[storageType][storageId][
                    mode
                  ][objectId].content.find(elem => elem.id === folderId);
                  reject(
                    JSON.stringify({
                      id: "unsuccessfulDownload",
                      name: currentElement.name,
                      type: "folder"
                    })
                  );
                } else {
                  reject(
                    JSON.stringify({
                      id: "unsuccessfulMultipleDownload"
                    })
                  );
                }
              } else {
                resolve(childResponse);
              }
            })
            .catch(childResponse => {
              if (childResponse.code !== 200) {
                reject(childResponse.text || childResponse.data);
              }
            });
        }, timeout);
      });
      requestPromise
        .then(result => {
          if (result !== undefined) {
            if (isArrayBuffer(result)) {
              const finalDownload = result.headers.get("finalDownload");
              if (finalDownload !== undefined && finalDownload !== null) {
                isFinalDownload = true;
              }
              buffers = addDataToBuffer(result, buffers);
            } else {
              partToDownload -= 1;
            }

            ["Large", "NotFound"].forEach(reason => {
              const key = `excluded${reason}Files`;
              const tempFiles = result.headers.get(key);

              if (tempFiles !== undefined && tempFiles !== null) {
                const excludedFiles = JSON.parse(tempFiles);
                let excludedNames = "";
                excludedFiles.forEach(name => {
                  excludedNames += `\n"${name}"`;
                });
                SnackbarUtils.alertWarning({
                  id: key,
                  names: excludedNames
                });
              }
            });
          }
          noOfConcurrentDownloads -= 1;
        })
        .catch(err => {
          errorOccurred = true;
          let error;
          if (typeof err === "object") {
            error = JSON.parse(err);
          } else {
            error = err;
          }
          SnackbarUtils.alertError(error);
          handleDownloadError(objects);
          endProcessInterval(intervalName);
        });
    }
    setTimeout(() => {
      if (
        (noOfConcurrentDownloads === 0 && isDownloadSuccess === true) ||
        isFinalDownload
      ) {
        const blob = new Blob(buffers, {
          type: "application/zip"
        });
        MainFunctions.downloadBlobAsFile(blob, zipName);
        endProcessInterval(intervalName);
        if (shortcutOriginalIds && shortcutOriginalIds.length) {
          shortcutOriginalIds.forEach(sId => ProcessActions.end(sId));
        }
        objects.forEach(object => {
          ProcessActions.end(object.id);
        });
      }
    }, 300);
  }, 2900);
}

/**
 * @private
 * @param objectId
 * @param type
 * @param name
 * @param onDownloadStart
 * @param onDownloadEnd
 * @param triggerEvent
 */
function downloadObject(
  objectId,
  type,
  name,
  onDownloadStart,
  onDownloadEnd,
  triggerEvent
) {
  modifyEntity(objectId, {
    processId: objectId
  });
  if (triggerEvent)
    FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
  setTimeout(() => {
    ProcessActions.start(objectId, Processes.DOWNLOADING);
  }, 0);
  if (onDownloadStart && _.isFunction(onDownloadStart)) {
    onDownloadStart();
  }
  if (type === "file") {
    newRequests
      .sendGenericRequest(
        `/files/${objectId}/data`,
        RequestsMethods.GET,
        newRequests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
      .then(response => {
        const mimeType = mime.getType(name);
        const blob = new Blob([response.data], { type: mimeType });
        MainFunctions.downloadBlobAsFile(blob, name);
        ProcessActions.end(objectId);
      })
      .catch(response => {
        if (response.code !== 200) {
          SnackbarUtils.alertError(
            String.fromCharCode.apply(null, new Uint8Array(response.data))
          );
          handleDownloadError([objectId]);
        }
      })
      .finally(() => {
        if (onDownloadEnd && _.isFunction(onDownloadEnd)) {
          onDownloadEnd();
        }
      });
    // if (TableStore.getRowInfo(TableStore.getFocusedTable(), objectId)) {
    // }
  } else {
    const zipName = `${name}.zip`;
    const headers = newRequests.getDefaultUserHeaders();
    const { storageType } = FilesListStore.findCurrentStorage();
    headers.storageType = storageType;
    newRequests
      .sendGenericRequest(
        `/folders/${objectId}/download`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
      .then(response => {
        const mainData = response.data;
        const downloadToken = mainData._id;
        const objects = [{ id: objectId }];
        handleSubDownloads(
          storageType,
          headers,
          objects,
          zipName,
          downloadToken,
          false
        );
      })
      .catch(response => {
        if (response.code !== 200) {
          SnackbarUtils.alertError(response.text || response.data);
          handleDownloadError([objectId]);
        }
      });
  }
}

/**
 * @private
 * @param objectId
 * @param shortcutOriginalId
 * @param type
 * @param name
 * @param onDownloadStart
 * @param onDownloadEnd
 * @param triggerEvent
 */
function downloadShortcut(
  objectId,
  shortcutOriginalId,
  type,
  name,
  onDownloadStart,
  onDownloadEnd,
  triggerEvent
) {
  modifyEntity(shortcutOriginalId, {
    processId: shortcutOriginalId
  });
  if (triggerEvent)
    FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
  setTimeout(() => {
    ProcessActions.start(shortcutOriginalId, Processes.DOWNLOADING);
  }, 0);
  if (onDownloadStart && _.isFunction(onDownloadStart)) {
    onDownloadStart();
  }
  if (type === "file") {
    newRequests
      .sendGenericRequest(
        `/files/${objectId}/data`,
        RequestsMethods.GET,
        newRequests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
      .then(response => {
        const mimeType = mime.getType(name);
        const blob = new Blob([response.data], { type: mimeType });
        MainFunctions.downloadBlobAsFile(blob, name);
        ProcessActions.end(shortcutOriginalId);
      })
      .catch(response => {
        if (response.code !== 200) {
          SnackbarUtils.alertError({ id: "CantDownloadBrokenShortcut" });
          handleDownloadError([shortcutOriginalId]);
        }
      })
      .finally(() => {
        if (onDownloadEnd && _.isFunction(onDownloadEnd)) {
          onDownloadEnd();
        }
      });
    // if (TableStore.getRowInfo(TableStore.getFocusedTable(), objectId)) {
    // }
  } else {
    const zipName = `${name}.zip`;
    const headers = newRequests.getDefaultUserHeaders();
    const { storageType } = FilesListStore.findCurrentStorage();
    headers.storageType = storageType;
    newRequests
      .sendGenericRequest(
        `/folders/${objectId}/download`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
      .then(response => {
        const mainData = response.data;
        const downloadToken = mainData._id;
        const objects = [{ id: objectId }];
        handleSubDownloads(
          storageType,
          headers,
          objects,
          zipName,
          downloadToken,
          false,
          [shortcutOriginalId]
        );
      })
      .catch(response => {
        if (response.code !== 200) {
          SnackbarUtils.alertError(response.text || response.data);
          handleDownloadError([objectId]);
        }
      });
  }
}

/**
 * @private
 * @param objects
 */
function downloadObjects(objects) {
  const parentFolderId = currentFolder._id;
  const headers = newRequests.getDefaultUserHeaders();
  const { storageType } = FilesListStore.findCurrentStorage();
  let possibleFolderName = FilesListStore.getCurrentFolder().name || "";
  if (possibleFolderName.length === 0 || possibleFolderName === "~") {
    // https://graebert.atlassian.net/browse/XENON-66672
    possibleFolderName = "";
  } else {
    if (possibleFolderName.length > 50) {
      possibleFolderName = possibleFolderName.substring(0, 50);
    }
    // prepend with _ to split with storage name
    // https://graebert.atlassian.net/browse/XENON-66995
    possibleFolderName = `_${possibleFolderName}`;
  }
  const zipName = `${
    MainFunctions.serviceStorageNameToEndUser(
      MainFunctions.storageCodeToServiceName(storageType)
    ) || "ARES Kudo Drive"
  }${possibleFolderName}_selected.zip`;
  headers.folderId = parentFolderId;
  headers.storageType = storageType;
  objects.forEach(object => {
    ProcessActions.start(
      object.shortcutOriginalId || object.id,
      Processes.DOWNLOADING
    );
    modifyEntity(object.shortcutOriginalId || object.id, {
      processId: object.shortcutOriginalId || object.id
    });
  });
  const downloads = objects.map(object => ({
    id: object.id,
    objectType: object.type
  }));
  const requestBody = { downloads };
  newRequests
    .sendGenericRequest(
      `/files/download`,
      RequestsMethods.POST,
      headers,
      requestBody,
      ["*"]
    )
    .then(response => {
      const mainData = response.data;
      const downloadToken = mainData._id;
      handleSubDownloads(
        storageType,
        headers,
        objects,
        zipName,
        downloadToken,
        true,
        objects.map(o => o.shortcutOriginalId)
      );
    })
    .catch(response => {
      if (response.code !== 200) {
        SnackbarUtils.alertError(response.text || response.data);
        handleDownloadError(objects);
      }
    });
}

/**
 * @private
 * @param objectId
 * @param type
 * @param name
 */
function cloneObject(
  objectId,
  type,
  copyName,
  doCopyShare,
  doCopyComments,
  doIncludeResolved,
  doIncludeDeleted
) {
  const { _id: currentFolderId, accountId, storage } = currentFolder;
  const cloneId = MainFunctions.guid();

  const entityToClone = filesTree[storage][accountId][mode][
    MainFunctions.parseObjectId(currentFolderId).objectId
  ].content.find(e => e._id === objectId);

  addEntity({
    name: copyName,
    type,
    id: cloneId,
    processId: cloneId,
    isShortcut: entityToClone.isShortcut,
    ...(entityToClone.isShortcut
      ? { shortcutInfo: entityToClone.shortcutInfo }
      : {})
  });
  FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
  setTimeout(() => {
    ProcessActions.start(cloneId, Processes.CLONING, cloneId);
  }, 0);

  const requestBody = { [`${type}Name`]: copyName };
  if (type === "file") {
    requestBody.copyComments = doCopyComments;
    requestBody.includeResolvedComments = doIncludeResolved;
    requestBody.includeDeletedComments = doIncludeDeleted;
  }
  requestBody.copyShare = doCopyShare;

  newRequests
    .sendGenericRequest(
      `/${type}s/${objectId}/clone`,
      RequestsMethods.POST,
      newRequests.getDefaultUserHeaders(),
      requestBody,
      ["*"]
    )
    .then(response => {
      const { data } = response;
      const { storageId, storageType } = MainFunctions.parseObjectId(objectId);
      const { objectId: elementId } = MainFunctions.parseObjectId(
        data.folderId || data.fileId || data.id
      );
      modifyEntity(cloneId, {
        id: `${storageType}+${storageId}+${elementId}`,
        _id: `${storageType}+${storageId}+${elementId}`
      });
      ProcessActions.end(cloneId);
      FilesListActions.updateEntityInfo(
        data.folderId || data.fileId,
        `${type}s`
      ).then(() => {
        ProcessActions.end(cloneId);
        addItemToContentCache(
          currentFolderId,
          storageType,
          storageId,
          elementId
        );
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      });
    })
    .catch(response => {
      if (response.code !== 200) {
        SnackbarUtils.alertError(response.text || response.data);
      }
    });
}

/**
 * @private
 * @param objectId
 * @param type
 * @param shortcutName
 * @param createInCurrentFolder - means that shortcut cant be created in current folder and should be created in root
 * @param resolve - action resolver
 * @param reject <string> - action rejection
 */
function createShortcut(
  objectId,
  type,
  shortcutName,
  createInCurrentFolder = true,
  resolve,
  reject
) {
  const { _id: currentFolderId } = currentFolder;
  const tempShortcutId = MainFunctions.guid();

  const addShortcutEntity = (entityId, withProcess) => {
    addEntity({
      name: shortcutName,
      type: "file",
      isShortcut: true,
      shortcutInfo: {
        mimeType: ``,
        type,
        targetId: objectId
      },
      id: entityId,
      _id: entityId,
      ...(withProcess ? { processId: tempShortcutId } : {})
    });
  };

  if (createInCurrentFolder) {
    addShortcutEntity(tempShortcutId, true);
    FilesListStore.emit(CONTENT_UPDATED, currentFolderId, mode);

    setTimeout(() => {
      ProcessActions.start(
        tempShortcutId,
        Processes.CREATING_SHORTCUT,
        tempShortcutId
      );
    }, 0);
  }

  try {
    newRequests
      .sendGenericRequest(
        `/${type}s/${objectId}/shortcut`,
        RequestsMethods.POST,
        newRequests.getDefaultUserHeaders(),
        { name: shortcutName, createInCurrentFolder },
        [200]
      )
      .then(response => {
        const { data } = response;
        const { storageId, storageType } =
          MainFunctions.parseObjectId(objectId);
        const { objectId: elementId } = MainFunctions.parseObjectId(data.id);

        const {
          objectId: parentObjectId,
          storageId: parentStorageId,
          storageType: parentStorageType
        } = MainFunctions.parseObjectId(data.parentId);

        if (createInCurrentFolder || data.parentId === currentFolderId) {
          if (createInCurrentFolder) {
            modifyEntity(tempShortcutId, {
              id: `${storageType}+${storageId}+${elementId}`,
              _id: `${storageType}+${storageId}+${elementId}`
            });
          } else {
            addShortcutEntity(tempShortcutId, true);
          }

          FilesListActions.updateEntityInfo(data.id, "files").then(() => {
            if (createInCurrentFolder) {
              ProcessActions.end(tempShortcutId);
            }

            addItemToContentCache(
              currentFolderId,
              storageType,
              storageId,
              elementId
            );
            FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
          });
        } else {
          const parentFolderUrl = `${ApplicationStore.getApplicationSetting(
            "UIPrefix"
          )}files/${parentStorageType}/${parentStorageId}/${parentObjectId}`;

          SnackbarUtils.alertOk({
            id: "shortcutCreatedInRootFolder",
            link: msg => IntlTagValues.link(msg, parentFolderUrl),
            strong: IntlTagValues.strong,
            folderName: "My Drive"
          });
        }

        resolve();
      })
      .catch(data => {
        if (data.status !== "ok") {
          ProcessActions.end(tempShortcutId);
          if (data.statusCode !== 200) {
            SnackbarUtils.alertError(data.error);
            reject(data.error);
          } else {
            SnackbarUtils.alertError(data.status);
            reject(data.status);
          }
        }
      });
  } catch (error) {
    SnackbarUtils.alertError(error.message);
    reject(error.message);
  }
}

function restoreRecentFile() {
  if (lastRemovedRecentData) {
    newRequests
      .sendGenericRequest(
        `/files/recent/restore`,
        RequestsMethods.PUT,
        newRequests.getDefaultUserHeaders(),
        lastRemovedRecentData,
        ["*"]
      )
      .then(() => {
        SnackbarUtils.alertOk({ id: "successfullyRestoredLastFilePreview" });
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
      })
      .finally(() => {
        FilesListStore.saveLastRemovedRecentData(null);
      });
  } else {
    SnackbarUtils.alertError({ id: "couldNotRestoreFilePreview" });
  }
}

// Register callback to handle all updates
FilesListStore.dispatcherIndex = AppDispatcher.register(action => {
  switch (action.actionType) {
    case FilesListConstants.GET_FOLDER_CONTENT:
      FilesListStore.fetchFolderContent(
        action.storage,
        action.accountId,
        action.folderId,
        action.fileFilter,
        {
          isIsolated: action.isIsolated,
          recursive: action.recursive,
          usePageToken: action.usePageToken
        }
      );
      FilesListStore.emit(FilesListConstants.GET_FOLDER_CONTENT);
      break;
    case FilesListConstants.GET_TRASH_FILES_COUNT: {
      FilesListStore.emit(FilesListConstants.GET_TRASH_FILES_COUNT);
      FilesListStore.fetchTrashFilesCount();
      break;
    }
    case FilesListConstants.GET_OBJECT_PATH_SUCCESS:
      savePath(action.path);
      // no need to emit. savePath will emit once done
      break;
    case FilesListConstants.RELOAD_LIST:
      FilesListStore.emitChange();
      break;
    case FilesListConstants.SAVE_FILES:
      saveFiles(action.results);
      break;
    case FilesListConstants.TOGGLE_VIEW:
      toggleMode(
        action.name,
        action.target,
        action.viewOnly,
        action.type,
        action.historyUpdate
      );
      FilesListStore.emitChange();
      break;
    case FilesListConstants.DELETE_OBJECTS:
      // apetrenko: This code will cancel all pending uploads to all folders
      // passed to delete endpoint. Btw server won't allow to
      // upload files to deleted or non-existent folders
      try {
        (action.ids.folders || []).forEach(id => {
          Object.values(uploads)
            // collect all pending uploads to this parent
            .filter(u => u.uploadToParentId === id && !u.isCanceled)
            // reformat them to string[] array of uploadIds
            .reduce((tempIds, u) => [...tempIds, ...(u.uploads || [])], [])
            // cancel each such upload
            .forEach(uploadId => cancelEntityUpload(uploadId));
        });
      } catch (error) {
        Logger.addEntry(
          "ERROR",
          `[Processes] error occured while trying to cancel uploads to \n${action.ids.folders} \nfolders`
        );
      }

      break;
    case FilesListConstants.DELETE_OBJECTS_SUCCESS:
      removeEntitiesFromCache(action.ids.files.concat(action.ids.folders));
      FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.ERASE_OBJECTS_SUCCESS:
      removeEntitiesFromCache(action.ids.files.concat(action.ids.folders));
      FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.RESTORE_OBJECTS_SUCCESS: {
      const ids = action.restore.map(item => item.id);
      removeEntitiesFromCache(ids);
      break;
    }
    case FilesListConstants.MOVE_OBJECTS:
      moveToFolder(action.targetId, action.entities);
      break;
    case FilesListConstants.RESTORE_LAST_REMOVED_RECENT_FILE:
      restoreRecentFile();
      break;
    case FilesListConstants.MAKE_PATH:
      savePath(action.path);
      FilesListStore.emitChange();
      break;
    case FilesListConstants.UPDATE_ENTITY_SUCCESS:
      updateEntity(action.data, action.forceType);
      if (action.fireEvent)
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.UPDATE_ENTITY_FAIL:
      // eslint-disable-next-line no-console
      console.error(
        `Error on get entity info for entity (storage: ${UserInfoStore.getUserInfo(
          "storage"
        ).type.toLowerCase()}, id:${action.entityId})`
      );

      // if (
      //   action.error.code !== 403 &&
      //   UserInfoStore.getUserInfo("storage").type.toLowerCase() !== "gdrive"
      // ) {
      //   SnackbarUtils.alertError(action.error.data.message);
      // }
      break;
    case FilesListConstants.GET_OBJECT_INFO_SUCCESS:
      if (
        action.type === "file" &&
        (currentFile.id || currentFile._id || "").length > 0 &&
        MainFunctions.areObjectIdsEqual(
          action.id,
          currentFile.id || currentFile._id
        )
      ) {
        FilesListStore.saveCurrentFile(action.info, true);
        FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      } else if (
        action.id === "-1" ||
        action.id ===
          getFullObjectId(
            currentFolder._id || "",
            currentFolder.storage || "",
            currentFolder.accountId || "",
            currentFolder.storage || ""
          )
      ) {
        FilesListStore.updateCurrentFolder(action.info);
        FilesListStore.emit(CURRENT_FOLDER_INFO_UPDATED);
        // TODO: remove
        FilesListStore.emitChange(); // backward compatibility
      } else if (action.type === "folder") {
        // I.A: XENON-34422 on sharing/unsharing folder we should update not current folder
        updateEntity(action.info);
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      }

      break;
    case FilesListConstants.UPLOAD_ENTITIES:
      uploadEntities(action.entities);
      break;
    case FilesListConstants.CANCEL_ENTITY_UPLOAD:
      cancelEntityUpload(action.uploadId);
      SnackbarUtils.alertInfo({ id: "uploadCanceled" });
      break;
    case FilesListConstants.SAVE_FILE_INFO:
      FilesListStore.saveCurrentFile(action.file);
      FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      FilesListStore.emitChange();
      break;
    case FilesListConstants.SAVE_FILE_VIEW_FLAG:
      FilesListStore.setCurrentFileViewFlag(action.viewFlag);
      FilesListStore.emitChange();
      break;
    case FilesListConstants.CHANGE_XREF_FOLDER:
      if (action.folderId !== "") {
        FilesListStore.emitXrefChangeFolder(
          action.folderName,
          action.folderId,
          { viewOnly: action.viewOnly }
        );
      }
      break;
    case FilesListConstants.SAVE_INITIAL_VIEWONLY:
      currentFile.initialViewOnly = action.viewOnly;
      FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      break;
    case FilesListConstants.SAVE_FILE_DIRECT_ACCESS_FLAG:
      currentFile.hasDirectAccess = action.hasDirectAccess;
      FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      break;
    case FilesListConstants.RESET_FILE_INFO:
      FilesListStore.resetCurrentFileInfo();
      FilesListStore.emitChange();
      break;
    case FilesListConstants.RELOAD_DRAWING:
      currentFile.viewingLatestVersion = true;
      currentFile.viewFlag = action.viewOnly;
      currentFile.viewOnly = action.viewOnly;
      FilesListStore.emit(
        DRAWING_RELOAD,
        action.viewOnly,
        action.forced,
        action.isSessionUpdateRequired
      );
      break;
    case FilesListConstants.SAVE_DRAWING_SESSION_ID:
      FilesListStore.saveDrawingSessionId(action.id);
      FilesListStore.emitChange();
      break;
    case FilesListConstants.SAVE_DOWNGRADE_SESSION_INFO:
      FilesListStore.saveDowngradeSessionInfo(action.info);
      FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      break;
    case FilesListConstants.CURRENT_FILE_DESHARED:
      FilesListStore.setCurrentFileDeshared();
      FilesListStore.emit(CURRENT_FILE_DESHARED);
      break;
    case FilesListConstants.SAVE_EDITING_USER_ID:
      FilesListStore.saveEditingUserId(action.userId);
      FilesListStore.emitChange();
      break;
    case FilesListConstants.SAVE_LAST_REMOVED_RECENT_DATA:
      FilesListStore.saveLastRemovedRecentData(action.recentData);
      break;
    case FilesListConstants.LOAD_RECENT_FILES_SUCCESS:
      FilesListStore.saveRecentFiles(action.recentFiles);
      FilesListStore.emit(RECENT_FILES_LOAD);
      break;
    case FilesListConstants.RECENT_FILE_REMOVED:
      FilesListStore.removeRecentFile(action.fileId);
      FilesListStore.emit(RECENT_FILES_LOAD);
      break;
    case FilesListConstants.SEARCH_SUCCESS:
      FilesListStore.saveSearchResults(action.storage, action.data);
      FilesListStore.emit(SEARCH, action.storage);
      break;
    case FilesListConstants.UPDATE_PATH: {
      FilesListStore.updatePath(action.targetId, action.name);
      FilesListStore.emit(PATH_LOADED);
      break;
    }
    case FilesListConstants.RESET_PATH: {
      folderPath = [
        {
          _id: "-1",
          name: "~",
          storage: null,
          accountId: null,
          mode: BROWSER,
          viewOnly: false,
          full: true
        }
      ];
      FilesListStore.emit(PATH_LOADED);
      break;
    }
    case FilesListConstants.DOWNLOAD_OBJECTS: {
      if (!action.objects || !action.objects.length) return;

      const { objects } = action;

      // Single object download
      if (objects.length === 1) {
        const [firstObject] = objects;

        // Download single shortcut file
        if (firstObject.isShortcut) {
          downloadShortcut(
            firstObject.shortcutInfo.targetId,
            firstObject.id || firstObject._id,
            firstObject.shortcutInfo.type,
            MainFunctions.getShortcutDownloadName(firstObject),
            firstObject.onDownloadStart,
            firstObject.onDownloadEnd,
            !location.pathname.includes("search")
          );
        }

        // Download single regular file
        else {
          downloadObject(
            firstObject.id,
            firstObject.type,
            firstObject.name || firstObject.filename,
            firstObject.onDownloadStart,
            firstObject.onDownloadEnd,
            !location.pathname.includes("search")
          );
        }
      }

      // Multiple object zip-download
      else if (objects.length > 1) {
        // Cast shortcuts to special files entities containing
        // required target info if they present in objects
        downloadObjects(
          objects.map(o =>
            o.isShortcut
              ? {
                  ...o,
                  shortcutOriginalId: o.id || o._id,
                  id: o.shortcutInfo.targetId,
                  _id: o.shortcutInfo.targetId,
                  type: o.shortcutInfo.type
                }
              : o
          )
        );
      }
      break;
    }
    case FilesListConstants.CLONE_OBJECT:
      cloneObject(
        action.objectId,
        action.type,
        action.name,
        action.doCopyShare,
        action.doCopyComments,
        action.doIncludeResolved,
        action.doIncludeDeleted
      );
      break;
    case FilesListConstants.CREATE_SHORTCUT:
      createShortcut(
        action.objectId,
        action.type,
        action.name,
        action.createInCurrentFolder,
        action.resolve,
        action.reject
      );
      break;
    case FilesListConstants.ADD_ENTITY:
      // check if entity was added, fire content updated message
      if (addEntity(action.entityData) && action.fireEvent)
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.MODIFY_ENTITY:
      modifyEntity(
        action.entityId,
        action.newEntityData,
        undefined,
        action.omitProperties
      );
      if (action.fireEvent)
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.DELETE_ENTITY:
      deleteEntity(action.entityId);
      if (action.fireEvent)
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.CHECK_NEW_FILES_PAGE:
      FilesListStore.emit(CHECK_NEW_FILES_PAGE);
      break;
    case FilesListConstants.CLEAR_TRASH_ENTITIES:
      deleteTrashEntities();
      if (action.fireEvent)
        FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      break;
    case FilesListConstants.DELETE_RECENT_ENTITIES:
      action.entities.forEach(entityId => {
        FilesListStore.removeRecentFile(entityId);
      });
      FilesListStore.emit(RECENT_FILES_LOAD);
      break;
    case FilesListConstants.ENTITY_RENAME_MODE:
      if (action.mode === true) {
        ProcessActions.start(
          action.entityId,
          Processes.RENAME,
          action.entityId
        );

        const renameProcesses = ProcessStore.getAllProcessesByType("rename");

        renameProcesses.forEach(process => {
          ProcessActions.end(process.id);
          FilesListStore.emit(`${ENTITY_RENAME_MODE}${process.owner}`, false);
        });
      } else {
        ProcessActions.end(action.entityId);
      }
      FilesListStore.emit(
        `${ENTITY_RENAME_MODE}${action.entityId}`,
        action.mode
      );
      break;
    case FilesListConstants.UPDATE_ENTITY_NAME_SUCCESS: {
      // if renaming was successful save changed entity in cache
      const { _id: currentFolderId } = currentFolder;
      const { storageId, storageType } = FilesListStore.findCurrentStorage();
      const { objectId } = MainFunctions.parseObjectId(currentFolderId);

      if (_.isEmpty(filesTree)) break;

      const currentElement = filesTree[storageType][storageId][mode]
        ? filesTree[storageType][storageId][mode][objectId].content.find(
            elem => elem.id === action.objectId
          )
        : null;

      if (!currentElement) break;
      FilesListStore.cacheObjectInfo(currentElement);
      break;
    }
    case FilesListConstants.CREATE_FOLDER:
    case FilesListConstants.CREATE_FILE_FROM_TEMPLATE: {
      const newId = MainFunctions.guid();

      addEntity({
        name: action.name,
        parentId: action.parentId,
        type:
          action.actionType === FilesListConstants.CREATE_FOLDER
            ? "folder"
            : "file",
        id: newId,
        _id: newId,
        processId: newId
      });
      FilesListStore.emit(CONTENT_UPDATED, currentFolder._id, mode);
      setTimeout(() => {
        ProcessActions.start(newId, Processes.CREATING, newId);
        FilesListStore.emit(SORT_REQUIRED, currentFolder._id, mode);
      }, 0);

      action.resolve(newId);
      break;
    }
    case FilesListConstants.CREATE_FOLDER_SUCCESS: {
      const { folderId: newFolderId } = action.response.data;
      const entity = findEntityById(action.tempId);
      if (entity) {
        modifyEntity(entity.id, {
          id: newFolderId,
          _id: newFolderId
        });
        ProcessActions.start(
          newFolderId,
          Processes.CREATE_COMPLETED,
          newFolderId
        );

        FilesListActions.updateEntityInfo(newFolderId, "folders").catch(err => {
          SnackbarUtils.alertError(err.text);
        });

        const { storageId, storageType, objectId } =
          MainFunctions.parseObjectId(newFolderId);
        addItemToContentCache(
          currentFolder._id,
          storageType,
          storageId,
          objectId
        );

        FilesListStore.emit(SORT_REQUIRED, currentFolder._id, mode);
        setTimeout(() => {
          ProcessActions.end(newFolderId);
          ProcessActions.end(action.tempId);
          FilesListStore.emit(SORT_REQUIRED, currentFolder._id, mode);
        }, 5000);
      }
      break;
    }
    case FilesListConstants.CREATE_FOLDER_FAIL:
    case FilesListConstants.CREATE_FILE_FROM_TEMPLATE_FAIL: {
      const entities = findEntitiesByName(action.name);

      if (entities) {
        entities
          .filter(entity =>
            Object.prototype.hasOwnProperty.call(entity, "processId")
          )
          .map(entity => {
            ProcessActions.end(entity.processId);
            deleteEntity(entity.id || entity._id);
            return entity.parentId;
          })
          .filter((value, index, array) => array.indexOf(value) === index)
          .forEach(parentId =>
            FilesListStore.emit(CONTENT_UPDATED, parentId, mode)
          );
      }
      break;
    }
    case FilesListConstants.TAKE_EDIT_PERMISSION: {
      const fileId = currentFile._id;
      const xSessionId = currentFile.drawingSessionId;

      newRequests
        .sendGenericRequest(
          `/files/${encodeURIComponent(fileId)}/session`,
          RequestsMethods.GET,
          newRequests.getDefaultUserHeaders(),
          undefined,
          ["*"]
        )
        .then(response => {
          // check if user is still on file page
          if (!window.location.toString().includes("file")) {
            return;
          }

          // check if edit mode is still available
          if (
            response.data.results &&
            _.isArray(response.data.results) &&
            response.data.results.length > 0
          ) {
            const editors =
              _.filter(
                response.data.results,
                sessionInfo => sessionInfo.mode === "edit"
              ) || [];
            if (_.isArray(editors) && editors.length > 0) {
              SnackbarUtils.warning("editSessionIsBusyBy", {
                username: editors[0].username
              });
              return;
            }
          }

          // token is now redundant, replace window url
          if (MainFunctions.QueryString("token").length > 0) {
            ApplicationActions.changePage(
              `/file/${encodeURIComponent(fileId)}`
            );
          }

          // if we have xSession id stored - xenon is aware of it
          // else there is no xSession created - force will make xenon create new one
          FilesListActions.reloadDrawing(
            false,
            !(xSessionId && xSessionId.length),
            true
          );
        })
        .catch(() => {
          SnackbarUtils.error("InternalError");
        });

      break;
    }
    case FilesListConstants.CHECK_EDIT_PERMISSION: {
      const {
        _id,
        viewingLatestVersion,
        viewFlag = false,
        initialViewOnly = true
      } = currentFile;

      if (
        initialViewOnly || // file info response says its a viewOnly
        !viewFlag || // file should be opened for viewOnly
        UserInfoStore.isFreeAccount() || // need license
        !UserInfoStore.getUserInfo("options").editor || // should be a potential user
        MainFunctions.isMobileDevice() // mobile devices are not supported yet
      ) {
        return;
      }

      setTimeout(async () => {
        if (MainFunctions.detectPageType() !== "file") return;

        const response = await newRequests.sendGenericRequest(
          `/files/${encodeURIComponent(_id)}/session`,
          RequestsMethods.GET,
          newRequests.getDefaultUserHeaders(),
          undefined,
          ["*"]
        );

        if (
          response.data.results &&
          _.isArray(response.data.results) &&
          response.data.results.findIndex(session => session.mode === "edit") >
            -1
        ) {
          return;
        }

        SnackbarUtils.editAvailable(action.message, viewingLatestVersion);
      }, 5000);

      break;
    }
    case FilesListConstants.SAVE_FILE_IN_XENON: {
      const { viewFlag = true, isModified = false } = currentFile;

      if (viewFlag) {
        action.resolve();
        return;
      }

      if (isModified) {
        XenonConnectionActions.postMessage({ messageName: "SAVE" });
        FilesListStore.setCurrentFileIsModified(false);

        WebsocketActions.awaitForNewVersion()
          .then(() => action.resolve())
          .catch(() => action.reject());
      } else {
        action.resolve();
      }
      break;
    }
    case FilesListConstants.SET_VIEWING_LATEST_VERSION: {
      if (currentFile.viewFlag === true) {
        currentFile.viewingLatestVersion = action.isLatest;
      }
      break;
    }
    case FilesListConstants.SET_CONFLICTING_REASON: {
      currentFile.viewFlag = true;
      currentFile.conflictingReason = action.conflictingReason;
      FilesListStore.emit(CURRENT_FILE_INFO_UPDATED);
      break;
    }
    case FilesListConstants.GET_OBJECT_INFO_FAIL: {
      const { id, err } = action;
      if (currentFolder.storage && currentFolder.accountId) {
        const fullCurrentFolderId = MainFunctions.encapsulateObjectId(
          currentFolder.storage,
          currentFolder.accountId,
          currentFolder._id
        );
        if (id === fullCurrentFolderId && err.code === 404) {
          if ((err.text || "").length > 0) SnackbarUtils.alertError(err.text);
          ApplicationActions.changePage(
            `/files/${currentFolder.storage}/${currentFolder.accountId}/-1`
          );
        }
      }
      break;
    }
    default:
    // no op
  }
});

export default FilesListStore;
