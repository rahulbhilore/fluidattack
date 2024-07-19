package com.graebert.storage.blocklibrary;

import static com.graebert.storage.storage.Dataplane.pk;
import static com.graebert.storage.storage.Dataplane.sk;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.resources.OldResourceTypes;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.stats.logs.block.BlockActions;
import com.graebert.storage.stats.logs.block.BlockLog;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.TypedIteratorUtils;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.HttpStatusCodes;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BlockLibraryManager extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.BLOCK_LIBRARIES;

  public static final String address = "blockLibrary";
  public static final String somethingWentWrongError = "Something went wrong";
  public static final String emptyErrorId = "";
  public static S3Regional s3Regional;

  private enum WebsocketAction {
    CREATED_BLOCK_LIBRARY,
    CREATED_BLOCK,
    UPDATED_BLOCK_LIBRARY,
    UPDATED_BLOCK,
    DELETED_BLOCK_LIBRARY,
    DELETED_BLOCK,
    SHARED_BLOCK_LIBRARY,
    SHARED_BLOCK,
    UNSHARED_BLOCK_LIBRARY,
    UNSHARED_BLOCK
  }

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getBlockLibraries", this::doGetBlockLibraries);
    eb.consumer(address + ".createBlockLibrary", this::doCreateBlockLibrary);
    eb.consumer(address + ".updateBlockLibrary", this::doUpdateBlockLibrary);
    eb.consumer(address + ".getBlockLibraryInfo", this::doGetBlockLibraryInfo);
    eb.consumer(address + ".deleteBlockLibraries", this::doDeleteBlockLibraries);
    eb.consumer(address + ".uploadBlock", this::doUploadBlock);
    eb.consumer(address + ".updateBlock", this::doUpdateBlock);
    eb.consumer(address + ".getBlocks", this::doGetBlocks);
    eb.consumer(address + ".getBlockInfo", this::doGetBlockInfo);
    eb.consumer(address + ".deleteBlocks", this::doDeleteBlocks);
    eb.consumer(address + ".getBlockContent", this::doGetBlockContent);
    eb.consumer(address + ".searchBlockLibrary", this::doSearchBlockLibrary);

    eb.consumer(address + ".shareBlockLibrary", this::doShareBlockLibrary);
    eb.consumer(
        address + ".unShareBlockLibrary",
        (Message<JsonObject> event) -> doUnShareBlockLibrary(event));
    eb.consumer(address + ".shareBlock", (Message<JsonObject> event) -> doShareBlock(event));
    eb.consumer(address + ".unShareBlock", (Message<JsonObject> event) -> doUnShareBlock(event));
    eb.consumer(address + ".lockBlock", (Message<JsonObject> event) -> doLockBlock(event));
    eb.consumer(address + ".unlockBlock", (Message<JsonObject> event) -> doUnlockBlock(event));

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-blockLibrary");
    String bucket = config.getProperties().getS3BlBucket();
    String region = config.getProperties().getS3BlRegion();
    s3Regional = new S3Regional(config, bucket, region);
  }

  private void doGetBlockLibraries(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.USER_ID.getName());
    String ownerId = message.body().getString(Field.OWNER_ID.getName());
    String ownerType = message.body().getString(Field.OWNER_TYPE.getName());
    JsonArray results = new JsonArray();

    if (!isOwnerValid(ownerId, ownerType)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "BL22"),
          HttpStatusCodes.FORBIDDEN,
          "BL22");
      return;
    }
    JsonObject hasAccess = hasAccessToCreateOrGetLibraries(
        ownerId, userId, ownerType, false, Utils.getLocaleFromMessage(message));
    if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
        && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
      sendError(
          segment,
          message,
          hasAccess.containsKey(Field.ERROR.getName())
              ? hasAccess.getString(Field.ERROR.getName())
              : somethingWentWrongError,
          HttpStatusCodes.FORBIDDEN,
          hasAccess.containsKey(Field.ERROR_ID.getName())
              ? hasAccess.getString(Field.ERROR_ID.getName())
              : emptyErrorId);
      return;
    }
    try {
      Iterator<Item> sharedIterator = Collections.emptyIterator();
      Iterator<Item> sharedBlocksIterator = Collections.emptyIterator();
      Iterator<Item> ownedIterator =
          BlockLibraryDao.getBlocksOrLibraries(ownerId, ownerType, true, true);
      if (!ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name())) {
        sharedIterator = BlockLibraryDao.getBlocksOrLibraries(ownerId, ownerType, false, true);
        sharedBlocksIterator =
            BlockLibraryDao.getBlocksOrLibraries(ownerId, ownerType, false, false);
      }
      List<String> excludedLibraries = BlockLibraryDao.getExcludedLibrariesOrBlocks(
          ownerType, userId, BlockLibraryDao.ObjectType.LIBRARY);
      HashSet<String> libraries = new HashSet<>();
      Iterators.concat(ownedIterator, sharedIterator).forEachRemaining(lib -> {
        if (excludedLibraries != null && excludedLibraries.contains(lib.getString(sk))) {
          return;
        }
        results.add(BlockLibraryDao.itemToJson(lib, true));
        libraries.add(lib.getString(sk));
      });

      if (sharedBlocksIterator.hasNext()) {
        sharedBlocksIterator.forEachRemaining(sharedBlock -> {
          String libId = sharedBlock.getString(Field.LIB_ID.getName());
          if (!libraries.contains(libId)) {
            Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libId);
            if (libraryIterator.hasNext()) {
              JsonObject fakeLibraryInfo =
                  BlockLibraryDao.itemToJson(libraryIterator.next(), false);
              fakeLibraryInfo.put("isSharedBlocksCollection", true);
              results.add(fakeLibraryInfo);
              libraries.add(libId);
            }
          }
        });
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    recordExecutionTime("getBlockLibraries", System.currentTimeMillis() - mills);
  }

  private void doCreateBlockLibrary(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String ownerId = body.getString(Field.OWNER_ID.getName());
    String ownerType = body.getString(Field.OWNER_TYPE.getName());
    String name = body.getString(Field.NAME.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String description = body.getString(Field.DESCRIPTION.getName());

    if (name == null || description == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, name == null ? "BL26" : "BL27"),
          HttpStatusCodes.FORBIDDEN,
          name == null ? "BL26" : "BL27");
      return;
    }

    if (Utils.isObjectNameLong(name)) {
      try {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "TooLongObjectName"),
            KudoErrorCodes.LONG_OBJECT_NAME,
            kong.unirest.HttpStatus.BAD_REQUEST,
            "TooLongObjectName");
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      }
    }

    if (!isOwnerValid(ownerId, ownerType)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "BL22"),
          HttpStatusCodes.FORBIDDEN,
          "BL22");
      return;
    }
    JsonObject hasAccess = hasAccessToCreateOrGetLibraries(
        ownerId, userId, ownerType, true, Utils.getLocaleFromMessage(message));
    if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
        && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
      sendError(
          segment,
          message,
          hasAccess.containsKey(Field.ERROR.getName())
              ? hasAccess.getString(Field.ERROR.getName())
              : somethingWentWrongError,
          HttpStatusCodes.FORBIDDEN,
          hasAccess.containsKey(Field.ERROR_ID.getName())
              ? hasAccess.getString(Field.ERROR_ID.getName())
              : emptyErrorId);
      return;
    }
    String libraryId = Utils.generateUUID();
    List<String> s3PathBuilder = new ArrayList<>();
    s3PathBuilder.add(BlockLibraryDao.BlockLibraryOwnerType.getType(ownerType).name());
    if (!ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name())) {
      s3PathBuilder.add(ownerId);
    } else {
      ownerId = BlockLibraryDao.emptyOwnerId;
    }
    s3PathBuilder.add(libraryId);
    String s3Path =
        String.join(S3Regional.pathSeparator, s3PathBuilder).concat(S3Regional.pathSeparator);
    try {
      if (BlockLibraryDao.getBlockLibraryByOwnerAndName(ownerId, ownerType, name)
          .hasNext()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL1"),
            HttpStatusCodes.PRECONDITION_FAILED,
            "BL1");
        return;
      }
      BlockLibraryDao.createBlockLibrary(ownerId, ownerType, name, description, s3Path, libraryId);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    sendOK(segment, message, new JsonObject().put(Field.LIB_ID.getName(), libraryId), mills);
    recordExecutionTime("createBlockLibrary", System.currentTimeMillis() - mills);

    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .run((Segment blockingSegment) -> {
          try {
            BlockLog blockLog = new BlockLog(
                userId,
                message.body().getString(Field.EMAIL.getName()),
                GMTHelper.utcCurrentTime(),
                BlockActions.CREATE_LIBRARY,
                GMTHelper.utcCurrentTime(),
                body.getString(Field.SESSION_ID.getName()),
                body.getString(Field.DEVICE.getName()));
            blockLog.sendToServer();
          } catch (Exception e) {
            log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", e);
          }
          eb_send(
              blockingSegment,
              WebSocketManager.address + ".resourceUpdated",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(
                      "resourceInfo",
                      new JsonObject()
                          .put(Field.ACTION.getName(), WebsocketAction.CREATED_BLOCK_LIBRARY)
                          .put(Field.LIB_ID.getName(), libraryId)));
        });
  }

  private void doUpdateBlockLibrary(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String libraryId = body.getString(Field.LIB_ID.getName());
    String name = body.getString(Field.NAME.getName());
    String description = body.getString(Field.DESCRIPTION.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Item> currentLibraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (currentLibraryIterator.hasNext()) {
        Item finalUpdatedItem;
        Item libraryItem = currentLibraryIterator.next();
        JsonObject hasAccess = hasAccess(
            userId,
            libraryItem,
            true,
            Utils.getLocaleFromMessage(message),
            BlockLibraryDao.PermissionType.UPDATE.name(),
            true);
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }
        String ownerType =
            BlockLibraryDao.getOwnerType(libraryItem.getString(Field.OWNER.getName()));
        String ownerId = BlockLibraryDao.getIdFromPk(libraryItem.getString(pk), ownerType);

        Iterator<Item> existingLibraryIterator =
            BlockLibraryDao.getBlockLibraryByOwnerAndName(ownerId, ownerType, name);
        if (existingLibraryIterator.hasNext()
            && Stream.of(existingLibraryIterator)
                .anyMatch(item -> !item.next().getString(sk).equals(libraryId))) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL1"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "BL1");
          return;
        }
        if (name.equals(libraryItem.getString(Field.NAME.getName()))) {
          name = null;
        } else {
          if (Utils.isObjectNameLong(name)) {
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
        }

        if (description.equals(libraryItem.getString(Field.DESCRIPTION.getName()))) {
          description = null;
        }

        if (name == null && description == null) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FL8"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "FL8");
          return;
        }

        // updating the original Item
        finalUpdatedItem = BlockLibraryDao.updateBlockLibrary(
            name, description, libraryId, libraryItem.getString(pk));

        List<Item> allSharedItems = getSharedItems(hasAccess, libraryId, null);

        // updating all the shared items
        for (Item item : allSharedItems) {
          String pkValue = item.getString(pk);
          Item updatedItem =
              BlockLibraryDao.updateBlockLibrary(name, description, libraryId, pkValue);
          if (userId.equals(BlockLibraryDao.getIdFromPk(pkValue))) {
            finalUpdatedItem = updatedItem;
          }
        }

        if (finalUpdatedItem != null) {
          eb_send(
              segment,
              WebSocketManager.address + ".resourceUpdated",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(
                      "resourceInfo",
                      new JsonObject()
                          .put(Field.ACTION.getName(), WebsocketAction.UPDATED_BLOCK_LIBRARY)
                          .put(Field.LIB_ID.getName(), libraryId)));

          sendOK(segment, message, BlockLibraryDao.itemToJson(finalUpdatedItem, false), mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "UpdatedItemNotReturned"),
              HttpStatusCodes.BAD_REQUEST);
          return;
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL2"),
            HttpStatusCodes.BAD_REQUEST,
            "BL2");
        return;
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("updateBlockLibrary", System.currentTimeMillis() - mills);
  }

  private void doGetBlockLibraryInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String libraryId = message.body().getString(Field.LIB_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (!libraryIterator.hasNext()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL2"),
            HttpStatusCodes.BAD_REQUEST,
            "BL2");
        return;
      }
      boolean isSharedByBlock = false;
      Item libraryItem = libraryIterator.next();
      JsonObject hasAccess =
          hasAccess(userId, libraryItem, false, Utils.getLocaleFromMessage(message), null, true);
      if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
          && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
        // if there's no access - there's still a chance that this library has shared items,
        // so we have to check
        Iterator<Item> sharedBlocks = BlockLibraryDao.getBlocksByLibrary(
            userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), libraryId, false);
        if (sharedBlocks.hasNext()) {
          isSharedByBlock = true;
        }

        // contains shares
        if (!isSharedByBlock) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }
      }
      if (isSharedByBlock) {
        JsonObject fakeLibraryInfo = BlockLibraryDao.itemToJson(libraryItem, false);
        fakeLibraryInfo.put("isSharedBlocksCollection", true);
        sendOK(segment, message, fakeLibraryInfo, mills);
      } else {
        sendOK(segment, message, BlockLibraryDao.itemToJson(libraryItem, true), mills);
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("getBlockLibraryInfo", System.currentTimeMillis() - mills);
  }

  @SuppressWarnings("checkstyle:VariableDeclarationUsageDistance")
  private void doDeleteBlockLibraries(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonArray libraries = message.body().getJsonArray(Field.LIBRARIES.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Object> librariesIterator = libraries.stream().iterator();
      while (librariesIterator.hasNext()) {
        String libraryId = (String) librariesIterator.next();
        Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
        if (libraryIterator.hasNext()) {
          Item library = libraryIterator.next();
          String libraryName = library.getString(Field.NAME.getName());
          JsonObject hasAccess = hasAccess(
              userId,
              library,
              true,
              Utils.getLocaleFromMessage(message),
              BlockLibraryDao.PermissionType.DELETE.name(),
              true);
          boolean toContinue = false;
          if (hasAccess.containsKey("selfUnSharePublicAndOrgAccess")
              && (!BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName()))
                      .equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.ORG.name())
                  || !hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName()))) {
            BlockLibraryDao.selfUnSharePublicAndOrgObject(
                true,
                BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName())),
                userId,
                libraryId,
                true);
            toContinue = true;
          }
          if (hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
            Item user = Users.getUserById(userId);
            if (Objects.nonNull(user)) {
              message
                  .body()
                  .put(Field.HAS_ACCESS.getName(), hasAccess)
                  .put(Field.LIB_ID.getName(), libraryId)
                  .put(
                      Field.EMAILS.getName(),
                      new JsonArray().add(user.getString(Field.USER_EMAIL.getName())));
              doUnShareBlockLibrary(message, false);
              toContinue = true;
            }
          }
          if (toContinue) {
            JsonObject wsEvent = new JsonObject()
                .put(Field.USER_ID.getName(), userId)
                .put(
                    "resourceInfo",
                    new JsonObject()
                        .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK_LIBRARY)
                        .put(Field.LIBRARIES.getName(), new JsonArray().add(libraryId)));
            log.info("[BLM] doDeleteBlockLibraries DELETED_BLOCK_LIBRARY -> toContinue -> "
                + wsEvent.encodePrettily());
            eb_send(segment, WebSocketManager.address + ".resourceDeleted", wsEvent);
            continue;
          }
          if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
              && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
            return;
          }
          log.info("[BLM] doDeleteBlockLibraries Access checks done");
          String ownerType = BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName()));
          String ownerId = BlockLibraryDao.getIdFromPk(library.getString(pk), ownerType);
          boolean isOwned = BlockLibraryDao.isOwned(library);
          log.info("[BLM] doDeleteBlockLibraries Data fetched");
          BlockLibraryDao.deleteBlockLibraryItem(libraryId, library.getString(pk));
          log.info("[BLM] doDeleteBlockLibraries library deleted");

          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .run((Segment blockingSegment) -> {
                log.info("[BLM] doDeleteBlockLibraries executeBlocking has started");
                BlockLibraryDao.deleteAllBlocks(
                    BlockLibraryDao.getBlocksByLibrary(ownerId, ownerType, libraryId, isOwned));
                log.info("[BLM] doDeleteBlockLibraries deleteAllBlocks done");
                List<Item> allSharedItems = getSharedItems(hasAccess, libraryId, null);
                log.info(
                    "[BLM] doDeleteBlockLibraries Received shared items: " + allSharedItems.size());
                allSharedItems.forEach(item -> {
                  BlockLibraryDao.deleteBlockLibraryItem(libraryId, item.getString(pk));
                  String sharedUserId = BlockLibraryDao.getIdFromPk(item.getString(Dataplane.pk));

                  JsonObject wsEvent = new JsonObject()
                      .put(Field.USER_ID.getName(), sharedUserId)
                      .put(
                          "resourceInfo",
                          new JsonObject()
                              .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK_LIBRARY)
                              .put(Field.LIBRARIES.getName(), new JsonArray().add(libraryId)));
                  log.info("[BLM] DELETED_BLOCK_LIBRARY -> allSharedItems -> "
                      + wsEvent.encodePrettily());
                  eb_send(blockingSegment, WebSocketManager.address + ".resourceDeleted", wsEvent);
                  eb_send(
                      blockingSegment,
                      MailUtil.address + ".resourceDeleted",
                      new JsonObject()
                          .put(Field.RESOURCE_ID.getName(), libraryId)
                          .put(Field.OWNER_ID.getName(), userId)
                          .put(Field.COLLABORATOR_ID.getName(), sharedUserId)
                          .put(Field.RESOURCE_NAME.getName(), libraryName)
                          .put(
                              Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCKLIBRARY.name()));
                });
                try {
                  if (library.hasAttribute(Field.S3_PATH.getName())) {
                    log.info("[BLM] doDeleteBlockLibraries Going to delete blocks");
                    BlockLibraryDao.deleteBatchBlocks(
                        s3Regional, library.getString(Field.S3_PATH.getName()));
                    log.info("[BLM] doDeleteBlockLibraries Blocks are deleted");
                  }
                } catch (Exception ex) {
                  log.error("[BLM] Exception happened on deleteBatchBlocks! " + ex);
                }
                try {
                  BlockLog blockLog = new BlockLog(
                      userId,
                      message.body().getString(Field.EMAIL.getName()),
                      GMTHelper.utcCurrentTime(),
                      BlockActions.DELETE_LIBRARY,
                      GMTHelper.utcCurrentTime(),
                      message.body().getString(Field.SESSION_ID.getName()),
                      message.body().getString(Field.DEVICE.getName()));
                  blockLog.sendToServer();
                } catch (Exception ex) {
                  log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", ex);
                }
                JsonObject wsEvent = new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(
                        "resourceInfo",
                        new JsonObject()
                            .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK_LIBRARY)
                            .put(Field.LIBRARIES.getName(), new JsonArray().add(libraryId)));
                log.info("[BLM] DELETED_BLOCK_LIBRARY -> blocking -> " + wsEvent.encodePrettily());
                eb_send(blockingSegment, WebSocketManager.address + ".resourceDeleted", wsEvent);
              });
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL2"),
              HttpStatusCodes.BAD_REQUEST,
              "BL2");
          return;
        }
      }
      sendOK(segment, message, mills);
    } catch (Exception ex) {
      log.info("[BLM] doDeleteBlockLibraries Got exception");
      log.error(ex);
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("deleteBlockLibrary", System.currentTimeMillis() - mills);
  }

  private void doUploadBlock(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    byte[] blockContent = object.getBinary(Field.BLOCK_CONTENT.getName());
    String fileName = object.getString(Field.FILE_NAME_C.getName());
    String name = Utils.isStringNotNullOrEmpty(object.getString(Field.NAME.getName()))
        ? object.getString(Field.NAME.getName())
        : fileName;
    String description = Utils.isStringNotNullOrEmpty(object.getString(Field.DESCRIPTION.getName()))
        ? object.getString(Field.DESCRIPTION.getName())
        : emptyString;
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = Utils.generateUUID();
    String userId = message.body().getString(Field.USER_ID.getName());

    try {
      Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (libraryIterator.hasNext()) {
        Item library = libraryIterator.next();
        JsonObject hasAccess = hasAccess(
            userId,
            library,
            true,
            Utils.getLocaleFromMessage(message),
            BlockLibraryDao.PermissionType.UPLOAD.name(),
            true);
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }
        if (Utils.isObjectNameLong(name)) {
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
        String ownerType = BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName()));
        String ownerId = BlockLibraryDao.getIdFromPk(library.getString(pk), ownerType);
        boolean isOwned = BlockLibraryDao.isOwned(library);
        String s3Path = library.getString(Field.S3_PATH.getName())
            + blockId
            + S3Regional.pathSeparator
            + fileName;

        if (BlockLibraryDao.getBlocksByLibraryAndName(ownerId, ownerType, libraryId, name, isOwned)
            .hasNext()) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL3"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "BL3");
          return;
        }

        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              InputStream stream = new ByteArrayInputStream(blockContent);
              ObjectMetadata metadata = new ObjectMetadata();
              metadata.setContentLength(blockContent.length);
              PutObjectRequest objectRequest =
                  new PutObjectRequest(s3Regional.getBucketName(), s3Path, stream, metadata);
              s3Regional.putObject(objectRequest);
              try {
                BlockLog blockLog = new BlockLog(
                    userId,
                    message.body().getString(Field.EMAIL.getName()),
                    GMTHelper.utcCurrentTime(),
                    BlockActions.UPLOAD_BLOCK,
                    GMTHelper.utcCurrentTime(),
                    object.getString(Field.SESSION_ID.getName()),
                    object.getString(Field.DEVICE.getName()));
                blockLog.sendToServer();
              } catch (Exception e) {
                log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", e);
              }
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".resourceUpdated",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put(
                          "resourceInfo",
                          new JsonObject()
                              .put(Field.ACTION.getName(), WebsocketAction.CREATED_BLOCK)
                              .put(Field.LIB_ID.getName(), libraryId)
                              .put(Field.BLOCK_ID.getName(), blockId)));
            });

        BlockLibraryDao.createBlock(
            ownerId, ownerType, name, description, s3Path, libraryId, blockId, isOwned, fileName);
        addLibrarySharesToBlocks(message, Collections.singletonList(blockId), libraryId);
        sendOK(segment, message, new JsonObject().put(Field.BLOCK_ID.getName(), blockId), mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL2"),
            HttpStatusCodes.BAD_REQUEST,
            "BL2");
        return;
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("uploadBlock", System.currentTimeMillis() - mills);
  }

  private void doUpdateBlock(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String name = object.getString(Field.NAME.getName());
    String description = object.getString(Field.DESCRIPTION.getName());
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = object.getString(Field.BLOCK_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (blockIterator.hasNext()) {
        Item finalUpdatedItem;
        Item block = blockIterator.next();
        JsonObject hasAccess = this.hasAccessToBlock(
            block,
            userId,
            true,
            BlockLibraryDao.PermissionType.UPDATE.name(),
            Utils.getLocaleFromMessage(message));
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }
        String ownerType = BlockLibraryDao.getOwnerType(block.getString(Field.OWNER.getName()));
        String ownerId = BlockLibraryDao.getIdFromPk(block.getString(pk), ownerType);
        boolean isOwned = BlockLibraryDao.isOwned(block);

        Iterator<Item> existingLibraryIterator =
            BlockLibraryDao.getBlocksByLibraryAndName(ownerId, ownerType, libraryId, name, isOwned);
        if (existingLibraryIterator.hasNext()
            && Stream.of(existingLibraryIterator)
                .anyMatch(item -> !item.next().getString(sk).equals(libraryId + "#" + blockId))) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL3"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "BL3");
          return;
        }

        if (block.getString(Field.NAME.getName()).equals(name)) {
          name = null;
        } else {
          if (Utils.isObjectNameLong(name)) {
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
        }

        if (block.getString(Field.DESCRIPTION.getName()).equals(description)) {
          description = null;
        }

        String fileName = null, newS3Path = null;
        if (object.containsKey(Field.BLOCK_CONTENT.getName())
            && object.containsKey(Field.FILE_NAME_C.getName())) {
          String oldFileName = block.getString(Field.FILE_NAME_C.getName());
          String finalFileName = object.getString(Field.FILE_NAME_C.getName());
          String oldS3Path = block.getString(Field.S3_PATH.getName());
          if (!oldFileName.equals(finalFileName)) {
            fileName = finalFileName;
            newS3Path = oldS3Path.substring(0, oldS3Path.lastIndexOf("/") + 1) + finalFileName;
          } else {
            newS3Path = oldS3Path;
          }

          String finalNewS3Path = newS3Path;
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("updateBlockContent")
              .runWithoutSegment(() -> {
                // deleting old file if it has a different name
                if (!oldFileName.equals(finalFileName) && s3Regional.doesObjectExist(oldS3Path)) {
                  s3Regional.delete(oldS3Path);
                }
                byte[] blockContent = object.getBinary(Field.BLOCK_CONTENT.getName());
                InputStream stream = new ByteArrayInputStream(blockContent);
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(blockContent.length);
                PutObjectRequest objectRequest = new PutObjectRequest(
                    s3Regional.getBucketName(), finalNewS3Path, stream, metadata);
                s3Regional.putObject(objectRequest);
              });
        }

        if (name == null && description == null && fileName == null && newS3Path == null) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FL8"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "FL8");
          return;
        }
        finalUpdatedItem = BlockLibraryDao.updateBlock(
            block.getString(pk), libraryId, blockId, name, description, fileName, newS3Path);

        List<Item> allSharedItems = getSharedItems(hasAccess, libraryId, blockId);

        // updating all the shared items
        for (Item item : allSharedItems) {
          String pkValue = item.getString(pk);
          Item updatedItem = BlockLibraryDao.updateBlock(
              pkValue, libraryId, blockId, name, description, fileName, newS3Path);
          if (userId.equals(BlockLibraryDao.getIdFromPk(pkValue))) {
            finalUpdatedItem = updatedItem;
          }
        }

        if (finalUpdatedItem != null) {
          sendOK(segment, message, BlockLibraryDao.itemToJson(finalUpdatedItem, false), mills);
          eb_send(
              segment,
              WebSocketManager.address + ".resourceUpdated",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(
                      "resourceInfo",
                      new JsonObject()
                          .put(Field.ACTION.getName(), WebsocketAction.UPDATED_BLOCK)
                          .put(Field.LIB_ID.getName(), libraryId)
                          .put(Field.BLOCK_ID.getName(), blockId)));
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "UpdatedItemNotReturned"),
              HttpStatusCodes.BAD_REQUEST);
          return;
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL4"),
            HttpStatusCodes.BAD_REQUEST,
            "BL4");
        return;
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("updateBlock", System.currentTimeMillis() - mills);
  }

  private void doGetBlocks(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String libraryId = message.body().getString(Field.LIB_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    JsonArray results = new JsonArray();
    try {
      Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (libraryIterator.hasNext()) {
        Item library = libraryIterator.next();
        boolean doesContainSharedBlocks = false;
        Iterator<Item> iterator = null;
        JsonObject hasAccess =
            hasAccess(userId, library, false, Utils.getLocaleFromMessage(message), null, true);
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          iterator = BlockLibraryDao.getBlocksByLibrary(
              userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), libraryId, false);
          if (!iterator.hasNext()) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
            return;
          }
          doesContainSharedBlocks = true;
        }
        String ownerType = BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName()));
        boolean isOwned = BlockLibraryDao.isOwned(library);
        if (!doesContainSharedBlocks) {
          String ownerId = BlockLibraryDao.getIdFromPk(library.getString(pk), ownerType);
          iterator = BlockLibraryDao.getBlocksByLibrary(ownerId, ownerType, libraryId, isOwned);
        }
        List<Item> blocks = Lists.newArrayList(iterator);
        List<String> blocksIds = blocks.stream()
            .map(item -> item.getString(Field.BLOCK_ID.getName()))
            .collect(Collectors.toList());
        if (!library.hasAttribute("blocksShared")) {
          addLibrarySharesToBlocks(message, blocksIds, libraryId);
          BlockLibraryDao.updateBlocksSharedForLibrary(
              library.getString(pk), library.getString(sk));
        }
        JsonArray requiredThumbnails = new JsonArray();
        List<String> excludedBlocks = BlockLibraryDao.getExcludedLibrariesOrBlocks(
            BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName())),
            userId,
            BlockLibraryDao.ObjectType.BLOCK);
        boolean finalDoesContainSharedBlocks = doesContainSharedBlocks;
        blocks.forEach(item -> {
          if (excludedBlocks != null
              && excludedBlocks.contains(item.getString(Field.BLOCK_ID.getName()))) {
            return;
          }
          JsonObject blockInfo = BlockLibraryDao.itemToJson(item, true);
          if (!isOwned && finalDoesContainSharedBlocks) {
            Iterator<Item> blockSharedItems = BlockLibraryDao.getAllSharedItems(
                libraryId, item.getString(Field.BLOCK_ID.getName()));
            if (Lists.newArrayList(blockSharedItems).stream()
                .anyMatch(share ->
                    userId.equalsIgnoreCase(BlockLibraryDao.getIdFromPk(share.getString(pk))))) {
              results.add(blockInfo);
            }
            return;
          }
          results.add(blockInfo);
          if (blockInfo.containsKey(Field.THUMBNAIL_NAME.getName())) {
            requiredThumbnails.add(new JsonObject()
                .put(Field.REQUEST_ID.getName(), Utils.generateUUID())
                .put(Field.ID.getName(), blockInfo.getString("thumbnailId"))
                .put(
                    Field.EXT.getName(),
                    Extensions.getExtension(blockInfo.getString(Field.NAME.getName())))
                .put(Field.SOURCE.getName(), item.getString(Field.S3_PATH.getName()))
                .put(Field.VERSION_ID.getName(), item.getLong(Field.MODIFIED.getName())));
          }
        });
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("createBlockThumbnails")
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  ThumbnailsManager.address + ".createForCustom",
                  new JsonObject()
                      .put(Field.IDS.getName(), requiredThumbnails)
                      .put(Field.S3_BUCKET.getName(), s3Regional.getBucketName())
                      .put(Field.S3_REGION.getName(), s3Regional.getRegion())
                      .put(Field.STORAGE_TYPE.getName(), Field.BL.getName()));
            });
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL2"),
            HttpStatusCodes.BAD_REQUEST,
            "BL2");
        return;
      }
      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("getBlocks", System.currentTimeMillis() - mills);
  }

  private void doDeleteBlocks(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String libraryId = message.body().getString(Field.LIB_ID.getName());
    JsonArray blocks = message.body().getJsonArray(Field.BLOCKS.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Object> blocksIterator = blocks.stream().iterator();
      List<String> blockList = new ArrayList<>();
      while (blocksIterator.hasNext()) {
        String blockId = (String) blocksIterator.next();
        blockList.add(blockId);
        Iterator<Item> blockIterator =
            BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
        if (blockIterator.hasNext()) {
          boolean canBlocking = false;
          Item block = blockIterator.next();
          String blockName = block.getString(Field.NAME.getName());
          JsonObject hasAccess = this.hasAccessToBlock(
              block,
              userId,
              true,
              BlockLibraryDao.PermissionType.DELETE.name(),
              Utils.getLocaleFromMessage(message));
          boolean toContinue = false;
          if (hasAccess.containsKey("selfUnSharePublicAndOrgAccess")
              && (!BlockLibraryDao.getOwnerType(block.getString(Field.OWNER.getName()))
                      .equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.ORG.name())
                  || !hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName()))) {
            BlockLibraryDao.selfUnSharePublicAndOrgObject(
                false,
                BlockLibraryDao.getOwnerType(block.getString(Field.OWNER.getName())),
                userId,
                blockId,
                true);
            toContinue = true;
          }
          if (hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
            Item user = Users.getUserById(userId);
            if (Objects.nonNull(user)) {
              message
                  .body()
                  .put(Field.HAS_ACCESS.getName(), hasAccess)
                  .put(Field.BLOCK_ID.getName(), blockId)
                  .put(
                      Field.EMAILS.getName(),
                      new JsonArray().add(user.getString(Field.USER_EMAIL.getName())));
              doUnShareBlock(message, false);
              toContinue = true;
            }
          }
          if (toContinue) {
            eb_send(
                segment,
                WebSocketManager.address + ".resourceDeleted",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(
                        "resourceInfo",
                        new JsonObject()
                            .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK)
                            .put(Field.LIB_ID.getName(), libraryId)
                            .put(Field.BLOCK_ID.getName(), blockId)));
            continue;
          }
          if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
              && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
            return;
          }
          BlockLibraryDao.deleteBlockItem(libraryId, blockId, block.getString(pk));
          if (userId.equals(BlockLibraryDao.getIdFromPk(block.getString(pk)))) {
            canBlocking = true;
          }

          if (!canBlocking) {
            List<Item> allSharedItems = getSharedItems(hasAccess, libraryId, blockId);
            allSharedItems.forEach(
                item -> BlockLibraryDao.deleteBlockItem(libraryId, blockId, item.getString(pk)));
          }

          boolean finalCanBlocking = canBlocking;
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .run((Segment blockingSegment) -> {
                if (finalCanBlocking) {
                  List<Item> allSharedItems = getSharedItems(hasAccess, libraryId, blockId);
                  allSharedItems.forEach(item -> {
                    BlockLibraryDao.deleteBlockItem(libraryId, blockId, item.getString(pk));
                    String sharedUserId = BlockLibraryDao.getIdFromPk(item.getString(Dataplane.pk));
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".resourceDeleted",
                        new JsonObject()
                            .put(Field.USER_ID.getName(), sharedUserId)
                            .put(
                                "resourceInfo",
                                new JsonObject()
                                    .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK)
                                    .put(Field.LIB_ID.getName(), libraryId)
                                    .put(Field.BLOCK_ID.getName(), blockId)));
                    eb_send(
                        blockingSegment,
                        MailUtil.address + ".resourceDeleted",
                        new JsonObject()
                            .put(Field.RESOURCE_ID.getName(), blockId)
                            .put(Field.OWNER_ID.getName(), userId)
                            .put(Field.COLLABORATOR_ID.getName(), sharedUserId)
                            .put(Field.RESOURCE_NAME.getName(), blockName)
                            .put(Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCK.name()));
                  });
                }
                if (block.hasAttribute(Field.S3_PATH.getName())
                    && s3Regional.doesObjectExist(block.getString(Field.S3_PATH.getName()))) {
                  s3Regional.delete(block.getString(Field.S3_PATH.getName()));
                }
                try {
                  BlockLog blockLog = new BlockLog(
                      userId,
                      message.body().getString(Field.EMAIL.getName()),
                      GMTHelper.utcCurrentTime(),
                      BlockActions.DELETE_BLOCK,
                      GMTHelper.utcCurrentTime(),
                      message.body().getString(Field.SESSION_ID.getName()),
                      message.body().getString(Field.DEVICE.getName()));
                  blockLog.sendToServer();
                } catch (Exception ex) {
                  log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", ex);
                }

                eb_send(
                    blockingSegment,
                    WebSocketManager.address + ".resourceDeleted",
                    new JsonObject()
                        .put(Field.USER_ID.getName(), userId)
                        .put(
                            "resourceInfo",
                            new JsonObject()
                                .put(Field.ACTION.getName(), WebsocketAction.DELETED_BLOCK)
                                .put(Field.LIB_ID.getName(), libraryId)
                                .put(Field.BLOCK_ID.getName(), blockId)));
              });
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL4"),
              HttpStatusCodes.BAD_REQUEST,
              "BL4");
          return;
        }
      }
      sendOK(segment, message, mills);
      removeLibrarySharesFromBlocks(message, blockList, libraryId);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("deleteBlock", System.currentTimeMillis() - mills);
  }

  private void doGetBlockInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String libraryId = message.body().getString(Field.LIB_ID.getName());
    String blockId = message.body().getString(Field.BLOCK_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (!blockIterator.hasNext()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL4"),
            HttpStatusCodes.BAD_REQUEST,
            "BL4");
        return;
      }
      Item block = blockIterator.next();

      JsonObject hasAccess =
          this.hasAccessToBlock(block, userId, false, null, Utils.getLocaleFromMessage(message));
      if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
          && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
        sendError(
            segment,
            message,
            hasAccess.containsKey(Field.ERROR.getName())
                ? hasAccess.getString(Field.ERROR.getName())
                : somethingWentWrongError,
            HttpStatusCodes.FORBIDDEN,
            hasAccess.containsKey(Field.ERROR_ID.getName())
                ? hasAccess.getString(Field.ERROR_ID.getName())
                : emptyErrorId);
        return;
      }
      sendOK(segment, message, BlockLibraryDao.itemToJson(block, true), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("getBlockInfo", System.currentTimeMillis() - mills);
  }

  /**
   * Check whether user has access to the block
   *
   * @param blockItem      the block item
   * @param userId         the userId
   * @param isEditRequired user required editing access for the permission or not
   * @param permission     the type of permission for actions like UPDATE, DELETE, etc.
   * @param locale         the local
   * @return the JsonObject containing the access data
   */
  private JsonObject hasAccessToBlock(
      Item blockItem, String userId, boolean isEditRequired, String permission, String locale) {
    JsonObject hasAccess = hasAccess(userId, blockItem, isEditRequired, locale, permission, false);
    if (hasAccess.containsKey(Field.HAS_ACCESS.getName())) {
      if (!hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
        if (!hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
          // check library
          JsonObject hasLibraryAccess = hasAccessToLibrary(
              blockItem.getString(Field.LIB_ID.getName()),
              userId,
              isEditRequired,
              locale,
              permission);
          if (Objects.nonNull(hasLibraryAccess)) {
            hasAccess = hasLibraryAccess;
          }
        }
      } else if ((hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName())
              || (hasAccess.containsKey("selfUnSharePublicAndOrgAccess")))
          && Utils.isStringNotNullOrEmpty(permission)
          && permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.DELETE.name())) {
        JsonObject hasLibraryAccess = hasAccessToLibrary(
            blockItem.getString(Field.LIB_ID.getName()),
            userId,
            isEditRequired,
            locale,
            permission);
        if (Objects.nonNull(hasLibraryAccess)
            && hasLibraryAccess.containsKey(Field.HAS_ACCESS.getName())
            && hasLibraryAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          if (hasLibraryAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
            hasLibraryAccess.remove(Field.SELF_UNSHARE_ACCESS.getName());
          }
          if (hasLibraryAccess.containsKey("selfUnSharePublicAndOrgAccess")) {
            hasLibraryAccess.remove("selfUnSharePublicAndOrgAccess");
          }
          hasAccess = hasLibraryAccess;
        }
      }
    }
    return hasAccess;
  }

  private JsonObject hasAccessToLibrary(
      String libraryId, String userId, boolean isEditRequired, String locale, String permission) {
    Iterator<Item> blockLibraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
    if (blockLibraryIterator.hasNext()) {
      return hasAccess(
          userId, blockLibraryIterator.next(), isEditRequired, locale, permission, true);
    }
    return null;
  }

  private void doGetBlockContent(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String libraryId = message.body().getString(Field.LIB_ID.getName());
    String blockId = message.body().getString(Field.BLOCK_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (!blockIterator.hasNext()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL4"),
            HttpStatusCodes.BAD_REQUEST,
            "BL4");
        return;
      }
      Item blockItem = blockIterator.next();
      JsonObject hasAccess = this.hasAccessToBlock(
          blockItem, userId, false, null, Utils.getLocaleFromMessage(message));
      if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
          && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
        sendError(
            segment,
            message,
            hasAccess.containsKey(Field.ERROR.getName())
                ? hasAccess.getString(Field.ERROR.getName())
                : somethingWentWrongError,
            HttpStatusCodes.FORBIDDEN,
            hasAccess.containsKey(Field.ERROR_ID.getName())
                ? hasAccess.getString(Field.ERROR_ID.getName())
                : emptyErrorId);
        return;
      }
      if (blockItem.hasAttribute(Field.FILE_NAME_C.getName())
          && blockItem.hasAttribute(Field.S3_PATH.getName())
          && s3Regional.doesObjectExist(blockItem.getString(Field.S3_PATH.getName()))) {
        byte[] data = s3Regional.get(blockItem.getString(Field.S3_PATH.getName()));
        sendOK(segment, message, new JsonObject().put(Field.DATA.getName(), data), mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL5"),
            HttpStatusCodes.BAD_REQUEST,
            "BL5");
        return;
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("getBlockContent", System.currentTimeMillis() - mills);
  }

  private void doSearchBlockLibrary(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String term = object.getString("term");
    String userId = object.getString(Field.USER_ID.getName());
    String libraryId = object.getString(Field.LIB_ID.getName());
    String type = object.getString(Field.TYPE.getName());
    JsonArray blockLibraries = new JsonArray();
    JsonArray blocks = new JsonArray();
    List<Future<Iterator<Item>>> blockLibraryFutures = new ArrayList<>();
    List<Future<Iterator<Item>>> blockFutures = new ArrayList<>();
    List<Future<Void>> mainFutures = new ArrayList<>();

    if (term.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "SearchTermCouldNotBeEmpty"),
          HttpStatusCodes.PRECONDITION_FAILED);
      return;
    }
    try {
      if (Utils.isStringNotNullOrEmpty(libraryId)) {
        Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
        if (libraryIterator.hasNext()) {
          Item library = libraryIterator.next();
          JsonObject hasAccess =
              hasAccess(userId, library, false, Utils.getLocaleFromMessage(message), null, true);
          if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
              && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
            return;
          }
          String ownerType = BlockLibraryDao.getOwnerType(library.getString(Field.OWNER.getName()));
          String ownerId = BlockLibraryDao.getIdFromPk(library.getString(pk), ownerType);
          boolean isOwned = BlockLibraryDao.isOwned(library);
          Iterator<Item> iterator =
              BlockLibraryDao.getBlocksByLibrary(ownerId, ownerType, libraryId, isOwned);
          iterator.forEachRemaining(item -> {
            if (isPatternMatched(
                term,
                item.getString(Field.NAME.getName()),
                item.getString(Field.DESCRIPTION.getName()),
                item.getString(Field.FILE_NAME_C.getName()))) {
              blocks.add(BlockLibraryDao.itemToJson(item, true));
            }
          });
          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(
                      Field.RESULTS.getName(),
                      new JsonObject()
                          .put(Field.BLOCKS.getName(), blocks)
                          .put(Field.BLOCK_LIBRARIES.getName(), blockLibraries)),
              mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL2"),
              HttpStatusCodes.BAD_REQUEST,
              "BL2");
          return;
        }
      } else {
        Item user = Users.getUserById(userId);
        if (user != null) {
          if (user.hasAttribute(Field.ORGANIZATION_ID.getName())) {
            Item org = Users.getOrganizationById(user.getString(Field.ORGANIZATION_ID.getName()));
            if (org != null) {
              String orgId = org.getString(sk);

              // get block libraries for org
              if (!Utils.isStringNotNullOrEmpty(type)
                  || type.equals(BlockLibraryDao.ObjectType.LIBRARY.name())) {
                blockLibraryFutures.add(getBlockOrLibraries(
                    orgId, BlockLibraryDao.BlockLibraryOwnerType.ORG.name(), true, true));
                blockLibraryFutures.add(getBlockOrLibraries(
                    orgId, BlockLibraryDao.BlockLibraryOwnerType.ORG.name(), false, true));
              }

              // get blocks for org
              if (!Utils.isStringNotNullOrEmpty(type)
                  || type.equals(BlockLibraryDao.ObjectType.BLOCK.name())) {
                blockFutures.add(getBlockOrLibraries(
                    orgId, BlockLibraryDao.BlockLibraryOwnerType.ORG.name(), true, false));
                blockFutures.add(getBlockOrLibraries(
                    orgId, BlockLibraryDao.BlockLibraryOwnerType.ORG.name(), false, false));
              }
            }
          }
          // AS : need to implement for GROUP later

          // get block libraries
          if (!Utils.isStringNotNullOrEmpty(type)
              || type.equals(BlockLibraryDao.ObjectType.LIBRARY.name())) {
            blockLibraryFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), true, true));
            blockLibraryFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), false, true));
            blockLibraryFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name(), true, true));
          }

          // get blocks
          if (!Utils.isStringNotNullOrEmpty(type)
              || type.equals(BlockLibraryDao.ObjectType.BLOCK.name())) {
            blockFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), true, false));
            blockFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.USER.name(), false, false));
            blockFutures.add(getBlockOrLibraries(
                userId, BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name(), true, false));
          }

          if (Utils.isListNotNullOrEmpty(blockLibraryFutures)) {
            Promise<Void> blockLibraryPromise = Promise.promise();
            TypedCompositeFuture.join(blockLibraryFutures).onComplete(ar -> {
              List<Iterator<Item>> list = ar.result().list();

              list.forEach(itemIterator -> itemIterator.forEachRemaining(item -> {
                if (isPatternMatched(
                    term,
                    item.getString(Field.NAME.getName()),
                    item.getString(Field.DESCRIPTION.getName()),
                    null)) {
                  blockLibraries.add(BlockLibraryDao.itemToJson(item, true));
                }
              }));

              blockLibraryPromise.complete();
            });
            mainFutures.add(blockLibraryPromise.future());
          }

          if (Utils.isListNotNullOrEmpty(blockFutures)) {
            Promise<Void> blockPromise = Promise.promise();
            TypedCompositeFuture.join(blockFutures).onComplete(ar -> {
              List<Iterator<Item>> list = ar.result().list();

              list.forEach(itemIterator -> itemIterator.forEachRemaining(item -> {
                if (isPatternMatched(
                    term,
                    item.getString(Field.NAME.getName()),
                    item.getString(Field.DESCRIPTION.getName()),
                    item.getString(Field.FILE_NAME_C.getName()))) {
                  blocks.add(BlockLibraryDao.itemToJson(item, true));
                }
              }));

              blockPromise.complete();
            });
            mainFutures.add(blockPromise.future());
          }

          TypedCompositeFuture.join(mainFutures)
              .onComplete(ar -> sendOK(
                  segment,
                  message,
                  new JsonObject()
                      .put(
                          Field.RESULTS.getName(),
                          new JsonObject()
                              .put(Field.BLOCKS.getName(), blocks)
                              .put(Field.BLOCK_LIBRARIES.getName(), blockLibraries)),
                  mills));
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "UserDoesNotExist"),
              HttpStatusCodes.BAD_REQUEST);
          return;
        }
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("searchBlockLibrary", System.currentTimeMillis() - mills);
  }

  private void doShareBlockLibrary(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonArray errors;
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    String userId = object.getString(Field.USER_ID.getName());
    JsonArray emails = object.getJsonArray(Field.EMAILS.getName());
    String libraryName;
    try {
      Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (libraryIterator.hasNext()) {
        Item library = libraryIterator.next();
        libraryName = library.getString(Field.NAME.getName());
        JsonObject hasAccess = hasAccess(
            userId,
            library,
            true,
            Utils.getLocaleFromMessage(message),
            BlockLibraryDao.PermissionType.SHARE.name(),
            true);
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }
        errors = shareToUsers(message, emails, libraryId, null, library);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "BL2"),
            HttpStatusCodes.BAD_REQUEST,
            "BL2");
        return;
      }
      sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
      String finalLibraryName = libraryName;
      Thread websocketNotifications = new Thread(() -> {
        Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

        try {
          BlockLog blockLog = new BlockLog(
              userId,
              message.body().getString(Field.EMAIL.getName()),
              GMTHelper.utcCurrentTime(),
              BlockActions.SHARE_LIBRARY,
              GMTHelper.utcCurrentTime(),
              message.body().getString(Field.SESSION_ID.getName()),
              message.body().getString(Field.DEVICE.getName()));
          blockLog.sendToServer();
        } catch (Exception e) {
          log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", e);
        }
        eb_send(
            blockingSegment,
            WebSocketManager.address + ".resourceUpdated",
            new JsonObject()
                .put(Field.USER_ID.getName(), userId)
                .put(
                    "resourceInfo",
                    new JsonObject()
                        .put(Field.ACTION.getName(), WebsocketAction.SHARED_BLOCK_LIBRARY)
                        .put(Field.LIB_ID.getName(), libraryId)));
        emails.forEach(email -> {
          JsonObject emailObj = (JsonObject) email;
          Iterator<Item> userIterator =
              Users.getUserByEmail(emailObj.getString(Field.ID.getName()));
          if (userIterator.hasNext()) {
            Item user = userIterator.next();
            String sharedUserId = user.getString(sk);
            eb_send(
                blockingSegment,
                WebSocketManager.address + ".resourceUpdated",
                new JsonObject()
                    .put(Field.USER_ID.getName(), sharedUserId)
                    .put(
                        "resourceInfo",
                        new JsonObject()
                            .put(Field.ACTION.getName(), WebsocketAction.SHARED_BLOCK_LIBRARY)
                            .put(Field.LIB_ID.getName(), libraryId)));
            eb_send(
                blockingSegment,
                MailUtil.address + ".resourceShared",
                new JsonObject()
                    .put(Field.RESOURCE_ID.getName(), libraryId)
                    .put(Field.OWNER_ID.getName(), userId)
                    .put(Field.COLLABORATOR_ID.getName(), sharedUserId)
                    .put(Field.RESOURCE_THUMBNAIL.getName(), "")
                    .put(Field.RESOURCE_NAME.getName(), finalLibraryName)
                    .put(Field.RESOURCE_PARENT_ID.getName(), Field.MINUS_1.getName())
                    .put(Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCKLIBRARY.name()));
          }
        });

        XRayManager.endSegment(blockingSegment);
      });
      websocketNotifications.start();
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("shareBlockLibrary", System.currentTimeMillis() - mills);
  }

  private void doUnShareBlockLibrary(Message<JsonObject> message) {
    doUnShareBlockLibrary(message, true);
  }

  private void doUnShareBlockLibrary(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    JsonArray errors = new JsonArray();
    String userId = object.getString(Field.USER_ID.getName());
    JsonArray emails = object.getJsonArray(Field.EMAILS.getName());
    JsonObject hasAccessObject = object.getJsonObject(Field.HAS_ACCESS.getName());
    String libraryName;
    List<String> unsharedUserIds = new ArrayList<>(emails.size());
    try {
      Item library;
      boolean accessible, selfUnShareAccess;
      Iterator<Item> libraryIterator = BlockLibraryDao.getBlockLibraryByLibraryId(libraryId);
      if (libraryIterator.hasNext()) {
        library = libraryIterator.next();
        libraryName = library.getString(Field.NAME.getName());
        JsonObject hasAccess;
        if (Utils.isJsonObjectNotNullOrEmpty(hasAccessObject)) {
          hasAccess = hasAccessObject;
        } else {
          hasAccess = hasAccess(
              userId,
              library,
              true,
              Utils.getLocaleFromMessage(message),
              BlockLibraryDao.PermissionType.UNSHARE.name(),
              true);
        }
        selfUnShareAccess = hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName());
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())
            && !selfUnShareAccess) {
          if (reply) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
          }
          return;
        }
        accessible = hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && hasAccess.getBoolean(Field.HAS_ACCESS.getName());
      } else {
        if (reply) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL2"),
              HttpStatusCodes.BAD_REQUEST,
              "BL2");
        }
        return;
      }
      boolean finalSelfUnShareAccess = selfUnShareAccess;
      emails.forEach(email -> {
        Iterator<Item> userIterator = Users.getUserByEmail((String) email);
        if (userIterator.hasNext()) {
          Item user = userIterator.next();
          String sharedUserId = user.getString(sk);
          unsharedUserIds.add(sharedUserId);
          if (finalSelfUnShareAccess && !accessible) {
            if (!sharedUserId.equals(userId)) {
              errors.add(new JsonObject()
                  .put(Field.EMAIL.getName(), sharedUserId)
                  .put(Field.ERROR.getName(), Utils.getLocalizedString(message, "BL25")));
              return;
            }
          }
          BlockLibraryDao.deleteBlockLibraryItem(
              libraryId,
              BlockLibraryDao.makeLibraryPkValue(
                  sharedUserId, BlockLibraryDao.BlockLibraryOwnerType.USER, false));
        }
      });
      String owner = library.getString(Field.OWNER.getName());
      boolean isOwned = BlockLibraryDao.isOwned(library);
      Iterator<Item> blocks = BlockLibraryDao.getBlocksByLibrary(
          BlockLibraryDao.getOwnerId(owner),
          BlockLibraryDao.getOwnerType(owner),
          libraryId,
          isOwned);
      // Also unShare each block inside this library
      blocks.forEachRemaining(block -> {
        message.body().put(Field.BLOCK_ID.getName(), block.getString(Field.BLOCK_ID.getName()));
        doUnShareBlock(message, false);
      });
      if (reply) {
        sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
      }

      String finalLibraryName = libraryName;
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            try {
              BlockLog blockLog = new BlockLog(
                  userId,
                  message.body().getString(Field.EMAIL.getName()),
                  GMTHelper.utcCurrentTime(),
                  BlockActions.UNSHARE_LIBRARY,
                  GMTHelper.utcCurrentTime(),
                  message.body().getString(Field.SESSION_ID.getName()),
                  message.body().getString(Field.DEVICE.getName()));
              blockLog.sendToServer();
            } catch (Exception ex) {
              log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", ex);
            }
            unsharedUserIds.forEach(unsharedUserId -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".resourceDeleted",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), unsharedUserId)
                      .put(
                          "resourceInfo",
                          new JsonObject()
                              .put(Field.ACTION.getName(), WebsocketAction.UNSHARED_BLOCK_LIBRARY)
                              .put(Field.LIBRARIES.getName(), new JsonArray().add(libraryId))));
              if (reply) {
                eb_send(
                    blockingSegment,
                    MailUtil.address + ".resourceUnshared",
                    new JsonObject()
                        .put(Field.RESOURCE_ID.getName(), libraryId)
                        .put(Field.OWNER_ID.getName(), userId)
                        .put(Field.COLLABORATOR_ID.getName(), unsharedUserId)
                        .put(Field.RESOURCE_THUMBNAIL.getName(), "")
                        .put(Field.RESOURCE_NAME.getName(), finalLibraryName)
                        .put(Field.RESOURCE_PARENT_ID.getName(), Field.MINUS_1.getName())
                        .put(Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCKLIBRARY.name()));
              }
            });
          });
    } catch (Exception ex) {
      if (reply) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      }
      return;
    }
    recordExecutionTime("unShareBlockLibrary", System.currentTimeMillis() - mills);
  }

  private void doShareBlock(Message<JsonObject> message) {
    doShareBlock(message, true);
  }

  private void doShareBlock(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = object.getString(Field.BLOCK_ID.getName());
    String userId = object.getString(Field.USER_ID.getName());
    JsonArray emails = object.getJsonArray(Field.EMAILS.getName());
    JsonArray errors;
    String blockName, lastModified;
    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (blockIterator.hasNext()) {
        Item block = blockIterator.next();
        blockName = block.getString(Field.NAME.getName());
        lastModified = String.valueOf(block.getLong(Field.MODIFIED.getName()));
        JsonObject hasAccess = this.hasAccessToBlock(
            block,
            userId,
            true,
            BlockLibraryDao.PermissionType.SHARE.name(),
            Utils.getLocaleFromMessage(message));
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          if (reply) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
          }
          return;
        }
        errors = shareToUsers(message, emails, libraryId, blockId, block);
      } else {
        if (reply) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL4"),
              HttpStatusCodes.BAD_REQUEST,
              "BL4");
        }
        return;
      }
      if (reply) {
        sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
      }
      String finalBlockName = blockName;
      String finalLastModified = lastModified;
      Thread websocketNotifications = new Thread(() -> {
        Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

        try {
          BlockLog blockLog = new BlockLog(
              userId,
              message.body().getString(Field.EMAIL.getName()),
              GMTHelper.utcCurrentTime(),
              BlockActions.SHARE_BLOCK,
              GMTHelper.utcCurrentTime(),
              message.body().getString(Field.SESSION_ID.getName()),
              message.body().getString(Field.DEVICE.getName()));
          blockLog.sendToServer();
        } catch (Exception e) {
          log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", e);
        }

        String thumbnailId = libraryId + "_" + blockId;
        String thumbnailName =
            ThumbnailsManager.getThumbnailName(Field.BL.getName(), thumbnailId, finalLastModified);
        String thumbnailURL = ThumbnailsManager.getThumbnailURL(config, thumbnailName, true);
        eb_send(
            blockingSegment,
            WebSocketManager.address + ".resourceUpdated",
            new JsonObject()
                .put(Field.USER_ID.getName(), userId)
                .put(
                    "resourceInfo",
                    new JsonObject()
                        .put(Field.ACTION.getName(), WebsocketAction.SHARED_BLOCK)
                        .put(Field.LIB_ID.getName(), libraryId)
                        .put(Field.BLOCK_ID.getName(), blockId)));
        emails.forEach(email -> {
          JsonObject emailObj = (JsonObject) email;
          Iterator<Item> userIterator =
              Users.getUserByEmail(emailObj.getString(Field.ID.getName()));
          if (userIterator.hasNext()) {
            Item user = userIterator.next();
            String sharedUserId = user.getString(sk);
            eb_send(
                blockingSegment,
                WebSocketManager.address + ".resourceUpdated",
                new JsonObject()
                    .put(Field.USER_ID.getName(), sharedUserId)
                    .put(
                        "resourceInfo",
                        new JsonObject()
                            .put(Field.ACTION.getName(), WebsocketAction.SHARED_BLOCK)
                            .put(Field.LIB_ID.getName(), libraryId)
                            .put(Field.BLOCK_ID.getName(), blockId)));
            if (reply) {
              eb_send(
                  blockingSegment,
                  MailUtil.address + ".resourceShared",
                  new JsonObject()
                      .put(Field.RESOURCE_ID.getName(), libraryId)
                      .put(Field.OWNER_ID.getName(), userId)
                      .put(Field.COLLABORATOR_ID.getName(), sharedUserId)
                      .put(Field.RESOURCE_THUMBNAIL.getName(), thumbnailURL)
                      .put(Field.RESOURCE_NAME.getName(), finalBlockName)
                      .put(Field.RESOURCE_PARENT_ID.getName(), libraryId)
                      .put(Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCK.name()));
            }
          }
        });

        XRayManager.endSegment(blockingSegment);
      });
      websocketNotifications.start();
    } catch (Exception ex) {
      if (reply) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      }
      return;
    }
    recordExecutionTime("shareBlock", System.currentTimeMillis() - mills);
  }

  public void doUnShareBlock(Message<JsonObject> message) {
    doUnShareBlock(message, true);
  }

  private void doUnShareBlock(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = object.getString(Field.BLOCK_ID.getName());
    String userId = object.getString(Field.USER_ID.getName());
    JsonArray emails = object.getJsonArray(Field.EMAILS.getName());
    JsonObject hasAccessObject = object.getJsonObject(Field.HAS_ACCESS.getName());
    JsonArray errors = new JsonArray();
    String blockName, lastModified;
    try {
      boolean accessible, selfUnShareAccess;
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (blockIterator.hasNext()) {
        Item block = blockIterator.next();
        blockName = block.getString(Field.NAME.getName());
        lastModified = String.valueOf(block.getLong(Field.MODIFIED.getName()));
        JsonObject hasAccess;
        if (Utils.isJsonObjectNotNullOrEmpty(hasAccessObject)) {
          hasAccess = hasAccessObject;
        } else {
          hasAccess = this.hasAccessToBlock(
              block,
              userId,
              true,
              BlockLibraryDao.PermissionType.UNSHARE.name(),
              Utils.getLocaleFromMessage(message));
        }
        selfUnShareAccess = hasAccess.containsKey(Field.SELF_UNSHARE_ACCESS.getName());
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())
            && !selfUnShareAccess) {
          if (reply) {
            sendError(
                segment,
                message,
                hasAccess.containsKey(Field.ERROR.getName())
                    ? hasAccess.getString(Field.ERROR.getName())
                    : somethingWentWrongError,
                HttpStatusCodes.FORBIDDEN,
                hasAccess.containsKey(Field.ERROR_ID.getName())
                    ? hasAccess.getString(Field.ERROR_ID.getName())
                    : emptyErrorId);
          }
          return;
        }
        accessible = hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && hasAccess.getBoolean(Field.HAS_ACCESS.getName());
      } else {
        if (reply) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL4"),
              HttpStatusCodes.BAD_REQUEST,
              "BL4");
        }
        return;
      }
      boolean finalSelfUnShareAccess = selfUnShareAccess;
      emails.forEach(email -> {
        Iterator<Item> userIterator = Users.getUserByEmail((String) email);
        if (userIterator.hasNext()) {
          Item user = userIterator.next();
          String sharedUserId = user.getString(sk);
          if (finalSelfUnShareAccess && !accessible) {
            if (!sharedUserId.equals(userId)) {
              errors.add(new JsonObject()
                  .put(Field.EMAIL.getName(), sharedUserId)
                  .put(Field.ERROR.getName(), Utils.getLocalizedString(message, "BL25")));
              return;
            }
          }
          BlockLibraryDao.deleteBlockItem(
              libraryId,
              blockId,
              BlockLibraryDao.makeBlockPkValue(
                  sharedUserId, BlockLibraryDao.BlockLibraryOwnerType.USER, false));
        }
      });
      String finalBlockName = blockName;
      String finalLastModified = lastModified;
      if (reply) {
        sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
      }
      Thread websocketNotifications = new Thread(() -> {
        Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

        try {
          BlockLog blockLog = new BlockLog(
              userId,
              message.body().getString(Field.EMAIL.getName()),
              GMTHelper.utcCurrentTime(),
              BlockActions.UNSHARE_BLOCK,
              GMTHelper.utcCurrentTime(),
              message.body().getString(Field.SESSION_ID.getName()),
              message.body().getString(Field.DEVICE.getName()));
          blockLog.sendToServer();
        } catch (Exception e) {
          log.error(LogPrefix.getLogPrefix() + " Error on creating block log: ", e);
        }

        String thumbnailId = libraryId + "_" + blockId;
        String thumbnailName =
            ThumbnailsManager.getThumbnailName(Field.BL.getName(), thumbnailId, finalLastModified);
        String thumbnailURL = ThumbnailsManager.getThumbnailURL(config, thumbnailName, true);
        emails.forEach(email -> {
          Iterator<Item> userIterator = Users.getUserByEmail((String) email);
          if (userIterator.hasNext()) {
            Item user = userIterator.next();
            String sharedUserId = user.getString(sk);
            eb_send(
                blockingSegment,
                WebSocketManager.address + ".resourceUpdated",
                new JsonObject()
                    .put(Field.USER_ID.getName(), sharedUserId)
                    .put(
                        "resourceInfo",
                        new JsonObject()
                            .put(Field.ACTION.getName(), WebsocketAction.UNSHARED_BLOCK)
                            .put(Field.LIB_ID.getName(), libraryId)
                            .put(Field.BLOCK_ID.getName(), blockId)));

            if (reply) {
              eb_send(
                  blockingSegment,
                  MailUtil.address + ".resourceUnshared",
                  new JsonObject()
                      .put(Field.RESOURCE_ID.getName(), libraryId)
                      .put(Field.OWNER_ID.getName(), userId)
                      .put(Field.COLLABORATOR_ID.getName(), sharedUserId)
                      .put(Field.RESOURCE_THUMBNAIL.getName(), thumbnailURL)
                      .put(Field.RESOURCE_NAME.getName(), finalBlockName)
                      .put(Field.RESOURCE_PARENT_ID.getName(), libraryId)
                      .put(Field.RESOURCE_TYPE.getName(), OldResourceTypes.BLOCK.name()));
            }
          }
        });
      });
      websocketNotifications.start();
    } catch (Exception ex) {
      if (reply) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      }
      return;
    }
    recordExecutionTime("unShareBlock", System.currentTimeMillis() - mills);
  }

  private void doLockBlock(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = object.getString(Field.BLOCK_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (blockIterator.hasNext()) {
        Item block = blockIterator.next();
        JsonObject hasAccess = this.hasAccessToBlock(
            block,
            userId,
            true,
            BlockLibraryDao.PermissionType.UPDATE.name(),
            Utils.getLocaleFromMessage(message));
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }

        Item itemLock = BlockLibraryDao.findItemLock(libraryId, blockId);
        if (itemLock != null) {
          sendError(
              segment,
              message,
              new JsonObject()
                  .put(
                      Field.MESSAGE.getName(),
                      MessageFormat.format(
                          Utils.getLocalizedString(message, "BL30"),
                          itemLock.getString(Field.EMAIL.getName())))
                  .put(
                      Field.IS_OWNER.getName(),
                      itemLock.getString(Field.USER_ID.getName()).equals(userId)),
              HttpStatusCodes.FORBIDDEN,
              "BL30");
          return;
        }

        BlockLibraryDao.lockBlock(
            libraryId, blockId, userId, object.getString(Field.EMAIL.getName()));
        sendOK(segment, message);
      }

    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("lockBlock", System.currentTimeMillis() - mills);
  }

  private void doUnlockBlock(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();
    String libraryId = object.getString(Field.LIB_ID.getName());
    String blockId = object.getString(Field.BLOCK_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    try {
      Iterator<Item> blockIterator =
          BlockLibraryDao.getBlockByLibraryIdAndBlockId(libraryId, blockId);
      if (blockIterator.hasNext()) {
        Item block = blockIterator.next();
        JsonObject hasAccess = this.hasAccessToBlock(
            block,
            userId,
            true,
            BlockLibraryDao.PermissionType.UPDATE.name(),
            Utils.getLocaleFromMessage(message));
        if (hasAccess.containsKey(Field.HAS_ACCESS.getName())
            && !hasAccess.getBoolean(Field.HAS_ACCESS.getName())) {
          sendError(
              segment,
              message,
              hasAccess.containsKey(Field.ERROR.getName())
                  ? hasAccess.getString(Field.ERROR.getName())
                  : somethingWentWrongError,
              HttpStatusCodes.FORBIDDEN,
              hasAccess.containsKey(Field.ERROR_ID.getName())
                  ? hasAccess.getString(Field.ERROR_ID.getName())
                  : emptyErrorId);
          return;
        }

        Item itemLock = BlockLibraryDao.findItemLock(libraryId, blockId);
        if (itemLock == null) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "BL31"),
              HttpStatusCodes.PRECONDITION_FAILED,
              "BL31");
          return;
        }

        if (!itemLock.getString(Field.USER_ID.getName()).equals(userId)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "BL30"),
                  itemLock.getString(Field.EMAIL.getName())),
              HttpStatusCodes.FORBIDDEN,
              "BL30");
          return;
        }

        BlockLibraryDao.unlockBlock(libraryId, blockId);
        sendOK(segment, message);
      }

    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatusCodes.SERVER_ERROR, ex);
      return;
    }
    recordExecutionTime("unlockBlock", System.currentTimeMillis() - mills);
  }

  /**
   * Share block or library to users.
   *
   * @param emails         the array
   * @param libraryId      the libraryId
   * @param blockId        the blockId
   * @param libraryOrBlock the library or block item
   * @return the list of errors
   */
  private JsonArray shareToUsers(
      Message<JsonObject> message,
      JsonArray emails,
      String libraryId,
      String blockId,
      Item libraryOrBlock) {
    JsonArray errors = new JsonArray();
    Iterator<Item> sharedIterator = BlockLibraryDao.getAllSharedItems(libraryId, blockId);
    List<Item> allShared = Lists.newArrayList(sharedIterator);
    emails.forEach(email -> {
      JsonObject emailObj = (JsonObject) email;
      if (!Utils.isStringNotNullOrEmpty(emailObj.getString(Field.MODE.getName()))) {
        return;
      }
      String mode =
          BlockLibraryDao.AccessType.getAccessType(emailObj.getString(Field.MODE.getName())).value;
      Iterator<Item> userIterator = Users.getUserByEmail(emailObj.getString(Field.ID.getName()));
      if (userIterator.hasNext()) {
        Item user = userIterator.next();
        String sharedUserId = user.getString(sk);
        if (sharedUserId.equals(
            BlockLibraryDao.getOwnerId(libraryOrBlock.getString(Field.OWNER.getName())))) {
          errors.add(new JsonObject()
              .put(Field.EMAIL.getName(), emailObj.getString(Field.ID.getName()))
              .put(
                  Field.ERROR.getName(),
                  Utils.getLocalizedString(message, (blockId == null) ? "BL6" : "BL7")));
          return;
        }
        Optional<Item> optional = allShared.stream()
            .filter(
                shared -> BlockLibraryDao.getIdFromPk(shared.getString(pk)).equals(sharedUserId))
            .findAny();
        String owner = libraryOrBlock.getString(Field.OWNER.getName());
        if (optional.isPresent()) {
          Item sharedLibraryOrBlock = optional.get();
          if (!sharedLibraryOrBlock.getString(Field.MODE.getName()).equalsIgnoreCase(mode)) {
            BlockLibraryDao.updateSharedItem(
                sharedLibraryOrBlock.getString(pk), sharedLibraryOrBlock.getString(sk), mode);
          }
        } else {
          if (blockId == null) {
            BlockLibraryDao.shareBlockLibrary(libraryOrBlock, sharedUserId, mode);
          } else {
            BlockLibraryDao.shareBlock(libraryOrBlock, sharedUserId, mode);
          }
          String ownerType = BlockLibraryDao.getOwnerType(owner);
          if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.ORG.name())) {
            boolean isBlockLibrary = blockId == null;
            String objectId = isBlockLibrary
                ? libraryOrBlock.getString(Field.SK.getName())
                : libraryOrBlock.getString(Field.BLOCK_ID.getName());
            BlockLibraryDao.selfUnSharePublicAndOrgObject(
                isBlockLibrary, ownerType, sharedUserId, objectId, false);
          }
        }
        if (blockId == null) {
          boolean isOwned = BlockLibraryDao.isOwned(libraryOrBlock);
          Iterator<Item> blocks = BlockLibraryDao.getBlocksByLibrary(
              BlockLibraryDao.getOwnerId(owner),
              BlockLibraryDao.getOwnerType(owner),
              libraryId,
              optional.isEmpty() || isOwned);
          // Also share it to each block inside this library
          blocks.forEachRemaining(block -> {
            message.body().put(Field.BLOCK_ID.getName(), block.getString(Field.BLOCK_ID.getName()));
            doShareBlock(message, false);
          });
        }
      } else {
        errors.add(new JsonObject()
            .put(Field.EMAIL.getName(), emailObj.getString(Field.ID.getName()))
            .put(Field.ERROR.getName(), Utils.getLocalizedString(message, "UserDoesNotExist")));
      }
    });
    return errors;
  }

  /**
   * Get blocks or libraries.
   *
   * @param ownerId        the ownerId
   * @param type           the ownerType
   * @param isOwned        current item is owned by user or not
   * @param isBlockLibrary current item is library or block
   * @return the Future object
   */
  private Future<Iterator<Item>> getBlockOrLibraries(
      String ownerId, String type, boolean isOwned, boolean isBlockLibrary) {
    Promise<Iterator<Item>> promise = Promise.promise();
    Iterator<Item> iterator =
        BlockLibraryDao.getBlocksOrLibraries(ownerId, type, isOwned, isBlockLibrary);
    promise.complete(iterator);
    return promise.future();
  }

  /**
   * Is search filter matched with the item properties
   *
   * @param filter      the search filter
   * @param name        the item name
   * @param description the item description
   * @param fileName    the fileName of the block
   * @return the boolean object
   */
  private static boolean isPatternMatched(
      String filter, String name, String description, String fileName) {
    Pattern pattern = Pattern.compile(Pattern.quote(filter), Pattern.CASE_INSENSITIVE);
    boolean isNameFound =
        Utils.isStringNotNullOrEmpty(name) && pattern.matcher(name).find();
    boolean isDescriptionFound = Utils.isStringNotNullOrEmpty(description)
        && pattern.matcher(description).find();
    boolean isFileNameFound =
        Utils.isStringNotNullOrEmpty(fileName) && pattern.matcher(fileName).find();
    return isNameFound || isDescriptionFound || isFileNameFound;
  }

  /**
   * Check whether user has access to create or get block libraries
   *
   * @param ownerId        the ownerId
   * @param userId         the userId
   * @param ownerType      the ownerType
   * @param isEditRequired user required editing access for the permission or not
   * @param locale         the local
   * @return the JsonObject containing the access data
   */
  private static JsonObject hasAccessToCreateOrGetLibraries(
      String ownerId, String userId, String ownerType, boolean isEditRequired, String locale) {
    JsonObject accessObject =
        checkAccessForOwnerType(userId, ownerId, ownerType, isEditRequired, locale, null, null);
    boolean hasAccess = accessObject.getBoolean(Field.HAS_ACCESS.getName());

    if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.GROUP.name())) {
      hasAccess = true;
    }

    if (!hasAccess && !accessObject.containsKey(Field.ERROR.getName())) {
      accessObject
          .put(Field.ERROR_ID.getName(), isEditRequired ? "BL9" : "BL8")
          .put(
              Field.ERROR.getName(),
              Utils.getLocalizedString(locale, isEditRequired ? "BL9" : "BL8"));
    }
    accessObject.put(Field.HAS_ACCESS.getName(), hasAccess);
    return accessObject;
  }

  /**
   * Check whether user has access to the item
   *
   * @param userId         the userId
   * @param libraryOrBlock the library or block item
   * @param isEditRequired user required editing access for the permission or not
   * @param locale         the local
   * @param permission     the type of permission for actions like UPDATE, DELETE, etc.
   * @param isBlockLibrary current item is library or block
   * @return the JsonObject containing the access data
   */
  private static JsonObject hasAccess(
      String userId,
      Item libraryOrBlock,
      boolean isEditRequired,
      String locale,
      String permission,
      boolean isBlockLibrary) {
    String pkValue = libraryOrBlock.getString(pk);
    String ownerType =
        BlockLibraryDao.getOwnerType(libraryOrBlock.getString(Field.OWNER.getName()));
    String ownerId = BlockLibraryDao.getIdFromPk(pkValue, ownerType);
    String errorId = getErrorIdFromPermission(permission, isBlockLibrary);
    JsonObject accessObject = checkAccessForOwnerType(
        userId, ownerId, ownerType, isEditRequired, locale, errorId, permission);
    boolean hasAccess = accessObject.getBoolean(Field.HAS_ACCESS.getName());
    // check if the object is shared with the user
    if (!hasAccess
        && !ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name())) {
      Iterator<Item> sharedIterator = BlockLibraryDao.getAllSharedItems(
          isBlockLibrary
              ? libraryOrBlock.getString(sk)
              : libraryOrBlock.getString(Field.LIB_ID.getName()),
          isBlockLibrary ? null : libraryOrBlock.getString(Field.BLOCK_ID.getName()));

      List<String> allSharedItems = new ArrayList<>();
      while (sharedIterator.hasNext()) {
        Item sharedItem = sharedIterator.next();
        allSharedItems.add(sharedItem.toJSON());
        if (BlockLibraryDao.getIdFromPk(sharedItem.getString(pk)).equals(userId)) {
          if (!isEditRequired) {
            hasAccess = true;
          } else {
            if (sharedItem.hasAttribute(Field.MODE.getName())
                && BlockLibraryDao.AccessType.getAccessType(
                        sharedItem.getString(Field.MODE.getName()))
                    .equals(BlockLibraryDao.AccessType.EDIT)) {
              hasAccess = true;
            }
            if (permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.UNSHARE.name())
                || permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.DELETE.name())) {
              accessObject.put(Field.SELF_UNSHARE_ACCESS.getName(), true);
            }
          }
          break;
        }
      }
      if (!accessObject.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
        // to reuse the shared Items in update/delete
        if (Utils.isStringNotNullOrEmpty(permission)
            && (permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.UPDATE.name())
                || permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.DELETE.name()))) {
          accessObject.put("allSharedItems", allSharedItems);
        }

        if (!hasAccess && Utils.isStringNotNullOrEmpty(permission)) {
          accessObject
              .put(Field.ERROR_ID.getName(), errorId)
              .put(Field.ERROR.getName(), Utils.getLocalizedString(locale, errorId));
        }
      }
    }

    if (!hasAccess
        && !accessObject.containsKey(Field.ERROR.getName())
        && !accessObject.containsKey(Field.SELF_UNSHARE_ACCESS.getName())) {
      accessObject
          .put(Field.ERROR_ID.getName(), errorId)
          .put(Field.ERROR.getName(), Utils.getLocalizedString(locale, errorId));
    }
    if (hasAccess) {
      accessObject.remove(Field.ERROR_ID.getName());
      accessObject.remove(Field.ERROR.getName());
    }

    accessObject.put(Field.HAS_ACCESS.getName(), hasAccess);
    return accessObject;
  }

  /**
   * Check whether user has access for the ownerType
   *
   * @param userId         the userId
   * @param ownerId        the ownerId
   * @param ownerType      the ownerType
   * @param isEditRequired user required editing access for the permission or not
   * @param locale         the local
   * @param errorId        id of the corresponding error string
   * @param permission     the type of permission for actions like UPDATE, DELETE, etc.
   * @return the JsonObject containing the access data
   */
  private static JsonObject checkAccessForOwnerType(
      String userId,
      String ownerId,
      String ownerType,
      boolean isEditRequired,
      String locale,
      String errorId,
      String permission) {
    JsonObject accessObject = new JsonObject();
    boolean hasAccess = false;
    if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.USER.name())) {
      if (ownerId.equals(userId)) {
        hasAccess = true;
      }
    } else if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.ORG.name())) {
      Item user = Users.getUserById(userId);
      if (user != null
          && user.hasAttribute(Field.ORGANIZATION_ID.getName())
          && user.getString(Field.ORGANIZATION_ID.getName()).equals(ownerId)) {
        if (!isEditRequired) {
          hasAccess = true;
        } else {
          if (user.hasAttribute(Field.IS_ORG_ADMIN.getName())
              && user.getBoolean(Field.IS_ORG_ADMIN.getName())) {
            hasAccess = true;
          } else if (Utils.isStringNotNullOrEmpty(permission)
              && (permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.DELETE.name()))) {
            accessObject.put("selfUnSharePublicAndOrgAccess", true);
          } else {
            accessObject
                .put(Field.ERROR_ID.getName(), errorId == null ? "BL23" : errorId)
                .put(
                    Field.ERROR.getName(),
                    Utils.getLocalizedString(locale, errorId == null ? "BL23" : errorId));
          }
        }
      }
    } else if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.PUBLIC.name())) {
      if (!isEditRequired) {
        hasAccess = true;
      } else {
        if (Utils.isStringNotNullOrEmpty(permission)
            && (permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.SHARE.name())
                || permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.UNSHARE.name()))) {
          accessObject
              .put(Field.ERROR_ID.getName(), "BL21")
              .put(Field.ERROR.getName(), Utils.getLocalizedString(locale, "BL21"));
        } else if (Utils.isStringNotNullOrEmpty(permission)
            && (permission.equalsIgnoreCase(BlockLibraryDao.PermissionType.DELETE.name()))) {
          accessObject.put("selfUnSharePublicAndOrgAccess", true);
        } else {
          Item user = Users.getUserById(userId);
          if (user != null
              && user.hasAttribute(Field.ROLES.getName())
              && user.getList(Field.ROLES.getName()).contains("1")) {
            hasAccess = true;
          } else {
            accessObject
                .put(Field.ERROR_ID.getName(), errorId == null ? "BL24" : errorId)
                .put(
                    Field.ERROR.getName(),
                    Utils.getLocalizedString(locale, errorId == null ? "BL24" : errorId));
          }
        }
      }
    }
    accessObject.put(Field.HAS_ACCESS.getName(), hasAccess);
    return accessObject;
  }

  /**
   * Get error string id based on permission request
   *
   * @param permission     the type of permission for actions like UPDATE, DELETE, etc.
   * @param isBlockLibrary the current item is a library or not.
   * @return id of the corresponding error string
   */
  private static String getErrorIdFromPermission(String permission, boolean isBlockLibrary) {
    if (permission != null) {
      BlockLibraryDao.PermissionType type = BlockLibraryDao.PermissionType.valueOf(permission);
      switch (type) {
        case SHARE:
          return isBlockLibrary ? "BL12" : "BL17";
        case UNSHARE:
          return isBlockLibrary ? "BL13" : "BL18";
        case UPDATE:
          return isBlockLibrary ? "BL14" : "BL19";
        case DELETE:
          return isBlockLibrary ? "BL15" : "BL20";
        case UPLOAD:
          return "BL16";
      }
    }
    return isBlockLibrary ? "BL11" : "BL10";
  }

  /**
   * Get shared items for block or library from DB or hasAccess object
   *
   * @param hasAccess the object containing access data.
   * @param libraryId the libraryId.
   * @param blockId   the blockId.
   * @return list of shared items
   */
  private List<Item> getSharedItems(JsonObject hasAccess, String libraryId, String blockId) {
    List<Item> allSharedItems;
    if (hasAccess.containsKey("allSharedItems")) {
      allSharedItems = hasAccess.getJsonArray("allSharedItems").stream()
          .map(obj -> Item.fromJSON((String) obj))
          .collect(Collectors.toList());
    } else {
      Iterator<Item> sharedIterator = BlockLibraryDao.getAllSharedItems(libraryId, blockId);
      allSharedItems = TypedIteratorUtils.toTypedList(sharedIterator);
    }
    return allSharedItems;
  }

  /**
   * Check if the owner info provided in the request is valid or not
   *
   * @param ownerId   the ownerId
   * @param ownerType the ownerType.
   * @return the boolean
   */
  private boolean isOwnerValid(String ownerId, String ownerType) {
    if (BlockLibraryDao.BlockLibraryOwnerType.getValues().contains(ownerType.toUpperCase())) {
      if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.USER.name())) {
        Item user = Users.getUserById(ownerId);
        return user != null;
      } else if (ownerType.equalsIgnoreCase(BlockLibraryDao.BlockLibraryOwnerType.ORG.name())) {
        Item org = Users.getOrganizationById(ownerId);
        return org != null || ownerId.equalsIgnoreCase(AuthManager.NON_EXISTENT_ORG);
      } else {
        return true;
      }
    }
    return false;
  }

  /**
   * Asynchronously add shares of the library to all the blocks inside
   *
   * @param blocks    list of the blocks to be shared
   * @param libraryId the libraryId.
   */
  private void addLibrarySharesToBlocks(
      Message<JsonObject> message, List<String> blocks, String libraryId) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "addLibrarySharesToBlocks");
    new ExecutorServiceAsyncRunner(executorService, operationGroup, subsegment, message)
        .withName("addLibrarySharesToBlocks")
        .run((Segment blockingSegment) -> {
          Iterator<Item> sharedItems = BlockLibraryDao.getAllSharedItems(libraryId, null);
          if (!sharedItems.hasNext()) {
            return;
          }
          List<Item> sharedItemList = Lists.newArrayList(sharedItems);
          JsonArray emails = new JsonArray(sharedItemList.stream()
              .map(share -> {
                Item user = Users.getUserById(BlockLibraryDao.getIdFromPk(share.getString(pk)));
                if (user == null) {
                  return null;
                }
                return new JsonObject()
                    .put(Field.ID.getName(), user.getString(Field.USER_EMAIL.getName()))
                    .put(Field.MODE.getName(), share.getString(Field.MODE.getName()));
              })
              .collect(Collectors.toList()));
          blocks.forEach(blockId -> {
            message
                .body()
                .put(Field.BLOCK_ID.getName(), blockId)
                .put(Field.EMAILS.getName(), emails);
            doShareBlock(message, false);
          });
        });
    XRayManager.endSegment(subsegment);
  }

  /**
   * Asynchronously remove shares of the library from all the blocks inside
   *
   * @param blocks    list of the blocks to be shared
   * @param libraryId the libraryId.
   */
  private void removeLibrarySharesFromBlocks(
      Message<JsonObject> message, List<String> blocks, String libraryId) {
    Entity subsegment =
        XRayManager.createSubSegment(operationGroup, "removeLibrarySharesFromBlocks");
    new ExecutorServiceAsyncRunner(executorService, operationGroup, subsegment, message)
        .withName("removeLibrarySharesFromBlocks")
        .run((Segment blockingSegment) -> {
          Iterator<Item> sharedItems = BlockLibraryDao.getAllSharedItems(libraryId, null);
          if (!sharedItems.hasNext()) {
            return;
          }
          List<Item> sharedItemList = Lists.newArrayList(sharedItems);
          JsonArray emails = new JsonArray(sharedItemList.stream()
              .map(share -> {
                Item user = Users.getUserById(BlockLibraryDao.getIdFromPk(share.getString(pk)));
                if (user == null) {
                  return null;
                }
                return user.getString(Field.USER_EMAIL.getName());
              })
              .collect(Collectors.toList()));
          blocks.forEach(blockId -> {
            message
                .body()
                .put(Field.BLOCK_ID.getName(), blockId)
                .put(Field.EMAILS.getName(), emails);
            doUnShareBlock(message, false);
          });
        });
    XRayManager.endSegment(subsegment);
  }
}
