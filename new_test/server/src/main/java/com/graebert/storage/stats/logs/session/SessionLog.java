package com.graebert.storage.stats.logs.session;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.storage.XenonSessions.Mode;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class SessionLog extends BasicLog {
  // main info - exists for all sessions
  private SessionTypes sessionType;
  private String sessionId;

  private Mode fileMode;

  private String userRole;
  private String region;
  private String device;
  private String userAgent;
  private long midnight;
  private long startTime;
  private long endTime;

  // for FL sessions
  private String licenseType;
  private long licenseExpirationDate;
  private boolean hasLicenseExpired;

  // xenon session constructor
  public SessionLog(
      String userId,
      String objectId,
      Mode fileMode,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      SessionTypes sessionType,
      String sessionId,
      String region,
      String device,
      String userAgent,
      long midnight,
      long startTime,
      long endTime,
      String licenseType,
      long licenseExpirationDate) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.sessionType = sessionType;
    this.fileMode = fileMode;
    this.sessionId = sessionId;
    this.region = region;
    this.device = device;
    this.userAgent = userAgent;
    this.midnight = midnight;
    this.startTime = startTime;
    this.endTime = endTime;
    this.licenseType = licenseType;
    this.licenseExpirationDate = licenseExpirationDate;
  }

  // fluorine session constructor
  public SessionLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      JsonArray userRoles,
      long timestamp,
      boolean isActive,
      SessionTypes sessionType,
      String sessionId,
      String region,
      String device,
      String userAgent,
      long midnight,
      long startTime,
      long endTime,
      String licenseType,
      long licenseExpirationDate,
      boolean hasLicenseExpired) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.userRole = userRoles.contains("1") ? "Admin" : "User";
    this.sessionType = sessionType;
    this.sessionId = sessionId;
    this.region = region;
    this.device = device;
    this.userAgent = userAgent;
    this.midnight = midnight;
    this.startTime = startTime;
    this.endTime = endTime;

    this.licenseType = licenseType;
    this.licenseExpirationDate = licenseExpirationDate;
    this.hasLicenseExpired = hasLicenseExpired;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put("sessiontype", getEnumValue(sessionType))
        .put("userrole", userRole)
        .put("filemode", getEnumValue(fileMode))
        .put("sessionid", sessionId)
        .put(Field.REGION.getName(), region)
        .put(Field.DEVICE.getName(), device)
        .put("useragent", userAgent)
        .put("midnight", midnight)
        .put("starttime", startTime)
        .put("endtime", endTime)
        .put("licensetype", licenseType)
        .put("licenseexpirationdate", licenseExpirationDate)
        .put("haslicenseexpired", hasLicenseExpired);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.sessionStream);
  }

  // GETTERS & SETTERS

  public SessionTypes getSessionType() {
    return sessionType;
  }

  public void setSessionType(SessionTypes sessionType) {
    this.sessionType = sessionType;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getRegion() {
    return region;
  }

  public void setRegion(String region) {
    this.region = region;
  }

  public String getDevice() {
    return device;
  }

  public void setDevice(String device) {
    this.device = device;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public long getMidnight() {
    return midnight;
  }

  public void setMidnight(long midnight) {
    this.midnight = midnight;
  }

  public long getStartTime() {
    return startTime;
  }

  public void setStartTime(long startTime) {
    this.startTime = startTime;
  }

  public long getEndTime() {
    return endTime;
  }

  public void setEndTime(long endTime) {
    this.endTime = endTime;
  }

  public String getLicenseType() {
    return licenseType;
  }

  public void setLicenseType(String licenseType) {
    this.licenseType = licenseType;
  }

  public long getLicenseExpirationDate() {
    return licenseExpirationDate;
  }

  public void setLicenseExpirationDate(long licenseExpirationDate) {
    this.licenseExpirationDate = licenseExpirationDate;
  }

  public boolean isHasLicenseExpired() {
    return hasLicenseExpired;
  }

  public void setHasLicenseExpired(boolean hasLicenseExpired) {
    this.hasLicenseExpired = hasLicenseExpired;
  }
}
