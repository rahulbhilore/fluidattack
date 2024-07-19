package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class DownloadRequests extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final String pkPrefix = "download_requests#";
  private static final Supplier<Long> DOWNLOAD_REQUEST_TTL = TtlUtils::inOneDay;
  private static final List<JsonObject> downloadRequests = new ArrayList<>();

  public static Item getRequest(String requestId) {
    return getItemFromCache(
        tableName, pk, pkPrefix + requestId, sk, requestId, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static String createRequest(
      String userId,
      String fileId,
      String extension,
      DownloadType downloadType,
      StorageType storageType,
      String lastModifier,
      String externalId) {
    String requestId = Utils.generateUUID();
    Item request = new Item()
        .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
        .withString(Field.REQUEST_ID.getName(), requestId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withString(Field.USER_ID.getName(), userId)
        .withString(Field.FILE_ID.getName(), fileId)
        .withString(Field.STORAGE_TYPE.getName(), storageType.name())
        .withLong(Field.TTL.getName(), DOWNLOAD_REQUEST_TTL.get());

    if (Utils.isStringNotNullOrEmpty(lastModifier)) {
      request.withString("lastModifier", lastModifier);
    }

    if (downloadType != null) {
      request.withString(Field.TYPE.getName(), downloadType.name());
    }

    if (Utils.isStringNotNullOrEmpty(extension)) {
      request.withString("extension", extension);
    }

    if (Utils.isStringNotNullOrEmpty(externalId)) {
      request.withString(Field.EXTERNAL_ID.getName(), externalId);
    }

    try {
      putItem(
          tableName, pkPrefix + requestId, requestId, request, DataEntityType.DOWNLOAD_REQUESTS);
    } catch (Exception e) {
      log.error("Error occurred in creating download request for fileId - " + fileId + " | "
          + e.getLocalizedMessage());
      return null;
    }
    return requestId;
  }

  public static String createResourceRequest(
      String userId, String objectId, ResourceType resourceType) {
    String requestId = Utils.generateUUID();
    Item request = new Item()
        .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
        .withString(Field.REQUEST_ID.getName(), requestId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withString(Field.USER_ID.getName(), userId)
        .withString(Field.OBJECT_ID.getName(), objectId)
        .withString(Field.RESOURCE_TYPE.getName(), resourceType.name())
        .withLong(Field.TTL.getName(), DOWNLOAD_REQUEST_TTL.get());

    try {
      putItem(
          tableName, pkPrefix + requestId, requestId, request, DataEntityType.DOWNLOAD_REQUESTS);
    } catch (Exception e) {
      log.error("Error occurred in creating download request in resource " + resourceType.name()
          + " for objectId - " + objectId + " | " + e.getLocalizedMessage());
      return null;
    }
    return requestId;
  }

  public static String createVersionConvertRequest(
      String userId,
      String fileId,
      StorageType storageType,
      String externalId,
      String format,
      String tempFilePath,
      String finalFilePath,
      String ext) {
    String requestId = Utils.generateUUID();
    Item request = new Item()
        .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
        .withString(Field.REQUEST_ID.getName(), requestId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withString(Field.TYPE.getName(), DownloadRequests.DownloadType.S3.name())
        .withString(Field.USER_ID.getName(), userId)
        .withString(Field.FILE_ID.getName(), fileId)
        .withString(Field.STORAGE_TYPE.getName(), storageType.name())
        .withString(Field.EXTERNAL_ID.getName(), externalId)
        .withString("format", format)
        .withString("tempFilePath", tempFilePath)
        .withString("finalFilePath", finalFilePath)
        .withString(Field.EXT.getName(), ext)
        .withLong(Field.TTL.getName(), DOWNLOAD_REQUEST_TTL.get());

    try {
      putItem(
          tableName, pkPrefix + requestId, requestId, request, DataEntityType.DOWNLOAD_REQUESTS);
    } catch (Exception e) {
      log.error("Error occurred in creating convert request for fileId - " + fileId + " | "
          + e.getLocalizedMessage());
      return null;
    }
    return requestId;
  }

  public static void setRequestData(
      String requestId, byte[] data, JobStatus status, String details, DownloadType fileType) {
    if (!Utils.isStringNotNullOrEmpty(requestId)) {
      log.error("Undefined requestId for setRequestData.");
      return;
    }
    String updateExpression = "";
    NameMap nameMap = new NameMap();
    ValueMap valueMap = new ValueMap();
    if (status != null) {
      updateExpression = "set #st = :status";
      nameMap.with("#st", Field.STATUS.getName());
      valueMap.withString(":status", status.name());
    }
    if (Utils.isStringNotNullOrEmpty(details)) {
      if (updateExpression.isEmpty()) {
        updateExpression = "set #dt = :details";
      } else {
        updateExpression += " , #dt = :details";
      }
      nameMap.with("#dt", "details");
      valueMap.withString(":details", details);
    }
    if (fileType != null) {
      if (updateExpression.isEmpty()) {
        updateExpression = "set #type = :type";
      } else {
        updateExpression += " , #type = :type";
      }
      nameMap.with("#type", Field.TYPE.getName());
      valueMap.withString(":type", fileType.name());
    }
    if (data != null) {
      downloadRequests.add(new JsonObject()
          .put(Field.REQUEST_ID.getName(), requestId)
          .put(Field.DATA.getName(), data));
    }
    if (updateExpression.isEmpty()) {
      updateExpression = "set #t = :ttl";
    } else {
      updateExpression += " , #t = :ttl";
    }
    nameMap.with("#t", Field.TTL.getName());
    valueMap.withLong(":ttl", DOWNLOAD_REQUEST_TTL.get());

    updateDownloadRequest(requestId, updateExpression, nameMap, valueMap);
  }

  public static void updateDownloadRequest(
      String requestId, String updateExpression, NameMap nameMap, ValueMap valueMap) {
    try {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
              .withUpdateExpression(updateExpression)
              .withNameMap(nameMap)
              .withValueMap(valueMap),
          DataEntityType.DOWNLOAD_REQUESTS);
    } catch (ConditionalCheckFailedException e) {
      if (getRequest(requestId) == null) {
        log.warn("Trying to update a downloadRequest which doesn't exist - " + requestId);
      } else {
        log.error("Invalid data received for update downloadRequest with requestId - " + requestId
            + " and with values - " + valueMap.toString() + " : " + e);
      }
    }
  }

  public static void deleteRequest(String requestId) {
    deleteItem(tableName, pk, requestId, sk, requestId, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static JsonObject getDownloadRequestData(String requestId) {
    Optional<JsonObject> optional = downloadRequests.stream()
        .filter(request -> request.getString(Field.REQUEST_ID.getName()).equals(requestId))
        .findAny();
    return optional.orElse(null);
  }

  public static void deleteDownloadRequestData(String requestId) {
    downloadRequests.removeIf(
        request -> request.getString(Field.REQUEST_ID.getName()).equals(requestId));
  }

  /*
     DownloadTypes
     * S3 - Requested file downloads for which data is saved in S3.
     * LARGE_DATA - Big files for which data is stored on server itself and return for /data.
     * LARGE_DIFFS - Big files for which data is stored on server itself and return for /diffs.
  */
  public enum DownloadType {
    S3,
    LARGE_DATA,
    LARGE_DIFFS
  }
}
