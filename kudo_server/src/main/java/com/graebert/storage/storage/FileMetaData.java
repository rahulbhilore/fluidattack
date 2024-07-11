package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FileMetaData extends Dataplane {

  private static final String tableName = TableName.TEMP.name;
  private static final String metadataPrefix = "metadata#";
  // this holds info about the filename, thumbnail link used in notifications
  // fileIds are stored fully qualified.
  // only temporary cache data that can be cleared once the last notification is sent (latest 24
  // hours)

  public static void putMetaData(
      final String id,
      final StorageType storageType,
      final String filename,
      final String thumbnail,
      final String versionId,
      final String ownerId) {
    String[] strings = id.split(":");
    String fileId = strings[0];
    if (strings.length == 2) {
      fileId = strings[1];
    }
    // AS: not updating if filename is null, later we can debug the cause using logs
    if (!Utils.isStringNotNullOrEmpty(filename)) {
      log.error("[ METADATA ] Filename is null for FileId: " + fileId + " storageType: "
          + storageType.name());
      return;
    }
    // only keep these for a week. by then all notifications should have cleared
    // DK: we should always have a versionId here, but there's a chance it's absent for some
    // storages
    log.info("[ METADATA ] Updating metadata. FileId: " + fileId + " storageType: "
        + storageType.name() + " versionId: " + versionId);
    // Keep ttl for 1 month if we are updating versionId in the metadata to avoid expiration and
    // version conflict
    Item metadata = getMetaData(fileId, storageType.name());
    if (Objects.isNull(metadata)) {
      // creating a new file metadata
      metadata = new Item()
          .withPrimaryKey(pk, metadataPrefix + fileId, sk, storageType.toString())
          .withString(Field.FILE_ID.getName(), fileId)
          .withLong(Field.TIMESTAMP.getName(), GMTHelper.utcCurrentTime())
          .withString(Field.FILE_NAME.getName(), filename);

      if (Utils.isStringNotNullOrEmpty(thumbnail)) {
        metadata.withString(Field.THUMBNAIL.getName(), thumbnail);
      } else {
        log.warn("[ METADATA ] thumbnail is null for FileId: " + fileId + " storageType: "
            + storageType.name());
      }
      if (Utils.isStringNotNullOrEmpty(versionId)) {
        metadata
            .withString(Field.VERSION_ID.getName(), versionId)
            .withLong(ttl, TtlUtils.inOneMonth());
      } else {
        metadata.withLong(ttl, TtlUtils.inOneWeek());
      }
      if (Utils.isStringNotNullOrEmpty(ownerId)) {
        metadata.withString(Field.OWNER_ID.getName(), ownerId);
      }
      putItem(tableName, metadata, DataEntityType.FILE_METADATA);
    } else {
      // updating the existing file metadata
      List<String> updateExpressionList = new ArrayList<>();
      updateExpressionList.add("fileId = :fId");
      updateExpressionList.add("#ts = :ts");
      updateExpressionList.add("filename = :fname");
      updateExpressionList.add("#ttl = :ttl");
      NameMap nameMap = new NameMap().with("#ts", Field.TIMESTAMP.getName()).with("#ttl", ttl);
      ValueMap valueMap = new ValueMap()
          .withString(":fId", fileId)
          .withLong(":ts", GMTHelper.utcCurrentTime())
          .withString(":fname", filename);
      if (Utils.isStringNotNullOrEmpty(thumbnail)) {
        updateExpressionList.add("thumbnail = :tn");
        valueMap.with(":tn", thumbnail);
      } else {
        log.warn("[ METADATA ] thumbnail is null for FileId: " + fileId + " storageType: "
            + storageType.name());
      }
      boolean hasVersionId = false;
      if (Utils.isStringNotNullOrEmpty(versionId)) {
        updateExpressionList.add("versionId = :vId");
        valueMap.with(":vId", versionId);
        hasVersionId = true;
      } else if (metadata.hasAttribute(Field.VERSION_ID.getName())) {
        hasVersionId = true;
      }
      valueMap.withLong(":ttl", (hasVersionId) ? TtlUtils.inOneMonth() : TtlUtils.inOneWeek());
      if (Utils.isStringNotNullOrEmpty(ownerId)) {
        updateExpressionList.add("ownerId = :oId");
        valueMap.with(":oId", ownerId);
      }
      String updateExpression = "SET " + String.join(", ", updateExpressionList);
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(
                  new PrimaryKey(pk, metadata.getString(pk), sk, metadata.getString(sk)))
              .withUpdateExpression(updateExpression)
              .withNameMap(nameMap)
              .withValueMap(valueMap),
          DataEntityType.FILE_METADATA);
    }
  }

  /**
   * Update versionId of all files for a workspace
   *
   * @param fileId - To get folderId and workspaceId
   * @param versionId - versionId to be updated
   */
  public static void updateOSWorkSpaceVersion(String fileId, String versionId) {
    String[] ids = fileId.split("_");
    if (ids.length == 3) {
      String folderId = ids[0];
      String workspaceId = ids[1];
      for (Item metadata : query(
          tableName,
          "sk-pk-index",
          new QuerySpec()
              .withKeyConditionExpression("sk = :sk and begins_with(pk, :pk)")
              .withFilterExpression("attribute_exists(#vId)") // to filter out deleted items
              .withNameMap(new NameMap().with("#vId", Field.VERSION_ID.getName()))
              .withValueMap(new ValueMap()
                  .withString(":sk", StorageType.ONSHAPE.name())
                  .withString(":pk", metadataPrefix + folderId + "_" + workspaceId)),
          DataEntityType.FILE_METADATA)) {
        updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(
                    new PrimaryKey(pk, metadata.getString(pk), sk, metadata.getString(sk)))
                .withUpdateExpression("SET #vId = :vId")
                .withNameMap(new NameMap().with("#vId", Field.VERSION_ID.getName()))
                .withValueMap(new ValueMap().withString(":vId", versionId)),
            DataEntityType.FILE_METADATA);
      }
    }
  }

  public static Item getMetaData(String fileId, String storage) {
    String[] ids = fileId.split(":");
    fileId = ids.length == 2 ? ids[1] : ids[0];
    log.info("[ METADATA ] Fetching metadata. FileId: " + fileId + " storageType: " + storage);
    return getItemFromCache(
        tableName, pk, metadataPrefix + fileId, sk, storage, DataEntityType.FILE_METADATA);
  }

  public static void updateLastCommentTimestamp(
      String fileId, String storageType, Long lastCommentedOn) {
    Item metadata = getMetaData(fileId, storageType);
    if (Objects.nonNull(metadata)) {
      UpdateItemSpec updateItemSpec = new UpdateItemSpec()
          .withPrimaryKey(pk, metadata.getString(pk), sk, metadata.getString(sk))
          .withUpdateExpression("set #lco = :lco")
          .withNameMap(new NameMap().with("#lco", "lastCommentedOn"))
          .withValueMap(new ValueMap().withLong(":lco", lastCommentedOn));
      updateItem(tableName, updateItemSpec, DataEntityType.FILE_METADATA);
    }
  }
}
