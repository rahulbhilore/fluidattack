package com.graebert.storage.stats.logs.sharing;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class SharingLog extends BasicLog {
  // main info - exists for all sharing events
  private SharingTypes sharingType;
  private SharingActions sharingAction;
  private String device;

  // only for sharing by email
  private String targetEmail;
  private String role;

  // for PL creation/update
  private long expirationTime;
  private boolean isExportAllowed;

  // sharing by email constructor
  public SharingLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      SharingTypes sharingType,
      SharingActions sharingAction,
      String device,
      String targetEmail,
      String role) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.sharingType = sharingType;
    this.sharingAction = sharingAction;
    this.device = device;

    this.targetEmail = targetEmail;
    this.role = role;
  }

  // sharing by PL constructor
  public SharingLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      SharingTypes sharingType,
      SharingActions sharingAction,
      String device,
      long expirationTime,
      boolean isExportAllowed) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.sharingType = sharingType;
    this.sharingAction = sharingAction;
    this.device = device;

    this.expirationTime = expirationTime;
    this.isExportAllowed = isExportAllowed;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put("sharingtype", getEnumValue(sharingType))
        .put("sharingaction", getEnumValue(sharingAction))
        .put(Field.DEVICE.getName(), device)
        .put("targetemail", targetEmail)
        .put(Field.ROLE.getName(), role)
        .put("expirationtime", expirationTime)
        .put("isexportallowed", isExportAllowed);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.sharingStream);
  }

  // GETTERS & SETTERS

  public SharingTypes getSharingType() {
    return sharingType;
  }

  public void setSharingType(SharingTypes sharingType) {
    this.sharingType = sharingType;
  }

  public SharingActions getSharingAction() {
    return sharingAction;
  }

  public void setSharingAction(SharingActions sharingAction) {
    this.sharingAction = sharingAction;
  }

  public String getDevice() {
    return device;
  }

  public void setDevice(String device) {
    this.device = device;
  }

  public String getTargetEmail() {
    return targetEmail;
  }

  public void setTargetEmail(String targetEmail) {
    this.targetEmail = targetEmail;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public long getExpirationTime() {
    return expirationTime;
  }

  public void setExpirationTime(long expirationTime) {
    this.expirationTime = expirationTime;
  }

  public boolean isExportAllowed() {
    return isExportAllowed;
  }

  public void setExportAllowed(boolean exportAllowed) {
    isExportAllowed = exportAllowed;
  }
}
