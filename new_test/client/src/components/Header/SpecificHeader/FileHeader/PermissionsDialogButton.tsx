import React, { SyntheticEvent, useCallback } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import ModalActions from "../../../../actions/ModalActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import IconButton from "../IconButton";

import permissionsSVG from "../../../../assets/images/context/permissionsWhite.svg";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";

type PropType = {
  fileId: string;
  isForMobileHeader?: boolean;
  name?: string | null;
  onMenuItemClick?: () => void;
};

export default function PermissionsDialogButton({
  fileId,
  isForMobileHeader = false,
  name = null,
  onMenuItemClick = () => null
}: PropType) {
  const { formatMessage } = useIntl();
  const { storageType } = MainFunctions.parseObjectId(fileId);
  const currentStorage = storageType
    ? MainFunctions.storageCodeToServiceName(storageType)
    : UserInfoStore.getUserInfo("storage").type;

  const isSharingAllowed =
    UserInfoStore.isFeatureAllowedByStorage(currentStorage, "share", "file") ||
    UserInfoStore.isFeatureAllowedByStorage(
      currentStorage,
      "share",
      "publicLink"
    );

  const openPermissionsDialog = useCallback(
    (e: SyntheticEvent) => {
      e.preventDefault();
      ModalActions.shareManagement(fileId, name);
      if (isForMobileHeader) onMenuItemClick();
    },
    [isForMobileHeader, fileId, name]
  );

  if (!isSharingAllowed) return null;
  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        caption={<FormattedMessage id="shareFile" />}
        dataComponent="manage_permissions_menu"
        icon={permissionsSVG}
        onClick={openPermissionsDialog}
      />
    );

  return (
    <IconButton
      caption={formatMessage({ id: "shareFile" })}
      dataComponent="manage_permissions"
      icon={permissionsSVG}
      onClick={openPermissionsDialog}
    />
  );
}
