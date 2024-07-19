import { Typography, styled } from "@mui/material";
import Immutable, { List } from "immutable";
import React, { ReactNode, useCallback, useEffect, useState } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import AdminActions from "../../../actions/AdminActions";
import ApplicationActions from "../../../actions/ApplicationActions";
import ModalActions from "../../../actions/ModalActions";
import AdminStore, {
  REFRESH_USERS_LIST,
  USERS_EVENT
} from "../../../stores/AdminStore";
import ApplicationStore from "../../../stores/ApplicationStore";
import UserInfoStore, {
  INFO_UPDATE,
  STORAGES_UPDATE
} from "../../../stores/UserInfoStore";
import Footer from "../../Footer/Footer";
import SmartTable from "../../SmartTable/SmartTable";
import userCompliance from "../../SmartTable/tables/users/Compliance";
import userOptions from "../../SmartTable/tables/users/Options";
import TableSorts from "../../SmartTable/types/TableSorts";
import ToolbarSpacer from "../../ToolbarSpacer";
import MainFunctions from "../../../libraries/MainFunctions";

const StyledBoldText = styled(Typography)(() => ({
  fontSize: 12,
  fontWeight: "bold"
}));

// DK: Sort options just by amount of allowed features.
const optionsSortFunc = (item: Immutable.Map<string, unknown>) =>
  item?.get("options")?.toString().match(/true/g)?.length;

const columns = List([
  { dataKey: "username", label: "username", width: 0.25 },
  { dataKey: "isAdmin", label: "role", width: 0.05 },
  { dataKey: "email", label: "email", width: 0.2 },
  { dataKey: "_id", label: "id", width: 0.15 },
  ApplicationStore.getApplicationSetting("customization").showCompliance
    ? { dataKey: "compliance", label: "complianceLabel", width: 0.1 }
    : { dataKey: "licenseType", label: "licenseType", width: 0.1 },
  { dataKey: "enabled", label: "status", width: 0.05 },
  { dataKey: "options", label: "options", width: 0.2 }
]);

const compareBy =
  (sortKey: string) =>
  (x: Immutable.Map<string, string>, y: Immutable.Map<string, string>) =>
    (x.get(sortKey) ?? "")
      .toLocaleLowerCase()
      .localeCompare((y.get(sortKey) ?? "").toLocaleLowerCase());

const customSorts: TableSorts<unknown> = {
  options: optionsSortFunc as never,
  username: compareBy("username"),
  isAdmin: (x, y) => (x.get("isAdmin") && !y.get("isAdmin") ? 1 : -1),
  enabled: (x, y) => (x.get("enabled") && !y.get("enabled") ? 1 : -1),
  email: compareBy("email"),
  _id: compareBy("_id"),
  ...(ApplicationStore.getApplicationSetting("customization").showCompliance
    ? { compliance: compareBy("compliance") }
    : { licenseType: compareBy("licenseType") })
};

type Fields =
  | "_id"
  | "compliance"
  | "email"
  | "enabled"
  | "isAdmin"
  | "licenseType"
  | "options"
  | "process"
  | "username";

const presentation = Immutable.Map<
  Fields,
  (
    val: Record<Fields, string> & { getter: (key: string) => string }
  ) => ReactNode
>({
  _id: val => (
    <Typography
      sx={{
        userSelect: "text",
        fontSize: 12
      }}
    >
      {val?.getter("graebertId")}
      <br />
      {val._id}
    </Typography>
  ),
  username: val => (
    <Typography
      sx={{
        color: theme => theme.palette.OBI,
        fontWeight: "bold",
        fontSize: 12
      }}
    >
      {val.username}
    </Typography>
  ),
  email: val => (
    <Typography data-component="user-email" fontSize={12}>
      {(val.email || "").trim()}
    </Typography>
  ),
  compliance: userCompliance,
  enabled: val => (
    <FormattedMessage id={val.enabled ? "enabled" : "disabled"} />
  ),
  isAdmin: val => (
    <StyledBoldText
      data-component="user_role"
      data-role={val.isAdmin ? "admin" : "user"}
    >
      <FormattedMessage id={val.isAdmin ? "admin" : "user"} />
    </StyledBoldText>
  ),
  options: userOptions,
  licenseType: val => {
    if (!val.licenseType) return null;
    return <StyledBoldText>{val.licenseType}</StyledBoldText>;
  }
});

type PropType = {
  params?: { query?: string };
};

export default function UsersPage({ params = {} }: PropType) {
  const { query = "" } = params;
  const [users, setUsers] = useState(List());
  const [isLoading, setIsLoading] = useState(true);
  const [isLazy, setIsLazy] = useState(false);
  const [accountId, setAccountId] = useState("");

  const { formatMessage } = useIntl();

  const checkAccess = () => {
    const { isAdmin, isFullInfo } = UserInfoStore.getUserInfo();
    const { type: storageType } = UserInfoStore.getUserInfo("storage") as {
      type: string;
      id: string;
    };
    const storage = MainFunctions.serviceNameToStorageCode(storageType);
    if (isFullInfo && !isAdmin) {
      ApplicationActions.changePage(`/files/${storage}/${accountId}/-1`);
      return false;
    }
    return true;
  };

  const handleUserDelete = useCallback(
    (selectedRows: string[]) => {
      if (selectedRows.length === 1) {
        ModalActions.deleteUser(selectedRows[0]);
      }
    },
    [ModalActions]
  );

  const isRowLoaded = ({ index }: { index: number }) => {
    if (query?.length > 0) return true;
    return AdminStore.isUserLoaded(index);
  };

  const loadMoreRows = () => {
    if (query?.length > 0) return true;
    if (!AdminStore.isUsersToLoad()) return false;
    if (isLazy || isLoading) return false;
    setIsLazy(true);
    return AdminActions.loadUsers(AdminStore.getPageToken());
  };

  const onUsersUpdate = () => {
    setUsers(AdminStore.getUsersInfo());
    setIsLoading(false);
    setIsLazy(false);
  };

  const refreshUsersList = useCallback(() => {
    setIsLoading(true);
    if (query?.length > 0) {
      // search
      AdminActions.findUsers(query.trim().toLowerCase());
    } else {
      AdminActions.loadUsers();
    }
  }, [query]);

  const onUserInfoUpdate = () => {
    const { isFullInfo } = UserInfoStore.getUserInfo();
    if (!isFullInfo) return;
    UserInfoStore.removeChangeListener(INFO_UPDATE, onUserInfoUpdate);
    checkAccess();
  };

  const handleRefresh = useCallback(() => {
    setUsers(List());
    refreshUsersList();
    setIsLazy(false);
  }, []);

  const onUserStorageUpdate = () => {
    const { id } = UserInfoStore.getUserInfo("storage");
    setAccountId(id);
  };

  useEffect(() => {
    refreshUsersList();
  }, [query]);

  useEffect(() => {
    const defaultTitle = ApplicationStore.getApplicationSetting("defaultTitle");
    document.title = `${defaultTitle} | ${formatMessage({ id: "users" })}`;

    AdminStore.addListener(USERS_EVENT, onUsersUpdate);
    AdminStore.addListener(REFRESH_USERS_LIST, handleRefresh);
    UserInfoStore.addChangeListener(STORAGES_UPDATE, onUserStorageUpdate);
    if (document.body.classList.contains("init")) {
      document.body.classList.remove("init");
      document.body.style.backgroundImage = "";
    }
    if (document.body.classList.contains("profile")) {
      document.body.classList.remove("profile");
    }
    if (!checkAccess()) {
      UserInfoStore.addChangeListener(INFO_UPDATE, onUserInfoUpdate);
    }

    return () => {
      AdminStore.removeListener(USERS_EVENT, onUsersUpdate);
      AdminStore.removeListener(REFRESH_USERS_LIST, handleRefresh);
      UserInfoStore.removeChangeListener(INFO_UPDATE, onUserInfoUpdate);
      UserInfoStore.addChangeListener(STORAGES_UPDATE, onUserStorageUpdate);
    };
  }, []);

  return (
    <main style={{ flexGrow: 1, padding: "10px 20px 0" }}>
      <ToolbarSpacer />
      <SmartTable
        noDataCaption="noUserInDB"
        data={users}
        columns={columns}
        tableType="users"
        presentation={presentation}
        isLoading={isLoading}
        handleDelete={handleUserDelete}
        heightOffsets={50 + 10 + 45}
        customSorts={customSorts}
        isLazy={isLazy}
        isRowLoaded={isRowLoaded}
        loadMoreRows={loadMoreRows}
      />
      <Footer customMargins={[0, 20, 0, 0]} customPaddings={[0, 0, 0, 22]} />
    </main>
  );
}
