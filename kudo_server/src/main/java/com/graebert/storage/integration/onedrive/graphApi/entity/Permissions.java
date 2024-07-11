package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.GraphApi;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class Permissions {
  private final JsonArray permissions;

  public Permissions(JsonArray permissions) {
    this.permissions = permissions;
  }

  public JsonObject getInheritedFrom() {
    return permissions.getJsonObject(0).getJsonObject("inheritedFrom");
  }

  public JsonArray getPermissions() {
    return permissions;
  }

  public boolean isViewOnly(GraphApi graphApi, StorageType storageType) {
    return (permissions == null
        || permissions.isEmpty()
        || permissions.stream().noneMatch(p -> {
          JsonObject per = (JsonObject) p;
          if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
            if (per.getJsonArray(Field.ROLES.getName()).contains(GraphUtils.WRITE)) {
              if (per.containsKey("grantedToIdentitiesV2")) {
                JsonArray users = per.getJsonArray("grantedToIdentitiesV2");
                if (!users.isEmpty()) {
                  for (Object u : users) {
                    JsonObject userObj = (JsonObject) u;
                    if (userObj.containsKey(Field.USER.getName())
                        && userObj
                            .getJsonObject(Field.USER.getName())
                            .containsKey(Field.EMAIL.getName())
                        && userObj
                            .getJsonObject(Field.USER.getName())
                            .getString(Field.EMAIL.getName())
                            .equals(graphApi.getEmail())) {
                      return true;
                    }
                  }
                }
              } else if (per.containsKey("grantedToV2")) {
                JsonObject userObj = per.getJsonObject("grantedToV2");
                return userObj.containsKey(Field.USER.getName())
                    && userObj
                        .getJsonObject(Field.USER.getName())
                        .containsKey(Field.EMAIL.getName())
                    && userObj
                        .getJsonObject(Field.USER.getName())
                        .getString(Field.EMAIL.getName())
                        .equals(graphApi.getEmail());
              }
            }
            return false;
          } else {
            return per.containsKey(Field.GRANTED_TO.getName())
                && per.getJsonObject(Field.GRANTED_TO.getName()).containsKey(Field.USER.getName())
                && per.getJsonObject(Field.GRANTED_TO.getName())
                    .getJsonObject(Field.USER.getName())
                    .getString(Field.ID.getName())
                    .equals(graphApi.getExternalId())
                && per.getJsonArray(Field.ROLES.getName()).contains(GraphUtils.WRITE);
          }
        }));
  }
}
