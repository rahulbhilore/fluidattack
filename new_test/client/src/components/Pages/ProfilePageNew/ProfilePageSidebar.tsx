import {
  Box,
  Collapse,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  buttonBaseClasses,
  listItemIconClasses,
  styled,
  typographyClasses
} from "@mui/material";
import MuiDrawer, { drawerClasses } from "@mui/material/Drawer";
import { CSSObject, Theme, useTheme } from "@mui/material/styles";
import React, { useEffect, useMemo, useState } from "react";
import { useIntl } from "react-intl";
import accountIcon from "../../../assets/images/profile/accountIcon.svg";
import collapseMenu from "../../../assets/images/sidebar/collapseMenu.svg";
import collapseSidebarIcon from "../../../assets/images/sidebar/collapseSidebarIcon.svg";
import expandMenu from "../../../assets/images/sidebar/expandMenu.svg";
import expandSidebarIcon from "../../../assets/images/sidebar/expandSidebarIcon.svg";
import Footer from "../../Footer/Footer";
import HeaderSpacer from "../../Page/HeaderSpacer";
import SideBarItem from "./SideBarItem";

const openedMixin = (theme: Theme): CSSObject => ({
  width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH,
  transition: theme.transitions.create("width", {
    easing: theme.transitions.easing.sharp,
    duration: theme.transitions.duration.enteringScreen
  })
});

const closedMixin = (theme: Theme): CSSObject => ({
  transition: theme.transitions.create("width", {
    easing: theme.transitions.easing.sharp,
    duration: theme.transitions.duration.leavingScreen
  }),
  width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN
});

const StyledDrawer = styled(MuiDrawer, {
  shouldForwardProp: prop => prop !== "open"
})(({ theme, open }) => ({
  [`& .${drawerClasses.paper}`]: {
    width: theme.kudoStyles.SIDEBAR_WIDTH,
    backgroundColor: theme.palette.drawer.bg,
    borderRight: "none",
    paddingBottom: 110,
    borderBottom: 0,
    overflow: "hidden",
    ...(!open
      ? {
          ...closedMixin(theme),
          [`& .${drawerClasses.paper}`]: closedMixin(theme)
        }
      : {
          ...openedMixin(theme),
          [`& .${drawerClasses.paper}`]: openedMixin(theme)
        })
  }
}));

type PropType = {
  isMobile: boolean;
  onCollapseClick: () => void;
  onExpandClick: () => void;
};

export default function ProfilePageSidebar({
  isMobile,
  onCollapseClick,
  onExpandClick
}: PropType) {
  const [profileOpen, setProfileOpen] = React.useState(true);
  const sidebarOpen = useMemo(() => !isMobile, [isMobile]);
  const [showFooter, setShowFooter] = useState(!isMobile);
  const { formatMessage } = useIntl();
  const {
    transitions: { duration }
  } = useTheme();
  const links = useMemo(
    () => [
      {
        link: "/profile/account",
        messageId: "accountData",
        dataComponent: "accountDataTab"
      },
      {
        link: "/profile/preferences",
        messageId: "Preferences",
        dataComponent: "preferencesTab"
      }
    ],
    []
  );

  const handleClickCollapseProfileItems = () => {
    if (!sidebarOpen) {
      onExpandClick();
      if (!profileOpen) setProfileOpen(true);
    } else setProfileOpen(prev => !prev);
  };

  const handleSidebarCollapse = () => {
    onCollapseClick();
  };

  const handleSidebarExpand = () => {
    onExpandClick();
  };

  useEffect(() => {
    let timeout: NodeJS.Timeout;
    if (isMobile) setShowFooter(false);
    else
      timeout = setTimeout(() => {
        setShowFooter(true);
        // should be same with drawer opened transition duration
      }, duration.enteringScreen);
    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [isMobile]);

  return (
    <Box
      component="nav"
      sx={{
        flexShrink: 0,
        position: "relative",
        zIndex: 1,
        flexWrap: "nowrap"
      }}
    >
      <StyledDrawer variant="permanent" open={sidebarOpen}>
        <HeaderSpacer />
        <List
          component="nav"
          sx={{
            background: theme =>
              profileOpen
                ? theme.palette.drawer.item.background.standard
                : "inherit",
            p: 0,
            "& > li": { p: 0 },
            [`& .${listItemIconClasses.root}`]: {
              alignItems: "center",
              display: "flex",
              height: theme => theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN,
              justifyContent: "center",
              minWidth: 0,
              width: theme => theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MIN
            },
            [`& .${buttonBaseClasses.root}`]: { p: 0, whiteSpace: "nowrap" },
            [`& .${typographyClasses.root}`]: { whiteSpace: "nowrap" },
            [`& .${typographyClasses.body1}`]: {
              fontSize: theme => theme.spacing(1.75)
            }
          }}
        >
          <ListItem
            disableGutters
            sx={{
              // no color definitions for here as it is not same with design and will be changed in future
              borderBottom: "1px solid #838383",
              background: "#494A4D"
            }}
          >
            <ListItemButton
              onClick={isMobile ? handleSidebarExpand : handleSidebarCollapse}
            >
              <ListItemIcon>
                <img
                  src={isMobile ? expandSidebarIcon : collapseSidebarIcon}
                  alt={formatMessage({
                    id: isMobile ? "expandSidebar" : "collapseSidebar"
                  })}
                />
              </ListItemIcon>
              <ListItemText
                primary={formatMessage({ id: "collapse" })}
                sx={{
                  color: kudoTheme => kudoTheme.palette.drawer.item.textColor,
                  display: isMobile ? "none" : "block"
                }}
              />
            </ListItemButton>
          </ListItem>
          <ListItem disableGutters>
            <ListItemButton
              onClick={handleClickCollapseProfileItems}
              sx={{
                overflowX: "hidden"
              }}
            >
              <ListItemIcon>
                <img
                  src={accountIcon}
                  alt={formatMessage({ id: "expandMenu" })}
                />
              </ListItemIcon>
              <ListItemText
                primary={formatMessage({ id: "myProfile" })}
                sx={{
                  color: kudoTheme => kudoTheme.palette.drawer.item.textColor,
                  display: isMobile ? "none" : "block"
                }}
              />
              <Box
                component="div"
                sx={{
                  color: theme => theme.palette.drawer.item.textColor,
                  display: isMobile ? "none" : "block",
                  p: 1.5
                }}
              >
                {profileOpen ? (
                  <img
                    src={expandMenu}
                    alt={formatMessage({ id: "expandMenu" })}
                  />
                ) : (
                  <img
                    src={collapseMenu}
                    alt={formatMessage({ id: "collapseMenu" })}
                  />
                )}
              </Box>
            </ListItemButton>
          </ListItem>

          <Collapse in={!isMobile && profileOpen} timeout="auto" unmountOnExit>
            {links.map(item => (
              <SideBarItem isMobile={isMobile} item={item} key={item.link} />
            ))}
          </Collapse>
        </List>
        {showFooter && <Footer isSideBar />}
      </StyledDrawer>
    </Box>
  );
}
