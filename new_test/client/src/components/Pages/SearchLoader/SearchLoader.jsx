import React from "react";
import { Portal } from "@mui/material";
import PropTypes from "prop-types";
import _ from "underscore";
import { injectIntl } from "react-intl";
import UserInfoStore, { STORAGES_UPDATE } from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import Sidebar from "../../Sidebar/Sidebar";
import ApplicationStore from "../../../stores/ApplicationStore";
import ApplicationActions from "../../../actions/ApplicationActions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import UserInfoActions from "../../../actions/UserInfoActions";
import SearchStore from "../../../stores/SearchStore";
import AccountsCounter from "./AccountsCounter/AccountsCounter";
import StorageResults from "./StorageResults";
import Loader from "../../Loader";
import ToolbarSpacer from "../../ToolbarSpacer";
import * as SearchConstants from "../../../constants/SearchConstants";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import SmartTableActions from "../../../actions/SmartTableActions";

const SEARCH = "SEARCH";

const minimumCharsNumber = 2;

class SearchLoader extends React.Component {
  static setPageDimensions() {
    document.body.classList.remove("scrollable", "init", "profile");
  }

  static propTypes = {
    params: PropTypes.shape({
      query: PropTypes.string.isRequired
    }).isRequired,
    intl: PropTypes.shape({ formatMessage: PropTypes.func.isRequired })
      .isRequired
  };

  constructor(props) {
    super(props);
    const { params } = props;
    this.state = {
      query: params.query || "",
      storagesToCheck: [],
      results: {},
      checkedStorages: [],
      storagesLoaded: false
    };
  }

  componentDidMount() {
    const { intl } = this.props;
    document.title = `${ApplicationStore.getApplicationSetting(
      "defaultTitle"
    )} | ${intl.formatMessage({ id: "search" })}`;
    SearchLoader.setPageDimensions();
    const { query } = this.state;
    if (query.length < minimumCharsNumber) {
      ApplicationActions.changePage(
        `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
      );
      SnackbarUtils.alertError({
        id: "minimumNCharsRequired",
        number: minimumCharsNumber
      });
    } else {
      FilesListStore.setSearchQuery(query);
      this.getTargetStorages();
    }
    UserInfoStore.addChangeListener(STORAGES_UPDATE, this.getTargetStorages);
    FilesListStore.addListener(SEARCH, this.onSearchResultsLoaded);
    SearchStore.addChangeListener(
      SearchConstants.REMOVE_SEARCH_RESULT,
      this.onSearchResultsDeleted
    );
  }

  componentDidUpdate(newProps) {
    const { query } = this.state;
    if (newProps.params.query !== query) {
      if ((newProps.params.query || "").length < minimumCharsNumber) {
        ApplicationActions.changePage(
          `${ApplicationStore.getApplicationSetting("UIPrefix")}files`
        );
        SnackbarUtils.alertError({
          id: "minimumNCharsRequired",
          number: minimumCharsNumber
        });
      } else {
        // eslint-disable-next-line react/no-did-update-set-state
        this.setState(
          {
            storagesToCheck: [],
            results: {},
            checkedStorages: [],
            storagesLoaded: false,
            query: newProps.params.query
          },
          () => {
            const { query: newQuery } = this.state;
            FilesListStore.setSearchQuery(newQuery);
            if (!Object.keys(UserInfoStore.getStoragesInfo() || {}).length) {
              UserInfoActions.getUserStorages();
            } else {
              this.getTargetStorages();
            }
          }
        );
      }
    }
  }

  componentWillUnmount() {
    UserInfoStore.removeChangeListener(STORAGES_UPDATE, this.getTargetStorages);
    // clear values in store.
    FilesListStore.clearSearchResults();
    FilesListStore.removeListener(SEARCH, this.onSearchResultsLoaded);
    FilesListStore.setSearchQuery(null);
    SearchStore.removeChangeListener(
      SearchConstants.REMOVE_SEARCH_RESULT,
      this.onSearchResultsDeleted
    );
  }

  /**
   * Save storages list to be checked and start check
   */
  getTargetStorages = () => {
    const storagesInfo = UserInfoStore.getStoragesInfo() || {};
    const { storagesLoaded } = this.state;
    if (storagesLoaded === false && Object.keys(storagesInfo).length > 0) {
      this.setState(
        {
          storagesToCheck: Object.keys(
            _.pick(storagesInfo, accounts => accounts.length > 0)
          ),
          storagesLoaded: true
        },
        this.checkStorages
      );
      UserInfoStore.removeChangeListener(
        STORAGES_UPDATE,
        this.getTargetStorages
      );
    }
  };

  /**
   * Start searching through storages
   */
  checkStorages = () => {
    // clear previous searches
    this.setState({ checkedStorages: [] });
    FilesListStore.clearSearchResults();

    // execute search
    const { storagesToCheck, query } = this.state;
    _.each(storagesToCheck, storageName => {
      FilesListActions.search(query, storageName);
    });
  };

  /**
   * Save search results once they are received from API
   */
  onSearchResultsLoaded = foundStorage => {
    const searchResults = FilesListStore.getSearchResults();
    const { checkedStorages } = this.state;
    // if storage hasn't been checked
    if (checkedStorages.includes(foundStorage) === false) {
      // save info that storage has been checked
      this.setState(
        {
          checkedStorages: [...checkedStorages, foundStorage]
        },
        () => {
          const { results } = this.state;
          const storageResults = searchResults[foundStorage];
          // iterate over accounts for this storage
          _.each(storageResults, accountResults => {
            // just to make sure that we have proper object now
            if (
              Object.prototype.hasOwnProperty.call(results, foundStorage) ===
              false
            ) {
              results[foundStorage] = {};
            }

            // get proper accountName
            const accountData = {
              name: (accountResults.name || "").toString()
            };
            if (accountData.name.length === 0) {
              const userInfo = UserInfoStore.getUserInfo();
              accountData.name = `${userInfo.name} ${
                userInfo.surname || ""
              }`.trim();
              if (accountData.name.length === 0) {
                accountData.name = (
                  userInfo.username ||
                  userInfo.email ||
                  " "
                ).trim();
              }
            }

            // get externalId
            accountData.externalId = (
              accountResults.externalId || "none"
            ).toString();

            // update state only if something was found
            if (
              (accountResults.files || []).length > 0 ||
              (accountResults.folders || []).length > 0
            ) {
              results[foundStorage][accountData.externalId] = {
                name: accountData.name
              };
              FilesListStore.formatFiles(accountResults.files || [], true).then(
                formattedFiles => {
                  _.map(formattedFiles, (files, parent) => {
                    results[foundStorage][accountData.externalId][parent] =
                      files;
                  });
                  FilesListStore.formatFolders(
                    accountResults.folders,
                    true
                  ).then(formattedFolders => {
                    _.map(formattedFolders, (folders, parent) => {
                      // merge contents - files + folders
                      results[foundStorage][accountData.externalId][parent] = (
                        results[foundStorage][accountData.externalId][parent] ||
                        []
                      ).concat(folders);
                    });
                    this.setState({
                      results
                    });
                  });
                }
              );
            }
          });
        }
      );
    }
  };

  onSearchResultsDeleted = (tableId, item) => {
    const { results } = this.state;
    // folderId for files, parent for folders
    const { id, folderId, parent } = item;

    const { storageType, storageId } = MainFunctions.parseObjectId(id);

    const storageName = MainFunctions.storageCodeToServiceName(storageType);

    const currentFilesFolder =
      results[storageName][storageId][folderId || parent];

    const index = currentFilesFolder.findIndex(entity => entity.id === id);

    results[storageName][storageId][folderId || parent].splice(index, 1);

    this.setState({
      results
    });

    SmartTableActions.recalculateDimensions(tableId);
  };

  render() {
    const { results, query, storagesLoaded, checkedStorages } = this.state;
    const foundAccounts = Object.values(results).reduce(
      (memo, accounts) => memo + Object.keys(accounts).length,
      0
    );
    return (
      <>
        <Sidebar />
        {storagesLoaded === true && checkedStorages.length > 0 ? null : (
          <Portal isOpened>
            <Loader />
          </Portal>
        )}
        <main style={{ flexGrow: 1, overflowY: "auto" }}>
          <ToolbarSpacer />
          <AccountsCounter
            query={query}
            amount={foundAccounts}
            storagesLoaded={storagesLoaded}
          />
          {storagesLoaded
            ? _.keys(results).map(storageName => (
                <StorageResults
                  key={`searchResultsOverlay_${storageName}`}
                  storageName={storageName}
                  foundAccounts={results[storageName]}
                  query={query}
                />
              ))
            : null}
        </main>
      </>
    );
  }
}

export default injectIntl(SearchLoader);
