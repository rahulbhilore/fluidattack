import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

export default function exportStateUpdated() {
  const handleSubmit = () => {
    XenonConnectionActions.postMessage({
      messageName: "exportStateUpdated",
      exportStateUpdate: this.props.info.exportStateUpdate
    });
    ModalActions.hide();
  };

  return (
    <>
      <DialogBody>
        <KudoForm
          id="exportStateUpdatedNotificationForm"
          onSubmitFunction={handleSubmit}
        >
          <Typography>
            <FormattedMessage id="exportStateUpdate" />{" "}
            <FormattedMessage id="doYouWantToReload" />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isDisabled={false}
          isSubmit
          formId="exportStateUpdatedNotificationForm"
        >
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
