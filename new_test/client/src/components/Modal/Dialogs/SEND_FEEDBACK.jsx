import React from "react";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import ModalActions from "../../../actions/ModalActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import Requests from "../../../utils/Requests";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

export default function SendFeedback() {
  const handleSubmit = formData => {
    const prettifiedData = {
      subject: formData.feedbackSubject.value.toString().trim(),
      comment: formData.feedbackComment.value.toString().trim()
    };
    if (
      prettifiedData.subject.length > 0 &&
      prettifiedData.comment.length > 0
    ) {
      const requestJSON = {
        subject: prettifiedData.subject,
        comment: prettifiedData.comment
      };

      Requests.sendGenericRequest(
        "/feedback",
        RequestsMethods.POST,
        Requests.getDefaultUserHeaders(),
        requestJSON,
        ["*"]
      )
        .then(() => {
          ModalActions.hide();
          SnackbarUtils.alertInfo({ id: "feedbackSent" });
        })
        .catch(errData => {
          SnackbarUtils.alertError(errData.text);
        });
    } else {
      SnackbarUtils.alertError({ id: "emptyName" });
    }
  };

  return (
    <>
      <DialogBody>
        <Typography>
          <FormattedMessage id="pleaseSendYourFeedback" />
        </Typography>
        <KudoForm id="feedbackForm" onSubmitFunction={handleSubmit}>
          <KudoInput
            name="feedbackSubject"
            type={InputTypes.TEXT}
            id="feedbackSubject"
            formId="feedbackForm"
            placeHolder="subject"
            readOnly={false}
            label="subject"
            validationFunction={InputValidationFunctions.isNonEmpty}
            inputDataComponent="send-feedback-subject-input"
          />
          <KudoInput
            name="feedbackComment"
            type={InputTypes.TEXTAREA}
            id="feedbackComment"
            formId="feedbackForm"
            placeHolder="comment"
            label="comment"
            className="feedbackComment"
            readOnly={false}
            validationFunction={InputValidationFunctions.isNonEmpty}
            inputDataComponent="send-feedback-comment-input"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="feedbackForm">
          <FormattedMessage id="submit" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
