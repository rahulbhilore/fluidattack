package com.graebert.storage.resources;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.resources.integration.BaseResource;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Users;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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
    eb.consumer(address + ".shareObject", this::doShareObject);
    eb.consumer(address + ".deShareObject", this::doDeShareObject);
    eb.consumer(address + ".moveObjects", this::doMoveObjects);
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
      resource
          .setUserId(jsonObject.getString(Field.USER_ID.getName()))
          .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
          .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
          .setParent(jsonObject.getString(Field.FOLDER_ID.getName()))
          .setObjectFilter(jsonObject.getString(Field.OBJECT_FILTER.getName()))
          .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

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

    ResourceBuffer resource = new ResourceBuffer()
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setParent(jsonObject.getString(Field.FOLDER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setName(jsonObject.getString(Field.NAME.getName()))
        .setDescription(jsonObject.getString(Field.DESCRIPTION.getName()))
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

    String fileName = jsonObject.getString(Field.FILE_NAME_C.getName());
    if (BaseResource.isFile(jsonObject)) {
      resource
          .setFileContent(jsonObject.getBinary(Field.FILE_CONTENT.getName()))
          .setFileName(fileName)
          .setFileType(Extensions.getExtension(fileName).substring(1).toUpperCase())
          .setFileSize(jsonObject.getInteger(Field.FILE_SIZE.getName()));
    }
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Utils.isObjectNameLong(resource.getName())) {
        try {
          throw new KudoFileException(
              Utils.getLocalizedString(message, "TooLongObjectName"),
              KudoErrorCodes.LONG_OBJECT_NAME,
              kong.unirest.HttpStatus.BAD_REQUEST,
              "TooLongObjectName");
        } catch (KudoFileException kfe) {
          sendError(
              segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
          return;
        }
      }
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (Utils.isStringNotNullOrEmpty(fileName)) {
        checkAllowedResourceFileTypes(
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

    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setParent(jsonObject.getString(Field.FOLDER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setName(jsonObject.getString(Field.NAME.getName()))
        .setDescription(jsonObject.getString(Field.DESCRIPTION.getName()))
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

    String fileName = null;
    if (BaseResource.isFile(jsonObject) && jsonObject.containsKey(Field.FILE_CONTENT.getName())) {
      fileName = jsonObject.getString(Field.FILE_NAME_C.getName());
      resource
          .setFileContent(jsonObject.getBinary(Field.FILE_CONTENT.getName()))
          .setFileName(fileName)
          .setFileType(Extensions.getExtension(fileName).substring(1).toUpperCase())
          .setFileSize(jsonObject.getInteger(Field.FILE_SIZE.getName()));
    }
    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (Utils.isObjectNameLong(resource.getName())) {
        try {
          throw new KudoFileException(
              Utils.getLocalizedString(message, "TooLongObjectName"),
              KudoErrorCodes.LONG_OBJECT_NAME,
              kong.unirest.HttpStatus.BAD_REQUEST,
              "TooLongObjectName");
        } catch (KudoFileException kfe) {
          sendError(
              segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
          return;
        }
      }
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (Utils.isStringNotNullOrEmpty(fileName)) {
        String fileExtension = fileName.substring(fileName.lastIndexOf(".") + 1);
        checkAllowedResourceFileTypes(message, resourceType, fileExtension);
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

  private void doShareObject(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    JsonObject collaborators = jsonObject.getJsonObject(Field.COLLABORATORS.getName());
    JsonArray editors = null, viewers = null;
    try {
      if (Objects.isNull(resourceType)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.INVALID_TYPE.id),
            KudoResourceErrorCodes.INVALID_RESOURCE_TYPE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.INVALID_TYPE.id);
      }
      if (Utils.isJsonObjectNotNullOrEmpty(collaborators)) {
        editors = collaborators.getJsonArray(Field.EDITORS.getName());
        viewers = collaborators.getJsonArray(Field.VIEWERS.getName());
      }
      if (!Utils.isJsonArrayNotNullOrEmpty(editors) && !Utils.isJsonArrayNotNullOrEmpty(viewers)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COLLABORATORS_NOT_PROVIDED.id),
            KudoResourceErrorCodes.INVALID_SHARE_DATA,
            HttpStatus.PRECONDITION_FAILED,
            ResourceErrorIds.COLLABORATORS_NOT_PROVIDED.id);
      }
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
      return;
    }

    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setResourceType(resourceType.name())
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

    AtomicBoolean isShareWithMyself = new AtomicBoolean(false);
    JsonArray nonExistentEmails = new JsonArray();
    List<String> editorsToUpdate = new ArrayList<>();
    if (Objects.nonNull(editors)) {
      editors.forEach(obj -> {
        String email = (String) obj;
        Iterator<Item> emailIterator = Users.getUserByEmail(email);
        if (!emailIterator.hasNext()) {
          nonExistentEmails.add(email);
        } else {
          editorsToUpdate.add(emailIterator.next().getString(Field.SK.getName()));
          if (jsonObject.getString(Field.USER_EMAIL.getName()).equalsIgnoreCase(email)) {
            isShareWithMyself.set(true);
          }
        }
      });
      resource.setEditors(editorsToUpdate);
    }

    List<String> viewersToUpdate = new ArrayList<>();
    if (Objects.nonNull(viewers)) {
      viewers.forEach(obj -> {
        String email = (String) obj;
        Iterator<Item> emailIterator = Users.getUserByEmail(email);
        if (!emailIterator.hasNext()) {
          nonExistentEmails.add(email);
        } else {
          viewersToUpdate.add(emailIterator.next().getString(Field.SK.getName()));
          if (jsonObject.getString(Field.USER_EMAIL.getName()).equalsIgnoreCase(email)) {
            isShareWithMyself.set(true);
          }
        }
      });
      resource.setViewers(viewersToUpdate);
    }

    JsonObject data = ResourceBuffer.toJson(resource);
    if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
      try {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      } catch (KudoResourceException kre) {
        sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
        return;
      }
    }

    eb_request(segment, ResourceType.getAddress(resourceType) + ".shareObject", data, event -> {
      if (event.succeeded()) {
        if (OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
          JsonObject result = (JsonObject) event.result().body();
          JsonObject collaboratorsData = result.getJsonObject(Field.COLLABORATORS.getName());
          if (Utils.isJsonObjectNotNullOrEmpty(collaboratorsData)) {
            collaboratorsData
                .getJsonArray(Field.CHANGED_COLLABORATORS.getName())
                .forEach(col -> {
                  eb_send(
                      segment,
                      MailUtil.address + ".resourceShared",
                      new JsonObject()
                          .put(Field.RESOURCE_ID.getName(), resource.getObjectId())
                          .put(Field.OBJECT_TYPE.getName(), resource.getObjectType())
                          .put(Field.OWNER_ID.getName(), resource.getUserId())
                          .put(Field.COLLABORATOR_ID.getName(), col)
                          .put(
                              Field.RESOURCE_THUMBNAIL.getName(),
                              result.getString(Field.THUMBNAIL.getName()))
                          .put(
                              Field.RESOURCE_NAME.getName(), result.getString(Field.NAME.getName()))
                          .put(
                              Field.RESOURCE_PARENT_ID.getName(),
                              result.getString(Field.PARENT.getName()))
                          .put(Field.RESOURCE_TYPE.getName(), ResourceType.BLOCKS.name())
                          .put(
                              Field.ROLE.getName(),
                              editorsToUpdate.contains((String) col)
                                  ? Role.EDITOR.name()
                                  : Role.VIEWER.name()));
                });
          }

          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(Field.NON_EXISTENT_EMAILS.getName(), nonExistentEmails)
                  .put(
                      Field.COLLABORATORS.getName(),
                      collaboratorsData.getJsonArray(Field.ALL_COLLABORATORS.getName()))
                  .put("isShareWithMyself", isShareWithMyself.get()),
              mills);
        } else {
          sendError(segment, message, event.result());
        }
      } else {
        sendError(
            segment,
            message,
            event.cause().getLocalizedMessage(),
            HttpStatus.INTERNAL_SERVER_ERROR);
      }
    });
  }

  private void doDeShareObject(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    JsonArray deShare = jsonObject.getJsonArray(Field.DE_SHARE.getName());

    try {
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

    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setResourceType(resourceType.name())
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

    JsonObject data = ResourceBuffer.toJson(resource);
    try {
      if (!Utils.isJsonObjectNotNullOrEmpty(data)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
            KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
      }
      if (!Utils.isJsonArrayNotNullOrEmpty(deShare)) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.COLLABORATORS_NOT_PROVIDED.id),
            KudoResourceErrorCodes.INVALID_UNSHARE_DATA,
            HttpStatus.PRECONDITION_FAILED,
            ResourceErrorIds.COLLABORATORS_NOT_PROVIDED.id);
      }
    } catch (KudoResourceException kre) {
      sendError(segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
      return;
    }

    data.put(
        Field.DE_SHARE.getName(),
        deShare.stream()
            .map(obj -> ((JsonObject) obj).getString(Field.USER_ID.getName()))
            .collect(Collectors.toList()));

    eb_request(segment, ResourceType.getAddress(resourceType) + ".deShareObject", data, event -> {
      if (event.succeeded()) {
        if (OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
          JsonObject result = (JsonObject) event.result().body();
          AtomicBoolean selfUnshare = new AtomicBoolean(false);
          deShare.forEach(user -> {
            if (((JsonObject) user)
                .getString(Field.USER_ID.getName())
                .equals(resource.getUserId())) {
              // self unshare
              selfUnshare.set(true);
              return;
            }
            if (result.containsKey(Field.NAME.getName())) {
              eb_send(
                  segment,
                  MailUtil.address + ".resourceUnshared",
                  new JsonObject()
                      .put(Field.RESOURCE_ID.getName(), resource.getObjectId())
                      .put(Field.OWNER_ID.getName(), resource.getUserId())
                      .put(
                          Field.COLLABORATOR_ID.getName(),
                          ((JsonObject) user).getString(Field.USER_ID.getName()))
                      .put(Field.RESOURCE_NAME.getName(), result.getString(Field.NAME.getName()))
                      .put(Field.RESOURCE_TYPE.getName(), ResourceType.BLOCKS.name()));
            }
          });

          sendOK(segment, message, new JsonObject().put("selfUnshare", selfUnshare.get()), mills);
        } else {
          sendError(segment, message, event.result());
        }
      } else {
        sendError(
            segment,
            message,
            event.cause().getLocalizedMessage(),
            HttpStatus.INTERNAL_SERVER_ERROR);
      }
    });
  }

  private void doMoveObjects(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String ownerId = jsonObject.getString(Field.OWNER_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    JsonArray objects = jsonObject.getJsonArray(Field.OBJECTS.getName());
    try {
      if (Objects.isNull(objects) || objects.isEmpty()) {
        throw new KudoResourceException(
            Utils.getLocalizedString(message, ResourceErrorIds.NOTHING_TO_MOVE.id),
            KudoResourceErrorCodes.NOTHING_TO_MOVE,
            HttpStatus.BAD_REQUEST,
            ResourceErrorIds.NOTHING_TO_MOVE.id);
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
    List<JsonObject> objectsToMove = new ArrayList<>();
    JsonArray errors = new JsonArray();
    ResourceBuffer buffer = new ResourceBuffer();
    if (Objects.nonNull(ownerId)) {
      buffer.setOwnerId(ownerId);
    } else {
      buffer.setOwnerId(userId);
    }
    buffer
        .setUserId(userId)
        .setParent(jsonObject.getString(Field.PARENT_FOLDER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Object obj : objects) {
        JsonObject object = (JsonObject) obj;
        buffer
            .setObjectId(object.getString(Field.ID.getName()))
            .setObjectType(object.getString(Field.OBJECT_TYPE.getName()));
        JsonObject objToMove = ResourceBuffer.toJson(buffer);
        try {
          if (!Utils.isJsonObjectNotNullOrEmpty(objToMove)) {
            throw new KudoResourceException(
                Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
                KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
                HttpStatus.BAD_REQUEST,
                ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
          }
        } catch (KudoResourceException kre) {
          sendError(
              segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
          return;
        }
        objectsToMove.add(objToMove);
      }
      for (JsonObject data : objectsToMove) {
        Promise<Object> promise = Promise.promise();
        eb_request(
            segment,
            ResourceType.getAddress(resourceType) + ".moveObject",
            data,
            event -> handleObjectPromiseResponse(
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
    recordExecutionTime("moveObjects", System.currentTimeMillis() - mills);
  }

  private void doDeleteObjects(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String ownerId = jsonObject.getString(Field.OWNER_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String type = jsonObject.getString(Field.RESOURCE_TYPE.getName());
    ResourceType resourceType = ResourceType.getType(type);
    JsonArray objects = jsonObject.getJsonArray(Field.OBJECTS.getName());
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
    JsonArray errors = new JsonArray();
    ResourceBuffer buffer = new ResourceBuffer();
    if (Objects.nonNull(ownerId)) {
      buffer.setOwnerId(ownerId);
    } else {
      buffer.setOwnerId(userId);
    }
    buffer
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(userId)
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (Object obj : objects) {
        JsonObject object = (JsonObject) obj;
        buffer
            .setObjectId(object.getString(Field.ID.getName()))
            .setObjectType(object.getString(Field.OBJECT_TYPE.getName()));
        JsonObject objToDelete = ResourceBuffer.toJson(buffer);
        try {
          if (!Utils.isJsonObjectNotNullOrEmpty(objToDelete)) {
            throw new KudoResourceException(
                Utils.getLocalizedString(message, ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id),
                KudoResourceErrorCodes.OBJECT_CONVERSION_ERROR,
                HttpStatus.BAD_REQUEST,
                ResourceErrorIds.COULD_NOT_PARSE_OBJECT.id);
          }
        } catch (KudoResourceException kre) {
          sendError(
              segment, message, kre.toResponseObject(), kre.getHttpStatus(), kre.getErrorId());
          return;
        }
        objectsToDelete.add(objToDelete);
      }
      for (JsonObject data : objectsToDelete) {
        Promise<Object> promise = Promise.promise();
        eb_request(
            segment,
            ResourceType.getAddress(resourceType) + ".deleteObject",
            data,
            event -> handleObjectPromiseResponse(
                promise, event, data.getString(Field.OBJECT_ID.getName()), errors));
        promise.future().onSuccess(event -> {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("NotifyResourceDelete")
              .run((blockingSegment) -> {
                if (event instanceof JsonObject) {
                  JsonObject result = (JsonObject) event;
                  if (result.containsKey(Field.COLLABORATORS.getName())) {
                    String resourceName = result.getString(Field.NAME.getName());
                    JsonArray collaborators = result.getJsonArray(Field.COLLABORATORS.getName());
                    collaborators.forEach(user -> {
                      eb_send(
                          blockingSegment,
                          MailUtil.address + ".resourceDeleted",
                          new JsonObject()
                              .put(
                                  Field.RESOURCE_ID.getName(),
                                  data.getString(Field.OBJECT_ID.getName()))
                              .put(Field.OWNER_ID.getName(), userId)
                              .put(Field.COLLABORATOR_ID.getName(), user)
                              .put(
                                  Field.RESOURCE_NAME.getName(),
                                  Utils.isStringNotNullOrEmpty(resourceName)
                                      ? resourceName
                                      : Field.UNKNOWN.getName())
                              .put(Field.RESOURCE_TYPE.getName(), resourceType.name()));
                    });
                  }
                }
              });
        });
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
    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

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
    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setObjectType(ObjectType.FOLDER.name())
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

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

    ResourceBuffer resource = new ResourceBuffer()
        .setObjectId(jsonObject.getString(Field.OBJECT_ID.getName()))
        .setOwnerId(jsonObject.getString(Field.OWNER_ID.getName()))
        .setOwnerType(jsonObject.getString(Field.OWNER_TYPE.getName()))
        .setUserId(jsonObject.getString(Field.USER_ID.getName()))
        .setObjectType(jsonObject.getString(Field.OBJECT_TYPE.getName()))
        .setOrganizationId(jsonObject.getString(Field.ORGANIZATION_ID.getName()));

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
      if (BaseResource.isFile(resource)) {
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
      if (BaseResource.isFolder(objectType)) {
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
      if (BaseResource.isFolder(objectType)) {
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

  private void handleObjectPromiseResponse(
      Promise<Object> promise,
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
        promise.complete(event.result().body());
      }
    } else {
      promise.fail(event.cause().getLocalizedMessage());
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

  private static void checkAllowedResourceFileTypes(
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
}
