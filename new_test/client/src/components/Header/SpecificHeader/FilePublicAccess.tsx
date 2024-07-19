import {
  Box,
  Button,
  Grid,
  SxProps,
  Theme,
  Typography,
  useTheme
} from "@mui/material";
import { Buffer } from "buffer";
import React, { useEffect, useMemo, useState } from "react";
import { FormattedMessage } from "react-intl";
import UserInfoActions from "../../../actions/UserInfoActions";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import Storage from "../../../utils/Storage";
import Tracker from "../../../utils/Tracker";
import SmartTooltip from "../../SmartTooltip/SmartTooltip";
import LoginMenu from "./FilePublicAccess/LoginMenu";
import HelpSection from "./HelpSection";
import ToggleButton from "../Logo/ToggleButton";

type PropType = {
  currentFile: {
    _id: string;
    name: string;
    folderId: string;
  };
  isMobile: boolean;
};

export default function FilePublicAccess({ currentFile, isMobile }: PropType) {
  const [displayWidth, setDisplayWidth] = useState(window.innerWidth);
  const [isLoginMenuVisible, setLoginMenuVisible] = useState(false);
  const {
    kudoStyles: {
      THRESHOLD_TO_SHOW_LOGIN_MENU,
      THRESHOLD_TO_SHOW_OPEN_IN_APP_MOBILE_MENU
    }
  } = useTheme();
  const showLoginMenu = useMemo(
    () => isMobile || displayWidth < THRESHOLD_TO_SHOW_LOGIN_MENU,
    [isMobile, displayWidth, THRESHOLD_TO_SHOW_LOGIN_MENU]
  );
  const fileName = useMemo(
    () => `${MainFunctions.shrinkString(currentFile.name, isMobile ? 15 : 60)}`,
    [isMobile, currentFile, THRESHOLD_TO_SHOW_OPEN_IN_APP_MOBILE_MENU]
  );
  const buttonSx = useMemo<SxProps<Theme>>(
    () => ({
      borderRadius: 0,
      border: theme => `1px solid ${theme.palette.CLONE}`,
      color: theme => theme.palette.LIGHT,
      height: "79%",
      "&:hover": {
        border: theme => `1px solid ${theme.palette.YELLOW_BUTTON}`
      }
    }),
    []
  );
  const textSx = useMemo<SxProps<Theme>>(
    () => ({
      color: theme => theme.palette.LIGHT,
      lineHeight: "50px"
    }),
    []
  );

  const handleLogin = () => {
    let bRedirectParam = Buffer.from(`file/${currentFile._id}`).toString(
      "base64"
    );
    // XENON-43040 - for box files there's no token
    if ((MainFunctions.QueryString("token").length as number) > 0) {
      bRedirectParam = Buffer.from(
        `file/${currentFile._id}?token=${MainFunctions.QueryString("token")}`
      ).toString("base64");
    }
    // XENON-52325 - for Box we need to retry getting connection, so changing the URL back
    else if (
      (MainFunctions.QueryString("external").length as number) > 0 &&
      location.href.includes("BX+") &&
      Storage.store("boxRedirect")
    ) {
      bRedirectParam = Storage.store("boxRedirect") as string;
    }
    if (MainFunctions.isInIframe()) {
      window.open(
        UserInfoActions.loginWithSSO(bRedirectParam, true, false),
        "_blank",
        "noopener,noreferrer"
      );
    } else {
      UserInfoActions.loginWithSSO(bRedirectParam, true);
    }
  };

  const handleSignUpRedirect = () => {
    const { independentLogin } =
      ApplicationStore.getApplicationSetting("featuresEnabled");

    if (independentLogin) {
      window.open(
        `
      ${ApplicationStore.getApplicationSetting(
        "UIPrefix"
      )}signup?bredirect=${btoa(
        `file/${currentFile._id}?token=${MainFunctions.QueryString("token")}`
      )}`,
        "_blank",
        "noopener,noreferrer"
      );
      return;
    }

    const redirectURL = `/file/${
      currentFile._id
    }?token=${MainFunctions.QueryString("token")}`;
    const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");

    const customerPortalURL =
      ApplicationStore.getApplicationSetting("customerPortalURL");
    Tracker.trackOutboundLink(
      "Signup",
      "CreateAccount",
      "Source:KudoInApp",
      `${customerPortalURL}/account/create?source=${btoa(
        `${
          location.origin
        }${UIPrefix}notify/account/sso/${MainFunctions.btoaForURLEncoding(
          btoa(redirectURL)
        )}`
      )}`,
      true,
      false
    );
  };

  const loginButtonClick = () => {
    setLoginMenuVisible(true);
  };

  const closeMenu = () => {
    setLoginMenuVisible(false);
  };

  const resizeHandler = () => {
    setDisplayWidth(window.innerWidth);
  };

  useEffect(() => {
    const observer = new ResizeObserver(() => {
      resizeHandler();
    });
    observer.observe(document.body);
    return () => {
      observer.unobserve(document.body);
    };
  }, []);

  return (
    <Grid
      container
      sx={{
        height: "100%",
        justifyContent: "space-between"
      }}
    >
      <Grid
        item
        sx={[
          { height: "100%" },
          !isMobile && {
            display: "flex",
            alignItems: "center",
            gap: "10px"
          }
        ]}
      >
        <SmartTooltip
          forcedOpen={(currentFile.name || "").length > 60}
          placement="bottom"
          title={currentFile.name}
        >
          <Typography
            sx={{
              ...textSx,
              paddingLeft: "10px",
              fontSize: theme => theme.typography.pxToRem(14)
            }}
          >
            {fileName}&nbsp;
            <FormattedMessage id="ViewOnly" />
          </Typography>
        </SmartTooltip>
      </Grid>
      <Grid item sx={{ display: "flex" }}>
        <HelpSection isMobile={isMobile} />
        {showLoginMenu ? (
          <>
            <ToggleButton id="logo-menu-button-id" onClick={loginButtonClick} />
            <LoginMenu
              isVisible={isLoginMenuVisible}
              closeMenu={closeMenu}
              login={handleLogin}
              signUpRedirect={handleSignUpRedirect}
              showOpenInAppButton={false}
            />
          </>
        ) : (
          <Box
            sx={{
              display: "flex",
              alignItems: "center",
              pr: 1,
              gap: 1
            }}
          >
            <Typography
              sx={{
                ...textSx,
                display: "inline-block",
                fontSize: theme => theme.typography.pxToRem(14)
              }}
            >
              <FormattedMessage id="wantToCommentDrawing" />
            </Typography>
            <Button
              onClick={handleLogin}
              data-component="loginButton"
              sx={buttonSx}
            >
              <FormattedMessage id="login" />
            </Button>
            <Button
              sx={buttonSx}
              onClick={handleSignUpRedirect}
              data-component="signupButton"
            >
              <FormattedMessage id="signUp" />
            </Button>
          </Box>
        )}
      </Grid>
    </Grid>
  );
}
