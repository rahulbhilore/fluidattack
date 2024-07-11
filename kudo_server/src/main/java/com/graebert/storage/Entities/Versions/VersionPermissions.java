package com.graebert.storage.Entities.Versions;

import io.vertx.core.json.JsonObject;

public class VersionPermissions {
  private boolean isDownloadable = false;
  private boolean canRename = false;
  private boolean canPromote = false;
  private boolean canDelete = true;

  public boolean isDownloadable() {
    return isDownloadable;
  }

  public void setDownloadable(boolean downloadable) {
    isDownloadable = downloadable;
  }

  public boolean isCanRename() {
    return canRename;
  }

  public void setCanRename(boolean canRename) {
    this.canRename = canRename;
  }

  public boolean isCanPromote() {
    return canPromote;
  }

  public void setCanPromote(boolean canPromote) {
    this.canPromote = canPromote;
  }

  public JsonObject toJson() {
    return new JsonObject()
        .put("isDownloadable", isDownloadable)
        .put("canRename", canRename)
        .put("canDelete", canDelete)
        .put("canPromote", canPromote);
  }

  public void setCanDelete(boolean canDelete) {
    this.canDelete = canDelete;
  }
}
