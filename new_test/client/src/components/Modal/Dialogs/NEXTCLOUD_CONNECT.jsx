import React, { useEffect, useReducer, useRef } from "react";
import { FormattedMessage } from "react-intl";
import { makeStyles } from "@material-ui/core";
import { CircularProgress } from "@mui/material";
import Collapse from "@mui/material/Collapse";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import KudoInput from "../../Inputs/KudoInput/KudoInput";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import * as InputTypes from "../../../constants/appConstants/InputTypes";
import * as InputValidationFunctions from "../../../constants/validationSchemas/InputValidationFunctions";
import UserInfoActions from "../../../actions/UserInfoActions";
import DialogBody from "../DialogBody";
import DialogFooter from "../DialogFooter";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import Loader from "../../Loader";
import WebsocketStore, {
  STORAGE_ADD_ERROR
} from "../../../stores/WebsocketStore";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";

const useStyles = makeStyles(theme => ({
  formGroup: {
    marginBottom: `${theme.spacing(2)}px !important`
  },
  label: {
    color: `${theme.palette.OBI} !important`,
    marginBottom: theme.spacing(1)
  },
  message: {
    marginTop: theme.spacing(2),
    display: "block",
    color: theme.palette.DARK,
    textAlign: "center"
  }
}));

const INITIAL_STATE = {
  validatingUrl: false,
  isRedirected: false,
  isWaitingLogin: false,
  timeLeft: 4,
  isCounting: false
};

const VALIDATING_URL = "VALIDATING_URL";
const URL_VALIDATED = "URL_VALIDATED";
const REDIRECT = "REDIRECT";
const STORAGE_CONNECT_FAIT = "STORAGE_CONNECT_FAIT";
const REDUCE_SECOND = "REDUCE_SECOND";

export default function webdavConnect() {
  const [state, dispatch] = useReducer((reducerState, action) => {
    switch (action.type) {
      case VALIDATING_URL:
        return {
          ...reducerState,
          validatingUrl: true
        };
      case URL_VALIDATED:
        return {
          ...reducerState,
          isWaitingLogin: true,
          isCounting: true
        };
      case REDIRECT:
        return {
          ...reducerState,
          isRedirected: true
        };
      case REDUCE_SECOND:
        if (reducerState.timeLeft <= 1) {
          return {
            ...reducerState,
            timeLeft: 4,
            isCounting: false
          };
        }
        return {
          ...reducerState,
          timeLeft: reducerState.timeLeft - 1
        };
      case STORAGE_CONNECT_FAIT:
      default:
        return INITIAL_STATE;
    }
  }, INITIAL_STATE);

  const validatedUrlRef = useRef();

  const storageConnectFailed = () => {
    dispatch({ type: STORAGE_CONNECT_FAIT });
  };

  useEffect(() => {
    WebsocketStore.addEventListener(STORAGE_ADD_ERROR, storageConnectFailed);

    return () => {
      if (validatedUrlRef.current) {
        Requests.sendGenericRequest(
          "/poll",
          RequestsMethods.DELETE,
          Requests.getDefaultUserHeaders(),
          { url: validatedUrlRef.current }
        );
      }

      WebsocketStore.removeEventListener(
        STORAGE_ADD_ERROR,
        storageConnectFailed
      );
    };
  }, []);

  useEffect(() => {
    if (state.isCounting) {
      setTimeout(() => {
        dispatch({ type: REDUCE_SECOND });
      }, 1000);
    }
  }, [state.timeLeft, state.isCounting]);

  const handleSubmit = json => {
    dispatch({ type: VALIDATING_URL });

    UserInfoActions.connectStorage("nextcloud", null, {
      url: json.url.value
    })
      .then(async data => {
        if (data.authUrl) {
          dispatch({ type: URL_VALIDATED });
          validatedUrlRef.current = json.url.value.toString();

          setTimeout(() => {
            dispatch({ type: REDIRECT });
            window.open(data.authUrl, "_blank", "noopener,noreferrer");
          }, 4000);
        } else {
          SnackbarUtils.alertError({ id: "noSuchNextCloudServerUrl" });
        }
      })
      .catch(() => {
        dispatch({ type: STORAGE_CONNECT_FAIT });
      });
  };

  const validateUrl = url =>
    InputValidationFunctions.isURL(url) && url.includes("nextcloud");

  const classes = useStyles();
  return (
    <>
      <DialogBody>
        <Collapse in={state.isRedirected} timeout="auto" unmountOnExit>
          <Loader
            isModal
            message={<FormattedMessage id="waitingForNextCloudIntegration" />}
          />
        </Collapse>
        <Collapse in={!state.isWaitingLogin} timeout="auto" unmountOnExit>
          <KudoForm id="nextcloudForm" onSubmitFunction={handleSubmit}>
            <KudoInput
              name="url"
              label="URL"
              id="url"
              placeHolder="nextCloudUrlPlaceHolder"
              showPlaceHolder
              formId="nextcloudForm"
              type={InputTypes.URL}
              validationFunction={validateUrl}
              classes={{ label: classes.label, formGroup: classes.formGroup }}
              inputDataComponent="nextcloud-url-input"
            />
          </KudoForm>
        </Collapse>
        <Collapse
          in={state.isWaitingLogin && !state.isRedirected}
          timeout="auto"
          unmountOnExit
        >
          <span className={classes.message}>
            <FormattedMessage
              id="youWillBeRedirectedToNextCloud"
              values={{ time: state.timeLeft }}
            />
          </span>
        </Collapse>
      </DialogBody>
      <Collapse in={!state.isWaitingLogin} timeout="auto" unmountOnExit>
        <DialogFooter>
          <KudoButton
            isSubmit={!state.isWaitingLogin}
            isDisabled={state.isWaitingLogin}
            formId="nextcloudForm"
          >
            {state.validatingUrl ? (
              <>
                <FormattedMessage id="connecting" />
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
            ) : (
              <FormattedMessage id="connect" />
            )}
          </KudoButton>
        </DialogFooter>
      </Collapse>
    </>
  );
}
