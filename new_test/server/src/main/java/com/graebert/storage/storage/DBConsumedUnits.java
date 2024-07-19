package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.BatchGetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.BatchWriteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DeleteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.GetItemOutcome;
import com.amazonaws.services.dynamodbv2.document.PutItemOutcome;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.model.Capacity;
import com.amazonaws.services.dynamodbv2.model.ConsumedCapacity;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity;
import com.graebert.storage.stats.logs.data.DataOperationType;
import com.graebert.storage.util.Utils;
import java.util.Map;
import java.util.Objects;

public class DBConsumedUnits {

  private final Object outcome;
  private final DataOperationType operationType;
  private final String tableIndex;
  private final String returnConsumedCapacity;
  private static final Double defaultUnits = 0.0;

  public DBConsumedUnits(
      Object outcome,
      String tableIndex,
      String returnConsumedCapacity,
      DataOperationType operationType) {
    this.outcome = outcome;
    this.operationType = operationType;
    this.tableIndex = tableIndex;
    this.returnConsumedCapacity = returnConsumedCapacity;
  }

  private static Double forGetOrQuery(
      Object outcome, String tableIndex, ReturnConsumedCapacity rcc) {
    double totalUnits = 0;
    if (Objects.nonNull(outcome)) {
      Map<String, Capacity> indexesCapacity = null;
      ConsumedCapacity consumedCapacity = null;
      if (outcome instanceof GetItemOutcome) {
        if (Objects.isNull(((GetItemOutcome) outcome).getGetItemResult())) {
          return defaultUnits;
        }
        consumedCapacity = ((GetItemOutcome) outcome).getGetItemResult().getConsumedCapacity();
        if (rcc.equals(ReturnConsumedCapacity.INDEXES)) {
          indexesCapacity = ((GetItemOutcome) outcome)
              .getGetItemResult()
              .getConsumedCapacity()
              .getGlobalSecondaryIndexes();
        }
      } else if (outcome instanceof QueryOutcome) {
        if (Objects.isNull(((QueryOutcome) outcome).getQueryResult())) {
          return defaultUnits;
        }
        consumedCapacity = ((QueryOutcome) outcome).getQueryResult().getConsumedCapacity();
        if (rcc.equals(ReturnConsumedCapacity.INDEXES)) {
          indexesCapacity = ((QueryOutcome) outcome)
              .getQueryResult()
              .getConsumedCapacity()
              .getGlobalSecondaryIndexes();
        }
      }
      if (Objects.nonNull(consumedCapacity)) {
        totalUnits = consumedCapacity.getCapacityUnits();
      }
      if (Objects.nonNull(indexesCapacity) && Utils.isStringNotNullOrEmpty(tableIndex)) {
        totalUnits += indexesCapacity.get(tableIndex).getCapacityUnits();
      }
    }
    return totalUnits;
  }

  private static Double forScan(ScanOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getScanResult())) {
      return defaultUnits;
    }
    return outcome.getScanResult().getConsumedCapacity().getCapacityUnits();
  }

  private static Double forQueryRequest(QueryResult result) {
    if (Objects.isNull(result)) {
      return defaultUnits;
    }
    return result.getConsumedCapacity().getCapacityUnits();
  }

  private static Double forPutItem(PutItemOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getPutItemResult())) {
      return defaultUnits;
    }
    return outcome.getPutItemResult().getConsumedCapacity().getCapacityUnits();
  }

  private static Double forUpdateItem(UpdateItemOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getUpdateItemResult())) {
      return defaultUnits;
    }
    return outcome.getUpdateItemResult().getConsumedCapacity().getCapacityUnits();
  }

  private static Double forDeleteItem(DeleteItemOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getDeleteItemResult())) {
      return defaultUnits;
    }
    return outcome.getDeleteItemResult().getConsumedCapacity().getCapacityUnits();
  }

  private static Double forBatchRead(BatchGetItemOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getBatchGetItemResult())) {
      return defaultUnits;
    }
    Double consumedUnits = defaultUnits;
    for (ConsumedCapacity capacity : outcome.getBatchGetItemResult().getConsumedCapacity()) {
      consumedUnits += capacity.getCapacityUnits();
    }
    return consumedUnits;
  }

  private static Double forBatchWrite(BatchWriteItemOutcome outcome) {
    if (Objects.isNull(outcome) || Objects.isNull(outcome.getBatchWriteItemResult())) {
      return defaultUnits;
    }
    Double consumedUnits = defaultUnits;
    for (ConsumedCapacity capacity : outcome.getBatchWriteItemResult().getConsumedCapacity()) {
      consumedUnits += capacity.getCapacityUnits();
    }
    return consumedUnits;
  }

  public Double getConsumedUnits() {
    if (!Utils.isStringNotNullOrEmpty(returnConsumedCapacity)) {
      return defaultUnits;
    }
    ReturnConsumedCapacity rcc = ReturnConsumedCapacity.valueOf(returnConsumedCapacity);
    if (rcc.equals(ReturnConsumedCapacity.NONE)) {
      return defaultUnits;
    }
    try {
      switch (operationType) {
        case GET:
        case QUERY:
          return forGetOrQuery(outcome, tableIndex, rcc);
        case QUERY_REQUEST:
          return forQueryRequest((QueryResult) outcome);
        case DELETE:
          return forDeleteItem((DeleteItemOutcome) outcome);
        case PUT:
          return forPutItem((PutItemOutcome) outcome);
        case UPDATE:
          return forUpdateItem((UpdateItemOutcome) outcome);
        case BATCH_READ:
          return forBatchRead((BatchGetItemOutcome) outcome);
        case BATCH_WRITE:
          return forBatchWrite((BatchWriteItemOutcome) outcome);
        case SCAN:
          return forScan((ScanOutcome) outcome);
        default:
          return defaultUnits;
      }
    } catch (NullPointerException npe) {
      return defaultUnits;
    }
  }
}
