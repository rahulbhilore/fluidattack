import React from "react";
import clsx from "clsx";
import PropTypes from "prop-types";
import Immutable from "immutable";
import { FormattedMessage } from "react-intl";
import { Box, Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Tooltip from "@material-ui/core/Tooltip";
import CreateIcon from "@material-ui/icons/Create";
import BookmarkIcon from "@material-ui/icons/Bookmark";
import ApplicationStore from "../../../../stores/ApplicationStore";
import MainFunctions from "../../../../libraries/MainFunctions";
import storagesBlack from "../../../../assets/images/storages/storages_black.svg";

const useStyles = makeStyles(theme => ({
  processCaption: {
    color: `${theme.palette.OBI} !important`,
    fontSize: theme.typography.body1.fontSize,
    fontWeight: "bold"
  },
  processUploadComplete: {
    fontWeight: "normal"
  },
  processUploadCanceled: {
    fontWeight: "normal",
    color: theme.palette.CLONE
  },
  optionsIcon: {
    width: "25px",
    height: "25px",
    display: "inline-block",
    margin: "0 10px",
    fontSize: "20px",
    lineHeight: "25px",
    "& > img": {
      maxWidth: "100%",
      maxHeight: "100%"
    },
    "& > svg": {
      color: theme.palette.JANGO,
      "&.disabled": {
        color: theme.palette.REY
      }
    }
  }
}));

export default function Options({ process, options }) {
  const classes = useStyles();

  if (process) {
    let additionalClass = null;
    if (process === "uploadComplete")
      additionalClass = classes.processUploadComplete;
    if (process === "canceled") additionalClass = classes.processUploadCanceled;

    return (
      <Box>
        <Typography className={clsx(classes.processCaption, additionalClass)}>
          <FormattedMessage id={process} />
        </Typography>
      </Box>
    );
  }

  const { externalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const storages = options.get("storages");
  const editor = options.get("editor");
  const noDebugLog = options.get("no_debug_log");
  const availableStorages = [];
  const unAvailableStorages = [];
  storages.map((isAvailable, storageName) =>
    isAvailable
      ? availableStorages.push(storageName)
      : unAvailableStorages.push(storageName)
  );
  return (
    <>
      {externalStoragesAvailable ? (
        <Tooltip
          placement="top"
          title={
            <>
              {availableStorages.length > 0 ? (
                <FormattedMessage
                  id="storageAvailable"
                  values={{ storageName: availableStorages.join(", ") }}
                />
              ) : null}

              {/* TODO: Enable icons instead of text? */}
              {/* {availableStorages.length > 0 ? availableStorages.map(
                  (storageName) =>
                      <Image
                        src={`images/storages/${storageName === 'kudo'
                          ? 'fluorine'
                          : storageName}-inactive.svg`}/>
                ) : null} */}
              {/* DK: Force double <br/> to make more obvious message */}
              <br />
              <br />
              {unAvailableStorages.length > 0 ? (
                <FormattedMessage
                  id="storageUnavailable"
                  values={{ storageName: unAvailableStorages.join(", ") }}
                />
              ) : null}
              {/* {unAvailableStorages.length > 0 ? unAvailableStorages.map(
                  (storageName) =>
                      <Image
                        src={`images/storages/${storageName === 'kudo'
                          ? 'fluorine'
                          : storageName}-inactive.svg`}/>
                ) : null} */}
            </>
          }
        >
          <Box className={classes.optionsIcon}>
            <img src={storagesBlack} alt="Storages availability" />
          </Box>
        </Tooltip>
      ) : null}
      <Tooltip
        placement="top"
        title={
          <FormattedMessage
            id={
              MainFunctions.forceBooleanType(editor)
                ? "userHasAccessToEditor"
                : "userHasNoAccessToEditor"
            }
          />
        }
      >
        <Box className={classes.optionsIcon}>
          <CreateIcon
            className={
              MainFunctions.forceBooleanType(editor) ? "enabled" : "disabled"
            }
          />
        </Box>
      </Tooltip>
      <Tooltip
        placement="top"
        title={
          <FormattedMessage
            id={
              MainFunctions.forceBooleanType(noDebugLog)
                ? "userLogDebugOff"
                : "userLogDebug"
            }
          />
        }
      >
        <Box className={classes.optionsIcon}>
          <BookmarkIcon
            className={
              MainFunctions.forceBooleanType(noDebugLog)
                ? "disabled"
                : "enabled"
            }
          />
        </Box>
      </Tooltip>
    </>
  );
}

Options.propTypes = {
  options: PropTypes.instanceOf(Immutable.Map).isRequired,
  process: PropTypes.string
};

Options.defaultProps = {
  process: ""
};
