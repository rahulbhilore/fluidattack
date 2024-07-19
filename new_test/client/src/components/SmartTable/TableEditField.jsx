import React, { useEffect, useRef, useState } from "react";
import { Shortcuts } from "react-shortcuts";
import PropTypes from "prop-types";
import _ from "underscore";
import {
  FormControl,
  Input,
  InputAdornment,
  makeStyles
} from "@material-ui/core";
import Requests from "../../utils/Requests";
import * as RequestsMethods from "../../constants/appConstants/RequestsMethods";
import Processes from "../../constants/appConstants/Processes";
import MainFunctions from "../../libraries/MainFunctions";
import FilesListActions from "../../actions/FilesListActions";
import FilesListStore from "../../stores/FilesListStore";
import TemplatesActions from "../../actions/TemplatesActions";
import ProcessActions from "../../actions/ProcessActions";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import { ResourceTypes } from "../Pages/ResourcesNewPage/ResourcesContent";
import templatesStore from "../../stores/resources/templates/TempatesStore";
import publicTemplatesStore from "../../stores/resources/templates/PublicTemplatesStore";

import {
  PUBLIC_TEMPLATES,
  CUSTOM_TEMPLATES
} from "../../constants/TemplatesConstants";

const useStyles = makeStyles(theme => ({
  root: {
    verticalAlign: "middle",
    width: `calc(100% - ${theme.spacing(1)}px)`
  },
  inputField: {
    backgroundColor: "rgba(0,0,0,.1)",
    border: "solid 1px rgba(0,0,0,.1)",
    height: "36px",
    borderRadius: "2px",
    "& > input": {
      color: theme.palette.JANGO,
      fontSize: theme.typography.pxToRem(12),
      padding: theme.spacing(0, 1)
    },
    "&:before,&:after": {
      display: "none"
    }
  },
  addonGroup: {
    borderRadius: 0,
    backgroundColor: theme.palette.REY,
    borderLeft: `solid 1px ${theme.palette.DARK}`,
    maxHeight: "none",
    height: "36px",
    padding: theme.spacing(1, 2),
    "& > p": {
      color: theme.palette.JANGO
    }
  }
}));

let savedInClosureValue = null;

export default function TableEditField({
  id,
  fieldName,
  value: originalValue,
  extensionEdit,
  type
}) {
  const input = useRef(null);
  const field = useRef(null);

  const getInitialValue = () => {
    if (savedInClosureValue) return savedInClosureValue;

    return extensionEdit
      ? originalValue.substr(
          0,
          originalValue.lastIndexOf(".") + 1
            ? originalValue.lastIndexOf(".")
            : originalValue.length
        )
      : originalValue;
  };

  const [value, _setValue] = useState(getInitialValue());
  const valueRef = useRef(value);
  const setValue = data => {
    valueRef.current = data;
    _setValue(data);
  };

  const [isSubmitted, setSubmitted] = useState(false);
  // DK: https://graebert.atlassian.net/browse/XENON-53946
  // For some reason we don't have focus
  const [isFocused, setFocused] = useState(false);

  useEffect(() => {
    if (input) {
      const { current } = input;
      const strLength = current.value.length;
      if (current.setSelectionRange !== undefined) {
        current.setSelectionRange(0, strLength);
      }
      current.focus();
      setFocused(true);
    }
  }, [input]);

  const executeSave = () => {
    // if (!isFocused) return false;
    const extension = MainFunctions.getExtensionFromName(originalValue) || "";
    if (isSubmitted === true) {
      return true;
    }
    setSubmitted(true);

    let fieldNewValue = valueRef.current.trim();

    if (fieldName === "name") {
      if (!fieldNewValue.length) {
        // empty name cannot be set for any entity
        SnackbarUtils.alertError({ id: "emptyName" });
        return false;
      }

      if (extensionEdit && extension.length > 0) {
        // if entity had extension that we have to keep - append it to the new file name
        fieldNewValue += `.${extension}`;
      }

      if (fieldNewValue === originalValue) {
        // if entity name is the same it was before - no need to change anything
        FilesListActions.setEntityRenameMode(id, false);
        savedInClosureValue = null;
        return false;
      }

      FilesListActions.setEntityRenameMode(id, false);
      savedInClosureValue = null;

      switch (type) {
        case "files":
        case "file":
        case "folder":
          FilesListActions.modifyEntity(id, { name: fieldNewValue });
          ProcessActions.start(id, Processes.RENAME, id);
          FilesListActions.updateName(id, type, fieldNewValue)
            .then(data => {
              // Entity was renamed by externalStorage e.g. duplicated name in DB
              if (data.name && data.name !== fieldNewValue) {
                SnackbarUtils.alertWarning({ id: "duplicateNameAutoRename" });
                FilesListActions.modifyEntity(id, { name: data.name });
              }

              if (type === "file") {
                const recentFiles = FilesListStore.getRecentFiles();
                const foundFile = _.find(
                  recentFiles,
                  file => file.fileId === id
                );
                if (foundFile) {
                  FilesListActions.loadRecentFiles();
                }
              }
              // TODO: I.A i think that update there not required
              // FilesListActions.updateEntityInfo(
              //   id,
              //   `${type}s`
              // );
            })
            .catch(err => {
              SnackbarUtils.alertError(err.text || err.message);
              FilesListActions.modifyEntity(id, { name: originalValue });
            })
            .finally(() => {
              ProcessActions.end(id);
            });
          break;
        case "template": {
          let activeTemplatesStorage = null;
          if (MainFunctions.detectPageType().endsWith("templates/public")) {
            activeTemplatesStorage = publicTemplatesStore;
          } else {
            activeTemplatesStorage = templatesStore;
          }

          activeTemplatesStorage.rename(id, fieldNewValue).catch(e => {
            SnackbarUtils.alertError(e);
          });
          break;
        }
        case "oldTemplate": {
          let templateType;
          if (MainFunctions.detectPageType().endsWith("templates/public")) {
            templateType = PUBLIC_TEMPLATES;
          } else {
            templateType = CUSTOM_TEMPLATES;
          }

          TemplatesActions.modifyTemplate(id, templateType, {
            name: fieldNewValue
          });

          TemplatesActions.renameTemplate(
            templateType,
            id,
            fieldNewValue
          ).catch(err => {
            TemplatesActions.modifyTemplate(id, templateType, {
              name: originalValue
            });
            SnackbarUtils.alertError(err.text);
          });
          break;
        }
        default:
          break;
      }
    } else if (fieldName === "comment") {
      Requests.sendGenericRequest(
        `/admin/templates/${id}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        { comment: fieldNewValue }
      )
        .then(() => {
          FilesListActions.modifyEntity(id, {
            comment: fieldNewValue
          });
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text);
          FilesListActions.modifyEntity(id, {
            comment: originalValue
          });
        });
    }

    return true;
  };

  const onBlur = () => {
    FilesListActions.setEntityRenameMode(id, false);
    savedInClosureValue = null;
  };

  const handleBlur = e => {
    if (
      e.target &&
      field?.current &&
      e.target !== field.current &&
      input?.current &&
      e.target !== input.current &&
      !e.target.id?.includes("context") &&
      !e.target.parentElement?.id?.includes("context")
    ) {
      executeSave();
    }
  };

  useEffect(() => {
    document.addEventListener("mousedown", handleBlur);
    return () => {
      document.removeEventListener("mousedown", handleBlur);
    };
  }, []);

  const onKey = action => {
    switch (action) {
      case "SAVE":
        executeSave();
        break;
      case "CLOSE":
        onBlur();
        break;
      default:
        break;
    }
  };

  const changeEditField = e => {
    setValue(e.target.value);
    savedInClosureValue = e.target.value;
  };

  const classes = useStyles();

  const extension = MainFunctions.getExtensionFromName(originalValue) || "";
  return (
    <Shortcuts
      name="EDIT_FIELD"
      handler={onKey}
      global
      className="shortcuts_wrapper"
    >
      <FormControl className={classes.root}>
        <Input
          ref={field}
          inputRef={input}
          type="text"
          value={value}
          autoFocus
          className={classes.inputField}
          onChange={changeEditField}
          endAdornment={
            extensionEdit && extension.length > 0 ? (
              <InputAdornment
                position="end"
                className={classes.addonGroup}
              >{`.${extension}`}</InputAdornment>
            ) : null
          }
          inputProps={{ maxLength: 250 }}
          data-component="rename-input-table"
        />
      </FormControl>
    </Shortcuts>
  );
}

TableEditField.propTypes = {
  id: PropTypes.string.isRequired,
  fieldName: PropTypes.string.isRequired,
  value: PropTypes.string,
  extensionEdit: PropTypes.bool,
  type: PropTypes.string
};

TableEditField.defaultProps = {
  value: "",
  type: "file",
  extensionEdit: false
};
