import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import { FormattedMessage, useIntl } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import _ from "underscore";
import { Typography } from "@material-ui/core";
import * as IntlTagValues from "../../../../../constants/appConstants/IntlTagValues";
import PermissionsCheck from "../../../../../constants/appConstants/PermissionsCheck";
import * as InputValidationFunctions from "../../../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import FormManagerActions from "../../../../../actions/FormManagerActions";
import KudoForm from "../../../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../../../Inputs/KudoButton/KudoButton";
import KudoSelect from "../../../../Inputs/KudoSelect/KudoSelect";
import CollaboratorsList from "./CollaboratorsList";
import FilesListActions from "../../../../../actions/FilesListActions";
import KudoAutocomplete from "../../../../Inputs/AutoComplete/AutoComplete";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";

const useStyles = makeStyles(theme => ({
  root: {
    padding: theme.spacing(1.5),
    "& > div": {
      width: "100%"
    }
  },
  label: {
    marginBottom: theme.spacing(1),
    display: "block",
    lineHeight: 1
  },
  input: {
    height: "34px"
  },
  notificationMessage: {
    marginBottom: theme.spacing(1)
  }
}));

export default function InternalAccess({
  storage,
  collaborators,
  type,
  parent,
  isOwner,
  objectId,
  isDeleted,
  isViewOnly,
  isSharingAllowed,
  ownerId,
  ownerName,
  ownerEmail,
  updateObjectInfo
}) {
  const intl = useIntl();
  const shareObject = sendData => {
    let requestData = {};
    if (sendData.role === "owner") {
      requestData = {
        newOwner: sendData.username
      };
    } else {
      requestData = {
        share: {
          editor: [],
          viewer: []
        }
      };
      requestData.share[sendData.role].push(sendData.username);
    }

    FilesListActions.shareObject(type, objectId, requestData)
      .then(data => {
        // clear form
        FormManagerActions.clearForm("permissions");
        if (data.status !== "ok") {
          SnackbarUtils.alertError(data.error);
        } else if ((data?.nonExistentEmails || []).length > 0) {
          // DK: endpoint allows multiple shares, but UI doesn't support it
          // So for now it should be fine but it should be updated later
          // if we ever decide to allow sharing to multiple users at once
          SnackbarUtils.alertError({ id: "sharingToUnregisteredUser" });
        } else {
          // update dialog data
          updateObjectInfo();
        }
      })
      .catch(err => {
        SnackbarUtils.alertError(err.message);
      });
  };

  const checkSharingPossibility = sendData => {
    if (storage === "internal") {
      FilesListActions.checkSharingPossibility(
        type,
        objectId,
        sendData.username
      )
        .then(data => {
          if (data.status !== "ok") {
            SnackbarUtils.alertError(data.error);
            // clear form
            FormManagerActions.clearForm("permissions");
          } else if (!data.possible) {
            SnackbarUtils.alertError({ id: "usedName" });
            // clear form
            FormManagerActions.clearForm("permissions");
          } else {
            shareObject(sendData);
          }
        })
        .catch(err => {
          SnackbarUtils.alertError(err.message);
        });
    } else {
      shareObject(sendData);
    }
  };

  const handleSubmit = formValues => {
    const sendData = _.mapObject(formValues, formElement => formElement.value);
    const user = sendData.username.toLowerCase();
    if (user.length) {
      if (
        (storage.toLowerCase() === "internal" &&
          UserInfoStore.getUserInfo("email").indexOf(user) > -1) ||
        user === ownerName
      ) {
        SnackbarUtils.alertError({ id: "cantShareWithOwner" });
      } else {
        checkSharingPossibility(sendData);
      }
    } else {
      SnackbarUtils.alertError({ id: "noUserSelect" });
    }
  };

  const storageConfig = UserInfoStore.getStorageConfig(
    storage.toUpperCase()
  ).capabilities;
  const availableRoles = PermissionsCheck.getAvailableRoles(
    storage.toUpperCase(),
    type,
    parent,
    isOwner
  );
  const sharingOptions = _.object(
    availableRoles,
    availableRoles.map(role => intl.formatMessage({ id: role }))
  );
  const { storageId } = MainFunctions.parseObjectId(objectId);
  const isKudoStorage =
    storage.trim().toLowerCase() === "samples" ||
    storage.trim().toLowerCase() === "internal";
  const currentAccountEmail =
    isKudoStorage && isOwner
      ? UserInfoStore.getUserInfo("email")
      : (UserInfoStore.getSpecificStorageInfo(storage, storageId) || {})[
          `${storage}_username`
        ] || "";
  const classes = useStyles();
  return (
    <Grid container className={classes.root}>
      <KudoForm
        id="permissions"
        onSubmitFunction={handleSubmit}
        autoComplete="off"
      >
        {storage === "onedrive" ||
        storage === "dropbox" ||
        storage === "nextcloud" ? (
          <Grid item xs={12} className={classes.notificationMessage}>
            {/* XENON-22600 */}
            {storage === "onedrive" ? (
              <Typography>
                <FormattedMessage
                  id="onedriveSharingNotification"
                  values={{ br: IntlTagValues.br }}
                />
              </Typography>
            ) : null}
            {/* XENON-22600 */}
            {storage === "dropbox" ? (
              <Typography>
                <FormattedMessage
                  id="dropboxSharingNotification"
                  values={{ br: IntlTagValues.br }}
                />
              </Typography>
            ) : null}
            {/* XENON-61659 */}
            {storage === "nextcloud" ? (
              <Typography>
                <FormattedMessage
                  id="nextcloudSharingNotification"
                  values={{ br: IntlTagValues.br }}
                />
              </Typography>
            ) : null}
          </Grid>
        ) : null}
        <Grid item sm={12}>
          <Grid container spacing={1}>
            {storage !== "nextcloud" &&
            !isDeleted &&
            !isViewOnly &&
            isSharingAllowed !== false ? (
              <>
                <Grid item xs={12} sm={7} md={7}>
                  <KudoAutocomplete
                    name="username"
                    label={storage === "nextcloud" ? "Username" : "Email"}
                    id="username"
                    formId="permissions"
                    fileId={objectId}
                    inputDataComponent="email-input"
                    validationFunction={value =>
                      (storage === "nextcloud" ||
                        InputValidationFunctions.isEmail(value)) &&
                      _.pluck(collaborators, "email").indexOf(value) === -1 &&
                      value.toLowerCase() !== currentAccountEmail.toLowerCase()
                    }
                    helpMessageFun={value => {
                      if (value.length === 0) {
                        return "";
                      }
                      if (_.pluck(collaborators, "email").indexOf(value) > -1) {
                        return { id: "userIsAlreadyCollaborator" };
                      }
                      if (
                        !InputValidationFunctions.isEmail(value) &&
                        storage !== "nextcloud"
                      ) {
                        return { id: "emailDidntPassValidation" };
                      }
                      if (
                        value.toLowerCase() ===
                        currentAccountEmail.toLowerCase()
                      ) {
                        return { id: "cantShareWithAccountOwner" };
                      }
                      return "";
                    }}
                  />
                </Grid>
                <Grid item xs={12} sm={3} md={3}>
                  <KudoSelect
                    options={sharingOptions}
                    id="role"
                    name="role"
                    label="role"
                    formId="permissions"
                    defaultValue={Object.keys(sharingOptions)[0]}
                    styles={{
                      select: {
                        "& .MuiSelect-root": {
                          padding: "10px 0 10px 12px"
                        }
                      }
                    }}
                    dataComponent="sharing-options"
                  />
                </Grid>
                <Grid item xs={12} sm={2} md={2}>
                  <KudoButton
                    isSubmit
                    formId="permissions"
                    styles={{
                      button: {
                        marginTop: "20px",
                        height: "36px",
                        borderRadius: 0,
                        width: "100%"
                      }
                    }}
                  >
                    <FormattedMessage id="add" />{" "}
                  </KudoButton>
                </Grid>
              </>
            ) : null}
            <CollaboratorsList
              collaborators={collaborators}
              ownerId={ownerId}
              type={type}
              objectId={objectId}
              isViewOnly={isViewOnly}
              sharingOptions={sharingOptions}
              ownerName={ownerName}
              ownerEmail={ownerEmail}
              storageConfig={storageConfig}
              isOwner={isOwner}
              updateObjectInfo={updateObjectInfo}
              isSharingAllowed={isSharingAllowed}
            />
          </Grid>
        </Grid>
      </KudoForm>
    </Grid>
  );
}
InternalAccess.propTypes = {
  storage: PropTypes.string.isRequired,
  type: PropTypes.string,
  collaborators: PropTypes.arrayOf(
    PropTypes.shape({
      _id: PropTypes.string,
      email: PropTypes.string
    })
  ),
  parent: PropTypes.string,
  isOwner: PropTypes.bool,
  objectId: PropTypes.string.isRequired,
  isDeleted: PropTypes.bool,
  isViewOnly: PropTypes.bool,
  isSharingAllowed: PropTypes.bool,
  ownerId: PropTypes.string,
  updateObjectInfo: PropTypes.func,
  ownerName: PropTypes.string,
  ownerEmail: PropTypes.string
};

InternalAccess.defaultProps = {
  collaborators: [],
  type: "file",
  parent: "-1",
  isOwner: false,
  isDeleted: false,
  isViewOnly: true,
  isSharingAllowed: true,
  ownerId: "",
  ownerName: "",
  ownerEmail: "",
  updateObjectInfo: () => null
};
