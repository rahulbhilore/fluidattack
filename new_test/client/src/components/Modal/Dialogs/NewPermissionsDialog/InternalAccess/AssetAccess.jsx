import React, { useCallback, useRef } from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import { FormattedMessage, useIntl } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import _ from "underscore";
import * as InputValidationFunctions from "../../../../../constants/validationSchemas/InputValidationFunctions";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import FormManagerStore from "../../../../../stores/FormManagerStore";
import FormManagerActions from "../../../../../actions/FormManagerActions";
import KudoForm from "../../../../Inputs/KudoForm/KudoForm";
import KudoButton from "../../../../Inputs/KudoButton/KudoButton";
import KudoSelect from "../../../../Inputs/KudoSelect/KudoSelect";
import KudoAutocomplete from "../../../../Inputs/AutoCompleteNext/AutoComplete";
import IndependentCollaboratorsList from "./IndependentCollaboratorsList";
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

export default function AssetAccess({
  collaborators,
  objectId,
  isViewOnly,
  isSharingAllowed,
  ownerId,
  ownerName,
  updateObjectInfo,
  shareAsset,
  removePermission,
  updatePermission,
  roles,
  scope
}) {
  const autoCompleteRef = useRef();
  const intl = useIntl();
  const shareObject = sendData => {
    shareAsset(sendData);
  };

  const handleSubmit = formValues => {
    const username = autoCompleteRef.current.getValue().email;

    const sendData = {
      username,
      role: formValues.role.value
    };

    const user = sendData.username.toLowerCase();
    if (user.length) {
      if (user === ownerName) {
        SnackbarUtils.alertError({ id: "cantShareWithOwner" });
      } else {
        autoCompleteRef.current?.reset();
        shareObject(sendData);
      }
    } else {
      SnackbarUtils.alertError({ id: "noUserSelect" });
    }
  };
  const availableRoles = ["editor", "viewer"];
  let sharingOptions = _.object(
    availableRoles,
    availableRoles.map(role => intl.formatMessage({ id: role }))
  );

  if (roles && roles.length > 0) {
    sharingOptions = _.object(
      roles.map(({ value }) => value),
      roles.map(({ label }) => intl.formatMessage({ id: label }))
    );
  }

  const currentAccountEmail = UserInfoStore.getUserInfo("email");

  const autoCompleteValidator = useCallback(value => {
    const isValidEmail =
      InputValidationFunctions.isEmail(value) &&
      _.pluck(collaborators, "email").indexOf(value) === -1 &&
      value.toLowerCase() !== currentAccountEmail.toLowerCase();

    const submitButtonId = FormManagerStore.getButtonIdForForm("permissions");

    FormManagerActions.changeButtonState(
      "permissions",
      submitButtonId,
      isValidEmail
    );

    return isValidEmail;
  }, []);

  const classes = useStyles();
  return (
    <Grid container className={classes.root}>
      <KudoForm
        id="permissions"
        onSubmitFunction={handleSubmit}
        autoComplete="off"
      >
        <Grid item sm={12}>
          <Grid container spacing={1}>
            {isSharingAllowed !== false ? (
              <>
                <Grid item xs={12} sm={7} md={7}>
                  <KudoAutocomplete
                    ref={autoCompleteRef}
                    name="username"
                    label="Email"
                    id="username"
                    formId="permissions"
                    fileId={objectId}
                    scope={scope}
                    inputDataComponent="email-input"
                    validationFunction={autoCompleteValidator}
                    helpMessageFun={value => {
                      if (value.length === 0) {
                        return "";
                      }
                      if (_.pluck(collaborators, "email").indexOf(value) > -1) {
                        return { id: "userIsAlreadyCollaborator" };
                      }
                      if (!InputValidationFunctions.isEmail(value)) {
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
            <IndependentCollaboratorsList
              collaborators={collaborators}
              ownerId={ownerId}
              type="file"
              objectId={objectId}
              isViewOnly={isViewOnly}
              sharingOptions={sharingOptions}
              ownerName={ownerName}
              updateObjectInfo={updateObjectInfo}
              isSharingAllowed={isSharingAllowed}
              removePermission={removePermission}
              updatePermission={updatePermission}
            />
          </Grid>
        </Grid>
      </KudoForm>
    </Grid>
  );
}
AssetAccess.propTypes = {
  collaborators: PropTypes.arrayOf(
    PropTypes.shape({
      _id: PropTypes.string,
      email: PropTypes.string
    })
  ),
  roles: PropTypes.arrayOf(
    PropTypes.shape({
      value: PropTypes.string,
      label: PropTypes.string
    })
  ),
  objectId: PropTypes.string.isRequired,
  isViewOnly: PropTypes.bool,
  isSharingAllowed: PropTypes.bool,
  ownerId: PropTypes.string,
  updateObjectInfo: PropTypes.func,
  ownerName: PropTypes.string,
  shareAsset: PropTypes.func.isRequired,
  removePermission: PropTypes.func.isRequired,
  updatePermission: PropTypes.func.isRequired,
  scope: PropTypes.string
};

AssetAccess.defaultProps = {
  collaborators: [],
  roles: [],
  isViewOnly: true,
  isSharingAllowed: true,
  ownerId: "",
  ownerName: "",
  updateObjectInfo: () => null,
  scope: "asset"
};
