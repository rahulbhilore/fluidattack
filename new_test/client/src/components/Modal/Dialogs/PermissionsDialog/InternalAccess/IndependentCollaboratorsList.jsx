import React from "react";
import PropTypes from "prop-types";
import Grid from "@material-ui/core/Grid";
import IndependentCollaborator from "./IndependentCollaborator";
import MainFunctions from "../../../../../libraries/MainFunctions";

export default function IndependentCollaboratorsList({
  collaborators,
  ownerId,
  sharingOptions,
  isViewOnly,
  ownerName,
  isOwner,
  isSharingAllowed,
  updatePermission,
  removePermission
}) {
  return (
    <Grid item xs={12}>
      <IndependentCollaborator
        key={ownerId || MainFunctions.guid()}
        isUpdateAllowed={false}
        allowedRoles={sharingOptions}
        username={ownerName}
        currentRole="owner"
        isViewOnly={isViewOnly}
        isOwner={isOwner}
        updatePermission={updatePermission}
        removePermission={removePermission}
      />
      {collaborators.map(collaborator => (
        <IndependentCollaborator
          key={
            collaborator.userId || collaborator.email || MainFunctions.guid()
          }
          isUpdateAllowed={isSharingAllowed}
          allowedRoles={sharingOptions}
          username={collaborator.name}
          userId={collaborator.userId || ""}
          email={collaborator.email || ""}
          currentRole={collaborator.mode}
          isViewOnly={isViewOnly}
          isOwner={isOwner}
          updatePermission={updatePermission}
          removePermission={removePermission}
        />
      ))}
    </Grid>
  );
}

IndependentCollaboratorsList.propTypes = {
  collaborators: PropTypes.arrayOf(
    PropTypes.shape({
      userId: PropTypes.string,
      email: PropTypes.string,
      name: PropTypes.string,
      mode: PropTypes.string
    })
  ),
  ownerId: PropTypes.string,
  sharingOptions: PropTypes.shape({ [PropTypes.string]: PropTypes.string })
    .isRequired,
  isViewOnly: PropTypes.bool,
  ownerName: PropTypes.string,
  isOwner: PropTypes.bool,
  isSharingAllowed: PropTypes.bool,
  updatePermission: PropTypes.func.isRequired,
  removePermission: PropTypes.func.isRequired
};

IndependentCollaboratorsList.defaultProps = {
  collaborators: [],
  ownerId: "",
  isViewOnly: true,
  ownerName: "",
  isOwner: false,
  isSharingAllowed: false
};
