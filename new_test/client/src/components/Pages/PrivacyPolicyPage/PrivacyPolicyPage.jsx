import React, { useEffect, useState } from "react";
import { FormattedMessage } from "react-intl";
import {
  AppBar,
  Dialog,
  Grid,
  IconButton,
  makeStyles,
  Toolbar,
  Typography
} from "@material-ui/core";
import CloseIcon from "@material-ui/icons/Close";
import ApplicationStore, {
  TOGGLE_TERMS
} from "../../../stores/ApplicationStore";
import MainFunctions from "../../../libraries/MainFunctions";
import ToolbarSpacer from "../../ToolbarSpacer";

const useStyles = makeStyles(theme => ({
  root: {
    overflowX: "hidden",
    backgroundColor: theme.palette.DARK
  },
  header: {
    justifyContent: "center",
    backgroundColor: theme.palette.DARK
  },
  closeButton: {
    position: "absolute",
    right: theme.spacing(2)
  },
  caption: {
    fontSize: "1rem",
    color: theme.palette.LIGHT,
    padding: 0,
    fontStyle: "normal"
  },
  content: {
    "& p, & span": {
      color: `${theme.palette.REY} !important`
    }
  }
}));

export default function PrivacyPolicyPage() {
  const isTermsPage = MainFunctions.detectPageType().includes("terms");
  const [isVisible, setVisible] = useState(isTermsPage);
  const [ToUText, setToUText] = useState("");

  const onTermsUpdate = () => {
    setVisible(!isVisible);
  };

  useEffect(() => {
    ApplicationStore.addChangeListener(TOGGLE_TERMS, onTermsUpdate);
    return () => {
      ApplicationStore.removeChangeListener(TOGGLE_TERMS, onTermsUpdate);
    };
  }, []);

  useEffect(() => {
    if (ToUText.length === 0 && isVisible === true) {
      import("../../../termsofuse.html?raw").then(fileText => {
        setToUText(fileText.default);
      });
    }
  }, [isVisible]);

  const handleClose = () => {
    setVisible(false);
    if (isTermsPage) {
      window.history.back();
    }
  };

  const classes = useStyles();
  return (
    <Dialog
      fullScreen
      keepMounted
      open={isVisible}
      onClose={handleClose}
      classes={{ paper: classes.root }}
    >
      <AppBar>
        <Toolbar className={classes.header}>
          <Typography variant="h6" className={classes.caption}>
            <FormattedMessage id="terms" />
          </Typography>
          <IconButton onClick={handleClose} className={classes.closeButton}>
            <CloseIcon />
          </IconButton>
        </Toolbar>
      </AppBar>
      <Grid container justifyContent="center" spacing={1}>
        <ToolbarSpacer />
        <ToolbarSpacer />
        <Grid
          item
          className={classes.content}
          xs={12}
          sm={9}
          md={6}
          // eslint-disable-next-line react/no-danger
          dangerouslySetInnerHTML={{
            __html: isVisible === true ? ToUText : null
          }}
        />
      </Grid>
    </Dialog>
  );
}
