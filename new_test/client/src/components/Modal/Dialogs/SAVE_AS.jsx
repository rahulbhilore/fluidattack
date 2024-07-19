import React, { useCallback, useState } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import { Box } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import KudoSwitch from "../../Inputs/KudoSwitch/KudoSwitch";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import KudoTreeView from "../../Inputs/KudoTreeView/KudoTreeView";
import newRequests from "../../../utils/Requests";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoSelect from "../../Inputs/KudoSelect/KudoSelect";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListStore from "../../../stores/FilesListStore";
import ModalActions from "../../../actions/ModalActions";
import Loader from "../../Loader";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import StoragesSelector from "./SaveAs/StoragesSelector";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  label: {
    color: `${theme.palette.OBI} !important`
  },
  selectBlock: {
    paddingRight: theme.typography.pxToRem(32)
  }
}));

const ALLOW_DIFFERENT_STORAGE = true;

export default function saveAs({ info }) {
  const [isLoading, setLoading] = useState(false);
  const [includeDisabled, setIncludeDisabled] = useState(false);
  const [storageInfo, setStorageInfo] = useState(
    FilesListStore.findCurrentStorage()
  );
  const { storageType, storageId } = storageInfo;

  const [refreshTree, setRefreshTree] = useState(false);

  const { filterArray, saveFilterIndex, commandName, caption } = info;

  const handleCopyCommentsSwitch = useCallback(() => {
    setIncludeDisabled(prev => !prev);
  }, []);

  const resetState = useCallback(() => {
    setIncludeDisabled(false);
    setLoading(false);
  }, []);

  const handleSubmit = json => {
    setLoading(true);
    const fileName = json.fileName.value.trim();
    if (!fileName) {
      SnackbarUtils.alertError({ id: "emptyName" });
      resetState();
    } else {
      const currentFileId = FilesListStore.getCurrentFile()._id;
      const { storageType: storageTypeInner } =
        MainFunctions.parseObjectId(currentFileId);
      const storageName =
        MainFunctions.storageCodeToServiceName(storageTypeInner);
      const validationFunction =
        InputValidationFunctions.getNameValidationFunction(storageName);
      if (!validationFunction(fileName)) {
        SnackbarUtils.alertError({ id: "specialCharactersName" });
        resetState();
      } else {
        const placeToMove = json.tree.value;
        if (placeToMove === null) {
          SnackbarUtils.alertError({ id: "noTargetFolder" });
          resetState();
        } else {
          let doIncludeResolved = false;
          let doIncludeDeleted = false;
          const doCopyComments = json.doCopyCommentsAndMarkups.value;
          if (doCopyComments) {
            doIncludeResolved = json.doIncludeResolvedCommentsAndMarkups.value;
            doIncludeDeleted = doIncludeResolved;
          }
          newRequests
            .sendGenericRequest(
              `/folders/${placeToMove}`,
              RequestsMethods.GET,
              newRequests.getDefaultUserHeaders(),
              undefined,
              ["*"]
            )
            .then(response => {
              const answer = response.data;
              const entitiesInTargetFolder = answer.results;
              const namesOfEntitiesInTargetFolder = {
                files: _.map(
                  _.pluck(entitiesInTargetFolder.files, "filename"),
                  entityName => {
                    const modifiedEntityName = entityName.toLowerCase();
                    if (modifiedEntityName.lastIndexOf(".") > -1) {
                      return modifiedEntityName.substr(
                        0,
                        modifiedEntityName.lastIndexOf(".")
                      );
                    }
                    return modifiedEntityName;
                  }
                )
              };
              if (
                namesOfEntitiesInTargetFolder.files.indexOf(
                  fileName.toLowerCase()
                ) > -1
              ) {
                SnackbarUtils.alertError({ id: "duplicateNameSaveAs" });
                resetState();
              } else {
                XenonConnectionActions.postMessage({
                  messageName: "saveAs",
                  fileName: json.fileName.value.trim(),
                  saveFilterIndex: json.saveAs?.value || saveFilterIndex,
                  path: json.tree.value,
                  cloneFileId: currentFileId,
                  copyComments: doCopyComments,
                  includeResolvedComments: doIncludeResolved,
                  includeDeletedComments: doIncludeDeleted,
                  commandName
                });
                ModalActions.hide();
              }
            })
            .catch(err => {
              SnackbarUtils.alertError(err.text);
            });
        }
      }
    }
  };

  const validationFunction = InputValidationFunctions.getNameValidationFunction(
    MainFunctions.storageCodeToServiceName(storageType)
  );

  const onStorageSwitch = newStorageInfo => {
    setStorageInfo(newStorageInfo);
    setRefreshTree(true);
    setTimeout(() => {
      setRefreshTree(false);
    }, 0);
  };

  const classes = useStyles();

  return (
    <>
      <DialogBody>
        <KudoForm id="saveAs" onSubmitFunction={handleSubmit}>
          {isLoading === true ? (
            <Loader isModal message={<FormattedMessage id="analyzing" />} />
          ) : (
            <>
              <KudoInput
                type={InputTypes.TEXT}
                name="fileName"
                id="fileName"
                label="name"
                placeHolder="name"
                formId="saveAs"
                autoFocus
                classes={{
                  formGroup: classes.input,
                  label: classes.label
                }}
                checkType={["notList"]}
                isEmptyValueValid={false}
                validationFunction={validationFunction}
                inputDataComponent="save-as-input"
              />
              <div>
                <KudoSwitch
                  name="doCopyCommentsAndMarkups"
                  id="doCopyCommentsAndMarkups"
                  label="copyCommentsAndMarkups"
                  dataComponent="copy-comments-switch"
                  formId="saveAs"
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
                  formId="saveAs"
                  styles={{
                    formGroup: {
                      marginTop: 1
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
              {ALLOW_DIFFERENT_STORAGE && (
                <StoragesSelector
                  onSelect={onStorageSwitch}
                  currentStorageInfo={storageInfo}
                />
              )}
              {refreshTree ? null : (
                <KudoTreeView
                  name="tree"
                  id="tree"
                  label="Folder"
                  formId="saveAs"
                  allowedTypes={["folders"]}
                  allFoldersAllowed
                  validationFunction={(value, rowInfo) => {
                    if (!rowInfo) {
                      return true;
                    }

                    return !rowInfo.viewOnly; // we check access (FULL_ACCESS/NO_ACCESS) on view-only
                  }}
                  storageType={storageType}
                  storageId={storageId}
                />
              )}
            </>
          )}
        </KudoForm>
      </DialogBody>
      <Box className={classes.selectBlock}>
        <KudoSelect
          defaultValue={saveFilterIndex}
          options={filterArray}
          id="saveAs"
          name="saveAs"
          label="saveAsType"
          formId="saveAs"
          dataСomponent="save-as-select"
          styles={{
            formControl: {
              marginTop: "4px",
              marginBottom: "16px",
              marginLeft: "16px",
              marginRight: "16px"
            },
            label: {
              marginBottom: "8px"
            }
          }}
        />
      </Box>
      <DialogFooter>
        {isLoading === true ? null : (
          <KudoButton formId="saveAs" isSubmit>
            <FormattedMessage id="saveAs" />
          </KudoButton>
        )}
      </DialogFooter>
    </>
  );
}
