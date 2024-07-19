package com.graebert.storage.gridfs;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkErrorCodes;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.comment.AccessCache;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.logs.ConflictingFileLogger;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.stats.logs.file.FileActions;
import com.graebert.storage.stats.logs.file.FileLog;
import com.graebert.storage.stats.logs.sharing.SharingActions;
import com.graebert.storage.stats.logs.sharing.SharingLog;
import com.graebert.storage.stats.logs.sharing.SharingTypes;
import com.graebert.storage.storage.CloudFieldFileVersion;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.FolderMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Mentions;
import com.graebert.storage.storage.S3PresignedUploadRequests;
import com.graebert.storage.storage.Templates;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.ids.IdName;
import com.graebert.storage.storage.ids.KudoFileId;
import com.graebert.storage.storage.link.BaseLink;
import com.graebert.storage.storage.link.LinkConfiguration;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionLink;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.storage.link.download.DownloadVersionLink;
import com.graebert.storage.storage.link.repository.DownloadVersionLinkRepositoryImpl;
import com.graebert.storage.storage.link.repository.LinkService;
import com.graebert.storage.storage.link.repository.VersionLinkRepository;
import com.graebert.storage.storage.link.repository.ViewVersionLinkRepositoryImpl;
import com.graebert.storage.storage.link.view.ViewVersionLink;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.users.IntercomConnection;
import com.graebert.storage.users.LicensingService;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.util.urls.ShortURL;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.TimeoutException;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import kong.unirest.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class GridFSModule extends DynamoBusModBase {
  @NonNls
  public static final String address = "gridfsmodule";

  @NonNls
  public static final String EDITOR = "EDITOR";

  @NonNls
  public static final String VIEWER = "VIEWER";

  private static final OperationGroup operationGroup = OperationGroup.GRID_FS_MODULE;

  @NonNls
  private static final String FULL_ACCESS = "FULL_ACCESS";

  @NonNls
  private static final String READ = "READ";

  @NonNls
  private static final String WRITER = "writer";

  @NonNls
  private static final String READER = "reader";

  @NonNls
  private static final String ONE = ":one";

  @NonNls
  private static final String ROOT = Field.ROOT.getName();

  @NonNls
  private static final String USER_ID = Field.USER_ID.getName();

  @NonNls
  protected static Logger log = LogManager.getRootLogger();

  private S3Regional s3Regional = null;
  private String bucket = null;
  private String region = null;
  private boolean returnDownloadUrl;

  public GridFSModule() {}

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".createByTmpl", this::doCreateByTmpl);
    eb.consumer(address + ".cloneFile", this::doCloneFile);
    eb.consumer(address + ".promoteVersion", this::doPromoteVersion);
    eb.consumer(address + ".markVersionAsPrinted", this::doMarkVersionAsPrinted);
    eb.consumer(address + ".getFile", this::doGetFile);
    eb.consumer(address + ".getVersion", this::doGetVersion);
    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".deleteVersion", this::doDeleteVersion);
    eb.consumer(address + ".getVersions", this::doGetVersions);
    eb.consumer(address + ".getLatestVersionId", this::doGetLatestVersion);
    eb.consumer(address + ".getS3PreSignedUploadURL", this::doGetS3PreSignedUploadURL);
    eb.consumer(address + ".checkUpload", this::doCheckUpload);
    eb.consumer(address + ".cancelUpload", this::doCancelUpload);
    eb.consumer(address + ".uploadFile", this::doUploadFile);
    eb.consumer(address + ".uploadVersion", this::doUploadVersion);
    eb.consumer(address + ".getChunk", this::doGetChunk);

    eb.consumer(
        address + ".deleteFile",
        (Message<JsonObject> message) -> doDeleteFile(message, null, null, true));
    eb.consumer(address + ".createFolder", this::doCreateFolder);
    eb.consumer(address + ".cloneFolder", this::doCloneFolder);
    eb.consumer(
        address + ".deleteFolder",
        (Message<JsonObject> message) -> doDeleteFolder(message, null, null, true));
    eb.consumer(address + ".moveFile", this::doMoveFile);
    eb.consumer(address + ".moveFolder", this::doMoveFolder);
    eb.consumer(address + ".renameFile", (Message<JsonObject> message) -> doRename(message, false));
    eb.consumer(
        address + ".renameFolder", (Message<JsonObject> message) -> doRename(message, true));
    eb.consumer(address + ".shareFile", (Message<JsonObject> message) -> doShare(message, false));
    eb.consumer(address + ".shareFolder", (Message<JsonObject> message) -> doShare(message, true));
    eb.consumer(
        address + ".deShareFile", (Message<JsonObject> message) -> doDeShare(message, false));
    eb.consumer(
        address + ".deShareFolder", (Message<JsonObject> message) -> doDeShare(message, true));
    eb.consumer(address + ".getObjectInfo", this::doGetObjectInfo);
    eb.consumer(address + ".getTrashedInfo", this::doGetTrashedInfo);
    eb.consumer(address + ".getThumbnail", this::doGetThumbnail);
    eb.consumer(address + ".getObjectInfoByToken", this::getObjectInfoByToken);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".getFolderContent", this::doGetFolderContent);
    eb.consumer(address + ".search", this::doSearch);
    eb.consumer(address + ".getFileByToken", this::doGetFileByToken);
    eb.consumer(address + ".trash", this::doTrash);
    eb.consumer(address + ".trashMultiple", this::doTrashMultiple);
    eb.consumer(address + ".eraseMultiple", this::doEraseMultiple);
    eb.consumer(address + ".eraseAll", this::doEraseAll);
    eb.consumer(address + ".restoreMultiple", this::doRestoreMultiple);
    eb.consumer(address + ".untrash", this::doUntrash);
    eb.consumer(address + ".getOwners", this::doGetOwners);
    eb.consumer(address + ".tryShare", this::doTryShare);
    eb.consumer(
        address + ".changeFileOwner",
        (Message<JsonObject> message) -> doChangeOwner(message, false));
    eb.consumer(
        address + ".changeFolderOwner",
        (Message<JsonObject> message) -> doChangeOwner(message, true));
    eb.consumer(address + ".findXRef", this::doFindXRef);
    eb.consumer(address + ".checkXRefPath", this::doCheckPath);
    eb.consumer(address + ".checkFileSave", this::doCheckFileSave);

    // public links versions
    eb.consumer(address + ".getFileLinks", this::doGetFileLinks);
    eb.consumer(address + ".deleteFileVersionDownloadLink", this::doDeleteFileVersionDownloadLink);
    eb.consumer(address + ".deleteFileVersionViewLink", this::doDeleteFileVersionViewLink);
    eb.consumer(address + ".getFileVersionDownloadLink", this::doGetFileVersionDownloadLink);
    eb.consumer(address + ".getFileVersionViewLink", this::doGetFileVersionViewLink);

    // public links files
    eb.consumer(address + ".getSharedLink", this::doGetSharedLink);
    eb.consumer(address + ".removeSharedLink", this::doRemoveSharedLink);
    eb.consumer(address + ".updateSharedLink", this::doUpdateSharedLink);

    eb.consumer(address + ".requestFolderZip", this::doRequestFolderZip);
    eb.consumer(address + ".requestMultipleDownloadZip", this::doRequestMultipleDownloadZip);
    eb.consumer(address + ".getFolderZip", this::doGetFolderZip);
    eb.consumer(address + ".globalSearch", this::doGlobalSearch);
    eb.consumer(address + ".getMetadata", this::doGetMetadata);
    eb.consumer(address + ".putMetadata", this::doPutMetadata);
    eb.consumer(address + ".deleteMetadata", this::doDeleteMetadata);
    eb.consumer(address + ".requestDownload", this::doRequestDownload);
    eb.consumer(address + ".getDownload", this::doGetDownload);

    eb.consumer(address + ".getToken", this::doGetToken);

    eb.consumer(address + ".createShortcut", this::doCreateShortcut);

    bucket = config.getProperties().getS3Bucket();
    region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-gridfs");

    breaker = CircuitBreaker.create(
        "vert.x-circuit-breaker-gridfs",
        vertx,
        new CircuitBreakerOptions().setTimeout(20000).setMaxRetries(0).setMaxFailures(2));

    // DK: hack to pass config values to "PublicLink" class.
    // I have no idea on how it could be done otherwise
    PublicLink.setConfigValues(
        config.getProperties().getUrl(),
        config.getProperties().getEnterprise(),
        config.getProperties().getDefaultCompanyOptionsAsJson(),
        config.getProperties().getFluorineSecretKey());

    LinkConfiguration.initialize(config);
    LicensingService.init(
        config.getProperties().getLicensingUrl(),
        config.getProperties().getLicensingApiToken(),
        config.getProperties().getLicensingSyncToken(),
        config.getProperties().getXKudoSecret());
    ShortURL.setShortenServiceURL(config.getProperties().getShortenServiceURL());
    returnDownloadUrl = config.getProperties().getReturnDownloadUrl();
  }

  private void doDeleteMetadata(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.ID.getName(), message, message.body());
    JsonObject json = Utils.parseItemId(id, Field.ID.getName());
    id = json.getString(Field.ID.getName());
    String storageType = json.containsKey(Field.STORAGE_TYPE.getName())
        ? json.getString(Field.STORAGE_TYPE.getName())
        : message.body().getString(Field.STORAGE_TYPE.getName());
    if (isStorageDisabled(
        segment,
        message,
        StorageType.getStorageType(storageType),
        message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    String externalId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : message.body().getString(Field.EXTERNAL_ID.getName());
    FolderMetaData.eraseMetaData(id, storageType);
    sendOK(segment, message, millis);
  }

  private void doPutMetadata(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.ID.getName(), message, message.body());
    JsonObject json = Utils.parseItemId(id, Field.ID.getName());
    id = json.getString(Field.ID.getName());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String storageType = json.containsKey(Field.STORAGE_TYPE.getName())
        ? json.getString(Field.STORAGE_TYPE.getName())
        : message.body().getString(Field.STORAGE_TYPE.getName());
    if (isStorageDisabled(segment, message, StorageType.getStorageType(storageType), userId)) {
      return;
    }
    JsonObject metadata = message.body().getJsonObject("metadata");

    FolderMetaData.putMetaData(id, storageType, userId, metadata.encode());
    sendOK(segment, message, millis);
  }

  private void doGetMetadata(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.ID.getName(), message, message.body());
    JsonObject json = Utils.parseItemId(id, Field.ID.getName());
    id = json.getString(Field.ID.getName());
    String storageType = json.containsKey(Field.STORAGE_TYPE.getName())
        ? json.getString(Field.STORAGE_TYPE.getName())
        : message.body().getString(Field.STORAGE_TYPE.getName());
    if (isStorageDisabled(
        segment,
        message,
        StorageType.getStorageType(storageType),
        message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    Item item = FolderMetaData.getMetaData(id, storageType);
    if (item == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileNotFound"),
          HttpStatus.NOT_FOUND);
      return;
    }
    sendOK(
        segment,
        message,
        new JsonObject().put("metadata", new JsonObject(item.getString("metadata"))),
        millis);
  }

  private void doGetFileLinks(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (Utils.anyNull(userId, fullFileId)) {
      return;
    }

    KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

    if (isStorageDisabled(segment, message, fileIds.getStorageType(), userId)) {
      return;
    }

    eb_request(
        segment,
        StorageType.getAddress(fileIds.getStorageType()) + ".checkFileVersion",
        fileIds.toJson().put(Field.USER_ID.getName(), userId),
        (AsyncResult<Message<JsonObject>> event) -> {
          try {
            if (event.succeeded() && isOk(event)) {
              List<JsonObject> links = VersionLinkRepository.getInstance()
                  .getAllLinksForFileVersions(fileIds.getExternalId(), userId, fileIds.getId())
                  .stream()
                  .map(BaseLink::toJson)
                  .collect(Collectors.toList());

              sendOK(segment, message, new JsonObject().put("links", new JsonArray(links)), mills);
            } else {
              handleExternal(segment, event, message, mills);
            }
          } catch (Exception exception) {
            sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
          }
          recordExecutionTime("getFileLinks", System.currentTimeMillis() - mills);
        });
  }

  private void doDeleteFileVersionViewLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String versionId =
        getRequiredString(segment, Field.VERSION_ID.getName(), message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());

    if (Utils.anyNull(versionId, userId, fullFileId)) {
      return;
    }

    final String sheetId = message.body().getString(Field.SHEET_ID.getName());

    KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

    if (isStorageDisabled(segment, message, fileIds.getStorageType(), userId)) {
      return;
    }

    try {
      ViewVersionLink link = ViewVersionLinkRepositoryImpl.getInstance()
          .getUserLink(userId, fileIds.getExternalId(), fileIds.getId(), versionId, sheetId);

      if (Objects.isNull(link)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionDoesNotExists"),
                LinkType.VERSION,
                fileIds.getId(),
                versionId),
            HttpStatus.BAD_REQUEST);
        return;
      }

      eb_request(
          segment,
          StorageType.getAddress(fileIds.getStorageType()) + ".checkFileVersion",
          fileIds
              .toJson()
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.USER_ID.getName(), userId),
          (AsyncResult<Message<JsonObject>> event) -> {
            try {
              if (event.succeeded() && isOk(event)) {
                ViewVersionLinkRepositoryImpl.getInstance().delete(link);

                sendOK(segment, message, mills);
              } else {
                handleExternal(segment, event, message, mills);
              }
            } catch (Exception exception) {
              sendError(
                  segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
            }
            recordExecutionTime("deleteFileVersionViewLink", System.currentTimeMillis() - mills);
          });
    } catch (Exception exception) {
      sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void doDeleteFileVersionDownloadLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String versionId =
        getRequiredString(segment, Field.VERSION_ID.getName(), message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());

    if (Utils.anyNull(versionId, userId, fullFileId)) {
      return;
    }

    final String sheetId = message.body().getString(Field.SHEET_ID.getName());

    KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

    if (isStorageDisabled(segment, message, fileIds.getStorageType(), userId)) {
      return;
    }

    try {
      DownloadVersionLink link = DownloadVersionLinkRepositoryImpl.getInstance()
          .getUserLink(userId, fileIds.getExternalId(), fileIds.getId(), versionId, sheetId);

      if (Objects.isNull(link)) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionDoesNotExists"),
                LinkType.VERSION,
                fileIds.getId(),
                versionId),
            HttpStatus.BAD_REQUEST);
        return;
      }

      eb_request(
          segment,
          StorageType.getAddress(fileIds.getStorageType()) + ".checkFileVersion",
          fileIds
              .toJson()
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.USER_ID.getName(), userId),
          (AsyncResult<Message<JsonObject>> event) -> {
            try {
              if (event.succeeded() && isOk(event)) {
                DownloadVersionLinkRepositoryImpl.getInstance().delete(link);

                sendOK(segment, message, mills);
              } else {
                handleExternal(segment, event, message, mills);
              }
            } catch (Exception exception) {
              sendError(
                  segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
            }
            recordExecutionTime("deleteFileVersionViewLink", System.currentTimeMillis() - mills);
          });
    } catch (Exception exception) {
      sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void doGetFileVersionViewLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String versionId =
        getRequiredString(segment, Field.VERSION_ID.getName(), message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());

    final String sheetId = message.body().getString(Field.SHEET_ID.getName());
    Boolean export = message.body().getBoolean(Field.EXPORT.getName(), false);
    final long endTime = message.body().containsKey(Field.END_TIME.getName())
        ? message.body().getLong(Field.END_TIME.getName())
        : 0L;
    final String password = message.body().getString(Field.PASSWORD.getName());

    if (Utils.anyNull(versionId, userId, fullFileId)) {
      return;
    }

    KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

    if (isStorageDisabled(segment, message, fileIds.getStorageType(), userId)) {
      return;
    }

    try {
      if (Objects.nonNull(ViewVersionLinkRepositoryImpl.getInstance()
          .getUserLink(userId, fileIds.getExternalId(), fileIds.getId(), versionId, sheetId))) {
        String formattedMessage = Objects.nonNull(sheetId)
            ? MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionSheetExists"),
                LinkType.VERSION,
                fileIds.getId(),
                versionId,
                sheetId)
            : MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionExists"),
                LinkType.VERSION,
                fileIds.getId(),
                versionId);

        sendError(segment, message, formattedMessage, HttpStatus.BAD_REQUEST);
        return;
      }

      eb_request(
          segment,
          StorageType.getAddress(fileIds.getStorageType()) + ".checkFileVersion",
          fileIds
              .toJson()
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.USER_ID.getName(), userId),
          (AsyncResult<Message<JsonObject>> event) -> {
            try {
              if (event.succeeded() && isOk(event)) {
                ViewVersionLink link = ViewVersionLink.createNew(
                    userId,
                    event.result().body().getString(Field.OWNER_IDENTITY.getName()),
                    sheetId,
                    fileIds.getId(),
                    fileIds.getExternalId(),
                    fileIds.getStorageType(),
                    event.result().body().getString(Field.NAME.getName()),
                    ObjectType.FILE.name(),
                    endTime,
                    export,
                    password,
                    versionId,
                    VersionType.parse(versionId));

                if (event.result().body().containsKey(Field.COLLABORATORS.getName())) {
                  link.setCollaboratorsList(
                      event.result().body().getJsonArray(Field.COLLABORATORS.getName()).stream()
                          .map(Object::toString)
                          .collect(Collectors.toList()));
                }

                ViewVersionLinkRepositoryImpl.getInstance().save(link);

                sendOK(
                    segment,
                    message,
                    new JsonObject()
                        .put(
                            Field.LINK.getName(),
                            link.toJson().put(Field.LINK.getName(), link.getEndUserLink())),
                    mills);
              } else {
                handleExternal(segment, event, message, mills);
              }
            } catch (Exception exception) {
              sendError(
                  segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
            }
            recordExecutionTime("deleteFileVersionViewLink", System.currentTimeMillis() - mills);
          });
    } catch (Exception exception) {
      sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void doGetFileVersionDownloadLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String versionId =
        getRequiredString(segment, Field.VERSION_ID.getName(), message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());

    final String sheetId = message.body().getString(Field.SHEET_ID.getName());
    final boolean export = message.body().getBoolean(Field.EXPORT.getName(), false);
    final long endTime = message.body().containsKey(Field.END_TIME.getName())
        ? message.body().getLong(Field.END_TIME.getName())
        : 0L;
    final String password = message.body().getString(Field.PASSWORD.getName());
    final boolean convertToPdf = message.body().getBoolean("convertToPdf", false);

    if (Utils.anyNull(versionId, userId, fullFileId)) {
      return;
    }

    KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

    if (isStorageDisabled(segment, message, fileIds.getStorageType(), userId)) {
      return;
    }

    try {
      if (Objects.nonNull(DownloadVersionLinkRepositoryImpl.getInstance()
          .getUserLink(userId, fileIds.getExternalId(), fileIds.getId(), versionId, sheetId))) {
        String formattedMessage = Objects.nonNull(sheetId)
            ? MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionSheetExists"),
                LinkType.DOWNLOAD,
                fileIds.getId(),
                versionId,
                sheetId)
            : MessageFormat.format(
                Utils.getLocalizedString(message, "SharedLinkForFileVersionExists"),
                LinkType.DOWNLOAD,
                fileIds.getId(),
                versionId);

        sendError(segment, message, formattedMessage, HttpStatus.BAD_REQUEST);
        return;
      }

      eb_request(
          segment,
          StorageType.getAddress(fileIds.getStorageType()) + ".checkFileVersion",
          fileIds
              .toJson()
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.USER_ID.getName(), userId),
          (AsyncResult<Message<JsonObject>> event) -> {
            try {
              if (event.succeeded() && isOk(event)) {
                DownloadVersionLink link = DownloadVersionLink.createNew(
                    userId,
                    event.result().body().getString(Field.OWNER_IDENTITY.getName()),
                    sheetId,
                    fileIds.getId(),
                    fileIds.getExternalId(),
                    fileIds.getStorageType(),
                    event.result().body().getString(Field.NAME.getName()),
                    ObjectType.FILE.name(),
                    endTime,
                    export,
                    password,
                    versionId,
                    VersionType.parse(versionId),
                    convertToPdf);

                if (event.result().body().containsKey(Field.COLLABORATORS.getName())) {
                  link.setCollaboratorsList(
                      event.result().body().getJsonArray(Field.COLLABORATORS.getName()).stream()
                          .map(Object::toString)
                          .collect(Collectors.toList()));
                }

                DownloadVersionLinkRepositoryImpl.getInstance().save(link);

                sendOK(
                    segment,
                    message,
                    new JsonObject()
                        .put(
                            Field.LINK.getName(),
                            link.toJson().put(Field.LINK.getName(), link.getEndUserLink())),
                    mills);
              } else {
                handleExternal(segment, event, message, mills);
              }
            } catch (Exception exception) {
              sendError(
                  segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
            }
            recordExecutionTime(
                "createFileVersionDownloadLink", System.currentTimeMillis() - mills);
          });
    } catch (Exception exception) {
      sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void doGetSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String graebertId = jsonObject.getString(Field.GRAEBERT_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    if (userId == null || fileId == null) {
      return;
    }
    JsonObject jsonId = Utils.parseItemId(fileId, Field.FILE_ID.getName());
    message.body().mergeIn(jsonId);
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".createSharedLink", jsonObject, event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("createSharedLink", System.currentTimeMillis() - mills);
          if (event.succeeded()
              && OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .runWithoutSegment(() -> {
                  FileLog fileLog = new FileLog(
                      userId,
                      jsonId.getString(Field.FILE_ID.getName()),
                      storageType,
                      jsonObject.getString(Field.USERNAME.getName()),
                      GMTHelper.utcCurrentTime(),
                      true,
                      FileActions.SHARED_LINK,
                      GMTHelper.utcCurrentTime(),
                      null,
                      message.body().getString(Field.SESSION_ID.getName()),
                      null,
                      jsonObject.getString(Field.DEVICE.getName()),
                      null);
                  fileLog.sendToServer();
                  Map<String, Object> metadata = new HashMap<>();
                  metadata.put(Field.STORAGE_TYPE.getName(), storageType.name());
                  metadata.put(Field.FILE_ID.getName(), jsonId.getString(Field.FILE_ID.getName()));
                  metadata.put("date", GMTHelper.utcCurrentTime());
                  metadata.put(Field.EXTERNAL_ID.getName(), externalId);
                  IntercomConnection.sendEvent(
                      "Share file with public link",
                      graebertId,
                      metadata,
                      intercomAccessToken,
                      jsonObject.getString(Field.INTERCOM_APP_ID.getName()));
                });
          }
        });
  }

  private void doUpdateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    if (userId == null || fileId == null) {
      return;
    }
    JsonObject jsonId = Utils.parseItemId(fileId, Field.FILE_ID.getName());
    message.body().mergeIn(jsonId);
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    PublicLink publicLink = new PublicLink(
        jsonId.getString(Field.FILE_ID.getName()),
        jsonId.getString(Field.EXTERNAL_ID.getName()),
        userId);
    try {
      if (!publicLink.fetchLinkInfo()) {
        throw new PublicLinkException(
            "Unable to find link based on parameters " + fileId,
            PublicLinkErrorCodes.LINK_NOT_FOUND);
      }
      boolean resetEndTime = jsonObject.getBoolean("resetEndTime");
      boolean resetPassword = jsonObject.getBoolean(Field.RESET_PASSWORD.getName());
      long endTime = jsonObject.getLong(Field.END_TIME.getName());
      String password = jsonObject.getString(Field.PASSWORD.getName());
      boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
      publicLink.setUserId(userId);
      if (resetPassword) {
        publicLink.setPassword(emptyString);
      } else if (Utils.isStringNotNullOrEmpty(password)) {
        publicLink.setPassword(password);
      }
      if (resetEndTime) {
        publicLink.setEndTime(0L);
      } else if (endTime != 0L) {
        publicLink.setEndTime(endTime);
      }
      if (export != publicLink.getExport()) {
        publicLink.setExport(export);
      }
      publicLink.createOrUpdate();
      recordExecutionTime("updateSharedLink", System.currentTimeMillis() - mills);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("sendFileLog")
          .runWithoutSegment(() -> {
            FileLog fileLog = new FileLog(
                userId,
                jsonId.getString(Field.FILE_ID.getName()),
                storageType,
                jsonObject.getString(Field.USERNAME.getName()),
                GMTHelper.utcCurrentTime(),
                true,
                FileActions.SHARED_LINK_UPDATE,
                GMTHelper.utcCurrentTime(),
                null,
                message.body().getString(Field.SESSION_ID.getName()),
                null,
                jsonObject.getString(Field.DEVICE.getName()),
                null);
            fileLog.sendToServer();
          });
      sendOK(segment, message, publicLink.getInfoInJSON(), mills);
    } catch (PublicLinkException ple) {
      sendError(segment, message, ple.toResponseObject(), HttpStatus.BAD_REQUEST);
    }
  }

  private void doRemoveSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    if (userId == null || fileId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".deleteSharedLink", jsonObject, event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("deleteSharedLink", System.currentTimeMillis() - mills);
          if (event.succeeded()
              && OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
            eb_send(
                segment,
                WebSocketManager.address + ".sharedLinkOff",
                new JsonObject()
                    .put(
                        Field.FILE_ID.getName(),
                        StorageType.processFileIdForWebsocketNotification(storageType, fileId))
                    .put(
                        Field.LINK_OWNER_IDENTITY.getName(),
                        ((JsonObject) event.result().body())
                            .getString(Field.LINK_OWNER_IDENTITY.getName())));
          }
        });
  }

  private void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    if (userId == null || path == null) {
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
    if (path.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "PathIsEmpty"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".checkPath", message.body(), event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("doCheckPath", System.currentTimeMillis() - mills);
        });
  }

  private void doCheckFileSave(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(Field.USER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    JsonArray errors = new JsonArray();
    List<Future<Void>> futures = new ArrayList<>();
    Promise<Void> sessionPromise = Promise.promise();
    // checking editing session for file
    eb_request(
        segment,
        XSessionManager.address + ".check",
        new JsonObject()
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.X_SESSION_ID.getName(), body.getString(Field.X_SESSION_ID.getName())),
        sessionEvent -> {
          if (sessionEvent.succeeded()) {
            if (!((JsonObject) sessionEvent.result().body())
                .getString(Field.STATUS.getName())
                .equals(OK)) {
              Object result =
                  ((JsonObject) sessionEvent.result().body()).getValue(Field.MESSAGE.getName());
              if (result instanceof JsonObject) {
                errors.add(result);
              } else {
                errors.add(sessionEvent.result().body());
              }
            }
            sessionPromise.complete();
          } else {
            sessionPromise.fail(sessionEvent.cause().getLocalizedMessage());
          }
        });
    futures.add(sessionPromise.future());
    Promise<Void> versionPromise = Promise.promise();
    // checking conflict for latest file version
    eb_request(
        segment,
        StorageType.getAddress(StorageType.getStorageType(storageType)) + ".getLatestVersionId",
        body,
        versionEvent -> {
          if (versionEvent.succeeded()) {
            JsonObject versionResult = (JsonObject) versionEvent.result().body();
            try {
              if (versionResult.getString(Field.STATUS.getName()).equals(OK)) {
                String versionId = versionResult.getString(Field.VERSION_ID.getName());
                if (Utils.isStringNotNullOrEmpty(versionId)) {
                  String baseChangeId = body.getString(Field.BASE_CHANGE_ID.getName());
                  if (BaseStorage.isConflictingChange(
                      userId,
                      fileId,
                      StorageType.getStorageType(storageType),
                      versionId,
                      baseChangeId)) {
                    throw new KudoFileException(
                        Utils.getLocalizedString(message, "VersionConflict"),
                        KudoErrorCodes.FILE_VERSION_CONFLICT,
                        HttpStatus.BAD_REQUEST,
                        "VersionConflict");
                  }
                } else {
                  throw new KudoFileException(
                      Utils.getLocalizedString(message, "FL21"),
                      KudoErrorCodes.FILE_LATEST_VERSION_ERROR,
                      HttpStatus.BAD_REQUEST,
                      "FL21");
                }
              } else {
                throw new KudoFileException(
                    KudoFileException.getErrorMessage(message, versionResult),
                    KudoErrorCodes.FILE_LATEST_VERSION_ERROR,
                    HttpStatus.BAD_REQUEST);
              }
            } catch (KudoFileException kfe) {
              errors.add(kfe.toResponseObject());
            }
            versionPromise.complete();
          } else {
            versionPromise.fail(versionEvent.cause().getLocalizedMessage());
          }
        });
    futures.add(versionPromise.future());
    TypedCompositeFuture.join(futures)
        .onSuccess(success -> {
          sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
          recordExecutionTime("checkFileSave", System.currentTimeMillis() - mills);
        })
        .onFailure(failure ->
            sendError(segment, message, failure.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR));
  }

  private void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(USER_ID);
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String token = jsonObject.getString(Field.TOKEN.getName());
    String password = jsonObject.getString(Field.PASSWORD.getName());
    String xSessionId = jsonObject.getString(Field.X_SESSION_ID.getName());
    if ((userId == null && token == null) || (fileId == null && folderId == null) || path == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetXref"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (path.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "PathIsEmpty"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    List<String> collaborators = new ArrayList<>();
    if (token != null) {
      PublicLink publicLink = new PublicLink(fileId);
      try {
        publicLink.findLinkByToken(token);
        if ((Utils.isStringNotNullOrEmpty(xSessionId)
                && publicLink.validateXSession(token, xSessionId))
            || publicLink.isValid(token, password)) {
          message
              .body()
              .put(Field.STORAGE_TYPE.getName(), publicLink.getStorageType())
              .put(Field.LOCATION.getName(), publicLink.getLocation());
          collaborators = publicLink.getCollaborators();
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "NoPublicAccessToThisFile"),
              HttpStatus.FORBIDDEN,
              "PL1");
          return;
        }
      } catch (PublicLinkException ple) {
        sendError(segment, message, ple.toResponseObject(), HttpStatus.FORBIDDEN, "PL1");
        return;
      }

    } else if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.EXTERNAL_ID.getName()))) {
      collaborators.add(jsonObject.getString(Field.EXTERNAL_ID.getName()));
    }

    message.body().put(Field.COLLABORATORS.getName(), new JsonArray(collaborators));
    eb_request(
        segment, StorageType.getAddress(storageType) + ".findXRef", message.body(), event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("findXRef", System.currentTimeMillis() - mills);
        });
  }

  private void doCloneFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String folderId = getRequiredString(segment, Field.FOLDER_ID.getName(), message, json);
    String userId = getRequiredString(segment, USER_ID, message, json);
    if (folderId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }

    JsonObject jsonObject = message.body();

    eb_request(segment, StorageType.getAddress(storageType) + ".clone", jsonObject, event -> {
      handleExternal(segment, event, message, mills);

      if (event.succeeded() && isOk(event)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(webSocketPrefix + "objectCreated")
            .run((Segment blockingSegment) -> {
              JsonObject resultObject = (JsonObject) event.result().body();
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".objectCreated",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put(
                          Field.SESSION_ID.getName(),
                          jsonObject.getString(Field.SESSION_ID.getName()))
                      .put(
                          Field.PARENT_ID.getName(),
                          resultObject.getString(Field.PARENT_ID.getName()))
                      .put(Field.ITEM_ID.getName(), resultObject.getString(Field.FILE_ID.getName()))
                      .put(Field.ACTION.getName(), "clone")
                      .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                      .put(Field.IS_FOLDER.getName(), false));
            });
      }

      recordExecutionTime("cloneFolder", System.currentTimeMillis() - mills);
    });
  }

  private void doCloneFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }

    JsonObject jsonObject = message.body();

    eb_request(segment, StorageType.getAddress(storageType) + ".clone", jsonObject, event -> {
      handleExternal(segment, event, message, mills);
      if (event.succeeded() && isOk(event)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(webSocketPrefix + "objectCreated")
            .run((Segment blockingSegment) -> {
              JsonObject resultObject = (JsonObject) event.result().body();
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".objectCreated",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put(
                          Field.SESSION_ID.getName(),
                          jsonObject.getString(Field.SESSION_ID.getName()))
                      .put(
                          Field.PARENT_ID.getName(),
                          resultObject.getString(Field.PARENT_ID.getName()))
                      .put(Field.ITEM_ID.getName(), resultObject.getString(Field.FILE_ID.getName()))
                      .put(Field.ACTION.getName(), "clone")
                      .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                      .put(Field.IS_FOLDER.getName(), false));
            });
      }
      recordExecutionTime("cloneFile", System.currentTimeMillis() - mills);
    });
  }

  private void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, USER_ID, message, json);
    String verId = getRequiredString(segment, Field.VER_ID.getName(), message, json);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userEmail = getRequiredString(segment, Field.EMAIL.getName(), message, json);
    String userName = getRequiredString(segment, Field.F_NAME.getName(), message, json);
    String userSurname = getRequiredString(segment, Field.SURNAME.getName(), message, json);
    if (userId == null || verId == null || fileId == null) {
      return;
    }
    json.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));

    // we do not allow promoting if someone else is hosting edit session
    Iterator<Item> xSessions = XenonSessions.getActiveSessions(
        json.getString(Field.FILE_ID.getName()), XenonSessions.Mode.EDIT);
    if (xSessions.hasNext()
        && !xSessions.next().getString(Field.USER_ID.getName()).equals(userId)) {
      sendError(
          segment, message, HttpStatus.FORBIDDEN, "CantRestoreRevisionWhileActiveEditSession");
      return;
    }

    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(segment, StorageType.getAddress(storageType) + ".promoteVersion", json, event -> {
      handleExternal(segment, event, message, mills);
      recordExecutionTime("promoteVersion", System.currentTimeMillis() - mills);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "newVersion")
          .run((Segment blockingSegment) -> {
            JsonObject eventResponse = (JsonObject) event.result().body();
            if (event.succeeded()
                && eventResponse.getString(Field.STATUS.getName()).equals(OK)) {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".newVersion",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(
                          Field.VERSION_ID.getName(),
                          eventResponse.getString(Field.VERSION_ID.getName()))
                      .put(Field.FORCE.getName(), true)
                      .put(Field.EMAIL.getName(), userEmail)
                      .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
            }
          });
    });
  }

  private void doMarkVersionAsPrinted(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    json.mergeIn(Utils.parseItemId(id, Field.FILE_ID.getName()));
    String userId = getRequiredString(segment, USER_ID, message, json);
    String fileId = json.getString(Field.FILE_ID.getName());
    String printedVersionId =
        getRequiredString(segment, Field.PRINTED_VERSION_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);
    try {
      CloudFieldFileVersion.markFileVersionAsPrinted(fileId, storageType, userId, printedVersionId);
      sendOK(segment, message, mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
  }

  private void doCreateByTmpl(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    message
        .body()
        .mergeIn(Utils.parseItemId(
            message.body().getString(Field.FOLDER_ID.getName()), Field.FOLDER_ID.getName()));

    JsonObject json = message.body();
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    String tmplId = getRequiredString(segment, "tmplId", message, json);
    String userId =
        json.containsKey(Field.USER_ID.getName()) ? json.getString(Field.USER_ID.getName()) : null;
    if (tmplId == null) {
      return;
    }

    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    // check if tmpl exists
    Map<String, Object> tmpl = Templates.getTemplate(tmplId, userId);
    if (tmpl == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "TemplateWithIdDoesNotExist"), tmplId),
          HttpStatus.NOT_FOUND);
      return;
    }
    if (json.getString(Field.FILE_NAME.getName()) == null) {
      json.put(Field.FILE_NAME.getName(), tmpl.get(Field.F_NAME.getName()));
    }
    json.put("length", ((BigDecimal) tmpl.get("flength")).longValue());
    byte[] content;
    try {
      content = s3Regional.get(tmplId);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetTemplateData"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    FileBuffer buffer = new FileBuffer();
    buffer.setUserId(json.getString(USER_ID));
    buffer.setFolderId(json.getString(Field.FOLDER_ID.getName()));
    buffer.setExternalId(json.getString(Field.EXTERNAL_ID.getName()));
    buffer.setFileName(json.getString(Field.FILE_NAME.getName()));
    buffer.setPreferences(json.getJsonObject(Field.PREFERENCES.getName()));
    buffer.setStorageType(storageType.toString());
    buffer.setData(content);
    buffer.setTmpl(true);
    buffer.setLocale(json.getString(Field.LOCALE.getName()));
    buffer.setAdmin(json.getBoolean(Field.IS_ADMIN.getName()));

    if (Utils.isObjectNameLong(buffer.getFileName())) {
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

    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".uploadFile",
        MessageUtils.generateBuffer(segment, buffer),
        event -> {
          handleExternal(segment, event, message, mills);
          if (event.succeeded() && isOk(event)) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .run((Segment blockingSegment) -> {
                  JsonObject resultJson = (JsonObject) event.result().body();
                  String resultFileId = resultJson.getString(Field.FILE_ID.getName());

                  eb_send(
                      blockingSegment,
                      WebSocketManager.address + ".objectCreated",
                      new JsonObject()
                          .put(Field.USER_ID.getName(), userId)
                          .put(
                              Field.SESSION_ID.getName(),
                              json.getString(Field.SESSION_ID.getName()))
                          .put(
                              Field.PARENT_ID.getName(),
                              Utils.getEncapsulatedId(
                                  storageType,
                                  json.getString(Field.EXTERNAL_ID.getName()),
                                  json.getString(Field.FOLDER_ID.getName())))
                          .put(Field.ITEM_ID.getName(), resultFileId)
                          .put(Field.ACTION.getName(), "upload")
                          .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                          .put(Field.IS_FOLDER.getName(), false));

                  FileLog fileLog = new FileLog(
                      userId,
                      resultFileId,
                      storageType,
                      json.getString(Field.USERNAME.getName()),
                      GMTHelper.utcCurrentTime(),
                      true,
                      FileActions.CREATE_BY_TEMPLATE,
                      GMTHelper.utcCurrentTime(),
                      json.getString(Field.FILE_NAME.getName()),
                      json.getString(Field.SESSION_ID.getName()),
                      null,
                      json.getString(Field.DEVICE.getName()) != null
                          ? json.getString(Field.DEVICE.getName())
                          : null,
                      null);
                  fileLog.sendToServer();
                });
          }
          recordExecutionTime("createByTmpl", System.currentTimeMillis() - mills);
        });
  }

  private void doGetFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String versionId = message.body().getString(Field.VER_ID.getName());
    String xSessionId = message.body().getString(Field.X_SESSION_ID.getName());
    if (fileId == null || userId == null) {
      return;
    }
    message
        .body()
        .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
        .put(Field.RETURN_DOWNLOAD_URL.getName(), returnDownloadUrl);
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String requestId =
        DownloadRequests.createRequest(userId, fileId, null, null, storageType, null, externalId);

    requestAndGetFile(
        segment,
        message,
        (!Utils.isStringNotNullOrEmpty(versionId) ? "getFile" : "getVersion"),
        message.body(),
        storageType,
        requestId,
        fileId,
        null,
        xSessionId,
        mills);

    String encapsulationMode = message.body().getString("encapsulationMode");
    if (Utils.isStringNotNullOrEmpty(encapsulationMode) && encapsulationMode.equals("0")) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            FileLog fileLog = new FileLog(
                "anonymous",
                fileId,
                storageType,
                "anonymous",
                GMTHelper.utcCurrentTime(),
                true,
                FileActions.OPEN_BOX_PROTOTYPE,
                GMTHelper.utcCurrentTime(),
                null,
                "anonymous",
                null,
                AuthManager.ClientType.BROWSER.name(),
                null);
            fileLog.sendToServer();
          });
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  private void doGetVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null) {
      return;
    }
    message
        .body()
        .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
        .put(Field.RETURN_DOWNLOAD_URL.getName(), returnDownloadUrl);
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".getVersion", message.body(), event -> {
          message.reply(event.result().body());
          XRayManager.endSegment(segment);
          recordExecutionTime("getVersion", System.currentTimeMillis() - mills);
        });
  }

  private void doGetVersionByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String token = getRequiredString(segment, Field.TOKEN.getName(), message, message.body());
    String fullFileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId =
        getRequiredString(segment, Field.VERSION_ID.getName(), message, message.body());
    String password = message.body().getString(Field.PASSWORD.getName());
    String format = message.body().getString("format");
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());

    if (Utils.anyNull(token, fullFileId, versionId)) {
      return;
    }

    try {
      KudoFileId fileIds = KudoFileId.parseFullId(fullFileId, IdName.FILE);

      VersionLink link = VersionLinkRepository.getInstance()
          .getByTokenAndVersionId(fileIds.getId(), versionId, token);

      LinkService.isLinkValid(link, password);

      // conversion has started - lets check it and return data if done
      if (Utils.isStringNotNullOrEmpty(downloadToken)) {
        Item request = DownloadRequests.getRequest(downloadToken);
        log.info(String.format(
            "[VERSION_LINK] (doGetVersionByToken) Checking conversion | downloadToken: %s , fileId: %s, requestJson: %s",
            downloadToken, fullFileId, Objects.nonNull(request) ? request.toJSON() : "null"));

        if (Objects.isNull(request)
            || !request.getString(Field.FILE_ID.getName()).equals(link.getObjectId())) {
          log.error(String.format(
              "[VERSION_LINK] (doGetVersionByToken) No such request | downloadToken: %s , fileId: %s",
              downloadToken, fullFileId));
          throw new KudoFileException(
              Utils.getLocalizedString(message, "UnknownRequestToken"),
              KudoErrorCodes.FILE_DOWNLOAD_ERROR,
              HttpStatus.BAD_REQUEST,
              "UnknownRequestToken");
        }

        JobStatus status = JobStatus.findStatus(request.getString(Field.STATUS.getName()));

        if (status.equals(JobStatus.IN_PROGRESS)) {
          log.info(String.format(
              "[VERSION_LINK] (doGetVersionByToken) Conversion in progress | downloadToken: %s , fileId: %s",
              downloadToken, fullFileId));
          sendOK(segment, message, HttpStatus.CREATED, System.currentTimeMillis() - mills);
          return;
        }

        String finalFilePath = request.getString("finalFilePath");

        if (status.equals(JobStatus.ERROR) || status.equals(JobStatus.UNKNOWN)) {
          log.error(String.format(
              "[VERSION_LINK] (doGetVersionByToken) Conversion failed | downloadToken: %s , fileId: %s, reason: %s",
              downloadToken, fullFileId, request.getString(Field.ERROR.getName())));
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("deleteDownloadRequest")
              .run((Segment blockingSegment) -> {
                DownloadRequests.deleteRequest(downloadToken);
                s3Regional.delete(finalFilePath);
              });
          throw new KudoFileException(
              request.getString(Field.ERROR.getName()),
              KudoErrorCodes.FILE_DOWNLOAD_ERROR,
              HttpStatus.INTERNAL_SERVER_ERROR);
        }

        log.info(String.format(
            "[VERSION_LINK] (doGetVersionByToken) Conversion succeed | downloadToken: %s , fileId: %s",
            downloadToken, fullFileId));

        DownloadRequests.deleteRequest(downloadToken);

        byte[] data = s3Regional.get(finalFilePath);
        s3Regional.delete(finalFilePath);

        String name = link.getObjectName()
            .substring(0, link.getObjectName().lastIndexOf('.'))
            .concat(".pdf");

        finishGetFileVersionByToken(message, data, versionId, name, true);

        XRayManager.endSegment(segment);
      }

      // this request requires file conversion
      // start conversion and return requestId
      else if (Utils.isStringNotNullOrEmpty(format)) {
        format = format.toUpperCase();
        if (!format.equals(FileFormats.FileType.PDF.name())) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FormatIsNotValid"),
              HttpStatus.BAD_REQUEST);
        }

        String tempFilePath = String.join(
            ThumbnailsManager.partsSeparator,
            StorageType.getShort(link.getStorageType()),
            link.getObjectId(),
            link.getVersionId(),
            link.getVersionType().equals(VersionType.SPECIFIC)
                ? emptyString
                : String.valueOf(mills));

        String finalFilePath =
            tempFilePath.concat(FileFormats.FileExtension(FileFormats.FileType.PDF.name()));

        String requestId = DownloadRequests.createVersionConvertRequest(
            link.getUserId(),
            link.getObjectId(),
            link.getStorageType(),
            link.getExternalId(),
            format,
            tempFilePath,
            finalFilePath,
            Extensions.getExtension(link.getObjectName()));

        eb_send(
            segment,
            ThumbnailsManager.address + ".convertFile",
            new JsonObject()
                .put("format", format)
                .put(Field.STORAGE_TYPE.getName(), link.getStorageType().name())
                .put(Field.FILE_ID.getName(), link.getObjectId())
                .put(Field.EXTERNAL_ID.getName(), link.getExternalId())
                .put(Field.TOKEN.getName(), requestId)
                .put(Field.USER_ID.getName(), link.getUserId())
                .put(Field.EXT.getName(), Extensions.getExtension(link.getObjectName()))
                .put(Field.VERSION_ID.getName(), link.getVersionId())
                .put("tempFilePath", tempFilePath)
                .put("finalFilePath", finalFilePath));

        log.info(String.format(
            "[VERSION_LINK] (doGetVersionByToken) Conversion started | downloadToken: %s , format: %s , fileId: %s",
            downloadToken, format, fullFileId));

        sendOK(
            segment,
            message,
            HttpStatus.CREATED,
            new JsonObject().put(Field.DOWNLOAD_TOKEN.getName(), requestId),
            System.currentTimeMillis() - mills);

        XRayManager.endSegment(segment);
      }

      // regular direct file download
      else {
        log.info(String.format(
            "[VERSION_LINK] (doGetVersionByToken) Regular file download | fileId: %s", fullFileId));

        String realVersionId = versionId;

        // In case of LAST_PRINTED version doesn't exist - LATEST is required
        // and will be retrieved inside native storage verticle ".getVersionByToken"
        if (link.getVersionType().equals(VersionType.LAST_PRINTED)) {
          Item cloudVersion = CloudFieldFileVersion.getCloudFieldFileVersion(
              fileIds.getId(), link.getStorageType().toString());

          // if (Objects.isNull(cloudVersion)) {
          //   sendError(
          //       segment,
          //       message,
          //       Utils.getLocalizedString(message, "LastPrintedVersionDoesNotExist"),
          //       HttpStatus.NOT_FOUND);
          //   return;
          // }

          realVersionId = Objects.nonNull(cloudVersion)
              ? cloudVersion.getString(Field.PRINTED_VERSION_ID.getName())
              : VersionType.LATEST.toString();
        }

        eb_request(
            segment,
            StorageType.getAddress(fileIds.getStorageType()) + ".getVersionByToken",
            fileIds
                .toJson()
                .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
                .put(Field.VERSION_ID.getName(), realVersionId)
                .put(Field.LINK_OWNER_ID.getName(), link.getOwnerIdentity())
                .put(Field.USER_ID.getName(), link.getUserId())
                .put(Field.LINK_TYPE.getName(), link.getType().toString()),
            event -> {
              message.reply(event.result().body());
              XRayManager.endSegment(segment);
              recordExecutionTime("doGetVersionByToken", System.currentTimeMillis() - mills);
            });
      }
    } catch (Exception exception) {
      log.error(String.format(
          "[VERSION_LINK] (doGetVersionByToken) doGetVersionByToken error | downloadToken: %s , fileId: %s, Exception: %s \nStackTrace: %s",
          downloadToken,
          fullFileId,
          exception.getMessage(),
          ExceptionUtils.getStackTrace(exception)));
      sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void doDeleteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".deleteVersion", message.body(), event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("deleteVersion", System.currentTimeMillis() - mills);
        });
  }

  private void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".getVersions", message.body(), event -> {
          if (event.succeeded()) {
            if (OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
              JsonObject response = (JsonObject) event.result().body();
              Item cFVersionItem =
                  CloudFieldFileVersion.getCloudFieldFileVersion(fileId, storageType.name());
              if (Objects.nonNull(cFVersionItem)
                  && cFVersionItem.hasAttribute(Field.PRINTED_VERSION_ID.getName())) {
                response.put(
                    Field.LAST_PRINTED_VERSION_ID.getName(),
                    cFVersionItem.getString(Field.PRINTED_VERSION_ID.getName()));
                if (cFVersionItem.hasAttribute(Field.UPDATED.getName())) {
                  response.put(
                      Field.LAST_PRINTED_TIME.getName(),
                      cFVersionItem.getLong(Field.UPDATED.getName()));
                }
              }
              sendOK(segment, message, response, mills);
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
          recordExecutionTime("getVersions", System.currentTimeMillis() - mills);
        });
  }

  private void doGetLatestVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".getLatestVersionId",
        message.body(),
        event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("getLatestVersionId", System.currentTimeMillis() - mills);
        });
  }

  private void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    String storageTypeVal = getRequiredString(
        segment, Field.STORAGE_TYPE.getName(), message, parsedMessage.getJsonObject());
    String userId = getRequiredString(segment, USER_ID, message, parsedMessage.getJsonObject());
    String fileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, parsedMessage.getJsonObject());
    if (storageTypeVal == null || userId == null || fileId == null) {
      return;
    }

    // we do not allow uploading versions if someone else is hosting edit session
    Iterator<Item> xSessions = XenonSessions.getActiveSessions(fileId, XenonSessions.Mode.EDIT);
    if (xSessions.hasNext()
        && !xSessions.next().getString(Field.USER_ID.getName()).equals(userId)) {
      sendError(segment, message, HttpStatus.FORBIDDEN, "CantUploadVersionWhileActiveEditSession");
      return;
    }

    StorageType storageType = StorageType.getStorageType(storageTypeVal);
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    Buffer buffer = message.body();
    String presignedUploadId =
        parsedMessage.getJsonObject().getString(Field.PRESIGNED_UPLOAD_ID.getName());
    if (Utils.isStringNotNullOrEmpty(presignedUploadId)) {
      if (Objects.nonNull(segment)) {
        segment.putAnnotation(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId);
      }
      if (!storageType.equals(StorageType.SAMPLES)) {
        String key = S3Regional.createPresignedKey(presignedUploadId, userId, storageTypeVal);
        if (returnDownloadUrl) {
          String downloadUrl = s3Regional.getDownloadUrl(key, null, null);
          buffer = MessageUtils.generateBuffer(
              segment,
              parsedMessage.getJsonObject().put(Field.DOWNLOAD_URL.getName(), downloadUrl),
              parsedMessage.getContentAsByteArray(),
              null);
        } else {
          byte[] data = s3Regional.getFromBucket(bucket, key);
          if (Objects.nonNull(data)) {
            buffer.appendBytes(data);
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("setFileDownloadedAt_UploadVersion")
                .runWithoutSegment(
                    () -> S3PresignedUploadRequests.setFileDownloadedAt(presignedUploadId, userId));
          }
        }
      }
    }
    eb_request(segment, StorageType.getAddress(storageType) + ".uploadVersion", buffer, event -> {
      if (event.succeeded()) {
        if (OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
          sendOK(segment, message, (JsonObject) event.result().body(), mills);
          if (Utils.isStringNotNullOrEmpty(presignedUploadId)
              && !storageType.equals(StorageType.SAMPLES)) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("setFileUploadedToStorageAt_UploadVersion")
                .runWithoutSegment(() -> S3PresignedUploadRequests.setFileUploadedToStorageAt(
                    presignedUploadId, userId));
          }
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
      recordExecutionTime("uploadVersion", System.currentTimeMillis() - mills);
    });
  }

  private void doGetS3PreSignedUploadURL(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body());
    final String fileName =
        getRequiredString(segment, Field.FILE_NAME_C.getName(), message, message.body());
    final String presignedUploadType =
        message.body().getString(Field.PRESIGNED_UPLOAD_TYPE.getName());
    final String contentType = message.body().getString(Field.CONTENT_TYPE.getName());
    final String fileId = message.body().getString(Field.FILE_ID.getName());

    String presignedUploadId =
        S3PresignedUploadRequests.createRequest(userId, fileName, storageType, presignedUploadType);
    JsonObject response =
        new JsonObject().put(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId);
    // For KD, we treat the "presignedUploadId" as "fileId" to generate actual key for presigned
    // upload
    if (storageType.equals(StorageType.SAMPLES.name())) {
      eb_request(
          segment,
          SimpleFluorine.address + ".createPresignedUrl",
          new JsonObject()
              .put("fileId", (Objects.nonNull(fileId) ? fileId : presignedUploadId))
              .put("userId", userId)
              .put("contentType", contentType),
          event -> {
            if (event.succeeded()) {
              JsonObject result = (JsonObject) event.result().body();
              if (OK.equals(result.getString(Field.STATUS.getName()))) {
                sendOK(
                    segment,
                    message,
                    response.put("presignedUrl", result.getString("presignedUrl")),
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
    } else {
      String key = S3Regional.createPresignedKey(presignedUploadId, userId, storageType);
      try {
        URL presignedUrl = s3Regional.createPresignedUrlToUploadFile(bucket, key, contentType);
        sendOK(segment, message, response.put("presignedUrl", presignedUrl.toString()), mills);
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
      }
    }
  }

  private void doCancelUpload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String storageTypeVal =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body());
    StorageType storageType = StorageType.getStorageType(storageTypeVal);
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String uploadToken = message.body().getString(Field.UPLOAD_TOKEN.getName());
    String presignedUploadId = message.body().getString(Field.PRESIGNED_UPLOAD_ID.getName());
    try {
      if (Utils.isStringNotNullOrEmpty(uploadToken)) {
        S3PresignedUploadRequests.setRequestAsCancelled(uploadToken, userId);
      }
      if (Utils.isStringNotNullOrEmpty(presignedUploadId)) {
        if (storageType.equals(StorageType.SAMPLES)) {
          JsonObject defaultS3 =
              config.getProperties().getSimpleStorageAsJson().getJsonObject("default");
          String samplesBucket = defaultS3.getString(Field.BUCKET.getName());
          String samplesRegion = defaultS3.getString(Field.REGION.getName());
          S3Regional samplesS3 = Users.getS3Regional(
              (Item) null,
              userId,
              new S3Regional(samplesBucket, samplesRegion),
              config,
              samplesBucket);
          samplesS3.delete(SimpleStorage.generatePath(userId, presignedUploadId));
        } else {
          S3PresignedUploadRequests.removeS3File(
              bucket, region, storageTypeVal, presignedUploadId, userId);
        }
      }
      sendOK(segment, message, millis);
    } catch (Exception ex) {
      sendError(segment, message, ex.getMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void doCheckUpload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    final String userId = getRequiredString(segment, USER_ID, message, message.body());
    final String uploadToken =
        getRequiredString(segment, Field.UPLOAD_TOKEN.getName(), message, message.body());
    final String storageTypeVal =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body());
    StorageType storageType = StorageType.getStorageType(storageTypeVal);
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    try {
      Item upload = S3PresignedUploadRequests.getRequestById(uploadToken, userId);
      // check if upload token is not found in the DB
      if (upload == null || !upload.getString(Field.SK.getName()).equals(userId)) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "UnknownRequestToken"),
            KudoErrorCodes.FILE_UPLOAD_ERROR,
            HttpStatus.BAD_REQUEST,
            "UnknownRequestToken");
      }
      // check if error occurred in upload request
      if (upload.hasAttribute("errorDetails") && Objects.nonNull(upload.get("errorDetails"))) {
        KudoFileException.checkAndThrowKudoFileException(
            message,
            new JsonObject(upload.getMap("errorDetails")),
            HttpStatus.BAD_REQUEST,
            KudoErrorCodes.FILE_UPLOAD_ERROR);
        return;
      }
      // check if the upload request is completed
      if (upload.hasAttribute("uploadResult") && Objects.nonNull(upload.get("uploadResult"))) {
        JsonObject uploadResult = new JsonObject(upload.getMap("uploadResult"));
        sendOK(segment, message, uploadResult, millis);
        return;
      }
      sendOK(
          segment,
          message,
          HttpStatus.ACCEPTED,
          new JsonObject().put("isDownloaded", upload.hasAttribute("downloadedAt")),
          System.currentTimeMillis() - millis);
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    }
  }

  private void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    ParsedMessage parsedMessage = MessageUtils.parse(message);
    String storageTypeVal = getRequiredString(
        segment, Field.STORAGE_TYPE.getName(), message, parsedMessage.getJsonObject());
    String userId = getRequiredString(segment, USER_ID, message, parsedMessage.getJsonObject());
    String fileName =
        getRequiredString(segment, Field.NAME.getName(), message, parsedMessage.getJsonObject());
    String fileSessionId =
        parsedMessage.getJsonObject().getString(Field.X_SESSION_ID.getName(), emptyString);
    // TODO: Add logs to Xray
    if (storageTypeVal == null || userId == null || fileName == null) {
      return;
    }
    StorageType storageType = StorageType.getStorageType(storageTypeVal);
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (Utils.isObjectNameLong(fileName)) {
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
    String externalId = parsedMessage.getJsonObject().containsKey(Field.EXTERNAL_ID.getName())
        ? parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (Utils.isStringNotNullOrEmpty(externalId) && !storageType.equals(StorageType.SAMPLES)) {
      Item account = ExternalAccounts.getExternalAccount(userId, externalId);
      if (account == null) {
        try {
          throw new KudoFileException(
              Utils.getLocalizedString(message, "SpecifiedStorageAccountIsntFound"),
              KudoErrorCodes.STORAGE_IS_NOT_CONNECTED,
              kong.unirest.HttpStatus.BAD_REQUEST,
              "SpecifiedStorageAccountIsntFound");
        } catch (KudoFileException kfe) {
          sendError(
              segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
          return;
        }
      }
    }
    Buffer buffer = message.body();
    boolean reply = true;
    String presignedUploadId =
        parsedMessage.getJsonObject().getString(Field.PRESIGNED_UPLOAD_ID.getName());
    if (Utils.isStringNotNullOrEmpty(presignedUploadId)) {
      if (Objects.nonNull(segment)) {
        segment.putAnnotation(Field.PRESIGNED_UPLOAD_ID.getName(), presignedUploadId);
      }
      if (!storageType.equals(StorageType.SAMPLES)) {
        // ending the request for other storages, check the upload status periodically from client
        reply = false;
        message.reply(
            new JsonObject().put(Field.STATUS.getName(), OK).put("uploadToken", presignedUploadId));

        String key = S3Regional.createPresignedKey(presignedUploadId, userId, storageTypeVal);

        if (returnDownloadUrl) {
          String downloadUrl = s3Regional.getDownloadUrl(key, null, null);
          buffer = MessageUtils.generateBuffer(
              segment,
              parsedMessage.getJsonObject().put(Field.DOWNLOAD_URL.getName(), downloadUrl),
              parsedMessage.getContentAsByteArray(),
              null);
        } else {
          byte[] data = s3Regional.getFromBucket(bucket, key);
          if (Objects.nonNull(data)) {
            buffer.appendBytes(data);
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("setFileDownloadedAt_UploadFile")
                .runWithoutSegment(
                    () -> S3PresignedUploadRequests.setFileDownloadedAt(presignedUploadId, userId));
          }
        }
        Item presignedRequest = S3PresignedUploadRequests.getRequestById(presignedUploadId, userId);
        if (Objects.nonNull(presignedRequest) && presignedRequest.hasAttribute("cancelled")) {
          return;
        }
      }
    }
    boolean finalReply = reply;
    eb_request(segment, StorageType.getAddress(storageType) + ".uploadFile", buffer, event -> {
      boolean isConflicted = parsedMessage.getJsonObject().containsKey("fileSessionExpired");
      String oldFileId = parsedMessage.getJsonObject().getString(Field.FILE_ID.getName());
      String fileId = oldFileId;
      try {
        if (event.succeeded()) {
          JsonObject result = (JsonObject) event.result().body();
          if (OK.equals(result.getString(Field.STATUS.getName()))) {
            log.info("UPLOAD FILE RESULT for file - " + fileId + " - " + result);
            if (Utils.isStringNotNullOrEmpty(presignedUploadId)
                && !storageType.equals(StorageType.SAMPLES)) {
              new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                  .withName("setFileUploadedToStorageAt_UploadFile")
                  .runWithoutSegment(() -> S3PresignedUploadRequests.setFileUploadedToStorageAt(
                      presignedUploadId, userId));
            }
            String versionId = result.getString(Field.VERSION_ID.getName());
            isConflicted = result.getBoolean(Field.IS_CONFLICTED.getName(), false);
            String resultFileId = result.getString(Field.FILE_ID.getName());
            if (isConflicted) {
              parsedMessage.getJsonObject().put("oldFileId", oldFileId);
              fileId = Utils.parseItemId(resultFileId, Field.FILE_ID.getName())
                  .getString(Field.FILE_ID.getName());
              parsedMessage.getJsonObject().put(Field.FILE_ID.getName(), fileId);

              // Log conflicting file creation
              String reason = result.containsKey(Field.CONFLICTING_FILE_REASON.getName())
                  ? result.getString(Field.CONFLICTING_FILE_REASON.getName())
                  : emptyString;
              if (parsedMessage.getJsonObject().containsKey("fileSessionExpired")
                  && parsedMessage.getJsonObject().getBoolean("fileSessionExpired", false)) {
                reason = "File session has expired";
              }
              new ConflictingFileLogger()
                  .log(
                      Level.ERROR,
                      userId,
                      oldFileId,
                      storageType,
                      "conflictedFileCreated",
                      String.format(
                          "Conflicted file was created: reason: %s, newFileId: %s",
                          reason, fileId));
            }
            result
                .put("fileConflicted", isConflicted)
                .put(
                    "fileSessionExpired",
                    parsedMessage.getJsonObject().containsKey("fileSessionExpired"));
            result.remove(Field.IS_CONFLICTED.getName());
            boolean finalIsConflicted = isConflicted;
            String finalFileId = fileId;
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .run((Segment blockingSegment) -> {
                  if (Utils.isStringNotNullOrEmpty(oldFileId) && !finalIsConflicted) {
                    log.info("[ METADATA ] Update on doUploadFile");
                    FileMetaData.putMetaData(
                        oldFileId,
                        storageType,
                        fileName,
                        result.getString(Field.THUMBNAIL_NAME.getName()),
                        versionId,
                        userId);
                  }
                  if (storageType.equals(StorageType.ONSHAPE)
                      && Utils.isStringNotNullOrEmpty(versionId)) {
                    String osFileId;
                    if (Utils.isStringNotNullOrEmpty(finalFileId)) {
                      osFileId = finalFileId;
                    } else {
                      osFileId = Utils.parseItemId(resultFileId, Field.FILE_ID.getName())
                          .getString(Field.FILE_ID.getName());
                    }
                    FileMetaData.updateOSWorkSpaceVersion(osFileId, versionId);
                  }
                  if (config.getProperties().getNewSessionWorkflow()
                      && Utils.isStringNotNullOrEmpty(oldFileId)) {
                    eb_send(
                        blockingSegment,
                        XSessionManager.address + ".update",
                        parsedMessage
                            .getJsonObject()
                            .put(Field.VALIDATE_SESSION.getName(), false)
                            .put(Field.IS_CONFLICTED.getName(), finalIsConflicted)
                            .put(Field.CHANGES_ARE_SAVED.getName(), true));
                  }

                  AuthManager.ClientType clientType = AuthManager.ClientType.getClientType(
                      parsedMessage.getJsonObject().getString(Field.DEVICE.getName()));
                  boolean isNotKudo = !clientType.equals(AuthManager.ClientType.BROWSER)
                      && !clientType.equals(AuthManager.ClientType.BROWSER_MOBILE);
                  if (finalIsConflicted) {
                    // For Kudo this is handled properly by opening the conflicting file via dialog
                    log.info(
                        "Encountered conflicted file. Going to save recent file if: " + isNotKudo
                            + " device reported: "
                            + parsedMessage.getJsonObject().getString(Field.DEVICE.getName()));
                    if (isNotKudo) {
                      eb_send(
                          blockingSegment,
                          RecentFilesVerticle.address + ".saveRecentFile",
                          parsedMessage.getJsonObject());
                    }
                  } else {
                    // only do this while updating an existing file (WB-1421)
                    if (Utils.isStringNotNullOrEmpty(oldFileId)) {
                      eb_send(
                          blockingSegment,
                          RecentFilesVerticle.address + ".updateRecentFile",
                          new JsonObject()
                              .put(Field.USER_ID.getName(), userId)
                              .put(Field.FILE_ID.getName(), oldFileId)
                              .put(
                                  Field.THUMBNAIL_NAME.getName(),
                                  result.getString(Field.THUMBNAIL_NAME.getName()))
                              .put(Field.STORAGE_TYPE.getName(), storageType)
                              .put("fileUpload", true));

                      eb_send(
                          blockingSegment,
                          NotificationEvents.address + ".modifiedFile",
                          new JsonObject()
                              .put(Field.FILE_ID.getName(), oldFileId)
                              .put(Field.USER_ID.getName(), userId)
                              .put(Field.STORAGE_TYPE.getName(), storageType));
                    }
                  }

                  // this is new file - send ws event
                  if (!Utils.isStringNotNullOrEmpty(oldFileId)) {
                    // means its project for OS/TR
                    boolean isFolder = result.getBoolean("createdFolder", false);

                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".objectCreated",
                        new JsonObject()
                            .put(Field.USER_ID.getName(), userId)
                            .put(
                                Field.SESSION_ID.getName(),
                                parsedMessage.getJsonObject().getString(Field.SESSION_ID.getName()))
                            .put(
                                Field.PARENT_ID.getName(),
                                Utils.getEncapsulatedId(
                                    storageType,
                                    externalId,
                                    parsedMessage
                                        .getJsonObject()
                                        .getString(Field.FOLDER_ID.getName())))
                            .put(
                                Field.ITEM_ID.getName(),
                                isFolder
                                    ? result.getString(Field.FOLDER_ID.getName())
                                    : resultFileId)
                            .put(Field.ACTION.getName(), "upload")
                            .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                            .put(Field.IS_FOLDER.getName(), isFolder));
                  }

                  // update versionId for xSession
                  if (Utils.isStringNotNullOrEmpty(fileSessionId)) {
                    XenonSessions.setVersionId(oldFileId, fileSessionId, versionId);
                  }
                });
            result.put("changeId", versionId);
            updateFileSessionState(oldFileId, fileSessionId, isConflicted);
            if (finalReply) {
              sendOK(segment, message, result, mills);
            }
            S3PresignedUploadRequests.setRequestAsCompleted(presignedUploadId, userId, result);
          } else {
            KudoFileException.checkAndThrowKudoFileException(
                message, result, HttpStatus.BAD_REQUEST, KudoErrorCodes.FILE_UPLOAD_ERROR);
          }
        } else {
          throw new KudoFileException(
              event.cause().getLocalizedMessage(),
              KudoErrorCodes.FILE_UPLOAD_ERROR,
              HttpStatus.BAD_REQUEST);
        }
      } catch (KudoFileException kfe) {
        if (Utils.isStringNotNullOrEmpty(presignedUploadId)
            && !storageType.equals(StorageType.SAMPLES)) {
          S3PresignedUploadRequests.setErrorDetails(
              presignedUploadId, userId, kfe.toResponseObject().getMap());
        }
        updateFileSessionState(oldFileId, fileSessionId, isConflicted);
        if (finalReply) {
          sendError(
              segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        }
      } finally {
        if (Utils.isStringNotNullOrEmpty(presignedUploadId)
            && !storageType.equals(StorageType.SAMPLES)) {
          S3PresignedUploadRequests.removeS3File(
              bucket, region, storageTypeVal, presignedUploadId, userId);
        }
      }
      recordExecutionTime("uploadFile", System.currentTimeMillis() - mills);
    });
  }

  private void doGetChunk(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = getRequiredString(segment, Field.VER_ID.getName(), message, jsonObject);
    Integer start = jsonObject.getInteger(Field.START.getName()),
        end = jsonObject.getInteger(Field.END.getName());
    byte[] result;
    try {
      if (start != null) {
        S3Object obj = s3Regional.getObject(id, start, end);
        result = IOUtils.toByteArray(obj.getObjectContent());
        Map<String, Object> raw = new HashMap<>(obj.getObjectMetadata().getRawMetadata());
        raw.put("Last-Modified", raw.get("Last-Modified").toString());
        sendOK(segment, message, new JsonObject().put("body", result).put("raw", raw), mills);
        return;
      } else {
        result = s3Regional.get(id);
      }
    } catch (Exception ex) {
      result = new byte[0];
      log.error("Error in getting data chunk for id " + id + " : " + ex);
    }
    message.reply(result);
    XRayManager.endSegment(segment);
    recordExecutionTime("getChunk", System.currentTimeMillis() - mills);
  }

  private void doUntrash(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    message
        .body()
        .mergeIn(Utils.parseItemId(
            message.body().getString(Field.FILE_ID.getName()), Field.FILE_ID.getName()));
    message
        .body()
        .mergeIn(Utils.parseItemId(
            message.body().getString(Field.FOLDER_ID.getName()), Field.FOLDER_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".restore",
        message.body(),
        event -> handleExternal(segment, event, message, mills));
  }

  private void doEraseAll(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String storageTypeStr = message.body().getString(Field.STORAGE_TYPE.getName());

    // DK 31/08/21: by request from Wiktor Kozłowski (wiktor.kozlowski@graebert.com)
    // we accept both short ST (e.g. GD) and long (e.g. GDRIVE)
    StorageType storageType = StorageType.getByShort(storageTypeStr);
    if (storageType == null) {
      storageType = StorageType.getStorageType(storageTypeStr);
    }
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }

    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".eraseAll",
        message.body(),
        event -> handleExternal(segment, event, message, mills));
  }

  private void doEraseMultiple(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonArray files = message.body().getJsonArray(Field.FILES.getName());
    JsonArray folders = message.body().getJsonArray(Field.FOLDERS.getName());
    int totalObjects = (files != null ? files.size() : 0) + (folders != null ? folders.size() : 0);
    boolean synchronousExecution = totalObjects < 10;
    message.body().put(Field.OWNER_ID.getName(), message.body().getString(USER_ID));
    if ((files == null || files.isEmpty()) && (folders == null || folders.isEmpty())) {
      sendOK(segment, message);
      return;
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    JsonArray errors = new JsonArray();
    List<Future<Void>> queue = new ArrayList<>();
    if (files != null && !files.isEmpty()) {
      for (Object fileId : files) {
        Promise<Void> handler = Promise.promise();
        if (synchronousExecution || storageType.equals(StorageType.SAMPLES)) {
          queue.add(handler.future());
        }
        message.body().mergeIn(Utils.parseItemId((String) fileId, Field.FILE_ID.getName()));
        doDeleteFile(message, handler, errors, false);
      }
    }
    message.body().remove(Field.FILE_ID.getName());
    if (folders != null && !folders.isEmpty()) {
      for (Object folderId : folders) {
        Promise<Void> handler = Promise.promise();
        // handling the folder erase for samples internally (inside SimpleFluorine)
        if (synchronousExecution && !storageType.equals(StorageType.SAMPLES)) {
          queue.add(handler.future());
        }
        message.body().mergeIn(Utils.parseItemId((String) folderId, Field.FOLDER_ID.getName()));
        doDeleteFolder(message, handler, errors, false);
      }
    }
    if (!queue.isEmpty()) {
      TypedCompositeFuture.join(queue).onComplete((event) -> {
        if (event.succeeded()) {
          if (storageType.equals(StorageType.SAMPLES)) {
            eb_send(
                segment,
                SimpleFluorine.address + ".sendSampleUsageWSNotification",
                new JsonObject().put(Field.USER_ID.getName(), message.body().getString(USER_ID)));
          }
          sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
        } else {
          if (Objects.nonNull(event.cause())) {
            sendError(
                segment, message, event.cause().getLocalizedMessage(), HttpStatus.BAD_REQUEST);
          } else {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "SomethingWentWrongForFiles"),
                HttpStatus.INTERNAL_SERVER_ERROR);
          }
        }
      });
    } else {
      sendOK(segment, message);
    }
  }

  private void doRestoreMultiple(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    JsonArray files = jsonObject.getJsonArray(Field.FILES.getName());
    JsonArray folders = jsonObject.getJsonArray(Field.FOLDERS.getName());
    boolean namesIncluded =
        jsonObject.containsKey("namesIncluded") && jsonObject.getBoolean("namesIncluded");
    if ((files == null || files.isEmpty()) && (folders == null || folders.isEmpty())) {
      sendOK(segment, message);
      return;
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    int totalObjects = (files != null ? files.size() : 0) + (folders != null ? folders.size() : 0);
    boolean synchronousExecution = totalObjects < 10;
    List<Future<Void>> queue = new ArrayList<>();
    JsonArray errors = new JsonArray();
    if (files != null && !files.isEmpty()) {
      for (Object fileObj : files) {
        String fileId;
        if (namesIncluded) {
          fileId = ((JsonObject) fileObj).getString(Field.ID.getName());
        } else {
          fileId = (String) fileObj;
        }
        JsonObject bodyCopy = jsonObject.copy();
        bodyCopy.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
        Promise<Void> handler = Promise.promise();
        if (synchronousExecution) {
          queue.add(handler.future());
        }
        eb_request(
            segment,
            StorageType.getAddress(storageType) + ".restore",
            bodyCopy,
            (messageAsyncResult) -> handleObjectEvent(messageAsyncResult, handler, errors, fileId));
      }
    }
    if (folders != null && !folders.isEmpty()) {
      for (Object folderObj : folders) {
        String folderId;
        if (namesIncluded) {
          folderId = ((JsonObject) folderObj).getString(Field.ID.getName());
        } else {
          folderId = (String) folderObj;
        }
        JsonObject bodyCopy = jsonObject.copy();
        bodyCopy.mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()));
        Promise<Void> handler = Promise.promise();
        if (synchronousExecution) {
          queue.add(handler.future());
        }
        eb_request(
            segment,
            StorageType.getAddress(storageType) + ".restore",
            bodyCopy,
            (messageAsyncResult) ->
                handleObjectEvent(messageAsyncResult, handler, errors, folderId));
      }
    }
    if (synchronousExecution) {
      TypedCompositeFuture.join(queue).onComplete((event) -> {
        if (event.succeeded()) {
          sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
        } else {
          if (Objects.nonNull(event.cause())) {
            sendError(
                segment, message, event.cause().getLocalizedMessage(), HttpStatus.BAD_REQUEST);
          } else {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "SomethingWentWrongForFiles"),
                HttpStatus.INTERNAL_SERVER_ERROR);
          }
        }
      });
    } else {
      sendOK(segment, message);
    }
  }

  private void handleObjectEvent(
      AsyncResult<Message<Object>> event,
      Promise<Void> promise,
      JsonArray errors,
      String objectId) {
    if (event.succeeded()) {
      JsonObject result = (JsonObject) event.result().body();
      if (!OK.equals(result.getString(Field.STATUS.getName()))) {
        errors.add(new JsonObject().put(objectId, getDeleteErrorObject(result)));
      }
      promise.complete();
    } else {
      promise.fail(event.cause());
    }
  }

  private void doTrashMultiple(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    JsonArray files = json.getJsonArray(Field.FILES.getName());
    JsonArray folders = json.getJsonArray(Field.FOLDERS.getName());
    int totalObjects = (files != null ? files.size() : 0) + (folders != null ? folders.size() : 0);
    boolean synchronousExecution = totalObjects < 10;
    boolean confirmed =
        !json.containsKey("confirmed") || Boolean.parseBoolean(json.getString("confirmed"));
    JsonArray editingFiles = new JsonArray();
    if ((files == null || files.isEmpty()) && (folders == null || folders.isEmpty())) {
      sendOK(segment, message);
      return;
    }
    Set<String> filesSet = files == null
        ? new HashSet<>()
        : files.stream().map(String::valueOf).collect(Collectors.toSet());
    Set<String> foldersSet = folders == null
        ? new HashSet<>()
        : folders.stream().map(String::valueOf).collect(Collectors.toSet());
    // check if there are running editing sessions for files
    if (!confirmed && !filesSet.isEmpty()) {
      Entity subSegment =
          XRayManager.createSubSegment(operationGroup, segment, "collectAllSessionForFiles");

      XenonSessions.collectSessionsForFiles(filesSet, true).forEach(e -> {
        editingFiles.add(e.getJson());
        files.remove(e.getFileId());
      });

      XRayManager.endSegment(subSegment);

      if (!editingFiles.isEmpty()) {
        JsonObject result = new JsonObject()
            .put(
                Field.MESSAGE.getName(),
                Utils.getLocalizedString(message, "ThereIsExistingEditingSession"))
            .put(Field.FILES.getName(), editingFiles);
        sendError(segment, message, result, HttpStatus.PRECONDITION_FAILED);
        return;
      }
      message.body().put(Field.FILES.getName(), files);
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    if (StorageType.DROPBOX.equals(storageType)) {
      eb_request(
          segment,
          StorageType.getAddress(storageType) + ".trashMultiple",
          message.body(),
          event -> {
            if (event.succeeded()) {
              if (OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
                sendOK(segment, message, (JsonObject) event.result().body(), mills);
                if (Objects.nonNull(folders) && !folders.isEmpty()) {
                  new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                      .withName("validateRecentFilesAfterTrash")
                      .runWithoutSegment(() -> {
                        String userId = getRequiredString(segment, USER_ID, message, json);
                        validateRecentFilesAfterTrash(
                            segment, message, userId, storageType, 2000, true);
                      });
                }
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
            recordExecutionTime("trashMultiple", System.currentTimeMillis() - mills);
          });
    } else {
      JsonArray errors = new JsonArray();
      List<Future<Void>> queue = new ArrayList<>();
      if (files != null && !files.isEmpty()) {
        for (Object fileId : files) {
          Promise<Void> handler = Promise.promise();
          if (synchronousExecution) {
            queue.add(handler.future());
          }
          message.body().mergeIn(Utils.parseItemId((String) fileId, Field.FILE_ID.getName()));
          trashFile(
              segment,
              message,
              handler,
              message.body().getString(Field.FILE_ID.getName()),
              errors,
              mills);
        }
      }
      message.body().remove(Field.FILE_ID.getName());
      if (folders != null && !folders.isEmpty()) {
        for (Object folderId : folders) {
          Promise<Void> handler = Promise.promise();
          if (synchronousExecution) {
            queue.add(handler.future());
          }
          message.body().mergeIn(Utils.parseItemId((String) folderId, Field.FOLDER_ID.getName()));
          if (storageType.equals(StorageType.GDRIVE)) {
            message.body().put("trashed", false);
          }
          trashFolder(
              segment,
              handler,
              message,
              message.body().getString(Field.FOLDER_ID.getName()),
              errors);
        }
      }
      if (synchronousExecution) {
        TypedCompositeFuture.join(queue).onComplete(event -> {
          if (event.succeeded()) {
            sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
          } else {
            if (Objects.nonNull(event.cause())) {
              sendError(
                  segment, message, event.cause().getLocalizedMessage(), HttpStatus.BAD_REQUEST);
            } else {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "SomethingWentWrongForFiles"),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          }
        });
      } else {
        sendOK(segment, message);
      }
      updateAndNotifyTrashedItems(segment, message, storageType, filesSet, foldersSet);
    }
  }

  private void doTrash(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = message.body().getString(Field.FILE_ID.getName());
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    if (fileId != null) {
      trashFile(segment, message, mills);
    } else if (folderId != null) {
      trashFolder(segment, message, mills);
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private void trashFile(Entity segment, Message<JsonObject> message, long mills) {
    message
        .body()
        .mergeIn(Utils.parseItemId(
            message.body().getString(Field.FILE_ID.getName()), Field.FILE_ID.getName()));
    String fileId = message.body().getString(Field.FILE_ID.getName());
    JsonArray errors = new JsonArray();
    Promise<Void> promise = Promise.promise();
    trashFile(segment, message, promise, fileId, errors, mills);
    promise
        .future()
        .onSuccess(event -> {
          sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
          // delete all version links for file
          VersionLinkRepository.getInstance().deleteAllFileLinks(fileId);
        })
        .onFailure(cause -> sendError(segment, message, cause.getLocalizedMessage(), 400));
  }

  private void trashFile(
      Entity parentSegment,
      Message<JsonObject> message,
      Promise<Void> promise,
      String fileId,
      JsonArray errors,
      long mills) {
    fileId = Utils.parseItemId(fileId, Field.FILE_ID.getName()).getString(Field.FILE_ID.getName());
    JsonObject jsonObject = message.body();
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        parentSegment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    String finalFileId = fileId;
    if (!jsonObject.containsKey(Field.FILE_ID.getName())) {
      jsonObject.put(Field.FILE_ID.getName(), fileId);
    }
    eb_request(
        parentSegment, StorageType.getAddress(storageType) + ".deleteFile", jsonObject, event -> {
          if (event.succeeded()) {
            JsonObject result = (JsonObject) event.result().body();
            if (OK.equals(result.getString(Field.STATUS.getName()))) {
              eb_send(
                  parentSegment,
                  WebSocketManager.address + ".deleted",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              storageType, finalFileId)));
              eb_send(
                  parentSegment,
                  WebSocketManager.address + ".objectDeleted",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.SESSION_ID.getName(), sessionId)
                      .put(Field.ITEM_ID.getName(), finalFileId)
                      .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                      .put(Field.IS_FOLDER.getName(), false));
              eb_send(
                  parentSegment,
                  RecentFilesVerticle.address + ".updateRecentFile",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), finalFileId)
                      .put(Field.ACCESSIBLE.getName(), false)
                      .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            } else {
              errors.add(new JsonObject().put(finalFileId, getDeleteErrorObject(result)));
            }
            promise.complete();
          } else {
            promise.fail(event.cause());
          }
          recordExecutionTime("trashFile", System.currentTimeMillis() - mills);
        });
  }

  private void doDeleteFile(
      Message<JsonObject> message, Promise<Void> promise, JsonArray errors, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String verId = jsonObject.getString(Field.VERSION_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (userId == null || fileId == null) {
      return;
    }
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".eraseFile",
        new JsonObject()
            .put(USER_ID, userId)
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.VER_ID.getName(), verId)
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(), message.body().getString(Field.EXTERNAL_ID.getName())),
        event -> {
          if (reply) {
            handleExternal(segment, event, message, mills);
          }
          if (Objects.nonNull(promise)) {
            handleObjectEvent(event, promise, errors, fileId);
          }
          recordExecutionTime("deleteFile", System.currentTimeMillis() - mills);
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("deleteFileLinks")
              .runWithoutSegment(() -> VersionLinkRepository.getInstance()
                  .deleteAllFileLinks(Utils.parseItemId(fileId).getString(Field.ID.getName())));
          // todo delete comments?
        });
  }

  private void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    String name = jsonObject.getString(Field.NAME.getName());
    if (userId == null) {
      return;
    }
    jsonObject.mergeIn(Utils.parseItemId(
        jsonObject.getString(Field.PARENT_ID.getName()), Field.PARENT_ID.getName()));
    String parentId = jsonObject.getString(Field.PARENT_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
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
    if (parentId == null) {
      parentId = Field.MINUS_1.getName();
    }
    String finalParentId = parentId;
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".createFolder",
        new JsonObject()
            .put(USER_ID, userId)
            .put(Field.PARENT_ID.getName(), parentId)
            .put(Field.NAME.getName(), name)
            .put(
                Field.DEVICE.getName(),
                jsonObject.getString(Field.DEVICE.getName(), AuthManager.ClientType.BROWSER.name()))
            .put(Field.LOCALE.getName(), jsonObject.getString(Field.LOCALE.getName()))
            .put(Field.EXTERNAL_ID.getName(), jsonObject.getString(Field.EXTERNAL_ID.getName())),
        event -> {
          handleExternal(segment, event, message, mills);

          if (event.succeeded() && isOk(event)) {
            eb_send(
                segment,
                WebSocketManager.address + ".objectCreated",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(
                        Field.SESSION_ID.getName(),
                        jsonObject.getString(Field.SESSION_ID.getName()))
                    .put(
                        Field.PARENT_ID.getName(),
                        Utils.getEncapsulatedId(
                            storageType,
                            jsonObject.getString(Field.EXTERNAL_ID.getName()),
                            finalParentId))
                    .put(
                        Field.ITEM_ID.getName(),
                        ((JsonObject) event.result().body()).getString(Field.FOLDER_ID.getName()))
                    .put(Field.ACTION.getName(), "createFolder")
                    .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                    .put(Field.IS_FOLDER.getName(), true));
          }

          recordExecutionTime("createFolder", System.currentTimeMillis() - mills);
        });
  }

  private void trashFolder(Entity segment, Message<JsonObject> message, long mills) {
    message
        .body()
        .mergeIn(Utils.parseItemId(
            message.body().getString(Field.FOLDER_ID.getName()), Field.FOLDER_ID.getName()));
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    JsonArray errors = new JsonArray();
    Promise<Void> promise = Promise.promise();
    trashFolder(segment, promise, message, folderId, errors);
    promise
        .future()
        .onSuccess(event ->
            sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills))
        .onFailure(cause -> sendError(segment, message, cause.getLocalizedMessage(), 400));
  }

  private void trashFolder(
      Entity segment,
      Promise<Void> promise,
      Message<JsonObject> message,
      String folderId,
      JsonArray errors) {
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = getRequiredString(segment, USER_ID, message, body);
    String sessionId = body.getString(Field.SESSION_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (folderId == null || userId == null) {
      return;
    }
    if (!body.containsKey(Field.FOLDER_ID.getName())) {
      body.put(Field.FOLDER_ID.getName(), folderId);
    }
    eb_request(segment, StorageType.getAddress(storageType) + ".deleteFolder", body, event -> {
      if (event.succeeded()) {
        JsonObject result = (JsonObject) event.result().body();
        if (OK.equals(result.getString(Field.STATUS.getName()))) {
          eb_send(
              segment,
              WebSocketManager.address + ".objectDeleted",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.SESSION_ID.getName(), sessionId)
                  .put(Field.ITEM_ID.getName(), folderId)
                  .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                  .put(Field.IS_FOLDER.getName(), true));
          if (!storageType.equals(StorageType.SAMPLES) && !storageType.equals(StorageType.GDRIVE)) {
            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .withName("validateRecentFilesAfterTrash")
                .runWithoutSegment(() -> validateRecentFilesAfterTrash(
                    segment, message, userId, storageType, 3000, false));
          }
        } else {
          if (Objects.nonNull(errors)) {
            errors.add(new JsonObject().put(folderId, getDeleteErrorObject(result)));
          }
        }
        if (Objects.nonNull(promise)) {
          promise.complete();
        }
      } else {
        if (Objects.nonNull(promise)) {
          promise.fail(event.cause());
        } else {
          log.warn(
              "Error occurred in deleting the folder with id " + folderId + " : " + event.cause());
        }
      }
      recordExecutionTime("trashFolder", System.currentTimeMillis() - mills);
    });
  }

  private JsonObject getDeleteErrorObject(JsonObject result) {
    if (result.containsKey(Field.MESSAGE.getName())
        && result.getValue(Field.MESSAGE.getName()) instanceof JsonObject) {
      String errorMessage =
          result.getJsonObject(Field.MESSAGE.getName()).getString(Field.MESSAGE.getName());
      result.remove(Field.MESSAGE.getName());
      result.remove(Field.STATUS.getName());
      result.put(Field.MESSAGE.getName(), errorMessage);
    }
    return result;
  }

  private void doGetOwners(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());

    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (folderId == null || userId == null) {
      return;
    }
    if (StorageType.INTERNAL.equals(storageType)) {
      eb_request(
          segment, StorageType.getAddress(storageType) + ".getOwners", message.body(), event -> {
            handleExternal(segment, event, message, mills);
            recordExecutionTime("getOwners", System.currentTimeMillis() - mills);
          });
    } else {
      sendOK(segment, message, mills);
    }
  }

  private void doDeleteFolder(
      Message<JsonObject> message, Promise<Void> promise, JsonArray errors, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String ownerId = getRequiredString(segment, Field.OWNER_ID.getName(), message, message.body());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    if (folderId == null || ownerId == null) {
      return;
    }
    message.body().put(USER_ID, ownerId);
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".eraseFolder",
        new JsonObject()
            .put(Field.FOLDER_ID.getName(), folderId)
            .put(USER_ID, ownerId)
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(), message.body().getString(Field.EXTERNAL_ID.getName())),
        event -> {
          if (reply) {
            handleExternal(segment, event, message, mills);
          }
          if (Objects.nonNull(promise)) {
            handleObjectEvent(event, promise, errors, folderId);
          }
          recordExecutionTime("deleteFolder", System.currentTimeMillis() - mills);
        });
  }

  private void doMoveFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, body);
    String folderId = getRequiredString(segment, Field.FOLDER_ID.getName(), message, body);
    String ownerId = getRequiredString(segment, Field.OWNER_ID.getName(), message, body);
    if (fileId == null || folderId == null || ownerId == null) {
      return;
    }
    fileId = message
        .body()
        .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
        .getString(Field.FILE_ID.getName());
    folderId = message
        .body()
        .mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()))
        .getString(Field.FOLDER_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    String finalFileId = fileId;
    String finalFolderId = folderId;
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".moveFile",
        new JsonObject()
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.PARENT_ID.getName(), folderId)
            .put(USER_ID, ownerId)
            .put(Field.LOCALE.getName(), body.getString(Field.LOCALE.getName()))
            .put(Field.EXTERNAL_ID.getName(), body.getString(Field.EXTERNAL_ID.getName())),
        event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("moveFile", System.currentTimeMillis() - mills);

          if (event.succeeded()
              && OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
            eb_send(
                segment,
                RecentFilesVerticle.address + ".updateRecentFile",
                new JsonObject()
                    .put(Field.FILE_ID.getName(), finalFileId)
                    .put(Field.PARENT_ID.getName(), finalFolderId)
                    .put(Field.STORAGE_TYPE.getName(), storageType.name()));
          }
        });
  }

  private void doMoveFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    String ownerId = getRequiredString(segment, Field.OWNER_ID.getName(), message, message.body());
    if (folderId == null || parentId == null || ownerId == null) {
      return;
    }
    folderId = message
        .body()
        .mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()))
        .getString(Field.FOLDER_ID.getName());
    parentId = message
        .body()
        .mergeIn(Utils.parseItemId(parentId, Field.PARENT_ID.getName()))
        .getString(Field.PARENT_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".moveFolder",
        new JsonObject()
            .put(USER_ID, ownerId)
            .put(Field.PARENT_ID.getName(), parentId)
            .put(Field.FOLDER_ID.getName(), folderId)
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(), message.body().getString(Field.EXTERNAL_ID.getName())),
        event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("moveFolder", System.currentTimeMillis() - mills);
        });
  }

  private void doRename(Message<JsonObject> message, boolean isFolder) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = getRequiredString(segment, Field.ID.getName(), message, jsonObject);
    String ownerId = getRequiredString(segment, Field.OWNER_ID.getName(), message, jsonObject);
    String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
    String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    if (id == null || ownerId == null || name == null) {
      return;
    }

    String onlyDotsInName = name;
    onlyDotsInName = onlyDotsInName.replaceAll("\\.", emptyString);
    if (onlyDotsInName.equals(emptyString) || onlyDotsInName.equals("dwg")) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "onlyDotsInName"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    id = message
        .body()
        .mergeIn(Utils.parseItemId(id, Field.ID.getName()))
        .getString(Field.ID.getName());
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    String finalId = id;
    eb_request(
        segment,
        StorageType.getAddress(storageType) + (isFolder ? ".renameFolder" : ".renameFile"),
        new JsonObject()
            .put((isFolder ? Field.FOLDER_ID.getName() : Field.FILE_ID.getName()), id)
            .put(Field.NAME.getName(), name)
            .put(USER_ID, ownerId)
            .put(Field.LOCALE.getName(), jsonObject.getString(Field.LOCALE.getName()))
            .put(Field.EXTERNAL_ID.getName(), jsonObject.getString(Field.EXTERNAL_ID.getName()))
            .put(
                "ignoreDeleted",
                jsonObject.containsKey("ignoreDeleted") && jsonObject.getBoolean("ignoreDeleted")),
        event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("renameObject", System.currentTimeMillis() - mills);
          if (event.succeeded()
              && OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
            eb_send(
                segment,
                WebSocketManager.address + ".objectRenamed",
                new JsonObject()
                    .put(Field.ITEM_ID.getName(), finalId)
                    .put(Field.USER_ID.getName(), ownerId)
                    .put(Field.SESSION_ID.getName(), sessionId)
                    .put(Field.IS_FOLDER.getName(), isFolder)
                    .put(Field.STORAGE_TYPE.getName(), storageType.toString()));
            if (!isFolder) {
              eb_send(
                  segment,
                  RecentFilesVerticle.address + ".updateRecentFile",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), finalId)
                      .put(Field.FILE_NAME.getName(), name)
                      .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            }
          }
        });
  }

  private void doTryShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (fileId == null || userId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.ID.getName()));
    if (StorageType.INTERNAL.equals(storageType)) {
      eb_request(
          segment, StorageType.getAddress(storageType) + ".tryShare", message.body(), event -> {
            handleExternal(segment, event, message, mills);
            recordExecutionTime("tryShare", System.currentTimeMillis() - mills);
          });
    } else {
      sendOK(segment, message, mills);
    }
  }

  private void doShare(Message<JsonObject> message, boolean isFolder) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(USER_ID);
    String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    String id = jsonObject.getString(Field.ID.getName());
    String username = jsonObject.getString(Field.USERNAME.getName());
    JsonObject share = jsonObject.getJsonObject(Field.SHARE.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    JsonArray editor = processShare(share.getJsonArray(Field.EDITOR.getName()), storageType);
    JsonArray viewer = processShare(share.getJsonArray(Field.VIEWER.getName()), storageType);
    Boolean content = share.getBoolean("content");
    Boolean isUpdate = jsonObject.getBoolean("isUpdate");
    if (content == null) {
      content = false;
    }

    if (isUpdate == null) {
      isUpdate = false;
    }

    id = message
        .body()
        .mergeIn(Utils.parseItemId(id, Field.ID.getName()))
        .getString(Field.ID.getName());
    String fileId = id;
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String role = emptyString;
    switch (storageType) {
      case GDRIVE:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? WRITER
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? READER : emptyString;
        break;
      case BOX:
      case DROPBOX:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? EDITOR
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? VIEWER : emptyString;
        break;
      case ONSHAPE:
      case ONSHAPEDEV:
      case ONSHAPESTAGING:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? "128"
            // READ, WRITE, DELETE, RESHARE, COMMENT, LINK, COPY
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? "8" : emptyString; // 8
        break;
      case TRIMBLE:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? FULL_ACCESS
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? READ : emptyString;
        break;
      case ONEDRIVE:
      case ONEDRIVEBUSINESS:
      case SHAREPOINT:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? GraphUtils.WRITE
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? GraphUtils.READ : emptyString;
        break;
      case NEXTCLOUD:
        role = (Objects.nonNull(editor) && !editor.isEmpty())
            ? "EDIT"
            : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? "VIEW" : emptyString;
        break;
      case HANCOM:
      case HANCOMSTG:
      case INTERNAL:
      case SAMPLES:
      default:
        break;
    }
    String email = (Objects.nonNull(editor) && !editor.isEmpty())
        ? editor.getString(0)
        : (Objects.nonNull(viewer) && !viewer.isEmpty()) ? viewer.getString(0) : emptyString;
    String finalId = id;
    String finalRole = role;
    Thread trackerThread = new Thread(() -> {
      try {
        SharingLog sharinglog = new SharingLog(
            userId,
            finalId,
            storageType,
            "no_data",
            GMTHelper.utcCurrentTime(),
            true,
            SharingTypes.EMAIL_SHARE,
            SharingActions.SHARED_BY_EMAIL,
            AuthManager.ClientType.getClientType(message.body().getString(Field.DEVICE.getName()))
                .name(),
            email,
            finalRole);
        sharinglog.sendToServer();
      } catch (Exception e) {
        log.error(e);
      }
    });
    trackerThread.start();
    JsonObject eventObject = new JsonObject()
        .put(USER_ID, userId)
        .put(Field.FOLDER_ID.getName(), id)
        .put(Field.ROLE.getName(), role)
        .put(Field.EMAIL.getName(), email)
        .put(Field.USERNAME.getName(), username)
        .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
        .put(Field.EXTERNAL_ID.getName(), message.body().getString(Field.EXTERNAL_ID.getName()))
        .put(Field.IS_FOLDER.getName(), isFolder)
        .put(Field.EDITOR.getName(), editor)
        .put(Field.VIEWER.getName(), viewer)
        .put("content", content)
        .put("isUpdate", isUpdate);
    if (storageType.equals(StorageType.SAMPLES)
        && share.containsKey(Field.CUSTOM_PERMISSIONS.getName())) {
      eventObject.put(
          Field.CUSTOM_PERMISSIONS.getName(),
          share.getJsonArray(Field.CUSTOM_PERMISSIONS.getName()));
    }
    eb_request(segment, StorageType.getAddress(storageType) + ".share", eventObject, event -> {
      handleExternal(segment, event, message, mills);
      recordExecutionTime("shareObject", System.currentTimeMillis() - mills);
      if (event.succeeded()
          && OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              JsonObject body = message.body();

              FileLog fileLog = new FileLog(
                  userId,
                  finalId,
                  storageType,
                  body.getString(Field.USERNAME.getName()),
                  GMTHelper.utcCurrentTime(),
                  true,
                  FileActions.SHARE,
                  GMTHelper.utcCurrentTime(),
                  null,
                  body.getString(Field.SESSION_ID.getName()),
                  null,
                  body.getString(Field.DEVICE.getName()),
                  null);
              fileLog.sendToServer();

              eb_request(
                  blockingSegment,
                  Subscriptions.address + ".getSubscription",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.FILE_ID.getName(), fileId),
                  eventSub -> {
                    try {
                      JsonObject respBody = ((JsonObject) eventSub.result().body());
                      boolean isFailed = eventSub.failed()
                          || !OK.equals(respBody.getString(Field.STATUS.getName()));
                      if (!isFailed && respBody.getJsonObject("subscription").isEmpty()) {
                        // no subscriptions found - let's create
                        eb.send(
                            Subscriptions.address + ".addSubscription",
                            new JsonObject()
                                .put(Field.FILE_ID.getName(), fileId)
                                .put(Field.USER_ID.getName(), userId)
                                .put(Field.STORAGE_TYPE.getName(), storageType)
                                .put(
                                    "scope",
                                    new JsonArray()
                                        .add(Subscriptions.subscriptionScope.GLOBAL.toString()))
                                .put("scopeUpdate", Subscriptions.scopeUpdate.APPEND.toString()));
                      }
                    } catch (Exception ignored) {
                    }
                  });

              JsonArray newCollaborators = new JsonArray();
              if (Objects.nonNull(editor) && !editor.isEmpty()) {
                newCollaborators.addAll(editor);
              }
              if (Objects.nonNull(viewer) && !viewer.isEmpty()) {
                newCollaborators.addAll(viewer);
              }
              JsonArray collaborators = new JsonArray();
              newCollaborators.forEach(userEmail -> {
                Iterator<Item> userIterator = Users.getUserByEmail((String) userEmail);
                if (userIterator.hasNext()) {
                  Item user = userIterator.next();
                  collaborators.add(user.getString(Dataplane.sk));
                }
              });

              if (!collaborators.isEmpty()
                  || (Utils.isStringNotNullOrEmpty(username)
                      && ((Objects.nonNull(editor) && editor.contains(username))
                          || (Objects.nonNull(viewer) && viewer.contains(username))))) {
                eb.send(
                    WebSocketManager.address + ".accessGranted",
                    new JsonObject()
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.SESSION_ID.getName(), sessionId)
                        .put(Field.ITEM_ID.getName(), finalId)
                        .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                        .put(
                            Field.ROLE.getName(),
                            (Objects.nonNull(editor) && !editor.isEmpty())
                                ? "edit"
                                : (Objects.nonNull(viewer) && !viewer.isEmpty())
                                    ? "view"
                                    : emptyString)
                        .put(Field.IS_FOLDER.getName(), false)
                        .put(Field.COLLABORATORS.getName(), collaborators));
              }

              List<JsonObject> collaboratorsWithRoleList = editor.stream()
                  .map((Object collaboratorEmail) -> new JsonObject()
                      .put(Field.ROLE.getName(), "edit")
                      .put(Field.EMAIL.getName(), collaboratorEmail))
                  .collect(Collectors.toList());

              collaboratorsWithRoleList.addAll(viewer.stream()
                  .map((Object collaboratorEmail) -> new JsonObject()
                      .put(Field.ROLE.getName(), "view")
                      .put(Field.EMAIL.getName(), collaboratorEmail))
                  .collect(Collectors.toList()));

              if (!collaboratorsWithRoleList.isEmpty()) {
                // notify users, who was shared with file opened
                eb_send(
                    blockingSegment,
                    WebSocketManager.address + ".shared",
                    new JsonObject()
                        .put(
                            Field.FILE_ID.getName(),
                            StorageType.processFileIdForWebsocketNotification(storageType, finalId))
                        .put(
                            Field.COLLABORATORS.getName(), new JsonArray(collaboratorsWithRoleList))
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.USERNAME.getName(), username));
              }

              // update rating for mentions
              Iterator<Item> users = Users.getUserByEmail(email);
              if (users.hasNext()) {
                Mentions.updateRating(
                    Mentions.Activity.FILE_SHARED,
                    users.next().getString(Field.SK.getName()),
                    fileId,
                    storageType);
              }
            });
      }
    });
  }

  private void doDeShare(Message<JsonObject> message, boolean isFolder) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(USER_ID);
    String sessionId = body.getString(Field.SESSION_ID.getName());
    String id = body.getString(Field.ID.getName());
    String fullId = id;
    JsonArray deShare = body.getJsonArray("deshare");
    id = body.mergeIn(Utils.parseItemId(id, Field.ID.getName())).getString(Field.ID.getName());
    StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String finalId = id;
    Thread trackerThread = new Thread(() -> {
      try {
        SharingLog sharinglog = new SharingLog(
            userId,
            finalId,
            storageType,
            "no_data",
            GMTHelper.utcCurrentTime(),
            true,
            SharingTypes.EMAIL_SHARE,
            SharingActions.UNSHARED,
            AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName()))
                .name(),
            deShare.getJsonObject(0).getString(Field.USER_ID.getName()),
            null);
        sharinglog.sendToServer();
      } catch (Exception e) {
        log.error(e);
      }
    });

    boolean tryDelete = deShare.getJsonObject(0).containsKey("tryDelete");

    if (tryDelete) {
      if (storageType.equals(StorageType.GDRIVE)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "GD2"),
            HttpStatus.FORBIDDEN,
            "GD2");
        return;
      }
      message.body().put(isFolder ? Field.FOLDER_ID.getName() : Field.FILE_ID.getName(), fullId);
      if (isFolder) {
        trashFolder(segment, message, mills);
      } else {
        trashFile(segment, message, mills);
      }
    } else {
      trackerThread.start();
      eb_request(
          segment,
          StorageType.getAddress(storageType) + ".deShare",
          new JsonObject()
              .put(USER_ID, userId)
              .put(Field.FOLDER_ID.getName(), id)
              .put(
                  Field.NAME.getName(), deShare.getJsonObject(0).getString(Field.USER_ID.getName()))
              .put(Field.LOCALE.getName(), body.getString(Field.LOCALE.getName()))
              .put(Field.DE_SHARE.getName(), deShare)
              .put(Field.EXTERNAL_ID.getName(), body.getString(Field.EXTERNAL_ID.getName()))
              .put(Field.IS_FOLDER.getName(), isFolder),
          event -> {
            handleExternal(segment, event, message, mills);
            recordExecutionTime("deShareObject", System.currentTimeMillis() - mills);
            JsonObject eventJson = (JsonObject) event.result().body();
            if (event.succeeded() && OK.equals(eventJson.getString(Field.STATUS.getName()))) {
              new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                  .run((Segment blockingSegment) -> {
                    FileLog fileLog = new FileLog(
                        userId,
                        finalId,
                        storageType,
                        body.getString(Field.USERNAME.getName()),
                        GMTHelper.utcCurrentTime(),
                        true,
                        FileActions.DESHARE,
                        GMTHelper.utcCurrentTime(),
                        null,
                        body.getString(Field.SESSION_ID.getName()),
                        null,
                        body.getString(Field.DEVICE.getName()),
                        null);
                    fileLog.sendToServer();

                    // people who lost access
                    JsonArray collaborators = new JsonArray();
                    if (eventJson.containsKey(Field.COLLABORATORS.getName())) {
                      collaborators.addAll(eventJson.getJsonArray(Field.COLLABORATORS.getName()));
                    }
                    if (collaborators.isEmpty()) {
                      deShare.forEach(de -> collaborators.add(((JsonObject) de)
                          .getString(
                              ((JsonObject) de).containsKey(Field.EMAIL.getName())
                                  ? Field.EMAIL.getName()
                                  : Field.USER_ID.getName())));
                    }
                    // notify users on files page
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".accessRemoved",
                        new JsonObject()
                            .put(Field.USER_ID.getName(), userId)
                            .put(Field.SESSION_ID.getName(), sessionId)
                            .put(Field.ITEM_ID.getName(), finalId)
                            .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                            .put(Field.IS_FOLDER.getName(), false)
                            .put(Field.COLLABORATORS.getName(), collaborators));

                    // notify users, who was deshared with file opened
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".deshared",
                        new JsonObject()
                            .put(
                                Field.FILE_ID.getName(),
                                StorageType.processFileIdForWebsocketNotification(
                                    storageType, finalId))
                            .put(Field.COLLABORATORS.getName(), collaborators)
                            .put(Field.USER_ID.getName(), userId)
                            .put(
                                Field.USERNAME.getName(),
                                eventJson.getString(Field.USERNAME.getName())));

                    if (!isFolder
                        && eventJson.containsKey(Field.COLLABORATORS.getName())
                        && !eventJson
                            .getJsonArray(Field.COLLABORATORS.getName())
                            .isEmpty()) {
                      eventJson
                          .getJsonArray(Field.COLLABORATORS.getName())
                          .forEach(o -> eb_send(
                              blockingSegment,
                              RecentFilesVerticle.address + ".updateRecentFile",
                              new JsonObject()
                                  .put(Field.USER_ID.getName(), o)
                                  .put(Field.FILE_ID.getName(), finalId)
                                  .put(Field.ACCESSIBLE.getName(), false)
                                  .put(Field.STORAGE_TYPE.getName(), storageType.name())));
                    }
                    // https://graebert.atlassian.net/browse/XENON-52369
                    // DK: for all of files - let's just clear access cache on desharing.
                    if (!isFolder) {
                      // disable possible public links
                      PublicLink.removeUnsharedFileLinks(finalId, collaborators)
                          .forEach(removedLink -> eb_send(
                              blockingSegment,
                              WebSocketManager.address + ".sharedLinkOff",
                              new JsonObject()
                                  .put(
                                      Field.FILE_ID.getName(),
                                      StorageType.processFileIdForWebsocketNotification(
                                          storageType, finalId))
                                  .put(
                                      Field.LINK_OWNER_IDENTITY.getName(),
                                      removedLink.getString(Field.LINK_OWNER_IDENTITY.getName()))));
                      AccessCache.clearAccessStatus(finalId);
                    }
                  });
            } else {
              sendError(
                  segment,
                  message,
                  eventJson.getString(Field.MESSAGE.getName()),
                  HttpStatus.BAD_REQUEST);
            }
          });
    }
  }

  private void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String ownerId = message.body().getString(USER_ID);
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    String query = message.body().getString(Field.QUERY.getName());
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".globalSearch",
        new JsonObject()
            .put(USER_ID, ownerId)
            .put(Field.QUERY.getName(), query)
            .put(Field.IS_ADMIN.getName(), message.body().getBoolean(Field.IS_ADMIN.getName()))
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName())),
        event -> handleExternal(segment, event, message, 0));
  }

  private void doGetFolderContent(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    Boolean trash = jsonObject.getBoolean(Field.TRASH.getName());
    String ownerId = jsonObject.getString(Field.OWNER_ID.getName());
    jsonObject.mergeIn(Utils.parseItemId(
        jsonObject.getString(Field.FOLDER_ID.getName()), Field.FOLDER_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, ownerId)) {
      return;
    }
    final String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());

    //        String cache = TemporaryData.getFolderCache(jsonObject.getString(Field.OWNER_ID
    //        .getName()), Utils
    //        .getEncapsulatedId(jsonObject));
    //        if (cache != null){
    //            XrayManager.newSegmentContextExecutor(segment);
    //            sendOK(segment, message, new JsonObject(cache), mills);
    //        }
    //        else {
    if (storageType.equals(StorageType.INTERNAL)
        && !isAddressAvailable(StorageType.getAddress(storageType) + ".getFolderContent")) {
      sendError(
          segment,
          message,
          "User doesn't have proper storageType set.",
          HttpStatus.INTERNAL_SERVER_ERROR,
          LogLevel.WARN);
    } else {
      eb_request(
          segment,
          StorageType.getAddress(storageType) + (trash ? ".getTrash" : ".getFolderContent"),
          new JsonObject()
              .put(USER_ID, ownerId)
              .put(Field.FOLDER_ID.getName(), jsonObject.getString(Field.FOLDER_ID.getName()))
              .put(Field.IS_ADMIN.getName(), jsonObject.getBoolean(Field.IS_ADMIN.getName()))
              .put(Field.LOCALE.getName(), jsonObject.getString(Field.LOCALE.getName()))
              .put(Field.EXTERNAL_ID.getName(), externalId)
              .put(Field.PAGE_TOKEN.getName(), jsonObject.getString(Field.PAGE_TOKEN.getName()))
              .put(Field.FULL.getName(), jsonObject.getBoolean(Field.FULL.getName()))
              .put(Field.SESSION_ID.getName(), jsonObject.getString(Field.SESSION_ID.getName()))
              .put(Field.FILE_FILTER.getName(), jsonObject.getString(Field.FILE_FILTER.getName()))
              .put(
                  Field.NO_DEBUG_LOG.getName(), jsonObject.getBoolean(Field.NO_DEBUG_LOG.getName()))
              .put(Field.PAGINATION.getName(), jsonObject.getString(Field.PAGINATION.getName()))
              .put(Field.TRASH.getName(), jsonObject.getBoolean("dbTrash"))
              .put(Field.DEVICE.getName(), jsonObject.getString(Field.DEVICE.getName()))
              .put("isUserThumbnailDisabled", jsonObject.getBoolean("isUserThumbnailDisabled"))
              .put(
                  Field.PREFERENCES.getName(),
                  jsonObject.getJsonObject(Field.PREFERENCES.getName()))
              .put(
                  Field.USE_NEW_STRUCTURE.getName(),
                  jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName())),
          event -> {
            if (event.succeeded() && isOk(event)) {
              JsonObject result = MessageUtils.parse(event.result()).getJsonObject();

              result
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(Field.EXTERNAL_ID.getName(), externalId);
              XRayManager.newSegmentContextExecutor(segment);
              sendOK(segment, message, result, mills);
              new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                  .withName("putMetadataOnGetFolderContent")
                  .runWithoutSegment(() -> {
                    JsonArray files = result
                        .getJsonObject(Field.RESULTS.getName())
                        .getJsonArray(Field.FILES.getName());
                    files.forEach(file -> {
                      JsonObject f = (JsonObject) file;
                      String fileId = f.getString(Field.ENCAPSULATED_ID.getName());
                      JsonObject id = Utils.parseItemId(fileId, Field.ENCAPSULATED_ID.getName());
                      log.info("[ METADATA ] Update on doGetFolderContent");
                      FileMetaData.putMetaData(
                          id.getString(Field.ENCAPSULATED_ID.getName()),
                          storageType,
                          f.getString(Field.FILE_NAME.getName()),
                          f.getString(Field.THUMBNAIL_NAME.getName()),
                          f.getString(Field.VERSION_ID.getName()),
                          f.getString(Field.OWNER_ID.getName()));
                    });
                  });
            } else {
              handleExternal(segment, event, message, mills);
            }
          });
    }
    //        }
  }

  private void doSearch(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  private void doRequestFolderZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String filter = message.body().getString(Field.FILTER.getName());
    boolean recursive = message.body().getBoolean(Field.RECURSIVE.getName());
    if (folderId == null || userId == null) {
      return;
    }
    folderId = message
        .body()
        .mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()))
        .getString(Field.FOLDER_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String requestId = ZipRequests.createZipRequest(userId, folderId, storageType);
    sendOK(
        segment, message, new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId), mills);
    eb_send(
        segment,
        StorageType.getAddress(storageType) + ".requestFolderZip",
        new JsonObject()
            .put(Field.REQUEST_ID.getName(), requestId)
            .put(Field.FOLDER_ID.getName(), folderId)
            .put(USER_ID, userId)
            .put(Field.FILTER.getName(), filter)
            .put(Field.RECURSIVE.getName(), recursive)
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(),
                message.body().getString(Field.EXTERNAL_ID.getName())));
  }

  private void doRequestMultipleDownloadZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String parentFolderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String filter = message.body().getString(Field.FILTER.getName());
    JsonArray downloads = message.body().getJsonArray(Field.DOWNLOADS.getName());
    boolean recursive = message.body().getBoolean(Field.RECURSIVE.getName());
    if (parentFolderId == null || userId == null) {
      return;
    }
    parentFolderId = message
        .body()
        .mergeIn(Utils.parseItemId(parentFolderId, Field.FOLDER_ID.getName()))
        .getString(Field.FOLDER_ID.getName());
    if (downloads == null || downloads.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "NothingToDownload"),
          HttpStatus.PRECONDITION_FAILED);
      return;
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String requestId = ZipRequests.createZipRequest(userId, parentFolderId, storageType);
    sendOK(
        segment, message, new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId), mills);
    eb_send(
        segment,
        StorageType.getAddress(storageType) + ".requestMultipleObjectsZip",
        new JsonObject()
            .put(Field.REQUEST_ID.getName(), requestId)
            .put(Field.PARENT_FOLDER_ID.getName(), parentFolderId)
            .put(USER_ID, userId)
            .put(Field.DOWNLOADS.getName(), downloads)
            .put(Field.FILTER.getName(), filter)
            .put(Field.RECURSIVE.getName(), recursive)
            .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
            .put(
                Field.EXTERNAL_ID.getName(),
                message.body().getString(Field.EXTERNAL_ID.getName())));
  }

  private void doGetFolderZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String folderId = Utils.parseItemId(
            getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body()),
            Field.FOLDER_ID.getName())
        .getString(Field.FOLDER_ID.getName());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String requestId = getRequiredString(segment, Field.TOKEN.getName(), message, message.body());
    int partToDownload = message.body().getInteger("partToDownload", 0);
    log.info("ZIP folderId: " + folderId + " userId: " + userId + " requestId: " + requestId);
    if (folderId == null || userId == null || requestId == null) {
      return;
    }
    try {
      Item request = ZipRequests.getZipRequest(userId, folderId, requestId);
      log.info("ZIP request: " + (request != null ? request.toString() : "NULL"));
      if (Objects.isNull(request)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UnknownRequestToken"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      @NonNls String status = request.getString(Field.STATUS.getName());
      log.info("ZIP request status: " + status);
      if (JobStatus.ERROR.name().equals(status)) {
        sendError(segment, message, request.getString("details"), HttpStatus.INTERNAL_SERVER_ERROR);
        removeZipFromS3(segment, message, request, requestId);
        return;
      }
      if (partToDownload == 0) {
        String mainZipStatus = request.getString("mainZipStatus");
        if (JobStatus.SUCCESS.name().equals(mainZipStatus)) {
          request.withString(
              Field.REQUEST_ID.getName(), requestId + S3Regional.pathSeparator + "main");
          JsonObject result = new JsonObject().put("finalDownload", true);
          if (returnDownloadUrl) {
            String downloadUrl = ZipRequests.getDownloadUrlForZipEntries(request);
            result.put(Field.DOWNLOAD_URL.getName(), downloadUrl);
          } else {
            byte[] data = ZipRequests.getZipFromS3(request);
            result.put(Field.DATA.getName(), data);
          }
          for (ExcludeReason excludeReason : ExcludeReason.values()) {
            String key = excludeReason.getKey();
            if (request.hasAttribute(key)) {
              result.put(key, request.getList(key));
            }
          }
          sendOK(segment, message, HttpStatus.OK, result, System.currentTimeMillis() - millis);
          removeZipFromS3(segment, message, request, requestId);
        } else if (JobStatus.ERROR.name().equals(mainZipStatus)) {
          sendError(
              segment,
              message,
              request.getString("mainZipDetails"),
              HttpStatus.INTERNAL_SERVER_ERROR);
          removeZipFromS3(segment, message, request, requestId);
        } else {
          sendOK(segment, message, HttpStatus.ACCEPTED, System.currentTimeMillis() - millis);
        }
        return;
      }
      Item downloadRequest = ZipRequests.getDownloadZipRequest(requestId);
      if (Objects.nonNull(downloadRequest)
          && JobStatus.SUCCESS.name().equals(status)
          && request.hasAttribute(Field.UPLOADED_PART.getName())
          && downloadRequest.hasAttribute(Field.DOWNLOADED_PART.getName())
          && request.getInt(Field.UPLOADED_PART.getName())
              == downloadRequest.getInt(Field.DOWNLOADED_PART.getName())) {
        JsonObject result = new JsonObject();
        for (ExcludeReason excludeReason : ExcludeReason.values()) {
          String key = excludeReason.getKey();
          if (request.hasAttribute(key)) {
            result.put(key, request.getList(key));
          }
        }
        sendOK(segment, message, HttpStatus.OK, result, System.currentTimeMillis() - millis);
        return;
      }
      boolean isDownloadLeft = Objects.isNull(downloadRequest)
          || !request.hasAttribute(Field.UPLOADED_PART.getName())
          || !downloadRequest.hasAttribute(Field.DOWNLOADED_PART.getName())
          || request.getInt(Field.UPLOADED_PART.getName())
              > downloadRequest.getInt(Field.DOWNLOADED_PART.getName());
      if (JobStatus.IN_PROGRESS.name().equals(status) || isDownloadLeft) {
        JsonObject result = new JsonObject();
        if (request.hasAttribute(Field.UPLOADED_PART.getName())
            && request.getInt(Field.UPLOADED_PART.getName()) > 0
            && isDownloadLeft) {
          if (partToDownload <= request.getInt(Field.UPLOADED_PART.getName())) {
            request.withString(
                Field.REQUEST_ID.getName(), requestId + S3Regional.pathSeparator + partToDownload);
            if (returnDownloadUrl) {
              String downloadUrl = ZipRequests.getDownloadUrlForZipEntries(request);
              result.put(Field.DOWNLOAD_URL.getName(), downloadUrl);
            } else {
              byte[] data = ZipRequests.getZipFromS3(request);
              if (Objects.isNull(data)) {
                try {
                  throw new KudoFileException(
                      Utils.getLocalizedString(message, "DownloadDataNotFound"),
                      KudoErrorCodes.FILE_DOWNLOAD_ERROR,
                      HttpStatus.BAD_REQUEST,
                      "DownloadDataNotFound");
                } catch (KudoFileException kfe) {
                  sendError(
                      segment,
                      message,
                      kfe.toResponseObject(),
                      kfe.getHttpStatus(),
                      kfe.getErrorId());
                  return;
                }
              } else {
                result.put(Field.DATA.getName(), data);
              }
            }
            ZipRequests.putDownloadedPart(downloadRequest, requestId, partToDownload);
            result.put(Field.DOWNLOADED_PART.getName(), partToDownload);
            isDownloadLeft = (request.getInt(Field.UPLOADED_PART.getName()) > partToDownload);
          }
        }
        // check if download is left but the uploaded is done
        boolean downloadCompleted = false;
        if ((JobStatus.SUCCESS.name().equals(status) && !isDownloadLeft)) {
          downloadCompleted = true;
          for (ExcludeReason excludeReason : ExcludeReason.values()) {
            String key = excludeReason.getKey();
            if (request.hasAttribute(key)) {
              result.put(key, request.getList(key));
            }
          }
          removeZipFromS3(segment, message, request, requestId);
        }
        sendOK(
            segment,
            message,
            downloadCompleted ? HttpStatus.OK : HttpStatus.ACCEPTED,
            result,
            System.currentTimeMillis() - millis);
        return;
      }
      sendOK(segment, message);
      removeZipFromS3(segment, message, request, requestId);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
    XRayManager.endSegment(segment);
  }

  private void removeZipFromS3(
      Entity segment, Message<JsonObject> message, Item request, String requestId) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("removeZipFromS3")
        .runWithoutSegment(() -> {
          request.withString(Field.REQUEST_ID.getName(), requestId);
          ZipRequests.removeZipFromS3(request);
        });
  }

  private void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, jsonObject);
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    if (id == null || userId == null) {
      return;
    }
    jsonObject.mergeIn(Utils.parseItemId(id, Field.FOLDER_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    //        String cache = TemporaryData.getFolderPathCache(jsonObject.getString(Field.USER_ID
    //        .getName()),
    //        Utils.getEncapsulatedId(jsonObject));
    //        if (cache != null){
    //            XrayManager.newSegmentContextExecutor(segment);
    //            sendOK(segment, message, new JsonObject(cache), mills);
    //        }
    //        else {
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".getFolderPath",
        new JsonObject()
            .put(Field.ID.getName(), jsonObject.getString(Field.FOLDER_ID.getName()))
            .put(Field.LOCALE.getName(), jsonObject.getString(Field.LOCALE.getName()))
            .put(Field.DEVICE.getName(), jsonObject.getString(Field.DEVICE.getName()))
            .put(USER_ID, userId)
            .put(Field.EXTERNAL_ID.getName(), jsonObject.getString(Field.EXTERNAL_ID.getName())),
        event -> {
          handleExternal(segment, event, message, mills);
          /*
          if (event.succeeded()) {
              Entity parent = XRayManager.createDummySegment();
              TemporaryData.putFolderPathCache(jsonObject.getString(Field.USER_ID
              .getName()), Utils
              .getEncapsulatedId(jsonObject), ((JsonObject) event.result().body())
              .encode());
              XRayManager.endSegment(parent);
          }
           */
        });
    //        }
  }

  private void getObjectInfoByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject messageBody = message.body();
    String fileId = messageBody.getString(Field.FILE_ID.getName());
    String versionId = messageBody.getString(Field.VERSION_ID.getName());
    String token = messageBody.getString(Field.TOKEN.getName());
    String password = messageBody.getString(Field.PASSWORD.getName());
    String xSessionId = messageBody.getString(Field.X_SESSION_ID.getName());

    if (Utils.isStringNotNullOrEmpty(versionId)) {
      try {
        VersionLink versionLink = VersionLinkRepository.getInstance()
            .getByTokenAndVersionId(
                Utils.parseItemId(fileId, Field.ID.getName()).getString(Field.ID.getName()),
                versionId,
                token);

        LinkService.isLinkValid(versionLink, password);

        eb_request(
            segment,
            StorageType.getAddress(versionLink.getStorageType()) + ".getInfo",
            new JsonObject()
                .put(Field.FILE_ID.getName(), versionLink.getObjectId())
                .put(Field.USER_ID.getName(), versionLink.getUserId())
                .put(Field.IS_ADMIN.getName(), false)
                .put(
                    Field.EXTERNAL_ID.getName(),
                    messageBody.getString(Field.EXTERNAL_ID.getName())),
            event -> {
              try {
                if (event.succeeded()) {
                  JsonObject result = (JsonObject) event.result().body();
                  if (OK.equals(result.getString(Field.STATUS.getName()))) {
                    sendOK(
                        segment,
                        message,
                        result.put(
                            Field.LINK_OWNER_IDENTITY.getName(), versionLink.getOwnerIdentity()),
                        mills);
                    JsonObject id = Utils.parseItemId(fileId, Field.FILE_ID.getName());
                    log.info("[ METADATA ] Update on doGetObjectInfoByToken");
                    String fileName =
                        result.getString(Field.FILE_NAME.getName(), versionLink.getObjectName());
                    FileMetaData.putMetaData(
                        id.getString(Field.FILE_ID.getName()),
                        versionLink.getStorageType(),
                        fileName,
                        result.getString(Field.THUMBNAIL_NAME.getName()),
                        result.getString(Field.VERSION_ID.getName()),
                        versionLink.getUserId());
                  } else {
                    throw new KudoFileException(
                        KudoFileException.getErrorMessage(message, result),
                        KudoErrorCodes.FILE_GET_INFO_ERROR,
                        HttpStatus.BAD_REQUEST);
                  }
                } else {
                  throw new KudoFileException(
                      event.cause().getLocalizedMessage(),
                      KudoErrorCodes.FILE_GET_INFO_ERROR,
                      HttpStatus.BAD_REQUEST);
                }
              } catch (KudoFileException e) {
                sendError(
                    segment,
                    message,
                    Utils.getLocalizedString(message, "NoPublicAccessToThisFile"),
                    HttpStatus.FORBIDDEN,
                    "PL1");
              }
            });

      } catch (Exception exception) {
        sendError(segment, message, exception.getMessage(), HttpStatus.BAD_REQUEST, exception);
      }
    } else {
      PublicLink publicLink = new PublicLink(fileId);
      try {
        if ((!Utils.isStringNotNullOrEmpty(xSessionId)
                || !publicLink.validateXSession(token, xSessionId))
            && !publicLink.isValid(token, password)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "NoPublicAccessToThisFile"),
              HttpStatus.FORBIDDEN,
              "PL1");
          return;
        }
        StorageType storageType = StorageType.getStorageType(publicLink.getStorageType());
        if (isStorageDisabled(
            segment, message, storageType, messageBody.getString(Field.USER_ID.getName()))) {
          return;
        }
        boolean export = publicLink.getExport();
        String userId = publicLink.getLastUser();
        if (export) {
          boolean isEnterprise = config.getProperties().getEnterprise();
          Item user = Users.getUserById(userId);
          // check if export is enabled for the company
          if ((user != null && user.hasAttribute(Field.ORGANIZATION_ID.getName()))
              || isEnterprise) {
            String orgId = isEnterprise
                ? Users.ENTERPRISE_ORG_ID
                : user.getString(Field.ORGANIZATION_ID.getName());
            Item company = Users.getOrganizationById(orgId);
            if (company != null) {
              JsonObject companyOptions =
                  config.getProperties().getDefaultCompanyOptionsAsJson().copy();
              JsonObject options = company.hasAttribute(Field.OPTIONS.getName())
                  ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
                  : new JsonObject();
              companyOptions.mergeIn(options, true);
              if (companyOptions.containsKey(Field.EXPORT.getName())) {
                export = companyOptions.getBoolean(Field.EXPORT.getName());
              }
            }
          }
        }

        JsonObject payload = new JsonObject()
            .put(Field.TOKEN.getName(), publicLink.getToken())
            .put(Field.LOCALE.getName(), messageBody.getString(Field.LOCALE.getName()))
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.STORAGE_TYPE.getName(), storageType.toString())
            .put(Field.PATH.getName(), publicLink.getPath())
            .put(Field.EXTERNAL_ID.getName(), publicLink.getExternalId())
            .put(Field.OWNER_ID.getName(), publicLink.getUserId())
            .put(Field.DRIVE_ID.getName(), publicLink.getDriveId())
            .put(Field.LOCATION.getName(), publicLink.getLocation())
            .put(Field.EXPORT.getName(), export)
            .put(Field.FILE_NAME.getName(), publicLink.getFilename())
            .put(Field.CREATOR_ID.getName(), userId);

        eb_request(
            segment, StorageType.getAddress(storageType) + ".doGetInfoByToken", payload, event -> {
              recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
              try {
                if (event.succeeded()) {
                  JsonObject result = (JsonObject) event.result().body();
                  if (OK.equals(result.getString(Field.STATUS.getName()))) {
                    try {
                      sendOK(
                          segment,
                          message,
                          result.put(
                              Field.LINK_OWNER_IDENTITY.getName(),
                              publicLink.getLinkOwnerIdentity()),
                          mills);
                    } catch (PublicLinkException ple) {
                      sendError(
                          segment,
                          message,
                          Utils.getLocalizedString(message, "NoPublicAccessToThisFile"),
                          HttpStatus.FORBIDDEN,
                          "PL1");
                    }
                    JsonObject id = Utils.parseItemId(fileId, Field.FILE_ID.getName());
                    log.info("[ METADATA ] Update on doGetObjectInfoByToken");
                    String fileName = result.getString(Field.FILE_NAME.getName()) == null
                        ? payload.getString(Field.FILE_NAME.getName())
                        : result.getString(Field.FILE_NAME.getName());
                    FileMetaData.putMetaData(
                        id.getString(Field.FILE_ID.getName()),
                        storageType,
                        fileName,
                        result.getString(Field.THUMBNAIL_NAME.getName()),
                        result.getString(Field.VERSION_ID.getName()),
                        payload.getString(Field.OWNER_ID.getName()));
                  } else {
                    throw new KudoFileException(
                        KudoFileException.getErrorMessage(message, result),
                        KudoErrorCodes.FILE_GET_INFO_ERROR,
                        HttpStatus.BAD_REQUEST);
                  }
                } else {
                  throw new KudoFileException(
                      event.cause().getLocalizedMessage(),
                      KudoErrorCodes.FILE_GET_INFO_ERROR,
                      HttpStatus.BAD_REQUEST);
                }
              } catch (KudoFileException kfe) {
                sendError(
                    segment,
                    message,
                    Utils.getLocalizedString(message, kfe.getMessage()),
                    HttpStatus.FORBIDDEN,
                    "PL1");
              }
            });
      } catch (PublicLinkException ple) {
        sendError(segment, message, ple.toResponseObject(), HttpStatus.FORBIDDEN, "PL1");
      }
    }
  }

  private void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    message.body().mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (userId == null) {
      return;
    }
    eb_request(
        segment, StorageType.getAddress(storageType) + ".getThumbnail", message.body(), event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("getThumbnail", System.currentTimeMillis() - mills);
        });
  }

  private void doGetObjectInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    try {
      if (folderId == null && fileId == null) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "IdMustBeSpecified"),
            KudoErrorCodes.FILE_GET_INFO_ERROR,
            HttpStatus.BAD_REQUEST,
            "IdMustBeSpecified");
      }
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
      return;
    }
    if (folderId != null) {
      jsonObject.mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()));
    }
    if (fileId != null) {
      jsonObject.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    }
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, jsonObject.getString(Field.USER_ID.getName()))) {
      return;
    }
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    if (userId == null) {
      return;
    }
    fileId = jsonObject.getString(Field.FILE_ID.getName());
    boolean isFile = Utils.isStringNotNullOrEmpty(fileId);
    String finalFileId = fileId;
    //    String cache = TemporaryData.getItemInfoCache(jsonObject.getString(Field.USER_ID
    //    .getName()), Utils
    //    .getEncapsulatedId(jsonObject));
    //    if (cache != null){
    //        XrayManager.newSegmentContextExecutor(segment);
    //        sendOK(segment, message, new JsonObject(cache), mills);
    //        recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
    //    }
    //    else {
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".getInfo",
        jsonObject.put(
            Field.USE_NEW_STRUCTURE.getName(),
            jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName())),
        event -> {
          recordExecutionTime("getObjectInfo", System.currentTimeMillis() - mills);
          Entity standaloneSegment =
              XRayManager.createStandaloneSegment(operationGroup, segment, "getObjectInfo.result");
          try {
            if (event.succeeded()) {
              JsonObject result = (JsonObject) event.result().body();
              if (!OK.equals(result.getString(Field.STATUS.getName()))) {
                if (isFile) {
                  eb_send(
                      standaloneSegment,
                      RecentFilesVerticle.address + ".updateRecentFile",
                      new JsonObject()
                          .put(Field.USER_ID.getName(), userId)
                          .put(Field.FILE_ID.getName(), finalFileId)
                          .put(Field.ACCESSIBLE.getName(), false)
                          .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                }
                int responseStatusCode = HttpStatus.BAD_REQUEST;
                try {
                  if (result.containsKey(Field.STATUS_CODE.getName())) {
                    responseStatusCode = result.getInteger(Field.STATUS_CODE.getName());
                  }
                } catch (Exception ex) {
                  // this is cast exception ignore
                }
                throw new KudoFileException(
                    KudoFileException.getErrorMessage(message, result),
                    KudoErrorCodes.FILE_GET_INFO_ERROR,
                    responseStatusCode);
              } else {
                if (isFile) {
                  JsonObject id = Utils.parseItemId(finalFileId, Field.FILE_ID.getName());
                  log.info("[ METADATA ] Update on doGetObjectInfo");
                  FileMetaData.putMetaData(
                      id.getString(Field.FILE_ID.getName()),
                      storageType,
                      result.getString(Field.FILE_NAME.getName()),
                      result.getString(Field.THUMBNAIL_NAME.getName()),
                      result.getString(Field.VERSION_ID.getName()),
                      result.getString(Field.OWNER_ID.getName()));
                  Item fileMetaData = FileMetaData.getMetaData(finalFileId, storageType.name());
                  if (Objects.nonNull(fileMetaData)
                      && fileMetaData.hasAttribute("lastCommentedOn")) {
                    result.put("lastCommentedOn", fileMetaData.getLong("lastCommentedOn"));
                    sendOK(segment, message, result, mills);
                  } else {
                    Promise<JsonObject> promise = Promise.promise();
                    eb_request(
                        segment,
                        CommentVerticle.address + ".getLatestFileComment",
                        jsonObject,
                        response -> {
                          if (response.succeeded() && Objects.nonNull(response.result())) {
                            JsonObject lastCommentResult =
                                (JsonObject) response.result().body();
                            promise.complete(lastCommentResult);
                          } else {
                            promise.fail(response.cause());
                          }
                        });
                    promise.future().onComplete(lastCommentEvent -> {
                      if (lastCommentEvent.succeeded()) {
                        JsonObject lastCommentResult = lastCommentEvent.result();
                        if (Objects.nonNull(lastCommentResult)
                            && lastCommentResult.containsKey("lastCommentedOn")) {
                          Long lastCommentedOn = lastCommentResult.getLong("lastCommentedOn");
                          if (Objects.nonNull(lastCommentedOn)) {
                            result.put("lastCommentedOn", lastCommentedOn);
                          }
                        }
                      } else {
                        log.error("Couldn't get lastCommentedOn for fileId : " + finalFileId
                            + " -> " + lastCommentEvent.cause());
                      }
                      sendOK(segment, message, result, mills);
                    });
                  }
                } else {
                  sendOK(segment, message, result, mills);
                }
                /*
                Entity parent = XRayManager.createDummySegment();
                TemporaryData.putItemInfoCache(jsonObject.getString(Field.USER_ID.getName()), Utils
                .getEncapsulatedId(jsonObject), ((JsonObject) event.result().body()).encode());
                XRayManager.endSegment(parent);
                 */
              }
            } else {
              throw new KudoFileException(
                  event.cause().getLocalizedMessage(),
                  KudoErrorCodes.FILE_GET_INFO_ERROR,
                  HttpStatus.BAD_REQUEST);
            }
          } catch (KudoFileException kfe) {
            sendError(
                segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
          }
          XRayManager.endSegment(standaloneSegment);
        });
    //        }
  }

  private void doGetTrashedInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (folderId != null) {
      jsonObject.mergeIn(Utils.parseItemId(folderId, Field.FOLDER_ID.getName()));
    }
    if (fileId != null) {
      jsonObject.mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()));
    }
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(
        segment, message, storageType, jsonObject.getString(Field.USER_ID.getName()))) {
      return;
    }
    String userId = getRequiredString(segment, USER_ID, message, jsonObject);
    if (userId == null) {
      return;
    }
    fileId = jsonObject.getString(Field.FILE_ID.getName());
    boolean isFile = fileId != null;
    String finalFileId = fileId;
    //        String cache = TemporaryData.getTrashCache(jsonObject.getString(Field.USER_ID
    //        .getName()), Utils
    //        .getEncapsulatedId(jsonObject));
    //        if (cache != null){
    //            XrayManager.newSegmentContextExecutor(segment);
    //            sendOK(segment, message, new JsonObject(cache), mills);
    //            recordExecutionTime("getTrash", System.currentTimeMillis() - mills);
    //        }
    //        else {
    eb_request(
        segment, StorageType.getAddress(storageType) + ".getTrashStatus", jsonObject, event -> {
          handleExternal(segment, event, message, mills);
          recordExecutionTime("getTrash", System.currentTimeMillis() - mills);
          if (event.succeeded()) {
            if (!OK.equals(
                ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
              if (isFile) {
                eb_send(
                    segment,
                    RecentFilesVerticle.address + ".updateRecentFile",
                    new JsonObject()
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.FILE_ID.getName(), finalFileId)
                        .put(Field.ACCESSIBLE.getName(), false)
                        .put(Field.STORAGE_TYPE.getName(), storageType.name()));
              }
            } else {
              /*
              Entity parent = XRayManager.createDummySegment();
              TemporaryData.putTrashedCache(jsonObject.getString(Field.USER_ID.getName()),
              Utils
              .getEncapsulatedId(jsonObject), ((JsonObject) event.result().body()).encode());
              XRayManager.endSegment(parent);
               */
            }
          }
        });
    //        }
  }

  private void doChangeOwner(Message<JsonObject> message, boolean isFolder) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(USER_ID);
    String id = Utils.parseItemId(
            message.body().getString(Field.ID.getName()), Field.FILE_ID.getName())
        .getString(Field.FILE_ID.getName());
    String newOwner = message.body().getString("newOwner");
    if (newOwner.matches(".+@.+\\.[a-zA-Z]+")) {
      Iterator<Item> it = Users.getUserByEmail(newOwner);
      if (it.hasNext()) {
        newOwner = it.next().getString(Field.SK.getName());
      } else {
        sendError(segment, message, Utils.getLocalizedString(message, "FL3"), HttpStatus.NOT_FOUND);
        return;
      }
    }
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    if (userId.equals(newOwner)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "NewUserShouldBeDifferent"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (StorageType.INTERNAL.equals(storageType) || StorageType.SAMPLES.equals(storageType)) {
      eb_request(
          segment,
          StorageType.getAddress(storageType) + ".changeOwner",
          new JsonObject()
              .put(Field.USER_ID.getName(), userId)
              .put(Field.IS_FOLDER.getName(), isFolder)
              .put(Field.ID.getName(), id)
              .put("newOwner", newOwner),
          event -> {
            handleExternal(segment, event, message, mills);
            recordExecutionTime("changeOwner", System.currentTimeMillis() - mills);
          });
    } else {
      sendOK(segment, message, mills);
    }

    // updating owner in metadata
    String finalNewOwner = newOwner;
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("putMetadataOnGetInfo")
        .run((Segment blockingSegment) -> {
          eb_request(
              blockingSegment,
              StorageType.getAddress(storageType) + ".getInfo",
              message.body().put(Field.FILE_ID.getName(), id),
              info -> {
                if (info.succeeded()) {
                  JsonObject result = (JsonObject) info.result().body();
                  if (OK.equals(result.getString(Field.STATUS.getName()))) {
                    log.info("[ METADATA ] Update on doGetInfo");
                    FileMetaData.putMetaData(
                        id,
                        storageType,
                        result.getString(Field.FILE_NAME.getName()),
                        result.getString(Field.THUMBNAIL_NAME.getName()),
                        result.getString(Field.VERSION_ID.getName()),
                        finalNewOwner);
                  }
                }
              });
        });
  }

  private void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = message.body().getString(Field.FILE_ID.getName());
    JsonObject parsedId = Utils.parseItemId(fileId, Field.FILE_ID.getName());
    fileId = parsedId.getString(Field.FILE_ID.getName());
    String token = message.body().getString(Field.TOKEN.getName());
    String password = message.body().getString(Field.PASSWORD.getName());
    String encrypted = message.body().getString("encrypted");
    String xSessionId = message.body().getString(Field.X_SESSION_ID.getName());

    // check encryption
    if (encrypted != null && !encrypted.isEmpty()) {
      String decrypted;
      try {
        try {
          decrypted =
              EncryptHelper.rsaDecode(encrypted, config.getProperties().getFluorineRsaKey());
        } catch (Exception e) {
          throw new KudoFileException(
              e.getCause().getLocalizedMessage(),
              KudoErrorCodes.FILE_TOKEN_ERROR,
              HttpStatus.INTERNAL_SERVER_ERROR);
        }
        if (!decrypted.equals(token)) {
          throw new KudoFileException(
              Utils.getLocalizedString(message, "EncryptedValueIsIncorrect"),
              KudoErrorCodes.FILE_TOKEN_ERROR,
              HttpStatus.BAD_REQUEST,
              "EncryptedValueIsIncorrect");
        }
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      }
    }
    try {
      PublicLink publicLink = new PublicLink(fileId);
      publicLink.findLinkByToken(token);
      if ((!Utils.isStringNotNullOrEmpty(xSessionId)
              || !publicLink.validateXSession(token, xSessionId))
          && !publicLink.isValid(token, password)) {
        sendError(segment, message, "Link is invalid", HttpStatus.FORBIDDEN, "PL1");
      }

      StorageType storageType = StorageType.getStorageType(publicLink.getStorageType());
      final String lastModifier = Utils.isStringNotNullOrEmpty(publicLink.getLastUser())
          ? publicLink.getLastUser()
          : "anonymous";
      final String finalFileId = fileId;
      final String externalId = publicLink.getExternalId();
      final String location = publicLink.getLocation();
      final String sharedLink = publicLink.getSharedLink();
      Thread trackerThread = new Thread(() -> {
        try {
          SharingLog sharinglog = new SharingLog(
              "no_data",
              finalFileId,
              storageType,
              "no_data",
              GMTHelper.utcCurrentTime(),
              true,
              SharingTypes.PUBLIC_LINK,
              SharingActions.LINK_ACCESSED,
              AuthManager.ClientType.getClientType(message.body().getString(Field.DEVICE.getName()))
                  .name(),
              null,
              null);
          sharinglog.sendToServer();
        } catch (Exception e) {
          log.error(e);
        }
      });
      trackerThread.start();
      String userId = publicLink.getUserId();
      JsonObject body = new JsonObject()
          .put(Field.FILE_ID.getName(), finalFileId)
          .put(Field.STORAGE_TYPE.getName(), storageType)
          .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
          .put(Field.EXTERNAL_ID.getName(), externalId)
          .put(Field.USER_ID.getName(), userId)
          .put(Field.SHARED_LINK.getName(), sharedLink)
          .put(Field.LOCATION.getName(), location)
          .put(Field.RETURN_DOWNLOAD_URL.getName(), returnDownloadUrl);
      String requestId = DownloadRequests.createRequest(
          userId, fileId, null, null, storageType, lastModifier, externalId);
      requestAndGetFile(
          segment,
          message,
          "getFileByToken",
          body,
          storageType,
          requestId,
          finalFileId,
          lastModifier,
          xSessionId,
          mills);
    } catch (PublicLinkException ple) {
      sendError(segment, message, ple.toResponseObject(), HttpStatus.FORBIDDEN, "PL1");
    }
  }

  private void requestAndGetFile(
      Entity segment,
      Message<JsonObject> message,
      String address,
      JsonObject body,
      StorageType storageType,
      String requestId,
      String fileId,
      String lastModifier,
      String xSessionId,
      long mills) {
    breaker
        .execute(promise -> eb_request(
            segment,
            StorageType.getAddress(storageType) + "." + address,
            body.put(Field.DOWNLOAD_TOKEN.getName(), requestId),
            event -> {
              if (event.succeeded()
                  && event.result() != null
                  && (event.result().body() instanceof Buffer
                      || (event.result().body() instanceof JsonObject && isOk(event)))) {
                promise.complete(event.result().body());
                new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                    .run((Segment blockingSegment) -> {
                      addSubscription(blockingSegment, storageType, body);
                      if (Utils.isStringNotNullOrEmpty(lastModifier)) {
                        sendFileLogsToServer(lastModifier, fileId, storageType);
                      }
                    });
              } else {
                if (event.cause() == null) {
                  promise.fail(
                      event.result() != null ? event.result().body().toString() : emptyString);
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
          String versionId;
          if (res instanceof Buffer) {
            Buffer buffer = (Buffer) res;
            message.reply(buffer);
            deleteDownloadRequests(segment, message, requestId);
            ParsedMessage parsedMessage = MessageUtils.parseBuffer(buffer, false);
            versionId = parsedMessage.getJsonObject().getString(Field.VER_ID.getName());
          } else {
            sendOK(segment, message, HttpStatus.OK, (JsonObject) res, mills);
            versionId = ((JsonObject) res).getString(Field.VERSION_ID.getName());
          }
          // update versionId for xSession
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("updateFileVersionId")
              .runWithoutSegment(() -> {
                if (Utils.isStringNotNullOrEmpty(versionId)
                    && Utils.isStringNotNullOrEmpty(xSessionId)) {
                  XenonSessions.setVersionId(fileId, xSessionId, versionId);
                }
              });
          XRayManager.endSegment(segment);
        })
        .onFailure(e -> {
          if (e instanceof TimeoutException) {
            DownloadRequests.setRequestData(
                requestId, null, null, emptyString, DownloadRequests.DownloadType.LARGE_DATA);
            sendOK(
                segment,
                message,
                HttpStatus.CREATED,
                new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId),
                mills);
          } else {
            log.error("Something went wrong in getting file data - FILE " + fileId
                + " | storageType " + storageType + " : " + e);
            try {
              throw new KudoFileException(
                  (e.getCause() != null)
                      ? e.getCause().getLocalizedMessage()
                      : e.getLocalizedMessage(),
                  KudoErrorCodes.FILE_GET_DATA_ERROR,
                  HttpStatus.BAD_REQUEST);
            } catch (KudoFileException kfe) {
              sendError(
                  segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
            }
          }
        });
  }

  private void sendFileLogsToServer(String lastModifier, String fileId, StorageType storageType) {
    try {
      FileLog fileLog = new FileLog(
          lastModifier,
          fileId,
          storageType,
          "anonymous",
          GMTHelper.utcCurrentTime(),
          true,
          FileActions.OPEN_BY_SHARED_LINK,
          GMTHelper.utcCurrentTime(),
          null,
          "anonymous",
          null,
          AuthManager.ClientType.BROWSER.name(),
          null);
      fileLog.sendToServer();
    } catch (Exception e) {
      log.error(e);
    }
  }

  private void addSubscription(Entity entity, StorageType storageType, JsonObject json) {
    // we hit here only if user has full access.
    // So we have to add global scope
    eb_send(
        entity,
        Subscriptions.address + ".addSubscription",
        new JsonObject()
            .put(Field.FILE_ID.getName(), json.getString(Field.FILE_ID.getName()))
            .put(Field.USER_ID.getName(), json.getString(USER_ID))
            .put(Field.STORAGE_TYPE.getName(), storageType)
            .put(Field.EXTERNAL_ID.getName(), json.getString(Field.EXTERNAL_ID.getName()))
            .put("scope", new JsonArray().add(Subscriptions.subscriptionScope.GLOBAL.toString()))
            .put("scopeUpdate", Subscriptions.scopeUpdate.REWRITE.toString()));
  }

  // deleting unnecessary download request data if data is received within 20 secs
  private void deleteDownloadRequests(
      Entity segment, Message<JsonObject> message, String requestId) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("deleteDownloadRequests")
        .runWithoutSegment(() -> {
          DownloadRequests.deleteDownloadRequestData(requestId);
          DownloadRequests.deleteRequest(requestId);
        });
  }

  private void replyDiffs(Entity segment, Message<JsonObject> message, Buffer buffer, long mills) {
    ParsedMessage parsedMessage = MessageUtils.parseBuffer(buffer, true);
    replyDiffs(
        segment,
        message,
        parsedMessage.getContentAsByteArray(),
        parsedMessage.getJsonObject().getString(Field.VER_ID.getName()),
        mills,
        parsedMessage.getJsonObject());
  }

  private void replyDiffs(
      Entity segment,
      Message<JsonObject> message,
      byte[] baseData,
      String changeId,
      long mills,
      JsonObject attributes) {
    String baseStr = null;
    if (baseData != null) {
      baseStr = new String(Base64.encodeBase64(baseData), StandardCharsets.UTF_8);
    }
    attributes.remove(Field.DATA.getName());
    attributes.remove(Field.VER_ID.getName());
    attributes.remove(Field.COLLABORATORS.getName());
    attributes.remove(Field.LOCALE.getName());
    attributes.remove(Field.LOCATION.getName());
    attributes.remove(Field.TRACE_ID.getName());
    attributes.remove(Field.SEGMENT_PARENT_ID.getName());
    attributes.remove("accessToken");
    attributes.remove(Field.USER_ID.getName());
    attributes.remove(Field.SESSION_ID.getName());
    attributes.put(Field.BASE_CHANGE_ID.getName(), changeId);
    attributes.put(Field.FILE_NAME.getName(), attributes.getString(Field.NAME.getName()));
    JsonObject json = attributes
        .put("baseContent", baseStr)
        .put("diffs", new JsonArray())
        .put("changeId", changeId);
    sendOK(segment, message, json, mills);
    recordExecutionTime("getDiffs", System.currentTimeMillis() - mills);
  }

  private void doRequestDownload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String format = getRequiredString(segment, "format", message, jsonObject);
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    if (fileId == null || userId == null || format == null) {
      return;
    }
    fileId = message
        .body()
        .mergeIn(Utils.parseItemId(fileId, Field.FILE_ID.getName()))
        .getString(Field.FILE_ID.getName());
    StorageType storageType =
        StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName()));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    String requestId = DownloadRequests.createRequest(
        userId,
        fileId,
        FileFormats.FileExtension(format),
        DownloadRequests.DownloadType.S3,
        storageType,
        null,
        jsonObject.getString(Field.EXTERNAL_ID.getName()));
    String verId = jsonObject.getString(Field.VERSION_ID.getName());

    String finalFileId = fileId;
    if (verId == null) {
      eb_request(segment, StorageType.getAddress(storageType) + ".getInfo", jsonObject, event -> {
        if (event.succeeded()
            && OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
          eb_send(
              segment,
              ThumbnailsManager.address + ".convertFile",
              message
                  .body()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.FILE_ID.getName(), finalFileId)
                  .put(Field.TOKEN.getName(), requestId)
                  .put(
                      Field.EXT.getName(),
                      Extensions.getExtension(((JsonObject) event.result().body())
                          .getString(Field.FILE_NAME.getName())))
                  .put(
                      Field.VERSION_ID.getName(),
                      ((JsonObject) event.result().body()).getString(Field.VER_ID.getName())));
        } else {
          eb_send(
              segment,
              ThumbnailsManager.address + ".convertFile",
              message
                  .body()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.FILE_ID.getName(), finalFileId)
                  .put(Field.TOKEN.getName(), requestId)
                  .put(Field.VERSION_ID.getName(), emptyString)
                  .put(Field.EXT.getName(), ".dwg"));
        }
        sendOK(
            segment,
            message,
            HttpStatus.OK,
            new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId),
            mills);
      });
    } else {
      sendOK(
          segment,
          message,
          HttpStatus.OK,
          new JsonObject().put(Field.ENCAPSULATED_ID.getName(), requestId),
          mills);

      eb_send(
          segment,
          ThumbnailsManager.address + ".convertFile",
          message
              .body()
              .put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.TOKEN.getName(), requestId)
              .put(Field.VERSION_ID.getName(), verId)
              .put(Field.EXT.getName(), ".dwg"));
    }
  }

  private void doGetDownload(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String fileId = Utils.parseItemId(
            getRequiredString(segment, Field.FILE_ID.getName(), message, message.body()),
            Field.FILE_ID.getName())
        .getString(Field.FILE_ID.getName());
    String userId = getRequiredString(segment, USER_ID, message, message.body());
    String token = getRequiredString(segment, Field.TOKEN.getName(), message, message.body());
    if (fileId == null || userId == null || token == null) {
      return;
    }
    Item request = DownloadRequests.getRequest(token);
    try {
      if (request == null
          || !request.getString(Field.USER_ID.getName()).equals(userId)
          || !request.getString(Field.FILE_ID.getName()).equals(fileId)) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "UnknownRequestToken"),
            KudoErrorCodes.FILE_DOWNLOAD_ERROR,
            HttpStatus.BAD_REQUEST,
            "UnknownRequestToken");
      }
      @NonNls JobStatus status = JobStatus.findStatus(request.getString(Field.STATUS.getName()));
      if (status.equals(JobStatus.IN_PROGRESS)) {
        sendOK(segment, message, HttpStatus.ACCEPTED, System.currentTimeMillis() - millis);
        return;
      }
      if (status.equals(JobStatus.ERROR) || status.equals(JobStatus.UNKNOWN)) {
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("deleteDownloadRequestOnError")
            .runWithoutSegment(() -> {
              DownloadRequests.deleteRequest(token);
              s3Regional.delete(token + request.getString("extension"));
            });
        throw new KudoFileException(
            request.getString(Field.ERROR.getName()),
            KudoErrorCodes.FILE_DOWNLOAD_ERROR,
            HttpStatus.INTERNAL_SERVER_ERROR);
      }
      DownloadRequests.deleteRequest(token);
      if (request.hasAttribute(Field.TYPE.getName())) {
        if (!request
            .getString(Field.TYPE.getName())
            .equals(DownloadRequests.DownloadType.S3.name())) {
          JsonObject data = DownloadRequests.getDownloadRequestData(token);
          if (data != null) {
            byte[] byteData = data.getBinary(Field.DATA.getName());
            if (request
                .getString(Field.TYPE.getName())
                .equals(DownloadRequests.DownloadType.LARGE_DATA.name())) {
              message.reply(Buffer.buffer(byteData));
              XRayManager.endSegment(segment);
            } else {
              replyDiffs(segment, message, Buffer.buffer(byteData), millis);
            }
            DownloadRequests.deleteDownloadRequestData(token);
          } else {
            throw new KudoFileException(
                Utils.getLocalizedString(message, "DownloadDataNotFound"),
                KudoErrorCodes.FILE_DOWNLOAD_ERROR,
                HttpStatus.BAD_REQUEST,
                "DownloadDataNotFound");
          }
        } else {
          byte[] data = s3Regional.get(token + request.getString("extension"));
          s3Regional.delete(token + request.getString("extension"));
          message.reply(data);
          XRayManager.endSegment(segment);
        }
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              String lastModifier = request.getString("lastModifier");
              StorageType storageType =
                  StorageType.getStorageType(request.getString(Field.STORAGE_TYPE.getName()));
              addSubscription(blockingSegment, storageType, new JsonObject(request.toJSON()));
              if (Utils.isStringNotNullOrEmpty(lastModifier)) {
                sendFileLogsToServer(lastModifier, fileId, storageType);
              }
            });
      } else {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "DownloadDataNotFound"),
            KudoErrorCodes.FILE_DOWNLOAD_ERROR,
            HttpStatus.BAD_REQUEST,
            "DownloadDataNotFound");
      }
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private void doGetToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    JsonObject parameters = message.body().getJsonObject("parameters");
    if (parameters == null || parameters.isEmpty()) {
      return;
    }
    String apikey = config.getProperties().getApikey();
    String tokenGenerator = config.getProperties().getTokenGenerator();
    HttpResponse<String> response = AWSXRayUnirest.post(tokenGenerator, "gridFsModule.getToken")
        .body(new JSONObject(parameters.encode()))
        .header("x-api-key", apikey)
        .header("x-original-user-agent", message.body().getString(Field.USER_AGENT.getName()))
        .asString();
    if (response.isSuccess()) {
      sendOK(
          segment,
          message,
          new JsonObject(response.getBody()),
          System.currentTimeMillis() - millis);
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetAccessToken"),
          HttpStatus.INTERNAL_SERVER_ERROR);
    }
    XRayManager.endSegment(segment);
  }

  private void doCreateShortcut(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    eb_request(
        segment,
        StorageType.getAddress(
                StorageType.getStorageType(message.body().getString(Field.STORAGE_TYPE.getName())))
            + ".createShortcut",
        message.body(),
        event -> handleExternal(segment, event, message, mills));

    XRayManager.endSegment(segment);
    recordExecutionTime("cloneFile", System.currentTimeMillis() - mills);
  }

  private JsonArray processShare(JsonArray collaborators, StorageType storageType) {
    if (Objects.nonNull(collaborators) && !collaborators.isEmpty()) {
      return new JsonArray(collaborators.stream()
          .filter(email -> Utils.isStringNotNullOrEmpty((String) email))
          .map(email ->
              (!storageType.equals(StorageType.SAMPLES)) ? ((String) email).toLowerCase() : email)
          .collect(Collectors.toList()));
    }
    return collaborators;
  }

  private void validateRecentFilesAfterTrash(
      Entity segment,
      Message<JsonObject> message,
      String userId,
      StorageType storageType,
      long delay,
      boolean reValidationRequired) {
    // to make sure that all the sub files/folders are deleted from the external storage before
    // validation (WB-53)
    // Random case : sometimes the files doesn't gets deleted while validating, so try revalidating
    vertx.setTimer(delay, time -> {
      Entity subSegment =
          XRayManager.createSubSegment(operationGroup, segment, "validateRecentFiles");
      eb_request(
          subSegment,
          RecentFilesVerticle.address + ".validateRecentFiles",
          new JsonObject()
              .put(Field.USER_ID.getName(), userId)
              .put(Field.STORAGE_TYPE.getName(), storageType.name()),
          recEvent -> {
            if (recEvent.succeeded()
                && ((JsonObject) recEvent.result().body())
                    .getString(Field.STATUS.getName())
                    .equals(OK)) {
              log.info("Delete Folder : Validation recent result - "
                  + recEvent.result().body());
              eb_send(
                  subSegment,
                  WebSocketManager.address + ".recentsUpdate",
                  new JsonObject().put(Field.COLLABORATORS.getName(), new JsonArray().add(userId)));
              if (reValidationRequired) {
                new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                    .withName("validateRecentFilesAfterTrash")
                    .runWithoutSegment(() -> validateRecentFilesAfterTrash(
                        subSegment, message, userId, storageType, 3000, false));
              }
            }
          });
      XRayManager.endSegment(subSegment);
    });
  }

  private void updateAndNotifyTrashedItems(
      Entity segment,
      Message<JsonObject> message,
      StorageType storageType,
      Set<String> filesSet,
      Set<String> foldersSet) {
    // Temporary for other storages
    if (!storageType.equals(StorageType.SAMPLES)) {
      // delete all possible xSessions for file
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("deleteFileSessionsAndLinks")
          .runWithoutSegment(() -> filesSet.stream()
              .map(o -> Utils.parseObjectId(o).getString(Field.OBJECT_ID.getName()))
              .collect(Collectors.toSet())
              .parallelStream()
              .forEach(objectId -> {
                // delete all possible
                // xSessions for file
                XenonSessions.deleteAllSessionsForFile(objectId);
                // delete all version
                // links for file
                VersionLinkRepository.getInstance().deleteAllFileLinks(objectId);
              }));
    }

    // Temporary for only GDrive for now
    if (storageType.equals(StorageType.GDRIVE)) {
      // delete all possible xSessions for all the sub-files inside the folders
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("handleFolderSubItemsAfterTrash")
          .run((Segment blockingSegment) -> {
            foldersSet.forEach(o -> {
              String folderId = Utils.parseObjectId(o).getString(Field.OBJECT_ID.getName());
              List<Future<Void>> queue = new ArrayList<>();
              deleteFileSessionsAndNotifyForAllSubItems(
                  blockingSegment,
                  storageType,
                  message.body(),
                  folderId,
                  null,
                  new HashSet<>(),
                  new HashSet<>(),
                  queue);
              Promise<Future<Void>> folderPromise = Promise.promise();
              completeFutureRequests(queue, folderPromise);
              folderPromise.future().onComplete(event -> {
                message.body().put("trashed", true);
                trashFolder(segment, null, message, folderId, null);
              });
            });
          });
    }
  }

  private void deleteFileSessionsAndNotifyForAllSubItems(
      Entity blockingSegment,
      StorageType storageType,
      JsonObject body,
      String folderId,
      String pageToken,
      Set<String> processedFileIds,
      Set<String> processedFolderIds,
      List<Future<Void>> queue) {
    Promise<Void> promise = Promise.promise();
    queue.add(promise.future());
    eb_request(
        blockingSegment,
        StorageType.getAddress(storageType) + ".getFolderContent",
        new JsonObject()
            .put(USER_ID, body.getString(Field.USER_ID.getName()))
            .put(Field.FOLDER_ID.getName(), folderId)
            .put(Field.IS_ADMIN.getName(), body.getBoolean(Field.IS_ADMIN.getName()))
            .put(Field.LOCALE.getName(), body.getString(Field.LOCALE.getName()))
            .put(Field.EXTERNAL_ID.getName(), body.getString(Field.EXTERNAL_ID.getName()))
            .put(Field.PAGE_TOKEN.getName(), pageToken)
            .put(Field.FULL.getName(), false)
            .put(Field.SESSION_ID.getName(), body.getString(Field.SESSION_ID.getName()))
            .put(Field.TRASH.getName(), false)
            .put(Field.DEVICE.getName(), body.getString(Field.DEVICE.getName()))
            .put("isUserThumbnailDisabled", true),
        event -> {
          if (event.succeeded() && isOk(event)) {
            JsonObject response = MessageUtils.parse(event.result()).getJsonObject();
            if (Objects.nonNull(response)) {
              if (response.containsKey(Field.RESULTS.getName())) {
                JsonObject results = response.getJsonObject(Field.RESULTS.getName());
                if (results.containsKey(Field.FILES.getName())) {
                  JsonArray files = results.getJsonArray(Field.FILES.getName());
                  files.forEach(file -> {
                    String fileId = ((JsonObject) file).getString(Field.WS_ID.getName());
                    if (processedFileIds.contains(fileId)) {
                      return;
                    }
                    processedFileIds.add(fileId);
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".deleted",
                        new JsonObject()
                            .put(
                                Field.FILE_ID.getName(),
                                StorageType.processFileIdForWebsocketNotification(
                                    storageType, fileId)));
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".objectDeleted",
                        new JsonObject()
                            .put(Field.USER_ID.getName(), body.getString(Field.USER_ID.getName()))
                            .put(
                                Field.SESSION_ID.getName(),
                                body.getString(Field.SESSION_ID.getName()))
                            .put(Field.ITEM_ID.getName(), fileId)
                            .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                            .put(Field.IS_FOLDER.getName(), false));
                    eb_send(
                        blockingSegment,
                        RecentFilesVerticle.address + ".updateRecentFile",
                        new JsonObject()
                            .put(Field.FILE_ID.getName(), fileId)
                            .put(Field.ACCESSIBLE.getName(), false)
                            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                    XenonSessions.deleteAllSessionsForFile(fileId);
                  });
                }
                if (results.containsKey(Field.FOLDERS.getName())) {
                  JsonArray folders = results.getJsonArray(Field.FOLDERS.getName());
                  if (!folders.isEmpty()) {
                    folders.forEach(folder -> {
                      String nextFolderId = Utils.parseObjectId(
                              ((JsonObject) folder).getString(Field.ENCAPSULATED_ID.getName()))
                          .getString(Field.OBJECT_ID.getName());
                      if (processedFolderIds.contains(nextFolderId)) {
                        return;
                      }
                      processedFolderIds.add(nextFolderId);
                      eb_send(
                          blockingSegment,
                          WebSocketManager.address + ".objectDeleted",
                          new JsonObject()
                              .put(Field.USER_ID.getName(), body.getString(Field.USER_ID.getName()))
                              .put(
                                  Field.SESSION_ID.getName(),
                                  body.getString(Field.SESSION_ID.getName()))
                              .put(Field.ITEM_ID.getName(), nextFolderId)
                              .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                              .put(Field.IS_FOLDER.getName(), true));
                      deleteFileSessionsAndNotifyForAllSubItems(
                          blockingSegment,
                          storageType,
                          body,
                          nextFolderId,
                          null,
                          processedFileIds,
                          processedFolderIds,
                          queue);
                    });
                  }
                }
              }
              String nextPageToken = response.getString(Field.PAGE_TOKEN.getName());
              if (Utils.isStringNotNullOrEmpty(nextPageToken)) {
                deleteFileSessionsAndNotifyForAllSubItems(
                    blockingSegment,
                    storageType,
                    body,
                    folderId,
                    nextPageToken,
                    processedFileIds,
                    processedFolderIds,
                    queue);
              }
            }
          } else {
            log.warn("[Notify Trash] : Error occurred in getting folder content for folderId - "
                + folderId + " : " + event.cause());
          }
          promise.complete();
        });
  }

  private void updateFileSessionState(String fileId, String fileSessionId, boolean isConflicted) {
    // switch to ACTIVE, as soon as the save request is completed
    if (Utils.isStringNotNullOrEmpty(fileId) && Utils.isStringNotNullOrEmpty(fileSessionId)) {
      if (!isConflicted) {
        XenonSessions.updateSessionState(fileId, fileSessionId, XenonSessions.SessionState.ACTIVE);
      } else {
        // switch to STALE if file conflicted
        XenonSessions.updateSessionState(fileId, fileSessionId, XenonSessions.SessionState.STALE);
      }
    }
  }

  private void completeFutureRequests(List<Future<Void>> queue, Promise<Future<Void>> promise) {
    if (queue.isEmpty()) {
      promise.complete();
      return;
    }
    TypedCompositeFuture.join(queue).onComplete(event -> {
      queue.removeIf(Future::succeeded);
      completeFutureRequests(queue, promise);
    });
  }
}
