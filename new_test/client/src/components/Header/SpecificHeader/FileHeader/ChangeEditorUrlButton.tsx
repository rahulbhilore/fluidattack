import React, { SyntheticEvent } from "react";
import { FormattedMessage } from "react-intl";
import ModalActions from "../../../../actions/ModalActions";
import FilesListStore from "../../../../stores/FilesListStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import IconButton from "../IconButton";

import settingsSVG from "../../../../assets/images/Settings.svg";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";

export default function ChangeEditorUrlButton({
  isForMobileHeader = false,
  onMenuItemClick = () => null
}: {
  isForMobileHeader?: boolean;
  onMenuItemClick?: () => void;
}) {
  // allowURLChange
  const userOptions = UserInfoStore.getUserInfo("options");
  if (
    !UserInfoStore.getUserInfo("isAdmin") &&
    (!Object.prototype.hasOwnProperty.call(userOptions, "allowURLChange") ||
      userOptions.allowURLChange === false)
  )
    return null;

  const openChangeEditorDialog = (e: SyntheticEvent) => {
    e.preventDefault();
    ModalActions.changeEditor(FilesListStore.getCurrentFile()._id);
    if (isForMobileHeader) onMenuItemClick();
  };

  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        onClick={openChangeEditorDialog}
        icon={settingsSVG}
        caption={<FormattedMessage id="changeEditor" />}
        dataComponent="change_editor_url_menu"
      />
    );

  return (
    <IconButton
      dataComponent="change_editor_url"
      onClick={openChangeEditorDialog}
      caption={<FormattedMessage id="changeEditor" />}
      icon={settingsSVG}
    />
  );
}
