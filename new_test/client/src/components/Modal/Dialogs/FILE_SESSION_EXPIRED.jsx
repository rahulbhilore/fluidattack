import React, { useEffect } from "react";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Button from "@material-ui/core/Button";
import Typography from "@material-ui/core/Typography";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogFooter from "../DialogFooter";
import ApplicationActions from "../../../actions/ApplicationActions";
import DialogBody from "../DialogBody";

const useStyles = makeStyles(theme => ({
  cancelButton: {
    backgroundColor: theme.palette.GREY_BACKGROUND,
    color: theme.palette.CLONE,
    height: "36px",
    textTransform: "uppercase",
    padding: "10px 30px"
  },
  warningText: {
    fontSize: ".9rem",
    textAlign: "center"
  }
}));

export default function DrawingSessionExpired() {
  const handleSubmit = () => {
    FilesListStore.saveDrawingSessionId("");
    FilesListActions.reloadDrawing();
    ModalActions.hide();
  };

  const returnToFiles = () => {
    ApplicationActions.changePage("/files");
    ModalActions.hide();
  };

  useEffect(() => returnToFiles, []);

  const classes = useStyles();

  return (
    <div>
      <DialogBody className="text-center">
        <Typography className={classes.warningText}>
          <FormattedMessage id="drawingSessionExpired" />
          <br />
          <FormattedMessage id="doYouWantToReload" />
        </Typography>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Button onClick={returnToFiles} className={classes.cancelButton}>
          <FormattedMessage id="returnToFilesPage" />
        </Button>
        <KudoButton isDisabled={false} onClick={handleSubmit}>
          <FormattedMessage id="Yes" />
        </KudoButton>
      </DialogFooter>
    </div>
  );
}
