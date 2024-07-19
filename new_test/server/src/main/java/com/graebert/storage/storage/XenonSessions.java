package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * DAO class for Xenon (File) Sessions.
 */
public class XenonSessions extends Dataplane {
  public static class FileSessions {
    private final String fileId;
    private final JsonObject json;

    public FileSessions(String fileId, JsonObject json) {
      this.fileId = fileId;
      this.json = json;
    }

    public String getFileId() {
      return fileId;
    }

    public JsonObject getJson() {
      return json;
    }
  }

  public static final String xsessionField = "XSESSION".concat(hashSeparator);
  public static final String xsessionRequestField = "XSESSION_REQUEST".concat(hashSeparator);
  public static final long MIN_UPDATE_TIME = TimeUnit.MINUTES.toMillis(3);
  private static final String xsessionChangesField =
      xsessionField.concat("CHANGES").concat(hashSeparator);
  private static final String tableName = TableName.TEMP.name;
  private static final String externalIdUserIdIndex = "externalId-userId-index";

  // DK: the diff with getSession is that cache is ignored here
  private static boolean doesSessionExist(final String fileId, final String xenonSessionId) {
    Item sessionItem = getItem(
        tableName,
        pk,
        fileId.startsWith(xsessionField) ? fileId : (xsessionField + fileId),
        sk,
        xenonSessionId,
        false,
        DataEntityType.XENON_SESSION);
    return Objects.nonNull(sessionItem);
  }

  public static ItemCollection<QueryOutcome> getAllSessionsForFile(final String fileId) {
    QuerySpec querySpec = new QuerySpec().withHashKey(pk, xsessionField + fileId);
    Sessions.filterSessionsQueryForTTL(querySpec);
    return query(tableName, null, querySpec, DataEntityType.XENON_SESSION);
  }

  public static void deleteSession(final String fileId, final String xenonSessionId) {
    deleteItem(
        tableName, pk, xsessionField + fileId, sk, xenonSessionId, DataEntityType.XENON_SESSION);
  }

  /**
   * Downgrades specified session to View only mode.
   *
   * @param sessionToDowngrade session that has to be downgraded.
   */
  public static void downgradeSession(Item sessionToDowngrade) {
    sessionToDowngrade.withString(Field.MODE.getName(), Mode.VIEW.name().toLowerCase());
    if (XenonSessions.doesSessionExist(
        sessionToDowngrade.getString(pk), sessionToDowngrade.getString(sk))) {
      putItem(
          tableName,
          sessionToDowngrade.getString(pk),
          sessionToDowngrade.getString(sk),
          sessionToDowngrade,
          DataEntityType.XENON_SESSION);
    }
  }

  /**
   * Creates a file session with specified parameters.
   *
   * @param fileId        file for which session has to be created
   * @param userId        user that opened the file
   * @param userName      user's name to be used for notifications
   * @param mode          specifies whether it should be editing or viewing session
   * @param userSessionId user's sessionId to keep track when user logout
   * @param storageType   storage where file exists
   * @param device        to keep track of specific cases
   * @param externalId    accountId in specified storage
   * @param fileSessionId File session ID that has to be used (for transfer).
   *                      E.g. if there's a sessionId on the client,
   *                      but session doesn't actually exist in DB
   * @return id of created session
   */
  public static String createSession(
      final String fileId,
      final String userId,
      final String userName,
      final Mode mode,
      final String userSessionId,
      final long ttl,
      final StorageType storageType,
      final String device,
      final String externalId,
      String fileSessionId,
      String fileName,
      String email,
      String name,
      String surname) {
    String id;
    if (Utils.isStringNotNullOrEmpty(fileSessionId)) {
      id = fileSessionId; // transfer session
    } else {
      id = Utils.generateUUID();
    }
    if (Objects.isNull(surname)) {
      log.warn("surname is null for user - " + email);
      surname = emptyString;
    }

    Item item = new Item()
        .withPrimaryKey(pk, xsessionField + fileId, sk, id)
        .withString(Field.ENCAPSULATED_ID.getName(), id)
        .withString(Field.USER_ID.getName(), userId)
        .withString(Field.USERNAME.getName(), userName)
        .withLong(Field.TTL.getName(), ttl)
        .withString(Field.MODE.getName(), mode.name().toLowerCase())
        .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime())
        .withLong("lastActivity", GMTHelper.utcCurrentTime())
        .with("fSessionId", userSessionId)
        .withString(Field.STORAGE_TYPE.getName(), storageType.toString())
        .withString(Field.STATE.getName(), SessionState.ACTIVE.name())
        .withLong("stateChanged", GMTHelper.utcCurrentTime())
        .withString(Field.DEVICE.getName(), device)
        .withString(Field.EMAIL.getName(), email)
        .withString(Field.NAME.getName(), name)
        .withString(Field.SURNAME.getName(), surname);
    if (Utils.isStringNotNullOrEmpty(externalId)) {
      item.withString(Field.EXTERNAL_ID.getName(), externalId);
    }
    if (Utils.isStringNotNullOrEmpty(fileName)) {
      item.withString(Field.FILE_NAME_C.getName(), fileName);
    }
    putItem(tableName, item, DataEntityType.XENON_SESSION);

    // delete requests for file
    if (mode.name().equalsIgnoreCase(Mode.EDIT.name())) {
      deleteRequestsForFile(fileId);
    }

    return id;
  }

  /**
   * Updates file session last activity time and TTL.
   * This is used to downgrade session after timeout.
   * E.g. if someone opened the file in editing mode and never closed it -
   * we'll time it out automatically using this info.
   *
   * @param fileId        ID of the file
   * @param fileSessionId File session ID
   * @param newTTL        - expire ttl in ms
   */
  public static void updateLastActivityAndTimeToLive(
      final String fileId, final String fileSessionId, long newTTL) {
    updateSession(
        "set lastActivity = :val , #t = :val2",
        new ValueMap().withLong(":val", GMTHelper.utcCurrentTime()).withLong(":val2", newTTL),
        new NameMap().with("#t", Field.TTL.getName()),
        fileId,
        fileSessionId);
  }

  /**
   * Sets new state for the session.
   * This is used for the new session handling procedures.
   * E.g. we mark session as SAVE_PENDING to prevent session removal until save is done.
   *
   * @param fileId        ID of the file
   * @param fileSessionId File session ID
   * @param state         New state to be set {@link SessionState}
   */
  public static void updateSessionState(
      final String fileId, final String fileSessionId, final SessionState state) {
    log.info("FILE SESSION STATE UPDATED for fileId " + fileId + " - " + state.name());
    updateSession(
        "set #st = :val , #stc = :val2",
        new ValueMap()
            .withString(":val", state.name())
            .withLong(":val2", GMTHelper.utcCurrentTime()),
        new NameMap().with("#st", Field.STATE.getName()).with("#stc", "stateChanged"),
        fileId,
        fileSessionId);
  }

  /**
   * Sets mode (edit/view) for the file.
   *
   * @param fileId        ID of the file
   * @param fileSessionId ID of the file session
   * @param mode          edit/view mode {@link Mode}
   */
  public static void setMode(final String fileId, final String fileSessionId, String mode) {
    updateSession(
        "set #md = :val",
        new ValueMap().withString(":val", mode),
        new NameMap().with("#md", Field.MODE.getName()),
        fileId,
        fileSessionId);

    // delete requests for file
    if (mode.equalsIgnoreCase(Mode.EDIT.name())) {
      updateSessionState(fileId, fileSessionId, SessionState.ACTIVE);
      deleteRequestsForFile(fileId);
    }
  }

  public static void setVersionId(final String fileId, final String xSessionId, String versionId) {
    updateSession(
        "set #vid = :val",
        new ValueMap().withString(":val", versionId),
        new NameMap().with("#vid", Field.VERSION_ID.getName()),
        fileId,
        xSessionId);
  }

  private static void updateSession(
      final String updateExpression,
      final ValueMap valueMap,
      final NameMap nameMap,
      final String fileId,
      final String xSessionId) {
    if (XenonSessions.doesSessionExist(fileId, xSessionId)) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, XenonSessions.xsessionField + fileId, sk, xSessionId)
              .withUpdateExpression(updateExpression)
              .withValueMap(valueMap)
              .withNameMap(nameMap),
          DataEntityType.XENON_SESSION);
    }
  }

  public static Iterator<Item> getActiveSessions(final String fileId, final Mode mode) {
    QuerySpec querySpec = new QuerySpec()
        .withHashKey(pk, xsessionField + fileId)
        .withFilterExpression("#md = :edit")
        .withNameMap(new NameMap().with("#md", Field.MODE.getName()))
        .withValueMap(new ValueMap().withString(":edit", mode.name().toLowerCase()));
    Sessions.filterSessionsQueryForTTL(querySpec);
    return query(tableName, null, querySpec, DataEntityType.XENON_SESSION).iterator();
  }

  public static Item getSessionForUser(final String fileId, final String userSessionId) {
    QuerySpec querySpec = new QuerySpec()
        .withHashKey(pk, xsessionField + fileId)
        .withFilterExpression("fSessionId = :fSessionId")
        .withValueMap(new ValueMap().withString(":fSessionId", userSessionId));
    Sessions.filterSessionsQueryForTTL(querySpec);
    Iterator<Item> itemIterator =
        query(tableName, null, querySpec, DataEntityType.XENON_SESSION).iterator();
    if (itemIterator.hasNext()) {
      return itemIterator.next();
    }
    return null;
  }

  public static Iterator<Item> getSessionForStorageAccount(
      final String externalId, final String userId, final String storageType) {
    QuerySpec querySpec = new QuerySpec()
        .withKeyConditionExpression("#externalId = :externalId and #userId = :userId")
        .withFilterExpression("begins_with(#pk, :pk) and #storageType = :storageType")
        .withNameMap(new NameMap()
            .with("#pk", Field.PK.getName())
            .with("#userId", Field.USER_ID.getName())
            .with("#externalId", Field.EXTERNAL_ID.getName())
            .with("#storageType", Field.STORAGE_TYPE.getName()))
        .withValueMap(new ValueMap()
            .withString(":userId", userId)
            .withString(":pk", XenonSessions.xsessionField)
            .withString(":externalId", externalId)
            .withString(":storageType", storageType));
    Sessions.filterSessionsQueryForTTL(querySpec);
    return query(tableName, externalIdUserIdIndex, querySpec, DataEntityType.XENON_SESSION)
        .iterator();
  }

  public static void addSessionChanges(
      String fileId,
      String xenonSessionId,
      List<Map<String, Object>> fileChangesInfo,
      boolean changesAreSaved) {

    String skValue = SessionChangesStatus.CURRENT.name() + hashSeparator + xenonSessionId;
    if (changesAreSaved) {
      skValue = SessionChangesStatus.SAVED.name()
          + hashSeparator
          + xenonSessionId
          + hashSeparator
          + GMTHelper.utcCurrentTime();
    }

    Item item = new Item()
        .withString(pk, xsessionChangesField + fileId)
        .withString(sk, skValue)
        .withList(Field.FILE_CHANGES_INFO.getName(), fileChangesInfo)
        .withLong("changesAdded", GMTHelper.utcCurrentTime())
        .withLong(Field.TTL.getName(), TtlUtils.inOneMonth());

    putItem(tableName, item, DataEntityType.XENON_SESSION);
  }

  public static void markSessionStatusAsSaved(Item unsavedChangesItem, String xenonSessionId) {
    deleteItem(
        tableName,
        pk,
        unsavedChangesItem.getString(pk),
        sk,
        unsavedChangesItem.getString(sk),
        DataEntityType.XENON_SESSION);

    unsavedChangesItem
        .withString(
            sk,
            SessionChangesStatus.SAVED.name()
                + hashSeparator
                + xenonSessionId
                + hashSeparator
                + GMTHelper.utcCurrentTime())
        .withLong(Field.TTL.getName(), TtlUtils.inOneMonth())
        .withLong("changesSaved", GMTHelper.utcCurrentTime());
    putItem(tableName, unsavedChangesItem, DataEntityType.XENON_SESSION);
  }

  public static void updateSessionChanges(
      String fileId, String xenonSessionId, List<Map<String, Object>> changesInfoInDb) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(
                pk,
                xsessionChangesField + fileId,
                sk,
                SessionChangesStatus.CURRENT.name() + hashSeparator + xenonSessionId)
            .withUpdateExpression("set #changes = :changes, #ttl = :ttl")
            .withValueMap(new ValueMap()
                .withList(":changes", changesInfoInDb)
                .withLong(":ttl", TtlUtils.inOneMonth()))
            .withNameMap(new NameMap()
                .with("#changes", Field.FILE_CHANGES_INFO.getName())
                .with("#ttl", Field.TTL.getName())),
        DataEntityType.XENON_SESSION);
  }

  public static void copyUnsavedChangesToNewFile(
      String fileId, String oldFileId, String xenonSessionId) {
    Item unsavedChanges = getUnsavedCurrentChanges(oldFileId, xenonSessionId);
    if (unsavedChanges != null) {
      // create unsaved changes item with new fileId
      unsavedChanges.withString(pk, xsessionChangesField + fileId);
      putItem(tableName, unsavedChanges, DataEntityType.XENON_SESSION);
      // delete unsaved changes item for old fileId
      deleteItem(
          tableName,
          pk,
          xsessionChangesField + oldFileId,
          sk,
          unsavedChanges.getString(sk),
          DataEntityType.XENON_SESSION);
    }
  }

  public static void copySavedChangesToNewFile(
      String fileId, String oldFileId, String xenonSessionId) {
    Iterator<Item> savedChanges = getSavedCurrentChanges(oldFileId, xenonSessionId);
    // copy the saved changes from the last session to the new file
    List<Item> savedItemsToPut = new ArrayList<>();
    savedChanges.forEachRemaining(change -> {
      change.withString(pk, xsessionChangesField + fileId);
      savedItemsToPut.add(change);
    });
    batchWriteListItems(savedItemsToPut, tableName, DataEntityType.XENON_SESSION);
  }

  public static Item getUnsavedCurrentChanges(String fileId, String xenonSessionId) {
    return getItemFromCache(
        tableName,
        pk,
        xsessionChangesField + fileId,
        sk,
        SessionChangesStatus.CURRENT.name() + hashSeparator + xenonSessionId,
        DataEntityType.XENON_SESSION);
  }

  public static Iterator<Item> getSavedCurrentChanges(String fileId, String xenonSessionId) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :pk " + "and" + " " + "begins_with" + "(sk, :sk)")
                .withValueMap(new ValueMap()
                    .withString(":pk", xsessionChangesField + fileId)
                    .withString(
                        ":sk", SessionChangesStatus.SAVED.name() + hashSeparator + xenonSessionId)),
            DataEntityType.XENON_SESSION)
        .iterator();
  }

  public static Item getSession(final String fileId, final String xenonSessionId) {
    Item item = getItemFromCache(
        tableName, pk, xsessionField + fileId, sk, xenonSessionId, DataEntityType.XENON_SESSION);
    if (Objects.nonNull(item) && Sessions.filterSessionForTTL(item)) {
      return item;
    }
    return null;
  }

  public static Item getEditingSession(final String fileId, final String userSessionId) {
    Iterator<Item> cursor = query(
            tableName,
            null,
            new QuerySpec()
                .withHashKey(pk, xsessionField + fileId)
                .withFilterExpression("fSessionId = :fSessionId and #md = :edit")
                .withNameMap(new NameMap().with("#md", Field.MODE.getName())) // NON-NLS
                .withValueMap(new ValueMap()
                    .withString(":fSessionId", userSessionId)
                    .withString(":edit", "edit")),
            DataEntityType.XENON_SESSION)
        .iterator();
    Item item;
    if (cursor.hasNext()) {
      item = cursor.next();
      if (Objects.nonNull(item) && Sessions.filterSessionForTTL(item)) {
        return item;
      }
    }
    return null;
  }

  public static void transferSession(
      final String oldFileId, final String newFileId, final Item xSession) {
    xSession
        .withPrimaryKey(pk, xsessionField + newFileId, sk, xSession.getString(sk))
        .withLong("lastActivity", GMTHelper.utcCurrentTime())
        .withString(Field.STATE.getName(), SessionState.ACTIVE.name());
    putItem(tableName, xSession, DataEntityType.XENON_SESSION);
    deleteItem(
        tableName,
        pk,
        xsessionField + oldFileId,
        sk,
        xSession.getString(sk),
        DataEntityType.XENON_SESSION);
  }

  public static String getFileIdFromPk(final String pkValue) {
    return pkValue.substring(xsessionField.length());
  }

  public enum Mode {
    EDIT,
    VIEW;

    public static Mode getMode(String value) {
      for (Mode mode : Mode.values()) {
        if (mode.name().toLowerCase().equals(value)) {
          return mode;
        }
      }
      return VIEW;
    }

    public static boolean contains(String value) {
      for (Mode mode : Mode.values()) {
        if (mode.name().toLowerCase().equals(value)) {
          return true;
        }
      }
      return false;
    }
  }

  public enum SessionState {
    ACTIVE,
    SAVE_PENDING,
    STALE;

    public static SessionState getSessionState(String value) {
      if (!Utils.isStringNotNullOrEmpty(value)) {
        return ACTIVE;
      }
      final String formattedValue = value.trim();
      for (SessionState t : SessionState.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
      return ACTIVE;
    }
  }

  public enum SessionChangesStatus {
    CURRENT,
    SAVED,
  }

  // checks if user has already requested edit
  // session for specified file
  public static Item getResentRequest(String fileId, String userId) {
    QuerySpec querySpec = new QuerySpec()
        .withHashKey(pk, xsessionRequestField + fileId)
        .withFilterExpression("#uid = :userid")
        .withNameMap(new NameMap().with("#uid", Field.USER_ID.getName()))
        .withValueMap(new ValueMap().withString(":userid", userId));
    Sessions.filterSessionsQueryForTTL(querySpec);
    Iterator<Item> requests =
        query(tableName, null, querySpec, DataEntityType.XENON_SESSION).iterator();
    return requests.hasNext() ? requests.next() : null;
  }

  // checks if request for xSession is still alive
  public static boolean isRequestAlive(Item requestedXSession, String applicantXSession) {
    Item request = getRequest(
        requestedXSession.getString(pk).substring(xsessionField.length()), applicantXSession);

    return request != null
        && Sessions.filterSessionForTTL(request)
        && !request.hasAttribute("denied");
  }

  // saves edit request for xSession
  public static Item saveRequest(
      Item editXSession, String requestUserId, String requestXSessionId) {
    int ttl = Sessions.getSessionRequestTTL();

    // REQUEST ITEM STRUCTURE
    // pk: XSESSION_REQUEST# + fileId
    // sk: collaborator's xSessionId
    // userId: collaborator's userId
    // xSessionId: editor session's xSessionId
    // fileId: file id (pure)
    // ttl: is 5 minutes, should be checked by /session/approve

    Item item = new Item()
        .withPrimaryKey(
            pk,
            editXSession.getString(pk).replace(xsessionField, xsessionRequestField),
            sk,
            requestXSessionId)
        .withString(Field.USER_ID.getName(), requestUserId)
        .withString(Field.X_SESSION_ID.getName(), editXSession.getString(sk))
        .withString(Field.FILE_ID.getName(), getFileIdFromPk(editXSession.getString(pk)))
        .withLong(Field.TTL.getName(), ttl);
    putItem(tableName, item, DataEntityType.XENON_SESSION);

    return item;
  }

  // returns Item of xSession request
  public static Item getRequest(final String fileId, final String requestXSessionId) {
    return getItemFromCache(
        tableName,
        pk,
        xsessionRequestField + fileId,
        sk,
        requestXSessionId,
        DataEntityType.XENON_SESSION);
  }

  // returns Items representing all request for specific fileId
  public static List<Item> getAllPendingRequests(final String fileId, Set<String> exceptSessions) {
    QuerySpec querySpec = new QuerySpec().withHashKey(pk, xsessionRequestField + fileId);
    Sessions.filterSessionsQueryForTTL(querySpec);
    ItemCollection<QueryOutcome> requests =
        query(tableName, null, querySpec, DataEntityType.XENON_SESSION);

    return StreamSupport.stream(requests.spliterator(), false)
        .filter(request -> !exceptSessions.contains(request.getString(Dataplane.sk))
            && (!request.hasAttribute("denied") || !request.getBoolean("denied")))
        .collect(Collectors.toList());
  }

  // returns xSession of a user that has requested this session first
  public static String getApplicantXSession(Item xSession) {
    String requestPk = xSession.getString(pk).replace(xsessionField, xsessionRequestField);

    QuerySpec querySpec = new QuerySpec().withHashKey(pk, requestPk);
    Sessions.filterSessionsQueryForTTL(querySpec);
    ItemCollection<QueryOutcome> requests =
        query(tableName, null, querySpec, DataEntityType.XENON_SESSION);

    long minTtl = Long.MAX_VALUE;
    String applicant = null;

    for (Item request : requests) {
      if (request.getLong(Field.TTL.getName()) < minTtl && !request.hasAttribute("denied")) {
        minTtl = request.getLong(Field.TTL.getName());
        applicant = request.getString(sk);
      }
    }

    if (applicant != null) {
      deleteItem(tableName, pk, requestPk, sk, applicant, DataEntityType.XENON_SESSION);
    }

    return applicant;
  }

  public static void denyRequest(Item request) {
    putItem(tableName, request.withBoolean("denied", true), DataEntityType.XENON_SESSION);
  }

  public static void deleteRequestsForFile(String fileId) {
    Iterator<Item> items = query(
            tableName,
            null,
            new QuerySpec().withHashKey(pk, xsessionRequestField + fileId),
            DataEntityType.XENON_SESSION)
        .iterator();

    List<PrimaryKey> primaryKeys = new ArrayList<>();
    while (items.hasNext()) {
      Item item = items.next();
      primaryKeys.add(new PrimaryKey(pk, item.getString(pk), sk, item.getString(sk)));
    }

    if (!primaryKeys.isEmpty()) {
      deleteListItemsBatch(primaryKeys, tableName, DataEntityType.XENON_SESSION);
    }
  }

  public static String getFileIdFromPK(String pkValue) {
    return pkValue.substring(XenonSessions.xsessionField.length());
  }

  public static List<FileSessions> collectSessionsForFiles(
      Set<String> files, boolean onlyEditSessions) {
    return files.parallelStream()
        .map(fileId -> {
          String originalFileId = fileId;
          fileId =
              Utils.parseItemId(fileId, Field.FILE_ID.getName()).getString(Field.FILE_ID.getName());
          Iterator<Item> sessions = onlyEditSessions
              ? XenonSessions.getActiveSessions(fileId, Mode.EDIT)
              : XenonSessions.getAllSessionsForFile(fileId).iterator();
          if (sessions.hasNext()) {
            JsonArray editors = new JsonArray();
            Item session = sessions.next();
            editors.add(new JsonObject()
                .put(Field.X_SESSION_ID.getName(), session.getString(Field.SK.getName()))
                .put(Field.EMAIL.getName(), session.getString(Field.USERNAME.getName())));

            return new FileSessions(
                originalFileId,
                new JsonObject()
                    .put(Field.FILE_ID.getName(), originalFileId)
                    .put(Field.EDITORS.getName(), editors));
          }
          return null;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  public static void deleteAllSessionsForFile(String fileId) {
    deleteListItemsBatch(
        Utils.asStream(XenonSessions.getAllSessionsForFile(fileId).iterator())
            .map(o -> new PrimaryKey(pk, o.getString(pk), sk, o.getString(sk)))
            .collect(Collectors.toList()),
        tableName,
        DataEntityType.XENON_SESSION);
  }
}
