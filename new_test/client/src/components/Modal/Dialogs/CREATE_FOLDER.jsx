import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function createFolder() {
  const [usedNames, setUsedNames] = useState([]);
  const activeStorage = MainFunctions.storageCodeToServiceName(
    FilesListStore.findCurrentStorage().storageType
  );

  useEffect(() => {
    const folder = FilesListStore.getCurrentFolder();
    const files = FilesListStore.getTreeData(folder._id);
    setUsedNames(
      files.filter(({ type }) => type === "folder").map(({ name }) => name)
    );
  }, []);

  const handleCreate = formData => {
    const folderName = formData.folderName.value;

    FilesListActions.createFolder(
      FilesListStore.getCurrentFolder()._id,
      folderName
    ).catch(err => {
      ModalActions.createFolder();
      SnackbarUtils.alertError(err.text);
    });

    ModalActions.hide();
  };

  const validationFunction =
    InputValidationFunctions.getNameValidationFunction(activeStorage);

  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <KudoForm id="createFolder" onSubmitFunction={handleCreate}>
          <KudoInput
            type={InputTypes.TEXT}
            name="folderName"
            id="folderName"
            label="name"
            placeHolder="name"
            formId="createFolder"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            isEmptyValueValid={false}
            restrictedValues={usedNames}
            restrictedValuesCaseInsensitive
            validationFunction={validationFunction}
            inputDataComponent="create-folder-input"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="createFolder">
          <FormattedMessage id="create" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
