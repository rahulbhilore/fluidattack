import React from "react";
import propTypes from "prop-types";
import { Box } from "@material-ui/core";
import MainFunctions from "../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../stores/UserInfoStore";
import FilesListStore, {
  ENTITY_RENAME_MODE
} from "../../../../stores/FilesListStore";
import IconBlock from "./innerComponents/IconBlock";
import NameBlock from "./innerComponents/NameBlock";
import smartTableStore from "../../../../stores/SmartTableStore";
import processStore from "../../../../stores/ProcessStore";
import Processes from "../../../../constants/appConstants/Processes";

class Name extends React.Component {
  static propTypes = {
    name: propTypes.string.isRequired,
    _id: propTypes.string.isRequired,
    nonBlockingProcess: propTypes.bool,
    isScrolling: propTypes.bool,
    icon: propTypes.string,
    data: propTypes.shape({
      thumbnail: propTypes.string,
      type: propTypes.string.isRequired,
      mimeType: propTypes.string,
      icon: propTypes.string,
      shared: propTypes.bool,
      storage: propTypes.string.isRequired,
      accountId: propTypes.string.isRequired,
      processId: propTypes.string,
      thumbnailStatus: propTypes.string,
      isShortcut: propTypes.bool,
      shortcutInfo: propTypes.shape({
        mimeType: propTypes.string.isRequired,
        type: propTypes.string.isRequired
      })
    }).isRequired,
    tableId: propTypes.string.isRequired
  };

  static defaultProps = {
    nonBlockingProcess: true,
    isScrolling: false,
    icon: ""
  };

  static getExtension(type, name) {
    return type === "folder"
      ? "folder"
      : MainFunctions.getExtensionFromName(name);
  }

  constructor(props) {
    super(props);
    const openFlag = this.getOpenFlag();
    const process = processStore.getProcess(props._id);
    this.state = {
      openFlag,
      isTooltipToShow: false,
      renameMode: process && process.type === Processes.RENAME
    };
  }

  componentDidMount() {
    const { _id } = this.props;
    FilesListStore.addEventListener(
      `${ENTITY_RENAME_MODE}${_id}`,
      this.renameListener
    );
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { _id, name, icon, isScrolling, data } = this.props;
    const { renameMode } = this.state;
    const { thumbnail } = data;

    if (_id !== nextProps._id) {
      FilesListStore.removeEventListener(
        `${ENTITY_RENAME_MODE}${_id}`,
        this.renameListener
      );
      FilesListStore.addEventListener(
        `${ENTITY_RENAME_MODE}${nextProps._id}`,
        this.renameListener
      );
    }

    return (
      _id !== nextProps._id ||
      name !== nextProps.name ||
      icon !== nextProps.icon ||
      thumbnail !== nextProps.data.thumbnail ||
      isScrolling !== nextProps.isScrolling ||
      renameMode !== nextState.renameMode ||
      data.shared !== nextProps.data.shared
    );
  }

  componentDidUpdate(prevProps) {
    const { name, _id } = this.props;
    if (prevProps.name !== name || prevProps._id !== _id) {
      // eslint-disable-next-line react/no-did-update-set-state
      this.setState({
        openFlag: this.getOpenFlag()
      });
    }
  }

  componentWillUnmount() {
    const { _id } = this.props;
    FilesListStore.removeEventListener(
      `${ENTITY_RENAME_MODE}${_id}`,
      this.renameListener
    );
  }

  renameListener = mode => {
    this.setState({
      renameMode: mode
    });
  };

  getOpenFlag = () => {
    const { name, nonBlockingProcess, data } = this.props;
    const { type, mimeType, isShortcut = false } = data;
    const ext = Name.getExtension(type, name);
    if (nonBlockingProcess === true) {
      return (
        (isShortcut || UserInfoStore.extensionSupported(ext, mimeType)) &&
        FilesListStore.getCurrentState() !== "trash"
      );
    }
    return false;
  };

  render() {
    const { name, _id, data, isScrolling, tableId } = this.props;
    const { isTooltipToShow, openFlag, renameMode } = this.state;

    const {
      icon,
      shared,
      mimeType,
      thumbnail,
      type,
      storage,
      accountId,
      processId,
      thumbnailStatus = "",
      shortcutInfo
    } = data;

    const tableInfo = smartTableStore.getTableInfo(tableId);
    let highlightQuery = null;
    if (tableInfo.type === "search") {
      highlightQuery = FilesListStore.getSearchQuery();
    }

    return (
      <Box>
        <IconBlock
          name={name}
          icon={icon}
          shared={shared}
          mimeType={mimeType}
          thumbnail={thumbnail}
          _id={_id}
          type={shortcutInfo ? shortcutInfo.type : type}
          isShortcut={!!shortcutInfo}
          isScrolling={isScrolling}
          renameMode={renameMode}
          thumbnailStatus={thumbnailStatus}
        />
        <NameBlock
          renameMode={renameMode}
          openFlag={openFlag}
          isTooltipToShow={isTooltipToShow}
          name={name}
          _id={_id}
          type={shortcutInfo ? shortcutInfo.type : type}
          isShortcut={!!shortcutInfo}
          shortcutInfo={shortcutInfo}
          storage={storage}
          accountId={accountId}
          mimeType={mimeType}
          processId={processId}
          tableId={tableId}
          highlightQuery={highlightQuery}
        />
      </Box>
    );
  }
}
// export default React.memo(Name);
export default Name;
