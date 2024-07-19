import {
  Box,
  CircularProgress,
  Grid,
  IconButton,
  Stack,
  Tooltip,
  Typography,
  styled
} from "@mui/material";
import React, { useContext, useEffect, useState } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { ReactSVG } from "react-svg";
import _ from "underscore";
import FilesListActions from "../../../../../actions/FilesListActions";
import ModalActions from "../../../../../actions/ModalActions";
import closeIconSVG from "../../../../../assets/images/dialogs/icons/closeIconSVG.svg";
import PermissionsCheck from "../../../../../constants/appConstants/PermissionsCheck";
import * as RequestsMethods from "../../../../../constants/appConstants/RequestsMethods";
import * as InputValidationFunctions from "../../../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../../../libraries/MainFunctions";
import FilesListStore from "../../../../../stores/FilesListStore";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import Requests from "../../../../../utils/Requests";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";
import { PermissionsDialogContext } from "../PermissionsDialogContext";
import { Collaborator, PermissionRole } from "../types";
import PermissionRoleMenu from "./PermissionRoleMenu";

const StyledIconButton = styled(IconButton)(({ theme: { palette } }) => ({
  borderRadius: 0,
  color: palette.button.secondary.contained.textColor,
  backgroundColor: palette.button.secondary.contained.background.standard,
  width: 26,
  height: 26
}));

export default function PermittedUser(
  props: Collaborator & { highlight?: boolean }
) {
  const {
    email,
    name,
    collaboratorRole,
    isInherited,
    _id: userId,
    canDelete = true,
    highlight
  } = props;
  const [isProccessing, setProccessing] = useState(false);
  const [isRoleUpdateProgressing, setRoleUpdateProgressing] = useState(false);
  const [isHighligted, setIsHighlighted] = useState(highlight);
  const {
    publicAccess: { fileId },
    invitePeople: {
      objectInfo: { isOwner, permissions, viewOnly, deleted, type, parent, id },
      storage: storageX,
      updateObjectInfo: updateDialog
    }
  } = useContext(PermissionsDialogContext);

  const { storageType: storage, storageId } =
    FilesListStore.findCurrentStorage(fileId);
  const storageName = MainFunctions.storageCodeToServiceName(storage);
  const isSharingAllowed = permissions.canManagePermissions;
  const isUpdateAllowed = isSharingAllowed && !isInherited;
  const isViewOnly = MainFunctions.forceBooleanType(viewOnly);
  const isDeleted = MainFunctions.forceBooleanType(deleted);
  const intl = useIntl();

  const availableRoles = PermissionsCheck.getAvailableRoles(
    (storageX ?? "")?.toUpperCase?.() ?? "",
    type,
    parent,
    isOwner
  );

  const allowedRoles = _.object(
    availableRoles,
    availableRoles.map(_role => intl.formatMessage({ id: _role }))
  );
  let currentAccountEmail =
    (UserInfoStore.getSpecificStorageInfo(storageName, storageId) || {})[
      `${storageName}_username`
    ] || "";

  if (
    storageName.toLowerCase() === "nextcloud" &&
    currentAccountEmail.includes(" at ")
  ) {
    currentAccountEmail = currentAccountEmail.split(" at ").at(0);
  }

  let isAbleToUpdate =
    isUpdateAllowed &&
    (email || userId) &&
    !isViewOnly &&
    !isDeleted &&
    collaboratorRole !== "owner" &&
    Object.keys(allowedRoles).length > 1;

  const storageConfig = UserInfoStore.getStorageConfig(
    storage.toUpperCase()
  ).capabilities;
  const permissionsConfig = storageConfig.share;

  let isUnshareAllowed = false;
  if ((email || userId) && collaboratorRole !== "owner") {
    if (
      email &&
      (email === UserInfoStore.getUserInfo("email") ||
        email === currentAccountEmail)
    ) {
      isUnshareAllowed =
        permissionsConfig[
          `revokeAccessOwn${MainFunctions.capitalizeString(type)}`
        ];
      if (!isViewOnly && !isAbleToUpdate && !isInherited) {
        // DK: fix for https://graebert.atlassian.net/browse/WB-1319
        // basically we cannot update if there's only 1 available role (i.e. we cannot set viewer/owner)
        isAbleToUpdate = Object.keys(allowedRoles).length > 1;
      }
    } else if (
      isOwner ||
      (!isViewOnly &&
        (storageName.toLowerCase() !== "samples" || type === "file"))
    ) {
      isUnshareAllowed =
        permissionsConfig[
          `revokeAccessOthers${MainFunctions.capitalizeString(type)}`
        ];
    }
  }

  if (isInherited) {
    isUnshareAllowed = false;
  }

  // DK: Fix for WB-1583
  if (isUnshareAllowed && !canDelete) {
    isUnshareAllowed = false;
  }

  const ncSelfUpdateHotFix =
    storageName.toLowerCase() === "nextcloud" && currentAccountEmail === email;

  const onUnshareClick = () => {
    if (!isProccessing) {
      setProccessing(true);
      Requests.sendGenericRequest(
        `/${type}s/${id}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        { deshare: [{ email, userId: userId || email }] },
        ["200"]
      )
        .then(() => {
          if (
            userId === UserInfoStore.getUserInfo("id") ||
            (currentAccountEmail !== "" &&
              email !== "" &&
              (userId === currentAccountEmail || email === currentAccountEmail))
          ) {
            if (MainFunctions.detectPageType() !== "file") {
              FilesListActions.deleteEntity(id);
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
          setProccessing(false);
        });
    }
  };

  const updatePermissions = (selectedRole: PermissionRole) => {
    if (selectedRole === collaboratorRole.toLowerCase()) return;
    if (!isProccessing && (email || userId)) {
      let updateData = {} as
        | { share: { editor: string[]; viewer: string[] }; isUpdate: boolean }
        | { newOwner: string };

      setRoleUpdateProgressing(true);
      if (selectedRole === "owner") {
        updateData = {
          newOwner: userId
        };
      } else {
        const { storageType } = MainFunctions.parseObjectId(id);
        let updateId = storageType === "NC" ? userId : email;

        if (
          !updateId ||
          typeof updateId !== "string" ||
          InputValidationFunctions.isEmail(updateId) === false
        ) {
          if (
            (storageType === "HC" || storageType === "HCS") &&
            updateId.includes("@")
          ) {
            updateId = email;
          } else {
            updateId = userId;
          }
        }
        updateData = { share: { editor: [], viewer: [] }, isUpdate: true };
        updateData.share[selectedRole].push(updateId);
      }

      Requests.sendGenericRequest(
        `/${type}s/${id}`,
        RequestsMethods.PUT,
        Requests.getDefaultUserHeaders(),
        updateData,
        ["200"]
      )
        .then(() => {
          updateDialog();
        })
        .catch(err => {
          SnackbarUtils.alertError(err.text);
        })
        .finally(() => {
          setRoleUpdateProgressing(false);
        });
    }
  };

  useEffect(() => {
    if (highlight)
      setTimeout(() => {
        setIsHighlighted(false);
      }, 1000);
  }, []);

  return (
    <Stack
      direction="row"
      alignItems="center"
      data-component="collaborator-row"
      data-name={name}
      data-role={collaboratorRole}
      data-email={email}
      sx={{
        ...(isHighligted ? { background: "#d96a1982 !important" } : {}),
        transition: "background-color ease-in-out 1s"
      }}
    >
      {isProccessing && <CircularProgress size="18px" />}
      <Grid container padding="8px 10px" rowGap="6px">
        <Grid item xs={12}>
          <Stack
            direction="row"
            alignItems="center"
            justifyContent="space-between"
          >
            <Typography sx={{ fontWeight: 700 }}>{`${name}`}</Typography>
            {collaboratorRole === "owner" ? (
              <Typography
                sx={{
                  color: theme =>
                    `${theme.palette.textField.value.placeholder} !important`
                }}
              >
                <FormattedMessage id={collaboratorRole} />
              </Typography>
            ) : (
              <Stack direction="row" alignItems="center" columnGap="14px">
                {isAbleToUpdate && !ncSelfUpdateHotFix ? (
                  <PermissionRoleMenu
                    isProgressing={isRoleUpdateProgressing}
                    permissionRole={collaboratorRole}
                    onRoleSelected={updatePermissions}
                  />
                ) : (
                  <Box>
                    <Typography>
                      <FormattedMessage id={collaboratorRole} />
                    </Typography>
                  </Box>
                )}

                {isUnshareAllowed && (
                  <Tooltip
                    placement="top"
                    title={<FormattedMessage id="unshare" />}
                  >
                    <StyledIconButton
                      onClick={onUnshareClick}
                      sx={{
                        color: theme =>
                          theme.palette.button.secondary.contained.textColor,
                        "& .react-svg-icon > div": { display: "flex" }
                      }}
                      data-component="unshareIcon"
                    >
                      <ReactSVG src={closeIconSVG} className="react-svg-icon" />
                    </StyledIconButton>
                  </Tooltip>
                )}
              </Stack>
            )}
          </Stack>
        </Grid>
        <Grid item xs={12}>
          <Typography>{email}</Typography>
        </Grid>
      </Grid>
    </Stack>
  );
}
