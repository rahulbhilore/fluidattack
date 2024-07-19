import React, { useState } from "react";
import _ from "underscore";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import Loader from "../../Loader";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListActions from "../../../actions/FilesListActions";
import ProcessActions from "../../../actions/ProcessActions";
import Processes from "../../../constants/appConstants/Processes";
import FilesListStore from "../../../stores/FilesListStore";
import ModalActions from "../../../actions/ModalActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

function RenameEntity({ info }) {
  const { entityId, name, type } = info;

  const [value, setValue] = useState(
    type === "file"
      ? name.substr(
          0,
          name.lastIndexOf(".") + 1 ? name.lastIndexOf(".") : name.length
        )
      : name
  );

  const [isLoading, setLoading] = useState(false);

  const handleSubmit = formData => {
    let newName = formData.rename.value.trim();
    const extension = MainFunctions.getExtensionFromName(name) || "";

    if (!newName.length) {
      // empty name cannot be set for any entity
      SnackbarUtils.alertError({ id: "emptyName" });
      return false;
    }

    if (type === "file" && extension.length > 0) {
      // if entity had extension that we have to keep - append it to the new file name
      newName += `.${extension}`;
    }

    setLoading(true);

    FilesListActions.modifyEntity(entityId, { name: newName });
    ProcessActions.start(entityId, Processes.RENAME, entityId);
    FilesListActions.updateName(entityId, type, newName)
      .then(data => {
        if (data.name && data.name !== newName) {
          SnackbarUtils.alertWarning({ id: "duplicateNameAutoRename" });
          FilesListActions.modifyEntity(entityId, { name: data.name });
        }

        if (type === "file") {
          const recentFiles = FilesListStore.getRecentFiles();
          const foundFile = _.find(
            recentFiles,
            file => file.fileId === entityId
          );
          if (foundFile) {
            FilesListActions.loadRecentFiles();
          }
        }
        ModalActions.hide();
      })
      .catch(err => {
        SnackbarUtils.alertError(err.message);
        FilesListActions.modifyEntity(entityId, { name });
        setValue(name);
        setLoading(false);
      })
      .finally(() => {
        ProcessActions.end(entityId);
      });
    return null;
  };

  const validator = validatedValue => {
    if (validatedValue === name) return false;

    return true;
  };

  return (
    <>
      <DialogBody>
        {!isLoading ? (
          <KudoForm
            id="renameEntitiesForm"
            onSubmitFunction={handleSubmit}
            checkOnMount
          >
            <KudoInput
              id="rename"
              formId="renameEntitiesForm"
              label="rename"
              autoFocus
              type={InputTypes.TEXT}
              defaultValue={value}
              name="rename"
              validationFunction={validator}
              inputDataComponent="rename-modal-input"
            />
          </KudoForm>
        ) : (
          <Loader isModal />
        )}
      </DialogBody>
      {!isLoading ? (
        <DialogFooter>
          <KudoButton isSubmit formId="renameEntitiesForm">
            <FormattedMessage id="rename" />
          </KudoButton>
        </DialogFooter>
      ) : null}
    </>
  );
}

RenameEntity.propTypes = {
  info: PropTypes.objectOf({
    entityId: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    type: PropTypes.string.isRequired
  }).isRequired
};

export default RenameEntity;
