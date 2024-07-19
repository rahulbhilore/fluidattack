import React, { useEffect, useState } from "react";
import { useIntl } from "react-intl";
import ModalActions from "../../../../actions/ModalActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListStore, {
  CURRENT_FILE_INFO_UPDATED,
  DRAWING_RELOAD
} from "../../../../stores/FilesListStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import IconButton from "../IconButton";

import editDrawingSVG from "../../../../assets/images/editDrawing.svg";
import conflictingReasons from "../../../../constants/appConstants/ConflictingFileReasons";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";

type PropType = {
  conflictingReason?: string | null;
  isForMobileHeader?: boolean;
  onMenuItemClick?: () => void;
  viewFlag?: boolean;
};

export default function UpgradeSessionButton({
  conflictingReason = null,
  isForMobileHeader = false,
  onMenuItemClick = () => null,
  viewFlag = false
}: PropType) {
  const [initialViewOnly, setInitialViewOnly] = useState(
    FilesListStore.getCurrentFile().initialViewOnly
  );
  const [_, setCounter] = useState(0);
  const { formatMessage } = useIntl();

  const forceUpdate = () => {
    setCounter(prev => prev + 1);
  };

  const updateInitialViewOnly = () => {
    setInitialViewOnly(FilesListStore.getCurrentFile().initialViewOnly);
  };

  const canIRequestEditPermission =
    viewFlag === true &&
    !UserInfoStore.isFreeAccount() &&
    UserInfoStore.getUserInfo("options").editor === true &&
    initialViewOnly === false &&
    location.search.indexOf("versionId") === -1;

  const upgradeFunction = (e: React.UIEvent) => {
    e.preventDefault();
    ModalActions.upgradeFileSession(FilesListStore.getCurrentFile()._id);
    if (isForMobileHeader) onMenuItemClick();
  };
  const { onKeyDown } = MainFunctions.getA11yHandler(upgradeFunction);
  const isMobileDevice = MainFunctions.isMobileDevice();
  const isDisabled =
    conflictingReason !== null &&
    (conflictingReason || "").length > 0 &&
    conflictingReason !== conflictingReasons.VERSIONS_CONFLICTED;

  useEffect(() => {
    FilesListStore.addEventListener(DRAWING_RELOAD, forceUpdate);
    FilesListStore.addEventListener(
      CURRENT_FILE_INFO_UPDATED,
      updateInitialViewOnly
    );
    return () => {
      FilesListStore.removeEventListener(DRAWING_RELOAD, forceUpdate);
      FilesListStore.removeEventListener(
        CURRENT_FILE_INFO_UPDATED,
        updateInitialViewOnly
      );
    };
  }, []);

  if (!canIRequestEditPermission) return null;
  if (isMobileDevice) return null;
  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        onClick={upgradeFunction}
        icon={editDrawingSVG}
        caption={formatMessage({ id: "switchToEditMode" })}
        dataComponent="upgrade_session_menu"
        isDisabled={isDisabled}
      />
    );
  return (
    <IconButton
      dataComponent="upgrade_session"
      onClick={upgradeFunction}
      onKeyDown={onKeyDown}
      caption={formatMessage({
        id: isDisabled
          ? "cannotTakeEditingAsConflictingFileWasCreated"
          : "switchToEditMode"
      })}
      icon={editDrawingSVG}
      isDisabled={isDisabled}
    />
  );
}
