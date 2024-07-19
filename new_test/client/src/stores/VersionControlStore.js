import EventEmitter from "events";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as VersionControlConstants from "../constants/VersionControlConstants";

class VersionControlStore extends EventEmitter {
  constructor() {
    super();
    this.dispatcherIndex = AppDispatcher.register(this.handleAction);
    this.versions = [];
    this.lastError = "error";
  }

  handleAction = action => {
    if (
      action.actionType.indexOf(VersionControlConstants.constantPrefix) === -1
    )
      return;
    switch (action.actionType) {
      case VersionControlConstants.GET_LIST: {
        this.emitEvent(VersionControlConstants.GET_LIST);
        break;
      }
      case VersionControlConstants.GET_LIST_SUCCESS: {
        this.versions = action.info.result
          .sort(
            (version1, version2) =>
              version2.creationTime - version1.creationTime
          )
          .map((elem, index, array) => ({
            customName: `V${array.length - index}`,
            isService: false,
            ...elem
          }));
        this.emitEvent(VersionControlConstants.GET_LIST_SUCCESS);
        break;
      }
      case VersionControlConstants.GET_LIST_FAIL: {
        this.lastError = action.info.message || "error";
        this.emitEvent(VersionControlConstants.GET_LIST_FAIL);
        break;
      }
      case VersionControlConstants.GET_LATEST: {
        this.emitEvent(VersionControlConstants.GET_LATEST);
        break;
      }
      case VersionControlConstants.GET_LATEST_SUCCESS: {
        this.emitEvent(VersionControlConstants.GET_LATEST_SUCCESS);
        break;
      }
      case VersionControlConstants.GET_LATEST_FAIL: {
        this.emitEvent(VersionControlConstants.GET_LATEST_FAIL);
        break;
      }
      case VersionControlConstants.DOWNLOAD: {
        this.emitEvent(VersionControlConstants.DOWNLOAD);
        break;
      }
      case VersionControlConstants.DOWNLOAD_SUCCESS: {
        this.emitEvent(VersionControlConstants.DOWNLOAD_SUCCESS);
        break;
      }
      case VersionControlConstants.DOWNLOAD_FAIL: {
        this.emitEvent(VersionControlConstants.DOWNLOAD_FAIL);
        break;
      }
      case VersionControlConstants.DOWNLOAD_STREAM: {
        this.emitEvent(VersionControlConstants.DOWNLOAD_STREAM);
        break;
      }
      case VersionControlConstants.DOWNLOAD_STREAM_SUCCESS: {
        this.emitEvent(VersionControlConstants.DOWNLOAD_STREAM_SUCCESS);
        break;
      }
      case VersionControlConstants.DOWNLOAD_STREAM_FAIL: {
        this.emitEvent(VersionControlConstants.DOWNLOAD_STREAM_FAIL);
        break;
      }
      case VersionControlConstants.UPLOAD: {
        this.versions.push({
          isService: true,
          customName: `V${this.versions.length + 1}`,
          creationTime: new Date(),
          id: action.serviceVersionId,
          modifier: {
            email: ""
          },
          permissions: {
            canDelete: false,
            canPromote: false,
            canRename: false,
            isDownloadable: false
          },
          size: 0,
          thumbnail: ""
        });
        this.emitEvent(VersionControlConstants.UPLOAD);
        break;
      }
      case VersionControlConstants.UPLOAD_SUCCESS: {
        this.emitEvent(VersionControlConstants.UPLOAD_SUCCESS);
        break;
      }
      case VersionControlConstants.UPLOAD_FAIL: {
        this.lastError = action.info.message || "error";
        if (action.serviceVersionId) {
          this.versions = this.versions.filter(
            version => version.id !== action.serviceVersionId
          );
        }
        this.emitEvent(VersionControlConstants.UPLOAD_FAIL);
        break;
      }
      case VersionControlConstants.REMOVE: {
        this.emitEvent(VersionControlConstants.REMOVE);
        break;
      }
      case VersionControlConstants.REMOVE_SUCCESS: {
        this.emitEvent(VersionControlConstants.REMOVE_SUCCESS);
        break;
      }
      case VersionControlConstants.REMOVE_FAIL: {
        this.emitEvent(VersionControlConstants.REMOVE_FAIL);
        break;
      }
      case VersionControlConstants.PROMOTE: {
        this.emitEvent(VersionControlConstants.PROMOTE);
        break;
      }
      case VersionControlConstants.PROMOTE_SUCCESS: {
        this.emitEvent(VersionControlConstants.PROMOTE_SUCCESS);
        break;
      }
      case VersionControlConstants.PROMOTE_FAIL: {
        this.lastError = action.info.message || this.lastError;
        this.emitEvent(VersionControlConstants.PROMOTE_FAIL);
        break;
      }
      case VersionControlConstants.SAVE_BEFORE_UPLOAD: {
        this.emitEvent(VersionControlConstants.SAVE_BEFORE_UPLOAD);
        break;
      }
      case VersionControlConstants.SAVE_BEFORE_UPLOAD_DONE: {
        this.emitEvent(VersionControlConstants.SAVE_BEFORE_UPLOAD_DONE);
        break;
      }
      default:
        break;
    }
  };

  getVersionsList() {
    return this.versions;
  }

  getVersionInfo(versionId) {
    return this.versions.find(elem => elem.id === versionId);
  }

  getLastError() {
    return this.lastError;
  }

  /**
   * Returns the latest version from already fetched versions
   * @returns {null|*}
   */
  getLatestVersion() {
    if (!this.versions) return null;

    return this.versions.reduce(
      (prevVersion, currentVersion) =>
        currentVersion.creationTime > prevVersion.creationTime
          ? currentVersion
          : prevVersion,
      { creationTime: -Infinity }
    );
  }

  /**
   * @private
   * @param eventType {string}
   */
  emitEvent(eventType) {
    this.emit(eventType);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  addChangeListener(eventType, callback) {
    this.on(eventType, callback);
  }

  /**
   * @public
   * @param eventType {string}
   * @param callback {Function}
   */
  removeChangeListener(eventType, callback) {
    this.removeListener(eventType, callback);
  }
}

VersionControlStore.dispatchToken = null;
const versionControlStore = new VersionControlStore();

export default versionControlStore;
