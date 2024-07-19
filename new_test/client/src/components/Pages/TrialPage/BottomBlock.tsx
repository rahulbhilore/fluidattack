import React from "react";
import PropTypes from "prop-types";
import clsx from "clsx";
import { FormattedMessage } from "react-intl";
import Grid from "@material-ui/core/Grid";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Typography from "@material-ui/core/Typography";
import Button from "@material-ui/core/Button";
import ApplicationStore from "../../../stores/ApplicationStore";
import Tracker from "../../../utils/Tracker";

const useStyles = makeStyles(theme => ({
  experience: {
    // @ts-ignore
    color: theme.palette.REY,
    fontSize: theme.typography.pxToRem(16),
    fontWeight: "bold",
    textAlign: "center",
    margin: theme.spacing(2, 0, 1),
    [theme.breakpoints.down("sm")]: {
      margin: theme.spacing(1, 0)
    }
  },
  partOfTrinity: {
    textAlign: "center",
    fontSize: theme.typography.pxToRem(12),
    letterSpacing: "0.3px"
  },
  installPromo: {
    fontSize: theme.typography.pxToRem(12),
    letterSpacing: "0.3px",
    textAlign: "center",
    margin: theme.spacing(2, 0, 1),
    [theme.breakpoints.down("sm")]: {
      margin: theme.spacing(1, 0)
    },
    "& a": {
      // @ts-ignore
      color: theme.palette.YELLOW_BUTTON
    }
  },
  button: {
    height: "36px",
    width: "100%",
    textTransform: "uppercase",
    margin: "0 0 10px",
    // @ts-ignore
    backgroundColor: theme.palette.YELLOW_BUTTON,
    border: "solid 1px #E7D300",
    // @ts-ignore
    color: theme.palette.DARK,
    fontWeight: "bold",
    fontSize: theme.typography.pxToRem(12),
    textShadow: "0 1px rgba(0, 0, 0, 0.1)",
    boxShadow: "none",
    "&:hover": {
      // @ts-ignore
      backgroundColor: theme.palette.YELLOW_BUTTON
    }
  },
  buttonEmpty: {
    backgroundColor: "transparent!important",
    fontWeight: "normal",
    // @ts-ignore
    color: theme.palette.GREY_TEXT
  }
}));

type Props = {
  hasTrialEnded: boolean;
  redirectUserToKudo: () => void;
};

export default function BottomBlock({
  hasTrialEnded,
  redirectUserToKudo
}: Props) {
  const classes = useStyles();

  const renderCommanderLink = React.useCallback(
    (msg: string[]) => (
      <a
        href="https://www.graebert.com/cad-software/ares-commander"
        target="_blank"
        rel="noopener noreferrer"
      >
        {msg}
      </a>
    ),
    []
  );

  const renderTouchLink = React.useCallback(
    (msg: string[]) => (
      <a
        href="https://www.graebert.com/cad-software/ares-touch"
        target="_blank"
        rel="noopener noreferrer"
      >
        {msg}
      </a>
    ),
    []
  );
  return (
    <Grid container justifyContent="center">
      <Grid item xs={8} sm={6} lg={4} xl={3}>
        <Typography className={classes.experience}>
          <FormattedMessage id="experienceTheTrinity" />
        </Typography>
        <Typography className={classes.partOfTrinity}>
          <FormattedMessage id="partOfTheTrinity" />
        </Typography>
        <Typography className={classes.installPromo}>
          <FormattedMessage
            id="installACAT"
            values={{
              aclink: renderCommanderLink,
              atlink: renderTouchLink
            }}
          />
        </Typography>
        <Button
          className={
            hasTrialEnded
              ? classes.button
              : clsx(classes.button, classes.buttonEmpty)
          }
          onClick={redirectUserToKudo}
          data-component="launchAK"
        >
          <FormattedMessage
            id="launchAK"
            values={{
              product: ApplicationStore.getApplicationSetting("product")
            }}
          />
        </Button>
        <Button
          className={classes.button}
          onClick={() => {
            Tracker.sendGAEvent("More", "ARES Kudo");
            location.href =
              ApplicationStore.getApplicationSetting(
                "customization"
              ).learnMoreURL;
          }}
        >
          <FormattedMessage id="learnMore" />
        </Button>
        <Button
          className={clsx(classes.button, classes.buttonEmpty)}
          onClick={() => {
            Tracker.sendGAEvent("Contact", "ARES Kudo");
            // @ts-ignore
            const { Intercom } = window;
            if (Intercom) {
              Intercom("showNewMessage");
            } else {
              window.open(
                "https://www.graebert.com/company/contactus",
                "_blank",
                "noopener,noreferrer"
              );
            }
          }}
        >
          <FormattedMessage id="contactUs" />
        </Button>
      </Grid>
    </Grid>
  );
}

BottomBlock.propTypes = {
  hasTrialEnded: PropTypes.bool.isRequired,
  redirectUserToKudo: PropTypes.func.isRequired
};
