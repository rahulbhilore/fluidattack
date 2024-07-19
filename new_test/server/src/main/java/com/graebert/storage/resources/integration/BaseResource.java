package com.graebert.storage.resources.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.common.collect.Iterables;
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
import com.graebert.storage.thumbnails.ThumbnailsManager;
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
import java.util.Collections;
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
import org.apache.commons.collections4.IteratorUtils;
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

  /**
   * Get S3 path of the stored file
   *
   * @param buffer                            Resource buffer
   * @param resourceType                      Type of the resource
   *
   * @return String
   */
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

  /**
   * Create a JsonObject with parameters for updating the DB item
   *
   * @param buffer                      Resource buffer
   * @param object                      Object to be updated
   *
   * @return JsonObject
   */
  public static JsonObject makeUpdateObjectData(ResourceBuffer buffer, Item object)
      throws KudoResourceException {
    JsonObject objectData = new JsonObject();
    if (Utils.isStringNotNullOrEmpty(buffer.getName())
        && (!object.hasAttribute(Field.NAME.getName())
            || !getName(object).equals(buffer.getName()))) {
      objectData.put(Field.NAME.getName(), buffer.getName());
    }
    if (Utils.isStringNotNullOrEmpty(buffer.getDescription())
        && (!object.hasAttribute(Field.DESCRIPTION.getName())
            || !object.getString(Field.DESCRIPTION.getName()).equals(buffer.getDescription()))) {
      objectData.put(Field.DESCRIPTION.getName(), buffer.getDescription());
    }
    if (isFile(buffer) && Objects.nonNull(buffer.getFileContent())) {
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

  /**
   * Get JsonObject after processing for owned/org and shared items inside a folder
   *
   * @param buffer                      Resource buffer
   * @param resourceType                type of the resource
   *
   * @return JsonObject
   */
  public JsonObject getOwnedAndSharedFolderContent(
      ResourceBuffer buffer, ResourceType resourceType) {
    String ownerId = buffer.getOwnerId();
    if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.SHARED.name())) {
      if (buffer.isOrganizationObject()) {
        buffer.setOwnerType(ResourceOwnerType.ORG.name()).setOwnerId(buffer.getOrganizationId());
      } else {
        buffer.setOwnerType(ResourceOwnerType.OWNED.name()).setOwnerId(buffer.getUserId());
      }
    }
    Iterator<Item> ownedIterator = getFolderContent(buffer, resourceType);
    JsonObject items = processFolderItems(
        ownedIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);
    if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
      buffer.setUserId(buffer.getOwnerId());
    }
    buffer.setOwnerId(ownerId);
    Iterator<Item> sharedIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.SHARED.name()), resourceType);
    if (sharedIterator.hasNext()) {
      JsonObject sharedItems = processFolderItems(
          sharedIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);
      concatFolderItems(items, sharedItems);
    }
    return items;
  }

  /**
   * Get iterable for owned/org and shared items inside a folder
   *
   * @param buffer                      Resource buffer
   * @param resourceType                type of the resource
   *
   * @return Iterable<Item>
   */
  public Iterable<Item> getOwnedAndSharedFolderContentIterable(
      ResourceBuffer buffer, ResourceType resourceType) {
    String ownerId = buffer.getOwnerId();
    if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.SHARED.name())) {
      buffer.setOwnerType(ResourceOwnerType.OWNED.name()).setOwnerId(buffer.getUserId());
    }
    Iterator<Item> ownedIterator = getFolderContent(buffer, resourceType);
    if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
      buffer.setUserId(buffer.getOwnerId());
    }
    buffer.setOwnerId(ownerId);
    Iterator<Item> sharedIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.SHARED.name()), resourceType);
    return Iterables.concat(
        IteratorUtils.toList(ownedIterator), IteratorUtils.toList(sharedIterator));
  }

  /**
   * Concat the folder items from different json objects
   *
   * @param original               Original object to be merged into
   * @param toMerge                Object to merge
   *
   */
  public static void concatFolderItems(JsonObject original, JsonObject toMerge) {
    original
        .put(
            Field.FILES.getName(),
            original
                .getJsonArray(Field.FILES.getName())
                .addAll(toMerge.getJsonArray(Field.FILES.getName())))
        .put(
            Field.FOLDERS.getName(),
            original
                .getJsonArray(Field.FOLDERS.getName())
                .addAll(toMerge.getJsonArray(Field.FOLDERS.getName())))
        .put(
            Field.NUMBER.getName(),
            original.getInteger(Field.NUMBER.getName())
                + toMerge.getInteger(Field.NUMBER.getName()));
  }

  /**
   * Get the contents/items of the folder
   *
   * @param buffer                      Resource buffer
   * @param resourceType                Type of the resource
   *
   * @return Iterator<Item>
   */
  public static Iterator<Item> getFolderContent(ResourceBuffer buffer, ResourceType resourceType) {
    if (Objects.isNull(buffer.getOwnerId())) {
      if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.OWNED.name())) {
        buffer.setOwnerId(buffer.getUserId());
      } else if (buffer.getOwnerType().equalsIgnoreCase(ResourceOwnerType.ORG.name())) {
        if (Objects.isNull(buffer.getOrganizationId())) {
          return Collections.emptyIterator();
        }
        buffer.setOwnerId(buffer.getOrganizationId());
      }
    }
    return resourceDao.getObjectsByFolderId(
        buffer.getParent(),
        buffer.getOwnerType(),
        buffer.getOwnerId(),
        ObjectFilter.getType(buffer.getObjectFilter()),
        buffer.getUserId(),
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
        case MOVE:
          return KudoResourceErrorCodes.OBJECT_MOVE_ERROR;
      }
    }
    return KudoResourceErrorCodes.OBJECT_ACCESS_ERROR;
  }

  /**
   * Iterate throw the folder items and parse them into files/folders structure
   *
   * @param folderIterator                      Iterator for all the folder items
   * @param objectFilter                        Filter for the type of objects to be returned
   * @param userId                              Current userId
   * @param full                                If required all parameters
   *
   * @return JsonObject
   */
  public JsonObject processFolderItems(
      Iterator<Item> folderIterator, ObjectFilter objectFilter, String userId, boolean full) {
    JsonObject results = new JsonObject();
    JsonArray files = new JsonArray();
    JsonArray folders = new JsonArray();
    if (Objects.isNull(objectFilter) || objectFilter.equals(ObjectFilter.ALL)) {
      folderIterator.forEachRemaining(object -> {
        if (isFile(object)) {
          files.add(itemToJson(object, userId, full));
        } else if (isFolder(object)) {
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
    results.put(Field.OBJECT_FILTER.getName(), objectFilter.name());
    results.put(Field.FILES.getName(), files);
    results.put(Field.FOLDERS.getName(), folders);
    results.put(Field.NUMBER.getName(), files.size() + folders.size());
    results.put(Field.FULL.getName(), full);
    return results;
  }

  /**
   * Convert the DB Item to JsonObject with required and updated parameters
   *
   * @param item                       DB Item to be converted
   * @param userId                     Current userId
   * @param full                       If required all parameters
   *
   * @return JsonObject
   */
  public JsonObject itemToJson(Item item, String userId, boolean full) {
    long itemModified = item.getLong(Field.CREATED.getName());
    JsonObject object = new JsonObject()
        .put(Field.ID.getName(), item.getString(Field.OBJECT_ID.getName()))
        .put(Field.NAME.getName(), item.getString(Field.NAME.getName()))
        .put(Field.PARENT.getName(), item.getString(Field.PARENT.getName()))
        .put(Field.TYPE.getName(), item.getString(Field.OBJECT_TYPE.getName()).toLowerCase())
        .put(Field.CREATED.getName(), itemModified);

    ResourceType resourceType = null;
    if (item.hasAttribute(Field.RESOURCE_TYPE.getName())) {
      resourceType = ResourceType.getType(item.getString(Field.RESOURCE_TYPE.getName()));
    }
    if (full) {
      object.put(Field.PATH.getName(), item.getString(Field.PATH.getName()));
      if (isFile(item)) {
        object.put(Field.S3_PATH.getName(), item.getString(Field.S3_PATH.getName()));
      }
      if (Objects.nonNull(resourceType)) {
        object.put(Field.RESOURCE_TYPE.getName(), resourceType.name());
      }
      Set<String> editors = getObjectEditors(item);
      Set<String> viewers = getObjectViewers(item);
      JsonArray newEditors = new JsonArray();
      if (Objects.nonNull(editors)) {
        editors.forEach(eId -> {
          Item user = Users.getUserById(eId);
          if (Objects.nonNull(user)) {
            newEditors.add(Users.getUserInfo(user));
          } else {
            Item organization = Users.getOrganizationById(eId);
            if (Objects.nonNull(organization)) {
              newEditors.add(new JsonObject()
                  .put(Field.ENCAPSULATED_ID.getName(), eId)
                  .put(Field.NAME.getName(), organization.getString(Field.COMPANY_NAME.getName()))
                  .put(Field.TYPE.getName(), Field.ORGANIZATION.getName()));
            }
          }
        });
      }
      JsonArray newViewers = new JsonArray();
      if (Objects.nonNull(viewers)) {
        viewers.forEach(eId -> {
          Item user = Users.getUserById(eId);
          if (Objects.nonNull(user)) {
            newViewers.add(Users.getUserInfo(user));
          } else {
            Item organization = Users.getOrganizationById(eId);
            if (Objects.nonNull(organization)) {
              newViewers.add(new JsonObject()
                  .put(Field.ENCAPSULATED_ID.getName(), eId)
                  .put(Field.NAME.getName(), organization.getString(Field.COMPANY_NAME.getName()))
                  .put(Field.TYPE.getName(), Field.ORGANIZATION.getName()));
            }
          }
        });
      }
      object.put(
          Field.SHARE.getName(),
          new JsonObject()
              .put(Field.EDITOR.getName(), newEditors)
              .put(Field.VIEWER.getName(), newViewers));
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
      itemModified = item.getLong(Field.UPDATED.getName());
    }
    object.put(Field.UPDATED.getName(), itemModified);

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

    if (item.hasAttribute(Field.FONT_INFO.getName())) {
      object.put("faces", item.getList(Field.FONT_INFO.getName()));
    }

    if (Objects.nonNull(resourceType) && resourceType.equals(ResourceType.BLOCKS) && isFile(item)) {
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          Field.BL.getName(),
          item.getString(Field.OBJECT_ID.getName()),
          String.valueOf(itemModified));
      object.put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
      object.put(
          Field.THUMBNAIL.getName(),
          ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    }

    return object;
  }

  /**
   * Create a JsonObject with parameters for creating the DB Item
   *
   * @param buffer                       Resource Buffer
   * @param resourceType                 type of resource
   * @param folder                       parent DB Item
   *
   * @return JsonObject
   */
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

    if (isFile(buffer)) {
      objectData.put(Field.FILE_NAME_C.getName(), buffer.getFileName());
      objectData.put("fileNameLower", buffer.getFileName().toLowerCase());
      objectData.put(Field.FILE_SIZE.getName(), buffer.getFileSize());
      String s3Path = getS3Path(buffer, resourceType);
      objectData.put(Field.S3_PATH.getName(), s3Path);
    }
    return objectData;
  }

  /**
   * Validate and get the parent folder if exist
   *
   * @param message                      Vertx message object
   * @param buffer                       Resource Buffer
   * @param resourceType                 type of resource
   *
   * @throws KudoResourceException resource exception
   */
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
      folder = resourceDao.getObjectById(buffer.getParent(), resourceType, buffer);
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

  /**
   * Validate and get the object if exist
   *
   * @param message                      Vertx message object
   * @param buffer                       Resource Buffer
   * @param resourceType                 type of resource
   *
   * @throws KudoResourceException resource exception
   */
  public Item checkAndGetObject(
      Message<JsonObject> message, ResourceBuffer buffer, ResourceType resourceType)
      throws KudoResourceException {
    Item object = resourceDao.getObjectById(buffer.getObjectId(), resourceType, buffer);
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
   * @param message                      Vertx message object
   * @param buffer                       Resource Buffer
   * @param resourceType                 type of resource
   * @param name                         name to be checked
   *
   * @throws KudoResourceException resource exception
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
   * Create the object item in the dynamoDb
   *
   * @param buffer               Resource buffer
   * @param objectData                    Additional data
   * @param resourceType                  type of resource
   *
   * @return Item
   */
  public Item createObject(
      ResourceBuffer buffer, ResourceType resourceType, JsonObject objectData) {
    return resourceDao.createObject(
        buffer.getObjectId(),
        buffer.getOwnerType(),
        buffer.getOwnerId(),
        buffer.getUserId(),
        resourceType,
        buffer.getParent(),
        objectData,
        buffer.getFontInfo(),
        buffer.isOrganizationObject(),
        buffer.getOrganizationId());
  }

  /**
   * Update object in DB
   *
   * @param pkValue                       pk value of DB Item
   * @param skValue                       sk value of DB Item
   * @param objectData                    Additional data
   * @param resourceType                  type of resource
   *
   */
  public Item updateObject(
      String pkValue, String skValue, JsonObject objectData, ResourceType resourceType) {
    return resourceDao.updateObject(pkValue, skValue, objectData, resourceType, null);
  }

  /**
   * share object with the collaborators
   *
   * @param object                       DB object to be shared
   * @param buffer                       Resource buffer
   * @param resourceType                 type of resource
   *
   * @return JsonObject
   */
  public JsonObject shareObject(Item object, ResourceBuffer buffer, ResourceType resourceType) {
    HashSet<String> editors, viewers;
    Set<String> existingEditors = getObjectEditors(object);
    if (Objects.nonNull(existingEditors)) {
      editors = new HashSet<>(existingEditors);
    } else {
      editors = new HashSet<>();
    }
    Set<String> existingViewers = getObjectViewers(object);
    if (Objects.nonNull(existingViewers)) {
      viewers = new HashSet<>(existingViewers);
    } else {
      viewers = new HashSet<>();
    }

    Set<String> changedCollaborators = new HashSet<>();
    if (Utils.isListNotNullOrEmpty(buffer.getEditors())) {
      Set<String> editorsDifference = new HashSet<>(buffer.getEditors());
      editorsDifference.removeAll(editors);
      changedCollaborators.addAll(editorsDifference);
      editors.addAll(buffer.getEditors());
      buffer.getEditors().forEach(viewers::remove);
    }
    if (Utils.isListNotNullOrEmpty(buffer.getViewers())) {
      Set<String> viewersDifference = new HashSet<>(buffer.getViewers());
      viewersDifference.removeAll(viewers);
      changedCollaborators.addAll(viewersDifference);
      viewers.addAll(buffer.getViewers());
      buffer.getViewers().forEach(editors::remove);
    }
    editors.remove(getOwner(object));
    viewers.remove(getOwner(object));

    if (object.hasAttribute(Field.UNSHARED_MEMBERS.getName())) {
      List<String> unsharedMembers = object.getList(Field.UNSHARED_MEMBERS.getName());
      editors.removeIf(ed -> {
        if (unsharedMembers.contains(ed)) {
          unsharedMembers.remove(ed);
          return true;
        }
        return false;
      });
      viewers.removeIf(vi -> {
        if (unsharedMembers.contains(vi)) {
          unsharedMembers.remove(vi);
          return true;
        }
        return false;
      });
      removeFromUnsharedUsersForOrg(object, unsharedMembers);
    }

    if (!editors.equals(existingEditors) || !viewers.equals(existingViewers)) {
      resourceDao.updateCollaborators(object, editors, viewers);
    }

    object.removeAttribute(Field.VIEWERS.getName());
    object.removeAttribute(Field.EDITORS.getName());

    Set<String> allCollaborators = new HashSet<>();
    allCollaborators.addAll(editors);
    allCollaborators.addAll(viewers);

    allCollaborators.forEach(col -> {
      if ((Objects.isNull(existingEditors) || !existingEditors.contains(col))
          && (Objects.isNull(existingViewers) || !existingViewers.contains(col))) {
        resourceDao.shareObject(object, col);
      }
    });

    if (isFolder(object)) {
      new ExecutorServiceAsyncRunner(executorService, OperationGroup.RESOURCES, null, null)
          .withName("shareResourceFolder")
          .run((Segment blockingSegment) -> {
            buffer.setParent(getObjectId(object)).setObjectFilter(ObjectFilter.ALL.name());
            Iterable<Item> iterable = getOwnedAndSharedFolderContentIterable(buffer, resourceType);
            for (Item item : iterable) {
              shareObject(item, buffer, resourceType);
            }
          });
    }

    return new JsonObject()
        .put(Field.ALL_COLLABORATORS.getName(), new JsonArray(new ArrayList<>(allCollaborators)))
        .put(
            Field.CHANGED_COLLABORATORS.getName(),
            new JsonArray(new ArrayList<>(changedCollaborators)));
  }

  /**
   * Remove user from the unshared members list to reset the object access for that user
   *
   * @param object                      DB object to be unshared
   * @param unsharedMembers             updated unshared members list
   *
   */
  public void removeFromUnsharedUsersForOrg(Item object, List<String> unsharedMembers) {
    resourceDao.updateUnsharedMembersForOrg(object, unsharedMembers);
  }

  /**
   * Add user to unshared members list for org to revoke object access for that user
   *
   * @param object                      DB object to be unshared
   * @param userId                      ID of the user to be added
   *
   */
  public void addToUnsharedUsersForOrg(Item object, String userId) {
    List<String> unsharedMembers;
    if (object.hasAttribute(Field.UNSHARED_MEMBERS.getName())) {
      unsharedMembers = object.getList(Field.UNSHARED_MEMBERS.getName());
    } else {
      unsharedMembers = new ArrayList<>();
    }
    unsharedMembers.add(userId);
    resourceDao.updateUnsharedMembersForOrg(object, unsharedMembers);
  }

  /**
   * unshare object from the collaborators
   *
   * @param object                       DB object to be unshared
   * @param deShare                      Array of collaborators to be removed
   * @param parentOwnerId                ownerId of the parent folder to check if parent needs to be updated
   * @param buffer                       Resource buffer
   * @param resourceType                 type of resource
   *
   */
  public void unshareObject(
      Item object,
      JsonArray deShare,
      String parentOwnerId,
      ResourceBuffer buffer,
      ResourceType resourceType) {
    Set<String> editors, viewers;
    Set<String> existingEditors = getObjectEditors(object);
    if (Objects.nonNull(existingEditors)) {
      existingEditors.removeIf(deShare::contains);
      editors = new HashSet<>(existingEditors);
    } else {
      editors = new HashSet<>();
    }
    Set<String> existingViewers = getObjectViewers(object);
    if (Objects.nonNull(existingViewers)) {
      existingViewers.removeIf(deShare::contains);
      viewers = new HashSet<>(existingViewers);
    } else {
      viewers = new HashSet<>();
    }
    resourceDao.updateCollaborators(object, editors, viewers);
    deShare.forEach(user -> {
      String finalParentOwnerId = parentOwnerId;
      if (user.equals(getOwner(object))) {
        if (Objects.isNull(finalParentOwnerId)) {
          Item parent =
              resourceDao.getObjectById(getParent(object), resourceType, new ResourceBuffer());
          if (Objects.nonNull(parent)) {
            finalParentOwnerId = getOwner(parent);
          }
        }
        if (Objects.nonNull(finalParentOwnerId) && !user.equals(finalParentOwnerId)) {
          // move the object the owner's root folder
          resourceDao.updateParent(object, resourceType, rootFolderId, buffer.getUserId());
          removeAllSharedItems(object, resourceType);
        }
      } else {
        resourceDao.unshareObject(
            object.getString(Field.PK.getName()), getParent(object), (String) user);
      }
    });

    if (isFolder(object) && Objects.nonNull(buffer)) {
      new ExecutorServiceAsyncRunner(executorService, OperationGroup.RESOURCES, null, null)
          .withName("unShareResourceFolder")
          .run((Segment blockingSegment) -> {
            buffer.setParent(getObjectId(object)).setObjectFilter(ObjectFilter.ALL.name());
            Iterable<Item> iterable = getOwnedAndSharedFolderContentIterable(buffer, resourceType);
            for (Item item : iterable) {
              unshareObject(item, deShare, getOwner(object), buffer, resourceType);
            }
          });
    }
  }

  private void removeAllSharedItems(Item object, ResourceType resourceType) {
    resourceDao.updateCollaborators(object, new HashSet<>(), new HashSet<>());
    Iterator<Item> sharedItems =
        resourceDao.getSharedObjectsById(getObjectId(object), resourceType);
    sharedItems.forEachRemaining(share ->
        deleteObject(share.getString(Field.PK.getName()), share.getString(Field.SK.getName())));
  }

  /**
   * Update fontInfo in DB
   *
   * @param pkValue                       pk value of DB Item
   * @param skValue                       sk value of DB Item
   * @param objectData                    Additional data
   * @param resourceType                  type of resource
   * @param fontInfo                      list of object for fonts
   */
  public Item updateObject(
      String pkValue,
      String skValue,
      JsonObject objectData,
      ResourceType resourceType,
      List<Map<String, Object>> fontInfo) {
    return resourceDao.updateObject(pkValue, skValue, objectData, resourceType, fontInfo);
  }

  /**
   * Delete object using pk and sk of the item
   *
   * @param pkValue                       pk value of DB Item
   * @param skValue                       sk value of DB Item
   *
   */
  public void deleteObject(String pkValue, String skValue) {
    resourceDao.deleteObject(pkValue, skValue);
  }

  /**
   * Move object to another folder
   *
   * @param locale                       locale for translation
   * @param buffer                       ResourceBuffer
   * @param resourceType                 Current resource type
   * @param parent                       Parent folder item
   * @param object                       Item to be moved
   *
   * @throws KudoResourceException       resource exception
   */
  public void moveObject(
      String locale, ResourceBuffer buffer, ResourceType resourceType, Item parent, Item object)
      throws KudoResourceException {
    String parentObjectId =
        buffer.isRootFolder() ? rootFolderId : parent.getString(Field.OBJECT_ID.getName());

    if (getParent(object).equals(parentObjectId)) {
      throw new KudoResourceException(
          Utils.getLocalizedString(locale, ResourceErrorIds.CANNOT_MOVE_TO_SAME_FOLDER.id),
          KudoResourceErrorCodes.NOTHING_TO_MOVE,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.CANNOT_MOVE_TO_SAME_FOLDER.id);
    }

    String newParent = ResourceDAOImpl.makeSkValue(
        getOwner(object),
        parentObjectId,
        ResourceOwnerType.getType(getOwnerType(object)),
        buffer.getUserId());

    if (ResourceOwnerType.getType(getOwnerType(object)).equals(ResourceOwnerType.PUBLIC)) {
      if (!buffer.isRootFolder()
          && !ResourceOwnerType.getType(getOwnerType(parent)).equals(ResourceOwnerType.PUBLIC)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(locale, ResourceErrorIds.PUBLIC_TO_OTHER_MOVE_RESTRICTED.id),
            KudoResourceErrorCodes.OBJECT_NAME_ALREADY_EXIST,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.PUBLIC_TO_OTHER_MOVE_RESTRICTED.id);
      }
    }

    resourceDao.updateParent(object, resourceType, newParent, buffer.getUserId());
    if (!ResourceOwnerType.getType(getOwnerType(object)).equals(ResourceOwnerType.PUBLIC)) {
      Iterator<Item> sharedObjects =
          resourceDao.getSharedObjectsById(getObjectId(object), resourceType);
      sharedObjects.forEachRemaining(
          share -> resourceDao.updateParent(share, resourceType, newParent, buffer.getUserId()));
    }

    if (!buffer.isRootFolder()
        && !ResourceOwnerType.getType(getOwnerType(parent)).equals(ResourceOwnerType.PUBLIC)) {
      Set<String> editors = getObjectEditors(parent);
      if (Objects.nonNull(editors)) {
        buffer.setEditors(new ArrayList<>(editors));
      }
      Set<String> viewers = getObjectViewers(parent);
      if (Objects.nonNull(viewers)) {
        buffer.setViewers(new ArrayList<>(viewers));
      }
      shareObject(object, buffer, resourceType);
    }
  }

  /**
   * Delete object and unshare the object from collaborator
   *
   * @param object                       Item to be deleted
   *
   * @return JsonArray
   */
  public JsonArray deleteObject(Item object) {
    JsonArray collaborators = getAllCollaborators(object);
    collaborators.forEach(user -> resourceDao.unshareObject(
        object.getString(Field.PK.getName()), getParent(object), (String) user));
    resourceDao.deleteObject(
        object.getString(Field.PK.getName()), object.getString(Field.SK.getName()));
    return collaborators;
  }

  /**
   * Mark the folder as deleted
   *
   * @param folder                       folder to be set as deleted
   *
   */
  public void setFolderAsDeleted(Item folder) {
    resourceDao.markFolderAsDeleted(
        folder.getString(Field.PK.getName()), folder.getString(Field.SK.getName()));
  }

  private void hasAccess(
      Message<JsonObject> message,
      ResourceBuffer buffer,
      boolean isEditRequired,
      ObjectType objectType,
      PermissionType permission,
      Item object,
      boolean checkParent)
      throws KudoResourceException {
    if (buffer.isRootFolder()
        && Objects.isNull(buffer.getOwnerType())
        && Objects.isNull(permission)) {
      // Get root folder content where owner type is not provided
      return;
    }
    ResourceOwnerType ownerType = ResourceOwnerType.getType(buffer.getOwnerType());
    boolean hasAccess = false;
    boolean toUnShare = Objects.nonNull(permission) && permission.equals(PermissionType.UNSHARE);
    if (buffer.isRootFolder()
        || ownerType.name().equalsIgnoreCase(object.getString(Field.OWNER_TYPE.getName()))) {
      if (ownerType.equals(ResourceOwnerType.OWNED)) {
        if (buffer.isRootFolder()
            || buffer.getOwnerId().equals(object.getString(Field.OWNER_ID.getName()))) {
          hasAccess = true;
        }
      } else if (ownerType.equals(ResourceOwnerType.ORG)) {
        boolean isUnsharedMember = false;
        if (!buffer.isRootFolder() && object.hasAttribute(Field.UNSHARED_MEMBERS.getName())) {
          List<String> unsharedMembers = object.getList(Field.UNSHARED_MEMBERS.getName());
          if (unsharedMembers.contains(buffer.getUserId())) {
            isUnsharedMember = true;
          }
        }
        if (!isUnsharedMember
            && (buffer.isRootFolder()
                || buffer.getOwnerId().equals(object.getString(Field.OWNER_ID.getName())))) {
          if (!isEditRequired) {
            hasAccess = true;
          } else {
            Item user = Users.getUserById(buffer.getUserId());
            if (Users.isOrgAdmin(user, buffer.getOrganizationId())) {
              if (toUnShare) {
                message.body().put(Field.CAN_DELETE.getName(), true);
                return;
              }
              hasAccess = true;
            } else if (toUnShare
                && Users.isUserPartOfOrganization(user, buffer.getOrganizationId())) {
              message.body().put(Field.UNSHARED_FROM_ORG.getName(), true);
              return;
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
      } else if (ownerType.equals(ResourceOwnerType.SHARED)) {
        if (checkParent || Objects.isNull(permission) || !permission.equals(PermissionType.MOVE)) {
          Set<String> editors = getObjectEditors(object);
          Set<String> viewers = getObjectViewers(object);
          boolean canUnShare = false, hasOnlyViewAccess = false;
          if (Objects.nonNull(editors) && editors.contains(buffer.getUserId())) {
            hasAccess = true;
            canUnShare = true;
          } else {
            if (Objects.nonNull(viewers) && viewers.contains(buffer.getUserId())) {
              if (!isEditRequired || (toUnShare)) {
                hasAccess = true;
              }
              hasOnlyViewAccess = true;
              canUnShare = true;
            }
          }
          if (canUnShare
              && (Objects.nonNull(permission) && permission.equals(PermissionType.DELETE))) {
            message.body().put(Field.SELF_UNSHARE_ACCESS.getName(), true);
            return;
          }
          if (!hasAccess && !hasOnlyViewAccess) {
            // check if the org has access and is the user org-admin
            if (buffer.isOrganizationObject()
                && Objects.nonNull(buffer.getOrganizationId())
                && (Objects.nonNull(editors) && editors.contains(buffer.getOrganizationId())
                    || (Objects.nonNull(viewers)
                        && viewers.contains(buffer.getOrganizationId())))) {
              if (!isEditRequired) {
                hasAccess = true;
              } else {
                Item user = Users.getUserById(buffer.getUserId());
                if (Users.isOrgAdmin(user, buffer.getOrganizationId())) {
                  hasAccess = true;
                }
              }
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

  /**
   * Verify that user has access to this object or not
   *
   * @param message                      Vertx message object
   * @param buffer                       Resource Buffer
   * @param objectType                   object type (File/Folder)
   * @param isEditRequired               Is the action required editing access
   * @param permission                   permission/action to be checked
   * @param resourceType                 type of the resource
   * @param checkParent                  Is parent folder validation is required
   *
   * @return Item
   * @throws KudoResourceException resource exception
   */
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
      object = checkAndGetObject(message, buffer, resourceType);
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
    if (!buffer.isRootFolder()
        && (Objects.isNull(buffer.getOwnerType())
            || !isOwnerValid(buffer.getOwnerId(), buffer.getOwnerType()))) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.INVALID_OWNER_INFO.id),
          KudoResourceErrorCodes.INVALID_OWNER,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.INVALID_OWNER_INFO.id);
    }
    // check for user access
    hasAccess(
        message,
        buffer,
        isEditRequired,
        ObjectType.getType(objectType),
        permission,
        object,
        checkParent);
    return object;
  }

  /**
   * Get the resource file from s3
   *
   * @param message                      Vertx message object
   * @param s3Regional                   S3Regional instance to access s3
   * @param object                       Current DB object
   * @param objectId                     ID of the object to be deleted
   *
   * @throws KudoResourceException resource exception
   */
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
          log.error("Error in getting resource file from s3 " + ex.getLocalizedMessage());
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
      log.error("Error in getting files from S3 for creating zip " + e.getLocalizedMessage());
      result = new byte[0];
    }
    return result;
  }

  /**
   * Upload the resource file to s3
   *
   * @param s3Regional                   S3Regional instance to access s3
   * @param content                      byte array of the file
   * @param s3Path                       path for the file to be uploaded in s3
   *
   */
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

  /**
   * Delete the resource file from s3
   *
   * @param s3Regional                   S3Regional instance to access s3
   * @param object                       Current DB object
   * @param objectId                     ID of the object to be deleted
   *
   */
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
        log.error("Error in deleting resource file from s3 " + ex.getLocalizedMessage());
      }
    } else {
      log.error("Resource Item does not have s3 Path for objectId " + objectId);
    }
  }

  /**
   * Update the resource file content in s3
   *
   * @param parentSegment                Xray parent segment
   * @param message                      Vertx message object
   * @param s3Regional                   S3Regional instance to access s3
   * @param object                       Current DB object
   * @param objectData                   Data
   * @param content                      byte array for file
   *
   * @return boolean
   */
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
        log.error("Update object data does not have s3 Path for objectId " + getObjectId(object));
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
                deleteFileFromS3(s3Regional, object, getObjectId(object)));
      }

      if (!objectData.containsKey(Field.FILE_NAME_C.getName())) {
        objectData.remove(Field.S3_PATH.getName());
        objectData.remove(Field.OLD_S3_PATH.getName());
      }
      return true;
    }
    log.error("Resource file content is null for objectId " + getObjectId(object));
    return false;
  }

  /**
   * Download the requested folder
   *
   * @param message               Vertx message object
   * @param s3Regional            S3Regional instance to access S3 bucket
   * @param requestId             ID to find download DB entry
   * @param buffer                Resource buffer
   * @param recursive             True if you want to download the inner levels of folder recursively
   * @param resourceType          Type of the resource
   *
   * @throws KudoResourceException resource exception
   */
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
      if (isFolder(resourceBuffer) && recursive) {
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

  /**
   * Finish the file download
   *
   * @param message               Vertx message object
   * @param downloadToken         download token
   * @param result                byte array of the content to download
   *
   */
  public void finishGetFile(Message<JsonObject> message, String downloadToken, byte[] result) {
    if (downloadToken != null) {
      DownloadRequests.setRequestData(downloadToken, result, JobStatus.SUCCESS, null, null);
    }
    message.reply(result);
  }

  /**
   * Finish the folder download
   *
   * @param message               Vertx message object
   * @param s3Regional            S3Regional instance to access s3 bucket
   * @param downloadToken         download token
   * @param request               download request item from DB
   * @param bos                   stream of the content to download
   *
   */
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

  /**
   * Get array of path structure for the folder
   *
   * @param message               Vertx message object
   * @param buffer                ResourceBuffer
   * @param resourceType          Type of the resource
   *
   * @return JsonArray
   * @throws KudoResourceException resource exception
   */
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
      Item object = resourceDao.getObjectById(parent, resourceType, buffer);
      if (Objects.isNull(object) || Objects.isNull(getParent(object))) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_PATH_FOR_FOLDER.id),
            KudoResourceErrorCodes.INVALID_FOLDER_PATH,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_PATH_FOR_FOLDER.id);
      }
      path.add(new JsonObject()
          .put(Field.NAME.getName(), getName(object))
          .put(Field.ENCAPSULATED_ID.getName(), parent)
          .put(Field.VIEW_ONLY.getName(), false));
      parent = getParent(object);
    }
    return path;
  }

  /**
   * Share the object with parent collaborators recursively
   *
   * @param folder                Parent folder from which the collaborators to be inherited
   * @param object                Current object to which the collaborators to be updated
   * @param buffer                ResourceBuffer
   * @param resourceType          Type of the resource
   */
  public void shareWithFolderCollaborators(
      Item folder, Item object, ResourceBuffer buffer, ResourceType resourceType) {
    Set<String> editors = getObjectEditors(folder);
    if (Objects.nonNull(editors)) {
      editors.add(getOwner(folder));
      buffer.setEditors(new ArrayList<>(editors));
    }
    Set<String> viewers = getObjectViewers(folder);
    if (Objects.nonNull(viewers)) {
      buffer.setViewers(new ArrayList<>(viewers));
    }
    shareObject(object, buffer, resourceType);
  }

  /**
   * Delete the folder content recursively
   *
   * @param buffer                ResourceBuffer
   * @param parentOwnerId         ownerId of the parent folder to check if parent needs to be updated
   * @param resourceType          Type of the resource
   */
  public void deleteFolderContent(
      ResourceBuffer buffer, String parentOwnerId, ResourceType resourceType) {
    Iterable<Item> iterable = getOwnedAndSharedFolderContentIterable(buffer, resourceType);
    String finalParentOwnerId = parentOwnerId;
    for (Item object : iterable) {
      if (!getOwner(object).equals(buffer.getOwnerId())) {
        if (Objects.isNull(finalParentOwnerId)) {
          Item parent = resourceDao.getObjectById(
              getParent(object), resourceType, new ResourceBuffer(), false);
          if (Objects.nonNull(parent)) {
            finalParentOwnerId = getOwner(parent);
          }
        }
        // remove access for the parent's owner from the object
        unshareObject(
            object, new JsonArray().add(buffer.getOwnerId()), getOwner(object), null, resourceType);
        if (Objects.nonNull(finalParentOwnerId) && !finalParentOwnerId.equals(getOwner(object))) {
          Iterator<Item> allItems =
              resourceDao.getAllObjectsById(getObjectId(object), resourceType);
          // move the object the owner's root folder
          allItems.forEachRemaining(item ->
              resourceDao.updateParent(item, resourceType, rootFolderId, buffer.getUserId()));
        }
      } else {
        deleteObject(object);
      }
      if (isFolder(object)) {
        buffer.setParent(getObjectId(object));
        deleteFolderContent(buffer, getOwner(object), resourceType);
      }
    }
  }

  private static Set<String> getObjectEditors(Item object) {
    if (Objects.isNull(object)) {
      return null;
    }
    getOriginalObjectIfShared(object);
    return object.hasAttribute(Field.EDITORS.getName())
        ? object.getStringSet(Field.EDITORS.getName())
        : null;
  }

  private static Set<String> getObjectViewers(Item object) {
    if (Objects.isNull(object)) {
      return null;
    }
    getOriginalObjectIfShared(object);
    return object.hasAttribute(Field.VIEWERS.getName())
        ? object.getStringSet(Field.VIEWERS.getName())
        : null;
  }

  private static void getOriginalObjectIfShared(Item object) {
    if (object.getString(Field.SK.getName()).startsWith(ResourceOwnerType.SHARED.name())
        && !object.hasAttribute(Field.VIEWERS.getName())
        && !object.hasAttribute(Field.EDITORS.getName())) {
      Item originalObject = resourceDao.getObjectById(
          getObjectId(object),
          ResourceType.getType(object.getString(Field.RESOURCE_TYPE.getName())),
          new ResourceBuffer());
      if (Objects.nonNull(originalObject)) {
        if (originalObject.hasAttribute(Field.VIEWERS.getName())) {
          object.withStringSet(
              Field.VIEWERS.getName(), originalObject.getStringSet(Field.VIEWERS.getName()));
        }
        if (originalObject.hasAttribute(Field.EDITORS.getName())) {
          object.withStringSet(
              Field.EDITORS.getName(), originalObject.getStringSet(Field.EDITORS.getName()));
        }
      }
    }
  }

  public static JsonArray getAllCollaborators(Item object) {
    Set<String> editors = getObjectEditors(object);
    Set<String> viewers = getObjectViewers(object);
    Set<String> collaborators = new HashSet<>();
    if (Objects.nonNull(editors)) {
      collaborators.addAll(editors);
    }
    if (Objects.nonNull(viewers)) {
      collaborators.addAll(viewers);
    }
    return new JsonArray(new ArrayList<>(collaborators));
  }

  public static String getName(Item object) {
    return object.getString(Field.NAME.getName());
  }

  public static String getParent(Item object) {
    return object.getString(Field.PARENT.getName());
  }

  public static String getOwnerType(Item object) {
    return object.getString(Field.OWNER_TYPE.getName());
  }

  public static String getOwner(Item object) {
    return object.getString(Field.OWNER_ID.getName());
  }

  public static String getObjectId(Item object) {
    return object.getString(Field.OBJECT_ID.getName());
  }

  /**
   * check if the resource object is a file or not (Default true)
   *
   * @param <T>                Item or JsonObject or ResourceBuffer to check
   * @return boolean
   */
  public static <T> boolean isFile(T object) {
    try {
      if (object instanceof Item) {
        return ((Item) object)
            .getString(Field.OBJECT_TYPE.getName())
            .equalsIgnoreCase(ObjectType.FILE.name());
      } else if (object instanceof JsonObject) {
        return ((JsonObject) object)
            .getString(Field.OBJECT_TYPE.getName())
            .equalsIgnoreCase(ObjectType.FILE.name());
      } else if (object instanceof ResourceBuffer) {
        return ((ResourceBuffer) object).getObjectType().equalsIgnoreCase(ObjectType.FILE.name());
      } else {
        return true;
      }
    } catch (NullPointerException npe) {
      return true;
    }
  }

  /**
   * check if the resource object is a folder or not (Default false)
   *
   * @param <T>                Item or String or ResourceBuffer to check
   * @return boolean
   */
  public static <T> boolean isFolder(T object) {
    try {
      if (object instanceof Item) {
        return ((Item) object)
            .getString(Field.OBJECT_TYPE.getName())
            .equalsIgnoreCase(ObjectType.FOLDER.name());
      } else if (object instanceof String) {
        return ((String) object).equalsIgnoreCase(ObjectType.FOLDER.name());
      } else if (object instanceof ResourceBuffer) {
        return ((ResourceBuffer) object).getObjectType().equalsIgnoreCase(ObjectType.FOLDER.name());
      } else {
        return false;
      }
    } catch (NullPointerException npe) {
      return false;
    }
  }
}
