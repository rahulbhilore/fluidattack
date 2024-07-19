import _ from "underscore";
import md5 from "md5";
import customProtocolCheck from "../libraries/CustomProtocolCheck";
import AppDispatcher from "../dispatcher/AppDispatcher";
import Requests from "../utils/Requests";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import * as FilesListConstants from "../constants/FilesListConstants";
import MainFunctions, { APPLE_DEVICE } from "../libraries/MainFunctions";
import ModalActions from "./ModalActions";
import { DELETE_ERRORS } from "../constants/appConstants/ErrorCodes";
import Processes from "../constants/appConstants/Processes";
import ProcessActions from "./ProcessActions";
import Storage from "../utils/Storage";
import FetchAbortsControl from "../utils/FetchAbortsControl";

const FilesListActions = {
  getObjectInfo(
    id,
    type,
    {
      isExternal = false,
      token = "",
      versionId = "",
      password = "",
      timeLimit = 0
    }
  ) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.GET_OBJECT_INFO,
      id,
      type
    });

    const { objectId } = MainFunctions.parseObjectId(id);

    return new Promise((resolve, reject) => {
      if (objectId === "-1" && type === "folder") {
        // if target folder is root - no need to get path from server
        AppDispatcher.dispatch({
          actionType: FilesListConstants.GET_OBJECT_INFO_SUCCESS,
          id,
          info: { name: "~", _id: id, viewOnly: false, full: true }
        });
        resolve({ name: "~", _id: id, viewOnly: false });
      } else {
        let headers = Requests.getDefaultUserHeaders();
        if (type === "file") {
          if (isExternal) {
            headers = undefined;
          } else if (token && token.length > 0) {
            // if token is passed - try to use it to authenticate
            headers.token = token;
            if (password && password.length > 0) {
              headers.password = password;
            }
          }
        }
        if (timeLimit && timeLimit > 0) {
          setTimeout(() => {
            reject(new Error("Time limit is over"));
          }, timeLimit);
        }

        const url =
          token.length && versionId.length
            ? `/files/${objectId}/versions/${versionId}/info?token=${token}`
            : `/${type}s/${id}/info`;

        Requests.sendGenericRequest(
          url,
          RequestsMethods.GET,
          headers,
          undefined,
          ["*"]
        )
          .then(response => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.GET_OBJECT_INFO_SUCCESS,
              info: response.data,
              id,
              type
            });
            resolve(response.data);
          })
          .catch(err => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.GET_OBJECT_INFO_FAIL,
              err,
              id,
              type
            });
            reject(err);
          });
      }
    });
  },

  /**
   * Move selected entities in trash.
   * @param objectId - id of entity
   * @param versionId - version id or revision
   * @param token - public token
   * @param password - password (if exists)
   * @returns {Promise<ClientFile>}
   */
  getLinkObjectInfo(objectId, versionId, token, password) {
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();

      if (password && password.length > 0) {
        headers.password = password;
      }

      Requests.sendGenericRequest(
        `/files/${objectId}/versions/${versionId}/info?token=${token}`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          resolve({
            ...response.data,
            name: response.data.filename || response.data.name
          });
        })
        .catch(err => {
          reject(err);
        });
    });
  },

  /**
   * Download raw data of link entity
   * @param objectId
   * @param versionId
   * @param token
   * @param asPdf
   * @param password
   * @returns {Promise<ArrayBuffer>}
   */
  async downloadLinkObject(objectId, versionId, token, asPdf, password) {
    const headers = Requests.getDefaultUserHeaders();

    if (password && password.length > 0) {
      headers.password = password;
    }

    if (asPdf) {
      const response = await Requests.sendGenericRequest(
        `/files/${objectId}/versions/${versionId}/data?token=${token}`,
        RequestsMethods.GET,
        {
          ...headers,
          format: "pdf"
        },
        undefined,
        ["*"]
      );

      if (response.code === 201) {
        const { downloadToken } = response.data;

        const promise = new Promise((resolve, reject) => {
          const interval = setInterval(async () => {
            const finalResponse = await Requests.sendGenericRequest(
              `/files/${objectId}/versions/${versionId}/data?token=${token}`,
              RequestsMethods.GET,
              {
                ...headers,
                downloadToken
              },
              undefined,
              ["*"]
            );

            const status = finalResponse.code;

            if (status === 200 || status === 202) {
              clearInterval(interval);
              resolve(finalResponse.data);
              return;
            }

            if (status !== 201) {
              clearInterval(interval);
              reject(new Error("Could not convert file"));
            }
          }, 3000);
        });

        try {
          return await promise;
        } catch (e) {
          throw new Error(e.message || e.error);
        }
      }

      throw new Error("Could not convert file to pdf");
    }

    const response = await Requests.sendGenericRequest(
      `/files/${objectId}/versions/${versionId}/data?token=${token}`,
      RequestsMethods.GET,
      headers,
      undefined,
      ["*"]
    );

    return response.data;
  },

  // TODO: seems it`s can be deleted
  reloadList() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RELOAD_LIST
    });
  },

  /**
   * Go to another xref folder
   * @param folderName {String}
   * @param folderId {String}
   * @param viewOnly {Boolean}
   * @param [historyUpdate] {Boolean} - whether browser history should be updated or not
   */
  changeXrefFolder(folderName, folderId, viewOnly) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CHANGE_XREF_FOLDER,
      folderId,
      folderName,
      viewOnly
    });
  },

  // TODO: seems it`s can be deleted
  saveCurrentFiles(results) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_FILES,
      results
    });
  },

  toggleView(name, targetId, viewOnly, pageType, historyUpdate) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.TOGGLE_VIEW,
      name,
      target: targetId,
      viewOnly,
      type: pageType,
      historyUpdate
    });
  },

  /**
   * Move selected entities in trash.
   * @param ids
   * @param isConfirmed
   * @returns {Promise<unknown>}
   */
  deleteSelected(ids = [], isConfirmed = false) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.DELETE_OBJECTS,
      isConfirmed,
      ids
    });
    return new Promise((resolve, reject) => {
      const requestHeaders = Requests.getDefaultUserHeaders();
      requestHeaders.confirmed = isConfirmed === true;
      if (_.isUndefined(ids) || ids.length === 0) {
        reject(
          new Error(
            DELETE_ERRORS.NO_IDS_SPECIFIED,
            "Ids of object to delete have to be specified!"
          )
        );
      }
      Requests.sendGenericRequest(
        "/trash",
        RequestsMethods.PUT,
        requestHeaders,
        ids,
        ["*"]
      )
        .then(response => {
          const { data } = response;
          if (data.status !== "ok") {
            if (isConfirmed !== true && data.statusCode === 412) {
              ModalActions.deleteObjectConfirmation(
                {
                  files: data.error.files
                },
                ids
              );
            } else {
              reject(
                new Error(DELETE_ERRORS.API_ERROR, "errorsDeletingEntities")
              );
            }
          } else {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.DELETE_OBJECTS_SUCCESS,
              ids
            });
            resolve();
          }
        })
        .catch(err => {
          if (isConfirmed !== true && err.code === 412 && err.data.files) {
            ModalActions.deleteObjectConfirmation(
              { files: err.data.files },
              ids
            );
          } else {
            reject(
              new Error(DELETE_ERRORS.API_ERROR, "errorsDeletingEntities")
            );
          }
        });
    });
  },

  /**
   * Erases selected entities from trash.
   * @param ids
   * @returns {Promise<unknown>}
   */
  eraseObjects(ids = []) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.ERASE_OBJECTS,
      ids
    });
    return new Promise((resolve, reject) => {
      if (_.isUndefined(ids) || ids.length === 0) {
        reject(
          new Error(
            DELETE_ERRORS.NO_IDS_SPECIFIED,
            "Ids of object to delete have to be specified!"
          )
        );
      }
      Requests.sendGenericRequest(
        "/erase",
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        ids,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.ERASE_OBJECTS_SUCCESS,
            ids,
            response
          });
          resolve(response);
        })
        .catch(() => {
          reject(new Error(DELETE_ERRORS.API_ERROR, "errorsErasingEntities"));
        });
    });
  },

  restoreRecentFile() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RESTORE_LAST_REMOVED_RECENT_FILE
    });
  },

  removeRecentPreview(fileId) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RECENT_FILE_REMOVED,
      fileId
    });
  },

  removeRecentFile(thumbnail, fileId, folderId, fileName, timestamp) {
    return new Promise((resolve, reject) => {
      let thumbnailName;
      if (thumbnail.includes("/png/")) {
        const initialPart = thumbnail.substring(0, thumbnail.indexOf("?"));
        thumbnailName = initialPart.substring(
          initialPart.lastIndexOf("/") + 1,
          initialPart.length
        );
      } else {
        thumbnailName = thumbnail;
      }
      FilesListActions.saveLastRemovedRecentData({
        fileId,
        fileName,
        thumbnailName,
        folderId,
        timestamp
      });
      Requests.sendGenericRequest(
        `/files/${fileId}/recent`,
        RequestsMethods.DELETE,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(() => {
          resolve();
        })
        .catch(err => {
          reject(err);
          this.loadRecentFiles();
        });
    });
  },

  restoreEntities(list) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RESTORE_OBJECTS,
      list
    });
    return new Promise((resolve, reject) => {
      const { false: restore, true: notRestore = [] } = _.groupBy(
        list,
        "cancelFlag"
      );

      restore.forEach(({ id }) => {
        ProcessActions.start(id, Processes.RESTORE);
      });

      // group entities by type
      const objectsByType = _.groupBy(restore, "type");

      const formatFunction = ({ oldName, name, id }) => {
        if (oldName !== name) {
          return { name, id };
        }
        return { id };
      };

      if (!objectsByType.file) {
        objectsByType.file = [];
      }
      if (!objectsByType.folder) {
        objectsByType.folder = [];
      }

      const formattedFiles = objectsByType.file.map(formatFunction);
      const formattedFolders = objectsByType.folder.map(formatFunction);

      if (objectsByType.file.length + objectsByType.folder.length > 0) {
        Requests.sendGenericRequest(
          "/restore",
          RequestsMethods.PUT,
          Requests.getDefaultUserHeaders(),
          {
            files: formattedFiles,
            folders: formattedFolders,
            namesIncluded: true
          },
          ["*"]
        )
          .then(response => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.RESTORE_OBJECTS_SUCCESS,
              restore,
              response
            });
            restore.forEach(({ id }) => {
              ProcessActions.end(id);
            });
            resolve(response);
          })
          .catch(err => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.RESTORE_OBJECTS_FAIL,
              restore,
              err
            });
            restore.forEach(({ id }) => {
              ProcessActions.end(id);
            });
            reject(err);
          });
      } else if (notRestore.length) {
        reject(
          new Error(
            JSON.stringify({
              id: "notRestoredBecauseCancelled",
              names: notRestore.map(({ oldName }) => oldName).join(",")
            })
          )
        );
      }
    });
  },

  /**
   * Move entities to folder
   * @param targetId {string} id of target folder
   * @param entities {Array} of entities id`s
   */
  moveSelected(targetId, entities) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.MOVE_OBJECTS,
      targetId,
      entities
    });
  },

  // TODO: Looks like it can be removed
  // moveObject(objectId, targetId) {
  //   AppDispatcher.dispatch({
  //     actionType: FilesListConstants.MOVE_OBJECT_TO_FOLDER,
  //     objectId,
  //     targetId
  //   });
  // },

  /**
   * Update info about entity
   * @param entityId
   * @param forceType
   * @param fireEvent
   * @returns {Promise<unknown>}
   */
  updateEntityInfo(entityId, forceType, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.UPDATE_ENTITY,
      entityId,
      forceType,
      fireEvent
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/${forceType}/${entityId}/info`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(answer => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.UPDATE_ENTITY_SUCCESS,
            data: answer.data,
            forceType,
            fireEvent
          });
          resolve(answer.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.UPDATE_ENTITY_FAIL,
            error: err,
            entityId,
            fireEvent
          });
          reject(err);
        });
    });
  },

  // TODO: Looks like it can be removed
  /**
   * Load objects (files/folders)
   * @param tableId {String}
   * @param [nextPage] {Boolean} if true - next page will be loaded.
   * Otherwise - objects will be loaded as usual
   * @param [offset] {Number} offset to scroll the table
   */
  // getObjects(tableId, nextPage, offset) {
  //   AppDispatcher.dispatch({
  //     actionType: FilesListConstants.GET_OBJECTS,
  //     tableId,
  //     nextPage,
  //     offset
  //   });
  // },

  /**
   * saves current file info to store
   * @param file {object} file info
   */
  saveFile(file) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_FILE_INFO,
      file
    });
  },

  /**
   * saves info about xenon access (view/edit)
   * @param viewFlag {boolean} true if viewer has been opened, false otherwise
   */
  saveFileViewFlag(viewFlag) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_FILE_VIEW_FLAG,
      viewFlag
    });
  },

  saveInitialViewOnly(viewOnly) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_INITIAL_VIEWONLY,
      viewOnly
    });
  },

  saveFileDirectAccessFlag(hasDirectAccess) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_FILE_DIRECT_ACCESS_FLAG,
      hasDirectAccess
    });
  },

  resetCurrentFileInfo() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RESET_FILE_INFO
    });
  },

  reloadDrawing(viewOnly, forced = false, isSessionUpdateRequired = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RELOAD_DRAWING,
      viewOnly,
      forced,
      isSessionUpdateRequired
    });
  },

  saveDrawingSessionId(id) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_DRAWING_SESSION_ID,
      id
    });
  },

  saveDowngradeSessionInfo(obj) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_DOWNGRADE_SESSION_INFO,
      info: obj
    });
  },

  saveEditingUserId(userId) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_EDITING_USER_ID,
      userId
    });
  },

  saveLastRemovedRecentData(recentData) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SAVE_LAST_REMOVED_RECENT_DATA,
      recentData
    });
  },

  loadRecentFiles() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.LOAD_RECENT_FILES
    });
    Requests.sendGenericRequest(
      "/files/recent",
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders(),
      undefined,
      ["*"]
    ).then(response => {
      // remove "broken" files
      const recentFiles = _.filter(
        response.data.result || [],
        file => file.fileId.length > 10
      );
      AppDispatcher.dispatch({
        actionType: FilesListConstants.LOAD_RECENT_FILES_SUCCESS,
        recentFiles
      });
    });
  },

  validateRecentFile(fileId) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.VALIDATE_RECENT_FILE,
      fileId
    });
    Requests.sendGenericRequest(
      `/files/recent/${fileId}/validate`,
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders(),
      undefined,
      ["*"]
    )
      .then(() => {
        // file is fine, no need to do anything special here
      })
      .catch(() => {
        AppDispatcher.dispatch({
          actionType: FilesListConstants.RECENT_FILE_REMOVED,
          fileId
        });
      });
  },

  search(query, storage) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SEARCH,
      query,
      storage
    });
    const headers = _.extend(Requests.getDefaultUserHeaders(), {
      query: encodeURIComponent(query.trim()),
      storageType: storage.toUpperCase()
    });
    Requests.sendGenericRequest(
      "/files/search",
      RequestsMethods.GET,
      headers,
      undefined,
      ["*"]
    )
      .then(response => {
        const data = response.data.result;
        AppDispatcher.dispatch({
          actionType: FilesListConstants.SEARCH_SUCCESS,
          storage,
          data
        });
      })
      .catch(() => {
        // ignore errors for search - https://graebert.atlassian.net/browse/XENON-30051
      });
  },

  loadPath(objectId, type, isIsolated = false) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.GET_OBJECT_PATH,
      objectId,
      type
    });
    const { objectId: folderId } = MainFunctions.parseObjectId(objectId);
    return new Promise((resolve, reject) => {
      if (folderId === "-1") {
        // if target folder is root - no need to get path from server
        const mockPath = [{ name: "~", _id: "-1", viewOnly: false }];
        if (!isIsolated) {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.GET_OBJECT_PATH_SUCCESS,
            path: mockPath
          });
        }
        resolve(mockPath);
      } else {
        Requests.sendGenericRequest(
          `/${type || "folder"}s/${objectId}/path`,
          RequestsMethods.GET,
          Requests.getDefaultUserHeaders(),
          undefined,
          ["*"]
        )
          .then(response => {
            const originalPath = response?.data?.result || [];
            const path = _.filter(
              originalPath.reverse(),
              pathEntry => pathEntry.server !== pathEntry._id
            );
            if (!isIsolated) {
              AppDispatcher.dispatch({
                actionType: FilesListConstants.GET_OBJECT_PATH_SUCCESS,
                path
              });
            }
            resolve(path);
          })
          .catch(err => {
            if (!isIsolated) {
              AppDispatcher.dispatch({
                actionType: FilesListConstants.GET_OBJECT_PATH_FAIL,
                err
              });
            }
            reject(err);
          });
      }
    });
  },

  getFolderContent(
    storage,
    accountId,
    folderId,
    fileFilter,
    { isIsolated = false, recursive = false, usePageToken = false }
  ) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.GET_FOLDER_CONTENT,
      storage,
      accountId,
      folderId,
      fileFilter,
      isIsolated,
      recursive,
      usePageToken
    });
  },

  getTrashFilesCount() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.GET_TRASH_FILES_COUNT
    });
  },

  createFileByTemplate(parentId, name, template) {
    return new Promise((resolve, reject) => {
      const createTempFilePromise = new Promise((localResolve, localReject) => {
        AppDispatcher.dispatch({
          actionType: FilesListConstants.CREATE_FILE_FROM_TEMPLATE,
          parentId,
          name,
          template,
          resolve: localResolve,
          reject: localReject
        });
      });

      createTempFilePromise.then(tempId => {
        Requests.sendGenericRequest(
          `/templates/${template}/clone`,
          RequestsMethods.POST,
          Requests.getDefaultUserHeaders(),
          { filename: name, folderId: parentId },
          ["*"]
        )
          .then(response => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.CREATE_FILE_FROM_TEMPLATE_SUCCESS,
              parentId,
              tempId,
              template,
              response
            });
            resolve(response);
          })
          .catch(err => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.CREATE_FILE_FROM_TEMPLATE_FAIL,
              parentId,
              name,
              template,
              err
            });
            reject(err);
          });
      });
    });
  },

  /**
   *
   * @param entities
   */
  uploadEntities(entities) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.UPLOAD_ENTITIES,
      entities
    });
  },

  /**
   *
   * @param uploadId
   */
  cancelEntityUpload(uploadId) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CANCEL_ENTITY_UPLOAD,
      uploadId
    });
  },

  createFolder(parentId, name) {
    return new Promise((resolve, reject) => {
      const createTempEntityPromise = new Promise(
        (localResolve, localReject) => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.CREATE_FOLDER,
            parentId,
            name,
            resolve: localResolve,
            reject: localReject
          });
        }
      );

      createTempEntityPromise.then(tempId => {
        Requests.sendGenericRequest(
          "/folders",
          RequestsMethods.POST,
          Requests.getDefaultUserHeaders(),
          { name, parentId },
          ["*"]
        )
          .then(response => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.CREATE_FOLDER_SUCCESS,
              parentId,
              tempId,
              response
            });
            resolve(response);
          })
          .catch(err => {
            AppDispatcher.dispatch({
              actionType: FilesListConstants.CREATE_FOLDER_FAIL,
              parentId,
              name,
              err
            });
            reject(err);
          });
      });
    });
  },

  createPublicLink({ fileId, isExport, endTime, password, resetPassword }) {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: FilesListConstants.CREATE_PUBLIC_LINK,
        endTime,
        fileId,
        password,
        resetPassword,
        isExport
      });

      const headers = _.extend(Requests.getDefaultUserHeaders(), {
        endTime,
        password,
        resetPassword,
        export: isExport
      });

      Requests.sendGenericRequest(
        `/files/${fileId}/link`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          const { link } = response.data;
          AppDispatcher.dispatch({
            actionType: FilesListConstants.CREATE_PUBLIC_LINK_SUCCESS,
            fileId,
            link
          });
          resolve(link);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.CREATE_PUBLIC_LINK_FAIL,
            fileId,
            err
          });
          reject(err);
        });
    });
  },

  generatePasswordHeader({ realm, nonce, opaque, password, ha1 }) {
    let finalHa1;
    if (ha1) {
      finalHa1 = ha1;
    } else {
      finalHa1 = md5(`${realm}:${password}`);
    }
    const uri = "GET:/files/link";
    const ha2 = md5(uri);
    const response = md5(`${finalHa1}:${nonce}:${ha2}`);

    return `ha1="${finalHa1}",nonce="${nonce}",response="${response}",opaque="${opaque}"`;
  },

  getNonceForPublicLinkPassword() {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/nonce`,
        "GET",
        undefined,
        undefined,
        ["*"],
        false
      )
        .then(nonceResponse => {
          const regExp = /"([a-zA-Z0-9./]+)"/g;
          const str = nonceResponse.data.auth;
          let m;
          const parsed = [];
          do {
            m = regExp.exec(str);
            if (m) {
              parsed.push(m[1]);
            }
          } while (m);
          // here comes simplified digest auth
          const [realm, nonce, opaque] = parsed;
          resolve({ realm, nonce, opaque });
        })
        .catch(() => {
          reject(new Error("Unexpected response"));
        });
    });
  },

  shareObject(type, objectId, dataObject) {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/${type}s/${objectId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        dataObject,
        ["*"]
      )
        .then(response => {
          const { data } = response;
          resolve(data);
        })
        .catch(err => {
          reject(new Error(err.text));
        });
    });
  },

  checkSharingPossibility(type, objectId, username) {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/${type}s/${objectId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        { tryShare: username.toLowerCase() },
        ["*"]
      )
        .then(response => {
          const { data } = response;
          resolve(data);
        })
        .catch(err => {
          reject(new Error(err.text));
        });
    });
  },

  updatePath(targetId, name) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.UPDATE_PATH,
      targetId,
      name
    });
  },

  resetPath() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.RESET_PATH
    });
  },

  downloadObjects(objects) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.DOWNLOAD_OBJECTS,
      objects
    });
  },

  cloneObject(
    objectId,
    type,
    name,
    doCopyShare,
    doCopyComments,
    doIncludeResolved,
    doIncludeDeleted
  ) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CLONE_OBJECT,
      objectId,
      type,
      name,
      doCopyShare,
      doCopyComments,
      doIncludeResolved,
      doIncludeDeleted
    });
  },

  createShortcut(objectId, type, name, createInCurrentFolder = true) {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: FilesListConstants.CREATE_SHORTCUT,
        objectId,
        type,
        name,
        createInCurrentFolder,
        resolve,
        reject
      });
    });
  },

  updateName(objectId, type, newName) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.UPDATE_ENTITY_NAME,
      objectId,
      type,
      newName
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/${type}s/${objectId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        {
          [type === "folder" ? "folderName" : "fileName"]: newName
        },
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.UPDATE_ENTITY_NAME_SUCCESS,
            objectId
          });
          const { data } = response;
          resolve(data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.UPDATE_ENTITY_NAME_FAIL,
            objectId,
            err
          });
          reject(new Error(err.text));
        });
    });
  },

  /**
   * Open object in commander or touch
   * @param {string} fileId - object id
   * @param {string} parentFolderId - Object parent id
   * @param {Object?} queryParams - Optional params to pass
   * @param {string?} queryParams.name - Object name
   * @param {string?} queryParams.versionId - Specific versionId
   * @param {string?} queryParams.token - File token (VersionToken or FileToken)
   * @param {string?} queryParams.threadId - Specific threadId to highlight
   * @param {string?} queryParams.commentId - Specific threadId to highlight
   */
  openFileInCommander(fileId, parentFolderId, queryParams = {}) {
    let fileOpenLink = `areskudo://open-file/${parentFolderId}/${fileId}`;

    const paramsString = Object.keys(queryParams)
      .filter(k => queryParams[k] && queryParams[k].length)
      .map(k => `${k}=${queryParams[k]}`)
      .join("&");

    if (paramsString.length) {
      fileOpenLink = `${fileOpenLink}?${paramsString}`;
    }

    // XENON-52817: In chrome for windows and for mobile changed user agent headers
    // and now they are use AppleWebKit with safari header at all
    if (
      /safari/.test(window.navigator.userAgent.toLowerCase()) &&
      !(
        /windows/.test(window.navigator.userAgent.toLowerCase()) ||
        /android/.test(window.navigator.userAgent.toLowerCase())
      )
    ) {
      return new Promise((resolve, reject) => {
        window.open(fileOpenLink, "_blank", "noopener,noreferrer");
        reject(new Error(APPLE_DEVICE));
      });
    }

    return new Promise((resolve, reject) => {
      customProtocolCheck(
        fileOpenLink,
        () => {
          reject();
        },
        () => {
          resolve();
        }
      );
    });
  },

  compareDrawings(
    { id, versionId, ext },
    { id: id2, versionId: version2, ext: ext2 }
  ) {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        "/files/compare",
        RequestsMethods.POST,
        Requests.getDefaultUserHeaders(),
        {
          files: [
            { id, versionId, ext },
            { id: id2, versionId: version2, ext: ext2 }
          ]
        },
        ["*"]
      )
        .then(response => {
          const { data } = response;
          resolve(data);
        })
        .catch(err => {
          reject(new Error(err.text));
        });
    });
  },

  setEntityRenameMode(entityId, mode = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.ENTITY_RENAME_MODE,
      entityId,
      mode
    });
  },

  addEntity(entityData, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.ADD_ENTITY,
      entityData,
      fireEvent
    });
  },

  modifyEntity(entityId, newEntityData, omitProperties, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.MODIFY_ENTITY,
      entityId,
      newEntityData,
      omitProperties,
      fireEvent
    });
  },

  deleteEntity(entityId, fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.DELETE_ENTITY,
      entityId,
      fireEvent
    });
  },

  // request new files if new page token exists and
  // files amount is less than 20
  checkNewFilesPage() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CHECK_NEW_FILES_PAGE
    });
  },

  clearTrashEntities(fireEvent = true) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CLEAR_TRASH_ENTITIES,
      fireEvent
    });
  },

  deleteRecentEntity(entities) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.DELETE_RECENT_ENTITIES,
      entities
    });
  },

  takeEditPermission() {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.TAKE_EDIT_PERMISSION
    });
  },

  checkEditPermission(message) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.CHECK_EDIT_PERMISSION,
      message
    });
  },

  saveFileInXenon() {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: FilesListConstants.SAVE_FILE_IN_XENON,
        resolve,
        reject
      });
    });
  },

  downgradeThisSessionForOwned(fileId, xSessionToDowngrade, applicantXSession) {
    this.saveFileInXenon().then(() => {
      const headers = {
        xSessionId: xSessionToDowngrade,
        sessionId: Storage.store("sessionId"),
        downgrade: true,
        applicantXSession
      };
      Requests.sendGenericRequest(
        `/files/${encodeURIComponent(fileId)}/session`,
        RequestsMethods.PUT,
        headers,
        undefined,
        ["*"]
      );
    });
  },

  setViewingLatestVersion(isLatest) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SET_VIEWING_LATEST_VERSION,
      isLatest
    });
  },

  setConflictingReason(conflictingReason) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.SET_CONFLICTING_REASON,
      conflictingReason
    });
  },

  eraseFilesAction(accountId, storageId) {
    AppDispatcher.dispatch({
      actionType: FilesListConstants.DELETE_TRASH
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      headers.storageType = storageId;
      headers.externalId = accountId;
      Requests.sendGenericRequest(
        `/trash`,
        RequestsMethods.DELETE,
        headers,
        ["*"],
        false
      )
        .then(response => {
          const { data } = response;
          const { status } = data;
          AppDispatcher.dispatch({
            actionType: FilesListConstants.DELETE_TRASH_SUCCESS,
            status
          });
          resolve();
        })
        .catch(() => {
          AppDispatcher.dispatch({
            actionType: FilesListConstants.DELETE_TRASH_FAIL
          });
          reject();
        });
    });
  },
  getFileBody(fileEntry) {
    return new Promise((resolve, reject) => {
      if (fileEntry.file) {
        fileEntry.file(
          body => resolve(body),
          err => reject(err)
        );
      } else {
        resolve(fileEntry);
      }
    });
  },
  /**
   * Generate Presigned s3 url to upload file
   *
   * @param {string} storageType
   * @param {string} fileContentType
   * @param {string} fileId
   * @param {string} presignedUploadType
   *
   * @returns {object}
   */
  generatePreSignedUrl(fileName, fileContentType, fileId, presignedUploadType) {
    return new Promise((resolve, reject) => {
      const data = { fileName, presignedUploadType };
      if (fileContentType) {
        data.fileContentType = fileContentType;
      }
      if (fileId) {
        data.fileId = fileId;
      }
      Requests.sendGenericRequest(
        `/files/signedurl/generate`,
        RequestsMethods.POST,
        Requests.getDefaultUserHeaders(),
        data,
        ["*"]
      )
        .then(response => {
          const result = response.data;
          resolve(result);
        })
        .catch(err => {
          reject(err);
        });
    });
  },
  /**
   * Upload the file to S3 using presigned URL
   *
   * @param {Array} canceledUploads
   * @param {string} presignedUrl
   * @param {File} file
   * @param {string} name
   * @param {string} fileContentType
   * @returns
   */
  uploadFileUsingPresignedUrl(presignedUrl, file, fileContentType) {
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      if (fileContentType) {
        headers["content-type"] = fileContentType;
      }
      fetch(presignedUrl, {
        method: "PUT",
        headers,
        body: file,
        credentials: "same-origin",
        signal: FetchAbortsControl._addSignalHandler(presignedUrl)
      })
        .then(() => {
          FetchAbortsControl._removeSignalHandler(presignedUrl);
          resolve();
        })
        .catch(err => {
          reject(err);
        });
    });
  }
};

export default FilesListActions;
