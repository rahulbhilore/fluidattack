package com.graebert.storage.users;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.intercom.api.Intercom;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.RequestBodyEntity;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

public class IntercomConnection {
  public static final String logPrefix = "[ INTERCOM ]";

  private static final String APIVersion = "1.4";
  private static JsonArray allTags = null;
  private static final List<String> kudoStorageTags = Arrays.asList(
      "KUDO_BOX",
      "KUDO_DROPBOX",
      "KUDO_GDRIVE",
      "KUDO_HANCOM",
      "KUDO_HANCOMSTG",
      "KUDO_ONEDRIVE",
      "KUDO_ONEDRIVEBUS",
      "KUDO_ONEDRIVEBUSINESS",
      "KUDO_ONSHAPE",
      "KUDO_ONSHAPENONPROD",
      "KUDO_SHAREPOINT",
      "KUDO_TRIMBLE",
      "KUDO_WEBDAV");

  public static void setToken(final String intercomAccessToken) {
    if (Utils.isStringNotNullOrEmpty(intercomAccessToken)) {
      Intercom.setToken(intercomAccessToken);
    }
  }

  private static GetRequest getRequest(final String query) {
    return Unirest.get(Intercom.getApiBaseURI().toString() + (query != null ? query : ""))
        .header(HttpHeaders.AUTHORIZATION.toString(), "Bearer " + Intercom.getToken())
        .header("Intercom-Version", APIVersion)
        .header(HttpHeaders.ACCEPT.toString(), "application/json");
  }

  private static RequestBodyEntity postRequest(final String query, final JsonObject body) {
    return Unirest.post(Intercom.getApiBaseURI().toString() + (query != null ? query : ""))
        .header(HttpHeaders.AUTHORIZATION.toString(), "Bearer " + Intercom.getToken())
        .header("Intercom-Version", APIVersion)
        .header(HttpHeaders.ACCEPT.toString(), "application/json")
        .header(HttpHeaders.CONTENT_TYPE.toString(), "application/json")
        .body(new JSONObject(body.toString()));
  }

  private static void loadAllTags() throws Exception {
    HttpResponse<String> response = getRequest("tags").asString();
    if (response.getStatus() != 200) {
      throw new Exception(String.format(
          "Error - %s | %s | Tags cannot be loaded", response.getStatusText(), response.getBody()));
    }
    allTags = new JsonObject(response.getBody()).getJsonArray("tags");
  }

  public static JsonObject getUserInfo(final String userId) throws Exception {
    HttpResponse<String> response = getRequest("users/?user_id=" + userId).asString();
    if (response.getStatus() != 200) {
      throw new Exception(String.format(
          "Error - %s | %s | User not found", response.getStatusText(), response.getBody()));
    }
    return new JsonObject(response.getBody());
  }

  public static void updateUserTags(
      final String userId,
      final JsonObject user,
      final ItemCollection<QueryOutcome> externalAccounts)
      throws Exception {
    loadAllTags();
    JsonArray userTags = null;
    List<String> userTagNames = new ArrayList<>();
    if (user.containsKey("tags") && user.getJsonObject("tags").containsKey("tags")) {
      userTags = user.getJsonObject("tags").getJsonArray("tags");
      for (Object tag : userTags) {
        userTagNames.add(((JsonObject) tag).getString(Field.NAME.getName()));
      }
    }
    List<String> addedTags = new ArrayList<>();

    for (Item account : externalAccounts) {
      StorageType type = StorageType.getStorageType(account.getString(Field.F_TYPE.getName()));
      String tagName;
      switch (type) {
        case ONSHAPEDEV:
        case ONSHAPESTAGING:
          tagName = "KUDO_ONSHAPENONPROD";
          break;
        case ONEDRIVEBUSINESS:
          tagName = "KUDO_ONEDRIVEBUS";
          break;
        default:
          tagName = String.format("KUDO_%s", type.name().toUpperCase());
          break;
      }
      addedTags.add(tagName);

      // check if user is already tagged
      if (userTagNames.contains(tagName)) {
        continue;
      }

      // create a tag if it doesn't exist
      String finalTagName = tagName;
      JsonObject tagToAdd = (JsonObject) allTags.stream()
          .filter(tag ->
              ((JsonObject) tag).getString(Field.NAME.getName()).equalsIgnoreCase(finalTagName))
          .findAny()
          .orElse(null);
      if (tagToAdd == null) {
        tagToAdd = new JsonObject().put(Field.NAME.getName(), tagName);
        HttpResponse<String> createdTag = postRequest("tags", tagToAdd).asString();
        if (createdTag.getStatus() != 200) {
          throw new Exception(String.format(
              "Error - %s | %s | Cannot create tag with name %s",
              createdTag.getStatusText(), createdTag.getBody(), tagName));
        }
        kudoStorageTags.add(tagName);
      }

      // Tag the user
      HttpResponse<String> userTagResponse = postRequest(
              "tags",
              tagToAdd.put(
                  Field.USERS.getName(),
                  new JsonArray().add(new JsonObject().put("user_id", userId))))
          .asString();
      if (userTagResponse.getStatus() != 200) {
        throw new Exception(String.format(
            "Error - %s | %s | Cannot tag user with tag %s",
            userTagResponse.getStatusText(), userTagResponse.getBody(), tagName));
      }
    }

    if (userTags != null) {
      // find tags to remove
      // iterate through all user tags
      for (String tag : userTagNames) {
        // if it wasn't re-added - remove
        // AS - Only untag the kudo storage tags if not active
        if (kudoStorageTags.contains(tag) && !addedTags.contains(tag)) {
          // Untag the user
          HttpResponse<String> untagUserRequest = postRequest(
                  "tags",
                  new JsonObject()
                      .put(Field.NAME.getName(), tag)
                      .put(
                          Field.USERS.getName(),
                          new JsonArray()
                              .add(new JsonObject().put("user_id", userId).put("untag", true))))
              .asString();

          if (untagUserRequest.getStatus() != 200) {
            throw new Exception(String.format(
                "Error - %s | %s | Cannot untag user with tag %s",
                untagUserRequest.getStatusText(), untagUserRequest.getBody(), tag));
          }
        }
      }
    }
  }

  public static void sendEvent(
      String eventName,
      String userId,
      Map<String, Object> metadata,
      String intercomAccessToken,
      String intercomAppId) {
    Thread intercomThread = new Thread(() -> {
      try {
        sendEventWithoutThread(eventName, userId, metadata, intercomAccessToken);
      } catch (Exception e) {
        DynamoBusModBase.log.error(
            String.format(
                "%s exception on sending event through thread. App ID: %s Exception: %s",
                logPrefix, intercomAppId, e.getMessage()),
            e);
      }
    });
    intercomThread.start();
  }

  private static void sendEventWithoutThread(
      String eventName, String userId, Map<String, Object> metadata, String intercomAccessToken)
      throws Exception {
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      throw new IllegalArgumentException("User id isn't specified");
    }
    IntercomConnection.setToken(intercomAccessToken);

    // base event structure
    JsonObject event = new JsonObject()
        .put("event_name", eventName)
        .put("user_id", userId)
        .put("created_at", GMTHelper.utcCurrentTime() / 1000L);

    // event metadata
    if (metadata != null) {
      for (String key : metadata.keySet()) {
        Object value = metadata.get(key);
        if (value instanceof StorageType) {
          value = value.toString();
        }
        event.put("metadata", new JsonObject().put(key, value));
      }
    }

    HttpResponse<String> response = postRequest("events", event).asString();
    if (!response.isSuccess()) {
      throw new Exception(String.format(
          "Error - %s | %s | Cannot create event with name %s for user %s",
          response.getStatusText(), response.getBody(), eventName, userId));
    }
  }
}
