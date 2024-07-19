import React, { useRef, useState } from "react";
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
import { supportedApps } from "../../../stores/UserInfoStore";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  fileInput: {
    display: "none !important"
  }
}));

export default function updateBlock({ info }) {
  const inputRef = useRef();
  const [blockFile, setBlockFile] = useState("");
  const handleCreate = formData => {
    const blockName = formData.blockName.value.trim();
    const blockDescription = formData.blockDescription.value.trim();
    BlocksActions.updateBlock(
      info.blockId,
      info.libraryId,
      blockFile,
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
        <KudoForm id="updateBlock" onSubmitFunction={handleCreate}>
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
            formId="updateBlock"
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={info.fileName}
            value={blockFile?.name}
            onClick={handleInputClick}
            isEmptyValueValid
            validationFunction={validationFunction}
            inputDataComponent="blockFileInput"
          />
          <KudoInput
            type={InputTypes.TEXT}
            name="blockName"
            id="blockName"
            label="blockName"
            placeHolder="blockName"
            formId="updateBlock"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={info.name}
            isEmptyValueValid={false}
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
            formId="updateBlock"
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            defaultValue={info.description}
            validationFunction={validationFunction}
            isEmptyValueValid={false}
            isDefaultValueValid
            inputDataComponent="blockDescriptionInput"
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit isDisabled={false} formId="updateBlock">
          <FormattedMessage id="update" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
