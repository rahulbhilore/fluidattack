import React, { useEffect, useState } from "react";
import propTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Typography } from "@material-ui/core";
import { FormattedMessage } from "react-intl";
import VersionControlActions from "../../../../actions/VersionControlActions";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListActions from "../../../../actions/FilesListActions";

const useStyles = makeStyles(theme => ({
  holder: {
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 9999
  },
  root: {
    backgroundColor: "rgba(0, 0, 0, 0.7)",
    border: `dotted 2px ${theme.palette.KYLO}`,
    position: "absolute",
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 9999,
    justifyContent: "center",
    alignItems: "center",
    display: "flex",
    "& *": {
      pointerEvents: "none"
    }
  },
  text: {
    fontSize: theme.typography.pxToRem(14),
    color: theme.palette.LIGHT
  }
}));

export default function DragNDrop({ fileId }) {
  const classes = useStyles();
  const [isVisible, setVisible] = useState(false);
  const isDragElementCorrect = event => {
    if (!event.dataTransfer.items.length) return false;

    return event.dataTransfer.types.find(
      item => item.toLowerCase() === `files`
    );
  };
  const dragEnter = event => {
    if (isDragElementCorrect(event)) {
      event.preventDefault();
      event.stopPropagation();
      event.dataTransfer.dropEffect = "copy";
      setVisible(true);
    }
  };
  const leaveDrag = () => {
    setVisible(false);
  };
  const uploadVersion = event => {
    event.preventDefault();
    event.stopPropagation();
    const { items } = event.dataTransfer;
    if (items.length > 0) {
      try {
        const fileData = items[0].getAsFile();

        const uploadFunc = () => {
          VersionControlActions.uploadVersion(fileId, fileData);
        };

        // save file if needed
        if (MainFunctions.detectPageType() === "file") {
          VersionControlActions.saveBeforeUpload();
          FilesListActions.saveFileInXenon().finally(() => {
            VersionControlActions.saveBeforeUploadDone();
            uploadFunc();
          });
        } else {
          uploadFunc();
        }
      } catch (ex) {
        // eslint-disable-next-line no-console
        console.error("Exception on uploading file");
      }
    }
    setVisible(false);
  };
  useEffect(() => {
    const dialog = document.querySelector("div[role='dialog']");
    if (dialog) {
      dialog.addEventListener("dragover", dragEnter);
      return () => {
        dialog.removeEventListener("dragover", dragEnter);
      };
    }
    return () => null;
  }, []);
  if (!isVisible) return null;
  return (
    <div
      className={classes.root}
      onDragLeave={leaveDrag}
      onDrop={uploadVersion}
    >
      <Typography className={classes.text}>
        <FormattedMessage id="dropHereToUpload" />
      </Typography>
    </div>
  );
}

DragNDrop.propTypes = {
  fileId: propTypes.string.isRequired
};
