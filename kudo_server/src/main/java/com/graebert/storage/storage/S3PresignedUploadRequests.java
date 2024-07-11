package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.Map;
import java.util.UUID;

public class S3PresignedUploadRequests extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final String uploadRequestPrefix = "S3_PRESIGNED_UPLOAD#";

  public static String createRequest(
      String userId, String fileName, String storageType, String presignedUploadType) {
    String presignedUploadId = UUID.randomUUID().toString();
    Item item = new Item()
        .withString(pk, uploadRequestPrefix + presignedUploadId)
        .withString(sk, userId)
        .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime())
        .withString(Field.FILE_NAME_C.getName(), fileName)
        .withString(Field.STORAGE_TYPE.getName(), storageType)
        .withLong(ttl, TtlUtils.inOneDay()); // Keeping this for atleast 1 Day to debug any issue
    if (Utils.isStringNotNullOrEmpty(presignedUploadType)) {
      item.withString(Field.PRESIGNED_UPLOAD_TYPE.getName(), presignedUploadType);
    }
    putItem(tableName, item, DataEntityType.UPLOAD_REQUESTS);
    return presignedUploadId;
  }

  public static Item getRequestById(String presignedUploadId, String userId) {
    return getItem(
        tableName,
        pk,
        uploadRequestPrefix + presignedUploadId,
        sk,
        userId,
        true,
        DataEntityType.UPLOAD_REQUESTS);
  }

  public static void setFileDownloadedAt(String presignedUploadId, String userId) {
    updateRequest(
        presignedUploadId,
        userId,
        "set #dAt = :dAt",
        new NameMap().with("#dAt", "downloadedAt"),
        new ValueMap().withLong(":dAt", GMTHelper.utcCurrentTime()));
  }

  public static void setRequestAsCompleted(
      String presignedUploadId, String userId, JsonObject uploadResult) {
    updateRequest(
        presignedUploadId,
        userId,
        "set #uploadResult = :uploadResult",
        new NameMap().with("#uploadResult", "uploadResult"),
        new ValueMap().withMap(":uploadResult", uploadResult.getMap()));
  }

  public static void setRequestAsCancelled(String presignedUploadId, String userId) {
    updateRequest(
        presignedUploadId,
        userId,
        "set #cancelled = :cancelled",
        new NameMap().with("#cancelled", "cancelled"),
        new ValueMap().withBoolean(":cancelled", true));
  }

  public static void setErrorDetails(
      String presignedUploadId, String userId, Map<String, Object> errorObject) {
    updateRequest(
        presignedUploadId,
        userId,
        "set #error = :error",
        new NameMap().with("#error", "errorDetails"),
        new ValueMap().withMap(":error", errorObject));
  }

  public static void setFileUploadedToStorageAt(String presignedUploadId, String userId) {
    updateRequest(
        presignedUploadId,
        userId,
        "set #uSAt = :uSAt",
        new NameMap().with("#uSAt", "uploadToStorageAt"),
        new ValueMap().withLong(":uSAt", GMTHelper.utcCurrentTime()));
    log.info(
        "[S3 Presigned] File uploaded to the storage with presignedUploadId: " + presignedUploadId);
  }

  public static void removeS3File(
      String bucket, String region, String storageType, String presignedUploadId, String userId) {
    S3Regional s3Regional = new S3Regional(bucket, region);
    log.info(
        "[S3 Presigned] Removing the file from S3 with presignedUploadId: " + presignedUploadId);
    String key = S3Regional.createPresignedKey(presignedUploadId, userId, storageType);
    s3Regional.deleteFromBucket(bucket, key);
  }

  public static void updateRequest(
      String presignedUploadId,
      String userId,
      String updateExpression,
      NameMap nameMap,
      ValueMap valueMap) {
    try {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, uploadRequestPrefix + presignedUploadId, sk, userId)
              .withUpdateExpression(updateExpression)
              .withNameMap(nameMap)
              .withValueMap(valueMap),
          DataEntityType.UPLOAD_REQUESTS);
    } catch (ConditionalCheckFailedException e) {
      if (getRequestById(presignedUploadId, userId) == null) {
        log.warn("[S3 Presigned] Trying to update a presigned uploadRequest which doesn't exist - "
            + presignedUploadId);
      } else {
        log.error("[S3 Presigned] Invalid data received for update a presigned uploadRequest with "
            + "requestId - " + presignedUploadId + " : " + e);
      }
    }
  }
}
