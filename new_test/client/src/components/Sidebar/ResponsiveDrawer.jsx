import React, { useEffect } from "react";
import propTypes from "prop-types";
import clsx from "clsx";
import { makeStyles, useTheme } from "@material-ui/core/styles";
import Drawer from "@material-ui/core/Drawer";
import ApplicationStore, {
  SIDEBAR_SWITCHED
} from "../../stores/ApplicationStore";

const useStyles = makeStyles(theme => ({
  drawer: {
    width: theme.kudoStyles.SIDEBAR_WIDTH,
    flexShrink: 0,
    position: "relative",
    zIndex: 1
  },
  drawerPaper: {
    width: theme.kudoStyles.SIDEBAR_WIDTH,
    backgroundColor: theme.palette.JANGO,
    overflowX: "hidden",
    borderRight: "none",
    paddingBottom: 110,
    borderBottom: 0
  },
  drawerPaperMobile: {
    width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH,
    maxWidth: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH_MAX
  }
}));

// Copied from https://material-ui.com/components/drawers/#responsive-drawer
// should be taken into consideration once header is update with MUI
// TODO: [XENON-40580] update once header is updated
function ResponsiveDrawer({ children, isMobile, onTouchMove }) {
  const classes = useStyles();
  const theme = useTheme();
  const [mobileOpen, setMobileOpen] = React.useState(false);

  const switchSidebar = () => {
    setMobileOpen(ApplicationStore.getApplicationSettingsObject().sidebarState);
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(SIDEBAR_SWITCHED, switchSidebar);
    return () => {
      ApplicationStore.removeChangeListener(SIDEBAR_SWITCHED, switchSidebar);
    };
  }, []);

  return (
    <nav
      className={clsx(!isMobile ? classes.drawer : null)}
      onTouchMove={onTouchMove}
    >
      {isMobile ? (
        <Drawer
          variant="persistent"
          anchor={theme.direction === "rtl" ? "right" : "left"}
          open={mobileOpen}
          classes={{
            paper: clsx(
              classes.drawerPaper,
              isMobile ? classes.drawerPaperMobile : null
            )
          }}
          ModalProps={{
            keepMounted: true // Better open performance on mobile.
          }}
        >
          {children}
        </Drawer>
      ) : (
        <Drawer
          anchor={theme.direction === "rtl" ? "right" : "left"}
          classes={{
            paper: clsx(classes.drawerPaper)
          }}
          variant="permanent"
          open
        >
          {children}
        </Drawer>
      )}
    </nav>
  );
}

ResponsiveDrawer.propTypes = {
  children: propTypes.node.isRequired,
  isMobile: propTypes.bool.isRequired,
  onTouchMove: propTypes.func.isRequired
};

export default ResponsiveDrawer;
