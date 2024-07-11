package com.graebert.storage.resources.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.resources.KudoResourceErrorCodes;
import com.graebert.storage.resources.KudoResourceException;
import com.graebert.storage.resources.ObjectFilter;
import com.graebert.storage.resources.PermissionType;
import com.graebert.storage.resources.ResourceBuffer;
import com.graebert.storage.resources.ResourceErrorIds;
import com.graebert.storage.resources.ResourceOwnerType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.resources.dao.ResourceDAOImpl;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BaseResource extends DynamoBusModBase {
  public static final Logger log = LogManager.getRootLogger();
  private static final String pathSeparator = "/";
  public static String rootFolderId = Field.MINUS_1.getName();
  public static String sharedFolderId = Field.SHARED.getName();
  private static ResourceDAOImpl resourceDao;

  public BaseResource() {
    resourceDao = new ResourceDAOImpl();
  }

  private static String getS3Path(ResourceBuffer buffer, ResourceType resourceType) {
    List<String> s3PathBuilder = new ArrayList<>();
    s3PathBuilder.add(resourceType.name());
    s3PathBuilder.add(buffer.getOwnerType());
    if (!Utils.isStringNotNullOrEmpty(buffer.getOwnerId())) {
      if (!buffer.getOwnerType().equals(ResourceOwnerType.PUBLIC.name())) {
        s3PathBuilder.add(buffer.getUserId());
      }
    } else {
      s3PathBuilder.add(buffer.getOwnerId());
    }
    s3PathBuilder.add(buffer.getObjectId());
    s3PathBuilder.add(buffer.getFileName());

    return String.join(pathSeparator, s3PathBuilder);
  }

  public static JsonObject makeUpdateObjectData(ResourceBuffer buffer, Item object)
      throws KudoResourceException {
    JsonObject objectData = new JsonObject();
    if (Utils.isStringNotNullOrEmpty(buffer.getName())
        && (!object.hasAttribute(Field.NAME.getName())
            || !object.getString(Field.NAME.getName()).equals(buffer.getName()))) {
      objectData.put(Field.NAME.getName(), buffer.getName());
    }
    if (Utils.isStringNotNullOrEmpty(buffer.getDescription())
        && (!object.hasAttribute(Field.DESCRIPTION.getName())
            || !object.getString(Field.DESCRIPTION.getName()).equals(buffer.getDescription()))) {
      objectData.put(Field.DESCRIPTION.getName(), buffer.getDescription());
    }
    if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())
        && Objects.nonNull(buffer.getFileContent())) {
      if (!object.hasAttribute(Field.FILE_SIZE.getName())
          || object.getInt(Field.FILE_SIZE.getName()) != buffer.getFileSize()) {
        objectData.put(Field.FILE_SIZE.getName(), buffer.getFileSize());
      }
      String s3Path = object.getString(Field.S3_PATH.getName());
      objectData.put(Field.OLD_S3_PATH.getName(), s3Path);
      s3Path = s3Path.substring(0, s3Path.lastIndexOf("/") + 1) + buffer.getFileName();
      objectData.put(Field.S3_PATH.getName(), s3Path);
      if (!object.hasAttribute(Field.FILE_NAME_C.getName())
          || !object.getString(Field.FILE_NAME_C.getName()).equals(buffer.getFileName())) {
        objectData.put(Field.FILE_NAME_C.getName(), buffer.getFileName());
        objectData.put("fileNameLower", buffer.getFileName().toLowerCase());
      }
    }
    return objectData;
  }

  public static Iterator<Item> getFolderContent(ResourceBuffer buffer, ResourceType resourceType) {
    return resourceDao.getObjectsByFolderId(
        buffer.getParent(),
        buffer.getOwnerType(),
        buffer.getOwnerId(),
        ObjectFilter.getType(buffer.getObjectFilter()),
        buffer.getParent().equals(sharedFolderId) ? buffer.getUserId() : null,
        resourceType);
  }

  private static JsonObject getErrorObjectFromPermissionType(
      Message<JsonObject> message, PermissionType permission, ObjectType objectType) {
    JsonObject errorObject = new JsonObject();
    String errorId = emptyString, errorMessage = emptyString;
    if (Objects.nonNull(permission)) {
      switch (permission) {
        case SHARE:
          errorId = ResourceErrorIds.NO_PERMISSION_TO_SHARE.id;
          errorMessage =
              Utils.getLocalizedString(message, ResourceErrorIds.NO_PERMISSION_TO_SHARE.id);
          break;
        case UNSHARE:
          errorId = ResourceErrorIds.NO_PERMISSION_TO_UNSHARE.id;
          errorMessage =
              Utils.getLocalizedString(message, ResourceErrorIds.NO_PERMISSION_TO_UNSHARE.id);
          break;
        case UPDATE:
          errorId = ResourceErrorIds.NO_PERMISSION_TO_UPDATE.id;
          errorMessage =
              Utils.getLocalizedString(message, ResourceErrorIds.NO_PERMISSION_TO_UPDATE.id);
          break;
        case DELETE:
          errorId = ResourceErrorIds.NO_PERMISSION_TO_DELETE.id;
          errorMessage =
              Utils.getLocalizedString(message, ResourceErrorIds.NO_PERMISSION_TO_DELETE.id);
          break;
        case UPLOAD:
          errorId = ResourceErrorIds.NO_PERMISSION_TO_UPLOAD.id;
          errorMessage =
              Utils.getLocalizedString(message, ResourceErrorIds.NO_PERMISSION_TO_UPLOAD.id);
          break;
        case CREATE:
          String id = objectType.equals(ObjectType.FILE)
              ? ResourceErrorIds.NO_PERMISSION_TO_CREATE_FILE.id
              : ResourceErrorIds.NO_PERMISSION_TO_CREATE_FOLDER.id;
          errorObject.put(Field.ERROR_ID.getName(), id);
          errorObject.put(Field.ERROR_MESSAGE.getName(), Utils.getLocalizedString(message, id));
          return errorObject;
      }
    } else {
      errorId = ResourceErrorIds.NO_ACCESS.id;
      errorMessage = Utils.getLocalizedString(message, ResourceErrorIds.NO_ACCESS.id);
    }
    if (Objects.isNull(objectType)) {
      objectType = ObjectType.FOLDER;
    }

    errorObject.put(Field.ERROR_ID.getName(), errorId);
    errorObject.put(
        Field.ERROR_MESSAGE.getName(),
        errorMessage + " "
            + Utils.getLocalizedString(
                message,
                objectType.equals(ObjectType.FILE)
                    ? ResourceErrorIds.THIS_FILE.id
                    : ResourceErrorIds.THIS_FOLDER.id));
    return errorObject;
  }

  private static KudoResourceErrorCodes getKudoErrorCodeFromPermissionType(
      PermissionType permission) {
    if (Objects.nonNull(permission)) {
      switch (permission) {
        case SHARE:
        case UNSHARE:
          return KudoResourceErrorCodes.OBJECT_SHARE_ERROR;
        case UPDATE:
          return KudoResourceErrorCodes.OBJECT_UPDATE_ERROR;
        case DELETE:
          return KudoResourceErrorCodes.OBJECT_DELETE_ERROR;
        case UPLOAD:
          return KudoResourceErrorCodes.OBJECT_UPLOAD_ERROR;
        case CREATE:
          return KudoResourceErrorCodes.OBJECT_CREATE_ERROR;
      }
    }
    return KudoResourceErrorCodes.OBJECT_ACCESS_ERROR;
  }

  public static void checkAllowedResourceFileTypes(
      Message<JsonObject> message, ResourceType resourceType, String extension)
      throws KudoResourceException {
    if (!ResourceType.getAllowedFileTypes(resourceType).contains(extension.toLowerCase())) {
      String errorId;
      switch (resourceType) {
        case BLOCKS:
          errorId = ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_BLOCKS.id;
          break;
        case TEMPLATES:
          errorId = ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_TEMPLATES.id;
          break;
        case FONTS:
          errorId = ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_FONTS.id;
          break;
        case LISP:
          errorId = ResourceErrorIds.FILE_NOT_SUPPORTED_FOR_LISPS.id;
          break;
        default:
          errorId = "InvalidTypeOfFile";
      }
      throw new KudoResourceException(
          Utils.getLocalizedString(message, errorId),
          KudoResourceErrorCodes.FILE_NOT_SUPPORTED,
          HttpStatus.BAD_REQUEST,
          errorId);
    }
  }

  public JsonObject processFolderItems(
      Iterator<Item> folderIterator, ObjectFilter objectFilter, String userId, boolean full) {
    JsonObject results = new JsonObject();
    JsonArray files = new JsonArray();
    JsonArray folders = new JsonArray();
    if (Objects.isNull(objectFilter) || objectFilter.equals(ObjectFilter.ALL)) {
      folderIterator.forEachRemaining(object -> {
        if (object
            .getString(Field.OBJECT_TYPE.getName())
            .equalsIgnoreCase(ObjectType.FILE.name())) {
          files.add(itemToJson(object, userId, full));
        } else if (object
            .getString(Field.OBJECT_TYPE.getName())
            .equalsIgnoreCase(ObjectType.FOLDER.name())) {
          folders.add(itemToJson(object, userId, full));
        }
      });
      if (Objects.isNull(objectFilter)) {
        objectFilter = ObjectFilter.ALL;
      }
    } else if (objectFilter.equals(ObjectFilter.FILES)) {
      folderIterator.forEachRemaining(object -> files.add(itemToJson(object, userId, full)));
    } else if (objectFilter.equals(ObjectFilter.FOLDERS)) {
      folderIterator.forEachRemaining(object -> folders.add(itemToJson(object, userId, full)));
    }
    results.put("objectFilter", objectFilter.name());
    results.put(Field.FILES.getName(), files);
    results.put(Field.FOLDERS.getName(), folders);
    results.put("number", files.size() + folders.size());
    results.put(Field.FULL.getName(), full);
    return results;
  }

  public JsonObject itemToJson(Item item, String userId, boolean full) {
    JsonObject object = new JsonObject()
        .put(Field.ID.getName(), item.getString(Field.OBJECT_ID.getName()))
        .put(Field.NAME.getName(), item.getString(Field.NAME.getName()))
        .put(Field.PARENT.getName(), item.getString(Field.PARENT.getName()))
        .put(Field.TYPE.getName(), item.getString(Field.OBJECT_TYPE.getName()).toLowerCase())
        .put(Field.CREATED.getName(), item.getLong(Field.CREATED.getName()));

    if (full) {
      object.put(Field.PATH.getName(), item.getString(Field.PATH.getName()));
      if (item.hasAttribute(Field.RESOURCE_TYPE.getName())) {
        object.put(Field.RESOURCE_TYPE.getName(), item.getString(Field.RESOURCE_TYPE.getName()));
      }
      if (item.hasAttribute(Field.THUMBNAIL.getName())) {
        object.put(Field.THUMBNAIL.getName(), item.getString(Field.THUMBNAIL.getName()));
      }
    }

    if (item.hasAttribute(Field.DESCRIPTION.getName())) {
      object.put(Field.DESCRIPTION.getName(), item.getString(Field.DESCRIPTION.getName()));
    }

    if (item.hasAttribute(Field.USER_ID.getName())) {
      object.put(Field.USER_ID.getName(), item.getString(Field.USER_ID.getName()));
    }

    if (item.hasAttribute(Field.CREATED_BY.getName())) {
      object.put(Field.CREATED_BY.getName(), item.getString(Field.CREATED_BY.getName()));
    }

    ResourceOwnerType ownerType = null;
    if (item.hasAttribute(Field.OWNER_TYPE.getName())) {
      ownerType = ResourceOwnerType.getType(item.getString(Field.OWNER_TYPE.getName()));
      object.put(Field.OWNER_TYPE.getName(), ownerType.name());
    }

    if (item.hasAttribute(Field.OWNER_ID.getName())) {
      object.put(Field.OWNER_ID.getName(), item.getString(Field.OWNER_ID.getName()));
      boolean isOwner = false;
      if (Objects.nonNull(ownerType)) {
        if (ownerType.equals(ResourceOwnerType.OWNED)) {
          isOwner = userId.equals(item.getString(Field.OWNER_ID.getName()));
        } else if (!ownerType.equals(ResourceOwnerType.SHARED)) {
          isOwner = userId.equals(item.getString(Field.CREATED_BY.getName()));
        }
      }
      object.put(Field.IS_OWNER.getName(), isOwner);
    }

    if (item.hasAttribute(Field.OWNER_NAME.getName())) {
      object.put(Field.OWNER_NAME.getName(), item.getString(Field.OWNER_NAME.getName()));
    }

    if (item.hasAttribute(Field.UPDATED.getName())) {
      object.put(Field.UPDATED.getName(), item.getLong(Field.UPDATED.getName()));
    }

    if (item.hasAttribute(Field.FILE_NAME_C.getName())) {
      String fileName = item.getString(Field.FILE_NAME_C.getName());
      object.put(Field.FILE_NAME_C.getName(), fileName);
      if (Utils.isStringNotNullOrEmpty(fileName) && fileName.contains(".")) {
        object.put("fileType", Extensions.getExtension(fileName).substring(1).toUpperCase());
      }
    }

    if (item.hasAttribute(Field.FILE_SIZE.getName())) {
      object.put(Field.FILE_SIZE.getName(), item.getInt(Field.FILE_SIZE.getName()));
    }

    if (item.hasAttribute("fontInfo")) {
      object.put("faces", item.getList("fontInfo"));
    }

    return object;
  }

  public JsonObject makeCreateObjectData(
      ResourceType resourceType, ResourceBuffer buffer, Item folder) {
    JsonObject objectData = new JsonObject().put(Field.NAME.getName(), buffer.getName());

    if (Utils.isStringNotNullOrEmpty(buffer.getDescription())) {
      objectData.put(Field.DESCRIPTION.getName(), buffer.getDescription());
    }

    String path;
    if (Objects.nonNull(folder) && folder.hasAttribute(Field.PATH.getName())) {
      path = folder.getString(Field.PATH.getName()) + pathSeparator + buffer.getObjectId();
    } else {
      path = buffer.getParent() + pathSeparator + buffer.getObjectId();
    }
    objectData.put(Field.PATH.getName(), path);
    objectData.put(Field.OBJECT_TYPE.getName(), buffer.getObjectType());

    if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
      objectData.put(Field.FILE_NAME_C.getName(), buffer.getFileName());
      objectData.put("fileNameLower", buffer.getFileName().toLowerCase());
      objectData.put(Field.FILE_SIZE.getName(), buffer.getFileSize());
      String s3Path = getS3Path(buffer, resourceType);
      objectData.put(Field.S3_PATH.getName(), s3Path);
    }
    return objectData;
  }

  public Item checkAndReturnFolder(
      Message<JsonObject> message, ResourceBuffer buffer, ResourceType resourceType)
      throws KudoResourceException {
    if (Objects.isNull(buffer.getParent())) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.FOLDER_ID_MISSING.id),
          KudoResourceErrorCodes.INVALID_OBJECT_ID,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.FOLDER_ID_MISSING.id);
    }
    Item folder = null;
    if (!buffer.getParent().equals(rootFolderId)) {
      folder = resourceDao.getObjectById(
          buffer.getParent(), resourceType, buffer.getOwnerType(), buffer.getUserId());

      if (Objects.isNull(folder)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.FOLDER_NOT_FOUND.id),
            KudoResourceErrorCodes.FOLDER_DOES_NOT_EXIST,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.FOLDER_NOT_FOUND.id);
      }
    } else {
      buffer.setRootFolder(true);
    }
    return folder;
  }

  public Item checkAndGetObject(
      Message<JsonObject> message,
      String objectId,
      ResourceType resourceType,
      String ownerType,
      String userId)
      throws KudoResourceException {
    Item object = resourceDao.getObjectById(objectId, resourceType, ownerType, userId);
    if (Objects.isNull(object)) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.OBJECT_NOT_FOUND.id),
          KudoResourceErrorCodes.OBJECT_DOES_NOT_EXIST,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.OBJECT_NOT_FOUND.id);
    }
    return object;
  }

  /**
   * Check if the resource object we are trying to create already exist with the same name in the
   * current folder.
   *
   * @throws KudoResourceException - resource exception
   */
  public void checkResourceNameExists(
      Message<JsonObject> message, ResourceBuffer buffer, ResourceType resourceType, String name)
      throws KudoResourceException {
    Iterator<Item> resourceIterator = resourceDao.getFolderResourcesByName(
        buffer.getOwnerId(),
        buffer.getOwnerType(),
        name,
        buffer.getUserId(),
        buffer.getParent(),
        ObjectType.getType(buffer.getObjectType()),
        resourceType);

    if (resourceIterator.hasNext()
        && Stream.of(resourceIterator).anyMatch(item -> !item.next()
            .getString(Dataplane.pk)
            .equals(ResourceDAOImpl.makePkValue(buffer.getObjectId(), resourceType)))) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.DUPLICATE_NAME.id),
          KudoResourceErrorCodes.OBJECT_NAME_ALREADY_EXIST,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.DUPLICATE_NAME.id);
    }
  }

  /**
   * create the object item in the dynamoDb
   */
  public void createObject(
      ResourceBuffer buffer, ResourceType resourceType, JsonObject objectData) {
    resourceDao.createObject(
        buffer.getObjectId(),
        buffer.getOwnerType(),
        buffer.getOwnerId(),
        buffer.getUserId(),
        resourceType,
        buffer.getParent(),
        objectData,
        buffer.getFontInfo());
  }

  public Item updateObject(
      String pkValue, String skValue, JsonObject objectData, ResourceType resourceType) {
    return resourceDao.updateObject(pkValue, skValue, objectData, resourceType, null);
  }

  public Item updateObject(
      String pkValue,
      String skValue,
      JsonObject objectData,
      ResourceType resourceType,
      List<Map<String, Object>> fontInfo) {
    return resourceDao.updateObject(pkValue, skValue, objectData, resourceType, fontInfo);
  }

  public void deleteObject(String pkValue, String skValue) {
    resourceDao.deleteObject(pkValue, skValue);
  }

  private void hasAccess(
      Message<JsonObject> message,
      ResourceBuffer buffer,
      boolean isEditRequired,
      ObjectType objectType,
      PermissionType permission,
      Item object)
      throws KudoResourceException {
    ResourceOwnerType ownerType = ResourceOwnerType.getType(buffer.getOwnerType());
    boolean hasAccess = false;
    if (buffer.isRootFolder()
        || ownerType.name().equalsIgnoreCase(object.getString(Field.OWNER_TYPE.getName()))) {
      if (ownerType.equals(ResourceOwnerType.OWNED)) {
        if (buffer.isRootFolder()
            || buffer.getOwnerId().equals(object.getString(Field.OWNER_ID.getName()))) {
          hasAccess = true;
        }
      } else if (ownerType.equals(ResourceOwnerType.ORG)) {
        if (buffer.isRootFolder()
            || buffer.getOwnerId().equals(object.getString(Field.OWNER_ID.getName()))) {
          if (!isEditRequired) {
            hasAccess = true;
          } else {
            Item user = Users.getUserById(buffer.getUserId());
            if (user != null
                && user.hasAttribute(Field.IS_ORG_ADMIN.getName())
                && user.getBoolean(Field.IS_ORG_ADMIN.getName())) {
              hasAccess = true;
            }
          }
        }
      } else if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
        if (!isEditRequired) {
          hasAccess = true;
        } else {
          if (Objects.nonNull(permission)
              && (permission.equals(PermissionType.SHARE)
                  || permission.equals(PermissionType.UNSHARE))) {
            throw new KudoResourceException(
                Utils.getLocalizedString(message, ResourceErrorIds.PUBLIC_SHARING_NOT_ALLOWED.id),
                KudoResourceErrorCodes.OBJECT_SHARE_ERROR,
                HttpStatus.BAD_REQUEST,
                ResourceErrorIds.PUBLIC_SHARING_NOT_ALLOWED.id);
          } else {
            Item user = Users.getUserById(buffer.getUserId());
            if (user != null
                && user.hasAttribute(Field.ROLES.getName())
                && user.getList(Field.ROLES.getName()).contains("1")) {
              hasAccess = true;
            }
          }
        }
      }
    }
    if (!hasAccess) {
      JsonObject errorObject = getErrorObjectFromPermissionType(message, permission, objectType);
      throw new KudoResourceException(
          errorObject.getString(Field.ERROR_MESSAGE.getName()),
          getKudoErrorCodeFromPermissionType(permission),
          HttpStatus.BAD_REQUEST,
          errorObject.getString(Field.ERROR_ID.getName()));
    }
  }

  private boolean isOwnerValid(String ownerId, String ownerType) {
    if (ResourceOwnerType.getValues().contains(ownerType.toUpperCase())) {
      if (ownerType.equalsIgnoreCase(ResourceOwnerType.OWNED.name())) {
        Item user = Users.getUserById(ownerId);
        return Objects.nonNull(user);
      } else if (ownerType.equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
        Item org = Users.getOrganizationById(ownerId);
        return Objects.nonNull(org);
      } else {
        return true;
      }
    }
    return false;
  }

  public Item verifyAccess(
      Message<JsonObject> message,
      ResourceBuffer buffer,
      String objectType,
      boolean isEditRequired,
      PermissionType permission,
      ResourceType resourceType,
      boolean checkParent)
      throws KudoResourceException {
    if (Objects.nonNull(buffer.getParent())
        && buffer.getParent().equals(sharedFolderId)
        && Objects.nonNull(permission)
        && permission.equals(PermissionType.CREATE)) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_CREATE_IN_SHARING_FOLDER.id),
          KudoResourceErrorCodes.CANNOT_CREATE_OBJECT,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.COULD_NOT_CREATE_IN_SHARING_FOLDER.id);
    }

    Item object;
    if (checkParent) {
      object = checkAndReturnFolder(message, buffer, resourceType);
    } else {
      object = checkAndGetObject(
          message, buffer.getObjectId(), resourceType, buffer.getOwnerType(), buffer.getUserId());
      if (Objects.nonNull(object)) {
        if (object.hasAttribute(Field.OBJECT_TYPE.getName())
            && !object.getString(Field.OBJECT_TYPE.getName()).equalsIgnoreCase(objectType)) {
          throw new KudoResourceException(
              MessageFormat.format(
                  Utils.getLocalizedString(message, ResourceErrorIds.OBJECT_TYPE_NOT_MATCH.id),
                  object.getString(Field.OBJECT_TYPE.getName()),
                  objectType),
              KudoResourceErrorCodes.WRONG_OBJECT_TYPE,
              HttpStatus.BAD_REQUEST,
              ResourceErrorIds.OBJECT_TYPE_NOT_MATCH.id);
        }
      }
    }

    // check and update if ownerType or ownerId is null
    if (Objects.nonNull(object)) {
      if (Objects.isNull(buffer.getOwnerType())) {
        if (object.hasAttribute(Field.OWNER_TYPE.getName())) {
          buffer.setOwnerType(object.getString(Field.OWNER_TYPE.getName()));
        } else {
          // this should not happen
          throw new KudoResourceException(
              Utils.getLocalizedString(message, ResourceErrorIds.OWNER_TYPE_NOT_FOUND.id),
              KudoResourceErrorCodes.INVALID_ITEM_OWNER_TYPE,
              HttpStatus.BAD_REQUEST,
              ResourceErrorIds.OWNER_TYPE_NOT_FOUND.id);
        }
      }
      if (Objects.isNull(buffer.getOwnerId())) {
        if (object.hasAttribute(Field.OWNER_ID.getName())) {
          buffer.setOwnerId(object.getString(Field.OWNER_ID.getName()));
        } else {
          // Handled only ORG case for now
          if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
            buffer.setOwnerId(buffer.getOrganizationId());
          } else {
            buffer.setOwnerId(buffer.getUserId());
          }
        }
      }
    }

    // check if owner is valid
    if (!isOwnerValid(buffer.getOwnerId(), buffer.getOwnerType())) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.INVALID_OWNER_INFO.id),
          KudoResourceErrorCodes.INVALID_OWNER,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.INVALID_OWNER_INFO.id);
    }
    // check for user access
    hasAccess(message, buffer, isEditRequired, ObjectType.getType(objectType), permission, object);
    return object;
  }

  public byte[] getFileFromS3(
      Message<JsonObject> message, S3Regional s3Regional, Item object, String objectId)
      throws KudoResourceException {
    byte[] content = null;
    if (object.hasAttribute(Field.S3_PATH.getName())) {
      String s3Path = object.getString(Field.S3_PATH.getName());
      if (!Utils.isStringNotNullOrEmpty(s3Path)) {
        log.error("Invalid S3 key for deleting a resource file");
      } else {
        try {
          log.info("Getting resource file for objectId " + objectId);
          content = s3Regional.get(s3Path);
        } catch (Exception ex) {
          log.error("Error in getting resource file from s3 - " + ex.getLocalizedMessage());
        }
      }
    } else {
      log.error("Resource Item does not have s3 Path for objectId " + objectId);
    }
    if (Objects.isNull(content)) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.FILE_NOT_FOUND.id),
          KudoResourceErrorCodes.OBJECT_DOWNLOAD_ERROR,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.FILE_NOT_FOUND.id);
    }
    return content;
  }

  private byte[] getChunk(S3Regional s3Regional, String path) {
    byte[] result;
    try {
      result = s3Regional.get(path);
    } catch (Exception e) {
      log.error("Error in getting files from S3 for creating zip - " + e.getLocalizedMessage());
      result = new byte[0];
    }
    return result;
  }

  public void uploadFileToS3(S3Regional s3Regional, byte[] content, String s3Path) {
    if (!Utils.isStringNotNullOrEmpty(s3Path)) {
      log.error("Invalid S3 key for uploading a resource file");
      return;
    }
    InputStream stream = new ByteArrayInputStream(content);
    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(content.length);
    PutObjectRequest objectRequest =
        new PutObjectRequest(s3Regional.getBucketName(), s3Path, stream, metadata);
    log.info("RESOURCES : Uploading a file with s3 Key " + s3Path);
    s3Regional.putObject(objectRequest);
  }

  public void deleteFileFromS3(S3Regional s3Regional, Item object, String objectId) {
    if (object.hasAttribute(Field.S3_PATH.getName())) {
      String s3Path = object.getString(Field.S3_PATH.getName());
      try {
        log.info("Deleting resource file for objectId " + objectId);
        if (!Utils.isStringNotNullOrEmpty(s3Path)) {
          log.error("Invalid S3 key for deleting a resource file");
          return;
        }
        if (s3Regional.doesObjectExist(s3Path)) {
          log.info("RESOURCES : Deleting a file with s3 Key " + s3Path);
          s3Regional.deleteObject(s3Regional.getBucketName(), s3Path);
        } else {
          log.warn("Object with s3 Key " + s3Path + " does not exist");
        }
      } catch (Exception ex) {
        log.error("Error in deleting resource file from s3 - " + ex.getLocalizedMessage());
      }
    } else {
      log.error("Resource Item does not have s3 Path for objectId " + objectId);
    }
  }

  public boolean updateS3File(
      Entity parentSegment,
      Message<JsonObject> message,
      S3Regional s3Regional,
      Item object,
      JsonObject objectData,
      byte[] content) {
    if (Objects.nonNull(content)) {
      if (objectData.containsKey(Field.S3_PATH.getName())) {
        uploadFileToS3(s3Regional, content, objectData.getString(Field.S3_PATH.getName()));
      } else {
        log.error("Update object data does not have s3 Path for objectId "
            + object.getString(Field.OBJECT_ID.getName()));
        return false;
      }

      if (objectData.containsKey(Field.OLD_S3_PATH.getName())
          && !objectData
              .getString(Field.OLD_S3_PATH.getName())
              .equals(objectData.getString(Field.S3_PATH.getName()))) {
        new ExecutorServiceAsyncRunner(
                executorService, OperationGroup.RESOURCES, parentSegment, message)
            .withName("deleteFileFromS3")
            .run((Segment blockingSegment) ->
                deleteFileFromS3(s3Regional, object, object.getString(Field.OBJECT_ID.getName())));
      }

      if (!objectData.containsKey(Field.FILE_NAME_C.getName())) {
        objectData.remove(Field.S3_PATH.getName());
        objectData.remove(Field.OLD_S3_PATH.getName());
      }
      return true;
    }
    log.error("Resource file content is null for objectId "
        + object.getString(Field.OBJECT_ID.getName()));
    return false;
  }

  public ByteArrayOutputStream downloadFolder(
      Message<JsonObject> message,
      S3Regional s3Regional,
      String requestId,
      ResourceBuffer buffer,
      boolean recursive,
      ResourceType resourceType)
      throws KudoResourceException {
    Item request = ZipRequests.getZipRequest(buffer.getUserId(), buffer.getObjectId(), requestId);
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      ZipOutputStream stream = new ZipOutputStream(bos);
      zipFolder(
          stream,
          s3Regional,
          buffer.getObjectId(),
          buffer.getUserId(),
          recursive,
          emptyString,
          buffer.getOwnerType(),
          buffer.getOwnerId(),
          resourceType);
      stream.close();
      bos.close();
      return bos;
    } catch (Exception e) {
      ZipRequests.setRequestException(message, request, e);
      throw new KudoResourceException(
          e.getLocalizedMessage(),
          KudoResourceErrorCodes.OBJECT_DOWNLOAD_ERROR,
          HttpStatus.BAD_REQUEST,
          emptyString);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      S3Regional s3Regional,
      String folderId,
      String userId,
      boolean recursive,
      String path,
      String owner,
      String ownerId,
      ResourceType resourceType)
      throws Exception {
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    Iterator<Item> items =
        resourceDao.getObjectsByFolderId(folderId, owner, ownerId, null, userId, resourceType);
    Item item;
    while (items.hasNext()) {
      item = items.next();
      ResourceBuffer resourceBuffer = ResourceBuffer.itemToResource(item);
      String name = resourceBuffer.getName();
      String properPath = path.isEmpty() ? path : path + File.separator;
      if (resourceBuffer.getObjectType().equalsIgnoreCase(ObjectType.FOLDER.name()) && recursive) {
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        zipFolder(
            stream,
            s3Regional,
            resourceBuffer.getObjectId(),
            userId,
            true,
            properPath + name,
            owner,
            ownerId,
            resourceType);
      } else {
        name = Utils.checkAndRename(fileNames, name);
        fileNames.add(name);
        ZipEntry zipEntry = new ZipEntry(properPath + name);
        stream.putNextEntry(zipEntry);
        stream.write(getChunk(s3Regional, resourceBuffer.getS3Path()));
        stream.closeEntry();
      }
    }
  }

  public void finishGetFile(Message<JsonObject> message, String downloadToken, byte[] result) {
    if (downloadToken != null) {
      DownloadRequests.setRequestData(downloadToken, result, JobStatus.SUCCESS, null, null);
    }
    message.reply(result);
  }

  public void finishGetFolder(
      Message<JsonObject> message,
      S3Regional s3Regional,
      String downloadToken,
      Item request,
      ByteArrayOutputStream bos) {
    if (downloadToken != null) {
      ZipRequests.putZipToS3(
          request,
          s3Regional.getBucketName(),
          s3Regional.getRegion(),
          bos.toByteArray(),
          true,
          Utils.getLocaleFromMessage(message));
    }
    message.reply(bos.toByteArray());
  }

  public JsonArray getFolderPath(
      Message<JsonObject> message, ResourceBuffer buffer, ResourceType resourceType)
      throws KudoResourceException {
    JsonArray path = new JsonArray();
    String parent = buffer.getObjectId();
    while (true) {
      if (parent.equals(rootFolderId) || parent.equals(sharedFolderId)) {
        path.add(new JsonObject()
            .put(Field.ENCAPSULATED_ID.getName(), rootFolderId)
            .put(Field.NAME.getName(), "~")
            .put(Field.VIEW_ONLY.getName(), !parent.equals(rootFolderId)));
        break;
      }
      Item object = resourceDao.getObjectById(
          parent, resourceType, buffer.getOwnerType(), buffer.getUserId());
      if (Objects.isNull(object) || Objects.isNull(ResourceDAOImpl.getParent(object))) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_PATH_FOR_FOLDER.id),
            KudoResourceErrorCodes.INVALID_FOLDER_PATH,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_PATH_FOR_FOLDER.id);
      }
      path.add(new JsonObject()
          .put(Field.NAME.getName(), ResourceDAOImpl.getName(object))
          .put(Field.ENCAPSULATED_ID.getName(), parent)
          .put(Field.VIEW_ONLY.getName(), false));
      parent = ResourceDAOImpl.getParent(object);
    }
    return path;
  }
}
