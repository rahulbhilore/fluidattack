package com.graebert.storage.gridfs;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Recents;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class RecentFilesVerticle extends DynamoBusModBase {
  public static final String address = "recent";
  private static final OperationGroup operationGroup = OperationGroup.RECENT;
  // private static final String table = "recent";

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getRecentFiles", this::doGetRecentFiles);
    eb.consumer(address + ".saveRecentFile", this::doSaveRecentFile);
    eb.consumer(address + ".updateRecentFile", this::doUpdateRecentFile);
    eb.consumer(address + ".deleteRecentFile", this::doDeleteRecentFile);
    eb.consumer(address + ".restoreRecentFile", this::doRestoreRecentFile);
    eb.consumer(address + ".validateRecentFiles", this::doValidateRecentFiles);
    eb.consumer(address + ".validateSingleRecentFile", this::doValidateSingleFile);

    executor = vertx.createSharedWorkerExecutor(
        "vert.x-new-internal-blocking-recent", ServerConfig.WORKER_POOL_SIZE, 5, TimeUnit.MINUTES);
  }

  private void cleanUp(String userId) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "cleanup");
    // keep up to 10 recents per user
    List<Item> list = new ArrayList<>();
    Recents.getRecentFilesByUserId(userId).forEachRemaining(list::add);
    int count = list.size();
    if (count > 10) {
      list.sort(Comparator.comparing(o -> o.getNumber("actionTime")));
      list = list.subList(0, list.size() - 10);
      List<PrimaryKey> toDelete = new ArrayList<>();
      list.forEach(item -> toDelete.add(
          new PrimaryKey(Recents.pk, Recents.getPk(item), Recents.sk, Recents.getSk(item))));

      if (!toDelete.isEmpty()) {
        Dataplane.deleteListItemsBatch(toDelete, Recents.tableName, DataEntityType.RECENTS);
      }
    }
    XRayManager.endSegment(subsegment);
  }

  private void doUpdateRecentFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String thumbnailName = jsonObject.getString(Field.THUMBNAIL_NAME.getName());
    Boolean accessible = jsonObject.getBoolean(Field.ACCESSIBLE.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String filename = jsonObject.getString(Field.FILE_NAME.getName());
    String parentId = jsonObject.getString(Field.PARENT_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null
        && userId == null
        && thumbnailName == null
        && accessible == null
        && externalId == null
        && filename == null
        && parentId == null) {
      log.error(LogPrefix.getLogPrefix()
          + " Error on updating recent file(s): at least one value must be specified");
      return;
    }

    JsonArray collaborators = new JsonArray();

    // deleted (done) or unshared file (just for fluorine storage)
    if (accessible != null && !accessible) {
      if (fileId == null) {
        log.error(
            LogPrefix.getLogPrefix() + " Error on deleting recent file: fileId must be specified");
        XRayManager.endSegment(segment);
        return;
      }
      if (Utils.isStringNotNullOrEmpty(userId)) {
        Recents.deleteRecentFile(userId, storageType, fileId);
        collaborators.add(userId);
      } else {
        Iterator<Item> it = Recents.getRecentFilesByFileIdIndex(storageType, fileId);
        List<PrimaryKey> toDelete = new ArrayList<>();
        it.forEachRemaining(recent -> {
          toDelete.add(
              new PrimaryKey(Recents.pk, Recents.getPk(recent), Recents.sk, Recents.getSk(recent)));
          collaborators.add(Recents.getUserId(recent));
        });
        if (!toDelete.isEmpty()) {
          Dataplane.deleteListItemsBatch(toDelete, Recents.tableName, DataEntityType.RECENTS);
        }
      }
    }

    // updated thumbnail (done)
    else if (Utils.isStringNotNullOrEmpty(thumbnailName)) {
      if (!Utils.isStringNotNullOrEmpty(fileId)) {
        log.error(String.format(
            "%s Error on updating thumbnail for recent file: fileId must be specified. "
                + "thumbnailName: %s",
            LogPrefix.getLogPrefix(), thumbnailName));
        XRayManager.endSegment(segment);
        return;
      }
      Iterator<Item> it = Recents.getRecentFilesByFileIdIndex(storageType, fileId);
      List<Item> toUpdate = new ArrayList<>();
      //            if (!it.hasNext()) {
      //                // Not the best operation for sure, but it should be rarely called.
      //                // The reason is that we have different fileId received from
      //                ThumbnailManager and saved in recent files
      //                // Ideally we need to unify them, but I'm not sure how to properly do it
      //                for now
      //                // either send id with driveId into Thumbnails
      //                // or pureId (without drive) into recentFiles
      //                // but this may lead to invalid work of business accounts and drives.
      //                // therefore - let's use scan for now and review later or remove and ignore
      //                //
      //                it = Recents.getUnreadRecentFiles(fileId);
      //            }
      if (Utils.isStringNotNullOrEmpty(userId)) {
        Item recentFile = Recents.getRecentFile(userId, fileId, storageType);
        if (Objects.isNull(recentFile) && jsonObject.getBoolean("fileUpload", false)) {
          doSaveRecentFile(message);
        }
      }
      it.forEachRemaining(recent -> {
        if (!thumbnailName.equals(recent.getString(Field.THUMBNAIL_NAME.getName()))) {
          recent.withString(Field.THUMBNAIL_NAME.getName(), thumbnailName);
          collaborators.add(Recents.getUserId(recent));
        }
        recent.withNumber("actionTime", GMTHelper.utcCurrentTime());
        toUpdate.add(recent);
      });

      if (!toUpdate.isEmpty()) {
        Dataplane.batchWriteListItems(toUpdate, Recents.tableName, DataEntityType.RECENTS);
      }
    }

    // storage disconnected (done)
    else if (Utils.isStringNotNullOrEmpty(externalId)) {
      if (userId == null) {
        log.error(
            LogPrefix.getLogPrefix() + " Error on deleting recent file: userId must be specified");
        XRayManager.endSegment(segment);
        return;
      }
      List<PrimaryKey> toDelete = new ArrayList<>();
      Recents.getRecentFilesByUserId(userId, externalId).forEachRemaining(recent -> {
        toDelete.add(
            new PrimaryKey(Recents.pk, Recents.getPk(recent), Recents.sk, Recents.getSk(recent)));
        collaborators.add(Recents.getUserId(recent));
      });

      if (!toDelete.isEmpty()) {
        Dataplane.deleteListItemsBatch(toDelete, Recents.tableName, DataEntityType.RECENTS);
      }
    }

    // check if the file was renamed or moved
    else if ((Utils.isStringNotNullOrEmpty(filename)) || (Utils.isStringNotNullOrEmpty(parentId))) {
      List<Item> toUpdate = new ArrayList<>();
      Recents.getRecentFilesByFileIdIndex(storageType, fileId).forEachRemaining(recent -> {
        boolean update = false;
        if (Utils.isStringNotNullOrEmpty(filename)
            && !filename.equals(recent.getString(Field.FILE_NAME.getName()))) {
          recent.withString(Field.FILE_NAME.getName(), filename);
          update = true;
        }
        if (Utils.isStringNotNullOrEmpty(parentId)
            && !parentId.equals(recent.getString(Field.PARENT_ID.getName()))) {
          recent.withString(Field.PARENT_ID.getName(), parentId);
          update = true;
        }
        if (update) {
          collaborators.add(Recents.getUserId(recent));
        }
        recent.withNumber("actionTime", GMTHelper.utcCurrentTime());
        toUpdate.add(recent);
      });
      if (!toUpdate.isEmpty()) {
        Dataplane.batchWriteListItems(toUpdate, Recents.tableName, DataEntityType.RECENTS);
      }
    }
    eb_send(
        segment,
        WebSocketManager.address + ".recentsUpdate",
        new JsonObject().put(Field.COLLABORATORS.getName(), collaborators));
    XRayManager.endSegment(segment);
  }

  private void doDeleteRecentFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    final String userId = body.getString(Field.USER_ID.getName());
    final String fileId = body.getString(Field.FILE_ID.getName());
    final StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));

    Recents.deleteRecentFile(userId, storageType, fileId);
    eb_send(
        segment,
        WebSocketManager.address + ".recentsUpdate",
        new JsonObject().put(Field.COLLABORATORS.getName(), new JsonArray().add(userId)));

    sendOK(segment, message);
  }

  private void doRestoreRecentFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    final String userId = body.getString(Field.USER_ID.getName());
    final StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
    final String externalId = body.getString(Field.EXTERNAL_ID.getName());
    final String fileName = body.getString(Field.FILE_NAME_C.getName());
    final String folderId = body.getString(Field.FOLDER_ID.getName());
    final String thumbnailName = body.getString(Field.THUMBNAIL_NAME.getName());
    final String fileId = body.getString(Field.FILE_ID.getName());
    final long timestamp = body.getLong(Field.TIMESTAMP.getName());

    Recents.putRecentFile(
        userId, storageType, externalId, fileId, fileName, folderId, thumbnailName, timestamp);

    eb_send(
        segment,
        WebSocketManager.address + ".recentsUpdate",
        new JsonObject().put(Field.COLLABORATORS.getName(), new JsonArray().add(userId)));

    sendOK(segment, message);
  }

  private void doSaveRecentFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, jsonObject);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    final String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    final String newThumbnailName = jsonObject.getString(Field.THUMBNAIL_NAME.getName());

    if (storageType == null || userId == null || fileId == null) {
      return;
    }

    StorageType storageType1 = StorageType.getStorageType(storageType);
    XRayEntityUtils.putStorageMetadata(segment, message, storageType1);
    String traceId = XRayEntityUtils.getTraceId(segment);

    eb_request(
        segment, StorageType.getAddress(storageType1) + ".getInfo", message.body(), event -> {
          Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
          if (event.succeeded() && event.result() != null && event.result().body() != null) {
            try {
              JsonObject eventJson = (JsonObject) event.result().body();
              String filename, thumbnailName, parentId;
              if (event.succeeded()
                  && Field.OK.getName().equals(eventJson.getString(Field.STATUS.getName()))) {
                filename = eventJson.getString(Field.FILE_NAME.getName());
                if (Utils.isStringNotNullOrEmpty(newThumbnailName)) {
                  thumbnailName = newThumbnailName;
                } else {
                  thumbnailName = (Utils.isStringNotNullOrEmpty(filename)
                          && !filename.toLowerCase().endsWith(".pdf"))
                      ? eventJson.getString(Field.THUMBNAIL_NAME.getName())
                      : emptyString;
                }
                parentId = eventJson.getString(Field.FOLDER_ID.getName());

                // I think we shouldn't save recent file if it doesn't have appropriate info
                // @link https://graebert.atlassian.net/browse/XENON-32021
                // breaking change here:

                String finalExternalId = Utils.isStringNotNullOrEmpty(externalId)
                    ? externalId
                    : findExternalId(
                        blockingSegment,
                        message,
                        jsonObject,
                        StorageType.getStorageType(storageType));

                if (filename != null) {
                  Recents.putRecentFile(
                      userId,
                      storageType1,
                      finalExternalId,
                      fileId,
                      filename,
                      parentId,
                      thumbnailName,
                      null);

                  eb_send(
                      blockingSegment,
                      WebSocketManager.address + ".recentsUpdate",
                      new JsonObject()
                          .put(Field.COLLABORATORS.getName(), new JsonArray().add(userId)));
                } else {
                  log.error("filename is null for fileId - " + fileId);
                }

                cleanUp(userId);
              }
            } catch (Exception e) {
              log.error(LogPrefix.getLogPrefix() + " Error on creating recent file: ", e);
            }
          } else {
            log.error(LogPrefix.getLogPrefix() + " Couldn't find info for recent file save "
                + fileId + " storage: " + storageType + " traceId: " + traceId);
          }
          XRayManager.endSegment(blockingSegment);
        });
  }

  private void doGetRecentFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());

    if (userId == null) {
      return;
    }

    sendOK(
        segment,
        message,
        new JsonObject().put(Field.RESULT.getName(), Recents.getRecentFilesJson(userId)),
        mills);
  }

  private void doValidateRecentFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String storage = jsonObject.getString(Field.STORAGE_TYPE.getName());

    if (userId == null) {
      return;
    }

    Iterator<Item> iterator;
    if (Utils.isStringNotNullOrEmpty(storage)) {
      // Delete folder case : filter with both userId and storageType to narrow the results.
      iterator = Recents.getRecentFilesByUserIdAndStorageType(userId, storage);
    } else {
      iterator = Recents.getRecentFilesByUserId(userId);
    }

    // create list to send response when all files are checked
    List<Future<JsonObject>> queue = new ArrayList<>();

    // if something went wrong - lets send the current ones
    JsonArray backup = new JsonArray();

    iterator.forEachRemaining(recent -> {
      StorageType storageType =
          StorageType.getStorageType(recent.getString(Field.STORAGE_TYPE.getName()));
      String externalId = recent.getString(Field.EXTERNAL_ID.getName());
      String fileId = Recents.getFileId(recent);

      JsonObject item = getJsonOfRecentFile(recent);
      // save as a backup plan
      backup.add(item);

      // append required info to the message containing user's info
      JsonObject infoRequest = new JsonObject()
          .mergeIn(jsonObject)
          .put(Field.EXTERNAL_ID.getName(), externalId)
          .put(Field.FILE_ID.getName(), fileId)
          .put(Field.USER_ID.getName(), userId)
          .put(Field.STORAGE_TYPE.getName(), storageType.name());
      Promise<JsonObject> handler = Promise.promise();
      queue.add(handler.future());
      log.info("Recent validation: requesting info with data" + infoRequest.toString());

      // send request to storage to check if it's accessible or not
      eb_request(
          segment,
          StorageType.getAddress(
                  StorageType.getStorageType(recent.getString(Field.STORAGE_TYPE.getName())))
              + ".getTrashStatus",
          infoRequest,
          event -> {
            Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
            try {
              if (!event.succeeded() || event.result() == null || event.result().body() == null) {
                log.info(
                    "Unsuccessful getting trash info for the file: " + Recents.getFileId(recent)
                        + " storage: " + recent.getString(Field.STORAGE_TYPE.getName()));
                JsonObject j = getJsonOfRecentFile(recent);
                handler.complete(j);
              } else {
                JsonObject eventJson = (JsonObject) event.result().body();
                log.info("Recent validation: received info " + eventJson.toString()
                    + " for fileId : " + fileId);
                // if unsuccessful - remove from recent files
                if (!Field.OK.getName().equals(eventJson.getString(Field.STATUS.getName()))
                    || eventJson.getBoolean(Field.IS_DELETED.getName(), false)) {
                  log.info("Recent validation: removing");
                  Recents.deleteRecentFile(userId, storageType, fileId);
                  handler.complete();
                } else {
                  // otherwise - add into result array
                  JsonObject j = getJsonOfRecentFile(recent);
                  handler.complete(j);
                }
              }
            } catch (Exception e) {
              log.error(LogPrefix.getLogPrefix() + " Error on validation of recent files: ", e);
              XRayEntityUtils.addException(segment, e);
              if (!handler.tryComplete()) {
                handler.fail(e);
              }
            }
            XRayManager.endSegment(blockingSegment);
          });
    });
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // all files has been checked.
      if (ar != null && ar.succeeded() && ar.result() != null) {
        List<JsonObject> list = ar.result().list();
        list.sort(Comparator.comparing(o -> {
              if (o != null && ((JsonObject) o).containsKey(Field.TIMESTAMP.getName())) {
                return ((JsonObject) o).getLong(Field.TIMESTAMP.getName());
              }
              return 0L;
            })
            .reversed());
        sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), list), mills);
      } else if (ar != null) {
        // added error handling
        log.error(
            LogPrefix.getLogPrefix() + " Error on validation of recent files: ",
            ar.cause().getMessage());
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.RESULT.getName(), backup)
                .put("isSuccessful", false)
                .put("exception", ar.cause().getMessage()),
            mills);
      } else {
        // if something went SEVERELY wrong - let's return old list.
        sendOK(
            segment,
            message,
            new JsonObject().put(Field.RESULT.getName(), backup).put("isSuccessful", false),
            mills);
      }
    });
  }

  private void doValidateSingleFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String externalId =
        getRequiredString(segment, Field.EXTERNAL_ID.getName(), message, jsonObject);
    String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, jsonObject);

    if (userId == null || fileId == null || storageType == null) {
      return;
    }

    // append required info to the message containing user's info
    JsonObject infoRequest = new JsonObject()
        .mergeIn(jsonObject)
        .put(Field.EXTERNAL_ID.getName(), externalId)
        .put(Field.FILE_ID.getName(), fileId)
        .put(Field.USER_ID.getName(), userId)
        .put(Field.STORAGE_TYPE.getName(), storageType);
    log.info("Recent validation: requesting info with data" + infoRequest.toString());

    // send request to storage to check if it's accessible or not
    eb_request(
        segment,
        StorageType.getAddress(StorageType.getStorageType(storageType)) + ".getTrashStatus",
        infoRequest,
        event -> {
          Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
          try {
            if (!event.succeeded() || event.result() == null || event.result().body() == null) {
              log.info("Unsuccessful getting trash info for the file: " + fileId + " storage: "
                  + storageType);
              sendError(segment, message, "Unable to get file's info", 400);
            } else {
              JsonObject eventJson = (JsonObject) event.result().body();
              log.info("Recent validation: received info " + eventJson.toString());
              // if unsuccessful - remove from recent files
              if (!Field.OK.getName().equals(eventJson.getString(Field.STATUS.getName()))
                  || eventJson.getBoolean(Field.IS_DELETED.getName(), false)) {
                log.info("Recent validation: removing");
                Recents.deleteRecentFile(userId, StorageType.getStorageType(storageType), fileId);
                sendError(segment, message, "File's deleted", 400);
              } else {
                // otherwise - add into result array
                sendOK(segment, message);
              }
            }
          } catch (Exception e) {
            log.error(LogPrefix.getLogPrefix() + " Error on validation of recent files: ", e);
            XRayEntityUtils.addException(segment, e);
            sendError(
                segment, message, "Exception on getting trash status: " + e.getMessage(), 500);
          }

          XRayManager.endSegment(blockingSegment);
        });
  }

  private JsonObject getJsonOfRecentFile(Item recent) {

    String shortST = StorageType.getShort(recent.getString(Field.STORAGE_TYPE.getName()));
    String externalId = recent.getString(Field.EXTERNAL_ID.getName());

    JsonObject j = new JsonObject()
        .put(
            Field.TIMESTAMP.getName(),
            recent.hasAttribute("actionTime") ? recent.getLong("actionTime") : 0)
        .put(
            Field.FILE_ID.getName(),
            Utils.getEncapsulatedId(shortST, externalId, Recents.getFileId(recent)))
        .put(
            Field.STORAGE_TYPE.getName(),
            StorageType.getStorageType(recent.getString(Field.STORAGE_TYPE.getName()))
                .toString())
        .put(Field.FILE_NAME.getName(), recent.getString(Field.FILE_NAME.getName()))
        .put(
            Field.THUMBNAIL.getName(),
            ThumbnailsManager.getThumbnailURL(
                config,
                recent.getString(Field.THUMBNAIL_NAME.getName()),
                !StorageType.WEBDAV.name().equals(recent.getString(Field.STORAGE_TYPE.getName()))));
    if (recent.getString(Field.PARENT_ID.getName()) != null
        && !recent.getString(Field.PARENT_ID.getName()).isEmpty()) {
      j.put(
          Field.FOLDER_ID.getName(),
          Utils.getEncapsulatedId(
              shortST, externalId, recent.getString(Field.PARENT_ID.getName())));
    }
    return j;
  }
}
