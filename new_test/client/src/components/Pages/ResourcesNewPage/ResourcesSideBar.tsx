import React, { useEffect, useReducer } from "react";
import clsx from "clsx";
import { styled } from "@mui/material";
import MuiDrawer, { drawerClasses } from "@mui/material/Drawer";
import { makeStyles } from "@mui/styles";
import { useTheme, Theme, CSSObject } from "@mui/material/styles";
import Grid from "@mui/material/Grid";
import List from "@mui/material/List";
import ApplicationStore, {
  SIDEBAR_SWITCHED
} from "../../../stores/ApplicationStore";
import userInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import SideBarItem from "./SideBarItem";
import Footer from "../../Footer/Footer";

import blocksSVG from "../../../assets/images/resources/blocks-16.svg";
import companyFontsSVG from "../../../assets/images/resources/company_fonts-16.svg";
import templatesSVG from "../../../assets/images/resources/templates-16.svg";
import publicTemplatesSVG from "../../../assets/images/resources/shared_templates-16.svg";
import fontsSVG from "../../../assets/images/resources/my_fonts-16.svg";

const useStyles = makeStyles((theme: Theme) => ({
  drawerRoot: {
    flexShrink: 0,
    position: "relative",
    zIndex: 1,
    flexWrap: "nowrap"
  },
  desktopDrawer: {
    width: theme.kudoStyles.SIDEBAR_WIDTH
  },
  drawerPaper: {
    width: theme.kudoStyles.SIDEBAR_WIDTH,
    backgroundColor: theme.palette.JANGO,
    overflowX: "hidden",
    borderRight: "none",
    paddingBottom: 110,
    borderBottom: 0,
    paddingTop: "60px"
  }
}));

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
    backgroundColor: theme.palette.JANGO,
    borderRight: "none",
    paddingBottom: 110,
    borderBottom: 0,
    ...(!open
      ? {
          ...closedMixin(theme),
          "& .MuiDrawer-paper": closedMixin(theme)
        }
      : {
          ...openedMixin(theme),
          "& .MuiDrawer-paper": openedMixin(theme)
        })
  }
}));

type Props = {
  isMobile: boolean;
};

export default function ResourcesSideBar({ isMobile }: Props) {
  const classes = useStyles();
  const theme = useTheme();
  const [mobileOpen, setMobileOpen] = React.useState(!isMobile);
  const [, forceUpdate] = useReducer(x => x + 1, 0);

  const switchSidebar = () => {
    setMobileOpen(ApplicationStore.getApplicationSettingsObject().sidebarState);
  };

  const userInfoUpdated = () => {
    forceUpdate();
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(SIDEBAR_SWITCHED, switchSidebar);
    return () => {
      ApplicationStore.removeChangeListener(SIDEBAR_SWITCHED, switchSidebar);
    };
  }, []);

  useEffect(() => {
    userInfoStore.addChangeListener(INFO_UPDATE, userInfoUpdated);
    return () => {
      userInfoStore.removeChangeListener(INFO_UPDATE, userInfoUpdated);
    };
  }, []);

  const getAvailableItems = () => {
    const isAdmin = userInfoStore.getUserInfo("isAdmin");
    const isCompanyAdmin = userInfoStore.getUserInfo("company")?.isAdmin;

    const items = [];
    items.push({
      link: "/resources/templates/my",
      messageId: "customtemplates",
      dataComponent: "customTemplatesTab",
      icon: templatesSVG
    });

    if (isAdmin)
      items.push({
        link: "/resources/templates/public",
        messageId: "publictemplates",
        dataComponent: "publicTemplatesTab",
        icon: publicTemplatesSVG
      });

    items.push({
      link: "/resources/fonts/my",
      messageId: "customFonts",
      dataComponent: "customFontsTab",
      icon: fontsSVG
    });

    if (isCompanyAdmin)
      items.push({
        link: "/resources/fonts/public",
        messageId: "companyFonts",
        dataComponent: "companyFontsTab",
        icon: companyFontsSVG
      });

    items.push({
      link: "/resources/blocks",
      messageId: "blockLibrary",
      dataComponent: "blockLibraryTab",
      icon: blocksSVG
    });

    return items;
  };

  return (
    <Grid
      component="nav"
      sx={{
        flexShrink: 0,
        position: "relative",
        zIndex: 1,
        flexWrap: "nowrap"
      }}
    >
      <StyledDrawer
        classes={{
          paper: clsx(classes.drawerPaper)
        }}
        anchor={theme.direction === "rtl" ? "right" : "left"}
        variant="permanent"
        open={mobileOpen}
      >
        <List>
          {getAvailableItems().map(item => (
            <SideBarItem item={item} />
          ))}
        </List>
        {!isMobile && <Footer isSideBar />}
      </StyledDrawer>
    </Grid>
  );
}
