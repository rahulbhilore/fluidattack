import React from "react";
import MenuItem from "./MenuItem";
import downloadSVG from "../../assets/images/context/download.svg";
import deleteSVG from "../../assets/images/context/delete.svg";
import MainFunctions from "../../libraries/MainFunctions";
import ModalActions from "../../actions/ModalActions";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";
import { FontsStore } from "../../stores/resources/fonts/FontsStore";

export type FontEntity = {
  _id: string;
  name: string;
};

export function getRenderFlags({
  customObjectInfo,
  ids,
  infoProvider
}: RenderFlagsParams & { customObjectInfo?: FontEntity }): RenderFlags {
  if (customObjectInfo && Object.keys(customObjectInfo).length > 0) {
    return {
      entities: [customObjectInfo],
      type: "fonts",
      isNeedToRenderMenu: true
    };
  }
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<FontEntity>;
  return {
    entities,
    type: "fonts",
    isNeedToRenderMenu: true
  };
}

export default function Fonts({ entities }: { entities: Array<FontEntity> }) {
  const optionsList = [];

  const downloadFont = () => {
    entities.forEach(entity => {
      const { name, _id } = entity;
      const activeStore = FontsStore.activeStorage;

      if (!activeStore) return;

      activeStore
        .fetchDownloadFont(_id)
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

  const deleteFont = () => {
    ModalActions.deleteObjects("fonts", entities);
  };

  optionsList.push(
    <MenuItem
      id="contextMenuDownloadFont"
      onClick={downloadFont}
      image={downloadSVG}
      caption="download"
      dataComponent="download-font"
    />
  );
  optionsList.push(
    <MenuItem
      id="contextMenuDeleteFont"
      onClick={deleteFont}
      image={deleteSVG}
      caption="delete"
      dataComponent="delete-font"
    />
  );

  return optionsList;
}
