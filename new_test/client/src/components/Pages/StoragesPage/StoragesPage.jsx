import React, { Component } from "react";
import PropTypes from "prop-types";
import { injectIntl, FormattedMessage } from "react-intl";
import $ from "jquery";
import { Grid } from "@material-ui/core";
import Footer from "../../Footer/Footer";
import ModalActions from "../../../actions/ModalActions";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGE_SWITCH,
  STORAGES_UPDATE,
  STORAGES_CONFIG_UPDATE
} from "../../../stores/UserInfoStore";
import UserInfoActions from "../../../actions/UserInfoActions";
import ApplicationActions from "../../../actions/ApplicationActions";
import ApplicationStore, {
  CONFIG_LOADED
} from "../../../stores/ApplicationStore";
import MainFunctions from "../../../libraries/MainFunctions";
import PromoBlock from "./PromoBlock";
import ToolbarSpacer from "../../ToolbarSpacer";
import Loader from "../../Loader";
import StorageBlock from "./StorageBlock";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

let formatMessage = null;

class StoragesPage extends Component {
  static onConfigLoaded() {
    const { externalStoragesAvailable } =
      ApplicationStore.getApplicationSetting("customization");
    if (!externalStoragesAvailable) {
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      ApplicationActions.changePage(`${UIPrefix}files/-1`);
    }
  }

  /**
   * Remove integrated storage account
   * @param name - storage name (box, gdrive etc.)
   * @param storage - storage info (id, username etc.)
   * @param e - event
   */
  static deleteStorage(name, storage, e) {
    e.preventDefault();
    e.stopPropagation();
    ModalActions.delinkStorage(name, storage[`${name}_id`]);
  }

  static propTypes = {
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired,
    location: PropTypes.shape({ pathname: PropTypes.string.isRequired })
      .isRequired
  };

  constructor(props) {
    super(props);
    ({ formatMessage } = props.intl);
    this.state = {
      accounts: UserInfoStore.getStoragesInfo(),
      connectionName: ""
    };
  }

  componentDidMount() {
    const { intl } = this.props;
    document.title = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )} | ${intl.formatMessage({ id: "Storages" })}`;
    UserInfoStore.addChangeListener(INFO_UPDATE, this.onUser);
    UserInfoStore.addChangeListener(STORAGE_SWITCH, this.onUser);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.onUser);
    UserInfoActions.getUserStorages();
    UserInfoActions.getStoragesConfiguration();
    UserInfoStore.addChangeListener(STORAGES_CONFIG_UPDATE, this.onUser);
    this.calculateSectionHeight();
    MainFunctions.updateBodyClasses([], ["init", "profile"]);
    $("body").css("background-image", "");

    const isCommander =
      location.pathname.indexOf("commander") > -1 ||
      window.navigator.userAgent.indexOf("ARES Commander") > -1;
    const style = MainFunctions.QueryString("style") || "dark";

    if (isCommander) {
      MainFunctions.updateBodyClasses(["commander"]);
    }
    MainFunctions.updateBodyClasses([style]);

    ApplicationStore.addChangeListener(
      CONFIG_LOADED,
      StoragesPage.onConfigLoaded
    );
  }

  componentDidUpdate() {
    this.calculateSectionHeight();
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(INFO_UPDATE, this.onUser);
    UserInfoStore.removeChangeListener(STORAGE_SWITCH, this.onUser);
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.onUser);
    MainFunctions.updateBodyClasses([], ["commander"]);
    ApplicationStore.removeChangeListener(
      CONFIG_LOADED,
      StoragesPage.onConfigLoaded
    );
    UserInfoStore.removeChangeListener(STORAGES_CONFIG_UPDATE, this.onUser);
  }

  calculateSectionHeight = () => {
    const body = $(window);
    const { location } = this.props;
    const isCommander =
      location.pathname.includes("commander") ||
      window.navigator.userAgent.includes("ARES Commander");
    if (isCommander === false) {
      const footerHeight = 45;
      const storagesPage = $("#storagesPage");
      let height = body.height();
      let offset = storagesPage.offset();
      if (
        typeof storagesPage !== "undefined" &&
        typeof offset !== "undefined"
      ) {
        storagesPage.css("height", `${height - offset.top - footerHeight}px`);
      }
      $(window).resize(() => {
        offset = storagesPage.offset();
        height = body.height();
        if (offset) {
          storagesPage.css("height", `${height - offset.top - footerHeight}px`);
        }
      });
    }
  };

  onUser = () => {
    this.setState({ accounts: UserInfoStore.getStoragesInfo() });
  };

  connectStorage = (storageName, redirectURL, scredirect) => {
    if (storageName === "webdav") {
      ModalActions.connectWebDav();
    } else if (storageName === "nextcloud") {
      ModalActions.connectNextCloud();
    } else {
      this.setState({ connectionName: storageName });
      UserInfoActions.connectStorage(
        storageName,
        redirectURL,
        null,
        scredirect
      ).catch(() => {
        this.setState({ connectionName: "" });
        SnackbarUtils.alertError({ id: "errorConnectingStorage" });
      });
    }
  };

  render() {
    const userOptions = UserInfoStore.getUserInfo("options");
    const storagesConfig = UserInfoStore.getStoragesConfig();
    if (
      !userOptions ||
      !userOptions.storages ||
      !Object.values(storagesConfig).length
    )
      return <Loader />;
    const availableStorages = Object.values(storagesConfig)
      .filter(storageSettings => {
        if (storageSettings.serviceName === "internal") return false;
        if (!storageSettings.isConnectable) return false;
        if (userOptions.storages[storageSettings.serviceName] === false) {
          return false;
        }
        if (
          userOptions.storages[storageSettings.serviceName.toLowerCase()] ===
          false
        ) {
          return false;
        }
        return true;
      })
      .map(storageConfig => ({
        name: storageConfig.serviceName.toLowerCase(),
        displayName: storageConfig.displayName
      }));
    if (!availableStorages) return null;
    const orderingFunction = UserInfoStore.getStoragesOrderingFunction();
    availableStorages.sort(orderingFunction);
    const { connectionName } = this.state;
    const { location } = this.props;
    const isCommander =
      location.pathname.indexOf("commander") > -1 ||
      window.navigator.userAgent.indexOf("ARES Commander") > -1;
    const style = MainFunctions.QueryString("style") || "dark";
    const { accounts } = this.state;
    return (
      <main
        style={{ flexGrow: 1, overflowY: "auto", overflowX: "hidden" }}
        id="storagesPage"
      >
        <ToolbarSpacer />
        {!isCommander && connectionName ? (
          <Loader
            message={
              <FormattedMessage
                id="redirectingTo"
                values={{
                  storage:
                    MainFunctions.serviceStorageNameToEndUser(connectionName)
                }}
              />
            }
          />
        ) : (
          <Grid
            container
            justifyContent="center"
            spacing={1}
            style={{ marginTop: "16px", marginBottom: "16px" }}
          >
            <Grid item xs={12} sm={7} md={5} xl={3}>
              {isCommander === false ? (
                <PromoBlock availableStorages={availableStorages} />
              ) : null}
              {availableStorages.map(storageObject => (
                <StorageBlock
                  key={storageObject.name}
                  storageObject={storageObject}
                  connectStorage={this.connectStorage}
                  isCommander={isCommander}
                  style={style}
                  accounts={accounts[storageObject.name] || []}
                  deleteStorage={StoragesPage.deleteStorage}
                />
              ))}
            </Grid>
          </Grid>
        )}
        {isCommander === false ? <Footer isFixed /> : null}
      </main>
    );
  }
}

export default injectIntl(StoragesPage);
