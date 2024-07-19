import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import Collaborator from "./Collaborator";
import MainFunctions from "../../../../../libraries/MainFunctions";

export default function CollaboratorsList({
  collaborators,
  ownerId,
  objectId,
  type,
  sharingOptions,
  isViewOnly,
  isDeleted,
  ownerName,
  ownerEmail,
  storageConfig,
  isOwner,
  updateObjectInfo,
  isSharingAllowed
}) {
  return (
    <Grid item xs={12}>
      <Collaborator
        key={ownerId || MainFunctions.guid()}
        isUpdateAllowed={false}
        objectId={objectId}
        objectType={type}
        allowedRoles={sharingOptions}
        username={ownerName}
        userId={ownerId || ""}
        email={ownerEmail || ""}
        currentRole="owner"
        isViewOnly={isViewOnly}
        isDeleted={isDeleted}
        isInherited={false}
        storageConfig={storageConfig}
        isOwner={isOwner}
        updateDialog={updateObjectInfo}
      />
      {collaborators.map(collaborator => {
        let finalViewOnly = isViewOnly;
        if ("canModify" in collaborator && !collaborator?.canModify) {
          finalViewOnly = true;
        }
        const isInherited = collaborator?.inherited === true;
        return (
          <Collaborator
            key={collaborator._id || collaborator.email || MainFunctions.guid()}
            isUpdateAllowed={isSharingAllowed && !isInherited}
            objectId={objectId}
            objectType={type}
            allowedRoles={sharingOptions}
            username={collaborator.name}
            userId={collaborator._id || ""}
            email={collaborator.email || ""}
            currentRole={collaborator.role}
            isViewOnly={finalViewOnly}
            isDeleted={isDeleted}
            isInherited={isInherited}
            storageConfig={storageConfig}
            isOwner={isOwner}
            updateDialog={updateObjectInfo}
          />
        );
      })}
    </Grid>
  );
}

CollaboratorsList.propTypes = {
  collaborators: PropTypes.arrayOf(
    PropTypes.shape({
      _id: PropTypes.string,
      email: PropTypes.string
    })
  ),
  ownerId: PropTypes.string,
  objectId: PropTypes.string.isRequired,
  type: PropTypes.string.isRequired,
  sharingOptions: PropTypes.shape({ [PropTypes.string]: PropTypes.string })
    .isRequired,
  isViewOnly: PropTypes.bool,
  isDeleted: PropTypes.bool,
  ownerName: PropTypes.string,
  ownerEmail: PropTypes.string,
  storageConfig: PropTypes.shape({
    share: PropTypes.shape({
      fileUpdatePermissions: PropTypes.bool,
      folderUpdatePermissions: PropTypes.bool
    })
  }),
  isOwner: PropTypes.bool,
  updateObjectInfo: PropTypes.func.isRequired,
  isSharingAllowed: PropTypes.bool
};

CollaboratorsList.defaultProps = {
  collaborators: [],
  storageConfig: {
    share: {
      fileUpdatePermissions: false,
      folderUpdatePermissions: false
    }
  },
  ownerId: "",
  isViewOnly: true,
  isDeleted: false,
  ownerName: "",
  ownerEmail: "",
  isOwner: false,
  isSharingAllowed: false
};
