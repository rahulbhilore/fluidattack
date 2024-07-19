package com.graebert.storage.storage;

import com.amazon.dax.client.dynamodbv2.ClientConfig;
import com.amazon.dax.client.dynamodbv2.ClusterDaxClient;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.retry.RetryPolicy;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.BatchGetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.BatchWriteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DeleteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.GetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.KeyAttribute;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableKeysAndAttributes;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.BatchGetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.BatchWriteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.GetItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchGetItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.KeysAndAttributes;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.dynamodbv2.model.ReturnValuesOnConditionCheckFailure;
import com.amazonaws.services.dynamodbv2.model.ScanRequest;
import com.amazonaws.services.dynamodbv2.model.TransactWriteItem;
import com.amazonaws.services.dynamodbv2.model.TransactWriteItemsRequest;
import com.amazonaws.services.dynamodbv2.model.Update;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.google.common.hash.Hashing;
import com.graebert.storage.config.Properties;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.stats.logs.data.DataOperationType;
import com.graebert.storage.stats.logs.data.DataplaneLog;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.ops.OperationStatus;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class Dataplane {
  public static final @NonNls Logger log = LogManager.getRootLogger();

  private static final OperationGroup operationGroup = OperationGroup.DATAPLANE;

  @NonNls
  public static final String nonce = Field.NONCE.getName();

  @NonNls
  public static final String pk = Field.PK.getName();

  @NonNls
  public static final String sk = Field.SK.getName();

  @NonNls
  public static final String ttl = Field.TTL.getName();

  public static final String hashSeparator = "#";
  public static final String emptyString = "";
  public static ServerConfig config = new ServerConfig();
  public static AmazonDynamoDB client;
  public static int batchChunkSize = 25;
  private static MemcachedClient memcachedClient;
  private static DynamoDB dbRead;
  private static DynamoDB dbWrite;
  private static DynamoDB dbDaxClient;
  private static String prefix;
  private static String dynamoRegion;
  private static boolean connecting;
  private static final String logPrefix = "[DATAPLANE] :";
  private static final String ddbLogPrefix = "[DYNAMODB] :";
  private static final String cacheLogPrefix = "[MEMCACHED] :";

  public static void init(Properties properties) {
    config.init(properties);
    dynamoRegion = config.getProperties().getDynamoRegion();
    prefix = config.getProperties().getPrefix();
    if (dbRead == null) {
      final RetryPolicy retryPolicy = new RetryPolicy(
          (originalRequest, exception, retriesAttempted) ->
              retriesAttempted < 10 && exception.isRetryable(),
          new RetryPolicy.BackoffStrategy() {
            private static final int SCALE_FACTOR = 100;
            private static final int MAX_BACKOFF_IN_MILLISECONDS = 25 * 1000;

            @Override
            public long delayBeforeNextRetry(
                AmazonWebServiceRequest amazonWebServiceRequest, AmazonClientException e, int i) {
              @NonNls String request = amazonWebServiceRequest.toString();
              if (amazonWebServiceRequest instanceof GetItemRequest) {
                request = "GetItemRequest " + amazonWebServiceRequest;
              } else if (amazonWebServiceRequest instanceof BatchGetItemRequest) {
                request = "BatchGetItemRequest " + amazonWebServiceRequest;
              } else if (amazonWebServiceRequest instanceof ScanRequest) {
                request = "ScanRequest " + amazonWebServiceRequest;
              } else if (amazonWebServiceRequest instanceof QueryRequest) {
                request = "QueryRequest " + amazonWebServiceRequest;
              }
              log.warn(logPrefix
                  + " Exponential backoff on read ### AmazonClientException: "
                  + e.toString() + " \nAmazonWebServiceRequest: "
                  + request);
              if (i < 0) {
                return 0;
              }
              long delay = (1L << i) * SCALE_FACTOR;
              delay = Math.min(delay, MAX_BACKOFF_IN_MILLISECONDS);
              log.info(logPrefix + " Retrying read request with attempt - " + (i + 1)
                  + " after delay " + delay + " for request - " + request);
              return delay;
            }
          },
          10,
          false);

      AmazonDynamoDBClientBuilder builder = AmazonDynamoDBClientBuilder.standard();
      builder.withClientConfiguration(new ClientConfiguration().withRetryPolicy(retryPolicy));
      builder.setRegion(dynamoRegion);
      // to trace the DDB transactions using xray
      XRayManager.applyTracingHandler(builder);

      client = builder.build();
      dbRead = new DynamoDB(client);
    }
    if (dbWrite == null) {
      final RetryPolicy retryPolicyWrite = new RetryPolicy(
          (originalRequest, exception, retriesAttempted) -> {
            if (!exception.isRetryable()) {
              return false;
            }
            if (exception instanceof AmazonDynamoDBException) {
              String message = exception.getMessage();
              if (Objects.equals(message, "The provided key element does not match the schema")) {
                return false;
              }
              if (Objects.equals(
                  message,
                  "The provided expression refers to an attribute that does not exist in the item")) {
                return false;
              }
            }
            return retriesAttempted < 10;
          },
          new RetryPolicy.BackoffStrategy() {
            private static final int SCALE_FACTOR = 100;
            private static final int MAX_BACKOFF_IN_MILLISECONDS = 25 * 1000;

            @Override
            public long delayBeforeNextRetry(
                AmazonWebServiceRequest amazonWebServiceRequest, AmazonClientException e, int i) {
              @NonNls String request = amazonWebServiceRequest.toString();
              if (amazonWebServiceRequest instanceof PutItemRequest) {
                request = "PutItemRequest " + amazonWebServiceRequest;
              } else if (amazonWebServiceRequest instanceof BatchWriteItemRequest) {
                request = "BatchWriteItemRequest " + amazonWebServiceRequest;
              } else if (amazonWebServiceRequest instanceof UpdateItemRequest) {
                request = "UpdateItemRequest " + amazonWebServiceRequest;
              }
              log.warn(logPrefix
                  + " Exponential backoff on write ### AmazonClientException: ProvisionedThroughputExceededException"
                  + " \nAmazonWebServiceRequest: "
                  + request);
              if (i < 0) {
                return 0;
              }
              long delay = (1L << i) * SCALE_FACTOR;
              delay = Math.min(delay, MAX_BACKOFF_IN_MILLISECONDS);
              log.info(logPrefix + " Retrying write request with attempt - " + (i + 1)
                  + " after delay " + delay + " for request - " + request);
              return delay;
            }
          },
          10,
          false);

      AmazonDynamoDBClientBuilder builder = AmazonDynamoDBClientBuilder.standard();
      builder.withClientConfiguration(new ClientConfiguration().withRetryPolicy(retryPolicyWrite));
      builder.setRegion(dynamoRegion);

      // to trace the DDB transactions using xray
      XRayManager.applyTracingHandler(builder);

      AmazonDynamoDB client = builder.build();
      dbWrite = new DynamoDB(client);
    }
    if (dbDaxClient == null) {
      if (config.getProperties().getDaxEnabled()
          && Regions.getCurrentRegion() != null
          && Regions.getCurrentRegion()
              .getName()
              .equals(config.getProperties().getDynamoRegion())) {
        ClientConfig daxConfig =
            new ClientConfig().withEndpoints(config.getProperties().getDaxEndpoint());
        AmazonDynamoDB client = new ClusterDaxClient(daxConfig);
        dbDaxClient = new DynamoDB(client);
      }
    }
    if (config.getProperties().getElasticacheEnabled() && !connecting) {
      connecting = true;
      String host = config.getProperties().getElasticacheEndpoint();
      int port = config.getProperties().getElasticachePort();
      try {
        memcachedClient = new MemcachedClient(new InetSocketAddress(host, port));
      } catch (IOException e) {
        log.error(cacheLogPrefix + " Elasticache start error: " + e.getMessage(), e);
      }
    }
  }

  public static String getTableName(@NonNls String name) {
    if (name.isEmpty()) {
      return prefix;
    }
    return prefix + "_" + name;
  }

  public static BatchGetItemOutcome batchGetItemUnprocessed(
      Map<String, KeysAndAttributes> unprocessedKeys) {
    return dbRead.batchGetItemUnprocessed(unprocessedKeys);
  }

  /**
   * Batch get items
   *
   * @param entityType - type of the service/entity
   * @param tableName - table from which batch to be read
   * @param tableKeysAndAttributes - keys and attributes for which the items to be read
   */
  public static BatchGetItemOutcome batchGetItem(
      DataEntityType entityType,
      String tableName,
      TableKeysAndAttributes... tableKeysAndAttributes) {
    BatchGetItemOutcome outcome = dbRead.batchGetItem(new BatchGetItemSpec()
        .withTableKeyAndAttributes(tableKeysAndAttributes)
        .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.BATCH_READ,
        tableName,
        null,
        outcome,
        ReturnConsumedCapacity.TOTAL.toString(),
        false,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return outcome;
  }

  /**
   * Batch write items
   *
   * @param tableName - table in which the item to be written
   * @param twi - items to be written
   * @param entityType - type of the service/entity
   */
  public static BatchWriteItemOutcome batchWriteItem(
      String tableName, TableWriteItems twi, DataEntityType entityType) {
    if ((twi.getPrimaryKeysToDelete() == null || twi.getPrimaryKeysToDelete().isEmpty())
        && (twi.getItemsToPut() == null || twi.getItemsToPut().isEmpty())) {
      try {
        throw new IllegalArgumentException("No items to put");
      } catch (IllegalArgumentException exception) {
        log.error(ddbLogPrefix + " Empty batchWriteItem call", exception);
      }
      return null;
    }
    BatchWriteItemOutcome outcome = dbWrite.batchWriteItem(new BatchWriteItemSpec()
        .withTableWriteItems(twi)
        .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
    Map<String, List<WriteRequest>> unprocessed;
    do {
      unprocessed = outcome.getUnprocessedItems();
      if (!unprocessed.isEmpty()) {
        outcome = dbWrite.batchWriteItemUnprocessed(unprocessed);
      }
    } while (!unprocessed.isEmpty());
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.BATCH_WRITE,
        tableName,
        null,
        outcome,
        ReturnConsumedCapacity.TOTAL.toString(),
        false,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return outcome;
  }

  private static BatchWriteItemOutcome batchWriteItemUnprocessed(
      Map<String, List<WriteRequest>> unprocessedItems) {
    return dbWrite.batchWriteItemUnprocessed(unprocessedItems);
  }

  public static Table getTableForRead(@NonNls String name) {
    if (name.isEmpty()) {
      return dbRead.getTable(prefix);
    }
    return dbRead.getTable(prefix + "_" + name);
  }

  public static Table getTableForRead(@NonNls String name, boolean cache) {
    if (cache && dbDaxClient != null) {
      if (name.isEmpty()) {
        return dbDaxClient.getTable(prefix);
      }

      return dbDaxClient.getTable(prefix + "_" + name);
    }
    if (name.isEmpty()) {
      return dbRead.getTable(prefix);
    }

    return dbRead.getTable(prefix + "_" + name);
  }

  public static Table getTableForWrite(@NonNls String name) {
    String tableName = getTableName(name);
    return dbWrite.getTable(tableName);
  }

  public static Table getTableForWrite() {
    return dbWrite.getTable(prefix);
  }

  public static boolean AllowAdminSessionId() {
    // we should update this to be a configuration setting
    return prefix.equals("kudo_development")
        || prefix.equals("kudo_local")
        || prefix.equals("kudo_dev");
  }

  private static String encodeString(Object stringToEncode) {
    try {
      return Base64.encodeBase64String(stringToEncode.toString().getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      return stringToEncode.toString();
    }
  }

  private static String getCacheKey(String table, Object hashKeyValue) {
    return getFullKey(table, hashKeyValue, null);
  }

  private static String getFullKey(String table, Object hashKeyValue, Object rangeKeyValue) {
    try {
      String hashKeyEncoded = encodeString(hashKeyValue);
      final String tableName = getTableName(table);
      String objKey = hashKeyEncoded;
      if (rangeKeyValue != null && Utils.isStringNotNullOrEmpty(rangeKeyValue.toString())) {
        String rangeKeyEncoded = encodeString(rangeKeyValue);
        objKey = hashKeyEncoded + rangeKeyEncoded;
      }
      String fullKey = tableName + objKey;
      if (fullKey.length() > MemcachedClient.MAX_KEY_LENGTH) {
        fullKey = tableName + DigestUtils.sha3_256Hex(objKey);
      }
      return fullKey;
    } catch (Exception e) {
      if (rangeKeyValue != null) {
        return getTableName(table) + hashKeyValue.toString() + rangeKeyValue;
      }
      return getTableName(table) + hashKeyValue.toString();
    }
  }

  /**
   * Delete an item from the DynamoDB and cache
   *
   * @param tableName - table from which the item to be deleted
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyName - sort key of the item
   * @param rangeKeyValue - sort key value of the item
   * @param entityType - type of the service/entity
   */
  public static void deleteItem(
      String tableName,
      String hashKeyName,
      Object hashKeyValue,
      String rangeKeyName,
      Object rangeKeyValue,
      DataEntityType entityType) {
    DeleteItemSpec deleteItemSpec =
        new DeleteItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    DeleteItemOutcome outcome;
    if (rangeKeyName == null) {
      outcome = getTableForWrite(tableName)
          .deleteItem(deleteItemSpec.withPrimaryKey(hashKeyName, hashKeyValue));
    } else {
      outcome = getTableForWrite(tableName)
          .deleteItem(deleteItemSpec.withPrimaryKey(
              hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue));
    }
    deleteFromCache(tableName, hashKeyValue, rangeKeyValue, entityType);
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.DELETE,
        tableName,
        null,
        outcome,
        deleteItemSpec.getReturnConsumedCapacity(),
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  /**
   * Update an item into the DynamoDB and cache
   *
   * @param tableName - table in which the item to be updated
   * @param updateItemSpec - item spec for update
   * @param entityType - type of the service/entity
   */
  public static UpdateItemOutcome updateItem(
      String tableName, UpdateItemSpec updateItemSpec, DataEntityType entityType) {
    Object hashKeyValue = null, rangeKeyValue = null;
    try {
      KeyAttribute[] keyAttributes = updateItemSpec.getKeyComponents().toArray(new KeyAttribute[0]);
      if (keyAttributes.length > 0) {
        hashKeyValue = keyAttributes[0].getValue();
      }
      if (keyAttributes.length > 1) {
        rangeKeyValue = keyAttributes[1].getValue();
      }
    } catch (Exception ignore) {
    }
    try {
      UpdateItemOutcome outcome = getTableForWrite(tableName)
          .updateItem(updateItemSpec
              .withReturnValues(ReturnValue.ALL_NEW)
              .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL));
      Item item = outcome.getItem();

      if (item != null && Objects.nonNull(hashKeyValue)) {
        populateCache(tableName, hashKeyValue, rangeKeyValue, item.toJSON());
      } else {
        log.error(ddbLogPrefix
            + " Update item error: UpdateItemOutcome doesn't have item or hashKeyValue is null. Table: "
            + tableName);
      }
      sendDataplaneLogToKinesis(
          entityType,
          DataOperationType.UPDATE,
          tableName,
          null,
          outcome,
          updateItemSpec.getReturnConsumedCapacity(),
          true,
          getLastReferenceMethod(Thread.currentThread().getStackTrace()));
      return outcome;
    } catch (Exception exception) {
      // if exception happened on update - we need to delete the cache just in case
      if (Objects.nonNull(hashKeyValue)) {
        deleteFromCache(tableName, hashKeyValue, rangeKeyValue, entityType);
      }
      throw exception;
    }
  }

  /**
   * Put an item into the DynamoDB and cache
   *
   * @param tableName - table in which the item to be added
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyValue - sort key value of the item
   * @param item - item to put
   * @param entityType - type of the service/entity
   */
  public static void putItem(
      String tableName,
      Object hashKeyValue,
      Object rangeKeyValue,
      Item item,
      DataEntityType entityType) {
    PutItemSpec putItemSpec =
        new PutItemSpec().withItem(item).withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    PutItemOutcome outcome = getTableForWrite(tableName).putItem(putItemSpec);
    populateCache(tableName, hashKeyValue, rangeKeyValue, item.toJSON());
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.PUT,
        tableName,
        null,
        outcome,
        putItemSpec.getReturnConsumedCapacity(),
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  /**
   * Put an item into the DynamoDB and cache
   *
   * @param tableName - table in which the item to be added
   * @param item - item to put
   * @param entityType - type of the service/entity
   */
  public static void putItem(String tableName, Item item, DataEntityType entityType) {
    PutItemSpec putItemSpec =
        new PutItemSpec().withItem(item).withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    PutItemOutcome outcome = getTableForWrite(tableName).putItem(putItemSpec);
    populateCache(
        tableName,
        item.getString(Field.PK.getName()),
        item.getString(Field.SK.getName()),
        item.toJSON());
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.PUT,
        tableName,
        null,
        outcome,
        putItemSpec.getReturnConsumedCapacity(),
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  /**
   * Put an item into the DynamoDB and cache
   *
   * @param item - item to put
   * @param entityType - type of the service/entity
   */
  public static void putItem(Item item, DataEntityType entityType) {
    PutItemSpec putItemSpec =
        new PutItemSpec().withItem(item).withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    PutItemOutcome outcome = getTableForWrite().putItem(putItemSpec);
    populateCache(
        emptyString,
        item.getString(Field.PK.getName()),
        item.getString(Field.SK.getName()),
        item.toJSON());
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.PUT,
        TableName.MAIN.name,
        null,
        outcome,
        putItemSpec.getReturnConsumedCapacity(),
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  /**
   * Get an item from DynamoDB
   *
   * @param tableName - table from which the item to be fetched
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyName - sort key of the item
   * @param rangeKeyValue - sort key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItemFromDB(
      @NonNls String tableName,
      @NonNls String hashKeyName,
      Object hashKeyValue,
      @NonNls String rangeKeyName,
      Object rangeKeyValue,
      DataEntityType entityType) {
    return getItem(
        tableName, hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue, false, entityType);
  }

  /**
   * Get an item from cache
   *
   * @param tableName - table from which the item to be fetched
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyName - sort key of the item
   * @param rangeKeyValue - sort key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItemFromCache(
      @NonNls String tableName,
      @NonNls String hashKeyName,
      Object hashKeyValue,
      @NonNls String rangeKeyName,
      Object rangeKeyValue,
      DataEntityType entityType) {
    return getItem(
        tableName, hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue, true, entityType);
  }

  /**
   * Get an item from cache
   *
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyValue - sort key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItemFromCache(
      Object hashKeyValue, Object rangeKeyValue, DataEntityType entityType) {
    return getItem(
        emptyString,
        Field.PK.getName(),
        hashKeyValue,
        Field.SK.getName(),
        rangeKeyValue,
        true,
        entityType);
  }

  /**
   * Get an item from cache
   *
   * @param hashKeyValue - primary key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItemFromCache(Object hashKeyValue, DataEntityType entityType) {
    return getItem(emptyString, Field.PK.getName(), hashKeyValue, true, entityType);
  }

  /**
   * Get an item from cache
   *
   * @param tableName - table from which the item to be fetched
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItemFromCache(
      @NonNls String tableName,
      @NonNls String hashKeyName,
      Object hashKeyValue,
      DataEntityType entityType) {
    return getItem(tableName, hashKeyName, hashKeyValue, true, entityType);
  }

  /**
   * Get an item from DynamoDB or cache
   *
   * @param tableName - table from which the item to be fetched
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItem(
      @NonNls String tableName,
      String hashKeyName,
      Object hashKeyValue,
      boolean cache,
      DataEntityType entityType) {
    return getItem(tableName, hashKeyName, hashKeyValue, null, null, cache, entityType);
  }

  /**
   * Get an item from DynamoDB or cache
   *
   * @param tableName - table from which the item to be fetched
   * @param hashKeyName - primary key of the item
   * @param hashKeyValue - primary key value of the item
   * @param rangeKeyName - sort key of the item
   * @param rangeKeyValue - sort key value of the item
   * @param entityType - type of the service/entity
   */
  public static Item getItem(
      @NonNls String tableName,
      @NonNls String hashKeyName,
      Object hashKeyValue,
      String rangeKeyName,
      Object rangeKeyValue,
      boolean cache,
      DataEntityType entityType) {
    Item obj = null;
    GetItemOutcome outcome = null;
    if (cache && memcachedClient != null) {
      try {
        String key = getFullKey(tableName, hashKeyValue, rangeKeyValue);
        String value = (String) memcachedClient.get(key);
        obj = Item.fromJSON(value);
        if (Objects.isNull(obj)) {
          try {
            outcome =
                getItemOutcome(tableName, hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue);
          } catch (ResourceNotFoundException e) {
            log.error(
                ddbLogPrefix + " DynamoDB getItem error for table: "
                    + tableName + " item: " + hashKeyName + "="
                    + hashKeyValue + " error: " + e.getMessage(),
                e);
          }
          if (Objects.nonNull(outcome)) {
            obj = outcome.getItem();
            if (Objects.nonNull(obj)) {
              // key is already encoded!
              populateCache(key, obj.toJSON(), (int) (3600 + Math.random() * 12), true, null);
            }
          }
        }
      } catch (Exception e) {
        log.error(cacheLogPrefix + " Elasticache getItem error: " + e.getMessage(), e);
        if (Objects.isNull(obj)) {
          outcome =
              getItemOutcome(tableName, hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue);
          if (Objects.nonNull(outcome)) {
            obj = outcome.getItem();
          }
        }
      }
    } else {
      try {
        GetItemSpec getItemSpec =
            new GetItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
        outcome = rangeKeyName == null
            ? getTableForRead(tableName, cache)
                .getItemOutcome(getItemSpec.withPrimaryKey(hashKeyName, hashKeyValue))
            : getTableForRead(tableName, cache)
                .getItemOutcome(getItemSpec.withPrimaryKey(
                    hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue));
        if (Objects.nonNull(outcome)) {
          obj = outcome.getItem();
        }
      } catch (ResourceNotFoundException e) {
        log.error(
            ddbLogPrefix + " DynamoDB getItem error for table: "
                + tableName + " item: " + hashKeyName + "="
                + hashKeyValue + " error: " + e.getMessage(),
            e);
      } catch (Exception e) {
        log.error(ddbLogPrefix + " Exception for name: " + hashKeyName + " value: " + hashKeyValue
            + " => " + e);
      }
    }
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.GET,
        tableName,
        null,
        outcome,
        ReturnConsumedCapacity.TOTAL.toString(),
        cache,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return obj;
  }

  private static GetItemOutcome getItemOutcome(
      String tableName,
      String hashKeyName,
      Object hashKeyValue,
      String rangeKeyName,
      Object rangeKeyValue) {
    GetItemSpec getItemSpec =
        new GetItemSpec().withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    return Objects.isNull(rangeKeyName)
        ? getTableForRead(tableName)
            .getItemOutcome(getItemSpec.withPrimaryKey(hashKeyName, hashKeyValue))
        : getTableForRead(tableName)
            .getItemOutcome(
                getItemSpec.withPrimaryKey(hashKeyName, hashKeyValue, rangeKeyName, rangeKeyValue));
  }

  /**
   * Scan items from DynamoDB
   *
   * @param tableName - table from which the items to be fetched
   * @param tableIndex - global index of the table
   * @param scanSpec - query spec of the request
   * @param entityType - type of the service/entity
   */
  public static ItemCollection<ScanOutcome> scan(
      String tableName, String tableIndex, ScanSpec scanSpec, DataEntityType entityType) {
    ItemCollection<ScanOutcome> items;
    if (Utils.isStringNotNullOrEmpty(tableIndex)) {
      scanSpec.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES);
      items = getTableForRead(tableName).getIndex(tableIndex).scan(scanSpec);
    } else {
      scanSpec.withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
      items = getTableForRead(tableName).scan(scanSpec);
    }
    //noinspection StatementWithEmptyBody
    if (items.iterator().hasNext()) {
      // not sure why we need this but once we iterate then only we get the consumed capacity
    }
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.SCAN,
        tableName,
        tableIndex,
        items.getLastLowLevelResult(),
        scanSpec.getReturnConsumedCapacity(),
        false,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return items;
  }

  /**
   * Query items from DynamoDB
   *
   * @param tableName - table from which the items to be fetched
   * @param tableIndex - global index of the table
   * @param querySpec - query spec of the request
   * @param entityType - type of the service/entity
   */
  public static ItemCollection<QueryOutcome> query(
      String tableName, String tableIndex, QuerySpec querySpec, DataEntityType entityType) {
    ItemCollection<QueryOutcome> items;
    if (Utils.isStringNotNullOrEmpty(tableIndex)) {
      querySpec.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES);
      items = getTableForRead(tableName).getIndex(tableIndex).query(querySpec);
    } else {
      querySpec.withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
      items = getTableForRead(tableName).query(querySpec);
    }
    //noinspection StatementWithEmptyBody
    if (items.iterator().hasNext()) {
      // not sure why we need this but once we iterate then only we get the consumed capacity
    }
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.QUERY,
        tableName,
        tableIndex,
        items.getLastLowLevelResult(),
        querySpec.getReturnConsumedCapacity(),
        false,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return items;
  }

  /**
   * Query items from DynamoDB using QueryRequest
   *
   * @param queryRequest - QueryRequest
   * @param entityType - type of the service/entity
   */
  public static QueryResult query(QueryRequest queryRequest, DataEntityType entityType) {
    QueryResult items;
    if (Utils.isStringNotNullOrEmpty(queryRequest.getIndexName())) {
      queryRequest.withReturnConsumedCapacity(ReturnConsumedCapacity.INDEXES);
    } else {
      queryRequest.withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL);
    }
    items = client.query(queryRequest);
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.QUERY_REQUEST,
        queryRequest.getTableName(),
        queryRequest.getIndexName(),
        items,
        queryRequest.getReturnConsumedCapacity(),
        false,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    return items;
  }

  /**
   * Delete from cache
   *
   * @param tableName - table of dynamodb which is used to get key for cache
   * @param hashKeyValue - primary key of the item which is used to get key for cache
   * @param entityType - type of the service/entity
   */
  public static void deleteFromCache(
      String tableName, Object hashKeyValue, DataEntityType entityType) {
    deleteFromCache(tableName, hashKeyValue, null, entityType);
  }

  /**
   * Delete from cache
   *
   * @param tableName - table of dynamodb which is used to get key for cache
   * @param hashKeyValue - primary key of the item which is used to get key for cache
   * @param rangeKeyValue - sort key of the item which is used to get key for cache
   * @param entityType - type of the service/entity
   */
  public static void deleteFromCache(
      String tableName, Object hashKeyValue, Object rangeKeyValue, DataEntityType entityType) {
    try {
      String key = getFullKey(tableName, hashKeyValue, rangeKeyValue);
      deleteFromCache(key, entityType);
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache delete error: " + e.getMessage(), e);
    }
  }

  private static void deleteFromCache(@NonNls String key, DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return;
    }
    try {
      OperationStatus status = memcachedClient.delete(key).getStatus();
      log.info(cacheLogPrefix
          + String.format(
              "Deleting key %s from cache. Operation status isSuccess %b, code %s, message %s",
              key, status.isSuccess(), status.getStatusCode(), status.getMessage()));
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache delete error: " + e.getMessage(), e);
    }
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.DELETE,
        null,
        null,
        null,
        null,
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  protected static Map<String, Object> getBulkFromCache(
      Set<String> keyValues, @SuppressWarnings("SameParameterValue") DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return null;
    }
    try {
      Collection<String> keys = keyValues.stream()
          .map(key -> getCacheKey(TableName.MAIN.name, key))
          .collect(Collectors.toList());
      sendDataplaneLogToKinesis(
          entityType,
          DataOperationType.BATCH_READ,
          null,
          null,
          null,
          null,
          true,
          getLastReferenceMethod(Thread.currentThread().getStackTrace()));
      return memcachedClient.getBulk(keys);
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache get error: " + e.getMessage(), e);
    }
    return null;
  }

  /**
   * Get from cache
   *
   * @param key - key for the object in the cache
   * @param entityType - type of the service/entity
   */
  public static Object getFromCache(String key, DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return null;
    }
    try {
      String keyBase = getCacheKey(emptyString, key);
      sendDataplaneLogToKinesis(
          entityType,
          DataOperationType.GET,
          null,
          null,
          null,
          null,
          true,
          getLastReferenceMethod(Thread.currentThread().getStackTrace()));
      return memcachedClient.get(keyBase);
    } catch (Exception e) {
      try {
        return memcachedClient.get(key);
      } catch (Exception ex) {
        log.error(cacheLogPrefix + " Elasticache get error: " + ex.getMessage(), ex);
        return null;
      }
    }
  }

  private static void populateCache(
      String table, Object hashKeyValue, Object rangeKeyValue, Object value) {
    if (isMemCachedClientClosed()) {
      return;
    }
    try {
      String key = getFullKey(table, hashKeyValue, rangeKeyValue);
      populateCache(key, value, 3600, true, null);
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache set error: " + e.getMessage(), e);
    }
  }

  /**
   * Populate cache
   *
   * @param key - key for the object in the cache
   * @param value - value for the object in the cache
   * @param exp - time for object to expire in cache
   * @param entityType - type of the service/entity
   */
  public static void populateCache(
      @NonNls String key, Object value, int exp, DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return;
    }
    try {
      populateCache(key, value, exp, false, entityType);
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache set error: " + e.getMessage(), e);
    }
  }

  private static void populateCache(
      @NonNls String key, Object value, int exp, boolean isEncoded, DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return;
    }
    try {
      if (!isEncoded) {
        try {
          String encodedKey = getCacheKey(emptyString, key);
          memcachedClient.set(encodedKey, exp, value);
        } catch (Exception e) {
          // let's retry with usual procedure
          memcachedClient.set(key, exp, value);
        }
      } else {
        memcachedClient.set(key, exp, value);
      }
      sendDataplaneLogToKinesis(
          entityType,
          DataOperationType.PUT,
          null,
          null,
          null,
          null,
          true,
          getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache set error: " + e.getMessage(), e);
    }
  }

  /**
   * Update in cache
   *
   * @param table - table of dynamodb which is used to get key for cache
   * @param hashKeyValue - primary key of the item which is used to get key for cache
   * @param rangeKeyValue - range key of the item which is used to get key for cache
   * @param value - value for the object in the cache
   * @param entityType - type of the service/entity
   */
  public static void updateInCache(
      String table,
      Object hashKeyValue,
      Object rangeKeyValue,
      Object value,
      DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return;
    }
    try {
      String key = getFullKey(table, hashKeyValue, rangeKeyValue);
      if (memcachedClient.get(key) != null) {
        memcachedClient.set(key, (int) (3600 + Math.random() * 120), value);
      }
      sendDataplaneLogToKinesis(
          entityType,
          DataOperationType.UPDATE,
          null,
          null,
          null,
          null,
          true,
          getLastReferenceMethod(Thread.currentThread().getStackTrace()));
    } catch (Exception e) {
      log.error(cacheLogPrefix + " Elasticache update error: " + e.getMessage(), e);
    }
  }

  // not sure if we are even using this function, needs to check
  public static void executeTransactWriteItems(TransactWriteItemsRequest twir) {
    client.transactWriteItems(twir);
  }

  // not sure if we are even using this function, needs to check
  public static Update createUpdate(
      String tableName,
      String expression,
      Map<String, AttributeValue> keys,
      Map<String, String> names,
      Map<String, AttributeValue> values) {
    return new Update()
        .withTableName(getTableName(tableName))
        .withKey(keys)
        .withUpdateExpression(expression)
        .withExpressionAttributeValues(values)
        .withExpressionAttributeNames(names)
        .withReturnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD);
  }

  // not sure if we are even using this function, needs to check
  public static TransactWriteItemsRequest createTWIR(Collection<TransactWriteItem> actions) {
    return new TransactWriteItemsRequest()
        .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
        .withTransactItems(actions);
  }

  /**
   * Delete users data
   *
   * @param items - items to be removed
   * @param tableName - table from which items to be deleted
   * @param entityType - type of the service/entity
   */
  public static void deleteUserData(
      Iterator<Item> items, String tableName, DataEntityType entityType) {
    List<PrimaryKey> primaryKeys = new ArrayList<>();
    while (items.hasNext()) {
      Item item = items.next();
      primaryKeys.add(new PrimaryKey(pk, item.getString(pk), sk, item.getString(sk)));
    }
    deleteListItemsBatch(primaryKeys, tableName, entityType);
  }

  /**
   * Delete batch
   *
   * @param keysToErase - list of primary keys of the items to be removed
   * @param tableName - table from which items to be deleted
   * @param entityType - type of the service/entity
   */
  public static void deleteListItemsBatch(
      List<PrimaryKey> keysToErase, String tableName, DataEntityType entityType) {
    if (Utils.isListNotNullOrEmpty(keysToErase)) {
      List<List<PrimaryKey>> list = Utils.splitList(keysToErase, batchChunkSize);
      list.forEach(arr -> {
        batchWriteItem(
            tableName,
            new TableWriteItems(getTableName(tableName))
                .withPrimaryKeysToDelete(arr.toArray(new PrimaryKey[0])),
            entityType);
      });
    }
  }

  /**
   * Write Batch
   *
   * @param items - list of the items to be written
   * @param tableName - table to which the items to be saved
   * @param entityType - type of the service/entity
   */
  public static void batchWriteListItems(
      List<Item> items, String tableName, DataEntityType entityType) {
    if (Utils.isListNotNullOrEmpty(items)) {
      List<List<Item>> list = Utils.splitList(items, batchChunkSize);
      list.forEach(arr -> {
        BatchWriteItemOutcome outcome = batchWriteItem(
            tableName,
            new TableWriteItems(getTableName(tableName)).withItemsToPut(arr),
            entityType);
        Map<String, List<WriteRequest>> unprocessed;
        if (Objects.isNull(outcome)) {
          return;
        }
        do {
          unprocessed = outcome.getUnprocessedItems();
          if (!unprocessed.isEmpty()) {
            outcome = batchWriteItemUnprocessed(unprocessed);
          }
        } while (!unprocessed.isEmpty());
      });
    }
  }

  /**
   * List cache data
   *
   * @param entityType - type of the service/entity
   */
  public static JsonObject getMemcachedData(DataEntityType entityType) {
    try {
      if (memcachedClient != null && !memcachedClient.getStats().isEmpty()) {
        log.info(cacheLogPrefix + " Memcached data is requested");

        sendDataplaneLogToKinesis(
            entityType,
            DataOperationType.LIST_CACHE,
            null,
            null,
            null,
            null,
            true,
            getLastReferenceMethod(Thread.currentThread().getStackTrace()));

        return new JsonObject(
            new HashMap<>(memcachedClient.getStats().values().iterator().next()));
      }
      return new JsonObject().put(Field.ERROR.getName(), "Memcached client is not available");
    } catch (Exception castException) {
      log.error(cacheLogPrefix + " Cant get memcached data", castException);
      return new JsonObject().put(Field.ERROR.getName(), "Memcached client is not available");
    }
  }

  /**
   * Delete cache data
   *
   * @param entityType - type of the service/entity
   */
  public static void deleteMemcachedData(String key, DataEntityType entityType) {
    if (isMemCachedClientClosed()) {
      return;
    }
    if (Utils.isStringNotNullOrEmpty(key)) {
      memcachedClient.delete(key).addListener(listener -> {
        log.info(cacheLogPrefix + " Key '" + key + "' is deleted from Memcached data");
      });
    } else {
      memcachedClient.flush().addListener(listener -> {
        log.info(cacheLogPrefix + " Memcached data is purged");
      });
    }
    sendDataplaneLogToKinesis(
        entityType,
        DataOperationType.DELETE,
        null,
        null,
        null,
        null,
        true,
        getLastReferenceMethod(Thread.currentThread().getStackTrace()));
  }

  private static boolean isMemCachedClientClosed() {
    if (Objects.isNull(memcachedClient)) {
      log.warn(cacheLogPrefix + " MemCachedClient is not alive");
      return true;
    }
    return false;
  }

  private static String getLastReferenceMethod(StackTraceElement[] stackTrace) {
    if (stackTrace.length < 3) {
      return "Unknown";
    }
    int i = 1;
    String className, methodName;
    do {
      i += 1;
      className = stackTrace[i].getClassName();
      methodName = stackTrace[i].getMethodName();
    } while (className.equals(Dataplane.class.getName()) || methodName.equals("forEach"));
    return methodName;
  }

  private static void sendDataplaneLogToKinesis(
      DataEntityType entityType,
      DataOperationType operationType,
      String tableName,
      String tableIndex,
      Object outcome,
      String returnConsumedCapacity,
      boolean cacheHit,
      String functionName) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Thread thread = new Thread(() -> {
      try {
        DBConsumedUnits units =
            new DBConsumedUnits(outcome, tableIndex, returnConsumedCapacity, operationType);
        DataplaneLog dataplaneLog = new DataplaneLog(
            entityType,
            operationType,
            tableName,
            tableIndex,
            GMTHelper.utcCurrentTime(),
            dynamoRegion,
            units.getConsumedUnits(),
            cacheHit,
            functionName);
        dataplaneLog.sendToServer();
      } catch (Exception ex) {
        log.error(logPrefix + " Could not send dataplane log to kinesis for entityType "
            + entityType + " and operationType " + operationType + " - " + ex);
      } finally {
        executor.shutdown();
      }
    });
    executor.execute(thread::start);
  }

  public enum TableName {
    MAIN(emptyString),
    TEMP("temp"),
    SIMPLE_STORAGE(Field.SIMPLE_STORAGE.getName()),
    COMMENTS("comments2"),
    SHARED_LINKS("shared_links"),
    STATS("perf_stats"),
    RESOURCES("resources");

    public final String name;

    TableName(String name) {
      this.name = name;
    }
  }
}
