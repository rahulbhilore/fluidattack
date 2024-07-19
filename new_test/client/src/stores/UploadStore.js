/* eslint-disable no-console */
import EventEmitter from "events";
import Immutable from "immutable";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as UploadConstants from "../constants/UploadConstants";

export const UPLOADS_QUEUE_UPDATE = "UPLOADS_QUEUE_UPDATE";
export const ITEM_UPLOAD_FINISHED = "ITEM_UPLOAD_FINISHED";

export const UPLOAD_STATUSES = Object.fromEntries(
  ["QUEUED", "DONE", "IN_PROGRESS", "NOT_FOUND"].map(v => [v, v])
);

class UploadStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this.handleAction);
    this.uploadsQueue = new Immutable.Map();
  }

  handleAction = action => {
    if (action.actionType.indexOf(UploadConstants.constantPrefix) === -1)
      return;

    switch (action.actionType) {
      case UploadConstants.QUEUE_UPLOAD: {
        // List
        action.data.forEach(rawUploadData => {
          let uploadData = Immutable.fromJS(rawUploadData);
          uploadData = uploadData.set("body", rawUploadData.body);
          this.uploadsQueue = this.uploadsQueue.set(
            uploadData.get("requestId"),
            uploadData.set("status", UPLOAD_STATUSES.QUEUED)
          );
        });

        this.emit(UPLOADS_QUEUE_UPDATE);
        break;
      }
      case UploadConstants.SET_RESPONSE: {
        let request = this.getRequest(action.requestId);
        if (!request) {
          console.warn(`No request found for id: ${action.requestId}`);
        } else {
          request = request.set("response", action.response);
          request = request.set("status", UPLOAD_STATUSES.DONE);
          this.uploadsQueue = this.uploadsQueue.set(action.requestId, request);
          this.emit(ITEM_UPLOAD_FINISHED, action.requestId);
          this.emit(UPLOADS_QUEUE_UPDATE);
        }
        break;
      }
      case UploadConstants.SET_ERROR: {
        let request = this.getRequest(action.requestId);
        if (!request) {
          console.warn(`No request found for id: ${action.requestId}`);
        } else {
          request = request.set("error", action.error);
          request = request.set("status", UPLOAD_STATUSES.DONE);
          this.uploadsQueue = this.uploadsQueue.set(action.requestId, request);
          this.emit(ITEM_UPLOAD_FINISHED, action.requestId);
          this.emit(UPLOADS_QUEUE_UPDATE);
        }
        break;
      }
      case UploadConstants.SET_IN_PROGRESS: {
        let request = this.getRequest(action.requestId);
        if (!request) {
          console.warn(`No request found for id: ${action.requestId}`);
        } else {
          request = request.set("status", UPLOAD_STATUSES.IN_PROGRESS);
          this.uploadsQueue = this.uploadsQueue.set(action.requestId, request);
          this.emit(UPLOADS_QUEUE_UPDATE);
        }
        break;
      }
      case UploadConstants.CLEAR_LIST: {
        this.uploadsQueue = new Immutable.Map();
        this.emit(UPLOADS_QUEUE_UPDATE);
        break;
      }
      default:
        break;
    }
  };

  getRequestStatus = requestId => {
    const request = this.getRequest(requestId);
    if (!request) return UPLOAD_STATUSES.NOT_FOUND;
    return request.get("status");
  };

  getRequest = requestId => {
    const request = this.uploadsQueue.get(requestId);
    if (!request) return null;
    return request;
  };

  waitForUploadToComplete = requestIds =>
    new Promise((resolve, reject) => {
      const waitTimer = setInterval(() => {
        const statuses = requestIds.map(this.getRequestStatus);
        if (_.every(statuses, status => status === UPLOAD_STATUSES.DONE)) {
          clearInterval(waitTimer);
          const requestsData = requestIds.map(this.getRequest);
          if (
            _.any(
              requestsData,
              request =>
                request.get("status") === UPLOAD_STATUSES.DONE &&
                request.has("error")
            )
          ) {
            reject(requestsData);
          } else {
            resolve(requestsData);
          }
        }
      }, 1000);
    });

  getUploadsQueue() {
    return this.uploadsQueue;
  }
}

UploadStore.dispatchToken = null;
const uploadStore = new UploadStore();

export default uploadStore;
