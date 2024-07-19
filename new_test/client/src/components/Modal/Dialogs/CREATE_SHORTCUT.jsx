import React, { useState } from "react";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import MainFunctions from "../../../libraries/MainFunctions";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import KudoCheckbox from "../../Inputs/KudoCheckbox/KudoCheckbox";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  text: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function createShortcut({ info }) {
  const classes = useStyles();

  const { type, fileId, objectName } = info;

  const [createInCurrentFolder, setCreateInCurrentFolder] = useState(true);

  const canCreateInCurrentParent = !FilesListStore.getCurrentFolder().viewOnly;

  const isCurrentFolderMainRoot = FilesListStore.getCurrentFolder().isFilesRoot;

  const [isLoading, setLoading] = useState(false);

  const handleSubmit = formData => {
    if (!isLoading) {
      setLoading(true);
      const promise = FilesListActions.createShortcut(
        fileId,
        type,
        formData.objectName.value,
        isCurrentFolderMainRoot ||
          (canCreateInCurrentParent && createInCurrentFolder)
      );

      if (
        isCurrentFolderMainRoot &&
        canCreateInCurrentParent &&
        createInCurrentFolder
      ) {
        setLoading(false);
        ModalActions.hide();
      } else {
        promise.finally(() => {
          setLoading(false);
          ModalActions.hide();
        });
      }
    }
  };

  const usedNames = FilesListStore.getTreeData(
    FilesListStore.getCurrentFolder()._id
  ).map(({ name }) => name);

  return (
    <>
      <DialogBody>
        <KudoForm
          id="createShortcut"
          checkOnMount
          onSubmitFunction={handleSubmit}
        >
          <Typography className={classes.text}>
            <FormattedMessage
              id={
                canCreateInCurrentParent
                  ? "createShortcutExplanation"
                  : "createShortcutInRootFolderWarning"
              }
              values={{
                strong: IntlTagValues.strong,
                folderName: "My Drive"
              }}
            />
          </Typography>
          <KudoInput
            disabled={isLoading}
            type={InputTypes.TEXT}
            name="objectName"
            id="objectName"
            label="name"
            placeHolder="name"
            formId="createShortcut"
            defaultValue={objectName}
            autofocus
            classes={{
              formGroup: classes.input
            }}
            isDefaultValueValid
            isEmptyValueValid={false}
            restrictedValues={usedNames}
            restrictedValuesCaseInsensitive
            validationFunction={InputValidationFunctions.getNameValidationFunction(
              MainFunctions.storageCodeToServiceName(
                FilesListStore.findCurrentStorage().storageType
              )
            )}
            inputDataComponent="createShortcutNameInput"
          />
          {canCreateInCurrentParent && (
            <KudoCheckbox
              disabled={isLoading || isCurrentFolderMainRoot}
              label="createShortcutInCurrentParent"
              id="inCurrentParent"
              checked={isCurrentFolderMainRoot || createInCurrentFolder}
              onChange={() => setCreateInCurrentFolder(prev => !prev)}
              reverse
              name="createShortcutInCurrentParent"
            />
          )}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          isLoading={isLoading}
          loadingLabelId="creating"
          isSubmit
          formId="createShortcut"
        >
          <FormattedMessage id="create" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
