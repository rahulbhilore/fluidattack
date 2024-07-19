package com.graebert.storage.stats.logs;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class BasicLog {
  private String userId;
  private String objectId;
  private StorageType storageType;
  private String email;
  private long timestamp;
  private boolean isActive;

  public BasicLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive) {
    this.userId = userId;
    this.objectId = objectId;
    this.storageType = storageType;
    this.email = email;
    this.timestamp = timestamp;
    this.isActive = isActive;
  }

  public BasicLog() {}

  public JsonObject toJson() {
    return new JsonObject()
        .put("userid", userId)
        .put("objectid", objectId)
        .put("storagetype", storageType.name())
        .put(Field.EMAIL.getName(), email)
        .put(Field.TIMESTAMP.getName(), timestamp)
        .put("isactive", isActive);
  }

  public void sendToServer() {}

  protected String getEnumValue(Enum val) {
    if (val != null) {
      try {
        return val.name();
      } catch (Exception ignored) {
      }
    }
    return "NONE";
  }
  // GETTERS & SETTERS

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getObjectId() {
    return objectId;
  }

  public void setObjectId(String objectId) {
    this.objectId = objectId;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public void setStorageType(StorageType storageType) {
    this.storageType = storageType;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    isActive = active;
  }
}
