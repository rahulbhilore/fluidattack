import _ from "underscore";
import React from "react";
import InfoOutlinedIcon from "@material-ui/icons/InfoOutlined";
import * as ModalConstants from "../ModalConstants";
import ApplicationStore from "../../stores/ApplicationStore";

/**
 * DK: All icons should be placed in images/dialogs/icons
 * all of them should have white or grey fills + transparent background
 * Try not to use classes there to prevent conflicts between differently loaded svgs
 */

import settingsSVG from "../../assets/images/dialogs/icons/settings.svg";
import userSVG from "../../assets/images/dialogs/icons/user.svg";
import fluorineSVG from "../../assets/images/dialogs/icons/samplesInactive.svg";
import DSIcon from "../../assets/images/dialogs/icons/DSLogo.png";
import deleteSVG from "../../assets/images/dialogs/icons/delete.svg";
import connectionLostSVG from "../../assets/images/dialogs/icons/connectionOff.svg";
import newFileSVG from "../../assets/images/dialogs/icons/newFile.svg";
import createShortcutSVG from "../../assets/images/dialogs/icons/createShortcut.svg";
import newFolderSVG from "../../assets/images/dialogs/icons/newFolder.svg";
import unshareSVG from "../../assets/images/dialogs/icons/unshare.svg";
import eraseSVG from "../../assets/images/dialogs/icons/erase.svg";
import gdriveInactiveSVG from "../../assets/images/dialogs/icons/gdriveInactive.svg";
import moveSVG from "../../assets/images/dialogs/icons/move.svg";
import permissionsSVG from "../../assets/images/dialogs/icons/permissions.svg";
import restoreSVG from "../../assets/images/dialogs/icons/restore.svg";
import editDrawingSVG from "../../assets/images/dialogs/icons/editDrawing.svg";
import uploadSVG from "../../assets/images/dialogs/icons/upload.svg";
import webdavActiveSVG from "../../assets/images/dialogs/icons/webdavInactive.svg";
import nextcloudActiveSVG from "../../assets/images/dialogs/icons/nextcloudInactive.svg";
import passwordSVG from "../../assets/images/dialogs/icons/password.svg";
import versionSVG from "../../assets/images/dialogs/icons/versionControl.svg";
import openInCommanderSVG from "../../assets/images/dialogs/icons/openInCommander.svg";

const getProductIcon = () => {
  const product = ApplicationStore.getApplicationSetting("product");
  return product === "DraftSight" ? DSIcon : fluorineSVG;
};

const DialogIcons = {
  [ModalConstants.CHANGE_EDITOR]: settingsSVG,
  [ModalConstants.CHANGE_USER_OPTIONS]: userSVG,
  [ModalConstants.CHOOSE_OBJECT]: getProductIcon,
  [ModalConstants.CONFIRM_DELETE]: deleteSVG,
  [ModalConstants.CONNECTION_LOST]: connectionLostSVG,
  [ModalConstants.CREATE_FILE]: newFileSVG,
  [ModalConstants.CREATE_FOLDER]: newFolderSVG,
  [ModalConstants.CREATE_RESOURCE_FOLDER]: newFolderSVG,
  [ModalConstants.DELETE_OBJECTS]: deleteSVG,
  [ModalConstants.DELETE_USER]: userSVG,
  [ModalConstants.DELINK_STORAGE]: unshareSVG,
  [ModalConstants.ERASE_OBJECTS]: eraseSVG,
  [ModalConstants.CONFIRM_ERASE_FILES]: eraseSVG,
  [ModalConstants.FILE_SESSION_EXPIRED]: connectionLostSVG,
  [ModalConstants.OPEN_GDRIVE_FILE_TEMP]: gdriveInactiveSVG,
  [ModalConstants.GDRIVE_CONNECT_ACCOUNT]: gdriveInactiveSVG,
  [ModalConstants.MOVE_OBJECTS]: moveSVG,
  [ModalConstants.PERMISSIONS]: permissionsSVG,
  [ModalConstants.RESTORE_DUPLICATES]: restoreSVG,
  [ModalConstants.REMOVE_PUBLIC_LINK_CONFIRMATION]: permissionsSVG,
  [ModalConstants.SAVE_AS]: getProductIcon,
  [ModalConstants.CREATE_SHORTCUT]: createShortcutSVG,
  [ModalConstants.UPGRADE_FILE_SESSION]: editDrawingSVG,
  [ModalConstants.UPLOAD_TEMPLATE]: uploadSVG,
  [ModalConstants.WEBDAV_CONNECT]: webdavActiveSVG,
  [ModalConstants.NEXTCLOUD_CONNECT]: nextcloudActiveSVG,
  [ModalConstants.SEND_FEEDBACK]: {
    img: [DSIcon],
    text: "Beta"
  },
  [ModalConstants.LINK_PASSWORD_REQUIRED]: passwordSVG,
  [ModalConstants.EXPORT_FILE]: getProductIcon,
  [ModalConstants.VERSION_CONTROL]: versionSVG,
  [ModalConstants.SAVE_VERSION_AS]: getProductIcon,
  [ModalConstants.OPEN_IN_COMMANDER]: openInCommanderSVG,
  [ModalConstants.SHOW_ABOUT]: <InfoOutlinedIcon />
};

const getIcon = modalType => {
  if (Object.keys(DialogIcons).includes(modalType)) {
    const iconGetter = DialogIcons[modalType];
    if (_.isFunction(iconGetter)) {
      return iconGetter();
    }
    return iconGetter;
  }
  return null;
};

export default getIcon;
