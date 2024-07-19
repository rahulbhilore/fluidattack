import React, { Component } from "react";
import ListItem from "@material-ui/core/ListItem";
import ListItemText from "@material-ui/core/ListItemText";
import { styled } from "@material-ui/core/styles";
import propTypes from "prop-types";
import SmartTooltip from "../SmartTooltip/SmartTooltip";
import AccountName from "./AccountName";

const AccountItem = styled(ListItem)(({ theme }) => ({
  color: theme.palette.CLONE,
  backgroundColor: theme.palette.secondary.main,
  margin: 0,
  padding: "5px 10px",
  width: theme.kudoStyles.SIDEBAR_WIDTH,
  cursor: "pointer",
  "&:hover,&:focus,&:active": {
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT
  },
  "&.Mui-selected": {
    "&, &:hover, &:focus, &:active": {
      backgroundColor: theme.palette.OBI,
      "& span": {
        color: theme.palette.LIGHT,
        fontWeight: "bold"
      }
    }
  },
  [theme.breakpoints.down("xs")]: {
    width: theme.kudoStyles.MOBILE_SIDEBAR_WIDTH,
    padding: "3px 10px"
  }
}));

export default class AccountBlock extends Component {
  static propTypes = {
    switchStorage: propTypes.func.isRequired,
    storageName: propTypes.string.isRequired,
    displayName: propTypes.string,
    account: propTypes.shape({
      [propTypes.string]: propTypes.string
    }).isRequired,
    finalAccountId: propTypes.string.isRequired
  };

  static defaultProps = {
    displayName: null
  };

  constructor(props) {
    super(props);
    this.state = {
      isTooltipToShow: false
    };
    this.rrr = null;
  }

  shouldComponentUpdate(nextProps, nextState) {
    const { isTooltipToShow } = this.state;
    const { isTooltipToShow: nextTooltip } = nextState;
    const { storageName, displayName, finalAccountId, account } = this.props;
    const {
      storageName: nextStorageName,
      displayName: nextDisplayName,
      finalAccountId: nextFinalAccount,
      account: nextAccount
    } = nextProps;
    return (
      isTooltipToShow !== nextTooltip ||
      finalAccountId !== nextFinalAccount ||
      storageName !== nextStorageName ||
      displayName !== nextDisplayName ||
      JSON.stringify(account) !== JSON.stringify(nextAccount)
    );
  }

  isTooltipShow = e => {
    this.setState({
      isTooltipToShow: e
    });
  };

  switchAccount = () => {
    const { switchStorage, account } = this.props;
    switchStorage(account);
  };

  render() {
    const { account, storageName, displayName, finalAccountId } = this.props;
    const accountName = account[`${storageName}_username`];
    const { isTooltipToShow } = this.state;

    return (
      <SmartTooltip
        forcedOpen={isTooltipToShow}
        placement="right"
        title={account[`${storageName}_username`]}
      >
        <AccountItem
          selected={account[`${storageName}_id`] === finalAccountId}
          onClick={this.switchAccount}
          onTouchEnd={this.switchAccount}
          className={`storageItem ${
            account[`${storageName}_id`] === finalAccountId
              ? "selectedAccount"
              : ""
          } ${storageName}`}
          data-component={`storage-block-${storageName}`}
          ref={ref => {
            this.rrr = ref;
          }}
        >
          <ListItemText>
            <AccountName
              accountName={accountName}
              showTooltip={this.isTooltipShow}
              storageName={storageName}
              isWebDav={
                (storageName === "webdav" || storageName === "nextcloud") &&
                accountName.includes("at")
              }
              displayName={displayName}
              parentWidth={this.rrr?.offsetWidth || 0}
            />
          </ListItemText>
        </AccountItem>
      </SmartTooltip>
    );
  }
}
