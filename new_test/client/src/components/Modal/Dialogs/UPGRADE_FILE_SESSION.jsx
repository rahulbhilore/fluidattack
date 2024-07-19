import React, { useEffect, useState } from "react";
import _ from "underscore";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import { Typography } from "@material-ui/core";
import { CircularProgress } from "@mui/material";
import makeStyles from "@material-ui/core/styles/makeStyles";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import Requests from "../../../utils/Requests";
import ModalActions from "../../../actions/ModalActions";
import FilesListActions from "../../../actions/FilesListActions";
import FilesListStore from "../../../stores/FilesListStore";
import UserInfoStore from "../../../stores/UserInfoStore";
import Storage from "../../../utils/Storage";
import ApplicationActions from "../../../actions/ApplicationActions";
import MainFunctions from "../../../libraries/MainFunctions";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import XenonConnectionActions from "../../../actions/XenonConnectionActions";

const useStyles = makeStyles(() => ({
  text: {
    textAlign: "center"
  }
}));

export default function UpgradeFileSession({ info }) {
  const [foundSessionDetails, setFoundSession] = useState({
    isMe: false,
    name: "",
    sessionToBeDowngraded: "",
    isFound: false,
    deviceType: ""
  });

  const [gainingOwnSession, setGettingOwnSession] = useState(false);
  const [retakeDisabled, setRetakeDisabled] = useState(false);
  const [offerForceRetake, setOfferForceRetake] = useState(false);
  const [updating, setUpdating] = useState(false);

  useEffect(() => {
    Requests.sendGenericRequest(
      `/files/${info.fileId}/session`,
      RequestsMethods.GET,
      Requests.getDefaultUserHeaders(),
      undefined,
      ["*"]
    )
      .then(response => {
        if (response.data.results && response.data.results instanceof Array) {
          const editors = _.where(response.data.results, { mode: "edit" });
          if (editors !== undefined && editors instanceof Array) {
            let editorIsMe = null;
            let editorName = "";
            let sessionToBeDowngraded = "";
            let deviceType = "";
            if (editors.length > 0) {
              const editor = editors[0];
              if (editor.userId === UserInfoStore.getUserInfo("id")) {
                editorIsMe = true;
                editorName = "You";
              } else {
                editorIsMe = false;
                editorName = editor.username;
              }
              sessionToBeDowngraded = editor.sk;
              deviceType = editor.device;

              setFoundSession({
                isMe: editorIsMe,
                name: editorName,
                sessionToBeDowngraded,
                isFound: true,
                deviceType
              });
              setRetakeDisabled(
                (deviceType === "TOUCH" || deviceType === "COMMANDER") &&
                  !editorIsMe
              );
            } else {
              setFoundSession({
                ...foundSessionDetails,
                sessionToBeDowngraded: null,
                isFound: false
              });
            }
          } else if (MainFunctions.QueryString("token").length > 0) {
            setFoundSession({
              ...foundSessionDetails,
              sessionToBeDowngraded: null,
              isFound: false
            });
          } else {
            SnackbarUtils.error("errorGettingSessionDetails");
          }
        } else {
          SnackbarUtils.error("errorGettingSessionDetails");
        }
      })
      .catch(err => {
        SnackbarUtils.error(err.text);
      });
  }, []);

  const upgradeViewOnlySession = () => {
    if (MainFunctions.QueryString("token").length > 0) {
      ApplicationActions.changePage(`/file/${info.fileId}`);
    }
    FilesListActions.reloadDrawing(false);
  };

  const downgradeEditingSession = (upgradeViewOnlySessionFlag = false) => {
    if (foundSessionDetails.sessionToBeDowngraded) {
      const headers = {
        xSessionId: foundSessionDetails.sessionToBeDowngraded,
        sessionId: Storage.store("sessionId"),
        downgrade: true
      };
      Requests.sendGenericRequest(
        `/files/${info.fileId}/session`,
        RequestsMethods.PUT,
        headers,
        undefined,
        ["*"]
      )
        .then(response => {
          if (response.data.checkinFailed === true) {
            SnackbarUtils.info("checkInFailed");
            return;
          }
          const downgradeSessionInfo = {
            xSessionId: foundSessionDetails.sessionToBeDowngraded,
            downgradeReq: true,
            updateTime: +new Date()
          };
          if (foundSessionDetails.deviceType !== "COMMANDER") {
            FilesListActions.saveDowngradeSessionInfo(downgradeSessionInfo);
          }

          if (upgradeViewOnlySessionFlag) upgradeViewOnlySession();
        })
        .catch(err => {
          SnackbarUtils.error(err.text);
        });
    }
    ModalActions.hide();
  };

  const requestEditPermission = (mySession = false) => {
    if (mySession) {
      // if nothing will happen in 10 seconds
      // offer force retake session
      setTimeout(() => {
        setOfferForceRetake(true);
      }, 10000);
      setGettingOwnSession(true);
    }
    const headers = {
      xSessionId: FilesListStore.getCurrentFile().drawingSessionId,
      isMySession: mySession,
      sessionId: Storage.store("sessionId")
    };
    Requests.sendGenericRequest(
      `/files/${info.fileId}/session/request`,
      RequestsMethods.POST,
      headers,
      undefined,
      ["*"]
    )
      .then(response => {
        if (response.data.canUpdateNow && response.data.canUpdateNow === true) {
          upgradeViewOnlySession();
        } else if (!mySession) {
          FilesListStore.setLastEditRequest(response.data.ttl);
          SnackbarUtils.info("waitingForEditorAccept");
        }
      })
      .catch(err => {
        if (mySession) {
          setOfferForceRetake(true);
        }
        if (err.data && err.data.timeToWait) {
          const minutes = Math.trunc(err.data.timeToWait / 60);
          const seconds = err.data.timeToWait - minutes * 60;

          const time = `${minutes > 0 ? `${minutes} min ` : ""}${seconds} sec`;

          SnackbarUtils.error("youHaveAlreadyRequestedEdit", { time });
        } else {
          SnackbarUtils.error(err.text ? err.text : "internalError");
        }
      });
  };

  const handleSubmit = () => {
    const submit = () => {
      // already in edit mode
      if (!FilesListStore.getCurrentFile().viewFlag) {
        ModalActions.hide();
        return;
      }

      if (offerForceRetake) {
        // force downgrade should not be doing this
        downgradeEditingSession(true);
        // close the dialog
        ModalActions.hide();
      }

      // if the other session is mine - downgrade it and upgrade current
      if (foundSessionDetails.isMe) {
        // temp fix to allow retake own sessions from commander and touch
        if (
          foundSessionDetails.deviceType === "TOUCH" ||
          foundSessionDetails.deviceType === "COMMANDER"
        ) {
          // force downgrade should not be doing this
          downgradeEditingSession(true);
          // close the dialog
          ModalActions.hide();
        } else {
          requestEditPermission(true);
        }
      }

      // if  there wasn't other session - just upgrade
      else if (foundSessionDetails.sessionToBeDowngraded === null) {
        upgradeViewOnlySession();

        // close the dialog
        ModalActions.hide();
      }

      // request for edit session
      else {
        requestEditPermission();

        // close the dialog
        ModalActions.hide();
      }
    };

    if (FilesListStore.getCurrentFile().viewingLatestVersion === false) {
      setUpdating(true);
      XenonConnectionActions.postMessage({ messageName: "reopen" });
      setTimeout(() => {
        setUpdating(false);
        submit();
      }, 3000);
    } else {
      submit();
    }
  };

  const checkIfUpdateNeededMessage = () => {
    if (FilesListStore.getCurrentFile().viewingLatestVersion === false) {
      return (
        <>
          <br />
          <FormattedMessage
            id="needToUpdateBeforeTakingEdit"
            values={{
              version: (
                <strong>
                  <FormattedMessage id="latestVersion" />
                </strong>
              )
            }}
          />
        </>
      );
    }
    return null;
  };

  const getContextualMessage = () => {
    const fileName = FilesListStore.getCurrentFile().name;
    if (FilesListStore.getCurrentFile().viewFlag) {
      // offering force retake session
      if (offerForceRetake) {
        return <FormattedMessage id="someErrorOccurredWhileRetaking" />;
      }

      // fetching data
      if (
        foundSessionDetails.isMe === false &&
        foundSessionDetails.sessionToBeDowngraded === ""
      ) {
        return (
          <FormattedMessage
            id="fetchingSessionDetails"
            values={{
              name: <strong>{fileName}</strong>
            }}
          />
        );
      }

      // no active session found
      if (!foundSessionDetails.sessionToBeDowngraded) {
        return <FormattedMessage id="noActiveEditSessionFound" />;
      }

      // user is hosting edit session in different tab or app
      if (foundSessionDetails.isMe) {
        return (
          <FormattedMessage
            id="drawingHasRunningEditSession"
            values={{
              name: <strong>{fileName}</strong>,
              editors: <strong>{foundSessionDetails.name}</strong>,
              canSessionBeKilled: <FormattedMessage id="killTheSession" />
            }}
          />
        );
      }

      // someone else is hosting an edit session
      return (
        <FormattedMessage
          id="drawingHasRunningEditSession"
          values={{
            name: <strong>{fileName}</strong>,
            editors: <strong>{foundSessionDetails.name}</strong>,
            canSessionBeKilled: <FormattedMessage id="requestEditMode" />
          }}
        />
      );
    }
    return (
      <FormattedMessage
        id="fileAlreadyInEditMode"
        values={{
          name: <strong>{fileName}</strong>
        }}
      />
    );
  };

  const getButtonText = () => {
    // already in edit mode
    if (!FilesListStore.getCurrentFile().viewFlag) {
      return <FormattedMessage id="ok" />;
    }

    // updating session before reloading
    if (updating) {
      return (
        <>
          <FormattedMessage id="updating" />
          <CircularProgress
            size={36}
            sx={{
              position: "absolute",
              top: "50%",
              left: "50%",
              marginTop: "-18px",
              marginLeft: "-18px"
            }}
          />
        </>
      );
    }
    // offering force retake session
    if (offerForceRetake) {
      return <FormattedMessage id="forceSwitchToEditMode" />;
    }
    if (gainingOwnSession) {
      return (
        <>
          <FormattedMessage id="reloadInProgress" />
          <CircularProgress
            size={36}
            sx={{
              position: "absolute",
              top: "50%",
              left: "50%",
              marginTop: "-18px",
              marginLeft: "-18px"
            }}
          />
        </>
      );
    }
    if (
      !foundSessionDetails.isMe &&
      foundSessionDetails.sessionToBeDowngraded === ""
    )
      return <FormattedMessage id="close" />;
    if (foundSessionDetails.isMe)
      return <FormattedMessage id="switchToEditMode" />;
    if (foundSessionDetails.sessionToBeDowngraded === null)
      return <FormattedMessage id="reload" />;
    return <FormattedMessage id="request" />;
  };

  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <Typography className={classes.text}>
          {getContextualMessage()}
          {checkIfUpdateNeededMessage()}
        </Typography>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          dataComponent="upgradeSessionActionButton"
          isDisabled={retakeDisabled || gainingOwnSession}
          onClick={handleSubmit}
        >
          {getButtonText()}
        </KudoButton>
      </DialogFooter>
    </>
  );
}

UpgradeFileSession.propTypes = {
  info: PropTypes.shape({
    fileId: PropTypes.string.isRequired
  }).isRequired
};
