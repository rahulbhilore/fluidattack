import React, { useEffect, useState } from "react";
import { Link } from "react-router";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import { styled, Typography, Button, Portal } from "@mui/material";
import UserInfoActions from "../../../actions/UserInfoActions";
import ApplicationActions from "../../../actions/ApplicationActions";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import UserInfoStore, {
  LOGIN,
  INFO_UPDATE,
  STORAGES_UPDATE
} from "../../../stores/UserInfoStore";
import ApplicationStore from "../../../stores/ApplicationStore";
import MainFunctions from "../../../libraries/MainFunctions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Storage from "../../../utils/Storage";
import ModalActions from "../../../actions/ModalActions";

import FilesListStore from "../../../stores/FilesListStore";
import Tracker, { AK_GA } from "../../../utils/Tracker";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import Loader from "../../Loader";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import { normalizeLocaleAndLang } from "../../../utils/languages";

const Spacer = styled("div")(({ theme }) => ({
  height: theme.spacing(2),
  width: "100%"
}));

const ForgotPasswordButton = styled("button")(({ theme }) => ({
  display: "block",
  margin: theme.spacing(3, "auto"),
  background: "none",
  outline: "none",
  border: "none",
  textDecoration: "underline",
  color: theme.palette.LIGHT,
  cursor: "pointer",
  textAlign: "center"
}));

const SignUpLink = styled(Typography)(({ theme }) => ({
  margin: theme.spacing(2, "auto"),
  textAlign: "center",
  fontSize: ".8rem",
  fontWeight: "bold"
}));

export default function LoginForm() {
  const handleRedirectConditions = () => {
    const storagesInfo = UserInfoStore.getStoragesInfo();
    const areStoragesConnected = _.flatten(_.toArray(storagesInfo)).length;
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    const isTrial = UserInfoStore.getUserInfo("licenseType") === "TRIAL";
    const isTrialPageShown = UserInfoStore.getUserInfo("isTrialShown");
    const product = ApplicationStore.getApplicationSetting("product");
    if (product !== "DraftSight") {
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.labels.storageCount,
        areStoragesConnected ? "true" : "false"
      );
    }
    const hasTrialEnded = UserInfoStore.hasTrialEnded();
    if (
      isTrial === true &&
      isTrialPageShown === false &&
      hasTrialEnded === false
    ) {
      if (
        ((MainFunctions.QueryString("redirect") || "") as string).length >= 4
      ) {
        ApplicationActions.changePage(
          `${UIPrefix}trial?redirect=${MainFunctions.QueryString("redirect")}`
        );
      } else {
        ApplicationActions.changePage(`${UIPrefix}trial`);
      }
    } else if (!areStoragesConnected) {
      ApplicationActions.changePage(`${UIPrefix}storages`);
    } else if (
      ((MainFunctions.QueryString("bredirect") || "") as string).length >= 4
    ) {
      ApplicationActions.changePage(
        `${UIPrefix}${atob(MainFunctions.QueryString("bredirect") as string)}`
      );
    } else if (
      ((MainFunctions.QueryString("redirect") || "") as string).length >= 4
    ) {
      ApplicationActions.changePage(
        `${UIPrefix}${MainFunctions.QueryString("redirect")}`
      );
    } else {
      ApplicationActions.changePage(`${UIPrefix}files`);
    }
  };

  const clearStoredValues = () => {
    Storage.deleteValue("name");
    Storage.deleteValue("surname");
  };

  const [loaded, setLoaded] = useState(true);
  const [retriesCount, setRetriesCount] = useState(0);
  const [loginData, setLoginData] = useState("");

  const retryLogin = (errorMessage: string) => {
    if (loginData.length > 0 && retriesCount < 1) {
      setRetriesCount(prev => prev + 1);
      setLoaded(false);
      const decoded = decodeURIComponent(atob(loginData)).split(":");
      const email = decoded[0];
      const password = decoded.slice(1).join(":");
      UserInfoActions.login(email, password);
    } else {
      setLoaded(true);
      clearStoredValues();
      SnackbarUtils.alertError(errorMessage);
    }
  };

  const loginUser = (formData: {
    email: { value: string };
    password: { value: string };
  }) => {
    const email = formData.email.value.toLowerCase();
    const password = formData.password.value;
    const newLoginData = btoa(encodeURIComponent(`${email}:${password}`));
    setLoaded(false);
    setLoginData(newLoginData);
    setRetriesCount(0);
    UserInfoActions.login(email, password);
  };

  const handleLoginServerResponse = () => {
    const { data: answer, code } =
      UserInfoStore.getLastRequest() as unknown as {
        data: Record<string, string>;
        // DK: no idea why eslint says that there is an error
        // eslint-disable-next-line no-use-before-define
        code: number;
      };
    if (answer.locale) {
      // set proper locale
      const { locale, language } = normalizeLocaleAndLang(answer.locale);
      ApplicationActions.changeLanguage(language, locale);
    }
    if (
      Object.prototype.hasOwnProperty.call(answer, "nameId") &&
      Object.prototype.hasOwnProperty.call(answer, "sessionIndex")
    ) {
      Storage.deleteValue("sessionId");
      location.href = `${ApplicationStore.getApplicationSetting(
        "oauthURL"
      )}?type=solidworks&mode=logout&nameId=${answer.nameId}&sessionIndex=${
        answer.sessionIndex
      }&url=${encodeURIComponent(
        location.origin + ApplicationStore.getApplicationSetting("UIPrefix")
      )}`;
    } else if (answer.status !== "ok") {
      setLoaded(true);
      Tracker.sendGAEvent(
        AK_GA.category,
        AK_GA.actions.login,
        AK_GA.labels.authenticationAnswer,
        `incorrect status: ${code}`
      );
      clearStoredValues();
      if (code === 423) {
        SnackbarUtils.alertWarning({ id: "waitForConfirm" });
      } else if (code === 403) {
        SnackbarUtils.alertError({ id: "accountDisabled" });
      } else if (code === 401) {
        SnackbarUtils.alertError({ id: "wrongCredentials" });
      } else {
        retryLogin(answer.message);
      }
    } else {
      clearStoredValues();
      if (answer.sessionId) {
        Tracker.sendGAEvent(
          AK_GA.category,
          AK_GA.actions.login,
          AK_GA.labels.authenticationAnswer,
          "sessionId received"
        );
        const config = window.ARESKudoConfigObject;
        // @ts-ignore
        config.defaultheaders = JSON.stringify([
          {
            name: "sessionId",
            value: answer.sessionId
          }
        ]);
        window.ARESKudoConfigObject = config;
        FilesListStore.resetStore();
        Storage.unsafeStoreSessionId(answer.sessionId);
        UserInfoActions.getUserInfo();
      } else if (answer.userId) {
        Tracker.sendGAEvent(
          AK_GA.category,
          AK_GA.actions.login,
          AK_GA.labels.authenticationAnswer,
          "userId received - wait for confirm"
        );
        SnackbarUtils.alertWarning({ id: "waitForConfirm" });
      } else {
        retryLogin(answer.message);
      }
    }
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(LOGIN, handleLoginServerResponse);
    UserInfoStore.addChangeListener(
      INFO_UPDATE,
      UserInfoActions.getUserStorages
    );
    UserInfoStore.addChangeListener(STORAGES_UPDATE, handleRedirectConditions);
    if (MainFunctions.QueryString("activated") === "true") {
      SnackbarUtils.alertOk({ id: "accountConfirmedCanLogin" });
    }
    return () => {
      UserInfoStore.removeChangeListener(LOGIN, handleLoginServerResponse);
      UserInfoStore.removeChangeListener(
        INFO_UPDATE,
        UserInfoActions.getUserStorages
      );
      UserInfoStore.removeChangeListener(
        STORAGES_UPDATE,
        handleRedirectConditions
      );
    };
  }, []);

  const customization = ApplicationStore.getApplicationSetting("customization");
  if (!loaded) {
    return (
      <Portal>
        <Loader isOverUI />
      </Portal>
    );
  }
  return (
    <>
      {customization.showLoginFields ? (
        <KudoForm id="login_form" onSubmitFunction={loginUser}>
          <KudoInput
            name="email"
            type={InputTypes.EMAIL}
            id="email"
            formId="login_form"
            placeHolder="email"
            validationFunction={InputValidationFunctions.isEmail}
            inputDataComponent="email-input"
          />
          <Spacer />
          <KudoInput
            name="password"
            type="password"
            id={InputTypes.PASSWORD}
            formId="login_form"
            placeHolder="password"
            isHiddenValue
            validationFunction={InputValidationFunctions.isPassword}
            inputDataComponent="password-input"
          />
          <Spacer />
          <KudoButton
            id="loginButton"
            formId="login_form"
            isSubmit
            styles={{
              button: {
                backgroundColor: "#E7D300!important",
                border: "solid 2px #E7D300",
                width: "100%",
                borderRadius: 0
              },
              typography: {
                color: "#000000 !important",
                fontWeight: "bold",
                fontSize: "12px"
              }
            }}
          >
            <FormattedMessage id="signIn" />
          </KudoButton>
        </KudoForm>
      ) : null}

      {customization.showResetPasswordButton ? (
        <ForgotPasswordButton
          type="button"
          onClick={ModalActions.forgotPassword}
        >
          <FormattedMessage id="forgotPassword" />
        </ForgotPasswordButton>
      ) : null}
      <div>
        {customization.showCreateAccountButton ? (
          <SignUpLink variant="body1">
            <FormattedMessage id="dontHaveGraebertAccount" />{" "}
            <Link
              to={`${ApplicationStore.getApplicationSetting("UIPrefix")}signup`}
            >
              <FormattedMessage id="signUp" />
            </Link>
          </SignUpLink>
        ) : null}
        {customization.showLearnMore ? (
          <Button
            className="kudoButton emptyInside loginButton"
            onClick={() => {
              Tracker.trackOutboundLink(
                "ARESKudo",
                "redirect",
                "LearnMore",
                ApplicationStore.getApplicationSetting("customization")
                  .learnMoreURL,
                true,
                true
              );
            }}
          >
            <FormattedMessage id="learnMore" />
          </Button>
        ) : null}
      </div>
    </>
  );
}
