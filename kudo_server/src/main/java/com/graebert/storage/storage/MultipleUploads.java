package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.apache.commons.collections4.IteratorUtils;

public class MultipleUploads extends Dataplane {

  private static final String tableName = TableName.TEMP.name;
  private static final String uploadRequestPrefix = "UPLOAD#";
  private static final String uploadFilePrefix = uploadRequestPrefix.concat("FILE#");

  public static String createUploadRequest(String userId) {
    String uploadRequestId = UUID.randomUUID().toString();
    Item item = new Item()
        .withString(pk, uploadRequestPrefix + uploadRequestId)
        .withString(sk, userId)
        .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime())
        .withLong(ttl, TtlUtils.inOneHour());
    putItem(tableName, item, DataEntityType.UPLOAD_REQUESTS);
    return uploadRequestId;
  }

  public static void addItem(String uploadRequestId, String fileId, long itemSize) {
    if (Utils.isStringNotNullOrEmpty(fileId)) {
      Item item = new Item()
          .withString(pk, uploadFilePrefix + uploadRequestId)
          .withString(sk, fileId)
          .withLong(Field.SIZE.getName(), itemSize)
          .withLong("uploadedTime", GMTHelper.utcCurrentTime())
          .withLong(ttl, TtlUtils.inOneHour());
      putItem(tableName, item, DataEntityType.UPLOAD_REQUESTS);
    } else {
      log.warn("Could not add item for multiple request: fileId is not provided");
    }
  }

  public static Iterator<Item> getAllUploadedItemsByRequestId(String uploadRequestId) {
    return query(
            tableName,
            null,
            new QuerySpec().withHashKey(pk, uploadFilePrefix + uploadRequestId),
            DataEntityType.UPLOAD_REQUESTS)
        .iterator();
  }

  public static Item getUploadRequestById(String uploadRequestId, String userId) {
    return getItem(
        tableName,
        pk,
        uploadRequestPrefix + uploadRequestId,
        sk,
        userId,
        true,
        DataEntityType.UPLOAD_REQUESTS);
  }

  public static void deleteUploadRequest(String uploadRequestId, String userId) {
    deleteItem(
        tableName,
        pk,
        uploadRequestPrefix + uploadRequestId,
        sk,
        userId,
        DataEntityType.UPLOAD_REQUESTS);
  }

  public static Long getLastUploadedTimeForMultipleFilesRequest(String uploadRequestId) {
    Iterator<Item> itemIterator = getAllUploadedItemsByRequestId(uploadRequestId);
    List<Item> items = IteratorUtils.toList(itemIterator);
    items.sort(
        Comparator.comparing(item -> ((Item) item).getLong("uploadedTime")).reversed());
    if (!items.isEmpty()) {
      Item lastUploadedItem = items.get(0);
      return lastUploadedItem.getLong("uploadedTime");
    }
    return null;
  }
}
