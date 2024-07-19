import React, { useRef } from "react";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import FilesListActions from "../../../actions/FilesListActions";
import KudoTreeView from "../../Inputs/KudoTreeView/KudoTreeView";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import FilesListStore from "../../../stores/FilesListStore";
import SmartTableStore from "../../../stores/SmartTableStore";
import DialogBody, { DEFAULT_SCROLL_TIME } from "../DialogBody";
import DialogFooter from "../DialogFooter";
import ModalActions from "../../../actions/ModalActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const isMoveToRestricted = selectedObjectInfo => {
  if (_.isEmpty(selectedObjectInfo)) return false;
  if (selectedObjectInfo?.storage === "GD") {
    // GDrive specific
    if (selectedObjectInfo?.capabilities?.canAddChildren === false) return true;
    if (selectedObjectInfo?.capabilities?.canAddChildren === true) return false;
  }
  if (
    selectedObjectInfo.permissions &&
    selectedObjectInfo.permissions.canMoveTo === false
  )
    return true;
  return false;
};

// XENON-62610
// This is a bad way, but requesting root info might be bad either?
const ROOT_FOLDER_MOVE_DISABLED = ["GD", "TR", "SF", "OD", "ODB", "SP"];

const isMoveAllowed = (selectedObjectId, selectedObjectInfo) => {
  const { storageType } = FilesListStore.findCurrentStorage();
  if (
    selectedObjectId === "-1" &&
    ROOT_FOLDER_MOVE_DISABLED.includes(storageType)
  ) {
    return false;
  }
  if (!selectedObjectInfo || Object.keys(selectedObjectInfo).length === 0) {
    return true;
  }
  if (isMoveToRestricted(selectedObjectInfo)) {
    return false;
  }
  // if selected current folder
  if (FilesListStore.getCurrentFolder()._id === selectedObjectId) return false;
  // if target folder is selected as move object
  if (
    SmartTableStore.getSelectedRowsByTableType("files")[0] === selectedObjectId
  )
    return false;
  // if (TableStore.isSelected(TableStore.getFocusedTable(), selectedObjectId))
  //   return false;
  const selectedRowInfo = FilesListStore.getObjectInfoInCurrentFolder(
    SmartTableStore.getSelectedRowsByTableType("files")[0]
  );

  if (storageType === "TR") {
    // for Trimble - we can only move within the same project
    return (
      selectedRowInfo.projectId &&
      selectedRowInfo.projectId === selectedObjectInfo.projectId
    );
  }

  // if target folder is parent of one of the folders in selection
  if (FilesListStore.isParentOfSelected(selectedObjectId)) return false;
  // if user isn't owner, and folder is shared for view only
  // - we cannot move files there
  if (
    !selectedObjectInfo.isOwner &&
    selectedObjectInfo.shared &&
    selectedObjectInfo.viewOnly
  )
    return false;
  // allowSubfolders check for OS
  // https://graebert.atlassian.net/browse/XENON-31774
  if (
    Object.prototype.hasOwnProperty.call(
      selectedObjectInfo,
      "allowSubfolders"
    ) &&
    selectedObjectInfo.allowSubfolders === false
  ) {
    return false;
  }
  return true;
};

const useStyles = makeStyles(() => ({
  content: {
    padding: 0
  }
}));

let keyPressStop = false;

export default function moveObjects() {
  const dialogRef = useRef(null);

  const handleSubmit = json => {
    if (json.tree.valid === true && json.tree.value) {
      FilesListActions.moveSelected(
        json.tree.value,
        SmartTableStore.getSelectedRowsByTableType("files")
      );
      ModalActions.hide();
    } else {
      SnackbarUtils.alertError({ id: "noTargetFolder" });
    }
  };

  const { storageType, storageId } = FilesListStore.findCurrentStorage();

  // TODO: do not sure that this is needed
  // const [selectedObj] = TableStore.getSelection(TableStore.getFocusedTable());
  const baseFolderId = "-1";
  // if (selectedObj && storageType === "SP" && selectedObj.driveId) {
  //   baseFolderId = `SP+${storageId}+${selectedObj.driveId}:root`;
  // }

  const stopScroll = () => keyPressStop;

  const stopScrollTree = needToScroll => {
    if (!needToScroll) return;
    keyPressStop = needToScroll;
    setTimeout(() => {
      keyPressStop = false;
    }, DEFAULT_SCROLL_TIME);
  };

  const classes = useStyles();
  return (
    <>
      <DialogBody
        className={classes.content}
        stopScroll={stopScroll}
        ref={dialogRef}
      >
        <KudoForm id="moveForm" onSubmitFunction={handleSubmit} autofocus>
          <KudoTreeView
            name="tree"
            id="tree"
            formId="moveForm"
            allowedTypes={["folders"]}
            storageType={storageType}
            storageId={storageId}
            baseFolderId={baseFolderId}
            validationFunction={isMoveAllowed}
            stopScrollTree={stopScrollTree}
            scrollContainer={dialogRef}
            disableSubfoldersOfSelected
            selectedObjects={SmartTableStore.getSelectedRowsByTableType(
              "files"
            )}
          />
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton isSubmit formId="moveForm">
          <FormattedMessage id="move" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
