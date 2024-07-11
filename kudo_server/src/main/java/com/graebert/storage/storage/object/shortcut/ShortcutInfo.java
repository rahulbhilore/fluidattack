package com.graebert.storage.storage.object.shortcut;

import com.google.api.services.drive.model.File;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;

public class ShortcutInfo implements JsonConvertable {
  private final StorageType storageType;
  private final String externalId;
  private final File.ShortcutDetails shortcutDetails;

  public ShortcutInfo(
      StorageType storageType, String externalId, File.ShortcutDetails shortcutDetails) {
    this.storageType = storageType;
    this.externalId = externalId;
    this.shortcutDetails = shortcutDetails;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public String getExternalId() {
    return externalId;
  }

  public File.ShortcutDetails getShortcutDetails() {
    return shortcutDetails;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject() {
      {
        put(Field.MIME_TYPE.getName(), shortcutDetails.getTargetMimeType());
        put(
            Field.TYPE.getName(),
            shortcutDetails.getTargetMimeType().endsWith(Field.FOLDER.getName())
                ? Field.FOLDER.getName()
                : Field.FILE.getName());
        put(
            Field.TARGET_ID.getName(),
            Utils.getEncapsulatedId(storageType, externalId, shortcutDetails.getTargetId()));
      }
    };
  }
}
