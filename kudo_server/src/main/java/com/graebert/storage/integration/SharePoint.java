package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.CollaboratorInfo;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onedrive.Drive;
import com.graebert.storage.integration.onedrive.Site;
import com.graebert.storage.integration.onedrive.graphApi.GraphApi;
import com.graebert.storage.integration.onedrive.graphApi.SPApi;
import com.graebert.storage.integration.onedrive.graphApi.entity.CloneObjectResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.CreateFolderResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.GraphId;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.PromoteVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadFileResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.integration.onedrive.graphApi.exception.UnauthorizedException;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import kong.unirest.UnirestException;
import org.jetbrains.annotations.NonNls;

public class SharePoint extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.SHAREPOINT;
  public static final String address = "sharepoint";
  private final StorageType storageType = StorageType.SHAREPOINT;
  private S3Regional s3Regional = null;
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };

  public SharePoint() {}

  @Override
  public void start() throws Exception {
    super.start();

    SPApi.Initialize(
        "/me",
        config.getProperties().getOnedriveBusinessClientId(),
        config.getProperties().getOnedriveBusinessClientSecret(),
        config.getProperties().getOnedriveBusinessRedirectUri(),
        config.getProperties().getFluorineSecretKey(),
        config);

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

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-sharepoint");
  }

  @Override
  public void doGetVersionByToken(Message<JsonObject> message) {
    final Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    final JsonObject jsonObject = message.body();
    final KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    final String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    final String lt = jsonObject.getString(Field.LINK_TYPE.getName());

    try {
      GraphApi graphApi = SPApi.connectByUserId(userId, fileIds.getExternalId());

      ObjectInfo objectInfo = graphApi.getBaseObjectInfo(true, fileIds.getId(), message);

      message.body().put(Field.NAME.getName(), objectInfo.getName(true));

      String realVersionId = VersionType.parse(versionId).equals(VersionType.LATEST)
          ? objectInfo.getVersionId()
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
      GraphApi graphApi = SPApi.connectByUserId(userId, fileIds.getExternalId());

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
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
      SPApi spApi = SPApi.connectAccount(userId, code, sessionId);

      storageLog(
          segment,
          message,
          userId,
          graebertId,
          sessionId,
          username,
          storageType.toString(),
          spApi.getExternalId(),
          true,
          intercomAccessToken);

      sendOK(segment, message, mills);
    } catch (UnauthorizedException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    if (trash) {
      sendError(segment, message, emptyString, HttpStatus.NOT_IMPLEMENTED);
      return;
    }
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String nextLink = jsonObject.getString(Field.PAGE_TOKEN.getName());
    String p = jsonObject.getString(Field.PAGINATION.getName());
    boolean pagination = p == null || Boolean.parseBoolean(p);
    boolean isTouch = AuthManager.ClientType.TOUCH
        .name()
        .equals(jsonObject.getString(Field.DEVICE.getName(), "default"));
    boolean useNewStructure = jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
    }
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
    boolean force = jsonObject.containsKey(Field.FORCE.getName())
        && jsonObject.getBoolean(Field.FORCE.getName());
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
      boolean canCreateThumbnails = canCreateThumbnails(jsonObject) && isBrowser;
      SPApi spApi = SPApi.connectByUserId(userId, externalId);
      GraphId folderIds = new GraphId(folderId);

      // root folder
      if (folderIds.getId().equals(Field.MINUS_1.getName())) {
        List<Site> searchedSites = new ArrayList<>();
        try {
          searchedSites = spApi.getAllSites();
        } catch (Exception e) {
          log.error(e);
        }
        String hostname = spApi.getSiteUrl();
        JsonArray sites = new JsonArray(searchedSites.stream()
            .parallel()
            // verify hostname
            .filter(site -> Utils.isStringNotNullOrEmpty(hostname)
                && Utils.isStringNotNullOrEmpty(site.getSiteCollectionHostname())
                && site.getSiteCollectionHostname().equals(hostname))
            // map to json
            .map((Function<Site, Object>) Site::toJson)
            // sort
            .sorted(Comparator.comparing(
                o -> ((JsonObject) o).getString(Field.NAME.getName()).toLowerCase()))
            .collect(Collectors.toList()));

        JsonObject result = new JsonObject()
            .put(
                Field.RESULTS.getName(),
                new JsonObject()
                    .put(Field.FILES.getName(), new JsonArray())
                    .put(Field.FOLDERS.getName(), sites))
            .put("number", sites.size())
            .put(Field.PAGE_TOKEN.getName(), nextLink)
            .put(Field.FULL.getName(), full)
            .put(Field.FILE_FILTER.getName(), jsonObject.getString(Field.FILE_FILTER.getName()));
        sendOK(segment, message, result, mills);
      }

      // not root folder
      else {
        // site id
        if (Site.isSiteId(folderIds.getId())) {
          try {
            String siteId = Site.getRawIdFromFinal(folderIds.getId());
            List<JsonObject> drives = spApi.getSiteDrives(siteId).stream()
                .parallel()
                .map(Drive::toJson)
                .sorted(
                    Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()))
                .collect(Collectors.toList());

            JsonObject result = new JsonObject()
                .put(
                    Field.RESULTS.getName(),
                    new JsonObject()
                        .put(Field.FILES.getName(), new JsonArray())
                        .put(Field.FOLDERS.getName(), new JsonArray(drives)))
                .put("number", drives.size())
                .put(Field.PAGE_TOKEN.getName(), nextLink)
                .put(Field.FULL.getName(), full)
                .put(
                    Field.FILE_FILTER.getName(),
                    message.body().getString(Field.FILE_FILTER.getName()));
            sendOK(segment, message, result, mills);
          } catch (GraphException exception) {
            sendError(
                segment,
                message,
                exception.getResponse(),
                exception.getResponse().getStatus() != HttpStatus.NOT_FOUND
                    ? exception.getResponse().getStatus()
                    : HttpStatus.BAD_REQUEST);
          }
        }
        // regular drive
        else {
          try {
            JsonArray value = spApi.getFolderContentArray(
                folderId, nextLink, false, isTouch, pagination, useNewStructure);

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
                  boolean createThumbnail = canCreateThumbnails;
                  String driveIdForSharedLink = folderIds.getDriveId();
                  if (driveIdForSharedLink == null) {
                    if (obj.containsKey(Field.PARENT_REFERENCE.getName())) {
                      if (obj.getJsonObject(Field.PARENT_REFERENCE.getName())
                          .containsKey(Field.DRIVE_ID.getName())) {
                        driveIdForSharedLink = obj.getJsonObject(Field.PARENT_REFERENCE.getName())
                            .getString(Field.DRIVE_ID.getName());
                      }
                    }
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

                  if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                    thumbnailIds.add(new JsonObject()
                        .put(Field.FILE_ID.getName(), folderIds.getDriveId() + ":" + remoteFileId)
                        .put(Field.EXT.getName(), Extensions.getExtension(filename))
                        .put(Field.VERSION_ID.getName(), GraphUtils.getVersionId(obj)));
                  } else {
                    createThumbnail = false;
                  }

                  ObjectInfo objectInfo = GraphUtils.getSPFileJson(
                      config,
                      spApi,
                      obj,
                      false,
                      full,
                      externalId,
                      null,
                      false,
                      storageType,
                      isAdmin,
                      userId,
                      force,
                      createThumbnail);
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
                foldersJson.put(
                    obj.getString(Field.ID.getName()),
                    GraphUtils.getSPFileJson(
                            config,
                            spApi,
                            obj,
                            true,
                            full,
                            externalId,
                            null,
                            false,
                            storageType,
                            isAdmin,
                            userId,
                            false,
                            false)
                        .toJson());
              }
            }
            if (isBrowser && !thumbnailIds.isEmpty()) {
              createThumbnails(
                  segment,
                  new JsonArray(thumbnailIds),
                  message.body().put(Field.STORAGE_TYPE.getName(), storageType.name()));
            }

            if (full && !possibleIdsToIds.isEmpty()) {
              Map<String, JsonObject> newSharedLinksResponse =
                  PublicLink.getSharedLinks(config, segment, userId, externalId, possibleIdsToIds);
              for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
                if (filesJson.containsKey(fileData.getKey())) {
                  filesJson.put(
                      fileData.getKey(),
                      filesJson.get(fileData.getKey()).mergeIn(fileData.getValue()));
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
                    Field.FILE_FILTER.getName(),
                    message.body().getString(Field.FILE_FILTER.getName()));
            sendOK(segment, message, result, mills);
          } catch (Exception e) {
            sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
          }
        }
      }

    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      SPApi.connect(message);
      sendOK(segment, message);
    } catch (GraphException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisSharePointAccount"),
          HttpStatus.FORBIDDEN);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    try {
      long mills = System.currentTimeMillis();
      JsonObject jsonObject = message.body();
      String folderId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
      String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
      String onlyDotsInName = name;
      onlyDotsInName = onlyDotsInName.replaceAll("\\.", emptyString);
      if (onlyDotsInName.isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "onlyDotsInName"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      if (folderId == null) {
        return;
      }

      SPApi spApi = SPApi.connect(message);
      CreateFolderResult folder = spApi.createFolder(folderId, name);

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType), spApi.getExternalId(), folder.getId()))
              .put(Field.DRIVE_ID.getName(), folder.getDriveId()),
          mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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

    try {
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

      SPApi.connect(message).move(id, parentId);

      sendOK(segment, message, mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
      SPApi.connect(message).rename(id, name);

      sendOK(segment, message, mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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

    try {
      long mills = System.currentTimeMillis();

      if (id == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      SPApi.connect(message).delete(id);

      sendOK(segment, message, mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
      GraphApi graphApi = SPApi.connect(message);
      CloneObjectResult result = graphApi.cloneObject(id, name);
      String newFileId = result.getObjectId();
      if (doCopyComments) {
        String finalId = id;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_COMMENTS.getName())
            .run((Segment blockingSegment) -> {
              boolean doIncludeResolvedComments =
                  message.body().getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
              boolean doIncludeDeletedComments =
                  message.body().getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
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
              List<CollaboratorInfo> collaborators =
                  GraphUtils.getSPItemCollaborators(graphApi, ids.getDriveId(), ids.getId());
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
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String downloadToken = jsonObject.getString(Field.DOWNLOAD_TOKEN.getName());
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

    try {
      // driveId can be inside jsonObject
      GraphId ids = new GraphId(fileId);
      XRayEntityUtils.putStorageMetadata(segment, message, storageType);
      XRayEntityUtils.putMetadata(segment, XrayField.DRIVE_ID, ids.getDriveId());
      XRayEntityUtils.putMetadata(segment, XrayField.FILE_ID, ids.getId());

      SPApi spApi = SPApi.connectByUserId(userId, externalId);

      String name = emptyString;
      byte[] content;

      if (latest) {
        long size = 0;
        try {
          ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);
          versionId = objectInfo.getVersionId();
          name = objectInfo.getName(true);
          size = objectInfo.getSizeValue();
        } catch (Exception ignore) {
        }
        if (returnDownloadUrl) {
          String downloadUrl = spApi.getDownloadUrl(ids.getFullId(), null);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(segment, message, downloadUrl, size, versionId, null, mills);
            return;
          }
        }
        content = spApi.getFileContent(fileId);
      } else {
        if (returnDownloadUrl) {
          String downloadUrl = spApi.getDownloadUrl(ids.getFullId(), null);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(
                segment,
                message,
                downloadUrl,
                null,
                versionId,
                spApi.getAccessToken(),
                true,
                null,
                mills);
            return;
          }
        }
        content = spApi.getFileVersionContent(fileId, versionId);
      }

      if (download) {
        try {
          ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);

          name = objectInfo.getName(false);
        } catch (GraphException exception) {
          exception.replyWithError(segment, message);
          XRayManager.endSegment(segment);
        } catch (Exception exception) {
          sendError(
              segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
        }
      }
      finishGetFile(message, start, end, content, storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (GraphException exception) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
      exception.replyWithError(segment, message);
    } catch (Exception exception) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject body = parsedMessage.getJsonObject();

    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, body, storageType);
    }

    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      SPApi spApi = SPApi.connectByUserId(userId, externalId);

      ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);
      String name = objectInfo.getName(false);

      UploadVersionResult uploadVersionResult =
          spApi.uploadVersion(fileId, stream, parsedMessage.getFileSizeFromJsonObject());
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

      JsonArray versions = spApi.getVersions(fileId).toJson(config);
      JsonObject version = versions.getJsonObject(versions.size() - 1);
      sendOK(segment, message, version, mills);
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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

    String responseName = null;
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null);
    boolean fileNotFound = false;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      SPApi spApi = SPApi.connectByUserId(userId, externalId);

      GraphId folderIds = new GraphId(folderId);
      GraphId fileIds = new GraphId(fileId);
      String driveId = folderIds.getDriveId();

      String versionId = "?";
      long uploadDate = -1L;

      if (fileId == null || parsedMessage.hasAnyContent()) {
        // if latest version in ex storage is unknown,
        // then save a new version as a file with a prefix beside the original file
        if (fileId != null) {
          ObjectInfo objectInfo = new ObjectInfo(true);
          try {
            objectInfo = spApi.getBaseObjectInfo(true, fileId, message);
            name = objectInfo.getName(false);
            versionId = objectInfo.getVersionId();
          } catch (GetObjectInfoException exception) {
            JsonObject errorObject = (JsonObject) exception.getResponse().getBody();
            if (errorObject != null
                && errorObject.containsKey(Field.ERROR.getName())
                && errorObject
                    .getJsonObject(Field.ERROR.getName())
                    .containsKey(Field.MESSAGE.getName())) {
              String errorMessage = errorObject
                  .getJsonObject(Field.ERROR.getName())
                  .getString(Field.MESSAGE.getName());
              if (errorMessage.equalsIgnoreCase("Access Denied")
                  || errorMessage.equalsIgnoreCase("Item does not exist")) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
                isConflictFile = true;
                fileNotFound = true;
              }
            }
            if (!fileNotFound) {
              sendError(segment, message, exception.getResponse());
              return;
            }
          }

          folderId = Field.MINUS_1.getName();
          if (!fileNotFound) {
            name = objectInfo.getName(false);
            // get latest version in external storage
            versionId = objectInfo.getVersionId();

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
            folderId = objectInfo.getParentReferenceId();
          }
        }

        String folderName =
            ((GraphUtils.isRootFolder(folderIds.getId())) ? "/root" : "/items/" + folderIds.getId())
                + ":/";
        UploadFileResult uploadResponse = null;
        try {
          uploadResponse = spApi.uploadFile(
              folderId, fileId, name, stream, parsedMessage.getFileSizeFromJsonObject());
        } catch (GraphException exception) {
          if (exception.getResponse().getStatus() != HttpStatus.CREATED
              && exception.getResponse().getStatus() != HttpStatus.OK) {
            if (Utils.isStringNotNullOrEmpty((String) exception.getResponse().getBody())) {
              JsonObject errorObj =
                  new JsonObject((String) exception.getResponse().getBody());
              if (errorObj.containsKey(Field.ERROR.getName())
                  && errorObj.getValue(Field.ERROR.getName()) instanceof JsonObject
                  && errorObj
                      .getJsonObject(Field.ERROR.getName())
                      .containsKey(Field.CODE.getName())) {
                if (errorObj
                    .getJsonObject(Field.ERROR.getName())
                    .getString(Field.CODE.getName())
                    .equalsIgnoreCase("accessDenied")) {
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
                } else {
                  conflictingFileReason =
                      XSessionManager.ConflictingFileReason.GENERAL_STORAGE_ERROR.name();
                  isConflictFile = true;
                }
              }
            }
            if (!isConflictFile) {
              sendError(segment, message, exception.getResponse());
              return;
            }
          }
        }
        String oldName = name;
        String newName = name;
        boolean isSameFolder = true;
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
          // driveId = oneDriveApiOverlay.getMyDrive().getString(Field.ID.getName());
          if (folderName.startsWith("/root")) {
            isSameFolder = false;
          }
        }
        if (isConflictFile) {
          try {
            uploadResponse = spApi.uploadConflictingFile(
                folderId, folderName, newName, stream, parsedMessage.getFileSizeFromJsonObject());
            handleConflictingFile(
                segment,
                message,
                body,
                oldName,
                newName,
                Utils.getEncapsulatedId(storageType, externalId, fileIds.getId()),
                Utils.getEncapsulatedId(storageType, externalId, uploadResponse.getFileId()),
                xSessionId,
                userId,
                null,
                conflictingFileReason,
                fileSessionExpired,
                isSameFolder,
                AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
          } catch (GraphException exception) {
            sendError(segment, message, exception.getResponse());
            return;
          }
        }

        if (Objects.isNull(uploadResponse)) {
          throw new Error(Utils.getLocalizedString(message, "SharepointUploadResponseIsNull"));
        }
        fileId = uploadResponse.getFileId();
        driveId = uploadResponse.getDriveId();
        versionId = uploadResponse.getVersionId();
        uploadDate = uploadResponse.getUploadDate();
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
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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
      SPApi spApi = SPApi.connect(message);
      JsonArray versions = spApi.getVersions(id).toJson(config);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            ObjectInfo objectInfo;
            try {
              objectInfo = spApi.getBaseObjectInfo(true, id, message);
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
              XRayManager.endSegment(blockingSegment);
            } catch (Exception ex) {
              log.warn("[SHAREPOINT] Get versions: Couldn't get object info to get extension.", ex);
            }
          });
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), versions), mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
      PromoteVersionResult result = SPApi.connect(message).promoteVersion(id, versionId);

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), result.getObjectId())
              .put(Field.VERSION_ID.getName(), result.getVersionId()),
          mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
    JsonObject jsonObject = message.body();
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());

    try {

      ObjectInfo objectInfo = SPApi.connect(message)
          .getObjectInfo(message, true, id, true, false, isAdmin, false, false);
      String versionId = objectInfo.getVersionId();
      sendOK(segment, message, new JsonObject().put(Field.VERSION_ID.getName(), versionId), mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doGetInfoByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String userId = jsonObject.getString(Field.OWNER_ID.getName());
    String token = jsonObject.getString(Field.TOKEN.getName());
    Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    String creatorId = jsonObject.getString(Field.CREATOR_ID.getName());
    String driveId = jsonObject.getString(Field.DRIVE_ID.getName());
    if (fileId == null) {
      return;
    }

    try {

      Item foreignUser = ExternalAccounts.getExternalAccount(userId, externalId);
      ObjectInfo fileInfoByToken =
          SPApi.connectByItem(foreignUser).getFileInfoByToken(driveId, fileId);

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

      sendOK(segment, message, fileInfoByToken.toJson(), mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
    boolean success = false;

    try {
      Item foreignUser = ExternalAccounts.getExternalAccount(userId, externalId);
      SPApi spApi = SPApi.connectByItem(foreignUser);

      ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);
      if (returnDownloadUrl) {
        String downloadUrl = spApi.getDownloadUrl(fileId, null);
        if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
          sendDownloadUrl(
              segment,
              message,
              downloadUrl,
              objectInfo.getSizeValue(),
              objectInfo.getVersionId(),
              null,
              mills);
          return;
        }
      }

      byte[] content = spApi.getFileContent(fileId);

      finishGetFile(
          message,
          null,
          null,
          content,
          storageType,
          objectInfo.getName(false),
          objectInfo.getVersionId(),
          downloadToken);
      success = true;
    } catch (Exception exception) {
      log.info(exception);
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
    }

    if (!success) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetTheFileData"),
          HttpStatus.BAD_REQUEST);
    } else {
      XRayManager.endSegment(segment);
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
      SPApi.connect(message).shareObject(id, emailOrId, role);
      if (reply) {
        sendOK(segment, message, mills);
      }
    } catch (GraphException exception) {
      if (reply) {
        exception.replyWithError(segment, message);
      }
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      if (reply) {
        sendError(
            segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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

    if (id == null || permissionId == null) {
      return;
    }

    try {
      SPApi spApi = SPApi.connect(message);

      spApi.removeObjectPermission(id, permissionId, objectToRemove);

      sendOK(
          segment,
          message,
          new JsonObject().put(Field.USERNAME.getName(), spApi.getEmail()),
          mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doRestore(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      SPApi.connect(message).checkFile(fileId);

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
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (Utils.isStringNotNullOrEmpty(externalId)) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      } else {
        super.deleteSubscriptionForUnavailableFile(
            id, jsonObject.getString(Field.USER_ID.getName()));
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return;
      }
    }

    boolean full = true;
    if (jsonObject.containsKey(Field.FULL.getName())
        && jsonObject.getString(Field.FULL.getName()) != null) {
      full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
    }

    try {
      ObjectInfo objectInfo = SPApi.connect(message)
          .getObjectInfo(message, isFile, id, full, true, isAdmin, false, false);

      sendOK(segment, message, objectInfo.toJson(), mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
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

    try {
      ObjectInfo objectInfo =
          SPApi.connectByUserId(userId, externalId).getBaseObjectInfo(true, id, message);

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
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = jsonObject.getString(Field.ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
    }
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      SPApi spApi = SPApi.connect(message);
      GraphId ids = new GraphId(id);
      final String properExternalId =
          Utils.isStringNotNullOrEmpty(externalId) ? externalId : ids.getDriveId();

      JsonArray result = new JsonArray();
      String myFiles = Utils.getLocalizedString(message, Field.MY_FILES_FOLDER.getName());

      if (GraphUtils.isRootMainFolder(ids.getId())) {
        result.add(new JsonObject()
            .put(Field.NAME.getName(), "~")
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, properExternalId, Field.MINUS_1.getName()))
            .put(Field.VIEW_ONLY.getName(), false));
        sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
        return;
      }

      // root fake folder
      if (GraphUtils.isRootFakeFolder(ids.getId())) {
        result.add(new JsonObject()
            .put(Field.NAME.getName(), myFiles)
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(
                    storageType, properExternalId, GraphUtils.ROOT_FOLDER_FAKE_ID))
            .put(Field.VIEW_ONLY.getName(), false));
      }
      // site folder
      else if (Site.isSiteId(ids.getId())) {
        Site site = spApi.getSite(ids.getId());
        result.add(new JsonObject()
            .put(Field.NAME.getName(), site.getName())
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, properExternalId, ids.getId()))
            .put(Field.VIEW_ONLY.getName(), false));
      }
      // regular folder
      else {
        getPath(spApi, ids, result, myFiles, properExternalId);
      }

      if (result.isEmpty()
          || !"~".equals(result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
        result.add(new JsonObject()
            .put(Field.NAME.getName(), "~")
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, properExternalId, Field.MINUS_1.getName()))
            .put(Field.VIEW_ONLY.getName(), false));
      }
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  private void getPath(
      SPApi spApi, GraphId ids, JsonArray result, String myFiles, String externalId)
      throws GraphException {
    ObjectInfo objectInfo = spApi.getBaseObjectInfo(false, ids.getFullId(), null);
    String name = objectInfo.getName(false);

    if (!name.equals(Field.ROOT.getName())) {
      String objDriveId = objectInfo.getParentReferenceDriveId();

      result.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(
                  storageType,
                  externalId,
                  (objDriveId != null ? objDriveId + ":" : emptyString) + ids.getId()))
          .put(Field.NAME.getName(), name)
          .put(Field.VIEW_ONLY.getName(), objectInfo.isViewOnly())
          .put(Field.SHARED.getName(), objectInfo.isShared()));

      String parent = objectInfo.getParentReferenceId();

      if (parent != null) {
        getPath(spApi, new GraphId(objDriveId, parent), result, myFiles, externalId);
      }
    } else {
      try {
        JsonObject driveObj = spApi.getDriveInfo(ids.getDriveId());

        result.add(new JsonObject()
            .put(Field.NAME.getName(), driveObj.getString(Field.NAME.getName()))
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, ids.getDriveId() + ":root"))
            .put(Field.VIEW_ONLY.getName(), false));

        if (driveObj.containsKey("sharePointIds")) {
          String siteId = driveObj.getJsonObject("sharePointIds").getString("siteId");

          Site site = null;
          try {
            site = spApi.getSite(siteId);
          } catch (Exception e) {
            log.error("getSiteInfoError", e);
          }

          String siteName = site != null
              ? site.getName()
              : driveObj.getJsonObject("sharePointIds").getString("siteUrl");

          if (Utils.isStringNotNullOrEmpty(siteId)) {
            result.add(new JsonObject()
                .put(Field.NAME.getName(), siteName)
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType, externalId, Site.getFinalIdFromRaw(siteId)))
                .put(Field.VIEW_ONLY.getName(), false));
          }
        }

      } catch (Exception exception) {
        result.add(new JsonObject()
            .put(Field.NAME.getName(), myFiles)
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, ids.getDriveId() + ":root"))
            .put(Field.VIEW_ONLY.getName(), false));
      }
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
      SPApi spApi = SPApi.connect(message);

      ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);

      try {
        final boolean oldExport = getLinkExportStatus(objectInfo.getWsId(), externalId, userId);
        PublicLink newLink = super.initializePublicLink(
            objectInfo.getWsId(),
            externalId,
            userId,
            spApi.getStorageType(),
            spApi.getEmail(),
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

    } catch (GraphException exception) {
      exception.replyWithError(segment, message);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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
      SPApi spApi = SPApi.connect(message);
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
              spApi.getObjectInfo(!isFolder, objectId, false, false, false, message);
          GraphId ids = new GraphId(objectId);
          String name = objectInfo.getName(true);
          if (isFolder) {
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(stream, spApi, ids, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = objectInfo.getSizeValue();
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, name, ExcludeReason.Large);
              return null;
            }
            addZipEntry(
                stream, spApi, ids, name, filter, filteredFileNames, fileNames, emptyString);
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
    } catch (GraphException ex) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
    } catch (Exception ex) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
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
    log.info("OD: folder ZIP: " + requestFolderId + " request: " + requestId);
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
      SPApi spApi = SPApi.connect(message);
      GraphId ids = new GraphId(requestFolderId);

      log.info("OD: folder ZIP. Drive: " + ids.getDriveId() + " folder: " + ids.getId());

      Future<Void> futureTask = executorService.submit(() -> {
        Entity standaloneSegment = XRayManager.createStandaloneSegment(
            operationGroup, segment, "SharePointZipFolderSegment");
        zipFolder(stream, spApi, ids, filter, recursive, emptyString, new HashSet<>(), 0, request);
        XRayManager.endSegment(standaloneSegment);
        return null;
      });
      sendOK(segment, message);
      finishDownloadZip(message, segment, s3Regional, stream, bos, futureTask, request);
    } catch (GraphException gex) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
    } catch (Exception ex) {
      ZipRequests.setRequestException(message, request, ex);
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      SPApi spApi,
      GraphId ids,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    JsonArray value = spApi.getFolderContent(ids);
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
              stream, spApi, fileIds, name, filter, filteredFileNames, fileNames, properPath);
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
                spApi,
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
      SPApi spApi,
      GraphId ids,
      String name,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames,
      String properPath)
      throws IOException {
    byte[] content;
    try {
      content = spApi.getFileContent(new GraphId(ids.getDriveId(), ids.getId()).getFullId());
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

      // RG: Refactored to not access global collection
      JsonArray result = new JsonArray(array.parallelStream()
          .map(sharepointUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup,
                segment,
                sharepointUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            StorageType userStorageType =
                StorageType.getStorageType(sharepointUser.getString(Field.F_TYPE.getName()));
            final String externalId = sharepointUser.getString(Field.EXTERNAL_ID_LOWER.getName());

            try {
              SPApi spApi = SPApi.connectByItem(sharepointUser);

              List<String> driveIds = new ArrayList<>();
              spApi.getAllSites().stream()
                  .map(site -> {
                    try {
                      return spApi.getSiteDrives(site.getRawId()).stream()
                          .map(Site::getRawId)
                          .collect(Collectors.toList());
                    } catch (Exception ignore) {
                      return new ArrayList<String>();
                    }
                  })
                  .collect(Collectors.toList())
                  .forEach(driveIds::addAll);

              JsonObject driveResult = spApi.globalSearch(
                  driveIds.stream().distinct().collect(Collectors.toList()), query, isAdmin);
              XRayManager.endSegment(subSegment);
              return driveResult;
            } catch (GraphException e) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), userStorageType.toString())
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(Field.NAME.getName(), sharepointUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
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
      SPApi spApi = SPApi.connect(message);
      GraphId ids = new GraphId(fileId);

      String currentFolder = folderId[0];
      if (currentFolder == null) {
        ObjectInfo fileInfo = spApi.getBaseObjectInfo(true, fileId, message);
        if (fileInfo.getParentReferenceId() == null
            || fileInfo.getParentReferenceId().endsWith("root:")) {
          currentFolder = Field.MINUS_1.getName();
        } else {
          currentFolder = fileInfo.getParentReferenceId();
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
                  .put(Field.PATH.getName(), null)
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
                // check that current user is an owner or an editor
                ObjectInfo folderInfo = spApi.getBaseObjectInfo(
                    false,
                    new GraphId(ids.getDriveId(), finalCurrentFolder[0]).getFullId(),
                    message);

                boolean available = folderInfo.isOwner() || folderInfo.canWrite();
                // check for existing file
                if (available) {
                  try {
                    JsonArray value;
                    try {
                      value = spApi.getFolderContentArray(ids.getDriveId(), finalCurrentFolder[0]);
                    } catch (GraphException exception) {
                      XRayManager.endSegment(subSegment);
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    }

                    for (Object item : value) {
                      // todo maybe also check if it's a file
                      available =
                          !((JsonObject) item).getString(Field.NAME.getName()).equals(filename);
                      if (!available) {
                        break;
                      }
                    }
                  } catch (Exception e) {
                    log.error(e);
                    available = false;
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
              Map<String, JsonObject> foldersCache = new HashMap<>();
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
                      ObjectInfo possibleFolderInfo =
                          spApi.getBaseObjectInfo(false, possibleFolderId, message);
                      String parentId = possibleFolderInfo.getParentReferenceId();
                      if (parentId == null
                          || possibleFolderInfo.getParentReferencePath().endsWith("root:")) {
                        parentId = Field.MINUS_1.getName();
                      }

                      adds.add(parentId);
                      dels.add(possibleFolderId);
                    } catch (GraphException exception) {
                      sendError(segment, message, exception.getResponse());
                      XRayManager.endSegment(subSegment);
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    } catch (Exception ignore) {
                    }
                  } else {
                    try {
                      JsonArray value;
                      try {
                        value = spApi.getFolderContentArray(null, possibleFolderId);
                      } catch (GraphException exception) {
                        XRayManager.endSegment(subSegment);
                        return new JsonObject()
                            .put(Field.PATH.getName(), pathStr)
                            .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                      }

                      for (Object item : value) {
                        JsonObject obj = (JsonObject) item;
                        if (obj.getString(Field.NAME.getName()).equals(array[i])) {
                          // todo maybe check if it's a folder
                          foldersCache.put(obj.getString(Field.ID.getName()), obj);
                          adds.add(obj.getString(Field.ID.getName()));
                        }
                      }
                    } catch (UnirestException e) {
                      log.error(e);
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
                  JsonObject possibleFolder = foldersCache.get(possibleFolderId);
                  boolean isOwner;
                  boolean writeAccess;

                  if (possibleFolder == null) {
                    ObjectInfo possibleFolderInfo;
                    try {
                      possibleFolderInfo =
                          spApi.getBaseObjectInfo(false, possibleFolderId, message);
                    } catch (GraphException exception) {
                      sendError(segment, message, exception.getResponse());
                      XRayManager.endSegment(subSegment);
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    }
                    isOwner = possibleFolderInfo.isOwner();
                    writeAccess = possibleFolderInfo.canWrite();
                  } else {
                    isOwner = (possibleFolder.containsKey(Field.CREATED_BY.getName())
                            ? possibleFolder
                                .getJsonObject(Field.CREATED_BY.getName())
                                .getJsonObject(Field.USER.getName())
                                .getString(Field.ID.getName())
                            : possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                    && possibleFolder
                                        .getJsonObject(Field.REMOTE_ID.getName())
                                        .containsKey(Field.CREATED_BY.getName())
                                ? possibleFolder
                                    .getJsonObject(Field.REMOTE_ID.getName())
                                    .getJsonObject(Field.CREATED_BY.getName())
                                    .getJsonObject(Field.USER.getName())
                                    .getString(Field.ID.getName())
                                : emptyString)
                        .equals(externalId);

                    writeAccess = possibleFolder.containsKey(Field.PERMISSION.getName())
                        && possibleFolder
                            .getJsonArray(Field.PERMISSION.getName())
                            .contains("write");
                  }

                  boolean available = GraphUtils.isRootFolder(possibleFolderId);
                  if (!available) {
                    available = isOwner || writeAccess;
                  }
                  if (available) {
                    if (lastStep == -1) {
                      boolean add = true;
                      String driveId = new GraphId(finalCurrentFolder[0]).getDriveId();
                      JsonArray value;

                      try {
                        value = spApi.getFolderContentArray(
                            driveId == null ? ids.getDriveId() : driveId, possibleFolderId);
                        for (Object item : value) {
                          // todo maybe also check if it's a file
                          add = !((JsonObject) item)
                              .getString(Field.NAME.getName())
                              .equals(filename);
                          if (!add) {
                            break;
                          }
                        }
                      } catch (GraphException exception) {
                        add = false;
                      }

                      if (add && possibleFolder != null) {
                        folders.add(new JsonObject()
                            .put(Field.ID.getName(), possibleFolderId)
                            .put(
                                Field.OWNER.getName(),
                                possibleFolder.containsKey(Field.CREATED_BY.getName())
                                    ? possibleFolder
                                        .getJsonObject(Field.CREATED_BY.getName())
                                        .getJsonObject(Field.USER.getName())
                                        .getString(Field.DISPLAY_NAME.getName())
                                    : possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                            && possibleFolder
                                                .getJsonObject(Field.REMOTE_ID.getName())
                                                .containsKey(Field.CREATED_BY.getName())
                                        ? possibleFolder
                                            .getJsonObject(Field.REMOTE_ID.getName())
                                            .getJsonObject(Field.CREATED_BY.getName())
                                            .getJsonObject(Field.USER.getName())
                                            .getString(Field.DISPLAY_NAME.getName())
                                        : emptyString)
                            .put(Field.IS_OWNER.getName(), isOwner)
                            .put(Field.CAN_MOVE.getName(), isOwner)
                            .put(
                                Field.UPDATE_DATE.getName(),
                                DatatypeConverter.parseDateTime(possibleFolder.getString(
                                        Field.LAST_MODIFIED_DATE_TIME.getName()))
                                    .getTime()
                                    .getTime())
                            .put(
                                Field.CHANGER.getName(),
                                (possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                        ? possibleFolder.getJsonObject(Field.REMOTE_ID.getName())
                                        : possibleFolder)
                                    .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                                    .getJsonObject(Field.USER.getName())
                                    .getString(Field.DISPLAY_NAME.getName()))
                            .put(
                                Field.CHANGER_ID.getName(),
                                (possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                        ? possibleFolder.getJsonObject(Field.REMOTE_ID.getName())
                                        : possibleFolder)
                                    .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                                    .getJsonObject(Field.USER.getName())
                                    .getString(Field.ID.getName()))
                            .put(
                                Field.NAME.getName(),
                                possibleFolder.getString(Field.NAME.getName()))
                            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                      }
                    } else {
                      folders.add(new JsonObject()
                          .put(Field.ID.getName(), possibleFolderId)
                          .put(
                              Field.OWNER.getName(),
                              possibleFolder.containsKey(Field.CREATED_BY.getName())
                                  ? possibleFolder
                                      .getJsonObject(Field.CREATED_BY.getName())
                                      .getJsonObject(Field.USER.getName())
                                      .getString(Field.DISPLAY_NAME.getName())
                                  : possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                          && possibleFolder
                                              .getJsonObject(Field.REMOTE_ID.getName())
                                              .containsKey(Field.CREATED_BY.getName())
                                      ? possibleFolder
                                          .getJsonObject(Field.REMOTE_ID.getName())
                                          .getJsonObject(Field.CREATED_BY.getName())
                                          .getJsonObject(Field.USER.getName())
                                          .getString(Field.DISPLAY_NAME.getName())
                                      : emptyString)
                          .put(Field.IS_OWNER.getName(), isOwner)
                          .put(Field.CAN_MOVE.getName(), isOwner)
                          .put(
                              Field.UPDATE_DATE.getName(),
                              DatatypeConverter.parseDateTime(possibleFolder.getString(
                                      Field.LAST_MODIFIED_DATE_TIME.getName()))
                                  .getTime()
                                  .getTime())
                          .put(
                              Field.CHANGER.getName(),
                              (possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                      ? possibleFolder.getJsonObject(Field.REMOTE_ID.getName())
                                      : possibleFolder)
                                  .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                                  .getJsonObject(Field.USER.getName())
                                  .getString(Field.DISPLAY_NAME.getName()))
                          .put(
                              Field.CHANGER_ID.getName(),
                              (possibleFolder.containsKey(Field.REMOTE_ID.getName())
                                      ? possibleFolder.getJsonObject(Field.REMOTE_ID.getName())
                                      : possibleFolder)
                                  .getJsonObject(Field.LAST_MODIFIED_BY.getName())
                                  .getJsonObject(Field.USER.getName())
                                  .getString(Field.ID.getName()))
                          .put(Field.NAME.getName(), possibleFolder.getString(Field.NAME.getName()))
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
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
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
      SPApi spApi = SPApi.connect(message);
      GraphId ids = new GraphId(fileId);

      String currentFolderId = requestFolderId[0];
      if (currentFolderId == null) {
        ObjectInfo objectInfo = spApi.getBaseObjectInfo(true, fileId, message);

        String parentReferencePath = objectInfo.getParentReferencePath();
        if (parentReferencePath != null && parentReferencePath.endsWith("root:")) {
          currentFolderId = ids.getDriveId() + ":root";
        } else {
          currentFolderId = objectInfo.getParentReferenceId();
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

                Iterator<String> it = possibleFolders.iterator();
                Set<String> adds = new HashSet<>(), dels = new HashSet<>();
                while (it.hasNext()) {
                  String folderId = it.next();
                  dels.add(folderId);
                  if ("..".equals(array[i])) {
                    if (GraphUtils.isRootFolder(folderId)) {
                      continue;
                    }
                    boolean isLastUp = (!array[i + 1].equals(".."));
                    goUp(spApi, finalDriveId, folderId, adds, isLastUp);
                  } else {
                    goDown(spApi, finalDriveId, folderId, array[i], cache, adds);
                  }
                }
                possibleFolders.removeAll(dels);
                possibleFolders.addAll(adds);
              }
            }
            // check in all possible folders
            for (String folderId : possibleFolders) {
              findFileInFolder(
                  spApi, pathFiles, filename, folderId, cache, finalDriveId, finalExternalId);
            }

            // check in the root if not found
            if (pathFiles.isEmpty() && !possibleFolders.contains(finalCurrentFolderId)) {
              findFileInFolder(
                  spApi,
                  pathFiles,
                  filename,
                  finalCurrentFolderId,
                  cache,
                  finalDriveId,
                  finalExternalId);
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
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private void goDown(
      SPApi spApi,
      String driveId,
      String folderId,
      String name,
      Map<String, JsonArray> cache,
      Set<String> adds) {
    boolean differentDrives = false, differentSites = false;

    try {
      JsonArray value = cache.get(folderId);
      String localDriveId = null;
      if (value == null) {
        GraphId ids = new GraphId(folderId);
        if (ids.getDriveId() == null) {
          localDriveId = driveId;
        }

        // for different drives
        if (ids.getId().startsWith(Site.sitePrefix)) {
          value = spApi.getSiteDrivesJson(ids.getId().substring(2));
          differentDrives = true;
        }

        // for different sites
        if (ids.getId().equals(Field.MINUS_1.getName())) {
          value = spApi.getAllSitesJson();
          differentSites = true;
        }

        if (value == null) {
          value = spApi.getFolderContentArray(localDriveId, folderId);
        }
        cache.put(ids.getId(), value);
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

        if (differentDrives && object.getString(Field.NAME.getName()).equals(name)) {
          String id = object.containsKey(Field.REMOTE_ID.getName())
              ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : object.getString(Field.ID.getName());
          id = id + ":root";
          adds.add(id);
          return;
        }

        if (differentSites && object.getString(Field.DISPLAY_NAME.getName()).equals(name)) {
          String id = object.containsKey(Field.REMOTE_ID.getName())
              ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : object.getString(Field.ID.getName());
          String[] ids = id.split(",");
          if (ids.length == 3) {
            id = ids[1];
          }
          id = Site.sitePrefix + id;
          adds.add(id);
          return;
        }

        if (object.containsKey(Field.FOLDER.getName())
            && object.getString(Field.NAME.getName()).equals(name)) {
          String id = object.containsKey(Field.REMOTE_ID.getName())
              ? object.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
              : object.getString(Field.ID.getName());
          id = new GraphId(localDriveId, id).getFullId();
          adds.add(id);
        }
      }
    } catch (GraphException e) {
      log.error("Error on going down in oneDrive", e);
    }
  }

  private void goUp(
      SPApi spApi, String driveId, String folderId, Set<String> adds, boolean isLastUp) {
    try {
      String parent, parentId;
      GraphId ids = new GraphId(folderId);
      parentId = ids.getDriveId();

      if (parentId == null) {
        parentId = driveId;
      }

      if (ids.getId().equals(Field.ROOT.getName())) {
        if (isLastUp) {
          // for different sites
          // if we go up outside site root, we search for all sites when we go down
          if (parentId.startsWith(Site.sitePrefix)) {
            parent = Field.MINUS_1.getName();
            adds.add(parent);
            return;
          }
          // for different drives
          // if we go up outside drive root, we search for all drives within a site when we go down
          try {
            JsonObject driveObj = spApi.getDriveInfo(driveId);
            String siteId = driveObj.getJsonObject("sharePointIds").getString("siteId");
            parent = Site.sitePrefix + siteId;
            adds.add(parent);
          } catch (GraphException exception) {
            log.error("Error on going up in oneDrive", exception.getResponse().getBody());
          }
        } else {
          // for site root
          parent = Site.sitePrefix + ":root";
          adds.add(parent);
        }
        return;
      }
      if (parentId == null) {
        return;
      }

      ObjectInfo objectInfo =
          spApi.getBaseObjectInfo(false, new GraphId(parentId, ids.getId()).getFullId(), null);

      parent = objectInfo.getParentReferenceId();
      String path = objectInfo.getParentReferencePath();
      if (Utils.isStringNotNullOrEmpty(path) && path.endsWith("root:")) {
        parent = Field.ROOT.getName();
      }

      adds.add(parent);
    } catch (GraphException e) {
      log.error("Error on going up in oneDrive", e);
    }
  }

  private void findFileInFolder(
      SPApi spApi,
      JsonArray pathFiles,
      String filename,
      String folderId,
      Map<String, JsonArray> cache,
      String driveId,
      String externalId) {
    JsonArray value = cache.get(folderId);
    try {
      if (value == null) {
        GraphId ids = new GraphId(folderId);
        String localDriveId = ids.getDriveId();
        if (localDriveId == null) {
          localDriveId = driveId;
        }
        if (localDriveId == null) {
          return;
        }

        value = spApi.getFolderContentArray(localDriveId, ids.getId());
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
        if (object.containsKey(Field.FILE.getName())
            && object.getString(Field.NAME.getName()).equals(filename)) {
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
          String originalExternalId =
              (externalId.startsWith("SP")) ? externalId.substring(3) : externalId;
          pathFiles.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType,
                      externalId,
                      driveId + ":" + object.getString(Field.ID.getName())))
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
                      .equals(originalExternalId))
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
    } catch (GraphException e) {
      log.info(
          "Error on searching for xref in possible folder in oneDrive",
          e.getResponse().getBody());
    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in oneDrive", e);
    }
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = jsonObject.getString(Field.FILE_ID.getName());

    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    String userId = jsonObject.getString(Field.USER_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
    }

    try {
      SPApi.connectByUserId(userId, externalId).getBaseObjectInfo(true, id, message);

      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), false), mills);
    } catch (GraphException exception) {
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", exception.getMessage()),
          mills);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }
}
