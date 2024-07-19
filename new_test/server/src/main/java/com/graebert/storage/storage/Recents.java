package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Objects;
import org.jetbrains.annotations.NonNls;

public class Recents extends Dataplane {
  public static final String PREFIX = "recent";
  public static final String pk = Field.PK.getName();
  public static final String sk = Field.SK.getName();

  @NonNls
  public static final String tableName = TableName.MAIN.name;

  private static final String index = "sk-pk-index";

  public static JsonArray getRecentFilesJson(String userId) {
    JsonArray result = new JsonArray();

    getRecentFilesByUserId(userId).forEachRemaining(recent -> {
      JsonObject item = getJsonOfRecentFile(recent);
      result.add(item);
    });

    result
        .getList()
        .sort(Comparator.comparing(o -> ((JsonObject) o).getLong(Field.TIMESTAMP.getName()))
            .reversed());

    return result;
  }

  public static Iterator<Item> getRecentFilesByUserId(String userId) {
    return query(
            tableName,
            null,
            new QuerySpec().withHashKey(pk, makePk(userId)),
            DataEntityType.RECENTS)
        .iterator();
  }

  public static Item getRecentFile(String userId, String fileId, StorageType storageType) {
    return getItemFromCache(
        tableName, pk, makePk(userId), sk, makeSk(storageType, fileId), DataEntityType.RECENTS);
  }

  public static Iterator<Item> getRecentFilesByUserId(String userId, String externalId) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withHashKey(pk, makePk(userId))
                .withFilterExpression("externalId = :externalId")
                .withValueMap(new ValueMap().withString(":externalId", externalId)),
            DataEntityType.RECENTS)
        .iterator();
  }

  public static Iterator<Item> getRecentFilesByUserIdAndStorageType(
      String userId, String storageType) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
                .withValueMap(new ValueMap()
                    .withString(":pk", makePk(userId))
                    .withString(":sk", storageType)),
            DataEntityType.RECENTS)
        .iterator();
  }

  public static Iterator<Item> getRecentFilesByFileIdIndex(StorageType storageType, String fileId) {
    return query(
            tableName,
            index,
            new QuerySpec().withHashKey(sk, makeSk(storageType, fileId)),
            DataEntityType.RECENTS)
        .iterator();
  }

  public static void deleteRecentFile(String userId, StorageType storageType, String fileId) {
    deleteItem(
        tableName, pk, makePk(userId), sk, makeSk(storageType, fileId), DataEntityType.RECENTS);
  }

  public static void putRecentFile(
      String userId,
      StorageType storageType,
      String externalId,
      String fileId,
      String filename,
      String parentId,
      String thumbnailName,
      Long actionTime) {
    if (!Utils.isStringNotNullOrEmpty(filename)) {
      log.error("filename is null for fileId - " + fileId);
      return;
    }
    Item recent = new Item()
        .withPrimaryKey(pk, makePk(userId), sk, makeSk(storageType, fileId))
        .withNumber(
            "actionTime", Objects.nonNull(actionTime) ? actionTime : GMTHelper.utcCurrentTime())
        .withString(Field.FILE_NAME.getName(), filename)
        .withString(Field.STORAGE_TYPE.getName(), storageType.name())
        .withString(Field.FILE_ID.getName(), fileId)
        .withString(
            Field.EXTERNAL_ID.getName(),
            externalId != null && !externalId.isEmpty() ? externalId : "_");
    if (!parentId.isEmpty()) {
      recent.withString(Field.PARENT_ID.getName(), parentId);
    }
    if (Utils.isStringNotNullOrEmpty(thumbnailName)) {
      recent.withString(Field.THUMBNAIL_NAME.getName(), thumbnailName);
    }
    log.info("New recent file " + recent.toJSON());

    putItem(recent, DataEntityType.RECENTS);
  }

  public static JsonObject getJsonOfRecentFile(Item recent) {

    String shortST = StorageType.getShort(recent.getString(Field.STORAGE_TYPE.getName()));
    String externalId = recent.getString(Field.EXTERNAL_ID.getName());

    String fileId = recent.hasAttribute(Field.FILE_ID.getName())
        ? recent.getString(Field.FILE_ID.getName())
        // should be removed in some time when recents will be regenerated
        : recent.getString("fullFileId");

    JsonObject j = new JsonObject()
        .put(
            Field.TIMESTAMP.getName(),
            recent.hasAttribute("actionTime") ? recent.getLong("actionTime") : 0)
        .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(shortST, externalId, fileId))
        .put(
            Field.STORAGE_TYPE.getName(),
            StorageType.getStorageType(recent.getString(Field.STORAGE_TYPE.getName()))
                .toString())
        .put(Field.FILE_NAME.getName(), recent.getString(Field.FILE_NAME.getName()))
        .put(
            Field.THUMBNAIL.getName(),
            ThumbnailsManager.getThumbnailURL(
                config,
                recent.getString(Field.THUMBNAIL_NAME.getName()),
                !StorageType.WEBDAV.name().equals(recent.getString(Field.STORAGE_TYPE.getName()))));
    if (recent.getString(Field.PARENT_ID.getName()) != null
        && !recent.getString(Field.PARENT_ID.getName()).isEmpty()) {
      j.put(
          Field.FOLDER_ID.getName(),
          Utils.getEncapsulatedId(
              shortST, externalId, recent.getString(Field.PARENT_ID.getName())));
    }
    return j;
  }

  public static String makePk(String userId) {
    return PREFIX.concat(hashSeparator).concat(userId);
  }

  public static String makeSk(StorageType storageType, String fileId) {
    return storageType.name().concat(hashSeparator).concat(fileId);
    // return storageType.name().concat("#").concat(externalId).concat("#").concat(fileId);
  }

  public static String getFileId(Item item) {
    String fileId = getSk(item);
    if (fileId.contains(hashSeparator)) {
      String[] id = fileId.split(hashSeparator);
      return id[id.length - 1];
    }
    return fileId;
  }

  // getters and setters
  public static String getPk(Item item) {
    return item.getString(pk);
  }

  public static String getUserId(Item item) {
    return item.getString(pk).substring((PREFIX + hashSeparator).length());
  }

  public static String getSk(Item item) {
    return item.getString(sk);
  }
}
