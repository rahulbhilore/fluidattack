import React, { Component } from "react";
import PropTypes from "prop-types";
import Immutable, { List } from "immutable";
import styled from "@material-ui/core/styles/styled";
import Box from "@material-ui/core/Box";
import { SortDirection } from "react-virtualized";
import SmartTable from "../../SmartTable/SmartTable";
import UserInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import BlocksActions from "../../../actions/BlocksActions";
import BlocksStore, {
  BLOCK,
  BLOCKS_REQUIRE_UPDATE,
  BLOCKS_SORT_REQUIRED,
  BLOCKS_UPDATE,
  LIBRARY
} from "../../../stores/BlocksStore";
import BlocksToolbar from "./BlocksToolbar";
import BlocksBreadcrumbs from "./BlocksBreadcrumbs";
import UploadManager from "../../Inputs/UploadManager";
import AccountsCounter from "../SearchLoader/AccountsCounter/AccountsCounter";
import { DESCRIPTION_ENABLED } from "./BlocksCapabilities";
import ModalActions from "../../../actions/ModalActions";
import WebsocketStore, {
  RESOURCE_DELETED,
  RESOURCE_UPDATED
} from "../../../stores/WebsocketStore";
import * as ResourcesWebsocketActions from "../../../constants/appConstants/ResourcesWebsocketActions";

import NameColumn from "../../SmartTable/tables/oldBlocks/Name";
import DescriptionColumn from "../../SmartTable/tables/oldBlocks/Description";
import PermissionsColumn from "../../SmartTable/tables/oldBlocks/Permissions";
import OwnerColumn from "../../SmartTable/tables/oldBlocks/Owner";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import SmartTableActions from "../../../actions/SmartTableActions";
import ProcessStore from "../../../stores/ProcessStore";
import Processes from "../../../constants/appConstants/Processes";
import ApplicationActions from "../../../actions/ApplicationActions";

const StyledTableBox = styled(Box)(({ theme }) => ({
  "& .ReactVirtualized__Table__headerColumn": {
    fontSize: theme.typography.pxToRem(12)
  },
  "& .ReactVirtualized__Table__headerColumn:first-of-type": {
    marginLeft: "38px"
  }
}));

let widths = { name: 0.3, permissions: 0.3, description: 0.2, owner: 0.2 };
if (!DESCRIPTION_ENABLED) {
  widths = { name: 0.4, permissions: 0.3, description: 0, owner: 0.3 };
}

const columns = new List(
  [
    // thumbnail + name as in files
    { dataKey: "name", label: "name", width: widths.name },
    // share
    {
      dataKey: "permissions",
      label: "access",
      width: widths.permissions
    },
    // description
    DESCRIPTION_ENABLED
      ? {
          dataKey: "description",
          label: "description",
          width: widths.description
        }
      : null,
    // tags
    // { key: "tags", label: "tags", width: 0.2 },
    // owner
    { dataKey: "ownerName", label: "owner", width: widths.owner }
  ].filter(v => v !== null)
);

const presentation = new Immutable.Map({
  name: NameColumn,
  permissions: PermissionsColumn,
  description: DescriptionColumn,
  ownerName: OwnerColumn
});

const BASE_PATH = [{ id: null, name: "~", link: "/resources/blocks/" }];

const TOP_PROCESS_TYPES = [
  Processes.CREATING,
  Processes.CREATE_COMPLETED,
  Processes.UPLOAD,
  Processes.UPLOADING,
  Processes.UPLOAD_CANCELED,
  Processes.UPLOAD_COMPLETE
];

let sortDirection = SortDirection.ASC;

const sortByProcess = (a, b) => {
  if (ProcessStore.getProcessesSize() > 0) {
    const runningProcessesA = ProcessStore.getProcess(a.get("id"));
    const runningProcessesB = ProcessStore.getProcess(b.get("id"));
    if (!runningProcessesA && !runningProcessesB) return 0;
    const isAProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesA?.type);
    const isBProcessTop = TOP_PROCESS_TYPES.includes(runningProcessesB?.type);
    if (isAProcessTop && !isBProcessTop)
      return sortDirection === SortDirection.ASC ? -1 : 1;
    if (isBProcessTop && !isAProcessTop)
      return sortDirection === SortDirection.ASC ? 1 : -1;
  }
  return 0;
};

const customSorts = {
  name: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    return (a.get("name") || "")
      .toLowerCase()
      .localeCompare((b.get("name") || "").toLowerCase());
  },
  permissions: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    const currentUserId = UserInfoStore.getUserInfo("id");
    if (a.get("ownerId") === currentUserId) return 1;
    if (b.get("ownerId") === currentUserId) return 1;
    return 0;
  },
  description: (a, b) => {
    const processSort = sortByProcess(a, b);
    return processSort;
  },
  ownerName: (a, b) => {
    const processSort = sortByProcess(a, b);
    if (processSort !== 0) return processSort;
    return (a.get("ownerName") || "")
      .toLowerCase()
      .localeCompare((b.get("ownerName") || "").toLowerCase());
  }
};

export default class Blocks extends Component {
  static beforeSort(direction) {
    sortDirection = direction;
  }

  static propTypes = {
    libId: PropTypes.string,
    query: PropTypes.string
  };

  static defaultProps = {
    libId: "",
    query: ""
  };

  constructor(props) {
    super(props);
    this.state = {
      blocks: new Immutable.List(),
      isLoaded: false,
      path: [...BASE_PATH],
      // to keep track of different owners
      librariesToLoad: 0,
      currentLibraryInfo: {},
      // to deselect rows on content change
      // TODO: make it default for table?..
      tableId: ""
    };
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    if (UserInfoStore.getUserInfo("id")) this.loadBlocks();

    BlocksStore.addListener(BLOCKS_UPDATE, this.onBlocksUpdate);
    BlocksStore.addListener(BLOCKS_REQUIRE_UPDATE, this.loadBlocks);
    BlocksStore.addListener(BLOCKS_SORT_REQUIRED, this.onBlocksUpdate);
    WebsocketStore.addEventListener(RESOURCE_UPDATED, this.onWebsocketUpdate);
    WebsocketStore.addEventListener(RESOURCE_DELETED, this.onWebsocketUpdate);
  }

  componentDidUpdate(prevProps) {
    const { libId: prevLibId, query: prevQuery } = prevProps;
    const { libId, query } = this.props;
    if (prevLibId !== libId || query !== prevQuery) {
      if (!query) {
        this.loadPath();
      }
      if (UserInfoStore.getUserInfo("id")) {
        this.loadBlocks();
      }
      this.setState({ currentLibraryInfo: {} });
    }
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    BlocksStore.removeListener(BLOCKS_UPDATE, this.onBlocksUpdate);
    BlocksStore.removeListener(BLOCKS_REQUIRE_UPDATE, this.loadBlocks);
    BlocksStore.removeListener(BLOCKS_SORT_REQUIRED, this.onBlocksUpdate);
    WebsocketStore.removeEventListener(
      RESOURCE_UPDATED,
      this.onWebsocketUpdate
    );
    WebsocketStore.removeEventListener(
      RESOURCE_DELETED,
      this.onWebsocketUpdate
    );
  }

  onWebsocketUpdate = ({ data }) => {
    // enum from server -> BlockLibraryManager.java -> enum WebsocketAction
    const { libId } = this.props;

    if (libId) {
      // handle specific lib events
      const { libId: wsLibraryId, blockId: wsBlockId, action } = data;
      // in case current library was deleted
      if (
        action === ResourcesWebsocketActions.UNSHARED_BLOCK_LIBRARY ||
        action === ResourcesWebsocketActions.DELETED_BLOCK_LIBRARY
      ) {
        const { libraries = [] } = data;
        if (!libraries.length && (wsLibraryId || "").length > 0) {
          libraries.push(wsLibraryId);
        }
        // current library is inaccessible
        if (libraries.includes(libId)) {
          SnackbarUtils.alertWarning({
            id: "currentLibraryIsntAccessibleAnymore"
          });
          ApplicationActions.changePage("/resources/blocks");
        }
      }
      // event for some another lib - ignore
      if (wsLibraryId !== libId) return;
      const { isLoaded } = this.state;
      switch (data.action) {
        case ResourcesWebsocketActions.CREATED_BLOCK: {
          if (isLoaded) {
            const { blocks } = this.state;
            const existingBlock = blocks.find(
              block => block.get("id") === wsBlockId
            );
            if (!existingBlock) {
              // we don't get full block info, so we need to get block info from server
              BlocksActions.getBlockInfo(wsBlockId, libId).then(blockInfo => {
                this.setState({
                  blocks: blocks.push(Immutable.fromJS(blockInfo.data))
                });
              });
            }
          }
          break;
        }
        case ResourcesWebsocketActions.DELETED_BLOCK: {
          if (isLoaded) {
            const { blocks } = this.state;
            const existingBlock = blocks.find(
              block => block.get("id") === wsBlockId
            );
            if (existingBlock) {
              // remove block from list
              this.setState({
                blocks: blocks.filter(block => block.get("id") !== wsBlockId)
              });
            }
          }
          break;
        }
        case ResourcesWebsocketActions.SHARED_BLOCK:
        case ResourcesWebsocketActions.UNSHARED_BLOCK:
        case ResourcesWebsocketActions.UPDATED_BLOCK:
          // For now for simplicity - just update blocks
          if (isLoaded) this.loadBlocks();
          break;
        default:
          break;
      }
    } else {
      // handle root level events
      const { libId: wsLibraryId } = data;
      switch (data.action) {
        case ResourcesWebsocketActions.CREATED_BLOCK_LIBRARY: {
          const { blocks } = this.state;
          const existingBlockLibrary = blocks.find(
            block => block.get("id") === wsLibraryId
          );
          // if block doesn't exist - reload list
          if (!existingBlockLibrary) this.loadBlocks();
          break;
        }
        case ResourcesWebsocketActions.DELETED_BLOCK_LIBRARY:
        case ResourcesWebsocketActions.SHARED_BLOCK_LIBRARY:
        case ResourcesWebsocketActions.UNSHARED_BLOCK_LIBRARY:
        case ResourcesWebsocketActions.UPDATED_BLOCK_LIBRARY:
          // For now for simplicity - just update blocks
          this.loadBlocks();
          break;
        default:
          break;
      }
    }
  };

  loadPath = () => {
    const { libId } = this.props;
    const { path } = this.state;
    const newPath = [...BASE_PATH];
    if (libId) {
      const foundLib = path.find(({ id }) => id === libId);
      if (!foundLib) {
        BlocksActions.getBlockLibraryInfo(libId).then(response => {
          newPath.push({
            id: libId,
            name: response.data.name,
            link: `/resources/blocks/${libId}`
          });
          this.setState({ path: newPath, currentLibraryInfo: response.data });
        });
      }
    } else {
      this.setState({ path: newPath, currentLibraryInfo: {} });
    }
  };

  onUser = () => {
    this.loadBlocks();
    this.loadPath();
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
  };

  onBlocksUpdate = () => {
    const { librariesToLoad } = this.state;
    if (librariesToLoad <= 1) {
      this.setState({
        blocks: Immutable.fromJS(BlocksStore.getBlocks()),
        isLoaded: true
      });
      const { tableId } = this.state;
      if ((tableId || "").length > 0) {
        SmartTableActions.selectRows(tableId, []);
      }
    } else {
      this.setState({ librariesToLoad: librariesToLoad - 1 });
    }
  };

  handleErrorOnLoad = err => {
    SnackbarUtils.alertError(err?.text || err.toString());
    this.setState({
      isLoaded: true
    });
  };

  loadBlocks = () => {
    const { libId, query } = this.props;
    this.setState({
      isLoaded: false,
      blocks: new Immutable.List()
    });
    if (query.length > 0) {
      BlocksActions.search(query).catch(this.handleErrorOnLoad);
    } else if (libId) {
      BlocksActions.getBlockLibraryContent(libId).catch(this.handleErrorOnLoad);
    } else {
      // user + public
      let librariesToLoad = 2;
      const isPartOfCompany = UserInfoStore.getUserInfo("company")?.id;
      if (isPartOfCompany) {
        librariesToLoad += 1;
      }
      this.setState({ librariesToLoad });

      // user libraries
      BlocksActions.getBlockLibraries(UserInfoStore.getUserInfo("id")).catch(
        this.handleErrorOnLoad
      );
      // public libraries
      BlocksActions.getBlockLibraries(null, "PUBLIC").catch(
        this.handleErrorOnLoad
      );
      // org libraries
      if (isPartOfCompany) {
        BlocksActions.getBlockLibraries(
          UserInfoStore.getUserInfo("company")?.id,
          "ORG"
        ).catch(this.handleErrorOnLoad);
      }
    }
  };

  deleteBlocks = ids => {
    const { libId } = this.props;
    const { blocks } = this.state;
    const entities = blocks
      .filter(block => ids.includes(block.get("id")))
      .toJS();
    ModalActions.deleteObjects(libId ? BLOCK : LIBRARY, entities);
  };

  onTableLoaded = receivedTableId => {
    const { tableId } = this.state;
    if (tableId !== receivedTableId) {
      SmartTableActions.selectRows(receivedTableId, []);
      this.setState({ tableId: receivedTableId });
    }
  };

  render() {
    const { blocks, isLoaded, path, currentLibraryInfo } = this.state;
    const { libId = "", query = "" } = this.props;

    return (
      <StyledTableBox>
        {query.length > 0 ? (
          <AccountsCounter
            amount={blocks?.size || 0}
            query={query}
            isCustom
            messages={{
              multiple: "searchMultipleEntitiesFound",
              single: "searchOneEntityFound"
            }}
            customRootURL="/resources/blocks"
          />
        ) : (
          <>
            <BlocksToolbar
              libraryId={libId}
              currentLibraryInfo={currentLibraryInfo}
            />
            <BlocksBreadcrumbs path={path} />
          </>
        )}
        <SmartTable
          isLoading={!isLoaded}
          data={blocks}
          columns={columns}
          presentation={presentation}
          handleDelete={this.deleteBlocks}
          beforeSort={this.beforeSort}
          tableType="oldBlocks"
          rowHeight={80}
          customSorts={customSorts}
          keyField="id"
          ref={ref => {
            if (isLoaded) return;
            if (!ref) return;
            this.onTableLoaded(ref.getTableId());
          }}
        />
        <UploadManager />
      </StyledTableBox>
    );
  }
}
