import React, { useEffect } from "react";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import { Grid, Portal, Typography, styled } from "@mui/material";
import LoginForm from "./LoginForm";
import SignUpForm from "./SignUpForm";
import Footer from "../../Footer/Footer";
import Loader from "../../Loader";
import ToolbarSpacer from "../../ToolbarSpacer";
import ApplicationActions from "../../../actions/ApplicationActions";
import ApplicationStore, {
  CONFIG_LOADED
} from "../../../stores/ApplicationStore";
import Storage from "../../../utils/Storage";
import MainFunctions from "../../../libraries/MainFunctions";
import UserInfoActions from "../../../actions/UserInfoActions";
import background from "../../../assets/images/bg.jpg";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import Tracker from "../../../utils/Tracker";

const FormsConstants = {
  LOGIN: "login",
  SIGN_UP: "signUp"
};

const Main = styled("main")({
  flexGrow: 1,
  backgroundImage: `url(${background})`,
  backgroundSize: "cover",
  display: "flex",
  alignItems: "start",
  justifyContent: "center"
});

const Caption = styled(Typography)(({ theme }) => ({
  textAlign: "center",
  margin: theme.spacing(3, 0),
  fontSize: "1rem"
}));

const GridItem = styled(Grid)({
  textAlign: "center"
});

/**
 * @class
 * @classdesc Index page manager
 */
export default function IndexPage() {
  let formTextRepresentation = FormsConstants.LOGIN;
  if (Storage.store("error") === "notLoggedInOrSessionExpired") {
    formTextRepresentation = FormsConstants.LOGIN;
  } else if (location.pathname.includes("signup")) {
    formTextRepresentation = FormsConstants.SIGN_UP;
  }
  const [formType, setFormType] = React.useState(formTextRepresentation);
  const [isLoaded, setLoaded] = React.useState(false);
  const [googleCallbackCalled, setGoogleCallbackCalled] = React.useState(false);

  const onConfigLoaded = () => {
    const { independentLogin } =
      ApplicationStore.getApplicationSetting("featuresEnabled");
    const redirectURL = (MainFunctions.QueryString("redirect") || "") as string;
    const bredirectURL = (MainFunctions.QueryString("bredirect") ||
      "") as string;
    let finalRedirect = bredirectURL;
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    if (!bredirectURL || !bredirectURL.length) {
      finalRedirect = btoa(redirectURL);
    }
    if (independentLogin) {
      setLoaded(true);
    } else if (formTextRepresentation === FormsConstants.LOGIN) {
      if (MainFunctions.QueryString("state")) {
        location.href = `${
          location.origin
        }${UIPrefix}notify?mode=account&type=sso&state=${MainFunctions.QueryString(
          "state"
        )}${finalRedirect.length > 0 ? `&bredirect=${finalRedirect}` : ""}`;
      }

      // verify that there isn't actually session
      else if ((Storage.getItem("sessionId") || "").length > 0) {
        ApplicationActions.changePage(`${UIPrefix}files`, "IP_sessionChecked");
      } else {
        UserInfoActions.loginWithSSO(finalRedirect, true);
      }
    } else {
      const customerPortalURL =
        ApplicationStore.getApplicationSetting("customerPortalURL");
      Tracker.trackOutboundLink(
        "Signup",
        "CreateAccount",
        "Source:KudoInApp",
        `${customerPortalURL}/account/create?source=${btoa(
          `${location.origin}/${atob(finalRedirect)}`
        )}`,
        false,
        true
      );
    }
  };

  const handleGoogleSignInCallback = (authCode: string) => {
    setGoogleCallbackCalled(true);
    Requests.sendGenericRequest(
      "/users/foreign",
      RequestsMethods.POST,
      { googleTemporaryCode: authCode },
      undefined,
      ["*"]
    )
      .then(answer => {
        setLoaded(true);
        if (answer.sessionId) {
          // backward compatibility
          const _config = window.ARESKudoConfigObject;
          // @ts-ignore
          _config.defaultheaders = JSON.stringify([
            {
              name: "sessionId",
              value: answer.sessionId
            }
          ]);
          window.ARESKudoConfigObject = _config;

          Storage.unsafeStoreSessionId(answer.sessionId);

          // get user info
          Requests.sendGenericRequest(
            "/users",
            RequestsMethods.GET,
            Requests.getDefaultUserHeaders(),
            undefined,
            ["*"]
          ).then(userInfoResponse => {
            const userInfo = userInfoResponse.data.results[0];
            userInfo.isAdmin =
              (userInfo.roles ? userInfo.roles[0] === "1" : userInfo.isAdmin) ||
              false;
            // start
            Requests.sendGenericRequest(`/integration/accounts`, "GET")
              .then(accountsResponse => {
                userInfo.linkedStorages = _.chain(accountsResponse)
                  .pick(_.isArray)
                  .pick(
                    (value, key) =>
                      !!_.findWhere(
                        // @ts-ignore
                        window.ARESKudoConfigObject.storage_features,
                        userInfo.isAdmin
                          ? { name: key }
                          : {
                              name: key,
                              adminOnly: false
                            }
                      )
                  )
                  .value();
                if (!_.flatten(_.toArray(userInfo.linkedStorages)).length) {
                  ApplicationActions.changePage(`/storages`);
                } else {
                  ApplicationActions.changePage(`/files`);
                }
              })
              .catch(err => {
                SnackbarUtils.alertError(err);
              });
            // end
          });
        } else if (answer.userId) {
          SnackbarUtils.alertWarning({ id: "waitForConfirm" });
          setFormType(FormsConstants.LOGIN);
        }
      })
      .catch(err => {
        setLoaded(true);
        if (err.code === 423) {
          SnackbarUtils.alertWarning({ id: "waitForConfirm" });
        } else if (err.code === 403) {
          SnackbarUtils.alertError({ id: "accountDisabled" });
        } else if (err.code === 401) {
          SnackbarUtils.alertError({ id: "wrongCredentials" });
        } else {
          SnackbarUtils.alertError(err.text);
        }
      });
  };

  useEffect(() => {
    document.title = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )}`;
    const topBarDOMElement = document.getElementById("topbar");
    if (topBarDOMElement) {
      topBarDOMElement.style.display = "none";
    }
    if (
      Storage.store("error") === "notLoggedInOrSessionExpired" &&
      Storage.store("noStoreError") !== "true"
    ) {
      SnackbarUtils.alertInfo({ id: "notLoggedInOrSessionExpired" });
      Storage.deleteValue("error");
    }
    if (Storage.store("noStoreError")) {
      Storage.deleteValue("noStoreError");
    }
    if (MainFunctions.QueryString("code") && googleCallbackCalled === false) {
      if (MainFunctions.QueryString("code") !== "undefined") {
        handleGoogleSignInCallback(
          (MainFunctions.QueryString("code") || "") as string
        );
      } else {
        SnackbarUtils.alertError({
          id: `gdrive_${MainFunctions.QueryString("error")}`
        });
      }
    } else if (Storage.store("sessionId")) {
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      UserInfoActions.checkSession()
        .then(() => {
          // valid session
          ApplicationActions.changePage(
            `${UIPrefix}files`,
            "IP_sessionChecked"
          );
        })
        .catch(ApplicationStore.sessionExpiredHandler);
    }
    ApplicationStore.addChangeListener(CONFIG_LOADED, onConfigLoaded);
    return () => {
      ApplicationStore.removeChangeListener(CONFIG_LOADED, onConfigLoaded);
    };
  }, []);

  useEffect(() => {
    setFormType(
      location.pathname.includes("signup")
        ? FormsConstants.SIGN_UP
        : FormsConstants.LOGIN
    );
  }, [formTextRepresentation]);

  return (
    <Main>
      {isLoaded ? (
        <Grid container justifyContent="center">
          <ToolbarSpacer />
          <GridItem item xs={12} md={6} lg={3} xl={2}>
            {ApplicationStore.getApplicationSetting("customization").logoURL
              .login ? (
              <div className="loginCaptionContainer">
                <img
                  alt="Logo"
                  src={
                    ApplicationStore.getApplicationSetting("customization")
                      .logoURL.login
                  }
                />
              </div>
            ) : (
              <Caption variant="h5">
                {formType === FormsConstants.LOGIN ? (
                  <FormattedMessage
                    id="signInToKudo"
                    values={{
                      product:
                        ApplicationStore.getApplicationSetting("product"),
                      strong: IntlTagValues.strong
                    }}
                  />
                ) : (
                  <FormattedMessage
                    id="signUpForTrial"
                    values={{
                      strong: IntlTagValues.strong
                    }}
                  />
                )}
              </Caption>
            )}

            {formType === FormsConstants.SIGN_UP ? (
              <SignUpForm />
            ) : (
              <LoginForm />
            )}
          </GridItem>
        </Grid>
      ) : (
        <Portal>
          <Loader isOverUI />
        </Portal>
      )}
      <Footer isIndexPage />
    </Main>
  );
}
