import React, { SyntheticEvent } from "react";
import { FormattedMessage } from "react-intl";
import commentsSVG from "../../../../assets/images/header/comment_title.svg";
import ApplicationStore from "../../../../stores/ApplicationStore";
import UserInfoStore from "../../../../stores/UserInfoStore";
import Storage from "../../../../utils/Storage";
import DrawingMenuItem from "../../DrawingMenu/DrawingMenuItem";
import IconButton from "../IconButton";

type PropType = {
  isForMobileHeader?: boolean;
  notificationBadge?: number;
  onCommentButtonClick?: (e: SyntheticEvent) => void;
  onMenuItemClick?: () => void;
};

export default function CommentsButton({
  isForMobileHeader = false,
  notificationBadge = 0,
  onCommentButtonClick = () => null,
  onMenuItemClick = () => null
}: PropType) {
  const isLoggedIn = !!Storage.store("sessionId");
  const features =
    ApplicationStore.getApplicationSetting("featuresEnabled") || {};
  const isVersion = location.search.indexOf("versionId") > -1;

  const isNeedToRender =
    isLoggedIn &&
    (features.commentsAll === true ||
      (features.commentsAdmin === true &&
        UserInfoStore.getUserInfo("isAdmin") === true)) &&
    !isVersion;

  const iconMenuItemClick = (e: SyntheticEvent) => {
    onCommentButtonClick(e);
    onMenuItemClick();
  };

  if (!isNeedToRender) return null;
  if (isForMobileHeader)
    return (
      <DrawingMenuItem
        caption={<FormattedMessage id="comments" />}
        dataComponent="comments_menu"
        icon={commentsSVG}
        onClick={iconMenuItemClick}
      />
    );

  return (
    <IconButton
      badge={notificationBadge}
      caption={<FormattedMessage id="comments" />}
      dataComponent="comments"
      icon={commentsSVG}
      onClick={onCommentButtonClick}
    />
  );
}
