import Immutable from "immutable";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as AdminConstants from "../constants/AdminConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import Requests from "../utils/Requests";
import UserInfoStore from "../stores/UserInfoStore";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";

export default class AdminActions {
  static loadUsers(pageToken = false) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_LOAD_USERS
    });
    const headers = Requests.getDefaultUserHeaders();

    if (pageToken) headers.pageToken = pageToken;

    Requests.sendGenericRequest(
      "/admin/users",
      RequestsMethods.GET,
      headers,
      undefined,
      ["*"]
    )
      .then(response => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_LOAD_USERS_SUCCESS,
          users: Immutable.fromJS(response.data.results),
          pageToken: response.data.pageToken,
          append: pageToken !== false
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_LOAD_USERS_FAIL,
          err
        });
      });
  }

  static findUsers(query) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_FIND_USERS,
      query
    });
    const headers = Requests.getDefaultUserHeaders();
    headers.pattern = query;
    Requests.sendGenericRequest(
      "/users/find",
      RequestsMethods.GET,
      headers,
      undefined,
      ["*"]
    )
      .then(response => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_FIND_USERS_SUCCESS,
          users: Immutable.fromJS(response.data)
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_FIND_USERS_FAIL,
          err
        });
      });
  }

  static toggleAccess(userId, isEnabled) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_TOGGLE_ACCESS
    });
    Requests.sendGenericRequest(
      `/admin/users/${userId}`,
      RequestsMethods.PUT,
      Requests.getDefaultUserHeaders(),
      { enabled: isEnabled },
      ["*"]
    )
      .then(() => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_TOGGLE_ACCESS_SUCCESS,
          data: { userId, enabled: isEnabled }
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_TOGGLE_ACCESS_FAIL,
          err
        });
      });
  }

  static complianceOverride(userId, compStatus) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_COMPLIANCE
    });
    Requests.sendGenericRequest(
      `/admin/users/${userId}`,
      RequestsMethods.PUT,
      Requests.getDefaultUserHeaders(),
      { complianceStatus: compStatus },
      ["*"]
    )
      .then(() => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_COMPLIANCE_SUCCESS,
          data: { userId, compStatus }
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_COMPLIANCE_FAIL,
          err
        });
      });
  }

  static toggleRole(userId, isAdmin) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_CHANGE_USER_ROLE
    });
    Requests.sendGenericRequest(
      `/admin/users/${userId}`,
      RequestsMethods.PUT,
      Requests.getDefaultUserHeaders(),
      {
        [isAdmin ? "rolesRemove" : "rolesAdd"]: ["1"]
      },
      ["*"]
    )
      .then(() => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_CHANGE_USER_ROLE_SUCCESS,
          data: { userId, isAdmin }
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_CHANGE_USER_ROLE_FAIL,
          err
        });
      });
  }

  static deleteUser(userId) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_DELETE_USER,
      userId
    });
    Requests.sendGenericRequest(
      `/admin/users/${userId}`,
      RequestsMethods.DELETE,
      Requests.getDefaultUserHeaders(),
      {},
      ["*"]
    )
      .then(response => {
        if (response.code === 200) {
          if (response.data.token) {
            // request has been created
            AppDispatcher.dispatch({
              actionType: AdminConstants.ADMIN_DELETE_USER_IN_PROGRESS,
              data: {
                userId,
                deleteId: response.data.token
              }
            });
          } else {
            // request has been finished
            AppDispatcher.dispatch({
              actionType: AdminConstants.ADMIN_DELETE_USER_SUCCESS,
              data: {
                userId,
                deleteId: response.data.token
              }
            });
          }
        } else if (response.code === 202) {
          // unexpected in progress status?
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_DELETE_USER_FAIL,
            data: {
              userId,
              deleteId: response.data.token || null
            }
          });
        } else {
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_DELETE_USER_FAIL,
            response
          });
        }
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_DELETE_USER_FAIL,
          err: err.text,
          code: err.code
        });
      });
  }

  static deleteUserCheck(userId, deleteId) {
    const headers = Requests.getDefaultUserHeaders();
    headers.token = deleteId;
    Requests.sendGenericRequest(
      `/admin/users/${userId}`,
      RequestsMethods.DELETE,
      headers,
      {},
      ["*"]
    )
      .then(response => {
        if (response.code === 200) {
          if (response.data.token) {
            // request has been created
            AppDispatcher.dispatch({
              actionType: AdminConstants.ADMIN_DELETE_USER_IN_PROGRESS,
              data: {
                userId,
                deleteId
              }
            });
          } else {
            // request has been finished
            AppDispatcher.dispatch({
              actionType: AdminConstants.ADMIN_DELETE_USER_SUCCESS,
              data: {
                userId,
                deleteId
              }
            });
          }
        } else if (response.code === 202) {
          // unexpected in progress status?
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_DELETE_USER_IN_PROGRESS,
            data: {
              userId,
              deleteId
            }
          });
        } else {
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_DELETE_USER_FAIL,
            response
          });
        }
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_DELETE_USER_FAIL,
          err
        });
      });
  }

  static changeUserOptions(userId, options) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_CHANGE_USER_OPTIONS,
      userId,
      options
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/admin/users/${userId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        { options: options.toJS() }
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_CHANGE_USER_OPTIONS_SUCCESS,
            data: {
              userId,
              options
            }
          });
          resolve();
          if (userId === UserInfoStore.getUserInfo("id")) {
            window.location.reload();
          }
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_CHANGE_USER_OPTIONS_FAIL,
            err
          });
          reject(err);
        });
    });
  }

  static triggerSkeletonCreation(userId, isForce = false) {
    const requestBody = { storageType: "SAMPLES", force: isForce };
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_CREATE_SKELETON,
      userId,
      requestBody
    });
    Requests.sendGenericRequest(
      `/admin/skeleton/${userId}`,
      RequestsMethods.POST,
      Requests.getDefaultUserHeaders(),
      requestBody,
      ["*"]
    )
      .then(() => {
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_CREATE_SKELETON_SUCCESS,
          data: {
            userId,
            requestBody
          }
        });
        SnackbarUtils.alertOk("Skeleton has been created");
        if (userId === UserInfoStore.getUserInfo("id")) {
          window.location.reload();
        }
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
        AppDispatcher.dispatch({
          actionType: AdminConstants.ADMIN_CREATE_SKELETON_FAIL,
          err
        });
      });
  }

  static getListOfStoragesOnInstance() {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/admin/storages`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
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
  }

  static getFullStoragesInfoForUser(userId) {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/admin/users/${userId}/accounts`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
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
  }

  static disableThumbnails(userId, doDisable, filters) {
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        "/admin/thumbnail",
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        {
          disableThumbnail: doDisable,
          disableThumbnailFilters: {
            users: {
              id: userId
            },
            ...filters
          }
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
  }

  static getUserInfo(userId) {
    AppDispatcher.dispatch({
      actionType: AdminConstants.ADMIN_UPDATE_USER_INFO,
      userId
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        `/admin/users/${userId}`,
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(response => {
          const { data } = response;
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_UPDATE_USER_INFO_SUCCESS,
            userId,
            data: data.results[0]
          });
          resolve(data.results[0]);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: AdminConstants.ADMIN_UPDATE_USER_INFO_FAIL,
            userId,
            err
          });
          reject(new Error(err.text));
        });
    });
  }
}
