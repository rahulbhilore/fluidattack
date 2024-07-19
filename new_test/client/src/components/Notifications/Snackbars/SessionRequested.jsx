import React, { forwardRef, useCallback, useEffect, useState } from "react";
import { browserHistory } from "react-router";
import { makeStyles } from "@material-ui/core/styles";
import { CircularProgress } from "@mui/material";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import PropTypes from "prop-types";
import Collapse from "@mui/material/Collapse";

import sessionRequestSvg from "../../../assets/images/snacks/sessionRequest.svg";
import FilesListActions from "../../../actions/FilesListActions";

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
    "&:hover, &:focus, &:visited": {
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
    "&:hover, &:focus, &:visited": {
      backgroundColor: theme.palette.VENERO,
      borderColor: theme.palette.LIGHT,
      color: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    }
  },
  btnName: {
    padding: "8px 20px",
    textDecoration: "none",
    width: "300px",
    display: "block",
    backgroundColor: theme.palette.VENERO,
    color: theme.palette.LIGHT,
    fontWeight: 300,
    borderWidth: "1px",
    borderColor: theme.palette.VENERO,
    // borderRadius: "5px",
    borderRadius: 0,
    marginTop: "10px",
    textAlign: "center",
    "&:hover, &:focus, &:visited": {
      borderColor: theme.palette.LIGHT,
      cursor: "pointer"
    }
  },
  btnBack: {
    padding: "8px 20px",
    textDecoration: "none",
    width: "300px",
    display: "block",
    backgroundColor: theme.palette.OBI,
    color: theme.palette.LIGHT,
    textTransform: "uppercase",
    fontWeight: 300,
    borderWidth: "1px",
    borderColor: theme.palette.OBI,
    // borderRadius: "5px",
    borderRadius: 0,
    marginTop: "10px",
    textAlign: "center",
    "&:hover, &:focus, &:visited": {
      borderColor: theme.palette.LIGHT,
      textTransform: "uppercase",
      cursor: "pointer"
    }
  }
}));

const SessionRequested = forwardRef((props, ref) => {
  const classes = useStyles();

  const { requests, id, accept, deny } = props;

  const { closeSnackbar } = useSnackbar();
  const [expanded, setExpanded] = useState(false);
  const [saving, setSaving] = useState(false);

  const handleExpandClick = useCallback(() => {
    setExpanded(oldExpanded => !oldExpanded);
  }, []);

  const close = useCallback(() => {
    closeSnackbar(id);
  }, [closeSnackbar]);

  const handleDismiss = () => {
    deny();
    close();
  };

  const handleAccept = request => {
    if (!saving) {
      setSaving(true);
      FilesListActions.saveFileInXenon()
        .then(() => {
          accept(request);
          setSaving(false);
          close();
        })
        .catch(() => {
          close();
        });
    }
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

  const tightenName = name =>
    name.length < 30 ? name : name.substring(0, 30).concat("...");

  let content;
  if (requests.length < 1) {
    content = null;
  } else if (requests.length === 1) {
    content = (
      <div className={classes.card}>
        <div className={classes.cardHeader}>
          <img
            style={{ width: "40px", marginRight: "10px" }}
            src={sessionRequestSvg}
            alt="sessionRequest"
          />
          <div>
            <FormattedMessage
              id="drawingSessionRequested"
              values={{
                username: <strong>{tightenName(requests[0].username)}</strong>
              }}
            />
          </div>
        </div>
        <div className={classes.cardBody}>
          <button
            className={classes.btnNo}
            onClick={handleDismiss}
            type="button"
            disabled={saving}
            aria-label="No"
          >
            <FormattedMessage id="No" />
          </button>
          <button
            className={classes.btnYes}
            onClick={() => handleAccept(requests[0])}
            type="button"
            disabled={saving}
          >
            {saving ? (
              <CircularProgress
                size={11}
                sx={{
                  color: "#FFFFFF",
                  position: "relative",
                  marginRight: "5px"
                }}
              />
            ) : null}
            <FormattedMessage id={saving ? "savingInProgress" : "Yes"} />
          </button>
        </div>
      </div>
    );
  } else {
    content = (
      <div className={classes.card}>
        <div className={classes.cardHeader}>
          <img
            style={{ width: "40px", marginRight: "10px" }}
            src={sessionRequestSvg}
            alt="sessionRequest"
          />
          <div>
            <FormattedMessage
              id="drawingSessionRequestedMultiple"
              values={{
                username: <strong>{tightenName(requests[0].username)}</strong>,
                amount: <strong>{requests.length - 1}</strong>
              }}
            />
          </div>
        </div>
        <div className={classes.cardBody}>
          <Collapse in={expanded} timeout="auto" unmountOnExit>
            {requests.map(request => (
              <div className={classes.cardBody}>
                <button
                  className={classes.btnName}
                  type="button"
                  onClick={() => handleAccept(request)}
                  disabled={saving}
                >
                  {tightenName(request.username)}
                </button>
              </div>
            ))}
            <div className={classes.cardBody}>
              <button
                className={classes.btnBack}
                type="button"
                onClick={handleExpandClick}
                disabled={saving}
              >
                {saving ? (
                  <CircularProgress
                    size={11}
                    sx={{
                      color: "#FFFFFF",
                      position: "relative",
                      marginRight: "5px"
                    }}
                  />
                ) : null}
                <FormattedMessage id={saving ? "savingInProgress" : "back"} />
              </button>
            </div>
          </Collapse>
        </div>
        <Collapse in={!expanded} timeout="auto" unmountOnExit>
          <div className={classes.cardBody}>
            <button
              className={classes.btnNo}
              onClick={handleDismiss}
              type="button"
              disabled={saving}
              aria-label="No"
            >
              <FormattedMessage id="No" />
            </button>
            <button
              className={classes.btnYes}
              onClick={handleExpandClick}
              type="button"
              disabled={saving}
              aria-label="Yes"
            >
              <FormattedMessage id="select" />
            </button>
          </div>
        </Collapse>
      </div>
    );
  }

  return (
    <SnackbarContent ref={ref} className={classes.root}>
      {content}
    </SnackbarContent>
  );
});

export default SessionRequested;

SessionRequested.propTypes = {
  id: PropTypes.number.isRequired,
  requests: PropTypes.arrayOf(
    PropTypes.shape({
      username: PropTypes.string,
      xSessionId: PropTypes.string,
      requestXSessionId: PropTypes.string
    })
  ).isRequired,
  accept: PropTypes.func.isRequired,
  deny: PropTypes.func.isRequired
};
