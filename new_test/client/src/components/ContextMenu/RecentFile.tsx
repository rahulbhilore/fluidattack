import React from "react";
import PropTypes from "prop-types";
import MenuItem from "./MenuItem";
import MainFunctions, { APPLE_DEVICE } from "../../libraries/MainFunctions";
import FilesListStore, { CONTENT_LOADED } from "../../stores/FilesListStore";
import UserInfoStore from "../../stores/UserInfoStore";
import UserInfoActions from "../../actions/UserInfoActions";
import ApplicationActions from "../../actions/ApplicationActions";
import ApplicationStore from "../../stores/ApplicationStore";

import downloadSVG from "../../assets/images/context/download.svg";
import newtabSVG from "../../assets/images/context/newtab.svg";
import showFileLocation from "../../assets/images/context/showFileLocation.svg";
import openInCommanderSVG from "../../assets/images/context/openInCommander.svg";
import FilesListActions from "../../actions/FilesListActions";
import ModalActions from "../../actions/ModalActions";
import ContextMenuActions from "../../actions/ContextMenuActions";
import versionSVG from "../../assets/images/versionControl/version-control.svg";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import SmartTableActions from "../../actions/SmartTableActions";
import smartTableStore from "../../stores/SmartTableStore";
import { RenderFlags } from "./ContextMenu";
import { FileEntity } from "./Files";

export type RecentFileEntity = {
  id: string;
  name: string;
  folderId: string;
  storageId: string;
  storageType: string;
  filename: string;
  onDownloadStart?: () => void;
  onDownloadEnd?: () => void;
};

export function getRenderFlags({
  customObjectInfo
}: {
  customObjectInfo: Record<string, unknown>;
}): RenderFlags {
  if (!customObjectInfo || Object.keys(customObjectInfo).length === 0) {
    return {
      isNeedToRenderMenu: false,
      entities: [],
      type: "recent"
    };
  }
  return {
    isNeedToRenderMenu: true,
    entities: [customObjectInfo],
    type: "recent"
  };
}

export default function RecentFile({
  entities
}: {
  entities: Array<RecentFileEntity>;
}) {
  // just to be sure
  if (entities.length !== 1) return null;

  const objectInfo = entities[0];
  const { id: objectId } = objectInfo;

  const optionsList = [];
  const isMobile = MainFunctions.isMobileDevice();
  const isDrawing =
    UserInfoStore.findApp(
      MainFunctions.getExtensionFromName(objectInfo.name),
      ""
    ) === "xenon";
  const isOpenFileInAresAllowed = isDrawing && !UserInfoStore.isFreeAccount();

  const openFileInNewWindow = () => {
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
    const isPDF =
      Object.keys(objectInfo).length > 0 &&
      Object.prototype.hasOwnProperty.call(objectInfo, "name") &&
      MainFunctions.getExtensionFromName(objectInfo.name) === "pdf";
    if (isPDF) {
      FilesListStore.open(objectId, undefined, undefined, false, objectInfo);
    } else {
      window.open(
        `${UIPrefix}file/${objectId}`,
        "_blank",
        "noopener,noreferrer"
      );
    }
  };
  const openInCommander = () => {
    const { folderId, name, storageId, storageType } = objectInfo;
    FilesListActions.openFileInCommander(
      objectId,
      `${MainFunctions.serviceNameToStorageCode(
        storageType
      )}+${storageId}+${folderId}`,
      { name }
    )
      .then(() => {
        SnackbarUtils.alertOk({ id: isMobile ? "atLaunched" : "acLaunched" });
      })
      .catch(error => {
        ModalActions.openInCommander();
        if (error?.message === APPLE_DEVICE) {
          setTimeout(() => {
            ModalActions.hide();
          }, 5000);
        }
      });
  };
  const openParentFolder = () => {
    const { folderId, storageId, storageType } = objectInfo;
    const currentFolderId = FilesListStore.getCurrentFolder()._id;
    const currentMode = FilesListStore.getCurrentState();
    const { type, id } = UserInfoStore.getUserInfo("storage");
    const trimmedStorageType = storageType.trim().toLowerCase();
    const isAccountIncorrect =
      type.toLowerCase().trim() !== trimmedStorageType ||
      (storageId !== id &&
        trimmedStorageType !== "internal" &&
        trimmedStorageType !== "samples");
    // if folder or storage has changed - switch. Otherwise - nothing to do
    // always switch from trash
    const { objectId: shortenedCurrentFolderId } =
      MainFunctions.parseObjectId(currentFolderId);

    const checkAndScrollToTarget = async (loadedFolderId: string) => {
      FilesListStore.removeEventListener(
        CONTENT_LOADED,
        checkAndScrollToTarget
      );

      if (!loadedFolderId.includes(folderId)) return;

      try {
        const fullLoadedFolderId = `${MainFunctions.serviceNameToStorageCode(
          storageType
        )}+${storageId}+${folderId}`;

        const isObjectLoaded =
          FilesListStore.getTreeData(fullLoadedFolderId, undefined).findIndex(
            (o: FileEntity) => o._id === objectId
          ) > -1;

        if (!isObjectLoaded) {
          FilesListActions.addEntity(
            FilesListStore.parseFileData(
              await FilesListActions.getObjectInfo(objectId, "file", {}),
              storageType,
              storageId,
              Date.now()
            ),
            true
          );
        }

        SmartTableActions.scrollToId(
          smartTableStore.getTableIdByType("files"),
          objectId
        );
      } catch (error) {
        SnackbarUtils.alertError({ id: "errorShowLocation" });
      }
    };

    if (
      currentMode === "trash" ||
      folderId !== shortenedCurrentFolderId ||
      isAccountIncorrect
    ) {
      if (currentMode === "trash") {
        FilesListStore.setMode("browser");
      }
      // reset path to load proper one
      // https://graebert.atlassian.net/browse/XENON-43497
      FilesListActions.resetPath();
      ApplicationActions.changePage(
        `/files/${MainFunctions.serviceNameToStorageCode(
          storageType
        )}/${storageId}/${folderId}`,
        "CTX_MENU_parent_folder"
      );
      if (isAccountIncorrect) {
        UserInfoActions.changeStorage(storageType, storageId, {});
      }
      FilesListStore.addEventListener(CONTENT_LOADED, checkAndScrollToTarget);
    } else {
      // we are in the target folder already, so just scroll
      checkAndScrollToTarget(folderId);
    }
  };

  const downloadHandler = () => {
    const filename = objectInfo.name || objectInfo.filename;
    FilesListActions.downloadObjects([
      {
        id: objectId,
        type: "file",
        name: filename,
        onDownloadStart: objectInfo.onDownloadStart,
        onDownloadEnd: objectInfo.onDownloadEnd
      }
    ]);
  };

  const versionControlHandler = () => {
    const { folderId, name } = objectInfo;
    ModalActions.versionControl(objectId, folderId, name);
    ContextMenuActions.hideMenu();
  };

  optionsList.push(
    <MenuItem
      id="contextMenuOpenInNewWindow"
      onClick={openFileInNewWindow}
      image={newtabSVG}
      caption="openInNewTab"
      key="contextMenuOpenInNewWindow"
      dataComponent="open-in-new-window"
    />
  );
  if (isOpenFileInAresAllowed) {
    optionsList.push(
      <MenuItem
        id="contextMenuOpenInAres"
        onClick={openInCommander}
        image={openInCommanderSVG}
        caption={!isMobile ? "openInCommander" : "openInTouch"}
        key="contextMenuOpenInAres"
        dataComponent="open-in-ares"
      />
    );
  }
  if (
    Object.keys(objectInfo).length > 0 &&
    Object.prototype.hasOwnProperty.call(objectInfo, "storageType") &&
    Object.prototype.hasOwnProperty.call(objectInfo, "folderId") &&
    Object.prototype.hasOwnProperty.call(objectInfo, "storageId")
  ) {
    optionsList.push(
      <MenuItem
        id="contextMenuShowLocation"
        onClick={openParentFolder}
        image={showFileLocation}
        caption="showLocation"
        key="contextMenuShowLocation"
        dataComponent="show-location"
      />
    );
  }
  optionsList.push(
    <MenuItem
      id="contextMenuDownload"
      onClick={downloadHandler}
      image={downloadSVG}
      caption="download"
      key="contextMenuDownload"
      dataComponent="download"
    />
  );
  if (isDrawing) {
    optionsList.push(
      <MenuItem
        onClick={versionControlHandler}
        id="contextMenuManageVersions"
        image={versionSVG}
        caption="manageVersions"
        key="contextMenuManageVersions"
        dataComponent="manage-versions"
      />
    );
  }
  return optionsList;
}

RecentFile.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.string,
      name: PropTypes.string,
      filename: PropTypes.string,
      folderId: PropTypes.string,
      storageId: PropTypes.string,
      storageType: PropTypes.string,
      onDownloadEnd: PropTypes.func,
      onDownloadStart: PropTypes.func
    })
  ).isRequired
};
