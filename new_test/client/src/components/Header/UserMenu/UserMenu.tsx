import { Button, Grid, Menu, menuClasses, styled } from "@mui/material";
import clsx from "clsx";
import React, {
  SyntheticEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import ApplicationActions from "../../../actions/ApplicationActions";
import UserInfoActions from "../../../actions/UserInfoActions";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore, {
  UPDATE,
  USER_MENU_SWITCHED
} from "../../../stores/ApplicationStore";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGES_UPDATE
} from "../../../stores/UserInfoStore";
import HelpSection from "../SpecificHeader/HelpSection";
import MenuItem from "./CustomMenuItem";
import UserName from "./UserName";

import companySVG from "../../../assets/images/userMenu/company.svg";
import filesSVG from "../../../assets/images/userMenu/files.svg";
import resourcesSVG from "../../../assets/images/userMenu/gear.svg";
import logoutSVG from "../../../assets/images/userMenu/logout.svg";
import storageSVG from "../../../assets/images/userMenu/storage.svg";
import userIconSVG from "../../../assets/images/userMenu/user-icon.svg";
import usersSVG from "../../../assets/images/userMenu/users.svg";
import webGLTestSVG from "../../../assets/images/userMenu/webgl-test.svg";
import FilesListStore from "../../../stores/FilesListStore";
import Storage from "../../../utils/Storage";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";

const StyledMenuGrid = styled(Grid)(({ theme }) => ({
  // [theme.breakpoints.down("sm")]: {
  //   height: "55px"
  // },
  "& .StyledMenuButtonHided": {
    width: "50px"
  },
  "& .StyledMenuButtonMobile": {
    width: "150px"
  }
}));

const StyledMenuButton = styled(Button)(() => ({
  height: "50px",
  width: "200px",
  justifyContent: "start",
  textTransform: "none",
  borderRadius: 0,
  padding: "6px 8px"
}));

const StyledMenu = styled(Menu)(({ theme }) => ({
  [`& .${menuClasses.paper}`]: {
    padding: 0,
    backgroundColor: theme.palette.DARK,
    border: `solid 1px ${theme.palette.VADER}`,
    borderRadius: 0,
    right: 0,
    width: "200px",
    left: "unset !important",
    top: `${theme.kudoStyles.HEADER_HEIGHT} !important`,
    [theme.breakpoints.down("sm")]: {
      width: "200px"
    }
  },
  [`& .${menuClasses.list}`]: {
    padding: 0
  }
}));

type PropType = {
  fileViewFlag?: boolean;
  isMobile: boolean;
};
let disableUserMenuActionClick = false;
let userMenuButtonRef: unknown = null;

const UserMenu = ({ isMobile, fileViewFlag = true }: PropType) => {
  const [areStoragesConnected, setAreStoragesConnected] = useState(
    _.flatten(_.values(UserInfoStore.getStoragesInfo() || {})).length > 0
  );
  const [userInfo, setUserInfo] = useState(
    _.clone(UserInfoStore.getUserInfo() || {})
  );
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [counter, setCounter] = useState(0);
  const currentPage = MainFunctions.detectPageType();

  //  constant variables
  const isLoggedIn = !!Storage.store("sessionId");
  const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
  const { externalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const { companiesAdmin, companiesAll } =
    ApplicationStore.getApplicationSetting("featuresEnabled");
  const companyInfo = UserInfoStore.getUserInfo("company");

  const getMyDrawingsLink = () => {
    const { storage, accountId } = FilesListStore.getCurrentFolder();
    if (!storage || !accountId) return `/files/-1`;
    return `/files/${storage}/${accountId}/-1`;
  };

  const updateMenuState = () => {
    if (disableUserMenuActionClick) {
      disableUserMenuActionClick = false;
      return;
    }
    (userMenuButtonRef as HTMLButtonElement).click();
  };

  const onUserStoreUpdate = () => {
    setUserInfo(_.clone(UserInfoStore.getUserInfo() || {}));
    setAreStoragesConnected(
      _.flatten(_.values(UserInfoStore.getStoragesInfo() || {})).length > 0
    );
  };

  const onApplicationStoreUpdate = () => {
    // just trigger render
    setCounter(c => counter + 1);
  };

  const closeMenu = () => {
    disableUserMenuActionClick = true;
    ApplicationActions.switchUserMenu(false);
    setAnchorEl(null);
  };

  const handleMenu = (event: SyntheticEvent) => {
    disableUserMenuActionClick = true;
    ApplicationActions.switchUserMenu(false);
    XenonConnectionActions.postMessage({ messageName: "FL_DROPDOWN_OPENED" });
    setAnchorEl(event.currentTarget as HTMLElement);
  };

  const initiateLogout = () => {
    if (MainFunctions.detectPageType() === "file" && !fileViewFlag)
      UserInfoActions.saveLogoutRequest(true);
    else UserInfoActions.logout();
  };

  const handleRedirect = (e: SyntheticEvent, url: string) => {
    e.preventDefault();
    if (MainFunctions.isInIframe()) {
      window.open(url, "_blank", "noopener,noreferrer");
    } else {
      ApplicationActions.changePage(url);
    }
    closeMenu();
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(INFO_UPDATE, onUserStoreUpdate);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, onUserStoreUpdate);
    ApplicationStore.addChangeListener(UPDATE, onApplicationStoreUpdate);
    ApplicationStore.addChangeListener(USER_MENU_SWITCHED, updateMenuState);

    return () => {
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUserStoreUpdate);
      UserInfoStore.removeChangeListener(STORAGES_UPDATE, onUserStoreUpdate);
      ApplicationStore.removeChangeListener(UPDATE, onApplicationStoreUpdate);
      ApplicationStore.removeChangeListener(
        USER_MENU_SWITCHED,
        updateMenuState
      );
    };
  }, []);

  const [isOldUIMode, setOldUIMode] = useState(
    UserInfoStore.getUserInfo("preferences")?.useOldUI || false
  );

  const onInfoUpdate = useCallback(() => {
    setOldUIMode(UserInfoStore.getUserInfo("preferences")?.useOldUI || false);
  }, []);

  useEffect(() => {
    UserInfoStore.addChangeListener(INFO_UPDATE, onInfoUpdate);
    return () => {
      UserInfoStore.removeChangeListener(INFO_UPDATE, onInfoUpdate);
    };
  }, []);

  const forceHide = useMemo(() => {
    if (isMobile && location.pathname.indexOf(`/file/`) !== -1) {
      return !isOldUIMode;
    }
    return false;
  }, [isMobile, isOldUIMode]);

  if (!isLoggedIn || currentPage === "index") return null;
  return (
    <StyledMenuGrid item>
      <HelpSection isMobile={isMobile} ignoreHide={forceHide} />
      <StyledMenuButton
        data-component="user-header-block"
        aria-controls="simple-menu"
        aria-haspopup="true"
        onClick={handleMenu}
        className={clsx(
          isMobile && Boolean(anchorEl) && !forceHide
            ? "StyledMenuButtonMobile"
            : null,
          !isMobile || (isMobile && Boolean(anchorEl) && !forceHide)
            ? null
            : "StyledMenuButtonHided"
        )}
        sx={{
          "&:hover": {
            background: "rgba(255, 255, 255, 0.08)"
          }
        }}
        ref={ref => {
          userMenuButtonRef = ref;
        }}
      >
        <UserName
          userInfo={userInfo}
          open={Boolean(anchorEl)}
          isMobile={isMobile}
          forceHide={forceHide}
        />
      </StyledMenuButton>
      <StyledMenu
        id="menu-header"
        anchorEl={anchorEl}
        elevation={0}
        keepMounted
        open={Boolean(anchorEl)}
        anchorOrigin={{
          vertical: "bottom",
          horizontal: "right"
        }}
        transformOrigin={{ vertical: "top", horizontal: "right" }}
        onClose={closeMenu}
      >
        {UserInfoStore.isFreeAccount() && (
          <MenuItem
            isBuy
            onClick={e => {
              e.preventDefault();
              window.open(
                ApplicationStore.getApplicationSetting("customization").buyURL,
                "_blank",
                "noopener,noreferrer"
              );
              closeMenu();
            }}
            dataComponent="buy-license-item"
          >
            <FormattedMessage id="buyLicense" />
          </MenuItem>
        )}

        {externalStoragesAvailable && (
          <MenuItem
            isCurrent={currentPage.includes("storages")}
            onClick={e => {
              handleRedirect(e, `${UIPrefix}storages`);
            }}
            icon={storageSVG}
            dataComponent="storage-item"
          >
            <FormattedMessage id="Storage" />
          </MenuItem>
        )}

        {areStoragesConnected && (
          <MenuItem
            isCurrent={currentPage.includes("files")}
            onClick={e => {
              handleRedirect(e, getMyDrawingsLink());
            }}
            icon={filesSVG}
            dataComponent="my-drawings-item"
          >
            <FormattedMessage id="myDrawings" />
          </MenuItem>
        )}

        {/* <MenuItem
          isCurrent={
            currentPage.includes("resources") &&
            !ApplicationStore.useOldResources
          }
          onClick={e => {
            ApplicationActions.switchOldResources(false);
            handleRedirect(e, `${UIPrefix}resources/templates/my`);
          }}
          icon={resourcesSVG}
          dataComponent="resources-item"
        >
          <FormattedMessage id="new resources" />
        </MenuItem> */}

        <MenuItem
          isCurrent={
            currentPage.includes("resources") &&
            !!ApplicationStore.useOldResources
          }
          onClick={e => {
            ApplicationActions.switchOldResources(true);
            handleRedirect(e, `${UIPrefix}resources/templates/my`);
          }}
          icon={resourcesSVG}
          dataComponent="resources-item"
        >
          <FormattedMessage id="resources" />
        </MenuItem>

        <MenuItem
          isCurrent={currentPage.includes("profile")}
          onClick={e => {
            handleRedirect(e, `${UIPrefix}profile`);
          }}
          icon={userIconSVG}
          dataComponent="my-profile-item"
        >
          <FormattedMessage id="myProfile" />
        </MenuItem>

        {companyInfo.isAdmin === true &&
          (companiesAll === true ||
            (companiesAdmin === true &&
              UserInfoStore.getUserInfo("isAdmin") === true)) && (
            <MenuItem
              isCurrent={currentPage.includes("company")}
              onClick={e => {
                handleRedirect(e, `${UIPrefix}company`);
              }}
              icon={companySVG}
              dataComponent="my-company-item"
            >
              <FormattedMessage id="myCompany" />
            </MenuItem>
          )}

        {userInfo.isAdmin && (
          <MenuItem
            isCurrent={currentPage.includes("users")}
            onClick={e => {
              handleRedirect(e, `${UIPrefix}users`);
            }}
            icon={usersSVG}
            dataComponent="users-item"
          >
            <FormattedMessage id="users" />
          </MenuItem>
        )}

        <MenuItem
          isCurrent={currentPage.includes("check")}
          onClick={e => {
            handleRedirect(e, `${UIPrefix}check`);
          }}
          icon={webGLTestSVG}
          dataComponent="webgl-test-item"
        >
          <FormattedMessage id="webGLTest" />
        </MenuItem>
        <MenuItem
          onClick={() => {
            initiateLogout();
            closeMenu();
          }}
          isLogout
          icon={logoutSVG}
          dataComponent="logout-item"
        >
          <FormattedMessage id="logout" />
        </MenuItem>
      </StyledMenu>
    </StyledMenuGrid>
  );
};

// const shouldComponentUpdate = (prevProps: Readonly<PropType>, nextProps: Readonly<PropType>): boolean => {
//   // To better performance a comparison needed here
//   return true
// }

export default React.memo(UserMenu /* , shouldComponentUpdate */);
