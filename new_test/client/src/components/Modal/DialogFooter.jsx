import React from "react";
import PropTypes from "prop-types";
import DialogActions from "@material-ui/core/DialogActions";
import Button from "@material-ui/core/Button";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { FormattedMessage } from "react-intl";
import ModalActions from "../../actions/ModalActions";

const useStyles = makeStyles(theme => ({
  root: {
    borderTop: `solid 1px ${theme.palette.JANGO}`,
    padding: theme.spacing(2),
    justifyContent: "space-between"
  },
  cancelButton: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE,
    height: "36px",
    textTransform: "uppercase",
    float: "left",
    padding: "10px 30px",
    fontSize: theme.typography.pxToRem(12)
  }
}));

export default function DialogFooter({ children, showCancel }) {
  const classes = useStyles();
  return (
    <DialogActions className={classes.root}>
      {showCancel ? (
        <Button
          onClick={ModalActions.hide}
          className={classes.cancelButton}
          data-component="modal-cancel-button"
        >
          <FormattedMessage id="cancel" />
        </Button>
      ) : null}
      {children}
    </DialogActions>
  );
}

DialogFooter.propTypes = {
  children: PropTypes.node,
  showCancel: PropTypes.bool
};

DialogFooter.defaultProps = {
  children: null,
  showCancel: true
};
