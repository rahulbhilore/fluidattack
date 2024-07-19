import React, { useState } from "react";
import PropTypes from "prop-types";
import MenuItem from "../MenuItem";
import ContextMenuActions from "../../../actions/ContextMenuActions";
import showFolderLocationSVG from "../../../assets/images/context/showFolderLocation.svg";
import showFileLocationSVG from "../../../assets/images/context/showFileLocation.svg";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import FilesListActions from "../../../actions/FilesListActions";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListStore, { CONTENT_LOADED } from "../../../stores/FilesListStore";
import UserInfoStore from "../../../stores/UserInfoStore";
import SmartTableActions from "../../../actions/SmartTableActions";
import smartTableStore from "../../../stores/SmartTableStore";
import ApplicationActions from "../../../actions/ApplicationActions";
import UserInfoActions from "../../../actions/UserInfoActions";
import { ShortcutInfo } from "../Files";

export default function ShowShortcutLocation({
  shortcutInfo
}: {
  shortcutInfo: ShortcutInfo;
}) {
  const [isLoading, setLoading] = useState(false);

  const openParentFolder = (fullParentId: string) => {
    const {
      objectId: parentFolderId,
      storageType: parentStorageType,
      storageId: parentStorageId
    } = MainFunctions.parseObjectId(fullParentId);

    const parentStorageServiceName =
      MainFunctions.storageCodeToServiceName(parentStorageType);

    const currentFolderId = FilesListStore.getCurrentFolder()._id;
    const currentMode = FilesListStore.getCurrentState();
    const { type, id } = UserInfoStore.getUserInfo("storage");
    const trimmedStorageType = parentStorageServiceName.trim().toLowerCase();
    const isAccountIncorrect =
      type.toLowerCase().trim() !== trimmedStorageType ||
      (parentStorageId !== id &&
        trimmedStorageType !== "internal" &&
        trimmedStorageType !== "samples");
    // if folder or storage has changed - switch. Otherwise - nothing to do
    // always switch from trash
    const { objectId: shortenedCurrentFolderId } =
      MainFunctions.parseObjectId(currentFolderId);
    const scrollToTarget = (loadedFolderId: string) => {
      if (loadedFolderId.includes(parentFolderId)) {
        SmartTableActions.scrollToId(
          smartTableStore.getTableIdByType("files"),
          shortcutInfo.targetId
        );
        FilesListStore.removeEventListener(CONTENT_LOADED, scrollToTarget);
      }
    };
    if (
      currentMode === "trash" ||
      parentFolderId !== shortenedCurrentFolderId ||
      isAccountIncorrect
    ) {
      if (currentMode === "trash") {
        FilesListStore.setMode("browser");
      }
      // reset path to load proper one
      // https://graebert.atlassian.net/browse/XENON-43497
      ApplicationActions.changePage(
        `/files/${parentStorageType}/${parentStorageId}/${parentFolderId}`,
        "CTX_MENU_parent_folder"
      );
      FilesListActions.resetPath();
      if (isAccountIncorrect) {
        UserInfoActions.changeStorage(
          parentStorageServiceName,
          parentStorageId,
          {}
        );
      }
      FilesListStore.addEventListener(CONTENT_LOADED, scrollToTarget);
    } else {
      // we are in the target folder already, so just scroll
      scrollToTarget(parentFolderId);
    }
  };

  const showLocationHandler = () => {
    if (isLoading) {
      return;
    }

    setLoading(true);

    FilesListActions.getObjectInfo(shortcutInfo.targetId, shortcutInfo.type, {})
      .then(targetObjectInfo => {
        if (targetObjectInfo.isDeleted) {
          SnackbarUtils.alertError({
            id: "getDeletedShortcutTargetInfoError"
          });
        } else {
          openParentFolder(
            targetObjectInfo.folderId || targetObjectInfo.parent
          );
        }
      })
      .catch(() => {
        SnackbarUtils.alertError({
          id: "getShortcutTargetInfoError"
        });
      })
      .finally(() => {
        ContextMenuActions.hideMenu();
      });
  };

  return (
    <MenuItem
      onClick={showLocationHandler}
      autoClose={false}
      id="contextMenuShowShortcutLocation"
      caption="showShortcutLocation"
      image={
        shortcutInfo.type === "file"
          ? showFileLocationSVG
          : showFolderLocationSVG
      }
      dataComponent="email-notifications"
      isLoading={isLoading}
    />
  );
}

ShowShortcutLocation.propTypes = {
  shortcutInfo: PropTypes.shape({
    mimeType: PropTypes.string,
    type: PropTypes.string,
    targetId: PropTypes.string
  }).isRequired
};
