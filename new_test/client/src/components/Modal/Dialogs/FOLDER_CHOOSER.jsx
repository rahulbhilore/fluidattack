import React from "react";
import { FormattedMessage } from "react-intl";
import KudoTreeView from "../../Inputs/KudoTreeView/KudoTreeView";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

export default function chooseFolder() {
  const handleSubmit = json => {
    const { tree } = json;
    const { value } = tree;
    ModalActions.hide();

    FilesListActions.loadPath(value, "folder", true)
      .then(path => {
        XenonConnectionActions.postMessage({
          messageName: "chooseFolder",
          id: value,
          fullPathToSend: path
        });
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
      });
  };
  const { storageType, storageId } = FilesListStore.findCurrentStorage();

  return (
    <>
      <DialogBody>
        <KudoForm id="chooseFolder" onSubmitFunction={handleSubmit}>
          <KudoTreeView
            name="tree"
            id="tree"
            label="Folder"
            formId="chooseFolder"
            allowedTypes={["folders"]}
            allFoldersAllowed
            storageType={storageType}
            storageId={storageId}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton formId="chooseFolder" isSubmit>
          <FormattedMessage id="ok" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
