package com.graebert.storage.integration.hancom.objects;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GridFSModule;
import com.graebert.storage.integration.hancom.HancomAPI;
import com.graebert.storage.thumbnails.JobPrefixes;
import com.graebert.storage.thumbnails.SQSRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public class BaseObject {
  protected JsonObject shareInfo = new JsonObject()
      .put(Field.EDITOR.getName(), new JsonArray())
      .put(Field.VIEWER.getName(), new JsonArray());
  protected boolean isViewOnly = false;
  protected boolean isOwner = true;
  protected String owner;
  protected String ownerEmail;
  protected JsonObject permissions = null;

  public static String normalizeObjectId(String rawId) {
    if (Field.MINUS_1.getName().equals(rawId)) {
      return HancomAPI.ROOT_FOLDER_ID;
    }
    return rawId;
  }

  public static String deNormalizeObjectId(String rawId) {
    if (HancomAPI.ROOT_FOLDER_ID.equals(rawId)) {
      return Field.MINUS_1.getName();
    }
    return rawId;
  }

  public static boolean isFolder(JsonObject objectInfo) {
    return (objectInfo.containsKey("dir") && objectInfo.getBoolean("dir"))
        || (objectInfo.containsKey("isdir") && objectInfo.getBoolean("isdir"));
  }

  /**
   * Converts Hancom's list of object shares into our arrays
   *
   * @param sharingList JsonArray returned from Hancom
   * @return JsonObject -> editor + viewer
   */
  public static JsonObject parseSharingInfoList(JsonArray sharingList) {
    JsonArray editors = new JsonArray();
    JsonArray viewers = new JsonArray();
    sharingList.forEach(collaborator -> {
      JsonObject collaboratorInfo = (JsonObject) collaborator;
      String name = collaboratorInfo.containsKey(Field.NAME.getName())
          ? collaboratorInfo.getString(Field.NAME.getName())
          : collaboratorInfo.getString(Field.EMAIL.getName());
      JsonObject infoToInsert = new JsonObject()
          .put(Field.NAME.getName(), name)
          .put(
              Field.ENCAPSULATED_ID.getName(), collaboratorInfo.getInteger(Field.USER_ID.getName()))
          .put(Field.EMAIL.getName(), collaboratorInfo.getString(Field.EMAIL.getName()));
      boolean isViewOnly = true;
      if (collaboratorInfo.containsKey(Field.PERMISSIONS.getName())) {
        JsonObject permissions = collaboratorInfo.getJsonObject(Field.PERMISSIONS.getName());
        isViewOnly = !permissions.getBoolean("edit") && !permissions.getBoolean("create");
      }
      if (isViewOnly) {
        viewers.add(infoToInsert);
      } else {
        editors.add(infoToInsert);
      }
    });
    return new JsonObject()
        .put(Field.EDITOR.getName(), editors)
        .put(Field.VIEWER.getName(), viewers);
  }

  public static String getPermissionsSet(String role) {
    if (role.equals(GridFSModule.EDITOR)) {
      return "&allow_create=true&allow_delete=true&allow_edit=true&allow_read=true&allow_rename=true&allow_extend=true&recursive=true";
    } else {
      return "&allow_create=false&allow_delete=false&allow_edit=false&allow_read=true&allow_rename=false&allow_extend=false&recursive=true";
    }
  }

  public boolean isShared() {
    return false;
  }

  protected String getObjectId(JsonObject objectInfo) {
    String[] pathIDsArray = this.parsePath(objectInfo, "pathID");
    if (objectInfo.containsKey(Field.ID.getName())) {
      return objectInfo.getString(Field.ID.getName());
    } else if (pathIDsArray.length > 0) {
      return URLDecoder.decode(pathIDsArray[pathIDsArray.length - 1], StandardCharsets.UTF_8);
    }
    return null;
  }

  protected String getOwner(JsonObject objectInfo) {
    if (objectInfo.containsKey(Field.OWNER_NAME.getName())) {
      return objectInfo.getString(Field.OWNER_NAME.getName());
    } else if (objectInfo.containsKey(Field.OWNER_EMAIL.getName())) {
      return objectInfo.getString(Field.OWNER_EMAIL.getName());
    } else if (objectInfo.containsKey("creatorName")) {
      return objectInfo.getString("creatorName");
    } else if (objectInfo.containsKey("creatorEmail")) {
      return objectInfo.getString("creatorEmail");
    }
    return "";
  }

  protected String getOwnerEmail(JsonObject objectInfo) {
    if (objectInfo.containsKey(Field.OWNER_EMAIL.getName())) {
      return objectInfo.getString(Field.OWNER_EMAIL.getName());
    } else if (objectInfo.containsKey("creatorEmail")) {
      return objectInfo.getString("creatorEmail");
    }
    return "";
  }

  private String[] parsePath(JsonObject objectInfo, String field) {
    String[] pathIDsArray = {};
    if (objectInfo.containsKey(field)) {
      pathIDsArray =
          URLDecoder.decode(objectInfo.getString(field), StandardCharsets.UTF_8).split("/");
    }
    return pathIDsArray;
  }

  protected String getParentId(JsonObject objectInfo, String field, String defaultParentId) {
    String[] pathIDsArray = this.parsePath(objectInfo, field);
    String folderId = Field.MINUS_1.getName();
    if (pathIDsArray.length > 1) {
      folderId = pathIDsArray[pathIDsArray.length - 2];
    } else if (Utils.isStringNotNullOrEmpty(defaultParentId)) {
      folderId = defaultParentId;
    }
    return BaseObject.deNormalizeObjectId(folderId);
  }

  protected String getName(JsonObject objectInfo) {
    if (objectInfo.containsKey(Field.NAME.getName())) {
      return objectInfo.getString(Field.NAME.getName());
    } else if (objectInfo.containsKey(Field.PATH.getName())) {
      String[] pathArray = objectInfo.getString(Field.PATH.getName()).split("/");
      return pathArray[pathArray.length - 1];
    }
    return null;
  }

  public void updateShare(JsonArray listOfCollaborators, String accountId) {
    this.shareInfo = BaseObject.parseSharingInfoList(listOfCollaborators);
    int account = Integer.parseInt(accountId);
    for (Object collaboratorObj : listOfCollaborators) {
      JsonObject collaborator = (JsonObject) collaboratorObj;
      if (collaborator.containsKey(Field.USER_ID.getName())
          && collaborator.getInteger(Field.USER_ID.getName()).equals(account)) {
        this.isOwner = false;
        if (collaborator.containsKey(Field.PERMISSIONS.getName())
            && collaborator.getJsonObject(Field.PERMISSIONS.getName()).containsKey("edit")
            && !collaborator.getJsonObject(Field.PERMISSIONS.getName()).getBoolean("edit")) {
          this.isViewOnly = true;
        }
        this.permissions = collaborator.getJsonObject(Field.PERMISSIONS.getName());
        break;
      }
    }
  }

  protected void updateFlagsFromInfo(JsonObject objectInfo) {
    if (objectInfo.containsKey(Field.PERMISSIONS.getName())) {
      if (objectInfo.containsKey(Field.PERMISSIONS.getName())
          && objectInfo.getJsonObject(Field.PERMISSIONS.getName()).containsKey("edit")
          && !objectInfo.getJsonObject(Field.PERMISSIONS.getName()).getBoolean("edit")) {
        this.isViewOnly = true;
        this.isOwner = false;
      }
    }
  }

  public void checkOwnerEmailAgainstUser(String userKnownEmail) {
    if (Utils.isStringNotNullOrEmpty(this.ownerEmail) && !this.ownerEmail.equals(userKnownEmail)) {
      this.isOwner = false;
    }
  }

  public boolean isOwner() {
    return this.isOwner;
  }

  public boolean isViewOnly() {
    return this.isViewOnly;
  }

  protected String getThumbnailStatus(
      String fileId,
      String storageType,
      String versionId,
      boolean force,
      boolean canCreateThumbnails) {
    String fullId = ThumbnailsManager.getFullFileId(fileId, storageType, versionId, false);
    Item request = SQSRequests.findRequest(JobPrefixes.THUMBNAIL.prefix + fullId);
    boolean availableInS3 = ThumbnailsManager.checkThumbnailInS3(fullId, force, false);
    if (request != null && request.hasAttribute(Field.STATUS.getName())) {
      if (request
          .getString(Field.STATUS.getName())
          .equals(ThumbnailsManager.ThumbnailStatus.AVAILABLE.name())) {
        if (availableInS3) {
          return ThumbnailsManager.ThumbnailStatus.AVAILABLE.name();
        }
      } else {
        return request.getString(Field.STATUS.getName());
      }
    }
    if (availableInS3) {
      return ThumbnailsManager.ThumbnailStatus.AVAILABLE.name();
    }

    if (canCreateThumbnails) {
      return ThumbnailsManager.ThumbnailStatus.LOADING.name();
    } else {
      return ThumbnailsManager.ThumbnailStatus.UNAVAILABLE.name();
    }
  }
}
