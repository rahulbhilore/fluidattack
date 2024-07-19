import React from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Button from "@material-ui/core/Button";
import Typography from "@material-ui/core/Typography";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import ModalActions from "../../../actions/ModalActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import KudoButton from "../../Inputs/KudoButton/KudoButton";

const useStyles = makeStyles(theme => ({
  text: {
    color: theme.palette.DARK
  },
  button: {
    height: "36px",
    padding: "10px 30px",
    textTransform: "uppercase"
  },
  cancel: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE
  },
  margined: {
    marginRight: `${theme.spacing(2)}px !important`
  }
}));

export default function ConfirmWaitOrReload({ info }) {
  const executeAction = React.useCallback(
    action => {
      if (info[action] && typeof info[action] === "function") {
        info[action]();
      }
      ModalActions.hide();
    },
    [info]
  );

  const close = React.useCallback(() => executeAction("onClose"), []);
  const viewOnly = React.useCallback(() => executeAction("onViewOnly"), []);
  const wait = React.useCallback(() => executeAction("onAction"), []);

  const { allowForce } = info;
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <Typography className={classes.text}>
          <FormattedMessage
            id={
              allowForce ? "cannotGetEditingSession" : "takingLongerThanUsual"
            }
          />
        </Typography>
      </DialogBody>
      <DialogFooter showCancel={false}>
        {allowForce ? (
          <>
            <KudoButton onClick={viewOnly} isDisabled={false}>
              <FormattedMessage id="openViewOnly" />
            </KudoButton>
            <KudoButton
              className={classes.margined}
              onClick={wait}
              isDisabled={false}
            >
              <FormattedMessage id="forceSwitchToEditMode" />
            </KudoButton>
          </>
        ) : (
          <>
            <Button
              onClick={close}
              className={clsx(classes.button, classes.cancel)}
            >
              <FormattedMessage id="close" />
            </Button>
            <span>
              <KudoButton
                className={classes.margined}
                onClick={wait}
                isDisabled={false}
              >
                <FormattedMessage id="wait" />
              </KudoButton>
              <KudoButton onClick={viewOnly} isDisabled={false}>
                <FormattedMessage id="openViewOnly" />
              </KudoButton>
            </span>
          </>
        )}
      </DialogFooter>
    </>
  );
}

ConfirmWaitOrReload.propTypes = {
  info: PropTypes.shape({
    onViewOnly: PropTypes.func,
    onAction: PropTypes.func,
    onClose: PropTypes.func,
    allowForce: PropTypes.bool
  }).isRequired
};
