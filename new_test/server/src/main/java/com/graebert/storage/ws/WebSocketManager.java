package com.graebert.storage.ws;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import kong.unirest.HttpResponse;
import kong.unirest.UnirestException;

public class WebSocketManager extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.WS_MANAGER;
  public static final String address = "websocketmanager";
  private String url;
  private String apikey;
  private boolean active;

  @Override
  public void start() throws Exception {
    super.start();

    active = config.getProperties().getWebsocketEnabled();
    apikey = config.getProperties().getWebsocketApikey();
    url = config.getProperties().getWebsocketUrl();

    eb.consumer(
        address + ".newVersion", (Message<JsonObject> message) -> doNotify(message, "newVersion"));
    eb.consumer(
        address + ".deleted",
        (Message<JsonObject> message) -> doNotify(message, Field.DELETED.getName()));
    eb.consumer(
        address + ".deshared", (Message<JsonObject> message) -> doNotify(message, "deshared"));
    eb.consumer(address + ".shared", (Message<JsonObject> message) -> doNotify(message, "shared"));
    eb.consumer(
        address + ".sessionRequested",
        (Message<JsonObject> message) -> doNotify(message, "sessionRequested"));
    eb.consumer(
        address + ".sessionDenied",
        (Message<JsonObject> message) -> doNotify(message, "sessionDenied"));
    eb.consumer(
        address + ".sessionDeleted",
        (Message<JsonObject> message) -> doNotify(message, "sessionDeleted"));
    eb.consumer(
        address + ".sessionDowngrade",
        (Message<JsonObject> message) -> doNotify(message, "sessionDowngrade"));
    eb.consumer(
        address + ".sharedLinkOn",
        (Message<JsonObject> message) -> doNotify(message, "sharedLinkOn"));
    eb.consumer(
        address + ".sharedLinkOff",
        (Message<JsonObject> message) -> doNotify(message, "sharedLinkOff"));
    eb.consumer(
        address + ".commentsUpdate",
        (Message<JsonObject> message) -> doNotify(message, "commentsUpdate"));
    eb.consumer(
        address + ".markupsUpdate",
        (Message<JsonObject> message) -> doNotify(message, "markupsUpdate"));
    eb.consumer(
        address + ".exportStateUpdated",
        (Message<JsonObject> message) -> doNotify(message, "exportStateUpdated"));

    eb.consumer(
        address + ".oldSessionRemoved",
        (Message<JsonObject> message) -> doNotifyUser(message, "oldSessionRemoved"));
    eb.consumer(
        address + ".storageAdd",
        (Message<JsonObject> message) -> doNotifyUser(message, "storageAdd"));
    eb.consumer(
        address + ".storageAddError",
        (Message<JsonObject> message) -> doNotifyUser(message, "storageAddError"));
    eb.consumer(
        address + ".storageRemove",
        (Message<JsonObject> message) -> doNotifyUser(message, "storageRemove"));
    eb.consumer(
        address + ".objectDeleted",
        (Message<JsonObject> message) -> doNotifyUser(message, "objectDeleted"));
    eb.consumer(
        address + ".objectCreated",
        (Message<JsonObject> message) -> doNotifyUser(message, "objectCreated"));
    eb.consumer(
        address + ".objectRenamed",
        (Message<JsonObject> message) -> doNotifyUser(message, "objectRenamed"));
    eb.consumer(
        address + ".accessGranted",
        (Message<JsonObject> message) -> doNotifyUser(message, "accessGranted"));
    eb.consumer(
        address + ".accessRemoved",
        (Message<JsonObject> message) -> doNotifyUser(message, "accessRemoved"));
    eb.consumer(
        address + ".languageSwitch",
        (Message<JsonObject> message) -> doNotifyUser(message, "languageSwitch"));
    eb.consumer(
        address + ".optionsChanged",
        (Message<JsonObject> message) -> doNotifyUser(message, "optionsChanged"));
    eb.consumer(
        address + ".backgroundColorChanged",
        (Message<JsonObject> message) -> doNotifyUser(message, "backgroundColorChanged"));
    eb.consumer(
        address + ".logout", (Message<JsonObject> message) -> doNotifyUser(message, "logout"));
    eb.consumer(
        address + ".recentsUpdate",
        (Message<JsonObject> message) -> doNotifyUser(message, "recentsUpdated"));
    eb.consumer(
        address + ".newThumbnail",
        (Message<JsonObject> message) -> doNotifyUser(message, "newThumbnail"));
    eb.consumer(
        address + ".comparisonReady",
        (Message<JsonObject> message) -> doNotifyUser(message, "comparisonReady"));
    eb.consumer(
        address + ".sampleUsageUpdated",
        (Message<JsonObject> message) -> doNotifyUser(message, "sampleUsageUpdated"));

    // resources
    eb.consumer(
        address + ".resourceUpdated",
        (Message<JsonObject> message) -> doNotifyUser(message, "resourceUpdated"));
    eb.consumer(
        address + ".resourceDeleted",
        (Message<JsonObject> message) -> doNotifyUser(message, "resourceDeleted"));

    eb.consumer(
        address + ".storageDisabled",
        (Message<JsonObject> message) ->
            doNotifyAllUsers(message, Field.STORAGE_DISABLED.getName()));
    eb.consumer(
        address + ".storageEnabled",
        (Message<JsonObject> message) -> doNotifyAllUsers(message, "storageEnabled"));

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-websockets");
  }

  private void doNotifyUser(Message<JsonObject> message, String messageType) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    if (!active) {
      XRayManager.endSegment(segment);
      return;
    }

    JsonObject json = message.body();
    log.info("[WS_USER] Received message: " + json.encodePrettily());
    String sessionId = json.getString(Field.SESSION_ID.getName());
    String userId = json.getString(Field.USER_ID.getName());
    String itemId = json.getString(Field.ITEM_ID.getName());
    String parentId = json.getString(Field.PARENT_ID.getName());
    String action = json.getString(Field.ACTION.getName());
    String thumbnail = json.getString(Field.THUMBNAIL.getName());
    String role = json.getString(Field.ROLE.getName());
    String backgroundColor = json.getString(Field.BACKGROUND_COLOR.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    JsonArray collaborators = json.getJsonArray(Field.COLLABORATORS.getName());
    if (collaborators == null) {
      collaborators = new JsonArray();
    }

    String infoMessage;
    switch (messageType) {
      case "oldSessionRemoved":
        infoMessage = "An old session is removed";
        break;
      case "storageAdd":
        infoMessage = "Connected a new external storage";
        break;
      case "storageAddError":
        infoMessage = "Error occurred while connecting a new external storage";
        break;
      case "storageRemove":
        infoMessage = "Disconnected an external storage";
        break;
      case "objectCreated":
        infoMessage = "An object was created";
        break;
      case "objectDeleted":
        infoMessage = "An object was deleted";
        break;
      case "objectRenamed":
        infoMessage = "An object was renamed";
        break;
      case "accessGranted":
        infoMessage = "An access to an object was granted";
        break;
      case "accessRemoved":
        infoMessage = "An access to an object was revoked";
        break;
      case "languageSwitch":
        infoMessage = "A language has been switched";
        break;
      case "optionsChanged":
        infoMessage = "Options have been changed";
        break;
      case "backgroundColorChanged":
        infoMessage = "Background color has been changed";
        break;
      case "logout":
        infoMessage = "The user is logged out";
        break;
      case "recentsUpdated":
        infoMessage = "Recent files have been updated";
        break;
      case "newThumbnail":
        infoMessage = "New thumbnail received";
        break;
      case "comparisonReady":
        infoMessage = "Comparison is ready";
        break;
      case "resourceUpdated":
        infoMessage = "Resource has been updated";
        break;
      case "resourceDeleted":
        infoMessage = "Resource has been deleted";
        break;
      case "sampleUsageUpdated":
        infoMessage = "Sample usage have been updated";
        break;
      default:
        infoMessage = emptyString;
        break;
    }
    JsonObject info = new JsonObject()
        .put(Field.MESSAGE_TYPE.getName(), messageType)
        .put(Field.MESSAGE.getName(), infoMessage);
    if (Utils.isStringNotNullOrEmpty(sessionId)) {
      info.put(Field.SESSION_ID.getName(), sessionId);
    }
    if (Utils.isStringNotNullOrEmpty(itemId)) {
      info.put(Field.ITEM_ID.getName(), itemId);
    }
    if (Utils.isStringNotNullOrEmpty(parentId)) {
      info.put(Field.PARENT_ID.getName(), parentId);
    }
    if (Utils.isStringNotNullOrEmpty(action)) {
      info.put(Field.ACTION.getName(), action);
    }
    if (Utils.isStringNotNullOrEmpty(thumbnail)) {
      info.put(Field.THUMBNAIL.getName(), thumbnail);
    }
    if (Utils.isStringNotNullOrEmpty(storageType)) {
      info.put(Field.STORAGE_TYPE.getName(), storageType);
    }
    if (Utils.isStringNotNullOrEmpty(role)) {
      info.put(Field.ROLE.getName(), role);
    }
    if (Utils.isStringNotNullOrEmpty(backgroundColor)) {
      info.put(Field.BACKGROUND_COLOR.getName(), backgroundColor);
    }
    if (json.containsKey(Field.IS_FOLDER.getName())) {
      info.put(Field.IS_FOLDER.getName(), json.getBoolean(Field.IS_FOLDER.getName(), false));
    }
    if (messageType.equals("sampleUsageUpdated")) {
      info.put(Field.USAGE.getName(), json.getLong(Field.USAGE.getName()));
      info.put(Field.QUOTA.getName(), json.getLong(Field.QUOTA.getName()));
      info.put(Field.EXTERNAL_ID.getName(), json.getString(Field.EXTERNAL_ID.getName()));
    }
    if (messageType.equals("comparisonReady")) {
      String jobId = json.getString(Field.JOB_ID.getName());
      String firstFile = json.getString("firstFile");
      String secondFile = json.getString("secondFile");
      if (Utils.isStringNotNullOrEmpty(jobId)) {
        info.put(Field.JOB_ID.getName(), jobId);
      }
      if (Utils.isStringNotNullOrEmpty(firstFile)) {
        info.put("firstFile", firstFile);
      }
      if (Utils.isStringNotNullOrEmpty(secondFile)) {
        info.put("secondFile", secondFile);
      }
    }
    if (messageType.contains("resource") && json.containsKey("resourceInfo")) {
      info.mergeIn(json.getJsonObject("resourceInfo"));
    }
    try {
      String fileId = json.getString(Field.FILE_ID.getName());
      if (Utils.isStringNotNullOrEmpty(fileId)) {
        info.put(Field.FILE_ID.getName(), fileId);
      }
    } catch (Exception ignored) {
    }

    if (!messageType.equals("accessGranted")
        && !messageType.equals("accessRemoved")
        && !messageType.equals("logout")
        && !messageType.equals("recentsUpdated")) {
      collaborators.add(userId);
    }
    Set<String> set = new HashSet<String>(collaborators.getList());
    log.info("[WS_USER] Going to send: " + info.encode() + " to: " + String.join(",", set));
    if (!collaborators.isEmpty()) {
      // get all users' sessions
      set.forEach(collabId -> {
        for (Iterator<Item> it = Sessions.getAllUserSessions(collabId); it.hasNext(); ) {
          Item session = it.next();
          log.info("[WS_USER] Going to send to: " + session.getString(Field.SK.getName()));
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName(webSocketPrefix + "notifyUser")
              .runWithoutSegment(() -> {
                HttpResponse<String> response = AWSXRayUnirest.post(
                        url + "wsnotifyuser/" + session.getString(Field.SK.getName()),
                        "websocketManager.doNotifyUserWithCollaborators)")
                    .header("x-api-key", apikey)
                    .body(info.encodePrettily())
                    .asString();
                log.info("[WS_USER] " + infoMessage + " res: " + response.getBody() + " status: "
                    + response.getStatus());
                if (response.getStatus() != 200) {
                  log.error("notify websocket failed: " + response.getBody());
                }
              });
        }
      });
    } else
    // send notification only for the particular session
    {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "notifyuser")
          .runWithoutSegment(() -> {
            HttpResponse<String> response = AWSXRayUnirest.post(
                    url + "wsnotifyuser/" + sessionId, "websocketManager.doNotifyUser")
                .header("x-api-key", apikey)
                .body(info.encodePrettily())
                .asString();
            if (response.getStatus() != 200) {
              log.error("notify websocket failed: " + response.getBody());
            }
          });
    }
    XRayManager.endSegment(segment);
  }

  private void doNotifyAllUsers(Message<JsonObject> message, String messageType) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    if (!active) {
      XRayManager.endSegment(segment);
      return;
    }
    String storageType = message.body().getString(Field.STORAGE_TYPE.getName());
    JsonObject info = new JsonObject()
        .put(Field.MESSAGE_TYPE.getName(), messageType)
        .put(Field.STORAGE_TYPE.getName(), storageType);

    if (messageType.equals(Field.STORAGE_DISABLED.getName())) {
      info.put("infoMessage", "A storage is disabled");
    } else {
      info.put("infoMessage", "A storage is enabled");
    }

    try {
      log.info(String.format("Sending ws notification to all users...\n%s", info.encodePrettily()));
      HttpResponse<String> response = AWSXRayUnirest.post(
              url + "wsnotifyuser/__all__", "websocketManager.doNotifyAllUsers")
          .header("x-api-key", apikey)
          .body(info.encodePrettily())
          .asString();
      if (response.getStatus() != 200) {
        log.error("notify websocket failed: " + response.getBody());
      } else {
        log.info("Websocket notification sent to users : " + response.getBody());
      }
    } catch (UnirestException e) {
      log.error("Something went wrong in notifying websocket : " + e);
    }
    XRayManager.endSegment(segment);
  }

  private void doNotify(Message<JsonObject> message, String messageType) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    if (!active) {
      XRayManager.endSegment(segment);
      return;
    }

    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    fileId = Utils.parseItemId(fileId, Field.ID.getName()).getString(Field.ID.getName());
    String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    String xSessionId = jsonObject.getString(Field.X_SESSION_ID.getName());
    String requestXSessionId = jsonObject.getString(Field.REQUEST_X_SESSION_ID.getName());
    String applicantXSession = jsonObject.getString(Field.APPLICANT_X_SESSION_ID.getName());
    Long ttl = jsonObject.getLong(Field.TTL.getName());
    String mode = jsonObject.getString(Field.MODE.getName());
    Long timestamp = jsonObject.getLong(Field.TIMESTAMP.getName());
    String markupId = jsonObject.getString(Field.MARKUP_ID.getName());
    String threadId = jsonObject.getString(Field.THREAD_ID.getName());
    String commentId = jsonObject.getString(Field.COMMENT_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String deletedUserId = jsonObject.getString(Field.DELETED_USER_ID.getName());
    String type = jsonObject.getString(Field.TYPE.getName());
    String author = jsonObject.getString(Field.AUTHOR.getName());
    Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    String token = jsonObject.getString(Field.TOKEN.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String username = jsonObject.getString(Field.USERNAME.getName());
    String email = jsonObject.getString(Field.EMAIL.getName());
    String linkOwnerIdentity = jsonObject.getString(Field.LINK_OWNER_IDENTITY.getName());
    String infoMessage;
    switch (messageType) {
      case "newVersion":
        infoMessage = "New version id: " + versionId;
        break;
      case "deleted":
        infoMessage = "The file is deleted";
        break;
      case "deshared":
        infoMessage = "The file is deshared";
        break;
      case "shared":
        infoMessage = "The file is shared";
        break;
      case "sessionRequested":
        infoMessage = "Editor access has been requested";
        break;
      case "sessionDenied":
        infoMessage = "Editor access has been denied";
        break;
      case "sessionDeleted":
        infoMessage = "The session has been deleted";
        break;
      case "sessionDowngrade":
        infoMessage = "The session's mode has been downgraded";
        break;
      case "sharedLinkOff":
        infoMessage = "The public link has been switched off";
        break;
      case "sharedLinkOn":
        infoMessage = "The public link has been switched on";
        break;
      case "commentsUpdate":
        infoMessage = "The comments were updated";
        break;
      case "exportStateUpdated":
        infoMessage = "Export state has been updated";
        break;
      default:
        infoMessage = emptyString;
    }
    JsonObject info = new JsonObject()
        .put(Field.MESSAGE_TYPE.getName(), messageType)
        .put(Field.MESSAGE.getName(), infoMessage);
    if (xSessionId != null) {
      info.put(Field.X_SESSION_ID.getName(), xSessionId);
    }
    if (requestXSessionId != null) {
      info.put(Field.REQUEST_X_SESSION_ID.getName(), requestXSessionId);
    }
    if (applicantXSession != null) {
      info.put(Field.APPLICANT_X_SESSION_ID.getName(), applicantXSession);
    }
    if (ttl != null) {
      info.put(Field.TTL.getName(), ttl);
    }
    if (mode != null) {
      info.put(Field.MODE.getName(), mode);
    }
    if (jsonObject.containsKey(Field.FORCE.getName())) {
      info.put(Field.FORCE.getName(), jsonObject.getBoolean(Field.FORCE.getName(), false));
    }
    if (jsonObject.containsKey(Field.IS_MY_SESSION.getName())) {
      info.put(
          Field.IS_MY_SESSION.getName(),
          jsonObject.getBoolean(Field.IS_MY_SESSION.getName(), false));
    }
    if (timestamp != null) {
      info.put(Field.TIMESTAMP.getName(), timestamp);
    }
    if (threadId != null) {
      info.put(Field.THREAD_ID.getName(), threadId);
    }
    if (markupId != null) {
      info.put(Field.MARKUP_ID.getName(), markupId);
    }
    if (commentId != null) {
      info.put(Field.COMMENT_ID.getName(), commentId);
    }
    if (userId != null) {
      info.put(Field.USER_ID.getName(), userId);
    }
    if (type != null) {
      info.put(Field.TYPE.getName(), type);
    }
    if (fileId != null) {
      info.put(Field.FILE_ID.getName(), fileId);
    }
    if (author != null) {
      info.put(Field.AUTHOR.getName(), author);
    }
    if (Utils.isStringNotNullOrEmpty(token)) {
      info.put(Field.TOKEN.getName(), token);
    }
    if (collaborators != null && !collaborators.isEmpty()) {
      info.put(Field.COLLABORATORS.getName(), collaborators);
    }
    if (Utils.isStringNotNullOrEmpty(username)) {
      info.put(Field.USERNAME.getName(), username);
    }
    if (Utils.isStringNotNullOrEmpty(email)) {
      info.put(Field.EMAIL.getName(), email);
    }
    if (Utils.isStringNotNullOrEmpty(linkOwnerIdentity)) {
      info.put(Field.LINK_OWNER_IDENTITY.getName(), linkOwnerIdentity);
    }
    if (Utils.isStringNotNullOrEmpty(deletedUserId)) {
      info.put(Field.DELETED_USER_ID.getName(), deletedUserId);
    }
    if (jsonObject.containsKey("parentDeleted")) {
      info.put("parentDeleted", jsonObject.getBoolean("parentDeleted", true));
    }

    info.put(Field.EXPORT.getName(), export);
    try {
      log.info(
          String.format("Sending ws notification for %s...\n%s", fileId, info.encodePrettily()));
      HttpResponse<String> response = AWSXRayUnirest.post(
              url + "wsnotify/" + fileId, "websocketManager.doNotify")
          .header("x-api-key", apikey)
          .body(info.encodePrettily())
          .asString();
      if (response.getStatus() != 200) {
        log.error("notify websocket failed: " + response.getBody());
      }
    } catch (UnirestException e1) {
      log.error("Something went wrong in notifying websocket : " + e1);
    }

    XRayManager.endSegment(segment);
  }
}
