package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class DriveInfo {
  private String id;

  public DriveInfo(JsonObject drive) {
    this.id = drive.getString(Field.ID.getName());
  }

  public String getId() {
    return id;
  }
}
