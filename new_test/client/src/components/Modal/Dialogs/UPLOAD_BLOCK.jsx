import React, { useRef, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { supportedApps } from "../../../stores/UserInfoStore";
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
  },
  fileInput: {
    display: "none !important"
  }
}));

export default function uploadBlock({ info }) {
  const inputRef = useRef();
  const [blockFile, setBlockFile] = useState("");
  const handleCreate = formData => {
    let blockName = formData.blockName.value.trim();
    const blockDescription = formData.blockDescription.value.trim();
    if (!blockName) blockName = blockFile.name;
    BlocksActions.uploadBlock(
      blockFile,
      info.libraryId,
      blockName,
      blockDescription
    );
    ModalActions.hide();
  };

  const handleInputClick = () => {
    if (!inputRef || !inputRef.current) return;
    inputRef.current.click();
  };

  const uploadHandler = (event, eventFiles) => {
    const files = eventFiles || event.target.files;
    if (files && files[0]) {
      setBlockFile(files[0]);
    }
  };

  const classes = useStyles();
  const validationFunction =
    InputValidationFunctions.getNameValidationFunction();
  return (
    <>
      <DialogBody>
        <KudoForm id="uploadBlock" onSubmitFunction={handleCreate}>
          <input
            name="file"
            type="file"
            className={classes.fileInput}
            accept={supportedApps.xenon.map(v => `.${v}`).join(",")}
            ref={inputRef}
            onChange={uploadHandler}
          />
          <KudoInput
            type={InputTypes.TEXT}
            name="blockFile"
            readOnly
            id="blockFile"
            label="blockFile"
            placeHolder="blockFile"
            formId="uploadBlock"
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            value={blockFile?.name}
            onClick={handleInputClick}
            isEmptyValueValid={false}
            validationFunction={validationFunction}
            inputDataComponent="blockFileInput"
          />
          <KudoInput
            type={InputTypes.TEXT}
            name="blockName"
            id="blockName"
            label="blockName"
            placeHolder="blockName"
            formId="uploadBlock"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={blockFile?.name}
            isEmptyValueValid
            isDefaultValueValid
            validationFunction={validationFunction}
            inputDataComponent="blockNameInput"
          />
          <KudoInput
            type={InputTypes.TEXT}
            name="blockDescription"
            id="blockDescription"
            label="blockDescription"
            placeHolder="blockDescription"
            formId="uploadBlock"
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            validationFunction={validationFunction}
            isEmptyValueValid
            inputDataComponent="blockDescriptionInput"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="uploadBlock">
          <FormattedMessage id="create" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
