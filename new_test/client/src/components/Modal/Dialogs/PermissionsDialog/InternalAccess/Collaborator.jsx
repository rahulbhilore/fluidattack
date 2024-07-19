import React, { useState } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Tooltip from "@material-ui/core/Tooltip";
import {
  Grid,
  IconButton,
  Typography,
  CircularProgress,
  Box
} from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import * as RequestsMethods from "../../../../../constants/appConstants/RequestsMethods";
import Requests from "../../../../../utils/Requests";
import MainFunctions from "../../../../../libraries/MainFunctions";
import ModalActions from "../../../../../actions/ModalActions";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import FilesListStore from "../../../../../stores/FilesListStore";
import * as InputValidationFunctions from "../../../../../constants/validationSchemas/InputValidationFunctions";
import KudoSelect from "../../../../Inputs/KudoSelect/KudoSelect";
import updateSVG from "../../../../../assets/images/Update.svg";
import updateDisabledSVG from "../../../../../assets/images/Update-disabled.svg";
import closeSVG from "../../../../../assets/images/Close.svg";
import closeDisabledSVG from "../../../../../assets/images/Close-disabled.svg";
import FilesListActions from "../../../../../actions/FilesListActions";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  ownerInfo: {
    fontWeight: "bold",
    color: theme.palette.OBI
  },
  nameRow: {
    lineHeight: 1.2,
    [theme.breakpoints.down("sm")]: {
      lineHeight: 1.5
    }
  },
  username: {
    fontWeight: "bold",
    marginBottom: "3px"
  },
  inheritedInfoClass: {
    lineHeight: 1.4,
    fontWeight: "bold",
    fontSize: "10px"
  },
  root: {
    minHeight: "45px",
    alignItems: "center"
  },
  actionButton: {
    padding: 0
  },
  actionsBlock: {
    minHeight: "36px",
    alignItems: "start",
    display: "flex",
    justifyContent: "center"
  },
  actionImage: {
    height: "25px",
    width: "25px"
  }
}));

export default function Collaborator({
  currentRole,
  username,
  email,
  allowedRoles,
  isUpdateAllowed,
  objectType,
  objectId,
  isViewOnly,
  isDeleted,
  isOwner,
  isInherited,
  storageConfig,
  userId,
  updateDialog
}) {
  const [isProcessing, setProcessing] = useState(false);
  const [isUpdate, setUpdate] = useState(false);
  const [role, setRole] = useState(currentRole.toLowerCase());

  const { accountId, storage } = FilesListStore.getCurrentFolder();

  const storageName = MainFunctions.storageCodeToServiceName(storage);
  let currentAccountEmail =
    (UserInfoStore.getSpecificStorageInfo(storageName, accountId) || {})[
      `${storageName}_username`
    ] || "";

  if (
    storageName.toLowerCase() === "nextcloud" &&
    currentAccountEmail.includes(" at ")
  ) {
    currentAccountEmail = currentAccountEmail.split(" at ").at(0);
  }

  // let storageEmail = UserInfoStore.getUserInfo("storage").email;
  // if (UserInfoStore.getUserInfo("storage").type === "nextcloud") {
  //   [storageEmail] = storageEmail.split(" at ");
  // }

  const updatePermissions = () => {
    if (!isProcessing && isUpdate && (email || userId)) {
      let updateData;
      setProcessing(true);
      if (role === "owner") {
        updateData = {
          newOwner: userId
        };
      } else {
        const { storageType } = MainFunctions.parseObjectId(objectId);
        let updateId = storageType === "NC" ? userId : email;

        if (
          !updateId ||
          typeof updateId !== "string" ||
          InputValidationFunctions.isEmail(updateId) === false
        ) {
          // Hancom doesn't encode properly
          if (
            (storageType === "HC" || storageType === "HCS") &&
            updateId.includes("@")
          ) {
            updateId = email;
          } else {
            updateId = userId;
          }
        }
        updateData = { share: { editor: [], viewer: [] } };
        updateData.share[role].push(updateId);
        updateData.isUpdate = true;
      }
      setUpdate(false);
      Requests.sendGenericRequest(
        `/${objectType}s/${objectId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        updateData,
        [200]
      )
        .then(() => {
          // update dialog data
          updateDialog();
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text);
        })
        .finally(() => {
          setProcessing(false);
        });
    }
  };

  const unshare = () => {
    if (!isProcessing) {
      setProcessing(true);
      Requests.sendGenericRequest(
        `/${objectType}s/${objectId}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        { deshare: [{ email, userId: userId || email }] },
        [200]
      )
        .then(() => {
          if (
            userId === UserInfoStore.getUserInfo("id") ||
            (currentAccountEmail !== "" &&
              email !== "" &&
              (userId === currentAccountEmail || email === currentAccountEmail))
          ) {
            if (MainFunctions.detectPageType() !== "file") {
              FilesListActions.deleteEntity(objectId);
            }
            ModalActions.hide();
          } else {
            // update dialog data
            updateDialog(true);
          }
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text);
          updateDialog(true);
        })
        .finally(() => {
          setProcessing(false);
        });
    }
  };

  const updateRole = newRole => {
    setRole(newRole);
    setUpdate(newRole !== role);
  };

  let isAbleToUpdate =
    isUpdateAllowed &&
    (email || userId) &&
    !isViewOnly &&
    !isDeleted &&
    currentRole !== "owner" &&
    Object.keys(allowedRoles).length > 1;

  const permissionsConfig = storageConfig.share;

  let isUnshareAllowed = false;
  if ((email || userId) && currentRole !== "owner") {
    if (
      email &&
      (email === UserInfoStore.getUserInfo("email") ||
        email === currentAccountEmail)
    ) {
      isUnshareAllowed =
        permissionsConfig[
          `revokeAccessOwn${MainFunctions.capitalizeString(objectType)}`
        ];
      if (!isViewOnly && !isAbleToUpdate && !isInherited) {
        // DK: fix for https://graebert.atlassian.net/browse/WB-1319
        // basically we cannot update if there's only 1 available role (i.e. we cannot set viewer/owner)
        isAbleToUpdate = Object.keys(allowedRoles).length > 1;
      }
    } else if (
      isOwner ||
      (!isViewOnly &&
        (storageName.toLowerCase() !== "samples" || objectType === "file"))
    ) {
      isUnshareAllowed =
        permissionsConfig[
          `revokeAccessOthers${MainFunctions.capitalizeString(objectType)}`
        ];
    }
  }

  if (isInherited) {
    isUnshareAllowed = false;
  }

  const classes = useStyles();

  const ownerClass = role === "owner" ? classes.ownerInfo : "";
  const userNameClass = clsx(
    ownerClass,
    role !== "owner" && [classes.nameRow, classes.username]
  );
  const userInfo =
    email && username ? (
      <>
        <Typography
          className={userNameClass}
          data-component="collaboratorUsername"
        >
          {username}
        </Typography>
        <Box sx={{ display: "flex", flexDirection: "row", gap: 3 }}>
          <Typography
            className={classes.nameRow}
            data-component="collaboratorEmail"
          >
            {email}
          </Typography>
          {isInherited ? (
            <Typography
              className={[classes.nameRow, classes.inheritedInfoClass]}
              data-component="inherited"
            >
              (<FormattedMessage id="inherited" />)
            </Typography>
          ) : null}
        </Box>
      </>
    ) : (
      <Typography
        className={ownerClass}
        data-component="collaboratorIdentificator"
      >
        {email || username || "UNKNOWN"}
      </Typography>
    );

  // xenon 65673
  const ncSelfUpdateHotFix =
    storageName.toLowerCase() === "nextcloud" && currentAccountEmail === email;

  return (
    <Grid container spacing={1} className={classes.root}>
      <Grid item xs={12} sm={6} md={7}>
        <Box
          sx={{
            display: "flex",
            alignItems: "center"
          }}
        >
          <CircularProgress
            style={{
              opacity: isProcessing ? 1 : 0,
              transition: "all .3s"
            }}
            size="18px"
          />
          <Box
            sx={{
              transition: "all .3s",
              transform: isProcessing ? "translateX(10px)" : "translateX(-18px)"
            }}
          >
            {userInfo}
          </Box>
        </Box>
      </Grid>
      <Grid
        item
        xs={9}
        sm={4}
        md={3}
        className={`permissionsUpdate${
          !isAbleToUpdate && !isUnshareAllowed ? " noActions" : ""
        }`}
      >
        {isAbleToUpdate && !ncSelfUpdateHotFix ? (
          <KudoSelect
            disabled={isProcessing}
            options={allowedRoles}
            id="permissionsUpdate"
            name="permissionsUpdate"
            defaultValue={role}
            onChange={updateRole}
            dataComponent="collaborator-select"
            styles={{
              select: {
                "& .MuiSelect-select": {
                  padding: "8px!important"
                }
              },
              label: {
                marginBottom: "8px"
              }
            }}
          />
        ) : (
          <Typography className={ownerClass}>
            <FormattedMessage id={role} />
          </Typography>
        )}
      </Grid>
      {isAbleToUpdate || isUnshareAllowed ? (
        <Grid item xs={3} sm={2} className={classes.actionsBlock}>
          {isAbleToUpdate && !ncSelfUpdateHotFix ? (
            <Tooltip placement="top" title={<FormattedMessage id="update" />}>
              <IconButton
                disabled={isProcessing}
                className={classes.actionButton}
                onClick={updatePermissions}
              >
                <img
                  data-component="updatePermissionIcon"
                  src={
                    !isUpdate || isProcessing ? updateDisabledSVG : updateSVG
                  }
                  alt="Update permissions"
                  className={classes.actionImage}
                />
              </IconButton>
            </Tooltip>
          ) : (
            <div id="updateIconPlaceholder" />
          )}
          {isUnshareAllowed ? (
            <Tooltip placement="top" title={<FormattedMessage id="unshare" />}>
              <IconButton
                className={classes.actionButton}
                disabled={isProcessing}
                onClick={unshare}
              >
                <img
                  data-component="unshareIcon"
                  src={isProcessing ? closeDisabledSVG : closeSVG}
                  alt="Unshare"
                  className={classes.actionImage}
                />
              </IconButton>
            </Tooltip>
          ) : (
            <div id="updateIconPlaceholder" />
          )}
        </Grid>
      ) : null}
    </Grid>
  );
}

Collaborator.propTypes = {
  currentRole: PropTypes.string.isRequired,
  username: PropTypes.string.isRequired,
  email: PropTypes.string,
  allowedRoles: PropTypes.objectOf(PropTypes.string).isRequired,
  isUpdateAllowed: PropTypes.bool,
  objectType: PropTypes.string.isRequired,
  objectId: PropTypes.string.isRequired,
  isViewOnly: PropTypes.bool.isRequired,
  isDeleted: PropTypes.bool.isRequired,
  isInherited: PropTypes.bool,
  isOwner: PropTypes.bool.isRequired,
  storageConfig: PropTypes.shape({ share: PropTypes.objectOf(PropTypes.bool) })
    .isRequired,
  userId: PropTypes.string,
  updateDialog: PropTypes.func
};

Collaborator.defaultProps = {
  email: "",
  isUpdateAllowed: false,
  isInherited: false,
  userId: "",
  updateDialog: () => null
};
