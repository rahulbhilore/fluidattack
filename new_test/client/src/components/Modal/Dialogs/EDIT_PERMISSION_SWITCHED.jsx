import { Typography } from "@material-ui/core";
import React from "react";
import { FormattedMessage } from "react-intl";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

export default function editPermissionSwitched({ info }) {
  return (
    <>
      <DialogBody>
        <Typography variant="body1">
          <FormattedMessage id="editPermissionSwitched" />
          {info.wasFileModified ? (
            <>
              <br />
              <FormattedMessage id="changesSavedToConflictingFile" />
            </>
          ) : null}
        </Typography>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isSubmit={false}
          isDisabled={false}
          onClick={ModalActions.hide}
        >
          <FormattedMessage id="ok" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
