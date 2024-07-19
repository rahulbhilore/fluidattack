import React, { useEffect, useRef, useState } from "react";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Typography } from "@material-ui/core";
import FilesListStore from "../../../stores/FilesListStore";
import TemplatesStore, {
  CUSTOM_TEMPLATES_LOADED,
  TEMPLATES_LOADED
} from "../../../stores/TemplatesStore";
import TemplatesActions from "../../../actions/TemplatesActions";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoSelect from "../../Inputs/KudoSelect/KudoSelect";
import Loader from "../../Loader";
import MainFunctions from "../../../libraries/MainFunctions";
import {
  CUSTOM_TEMPLATES,
  PUBLIC_TEMPLATES
} from "../../../constants/TemplatesConstants";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import ModalActions from "../../../actions/ModalActions";
import FilesListActions from "../../../actions/FilesListActions";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const TEMPLATES_TYPES = [
  { type: CUSTOM_TEMPLATES, event: CUSTOM_TEMPLATES_LOADED },
  { type: PUBLIC_TEMPLATES, event: TEMPLATES_LOADED }
];

const FILE_EXTENSION = ".dwg";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  }
}));

export default function createFileDialog() {
  const [templates, setTemplates] = useState({});
  const [isLoading, setLoading] = useState(true);
  const [usedNames, setUsedNames] = useState([]);
  const [loadedTypes, _setTypeLoaded] = useState(0);

  const loadedTypesRef = useRef(loadedTypes);
  const setTypeLoaded = data => {
    loadedTypesRef.current = data;
    _setTypeLoaded(data);
  };

  const onTemplatesLoaded = () => {
    setTypeLoaded(loadedTypesRef.current + 1);
  };

  useEffect(() => {
    // if we don't have any not loaded
    if (loadedTypes === TEMPLATES_TYPES.length) {
      const field = "name";
      const sortFunction = (a, b) =>
        (a[field] || "").toString().localeCompare((b[field] || "").toString());
      const publicTemplates = TemplatesStore.getTemplates(PUBLIC_TEMPLATES)
        .toJS()
        .sort(sortFunction)
        .map(item => {
          item.name = item.name.includes("(public)")
            ? item.name
            : `(public) ${item.name}`;
          return item;
        });
      const customTemplates = TemplatesStore.getTemplates(CUSTOM_TEMPLATES)
        .toJS()
        .sort(sortFunction);
      const allTemplates = publicTemplates.concat(customTemplates);
      if (allTemplates.length === 0) {
        SnackbarUtils.alertError({ id: "templatesFirst" });
        setTemplates({});
      } else {
        setTemplates(
          _.object(_.pluck(allTemplates, "_id"), _.pluck(allTemplates, "name"))
        );
      }
      setLoading(false);
    }
  }, [loadedTypes]);

  useEffect(() => {
    const removals = TEMPLATES_TYPES.map(({ type, event }) => {
      TemplatesStore.addChangeListener(event, onTemplatesLoaded);
      TemplatesActions.loadTemplates(type);
      return () => {
        TemplatesStore.removeChangeListener(
          TEMPLATES_LOADED,
          onTemplatesLoaded
        );
      };
    });
    return () => {
      removals.forEach(f => f());
    };
  }, []);

  const activeStorage = MainFunctions.storageCodeToServiceName(
    FilesListStore.findCurrentStorage().storageType
  );
  useEffect(() => {
    if (!activeStorage.includes("onshape")) {
      const folder = FilesListStore.getCurrentFolder();
      const files = FilesListStore.getTreeData(folder._id);
      setUsedNames(
        files
          .filter(
            ({ type, name }) =>
              type === "file" &&
              name.length > FILE_EXTENSION.length &&
              name.endsWith(FILE_EXTENSION)
          )
          .map(({ name }) => name.substr(0, name.lastIndexOf(FILE_EXTENSION)))
      );
    }
  }, []);
  const handleCreate = formData => {
    const data = {
      fileName: formData.fileName.value.toString().trim(),
      templateId: formData.template.value.toString().trim()
    };

    // append extension if not specified - after filter, because it doesn't contain extensions
    if (!data.fileName.toLowerCase().endsWith(FILE_EXTENSION)) {
      data.fileName += FILE_EXTENSION;
    }

    ModalActions.hide();

    FilesListActions.createFileByTemplate(
      FilesListStore.getCurrentFolder()._id,
      data.fileName,
      data.templateId
    )
      .then(response => {
        FilesListActions.toggleView(
          data.fileName,
          response.data.fileId,
          false,
          "viewer"
        );
      })
      .catch(errData => {
        if (errData?.data?.errorId === "KD1") {
          SnackbarUtils.alertError({
            id: "cannotCreateDrawingUsingTemplateKudoDriveIsFull"
          });
        } else {
          SnackbarUtils.alertError(errData.text);
          ModalActions.createFile();
        }
      });
  };
  const validationFunction =
    InputValidationFunctions.getNameValidationFunction(activeStorage);
  const areTemplatesAvailable = Object.keys(templates).length > 0;
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        {isLoading ? <Loader isModal /> : null}
        {!isLoading && areTemplatesAvailable ? (
          <KudoForm
            id="createFileForm"
            onSubmitFunction={handleCreate}
            enforceEnter
          >
            <KudoSelect
              options={templates}
              defaultValue={Object.keys(templates)[0]}
              id="template"
              name="template"
              label="template"
              formId="createFileForm"
              classes={{ formControl: classes.input }}
              styles={{
                label: {
                  marginBottom: "10px",
                  textTransform: "capitalize"
                }
              }}
              dataComponent="create-file-select"
            />
            <KudoInput
              id="fileName"
              type={InputTypes.TEXT}
              name="fileName"
              label="name"
              formId="createFileForm"
              classes={{
                formGroup: classes.input,
                label: classes.label
              }}
              autoFocus
              isEmptyValueValid={false}
              restrictedValues={usedNames}
              restrictedValuesCaseInsensitive
              validationFunction={validationFunction}
              inputDataComponent="create-file-input"
            />
          </KudoForm>
        ) : null}
        {!isLoading && !areTemplatesAvailable ? (
          <Typography>No templates</Typography>
        ) : null}
      </DialogBody>
      <DialogFooter>
        {!isLoading && areTemplatesAvailable ? (
          <KudoButton isSubmit formId="createFileForm">
            <FormattedMessage id="create" />
          </KudoButton>
        ) : null}
      </DialogFooter>
    </>
  );
}
