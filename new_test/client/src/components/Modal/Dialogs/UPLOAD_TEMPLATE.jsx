import React, { useEffect, useState } from "react";
import { makeStyles } from "@mui/styles";
import _ from "underscore";
import { FormattedMessage, useIntl } from "react-intl";
import { InputAdornment, TextField } from "@mui/material";

import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import formManagerStore from "../../../stores/FormManagerStore";
import FormManagerActions from "../../../actions/FormManagerActions";
import templatesStore, {
  TemplatesStore
} from "../../../stores/resources/templates/TempatesStore";
import OldTemplatesStore from "../../../stores/TemplatesStore";
import TemplatesActions from "../../../actions/TemplatesActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import applicationStore from "../../../stores/ApplicationStore";

const useStyles = makeStyles(theme => ({
  label: {
    color: theme.palette.OBI,
    marginBottom: theme.spacing(1),
    display: "block"
  },
  textField: {
    "& input": {
      color: theme.palette.DARK,
      height: `${36 - theme.spacing(2)}px`,
      padding: theme.spacing(1, 1),
      fontSize: theme.typography.pxToRem(14),
      border: `solid 1px ${theme.palette.REY}`,
      "&:hover,&:focus,&:active": {
        borderColor: theme.palette.OBI,
        color: theme.palette.OBI
      },
      "&[disabled]": {
        backgroundColor: theme.palette.REY,
        pointerEvents: "none"
      }
    },
    "& > div": {
      padding: 0
    },
    "& fieldset": {
      border: "none !important"
    },
    width: "100%",
    borderRadius: 0,
    outline: "none !important"
  },
  input: {
    borderRadius: 0,
    height: "36px",
    zIndex: 0,
    cursor: "pointer",
    backgroundColor: `${theme.palette.LIGHT} !important`
  },
  inputAddon: {
    padding: 0,
    backgroundColor: "transparent",
    border: "none",
    height: "36px",
    verticalAlign: "bottom"
  },
  inputAddonButton: {
    textTransform: "uppercase",
    padding: `${theme.spacing(1)}px ${theme.spacing(2)}px !important`,
    borderRadius: 0,
    height: "36px",
    backgroundColor: `${theme.palette.OBI} !important`,
    borderColor: theme.palette.OBI,
    boxShadow: "none",
    fontSize: "1rem",
    fontWeight: "bold",
    textShadow: "none"
  }
}));

const formId = "uploadTemplateForm";
const inputId = "fileInput";

export default function uploadTemplate({ info }) {
  const [uploadObject, setUploadObject] = useState({ body: null, name: "" });

  const uploadSimulation = e => {
    e.preventDefault();
    e.currentTarget.blur();
    document.getElementById(inputId).click();
  };

  const intl = useIntl();

  const handleSubmitOld = () => {
    const duplicatesMessage = intl.formatMessage({
      id: "duplicateNameUpload"
    });
    const templateName = uploadObject.name;
    const duplicateFiles = _.where(
      OldTemplatesStore.getTemplates(info.type).toJS(),
      { name: templateName }
    );
    if (duplicateFiles.length) {
      SnackbarUtils.alertError(
        `${
          duplicatesMessage + _.pluck(duplicateFiles, "name").join("\r\n")
        }\r\n`
      );
      setUploadObject({ body: null, name: "" });
    } else {
      ModalActions.hide();
      TemplatesActions.uploadTemplate(info.type, uploadObject.body)
        .then(() => {
          SnackbarUtils.alertOk({
            id: "successfulUploadSingle",
            type: intl.formatMessage({ id: "template" })
          });
        })
        .catch(err => {
          SnackbarUtils.alertError({
            id: "uploadingFileError",
            name: templateName,
            error: err.message
          });
        });
    }
  };

  const handleSubmitNew = () => {
    const duplicatesMessage = intl.formatMessage({
      id: "duplicateNameUpload"
    });
    const templateName = uploadObject.name;
    const activeTemplatesStorage = TemplatesStore.activeStorage;

    ModalActions.hide();
    const promises = activeTemplatesStorage.loadTemplates(uploadObject.body);

    Promise.all(promises)
      .then(() => {
        SnackbarUtils.alertOk({
          id: "successfulUploadSingle",
          type: "template"
        });
      })
      .catch(err => {
        if (err.type === "FILE_NAME_DUPLICATED") {
          SnackbarUtils.alertWarning({
            id: "duplicateNameUpload",
            duplicates: err.value
          });
          return;
        }

        SnackbarUtils.alertError({
          id: "uploadingFileError",
          name: templateName,
          error: err.message
        });
      });
  };

  const handleSubmit = () => {
    const { pathname } = location;
    const { templates, pTemplates } = applicationStore.getOldResourcesUsage();

    if (pathname.includes("templates") && pathname.includes("public")) {
      if (pTemplates) return handleSubmitOld();
      return handleSubmitNew();
    }

    if (pathname.includes("templates") && pathname.includes("my")) {
      if (templates) return handleSubmitOld();
      return handleSubmitNew();
    }

    return () => null;
  };

  useEffect(() => {
    formManagerStore.registerFormElement(
      formId,
      "INPUT",
      inputId,
      inputId,
      uploadObject.name,
      false
    );
  }, []);

  const emitChangeEvent = newName => {
    if (
      formId.length > 0 &&
      formManagerStore.checkIfElementIsRegistered(formId, inputId)
    ) {
      FormManagerActions.changeInputValue(
        formId,
        inputId,
        newName,
        newName.length > 0,
        false
      );
    }
  };

  const changeCaption = ev => {
    const fileName = ev.target.value;
    const file = ev.target.files;
    const name = fileName.substr(fileName.lastIndexOf("\\") + 1);
    setUploadObject({
      body: file[0],
      name
    });
    emitChangeEvent(name);
  };

  const classes = useStyles();

  return (
    <>
      <DialogBody>
        <KudoForm id={formId} onSubmitFunction={handleSubmit}>
          <TextField
            className={classes.textField}
            variant="outlined"
            aria-readonly
            value={uploadObject.name}
            onClick={uploadSimulation}
            InputProps={{
              readOnly: true,
              endAdornment: (
                <InputAdornment position="end">
                  <KudoButton
                    isDisabled={false}
                    className={classes.inputAddonButton}
                  >
                    <FormattedMessage id="choose" />
                  </KudoButton>
                </InputAdornment>
              )
            }}
          />
          <input
            id={inputId}
            style={{ display: "none" }}
            type="file"
            name="template"
            onChange={changeCaption}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId={formId}>
          <FormattedMessage id="uploadObject" values={{ type: "" }} />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
