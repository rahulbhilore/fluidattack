package com.graebert.storage.config;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jetbrains.annotations.NonNls;

public class ServerConfigParser {
  private final JsonObject serverConfig;

  public ServerConfigParser(JsonObject serverConfig) {
    this.serverConfig = serverConfig;
  }

  public String getString(
      ConfigObject rootObject, String subParent, String field, String defValue) {
    if (serverConfig.isEmpty()) {
      return defValue;
    }
    JsonObject parentObj = serverConfig.getJsonObject(rootObject.getLabel());
    if (parentObj == null) {
      return defValue;
    }
    JsonObject subParentObject = parentObj.getJsonObject(subParent);
    if (subParentObject == null) {
      return defValue;
    }
    String value = subParentObject.getString(field);
    return value == null ? defValue : value;
  }

  public String getString(
      @NonNls ConfigObject rootObject, @NonNls String field, @NonNls String defValue) {
    if (serverConfig.isEmpty()) {
      return defValue;
    }
    JsonObject parentObj = serverConfig.getJsonObject(rootObject.getLabel());
    if (parentObj == null) {
      return defValue;
    }
    String value = parentObj.getString(field);
    return value == null ? defValue : value;
  }

  public Integer getInt(@NonNls ConfigObject rootObject, @NonNls String field, Integer defValue) {
    String str = getString(rootObject, field, defValue.toString());
    int value;
    try {
      value = Integer.parseInt(str);
    } catch (Exception e) {
      value = defValue;
    }
    return value;
  }

  public Boolean getBoolean(
      @NonNls ConfigObject rootObject, @NonNls String field, Boolean defValue) {
    String str = getString(rootObject, field, defValue.toString());
    boolean value;
    try {
      value = Boolean.parseBoolean(str);
    } catch (Exception e) {
      value = defValue;
    }
    return value;
  }

  public Boolean getBoolean(
      @NonNls ConfigObject rootObject,
      @NonNls String parent,
      @NonNls String field,
      Boolean defValue) {
    JsonObject rootObj = serverConfig.getJsonObject(rootObject.getLabel());
    if (rootObj == null) {
      return defValue;
    }
    JsonObject parentObj = rootObj.getJsonObject(parent);
    if (parentObj == null) {
      return defValue;
    }
    Boolean value = defValue;
    try {
      if (parentObj.containsKey(field)) {
        if (parentObj.getValue(field) instanceof String) {
          value = Boolean.valueOf(parentObj.getString(field));
        } else if (parentObj.getValue(field) instanceof Boolean) {
          value = parentObj.getBoolean(field);
        }
      }
    } catch (Exception ignore) {
    }
    return value;
  }

  public JsonArray getJsonArray(ConfigObject rootObject, String field, JsonArray defValue) {
    if (serverConfig.isEmpty()) {
      return defValue;
    }
    JsonObject parentObj = serverConfig.getJsonObject(rootObject.getLabel());
    if (parentObj == null) {
      return defValue;
    }
    JsonArray value = parentObj.getJsonArray(field);
    return value == null ? defValue : value;
  }

  public JsonArray getJsonArray(
      ConfigObject rootObject, String parent, String field, JsonArray defValue) {
    if (serverConfig.isEmpty()) {
      return defValue;
    }
    JsonObject rootObj = serverConfig.getJsonObject(rootObject.getLabel());
    if (rootObj == null) {
      return defValue;
    }
    JsonObject parentObj = rootObj.getJsonObject(parent);
    if (parentObj == null) {
      return defValue;
    }
    JsonArray value = parentObj.getJsonArray(field);
    return value == null ? defValue : value;
  }

  public JsonObject getJsonObject(@NonNls String field, JsonObject defValue) {
    if (serverConfig.isEmpty()) {
      return defValue;
    }
    try {
      return serverConfig.getJsonObject(field);
    } catch (Exception e) {
      return defValue;
    }
  }
}
