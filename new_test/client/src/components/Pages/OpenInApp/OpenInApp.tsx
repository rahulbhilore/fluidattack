import { Box, Typography } from "@material-ui/core";
import _ from "underscore";
import React, { useEffect, useState, useRef } from "react";
import PropTypes from "prop-types";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import { FormattedMessage, FormattedDate } from "react-intl";
import applicationStore from "../../../stores/ApplicationStore";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import KudoCheckbox from "../../Inputs/KudoCheckbox/KudoCheckbox";
import FilesListActions from "../../../actions/FilesListActions";
import UserInfoActions from "../../../actions/UserInfoActions";
import UserInfoStore, { INFO_UPDATE } from "../../../stores/UserInfoStore";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import Thumbnail from "../../Thumbnail";
import ApplicationActions from "../../../actions/ApplicationActions";
import Loader from "../../Loader";
import Storage from "../../../utils/Storage";
import MainFunctions from "../../../libraries/MainFunctions";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  root: {
    // @ts-ignore
    backgroundColor: theme.palette.JANGO,
    height: "100vh",
    position: "relative",
    flexGrow: 1
  },
  openFileCaption: {
    fontSize: theme.typography.pxToRem(22),
    fontWeight: "bold",
    textAlign: "center",
    // @ts-ignore
    color: theme.palette.REY,
    marginBottom: theme.spacing(2)
  },
  filename: {
    marginTop: theme.spacing(1)
  },
  lastchange: {
    marginTop: theme.spacing(1),
    marginBottom: theme.spacing(1)
  },
  top: {
    minHeight: "30%",
    // @ts-ignore
    backgroundColor: theme.palette.SNOKE,
    // @ts-ignore
    color: theme.palette.LIGHT,
    paddingBottom: theme.spacing(1),
    paddingTop: theme.spacing(6)
  },
  centered: {
    width: "30%",
    margin: "auto",
    textAlign: "center",
    [theme.breakpoints.up("xl")]: {
      width: "20%"
    },
    [theme.breakpoints.down("sm")]: {
      width: "60%"
    },
    [theme.breakpoints.down("xs")]: {
      width: "90%"
    }
  },
  middle: {
    marginTop: theme.spacing(2)
  },
  openedAppCaption: {
    // @ts-ignore
    color: theme.palette.LIGHT,
    marginBottom: theme.spacing(2),
    fontSize: "16px"
  },
  button: {
    borderRadius: 0,
    display: "block",
    width: "100%",
    marginBottom: theme.spacing(2)
  },
  checkbox: {
    // @ts-ignore
    color: theme.palette.REY,
    "& .MuiFormControlLabel-root": {
      marginBottom: 0
    },
    "& .MuiFormHelperText-root": {
      marginTop: "-2px",
      marginLeft: "25px"
    }
  },
  footer: {
    textAlign: "center",
    width: "100%",
    position: "absolute",
    bottom: "70px"
  },
  copyright: {
    // @ts-ignore
    color: theme.palette.CLONE,
    display: "block"
  }
}));

type Props = {
  params: { fileId: string };
  location: { query: { token: string } };
};

type ApplicationToOpen = "AC" | "AK";

type PartialFileInfo = {
  _id: string;
  folderId: string;
  filename: string;
  thumbnail?: string;
  updateDate?: number;
  creationDate?: number;
  changer?: string;
  owner?: string;
};

export default function OpenInAppPage({ params, location }: Props) {
  const defaultTitle = applicationStore.getApplicationSetting("defaultTitle");
  const vendor = applicationStore.getApplicationSetting("vendor");
  const classes = useStyles();
  const [fileInfo, setFileInfo] = useState<PartialFileInfo | null>(null);
  const [openedApp, setOpenedApp] = useState<ApplicationToOpen | null>(null);
  const [error, setError] = useState<string | undefined>(undefined);
  const rememberCheckbox = useRef<KudoCheckbox>(null);
  const mobile = MainFunctions.isMobileDevice();
  const updatePreferences = (appToUse: ApplicationToOpen) => {
    if (rememberCheckbox.current && rememberCheckbox.current.state.value) {
      // save to prefs
      const modificationData = {
        preferences: {
          ...UserInfoStore.getUserInfo("preferences"),
          appToUseDesktop: appToUse
        }
      };
      UserInfoActions.modifyUserInfo(modificationData);
    }
  };
  const queryParams = new URLSearchParams(location.query);
  const threadId = queryParams.get("tId") || "";
  const commentId = queryParams.get("cId") || "";
  const versionId = queryParams.get("versionId") || "";
  const token = queryParams.get("token") || "";
  const openInCommander = () => {
    if (fileInfo === null) return;
    updatePreferences("AC");
    setOpenedApp("AC");
    FilesListActions.openFileInCommander(fileInfo._id, fileInfo.folderId, {
      name: fileInfo.filename,
      threadId,
      commentId,
      versionId,
      token
    });
  };
  const openInKudo = () => {
    updatePreferences("AK");
    setOpenedApp("AK");
    let filePage = `/file/${params.fileId}`;
    if (location.query.token && location.query.token.length > 0) {
      filePage += `?token=${location.query.token}`;
    } else if (fileInfo !== null) {
      filePage = `/file/${fileInfo._id}`;
    }
    if (threadId.length > 0)
      filePage += `${filePage.includes("?") ? "&" : "?"}tId=${threadId}`;
    if (commentId.length > 0)
      filePage += `${filePage.includes("?") ? "&" : "?"}cId=${commentId}`;
    ApplicationActions.changePage(filePage);
  };
  const onUserInfoFetched = () => {
    UserInfoStore.removeChangeListener(INFO_UPDATE, onUserInfoFetched);
    const preferences = UserInfoStore.getUserInfo("preferences");
    if (
      preferences &&
      Object.prototype.hasOwnProperty.call(preferences, "appToUseDesktop")
    ) {
      if (preferences.appToUseDesktop === "AK") openInKudo();
      else if (preferences.appToUseDesktop === "AC") openInCommander();
    }
  };
  useEffect(() => {
    if (_.isEmpty(fileInfo)) {
      if (location.query.token) {
        // for now AC cannot open PLs, so open in kudo directly
        openInKudo();
        return;
      }
      if (!Storage.store("sessionId")) {
        UserInfoActions.loginWithSSO(
          window.location.pathname + window.location.search
        );
        return;
      }

      FilesListActions.getObjectInfo(params.fileId, "file", {})
        .then((file: PartialFileInfo) => {
          setFileInfo(file);
        })
        .catch(() => {
          // DESKTOP-245183
          // this page is shown for shared files as well, so it has to be verified
          const { storageType, objectId } = MainFunctions.parseObjectId(
            params.fileId
          );
          const specialFileId = `${storageType}+${objectId}`;

          FilesListActions.getObjectInfo(specialFileId, "file", {})
            .then((finalFileInfo: PartialFileInfo) => {
              setFileInfo(finalFileInfo);
            })
            .catch((err: unknown) => {
              let message = "URLIsInvalid";
              if (err?.data?.errorConstant === "FILE_GET_INFO_ERROR") {
                message = "noAccessToFile";
              }
              SnackbarUtils.alertError({ id: message });
              setError(message);
            });
        });
      return;
    }

    if (
      UserInfoStore.getUserInfo("isLoggedIn") &&
      UserInfoStore.getUserInfo("isFullInfo")
    ) {
      onUserInfoFetched();
    } else {
      UserInfoStore.addChangeListener(INFO_UPDATE, onUserInfoFetched);
    }
  }, [fileInfo]);
  useEffect(() => {
    document.title = `${defaultTitle} | Open file`;
  }, []);
  return (
    <Box className={classes.root}>
      <Box className={classes.top}>
        <Typography variant="h2" className={classes.openFileCaption}>
          <FormattedMessage id="openFile" />
        </Typography>
        {error ? (
          <Box className={classes.centered}>
            <Typography variant="body1" className={classes.openedAppCaption}>
              <FormattedMessage id={error} />
            </Typography>
          </Box>
        ) : null}
        {fileInfo && Object.keys(fileInfo).length > 0 && !error ? (
          <Box className={classes.centered}>
            {fileInfo.thumbnail ? (
              <Thumbnail src={fileInfo.thumbnail} width={165} height={88} />
            ) : null}
            <Typography variant="body1" className={classes.filename}>
              {fileInfo.filename}
            </Typography>
            <Typography variant="body1" className={classes.lastchange}>
              <FormattedMessage
                id="lastChange"
                values={{
                  strong: IntlTagValues.strong,
                  date: (
                    <FormattedDate
                      value={fileInfo.updateDate || fileInfo.creationDate}
                    />
                  ),
                  user: fileInfo.changer || fileInfo.owner || "unknown"
                }}
              />
            </Typography>
          </Box>
        ) : null}
        {fileInfo === null && !error ? <Loader /> : null}
      </Box>
      {fileInfo &&
      Object.keys(fileInfo).length > 0 &&
      openedApp === null &&
      !error ? (
        <Box className={clsx(classes.centered, classes.middle)}>
          <KudoButton
            isDisabled={false}
            className={classes.button}
            onClick={openInKudo}
          >
            <FormattedMessage id="openInKudo" />
          </KudoButton>
          <KudoButton
            isDisabled={false}
            className={classes.button}
            onClick={openInCommander}
          >
            {mobile ? (
              <FormattedMessage id="openInTouch" />
            ) : (
              <FormattedMessage id="openInCommander" />
            )}
          </KudoButton>
          <KudoCheckbox
            label="useMyChoiceAsDefault"
            reverse
            ref={rememberCheckbox}
            className={classes.checkbox}
            styles={{
              icon: {
                backgroundColor: "#1e2023",
                border: "solid 1px #000000",
                "input:hover ~ &": {
                  backgroundColor: "#1e2023"
                }
              }
            }}
            helperText="youCanChangeInProfile"
          />
        </Box>
      ) : null}
      {fileInfo &&
      Object.keys(fileInfo).length > 0 &&
      openedApp !== null &&
      !error ? (
        <Box className={clsx(classes.centered, classes.middle)}>
          <Typography variant="body1" className={classes.openedAppCaption}>
            {mobile ? (
              <FormattedMessage id="fileIsOpenedInAT" />
            ) : (
              <FormattedMessage id="fileIsOpenedInAC" />
            )}
          </Typography>
          <KudoButton
            isDisabled={false}
            className={classes.button}
            onClick={openInKudo}
          >
            <FormattedMessage id="openInKudoInstead" />
          </KudoButton>
          <KudoCheckbox
            label="useMyChoiceAsDefault"
            reverse
            ref={rememberCheckbox}
            className={classes.checkbox}
            styles={{
              icon: {
                backgroundColor: "#1e2023",
                border: "solid 1px #000000",
                "input:hover ~ &": {
                  backgroundColor: "#1e2023"
                }
              }
            }}
            helperText="youCanChangeInProfile"
          />
        </Box>
      ) : null}
      <Box component="footer" className={classes.footer}>
        <Typography variant="body1" className={classes.copyright}>
          <FormattedMessage
            id="copyright"
            values={{ year: new Date().getFullYear(), vendor }}
          />
        </Typography>
      </Box>
    </Box>
  );
}

OpenInAppPage.propTypes = {
  params: PropTypes.shape({
    fileId: PropTypes.string.isRequired
  }).isRequired,
  location: PropTypes.shape({
    query: PropTypes.shape({
      token: PropTypes.string.isRequired
    })
  }).isRequired
};
