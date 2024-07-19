import mime from "mime";
import FilesListActions from "./FilesListActions";
import AppDispatcher from "../dispatcher/AppDispatcher";
import Requests from "../utils/Requests";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import * as VersionControlConstants from "../constants/VersionControlConstants";
import ProcessActions from "./ProcessActions";
import MainFunctions from "../libraries/MainFunctions";
import Processes from "../constants/appConstants/Processes";

const VersionControlActions = {
  getVersions(fileId) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.GET_LIST,
      fileId
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/files/${fileId}/versions`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.GET_LIST_SUCCESS,
            info: response.data,
            fileId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.GET_LIST_FAIL,
            info: response.data,
            fileId
          });
          reject(response);
        });
    });
  },
  getLatestVersion(fileId) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.GET_LATEST,
      fileId
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/files/${fileId}/versions/latest`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.GET_LATEST_SUCCESS,
            info: response.data,
            fileId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.GET_LATEST_FAIL,
            info: response.data,
            fileId
          });
          reject(response);
        });
    });
  },
  downloadVersion(fileId, versionId) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.DOWNLOAD,
      fileId
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/files/${fileId}/versions/${versionId}/data`,
        RequestsMethods.GET,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.DOWNLOAD_SUCCESS,
            info: response.data,
            fileId,
            versionId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.DOWNLOAD_FAIL,
            info: response.data,
            fileId,
            versionId
          });
          reject(response);
        });
    });
  },
  downloadVersionViaStream(fileId, versionId, versionSize) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.DOWNLOAD_STREAM,
      fileId,
      versionId,
      versionSize
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      let finalServerURL = `/files/${fileId}/versions/${versionId}/data`;
      finalServerURL =
        (window.ARESKudoConfigObject.api ||
          window.ARESKudoConfigObject.apiURL) + finalServerURL;

      ProcessActions.start(versionId, Processes.VERSION_DOWNLOAD);

      const rejectFailedDownload = error => {
        AppDispatcher.dispatch({
          actionType: VersionControlConstants.DOWNLOAD_STREAM_FAIL,
          info: error.message,
          fileId,
          versionId
        });
        ProcessActions.end(versionId);
        reject(error);
      };

      fetch(finalServerURL, {
        method: RequestsMethods.GET,
        mode: "cors",
        cache: "reload",
        headers: new Headers(headers)
      })
        .then(response => {
          if (response.status !== 200) {
            response
              .json()
              .then(({ message }) => rejectFailedDownload(new Error(message)));
            return;
          }

          const reader = response.body.getReader();
          let receivedLength = 0;
          const chunks = [];

          // eslint-disable-next-line no-new
          new ReadableStream({
            start(controller) {
              function push() {
                reader.read().then(({ done, value }) => {
                  if (done) {
                    controller.close();
                    ProcessActions.end(versionId);
                    const finalBlob = new Blob(chunks);
                    AppDispatcher.dispatch({
                      actionType:
                        VersionControlConstants.DOWNLOAD_STREAM_SUCCESS,
                      info: finalBlob,
                      fileId,
                      versionId
                    });
                    resolve(finalBlob);
                    return;
                  }

                  receivedLength += value.length;
                  ProcessActions.step(
                    versionId,
                    Math.floor((receivedLength / versionSize) * 100)
                  );
                  controller.enqueue(value);
                  chunks.push(value);
                  push();
                });
              }
              push();
            }
          });
        })
        .catch(exception => rejectFailedDownload(exception));
    });
  },
  uploadVersion(fileId, file) {
    const serviceVersionId = MainFunctions.guid();

    AppDispatcher.dispatch({
      actionType: VersionControlConstants.UPLOAD,
      fileId,
      serviceVersionId
    });
    ProcessActions.start(serviceVersionId, Processes.VERSION_UPLOAD);
    return new Promise((resolve, reject) => {
      const mimeType = mime.getType(file.name);
      this.checkAndDoPresignedUpload(
        file.name,
        file.size,
        mimeType,
        file,
        fileId
      )
        .then(presignedUploadId => {
          const headers = Requests.getDefaultUserHeaders();
          if (presignedUploadId) {
            headers.presignedUploadId = presignedUploadId;
          }
          const url = `${window.ARESKudoConfigObject.api}/files/${fileId}/versions`;
          const xhr = new XMLHttpRequest();

          xhr.onloadend = () => {
            ProcessActions.end(serviceVersionId);
            if (xhr.status === 200) {
              AppDispatcher.dispatch({
                actionType: VersionControlConstants.UPLOAD_SUCCESS,
                info: xhr.response,
                fileId
              });
              resolve(xhr.response);
            } else {
              AppDispatcher.dispatch({
                actionType: VersionControlConstants.UPLOAD_FAIL,
                info: {
                  status: xhr.status,
                  message: JSON.parse(xhr.response).message
                },
                serviceVersionId,
                fileId
              });
              reject(xhr.status);
            }
          };

          xhr.upload.onprogress = event => {
            ProcessActions.step(
              serviceVersionId,
              Math.floor((event.loaded / event.total) * 100)
            );
          };

          xhr.open(RequestsMethods.POST, url);

          Object.keys(headers).forEach(key => {
            xhr.setRequestHeader(key, headers[key]);
          });

          if (presignedUploadId) {
            xhr.send();
          } else {
            const formData = new FormData();
            formData.append(0, file);
            xhr.send(formData);
          }
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.UPLOAD_FAIL,
            info: {
              status: response.code,
              message: response.text
            },
            serviceVersionId,
            fileId
          });
          reject(response.code);
        });
    });
  },
  promote(fileId, versionId) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.PROMOTE,
      fileId
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/files/${fileId}/versions/${versionId}/promote`,
        RequestsMethods.POST,
        headers,
        undefined,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.PROMOTE_SUCCESS,
            info: response.data,
            fileId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.PROMOTE_FAIL,
            info: response.data,
            fileId
          });
          reject(response);
        });
    });
  },
  remove(fileId, versionId) {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.REMOVE,
      fileId
    });
    return new Promise((resolve, reject) => {
      const headers = Requests.getDefaultUserHeaders();
      Requests.sendGenericRequest(
        `/files/${fileId}/versions/${versionId}`,
        RequestsMethods.DELETE,
        headers,
        undefined,
        ["*"],
        false
      )
        .then(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.REMOVE_SUCCESS,
            info: response.data,
            fileId
          });
          resolve(response);
        })
        .catch(response => {
          AppDispatcher.dispatch({
            actionType: VersionControlConstants.REMOVE_FAIL,
            info: response.data,
            fileId
          });
          reject(response);
        });
    });
  },
  saveBeforeUpload() {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.SAVE_BEFORE_UPLOAD
    });
  },
  saveBeforeUploadDone() {
    AppDispatcher.dispatch({
      actionType: VersionControlConstants.SAVE_BEFORE_UPLOAD_DONE
    });
  },
  /**
   * Check if the file is large (> 5 MB), then upload it via presigned url
   *
   * @param {string} fileName
   * @param {string} fileSize
   * @param {string} fileType
   * @param {File} file
   * @param {string} fileId
   *
   */
  checkAndDoPresignedUpload(fileName, fileSize, fileType, file, fileId) {
    return new Promise((resolve, reject) => {
      if (fileSize / (1024 * 1024) > 5) {
        FilesListActions.generatePreSignedUrl(
          fileName,
          fileType,
          fileId,
          "version"
        )
          .then(result => {
            FilesListActions.getFileBody(file)
              .then(fileBody => {
                FilesListActions.uploadFileUsingPresignedUrl(
                  result.presignedUrl,
                  fileBody,
                  fileType
                )
                  .then(() => {
                    resolve(result.presignedUploadId);
                  })
                  .catch(err => {
                    reject(err);
                  });
              })
              .catch(err => {
                reject(err);
              });
          })
          .catch(err => {
            reject(err);
          });
      } else {
        resolve(null);
      }
    });
  }
};

export default VersionControlActions;
