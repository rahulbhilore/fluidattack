import HelpIcon from "@mui/icons-material/Help";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import {
  Box,
  IconButton,
  Menu,
  MenuItem,
  SxProps,
  menuClasses,
  useTheme
} from "@mui/material";
import React, {
  CSSProperties,
  SyntheticEvent,
  useCallback,
  useEffect,
  useMemo,
  useState
} from "react";
import { FormattedMessage, useIntl } from "react-intl";
import ModalActions from "../../../actions/ModalActions";
import helpSVG from "../../../assets/images/userMenu/help.svg";
import quickTourSVG from "../../../assets/images/userMenu/quick-tour.svg";
import ApplicationStore, {
  USER_MENU_SWITCHED
} from "../../../stores/ApplicationStore";
import UserInfoStore from "../../../stores/UserInfoStore";
import Storage from "../../../utils/Storage";
import QuickTour from "../QuickTour/QuickTour";

export default function HelpSection({
  isMobile,
  ignoreHide = false
}: {
  isMobile: boolean;
  ignoreHide?: boolean;
}) {
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  const [isQuickTourOpen, setQuickTour] = useState<boolean>(false);
  const [needToHide, setNeedToHide] = useState<boolean>(false);
  const theme = useTheme();
  const open = Boolean(anchorEl);
  const { formatMessage } = useIntl();

  const userMenuSwitched = useCallback(() => {
    if ((!isMobile && !needToHide) || ignoreHide) return;

    if (!isMobile && needToHide) {
      setNeedToHide(false);
      return;
    }

    if (!ApplicationStore.getUserMenuState() && needToHide) {
      setNeedToHide(false);
      return;
    }

    setNeedToHide(true);
  }, [isMobile, needToHide, ignoreHide]);

  useEffect(() => {
    ApplicationStore.addChangeListener(USER_MENU_SWITCHED, userMenuSwitched);
    return () => {
      ApplicationStore.removeChangeListener(
        USER_MENU_SWITCHED,
        userMenuSwitched
      );
    };
  }, [needToHide]);

  const handleMenu = (event: SyntheticEvent) => {
    setAnchorEl(event.currentTarget as HTMLElement);
  };

  const handleClose = () => {
    setAnchorEl(null);
  };

  const handleQuickTourClose = () => {
    setQuickTour(false);
  };

  const handleQuickTourOpen = () => {
    setQuickTour(true);
    handleClose();
  };

  const openHelp = () => {
    const helpURL =
      Storage.store("lang") === "en" ? "" : `${Storage.store("lang")}/`;
    window.open(`/help/${helpURL}index.htm`, "_blank", "noopener,noreferrer");
  };

  const handleAbout = () => {
    ModalActions.showAbout();
    handleClose();
  };

  const itemIconStyles = useMemo<CSSProperties>(
    () => ({
      width: "20px",
      height: "20px",
      marginRight: theme.spacing(1)
    }),
    []
  );

  const menuItemSx = useMemo<SxProps>(
    () => ({
      color: theme.palette.LIGHT,
      padding: theme.spacing(1),
      minWidth: "120px",
      fontSize: 12,
      "&:hover": {
        backgroundColor: `${theme.palette.OBI} !important`
      },
      "&:focus": {
        backgroundColor: `${theme.palette.SNOKE}`
      }
    }),
    []
  );

  if (needToHide) return null;

  return (
    <Box
      sx={{
        display: "inline-block",
        margin: `0 ${theme.spacing(1)}px`,
        alignSelf: "center",
        [theme.breakpoints.down("xs")]: {
          margin: "0 -5px 0 5px"
        }
      }}
    >
      <QuickTour
        isOpen={isQuickTourOpen}
        onClose={handleQuickTourClose}
        isLoggedIn={UserInfoStore.getUserInfo("isLoggedIn")}
      />
      <IconButton
        aria-label="help"
        aria-controls="menu-appbar"
        aria-haspopup="true"
        onClick={handleMenu}
        sx={{
          padding: theme.spacing(1),
          color: theme.palette.LIGHT,
          fontSize: "20px",
          "&:hover": {
            backgroundColor: `${theme.palette.LIGHT}14`
          }
        }}
        data-component="help-dropdown-button"
      >
        <HelpIcon />
      </IconButton>
      <Menu
        id="menu-appbar"
        anchorEl={anchorEl}
        elevation={0}
        sx={{
          [`& .${menuClasses.paper}`]: {
            padding: 0,
            backgroundColor: theme.palette.DARK,
            border: `solid 1px ${theme.palette.VADER}`,
            borderRadius: 0
          },
          [`& .${menuClasses.list}`]: {
            padding: 0
          }
        }}
        anchorOrigin={{
          vertical: "bottom",
          horizontal: "left"
        }}
        keepMounted
        transformOrigin={{
          vertical: "top",
          horizontal: "left"
        }}
        open={open}
        onClose={handleClose}
      >
        <MenuItem
          onClick={openHelp}
          sx={{ ...menuItemSx }}
          data-component="help-item"
        >
          <img
            src={helpSVG}
            style={{ ...itemIconStyles }}
            alt={formatMessage({ id: "help" })}
            data-component="help-img"
          />
          <FormattedMessage id="help" />
        </MenuItem>
        <MenuItem
          onClick={handleQuickTourOpen}
          sx={{ ...menuItemSx }}
          data-component="quick-tour-item"
        >
          <img
            src={quickTourSVG}
            style={{ ...itemIconStyles }}
            alt={formatMessage({ id: "quickTour" })}
            data-component="quick-tour-img"
          />
          <FormattedMessage id="quickTour" />
        </MenuItem>
        <MenuItem
          onClick={handleAbout}
          sx={{ ...menuItemSx }}
          data-component="about-item"
        >
          <InfoOutlinedIcon
            sx={{
              marginRight: theme.spacing(1),
              color: theme.palette.LIGHT,
              fontSize: "20px"
            }}
            data-component="about-img"
            aria-label={formatMessage({ id: "about" })}
          />
          <FormattedMessage id="about" />
        </MenuItem>
      </Menu>
    </Box>
  );
}
