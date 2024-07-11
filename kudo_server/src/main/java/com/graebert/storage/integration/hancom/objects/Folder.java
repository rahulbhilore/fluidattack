package com.graebert.storage.integration.hancom.objects;

import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.List;

public class Folder extends BaseObject {
  private final String name;
  private final boolean isShared;
  private final int versionId;
  private final long modifiedTime;
  private final int size;
  private final long createdTime;
  private final String id;
  private final String parentFolderId;
  private final boolean isTrash;
  private String ownerEmail = "";
  private String ownerName = "";

  /**
   * Regular constructor out of getFolderContent
   *
   * @param folderInfo     - info from Hancom's API
   * @param parentFolderId - parent folder
   * @param isTrash        - flag for deleted objects
   */
  public Folder(JsonObject folderInfo, String parentFolderId, boolean isTrash) {
    this.name = folderInfo.getString(Field.NAME.getName());
    this.isShared = folderInfo.getBoolean(Field.SHARED.getName());
    this.versionId = folderInfo.getInteger("lastVersion");
    this.modifiedTime = folderInfo.getLong("gmtModified");
    this.size = folderInfo.getInteger(Field.SIZE.getName());
    this.createdTime = folderInfo.getLong("gmtCreated");
    this.parentFolderId = getParentId(folderInfo, "pathID", parentFolderId);
    this.id = getObjectId(folderInfo);
    this.ownerEmail = getOwnerEmail(folderInfo);
    this.ownerName = getOwner(folderInfo);
    this.isTrash = isTrash;
    this.updateFlagsFromInfo(folderInfo);
  }

  /**
   * This one is generated out of info request
   *
   * @param id         - folder id
   * @param folderInfo - info from Hancom's API
   * @param isTrash    - flag if obj is deleted
   */
  public Folder(String id, JsonObject folderInfo, boolean isTrash) {
    this.name = getName(folderInfo);
    this.isShared = folderInfo.getBoolean(Field.SHARED.getName());
    this.versionId = folderInfo.getInteger("lastVersion");
    this.modifiedTime = folderInfo.getLong(Field.LAST_MODIFIED.getName());
    this.size = 0;
    this.createdTime = folderInfo.containsKey(Field.CREATED.getName())
        ? folderInfo.getLong(Field.CREATED.getName())
        : 0;
    this.parentFolderId = getParentId(folderInfo, "pathID", null);
    this.id = Utils.isStringNotNullOrEmpty(id) ? id : getObjectId(folderInfo);
    this.ownerEmail = getOwnerEmail(folderInfo);
    this.ownerName = getOwner(folderInfo);
    this.isTrash = isTrash;
    this.updateFlagsFromInfo(folderInfo);
  }

  public Folder(JsonObject searchInfo) {
    this.name = searchInfo.getString("M_NAME");
    this.isShared = searchInfo.getBoolean(Field.SHARED.getName());
    this.modifiedTime = Long.parseLong(searchInfo.getString("M_LAST_MODIFIED"));
    this.size = 0;
    this.createdTime = Long.parseLong(searchInfo.getString("M_CREATED"));
    this.parentFolderId = getParentId(searchInfo, "PATH", null);
    this.id = searchInfo.getString("M_NATIVE_ID");
    this.isTrash = false;
    this.versionId = 0;
    this.ownerEmail = getOwnerEmail(searchInfo);
    this.ownerName = getOwner(searchInfo);
    this.updateFlagsFromInfo(searchInfo);
  }

  public JsonObject toJson(String accountId, StorageType storageType) {
    JsonObject obj = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), accountId, id))
        .put(Field.NAME.getName(), name)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), accountId, parentFolderId)) // ?
        .put(
            Field.OWNER_ID.getName(),
            Utils.isStringNotNullOrEmpty(ownerEmail) ? ownerEmail : accountId) // ?
        .put(Field.DELETED.getName(), isTrash)
        .put(Field.CREATION_DATE.getName(), createdTime);
    obj.put(Field.OWNER.getName(), ownerName);
    obj.put(Field.IS_OWNER.getName(), this.isOwner); // ?
    obj.put(Field.CAN_MOVE.getName(), !isShared); // ?
    obj.put(Field.SHARED.getName(), isShared);
    obj.put(Field.VIEW_ONLY.getName(), this.isViewOnly);
    obj.put(Field.SHARE.getName(), this.shareInfo);
    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setBatchTo(
            List.of(
                AccessType.canClone, AccessType.canViewPublicLink, AccessType.canManagePublicLink),
            false)
        .setPermissionAccess(
            AccessType.canManagePermissions, !this.isTrash && (!this.isViewOnly || this.isOwner))
        .setPermissionAccess(AccessType.canViewPermissions, !this.isTrash);
    obj.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return obj;
  }

  public String getName() {
    return name;
  }

  public String getId() {
    return id;
  }

  @Override
  public boolean isShared() {
    return isShared;
  }
}
