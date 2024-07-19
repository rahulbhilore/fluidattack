package com.graebert.storage.thumbnails;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;

public class ThumbnailNameInfo extends DynamoBusModBase {

  private String thumbnailName;
  private String versionId;
  private String fileId;
  private String shortStorageType;
  private String storageType;
  private boolean success = false;

  // this class is not used for now, but let's keep it for future
  public ThumbnailNameInfo(String thumbnail) {
    try {
      thumbnailName = thumbnail.substring(thumbnail.lastIndexOf("/") + 1, thumbnail.indexOf("?"));
      if (Utils.isStringNotNullOrEmpty(thumbnailName)) {
        String fullFileId = getValueWithoutExtension(thumbnailName);
        if (Utils.isStringNotNullOrEmpty(fullFileId)) {
          shortStorageType = fullFileId.split("_")[0];
          storageType = StorageType.shortToLong(fullFileId.split("_")[0]);
          versionId = fullFileId.split("_")[fullFileId.split("_").length - 1];
          fileId = fullFileId.substring(fullFileId.indexOf("_") + 1, fullFileId.lastIndexOf("_"));
          success = true;
        }
      }
    } catch (Exception e) {
      log.error("Recent : Error in getting thumbnail info " + e.getLocalizedMessage());
    }
  }

  private static String getValueWithoutExtension(String value) {
    return value.substring(0, value.lastIndexOf("."));
  }

  public String getThumbnailName() {
    return thumbnailName;
  }

  public String getVersionId() {
    return versionId;
  }

  public String getFileId() {
    return fileId;
  }

  public String getStorageType() {
    return storageType;
  }

  public String getShortStorageType() {
    return shortStorageType;
  }

  public boolean isSuccess() {
    return success;
  }
}
