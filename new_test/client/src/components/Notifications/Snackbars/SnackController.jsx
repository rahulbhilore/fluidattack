import { useSnackbar } from "notistack";
import React from "react";
import PropTypes from "prop-types";

import Snackbar from "./Snackbar";
import EditAvailable from "./EditAvailable";
import UndoAction from "./UndoAction";
import SessionRequested from "./SessionRequested";
import CookieAccept from "./CookieAccept";
import OfflineSnackbar from "./OfflineSnackbar";
import AlertSnackbar from "./AlertSnackbar";
import TestsInProgressSnack from "./TestsSnacks/TestsInProgressSnack";

import Storage from "../../../utils/Storage";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import Requests from "../../../utils/Requests";
import NewVersionSnack from "./QuickTour/NewVersionSnack";

// To set up snack's Controller, this part should be
// used as a ref inside SnackProvider as a null-child
function InnerSnackbarUtilsConfigurator(props) {
  const { setUseSnackbarRef } = props;
  setUseSnackbarRef(useSnackbar());
  return null;
}
InnerSnackbarUtilsConfigurator.propTypes = {
  setUseSnackbarRef: PropTypes.func.isRequired
};
let useSnackbarRef;
const setUseSnackbarRef = useSnackbarRefProp => {
  useSnackbarRef = useSnackbarRefProp;
};
export function SnackbarUtilsConfigurator() {
  return (
    <InnerSnackbarUtilsConfigurator setUseSnackbarRef={setUseSnackbarRef} />
  );
}

/**
 * Variable stores all pending requests for edit,
 * can be accessed with methods provided below.
 * This part represents some additional
 * functions to use inside SnackBar components
 * by passing them in props
 */
let requests = [];
let editRequestedSnackId = null;
let testInProgressSnackId = null;
let alertSnackId = null;

/**
 * Function denies specific request
 * and changes mode to edit if so
 * @type function
 * @param request is an object, contains
 * {xSession, username, requestXSession, ttl}
 * @param denyAll - if true - all requests will be denied at once
 */
function denyRequest(request, denyAll = false) {
  const headers = {
    xSessionId: request.xSessionId,
    requestXSessionId: denyAll ? "*" : request.requestXSessionId,
    sessionId: Storage.store("sessionId")
  };
  Requests.sendGenericRequest(
    `/files/${encodeURIComponent(request.fileId)}/session/request`,
    RequestsMethods.DELETE,
    headers,
    undefined,
    ["*"]
  ).then(() => {
    // this.ok("success")
  });
}

/**
 * Function denies all pending requests
 * and changes mode to edit if so
 * @type function
 */
function denyRequests() {
  if (requests.length) {
    denyRequest(requests[0], true);
  }

  requests = [];
  editRequestedSnackId = null;
}

/**
 * Function accepts request and then denies all others
 * and changes mode to edit if so
 * @type function
 * @param request is an object, contains
 * {xSession, username, requestXSession, ttl}
 */
function acceptRequest(request) {
  return new Promise((resolve, reject) => {
    // all requests will be denied by server from 23.06.2023
    requests = [];
    editRequestedSnackId = null;

    // downgrade this session for
    // applicant specified in headers
    const headers = {
      xSessionId: request.xSessionId,
      sessionId: Storage.store("sessionId"),
      downgrade: true,
      applicantXSession: request.requestXSessionId
    };
    Requests.sendGenericRequest(
      `/files/${encodeURIComponent(request.fileId)}/session`,
      RequestsMethods.PUT,
      headers,
      undefined,
      ["*"]
    )
      .then(() => {
        resolve();
      })
      .catch(err => {
        reject(err);
      });
  });
}

/**
 * Exported methods to open different notifications
 * to use:
 * 1. import SnackbarUtils from .~./SnackController
 * 2. SnackbarUtils.cookieAccept();
 * @type exports
 * @public
 */
export default class SnackbarUtils {
  /**
   * Function opens an OFFLINE snackbar
   * will execute when connection is lost
   * automatically gone after re-connect
   * @type function
   * @public
   */
  static offline() {
    useSnackbarRef.enqueueSnackbar("offline", {
      preventDuplicate: true,
      persist: true,
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => <OfflineSnackbar id={key} />
    });
  }

  /**
   * Function opens an COOKIE ACCEPT snackbar
   * will execute only if wasn't accepted before
   * @type function
   * @public
   */
  static cookieAccept() {
    useSnackbarRef.enqueueSnackbar("cookieAccept", {
      preventDuplicate: true,
      persist: true,
      content: key => <CookieAccept id={key} />
    });
  }

  /**
   * Function opens an NEW VERSION snackbar
   * will execute only if wasn't accepted before
   * @type function
   * @public
   */
  static newKudoVersion(revision) {
    useSnackbarRef.enqueueSnackbar("newVersion", {
      preventDuplicate: true,
      persist: true,
      content: key => <NewVersionSnack id={key} revision={revision} />
    });
  }

  /**
   * Function opens an INFO snackbar
   * @type function
   * @param msgOrId
   * @param inputs is an object containing values to
   * put inside generated string by provided keys
   * @public
   */
  static info(msgOrId, inputs = {}) {
    useSnackbarRef.enqueueSnackbar(msgOrId, {
      autoHideDuration: 7000,
      content: (key, message) => (
        <Snackbar id={key} message={message} type="info" values={inputs} />
      )
    });
  }

  /**
   * Function opens WARNING snackbar
   * @type function
   * @param msgOrId
   * @param inputs is an object containing values to
   * put inside generated string by provided keys
   * @public
   */
  static warning(msgOrId, inputs = {}) {
    useSnackbarRef.enqueueSnackbar(msgOrId, {
      autoHideDuration: 7000,
      content: (key, message) => (
        <Snackbar id={key} message={message} type="warning" values={inputs} />
      )
    });
  }

  /**
   * Function opens OK snackbar
   * @type function
   * @param msgOrId
   * @param inputs is an object containing values to
   * put inside generated string by provided keys
   * @public
   */
  static ok(msgOrId, inputs = {}) {
    useSnackbarRef.enqueueSnackbar(msgOrId, {
      autoHideDuration: 7000,
      content: (key, message) => (
        <Snackbar id={key} message={message} type="success" values={inputs} />
      )
    });
  }

  /**
   * Function opens ERROR snackbar
   * @type function
   * @param msgOrId
   * @param inputs is an object containing values to
   * put inside generated string by provided keys
   * @public
   */
  static error(msgOrId, inputs = {}) {
    useSnackbarRef.enqueueSnackbar(msgOrId, {
      autoHideDuration: 7000,
      content: (key, message) => (
        <Snackbar id={key} message={message} type="error" values={inputs} />
      )
    });
  }

  /**
   * Function opens an EDIT AVAILABLE snackbar
   * to notify that user can update to edit mode
   * @param stringId id of translation
   * @param isLatestVersion is file updated to the latest version in xenon
   * @type function
   * @public
   */
  static editAvailable(stringId, isLatestVersion) {
    useSnackbarRef.enqueueSnackbar("editSessionAvailable", {
      preventDuplicate: true,
      autoHideDuration: 30000,
      content: key => (
        <EditAvailable
          id={key}
          message={stringId}
          isLatestVersion={isLatestVersion}
        />
      )
    });
  }

  /**
   * Function closes an SESSION REQUESTED snackbar
   * @type function
   * @public
   */
  static closeSessionRequest() {
    if (editRequestedSnackId !== null) {
      useSnackbarRef.closeSnackbar(editRequestedSnackId);
      denyRequests();
    }
  }

  /**
   * Function opens an SESSION REQUESTED snackbar
   * to notify editor about viewers who want to get
   * edit permission. New requests will be added to array
   * and will be auto-dismissed with provided ttl time
   * @param fileId full fileId
   * @param username requested username
   * @param xSessionId editor's session id
   * @param requestXSessionId requested session id
   * @param ttl [number] request's ttl
   * @type function
   * @public
   */
  static sessionRequest(fileId, username, xSessionId, requestXSessionId, ttl) {
    // push request to requests
    const request = { fileId, username, xSessionId, requestXSessionId, ttl };
    requests.push(request);

    const accept = req => {
      acceptRequest(req).catch(() => {
        this.error("applicantDrawingSessionExpired", {
          username: req.username
        });
      });
    };

    const enqueueSnack = () => {
      useSnackbarRef.enqueueSnackbar("editSessionRequested", {
        preventDuplicate: true,
        persist: true,
        content: (key, message) => {
          editRequestedSnackId = key;
          return (
            <SessionRequested
              id={key}
              message={message}
              requests={requests}
              accept={accept}
              deny={denyRequests}
            />
          );
        }
      });
    };

    // auto dismiss snack in case of reaching ttl with delta of 3 seconds
    const timeout = parseInt(ttl, 10) * 1000 - new Date().getTime() - 3000;
    setTimeout(() => {
      const deleteIndex = requests.findIndex(
        foundReq => foundReq.username === username && foundReq.ttl === ttl
      );

      if (deleteIndex > -1) {
        requests.splice(deleteIndex, 1);
        denyRequest(request);

        // update snack
        enqueueSnack();
      }
    }, timeout);

    // show or refresh snack
    enqueueSnack();
  }

  /**
   * Function opens an ERROR alert
   * @type function
   * @param data is an object containing values
   * @param sticky sticks alert to screen
   * @public
   */
  static alertError(data, sticky = false) {
    if (alertSnackId) {
      useSnackbarRef.closeSnackbar(alertSnackId);
    }

    const options = {
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        alertSnackId = key;
        return <AlertSnackbar id={key} message={data} type="error" />;
      }
    };

    if (sticky === true) {
      options.persist = true;
    } else {
      options.autoHideDuration = 7000;
    }

    useSnackbarRef.enqueueSnackbar("alertError", options);
  }

  /**
   * Function opens an WARNING alert
   * @type function
   * @param data is an object containing values
   * @param sticky sticks alert to screen
   * @public
   */
  static alertWarning(data, sticky = false) {
    if (alertSnackId) {
      useSnackbarRef.closeSnackbar(alertSnackId);
    }

    const options = {
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        alertSnackId = key;
        return <AlertSnackbar id={key} message={data} type="warning" />;
      }
    };

    if (sticky === true) {
      options.persist = true;
    } else {
      options.autoHideDuration = 7000;
    }

    useSnackbarRef.enqueueSnackbar("alertWarning", options);
  }

  /**
   * Function opens an INFO alert
   * @type function
   * @param data is an object containing values
   * @param sticky sticks alert to screen
   * @public
   */
  static alertInfo(data, sticky = false) {
    if (alertSnackId) {
      useSnackbarRef.closeSnackbar(alertSnackId);
    }

    const options = {
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        alertSnackId = key;
        return <AlertSnackbar id={key} message={data} type="info" />;
      }
    };

    if (sticky === true) {
      options.persist = true;
    } else {
      options.autoHideDuration = 7000;
    }

    useSnackbarRef.enqueueSnackbar("alertInfo", options);
  }

  /**
   * Function opens an OK alert
   * @type function
   * @param data is an object containing values
   * @param sticky sticks alert to screen
   * @public
   */
  static alertOk(data, sticky = false) {
    if (alertSnackId) {
      useSnackbarRef.closeSnackbar(alertSnackId);
    }
    const options = {
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        alertSnackId = key;
        return <AlertSnackbar id={key} message={data} type="success" />;
      }
    };

    if (sticky === true) {
      options.persist = true;
    } else {
      options.autoHideDuration = 7000;
    }

    useSnackbarRef.enqueueSnackbar("alertOk", options);
  }

  /**
   * Function opens an EDIT AVAILABLE snackbar
   * to notify that user can update to edit mode
   * @param stringId id of translation
   * @param isLatestVersion is file updated to the latest version in xenon
   * @type function
   * @public
   */
  static undoLastAction(undoMessage, undoFunction) {
    const autoHideTime = 5000;
    if (alertSnackId) {
      useSnackbarRef.closeSnackbar(alertSnackId);
    }
    const options = {
      // this snackbar would be closed manually by the progress and timer effect in UndoAction.tsx
      autoHideDuration: null,
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        alertSnackId = key;
        return (
          <UndoAction
            id={key}
            undoMessage={undoMessage}
            undoFunction={undoFunction}
            autoHideTime={autoHideTime / 1000}
          />
        );
      }
    };
    useSnackbarRef.enqueueSnackbar("undoLastAction", options);
  }

  /**
   * Function opens TESTS IN PROGRESS alert
   * @type function
   * @public
   */
  static testsInProgress() {
    useSnackbarRef.enqueueSnackbar("alertOk", {
      persist: true,
      preventDuplicate: true,
      anchorOrigin: {
        vertical: "top",
        horizontal: "center"
      },
      content: key => {
        testInProgressSnackId = key;
        return <TestsInProgressSnack id={key} />;
      }
    });
  }

  /**
   * Function closes TESTS IN PROGRESS alert
   * @type function
   * @public
   */
  static stopTestsInProgress() {
    if (testInProgressSnackId !== null) {
      useSnackbarRef.closeSnackbar(testInProgressSnackId);
      testInProgressSnackId = null;
    }
  }

  /**
   * Function closes STICKY alert
   * @type function
   * @public
   */
  static closeStickyAlert() {
    if (alertSnackId !== null) {
      useSnackbarRef.closeSnackbar(alertSnackId);
      alertSnackId = null;
    }
  }

  /**
   * Function closes STICKY alert
   * @type function
   * @return boolean
   * @public
   */
  static isStickyAlertOpen() {
    return alertSnackId !== null;
  }
}
