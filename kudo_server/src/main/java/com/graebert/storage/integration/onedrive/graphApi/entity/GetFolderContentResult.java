package com.graebert.storage.integration.onedrive.graphApi.entity;

import io.vertx.core.json.JsonObject;

public class GetFolderContentResult {
  private JsonObject result;

  public GetFolderContentResult(JsonObject result) {
    this.result = result;
  }
}
