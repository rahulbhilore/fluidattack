import React, { useEffect, useRef, useState } from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Grid from "@material-ui/core/Grid";
import Container from "@material-ui/core/Container";
import { FormattedMessage } from "react-intl";
import { Typography, styled } from "@material-ui/core";
import newFolderSVG from "../../../assets/images/newfolder.svg";
import uploadSVG from "../../../assets/images/upload.svg";
import TooltipButton from "../../Toolbar/TooltipButton";
import ModalActions from "../../../actions/ModalActions";
import FileDragAndDrop from "../../Toolbar/FileDragAndDrop";
import BlocksActions from "../../../actions/BlocksActions";
import BlocksStore, { BLOCK } from "../../../stores/BlocksStore";
import MainFunctions from "../../../libraries/MainFunctions";
import userInfoStore from "../../../stores/UserInfoStore";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import { SUPPORTED_EXTENSIONS } from "../../../constants/BlocksConstants";

const UPLOAD_FOLDER_ENABLED = false;

const StyledViewOnlyMessage = styled(Typography)(({ theme }) => ({
  display: "inline-block",
  color: theme.palette.KYLO,
  fontWeight: 900,
  verticalAlign: "top",
  marginTop: "8px",
  marginLeft: "4px"
}));

const useStyles = makeStyles(theme => ({
  root: {
    margin: "20px 16px 4px 29px",
    padding: "0 0 15px 0",
    borderBottom: `1px solid ${theme.palette.BORDER_COLOR}`
  },
  button: {
    border: "none",
    boxShadow: "none",
    borderRadius: 0,
    padding: 0,
    margin: "20px 0 15px 0",
    backgroundColor: "transparent",
    "&:hover .st0": {
      fill: theme.palette.OBI
    },
    "&[disabled] .st0": {
      fill: theme.palette.REY
    }
  },
  input: {
    display: "none!important"
  },
  icon: {
    maxWidth: "32px!important",
    height: "32px!important",
    marginBottom: "-1px"
  },
  fileInput: {
    display: "none !important"
  }
}));

const checkIsOwner = entity => {
  const { ownerId, ownerType } = entity;
  let isOwner = false;
  if (ownerType === "USER" && ownerId === userInfoStore.getUserInfo("id")) {
    isOwner = true;
  } else if (ownerType === "ORG") {
    isOwner = userInfoStore.getUserInfo("company")?.isAdmin;
  } else if (ownerType === "PUBLIC") {
    isOwner = false;
  }
  return isOwner;
};

const checkIsViewOnly = entity => {
  if (!entity || Object.keys(entity).length === 0) {
    return false;
  }
  const isOwner = checkIsOwner(entity);
  if (isOwner) return false;
  const { ownerType } = entity;
  if (entity.isSharedBlocksCollection === true) {
    return true;
  }
  // public libraries shouldn't be changed manually
  if (ownerType === "PUBLIC") {
    return true;
  }

  let isViewOnly = true;
  const { shares = [] } = entity;
  if (shares.length > 0) {
    const shareInfo = shares.find(
      ({ userId }) => userId === userInfoStore.getUserInfo("id")
    );
    if (shareInfo) {
      isViewOnly = shareInfo.mode !== "editor";
    }
  }
  if (!isViewOnly) {
    const { type, libId } = entity;
    // check parent library
    if (type === BLOCK) {
      const libInfo = BlocksStore.blocksStore.getLibraryInfo(libId);
      if (libInfo) {
        return checkIsViewOnly(libInfo);
      }
    }
  }
  return isViewOnly;
};

export default function BlocksToolbar({ libraryId, currentLibraryInfo }) {
  const classes = useStyles();
  const inputRef = useRef();
  const directoryInputRef = useRef();

  const handleNewLibraryCreation = () => {
    ModalActions.createBlockLibrary();
  };

  const handleBlockUpload = e => {
    e.preventDefault();
    e.stopPropagation();
    inputRef.current.click();
  };

  const uploadHandler = e => {
    e.preventDefault();
    e.stopPropagation();
    BlocksActions.uploadMultipleBlocks(
      Array.from(e.target.files),
      libraryId,
      BlocksStore.getBlocks().map(block => block.name)
    );
  };

  const directoryHandler = e => {
    e.preventDefault();
    e.stopPropagation();
    if (e.target.files[0]?.webkitRelativePath?.length > 0) {
      const relativePath = e.target.files[0].webkitRelativePath;
      const libraryName = relativePath.substring(0, relativePath.indexOf("/"));
      BlocksActions.createBlockLibrary(
        libraryName,
        "",
        userInfoStore.getUserInfo("id"),
        "USER"
      ).then(response => {
        BlocksActions.uploadMultipleBlocks(
          Array.from(e.target.files),
          response.data.libId,
          BlocksStore.getBlocks().map(block => block.name)
        );
      });
    }
  };

  const triggerFolderUpload = e => {
    e.preventDefault();
    e.stopPropagation();
    directoryInputRef.current.click();
  };

  const readDirectoryEntries = directory =>
    new Promise(resolve => {
      const directoryReader = directory.createReader();
      directoryReader.readEntries(entries => {
        resolve({ name: directory.name, entries });
      });
    });

  const convertEntryToFile = fileEntry =>
    new Promise(resolve => {
      if (!fileEntry.file) resolve(null);
      else fileEntry.file(finalFile => resolve(finalFile));
    });

  const handleFileDrop = event => {
    event.preventDefault();
    event.stopPropagation();

    const { items } = event.dataTransfer;
    if (!libraryId) {
      let areAnyFilesInUpload = false;
      const directoryReadPromises =
        Array.from(items)
          .filter(item => {
            if (item.webkitGetAsEntry && item.webkitGetAsEntry().isDirectory) {
              return true;
            }
            areAnyFilesInUpload = true;
            return false;
          })
          .map(item => readDirectoryEntries(item.webkitGetAsEntry())) || [];
      if (areAnyFilesInUpload) {
        SnackbarUtils.alertError({ id: "cannotUploadFilesToTheRootOfBlocks" });
      }
      Promise.all(directoryReadPromises).then(results => {
        results.forEach(result => {
          BlocksActions.createBlockLibrary(
            result.name,
            "",
            userInfoStore.getUserInfo("id"),
            "USER"
          ).then(response => {
            const entriesPromises = result.entries.map(convertEntryToFile);
            Promise.all(entriesPromises).then(finalEntries => {
              BlocksActions.uploadMultipleBlocks(
                finalEntries.filter(v => v !== null),
                response.data.libId,
                BlocksStore.getBlocks().map(block => block.name)
              );
            });
          });
        });
      });
    } else {
      let areAnyDirectoriesInUpload = false;
      const filteredFiles =
        Array.from(items)
          .filter(item => {
            if (item.webkitGetAsEntry && item.webkitGetAsEntry().isDirectory) {
              areAnyDirectoriesInUpload = true;
              return false;
            }
            return true;
          })
          .map(item => item.getAsFile()) || [];
      if (areAnyDirectoriesInUpload) {
        SnackbarUtils.alertWarning({ id: "folderUploadIsntSupported" });
      }
      if (filteredFiles.length > 0) {
        BlocksActions.uploadMultipleBlocks(
          filteredFiles,
          libraryId,
          BlocksStore.getBlocks().map(block => block.name)
        );
      }
    }
  };

  const [isViewOnlyLibrary, setIsViewOnlyLibrary] = useState(
    checkIsViewOnly(currentLibraryInfo)
  );

  useEffect(() => {
    setIsViewOnlyLibrary(checkIsViewOnly(currentLibraryInfo));
  }, [currentLibraryInfo]);

  return (
    <Container maxWidth={false} className={classes.root}>
      <Grid container justifyContent="space-between">
        <Grid item lg={7} md={6} sm={4}>
          <input
            name="file"
            type="file"
            className={classes.fileInput}
            accept={SUPPORTED_EXTENSIONS.map(ext => `.${ext}`).join(",")}
            multiple
            ref={inputRef}
            onChange={uploadHandler}
          />
          {MainFunctions.isDirectoryUploadSupported() ? (
            <input
              name="directory"
              type="file"
              className={classes.fileInput}
              // directory=""
              webkitdirectory=""
              multiple
              ref={directoryInputRef}
              onChange={directoryHandler}
            />
          ) : null}
          {libraryId && !isViewOnlyLibrary ? (
            <FileDragAndDrop dropHandler={handleFileDrop} />
          ) : null}

          <Grid item>
            {!libraryId ? (
              <>
                <TooltipButton
                  disabled={false}
                  onClick={handleNewLibraryCreation}
                  tooltipTitle={<FormattedMessage id="createNewBlockLibrary" />}
                  icon={newFolderSVG}
                  id="createBlockLibraryButton"
                  dataComponent="createBlockLibraryButton"
                />
                {UPLOAD_FOLDER_ENABLED &&
                MainFunctions.isDirectoryUploadSupported() ? (
                  <TooltipButton
                    disabled={false}
                    onClick={triggerFolderUpload}
                    tooltipTitle={
                      <FormattedMessage id="uploadFolderAsBlockLibrary" />
                    }
                    icon={newFolderSVG}
                    id="uploadBlockLibraryButton"
                    dataComponent="uploadBlockLibraryButton"
                  />
                ) : null}
              </>
            ) : null}
            {libraryId ? (
              <TooltipButton
                disabled={isViewOnlyLibrary}
                onClick={handleBlockUpload}
                tooltipTitle={<FormattedMessage id="uploadNewBlock" />}
                icon={uploadSVG}
                id="uploadNewBlockButton"
                dataComponent="uploadNewBlockButton"
              />
            ) : null}
            {isViewOnlyLibrary ? (
              <StyledViewOnlyMessage>
                <FormattedMessage id="thisLibraryHasViewOnlyAccess" />
              </StyledViewOnlyMessage>
            ) : null}
          </Grid>
        </Grid>
      </Grid>
    </Container>
  );
}

BlocksToolbar.propTypes = {
  libraryId: PropTypes.string,
  currentLibraryInfo: PropTypes.shape({
    id: PropTypes.string,
    name: PropTypes.string,
    ownerType: PropTypes.string,
    isSharedBlocksCollection: PropTypes.bool
  })
};
BlocksToolbar.defaultProps = {
  libraryId: "",
  currentLibraryInfo: {}
};
