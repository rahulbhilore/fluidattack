package com.graebert.storage.thumbnails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.google.gson.Gson;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NonNls;

public class SQSRequests extends Dataplane {
  public static final String thumbnailChunkPrefix = "sqs_thumbnailChunk#";

  @NonNls
  private static final String pkPrefix = "sqs_requests#";

  @NonNls
  private static final String dbTable = TableName.TEMP.name;

  private static final String dbSKPKIndex = "sk-pk-index";
  private static final int maxFailures = 3;

  public static void saveRequest(String requestId, String requestUserId, String requestExternalId) {
    Item item = new Item()
        .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
        .withLong(ttl, TtlUtils.inOneDay());
    if (Utils.isStringNotNullOrEmpty(requestUserId)) {
      item.withString(Field.USER_ID.getName(), requestUserId);
    }
    if (Utils.isStringNotNullOrEmpty(requestExternalId)) {
      item.withString(Field.EXTERNAL_ID.getName(), requestExternalId);
    }
    putItem(dbTable, item, DataEntityType.THUMBNAILS);
  }

  public static void saveThumbnailChunkRequest(
      String chunkId, JsonObject body, List<JsonObject> chunk, String groupName) {
    try {
      List<Map<String, Object>> newChunk =
          chunk.stream().map(JsonObject::getMap).collect(Collectors.toList());
      Map<String, ?> bodyMap = new Gson().fromJson(body.encode(), HashMap.class);
      log.info("[THUMBNAIL] Creating chunk: " + chunkId + " for groupName:" + groupName);
      Item item = new Item()
          .withPrimaryKey(pk, thumbnailChunkPrefix + chunkId, sk, groupName)
          .withList("fileIds", newChunk)
          .withMap(Field.INFO.getName(), bodyMap)
          .withString(Field.STATUS.getName(), ThumbnailChunkStatus.INQUEUE.name())
          .withLong(ttl, GMTHelper.utcCurrentTime() / 1000 + TimeUnit.MINUTES.toSeconds(10));
      putItem(dbTable, item, DataEntityType.THUMBNAILS);
    } catch (Exception e) {
      log.error("Error in creating new thumbnail chunk request | " + e.getLocalizedMessage());
    }
  }

  public static Item getThumbnailChunkRequest(String chunkId, String groupName) {
    return getItem(
        dbTable,
        pk,
        thumbnailChunkPrefix + chunkId,
        sk,
        groupName,
        true,
        DataEntityType.THUMBNAILS);
  }

  public static void updateThumbnailChunkRequest(
      String chunkId, String groupName, ThumbnailChunkStatus status) {
    ValueMap valueMap = new ValueMap().withString(":status", status.name());
    NameMap nameMap = new NameMap().with("#status", Field.STATUS.getName());
    String updateExpression = "set #status = :status";

    if (status.equals(ThumbnailChunkStatus.PROCESSING)) {
      valueMap.withLong(":ttl", TtlUtils.inOneDay());
      nameMap.with("#ttl", Field.TTL.getName());
      updateExpression += ", #ttl = :ttl";
    }

    updateItem(
        dbTable,
        new UpdateItemSpec()
            .withPrimaryKey(pk, thumbnailChunkPrefix + chunkId, sk, groupName)
            .withUpdateExpression(updateExpression)
            .withNameMap(nameMap)
            .withValueMap(valueMap),
        DataEntityType.THUMBNAILS);
  }

  public static Iterator<Item> getThumbnailChunkRequestForGroupName(
      String groupName, String storageType) {
    String filterExpression = "#status = :inqueue";
    ValueMap valueMap = new ValueMap()
        .withString(":chunkPrefix", thumbnailChunkPrefix)
        .withString(":groupName", groupName)
        .withString(":inqueue", ThumbnailChunkStatus.INQUEUE.name());

    // for webdav we check if any thumbnail request is in INQUEUE OR PROCESSING state
    if (storageType.equals(StorageType.WEBDAV.name())) {
      filterExpression += " or #status = :processing";
      valueMap.withString(":processing", ThumbnailChunkStatus.PROCESSING.name());
    }

    return query(
            dbTable,
            dbSKPKIndex,
            new QuerySpec()
                .withKeyConditionExpression(
                    "sk = :groupName and begins_with(pk, " + ":chunkPrefix)")
                .withFilterExpression(filterExpression)
                .withNameMap(new NameMap().with("#status", Field.STATUS.getName()))
                .withValueMap(valueMap),
            DataEntityType.THUMBNAILS)
        .iterator();
  }

  public static void deleteThumbnailChunkRequest(String chunkId, String groupName) {
    deleteItem(
        dbTable, pk, thumbnailChunkPrefix + chunkId, sk, groupName, DataEntityType.THUMBNAILS);
  }

  public static void saveThumbnailRequest(
      String requestId, ThumbnailsManager.ThumbnailStatus status) {
    Item item = new Item()
        .withPrimaryKey(pk, pkPrefix + requestId, sk, requestId)
        .withString(Field.STATUS.getName(), status.name())
        .withLong(ttl, TtlUtils.inOneDay());
    putItem(dbTable, item, DataEntityType.THUMBNAILS);
  }

  public static void updateThumbnailRequest(
      String requestId, ThumbnailsManager.ThumbnailStatus status, String failedDetails) {
    UpdateItemSpec updateItemSpec =
        new UpdateItemSpec().withPrimaryKey(pk, pkPrefix + requestId, sk, requestId);

    String updateExpression = "set #status = :status, #ttl = :ttl";
    NameMap nameMap =
        new NameMap().with("#status", Field.STATUS.getName()).with("#ttl", Field.TTL.getName());
    ValueMap valueMap = new ValueMap().withString(":status", status.name());

    Item request = findRequest(requestId);
    if (status.equals(ThumbnailsManager.ThumbnailStatus.UNAVAILABLE)) {
      valueMap.withLong(":ttl", TtlUtils.inNHours(3));
      int noOfFailures = 0;
      if (request != null && request.hasAttribute("noOfFailures")) {
        noOfFailures = request.getInt("noOfFailures");
        if (noOfFailures >= maxFailures) {
          valueMap.withString(":status", ThumbnailsManager.ThumbnailStatus.COULDNOTGENERATE.name());
        }
      }
      updateExpression += ", #noOfFailures = :noOfFailures";
      nameMap.with("#noOfFailures", "noOfFailures");
      valueMap.withInt(":noOfFailures", noOfFailures + 1);
      if (Utils.isStringNotNullOrEmpty(failedDetails)) {
        updateExpression += ", #failedDetails = :failedDetails";
        nameMap.with("#failedDetails", "failedDetails");
        valueMap.withString(":failedDetails", failedDetails);
      }
    } else {
      if (!status.equals(ThumbnailsManager.ThumbnailStatus.LOADING)) {
        valueMap.withLong(":ttl", TtlUtils.inOneMonth());
        if (request != null) {
          if (request.hasAttribute("noOfFailures")) {
            updateExpression += " remove #noOfFailures";
            nameMap.with("#noOfFailures", "noOfFailures");
          }
          if (request.hasAttribute("failedDetails")) {
            nameMap.with("#failedDetails", "failedDetails");
            if (updateExpression.contains("remove")) {
              updateExpression += ", #failedDetails";
            } else {
              updateExpression += " remove #failedDetails";
            }
          }
        }
      } else {
        valueMap.withLong(":ttl", TtlUtils.inOneDay());
      }
    }
    updateItemSpec
        .withUpdateExpression(updateExpression)
        .withNameMap(nameMap)
        .withValueMap(valueMap);

    updateItem(dbTable, updateItemSpec, DataEntityType.THUMBNAILS);
  }

  public static void saveCompareJobRequest(
      String jobId, String requestUserId, String firstFileId, String secondFileId) {
    Item item = new Item()
        .withPrimaryKey(pk, pkPrefix + jobId, sk, jobId)
        .withLong(ttl, TtlUtils.inOneDay());
    if (Utils.isStringNotNullOrEmpty(requestUserId)) {
      item.withString(Field.USER_ID.getName(), requestUserId);
    }
    if (Utils.isStringNotNullOrEmpty(firstFileId)) {
      item.withString("firstFile", firstFileId);
    }
    if (Utils.isStringNotNullOrEmpty(secondFileId)) {
      item.withString("secondFile", secondFileId);
    }
    putItem(dbTable, item, DataEntityType.THUMBNAILS);
  }

  public static Item findRequest(String requestId) {
    return getItemFromCache(
        dbTable, pk, pkPrefix + requestId, sk, requestId, DataEntityType.THUMBNAILS);
  }

  public static void removeRequest(String requestId) {
    deleteItem(dbTable, pk, pkPrefix + requestId, sk, requestId, DataEntityType.THUMBNAILS);
  }

  public static boolean updateRequestStatus(String requestId, String status, String error) {
    Item request = SQSRequests.findRequest(requestId);
    if (request != null) {
      request.withString(Field.STATUS.getName(), status);
      if (Utils.isStringNotNullOrEmpty(error)) {
        request.withString(Field.ERROR.getName(), error);
      }
      request.withLong(Field.TTL.getName(), TtlUtils.inOneDay());
      putItem(dbTable, request, DataEntityType.THUMBNAILS);
      return true;
    }
    return false;
  }

  public enum ThumbnailChunkStatus {
    INQUEUE,
    PROCESSING,
    PROCESSED
  }
}
