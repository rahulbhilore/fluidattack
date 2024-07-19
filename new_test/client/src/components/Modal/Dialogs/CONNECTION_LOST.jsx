/**
 * Created by khizh on 8/25/2016.
 */
import React, { useEffect } from "react";
import { FormattedMessage } from "react-intl";
import Typography from "@material-ui/core/Typography";
import Button from "@material-ui/core/Button";
import makeStyles from "@material-ui/core/styles/makeStyles";
import Storage from "../../../utils/Storage";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import ApplicationStore from "../../../stores/ApplicationStore";
import FilesListStore from "../../../stores/FilesListStore";
import DialogFooter from "../DialogFooter";
import DialogBody from "../DialogBody";
import ModalActions from "../../../actions/ModalActions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import ApplicationActions from "../../../actions/ApplicationActions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

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

export default function connectionLost() {
  useEffect(() => {
    if (!FilesListStore.getCurrentFile().viewFlag) {
      const headers = {
        xSessionId: FilesListStore.getCurrentFile().drawingSessionId,
        sessionId: Storage.store("sessionId")
      };
      const fileId = FilesListStore.getCurrentFile()._id;
      Requests.sendGenericRequest(
        `/files/${encodeURIComponent(fileId)}/session`,
        RequestsMethods.DELETE,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          if (response.data.status !== "ok") {
            SnackbarUtils.alertError({ id: "errorDeletingXSession" });
          }
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text);
        });
    }
    return () => {
      const UIPrefix = ApplicationStore.getApplicationSetting("UIPrefix");
      ApplicationActions.changePage(`${UIPrefix}files/-1`);
    };
  }, []);

  const returnToFiles = () => {
    ApplicationActions.changePage("/files");
    ModalActions.hide();
  };
  const reload = () => {
    location.reload();
  };

  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <Typography className={classes.warningText}>
          <FormattedMessage
            id="AKisNotConnected"
            values={{
              product: ApplicationStore.getApplicationSetting("product"),
              type: (
                <FormattedMessage
                  id={
                    FilesListStore.getCurrentFile().viewFlag
                      ? "viewer"
                      : "editor"
                  }
                />
              )
            }}
          />
          {FilesListStore.getCurrentFile().viewFlag ? null : (
            <>
              <br />
              <FormattedMessage id="yourDocumentIsSaved" />
            </>
          )}
          <br />
          <FormattedMessage id="tryToOpenLater" />
        </Typography>
      </DialogBody>
      <DialogFooter showCancel={false}>
        <Button onClick={returnToFiles} className={classes.cancelButton}>
          <FormattedMessage id="returnToFilesPage" />
        </Button>
        <KudoButton isDisabled={false} onClick={reload}>
          <FormattedMessage id="continueWorking" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
