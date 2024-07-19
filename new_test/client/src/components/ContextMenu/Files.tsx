import React from "react";
import PropTypes from "prop-types";
import ApplicationStore from "../../stores/ApplicationStore";
import UserInfoStore from "../../stores/UserInfoStore";
import ModalActions from "../../actions/ModalActions";
import MainFunctions from "../../libraries/MainFunctions";
import FilesListStore from "../../stores/FilesListStore";
import FilesListActions from "../../actions/FilesListActions";
import ContextMenuActions from "../../actions/ContextMenuActions";
import SubscriptionControl from "./SubscriptionControl";
import MenuItem from "./MenuItem";

// icons
import cloneSVG from "../../assets/images/context/clone.svg";
import deleteSVG from "../../assets/images/context/delete.svg";
import downloadSVG from "../../assets/images/context/download.svg";
import moveSVG from "../../assets/images/context/move.svg";
import createShortcutSVG from "../../assets/images/dialogs/icons/createShortcut.svg";
import newtabSVG from "../../assets/images/context/newtab.svg";
import permissionsSVG from "../../assets/images/context/permissionsWhite.svg";
import renameSVG from "../../assets/images/context/rename.svg";
import versionSVG from "../../assets/images/versionControl/version-control.svg";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import ProcessStore from "../../stores/ProcessStore";
import ShowShortcutLocation from "./Options/ShowShortcutLocation";
import OpenInAresCommander from "./Options/OpenInAresCommander";
import { RenderFlagsParams } from "./ContextMenu";

export type ShortcutInfo = {
  targetId: string;
  type: string;
  mimeType: string;
};

export type FileEntity = {
  isClientSideData: boolean;
  isDeleted: boolean;
  id: string;
  _id: string;
  type: string;
  name: string;
  isShortcut: boolean;
  filename: string;
  isExport: boolean;
  isOwner: boolean;
  parent: string;
  folderId: string;
  viewOnly: boolean;
  sizeValue: number;
  mimeType: string;
  permissions: {
    canManagePermissions: boolean;
    canViewPermissions: boolean;
    canUnShare: boolean;
    canDownload: boolean;
    canMove: boolean;
    canDelete: boolean;
    canRename: boolean;
    canClone: boolean;
    canViewPublicLink: boolean;
    canManagePublicLink: boolean;
  };
  shortcutInfo?: ShortcutInfo;
  viewFlag: boolean;
  updateDate: number;
  changer: string;
  conflictingReason?: string;
  shared: boolean;
};

export function getRenderFlags({ ids, infoProvider }: RenderFlagsParams) {
  // XENON-64220 and similar
  // if id starts with CS_ - this is client-side upload
  // so we don't have all info and cannot work with it properly
  // TODO: remove from selection?
  const entities = ids
    .filter(id => id && !id.startsWith("CS_"))
    .map(id => infoProvider(id))
    .filter(
      entityData => entityData?.isClientSideData !== true
    ) as Array<FileEntity>;
  if (entities.length < 1) {
    return {
      isNeedToRenderMenu: false,
      entities: [],
      type: "files"
    };
  }
  const isAnyDeleted = entities.find(({ isDeleted }) => isDeleted);
  return {
    isNeedToRenderMenu: true,
    entities,
    type: isAnyDeleted ? "trash" : "files"
  };
}

export default function Files({
  entities,
  tableId
}: {
  entities: Array<FileEntity>;
  tableId: string;
}) {
  // just to be sure
  if (entities.length < 1 || ProcessStore.isProcess(entities[0].id))
    return null;

  const isSingleObjectSelected = entities.length === 1;
  const firstEntity = entities[0];
  const currentStorage = MainFunctions.storageCodeToServiceName(
    FilesListStore.findCurrentStorage(firstEntity.id).storageType
  );
  const isSearchPage = location.href.includes("search");

  const shareObject = () => {
    ModalActions.shareManagement(
      firstEntity.id,
      firstEntity.name,
      firstEntity.type
    );
  };

  const openFileInNewWindow = () => {
    const {
      storageType,
      storageId,
      objectId: pureId
    } = MainFunctions.parseObjectId(
      firstEntity.isShortcut
        ? firstEntity.shortcutInfo?.targetId || firstEntity.id
        : firstEntity.id
    );

    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");

    // open folder shortcut
    if (firstEntity.isShortcut && firstEntity.shortcutInfo?.type === "folder") {
      window.open(
        `${UIPrefix}files/${storageType}/${storageId}/${pureId}`,
        "_blank",
        "noopener,noreferrer"
      );
      return;
    }

    if (firstEntity.type === "file") {
      const isPDF =
        Object.prototype.hasOwnProperty.call(firstEntity, "name") &&
        MainFunctions.getExtensionFromName(
          firstEntity.isShortcut
            ? firstEntity.shortcutInfo?.mimeType || firstEntity.name
            : firstEntity.name
        ) === "pdf";

      if (isPDF) {
        FilesListStore.open(
          firstEntity.id,
          undefined,
          undefined,
          false,
          firstEntity
        );
      } else {
        window.open(
          `${UIPrefix}file/${
            firstEntity.isShortcut
              ? firstEntity.shortcutInfo?.targetId || firstEntity.id
              : firstEntity.id
          }`,
          "_blank",
          "noopener,noreferrer"
        );
      }
    } else {
      window.open(
        `${UIPrefix}files/${storageType}/${storageId}/${pureId}`,
        "_blank",
        "noopener,noreferrer"
      );
    }
  };

  const renameObject = () => {
    FilesListActions.setEntityRenameMode(firstEntity.id);
  };

  const findNameOfCopy = (name: string, type: string, action?: string) => {
    const { _id: currentFolderId, viewOnly } =
      FilesListStore.getCurrentFolder();
    let finalFolderId = currentFolderId;
    if (
      action &&
      action === "clone" &&
      (finalFolderId.endsWith("shared") || viewOnly === true)
    ) {
      finalFolderId = `${finalFolderId.substr(
        0,
        finalFolderId.lastIndexOf("+") + 1
      )}root`;
    }
    const entitiesInFolder = FilesListStore.getTreeData(
      finalFolderId
    ) as Array<FileEntity>;
    let extension = "";
    let baseName = "";
    let copyNumber = 1;
    if (type === "folder") {
      baseName = name;
    } else {
      extension = MainFunctions.getExtensionFromName(name);
      baseName = name.substr(0, name.lastIndexOf(".")) || name;
    }

    let endName = `${baseName}(${copyNumber})`;

    const filteredResults = entitiesInFolder.filter(elem => elem.type === type);

    const isDuplicatedNames = () =>
      filteredResults.find(elem => {
        const { name: entityName } = elem;

        return type === "folder"
          ? endName === entityName
          : endName ===
              (entityName.substr(0, entityName.lastIndexOf(".")) || entityName);
      });

    while (isDuplicatedNames()) {
      copyNumber += 1;
      endName = `${baseName}(${copyNumber})`;
    }
    return type === "folder" ? endName : `${endName}.${extension}`;
  };

  const cloneHandler = async () => {
    const { _id: currentFolderId, viewOnly } =
      FilesListStore.getCurrentFolder();
    let folderId;
    if (currentFolderId.endsWith("shared") || viewOnly === true) {
      folderId = `${currentFolderId.substr(
        0,
        currentFolderId.lastIndexOf("+") + 1
      )}root`;
    } else {
      folderId = currentFolderId;
    }
    if (folderId !== currentFolderId) {
      const entitiesInFolder = FilesListStore.getTreeData(folderId);
      if (entitiesInFolder.length === 0) {
        const { storageType, storageId, objectId } =
          MainFunctions.parseObjectId(folderId);
        await FilesListStore.fetchFolderContent(
          storageType,
          storageId,
          objectId,
          false,
          {
            isIsolated: false,
            recursive: true,
            usePageToken: false
          }
        );
      }
    }
    const copyName = findNameOfCopy(
      firstEntity.name || firstEntity.filename,
      firstEntity.type,
      "clone"
    );
    ModalActions.cloneObject(
      firstEntity.id,
      firstEntity.type,
      copyName,
      firstEntity.isShortcut
    );
  };

  const createShortcutHandler = () => {
    const shortcutName = findNameOfCopy(
      (firstEntity.name || firstEntity.filename).concat("-shortcut"),
      "folder"
    );
    ModalActions.createShortcut(firstEntity.id, firstEntity.type, shortcutName);
  };

  const downloadHandler = () => {
    if (entities.length) {
      FilesListActions.downloadObjects(entities);
    }
  };

  const moveHandler = () => {
    ModalActions.moveObjects(entities);
  };

  const canRemoveShare = (entity: FileEntity) =>
    (entity?.isOwner === false &&
      (entity?.permissions?.canManagePermissions ||
        entity?.permissions?.canViewPermissions)) ||
    (currentStorage === "onshape" && entity?.permissions?.canUnShare);

  const removeShareHandler = () => {
    ModalActions.removeShare(
      firstEntity.id,
      firstEntity.name,
      firstEntity.type
    );
  };

  const deleteHandler = () => {
    if (
      // TODO: replace
      !FilesListStore.isAnyShared() ||
      currentStorage === "onshape" ||
      currentStorage === "onshapedev" ||
      currentStorage === "onshapestaging" ||
      currentStorage === "sharepoint" ||
      currentStorage === "samples" ||
      currentStorage.includes("webdav")
    ) {
      ModalActions.deleteObjects(
        !isSearchPage ? "files" : "search",
        entities.map(({ _id, id, name, type, parent, folderId }) => ({
          id: id || _id,
          name,
          type,
          folderId: parent || folderId
        })),
        null,
        { tableId }
      );
    } else {
      SnackbarUtils.alertError({ id: "sharedDeleteError" });
    }
  };

  const checkPermission = (
    entity: FileEntity,
    name: keyof FileEntity["permissions"]
  ) => {
    if (
      Object.keys(entity.permissions || {}).length > 0 &&
      Object.prototype.hasOwnProperty.call(entity.permissions, name)
    ) {
      return entity.permissions[name] !== false;
    }
    return true;
  };

  const versionControlHandler = () => {
    const { id, folderId, filename } = firstEntity;
    ModalActions.versionControl(id, folderId, filename);
    ContextMenuActions.hideMenu();
  };

  const optionsList = [];

  const addCommonMenuOptions = () => {
    if (entities.every(ent => checkPermission(ent, "canDownload"))) {
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
    }
    if (
      isSearchPage === false &&
      entities.every(ent => checkPermission(ent, "canMove"))
    ) {
      optionsList.push(
        <MenuItem
          id="contextMenuMove"
          onClick={moveHandler}
          image={moveSVG}
          caption="move"
          key="contextMenuMove"
          dataComponent="move"
        />
      );
    }

    if (entities.every(ent => checkPermission(ent, "canDelete"))) {
      optionsList.push(
        <MenuItem
          id="contextMenuDelete"
          onClick={deleteHandler}
          image={deleteSVG}
          caption="delete"
          key="contextMenuDelete"
          dataComponent="delete"
        />
      );
    } else if (
      entities.every(ent => canRemoveShare(ent)) &&
      isSingleObjectSelected === true
    ) {
      optionsList.push(
        <MenuItem
          id="contextMenuRemoveShare"
          onClick={removeShareHandler}
          image={deleteSVG}
          caption="removeShare"
          key="contextMenuRemoveShare"
          dataComponent="removeShare"
        />
      );
    }
  };

  if (isSingleObjectSelected === true) {
    const { objectId: currentFolderId } = MainFunctions.parseObjectId(
      FilesListStore.getCurrentFolder()._id
    );

    // XENON-31043 - Regression: Unable to open file shared through dropbox
    const isDropboxSharedFile =
      currentStorage === "dropbox" &&
      firstEntity.type === "file" &&
      (firstEntity.viewOnly === true || firstEntity.sizeValue === 0) &&
      currentFolderId === "-1";

    const isFolder = firstEntity.type === "folder";

    const isDrawing =
      !isFolder &&
      UserInfoStore.findApp(
        MainFunctions.getExtensionFromName(
          firstEntity.isShortcut
            ? firstEntity.shortcutInfo?.mimeType || firstEntity.name
            : firstEntity.name || firstEntity.filename
        ),
        firstEntity.isShortcut
          ? firstEntity.shortcutInfo?.type || firstEntity.mimeType
          : firstEntity.mimeType
      ) === "xenon";

    const isEntityAvailableForOpen =
      isDrawing ||
      isFolder ||
      (firstEntity.isShortcut && firstEntity.shortcutInfo?.type === "folder");
    const isSharingAllowed =
      UserInfoStore.isFeatureAllowedByStorage(
        currentStorage,
        "share",
        firstEntity.type
      ) ||
      (isDrawing &&
        UserInfoStore.isFeatureAllowedByStorage(
          currentStorage,
          "share",
          "publicLink"
        ));
    const isFreeAccount = UserInfoStore.isFreeAccount();
    const isInIframe = MainFunctions.isInIframe();
    const hasCommanderLicense = UserInfoStore.getUserInfo(
      "hasCommanderLicense"
    );
    const hasTouchLicense = UserInfoStore.getUserInfo("hasTouchLicense");
    const mobile = MainFunctions.isMobileDevice();
    const hasAppropriateLicense =
      (mobile && hasTouchLicense) || (!mobile && hasCommanderLicense);
    const isOpenFileInAresAllowed =
      isDrawing && !isFreeAccount && !isInIframe && hasAppropriateLicense;

    const canCreateShortcut =
      currentStorage === "gdrive" &&
      currentFolderId !== "-1" &&
      !firstEntity.isShortcut;

    if (isDropboxSharedFile === true && isSharingAllowed) {
      optionsList.push(
        <MenuItem
          id="contextMenuPermissions"
          onClick={shareObject}
          image={permissionsSVG}
          caption="permissions"
          key="contextMenuPermissions"
          dataComponent="permissions"
        />
      );
      return optionsList;
    }

    if (isEntityAvailableForOpen) {
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
    }

    if (isOpenFileInAresAllowed) {
      optionsList.push(<OpenInAresCommander fileInfo={firstEntity} />);
    }

    if (isSearchPage === false && checkPermission(firstEntity, "canRename")) {
      optionsList.push(
        <MenuItem
          id="contextMenuRename"
          onClick={renameObject}
          image={renameSVG}
          caption="rename"
          key="contextMenuRename"
          dataComponent="rename"
        />
      );
    }

    if (isSearchPage === false && checkPermission(firstEntity, "canClone")) {
      optionsList.push(
        <MenuItem
          id="contextMenuClone"
          onClick={cloneHandler}
          image={cloneSVG}
          caption="clone"
          key="contextMenuClone"
          dataComponent="clone"
        />
      );
    }

    addCommonMenuOptions();
    if (
      isSharingAllowed &&
      (checkPermission(firstEntity, "canManagePermissions") ||
        checkPermission(firstEntity, "canViewPublicLink") ||
        checkPermission(firstEntity, "canViewPermissions") ||
        checkPermission(firstEntity, "canManagePublicLink"))
    ) {
      optionsList.push(
        <MenuItem
          id="contextMenuPermissions"
          onClick={shareObject}
          image={permissionsSVG}
          caption="permissions"
          key="contextMenuPermissions"
          dataComponent="permissions"
        />
      );
    }

    if (
      !firstEntity.isShortcut &&
      isDrawing &&
      isEntityAvailableForOpen &&
      !firstEntity.viewOnly
    ) {
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

    if (canCreateShortcut) {
      optionsList.push(
        <MenuItem
          onClick={createShortcutHandler}
          id="contextMenuCreateShortcut"
          image={createShortcutSVG}
          caption="createShortcut"
          key="contextMenuCreateShortcut"
          dataComponent="create-shortcut"
        />
      );
    }

    if (firstEntity.isShortcut) {
      optionsList.push(
        <ShowShortcutLocation
          shortcutInfo={firstEntity.shortcutInfo as ShortcutInfo}
        />
      );
    }

    if (
      !firstEntity.isShortcut &&
      isDrawing &&
      UserInfoStore.areEmailNotificationsAvailable()
    ) {
      optionsList.push(
        <SubscriptionControl
          id={firstEntity.id}
          name={firstEntity.name || firstEntity.filename}
        />
      );
    }
  } else {
    addCommonMenuOptions();
  }

  // This is awful way to do this. Should be refactored to be similar to versions multiselect with SmartTable
  if (!isSearchPage && entities.length === 2) {
    // const firstExtension = MainFunctions.getExtensionFromName(
    //   entities[0].name || entities[0].filename
    // );
    // const secondExtension = MainFunctions.getExtensionFromName(
    //   entities[0].name || entities[0].filename
    // );
    // const isFirstDrawing =
    //   entities[0].type === "file" &&
    //   UserInfoStore.findApp(firstExtension, entities[0].mimeType) === "xenon";
    // const isSecondDrawing =
    //   entities[1].type === "file" &&
    //   UserInfoStore.findApp(secondExtension, entities[1].mimeType) === "xenon";
    // if (isFirstDrawing && isSecondDrawing) {
    //  const compareVersions = () => {
    //    ModalActions.compareDrawings(
    //      {
    //        id: entities[0].id,
    //        versionId: entities[0].versionId,
    //        thumbnail: entities[0].thumbnail,
    //        ext: firstExtension
    //      },
    //      {
    //        id: entities[1].id,
    //        versionId: entities[1].versionId,
    //        thumbnail: entities[1].thumbnail,
    //        ext: secondExtension
    //      }
    //    );
    //  };
    //  optionsList.push(
    //    <MenuItem
    //      id="contextMenuCompareDrawings"
    //      onClick={compareVersions}
    //      image={promoteSVG}
    //      caption="compare"
    //      dataComponent="compare"
    //    />
    //  );
    // }
  }

  return optionsList;
}

Files.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.string,
      accountId: PropTypes.string,
      name: PropTypes.string,
      type: PropTypes.string,
      isShortcut: PropTypes.bool,
      shortcutInfo: PropTypes.shape({
        mimeType: PropTypes.string,
        type: PropTypes.string,
        targetId: PropTypes.string
      }),
      parent: PropTypes.string,
      filename: PropTypes.string,
      folderId: PropTypes.string,
      viewOnly: PropTypes.bool,
      sizeValue: PropTypes.number,
      mimeType: PropTypes.string,
      onDownloadEnd: PropTypes.func,
      onDownloadStart: PropTypes.func
    })
  ).isRequired,
  tableId: PropTypes.string.isRequired
};
