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
import clsx from "clsx";
import Requests from "../../utils/Requests";
import * as RequestsMethods from "../../constants/appConstants/RequestsMethods";
import MainFunctions from "../../libraries/MainFunctions";
import TableStore from "../../stores/TableStore";
import FilesListActions from "../../actions/FilesListActions";
import FilesListStore from "../../stores/FilesListStore";
import TableActions from "../../actions/TableActions";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  root: {
    verticalAlign: "middle",
    width: "100%"
  },
  inputField: {
    backgroundColor: "rgba(0,0,0,.1)",
    border: "solid 1px rgba(0,0,0,.1)",
    height: "36px",
    borderRadius: "2px 4px",
    boxShadow: "inset 0 1px 1px rgb(0 0 0 / 8%)",
    borderRight: 0,
    "& > input": {
      color: theme.palette.JANGO,
      fontSize: theme.typography.pxToRem(12),
      padding: theme.spacing(0, 1)
    },
    "&:before,&:after": {
      display: "none"
    },
    "&:hover,&:focus,&:active,&:focus-within": {
      borderColor: theme.palette.OBI,
      "& > .extension": {
        borderLeft: `solid 1px ${theme.palette.OBI}`
      }
    }
  },
  addonGroup: {
    border: "1px solid #ccc",
    borderRadius: "0 4px 4px 0",
    backgroundColor: "#eeeeee",
    borderLeft: `solid 1px rgba(0,0,0,.1)`,
    maxHeight: "none",
    height: "36px",
    padding: theme.spacing(1, 2),
    "& > p": {
      color: theme.palette.JANGO,
      fontSize: theme.typography.pxToRem(14)
    }
  }
}));

export default function TableEditField({
  id,
  fieldName,
  value: originalValue,
  extensionEdit,
  type
}) {
  const input = useRef(null);
  const [value, setValue] = useState(
    extensionEdit
      ? originalValue.substr(
          0,
          originalValue.lastIndexOf(".") + 1
            ? originalValue.lastIndexOf(".")
            : originalValue.length
        )
      : originalValue
  );
  const [isSubmitted, setSubmitted] = useState(false);

  useEffect(() => {
    if (input) {
      const { current } = input;
      const strLength = current.value.length;
      if (current.setSelectionRange !== undefined) {
        current.setSelectionRange(0, strLength);
      }
    }
  }, [input]);

  const onBlur = () => {
    const extension = MainFunctions.getExtensionFromName(originalValue) || "";
    if (isSubmitted === true) {
      return true;
    }
    setSubmitted(true);

    // inline means that it is performed in header, so no table is selected
    TableActions.editField(TableStore.getFocusedTable(), id, fieldName, false);
    if (
      _.find(
        TableStore.getTable(TableStore.getFocusedTable()).fields,
        (field, tableFieldName) =>
          field.edit === true && tableFieldName === fieldName
      )
    ) {
      let fieldNewValue = value.trim();

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
          return false;
        }

        if (
          TableStore.isInList(
            TableStore.getFocusedTable(),
            fieldNewValue,
            originalValue,
            type
          )
        ) {
          // if we are in the table - check for existing already name.
          SnackbarUtils.alertError({ id: "usedName" });
          return false;
        }

        // seems like we have check on server-side,
        // so it shouldn't be a problem not to check this.
        // TODO: has to be checked ^

        // modify entity only if we have a table obviously
        TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
          name: fieldNewValue
        });

        switch (TableStore.getTable(TableStore.getFocusedTable()).type) {
          case "templates": {
            let pageType;
            const headers = Requests.getDefaultUserHeaders();
            if (MainFunctions.detectPageType() === "publictemplates") {
              pageType = "/admin/templates/";
              headers.templateType = "PUBLIC";
            } else {
              pageType = "/templates/";
              headers.templateType = "USER";
            }
            TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
              name: fieldNewValue
            });
            Requests.sendGenericRequest(
              `${pageType}${id}`,
              RequestsMethods.PUT,
              headers,
              { name: fieldNewValue }
            ).catch(err => {
              SnackbarUtils.alertError(err.text);
              TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
                name: originalValue
              });
            });
            break;
          }
          case "files":
          case "file":
          case "folder":
          default:
            Requests.sendGenericRequest(
              `/${type}s/${id}`,
              RequestsMethods.PUT,
              Requests.getDefaultUserHeaders(),
              {
                [type === "folder" ? "folderName" : "fileName"]: fieldNewValue
              },
              ["*"]
            )
              .then(response => {
                const { data } = response;
                // update recent files if necessary
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

                if (data._id) {
                  TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
                    id: data._id,
                    _id: data._id
                  });
                  // is update required?..
                } else {
                  FilesListActions.updateEntityInfo(
                    TableStore.getFocusedTable(),
                    id,
                    `${type}s`
                  );
                }
              })
              .catch(err => {
                SnackbarUtils.alertError(err.text);
                TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
                  name: originalValue
                });
              });
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
            TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
              comment: fieldNewValue
            });
          })
          .catch(err => {
            SnackbarUtils.alertError(err.text);
            TableActions.modifyEntity(TableStore.getFocusedTable(), id, {
              comment: originalValue
            });
          });
      }
    }
    return true;
  };

  const onKey = action => {
    switch (action) {
      case "SAVE":
        onBlur();
        break;
      case "CLOSE":
        TableActions.editField(
          TableStore.getFocusedTable(),
          id,
          fieldName,
          false
        );
        break;
      default:
        break;
    }
  };

  const changeEditField = e => {
    setValue(e.target.value);
  };

  const classes = useStyles();

  const extension = MainFunctions.getExtensionFromName(originalValue) || "";
  return (
    <Shortcuts name="EDIT_FIELD" handler={onKey} global>
      <FormControl className={classes.root}>
        <Input
          data-component="renameObjectField"
          inputRef={input}
          type="text"
          value={value}
          autoFocus
          className={classes.inputField}
          onChange={changeEditField}
          onBlur={onBlur}
          endAdornment={
            extensionEdit && extension.length > 0 ? (
              <InputAdornment
                position="end"
                className={clsx(classes.addonGroup, "extension")}
              >{`.${extension}`}</InputAdornment>
            ) : null
          }
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
