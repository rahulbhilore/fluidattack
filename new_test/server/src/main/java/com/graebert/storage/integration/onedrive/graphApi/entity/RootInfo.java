package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class RootInfo {
  private String id;
  private JsonObject info;

  public RootInfo(JsonObject info) {
    this.id = info.getString(Field.ID.getName());
    this.info = info;
  }

  public String getId() {
    return id;
  }

  public String getDriveId() {
    return info.containsKey(Field.PARENT_REFERENCE.getName())
        ? info.getJsonObject(Field.PARENT_REFERENCE.getName()).getString(Field.DRIVE_ID.getName())
        : null;
  }
}
