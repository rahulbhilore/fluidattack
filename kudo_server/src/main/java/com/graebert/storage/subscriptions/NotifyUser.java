package com.graebert.storage.subscriptions;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class NotifyUser extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.NOTIFY_USER;
  public static String address = "usernotifications";
  private boolean notifyUsers = true;

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".sendPendingUserNotifications", this::doSendPendingUserNotifications);
    eb.consumer(address + ".newUserNotification", this::doNewUserNotification);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-notifications");

    notifyUsers = config.getProperties().getNotificationsEnabled();

    if (devMode
        || Regions.getCurrentRegion().getName().equals(config.getProperties().getSesRegion())) {
      // every 5 minutes (for debug once a minute)
      int minutes = 5;
      if (devMode) {
        minutes = 1;
      }
      vertx.setPeriodic(1000 * 60 * minutes, event -> {
        // not sure we really want to do this on every host and rather do that centrally.
        findUsersToMessage();
      });
    }
  }

  private void doNewUserNotification(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);

    if (userId == null) {
      return;
    }
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(segment, message, Utils.getLocalizedString(message, "FL3"), 400);
      return;
    }
    Users.saveUserPendingMessages(userId);

    sendOK(segment, message, new JsonObject(), mills);
  }

  private void findUsersToMessage() {
    Entity segment =
        XRayManager.createIndependentStandaloneSegment(operationGroup, "findUsersToMessage");
    List<String> users = Users.getUsersWithPendingMessages();
    if (Utils.isListNotNullOrEmpty(users)) {
      for (String userId : users) {
        log.info("[ NOTIFICATIONS ] findUsersToMessage: user " + userId + " has pending messages.");
        Item user = Users.getUserById(userId);
        if (user != null
            && user.hasAttribute(Field.ENABLED.getName())
            && user.getBoolean(Field.ENABLED.getName())) {
          decideToNotifyUser(segment, user);
        }
      }
    }
    XRayManager.endSegment(segment);
  }

  private void decideToNotifyUser(Entity segment, Item user) {
    JsonObject userJson = Users.userToJsonWithoutGoogleId(
        user,
        config.getProperties().getEnterprise(),
        config.getProperties().getInstanceOptionsAsJson(),
        config.getProperties().getDefaultUserOptionsAsJson(),
        config.getProperties().getDefaultLocale(),
        config.getProperties().getUserPreferencesAsJson(),
        config.getProperties().getDefaultCompanyOptionsAsJson());

    long lastMessage = 0;
    if (user.hasAttribute("lastMessageSent")) {
      lastMessage = user.getLong("lastMessageSent");
    }
    String frequency = userJson
        .getJsonObject(Field.PREFERENCES.getName())
        .getString("frequencyOfGettingEmailNotifications");
    if (notifyUsers) {
      switch (frequency) {
        case "Immediately_on_change":
          // more than 5 minutes have passed
          if ((GMTHelper.utcCurrentTime() - lastMessage) > 1000 * 60 * 3) {
            log.info(
                "[ NOTIFICATIONS ] decideToNotifyUser: user " + user.getString(Field.SK.getName())
                    + " has pending messages and elapsed time > 3 min.");
            eb_send(
                segment,
                address + ".sendPendingUserNotifications",
                new JsonObject().put(Field.USER_ID.getName(), user.getString(Field.SK.getName())));
          }
          break;
        case "Hourly":
          // more than 1 hour has passed
          if ((GMTHelper.utcCurrentTime() - lastMessage) > 1000 * 60 * 60) {
            log.info(
                "[ NOTIFICATIONS ] decideToNotifyUser: user " + user.getString(Field.SK.getName())
                    + " has pending messages and elapsed time > 1 hour.");
            eb_send(
                segment,
                address + ".sendPendingUserNotifications",
                new JsonObject().put(Field.USER_ID.getName(), user.getString(Field.SK.getName())));
          }
          break;
        default:
        case "Daily":
          // more than 1 day has passed
          if ((GMTHelper.utcCurrentTime() - lastMessage) > 1000 * 60 * 60 * 24) {
            log.info(
                "[ NOTIFICATIONS ] decideToNotifyUser: user " + user.getString(Field.SK.getName())
                    + " has pending messages and elapsed time > 24 hours.");
            eb_send(
                segment,
                address + ".sendPendingUserNotifications",
                new JsonObject().put(Field.USER_ID.getName(), user.getString(Field.SK.getName())));
          }
          break;
        case "Never":
          // we shouldn't notify user, but I think we may want to clear the queue.
          //          if ((GMTHelper.utcCurrentTime() - lastMessage) > 1000 * 60 * 60 * 24) {
          //            log.info("[ NOTIFICATIONS ] decideToNotifyUser: user " +
          // user.getString(Field.SK.getName())
          //                     + " has pending messages, but shouldn't be notified (elapsed time >
          // 24 "
          //                     + "hours)");
          //          }
          clearUsersMessageStatus(segment, user.getString(Field.SK.getName()));
          // always empty the queue in case of never (WB-28)
          break;
      }
    }
  }

  private void clearUsersMessageStatus(Entity segment, final String userId) {
    if (userId == null) {
      return;
    }

    if (Users.getUserByIdFromDB(userId) == null) {
      return;
    }

    List<String> users = Users.getUsersWithPendingMessages();
    if (Utils.isListNotNullOrEmpty(users) && !users.contains(userId)) {
      return;
    }
    log.info("[ NOTIFICATIONS ] Clearing users' notifications status. UserID: " + userId);
    // reset timer
    long currentTime = Users.setLastMessageSent(userId);
    Users.removeUserPendingMessages(userId);

    eb_send(
        segment,
        NotificationEvents.address + ".markNotificationsAsRead",
        new JsonObject()
            .put(Field.USER_ID.getName(), userId)
            .put(Field.TIMESTAMP.getName(), currentTime));
  }

  private void doSendPendingUserNotifications(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    boolean isUserTable = json.containsKey("isUserTable") ? json.getBoolean("isUserTable") : false;

    if (userId == null) {
      return;
    }
    Item user = Users.getUserByIdFromDB(userId);
    if (user == null) {
      sendError(segment, message, Utils.getLocalizedString(message, "FL3"), 400);
      return;
    }
    List<String> users = Users.getUsersWithPendingMessages();
    log.info("[ NOTIFICATIONS ] Check if message is pending: " + users.contains(userId));
    // before we do anything, let make sure we are still pending.
    // ignore for local
    if (Utils.isListNotNullOrEmpty(users) && !users.contains(userId)) {
      if (!isUserTable) {
        sendError(segment, message, Utils.getLocalizedString(message, "FL8"), 400);
        return;
      }
    }

    long currentTime = GMTHelper.utcCurrentTime();

    eb_request(segment, NotificationEvents.address + ".getNotifications", json, event -> {
      Entity subSegment =
          XRayManager.createSubSegment(operationGroup, segment, "processNotifications");
      boolean isSent = true;
      log.info("[ NOTIFICATIONS ] Get notifications finished: " + event.succeeded());
      if (event.succeeded()) {
        JsonObject results = (JsonObject) event.result().body();
        log.info("[ NOTIFICATIONS ] Get notifications results: " + results.encode());
        if (results.size() > 0) {
          // results format : fileId->threadId->events[]
          for (Map.Entry<String, Object> item : results) {
            String fullId = item.getKey();
            if (!fullId.equals(Field.STATUS.getName())) {
              JsonObject id = Utils.parseItemId(fullId, Field.FILE_ID.getName());
              Item metadata = FileMetaData.getMetaData(
                  id.getString(Field.FILE_ID.getName()),
                  id.getString(Field.STORAGE_TYPE.getName()));
              log.info("[ NOTIFICATIONS ] File metadata: "
                  + (metadata != null ? metadata.toString() : null) + " fileId: " + fullId);
              if (metadata == null) {
                isSent = false;
              } else {
                // check thumbnails
                String thumbnail = metadata.getString(Field.THUMBNAIL.getName());
                if (Utils.isStringNotNullOrEmpty(thumbnail)) {
                  String cloudfrontDistribution = config.getProperties().getCfDistribution();
                  String region = config.getProperties().getS3Region();
                  S3Regional s3Regional = new S3Regional(config, cloudfrontDistribution, region);
                  boolean doesThumbnailExist = s3Regional.doesObjectExistInBucket(
                      cloudfrontDistribution, "png/" + thumbnail);
                  // https://graebert.atlassian.net/browse/XENON-67098
                  // if thumbnail isn't generated for quite some time - let's send notification
                  // anyway
                  // item.getValue() -> {thread1:[...],thread2:[...]} -> event description object
                  // -> tmstmp
                  // we need to find the latest
                  long latestTimestamp = 0;

                  // if we're waiting for more than maxDifference - send
                  final long maxDifference = TimeUnit.MINUTES.toMillis(5);

                  // if thumbnail exists - all fine, we can proceed as usual
                  if (!doesThumbnailExist) {
                    try {
                      JsonObject threads = (JsonObject) item.getValue();
                      // threadId -> events[]
                      Iterator<Map.Entry<String, Object>> threadsIterator =
                          threads.stream().iterator();
                      while (threadsIterator.hasNext()) {
                        Map.Entry<String, Object> threadObject = threadsIterator.next();
                        JsonArray eventsInThread = (JsonArray) threadObject.getValue();
                        Iterator<Object> eventsIterator =
                            eventsInThread.stream().iterator();
                        while (eventsIterator.hasNext()) {
                          JsonObject eventObject = (JsonObject) eventsIterator.next();
                          if (eventObject.containsKey("tmstmp")
                              && eventObject.getLong("tmstmp") > latestTimestamp) {
                            latestTimestamp = eventObject.getLong("tmstmp");
                          }
                        }
                      }
                    } catch (Exception ex) {
                      log.error(
                          "[ NOTIFICATIONS ] Exception iterating events to find timestamp", ex);
                    }
                    log.info(
                        "[ NOTIFICATIONS ] Found latest timestamp:" + latestTimestamp + " Diff is: "
                            + (System.currentTimeMillis() - latestTimestamp) + " maxDifference: "
                            + maxDifference);
                  }

                  if (doesThumbnailExist
                      || (latestTimestamp > 0
                          && System.currentTimeMillis() - latestTimestamp > maxDifference)) {
                    log.info("[ NOTIFICATIONS ] Going to send email for file: " + fullId
                        + " thumbnail exists: " + doesThumbnailExist + " timestamp check: "
                        + latestTimestamp + " elapsed time: "
                        + (System.currentTimeMillis() - latestTimestamp));
                    // item.getValue() -> {thread1:[...],thread2:[...]}, item.getKey() -> fileId
                    // if we've found a metadata - we can mark all notifications for file as read
                    eb_send(
                        subSegment,
                        NotificationEvents.address + ".markFileNotificationsAsRead",
                        json.put(Field.FILE_ID.getName(), id.getString(Field.FILE_ID.getName()))
                            .put(Field.TIMESTAMP.getName(), currentTime));
                    eb_send(
                        subSegment,
                        MailUtil.address + ".notifyFileChanges",
                        new JsonObject()
                            .put(Field.LOCALE.getName(), user.getString(Field.LOCALE.getName()))
                            .put(Field.USER_ID.getName(), userId)
                            .put(Field.FILE_ID.getName(), fullId)
                            .put(Field.RESULTS.getName(), item.getValue())
                            .put(
                                Field.FILE_NAME.getName(),
                                metadata.getString(Field.FILE_NAME.getName()))
                            .put(
                                Field.THUMBNAIL.getName(),
                                metadata.getString(Field.THUMBNAIL.getName()))
                            .put("doesThumbnailExist", doesThumbnailExist)
                            .put(
                                Field.STORAGE_TYPE.getName(),
                                metadata.getString(Field.SK.getName()))
                            .put(
                                Field.EXTERNAL_ID.getName(),
                                findExternalId(
                                    segment,
                                    message,
                                    new JsonObject()
                                        .put(Field.USER_ID.getName(), userId)
                                        .put(Field.FILE_ID.getName(), fullId),
                                    StorageType.getStorageType(
                                        metadata.getString(Field.SK.getName())))));
                  } else {
                    isSent = false;
                    log.warn(
                        "[ NOTIFICATIONS ] Email isn't sent because thumbnail for file: " + fullId
                            + " isn't ready yet. Checked ID: " + thumbnail + ". Timestamp check: "
                            + latestTimestamp + " elapsed time: "
                            + (System.currentTimeMillis() - latestTimestamp));
                  }
                } else {
                  isSent = false;
                  log.warn(
                      "[ NOTIFICATIONS ] Email isn't sent because there's no thumbnail info in "
                          + "file's metadata for file: " + fullId);
                }
              }
            }
          }
        } else {
          isSent = false;
        }
      } else {
        isSent = false;
      }
      if (isSent) {
        // mark all read. Just to be sure
        // ignore locally
        eb_send(
            subSegment,
            NotificationEvents.address + ".markNotificationsAsRead",
            json.put(Field.TIMESTAMP.getName(), currentTime));
        // update this time
        if (!isUserTable) {
          Users.setLastMessageSent(userId);
          Users.removeUserPendingMessages(userId);
        }
      }
      XRayManager.endSegment(subSegment);
      sendOK(segment, message, new JsonObject(), mills);
    });
  }
}
