package com.graebert.storage.integration.onedrive.graphApi.util;

import static com.graebert.storage.integration.BaseStorage.MY_FILES;
import static com.graebert.storage.vertx.DynamoBusModBase.log;

import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.CollaboratorInfo;
import com.graebert.storage.Entities.Collaborators;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionModifier;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onedrive.graphApi.GraphApi;
import com.graebert.storage.integration.onedrive.graphApi.entity.GraphId;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.integration.onedrive.graphApi.exception.WrongNameException;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.xml.bind.DatatypeConverter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kong.unirest.UnirestException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.NonNls;

public class GraphUtils {
  public static final String READ = "read";
  public static final String WRITE = "write";

  @NonNls
  public static final String SHARED_FOLDER = Field.SHARED.getName();

  @NonNls
  public static final String ROOT_FOLDER_ID = Field.MINUS_1.getName();

  @NonNls
  public static final String OWNER = Field.OWNER.getName();

  @NonNls
  public static final String ROOT = Field.ROOT.getName();

  private static final String emptyString = "";
  public static final String invalidDriveId = "8000000000000000";
  public static String ROOT_FOLDER_FAKE_ID = String.valueOf("/drive/root:".hashCode());
  public static final String[] SPECIAL_CHARACTERS = {"<", ">", "\"", "|", ":", "?", "*", "/", "\\"};
  public static final String[] WINDOWS_SPECIAL_CHARACTERS = {
    "<", ">", "\"", "|", ":", "?", "*", "/", "\\"
  };

  public static String getParent(
      JsonObject parentReference,
      boolean isFile,
      boolean isShared,
      JsonObject inheritedFrom,
      StorageType storageType) {
    String[] parsedData = getInfoFromParentReference(parentReference);
    String remoteDriveId = parsedData[0];
    String parentIdFromRemote = parsedData[1];
    // check if file is in shared with me folder
    if (!storageType.equals(StorageType.SHAREPOINT)
        && isShared
        && (!Utils.isStringNotNullOrEmpty(parentIdFromRemote) || inheritedFrom == null)) {
      return Field.SHARED.getName();
    }

    if (isFile) {
      // if file parent is root folder we should mark it.
      String parentReferencePath = parentReference.getString(Field.PATH.getName());
      // check is parent folder is root.
      // https://docs.microsoft.com/ru-ru/onedrive/developer/rest-api/resources/driveitem?view=odsp-graph-online
      if (Utils.isStringNotNullOrEmpty(parentReferencePath)
          && parentReferencePath.endsWith("root:")) {
        parentIdFromRemote = Field.ROOT.getName();
      }
    }
    return remoteDriveId + ":" + parentIdFromRemote;
  }

  public static String generateParent(JsonObject obj, boolean isShared, StorageType storageType) {
    boolean isRemoteItemExist = obj.containsKey(Field.REMOTE_ID.getName());
    boolean isParentReferenceExist = obj.containsKey(Field.PARENT_REFERENCE.getName());
    boolean isFile = obj.containsKey(Field.FILE.getName()); // check if current obj is file
    JsonObject parentReference;
    JsonObject remoteItem;
    String remoteParent = null;
    String referenceParent = null;
    JsonObject inheritedFrom = obj.getJsonObject("inheritedFrom");
    // get all possible data from remote item property if it exists
    if (isRemoteItemExist) {
      remoteItem = obj.getJsonObject(Field.REMOTE_ID.getName());
      boolean doesRemoteItemHaveParentReference =
          remoteItem.containsKey(Field.PARENT_REFERENCE.getName());
      if (doesRemoteItemHaveParentReference) {
        parentReference = remoteItem.getJsonObject(Field.PARENT_REFERENCE.getName());
        remoteParent = getParent(parentReference, isFile, isShared, inheritedFrom, storageType);
      }
    }
    // get all possible data from parentReference property if it exists
    if (isParentReferenceExist) {
      parentReference = obj.getJsonObject(Field.PARENT_REFERENCE.getName());
      referenceParent = getParent(parentReference, isFile, isShared, inheritedFrom, storageType);
    }
    String parent;
    if (Utils.isStringNotNullOrEmpty(referenceParent)) {
      parent = referenceParent;
    } else if (Utils.isStringNotNullOrEmpty(remoteParent)) {
      parent = remoteParent;
    } else {
      parent = Field.MINUS_1.getName();
    }
    return parent;
  }

  public static String generateId(JsonObject ODObjectInfo) {
    boolean isRemoteItemExist = ODObjectInfo.containsKey(Field.REMOTE_ID.getName());
    boolean isParentReferenceExist = ODObjectInfo.containsKey(Field.PARENT_REFERENCE.getName());
    String remoteDriveId = null;
    String parentDriveId = null;
    String objectIdFromRemote = null;
    String objectId = ODObjectInfo.getString(Field.ID.getName());
    JsonObject parentReference;
    JsonObject remoteItem;

    // get all possible data from remote item property if it exists
    if (isRemoteItemExist) {
      remoteItem = ODObjectInfo.getJsonObject(Field.REMOTE_ID.getName());
      boolean doesRemoteItemHaveParentReference =
          remoteItem.containsKey(Field.PARENT_REFERENCE.getName());
      if (doesRemoteItemHaveParentReference) {
        parentReference = remoteItem.getJsonObject(Field.PARENT_REFERENCE.getName());
        String[] parsedData = getInfoFromParentReference(parentReference);
        remoteDriveId = parsedData[0];
      }
      if (remoteItem.containsKey(Field.ID.getName())) {
        objectIdFromRemote = remoteItem.getString(Field.ID.getName());
      }
    }

    // get all possible data from parentReference property if it exists
    if (isParentReferenceExist) {
      parentReference = ODObjectInfo.getJsonObject(Field.PARENT_REFERENCE.getName());
      String[] parsedData = getInfoFromParentReference(parentReference);
      parentDriveId = parsedData[0];
    }

    // provide the best id we can based on received information
    String id;
    if (Utils.isStringNotNullOrEmpty(remoteDriveId)
        && Utils.isStringNotNullOrEmpty(objectIdFromRemote)) {
      id = remoteDriveId + ":" + objectIdFromRemote;
    } else if (Utils.isStringNotNullOrEmpty(parentDriveId)
        && Utils.isStringNotNullOrEmpty(objectId)) {
      id = parentDriveId + ":" + objectId;
    } else {
      log.warn("MS Graph : Generate ID else case for item - " + ODObjectInfo); // WB-157
      id = objectId;
    }
    return id;
  }

  public static String[] getInfoFromParentReference(JsonObject parentReference) {
    String driveId = null;
    String parentId = null;
    boolean doesInnerReferenceHaveDriveId = parentReference.containsKey(Field.DRIVE_ID.getName());
    if (doesInnerReferenceHaveDriveId) {
      driveId = parentReference.getString(Field.DRIVE_ID.getName());
    }
    boolean doesParentReferenceHaveId = parentReference.containsKey(Field.ID.getName());
    if (doesParentReferenceHaveId) {
      parentId = parentReference.getString(Field.ID.getName());
    }
    return new String[] {driveId, parentId};
  }

  public static String getDriveIdFromId(String id) {
    String[] idParts = id.split(":");
    if (idParts.length == 2) {
      return idParts[0];
    }
    return null;
  }

  public static boolean isRootMainFolder(String folderId) {
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      return folderId.equals(Field.MINUS_1.getName());
    }
    return false;
  }

  public static boolean isRootFakeFolder(String folderId) {
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      return folderId.equals(ROOT_FOLDER_FAKE_ID);
    }
    return false;
  }

  public static boolean isRootFolder(String folderId) {
    return isRootMainFolder(folderId) || isRootFakeFolder(folderId);
  }

  public static boolean isSharedWithMeFolder(String folderId) {
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      return folderId.equals(SHARED_FOLDER);
    }
    return false;
  }

  public static String getVersionId(JsonObject obj) {
    if (obj.containsKey("cTag")) {
      LogManager.getRootLogger().info("OD VERSION: " + obj.getString("cTag"));
      return DigestUtils.sha3_256Hex(obj.getString("cTag")).toUpperCase();
    }
    // it can also have eTag
    if (obj.containsKey("eTag")) {
      LogManager.getRootLogger().info("OD VERSION: " + obj.getString("eTag"));
      return DigestUtils.sha3_256Hex(obj.getString("eTag")).toUpperCase();
    }
    LogManager.getRootLogger().info("OD VERSION: null!");
    return null;
  }

  public static long getCreationDate(String creationDate) {
    return DatatypeConverter.parseDateTime(creationDate).getTime().getTime();
  }

  public static VersionInfo getVersionInfo(
      ServerConfig config,
      StorageType storageType,
      JsonObject versionInfoObj,
      String fileId,
      String externalId,
      boolean current) {
    VersionModifier versionModifier = new VersionModifier();
    try {
      if (versionInfoObj.containsKey(Field.LAST_MODIFIED_BY.getName())
          && versionInfoObj
              .getJsonObject(Field.LAST_MODIFIED_BY.getName())
              .containsKey(Field.USER.getName())) {
        JsonObject userInfo = versionInfoObj
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .getJsonObject(Field.USER.getName());
        versionModifier.setId(userInfo.getString(Field.ID.getName()));
        versionModifier.setEmail(userInfo.getString(Field.EMAIL.getName()));
        versionModifier.setName(userInfo.getString(Field.DISPLAY_NAME.getName()));
        versionModifier.setCurrentUser(externalId.equals(userInfo.getString(Field.ID.getName())));
      }
    } catch (Exception ignored) {
      // we don't care that much about modifier
    }
    String versionId = versionInfoObj.getString(Field.ID.getName());

    long creationDate =
        getCreationDate(versionInfoObj.getString(Field.LAST_MODIFIED_DATE_TIME.getName()));
    if (current && versionId.equals(Field.CURRENT.getName())) {
      versionId = Field.CUR.getName() + creationDate;
    }

    VersionInfo versionInfo = new VersionInfo(versionId, creationDate, null);
    versionInfo.setSize(versionInfoObj.getLong(Field.SIZE.getName()));
    versionInfo.setModifier(versionModifier);
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setDownloadable(true);
    versionPermissions.setCanPromote(!current);
    versionPermissions.setCanDelete(!current);
    versionPermissions.setCanDelete(false);
    versionInfo.setPermissions(versionPermissions);
    try {
      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, fileId, versionId);
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public static ObjectInfo getFileJson(
      ServerConfig config,
      GraphApi graphApi,
      JsonObject obj,
      boolean isFolder,
      boolean full,
      String externalId,
      String originalId,
      boolean addShare,
      StorageType storageType,
      boolean isAdmin,
      String userId,
      Message<JsonObject> message) {
    JsonObject parentReference = obj.containsKey(Field.PARENT_REFERENCE.getName())
        ? obj.getJsonObject(Field.PARENT_REFERENCE.getName(), new JsonObject())
        : obj.containsKey(Field.REMOTE_ID.getName())
            ? obj.getJsonObject(Field.REMOTE_ID.getName(), new JsonObject())
                .getJsonObject(Field.PARENT_REFERENCE.getName(), new JsonObject())
            : new JsonObject();

    long updateDate = 0L;
    String changer = emptyString, changerId = emptyString;
    String id = generateId(obj);

    // XENON-34441 - updating id for invalid driveId in parentReference (8000000000000000)
    if (id.contains(invalidDriveId)) {
      id = id.replace(invalidDriveId, externalId);
    }

    GraphId ids = new GraphId(id);
    String driveId = ids.getDriveId();
    String pureId = ids.getId();

    String verId = getVersionId(obj);
    if (full) {
      try {
        updateDate = getCreationDate(obj.getString(Field.LAST_MODIFIED_DATE_TIME.getName()));
        changer = (obj.containsKey(Field.REMOTE_ID.getName())
                ? obj.getJsonObject(Field.REMOTE_ID.getName())
                : obj)
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .getJsonObject(Field.USER.getName())
            .getString(Field.DISPLAY_NAME.getName());
        changerId = (obj.containsKey(Field.REMOTE_ID.getName())
                ? obj.getJsonObject(Field.REMOTE_ID.getName())
                : obj)
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .getJsonObject(Field.USER.getName())
            .getString(Field.ID.getName());
      } catch (Exception ignore) {
      }
    }

    boolean shared = obj.containsKey(Field.SHARED.getName())
        || obj.containsKey(Field.REMOTE_ID.getName())
            && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.SHARED.getName());
    boolean canWrite = obj.containsKey(Field.PERMISSION.getName())
        && obj.getJsonArray(Field.PERMISSION.getName()).contains(WRITE);
    JsonArray editor, viewer;
    if (full && addShare) {
      List<CollaboratorInfo> collaboratorInfoList =
          getODItemCollaborators(graphApi, driveId, ids.getId(), storageType);
      Collaborators collaborators = new Collaborators(collaboratorInfoList);
      editor = Collaborators.toJsonArray(collaborators.editor);
      viewer = Collaborators.toJsonArray(collaborators.viewer);
    } else {
      editor = new JsonArray();
      viewer = new JsonArray();
    }

    boolean viewOnly = false;
    for (int i = 0; i < viewer.size(); ++i) {
      JsonObject rec = viewer.getJsonObject(i);
      if (rec.getString(Field.USER_ID.getName()).equalsIgnoreCase(externalId)) {
        viewOnly = true;
        break;
      }
    }

    long creationDate = 0;
    try {
      creationDate = DatatypeConverter.parseDateTime /* sdf.parse */(
              obj.getString("createdDateTime"))
          .getTime()
          .getTime();
    } catch (Exception ignore) {
    }

    boolean isOwner = false;
    String owner = emptyString, ownerId = emptyString, ownerEmail = emptyString;
    try {
      JsonObject user = null;
      if (obj.containsKey(Field.CREATED_BY.getName())) {
        user = obj.getJsonObject(Field.CREATED_BY.getName()).getJsonObject(Field.USER.getName());
      } else if (obj.containsKey(Field.REMOTE_ID.getName())
          && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.CREATED_BY.getName())) {
        user = obj.getJsonObject(Field.REMOTE_ID.getName())
            .getJsonObject(Field.CREATED_BY.getName())
            .getJsonObject(Field.USER.getName());
      }
      if (Objects.nonNull(user)) {
        ownerId = user.getString(Field.ID.getName());
        owner = user.getString(Field.DISPLAY_NAME.getName());
        if (user.containsKey(Field.EMAIL.getName())) {
          ownerEmail = user.getString(Field.EMAIL.getName());
        }
      }
      isOwner = ownerId.equals(externalId)
          || externalId.contains(ownerId)
          || ownerId.contains(externalId);
    } catch (Exception ignore) {
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (!isFolder && full && addShare) {
      final String sharedLinkFileId = obj.getString(Field.SHARED_LINKS_ID.getName());
      PLData = BaseStorage.findLinkForFile(sharedLinkFileId, externalId, userId, viewOnly);
    }

    // parsing inheritedFrom tag for shared files
    try {
      if (shared && !isOwner) {
        JsonArray permissions =
            graphApi.getPermissionsForObject(driveId, obj.getString(Field.ID.getName()));
        obj.put("inheritedFrom", permissions.getJsonObject(0).getJsonObject("inheritedFrom"));
      }
    } catch (Exception ignore) {
    }

    // verify that collaborator is in shared object (WB-1583)
    if (addShare && !isOwner && shared) {
      // at this point we iterated viewer and didn't find user in there
      // so now iterate editor
      if (!viewOnly) {
        boolean isFoundInCollaborators = false;
        for (int i = 0; i < editor.size(); ++i) {
          JsonObject rec = editor.getJsonObject(i);
          if (rec.getString(Field.USER_ID.getName()).equalsIgnoreCase(externalId)) {
            isFoundInCollaborators = true;
            break;
          }
        }
        if (!isFoundInCollaborators) {
          // apparently this is view only
          viewOnly = true;
          String name = graphApi.getDisplayName();
          if (name.isBlank()) {
            name = graphApi.getEmail();
          }
          viewer.add(new CollaboratorInfo(
                  graphApi.getExternalId(),
                  name,
                  graphApi.getEmail(),
                  graphApi.getUserId(),
                  READ,
                  Role.VIEWER)
              .toJson()
              .put(Field.CAN_DELETE.getName(), false));
        }
      }
    }

    // same for parent
    // not always possible - see https://graebert.atlassian.net/browse/XENON-31216
    // if some ids are null - let's assume it's inside Field.ROOT.getName() folder,
    // this will have the best possible consequences for UI
    String parent = generateParent(obj, shared && !isOwner, storageType);
    String parentReferenceId = parentReference.getString(Field.ID.getName(), null);
    String parentReferenceDriveId = parentReference.getString(Field.DRIVE_ID.getName(), null);
    String parentReferencePath = parentReference.getString(Field.PATH.getName(), null);

    int size = obj.containsKey(Field.SIZE.getName())
        ? obj.getInteger(Field.SIZE.getName())
        : obj.containsKey(Field.REMOTE_ID.getName())
                && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.SIZE.getName())
            ? obj.getJsonObject(Field.REMOTE_ID.getName()).getInteger(Field.SIZE.getName())
            : 0;

    if (!isOwner && shared && parent.equals(Field.MINUS_1.getName())) {
      parent = SHARED_FOLDER;
    } else if (parent.endsWith("null") && Utils.isStringNotNullOrEmpty(driveId)) {
      // root folder fix
      parent = driveId + ":root";
    }

    boolean isPublic = PLData.getBoolean(Field.IS_PUBLIC.getName());
    String name = obj.getString(Field.NAME.getName());
    if (Utils.isStringNotNullOrEmpty(name)) {
      if (name.equalsIgnoreCase(Field.ROOT.getName())) {
        // https://graebert.atlassian.net/browse/XENON-66995
        name = Utils.getLocalizedString(message, Field.MY_FILES_FOLDER.getName());
      }
    } else {
      name = Utils.getLocalizedString(message, Field.UNKNOWN.getName());
    }

    ObjectInfo objectInfo = new ObjectInfo(!isFolder)
        .withId(Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
        .withWsId(id)
        .withOriginalId(originalId)
        .withName(name)
        .withParent(Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parent))
        .withParentReferenceId(parentReferenceId)
        .withParentReferenceDriveId(parentReferenceDriveId)
        .withParentReferencePath(parentReferencePath)
        .withOwner(owner)
        .withOwnerEmail(ownerEmail)
        .withOwnerId(ownerId)
        .withIsOwner(isOwner)
        .withCreationDate(creationDate)
        .withUpdateDate(updateDate)
        .withChanger(changer)
        .withChangerId(changerId)
        .withSize(size)
        .withShared(shared)
        .withViewOnly(viewOnly)
        .withCanWrite(canWrite)
        .withCanMove(isOwner)
        .withCollaborators(editor, viewer)
        .withIsPublic(isPublic)
        .withExternalPublic(true);

    if (isPublic) {
      objectInfo
          .withLink(PLData.getString(Field.LINK.getName()))
          .withExport(PLData.getBoolean(Field.EXPORT.getName()))
          .withLinkEndTime(PLData.getLong(Field.LINK_END_TIME.getName()))
          .withPublicLinkInfo(PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }

    if (!isFolder) {
      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, id, verId);
      String previewId = ThumbnailsManager.getPreviewName(
          StorageType.getShort(storageType), obj.getString(Field.ID.getName()));

      objectInfo.withPreviewId(previewId).withThumbnailName(thumbnailName);

      if (Extensions.isThumbnailExt(config, obj.getString(Field.NAME.getName()), isAdmin)) {
        // AS : Removing this temporarily until we have some server cache (WB-1248)
        // .withThumbnailStatus(ThumbnailsManager.getThumbnailStatus(id, storageType.name(),
        // verId, force, canCreateThumbnails))
        objectInfo
            .withThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .withGeomdata(ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
            .withPreview(ThumbnailsManager.getPreviewURL(config, previewId, true));
      }
      if (Utils.isStringNotNullOrEmpty(verId)) {
        FileMetaData.putMetaData(
            pureId, storageType, obj.getString(Field.NAME.getName()), thumbnailName, verId, null);

        objectInfo.withVersionId(verId);
      }
    }

    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setPermissionAccess(AccessType.canManagePermissions, !viewOnly || isOwner)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isFolder)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isFolder)
        .setPermissionAccess(AccessType.canRename, !viewOnly && isOwner && !shared)
        .setPermissionAccess(AccessType.canMove, !viewOnly && isOwner && !shared)
        .setPermissionAccess(AccessType.canClone, isOwner && !shared);

    if (!isFolder) {
      permissions.setBatchTo(
          List.of(
              AccessType.canMoveFrom,
              AccessType.canMoveTo,
              AccessType.canCreateFiles,
              AccessType.canCreateFolders),
          false);
    }

    try {
      if (Utils.isStringNotNullOrEmpty(originalId)
          && isFolder
          && new GraphId(originalId).getId().equals(Field.ROOT.getName())) {
        objectInfo
            .withId(
                Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, originalId))
            .withWsId(originalId);
      }
    } catch (Exception ignore) {
    }

    return objectInfo.withPermissions(permissions);
  }

  public static String generateSPId(JsonObject ODObjectInfo) {
    boolean isRemoteItemExist = ODObjectInfo.containsKey(Field.REMOTE_ID.getName());
    boolean isParentReferenceExist = ODObjectInfo.containsKey(Field.PARENT_REFERENCE.getName());
    JsonObject parentReference;
    JsonObject remoteItem;
    String remoteDriveId = null;
    String parentDriveId = null;
    String objectIdFromRemote = null;
    String objectId = ODObjectInfo.getString(Field.ID.getName());

    // get all possible data from remote item property if it exists
    if (isRemoteItemExist) {
      remoteItem = ODObjectInfo.getJsonObject(Field.REMOTE_ID.getName());
      boolean doesRemoteItemHaveParentReference =
          remoteItem.containsKey(Field.PARENT_REFERENCE.getName());
      if (doesRemoteItemHaveParentReference) {
        parentReference = remoteItem.getJsonObject(Field.PARENT_REFERENCE.getName());
        String[] parsedData = getInfoFromParentReference(parentReference);
        remoteDriveId = parsedData[0];
      }
      if (remoteItem.containsKey(Field.ID.getName())) {
        objectIdFromRemote = remoteItem.getString(Field.ID.getName());
      }
    }

    // get all possible data from parentReference property if it exists
    if (isParentReferenceExist) {
      parentReference = ODObjectInfo.getJsonObject(Field.PARENT_REFERENCE.getName());
      String[] parsedData = getInfoFromParentReference(parentReference);
      parentDriveId = parsedData[0];
    }

    // provide the best id we can based on received information
    String id;
    if (Utils.isStringNotNullOrEmpty(remoteDriveId)
        && Utils.isStringNotNullOrEmpty(objectIdFromRemote)) {
      id = remoteDriveId + ":" + objectIdFromRemote;
    } else if (Utils.isStringNotNullOrEmpty(parentDriveId)
        && Utils.isStringNotNullOrEmpty(objectId)) {
      id = parentDriveId + ":" + objectId;
    } else {
      id = objectId + ":" + Field.ROOT.getName();
    }
    return id;
  }

  public static ObjectInfo getSPFileJson(
      ServerConfig config,
      GraphApi graphApi,
      JsonObject obj,
      boolean isFolder,
      boolean full,
      String externalId,
      String originalId,
      boolean addShare,
      StorageType storageType,
      boolean isAdmin,
      String userId,
      boolean force,
      boolean canCreateThumbnails)
      throws UnirestException {
    JsonObject parentReference = obj.containsKey(Field.PARENT_REFERENCE.getName())
        ? obj.getJsonObject(Field.PARENT_REFERENCE.getName(), new JsonObject())
        : obj.containsKey(Field.REMOTE_ID.getName())
            ? obj.getJsonObject(Field.REMOTE_ID.getName(), new JsonObject())
                .getJsonObject(Field.PARENT_REFERENCE.getName(), new JsonObject())
            : new JsonObject();

    long updateDate = 0L;
    String changer = emptyString, changerId = emptyString;
    String id = generateSPId(obj);
    String driveId = null;
    if (obj.containsKey(Field.REMOTE_ID.getName())
        && obj.getJsonObject(Field.REMOTE_ID.getName())
            .containsKey(Field.PARENT_REFERENCE.getName())
        && obj.getJsonObject(Field.REMOTE_ID.getName())
            .getJsonObject(Field.PARENT_REFERENCE.getName())
            .containsKey(Field.DRIVE_ID.getName())) {
      driveId = obj.getJsonObject(Field.REMOTE_ID.getName())
          .getJsonObject(Field.PARENT_REFERENCE.getName())
          .getString(Field.DRIVE_ID.getName());
    }
    if (driveId == null
        && obj.containsKey(Field.PARENT_REFERENCE.getName())
        && obj.getJsonObject(Field.PARENT_REFERENCE.getName())
            .containsKey(Field.DRIVE_ID.getName())) {
      driveId =
          obj.getJsonObject(Field.PARENT_REFERENCE.getName()).getString(Field.DRIVE_ID.getName());
    }
    String[] strings = id.split(":");
    String pureId = strings[0];
    if (strings.length == 2) {
      driveId = strings[0];
      pureId = strings[1];
    }
    String verId = getVersionId(obj);
    if (full) {
      try {
        updateDate = getCreationDate(obj.getString(Field.LAST_MODIFIED_DATE_TIME.getName()));
        changer = (obj.containsKey(Field.REMOTE_ID.getName())
                ? obj.getJsonObject(Field.REMOTE_ID.getName())
                : obj)
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .getJsonObject(Field.USER.getName())
            .getString(Field.DISPLAY_NAME.getName());
        changerId = (obj.containsKey(Field.REMOTE_ID.getName())
                ? obj.getJsonObject(Field.REMOTE_ID.getName())
                : obj)
            .getJsonObject(Field.LAST_MODIFIED_BY.getName())
            .getJsonObject(Field.USER.getName())
            .getString(Field.ID.getName());
      } catch (Exception ignore) {
      }
    }
    JsonArray editor, viewer;
    boolean shared = obj.containsKey(Field.SHARED.getName())
        || obj.containsKey(Field.REMOTE_ID.getName())
            && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.SHARED.getName());
    if (full && addShare) {
      List<CollaboratorInfo> collaboratorInfoList =
          getSPItemCollaborators(graphApi, driveId, pureId);
      Collaborators collaborators = new Collaborators(collaboratorInfoList);
      editor = Collaborators.toJsonArray(collaborators.editor);
      viewer = Collaborators.toJsonArray(collaborators.viewer);
    } else {
      editor = new JsonArray();
      viewer = new JsonArray();
    }

    boolean viewOnly = false;
    for (int i = 0; i < viewer.size(); ++i) {
      JsonObject rec = viewer.getJsonObject(i);
      if (rec.getString(Field.USER_ID.getName()).equalsIgnoreCase(externalId)) {
        viewOnly = true;
        break;
      }
    }

    long creationDate = 0;
    try {
      creationDate = DatatypeConverter.parseDateTime /* sdf.parse */(
              obj.getString("createdDateTime"))
          .getTime()
          .getTime();
    } catch (Exception ignore) {
    }
    boolean canWrite = obj.containsKey(Field.PERMISSION.getName())
        && obj.getJsonArray(Field.PERMISSION.getName()).contains(WRITE);
    boolean isOwner = false;
    String owner = emptyString, ownerId = emptyString, ownerEmail = emptyString;
    try {
      JsonObject user = null;
      if (obj.containsKey(Field.CREATED_BY.getName())) {
        user = obj.getJsonObject(Field.CREATED_BY.getName()).getJsonObject(Field.USER.getName());
      } else if (obj.containsKey(Field.REMOTE_ID.getName())
          && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.CREATED_BY.getName())) {
        user = obj.getJsonObject(Field.REMOTE_ID.getName())
            .getJsonObject(Field.CREATED_BY.getName())
            .getJsonObject(Field.USER.getName());
      }
      if (Objects.nonNull(user)) {
        ownerId = user.getString(Field.ID.getName());
        owner = user.getString(Field.DISPLAY_NAME.getName());
        if (user.containsKey(Field.EMAIL.getName())) {
          ownerEmail = user.getString(Field.EMAIL.getName());
        }
      }
      isOwner = ownerId.equals(externalId.substring(3))
          || externalId.substring(3).contains(ownerId)
          || ownerId.contains(externalId.substring(3));
    } catch (Exception ignore) {
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (!isFolder && full && addShare) {
      final String sharedLinkFileId = obj.getString(Field.SHARED_LINKS_ID.getName());
      PLData = BaseStorage.findLinkForFile(sharedLinkFileId, externalId, userId, viewOnly);
    }

    // same for parent
    // not always possible - see https://graebert.atlassian.net/browse/XENON-31216
    // if some ids are null - let's assume it's inside Field.ROOT.getName() folder,
    // this will have the best possible consequences for UI
    String parent = generateParent(obj, shared && !isOwner, storageType);
    String parentReferenceId = parentReference.getString(Field.ID.getName(), null);
    String parentReferenceDriveId = parentReference.getString(Field.DRIVE_ID.getName(), null);
    String parentReferencePath = parentReference.getString(Field.PATH.getName(), null);

    int size = obj.containsKey(Field.SIZE.getName())
        ? obj.getInteger(Field.SIZE.getName())
        : obj.containsKey(Field.REMOTE_ID.getName())
                && obj.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.SIZE.getName())
            ? obj.getJsonObject(Field.REMOTE_ID.getName()).getInteger(Field.SIZE.getName())
            : 0;
    if (parent.endsWith("null") && Utils.isStringNotNullOrEmpty(driveId)) {
      // root folder fix
      parent = driveId + ":root";
    }

    boolean isPublic = PLData.getBoolean(Field.IS_PUBLIC.getName());

    ObjectInfo objectInfo = new ObjectInfo(!isFolder)
        .withId(Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
        .withWsId(id)
        .withOriginalId(originalId)
        .withName(obj.getString(Field.NAME.getName()))
        .withParent(Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parent))
        .withParentReferenceId(parentReferenceId)
        .withParentReferenceDriveId(parentReferenceDriveId)
        .withParentReferencePath(parentReferencePath)
        .withOwner(owner)
        .withOwnerEmail(ownerEmail)
        .withOwnerId(ownerId)
        .withIsOwner(isOwner)
        .withCreationDate(creationDate)
        .withUpdateDate(updateDate)
        .withChanger(changer)
        .withChangerId(changerId)
        .withSize(size)
        .withShared(shared)
        .withViewOnly(viewOnly)
        .withCanWrite(canWrite)
        .withCanMove(isOwner)
        .withCollaborators(editor, viewer)
        .withIsPublic(isPublic)
        .withExternalPublic(true);

    if (Utils.isStringNotNullOrEmpty(driveId)) {
      objectInfo.withDriveId(driveId);
    }

    if (isPublic) {
      objectInfo
          .withLink(PLData.getString(Field.LINK.getName()))
          .withExport(PLData.getBoolean(Field.EXPORT.getName()))
          .withLinkEndTime(PLData.getLong(Field.LINK_END_TIME.getName()))
          .withPublicLinkInfo(PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }

    if (!isFolder) {
      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, id, verId);
      String previewId = ThumbnailsManager.getPreviewName(
          StorageType.getShort(storageType), obj.getString(Field.ID.getName()));

      objectInfo.withPreviewId(previewId).withThumbnailName(thumbnailName);

      if (Extensions.isThumbnailExt(config, obj.getString(Field.NAME.getName()), isAdmin)) {
        // AS : Removing this temporarily until we have some server cache (WB-1248)
        // .withThumbnailStatus(ThumbnailsManager.getThumbnailStatus(id, storageType.name(),
        // verId, force, canCreateThumbnails))
        objectInfo
            .withThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .withGeomdata(ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
            .withPreview(ThumbnailsManager.getPreviewURL(config, previewId, true));
      }
      if (Utils.isStringNotNullOrEmpty(verId)) {
        FileMetaData.putMetaData(
            pureId, storageType, obj.getString(Field.NAME.getName()), thumbnailName, verId, null);

        objectInfo.withVersionId(verId);
      }
    }

    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setPermissionAccess(AccessType.canManagePermissions, !viewOnly || isOwner)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isFolder)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isFolder);

    if (!isFolder) {
      permissions.setBatchTo(
          List.of(
              AccessType.canMoveFrom,
              AccessType.canMoveTo,
              AccessType.canCreateFiles,
              AccessType.canCreateFolders),
          false);
    }

    return objectInfo.withPermissions(permissions);
  }

  public static ObjectInfo getRootFolderInfo(
      final StorageType storageType,
      final String externalId,
      final String id,
      final String myFilesFolderName) {
    return new ObjectInfo(false)
        .withId(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, id != null ? id : ROOT_FOLDER_ID))
        .withWsId(id != null ? id : ROOT_FOLDER_ID)
        .withName(Utils.isStringNotNullOrEmpty(myFilesFolderName) ? myFilesFolderName : MY_FILES)
        .withParent(
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, ROOT_FOLDER_ID))
        .withOwner(emptyString)
        .withOwnerEmail(emptyString)
        .withCreationDate(0)
        .withUpdateDate(0)
        .withChanger(emptyString)
        .withSize(0)
        .withShared(false)
        .withViewOnly(false)
        .withIsDeleted(false)
        .withCanMove(false)
        .withCollaborators(new JsonArray(), new JsonArray())
        .withExternalPublic(false)
        .withIsTeamDrive(false)
        .withPermissions(new ObjectPermissions().setPermissionAccess(AccessType.canMoveFrom, true))
        .withIsPublic(false);
  }

  public static ObjectInfo getSharedFolderInfo(
      final StorageType storageType, final String externalId, final String sharedFolderName) {
    return new ObjectInfo(false)
        .withId(
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, SHARED_FOLDER))
        .withWsId(SHARED_FOLDER)
        .withName(sharedFolderName)
        .withParent(
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, ROOT_FOLDER_ID))
        .withOwner(emptyString)
        .withOwnerEmail(emptyString)
        .withCreationDate(0)
        .withUpdateDate(0)
        .withChanger(emptyString)
        .withSize(0)
        .withShared(true)
        .withViewOnly(true)
        .withIsDeleted(false)
        .withCanMove(false)
        .withCollaborators(new JsonArray(), new JsonArray())
        .withExternalPublic(false)
        .withIsTeamDrive(false)
        .withPermissions(new ObjectPermissions())
        .withIsPublic(false);
  }

  public static ObjectInfo getRootFakeFolderInfo(
      final StorageType storageType, final String externalId, final String myFilesName) {
    return new ObjectInfo(false)
        .withId(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, ROOT_FOLDER_FAKE_ID))
        .withWsId(ROOT_FOLDER_FAKE_ID)
        .withName(myFilesName)
        .withParent(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .withOwner(emptyString)
        .withOwnerEmail(emptyString)
        .withCreationDate(0)
        .withUpdateDate(0)
        .withChanger(emptyString)
        .withSize(0)
        .withShared(false)
        .withViewOnly(false)
        .withIsOwner(true)
        .withIsDeleted(false)
        .withCanMove(false)
        .withCollaborators(new JsonArray(), new JsonArray())
        .withExternalPublic(false)
        .withIsTeamDrive(false)
        .withIsPublic(false);
  }

  public static List<CollaboratorInfo> getODItemCollaborators(
      GraphApi graphApi, String driveId, String objectId, StorageType storageType) {
    JsonArray body = new JsonArray();
    try {
      body = graphApi.getPermissionsForObject(driveId, objectId);
    } catch (Exception ignore) {
    }
    List<CollaboratorInfo> collaboratorInfoList = new ArrayList<>();
    if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
      body.forEach(p -> {
        JsonObject permission = (JsonObject) p;
        if (permission.containsKey(Field.ROLES.getName())
            && !permission.getJsonArray(Field.ROLES.getName()).contains(OWNER)) {
          if (permission.containsKey("grantedToV2")) {
            JsonObject userObj = permission.getJsonObject("grantedToV2");
            if (userObj.containsKey(Field.USER.getName())
                && Utils.isJsonObjectNotNullOrEmpty(userObj.getJsonObject(Field.USER.getName()))
                && userObj.getJsonObject(Field.USER.getName()).containsKey(Field.EMAIL.getName())) {
              JsonObject user = userObj.getJsonObject(Field.USER.getName());
              String name = user.getString(Field.DISPLAY_NAME.getName(), emptyString);
              if (name.isEmpty()) {
                name = user.getString(Field.EMAIL.getName());
              }
              String storageAccessRole = getRoleFromPermission(permission);
              if (Objects.nonNull(storageAccessRole)) {
                Role role = WRITE.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
                CollaboratorInfo info = new CollaboratorInfo(
                    permission.getString(Field.ID.getName()),
                    name,
                    user.getString(Field.EMAIL.getName()),
                    user.getString(Field.ID.getName()),
                    storageAccessRole,
                    role);
                collaboratorInfoList.add(info);
              }
            }
          } else if (permission.containsKey("grantedToIdentitiesV2")) {
            JsonArray users = permission.getJsonArray("grantedToIdentitiesV2");
            users.forEach(u -> {
              JsonObject userObj = (JsonObject) u;
              if (userObj.containsKey(Field.USER.getName())
                  && Utils.isJsonObjectNotNullOrEmpty(userObj.getJsonObject(Field.USER.getName()))
                  && userObj
                      .getJsonObject(Field.USER.getName())
                      .containsKey(Field.EMAIL.getName())) {
                JsonObject user = userObj.getJsonObject(Field.USER.getName());
                String name = user.getString(Field.DISPLAY_NAME.getName(), emptyString);
                if (name.isEmpty()) {
                  name = user.getString(Field.EMAIL.getName());
                }
                String storageAccessRole = getRoleFromPermission(permission);
                if (Objects.nonNull(storageAccessRole)) {
                  Role role = WRITE.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
                  CollaboratorInfo info = new CollaboratorInfo(
                      permission.getString(Field.ID.getName()),
                      name,
                      user.getString(Field.EMAIL.getName()),
                      emptyString,
                      storageAccessRole,
                      role);
                  collaboratorInfoList.add(info);
                }
              }
            });
          }
        }
      });
      List<CollaboratorInfo> viewersToRemove = new ArrayList<>();
      for (CollaboratorInfo info : collaboratorInfoList) {
        if (info.storageAccessRole.equals(READ)
            && collaboratorInfoList.stream()
                .anyMatch(e -> e.storageAccessRole.equals(WRITE) && e.email.equals(info.email))) {
          viewersToRemove.add(info);
        }
      }
      collaboratorInfoList.removeAll(viewersToRemove);
    } else {
      body.forEach(o -> {
        JsonObject permission = (JsonObject) o;
        String name = null, email = null;
        if (permission.containsKey(Field.GRANTED_TO.getName())
            && permission
                .getJsonObject(Field.GRANTED_TO.getName())
                .containsKey(Field.USER.getName())) {
          name = permission
              .getJsonObject(Field.GRANTED_TO.getName())
              .getJsonObject(Field.USER.getName())
              .getString(Field.DISPLAY_NAME.getName());
        }
        if (permission.containsKey("invitation")
            && permission.getJsonObject("invitation").containsKey(Field.EMAIL.getName())) {
          email = permission.getJsonObject("invitation").getString(Field.EMAIL.getName());
          if (name == null) {
            name = email;
          }
        }
        if (name == null) { // ignore permissions without both name and email
          return;
        }
        String collaboratorId = emptyString;
        if (permission.containsKey(Field.GRANTED_TO.getName())) {
          if (permission
              .getJsonObject(Field.GRANTED_TO.getName())
              .containsKey(Field.USER.getName())) {
            collaboratorId = permission
                .getJsonObject(Field.GRANTED_TO.getName())
                .getJsonObject(Field.USER.getName())
                .getString(Field.ID.getName(), emptyString);
          }
        }
        String storageAccessRole = getRoleFromPermission(permission);
        if (Objects.nonNull(storageAccessRole)) {
          Role role = WRITE.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
          CollaboratorInfo info = new CollaboratorInfo(
              permission.getString(Field.ID.getName()),
              name,
              email,
              collaboratorId,
              storageAccessRole,
              role);
          collaboratorInfoList.add(info);
        }
      });
    }
    return collaboratorInfoList;
  }

  public static List<CollaboratorInfo> getSPItemCollaborators(
      GraphApi graphApi, String driveId, String objectId) {
    JsonArray body = new JsonArray();
    try {
      body = graphApi.getPermissionsForObject(driveId, objectId);
    } catch (Exception ignore) {
    }
    List<CollaboratorInfo> collaboratorInfoList = new ArrayList<>();
    body.forEach(o -> {
      JsonObject permission = (JsonObject) o;
      if (permission.containsKey("grantedToIdentities")) {
        JsonArray usersWithAccess = permission.getJsonArray("grantedToIdentities");
        usersWithAccess.forEach(user -> {
          JsonObject userObject = (JsonObject) user;
          if (userObject.containsKey(Field.USER.getName())) {
            String name = userObject
                .getJsonObject(Field.USER.getName())
                .getString(Field.DISPLAY_NAME.getName());
            String email =
                userObject.getJsonObject(Field.USER.getName()).getString(Field.EMAIL.getName());
            // sharepoint groups shares to "outsiders" into a single entry
            // because we rely on _id on UI - add email for each entry

            String storageAccessRole = getRoleFromPermission(permission);
            if (Objects.nonNull(storageAccessRole)) {
              String id = permission.getString(Field.ID.getName()) + "#" + email;
              Role role = WRITE.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
              CollaboratorInfo info =
                  new CollaboratorInfo(id, name, email, email, storageAccessRole, role);
              collaboratorInfoList.add(info);
            }
          }
        });
      } else {
        String name = null, email = null;
        JsonObject grantedTo = permission.containsKey(Field.GRANTED_TO.getName())
            ? permission.getJsonObject(Field.GRANTED_TO.getName())
            : null;
        if (grantedTo != null && grantedTo.containsKey(Field.USER.getName())) {
          name =
              grantedTo.getJsonObject(Field.USER.getName()).getString(Field.DISPLAY_NAME.getName());
        }
        if (permission.containsKey("invitation")
            && permission.getJsonObject("invitation").containsKey(Field.EMAIL.getName())) {
          email = permission.getJsonObject("invitation").getString(Field.EMAIL.getName());
          if (name == null) {
            name = email;
          }
        }
        if (name == null) {
          name = emptyString;
        }
        if (email == null) {
          email = emptyString;
        }
        if (name.isEmpty() && email.isEmpty()) // ignore permissions without both name and email
        {
          return;
        }
        String collaboratorId = emptyString;
        if (grantedTo != null) {
          if (grantedTo.containsKey(Field.USER.getName())) {
            collaboratorId = grantedTo
                .getJsonObject(Field.USER.getName())
                .getString(Field.ID.getName(), emptyString);
          }
        }
        String storageAccessRole = getRoleFromPermission(permission);
        if (Objects.nonNull(storageAccessRole)) {
          Role role = WRITE.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
          CollaboratorInfo info = new CollaboratorInfo(
              permission.getString(Field.ID.getName()),
              name,
              email,
              collaboratorId,
              storageAccessRole,
              role);
          collaboratorInfoList.add(info);
        }
      }
    });
    return collaboratorInfoList;
  }

  private static String getRoleFromPermission(JsonObject permission) {
    if (permission.getJsonArray(Field.ROLES.getName()).contains(WRITE)) {
      return WRITE;
    } else if (!permission.getJsonArray(Field.ROLES.getName()).contains(OWNER)) {
      return READ;
    }
    return null;
  }

  public static void checkName(String name) throws WrongNameException {
    if (name.replaceAll("\\.", emptyString).equals(emptyString)) {
      throw new WrongNameException();
    }

    for (String specialCharacter : SPECIAL_CHARACTERS) {
      if (name.contains(specialCharacter)) {
        throw new WrongNameException();
      }
    }
  }

  public static String replaceWindowsChars(String name) {
    for (String specialCharacter : WINDOWS_SPECIAL_CHARACTERS) {
      if (name.contains(specialCharacter)) {
        name = name.replaceAll(specialCharacter, "_");
      }
    }

    return name;
  }
}
