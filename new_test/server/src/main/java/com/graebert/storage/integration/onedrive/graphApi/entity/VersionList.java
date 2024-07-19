package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VersionList {
  private final JsonObject versions;
  private final String externalId;
  private final String fileId;
  private final String driveId;
  private final StorageType storageType;

  public VersionList(
      JsonObject versions,
      StorageType storageType,
      String externalId,
      String driveId,
      String fileId) {
    this.versions = versions;
    this.fileId = fileId;
    this.driveId = driveId;
    this.externalId = externalId;
    this.storageType = storageType;
  }

  public JsonArray toJson(ServerConfig config) {
    List<JsonObject> result = new ArrayList<>();

    for (int i = 0; i < versions.getJsonArray("value").size(); i++) {
      JsonObject obj = versions.getJsonArray("value").getJsonObject(i);
      result.add(GraphUtils.getVersionInfo(
              config,
              storageType,
              obj,
              new GraphId(driveId, fileId).getFullId(),
              externalId,
              i == 0)
          .toJson());
    }

    result.sort(Comparator.comparing(o -> o.getLong("creationTime")));

    return new JsonArray(result);
  }

  public String getLatestVersionId() {
    String latestVersionId = Field.CURRENT.getName();

    if (versions.containsKey("value")) {
      JsonObject latestVersionInfo = versions.getJsonArray("value").getJsonObject(0);
      latestVersionId = latestVersionInfo.getString(Field.ID.getName());
      if (latestVersionId.equals(
          Field.CURRENT.getName())) { // we should avoid Field.CURRENT.getName() for OD
        latestVersionId = String.valueOf(GraphUtils.getCreationDate(
            latestVersionInfo.getString(Field.LAST_MODIFIED_DATE_TIME.getName())));
      }
    }

    return latestVersionId;
  }

  public boolean checkVersion(String toFindVersionId) {
    if (versions.containsKey("value")) {
      for (Object versionObj : versions.getJsonArray("value")) {
        if (((JsonObject) versionObj).getString(Field.ID.getName(), "").equals(toFindVersionId)) {
          return true;
        }
      }
    }

    return false;
  }

  public String getFileId() {
    return fileId;
  }

  public String getDriveId() {
    return driveId;
  }
}
