package com.graebert.storage.stats.logs.email;

import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.mail.emails.EmailTemplateType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class EmailLog extends BasicLog {
  // email
  private String fileName;
  private boolean isEmailSent;
  private boolean isErrored;
  private String errorMessage;
  private String emailId;
  private EmailErrorCodes errorCode = null;
  private long emailTime;
  private EmailTemplateType emailTemplateType;

  // email actions
  private EmailActionType emailActionType;
  private String source;

  // email constructor
  public EmailLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      EmailTemplateType emailTemplateType) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.emailTemplateType = emailTemplateType;
  }

  // email actions constructor
  public EmailLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      EmailActionType emailActionType,
      String source) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.emailActionType = emailActionType;
    this.source = source;
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put(Field.FILE_NAME.getName(), fileName)
        .put("isemailsent", isEmailSent)
        .put("iserrored", isErrored)
        .put("errormessage", errorMessage)
        .put("emailid", emailId)
        .put("errorcode", getEnumValue(errorCode))
        .put("emailtime", emailTime)
        .put("templatetype", getEnumValue(emailTemplateType))
        .put("emailactiontype", getEnumValue(emailActionType))
        .put("source", source);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.emailStream);
  }

  public void triggerError(EmailErrorCodes emailErrorCodes, String errorMessage) {
    this.errorCode = emailErrorCodes;
    this.errorMessage = errorMessage;
    this.emailTime = GMTHelper.utcCurrentTime();
    this.isErrored = true;
    this.sendToServer();
  }

  // GETTERS & SETTERS

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public boolean isEmailSent() {
    return isEmailSent;
  }

  public void setEmailSent(boolean emailSent) {
    isEmailSent = emailSent;
  }

  public boolean isErrored() {
    return isErrored;
  }

  public void setErrored(boolean errored) {
    isErrored = errored;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public String getEmailId() {
    return emailId;
  }

  public void setEmailId(String emailId) {
    this.emailId = emailId;
  }

  public EmailErrorCodes getErrorCode() {
    return errorCode;
  }

  public void setErrorCode(EmailErrorCodes errorCode) {
    this.errorCode = errorCode;
  }

  public long getEmailTime() {
    return emailTime;
  }

  public void setEmailTime(long emailTime) {
    this.emailTime = emailTime;
  }

  public EmailTemplateType getTemplateType() {
    return emailTemplateType;
  }

  public void setTemplateType(EmailTemplateType emailTemplateType) {
    this.emailTemplateType = emailTemplateType;
  }

  public EmailActionType getEmailActionType() {
    return emailActionType;
  }

  public void setEmailActionType(EmailActionType emailActionType) {
    this.emailActionType = emailActionType;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }
}
