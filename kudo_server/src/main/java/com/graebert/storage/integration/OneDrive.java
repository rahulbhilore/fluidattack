package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.CollaboratorInfo;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onedrive.Site;
import com.graebert.storage.integration.onedrive.graphApi.GraphApi;
import com.graebert.storage.integration.onedrive.graphApi.ODApi;
import com.graebert.storage.integration.onedrive.graphApi.ODBApi;
import com.graebert.storage.integration.onedrive.graphApi.entity.CloneObjectResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.CreateFolderResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.GraphId;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.Permissions;
import com.graebert.storage.integration.onedrive.graphApi.entity.PromoteVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadFileResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectPermissionsException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.ids.IdName;
import com.graebert.storage.storage.ids.KudoFileId;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.xml.bind.DatatypeConverter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import org.jetbrains.annotations.NonNls;

public class OneDrive extends BaseStorage implements Storage {
  private OperationGroup operationGroup;
  public static final String addressPersonal = "onedrive";
  public static final String addressBusiness = "onedrivebusiness";
  private String address;
  private boolean isPersonal;
  private S3Regional s3Regional = null;
  private StorageType storageType;
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };

  public OneDrive() {}

  @Override
  public void start() throws Exception {
    super.start();

    if (config.getProperties().getOnedrivebusiness()) {
      isPersonal = false;
      address = addressBusiness;
      operationGroup = OperationGroup.ONEDRIVEBUSINESS;
      storageType = StorageType.ONEDRIVEBUSINESS;
      ODBApi.Initialize(
          "/me",
          config.getProperties().getOnedriveBusinessClientId(),
          config.getProperties().getOnedriveBusinessClientSecret(),
          config.getProperties().getOnedriveBusinessRedirectUri(),
          config.getProperties().getFluorineSecretKey(),
          config);
    } else {
      isPersonal = true;
      address = addressPersonal;
      operationGroup = OperationGroup.ONEDRIVE;
      storageType = StorageType.ONEDRIVE;
      ODApi.Initialize(
          emptyString,
          config.getProperties().getOnedriveClientId(),
          config.getProperties().getOnedriveClientSecret(),
          config.getProperties().getOnedriveRedirectUri(),
          config.getProperties().getFluorineSecretKey(),
          config);
    }

    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> event) -> doGetFolderContent(event, false));
    eb.consumer(address + ".createFolder", this::doCreateFolder);
    eb.consumer(address + ".moveFolder", this::doMoveFolder);
    eb.consumer(address + ".renameFolder", this::doRenameFolder);
    eb.consumer(address + ".deleteFolder", this::doDeleteFolder);
    eb.consumer(address + ".clone", this::doClone);
    eb.consumer(address + ".createShortcut", this::doCreateShortcut);
    eb.consumer(address + ".getFile", this::doGetFile);
    eb.consumer(address + ".getAllFiles", this::doGlobalSearch);
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
        address + ".getTrash", (Message<JsonObject> event) -> doGetFolderContent(event, true));
    eb.consumer(address + ".share", (Message<JsonObject> event) -> doShare(event));
    eb.consumer(address + ".deShare", this::doDeShare);
    eb.consumer(address + ".restore", this::doRestore);
    eb.consumer(address + ".getInfo", this::doGetInfo);
    eb.consumer(address + ".getThumbnail", this::doGetThumbnail);
    eb.consumer(address + ".doGetInfoByToken", this::doGetInfoByToken);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".eraseAll", this::doEraseAll);
    eb.consumer(address + ".eraseFile", this::doEraseFile);
    eb.consumer(address + ".eraseFolder", this::doEraseFolder);
    eb.consumer(address + ".connect", (Message<JsonObject> event) -> connect(event, true));
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

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-onedrive");
  }

  @Override
  public void doGetVersionByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String lt = jsonObject.getString(Field.LINK_TYPE.getName());

    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, fileIds.getExternalId())
          : ODBApi.connectByUserId(userId, fileIds.getExternalId());

      ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileIds.getId(), message);

      message.body().put(Field.NAME.getName(), objectInfo.getName(true));

      String realVersionId = VersionType.parse(versionId).equals(VersionType.LATEST)
          ? graphApi.getLatestVersionId(fileIds.getId())
          : versionId;

      byte[] data = graphApi.getFileVersionContent(fileIds.getId(), versionId);

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
          objectInfo.getName(true),
          LinkType.parse(lt).equals(LinkType.DOWNLOAD));
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getVersionByToken", System.currentTimeMillis() - mills);
  }

  @Override
  public void doCheckFileVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    // if versionId non-null - check file & version, else just file
    final String versionId = message.body().getString(Field.VERSION_ID.getName());
    final String userId = message.body().getString(Field.USER_ID.getName());

    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, fileIds.getExternalId())
          : ODBApi.connectByUserId(userId, fileIds.getExternalId());

      ObjectInfo objectInfo =
          graphApi.getObjectInfo(message, true, fileIds.getId(), false, true, false, false, false);

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          if (!graphApi.getVersions(fileIds.getId()).checkVersion(versionId)) {
            throw new Exception();
          }
        } catch (Exception exception) {
          sendError(
              segment,
              message,
              MessageFormat.format(
                  Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), versionId),
              HttpStatus.BAD_REQUEST);
          return;
        }
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.NAME.getName(), objectInfo.getName(true))
              .put(
                  Field.OWNER_IDENTITY.getName(),
                  ExternalAccounts.getExternalEmail(userId, fileIds.getExternalId()))
              .put(Field.COLLABORATORS.getName(), List.of(userId)),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  private <T> void handleError(Exception exception, Entity segment, Message<T> message) {
    String methodName = Thread.currentThread().getStackTrace()[2].getMethodName();
    log.error("[" + storageType + "] error " + methodName, exception);

    if (exception instanceof GraphException) {
      ((GraphException) exception).replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } else {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  // to use in consumer
  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      if (isPersonal) {
        ODApi.connect(message);
      } else {
        ODBApi.connect(message);
      }
      sendOK(segment, message);
    } catch (GraphException gex) {
      String errorMessage = gex.createErrorMessage(message);
      JsonObject errorObject = gex.createErrorObject(errorMessage, gex.getErrorId());
      boolean no_debug_log = MessageUtils.parse(message)
          .getJsonObject()
          .getBoolean(Field.NO_DEBUG_LOG.getName(), false);

      // send log for any internal graph error
      gex.sendErrorLog(segment, message, errorObject, no_debug_log);

      // reply with error
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisOnedriveAccount"),
          HttpStatus.FORBIDDEN,
          gex.getErrorId());
    }
  }

  public void doAddAuthCode(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String graebertId = (jsonObject).getString(Field.GRAEBERT_ID.getName());
    String username = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);
    String code = getRequiredString(segment, Field.CODE.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    if (userId == null || code == null || sessionId == null) {
      return;
    }

    try {
      GraphApi graphApi;

      if (address.equals(addressPersonal)) {
        graphApi = ODApi.connectAccount(userId, code, sessionId);
      } else {
        graphApi = ODBApi.connectAccount(userId, code, sessionId);
      }

      storageLog(
          segment,
          message,
          userId,
          graebertId,
          sessionId,
          username,
          storageType.toString(),
          graphApi.getExternalId(),
          true,
          intercomAccessToken);

      sendOK(segment, message, mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String nextLink = jsonObject.getString(Field.PAGE_TOKEN.getName());
    String p = jsonObject.getString(Field.PAGINATION.getName());
    boolean pagination = p == null || Boolean.parseBoolean(p);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    Boolean full = jsonObject.getBoolean(Field.FULL.getName());
    if (full == null) {
      full = true;
    }
    @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    Map<String, JsonObject> filesJson = new HashMap<>();
    Map<String, JsonObject> foldersJson = new HashMap<>();
    String device = jsonObject.getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    boolean isTouch = AuthManager.ClientType.TOUCH.name().equals(device);
    boolean useNewStructure = jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    List<JsonObject> thumbnailIds = new ArrayList<>();
    Map<String, String> possibleIdsToIds = new HashMap<>();
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    if (folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      JsonObject messageBody = message.body();
      boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;
      String driveId = new GraphId(folderId).getDriveId();

      if (!folderId.equals(Field.MINUS_1.getName()) || (isTouch && !useNewStructure) || trash) {
        JsonArray value = graphApi.getFolderContentArray(
            folderId, nextLink, trash, isTouch, pagination, useNewStructure);

        List<String> remoteIds = new ArrayList<>(), ids = new ArrayList<>();
        JsonObject obj;
        for (Object item : value) {
          obj = (JsonObject) item;
          String id = obj.getString(Field.ID.getName());
          if (ids.contains(id)) {
            continue;
          }
          ids.add(id);
          String remoteId = obj.containsKey(Field.REMOTE_ID.getName())
              ? obj.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : emptyString;
          if (remoteIds.contains(remoteId)) {
            continue;
          }
          if (!remoteId.isEmpty()) {
            remoteIds.add(remoteId);
          }
          if (obj.containsKey(Field.FILE.getName())
              || (obj.containsKey(Field.REMOTE_ID.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FILE.getName()))) {
            @NonNls String filename = obj.getString(Field.NAME.getName());
            if (Extensions.isValidExtension(extensions, filename)) {
              String driveIdForSharedLink = GraphUtils.getDriveIdFromId(GraphUtils.generateId(obj));
              if (!Utils.isStringNotNullOrEmpty(driveIdForSharedLink)) {
                driveIdForSharedLink = driveId;
              }
              obj.put(
                  Field.SHARED_LINKS_ID.getName(),
                  (driveIdForSharedLink == null ? emptyString : driveIdForSharedLink) + ":"
                      + obj.getString(Field.ID.getName()));
              String remoteFileId = obj.getString(Field.ID.getName());
              try {
                remoteFileId =
                    obj.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName());
              } catch (Exception ignore) {
              }
              boolean createThumbnail = canCreateThumbnails;
              if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                thumbnailIds.add(new JsonObject()
                    .put(
                        Field.FILE_ID.getName(),
                        new GraphId(driveIdForSharedLink, remoteFileId).getFullId())
                    .put(Field.DRIVE_ID.getName(), driveIdForSharedLink)
                    .put(Field.EXT.getName(), Extensions.getExtension(filename))
                    .put(Field.VERSION_ID.getName(), GraphUtils.getVersionId(obj)));
              } else {
                createThumbnail = false;
              }

              ObjectInfo objectInfo = GraphUtils.getFileJson(
                  config,
                  graphApi,
                  obj,
                  false,
                  full,
                  externalId,
                  null,
                  false,
                  storageType,
                  isAdmin,
                  userId,
                  message);
              filesJson.put(remoteFileId, objectInfo.toJson());

              if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                if (!objectInfo.isViewOnly()) {
                  possibleIdsToIds.put(
                      obj.getString(Field.SHARED_LINKS_ID.getName()), remoteFileId);
                  possibleIdsToIds.put(obj.getString(Field.ID.getName()), remoteFileId);
                }
              }
            }
          } else if (obj.containsKey(Field.FOLDER.getName())
              || (obj.containsKey(Field.REMOTE_ID.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FOLDER.getName()))) {
            ObjectInfo objectInfo = GraphUtils.getFileJson(
                config,
                graphApi,
                obj,
                true,
                full,
                externalId,
                null,
                false,
                storageType,
                isAdmin,
                userId,
                message);
            // if (driveId != null)
            // json.put(Field.ENCAPSULATED_ID.getName(), driveId + ":" +
            // json.getString(Field.ENCAPSULATED_ID.getName()));
            foldersJson.put(obj.getString(Field.ID.getName()), objectInfo.toJson());
          }
        }
        if (canCreateThumbnails && !thumbnailIds.isEmpty()) {
          createThumbnails(
              segment,
              new JsonArray(thumbnailIds),
              messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
        }

        if (full && !possibleIdsToIds.isEmpty()) {
          Map<String, JsonObject> newSharedLinksResponse =
              PublicLink.getSharedLinks(config, segment, userId, externalId, possibleIdsToIds);
          for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
            if (filesJson.containsKey(fileData.getKey())) {
              filesJson.put(
                  fileData.getKey(), filesJson.get(fileData.getKey()).mergeIn(fileData.getValue()));
            }
          }
        }
        List<JsonObject> foldersList = new ArrayList<>(foldersJson.values());
        foldersList.sort(
            Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
        List<JsonObject> filesList = new ArrayList<>(filesJson.values());
        filesList.sort(
            Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
        JsonObject result = new JsonObject()
            .put(
                Field.RESULTS.getName(),
                new JsonObject()
                    .put(Field.FILES.getName(), new JsonArray(filesList))
                    .put(Field.FOLDERS.getName(), new JsonArray(foldersList)))
            .put("number", filesJson.size() + foldersJson.size())
            .put(Field.PAGE_TOKEN.getName(), nextLink)
            .put(Field.FULL.getName(), full)
            .put(
                Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
        sendOK(segment, message, result, mills);
      }
      // root folder
      else {
        String myDriveId = null;
        try {
          myDriveId = graphApi.getMyDrive().getId();
        } catch (Exception e) {
          log.error(e);
        }
        ObjectPermissions objectPermissions = new ObjectPermissions()
            .setAllTo(false) // all defaults are false
            .setBatchTo(List.of(AccessType.canMoveFrom, AccessType.canMoveTo), true);
        String myFilesId = GraphUtils.ROOT_FOLDER_FAKE_ID;
        if (Utils.isStringNotNullOrEmpty(myDriveId)) {
          myFilesId = myDriveId + ":" + Field.ROOT.getName();
        }
        JsonObject result = new JsonObject()
            .put(
                Field.RESULTS.getName(),
                new JsonObject()
                    .put(Field.FILES.getName(), new JsonArray())
                    .put(
                        Field.FOLDERS.getName(),
                        new JsonArray()
                            .add(getSharedFolderInfo(
                                storageType,
                                externalId,
                                Utils.getLocalizedString(
                                    message, Field.SHARED_WITH_ME_FOLDER.getName())))
                            .add(new JsonObject()
                                .put(
                                    Field.ENCAPSULATED_ID.getName(),
                                    Utils.getEncapsulatedId(
                                        StorageType.getShort(storageType), externalId, myFilesId))
                                .put(Field.WS_ID.getName(), myFilesId)
                                .put(
                                    Field.NAME.getName(),
                                    Utils.getLocalizedString(
                                        message, Field.MY_FILES_FOLDER.getName()))
                                .put(
                                    Field.PARENT.getName(),
                                    Utils.getEncapsulatedId(
                                        StorageType.getShort(storageType),
                                        externalId,
                                        Field.MINUS_1.getName()))
                                .put(OWNER, emptyString)
                                .put(Field.CREATION_DATE.getName(), 0)
                                .put(Field.UPDATE_DATE.getName(), 0)
                                .put(Field.CHANGER.getName(), emptyString)
                                .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
                                .put(Field.SIZE_VALUE.getName(), 0)
                                .put(Field.SHARED.getName(), false)
                                .put(Field.VIEW_ONLY.getName(), false)
                                .put(Field.IS_OWNER.getName(), true)
                                .put(Field.IS_ROOT.getName(), true)
                                .put(Field.IS_DELETED.getName(), false)
                                .put(Field.CAN_MOVE.getName(), false)
                                .put(
                                    Field.SHARE.getName(),
                                    new JsonObject()
                                        .put(Field.VIEWER.getName(), new JsonArray())
                                        .put(Field.EDITOR.getName(), new JsonArray()))
                                .put(Field.EXTERNAL_PUBLIC.getName(), false)
                                .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
                                .put(Field.PUBLIC.getName(), false))))
            .put("number", 2)
            .put(Field.PAGE_TOKEN.getName(), nextLink)
            .put(Field.FULL.getName(), full)
            .put(
                Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
        sendOK(segment, message, result, mills);
      }

    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    try {
      long mills = System.currentTimeMillis();
      JsonObject jsonObject = message.body();
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
      String folderId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
      String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);

      if (folderId == null || userId == null || name == null) {
        return;
      }

      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);
      CreateFolderResult folder = graphApi.createFolder(folderId, name);

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType), graphApi.getExternalId(), folder.getId()))
              .put(Field.DRIVE_ID.getName(), folder.getDriveId()),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doMoveFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doMove(message, id);
  }

  public void doMoveFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doMove(message, id);
  }

  private void doMove(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (id == null || parentId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message)).move(id, parentId);

      sendOK(segment, message, mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doRenameFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doRename(message, id);
  }

  public void doRenameFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doRename(message, id);
  }

  private void doRename(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());

    if (id == null || name == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message)).rename(id, name);

      sendOK(segment, message, mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doDeleteFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doDelete(message, id);
  }

  public void doDeleteFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doDelete(message, id);
  }

  private void doDelete(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message)).delete(id);

      sendOK(segment, message, mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doClone(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject object = message.body();

    boolean isFile = true;
    String id = object.getString(Field.FILE_ID.getName());
    boolean doCopyComments = object.getBoolean(Field.COPY_COMMENTS.getName(), false);
    boolean doCopyShare = message.body().getBoolean(Field.COPY_SHARE.getName(), false);
    if (id == null) {
      id = object.getString(Field.FOLDER_ID.getName());
      isFile = false;
    }
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String name = object.getString(Field.FILE_NAME_C.getName());
    if (name == null) {
      name = object.getString(Field.FOLDER_NAME.getName());
    }
    if (name == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FilenameOrFoldernameMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String externalId = object.getString(Field.EXTERNAL_ID.getName());
    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);
      CloneObjectResult result = graphApi.cloneObject(id, name);
      String newFileId = result.getObjectId();
      if (doCopyComments) {
        String finalId = id;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_COMMENTS.getName())
            .run((Segment blockingSegment) -> {
              boolean doIncludeResolvedComments =
                  object.getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
              boolean doIncludeDeletedComments =
                  object.getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
              copyFileComments(
                  blockingSegment,
                  finalId,
                  storageType,
                  newFileId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }
      if (doCopyShare) {
        GraphId ids = new GraphId(id);
        boolean finalIsFile = isFile;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_SHARE.getName())
            .runWithoutSegment(() -> {
              List<CollaboratorInfo> collaborators = GraphUtils.getODItemCollaborators(
                  graphApi, ids.getDriveId(), ids.getId(), storageType);
              message
                  .body()
                  .put(Field.FOLDER_ID.getName(), newFileId)
                  .put(Field.IS_FOLDER.getName(), !finalIsFile);
              collaborators.forEach(user -> {
                message
                    .body()
                    .put(Field.ROLE.getName(), user.storageAccessRole)
                    .put(Field.EMAIL.getName(), user.email);
                doShare(message, false);
              });
            });
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(storageType, externalId, newFileId))
              .put(
                  Field.PARENT_ID.getName(),
                  Utils.getEncapsulatedId(storageType, externalId, result.getParentId())),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doGetFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String downloadToken = jsonObject.getString(Field.DOWNLOAD_TOKEN.getName());
    try {
      String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
      Boolean latest = jsonObject.getBoolean(Field.LATEST.getName());
      if (latest == null) {
        latest = false;
      }
      Boolean download = jsonObject.getBoolean(Field.DOWNLOAD.getName());
      if (download == null) {
        download = false;
      }
      Integer start = jsonObject.getInteger(Field.START.getName()),
          end = jsonObject.getInteger(Field.END.getName());
      if (fileId == null) {
        return;
      }
      boolean returnDownloadUrl =
          message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
      String versionId = jsonObject.getString(Field.VER_ID.getName());
      if (Utils.isStringNotNullOrEmpty(versionId) && versionId.startsWith(Field.CUR.getName())) {
        versionId = Field.CURRENT.getName();
        latest = true;
      }
      if (!Utils.isStringNotNullOrEmpty(versionId) && !latest) {
        latest = true;
      }

      String userId = jsonObject.getString(Field.USER_ID.getName());
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      if (!Utils.isStringNotNullOrEmpty(externalId)) {
        externalId = findExternalId(segment, message, jsonObject, storageType);
      }

      log.info(
          "[" + storageType + "] start doGetFile. Params: fileId " + fileId + ", userId " + userId
              + ", externalId " + externalId + ", versionId " + versionId + ", latest" + latest);

      // driveId can be inside jsonObject
      GraphId ids = new GraphId(externalId, fileId);
      XRayEntityUtils.putStorageMetadata(segment, message, storageType);
      XRayEntityUtils.putMetadata(segment, XrayField.DRIVE_ID, ids.getDriveId());
      XRayEntityUtils.putMetadata(segment, XrayField.FILE_ID, ids.getId());

      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      String name = emptyString;

      byte[] content;
      if (latest) {
        long size = 0;
        try {
          ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, ids.getFullId(), message);
          log.info("[" + storageType + "] doGetFile file info loaded, " + objectInfo.toJson());
          versionId = objectInfo.getVersionId();
          name = objectInfo.getName(true);
          size = objectInfo.getSizeValue();
        } catch (Exception exception) {
          log.error("[" + storageType + "] error doGetFile file info", exception);
        }
        if (returnDownloadUrl) {
          String downloadUrl = graphApi.getDownloadUrl(ids.getFullId(), null);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(
                segment,
                message,
                downloadUrl,
                size,
                versionId,
                (!isPersonal) ? graphApi.getAccessToken() : null,
                true,
                null,
                mills);
            return;
          }
        }
        content = graphApi.getFileContent(ids.getFullId());
        log.info(
            "[" + storageType + "] doGetFile file latest content loaded, length " + content.length);
      } else {
        if (returnDownloadUrl) {
          String downloadUrl = graphApi.getDownloadUrl(ids.getFullId(), versionId);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(
                segment,
                message,
                downloadUrl,
                null,
                versionId,
                graphApi.getAccessToken(),
                true,
                null,
                mills);
            return;
          }
        }
        content = graphApi.getFileVersionContent(ids.getFullId(), versionId);
        log.info("[" + storageType + "] doGetFile file version " + versionId
            + " content loaded, length " + content.length);
      }

      if (download && name.equals(emptyString)) {
        try {
          ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, ids.getFullId(), message);

          log.info("[" + storageType + "] doGetFile file info for download loaded, "
              + objectInfo.toJson());

          name = objectInfo.getName(false);
        } catch (Exception exception) {
          handleError(exception, segment, message);
        }
      }
      finishGetFile(message, start, end, content, storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
      handleError(exception, segment, message);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();

    String userId = body.getString(Field.USER_ID.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, body, storageType);
    }

    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());

    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);
      String name = objectInfo.getName(false);

      UploadVersionResult uploadVersionResult =
          graphApi.uploadVersion(fileId, stream, parsedMessage.getFileSizeFromJsonObject());
      String versionId = uploadVersionResult.getVersionId();

      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.FORCE.getName(), true)
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      }

      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FORCE.getName(), true)
              .put(Field.EMAIL.getName(), userEmail)
              .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));

      JsonArray versions = graphApi.getVersions(fileId).toJson(config);
      JsonObject version = versions.getJsonObject(versions.size() - 1);
      sendOK(segment, message, version, mills);
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject body = parsedMessage.getJsonObject();

    String name = body.getString(Field.NAME.getName());
    try {
      this.validateObjectName(name, GraphUtils.SPECIAL_CHARACTERS);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }
    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, body, storageType);
    }
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String baseChangeId = body.getString(Field.BASE_CHANGE_ID.getName());
    boolean doCopyComments = body.getBoolean(Field.COPY_COMMENTS.getName(), false);
    String cloneFileId = body.getString(Field.CLONE_FILE_ID.getName());
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());
    boolean isFileUpdate = Utils.isStringNotNullOrEmpty(fileId);
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      GraphId folderIds = new GraphId(folderId);
      GraphId fileIds = new GraphId(fileId);

      String responseName = null,
          changer = null,
          driveId =
              Utils.isStringNotNullOrEmpty(fileIds.getDriveId())
                  ? fileIds.getDriveId()
                  : folderIds.getDriveId();

      String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
      boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
      boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
      String versionId = "?";
      long uploadDate = -1L;

      if (parsedMessage.hasAnyContent() || fileId == null) {
        // if latest version in ex storage is unknown,
        // then save a new version as a file with a prefix beside the original file
        if (fileId != null) {
          ObjectInfo objectInfo = new ObjectInfo(true);

          try {
            objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);
            name = objectInfo.getName(false);
            versionId = objectInfo.getVersionId();
            changer = objectInfo.getChanger();
          } catch (GetObjectInfoException exception) {
            HttpResponse<String> response = exception.getResponse();
            JsonObject errorObject = new JsonObject(response.getBody());

            if (errorObject.containsKey(Field.ERROR.getName())
                && errorObject
                    .getJsonObject(Field.ERROR.getName())
                    .containsKey(Field.MESSAGE.getName())) {
              String errorMessage = errorObject
                  .getJsonObject(Field.ERROR.getName())
                  .getString(Field.MESSAGE.getName());
              if (errorMessage.equalsIgnoreCase("Access Denied")
                  || errorMessage.equalsIgnoreCase("Item does not exist")
                  || errorMessage.equalsIgnoreCase("ObjectHandle is Invalid")) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
                isConflictFile = true;
                fileNotFound = true;
              }
            }
            if (!fileNotFound) {
              sendError(segment, message, response);
              return;
            }
          }

          folderId = Field.MINUS_1.getName();
          // check if user still has the access to edit this file
          if (!Utils.isStringNotNullOrEmpty(conflictingFileReason) && !objectInfo.isOwner()) {
            Permissions permissions = null;
            try {
              permissions = graphApi.getPermissions(fileId);
            } catch (GetObjectPermissionsException ignore) {
              // item permissions not found
            }
            if (Objects.isNull(permissions) || permissions.isViewOnly(graphApi, storageType)) {
              if (config.getProperties().getNewSessionWorkflow()) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.NO_EDITING_RIGHTS.name();
                isConflictFile = true;
              } else {
                try {
                  throw new KudoFileException(
                      Utils.getLocalizedString(message, "noEditingRightsToEditThisFile"),
                      KudoErrorCodes.NO_EDITING_RIGHTS,
                      HttpStatus.BAD_REQUEST,
                      "noEditingRights");
                } catch (KudoFileException kfe) {
                  sendError(
                      segment,
                      message,
                      kfe.toResponseObject(),
                      kfe.getHttpStatus(),
                      kfe.getErrorId());
                  return;
                }
              }
            }
          }

          if (!fileNotFound) {
            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
              isConflictFile = isConflictingChange(
                  userId, fileIds.getId(), storageType, versionId, baseChangeId);
              if (isConflictFile) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
              }
            }
          }

          if (Utils.isStringNotNullOrEmpty(objectInfo.getParentReferenceId())) {
            if (Utils.isStringNotNullOrEmpty(objectInfo.getParentReferenceDriveId())) {
              folderId =
                  objectInfo.getParentReferenceDriveId() + ":" + objectInfo.getParentReferenceId();
            } else {
              folderId = objectInfo.getParentReferenceId();
            }
            if (!Utils.isStringNotNullOrEmpty(folderIds.getId())) {
              folderIds = new GraphId(folderId);
            }
          }
        }

        String oldName = name;
        String newName = name;
        boolean isSameFolder = true;

        UploadFileResult uploadFileResult;
        if (isConflictFile) {
          if (oldName == null) {
            Item metaData = FileMetaData.getMetaData(fileIds.getId(), storageType.name());
            if (Objects.nonNull(metaData)) {
              oldName = metaData.getString(Field.FILE_NAME.getName());
            }
            if (oldName == null) {
              oldName = unknownDrawingName;
            }
          }
          // create a new file and save it beside original one
          newName = getConflictingFileName(oldName);
          responseName = newName;

          Permissions folderPermissions = null;
          try {
            folderPermissions = graphApi.getPermissions(folderIds.getFullId());
          } catch (GetObjectPermissionsException ignore) {
            // folder permissions not found
          }

          driveId = graphApi.getMyDrive().getId();
          String folderName = ((GraphUtils.isRootFolder(folderIds.getId())
                      || Objects.isNull(folderPermissions)
                      || folderPermissions.isViewOnly(graphApi, storageType))
                  ? "/root"
                  : "/items/" + folderIds.getId())
              + ":/";
          if (folderName.startsWith("/root")) {
            isSameFolder = false;
            folderId = externalId + ":" + folderIds.getId();
          }

          // full folderId
          uploadFileResult = graphApi.uploadConflictingFile(
              folderId, folderName, newName, stream, parsedMessage.getFileSizeFromJsonObject());

          String newFileId;
          if (Utils.isStringNotNullOrEmpty(uploadFileResult.getDriveId())) {
            newFileId = uploadFileResult.getDriveId() + ":" + uploadFileResult.getFileId();
          } else {
            newFileId = uploadFileResult.getFileId();
          }

          handleConflictingFile(
              segment,
              message,
              body,
              oldName,
              newName,
              Utils.getEncapsulatedId(storageType, externalId, fileIds.getFullId()),
              Utils.getEncapsulatedId(storageType, externalId, newFileId),
              xSessionId,
              userId,
              changer,
              conflictingFileReason,
              fileSessionExpired,
              isSameFolder,
              AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
        } else {
          // full ids
          uploadFileResult = graphApi.uploadFile(
              folderId, fileId, newName, stream, parsedMessage.getFileSizeFromJsonObject());
        }

        fileId = uploadFileResult.getFileId();
        versionId = uploadFileResult.getVersionId();
        uploadDate = uploadFileResult.getUploadDate();
        if (driveId == null) {
          driveId = uploadFileResult.getDriveId();
        }
      }

      String objectId = new GraphId(driveId, fileId).getFullId();
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), objectId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }
      JsonObject json = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, objectId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              ThumbnailsManager.getThumbnailName(storageType, fileId, versionId));
      if (uploadDate != -1L) {
        json.put(Field.UPLOAD_DATE.getName(), uploadDate);
      }
      if (isConflictFile && Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
        json.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
      }
      if (isConflictFile && Utils.isStringNotNullOrEmpty(responseName)) {
        json.put(Field.NAME.getName(), responseName);
      }

      // for file save-as case
      if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
        String finalCloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
            .getString(Field.FILE_ID.getName());
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_COMMENTS_ON_SAVE_AS.getName())
            .run((Segment blockingSegment) -> {
              boolean doIncludeResolvedComments =
                  body.getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
              boolean doIncludeDeletedComments =
                  body.getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
              copyFileComments(
                  blockingSegment,
                  finalCloneFileId,
                  storageType,
                  objectId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }

      sendOK(segment, message, json, mills);

      if (isFileUpdate && parsedMessage.hasAnyContent()) {
        eb_send(
            segment,
            WebSocketManager.address + ".newVersion",
            new JsonObject()
                .put(Field.FILE_ID.getName(), Utils.urlencode(objectId))
                .put(Field.VERSION_ID.getName(), versionId)
                .put(Field.X_SESSION_ID.getName(), xSessionId)
                .put(Field.EMAIL.getName(), userEmail)
                .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
      }
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    final String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }

    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);
      JsonArray versions = graphApi.getVersions(id).toJson(config);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            ObjectInfo objectInfo;
            try {
              objectInfo = graphApi.getBaseObjectInfo(true, id, message);
              String name = objectInfo.getName(false);
              if (Utils.isStringNotNullOrEmpty(name)) {
                ext = Extensions.getExtension(name);
              }
              JsonObject jsonObject = message.body();
              jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
              JsonArray requiredVersions = new JsonArray();
              String finalExt = ext;
              versions.forEach(version -> requiredVersions.add(new JsonObject()
                  .put(Field.FILE_ID.getName(), objectInfo.getWsId())
                  .put(
                      Field.VERSION_ID.getName(),
                      ((JsonObject) version).getString(Field.ID.getName()))
                  .put(Field.EXT.getName(), finalExt)));
              eb_send(
                  blockingSegment,
                  ThumbnailsManager.address + ".create",
                  jsonObject.put(Field.IDS.getName(), requiredVersions));
            } catch (Exception ex) {
              log.warn("[ONEDRIVE] Get versions: Couldn't get object info to get extension.", ex);
            }
          });
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), versions), mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (id == null || versionId == null) {
      return;
    }

    try {
      PromoteVersionResult result = (isPersonal ? ODApi.connect(message) : ODBApi.connect(message))
          .promoteVersion(id, versionId);

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), result.getObjectId())
              .put(Field.VERSION_ID.getName(), result.getVersionId()),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }

    try {
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.VERSION_ID.getName(),
                  (isPersonal ? ODApi.connect(message) : ODBApi.connect(message))
                      .getLatestVersionId(id)),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doGetInfoByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String token = jsonObject.getString(Field.TOKEN.getName());
    Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    String creatorId = jsonObject.getString(Field.CREATOR_ID.getName());
    String userId = jsonObject.getString(Field.OWNER_ID.getName());

    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, storageType);
    }

    String driveId = jsonObject.getString(Field.DRIVE_ID.getName());
    if (fileId == null) {
      return;
    }

    try {
      log.info(
          "[" + storageType + "] start doGetInfoByToken. Params: fileId " + fileId + ", userId "
              + userId + ", externalId " + externalId + ", driveId " + driveId + ", token" + token);

      Item foreignUser = ExternalAccounts.getExternalAccount(userId, externalId);

      ObjectInfo fileInfoByToken = (isPersonal
              ? ODApi.connectByItem(foreignUser)
              : ODBApi.connectByItem(foreignUser))
          .getFileInfoByToken(driveId, fileId);

      log.info("[" + storageType + "] doGetInfoByToken info loaded. "
          + fileInfoByToken.toJson().toString());

      fileInfoByToken
          .withCreatorId(creatorId)
          .withIsPublic(true)
          .withViewOnly(true)
          .withPermissions(new ObjectPermissions().setAllTo(false))
          .withIsOwner(false)
          .withExport(export)
          .withLink(config.getProperties().getUrl() + "file/" + new GraphId(fileId).getId()
              + "?token=" + token)
          .withThumbnailName(ThumbnailsManager.getThumbnailName(
              storageType, fileId, fileInfoByToken.getVersionId()));

      log.info("[" + storageType + "] doGetInfoByToken info updated. "
          + fileInfoByToken.toJson().toString());

      sendOK(segment, message, fileInfoByToken.toJson(), mills);
    } catch (Exception exception) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      handleError(exception, segment, message);
    }
  }

  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();

    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
    }
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String downloadToken = jsonObject.getString(Field.DOWNLOAD_TOKEN.getName());

    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);
      if (returnDownloadUrl) {
        String downloadUrl = graphApi.getDownloadUrl(fileId, null);
        if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
          sendDownloadUrl(
              segment,
              message,
              downloadUrl,
              objectInfo.getSizeValue(),
              objectInfo.getVersionId(),
              (!isPersonal) ? graphApi.getAccessToken() : null,
              true,
              null,
              mills);
          return;
        }
      }

      byte[] content = graphApi.getFileContent(fileId);

      finishGetFile(
          message,
          null,
          null,
          content,
          storageType,
          objectInfo.getName(false),
          objectInfo.getVersionId(),
          downloadToken);
    } catch (Exception exception) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
      handleError(exception, segment, message);
    }

    recordExecutionTime("getFileByToken", System.currentTimeMillis() - mills);
  }

  public void doShare(Message<JsonObject> message) {
    doShare(message, true);
  }

  public void doShare(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String emailOrId = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    String role = getRequiredString(segment, Field.ROLE.getName(), message, message.body());
    if (id == null || emailOrId == null || role == null) {
      return;
    }
    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message))
          .shareObject(id, emailOrId, role);

      if (reply) {
        sendOK(segment, message, mills);
      }
    } catch (Exception exception) {
      if (reply) {
        handleError(exception, segment, message);
      }
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String permissionId = getRequiredString(segment, Field.NAME.getName(), message, message.body());

    JsonArray deShare = message.body().getJsonArray(Field.DE_SHARE.getName());
    JsonObject objectToRemove =
        (deShare != null && !deShare.isEmpty()) ? deShare.getJsonObject(0) : null;

    if (id == null
        || permissionId == null
        || (storageType.equals(StorageType.ONEDRIVEBUSINESS) && objectToRemove == null)) {
      return;
    }

    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);

      graphApi.removeObjectPermission(id, permissionId, objectToRemove);

      sendOK(
          segment,
          message,
          new JsonObject().put(Field.USERNAME.getName(), graphApi.getEmail()),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      id = message.body().getString(Field.FOLDER_ID.getName());
      if (id == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }

    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message)).restoreObject(id);

      sendOK(segment, message, mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      (isPersonal ? ODApi.connect(message) : ODBApi.connect(message)).checkFile(fileId);

      return true;
    } catch (Exception e) {
      return false;
    }
  }

  public void doGetInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, storageType);
    }
    boolean isFile = true;
    String id = jsonObject.getString(Field.FILE_ID.getName());
    if (id == null) {
      id = jsonObject.getString(Field.FOLDER_ID.getName());
      isFile = false;
    }
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    if (externalId == null) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
      return;
    }

    log.info("[" + storageType + "] start doGetInfo. Params: isFile " + isFile + ", fileId " + id
        + ", userId " + userId + ", externalId " + externalId);

    boolean full = true;
    if (jsonObject.containsKey(Field.FULL.getName())
        && jsonObject.getString(Field.FULL.getName()) != null) {
      full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
    }

    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      ObjectInfo objectInfo =
          graphApi.getObjectInfo(message, isFile, id, full, true, isAdmin, false, false);

      log.info(
          "[" + storageType + "] doGetInfo info loaded. " + objectInfo.toJson().toString());

      sendOK(segment, message, objectInfo.toJson(), mills);
    } catch (Exception exception) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      handleError(exception, segment, message);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject jsonObject = message.body();
      String id = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
      if (id == null) {
        return;
      }

      String userId = jsonObject.getString(Field.USER_ID.getName());
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      if (!Utils.isStringNotNullOrEmpty(externalId)) {
        externalId = findExternalId(segment, message, jsonObject, storageType);
      }

      log.info("[" + storageType + "] start doGetThumbnail. Params: fileId " + id + ", userId "
          + userId + ", externalId " + externalId);

      ObjectInfo objectInfo = (isPersonal
              ? ODApi.connectByUserId(userId, externalId)
              : ODBApi.connectByUserId(userId, externalId))
          .getBaseObjectInfo(true, id, message);

      log.info("[" + storageType + "] doGetThumbnail file info loaded, " + objectInfo.toJson());

      eb_send(
          segment,
          ThumbnailsManager.address + ".create",
          jsonObject
              .put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(
                  Field.IDS.getName(),
                  new JsonArray()
                      .add(new JsonObject()
                          .put(Field.FILE_ID.getName(), id)
                          .put(Field.VERSION_ID.getName(), objectInfo.getVersionId())
                          .put(
                              Field.EXT.getName(),
                              Extensions.getExtension(objectInfo.getName(false)))))
              .put(Field.FORCE.getName(), true));

      String thumbnailName =
          ThumbnailsManager.getThumbnailName(storageType, id, objectInfo.getVersionId());

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      id, storageType.name(), objectInfo.getVersionId(), true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = jsonObject.getString(Field.ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    boolean isTouch =
        AuthManager.ClientType.TOUCH.name().equals(jsonObject.getString(Field.DEVICE.getName()));
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);

      GraphId ids = new GraphId(id);

      String myFilesDriveId = null;
      try {
        myFilesDriveId = graphApi.getMyDrive().getId();
      } catch (Exception ignore) {
      }

      final String properExternalId =
          Utils.isStringNotNullOrEmpty(externalId) ? externalId : ids.getDriveId();
      boolean isRootMainFolder = GraphUtils.isRootMainFolder(ids.getId());

      JsonArray result = new JsonArray();

      String myFiles = Utils.getLocalizedString(message, Field.MY_FILES_FOLDER.getName());

      // Touch handles api different?
      if (isTouch) {
        if (!isRootMainFolder) {
          getPath(graphApi, ids, result, myFiles, properExternalId, myFilesDriveId);
          if (!result.isEmpty()
              && !"~"
                  .equals(
                      result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
            result.add(new JsonObject()
                .put(Field.NAME.getName(), "~")
                .put(Field.ENCAPSULATED_ID.getName(), Field.MINUS_1.getName())
                .put(Field.VIEW_ONLY.getName(), false));
          }
        } else {
          result.add(new JsonObject()
              .put(Field.NAME.getName(), "~")
              .put(Field.ENCAPSULATED_ID.getName(), Field.MINUS_1.getName())
              .put(Field.VIEW_ONLY.getName(), false));
        }
      }

      // regular requests
      else {
        // this is root main folder. with id = {OD|ODB}+{externalId}+-1
        if (isRootMainFolder) {
          result.add(new JsonObject()
              .put(Field.NAME.getName(), "~")
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(storageType, properExternalId, Field.MINUS_1.getName()))
              .put(Field.VIEW_ONLY.getName(), false));

          sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
          return;
        }

        if (GraphUtils.isSharedWithMeFolder(ids.getId())) {
          result.add(new JsonObject()
              .put(
                  Field.NAME.getName(),
                  Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName()))
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(storageType, properExternalId, SHARED_FOLDER))
              .put(Field.VIEW_ONLY.getName(), true));
        } else if (GraphUtils.isRootFakeFolder(ids.getId())) {
          result.add(new JsonObject()
              .put(Field.NAME.getName(), myFiles)
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType, properExternalId, GraphUtils.ROOT_FOLDER_FAKE_ID))
              .put(Field.VIEW_ONLY.getName(), false));
        } else if (Site.isSiteId(ids.getId())) {
          String rawId = Site.getRawIdFromFinal(ids.getId());
          if (Site.isCollectionId(rawId)) {
            result.add(new JsonObject()
                .put(Field.NAME.getName(), rawId)
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, properExternalId, ids.getId()))
                .put(Field.VIEW_ONLY.getName(), false));
          } else if (Site.isSpecificSiteId(rawId)) {
            result.add(new JsonObject()
                .put(Field.NAME.getName(), graphApi.getSiteInfo(rawId).getName())
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, properExternalId, ids.getId()))
                .put(Field.VIEW_ONLY.getName(), false));
            result.add(new JsonObject()
                .put(Field.NAME.getName(), Site.getCollectionId(rawId))
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType,
                        properExternalId,
                        Site.getFinalIdFromRaw(Site.getCollectionId(rawId))))
                .put(Field.VIEW_ONLY.getName(), false));
          }
        } else {
          getPath(graphApi, ids, result, myFiles, properExternalId, myFilesDriveId);
          // getFolderPath goes here and returns []
        }
        if (!GraphUtils.isSharedWithMeFolder(ids.getId())
            && !Site.isSiteId(ids.getId())
            && !result.isEmpty()
            && !myFiles.equals(
                result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
          result.add(new JsonObject()
              .put(
                  Field.NAME.getName(),
                  Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName()))
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(storageType, properExternalId, SHARED_FOLDER))
              .put(Field.VIEW_ONLY.getName(), true));
        }
        // add root folder if not there
        if (result.isEmpty()
            || !"~"
                .equals(result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
          result.add(new JsonObject()
              .put(Field.NAME.getName(), "~")
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(storageType, properExternalId, Field.MINUS_1.getName()))
              .put(Field.VIEW_ONLY.getName(), false));
        }
      }
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);

    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  private void getPath(
      GraphApi graphApi,
      GraphId ids,
      JsonArray result,
      String myFiles,
      String properExternalId,
      String myFilesExternalId)
      throws GraphException {
    ObjectInfo objectInfo = graphApi.getBaseObjectInfo(false, ids.getFullId(), null);

    String objDriveId = objectInfo.getParentReferenceDriveId();

    String name = objectInfo.getName(false);
    if (!name.equals(Field.ROOT.getName())) {
      result.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, properExternalId, ids.getFullId()))
          .put(Field.NAME.getName(), name)
          .put(Field.VIEW_ONLY.getName(), objectInfo.isViewOnly())
          .put(Field.SHARED.getName(), objectInfo.isShared()));

      String parent = objectInfo.getParentReferenceId();
      if (parent != null) {
        getPath(
            graphApi,
            new GraphId(ids.getDriveId(), parent),
            result,
            myFiles,
            properExternalId,
            myFilesExternalId);
      }
    } else if (Utils.isStringNotNullOrEmpty(myFilesExternalId)
        && myFilesExternalId.equals(objDriveId)) {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), myFiles)
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, properExternalId, ids.getDriveId() + ":root"))
          .put(Field.VIEW_ONLY.getName(), false));
    }
  }

  public void doEraseAll(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doEraseFolder(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doEraseFile(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doCreateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
    Boolean export = body.getBoolean(Field.EXPORT.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, body, storageType);
    }
    final long endTime =
        body.containsKey(Field.END_TIME.getName()) ? body.getLong(Field.END_TIME.getName()) : 0L;
    final String userId = body.getString(Field.USER_ID.getName());
    final String password = body.getString(Field.PASSWORD.getName());
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

    try {
      GraphApi graphApi = isPersonal
          ? ODApi.connectByUserId(userId, externalId)
          : ODBApi.connectByUserId(userId, externalId);

      ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);

      try {
        final boolean oldExport = getLinkExportStatus(objectInfo.getWsId(), externalId, userId);
        PublicLink newLink = super.initializePublicLink(
            objectInfo.getWsId(),
            externalId,
            userId,
            graphApi.getStorageType(),
            graphApi.getEmail(),
            objectInfo.getName(false),
            export,
            endTime,
            password);

        newLink.setCollaboratorsList(Collections.singletonList(objectInfo.getOwnerId()));
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

        String endUserLink = newLink.getEndUserLink();

        sendOK(
            segment,
            message,
            newLink.getInfoInJSON().put(Field.LINK.getName(), endUserLink),
            mills); // NON-NLS
        if (oldExport != newLink.getExport()) {
          eb_send(
              segment,
              WebSocketManager.address + ".exportStateUpdated",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), objectInfo.getWsId())
                  .put(Field.EXPORT.getName(), newLink.getExport())
                  .put(Field.TOKEN.getName(), newLink.getToken()));
        }
      } catch (PublicLinkException ple) {
        sendError(segment, message, ple.toResponseObject(), HttpStatus.BAD_REQUEST);
      }

    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doDeleteSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String fileId = body.getString(Field.FILE_ID.getName());
    final String externalId = body.getString(Field.EXTERNAL_ID.getName());
    final String userId = body.getString(Field.USER_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      String linkOwnerIdentity =
          super.deleteSharedLink(segment, message, fileId, externalId, userId);
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.LINK_OWNER_IDENTITY.getName(), linkOwnerIdentity),
          mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
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
    Set<String> fileNames = new HashSet<>();
    Set<String> folderNames = new HashSet<>();
    Set<String> filteredFileNames = new HashSet<>();
    List<Callable<Void>> callables = new ArrayList<>();
    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);
      downloads.stream().iterator().forEachRemaining(download -> {
        Entity subSegment = XRayManager.createSegment(operationGroup, message);
        JsonObject object = (JsonObject) download;
        if (!object.containsKey(Field.OBJECT_TYPE.getName())
            || !object.containsKey(Field.ID.getName())
            || !Utils.isStringNotNullOrEmpty(object.getString(Field.ID.getName()))) {
          return;
        }
        ObjectType type =
            ObjectType.valueOf(object.getString(Field.OBJECT_TYPE.getName()).toUpperCase());

        String objectId = Utils.parseItemId(
                object.getString(Field.ID.getName()), Field.OBJECT_ID.getName())
            .getString(Field.OBJECT_ID.getName());
        boolean isFolder = type.equals(ObjectType.FOLDER);
        callables.add(() -> {
          Entity blockingSegment =
              XRayManager.createSubSegment(operationGroup, subSegment, "MultipleDownloadSegment");
          ObjectInfo objectInfo =
              graphApi.getObjectInfo(!isFolder, objectId, false, false, false, message);
          GraphId ids = new GraphId(objectId);
          String name = objectInfo.getName(true);
          if (isFolder) {
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(stream, graphApi, ids, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = objectInfo.getSizeValue();
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, name, ExcludeReason.Large);
              return null;
            }
            addZipEntry(
                stream, graphApi, ids, name, filter, filteredFileNames, fileNames, emptyString);
          }
          XRayManager.endSegment(blockingSegment);
          return null;
        });
        XRayManager.endSegment(subSegment);
      });
      if (callables.isEmpty()) {
        log.warn("Nothing to download, please check the logs for multiple downloads for requestId "
            + requestId + " for storage - " + storageType);
        return;
      }
      finishDownloadZip(message, segment, s3Regional, stream, bos, callables, request);
    } catch (Exception exception) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      handleError(exception, segment, message);
    }
    sendOK(segment, message);
  }

  public void doRequestFolderZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    String requestId = body.getString(Field.REQUEST_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String requestFolderId = body.getString(Field.FOLDER_ID.getName());
    String filter = body.getString(Field.FILTER.getName());
    boolean recursive = body.getBoolean(Field.RECURSIVE.getName());
    log.info("[" + storageType + "] start doRequestFolderZip. requestFolderId " + requestFolderId
        + " request " + requestId);
    Item request = ZipRequests.getZipRequest(userId, requestFolderId, requestId);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    ZipOutputStream stream = new ZipOutputStream(bos);
    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);

      GraphId ids = new GraphId(requestFolderId);

      log.info("[" + storageType + "] doRequestFolderZip. driveId " + ids.getDriveId()
          + " folderId " + ids.getId());

      Future<Void> futureTask = executorService.submit(() -> {
        Entity subSegment = XRayManager.createStandaloneSegment(
            operationGroup, segment, "OneDriveZipFolderSegment");
        zipFolder(
            stream, graphApi, ids, filter, recursive, emptyString, new HashSet<>(), 0, request);
        XRayManager.endSegment(subSegment);
        return null;
      });
      sendOK(segment, message);
      finishDownloadZip(message, segment, s3Regional, stream, bos, futureTask, request);
    } catch (Exception exception) {
      ZipRequests.setRequestStatus(
          request,
          JobStatus.ERROR,
          "could not connect to " + storageType.name() + " , " + exception.getLocalizedMessage());
      handleError(exception, segment, message);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      GraphApi graphApi,
      GraphId ids,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    JsonArray value = graphApi.getFolderContent(ids);
    JsonObject obj;
    if (value.isEmpty()) { // add empty zip entry
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
    } else {
      for (Object item : value) {
        obj = (JsonObject) item;
        String name = obj.getString(Field.NAME.getName());
        String id;
        String properPath = path.isEmpty() ? path : path + File.separator;
        if (obj.containsKey(Field.FILE.getName())
            || (obj.containsKey(Field.REMOTE_ID.getName())
                && obj.getJsonObject(Field.REMOTE_ID.getName())
                    .containsKey(Field.FILE.getName()))) {
          long fileSize =
              obj.containsKey(Field.SIZE.getName()) ? obj.getInteger(Field.SIZE.getName()) : 0;
          if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
            excludeFileFromRequest(request, name, ExcludeReason.Large);
            return;
          }
          GraphId fileIds = new GraphId(ids.getDriveId(), obj.getString(Field.ID.getName()));
          addZipEntry(
              stream, graphApi, fileIds, name, filter, filteredFileNames, fileNames, properPath);
        } else if ((obj.containsKey(Field.FOLDER.getName())
                || (obj.containsKey(Field.REMOTE_ID.getName())
                    && obj.getJsonObject(Field.REMOTE_ID.getName())
                        .containsKey(Field.FOLDER.getName())))
            && recursive) {
          id = (obj.containsKey(Field.REMOTE_ID.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.PARENT_REFERENCE.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .getJsonObject(Field.PARENT_REFERENCE.getName())
                      .containsKey(Field.DRIVE_ID.getName())
              ? obj.getJsonObject(Field.REMOTE_ID.getName())
                      .getJsonObject(Field.PARENT_REFERENCE.getName())
                      .getString(Field.DRIVE_ID.getName())
                  + ":" + obj.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : obj.getString(Field.ID.getName()));
          name = Utils.checkAndRename(folderNames, name, true);
          folderNames.add(name);
          if (recursionDepth <= MAX_RECURSION_DEPTH) {
            recursionDepth += 1;
            zipFolder(
                stream,
                graphApi,
                new GraphId(ids.getDriveId(), id),
                filter,
                true,
                properPath + name,
                filteredFileNames,
                recursionDepth,
                request);
          } else {
            log.warn(
                "Zip folder recursion exceeds the limit for path " + path + " in " + storageType);
          }
        }
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      GraphApi graphApi,
      GraphId ids,
      String name,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames,
      String properPath)
      throws IOException {
    byte[] content;
    try {
      content = graphApi.getFileContent(new GraphId(ids.getDriveId(), ids.getId()).getFullId());
    } catch (Exception e) {
      return;
    }
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
      stream.write(content);
      stream.closeEntry();
      stream.flush();
    }
  }

  public void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String query = getRequiredString(segment, Field.QUERY.getName(), message, message.body());
    boolean isAdmin = message.body().getBoolean(Field.IS_ADMIN.getName());
    if (userId == null || query == null) {
      return;
    }
    try {
      Iterator<Item> accounts = ExternalAccounts.getExternalAccountsByUserId(userId, storageType);
      List<Item> array = StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(accounts, 0), false)
          .collect(Collectors.toList());

      JsonArray result = new JsonArray(array.parallelStream()
          .map(onedriveUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, onedriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()));

            try {
              JsonObject entries = (isPersonal
                      ? ODApi.connectByItem(onedriveUser)
                      : ODBApi.connectByItem(onedriveUser))
                  .globalSearch(query, isAdmin);

              XRayManager.endSegment(subSegment);
              return entries;
            } catch (Exception ignore) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(
                      Field.STORAGE_TYPE.getName(),
                      StorageType.getStorageType(onedriveUser.getString(Field.F_TYPE.getName())))
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      onedriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), onedriveUser.get(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), new JsonArray())
                  .put(Field.FOLDERS.getName(), new JsonArray());
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    final String[] folderId = {jsonObject.getString(Field.FOLDER_ID.getName())};
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());

    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);
      GraphId ids = new GraphId(fileId);

      String currentFolder = folderId[0];
      if (currentFolder == null) {
        ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);
        currentFolder = objectInfo.getParentReferenceId();
        String parentPath = objectInfo.getParentReferencePath();

        if (Utils.isStringNotNullOrEmpty(parentPath) && parentPath.endsWith("root:")) {
          currentFolder = Field.MINUS_1.getName();
        }
      }

      final String[] finalCurrentFolder = {currentFolder};
      List<String> pathList = path.getList();

      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            if (pathStr == null) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
            String[] array = pathStr.split("/");
            if (array.length == 0) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
            String filename = array[array.length - 1];
            try {
              // current folder
              if (array.length == 1) {
                ObjectInfo objectInfo =
                    graphApi.getBaseObjectInfo(false, finalCurrentFolder[0], message);

                // check that current user is an owner or an editor
                boolean available = objectInfo.isOwner() || objectInfo.canWrite();
                // check for existing file
                if (available) {
                  try {
                    JsonArray value =
                        graphApi.getFolderContentArray(ids.getDriveId(), finalCurrentFolder[0]);
                    for (Object item : value) {
                      // todo maybe also check if it's a file
                      available =
                          !((JsonObject) item).getString(Field.NAME.getName()).equals(filename);
                      if (!available) {
                        break;
                      }
                    }
                  } catch (GraphException exception) {
                    log.debug(
                        "Onedrive Response Failed : " + exception.getResponse().getBody());
                    XRayManager.endSegment(subSegment);
                    return new JsonObject()
                        .put(Field.PATH.getName(), pathStr)
                        .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                  }
                }
                JsonArray folders = new JsonArray();
                if (available) {
                  folders.add(new JsonObject().put(Field.ID.getName(), finalCurrentFolder[0]));
                }
                XRayManager.endSegment(subSegment);
                return new JsonObject()
                    .put(Field.PATH.getName(), pathStr)
                    .put(
                        Field.STATE.getName(),
                        available ? Field.AVAILABLE.getName() : Field.UNAVAILABLE.getName())
                    .put(Field.FOLDERS.getName(), folders);
              }

              Map<String, ObjectInfo> foldersCache = new HashMap<>();
              Set<String> possibleFolders = new HashSet<>();
              possibleFolders.add(finalCurrentFolder[0]);
              int lastStep = -1;
              for (int i = 0; i < array.length - 1; i++) {
                if (array[i].isEmpty()) {
                  continue; // todo ?
                }
                Iterator<String> it = possibleFolders.iterator();
                Set<String> adds = new HashSet<>(), dels = new HashSet<>();
                while (it.hasNext()) {
                  adds.clear();
                  dels.clear();
                  String possibleFolderId = it.next();
                  if ("..".equals(array[i])) {
                    if (GraphUtils.isRootFolder(possibleFolderId)) {
                      continue;
                    }
                    try {
                      ObjectInfo objectInfo =
                          graphApi.getBaseObjectInfo(true, possibleFolderId, message);

                      String parentId = objectInfo.getParentReferenceId();
                      String parentPath = objectInfo.getParentReferencePath();

                      if (Utils.isStringNotNullOrEmpty(parentPath)
                          && parentPath.endsWith("root:")) {
                        parentId = Field.MINUS_1.getName();
                      }

                      adds.add(parentId);
                      dels.add(possibleFolderId);
                    } catch (GraphException exception) {
                      handleError(exception, segment, message);
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    }
                  } else {
                    try {
                      JsonArray value = graphApi.getFolderContentArray(null, possibleFolderId);
                      for (Object item : value) {
                        JsonObject obj = (JsonObject) item;
                        if (obj.getString(Field.NAME.getName()).equals(array[i])) {
                          // todo maybe check if it's a folder
                          foldersCache.put(
                              obj.getString(Field.ID.getName()),
                              GraphUtils.getFileJson(
                                  config,
                                  graphApi,
                                  obj,
                                  false,
                                  false,
                                  externalId,
                                  null,
                                  false,
                                  storageType,
                                  false,
                                  null,
                                  message));
                          adds.add(obj.getString(Field.ID.getName()));
                        }
                      }
                    } catch (GraphException exception) {
                      log.debug("Onedrive Response Failed : "
                          + exception.getResponse().getBody().toString());
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
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
              // check if it's possible to either upload a file (if lastStep == -1) or create
              // a path starting from array[i] in possible folders
              JsonArray folders = new JsonArray();
              boolean possible = lastStep != -1;
              for (String possibleFolderId : possibleFolders) {
                // check that current user is an owner or an editor
                try {
                  ObjectInfo possibleFolder = foldersCache.get(possibleFolderId);
                  if (possibleFolder == null) {
                    possibleFolder = graphApi.getBaseObjectInfo(true, possibleFolderId, message);
                  }
                  boolean isOwner = possibleFolder.isOwner();
                  boolean available = GraphUtils.isRootFolder(possibleFolderId);
                  if (!available) {
                    available = isOwner || possibleFolder.canWrite();
                  }
                  if (available) {
                    if (lastStep == -1) {
                      boolean add = true;
                      try {
                        JsonArray value =
                            graphApi.getFolderContentArray(ids.getDriveId(), possibleFolderId);
                        for (Object item : value) {
                          // todo maybe also check if it's a file
                          add = !((JsonObject) item)
                              .getString(Field.NAME.getName())
                              .equals(filename);
                          if (!add) {
                            break;
                          }
                        }
                      } catch (Exception ignore) {
                        add = false;
                      }
                      if (add) {
                        folders.add(new JsonObject()
                            .put(Field.ID.getName(), possibleFolderId)
                            .put(Field.OWNER.getName(), possibleFolder.getOwner())
                            .put(Field.IS_OWNER.getName(), isOwner)
                            .put(Field.CAN_MOVE.getName(), isOwner)
                            .put(Field.UPDATE_DATE.getName(), possibleFolder.getUpdateDate())
                            .put(Field.CHANGER.getName(), possibleFolder.getChanger())
                            .put(Field.CHANGER_ID.getName(), possibleFolder.getChangerId())
                            .put(Field.NAME.getName(), possibleFolder.getName(false))
                            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                      }
                    } else {
                      folders.add(new JsonObject()
                          .put(Field.ID.getName(), possibleFolderId)
                          .put(Field.OWNER.getName(), possibleFolder.getOwner())
                          .put(Field.IS_OWNER.getName(), possibleFolder.isOwner())
                          .put(Field.CAN_MOVE.getName(), possibleFolder.isCanMove())
                          .put(Field.UPDATE_DATE.getName(), possibleFolder.getUpdateDate())
                          .put(Field.CHANGER.getName(), possibleFolder.getChanger())
                          .put(Field.CHANGER_ID.getName(), possibleFolder.getChangerId())
                          .put(Field.NAME.getName(), possibleFolder.getName(false))
                          .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                    }
                  }
                } catch (Exception exception) {
                  log.error(exception);
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
            } catch (Exception e) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName())
                  .put(Field.FOLDERS.getName(), new JsonArray());
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    final String[] requestFolderId = {jsonObject.getString(Field.FOLDER_ID.getName())};
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
    }
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "MustBeSpecified"), Field.EXTERNAL_ID.getName()),
          HttpStatus.BAD_REQUEST);
      return;
    }
    final String finalExternalId = externalId;
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());

    try {
      GraphApi graphApi = isPersonal ? ODApi.connect(message) : ODBApi.connect(message);
      GraphId ids = new GraphId(fileId);

      String currentFolderId = requestFolderId[0];
      if (currentFolderId == null) {
        ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileId, message);
        String parentReferencePath = objectInfo.getParentReferencePath();
        if (parentReferencePath != null && parentReferencePath.endsWith("root:")) {
          currentFolderId = ids.getDriveId() + ":root";
        } else {
          String parentReferenceId = objectInfo.getParentReferenceId();
          currentFolderId = parentReferenceId != null ? parentReferenceId : SHARED_FOLDER;
        }

        if (objectInfo.getParentReferenceId() == null) {
          currentFolderId = ids.getDriveId() + ":root";
        }
      }

      String finalDriveId = ids.getDriveId();
      String finalCurrentFolderId = currentFolderId;

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

            JsonArray pathFiles = new JsonArray();

            try {
              String[] array = Utils.parseRelativePath(pathStr);
              String filename = array[array.length - 1];
              Set<String> possibleFolders = new HashSet<>();
              possibleFolders.add(finalCurrentFolderId);
              Map<String, JsonArray> cache = new HashMap<>();
              if (!(array.length == 1 || (array.length == 2 && array[0].trim().isEmpty()))) {
                for (int i = 0; i < array.length - 1; i++) {

                  if (array[i].isEmpty()) {
                    continue;
                  }

                  if (array[i].equals("Shared with me")) {
                    findInSharedFolder(
                        graphApi,
                        finalDriveId,
                        pathFiles,
                        filename,
                        Arrays.copyOfRange(array, i, array.length),
                        cache,
                        finalExternalId);
                    if (!pathFiles.isEmpty()) {
                      break;
                    }
                  }

                  if (array[i].equals("My files")) {
                    continue;
                  }
                  Iterator<String> it = possibleFolders.iterator();
                  Set<String> adds = new HashSet<>(), dels = new HashSet<>();
                  while (it.hasNext()) {
                    String folderId = it.next();
                    dels.add(folderId);

                    if ("..".equals(array[i])) {
                      goUp(graphApi, finalDriveId, folderId, adds);
                    } else {
                      goDown(graphApi, finalDriveId, folderId, array[i], cache, adds);
                    }
                  }
                  possibleFolders.removeAll(dels);
                  possibleFolders.addAll(adds);
                }
              }
              // check in all possible folders
              if (pathFiles.isEmpty()) {
                for (String folderId : possibleFolders) {
                  if (Utils.isStringNotNullOrEmpty(folderId)) {
                    findFileInFolder(
                        graphApi,
                        pathFiles,
                        filename,
                        folderId,
                        cache,
                        finalDriveId,
                        finalExternalId);
                  }
                }
              }
              // check in the root if not found
              if (pathFiles.isEmpty() && !possibleFolders.contains(finalCurrentFolderId)) {
                if (Utils.isStringNotNullOrEmpty(finalCurrentFolderId)) {
                  findFileInFolder(
                      graphApi,
                      pathFiles,
                      filename,
                      finalCurrentFolderId,
                      cache,
                      finalDriveId,
                      finalExternalId);
                }
              }
            } catch (Exception ignore) {
            }

            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.PATH.getName(), pathStr)
                .put(Field.FILES.getName(), pathFiles);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }

  private void findInSharedFolder(
      GraphApi graphApi,
      String driveId,
      JsonArray pathFiles,
      String filename,
      String[] array,
      Map<String, JsonArray> cache,
      String externalId)
      throws GraphException {
    JsonArray value = graphApi.getFolderContentArray(null, Field.SHARED.getName());

    for (int i = 0; i < array.length - 1; i++) {
      // if not last folder from path
      if (i != array.length - 2) {
        for (Object v : value) {
          JsonObject object = (JsonObject) v;

          boolean isFolder = object.containsKey(Field.FOLDER.getName())
              || (object.containsKey(Field.REMOTE_ID.getName())
                  && object
                      .getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FOLDER.getName()));

          if (isFolder && object.getString(Field.NAME.getName()).equals(array[i + 1])) {
            JsonObject remoteItem = object.containsKey(Field.REMOTE_ID.getName())
                ? object.getJsonObject(Field.REMOTE_ID.getName())
                : null;
            String folderId = remoteItem != null
                ? remoteItem.getString(Field.ID.getName())
                : object.getString(Field.ID.getName());

            String localDriveId = null;
            String[] s = folderId.split("!");
            if (s.length == 2) {
              localDriveId = s[0];
            }

            // try to parse driveId
            if (remoteItem != null) {
              localDriveId = (remoteItem.containsKey(Field.PARENT_REFERENCE.getName())
                      && remoteItem
                          .getJsonObject(Field.PARENT_REFERENCE.getName())
                          .containsKey(Field.DRIVE_ID.getName()))
                  ? remoteItem
                      .getJsonObject(Field.PARENT_REFERENCE.getName())
                      .getString(Field.DRIVE_ID.getName())
                  : localDriveId;
            } else {
              localDriveId = (object.containsKey(Field.PARENT_REFERENCE.getName())
                      && object
                          .getJsonObject(Field.PARENT_REFERENCE.getName())
                          .containsKey(Field.DRIVE_ID.getName()))
                  ? object
                      .getJsonObject(Field.PARENT_REFERENCE.getName())
                      .getString(Field.DRIVE_ID.getName())
                  : localDriveId;
            }
            if (localDriveId == null) {
              localDriveId = driveId;
            }

            value = graphApi.getFolderContentArray(localDriveId, folderId);
            cache.put(folderId, value);
          }
        }
      }
      // file should be in current folder
      else {
        for (Object v : value) {
          JsonObject object = (JsonObject) v;

          boolean isFile = object.containsKey(Field.FILE.getName())
              || (object.containsKey(Field.REMOTE_ID.getName())
                  && object
                      .getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FILE.getName()));

          if (isFile && object.getString(Field.NAME.getName()).equals(array[i + 1])) {
            long updateDate = 0;
            String changer = emptyString, changerId = emptyString;
            boolean isOwner;
            String parentExternalId = null;
            String objectId = null;
            try {
              updateDate = DatatypeConverter.parseDateTime /* sdf.parse */(
                      object.getString(Field.LAST_MODIFIED_DATE_TIME.getName()))
                  .getTime()
                  .getTime();
              changer = (object.containsKey(Field.REMOTE_ID.getName())
                      ? object.getJsonObject(Field.REMOTE_ID.getName())
                      : object)
                  .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                  .getJsonObject(Field.USER.getName())
                  .getString(Field.DISPLAY_NAME.getName());
              changerId = (object.containsKey(Field.REMOTE_ID.getName())
                      ? object.getJsonObject(Field.REMOTE_ID.getName())
                      : object)
                  .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                  .getJsonObject(Field.USER.getName())
                  .getString(Field.ID.getName());

              objectId = object.containsKey(Field.REMOTE_ID.getName())
                  ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
                  : object.getString(Field.ID.getName());

              // try to parse externalId from object's parent reference
              if (object.containsKey(Field.PARENT_REFERENCE.getName())) {
                parentExternalId = object
                    .getJsonObject(Field.PARENT_REFERENCE.getName())
                    .getString(Field.DRIVE_ID.getName());
              }

              // try to parse externalId from remoteItem's parent reference
              if (object.containsKey(Field.REMOTE_ID.getName())
                  && object
                      .getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.PARENT_REFERENCE.getName())) {
                parentExternalId = object
                    .getJsonObject(Field.REMOTE_ID.getName())
                    .getJsonObject(Field.PARENT_REFERENCE.getName())
                    .getString(Field.DRIVE_ID.getName());
              }

              isOwner = (object.containsKey(Field.CREATED_BY.getName())
                      ? object
                          .getJsonObject(Field.CREATED_BY.getName())
                          .getJsonObject(Field.USER.getName())
                          .getString(Field.ID.getName())
                      : object.containsKey(Field.REMOTE_ID.getName())
                              && object
                                  .getJsonObject(Field.REMOTE_ID.getName())
                                  .containsKey(Field.CREATED_BY.getName())
                          ? object
                              .getJsonObject(Field.REMOTE_ID.getName())
                              .getJsonObject(Field.CREATED_BY.getName())
                              .getJsonObject(Field.USER.getName())
                              .getString(Field.ID.getName())
                          : emptyString)
                  .equals(externalId);
            } catch (Exception ignore) {
              isOwner = false;
            }

            String fileId = Utils.isStringNotNullOrEmpty(parentExternalId)
                ? (parentExternalId + ":" + objectId)
                : objectId;

            pathFiles.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, externalId, fileId))
                .put(
                    Field.OWNER.getName(),
                    object.containsKey(Field.CREATED_BY.getName())
                        ? object
                            .getJsonObject(Field.CREATED_BY.getName())
                            .getJsonObject(Field.USER.getName())
                            .getString(Field.DISPLAY_NAME.getName())
                        : object.containsKey(Field.REMOTE_ID.getName())
                                && object
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .containsKey(Field.CREATED_BY.getName())
                            ? object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .getJsonObject(Field.CREATED_BY.getName())
                                .getJsonObject(Field.USER.getName())
                                .getString(Field.DISPLAY_NAME.getName())
                            : emptyString)
                .put(Field.IS_OWNER.getName(), isOwner)
                .put(Field.UPDATE_DATE.getName(), updateDate)
                .put(Field.CHANGER.getName(), changer)
                .put(Field.CHANGER_ID.getName(), changerId)
                .put(
                    Field.SIZE.getName(),
                    Utils.humanReadableByteCount(
                        object.containsKey(Field.SIZE.getName())
                            ? object.getInteger(Field.SIZE.getName())
                            : object.containsKey(Field.REMOTE_ID.getName())
                                    && object
                                        .getJsonObject(Field.REMOTE_ID.getName())
                                        .containsKey(Field.SIZE.getName())
                                ? object
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .getInteger(Field.SIZE.getName())
                                : 0))
                .put(
                    Field.SIZE_VALUE.getName(),
                    object.containsKey(Field.SIZE.getName())
                        ? object.getInteger(Field.SIZE.getName())
                        : object.containsKey(Field.REMOTE_ID.getName())
                                && object
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .containsKey(Field.SIZE.getName())
                            ? object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .getInteger(Field.SIZE.getName())
                            : 0)
                .put(Field.NAME.getName(), filename)
                .put(Field.STORAGE_TYPE.getName(), storageType.name()));
          }
        }
      }
    }
  }

  private void goDown(
      GraphApi graphApi,
      String driveId,
      String folderId,
      String name,
      Map<String, JsonArray> cache,
      Set<String> adds)
      throws GraphException {
    try {
      JsonArray value = cache.get(folderId);
      if (value == null) {
        value = graphApi.getFolderContentArray(driveId, folderId);
        cache.put(folderId, value);
      }
      List<String> remoteIds = new ArrayList<>();
      JsonObject object;
      for (Object item : value) {
        object = (JsonObject) item;
        String remoteId = object.containsKey(Field.REMOTE_ID.getName())
            ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
            : emptyString;
        if (remoteIds.contains(remoteId)) {
          continue;
        }
        if (!remoteId.isEmpty()) {
          remoteIds.add(remoteId);
        }
        if (object.containsKey(Field.FOLDER.getName())
            && object.getString(Field.NAME.getName()).equals(name)) {
          String id = object.containsKey(Field.REMOTE_ID.getName())
              ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : object.getString(Field.ID.getName());
          adds.add(id);
        }
      }
    } catch (GraphException exception) {
      log.error("Error on going down in oneDrive", exception);
      throw exception;
    }
  }

  private void goUp(GraphApi graphApi, String driveId, String folderId, Set<String> adds)
      throws GraphException {
    try {
      GraphId ids = new GraphId(driveId, folderId);
      // its a drive
      if (!ids.getId().contains("!")) {
        adds.add(Field.MINUS_1.getName());
        return;
      }
      String parent =
          graphApi.getBaseObjectInfo(false, ids.getFullId(), null).getParentReferenceId();
      adds.add(parent);
    } catch (GraphException exception) {
      log.error("Error on going up in oneDrive", exception);
      throw exception;
    }
  }

  private void findFileInFolder(
      GraphApi graphApi,
      JsonArray pathFiles,
      String filename,
      String folderId,
      Map<String, JsonArray> cache,
      String driveId,
      String externalId) {
    JsonArray value = cache.get(folderId);
    if (value == null) {
      try {
        value = graphApi.getFolderContentArray(driveId, folderId);
      } catch (GraphException graphException) {
        // https://graebert.atlassian.net/browse/WB-1549
        // DK: I think we would be better off just ignoring folder which has failed
        log.info(
            "[" + storageType
                + "] [XREF] [findFileInFolder]. Get folder content has failed. DriveID: "
                + driveId + " FolderID: "
                + folderId + " Response code: " + graphException.getResponse().getStatus()
                + " Response body: " + graphException.getResponse().getBody().toString(),
            graphException);
        return;
      }
      cache.put(folderId, value);
    }

    List<String> remoteIds = new ArrayList<>();
    JsonObject object;
    for (Object item : value) {
      object = (JsonObject) item;
      String remoteId = object.containsKey(Field.REMOTE_ID.getName())
          ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
          : emptyString;
      if (remoteIds.contains(remoteId)) {
        continue;
      }
      if (!remoteId.isEmpty()) {
        remoteIds.add(remoteId);
      }

      boolean isFile = object.containsKey(Field.FILE.getName())
          || (object.containsKey(Field.REMOTE_ID.getName())
              && object.getJsonObject(Field.REMOTE_ID.getName()).containsKey(Field.FILE.getName()));

      if (isFile && object.getString(Field.NAME.getName()).equals(filename)) {
        long updateDate = 0;
        String changer = emptyString, changerId = emptyString;
        try {
          updateDate = DatatypeConverter.parseDateTime /* sdf.parse */(
                  object.getString(Field.LAST_MODIFIED_DATE_TIME.getName()))
              .getTime()
              .getTime();
          changer = (object.containsKey(Field.REMOTE_ID.getName())
                  ? object.getJsonObject(Field.REMOTE_ID.getName())
                  : object)
              .getJsonObject(Field.LAST_MODIFIED_BY.getName())
              .getJsonObject(Field.USER.getName())
              .getString(Field.DISPLAY_NAME.getName());

          changerId = (object.containsKey(Field.REMOTE_ID.getName())
                  ? object.getJsonObject(Field.REMOTE_ID.getName())
                  : object)
              .getJsonObject(Field.LAST_MODIFIED_BY.getName())
              .getJsonObject(Field.USER.getName())
              .getString(Field.ID.getName());
        } catch (Exception ignore) {
        }
        String parentExternalId = null;
        // try to parse externalId from object's parent reference
        if (object.containsKey(Field.PARENT_REFERENCE.getName())) {
          parentExternalId = object
              .getJsonObject(Field.PARENT_REFERENCE.getName())
              .getString(Field.DRIVE_ID.getName());
        }
        // try to parse externalId from remoteItem's parent reference
        if (!Utils.isStringNotNullOrEmpty(parentExternalId)
            && object.containsKey(Field.REMOTE_ID.getName())
            && object
                .getJsonObject(Field.REMOTE_ID.getName())
                .containsKey(Field.PARENT_REFERENCE.getName())) {
          parentExternalId = object
              .getJsonObject(Field.REMOTE_ID.getName())
              .getJsonObject(Field.PARENT_REFERENCE.getName())
              .getString(Field.DRIVE_ID.getName());
        }
        String objectId = object.getString(Field.ID.getName());
        String fileId = Utils.isStringNotNullOrEmpty(parentExternalId)
            ? (parentExternalId + ":" + objectId)
            : objectId;

        pathFiles.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, fileId))
            .put(
                Field.OWNER.getName(),
                object.containsKey(Field.CREATED_BY.getName())
                    ? object
                        .getJsonObject(Field.CREATED_BY.getName())
                        .getJsonObject(Field.USER.getName())
                        .getString(Field.DISPLAY_NAME.getName())
                    : object.containsKey(Field.REMOTE_ID.getName())
                            && object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .containsKey(Field.CREATED_BY.getName())
                        ? object
                            .getJsonObject(Field.REMOTE_ID.getName())
                            .getJsonObject(Field.CREATED_BY.getName())
                            .getJsonObject(Field.USER.getName())
                            .getString(Field.DISPLAY_NAME.getName())
                        : emptyString)
            .put(
                Field.IS_OWNER.getName(),
                (object.containsKey(Field.CREATED_BY.getName())
                        ? object
                            .getJsonObject(Field.CREATED_BY.getName())
                            .getJsonObject(Field.USER.getName())
                            .getString(Field.ID.getName())
                        : object.containsKey(Field.REMOTE_ID.getName())
                                && object
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .containsKey(Field.CREATED_BY.getName())
                            ? object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .getJsonObject(Field.CREATED_BY.getName())
                                .getJsonObject(Field.USER.getName())
                                .getString(Field.ID.getName())
                            : emptyString)
                    .equals(externalId))
            .put(Field.UPDATE_DATE.getName(), updateDate)
            .put(Field.CHANGER.getName(), changer)
            .put(Field.CHANGER_ID.getName(), changerId)
            .put(
                Field.SIZE.getName(),
                Utils.humanReadableByteCount(
                    object.containsKey(Field.SIZE.getName())
                        ? object.getInteger(Field.SIZE.getName())
                        : object.containsKey(Field.REMOTE_ID.getName())
                                && object
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .containsKey(Field.SIZE.getName())
                            ? object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .getInteger(Field.SIZE.getName())
                            : 0))
            .put(
                Field.SIZE_VALUE.getName(),
                object.containsKey(Field.SIZE.getName())
                    ? object.getInteger(Field.SIZE.getName())
                    : object.containsKey(Field.REMOTE_ID.getName())
                            && object
                                .getJsonObject(Field.REMOTE_ID.getName())
                                .containsKey(Field.SIZE.getName())
                        ? object
                            .getJsonObject(Field.REMOTE_ID.getName())
                            .getInteger(Field.SIZE.getName())
                        : 0)
            .put(Field.NAME.getName(), filename)
            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }
    }
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    try {
      JsonObject jsonObject = message.body();
      String userId = jsonObject.getString(Field.USER_ID.getName());
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      if (!Utils.isStringNotNullOrEmpty(externalId)) {
        externalId = findExternalId(segment, message, jsonObject, storageType);
      }
      String id = jsonObject.getString(Field.FILE_ID.getName());

      log.info("[" + storageType + "] start doGetTrashStatus. Params: objectId " + id + ", userId "
          + userId + ", externalId " + externalId);

      if (id == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      (isPersonal
              ? ODApi.connectByUserId(userId, externalId)
              : ODBApi.connectByUserId(userId, externalId))
          .getBaseObjectInfo(true, id, message);

      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), false), mills);
    } catch (Exception exception) {
      handleError(exception, segment, message);
    }
  }
}
