package com.graebert.storage.xray;

public enum OperationGroup {
  UNNAMED("UNNAMED"),

  // system
  DYNAMO_BUS_MODE("DYNAMO_BUS_MODE"),
  STORAGE_VERTICLE("STORAGE_VERTICLE"),
  BASE_STORAGE("BASE_STORAGE"),
  RESPONSE_TIME_HANDLER_STATS_D("RESPONSE_TIME_HANDLER_STATS_D"),
  REQUEST_LOG_HANDLER("REQUEST_LOG_HANDLER"),
  RATE_LIMITATION_HANDLER("RATE_LIMITATION_HANDLER"),

  // services
  THUMBNAILS("THUMBNAILS"),
  PERIODIC("PERIODIC"),
  MAIL("MAIL"),
  TMPL("TMPL"),
  AUTH_MANAGER("AUTH_MANAGER"),
  X_SESSION_MANAGER("X_SESSION_MANAGER"),
  COMMENTS("COMMENTS"),
  NOTIFICATION_EVENTS("NOTIFICATION_EVENTS"),
  NOTIFY_USER("NOTIFY_USER"),
  WS_MANAGER("WS_MANAGER"),
  MESSAGING_MANAGER("MESSAGING_MANAGER"),
  USERS("USERS"),
  SUBSCRIPTIONS("SUBSCRIPTIONS"),
  UNIREST("UNIREST"),
  STATS("STATS"),
  RECENT("RECENT"),
  DATAPLANE("DATAPLANE"),

  // resources
  RESOURCES("RESOURCES"),
  TEMPLATES("TEMPLATES"),
  LISPS("LISPS"),
  FONTS("FONTS"),
  BLOCK_LIBRARIES("BLOCK_LIBRARIES"),

  // externals
  GRID_FS_MODULE("GRID_FS_MODULE"),
  INTERNAL("INTERNAL"),
  BOX("BOX"),
  DROPBOX("DROPBOX"),
  GDRIVE("GDRIVE"),
  TRIMBLE("TRIMBLE"),
  ONSHAPE("ONSHAPE"),
  ONSHAPEDEV("ONSHAPEDEV"),
  ONSHAPESTAGING("ONSHAPESTAGING"),
  ONEDRIVE("ONEDRIVE"),
  ONEDRIVEBUSINESS("ONEDRIVEBUSINESS"),
  SHAREPOINT("SHAREPOINT"),
  WEBDAV("WEBDAV"),
  NEXTCLOUD("NEXTCLOUD"),
  SAMPLES("SAMPLES"),
  HANCOM("HANCOM"),
  HANCOMSTG("HANCOMSTG");

  private final String name;

  OperationGroup(String name) {
    this.name = name;
  }

  public String getName() {
    return this.name;
  }
}
