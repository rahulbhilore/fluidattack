import { PermissionRole } from "../../components/Modal/Dialogs/NewPermissionsDialog/types";

const PermissionsCheck = {
  /**
   * @description returns an array of available roles for sharing
   * @param storage {String}
   * @param type {String}
   * @param parent {String}
   * @param isOwner {Boolean}
   * @returns {Array}
   */
  getAvailableRoles(
    storage: string,
    type = "",
    parent = "",
    isOwner = false
  ): Array<PermissionRole> {
    switch (storage) {
      case "GDRIVE":
      case "BOX":
      case "ONSHAPE":
      case "ONSHAPEDEV":
      case "ONSHAPESTAGING":
      case "ONEDRIVE":
      case "ONEDRIVEBUSINESS":
      case "SHAREPOINT":
      case "HANCOM":
      case "HANCOMSTG":
      case "NEXTCLOUD":
        return ["editor", "viewer"];
      case "DROPBOX":
        // files can't be shared with editing rights in DB - XENON-16741
        if (type === "file") {
          return ["viewer"];
        }
        return ["editor", "viewer"];
      case "TRIMBLE":
        if (parent.endsWith("+-1")) {
          return ["editor"];
        }
        return ["editor", "viewer"];
      case "INTERNAL":
        if (isOwner) {
          return ["editor", "viewer", "owner"];
        }
        return ["editor", "viewer"];
      case "SAMPLES":
        if (isOwner) {
          return ["editor", "viewer", "owner"];
        }
        return ["editor", "viewer"];
      default:
        return [];
    }
  }
};
export default PermissionsCheck;
