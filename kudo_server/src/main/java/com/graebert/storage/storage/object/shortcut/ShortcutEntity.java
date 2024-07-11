package com.graebert.storage.storage.object.shortcut;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.storage.object.ObjectEntity;
import com.graebert.storage.storage.object.share.ObjectShareInfo;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class ShortcutEntity extends ObjectEntity {
  private final ShortcutInfo shortcutInfo;

  public ShortcutEntity(
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
      ShortcutInfo shortcutInfo,
      ObjectPermissions objectPermissions) {
    super(
        storageType,
        externalId,
        id,
        parentId,
        owner,
        isOwner,
        isShared,
        isViewOnly,
        name,
        creationDate,
        updateDate,
        isDeleted,
        objectShareInfo,
        objectPermissions);
    this.shortcutInfo = shortcutInfo;
  }

  public ShortcutInfo getShortcutInfo() {
    return shortcutInfo;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put(Field.FILE_NAME.getName(), this.getName())
        .put(Field.PARENT_ID.getName(), composeEncapsulatedId(getParentId()))
        .put(Field.SHORTCUT_INFO.getName(), shortcutInfo.toJson());
  }
}
