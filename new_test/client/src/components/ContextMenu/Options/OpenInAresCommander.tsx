import React, { useCallback, useState } from "react";
import PropTypes from "prop-types";
import MenuItem from "../MenuItem";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import FilesListActions from "../../../actions/FilesListActions";
import MainFunctions, { APPLE_DEVICE } from "../../../libraries/MainFunctions";
import openInCommanderSVG from "../../../assets/images/context/openInCommander.svg";
import ModalActions from "../../../actions/ModalActions";
import ContextMenuActions from "../../../actions/ContextMenuActions";
import { FileEntity } from "../Files";

export default function OpenInAresCommander({
  fileInfo
}: {
  fileInfo: FileEntity;
}) {
  const [isLoading, setLoading] = useState(false);
  const isMobile = MainFunctions.isMobileDevice();

  const openInCommander = useCallback(
    (id: string, parent: string, name: string) => {
      FilesListActions.openFileInCommander(id, parent, { name })
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
        })
        .finally(() => {
          if (isLoading) {
            setLoading(false);
          }
        });
    },
    []
  );

  const openInCommanderHandler = useCallback(() => {
    if (isLoading) return;

    if (fileInfo.isShortcut) {
      setLoading(true);

      FilesListActions.getObjectInfo(
        fileInfo.shortcutInfo?.targetId,
        fileInfo.shortcutInfo?.type,
        {}
      )
        .then(targetObjectInfo => {
          if (targetObjectInfo.isDeleted) {
            SnackbarUtils.alertError({
              id: "getDeletedShortcutTargetInfoError"
            });
          } else {
            openInCommander(
              targetObjectInfo._id,
              targetObjectInfo.folderId,
              targetObjectInfo.name || targetObjectInfo.filename
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
    } else {
      openInCommander(fileInfo.id, fileInfo.parent, fileInfo.name);
    }
  }, [fileInfo, openInCommander, isLoading]);

  return (
    <MenuItem
      autoClose={!fileInfo.isShortcut}
      id="contextMenuOpenInAres"
      onClick={openInCommanderHandler}
      image={openInCommanderSVG}
      caption={!isMobile ? "openInCommander" : "openInTouch"}
      key="contextMenuOpenInAres"
      dataComponent="open-in-ares"
      isLoading={isLoading}
    />
  );
}

OpenInAresCommander.propTypes = {
  fileInfo: PropTypes.shape({
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
  }).isRequired
};
