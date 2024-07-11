package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.common.collect.Lists;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Xref;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.logs.ConflictingFileLogger;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.stats.logs.file.FileActions;
import com.graebert.storage.stats.logs.file.FileLog;
import com.graebert.storage.storage.Attachments;
import com.graebert.storage.storage.Comments;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.thumbnails.ThumbnailsDao;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipOutputStream;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.NonNls;

public class BaseStorage extends DynamoBusModBase {
  @NonNls
  public static final String SHARED_FOLDER = Field.SHARED.getName();

  @NonNls
  public static final String ROOT_FOLDER_ID = Field.MINUS_1.getName();

  @NonNls
  public static final String OWNER = Field.OWNER.getName();

  @NonNls
  public static final String ROOT = Field.ROOT.getName();

  @NonNls
  public static final String MY_FILES = "My files";

  public static final String unknownDrawingName = "Unknown.dwg";
  public static final int MAX_RECURSION_DEPTH = 20;
  public static final int MAXIMUM_DOWNLOAD_CHUNK_SIZE = 50 * 1024 * 1024; // ~50 MB
  public static final int MAXIMUM_DOWNLOAD_FILE_SIZE = 800 * 1024 * 1024; // ~800 MB
  public static final int ZIP_FOLDER_MAX_TIME_LIMIT = 600;

  @NonNls
  public static final String VERSION_ID = Field.VERSION_ID.getName();

  private static final OperationGroup operationGroup = OperationGroup.BASE_STORAGE;
  private static final String conflictingPostFix = "_conflicting_";
  private static final int MINIMUM_DOWNLOAD_CHUNK_SIZE = 100 * 1024; // ~300 KB
  private static final int MINIMUM_DOWNLOAD_CHUNK_SIZE_5_try = 50 * 1024; // ~150 KB
  private static final int MINIMUM_DOWNLOAD_CHUNK_SIZE_10_try = 20 * 1024; // ~50 KB
  private static final int UPLOAD_REQUEST_TRY_LIMIT = 10;

  public static JsonObject findLinkForFile(
      final String fileId, final String externalId, final String userId, final boolean viewOnly) {
    JsonObject responseData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    try {
      final PublicLink publicLink = new PublicLink(fileId, externalId, userId);
      final boolean isPublic = publicLink.fetchLinkInfo() && publicLink.isEnabled();
      responseData.put(Field.IS_PUBLIC.getName(), isPublic);
      if (isPublic && !viewOnly) {
        responseData.put(Field.LINK.getName(), publicLink.getEndUserLink());
        responseData.put(Field.EXPORT.getName(), publicLink.getExport());
        responseData.put(Field.LINK_END_TIME.getName(), publicLink.getExpirationTime());
        responseData.put(Field.PUBLIC_LINK_INFO.getName(), publicLink.getInfoInJSON());
      }
    } catch (PublicLinkException e) {
      // basically ignore this
      log.warn(e);
    }
    return responseData;
  }

  public static boolean isConflictingChange(
      String userId,
      String fileId,
      StorageType storageType,
      String versionId,
      String baseChangeId) {
    ConflictingFileLogger logger = new ConflictingFileLogger();
    logger.log(
        Level.INFO,
        userId,
        fileId,
        storageType,
        "changeIdCheck",
        String.format(
            "Check started, baseChangeId is%s specified",
            Utils.isStringNotNullOrEmpty(baseChangeId) ? " " : " not"));

    // if changeId is provided - check using it
    if (Utils.isStringNotNullOrEmpty(baseChangeId)) {
      logger.log(
          Level.INFO,
          userId,
          fileId,
          storageType,
          "changeIdCheck",
          String.format("Checking baseChangeId: %s with versionId: %s", baseChangeId, versionId));
      boolean equals = baseChangeId.equals(versionId);
      if (!equals) {
        logger.log(
            Level.ERROR,
            userId,
            fileId,
            storageType,
            "changeIdCheck",
            "baseChangeId and versionId are not equal");
      }
      return !equals;
    } else {
      // let's check metadata
      Item metadata = FileMetaData.getMetaData(fileId, storageType.toString());

      if (Objects.isNull(metadata) || !metadata.hasAttribute(Field.VERSION_ID.getName())) {
        if (storageType.equals(StorageType.WEBDAV)) {
          logger.log(
              Level.INFO,
              userId,
              fileId,
              storageType,
              "changeIdCheck",
              "Metadata cant be checked as WebDav may have no versionId");
        } else {
          logger.log(
              Level.ERROR,
              userId,
              fileId,
              storageType,
              "changeIdCheck",
              ("Metadata cant be checked: not specified or versionId is empty. Change is not "
                      + "conflicted, metadata: ")
                  .concat(Objects.nonNull(metadata) ? metadata.toJSONPretty() : "null"));
        }
        return false;
      } else {
        logger.log(
            Level.INFO,
            userId,
            fileId,
            storageType,
            "changeIdCheck",
            String.format("Checking metadata. Metadata is specified: %s", metadata.toJSONPretty()));
      }

      logger.log(
          Level.INFO,
          userId,
          fileId,
          storageType,
          "changeIdCheck",
          String.format(
              "Checking metadataVersionId: %s with versionId: %s",
              metadata.getString(Field.VERSION_ID.getName()), versionId));
      boolean equals = metadata.getString(Field.VERSION_ID.getName()).equals(versionId);

      if (!equals) {
        logger.log(
            Level.ERROR,
            userId,
            fileId,
            storageType,
            "changeIdCheck",
            "metadataVersionId and versionId are not equal");
      }

      return !equals;
    }
  }

  protected PublicLink initializePublicLink(
      String fileId,
      String externalId,
      String userId,
      StorageType storageType,
      String linkOwnerIdentity,
      String filename,
      boolean export,
      long endTime,
      String password)
      throws PublicLinkException {
    PublicLink newLink = new PublicLink(fileId, externalId, userId);

    newLink.setStorageType(storageType.toString());
    newLink.setFilename(filename);
    newLink.setExport(export);
    newLink.setLinkOwnerIdentity(linkOwnerIdentity);
    if (endTime != 0) {
      newLink.setEndTime(endTime);
    }
    if (Utils.isStringNotNullOrEmpty(password)) {
      newLink.setPassword(password);
    } // if password is empty - just ignore it
    return newLink;
  }

  protected boolean getLinkExportStatus(String fileId, String externalId, String userId)
      throws PublicLinkException {
    PublicLink fileLink = new PublicLink(fileId, externalId, userId);
    return fileLink.fetchLinkInfo() && fileLink.getExport();
  }

  /**
   * Will remove public link and trigger side-effects - e.g. remove subscriptions used with link
   * and return link owner's identity (email | userId | shareId)
   *
   * @param fileId     {String}
   * @param externalId {String}
   * @param userId     {String}
   */
  protected String deleteSharedLink(
      final Entity segment,
      final Message<JsonObject> message,
      final String fileId,
      final String externalId,
      final String userId)
      throws PublicLinkException {
    PublicLink publicLink = new PublicLink(fileId, externalId, userId);
    String linkOwnerIdentify = null;
    if (publicLink.fetchLinkInfo()) {
      try {
        final String token = publicLink.getToken();
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("removePublicSubscriptions")
            .run((Segment blockingSegment) -> eb_send(
                blockingSegment,
                Subscriptions.address + ".removePublicSubscriptions",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.TOKEN.getName(), token)));
        linkOwnerIdentify = publicLink.getLinkOwnerIdentity();
      } catch (PublicLinkException ple) {
        // just ignore this - nothing bad will happen if link doesn't exist
      }
    }

    try {
      // if sk is incomplete - it'll throw exception
      publicLink.delete();
    } catch (PublicLinkException publicLinkException) {
      // this is dangerous (see method's description)
      // if fileId or userId is undefined or link isn't found - new exception will be thrown
      PublicLink.queryDelete(fileId, userId);
    }

    return linkOwnerIdentify;
  }

  /**
   * This will remove subscription for the file that isn't accessible anymore.
   *
   * @param fileId {String}
   * @param userId {String}
   */
  protected void deleteSubscriptionForUnavailableFile(final String fileId, final String userId) {
    if (Utils.isStringNotNullOrEmpty(fileId) && Utils.isStringNotNullOrEmpty(userId)) {
      Entity subSegment =
          XRayManager.createSubSegment(operationGroup, "deleteSubscriptionForUnavailableFile");
      eb_request(
          subSegment,
          Subscriptions.address + ".deleteSubscription",
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.USER_ID.getName(), userId)
              .put("manual", false),
          response -> {
            XRayManager.endSegment(subSegment);
          });
    }
  }

  /**
   * Will return if name is allowed.
   *
   * @param initialName       - name to validate
   * @param specialCharacters - illegal characters
   * @return true if name is fine, false if it's empty/null
   * @throws IllegalArgumentException if check failed for some reason
   */
  protected boolean validateObjectName(final String initialName, final String[] specialCharacters)
      throws IllegalArgumentException {
    if (Utils.isStringNotNullOrEmpty(initialName)) {
      String onlyDotsInName = initialName;
      onlyDotsInName = onlyDotsInName.replaceAll("\\.", emptyString);
      if (onlyDotsInName.isEmpty() || onlyDotsInName.equals("dwg")) {
        throw new IllegalArgumentException("onlyDotsInName");
      }
      for (String specialCharacter : specialCharacters) {
        if (initialName.contains(specialCharacter)) {
          throw new IllegalArgumentException("specialCharacters");
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Logs issues with storage connection.
   *
   * @param storageType  type of storage
   * @param userId       user who requested connect
   * @param connectionId externalId
   * @param reason       why connect has failed
   */
  protected void logConnectionIssue(
      final StorageType storageType,
      final String userId,
      final String connectionId,
      final ConnectionError reason) {
    log.error("Storage connection issue: storageType: " + storageType.name() + " connectionId: "
        + connectionId + " userId: " + userId + " reason: " + reason.name());
  }

  protected String getConflictingFileName(final String originalFileName) {
    String ext = Utils.safeSubstringFromLastOccurrence(originalFileName, ".");
    if (originalFileName.contains(conflictingPostFix)) {
      return originalFileName.substring(0, originalFileName.lastIndexOf("_") + 1)
          + GMTHelper.utcCurrentTime()
          + ext;
    }
    return originalFileName.substring(0, originalFileName.lastIndexOf("."))
        + conflictingPostFix
        + GMTHelper.utcCurrentTime()
        + ext;
  }

  protected void handleConflictingFile(
      Entity parentSegment,
      Message<Buffer> message,
      JsonObject object,
      final String oldName,
      final String newName,
      final String originalFileId,
      final String newFileId,
      String fileSessionId,
      String userId,
      String modifier,
      String conflictingFileReason,
      boolean fileSessionExpired,
      boolean isSameFolder,
      AuthManager.ClientType clientType) {
    JsonObject origParsedId = Utils.parseItemId(originalFileId);
    JsonObject newParsedId = Utils.parseItemId(newFileId);

    if (newParsedId != null
        && Utils.isStringNotNullOrEmpty(newParsedId.getString(Field.ID.getName()))
        && origParsedId != null
        && Utils.isStringNotNullOrEmpty(origParsedId.getString(Field.ID.getName()))) {
      String shortNewFileId = newParsedId.getString(Field.ID.getName());
      String shortOrigFileId = origParsedId.getString(Field.ID.getName());
      boolean isNotKudo = !clientType.equals(AuthManager.ClientType.BROWSER)
          && !clientType.equals(AuthManager.ClientType.BROWSER_MOBILE);
      if (object.containsKey("fileSessionExpired")) {
        // For Kudo - it's hard to handle session transferring
        // so instead of transfer - we'll just kill the existing session and
        // will create new one manually later on
        if (isNotKudo) {
          // create a new session for the new conflicting file with same fileSessionId
          XenonSessions.createSession(
              shortNewFileId,
              userId,
              object.getString("userName"),
              XenonSessions.Mode.EDIT,
              object.getString(Field.SESSION_ID.getName()),
              Sessions.getXSessionTTL(),
              StorageType.getStorageType(object.getString(Field.STORAGE_TYPE.getName())),
              object.getString(Field.DEVICE.getName()),
              object.getString(Field.EXTERNAL_ID.getName()),
              fileSessionId,
              newName,
              object.getString(Field.EMAIL.getName()),
              object.getString(Field.F_NAME.getName()),
              object.getString(Field.SURNAME.getName()));
        }

        Item session = XenonSessions.getSession(shortOrigFileId, fileSessionId);
        if (Objects.nonNull(session)
            && (!session.hasAttribute(Field.MODE.getName())
                || session
                    .getString(Field.MODE.getName())
                    .equalsIgnoreCase(XenonSessions.Mode.VIEW.name()))) {
          // delete old file session if the mode changed to view or there is no session mode
          XenonSessions.deleteSession(shortOrigFileId, fileSessionId);
        }
      } else {
        // transfer the file session
        // For Kudo - same as above.
        // Just downgrade existing session
        Item session = XenonSessions.getSession(shortOrigFileId, fileSessionId);
        if (session != null) {
          if (isNotKudo) {
            XenonSessions.transferSession(shortOrigFileId, shortNewFileId, session);
          } else {
            XenonSessions.downgradeSession(session);
          }
        }
      }
    }
    // notify the user
    new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, message)
        .withName("handleConflictingFile")
        .run((Segment blockingSegment) -> {
          FileLog fileLog = new FileLog(
              userId,
              originalFileId,
              newFileId,
              StorageType.getStorageType(object.getString(Field.STORAGE_TYPE.getName())),
              XSessionManager.ConflictingFileReason.getReason(conflictingFileReason),
              GMTHelper.utcCurrentTime(),
              true,
              FileActions.CONFLICTING_FILE,
              GMTHelper.utcCurrentTime(),
              oldName,
              object.getString(Field.SESSION_ID.getName()),
              fileSessionId,
              clientType.name(),
              modifier,
              fileSessionExpired);
          fileLog.sendToServer();

          XRayEntityUtils.putMetadata(blockingSegment, XrayField.FILE_LOG, fileLog.toJson());
          XRayEntityUtils.putMetadata(blockingSegment, XrayField.USER_ID, userId);
          XRayEntityUtils.putMetadata(blockingSegment, XrayField.ORIGINAL_FILE_ID, originalFileId);
          XRayEntityUtils.putMetadata(blockingSegment, XrayField.NEW_FILE_ID, newFileId);

          if (conflictingFileReason.equalsIgnoreCase(
              XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name())) {
            eb_send(
                blockingSegment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), originalFileId)
                    .put(Field.ACCESSIBLE.getName(), false)
                    .put(
                        Field.STORAGE_TYPE.getName(),
                        object.getString(Field.STORAGE_TYPE.getName())));
          }

          eb_send(
              blockingSegment,
              MailUtil.address + ".notifySaveFailed",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.FILE_NAME.getName(), oldName)
                  .put("newName", newName)
                  .put(Field.FILE_ID.getName(), originalFileId)
                  .put("conflictReason", conflictingFileReason)
                  .put("isSameFolder", isSameFolder)
                  .put("newFileId", newFileId)
                  .put(
                      "anotherUser",
                      Utils.isStringNotNullOrEmpty(modifier)
                          ? modifier
                          : Utils.getLocalizedString(
                              object.getString(Field.LOCALE.getName()), "anotheruser")));
        });
  }

  protected String getStorageObject(
      final String storageType,
      final String externalId,
      final String serverLocation,
      final List<String> regionLocations) {
    JsonObject storageObject = new JsonObject()
        .put(Field.STORAGE_TYPE.getName(), storageType)
        .put(Field.ID.getName(), externalId);
    if (Utils.isStringNotNullOrEmpty(serverLocation)) {
      storageObject.put(Field.SERVER.getName(), serverLocation);
    }
    if (Utils.isListNotNullOrEmpty(regionLocations)) {
      storageObject.put(Field.REGIONS.getName(), regionLocations);
    }
    return storageObject.encode();
  }

  protected String getStorageObject(
      final StorageType storageType,
      final String externalId,
      final String serverLocation,
      final List<String> regionLocations) {
    return getStorageObject(storageType.toString(), externalId, serverLocation, regionLocations);
  }

  protected void sendDownloadUrl(
      final Entity segment,
      final Message<JsonObject> message,
      final String downloadUrl,
      final Long fileSize,
      final String versionId,
      final ByteArrayOutputStream stream,
      long mills) {
    sendDownloadUrl(segment, message, downloadUrl, fileSize, versionId, null, null, stream, mills);
  }

  protected void sendDownloadUrl(
      final Entity segment,
      final Message<JsonObject> message,
      final String downloadUrl,
      final Long fileSize,
      final String versionId,
      final String accessToken,
      final Boolean isBearerToken,
      final ByteArrayOutputStream stream,
      long mills) {
    final String storageType = message.body().getString(Field.STORAGE_TYPE.getName());
    log.info("Returning file download url - " + message.body().getString(Field.FILE_ID.getName())
        + " | storage - " + storageType + " | versionId - " + versionId);

    JsonObject response = new JsonObject()
        .put(Field.DOWNLOAD_URL.getName(), downloadUrl)
        .put(Field.STORAGE_TYPE.getName(), storageType);
    if (Objects.nonNull(fileSize) && fileSize > 0) {
      response.put(Field.FILE_SIZE.getName(), fileSize);
    }
    if (Utils.isStringNotNullOrEmpty(versionId)) {
      response.put(Field.VERSION_ID.getName(), versionId);
    }
    if (Utils.isStringNotNullOrEmpty(accessToken)) {
      response.put(Field.ACCESS_TOKEN.getName(), accessToken);
    }
    if (Objects.nonNull(isBearerToken)) {
      response.put("isBearerToken", isBearerToken);
    }

    sendOK(segment, message, response, mills);
    if (Objects.nonNull(stream)) {
      try {
        stream.close();
      } catch (IOException ignore) {
        // ignored stream IOE
      }
    }
  }

  protected void finishGetFile(
      Message<JsonObject> message,
      Integer start,
      Integer end,
      byte[] result,
      StorageType storageType,
      String name,
      String versionId,
      String downloadToken) {
    log.info("Finishing Get File - " + message.body().getString(Field.FILE_ID.getName())
        + " | storage - " + storageType.name() + " | versionId - " + versionId);
    JsonObject responseJson = new JsonObject();
    byte[] endData = result;
    if (start != null && start >= 0) {
      int length = result.length;
      if (end != null && end > start) {
        endData = Arrays.copyOfRange(result, start, end + 1);
      } else {
        endData = Arrays.copyOfRange(result, start, length);
      }
      responseJson.put("length", length);
    }
    responseJson.put(Field.VER_ID.getName(), versionId);
    if (name != null) {
      responseJson.put(Field.NAME.getName(), name);
    }
    responseJson.put(Field.STATUS.getName(), OK);
    Buffer jsonBuffer = responseJson.toBuffer();
    Buffer endBuffer = Buffer.buffer();
    endBuffer.appendInt(jsonBuffer.length());
    endBuffer.appendBuffer(jsonBuffer);
    endBuffer.appendBytes(endData);
    if (downloadToken != null) {
      log.info("Setting file data for download token - " + downloadToken);
      DownloadRequests.setRequestData(
          downloadToken, endBuffer.getBytes(), JobStatus.SUCCESS, null, null);
    }
    message.reply(endBuffer);
  }

  protected JsonObject getSharedFolderInfo(
      final StorageType storageType, final String externalId, final String sharedFolderName) {
    ObjectPermissions objectPermissions = new ObjectPermissions(); // all defaults are false
    JsonObject info = new JsonObject();
    info.put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                (storageType.equals(StorageType.SAMPLES))
                    ? SimpleStorage.getAccountId(externalId)
                    : externalId,
                SHARED_FOLDER))
        .put(Field.WS_ID.getName(), SHARED_FOLDER)
        .put(Field.NAME.getName(), sharedFolderName)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                (storageType.equals(StorageType.SAMPLES))
                    ? SimpleStorage.getAccountId(externalId)
                    : externalId,
                ROOT_FOLDER_ID))
        .put(Field.OWNER_ID.getName(), externalId)
        .put(Field.CREATION_DATE.getName(), 0)
        .put(Field.UPDATE_DATE.getName(), 0)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), true)
        .put(Field.VIEW_ONLY.getName(), true)
        .put(Field.IS_OWNER.getName(), false)
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
    if (storageType.equals(StorageType.GDRIVE)) {
      info.put(Field.IS_TEAM_DRIVE.getName(), false);
    }
    return info;
  }

  protected JsonObject getRootFolderInfo(
      final StorageType storageType,
      final String externalId,
      final ObjectPermissions customPermissions) {
    ObjectPermissions objectPermissions = customPermissions != null
        ? customPermissions
        : new ObjectPermissions(); // all defaults are false
    JsonObject ownerInfo = Users.getUserInfo(externalId);
    JsonObject info = new JsonObject();
    info.put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                (storageType.equals(StorageType.SAMPLES))
                    ? SimpleStorage.getAccountId(externalId)
                    : externalId,
                ROOT_FOLDER_ID))
        .put(Field.WS_ID.getName(), ROOT_FOLDER_ID)
        .put(Field.NAME.getName(), ROOT)
        // Root doesn't have a parent, but just in case someone relies on it
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType),
                (storageType.equals(StorageType.SAMPLES))
                    ? SimpleStorage.getAccountId(externalId)
                    : externalId,
                ROOT_FOLDER_ID))
        .put(OWNER, ownerInfo.getString(Field.NAME.getName()))
        .put(Field.OWNER_EMAIL.getName(), ownerInfo.getString(Field.EMAIL.getName()))
        .put(Field.CREATION_DATE.getName(), 0)
        .put(Field.UPDATE_DATE.getName(), 0)
        .put(Field.CHANGER.getName(), Users.getUserNameById(externalId))
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
        .put(Field.IS_TEAM_DRIVE.getName(), false)
        .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
        .put(Field.PUBLIC.getName(), false);

    if (storageType.equals(StorageType.GDRIVE)) {
      info.put(Field.IS_TEAM_DRIVE.getName(), false);
    }
    return info;
  }

  protected JsonObject findCachedXrefs(
      Entity segment,
      Message<JsonObject> message,
      JsonObject jsonObject,
      final StorageType storageType,
      final String userId,
      final String externalId,
      final String fileId,
      final JsonArray paths) {
    JsonArray foundXrefs = new JsonArray();
    JsonArray unknownXrefs = new JsonArray();
    for (Object s : paths) {
      try {
        String currentPath = (String) s;
        Xref xref = Xref.getXref(userId, storageType, externalId, fileId, currentPath);
        if (xref != null) {
          // check each file if it still exists or not
          JsonArray knownXrefs = xref.getXrefs(); // each item => {_id:"",...}
          JsonArray currentPathXrefs = new JsonArray();
          // did we find any of the files saved in xrefs list
          boolean doesAnyExist = false;
          for (Object x : knownXrefs) {
            JsonObject xrefFile = (JsonObject) x;
            String xrefFileId = Utils.parseItemId(
                    xrefFile.getString(Field.ENCAPSULATED_ID.getName()))
                .getString(Field.ID.getName());
            // does this file still exist
            boolean doesExist = this.checkFile(
                segment,
                message,
                jsonObject.put(Field.EXTERNAL_ID.getName(), xref.getExternalId()),
                xrefFileId);
            if (doesExist) {
              doesAnyExist = true;
              currentPathXrefs.add(xrefFile);
            }
          }
          if (doesAnyExist) {
            foundXrefs.add(new JsonObject()
                .put(Field.FILES.getName(), currentPathXrefs)
                .put(Field.PATH.getName(), currentPath));
          } else {
            unknownXrefs.add(currentPath);
          }
        } else {
          unknownXrefs.add(currentPath);
        }
      } catch (Exception e) {
        log.error("XREF[" + storageType + "] error on getting xr from cache: ", e);
      }
    }
    return new JsonObject().put("foundXrefs", foundXrefs).put("unknownXrefs", unknownXrefs);
  }

  protected void saveXrefToCache(
      final StorageType storageType,
      final String userId,
      final String externalId,
      final String fileId,
      final String path,
      final JsonArray xrefs) {
    // create or update xref
    try {
      if (!xrefs.isEmpty()) {
        Xref updateXref = new Xref(userId, fileId, storageType, externalId, xrefs, path);
        updateXref.createOrUpdate();
      }
    } catch (Exception e) {
      log.error("XREF[" + storageType + "] error on updating xr in cache: ", e);
    }
  }

  protected boolean canCreateThumbnails(JsonObject info) {
    boolean isUserThumbnailDisabled = info.getBoolean("isUserThumbnailDisabled", false);
    boolean isAccountThumbnailDisabled = info.getBoolean("isAccountThumbnailDisabled", false);

    return !ThumbnailsDao.isThumbnailGenerationDisabled(
        isUserThumbnailDisabled, isAccountThumbnailDisabled);
  }

  protected boolean canCreateThumbnailsForKudoDrive(JsonObject info) {
    return !info.getBoolean("isUserThumbnailDisabled", false);
  }

  protected void createThumbnails(Entity segment, JsonArray thumbnail, JsonObject info) {
    eb_send(
        segment, ThumbnailsManager.address + ".create", info.put(Field.IDS.getName(), thumbnail));
  }

  protected boolean checkIfFileSessionExpired(String conflictingFileReason) {
    return (conflictingFileReason != null
        && (conflictingFileReason.equalsIgnoreCase(
                XSessionManager.ConflictingFileReason.SESSION_NOT_FOUND.name())
            || (conflictingFileReason.equalsIgnoreCase(
                XSessionManager.ConflictingFileReason.VIEW_ONLY_SESSION.name()))));
  }

  protected void copyFileComments(
      Entity parent,
      String fileId,
      StorageType storageType,
      String newFileId,
      boolean doIncludeResolvedComments,
      boolean doIncludeDeletedComments) {
    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    S3Regional s3Regional = new S3Regional(config, bucket, region);
    Thread commentsThread = new Thread(() -> {
      Entity standaloneSegment =
          XRayManager.createStandaloneSegment(operationGroup, parent, "copyCommentsSegment");
      String type = "comments";
      try {
        List<Item> newCommentsToAdd = new ArrayList<>();
        List<Item> newCommentThreadsToAdd = new ArrayList<>();
        Iterator<Item> commentsIterator = Comments.getAllAnnotations(
            null, fileId, doIncludeDeletedComments, doIncludeResolvedComments, type);
        List<Item> allComments = Lists.newArrayList(commentsIterator);
        List<Item> commentThreads = allComments.stream()
            .filter(comment ->
                comment.getString(Dataplane.sk).split(Dataplane.hashSeparator).length == 1)
            .collect(Collectors.toList());
        for (Item thread : commentThreads) {
          String orgCommentThreadId = thread.getString(Dataplane.sk);
          String newCommentThreadId = Utils.generateUUID();
          thread
              .withString(Dataplane.pk, type + Dataplane.hashSeparator + newFileId)
              .withString(Dataplane.sk, newCommentThreadId)
              .withString("clonedBy", orgCommentThreadId);
          newCommentThreadsToAdd.add(thread);
          List<Item> allThreadComments = allComments.stream()
              .filter(comment -> comment.getString(Dataplane.sk).startsWith(orgCommentThreadId))
              .collect(Collectors.toList());
          for (Item comment : allThreadComments) {
            String orgCommentId = comment.getString(Dataplane.sk);
            String newCommentId;
            if (orgCommentId.endsWith(Field.ROOT.getName())) {
              newCommentId = newCommentThreadId + Dataplane.hashSeparator + Field.ROOT.getName();
            } else {
              newCommentId = newCommentThreadId + Dataplane.hashSeparator + Utils.generateUUID();
            }
            comment
                .withString(Dataplane.pk, type + Dataplane.hashSeparator + newFileId)
                .withString(Dataplane.sk, newCommentId)
                .withString("clonedBy", orgCommentId);
            newCommentsToAdd.add(comment);
          }
        }
        if (!newCommentThreadsToAdd.isEmpty() || !newCommentsToAdd.isEmpty()) {
          Dataplane.batchWriteListItems(
              newCommentThreadsToAdd, Comments.table, DataEntityType.COMMENTS);
          Dataplane.batchWriteListItems(newCommentsToAdd, Comments.table, DataEntityType.COMMENTS);
          log.info(newCommentThreadsToAdd.size() + " commentThreads and " + newCommentsToAdd.size()
              + " comments are copied from file " + fileId + " to cloned file " + newFileId);
        }
      } catch (Exception ex) {
        XRayEntityUtils.addException(standaloneSegment, ex);
        log.error("Something went wrong in cloning comments for file " + fileId + " : "
            + ex.getLocalizedMessage());
      }
      XRayManager.endSegment(standaloneSegment);
    });

    Thread markupThread = new Thread(() -> {
      Entity standaloneSegment =
          XRayManager.createStandaloneSegment(operationGroup, parent, "copyMarkupsSegment");

      String type = "markups";
      try {
        List<Item> newMarkupsToAdd = new ArrayList<>();
        List<Item> newAttachmentsToAdd = new ArrayList<>();
        Iterator<Item> markups = Comments.getAllAnnotations(
            null, fileId, doIncludeDeletedComments, doIncludeResolvedComments, "markups");
        while (markups.hasNext()) {
          Item markup = markups.next();
          if (markup.hasAttribute(Field.TYPE.getName())) {
            String markupType = markup.getString(Field.TYPE.getName());
            if (markupType.equalsIgnoreCase(CommentVerticle.MarkupType.PICTURENOTE.name())
                || markupType.equalsIgnoreCase(CommentVerticle.MarkupType.VOICENOTE.name())
                    && markup.hasAttribute("notes")) {
              List<Map<String, String>> notes = markup.getList("notes");
              List<Map<String, String>> newNotes = new ArrayList<>();
              for (Map<String, String> note : notes) {
                String orgAttachmentId = note.get(Field.ID.getName());
                String attachmentId;
                if (Utils.isStringNotNullOrEmpty(orgAttachmentId)
                    && orgAttachmentId.split("_").length > 1) {
                  attachmentId = orgAttachmentId.split("_")[0] + "_" + UUID.randomUUID();
                } else {
                  attachmentId = UUID.randomUUID().toString();
                }
                note.put(Field.ID.getName(), attachmentId);
                newNotes.add(note);
                Item attachment = Attachments.getAttachment(fileId, storageType, orgAttachmentId);
                if (attachment != null) {
                  attachment
                      .withString(Dataplane.sk, attachmentId)
                      .withString(Dataplane.pk, Attachments.attachmentsPrefix + newFileId)
                      .withString("clonedBy", orgAttachmentId);
                  newAttachmentsToAdd.add(attachment);
                  copyAttachmentFiles(attachment, s3Regional, fileId, newFileId, orgAttachmentId);
                }
              }
              markup.withList("notes", newNotes);
            }
          }
          String orgMarkupId = markup.getString(Dataplane.sk);
          String markupId = Utils.generateUUID();
          markup
              .withString(Dataplane.sk, markupId)
              .withString(Dataplane.pk, type + Dataplane.hashSeparator + newFileId)
              .withString("clonedBy", orgMarkupId);
          newMarkupsToAdd.add(markup);
        }
        if (!newMarkupsToAdd.isEmpty() || !newAttachmentsToAdd.isEmpty()) {
          Dataplane.batchWriteListItems(newMarkupsToAdd, Comments.table, DataEntityType.COMMENTS);
          Dataplane.batchWriteListItems(
              newAttachmentsToAdd, Comments.table, DataEntityType.COMMENTS);
          log.info(newMarkupsToAdd.size() + " markups and " + newAttachmentsToAdd.size()
              + " attachments are copied from file " + fileId + " to cloned file "
              + newFileId);
        }
      } catch (Exception ex) {
        XRayEntityUtils.addException(standaloneSegment, ex);
        log.error("Something went wrong in cloning markups for file " + fileId + " : "
            + ex.getLocalizedMessage());
      }
      XRayManager.endSegment(standaloneSegment);
    });
    commentsThread.start();
    markupThread.start();
  }

  private void copyAttachmentFiles(
      Item attachment,
      S3Regional s3Regional,
      String fileId,
      String newFileId,
      String orgAttachmentId) {
    String newAttachmentId = attachment.getString(Dataplane.sk);
    String attachmentS3Key = "attachments/" + fileId + "/" + orgAttachmentId;
    ObjectMetadata attachmentMetadata =
        s3Regional.getObjectMetadata(s3Regional.getBucketName(), attachmentS3Key);
    if (attachmentMetadata == null) {
      attachmentMetadata = new ObjectMetadata();
    }
    byte[] attachmentData = s3Regional.get(attachmentS3Key);
    if (attachmentData != null) {
      s3Regional.putObject(new PutObjectRequest(
          s3Regional.getBucketName(),
          "attachments/" + newFileId + "/" + newAttachmentId,
          new ByteArrayInputStream(attachmentData),
          attachmentMetadata));
    }
    if (attachment.hasAttribute("preview800")) {
      String previewS3Key = "previews/" + fileId + "/_800_/" + orgAttachmentId;
      ObjectMetadata previewMetadata =
          s3Regional.getObjectMetadata(s3Regional.getBucketName(), previewS3Key);
      if (previewMetadata == null) {
        previewMetadata = new ObjectMetadata();
      }
      byte[] previewData = s3Regional.get(previewS3Key);
      if (previewData != null) {
        s3Regional.putObject(new PutObjectRequest(
            s3Regional.getBucketName(),
            "previews/" + newFileId + "/_800_/" + newAttachmentId,
            new ByteArrayInputStream(previewData),
            previewMetadata));
      }
    }
  }

  protected void sendUsageWsNotification(String userId, String storageType, String externalId) {
    Entity subSegment = XRayManager.createSubSegment(operationGroup, "sendUsageWsNotification");

    Item user = Users.getUserById(userId);
    if (user != null) {
      JsonObject userOptions;
      if (user.hasAttribute(Field.OPTIONS.getName())) {
        userOptions = new JsonObject(user.getJSON(Field.OPTIONS.getName()));
      } else {
        userOptions = config.getProperties().getDefaultUserOptionsAsJson();
      }
      if (userOptions.containsKey(Field.QUOTA.getName())
          && user.hasAttribute(Field.SAMPLE_USAGE.getName())) {
        eb_send(
            subSegment,
            WebSocketManager.address + ".sampleUsageUpdated",
            new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType)
                .put(Field.USER_ID.getName(), userId)
                .put(Field.EXTERNAL_ID.getName(), externalId)
                .put(Field.USAGE.getName(), user.getLong(Field.SAMPLE_USAGE.getName()))
                .put(Field.QUOTA.getName(), userOptions.getLong(Field.QUOTA.getName())));
      }
    }

    XRayManager.endSegment(subSegment);
  }

  private String setErrorMessage(Message<JsonObject> message, Throwable err) {
    log.error("[ZIP] error happened inside thread: ", err);
    if (err instanceof OutOfMemoryError) {
      return Utils.getLocalizedString(message, "MaxHeapSizeLimitReached");
    } else {
      return err.getLocalizedMessage();
    }
  }

  protected void finishDownloadZip(
      Message<JsonObject> message,
      Entity segment,
      S3Regional s3Regional,
      ZipOutputStream stream,
      ByteArrayOutputStream bos,
      Object task,
      Item request) {
    Timer time = new Timer();
    AtomicInteger uploadedPart = new AtomicInteger(0);
    AtomicInteger streamStart = new AtomicInteger(0);
    AtomicInteger uploadTry = new AtomicInteger(0);
    AtomicInteger downloadTry = new AtomicInteger(0);
    final String[] errorMessageIfAny = {null};
    final Promise[] promise = new Promise[1];
    final String locale = Utils.getLocaleFromMessage(message);
    final int[] minimumDownloadChunkSize = {MINIMUM_DOWNLOAD_CHUNK_SIZE};
    TimerTask concurrentUpload = new TimerTask() {
      @Override
      public void run() {
        Entity standaloneSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "concurrentUpload");
        try {
          if (promise[0] == null || promise[0].future().isComplete()) {
            promise[0] = Promise.promise();
            byte[] data = bos.toByteArray();
            if (data.length > streamStart.intValue()) {
              if ((data.length - streamStart.intValue()) > minimumDownloadChunkSize[0]) {
                uploadPartToS3(data, request, s3Regional, uploadedPart, streamStart, false, locale);
                downloadTry.set(0);
                minimumDownloadChunkSize[0] = MINIMUM_DOWNLOAD_CHUNK_SIZE;
                streamStart.set(data.length);
              } else {
                if (downloadTry.intValue() > 10) {
                  minimumDownloadChunkSize[0] = MINIMUM_DOWNLOAD_CHUNK_SIZE_10_try;
                } else if (downloadTry.intValue() > 5) {
                  minimumDownloadChunkSize[0] = MINIMUM_DOWNLOAD_CHUNK_SIZE_5_try;
                }
                downloadTry.addAndGet(1);
              }
            }
            promise[0].complete();
          } else {
            if (uploadTry.intValue() > UPLOAD_REQUEST_TRY_LIMIT) {
              uploadTry.set(0);
            }
            uploadTry.addAndGet(1);
          }
        } catch (Throwable ex) {
          errorMessageIfAny[0] = setErrorMessage(message, ex);
        }
        XRayManager.endSegment(standaloneSegment);
      }
    };
    time.schedule(concurrentUpload, 200, 2500);
    Thread multipleDownloadThread = new Thread(() -> {
      ThreadPoolExecutor poolExecutor = null;
      Entity multipleDownloadThreadSegment =
          XRayManager.createStandaloneSegment(operationGroup, segment, "multipleDownloadThread");
      try {
        if (task instanceof List) {
          List<Callable<Void>> callables = (List<Callable<Void>>) task;
          int numberOfProcessors = Runtime.getRuntime().availableProcessors();
          int poolSize = Math.min(numberOfProcessors * 2, callables.size());
          poolExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize);
          // Rejection handler
          poolExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

          for (Callable<Void> callable : callables) {
            if (!poolExecutor.isShutdown()) {
              Future<Void> future = poolExecutor.submit(callable);
              future.get(ZIP_FOLDER_MAX_TIME_LIMIT, TimeUnit.SECONDS);
            }
          }
        } else {
          Future<Void> future = (Future<Void>) task;
          future.get(ZIP_FOLDER_MAX_TIME_LIMIT, TimeUnit.SECONDS);
        }
      } catch (TimeoutException tex) {
        if (Objects.nonNull(poolExecutor) && !poolExecutor.isShutdown()) {
          poolExecutor.shutdownNow();
        }
      } catch (Throwable err) {
        errorMessageIfAny[0] = setErrorMessage(message, err);
      } finally {
        try {
          stream.close();
          bos.close();
          concurrentUpload.cancel();
          time.cancel();
        } catch (OutOfMemoryError eox) {
          errorMessageIfAny[0] = setErrorMessage(message, eox);
        } catch (IOException ex) {
          log.warn("Exception occurred in closing the streams after downloading items : " + ex);
        }
      }
      try {
        if (!Utils.isStringNotNullOrEmpty(errorMessageIfAny[0])) {
          if (promise[0] != null && !promise[0].future().isComplete()) {
            promise[0].future().onComplete(event -> {
              try {
                completeDownload(
                    bos.toByteArray(),
                    request,
                    s3Regional,
                    uploadedPart,
                    streamStart,
                    Utils.getLocaleFromMessage(message));
              } catch (OutOfMemoryError oex) {
                ZipRequests.setRequestStatus(
                    request,
                    JobStatus.ERROR,
                    Utils.getLocalizedString(message, "MaxHeapSizeLimitReached"));
              }
            });
          } else {
            completeDownload(
                bos.toByteArray(),
                request,
                s3Regional,
                uploadedPart,
                streamStart,
                Utils.getLocaleFromMessage(message));
          }
          ZipRequests.putMainZipToS3(
              request, s3Regional.getBucketName(), s3Regional.getRegion(), bos.toByteArray());
        } else {
          ZipRequests.setRequestStatus(request, JobStatus.ERROR, errorMessageIfAny[0]);
        }
      } catch (OutOfMemoryError oex) {
        ZipRequests.setRequestStatus(
            request, JobStatus.ERROR, Utils.getLocalizedString(message, "MaxHeapSizeLimitReached"));
      }
      if (Objects.nonNull(poolExecutor) && !poolExecutor.isShutdown()) {
        poolExecutor.shutdownNow();
      }
      XRayManager.endSegment(multipleDownloadThreadSegment);
    });
    multipleDownloadThread.start();
  }

  private void uploadPartToS3(
      byte[] data,
      Item request,
      S3Regional s3Regional,
      AtomicInteger uploadedPart,
      AtomicInteger streamStart,
      boolean isCompleted,
      String locale) {
    byte[] subData = Arrays.copyOfRange(data, streamStart.intValue(), data.length);
    if (subData.length > MAXIMUM_DOWNLOAD_CHUNK_SIZE) {
      int iteration = subData.length / MAXIMUM_DOWNLOAD_CHUNK_SIZE;
      if ((subData.length % MAXIMUM_DOWNLOAD_CHUNK_SIZE) != 0) {
        iteration += 1;
      }
      int subStreamStart = 0;
      while (iteration > 0) {
        int nextCopyTo = subStreamStart + MAXIMUM_DOWNLOAD_CHUNK_SIZE;
        byte[] smallSubData =
            Arrays.copyOfRange(subData, subStreamStart, Math.min(nextCopyTo, subData.length));
        request.withInt(Field.UPLOADED_PART.getName(), uploadedPart.addAndGet(1));
        request.withLong(Field.UPLOADED_SIZE.getName(), data.length);
        ZipRequests.putZipToS3(
            request,
            s3Regional.getBucketName(),
            s3Regional.getRegion(),
            smallSubData,
            isCompleted,
            locale);
        subStreamStart += smallSubData.length;
        iteration--;
      }
    } else {
      request.withInt(Field.UPLOADED_PART.getName(), uploadedPart.addAndGet(1));
      request.withLong(Field.UPLOADED_SIZE.getName(), data.length);
      ZipRequests.putZipToS3(
          request,
          s3Regional.getBucketName(),
          s3Regional.getRegion(),
          subData,
          isCompleted,
          locale);
    }
  }

  private void completeDownload(
      byte[] data,
      Item request,
      S3Regional s3Regional,
      AtomicInteger uploadedPart,
      AtomicInteger streamStart,
      String locale) {
    if (data.length > streamStart.intValue()) {
      uploadPartToS3(data, request, s3Regional, uploadedPart, streamStart, true, locale);
    } else {
      ZipRequests.setRequestStatus(request, JobStatus.SUCCESS, null);
    }
  }

  public void excludeFileFromRequest(Item request, String path, ExcludeReason reason) {
    if (Objects.nonNull(request)) {
      String key = reason.getKey();
      List<String> excludedLargeFiles =
          request.hasAttribute(key) ? request.getList(key) : new ArrayList<>();
      excludedLargeFiles.add(path);
      ZipRequests.updateExcludedFilesList(request, excludedLargeFiles, reason);
    }
  }

  public String getNonRegexSpecialCharacter(String character) {
    if (Utils.isStringNotNullOrEmpty(character)) {
      String formattedCharacter = character;
      if (character.contains("\\") && character.length() == 2) {
        formattedCharacter = character.substring(1);
      }
      return formattedCharacter;
    }
    return emptyString;
  }

  protected enum ConnectionError {
    NO_USER_ID,
    NO_EXTERNAL_ID,
    NO_ENTRY_IN_DB,
    CANNOT_DECRYPT_TOKENS,
    CANNOT_ENCRYPT_TOKENS,
    TOKEN_EXPIRED,
    NO_ACCESS_TOKEN,
    INVALID_ACCESS_TOKEN,
    CANNOT_REFRESH_TOKENS,
    CONNECTION_EXCEPTION,
    NO_REFRESH_TOKEN
  }
}
