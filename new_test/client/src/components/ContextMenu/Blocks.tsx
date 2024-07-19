import React from "react";
import MenuItem from "./MenuItem";
import deleteSVG from "../../assets/images/context/delete.svg";
import downloadSVG from "../../assets/images/context/download.svg";

import ModalActions from "../../actions/ModalActions";
import MainFunctions from "../../libraries/MainFunctions";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import blocksStore from "../../stores/resources/blocks/BlocksStore";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";

export type BlockEntity = {
  _id: string;
  name: string;
};

export function getRenderFlags({
  ids,
  infoProvider
}: RenderFlagsParams): RenderFlags {
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<BlockEntity>;

  return {
    entities,
    type: "blocks",
    isNeedToRenderMenu: true
  };
}

export default function Blocks({ entities }: { entities: Array<BlockEntity> }) {
  const optionsList = [];

  const deleteItem = () => {
    ModalActions.deleteObjects("blocks", entities);
  };

  const downloadHandler = () => {
    entities.forEach(entity => {
      const { name, _id } = entity;

      blocksStore
        .downloadBlock(_id)
        .then((response: { data: ArrayBuffer }) => {
          if (response.data instanceof ArrayBuffer) {
            MainFunctions.downloadBlobAsFile(
              new Blob([new Uint8Array(response.data)]),
              name
            );
          } else {
            MainFunctions.downloadBlobAsFile(response.data, name);
          }
        })
        .catch(() => {
          SnackbarUtils.alertError({ id: "error" });
        });
    });
  };

  optionsList.push(
    <MenuItem
      id="contextMenuDownloadBlock"
      onClick={downloadHandler}
      image={downloadSVG}
      caption="download"
      dataComponent="download-font"
    />
  );
  optionsList.push(
    <MenuItem
      id="contextMenuDeleteBlock"
      onClick={deleteItem}
      image={deleteSVG}
      caption="delete"
      dataComponent="delete-font"
    />
  );

  return optionsList;
}
