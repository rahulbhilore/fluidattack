package com.graebert.storage.integration.onedrive.graphApi.entity;

public class CloneObjectResult {
  private final String driveId;
  private final String objectId;
  private final String parentId;

  public CloneObjectResult(String objectId, String driveId, String parentId) {
    this.driveId = driveId;
    this.objectId = objectId;
    this.parentId = parentId;
  }

  public String getDriveId() {
    return driveId;
  }

  public String getObjectId() {
    return objectId;
  }

  public String getParentId() {
    return parentId;
  }
}
