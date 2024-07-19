import React from "react";
import { FormattedMessage } from "react-intl";
import XenonConnectionActions from "../../../../actions/XenonConnectionActions";
import blockLibrarySVG from "../../../../assets/block_library.svg";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";
import IconButton from "../IconButton";

type PropType = {
  isForMobileHeader?: boolean;
  onMenuItemClick?: () => void;
  viewFlag: boolean;
};

export default function BlockLibraryButton({
  isForMobileHeader = false,
  onMenuItemClick = () => null,
  viewFlag
}: PropType) {
  const openBlockLibrary = () => {
    XenonConnectionActions.postMessage({ messageName: "blockLibrary" });
    if (isForMobileHeader) onMenuItemClick();
  };

  if (viewFlag) return null;
  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        onClick={openBlockLibrary}
        icon={blockLibrarySVG}
        caption={<FormattedMessage id="blockLibrary" />}
        dataComponent="block_library_menu"
      />
    );

  return (
    <IconButton
      dataComponent="block_library"
      onClick={openBlockLibrary}
      caption={<FormattedMessage id="blockLibrary" />}
      icon={blockLibrarySVG}
    />
  );
}
