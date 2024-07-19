/**
 * Created by khizh on 8/25/2016.
 */
import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import ModalActions from "../../../actions/ModalActions";
import FilesListStore from "../../../stores/FilesListStore";
import FilesListActions from "../../../actions/FilesListActions";

export default function drawingUpdated() {
  const handleSubmit = () => {
    FilesListActions.setViewingLatestVersion(true);

    if (FilesListStore.getCurrentFile().viewFlag) {
      XenonConnectionActions.postMessage({ messageName: "reopen" });
    }
    ModalActions.hide();
  };

  return (
    <>
      <DialogBody>
        <KudoForm
          id="drawingUpdatedNotificationForm"
          onSubmitFunction={handleSubmit}
        >
          <Typography variant="body1">
            <FormattedMessage id="drawingHasChanged" />{" "}
            <FormattedMessage id="doYouWantToReload" />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isDisabled={false}
          isSubmit
          formId="drawingUpdatedNotificationForm"
        >
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
