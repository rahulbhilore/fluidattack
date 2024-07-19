package com.graebert.storage.stats.logs.data;

import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.storage.Dataplane;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

public class DataplaneLog extends BasicLog {
  private final DataEntityType entityType;
  private final DataOperationType transactionType;
  private final long actionTime;
  private final String tableName;
  private final String tableIndex;
  private final String dynamoDbRegion;
  private final String consumedUnits;
  private final boolean cacheHit;
  private final String referenceFunctionName;

  public DataplaneLog(
      DataEntityType entityType,
      DataOperationType transactionType,
      String tableName,
      String tableIndex,
      long actionTime,
      String dynamoDbRegion,
      Double consumedUnits,
      boolean cacheHit,
      String referenceFunctionName) {
    String finalTableName;
    if (Objects.nonNull(tableName) && tableName.isEmpty()) {
      finalTableName = Dataplane.TableName.MAIN.toString().toLowerCase();
    } else {
      finalTableName = tableName;
    }
    this.entityType = entityType;
    this.transactionType = transactionType;
    this.tableName = finalTableName;
    this.tableIndex = tableIndex;
    this.actionTime = actionTime;
    this.dynamoDbRegion = dynamoDbRegion;
    this.consumedUnits = consumedUnits.toString();
    this.cacheHit = cacheHit;
    this.referenceFunctionName = referenceFunctionName;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject()
        .put("entitytype", entityType)
        .put("transactiontype", transactionType)
        .put("actiontime", actionTime)
        .put("tablename", tableName)
        .put("tableindex", tableIndex)
        .put("dynamodbregion", dynamoDbRegion)
        .put("consumedunits", consumedUnits)
        .put("cachehit", cacheHit)
        .put("referencefunctionname", referenceFunctionName);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.dataplaneStream);
  }
}
