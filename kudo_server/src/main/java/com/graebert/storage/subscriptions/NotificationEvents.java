package com.graebert.storage.subscriptions;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.mail.emails.renderers.MentionRender;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Notifications;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

public class NotificationEvents extends DynamoBusModBase {
  private static final Supplier<Long> TTL = TtlUtils::inOneWeek;
  private static final OperationGroup operationGroup = OperationGroup.DYNAMO_BUS_MODE;
  // Let's use !! instead of ! because "!" is used in OD's fileId
  private static final String skDelimiter = "!!";
  public static String address = "notificationevents";

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".newCommentThread", this::doNewCommentThread);
    eb.consumer(address + ".threadDeleted", this::doCommentThreadDeleted);
    eb.consumer(address + ".threadResolved", this::doThreadResolved);
    eb.consumer(address + ".threadReopened", this::doThreadReopened);
    eb.consumer(address + ".threadTitleChanged", this::doThreadTitleChanged);
    eb.consumer(address + ".entitiesChanged", this::doEntitiesChanged);

    eb.consumer(address + ".newMarkup", this::doNewMarkup);
    eb.consumer(address + ".modifiedMarkup", this::doModifiedMarkup);
    eb.consumer(address + ".markupResolved", this::doMarkupResolved);
    eb.consumer(address + ".markupDeleted", this::doMarkupDeleted);
    eb.consumer(address + ".markupActive", this::doMarkupActivated);

    eb.consumer(address + ".newComment", this::doNewComment);
    eb.consumer(address + ".modifiedComment", this::doModifiedComment);
    eb.consumer(address + ".deletedComment", this::doDeletedComment);

    eb.consumer(address + ".modifiedFile", this::doModifiedFile);

    eb.consumer(address + ".getFileNotifications", this::doGetFileNotifications);
    eb.consumer(address + ".getNotifications", this::doGetNotifications);

    eb.consumer(address + ".markNotificationsAsRead", this::doMarkNotificationsAsRead);
    eb.consumer(address + ".markFileNotificationsAsRead", this::doMarkFileNotificationsAsRead);
  }

  private void saveNewNotification(
      String fileId,
      String storageType,
      String userId,
      Item event,
      Entity segment,
      String requiredScope,
      JsonArray mentionedUsers) {
    JsonObject subscriptionsFilter =
        new JsonObject().put(Field.FILE_ID.getName(), fileId).put("requiredScope", requiredScope);
    // dumb check to distinguish DS and AK
    final String proName = config.getProperties().getSmtpProName();
    if (proName.contains("Kudo")) {
      eb_request(
          segment,
          Subscriptions.address + ".getSubscriptionsWithFilter",
          subscriptionsFilter,
          subscriptionEvent -> {
            if (subscriptionEvent.succeeded()) {
              if (subscriptionEvent.result().body() instanceof JsonObject
                  && Field.ERROR
                      .getName()
                      .equals(((JsonObject) subscriptionEvent.result().body())
                          .getString(Field.STATUS.getName()))) {
                // error
                XRayEntityUtils.addException(
                    segment, new Exception("Error on getting subscriptions with filter"));
              } else {
                Entity subsegment =
                    XRayManager.createSubSegment(operationGroup, segment, "notificationevents");
                JsonObject subscriptions =
                    (JsonObject) subscriptionEvent.result().body();
                JsonArray subscriptionsList = subscriptions.getJsonArray("subscriptions");

                event.withString(Field.STORAGE_TYPE.getName(), storageType);
                for (Object subscription : subscriptionsList) {
                  JsonObject subscriptionValue = (JsonObject) subscription;
                  String user = subscriptionValue.getString(Field.SK.getName());
                  if (!user.equals(userId)
                      && (mentionedUsers == null || !mentionedUsers.contains(user))) {
                    Notifications.putNotification(
                        event.withString(Field.PK.getName(), "notificationevents#" + user));
                    eb_send(
                        segment,
                        NotifyUser.address + ".newUserNotification",
                        new JsonObject().put(Field.USER_ID.getName(), user));
                  }
                }
                XRayManager.endSegment(subsegment);
              }
            } else {
              XRayEntityUtils.addException(
                  segment, new Exception("Error on getting subscriptions with filter"));
            }
          });
    }
  }

  private void iterateNotifications(
      Entity segment,
      Message<JsonObject> message,
      QuerySpec qs,
      long mills,
      boolean groupByFileIds) {
    JsonObject results = new JsonObject();

    Notifications.getNotifications(qs).forEachRemaining(item -> {
      String[] names = item.getString(Field.SK.getName()).split("#");
      String thread = names.length >= 2 ? names[1].split(skDelimiter)[0] : "";
      String author = item.getString("source");
      String storageType = item.getString(Field.STORAGE_TYPE.getName());
      JsonObject data = new JsonObject(item.getJSON(Field.DATA.getName()));
      long tmstmp = item.getLong("tmstmp");
      JsonObject event = new JsonObject()
          .put(Field.SK.getName(), item.getString(Field.SK.getName()))
          .put("event", item.getString("event"))
          .put(Field.USER.getName(), Users.getUserInfo(author))
          .put("tmstmp", tmstmp)
          .put(Field.DATA.getName(), data);
      switch (eventTypes.valueOf(item.getString("event"))) {
        case NEWCOMMENT:
        case MODIFIEDCOMMENT: {
          event = event.put(Field.ID.getName(), names[2]);
          break;
        }
        case MARKUPDELETED:
        case MARKUPRESOLVED:
        case MARKUPACTIVATED:
        case MODIFIEDMARKUP:
        case NEWMARKUP: {
          thread = "_MARKUP_" + thread;
          break;
        }
        case MODIFIEDFILE: {
          thread = "_MODIFIERS_";
          break;
        }
        default: {
          break;
        }
      }
      if (event != null) {
        if (groupByFileIds) {
          String fileId =
              storageType == null ? names[0] : StorageType.getShort(storageType) + "+" + names[0];
          if (fileId.contains(skDelimiter)) {
            fileId = fileId.split(skDelimiter)[0];
          }
          if (!results.containsKey(fileId)) {
            results.put(fileId, new JsonObject().put(thread, new JsonArray().add(event)));
          } else {
            if (!results.getJsonObject(fileId).containsKey(thread)) {
              results.getJsonObject(fileId).put(thread, new JsonArray().add(event));
            } else {
              results.getJsonObject(fileId).getJsonArray(thread).add(event);
            }
          }
        } else if (!results.containsKey(thread)) {
          results.put(thread, new JsonArray().add(event));
        } else {
          results.getJsonArray(thread).add(event);
        }
      }
    });

    sendOK(segment, message, results, mills);
  }

  private void doGetNotifications(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);

    if (userId == null) {
      return;
    }

    QuerySpec qs = new QuerySpec();
    qs.withFilterExpression("attribute_not_exists(#rd)")
        .withKeyConditionExpression("pk = :pk")
        .withNameMap(new NameMap().with("#rd", "read"))
        .withValueMap(new ValueMap().withString(":pk", "notificationevents#" + userId));
    iterateNotifications(segment, message, qs, mills, true);
  }

  private void doGetFileNotifications(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);

    if (fileId == null || userId == null) {
      return;
    }

    QuerySpec qs = new QuerySpec();
    qs.withFilterExpression("attribute_not_exists(#rd)")
        .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
        .withNameMap(new NameMap().with("#rd", "read"))
        .withValueMap(new ValueMap()
            .withString(":pk", "notificationevents#" + userId)
            .withString(":sk", fileId));
    iterateNotifications(segment, message, qs, mills, false);
  }

  private void doNewCommentThread(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    // let's have title and text as optional params
    String title = json.getString("title");
    String text = json.getString("text");

    if (fileId == null || userId == null || threadId == null) {
      return;
    }
    JsonObject additionalInfo = new JsonObject().put("additional", "NA");

    if (title != null && !title.isEmpty()) {
      additionalInfo.put("title", title);
    }

    if (text != null && !text.isEmpty()) {
      additionalInfo.put("text", text);
    }

    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.NEWTHREAD.toString())
        .withJSON(Field.DATA.getName(), additionalInfo.toString())
        .withString("source", userId)
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    JsonArray mentionedUsers = json.getJsonArray("mentionedUsers");
    // notify all subscribed users of the file
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        mentionedUsers);

    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doThreadTitleChanged(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    String title = getRequiredString(segment, "title", message, json);
    String oldTitle = getRequiredString(segment, "oldTitle", message, json);

    if (fileId == null || userId == null || threadId == null || title == null || oldTitle == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.THREADTITLEMODIFIED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put("title", title).put("oldTitle", oldTitle).toString())
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doEntitiesChanged(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);

    if (fileId == null || userId == null || threadId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.ENTITIESCHANGED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.DESCRIPTION.getName(), "NA").toString())
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doThreadResolved(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);

    if (fileId == null || userId == null || threadId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.THREADRESOLVED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.DESCRIPTION.getName(), "NA").toString())
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doThreadReopened(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);

    if (fileId == null || userId == null || threadId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.THREADREOPENED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.DESCRIPTION.getName(), "NA").toString())
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doCommentThreadDeleted(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);

    if (fileId == null || userId == null || threadId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.THREADDELETED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.DESCRIPTION.getName(), "NA").toString())
        .withString(Field.SK.getName(), fileId + "#" + threadId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doNewMarkup(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);

    if (fileId == null || userId == null || markupId == null || type == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.NEWMARKUP.toString())
        .withString("source", userId)
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.TYPE.getName(), type).toString())
        .withString(Field.SK.getName(), fileId + "#" + markupId + skDelimiter + timestamp);

    // notify all subscribed users of the file
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MARKUP + "#" + markupId,
        null);

    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doModifiedMarkup(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);
    if (fileId == null || userId == null || markupId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.MODIFIEDMARKUP.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.TYPE.getName(), type).toString())
        .withString(Field.SK.getName(), fileId + "#" + markupId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MARKUP + "#" + markupId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doMarkupActivated(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);
    if (fileId == null || userId == null || markupId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.MARKUPACTIVATED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.TYPE.getName(), type).toString())
        .withString(Field.SK.getName(), fileId + "#" + markupId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MARKUP + "#" + markupId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doMarkupResolved(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);
    if (fileId == null || userId == null || markupId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.MARKUPRESOLVED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.TYPE.getName(), type).toString())
        .withString(Field.SK.getName(), fileId + "#" + markupId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MARKUP + "#" + markupId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doMarkupDeleted(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);
    if (fileId == null || userId == null || markupId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.MARKUPDELETED.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.TYPE.getName(), type).toString())
        .withString(Field.SK.getName(), fileId + "#" + markupId + skDelimiter + timestamp);

    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MARKUP + "#" + markupId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doModifiedFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);

    if (fileId == null || userId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.MODIFIEDFILE.toString())
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject().put(Field.DESCRIPTION.getName(), "NA").toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString(Field.SK.getName(), fileId + skDelimiter + timestamp);

    // notify all subscribed users of the file except the source of the event
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.MODIFICATIONS.toString(),
        null);

    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doNewComment(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    String commentId = getRequiredString(segment, Field.COMMENT_ID.getName(), message, json);
    String text = getRequiredString(segment, "text", message, json);

    if (fileId == null || userId == null || threadId == null || commentId == null || text == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.NEWCOMMENT.toString())
        .withString("source", userId)
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject()
                .put("text", MentionRender.createMentionTextForEmail(text, null, true))
                .toString())
        .withString(
            Field.SK.getName(),
            fileId + "#" + threadId + skDelimiter + timestamp + "#" + commentId);

    JsonArray mentionedUsers = json.getJsonArray("mentionedUsers");
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        mentionedUsers);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doModifiedComment(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    String commentId = getRequiredString(segment, Field.COMMENT_ID.getName(), message, json);
    String text = json.getString("text");
    String oldText = json.getString("oldText");

    if (fileId == null || userId == null || threadId == null || commentId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    eventTypes type = commentId.equals(Field.ROOT.getName())
        ? eventTypes.THREADMODIFIED
        : eventTypes.MODIFIEDCOMMENT;
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", type.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(
            Field.DATA.getName(),
            new JsonObject()
                .put("text", MentionRender.createMentionTextForEmail(text, null, true))
                .put("oldText", MentionRender.createMentionTextForEmail(oldText, null, true))
                .toString())
        .withString(
            Field.SK.getName(),
            fileId + "#" + threadId + skDelimiter + timestamp + "#" + commentId);

    JsonArray mentionedUsers = json.getJsonArray("mentionedUsers");
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        mentionedUsers);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doDeletedComment(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    String commentId = getRequiredString(segment, Field.COMMENT_ID.getName(), message, json);
    String text = json.getString("text");

    if (fileId == null || userId == null || threadId == null || commentId == null) {
      return;
    }
    long timestamp = GMTHelper.utcCurrentTime();
    Item event = new Item()
        .withLong("tmstmp", timestamp)
        .withLong(Field.TTL.getName(), TTL.get())
        .withString("event", eventTypes.DELETEDCOMMENT.toString())
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString("source", userId)
        .withJSON(Field.DATA.getName(), new JsonObject().put("text", text).toString())
        .withString(
            Field.SK.getName(),
            fileId + "#" + threadId + skDelimiter + timestamp + "#" + commentId);
    saveNewNotification(
        fileId,
        storageType,
        userId,
        event,
        segment,
        Subscriptions.subscriptionScope.THREAD + "#" + threadId,
        null);
    sendOK(segment, message, new JsonObject().put("events", new JsonArray()), mills);
  }

  private void doMarkNotificationsAsRead(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    Long timestamp = json.getLong(Field.TIMESTAMP.getName());

    if (userId == null) {
      return;
    }
    log.info("Marking notifications as read for user " + userId + " before: " + timestamp);

    QuerySpec qs = new QuerySpec();
    if (timestamp == null) {
      qs.withFilterExpression("attribute_not_exists(#rd)")
          .withKeyConditionExpression("pk = :pk")
          .withNameMap(new NameMap().with("#rd", "read"))
          .withValueMap(new ValueMap().withString(":pk", "notificationevents#" + userId));
    } else {
      qs.withFilterExpression("attribute_not_exists(#rd) and tmstmp < :time")
          .withKeyConditionExpression("pk = :pk")
          .withNameMap(new NameMap().with("#rd", "read"))
          .withValueMap(new ValueMap()
              .withString(":pk", "notificationevents#" + userId)
              .withLong(":time", timestamp));
    }
    markNotificationsRead(message, segment, mills, qs);
  }

  private void doMarkFileNotificationsAsRead(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    Long timestamp = json.getLong(Field.TIMESTAMP.getName());

    if (fileId == null || userId == null) {
      return;
    }

    QuerySpec qs = new QuerySpec();
    if (timestamp == null) {
      qs.withFilterExpression("attribute_not_exists(#rd)")
          .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
          .withNameMap(new NameMap().with("#rd", "read"))
          .withValueMap(new ValueMap()
              .withString(":pk", "notificationevents#" + userId)
              .withString(":sk", fileId));
    } else {
      qs.withFilterExpression("attribute_not_exists(#rd) and tmstmp < :time")
          .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
          .withNameMap(new NameMap().with("#rd", "read"))
          .withValueMap(new ValueMap()
              .withString(":pk", "notificationevents#" + userId)
              .withString(":sk", fileId)
              .withLong(":time", timestamp));
    }
    markNotificationsRead(message, segment, mills, qs);
  }

  private void markNotificationsRead(
      Message<JsonObject> message, Entity segment, long mills, QuerySpec qs) {
    Iterator<Item> notificationIterator = Notifications.getNotifications(qs);
    List<Item> lst = new ArrayList<>();
    while (notificationIterator.hasNext()) {
      Item item = notificationIterator.next();
      if (!item.hasAttribute("read")) {
        item.withBoolean("read", true);
        lst.add(item);
      }
    }

    Dataplane.batchWriteListItems(lst, Notifications.tableName, DataEntityType.NOTIFICATIONS);

    JsonObject result = new JsonObject();
    result.put("records", lst.size());
    sendOK(segment, message, result, mills);
  }

  public enum eventTypes {
    NEWTHREAD,
    THREADDELETED,
    THREADRESOLVED,
    THREADREOPENED,
    THREADTITLEMODIFIED,
    NEWMARKUP,
    MODIFIEDMARKUP,
    MARKUPDELETED,
    MARKUPRESOLVED,
    MARKUPACTIVATED,
    NEWCOMMENT,
    MODIFIEDCOMMENT,
    DELETEDCOMMENT,
    MODIFIEDFILE,
    THREADMODIFIED,
    ENTITIESCHANGED
  }
}
