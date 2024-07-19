import React from "react";
import { FormattedMessage } from "react-intl";
import { makeStyles } from "@material-ui/core";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import UserInfoActions from "../../../actions/UserInfoActions";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

const useStyles = makeStyles(theme => ({
  formGroup: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  label: {
    color: `${theme.palette.OBI} !important`,
    marginBottom: theme.spacing(1)
  }
}));

export default function webdavConnect() {
  const handleSubmit = json => {
    UserInfoActions.connectStorage("webdav", null, {
      url: json.url.value,
      username: json.username.value,
      password: json.password.value
    });
    ModalActions.hide();
  };
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <KudoForm id="webdavForm" onSubmitFunction={handleSubmit}>
          <KudoInput
            name="url"
            label="URL"
            id="url"
            placeHolder="URL"
            formId="webdavForm"
            type={InputTypes.URL}
            validationFunction={InputValidationFunctions.isURL}
            classes={{ label: classes.label, formGroup: classes.formGroup }}
            inputDataComponent="webdaw-url-input"
          />
          <KudoInput
            name="username"
            label="login"
            id="username"
            placeHolder="login"
            formId="webdavForm"
            type={InputTypes.TEXT}
            validationFunction={InputValidationFunctions.isNonEmpty}
            classes={{ label: classes.label, formGroup: classes.formGroup }}
            inputDataComponent="webdaw-username-input"
          />
          <KudoInput
            name="password"
            label="password"
            id="password"
            placeHolder="password"
            formId="webdavForm"
            type={InputTypes.PASSWORD}
            validationFunction={InputValidationFunctions.isPassword}
            classes={{ label: classes.label, formGroup: classes.formGroup }}
            inputDataComponent="webdaw-password-input"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="webdavForm">
          <FormattedMessage id="connect" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
