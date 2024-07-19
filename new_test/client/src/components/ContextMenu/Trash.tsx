import React from "react";
import MenuItem from "./MenuItem";
import ModalActions from "../../actions/ModalActions";
import eraseSVG from "../../assets/images/context/erase.svg";
import restoreSVG from "../../assets/images/context/restore.svg";
import FilesListStore from "../../stores/FilesListStore";
import MainFunctions from "../../libraries/MainFunctions";
import { RenderFlagsParams, TrashRenderFlags } from "./ContextMenu";

export function getRenderFlags({
  ids,
  infoProvider
}: RenderFlagsParams): TrashRenderFlags {
  const { storageType: currentStorage } = FilesListStore.findCurrentStorage();
  const storage = MainFunctions.storageCodeToServiceName(currentStorage);
  if (ids.length < 1) {
    return {
      isNeedToRenderMenu: false,
      entities: [],
      type: "trash",
      storage
    };
  }
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<Record<string, unknown>>;
  // const isAnyDeleted = entities.find(({ isDeleted }) => isDeleted);
  return {
    isNeedToRenderMenu: true,
    entities,
    type: "trash",
    storage
  };
}

type Props = {
  entities: Array<Record<string, unknown>>;
  isEraseAvailable: boolean;
  isRestoreAvailable: boolean;
};

export default function Trash({
  entities,
  isEraseAvailable,
  isRestoreAvailable
}: Props) {
  const optionsList = [];

  const erase = () => {
    ModalActions.eraseObjects(entities);
  };

  const restore = () => {
    ModalActions.restoreDuplicates(entities);
  };

  if (isEraseAvailable)
    optionsList.push(
      <MenuItem
        id="contextMenuErase"
        onClick={erase}
        image={eraseSVG}
        caption="erase"
        key="contextMenuErase"
        dataComponent="erase"
      />
    );
  if (isRestoreAvailable)
    optionsList.push(
      <MenuItem
        id="contextMenuRestore"
        onClick={restore}
        image={restoreSVG}
        caption="restore"
        key="contextMenuRestore"
        dataComponent="restore"
      />
    );
  return optionsList;
}
