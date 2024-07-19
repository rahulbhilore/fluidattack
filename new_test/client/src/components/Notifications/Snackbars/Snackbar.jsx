import React, { forwardRef, useCallback } from "react";
import { makeStyles } from "@material-ui/core/styles";
import { SnackbarContent, useSnackbar } from "notistack";
import { FormattedMessage } from "react-intl";
import PropTypes from "prop-types";

import infoSvg from "../../../assets/images/snacks/info.svg";
import failSvg from "../../../assets/images/snacks/fail.svg";
import successSvg from "../../../assets/images/snacks/success.svg";
import warningSvg from "../../../assets/images/snacks/warning.svg";

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
  btnOk: {
    padding: "8px 20px",
    textDecoration: "none",
    width: "100%",
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
  }
}));

const Snackbar = forwardRef((props, ref) => {
  const classes = useStyles();

  const { id, type, message, values } = props;

  const { closeSnackbar } = useSnackbar();
  const handleOk = useCallback(() => {
    closeSnackbar(id);
  }, [closeSnackbar]);

  let svg;
  switch (type) {
    case "info":
      svg = infoSvg;
      break;
    case "success":
    case "ok":
      svg = successSvg;
      break;
    case "error":
      svg = failSvg;
      break;
    case "warning":
      svg = warningSvg;
      break;
    default:
      svg = infoSvg;
  }

  return (
    <SnackbarContent ref={ref} className={classes.root}>
      <div className={classes.card} data-component="snackbar" data-type={type}>
        <div className={classes.cardHeader}>
          <img
            style={{ width: "40px", marginRight: "10px" }}
            src={svg}
            alt="svgIcon"
          />
          <FormattedMessage id={message} values={values} />
        </div>
        <div className={classes.cardBody}>
          <button
            type="button"
            className={classes.btnOk}
            onClick={handleOk}
            aria-label="Ok"
          >
            <FormattedMessage id="GotIt" />
          </button>
        </div>
      </div>
    </SnackbarContent>
  );
});

export default Snackbar;

Snackbar.propTypes = {
  id: PropTypes.number.isRequired,
  message: PropTypes.string.isRequired,
  type: PropTypes.string.isRequired,
  values: PropTypes.objectOf(PropTypes.oneOfType([PropTypes.string]))
};

Snackbar.defaultProps = {
  values: {}
};
