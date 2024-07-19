import React from "react";
import PropTypes from "prop-types";
import MenuItem from "./MenuItem";
import promoteSVG from "../../assets/images/versionControl/promote.svg";
import downloadSVG from "../../assets/images/versionControl/download.svg";
import removeSVG from "../../assets/images/versionControl/remove.svg";
import saveAsSVG from "../../assets/images/versionControl/save-as.svg";
import openSVG from "../../assets/images/versionControl/open.svg";
import VersionControlActions from "../../actions/VersionControlActions";
import ApplicationActions from "../../actions/ApplicationActions";
import ModalActions from "../../actions/ModalActions";
import VersionControlStore from "../../stores/VersionControlStore";
import MainFunctions from "../../libraries/MainFunctions";
import FilesListActions from "../../actions/FilesListActions";
import FilesListStore from "../../stores/FilesListStore";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";

export type VersionEntity = {
  id: string;
  fileId: string;
  filenameInfo: {
    filename: string;
    realName: string;
  };
  folderId: string;
};

export function getRenderFlags({
  customObjectInfo,
  ids,
  infoProvider
}: RenderFlagsParams & { customObjectInfo?: VersionEntity }): RenderFlags {
  if (customObjectInfo && Object.keys(customObjectInfo).length > 0) {
    return {
      entities: [customObjectInfo],
      type: "versions",
      isNeedToRenderMenu: true
    };
  }
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<VersionEntity>;
  return {
    entities,
    type: "versions",
    isNeedToRenderMenu: entities.length === 1
  };
}

export default function Versions({
  entities
}: {
  entities: Array<VersionEntity>;
}) {
  const optionsList = [];

  // const selectedVersionsNumber = entities.length;

  // if (selectedVersionsNumber === 2) {
  //   const compareVersions = () => {
  //     ModalActions.compareDrawings(
  //       {
  //         id: entities[0].fileId,
  //         versionId: entities[0].id,
  //         thumbnail: entities[0].thumbnail,
  //         ext:
  //           MainFunctions.getExtensionFromName(
  //             entities[0]?.filenameInfo?.filename
  //           ) || "dwg"
  //       },
  //       {
  //         id: entities[1].fileId,
  //         versionId: entities[1].id,
  //         thumbnail: entities[1].thumbnail,
  //         ext:
  //           MainFunctions.getExtensionFromName(
  //             entities[1]?.filenameInfo?.filename
  //           ) || "dwg"
  //       }
  //     );
  //   };
  //   return [
  //     <MenuItem
  //       id="contextMenuCompareVersions"
  //       onClick={compareVersions}
  //       image={promoteSVG}
  //       caption="compare"
  //     />
  //   ];
  // }

  const { id: versionId, fileId, filenameInfo, folderId } = entities[0];
  const { filename: versionName, realName } = filenameInfo;

  const isLastVersion = () => {
    const latestVersion = VersionControlStore.getLatestVersion();

    if (!latestVersion) return false;

    return versionId === latestVersion.id;
  };

  const openVersion = () => {
    if (isLastVersion()) {
      // https://graebert.atlassian.net/browse/XENON-50311
      // if URL is the same - version won't be opened
      // so emit event to reload with the same rights.
      if (location.pathname === `/file/${fileId}`) {
        FilesListActions.reloadDrawing(
          FilesListStore.getCurrentFile().viewFlag,
          true
        );
      } else {
        ApplicationActions.changePage(`/file/${fileId}`);
      }
    } else
      ApplicationActions.changePage(`/file/${fileId}?versionId=${versionId}`);
    ModalActions.hide();
  };

  const promoteVersion = () => {
    VersionControlActions.promote(fileId, versionId);
  };

  const downloadVersion = () => {
    const { size } = VersionControlStore.getVersionInfo(versionId);
    VersionControlActions.downloadVersionViaStream(fileId, versionId, size)
      .then(response => {
        const clearFileName = realName.replace(/\.[^/.]+$/, "");
        const matches = realName.match(/[^\\]*\.(\w+)$/);
        if (!matches || matches.length < 2)
          throw new Error("Invalid file name");
        const extension = matches[1];
        MainFunctions.downloadBlobAsFile(
          response,
          `${clearFileName}_${versionName}.${extension}`
        );
      })
      .catch(err => {
        SnackbarUtils.alertError(err.message);
      });
  };

  const deleteVersion = () => {
    VersionControlActions.remove(fileId, versionId);
  };

  const saveAsVersion = () => {
    ModalActions.saveVersionAs(fileId, versionId, folderId, realName);
  };

  const versionInfo = VersionControlStore.getVersionInfo(versionId);
  const { permissions } = versionInfo;
  const { canDelete, canPromote, isDownloadable } = permissions;
  if (canPromote) {
    optionsList.push(
      <MenuItem
        id="contextMenuPromoteVersion"
        onClick={promoteVersion}
        image={promoteSVG}
        caption="promote"
        key="contextMenuPromoteVersion"
        dataComponent="promote-version"
      />
    );
  }
  if (isDownloadable) {
    optionsList.push(
      <MenuItem
        id="contextMenuDownloadVersion"
        onClick={downloadVersion}
        image={downloadSVG}
        caption="download"
        key="contextMenuDownloadVersion"
        dataComponent="download-version"
      />
    );
    optionsList.push(
      <MenuItem
        id="contextMenuOpenVersion"
        onClick={openVersion}
        image={openSVG}
        caption="open"
        key="contextMenuOpenVersion"
        dataComponent="open-version"
      />
    );
    optionsList.push(
      <MenuItem
        id="contextMenuSaveNewFileFromVersion"
        onClick={saveAsVersion}
        image={saveAsSVG}
        caption="saveNewFile"
        key="contextMenuSaveNewFileFromVersion"
        dataComponent="save-as-version"
      />
    );
  }
  if (canDelete) {
    optionsList.push(
      <MenuItem
        id="contextMenuDeleteVersion"
        onClick={deleteVersion}
        image={removeSVG}
        caption="delete"
        key="contextMenuDeleteVersion"
        dataComponent="delete-version"
      />
    );
  }

  return optionsList;
}

Versions.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      fileId: PropTypes.string,
      id: PropTypes.string,
      thumbnail: PropTypes.string,
      filenameInfo: PropTypes.shape({
        filename: PropTypes.string,
        realName: PropTypes.string
      }),
      folderId: PropTypes.string
    })
  ).isRequired
};
