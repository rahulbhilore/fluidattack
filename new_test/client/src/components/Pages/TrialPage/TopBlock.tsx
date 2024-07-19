import React, { useCallback, useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import { Grid } from "@material-ui/core";
import UserInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import Storage from "../../../utils/Storage";
import ApplicationStore from "../../../stores/ApplicationStore";
import logoImage from "../../../assets/images/kudo-logo-small.svg";

const useStyles = makeStyles(theme => ({
  root: {
    // @ts-ignore
    backgroundColor: theme.palette.SNOKE
  },
  logoBlock: {
    margin: "0 auto"
  },
  logoImage: {
    margin: theme.spacing(3, 0),
    [theme.breakpoints.down("md")]: {
      margin: theme.spacing(1, 0)
    },
    [theme.breakpoints.down("sm")]: {
      margin: 0
    }
  },
  textBlock: {
    width: "360px",
    margin: "0 auto",
    textAlign: "center"
  },
  mainMessage: {
    fontSize: "20px",
    fontWeight: "bold",
    // @ts-ignore
    color: theme.palette.LIGHT,
    textAlign: "center",
    marginBottom: theme.spacing(2),
    marginTop: 0,
    [theme.breakpoints.down("md")]: {
      marginBottom: theme.spacing(1)
    },
    [theme.breakpoints.down("sm")]: {
      margin: 0
    }
  },
  greeting: {
    textAlign: "center",
    marginBottom: theme.spacing(3),
    fontSize: theme.typography.pxToRem(14),
    [theme.breakpoints.down("lg")]: {
      marginBottom: theme.spacing(2)
    },
    [theme.breakpoints.down("md")]: {
      marginBottom: theme.spacing(1)
    },
    [theme.breakpoints.down("sm")]: {
      margin: 0
    }
  },
  daysLeft: {
    marginBottom: theme.spacing(3),
    fontSize: theme.typography.pxToRem(24),
    // @ts-ignore
    color: theme.palette.YELLOW_BUTTON,
    fontWeight: "bold",
    textAlign: "center",
    [theme.breakpoints.down("lg")]: {
      marginBottom: theme.spacing(2)
    },
    [theme.breakpoints.down("md")]: {
      marginBottom: theme.spacing(1)
    },
    [theme.breakpoints.down("sm")]: {
      margin: 0
    }
  },
  daysMessage: {
    fontSize: theme.typography.pxToRem(14),
    fontWeight: "bold",
    textTransform: "uppercase"
  },
  controlsText: {
    textAlign: "center",
    fontSize: theme.typography.pxToRem(12),
    letterSpacing: "0.3px",
    marginBottom: theme.spacing(3),
    [theme.breakpoints.down("lg")]: {
      marginBottom: theme.spacing(2)
    },
    [theme.breakpoints.down("md")]: {
      marginBottom: theme.spacing(1)
    },
    [theme.breakpoints.down("sm")]: {
      marginBottom: theme.spacing(1)
    }
  }
}));

export default function TopBlock() {
  const classes = useStyles();

  const [daysLeft, setDaysLeft] = useState(UserInfoStore.getDaysLeftAmount());
  const [name, setName] = useState(
    `${UserInfoStore.getUserInfo("name") || Storage.store("name") || ""} ${
      UserInfoStore.getUserInfo("surname") || Storage.store("surname") || ""
    }`
  );

  const onUserInfoUpdate = useCallback(() => {
    setDaysLeft(UserInfoStore.getDaysLeftAmount());
    setName(
      `${UserInfoStore.getUserInfo("name") || Storage.store("name") || ""} ${
        UserInfoStore.getUserInfo("surname") || Storage.store("surname") || ""
      }`
    );
  }, []);

  useEffect(() => {
    UserInfoStore.addListener(INFO_UPDATE, onUserInfoUpdate);
    return () => {
      UserInfoStore.removeListener(INFO_UPDATE, onUserInfoUpdate);
    };
  });

  const renderGreeting = React.useCallback(
    (msg: React.ReactNode[], textClass: string) => (
      <Typography className={textClass}>{msg}</Typography>
    ),
    []
  );

  const renderDaysLeft = React.useCallback(
    (msg: React.ReactNode[], textClass: string) =>
      msg ? <Typography className={textClass}>{msg}</Typography> : null,
    []
  );

  const renderDaysMessage = React.useCallback(
    (msg: React.ReactNode[], textClass: string) => (
      <Typography className={textClass}>{msg}</Typography>
    ),
    []
  );
  return (
    <Grid container justifyContent="center" className={classes.root}>
      <Grid item xs={8} sm={6} lg={4} xl={3}>
        <Grid item xs={6} sm={4} className={classes.logoBlock}>
          <img className={classes.logoImage} src={logoImage} alt="ARES Kudo" />
        </Grid>
        <Typography className={classes.mainMessage}>
          <FormattedMessage id="wowTrial" />
        </Typography>
        <FormattedMessage
          id="hiYouHaveNDaysLeft"
          values={{
            name,
            daysLeft,
            greeting: msg => renderGreeting(msg, classes.greeting),
            dayblock: msg => renderDaysLeft(msg, classes.daysLeft),
            daymessage: msg => renderDaysMessage(msg, classes.daysMessage)
          }}
        />
        <Typography className={classes.controlsText}>
          <FormattedMessage
            id="useButtonsBelowToLaunch"
            values={{
              product: ApplicationStore.getApplicationSetting("product")
            }}
          />
        </Typography>
      </Grid>
    </Grid>
  );
}
