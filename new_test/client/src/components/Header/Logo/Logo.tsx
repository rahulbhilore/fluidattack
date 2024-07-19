import { Box, Grid } from "@mui/material";
import { makeStyles } from "@mui/styles";
import clsx from "clsx";
import React, {
  SyntheticEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from "react";
import kudoLogo from "../../../assets/images/kudo-logo-small.svg";
import kudoLogoMobile from "../../../assets/images/storages/samples-active.svg";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import Tracker from "../../../utils/Tracker";
import DrawingMenu from "../DrawingMenu/DrawingMenu";
import ToggleButton from "./ToggleButton";
import ApplicationActions from "../../../actions/ApplicationActions";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";

const useStyles = makeStyles(() => ({
  logoButton: {
    cursor: "pointer",
    height: "100%",
    background: "none",
    border: "none",
    outline: "none",
    textAlign: "left",
    padding: 0,
    margin: 0
  },
  logoButtonMobile: {
    width: 35
  }
}));

type Permissions = {
  canManagePermissions: boolean;
  canViewPermissions: boolean;
  canViewPublicLink: boolean;
  canManagePublicLink: boolean;
};

type CurrentFile = {
  _id: string;
  folderId: string;
  isOwner: boolean;
  name: string;
  permissions: Permissions;
  shared: boolean;
  viewFlag: boolean;
  viewOnly: boolean;
};

type PropType = {
  changePageType: (e: React.UIEvent) => void;
  currentFile: CurrentFile;
  isLoggedIn: boolean;
  isMobile: boolean;
  notificationBadge: number;
  onCommentButtonClick: (e: SyntheticEvent) => void;
};

export default function Logo({
  changePageType,
  currentFile,
  isLoggedIn,
  isMobile,
  notificationBadge,
  onCommentButtonClick
}: PropType) {
  const isDrawingPage = location.pathname.indexOf(`/file/`) !== -1;
  const logoClickHandler = useCallback(
    (event: React.UIEvent) => {
      if (isLoggedIn) {
        changePageType(event);
      } else {
        Tracker.trackOutboundLink(
          "More",
          "ARES Kudo",
          "Source:KudoInApp",
          ApplicationStore.getApplicationSetting("customization").learnMoreURL,
          true,
          false
        );
      }
    },
    [isLoggedIn]
  );

  const { onKeyDown } = MainFunctions.getA11yHandler(logoClickHandler);
  const classes = useStyles();

  const onToggleButtonClick = () => {
    if (!isDrawingPage) ApplicationActions.switchSidebar();
    else ApplicationActions.switchDrawingMenu();
  };

  const srcLogo = useMemo(
    () => (isMobile ? kudoLogoMobile : kudoLogo),
    [isMobile]
  );

  const token = (MainFunctions.QueryString("token") || "") as string;
  const isTokenAccess = (token as string).length > 0;
  const [isOldUIMode, setOldUIMode] = useState(
    userInfoStore.getUserInfo("preferences")?.useOldUI || false
  );

  const onInfoUpdate = useCallback(() => {
    setOldUIMode(userInfoStore.getUserInfo("preferences")?.useOldUI || false);
  }, []);

  useEffect(() => {
    userInfoStore.addChangeListener(INFO_UPDATE, onInfoUpdate);
    return () => {
      userInfoStore.removeChangeListener(INFO_UPDATE, onInfoUpdate);
    };
  }, []);

  const showButton: boolean = useMemo(() => {
    if (isMobile && location.pathname.indexOf(`/files`) !== -1) return true;

    if (isMobile && location.pathname.indexOf(`/resources/`) !== -1)
      return true;

    if (location.pathname.indexOf(`/file/`) !== -1) {
      const isFileInfoLoaded = !!(currentFile.name && currentFile._id);

      if (!isFileInfoLoaded || !isLoggedIn) return false;
      // for old UI - there are a bunch of options
      if (isOldUIMode) return true;
      // with ribbon - we show at least name in mobile mode
      if (isMobile) return true;
      // if it's pure token access - we don't show anything
      if (isTokenAccess && currentFile.viewFlag) return false;
      if (!Object.prototype.hasOwnProperty.call(currentFile, "viewFlag"))
        return false;
      // if it's not view only - there will be a bunch of options
      if (!currentFile.viewFlag) return true;
    }

    return false;
  }, [isMobile, isOldUIMode, isLoggedIn, currentFile, currentFile.viewFlag]);

  return (
    <Grid
      item
      sx={[
        {
          height: "100%",
          display: "flex",
          flexDirection: "row"
        },
        showButton && !isLoggedIn && { width: 220 },
        showButton && isMobile && !isTokenAccess && { width: 110 },
        isMobile && isTokenAccess && !isLoggedIn && { width: 50 }
      ]}
      id="temp-logo-id"
    >
      <Box sx={showButton ? { display: "inline-block" } : { display: "none" }}>
        {showButton && (
          <>
            <ToggleButton onClick={onToggleButtonClick} />
            {Object.keys(currentFile ?? {}).length > 0 && (
              <DrawingMenu
                currentFile={currentFile}
                notificationBadge={notificationBadge}
                onCommentButtonClick={onCommentButtonClick}
                isMobile={isMobile}
              />
            )}
          </>
        )}
      </Box>
      <Box
        sx={[
          { height: "100%", display: "flex" },
          isMobile && { width: "auto !important", maxWidth: 162 }
        ]}
      >
        <button
          className={clsx(
            classes.logoButton,
            isMobile ? classes.logoButtonMobile : null
          )}
          type="button"
          onClick={logoClickHandler}
          onKeyDown={onKeyDown}
        >
          <Box
            component="img"
            sx={{
              height: "50px",
              maxWidth: "100%",
              maxHeight: "100%",
              margin: "0 auto",
              display: "inline-block",
              padding: "3px 10px 5px 10px",
              transitionDuration: "0.2s",
              cursor: "pointer",
              "&:hover,&:focus,&:active": {
                backgroundColor: theme => theme.palette.VADER
              },
              ...(isMobile
                ? {
                    height: "45px",
                    padding: 0
                  }
                : {})
            }}
            src={srcLogo}
            alt="logo"
            data-component="logo-image"
          />
        </button>
      </Box>
    </Grid>
  );
}
