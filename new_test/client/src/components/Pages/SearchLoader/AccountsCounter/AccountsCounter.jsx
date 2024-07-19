import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Grid from "@material-ui/core/Grid";
import Breadcrumbs from "@material-ui/core/Breadcrumbs";
import Link from "@material-ui/core/Link";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import ApplicationStore from "../../../../stores/ApplicationStore";
import ApplicationActions from "../../../../actions/ApplicationActions";
import HomeIcon from "../../../Breadcrumbs/HomeIcon";
import Separator from "../../../Breadcrumbs/Separator";
import userInfoStore from "../../../../stores/UserInfoStore";
import MainFunctions from "../../../../libraries/MainFunctions";

const useStyles = makeStyles(theme => ({
  root: {
    margin: "20px 0 3px 30px",
    paddingBottom: "17px",
    borderBottom: "1px solid #ececec"
  },
  breadcrumbs: {
    "& .MuiBreadcrumbs-separator": {
      margin: 0,
      color: theme.palette.primary.main
    }
  },
  message: {
    color: theme.palette.OBI,
    fontSize: "12px"
  },
  query: {
    display: "inline-block",
    fontWeight: "bold",
    fontSize: "12px"
  }
}));

export default function AccountsCounter({
  amount,
  storagesLoaded,
  query,
  isCustom = false,
  messages = {},
  customRootURL = ""
}) {
  const classes = useStyles();
  const { externalStoragesAvailable: areExternalStoragesAvailable } =
    ApplicationStore.getApplicationSetting("customization");
  const { type, id } = userInfoStore.getUserInfo("storage");
  const storageCode = MainFunctions.serviceNameToStorageCode(type);
  const rootURL =
    customRootURL ||
    `${ApplicationStore.getApplicationSetting(
      "UIPrefix"
    )}files/${storageCode}/${id}/-1`;
  if (!isCustom && areExternalStoragesAvailable === false) {
    if (amount === 0) {
      return (
        <Grid item xs={12} className={classes.root}>
          <Breadcrumbs
            className={classes.breadcrumbs}
            separator={<Separator />}
          >
            <Link
              href={rootURL}
              onClick={() => {
                ApplicationActions.changePage(rootURL);
              }}
            >
              <HomeIcon />
            </Link>
            <Typography variant="body2" className={classes.message}>
              <FormattedMessage id="noResultsFound" />
            </Typography>
          </Breadcrumbs>
        </Grid>
      );
    }
    return null;
  }
  return (
    <Grid item xs={12} className={classes.root}>
      {isCustom || storagesLoaded ? (
        <Breadcrumbs className={classes.breadcrumbs} separator={<Separator />}>
          <Link
            href={rootURL}
            onClick={e => {
              e.preventDefault();
              ApplicationActions.changePage(rootURL);
            }}
          >
            <HomeIcon />
          </Link>
          <Typography variant="body2" className={classes.message}>
            {amount === 1 ? (
              <FormattedMessage
                id={messages?.single || "searchOneStorageFound"}
              />
            ) : (
              <FormattedMessage
                id={messages?.multiple || "searchMultipleStoragesFound"}
                values={{
                  number: amount
                }}
              />
            )}{" "}
            <Typography
              variant="body1"
              className={classes.query}
              component="span"
            >{` ${query}`}</Typography>
          </Typography>
        </Breadcrumbs>
      ) : null}
    </Grid>
  );
}

AccountsCounter.propTypes = {
  amount: PropTypes.number,
  storagesLoaded: PropTypes.bool,
  query: PropTypes.string,
  isCustom: PropTypes.bool,
  messages: PropTypes.shape({
    multiple: PropTypes.string,
    single: PropTypes.string
  }),
  customRootURL: PropTypes.string
};

AccountsCounter.defaultProps = {
  amount: 0,
  storagesLoaded: false,
  query: "",
  isCustom: false,
  messages: {},
  customRootURL: ""
};
