package com.graebert.storage.subscriptions;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.stats.logs.subscription.SubscriptionActions;
import com.graebert.storage.stats.logs.subscription.SubscriptionLog;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.SubscriptionsDao;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// Notify all other users who has previously opened a file when a new comment thread is created.
// Note: technically, openings of files by users are saved in the Field.FILES.getName() statistics.
// Notify all other participants of a comment thread when...
// ... a comment within that thread is created, changed or deleted.
// ... thread name is changed
// ... an entity is added or deleted

public class Subscriptions extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.SUBSCRIPTIONS;

  public static String address = "subscriptions";

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getSubscription", this::doGetSubscription);
    eb.consumer(address + ".addSubscription", this::doAddSubscription);
    eb.consumer(address + ".deleteSubscription", this::doDeleteSubscription);
    eb.consumer(address + ".removePublicSubscriptions", this::doRemovePublicSubscriptions);
    eb.consumer(address + ".getSubscriptionsWithFilter", this::doGetSubscriptionsWithFilter);
  }

  private void doGetSubscriptionsWithFilter(Message<JsonObject> message) {
    // we may need to generify this later
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String requiredScope = json.getString("requiredScope");

    if (fileId == null) {
      return;
    }

    // find active subscriptions for file
    // have to declare those 2 as variables to alter them with scope if necessary
    sendOK(
        segment,
        message,
        new JsonObject()
            .put("subscriptions", SubscriptionsDao.getSubscriptionsJson(fileId, requiredScope)),
        mills);
  }

  private boolean validateLink(String fileId, String storedToken) {
    try {
      PublicLink publicLink = new PublicLink(fileId);
      if (publicLink.findLinkByToken(storedToken)) {
        long expirationTime = publicLink.getExpirationTime();
        String token = publicLink.getToken();
        if (expirationTime > 0 && expirationTime < GMTHelper.utcCurrentTime()) {
          return false;
        } else {
          return token.equals(storedToken);
        }
      } else {
        return false;
      }
    } catch (Exception e) {
      log.error("Error on Subscriptions::validateLink fileId:" + fileId + " token:" + storedToken);
      log.error(e);
      return false;
    }
  }

  private void doGetSubscription(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);

    final boolean validateLink =
        json.getBoolean("validateLink") != null ? json.getBoolean("validateLink") : false;
    if (fileId == null || userId == null) {
      return;
    }
    Item subscription = SubscriptionsDao.getSubscription(userId, fileId);
    JsonObject responseObject = new JsonObject().put("subscription", new JsonObject());
    if (subscription != null) {
      if (validateLink
          && Utils.isStringNotNullOrEmpty(subscription.getString(Field.TOKEN.getName()))) {
        if (!this.validateLink(fileId, subscription.getString(Field.TOKEN.getName()))) {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("deleteSubscription")
              .run((Segment blockingSegment) -> {
                eb_send(
                    blockingSegment,
                    Subscriptions.address + ".deleteSubscription",
                    new JsonObject()
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.FILE_ID.getName(), fileId)
                        .put("manual", false));
              });
          sendOK(segment, message, responseObject, mills);
          return;
        }
      }
      // getList may provide null, so we need to check or it will produce NPE
      List<String> scope = subscription.getList("scope");
      JsonArray scopeResponse = new JsonArray();
      if (scope != null && !scope.isEmpty()) {
        scopeResponse = new JsonArray(scope);
      }
      JsonObject subscriptionInfo = new JsonObject()
          .put(Field.STATE.getName(), subscription.getString(Field.STATE.getName()))
          .put(Field.TIMESTAMP.getName(), subscription.getLong("tmstmp"))
          .put("scope", scopeResponse);
      if (Utils.isStringNotNullOrEmpty(subscription.getString(Field.TOKEN.getName()))) {
        subscriptionInfo.put(Field.TOKEN.getName(), subscription.getString(Field.TOKEN.getName()));
      }
      responseObject.put("subscription", subscriptionInfo);
    }
    sendOK(segment, message, responseObject, mills);
  }

  private void doAddSubscription(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    final List<String> scope = json.getJsonArray("scope").getList();
    final scopeUpdate scopeUpdateMode = scopeUpdate.valueOf(json.getString("scopeUpdate"));
    final boolean manual = json.getBoolean("manual") != null ? json.getBoolean("manual") : false;
    String token = json.getString(Field.TOKEN.getName());

    if (fileId == null || userId == null) {
      return;
    }

    SubscriptionLog subLog = null;
    try {
      subLog = new SubscriptionLog(
          userId,
          fileId,
          StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
          "no_data",
          GMTHelper.utcCurrentTime(),
          true,
          null,
          null,
          json.getString(Field.DEVICE.getName()));
    } catch (Exception ignored) {
    }

    Item oldSubscription = SubscriptionsDao.getSubscription(userId, fileId);
    Item subscription = oldSubscription;
    // if there wasn't subscription - add it
    if (oldSubscription == null) {
      long timestamp = GMTHelper.utcCurrentTime();
      subscription = new Item()
          .withPrimaryKey(Field.PK.getName(), "subscriptions#" + fileId, Field.SK.getName(), userId)
          .withLong("tmstmp", timestamp)
          .withString(Field.STATE.getName(), subscriptionState.ACTIVE.name());
      if (scope != null && !scope.isEmpty()) {
        subscription.withList("scope", scope);
      }
      // we should include token for links in email
      if (Utils.isStringNotNullOrEmpty(token)) {
        subscription.withString(Field.TOKEN.getName(), token);
      }

      log.info("Created new subscription: " + subscription.toJSONPretty());

      // we should add into cache for https://graebert.atlassian.net/browse/XENON-35808
      SubscriptionsDao.putSubscription(subscription);
      sendOK(
          segment,
          message,
          new JsonObject().put("subscriptions", subscriptionState.NEW.name()),
          mills);

      Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

      if (subLog != null) {
        try {
          subLog.setSubscriptionAction(
              manual
                  ? SubscriptionActions.SUBSCRIBED_MANUALLY
                  : SubscriptionActions.SUBSCRIBED_AUTOMATICALLY);
          subLog.sendToServer();
        } catch (Exception e) {
          log.error(e);
        }
      }
      XRayManager.endSegment(blockingSegment);
    } else if (oldSubscription.hasAttribute(Field.STATE.getName())
        && !oldSubscription
            .getString(Field.STATE.getName())
            .equals(subscriptionState.ACTIVE.name())) {
      // if subscription was deleted - we should re-activate it, but only if it wasn't manually
      // removed!
      // if subscription state has been changed manually and this isn't "manual" request - don't
      // change anything
      if (oldSubscription.hasAttribute("manual")
          && oldSubscription.getBoolean("manual")
          && !manual) {
        sendError(
            segment,
            message,
            new JsonObject().put("subscriptions", subscriptionState.DELETED),
            400,
            "SUB1");
      } else {
        // otherwise - set it to active and update with scope.
        if (Utils.isListNotNullOrEmpty(scope)) {
          SubscriptionsDao.updateScopeAndToken(scope, scopeUpdateMode, token, oldSubscription);
        }
        subscription = SubscriptionsDao.setSubscriptionState(
            subscriptionState.ACTIVE.name(),
            oldSubscription.getString(Field.PK.getName()),
            oldSubscription.getString(Field.SK.getName()));
        log.info("Updated deleted subscription: " + subscription.toJSONPretty());
        sendOK(
            segment,
            message,
            new JsonObject().put("subscriptions", subscriptionState.ACTIVE.name()),
            mills);

        Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

        if (subLog != null) {
          try {
            subLog.setSubscriptionAction(
                manual
                    ? SubscriptionActions.SUBSCRIBED_MANUALLY
                    : SubscriptionActions.SUBSCRIBED_AUTOMATICALLY);
            subLog.sendToServer();
          } catch (Exception e) {
            log.error(e);
          }
        }

        XRayManager.endSegment(blockingSegment);
      }
    } else if (Utils.isListNotNullOrEmpty(scope)) {
      // if this is just scope update
      subscription =
          SubscriptionsDao.updateScopeAndToken(scope, scopeUpdateMode, token, oldSubscription);
      log.info("Update subscription's scope: " + subscription.toJSONPretty());
      sendOK(
          segment,
          message,
          new JsonObject().put("subscriptions", subscriptionState.ACTIVE.name()),
          mills);
      if (subLog != null) {
        try {
          subLog.setSubscriptionAction(SubscriptionActions.SUBSCRIPTION_UPDATE);
          subLog.sendToServer();
        } catch (Exception e) {
          log.error(e);
        }
      }
    } else {
      sendOK(
          segment,
          message,
          new JsonObject().put("subscriptions", subscriptionState.EXISTING.name()),
          mills);
    }
  }

  private void doDeleteSubscription(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    final boolean manual = json.getBoolean("manual") != null ? json.getBoolean("manual") : false;

    if (fileId == null || userId == null) {
      return;
    }

    SubscriptionLog subLog = null;
    try {
      subLog = new SubscriptionLog(
          userId,
          fileId,
          StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
          "no_data",
          GMTHelper.utcCurrentTime(),
          true,
          null,
          null,
          json.getString(Field.DEVICE.getName()));
    } catch (Exception ignored) {
    }

    JsonObject responseObject = new JsonObject().put("subscription", new JsonObject());

    Item subscription = SubscriptionsDao.getSubscription(userId, fileId);
    if (subscription != null) {
      // let's save manual from the request assuming that we may have programmatic update at some
      // point
      subscription
          .withString(Field.STATE.getName(), subscriptionState.DELETED.name())
          .withBoolean("manual", manual);

      SubscriptionsDao.putSubscription(subscription);

      JsonObject subscriptionInfo = new JsonObject()
          .put(Field.STATE.getName(), subscription.getString(Field.STATE.getName()))
          .put(Field.TIMESTAMP.getName(), subscription.getLong("tmstmp"));
      responseObject.getJsonObject("subscription").mergeIn(subscriptionInfo);
      // let's track only if we've had a subscription

      if (subLog != null) {
        try {
          subLog.setSubscriptionAction(
              manual
                  ? SubscriptionActions.UNSUBSCRIBED_MANUALLY
                  : SubscriptionActions.UNSUBSCRIBED_AUTOMATICALLY);
          subLog.sendToServer();
        } catch (Exception e) {
          log.error(e);
        }
      }
    }
    sendOK(segment, message, responseObject, mills);
  }

  private void doRemovePublicSubscriptions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String token = getRequiredString(segment, Field.TOKEN.getName(), message, json);
    if (fileId == null || token == null) {
      return;
    }

    Iterator<Item> subscriptionsWithToken =
        SubscriptionsDao.getPublicSubscriptions(fileId, token, subscriptionState.ACTIVE);
    List<Item> items = new ArrayList<>();
    while (subscriptionsWithToken.hasNext()) {
      Item subscriptionItem = subscriptionsWithToken.next();

      SubscriptionLog subLog;
      try {
        subLog = new SubscriptionLog(
            "no_data",
            fileId,
            StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
            "no_data",
            GMTHelper.utcCurrentTime(),
            true,
            SubscriptionActions.UNSUBSCRIBED_AUTOMATICALLY,
            "Link revoked by owner",
            json.getString(Field.DEVICE.getName()));
        subLog.sendToServer();
      } catch (Exception e) {
        log.error(e);
      }

      subscriptionItem
          .withString(Field.STATE.getName(), subscriptionState.DELETED.name())
          .withBoolean("manual", false);
      items.add(subscriptionItem);
      SubscriptionsDao.deleteSubscription(subscriptionItem);
    }
    final int subscriptionsSize = items.size();

    if (subscriptionsSize > 0) {
      Dataplane.batchWriteListItems(items, SubscriptionsDao.tableName, DataEntityType.SUBSCRIPTION);
    }

    System.out.println("Removed subscriptions: " + subscriptionsSize);

    sendOK(segment, message, new JsonObject().put("subscriptionsSize", subscriptionsSize), mills);
  }

  public enum subscriptionState {
    ACTIVE,
    DELETED,
    EXISTING,
    NEW
  }

  public enum subscriptionScope {
    THREAD,
    MARKUP,
    GLOBAL,
    MODIFICATIONS
  }

  public enum scopeUpdate {
    APPEND,
    REWRITE
  }
}
