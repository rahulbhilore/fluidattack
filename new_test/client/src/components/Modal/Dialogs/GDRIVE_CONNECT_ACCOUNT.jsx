import React from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import Button from "@material-ui/core/Button";
import makeStyles from "@material-ui/core/styles/makeStyles";
import UserInfoActions from "../../../actions/UserInfoActions";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";

const useStyles = makeStyles(theme => ({
  rightSideButton: {
    float: "right !important",
    marginLeft: theme.spacing(1)
  },
  cancelButton: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE,
    height: "36px",
    textTransform: "uppercase",
    padding: "10px 30px"
  }
}));

export default function gdriveConnectAccount() {
  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <Typography variant="body1">
          <FormattedMessage id="connectGDriveToOpenFile" />
        </Typography>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Button
          className={classes.cancelButton}
          onClick={() => {
            ModalActions.hide();
          }}
          data-component="modal-cancel-button"
        >
          <FormattedMessage id="cancel" />
        </Button>
        <KudoButton
          isDisabled={false}
          className={classes.rightSideButton}
          onClick={() => {
            UserInfoActions.connectStorage(
              "gdrive",
              location.pathname + location.search
            );
          }}
        >
          <FormattedMessage id="connect" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
