import React from "react";
import PropTypes from "prop-types";
import { Button, Grid } from "@material-ui/core";
import { makeStyles } from "@material-ui/core/styles";
import { Link } from "react-router";
import SVG from "react-inlinesvg";
import Typography from "@material-ui/core/Typography";
import Box from "@material-ui/core/Box";
import RelativeTime from "../../../../RelativeTime/RelativeTime";
import Thumbnail from "../../../../Thumbnail";
import ModalActions from "../../../../../actions/ModalActions";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import iconsDictionary from "../../../../../constants/appConstants/ObjectIcons";
import MainFunctions from "../../../../../libraries/MainFunctions";
import ContextMenuStore from "../../../../../stores/ContextMenuStore";
import ContextMenuActions from "../../../../../actions/ContextMenuActions";

import permissionsSVG from "../../../../../assets/images/permissions.svg";
import publicLinkSVG from "../../../../../assets/images/publicLink.svg";

const useStyles = makeStyles(theme => ({
  name: {
    color: theme.palette.VADER,
    fontSize: theme.typography.pxToRem(12),
    fontWeight: 600,
    marginLeft: theme.typography.pxToRem(4),
    overflowX: "hidden",
    whiteSpace: "nowrap"
  },
  date: {
    color: theme.palette.VADER,
    fontSize: theme.typography.pxToRem(12),
    marginLeft: theme.typography.pxToRem(4)
  },
  publicIcon: {
    marginRight: "5px",
    width: "20px",
    height: "20px",
    verticalAlign: "middle"
  },
  controlsBlock: {
    marginTop: theme.typography.pxToRem(32),
    marginLeft: theme.typography.pxToRem(4)
  },
  genericIcon: {
    height: theme.typography.pxToRem(100)
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
      marginTop: theme.typography.pxToRem(-35)
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

export default function FileOneCol({
  data,
  width,
  showMenu,
  openLink,
  onLinkClick,
  isSharingAllowed,
  interactionsBlockDueScroll
  // process
}) {
  const classes = useStyles();
  const {
    name,
    updateDate,
    creationDate,
    _id,
    thumbnail,
    type,
    isShortcut,
    shortcutInfo,
    mimeType,
    public: isPublic,
    permissions = {}
  } = data;

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

  const getRenderIcon = () => {
    const ext = MainFunctions.getExtensionFromName(name);

    if (isShortcut) {
      return (
        <img
          src={
            shortcutInfo.type.endsWith("folder")
              ? iconsDictionary.folderShortcutSVG
              : iconsDictionary.fileShortcutSVG
          }
          alt={name}
          className={classes.genericIcon}
        />
      );
    }

    if (UserInfoStore.findApp(ext, mimeType) === "xenon" && thumbnail) {
      return (
        <Thumbnail
          src={thumbnail}
          fileId={_id}
          width={width / 3 + 10}
          height={100}
        />
      );
    }

    const svgType = UserInfoStore.getIconClassName(ext, type, name, mimeType);
    // just in case, but we should
    // always be able to get from the dictionary.
    let svgLink = `images/icons/${svgType}.svg`;
    if (
      Object.prototype.hasOwnProperty.call(iconsDictionary, `${svgType}SVG`)
    ) {
      svgLink = iconsDictionary[`${svgType}SVG`];
    }

    return <img src={svgLink} alt={name} className={classes.genericIcon} />;
  };

  let isPublicAccessAvailable =
    isPublic === true && permissions.canViewPublicLink;
  const userOptions = UserInfoStore.getUserInfo("options");
  if (userOptions.sharedLinks === false) {
    isPublicAccessAvailable = false;
  }

  const processMarker = null;

  // if (process && Object.keys(process).length > 0) {
  //   if (process.value || process.type) {
  //     processMarker = (
  //       <div>
  //         <FormattedMessage id={process.type} />
  //         <span className={classes.process}>
  //           {process.value && !_.isNaN(parseFloat(process.value)) ? (
  //             `${
  //               parseFloat(process.value) > 99.99
  //                 ? 99.99
  //                 : parseFloat(process.value)
  //             }%`
  //           ) : (
  //             <FormattedMessage id={process.type} />
  //           )}
  //         </span>
  //       </div>
  //     );
  //   }
  // }

  return (
    <Box data-component="file-one-col-container" onClick={onLinkClick}>
      <Grid container>
        <Grid item xs={5}>
          {getRenderIcon()}
        </Grid>
        <Grid item xs={7}>
          <Grid item xs={12}>
            <Link
              to={openLink}
              onClick={onLinkClick}
              data-component="file-one-col-link"
            >
              <Typography className={classes.name}>{name}</Typography>
            </Link>
            <Typography className={classes.date}>
              {(updateDate || creationDate || 0) !== 0 ? (
                <RelativeTime timestamp={updateDate || creationDate || 0} />
              ) : (
                <span>{String.fromCharCode(8212)}</span>
              )}
            </Typography>
          </Grid>
          <Grid item xs={12} className={classes.controlsBlock}>
            {isPublicAccessAvailable ? (
              <img
                className={classes.publicIcon}
                src={publicLinkSVG}
                alt="View only link available"
              />
            ) : null}
            {processMarker}
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
      </Grid>
    </Box>
  );
}

FileOneCol.propTypes = {
  data: PropTypes.shape({
    _id: PropTypes.string,
    name: PropTypes.string,
    updateDate: PropTypes.number,
    creationDate: PropTypes.number,
    thumbnail: PropTypes.string,
    type: PropTypes.string,
    isShortcut: PropTypes.bool,
    shortcutInfo: PropTypes.shape({
      targetId: PropTypes.string,
      type: PropTypes.string,
      mimeType: PropTypes.string
    }),
    mimeType: PropTypes.string,
    public: PropTypes.bool,
    permissions: PropTypes.shape({
      canViewPublicLink: PropTypes.bool
    }).isRequired
  }).isRequired,
  showMenu: PropTypes.func.isRequired,
  openLink: PropTypes.string.isRequired,
  onLinkClick: PropTypes.func.isRequired,
  width: PropTypes.number.isRequired,
  isSharingAllowed: PropTypes.bool.isRequired,
  interactionsBlockDueScroll: PropTypes.bool.isRequired
};
