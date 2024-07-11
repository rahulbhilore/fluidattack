package com.graebert.storage.Entities;

import com.graebert.storage.util.Field;
import java.util.ArrayList;
import java.util.List;

public enum AccessType {
  canRename("rename"),
  canDelete("delete"),
  canOpen("open"),
  canDownload(Field.DOWNLOAD.getName()),
  canMove("move"),
  canMoveFrom("move from"),
  canMoveTo("move to"),
  canChangeOwner("change owner"),
  canCreateFolders("create folders"),
  canCreateFiles("create files"),
  canComment("comment"),
  canManageVersions("manage versions"),
  canEdit("edit"),
  canClone("clone"),
  canManagePermissions("manage permissions"),
  canViewPermissions("view permissions"),
  canManagePublicLink("manage public link"),
  canViewPublicLink("view public link"),
  canManageTrash("manage trash");

  public final String label;

  AccessType(String label) {
    this.label = label;
  }

  public static List<String> getAllAccessTypes() {
    List<String> accessTypes = new ArrayList<>();
    for (AccessType accessType : AccessType.values()) {
      accessTypes.add(accessType.name());
    }
    return accessTypes;
  }
}
