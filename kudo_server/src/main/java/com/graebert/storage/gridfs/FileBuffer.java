package com.graebert.storage.gridfs;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class FileBuffer {

  private byte[] data;

  private String fileId;
  private String userId;
  private String fileName;
  private String sessionId;
  private String folderId;
  private String userName;
  private String fname;
  private String surname;
  private String email;
  private String externalId;
  private String storageType;
  private String locale;
  private String xSessionId;
  private String baseChangeId;
  private String conflictingFileReason;
  private String cloneFileId;
  private String uploadRequestId;
  private String device;
  private String presignedUploadId;
  private String uploadToken;

  private JsonArray fileChangesInfo;
  private JsonObject preferences;

  private boolean tmpl;
  private boolean isAdmin;
  private boolean fileSessionExpired;
  private boolean fileSavePending;
  private boolean copyComments;
  private boolean includeResolvedComments;
  private boolean includeDeletedComments;
  private boolean updateUsage = true; // keeping default "true" for updateUsage

  public void setFolderId(String folderId) {
    this.folderId = folderId;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getDevice() {
    return device;
  }

  public void setDevice(String device) {
    this.device = device;
  }

  public void setUserName(String userName) {
    this.userName = userName;
  }

  public String getFname() {
    return fname;
  }

  public void setFname(String fname) {
    this.fname = fname;
  }

  public String getSurname() {
    return surname;
  }

  public void setSurname(String surname) {
    this.surname = surname;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }

  public void setStorageType(String storageType) {
    this.storageType = storageType;
  }

  public String getXSessionId() {
    return this.xSessionId;
  }

  public void setXSessionId(String xSessionId) {
    this.xSessionId = xSessionId;
  }

  public byte[] getData() {
    return data;
  }

  public void setData(byte[] data) {
    this.data = data;
  }

  public void setPreferences(JsonObject preferences) {
    this.preferences = preferences;
  }

  public void setFileChangesInfo(JsonArray fileChangesInfo) {
    this.fileChangesInfo = fileChangesInfo;
  }

  public boolean isFileSessionExpired() {
    return fileSessionExpired;
  }

  public void setFileSessionExpired(boolean fileSessionExpired) {
    this.fileSessionExpired = fileSessionExpired;
  }

  public boolean isFileSavePending() {
    return fileSavePending;
  }

  public void setFileSavePending(boolean fileSavePending) {
    this.fileSavePending = fileSavePending;
  }

  public void setUpdateUsage(boolean updateUsage) {
    this.updateUsage = updateUsage;
  }

  public void setBaseChangeId(String baseChangeId) {
    this.baseChangeId = baseChangeId;
  }

  public void setTmpl(boolean tmpl) {
    this.tmpl = tmpl;
  }

  public void setAdmin(boolean admin) {
    isAdmin = admin;
  }

  public String getFileId() {
    return fileId;
  }

  public void setFileId(String fileId) {
    this.fileId = fileId;
  }

  public void setConflictingFileReason(String conflictingFileReason) {
    this.conflictingFileReason = conflictingFileReason;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public boolean isCopyComments() {
    return copyComments;
  }

  public void setCopyComments(boolean copyComments) {
    this.copyComments = copyComments;
  }

  public boolean isIncludeResolvedComments() {
    return includeResolvedComments;
  }

  public void setIncludeResolvedComments(boolean includeResolvedComments) {
    this.includeResolvedComments = includeResolvedComments;
  }

  public boolean isIncludeDeletedComments() {
    return includeDeletedComments;
  }

  public void setIncludeDeletedComments(boolean includeDeletedComments) {
    this.includeDeletedComments = includeDeletedComments;
  }

  public String getLocale() {
    return locale != null ? locale : "en";
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public String getCloneFileId() {
    return cloneFileId;
  }

  public void setCloneFileId(String cloneFileId) {
    this.cloneFileId = cloneFileId;
  }

  public void setUploadRequestId(String uploadRequestId) {
    this.uploadRequestId = uploadRequestId;
  }

  public String getPresignedUploadId() {
    return presignedUploadId;
  }

  public void setPresignedUploadId(String presignedUploadId) {
    this.presignedUploadId = presignedUploadId;
  }

  public String getUploadToken() {
    return uploadToken;
  }

  public void setUploadToken(String uploadToken) {
    this.uploadToken = uploadToken;
  }

  public JsonObject bufferToJson() {
    JsonObject jsonObject = new JsonObject();

    if (Utils.isStringNotNullOrEmpty(fileId)) {
      jsonObject.put(Field.FILE_ID.getName(), fileId);
    }

    if (Utils.isStringNotNullOrEmpty(userId)) {
      jsonObject.put(Field.USER_ID.getName(), userId);
    }

    if (Utils.isStringNotNullOrEmpty(folderId)) {
      jsonObject.put(Field.FOLDER_ID.getName(), folderId);
    }

    if (Utils.isStringNotNullOrEmpty(fileName)) {
      jsonObject.put(Field.NAME.getName(), fileName);
    }

    if (Utils.isStringNotNullOrEmpty(externalId)) {
      jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
    }

    if (Utils.isStringNotNullOrEmpty(xSessionId)) {
      jsonObject.put(Field.X_SESSION_ID.getName(), xSessionId);
    }

    if (Utils.isStringNotNullOrEmpty(device)) {
      jsonObject.put(Field.DEVICE.getName(), device);
    }

    if (Utils.isStringNotNullOrEmpty(storageType)) {
      jsonObject.put(Field.STORAGE_TYPE.getName(), storageType);
    }

    if (Utils.isJsonObjectNotNullOrEmpty(preferences)) {
      jsonObject.put(Field.PREFERENCES.getName(), preferences);
    }

    if (Utils.isStringNotNullOrEmpty(sessionId)) {
      jsonObject.put(Field.SESSION_ID.getName(), sessionId);
    }

    if (Utils.isStringNotNullOrEmpty(userName)) {
      jsonObject.put("userName", userName);
    }

    if (Utils.isStringNotNullOrEmpty(fname)) {
      jsonObject.put(Field.F_NAME.getName(), fname);
    }

    if (Utils.isStringNotNullOrEmpty(surname)) {
      jsonObject.put(Field.SURNAME.getName(), surname);
    }

    if (Utils.isStringNotNullOrEmpty(email)) {
      jsonObject.put(Field.EMAIL.getName(), email);
    }

    if (Utils.isStringNotNullOrEmpty(baseChangeId)) {
      jsonObject.put(Field.BASE_CHANGE_ID.getName(), baseChangeId);
    }

    if (Utils.isStringNotNullOrEmpty(locale)) {
      jsonObject.put(Field.LOCALE.getName(), locale);
    }

    if (Utils.isStringNotNullOrEmpty(presignedUploadId)) {
      jsonObject.put(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId);
    }

    if (isFileSessionExpired()) {
      jsonObject.put("fileSessionExpired", true);
    }

    if (fileChangesInfo != null) {
      jsonObject.put("fileChangesInfo", fileChangesInfo);
    }

    if (Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
      jsonObject.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
    }

    if (Utils.isStringNotNullOrEmpty(cloneFileId)) {
      jsonObject.put(Field.CLONE_FILE_ID.getName(), cloneFileId);
    }

    if (Utils.isStringNotNullOrEmpty(uploadRequestId)) {
      jsonObject.put(Field.UPLOAD_REQUEST_ID.getName(), uploadRequestId);
    }

    if (isFileSavePending()) {
      jsonObject.put("fileSavePending", true);
    }

    if (isCopyComments()) {
      jsonObject.put(Field.COPY_COMMENTS.getName(), true);
    }

    if (isIncludeResolvedComments()) {
      jsonObject.put(Field.INCLUDE_RESOLVED_COMMENTS.getName(), true);
    }

    if (isIncludeDeletedComments()) {
      jsonObject.put(Field.INCLUDE_DELETED_COMMENTS.getName(), true);
    }

    jsonObject.put("updateUsage", updateUsage);
    jsonObject.put(Field.IS_ADMIN.getName(), isAdmin);
    jsonObject.put("tmpl", tmpl);

    return jsonObject;
  }
}
