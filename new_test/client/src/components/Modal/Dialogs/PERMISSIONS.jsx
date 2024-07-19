import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import { FormattedMessage } from "react-intl";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import { Typography } from "@material-ui/core";
import FilesListActions from "../../../actions/FilesListActions";
import UserInfoStore from "../../../stores/UserInfoStore";
import ApplicationStore from "../../../stores/ApplicationStore";
import MainFunctions from "../../../libraries/MainFunctions";
import FilesListStore from "../../../stores/FilesListStore";
import StorageIcons from "../../../constants/appConstants/StorageIcons";
import DSLogo from "../../../assets/images/DS/ds_original.png";
import "../../Table/TableView.scss";
import PublicAccess from "./PermissionsDialog/PublicAccess/PublicAccess";
import InternalAccess from "./PermissionsDialog/InternalAccess/InternalAccess";
import Tabs from "./PermissionsDialog/Tabs";
import Loader from "../../Loader";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";
import DialogBody from "../DialogBody";
import SnackbarUtils from "../../Notifications/Snackbars/SnackController";
import ModalActions from "../../../actions/ModalActions";

const useStyles = makeStyles(theme => ({
  root: {
    padding: 0
  },
  rootApp: {
    paddingTop: `0 !important`
  },
  noPermissions: {
    padding: theme.spacing(3)
  },
  warningMessage: {
    fontSize: theme.typography.pxToRem(13),
    textAlign: "center"
  }
}));

export default function shareManagementDialog({
  info,
  changeDialogCaption,
  setCaptionParams
}) {
  const [storage, setStorage] = useState(
    MainFunctions.storageCodeToServiceName(
      FilesListStore.findCurrentStorage().storageType
    )
  );
  const [isUpdated, setUpdated] = useState(false);
  const [objectInfo, setInfo] = useState({});
  const [currentTab, setTab] = useState(0);
  const [captionInfo, setCaptionInfo] = useState({
    name: info.name,
    type: info.type
  });

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

  const setTabNumber = num => {
    setTab(num);
  };

  const classes = useStyles();

  if (!isUpdated || !Object.keys(objectInfo).length > 0) {
    return <Loader isModal />;
  }

  let collaborators = [];
  if (objectInfo.share) {
    collaborators = _.toArray(
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
    collaborators = _.sortBy(collaborators, user => user.name.toLowerCase());
  }

  const isFreeAccount = UserInfoStore.isFreeAccount();
  let isPublicAccessAvailable = objectInfo.permissions.canViewPublicLink;
  const { publicLinksEnabled } =
    ApplicationStore.getApplicationSetting("customization");
  if (!publicLinksEnabled || isFreeAccount === true) {
    isPublicAccessAvailable = false;
  }

  let isExportAvailable = true;
  // company's settings
  const { companiesAll, companiesAdmin } =
    ApplicationStore.getApplicationSetting("featuresEnabled");
  const userOptions = UserInfoStore.getUserInfo("options");
  const companyInfo = UserInfoStore.getUserInfo("company");
  const isAdmin = UserInfoStore.getUserInfo("isAdmin");
  // if companies are enabled and user is a part of company
  if (
    (companiesAll === true || (companiesAdmin === true && isAdmin === true)) &&
    companyInfo.id.length > 0
  ) {
    if (userOptions.sharedLinks === false) {
      isPublicAccessAvailable = false;
    }
    if (userOptions.export === false) {
      isExportAvailable = false;
    }
  }

  const isInternalAccessAvailable = objectInfo.permissions.canViewPermissions;
  const isDrawing =
    info.type === "file" &&
    UserInfoStore.findApp(
      MainFunctions.getExtensionFromName(
        objectInfo.name || objectInfo.filename
      ),
      objectInfo.mimeType
    ) === "xenon";
  const isPublicallyAccessible = MainFunctions.forceBooleanType(
    objectInfo.public
  );
  const isViewOnly = MainFunctions.forceBooleanType(objectInfo.viewOnly);
  const isDeleted = MainFunctions.forceBooleanType(objectInfo.deleted);
  let linkValue = objectInfo.link || "";

  if (isPublicallyAccessible && isViewOnly) {
    linkValue = (
      <FormattedMessage
        id="publicLinkNotSharedViaSharingLink"
        values={{ entity: info.type }}
      />
    );
  } else if (isViewOnly || isDeleted) {
    linkValue = (
      <FormattedMessage
        id="notAccessibleBySharingLink"
        values={{ entity: info.type }}
      />
    );
  }

  const product = ApplicationStore.getApplicationSetting("product");
  let productIconUrl =
    StorageIcons[
      `samples${isPublicallyAccessible ? "Active" : "InactiveGrey"}SVG`
    ];
  let storageName = storage.toLowerCase();
  let storageIcon = StorageIcons[`${storageName}ActiveSVG`];
  if (product === "DraftSight") {
    productIconUrl = DSLogo;
    storageIcon = DSLogo;
    storageName = product;
  }

  const { publicLinkInfo = {} } = objectInfo;

  const plInfoFormatted = publicLinkInfo || {}; // we need this in case publicLink is defined as null
  const cantManagePermissions =
    (isPublicAccessAvailable === false || isDrawing === false) &&
    isInternalAccessAvailable === false;
  return (
    <DialogBody
      className={clsx(
        classes.root,
        cantManagePermissions ? classes.noPermissions : null,
        location.href.includes("/app/") ? classes.rootApp : null
      )}
    >
      {cantManagePermissions ? (
        <Typography className={classes.warningMessage}>
          <FormattedMessage id="cantManagePermissions" />
        </Typography>
      ) : (
        <Tabs
          productIconUrl={productIconUrl}
          storage={storageName}
          storageIcon={storageIcon}
          passTabNumber={setTabNumber}
          tabNumber={currentTab}
          internalAccess={isInternalAccessAvailable}
          publicAccess={isPublicAccessAvailable && isDrawing}
        >
          {/* Public Access */}
          {isPublicAccessAvailable && isDrawing ? (
            <PublicAccess
              isFull={false}
              productIconUrl={productIconUrl}
              isViewOnly={isViewOnly}
              isDeleted={isDeleted}
              isPublic={objectInfo.public}
              fileId={objectInfo.id}
              link={linkValue}
              isExportAvailable={isExportAvailable}
              isExport={plInfoFormatted.export}
              endTime={plInfoFormatted.expirationTime}
              isPasswordRequired={plInfoFormatted.passwordRequired}
              updateObjectInfo={updateObjectInfo}
              name={captionInfo.name}
            />
          ) : null}
          {/* Internal Access */}
          {isInternalAccessAvailable ? (
            <InternalAccess
              type={objectInfo.type}
              storage={storage.toLowerCase()}
              parent={objectInfo.parent}
              isOwner={objectInfo.isOwner}
              objectId={objectInfo.id}
              collaborators={collaborators}
              isDeleted={isDeleted}
              isViewOnly={isViewOnly}
              isSharingAllowed={objectInfo.permissions.canManagePermissions}
              ownerId={objectInfo.ownerId}
              ownerName={objectInfo.owner}
              ownerEmail={objectInfo.ownerEmail}
              updateObjectInfo={updateObjectInfo}
            />
          ) : null}
        </Tabs>
      )}
    </DialogBody>
  );
}

shareManagementDialog.propTypes = {
  info: PropTypes.shape({
    id: PropTypes.string.isRequired,
    type: PropTypes.oneOf(["file", "folder"]).isRequired,
    export: PropTypes.bool
  }),
  changeDialogCaption: PropTypes.func.isRequired,
  setCaptionParams: PropTypes.func.isRequired
};
