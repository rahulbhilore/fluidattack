package com.graebert.storage.integration.hancom.objects;

import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class SharedObject extends BaseObject {

  private boolean isViewOnly = true;
  private String ownerId = "";
  private String ownerEmail = "";
  private BaseObject underlyingObject = null;
  private boolean isFolder = false;

  public SharedObject(JsonObject sharedObjectInfo) {
    super();
    if (isFolder(sharedObjectInfo)) {
      underlyingObject = new Folder(sharedObjectInfo, null, false);
      isFolder = true;
    } else {
      underlyingObject = new File(sharedObjectInfo, null, false);
    }
    if (sharedObjectInfo.containsKey(Field.PERMISSIONS.getName())) {
      permissions = sharedObjectInfo.getJsonObject(Field.PERMISSIONS.getName());
      isViewOnly = !permissions.getBoolean("edit") && !permissions.getBoolean("create");
    }
    if (sharedObjectInfo.containsKey(Field.OWNER_ID.getName())) {
      ownerId = String.valueOf(sharedObjectInfo.getInteger(Field.OWNER_ID.getName()));
    }
    if (sharedObjectInfo.containsKey(Field.OWNER_EMAIL.getName())) {
      ownerEmail = sharedObjectInfo.getString(Field.OWNER_EMAIL.getName());
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
    JsonObject baseInfo = new JsonObject();
    if (underlyingObject instanceof File) {
      baseInfo = ((File) underlyingObject)
          .toJson(
              accountId,
              config,
              isAdmin,
              s3Regional,
              userId,
              storageType,
              force,
              canCreateThumbnails);
    } else if (underlyingObject instanceof Folder) {
      baseInfo = ((Folder) underlyingObject).toJson(accountId, storageType);
    }
    baseInfo.put(Field.VIEW_ONLY.getName(), isViewOnly);
    baseInfo.put(Field.OWNER_ID.getName(), ownerId);
    if (Utils.isStringNotNullOrEmpty(ownerId) && Utils.isStringNotNullOrEmpty(accountId)) {
      baseInfo.put(Field.IS_OWNER.getName(), ownerId.equals(accountId));
    }
    baseInfo.put(Field.OWNER.getName(), ownerEmail);
    baseInfo.put(Field.CAN_MOVE.getName(), false);
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
    ObjectPermissions objectPermissions = new ObjectPermissions()
        .setAllTo(false)
        .setPermissionAccess(AccessType.canDelete, canDelete)
        .setPermissionAccess(AccessType.canViewPermissions, true)
        .setPermissionAccess(AccessType.canViewPublicLink, canManagePublicLink)
        .setPermissionAccess(AccessType.canManagePermissions, canManagePermissions)
        .setPermissionAccess(AccessType.canManagePublicLink, canManagePublicLink);
    baseInfo.put(Field.PERMISSIONS.getName(), objectPermissions.toJson());
    return baseInfo;
  }

  public boolean isFolder() {
    return isFolder;
  }

  public String getName() {
    if (isFolder) { // ignore
      return null;
    } else {
      return ((File) underlyingObject).getName();
    }
  }

  public String getId() {
    if (isFolder) { // ignore
      return null;
    } else {
      return ((File) underlyingObject).getId();
    }
  }

  public String getVersionId() {
    if (isFolder) { // ignore
      return null;
    } else {
      return ((File) underlyingObject).getVersionId();
    }
  }

  @Override
  public void updateShare(JsonArray listOfCollaborators, String accountId) {}
}
