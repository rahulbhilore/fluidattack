package com.graebert.storage.stats.logs.file;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class FileLog extends BasicLog {
  // main info - exists for all files
  private final FileActions actionType;
  private final long actionTime;
  private final String fileName;
  private final String sessionId;
  private final String fileSessionId;
  private final String modifier;
  private final String conflictingReason;
  private final String newObjectId;
  private final String previewId;
  private final String device;
  private final String thumbnailName;
  private final Boolean fileSessionExpired;

  // xenon session constructor
  public FileLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      FileActions actionType,
      long actionTime,
      String fileName,
      String sessionId,
      String previewId,
      String device,
      String thumbnailName) {
    super(userId, objectId, storageType, email, timestamp, isActive);

    this.actionType = actionType;
    this.actionTime = actionTime;
    this.fileName = fileName;
    this.sessionId = sessionId;
    this.previewId = previewId;
    this.device = device;
    this.thumbnailName = thumbnailName;
    this.modifier = null;
    this.fileSessionId = null;
    this.newObjectId = null;
    this.conflictingReason = null;
    this.fileSessionExpired = null;
  }

  // conflicting files constructor
  public FileLog(
      String userId,
      String objectId,
      String newObjectId,
      StorageType storageType,
      XSessionManager.ConflictingFileReason conflictingReason,
      long timestamp,
      boolean isActive,
      FileActions actionType,
      long actionTime,
      String fileName,
      String sessionId,
      String fileSessionId,
      String device,
      String modifier,
      boolean fileSessionExpired) {
    super(userId, objectId, storageType, null, timestamp, isActive);

    this.actionType = actionType;
    this.actionTime = actionTime;
    this.fileName = fileName;
    this.sessionId = sessionId;
    this.device = device;
    this.modifier = modifier;
    this.fileSessionId = fileSessionId;
    this.newObjectId = newObjectId;
    this.conflictingReason = conflictingReason.name();
    this.fileSessionExpired = fileSessionExpired;
    this.previewId = null;
    this.thumbnailName = null;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put("actiontype", getEnumValue(actionType))
        .put("actiontime", actionTime)
        .put(Field.FILE_NAME.getName(), fileName)
        .put("sessionid", sessionId)
        .put(Field.DEVICE.getName(), device)
        .put("previewid", previewId)
        .put("thumbnailname", thumbnailName)
        .put("filesessionid", fileSessionId)
        .put("conflictingreason", conflictingReason)
        .put("newobjectid", newObjectId)
        .put("modifier", modifier)
        .put("filesessionexpired", fileSessionExpired);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.filesStream);
  }
}
