package com.graebert.storage.xray;

import com.graebert.storage.util.Field;

public enum XrayField {
  // XRAY-SPECIFIC
  OPERATION_GROUP("operationGroup"),
  OPERATION_ID("operationId"),
  IS_SEGMENT_ERROR("isSegmentError"),
  IS_OPERATIONAL_ERROR("isOperationalError"),
  STATUS_CODE(Field.STATUS_CODE.getName()),

  // EXTERNAL OBJECTS
  STORAGE_TYPE(Field.STORAGE_TYPE.getName()),
  EXTERNAL_ID(Field.EXTERNAL_ID.getName()),
  DRIVE_ID(Field.DRIVE_ID.getName()),
  ORIGINAL_FILE_ID("originalFileId"),
  NEW_FILE_ID("newFileId"),
  OBJECT_ID(Field.OBJECT_ID.getName()),
  FILE_ID(Field.FILE_ID.getName()),
  FOLDER_ID(Field.FOLDER_ID.getName()),
  ID(Field.ID.getName()),
  VERSION_ID(Field.VERSION_ID.getName()),
  PATH(Field.PATH.getName()),

  IS_CONFLICTED(Field.IS_CONFLICTED.getName()),
  CONFLICTED_FILE_REASON("conflictedFileReason"),
  CONFLICTED_FILE_ID("conflictedFileId"),
  ERROR_MESSAGE(Field.ERROR_MESSAGE.getName()),
  ERROR_ID(Field.ERROR_ID.getName()),
  FILE_LOG("fileLog"),

  USER_ID(Field.USER_ID.getName()),
  USER_EXISTS("userExists"),
  CREATE_NEW_USER("createNewUser"),
  FROM_XENON("fromXenon"),
  X_SESSION_ID(Field.X_SESSION_ID.getName()),
  SESSION_ID(Field.SESSION_ID.getName()),
  EXISTING_SESSION_ID("existingSessionId"),
  SESSION_FOUND("sessionFound"),

  // EXTERNAL
  TIMINGS("timings"),
  ITEMS("items"),
  META_DATA("metaData"),
  INCOMING_BODY("incomingBody"),
  DESTINATION("destination"),
  PAGE_TOKEN(Field.PAGE_TOKEN.getName()),
  QUERY(Field.QUERY.getName()),

  // WEBDAV
  SOURCE("source"),
  RESOURCES_SIZE("resourcesSize"),
  CONNECTION_DATA("connectionData"),
  NEW_DAV_ID("newDavId"),
  DAV_USER("DavUser"),

  // THUMBNAIL
  IDS(Field.IDS.getName()),
  IDS_TO_GENERATE("idsToGenerate"),
  JOB_ID(Field.JOB_ID.getName()),
  RESULT(Field.RESULT.getName()),
  MESSAGE(Field.MESSAGE.getName()),
  QUEUE_URL("queueUrl"),
  FORCE(Field.FORCE.getName()),
  PREFERENCES(Field.PREFERENCES.getName()),
  THUMBNAIL_INFO("thumbnailInfo"),

  // UNIREST
  UNIREST_URL("unirestUrl"),
  UNIREST_METHOD("unirestMethod"),
  UNIREST_RESPONSE_STATUS("unirestResponseStatus"),
  UNIREST_RESPONSE_MESSAGE("unirestResponseMessage"),
  UNIREST_ERROR_STATUS("unirestErrorStatus"),
  UNIREST_REQUEST_NAME("unirestRequestName"),

  // ASYNC
  ASYNC_NAME("asyncName"),

  // THUMBNAILS METADATA
  CAN_PROCESS_CHUNK("canProcessChunk"),
  AMOUNT_OF_RECEIVED_SQS_MESSAGES("amountOfReceivedSQSMessages");

  private final String key;

  XrayField(String key) {
    this.key = key;
  }

  public String getKey() {
    return key;
  }
}
