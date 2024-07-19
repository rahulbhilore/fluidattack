package com.graebert.storage.integration.onedrive;

import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;

// Not sure what should be the inheritance order, but they are quite similar
public class Drive extends Site {
  private String owner;
  private String modifiedBy;
  private final String id;
  private final String siteId;

  public Drive(JsonObject driveData, StorageType storageType, String externalId, String siteId) {
    super(driveData, storageType, externalId);
    owner = driveData.getString("webUrl");
    modifiedBy = owner;
    if (driveData.containsKey(Field.OWNER.getName())
        && driveData.getJsonObject(Field.OWNER.getName()).containsKey(Field.USER.getName())) {
      owner = driveData
          .getJsonObject(Field.OWNER.getName())
          .getJsonObject(Field.USER.getName())
          .getString(Field.DISPLAY_NAME.getName());
    } else if (driveData.containsKey(Field.OWNER.getName())
        && driveData.getJsonObject(Field.OWNER.getName()).containsKey("group")) {
      owner = driveData
          .getJsonObject(Field.OWNER.getName())
          .getJsonObject("group")
          .getString(Field.DISPLAY_NAME.getName());
    }
    if (driveData.containsKey(Field.LAST_MODIFIED_BY.getName())
        && driveData
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .containsKey(Field.USER.getName())) {
      modifiedBy = driveData
          .getJsonObject(Field.LAST_MODIFIED_BY.getName())
          .getJsonObject(Field.USER.getName())
          .getString(Field.DISPLAY_NAME.getName());
    }
    id = driveData.getString(Field.ID.getName()) + ":" + Field.ROOT.getName();
    this.siteId = siteId;
  }

  public JsonObject toJson() {
    JsonObject response = super.toJson();
    response
        .put(BaseStorage.OWNER, owner)
        .put(Field.CHANGER.getName(), modifiedBy)
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, siteId))
        .put(
            Field.PERMISSIONS.getName(),
            new ObjectPermissions()
                .setPermissionAccess(AccessType.canMoveTo, true)
                .toJson());
    return response;
  }
}
