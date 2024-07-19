package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class SiteInfo {
  private String name;

  public SiteInfo(JsonObject siteInfo) {
    this.name = siteInfo.containsKey(Field.DISPLAY_NAME.getName())
        ? siteInfo.getString(Field.DISPLAY_NAME.getName())
        : siteInfo.getString(Field.NAME.getName());
  }

  public String getName() {
    return name;
  }
}
