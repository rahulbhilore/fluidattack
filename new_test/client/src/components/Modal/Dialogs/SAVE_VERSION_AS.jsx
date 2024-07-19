import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import KudoTreeView from "../../Inputs/KudoTreeView/KudoTreeView";
import FilesListStore, { CONTENT_LOADED } from "../../../stores/FilesListStore";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ModalActions from "../../../actions/ModalActions";
import VersionControlActions from "../../../actions/VersionControlActions";
import MainFunctions from "../../../libraries/MainFunctions";
import newRequests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import Loader from "../../Loader";

import FilesListActions from "../../../actions/FilesListActions";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  input: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  label: {
    color: `${theme.palette.OBI} !important`
  },
  button: {
    height: "36px",
    padding: "10px 30px",
    textTransform: "uppercase"
  },
  cancel: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE
  }
}));

export default function saveVersionAsDialog({ info }) {
  const [usedNames, setUsedNames] = useState([]);
  const [isLoading, setLoading] = useState(false);
  const [selectedFolder, setSelected] = useState("-1");
  const { storageType, storageId } = FilesListStore.findCurrentStorage();

  const classes = useStyles();

  const onContentLoaded = () => {
    if (!selectedFolder) return;

    setUsedNames(
      FilesListStore.getTreeData(selectedFolder)
        .filter(elem => elem.type === "file")
        .map(elem => elem.name.replace(/.dwg/, ""))
    );
  };

  useEffect(() => {
    FilesListStore.addEventListener(CONTENT_LOADED, onContentLoaded);
    return () => {
      FilesListStore.removeEventListener(CONTENT_LOADED, onContentLoaded);
    };
  }, [selectedFolder]);

  useEffect(
    () => () => {
      ModalActions.versionControl(info.fileId, info.folderId, info.fileName);
    },
    []
  );

  const saveForCurrentFolder = filename => {
    const { folderId, fileId, versionId } = info;

    setLoading(true);

    VersionControlActions.downloadVersion(fileId, versionId)
      .then(response => {
        const file = new File([response.data], filename);

        const newEntityId = MainFunctions.guid();
        const newEntity = {
          folderId,
          id: newEntityId,
          _id: newEntityId,
          processId: newEntityId,
          name: filename,
          type: "file"
        };

        FilesListActions.addEntity(newEntity);
        FilesListStore.createFileInCurrentDirectory(
          filename,
          folderId,
          file,
          newEntityId
        )
          .then(answer => {
            SnackbarUtils.alertOk({ id: "versionSaved" });
            ModalActions.versionControl(fileId, folderId, info.fileName);

            FilesListActions.modifyEntity(newEntityId, {
              _id: answer.fileId,
              id: answer.fileId
            });
            setTimeout(() => {
              FilesListActions.updateEntityInfo(answer.fileId, "files").catch(
                () => {
                  SnackbarUtils.alertError({ id: "ERROR" });
                  setLoading(false);
                }
              );
            }, 0);
          })
          .catch(err => {
            const errorMessage = MainFunctions.getErrorMessage(err);
            SnackbarUtils.alertError(errorMessage || { id: "ERROR" });
            setLoading(false);
          });
      })
      .catch(err => {
        const errorMessage = MainFunctions.getErrorMessage(err);
        SnackbarUtils.alertError(errorMessage || { id: "ERROR" });
        setLoading(false);
      });
  };

  const saveForOtherFolder = (filename, dir) => {
    const { fileId, versionId } = info;
    setLoading(true);

    VersionControlActions.downloadVersion(fileId, versionId)
      .then(response => {
        const file = new File([response.data], filename);
        const fd = new FormData();
        fd.append(0, file);
        const headers = newRequests.getDefaultUserHeaders();

        if (dir !== "-1") headers.folderId = dir;

        newRequests
          .sendGenericRequest(`/files`, RequestsMethods.POST, headers, fd, [
            "*"
          ])
          .then(() => {
            SnackbarUtils.alertOk({ id: "versionSaved" });
            ModalActions.versionControl(fileId, info.folderId, info.fileName);
          })
          .catch(err => {
            const errorMessage = MainFunctions.getErrorMessage(err);
            SnackbarUtils.alertError(errorMessage || { id: "ERROR" });
            setLoading(false);
          });
      })
      .catch(err => {
        const errorMessage = MainFunctions.getErrorMessage(err);
        SnackbarUtils.alertError(errorMessage || { id: "ERROR" });
        setLoading(false);
      });
  };

  const handleSubmit = json => {
    const { folderId } = info;

    const filename = `${json?.fileName?.value.trim()}.dwg`;
    const dir = json?.tree?.value;

    if (dir === folderId && location.pathname.includes("files"))
      return saveForCurrentFolder(filename);

    return saveForOtherFolder(filename, dir);
  };
  const validationFunction = InputValidationFunctions.getNameValidationFunction(
    MainFunctions.storageCodeToServiceName(storageType)
  );

  const handleOnChange = e => {
    if (e === "-1") {
      setUsedNames(
        FilesListStore.getTreeData(`${storageType}+${storageId}+-1`)
          .filter(elem => elem.type === "file")
          .map(elem => elem.name.replace(/.dwg/, ""))
      );
    } else {
      setSelected(e);
    }
  };

  return (
    <>
      <DialogBody>
        {isLoading ? (
          <Loader isModal message={<FormattedMessage id="saving" />} />
        ) : (
          <KudoForm id="saveVersionAs" onSubmitFunction={handleSubmit}>
            <KudoInput
              type={InputTypes.TEXT}
              name="fileName"
              id="fileName"
              label="name"
              placeHolder="name"
              formId="saveVersionAs"
              autoFocus
              isEmptyValueValid={false}
              restrictedValues={usedNames}
              restrictedValuesCaseInsensitive
              classes={{
                formGroup: classes.input,
                label: classes.label
              }}
              validationFunction={validationFunction}
              inputDataComponent="save-version-as-input"
            />
            <KudoTreeView
              name="tree"
              id="tree"
              label="Folder"
              formId="saveVersionAs"
              allowedTypes={["folders"]}
              allFoldersAllowed
              validationFunction={(value, rowInfo) => {
                if (!rowInfo) {
                  return true;
                }

                // disable save in library level of SP
                if (rowInfo.storageType === "SP" && rowInfo.offsetLevel < 2) {
                  return false;
                }

                return !rowInfo.viewOnly; // we check access (FULL_ACCESS/NO_ACCESS) on view-only
              }}
              storageType={storageType}
              storageId={storageId}
              onChange={handleOnChange}
            />
          </KudoForm>
        )}
      </DialogBody>
      <DialogFooter>
        <KudoButton formId="saveVersionAs" isSubmit>
          <FormattedMessage id="saveAs" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
