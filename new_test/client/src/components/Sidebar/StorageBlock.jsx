import React, { Component } from "react";
import ListItem from "@material-ui/core/ListItem";
import ListItemIcon from "@material-ui/core/ListItemIcon";
import ListItemText from "@material-ui/core/ListItemText";
import { styled } from "@material-ui/core/styles";
import propTypes from "prop-types";
import StorageIcons from "../../constants/appConstants/StorageIcons";
import AccountBlock from "./AccountBlock";
import StorageSizeBar from "./StorageSizeBar";
import MainFunctions from "../../libraries/MainFunctions";

const StorageItem = styled(ListItem)(({ theme }) => ({
  backgroundColor: theme.palette.primary.main,
  margin: 0,
  padding: "5px 10px",
  width: theme.kudoStyles.SIDEBAR_WIDTH,
  "&.Mui-selected": {
    "&, &:hover, &:focus, &:active": {
      backgroundColor: theme.palette.LIGHT,
      "& span": {
        color: theme.palette.secondary.main
      }
    }
  },
  [theme.breakpoints.down("xs")]: {
    width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH,
    padding: "4px 10px"
  }
}));

const StorageIcon = styled(ListItemIcon)({
  width: 23,
  minWidth: 23,
  maxHeight: 23,
  marginRight: 10,
  "& img": {
    width: 23,
    maxHeight: 23
  }
});

const StorageName = styled(ListItemText)(({ theme }) => ({
  "& span": {
    fontSize: 12,
    color: theme.kudoStyles.LIGHT,
    letterSpacing: 0,
    fontWeight: "normal",
    fontFamily: theme.kudoStyles.FONT_STACK
  }
}));

export default class StorageBlock extends Component {
  static propTypes = {
    switchStorage: propTypes.func.isRequired,
    storageInfo: propTypes.shape({
      name: propTypes.string.isRequired,
      displayName: propTypes.string.isRequired
    }).isRequired,
    isActive: propTypes.bool.isRequired,
    accounts: propTypes.arrayOf(
      propTypes.shape({
        [propTypes.string]: propTypes.string
      }).isRequired
    ).isRequired,
    finalAccountId: propTypes.string.isRequired
  };

  shouldComponentUpdate(nextProps) {
    const { storageInfo, isActive, accounts, finalAccountId } = this.props;
    const {
      storageInfo: nextStorageInfo,
      isActive: nextActive,
      accounts: nextAccounts,
      finalAccountId: nextFinalAccount
    } = nextProps;
    return (
      isActive !== nextActive ||
      finalAccountId !== nextFinalAccount ||
      accounts.length !== nextAccounts.length ||
      JSON.stringify(accounts) !== JSON.stringify(nextAccounts) ||
      Object.keys(storageInfo).length !== Object.keys(nextStorageInfo).length
    );
  }

  changeAccount = account => {
    const { switchStorage, storageInfo } = this.props;
    switchStorage(storageInfo, account);
  };

  render() {
    const { isActive, storageInfo, accounts, finalAccountId } = this.props;
    const { name } = storageInfo;
    if (accounts.length === 0) return null;
    const icon =
      StorageIcons[
        `${storageInfo.name.toLowerCase()}${
          isActive ? "Active" : "Inactive"
        }SVG`
      ];
    return (
      <>
        <StorageItem selected={isActive}>
          <StorageIcon>
            <img src={icon} alt={storageInfo.displayName} />
          </StorageIcon>
          <StorageName>{storageInfo.displayName}</StorageName>
        </StorageItem>
        {accounts.map(account => {
          const accountId = account[`${storageInfo.name.toLowerCase()}_id`];
          const accountName =
            account[`${storageInfo.name.toLowerCase()}_username`];
          return (
            <AccountBlock
              account={account}
              key={`${accountId}_${btoa(
                MainFunctions.convertStringToBinary(accountName)
              )}`}
              storageName={storageInfo.name.toLowerCase()}
              displayName={
                account[`${storageInfo.name.toLowerCase()}_displayName`]
              }
              finalAccountId={finalAccountId}
              switchStorage={this.changeAccount}
            />
          );
        })}
        {name === "samples" ? <StorageSizeBar storage="samples" /> : null}
      </>
    );
  }
}
