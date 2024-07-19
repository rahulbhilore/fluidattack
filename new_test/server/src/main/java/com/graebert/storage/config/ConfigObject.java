package com.graebert.storage.config;

public enum ConfigObject {
  FLUORINE("fluorine"),
  RESOURCES("resources"),
  STORAGES("storages"),
  SECRETS("secrets"),
  COGNITO("cognito"),
  TRANSLATE("translate"),
  SAML("saml"),
  SMTP("smtp"),
  POSTMARK("postmark"),
  STATSD("statsd"),
  SAMPLES("samples"),
  BOX("box"),
  DROPBOX("dropbox"),
  GDRIVE("gdrive"),
  ONSHAPE("onshape"),
  ONSHAPE_DEV("onshapedev"),
  ONSHAPE_STAGING("onshapestaging"),
  TRIMBLE("trimble"),
  ONEDRIVE("onedrive"),
  ONEDRIVEBUSINESS("onedrivebusiness"),
  ONEDRIVE_BUSINESS("onedrive_business"),
  SHAREPOINT("sharepoint"),
  WEBDAV("webdav"),
  NEXTCLOUD("nextcloud"),
  HANCOM("hancom"),
  HANCOM_STAGING("hancomstg"),
  DAX("dax"),
  DYNAMODB("dynamodb"),
  S3("s3"),
  ELASTICACHE("elasticache"),
  SQS("sqs"),
  THUMBNAIL("thumbnail"),
  SIMPLESTORAGE("simplestorage"),
  KINESIS("kinesis"),
  FONTS("fonts"),
  INSTANCE_OPTIONS("instanceOptions"),
  DEFAULT_USER_OPTIONS("defaultUserOptions"),
  DEFAULT_COMPANY_OPTIONS("defaultCompanyOptions"),
  USER_PREFERENCES("userPreferences"),
  SAMPLE_FILES("samplefiles"),
  THUMBNAIL_CHUNK_SIZE("thumbnailChunkSize"),
  DOWNLOAD_ROOT_FOLDERS("downloadRootFolders");

  private final String label;

  ConfigObject(String label) {
    this.label = label;
  }

  public String getLabel() {
    return this.label;
  }
}
