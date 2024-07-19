import React from "react";
import PropTypes from "prop-types";
import { makeStyles } from "@material-ui/core/styles";
import Typography from "@material-ui/core/Typography";
import Box from "@material-ui/core/Box";
import { Button, Grid } from "@material-ui/core";
import SVG from "react-inlinesvg";
import { Link } from "react-router";
import iconsDictionary from "../../../../../constants/appConstants/ObjectIcons";
import permissionsSVG from "../../../../../assets/images/permissions.svg";
import ModalActions from "../../../../../actions/ModalActions";
import RelativeTime from "../../../../RelativeTime/RelativeTime";
import ContextMenuStore from "../../../../../stores/ContextMenuStore";
import ContextMenuActions from "../../../../../actions/ContextMenuActions";

const useStyles = makeStyles(theme => ({
  name: {
    color: theme.palette.VADER,
    fontSize: theme.typography.pxToRem(12),
    fontWeight: 600,
    marginLeft: theme.typography.pxToRem(4)
  },
  date: {
    color: theme.palette.VADER,
    fontSize: theme.typography.pxToRem(12),
    marginLeft: theme.typography.pxToRem(4)
  },
  container: {
    marginTop: theme.typography.pxToRem(10)
  },
  controlsContainer: {
    textAlign: "right"
  },
  genericIcon: {
    height: "32px",
    width: "32px",
    marginLeft: theme.typography.pxToRem(6)
  },
  permissionsButton: {
    height: "32px",
    width: "32px",
    minWidth: "32px",
    backgroundColor: "transparent",
    border: `1px solid ${theme.palette.GREY_TEXT}`,
    borderRadius: 0,
    "& svg": {
      minWidth: theme.typography.pxToRem(21)
    },
    "&:hover svg > .st0": {
      fill: theme.palette.LIGHT
    },
    "&:hover": {
      backgroundColor: theme.palette.OBI
    }
  },
  menuButton: {
    height: theme.typography.pxToRem(27),
    minWidth: theme.typography.pxToRem(30),
    backgroundColor: "transparent",
    border: "none",
    float: "right",
    marginLeft: theme.typography.pxToRem(4),
    "& .MuiButton-label": {
      display: "block",
      marginTop: theme.typography.pxToRem(-34)
    },
    "&:hover svg > .st0": {
      fill: theme.palette.LIGHT
    },
    "&:hover": {
      // backgroundColor: theme.palette.VADER
    }
  },
  menuButtonPoint: {
    display: "block",
    color: theme.palette.VADER,
    height: theme.typography.pxToRem(8),
    fontWeight: 900,
    fontSize: theme.typography.pxToRem(20)
  }
}));

export default function Folder({
  data,
  showMenu,
  openLink,
  onLinkClick,
  isSharingAllowed,
  interactionsBlockDueScroll
}) {
  const classes = useStyles();

  const {
    name,
    shared,
    icon,
    _id,
    updateDate,
    creationDate,
    type
    // mimeType,
    // permissions = {}
  } = data;

  const getImageComponent = src => (
    <img src={src} alt={name} className={classes.genericIcon} />
  );

  const getRenderIcon = () => {
    let svgLink = iconsDictionary.folderSVG;
    if (icon) {
      svgLink = icon;
    } else if (shared) {
      svgLink = iconsDictionary.folderSharedSVG;
    }

    return getImageComponent(svgLink);
  };

  const openPermissionsDialog = e => {
    e.preventDefault();

    if (interactionsBlockDueScroll) return;

    if (ContextMenuStore.currentState.isVisible) {
      ContextMenuActions.hideMenu();
    }

    ModalActions.shareManagement(_id, name, type);
  };

  const openContentMenu = e => {
    e.preventDefault();
    showMenu(e);
  };

  return (
    <Box data-component="folder-container" onClick={onLinkClick}>
      <Link to={openLink} onClick={onLinkClick}>
        <Typography className={classes.name}>{name}</Typography>
      </Link>
      <Typography className={classes.date}>
        {(updateDate || creationDate || 0) !== 0 ? (
          <RelativeTime timestamp={updateDate || creationDate || 0} />
        ) : (
          <span>{String.fromCharCode(8212)}</span>
        )}
      </Typography>
      <Grid container className={classes.container}>
        <Grid item xs={6}>
          <Link to={openLink} onClick={onLinkClick}>
            {getRenderIcon()}
          </Link>
        </Grid>
        <Grid item xs={6} className={classes.controlsContainer}>
          {isSharingAllowed ? (
            <Button
              onClick={openPermissionsDialog}
              onTouchEnd={openPermissionsDialog}
              className={classes.permissionsButton}
            >
              <SVG src={permissionsSVG}>
                <img src={permissionsSVG} alt="permissions" />
              </SVG>
            </Button>
          ) : null}
          <Button
            onClick={openContentMenu}
            onTouchEnd={openContentMenu}
            className={classes.menuButton}
          >
            <span className={classes.menuButtonPoint}>.</span>
            <span className={classes.menuButtonPoint}>.</span>
            <span className={classes.menuButtonPoint}>.</span>
          </Button>
        </Grid>
      </Grid>
    </Box>
  );
}

Folder.propTypes = {
  data: PropTypes.shape({
    _id: PropTypes.string,
    name: PropTypes.string,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number,
    thumbnail: PropTypes.string,
    type: PropTypes.string,
    mimeType: PropTypes.string,
    public: PropTypes.bool,
    shared: PropTypes.bool,
    icon: PropTypes.string,
    permissions: PropTypes.shape({
      canViewPublicLink: PropTypes.bool
    }).isRequired
  }).isRequired,
  showMenu: PropTypes.func.isRequired,
  openLink: PropTypes.string.isRequired,
  onLinkClick: PropTypes.func.isRequired,
  isSharingAllowed: PropTypes.bool.isRequired,
  interactionsBlockDueScroll: PropTypes.bool.isRequired
};
