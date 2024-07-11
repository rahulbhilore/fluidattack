package com.graebert.storage.resources;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.resources.integration.BaseResource;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.TimeoutException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import kong.unirest.HttpStatus;
import org.jetbrains.annotations.NonNls;

public class ResourceModule extends DynamoBusModBase {
  @NonNls
  public static final String address = "resourcemodule";

  private static final OperationGroup operationGroup = OperationGroup.RESOURCES;

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getFolderContent", this::doGetFolderContent);
    eb.consumer(address + ".createObject", this::doCreateObject);
    eb.consumer(address + ".updateObject", this::doUpdateObject);
    eb.consumer(address + ".deleteObjects", this::doDeleteObjects);
    eb.consumer(address + ".getObjectInfo", this::doGetObjectInfo);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".requestDownload", this::doRequestDownload);
    eb.consumer(address + ".getDownload", this::doGetDownload);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-resourceModule");

    breaker = CircuitBreaker.create(
        "vert.x-circuit-breaker-resourceModule",
        vertx,
        new CircuitBreakerOptions().setTimeout(20000).setMaxRetries(0).setMaxFailures(2));
  }

  private void doGetFolderContent(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }

      ResourceBuffer resource = new ResourceBuffer();
      resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
      resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
      resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
      resource.setParent(jsonObject.getString(Field.FOLDER_ID.getName()));
      resource.setObjectFilter(jsonObject.getString("objectFilter"));
      JsonObject data = ResourceBuffer.toJson(resource);

      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      eb_request(
          segment,
          ResourceType.getAddress(resourceType) + ".getFolderContent",
          data,
          event -> handleExternal(segment, event, message, mills));
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("getFolderContent", System.currentTimeMillis() - mills);
  }

  private void doCreateObject(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);

    ResourceBuffer resource = new ResourceBuffer();
    resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
    resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
    resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
    resource.setParent(jsonObject.getString(Field.FOLDER_ID.getName()));
    resource.setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()));
    resource.setName(jsonObject.getString(Field.NAME.getName()));
    resource.setDescription(jsonObject.getString(Field.DESCRIPTION.getName()));
    String fileName = jsonObject.getString(Field.FILE_NAME_C.getName());
    if (jsonObject
        .getString(Field.OBJECT_TYPE.getName())
        .equalsIgnoreCase(ObjectType.FILE.name())) {
      resource.setFileContent(jsonObject.getBinary(Field.FILE_CONTENT.getName()));
      resource.setFileName(fileName);
      resource.setFileType(Extensions.getExtension(fileName).substring(1).toUpperCase());
      resource.setFileSize(jsonObject.getInteger(Field.FILE_SIZE.getName()));
    }
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (Utils.isStringNotNullOrEmpty(fileName)) {
        BaseResource.checkAllowedResourceFileTypes(
            message, resourceType, Extensions.getExtension(fileName).substring(1));
      }
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      eb_request(
          segment,
          ResourceType.getAddress(resourceType) + ".createObject",
          data,
          event -> handleExternal(segment, event, message, mills));
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("createObject", System.currentTimeMillis() - mills);
  }

  private void doUpdateObject(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);

    ResourceBuffer resource = new ResourceBuffer();
    resource.setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()));
    resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
    resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
    resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
    resource.setParent(jsonObject.getString(Field.FOLDER_ID.getName()));
    resource.setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()));
    resource.setName(jsonObject.getString(Field.NAME.getName()));
    resource.setDescription(jsonObject.getString(Field.DESCRIPTION.getName()));
    String fileName = null;
    if (jsonObject.getString(Field.OBJECT_TYPE.getName()).equalsIgnoreCase(ObjectType.FILE.name())
        && jsonObject.containsKey(Field.FILE_CONTENT.getName())) {
      fileName = jsonObject.getString(Field.FILE_NAME_C.getName());
      resource.setFileContent(jsonObject.getBinary(Field.FILE_CONTENT.getName()));
      resource.setFileName(fileName);
      resource.setFileType(Extensions.getExtension(fileName).substring(1).toUpperCase());
      resource.setFileSize(jsonObject.getInteger(Field.FILE_SIZE.getName()));
    }
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (Utils.isStringNotNullOrEmpty(fileName)) {
        String fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
        BaseResource.checkAllowedResourceFileTypes(message, resourceType, fileExtension);
      }
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      eb_request(
          segment,
          ResourceType.getAddress(resourceType) + ".updateObject",
          data,
          event -> handleExternal(segment, event, message, mills));
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("updateObject", System.currentTimeMillis() - mills);
  }

  private void doDeleteObjects(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String ownerId = jsonObject.getString(Field.OWNER_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    JsonArray objects = jsonObject.getJsonArray("objects");
    try {
      if (Objects.isNull(objects) || objects.isEmpty()) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.NOTHING_TO_DELETE.id),
            KudoResourceErrorCodes.NOTHING_TO_DELETE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.NOTHING_TO_DELETE.id);
      }
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
      return;
    }
    List<JsonObject> objectsToDelete = new ArrayList<>();
    List<Future<String>> futures = new ArrayList<>();
    JsonArray errors = new JsonArray();
    try {
      for (Object obj : objects) {
        JsonObject object = (JsonObject) obj;
        ResourceBuffer buffer = new ResourceBuffer();
        if (Objects.nonNull(ownerId)) {
          buffer.setOwnerId(ownerId);
        } else {
          buffer.setOwnerId(userId);
        }
        buffer.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
        buffer.setUserId(userId);
        buffer.setObjectId(object.getString(Field.ID.getName()));
        buffer.setObjectType(object.getString(Field.OBJECT_TYPE.getName()));

        getObjectsToDelete(message, buffer, objectsToDelete, resourceType);
      }
      for (JsonObject data : objectsToDelete) {
        Promise<String> promise = Promise.promise();
        eb_request(
            segment,
            ResourceType.getAddress(resourceType) + ".deleteObject",
            data,
            event -> handleObjectDeleteResponse(
                promise, event, data.getString(Field.OBJECT_ID.getName()), errors));
        futures.add(promise.future());
      }
      TypedCompositeFuture.join(futures).onComplete(event -> {
        if (event.succeeded()) {
          sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
        } else {
          if (Objects.nonNull(event.cause())) {
            if (!errors.isEmpty() && event.cause().getLocalizedMessage().isEmpty()) {
              sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
            } else {
              String[] error = event.cause().getLocalizedMessage().split("&");
              if (error.length == 3) {
                int statusCode = Integer.parseInt(error[0]);
                String errorId = error[1];
                String errorMessage = error[2];
                sendError(segment, message, errorMessage, statusCode, errorId);
                return;
              }
              sendError(
                  segment, message, event.cause().getLocalizedMessage(), HttpStatus.BAD_REQUEST);
            }
          } else {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "SomethingWentWrongForResources"),
                HttpStatus.INTERNAL_SERVER_ERROR);
          }
        }
      });
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("deleteObjects", System.currentTimeMillis() - mills);
  }

  private void doGetObjectInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    ResourceBuffer resource = new ResourceBuffer();
    resource.setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()));
    resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
    resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
    resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
    resource.setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()));
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      eb_request(
          segment,
          ResourceType.getAddress(resourceType) + ".getObjectInfo",
          data,
          event -> handleExternal(segment, event, message, mills));
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
  }

  private void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    ResourceBuffer resource = new ResourceBuffer();
    resource.setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()));
    resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
    resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
    resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
    resource.setObjectType(ObjectType.FOLDER.name());
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      eb_request(
          segment,
          ResourceType.getAddress(resourceType) + ".getFolderPath",
          data,
          event -> handleExternal(segment, event, message, mills));
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("getFolderPath", System.currentTimeMillis() - mills);
  }

  private void doRequestDownload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);

    ResourceBuffer resource = new ResourceBuffer();
    resource.setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()));
    resource.setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()));
    resource.setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()));
    resource.setUserId(jsonObject.getString(Field.USER_ID.getName()));
    resource.setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()));
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }

      String requestId;
      if (resource.getObjectType().equalsIgnoreCase(ObjectType.FILE.name())) {
        requestId = DownloadRequests.createResourceRequest(
            resource.getUserId(), resource.getObjectId(), resourceType);
      } else {
        requestId = ZipRequests.createResourceZipRequest(
            resource.getUserId(), resource.getObjectId(), resourceType);
      }

      breaker
          .execute(promise -> eb_request(
              segment,
              ResourceType.getAddress(resourceType) + ".downloadObject",
              data.put(Field.DOWNLOAD_TOKEN.getName(), requestId)
                  .put(Field.RECURSIVE.getName(), jsonObject.getBoolean(Field.RECURSIVE.getName())),
              event -> {
                if (event.succeeded() && event.result() != null) {
                  promise.complete(event.result().body());
                } else {
                  if (event.cause() == null) {
                    if (event.result() != null) {
                      sendError(segment, message, event.result());
                    }
                    promise.fail(emptyString);
                    DownloadRequests.setRequestData(
                        requestId,
                        null,
                        JobStatus.ERROR,
                        event.result() != null ? event.result().body().toString() : emptyString,
                        null);
                    return;
                  }
                  if (event.cause().toString().contains("TIMEOUT")) {
                    return;
                  }
                  DownloadRequests.setRequestData(
                      requestId, null, JobStatus.ERROR, event.cause().getLocalizedMessage(), null);
                  promise.fail(event.cause());
                }
              }))
          .onSuccess(res -> {
            message.reply(res);
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("deleteDownloadRequests")
                .run((Segment blockingSegment) -> deleteDownloadRequests(
                    requestId,
                    resource.getUserId(),
                    resource.getObjectId(),
                    ObjectType.getType(resource.getObjectType())));
            XRayManager.endSegment(segment);
          })
          .onFailure(e -> {
            if (e instanceof TimeoutException) {
              sendOK(
                  segment,
                  message,
                  201,
                  new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId),
                  mills);
            } else {
              if (Utils.isStringNotNullOrEmpty(e.getLocalizedMessage())) {
                log.error(e.getLocalizedMessage());
                sendError(
                    segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
              }
            }
          });
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    }
    recordExecutionTime("requestDownload", System.currentTimeMillis() - mills);
  }

  private void doGetDownload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String objectId = jsonObject.getString(Field.OBJECT_ID.getName());
    String objectType = jsonObject.getString(Field.OBJECT_TYPE.getName());
    try {
      if (!jsonObject.containsKey(Field.DOWNLOAD_TOKEN.getName())
          || !Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.DOWNLOAD_TOKEN.getName()))) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_DOWNLOAD_TOKEN.id),
            KudoResourceErrorCodes.INVALID_DOWNLOAD_TOKEN,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_DOWNLOAD_TOKEN.id);
      }
      String downloadToken = jsonObject.getString(Field.DOWNLOAD_TOKEN.getName());
      Item request;
      if (objectType.equalsIgnoreCase(ObjectType.FOLDER.name())) {
        request = ZipRequests.getZipRequest(userId, objectId, downloadToken);
      } else {
        request = DownloadRequests.getRequest(downloadToken);
      }
      if (request == null
          || !request.getString(Field.USER_ID.getName()).equals(userId)
          || !request.getString(Field.OBJECT_ID.getName()).equals(objectId)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, "UnknownRequestToken"),
            KudoResourceErrorCodes.OBJECT_DOWNLOAD_ERROR,
            HttpStatus.BAD_REQUEST,
            "UnknownRequestToken");
      }
      @NonNls JobStatus status = JobStatus.findStatus(request.getString(Field.STATUS.getName()));
      if (status.equals(JobStatus.IN_PROGRESS)) {
        sendOK(segment, message, 202, System.currentTimeMillis() - mills);
        return;
      }
      if (status.equals(JobStatus.ERROR) || status.equals(JobStatus.UNKNOWN)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("deleteDownloadRequests")
            .runWithoutSegment(() -> deleteDownloadRequests(
                downloadToken, userId, objectId, ObjectType.getType(objectType)));
        throw new KudoResourceException(
            request.getString(Field.ERROR.getName()),
            KudoResourceErrorCodes.OBJECT_DOWNLOAD_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR,
            emptyString);
      }

      byte[] content = null;
      if (objectType.equalsIgnoreCase(ObjectType.FOLDER.name())) {
        content = ZipRequests.getZipFromS3(request);
        ZipRequests.removeZipFromS3(request);
      } else {
        JsonObject data = DownloadRequests.getDownloadRequestData(downloadToken);

        if (data != null) {
          content = data.getBinary(Field.DATA.getName());
          DownloadRequests.deleteDownloadRequestData(downloadToken);
        }
      }
      if (Objects.nonNull(content)) {
        message.reply(content);
      } else {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, "DownloadDataNotFound"),
            KudoResourceErrorCodes.OBJECT_DOWNLOAD_ERROR,
            HttpStatus.BAD_REQUEST,
            "DownloadDataNotFound");
      }
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("getDownload", System.currentTimeMillis() - mills);
  }

  private void handleObjectDeleteResponse(
      Promise<String> promise,
      AsyncResult<Message<Object>> event,
      String objectId,
      JsonArray errors) {
    if (event.succeeded()) {
      if (!OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
        int statusCode = HttpStatus.BAD_REQUEST;
        String errorId = emptyString;
        JsonObject errorObject = (JsonObject) event.result().body();
        if (errorObject.containsKey(Field.STATUS_CODE.getName())) {
          statusCode = errorObject.getInteger(Field.STATUS_CODE.getName());
        }
        if (errorObject.containsKey(Field.ERROR_ID.getName())) {
          errorId = errorObject.getString(Field.ERROR_ID.getName());
        }
        if (errorObject.containsKey(Field.MESSAGE.getName())) {
          if (errorObject.containsKey(Field.ERROR_ID.getName())
              && errorObject.getString(Field.ERROR_ID.getName()).startsWith("RS")) {
            errors.add(
                new JsonObject().put(objectId, errorObject.getJsonObject(Field.MESSAGE.getName())));
            promise.fail(emptyString);
          } else {
            JsonObject innerObject = errorObject.getJsonObject(Field.MESSAGE.getName());
            if (innerObject.containsKey(Field.MESSAGE.getName())) {
              promise.fail(statusCode + "&" + errorId + "&"
                  + innerObject.getString(Field.MESSAGE.getName()));
            } else {
              promise.fail(statusCode + "&" + errorId + "&" + innerObject);
            }
          }
        } else {
          promise.fail(statusCode + "&" + errorId + "&" + errorObject);
        }
      } else {
        promise.complete();
      }
    } else {
      promise.fail(event.cause().getLocalizedMessage());
    }
  }

  private void getObjectsToDelete(
      Message<JsonObject> message,
      ResourceBuffer buffer,
      List<JsonObject> objectsToDelete,
      ResourceType resourceType)
      throws KudoResourceException {
    JsonObject object = ResourceBuffer.toJson(buffer);
    if (!Utils.isJsonObjectNotNullOrEmpty(object)) {
      throw new KudoResourceException(
          Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
          KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
          HttpStatus.BAD_REQUEST,
          ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
    }
    object.remove(Field.PARENT.getName());
    objectsToDelete.add(object);
    if (buffer.getObjectType().equalsIgnoreCase(ObjectType.FOLDER.name())) {
      buffer.setParent(buffer.getObjectId());
      Iterator<Item> items = BaseResource.getFolderContent(buffer, resourceType);
      while (items.hasNext()) {
        Item item = items.next();
        buffer.setObjectId(item.getString(Field.OBJECT_ID.getName()));
        buffer.setObjectType(item.getString(Field.OBJECT_TYPE.getName()));
        getObjectsToDelete(message, buffer, objectsToDelete, resourceType);
      }
    }
  }

  private void deleteDownloadRequests(
      String requestId, String userId, String folderId, ObjectType objectType) {
    if (Objects.nonNull(objectType) && objectType.equals(ObjectType.FOLDER)) {
      ZipRequests.deleteZipRequest(userId, folderId, requestId);
      Item request = ZipRequests.getZipRequest(userId, folderId, requestId);
      ZipRequests.removeZipFromS3(request);
    } else {
      DownloadRequests.deleteDownloadRequestData(requestId);
      DownloadRequests.deleteRequest(requestId);
    }
  }
}
