import React, { useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Box } from "@material-ui/core";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import KudoSwitch from "../../Inputs/KudoSwitch/KudoSwitch";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import { supportedApps } from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";

const FILE_EXTENSIONS = supportedApps.xenon;
const dot = ".";
const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  cloneToRootFolderNote: {
    fontSize: "10px",
    fontWeight: "bold",
    color: "#124daf",
    marginTop: "15px"
  }
}));

export default function cloneObject({ info }) {
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const { objectName } = info;
  let fileExtension = ".dwg";
  if (info.type === "file") {
    fileExtension = objectName.substr(objectName.lastIndexOf(dot));
  }
  const folder = FilesListStore.getCurrentFolder();
  let folderId = folder._id;
  let fromSharedFiles = false;
  let fromViewOnlyFolder = false;
  if (folderId.endsWith("shared")) {
    fromSharedFiles = true;
  } else if (folder.viewOnly === true) {
    fromViewOnlyFolder = true;
  }
  if (fromSharedFiles || fromViewOnlyFolder) {
    folderId = `${folderId.substr(0, folderId.lastIndexOf("+") + 1)}root`;
  }

  const files = FilesListStore.getTreeData(folderId);
  const usedNames =
    info.type === "file"
      ? files
          .filter(
            ({ type, name }) =>
              type === "file" && name.length > fileExtension.length
          )
          .map(({ name }) => name.substr(0, name.lastIndexOf(dot)))
      : files.filter(({ type }) => type === "folder").map(({ name }) => name);

  const { fileId, type, isShortcut } = info;
  const isCopyCommentsAllowed =
    type === "file" &&
    !isShortcut &&
    FILE_EXTENSIONS.some(ext => objectName.endsWith(ext));

  let objectNameWithoutExtension;
  if (type === "file") {
    objectNameWithoutExtension = objectName.substr(
      0,
      objectName.lastIndexOf(dot)
    );
  } else {
    objectNameWithoutExtension = objectName;
  }
  const handleCopyCommentsSwitch = () => {
    setIncludeDisabled(!includeDisabled);
  };
  const handleSubmit = formData => {
    let doCopyComments = false;
    let doIncludeResolved = false;
    let doIncludeDeleted = false;
    if (isCopyCommentsAllowed) {
      doCopyComments = formData.doCopyCommentsAndMarkups.value;
      if (doCopyComments) {
        doIncludeResolved = formData.doIncludeResolvedCommentsAndMarkups.value;
        doIncludeDeleted = doIncludeResolved;
      }
    }
    const doCopyShare = formData.doCopyShare.value;
    let finalObjectName = formData.objectName.value;
    if (
      type === "file" &&
      fileExtension !== "." &&
      !finalObjectName.endsWith(fileExtension)
    ) {
      finalObjectName += fileExtension;
    }
    FilesListActions.cloneObject(
      fileId,
      type,
      finalObjectName,
      doCopyShare,
      doCopyComments,
      doIncludeResolved,
      doIncludeDeleted
    );
    ModalActions.hide();
  };
  const activeStorage = MainFunctions.storageCodeToServiceName(
    FilesListStore.findCurrentStorage().storageType
  );
  const validationFunction =
    InputValidationFunctions.getNameValidationFunction(activeStorage);
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <KudoForm id="cloneObject" checkOnMount onSubmitFunction={handleSubmit}>
          <KudoInput
            type={InputTypes.TEXT}
            name="objectName"
            id="objectName"
            label="name"
            placeHolder="name"
            formId="cloneObject"
            defaultValue={objectNameWithoutExtension}
            autofocus
            classes={{
              formGroup: classes.input
            }}
            isDefaultValueValid
            isEmptyValueValid={false}
            restrictedValues={usedNames}
            restrictedValuesCaseInsensitive
            validationFunction={validationFunction}
            inputDataComponent="cloneObjectNameInput"
          />
          <div>
            <KudoSwitch
              name="doCopyShare"
              id="doCopyShare"
              label="shareItWithSamePeople"
              dataComponent="copy-share-switch"
              formId="cloneObject"
              styles={{
                formGroup: {
                  margin: 0
                },
                label: {
                  width: "100%",
                  margin: "0 !important",
                  "& .MuiTypography-root": {
                    fontSize: "12px"
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
          </div>
          {isCopyCommentsAllowed ? (
            <div>
              <KudoSwitch
                name="doCopyCommentsAndMarkups"
                id="doCopyCommentsAndMarkups"
                label="copyCommentsAndMarkups"
                dataComponent="copy-comments-switch"
                formId="cloneObject"
                onChange={handleCopyCommentsSwitch}
                styles={{
                  formGroup: {
                    margin: 0
                  },
                  label: {
                    width: "100%",
                    margin: "0 !important",
                    "& .MuiTypography-root": {
                      fontSize: "12px"
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
              <KudoSwitch
                name="doIncludeResolvedCommentsAndMarkups"
                id="doIncludeResolvedCommentsAndMarkups"
                label="includeResolvedCommentsAndMarkups"
                dataComponent="copy-comments-include-resolved-switch"
                disabled={!includeDisabled}
                formId="cloneObject"
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
            </div>
          ) : null}
          {fromSharedFiles ? (
            <Box className={classes.cloneToRootFolderNote}>
              <FormattedMessage id="cloneFromSharedFiles" />
            </Box>
          ) : null}
          {fromViewOnlyFolder ? (
            <Box className={classes.cloneToRootFolderNote}>
              <FormattedMessage id="cloneFromViewOnlyFolder" />
            </Box>
          ) : null}
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="cloneObject">
          <FormattedMessage id="clone" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
