import md5 from "md5";
import _ from "underscore";
import AppDispatcher from "../dispatcher/AppDispatcher";
import * as UserInfoConstants from "../constants/UserInfoConstants";
import * as RequestsMethods from "../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../constants/validationSchemas/InputValidationFunctions";
import Requests from "../utils/Requests";
import Storage from "../utils/Storage";
import Logger from "../utils/Logger";
import ApplicationStore from "../stores/ApplicationStore";
import ApplicationActions from "./ApplicationActions";
import MainFunctions from "../libraries/MainFunctions";

import Tracker, { AK_GA } from "../utils/Tracker";
import UtmTracker from "../utils/UtmTracker";
import SnackbarUtils from "../components/Notifications/Snackbars/SnackController";
import { normalizeLocaleAndLang } from "../utils/languages";

/**
 * @class
 * @classdesc Actions for changing overall Application state
 */
export default class UserInfoActions {
  /**
   * @public
   * @static
   * @method
   * @description Get user info from server
   */
  static getUserInfo() {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_GET_INFO
    });
    Requests.sendGenericRequest(
      "/users",
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders(),
      undefined,
      [401]
    )
      .then(userInfo => {
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_INFO_UPDATE_SUCCESS,
          userInfo: userInfo.data
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_INFO_UPDATE_FAIL,
          err
        });
      });
  }

  /**
   * @public
   * @static
   * @method
   * @description Logs user in
   * @param username {String}
   * @param password {String}
   */
  static login(username, password) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_LOGIN
    });
    Storage.deleteValue("sessionDeleted");
    if (
      ApplicationStore.getApplicationSetting("featuresEnabled").independentLogin
    ) {
      Requests.sendGenericRequest(
        "/auth",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        ["*"]
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_LOGIN_FAIL
          });
        })
        .catch(authInfo => {
          if (authInfo.code !== 401) {
            AppDispatcher.dispatch({
              actionType: UserInfoConstants.USER_LOGIN_FAIL,
              code: authInfo.code,
              data: authInfo.data,
              err: authInfo
            });
          } else {
            const regExp = /"([a-zA-Z0-9./]+)"/g;
            const str = authInfo.headers.get("authenticate");
            let m;
            const parsed = [];
            do {
              m = regExp.exec(str);
              if (m) {
                parsed.push(m[1]);
              }
            } while (m);
            const authHeadersPreparation = [];
            authHeadersPreparation.push(
              md5(`${username.toLowerCase()}:${parsed[0]}:${password}`) // ha1
            );
            authHeadersPreparation.push(md5("POST:files.html"));
            /* eslint-disable */
            authHeadersPreparation.push(
              md5(
                `${authHeadersPreparation[0]}:${parsed[1]}:${authHeadersPreparation[1]}`
              )
            );
            /* eslint-enable */
            const headers = `Digest username="${username.toLowerCase()}",realm="${
              parsed[0]
            }",nonce="${parsed[1]}",uri="files.html",response="${
              authHeadersPreparation[2]
            }",opaque="${parsed[2]}"`;
            Requests.sendGenericRequest(
              "/authentication",
              RequestsMethods.POST,
              { Authorization: headers },
              undefined,
              ["*"],
              process.env.NODE_ENV !== "development"
            )
              .then(userInfo => {
                if (
                  Storage.store("EULAAccepted") !== "true" &&
                  ApplicationStore.getApplicationSetting("product") ===
                    "DraftSight"
                ) {
                  Storage.store("DSPreSID", userInfo.data.sessionId, true);
                  const UIPrefix =
                    ApplicationStore.getApplicationSetting("UIPrefix");
                  ApplicationActions.changePage(
                    `${UIPrefix}notify/?mode=showEULA&type=SW`
                  );
                } else {
                  AppDispatcher.dispatch({
                    actionType: UserInfoConstants.USER_LOGIN_SUCCESS,
                    data: userInfo.data,
                    code: userInfo.code
                  });
                }
              })
              .catch(err => {
                AppDispatcher.dispatch({
                  actionType: UserInfoConstants.USER_LOGIN_FAIL,
                  code: err.code,
                  data: err.text,
                  err
                });
              });
          }
        });
    } else {
      Requests.sendGenericRequest(
        "/users/foreign",
        RequestsMethods.POST,
        {},
        {
          email: username,
          password: btoa(password),
          pwdEncrypted: true
        },
        [401, 423, 403, 412, 400]
      )
        .then(userInfo => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_LOGIN_SUCCESS,
            data: userInfo.data,
            code: userInfo.code
          });
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_LOGIN_FAIL,
            code: err.code,
            data: err.data
          });
        });
    }
  }

  static signUp(email, password) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_SIGNUP
    });
    Storage.deleteValue("sessionDeleted");
    Requests.sendGenericRequest(
      "/users",
      RequestsMethods.POST,
      {},
      {
        email,
        password,
        passconfirm: password
      },
      [400]
    )
      .then(() => {
        SnackbarUtils.alertInfo({ id: "nextSignUpStage" });
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_SIGNUP_SUCCESS
        });
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_SIGNUP_FAIL,
          err
        });
      });
  }

  static signUpOnCustomerPortal(
    email,
    password,
    firstName,
    lastName,
    country,
    state,
    organization,
    city,
    phone,
    agreedToEula
  ) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_SIGNUP
    });
    Requests.sendGenericRequest(
      "/users/portal",
      RequestsMethods.POST,
      {},
      {
        email,
        password,
        firstName,
        lastName,
        passconfirm: password,
        country,
        state,
        organization,
        city,
        phone,
        agreedToEula
      },
      ["*"]
    )
      .then(() => {
        SnackbarUtils.alertInfo({ id: "confirmAccountCreation" });
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_SIGNUP_SUCCESS
        });
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_SIGNUP_FAIL,
          err
        });
      });
  }

  static integrateWithSolidWorksId() {
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    const UIUrl = `${location.origin}${UIPrefix}notify?mode=storage&type=solidworks&code=`;
    let apiUrl = `${ApplicationStore.getApplicationSetting("apiURL")}/saml`;
    if (!InputValidationFunctions.isURL(apiUrl)) {
      apiUrl = `${location.origin}${ApplicationStore.getApplicationSetting(
        "apiURL"
      )}/saml`;
    }
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_SW_INTEGRATION,
      UIUrl,
      apiUrl
    });
    location.href = `${ApplicationStore.getApplicationSetting(
      "oauthURL"
    )}?type=solidworks&mode=register&url=${encodeURIComponent(
      UIUrl
    )}&force_authn=${
      Storage.store("force_authn") === "true"
    }&serverUrl=${encodeURIComponent(apiUrl)}`;
  }

  static loginWithSSO(
    redirectURL = "",
    isEncoded = false,
    performRedirect = true
  ) {
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    const domain = ApplicationStore.getApplicationSetting("domain");
    const finalRedirect = isEncoded ? redirectURL : btoa(redirectURL);
    Storage.setItem("sessionDeleted", "false");
    UtmTracker.initializeTracker();
    const UIUrl = UtmTracker.updateLink(
      `${location.origin}${UIPrefix}notify?mode=account&type=sso${
        finalRedirect.length > 0
          ? `&bredirect=${MainFunctions.btoaForURLEncoding(finalRedirect)}`
          : ""
      }`
    );
    const signUpURL = UtmTracker.updateLink(
      `${location.origin}${UIPrefix}${
        finalRedirect.length > 0
          ? `?bredirect=${MainFunctions.btoaForURLEncoding(finalRedirect)}`
          : ""
      }`
    );
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_SSO_LOGIN,
      UIUrl
    });
    const finalLink = UtmTracker.updateLink(
      `${ApplicationStore.getApplicationSetting(
        "ssoURL"
      )}?encoding=b64&app_name=${encodeURIComponent(
        "ARES Kudo"
      )}&redirect_url=${btoa(UIUrl)}&sign_up_redirect=${btoa(signUpURL)}${
        domain.length > 0 ? `&domain=${domain}` : ""
      }`
    );
    if (performRedirect) location.replace(finalLink);
    return finalLink;
  }

  static getUserStorages() {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_GET_STORAGES
      });
      Requests.sendGenericRequest(
        "/integration/accounts",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        []
      )
        .then(storagesData => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_STORAGES_UPDATE_SUCCESS,
            storagesData: storagesData.data
          });
          resolve(storagesData.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_STORAGES_UPDATE_FAIL,
            err
          });
          reject(err);
        });
    });
  }

  static getStoragesConfiguration() {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_GET_STORAGES_CONFIG
      });
      Requests.sendGenericRequest(
        "/integration/settings",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        []
      )
        .then(config => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_STORAGES_CONFIG_SUCCESS,
            config: config.data
          });
          resolve(config.data);
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_STORAGES_CONFIG_FAIL,
            err
          });
          reject(err);
        });
    });
  }

  static connectStorage(storageType, redirectURI, params, scRedirect) {
    Tracker.sendGAEvent(
      AK_GA.category,
      AK_GA.labels.addingStorage,
      storageType
    );
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_ADD_STORAGE,
      storageType,
      redirectURI,
      params
    });
    return new Promise((resolve, reject) => {
      const noClientIdError = new Error("No client ID");
      const noStateError = new Error("No state from oauth");
      const { origin } = window.location;
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      const oauthURL = ApplicationStore.getApplicationSetting("oauthURL");
      let redirectParam = "";
      if (redirectURI) {
        redirectParam = `&bredirect=${btoa(redirectURI)}`;
      }
      let finalRedirectURL = `${origin}${UIPrefix}notify/?mode=storage&type=${storageType.toLowerCase()}${redirectParam}&code=`;
      if (scRedirect) {
        finalRedirectURL = atob(scRedirect);
      }
      Requests.sendGenericRequest(
        "/integration",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders()
      ).then(response => {
        const clientIds = response.data;
        switch (storageType) {
          case "hancomstg":
          case "hancom":
            window.location.href = `${oauthURL}?type=${storageType}&mode=register&url=${encodeURIComponent(
              finalRedirectURL
            )}`;
            break;
          case "box":
            Requests.sendGenericRequest(
              `${oauthURL}?type=box&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              if (!clientIds.box) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://app.box.com/api/oauth2/authorize?response_type=code&client_id=${clientIds.box}&state=${state._id}`;
              }
            });
            break;
          case "internal":
            Requests.sendGenericRequest(
              "/users",
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              {
                storage: {
                  type: "INTERNAL"
                }
              },
              []
            ).then(() => {
              AppDispatcher.dispatch({
                actionType: UserInfoConstants.USER_ADD_STORAGE_SUCCESS,
                type: "internal"
              });
            });
            break;
          case "samples":
            Requests.sendGenericRequest(
              "/users",
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              {
                storage: {
                  type: "SAMPLES"
                }
              },
              []
            ).then(() => {
              AppDispatcher.dispatch({
                actionType: UserInfoConstants.USER_ADD_STORAGE_SUCCESS,
                type: "samples"
              });
            });
            break;
          case "trimble":
            Requests.sendGenericRequest(
              `${oauthURL}?type=trimble&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              // TODO: update
              /* eslint-disable */
              const { storage_features } = window.ARESKudoConfigObject;
              /* eslint-enable */
              const trimbleURL = _.findWhere(storage_features, {
                name: "trimble"
              }).url;
              if (!clientIds.trimble) {
                reject(new Error("No client id"));
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `${trimbleURL}/authorize/?response_type=code&client_id=${
                  clientIds.trimble
                }&scope=${encodeURIComponent("openid ARESKudo")}&state=${
                  state._id
                }&redirect_uri=${encodeURIComponent(
                  `${oauthURL}?mode=storage&type=trimble`
                )}`;
              }
            });
            break;
          case "gdrive":
            if (!clientIds.gdrive) {
              reject(new Error("No client id"));
            } else {
              location.href = `${oauthURL}?type=google&mode=register&client_id=${
                clientIds.gdrive
              }&accessType=offline&url=${encodeURIComponent(finalRedirectURL)}`;
            }
            break;
          case "onshapestaging":
            Requests.sendGenericRequest(
              `${oauthURL}?type=onshapestaging&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              if (!clientIds.onshapestaging) {
                reject(new Error("No client id"));
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://staging-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
                  clientIds.onshapestaging
                )}&state=${state._id}&redirect_uri=${encodeURIComponent(
                  `${oauthURL}?mode=storage&type=onshapestaging`
                )}`;
              }
            });
            break;
          case "onshapedev":
            Requests.sendGenericRequest(
              `${oauthURL}?type=onshapedev&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              if (!clientIds.onshapedev) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://demo-c-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
                  clientIds.onshapedev
                )}&state=${state._id}&redirect_uri=${encodeURIComponent(
                  `${oauthURL}?mode=storage&type=onshapedev`
                )}`;
              }
            });
            break;
          case "onshape":
            Requests.sendGenericRequest(
              `${oauthURL}?type=onshape&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              if (!clientIds.onshape) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://oauth.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
                  clientIds.onshape
                )}&state=${state._id}&redirect_uri=${encodeURIComponent(
                  `${oauthURL}?mode=storage&type=onshape`
                )}`;
              }
            });
            break;
          case "dropbox":
            Requests.sendGenericRequest(
              `${oauthURL}?type=dropbox&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oauthResponse => {
              const state = oauthResponse.data;
              if (!clientIds.dropbox) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://www.dropbox.com/oauth2/authorize?response_type=code&token_access_type=offline&client_id=${
                  clientIds.dropbox
                }&force_reapprove=true&state=${
                  state._id
                }&redirect_uri=${encodeURIComponent(
                  `${oauthURL}?type=dropbox`
                )}`;
              }
            });
            break;
          case "onedrive":
            Requests.sendGenericRequest(
              `${oauthURL}/?type=onedrive&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(oneDriveResponse => {
              const state = oneDriveResponse.data;
              if (!clientIds.onedrive) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=${
                  clientIds.onedrive
                }&response_type=code&redirect_uri=${encodeURIComponent(
                  oauthURL
                )}&response_mode=query&scope=openid%20User.Read%20files.readwrite.all%20offline_access&state=${
                  state._id
                }`;
              }
            });
            break;
          case "onedrivebusiness":
          case "sharepoint":
            Requests.sendGenericRequest(
              `${oauthURL}/?type=onedrive&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(ODBResponse => {
              const state = ODBResponse.data;
              if (!clientIds.onedrivebusiness) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?scope=openid%20User.Read%20sites.read.all%20sites.readwrite.all%20files.read%20files.readwrite%20files.readwrite.all%20offline_access&response_type=code&state=${
                  state._id
                }&client_id=${
                  clientIds.onedrivebusiness
                }&redirect_uri=${encodeURIComponent(oauthURL)}`;
              }
            });
            break;
          case "odbadminconsent":
            Requests.sendGenericRequest(
              `${window.ARESKudoConfigObject.oauth}/?type=odbadminconsent&mode=register`,
              RequestsMethods.POST,
              {
                url: finalRedirectURL
              }
            ).then(ODBACResponse => {
              const state = ODBACResponse.data;
              if (!clientIds.onedrivebusiness) {
                reject(noClientIdError);
              } else if (!state._id) {
                reject(noStateError);
              } else {
                location.href = `https://login.microsoftonline.com/common/adminconsent?response_type=code&state=${
                  state._id
                }&client_id=${
                  clientIds.onedrivebusiness
                }&redirect_uri=${encodeURIComponent(oauthURL)}`;
              }
            });
            break;
          case "webdav":
            Requests.sendGenericRequest(
              "/users",
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              {
                storage: {
                  type: "WEBDAV",
                  password: params.password,
                  username: params.username,
                  url: params.url
                }
              },
              ["*"]
            )
              .then(() => {
                SnackbarUtils.alertOk({ id: "webDAVIsConnectedSuccessfully" });
                UserInfoActions.getUserStorages();
                UserInfoActions.getUserInfo();
              })
              .catch(err => {
                SnackbarUtils.alertError(err.text);
              });

            break;
          case "nextcloud":
            Requests.sendGenericRequest(
              "/users",
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              {
                storage: {
                  type: "NEXTCLOUD",
                  url: params.url
                }
              },
              ["*"]
            )
              .then(data => {
                resolve(data.data);
              })
              .catch(err => {
                SnackbarUtils.alertError(err.text);
                reject(err.text);
              });

            break;
          default:
            Logger.addEntry("WARNING", "Storage isn't integrated.");
            reject(new Error("Not supported storage type"));
            break;
        }
      });
    });
  }

  static switchToStorage(storageType, accountId, additional, callback) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_SWITCH_STORAGE_CHECK,
      storageType,
      accountId,
      additional,
      callback
    });
  }

  static reconnectStorage(storageType, redirectURI) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_STORAGE_RECONNECT
    });
    const { origin } = window.location;
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    const oauthURL = ApplicationStore.getApplicationSetting("oauthURL");
    let redirectParam = "";
    if (redirectURI) {
      redirectParam = `&bredirect=${btoa(redirectURI)}`;
    }
    const finalRedirectURL = `${origin}${UIPrefix}notify/?mode=storage&type=${storageType.toLowerCase()}${redirectParam}&code=`;
    Requests.sendGenericRequest(
      "/integration",
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders()
    ).then(response => {
      const clientIds = response.data;
      switch (storageType.toLowerCase()) {
        case "box":
          Requests.sendGenericRequest(
            `${oauthURL}?type=box&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            location.href = `https://app.box.com/api/oauth2/authorize?response_type=code&client_id=${clientIds.box}&state=${state._id}`;
          });
          break;
        case "trimble":
          Requests.sendGenericRequest(
            `${oauthURL}?type=trimble&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            // TODO: update
            /* eslint-disable */
            const { storage_features } = window.ARESKudoConfigObject;
            /* eslint-enable */
            const trimbleURL = _.findWhere(storage_features, {
              name: "trimble"
            }).url;
            location.href = `${trimbleURL}/authorize/?response_type=code&client_id=${
              clientIds.trimble
            }&scope=openid&state=${state._id}&redirect_uri=${encodeURIComponent(
              `${oauthURL}?mode=storage&type=trimble`
            )}`;
          });
          break;
        case "gdrive":
          location.href = `${oauthURL}?type=google&mode=register&client_id=${
            clientIds.google
          }&accessType=offline&url=${encodeURIComponent(finalRedirectURL)}`;
          break;
        case "onshapestaging":
          Requests.sendGenericRequest(
            `${oauthURL}?type=onshapestaging&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            location.href = `https://staging-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
              clientIds.onshapestaging
            )}&state=${state._id}&redirect_uri=${encodeURIComponent(
              `${oauthURL}?mode=storage&type=onshapestaging`
            )}`;
          });
          break;
        case "onshapedev":
          Requests.sendGenericRequest(
            `${oauthURL}?type=onshapedev&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            location.href = `https://demo-c-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
              clientIds.onshapedev
            )}&state=${state._id}&redirect_uri=${encodeURIComponent(
              `${oauthURL}?mode=storage&type=onshapedev`
            )}`;
          });
          break;
        case "onshape":
          Requests.sendGenericRequest(
            `${oauthURL}?type=onshape&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            location.href = `https://oauth.onshape.com/oauth/authorize?response_type=code&client_id=${encodeURIComponent(
              clientIds.onshape
            )}&state=${state._id}&redirect_uri=${encodeURIComponent(
              `${oauthURL}?mode=storage&type=onshape`
            )}`;
          });
          break;
        case "dropbox":
          Requests.sendGenericRequest(
            `${oauthURL}?type=dropbox&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(oauthResponse => {
            const state = oauthResponse.data;
            location.href = `https://www.dropbox.com/oauth2/authorize?response_type=code&token_access_type=offline&client_id=${
              clientIds.dropbox
            }&force_reapprove=true&state=${
              state._id
            }&redirect_uri=${encodeURIComponent(`${oauthURL}?type=dropbox`)}`;
          });
          break;
        case "onedrive":
          Requests.sendGenericRequest(
            `${window.ARESKudoConfigObject.oauth}/?type=onedrive&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(ODResponse => {
            const state = ODResponse.data;
            location.href = `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=${
              clientIds.onedrive
            }&response_type=code&redirect_uri=${encodeURIComponent(
              window.ARESKudoConfigObject.oauth
            )}&response_mode=query&scope=openid%20User.Read%20files.readwrite.all%20offline_access&state=${
              state._id
            }`;
          });
          break;
        case "onedrivebusiness":
          Requests.sendGenericRequest(
            `${window.ARESKudoConfigObject.oauth}/?type=onedrive&mode=register`,
            RequestsMethods.POST,
            {
              url: finalRedirectURL
            }
          ).then(ODBResponse => {
            const state = ODBResponse.data;
            location.href = `https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=${
              clientIds.onedrivebusiness
            }&scope=openid%20User.Read%20sites.read.all%20sites.readwrite.all%20files.read%20files.readwrite%20files.readwrite.all%20offline_access&response_type=code&redirect_uri=${encodeURIComponent(
              window.ARESKudoConfigObject.oauth
            )}&state=${state._id}`;
          });
          break;
        default:
          break;
      }
    });
  }

  static changeStorage(storageType, accountId, additional, callback) {
    // const tableReload =
    //   MainFunctions.detectPageType() !== "files/search" && false; // local disable for new browser
    const data = _.extend(
      {
        type: storageType.toUpperCase(),
        id: accountId
      },
      _.omit(additional || {}, "username")
    );
    // const currentTable =
    //   TableStore.getFocusedTable() || TableStore.getRegisteredTables()[0];
    // if (tableReload) {
    //   TableActions.setTableLock(currentTable);
    //   const tableInfo = TableStore.getTable(currentTable);
    //   if (tableInfo) {
    //     tableInfo.results = [];
    //     tableInfo.loading = true;
    //     TableActions.saveConfiguration(currentTable, tableInfo);
    //   }
    // }
    Requests.sendGenericRequest(
      "/integration/account",
      RequestsMethods.PUT,
      Requests.getDefaultUserHeaders(),
      data,
      [400]
    )
      .then(() => {
        // TODO: cache
        // if (tableReload && TableStore.isTableRegistered(currentTable)) {
        //   TableActions.releaseTableLock(currentTable);
        //   ApplicationActions.changePage(
        //     `${ApplicationStore.getApplicationSetting("UIPrefix")}files/-1`,
        //     "UIA_changeStorage"
        //   );
        // }
        // TODO
        // FilesListStore.resetStore(true)
        UserInfoActions.getUserInfo();
        if (callback) {
          callback();
        }
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_SWITCH_STORAGE_SUCCESS,
          storageType,
          accountId,
          additional
        });
      })
      .catch(() => {
        if (callback) {
          callback();
        }
        // TODO: For now just disable reconnectStorage(), later need to make special alert with
        // ability to reconnect to storage manually by pressing "reconnect" button on it.

        // UserInfoActions.reconnectStorage(storageType);
        // AppDispatcher.dispatch({
        //   actionType: UserInfoConstants.USER_SWITCH_STORAGE_SUCCESS,
        //   storageType,
        //   accountId
        // });
      });
  }

  static removeStorage(storageType, storageId) {
    Requests.sendGenericRequest(
      "/integration/account",
      RequestsMethods.DELETE,
      Requests.getDefaultUserHeaders(),
      {
        type: storageType.toUpperCase(),
        id: storageId
      }
    ).then(() => {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_REMOVE_STORAGE,
        type: storageType.toLowerCase(),
        id: storageId
      });
      UserInfoActions.getUserInfo();
      // replaced with "optimistic" update: see XENON-21672
      /* UserInfoActions.getUserStorages() */
    });
  }

  /**
   * Update specific user fields both on server and storage
   * @param {Object} newInfo - fields to update
   * @param {boolean?} isSilent - should Snackbars be shown
   * @param {boolean?} isUpdateUnnecessary - should fetch info from server
   * @return {Promise<void>}
   */
  static modifyUserInfo(
    newInfo,
    isSilent = false,
    isUpdateUnnecessary = false
  ) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_UPDATE_INFO,
      newInfo
    });
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        "/users",
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        newInfo,
        ["*"]
      )
        .then(answer => {
          if (answer.data.status !== "ok") {
            if (!isSilent) {
              SnackbarUtils.alertError(answer.error);
            }
            AppDispatcher.dispatch({
              actionType: UserInfoConstants.USER_UPDATE_INFO_FAIL,
              answer
            });
            reject();
          } else {
            if (
              Object.prototype.hasOwnProperty.call(newInfo, "lang") ||
              Object.prototype.hasOwnProperty.call(newInfo, "locale")
            ) {
              const { language, locale } = normalizeLocaleAndLang(
                newInfo.locale || newInfo.lang,
                true
              );
              ApplicationActions.changeLanguage(language, locale);
            } else {
              AppDispatcher.dispatch({
                actionType: UserInfoConstants.USER_UPDATE_INFO_SUCCESS,
                isUpdateUnnecessary
              });
            }
            resolve();
          }
        })
        .catch(err => {
          if (!isSilent) {
            SnackbarUtils.alertError(err.text);
          }
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_UPDATE_INFO_FAIL,
            err
          });
          reject();
        });
    });
  }

  static resetPassword(userId, hash, password, rePassword) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_RESET_PASSWORD,
      userId
    });
    if (password === rePassword) {
      Requests.sendGenericRequest(
        "/users/reset",
        RequestsMethods.POST,
        undefined,
        {
          userId,
          hash,
          password
        },
        [415, 403, 423, 400, 404, 412]
      )
        .then(answer => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_RESET_PASSWORD_SUCCESS,
            userId,
            sessionId: answer.data.sessionId
          });
        })
        .catch(error => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_RESET_PASSWORD_FAIL,
            error: error.text,
            code: error.code
          });
          SnackbarUtils.alertError(error.text);
        });
    } else {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_RESET_PASSWORD_FAIL,
        error: "passwords doesn't match"
      });
      SnackbarUtils.alertError({ id: "passwordNotMatch" });
    }
  }

  static logout() {
    return new Promise(resolve => {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_LOGOUT
      });
      // clear cookies before logout just in case of pending /auth requests
      const headers = _.clone(Requests.getDefaultUserHeaders());
      Storage.clearStorage();
      Storage.setItem("sessionDeleted", "true");
      Requests.sendGenericRequest(
        "/logout",
        RequestsMethods.POST,
        headers,
        {},
        []
      )
        .then(answer => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_LOGOUT_SUCCESS,
            answer
          });
        })
        .catch(() => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_LOGOUT_SUCCESS,
            answer: {}
          });
        })
        .finally(resolve);
    });
  }

  static saveLogoutRequest(logoutRequest) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_LOGOUT_INITIATED,
      logoutRequest
    });
  }

  static checkSession() {
    Logger.addEntry("SESSION_CHECK", Requests.getDefaultUserHeaders());
    return new Promise((resolve, reject) => {
      Requests.sendGenericRequest(
        "/auth",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        [401]
      )
        .then(() => {
          resolve();
        })
        .catch(() => {
          reject();
        });
    });
  }

  static getCompanyInfo(companyId) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_GET_COMPANY_INFO
    });
    Requests.sendGenericRequest(
      `/company/${companyId}`,
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders(),
      undefined,
      ["*"]
    )
      .then(companyInfo => {
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_COMPANY_INFO_GET_SUCCESS,
          companyInfo: companyInfo.data
        });
      })
      .catch(err => {
        AppDispatcher.dispatch({
          actionType: UserInfoConstants.USER_COMPANY_INFO_GET_FAIL,
          err
        });
      });
  }

  static updateCompanyInfo(companyId, newInfo) {
    return new Promise((resolve, reject) => {
      AppDispatcher.dispatch({
        actionType: UserInfoConstants.USER_GET_COMPANY_INFO
      });
      Requests.sendGenericRequest(
        `/company/${companyId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        newInfo,
        ["*"]
      )
        .then(() => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_COMPANY_INFO_UPDATE_SUCCESS,
            companyId
          });
          resolve();
        })
        .catch(err => {
          AppDispatcher.dispatch({
            actionType: UserInfoConstants.USER_COMPANY_INFO_UPDATE_FAIL,
            err
          });
          reject(err);
        });
    });
  }

  static validateSSOToken(state) {
    return new Promise((resolve, reject) => {
      Storage.deleteValue("sessionDeleted");
      Requests.sendGenericRequest(
        "/users/sso",
        RequestsMethods.POST,
        undefined,
        { state },
        ["*"]
      )
        .then(answer => {
          const { data } = answer;
          if (data.sessionId) {
            Storage.unsafeStoreSessionId(data.sessionId);
            const config = window.ARESKudoConfigObject;
            config.defaultheaders = JSON.stringify([
              {
                name: "sessionId",
                value: data.sessionId
              }
            ]);
            window.ARESKudoConfigObject = config;
            resolve();
          }
        })
        .catch(response => {
          // clear sessionId just in case
          Storage.deleteValue("sessionId");
          reject(response);
        });
    });
  }

  static disableStorage(storageType) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_STORAGE_DISABLED,
      storageType
    });
  }

  static updateSampleUsage(storageType, externalId, usage, quota) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.SAMPLE_USAGE_UPDATED,
      storageType,
      externalId,
      usage,
      quota
    });
  }

  static updateRecentFilesSwitchChangeState(state) {
    AppDispatcher.dispatch({
      actionType: UserInfoConstants.USER_RECENT_FILES_SWITCH_UPDATE,
      state
    });
  }
}
