package com.graebert.storage.Entities;

import com.google.common.collect.Lists;
import com.graebert.storage.util.Utils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public enum Role {
  EDITOR,
  VIEWER,
  OWNER,
  CUSTOM;

  public static Role getRole(String value) {
    if (Utils.isStringNotNullOrEmpty(value)) {
      final String formattedValue = value.trim();
      for (Role t : Role.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
    }
    return null;
  }

  /**
   * Get all predefined roles
   *
   * @return list of roles
   */
  public static List<String> getAllRoles() {
    List<String> roles = new ArrayList<>();
    for (Role role : Role.values()) {
      roles.add(role.name());
    }
    return roles;
  }

  /**
   * Get predefined permissions for a role and object type
   *
   * @param role   role of the user
   * @param isFile item is a file or not
   * @return list of permissions
   */
  public static List<AccessType> getPredefinedPermissionsForRole(Role role, boolean isFile) {
    List<AccessType> permissions = getDefaultPredefinedPermissions();
    if (Objects.nonNull(role)) {
      switch (role) {
        case VIEWER: {
          return getViewerPredefinedPermissions(isFile);
        }
        case EDITOR: {
          return getEditorPredefinedPermissions(isFile);
        }
        case OWNER: {
          return getOwnerPredefinedPermissions(isFile);
        }
        default:
          return permissions;
      }
    }
    return null;
  }

  private static List<AccessType> getDefaultPredefinedPermissions() {
    return Lists.newArrayList(AccessType.canDownload);
  }

  private static List<AccessType> getViewerPredefinedPermissions(boolean isFile) {
    List<AccessType> viewerPermissions = getDefaultPredefinedPermissions();
    viewerPermissions.addAll(List.of(AccessType.canClone, AccessType.canViewPermissions));
    if (isFile) {
      viewerPermissions.addAll(
          List.of(AccessType.canOpen, AccessType.canComment, AccessType.canViewPublicLink));
    }
    return viewerPermissions;
  }

  private static List<AccessType> getEditorPredefinedPermissions(boolean isFile) {
    List<AccessType> editorPermissions = getViewerPredefinedPermissions(isFile);
    editorPermissions.add(AccessType.canRename);
    // 1st Phase for folder sharing - Sharing only allowed for owner
    if (isFile) {
      editorPermissions.addAll(List.of(
          AccessType.canEdit,
          AccessType.canManageVersions,
          AccessType.canManagePublicLink,
          AccessType.canManagePermissions));
    } else {
      editorPermissions.addAll(List.of(
          AccessType.canCreateFolders,
          AccessType.canMoveFrom,
          AccessType.canMoveTo,
          AccessType.canCreateFiles));
    }
    return editorPermissions;
  }

  private static List<AccessType> getOwnerPredefinedPermissions(boolean isFile) {
    List<AccessType> ownerPermissions = getEditorPredefinedPermissions(isFile);
    ownerPermissions.addAll(List.of(
        AccessType.canChangeOwner,
        AccessType.canDelete,
        AccessType.canMove,
        AccessType.canManageTrash));
    if (!isFile) {
      ownerPermissions.add(AccessType.canManagePermissions); // 1st Phase for folder sharing
    }
    return ownerPermissions;
  }
}
