package com.graebert.storage.stats.logs.subscription;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class SubscriptionLog extends BasicLog {
  // main info - exists for all subscription events
  private SubscriptionActions subscriptionAction;
  private String reason;
  private String source;

  public SubscriptionLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      SubscriptionActions subscriptionAction,
      String reason,
      String source) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.subscriptionAction = subscriptionAction;
    this.reason = reason;
    this.source = source;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put("subscriptionaction", getEnumValue(subscriptionAction))
        .put(Field.REASON.getName(), reason)
        .put(Field.SOURCE.getName(), source);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.subscriptionStream);
  }

  // GETTERS & SETTERS

  public SubscriptionActions getSubscriptionAction() {
    return subscriptionAction;
  }

  public void setSubscriptionAction(SubscriptionActions subscriptionAction) {
    this.subscriptionAction = subscriptionAction;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
