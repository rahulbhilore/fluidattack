import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { IconButton, Tooltip, Typography } from "@material-ui/core";
import StorageIcons from "../../../constants/appConstants/StorageIcons";
import MainFunctions from "../../../libraries/MainFunctions";
import addStorageSVG from "../../../assets/images/AddStorage.svg";
// assets - import for AC
import addButton from "../../../assets/images/Commander/AddStorage.png";
import iGdrive from "../../../assets/images/Commander/GoogleDrive.svg";
import iBox from "../../../assets/images/Commander/Box.svg";
import iOnshape from "../../../assets/images/Commander/Onshape.svg";
import iOnedrive from "../../../assets/images/Commander/OneDrive.svg";
import iTrimble from "../../../assets/images/Commander/Trimble.svg";
import iDropbox from "../../../assets/images/Commander/Dropbox.svg";
import iWebdav from "../../../assets/images/Commander/DAV.svg";

import iGdriveLight from "../../../assets/images/storages/gdrive-active.svg";
import iBoxLight from "../../../assets/images/storages/box-active.svg";
import iOnshapeLight from "../../../assets/images/storages/onshape-active.svg";
import iOnedriveLight from "../../../assets/images/storages/onedrive-active.svg";
import iOnedriveBusinessLight from "../../../assets/images/storages/onedrivebusiness-active.svg";
import iTrimbleLight from "../../../assets/images/storages/trimble-active.svg";
import iDropboxLight from "../../../assets/images/storages/dropbox-active.svg";
import iWebdavLight from "../../../assets/images/storages/webdav-active.svg";

const iconsPerStorage = {
  dark: {
    gdrive: iGdrive,
    box: iBox,
    onshape: iOnshape,
    onshapedev: iOnshape,
    onshapestaging: iOnshape,
    onedrive: iOnedrive,
    trimble: iTrimble,
    dropbox: iDropbox,
    onedrivebusiness: iOnedrive,
    webdav: iWebdav
  },
  light: {
    gdrive: iGdriveLight,
    box: iBoxLight,
    onshape: iOnshapeLight,
    onshapedev: iOnshapeLight,
    onshapestaging: iOnshapeLight,
    onedrive: iOnedriveLight,
    trimble: iTrimbleLight,
    dropbox: iDropboxLight,
    onedrivebusiness: iOnedriveBusinessLight,
    webdav: iWebdavLight
  }
};

const useStyles = makeStyles(theme => ({
  root: {
    backgroundColor: theme.palette.LIGHT
  },
  storageCaption: {
    borderBottom: `1px solid ${theme.palette.REY}`,
    padding: theme.spacing(2)
  },
  img: {
    width: "30px",
    verticalAlign: "middle",
    margin: theme.spacing(0, 3, 0, 0)
  },
  caption: {
    display: "inline-block",
    color: theme.palette.OBI,
    fontWeight: "bold",
    fontSize: ".8rem"
  },
  images: {
    textAlign: "center"
  },
  addButton: {
    float: "right",
    padding: 0
  },
  adminConsent: {
    float: "right",
    backgroundColor: `${theme.palette.OBI} !important`,
    fontSize: ".6rem",
    color: theme.palette.LIGHT,
    marginRight: theme.spacing(1),
    padding: 0,
    height: "25px",
    width: "25px"
  },
  addIcon: {
    width: "25px",
    height: "25px"
  }
}));

export default function StorageCaption({
  storageObject,
  connectStorage,
  isCommander,
  style
}) {
  const classes = useStyles();
  const connectStorageCommander = () => {
    connectStorage(
      storageObject.name,
      encodeURIComponent(`/commander/storages?style=${style}`)
    );
  };
  const connectStorageRegular = () => {
    connectStorage(
      storageObject.name,
      null,
      MainFunctions.QueryString("scredirect")
    );
  };
  return (
    <div className={classes.storageCaption}>
      <img
        className={classes.img}
        alt={storageObject.name}
        src={
          isCommander
            ? iconsPerStorage[style][storageObject.name]
            : StorageIcons[`${storageObject.name}ActiveSVG`]
        }
      />
      <Typography variant="h3" className={classes.caption}>
        {storageObject.displayName}
      </Typography>

      <Tooltip
        placement={isCommander === true ? "bottom" : "top"}
        title={<FormattedMessage id="addAccount" />}
      >
        <IconButton
          data-component="add_storage_button"
          className={classes.addButton}
          onClick={
            isCommander === true
              ? connectStorageCommander
              : connectStorageRegular
          }
        >
          <img
            alt="Add new account"
            className={classes.addIcon}
            src={isCommander ? addButton : addStorageSVG}
          />
        </IconButton>
      </Tooltip>
      {storageObject.name === "onedrivebusiness" ? (
        <Tooltip placement="top" title={<FormattedMessage id="adminConsent" />}>
          <IconButton
            className={classes.adminConsent}
            onClick={() => {
              connectStorage(
                "odbadminconsent",
                null,
                MainFunctions.QueryString("scredirect")
              );
            }}
          >
            AC
          </IconButton>
        </Tooltip>
      ) : null}
    </div>
  );
}

StorageCaption.propTypes = {
  storageObject: PropTypes.shape({
    name: PropTypes.string.isRequired,
    displayName: PropTypes.string.isRequired
  }).isRequired,
  connectStorage: PropTypes.func.isRequired,
  isCommander: PropTypes.bool,
  style: PropTypes.string
};

StorageCaption.defaultProps = {
  isCommander: false,
  style: ""
};
