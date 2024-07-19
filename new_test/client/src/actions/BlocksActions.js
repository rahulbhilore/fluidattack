import Requests from "../utils/Requests";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as BlocksConstants from "../constants/BlocksConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import Processes from "../constants/appConstants/Processes";
import ProcessActions from "./ProcessActions";
import applicationStore from "../stores/ApplicationStore";
import Storage from "../utils/Storage";
import UploadActions from "./UploadActions";
import uploadStore from "../stores/UploadStore";
import MainFunctions from "../libraries/MainFunctions";
import SnackController from "../components/Notifications/Snackbars/SnackController";

const BlocksActions = {
  getBlockLibraries(ownerId, ownerType = "USER") {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.GET_BLOCK_LIBRARIES,
      ownerId,
      ownerType
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      headers.ownerId = ownerId;
      headers.ownerType = ownerType;
      Requests.sendGenericRequest(
        `/library/blocks`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARIES_SUCCESS,
            info: response.data,
            ownerId,
            ownerType
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARIES_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  getBlockLibraryContent(libraryId) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.GET_BLOCK_LIBRARY_CONTENT
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARY_CONTENT_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARY_CONTENT_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  getBlockLibraryInfo(libraryId) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.GET_BLOCK_LIBRARY_INFO
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARY_INFO_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_LIBRARY_INFO_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  createBlockLibrary(name, description, ownerId, ownerType) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.CREATE_BLOCK_LIBRARY,
      name,
      description,
      ownerId,
      ownerType
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/library/blocks`,
        RequestsMethods.POST,
        headers,
        { name, description, ownerId, ownerType },
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.CREATE_BLOCK_LIBRARY_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          SnackController.alertError(err.text);
          AppDispatcher.dispatch({
            actionType: BlocksConstants.CREATE_BLOCK_LIBRARY_FAIL,
            info: err,
            name
          });
          reject(err);
        });
    });
  },

  uploadMultipleBlocks(files, libraryId, existingBlocks) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.UPLOAD_BLOCKS,
      libraryId,
      names: files.map(fileEntry => fileEntry.name)
    });
    return new Promise((resolve, reject) => {
      const incorrectFormatFiles = [];
      const duplicates = [];
      const filesToUpload = files.filter(fileEntry => {
        const { name: fileName } = fileEntry;
        if (!fileName.includes(".")) {
          incorrectFormatFiles.push(fileName);
          return false;
        }
        const extension = MainFunctions.getExtensionFromName(fileName);
        const isCorrectExtension =
          BlocksConstants.SUPPORTED_EXTENSIONS.includes(extension);
        if (!isCorrectExtension) {
          incorrectFormatFiles.push(fileName);
          return false;
        }
        if (
          existingBlocks
            .map(n => n.toLowerCase())
            .includes(fileName.toLowerCase())
        ) {
          duplicates.push(fileName);
          return false;
        }
        return true;
      });

      const errorMessages = [];
      if (incorrectFormatFiles.length > 0) {
        errorMessages.push({
          id: "blockUploadFail",
          files: incorrectFormatFiles
            .map(name => MainFunctions.shrinkString(name))
            .join("\r\n"),
          types: BlocksConstants.SUPPORTED_EXTENSIONS.join(",")
        });
      }
      if (duplicates.length > 0) {
        errorMessages.push({
          id: "duplicateNameUpload",
          duplicates: duplicates
            .map(name => MainFunctions.shrinkString(name))
            .join("\r\n")
        });
      }
      if (errorMessages.length > 0) {
        SnackController.alertError(errorMessages);
        AppDispatcher.dispatch({
          actionType: BlocksConstants.UPLOAD_BLOCKS_FAIL,
          libraryId,
          names: duplicates.concat(incorrectFormatFiles)
        });
      }
      const uploadEntries = filesToUpload.map(file => {
        const formData = new FormData();
        formData.append(file.name, file);
        const headers = Requests.getDefaultUserHeaders();
        // having the name in header isn't supported if it contains non-latin characters
        // https://graebert.atlassian.net/browse/XENON-60819
        // headers.name = file.name;
        headers.description = "";
        return {
          url: `/library/blocks/${libraryId}/items`,
          method: RequestsMethods.POST,
          body: formData,
          headers,
          name: file.name
        };
      });
      if (!uploadEntries.length) {
        return;
      }
      const uploadsQueue = UploadActions.queueUploads(uploadEntries);
      uploadStore
        .waitForUploadToComplete(uploadsQueue.map(({ requestId }) => requestId))
        .then(finishedRequests => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPLOAD_BLOCKS_SUCCESS,
            info: finishedRequests,
            libraryId
          });
          resolve(finishedRequests);
        })
        .catch(finishedRequests => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPLOAD_BLOCKS_FAIL,
            info: finishedRequests,
            libraryId
          });
          reject(finishedRequests);
        });
    });
  },

  uploadBlock(file, libraryId, name = "", description = "") {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.UPLOAD_BLOCK,
      name,
      description
    });
    return new Promise((resolve, reject) => {
      const formData = new FormData();
      formData.append(0, file);
      const headers = Requests.getDefaultUserHeaders();
      headers.name = name;
      headers.description = description;
      const uploadsQueue = UploadActions.queueUploads([
        {
          url: `/library/blocks/${libraryId}/items`,
          method: RequestsMethods.POST,
          body: formData,
          headers,
          name
        }
      ]);
      uploadStore
        .waitForUploadToComplete(uploadsQueue.map(({ requestId }) => requestId))
        .then(finishedRequests => {
          const response = finishedRequests[0].get("response");
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPLOAD_BLOCK_SUCCESS,
            info: response.data,
            libraryId
          });
          resolve(response);
        })
        .catch(finishedRequests => {
          const response = finishedRequests[0].get("error");
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPLOAD_BLOCK_FAIL,
            info: response.data
          });
          reject(response);
        });
    });
  },

  deleteMultipleBlockLibraries(librariesIds) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.DELETE_LIBRARIES,
      librariesIds
    });
    librariesIds.forEach(libId => {
      ProcessActions.start(libId, Processes.DELETE);
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/library/blocks`,
        RequestsMethods.DELETE,
        headers,
        librariesIds,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.DELETE_LIBRARIES_SUCCESS,
            info: response.data,
            librariesIds
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.DELETE_LIBRARIES_FAIL,
            info: response.data
          });
          reject(response);
        });
    });
  },

  deleteMultipleBlocks(blockIds, libraryId) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.DELETE_BLOCKS,
      blockIds,
      libraryId
    });
    blockIds.forEach(blockId => {
      ProcessActions.start(blockId, Processes.DELETE);
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items`,
        RequestsMethods.DELETE,
        headers,
        blockIds,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.DELETE_BLOCKS_SUCCESS,
            info: response.data,
            blockIds
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.DELETE_BLOCKS_FAIL,
            info: response.data
          });
          reject(response);
        });
    });
  },

  updateBlockLibrary(libraryId, name, description) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.UPDATE_BLOCK_LIBRARY
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}`,
        RequestsMethods.PUT,
        headers,
        { name, description },
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPDATE_BLOCK_LIBRARY_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPDATE_BLOCK_LIBRARY_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  updateBlock(blockId, libraryId, blockFile, name, description) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.UPDATE_BLOCK
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      headers.name = name;
      headers.description = description;
      const formData = new FormData();
      if (blockFile) {
        formData.append(0, blockFile);
      }
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items/${blockId}`,
        RequestsMethods.PUT,
        headers,
        formData,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPDATE_BLOCK_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.UPDATE_BLOCK_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  downloadBlock(blockId, libraryId, name) {
    const apiURL = applicationStore.getApplicationSetting("apiURL");
    AppDispatcher.dispatch({
      actionType: BlocksConstants.DOWNLOAD_BLOCK,
      blockId,
      libraryId
    });
    return new Promise((resolve, reject) => {
      const oReq = new XMLHttpRequest();
      oReq.open(
        "GET",
        `${apiURL}/library/blocks/${libraryId}/items/${blockId}/content`,
        true
      );
      oReq.setRequestHeader("sessionId", Storage.store("sessionId"));
      oReq.setRequestHeader("locale", Storage.store("locale"));
      oReq.responseType = "arraybuffer";
      oReq.onload = () => {
        if (oReq.status !== 200) {
          let errorMessage = "";
          try {
            errorMessage = String.fromCharCode.apply(
              null,
              new Uint8Array(oReq.response)
            );
          } catch (Exception) {
            errorMessage = {
              id: "unsuccessfulDownload",
              name,
              // eslint-disable-next-line react/jsx-filename-extension, react/react-in-jsx-scope
              type: "file"
            };
          }
          reject(errorMessage);
        } else {
          const blob = new Blob([oReq.response]);
          MainFunctions.downloadBlobAsFile(blob, name);
          resolve(blob);
        }
      };
      oReq.send();
    });
  },

  getBlockInfo(blockId, libraryId) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.GET_BLOCK_INFO
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items/${blockId}`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_INFO_SUCCESS,
            info: response.data
          });
          resolve(response);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.GET_BLOCK_INFO_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  shareBlockLibrary(libraryId, shareData) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.SHARE_BLOCK_LIBRARY,
      libraryId,
      shareData
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/access`,
        RequestsMethods.POST,
        Requests.getDefaultUserHeaders(),
        shareData,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SHARE_BLOCK_LIBRARY_SUCCESS,
            info: response.data
          });
          resolve(response.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SHARE_BLOCK_LIBRARY_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  shareBlock(blockId, libraryId, shareData) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.SHARE_BLOCK,
      libraryId,
      shareData
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items/${blockId}/access`,
        RequestsMethods.POST,
        Requests.getDefaultUserHeaders(),
        shareData,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SHARE_BLOCK_SUCCESS,
            info: response.data
          });
          resolve(response.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SHARE_BLOCK_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  removeBlockLibraryShare(libraryId, shareData) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.REMOVE_SHARE_BLOCK_LIBRARY,
      libraryId,
      shareData
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/access`,
        RequestsMethods.DELETE,
        Requests.getDefaultUserHeaders(),
        shareData,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.REMOVE_SHARE_BLOCK_LIBRARY_SUCCESS,
            info: response.data
          });
          resolve(response.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.REMOVE_SHARE_BLOCK_LIBRARY_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  removeBlockShare(blockId, libraryId, shareData) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.REMOVE_SHARE_BLOCK,
      libraryId,
      shareData
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/${libraryId}/items/${blockId}/access`,
        RequestsMethods.DELETE,
        Requests.getDefaultUserHeaders(),
        shareData,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.REMOVE_SHARE_BLOCK_SUCCESS,
            info: response.data
          });
          resolve(response.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.REMOVE_SHARE_BLOCK_FAIL,
            info: err
          });
          reject(err);
        });
    });
  },

  search(query) {
    AppDispatcher.dispatch({
      actionType: BlocksConstants.SEARCH_BLOCKS,
      query
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/library/blocks/items/search?term=${encodeURIComponent(query)}`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SEARCH_BLOCKS_SUCCESS,
            info: response.data
          });
          resolve(response.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: BlocksConstants.SEARCH_BLOCKS_FAIL,
            info: err
          });
          reject(err);
        });
    });
  }
};

export default BlocksActions;
