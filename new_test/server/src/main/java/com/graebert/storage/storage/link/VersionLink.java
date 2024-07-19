package com.graebert.storage.storage.link;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.link.repository.VersionLinkRepository;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

/**
 * Represents Link, pointing to a specific version (one of VersionType) :
 * 1. current
 * 2. last-printed
 * 3. specific (set with versionId)
 */
public abstract class VersionLink extends BaseLink {
  protected String versionId;
  protected VersionType versionType;

  protected VersionLink() {}

  protected VersionLink(
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
      String passwordHeader,
      String versionId,
      VersionType versionType)
      throws PublicLinkException {
    super(
        userId,
        ownerIdentity,
        sheetId,
        objectId,
        externalId,
        storageType,
        objectName,
        mimeType,
        expireDate,
        export,
        passwordHeader);
    this.versionId = versionId;
    this.versionType = versionType;
    this.generateShortLink();
  }

  @Override
  public Item toItem() {
    return super.toItem()
        .withPrimaryKey(
            Dataplane.pk,
            VersionLinkRepository.PREFIX.concat(Dataplane.hashSeparator).concat(objectId),
            Dataplane.sk,
            token
                .concat(Dataplane.hashSeparator)
                .concat(versionId)
                .concat(Dataplane.hashSeparator)
                .concat(type.toString()))
        .withString(
            VersionLinkRepository.INDEX_SK,
            objectId
                .concat(Dataplane.hashSeparator)
                .concat(type.toString())
                .concat(Dataplane.hashSeparator)
                .concat(versionId)
                .concat(this.hasSheetId() ? Dataplane.hashSeparator.concat(sheetId) : ""))
        .withString(Field.VERSION_ID.getName(), versionId)
        .withString(Field.VERSION_TYPE.getName(), versionType.toString());
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put(Field.VERSION_ID.getName(), versionId)
        .put(Field.VERSION_TYPE.getName(), versionType.toString());
  }

  @Override
  protected void insertBaseData(Item item) {
    super.insertBaseData(item);
    setVersionId(item.getString(Field.VERSION_ID.getName()));
    setVersionType(VersionType.parse(item.getString(Field.VERSION_TYPE.getName())));
  }

  public String getVersionId() {
    return versionId;
  }

  public void setVersionId(String versionId) {
    this.versionId = versionId;
  }

  public VersionType getVersionType() {
    return versionType;
  }

  public void setVersionType(VersionType versionType) {
    this.versionType = versionType;
  }
}
