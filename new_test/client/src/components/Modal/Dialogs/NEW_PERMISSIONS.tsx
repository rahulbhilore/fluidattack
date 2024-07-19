import { useMediaQuery, useTheme } from "@mui/material";
import React, { ReactElement, useEffect, useMemo, useState } from "react";
import { FormattedMessage } from "react-intl";
import _ from "underscore";
import FilesListActions from "../../../actions/FilesListActions";
import ModalActions from "../../../actions/ModalActions";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import PermissionsCheck from "../../../constants/appConstants/PermissionsCheck";
import MainFunctions from "../../../libraries/MainFunctions";
import ApplicationStore from "../../../stores/ApplicationStore";
import FilesListStore from "../../../stores/FilesListStore";
import UserInfoStore from "../../../stores/UserInfoStore";
import { StorageType } from "../../../types/StorageTypes";
import Loader from "../../Loader";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import PermissionsDialog from "./NewPermissionsDialog/PermissionsDialog";
import {
  PermissionsDialogContext,
  PermissionsDialogContextType
} from "./NewPermissionsDialog/PermissionsDialogContext";
import {
  Collaborator,
  ObjectInfo,
  PermissionRole
} from "./NewPermissionsDialog/types";

type Info = {
  id: string;
  type: "file" | "folder";
  export?: boolean;
  name: string;
};

type PropType = {
  info: Info;
  changeDialogCaption: (element: ReactElement) => void;
  setCaptionParams: (params: {
    enforceTooltip: boolean;
    fullCaption: ReactElement | null;
  }) => void;
};

export default function shareManagementDialog({
  info,
  changeDialogCaption,
  setCaptionParams
}: PropType) {
  // States
  const [objectInfo, setInfo] = useState<ObjectInfo>({} as ObjectInfo);
  const [isUpdated, setUpdated] = useState(false);
  const [isExpanded, setIsExpanded] = useState(true);
  const [captionInfo, setCaptionInfo] = useState({
    name: info.name,
    type: info.type
  });
  const [storage, setStorage] = useState(
    MainFunctions.storageCodeToServiceName(
      FilesListStore.findCurrentStorage().storageType
    )
  );
  const [isPublic, setIsPublic] = useState(objectInfo?.public);

  useEffect(() => {
    setIsPublic(objectInfo?.public);
  }, [objectInfo?.public]);

  // Assignments
  const { companiesAll, companiesAdmin } =
    ApplicationStore.getApplicationSetting("featuresEnabled");
  const userOptions = UserInfoStore.getUserInfo("options");
  const companyInfo = UserInfoStore.getUserInfo("company");
  const isAdmin = UserInfoStore.getUserInfo("isAdmin");
  const isFreeAccount = UserInfoStore.isFreeAccount();
  const { publicLinksEnabled } =
    ApplicationStore.getApplicationSetting("customization");

  const isPublicallyAccessible = useMemo(
    () => MainFunctions.forceBooleanType(objectInfo.public),
    [objectInfo]
  );
  const isDeleted = useMemo(
    () => MainFunctions.forceBooleanType(objectInfo.deleted),
    [objectInfo]
  );
  const isViewOnly = useMemo(
    () => MainFunctions.forceBooleanType(objectInfo.viewOnly),
    [objectInfo]
  );

  const isPublicAccessAvailable = useMemo(() => {
    let access = Boolean(objectInfo?.permissions?.canViewPublicLink);
    if (!publicLinksEnabled || isFreeAccount === true) {
      access = false;
    }
    // if companies are enabled and user is a part of company
    if (
      (companiesAll === true ||
        (companiesAdmin === true && isAdmin === true)) &&
      companyInfo.id.length > 0
    ) {
      if (userOptions.sharedLinks === false) {
        access = false;
      }
    }
    return access;
  }, [
    objectInfo,
    publicLinksEnabled,
    isFreeAccount,
    companiesAll,
    companiesAdmin,
    isAdmin
  ]);

  const availableRoles = PermissionsCheck.getAvailableRoles(
    storage.toUpperCase(),
    objectInfo.type,
    objectInfo.parent,
    objectInfo.isOwner
  );

  const isExportAvailable = useMemo(
    () => userOptions?.export === true,
    [userOptions]
  );

  const isInternalAccessAvailable = objectInfo?.permissions?.canViewPermissions;
  const isDrawing = useMemo(
    () =>
      info.type === "file" &&
      UserInfoStore.findApp(
        MainFunctions.getExtensionFromName(
          (objectInfo?.name ?? "") || (objectInfo?.filename ?? "")
        ),
        objectInfo.mimeType
      ) === "xenon",
    [objectInfo]
  );

  const linkValue = useMemo(() => {
    let value: string | ReactElement = objectInfo.link ?? "";
    if (isPublicallyAccessible && isViewOnly) {
      value = (
        <FormattedMessage
          id="publicLinkNotSharedViaSharingLink"
          values={{ entity: info.type }}
        />
      );
    } else if (isViewOnly || isDeleted) {
      value = (
        <FormattedMessage
          id="notAccessibleBySharingLink"
          values={{ entity: info.type }}
        />
      );
    }
    return value;
  }, [objectInfo, isPublicallyAccessible, isViewOnly, isDeleted, info]);
  const [generatedLink, setGeneratedLink] = useState<string | ReactElement>(
    linkValue
  );

  // Plain hooks
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down("md"));

  const updateObjectInfo = (shouldSpinnerBeShowed = false) => {
    const { id, type } = info;
    if (!id) return;

    if (shouldSpinnerBeShowed) {
      setUpdated(false);
    }

    FilesListActions.getObjectInfo(id, type, {})
      .then(data => {
        const newInfo = _.defaults(data, info);
        const { storageType } = MainFunctions.parseObjectId(newInfo.id);

        let newStorage = storageType;
        if (newStorage) {
          newStorage = MainFunctions.storageCodeToServiceName(newStorage);
        } else {
          newStorage = MainFunctions.storageCodeToServiceName(
            FilesListStore.findCurrentStorage().storageType
          );
        }

        if (objectInfo.storage !== newStorage) {
          setStorage(newStorage);
        }
        const fullName = newInfo.name || newInfo.filename || newInfo.foldername;
        setCaptionInfo({ name: fullName, type });

        setUpdated(true);
        setInfo(newInfo);
      })
      .catch(err => {
        SnackbarUtils.alertError(err.text);
        ModalActions.hide();
      });
  };

  const createPublicLink = () => {
    setIsPublic(true);
  };

  const deletePublicLink = () => {
    ModalActions.removePublicLink(objectInfo.id, captionInfo.name);
  };

  useEffect(updateObjectInfo, [info.id]);

  useEffect(() => {
    const { name: fullName = "", type } = captionInfo;
    if (fullName?.length > 0) {
      const shrinkedName = MainFunctions.shrinkNameWithExtension(fullName);
      changeDialogCaption(
        <FormattedMessage
          id="permissionsFor"
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
            id="permissionsFor"
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
  const collaborators = useMemo(() => {
    let list = [];
    if (objectInfo.share) {
      list = _.toArray(
        _.mapObject(objectInfo.share.editor, val =>
          _.extend(val, { role: "Editor" })
        )
      ).concat(
        _.toArray(
          _.mapObject(objectInfo.share.viewer, val =>
            _.extend(val, { role: "Viewer" })
          )
        )
      );
      list = _.sortBy(list, user => user.name.toLowerCase());
    }
    return list as Array<
      Omit<Collaborator, "collaboratorRole"> & { role: PermissionRole }
    >;
  }, [objectInfo]);

  const contextValue = useMemo<PermissionsDialogContextType>(
    () => ({
      isDrawing,
      isMobile,
      isInternalAccessAvailable,
      publicAccess: {
        createPublicLink,
        deletePublicLink,
        fileId: objectInfo.id,
        generatedLink,
        isDeleted,
        isPublic,
        isExport: objectInfo?.publicLinkInfo?.export ?? false,
        isPasswordRequired: objectInfo?.publicLinkInfo?.passwordRequired,
        isPublicallyAccessible,
        isPublicAccessAvailable,
        isExportAvailable,
        isViewOnly,
        link: linkValue,
        endTime: objectInfo?.publicLinkInfo?.expirationTime,
        setGeneratedLink,
        updateObjectInfo
      },
      invitePeople: {
        availableRoles,
        isExpanded,
        setIsExpanded,
        collaborators,
        objectInfo,
        storage: storage as StorageType,
        updateObjectInfo
      }
    }),
    [
      availableRoles,
      collaborators,
      generatedLink,
      isDrawing,
      isInternalAccessAvailable,
      isExpanded,
      isDeleted,
      isMobile,
      isPublic,
      isPublicAccessAvailable,
      isPublicallyAccessible,
      isViewOnly,
      linkValue,
      objectInfo,
      setIsExpanded,
      setGeneratedLink,
      storage,
      updateObjectInfo
    ]
  );

  if (!isUpdated || Object.keys(objectInfo).length === 0) {
    return <Loader isModal />;
  }

  return (
    <PermissionsDialogContext.Provider value={contextValue}>
      <PermissionsDialog />
    </PermissionsDialogContext.Provider>
  );
}
