package com.graebert.storage.storage.link;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.common.DbConvertable;
import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.storage.link.repository.LinkService;
import com.graebert.storage.storage.link.repository.VersionLinkRepository;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.urls.ShortURL;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;

/**
 * Class represents Base Link Object, which is pointing to specific object
 */
public abstract class BaseLink implements JsonConvertable, DbConvertable {
  protected String token;
  protected LinkType type;

  protected boolean enabled = true;
  protected boolean export;
  protected String password = null;
  protected long expireDate = 0;

  protected String sheetId;
  protected String objectId;
  protected String externalId;
  protected StorageType storageType;
  protected String host;
  protected String shortUrl;

  protected String objectName;
  protected String mimeType;

  protected long creationDate;

  protected String userId;
  protected String ownerIdentity;

  protected String updater;
  protected long updateDate;

  // additional info from storage
  protected String sharedLink = null;
  protected List<String> collaboratorsList = null;
  protected String path = null;

  protected BaseLink() {}

  /**
   * Constructor to create new Link, should be overwritten
   * @param userId userId
   * @param ownerIdentity - email or external identity
   * @param objectId - object pure id
   * @param externalId - object external id
   * @param storageType - object storageType
   * @param objectName - object name
   * @param mimeType - type of object (Field.FILE.getName() | Field.FOLDER.getName())
   * @param expireDate - expiration timestamp in sec or 0 for eternal
   * @param export - not sure todo
   * @param passwordHeader - password header
   * @throws PublicLinkException - in case of bad password
   */
  protected BaseLink(
      String userId,
      String ownerIdentity,
      String sheetId,
      String objectId,
      String externalId,
      StorageType storageType,
      String objectName,
      String mimeType,
      long expireDate,
      Boolean export,
      String passwordHeader)
      throws PublicLinkException {
    this.userId = userId;
    this.ownerIdentity = ownerIdentity;
    this.sheetId = sheetId;
    this.objectId = objectId;
    this.externalId = externalId;
    this.storageType = storageType;
    this.objectName = objectName;
    this.mimeType = mimeType;

    this.token = Utils.generateUUID();
    this.enabled = true;
    this.export = Objects.nonNull(export) && export;
    this.expireDate = expireDate > 0 ? expireDate : 0;

    this.host = LinkConfiguration.getInstance().getInstanceUrl();

    this.creationDate = GMTHelper.utcCurrentTime();
    this.updater = userId;
    this.updateDate = creationDate;

    if (Utils.isStringNotNullOrEmpty(passwordHeader)) {
      LinkService.setPassword(this, passwordHeader);
    }
  }

  /**
   * Method to compose full end user url, pointing to object
   * @return String - url
   */
  public abstract String getEndUserLink();

  /**
   * Generates new url based on class type and applies short version
   */
  protected void generateShortLink() {
    String endUserURL = this.getEndUserLink();
    this.applyShortUrl(endUserURL);
  }

  /**
   * Generates short url based on input one and applies it to object
   * @param endUserURL - full url pointing to object
   */
  private void applyShortUrl(String endUserURL) {
    final String shortURL = new ShortURL(endUserURL).getShortURL();
    if (Utils.isStringNotNullOrEmpty(shortURL)) {
      setShortUrl(shortURL);
      setHost(LinkConfiguration.getInstance().getInstanceUrl());
    }
  }

  /**
   * Method inserts basic link info to objects using setters.
   * Should be reused and overwritten by extendable objects
   * @param item - DynamoDbItem
   */
  protected void insertBaseData(Item item) {
    setToken(item.getString(Field.TOKEN.getName()));
    setType(LinkType.parse(item.getString(Field.TYPE.getName())));
    setEnabled(item.getBoolean(Field.ENABLED.getName()));
    setExport(item.getBoolean(Field.EXPORT.getName()));
    setExpireDate(item.getLong("expireDate"));
    setSheetId(item.getString(Field.SHEET_ID.getName()));
    setObjectId(item.getString(Field.OBJECT_ID.getName()));
    setExternalId(item.getString(Field.EXTERNAL_ID.getName()));
    setStorageType(StorageType.getStorageType(item.getString(Field.STORAGE_TYPE.getName())));
    setHost(item.getString("host"));
    setShortUrl(item.getString("shortUrl"));
    setObjectName(item.getString("objectName"));
    setMimeType(item.getString(Field.MIME_TYPE.getName()));
    setCreationDate(item.getLong(Field.CREATION_DATE.getName()));
    setUserId(item.getString(Field.USER_ID.getName()));
    setOwnerIdentity(item.getString(Field.OWNER_IDENTITY.getName()));
    setUpdater(item.getString("updater"));
    setUpdateDate(item.getLong(Field.UPDATE_DATE.getName()));
    if (item.hasAttribute(Field.PASSWORD.getName())) {
      setPassword(item.getString(Field.PASSWORD.getName()));
    }
  }

  /**
   * Returns true if password is set with this link
   * @return boolean
   */
  public boolean isPasswordSet() {
    return Objects.nonNull(this.password);
  }

  /**
   * Returns true if link has target sheetId
   * @return boolean
   */
  public boolean hasSheetId() {
    return Objects.nonNull(sheetId);
  }

  /**
   * Converts object to Item, to work with db
   * @return Item
   */
  @Override
  public Item toItem() {
    Item item = new Item()
        // Pk and Sk are not really used as they are rewritten by child classes
        // BaseLink should fit default Kudo links if required, so may need them
        .withPrimaryKey(
            Dataplane.pk,
            VersionLinkRepository.PREFIX.concat(Dataplane.hashSeparator).concat(this.objectId),
            Dataplane.sk,
            this.token)

        // May be rewritten by child classes
        .withString(
            VersionLinkRepository.INDEX_PK,
            externalId.concat(Dataplane.hashSeparator).concat(userId))
        .withString(
            VersionLinkRepository.INDEX_SK,
            objectId
                .concat(Dataplane.hashSeparator)
                .concat(type.toString())
                .concat(this.hasSheetId() ? Dataplane.hashSeparator.concat(sheetId) : ""))
        .withString(Field.TOKEN.getName(), token)
        .withString(Field.TYPE.getName(), type.toString())
        .withBoolean(Field.ENABLED.getName(), enabled)
        .withBoolean(Field.EXPORT.getName(), export)
        .withLong("expireDate", expireDate)
        .withString(Field.OBJECT_ID.getName(), objectId)
        .withString(Field.EXTERNAL_ID.getName(), externalId)
        .withString(Field.STORAGE_TYPE.getName(), storageType.toString())
        .withString("host", host)
        .withString("shortUrl", shortUrl)
        .withString("objectName", objectName)
        .withString(Field.MIME_TYPE.getName(), mimeType)
        .withLong(Field.CREATION_DATE.getName(), creationDate)
        .withString(Field.USER_ID.getName(), userId)
        .withString(Field.OWNER_IDENTITY.getName(), ownerIdentity)
        .withString("updater", updater)
        .withLong(Field.UPDATE_DATE.getName(), updateDate);

    if (this.isPasswordSet()) {
      item.withString(Field.PASSWORD.getName(), password);
    }

    if (this.hasSheetId()) {
      item.withString(Field.SHEET_ID.getName(), sheetId);
    }

    return item;
  }

  /**
   * Converts object to Json, to send with api
   * @return JsonObject
   */
  @Override
  public JsonObject toJson() {
    JsonObject jsonObject = new JsonObject()
        .put(Field.TYPE.getName(), this.type.toString())
        .put(Field.TOKEN.getName(), this.token)
        .put(Field.IS_PUBLIC.getName(), this.enabled)
        .put("shortLink", this.shortUrl)
        .put(Field.LINK.getName(), this.getEndUserLink())
        .put("isEnabled", this.enabled)
        .put(Field.EXPORT.getName(), this.export)
        .put(Field.LINK_END_TIME.getName(), this.expireDate)
        .put(Field.LINK_OWNER_IDENTITY.getName(), this.ownerIdentity)
        .put(Field.OWNER_ID.getName(), this.userId)
        .put("passwordRequired", this.isPasswordSet())
        .put(Field.EXTERNAL_ID.getName(), this.externalId)
        .put(Field.FILE_ID.getName(), this.objectId)
        .put(Field.STORAGE_TYPE.getName(), this.storageType.toString());

    if (this.hasSheetId()) {
      jsonObject.put(Field.SHEET_ID.getName(), this.sheetId);
    }

    return jsonObject;
  }

  /**
   * Setters
   **/
  public void setToken(String token) {
    this.token = token;
  }

  public void setType(LinkType type) {
    this.type = type;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public void setExport(boolean export) {
    this.export = export;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public void setExpireDate(long expireDate) {
    this.expireDate = expireDate;
  }

  public void setSheetId(String sheetId) {
    this.sheetId = sheetId;
  }

  public void setObjectId(String objectId) {
    this.objectId = objectId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public void setStorageType(StorageType storageType) {
    this.storageType = storageType;
  }

  public void setHost(String host) {
    this.host = host;
  }

  public void setShortUrl(String shortUrl) {
    this.shortUrl = shortUrl;
  }

  public void setObjectName(String objectName) {
    this.objectName = objectName;
  }

  public void setMimeType(String mimeType) {
    this.mimeType = mimeType;
  }

  public void setCreationDate(long creationDate) {
    this.creationDate = creationDate;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public void setOwnerIdentity(String ownerIdentity) {
    this.ownerIdentity = ownerIdentity;
  }

  public void setUpdater(String updater) {
    this.updater = updater;
  }

  public void setUpdateDate(long updateDate) {
    this.updateDate = updateDate;
  }

  public void setSharedLink(String sharedLink) {
    this.sharedLink = sharedLink;
  }

  public void setCollaboratorsList(List<String> collaboratorsList) {
    this.collaboratorsList = collaboratorsList;
  }

  public void setPath(String path) {
    this.path = path;
  }

  /**
   * Getters
   **/
  public String getToken() {
    return token;
  }

  public LinkType getType() {
    return type;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean isExport() {
    return export;
  }

  public String getPassword() {
    return password;
  }

  public long getExpireDate() {
    return expireDate;
  }

  public String getSheetId() {
    return sheetId;
  }

  public String getObjectId() {
    return objectId;
  }

  public String getExternalId() {
    return externalId;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public String getHost() {
    return host;
  }

  public String getShortUrl() {
    return shortUrl;
  }

  public String getObjectName() {
    return objectName;
  }

  public String getMimeType() {
    return mimeType;
  }

  public long getCreationDate() {
    return creationDate;
  }

  public String getUserId() {
    return userId;
  }

  public String getOwnerIdentity() {
    return ownerIdentity;
  }

  public String getUpdater() {
    return updater;
  }

  public long getUpdateDate() {
    return updateDate;
  }

  public String getSharedLink() {
    return sharedLink;
  }

  public List<String> getCollaboratorsList() {
    return collaboratorsList;
  }

  public String getPath() {
    return path;
  }
}
