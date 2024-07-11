package com.graebert.storage.storage.zipRequest;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ZipRequests extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final String pkPrefix = "zip_requests#";

  private static final String pkDownloadPrefix = "zip_download_requests#";
  private static final String pk = Field.PK.getName();
  private static final String sk = Field.SK.getName();
  private static final Supplier<Long> ZIP_REQUEST_TTL = () -> TtlUtils.inNHours(2);

  public static Item getZipRequest(String userId, String folderId, String requestId) {
    return getItemFromCache(
        tableName,
        pk,
        pkPrefix + userId,
        sk,
        folderId + hashSeparator + requestId,
        DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static Item getDownloadZipRequest(String requestId) {
    return getItemFromCache(
        emptyString,
        pk,
        pkDownloadPrefix + requestId,
        sk,
        Field.INFO.getName(),
        DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static String createZipRequest(String userId, String folderId, StorageType storageType) {
    String requestId = Utils.generateUUID();
    Item request = new Item()
        .withPrimaryKey(pk, pkPrefix + userId, sk, folderId + hashSeparator + requestId)
        .withString(Field.REQUEST_ID.getName(), requestId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withString(Field.STORAGE_TYPE.getName(), storageType.name())
        .withLong(Field.TTL.getName(), ZIP_REQUEST_TTL.get());

    putItem(
        tableName,
        pkPrefix + userId,
        folderId + hashSeparator + requestId,
        request,
        DataEntityType.DOWNLOAD_REQUESTS);
    return requestId;
  }

  public static String createResourceZipRequest(
      String userId, String objectId, ResourceType resourceType) {
    String requestId = Utils.generateUUID();
    Item request = new Item()
        .withPrimaryKey(pk, pkPrefix + userId, sk, objectId + hashSeparator + requestId)
        .withString(Field.REQUEST_ID.getName(), requestId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withString(Field.RESOURCE_TYPE.getName(), resourceType.name())
        .withLong(Field.TTL.getName(), ZIP_REQUEST_TTL.get());

    putItem(
        tableName,
        pkPrefix + userId,
        objectId + hashSeparator + requestId,
        request,
        DataEntityType.DOWNLOAD_REQUESTS);
    return requestId;
  }

  private static void updateS3Properties(Item request, String s3bucketName, String s3Region) {
    if (Utils.isStringNotNullOrEmpty(s3bucketName) && Utils.isStringNotNullOrEmpty(s3Region)) {
      request
          .withString(Field.BUCKET.getName(), s3bucketName)
          .withString(Field.REGION.getName(), s3Region);
    }
    putItem(tableName, request, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void updateExcludedFilesList(
      Item request, List<String> excludedFiles, ExcludeReason reason) {
    request.withList(reason.getKey(), excludedFiles);
    putItem(tableName, request, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void updateMainZipStatus(Item request, JobStatus status, String details) {
    String updateExpression = "set #mzs = :mzs";
    NameMap nameMap = new NameMap().with("#mzs", "mainZipStatus");
    ValueMap valueMap = new ValueMap().withString(":mzs", status.name());
    UpdateItemSpec updateItemSpec =
        new UpdateItemSpec().withPrimaryKey(pk, request.getString(pk), sk, request.getString(sk));
    if (Utils.isStringNotNullOrEmpty(details)) {
      updateExpression += ", #mDetails = :mDetails";
      nameMap.with("#mDetails", "mainZipDetails");
      valueMap.withString(":mDetails", details);
    }
    updateItemSpec
        .withUpdateExpression(updateExpression)
        .withNameMap(nameMap)
        .withValueMap(valueMap);
    updateItem(tableName, updateItemSpec, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void updateUploadedPartAndUploadedSize(Item request) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, request.getString(pk), sk, request.getString(sk))
            .withUpdateExpression("set #us = :us, #up = :up")
            .withNameMap(new NameMap()
                .with("#us", Field.UPLOADED_SIZE.getName())
                .with("#up", Field.UPLOADED_PART.getName()))
            .withValueMap(new ValueMap()
                .withLong(":us", request.getLong(Field.UPLOADED_SIZE.getName()))
                .withInt(":up", request.getInt(Field.UPLOADED_PART.getName()))),
        DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void putDownloadedPart(Item request, String requestId, int downloadedPart) {
    // AS : Not able to update anything in the same table for some reason while a thread is
    // running, so used main table
    if (Objects.isNull(request)) {
      request = new Item()
          .withString(pk, pkDownloadPrefix + requestId)
          .withString(sk, Field.INFO.getName())
          .withInt(Field.DOWNLOADED_PART.getName(), downloadedPart)
          .withLong(Field.TTL.getName(), ZIP_REQUEST_TTL.get());
      putItem(request, DataEntityType.DOWNLOAD_REQUESTS);
    } else {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, request.getString(pk), sk, request.getString(sk))
              .withUpdateExpression("set #dp = :dp")
              .withNameMap(new NameMap().with("#dp", Field.DOWNLOADED_PART.getName()))
              .withValueMap(new ValueMap().withInt(":dp", downloadedPart)),
          DataEntityType.DOWNLOAD_REQUESTS);
    }
  }

  public static void setRequestException(String locale, Item request, Throwable error) {
    if (error instanceof OutOfMemoryError) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, Utils.getLocalizedString(locale, "MaxHeapSizeLimitReached"));
    } else {
      ZipRequests.setRequestStatus(request, JobStatus.ERROR, error.getLocalizedMessage());
    }
  }

  public static void setRequestException(
      Message<JsonObject> message, Item request, Throwable error) {
    ZipRequests.setRequestException(Utils.getLocaleFromMessage(message), request, error);
  }

  public static void setRequestStatus(Item request, JobStatus status, String details) {
    // add logs to make sure we catch the error
    if (status == JobStatus.ERROR) {
      log.error("[ZIP] error on request: " + details, new Exception("ZIP download error"));
    }
    request.withString(Field.STATUS.getName(), status.name());
    if (Utils.isStringNotNullOrEmpty(details)) {
      request.withString("details", details);
    }
    request.withLong(Field.TTL.getName(), ZIP_REQUEST_TTL.get());
    putItem(tableName, request, DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void deleteZipRequest(String userId, String folderId, String requestId) {
    deleteItem(
        tableName,
        pk,
        pkPrefix + userId,
        sk,
        folderId + hashSeparator + requestId,
        DataEntityType.DOWNLOAD_REQUESTS);
  }

  public static void deleteDownloadZipRequest(String requestId) {
    deleteItem(
        tableName,
        pk,
        pkDownloadPrefix + requestId,
        sk,
        Field.INFO.getName(),
        DataEntityType.DOWNLOAD_REQUESTS);
  }

  private static String validateS3RequestObject(Item request) {
    if (request == null) {
      throw new IllegalArgumentException("request shouldn't be null!");
    }
    String requestId = getRequestIdFromRequest(request);
    if (!Utils.isStringNotNullOrEmpty(requestId)) {
      throw new IllegalArgumentException("requestId isn't found");
    }
    if (!request.hasAttribute(Field.BUCKET.getName())
        || !request.hasAttribute(Field.REGION.getName())) {
      throw new IllegalArgumentException("S3 is not specified");
    }
    return requestId;
  }

  public static void removeZipFromS3(Item request) {
    String requestId = validateS3RequestObject(request);
    S3Regional s3Regional = new S3Regional(
        request.getString(Field.BUCKET.getName()), request.getString(Field.REGION.getName()));
    s3Regional.delete(requestId);
  }

  public static String getRequestIdFromRequest(Item request) {
    if (request.hasAttribute(Field.REQUEST_ID.getName())) {
      return request.getString(Field.REQUEST_ID.getName());
    }
    return null;
  }

  public static void putMainZipToS3(
      Item request, String s3bucketName, String s3Region, byte[] data) {
    if (request == null) {
      throw new IllegalArgumentException("request shouldn't be null!");
    }
    try {
      S3Regional s3Regional = new S3Regional(s3bucketName, s3Region);
      s3Regional.put(getRequestIdFromRequest(request) + S3Regional.pathSeparator + "main", data);
      updateMainZipStatus(request, JobStatus.SUCCESS, null);
    } catch (Exception ex) {
      updateMainZipStatus(request, JobStatus.ERROR, ex.getLocalizedMessage());
    }
  }

  /**
   * Puts to proper s3 location and saves it for further retrievals.
   * Automatically updates status!
   *
   * @param request      - db item
   * @param s3bucketName - bucket to use
   * @param s3Region     - region
   * @param data         - ByteArray with zip data
   * @param locale       - locale string
   */
  public static void putZipToS3(
      Item request,
      String s3bucketName,
      String s3Region,
      byte[] data,
      boolean isCompleted,
      String locale) {
    if (request == null) {
      throw new IllegalArgumentException("request shouldn't be null!");
    }
    try {
      S3Regional s3Regional = new S3Regional(s3bucketName, s3Region);
      s3Regional.put(
          getRequestIdFromRequest(request)
              + (request.hasAttribute(Field.UPLOADED_PART.getName())
                  ? S3Regional.pathSeparator + request.getInt(Field.UPLOADED_PART.getName())
                  : emptyString),
          data);
      if (request.hasAttribute(Field.UPLOADED_PART.getName())
          && request.hasAttribute(Field.UPLOADED_SIZE.getName())) {
        updateUploadedPartAndUploadedSize(request);
      }
      updateS3Properties(request, s3bucketName, s3Region);
      if (isCompleted) {
        setRequestStatus(request, JobStatus.SUCCESS, null);
      }
    } catch (Exception ex) {
      setRequestException(locale, request, ex);
    }
  }

  public static String getDownloadUrlForZipEntries(Item request) {
    String requestId = validateS3RequestObject(request);
    S3Regional s3Regional = new S3Regional(
        request.getString(Field.BUCKET.getName()), request.getString(Field.REGION.getName()));
    return s3Regional.getDownloadUrl(requestId, null, null);
  }

  public static byte[] getZipFromS3(Item request) {
    String requestId = validateS3RequestObject(request);
    S3Regional s3Regional = new S3Regional(
        request.getString(Field.BUCKET.getName()), request.getString(Field.REGION.getName()));
    return s3Regional.get(requestId);
  }
}
