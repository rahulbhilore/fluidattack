import React, { Component } from "react";
import $ from "jquery";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import md5 from "md5";
import propTypes from "prop-types";
import styled from "@material-ui/core/styles/styled";
import { Typography } from "@material-ui/core";
import ApplicationActions from "../../actions/ApplicationActions";
import UserInfoActions from "../../actions/UserInfoActions";
import UserInfoStore, {
  INFO_UPDATE,
  RESET_PASSWORD,
  STORAGES_UPDATE
} from "../../stores/UserInfoStore";
import ApplicationStore, { CONFIG_LOADED } from "../../stores/ApplicationStore";
import * as RequestsMethods from "../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../constants/validationSchemas/InputValidationFunctions";
import Requests from "../../utils/Requests";
import Storage from "../../utils/Storage";
import Logger from "../../utils/Logger";
import MainFunctions from "../../libraries/MainFunctions";
import KudoForm from "../Inputs/KudoForm/KudoForm";
import KudoInput from "../Inputs/KudoInput/KudoInput";
import KudoCheckbox from "../Inputs/KudoCheckbox/KudoCheckbox";
import KudoButton from "../Inputs/KudoButton/KudoButton";
import Tracker, { AK_GA } from "../../utils/Tracker";
import kudoLogoSmall from "../../assets/images/kudo-logo-small.svg";
import DSLogoSmall from "../../assets/images/DS/logo.png";
import Loader from "../Loader";

let query = {};
const isCommander =
  location.pathname.indexOf("commander") > -1 ||
  window.navigator.userAgent.indexOf("ARES Commander") > -1;

const timeout = isCommander ? 0 : 3000;

const Main = styled("main")(({ theme }) => ({
  flexGrow: 1,
  backgroundColor: theme.palette.SNOKE,
  backgroundSize: "cover",
  display: "flex",
  alignItems: "center",
  justifyContent: "center"
}));

const Logo = styled("img")(({ theme }) => ({
  height: "85px",
  marginBottom: theme.spacing(2)
}));

const Content = styled("div")({
  textAlign: "center",
  minHeight: "400px"
});

const Spacer = styled("div")(({ theme }) => ({
  height: theme.spacing(2),
  width: "100%"
}));

export default class NotifyLoader extends Component {
  static propTypes = {
    params: propTypes.shape({
      [propTypes.string]: propTypes.string
    }).isRequired
  };

  static setJqueryListeners() {
    const iframeElement = document.getElementById("EULAframe");

    if (!iframeElement) return;

    let iframeHeight = 250;
    const maxHeight = Math.max(window.innerHeight, document.body.clientHeight);
    const maxWidth = Math.max(window.innerWidth, document.body.clientWidth);
    if (maxHeight > 500 && maxWidth > 768) {
      iframeHeight = maxHeight * 0.6;
    } else {
      iframeElement.onload = () => {
        iframeHeight =
          iframeElement.contentWindow.document.body.scrollHeight * 1.025;
        iframeElement.style.height = `${iframeHeight}px`;
      };
    }
    iframeElement.style.height = `${iframeHeight}px`;
    MainFunctions.attachJQueryListener($(window), "resize", () => {
      let defaultIframeHeight = 250;
      const maxHeightAvailable = Math.max(
        window.innerHeight,
        document.body.clientHeight
      );
      const maxWidthAvailable = Math.max(
        window.innerWidth,
        document.body.clientWidth
      );
      if (maxHeightAvailable > 500 && maxWidthAvailable > 768) {
        defaultIframeHeight = maxHeightAvailable * 0.6;
      } else {
        defaultIframeHeight =
          iframeElement.contentWindow.document.body.scrollHeight * 1.025;
      }
      document.getElementById(
        "EULAframe"
      ).style.height = `${defaultIframeHeight}px`;
    });
  }

  static getRedirectURL(emptyAllowed) {
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    let AKURL = UIPrefix;
    if (query.redirect) {
      AKURL += decodeURIComponent(query.redirect);
    } else if (query.bredirect) {
      let decodedRedirect = atob(
        MainFunctions.atobForURLEncoding(query.bredirect)
      );
      if (decodedRedirect.includes("%")) {
        // means that it is pre urlencoded
        decodedRedirect = decodeURIComponent(decodedRedirect);
      }
      AKURL += decodedRedirect;
    } else if (isCommander === true) {
      AKURL += "commander/storages";
    } else if (emptyAllowed === true) {
      return "";
    } else if (Storage.getItem("sessionId")) {
      AKURL += "files";
    }
    if (AKURL.startsWith(`${UIPrefix}${UIPrefix}`)) {
      AKURL = AKURL.substr(UIPrefix.length);
    }
    return AKURL;
  }

  // XENON-52325 - for Box external file if connection exists
  // we need to remove "+0" from BX+xxx+yyy+0 to indicate that
  // this is basically regular open
  static checkBoxExternalFile() {
    if (query.bredirect) {
      let decodedRedirect = atob(
        MainFunctions.atobForURLEncoding(query.bredirect)
      );
      if (decodedRedirect.includes("%")) {
        // means that it is pre urlencoded
        decodedRedirect = decodeURIComponent(decodedRedirect);
      }
      if (
        // check that this is an external file
        decodedRedirect.includes("file/external") &&
        // check that it's opened from Box
        decodedRedirect.includes("type=box")
      ) {
        // we already know it's box
        const storageName = "box";

        const connectedStorages = UserInfoStore.getStoragesInfo()[storageName];

        if (!connectedStorages) return false;

        // find out query
        const [, foundQuery] = decodedRedirect.split("?");

        const params = new URLSearchParams(foundQuery);

        const obj = {};

        // iterate over all keys
        // eslint-disable-next-line no-restricted-syntax
        for (const key of params.keys()) {
          if (params.getAll(key).length > 1) {
            obj[key] = params.getAll(key);
          } else {
            obj[key] = params.get(key);
          }
        }
        const { userId: externalId, fileId } = obj;

        const isAccountConnected = !!connectedStorages.find(
          elem => elem[`${storageName}_id`] === externalId
        );

        if (!isAccountConnected) return false;
        ApplicationActions.changePage(
          `file/BX+${externalId}+${fileId}`,
          "NL_CBEF"
        );
        return true;
      }
    }
    return false;
  }

  constructor() {
    super();
    this.state = {
      message: null,
      type: null,
      timer: false,
      configLoaded: false,
      showSpinnerOnly: false,
      isError: false
    };
  }

  componentDidMount() {
    $("header").hide();
    document.title = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )}`;
    // $("body").css("height", "100%");
    // $("html").css("height", "100%");
    // $("#react").css("height", "100%");

    ApplicationStore.addChangeListener(CONFIG_LOADED, this.onConfigLoaded);
    const { params } = this.props;
    query = _.extend(MainFunctions.QueryString(), params);

    if (isCommander) {
      MainFunctions.updateBodyClasses(["commander"]);
    }
    if (
      Storage.store("latestNotify") ===
      md5(`${Storage.store("loadTimestamp")}${JSON.stringify(query)}`)
    ) {
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      if (Storage.store("sessionId")) {
        ApplicationActions.changePage(`${UIPrefix}files`);
      } else {
        ApplicationActions.changePage(UIPrefix);
      }
    } else {
      this.handleIntegration(query)
        .then(() => {
          const { timer, time } = this.state;
          if (!timer || !time) {
            history.replaceState(
              {},
              ApplicationStore.getApplicationSetting("product"),
              NotifyLoader.getRedirectURL()
            );
          }
        })
        .catch(() => {
          // no actions
        });
      Storage.store(
        "latestNotify",
        md5(`${Storage.store("loadTimestamp")}${JSON.stringify(query)}`)
      );
    }
  }

  componentDidUpdate() {
    const { timer, time, timerFunction, timerFunctionArgs } = this.state;
    if (timer) {
      setTimeout(() => {
        this.setState({ time: time - 1000 }, () => {
          if (time < 1000) {
            if (timerFunction) {
              timerFunction.call(this, timerFunctionArgs);
            } else {
              history.replaceState(
                {},
                ApplicationStore.getApplicationSetting("product"),
                NotifyLoader.getRedirectURL()
              );
              ApplicationActions.changePage(NotifyLoader.getRedirectURL());
            }
          }
        });
      }, 1000);
    }
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(
      INFO_UPDATE,
      this.handlePermissionsCheck
    );
    UserInfoStore.removeChangeListener(
      RESET_PASSWORD,
      this.handlePasswordReset
    );
    $("header").show();
    ApplicationStore.removeChangeListener(CONFIG_LOADED, this.onConfigLoaded);
  }

  onConfigLoaded = () => {
    this.setState({ configLoaded: true });
  };

  handlePermissionsCheck = () => {
    if (query.mode === "activate") {
      if (UserInfoStore.getUserInfo("isAdmin")) {
        this.setState(
          {
            message: { id: "waitForConfirmationFinish" }
          },
          () => {
            Requests.sendGenericRequest(
              `/admin/users/${query.key}`,
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              { enabled: true },
              [401]
            )
              .then(() => {
                this.setState({
                  message: { id: "accountIsActivated" },
                  timer: true,
                  time: timeout
                });
              })
              .catch(error => {
                if (parseInt(error.code || 0, 10) === 401) {
                  this.setState({
                    message: { id: "haveToBeAnAdmin" },
                    timer: true,
                    time: timeout
                  });
                }
              });
          }
        );
      } else {
        this.setState({
          message: { id: "haveToBeLoggedInAsAdmin" },
          timer: true,
          time: timeout
        });
      }
    }
  };

  checkIfStorageHasToBeRemoved = () => {
    const userActiveStorageInfo = UserInfoStore.getUserInfo("storage");
    if (userActiveStorageInfo) {
      Requests.sendGenericRequest(
        "/files",
        RequestsMethods.GET,
        Requests.getDefaultUserHeaders(),
        undefined,
        [400]
      )
        .then(() => {
          this.setState({
            timer: true,
            time: timeout
          });
        })
        .catch(error => {
          if (error.code === 400) {
            UserInfoActions.removeStorage(
              userActiveStorageInfo.type,
              userActiveStorageInfo.id
            );
            UserInfoStore.removeChangeListener(
              INFO_UPDATE,
              this.checkIfStorageHasToBeRemoved
            );
            UserInfoStore.addChangeListener(
              INFO_UPDATE,
              this.afterStorageDeleteCallback
            );
          }
        });
    }
  };

  afterStorageDeleteCallback = () => {
    UserInfoStore.removeChangeListener(
      INFO_UPDATE,
      this.afterStorageDeleteCallback
    );
    this.setState({
      timer: true,
      time: timeout
    });
  };

  handleIntegration = () =>
    new Promise((resolve, reject) => {
      switch (query.mode) {
        case "confirm":
          if (query.key.length && query.hash.length) {
            this.setState(
              {
                message: { id: "waitForConfirmationFinish" }
              },
              () => {
                Requests.sendGenericRequest(
                  "/users/confirm",
                  RequestsMethods.POST,
                  Requests.getDefaultUserHeaders(),
                  {
                    userId: query.key,
                    hash: query.hash
                  }
                )
                  .then(response => {
                    const { data } = response;
                    if (data.status !== "ok") {
                      this.setState({ message: data.code }, resolve);
                    } else {
                      this.setState(
                        {
                          message: { id: "waitForAdminConfirm" },
                          timer: true,
                          time: timeout
                        },
                        resolve
                      );
                    }
                  })
                  .catch(error => {
                    this.setState({ message: error.text }, resolve);
                  });
              }
            );
          } else {
            this.handleInvalidQuery();
            reject();
          }
          break;
        case "activate":
          if (query.key.length) {
            if (Storage.store("sessionId")) {
              UserInfoActions.getUserInfo();
              UserInfoStore.addChangeListener(
                INFO_UPDATE,
                this.handlePermissionsCheck
              );
              resolve();
            } else {
              this.setState(
                {
                  message: { id: "haveToBeAnAdmin" },
                  timer: true,
                  time: timeout
                },
                resolve
              );
            }
          } else {
            this.handleInvalidQuery();
            reject();
          }
          break;
        case "reset":
          if (query.key.length) {
            this.setState({
              message: { id: "checkPassQuery" }
            });
            Requests.sendGenericRequest(
              "/users/tryReset",
              RequestsMethods.POST,
              Requests.getDefaultUserHeaders(),
              {
                userId: query.key,
                hash: query.hash
              },
              [401, 400]
            )
              .then(() => {
                this.setState(
                  {
                    message: { id: "forgotPassword" },
                    type: "RESET_PASSWORD"
                  },
                  () => {
                    UserInfoStore.addChangeListener(
                      RESET_PASSWORD,
                      this.handlePasswordReset
                    );
                    resolve();
                  }
                );
              })
              .catch(error => {
                if (error.code === 401) {
                  this.setState(
                    {
                      message: { id: "shouldBeLoggedIn" },
                      timer: true,
                      time: timeout
                    },
                    resolve
                  );
                } else {
                  this.setState(
                    {
                      message: error.text,
                      timer: true,
                      time: timeout
                    },
                    resolve
                  );
                }
              });
          } else {
            this.handleInvalidQuery();
            reject();
          }
          break;
        case "confirmEmail":
          if (query.key.length && query.hash.length) {
            this.setState(
              {
                message: { id: "waitForConfirmationFinish" }
              },
              () => {
                Requests.sendGenericRequest(
                  "/users/email",
                  RequestsMethods.POST,
                  Requests.getDefaultUserHeaders(),
                  {
                    userId: query.key,
                    hash: encodeURIComponent(query.hash)
                  },
                  [400, 415]
                )
                  .then(() => {
                    this.setState(
                      {
                        message: { id: "emailChanged" },
                        timer: true,
                        time: timeout
                      },
                      resolve
                    );
                  })
                  .catch(error => {
                    switch (parseInt(error.code, 10)) {
                      case 415:
                        // body isn't json
                        Logger.addEntry("ERROR", "body isn't json");
                        break;
                      case 400:
                        Logger.addEntry("ERROR", "incorrect data");
                        break;
                      default:
                        break;
                    }
                    this.setState(
                      {
                        message: error.text
                      },
                      resolve
                    );
                  });
              }
            );
          } else {
            this.handleInvalidQuery();
            reject();
          }
          break;
        case "account":
          if (query.type === "sso") {
            if (query.state.length) {
              this.setState(
                {
                  showSpinnerOnly: true
                },
                () => {
                  UserInfoActions.validateSSOToken(query.state)
                    .then(() => {
                      // have to request info because this one is useless in terms of storages
                      UserInfoActions.getUserInfo();
                      UserInfoStore.addChangeListener(
                        INFO_UPDATE,
                        this.onUserInfoReceived
                      );
                    })
                    .catch(response => {
                      this.setState(
                        {
                          showSpinnerOnly: false,
                          message: {
                            id: "externalStorageIntegrationError",
                            values: {
                              error: response.text,
                              storage: "SSO"
                            }
                          },
                          timer: true,
                          time: MainFunctions.calculateTimeout(response.text),
                          isError: true
                        },
                        resolve
                      );
                    });
                }
              );
            }
          } else {
            this.handleInvalidQuery();
            reject();
          }
          break;
        case "storage": {
          const storageName = query.type;
          const apiStorageName = storageName.toUpperCase();
          let capitalizedStorageName =
            storageName.charAt(0).toUpperCase() + storageName.slice(1);
          if (query.type === "gdrive") {
            capitalizedStorageName = "Google Drive";
          } else if (query.type === "onedrivebusiness") {
            capitalizedStorageName = "OneDrive for Business";
          } else if (query.type === "onshapedev") {
            capitalizedStorageName = "Onshape Dev";
          } else if (query.type === "onshapestaging") {
            capitalizedStorageName = "Onshape Staging";
          }
          switch (query.type) {
            case "onshape":
            case "onshapedev":
            case "onshapestaging":
            case "box":
            case "trimble":
            case "gdrive":
            case "onedrive":
            case "onedrivebusiness":
            case "sharepoint":
            case "hancom":
            case "hancomstg":
            case "dropbox": {
              if (query.error) {
                const error = `${MainFunctions.URLDecode(
                  query.error_description
                )}(${query.error})`;
                if (Storage.store("sessionId")) {
                  this.setState(
                    {
                      message: {
                        id: "externalStorageIntegrationError",
                        values: {
                          error,
                          storage: capitalizedStorageName
                        }
                      }
                    },
                    () => {
                      UserInfoActions.getUserInfo();
                      UserInfoStore.addChangeListener(
                        INFO_UPDATE,
                        this.checkIfStorageHasToBeRemoved
                      );
                      resolve();
                    }
                  );
                } else {
                  this.setState(
                    {
                      message: {
                        id: "externalStorageIntegrationError",
                        values: {
                          error,
                          storage: capitalizedStorageName
                        }
                      },
                      timer: true,
                      time: MainFunctions.calculateTimeout(error)
                    },
                    resolve
                  );
                }
              } else if (query.code) {
                this.setState(
                  {
                    message: {
                      id: "externalStorageIntegrationCompleting",
                      values: { storage: capitalizedStorageName }
                    }
                  },
                  () => {
                    const storageCode = query.code;
                    Requests.sendGenericRequest(
                      "/users",
                      RequestsMethods.PUT,
                      Requests.getDefaultUserHeaders(),
                      {
                        storage: {
                          type: apiStorageName,
                          authCode: storageCode
                        }
                      },
                      [400, 415, 500]
                    )
                      .then(() => {
                        this.setState(
                          {
                            message: {
                              id: "externalStorageIntegrationSuccess",
                              values: { storage: capitalizedStorageName }
                            },
                            timer: true,
                            time: timeout
                          },
                          resolve
                        );
                      })
                      .catch(error => {
                        switch (parseInt(error.code, 10)) {
                          case 415:
                            // body isn't json
                            Logger.addEntry("ERROR", "body isn't json");
                            break;
                          case 400:
                            Logger.addEntry("ERROR", "incorrect data");
                            break;
                          default:
                            break;
                        }
                        this.setState(
                          {
                            message: {
                              id: "externalStorageIntegrationError",
                              values: {
                                error: error.text,
                                storage: capitalizedStorageName
                              }
                            },
                            timer: true,
                            time: MainFunctions.calculateTimeout(error.text)
                          },
                          resolve
                        );
                      });
                  }
                );
              } else {
                this.setState(
                  {
                    message: { id: "unsupportedExtStorage" }
                  },
                  resolve
                );
              }
              break;
            }
            case "solidworks":
              if (Storage.store("sessionId")) {
                UserInfoActions.logout();
                resolve();
              } else if (query.code) {
                Requests.sendGenericRequest(
                  "/users/foreign",
                  RequestsMethods.POST,
                  { SAMLResponse: query.code },
                  undefined,
                  ["*"]
                )
                  .then(answer => {
                    const { data } = answer;
                    if (data.sessionId) {
                      const UIPrefix =
                        ApplicationStore.getApplicationSetting("UIPrefix");
                      if (Storage.store("EULAAccepted") === "true") {
                        Storage.unsafeStoreSessionId(data.sessionId);
                        ApplicationActions.changePage(`${UIPrefix}files`);
                      } else {
                        this.setState(
                          {
                            sessionId: data.sessionId,
                            type: "SW"
                          },
                          NotifyLoader.setJqueryListeners
                        );
                      }
                      resolve();
                    }
                  })
                  .catch(response => {
                    // clear sessionId just in case
                    Storage.deleteValue("sessionId");
                    if (
                      Object.prototype.hasOwnProperty.call(
                        response.data,
                        "nameId"
                      ) &&
                      Object.prototype.hasOwnProperty.call(
                        response.data,
                        "sessionIndex"
                      )
                    ) {
                      Storage.deleteValue("sessionId");
                      location.href = `${ApplicationStore.getApplicationSetting(
                        "oauthURL"
                      )}?type=solidworks&mode=logout&nameId=${
                        response.data.nameId
                      }&sessionIndex=${
                        response.data.sessionIndex
                      }&url=${encodeURIComponent(
                        location.origin +
                          ApplicationStore.getApplicationSetting("UIPrefix")
                      )}`;
                    }
                    this.setState(
                      {
                        message: {
                          id: "externalStorageIntegrationError",
                          values: {
                            error: response.text,
                            storage: capitalizedStorageName
                          }
                        },
                        timer: true,
                        time: MainFunctions.calculateTimeout(response.text)
                      },
                      resolve
                    );
                  });
              }
              break;
            case "odbadminconsent":
              if (query.error || query.status !== "true") {
                this.setState(
                  {
                    message: {
                      id: "OneDriveBusinessAdminConsentError",
                      values: {
                        error: `${MainFunctions.URLDecode(
                          query.error_description || ""
                        )}(${query.error})`
                      }
                    },
                    timer: true,
                    time: timeout
                  },
                  resolve
                );
              } else if (query.status === "true") {
                this.setState(
                  {
                    message: {
                      id: "OneDriveBusinessAdminConsentSuccess"
                    },
                    timer: true,
                    time: timeout
                  },
                  resolve
                );
              } else {
                this.setState(
                  {
                    message: { id: "unsupportedExtStorage" }
                  },
                  resolve
                );
              }
              break;
            default:
              this.setState(
                {
                  message: { id: "unsupportedExtStorage" }
                },
                resolve
              );
          }
          break;
        }
        case "showEULA": {
          const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
          const receivedSessionId = Storage.store("DSPreSID", undefined, true);
          Storage.deleteValue("DSPreSID", true);
          if (Storage.store("EULAAccepted") === "true") {
            Storage.unsafeStoreSessionId(receivedSessionId);
            ApplicationActions.changePage(`${UIPrefix}files`);
          } else {
            this.setState(
              {
                type: "SW",
                sessionId: receivedSessionId
              },
              NotifyLoader.setJqueryListeners
            );
          }
          break;
        }
        default:
          this.handleInvalidQuery();
          reject();
          break;
      }
    });

  onUserInfoReceived = () => {
    UserInfoActions.getUserStorages();
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUserInfoReceived);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.onSSOLoginFinished);
  };

  onSSOLoginFinished = () => {
    Tracker.sendGAEvent(
      AK_GA.category,
      AK_GA.labels.storageCount,
      _.flatten(_.toArray(UserInfoStore.getStoragesInfo())).length
    );
    UserInfoStore.removeChangeListener(
      STORAGES_UPDATE,
      this.onSSOLoginFinished
    );
    // if (!this.checkBoxExternalFile()) {
    UserInfoStore.handleRedirectConditions(NotifyLoader.getRedirectURL(true));
    // }
  };

  handleInvalidQuery = () => {
    this.setState({
      message: { id: "invalidQuery" }
    });
  };

  handlePasswordReset = () => {
    if (UserInfoStore.getUserInfo("isLoggedIn")) {
      this.setState({
        timer: true,
        message: { id: "passwordSuccessfullyChanged" },
        time: timeout,
        type: null,
        timerFunction: sessionId => {
          const _config = window.ARESKudoConfigObject;
          _config.defaultheaders = JSON.stringify([
            {
              name: "sessionId",
              value: sessionId
            },
            { name: "locale", value: Storage.store("locale") }
          ]);
          window.ARESKudoConfigObject = _config;
          UserInfoActions.getUserInfo();
          let AKURL = ApplicationStore.getApplicationSetting("UIPrefix");
          if (query.redirect) {
            AKURL += decodeURIComponent(query.redirect);
          } else {
            AKURL += "files";
          }
          ApplicationActions.changePage(AKURL);
        },
        timerFunctionArgs: Storage.store("sessionId")
      });
    } else {
      this.setState({
        timer: true,
        message: { id: "passwordUpdateError" },
        time: timeout,
        type: null
      });
    }
  };

  render() {
    const {
      message,
      type,
      timer,
      time,
      sessionId,
      configLoaded,
      showSpinnerOnly,
      isError
    } = this.state;
    const product = ApplicationStore.getApplicationSetting("product");
    let productIconUrl = kudoLogoSmall;
    if (product === "DraftSight") {
      productIconUrl = DSLogoSmall;
    }

    return (
      <Main>
        <Content>
          {configLoaded === false || showSpinnerOnly === true ? (
            <Loader />
          ) : (
            <>
              <Logo src={productIconUrl} alt={product} />

              {message ? (
                <Typography
                  variant="body1"
                  data-component={
                    isError
                      ? "notification_error_message"
                      : "notification_message"
                  }
                >
                  {typeof message === "object" ? (
                    // eslint-disable-next-line react/jsx-props-no-spreading
                    <FormattedMessage {...message} />
                  ) : (
                    message.toString()
                  )}
                </Typography>
              ) : null}
              {timer ? (
                <Typography variant="body1">
                  <FormattedMessage
                    id="redirectToKudo"
                    values={{
                      time: time / 1000,
                      product: ApplicationStore.getApplicationSetting("product")
                    }}
                  />
                </Typography>
              ) : null}
              {type === "RESET_PASSWORD" ? (
                <div className="login">
                  <KudoForm
                    id="reset_password_form"
                    onSubmitFunction={formData => {
                      UserInfoActions.resetPassword(
                        query.key,
                        query.hash,
                        formData.password.value,
                        formData.passwordConfirm.value
                      );
                    }}
                  >
                    <Spacer />
                    <KudoInput
                      name="password"
                      type="password"
                      id="signUpForm_password"
                      ref={input => {
                        if (input) {
                          this.passwordField = input;
                        }
                      }}
                      placeHolder="password"
                      formId="reset_password_form"
                      isStrengthCheck
                      isHiddenValue
                      validationFunction={InputValidationFunctions.isPassword}
                      onChange={() => {
                        // update to check passwordConfirm
                        this.forceUpdate();
                      }}
                      inputDataComponent="password-input"
                    />
                    <Spacer />
                    <KudoInput
                      name="passwordConfirm"
                      type="password"
                      id="signUpForm_passwordConfirm"
                      placeHolder="rePass"
                      formId="reset_password_form"
                      isHiddenValue
                      isCheckOnExternalUpdate
                      allowedValues={
                        this.passwordField
                          ? [
                              this.passwordField.getCurrentValidState()
                                ? this.passwordField.getCurrentValue()
                                : null
                            ]
                          : []
                      }
                      inputDataComponent="confirm-password-input"
                    />
                    <Spacer />
                    <KudoButton
                      isSubmit
                      formId="reset_password_form"
                      styles={{
                        button: {
                          backgroundColor: "#E7D300!important",
                          margin: "0 0 10px !important"
                        },
                        typography: {
                          color: "#000000 !important"
                        }
                      }}
                    >
                      <FormattedMessage id="submit" />
                    </KudoButton>
                  </KudoForm>
                </div>
              ) : null}
              {type === "SW" && Storage.store("EULAAccepted") !== "true" ? (
                <KudoForm
                  id="eula_acceptance"
                  onSubmitFunction={formData => {
                    if (formData.accept.value === true) {
                      Storage.store("EULAAccepted", "true");
                      Storage.unsafeStoreSessionId(sessionId);
                      const UIPrefix =
                        ApplicationStore.getApplicationSetting("UIPrefix");
                      ApplicationActions.changePage(`${UIPrefix}files`);
                    }
                  }}
                  checkFunction={formData => formData.accept.value}
                >
                  <iframe
                    title="Solidworks EULA"
                    src={
                      ApplicationStore.getApplicationSetting("customization")
                        .EULALink
                    }
                    id="EULAframe"
                    style={{
                      display: "block",
                      backgroundColor: "#ffffff",
                      position: "relative",
                      margin: "10px 0"
                    }}
                  />
                  <KudoCheckbox
                    id="accept"
                    name="accept"
                    label="IAcceptEULA"
                    defaultChecked={false}
                    reverse
                    formId="eula_acceptance"
                    styles={{
                      formGroup: {
                        marginLeft: "2px"
                      }
                    }}
                  />
                  <KudoButton
                    isSubmit
                    formId="eula_acceptance"
                    styles={{
                      button: {
                        backgroundColor: "#E7D300!important",
                        margin: "0 0 10px !important"
                      },
                      typography: {
                        color: "#000000 !important"
                      }
                    }}
                  >
                    <FormattedMessage id="submit" />
                  </KudoButton>
                </KudoForm>
              ) : null}
            </>
          )}
        </Content>
      </Main>
    );
  }
}
