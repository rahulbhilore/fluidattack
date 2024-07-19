import React from "react";
import { FormattedMessage } from "react-intl";
import ModalActions from "../../../../actions/ModalActions";
import IconButton from "../IconButton";

import versionSVG from "../../../../assets/images/versionControl/version-control.svg";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";

type PropType = {
  _id: string;
  filename: string;
  folderId?: string;
  isForMobileHeader?: boolean;
  onMenuItemClick?: () => void;
  viewOnly?: boolean;
};

export default function VersionControlButton({
  _id,
  filename,
  folderId = "",
  isForMobileHeader = false,
  onMenuItemClick = () => null,
  viewOnly = true
}: PropType) {
  const openVersionControl = () => {
    ModalActions.versionControl(_id, folderId, filename);
    if (isForMobileHeader) onMenuItemClick();
  };

  if (viewOnly) return null;
  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        onClick={openVersionControl}
        icon={versionSVG}
        caption={<FormattedMessage id="manageVersions" />}
        dataComponent="version_control_menu"
      />
    );

  return (
    <IconButton
      dataComponent="version_control"
      onClick={openVersionControl}
      caption={<FormattedMessage id="manageVersions" />}
      icon={versionSVG}
    />
  );
}
