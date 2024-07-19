package com.graebert.storage.Entities;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.util.Base64;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import io.vertx.core.json.JsonArray;

public class Xref extends Dataplane {
  private static final String XREF_PREFIX = "xref#";
  private static final String XREF_TABLE = TableName.TEMP.name;

  private String pk;
  private String sk;

  private StorageType storageType;
  private String externalId;
  private String userId;

  private String masterFileId;
  private String path;
  private JsonArray xrefs;

  private Item xrefItem;

  public Xref(
      String userId,
      String masterFileId,
      StorageType storageType,
      String externalId,
      JsonArray xrefs,
      String path) {
    this.userId = userId;
    this.masterFileId = masterFileId;
    this.storageType = storageType;
    this.externalId = externalId;
    this.path = path;

    this.pk = makePk(this.storageType, this.userId, this.externalId, this.masterFileId);
    this.sk = Base64.encodeAsString(path.getBytes());

    this.xrefs = xrefs;

    xrefItem = new Item()
        .withString(Dataplane.pk, this.pk)
        .withString(Dataplane.sk, this.sk)
        .withString(Field.STORAGE_TYPE.getName(), this.storageType.name())
        .withString(Field.EXTERNAL_ID.getName(), this.externalId)
        .withString(Field.FILE_ID.getName(), this.masterFileId)
        .withString(Field.USER_ID.getName(), this.userId)
        .withString(Field.PATH.getName(), this.path)
        .withJSON("xrefs", this.xrefs.encode())
        .withLong(Dataplane.ttl, TtlUtils.inOneWeek());
  }

  private Xref(Item item) {
    if (item != null) {
      updateFields(item);
    }
  }

  public static Xref getXref(
      String userId, StorageType storageType, String externalId, String fileId, String path)
      throws Exception {
    try {
      String pk = Xref.makePk(storageType, userId, externalId, fileId);
      Item xrefItem = getItemFromDB(
          XREF_TABLE,
          Dataplane.pk,
          pk,
          Dataplane.sk,
          Base64.encodeAsString(path.getBytes()),
          DataEntityType.XREF);
      if (xrefItem != null) {
        return new Xref(xrefItem);
      } else {
        return null;
      }
    } catch (Exception e) {
      throw new Exception(String.format(
          "Exception on trying to get xref. Supplied params: %s; %s; %s; %s; %s",
          userId, storageType != null ? storageType.name() : "null", externalId, fileId, path));
    }
  }

  private static String makePk(
      StorageType storageType, String userId, String externalId, String masterFileId) {
    return XREF_PREFIX
        .concat(storageType.name())
        .concat("#")
        .concat(userId)
        .concat("#")
        .concat(externalId)
        .concat("#")
        .concat(masterFileId);
  }

  private void updateFields(Item item) {
    this.setXrefs(new JsonArray(item.getJSON("xrefs")));
    this.setExternalId(item.getString(Field.EXTERNAL_ID.getName()));
    this.setMasterFileId(item.getString(Field.FILE_ID.getName()));
    this.setPk(item.getString(Field.PK.getName()));
    this.setSk(item.getString(Field.SK.getName()));
    this.setUserId(item.getString(Field.USER_ID.getName()));
    this.setPath(item.getString(Field.PATH.getName()));
    this.setStorageType(StorageType.getStorageType(item.getString(Field.STORAGE_TYPE.getName())));

    this.xrefItem = item;
  }

  public void createOrUpdate() {
    putItem(XREF_TABLE, pk, sk, xrefItem, DataEntityType.XREF);
  }

  // GETTERS & SETTERS

  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public void setStorageType(StorageType storageType) {
    this.storageType = storageType;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public String getMasterFileId() {
    return masterFileId;
  }

  public void setMasterFileId(String masterFileId) {
    this.masterFileId = masterFileId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public JsonArray getXrefs() {
    return xrefs;
  }

  public void setXrefs(JsonArray xrefs) {
    this.xrefs = xrefs;
  }

  public Item getXrefItem() {
    return xrefItem;
  }

  public void setXrefItem(Item xrefItem) {
    this.xrefItem = xrefItem;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }
}
