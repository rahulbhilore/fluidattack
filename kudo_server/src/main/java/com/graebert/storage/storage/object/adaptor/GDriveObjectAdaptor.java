package com.graebert.storage.storage.object.adaptor;

import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.gdrive.TeamDriveWrapper;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.storage.object.share.ObjectShareInfo;
import com.graebert.storage.storage.object.share.ShareInfo;
import com.graebert.storage.storage.object.shortcut.ShortcutEntity;
import com.graebert.storage.storage.object.shortcut.ShortcutInfo;
import com.graebert.storage.util.Field;
import java.util.List;
import java.util.Objects;

public class GDriveObjectAdaptor extends DefaultAdapter {
  private static final String WRITER = "writer";
  private static final String OWNER = Field.OWNER.getName();

  private static final StorageType storageType = StorageType.GDRIVE;

  public static ShortcutEntity makeShortcutEntity(File shortcutInfo, String externalId) {
    boolean isTeamDrive = Objects.nonNull(shortcutInfo.getTeamDriveId());
    boolean isOwner = !isTeamDrive && shortcutInfo.getOwnedByMe();
    boolean viewOnly = !Objects.nonNull(shortcutInfo.getCapabilities())
        || !shortcutInfo.getCapabilities().getCanEdit();
    String driveId = isTeamDrive ? shortcutInfo.getTeamDriveId() : shortcutInfo.getDriveId();
    String rawId = isTeamDrive
        ? TeamDriveWrapper.getItemId(driveId, shortcutInfo.getId())
        : shortcutInfo.getId();
    long updateDate = Objects.nonNull(shortcutInfo.getModifiedTime())
        ? shortcutInfo.getModifiedTime().getValue()
        : 0;
    File.ShortcutDetails shortcutDetails = shortcutInfo.getShortcutDetails();

    ObjectShareInfo objectShareInfo = new ObjectShareInfo() {
      {
        if (Objects.nonNull(shortcutInfo.getPermissions())) {
          for (Permission permission : shortcutInfo.getPermissions()) {
            if (OWNER.equals(permission.getRole())) {
              continue;
            }

            if (Objects.nonNull(permission.getDisplayName())) {
              ShareInfo shareInfo = new ShareInfo(
                  permission.getId(),
                  permission.getDisplayName(),
                  Objects.nonNull(permission.getEmailAddress())
                      ? permission.getEmailAddress().toLowerCase()
                      : null);

              if (WRITER.equals(permission.getRole())) {
                addEditor(shareInfo);
              } else {
                addViewer(shareInfo);
              }
            }
          }
        }
      }
    };

    String parent = Field.MINUS_1.getName();
    if (Objects.nonNull(shortcutInfo.getParents())) {
      parent = shortcutInfo.getParents().get(0);
    }

    if (isTeamDrive) {
      parent = TeamDriveWrapper.getItemId(driveId, parent);
    } else if (parent.equals(Field.MINUS_1.getName()) && !isOwner) {
      parent = SHARED_FOLDER;
    }

    boolean isDeleted = shortcutInfo.getTrashed();

    ObjectPermissions permissions = new ObjectPermissions();
    if (shortcutInfo.getCapabilities() != null) {
      File.Capabilities capabilities = shortcutInfo.getCapabilities();
      boolean sharingCapability = !isDeleted && capabilities.getCanShare();
      boolean canMoveFrom = capabilities.getCanMoveChildrenWithinDrive() != null
          ? capabilities.getCanMoveChildrenWithinDrive()
          : true;
      permissions
          .setPermissionAccess(
              AccessType.canRename,
              capabilities.getCanRename() != null ? capabilities.getCanRename() : true)
          .setPermissionAccess(
              AccessType.canCreateFiles,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : true)
          .setPermissionAccess(
              AccessType.canCreateFolders,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : true)
          .setPermissionAccess(
              AccessType.canDelete,
              capabilities.getCanDelete() != null ? capabilities.getCanDelete() : true)
          .setPermissionAccess(AccessType.canDownload, true)
          .setPermissionAccess(
              AccessType.canClone,
              capabilities.getCanCopy() != null ? capabilities.getCanCopy() : false)
          .setPermissionAccess(AccessType.canManagePermissions, sharingCapability)
          .setPermissionAccess(AccessType.canMoveFrom, canMoveFrom)
          .setPermissionAccess(AccessType.canMoveTo, true);
    } else {
      permissions
          .setAllTo(true)
          .setPermissionAccess(
              AccessType.canManagePermissions, !isDeleted && (!viewOnly || isOwner))
          .setPermissionAccess(AccessType.canClone, true);
    }
    permissions
        .setPermissionAccess(AccessType.canMove, isOwner)
        .setPermissionAccess(AccessType.canViewPermissions, !isDeleted)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isDeleted)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isDeleted)
        .setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false);

    boolean shared = false;
    try {
      shared = shortcutInfo.getShared();
    } catch (Exception ignore) {
    }

    return new ShortcutEntity(
        storageType,
        externalId,
        rawId,
        parent,
        Objects.nonNull(shortcutInfo.getOwners()) && !shortcutInfo.getOwners().isEmpty()
            ? shortcutInfo.getOwners().get(0).getDisplayName()
            : "",
        isOwner,
        shared,
        viewOnly,
        Objects.nonNull(shortcutInfo.getName()) ? shortcutInfo.getName() : "UnnamedShortcut",
        Objects.nonNull(shortcutInfo.getCreatedTime())
            ? shortcutInfo.getCreatedTime().getValue()
            : 0,
        updateDate,
        isDeleted,
        objectShareInfo,
        new ShortcutInfo(storageType, externalId, shortcutDetails),
        permissions);
  }
}
