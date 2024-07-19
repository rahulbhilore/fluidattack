import EventEmitter from "events";
import { List, fromJS } from "immutable";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as AdminConstants from "../constants/AdminConstants";
import AdminActions from "../actions/AdminActions";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";

export const USERS_EVENT = "USERS_EVENT";
export const REFRESH_USERS_LIST = "REFRESH_USERS_LIST";
class AdminStore extends EventEmitter {
  constructor() {
    super();
    this._users = new List();
    this._pageToken = null;
    this.dispatcherIndex = AppDispatcher.register(this.handleAction);
  }

  getUsersInfo() {
    return this._users;
  }

  getIndexByUserId(userId) {
    return this._users.findIndex(item => item.get("_id") === userId);
  }

  updateUser(userId, field, newValue) {
    const keyPath = [this.getIndexByUserId(userId), field];
    this._users = this._users.setIn(keyPath, newValue);
  }

  updateUserInfo(userId, newUserInfo) {
    const keyPath = [this.getIndexByUserId(userId)];
    this._users = this._users.setIn(keyPath, fromJS(newUserInfo));
  }

  removeUser(userId) {
    this._users = this._users.delete(this.getIndexByUserId(userId));
  }

  isUserLoaded(index) {
    return this._users.has(index);
  }

  getPageToken() {
    return this._pageToken;
  }

  loadUsers(additionUsers, append = false) {
    if (append) {
      this._users = this._users.concat(additionUsers);
    } else {
      this._users = additionUsers;
    }
  }

  isUsersToLoad() {
    return !!this._pageToken;
  }

  handleAction = action => {
    if (action.actionType.indexOf(AdminConstants.constantPrefix) > -1) {
      switch (action.actionType) {
        case AdminConstants.ADMIN_LOAD_USERS_SUCCESS:
          this.loadUsers(action.users, action.append);
          this._pageToken = action.pageToken;
          if (action.callback) action.callback(this._users);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_FIND_USERS_SUCCESS:
          // DK: Not sure if we have to save found users separately.
          // Probably not.
          this._users = action.users;
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_TOGGLE_ACCESS_SUCCESS:
          this.updateUser(action.data.userId, "enabled", action.data.enabled);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_CHANGE_USER_ROLE_SUCCESS:
          this.updateUser(action.data.userId, "isAdmin", action.data.isAdmin);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_CHANGE_USER_OPTIONS_SUCCESS:
          this.updateUser(action.data.userId, "options", action.data.options);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_COMPLIANCE_SUCCESS:
          this.updateUser(
            action.data.userId,
            "complianceStatus",
            action.data.compStatus
          );
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_DELETE_USER_IN_PROGRESS:
          this.updateUser(action.data.userId, "deleteId", action.data.deleteId);
          this.updateUser(action.data.userId, "process", "delete");
          // recheck every 5 seconds
          setTimeout(() => {
            AdminActions.deleteUserCheck(
              action.data.userId,
              action.data.deleteId
            );
          }, 5000);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_DELETE_USER_SUCCESS:
          this.removeUser(action.data.userId);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_UPDATE_USER_INFO_SUCCESS:
          this.updateUserInfo(action.userId, action.data);
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_LOAD_USERS_FAIL:
        case AdminConstants.ADMIN_TOGGLE_ACCESS_FAIL:
        case AdminConstants.ADMIN_CHANGE_USER_ROLE_FAIL:
        case AdminConstants.ADMIN_CHANGE_USER_OPTIONS_FAIL:
        case AdminConstants.ADMIN_COMPLIANCE_FAIL:
          SnackbarUtils.alertError(action.err.toString());
          this.emit(USERS_EVENT);
          break;
        case AdminConstants.ADMIN_DELETE_USER_FAIL:
          SnackbarUtils.alertError(action.err.toString());
          this.emit(REFRESH_USERS_LIST);
          break;
        default:
          break;
      }
    }
  };
}

AdminStore.dispatchToken = null;
const adminStore = new AdminStore();

export default adminStore;
