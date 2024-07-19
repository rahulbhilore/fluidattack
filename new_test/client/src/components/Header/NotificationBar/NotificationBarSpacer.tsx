import React, { useEffect, useState } from "react";
import MainFunctions from "../../../libraries/MainFunctions";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import { NOTIFICATION_SIZE } from "./NotificationBar";

type CalculateSizeParams = {
  detail: number;
};

export default function NotificationBarSpacer() {
  const [isVisible, setVisible] = useState(false);
  const [height, setHeight] = useState(0);

  const calculateSize = (params?: CalculateSizeParams) => {
    const { detail } = params ?? ({} as CalculateSizeParams);
    const isNotificationToShow = userInfoStore.getUserInfo(
      "isNotificationToShow"
    );
    const isUserInfoLoaded =
      userInfoStore.getUserInfo("isLoggedIn") &&
      userInfoStore.getUserInfo("isFullInfo");
    if (
      isUserInfoLoaded &&
      MainFunctions.detectPageType() === "files" &&
      (isVisible !== isNotificationToShow || height !== detail)
    ) {
      setVisible(isNotificationToShow);
      setHeight(detail);
    }
  };

  const onUserInfoUpdate = () => {
    // initial size
    const container = document.getElementById("notificationBarContainer");
    const initialHeight = container ? Number(container.clientHeight) : 0;
    const isNotificationToShow = userInfoStore.getUserInfo(
      "isNotificationToShow"
    );
    const isUserInfoLoaded =
      userInfoStore.getUserInfo("isLoggedIn") &&
      userInfoStore.getUserInfo("isFullInfo");
    if (
      isUserInfoLoaded &&
      MainFunctions.detectPageType() === "files" &&
      isNotificationToShow &&
      initialHeight !== 0
    ) {
      calculateSize({ detail: initialHeight });
    }
  };

  const onNotificationResize = () => {
    calculateSize();
  };

  useEffect(() => {
    userInfoStore.addChangeListener(INFO_UPDATE, onUserInfoUpdate);
    document.addEventListener(NOTIFICATION_SIZE, onNotificationResize);
    return () => {
      userInfoStore.removeChangeListener(INFO_UPDATE, onUserInfoUpdate);
      document.removeEventListener(NOTIFICATION_SIZE, onNotificationResize);
    };
  }, []);

  if (!isVisible || height === 0) return null;
  return (
    <div
      className="notification-bar-spacer"
      style={{ height, width: "100%" }}
    />
  );
}
