import React, { useState } from "react";
import PropTypes from "prop-types";
import { FormattedMessage } from "react-intl";
import Tooltip from "@material-ui/core/Tooltip";
import { Grid, IconButton, Typography } from "@material-ui/core";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import KudoSelect from "../../../../Inputs/KudoSelect/KudoSelect";
import updateSVG from "../../../../../assets/images/Update.svg";
import updateDisabledSVG from "../../../../../assets/images/Update-disabled.svg";
import closeSVG from "../../../../../assets/images/Close.svg";

const useStyles = makeStyles(theme => ({
  ownerInfo: {
    fontWeight: "bold",
    color: theme.palette.CLONE
  },
  nameRow: {
    lineHeight: 1,
    [theme.breakpoints.down("sm")]: {
      lineHeight: 1.5
    }
  },
  username: {
    fontWeight: "bold"
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

export default function IndependentCollaborator({
  currentRole,
  username,
  email,
  allowedRoles,
  isUpdateAllowed,
  isViewOnly,
  isOwner,
  userId,
  updatePermission,
  removePermission
}) {
  const [isProcessing, setProcessing] = useState(false);
  const [isUpdate, setUpdate] = useState(false);
  const [role, setRole] = useState(currentRole.toLowerCase());

  const handlePermissionUpdate = () => {
    if (!isProcessing && isUpdate && (email || userId)) {
      setProcessing(true);
      updatePermission(email, role).finally(() => {
        setUpdate(false);
        setProcessing(false);
      });
    }
  };

  const unshare = () => {
    if (!isProcessing) {
      setProcessing(true);
      // No need to update after
      removePermission(email);
    }
  };

  const updateRole = newRole => {
    setRole(newRole);
    setUpdate(newRole !== role);
  };

  const isAbleToUpdate =
    isUpdateAllowed &&
    (email || userId) &&
    !isViewOnly &&
    Object.keys(allowedRoles).length > 1;

  let isUnshareAllowed = false;
  if (isUpdateAllowed) {
    // remove self access
    if (email && email === UserInfoStore.getUserInfo("email")) {
      isUnshareAllowed = true;
    } else if (!email && !userId) {
      isUnshareAllowed = false;
    } else if (isOwner || !isViewOnly) {
      // editor/owner remove access
      isUnshareAllowed = true;
    }
  }

  const classes = useStyles();

  const ownerClass = role === "owner" ? classes.ownerInfo : "";
  const userInfo =
    email && username ? (
      <>
        <Typography className={clsx(classes.nameRow, classes.username)}>
          {username}
        </Typography>
        <Typography className={classes.nameRow}>{email}</Typography>
      </>
    ) : (
      <Typography className={ownerClass}>
        {email || username || "UNKNOWN"}
      </Typography>
    );

  return (
    <Grid container spacing={1} className={classes.root}>
      <Grid item xs={12} sm={6} md={7}>
        {userInfo}
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
        {isAbleToUpdate ? (
          <KudoSelect
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
          {isAbleToUpdate ? (
            <Tooltip placement="top" title={<FormattedMessage id="update" />}>
              <IconButton
                className={classes.actionButton}
                onClick={handlePermissionUpdate}
              >
                <img
                  src={isUpdate ? updateSVG : updateDisabledSVG}
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
              <IconButton className={classes.actionButton} onClick={unshare}>
                <img
                  src={closeSVG}
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

IndependentCollaborator.propTypes = {
  currentRole: PropTypes.string.isRequired,
  username: PropTypes.string.isRequired,
  email: PropTypes.string,
  allowedRoles: PropTypes.objectOf(PropTypes.string).isRequired,
  isUpdateAllowed: PropTypes.bool,
  isViewOnly: PropTypes.bool.isRequired,
  isOwner: PropTypes.bool.isRequired,
  userId: PropTypes.string,
  updatePermission: PropTypes.func.isRequired,
  removePermission: PropTypes.func.isRequired
};

IndependentCollaborator.defaultProps = {
  email: "",
  isUpdateAllowed: false,
  userId: ""
};
