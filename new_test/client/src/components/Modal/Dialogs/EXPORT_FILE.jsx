import React, { useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
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
import FilesListStore from "../../../stores/FilesListStore";
import MainFunctions from "../../../libraries/MainFunctions";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import Loader from "../../Loader";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  formGroup: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  label: {
    color: `${theme.palette.OBI} !important`,
    marginBottom: theme.spacing(1)
  }
}));

export default function exportFile({ info }) {
  const [isLoading, setLoading] = useState(false);

  const handleSubmit = json => {
    setLoading(true);
    const { filterArray, messageName } = info;
    const fileName = json.fileName.value.trim();
    if (!fileName) {
      SnackbarUtils.alertError({ id: "emptyName" });
    } else {
      const placeToMove = json.tree.value;
      if (placeToMove === null) {
        SnackbarUtils.alertError({ id: "noTargetFolder" });
      } else {
        const extension = filterArray[json.exportFile.value];
        newRequests
          .sendGenericRequest(
            `/folders/${placeToMove}`,
            RequestsMethods.GET,
            newRequests.getDefaultUserHeaders(),
            undefined,
            ["*"]
          )
          .then(response => {
            const namesOfEntitiesInTargetFolder =
              response.data.results.files.map(({ filename }) =>
                filename.toLowerCase()
              );

            if (
              namesOfEntitiesInTargetFolder.includes(
                `${fileName}.${extension}`.toLowerCase()
              )
            ) {
              SnackbarUtils.alertError({ id: "duplicateNameExportFile" });
              setLoading(false);
            } else {
              XenonConnectionActions.postMessage({
                messageName,
                fileName: json.fileName.value.trim(),
                value: filterArray[json.exportFile.value],
                path: json.tree.value
              });
              setLoading(false);
              ModalActions.hide();
            }
          })
          .catch(err => {
            SnackbarUtils.alertError(err.text);
          });
      }
    }
  };
  const { filterArray, saveFilterIndex } = info;
  const { storageType, storageId } = FilesListStore.findCurrentStorage();
  const validationFunction = InputValidationFunctions.getNameValidationFunction(
    MainFunctions.storageCodeToServiceName(storageType)
  );
  const classes = useStyles();

  return (
    <>
      <DialogBody>
        {isLoading === true ? (
          <Loader isModal message={<FormattedMessage id="analyzing" />} />
        ) : (
          <KudoForm id="exportFile" onSubmitFunction={handleSubmit}>
            <KudoInput
              type={InputTypes.TEXT}
              name="fileName"
              id="fileName"
              label="name"
              placeHolder="name"
              formId="exportFile"
              autoFocus
              classes={{ label: classes.label, formGroup: classes.formGroup }}
              isEmptyValueValid={false}
              validationFunction={validationFunction}
              inputDataComponent="export-file-input"
            />
            <KudoTreeView
              name="tree"
              id="tree"
              label="Folder"
              formId="exportFile"
              allowedTypes={["folders"]}
              allFoldersAllowed
              className={classes.formGroup}
              validationFunction={(objectId, rowInfo) => {
                if (!rowInfo) {
                  return true;
                }

                // if user isn't owner, and folder is shared for view only
                // - we cannot move files there
                if (!rowInfo.isOwner && rowInfo.shared && rowInfo.viewOnly)
                  return false;

                // if its external service root - also cant move there
                if (
                  MainFunctions.isExternalServiceRoot(
                    true,
                    objectId,
                    storageType
                  )
                ) {
                  return false;
                }

                // disable export to TR root
                if (storageType === "TR" && objectId === "-1") {
                  return false;
                }

                return true;
              }}
              storageType={storageType}
              storageId={storageId}
            />
            <KudoSelect
              defaultValue={saveFilterIndex}
              options={filterArray}
              id="exportFile"
              name="exportFile"
              label="saveAsType"
              formId="exportFile"
              styles={{
                label: {
                  marginBottom: "10px"
                }
              }}
              dataComponent="export-file-select"
            />
          </KudoForm>
        )}
      </DialogBody>

      {!isLoading ? (
        <DialogFooter>
          <KudoButton formId="exportFile" isSubmit>
            <FormattedMessage id="exportFile" />
          </KudoButton>
        </DialogFooter>
      ) : null}
    </>
  );
}
