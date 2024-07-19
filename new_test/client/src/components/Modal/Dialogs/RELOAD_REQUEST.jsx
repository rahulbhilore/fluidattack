import React, { useEffect } from "react";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

export default function reloadRequest({ info }) {
  const handleSubmit = () => {
    if (info.onAccept) {
      info.onAccept();
    } else {
      location.reload();
    }
  };
  useEffect(
    () => () => {
      if (info.onAccept) {
        info.onAccept();
      } else {
        location.reload();
      }
    },
    []
  );

  return (
    <>
      <DialogBody>
        <KudoForm
          id="updateUIForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
          checkFunction={() => true}
        >
          <Typography variant="body1">
            <FormattedMessage id="updateToNewVersion" />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isDisabled={false} isSubmit formId="updateUIForm">
          <FormattedMessage id="Ok" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
