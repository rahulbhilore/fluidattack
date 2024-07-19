import React from "react";
import PropTypes from "prop-types";
import MenuItem from "./MenuItem";
import AdminActions from "../../actions/AdminActions";
import ModalActions from "../../actions/ModalActions";
import SnackbarUtils from "../Notifications/Snackbars/SnackController";
import { RenderFlags, RenderFlagsParams } from "./ContextMenu";

export type UserEntity = {
  _id: string;
  enabled: boolean;
  isAdmin: boolean;
  compliance: {
    status: string;
  };
  graebertId: string;
  deleteId: string;
};

export function getRenderFlags({
  ids,
  infoProvider
}: RenderFlagsParams): RenderFlags {
  if (ids.length !== 1) {
    return {
      isNeedToRenderMenu: false,
      entities: [],
      type: "users"
    };
  }
  const entities = ids
    .map(id => infoProvider(id))
    .filter(v => v !== null) as Array<UserEntity>;
  return {
    isNeedToRenderMenu: true,
    entities,
    type: "users"
  };
}

export default function Users({ entities }: { entities: Array<UserEntity> }) {
  // just to be sure
  if (entities.length !== 1) return null;

  const objectInfo = entities[0];

  const toggleStatus = () => {
    AdminActions.toggleAccess(objectInfo._id, !objectInfo.enabled);
  };

  const toggleRole = () => {
    AdminActions.toggleRole(objectInfo._id, !!objectInfo.isAdmin);
  };

  const getComplianceItemCaption = () => {
    const { compliance } = objectInfo;
    if (
      compliance &&
      Object.prototype.hasOwnProperty.call(compliance, "status")
    ) {
      if (compliance.status.includes("OVERRIDDEN")) {
        return "complianceClear";
      }
      return "complianceOverride";
    }
    return "complianceOverride";
  };

  const overrideCompliance = () => {
    const { compliance, _id } = objectInfo;
    let value;
    if (compliance && compliance.status) {
      if (compliance.status.includes("CLEARED") || compliance.status === "")
        value = "override";
      if (compliance.status.includes("OVERRIDDEN")) value = "clear";
    } else {
      value = "override";
    }
    AdminActions.complianceOverride(_id, value);
  };

  const changeUserOptions = () => {
    ModalActions.changeUserOptions(objectInfo);
  };
  const changeThumbnailsOptions = () => {
    ModalActions.changeThumbnailsOptions(objectInfo);
  };

  const copyString = async (str: string) => {
    try {
      await navigator.clipboard.writeText(str);
      SnackbarUtils.alertInfo({ id: "copiedToClipboard" });
    } catch (ex) {
      // eslint-disable-next-line no-console
      console.error(`[COPY] Couldn't copy to clipboard`, ex);
      SnackbarUtils.alertError({ id: "cannotCopyToClipboard" });
    }
  };

  const copyUserId = () => {
    copyString(objectInfo._id);
  };

  const copyGraebertId = () => {
    copyString(objectInfo.graebertId);
  };

  const deleteUser = () => {
    ModalActions.deleteUser(objectInfo._id, objectInfo.deleteId);
  };

  let optionsList: Array<React.ReactNode> = [];
  if (entities.length === 1) {
    optionsList = [
      <MenuItem
        id="contextMenuChangeStatus"
        onClick={toggleStatus}
        caption={objectInfo.enabled === true ? "disable" : "enable"}
        key="contextMenuChangeStatus"
        dataComponent="change-status"
      />,
      <MenuItem
        id="contextMenuChangeRole"
        onClick={toggleRole}
        caption={objectInfo.isAdmin === true ? "removeAdmin" : "makeAdmin"}
        key="contextMenuChangeRole"
        dataComponent="change-role"
      />,
      <MenuItem
        id="contextMenuChangeUserOptions"
        onClick={changeUserOptions}
        caption="changeOptions"
        key="contextMenuChangeUserOptions"
        dataComponent="user-options"
      />,
      <MenuItem
        id="contextMenuChangeUserThumbnailsOptions"
        onClick={changeThumbnailsOptions}
        caption="changeThumbnailsOptions"
        key="contextMenuChangeUserThumbnailsOptions"
        dataComponent="user-thumbnails-options"
      />,
      <MenuItem
        id="contextMenuCopyUserId"
        onClick={copyUserId}
        caption="copyUserId"
        key="contextMenuCopyUserId"
        dataComponent="copy-user-id"
      />,
      objectInfo.graebertId ? (
        <MenuItem
          id="contextMenuCopyGraebertId"
          onClick={copyGraebertId}
          caption="copyGraebertId"
          key="contextMenuCopyGraebertId"
          dataComponent="copy-graebert-id"
        />
      ) : null,
      <MenuItem
        id="contextMenuDeleteUser"
        onClick={deleteUser}
        caption="deleteUser"
        key="contextMenuDeleteUser"
        dataComponent="delete-user"
      />,
      <MenuItem
        id="contextMenuOverrideCompliance"
        onClick={overrideCompliance}
        caption={getComplianceItemCaption()}
        key="contextMenuOverrideCompliance"
        dataComponent="overrider"
      />
    ];
  }
  return optionsList;
}

Users.propTypes = {
  entities: PropTypes.arrayOf(
    PropTypes.shape({
      _id: PropTypes.string,
      graebertId: PropTypes.string,
      deleteId: PropTypes.string,
      enabled: PropTypes.bool,
      isAdmin: PropTypes.bool,
      compliance: PropTypes.shape({
        status: PropTypes.string
      })
    })
  ).isRequired
};
