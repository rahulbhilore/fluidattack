package com.graebert.storage.Entities;

import com.amazonaws.services.dynamodbv2.document.BatchGetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.TableKeysAndAttributes;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.stats.logs.sharing.SharingActions;
import com.graebert.storage.stats.logs.sharing.SharingLog;
import com.graebert.storage.stats.logs.sharing.SharingTypes;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.link.repository.LinkService;
import com.graebert.storage.users.LicensingService;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.urls.ShortURL;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kong.unirest.HttpResponse;

public class PublicLink extends Dataplane {
  private static final String PK_PREFIX = "shared_link#";
  private static boolean isEnterprise;
  private static String instanceURL;
  private static JsonObject companyOptions;
  private static String secretKey;
  // required parameters
  private String fileId;
  private String externalId;
  private String userId;
  private String storageType;
  private String linkOwnerIdentity;
  private String filename;

  // additional info from storage
  private String sharedLink;
  private List<String> collaboratorsList;
  private String path;

  // additional parameters
  private String password;
  private boolean export;
  private long endTime;

  // underlying DB item
  private Item DBLink;

  // DB keys
  private final String pk;
  private String sk;

  /**
   * Normal constructor
   * Enough to certainly get file's public link if it exists
   *
   * @param fileId     ID of the target file
   * @param externalId Account ID
   * @param userId     Kudo's ID of the user
   */
  public PublicLink(String fileId, String externalId, String userId) {
    this.fileId = fileId;
    this.externalId = externalId;
    this.userId = userId;

    this.pk = PK_PREFIX + fileId;
    //      just in case if we receive empty strings - sk will be broken basically.
    if (Utils.isStringNotNullOrEmpty(externalId) && Utils.isStringNotNullOrEmpty(userId)) {
      this.sk = externalId + "#" + userId;
    }
  }

  /**
   * Minimum constructor
   * Can fetch the info, but should be avoided if possible
   *
   * @param fileId ID of the target file
   */
  public PublicLink(String fileId) {
    this.fileId = fileId;
    this.pk = PK_PREFIX + fileId;
  }

  /**
   * For stats use
   *
   * @param userId      - optional
   * @param storageType - optional
   */
  public static JsonArray getAllLinks(final String userId, final String storageType) {
    // TODO: SCAN stats
    String scanFilter = "begins_with(pk,:key)";
    ValueMap valueMap = new ValueMap().withString(":key", PK_PREFIX);
    if (Utils.isStringNotNullOrEmpty(userId)) {
      scanFilter += " and contains(sk,:uid)";
      valueMap.withString(":uid", userId);
    }
    if (Utils.isStringNotNullOrEmpty(storageType)) {
      scanFilter += " and storageType=:st";
      valueMap.withString(":st", storageType);
    }
    JsonArray result = new JsonArray();
    for (Item item : scan(
        TableName.MAIN.name,
        null,
        new ScanSpec().withFilterExpression(scanFilter).withValueMap(valueMap),
        DataEntityType.PUBLIC_LINK)) {
      result.add(new JsonObject(item.toJSON()));
    }
    return result;
  }

  /**
   * Has to be used, because this class shouldn't be deployed as verticle
   *
   * @param _isEnterprise   {boolean} Is it enterprise Kudo version or not
   * @param _companyOptions {JsonObject} Default options for company in Kudo
   */
  public static void setConfigValues(
      final String _instanceURL,
      final boolean _isEnterprise,
      final JsonObject _companyOptions,
      final String _secretKey) {
    instanceURL = _instanceURL;
    isEnterprise = _isEnterprise;
    companyOptions = _companyOptions;
    secretKey = _secretKey;
  }

  /**
   * Delete using query
   *
   * @param fileId - Id of the file
   * @param userId - current user Id. Assumed to be an owner
   */
  public static void queryDelete(String fileId, String userId) throws PublicLinkException {
    if (Utils.isStringNotNullOrEmpty(fileId) && Utils.isStringNotNullOrEmpty(userId)) {
      String pk = PK_PREFIX + fileId;
      deleteFromCache(TableName.MAIN.name, pk, DataEntityType.PUBLIC_LINK);
      ItemCollection<QueryOutcome> queryFoundLinks = query(
          TableName.MAIN.name,
          null,
          new QuerySpec().withHashKey(Dataplane.pk, pk),
          DataEntityType.PUBLIC_LINK);
      for (Item foundLink : queryFoundLinks) {
        if (foundLink.getString(Dataplane.sk).contains(userId)) {
          deleteItem(
              TableName.MAIN.name,
              Field.PK.getName(),
              pk,
              Field.SK.getName(),
              foundLink.getString(Field.SK.getName()),
              DataEntityType.PUBLIC_LINK);
          Thread trackerThread = new Thread(() -> {
            try {
              String email = "no_data";
              String device = "no_data";

              Item user = Users.getUserById(userId);
              if (user != null && user.hasAttribute(Field.USER_EMAIL.getName())) {
                email = user.getString(Field.USER_EMAIL.getName());
                Iterator<Item> it = Sessions.getLastUserSession(userId);
                if (it.hasNext()) {
                  Item mostRecentSession = it.next();
                  if (mostRecentSession != null) {
                    device = mostRecentSession.getString(Field.DEVICE.getName());
                  }
                }
              }

              SharingLog sharingLog = new SharingLog(
                  userId,
                  fileId,
                  StorageType.getStorageType(foundLink.getString(Field.STORAGE_TYPE.getName())),
                  email,
                  GMTHelper.utcCurrentTime(),
                  true,
                  SharingTypes.PUBLIC_LINK,
                  SharingActions.LINK_DELETED_UNSAFELY,
                  device,
                  null,
                  null);
              sharingLog.setSharingType(SharingTypes.PUBLIC_LINK);
              sharingLog.setSharingAction(SharingActions.LINK_DELETED_UNSAFELY);
              sharingLog.setDevice(device);
              sharingLog.sendToServer();
            } catch (Exception e) {
              log.error(e);
            }
          });
          trackerThread.start();
          return;
        }
      }
      // because there's a loop with return on success - throw exception if not exited.
      throw new PublicLinkException("Link isn't found", PublicLinkErrorCodes.LINK_NOT_FOUND);

    } else {
      throw new PublicLinkException(
          "ExternalId or userId aren't defined", PublicLinkErrorCodes.PARAMETERS_ARE_INCOMPLETE);
    }
  }

  public static void deleteUserNewSharedLinks(String userId) {
    // TODO: SCAN user removal
    Iterator<Item> newLinks = scan(
            TableName.MAIN.name,
            null,
            new ScanSpec()
                .withFilterExpression("begins_with (pk, :pk) and contains(sk, :userId)")
                .withValueMap(
                    new ValueMap().withString(":pk", PK_PREFIX).withString(":userId", userId)),
            DataEntityType.PUBLIC_LINK)
        .iterator();
    List<PrimaryKey> newLinksToErase = new ArrayList<>();
    newLinks.forEachRemaining(link -> {
      String pk = link.getString(Dataplane.pk);
      String sk = link.getString(Dataplane.sk);
      if (Utils.isStringNotNullOrEmpty(pk) && Utils.isStringNotNullOrEmpty(sk)) {
        newLinksToErase.add(new PrimaryKey(Dataplane.pk, pk, Dataplane.sk, sk));
      }
    });
    deleteListItemsBatch(newLinksToErase, TableName.MAIN.name, DataEntityType.PUBLIC_LINK);
  }

  public static Map<String, JsonObject> getSharedLinks(
      ServerConfig config,
      Entity segment,
      String userId,
      String externalId,
      Map<String, String> possibleIdsToIds) {
    // get shared links from cache

    Set<String> rawIdsToRequest = new HashSet<>();
    // key - variaton of id
    // value - "real" id
    for (Map.Entry<String, String> id : possibleIdsToIds.entrySet()) {
      rawIdsToRequest.add(PK_PREFIX + id.getKey());
    }

    Map<String, JsonObject> responseObject = new HashMap<>();

    // get data from cache
    Map<String, Object> cachedLinks = getBulkFromCache(rawIdsToRequest, DataEntityType.PUBLIC_LINK);
    if (cachedLinks != null && !cachedLinks.isEmpty()) {
      // key - shared_link#fileId, value - shared Link info
      cachedLinks.forEach((key, value) -> {
        if (value != null) {
          Item sharedLink = Item.fromJSON((String) value);
          if (!sharedLink.hasAttribute(Field.ENABLED.getName())
              || sharedLink.getBoolean(Field.ENABLED.getName())) {
            final String foundId = key.substring(PK_PREFIX.length());
            // foundId is one of the "variatons", so get will return value which is "real" key
            final String fileId = possibleIdsToIds.get(foundId);
            if (fileId != null) {
              responseObject.put(
                  fileId,
                  new JsonObject()
                      .put(Field.PUBLIC.getName(), true)
                      .put(
                          Field.LINK.getName(),
                          config.getProperties().getUrl() + "file/" + foundId + "?token="
                              + sharedLink.getString(Field.TOKEN.getName()))
                      .put(
                          Field.EXPORT.getName(),
                          sharedLink.hasAttribute(Field.EXPORT.getName())
                              && sharedLink.getBoolean(Field.EXPORT.getName())));
            }
            // we should filter viewOnly files before/after the call
          }
        }
      });
    }

    // get data from table
    if (!rawIdsToRequest.isEmpty() && userId != null && externalId != null) {
      final String sk = externalId + "#" + userId;

      // split into chunks
      Object[] ids = rawIdsToRequest.toArray();
      List<Object[]> chunkedIds = new ArrayList<>();
      if (ids.length > 100) {
        for (int i = 0; i < ids.length / 100; i++) {
          int length = Math.min(100, ids.length - i * 100);
          Object[] chunk = new Object[length];
          System.arraycopy(ids, 0, chunk, 0, chunk.length);
          chunkedIds.add(chunk);
        }
      } else {
        chunkedIds.add(ids);
      }

      List<String> existing = new ArrayList<>();
      chunkedIds.forEach(chunk -> {

        // generate array to request
        // we should provide array like [pk,sk,pk,sk,...]
        ArrayList<String> keysArray = new ArrayList<>(chunk.length * 2);
        for (Object fileId : chunk) {
          keysArray.add(fileId.toString());
          keysArray.add(sk);
        }

        // try to get data from tables
        try {
          BatchGetItemOutcome outcome = batchGetItem(
              DataEntityType.PUBLIC_LINK,
              TableName.MAIN.name,
              new TableKeysAndAttributes(getTableName(TableName.MAIN.name))
                  .addHashAndRangePrimaryKeys(
                      Field.PK.getName(), Field.SK.getName(), keysArray.toArray()));

          Map<String, KeysAndAttributes> unprocessed;
          do {
            for (String tableName : outcome.getTableItems().keySet()) {
              List<Item> items = outcome.getTableItems().get(tableName);
              for (Item sharedLink : items) {
                String pk = sharedLink.getString(Field.PK.getName());
                final String foundId = pk.substring(PK_PREFIX.length());
                final String fileId = possibleIdsToIds.get(foundId);
                existing.add(pk);
                populateCache(pk, sharedLink.toJSON(), 60 * 60, DataEntityType.PUBLIC_LINK);
                if ((!sharedLink.hasAttribute(Field.ENABLED.getName())
                        || sharedLink.getBoolean(Field.ENABLED.getName()))
                    && fileId != null) {
                  responseObject.put(
                      fileId,
                      new JsonObject()
                          .put(Field.PUBLIC.getName(), true)
                          .put(
                              Field.LINK.getName(),
                              config.getProperties().getUrl() + "file/" + foundId + "?token="
                                  + sharedLink.getString(Field.TOKEN.getName()))
                          .put(
                              Field.EXPORT.getName(),
                              sharedLink.hasAttribute(Field.EXPORT.getName())
                                  && sharedLink.getBoolean(Field.EXPORT.getName())));
                }
              }
            }
            unprocessed = outcome.getUnprocessedKeys();
            if (!unprocessed.isEmpty()) {
              outcome = batchGetItemUnprocessed(unprocessed);
            }
          } while (!unprocessed.isEmpty());
        } catch (Exception ex) {
          log.error("Get shared links exception: " + ex.getMessage());
        }
      });

      // cache non-existing shared links, so we will not try to get them from the db next time
      existing.forEach(rawIdsToRequest::remove);
      rawIdsToRequest.forEach(cacheKey -> populateCache(
          cacheKey,
          new Item()
              .withString(Field.PK.getName(), cacheKey)
              .withBoolean(Field.ENABLED.getName(), false)
              .toJSON(),
          60 * 60,
          DataEntityType.PUBLIC_LINK));
    }

    return responseObject;
  }

  public static List<Item> removeUnsharedFileLinks(String fileId, JsonArray collaborators) {
    try {
      if (Utils.isStringNotNullOrEmpty(fileId) && !collaborators.isEmpty()) {
        ArrayList<Item> removedLinks = new ArrayList<>();

        String pk = PK_PREFIX + fileId;
        deleteFromCache(TableName.MAIN.name, pk, DataEntityType.PUBLIC_LINK);
        ItemCollection<QueryOutcome> queryFoundLinks = query(
            TableName.MAIN.name,
            null,
            new QuerySpec().withHashKey(Dataplane.pk, pk),
            DataEntityType.PUBLIC_LINK);
        for (Item foundLink : queryFoundLinks) {
          if (foundLink.hasAttribute(Field.LINK_OWNER_IDENTITY.getName())
              && collaborators.contains(foundLink.getString(Field.LINK_OWNER_IDENTITY.getName()))) {
            removedLinks.add(foundLink);
            deleteItem(
                TableName.MAIN.name,
                Field.PK.getName(),
                pk,
                Field.SK.getName(),
                foundLink.getString(Field.SK.getName()),
                DataEntityType.PUBLIC_LINK);
          }
        }

        return removedLinks;
      }
    } catch (Exception ex) {
      log.error("Remove shared links for file exception: " + ex.getMessage());
    }
    return new ArrayList<>();
  }

  private void updateValuesFromDB() {
    try {
      if (DBLink == null
          || !DBLink.hasAttribute(Field.PK.getName())
          || !DBLink.hasAttribute(Field.SK.getName())) {
        return;
      }
      this.fileId = DBLink.getString(Field.PK.getName()).substring(PK_PREFIX.length());
      this.externalId = DBLink.getString(Field.SK.getName())
          .substring(0, DBLink.getString(Field.SK.getName()).indexOf("#"));
      this.userId = DBLink.getString(Field.SK.getName())
          .substring(DBLink.getString(Field.SK.getName()).indexOf("#") + 1);
      this.storageType = DBLink.getString(Field.STORAGE_TYPE.getName());
      this.filename = DBLink.getString(Field.FILE_NAME.getName());
      this.export = DBLink.getBoolean(Field.EXPORT.getName());
      this.endTime = DBLink.getLong("expiryDate");
      this.linkOwnerIdentity = DBLink.getString(Field.LINK_OWNER_IDENTITY.getName());
    } catch (Exception e) {
      log.error(e);
    }
  }

  /**
   * Checks if "new format" shared link exists
   * Updates DBLink property
   *
   * @return true if exists, false otherwise
   */
  private boolean doesNewLinkExist() {
    Item newSharedLink;
    if (!Utils.isStringNotNullOrEmpty(sk)) {
      return false;
    } else {
      newSharedLink = getItemFromCache(
          TableName.MAIN.name,
          Field.PK.getName(),
          pk,
          Field.SK.getName(),
          sk,
          DataEntityType.PUBLIC_LINK);
      if (newSharedLink != null) {
        DBLink = newSharedLink;
        storageType = DBLink.getString(Field.STORAGE_TYPE.getName());
        filename = DBLink.getString(Field.FILE_NAME.getName());
      }
    }
    return newSharedLink != null;
  }

  /**
   * Try to get new link.
   * This will work only if you've specified userId and externalId
   * If you need to find link by fileId and token - use like this
   * new PublicLink(fileId).findLinkByToken(token)
   *
   * @return {boolean} - false if no link found in DB, true - otherwise
   */
  public boolean fetchLinkInfo() {
    boolean status = doesNewLinkExist();
    updateValuesFromDB();
    return status;
  }

  /**
   * Creates new link in the DB or updates existing one
   */
  public void createOrUpdate() {
    if (!Utils.isStringNotNullOrEmpty(storageType) || !Utils.isStringNotNullOrEmpty(filename)) {
      throw new IllegalStateException("Filename and/or storageType isn't specified");
    }

    boolean sharedLinkUpdate = doesNewLinkExist() && DBLink != null;

    Item itemToInsert = DBLink != null ? DBLink : new Item();

    String token = emptyString;
    if (sharedLinkUpdate) {
      token = DBLink.getString(Field.TOKEN.getName());
    }
    if (!Utils.isStringNotNullOrEmpty(token)) {
      token = UUID.randomUUID().toString();
    }
    if (sk == null) {
      if (DBLink != null) {
        sk = DBLink.getString(Field.SK.getName());
      } else {
        throw new IllegalStateException("SK is undefined");
      }
    }
    itemToInsert
        .withPrimaryKey(Field.PK.getName(), pk, Field.SK.getName(), sk)
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withString(Field.TOKEN.getName(), token)
        .withString(Field.LINK_OWNER_IDENTITY.getName(), linkOwnerIdentity)
        .withString(Field.FILE_NAME.getName(), filename)
        .withBoolean(Field.ENABLED.getName(), true)
        .withLong(Field.UPDATE_DATE.getName(), GMTHelper.utcCurrentTime())
        .withBoolean(Field.EXPORT.getName(), export)
        .withLong("expiryDate", endTime);
    if (!sharedLinkUpdate) {
      itemToInsert
          .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime())
          .withString(Field.CREATOR.getName(), userId);
    } else {
      itemToInsert.withString("updater", userId);
    }
    if (sharedLink != null) {
      itemToInsert.withString(Field.SHARED_LINK.getName(), sharedLink);
    }
    if (collaboratorsList != null) {
      itemToInsert.withList(Field.COLLABORATORS.getName(), collaboratorsList);
    }
    if (path != null) {
      itemToInsert.withString(Field.PATH.getName(), path);
    }
    if (password != null) {
      if (password.isEmpty() && DBLink != null && DBLink.hasAttribute(Field.PASSWORD.getName())) {
        itemToInsert.removeAttribute(Field.PASSWORD.getName());
      } else {
        itemToInsert.withString(Field.PASSWORD.getName(), password);
      }
    }
    if (Utils.isStringNotNullOrEmpty(userId)) {
      itemToInsert.withString(Field.USER_ID.getName(), userId);
    }
    if (Utils.isStringNotNullOrEmpty(externalId)) {
      itemToInsert.withString(Field.EXTERNAL_ID.getName(), externalId);
    }
    if (!sharedLinkUpdate) {
      try {
        String endUserURL = instanceURL + "file/" + fileId + "?token=" + token;
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          // let's try to encapsulate if possible
          endUserURL = instanceURL + "file/"
              + Utils.getEncapsulatedId(StorageType.getStorageType(storageType), externalId, fileId)
              + "?token=" + token;
        }
        final String shortURL = new ShortURL(endUserURL).getShortURL();
        if (Utils.isStringNotNullOrEmpty(shortURL)) {
          itemToInsert.withString("shortURL", shortURL);
          itemToInsert.withString("host", instanceURL);
        }
      } catch (Exception ex) {
        log.error(ex);
      }
    }
    if (Utils.isStringNotNullOrEmpty(sk)) {
      deleteFromCache(TableName.MAIN.name, pk, sk, DataEntityType.PUBLIC_LINK);
    }
    deleteFromCache(TableName.MAIN.name, pk, DataEntityType.PUBLIC_LINK);
    putItem(itemToInsert, DataEntityType.PUBLIC_LINK);
    DBLink = itemToInsert;

    Thread trackerThread = new Thread(() -> {
      try {
        String email = "no_data";
        String device = "no_data";

        Item user = Users.getUserById(userId);
        if (user != null && user.hasAttribute(Field.USER_EMAIL.getName())) {
          email = user.getString(Field.USER_EMAIL.getName());
          Iterator<Item> it = Sessions.getLastUserSession(userId);
          if (it.hasNext()) {
            Item mostRecentSession = it.next();
            if (mostRecentSession != null) {
              device = mostRecentSession.getString(Field.DEVICE.getName());
            }
          }
        }

        SharingLog sharingLog = new SharingLog(
            userId,
            fileId,
            StorageType.getStorageType(storageType),
            email,
            GMTHelper.utcCurrentTime(),
            true,
            SharingTypes.PUBLIC_LINK,
            sharedLinkUpdate ? SharingActions.LINK_UPDATED : SharingActions.LINK_CREATED,
            device,
            endTime,
            export);
        sharingLog.sendToServer();
      } catch (Exception ex) {
        log.error(ex);
      }
    });
    trackerThread.start();
  }

  /**
   * Removes the link completely
   */
  public void delete() throws PublicLinkException {
    if (Utils.isStringNotNullOrEmpty(sk)) {
      deleteItem(
          TableName.MAIN.name,
          Field.PK.getName(),
          pk,
          Field.SK.getName(),
          sk,
          DataEntityType.PUBLIC_LINK);
      DBLink = null;
      Thread trackerThread = new Thread(() -> {
        try {
          String email = "no_data";
          String device = "no_data";

          Item user = Users.getUserById(userId);
          if (user != null && user.hasAttribute(Field.USER_EMAIL.getName())) {
            email = user.getString(Field.USER_EMAIL.getName());
            Iterator<Item> it = Sessions.getLastUserSession(userId);
            if (it.hasNext()) {
              Item mostRecentSession = it.next();
              if (mostRecentSession != null) {
                device = mostRecentSession.getString(Field.DEVICE.getName());
              }
            }
          }

          // todo log comes with "#" in start of :userId. why?

          SharingLog sharingLog = new SharingLog(
              userId,
              fileId,
              StorageType.getStorageType(storageType),
              email,
              GMTHelper.utcCurrentTime(),
              true,
              SharingTypes.PUBLIC_LINK,
              SharingActions.LINK_DELETED,
              device,
              endTime,
              export);
          sharingLog.sendToServer();
        } catch (Exception e) {
          log.error(e);
        }
      });
      trackerThread.start();
    } else {
      throw new PublicLinkException(
          "ExternalId or userId aren't defined", PublicLinkErrorCodes.PARAMETERS_ARE_INCOMPLETE);
    }
  }

  public boolean validateXSession(final String token, final String xSessionId)
      throws PublicLinkException {

    if (!Utils.isStringNotNullOrEmpty(token)) {
      throw new PublicLinkException(
          "Token isn't specified", PublicLinkErrorCodes.TOKEN_NOT_SPECIFIED);
    }

    if (DBLink == null && !findLinkByToken(token)) {
      throw new PublicLinkException(
          "Unable to find link based on parameters", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }

    boolean isEnabled = this.isEnabled();

    if (!isEnabled) {
      throw new PublicLinkException("Link isn't enabled", PublicLinkErrorCodes.LINK_DISABLED);
    }

    String dbToken = DBLink.hasAttribute(Field.TOKEN.getName())
        ? DBLink.getString(Field.TOKEN.getName())
        : UUID.randomUUID().toString();

    if (!dbToken.equals(token)) {
      throw new PublicLinkException("Token is incorrect", PublicLinkErrorCodes.TOKEN_IS_INVALID);
    }

    Item session = XenonSessions.getSession(fileId, xSessionId);

    if (session == null) {
      throw new PublicLinkException(
          "XSession doesn't exist", PublicLinkErrorCodes.XSESSION_IS_INVALID);
    }

    long expirationTime = this.getExpirationTime();
    if (expirationTime > 0 && GMTHelper.utcCurrentTime() > expirationTime) {
      throw new PublicLinkException("Link has expired", PublicLinkErrorCodes.LINK_EXPIRED);
    }

    String userId = DBLink.hasAttribute(Field.CREATOR.getName())
        ? DBLink.getString(Field.CREATOR.getName())
        : null;

    if (userId == null) {
      userId = DBLink.hasAttribute("updater") ? DBLink.getString("updater") : null;
    }

    if (userId == null) {
      throw new PublicLinkException("User not found", PublicLinkErrorCodes.USER_NOT_FOUND);
    }

    Item user = Users.getUserById(userId);
    if (user == null) {
      return true; // not sure why this is true. We should review
    }

    // check if shared links are enabled for the company
    if (user.hasAttribute(Field.ORGANIZATION_ID.getName()) || isEnterprise) {
      String orgId =
          isEnterprise ? Users.ENTERPRISE_ORG_ID : user.getString(Field.ORGANIZATION_ID.getName());
      Item company = Users.getOrganizationById(orgId);
      if (company != null) {
        JsonObject options = company.hasAttribute(Field.OPTIONS.getName())
            ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
            : new JsonObject();
        JsonObject finalOptions = companyOptions.copy().mergeIn(options, true);
        if (finalOptions.containsKey(Field.SHARED_LINKS.getName())
            && !finalOptions.getBoolean(Field.SHARED_LINKS.getName())) {
          throw new PublicLinkException(
              "Public links aren't allowed", PublicLinkErrorCodes.LINKS_NOT_ALLOWED_BY_COMPANY);
        }
      }
    }

    return checkLicense(user.getString(Field.USER_EMAIL.getName()));
  }

  // check license XENON-18273
  private boolean checkLicense(String userEmail) throws PublicLinkException {
    Calendar calendar = Calendar.getInstance();
    // check license after 01/07/20 - COVID free period ends there
    if ((calendar.get(Calendar.YEAR) != 2020 || calendar.get(Calendar.MONTH) >= 6)
        && !isEnterprise) {
      HttpResponse<String> licenseResponse;

      String traceId = null;
      try {
        traceId = AWSXRay.getGlobalRecorder().getCurrentSegment().getTraceId().toString();
      } catch (Exception e) {
        log.warn("Cannot get traceId", e);
      }
      try {
        licenseResponse = LicensingService.hasAnyKudo(userEmail, null, traceId);
      } catch (Exception e) {
        throw new PublicLinkException(
            "Unable to check user's license", PublicLinkErrorCodes.UNABLE_TO_CHECK_LICENSE);
      }

      // LS fallback
      if (Objects.isNull(licenseResponse)) {
        return true;
      }
      boolean isLicenseExpired = licenseResponse.getStatus() != 200;
      if (isLicenseExpired) {
        throw new PublicLinkException(
            "License expired", PublicLinkErrorCodes.KUDO_LICENSE_HAS_EXPIRED);
      } else {
        return true;
      }
    }
    return true;
  }

  public boolean findLinkByToken(final String token) {
    Item newSharedLink = Item.fromJSON((String) getFromCache(pk, DataEntityType.PUBLIC_LINK));
    if (newSharedLink != null
        && newSharedLink.hasAttribute(Field.TOKEN.getName())
        && newSharedLink.getString(Field.TOKEN.getName()).equals(token)) {
      DBLink = newSharedLink;
      storageType = DBLink.getString(Field.STORAGE_TYPE.getName());
      filename = DBLink.getString(Field.FILE_NAME.getName());
      updateValuesFromDB();
      return true;
    } else {
      for (Item item : query(
          TableName.MAIN.name,
          null,
          new QuerySpec()
              .withKeyConditionExpression("pk = :pk")
              .withFilterExpression("#token=:token")
              .withNameMap(new NameMap().with("#token", Field.TOKEN.getName()))
              .withValueMap(new ValueMap().withString(":pk", pk).withString(":token", token)),
          DataEntityType.PUBLIC_LINK)) {
        newSharedLink = item;
        if (newSharedLink.getString(Field.TOKEN.getName()).equals(token)) {
          break;
        } else {
          newSharedLink = null;
        }
      }
      if (newSharedLink != null) {
        populateCache(pk, newSharedLink.toJSON(), 60 * 60, DataEntityType.PUBLIC_LINK);
      }
    }
    if (newSharedLink != null) {
      DBLink = newSharedLink;
      storageType = DBLink.getString(Field.STORAGE_TYPE.getName());
      filename = DBLink.getString(Field.FILE_NAME.getName());
      updateValuesFromDB();
    }
    return newSharedLink != null;
  }

  /**
   * Check if link is accessible
   *
   * @param token    {String} Token required for link usage
   * @param password [String] Password if link is protected with password
   * @return {boolean} - true if valid, false otherwise
   * @throws PublicLinkException exception is thrown if link is unavailable. See
   *                             PublicLinkErrorCodes
   */
  public boolean isValid(final String token, final String password) throws PublicLinkException {

    if (!Utils.isStringNotNullOrEmpty(token)) {
      throw new PublicLinkException(
          "Token isn't specified", PublicLinkErrorCodes.TOKEN_NOT_SPECIFIED);
    }

    if (DBLink == null && !findLinkByToken(token)) {
      throw new PublicLinkException(
          "Unable to find link based on parameters", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }

    boolean isEnabled = this.isEnabled();

    if (!isEnabled) {
      throw new PublicLinkException("Link isn't enabled", PublicLinkErrorCodes.LINK_DISABLED);
    }

    String dbToken = DBLink.hasAttribute(Field.TOKEN.getName())
        ? DBLink.getString(Field.TOKEN.getName())
        : UUID.randomUUID().toString();

    if (!dbToken.equals(token)) {
      throw new PublicLinkException("Token is incorrect", PublicLinkErrorCodes.TOKEN_IS_INVALID);
    }

    long expirationTime = this.getExpirationTime();
    if (expirationTime > 0 && GMTHelper.utcCurrentTime() > expirationTime) {
      throw new PublicLinkException("Link has expired", PublicLinkErrorCodes.LINK_EXPIRED);
    }

    this.checkIfPasswordIsCorrect(password); // if password is incorrect - exception will be thrown

    String userId = DBLink.hasAttribute(Field.CREATOR.getName())
        ? DBLink.getString(Field.CREATOR.getName())
        : null;

    if (userId == null) {
      userId = DBLink.hasAttribute("updater") ? DBLink.getString("updater") : null;
    }

    if (userId == null) {
      throw new PublicLinkException("User not found", PublicLinkErrorCodes.USER_NOT_FOUND);
    }

    Item user = Users.getUserById(userId);
    if (user == null) {
      return true; // not sure why this is true. We should review
    }

    // check if shared links are enabled for the company
    if (user.hasAttribute(Field.ORGANIZATION_ID.getName()) || isEnterprise) {
      String orgId =
          isEnterprise ? Users.ENTERPRISE_ORG_ID : user.getString(Field.ORGANIZATION_ID.getName());
      Item company = Users.getOrganizationById(orgId);
      if (company != null) {
        JsonObject options = company.hasAttribute(Field.OPTIONS.getName())
            ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
            : new JsonObject();
        JsonObject finalOptions = companyOptions.copy().mergeIn(options, true);
        if (finalOptions.containsKey(Field.SHARED_LINKS.getName())
            && !finalOptions.getBoolean(Field.SHARED_LINKS.getName())) {
          throw new PublicLinkException(
              "Public links aren't allowed", PublicLinkErrorCodes.LINKS_NOT_ALLOWED_BY_COMPANY);
        }
      }
    }

    return checkLicense(user.getString(Field.USER_EMAIL.getName()));
  }

  private void checkIfPasswordIsCorrect(final String passwordHeader) throws PublicLinkException {
    if (this.isPasswordSet()) {
      if (Utils.isStringNotNullOrEmpty(passwordHeader)) {
        final String DBPass = DBLink.getString(Field.PASSWORD.getName());
        HashMap<String, String> values = LinkService.parsePasswordHeader(passwordHeader);

        final String nonce = values.get(Field.NONCE.getName());
        final String response = values.get("response");
        final String ha1 = values.get("ha1");
        // let's not share it - just use a constant on each platform
        final String uri = "GET:/files/link";

        if (!Utils.isStringNotNullOrEmpty(nonce)) {
          throw new PublicLinkException(
              "Nonce is required", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
        } else {
          // let's check that the password is encoded correctly
          Item nonceDb = getItem(
              TableName.TEMP.name,
              Field.PK.getName(),
              "nonce#" + nonce,
              Field.SK.getName(),
              nonce,
              false,
              DataEntityType.PUBLIC_LINK);

          // if such nonce exists in the DB - means that it was most likely generated on the FL
          // server
          if (nonceDb != null) {

            // if it's a long nonce (for Xenon) - don't remove it. It'll be removed after TTL (2
            // hours)
            if (!nonce.startsWith("L")) {
              // just remove nonce from the DB to make sure it cannot be reused
              deleteItem(
                  TableName.TEMP.name,
                  Field.PK.getName(),
                  "nonce#" + nonce,
                  Field.SK.getName(),
                  nonce,
                  DataEntityType.PUBLIC_LINK);
            }

            try {
              String ha2 = EncryptHelper.md5Hex(uri);
              String serverResponse = EncryptHelper.md5Hex(ha1 + ":" + nonce + ":" + ha2);
              if (!serverResponse.equals(response)) {
                throw new PublicLinkException(
                    "Digest responses aren't equal", PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
              } else {
                final String encryptedPassword = EncryptHelper.encrypt(ha1, PublicLink.secretKey);
                if (!encryptedPassword.equals(DBPass)) {
                  throw new PublicLinkException(
                      "Password is incorrect", PublicLinkErrorCodes.PASSWORD_IS_INCORRECT);
                }
              }
            } catch (PublicLinkException ple) {
              // just rethrow
              throw ple;
            } catch (Exception encryptionException) {
              log.error(encryptionException);
              throw new PublicLinkException(
                  "Couldn't encrypt the password: " + encryptionException.getMessage(),
                  PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
            }

          } else {
            throw new PublicLinkException(
                "Nonce isn't found in DB", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
          }
        }
      } else {
        throw new PublicLinkException(
            "Password is required", PublicLinkErrorCodes.PASSWORD_IS_REQUIRED);
      }
    }
  }

  /**
   * Validates that link exists. It won't be fetched again if it has been already
   *
   * @throws PublicLinkException - if link isn't found
   */
  private void checkLinkExistance() throws PublicLinkException {
    if (DBLink == null && !doesNewLinkExist()) {
      throw new PublicLinkException(
          "Unable to find link based on parameters", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }
  }

  public String getEndUserLink() throws PublicLinkException {
    if (DBLink == null && !doesNewLinkExist()) {
      throw new PublicLinkException(
          "Unable to find link based on parameters", PublicLinkErrorCodes.LINK_NOT_FOUND);
    }
    if (DBLink.hasAttribute("shortURL")
        && DBLink.hasAttribute("host")
        && DBLink.getString("host").equals(instanceURL)) {
      return DBLink.getString("shortURL");
    }

    if (DBLink.hasAttribute(Field.EXTERNAL_ID.getName())
        && Utils.isStringNotNullOrEmpty(DBLink.getString(Field.EXTERNAL_ID.getName()))) {
      // let's try to encapsulate if possible
      return instanceURL + "file/"
          + Utils.getEncapsulatedId(
              StorageType.getStorageType(DBLink.getString(Field.STORAGE_TYPE.getName())),
              DBLink.getString(Field.EXTERNAL_ID.getName()),
              fileId)
          + "?token=" + DBLink.getString(Field.TOKEN.getName());
    }
    return instanceURL + "file/" + fileId + "?token=" + DBLink.getString(Field.TOKEN.getName());
  }

  public boolean getExport() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.EXPORT.getName()) && DBLink.getBoolean(Field.EXPORT.getName());
  }

  public void setExport(boolean export) {
    this.export = export;
  }

  public boolean isEnabled() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.ENABLED.getName())
        && DBLink.getBoolean(Field.ENABLED.getName());
  }

  public boolean isExpired() throws PublicLinkException {
    this.checkLinkExistance();
    return (getExpirationTime() != 0L) && System.currentTimeMillis() > getExpirationTime();
  }

  public long getExpirationTime() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute("expiryDate") ? DBLink.getLong("expiryDate") : 0L;
  }

  public boolean isPasswordSet() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.PASSWORD.getName())
        && DBLink.getString(Field.PASSWORD.getName()).length() > 0;
  }

  public JsonObject getInfoInJSON() throws PublicLinkException {
    this.checkLinkExistance();
    JsonObject linkMetadata = new JsonObject();
    linkMetadata.put(Field.USER_ID.getName(), userId);
    linkMetadata.put(Field.EXTERNAL_ID.getName(), externalId);
    linkMetadata.put(Field.FILE_ID.getName(), fileId);
    linkMetadata.put("isEnabled", isEnabled());
    linkMetadata.put(Field.EXPORT.getName(), getExport());
    linkMetadata.put("expirationTime", getExpirationTime());
    linkMetadata.put("passwordRequired", isPasswordSet());
    linkMetadata.put(Field.LINK.getName(), this.getEndUserLink());

    return linkMetadata;
  }

  public List<String> getOwners() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute("owners") ? DBLink.getList("owners") : null;
  }

  public String getToken() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.TOKEN.getName())
        ? DBLink.getString(Field.TOKEN.getName())
        : null;
  }

  public String getFilename() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.FILE_NAME.getName())
        ? DBLink.getString(Field.FILE_NAME.getName())
        : null;
  }

  public void setFilename(String filename) {
    this.filename = filename;
  }

  public String getLinkOwnerIdentity() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.LINK_OWNER_IDENTITY.getName())
        ? DBLink.getString(Field.LINK_OWNER_IDENTITY.getName())
        : this.linkOwnerIdentity;
  }

  public void setLinkOwnerIdentity(String linkOwnerIdentity) {
    this.linkOwnerIdentity = linkOwnerIdentity;
  }

  public String getStorageType() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.STORAGE_TYPE.getName())
        ? DBLink.getString(Field.STORAGE_TYPE.getName())
        : null;
  }

  public void setStorageType(String storageType) {
    this.storageType = storageType;
  }

  public String getLocation() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.LOCATION.getName())
        ? DBLink.getString(Field.LOCATION.getName())
        : null;
  }

  public List<String> getCollaborators() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.COLLABORATORS.getName())
        ? DBLink.getList(Field.COLLABORATORS.getName())
        : new ArrayList<String>();
  }

  public String getLastUser() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.CREATOR.getName())
        ? DBLink.getString(Field.CREATOR.getName())
        : DBLink.getString("updater");
  }

  public String getPath() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.PATH.getName())
        ? DBLink.getString(Field.PATH.getName())
        : null;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getDriveId() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.DRIVE_ID.getName())
        ? DBLink.getString(Field.DRIVE_ID.getName())
        : null;
  }

  public String getSharedLink() throws PublicLinkException {
    this.checkLinkExistance();
    return DBLink.hasAttribute(Field.SHARED_LINK.getName())
        ? DBLink.getString(Field.SHARED_LINK.getName())
        : null;
  }

  public void setSharedLink(String sharedLink) {
    this.sharedLink = sharedLink;
  }

  public String getExternalId() throws PublicLinkException {
    this.checkLinkExistance();
    return this.externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getUserId() throws PublicLinkException {
    this.checkLinkExistance();
    return this.userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public void setCollaboratorsList(List<String> collaboratorsList) {
    this.collaboratorsList = collaboratorsList;
  }

  public void setEndTime(long endTime) {
    if (endTime <= System.currentTimeMillis()) {
      throw new IllegalArgumentException("endTime should be > current time");
    }
    this.endTime = endTime;
  }

  public void setFileId(String fileId) {
    this.fileId = fileId;
  }

  public void resetPassword() {
    //        TODO: do we need any particular check here?
    this.password = "";
  }

  public void setPassword(String password) throws PublicLinkException {

    HashMap<String, String> values = LinkService.parsePasswordHeader(password);

    final String nonce = values.get(Field.NONCE.getName());
    final String response = values.get("response");
    final String ha1 = values.get("ha1");
    // let's not share it - just use a constant on each platform
    final String uri = "GET:/files/link";

    if (!Utils.isStringNotNullOrEmpty(nonce)) {
      throw new PublicLinkException("Nonce is required", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
    } else {
      // let's check that the password is encoded correctly
      Item nonceDb = getItem(
          TableName.TEMP.name,
          Field.PK.getName(),
          "nonce#" + nonce,
          Field.SK.getName(),
          nonce,
          false,
          DataEntityType.PUBLIC_LINK);

      // if such nonce exists in the DB - means that it was most likely generated on the FL server
      if (nonceDb != null) {

        // just remove nonce from the DB to make sure it cannot be reused
        deleteItem(
            TableName.TEMP.name,
            Field.PK.getName(),
            "nonce#" + nonce,
            Field.SK.getName(),
            nonce,
            DataEntityType.PUBLIC_LINK);

        try {
          String ha2 = EncryptHelper.md5Hex(uri);
          String serverResponse = EncryptHelper.md5Hex(ha1 + ":" + nonce + ":" + ha2);
          if (!serverResponse.equals(response)) {
            throw new PublicLinkException(
                "Digest responses aren't equal", PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
          } else {
            this.password = EncryptHelper.encrypt(ha1, PublicLink.secretKey);
          }
        } catch (Exception encryptionException) {
          log.error(encryptionException);
          throw new PublicLinkException(
              "Couldn't encrypt the password: " + encryptionException.getMessage(),
              PublicLinkErrorCodes.PASSWORD_IS_INSECURE);
        }

      } else {
        throw new PublicLinkException(
            "Nonce isn't found in DB", PublicLinkErrorCodes.NONCE_IS_REQUIRED);
      }
    }
  }
}
