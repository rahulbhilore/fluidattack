import React, { useEffect, useRef, useState } from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import Button from "@material-ui/core/Button";
import { CircularProgress } from "@mui/material";
import _ from "underscore";
import { Grid } from "@material-ui/core";
import Tooltip from "@material-ui/core/Tooltip";
import { makeStyles } from "@material-ui/core/styles";
import { FormattedMessage } from "react-intl";
import VersionControlActions from "../../../../actions/VersionControlActions";
import ApplicationActions from "../../../../actions/ApplicationActions";
import ModalActions from "../../../../actions/ModalActions";
import VersionControlStore from "../../../../stores/VersionControlStore";
import MainFunctions from "../../../../libraries/MainFunctions";
import FilesListActions from "../../../../actions/FilesListActions";
import FilesListStore from "../../../../stores/FilesListStore";
import { supportedApps } from "../../../../stores/UserInfoStore";
import SnackbarUtils from "../../../Notifications/Snackbars/SnackController";
import * as VersionControlConstants from "../../../../constants/VersionControlConstants";

const useStyles = makeStyles(theme => ({
  root: {
    border: `1px solid ${theme.palette.OBI}`,
    padding: "6px 12px",
    minWidth: 0,
    textTransform: "uppercase",
    borderRadius: 0,
    marginRight: "8px"
  },
  emptyButton: {
    backgroundColor: theme.palette.LIGHT,
    color: theme.palette.OBI,
    "&:hover": {
      backgroundColor: theme.palette.GRAY_BACKGROUND
    },
    "&:disabled": {
      color: theme.palette.REY
    }
  },
  filledButton: {
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    "&:hover": {
      backgroundColor: theme.palette.OBI
    },
    "&:disabled": {
      color: theme.palette.REY
    }
  },
  input: {
    display: "none !important"
  },
  circular: {
    color: theme.palette.LIGHT,
    position: "relative",
    marginRight: "5px"
  }
}));

export default function ControlButtons({
  selectedVersions,
  fileId,
  folderId,
  onButtonAction,
  fileName
}) {
  const inputRef = useRef(null);
  const classes = useStyles();
  let selectedVersionsInfo = null;

  const [savingForPromote, setSavingForPromote] = useState(false);
  const [savingForUpload, setSavingForUpload] = useState(false);
  const startSaveBeforeUpload = () => setSavingForUpload(true);
  const stopSaveBeforeUpload = () => setSavingForUpload(false);

  useEffect(() => {
    VersionControlStore.addChangeListener(
      VersionControlConstants.SAVE_BEFORE_UPLOAD,
      startSaveBeforeUpload
    );
    VersionControlStore.addChangeListener(
      VersionControlConstants.SAVE_BEFORE_UPLOAD_DONE,
      stopSaveBeforeUpload
    );

    return () => {
      VersionControlStore.removeChangeListener(
        VersionControlConstants.SAVE_BEFORE_UPLOAD,
        startSaveBeforeUpload
      );
      VersionControlStore.removeChangeListener(
        VersionControlConstants.SAVE_BEFORE_UPLOAD_DONE,
        stopSaveBeforeUpload
      );
    };
  }, []);

  const promoteHandler = () => {
    const promoteFunc = () => {
      if (selectedVersions.length !== 1) return;
      onButtonAction();
      VersionControlActions.promote(fileId, _.first(selectedVersions));
    };

    // save file if needed
    if (MainFunctions.detectPageType() === "file") {
      setSavingForPromote(true);
      FilesListActions.saveFileInXenon().finally(() => {
        setSavingForPromote(false);
        promoteFunc();
      });
    } else {
      promoteFunc();
    }
  };

  const isLastVersionSelected = () => {
    const latestVersion = VersionControlStore.getLatestVersion();

    if (!latestVersion) return false;

    return !!selectedVersionsInfo.find(
      elem => elem?.creationTime === latestVersion?.creationTime
    );
  };

  const openHandler = () => {
    if (selectedVersions.length !== 1) return;

    if (isLastVersionSelected()) {
      // https://graebert.atlassian.net/browse/XENON-50311
      // https://graebert.atlassian.net/browse/XENON-52644
      // if URL is the same - version won't be opened
      // so emit event to reload with the same rights.
      if (
        location.pathname === `/file/${fileId}` &&
        location.search.indexOf(`versionId`) === -1
      ) {
        FilesListActions.reloadDrawing(
          FilesListStore.getCurrentFile().viewFlag,
          true
        );
      } else {
        ApplicationActions.changePage(`/file/${fileId}`);
      }
    } else
      ApplicationActions.changePage(
        `/file/${fileId}?versionId=${_.first(selectedVersions)}`
      );

    ModalActions.hide();
  };

  const downloadHandler = () => {
    if (selectedVersions.length === 0) return;

    selectedVersions.forEach(versionId => {
      const { customName, size } =
        VersionControlStore.getVersionInfo(versionId);
      VersionControlActions.downloadVersionViaStream(fileId, versionId, size)
        .then(blob => {
          const clearFileName = fileName.replace(/\.[^/.]+$/, "");
          const extension = fileName.match(/[^\\]*\.(\w+)$/)[1];
          MainFunctions.downloadBlobAsFile(
            blob,
            `${clearFileName}_${customName}.${extension}`
          );
        })
        .catch(err => {
          SnackbarUtils.alertError(err.message);
        });
    });
  };

  const deleteHandler = () => {
    if (selectedVersions.length === 0) return;
    onButtonAction();
    selectedVersions.forEach(versionId => {
      VersionControlActions.remove(fileId, versionId);
    });
  };

  const saveAsHandler = () => {
    ModalActions.saveVersionAs(
      fileId,
      _.first(selectedVersions),
      folderId,
      fileName
    );
  };

  if (selectedVersions.length !== 0) {
    selectedVersionsInfo = selectedVersions.map(elem =>
      VersionControlStore.getVersionInfo(elem)
    );
  }

  const handleFileUpload = (event, eventFiles) => {
    const files = eventFiles || event.target.files;
    const allowedExtensions = supportedApps.xenon;
    const fileExtension = MainFunctions.getExtensionFromName(files[0].name);
    const isValidDrawing = allowedExtensions.includes(fileExtension);

    if (!isValidDrawing) {
      SnackbarUtils.alertError({ id: "wrongFileType" });
      return;
    }

    const uploadFunc = () => {
      VersionControlActions.uploadVersion(fileId, files[0]);
    };

    // save file
    if (MainFunctions.detectPageType() === "file") {
      setSavingForUpload(true);
      FilesListActions.saveFileInXenon().finally(() => {
        setSavingForUpload(false);
        uploadFunc();
      });
    } else {
      uploadFunc();
    }
  };

  const uploadClick = () => {
    if (!inputRef.current) return;
    inputRef.current.click();
  };

  return (
    <>
      <Grid item>
        <input
          id="versions-file-input"
          name="file"
          type="file"
          className={classes.input}
          accept={`${supportedApps.xenon
            .map(v => `.${v}`)
            .join(",")},application/acad,image/vnd.dwg,image/vnd.dwt`}
          ref={inputRef}
          onChange={handleFileUpload}
          data-component="versions-file-input"
        />
        <Tooltip
          placement="top"
          title={<FormattedMessage id="deleteSelectedVersions" />}
        >
          <span>
            <Button
              data-component="deleteVersion"
              className={clsx(classes.root, classes.emptyButton)}
              disabled={
                selectedVersionsInfo &&
                !!selectedVersionsInfo.find(
                  elem => !elem?.permissions?.canDelete
                )
              }
              onClick={deleteHandler}
            >
              <FormattedMessage id="delete" />
            </Button>
          </span>
        </Tooltip>
        <Tooltip
          placement="top"
          title={<FormattedMessage id="saveVersionAsFile" />}
        >
          <span>
            <Button
              data-component="saveVersionAsFile"
              className={clsx(classes.root, classes.emptyButton)}
              disabled={
                selectedVersions.length !== 1 ||
                (selectedVersionsInfo &&
                  selectedVersionsInfo.find(
                    elem => !elem?.permissions?.isDownloadable
                  ))
              }
              onClick={saveAsHandler}
            >
              <FormattedMessage id="saveNewFile" />
            </Button>
          </span>
        </Tooltip>
        <Tooltip
          placement="top"
          title={<FormattedMessage id="uploadNewVersion" />}
        >
          <span>
            <Button
              data-component="uploadNewVersion"
              className={clsx(classes.root, classes.emptyButton)}
              onClick={uploadClick}
            >
              {savingForUpload ? (
                <CircularProgress
                  size={11}
                  sx={{ color: "#124daf" }}
                  className={clsx(classes.circular)}
                />
              ) : null}
              <FormattedMessage
                id={savingForUpload ? "savingInProgress" : "upload"}
              />
            </Button>
          </span>
        </Tooltip>
        <Tooltip
          placement="top"
          title={<FormattedMessage id="downloadSelectedVersions" />}
        >
          <span>
            <Button
              data-component="downloadSelectedVersions"
              className={clsx(classes.root, classes.emptyButton)}
              disabled={
                selectedVersionsInfo &&
                selectedVersionsInfo.find(
                  elem => !elem?.permissions?.isDownloadable
                )
              }
              onClick={downloadHandler}
            >
              <FormattedMessage id="download" />
            </Button>
          </span>
        </Tooltip>
      </Grid>
      <Grid item>
        <Tooltip
          placement="top"
          title={<FormattedMessage id="promoteSelectedVersion" />}
        >
          <span>
            <Button
              data-component="promoteSelectedVersion"
              className={clsx(classes.root, classes.filledButton)}
              disabled={
                selectedVersions.length !== 1 ||
                (selectedVersions.length === 1 &&
                  !_.first(selectedVersionsInfo)?.permissions?.canPromote)
              }
              onClick={promoteHandler}
            >
              {savingForPromote ? (
                <CircularProgress
                  size={11}
                  sx={{ color: "#FFFFFF" }}
                  className={clsx(classes.circular)}
                />
              ) : null}
              <FormattedMessage
                id={savingForPromote ? "savingInProgress" : "promote"}
              />
            </Button>
          </span>
        </Tooltip>
        <Tooltip placement="top" title={<FormattedMessage id="openVersion" />}>
          <span>
            <Button
              data-component="openVersion"
              className={clsx(classes.root, classes.filledButton)}
              disabled={
                selectedVersions.length !== 1 ||
                (selectedVersionsInfo &&
                  !selectedVersionsInfo.find(
                    elem => elem?.permissions?.isDownloadable
                  ))
              }
              onClick={openHandler}
            >
              <FormattedMessage id="open" />
            </Button>
          </span>
        </Tooltip>
      </Grid>
    </>
  );
}

ControlButtons.propTypes = {
  selectedVersions: PropTypes.arrayOf(PropTypes.string).isRequired,
  fileId: PropTypes.string.isRequired,
  folderId: PropTypes.string.isRequired,
  onButtonAction: PropTypes.func.isRequired,
  fileName: PropTypes.string.isRequired
};
