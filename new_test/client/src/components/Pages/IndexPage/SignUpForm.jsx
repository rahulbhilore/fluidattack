import React, { Component, useEffect, useRef, useState } from "react";
import { Portal, styled } from "@mui/material";
import { injectIntl, FormattedMessage } from "react-intl";
import { Link } from "react-router";
import UserInfoActions from "../../../actions/UserInfoActions";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import UserInfoStore, { SIGNUP } from "../../../stores/UserInfoStore";
import FormManagerStore from "../../../stores/FormManagerStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Loader from "../../Loader";
import ApplicationStore from "../../../stores/ApplicationStore";
import * as InputTypes from "../../../constants/appConstants/InputTypes";

const Spacer = styled("div")(({ theme }) => ({
  height: theme.spacing(1),
  width: "100%"
}));

const renderLink = msg => <Link to="/">{msg}</Link>;

export default function SignUpForm() {
  const [isLoaded, setIsLoaded] = useState(true);
  const [isEmailOccupied, setIsEmailOccupied] = useState(false);
  const passwordField = useRef(null);

  /**
   * Check if email is unique(XENON-28773) Can be depricated due to XENON-31658
   * @see https://graebert.atlassian.net/browse/XENON-31658
   * @see https://graebert.atlassian.net/browse/XENON-28773
   * @deprecated
   */
  const checkIfEmailExists = (ev, email, isValid) => {
    const isIndependentLogin =
      ApplicationStore.getApplicationSetting(
        "featuresEnabled"
      ).independentLogin;
    if (isIndependentLogin === false && isValid === true) {
      Requests.sendGenericRequest(
        `/users/portal/exists?email=${email}`,
        RequestsMethods.GET,
        undefined,
        undefined,
        ["*"]
      ).then(response => {
        // code 200 - email is occupied
        setIsEmailOccupied(!(response.data.isAvailable || false));
      });
    }
  };

  const getHelpMessage = () => {
    const isIndependentLogin =
      ApplicationStore.getApplicationSetting(
        "featuresEnabled"
      ).independentLogin;
    if (isIndependentLogin === false && isEmailOccupied === true) {
      return {
        id: "accountAlreadyExistsPleaseLogin",
        values: {
          login: renderLink
        }
      };
    }
    return null;
  };

  const onSignUpComplete = () => {
    setIsLoaded(true);
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(SIGNUP, onSignUpComplete);
    return () => {
      UserInfoStore.removeChangeListener(SIGNUP, onSignUpComplete);
    };
  }, []);

  return (
    <div>
      {!isLoaded ? (
        <Portal isOpen>
          <Loader />
        </Portal>
      ) : null}
      {!isLoaded ? null : (
        <KudoForm
          id="signup_form"
          onSubmitFunction={formData => {
            setIsLoaded(false);
            UserInfoActions.signUp(
              formData.email.value.toLowerCase(),
              formData.password.value
            );
          }}
        >
          <KudoInput
            label="email"
            name="email"
            type={InputTypes.EMAIL}
            id="email"
            formId="signup_form"
            placeHolder="email"
            isCheckOnExternalUpdate
            restrictedValues={
              isEmailOccupied === true
                ? [
                    FormManagerStore.getElementData("signup_form", "email")
                      .value
                  ]
                : []
            }
            onBlur={checkIfEmailExists}
            validationFunction={InputValidationFunctions.isEmail}
            helpMessage={getHelpMessage}
            showHelpBlock={isEmailOccupied}
            inputDataComponent="email-input"
          />
          <Spacer />
          <KudoInput
            label="password"
            name="password"
            type={InputTypes.PASSWORD}
            id="password"
            formId="signup_form"
            ref={passwordField}
            placeHolder="password"
            isStrengthCheck
            isHiddenValue
            validationFunction={InputValidationFunctions.isPassword}
            inputDataComponent="password-input"
          />
          <Spacer />
          <KudoInput
            label="rePass"
            name="passwordConfirm"
            type={InputTypes.PASSWORD}
            id="passwordConfirm"
            formId="signup_form"
            placeHolder="rePass"
            isHiddenValue
            isCheckOnExternalUpdate
            allowedValues={
              passwordField.current
                ? [
                    passwordField.current.getCurrentValidState()
                      ? passwordField.current.getCurrentValue()
                      : null
                  ]
                : []
            }
            inputDataComponent="rePassword-input"
          />
          <KudoButton
            isSubmit
            formId="signup_form"
            styles={{
              button: {
                backgroundColor: "#E7D300!important",
                border: "solid 2px #E7D300",
                width: "100%",
                borderRadius: 0,
                margin: "16px 0"
              },
              typography: {
                color: "#000000 !important",
                fontWeight: "bold",
                fontSize: "12px"
              }
            }}
          >
            <FormattedMessage id="createAccount" />
          </KudoButton>
        </KudoForm>
      )}
      {!isLoaded ? null : (
        <span>
          <div className="switchToLogin">
            <FormattedMessage id="alreadyHaveAccount" />{" "}
            <Link to={ApplicationStore.getApplicationSetting("UIPrefix")}>
              <FormattedMessage id="login" />
            </Link>
          </div>
        </span>
      )}
    </div>
  );
}
