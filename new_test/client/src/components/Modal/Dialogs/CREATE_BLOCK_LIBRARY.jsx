import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import BlocksActions from "../../../actions/BlocksActions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoSwitch from "../../Inputs/KudoSwitch/KudoSwitch";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import userInfoStore from "../../../stores/UserInfoStore";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import blocksStore from "../../../stores/BlocksStore";

const USE_DESCRIPTION = false;

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function createBlockLibrary() {
  const handleCreate = formData => {
    const blockLibraryName = formData.blockLibraryName.value;
    const blockLibraryDescription = !USE_DESCRIPTION
      ? ""
      : formData.blockLibraryDescription.value.trim();

    BlocksActions.createBlockLibrary(
      blockLibraryName,
      blockLibraryDescription,
      formData.isOrgLibrary?.value
        ? userInfoStore.getUserInfo("company")?.id
        : userInfoStore.getUserInfo("id"),
      formData.isOrgLibrary?.value ? "ORG" : "USER"
    );
    ModalActions.hide();
  };

  const [usedNames, setUsedNames] = useState([]);

  useEffect(() => {
    const existingBlocks = blocksStore.getBlocks();
    setUsedNames(existingBlocks.map(({ name }) => name));
  }, []);

  const classes = useStyles();
  const validationFunction =
    InputValidationFunctions.getNameValidationFunction();
  const isCompanyAdmin = userInfoStore.getUserInfo("company")?.isAdmin;
  return (
    <>
      <DialogBody>
        <KudoForm id="createBlockLibrary" onSubmitFunction={handleCreate}>
          <KudoInput
            type={InputTypes.TEXT}
            name="blockLibraryName"
            id="blockLibraryName"
            label="blockLibraryName"
            placeHolder="blockLibraryName"
            formId="createBlockLibrary"
            autoFocus
            classes={{
              formGroup: classes.input,
              label: classes.label
            }}
            isEmptyValueValid={false}
            restrictedValues={usedNames}
            restrictedValuesCaseInsensitive
            validationFunction={validationFunction}
            inputDataComponent="blockLibraryNameInput"
          />
          {USE_DESCRIPTION && (
            <KudoInput
              type={InputTypes.TEXT}
              name="blockLibraryDescription"
              id="blockLibraryDescription"
              label="blockLibraryDescription"
              placeHolder="blockLibraryDescription"
              formId="createBlockLibrary"
              classes={{
                formGroup: classes.input,
                label: classes.label
              }}
              validationFunction={validationFunction}
              isEmptyValueValid
              inputDataComponent="blockLibraryDescriptionInput"
            />
          )}
          {isCompanyAdmin ? (
            <KudoSwitch
              name="isOrgLibrary"
              id="isOrgLibrary"
              label="isOrgLibrary"
              dataComponent="is-org-library-switch"
              formId="createBlockLibrary"
              styles={{
                formGroup: {
                  margin: 0
                },
                label: {
                  width: "100%",
                  margin: "0 !important",
                  "& .MuiTypography-root": {
                    fontSize: 12
                  }
                },
                switch: {
                  width: "58px",
                  height: "32px",
                  margin: "0 !important",
                  "& .MuiSwitch-thumb": {
                    width: 20,
                    height: 20
                  },
                  "& .Mui-checked": {
                    transform: "translateX(23px)"
                  },
                  "& .MuiSwitch-switchBase": {
                    padding: 1,
                    color: "#FFFFFF",
                    top: "5px",
                    left: "6px"
                  }
                }
              }}
            />
          ) : null}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="createBlockLibrary">
          <FormattedMessage id="create" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
