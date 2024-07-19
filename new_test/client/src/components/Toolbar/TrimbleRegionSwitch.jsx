import React, { PureComponent } from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import { styled } from "@material-ui/core";
import Typography from "@material-ui/core/Typography";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import ApplicationStore from "../../stores/ApplicationStore";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGE_SWITCH,
  STORAGES_UPDATE
} from "../../stores/UserInfoStore";
import FilesListStore from "../../stores/FilesListStore";
import UserInfoActions from "../../actions/UserInfoActions";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationActions from "../../actions/ApplicationActions";
import FilesListActions from "../../actions/FilesListActions";
import ToolbarSelect from "./ToolbarSelect";
import SmartTableActions from "../../actions/SmartTableActions";

const TRIMBLE = `trimble`;

const StyledGrid = styled(Grid)(() => ({
  marginTop: "20px"
}));

const StyledTypography = styled(Typography)(({ theme }) => ({
  marginBottom: "5px",
  color: theme.palette.VADER
}));

class TrimbleRegionSwitch extends PureComponent {
  static propTypes = {
    formatMessage: PropTypes.func.isRequired,
    startLoader: PropTypes.func,
    isVisible: PropTypes.bool
  };

  static defaultProps = {
    isVisible: false,
    startLoader: () => null
  };

  constructor(props) {
    super(props);
    const { userStorageInfo } = this.getRenderFlags();
    this.state = {
      currentServer: userStorageInfo.otherInfo?.server
    };
    this._previousRegion = null;
  }

  componentDidMount() {
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    UserInfoStore.addChangeListener(STORAGE_SWITCH, this.onUserStorageSwitch);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.onUser);
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    UserInfoStore.removeChangeListener(
      STORAGE_SWITCH,
      this.onUserStorageSwitch
    );
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.onUser);
  }

  onUser = () => {
    const { userStorageInfo } = this.getRenderFlags();
    this.setState({
      currentServer: userStorageInfo?.otherInfo?.server
    });
  };

  onUserStorageSwitch = () => {
    if (
      !Object.prototype.hasOwnProperty.call(
        UserInfoStore.getUserInfo().storage.otherInfo || {},
        "regions"
      )
    )
      return;
    SmartTableActions.recalculateDimensions();
    this.forceUpdate();
  };

  getRenderFlags = () => {
    const currentFolder = FilesListStore.getCurrentFolder();
    const { accountId } = currentFolder;
    const storage = MainFunctions.storageCodeToServiceName(
      FilesListStore.findCurrentStorage().storageType
    );
    let userStorageInfo = UserInfoStore.getUserInfo("storage");

    if (
      storage &&
      accountId &&
      (userStorageInfo.type !== storage || userStorageInfo.id !== accountId)
    ) {
      userStorageInfo = { type: storage, id: accountId };
      const storagesInfo = UserInfoStore.getStoragesInfo();
      if (Object.prototype.hasOwnProperty.call(storagesInfo, storage)) {
        const currentAccount = _.find(
          storagesInfo[storage],
          accountData => accountData[`${storage}_id`] === accountId
        );
        if (currentAccount) {
          userStorageInfo = {
            type: storage,
            name: currentAccount[`${storage}_username`],
            id: currentAccount[`${storage}_id`] || 0,
            email:
              currentAccount[`${storage}_email`] ||
              currentAccount[`${storage}_username`] ||
              currentAccount[`${storage}_id`],
            otherInfo:
              _.omit(currentAccount, [
                `${storage}_id`,
                `${storage}_email`,
                `${storage}_username`
              ]) || {}
          };
        }
      }
    }

    const storageType = UserInfoStore.getUserInfo().storage.type;

    if (storageType !== TRIMBLE && this._previousRegion === TRIMBLE)
      setTimeout(() => {
        SmartTableActions.recalculateDimensions();
      }, 0);

    this._previousRegion = storageType;

    return {
      userStorageInfo,
      storageType
    };
  };

  handleChange = (input, userStorageInfo) => {
    const { storage, accountId, _id } = FilesListStore.getCurrentFolder();
    this.setState({ currentServer: input.target.value });
    const { startLoader } = this.props;
    startLoader();
    UserInfoActions.switchToStorage(
      "trimble",
      userStorageInfo.id,
      { server: input.target.value },
      () => {
        const { pathname } = location;
        const { storageType, storageId } = MainFunctions.parseObjectId(_id);
        const rootFolder = `${ApplicationStore.getApplicationSetting(
          "UIPrefix"
        )}files/${storageType || storage}/${storageId || accountId}/-1`;
        if (pathname !== rootFolder) {
          ApplicationActions.changePage(rootFolder);
        } else {
          FilesListActions.getFolderContent(
            storageType || storage,
            storageId || accountId,
            "-1",
            false,
            { isIsolated: false, recursive: false, usePageToken: false }
          );
        }
      }
    );
  };

  render() {
    const { userStorageInfo, storageType } = this.getRenderFlags();
    const { isVisible, formatMessage } = this.props;

    if (!isVisible) return null;

    if (storageType !== TRIMBLE) return null;

    if (
      !Object.prototype.hasOwnProperty.call(
        userStorageInfo.otherInfo || {},
        "regions"
      )
    )
      return null;

    const regions = userStorageInfo.otherInfo.regions
      .filter(region => region !== "oregon")
      .map(region => {
        if ((region || "").length) {
          return {
            value: region,
            label: formatMessage({ id: region || "" })
          };
        }
        return null;
      });

    const { currentServer } = this.state;
    if (!currentServer || regions.length < 1) return null;
    return (
      <StyledGrid item lg={3} md={3} sm={6} xs={12}>
        <StyledTypography>
          <FormattedMessage id="projectServerLocation" />
        </StyledTypography>
        <ToolbarSelect
          onChange={input => this.handleChange(input, userStorageInfo)}
          value={currentServer}
          options={regions}
          width="100%"
          mobileWidht="100%"
        />
      </StyledGrid>
    );
  }
}

export default TrimbleRegionSwitch;
