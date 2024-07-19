import React from "react";
import { FormattedMessage } from "react-intl";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

export default function forgotPassword() {
  const handleSubmit = json => {
    Requests.sendGenericRequest(
      "/users/resetrequest",
      RequestsMethods.POST,
      {},
      { email: json.email.value.toLowerCase().trim() },
      ["*"]
    )
      .then(() => {
        SnackbarUtils.alertInfo({ id: "instructionsSentPasswordReset" });
        ModalActions.hide();
      })
      .catch(errData => {
        SnackbarUtils.alertError(errData.text);
      });
  };

  return (
    <>
      <DialogBody>
        <KudoForm id="resetPasswordForm" onSubmitFunction={handleSubmit}>
          <KudoInput
            id="RPF_email"
            formId="resetPasswordForm"
            label="email"
            autoFocus
            type={InputTypes.EMAIL}
            name="email"
            validationFunction={InputValidationFunctions.isEmail}
            inputDataComponent="forgot-password-input"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="resetPasswordForm">
          <FormattedMessage id="send" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
