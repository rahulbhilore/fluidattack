package com.graebert.storage.integration;

import com.amazonaws.SdkClientException;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.api.client.util.Lists;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PermissionsDao;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionModifier;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.FolderMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.MultipleUploads;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.ids.IdName;
import com.graebert.storage.storage.ids.KudoFileId;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.storage.link.repository.VersionLinkRepository;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.UploadHelper;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

public class SimpleFluorine extends BaseStorage implements Storage {
  public static final String address = "samples";
  public static final int PAGE_SIZE = 100;
  public static final int MIN_PAGE_SIZE = 20;
  private static final OperationGroup operationGroup = OperationGroup.SAMPLES;
  private static final Logger log = LogManager.getRootLogger();
  private static final StorageType storageType = StorageType.SAMPLES;
  private static final String skeletonPrefix = "SKELETON INTERNAL ";
  // this is just a default value. Will be fetched from s3 lifecycle config.
  private static final String SAMPLE_VERSION = "SAMPLE";

  private static final String DWG_SAMPLE_FILES = "DWG sample files";
  private static final String SHARED_FILES = "Shared files";
  private static final String pageToken_SharedPrefix = "S_D:";
  private static final String pageToken_RootPrefix = "R_T:";

  private static String bucket = emptyString;
  private static S3Regional s3Regional = null;
  // private static S3Regional thumbnailsS3 = null;
  private static int ttl = 30;
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };

  public SimpleFluorine() {}

  public static Set<String> getAllObjectCollaborators(Item item) {
    Set<String> collaborators = SimpleStorage.getEditors(item) != null
        ? new HashSet<>(SimpleStorage.getEditors(item))
        : new HashSet<>();
    collaborators.addAll(
        SimpleStorage.getViewers(item) != null
            ? new HashSet<>(SimpleStorage.getViewers(item))
            : new HashSet<>());
    return collaborators;
  }

  private static boolean checkIfParentCollaboratorsHasAccess(Item item, String folderId) {
    Item folder = SimpleStorage.getItemEvenIfErased(SimpleStorage.makeFolderId(folderId, null));
    if (Objects.nonNull(folder)) {
      Set<String> folderCollaborators = getAllObjectCollaborators(folder);
      folderCollaborators.add(SimpleStorage.getOwner(folder));
      return folderCollaborators.contains(SimpleStorage.getOwner(item));
    }
    return false;
  }

  private static boolean isClonedFromViewOnlyFolder(String currentParentId, String userId) {
    Item parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(currentParentId, userId));
    if (Objects.nonNull(parent)) {
      Set<String> viewers = SimpleStorage.getViewers(parent);
      // create the cloned fine in the root if the current parent is view-only
      return Objects.nonNull(viewers) && viewers.contains(userId);
    }
    return false;
  }

  @Override
  public void start() throws Exception {
    super.start();
    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> message) -> doGetFolderContent(message, false));
    // execute(event, "doGetFolderContent", new Class[]{boolean.class}, new Object[]{false})
    eb.consumer(address + ".createFolder", this::doCreateFolder);
    eb.consumer(address + ".moveFolder", this::doMoveFolder);
    eb.consumer(address + ".renameFolder", this::doRenameFolder);
    eb.consumer(address + ".deleteFolder", this::doDeleteFolder);
    eb.consumer(address + ".clone", this::doClone);
    eb.consumer(address + ".createShortcut", this::doCreateShortcut);
    eb.consumer(address + ".getFile", this::doGetFile);
    eb.consumer(address + ".uploadFile", this::doUploadFile);
    eb.consumer(address + ".uploadVersion", this::doUploadVersion);
    eb.consumer(address + ".moveFile", this::doMoveFile);
    eb.consumer(address + ".renameFile", this::doRenameFile);
    eb.consumer(address + ".deleteFile", this::doDeleteFile);
    eb.consumer(address + ".getVersions", this::doGetVersions);
    eb.consumer(address + ".getLatestVersionId", this::doGetLatestVersionId);
    eb.consumer(address + ".getVersion", this::doGetFile);
    eb.consumer(address + ".getFileByToken", this::doGetFileByToken);
    eb.consumer(address + ".promoteVersion", this::doPromoteVersion);
    eb.consumer(address + ".deleteVersion", this::doDeleteVersion);
    eb.consumer(
        address + ".getTrash", (Message<JsonObject> message) -> doGetFolderContent(message, true));
    eb.consumer(address + ".share", (Message<JsonObject> event) -> doShare(event));
    eb.consumer(address + ".deShare", this::doDeShare);
    eb.consumer(address + ".restore", this::doRestore);
    eb.consumer(address + ".getInfo", this::doGetInfo);
    eb.consumer(address + ".getThumbnail", this::doGetThumbnail);
    eb.consumer(address + ".doGetInfoByToken", this::doGetInfoByToken);
    eb.consumer(
        address + ".getFolderPath", (Message<JsonObject> message) -> this.doGetFolderPath(message));
    eb.consumer(address + ".eraseAll", this::doEraseAll);
    eb.consumer(address + ".eraseFile", (Message<JsonObject> message) -> this.doEraseFile(message));
    eb.consumer(
        address + ".eraseFolder", (Message<JsonObject> message) -> this.doEraseFolder(message));
    eb.consumer(address + ".eraseUser", this::doEraseUser);
    eb.consumer(address + ".connect", (Message<JsonObject> message) -> connect(message, true));
    eb.consumer(address + ".createSharedLink", this::doCreateSharedLink);
    eb.consumer(address + ".deleteSharedLink", this::doDeleteSharedLink);
    eb.consumer(address + ".requestFolderZip", this::doRequestFolderZip);
    eb.consumer(address + ".requestMultipleObjectsZip", this::doRequestMultipleObjectsZip);
    eb.consumer(address + ".globalSearch", this::doGlobalSearch);
    eb.consumer(address + ".findXRef", this::doFindXRef);
    eb.consumer(address + ".checkPath", this::doCheckPath);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    eb.consumer(address + ".changeOwner", this::doChangeOwner);
    eb.consumer(address + ".createSkeleton", this::doCreateSkeleton);
    eb.consumer(address + ".updateSkeleton", this::doUpdateSkeleton);
    eb.consumer(address + ".transferFiles", this::doTransferFiles);
    eb.consumer(address + ".sendSampleUsageWSNotification", this::doSendSampleUsageWSNotification);
    eb.consumer(address + ".requestMultipleUpload", this::doRequestMultipleUpload);
    eb.consumer(address + ".createPresignedUrl", this::doCreatePresignedUrl);

    eb.consumer(address + ".validateFiles", this::doValidateFiles);

    bucket = config
        .getProperties()
        .getSimpleStorageAsJson()
        .getJsonObject("default")
        .getString("bucket");
    String region = config
        .getProperties()
        .getSimpleStorageAsJson()
        .getJsonObject("default")
        .getString(Field.REGION.getName());
    s3Regional = new S3Regional(config, bucket, region);
    ttl = s3Regional.getVersionExpiration(bucket);

    // String defaultRegion = config.getProperties().getS3Region();
    // String defaultBucket = config.getProperties().getS3Bucket();
    // thumbnailsS3 = new S3Regional(config, defaultBucket, defaultRegion);

    executor = vertx.createSharedWorkerExecutor(
        "vert.x-new-internal-blocking-fluorine", 100, 180, TimeUnit.SECONDS);
  }

  private <T> S3Regional getS3Regional(T object, String userId) {
    if (object == null) {
      return Users.getS3Regional(new Item(), userId, s3Regional, config, bucket);
    } else if (object instanceof String) {
      return Users.getS3Regional((String) object, userId, s3Regional, config, bucket);
    } else if (object instanceof Item) {
      return Users.getS3Regional((Item) object, userId, s3Regional, config, bucket);
    } else {
      return s3Regional;
    }
  }

  @Nullable private String createFileWithParameters(
      final Entity segment,
      final Message<JsonObject> message,
      final S3Regional userS3,
      String targetFolderId,
      final String userId,
      final String filename,
      final String s3Link,
      final long fileLength) {
    if (fileLength == 0) {
      log.info(skeletonPrefix + "File content length equals 0");
      return null;
    }
    final String targetFileId = UUID.randomUUID().toString();
    try {
      SimpleStorage.addFile(
          targetFileId,
          SimpleStorage.makeUserId(userId),
          filename,
          SimpleStorage.makeFolderId(targetFolderId, userId),
          SAMPLE_VERSION,
          fileLength,
          s3Link,
          null,
          null,
          userS3.getRegion(),
          false);
      // sendSampleUsageWsNotification(segment, message, userId, true);
    } catch (Exception ex) {
      log.error(skeletonPrefix + "Failed to save file's info into DB", ex);
      return null;
    }
    log.info(skeletonPrefix + "Created copy with id: " + targetFileId);
    return targetFileId;
  }

  private void doCreateSkeleton(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String targetFolderId = ROOT_FOLDER_ID;
    final String userId = message.body().getString(Field.USER_ID.getName());
    final JsonObject samplesConfig = config.getProperties().getSamplesFilesAsJson();
    final S3Regional userS3 = getS3Regional(null, userId);
    if (samplesConfig.isEmpty()) {
      log.warn(skeletonPrefix + "Error skeleton update: config is empty");
      sendError(segment, message, "Error skeleton update: config is empty", HttpStatus.BAD_REQUEST);
      return;
    }
    final JsonObject appConfig =
        samplesConfig.getJsonObject(config.getProperties().getSmtpProName());
    if (appConfig == null || appConfig.isEmpty()) {
      log.warn(skeletonPrefix + "Error skeleton update: Couldn't find config for app");
      sendError(
          segment,
          message,
          "Error skeleton update: Couldn't find config for app",
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      String targetFolderName = appConfig.getString("targetFolderName", DWG_SAMPLE_FILES);

      // creating "DWG Sample Files" folder inside "My Files"
      targetFolderId = createSkeletonFolders(targetFolderName, userId);
    } catch (Exception e) {
      log.error(skeletonPrefix + "Error skeleton creation:", e);
      sendError(
          segment,
          message,
          "Error creating folder for sample files: " + e.getMessage(),
          HttpStatus.BAD_REQUEST,
          e);
    }
    if (!Utils.isStringNotNullOrEmpty(targetFolderId)) {
      log.warn(skeletonPrefix + "Error skeleton creation: Target folderId cannot be null");
      sendError(segment, message, "Target folder id cannot be null", HttpStatus.BAD_REQUEST);
      return;
    }
    log.info(skeletonPrefix + "Going to create skeleton in folder " + targetFolderId + " userId: "
        + userId);
    try {
      JsonArray s3prefixes = appConfig.getJsonArray("s3prefixes");
      if (s3prefixes == null || s3prefixes.isEmpty()) {
        log.warn(skeletonPrefix + "Error skeleton creation: No s3 prefixes in config");
        sendError(
            segment,
            message,
            "Error skeleton creation: No s3 prefixes in config",
            HttpStatus.BAD_REQUEST);
        return;
      }

      JsonArray files = new JsonArray();
      for (Object prefix : s3prefixes) {
        final String objectPath = (String) prefix;
        log.info(skeletonPrefix + "Looking for sample file by path: " + objectPath);
        if (Utils.isStringNotNullOrEmpty(objectPath)) {
          ObjectMetadata metaData = userS3.getObjectMetadata(bucket, objectPath);
          log.info(skeletonPrefix + "Found sample file: " + objectPath + " (" + (metaData != null)
              + ") ");
          if (metaData != null) {
            String fileName = Utils.getFileNameFromPath(objectPath);
            long fileLength = metaData.getContentLength();
            final String newFileId = createFileWithParameters(
                segment, message, userS3, targetFolderId, userId, fileName, objectPath, fileLength);
            files.add(newFileId);
          }
        }
      }
      log.info(skeletonPrefix + "List of created files: " + files.encodePrettily());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      SimpleStorage.getAccountId(userId),
                      targetFolderId))
              .put(Field.FILES.getName(), files),
          mills);
      recordExecutionTime("createSkeleton", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      log.error(e);
      System.out.println(e.getMessage());
      sendError(
          segment,
          message,
          "Error cloning sample files: " + e.getMessage(),
          HttpStatus.BAD_REQUEST,
          e);
    }
  }

  private void doUpdateSkeleton(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String targetFolderId = ROOT_FOLDER_ID;
    final String userId = message.body().getString(Field.USER_ID.getName());
    final JsonObject samplesConfig = config.getProperties().getSamplesFilesAsJson();
    final S3Regional userS3 = getS3Regional(null, userId);
    if (samplesConfig.isEmpty()) {
      log.warn(skeletonPrefix + "Error skeleton update: config is empty");
      sendError(segment, message, "Error skeleton update: config is empty", HttpStatus.BAD_REQUEST);
      return;
    }
    final JsonObject appConfig =
        samplesConfig.getJsonObject(config.getProperties().getSmtpProName());
    if (appConfig == null || appConfig.isEmpty()) {
      log.warn(skeletonPrefix + "Error skeleton update: Couldn't find config for app");
      sendError(
          segment,
          message,
          "Error skeleton update: Couldn't find config for app",
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      String targetFolderName = appConfig.getString("targetFolderName", DWG_SAMPLE_FILES);
      // creating "DWG Sample Files" folder inside "My Files"
      targetFolderId = updateSkeletonFolders(targetFolderName, userId);
    } catch (Exception e) {
      log.error(skeletonPrefix + "Error skeleton update:", e);
      sendError(
          segment,
          message,
          "Error finding folder for sample files: " + e.getMessage(),
          HttpStatus.BAD_REQUEST,
          e);
    }
    if (!Utils.isStringNotNullOrEmpty(targetFolderId)) {
      log.warn(skeletonPrefix + "Error skeleton update: Target folderId cannot be null");
      sendError(segment, message, "Target folder id cannot be null", HttpStatus.BAD_REQUEST);
      return;
    }
    log.info(skeletonPrefix + "Going to update skeleton in folder " + targetFolderId + " userId: "
        + userId);
    try {
      JsonArray s3prefixes = appConfig.getJsonArray("s3prefixes");

      if (s3prefixes == null || s3prefixes.isEmpty()) {
        log.warn(skeletonPrefix + "Error skeleton creation: No s3 prefixes in config");
        sendError(
            segment,
            message,
            "Error skeleton creation: No s3 prefixes in config",
            HttpStatus.BAD_REQUEST);
        return;
      }

      // get current list and see if anything is different
      // won't work if pagination is involved
      ItemCollection<QueryOutcome> itemCollection = SimpleStorage.getFolderItems(
          SimpleStorage.makeFolderId(targetFolderId, userId),
          SimpleStorage.makeUserId(userId),
          null);
      List<String> currentNames = new ArrayList<>();
      for (Item item : itemCollection) {
        if (SimpleStorage.isFile(item)) {
          currentNames.add(SimpleStorage.getName(item));
        }
      }
      JsonArray files = new JsonArray();
      for (Object prefix : s3prefixes) {
        final String objectPath = (String) prefix;
        log.info(skeletonPrefix + "Looking for sample file by path: " + objectPath);
        if (Utils.isStringNotNullOrEmpty(objectPath)) {
          ObjectMetadata metaData = userS3.getObjectMetadata(bucket, objectPath);
          log.info(skeletonPrefix + "Found sample file: " + objectPath + " (" + (metaData != null)
              + ") ");
          if (metaData != null) {
            String fileName = Utils.getFileNameFromPath(objectPath);
            if (!currentNames.contains(fileName)) {
              long fileLength = metaData.getContentLength();
              final String newFileId = createFileWithParameters(
                  segment,
                  message,
                  userS3,
                  targetFolderId,
                  userId,
                  fileName,
                  objectPath,
                  fileLength);
              files.add(newFileId);
            } else {
              System.out.println("No update required");
            }
          }
        }
      }
      log.info(skeletonPrefix + "List of created files: " + files.encodePrettily());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      SimpleStorage.getAccountId(userId),
                      targetFolderId))
              .put(Field.FILES.getName(), files),
          mills);
      recordExecutionTime("updateSkeleton", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      log.error(e);
      sendError(
          segment,
          message,
          "Error cloning sample files: " + e.getMessage(),
          HttpStatus.BAD_REQUEST,
          e);
    }
  }

  private String createSkeletonFolders(String folderName, String userId) {
    return SimpleStorage.addFolder(
        SimpleStorage.makeUserId(userId),
        folderName,
        SimpleStorage.makeFolderId(BaseStorage.ROOT, userId));
  }

  private String updateSkeletonFolders(String folderName, String userId) {
    boolean found = false;
    String folderId = emptyString;
    ItemCollection<QueryOutcome> itemCollection = SimpleStorage.getFolderItems(
        SimpleStorage.makeFolderId(BaseStorage.ROOT, userId),
        SimpleStorage.makeUserId(userId),
        null);
    for (Item item : itemCollection) {
      // if it's the folder with the same name - let's assume it's what we need
      if (SimpleStorage.isFolder(item) && SimpleStorage.getName(item).equals(folderName)) {
        folderId = SimpleStorage.getItemId(item);
        found = true;
      }
    }
    if (!found) {
      folderId = createSkeletonFolders(folderName, userId);
    }
    return folderId;
  }

  private void doTransferFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    final String userId = message.body().getString(Field.USER_ID.getName());
    final String s3Region = message.body().getString(Field.S3_REGION.getName());

    S3Regional newS3 = getS3Regional(null, userId);

    Iterator<Item> files =
        SimpleStorage.getAllFilesOwnedByUser(SimpleStorage.makeUserId(userId)).iterator();
    files.forEachRemaining(file -> {
      S3Regional oldS3 = getS3Regional(file, userId);

      try {
        String version = newS3.putToBucket(
            newS3.getBucketName(),
            SimpleStorage.getPath(file),
            oldS3.getFromBucket(oldS3.getBucketName(), SimpleStorage.getPath(file)));
        oldS3.deleteFromBucket(oldS3.getBucketName(), SimpleStorage.getPath(file));

        SimpleStorage.updateRegion(file, s3Region, version);
      } catch (Exception e) {
        log.error("Could not find file \"" + SimpleStorage.getItemId(file) + "\" for user \""
            + userId + "\" in \"" + s3Region + "\" region");
      }
    });

    XRayManager.endSegment(segment);
  }

  private void doChangeOwner(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {

      JsonObject json = message.body();
      boolean isFolder = json.getBoolean(Field.IS_FOLDER.getName());
      String userId = json.getString(Field.USER_ID.getName());
      String id = json.getString(Field.ID.getName());
      String newOwner = json.getString("newOwner");

      if (Users.getUserById(newOwner) == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserDoesNotExist"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      Item object = SimpleStorage.getItem(
          isFolder ? SimpleStorage.makeFolderId(id, userId) : SimpleStorage.makeFileId(id));
      if (object == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                id),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(object)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                id),
            HttpStatus.BAD_REQUEST);
        return;
      }

      // check if user is owner
      if (!SimpleStorage.checkOwner(object, SimpleStorage.makeUserId(userId))) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(
                    message,
                    isFolder
                        ? "UserHasNoAccessWithTypeForFolderWithId"
                        : "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canChangeOwner.label,
                id),
            HttpStatus.FORBIDDEN);
        return;
      }

      if (SimpleStorage.isFile(object)
          && !SimpleStorage.checkQuota(
              SimpleStorage.makeUserId(newOwner),
              SimpleStorage.getFileSize(object),
              config.getProperties().getDefaultUserOptionsAsJson())) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CollaboratorDoesntHaveEnoughSpace"),
            HttpStatus.FORBIDDEN,
            KudoErrorCodes.NO_SPACE_LEFT.getCode());
        return;
      }

      SimpleStorage.updateOwner(object, SimpleStorage.makeUserId(newOwner), true);
      sendSampleUsageWsNotification(
          segment,
          message,
          SimpleStorage.stripPrefix(object.getString(Field.OWNER.getName())),
          true);
      sendSampleUsageWsNotification(segment, message, newOwner, true);

      if (isFolder) {
        SimpleStorage.updateOwnerForFolders(id, userId, SimpleStorage.makeUserId(newOwner));
        List<String> newEditor = new ArrayList<>();
        newEditor.add(newOwner);
        shareContent(id, userId, newEditor, null, false);
        shareFolderItems(id, newOwner, userId, false);
      }

      eb_send(
          segment,
          MailUtil.address + ".notifyOwner",
          new JsonObject()
              .put(Field.OWNER_ID.getName(), userId)
              .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
              .put(Field.NAME.getName(), SimpleStorage.getName(object))
              .put(Field.STORAGE_TYPE.getName(), storageType.toString())
              .put("newOwner", newOwner)
              .put(Field.TYPE.getName(), isFolder ? 0 : 1)
              .put(Field.ID.getName(), id));
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doAddAuthCode(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      if (trash) {
        getTrashFolderContent(segment, message);
      } else {
        getFolderContent(segment, message);
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private void getFolderContent(Entity segment, Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String pageToken = message.body().getString(Field.PAGE_TOKEN.getName());
    Boolean full = jsonObject.getBoolean(Field.FULL.getName());
    if (full == null) {
      full = true;
    }
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    @NonNls String fileFilter = message.body().getString(Field.FILE_FILTER.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    List<JsonObject> filesJson = new ArrayList<>();
    List<JsonObject> foldersJson = new ArrayList<>();
    String device = message.body().getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    boolean canCreateThumbnails = canCreateThumbnailsForKudoDrive(jsonObject) && isBrowser;
    JsonArray thumbnail = new JsonArray();
    Item folder = null;
    boolean isSharedFolder = false;
    final S3Regional userS3;
    if (!folderId.equals(ROOT_FOLDER_ID)
        && !folderId.equals(ROOT)
        && !folderId.equals(SHARED_FOLDER)) {
      folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
      if (folder == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(folder)) {
        // make it's HttpStatus.NOT_FOUND to make sure we handle it properly on UI
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(folder, SimpleStorage.makeUserId(userId), false, null)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessToFolderWithId"), folderId),
            HttpStatus.FORBIDDEN);
        return;
      }
      isSharedFolder = SimpleStorage.isSharedWithUser(folder, userId);
      userS3 = getS3Regional(folder, userId);
    } else {
      userS3 = getS3Regional(null, userId);
    }
    boolean isTouch = AuthManager.ClientType.TOUCH.name().equals(device);
    boolean useNewStructure = jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
    if (!folderId.equals(ROOT_FOLDER_ID) || (isTouch && !useNewStructure)) {
      Iterator<Item> items;
      List<Item> queryResultItems = new ArrayList<>();
      if (isTouch && folderId.equals(ROOT_FOLDER_ID)) {
        boolean isSharedToken = false;
        if (Objects.nonNull(pageToken)) {
          if (pageToken.startsWith(pageToken_RootPrefix)) {
            pageToken = pageToken.substring(pageToken_RootPrefix.length());
          } else if (pageToken.startsWith(pageToken_SharedPrefix)) {
            pageToken = pageToken.substring(pageToken_SharedPrefix.length());
            isSharedToken = true;
          }
        }
        QueryResult queryResult;
        if (!isSharedToken) {
          queryResult = SimpleStorage.getFolderItemsWithQueryResult(
              SimpleStorage.makeFolderId(folderId, userId),
              SimpleStorage.makeUserId(userId),
              pageToken);
          if (Objects.nonNull(queryResult)) {
            queryResultItems.addAll(SimpleStorage.getItems(queryResult));
            pageToken = SimpleStorage.getContinuationToken(queryResult);
            while (Objects.nonNull(pageToken) && queryResultItems.size() < MIN_PAGE_SIZE) {
              queryResult = SimpleStorage.getFolderItemsWithQueryResult(
                  SimpleStorage.makeFolderId(folderId, userId),
                  SimpleStorage.makeUserId(userId),
                  pageToken);
              if (Objects.isNull(queryResult)) {
                break;
              }
              pageToken = SimpleStorage.getContinuationToken(queryResult);
              queryResultItems.addAll(SimpleStorage.getItems(queryResult));
            }
            if (Objects.nonNull(pageToken)) {
              pageToken = pageToken_RootPrefix + pageToken;
            }
          }
        }
        if (Objects.isNull(pageToken) || isSharedToken) {
          queryResult = SimpleStorage.getSharedFolderItemsWithQueryResult(userId, pageToken, null);
          if (Objects.nonNull(queryResult)) {
            queryResultItems.addAll(SimpleStorage.getItems(queryResult));
            pageToken = SimpleStorage.getContinuationToken(queryResult);
            while (Objects.nonNull(pageToken) && queryResultItems.size() < MIN_PAGE_SIZE) {
              queryResult =
                  SimpleStorage.getSharedFolderItemsWithQueryResult(userId, pageToken, null);
              if (Objects.isNull(queryResult)) {
                break;
              }
              pageToken = SimpleStorage.getContinuationToken(queryResult);
              queryResultItems.addAll(SimpleStorage.getItems(queryResult));
            }
            if (Objects.nonNull(pageToken)) {
              pageToken = pageToken_SharedPrefix + pageToken;
            }
          }
        }
      } else {
        QueryResult queryResult = getFolderQueryResult(
            folderId, userId, pageToken, isSharedFolder, folder, queryResultItems);
        pageToken = SimpleStorage.getContinuationToken(queryResult);
        while (Objects.nonNull(pageToken) && queryResultItems.size() < MIN_PAGE_SIZE) {
          queryResult = getFolderQueryResult(
              folderId, userId, pageToken, isSharedFolder, folder, queryResultItems);
          pageToken = SimpleStorage.getContinuationToken(queryResult);
        }
      }
      items = queryResultItems.iterator();
      JsonArray samplesThumbnails = new JsonArray();
      List<Item> thumbnailFiles = new ArrayList<>();
      final JsonObject samplesConfig = config.getProperties().getSamplesFilesAsJson();
      final JsonObject appConfig =
          samplesConfig.getJsonObject(config.getProperties().getSmtpProName());
      JsonArray disabledSampleFiles = appConfig.getJsonArray("disabledFiles");
      while (items.hasNext()) {
        Item item = items.next();
        if (SimpleStorage.isFile(item)) {
          String filename = SimpleStorage.getName(item);
          if (SimpleStorage.isSampleFile(item) && disabledSampleFiles.contains(filename)) {
            continue;
          }
          if (Extensions.isValidExtension(extensions, filename)) {
            boolean createThumbnail = canCreateThumbnails;
            if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
              thumbnailFiles.add(item);
            } else {
              createThumbnail = false;
            }
            filesJson.add(getFileJson(item, userId, isAdmin, false, force, createThumbnail));
          }
        } else {
          foldersJson.add(getFolderJson(segment, message, item, userId, false));
        }
      }
      if (canCreateThumbnails && !thumbnailFiles.isEmpty()) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              String s3Bucket = userS3.getBucketName();
              String s3Region = userS3.getRegion();
              thumbnailFiles.forEach(item -> {
                if (!SimpleStorage.isSampleFile(item)
                    || !Utils.isStringNotNullOrEmpty(SimpleStorage.getPath(item))) {
                  thumbnail.add(new JsonObject()
                      .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                      .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
                      .put(
                          Field.EXT.getName(),
                          Extensions.getExtension(SimpleStorage.getName(item))));
                } else {
                  samplesThumbnails.add(new JsonObject()
                      .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                      .put("s3bucket", s3Bucket)
                      .put("s3region", s3Region)
                      .put("s3path", SimpleStorage.getPath(item))
                      .put(
                          "thumbnailPath",
                          ThumbnailsManager.getPublicFileThumbnailS3Path(
                              SimpleStorage.getPath(item))));
                }
              });

              if (!thumbnail.isEmpty()) {
                log.info("[SF_THUMBNAILS] getFolderContent: " + thumbnail.encodePrettily());
                eb_send(
                    blockingSegment,
                    ThumbnailsManager.address + ".create",
                    message
                        .body()
                        .put(Field.STORAGE_TYPE.getName(), storageType.name())
                        .put(Field.IDS.getName(), thumbnail));
              }

              if (!samplesThumbnails.isEmpty()) {
                log.info("[SF_THUMBNAILS] getFolderContent -> public: "
                    + samplesThumbnails.encodePrettily());
                eb_send(
                    blockingSegment,
                    ThumbnailsManager.address + ".createForPublic",
                    message
                        .body()
                        .put(Field.STORAGE_TYPE.getName(), storageType.name())
                        .put("items", samplesThumbnails));
              }
            });
      }
    } else {
      addRootFoldersJson(foldersJson, userId);
    }
    foldersJson.sort(Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
    filesJson.sort(
        Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
    JsonObject response = new JsonObject()
        .put(
            Field.RESULTS.getName(),
            new JsonObject()
                .put(Field.FILES.getName(), new JsonArray(filesJson))
                .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
        .put("number", filesJson.size() + foldersJson.size())
        .put(Field.FULL.getName(), full)
        .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      response.put(Field.PAGE_TOKEN.getName(), pageToken);
    }
    sendOK(segment, message, response, mills);
    recordExecutionTime("getFolderContent", System.currentTimeMillis() - mills);
  }

  private QueryResult getFolderQueryResult(
      String folderId,
      String userId,
      String pageToken,
      boolean isSharedFolder,
      Item folder,
      List<Item> queryResultItems) {
    QueryResult queryResult;
    if (folderId.equals(SHARED_FOLDER)) {
      queryResult = SimpleStorage.getSharedFolderItemsWithQueryResult(userId, pageToken, null);
    } else {
      queryResult = SimpleStorage.getFolderItemsWithQueryResult(
          SimpleStorage.makeFolderId(folderId, userId),
          SimpleStorage.makeUserId(userId),
          pageToken);
    }
    if (Objects.nonNull(queryResult)) {
      queryResultItems.addAll(SimpleStorage.getItems(queryResult));
    }
    if (isSharedFolder
        || (Objects.nonNull(folder)
            && (SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(userId))
                || SimpleStorage.isShared(folder)))) {
      queryResult = SimpleStorage.getSharedFolderItemsWithQueryResult(userId, pageToken, folderId);
      if (Objects.nonNull(queryResult)) {
        queryResultItems.addAll(SimpleStorage.getItems(queryResult));
      }
    }
    return queryResult;
  }

  private void getTrashFolderContent(Entity segment, Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String ownerId = jsonObject.getString(Field.USER_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String pageToken = message.body().getString(Field.PAGE_TOKEN.getName());
    @NonNls String fileFilter = message.body().getString(Field.FILE_FILTER.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    List<JsonObject> foldersJson = new ArrayList<>();
    List<JsonObject> filesJson = new ArrayList<>();

    // we do not allow accessing trash folder below the root.
    if (!Objects.equals(folderId, ROOT_FOLDER_ID)) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "UserHasNoAccessToFolderWithId"), folderId),
          HttpStatus.FORBIDDEN);
      return;
    }
    List<Item> queryResultItems = new ArrayList<>();
    QueryResult queryResult = SimpleStorage.getTrashFolderItems(
        SimpleStorage.makeTrashFolderId(folderId, ownerId), pageToken);
    if (Objects.nonNull(queryResult)) {
      queryResultItems.addAll(SimpleStorage.getItems(queryResult));
      pageToken = SimpleStorage.getContinuationToken(queryResult);
      while (Objects.nonNull(pageToken) && queryResultItems.size() < MIN_PAGE_SIZE) {
        queryResult = SimpleStorage.getTrashFolderItems(
            SimpleStorage.makeTrashFolderId(folderId, ownerId), pageToken);
        if (Objects.isNull(queryResult)) {
          break;
        }
        pageToken = SimpleStorage.getContinuationToken(queryResult);
        queryResultItems.addAll(SimpleStorage.getItems(queryResult));
      }
    }
    for (Item item : queryResultItems) {
      if (!SimpleStorage.isErased(
          item)) { // we are already checking erased attribute but just to be sure.
        if (SimpleStorage.isFile(item)) {
          if (Extensions.isValidExtension(extensions, SimpleStorage.getName(item))) {
            filesJson.add(getFileJson(item, ownerId, isAdmin, false, false, false));
          }
        } else {
          foldersJson.add(getFolderJson(segment, message, item, ownerId, false));
        }
      }
    }
    filesJson.sort(Comparator.comparing(o2 -> o2.getString(Field.FILE_NAME.getName())));
    foldersJson.sort(Comparator.comparing(o -> o.getString(Field.NAME.getName())));
    JsonObject response = new JsonObject()
        .put(
            Field.RESULTS.getName(),
            new JsonObject()
                .put(Field.FILES.getName(), new JsonArray(filesJson))
                .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
        .put("number", filesJson.size() + foldersJson.size())
        .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      response.put(Field.PAGE_TOKEN.getName(), pageToken);
    }
    sendOK(segment, message, response, mills);
    recordExecutionTime("getTrashFolderContent", System.currentTimeMillis() - mills);
  }

  public void connect(Message<JsonObject> message, boolean bool) {
    sendNotImplementedError(message);
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String parentId =
          getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
      String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
      String userId = message.body().getString(Field.USER_ID.getName());
      Item parent = null;
      if (parentId == null) {
        return;
      }
      boolean isTouch = message
          .body()
          .getString(Field.DEVICE.getName(), AuthManager.ClientType.BROWSER.name())
          .equals(AuthManager.ClientType.TOUCH.name());
      // if parentId provided check if it exists and user has access to it
      if (!parentId.equals(ROOT_FOLDER_ID)
          && !parentId.equals(ROOT)
          && !parentId.equals(SHARED_FOLDER)) {
        parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
        if (parent == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "ParentFolderWithIdDoesNotExist"), parentId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (SimpleStorage.isDeleted(parent)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "ParentFolderWithIdIsDeleted"), parentId),
              HttpStatus.BAD_REQUEST);
          return;
        }
        if (!SimpleStorage.hasRights(
            parent, SimpleStorage.makeUserId(userId), true, AccessType.canCreateFolders)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeInFolderWithId"),
                  AccessType.canCreateFolders.label,
                  parentId),
              HttpStatus.FORBIDDEN);
          return;
        }
      } else if (!isTouch
          && (parentId.equals(SHARED_FOLDER) || (parentId.equals(ROOT_FOLDER_ID)))) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotCreateFileOrFolderHere"),
            HttpStatus.FORBIDDEN);
        return;
      }
      // check the name
      if (!SimpleStorage.checkName(
          SimpleStorage.makeFolderId(parentId, userId), name, false, null)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      Set<String> editors = SimpleStorage.getEditors(parent);
      Set<String> viewers = SimpleStorage.getViewers(parent);

      if (parent != null && SimpleStorage.isShared(parent)) {
        // if the parent folder is shared and our user != owner, add the owner of the parent
        // folder as an editor to this folder
        if (!SimpleStorage.checkOwner(parent, SimpleStorage.makeUserId(userId))) {
          editors.add(SimpleStorage.getOwner(parent));
          editors.remove(userId);
        }
      }

      String folderId = SimpleStorage.addFolder(
          SimpleStorage.makeUserId(userId),
          name,
          SimpleStorage.makeFolderId(parentId, userId),
          editors,
          viewers);

      // create share items for folder from parent collaborators
      if (Objects.nonNull(parent)
          && SimpleStorage.isShared(parent)
          && (Objects.nonNull(editors) || Objects.nonNull(viewers))) {
        Item finalParent = parent;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("shareCreatedFolder")
            .run((Segment blockingSegment) -> {
              Item newFolder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
              if (Objects.isNull(newFolder)) {
                return;
              }
              Set<String> collaborators = new HashSet<>();
              if (Objects.nonNull(editors)) {
                collaborators.addAll(editors);
              }
              if (Objects.nonNull(viewers)) {
                collaborators.addAll(viewers);
              }
              for (String collaborator : collaborators) {
                SimpleStorage.addItemShare(
                    newFolder,
                    SimpleStorage.makeUserId(collaborator),
                    SimpleStorage.makeFolderId(SimpleStorage.getItemId(finalParent), collaborator));
              }
            });
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      SimpleStorage.getAccountId(userId),
                      folderId)),
          mills);

      // update lastModify info for all folders in hierarchy
      updateLastModifiedInfo(
          segment, message, SimpleStorage.makeFolderId(folderId, userId), userId);

      recordExecutionTime("createFolder", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doMoveFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String folderId =
          getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
      String parentId =
          getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
      String ownerId = message.body().getString(Field.USER_ID.getName());

      if (folderId == null || parentId == null) {
        return;
      }
      if (ROOT_FOLDER_ID.equals(folderId)
          || ROOT.equals(folderId)
          || SHARED_FOLDER.equals(folderId)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YouCannotMoveRootFolder"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      // check if folder exists and user is able to modify it
      Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, ownerId));
      if (folder == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
            HttpStatus.NOT_FOUND);
        return;
      }

      if (SimpleStorage.isDeleted(folder)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
            HttpStatus.BAD_REQUEST);
        return;
      }

      boolean isOwner = SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(ownerId));
      Item share = null;
      if (!isOwner) {
        share = SimpleStorage.getItemShare(
            SimpleStorage.makeFolderId(folderId, ownerId), SimpleStorage.makeUserId(ownerId));
      }

      if (!isOwner && share == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "OnlyOwnerCanAccessWithTypeToFolderWithId"),
                AccessType.canMove.label,
                folderId),
            HttpStatus.FORBIDDEN);
        return;
      }
      String originalParentId = SimpleStorage.getParent(folder, ownerId);
      Item originalParent =
          SimpleStorage.getItem(SimpleStorage.makeFolderId(originalParentId, ownerId));
      // check canMoveFrom for current folder
      if (Objects.nonNull(originalParent)
          && !SimpleStorage.hasRights(
              originalParent, SimpleStorage.makeUserId(ownerId), true, AccessType.canMoveFrom)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                AccessType.canMoveFrom.label,
                originalParentId),
            HttpStatus.FORBIDDEN);
        return;
      }
      boolean notifyMove = false;
      // check the same for parent, if it's not 'root' or 'shared' folder
      String parentName = ROOT;
      Item parent = null;
      if (!ROOT.equals(parentId)
          && !SHARED_FOLDER.equals(parentId)
          && !ROOT_FOLDER_ID.equals(parentId)) {
        parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, ownerId));
        if (parent == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "ParentFolderWithIdDoesNotExist"), parentId),
              HttpStatus.NOT_FOUND);
          return;
        }
        // check canMoveTo for new folder
        if (!SimpleStorage.hasRights(
            parent, SimpleStorage.makeUserId(ownerId), true, AccessType.canMoveTo)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                  AccessType.canMoveTo.label,
                  parentId),
              HttpStatus.FORBIDDEN);
          return;
        }
        if (SimpleStorage.isDeleted(parent)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "ParentFolderWithIdIsDeleted"), parentId),
              HttpStatus.BAD_REQUEST);
          return;
        }
        notifyMove = true;
        parentName = SimpleStorage.getName(parent);
      } else if (parentId.equals(ROOT_FOLDER_ID) || parentId.equals(SHARED_FOLDER)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToBaseOrSharedFolder"),
                AccessType.canMoveTo.label),
            HttpStatus.FORBIDDEN);
        return;
      } else {
        // root ("My files")
        if (!SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(ownerId))) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToRootFolder"),
                  AccessType.canMoveTo.label),
              HttpStatus.FORBIDDEN);
          return;
        }
      }

      // check recursively if parent is not a sub folder of moving folder
      if (SimpleStorage.isNotChild(parentId, folderId)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "ParentCouldNotBeTheSameFoldeOrASubFolderOfMovedOne"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      // check the name
      if (!SimpleStorage.checkName(parentId, SimpleStorage.getName(folder), false, null)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      SimpleStorage.updateParent(
          (share != null ? share : folder), SimpleStorage.makeFolderId(parentId, ownerId));

      Set<String> itemCollaborators = getAllObjectCollaborators(folder);
      Set<String> parentCollaborators = getAllObjectCollaborators(parent);
      Item finalParent = parent;

      // WB-1137 : Notify only if collaborator has access to new moved parent
      JsonArray usersToNotify = new JsonArray();
      if (notifyMove) {
        itemCollaborators.stream()
            .filter(collaborator -> parentCollaborators.contains(collaborator)
                || SimpleStorage.getOwner(finalParent).equals(collaborator))
            .forEach(usersToNotify::add);

        // 03/08/20 DK: I don't understand what's the purpose of this message at all.
        eb_send(
            segment,
            MailUtil.address + ".notifyMove",
            new JsonObject()
                .put(Field.OWNER_ID.getName(), ownerId)
                .put(Field.NAME.getName(), SimpleStorage.getName(folder))
                .put(Field.ID.getName(), folderId)
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(Field.FOLDER.getName(), parentName)
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(Field.FOLDER_ID.getName(), parentId)
                .put(Field.USERS.getName(), usersToNotify)
                .put(Field.TYPE.getName(), 0));
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("handleShareOnMoveFolder")
          .runWithoutSegment(() -> {
            Item finalFolder = null;
            // update the current item collaborators based on the new parent
            for (String user : itemCollaborators) {
              Item shareItem = SimpleStorage.getItemShare(folder, SimpleStorage.makeUserId(user));
              if (Objects.nonNull(shareItem)) {
                if (usersToNotify.contains(user)) {
                  SimpleStorage.updateParent(
                      shareItem, SimpleStorage.makeFolderId(parentId, ownerId));
                } else {
                  if (SimpleStorage.checkIfUserHasAccessToItem(originalParent, user)) {
                    // Remove access to the folder if it's shared along with the parent
                    SimpleStorage.removeSharedUsersAndUpdateItem(
                        folder, new JsonArray().add(user), false);
                    SimpleStorage.moveFolderSharedWithUser(folder, ownerId);
                    finalFolder =
                        SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, ownerId));
                  }
                }
              }
            }

            if (Objects.nonNull(originalParent)) {
              String[] baseParent = new String[1];
              getBaseParent(originalParent, baseParent, ownerId);
              String oldBaseParentId = baseParent[0];
              String newBaseParentId;
              if (Objects.nonNull(finalParent)) {
                getBaseParent(finalParent, baseParent, ownerId);
                newBaseParentId = baseParent[0];
              } else {
                newBaseParentId = parentId;
              }
              // only if item is moved from "Shared files" to "My files"
              if (Utils.isStringNotNullOrEmpty(oldBaseParentId)
                  && Utils.isStringNotNullOrEmpty(newBaseParentId)
                  && oldBaseParentId.equals(SHARED_FOLDER)
                  && newBaseParentId.equals(ROOT)) {
                Set<String> folderCollaborators =
                    SimpleFluorine.getAllObjectCollaborators(originalParent);
                if (!SimpleStorage.getOwner(originalParent).equals(ownerId)) {
                  folderCollaborators.add(SimpleStorage.getOwner(originalParent));
                  folderCollaborators.remove(ownerId);
                }
                List<String> commonSharedUsers = new ArrayList<>(folderCollaborators);
                commonSharedUsers.retainAll(itemCollaborators);
                SimpleStorage.removeSharedUsersAndUpdateItem(
                    folder, new JsonArray(commonSharedUsers), false);
                SimpleStorage.moveFolderSharedWithUser(folder, ownerId);
                // just to make sure we have the latest folder item after update
                finalFolder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, ownerId));
              }
            }
            if (Objects.isNull(finalFolder)) {
              finalFolder = folder;
            }
            List<String> parentEditors = SimpleStorage.getEditors(finalParent) != null
                ? new ArrayList<>(SimpleStorage.getEditors(finalParent))
                : null;
            List<String> parentViewers = SimpleStorage.getViewers(finalParent) != null
                ? new ArrayList<>(SimpleStorage.getViewers(finalParent))
                : null;
            if (!SimpleStorage.checkOwner(finalParent, SimpleStorage.makeUserId(ownerId))) {
              if (Objects.nonNull(parentEditors)) {
                parentEditors.remove(SimpleStorage.getOwner(finalFolder));
                parentEditors.add(SimpleStorage.getOwner(finalParent));
              }
              if (Objects.nonNull(parentViewers)) {
                parentViewers.remove(SimpleStorage.getOwner(finalFolder));
              }
              parentCollaborators.remove(SimpleStorage.getOwner(finalFolder));
              parentCollaborators.add(SimpleStorage.getOwner(finalParent));
            }
            Set<String> folderCollaborators = getAllObjectCollaborators(finalFolder);
            parentCollaborators.removeAll(folderCollaborators);
            for (String collaborator : parentCollaborators) {
              SimpleStorage.addItemShare(
                  finalFolder,
                  SimpleStorage.makeUserId(collaborator),
                  SimpleStorage.makeFolderId(SimpleStorage.getItemId(finalParent), collaborator));
            }
            addItemCollaborators(finalFolder, parentEditors, parentViewers);
            SimpleStorage.shareMovedFolderWithParentCollaborators(
                finalFolder, ownerId, parentCollaborators);
            shareContent(folderId, ownerId, parentEditors, parentViewers, false);
          });
      sendOK(segment, message, mills);
      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(
          segment, message, SimpleStorage.makeFolderId(folderId, ownerId), ownerId);
      updateLastModifiedInfo(
          segment, message, SimpleStorage.makeFolderId(originalParentId, ownerId), ownerId);

      recordExecutionTime("moveFolder", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doMoveFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
      String folderId =
          getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
      String ownerId = message.body().getString(Field.USER_ID.getName());
      String originalParentId;
      if (fileId == null || folderId == null) {
        return;
      }
      // check if file exists and user is able to modify it
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      originalParentId = SimpleStorage.getParent(file, ownerId);
      if (!SimpleStorage.checkOwner(file, SimpleStorage.makeUserId(ownerId))) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "OnlyOwnerCanAccessWithTypeToFileWithId"),
                AccessType.canMove.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                Utils.getLocalizedString(message, "File"),
                fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item originalParent =
          SimpleStorage.getItem(SimpleStorage.makeFolderId(originalParentId, ownerId));
      if (Objects.nonNull(originalParent)
          && !SimpleStorage.hasRights(
              originalParent, SimpleStorage.makeUserId(ownerId), true, AccessType.canMoveFrom)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                AccessType.canMoveFrom.label,
                originalParentId),
            HttpStatus.FORBIDDEN);
        return;
      }

      boolean notifyMove = false;
      // check if folder exists and user is able to change this folder
      String folderName = Field.ROOT.getName();
      Item folder = null;
      if (!ROOT_FOLDER_ID.equals(folderId)
          && !ROOT.equals(folderId)
          && !SHARED_FOLDER.equals(folderId)) {
        folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, ownerId));
        if (Objects.isNull(folder)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (!SimpleStorage.hasRights(
            folder, SimpleStorage.makeUserId(ownerId), true, AccessType.canMoveTo)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                  AccessType.canMoveTo.label,
                  folderId),
              HttpStatus.FORBIDDEN);
          return;
        }
        if (SimpleStorage.isDeleted(folder)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
              HttpStatus.BAD_REQUEST);
          return;
        }
        notifyMove = true;
        folderName = SimpleStorage.getName(folder);
      } else if (folderId.equals(ROOT_FOLDER_ID) || folderId.equals(SHARED_FOLDER)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToBaseOrSharedFolder"),
                AccessType.canMoveTo.label),
            HttpStatus.FORBIDDEN);
        return;
      } else {
        // root ("My files")
        if (!SimpleStorage.checkOwner(file, SimpleStorage.makeUserId(ownerId))) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToRootFolder"),
                  AccessType.canMoveTo.label),
              HttpStatus.FORBIDDEN);
          return;
        }
      }
      // check the name
      if (!SimpleStorage.checkName(folderId, SimpleStorage.getName(file), true, null)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      SimpleStorage.updateParent(file, SimpleStorage.makeFolderId(folderId, ownerId));

      Set<String> itemCollaborators = getAllObjectCollaborators(file);
      Set<String> parentCollaborators = getAllObjectCollaborators(folder);

      Item finalFolder = folder;
      // WB-1137 : Notify only if collaborator has access to new moved parent
      JsonArray usersToNotify = new JsonArray();
      if (notifyMove) {
        itemCollaborators.stream()
            .filter(collaborator -> parentCollaborators.contains(collaborator)
                || SimpleStorage.getOwner(finalFolder).equals(collaborator))
            .forEach(usersToNotify::add);

        eb_send(
            segment,
            MailUtil.address + ".notifyMove",
            new JsonObject()
                .put(Field.OWNER_ID.getName(), ownerId)
                .put(Field.NAME.getName(), SimpleStorage.getName(file))
                .put(Field.ID.getName(), fileId)
                .put(Field.FOLDER.getName(), folderName)
                .put(Field.FOLDER_ID.getName(), folderId)
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(Field.USERS.getName(), usersToNotify)
                .put(Field.TYPE.getName(), 1));
      }

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("handleShareOnMoveFile")
          .runWithoutSegment(() -> {
            Item finalFile = null;
            // update the current item collaborators based on the new parent
            for (String user : itemCollaborators) {
              Item share = SimpleStorage.getItemShare(file, SimpleStorage.makeUserId(user));
              if (Objects.nonNull(share)) {
                if (usersToNotify.contains(user)) {
                  SimpleStorage.updateParent(share, SimpleStorage.makeFolderId(folderId, ownerId));
                } else {
                  if (SimpleStorage.checkIfUserHasAccessToItem(originalParent, user)) {
                    // Remove access to the file if it's shared along with the parent
                    SimpleStorage.removeSharedUsersAndUpdateItem(
                        file, new JsonArray().add(user), false);
                    finalFile = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
                  }
                }
              }
            }

            if (Objects.nonNull(originalParent)) {
              String[] baseParent = new String[1];
              getBaseParent(originalParent, baseParent, ownerId);
              String oldBaseParentId = baseParent[0];
              String newBaseParentId;
              if (Objects.nonNull(finalFolder)) {
                getBaseParent(finalFolder, baseParent, ownerId);
                newBaseParentId = baseParent[0];
              } else {
                newBaseParentId = folderId;
              }
              // only if item is moved from "Shared files" to "My files"
              if (Utils.isStringNotNullOrEmpty(oldBaseParentId)
                  && Utils.isStringNotNullOrEmpty(newBaseParentId)
                  && oldBaseParentId.equals(SHARED_FOLDER)
                  && newBaseParentId.equals(ROOT)) {
                Set<String> folderCollaborators =
                    SimpleFluorine.getAllObjectCollaborators(originalParent);
                if (!SimpleStorage.getOwner(originalParent).equals(ownerId)) {
                  folderCollaborators.add(SimpleStorage.getOwner(originalParent));
                  folderCollaborators.remove(ownerId);
                }
                List<String> commonSharedUsers = new ArrayList<>(folderCollaborators);
                commonSharedUsers.retainAll(itemCollaborators);
                SimpleStorage.removeSharedUsersAndUpdateItem(
                    file, new JsonArray(commonSharedUsers), false);
                // just to make sure we have the latest file item after any update
                finalFile = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
              }
            }
            if (Objects.isNull(finalFile)) {
              finalFile = file;
            }
            // ----- WB-1089 -----
            List<String> parentEditors = SimpleStorage.getEditors(finalFolder) != null
                ? new ArrayList<>(SimpleStorage.getEditors(finalFolder))
                : null;
            List<String> parentViewers = SimpleStorage.getViewers(finalFolder) != null
                ? new ArrayList<>(SimpleStorage.getViewers(finalFolder))
                : null;
            if (!SimpleStorage.checkOwner(finalFolder, SimpleStorage.makeUserId(ownerId))) {
              if (Objects.nonNull(parentEditors)) {
                parentEditors.remove(SimpleStorage.getOwner(file));
                parentEditors.add(SimpleStorage.getOwner(finalFolder));
              }
              if (Objects.nonNull(parentViewers)) {
                parentViewers.remove(SimpleStorage.getOwner(file));
              }
            }
            addItemCollaborators(finalFile, parentEditors, parentViewers);

            if (!SimpleStorage.checkOwner(finalFolder, SimpleStorage.makeUserId(ownerId))) {
              parentCollaborators.remove(SimpleStorage.getOwner(file));
              parentCollaborators.add(SimpleStorage.getOwner(finalFolder));
            }
            Set<String> fileCollaborators = getAllObjectCollaborators(finalFile);
            parentCollaborators.removeAll(fileCollaborators);
            for (String collaborator : parentCollaborators) {
              SimpleStorage.addItemShare(
                  finalFile,
                  SimpleStorage.makeUserId(collaborator),
                  SimpleStorage.makeFolderId(SimpleStorage.getItemId(finalFolder), collaborator));
            }
          });

      sendOK(segment, message, mills);

      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(segment, message, SimpleStorage.makeFileId(fileId), ownerId);
      updateLastModifiedInfo(
          segment, message, SimpleStorage.makeFolderId(originalParentId, ownerId), ownerId);

      recordExecutionTime("moveFile", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private void getBaseParent(Item item, String[] baseParentId, String currentUserId) {
    String parentId = SimpleStorage.getParent(item, currentUserId);
    baseParentId[0] = parentId;
    if (Objects.nonNull(parentId)
        && !parentId.equals(ROOT_FOLDER_ID)
        && !parentId.equals(ROOT)
        && !parentId.equals(SHARED_FOLDER)) {
      Item parent = SimpleStorage.getItem(parentId);
      if (Objects.nonNull(parent)) {
        getBaseParent(parent, baseParentId, currentUserId);
      }
    }
  }

  public void doRenameFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String folderId =
          getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
      String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
      String ownerId = message.body().getString(Field.USER_ID.getName());
      boolean ignoreDeleted = message.body().getBoolean("ignoreDeleted");
      if (folderId == null || name == null) {
        return;
      }
      // check if file exists and user is able to change it
      Item item = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, ownerId));
      if (item == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                Utils.getLocalizedString(message, "Folder"),
                folderId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(
          item, SimpleStorage.makeUserId(ownerId), true, AccessType.canRename)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                AccessType.canRename.label,
                folderId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (!ignoreDeleted && SimpleStorage.isDeleted(item)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                Utils.getLocalizedString(message, "Folder"),
                folderId),
            HttpStatus.FORBIDDEN);
        return;
      }
      // check the name
      if (!SimpleStorage.checkName(
          SimpleStorage.getParentStr(item), name, false, item.getString(Field.SK.getName()))) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      try {
        SimpleStorage.updateFolderName(item, name);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }
      sendOK(segment, message, mills);

      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(segment, message, item, ownerId);

      recordExecutionTime("renameFolder", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRenameFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
      String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
      String ownerId = message.body().getString(Field.USER_ID.getName());
      boolean ignoreDeleted = message.body().getBoolean("ignoreDeleted");
      if (fileId == null || name == null) {
        return;
      }

      // check if file exists and user is able to change it
      Item item = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (item == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                Utils.getLocalizedString(message, "File"),
                fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(
          item, SimpleStorage.makeUserId(ownerId), true, AccessType.canRename)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                AccessType.canRename.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (!ignoreDeleted && SimpleStorage.isDeleted(item)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                Utils.getLocalizedString(message, "File"),
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      // check the name
      if (!SimpleStorage.checkName(
          SimpleStorage.getParentStr(item), name, true, item.getString(Field.SK.getName()))) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      try {
        SimpleStorage.updateFileName(item, name);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }
      sendOK(segment, message, mills);

      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(segment, message, item, ownerId);

      recordExecutionTime("renameFile", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doDeleteFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String folderId =
          getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
      String userId = message.body().getString(Field.USER_ID.getName());
      if (folderId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      trashFolder(segment, message, folderId, userId, mills);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("handleTrashFolderContent")
          .run((Segment blockingSegment) -> {
            Set<Item> parentFolders = new HashSet<>();
            trashFolderContent(blockingSegment, folderId, userId, parentFolders);
            List<Item> sortedFolders =
                SimpleStorage.sortParentFoldersForCreationDate(parentFolders, null);
            sortedFolders.forEach(folder -> {
              String idToDelete =
                  SimpleStorage.makeFolderId(SimpleStorage.getItemId(folder), userId);
              if (SimpleStorage.isEmptyFolder(idToDelete)) {
                SimpleStorage.delete(folder, ttl, null);
              }
            });
          });
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("trashFolder", System.currentTimeMillis() - mills);
  }

  private void trashFolder(
      Entity segment, Message<JsonObject> message, String folderId, String userId, long mills) {
    // check if the user is able to delete this folder
    Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
    if (folder != null) {
      if (!SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(userId))) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                AccessType.canDelete.label,
                folderId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(folder)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      // set parent = owner root for deleted items
      SimpleStorage.deleteAndResetParent(folder, ttl, null);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("notifyDelete")
          .run((Segment blockingSegment) -> {
            Set<String> collaborators = getAllObjectCollaborators(folder);
            Set<String> owners = getAllItemOwnersInFolder(folder, userId, collaborators);
            collaborators.addAll(owners);
            eb_send(
                blockingSegment,
                MailUtil.address + ".notifyDelete",
                new JsonObject()
                    .put(Field.OWNER_ID.getName(), userId)
                    .put(Field.NAME.getName(), SimpleStorage.getName(folder))
                    .put(Field.USERS.getName(), new JsonArray(new ArrayList<>(collaborators)))
                    .put(Field.TYPE.getName(), 0));
          });

      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(segment, message, folder, userId);

      sendOK(segment, message, mills);
    } else {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
          HttpStatus.NOT_FOUND);
    }
  }

  private void trashFolderContent(
      Entity segment, String folderId, String userId, Set<Item> parentFolders) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "trashFolderContent");
    try {
      Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
      if (Objects.nonNull(folder)) {
        Iterator<Item> items = SimpleStorage.getFolderItems(
                SimpleStorage.makeFolderId(folderId, userId), null, null)
            .iterator();
        List<Item> itemList = IteratorUtils.toList(items);
        List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, false);
        itemList.addAll(itemShares);
        for (Item item : itemList) {
          if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
            if (!checkIfParentCollaboratorsHasAccess(item, folderId)) {
              continue;
            }
          }
          boolean isItemShare = SimpleStorage.isItemShare(item);
          boolean isOwner = SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId));
          Set<String> itemCollaborators;
          if (isItemShare) {
            Item originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
            if (Objects.nonNull(originalItem)) {
              itemCollaborators = getAllObjectCollaborators(originalItem);
            } else {
              itemCollaborators = new HashSet<>();
            }
          } else {
            itemCollaborators = getAllObjectCollaborators(item);
          }
          if (!isOwner) {
            itemCollaborators.add(SimpleStorage.getOwner(item));
          }
          String deleteMarker = null;
          if (SimpleStorage.isFile(item)) {
            if (isOwner) {
              if (!SimpleStorage.isSampleFile(item)) {
                deleteMarker = getS3Regional(item, userId).delete(SimpleStorage.getPath(item));
              }
            } else {
              log.warn("trashFolderContent : Item does not belong to owner " + item);
            }
            eb_send(
                subsegment,
                WebSocketManager.address + ".deleted",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                    .put("parentDeleted", true));
            eb_send(
                subsegment,
                WebSocketManager.address + ".objectDeleted",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.ITEM_ID.getName(), SimpleStorage.getItemId(item))
                    .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                    .put(Field.IS_FOLDER.getName(), false)
                    .put(
                        Field.COLLABORATORS.getName(),
                        new JsonArray(Lists.newArrayList(itemCollaborators))));
            eb_send(
                subsegment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                    .put(Field.ACCESSIBLE.getName(), false)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            XenonSessions.deleteAllSessionsForFile(SimpleStorage.getItemId(item));
            VersionLinkRepository.getInstance().deleteAllFileLinks(SimpleStorage.getItemId(item));
          }
          if (isItemShare) {
            SimpleStorage.hideItemShare(item, ttl);
          } else {
            if (SimpleStorage.isFile(item)) {
              SimpleStorage.delete(item, ttl, deleteMarker);
            } else {
              parentFolders.add(item);
            }
          }
          if (SimpleStorage.isFolder(item)) {
            eb_send(
                subsegment,
                WebSocketManager.address + ".objectDeleted",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.ITEM_ID.getName(), SimpleStorage.getItemId(item))
                    .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                    .put(Field.IS_FOLDER.getName(), true)
                    .put(
                        Field.COLLABORATORS.getName(),
                        new JsonArray(Lists.newArrayList(itemCollaborators))));
            trashFolderContent(segment, SimpleStorage.getItemId(item), userId, parentFolders);
          }
        }
      } else {
        log.error("trashFolderContent : Folder with id " + folderId + " does not exist");
      }
    } catch (Exception e) {
      log.error("Error occurred in deleting folder with id " + folderId + " : "
          + e.getLocalizedMessage());
    }
    XRayManager.endSegment(subsegment);
  }

  public void doDeleteFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
      String userId = message.body().getString(Field.USER_ID.getName());
      if (fileId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.checkOwner(file, SimpleStorage.makeUserId(userId))) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                AccessType.canDelete.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String deleteMarker = null;
      if (!SimpleStorage.isSampleFile(file)) {
        deleteMarker = getS3Regional(file, userId).delete(SimpleStorage.getPath(file));
      }
      try {
        SimpleStorage.deleteAndResetParent(file, ttl, deleteMarker);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("notifyTrashFile")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                WebSocketManager.address + ".deleted",
                new JsonObject().put(Field.FILE_ID.getName(), file.getString(Field.ID.getName())));
            eb_send(
                blockingSegment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), file.getString(Field.ID.getName()))
                    .put(Field.ACCESSIBLE.getName(), false)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            Set<String> collaborators = getAllObjectCollaborators(file);
            eb_send(
                blockingSegment,
                MailUtil.address + ".notifyDelete",
                new JsonObject()
                    .put(Field.OWNER_ID.getName(), userId)
                    .put(Field.NAME.getName(), SimpleStorage.getName(file))
                    .put(Field.USERS.getName(), new JsonArray(new ArrayList<>(collaborators)))
                    .put(Field.TYPE.getName(), 1));
          });
      sendOK(segment, message, mills);

      // update lastModified info for all folders in hierarchy
      updateLastModifiedInfo(segment, message, file, userId);

      recordExecutionTime("trashFile", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private String cloneFile(
      Entity segment,
      Message<JsonObject> message,
      String folderId,
      String userId,
      String name,
      String fileId,
      Set<String> editors,
      Set<String> viewers,
      boolean sendMsg) {
    Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
    if (file == null || SimpleStorage.isDeleted(file)) {
      if (sendMsg) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
      }
      return null;
    }

    S3Regional userS3 = getS3Regional(file, userId);

    if (!SimpleStorage.checkQuota(
        SimpleStorage.makeUserId(userId),
        SimpleStorage.getFileSize(file),
        config.getProperties().getDefaultUserOptionsAsJson())) {
      if (sendMsg) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YouDontHaveEnoughSpace"),
            HttpStatus.FORBIDDEN,
            KudoErrorCodes.NO_SPACE_LEFT.getCode());
      }
      return null;
    }
    if (!userS3.doesObjectExist(SimpleStorage.getPath(file))) {
      if (sendMsg) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
      }
      return null;
    }
    String newFileId = UUID.randomUUID().toString();
    String versionId =
        userS3.copy(SimpleStorage.getPath(file), SimpleStorage.generatePath(userId, newFileId));

    SimpleStorage.addFile(
        newFileId,
        SimpleStorage.makeUserId(userId),
        name,
        SimpleStorage.makeFolderId(folderId, userId),
        versionId,
        SimpleStorage.getFileSize(file),
        SimpleStorage.generatePath(userId, newFileId),
        editors,
        viewers,
        userS3.getRegion(),
        true);
    sendSampleUsageWsNotification(segment, message, userId, true);
    return newFileId;
  }

  private void cloneFolder(
      Entity segment,
      Message<JsonObject> message,
      String folderId,
      String userId,
      String newParentId,
      Set<String> editors,
      Set<String> viewers) {
    Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
    if (Objects.isNull(folder)) {
      return;
    }
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, userId), null, null)
        .iterator();
    Item item;
    while (items.hasNext()) {
      item = items.next();
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      if (SimpleStorage.isFile(item)) {
        cloneFile(
            segment,
            message,
            newParentId,
            userId,
            SimpleStorage.getName(item),
            SimpleStorage.getItemId(item),
            editors,
            viewers,
            false);
      } else {
        String newFolderId = SimpleStorage.addFolder(
            SimpleStorage.makeUserId(userId),
            SimpleStorage.getName(item),
            SimpleStorage.makeFolderId(newParentId, userId),
            editors,
            viewers);
        cloneFolder(
            segment, message, SimpleStorage.getItemId(item), userId, newFolderId, editors, viewers);
      }
    }
  }

  public void doClone(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String userId = message.body().getString(Field.USER_ID.getName());
      String fileId = message.body().getString(Field.FILE_ID.getName());
      String folderId = message.body().getString(Field.FOLDER_ID.getName());
      boolean doCopyComments = message.body().getBoolean(Field.COPY_COMMENTS.getName(), false);
      boolean doCopyShare = message.body().getBoolean(Field.COPY_SHARE.getName(), false);
      String name = getRequiredString(
          segment,
          fileId != null ? Field.FILE_NAME_C.getName() : Field.FOLDER_NAME.getName(),
          message,
          message.body());
      if (name == null) {
        return;
      }
      if (fileId == null && folderId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (folderId != null) {
        // check if folder exists and user has access to it
        Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
        if (folder == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (SimpleStorage.isDeleted(folder)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
              HttpStatus.BAD_REQUEST);
          return;
        }
        if (!SimpleStorage.hasRights(
            folder, SimpleStorage.makeUserId(userId), false, AccessType.canClone)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
                  AccessType.canClone.label,
                  folderId),
              HttpStatus.FORBIDDEN);
          return;
        }
        String parentId = SimpleStorage.getParent(folder, userId);
        boolean clonedFromSharedFiles = false,
            clonedFromViewOnlyFolder = false,
            clonedToRoot = false;
        if (SimpleStorage.isSharedWithUser(folder, userId)) {
          Item share = SimpleStorage.getItemShare(folder, SimpleStorage.makeUserId(userId));
          String shareParentId = SimpleStorage.getParent(share, userId);
          if (shareParentId.equals(SHARED_FOLDER)) {
            clonedFromSharedFiles = true;
          } else if (!shareParentId.equals(ROOT)) {
            clonedFromViewOnlyFolder = isClonedFromViewOnlyFolder(shareParentId, userId);
          }
        } else if (SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(userId))) {
          clonedFromViewOnlyFolder = isClonedFromViewOnlyFolder(parentId, userId);
        }
        if (clonedFromSharedFiles || clonedFromViewOnlyFolder) {
          parentId = ROOT;
          clonedToRoot = true;
        }
        // check folder name
        if (!SimpleStorage.checkName(
            (parentId.equals(SHARED_FOLDER) || parentId.equals(ROOT))
                ? SimpleStorage.makeUserId(userId)
                : SimpleStorage.getParentStr(folder),
            name,
            false,
            null)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "DuplicateName"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        Item parent = null;
        Set<String> editors = null, viewers = null;
        if (!parentId.equals(ROOT_FOLDER_ID)
            && !parentId.equals(ROOT)
            && !parentId.equals(SHARED_FOLDER)) {
          parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
          if (parent == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), parentId),
                HttpStatus.NOT_FOUND);
            return;
          }
          if (SimpleStorage.isDeleted(parent)) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), parentId),
                HttpStatus.BAD_REQUEST);
            return;
          }

          if (!SimpleStorage.hasRights(
              parent, SimpleStorage.makeUserId(userId), true, AccessType.canCreateFolders)) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "UserHasNoAccessWithTypeInFolderWithId"),
                    AccessType.canClone.label,
                    parentId),
                HttpStatus.FORBIDDEN);
            return;
          }
          if (SimpleStorage.isShared(parent)) {
            // if the folder is shared and our user != owner, add the owner of the folder as an
            // editor to this folder
            editors = SimpleStorage.getEditors(parent);
            viewers = SimpleStorage.getViewers(parent);

            if (!SimpleStorage.checkOwner(parent, SimpleStorage.makeUserId(userId))) {
              editors.add(SimpleStorage.getOwner(parent));
              editors.remove(userId);
            }
          }
        } else if (parentId.equals(ROOT_FOLDER_ID)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotCreateFileOrFolderHere"),
              HttpStatus.FORBIDDEN);
          return;
        }
        // create new folder
        String newFolderId = SimpleStorage.addFolder(
            SimpleStorage.makeUserId(userId),
            name,
            SimpleStorage.makeFolderId(parentId, userId),
            editors,
            viewers);

        Set<String> finalEditors = editors, finalViewers = viewers;
        String finalFolderId = folderId;
        boolean finalClonedToRoot = clonedToRoot;
        if (doCopyShare) {
          // AS: We run /info API just after clone is finished and if this is done as async then
          // we don't see the latest data (new file share info) on the UI until we refresh.

          // clone accessible files
          cloneFolder(
              segment, message, finalFolderId, userId, newFolderId, finalEditors, finalViewers);
          // copy sharing data
          copyShareData(message, folder, userId, newFolderId, finalClonedToRoot, true);
        } else {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("cloneFolder")
              .run((Segment blockingSegment) -> cloneFolder(
                  blockingSegment,
                  message,
                  finalFolderId,
                  userId,
                  newFolderId,
                  finalEditors,
                  finalViewers));
        }

        // create share items for folder from parent collaborators
        if (Objects.nonNull(parent)
            && SimpleStorage.isShared(parent)
            && (Objects.nonNull(finalEditors) || Objects.nonNull(finalViewers))) {
          Item finalParent = parent;
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("shareOnCloneFolder")
              .runWithoutSegment(() -> {
                Item newFolder =
                    SimpleStorage.getItem(SimpleStorage.makeFolderId(newFolderId, userId));
                if (Objects.isNull(newFolder)) {
                  return;
                }
                Set<String> collaborators = new HashSet<>();
                if (Objects.nonNull(finalEditors)) {
                  collaborators.addAll(finalEditors);
                }
                if (Objects.nonNull(finalViewers)) {
                  collaborators.addAll(finalViewers);
                }
                for (String collaborator : collaborators) {
                  SimpleStorage.addItemShare(
                      newFolder,
                      SimpleStorage.makeUserId(collaborator),
                      SimpleStorage.makeFolderId(
                          SimpleStorage.getItemId(finalParent), collaborator));
                  shareFolderItems(newFolderId, collaborator, userId, false);
                }
              });
        }
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.FOLDER_ID.getName(), newFolderId)
                .put(
                    Field.PARENT_ID.getName(),
                    Utils.getEncapsulatedId(
                        StorageType.getShort(storageType),
                        SimpleStorage.getAccountId(userId),
                        parentId)),
            mills);

        // update lastModified info for all folders in hierarchy
        updateLastModifiedInfo(segment, message, folder, userId);

        recordExecutionTime("cloneFolder", System.currentTimeMillis() - mills);
      } else {
        // check if file exists and user has access to it
        Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
        if (file == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (SimpleStorage.isDeleted(file)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
              HttpStatus.BAD_REQUEST);
          return;
        }
        if (!SimpleStorage.hasRights(
            file, SimpleStorage.makeUserId(userId), false, AccessType.canClone)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                  AccessType.canClone.label,
                  fileId),
              HttpStatus.FORBIDDEN);
          return;
        }
        folderId = SimpleStorage.getParent(file, userId);
        boolean clonedFromSharedFiles = false,
            clonedFromViewOnlyFolder = false,
            clonedToRoot = false;
        if (SimpleStorage.isSharedWithUser(file, userId)) {
          Item share = SimpleStorage.getItemShare(file, SimpleStorage.makeUserId(userId));
          String shareParentId = SimpleStorage.getParent(share, userId);
          if (shareParentId.equals(SHARED_FOLDER)) {
            clonedFromSharedFiles = true;
          } else if (!shareParentId.equals(ROOT)) {
            clonedFromViewOnlyFolder = isClonedFromViewOnlyFolder(shareParentId, userId);
          }
        } else if (SimpleStorage.checkOwner(file, SimpleStorage.makeUserId(userId))) {
          clonedFromViewOnlyFolder = isClonedFromViewOnlyFolder(folderId, userId);
        }
        if (clonedFromSharedFiles || clonedFromViewOnlyFolder) {
          folderId = ROOT;
          clonedToRoot = true;
        }
        // check the name
        if (!SimpleStorage.checkName(
            (folderId.equals(SHARED_FOLDER) || folderId.equals(ROOT))
                ? SimpleStorage.makeUserId(userId)
                : SimpleStorage.getParentStr(file),
            name,
            true,
            null)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "DuplicateName"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        Item folder = null;
        Set<String> editors = null, viewers = null;
        if (!folderId.equals(ROOT_FOLDER_ID)
            && !folderId.equals(ROOT)
            && !folderId.equals(SHARED_FOLDER)) {
          folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
          if (folder == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
                HttpStatus.NOT_FOUND);
            return;
          }
          if (SimpleStorage.isDeleted(folder)) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
                HttpStatus.BAD_REQUEST);
            return;
          }
          if (!SimpleStorage.hasRights(
              folder, SimpleStorage.makeUserId(userId), true, AccessType.canCreateFiles)) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "UserHasNoAccessWithTypeInFolderWithId"),
                    AccessType.canClone.label,
                    folderId),
                HttpStatus.FORBIDDEN);
            return;
          }
          if (SimpleStorage.isShared(folder)) {
            // if the folder is shared and our user != owner, add the owner of the folder as an
            // editor to this folder
            editors = SimpleStorage.getEditors(folder);
            viewers = SimpleStorage.getViewers(folder);

            if (!SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(userId))) {
              editors.add(SimpleStorage.getOwner(folder));
              editors.remove(userId);
            }
          }
        } else if (folderId.equals(ROOT_FOLDER_ID)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotCreateFileOrFolderHere"),
              HttpStatus.FORBIDDEN);
          return;
        }
        // create new file
        String newFileId =
            cloneFile(segment, message, folderId, userId, name, fileId, editors, viewers, true);
        if (newFileId != null) {
          if (doCopyComments) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName(Field.COPY_COMMENTS.getName())
                .run((Segment blockingSegment) -> {
                  boolean doIncludeResolvedComments =
                      message.body().getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
                  boolean doIncludeDeletedComments =
                      message.body().getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
                  copyFileComments(
                      blockingSegment,
                      fileId,
                      storageType,
                      newFileId,
                      doIncludeResolvedComments,
                      doIncludeDeletedComments);
                });
          }
          if (doCopyShare) {
            boolean finalClonedToRoot = clonedToRoot;
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName(Field.COPY_SHARE.getName())
                .run((Segment blockingSegment) -> {
                  copyShareData(message, file, userId, newFileId, finalClonedToRoot, false);
                });
          }

          // create share items for file from parent collaborators
          if (Objects.nonNull(folder)
              && SimpleStorage.isShared(folder)
              && (Objects.nonNull(editors) || Objects.nonNull(viewers))) {
            Item finalFolder = folder;
            Set<String> finalViewers = viewers;
            Set<String> finalEditors = editors;
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("shareOnCloneFile")
                .runWithoutSegment(() -> {
                  Item newFile = SimpleStorage.getItem(SimpleStorage.makeFileId(newFileId));
                  if (Objects.isNull(newFile)) {
                    return;
                  }
                  Set<String> collaborators = new HashSet<>();
                  if (Objects.nonNull(finalEditors)) {
                    collaborators.addAll(finalEditors);
                  }
                  if (Objects.nonNull(finalViewers)) {
                    collaborators.addAll(finalViewers);
                  }
                  for (String collaborator : collaborators) {
                    SimpleStorage.addItemShare(
                        newFile,
                        SimpleStorage.makeUserId(collaborator),
                        SimpleStorage.makeFolderId(
                            SimpleStorage.getItemId(finalFolder), collaborator));
                  }
                });
          }
          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      Utils.getEncapsulatedId(
                          storageType, SimpleStorage.getAccountId(userId), newFileId))
                  .put(
                      Field.PARENT_ID.getName(),
                      Utils.getEncapsulatedId(
                          StorageType.getShort(storageType),
                          SimpleStorage.getAccountId(userId),
                          folderId)),
              mills);
        }

        // update lastModified info for all folders in hierarchy
        updateLastModifiedInfo(segment, message, file, userId);

        recordExecutionTime("cloneFile", System.currentTimeMillis() - mills);
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      ParsedMessage parsedBuffer = MessageUtils.parseBuffer(message.body(), true);
      JsonObject jsonObject = parsedBuffer.getJsonObject();
      String name = jsonObject.getString(Field.NAME.getName());
      jsonObject.put(Field.FILE_NAME.getName(), name);
      String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
      String fileId = jsonObject.getString(Field.FILE_ID.getName());
      String userId = jsonObject.getString(Field.USER_ID.getName());
      String baseChangeId = jsonObject.getString(Field.BASE_CHANGE_ID.getName());
      boolean doCopyComments = jsonObject.getBoolean(Field.COPY_COMMENTS.getName(), false);
      String cloneFileId = jsonObject.getString(Field.CLONE_FILE_ID.getName());
      String xSessionId = jsonObject.getString(Field.X_SESSION_ID.getName());
      Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
      String responseName = null, modifier = null;
      String conflictingFileReason = jsonObject.getString(Field.CONFLICTING_FILE_REASON.getName());
      String userEmail = jsonObject.getString(Field.EMAIL.getName());
      String userName = jsonObject.getString(Field.F_NAME.getName());
      String userSurname = jsonObject.getString(Field.SURNAME.getName());
      String presignedUploadId = jsonObject.getString(Field.PRESIGNED_UPLOAD_ID.getName());
      boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
      boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
      boolean isFileUpdate = Utils.isStringNotNullOrEmpty(fileId);
      if (folderId == null && fileId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item existingFileItem = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (existingFileItem != null) {
        boolean isOwner =
            SimpleStorage.checkOwner(existingFileItem, SimpleStorage.makeUserId(userId));
        if (!isOwner
            && (!existingFileItem.hasAttribute("editors")
                || !existingFileItem.getList("editors").contains(userId))) {
          if (config.getProperties().getNewSessionWorkflow()) {
            if (!existingFileItem.hasAttribute("viewers")
                || !existingFileItem.getList("viewers").contains(userId)) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
              isConflictFile = true;
            }
            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.NO_EDITING_RIGHTS.name();
              isConflictFile = true;
            }
          } else {
            try {
              throw new KudoFileException(
                  Utils.getLocalizedString(message, "noEditingRightsToEditThisFile"),
                  KudoErrorCodes.NO_EDITING_RIGHTS,
                  HttpStatus.BAD_REQUEST,
                  "noEditingRights");
            } catch (KudoFileException kfe) {
              sendError(
                  segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
              return;
            }
          }
        }
        if (!Utils.isStringNotNullOrEmpty(folderId)) {
          folderId = SimpleStorage.getParent(existingFileItem, userId);
        }
        if (!Utils.isStringNotNullOrEmpty(name)) {
          name = SimpleStorage.getName(existingFileItem);
          jsonObject.put(Field.FILE_NAME.getName(), name);
        }
      } else if (fileId != null) {
        conflictingFileReason = XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
        isConflictFile = true;
        fileNotFound = true;
      }
      if (!fileNotFound && fileId != null) {
        String versionId = SimpleStorage.getLatestVersion(existingFileItem);
        if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
          isConflictFile =
              isConflictingChange(userId, fileId, storageType, versionId, baseChangeId);
          if (isConflictFile) {
            conflictingFileReason =
                XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
          }
        }
      }
      String oldName = name;
      boolean isSameFolder = true;
      if (isConflictFile) {
        if (!fileNotFound) {
          if (Objects.nonNull(existingFileItem)) {
            String modifierId = SimpleStorage.getLatestChanger(existingFileItem);
            modifier = Users.getUserNameById(modifierId);
          }
          Item folderItem =
              SimpleStorage.getItem(SimpleStorage.getParent(existingFileItem, userId));
          if (folderItem != null) {
            boolean isOwner =
                SimpleStorage.checkOwner(folderItem, SimpleStorage.makeUserId(userId));
            if (isOwner
                || (folderItem.hasAttribute("editors")
                    && folderItem.getList("editors").contains(userId))) {
              folderId = SimpleStorage.getParent(existingFileItem, userId);
            }
          }
        } else {
          if (oldName == null) {
            Item metaData = FileMetaData.getMetaData(fileId, storageType.name());
            if (Objects.nonNull(metaData)) {
              oldName = metaData.getString(Field.FILE_NAME.getName());
            }
            if (oldName == null) {
              oldName = unknownDrawingName;
            }
          }
        }
        name = getConflictingFileName(oldName);
        responseName = name;
        jsonObject.put(Field.FILE_NAME.getName(), name);
        if (!Utils.isStringNotNullOrEmpty(folderId)) {
          folderId = ROOT;
        }
        jsonObject.put(Field.FOLDER_ID.getName(), folderId);
        // Create a new conflicting file and fileId should be null to consider this as a new file
        jsonObject.remove(Field.FILE_ID.getName());
        if (folderId.equalsIgnoreCase(ROOT)) {
          isSameFolder = false;
        }
      }
      Item item =
          doSaveFile(message, jsonObject, parsedBuffer.getContentAsByteArray(), presignedUploadId);
      if (item != null) {
        if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
          JsonArray requestData = new JsonArray()
              .add(new JsonObject()
                  .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                  .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
                  .put(Field.EXT.getName(), Extensions.getExtension(name))
                  .put(Field.PRIORITY.getName(), true));
          log.info("[SF_THUMBNAILS] uploadFile: " + requestData.encodePrettily());
          eb_send(
              segment,
              ThumbnailsManager.address + ".create",
              jsonObject
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.IDS.getName(), requestData));
        }

        final String encapsulatedId = Utils.getEncapsulatedId(
            StorageType.getShort(storageType),
            SimpleStorage.getAccountId(userId),
            SimpleStorage.getItemId(item));

        if (fileId != null && isConflictFile) {
          final String oldEncapsulatedId = Utils.getEncapsulatedId(
              StorageType.getShort(storageType), SimpleStorage.getAccountId(userId), fileId);
          handleConflictingFile(
              segment,
              message,
              jsonObject,
              oldName,
              name,
              oldEncapsulatedId,
              encapsulatedId,
              xSessionId,
              userId,
              modifier,
              conflictingFileReason,
              fileSessionExpired,
              isSameFolder,
              AuthManager.ClientType.getClientType(jsonObject.getString(Field.DEVICE.getName())));
        }

        if (fileId == null || isConflictFile) {
          fileId = SimpleStorage.getItemId(item);
        }

        JsonObject response = new JsonObject()
            .put(Field.IS_CONFLICTED.getName(), isConflictFile)
            .put(Field.FILE_ID.getName(), encapsulatedId)
            .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
            .put(
                Field.THUMBNAIL_NAME.getName(),
                StorageType.getShort(storageType) + "_" + fileId
                    + "_" + SimpleStorage.getLatestVersion(item)
                    + ".png");

        if (isConflictFile) {
          if (Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            response.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
          }
          if (Utils.isStringNotNullOrEmpty(responseName)) {
            response.put(Field.NAME.getName(), responseName);
          }
          response.put(
              Field.FOLDER_ID.getName(),
              Utils.getEncapsulatedId(
                  StorageType.getShort(storageType), SimpleStorage.getAccountId(userId), folderId));
        }

        // for file save-as case
        if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
          String finalCloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
              .getString(Field.FILE_ID.getName());
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName(Field.COPY_COMMENTS_ON_SAVE_AS.getName())
              .run((Segment blockingSegment) -> {
                boolean doIncludeResolvedComments =
                    jsonObject.getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
                boolean doIncludeDeletedComments =
                    jsonObject.getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
                copyFileComments(
                    blockingSegment,
                    finalCloneFileId,
                    storageType,
                    SimpleStorage.getItemId(item),
                    doIncludeResolvedComments,
                    doIncludeDeletedComments);
              });
        }

        sendOK(segment, message, response, mills);

        if (isFileUpdate
            && (parsedBuffer.getContentAsByteArray() != null
                || Utils.isStringNotNullOrEmpty(presignedUploadId))) {
          eb_send(
              segment,
              WebSocketManager.address + ".newVersion",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                  .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
                  .put(Field.X_SESSION_ID.getName(), xSessionId)
                  .put(Field.EMAIL.getName(), userEmail)
                  .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
        }

        // update lastModified info for all folders in hierarchy
        updateLastModifiedInfo(segment, message, item, userId);
      }
      recordExecutionTime("UploadFile", System.currentTimeMillis() - mills);
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      ParsedMessage parsedBuffer = MessageUtils.parseBuffer(message.body(), true);
      JsonObject jsonObject = parsedBuffer.getJsonObject();

      String fileId = jsonObject.getString(Field.FILE_ID.getName());
      Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
      String userEmail = jsonObject.getString(Field.EMAIL.getName());
      String userName = jsonObject.getString(Field.F_NAME.getName());
      String userSurname = jsonObject.getString(Field.SURNAME.getName());
      String presignedUploadId = jsonObject.getString(Field.PRESIGNED_UPLOAD_ID.getName());
      if (fileId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item item =
          doSaveFile(message, jsonObject, parsedBuffer.getContentAsByteArray(), presignedUploadId);
      if (item != null) {
        String name = SimpleStorage.getName(item);
        if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
          JsonArray requestData = new JsonArray()
              .add(new JsonObject()
                  .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                  .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
                  .put(Field.EXT.getName(), Extensions.getExtension(name)));
          log.info("[SF_THUMBNAILS] uploadVersion: " + requestData.encodePrettily());
          eb_send(
              segment,
              ThumbnailsManager.address + ".create",
              jsonObject
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.IDS.getName(), requestData));
        }
        String userId = jsonObject.getString(Field.USER_ID.getName());
        VersionInfo newVersionInfo = getVersionInfo(fileId, item, true, userId);
        sendOK(segment, message, newVersionInfo.toJson(), mills);

        if (parsedBuffer.getContentAsByteArray() != null
            || Utils.isStringNotNullOrEmpty(presignedUploadId)) {
          eb_send(
              segment,
              WebSocketManager.address + ".newVersion",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                  .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(item))
                  .put(Field.FORCE.getName(), true)
                  .put(Field.EMAIL.getName(), userEmail)
                  .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
        }

        // update lastModified info for all folders in hierarchy
        updateLastModifiedInfo(segment, message, item, userId);
      }
      recordExecutionTime("UploadVersion", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private VersionInfo getVersionInfo(
      String fileId, Item versionItem, boolean isFile, String currentUserId) {
    String versionId;
    String hash = SimpleStorage.getS3Version(versionItem);
    long size = SimpleStorage.getFileSize(versionItem);
    long date;
    String modifierId;
    if (isFile) {
      modifierId = SimpleStorage.getLatestChanger(versionItem);
      versionId = SimpleStorage.getLatestVersion(versionItem);
      date = SimpleStorage.getLatestChangeTime(versionItem);
    } else {
      modifierId = SimpleStorage.getVersionCreator(versionItem);
      versionId = SimpleStorage.getFileVersionId(versionItem);
      date = SimpleStorage.getCreationDate(versionItem);
    }
    if (!Utils.isStringNotNullOrEmpty(modifierId)) {
      modifierId = SimpleStorage.getOwner(versionItem);
    }
    VersionModifier versionModifier = new VersionModifier();
    if (Utils.isStringNotNullOrEmpty(modifierId)) {
      Item user = Users.getUserById(modifierId);
      versionModifier.setCurrentUser(modifierId.equals(currentUserId));
      versionModifier.setEmail(
          Objects.nonNull(user) ? user.getString(Field.USER_EMAIL.getName()) : emptyString);
      versionModifier.setName(Objects.nonNull(user) ? MailUtil.getDisplayName(user) : emptyString);
      versionModifier.setId(modifierId);
    }
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(!isFile);
    versionPermissions.setCanDelete(!isFile);
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    VersionInfo versionInfo = new VersionInfo(versionId, date, null);
    versionInfo.setSize(size);
    versionInfo.setModifier(versionModifier);
    versionInfo.setPermissions(versionPermissions);
    versionInfo.setHash(hash);
    try {
      boolean encode = true;
      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, fileId, versionId);
      if (hash.equals(SAMPLE_VERSION)) {
        thumbnailName =
            ThumbnailsManager.getPublicFileThumbnailS3Path(SimpleStorage.getPath(versionItem));
        encode = false;
      }
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, encode));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (fileId == null || userId == null) {
      return;
    }

    Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
    if (file == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
          HttpStatus.NOT_FOUND);
      return;
    }
    if (SimpleStorage.isDeleted(file)) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (!SimpleStorage.hasRights(
        file, SimpleStorage.makeUserId(userId), false, AccessType.canManageVersions)) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
              AccessType.canManageVersions.label,
              fileId),
          HttpStatus.FORBIDDEN);
      return;
    }
    try {
      List<JsonObject> result = new ArrayList<>();
      result.add(getVersionInfo(fileId, file, true, userId).toJson());
      Iterator<Item> cursor =
          SimpleStorage.getFileVersions(SimpleStorage.makeFileId(fileId)).iterator();
      Item version;
      while (cursor.hasNext()) {
        version = cursor.next();
        result.add(getVersionInfo(fileId, version, false, userId).toJson());
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            if (Utils.isStringNotNullOrEmpty(SimpleStorage.getName(file))) {
              ext = Extensions.getExtension(SimpleStorage.getName(file));
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            result.forEach(revision -> requiredVersions.add(new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(Field.VERSION_ID.getName(), revision.getString(Field.ID.getName()))
                .put(Field.EXT.getName(), finalExt)));
            log.info("[SF_THUMBNAILS] doGetVersions: " + requiredVersions.encodePrettily());
            eb_send(
                blockingSegment,
                ThumbnailsManager.address + ".create",
                jsonObject.put(Field.IDS.getName(), requiredVersions));
          });
      result.sort(Comparator.comparing(o -> o.getLong("creationTime")));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
      recordExecutionTime("getVersion", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (fileId == null || userId == null) {
      return;
    }

    Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));

    if (file != null) {
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), false, AccessType.canManageVersions)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManageVersions.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(file)),
          mills);
    }
    sendOK(
        XRayManager.createSegment(operationGroup, message),
        message,
        new JsonObject().put(Field.VERSION_ID.getName(), emptyString),
        System.currentTimeMillis());
  }

  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    try {
      // check if file exists and user has access
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      S3Regional userS3 = getS3Regional(file, SimpleStorage.getOwner(file));
      if (returnDownloadUrl) {
        String downloadUrl =
            userS3.getDownloadUrl(SimpleStorage.getPath(file), SimpleStorage.getName(file), null);
        sendDownloadUrl(
            segment,
            message,
            downloadUrl,
            SimpleStorage.getFileSize(file),
            SimpleStorage.getLatestVersion(file),
            null,
            mills);
        return;
      }
      byte[] data = getChunk(userS3, SimpleStorage.getPath(file), null, null, null);

      finishGetFile(
          message,
          null,
          null,
          data,
          storageType,
          SimpleStorage.getName(file),
          SimpleStorage.getLatestVersion(file),
          downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getFileByToken", System.currentTimeMillis() - mills);
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject json = message.body();
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
      String verId = getRequiredString(segment, Field.VER_ID.getName(), message, json);
      String userId = json.getString(Field.USER_ID.getName());
      if (fileId == null || verId == null) {
        return;
      }
      // check if file exists and user has access to it
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), true, AccessType.canManageVersions)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManageVersions.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }

      // check if version exists (we ignore the latest version, cannot promote)
      Item version = SimpleStorage.getFileVersion(
          SimpleStorage.makeFileId(fileId), SimpleStorage.makeVersionId(verId));
      boolean exists = version != null;
      if (!exists) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), verId),
            HttpStatus.NOT_FOUND);
        return;
      }

      String versionId;

      if (!SimpleStorage.isSampleFile(version)) {
        String newVersion = getS3Regional(file, userId)
            .promoteVersion(SimpleStorage.getPath(version), SimpleStorage.getS3Version(version));
        if (newVersion == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "BodyOfTheVersionWithIdIsEmpty"), verId),
              HttpStatus.INTERNAL_SERVER_ERROR);
          return;
        }
        versionId = SimpleStorage.getFileVersionId(SimpleStorage.addFileVersion(
            file,
            null,
            null,
            newVersion,
            SimpleStorage.makeUserId(userId),
            SimpleStorage.getPath(version),
            SimpleStorage.getFileSize(version),
            ttl,
            true));
      } else {
        S3Regional userS3 = getS3Regional(null, userId);
        versionId = SimpleStorage.getFileVersionId(SimpleStorage.addFileVersion(
            file,
            userS3,
            bucket,
            SAMPLE_VERSION,
            SimpleStorage.makeUserId(userId),
            SimpleStorage.getPath(version),
            SimpleStorage.getFileSize(version),
            ttl,
            true));
      }
      sendSampleUsageWsNotification(
          segment, message, SimpleStorage.stripPrefix(file.getString(Field.OWNER.getName())), true);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), versionId),
          mills);
      recordExecutionTime("promoteVersion", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
      String versionId =
          getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
      String userId = message.body().getString(Field.USER_ID.getName());
      if (fileId == null || versionId == null) {
        return;
      }
      // check if file exists and user has access to it
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), true, AccessType.canManageVersions)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManageVersions.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }

      // check if version exists (so not the latest version)
      Item version = SimpleStorage.getFileVersion(
          SimpleStorage.makeFileId(fileId), SimpleStorage.makeVersionId(versionId));
      boolean exists = version != null;
      if (exists) {
        if (!SimpleStorage.isSampleFile(version)) {
          getS3Regional(file, userId)
              .deleteVersion(SimpleStorage.getPath(version), SimpleStorage.getS3Version(version));
        }
        SimpleStorage.deleteFileVersion(
            SimpleStorage.makeFileId(fileId), SimpleStorage.makeVersionId(versionId));
        sendOK(segment, message, mills);
      } else {
        if (versionId.equals(SimpleStorage.getLatestVersion(file))) {
          // TODO handle deleting current version
          // 1. check if we have older versions
          // 2. copy info from the previous version into our object
          // 3. update shares
          // 4. update quotas
          // 5. remove version object for the newly promoted version
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), versionId),
              HttpStatus.NOT_FOUND);
        } else {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), versionId),
              HttpStatus.NOT_FOUND);
        }
      }
      recordExecutionTime("deleteVersion", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  /**
   * Add collaborators to object item
   *
   * @param obj     - Item of object
   * @param editors - object editors
   * @param viewers - object viewers
   * @return Differences in collaborators
   */
  private HashMap<String, HashSet<String>> addItemCollaborators(
      Item obj, List<String> editors, List<String> viewers) {
    HashSet<String> fileEditors, fileViewers;
    if (SimpleStorage.getEditors(obj) != null) {
      fileEditors = new HashSet<>(SimpleStorage.getEditors(obj));
    } else {
      fileEditors = new HashSet<>();
    }
    if (SimpleStorage.getViewers(obj) != null) {
      fileViewers = new HashSet<>(SimpleStorage.getViewers(obj));
    } else {
      fileViewers = new HashSet<>();
    }

    if (editors != null) {
      fileEditors.addAll(editors);
    }
    if (viewers != null) {
      for (String str : viewers) {
        fileViewers.add(str);
        fileEditors.remove(str);
      }
    }
    fileEditors.remove((SimpleStorage.getOwner(obj)));
    fileViewers.remove((SimpleStorage.getOwner(obj)));
    fileViewers.removeAll(fileEditors);
    SimpleStorage.updateCollaborators(obj, fileEditors, fileViewers);
    HashMap<String, HashSet<String>> result = new HashMap<>();
    if (!fileEditors.isEmpty()) {
      result.put("editors", fileEditors);
    }
    if (!fileViewers.isEmpty()) {
      result.put("viewers", fileViewers);
    }
    return result;
  }

  private void shareContent(
      String folderId,
      String userId,
      List<String> editors,
      List<String> viewers,
      boolean isSharing) {
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, userId), null, null)
        .iterator();
    List<Item> itemList = IteratorUtils.toList(items);
    if (isSharing) {
      List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, false);
      itemList.addAll(itemShares);
    }
    for (Item item : itemList) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        if (!isSharing || !checkIfParentCollaboratorsHasAccess(item, folderId)) {
          continue;
        }
      }
      Item originalItem;
      if (SimpleStorage.isItemShare(item)) {
        originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
        if (!isSharing || Objects.isNull(originalItem)) {
          continue;
        }
      } else {
        originalItem = item;
      }
      List<String> parentEditors = null, parentViewers = null;
      if (Objects.nonNull(editors)) {
        parentEditors = new ArrayList<>(editors);
      }
      if (Objects.nonNull(viewers)) {
        parentViewers = new ArrayList<>(viewers);
      }
      if (!SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId))) {
        if (Utils.isListNotNullOrEmpty(parentEditors)) {
          parentEditors.remove(SimpleStorage.getOwner(item));
        }
        if (Utils.isListNotNullOrEmpty(parentViewers)) {
          parentViewers.remove(SimpleStorage.getOwner(item));
        }
      }
      addItemCollaborators(originalItem, parentEditors, parentViewers);
      if (SimpleStorage.isFolder(item)) {
        shareContent(SimpleStorage.getItemId(item), userId, editors, viewers, isSharing);
      }
    }
  }

  private void shareFolderItems(
      String folderId, String sharedUserId, String currentUserId, boolean isSharing) {
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, currentUserId), null, null)
        .iterator();
    List<Item> itemList = IteratorUtils.toList(items);
    if (isSharing) {
      List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, currentUserId, false);
      itemList.addAll(itemShares);
    }
    for (Item item : itemList) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, currentUserId)) {
        if (!isSharing || !checkIfParentCollaboratorsHasAccess(item, folderId)) {
          continue;
        }
      }
      Item originalItem;
      if (SimpleStorage.isItemShare(item)) {
        originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
        if (!isSharing || Objects.isNull(originalItem)) {
          continue;
        }
      } else {
        originalItem = item;
      }
      if (!SimpleStorage.checkOwner(originalItem, SimpleStorage.makeUserId(sharedUserId))) {
        SimpleStorage.addItemShare(
            originalItem,
            SimpleStorage.makeUserId(sharedUserId),
            SimpleStorage.makeFolderId(folderId, sharedUserId));
      }
      if (SimpleStorage.isFolder(item)) {
        shareFolderItems(SimpleStorage.getItemId(item), sharedUserId, currentUserId, isSharing);
      }
    }
  }

  private void addOrDeleteFolderPermissionsForSharedUsers(
      String folderId,
      String userId,
      String sharedUserId,
      Role role,
      List<?> customPermissions,
      boolean isAdd) {
    for (Item item :
        SimpleStorage.getFolderItems(SimpleStorage.makeFolderId(folderId, userId), null, null)) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      if (isAdd) {
        PermissionsDao.addOrUpdateObjectPermissionsForUser(
            sharedUserId,
            SimpleStorage.getItemId(item),
            (SimpleStorage.isFolder(item) ? ObjectType.FOLDER : ObjectType.FILE),
            role,
            customPermissions);
      } else {
        PermissionsDao.deleteCustomPermissions(sharedUserId, SimpleStorage.getItemId(item));
      }
      if (SimpleStorage.isFolder(item)) {
        addOrDeleteFolderPermissionsForSharedUsers(
            SimpleStorage.getItemId(item), userId, sharedUserId, role, customPermissions, isAdd);
      }
    }
  }

  private void removeItemCollaborators(Item object, Set<String> deS) {
    Set<String> fileEditors = SimpleStorage.getEditors(object) != null
        ? new HashSet<>(SimpleStorage.getEditors(object))
        : new HashSet<>();
    Set<String> fileViewers = SimpleStorage.getViewers(object) != null
        ? new HashSet<>(SimpleStorage.getViewers(object))
        : new HashSet<>();
    fileEditors.removeAll(deS);
    fileViewers.removeAll(deS);
    SimpleStorage.updateCollaborators(object, fileEditors, fileViewers);
  }

  private void deShareContent(String folderId, String userId, Set<String> deShare) {
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, userId), null, null)
        .iterator();
    List<Item> itemList = IteratorUtils.toList(items);
    List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, false);
    itemList.addAll(itemShares);
    for (Item item : itemList) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        if (!checkIfParentCollaboratorsHasAccess(item, folderId)) {
          continue;
        }
      }
      Item originalItem;
      if (SimpleStorage.isItemShare(item)) {
        originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
        if (Objects.isNull(originalItem)) {
          continue;
        }
      } else {
        originalItem = item;
      }
      removeItemCollaborators(originalItem, deShare);
      if (SimpleStorage.isFolder(item)) {
        deShareContent(SimpleStorage.getItemId(item), userId, deShare);
      }
    }
  }

  private void unShareFolderItems(
      String folderId, String userId, String sharedUserId, Set<String> folderCollaborators) {
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, userId), null, null)
        .iterator();
    List<Item> itemList = IteratorUtils.toList(items);
    List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, false);
    itemList.addAll(itemShares);
    for (Item item : itemList) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        if (!checkIfParentCollaboratorsHasAccess(item, folderId)) {
          continue;
        }
      }
      boolean isItemShare = SimpleStorage.isItemShare(item);
      Item originalItem;
      if (isItemShare) {
        originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
        if (Objects.isNull(originalItem)) {
          continue;
        }
      } else {
        originalItem = item;
      }
      if (SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(sharedUserId))) {
        Set<String> itemCollaborators = getAllObjectCollaborators(originalItem);
        List<String> commonSharedUsers = new ArrayList<>(folderCollaborators);
        commonSharedUsers.retainAll(itemCollaborators);
        Item parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, null));
        if (Objects.isNull(parent)) {
          continue;
        }
        SimpleStorage.removeSharedUsersAndUpdateItem(
            item, new JsonArray(commonSharedUsers), !isItemShare);
      } else {
        SimpleStorage.removeSharedUsersAndUpdateItem(
            item, new JsonArray().add(sharedUserId), !isItemShare);
      }
      if (SimpleStorage.isFolder(item)) {
        unShareFolderItems(
            SimpleStorage.getItemId(item), userId, sharedUserId, folderCollaborators);
      }
    }
  }

  public void doShare(Message<JsonObject> message) {
    doShare(message, true);
  }

  public void doShare(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject json = message.body();
      boolean isFolder = json.getBoolean(Field.IS_FOLDER.getName());
      String id = json.getString(Field.FOLDER_ID.getName());
      String userId = json.getString(Field.USER_ID.getName());
      JsonArray editor = json.getJsonArray(Field.EDITOR.getName());
      JsonArray viewer = json.getJsonArray(Field.VIEWER.getName());
      Item object = SimpleStorage.getItem(
          isFolder ? SimpleStorage.makeFolderId(id, userId) : SimpleStorage.makeFileId(id));
      if (object == null) {
        if (reply) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                  Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                  id),
              HttpStatus.NOT_FOUND);
        }
        return;
      }
      if (SimpleStorage.isDeleted(object)) {
        if (reply) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                  Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                  id),
              HttpStatus.BAD_REQUEST);
        }
        return;
      }

      boolean hasAccess = false;
      if (isFolder) {
        Set<String> currentEditors = SimpleStorage.getEditors(object);
        if (Objects.nonNull(currentEditors)) {
          // self changing from editor to viewer
          hasAccess = currentEditors.contains(userId);
        }
      }

      // check if user is owner or editor
      if (!hasAccess
          && !SimpleStorage.hasRights(
              object, SimpleStorage.makeUserId(userId), true, AccessType.canManagePermissions)) {
        if (reply) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(
                      message,
                      isFolder
                          ? "UserHasNoAccessWithTypeForFolderWithId"
                          : "UserHasNoAccessWithTypeForFileWithId"),
                  AccessType.canManagePermissions.label,
                  id),
              HttpStatus.FORBIDDEN);
        }
        return;
      }

      ArrayList<String> editorId = new ArrayList<>();
      ArrayList<String> viewerId = new ArrayList<>();

      Set<String> nonExistentEmails = new HashSet<>();
      boolean isSharedWithMyself = false;
      if (editor != null) {
        for (Object str : editor.getList()) {
          Iterator<Item> users = Users.getUserByEmail((String) str);
          String targetUserId = null;
          if (users.hasNext()) {
            Item user = users.next();
            targetUserId = user.getString(Field.SK.getName());
          } else {
            Item user = Users.getUserById((String) str);
            if (user != null) {
              targetUserId = user.getString(Field.SK.getName());
            }
          }
          if (targetUserId != null) {
            if (!targetUserId.equals(userId)) {
              editorId.add(targetUserId);
            } else {
              isSharedWithMyself = true;
            }
          } else {
            nonExistentEmails.add((String) str);
          }
        }
      }
      if (viewer != null) {
        for (Object str : viewer.getList()) {
          Iterator<Item> users = Users.getUserByEmail((String) str);
          String targetUserId = null;

          if (users.hasNext()) {
            Item user = users.next();
            targetUserId = user.getString(Field.SK.getName());
          } else {
            Item user = Users.getUserById((String) str);
            if (user != null) {
              targetUserId = user.getString(Field.SK.getName());
            }
          }
          if (targetUserId != null) {
            if (targetUserId.equals(userId)
                && !SimpleStorage.isCollaboratorShareDowngrade(targetUserId, object)) {
              isSharedWithMyself = true;
            } else {
              viewerId.add(targetUserId);
            }
          } else {
            nonExistentEmails.add((String) str);
          }
        }
      }

      if (isSharedWithMyself
          && (((editor == null ? 0 : editor.size()) + (viewer == null ? 0 : viewer.size())) == 1)) {
        if (isFolder) {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "CantShareFolderWithYourself"),
                HttpStatus.BAD_REQUEST);
          }
        } else {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "CantShareFileWithYourself"),
                HttpStatus.BAD_REQUEST);
          }
        }
        return;
      }
      HashMap<String, HashSet<String>> differences =
          addItemCollaborators(object, editorId, viewerId);
      // share content
      if (isFolder) {
        shareContent(id, userId, editorId, viewerId, true);
      }

      eb_send(
          segment,
          MailUtil.address + ".notifyShare",
          new JsonObject()
              .put(Field.OWNER_ID.getName(), userId)
              .put(Field.NAME.getName(), SimpleStorage.getName(object))
              .put(Field.STORAGE_TYPE.getName(), storageType)
              .put("editors", editor)
              .put("viewers", viewer)
              .put(Field.TYPE.getName(), isFolder ? 0 : 1)
              .put(Field.ID.getName(), id));

      List<String> collaborators_changes = new ArrayList<>();
      if (!differences.isEmpty()) {
        if (differences.containsKey("editors")) {
          collaborators_changes.addAll(differences.get("editors"));
        }
        if (differences.containsKey("viewers")) {
          collaborators_changes.addAll(differences.get("viewers"));
        }
      }
      List<String> collaborators_shares = new ArrayList<>(collaborators_changes);
      // check the parent folders to see if collaborators already have access. In that case,
      // there is no need to add another item to their root folder.
      if (!collaborators_changes.isEmpty()) {
        String parentId = SimpleStorage.getParent(object, userId);
        if (!parentId.equals(ROOT)
            && !parentId.equals(ROOT_FOLDER_ID)
            && !parentId.equals(SHARED_FOLDER)) {
          Item parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
          if (Objects.nonNull(parent)) {
            Set<String> parentCollaborators = getAllObjectCollaborators(parent);
            collaborators_shares.removeAll(parentCollaborators);
            collaborators_shares.remove(SimpleStorage.getOwner(parent));
            for (String collaborator : collaborators_changes) {
              // share item to the shared user under the same parent
              if (parentCollaborators.contains(collaborator)
                  || SimpleStorage.getOwner(parent).equals(collaborator)) {
                SimpleStorage.addItemShare(
                    object,
                    SimpleStorage.makeUserId(collaborator),
                    SimpleStorage.makeFolderId(parentId, collaborator));
                if (isFolder) {
                  shareFolderItems(id, collaborator, userId, true);
                }
              }
            }
          }
        }
        for (String collaborator : collaborators_shares) {
          SimpleStorage.addItemShare(
              object,
              SimpleStorage.makeUserId(collaborator),
              SimpleStorage.makeFolderId(SHARED_FOLDER, collaborator));
          if (isFolder) {
            shareFolderItems(id, collaborator, userId, true);
          }
        }
      }

      JsonArray collaboratorsToUpdate = new JsonArray();
      if (Objects.nonNull(editor) && !editor.isEmpty()) {
        collaboratorsToUpdate.addAll(editor);
      }
      if (Objects.nonNull(viewer) && !viewer.isEmpty()) {
        collaboratorsToUpdate.addAll(viewer);
      }
      JsonArray usersWithCustomPermissions = new JsonArray();
      // adding custom permissions for the object
      JsonArray customPermissions = json.getJsonArray(Field.CUSTOM_PERMISSIONS.getName());
      if (Objects.nonNull(customPermissions) && !customPermissions.isEmpty()) {
        collaboratorsToUpdate.forEach(user -> {
          Optional<Object> optional = customPermissions.stream()
              .filter(obj -> ((JsonObject) obj).containsKey((String) user))
              .findAny();
          if (optional.isPresent()) {
            Iterator<Item> sharedUserIterator = Users.getUserByEmail((String) user);
            if (sharedUserIterator.hasNext()) {
              Role role = Role.CUSTOM;
              if (Objects.nonNull(editor) && editor.contains(user)) {
                role = Role.EDITOR;
              } else if (Objects.nonNull(viewer) && viewer.contains(user)) {
                role = Role.VIEWER;
              }
              Item sharedUser = sharedUserIterator.next();
              JsonArray permissions = ((JsonObject) optional.get()).getJsonArray((String) user);
              PermissionsDao.addOrUpdateObjectPermissionsForUser(
                  sharedUser.getString(Dataplane.sk),
                  id,
                  (isFolder ? ObjectType.FOLDER : ObjectType.FILE),
                  role,
                  permissions.getList());
              if (isFolder) {
                addOrDeleteFolderPermissionsForSharedUsers(
                    id,
                    userId,
                    sharedUser.getString(Dataplane.sk),
                    role,
                    permissions.getList(),
                    true);
              }
              usersWithCustomPermissions.add(user);
            }
          }
        });
      }
      // to check and delete the custom permissions if the corresponding user is re-shared with
      // normal rights (not custom)
      if (collaboratorsToUpdate.size() != usersWithCustomPermissions.size()) {
        collaboratorsToUpdate.forEach(user -> {
          if (!usersWithCustomPermissions.contains(user)) {
            Iterator<Item> sharedUserIterator = Users.getUserByEmail((String) user);
            if (sharedUserIterator.hasNext()) {
              Item sharedUser = sharedUserIterator.next();
              PermissionsDao.deleteCustomPermissions(sharedUser.getString(Dataplane.sk), id);
              if (isFolder) {
                addOrDeleteFolderPermissionsForSharedUsers(
                    id, userId, sharedUser.getString(Dataplane.sk), null, null, false);
              }
            }
          }
        });
      }
      // this shows what collaborators were affected
      if (reply) {
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.COLLABORATORS.getName(), new JsonArray(collaborators_changes))
                .put("nonExistentEmails", new JsonArray(new ArrayList<>(nonExistentEmails))),
            mills);
      }
      recordExecutionTime("shareObject", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      if (reply) {
        sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
      }
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject json = message.body();
      boolean isFolder = json.getBoolean(Field.IS_FOLDER.getName());
      String id = json.getString(Field.FOLDER_ID.getName());
      String userId = json.getString(Field.USER_ID.getName());

      // deShare structure: [{userId: userId || email, email: email}, ...]
      JsonArray deShare = json.getJsonArray(Field.DE_SHARE.getName());
      boolean selfDeShare = deShare.size() == 1
          && deShare.getJsonObject(0).getString(Field.USER_ID.getName()).equals(userId);

      Item object = SimpleStorage.getItem(
          isFolder ? SimpleStorage.makeFolderId(id, userId) : SimpleStorage.makeFileId(id));
      if (object == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                id),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (SimpleStorage.isDeleted(object)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdIsInTrash"),
                Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                id),
            HttpStatus.BAD_REQUEST);
        return;
      }

      // check if user is owner or editor
      if (!selfDeShare
          && !SimpleStorage.hasRights(
              object, SimpleStorage.makeUserId(userId), true, AccessType.canManagePermissions)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(
                    message,
                    isFolder
                        ? "UserHasNoAccessWithTypeForFolderWithId"
                        : "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManagePermissions.label,
                id),
            HttpStatus.FORBIDDEN);
        return;
      }

      Set<String> objectCollaborators = getAllObjectCollaborators(object);
      HashSet<String> newCollaborators = new HashSet<>(objectCollaborators);

      Set<String> deS = new HashSet<>();

      deShare.forEach(userObject -> {
        Item user = Users.getUserById(((JsonObject) userObject).getString(Field.USER_ID.getName()));
        if (user != null) {
          deS.add((user.getString(Field.SK.getName())));
          newCollaborators.remove((user.getString(Field.SK.getName())));
        }
      });
      if (!selfDeShare) {
        deS.remove(SimpleStorage.makeUserId(userId));
      }

      removeItemCollaborators(object, deS);

      if (!selfDeShare) {
        List<String> users = new ArrayList<>();
        deShare.forEach(u -> users.add(((JsonObject) u).getString(Field.USER_ID.getName())));
        eb_send(
            segment,
            MailUtil.address + ".notifyDeShare",
            new JsonObject()
                .put(Field.OWNER_ID.getName(), userId)
                .put(Field.NAME.getName(), SimpleStorage.getName(object))
                .put(Field.USERS.getName(), new JsonArray(users))
                .put(Field.TYPE.getName(), isFolder ? 0 : 1));
      }

      List<String> collaborators = new ArrayList<>(objectCollaborators);
      collaborators.removeAll(newCollaborators);
      objectCollaborators.add(SimpleStorage.getOwner(object));
      for (String collaborator : collaborators) {
        SimpleStorage.removeItemShare(object, SimpleStorage.makeUserId(collaborator));
        if (isFolder) {
          unShareFolderItems(
              SimpleStorage.getItemId(object), userId, collaborator, objectCollaborators);
        }
      }

      // deShare content
      if (isFolder) {
        deShareContent(id, userId, deS);
      }

      removeCustomPermissions(
          segment,
          message,
          Sets.newHashSet(collaborators),
          SimpleStorage.isFolder(object),
          object,
          userId);

      // this shows what collaborators were affected
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.COLLABORATORS.getName(), new JsonArray(collaborators))
              .put(Field.USERNAME.getName(), Users.getUserNameById(userId)),
          mills);
      recordExecutionTime("deShareObject", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      String fileId = message.body().getString(Field.FILE_ID.getName());
      String folderId = message.body().getString(Field.FOLDER_ID.getName());
      if (fileId == null && folderId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (fileId != null) {
        untrashFile(segment, message);
      } else {
        untrashFolder(segment, message);
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private void untrashFile(Entity segment, Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    String fileId = message.body().getString(Field.FILE_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    // check if file exists, marked as deleted and owned by user
    Item file = hasTrashRights(segment, message, fileId, true, false, userId);
    if (file == null) {
      return;
    }
    // check parent's state
    String parentId = SimpleStorage.getParent(file, userId);
    Item folder = null;
    if (!ROOT_FOLDER_ID.equals(parentId)
        && !ROOT.equals(parentId)
        && !SHARED_FOLDER.equals(parentId)) {
      folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
      if (folder == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "ParentFolderWithIdDoesNotExist"), parentId),
            HttpStatus.NOT_FOUND);
        return;
      }
    }
    // untrash file if parent folder is not deleted (or root)
    // otherwise try to untrash folder tree
    if (ROOT.equals(parentId) || (Objects.nonNull(folder) && !SimpleStorage.isDeleted(folder))) {
      SimpleStorage.undeleteAndResetParent(file);
      if (Utils.isStringNotNullOrEmpty(SimpleStorage.getDeleteMarker(file))) {
        getS3Regional(file, userId)
            .deleteVersion(SimpleStorage.getPath(file), SimpleStorage.getDeleteMarker(file));
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("updateRecentFile")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(file))
                    .put(Field.ACCESSIBLE.getName(), true)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name()));
          });
      sendOK(segment, message, mills);
    } else {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "CouldNotUntrashFolderTreeForFolderWithId"),
              parentId),
          HttpStatus.BAD_REQUEST);
    }
    recordExecutionTime("untrashFile", System.currentTimeMillis() - mills);
  }

  private void untrashFolder(Entity segment, Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());

    // check if folder exists, marked as deleted and owned by user
    Item folder = hasTrashRights(segment, message, folderId, false, false, userId);
    if (folder == null) {
      return;
    }

    log.info(String.format("restoring folder %s...", folderId));
    // check parent's state
    String parentId = SimpleStorage.getParent(folder, userId);
    Item parentFolder = null;
    if (!ROOT.equals(parentId)) {
      parentFolder = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
      if (parentFolder == null) {
        log.error(String.format("parent folder with id %s doesn't exist", parentId));
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "ParentFolderWithIdDoesNotExist"), parentId),
            HttpStatus.NOT_FOUND);
        return;
      }
    }
    // untrash folder if parent is not deleted or root
    // otherwise try to untrash folder tree
    if (ROOT.equals(parentId) || !SimpleStorage.isDeleted(parentFolder)) {
      SimpleStorage.undeleteAndResetParent(folder);
      // untrash folder and it's content
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("unTrashFolderContent")
          .run((Segment blockingSegment) -> {
            untrashFolderContent(blockingSegment, folderId, userId);
          });
      sendOK(segment, message, mills);
    } else {
      log.error(String.format(
          "couldn't restore folder with id %s and parentId %s which is %s deleted",
          folderId, parentId, SimpleStorage.isDeleted(parentFolder) ? emptyString : "not"));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "CouldNotUntrashFolderTreeForFolderWithId"),
              parentId),
          HttpStatus.BAD_REQUEST);
    }
    recordExecutionTime("untrashFolder", System.currentTimeMillis() - mills);
  }

  private void untrashFolderContent(Entity segment, String folderId, String userId) {
    Entity subsegment =
        XRayManager.createSubSegment(operationGroup, segment, "untrashFolderContent");
    try {
      Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
      if (Objects.nonNull(folder)) {
        log.info(String.format("Restoring content of folder %s...", folderId));
        Iterator<Item> items = SimpleStorage.getDeletedFolderItems(
                SimpleStorage.makeFolderId(folderId, userId))
            .iterator();
        List<Item> itemList = IteratorUtils.toList(items);
        List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, true);
        itemList.addAll(itemShares);
        for (Item item : itemList) {
          if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
            if (!checkIfParentCollaboratorsHasAccess(item, folderId)) {
              continue;
            }
          }
          if (SimpleStorage.isFile(item)) {
            String itemUser;
            if (SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId))) {
              itemUser = userId;
              String deleteMarker = SimpleStorage.getDeleteMarker(item);
              if (Utils.isStringNotNullOrEmpty(deleteMarker)) {
                getS3Regional(item, userId)
                    .deleteVersion(
                        SimpleStorage.getPath(item), SimpleStorage.getDeleteMarker(item));
              }
            } else {
              itemUser = SimpleStorage.stripPrefix(item.getString(Field.SK.getName()));
              log.warn("UnTrashFolderContent : Item does not belong to owner " + item);
            }
            eb_send(
                subsegment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), item.getString(Field.ID.getName()))
                    .put(Field.ACCESSIBLE.getName(), true)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name())
                    .put(Field.USER_ID.getName(), itemUser));
          }
          if (SimpleStorage.isItemShare(item)) {
            SimpleStorage.unhideItemShare(item);
          } else {
            SimpleStorage.undelete(item);
          }
          if (SimpleStorage.isFolder(item)) {
            untrashFolderContent(segment, SimpleStorage.getItemId(item), userId);
          }
        }
      } else {
        log.error(
            "Folder with Id " + folderId + " does not exist or user is not allowed to untrash it");
      }
    } catch (Exception e) {
      log.error("Error occurred in untrashing folder with id " + folderId + " : "
          + e.getLocalizedMessage());
    }
    XRayManager.endSegment(subsegment);
  }

  public void doGetInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName(), false);
    String device =
        jsonObject.getString(Field.DEVICE.getName(), AuthManager.ClientType.BROWSER.name());
    String id = folderId != null ? folderId : fileId;
    boolean isFolder = folderId != null;
    boolean isTouch = device.equals(AuthManager.ClientType.TOUCH.name());
    try {
      if (isFolder) {
        if (isTouch && id.equals(ROOT_FOLDER_ID)) {
          sendOK(
              segment,
              message,
              getRootFolderInfo(
                  storageType,
                  userId,
                  new ObjectPermissions()
                      .setBatchTo(
                          List.of(
                              AccessType.canMoveFrom,
                              AccessType.canMoveTo,
                              AccessType.canCreateFiles,
                              AccessType.canCreateFolders),
                          true)),
              mills);

          return;
        }

        if (id.equals(ROOT_FOLDER_ID)) {
          sendOK(
              segment,
              message,
              getRootFolderInfo(storageType, userId, new ObjectPermissions().setAllTo(false)),
              mills);
          return;
        }

        if (id.equals(ROOT)) {
          sendOK(segment, message, getMyFilesFolderInfo(userId), mills);
          return;
        }

        if (id.equals(SHARED_FOLDER)) {
          sendOK(segment, message, getSharedFolderInfo(storageType, userId, SHARED_FILES), mills);
          return;
        }
      }
      // check if object exists and user has permission
      Item obj = SimpleStorage.getItem(
          isFolder ? SimpleStorage.makeFolderId(id, userId) : SimpleStorage.makeFileId(id));
      if (obj == null) {
        super.deleteSubscriptionForUnavailableFile(id, userId);
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                id),
            HttpStatus.NOT_FOUND);
        return;
      }
      boolean full = true;
      if (jsonObject.containsKey(Field.FULL.getName())
          && jsonObject.getString(Field.FULL.getName()) != null) {
        full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
      }
      if (!SimpleStorage.hasRights(obj, SimpleStorage.makeUserId(userId), false, null)) {
        super.deleteSubscriptionForUnavailableFile(id, userId);
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(
                    message,
                    isFolder ? "UserHasNoAccessToFolderWithId" : "UserHasNoAccessToFileWithId"),
                (isFolder) ? folderId : fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      JsonObject result = isFolder
          ? getFolderJson(segment, message, obj, userId, full)
          : getFileJson(obj, userId, isAdmin, full, false, false);
      if (isTouch) {
        String parentId = result.getString(Field.FOLDER_ID.getName());
        if (isFolder) {
          parentId = result.getString(Field.PARENT.getName());
        }
        String pureParentId = Utils.parseItemId(parentId).getString(Field.ID.getName());
        if (pureParentId.equals(ROOT) || pureParentId.equals(SHARED_FOLDER)) {
          parentId = parentId.replace(ROOT, ROOT_FOLDER_ID);
          parentId = parentId.replace(SHARED_FOLDER, ROOT_FOLDER_ID);
          result.put(isFolder ? Field.PARENT.getName() : Field.FOLDER_ID.getName(), parentId);
        }
      }
      sendOK(segment, message, result, mills);
      recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      if (!isFolder) {
        super.deleteSubscriptionForUnavailableFile(id, userId);
      }
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      String fileId = message.body().getString(Field.FILE_ID.getName());
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (Objects.nonNull(file)) {
        JsonObject requestData = message
            .body()
            .put(Field.STORAGE_TYPE.getName(), storageType.name())
            .put(
                Field.IDS.getName(),
                new JsonArray()
                    .add(new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(file))
                        .put(
                            Field.EXT.getName(),
                            Extensions.getExtension(SimpleStorage.getName(file)))))
            .put(Field.FORCE.getName(), true);
        log.info("[SF_THUMBNAILS] doGetThumbnail: " + requestData.encodePrettily());
        eb_send(segment, ThumbnailsManager.address + ".create", requestData);
        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType), fileId, SimpleStorage.getLatestVersion(file));
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(
                    "thumbnailStatus",
                    ThumbnailsManager.getThumbnailStatus(
                        SimpleStorage.getItemId(file),
                        storageType.name(),
                        SimpleStorage.getLatestVersion(file),
                        true,
                        true))
                .put(
                    Field.THUMBNAIL.getName(),
                    ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
                .put(
                    Field.GEOMDATA.getName(),
                    ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
            mills);
      } else {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetInfoByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject json = message.body();
      String fileId = json.getString(Field.FILE_ID.getName());
      String token = json.getString(Field.TOKEN.getName());
      Boolean export = json.getBoolean(Field.EXPORT.getName());
      String creatorId = json.getString(Field.CREATOR_ID.getName());
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(json.getString(Field.OWNER_ID.getName())), true, null)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessToFileWithId"), fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (file == null) {
        if (Utils.isStringNotNullOrEmpty(json.getString(Field.USER_ID.getName()))) {
          super.deleteSubscriptionForUnavailableFile(
              fileId, json.getString(Field.USER_ID.getName()));
        }
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      JsonObject result = new JsonObject();
      String versionId = SimpleStorage.getLatestVersion(file);
      String changerId = SimpleStorage.getLatestChanger(file);
      String changer = Users.getUserNameById(changerId);
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), fileId, versionId);
      result
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(
                  StorageType.getShort(storageType),
                  SimpleStorage.getAccountId(SimpleStorage.getOwner(file)),
                  fileId))
          .put(Field.WS_ID.getName(), fileId)
          .put(
              Field.SHARE.getName(),
              new JsonObject()
                  .put(Field.VIEWER.getName(), new JsonArray())
                  .put(Field.EDITOR.getName(), new JsonArray()))
          .put(Field.VIEW_ONLY.getName(), true)
          .put(Field.PUBLIC.getName(), true)
          .put(Field.IS_OWNER.getName(), false)
          .put(Field.DELETED.getName(), SimpleStorage.isDeleted(file))
          .put(
              Field.LINK.getName(),
              config.getProperties().getUrl() + "file/" + fileId + "?token=" + token)
          .put(Field.FILE_NAME.getName(), SimpleStorage.getName(file))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(Field.CREATOR_ID.getName(), creatorId)
          .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
          .put(Field.EXPORT.getName(), export)
          .put(Field.UPDATE_DATE.getName(), SimpleStorage.getLatestChangeTime(file))
          .put(Field.CHANGER.getName(), changer)
          .put(Field.CHANGER_ID.getName(), changerId);
      sendOK(segment, message, result, mills);
      recordExecutionTime("getObjectInfoByToken", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    doGetFolderPath(message, true);
  }

  public void doGetFolderPath(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject json = message.body();
      String id = json.getString(Field.ID.getName());
      String userId = json.getString(Field.USER_ID.getName());
      String parentId;
      if (!reply && !Utils.isStringNotNullOrEmpty(id)) {
        return;
      }
      JsonArray result = new JsonArray();
      if (!id.equals(ROOT_FOLDER_ID) && !id.equals(ROOT) && !id.equals(SHARED_FOLDER)) {
        // check if folder exists and user has access
        Item obj = SimpleStorage.getItem(SimpleStorage.makeFolderId(id, userId));
        if (obj == null) {
          if (reply) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                    Utils.getLocalizedString(message, "Folder"),
                    id),
                HttpStatus.NOT_FOUND);
          }
          return;
        }
        if (!SimpleStorage.hasRights(obj, SimpleStorage.makeUserId(userId), false, null)) {
          if (reply) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "UserHasNoAccessToFolderWithId"), id),
                HttpStatus.FORBIDDEN);
          }
          return;
        }
        result.add(new JsonObject()
            .put(Field.NAME.getName(), SimpleStorage.getName(obj))
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, SimpleStorage.getAccountId(userId), id))
            .put(Field.VIEW_ONLY.getName(), false));

        parentId = SimpleStorage.getParent(obj, userId);
      } else {
        parentId = id;
      }
      boolean isTouch = AuthManager.ClientType.TOUCH
          .name()
          .equals(message
              .body()
              .getString(Field.DEVICE.getName(), AuthManager.ClientType.BROWSER.name()));
      if (!isTouch && !parentId.equals(ROOT_FOLDER_ID)) {
        while (true) {
          if (parentId.equals(ROOT)) {
            result.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, SimpleStorage.getAccountId(userId), ROOT))
                .put(Field.NAME.getName(), MY_FILES)
                .put(Field.VIEW_ONLY.getName(), false));
            break;
          } else if (parentId.equals(SHARED_FOLDER)) {
            result.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType, SimpleStorage.getAccountId(userId), SHARED_FOLDER))
                .put(Field.NAME.getName(), SHARED_FILES)
                .put(Field.VIEW_ONLY.getName(), true));
            break;
          }
          Item parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
          if (parent == null) {
            if (reply) {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "OneOfTheSubFolderIsDeletedInPath"),
                  HttpStatus.BAD_REQUEST);
            }
            return;
          }
          result.add(new JsonObject()
              .put(Field.NAME.getName(), SimpleStorage.getName(parent))
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType, SimpleStorage.getAccountId(userId), parentId))
              .put(Field.VIEW_ONLY.getName(), false));

          parentId = SimpleStorage.getParent(parent, userId);
        }
      }
      result.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(
                  storageType, SimpleStorage.getAccountId(userId), ROOT_FOLDER_ID))
          .put(Field.NAME.getName(), "~")
          .put(Field.VIEW_ONLY.getName(), false));
      if (reply) {
        sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
      } else {
        message.body().put("folderPathValid", true);
      }
      recordExecutionTime("getFolderPath", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      if (reply) {
        sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
      }
    }
  }

  private void eraseFolderContent(
      Entity segment,
      Message<JsonObject> message,
      String folderId,
      String userId,
      boolean force,
      Set<String> folderCollaborators,
      boolean isMainFolder,
      Set<Item> parentFolders) {
    Entity subSegment = XRayManager.createSubSegment(operationGroup, segment, "eraseFolderContent");
    try {
      Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
      if (Objects.nonNull(folder) || isMainFolder) {
        // mark as erased
        if (!isMainFolder) {
          log.info(String.format("erasing folder %s...", folderId));
          SimpleStorage.eraseFile(folder, ttl);
          removeCustomPermissions(segment, message, null, true, folder, userId);
          if (Objects.nonNull(parentFolders)) {
            parentFolders.add(folder);
          }
        }
        // delete files owned by the user
        Iterator<Item> items = (force)
            ? SimpleStorage.getFolderWithDeletedItems(SimpleStorage.makeFolderId(folderId, userId))
                .iterator()
            : SimpleStorage.getDeletedFolderItems(SimpleStorage.makeFolderId(folderId, userId))
                .iterator();
        List<Item> itemList = IteratorUtils.toList(items);
        List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, true);
        itemList.addAll(itemShares);
        for (Item item : itemList) {
          if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
            if (!checkIfParentCollaboratorsHasAccess(item, folderId)) {
              continue;
            }
          }
          if (SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId))) {
            if (SimpleStorage.isFile(item)) {
              message.body().put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item));
              doEraseFile(message, false);
            } else {
              message.body().put(Field.FOLDER_ID.getName(), SimpleStorage.getItemId(item));
              Set<String> itemCollaborators = getAllObjectCollaborators(item);
              eraseFolderContent(
                  subSegment,
                  message,
                  SimpleStorage.getItemId(item),
                  userId,
                  force,
                  itemCollaborators,
                  false,
                  parentFolders);
            }
          } else {
            boolean isItemShare = SimpleStorage.isItemShare(item);
            Item originalItem;
            if (isItemShare) {
              originalItem = SimpleStorage.getItem(item.getString(Dataplane.pk));
              if (Objects.isNull(originalItem)) {
                continue;
              }
            } else {
              originalItem = item;
            }
            Set<String> itemCollaborators = getAllObjectCollaborators(originalItem);
            List<String> commonSharedUsers = new ArrayList<>(folderCollaborators);
            commonSharedUsers.remove(SimpleStorage.getOwner(item));
            commonSharedUsers.retainAll(itemCollaborators);
            commonSharedUsers.add(userId);
            SimpleStorage.removeSharedUsersAndUpdateItem(
                item, new JsonArray(commonSharedUsers), !isItemShare);
            if (SimpleStorage.isFile(item)) {
              eb_send(
                  subSegment,
                  RecentFilesVerticle.address + ".updateRecentFile",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                      .put(Field.ACCESSIBLE.getName(), true)
                      .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            }
            if (SimpleStorage.isFolder(item)) {
              removeSharedUsersAndUpdateFolder(
                  subSegment,
                  message,
                  userId,
                  item,
                  new JsonArray(commonSharedUsers),
                  force,
                  true,
                  parentFolders);
            }
          }
        }
        // delete metadata
        FolderMetaData.eraseMetaData(folderId, storageType.toString());
      } else {
        log.error(
            "Folder with Id " + folderId + " does not exist or user is not allowed to delete it");
      }
    } catch (Exception e) {
      log.error(
          "Error occurred in erasing the folder " + folderId + " : " + e.getLocalizedMessage());
    }
    XRayManager.endSegment(subSegment);
  }

  private void eraseMainFolder(
      Entity segment, Message<JsonObject> message, Item folder, String userId, boolean reply) {
    try {
      log.info(String.format("erasing main folder %s...", SimpleStorage.getItemId(folder)));
      SimpleStorage.eraseFile(folder, ttl);
      FolderMetaData.eraseMetaData(SimpleStorage.getItemId(folder), storageType.toString());
      removeCustomPermissions(segment, message, null, true, folder, userId);
    } catch (Exception ex) {
      if (reply) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST);
      } else {
        log.error("Error occurred in erasing folder " + ex);
      }
    }
  }

  private void removeSharedUsersAndUpdateFolder(
      Entity subSegment,
      Message<JsonObject> message,
      String userId,
      Item folder,
      JsonArray sharedUsers,
      boolean force,
      boolean doErase,
      Set<Item> parentFolders) {
    String folderId = SimpleStorage.makeFolderId(SimpleStorage.getItemId(folder), userId);
    Iterator<Item> items;
    if (doErase) {
      items = (force)
          ? SimpleStorage.getFolderWithDeletedItems(folderId).iterator()
          : SimpleStorage.getDeletedFolderItems(folderId).iterator();
    } else {
      items = SimpleStorage.getFolderItems(folderId, null, null).iterator();
    }
    List<Item> itemList = IteratorUtils.toList(items);
    if (doErase) {
      List<Item> itemShares = SimpleStorage.getFolderItemShares(folderId, userId, true);
      itemList.addAll(itemShares);
    }
    for (Item item : itemList) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        if (!doErase || !checkIfParentCollaboratorsHasAccess(item, folderId)) {
          continue;
        }
      }
      if (SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId))) {
        if (doErase) {
          if (SimpleStorage.isFile(item)) {
            message.body().put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item));
            doEraseFile(message, false);
          } else {
            message.body().put(Field.FOLDER_ID.getName(), SimpleStorage.getItemId(item));
            Set<String> itemCollaborators = getAllObjectCollaborators(item);
            eraseFolderContent(
                XRayManager.createSubSegment(operationGroup, "doErase:eraseFolderContent"),
                message,
                SimpleStorage.getItemId(item),
                userId,
                force,
                itemCollaborators,
                false,
                parentFolders);
          }
        } else {
          SimpleStorage.removeItemShares(item);
        }
      } else {
        SimpleStorage.removeSharedUsersAndUpdateItem(
            item, sharedUsers, !SimpleStorage.isItemShare(item));
        if (doErase && SimpleStorage.isFile(item)) {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, subSegment, message)
              .withName("updateRecentFile")
              .run((Segment blockingSegment) -> {
                eb_send(
                    blockingSegment,
                    RecentFilesVerticle.address + ".updateRecentFile",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), SimpleStorage.getItemId(item))
                        .put(Field.ACCESSIBLE.getName(), true)
                        .put(Field.STORAGE_TYPE.getName(), storageType.name()));
              });
        }
      }
      if (SimpleStorage.isFolder(item)) {
        removeSharedUsersAndUpdateFolder(
            subSegment, message, userId, item, sharedUsers, force, doErase, parentFolders);
      }
    }
  }

  public void doEraseFolder(Message<JsonObject> message) {
    doEraseFolder(message, true);
  }

  public void doEraseFolder(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    boolean force = body.getBoolean(Field.FORCE.getName(), false);

    if (folderId == null && reply) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    Item folder;
    if (!reply
        && body.containsKey("folderItemJson")
        && Utils.isStringNotNullOrEmpty(body.getString("folderItemJson"))) {
      folder = Item.fromJSON(body.getString("folderItemJson"));
      log.info("EraseAll request for folder : " + SimpleStorage.getItemId(folder));
    } else {
      folder = hasTrashRights(segment, message, folderId, false, force, userId);
    }
    // check if folder exists, marked as deleted and owned by user
    if (folder == null) {
      return;
    } else if (folderId == null) {
      folderId = SimpleStorage.getItemId(folder);
    }
    // to check for both deleted/non-deleted(non-owned) files
    if (SimpleStorage.isShared(folder)) {
      force = true;
    }
    Set<String> folderCollaborators = getAllObjectCollaborators(folder);
    boolean finalForce = force;
    String finalFolderId = folderId;
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("handleEraseFolderContent")
        .run((Segment blockingSegment) -> {
          Set<Item> parentFolders = new HashSet<>();
          eraseFolderContent(
              blockingSegment,
              message,
              finalFolderId,
              userId,
              finalForce,
              folderCollaborators,
              true,
              parentFolders);
          List<Item> sortedFolders =
              SimpleStorage.sortParentFoldersForCreationDate(parentFolders, userId);
          sortedFolders.forEach(item -> {
            String id = SimpleStorage.makeFolderId(SimpleStorage.getItemId(item), userId);
            // Deleting all sub folders at the end when their content are erased
            if (SimpleStorage.isEmptyFolder(id)) {
              SimpleStorage.removeFolder(item);
            }
          });
          // Deleting the main folder item at the end
          if (SimpleStorage.isEmptyFolder(SimpleStorage.getItemId(folder))) {
            SimpleStorage.removeFolder(folder);
          }
          if (reply) {
            sendSampleUsageWsNotification(segment, message, userId, true);
          }
        });
    eraseMainFolder(segment, message, folder, userId, reply);
    if (reply) {
      sendOK(segment, message, mills);
    }
    recordExecutionTime("eraseFolder", System.currentTimeMillis() - mills);
  }

  private Item hasTrashRights(
      Entity segment,
      Message<JsonObject> message,
      String id,
      boolean isFile,
      boolean force,
      String userId) {
    Item item = SimpleStorage.getItem(
        (!isFile) ? SimpleStorage.makeFolderId(id, userId) : SimpleStorage.makeFileId(id));
    if (item == null) {
      log.error(String.format(
          ((!isFile) ? Field.FOLDER.getName() : Field.FILE.getName()) + " with id %s doesn't exist",
          id));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(
                  message, (!isFile) ? "FolderWithIdDoesNotExist" : "FileWithIdDoesNotExist"),
              id),
          HttpStatus.NOT_FOUND);
      return null;
    }
    if (!SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId))) {
      log.error(String.format(
          "user %s is not the owner of "
              + ((!isFile) ? Field.FOLDER.getName() : Field.FILE.getName()) + " with id %s ",
          userId,
          id));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(
                  message,
                  (!isFile)
                      ? "OnlyOwnerCanAccessWithTypeToFolderWithId"
                      : "OnlyOwnerCanAccessWithTypeToFileWithId"),
              AccessType.canManageTrash.label,
              id),
          HttpStatus.FORBIDDEN);
      return null;
    }
    if (!force && !SimpleStorage.isDeleted(item)) {
      log.error(String.format(
          ((!isFile) ? Field.FOLDER.getName() : Field.FILE.getName())
              + " with id %s is not deleted",
          id));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(
                  message, (!isFile) ? "FolderWithIdIsNotInTrash" : "FileWithIdIsNotInTrash"),
              id),
          HttpStatus.BAD_REQUEST);
      return null;
    }
    return item;
  }

  private void doEraseUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      String userId = message.body().getString(Field.USER_ID.getName());
      if (userId == null) {
        sendError(segment, message, Utils.getLocalizedString(message, "FL3"), HttpStatus.NOT_FOUND);
        return;
      }
      // erase files and folders

      // 1. delete all files owned by user
      // 2. remove user from collaborators from shared items
      // 3. move all items owned by other users that are in folders owned by this user to to
      // their root folder
      // 4. delete all folders owned by users
      Iterator<Item> files =
          SimpleStorage.getAllFilesOwnedByUser(SimpleStorage.makeUserId(userId)).iterator();
      files.forEachRemaining(file -> {
        // notify editors and viewers
        Set<String> collaborators = getAllObjectCollaborators(file);
        if (!collaborators.isEmpty()) {
          eb_send(
              segment,
              MailUtil.address + ".notifyDelete",
              new JsonObject()
                  .put(Field.OWNER_ID.getName(), userId)
                  .put(Field.NAME.getName(), SimpleStorage.getName(file))
                  .put(Field.USERS.getName(), new JsonArray(new ArrayList<>(collaborators)))
                  .put(Field.TYPE.getName(), 1));
        }
        SimpleStorage.eraseFile(file, ttl);
        removeCustomPermissions(segment, message, collaborators, false, file, userId);
        Iterator<Item> cursor = SimpleStorage.getFileVersions(
                SimpleStorage.makeFileId(SimpleStorage.getItemId(file)))
            .iterator();
        Item version;

        S3Regional userS3 = getS3Regional(file, userId);

        if (!SimpleStorage.isSampleFile(file)) {
          if (SimpleStorage.getDeleteMarker(file) == null) {
            // no need to delete it twice
            userS3.delete(SimpleStorage.getPath(file));
          }
        }

        String lastPath = emptyString;
        while (cursor.hasNext()) {
          version = cursor.next();
          if (!SimpleStorage.isSampleFile(version)) {
            if (!lastPath.equals(SimpleStorage.getPath(version))) {
              userS3.delete(SimpleStorage.getPath(version));
              lastPath = SimpleStorage.getPath(version);
            }
          }
          SimpleStorage.removeFileVersion(version);
        }
      });

      Iterator<Item> sharedItems = SimpleStorage.getAllItemsSharedWithUser(
              SimpleStorage.makeUserId(userId))
          .iterator();
      sharedItems.forEachRemaining(item -> {
        SimpleStorage.removeSharedUsersAndUpdateItem(item, new JsonArray().add(userId), false);
        if (SimpleStorage.isFolder(item)) {
          removeSharedUsersAndUpdateFolder(
              segment, null, null, item, new JsonArray().add(userId), false, false, null);
        }
      });

      Iterator<Item> folders = SimpleStorage.getAllFoldersOwnedByUser(
              SimpleStorage.makeUserId(userId))
          .iterator();
      folders.forEachRemaining(folder -> {
        // notify editors and viewers
        Set<String> collaborators = getAllObjectCollaborators(folder);
        if (!SimpleStorage.isDeleted(folder)) {
          if (!collaborators.isEmpty()) {
            eb_send(
                segment,
                MailUtil.address + ".notifyDelete",
                new JsonObject()
                    .put(Field.OWNER_ID.getName(), userId)
                    .put(Field.NAME.getName(), SimpleStorage.getName(folder))
                    .put(Field.USERS.getName(), new JsonArray(new ArrayList<>(collaborators)))
                    .put(Field.TYPE.getName(), 0));
          }
        }
        removeCustomPermissions(segment, message, collaborators, true, folder, userId);
        SimpleStorage.removeFolderItemShares(SimpleStorage.getItemId(folder), userId);
        SimpleStorage.removeFolder(folder);
        FolderMetaData.eraseMetaData(SimpleStorage.getItemId(folder), storageType.toString());
      });
      sendOK(segment, message);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private void doEraseFile(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    try {
      String userId = body.getString(Field.USER_ID.getName());
      String verId = body.getString(Field.VER_ID.getName());
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, body);
      if (fileId == null && reply) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item fileObject;
      if (!reply
          && body.containsKey("fileItemJson")
          && Utils.isStringNotNullOrEmpty(body.getString("fileItemJson"))) {
        fileObject = Item.fromJSON(body.getString("fileItemJson"));
        log.info("EraseAll request for file : " + SimpleStorage.getItemId(fileObject));
      } else {
        fileObject = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      }
      if (fileObject == null) {
        return;
      } else if (fileId == null) {
        fileId = SimpleStorage.getItemId(fileObject);
      }

      // check if user is owner (if erasing the file explicitly)
      if (reply) {
        if (!SimpleStorage.checkOwner(fileObject, SimpleStorage.makeUserId(userId))) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "OnlyOwnerCanAccessWithTypeToFileWithId"),
                  AccessType.canManageTrash.label,
                  fileId),
              HttpStatus.FORBIDDEN);
          return;
        }
      }

      S3Regional userS3 = getS3Regional(fileObject, userId);

      // delete version, snapshot or undo
      if (verId != null) {
        if (SimpleStorage.isDeleted(fileObject)) {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "ItIsNotPossibleToChangeFilesFromTrash"),
                HttpStatus.BAD_REQUEST);
          }
          return;
        }
        SimpleStorage.deleteFileVersion(
            SimpleStorage.makeFileId(fileId), SimpleStorage.makeVersionId(verId));
        // delete chunks
        userS3.deleteVersion(SimpleStorage.getPath(fileObject), verId);
      }
      // delete file
      else {
        if (!SimpleStorage.isDeleted(fileObject)) {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "OnlyFilesFromTrashCanBePermanentlyDeleted"),
                HttpStatus.BAD_REQUEST);
          }
          return;
        }
        // delete file info
        SimpleStorage.eraseFile(fileObject, ttl);
        removeCustomPermissions(segment, message, null, false, fileObject, userId);

        if (!SimpleStorage.isSampleFile(fileObject)) {
          if (SimpleStorage.getDeleteMarker(fileObject) == null) {
            // no need to delete it twice
            userS3.delete(SimpleStorage.getPath(fileObject));
          }
        }

        Iterator<Item> cursor =
            SimpleStorage.getFileVersions(SimpleStorage.makeFileId(fileId)).iterator();
        Item version;
        String lastPath = emptyString;
        while (cursor.hasNext()) {
          version = cursor.next();
          if (!SimpleStorage.isSampleFile(version)) {
            if (!lastPath.equals(SimpleStorage.getPath(version))) {
              userS3.delete(SimpleStorage.getPath(version));
              lastPath = SimpleStorage.getPath(version);
            }
          }
          SimpleStorage.removeFileVersion(version);
        }
        if (!SimpleStorage.isSampleFile(fileObject)) {
          SimpleStorage.updateUsage(
              SimpleStorage.makeUserId(userId), -1 * SimpleStorage.getFileSize(fileObject));
        } else {
          S3Regional sampleS3 = getS3Regional(null, userId);
          if (SimpleStorage.isSampleFileModified(fileObject, sampleS3, bucket)) {
            SimpleStorage.updateUsage(
                SimpleStorage.makeUserId(userId), -1 * SimpleStorage.getFileSize(fileObject));
          }
        }
        if (reply) {
          sendOK(segment, message, mills);
        }
      }
      recordExecutionTime("eraseFile", System.currentTimeMillis() - mills);
    } catch (Exception ex) {
      if (reply) {
        sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
      } else {
        log.error("Error occurred in erasing file " + ex);
      }
    }
  }

  public void doEraseAll(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.USER_ID.getName());
    try {
      // Doing the erasing task asynchronously
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("EraseAllContent")
          .runWithoutSegment(() -> {
            // Getting and erasing all files
            for (Item file : SimpleStorage.getAllDeletedItemsOwnedByUser(
                SimpleStorage.makeUserId(userId), true)) {
              message.body().put("fileItemJson", file.toJSON());
              doEraseFile(message, false);
            }

            // send updated usage WS notification
            sendSampleUsageWsNotification(segment, message, userId, true);
            message.body().remove("fileItemJson");

            // Getting and erasing all folders
            for (Item folder : SimpleStorage.getAllDeletedItemsOwnedByUser(
                SimpleStorage.makeUserId(userId), false)) {
              message.body().put("folderItemJson", folder.toJSON());
              doEraseFolder(message, false);
            }
          });
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doEraseFile(Message<JsonObject> message) {
    doEraseFile(message, true);
  }

  public void doCheckFileVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    // if versionId non-null - check file & version, else just file
    final String versionId = message.body().getString(Field.VERSION_ID.getName());
    final String userId = message.body().getString(Field.USER_ID.getName());

    try {
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileIds.getId()));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileIds.getId()),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), true, AccessType.canManagePublicLink)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManagePublicLink.label,
                fileIds.getId()),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileIds.getId()),
            HttpStatus.BAD_REQUEST);
        return;
      }

      if (Objects.nonNull(versionId)) {
        VersionType versionType = VersionType.parse(versionId);

        // need to check specific version if it is not latest
        if (versionType.equals(VersionType.SPECIFIC)
            && !SimpleStorage.getLatestVersion(file).equals(versionId)) {
          Item version = SimpleStorage.getFileVersion(
              SimpleStorage.makeFileId(fileIds.getId()), SimpleStorage.makeVersionId(versionId));

          if (Objects.isNull(version)) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), versionId),
                HttpStatus.BAD_REQUEST);
            return;
          }
        }
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.NAME.getName(), SimpleStorage.getName(file))
              .put(Field.OWNER_IDENTITY.getName(), userId)
              .put(Field.COLLABORATORS.getName(), Collections.singletonList(userId)),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doCreateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
    Boolean export = body.getBoolean(Field.EXPORT.getName());
    final long endTime =
        body.containsKey(Field.END_TIME.getName()) ? body.getLong(Field.END_TIME.getName()) : 0L;
    final String userId = body.getString(Field.USER_ID.getName());
    final String password = body.getString(Field.PASSWORD.getName());
    try {
      if (export == null) {
        export = false;
      }
      if (fileId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), true, AccessType.canManagePublicLink)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManagePublicLink.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }

      try {
        final boolean oldExport =
            getLinkExportStatus(fileId, SimpleStorage.getAccountId(userId), userId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            SimpleStorage.getAccountId(userId),
            userId,
            storageType,
            userId,
            SimpleStorage.getName(file),
            export,
            endTime,
            password);

        newLink.setCollaboratorsList(Collections.singletonList(userId));
        try {
          if (body.containsKey(Field.RESET_PASSWORD.getName())
              && body.getBoolean(Field.RESET_PASSWORD.getName())
              && newLink.isPasswordSet()
              && !Utils.isStringNotNullOrEmpty(password)) {
            newLink.resetPassword();
          }
        } catch (Exception ignore) {
          // let's ignore this exception
        }
        newLink.createOrUpdate();

        sendOK(
            segment,
            message,
            newLink.getInfoInJSON().put(Field.LINK.getName(), newLink.getEndUserLink()),
            mills); // NON-NLS
        if (oldExport != newLink.getExport()) {
          eb_send(
              segment,
              WebSocketManager.address + ".exportStateUpdated",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.EXPORT.getName(), newLink.getExport())
                  .put(Field.TOKEN.getName(), newLink.getToken()));
        }
      } catch (PublicLinkException ple) {
        sendError(segment, message, ple.toResponseObject(), HttpStatus.BAD_REQUEST);
      }

    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doDeleteSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String fileId = body.getString(Field.FILE_ID.getName());
    final String userId = body.getString(Field.USER_ID.getName());
    try {
      if (fileId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(userId), true, AccessType.canManagePublicLink)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeForFileWithId"),
                AccessType.canManagePublicLink.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }
      if (SimpleStorage.isDeleted(file)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String linkOwnerIdentity = super.deleteSharedLink(
          segment, message, fileId, SimpleStorage.getAccountId(userId), userId);
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.LINK_OWNER_IDENTITY.getName(), linkOwnerIdentity),
          mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRequestMultipleObjectsZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String requestId = json.getString(Field.REQUEST_ID.getName());
    String parentFolderId = json.getString(Field.PARENT_FOLDER_ID.getName());
    String userId = json.getString(Field.USER_ID.getName());
    String filter = json.getString(Field.FILTER.getName());
    JsonArray downloads = json.getJsonArray(Field.DOWNLOADS.getName());
    boolean recursive = message.body().getBoolean(Field.RECURSIVE.getName());
    Item request = ZipRequests.getZipRequest(userId, parentFolderId, requestId);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream stream = new ZipOutputStream(bos);
    Set<String> folderNames = new HashSet<>();
    Set<String> fileNames = new HashSet<>();
    Set<String> filteredFileNames = new HashSet<>();
    ThreadPoolExecutor poolExecutor = new ThreadPoolExecutor(
        0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    List<Callable<Void>> callables = new ArrayList<>();
    try {
      downloads.stream().iterator().forEachRemaining(download -> {
        Entity subSegment = XRayManager.createSegment(operationGroup, message);
        JsonObject object = (JsonObject) download;
        if (!object.containsKey(Field.OBJECT_TYPE.getName())
            || !object.containsKey(Field.ID.getName())
            || !Utils.isStringNotNullOrEmpty(object.getString(Field.ID.getName()))) {
          return;
        }

        if (!object.containsKey(Field.OBJECT_TYPE.getName())) {
          return;
        }

        ObjectType type =
            ObjectType.valueOf(object.getString(Field.OBJECT_TYPE.getName()).toUpperCase());

        String objectId = Utils.parseItemId(
                object.getString(Field.ID.getName()), Field.OBJECT_ID.getName())
            .getString(Field.OBJECT_ID.getName());
        boolean isFolder = type.equals(ObjectType.FOLDER);
        Item item = SimpleStorage.getItem(
            isFolder
                ? SimpleStorage.makeFolderId(objectId, userId)
                : SimpleStorage.makeFileId(objectId));
        if (item == null || SimpleStorage.isDeleted(item)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileOrFolderWithIdDoesNotExist"),
                  Utils.getLocalizedString(message, isFolder ? "Folder" : "File"),
                  objectId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (!SimpleStorage.hasRights(
            item, SimpleStorage.makeUserId(userId), false, AccessType.canDownload)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(
                      message,
                      isFolder
                          ? "UserHasNoAccessWithTypeToFolderWithId"
                          : "UserHasNoAccessWithTypeToFileWithId"),
                  AccessType.canDownload.label,
                  objectId),
              HttpStatus.FORBIDDEN);
          return;
        }
        callables.add(() -> {
          Entity standaloneSegment =
              XRayManager.createSubSegment(operationGroup, subSegment, "MultipleDownloadSegment");
          String name = SimpleStorage.getName(item);
          if (isFolder) {
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(
                stream, objectId, userId, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = SimpleStorage.getFileSize(item);
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, SimpleStorage.getName(item), ExcludeReason.Large);
              return null;
            }
            addZipEntry(
                stream, item, userId, name, filter, filteredFileNames, emptyString, fileNames);
          }
          XRayManager.endSegment(standaloneSegment);
          return null;
        });
        XRayManager.endSegment(subSegment);
      });
      sendOK(segment, message);
      if (callables.isEmpty()) {
        log.warn("Nothing to download, please check the logs for multiple downloads for requestId "
            + requestId + " for storage - " + storageType);
        return;
      }
      finishDownloadZip(message, segment, s3Regional, stream, bos, callables, request);
    } catch (Exception ex) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doRequestFolderZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String requestId = json.getString(Field.REQUEST_ID.getName());
    String userId = json.getString(Field.USER_ID.getName());
    String folderId = json.getString(Field.FOLDER_ID.getName());
    String filter = json.getString(Field.FILTER.getName());
    boolean recursive = message.body().getBoolean(Field.RECURSIVE.getName());
    Item request = ZipRequests.getZipRequest(userId, folderId, requestId);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    Item f = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
    if (f == null || SimpleStorage.isDeleted(f)) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
          HttpStatus.NOT_FOUND);
      return;
    }
    if (!SimpleStorage.hasRights(
        f, SimpleStorage.makeUserId(userId), false, AccessType.canDownload)) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFolderWithId"),
              AccessType.canDownload.label,
              folderId),
          HttpStatus.FORBIDDEN);
      return;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream stream = new ZipOutputStream(bos);
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    try {
      Future<Void> futureTask = executorService.submit(() -> {
        Entity subSegment = XRayManager.createStandaloneSegment(
            operationGroup, segment, "FluorineZipFolderSegment");
        zipFolder(
            stream, folderId, userId, filter, recursive, emptyString, new HashSet<>(), 0, request);
        XRayManager.endSegment(subSegment);
        return null;
      });
      sendOK(segment, message);
      S3Regional usersS3 = getS3Regional(SimpleStorage.makeFolderId(folderId, userId), userId);
      finishDownloadZip(message, segment, usersS3, stream, bos, futureTask, request);
    } catch (Exception ex) {
      ZipRequests.setRequestException(message, request, ex);
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      String folderId,
      String userId,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, null));
    if (Objects.isNull(folder)) {
      return;
    }
    Iterator<Item> items = SimpleStorage.getFolderItems(
            SimpleStorage.makeFolderId(folderId, userId), null, null)
        .iterator();
    if (!items.hasNext()) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    Item item;
    while (items.hasNext()) {
      item = items.next();
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      String name = SimpleStorage.getName(item);
      String properPath = path.isEmpty() ? path : path + File.separator;
      if (SimpleStorage.isFolder(item) && recursive) {
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          zipFolder(
              stream,
              SimpleStorage.getItemId(item),
              userId,
              filter,
              true,
              properPath + name,
              filteredFileNames,
              recursionDepth,
              request);
        } else {
          log.warn("Zip folder recursion exceeds the limit for usedId : " + userId);
        }
      } else {
        long fileSize = SimpleStorage.getFileSize(item);
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, SimpleStorage.getName(item), ExcludeReason.Large);
          return;
        }
        addZipEntry(stream, item, userId, name, filter, filteredFileNames, properPath, fileNames);
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      Item item,
      String userId,
      String name,
      String filter,
      Set<String> filteredFileNames,
      String properPath,
      Set<String> fileNames)
      throws IOException {
    for (String specialCharacter : windowsSpecialCharacters) {
      if (name.contains(getNonRegexSpecialCharacter(specialCharacter))) {
        name = name.replaceAll(specialCharacter, "_");
      }
    }
    ZipEntry zipEntry = null;
    if (Utils.isStringNotNullOrEmpty(filter)) {
      String[] formats = filter.split(",");
      for (String expansion : formats) {
        if (name.toLowerCase().endsWith(expansion)) {
          name = Utils.checkAndRename(filteredFileNames, name);
          filteredFileNames.add(name);
          zipEntry = new ZipEntry(name);
        }
      }
    } else {
      name = Utils.checkAndRename(fileNames, name);
      fileNames.add(name);
      zipEntry = new ZipEntry(properPath + name);
    }
    if (zipEntry != null) {
      stream.putNextEntry(zipEntry);
      stream.write(
          getChunk(getS3Regional(item, userId), SimpleStorage.getPath(item), null, null, null));
      stream.closeEntry();
      stream.flush();
    }
  }

  public void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      JsonObject json = message.body();
      String ownerId = json.getString(Field.USER_ID.getName());
      String query = json.getString(Field.QUERY.getName());
      JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
      Iterator<Item> items = SimpleStorage.searchForName(SimpleStorage.makeUserId(ownerId), query);
      Item item;
      final JsonObject samplesConfig = config.getProperties().getSamplesFilesAsJson();
      final JsonObject appConfig =
          samplesConfig.getJsonObject(config.getProperties().getSmtpProName());
      JsonArray disabledSampleFiles = appConfig.getJsonArray("disabledFiles");
      while (items.hasNext()) {
        item = items.next();
        message.body().remove("folderPathValid");
        message.body().put(Field.ID.getName(), SimpleStorage.getParent(item, ownerId));
        doGetFolderPath(message, false);
        // means the path of the item is valid
        if (message.body().containsKey("folderPathValid")) {
          if (SimpleStorage.isFolder(item)) {
            JsonObject folderJson = getFolderJson(segment, message, item, ownerId, false);
            foldersJson.add(folderJson);
          } else {
            if (SimpleStorage.isSampleFile(item)
                && disabledSampleFiles.contains(SimpleStorage.getName(item))) {
              continue;
            }
            JsonObject fileJson = getFileJson(item, ownerId, false, false, false, false);
            filesJson.add(fileJson);
          }
        }
      }

      JsonArray result = new JsonArray();
      result.add(new JsonObject()
          .put(Field.EXTERNAL_ID.getName(), SimpleStorage.getAccountId(ownerId))
          .put(Field.STORAGE_TYPE.getName(), storageType)
          .put(Field.FILES.getName(), filesJson)
          .put(Field.FOLDERS.getName(), foldersJson));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject jsonObject = message.body();
      String fileId = jsonObject.getString(Field.FILE_ID.getName());
      String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
      JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
      String userId = jsonObject.getString(Field.USER_ID.getName());
      String currentFolder = requestFolderId;
      if (currentFolder != null) {
        currentFolder = Utils.parseItemId(currentFolder).getString(Field.ID.getName());
        if (!currentFolder.equals(ROOT_FOLDER_ID)
            && !currentFolder.equals(ROOT)
            && !currentFolder.equals(SHARED_FOLDER)) {
          Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(currentFolder, userId));
          if (folder == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), fileId),
                HttpStatus.NOT_FOUND);
            return;
          }
        }
      } else {
        Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
        if (file == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
              HttpStatus.NOT_FOUND);
          return;
        }
        if (userId == null) {
          userId = SimpleStorage.getOwner(file);
        }
        currentFolder = SimpleStorage.getParent(file, userId);
      }
      String finalUserId = userId;
      // RG: Refactored to not use global collections
      List<String> pathList = path.getList();
      String finalCurrentFolder = currentFolder;
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            if (pathStr == null) {
              XRayManager.endSegment(subSegment);
              return null;
            }
            JsonArray pathFiles = new JsonArray();
            String[] array = Utils.parseRelativePath(pathStr);
            String filename = array[array.length - 1];
            Set<String> possibleFolders = new HashSet<>();
            possibleFolders.add(finalCurrentFolder);
            if (!(array.length == 1 || (array.length == 2 && array[0].trim().isEmpty()))) {
              for (int i = 0; i < array.length - 1; i++) {
                if (array[i].isEmpty()) {
                  continue;
                }
                Iterator<String> it = possibleFolders.iterator();
                Set<String> adds = new HashSet<>(), dels = new HashSet<>();
                while (it.hasNext()) {
                  adds.clear();
                  dels.clear();
                  String folderId = it.next();
                  dels.add(folderId);
                  if ("..".equals(array[i])) {
                    goUp(folderId, finalUserId, adds);
                  } else {
                    goDown(folderId, finalUserId, array[i], adds);
                  }
                }
                possibleFolders.removeAll(dels);
                possibleFolders.addAll(adds);
              }
            }
            // check in all possible folders
            for (String folderId : possibleFolders) {
              findFileInFolder(finalUserId, folderId, filename, pathFiles);
            }
            // check in the root if not found
            if (pathFiles.isEmpty() && !possibleFolders.contains(finalCurrentFolder)) {
              findFileInFolder(finalUserId, finalCurrentFolder, filename, pathFiles);
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.PATH.getName(), pathStr)
                .put(Field.FILES.getName(), pathFiles);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  private void goDown(String folderId, String userId, String name, Set<String> adds) {
    for (Item item : SimpleStorage.getSubFolder(
        SimpleStorage.makeFolderId(folderId, userId), name, SimpleStorage.makeUserId(userId))) {
      adds.add(SimpleStorage.getItemId(item));
    }
  }

  private void goUp(String folderId, String userId, Set<String> adds) {
    if (ROOT.equals(folderId)) {
      return;
    }
    Item obj = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
    if (obj != null) {
      adds.add(SimpleStorage.getParent(obj, userId));
    }
  }

  private JsonObject fileToJson(Item item, String userId, String filename) {
    // get size and update date
    String size;
    long sizeRaw = SimpleStorage.getFileSize(item);
    size = Utils.humanReadableByteCount(sizeRaw);
    String changerId = SimpleStorage.getLatestChanger(item);
    String changer = Users.getUserNameById(changerId);
    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                SimpleStorage.getAccountId(userId),
                SimpleStorage.getItemId(item)))
        .put(Field.OWNER.getName(), Users.getUserNameById(SimpleStorage.getOwner(item)))
        .put(
            Field.IS_OWNER.getName(),
            SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId)))
        .put(
            Field.CAN_MOVE.getName(),
            SimpleStorage.checkOwner(item, SimpleStorage.makeUserId(userId)))
        .put(Field.UPDATE_DATE.getName(), SimpleStorage.getLatestChangeTime(item))
        .put(Field.CHANGER.getName(), changer)
        .put(Field.CHANGER_ID.getName(), changerId)
        .put(Field.SIZE.getName(), size)
        .put(Field.SIZE_VALUE.getName(), sizeRaw)
        .put(Field.NAME.getName(), filename)
        .put(Field.STORAGE_TYPE.getName(), storageType.name());
  }

  private void findFileInFolder(
      String userId, String folderId, String filename, JsonArray pathFiles) {
    if (folderId != null) {
      Item folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
      if (folderId.equals(ROOT) || (folder != null && !SimpleStorage.isDeleted(folder))) {
        Iterator<Item> it = SimpleStorage.getFileInFolder(
                SimpleStorage.makeFolderId(folderId, userId),
                filename,
                SimpleStorage.makeUserId(userId))
            .iterator();
        List<Item> list = new ArrayList<>();
        it.forEachRemaining(list::add);
        for (Item item : list) {
          pathFiles.add(fileToJson(item, userId, filename));
        }
      }
    }
  }

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject jsonObject = message.body();
      String fileId = jsonObject.getString(Field.FILE_ID.getName());
      String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
      JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
      String userId = jsonObject.getString(Field.USER_ID.getName());
      String currentFolder = folderId;
      final Item[] folder = {null};
      if (currentFolder != null) {
        if (!currentFolder.equals(ROOT_FOLDER_ID)
            && !currentFolder.equals(ROOT)
            && !currentFolder.equals(SHARED_FOLDER)) {
          folder[0] = SimpleStorage.getItem(SimpleStorage.makeFolderId(currentFolder, userId));
          if (folder[0] == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), fileId),
                HttpStatus.NOT_FOUND);
            return;
          }
        }
      } else {
        Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
        if (file == null) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
              HttpStatus.NOT_FOUND);
          return;
        }
        currentFolder = SimpleStorage.getParent(file, userId);
      }
      String finalCurrentFolder = currentFolder;
      // RG: Refactored to not use global collections
      List<String> pathList = path.getList();
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            if (pathStr == null) {
              XRayManager.endSegment(subSegment);
              return null;
            }
            String[] array = pathStr.split("/");
            if (array.length == 0) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
            String filename = array[array.length - 1];
            // current folder
            if (array.length == 1) {
              if (folder[0] == null
                  && !finalCurrentFolder.equals(ROOT_FOLDER_ID)
                  && !finalCurrentFolder.equals(ROOT)
                  && !finalCurrentFolder.equals(SHARED_FOLDER)) {
                folder[0] =
                    SimpleStorage.getItem(SimpleStorage.makeFolderId(finalCurrentFolder, userId));
                if (folder[0] == null) {
                  XRayManager.endSegment(subSegment);
                  sendError(
                      segment,
                      message,
                      MessageFormat.format(
                          Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), fileId),
                      HttpStatus.NOT_FOUND);
                  return null;
                }
              }
              boolean available = finalCurrentFolder.equals(ROOT)
                  || SimpleStorage.hasRights(
                      folder[0], SimpleStorage.makeUserId(userId), false, null);
              // check for existing file
              if (available) {
                available = SimpleStorage.checkName(
                    SimpleStorage.makeFolderId(finalCurrentFolder, userId), filename, true, null);
              }
              JsonArray folders = new JsonArray();
              if (available) {
                folders.add(new JsonObject().put(Field.ID.getName(), finalCurrentFolder));
              }
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(
                      Field.STATE.getName(),
                      available ? Field.AVAILABLE.getName() : Field.UNAVAILABLE.getName())
                  .put(Field.FOLDERS.getName(), folders);
            }
            Map<String, Item> foldersCache = new HashMap<>();
            Set<String> possibleFolders = new HashSet<>();
            possibleFolders.add(finalCurrentFolder);
            int lastStep = -1;
            for (int i = 0; i < array.length - 1; i++) {
              if (array[i].isEmpty()) {
                continue; // todo ?
              }
              Iterator<String> it = possibleFolders.iterator();
              Set<String> adds = new HashSet<>(), dels = new HashSet<>();
              Item obj;
              while (it.hasNext()) {
                adds.clear();
                dels.clear();
                String possibleFolderId = it.next();
                if ("..".equals(array[i])) {
                  if (ROOT.equals(possibleFolderId)) {
                    continue;
                  }
                  obj = SimpleStorage.getItem(SimpleStorage.makeFolderId(possibleFolderId, userId));
                  if (obj != null) {
                    adds.add(obj.getString(Field.PARENT.getName()));
                    dels.add(possibleFolderId);
                  }
                } else {
                  for (Item f : SimpleStorage.getSubFolders(
                      SimpleStorage.makeFolderId(possibleFolderId, userId),
                      SimpleStorage.makeUserId(userId))) {
                    foldersCache.put(SimpleStorage.getItemId(f), f);
                    adds.add(SimpleStorage.getItemId(f));
                  }
                  if (!adds.isEmpty()) {
                    dels.add(possibleFolderId);
                  } else {
                    lastStep = i;
                    break;
                  }
                }
              }
              possibleFolders.removeAll(dels);
              possibleFolders.addAll(adds);
              if (lastStep != -1) {
                break;
              }
            }
            // check if it's possible to either upload a file (if lastStep == -1) or create a path
            // starting from array[i] in possible folders
            JsonArray folders = new JsonArray();
            boolean possible = lastStep != -1;
            for (String possibleFolderId : possibleFolders) {
              Item possibleFolder = foldersCache.get(possibleFolderId);
              if (possibleFolder == null) {
                possibleFolder =
                    SimpleStorage.getItem(SimpleStorage.makeFolderId(possibleFolderId, userId));
              }
              if (possibleFolder == null) {
                continue;
              }
              boolean available = SimpleStorage.hasRights(
                  possibleFolder, SimpleStorage.makeUserId(userId), false, null);
              if (available) {
                if (lastStep == -1) {
                  Iterator<Item> files = SimpleStorage.getFileInFolder(
                          SimpleStorage.makeFolderId(possibleFolderId, userId),
                          filename,
                          SimpleStorage.makeUserId(userId))
                      .iterator();
                  if (!files.hasNext()) {
                    folders.add(new JsonObject()
                        .put(Field.ID.getName(), possibleFolderId)
                        .put(Field.NAME.getName(), SimpleStorage.getName(possibleFolder))
                        .put(
                            Field.OWNER.getName(),
                            Users.getUserNameById(SimpleStorage.getOwner(possibleFolder)))
                        .put(
                            Field.IS_OWNER.getName(),
                            Users.getUserNameById(SimpleStorage.getOwner(possibleFolder)))
                        .put(Field.UPDATE_DATE.getName(), 0)
                        .put(Field.CHANGER.getName(), emptyString)
                        .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                  }
                } else {
                  folders.add(new JsonObject()
                      .put(Field.ID.getName(), possibleFolderId)
                      .put(Field.NAME.getName(), SimpleStorage.getName(possibleFolder))
                      .put(
                          Field.OWNER.getName(),
                          Users.getUserNameById(SimpleStorage.getOwner(possibleFolder)))
                      .put(
                          Field.IS_OWNER.getName(),
                          SimpleStorage.checkOwner(
                              possibleFolder, SimpleStorage.makeUserId(userId)))
                      .put(
                          Field.CAN_MOVE.getName(),
                          SimpleStorage.checkOwner(
                              possibleFolder, SimpleStorage.makeUserId(userId)))
                      .put(Field.UPDATE_DATE.getName(), 0)
                      .put(Field.CHANGER.getName(), emptyString)
                      .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                }
              }
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.PATH.getName(), pathStr)
                .put(
                    Field.STATE.getName(),
                    folders.isEmpty()
                        ? Field.UNAVAILABLE.getName()
                        : folders.size() > 1
                            ? Field.CONFLICT.getName()
                            : possible ? Field.POSSIBLE.getName() : Field.AVAILABLE.getName())
                .put(Field.FOLDERS.getName(), folders);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetVersionByToken(Message<JsonObject> message) {
    final Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    final JsonObject jsonObject = message.body();
    final KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    final String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    final String linkOwnerId = jsonObject.getString(Field.LINK_OWNER_ID.getName());
    final String lt = jsonObject.getString(Field.LINK_TYPE.getName());

    try {
      // check if file exists and user has access
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileIds.getId()));

      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileIds.getId()),
            HttpStatus.NOT_FOUND);
        return;
      }

      message.body().put(Field.NAME.getName(), SimpleStorage.getName(file));

      VersionType versionType = VersionType.parse(versionId);

      S3Regional userS3 = getS3Regional(file, linkOwnerId);

      if (!SimpleStorage.hasRights(
          file, SimpleStorage.makeUserId(linkOwnerId), false, AccessType.canDownload)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                AccessType.canDownload.label,
                fileIds.getId()),
            HttpStatus.FORBIDDEN);
        return;
      }

      String latestFileVersion = SimpleStorage.getLatestVersion(file);
      String realVersionId;
      byte[] data;

      if (!versionType.equals(VersionType.LATEST) && !versionId.equals(latestFileVersion)) {
        realVersionId = versionId;

        Item versionItem = SimpleStorage.getFileVersion(
            SimpleStorage.makeFileId(fileIds.getId()), SimpleStorage.makeVersionId(realVersionId));

        if (Objects.isNull(versionItem)) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), realVersionId),
              HttpStatus.NOT_FOUND);
          return;
        }

        if (SimpleStorage.isSampleFile(versionItem)) {
          data = getChunk(userS3, SimpleStorage.getPath(versionItem), null, null, null);
        } else {
          data = getChunk(
              userS3,
              SimpleStorage.getPath(versionItem),
              SimpleStorage.getS3Version(versionItem),
              null,
              null);
        }
      } else {
        realVersionId = latestFileVersion;
        data = getChunk(userS3, SimpleStorage.getPath(file), null, null, null);
      }

      if (data.length == 0) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), realVersionId),
            HttpStatus.NOT_FOUND);
        return;
      }

      finishGetFileVersionByToken(
          message,
          data,
          realVersionId,
          SimpleStorage.getName(file),
          LinkType.parse(lt).equals(LinkType.DOWNLOAD));
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getVersionByToken", System.currentTimeMillis() - mills);
  }

  public void doGetFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    boolean isFileOpenDownload = jsonObject.containsKey(Field.X_SESSION_ID.getName())
        && Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.X_SESSION_ID.getName()));
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    if (userId == null || fileId == null) {
      return;
    }
    String verId = jsonObject.getString(Field.VER_ID.getName());
    Boolean download = message.body().getBoolean(Field.DOWNLOAD.getName());
    if (download == null) {
      download = false;
    }
    Integer start = message.body().getInteger(Field.START.getName()),
        end = message.body().getInteger(Field.END.getName());
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    try {
      // check if file exists and user has access
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      S3Regional userS3 = getS3Regional(file, userId);
      if (!SimpleStorage.hasRights(
          file,
          SimpleStorage.makeUserId(userId),
          false,
          (isFileOpenDownload) ? AccessType.canOpen : AccessType.canDownload)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                (isFileOpenDownload) ? AccessType.canOpen.label : AccessType.canDownload.label,
                fileId),
            HttpStatus.FORBIDDEN);
        return;
      }

      if (download) {
        message.body().put(Field.NAME.getName(), SimpleStorage.getName(file));
      }

      byte[] data;
      if (Utils.isStringNotNullOrEmpty(verId)
          && !SimpleStorage.getLatestVersion(file).equals(verId)) {
        Item version = SimpleStorage.getFileVersion(
            SimpleStorage.makeFileId(fileId), SimpleStorage.makeVersionId(verId));
        if (version == null) {
          log.info("[SF_VERSION] Missing version on doGetFile -> getFileVersion. FileId: " + fileId
              + " VersionId: " + verId + " makeFileId: " + SimpleStorage.makeFileId(fileId)
              + " makeVersionId: " + SimpleStorage.makeVersionId(verId)
              + " latestFileVersion: " + SimpleStorage.getLatestVersion(file));
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), verId),
              HttpStatus.NOT_FOUND);
          return;
        } else {
          if (returnDownloadUrl) {
            String s3VersionId =
                SimpleStorage.isSampleFile(version) ? null : SimpleStorage.getS3Version(version);
            String downloadUrl = userS3.getDownloadUrl(
                SimpleStorage.getPath(version), SimpleStorage.getName(version), s3VersionId);
            sendDownloadUrl(
                segment,
                message,
                downloadUrl,
                SimpleStorage.getFileSize(version),
                verId,
                null,
                mills);
            return;
          }
          if (SimpleStorage.isSampleFile(version)) {
            data = getChunk(userS3, SimpleStorage.getPath(version), null, start, end);
          } else {
            data = getChunk(
                userS3,
                SimpleStorage.getPath(version),
                SimpleStorage.getS3Version(version),
                start,
                end);
          }
          if (data.length == 0) {
            log.info(
                "[SF_VERSION] Missing version DATA on doGetFile -> versioned -> getChunk. FileId: "
                    + fileId + " VersionId: " + verId + " makeFileId: "
                    + SimpleStorage.makeFileId(fileId) + " makeVersionId: "
                    + SimpleStorage.makeVersionId(verId)
                    + " latestFileVersion: " + SimpleStorage.getLatestVersion(file) + " filePath: "
                    + SimpleStorage.getPath(version) + " s3Version: "
                    + SimpleStorage.getS3Version(version));
          }
        }
      } else {
        verId = SimpleStorage.getLatestVersion(file);
        if (returnDownloadUrl) {
          String downloadUrl =
              userS3.getDownloadUrl(SimpleStorage.getPath(file), SimpleStorage.getName(file), null);
          sendDownloadUrl(
              segment, message, downloadUrl, SimpleStorage.getFileSize(file), verId, null, mills);
          return;
        }
        data = getChunk(userS3, SimpleStorage.getPath(file), null, start, end);
        if (data.length == 0) {
          log.info("[SF_VERSION] Missing version DATA on doGetFile -> getChunk. FileId: " + fileId
              + " VersionId: " + verId + " makeFileId: " + SimpleStorage.makeFileId(fileId)
              + " makeVersionId: " + verId + " filePath: " + SimpleStorage.getPath(file));
        }
      }
      if (data.length == 0) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), verId),
            HttpStatus.NOT_FOUND);
        return;
      }

      finishGetFile(
          message,
          start,
          end,
          data,
          storageType,
          SimpleStorage.getName(file),
          verId,
          downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  private byte[] getChunk(
      S3Regional userS3, String path, String versionId, Integer start, Integer end) {
    try {
      byte[] result;
      if (versionId == null) {
        if (start != null) {
          S3Object obj = userS3.getObject(path, start, end);
          result = IOUtils.toByteArray(obj.getObjectContent());
        } else {
          result = userS3.get(path);
        }
      } else {
        if (start != null) {
          S3Object obj = userS3.getFromBucketWithVersion(bucket, path, versionId, start, end);
          result = IOUtils.toByteArray(obj.getObjectContent());
        } else {
          result = userS3.getFromBucketWithVersion(bucket, path, versionId);
        }
      }
      return result;
    } catch (Exception e) {
      log.error(e);
      return new byte[0];
    }
  }

  private Item doSaveFile(
      Message<Buffer> message, JsonObject jsonObject, byte[] data, String presignedUploadId)
      throws KudoFileException {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    if (userId == null) {
      throw new KudoFileException(
          Utils.getLocalizedString(message, "UseridMustBeSpecified"),
          KudoErrorCodes.GENERAL_KUDO_ERROR,
          HttpStatus.BAD_REQUEST,
          "UseridMustBeSpecified");
    }
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    boolean updateUsage = jsonObject.getBoolean("updateUsage", true);
    String uploadRequestId = jsonObject.getString(Field.UPLOAD_REQUEST_ID.getName());
    Item file = null;
    try {
      String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
      boolean isNew = fileId == null;
      String filename = jsonObject.getString(Field.FILE_NAME.getName());
      if (checkFile(fileId, userId)) {
        // save version
        Integer length = jsonObject.getInteger("length", 0);
        Item folder = null;
        Set<String> editors = null, viewers = null;
        String ownerId;
        if (length != null) {
          // create information about file in files collection
          if (isNew) {
            if (!SimpleStorage.checkQuota(
                SimpleStorage.makeUserId(userId),
                length,
                config.getProperties().getDefaultUserOptionsAsJson())) {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "YouDontHaveEnoughSpace"),
                  HttpStatus.FORBIDDEN,
                  "YouDontHaveEnoughSpace");
              return null;
            }
            boolean isTouch = jsonObject
                .getString(Field.DEVICE.getName(), AuthManager.ClientType.BROWSER.name())
                .equals(AuthManager.ClientType.TOUCH.name());

            // if we are creating new file folder id should be specified and it should exists
            // and user should be allowed to work with this folder,
            if (!folderId.equals(ROOT_FOLDER_ID)
                && !folderId.equals(ROOT)
                && !folderId.equals(SHARED_FOLDER)) {
              folder = SimpleStorage.getItem(SimpleStorage.makeFolderId(folderId, userId));
              if (folder == null) {
                sendError(
                    segment,
                    message,
                    MessageFormat.format(
                        Utils.getLocalizedString(message, "FolderWithIdDoesNotExist"), folderId),
                    HttpStatus.NOT_FOUND,
                    "FolderWithIdDoesNotExist");
                return null;
              }
              if (SimpleStorage.isDeleted(folder)) {
                sendError(
                    segment,
                    message,
                    MessageFormat.format(
                        Utils.getLocalizedString(message, "FolderWithIdIsDeleted"), folderId),
                    HttpStatus.BAD_REQUEST,
                    "FolderWithIdIsDeleted");
                return null;
              }
              if (!SimpleStorage.hasRights(
                  folder, SimpleStorage.makeUserId(userId), true, AccessType.canCreateFiles)) {
                sendError(
                    segment,
                    message,
                    MessageFormat.format(
                        Utils.getLocalizedString(message, "UserHasNoAccessWithTypeInFolderWithId"),
                        AccessType.canCreateFiles.label,
                        folderId),
                    HttpStatus.FORBIDDEN,
                    "UserHasNoAccessWithTypeInFolderWithId");
                return null;
              }
              if (SimpleStorage.isShared(folder)) {
                // if the folder is shared and our user != owner, add the owner of the folder as
                // an editor to this folder
                editors = SimpleStorage.getEditors(folder);
                viewers = SimpleStorage.getViewers(folder);

                if (!SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(userId))) {
                  editors.add(SimpleStorage.getOwner(folder));
                  editors.remove(userId);
                }
              }
            } else if (!isTouch
                && (folderId.equals(SHARED_FOLDER) || (folderId.equals(ROOT_FOLDER_ID)))) {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "CouldNotCreateFileOrFolderHere"),
                  HttpStatus.FORBIDDEN,
                  "CouldNotCreateFileOrFolderHere");
              return null;
            }
            // check the name
            if (!SimpleStorage.checkName(
                SimpleStorage.makeFolderId(folderId, userId), filename, true, null)) {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "DuplicateName"),
                  HttpStatus.BAD_REQUEST,
                  "DuplicateName");
              return null;
            }
            if (Utils.isStringNotNullOrEmpty(presignedUploadId)) {
              fileId = presignedUploadId;
            } else {
              fileId = UUID.randomUUID().toString();
            }
            ownerId = userId;
          } else {
            file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
            if (file == null) {
              sendError(
                  segment,
                  message,
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                  HttpStatus.NOT_FOUND,
                  "FileWithIdDoesNotExist");
              return null;
            }
            if (SimpleStorage.isDeleted(file)) {
              sendError(
                  segment,
                  message,
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "FileWithIdIsDeleted"), fileId),
                  HttpStatus.BAD_REQUEST,
                  "FileWithIdIsDeleted");
              return null;
            }
            if (!SimpleStorage.hasRights(
                file, SimpleStorage.makeUserId(userId), true, AccessType.canEdit)) {
              sendError(
                  segment,
                  message,
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                      AccessType.canEdit.label,
                      fileId),
                  HttpStatus.FORBIDDEN,
                  "UserHasNoAccessWithTypeToFileWithId");
              return null;
            }
            ownerId = SimpleStorage.getOwner(file);
          }
          String versionId = null;
          // this is always the path! For sample files - always move
          final String s3Path = file != null && !SimpleStorage.isSampleFile(file)
              ? SimpleStorage.getPath(file)
              : SimpleStorage.generatePath(ownerId, fileId);
          long fileSize = 0;
          S3Regional userS3 = getS3Regional(file, userId);
          try {
            if (!Utils.isStringNotNullOrEmpty(presignedUploadId)) {
              versionId = userS3.putToBucket(userS3.getBucketName(), s3Path, data);
              fileSize = data.length;
            } else {
              ObjectMetadata objectMetadata =
                  userS3.getObjectMetadataOnly(userS3.getBucketName(), s3Path);
              if (Objects.nonNull(objectMetadata)) {
                versionId = objectMetadata.getVersionId();
                fileSize = objectMetadata.getContentLength();
              }
            }
          } catch (SdkClientException e) {
            // this is critical
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "CouldNotSaveDataInS3"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                e);
            return null;
          }
          try {
            String parentId = folderId;
            String owner;
            if (isNew) {
              // for new files - use the path
              file = SimpleStorage.addFile(
                  fileId,
                  SimpleStorage.makeUserId(userId),
                  filename,
                  SimpleStorage.makeFolderId(folderId, userId),
                  versionId,
                  fileSize,
                  s3Path,
                  editors,
                  viewers,
                  userS3.getRegion(),
                  updateUsage);
              if (Utils.isStringNotNullOrEmpty(uploadRequestId)) {
                MultipleUploads.addItem(uploadRequestId, fileId, fileSize);
              }
              // create share items for file from parent collaborators
              if (Objects.nonNull(folder)
                  && SimpleStorage.isShared(folder)
                  && (Objects.nonNull(editors) || Objects.nonNull(viewers))) {
                Item finalFolder = folder;
                Set<String> finalEditors = editors;
                Set<String> finalViewers = viewers;
                Item finalFile = file;
                new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                    .withName("shareOnCreateFile")
                    .runWithoutSegment(() -> {
                      Item newFile = SimpleStorage.getItem(
                          SimpleStorage.makeFileId(SimpleStorage.getItemId(finalFile)));
                      if (Objects.isNull(newFile)) {
                        return;
                      }
                      Set<String> collaborators = new HashSet<>();
                      if (Objects.nonNull(finalEditors)) {
                        collaborators.addAll(finalEditors);
                      }
                      if (Objects.nonNull(finalViewers)) {
                        collaborators.addAll(finalViewers);
                      }
                      for (String collaborator : collaborators) {
                        SimpleStorage.addItemShare(
                            newFile,
                            SimpleStorage.makeUserId(collaborator),
                            SimpleStorage.makeFolderId(
                                SimpleStorage.getItemId(finalFolder), collaborator));
                      }
                    });
              }
              owner = userId;
            } else if (SimpleStorage.isSampleFile(file)) {
              S3Regional sampleS3 = getS3Regional(null, userId);
              // for sample files - always update path!
              SimpleStorage.addFileVersion(
                  file,
                  sampleS3,
                  bucket,
                  versionId,
                  SimpleStorage.makeUserId(userId),
                  s3Path,
                  fileSize,
                  ttl,
                  updateUsage);
              owner = SimpleStorage.stripPrefix(file.getString(Field.OWNER.getName()));
              parentId = ROOT;
            } else {
              // for old files - don't update path
              SimpleStorage.addFileVersion(
                  file,
                  null,
                  null,
                  versionId,
                  SimpleStorage.makeUserId(userId),
                  null,
                  fileSize,
                  ttl,
                  updateUsage);
              owner = SimpleStorage.stripPrefix(file.getString(Field.OWNER.getName()));
              parentId = SimpleStorage.getParent(file, userId);
            }
            if (updateUsage) {
              sendSampleUsageWsNotification(segment, message, owner, true);
            }
            // for new files we already properly get the item data.
            if (!isNew) {
              file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
            }
            // lastModified
            if (!parentId.equals(ROOT_FOLDER_ID)
                && !parentId.equals(ROOT)
                && !parentId.equals(SHARED_FOLDER)) {
              // update lastModified info for all folders in hierarchy
              updateLastModifiedInfo(segment, message, file, userId);
            }
          } catch (Exception ex) {
            sendError(
                segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
            return null;
          }
        }
      } else {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND,
            "FileWithIdDoesNotExist");
        return null;
      }
      recordExecutionTime("saveFile", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
    }
    return file;
  }

  private boolean checkFile(String fileId, String userId) {
    if (fileId != null) {
      Item file = SimpleStorage.getItem(SimpleStorage.makeFileId(fileId));
      if (file == null) {
        return false;
      }
      boolean canEdit = SimpleStorage.hasRights(file, SimpleStorage.makeUserId(userId), true, null);
      boolean isDeleted = SimpleStorage.isDeleted(file);
      return canEdit && !isDeleted;
    } else {
      return true;
    }
  }

  private JsonObject getFolderJson(
      Entity segment,
      Message<JsonObject> message,
      Item folder,
      String currentUserId,
      boolean addShare) {
    String id = SimpleStorage.getItemId(folder);
    if (id.endsWith(SimpleStorage.hashSeparator + ROOT)) {
      id = ROOT;
    } else if (id.endsWith(SimpleStorage.hashSeparator + SHARED_FOLDER)) {
      id = SHARED_FOLDER;
    }
    String updaterId = SimpleStorage.getLatestChanger(folder);
    String updater = Users.getUserNameById(updaterId);
    JsonObject obj = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), SimpleStorage.getAccountId(currentUserId), id))
        .put(Field.NAME.getName(), SimpleStorage.getName(folder))
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                SimpleStorage.getAccountId(currentUserId),
                SimpleStorage.getParent(folder, currentUserId)))
        .put(Field.OWNER_ID.getName(), SimpleStorage.getOwner(folder))
        .put(Field.DELETED.getName(), SimpleStorage.isDeleted(folder))
        .put(Field.CREATION_DATE.getName(), SimpleStorage.getCreationDate(folder))
        .put(Field.UPDATE_DATE.getName(), SimpleStorage.getLatestChangeTime(folder))
        .put(Field.CHANGER.getName(), updater)
        .put(Field.CHANGER_ID.getName(), updaterId);
    JsonObject ownerInfo = Users.getUserInfo(SimpleStorage.getOwner(folder));
    obj.put(Field.OWNER.getName(), ownerInfo.getString(Field.NAME.getName()));
    obj.put(Field.OWNER_EMAIL.getName(), ownerInfo.getString(Field.EMAIL.getName()));
    boolean isOwner = SimpleStorage.checkOwner(folder, SimpleStorage.makeUserId(currentUserId));
    obj.put(Field.IS_OWNER.getName(), isOwner);
    obj.put(Field.CAN_MOVE.getName(), isOwner || SimpleStorage.isItemShare(folder));

    Set<String> editors = new HashSet<>();
    Set<String> viewers = new HashSet<>();
    if (isOwner && addShare) {
      editors = SimpleStorage.getEditors(folder) != null
          ? new HashSet<>(SimpleStorage.getEditors(folder))
          : new HashSet<>();
      viewers = SimpleStorage.getViewers(folder) != null
          ? new HashSet<>(SimpleStorage.getViewers(folder))
          : new HashSet<>();
    } else if (!isOwner) {
      Item sharedFolder = SimpleStorage.getItem(
          SimpleStorage.makeFolderId(SimpleStorage.getItemId(folder), currentUserId));
      if (addShare) {
        editors = SimpleStorage.getEditors(sharedFolder) != null
            ? new HashSet<>(SimpleStorage.getEditors(sharedFolder))
            : new HashSet<>();
      }
      viewers = SimpleStorage.getViewers(sharedFolder) != null
          ? new HashSet<>(SimpleStorage.getViewers(sharedFolder))
          : new HashSet<>();
    }

    obj.put(Field.SHARED.getName(), SimpleStorage.isShared(folder));
    obj.put(Field.VIEW_ONLY.getName(), !isOwner && viewers.contains(currentUserId));
    if (addShare && (!editors.isEmpty() || !viewers.isEmpty())) {
      JsonArray editorNew = new JsonArray();
      if (!editors.isEmpty()) {
        for (String usId : editors) {
          Item user = Users.getUserById(usId);
          if (user != null) {
            editorNew.add(Users.getUserInfo(user));
          }
        }
      }
      JsonArray viewerNew = new JsonArray();
      if (!viewers.isEmpty()) {
        for (String usId : viewers) {
          Item user = Users.getUserById(usId);
          if (user != null) {
            viewerNew.add(Users.getUserInfo(user));
          }
        }
      }
      obj.put(
          Field.SHARE.getName(),
          new JsonObject()
              .put(Field.EDITOR.getName(), editorNew)
              .put(Field.VIEWER.getName(), viewerNew));
    } else {
      obj.put(
          Field.SHARE.getName(),
          new JsonObject()
              .put(Field.EDITOR.getName(), new JsonArray())
              .put(Field.VIEWER.getName(), new JsonArray()));
    }
    PermissionsDao.setObjectPermissions(
        currentUserId, SimpleStorage.getItemId(folder), isOwner, false, obj);

    if (!folder.hasAttribute(Field.LAST_MODIFIED.getName())) {
      AtomicLong lastModified = new AtomicLong(0);
      findAndUpdateLatestModifiedTimeForFolder(folder, currentUserId, lastModified);
      // no items are inside the folder or none of the items have lastModified attribute
      if (lastModified.get() == 0) {
        lastModified.set(folder.getLong(Field.CREATION_DATE.getName()));
      }
      obj.put(Field.UPDATE_DATE.getName(), lastModified.get());
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("UpdateFolderModifiedTime")
          .runWithoutSegment(() -> SimpleStorage.updateModifiedTime(folder, lastModified.get()));
    }
    return obj;
  }

  private void findAndUpdateLatestModifiedTimeForFolder(
      Item folder, String userId, AtomicLong lastModified) {
    for (Item item : SimpleStorage.getFolderItems(SimpleStorage.getItemId(folder), null, null)) {
      if (!SimpleStorage.checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      if ((item.hasAttribute(Field.LAST_MODIFIED.getName())
              && item.getLong(Field.LAST_MODIFIED.getName()) > 0)
          && (lastModified.get() == 0
              || item.getLong(Field.LAST_MODIFIED.getName()) > lastModified.get())) {
        lastModified.set(item.getLong(Field.LAST_MODIFIED.getName()));
      }
      if (SimpleStorage.isFolder(item)) {
        findAndUpdateLatestModifiedTimeForFolder(item, userId, lastModified);
      }
    }
  }

  private void addRootFoldersJson(List<JsonObject> folderJson, String userId) {
    folderJson.add(getMyFilesFolderInfo(userId));
    folderJson.add(getSharedFolderInfo(storageType, userId, SHARED_FILES));
  }

  private void updateLastModifiedInfo(
      Entity segment, Message<?> message, Item item, String userId) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("updateLastModifies")
        .run((Segment blockingSegment) -> {
          SimpleStorage.checkParentAndUpdateModifiedTime(item, userId, GMTHelper.utcCurrentTime());
          SimpleStorage.checkParentAndUpdateModifier(item, userId);
        });
  }

  private void updateLastModifiedInfo(
      Entity segment, Message<?> message, String id, String userId) {
    updateLastModifiedInfo(segment, message, SimpleStorage.getItem(id), userId);
  }

  private JsonObject getFileJson(
      Item file,
      String userId,
      boolean isAdmin,
      boolean addShare,
      boolean force,
      boolean canCreateThumbnails) {
    if (file == null) {
      return null;
    }

    boolean isDeleted = SimpleStorage.isDeleted(file);
    long updateTime = SimpleStorage.getLatestChangeTime(file);
    String updaterId = SimpleStorage.getLatestChanger(file);
    String updater = Users.getUserNameById(updaterId);
    long sizeRaw = SimpleStorage.getFileSize(file);
    String size = Utils.humanReadableByteCount(sizeRaw);
    boolean isOwner = SimpleStorage.checkOwner(file, SimpleStorage.makeUserId(userId));
    boolean isViewer = false;
    String parent = SimpleStorage.getParent(file, userId);
    Item sharingInfo = null;
    // if user isn't owner - it's shared, so we have to check sharing info for parentId
    if (!isOwner) {
      sharingInfo = SimpleStorage.getItemShare(file, SimpleStorage.makeUserId(userId));
      if (sharingInfo != null) {
        parent = SimpleStorage.getParent(sharingInfo, userId);
      }
    }

    JsonObject obj = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                SimpleStorage.getAccountId(userId),
                SimpleStorage.getItemId(file)))
        .put(Field.WS_ID.getName(), SimpleStorage.getItemId(file))
        .put(Field.FILE_NAME.getName(), SimpleStorage.getName(file))
        .put(
            Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), SimpleStorage.getAccountId(userId), parent))
        .put(Field.VERSION_ID.getName(), SimpleStorage.getLatestVersion(file))
        .put(Field.OWNER_ID.getName(), SimpleStorage.getOwner(file))
        .put(Field.DELETED.getName(), isDeleted)
        .put(Field.CREATION_DATE.getName(), SimpleStorage.getCreationDate(file))
        .put(Field.UPDATE_DATE.getName(), updateTime)
        .put(Field.CHANGER.getName(), updater)
        .put(Field.CHANGER_ID.getName(), updaterId)
        .put(Field.SIZE.getName(), size)
        .put(Field.SIZE_VALUE.getName(), sizeRaw);
    String thumbnailName = ThumbnailsManager.getThumbnailName(
        StorageType.getShort(storageType),
        SimpleStorage.getItemId(file),
        SimpleStorage.getLatestVersion(file));
    String previewId = ThumbnailsManager.getPreviewName(
        StorageType.getShort(storageType), SimpleStorage.getItemId(file));
    boolean encode = true;

    if (SimpleStorage.isSampleFile(file)
        && Utils.isStringNotNullOrEmpty(SimpleStorage.getPath(file))) {
      thumbnailName = ThumbnailsManager.getPublicFileThumbnailS3Path(SimpleStorage.getPath(file));
      previewId = ThumbnailsManager.getPublicFilePreviewS3Path(SimpleStorage.getPath(file));
      encode = false;
    }
    obj.put(Field.PREVIEW_ID.getName(), previewId)
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
    if (Extensions.isThumbnailExt(config, SimpleStorage.getName(file), isAdmin)) {
      // AS : Removing this temporarily until we have some server cache (WB-1248)
      //                    .put("thumbnailStatus", getThumbnailStatus(SimpleStorage
      //                    .getItemId(file), storageType.name(), SimpleStorage
      //                    .getLatestVersion(file), force, canCreateThumbnails))
      obj.put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, encode))
          .put(
              Field.GEOMDATA.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", encode))
          .put(Field.PREVIEW.getName(), ThumbnailsManager.getPreviewURL(config, previewId, encode));
    }
    JsonObject ownerInfo = Users.getUserInfo(SimpleStorage.getOwner(file));
    obj.put(Field.OWNER.getName(), ownerInfo.getString(Field.NAME.getName()));
    obj.put(Field.OWNER_EMAIL.getName(), ownerInfo.getString(Field.EMAIL.getName()));
    obj.put("grey", !isOwner);
    obj.put(Field.IS_OWNER.getName(), isOwner);
    obj.put(
        Field.CAN_MOVE.getName(),
        isOwner || SimpleStorage.isItemShare(file) || sharingInfo != null);

    Set<String> editors = new HashSet<>();
    Set<String> viewers = new HashSet<>();
    if (isOwner && addShare) {
      editors = SimpleStorage.getEditors(file) != null
          ? new HashSet<>(SimpleStorage.getEditors(file))
          : new HashSet<>();
      viewers = SimpleStorage.getViewers(file) != null
          ? new HashSet<>(SimpleStorage.getViewers(file))
          : new HashSet<>();
    } else if (!isOwner) {
      Item sharedFile =
          SimpleStorage.getItem(SimpleStorage.makeFileId(SimpleStorage.getItemId(file)));
      if (addShare) {
        editors = SimpleStorage.getEditors(sharedFile) != null
            ? new HashSet<>(SimpleStorage.getEditors(sharedFile))
            : new HashSet<>();
      }
      viewers = SimpleStorage.getViewers(sharedFile) != null
          ? new HashSet<>(SimpleStorage.getViewers(sharedFile))
          : new HashSet<>();
      isViewer = viewers.contains(userId);
    }

    obj.put(Field.SHARED.getName(), SimpleStorage.isShared(file));
    obj.put(Field.VIEW_ONLY.getName(), isViewer);
    if (addShare && (!editors.isEmpty() || !viewers.isEmpty())) {
      JsonArray editorNew = new JsonArray();
      if (!editors.isEmpty()) {
        for (String usId : editors) {
          if (Utils.isStringNotNullOrEmpty(usId)) {
            Item user = Users.getUserById(usId);
            if (user != null) {
              editorNew.add(Users.getUserInfo(user));
            }
          }
        }
      }
      JsonArray viewerNew = new JsonArray();
      if (!viewers.isEmpty()) {
        for (String usId : viewers) {
          if (Utils.isStringNotNullOrEmpty(usId)) {
            Item user = Users.getUserById(usId);
            if (user != null) {
              viewerNew.add(Users.getUserInfo(user));
            }
          }
        }
      }
      obj.put(
          Field.SHARE.getName(),
          new JsonObject()
              .put(Field.EDITOR.getName(), editorNew)
              .put(Field.VIEWER.getName(), viewerNew));
    } else {
      obj.put(
          Field.SHARE.getName(),
          new JsonObject()
              .put(Field.EDITOR.getName(), new JsonArray())
              .put(Field.VIEWER.getName(), new JsonArray()));
    }
    // if file is public
    JsonObject PLData = findLinkForFile(
        SimpleStorage.getItemId(file),
        SimpleStorage.getAccountId(userId),
        userId,
        !isOwner && viewers.contains(userId));
    obj.put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()));
    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      obj.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    PermissionsDao.setObjectPermissions(userId, SimpleStorage.getItemId(file), isOwner, true, obj);
    return obj;
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject jsonObject = message.body();
      String id = jsonObject.getString(Field.FILE_ID.getName());

      // check if object exists and user has permission
      Item obj = SimpleStorage.getItem(SimpleStorage.makeFileId(id));
      if (obj == null) {
        // if file isn't found - return true for RF validation
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.IS_DELETED.getName(), true)
                .put("nativeResponse", "Object doesn't exist"),
            mills);
        return;
      }
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.IS_DELETED.getName(), SimpleStorage.isDeleted(obj)),
          mills);
      recordExecutionTime("doGetTrashStatus", System.currentTimeMillis() - mills);
    } catch (Exception e) {
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", e.getMessage()),
          mills);
    }
  }

  protected JsonObject getMyFilesFolderInfo(final String externalId) {
    final ObjectPermissions objectPermissions = new ObjectPermissions()
        .setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            true);
    JsonObject ownerInfo = Users.getUserInfo(externalId);
    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(SimpleFluorine.storageType),
                SimpleStorage.getAccountId(externalId),
                ROOT))
        .put(Field.WS_ID.getName(), ROOT)
        .put(Field.NAME.getName(), MY_FILES)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(SimpleFluorine.storageType),
                SimpleStorage.getAccountId(externalId),
                ROOT_FOLDER_ID))
        .put(Field.OWNER_ID.getName(), externalId)
        .put(OWNER, ownerInfo.getString(Field.NAME.getName()))
        .put(Field.OWNER_EMAIL.getName(), ownerInfo.getString(Field.EMAIL.getName()))
        .put(Field.CREATION_DATE.getName(), 0)
        .put(Field.UPDATE_DATE.getName(), 0)
        .put(Field.CHANGER.getName(), Users.getUserNameById(externalId))
        .put(Field.CHANGER_ID.getName(), externalId)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), false)
        .put(Field.VIEW_ONLY.getName(), false)
        .put(Field.IS_OWNER.getName(), true)
        .put(Field.IS_DELETED.getName(), false)
        .put(Field.CAN_MOVE.getName(), false)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()))
        .put(Field.EXTERNAL_PUBLIC.getName(), false)
        .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
        .put(Field.PUBLIC.getName(), false);
  }

  private Set<String> getAllItemOwnersInFolder(
      Item folder, String userId, Set<String> parentCollaborators) {
    Set<String> owners = new HashSet<>();
    for (Item item : SimpleStorage.getFolderItems(
        SimpleStorage.makeFolderId(SimpleStorage.getItemId(folder), userId), null, null)) {
      String itemOwner = SimpleStorage.getOwner(item);
      if (!itemOwner.equals(userId) && !parentCollaborators.contains(itemOwner)) {
        owners.add(itemOwner);
      }
      if (SimpleStorage.isFolder(item)) {
        owners.addAll(getAllItemOwnersInFolder(item, userId, parentCollaborators));
      }
    }
    return owners;
  }

  private void doCreatePresignedUrl(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String fileId = body.getString("fileId");
    final String userId = body.getString("userId");
    final String contentType = body.getString("contentType");
    try {
      String key = SimpleStorage.generatePath(userId, fileId);
      S3Regional storageS3 = getS3Regional(null, userId);
      URL presignedUrl =
          storageS3.createPresignedUrlToUploadFile(storageS3.getBucketName(), key, contentType);
      sendOK(
          segment, message, new JsonObject().put("presignedUrl", presignedUrl.toString()), mills);
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    }
  }

  private void doRequestMultipleUpload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(Field.USER_ID.getName());
    boolean begin = body.getBoolean("begin");
    String uploadRequestId;
    try {
      if (begin) {
        uploadRequestId = MultipleUploads.createUploadRequest(userId);
        UploadHelper uploadHelper = new UploadHelper(uploadRequestId, userId);
        uploadHelper.start();
        sendOK(
            segment,
            message,
            new JsonObject().put(Field.UPLOAD_REQUEST_ID.getName(), uploadRequestId),
            mills);
      } else {
        uploadRequestId = body.getString(Field.UPLOAD_REQUEST_ID.getName());
        if (Utils.isStringNotNullOrEmpty(uploadRequestId)) {
          long totalUploadedSize = calculateAndGetTotalUploadedSize(uploadRequestId);
          if (totalUploadedSize != 0) {
            SimpleStorage.updateUsage(SimpleStorage.makeUserId(userId), totalUploadedSize);
            sendSampleUsageWsNotification(segment, message, userId, true);
          }
          MultipleUploads.deleteUploadRequest(uploadRequestId, userId);
          sendOK(segment, message, mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "RequestIdRequiredToFinishUpload"),
              HttpStatus.FORBIDDEN,
              "RequestIdRequiredToFinishUpload");
        }
      }
    } catch (Exception ex) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
    }
  }

  public long calculateAndGetTotalUploadedSize(String uploadRequestId) {
    long totalUploadedSize = 0;
    Iterator<Item> itemIterator = MultipleUploads.getAllUploadedItemsByRequestId(uploadRequestId);
    if (itemIterator.hasNext()) {
      while (itemIterator.hasNext()) {
        Item item = itemIterator.next();
        if (item.hasAttribute(Field.SIZE.getName())) {
          long itemSize = item.getLong(Field.SIZE.getName());
          totalUploadedSize += itemSize;
        }
      }
    }
    return totalUploadedSize;
  }

  private void doSendSampleUsageWSNotification(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = message.body().getString(Field.USER_ID.getName());
    sendSampleUsageWsNotification(segment, message, userId, true);
    sendOK(segment, message);
  }

  public void sendSampleUsageWsNotification(
      Entity segment, Message<?> message, String userId, boolean isBlocking) {
    if (isBlocking) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("sendUsageWsNotification")
          .runWithoutSegment(() -> sendUsageWsNotification(
              userId, storageType.name(), SimpleStorage.getExternalId(userId)));
    } else {
      sendUsageWsNotification(userId, storageType.name(), SimpleStorage.getExternalId(userId));
    }
  }

  public void doValidateFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = message.body().getString(Field.USER_ID.getName());
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("ValidateKudoDriveFiles")
        .run((Segment blockingSegment) -> {
          AtomicLong totalUsage = new AtomicLong(0);
          try {
            ItemCollection<QueryOutcome> files =
                SimpleStorage.getAllFilesOwnedByUser(SimpleStorage.makeUserId(userId));
            ItemCollection<QueryOutcome> folders =
                SimpleStorage.getAllFoldersOwnedByUser(SimpleStorage.makeUserId(userId));
            Iterable<Item> items = Iterables.concat(files, folders);
            items.forEach(item -> {
              String parentId = SimpleStorage.getParent(item, userId);
              while (!parentId.equals(ROOT)) {
                Item parent = SimpleStorage.getItem(SimpleStorage.makeFolderId(parentId, userId));
                if (parent == null) {
                  SimpleStorage.eraseFile(item, ttl);
                  return;
                }
                parentId = SimpleStorage.getParent(parent, userId);
              }
              if (SimpleStorage.isFile(item)) {
                if (!SimpleStorage.isSampleFile(item)) {
                  totalUsage.addAndGet(SimpleStorage.getFileSize(item));
                } else {
                  S3Regional sampleS3 = getS3Regional(null, userId);
                  if (SimpleStorage.isSampleFileModified(item, sampleS3, bucket)) {
                    totalUsage.addAndGet(SimpleStorage.getFileSize(item));
                  }
                }
              }
            });
            SimpleStorage.setUsage(SimpleStorage.makeUserId(userId), totalUsage.get());
            sendSampleUsageWsNotification(blockingSegment, message, userId, false);
            Users.setFilesLastValidated(userId);
            log.info("KD files validated successfully for user " + userId);
          } catch (Exception ex) {
            log.error(
                "Something went wrong in validating KD files for user " + userId + " : " + ex);
          }
        });
    sendOK(segment, message);
  }

  private void removeCustomPermissions(
      Entity segment,
      Message<JsonObject> message,
      Set<String> collaborators,
      boolean isFolder,
      Item object,
      String userId) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("removeCustomPermissions")
        .runWithoutSegment(() -> {
          Set<String> finalCollaborators;
          if (Objects.isNull(collaborators)) {
            finalCollaborators = getAllObjectCollaborators(object);
          } else {
            finalCollaborators = collaborators;
          }
          for (String collaborator : finalCollaborators) {
            Item sharedUser = Users.getUserById(collaborator);
            if (Objects.nonNull(sharedUser)) {
              PermissionsDao.deleteCustomPermissions(
                  sharedUser.getString(Dataplane.sk), SimpleStorage.getItemId(object));
              if (isFolder) {
                addOrDeleteFolderPermissionsForSharedUsers(
                    SimpleStorage.getItemId(object),
                    userId,
                    sharedUser.getString(Dataplane.sk),
                    null,
                    null,
                    false);
              }
            }
          }
        });
  }

  private void copyShareData(
      Message<JsonObject> message,
      Item currentItem,
      String userId,
      String newItemId,
      boolean clonedToRoot,
      boolean isFolder) {
    List<String> editors = null, viewers = null;
    // add editors
    Set<String> currentFileEditors = SimpleStorage.getEditors(currentItem);
    if (Objects.nonNull(currentFileEditors) && !currentFileEditors.isEmpty()) {
      editors = new ArrayList<>(currentFileEditors);
      if (clonedToRoot) {
        editors.add(SimpleStorage.getOwner(currentItem));
        editors.remove(userId);
      }
    }
    // add viewers
    Set<String> currentFileViewers = SimpleStorage.getViewers(currentItem);
    if (Objects.nonNull(currentFileViewers) && !currentFileViewers.isEmpty()) {
      viewers = new ArrayList<>(currentFileViewers);
      if (clonedToRoot) {
        viewers.remove(userId);
      }
    }
    JsonArray finalEditors = new JsonArray();
    JsonArray finalViewers = new JsonArray();
    Set<JsonObject> customPermissions = new HashSet<>();
    if (Objects.nonNull(editors)) {
      editors.forEach(editorId -> {
        JsonObject userObject = Users.getUserInfo(editorId);
        String userEmail = userObject.getString(Field.EMAIL.getName());
        if (Utils.isStringNotNullOrEmpty(userEmail)) {
          List<String> userCustomPermissions =
              PermissionsDao.getCustomPermissions(editorId, SimpleStorage.getItemId(currentItem));
          if (Objects.nonNull(userCustomPermissions)) {
            customPermissions.add(new JsonObject().put(userEmail, userCustomPermissions));
          }
          finalEditors.add(userEmail);
        }
      });
    }
    if (Objects.nonNull(viewers)) {
      viewers.forEach(viewerId -> {
        JsonObject userObject = Users.getUserInfo(viewerId);
        String userEmail = userObject.getString(Field.EMAIL.getName());
        if (Utils.isStringNotNullOrEmpty(userEmail)) {
          List<String> userCustomPermissions =
              PermissionsDao.getCustomPermissions(viewerId, SimpleStorage.getItemId(currentItem));
          if (Objects.nonNull(userCustomPermissions)) {
            customPermissions.add(new JsonObject().put(userEmail, userCustomPermissions));
          }
          finalViewers.add(userEmail);
        }
      });
    }
    if (!finalEditors.isEmpty() || !finalViewers.isEmpty()) {
      message
          .body()
          .put(Field.FOLDER_ID.getName(), newItemId)
          .put(Field.IS_FOLDER.getName(), isFolder)
          .put(Field.EDITOR.getName(), finalEditors)
          .put(Field.VIEWER.getName(), finalViewers);
      if (!customPermissions.isEmpty()) {
        message.body().put(Field.CUSTOM_PERMISSIONS.getName(), customPermissions);
      }
      doShare(message, false);
    }
  }
}
