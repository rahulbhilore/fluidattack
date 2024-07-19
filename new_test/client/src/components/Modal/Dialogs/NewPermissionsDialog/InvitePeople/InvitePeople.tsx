import AddIcon from "@mui/icons-material/Add";
import { Collapse, Grid, Stack, Typography } from "@mui/material";
import React, {
  SyntheticEvent,
  useCallback,
  useContext,
  useRef,
  useState
} from "react";
import { useForm } from "react-hook-form";
import { FormattedMessage, useIntl } from "react-intl";
import _ from "underscore";
import FilesListActions from "../../../../../actions/FilesListActions";
import * as IntlTagValues from "../../../../../constants/appConstants/IntlTagValues";
import * as InputValidationFunctions from "../../../../../constants/validationSchemas/InputValidationFunctions";
import MainFunctions from "../../../../../libraries/MainFunctions";
import UserInfoStore from "../../../../../stores/UserInfoStore";
import { StorageType } from "../../../../../types/StorageTypes";
import KudoAutocomplete, {
  RefType
} from "../../../../Inputs/AutoCompleteNext/AutoComplete";
import SnackbarUtils from "../../../../Notifications/Snackbars/SnackController";
import { PermissionsDialogContext } from "../PermissionsDialogContext";
import ResponsiveButton from "../PublicFileLink/ResponsiveButton";
import SectionHeader from "../SectionHeader";
import { PermissionRole } from "../types";
import PermissionRoleMenu from "./PermissionRoleMenu";
import PermittedUserList from "./PermittedUserList";

type FormValues = {
  username: string;
  role: PermissionRole;
};

export default function InvitePeople() {
  const {
    invitePeople: {
      availableRoles,
      collaborators,
      storage,
      updateObjectInfo,
      isExpanded,
      setIsExpanded,
      objectInfo: {
        isOwner,
        id: objectId,
        permissions: { canManagePermissions },
        owner: ownerName,
        type
      }
    },
    publicAccess: { isDeleted, isViewOnly }
  } = useContext(PermissionsDialogContext);
  const {
    setValue,
    watch,
    handleSubmit,
    reset: resetForm
  } = useForm<FormValues>({
    defaultValues: {
      username: "",
      role: availableRoles.includes("editor") ? "editor" : availableRoles[0]
    }
  });
  const [isLoading, setIsLoading] = useState(false);
  const [emailJustAdded, setEmailJustAdded] = useState("");
  const { formatMessage } = useIntl();
  const autoCompleteRef = useRef<RefType>(null);

  const onExpandButtonClick = () => setIsExpanded(!isExpanded);

  const { storageId } = MainFunctions.parseObjectId(objectId);
  const isKudoStorage =
    (storage ?? "").trim().toLowerCase() === "samples" ||
    (storage ?? "").trim().toLowerCase() === "internal";

  const currentAccountEmail =
    isKudoStorage && isOwner
      ? UserInfoStore.getUserInfo("email")
      : (UserInfoStore.getSpecificStorageInfo(storage, storageId) || {})[
          `${storage}_username`
        ] || "";

  const shareObject = useCallback(
    (sendData: FormValues) => {
      let requestData = {} as
        | { newOwner: string }
        | { share: { editor: string[]; viewer: string[] } };
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

      setIsLoading(true);
      FilesListActions.shareObject(type, objectId, requestData)
        .then(data => {
          resetForm();
          autoCompleteRef.current?.reset();
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
            setEmailJustAdded(sendData.username);
          }
        })
        .catch(err => {
          SnackbarUtils.alertError(err.message);
        })
        .finally(() => {
          setIsLoading(false);
        });
    },
    [type, objectId, updateObjectInfo]
  );

  const checkSharingPossibility = useCallback(
    (sendData: FormValues) => {
      if (storage === "internal") {
        FilesListActions.checkSharingPossibility(
          type,
          objectId,
          sendData.username
        )
          .then(data => {
            if (data.status !== "ok") {
              SnackbarUtils.alertError(data.error);
              resetForm();
            } else if (!data.possible) {
              SnackbarUtils.alertError({ id: "usedName" });
              resetForm();
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
    },
    [storage, type, objectId, resetForm]
  );

  const handleFormSubmit = (e: SyntheticEvent) => {
    handleSubmit(values => {
      const user = values.username.toLowerCase();
      if (user.length) {
        if (
          ((storage as StorageType).toLowerCase() === "internal" &&
            UserInfoStore.getUserInfo("email").indexOf(user) > -1) ||
          user === ownerName
        ) {
          SnackbarUtils.alertError({ id: "cantShareWithOwner" });
        } else {
          checkSharingPossibility(values);
        }
      } else {
        SnackbarUtils.alertError({ id: "noUserSelect" });
      }
    })(e);
  };

  return (
    <form onSubmit={handleFormSubmit}>
      <Grid container rowGap={1.5} pb={3}>
        <Grid xs={12} item>
          <SectionHeader
            header={formatMessage({ id: "invitePeople" })}
            expandButton={{ isExpanded, onExpandButtonClick }}
          />
        </Grid>

        <Grid item xs={12}>
          {storage === "onedrive" && (
            <Typography>
              <FormattedMessage
                id="onedriveSharingNotification"
                values={{ br: IntlTagValues.br }}
              />
            </Typography>
          )}
          {/* XENON-22600 */}
          {storage === "dropbox" && (
            <Typography>
              <FormattedMessage
                id="dropboxSharingNotification"
                values={{ br: IntlTagValues.br }}
              />
            </Typography>
          )}
          {/* XENON-61659 */}
          {storage === "nextcloud" && (
            <Typography>
              <FormattedMessage
                id="nextcloudSharingNotification"
                values={{ br: IntlTagValues.br }}
              />
            </Typography>
          )}
        </Grid>

        {!isDeleted &&
          !isViewOnly &&
          canManagePermissions !== false &&
          storage !== "nextcloud" && (
            <Grid xs={12} item>
              <Stack direction="row" columnGap={1.5}>
                <KudoAutocomplete
                  ref={autoCompleteRef}
                  endAdornment={
                    <PermissionRoleMenu
                      permissionRole={watch("role") as PermissionRole}
                      onRoleSelected={role => setValue("role", role)}
                    />
                  }
                  fullWidth
                  placeholder={formatMessage({ id: "email" })}
                  id="username"
                  name="username"
                  fileId={objectId}
                  inputDataComponent="email-input"
                  validationFunction={(value: string) =>
                    InputValidationFunctions.isEmail(value) &&
                    _.pluck(collaborators, "email").indexOf(value) === -1 &&
                    value.toLowerCase() !== currentAccountEmail.toLowerCase()
                  }
                  onValueSelected={selectedEmail =>
                    setValue("username", selectedEmail ?? "")
                  }
                  helpMessageFun={(value: string) => {
                    if (value.length === 0) {
                      return null;
                    }
                    if (_.pluck(collaborators, "email").indexOf(value) > -1) {
                      return { id: "userIsAlreadyCollaborator" };
                    }
                    if (!InputValidationFunctions.isEmail(value)) {
                      return { id: "emailDidntPassValidation" };
                    }
                    if (
                      value.toLowerCase() === currentAccountEmail.toLowerCase()
                    ) {
                      return { id: "cantShareWithAccountOwner" };
                    }
                    return null;
                  }}
                />

                <ResponsiveButton
                  loading={isLoading}
                  variant="contained"
                  sx={{ width: 94 }}
                  icon={<AddIcon />}
                  disabled={!watch("username")}
                  type="submit"
                  data-component="submit-button"
                >
                  <FormattedMessage id="add" />
                </ResponsiveButton>
              </Stack>
            </Grid>
          )}
        <Grid xs={12} item>
          <Collapse in={isExpanded}>
            <PermittedUserList emailJustAdded={emailJustAdded} />
          </Collapse>
        </Grid>
      </Grid>
    </form>
  );
}
