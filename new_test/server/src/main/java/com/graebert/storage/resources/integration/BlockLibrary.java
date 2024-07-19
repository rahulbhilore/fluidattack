package com.graebert.storage.resources.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
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
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import kong.unirest.HttpStatus;
import org.jetbrains.annotations.NonNls;

public class BlockLibrary extends BaseResource implements Resource {
  @NonNls
  public static final String address = "blocklibrary";

  protected static final OperationGroup operationGroup = OperationGroup.BLOCK_LIBRARIES;
  private static final ResourceType resourceType = ResourceType.BLOCKS;
  public static JsonArray supportedFileTypes;
  private static S3Regional s3Regional, userS3;

  public BlockLibrary() {}

  @Override
  public void start() throws Exception {
    super.start();

    String bucket = config.getProperties().getS3Bucket();
    String resourcesBucket = config.getProperties().getS3ResourcesBucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, resourcesBucket, region);
    userS3 = new S3Regional(config, bucket, region);
    supportedFileTypes = config.getProperties().getBlocksSupportedFileTypesAsArray();

    eb.consumer(address + ".getFolderContent", this::doGetFolderContent);
    eb.consumer(address + ".createObject", this::doCreateObject);
    eb.consumer(address + ".updateObject", this::doUpdateObject);
    eb.consumer(address + ".shareObject", this::doShareObject);
    eb.consumer(address + ".deShareObject", this::doDeShareObject);
    eb.consumer(address + ".moveObject", this::doMoveObject);
    eb.consumer(address + ".deleteObject", this::doDeleteObject);
    eb.consumer(address + ".getObjectInfo", this::doGetObjectInfo);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".downloadObject", this::doDownloadObject);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-res-blockLibrary");
  }

  public void doGetFolderContent(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      boolean returnOwnedAndShared = !Utils.isStringNotNullOrEmpty(buffer.getOwnerType());

      verifyAccess(message, buffer, ObjectType.FOLDER.name(), false, null, resourceType, true);

      JsonObject items;
      if (returnOwnedAndShared) {
        if (buffer.getParent().equals(rootFolderId)) {
          items = getAllRootFolderItems(buffer);
        } else {
          items = getOwnedAndSharedFolderContent(buffer, resourceType);
        }
      } else {
        Iterator<Item> ownedIterator = getFolderContent(buffer, resourceType);
        items = processFolderItems(
            ownedIterator,
            ObjectFilter.getType(buffer.getObjectFilter()),
            buffer.getUserId(),
            true);
      }

      JsonArray files = items.getJsonArray(Field.FILES.getName());
      if (Utils.isJsonArrayNotNullOrEmpty(files)) {
        JsonArray requiredThumbnails = new JsonArray();

        List<JsonObject> finalFiles = files.stream()
            .map(obj -> {
              JsonObject file = (JsonObject) obj;
              requiredThumbnails.add(new JsonObject()
                  .put(Field.REQUEST_ID.getName(), Utils.generateUUID())
                  .put(Field.ID.getName(), file.getString(Field.ID.getName()))
                  .put(
                      Field.EXT.getName(),
                      Extensions.getExtension(file.getString(Field.NAME.getName())))
                  .put(Field.SOURCE.getName(), file.getString(Field.S3_PATH.getName()))
                  .put(Field.VERSION_ID.getName(), file.getLong(Field.UPDATED.getName())));
              file.remove(Field.S3_PATH.getName());
              return file;
            })
            .collect(Collectors.toList());

        items.put(Field.FILES.getName(), finalFiles);

        // generate thumbnails
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("createBlockThumbnails")
            .run((Segment blockingSegment) -> eb_send(
                blockingSegment,
                ThumbnailsManager.address + ".createForCustom",
                new JsonObject()
                    .put(Field.IDS.getName(), requiredThumbnails)
                    .put(Field.S3_BUCKET.getName(), s3Regional.getBucketName())
                    .put(Field.S3_REGION.getName(), s3Regional.getRegion())
                    .put(Field.STORAGE_TYPE.getName(), Field.BL.getName())));
      }

      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), items), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getFolderContent", System.currentTimeMillis() - mills);
  }

  private JsonObject getAllRootFolderItems(ResourceBuffer buffer) {
    Iterator<Item> ownedIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.OWNED.name()), resourceType);
    JsonObject items = processFolderItems(
        ownedIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);

    Iterator<Item> sharedIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.SHARED.name()), resourceType);
    if (sharedIterator.hasNext()) {
      JsonObject sharedItems = processFolderItems(
          sharedIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);
      concatFolderItems(items, sharedItems);
    }
    // to reset ownerId for ORG while getting folder content
    buffer.setOwnerId(null);
    Iterator<Item> orgIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.ORG.name()), resourceType);
    if (orgIterator.hasNext()) {
      JsonObject orgItems = processFolderItems(
          orgIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);
      concatFolderItems(items, orgItems);
    }

    Iterator<Item> publicIterator =
        getFolderContent(buffer.setOwnerType(ResourceOwnerType.PUBLIC.name()), resourceType);
    if (publicIterator.hasNext()) {
      JsonObject publicItems = processFolderItems(
          publicIterator, ObjectFilter.getType(buffer.getObjectFilter()), buffer.getUserId(), true);
      concatFolderItems(items, publicItems);
    }
    return items;
  }

  public void doCreateObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    String objectId = Utils.generateUUID();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      buffer.setObjectId(objectId);

      Item folder = verifyAccess(
          message, buffer, buffer.getObjectType(), true, PermissionType.CREATE, resourceType, true);

      checkResourceNameExists(message, buffer, resourceType, buffer.getName());

      JsonObject objectData = makeCreateObjectData(resourceType, buffer, folder);
      if (isFile(buffer) && objectData.containsKey(Field.S3_PATH.getName())) {
        uploadFileToS3(
            s3Regional, buffer.getFileContent(), objectData.getString(Field.S3_PATH.getName()));
        log.info("New block file uploaded with id - " + objectId);
      }
      Item object = createObject(buffer, resourceType, objectData);
      shareWithFolderCollaborators(folder, object, buffer, resourceType);

      sendOK(segment, message, new JsonObject().put(Field.OBJECT_ID.getName(), objectId), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("createObject", System.currentTimeMillis() - mills);
  }

  public void doUpdateObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);

      Item object = verifyAccess(
          message,
          buffer,
          buffer.getObjectType(),
          true,
          PermissionType.UPDATE,
          resourceType,
          false);

      if (Utils.isStringNotNullOrEmpty(buffer.getName())) {
        checkResourceNameExists(message, buffer, resourceType, buffer.getName());
      }
      JsonObject objectData = makeUpdateObjectData(buffer, object);
      Item updatedObject = null;
      boolean isObjectUpdated = false;
      if (isFile(buffer)) {
        isObjectUpdated =
            updateS3File(segment, message, s3Regional, object, objectData, buffer.getFileContent());
      }
      if (objectData.isEmpty()) {
        if (isObjectUpdated) {
          updatedObject = object;
        } else {
          throw new KudoResourceException(
              Utils.getLocalizedString(message, ResourceErrorIds.NOTHING_TO_UPDATE.id),
              KudoResourceErrorCodes.NOTHING_TO_UPDATE,
              HttpStatus.PRECONDITION_FAILED,
              ResourceErrorIds.NOTHING_TO_UPDATE.id);
        }
      }
      if (Objects.isNull(updatedObject)) {
        updatedObject = updateObject(
            object.getString(Dataplane.pk),
            object.getString(Dataplane.sk),
            objectData,
            resourceType);
      }
      sendOK(segment, message, itemToJson(updatedObject, buffer.getUserId(), false), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("updateObject", System.currentTimeMillis() - mills);
  }

  public void doShareObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object = verifyAccess(
          message, buffer, buffer.getObjectType(), true, PermissionType.SHARE, resourceType, false);

      JsonObject collaborators = shareObject(object, buffer, resourceType);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.COLLABORATORS.getName(), collaborators)
              .put(Field.PARENT.getName(), getParent(object))
              .put(Field.NAME.getName(), getName(object))
              .put(Field.THUMBNAIL.getName(), getThumbnailUrlForBlock(object)),
          mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("shareObject", System.currentTimeMillis() - mills);
  }

  public void doDeShareObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    JsonArray deShare = jsonObject.getJsonArray(Field.DE_SHARE.getName());
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object = verifyAccess(
          message,
          buffer,
          buffer.getObjectType(),
          true,
          PermissionType.UNSHARE,
          resourceType,
          false);

      if (message.body().getBoolean(Field.CAN_DELETE.getName(), false)) {
        message.body().put(Field.HAS_ACCESS.getName(), true).put("object", object.toJSON());
        doDeleteObject(message);
        return;
      }

      if (message.body().getBoolean(Field.UNSHARED_FROM_ORG.getName(), false)) {
        addToUnsharedUsersForOrg(object, buffer.getUserId());
        sendOK(segment, message, mills);
        return;
      }

      unshareObject(object, deShare, null, buffer, resourceType);

      sendOK(segment, message, new JsonObject().put(Field.NAME.getName(), getName(object)), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("deShareObject", System.currentTimeMillis() - mills);
  }

  public void doMoveObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item parent = verifyAccess(
          message, buffer, buffer.getObjectType(), true, PermissionType.MOVE, resourceType, true);

      Item object = verifyAccess(
          message, buffer, buffer.getObjectType(), true, PermissionType.MOVE, resourceType, false);

      moveObject(
          jsonObject.getString(Field.LOCALE.getName()), buffer, resourceType, parent, object);
      sendOK(segment, message, mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("moveObject", System.currentTimeMillis() - mills);
  }

  public void doDeleteObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object;
      if (message.body().getBoolean(Field.HAS_ACCESS.getName(), false)) {
        object = Item.fromJSON(message.body().getString("object"));
      } else {
        object = verifyAccess(
            message,
            buffer,
            buffer.getObjectType(),
            true,
            PermissionType.DELETE,
            resourceType,
            false);
      }
      if (message.body().getBoolean(Field.SELF_UNSHARE_ACCESS.getName(), false)) {
        buffer.setResourceType(resourceType.name());
        unshareObject(object, new JsonArray().add(buffer.getUserId()), null, buffer, resourceType);
        sendOK(segment, message, mills);
        return;
      }
      if (isFile(buffer)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("deleteFileFromS3")
            .runWithoutSegment(() -> deleteFileFromS3(s3Regional, object, buffer.getObjectId()));
      }
      JsonArray collaborators;
      if (isFile(buffer)) {
        collaborators = deleteObject(object);
      } else {
        setFolderAsDeleted(object);
        collaborators = getAllCollaborators(object);
      }
      if (isFolder(object)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("deleteBlockLibraryFolderContent")
            .runWithoutSegment(() -> {
              buffer.setParent(getObjectId(object));
              deleteFolderContent(buffer, null, resourceType);
              // finally deleting the main folder after removing its sub-items
              deleteObject(object);
            });
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.COLLABORATORS.getName(), collaborators)
              .put(Field.NAME.getName(), getName(object)),
          mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("deleteObject", System.currentTimeMillis() - mills);
  }

  public void doGetObjectInfo(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object =
          verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      sendOK(segment, message, itemToJson(object, buffer.getUserId(), true), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      JsonArray path = getFolderPath(message, buffer, resourceType);

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), path), mills);
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getFolderPath", System.currentTimeMillis() - mills);
  }

  public void doDownloadObject(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    boolean recursive = jsonObject.getBoolean(Field.RECURSIVE.getName());
    try {
      ResourceBuffer buffer = ResourceBuffer.jsonObjectToResource(jsonObject);
      Item object =
          verifyAccess(message, buffer, buffer.getObjectType(), false, null, resourceType, false);
      if (isFile(buffer)) {
        byte[] content = getFileFromS3(message, s3Regional, object, buffer.getObjectId());
        finishGetFile(message, buffer.getDownloadToken(), content);
      } else {
        ByteArrayOutputStream bos = downloadFolder(
            message, s3Regional, buffer.getDownloadToken(), buffer, recursive, resourceType);
        Item request = ZipRequests.getZipRequest(
            buffer.getUserId(), buffer.getObjectId(), buffer.getDownloadToken());
        finishGetFolder(message, userS3, buffer.getDownloadToken(), request, bos);
      }
      log.info("Download is complete for" + buffer.getObjectType().toLowerCase() + " - "
          + buffer.getObjectId());
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("downloadObject", System.currentTimeMillis() - mills);
  }

  private String getThumbnailUrlForBlock(Item object) {
    try {
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          Field.BL.getName(),
          object.getString(Field.OBJECT_ID.getName()),
          String.valueOf(
              object.hasAttribute(Field.UPDATED.getName())
                  ? object.getLong(Field.UPDATED.getName())
                  : object.getLong(Field.CREATED.getName())));
      return ThumbnailsManager.getThumbnailURL(config, thumbnailName, true);
    } catch (Exception ex) {
      log.error("[RESOURCES] Something went wrong in getting thumbnail url for block : " + ex);
      return emptyString;
    }
  }
}
