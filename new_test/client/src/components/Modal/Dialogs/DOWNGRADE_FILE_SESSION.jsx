import { Typography } from "@material-ui/core";
import React from "react";
import { FormattedMessage } from "react-intl";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

export default function DowngradeDrawingSession() {
  return (
    <>
      <DialogBody>
        <Typography variant="body1">
          <FormattedMessage id="drawingSessionDowngraded" />
        </Typography>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isSubmit={false}
          isDisabled={false}
          dataComponent="viewButton"
          onClick={ModalActions.hide}
        >
          <FormattedMessage id="view" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
