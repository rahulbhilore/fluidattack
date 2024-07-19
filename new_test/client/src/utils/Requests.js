/* eslint-disable no-console */
import "whatwg-fetch";
import jsonpack from "jsonpack";
import _ from "underscore";
import { browserHistory } from "react-router";
import axios from "axios";
import Storage from "./Storage";
import AppDispatcher from "../dispatcher/AppDispatcher";
import MainFunctions from "../libraries/MainFunctions";
import FetchAbortsControl from "./FetchAbortsControl";

let requestsLog = [];
let packedLogs = "";
let lastRequestTime = 0;

// window.saveLogs = () => {
//   if (requestsLog.length > 0) {
//     packedLogs += jsonpack.pack(requestsLog)
//     requestsLog = []
//   }
//   const blob = new Blob([packedLogs], {type: 'text'})
//   const e = document.createEvent('MouseEvents')
//   const a = document.createElement('a')

//   a.download = 'reqLogs_txt.txt'
//   a.href = window.URL.createObjectURL(blob)
//   a.dataset.downloadurl = ['text', a.download, a.href].join(':')
//   e.initMouseEvent('click', true, false, window, 0, 0, 0, 0, 0, false, false,
//     false, false, 0, null)
//   a.dispatchEvent(e)
// }

// setInterval(function logCompressionCheck () {
//   if (requestsLog.length > 0 && Date.now() - lastRequestTime > 5000) {
//     packedLogs += jsonpack.pack(requestsLog)
//     requestsLog = []
//   }
// }, 5000)

export default class Requests {
  static getRequestsLogs(forceLogUpdate) {
    if (forceLogUpdate === true) {
      if (requestsLog.length > 0) {
        packedLogs += jsonpack.pack(requestsLog);
        requestsLog = [];
      }
    }
    return jsonpack.unpack(packedLogs);
  }

  /**
   * Send generic request (except uploads)
   * @param requestURL {string}
   * @param requestMethod {string}
   * @param [requestHeaders] {{}}
   * @param [requestData] {{}}
   * @param [allowedStatusCodes] {string[]}
   * @param [areCredentialsToBeIncluded] {boolean}
   * @return {Promise}
   */
  static sendGenericRequest(
    requestURL,
    requestMethod,
    requestHeaders,
    requestData,
    allowedStatusCodes,
    areCredentialsToBeIncluded = false,
    preventAbortSignal = false,
    actionId = null
  ) {
    if (AppDispatcher.debugMode === true) {
      return Promise.resolve(null);
    }
    let finalServerURL = requestURL;
    let headers = requestHeaders || {};
    if (finalServerURL.indexOf("http") === -1) {
      finalServerURL =
        (window.ARESKudoConfigObject.api ||
          window.ARESKudoConfigObject.apiURL) + finalServerURL;
      if (!(requestData instanceof FormData)) {
        headers = _.defaults(requestHeaders || {}, {
          "Content-Type": "application/json"
        });
      }
      if (!requestData && ["PUT", "POST", "PATCH"].includes(requestMethod)) {
        delete headers["Content-Type"];
      }
    }
    lastRequestTime = Date.now();

    const newLogEntry = {
      request: {
        url: finalServerURL,
        data: requestData,
        headers,
        method: requestMethod,
        timeStamp: lastRequestTime
      },
      response: {}
    };

    return new Promise((returnResolver, returnRejecter) => {
      fetch(finalServerURL, {
        method: requestMethod,
        mode: "cors",
        cache: "reload",
        body:
          requestData instanceof FormData
            ? requestData
            : JSON.stringify(requestData),
        headers: new Headers(headers),
        // DK: hotfix for login. Need to check different properties
        credentials: areCredentialsToBeIncluded ? "include" : "same-origin",
        signal: !preventAbortSignal
          ? FetchAbortsControl._addSignalHandler(finalServerURL, actionId)
          : null
      })
        .then(response => {
          FetchAbortsControl._removeSignalHandler(finalServerURL);
          newLogEntry.response = {
            headers: response.headers.map,
            status: response.status,
            statusText: response.statusText,
            url: response.url
          };
          const contentType = response.headers.get("content-type");
          if (
            contentType === "binary/octet-stream" ||
            contentType === "application/octet-stream" ||
            contentType === "image/svg+xml"
          ) {
            response
              .arrayBuffer()
              .then(buffer => {
                returnResolver({
                  code: response.status,
                  contentType,
                  data: buffer,
                  headers: response.headers
                });
                newLogEntry.response.data = buffer;
                requestsLog.push(newLogEntry);
              })
              .catch(Requests.handleGenericError);
          } else if (!response.ok) {
            if (
              _.isArray(allowedStatusCodes) &&
              (allowedStatusCodes[0] !== "*" ||
                allowedStatusCodes.indexOf(response.status) < 0)
            ) {
              response
                .text()
                .then(responseData => {
                  const responseDataParsed =
                    MainFunctions.convertToJSON(responseData);
                  newLogEntry.response.data = responseDataParsed;
                  requestsLog.push(newLogEntry);
                  // eslint-disable-next-line prefer-promise-reject-errors
                  returnRejecter({
                    code: response.status,
                    text:
                      responseDataParsed.message || responseDataParsed.string,
                    data: responseDataParsed,
                    headers: response.headers
                  });
                })
                .catch(Requests.handleGenericError);
            } else {
              response
                .text()
                .then(text => {
                  newLogEntry.response.data = text;
                  requestsLog.push(newLogEntry);
                  Requests.handleGenericError(text, response.status);
                })
                .catch(Requests.handleGenericError);
            }
          } else {
            response
              .json()
              .then(jsonData => {
                newLogEntry.response.data = jsonData;
                requestsLog.push(newLogEntry);
                returnResolver({
                  code: response.status,
                  data: jsonData,
                  headers: response.headers
                });
              })
              .catch(Requests.handleGenericError);
          }
        })
        .catch(exception => {
          newLogEntry.response = exception;
          requestsLog.push(newLogEntry);
          const errorMessage = Requests.handleGenericError(
            exception,
            null,
            true
          );
          if (
            errorMessage?.includes("abort") &&
            requestURL.endsWith("/folders")
          ) {
            // eslint-disable-next-line prefer-promise-reject-errors
            returnRejecter({
              text: "Abort"
            });
          }
        });
    });
  }

  static handleGenericError(exception, statusCode, isSilent) {
    const convertedData = MainFunctions.convertToJSON(exception);
    const errorMessage = convertedData.message || convertedData.string;
    if (statusCode) {
      console.error("Requests error:", `(${statusCode}) ${errorMessage}`);
      if (statusCode === 401) {
        Requests.sessionHasExpiredHandler();
      } else {
        // SnackbarUtils.alertError(errorMessage);
      }
    } else if (isSilent === true) {
      console.error("Requests exception:", exception);
    } else {
      console.error("Requests error:", errorMessage);
      // SnackbarUtils.alertError(errorMessage);
    }
    return errorMessage;
  }

  static sessionHasExpiredHandler() {
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
      if (
        window.ARESKudoConfigObject.features_enabled.intercom === true &&
        Intercom
      ) {
        Intercom("shutdown");
        Intercom("boot", {
          app_id: "dzoczj6l",
          created_at: new Date().getTime(),
          sitename: location.hostname
        });
      }
      Storage.store("error", "notLoggedInOrSessionExpired");
      const currentPageUrl =
        location.pathname.substr(
          location.pathname.indexOf(window.ARESKudoConfigObject.UIPrefix) + 1
        ) + location.search;
      browserHistory.push(
        `${window.ARESKudoConfigObject.UIPrefix}?redirect=${encodeURIComponent(
          currentPageUrl
        )}`
      );
    }
  }

  /**
   * Returns default headers for logged in user
   * @return {{sessionId: string, locale: (string)}}
   */
  static getDefaultUserHeaders() {
    return {
      sessionId: Storage.store("sessionId"),
      locale: Storage.store("locale") || "en_gb"
    };
  }

  /**
   * Returns default api url
   * @return {{sessionId: string, locale: (string)}}
   */
  static getApiUri() {
    return (
      window.ARESKudoConfigObject.api || window.ARESKudoConfigObject.apiURL
    );
  }

  /**
   * @param url
   * @param data
   * @param headers
   * @param callback
   * @param controller
   * @param processData
   * @returns {*}
   */
  static uploadFile(url, formData, headers, callback, controller, processData) {
    const uploadHeaders = {};
    headers.forEach(elem => {
      uploadHeaders[elem.name] = elem.value;
    });
    axios
      .post(url, formData, {
        headers: uploadHeaders,
        onUploadProgress: processData,
        signal: controller ? controller.signal : null
      })
      .then(response => {
        callback(response);
      })
      .catch(error => {
        if (error?.name && error.name === "CanceledError") {
          callback(error);
        } else {
          callback(error.response);
        }
      });
  }
}
