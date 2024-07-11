package com.graebert.storage.integration.gdrive;

import static com.graebert.storage.util.Utils.humanReadableByteCount;
import static com.graebert.storage.vertx.DynamoBusModBase.log;

import com.google.api.services.drive.model.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.drive.model.PermissionList;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.xray.AWSXRayGDrive;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class TeamDriveWrapper {
  @NonNls
  private static final String READER = "reader";

  @NonNls
  private static final String OWNER = Field.OWNER.getName();

  @NonNls
  private static final String ORGANIZER = "organizer";

  @NonNls
  private static final String OWNERNAME = "ORGANIZATION";

  @NonNls
  private static final String APPLICATION_VND_GOOGLE_APPS_FOLDER =
      "application/vnd.google-apps.folder";

  private static final String teamDrivePrefix = "td";
  private static final String glue = ":";
  private static final String emptyString = "";
  private static ServerConfig config = null;
  private static S3Regional s3Regional = null;

  private final String teamDriveId;
  private final AWSXRayGDrive drive;
  private String itemId = null;
  private Drive teamDriveInfo;
  private File fileInfo;

  public TeamDriveWrapper(
      final AWSXRayGDrive drive,
      final ServerConfig serverConfig,
      final @NotNull String encapsulatedId,
      final S3Regional s3Regional) {
    this.drive = drive;
    config = serverConfig;
    TeamDriveWrapper.s3Regional = s3Regional;
    String[] splitted = encapsulatedId.split(glue);
    teamDriveId = splitted[1];
    if (splitted.length > 2) {
      itemId = splitted[2];
    }
  }

  @Contract(pure = true)
  public static @NotNull String getItemId(final String teamDriveId, final String itemId) {
    if (teamDriveId.equals(itemId) || itemId == null) {
      return teamDrivePrefix + glue + teamDriveId;
    }
    return teamDrivePrefix + glue + teamDriveId + glue + itemId;
  }

  public static boolean isTeamDriveId(final String idToCheck) {
    return idToCheck.startsWith(teamDrivePrefix);
  }

  public static String getObjectId(String itemId) throws Exception {
    if (isTeamDriveId(itemId)) {
      return itemId.substring(itemId.lastIndexOf(glue) + 1);
    } else {
      throw new IllegalArgumentException("Not Team Drive Id");
    }
  } // return id of object based on team drive id

  /**
   * @return true if id represents object inside teamdrive, false otherwise
   */
  public boolean isInnerObject() {
    return itemId != null;
  }

  public String getTeamDriveId() {
    return teamDriveId;
  }

  public String getItemId() {
    return itemId;
  }

  public String getParentId() {
    String parent = null;

    if (teamDriveInfo != null) {
      parent = Field.MINUS_1.getName();
    } else if (fileInfo != null) {
      String driveId = fileInfo.getTeamDriveId();
      parent =
          fileInfo.getParents() != null ? fileInfo.getParents().get(0) : Field.MINUS_1.getName();
      if (!parent.equals(Field.MINUS_1.getName())) {
        // if driveId == parent it means it is TeamDrive root folder, no need for parent
        parent = teamDrivePrefix
            + glue
            + driveId
            + (driveId.equals(parent) ? emptyString : (glue + parent));
      }
    }

    return parent;
  }

  public void getInfo() {
    try {
      if (itemId == null) {
        teamDriveInfo =
            drive.drives().get(teamDriveId).setFields("id,name,capabilities").execute();
      } else {
        fileInfo = drive
            .files()
            .get(itemId)
            .setSupportsAllDrives(true)
            .setFields("mimeType,headRevisionId,createdTime,id,lastModifyingUser,"
                + "modifiedTime,name,owners,ownedByMe,parents,permissions,shared,"
                + "size,trashed,capabilities,webContentLink,teamDriveId")
            .execute();
      }
    } catch (IOException e) {
      log.error(e);
    }
  }

  public File getFileInfo() {
    return this.fileInfo;
  }

  public JsonObject toJson(
      boolean full,
      boolean addShare,
      boolean isAdmin,
      String externalId,
      String userId,
      String email) {
    if (teamDriveInfo != null) {
      return getDriveJson(full, externalId);
    }
    if (fileInfo != null) {
      return getFileJson(full, addShare, isAdmin, externalId, userId, email);
    }
    return new JsonObject();
  }

  private JsonObject getDriveJson(boolean full, String externalId) {
    long updateDate = 0;
    String driveId = teamDriveInfo.getId();
    if (!Utils.isStringNotNullOrEmpty(driveId)) {
      driveId = teamDriveId;
    }
    if (full) {
      if (teamDriveInfo.getCreatedTime() != null) {
        updateDate = teamDriveInfo.getCreatedTime().getValue();
      }
    }
    boolean viewOnly = true;
    if (teamDriveInfo.getCapabilities() != null) {
      viewOnly = !teamDriveInfo.getCapabilities().getCanEdit();
    }
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();

    String rawId = teamDrivePrefix + glue + driveId;
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(StorageType.GDRIVE), externalId, rawId))
        .put(Field.WS_ID.getName(), rawId)
        .put(
            Field.NAME.getName(),
            teamDriveInfo.getName() != null ? teamDriveInfo.getName() : emptyString)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(StorageType.GDRIVE), externalId, Field.MINUS_1.getName()))
        .put(OWNER, OWNERNAME)
        .put(Field.OWNER_EMAIL.getName(), emptyString)
        .put(Field.CREATION_DATE.getName(), updateDate)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), emptyString)
        .put(Field.SIZE.getName(), humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), true)
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), false)
        .put(Field.IS_DELETED.getName(), false)
        .put(Field.CAN_MOVE.getName(), false)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.DRIVE_ID.getName(), driveId)
        .put(Field.IS_TEAM_DRIVE.getName(), true)
        .put(Field.PUBLIC.getName(), false);

    ObjectPermissions teamDrivePermissions = new ObjectPermissions().setAllTo(false);
    if (teamDriveInfo.getCapabilities() != null) {
      Drive.Capabilities capabilities = teamDriveInfo.getCapabilities();
      boolean sharingCapability = capabilities.getCanManageMembers() && capabilities.getCanShare();
      teamDrivePermissions
          .setPermissionAccess(
              AccessType.canRename,
              capabilities.getCanRenameDrive() != null ? capabilities.getCanRenameDrive() : false)
          .setPermissionAccess(
              AccessType.canCreateFiles,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : false)
          .setPermissionAccess(
              AccessType.canCreateFolders,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : false)
          .setPermissionAccess(
              AccessType.canDelete,
              capabilities.getCanDeleteDrive() != null ? capabilities.getCanDeleteDrive() : false)
          .setPermissionAccess(
              AccessType.canDownload,
              capabilities.getCanDownload() != null ? capabilities.getCanDownload() : false)
          .setPermissionAccess(
              AccessType.canClone,
              capabilities.getCanCopy() != null ? capabilities.getCanCopy() : false)
          .setPermissionAccess(AccessType.canManagePermissions, sharingCapability)
          .setPermissionAccess(AccessType.canViewPermissions, true);
    }
    json.put(Field.PERMISSIONS.getName(), teamDrivePermissions.toJson());
    return json;
  }

  private JsonObject getFileJson(
      boolean full,
      boolean addShare,
      boolean isAdmin,
      String externalId,
      String userId,
      String email) {
    long updateDate = 0;
    String changer = emptyString;
    boolean isFolder = false;
    try {
      System.out.println(fileInfo.toPrettyString());
      isFolder = APPLICATION_VND_GOOGLE_APPS_FOLDER.equals(fileInfo.getMimeType());
    } catch (IOException e) {
      e.printStackTrace();
    }
    String driveId = fileInfo.getTeamDriveId();
    String verId = fileInfo.getHeadRevisionId();
    if (full) {
      if (fileInfo.getModifiedTime() != null) {
        updateDate = fileInfo.getModifiedTime().getValue();
      }
      if (fileInfo.getLastModifyingUser() != null) {
        changer = fileInfo.getLastModifyingUser().getDisplayName();
      }
    }
    AtomicBoolean isOwner = new AtomicBoolean(false);
    boolean viewOnly = true;
    if (fileInfo.getCapabilities() != null) {
      viewOnly = !fileInfo.getCapabilities().getCanEdit();
    }
    boolean externalPublic = true; // fileInfo.getWebContentLink() != null;
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    List<Permission> permissions = null;
    try {
      permissions = listPermission(itemId).getPermissions();
    } catch (IOException e) {
      // ignore
    }
    if (permissions != null && addShare) {
      // RG: no need for parallelStream. Not calling API
      permissions = permissions.stream()
          .filter(permission ->
              permission != null && (permission.getDeleted() == null || !permission.getDeleted()))
          .collect(Collectors.toList());
      permissions.forEach(permission -> {
        String name = permission.getDisplayName();
        if (name != null) {
          JsonObject obj = new JsonObject()
              .put(Field.NAME.getName(), name)
              .put(Field.ENCAPSULATED_ID.getName(), permission.getId())
              .put(
                  Field.EMAIL.getName(),
                  permission.getEmailAddress() != null
                      ? permission.getEmailAddress().toLowerCase()
                      : null);
          if (ORGANIZER.equals(permission.getRole())
              && permission.getEmailAddress().equalsIgnoreCase(email)) {
            isOwner.set(true);
          } else if (READER.equals(permission.getRole())) {
            viewer.add(obj);
          } else {
            editor.add(obj);
          }
          // Considering all permissions other than reader as writer
        }
      });
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (!isFolder && addShare) {
      PLData = BaseStorage.findLinkForFile(
          getItemId(fileInfo.getTeamDriveId(), fileInfo.getId()), externalId, userId, viewOnly);
    }
    String rawId = teamDrivePrefix + glue + driveId + glue + fileInfo.getId();
    String parent =
        fileInfo.getParents() != null ? fileInfo.getParents().get(0) : Field.MINUS_1.getName();
    if (!parent.equals(Field.MINUS_1.getName())) {
      // if driveId == parent it means it is TeamDrive root folder, no need for parent
      parent = teamDrivePrefix
          + glue
          + driveId
          + (driveId.equals(parent) ? emptyString : (glue + parent));
    }
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(StorageType.GDRIVE), externalId, rawId))
        .put(Field.WS_ID.getName(), rawId)
        .put(
            isFolder ? Field.NAME.getName() : Field.FILE_NAME.getName(),
            fileInfo.getName() != null ? fileInfo.getName() : emptyString)
        .put(
            isFolder ? Field.PARENT.getName() : Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(StorageType.GDRIVE), externalId, parent))
        .put(
            OWNER,
            fileInfo.getOwners() != null && !fileInfo.getOwners().isEmpty()
                ? fileInfo.getOwners().get(0).getDisplayName()
                : OWNERNAME)
        .put(
            Field.OWNER_EMAIL.getName(),
            fileInfo.getOwners() != null && !fileInfo.getOwners().isEmpty()
                ? fileInfo.getOwners().get(0).getEmailAddress()
                : emptyString)
        .put(
            Field.CREATION_DATE.getName(),
            fileInfo.getCreatedTime() != null ? fileInfo.getCreatedTime().getValue() : 0)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), changer)
        .put(
            Field.SIZE.getName(),
            humanReadableByteCount(fileInfo.getSize() != null ? fileInfo.getSize() : 0))
        .put(Field.SIZE_VALUE.getName(), fileInfo.getSize() != null ? fileInfo.getSize() : 0)
        .put(Field.SHARED.getName(), fileInfo.getShared())
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner.get())
        .put(Field.IS_DELETED.getName(), fileInfo.getTrashed())
        .put(Field.CAN_MOVE.getName(), isOwner.get())
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor))
        .put(Field.EXTERNAL_PUBLIC.getName(), externalPublic)
        .put(Field.DRIVE_ID.getName(), driveId)
        .put(Field.IS_TEAM_DRIVE.getName(), true)
        .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()));
    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    if (!isFolder) {
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.GDRIVE, fileInfo.getId(), verId);
      String previewId = ThumbnailsManager.getPreviewName(
          StorageType.getShort(StorageType.GDRIVE), fileInfo.getId());
      json.put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put(Field.PREVIEW_ID.getName(), previewId);

      if (config != null
          && s3Regional != null
          && Extensions.isThumbnailExt(config, fileInfo.getName(), isAdmin)) {
        try {
          json.put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(
                      config,
                      thumbnailName,
                      true)) /// *config.getProperties().getUrl() + */"/thumbnails/"
              // + thumbnailName)
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true)
                      + ".json") // "/geomdata/" + thumbnailName + ".json")
              .put(
                  Field.PREVIEW.getName(),
                  ThumbnailsManager.getPreviewURL(
                      config, previewId,
                      true)); // "/previews/" + StorageType.getShort(StorageType.GDRIVE) + "_" +
          // fileInfo.getId(), true);
        } catch (Exception ignore) {
        } //
      }
    }
    if (verId != null) {
      json.put(Field.VER_ID.getName(), verId);
      json.put(Field.VERSION_ID.getName(), verId);
    }
    boolean isDeleted = fileInfo.getTrashed();
    ObjectPermissions teamDrivePermissions = new ObjectPermissions();
    if (fileInfo.getCapabilities() != null) {
      File.Capabilities capabilities = fileInfo.getCapabilities();
      boolean sharingCapability = !isDeleted && capabilities.getCanShare() && isOwner.get();
      boolean canMoveFrom = (capabilities.getCanMoveChildrenWithinDrive() != null
          ? capabilities.getCanMoveChildrenWithinDrive()
          : true);
      boolean canMove = (capabilities.getCanMoveItemWithinDrive() != null
          ? capabilities.getCanMoveItemWithinDrive()
          : true);
      teamDrivePermissions
          .setPermissionAccess(
              AccessType.canRename,
              capabilities.getCanRename() != null ? capabilities.getCanRename() : false)
          .setPermissionAccess(
              AccessType.canCreateFiles,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : false)
          .setPermissionAccess(
              AccessType.canCreateFolders,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : false)
          .setPermissionAccess(
              AccessType.canDelete,
              capabilities.getCanDelete() != null ? capabilities.getCanDelete() : false)
          .setPermissionAccess(
              AccessType.canDownload,
              capabilities.getCanDownload() != null ? capabilities.getCanDownload() : false)
          .setPermissionAccess(
              AccessType.canClone,
              capabilities.getCanCopy() != null ? capabilities.getCanCopy() : false)
          .setPermissionAccess(AccessType.canManagePermissions, sharingCapability)
          .setPermissionAccess(AccessType.canMove, canMove)
          .setPermissionAccess(AccessType.canMoveFrom, canMoveFrom)
          .setPermissionAccess(AccessType.canMoveTo, true);
    } else {
      teamDrivePermissions
          .setAllTo(true)
          .setPermissionAccess(AccessType.canManagePermissions, !isDeleted && isOwner.get())
          .setPermissionAccess(AccessType.canClone, !isFolder);
    }
    teamDrivePermissions
        .setPermissionAccess(AccessType.canViewPermissions, !isDeleted)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isDeleted)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isDeleted);

    if (!isFolder) {
      teamDrivePermissions.setBatchTo(
          List.of(
              AccessType.canMoveFrom,
              AccessType.canMoveTo,
              AccessType.canCreateFiles,
              AccessType.canCreateFolders),
          false);
    }

    json.put(Field.PERMISSIONS.getName(), teamDrivePermissions.toJson());
    return json;
  }

  // fileInfo.getPermissions() is null for TD, so using drive list permissions
  public PermissionList listPermission(String fileId) throws IOException {
    return drive
        .permissions()
        .list(fileId)
        .setFields("permissions(id,emailAddress,role,displayName,deleted)")
        .setSupportsAllDrives(true)
        .execute();
  }
}
