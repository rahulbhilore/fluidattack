package com.graebert.storage.stats.logs.storage;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class StorageLog extends BasicLog {
  // main info - exists for all files
  private final StorageActions actionType;
  private final String externalId;

  // xenon session constructor
  public StorageLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      StorageActions actionType,
      String externalId) {
    super(userId, objectId, storageType, email, timestamp, isActive);

    this.actionType = actionType;
    this.externalId = externalId;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put(Field.ACTION.getName(), getEnumValue(actionType))
        .put(Field.EXTERNAL_ID_LOWER.getName(), externalId);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.storageStream);
  }
}
