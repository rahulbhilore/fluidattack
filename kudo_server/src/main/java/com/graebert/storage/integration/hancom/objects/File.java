package com.graebert.storage.integration.hancom.objects;

import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;

public class File extends BaseObject {
  private final String name;
  private final boolean isShared;
  private final long modifiedTime;
  private final int size;
  private final long createdTime;
  private final String id;
  private final String parentFolderId;
  private final boolean isTrash;
  private int versionId = 1;
  private String editorName = "";

  public File(JsonObject fileInfo, String parentFolderId, boolean isTrash) {
    this.name = getName(fileInfo);
    this.isShared = fileInfo.getBoolean(Field.SHARED.getName());
    this.versionId = fileInfo.getInteger("lastVersion");
    this.modifiedTime = fileInfo.getLong("gmtModified");
    this.size = fileInfo.getInteger(Field.SIZE.getName());
    this.createdTime = fileInfo.getLong("gmtCreated");
    this.parentFolderId = getParentId(fileInfo, "pathID", parentFolderId);
    this.id = getObjectId(fileInfo);
    this.isTrash = isTrash;
    this.owner = getOwner(fileInfo);
    this.ownerEmail = getOwnerEmail(fileInfo);
    this.updateFlagsFromInfo(fileInfo);
  }

  public File(JsonObject fileInfo, boolean isTrash) {
    this.name = getName(fileInfo);
    this.isShared = fileInfo.getBoolean(Field.SHARED.getName());
    this.versionId = fileInfo.getInteger("lastVersion");
    this.modifiedTime = fileInfo.getLong(Field.LAST_MODIFIED.getName());
    this.size = fileInfo.getInteger(Field.SIZE.getName());
    this.createdTime = fileInfo.containsKey(Field.CREATED.getName())
        ? fileInfo.getLong(Field.CREATED.getName())
        : 0;
    this.parentFolderId = getParentId(fileInfo, "pathID", null);
    this.id = getObjectId(fileInfo);
    this.isTrash = isTrash;
    this.owner = getOwner(fileInfo);
    this.ownerEmail = getOwnerEmail(fileInfo);
    this.updateFlagsFromInfo(fileInfo);
  }

  public File(JsonObject searchInfo) {
    this.name = searchInfo.getString("M_NAME");
    this.isShared = searchInfo.getBoolean(Field.SHARED.getName());
    this.modifiedTime = Long.parseLong(searchInfo.getString("M_LAST_MODIFIED"));
    this.size = Integer.parseInt(searchInfo.getString("SIZE"));
    this.createdTime = Long.parseLong(searchInfo.getString("M_CREATED"));
    this.parentFolderId = getParentId(searchInfo, "PATH", null);
    this.id = searchInfo.getString("M_NATIVE_ID");
    this.isTrash = false;
    this.owner = getOwner(searchInfo);
    this.ownerEmail = getOwnerEmail(searchInfo);
    this.updateFlagsFromInfo(searchInfo);
  }

  public void fetchEditor(JsonObject versionsResponse) {
    JsonArray versionsList = versionsResponse.getJsonArray("list");
    if (versionsList.size() > 0) {
      JsonObject lastVersion = versionsList.getJsonObject(versionsList.size() - 1);
      if (!lastVersion.isEmpty() && lastVersion.containsKey(Field.EDITOR.getName())) {
        JsonObject editor = lastVersion.getJsonObject(Field.EDITOR.getName());
        if (editor.containsKey("user_name")) {
          this.editorName = editor.getString("user_name");
        } else if (editor.containsKey("user_email")) {
          this.editorName = editor.getString("user_email");
        }
      }
    } else {
      this.editorName = owner;
    }
  }

  public JsonObject toJson(
      String accountId,
      ServerConfig config,
      boolean isAdmin,
      S3Regional s3Regional,
      String userId,
      StorageType storageType,
      boolean force,
      boolean canCreateThumbnails) {
    JsonObject obj = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), accountId, id))
        .put(Field.WS_ID.getName(), Utils.encodeValue(id))
        .put(Field.FILE_NAME.getName(), name)
        .put(
            Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), accountId, parentFolderId)) // ?
        .put(Field.VER_ID.getName(), String.valueOf(versionId))
        .put(Field.VERSION_ID.getName(), String.valueOf(versionId))
        .put(Field.CHANGER.getName(), this.editorName)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(size))
        .put(Field.SIZE_VALUE.getName(), size)
        .put(Field.UPDATE_DATE.getName(), modifiedTime)
        .put(Field.OWNER_ID.getName(), accountId) // ?
        .put(Field.DELETED.getName(), isTrash)
        .put(Field.CREATION_DATE.getName(), createdTime);
    obj.put(Field.OWNER.getName(), owner);
    obj.put(Field.IS_OWNER.getName(), this.isOwner);
    obj.put(Field.CAN_MOVE.getName(), isOwner || !isShared);
    String thumbnailName = ThumbnailsManager.getThumbnailName(
        StorageType.getShort(storageType), id, String.valueOf(versionId));
    String previewId = ThumbnailsManager.getPreviewName(StorageType.getShort(storageType), id);
    obj.put(Field.PREVIEW_ID.getName(), previewId)
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
    if (Extensions.isThumbnailExt(config, name, isAdmin)) {
      obj.put(
              "thumbnailStatus",
              getThumbnailStatus(
                  id, storageType.name(), String.valueOf(versionId), force, canCreateThumbnails))
          .put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
          .put(
              Field.GEOMDATA.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true) + ".json")
          .put(Field.PREVIEW.getName(), ThumbnailsManager.getPreviewURL(config, previewId, true));
    }
    obj.put(Field.SHARED.getName(), isShared);
    obj.put(Field.VIEW_ONLY.getName(), this.isViewOnly);
    obj.put(Field.SHARE.getName(), this.shareInfo);
    // if file is public
    JsonObject PLData = BaseStorage.findLinkForFile(id, accountId, userId, false);
    obj.put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()));
    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      obj.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    boolean canManagePermissions = true;
    boolean canManagePublicLink = true;
    boolean canDelete = true;
    if (permissions != null && !permissions.isEmpty()) {
      if (permissions.containsKey("re_share")) {
        canManagePermissions = permissions.getBoolean("re_share");
      }
      if (permissions.containsKey("public_share")) {
        canManagePublicLink = permissions.getBoolean("public_share");
      }
      if (permissions.containsKey("delete")) {
        canDelete = permissions.getBoolean("delete");
      }
    }
    if (this.isTrash) {
      canManagePermissions = false;
      canManagePublicLink = false;
    }
    ObjectPermissions objectPermissions = new ObjectPermissions()
        .setAllTo(true)
        .setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false)
        .setPermissionAccess(AccessType.canDelete, canDelete)
        .setPermissionAccess(AccessType.canViewPermissions, !this.isTrash)
        .setPermissionAccess(AccessType.canViewPublicLink, !this.isViewOnly && !this.isTrash)
        .setPermissionAccess(AccessType.canManagePermissions, canManagePermissions)
        .setPermissionAccess(AccessType.canManagePublicLink, canManagePublicLink);

    obj.put(Field.PERMISSIONS.getName(), objectPermissions.toJson());
    return obj;
  }

  public String getName() {
    return this.name;
  }

  public String getId() {
    return this.id;
  }

  public String getVersionId() {
    return String.valueOf(this.versionId);
  }

  public String getParentFolderId() {
    return this.parentFolderId;
  }

  public long getModifiedTime() {
    return this.modifiedTime;
  }

  public String getEditorName() {
    return this.editorName;
  }

  public String getOwner() {
    return this.owner;
  }

  @Override
  public boolean isShared() {
    return isShared;
  }
}
