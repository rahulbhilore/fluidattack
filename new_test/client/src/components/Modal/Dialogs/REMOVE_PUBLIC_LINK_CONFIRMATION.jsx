import React, { useEffect } from "react";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import FilesListActions from "../../../actions/FilesListActions";

export default function removePublicLink({ info }) {
  const { fileId, name } = info;

  useEffect(
    () => () => {
      ModalActions.shareManagement(fileId, name, "file");
    },
    []
  );

  const handleSubmit = () => {
    FilesListActions.modifyEntity(fileId, {
      public: false,
      link: ""
    });
    Requests.sendGenericRequest(
      `/files/${fileId}/link`,
      RequestsMethods.DELETE,
      Requests.getDefaultUserHeaders()
    ).then(() => {
      ModalActions.shareManagement(fileId, name, "file");
    });
  };
  return (
    <>
      <DialogBody>
        <KudoForm
          id="removePublicLinkForm"
          onSubmitFunction={handleSubmit}
          checkOnMount
          checkFunction={() => true}
        >
          <Typography variant="body1" data-component="removeSharingLinkAlert">
            <FormattedMessage id="sharingLinkAlert" />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isDisabled={false} isSubmit formId="removePublicLinkForm">
          <FormattedMessage id="OK" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
