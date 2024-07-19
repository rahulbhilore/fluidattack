import React, { useEffect, useState, useCallback } from "react";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import { Typography } from "@material-ui/core";
import FilesListActions from "../../../actions/FilesListActions";
import UserInfoStore from "../../../stores/UserInfoStore";
import MainFunctions from "../../../libraries/MainFunctions";
import "../../Table/TableView.scss";
import Loader from "../../Loader";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import DialogBody from "../DialogBody";
import KudoButton from "../../Inputs/KudoButton/KudoButton";
import DialogFooter from "../DialogFooter";
import KudoForm from "../../Inputs/KudoForm/KudoForm";
import Requests from "../../../utils/Requests";
import * as RequestsMethods from "../../../constants/appConstants/RequestsMethods";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import ModalActions from "../../../actions/ModalActions";

const useStyles = makeStyles(() => ({
  text: {
    fontSize: ".9rem"
  }
}));

type RemovePermissionsDialogProps = {
  info: {
    id: string;
    name: string;
    type: "file" | "folder";
  };
  changeDialogCaption: (captionElement: React.ReactElement) => void;
  setCaptionParams: (options: {
    enforceTooltip: boolean;
    fullCaption: React.ReactElement | null;
  }) => void;
};

type CollaboratorType = {
  email: string;
  _id: string;
  name: string;
};

type CollaboratorRole = "edit" | "view";

type ShareObjectInfoType = {
  id: string;
  share: {
    editor: CollaboratorType[];
    viewer: CollaboratorType[];
  };
};

type StorageNames = ReturnType<typeof MainFunctions.storageCodeToServiceName>;
type StorageConfiguration<T extends StorageNames> = Record<
  `${T}_username` | `${T}_id`,
  string
> & { rootFolderId: string };
type StoragesInfo = {
  [key in StorageNames]?: Array<StorageConfiguration<key>>;
};

export default function removePermissionsDialog({
  info,
  changeDialogCaption,
  setCaptionParams
}: RemovePermissionsDialogProps) {
  const [isUpdated, setUpdated] = useState(false);
  const [objectInfo, setInfo] = useState<ShareObjectInfoType | null>(null);
  const [captionInfo, setCaptionInfo] = useState({
    name: info.name,
    type: info.type
  });
  const [share, setShare] = useState<CollaboratorType | null>(null);
  const [role, setRole] = useState<CollaboratorRole | null>(null);

  const handleRemove = useCallback(() => {
    if (!objectInfo) return;
    const deshare =
      share && role
        ? [{ email: share.email, userId: share._id || share.email, role }]
        : [{ tryDelete: true }];
    setUpdated(false);
    Requests.sendGenericRequest(
      `/${info.type}s/${objectInfo.id}`,
      RequestsMethods.PUT,
      Requests.getDefaultUserHeaders(),
      { deshare },
      ["*"]
    )
      .then(() => {
        SnackbarUtils.alertOk({
          id: "removePermissionSuccess",
          type: <FormattedMessage id={info.type} />
        });
        FilesListActions.deleteEntity(objectInfo.id);
        ModalActions.hide();
      })
      .catch(err => {
        setUpdated(true);
        if (err.data?.message) {
          if (err.data?.errorId && err.data?.errorId === "GD2") {
            SnackbarUtils.alertInfo(err.data?.message);
          } else {
            SnackbarUtils.alertError(err.data?.message);
          }
        } else {
          SnackbarUtils.alertError({
            id: "removePermissionError",
            type: <FormattedMessage id={info.type} />
          });
        }
      });
  }, [share, role]);

  const updateObjectInfo = (shouldSpinnerBeShowed = false) => {
    const { id, type } = info;

    if (!id) return;

    if (shouldSpinnerBeShowed) {
      setUpdated(false);
    }

    FilesListActions.getObjectInfo(id, type, {}).then(data => {
      const newInfo = _.defaults(data, info);

      const fullName = newInfo.name || newInfo.filename || newInfo.foldername;
      setCaptionInfo({ name: fullName, type });

      setUpdated(true);
      setInfo(newInfo);
    });
  };

  useEffect(updateObjectInfo, [info.id]);

  useEffect(() => {
    const { name: fullName = "", type } = captionInfo;
    if (fullName?.length > 0) {
      const shrinkedName = MainFunctions.shrinkNameWithExtension(fullName);
      changeDialogCaption(
        <FormattedMessage
          id="removePermissionFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: shrinkedName,
            objectType: type
          }}
        />
      );
      const enforceTooltip = shrinkedName.length !== fullName.length;
      setCaptionParams({
        enforceTooltip,
        fullCaption: enforceTooltip ? (
          <FormattedMessage
            id="removePermissionFor"
            values={{
              strong: IntlTagValues.strong,
              objectName: fullName,
              objectType: type
            }}
          />
        ) : null
      });
    }
  }, [captionInfo]);

  const classes = useStyles();
  useEffect(() => {
    if (!objectInfo) return;
    const { storageId, storageType } = MainFunctions.parseObjectId(
      objectInfo.id
    );
    const storageName = MainFunctions.storageCodeToServiceName(storageType);
    const specificStorageInfo = (
      UserInfoStore.getStoragesInfo() as StoragesInfo
    )[storageName];
    if (!specificStorageInfo) return;
    const storageInfo = (
      specificStorageInfo as Array<StorageConfiguration<typeof storageName>>
    ).find(storage => storage[`${storageName}_id`] === storageId);
    if (!storageInfo) return;
    let storageUsername = storageInfo[`${storageName}_username`];

    if (storageName === "nextcloud") {
      [storageUsername] = storageUsername.split(" at ");
    }
    const currentUserId = UserInfoStore.getUserInfo("id");
    const { editor: editors = [], viewer: viewers = [] } = objectInfo.share;
    let newShare: CollaboratorType | undefined = editors.find(
      e =>
        (e.email === storageUsername || e.name === storageUsername) &&
        (storageName !== "samples" || !e._id || e._id === currentUserId)
    );
    let newRole: CollaboratorRole | null = newShare ? "edit" : null;
    if (!newShare) {
      newRole = "view";
      newShare = viewers.find(
        w =>
          (w.email === storageUsername || w.name === storageUsername) &&
          (storageName !== "samples" || !w._id || w._id === currentUserId)
      );
    }
    setShare(newShare || null);
    setRole(newRole);
  }, [objectInfo]);

  if (!isUpdated || !objectInfo) {
    return <Loader isModal />;
  }

  return (
    <>
      <DialogBody>
        <KudoForm id="removeFileShare">
          <Typography className={classes.text}>
            <FormattedMessage
              id="removePermissionQuestion"
              values={{
                strong: IntlTagValues.strong,
                role,
                objectName: info.name,
                objectType: info.type
              }}
            />
          </Typography>
        </KudoForm>
      </DialogBody>
      <DialogFooter>
        <KudoButton
          formId="removeFileShare"
          isDisabled={false}
          onClick={handleRemove}
        >
          <FormattedMessage id="remove" />
        </KudoButton>
      </DialogFooter>
    </>
  );
}
