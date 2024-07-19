import React, { forwardRef, useCallback, useEffect, useState } from "react";
import { CircularProgress } from "@mui/material";
import { browserHistory } from "react-router";
import { makeStyles } from "@material-ui/core/styles";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import PropTypes from "prop-types";

import availableSvg from "../../../assets/images/snacks/editAvailable.svg";
import FilesListActions from "../../../actions/FilesListActions";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";

const useStyles = makeStyles(theme => ({
  root: {
    "@media (min-width:600px)": {
      minWidth: "344px !important"
    }
  },
  card: {
    padding: "10px 10px 10px 10px",
    backgroundColor: theme.palette.VADER,
    width: "320px",
    // borderRadius: "5px",
    borderRadius: 0,
    color: theme.palette.LIGHT,
    textAlign: "left",
    fontSize: "12px",
    lineHeight: "20px",
    fontFamily: theme.kudoStyles.FONT_STACK
  },
  cardHeader: {
    display: "flex",
    justifyContent: "space-between",
    alignItems: "center"
  },
  cardBody: {
    display: "flex",
    justifyContent: "space-between"
  },
  btnYes: {
    padding: "8px 20px",
    textDecoration: "none",
    width: "145px",
    display: "block",
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    fontWeight: 600,
    borderWidth: "1px",
    borderColor: theme.palette.OBI,
    // borderRadius: "5px",
    borderRadius: 0,
    marginTop: "10px",
    textAlign: "center",
    "&:hover": {
      backgroundColor: theme.palette.OBI,
      borderColor: theme.palette.LIGHT,
      color: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    },
    "&:focus": {
      backgroundColor: theme.palette.OBI,
      borderColor: theme.palette.LIGHT,
      color: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    },
    "&:visited": {
      backgroundColor: theme.palette.OBI,
      borderColor: theme.palette.LIGHT,
      color: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    }
  },
  btnNo: {
    padding: "8px 20px",
    textDecoration: "none",
    width: "145px",
    display: "block",
    backgroundColor: theme.palette.VENERO,
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    fontWeight: 600,
    borderWidth: "1px",
    borderColor: theme.palette.VENERO,
    // borderRadius: "5px",
    borderRadius: 0,
    marginTop: "10px",
    textAlign: "center",
    "&:hover": {
      backgroundColor: theme.palette.VENERO,
      borderColor: theme.palette.LIGHT,
      color: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    }
  }
}));

const EditAvailable = forwardRef((props, ref) => {
  const classes = useStyles();

  const [needUpdate, setNeedUpdate] = useState(false);
  const [updating, setUpdating] = useState(false);

  const { id, message, isLatestVersion } = props;

  /**
   * Function checks if there is free edit spot for session
   * and changes mode to edit if so
   * @type function
   */
  function takeEditPermission() {
    FilesListActions.takeEditPermission();
  }

  const { closeSnackbar } = useSnackbar();
  const handleDismiss = useCallback(() => {
    closeSnackbar(id);
  }, [closeSnackbar]);

  const handleAccept = () => {
    if (isLatestVersion) {
      takeEditPermission();
      handleDismiss();
    } else {
      setNeedUpdate(true);
    }
  };

  const updateAndAccept = () => {
    setUpdating(true);

    // update
    XenonConnectionActions.postMessage({ messageName: "reopen" });
    setTimeout(() => {
      takeEditPermission();
      setUpdating(false);
      handleDismiss();
      handleAccept();
    }, 3000);
  };

  // close snack if left file page
  useEffect(() => {
    const stopListen = browserHistory.listen(location => {
      if (!location.pathname.includes("/file/")) {
        handleDismiss();
      }
    });

    return () => {
      stopListen();
    };
  }, []);

  const getConfirmButtonText = () => {
    if (updating) {
      return "updating";
    }
    if (needUpdate) {
      return "ok";
    }
    return "Yes";
  };

  return (
    <SnackbarContent ref={ref} className={classes.root}>
      <div className={classes.card}>
        <div className={classes.cardHeader}>
          <img
            style={{ width: "40px", marginRight: "10px" }}
            src={availableSvg}
            alt="available"
          />
          {needUpdate ? (
            <FormattedMessage
              id="needToUpdateBeforeTakingEdit"
              values={{
                version: <FormattedMessage id="latestVersion" />
              }}
            />
          ) : (
            <FormattedMessage id={message} />
          )}
        </div>
        <div className={classes.cardBody}>
          <button
            className={classes.btnNo}
            onClick={handleDismiss}
            onKeyDown={handleDismiss}
            type="button"
            aria-label="No"
          >
            <FormattedMessage id="No" />
          </button>
          <button
            className={classes.btnYes}
            onClick={needUpdate ? updateAndAccept : handleAccept}
            onKeyDown={needUpdate ? updateAndAccept : handleAccept}
            type="button"
          >
            {updating ? (
              <CircularProgress
                size={11}
                sx={{
                  color: "#FFFFFF",
                  position: "relative",
                  marginRight: "5px"
                }}
              />
            ) : null}
            <FormattedMessage id={getConfirmButtonText()} />
          </button>
        </div>
      </div>
    </SnackbarContent>
  );
});

export default EditAvailable;

EditAvailable.propTypes = {
  id: PropTypes.number.isRequired,
  message: PropTypes.string.isRequired,
  isLatestVersion: PropTypes.bool.isRequired
};
