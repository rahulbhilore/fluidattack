import React from "react";
import PropTypes from "prop-types";
import MenuItem from "./MenuItem";
import deleteSVG from "../../assets/images/context/delete.svg";
import editDrawingSVG from "../../assets/images/context/editDrawing.svg";
import downloadSVG from "../../assets/images/context/download.svg";
import permissionsSVG from "../../assets/images/context/permissionsWhite.svg";
import BlocksActions from "../../actions/BlocksActions";
import ModalActions from "../../actions/ModalActions";
import MainFunctions from "../../libraries/MainFunctions";
import ProcessActions from "../../actions/ProcessActions";
import Processes from "../../constants/appConstants/Processes";
import userInfoStore from "../../stores/UserInfoStore";
import blocksStore, { BLOCK, LIBRARY } from "../../stores/BlocksStore";
import { UPDATE_ENABLED } from "../Pages/ResourcesPage/BlocksCapabilities";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";

const DELETE = "DELETE";
const REMOVE = "REMOVE";

export type BlockEntity = {
  ownerId: string;
  ownerType: string;
  isSharedBlocksCollection: boolean;
  shares: Array<{
    userId: string;
    mode: string;
  }>;
  type: typeof BLOCK | typeof LIBRARY;
  libId: string;
  id: string;
  name: string;
  description: string;
  fileName: string;
};

const checkIsOwner = (entity: BlockEntity) => {
  const { ownerId, ownerType } = entity;
  let isOwner = false;
  if (ownerType === "USER" && ownerId === userInfoStore.getUserInfo("id")) {
    isOwner = true;
  } else if (ownerType === "ORG") {
    isOwner = userInfoStore.getUserInfo("company")?.isAdmin;
  } else if (ownerType === "PUBLIC") {
    isOwner = false;
  }
  return isOwner;
};

const checkIsViewOnly = (entity: BlockEntity): boolean => {
  const isOwner = checkIsOwner(entity);
  if (isOwner) return false;
  const { ownerType } = entity;
  if (entity.isSharedBlocksCollection === true) {
    return true;
  }
  // public libraries shouldn't be changed manually
  if (ownerType === "PUBLIC") {
    return true;
  }

  let isViewOnly = true;
  const { shares = [] } = entity;
  if (shares.length > 0) {
    const shareInfo = shares.find(
      ({ userId }) => userId === userInfoStore.getUserInfo("id")
    );
    if (shareInfo) {
      isViewOnly = shareInfo.mode !== "editor";
    }
  }
  if (!isViewOnly) {
    const { type, libId } = entity;
    // check parent library
    if (type === BLOCK) {
      const libInfo = blocksStore.getLibraryInfo(libId);
      if (libInfo) {
        return checkIsViewOnly(libInfo);
      }
    }
  }
  return isViewOnly;
};

const checkIsSharedWithYou = (entity: BlockEntity) => {
  const { ownerType, shares = [] } = entity;
  if (shares.length > 0) {
    const shareInfo = shares.find(
      ({ userId }) => userId === userInfoStore.getUserInfo("id")
    );
    if (shareInfo) {
      return true;
    }
  }
  if (ownerType === "ORG" || ownerType === "PUBLIC") {
    return true;
  }
  let isSharedWithYou = false;
  if (shares.length > 0) {
    const shareInfo = shares.find(
      ({ userId }) => userId === userInfoStore.getUserInfo("id")
    );
    if (shareInfo) {
      isSharedWithYou = true;
    }
  }
  return isSharedWithYou;
};

export function getRenderFlags({
  ids,
  infoProvider
}: RenderFlagsParams): RenderFlags {
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<BlockEntity>;
  const areAllEditable = entities.reduce(
    (memo, ent) => memo && !checkIsViewOnly(ent),
    true
  );
  let areAllShared = false;
  if (!areAllEditable) {
    areAllShared = entities.reduce(
      (memo, ent) => memo && checkIsSharedWithYou(ent),
      true
    );
  }
  return {
    entities,
    type: "oldBlocks",
    isNeedToRenderMenu:
      entities.length === 1 ||
      (entities.length > 0 && (areAllEditable || areAllShared))
  };
}

export default function Blocks({ entities }: { entities: Array<BlockEntity> }) {
  const optionsList = [];
  const { id, type, ownerType, libId, name, description, fileName } =
    entities[0];

  const deleteItem = () => {
    ModalActions.deleteObjects(type, entities);
  };

  const shareObject = () => {
    ModalActions.shareBlock(id, libId, type, name);
  };

  const updateItem = () => {
    if (type === BLOCK) {
      ModalActions.updateBlock(id, libId, name, description, fileName);
    } else {
      ModalActions.updateBlockLibrary(id, name, description);
    }
  };

  const downloadHandler = () => {
    if (type === BLOCK) {
      let downloadName = name;
      const extension = MainFunctions.getExtensionFromName(fileName);
      if (!downloadName.includes(extension))
        downloadName = `${downloadName}.${extension}`;
      ProcessActions.start(id, Processes.DOWNLOADING);
      BlocksActions.downloadBlock(id, libId, downloadName).finally(() => {
        ProcessActions.end(id);
      });
    }
  };

  const areAllEditable = entities.reduce(
    (memo, ent) => memo && !checkIsViewOnly(ent),
    true
  );

  const areAllOwned = entities.reduce(
    (memo, ent) => memo && checkIsOwner(ent),
    true
  );

  let deleteOrRemove;
  if (areAllEditable) {
    if (type === LIBRARY) {
      if (areAllOwned) {
        deleteOrRemove = DELETE;
      } else {
        deleteOrRemove = REMOVE;
      }
    } else {
      deleteOrRemove = DELETE;
    }
  } else {
    const areAllShared = entities.reduce(
      (memo, ent) => memo && checkIsSharedWithYou(ent),
      true
    );
    if (areAllShared) {
      deleteOrRemove = REMOVE;
    }
  }

  if (deleteOrRemove !== undefined) {
    if (deleteOrRemove === DELETE) {
      optionsList.push(
        <MenuItem
          id="contextMenuDeleteBlock"
          onClick={deleteItem}
          image={deleteSVG}
          caption="delete"
          key="contextMenuDeleteBlock"
          dataComponent="delete-block"
        />
      );
    } else {
      optionsList.push(
        <MenuItem
          id="contextMenuDeleteBlock"
          onClick={deleteItem}
          image={deleteSVG}
          caption="removeShare"
          key="contextMenuDeleteBlock"
          dataComponent="delete-block"
        />
      );
    }
  }

  if (UPDATE_ENABLED && entities.length === 1 && areAllEditable) {
    optionsList.push(
      <MenuItem
        id="contextMenuUpdateBlock"
        onClick={updateItem}
        image={editDrawingSVG}
        caption="update"
        key="contextMenuUpdateBlock"
        dataComponent="update-block"
      />
    );
  }
  if (entities.length === 1 && ownerType !== "PUBLIC") {
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
  if (entities.length === 1 && type === "block") {
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

  return optionsList;
}

Blocks.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      id: PropTypes.string,
      type: PropTypes.string
    })
  ).isRequired
};
