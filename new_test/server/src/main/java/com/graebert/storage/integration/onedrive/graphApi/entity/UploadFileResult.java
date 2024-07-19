package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import jakarta.xml.bind.DatatypeConverter;

public class UploadFileResult {
  final String versionId;
  final String driveId;
  final String fileId;
  final Long uploadDate;

  public UploadFileResult(JsonObject result, GraphId ids) {
    fileId = result.getString(Field.ID.getName());
    versionId = GraphUtils.getVersionId(result);
    uploadDate = DatatypeConverter.parseDateTime(
            result.getString(Field.LAST_MODIFIED_DATE_TIME.getName()))
        .getTime()
        .getTime();

    String driveId = ids.getDriveId();
    if (driveId == null) {
      driveId = result.containsKey(Field.PARENT_REFERENCE.getName())
              && result
                  .getJsonObject(Field.PARENT_REFERENCE.getName())
                  .containsKey(Field.DRIVE_ID.getName())
          ? result
              .getJsonObject(Field.PARENT_REFERENCE.getName())
              .getString(Field.DRIVE_ID.getName())
          : null;
    }

    this.driveId = driveId;
  }

  public String getVersionId() {
    return versionId;
  }

  public String getDriveId() {
    return driveId;
  }

  public String getFileId() {
    return fileId;
  }

  public Long getUploadDate() {
    return uploadDate;
  }
}
