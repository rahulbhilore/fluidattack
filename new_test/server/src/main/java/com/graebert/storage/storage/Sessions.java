package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.xray.entities.Entity;
import com.google.common.collect.Lists;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.thumbnails.ThumbnailsDao;
import com.graebert.storage.users.IntercomConnection;
import com.graebert.storage.users.LicensingService;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.BaseVerticle;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.json.JsonObject;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import kong.unirest.HttpResponse;
import org.jetbrains.annotations.NonNls;

public class Sessions extends Dataplane {

  public static final String tableName = TableName.TEMP.name;
  public static final String sessionField = "SESSION#";
  private static final String mainTableName = emptyString;
  private static final String tableIndex = "sk-pk-index";
  private static final String sessionStatsField = "SESSIONSTATS";
  public static int sessionTTLInMinutes = 30;
  public static int xSessionTTLInMinutes = 52;
  public static int sessionRequestTTLInMinutes = 5;
  public static long MIN_UPDATE_TIME = Utils.MINUTE_IN_MS * 2;

  public static void cleanUpSession(BaseVerticle bv, Item session) {
    Entity subSegment =
        XRayManager.createSubSegment(OperationGroup.X_SESSION_MANAGER, "cleanUpSession");

    if (session == null || session.getString(sk) == null) {
      return;
    }
    if (session.hasAttribute("lstoken")) {
      try {
        // unbind from LS
        HttpResponse<String> unbindResult = LicensingService.unbind(
            config.getProperties().getRevision(), session, null, session.getString("lstoken"));
        if (Objects.nonNull(unbindResult) && !unbindResult.isSuccess()) {
          log.warn("[LS_API] unbind unsuccessful. Status code: " + unbindResult.getStatus()
              + ". Session info: " + session.toJSONPretty());
        }
      } catch (Exception ignored) {
      }
    } else {
      log.warn("[LS_API] No token for unbind. Session info: " + session.toJSONPretty());
    }
    // delete the item from the sessions table
    deleteItem(
        tableName, pk, session.getString(pk), sk, session.getString(sk), DataEntityType.SESSION);
    bv.eb_send(
        subSegment,
        WebSocketManager.address + ".logout",
        new JsonObject().put(Field.SESSION_ID.getName(), session.getString(sk)));

    Item userInfo = Users.getUserById(getUserIdFromPK(session.getString(pk)));
    if (userInfo != null
        && userInfo.hasAttribute(Field.GRAEBERT_ID.getName())
        && userInfo.hasAttribute(Field.INTERCOM_ACCESS_TOKEN.getName())) {
      String graebertId = userInfo.getString(Field.GRAEBERT_ID.getName());
      String intercomAccessToken = userInfo.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
      if (Utils.isStringNotNullOrEmpty(graebertId)
          && Utils.isStringNotNullOrEmpty(intercomAccessToken)) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("date", GMTHelper.utcCurrentTime());
        metadata.put(Field.SESSION_ID.getName(), session.getString(sk));
        IntercomConnection.sendEvent(
            "User session deleted",
            graebertId,
            metadata,
            intercomAccessToken,
            userInfo.getString(Field.INTERCOM_APP_ID.getName()));
      }
    }

    XRayManager.endSegment(subSegment);
  }

  public static String getUserIdFromPK(String pkValue) {
    return pkValue.substring(pkValue.indexOf("#") + 1);
  }

  public static boolean AllowAdminSessionId() {
    return Dataplane.AllowAdminSessionId();
  }

  public static Item getSessionById(final String sessionId) {
    if (!Utils.isStringNotNullOrEmpty(sessionId)) {
      return null;
    }
    Iterator<Item> itemIterator = query(
            tableName,
            tableIndex,
            new QuerySpec().withHashKey(sk, sessionId),
            DataEntityType.SESSION)
        .iterator();
    Item item;
    if (itemIterator.hasNext()) {
      item = itemIterator.next();
      if (Objects.nonNull(item) && filterSessionForTTL(item)) {
        return item;
      }
    }
    return null;
  }

  public static void setCurrentStorage(
      final String userId, final String sessionId, final String storage) {
    updateSession(
        userId, sessionId, "set fstorage = :val", new ValueMap().withJSON(":val", storage), null);
  }

  public static void setLicensingToken(
      final String userId, final String sessionId, final String licensingToken) {
    updateSession(
        userId,
        sessionId,
        "set lstoken = :val",
        new ValueMap().withString(":val", licensingToken),
        null);
  }

  public static void setGoogleId(
      final String userId, final String sessionId, final String externalId) {
    updateSession(
        userId, sessionId, "set gId = :val", new ValueMap().withString(":val", externalId), null);
  }

  public static void setOptions(final String userId, final String sessionId, final String options) {
    updateSession(
        userId, sessionId, "set options = :val", new ValueMap().withJSON(":val", options), null);
  }

  public static void updateLastActivityAndTTL(final String userId, final String sessionId) {
    updateSession(
        userId,
        sessionId,
        "set lastActivity = :val , #t = :val2",
        new ValueMap()
            .withLong(":val", GMTHelper.utcCurrentTime())
            .withLong(":val2", getSessionTTL()),
        new NameMap().with("#t", Field.TTL.getName()));
  }

  private static void updateSession(
      final String userId,
      final String sessionId,
      final String updateExpression,
      final ValueMap valueMap,
      final NameMap nameMap) {
    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(pk, sessionField + userId, sk, sessionId)
        .withUpdateExpression(updateExpression)
        .withValueMap(valueMap);

    if (nameMap != null) {
      updateItemSpec.withNameMap(nameMap);
    }
    updateItem(tableName, updateItemSpec, DataEntityType.SESSION);
  }

  public static void saveSession(final String sessionId, final Item newSessionItem) {
    if (newSessionItem != null) {
      putItem(
          tableName,
          newSessionItem.getString(pk),
          sessionId,
          newSessionItem,
          DataEntityType.SESSION);
    }
  }

  private static Item getStatItem() {
    Item sessionStat = getItem(
        mainTableName, pk, sessionStatsField, sk, sessionStatsField, true, DataEntityType.SESSION);
    if (sessionStat == null) {
      sessionStat = new Item().withPrimaryKey(pk, sessionStatsField, sk, sessionStatsField);
    }
    return sessionStat;
  }

  public static void refreshDeviceSessionCount(String device, int sessionCount) {
    if (device != null) {
      Item sessionStat = getStatItem();
      sessionStat.withInt(device.toLowerCase(), sessionCount);
      putItem(mainTableName, sessionStat, DataEntityType.SESSION);
    }
  }

  public static void updateDeviceSessionCount(String device, SessionMode mode) {
    if (device != null) {
      Item sessionStat = getStatItem();

      int sessionCount = sessionStat.hasAttribute(device.toLowerCase())
          ? sessionStat.getInt(device.toLowerCase())
          : 0;
      if (mode == SessionMode.ADDSESSION) {
        sessionCount += 1;
      } else {
        if (sessionCount > 0) {
          sessionCount -= 1;
        } else {
          sessionCount = 0;
        }
      }
      sessionStat.withInt(device.toLowerCase(), sessionCount);
      putItem(mainTableName, sessionStat, DataEntityType.SESSION);
    }
  }

  public static int getSessionTTL() {
    return (int)
        (GMTHelper.utcCurrentTime() / 1000 + TimeUnit.MINUTES.toSeconds(sessionTTLInMinutes));
  }

  public static int getXSessionTTL() {
    return (int)
        (GMTHelper.utcCurrentTime() / 1000 + TimeUnit.MINUTES.toSeconds(xSessionTTLInMinutes));
  }

  public static int getSessionRequestTTL() {
    return (int) (GMTHelper.utcCurrentTime() / 1000
        + TimeUnit.MINUTES.toSeconds(sessionRequestTTLInMinutes));
  }

  public static Item mergeSessionWithUserInfo(final Item session, final Item user) {
    JsonObject sessionObject = new JsonObject(session != null ? session.toJSON() : emptyString);
    JsonObject userObject = new JsonObject(user != null ? user.toJSON() : emptyString);
    userObject.remove(Field.PK.getName());
    userObject.remove(Field.SK.getName());
    JsonObject resultingObject = sessionObject.mergeIn(userObject);
    return Item.fromJSON(resultingObject.encode());
  }

  public static Iterator<Item> getAllUserSessions(final String userId) {
    QuerySpec querySpec = new QuerySpec().withHashKey(pk, sessionField + userId);
    filterSessionsQueryForTTL(querySpec);
    return query(tableName, null, querySpec, DataEntityType.SESSION).iterator();
  }

  public static Iterator<Item> getLastUserSession(final String userId) {
    QuerySpec querySpec = new QuerySpec()
        .withHashKey(pk, sessionField + userId)
        .withFilterExpression("lastActivity > :val")
        .withProjectionExpression(Field.DEVICE.getName())
        .withValueMap(new ValueMap()
            .withLong(":val", GMTHelper.utcCurrentTime() - TimeUnit.MINUTES.toMillis(2)));
    filterSessionsQueryForTTL(querySpec);
    return query(tableName, null, querySpec, DataEntityType.SESSION).iterator();
  }

  public static void removeFromCache(final String pkValue, final String sessionId) {
    deleteFromCache(tableName, pkValue, sessionId, DataEntityType.SESSION);
  }

  public static int countActiveSessions(final AuthManager.ClientType clientType) {
    Item sessionStat = getItem(
        mainTableName, pk, sessionStatsField, sk, sessionStatsField, true, DataEntityType.SESSION);
    if (sessionStat != null
        && clientType != null
        && sessionStat.hasAttribute(clientType.toString().toLowerCase())) {
      return sessionStat.getInt(clientType.toString().toLowerCase());
    }
    return 0;
  }

  // get user sessions for a device
  private static ItemCollection<QueryOutcome> getActiveUserDeviceSessions(
      final String userId, final String device) {
    QuerySpec querySpec = new QuerySpec()
        .withHashKey(Field.PK.getName(), sessionField + userId)
        .withFilterExpression("#device = :device")
        .withNameMap(new NameMap().with("#device", Field.DEVICE.getName()))
        .withValueMap(new ValueMap().withString(":device", device));
    filterSessionsQueryForTTL(querySpec);
    return query(tableName, null, querySpec, DataEntityType.SESSION);
  }

  public static Item getUserDeviceSessionOverLimit(String userId, String device) {
    // excluding limit for touch
    if (device.equals("TOUCH")) {
      return null;
    }

    ItemCollection<QueryOutcome> activeUserSessions = getActiveUserDeviceSessions(userId, device);
    // needs to check this to get Accumulated Item Count
    if (!activeUserSessions.iterator().hasNext()) {
      return null;
    }

    int noOfSessions = activeUserSessions.getAccumulatedItemCount();
    if (noOfSessions >= AuthManager.maxUserSessions) {
      List<Item> sessions = Lists.newArrayList(activeUserSessions.iterator());
      // get oldest user session based on its lastActivity
      Optional<Item> sessionOptional =
          sessions.stream().min(Comparator.comparing(i -> i.getLong("lastActivity")));
      return sessionOptional.orElse(null);
    }
    return null;
  }

  public enum SessionMode {
    ADDSESSION,
    REMOVESESSION
  }

  public static void updateSessionOnConnect(
      final Item externalAccount,
      final String userId,
      final StorageType storageType,
      final String externalId,
      final String sessionId) {
    updateSessionOnConnect(externalAccount, userId, storageType.toString(), externalId, sessionId);
  }

  public static void updateSessionOnConnect(
      final Item externalAccount,
      final String userId,
      final StorageType storageType,
      final String externalId,
      final String sessionId,
      final String storageObject) {
    updateSessionOnConnect(
        externalAccount, userId, storageType.toString(), externalId, sessionId, storageObject);
  }

  public static void updateSessionOnConnect(
      final Item externalAccount,
      final String userId,
      final String storageType,
      final String externalId,
      final String sessionId) {
    updateSessionOnConnect(
        externalAccount,
        userId,
        storageType,
        externalId,
        sessionId,
        Utils.getStorageObject(storageType, externalId, null, null));
  }

  public static void updateSessionOnConnect(
      final Item externalAccount,
      final String userId,
      final String storageType,
      final String externalId,
      final String sessionId,
      final String storageObject) {
    boolean doDisableThumbnail = ThumbnailsDao.doThumbnailDisabledForStorageOrURL(externalAccount);
    externalAccount.withBoolean("disableThumbnail", doDisableThumbnail);
    ExternalAccounts.saveExternalAccount(userId, externalId, externalAccount);
    @NonNls Item user = Users.getUserById(userId);
    user.withJSON(Field.F_STORAGE.getName(), storageObject);
    Users.setUserStorage(userId, storageObject);
    Sessions.setCurrentStorage(userId, sessionId, storageObject);

    // XENON-23932
    // Enable Editor automatically when user connects Onshape storage
    if (storageType.equals(StorageType.ONSHAPE.toString())) {
      JsonObject options;
      if (user.hasAttribute(Field.OPTIONS.getName())) {
        options = new JsonObject(user.getJSON(Field.OPTIONS.getName()));
      } else {
        options = config.getProperties().getDefaultUserOptionsAsJson();
      }
      options.put(Field.EDITOR.getName(), "true");
      user.withJSON(Field.OPTIONS.getName(), options.encode());
      Users.setUserOptions(userId, options.encode());
      Sessions.setOptions(userId, sessionId, options.encode());
    }
  }

  /**
   * Filter the query to check if items of the query results are already expired comparing the TTL and current time
   *
   * @param querySpec - Item to filter with session ttl
   */
  public static void filterSessionsQueryForTTL(QuerySpec querySpec) {
    long currentTime = GMTHelper.utcCurrentTime() / 1000;
    String filterExpression = emptyString;
    NameMap nameMap = new NameMap();
    ValueMap valueMap = new ValueMap();

    if (Objects.nonNull(querySpec.getFilterExpression())) {
      filterExpression = querySpec.getFilterExpression() + " and ";
    }

    if (Objects.nonNull(querySpec.getNameMap())) {
      Map<String, String> map = querySpec.getNameMap();
      for (String key : map.keySet()) {
        nameMap.with(key, map.get(key));
      }
    }

    if (Objects.nonNull(querySpec.getValueMap())) {
      Map<String, Object> map = querySpec.getValueMap();
      for (String key : map.keySet()) {
        valueMap.with(key, map.get(key));
      }
    }

    querySpec
        .withFilterExpression(filterExpression + "#ttl >= :currentTime")
        .withNameMap(nameMap.with("#ttl", Field.TTL.getName()))
        .withValueMap(valueMap.with(":currentTime", currentTime));
  }

  /**
   * Check the item if it is already expired based on TTL and current time
   *
   * @param item - Item to filter with session ttl
   * @return boolean
   */
  public static boolean filterSessionForTTL(Item item) {
    if (item.hasAttribute(Field.TTL.getName())) {
      long currentTime = GMTHelper.utcCurrentTime() / 1000;
      return item.getLong(Field.TTL.getName()) >= currentTime;
    } else {
      log.error("[XSESSIONS] [TTL] No ttl value for item: " + item.toJSONPretty());
      return true;
    }
  }
}
