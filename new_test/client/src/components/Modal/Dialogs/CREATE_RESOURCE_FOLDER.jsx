import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import userInfoStore from "../../../stores/UserInfoStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";

const USE_DESCRIPTION = false;

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function createResourceFolder({ info }) {
  const classes = useStyles();
  const [usedNames, setUsedNames] = useState([]);
  const { storage, ownerType } = info;

  const handleCreate = formData => {
    const folderName = formData.resourceFolderName.value;
    const folderDescription = !USE_DESCRIPTION
      ? ""
      : formData.resourceFolderName.value.trim();

    storage.createFolder(folderName, folderDescription, ownerType);
    ModalActions.hide();
  };

  const validationFunction =
    InputValidationFunctions.getNameValidationFunction();

  const isCompanyAdmin = userInfoStore.getUserInfo("company")?.isAdmin;

  return (
    <>
      <DialogBody>
        <KudoForm id="createResourceFolder" onSubmitFunction={handleCreate}>
          <KudoInput
            type={InputTypes.TEXT}
            name="resourceFolderName"
            id="resourceFolderName"
            label="name"
            placeHolder="name"
            formId="createResourceFolder"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            isEmptyValueValid={false}
            restrictedValues={usedNames}
            restrictedValuesCaseInsensitive
            validationFunction={validationFunction}
            inputDataComponent="resourceFolderNameInput"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="createResourceFolder">
          <FormattedMessage id="create" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
