import { withStyles } from "@material-ui/core";
import Immutable, { List } from "immutable";
import propTypes from "prop-types";
import React, { Component } from "react";
import { browserHistory } from "react-router";
import { SortDirection } from "react-virtualized";
import _ from "underscore";
import ApplicationActions from "../../../actions/ApplicationActions";
import FilesListActions from "../../../actions/FilesListActions";
import ModalActions from "../../../actions/ModalActions";
import SmartTableActions from "../../../actions/SmartTableActions";
import UserInfoActions from "../../../actions/UserInfoActions";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import StorageContext from "../../../context/StorageContext";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import FilesListStore, {
  CHECK_NEW_FILES_PAGE,
  CONTENT_LOADED,
  CONTENT_UPDATED,
  CURRENT_FOLDER_INFO_UPDATED,
  MODE_UPDATED,
  PATH_LOADED,
  SORT_REQUIRED,
  STORAGE_RECONNECT_REQUIRED
} from "../../../stores/FilesListStore";
import modalStore from "../../../stores/ModalStore";
import ProcessStore from "../../../stores/ProcessStore";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGES_UPDATE
} from "../../../stores/UserInfoStore";
import FetchAbortsControl, {
  FOLDERS as REQUEST_FOLDERS,
  TRASH as REQUEST_TRASH
} from "../../../utils/FetchAbortsControl";
import Requests from "../../../utils/Requests";
import Breadcrumbs from "../../Breadcrumbs/Breadcrumbs";
import Footer from "../../Footer/Footer";
import NotificationBarSpacer from "../../Header/NotificationBar/NotificationBarSpacer";
import NotificationBar from "../../Header/NotificationBar/NotificationBar";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import RecentFiles from "../../RecentFiles/RecentFiles";
import Sidebar from "../../Sidebar/Sidebar";
import SmartGrid from "../../SmartGrid/SmartGrid";
import SmartTable from "../../SmartTable/SmartTable";
import Toolbar from "../../Toolbar/Toolbar";
import ToolbarSpacer from "../../ToolbarSpacer";
import ReconnectMessage from "./ReconnectMessage";

import Access from "../../SmartTable/tables/files/Access";
import Modified from "../../SmartTable/tables/files/Modified";
import Name from "../../SmartTable/tables/files/Name";
import Size from "../../SmartTable/tables/files/Size";

import WebsocketStore, {
  STORAGE_DISABLED
} from "../../../stores/WebsocketStore";
import FileEntity from "../../SmartGrid/grids/files/FileEntity";
import {
  sortByAccess,
  sortByModification,
  sortByName,
  sortBySize
} from "../../../utils/FileSort";

const columns = new List([
  { dataKey: "name", label: "name", width: 0.5 },
  { dataKey: "access", label: "access", width: 0.1 },
  { dataKey: "modified", label: "modified", width: 0.2 },
  { dataKey: "size", label: "size", width: 0.2 }
]);

const presentation = new Immutable.Map({
  name: Name,
  access: Access,
  modified: Modified,
  size: Size
});

let sortDirection = SortDirection.ASC;

const customSorts = {
  name: (a, b) => sortByName(a.toJS(), b.toJS(), sortDirection),
  access: (a, b) => sortByAccess(a.toJS(), b.toJS(), sortDirection),
  modified: (a, b) => sortByModification(a.toJS(), b.toJS(), sortDirection),
  size: (a, b) => sortBySize(a.toJS(), b.toJS(), sortDirection)
};

const styles = theme => ({
  box: {
    padding: theme.spacing(2)
  },
  table: {
    "& .ReactVirtualized__Table__headerRow .ReactVirtualized__Table__headerColumn":
      {
        fontSize: theme.typography.pxToRem(11)
      },
    "& .ReactVirtualized__Table__sortableHeaderIcon": {
      width: "22px",
      height: "22px",
      verticalAlign: "middle"
    },
    "& .ReactVirtualized__Table__Grid .noDataRow": {
      color: theme.palette.JANGO
    }
  }
});

class FileLoader extends Component {
  static propTypes = {
    location: propTypes.shape({
      pathname: propTypes.string.isRequired
    }).isRequired,
    params: propTypes.shape({
      account: propTypes.string,
      storage: propTypes.string,
      id: propTypes.string,
      folder: propTypes.string,
      fileId: propTypes.string
    }).isRequired,
    classes: propTypes.objectOf(propTypes.string).isRequired
  };

  constructor(props) {
    super(props);

    const { location } = props;
    const isTrash = location.pathname.includes("trash");
    this.state = {
      currentObjectId: "",
      currentFolderName: "",
      isTrash,
      isReconnectRequired: false,
      isLoading: true,
      tableContent: new List(),
      isLazy: false,
      isSortRequired: false,
      isGrid: MainFunctions.isMobileDevice(),
      countOfColumns: null
    };
  }

  componentDidMount() {
    const { location } = this.props;
    const { pathname } = location;
    if (pathname.includes("unsubscribe")) {
      this.checkUnsubscribe();
    }

    // listeners
    FilesListStore.addEventListener(
      CHECK_NEW_FILES_PAGE,
      this.checkNewFilesPage
    );
    FilesListStore.addEventListener(MODE_UPDATED, this.onModeChanged);
    FilesListStore.addEventListener(CONTENT_LOADED, this.onContentLoaded);
    FilesListStore.addEventListener(CONTENT_UPDATED, this.onContentUpdated);
    FilesListStore.addEventListener(SORT_REQUIRED, this.onSortRequired);
    FilesListStore.addEventListener(PATH_LOADED, this.onPathChanged);
    FilesListStore.addEventListener(
      CURRENT_FOLDER_INFO_UPDATED,
      this.onFolderInfoUpdate
    );
    FilesListStore.addEventListener(
      STORAGE_RECONNECT_REQUIRED,
      this.onReconnectRequired
    );
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.onStoragesUpdate);
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    WebsocketStore.addEventListener(STORAGE_DISABLED, this.onStorageDisabled);

    this.normalizeURL();

    window.addEventListener("resize", this.onResize);
    this.onResize();
    this.countGridColumns();
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { location } = this.props;
    const oldPathname = location.pathname;
    const newPathname = nextProps.location.pathname;
    const { isSortRequired } = this.state;
    if (oldPathname !== newPathname || isSortRequired) return true;
    const stringifiedState = JSON.stringify(this.state);
    const stringifiedNewState = JSON.stringify(nextState);
    return stringifiedNewState !== stringifiedState;
  }

  componentDidUpdate(prevProps) {
    const { location } = this.props;
    const { location: oldLocation } = prevProps;
    if (
      location.pathname.includes("share") &&
      !location.pathname.includes("shared")
    ) {
      ModalActions.shareManagement(
        location.pathname.substr(
          location.pathname.indexOf("share") + "share/".length
        )
      );
    }
    const { params } = this.props;
    const { account, folder, storage, id = "-1" } = params;
    const { params: oldParams } = prevProps;
    const {
      account: oldAccount,
      folder: oldFolder,
      storage: oldStorage,
      id: oldId = "-1"
    } = oldParams;
    const isTrash = location.pathname.includes("trash");
    const isPreviousTrash = oldLocation.pathname.includes("trash");
    if (
      isTrash !== isPreviousTrash ||
      `${account}+${storage}` !== `${oldAccount}+${oldStorage}`
    ) {
      FilesListActions.resetPath();
    }
    if (
      isTrash !== isPreviousTrash ||
      `${account}+${storage}+${folder}+${id}` !==
        `${oldAccount}+${oldStorage}+${oldFolder}+${oldId}`
    ) {
      this.checkReconnect(false).then(() => {
        this.setState({ isLoading: true, tableContent: new Immutable.List() });
      });
    }
    this.normalizeURL();
  }

  componentWillUnmount() {
    FilesListStore.removeEventListener(
      CHECK_NEW_FILES_PAGE,
      this.checkNewFilesPage
    );
    FilesListStore.removeEventListener(MODE_UPDATED, this.onModeChanged);
    FilesListStore.removeEventListener(CONTENT_LOADED, this.onContentLoaded);
    FilesListStore.removeEventListener(PATH_LOADED, this.onPathChanged);
    FilesListStore.removeEventListener(
      CURRENT_FOLDER_INFO_UPDATED,
      this.onFolderInfoUpdate
    );
    FilesListStore.removeEventListener(
      STORAGE_RECONNECT_REQUIRED,
      this.onReconnectRequired
    );
    FilesListStore.removeEventListener(CONTENT_UPDATED, this.onContentUpdated);
    FilesListStore.removeEventListener(SORT_REQUIRED, this.onSortRequired);
    WebsocketStore.removeEventListener(
      STORAGE_DISABLED,
      this.onStorageDisabled
    );
    FilesListActions.resetPath();
    window.removeEventListener("popstate", this.handleHistoryStateChange);
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.onStoragesUpdate);
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    window.removeEventListener("resize", this.onResize);
  }

  onStorageDisabled = (disabledStorage, force = false) => {
    const { storage } = this.state;
    if (
      force ||
      MainFunctions.serviceNameToStorageCode(disabledStorage) === storage
    ) {
      const allStorages = UserInfoStore.getStoragesInfo();
      // storages we can switch to in order
      const availableStorages = Object.keys(allStorages)
        .filter(
          storageServiceName =>
            storageServiceName !== disabledStorage &&
            (allStorages[storageServiceName] || []).length > 0
        )
        .map(name => ({ name }))
        .sort(UserInfoStore.getStoragesOrderingFunction());
      if (availableStorages.length > 0) {
        // select the best one
        const { name } = availableStorages[0];
        const [accountToSwitchTo] = allStorages[name];
        UserInfoActions.switchToStorage(name, accountToSwitchTo[`${name}_id`]);
        ApplicationActions.changePage(
          `/files/${MainFunctions.serviceNameToStorageCode(name)}/${
            accountToSwitchTo[`${name}_id`]
          }/-1`
        );
      } else {
        // if there are no accounts to switch to - redirect to storages
        ApplicationActions.changePage("/storages");
      }
    }
  };

  onResize = () => {
    this.countGridColumns();

    if (!MainFunctions.isMobileDevice()) return;

    if (window.innerWidth < window.innerHeight) return;

    if (!UserInfoStore.getUserInfo("showRecent")) return;

    setTimeout(() => {
      UserInfoActions.modifyUserInfo({ showRecent: false }, true);
    }, 0);
  };

  countGridColumns = () => {
    const { isGrid, countOfColumns } = this.state;

    if (!MainFunctions.isMobileDevice()) {
      return this.setState({
        countOfColumns: null,
        isGrid: false
      });
    }

    if (isGrid && window.innerWidth > 801 && countOfColumns !== 4) {
      return this.setState({
        countOfColumns: 4,
        isGrid: true
      });
    }

    if (
      window.innerWidth <= 801 &&
      window.innerWidth > 500 &&
      countOfColumns !== 3
    ) {
      return this.setState({
        countOfColumns: 3,
        isGrid: true
      });
    }

    if (
      window.innerWidth <= 499 &&
      window.innerWidth > 400 &&
      countOfColumns !== 2
    ) {
      return this.setState({
        countOfColumns: 2,
        isGrid: true
      });
    }

    if (window.innerWidth <= 399 && countOfColumns !== 1) {
      return this.setState({
        countOfColumns: 1,
        isGrid: true
      });
    }

    return null;
  };

  /**
   * Checks if we should change the flag and initialize the table
   * @param {boolean} newReconnectValue - new value to set
   */
  checkReconnect = (newReconnectValue = false) => {
    const { isReconnectRequired } = this.state;
    return new Promise(resolve => {
      if (isReconnectRequired === newReconnectValue) {
        resolve();
      } else {
        this.setState({ isReconnectRequired: newReconnectValue }, () => {
          resolve();
        });
      }
    });
  };

  checkUnsubscribe = () => {
    const { params } = this.props;
    const { fileId } = params;
    let source = "generic";
    if (MainFunctions.QueryString("source")) {
      source = MainFunctions.QueryString("source");
    }
    Requests.sendGenericRequest(
      `/files/${fileId}/subscription?source=${source}`,
      RequestsMethods.DELETE,
      Requests.getDefaultUserHeaders()
    ).then(() => {
      // let's use timeLimit to not force user to wait for long getInfo operation just to show file's name
      FilesListActions.getObjectInfo(fileId, "file", { timeLimit: 2000 })
        .then(data => {
          SnackbarUtils.alertOk({
            id: "unsubscribedNotificationsForFile",
            name: data.name || data.filename
          });
        })
        .catch(() => {
          SnackbarUtils.alertOk({
            id: "unsubscribedNotificationsForFile",
            name: "file"
          });
        });
    });
  };

  loadFolder = () => {
    // remove selected before rows
    SmartTableActions.deselectAllRows({
      tableType: "files"
    });
    this.setState({ isLoading: true, tableContent: new Immutable.List() });
    const { location } = this.props;
    const { pathname } = location;
    const mode = pathname.indexOf("trash") > -1 ? "trash" : "browser";

    const { storage, account, currentObjectId } = this.state;
    const { objectId } = MainFunctions.parseObjectId(currentObjectId);
    FetchAbortsControl.abortAllSignalsByType([REQUEST_TRASH, REQUEST_FOLDERS]);
    FilesListStore.setCurrentFolder({ storage, account, objectId, mode });
    // TODO: need to move prepareOpen to search loader after refactor tables on MUI (XENON-40651)
    // for now prepareOpen returns promise,
    // it waits till storage switch is done
    // XENON-58938
    FilesListStore.prepareOpen(
      MainFunctions.storageCodeToServiceName(storage),
      account
    ).then(() => {
      FilesListActions.getObjectInfo(currentObjectId, "folder", {});
      if (FilesListStore.getFolderPath().length === 1 && objectId !== "-1") {
        // only initial loade
        FilesListActions.loadPath(currentObjectId, "folder");
      }
      window.addEventListener("popstate", this.handleHistoryStateChange);
      FilesListActions.getFolderContent(storage, account, objectId, false, {
        isIsolated: false,
        recursive: false,
        usePageToken: false
      });
    });
  };

  handleHistoryStateChange = () => {
    const { currentObjectId } = this.state;
    FilesListActions.loadPath(currentObjectId, "folder");
  };

  normalizeURL = () => {
    const { params } = this.props;
    const { account, folder, storage, id = "-1" } = params;
    // remove listener anyway. If url cannot be normalized - listener will be readded
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.normalizeURL);
    // URL isn't normalized - let's try to normalize
    if (!account || !storage || !folder) {
      if (UserInfoStore.getUserInfo("isFullInfo") === true) {
        // make sure we reset state
        this.setState({ storage: "", account: "", currentObjectId: "" });
        const { storageType, storageId, objectId } =
          MainFunctions.parseObjectId(id);
        const userStorageInfo = UserInfoStore.getUserInfo("storage");
        const folderPath = `${
          storageType ||
          MainFunctions.serviceNameToStorageCode(userStorageInfo.type)
        }/${storageId || userStorageInfo.id}/${objectId}`;
        browserHistory.replace(
          `/files/${
            FilesListStore.getCurrentState() === "trash" ? "trash/" : ""
          }${folderPath}`
        );
      } else {
        UserInfoStore.addChangeListener(STORAGES_UPDATE, this.normalizeURL);
      }
    } else if (storage.includes("WEBDAV")) {
      const folderPath = `${MainFunctions.serviceNameToStorageCode(
        storage
      )}/${account}/${id}`;
      browserHistory.replace(
        `/files/${
          FilesListStore.getCurrentState() === "trash" ? "trash/" : ""
        }${folderPath}`
      );
      this.setState(
        {
          storage: MainFunctions.serviceNameToStorageCode(storage),
          account,
          objectId: id,
          currentObjectId: `${MainFunctions.serviceNameToStorageCode(
            storage
          )}+${account}+${id}`
        },
        this.loadFolder
      );
    } else {
      const {
        storage: currentStorage,
        account: currentAccount,
        currentObjectId,
        isTrash
      } = this.state;
      const { location } = this.props;
      const isTrashNow = location.pathname.includes("trash");
      if (
        currentObjectId !== `${storage}+${account}+${folder}` ||
        currentAccount !== account ||
        currentStorage !== storage ||
        isTrash !== isTrashNow
      ) {
        // URL is fine
        this.setState(
          {
            storage,
            account,
            currentObjectId: `${storage}+${account}+${folder}`,
            isTrash: isTrashNow
          },
          this.loadFolder
        );
      }
    }
  };

  onStoragesUpdate = () => {
    const storagesInfo = UserInfoStore.getStoragesInfo();
    const areStoragesConnected = _.flatten(_.toArray(storagesInfo)).length;
    if (!areStoragesConnected) {
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      SnackbarUtils.alertInfo({ id: "connectToOneOfTheStorages" });
      ApplicationActions.changePage(`${UIPrefix}storages`, "FL_storagesUpdate");
    } else {
      const { storage } = this.state;
      const storageServiceName =
        MainFunctions.storageCodeToServiceName(storage);
      // if there's no storage in list - it's either disabled or not available at all
      if (
        storageServiceName.length === 0 ||
        !Object.prototype.hasOwnProperty.call(storagesInfo, storageServiceName)
      ) {
        this.onStorageDisabled(storageServiceName, true);
      } else {
        // storage exists - verify that such account exists
        const { currentObjectId } = this.state;
        const { storageId } = MainFunctions.parseObjectId(currentObjectId);
        const doesAccountExist = Boolean(
          storagesInfo[storageServiceName].find(
            obj => obj[`${storageServiceName}_id`] === storageId
          )
        );
        // https://graebert.atlassian.net/browse/XENON-65643
        if (!doesAccountExist) {
          // we don't need to filter out current storage as it can just be a typo
          this.onStorageDisabled("", true);
        }
      }
    }
  };

  onUser = () => {
    this.forceUpdate();
  };

  onFolderInfoUpdate = () => {
    const { currentFolderName } = this.state;
    const { name } =
      FilesListStore[
        FilesListStore.getCurrentState() === "trash"
          ? "getCurrentTrashFolder"
          : "getCurrentFolder"
      ]();
    if (name !== currentFolderName && currentFolderName !== null) {
      this.setState({ currentFolderName: name });

      document.title = `${ApplicationStore.getApplicationSetting(
        "defaultTitle"
      )} | ${name || "files"}`;
    }
    const { currentObjectId } = this.state;
    const { objectId } = MainFunctions.parseObjectId(currentObjectId);
    // it's possible that we update path twice,
    // but otherwise path won't be updated in some cases
    // e.g. browser navigation
    FilesListActions.updatePath(objectId, name);
  };

  onContentLoaded = (folderId, mode) => {
    const { currentObjectId } = this.state;
    const browserMode = FilesListStore.getCurrentState();
    // Check modal because of XENON-41865
    // The idea of update by dialog doesn't work well if we have pagination.
    // Probably can improve later
    if (
      !modalStore.isDialogOpen() &&
      folderId === currentObjectId &&
      browserMode === mode
    ) {
      this.setState({
        isLoading: false,
        isLazy: false,
        tableContent: Immutable.fromJS(
          FilesListStore.getTreeData(currentObjectId)
        )
      });
    }
  };

  onContentUpdated = (folderId, mode) => {
    const { currentObjectId } = this.state;

    if (folderId === currentObjectId || mode === "trash") {
      this.setState({
        isLoading: false,
        isLazy: false,
        tableContent: Immutable.fromJS(
          FilesListStore.getTreeData(currentObjectId)
        )
      });
    }
  };

  onSortRequired = (folderId, mode) => {
    const { currentObjectId } = this.state;

    if (folderId === currentObjectId || mode === "trash") {
      this.setState(
        {
          isSortRequired: true
        },
        () => {
          setTimeout(() => {
            this.forceUpdate();
            SmartTableActions.sortCompleted();
          }, 0);
        }
      );
    }
  };

  onModeChanged = () => {
    this.setState({ isLoading: true, tableContent: new Immutable.List() });
    this.normalizeURL();
  };

  onPathChanged = () => {
    this.forceUpdate();
  };

  onReconnectRequired = () => {
    this.setState({ isReconnectRequired: true });
  };

  checkNewFilesPage = () => {
    const { tableContent } = this.state;
    if (tableContent.size <= 20) {
      this.loadMoreRows();
    }
  };

  loadMoreRows = () => {
    const { storage, account, currentObjectId } = this.state;
    const { objectId } = MainFunctions.parseObjectId(currentObjectId);
    if (FilesListStore.doesNextPageExist(storage, account, objectId, false)) {
      this.setState({ isLazy: true });
      FilesListActions.getFolderContent(storage, account, objectId, false, {
        isIsolated: false,
        recursive: false,
        usePageToken: true
      });
    }
  };

  isRowLoaded = ({ index }) => {
    const { tableContent } = this.state;
    return index <= tableContent.size;
  };

  isGridRowLoaded = ({ index }) => {
    const { tableContent, countOfColumns } = this.state;
    return index <= Math.floor(tableContent.size / countOfColumns);
  };

  handleRename = id => {
    const { tableContent } = this.state;

    const entityToRename = tableContent
      .find(entity => entity.toJS()?._id === id)
      ?.toJS();

    if (!entityToRename) return;

    FilesListActions.setEntityRenameMode(id);
  };

  beforeSort = direction => {
    sortDirection = direction;
    const { isSortRequired } = this.state;
    if (isSortRequired) this.setState({ isSortRequired: false });
  };

  deleteFiles = ids => {
    const { tableContent, isTrash } = this.state;
    const entities = ids
      .map(id => tableContent.find(t => t.get("id") === id))
      .filter(v => v !== undefined)
      .map(iMap => iMap.toJS())
      .map(({ _id, id, name, type, parent, folderId }) => ({
        id: id || _id,
        _id: id || _id,
        name,
        type,
        folderId: parent || folderId
      }));
    if (isTrash) {
      ModalActions.eraseObjects(entities);
    } else {
      ModalActions.deleteObjects("files", entities);
    }
  };

  startLoader = () => {
    this.setState({ isLoading: true, tableContent: new Immutable.List() });
  };

  handleDoubleClick = (event, rowData) => {
    const { isTrash } = this.state;

    if (isTrash) return;

    event.preventDefault();
    const id = rowData.get("id");
    if (!ProcessStore.getProcess(id)) {
      FilesListStore.open(id, null, null, false, rowData.toJS());
    }
  };

  getNoDataMessage = () => {
    const { isTrash } = this.state;

    if (!isTrash) return "noFilesInCurrentFolder";

    const currentFilter = UserInfoStore.getUserInfo("fileFilter");

    if (currentFilter === "allFiles") return "noFilesInCurrentFolder";

    if (currentFilter === "drawingsAndPdf") return "noDrawingsAndPdfInFolder";

    return "noDrawingsInFolder";
  };

  render() {
    const { showFooterOnFileLoaderPage } =
      ApplicationStore.getApplicationSetting("customization");
    const {
      storage,
      account,
      currentFolderName,
      isReconnectRequired,
      isLoading,
      tableContent,
      isLazy,
      isGrid,
      countOfColumns
    } = this.state;
    const { params, classes } = this.props;
    const { folder = "-1" } = params;
    return (
      <StorageContext.Provider
        // TODO: remove or useMemo
        // eslint-disable-next-line react/jsx-no-constructed-context-values
        value={{
          storage: MainFunctions.storageCodeToServiceName(storage),
          account,
          objectId: folder
        }}
      >
        <NotificationBar />
        <Sidebar />
        <main style={{ flexGrow: 1 }}>
          <ToolbarSpacer />
          <NotificationBarSpacer />
          <RecentFiles />
          {isReconnectRequired ? (
            <ReconnectMessage />
          ) : (
            <>
              <Toolbar startLoader={this.startLoader} />
              <Breadcrumbs
                storage={{
                  type: MainFunctions.storageCodeToServiceName(storage),
                  id: account
                }}
                path={FilesListStore.getFolderPath()}
              />
              {isGrid ? (
                <SmartGrid
                  data={tableContent}
                  isLoading={isLoading}
                  countOfColumns={countOfColumns}
                  gridItem={FileEntity}
                  customSorts={customSorts}
                  fixedGridHeight={false}
                  isRowLoaded={this.isGridRowLoaded}
                  isLazy={isLazy}
                  loadMoreRows={this.loadMoreRows}
                  noDataCaption={this.getNoDataMessage()}
                />
              ) : (
                <SmartTable
                  customSorts={customSorts}
                  columns={columns}
                  presentation={presentation}
                  tableType="files"
                  data={tableContent}
                  isLoading={isLoading}
                  rowHeight={80}
                  widthPadding={32}
                  classes={classes}
                  loadMoreRows={this.loadMoreRows}
                  isLazy={isLazy}
                  isRowLoaded={this.isRowLoaded}
                  handleDelete={this.deleteFiles}
                  handleRename={this.handleRename}
                  beforeSort={this.beforeSort}
                  eventHandlers={{
                    row: {
                      onDoubleClick: this.handleDoubleClick
                    }
                  }}
                  noDataCaption={this.getNoDataMessage()}
                />
              )}
              {showFooterOnFileLoaderPage ? <Footer /> : null}
            </>
          )}
        </main>
      </StorageContext.Provider>
    );
  }
}

export default withStyles(styles)(FileLoader);
