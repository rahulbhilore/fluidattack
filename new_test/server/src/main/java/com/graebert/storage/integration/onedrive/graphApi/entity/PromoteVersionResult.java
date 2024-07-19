package com.graebert.storage.integration.onedrive.graphApi.entity;

public class PromoteVersionResult {
  private final String versionId;
  private final String objectId;

  public PromoteVersionResult(String objectId, String versionId) {
    this.versionId = versionId;
    this.objectId = objectId;
  }

  public String getVersionId() {
    return versionId;
  }

  public String getObjectId() {
    return objectId;
  }
}
