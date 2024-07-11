package com.graebert.storage.integration.objects;

import com.google.gson.Gson;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ObjectPermissions implements JsonConvertable {

  private Role role;

  private final Map<AccessType, Boolean> accessTypes =
      Arrays.stream(AccessType.values()).collect(Collectors.toMap(v -> v, v -> false));

  public ObjectPermissions() {}

  public ObjectPermissions(boolean isOwner, boolean isViewOnly) {
    if (isOwner) {
      this.role = Role.OWNER;
    } else if (!isViewOnly) {
      this.role = Role.EDITOR;
    } else {
      this.role = Role.VIEWER;
    }
  }

  /**
   * Convert the permissions object to JsonObject
   *
   * @return json object for permissions
   */
  public JsonObject toJson() {
    Gson gson = new Gson();
    String json = gson.toJson(accessTypes);
    return new JsonObject(json);
  }

  /**
   * Update all the permissions together
   *
   * @param newValue new boolean for all permissions
   * @return this
   */
  public ObjectPermissions setAllTo(boolean newValue) {
    accessTypes.replaceAll((t, v) -> newValue);
    return this;
  }

  /**
   * Update all the selected/required permissions together
   *
   * @param accessToUpdate list of permissions to update
   * @param newValue       newValue new boolean for selected permissions
   * @return this
   */
  public ObjectPermissions setBatchTo(List<AccessType> accessToUpdate, boolean newValue) {
    for (AccessType accessType : accessToUpdate) {
      setPermissionAccess(accessType, newValue);
    }
    return this;
  }

  /**
   * Update single permission
   *
   * @param accessType permission to be updated
   * @param newValue   newValue new boolean for the permission
   * @return this
   */
  public ObjectPermissions setPermissionAccess(AccessType accessType, boolean newValue) {
    accessTypes.put(accessType, newValue);
    return this;
  }

  /**
   * Get permissions for a KD item based on role
   *
   * @param isFile item is a file or not
   * @return this
   */
  public ObjectPermissions getObjectPermissions(boolean isFile) {
    List<AccessType> accessTypes = Role.getPredefinedPermissionsForRole(role, isFile);
    if (Objects.nonNull(accessTypes)) {
      setBatchTo(accessTypes, true);
    }
    return this;
  }

  /**
   * Check if there is permission for an access event
   *
   * @param accessType        permission to be checked
   * @param customPermissions list of custom permissions
   * @param isFile            item is a file or not
   * @return boolean
   */
  public boolean checkPermissions(
      AccessType accessType, List<String> customPermissions, boolean isFile) {

    if (Objects.nonNull(customPermissions)) {
      return customPermissions.contains(accessType.name());
    }
    List<AccessType> accessTypes = Role.getPredefinedPermissionsForRole(role, isFile);
    if (Objects.isNull(accessType)) {
      return false;
    }
    return Objects.requireNonNull(accessTypes).contains(accessType);
  }

  /**
   * Check if there is permission for an access event
   *
   * @return json object containing default roles and permissions and their relation
   */
  public static JsonObject getDefaultRolesAndPermissions() {
    JsonObject result = new JsonObject();
    JsonArray roles = new JsonArray(Role.getAllRoles());
    JsonArray permissions = new JsonArray(AccessType.getAllAccessTypes());
    JsonObject interConnection = new JsonObject();
    roles.forEach(val -> {
      Role role = Role.getRole((String) val);
      if (Objects.isNull(role) || role.equals(Role.CUSTOM)) {
        return;
      }
      List<AccessType> filePermissions = Role.getPredefinedPermissionsForRole(role, true);
      List<AccessType> folderPermissions = Role.getPredefinedPermissionsForRole(role, false);
      interConnection.put(
          role.name(),
          new JsonObject()
              .put(
                  Field.FILES.getName(),
                  Objects.nonNull(filePermissions)
                      ? new JsonArray(filePermissions)
                      : new JsonArray())
              .put(
                  Field.FOLDERS.getName(),
                  Objects.nonNull(folderPermissions)
                      ? new JsonArray(folderPermissions)
                      : new JsonArray()));
    });
    result
        .put(Field.ROLES.getName(), roles)
        .put(Field.PERMISSIONS.getName(), permissions)
        .put("interConnection", interConnection);
    return result;
  }
}
