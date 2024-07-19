import React, { useEffect, useState } from "react";
import PropTypes from "prop-types";
import _ from "underscore";
import makeStyles from "@material-ui/core/styles/makeStyles";
import clsx from "clsx";
import { FormattedMessage, useIntl } from "react-intl";
import Loader from "../../Loader";
import DialogBody from "../DialogBody";
import BlocksActions from "../../../actions/BlocksActions";
import { BLOCK, LIBRARY } from "../../../stores/BlocksStore";
import AssetAccess from "./PermissionsDialog/InternalAccess/AssetAccess";
import userInfoStore from "../../../stores/UserInfoStore";
import ModalActions from "../../../actions/ModalActions";
import SnackController from "../../Notifications/Snackbars/SnackController";
import MainFunctions from "../../../libraries/MainFunctions";
import * as IntlTagValues from "../../../constants/appConstants/IntlTagValues";

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

export default function shareBlock({
  info,
  changeDialogCaption,
  setCaptionParams
}) {
  const [isUpdated, setUpdated] = useState(false);
  const [objectInfo, setInfo] = useState({});

  const [captionInfo, setCaptionInfo] = useState({
    name: info.name,
    type: info.type
  });

  const updateObjectInfo = (shouldSpinnerBeShowed = false) => {
    const { id, libraryId, type } = info;

    if (!id) return Promise.reject(new Error("No id provided"));

    if (shouldSpinnerBeShowed) {
      setUpdated(false);
    }

    const isLibrary = type === LIBRARY;
    let updatePromise;
    if (isLibrary) {
      updatePromise = BlocksActions.getBlockLibraryInfo(id);
    } else {
      updatePromise = BlocksActions.getBlockInfo(id, libraryId);
    }
    return new Promise((resolve, reject) => {
      updatePromise
        .then(response => {
          const newInfo = _.defaults(response.data, info);
          setUpdated(true);
          const fullName =
            newInfo.name || newInfo.filename || newInfo.foldername;
          setCaptionInfo({ name: fullName, type });
          setInfo(newInfo);
          resolve();
        })
        .catch(err => {
          reject(new Error(err.text));
        });
    });
  };

  useEffect(() => {
    updateObjectInfo().catch(err => {
      SnackController.alertError(err.message);
      ModalActions.hide();
    });
  }, [info.id]);

  const { formatMessage } = useIntl();

  useEffect(() => {
    const { name: fullName = "", type } = captionInfo;
    const isLibrary = type === LIBRARY;
    if (fullName?.length > 0) {
      const shrinkedName = MainFunctions.shrinkNameWithExtension(fullName);
      changeDialogCaption(
        <FormattedMessage
          id="permissionsFor"
          values={{
            strong: IntlTagValues.strong,
            objectName: shrinkedName,
            objectType: formatMessage({ id: isLibrary ? "library" : "block" })
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
              objectType: formatMessage({ id: isLibrary ? "library" : "block" })
            }}
          />
        ) : null
      });
    }
  }, [captionInfo]);

  const classes = useStyles();

  if (!isUpdated || !Object.keys(objectInfo).length > 0) {
    return <Loader isModal />;
  }

  const shareAsset = shareData => {
    const { id, libraryId, type } = info;
    const isLibrary = type === LIBRARY;
    const formattedShareData = {
      emails: [{ id: shareData.username, mode: shareData.role }]
    };
    let sharePromise;
    if (isLibrary) {
      sharePromise = BlocksActions.shareBlockLibrary(id, formattedShareData);
    } else {
      sharePromise = BlocksActions.shareBlock(
        id,
        libraryId,
        formattedShareData
      );
    }
    return new Promise(resolve => {
      sharePromise.then(() => {
        updateObjectInfo()
          .then(() => {
            resolve();
          })
          .catch(err => {
            SnackController.alertError(err.message);
            resolve();
          });
      });
    });
  };

  const removePermission = email => {
    const { id, libraryId, type } = info;
    const isLibrary = type === LIBRARY;
    const formattedShareData = {
      emails: [email]
    };
    let unsharePromise;
    if (isLibrary) {
      unsharePromise = BlocksActions.removeBlockLibraryShare(
        id,
        formattedShareData
      );
    } else {
      unsharePromise = BlocksActions.removeBlockShare(
        id,
        libraryId,
        formattedShareData
      );
    }
    const currentUserEmail = userInfoStore.getUserInfo("email");
    const isSelfUnshare = email === currentUserEmail;
    return new Promise(resolve => {
      unsharePromise.then(() => {
        if (isSelfUnshare) {
          ModalActions.hide();
          if (isLibrary) {
            const librariesPromises = [];
            const isPartOfCompany = userInfoStore.getUserInfo("company")?.id;
            // user libraries
            librariesPromises.push(
              BlocksActions.getBlockLibraries(userInfoStore.getUserInfo("id"))
            );
            // public libraries
            librariesPromises.push(
              BlocksActions.getBlockLibraries(null, "PUBLIC")
            );
            // org libraries
            if (isPartOfCompany) {
              librariesPromises.push(
                BlocksActions.getBlockLibraries(
                  userInfoStore.getUserInfo("company")?.id,
                  "ORG"
                )
              );
            }
            Promise.all(librariesPromises)
              .then(() => {
                resolve();
              })
              .catch(err => {
                SnackController.alertError(err.message);
              });
          } else {
            BlocksActions.getBlockLibraryContent(libraryId)
              .then(() => {
                resolve();
              })
              .catch(err => {
                SnackController.alertError(err.message);
              });
          }
        } else {
          updateObjectInfo()
            .then(() => {
              resolve();
            })
            .catch(err => {
              SnackController.alertError(err.message);
            });
        }
      });
    });
  };

  const updatePermission = (username, role) =>
    new Promise(resolve => {
      shareAsset({ username, role }).then(() => {
        resolve();
      });
    });

  const checkIsOwner = entity => {
    const { ownerId, ownerType } = entity;
    let isOwner = false;
    if (ownerType === "USER" && ownerId === userInfoStore.getUserInfo("id")) {
      isOwner = true;
    } else if (ownerType === "ORG") {
      isOwner = userInfoStore.getUserInfo("company")?.isAdmin;
    } else if (ownerType === "PUBLIC") {
      isOwner = false;
    }
    return isOwner;
  };

  const checkIsViewOnly = entity => {
    const isOwner = checkIsOwner(entity);
    if (isOwner) return false;
    const { ownerType } = entity;
    if (entity.isSharedBlocksCollection === true) {
      return true;
    }
    // public libraries shouldn't be changed manually
    if (ownerType === "PUBLIC") {
      return true;
    }

    let isViewOnly = true;
    const { shares = [] } = entity;
    if (shares.length > 0) {
      const shareInfo = shares.find(
        ({ userId }) => userId === userInfoStore.getUserInfo("id")
      );
      if (shareInfo) {
        isViewOnly = shareInfo.mode !== "editor";
      }
    }
    return isViewOnly;
  };

  const isOwner = checkIsOwner(objectInfo);
  const isViewOnly = checkIsViewOnly(objectInfo);

  return (
    <DialogBody
      className={clsx(
        classes.root,
        location.href.includes("/app/") ? classes.rootApp : null
      )}
    >
      {/* Internal Access */}
      <AssetAccess
        scope="resources"
        isOwner={isOwner}
        objectId={objectInfo.id}
        collaborators={objectInfo.shares || []}
        isViewOnly={isViewOnly}
        isSharingAllowed={!isViewOnly}
        ownerId={objectInfo.ownerId}
        ownerName={objectInfo.ownerName}
        updateObjectInfo={updateObjectInfo}
        shareAsset={shareAsset}
        removePermission={removePermission}
        updatePermission={updatePermission}
        roles={[
          { value: "editor", label: "editor" },
          { value: "viewer", label: "viewer" }
        ]}
      />
    </DialogBody>
  );
}

shareBlock.propTypes = {
  info: PropTypes.shape({
    id: PropTypes.string.isRequired,
    type: PropTypes.oneOf([BLOCK, LIBRARY]).isRequired,
    name: PropTypes.string.isRequired,
    libraryId: PropTypes.string
  })
};
