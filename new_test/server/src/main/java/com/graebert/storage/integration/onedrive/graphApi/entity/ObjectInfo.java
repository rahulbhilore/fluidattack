package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ObjectInfo {
  private final boolean isFile;
  private String originalId;
  private String id;

  private String driveId;
  private String wsId;
  // for folder - name, for file - filename
  private String name;
  // for folder - parent, for file - folderId
  private String parent;
  private String parentReferenceId;
  private String parentReferenceDriveId;
  private String parentReferencePath;
  private String owner;
  private String ownerEmail;
  private String ownerId;
  private long creationDate = 0;
  private long updateDate = 0;
  private String changer = null;
  private String changerId = null;
  private String creatorId = null;
  private String size = "0 B";
  private long sizeValue = 0;
  private boolean shared = false;
  private boolean viewOnly = false;
  private boolean canWrite = false;
  private boolean isOwner = true;
  private boolean isDeleted = false;
  private boolean canMove = false;
  private final JsonObject share = new JsonObject()
      .put(Field.VIEWER.getName(), new JsonArray())
      .put(Field.EDITOR.getName(), new JsonArray());
  private boolean externalPublic = false;
  private ObjectPermissions permissions = new ObjectPermissions();
  // public - isPublic
  private boolean isPublic = false;
  private boolean export = false;
  private boolean isTeamDrive = false;

  // file props
  // need to put for file only Field.VERSION_ID.getName() and Field.VER_ID.getName()
  private String versionId;
  private String previewId;
  private String thumbnailName;
  private String thumbnailStatus;
  private String thumbnail;
  private String geomdata;
  private String preview;
  private String sharedLinksId;
  private String link;
  private long linkEndTime;
  private JsonObject publicLinkInfo;

  public ObjectInfo(boolean isFile) {
    this.isFile = isFile;
  }

  public ObjectInfo withOriginalId(String originalId) {
    this.originalId = originalId;
    return this;
  }

  public ObjectInfo withId(String id) {
    this.id = id;
    return this;
  }

  public ObjectInfo withDriveId(String driveId) {
    this.driveId = driveId;
    return this;
  }

  public ObjectInfo withWsId(String wsId) {
    this.wsId = wsId;
    return this;
  }

  public ObjectInfo withName(String name) {
    this.name = name;
    return this;
  }

  public ObjectInfo withParent(String parent) {
    this.parent = parent;
    return this;
  }

  public ObjectInfo withParentReferenceId(String parentReferenceId) {
    this.parentReferenceId = parentReferenceId;
    return this;
  }

  public ObjectInfo withParentReferenceDriveId(String parentReferenceDriveId) {
    this.parentReferenceDriveId = parentReferenceDriveId;
    return this;
  }

  public ObjectInfo withParentReferencePath(String parentReferencePath) {
    this.parentReferencePath = parentReferencePath;
    return this;
  }

  public ObjectInfo withVersionId(String versionId) {
    this.versionId = versionId;
    return this;
  }

  public ObjectInfo withOwner(String owner) {
    this.owner = owner;
    return this;
  }

  public ObjectInfo withOwnerEmail(String ownerEmail) {
    this.ownerEmail = ownerEmail;
    return this;
  }

  public ObjectInfo withOwnerId(String ownerId) {
    this.ownerId = ownerId;
    return this;
  }

  public ObjectInfo withCreationDate(long creationDate) {
    this.creationDate = creationDate;
    return this;
  }

  public ObjectInfo withUpdateDate(long updateDate) {
    this.updateDate = updateDate;
    return this;
  }

  public ObjectInfo withChanger(String changer) {
    this.changer = changer;
    return this;
  }

  public ObjectInfo withChangerId(String changerId) {
    this.changerId = changerId;
    return this;
  }

  public ObjectInfo withSize(long sizeValue) {
    this.sizeValue = sizeValue;
    this.size = Utils.humanReadableByteCount(sizeValue);
    return this;
  }

  public ObjectInfo withShared(boolean shared) {
    this.shared = shared;
    return this;
  }

  public ObjectInfo withViewOnly(boolean viewOnly) {
    this.viewOnly = viewOnly;
    if (viewOnly) shared = true;
    return this;
  }

  public ObjectInfo withCanWrite(boolean canWrite) {
    this.canWrite = canWrite;
    return this;
  }

  public ObjectInfo withIsOwner(boolean isOwner) {
    this.isOwner = isOwner;
    if (!isOwner) shared = true;
    return this;
  }

  public ObjectInfo withIsDeleted(boolean isDeleted) {
    this.isDeleted = isDeleted;
    return this;
  }

  public ObjectInfo withCanMove(boolean canMove) {
    this.canMove = canMove;
    return this;
  }

  public ObjectInfo withCollaborators(JsonArray editors, JsonArray viewers) {
    if (!editors.isEmpty()) {
      this.share.put(Field.EDITOR.getName(), editors);
    }

    if (!viewers.isEmpty()) {
      this.share.put(Field.VIEWER.getName(), viewers);
    }
    return this;
  }

  public ObjectInfo withPermissions(ObjectPermissions permissions) {
    this.permissions = permissions;
    return this;
  }

  public ObjectInfo withIsPublic(boolean isPublic) {
    this.isPublic = isPublic;
    return this;
  }

  public ObjectInfo withExternalPublic(boolean externalPublic) {
    this.externalPublic = externalPublic;
    return this;
  }

  public ObjectInfo isTeamDrive(boolean isTeamDrive) {
    this.isTeamDrive = isTeamDrive;
    return this;
  }

  public ObjectInfo withSharedLinksId(String sharedLinksId) {
    this.sharedLinksId = sharedLinksId;
    return this;
  }

  public ObjectInfo withExport(boolean export) {
    this.export = export;
    return this;
  }

  public ObjectInfo withCreatorId(String creatorId) {
    this.creatorId = creatorId;
    return this;
  }

  public ObjectInfo withThumbnail(String thumbnail) {
    this.thumbnail = thumbnail;
    return this;
  }

  public ObjectInfo withThumbnailName(String thumbnailName) {
    this.thumbnailName = thumbnailName;
    return this;
  }

  public ObjectInfo withThumbnailStatus(String thumbnailStatus) {
    this.thumbnailStatus = thumbnailStatus;
    return this;
  }

  public ObjectInfo withGeomdata(String geomdata) {
    this.geomdata = geomdata;
    return this;
  }

  public ObjectInfo withPreviewId(String previewId) {
    this.previewId = previewId;
    return this;
  }

  public ObjectInfo withPreview(String preview) {
    this.preview = preview;
    return this;
  }

  public ObjectInfo withLink(String link) {
    this.link = link;
    return this;
  }

  public ObjectInfo withLinkEndTime(long linkEndTime) {
    this.linkEndTime = linkEndTime;
    return this;
  }

  public ObjectInfo withPublicLinkInfo(JsonObject publicLinkInfo) {
    this.publicLinkInfo = publicLinkInfo;
    return this;
  }

  public ObjectInfo withIsTeamDrive(boolean isTeamDrive) {
    this.isTeamDrive = isTeamDrive;
    return this;
  }

  public JsonObject toJson() {
    JsonObject object = new JsonObject()
        .put(Field.ENCAPSULATED_ID.getName(), id)
        .put(Field.WS_ID.getName(), wsId)
        .put(isFile ? Field.FILE_NAME.getName() : Field.NAME.getName(), name)
        .put(isFile ? Field.FOLDER_ID.getName() : Field.PARENT.getName(), parent)
        .put(Field.OWNER_ID.getName(), ownerId)
        .put(Field.OWNER.getName(), owner)
        .put(Field.OWNER_EMAIL.getName(), ownerEmail)
        .put(Field.CREATION_DATE.getName(), creationDate)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), changer)
        .put(Field.CHANGER_ID.getName(), changerId)
        .put(Field.SIZE.getName(), size)
        .put(Field.SIZE_VALUE.getName(), sizeValue)
        .put(Field.SHARED.getName(), shared)
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(Field.CAN_MOVE.getName(), isOwner)
        .put(Field.SHARE.getName(), share)
        .put(Field.PUBLIC.getName(), link != null)
        .put(Field.EXTERNAL_PUBLIC.getName(), externalPublic)
        .put(Field.PERMISSIONS.getName(), permissions.toJson());

    if (isFile) {
      if (creatorId != null) {
        object.put(Field.CREATOR_ID.getName(), creatorId);
      }

      if (driveId != null) {
        object.put(Field.DRIVE_ID.getName(), driveId);
      }

      object
          .put(Field.VER_ID.getName(), versionId)
          .put(Field.VERSION_ID.getName(), versionId)
          .put(Field.LINK.getName(), link)
          .put(Field.EXPORT.getName(), export)
          .put(Field.LINK_END_TIME.getName(), linkEndTime)
          .put(Field.PUBLIC_LINK_INFO.getName(), publicLinkInfo)
          .put(Field.PREVIEW_ID.getName(), previewId)
          .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put("thumbnailStatus", thumbnailStatus)
          .put(Field.THUMBNAIL.getName(), thumbnail)
          .put(Field.GEOMDATA.getName(), geomdata)
          .put(Field.PREVIEW.getName(), preview);
    }

    return object;
  }

  public String getSharedLinksId() {
    return sharedLinksId;
  }

  public boolean isViewOnly() {
    return viewOnly;
  }

  public boolean canWrite() {
    return canWrite;
  }

  public String getId() {
    return id;
  }

  public boolean isFile() {
    return isFile;
  }

  public String getOriginalId() {
    return originalId;
  }

  public String getWsId() {
    return wsId;
  }

  public String getName(boolean replaceWindowsChars) {
    if (replaceWindowsChars) {
      String modifiedName = name;

      for (String specialCharacter : GraphUtils.WINDOWS_SPECIAL_CHARACTERS) {
        if (modifiedName.contains(specialCharacter)) {
          modifiedName.replaceAll(specialCharacter, "_");
        }
      }

      return modifiedName;
    }
    return name;
  }

  public String getParent() {
    return parent;
  }

  public String getParentReferenceId() {
    return parentReferenceId;
  }

  public String getParentReferenceDriveId() {
    return parentReferenceDriveId;
  }

  public String getParentReferencePath() {
    return parentReferencePath;
  }

  public String getOwner() {
    return owner;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public long getCreationDate() {
    return creationDate;
  }

  public long getUpdateDate() {
    return updateDate;
  }

  public String getChanger() {
    return changer;
  }

  public String getChangerId() {
    return changerId;
  }

  public String getSize() {
    return size;
  }

  public long getSizeValue() {
    return sizeValue;
  }

  public boolean isShared() {
    return shared;
  }

  public boolean isOwner() {
    return isOwner;
  }

  public boolean isDeleted() {
    return isDeleted;
  }

  public boolean isCanMove() {
    return canMove;
  }

  public JsonObject getShare() {
    return share;
  }

  public boolean isExternalPublic() {
    return externalPublic;
  }

  public ObjectPermissions getPermissions() {
    return permissions;
  }

  public boolean isPublic() {
    return isPublic;
  }

  public boolean isTeamDrive() {
    return isTeamDrive;
  }

  public String getVersionId() {
    return versionId;
  }

  public String getPreviewId() {
    return previewId;
  }

  public String getThumbnailName() {
    return thumbnailName;
  }

  public String getThumbnailStatus() {
    return thumbnailStatus;
  }

  public String getThumbnail() {
    return thumbnail;
  }

  public String getGeomdata() {
    return geomdata;
  }

  public String getPreview() {
    return preview;
  }

  public String getLink() {
    return link;
  }

  public boolean isExport() {
    return export;
  }

  public long getLinkEndTime() {
    return linkEndTime;
  }

  public JsonObject getPublicLinkInfo() {
    return publicLinkInfo;
  }
}
