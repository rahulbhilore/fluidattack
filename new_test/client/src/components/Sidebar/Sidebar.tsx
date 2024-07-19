/* eslint-disable */
import { List, styled, useMediaQuery, useTheme } from "@mui/material";
import React, {
  useContext,
  useEffect,
  useMemo,
  useReducer,
  useState
} from "react";
import _ from "underscore";
import ApplicationActions from "../../actions/ApplicationActions";
import SmartTableActions from "../../actions/SmartTableActions";
import UserInfoActions from "../../actions/UserInfoActions";
import StorageContext from "../../context/StorageContext";
import MainFunctions from "../../libraries/MainFunctions";
import ApplicationStore, { CONFIG_LOADED } from "../../stores/ApplicationStore";
import FilesListStore from "../../stores/FilesListStore";
import UserInfoStore, {
  INFO_UPDATE,
  NOTIFICATION_UPDATE,
  STORAGES_CONFIG_UPDATE,
  STORAGES_UPDATE,
  STORAGE_SWITCH
} from "../../stores/UserInfoStore";
import {
  StorageSettings,
  StorageType,
  StorageValues,
  UserStoragesInfo
} from "../../types/StorageTypes";
import Footer from "../Footer/Footer";
import NotificationBarSpacer from "../Header/NotificationBar/NotificationBarSpacer";
import ToolbarSpacer from "../ToolbarSpacer";
import AddStorageLink from "./AddStorageLink";
import RecentSwitch from "./RecentSwitch";
import ResponsiveDrawer from "./ResponsiveDrawer";
import StorageBlock from "./StorageBlock";

const RECENT_FILES_LOAD = "RECENT_FILES_LOAD";

const KudoList = styled(List)({
  margin: 0,
  padding: 0
});

export default function Sidebar({
  mode = "default"
}: {
  mode: "header" | "default";
}) {
  const [activeAccountId, setActiveAccountId] = useState("");
  const [activeStorage, setActiveStorage] = useState("");
  const [clickBlock, setClickBlock] = useState(false);
  const context = useContext(StorageContext);
  const { storage, account } = context;
  const theme = useTheme();
  const isSmallerThanMd = useMediaQuery(theme.breakpoints.down("md"));
  const [, forceUpdate] = useReducer(x => x + 1, 0);
  const isMobile = useMemo(
    () => MainFunctions.isMobileDevice() || isSmallerThanMd,
    [isSmallerThanMd]
  );

  const debouncedTouchInterval = _.debounce(() => {
    setClickBlock(false);
  }, 300);

  const onRecentFilesLoad = () => {
    forceUpdate();
  };

  const onConfigLoaded = () => {
    forceUpdate();
  };

  const onTouchMove = () => {
    if (!isMobile) return;

    if (!clickBlock) {
      setClickBlock(true);
    }

    debouncedTouchInterval();
  };

  const switchStorage = (
    storage: StorageSettings,
    account: StorageValues<StorageType>
  ) => {
    if (clickBlock) return;
    // storage switch is executed in FilesListStore.prepareOpen
    // when FileLoader is executed
    ApplicationActions.changePage(
      `${ApplicationStore.getApplicationSetting(
        "UIPrefix"
      )}files/${MainFunctions.serviceNameToStorageCode(storage.name)}/${
        account[`${storage.name}_id` as keyof typeof account]
      }/-1`
    );
    setActiveStorage(storage.name);
    setActiveAccountId(account[`${storage.name}_id` as keyof typeof account]);
  };

  const onUser = () => {
    setActiveAccountId(UserInfoStore.getUserInfo("storage").id);
    setActiveStorage(UserInfoStore.getUserInfo("storage").type.toLowerCase());
    setTimeout(SmartTableActions.recalculateDimensions, 0);
  };

  useEffect(() => {
    UserInfoStore.addChangeListener(INFO_UPDATE, onUser);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, onUser);
    UserInfoStore.addChangeListener(STORAGE_SWITCH, onUser);
    UserInfoStore.addChangeListener(NOTIFICATION_UPDATE, onUser);
    UserInfoActions.getUserStorages();
    UserInfoActions.getStoragesConfiguration();
    UserInfoStore.addChangeListener(STORAGES_CONFIG_UPDATE, onConfigLoaded);
    ApplicationStore.addChangeListener(CONFIG_LOADED, onConfigLoaded);
    FilesListStore.addListener(RECENT_FILES_LOAD, onRecentFilesLoad);

    return () => {
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUser);
      UserInfoStore.removeChangeListener(STORAGES_UPDATE, onUser);
      UserInfoStore.removeChangeListener(
        STORAGES_CONFIG_UPDATE,
        onConfigLoaded
      );
      UserInfoStore.removeChangeListener(STORAGE_SWITCH, onUser);
      UserInfoStore.removeChangeListener(NOTIFICATION_UPDATE, onUser);
      ApplicationStore.removeChangeListener(CONFIG_LOADED, onConfigLoaded);
      FilesListStore.removeListener(RECENT_FILES_LOAD, onRecentFilesLoad);
    };
  }, []);

  const { externalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");

  const storagesSettings = ApplicationStore.getApplicationSetting(
    "storagesSettings"
  ) as Array<StorageSettings>;
  const finalStorage = storage || activeStorage;
  const finalAccountId = account || activeAccountId;
  const storagesInfo = UserInfoStore.getStoragesInfo() as UserStoragesInfo;
  const isUserInfoLoaded =
    UserInfoStore.getUserInfo("isLoggedIn") &&
    UserInfoStore.getUserInfo("isFullInfo");
  const areRecentFilesAvailable = FilesListStore.getRecentFiles().length > 0;
  let storagesList = _.where(storagesSettings, { active: true });
  const orderingFunction = UserInfoStore.getStoragesOrderingFunction();
  storagesList = storagesList.sort(orderingFunction);

  if (externalStoragesAvailable === false) {
    return null;
  }
  if (mode === "header") {
    // TODO
    return null;
  }

  return (
    <ResponsiveDrawer isMobile={isMobile} onTouchMove={onTouchMove}>
      <ToolbarSpacer />
      <KudoList>
        <NotificationBarSpacer />
        {/* Recent files switch */}
        {isUserInfoLoaded === true && areRecentFilesAvailable === true ? (
          <RecentSwitch isMobile={isMobile} />
        ) : null}
        {/* Storages management link */}
        <AddStorageLink />
        {/* Accounts list */}
        {storagesList.map(storageObject => {
          const accounts = storagesInfo[storageObject.name] || [];
          if (!accounts?.length) return null;
          const isActive =
            storageObject.name.toLowerCase() === finalStorage.toLowerCase() &&
            _.pluck(accounts, `${storageObject.name}_id`).indexOf(
              finalAccountId
            ) > -1;
          return (
            <StorageBlock
              key={`storage_${storageObject.name}`}
              storageInfo={storageObject}
              isActive={isActive}
              accounts={accounts}
              finalAccountId={finalAccountId.toString()}
              switchStorage={switchStorage}
            />
          );
        })}
      </KudoList>
      <Footer isSideBar />
    </ResponsiveDrawer>
  );
}
