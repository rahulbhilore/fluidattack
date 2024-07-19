package com.graebert.storage.storage.object;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.storage.object.share.ObjectShareInfo;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;

public abstract class ObjectEntity implements JsonConvertable {
  // object storageType
  private final StorageType storageType;
  // object externalId
  private final String externalId;
  // pure id without external
  private final String id;
  // pure parentId without external
  private final String parentId;

  private final String owner;
  private final boolean isOwner;

  private final boolean isShared;
  private final boolean isViewOnly;

  private final String name;
  private final long creationDate;
  private final long updateDate;
  private final boolean isDeleted;

  private final ObjectShareInfo objectShareInfo;

  private final ObjectPermissions objectPermissions;

  public ObjectEntity(
      StorageType storageType,
      String externalId,
      String id,
      String parentId,
      String owner,
      boolean isOwner,
      boolean isShared,
      boolean isViewOnly,
      String name,
      long creationDate,
      long updateDate,
      boolean isDeleted,
      ObjectShareInfo objectShareInfo,
      ObjectPermissions objectPermissions) {
    this.storageType = storageType;
    this.externalId = externalId;
    this.id = id;
    this.parentId = parentId;
    this.owner = owner;
    this.isOwner = isOwner;
    this.isShared = isShared;
    this.isViewOnly = isViewOnly;
    this.name = name;
    this.creationDate = creationDate;
    this.updateDate = updateDate;
    this.isDeleted = isDeleted;
    this.objectShareInfo = objectShareInfo;
    this.objectPermissions = objectPermissions;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public String getExternalId() {
    return externalId;
  }

  public String getId() {
    return id;
  }

  public String getParentId() {
    return parentId;
  }

  public String getOwner() {
    return owner;
  }

  public boolean isOwner() {
    return isOwner;
  }

  public boolean isShared() {
    return isShared;
  }

  public boolean isViewOnly() {
    return isViewOnly;
  }

  public String getName() {
    return name;
  }

  public long getCreationDate() {
    return creationDate;
  }

  public long getUpdateDate() {
    return updateDate;
  }

  public boolean isDeleted() {
    return isDeleted;
  }

  public ObjectShareInfo getObjectShareInfo() {
    return objectShareInfo;
  }

  public ObjectPermissions getObjectPermissions() {
    return objectPermissions;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject() {
      {
        put(Field.ENCAPSULATED_ID.getName(), composeEncapsulatedId(id));

        // property's Field.NAME.getName() name may be different for extended objects
        // proper's Field.PARENT_ID.getName() name may be different for extended objects
        //      put(Field.NAME.getName(), name);
        //      put(Field.PARENT_ID.getName(), composeEncapsulatedId(parentId));

        put(Field.NAME.getName(), name);
        put(Field.OWNER.getName(), owner);
        put(Field.CREATION_DATE.getName(), creationDate);
        put(Field.UPDATE_DATE.getName(), updateDate);
        put(Field.SHARED.getName(), isShared);
        put(Field.VIEW_ONLY.getName(), isViewOnly);
        put(Field.IS_OWNER.getName(), isOwner);
        put(Field.IS_DELETED.getName(), isDeleted);
        put(Field.SHARE.getName(), objectShareInfo.toJson());
        put(Field.PERMISSIONS.getName(), objectPermissions.toJson());
      }
    };
  }

  protected String composeEncapsulatedId(String idToCompose) {
    return Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, idToCompose);
  }
}
