/**
 * Created by Diwakar on 03.07.17. */

// TODO: refactor all
// DK: It is really hard to mantain basically the same code across different files
// We have to refactor this when new files' operations are done
// 03/06/2020 DK: refactored some stuff, still I'd like to get rid of custom events and getObjects here
import React from "react";
import PropTypes from "prop-types";
import $ from "jquery";
import _ from "underscore";
import { Map } from "immutable";
import Breadcrumbs from "../Breadcrumbs/Breadcrumbs";
import UserInfoStore from "../../stores/UserInfoStore";
import TableView from "../Table/TableView";
import FilesListStore, { CONTENT_LOADED } from "../../stores/FilesListStore";
import TableStore, { SELECT_EVENT } from "../../stores/TableStore";
import TableActions from "../../actions/TableActions";
import MainFunctions from "../../libraries/MainFunctions";
import FormManagerStore, { TREEVIEW } from "../../stores/FormManagerStore";
import FormManagerActions from "../../actions/FormManagerActions";
import FilesListActions from "../../actions/FilesListActions";
// TODO: merge with Tree.jsx?
export default class XrefTree extends React.Component {
  static propTypes = {
    id: PropTypes.string.isRequired,
    name: PropTypes.string.isRequired,
    formId: PropTypes.string,
    onChange: PropTypes.func,
    filter: PropTypes.arrayOf(PropTypes.string)
  };

  static defaultProps = {
    formId: "",
    onChange: null,
    filter: ["dwg", "flx", "dxf"]
  };

  constructor(props) {
    super(props);

    const currentTableId = `xref_${MainFunctions.guid()}`;
    const _table = {
      fields: {
        name: {
          order: "asc",
          type: "string",
          edit: false,
          search: true
        },
        modified: {
          order: "desc",
          type: "updateDate",
          edit: false,
          search: false
        },
        size: {
          order: "desc",
          type: "num",
          edit: false,
          search: false
        }
      },
      type: "xref",
      multiSelect: false,
      loading: true,
      orderedBy: window.ARESKudoMainTableSort
        ? window.ARESKudoMainTableSort.orderedBy
        : null,
      pageTokens: Map(),
      nextPageIndex: -1,
      results: []
    };

    TableActions.registerTable(currentTableId);
    TableActions.saveConfiguration(currentTableId, _table);

    const initialFolder = {
      _id: "-1",
      name: "~",
      additional: { viewOnly: false }
    };
    this.state = {
      currentTableId,
      storageType: null,
      storageId: null,
      currentFolder: initialFolder,
      folderPath: [initialFolder]
    };
    if (props.formId !== "") {
      FormManagerStore.registerFormElement(
        props.formId,
        TREEVIEW,
        props.id,
        props.name,
        "",
        false
      );
    }
  }

  componentDidMount() {
    FilesListStore.addEventListener(CONTENT_LOADED, this.onContentLoaded);
    FilesListStore.addXrefOpenListener(this.openFolder);
    FilesListStore.addXrefChangeFolderListener(this.setFolder);
    TableStore.addListener(SELECT_EVENT, this.onTableRowSelection);
    this.getCurrentStorage(() => {
      this.getObjects(false);
    });
    this.setJqueryListeners();
  }

  componentWillUnmount() {
    const { currentTableId } = this.state;
    TableActions.unRegisterTable(currentTableId);
    FilesListStore.removeXrefOpenListener(this.openFolder);
    FilesListStore.removeXrefChangeFolderListener(this.setFolder);
    FilesListStore.removeEventListener(CONTENT_LOADED, this.onContentLoaded);
    TableStore.removeListener(SELECT_EVENT, this.onTableRowSelection);
  }

  getCurrentStorage = callback => {
    let storageType = null;
    let storageId = null;
    // try to get from current file
    if (!storageType || !storageId) {
      const currentFile = FilesListStore.getCurrentFile();
      if (currentFile && currentFile._id) {
        ({ storageType, storageId } = MainFunctions.parseObjectId(
          currentFile._id
        ));
      }
    }
    // try to get from user info
    if (!storageType || !storageId) {
      const userStorageInfo = UserInfoStore.getUserInfo("storage");
      if (userStorageInfo && userStorageInfo.type && userStorageInfo.id) {
        storageType = MainFunctions.serviceNameToStorageCode(
          userStorageInfo.type
        );
        storageId = userStorageInfo.id;
      }
    }

    if (!storageType || !storageId) {
      // eslint-disable-next-line no-console
      console.error("XREF dialog - Incomplete data supplied to getObjects");
    } else {
      this.setState({ storageType, storageId }, () => {
        callback();
      });
    }
  };

  getObjects = nextPage => {
    const {
      storageType: stateStorageType,
      storageId: stateStorageId,
      currentFolder,
      currentTableId
    } = this.state;

    const { storageType, storageId, objectId } = MainFunctions.parseObjectId(
      currentFolder._id
    );
    let finalStorageType = storageType;
    let finalStorageId = storageId;
    if (!finalStorageType || !finalStorageId) {
      finalStorageType = stateStorageType;
      finalStorageId = stateStorageId;
    } else if (
      finalStorageId !== stateStorageId ||
      finalStorageType !== stateStorageType
    ) {
      this.setState({
        storageType: finalStorageType,
        storageId: finalStorageId
      });
    }
    FilesListActions.getFolderContent(
      finalStorageType,
      finalStorageId,
      objectId,
      null,
      {
        usePageToken: nextPage
      }
    );

    TableStore.getTable(currentTableId).loading = true;
  };

  doesNextPageExist = () => {
    const {
      storageType: stateStorageType,
      storageId: stateStorageId,
      currentFolder
    } = this.state;

    const { storageType, storageId, objectId } = MainFunctions.parseObjectId(
      currentFolder._id
    );
    let finalStorageType = storageType;
    let finalStorageId = storageId;
    if (!finalStorageType || !finalStorageId) {
      finalStorageType = stateStorageType;
      finalStorageId = stateStorageId;
    }

    return FilesListStore.doesNextPageExist(
      finalStorageType,
      finalStorageId,
      objectId,
      false
    );
  };

  onContentLoaded = folderId => {
    const { currentFolder, storageType, storageId, currentTableId } =
      this.state;
    const { filter } = this.props;
    const { objectId } = MainFunctions.parseObjectId(currentFolder._id);
    const targetFolderId = MainFunctions.encapsulateObjectId(
      storageType,
      storageId,
      objectId
    );

    if (folderId === targetFolderId) {
      const tableContent = FilesListStore.getTreeData(folderId).filter(obj => {
        if (obj.type !== "file") return true;
        const extension = MainFunctions.getExtensionFromName(obj.name);
        return filter.includes(extension);
      });
      if (TableStore.isTableRegistered(currentTableId)) {
        let newTableInfo = TableStore.getTable(currentTableId);
        const orderedBy =
          newTableInfo.orderedBy || Object.keys(newTableInfo.fields)[0];
        newTableInfo = _.extend(newTableInfo, {
          results: tableContent,
          loading: false
        });
        TableActions.sortList(
          currentTableId,
          orderedBy,
          TableStore.getTable(currentTableId).fields[orderedBy].order
        );
      }
    }
  };

  setFolder = (newFolderName, newFolderId, additional = {}) => {
    const { folderPath } = this.state;
    let newFolderPath = folderPath;
    const newFolderInfo = {
      _id: newFolderId,
      name: newFolderName,
      additional
    };
    const index = _.findIndex(folderPath, ({ _id }) => _id === newFolderId);
    if (index > -1) {
      // going back
      newFolderPath = folderPath.slice(0, index + 1);
    } else {
      newFolderPath.push(newFolderInfo);
    }
    this.setState(
      { folderPath: newFolderPath, currentFolder: newFolderInfo },
      () => {
        this.getObjects(false);
      }
    );
  };

  openFolder = id => {
    const { currentTableId } = this.state;
    const entity = TableStore.getRowInfo(currentTableId, id);
    if (!entity) {
      const err = new Error(
        `Entity with id ${id} not found in table ${TableStore.getFocusedTable()}`
      );
      // eslint-disable-next-line no-console
      console.error(`${err.toString()} ${err.stack}`);
      return;
    }
    if (entity.type === "folder") {
      TableActions.saveConfiguration(
        TableStore.getFocusedTable(),
        _.extend(TableStore.getTable(TableStore.getFocusedTable()), {
          loading: true,
          results: [],
          pageTokens: Map(),
          nextPageIndex: -1
        })
      );
      this.setFolder(entity.name, id, { viewOnly: entity.viewOnly });
    }
  };

  setJqueryListeners = () => {
    const { currentTableId } = this.state;
    const self = this;
    const currentTable = TableStore.getTable(currentTableId);
    if (TableStore.isTableRegistered(currentTableId)) {
      MainFunctions.attachJQueryListener(
        $("table.tableViewMain.xref tbody"),
        "scroll",
        function NextPageLoader() {
          const isNextPageExists =
            currentTable.pageTokens.size > 0 &&
            currentTable.pageTokens.get(currentTable.currentPageToken) !==
              undefined;
          if (
            isNextPageExists &&
            $(this)[0].scrollHeight - $(this).scrollTop() - $(this).height() <=
              50 &&
            !TableStore.getTable(currentTableId).loading
          ) {
            self.getObjects(true);
          }
        }
      );
    }
  };

  onTableRowSelection = () => {
    const { currentTableId, folderPath } = this.state;
    const selectedItem = TableStore.getSelection(currentTableId);
    if (selectedItem.length) {
      let newSelectedItem = null;
      let validFile = false;
      if (
        TableStore.isSelected(TableStore.getFocusedTable(), selectedItem[0]._id)
      ) {
        if (selectedItem[0].type === "file") {
          const { filter } = this.props;
          if (
            filter.includes(
              MainFunctions.getExtensionFromName(selectedItem[0].name)
            )
          ) {
            newSelectedItem = {};
            newSelectedItem.id = selectedItem[0]._id;
            newSelectedItem.name = selectedItem[0].name;
            newSelectedItem.creationDate = selectedItem[0].creationDate;
            newSelectedItem.updateDate = selectedItem[0].updateDate;
            newSelectedItem.size = selectedItem[0].size;
            newSelectedItem.folderId = selectedItem[0].folderId;
            const path = _.pluck(folderPath, "name").join("/");
            newSelectedItem.path = `${path}/${selectedItem[0].name}`;
            validFile = true;
          }
        }

        const { onChange } = this.props;
        if (onChange) {
          onChange(newSelectedItem, validFile);
        }
        this.emitChangeAction(newSelectedItem, validFile);
      }
    }
  };

  emitChangeAction = (value, valid) => {
    const { formId, id } = this.props;
    if (
      formId.length > 0 &&
      FormManagerStore.checkIfElementIsRegistered(formId, id)
    ) {
      FormManagerActions.changeInputValue(formId, id, value, valid, false);
    }
  };

  render() {
    const currentStorage = UserInfoStore.getUserInfo("storage");
    const { currentTableId, folderPath } = this.state;
    return (
      <>
        <Breadcrumbs path={folderPath} storage={currentStorage} />
        <TableView
          id={currentTableId}
          isInDialog
          getObjects={this.getObjects}
          doesNextPageExist={this.doesNextPageExist}
        />
      </>
    );
  }
}
