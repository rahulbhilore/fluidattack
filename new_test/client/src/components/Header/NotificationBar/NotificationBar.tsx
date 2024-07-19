import ClearIcon from "@mui/icons-material/Clear";
import { Box, IconButton, Link, styled, Portal } from "@mui/material";
import React, {
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useState
} from "react";
import { FormattedMessage } from "react-intl";
import ResizeObserver from "resize-observer-polyfill";
import SmartTableActions from "../../../actions/SmartTableActions";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import UserInfoStore, {
  NOTIFICATION_UPDATE
} from "../../../stores/UserInfoStore";
import Storage from "../../../utils/Storage";
import Tracker from "../../../utils/Tracker";

export const NOTIFICATION_SIZE = "NOTIFICATION_SIZE";

const StyledNotificationBar = styled(Box)(({ theme }) => ({
  minHeight: 36,
  width: "100%",
  textAlign: "center",
  backgroundColor: theme.palette.OBI,
  color: theme.palette.LIGHT,
  lineHeight: "36px",
  fontSize: 12,
  position: "absolute",
  top: 50,
  zIndex: 2,
  "& a, & a:hover, & a:focus": {
    fontWeight: "bold",
    color: theme.palette.LIGHT,
    textDecoration: "underline",
    textTransform: "uppercase"
  }
}));

const CrossButton = styled(IconButton)(({ theme }) => ({
  position: "absolute",
  userSelect: "none",
  padding: 0,
  width: 20,
  height: 20,
  top: 1,
  right: 5,
  color: theme.palette.LIGHT,
  marginTop: 5,
  "&:hover": {
    color: theme.palette.KYLO
  }
}));

const BuyLink = (msg: ReactNode) => {
  const { buyURL } = ApplicationStore.getApplicationSetting("customization");
  const buyGAEvent = () =>
    Tracker.sendGAEvent("Purchase", "ARES Kudo", "Source:KudoInApp");
  return (
    <Link
      href={buyURL}
      target="_blank"
      rel="noopener noreferrer"
      onClick={buyGAEvent}
    >
      {msg}
    </Link>
  );
};

const DaysLeftCaption = () => {
  const daysLeft = UserInfoStore.getDaysLeftAmount()?.toString?.() ?? "0";
  switch (daysLeft) {
    case "0":
      return (
        <FormattedMessage
          id="lastDayInYourTrial"
          values={{ strong: IntlTagValues.strong }}
        />
      );
    case "1":
      return (
        <FormattedMessage
          id="dayLeftInYourTrial"
          values={{ strong: IntlTagValues.strong }}
        />
      );
    default:
      return (
        <FormattedMessage
          id="daysLeftInYourTrial"
          values={{ daysLeft, strong: IntlTagValues.strong }}
        />
      );
  }
};

export default function NotificationBar() {
  const [barHeight, setBarHeight] = useState<number>(0);
  const isIndependentLogin =
    ApplicationStore.getApplicationSetting("featuresEnabled").independentLogin;
  const isFreeAccount = UserInfoStore.isFreeAccount();
  const product = ApplicationStore.getApplicationSetting("product") || "";
  const isNotificationToShow = UserInfoStore.getUserInfo(
    "isNotificationToShow"
  );
  const [, forceUpdate] = useReducer(x => x + 1, 0);

  const observer = useMemo(
    () =>
      new ResizeObserver(([entry]: ResizeObserverEntry[]) => {
        const newHeight = entry.target.clientHeight;
        if (newHeight !== barHeight) {
          setBarHeight(newHeight);
          const notificationResizeEvent = new CustomEvent(NOTIFICATION_SIZE, {
            detail: newHeight
          });
          document.dispatchEvent(notificationResizeEvent);
        }
      }),
    [barHeight]
  );

  const onNotificationUpdate = () => {
    forceUpdate();
    SmartTableActions.recalculateDimensions();
  };

  const hideTrialBar = useCallback(() => {
    Storage.store("trialBarHidden", "true");
    UserInfoStore.updateNotificationStatus();
    setBarHeight(0);
    const notificationResizeEvent = new CustomEvent(NOTIFICATION_SIZE, {
      detail: 0
    });
    document.dispatchEvent(notificationResizeEvent);
  }, []);

  const { onKeyDown } = MainFunctions.getA11yHandler(hideTrialBar);

  useEffect(() => {
    UserInfoStore.addChangeListener(NOTIFICATION_UPDATE, onNotificationUpdate);
    const notificatinBar = document.getElementById("notificationBarContainer");
    if (notificatinBar) {
      observer.observe(notificatinBar);

      const _barHeight = notificatinBar.clientHeight;
      setBarHeight(_barHeight);

      const notificationResizeEvent = new CustomEvent(NOTIFICATION_SIZE, {
        detail: _barHeight
      });
      document.dispatchEvent(notificationResizeEvent);
    }
    return () => {
      UserInfoStore.removeChangeListener(
        NOTIFICATION_UPDATE,
        onNotificationUpdate
      );
    };
  }, []);

  if (isIndependentLogin || UserInfoStore.isPerpetualAccount()) return null;
  if (!isNotificationToShow || !product.toLowerCase().includes("kudo"))
    return null;

  return (
    <Portal>
      <StyledNotificationBar id="notificationBarContainer">
        {isFreeAccount ? (
          <>
            <FormattedMessage
              id="trialExpiredAccountSwitchedToFreeSharing"
              values={{ strong: IntlTagValues.strong }}
            />{" "}
            <FormattedMessage
              id="buyLicenseForFullAccess"
              values={{
                buy: BuyLink
              }}
            />
          </>
        ) : (
          <>
            <DaysLeftCaption />{" "}
            <FormattedMessage
              id="buyLicenseNow"
              values={{
                buy: BuyLink
              }}
            />
          </>
        )}
        {!isFreeAccount && (
          <CrossButton
            onClick={hideTrialBar}
            onKeyDown={onKeyDown}
            tabIndex={0}
          >
            <ClearIcon />
          </CrossButton>
        )}
      </StyledNotificationBar>
    </Portal>
  );
}
