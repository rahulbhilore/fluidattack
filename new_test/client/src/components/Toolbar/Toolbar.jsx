/* eslint-disable class-methods-use-this */
/* eslint-disable no-console */
import { styled } from "@material-ui/core";
import Container from "@material-ui/core/Container";
import Grid from "@material-ui/core/Grid";
import Hidden from "@material-ui/core/Hidden";
import Typography from "@material-ui/core/Typography";
import $ from "jquery";
import PropTypes from "prop-types";
import React, { Component } from "react";
import { FormattedMessage, injectIntl } from "react-intl";
import _ from "underscore";
import { withRouter } from "react-router";
import FilesListActions from "../../actions/FilesListActions";
import ModalActions from "../../actions/ModalActions";
import TemplatesActions from "../../actions/TemplatesActions";
import {
  CUSTOM_TEMPLATES,
  PUBLIC_TEMPLATES
} from "../../constants/TemplatesConstants";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore, { CONFIG_LOADED } from "../../stores/ApplicationStore";
import FilesListStore, {
  BROWSER,
  CONTENT_LOADED,
  CONTENT_UPDATED,
  CURRENT_FOLDER_INFO_UPDATED,
  TRASH,
  UPLOAD_FINISHED
} from "../../stores/FilesListStore";
import TemplatesStore from "../../stores/TemplatesStore";
import UserInfoStore, { INFO_UPDATE } from "../../stores/UserInfoStore";
import BackToFiles from "./BackToFiles";
import FileDragAndDrop from "./FileDragAndDrop";
import FileFilterSelect from "./FileFilterSelect";
import TooltipButton from "./TooltipButton";
import TrimbleRegionSwitch from "./TrimbleRegionSwitch";
import UploadForm from "./UploadForm";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import UserInfoActions from "../../actions/UserInfoActions";
import SmartTableActions from "../../actions/SmartTableActions";
import smartTableStore from "../../stores/SmartTableStore";

import newFileSVG from "../../assets/images/newfile.svg";
import newFolderSVG from "../../assets/images/newfolder.svg";
import trimbleNewFolderSVG from "../../assets/images/trimble-new-folder.svg";
import uploadSVG from "../../assets/images/upload.svg";
import TrashButton from "./TrashButton";
import EraseAllButton from "./EraseAllButton";

let formatMessage = null;

const StyledContainer = styled(Container)(() => ({
  padding: "12px 16px 0px 29px",
  "@media (max-width: 767px)": {
    padding: 0
  }
}));

const StyledToolbarGrid = styled(Grid)(({ theme }) => ({
  borderBottom: `solid 1px ${theme.palette.BORDER_COLOR}`,
  alignItems: "center",
  height: "50px",
  paddingBottom: "12px",
  zIndex: 1000,
  "@media (max-width: 767px)": {
    height: "40px",
    padding: 0,
    paddingLeft: "10px",
    borderBottom: `none`
  }
}));

const StyledRightGrid = styled(Grid)(() => ({
  justifyContent: "end",
  alignItems: "center",
  display: "flex",
  textAlign: "right"
}));

const StyledLeftGrid = styled(Grid)(() => ({
  maxHeight: "32px"
}));

const StyledViewOnlyMessage = styled(Typography)(({ theme }) => ({
  display: "inline-block",
  color: theme.palette.KYLO,
  fontWeight: 900,
  verticalAlign: "top",
  marginTop: "8px",
  marginLeft: "4px"
}));

class Toolbar extends Component {
  static createNewFolderModal(e) {
    e.currentTarget.blur();
    // TODO: find storage-specific data
    ModalActions.createFolder(
      FilesListStore.getCurrentFolder()._id === "-1" &&
        FilesListStore.getCurrentState() === BROWSER
        ? UserInfoStore.getUserInfo("storage_config").topLevelCreateCaption
        : "createFolder"
    );
  }

  static createNewFileModal(e) {
    e.currentTarget.blur();
    ModalActions.createFile();
  }

  static switchTrash(e) {
    e.currentTarget.blur();
    e.preventDefault();
    if (e.button === 0) {
      if (FilesListStore.getCurrentState() !== TRASH) {
        FilesListActions.toggleView(
          FilesListStore.getCurrentTrashFolder().name,
          FilesListStore.getCurrentTrashFolder()._id,
          false,
          TRASH,
          true
        );
      } else {
        FilesListActions.toggleView(
          FilesListStore.getCurrentFolder().name,
          FilesListStore.getCurrentFolder()._id,
          FilesListStore.getCurrentFolder().viewOnly || false,
          BROWSER,
          true
        );
      }
    }
  }

  static propTypes = {
    isMobile: PropTypes.bool,
    startLoader: PropTypes.func,
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired,
    location: PropTypes.shape({ pathname: PropTypes.string.isRequired })
      .isRequired
  };

  static defaultProps = {
    isMobile: false,
    startLoader: () => null
  };

  constructor(props) {
    super(props);
    ({ formatMessage } = props.intl);
    this.state = {
      viewOnly: false,
      lastVisitedDirectory: "-1",
      isFolderEmpty: false
    };
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    FilesListStore.addListener(UPLOAD_FINISHED, this.onUploadFinished);
    FilesListStore.addListener(CURRENT_FOLDER_INFO_UPDATED, this.onChange);
    FilesListStore.addListener(CONTENT_LOADED, this.onContentLoaded);
    FilesListStore.addListener(CONTENT_UPDATED, this.onContentLoaded);
    ApplicationStore.addChangeListener(CONFIG_LOADED, this.onUser);
  }

  componentDidUpdate(prevProps) {
    const { location } = this.props;
    const hasLocationChanged =
      prevProps.location.pathname !== location.pathname;
    if (hasLocationChanged) this.onChange();
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    FilesListStore.removeListener(UPLOAD_FINISHED, this.onUploadFinished);
    FilesListStore.removeListener(CURRENT_FOLDER_INFO_UPDATED, this.onChange);
    FilesListStore.removeListener(CONTENT_LOADED, this.onContentLoaded);
    FilesListStore.removeListener(CONTENT_UPDATED, this.onContentLoaded);
    ApplicationStore.removeChangeListener(CONFIG_LOADED, this.onUser);
  }

  onContentLoaded = (folderId, mode) => {
    const currentFolderId =
      mode === TRASH
        ? FilesListStore.getCurrentTrashFolder()._id
        : FilesListStore.getCurrentFolder()._id;
    if (folderId === currentFolderId) {
      const folderContent = FilesListStore.getTreeData(currentFolderId) || [];
      const isCurrentFolderEmpty = folderContent.length === 0;
      const { isFolderEmpty } = this.state;
      if (isCurrentFolderEmpty !== isFolderEmpty) {
        this.setState({ isFolderEmpty: isCurrentFolderEmpty });
      }
    }
  };

  onUploadFinished = () => {
    UserInfoActions.getUserStorages();
  };

  onUser = () => {
    this.forceUpdate();
    if (ApplicationStore.isConfigLoaded()) {
      ApplicationStore.removeChangeListener(CONFIG_LOADED, this.onUser);
    }
  };

  onChange = () => {
    const currentFolder = FilesListStore.getCurrentFolder();

    this.setState({
      viewOnly: FilesListStore.getCurrentFolder().viewOnly || false,
      hideControls: FilesListStore.getCurrentFolder().isError === true,
      // we use it only for trash rn, so keep it false on change
      isFolderEmpty: false
    });

    if (currentFolder.mode === TRASH) return;

    this.setState({
      lastVisitedDirectory: currentFolder._id
    });
  };

  getLinkForSwitch = () => {
    const { storage, accountId } = FilesListStore.getCurrentFolder();

    if (FilesListStore.getCurrentState() !== TRASH)
      return `/files/trash/${storage}/${accountId}/-1`;

    const { lastVisitedDirectory } = this.state;

    const { objectId } = MainFunctions.parseObjectId(lastVisitedDirectory);

    return `/files/${storage}/${accountId}/${objectId}`;
  };

  getRenderFlags = () => {
    const pageType = MainFunctions.detectPageType();
    // Please note that for trash isFiles=true, isTrash=true
    // For files page isFiles=true, isTrash=false
    const isFiles = pageType.includes("files");
    const isTrash = pageType.includes("trash");

    const { isMobile } = this.props;

    const currentFolder = FilesListStore.getCurrentFolder();

    const { storageType } = FilesListStore.findCurrentStorage();
    const storage = MainFunctions.storageCodeToServiceName(storageType);
    const storageConfig = UserInfoStore.getStorageConfig(
      storage.toUpperCase()
    ).capabilities;

    const createFolderCaption = "createFolder";

    const { viewOnly } = this.state;

    const isServiceRoot = MainFunctions.isExternalServiceRoot(
      isFiles,
      MainFunctions.parseObjectId(currentFolder._id).objectId,
      storageType
    );

    const isUploadAvailable =
      !isServiceRoot &&
      (ApplicationStore.getApplicationSetting("trial") === false ||
        UserInfoStore.getUserInfo("isAdmin")) &&
      ((isFiles === true && isTrash === false) ||
        pageType.includes("resources")) &&
      viewOnly === false &&
      (!UserInfoStore.isFreeAccount() || storage !== "samples");

    const allowSubfolders = Object.prototype.hasOwnProperty.call(
      currentFolder,
      "allowSubfolders"
    )
      ? currentFolder.allowSubfolders
      : true;

    const isCreateFolderAvailable =
      !isServiceRoot &&
      isFiles === true &&
      isTrash === false &&
      allowSubfolders === true &&
      viewOnly === false;

    const userOptions = UserInfoStore.getUserInfo("options") || {};

    const isCreateFileAvailable =
      !isServiceRoot &&
      isFiles === true &&
      isTrash === false &&
      viewOnly === false &&
      Object.keys(userOptions).length > 0 &&
      MainFunctions.forceBooleanType(userOptions.editor) === true &&
      UserInfoStore.isFreeAccount() === false;

    const isFileFilterEnabled =
      ApplicationStore.getApplicationSetting("featuresEnabled").fileFilter ===
        true && isFiles === true;

    const isSharedWithMeDir =
      currentFolder?.wsId === "shared" || currentFolder?._id === "shared";
    const isPreparingOpen = currentFolder?.wsId === "preparing";

    return {
      storageConfig,
      isFiles,
      isTrash,
      isMobile,
      createFolderCaption,
      isUploadAvailable,
      isCreateFolderAvailable,
      isCreateFileAvailable,
      isFileFilterEnabled,
      storage,
      isSharedWithMeDir,
      isPreparingOpen
    };
  };

  uploadSimulation = e => {
    e.preventDefault();
    e.currentTarget.blur();
    if (MainFunctions.detectPageType() === "files") {
      $("#dwgupload").click();
    } else {
      let templateType = CUSTOM_TEMPLATES;
      const { pathname } = location;
      if (pathname.includes("resources/templates/public")) {
        templateType = PUBLIC_TEMPLATES;
      }
      ModalActions.uploadTemplate(templateType);
    }
  };

  handleUpload = (e, files) => {
    SmartTableActions.scrollToTop(smartTableStore.getTableIdByType("files"));
    FilesListActions.uploadEntities(files || e.target.files);
  };

  handleFileDrop = event => {
    event.preventDefault();
    event.stopPropagation();
    if (MainFunctions.detectPageType() === "files") {
      SmartTableActions.scrollToTop(smartTableStore.getTableIdByType("files"));
      FilesListActions.uploadEntities(event);
    } else {
      let templateType = CUSTOM_TEMPLATES;
      const { pathname } = location;
      if (pathname.includes("resources/templates/public")) {
        templateType = PUBLIC_TEMPLATES;
      }
      const { items } = event.dataTransfer;
      let areAnyFoldersInUpload = false;
      let files = Array.from(items)
        .filter(item => {
          if (item.webkitGetAsEntry && item.webkitGetAsEntry().isDirectory) {
            areAnyFoldersInUpload = true;
            return false;
          }
          return true;
        })
        .map(i => i.getAsFile());
      const names = files.map(({ name }) => name);
      const duplicateNames = _.intersection(
        TemplatesStore.getTemplates(templateType)
          .toJS()
          .map(({ name }) => name),
        names
      );
      files = files.filter(({ name }) => !duplicateNames.includes(name));
      const uploadPromises = files.map(
        file =>
          new Promise(resolve => {
            TemplatesActions.uploadTemplate(templateType, file)
              .then(() => {
                resolve();
              })
              .catch(err => {
                resolve({ name: file.name, error: err.message });
              });
          })
      );
      Promise.all(uploadPromises).then(results => {
        const messageEntityType = results.length > 1 ? "templates" : "template";
        const unsuccessfulUploads = results.filter(v => !!v);

        const finalMessages = [];

        let messageType = "success";
        if (areAnyFoldersInUpload) {
          messageType = "warning";
          finalMessages.push({ id: "folderUploadIsntSupported" });
        }
        if (duplicateNames.length) {
          messageType = "warning";
          finalMessages.push({
            id: "duplicateNameUpload",
            duplicates: duplicateNames.join("\r\n")
          });
        }
        if (unsuccessfulUploads.length > 0) {
          messageType = "warning";

          finalMessages.push(
            unsuccessfulUploads.map(uploadEntry => ({
              id: "uploadingFileError",
              name: uploadEntry.name,
              error: uploadEntry.error
            }))
          );
          if (unsuccessfulUploads.length === results.length) {
            messageType = "error";
          }
        }

        if (results.length > 0) {
          finalMessages.push({
            id:
              results.length === 1
                ? "successfulUploadSingle"
                : "successfulUploadMultiple",
            type: <FormattedMessage id={messageEntityType} />
          });
        }

        switch (messageType) {
          case "success":
            SnackbarUtils.alertOk(finalMessages);
            break;
          case "warning":
            SnackbarUtils.alertWarning(finalMessages);
            break;
          default:
            SnackbarUtils.alertError(finalMessages);
            break;
        }
      });
    }
  };

  render() {
    const pageType = MainFunctions.detectPageType();
    const isTemplate = pageType.includes("resources");

    const {
      storageConfig,
      isFiles,
      isTrash,
      isMobile,
      createFolderCaption,
      isUploadAvailable,
      isCreateFolderAvailable,
      isCreateFileAvailable,
      isFileFilterEnabled,
      storage,
      isSharedWithMeDir,
      isPreparingOpen
    } = this.getRenderFlags();

    if (isTemplate !== true && Object.keys(storageConfig).length === 0) {
      return null;
    }

    const { hideControls, viewOnly } = this.state;
    if (hideControls === true) {
      return (
        <Hidden xsDown smDown={isMobile}>
          <StyledContainer maxWidth={false} />
        </Hidden>
      );
    }

    const { startLoader } = this.props;
    const fileFilter = <FileFilterSelect isVisible={isFileFilterEnabled} />;

    return (
      <StyledContainer maxWidth={false}>
        {isUploadAvailable ? (
          <FileDragAndDrop dropHandler={this.handleFileDrop} />
        ) : null}

        {isUploadAvailable ? <UploadForm onChange={this.handleUpload} /> : null}

        <StyledToolbarGrid container justifyContent="space-between">
          <Grid item lg={7} md={6} sm={4}>
            {isFiles === true && isTrash === true ? (
              <BackToFiles to={this.getLinkForSwitch} />
            ) : null}
            <StyledLeftGrid item>
              {/* Upload file */}
              {!isTrash ? (
                <TooltipButton
                  onClick={this.uploadSimulation}
                  tooltipTitle={
                    <FormattedMessage
                      id="uploadObject"
                      values={{
                        type: formatMessage({
                          id: isTemplate ? "template" : "drawing"
                        })
                      }}
                    />
                  }
                  id="uploadFileButton"
                  disabled={!isUploadAvailable}
                  icon={uploadSVG}
                  dataComponent="upload-file-button"
                />
              ) : null}

              {isFiles && !isTrash ? (
                <>
                  {/* Create folder */}
                  <TooltipButton
                    onClick={Toolbar.createNewFolderModal}
                    tooltipTitle={<FormattedMessage id={createFolderCaption} />}
                    disabled={!isCreateFolderAvailable}
                    icon={
                      storage === "trimble" &&
                      FilesListStore.getCurrentFolder()._id.indexOf("+-1") !==
                        -1
                        ? trimbleNewFolderSVG
                        : newFolderSVG
                    }
                    id="createFolderButton"
                    dataComponent="create-folder-button"
                  />

                  {/* Create file */}
                  <TooltipButton
                    onClick={Toolbar.createNewFileModal}
                    tooltipTitle={<FormattedMessage id="createDrawing" />}
                    disabled={!isCreateFileAvailable}
                    icon={newFileSVG}
                    id="createFileButton"
                    dataComponent="create-file-button"
                  />
                </>
              ) : null}
              {viewOnly &&
              !isSharedWithMeDir &&
              !isTrash &&
              !isPreparingOpen ? (
                <StyledViewOnlyMessage>
                  <FormattedMessage id="thisFolderHasViewOnlyAccess" />
                </StyledViewOnlyMessage>
              ) : null}
            </StyledLeftGrid>
          </Grid>
          <StyledRightGrid item lg={5} md={6} sm={8}>
            {isFiles === true &&
            isTrash === false &&
            storageConfig.trash.isAvailable ? (
              <TrashButton
                messageId="deletedFiles"
                href={this.getLinkForSwitch()}
              />
            ) : null}
            {isTrash && storageConfig.trash.eraseAll && (
              <EraseAllButton
                messageId="eraseFiles"
                onClick={ModalActions.confirmEraseFiles}
              />
            )}
            <Hidden xsDown>{fileFilter}</Hidden>
          </StyledRightGrid>
        </StyledToolbarGrid>
        <Grid container>
          <Hidden smUp>{fileFilter}</Hidden>
          <TrimbleRegionSwitch
            startLoader={startLoader}
            isVisible={isFiles}
            formatMessage={formatMessage}
          />
        </Grid>
      </StyledContainer>
    );
  }
}

export default withRouter(injectIntl(Toolbar));
