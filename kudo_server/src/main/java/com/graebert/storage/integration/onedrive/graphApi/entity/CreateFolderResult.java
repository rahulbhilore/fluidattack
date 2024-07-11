package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class CreateFolderResult {
  private final String driveId;
  private final JsonObject responseBody;

  private final StorageType storageType;

  public CreateFolderResult(JsonObject responseBody, String driveId, StorageType storageType) {
    this.driveId = driveId;
    this.responseBody = responseBody;
    this.storageType = storageType;
  }

  public String getId() {
    return driveId != null
        ? (storageType.equals(StorageType.SHAREPOINT)
            ? GraphUtils.generateSPId(responseBody)
            : GraphUtils.generateId(responseBody))
        : responseBody.getString(Field.ID.getName());
  }

  public String getDriveId() {
    return driveId != null
        ? driveId
        : responseBody
            .getJsonObject(Field.PARENT_REFERENCE.getName())
            .getString(Field.DRIVE_ID.getName());
  }
}
