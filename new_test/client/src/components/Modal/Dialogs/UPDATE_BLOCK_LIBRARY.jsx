import React from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import BlocksActions from "../../../actions/BlocksActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function updateBlockLibrary({ info }) {
  const handleUpdate = formData => {
    const blockLibraryName = formData.blockLibraryName.value.trim();
    const blockLibraryDescription =
      formData.blockLibraryDescription.value.trim();
    BlocksActions.updateBlockLibrary(
      info.libraryId,
      blockLibraryName,
      blockLibraryDescription
    );
    ModalActions.hide();
  };

  const classes = useStyles();
  const validationFunction =
    InputValidationFunctions.getNameValidationFunction();
  return (
    <>
      <DialogBody>
        <KudoForm id="updateBlockLibrary" onSubmitFunction={handleUpdate}>
          <KudoInput
            type={InputTypes.TEXT}
            name="blockLibraryName"
            id="blockLibraryName"
            label="blockLibraryName"
            placeHolder="blockLibraryName"
            formId="updateBlockLibrary"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={info.name}
            isEmptyValueValid={false}
            isDefaultValueValid
            validationFunction={validationFunction}
            inputDataComponent="blockLibraryNameInput"
          />
          <KudoInput
            type={InputTypes.TEXT}
            name="blockLibraryDescription"
            id="blockLibraryDescription"
            label="blockLibraryDescription"
            placeHolder="blockLibraryDescription"
            formId="updateBlockLibrary"
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={info.description}
            validationFunction={validationFunction}
            isEmptyValueValid
            isDefaultValueValid
            inputDataComponent="blockLibraryDescriptionInput"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="updateBlockLibrary">
          <FormattedMessage id="update" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
