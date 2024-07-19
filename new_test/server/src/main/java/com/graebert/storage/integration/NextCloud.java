package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.github.sardine.DavResource;
import com.github.sardine.impl.SardineException;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.nextcloud.NextCloudMapping;
import com.graebert.storage.integration.nextcloud.NextcloudApi;
import com.graebert.storage.integration.nextcloud.OwncloudPermission;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Base64;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NonNls;

public class NextCloud extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.NEXTCLOUD;
  public static final String address = "nextcloud";
  private static final StorageType storageType = StorageType.NEXTCLOUD;
  private final String[] specialCharacters = {
    "<", ">", "\"", "|", ":", "?", "*", "/", "\\", "#", "'"
  };
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private JsonArray fileFormats;
  private S3Regional s3Regional = null;

  private static void collectFiles(HashMap<String, JsonObject> m, JsonObject v) {
    try {
      m.put(v.getString(Field.WS_ID.getName()), v);
    } catch (Exception ex) {
      log.error(ex);
    }
  }

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> event) -> doGetFolderContent(event, false));
    eb.consumer(address + ".createFolder", this::doCreateFolder);
    eb.consumer(address + ".renameFolder", this::doRenameFolder);
    eb.consumer(address + ".moveFolder", this::doMoveFolder);
    eb.consumer(address + ".deleteFolder", this::doDeleteFolder);
    eb.consumer(address + ".getFile", this::doGetFile);
    eb.consumer(address + ".uploadFile", this::doUploadFile);
    eb.consumer(address + ".uploadVersion", this::doUploadVersion);
    eb.consumer(address + ".moveFile", this::doMoveFile);
    eb.consumer(address + ".deleteFile", this::doDeleteFile);
    eb.consumer(address + ".getVersions", this::doGetVersions);
    eb.consumer(address + ".getLatestVersionId", this::doGetLatestVersionId);
    eb.consumer(address + ".getVersion", this::doGetFile);
    eb.consumer(address + ".promoteVersion", this::doPromoteVersion);
    eb.consumer(address + ".clone", this::doClone);
    eb.consumer(address + ".createShortcut", this::doCreateShortcut);
    eb.consumer(address + ".renameFile", this::doRenameFile);
    eb.consumer(address + ".getFileByToken", this::doGetFileByToken);
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
    eb.consumer(address + ".disconnect", this::doDisconnect);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);
    eb.consumer(address + ".stopPoll", this::stopPoll);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-nextcloud");

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    fileFormats = Extensions.getExtensions(config);
  }

  @Override
  public void doGetVersionByToken(Message<JsonObject> message) {
    final Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    final JsonObject jsonObject = message.body();
    final KudoFileId fileIds = KudoFileId.fromJson(message.body(), IdName.FILE);
    final String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    final String lt = jsonObject.getString(Field.LINK_TYPE.getName());

    try {
      JsonObject api = connect(segment, message);
      if (api == null) {
        return;
      }

      NextcloudApi nextcloudApi = new NextcloudApi(api);

      String name = "unnamed.dwg";
      String finalUrl;
      String currentVersion = emptyString;
      String nextcloudfileid = NextCloudMapping.getResourceFileId(api, fileIds.getId());

      finalUrl = NextCloudMapping.getResourcePath(api, fileIds.getId());
      List<DavResource> resources = nextcloudApi.getInfo(finalUrl);

      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        currentVersion = parseVersionId(res.getModified().getTime());
        name = getResourceName(res);
      }

      message.body().put(Field.NAME.getName(), name);

      String realVersionId;
      if (VersionType.parse(versionId).equals(VersionType.LATEST)) {
        finalUrl = NextCloudMapping.getResourcePath(api, fileIds.getId());
        realVersionId = currentVersion;
      } else {
        realVersionId = versionId;
      }

      if (!nextcloudfileid.isEmpty() && !currentVersion.equals(realVersionId)) {
        String versionPath = "/remote.php/dav/versions/" + api.getString(Field.EMAIL.getName())
            + "/versions/" + nextcloudfileid + "/" + versionId;

        resources = nextcloudApi.getVersion(nextcloudfileid, versionId);
        if (!resources.isEmpty()) {
          finalUrl = versionPath;
        }
      }

      byte[] data;
      try (InputStream in = nextcloudApi.getObjectData(finalUrl)) {
        data = IOUtils.toByteArray(in);
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
          message, data, realVersionId, name, LinkType.parse(lt).equals(LinkType.DOWNLOAD));
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
      JsonObject api = connect(segment, message, message.body(), false);
      if (api == null) {
        super.deleteSubscriptionForUnavailableFile(fileIds.getId(), userId);
        return;
      }

      NextcloudApi nextcloudApi = new NextcloudApi(api);

      String nextcloudfileid = NextCloudMapping.getResourceFileId(api, fileIds.getId());
      String name = "unnamed.dwg";
      String finalUrl = NextCloudMapping.getResourcePath(api, fileIds.getId());
      List<DavResource> resources = nextcloudApi.getInfo(finalUrl);
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        nextcloudfileid = NextCloudMapping.getFileId(res);
        name = getResourceName(res);
      }

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          nextcloudApi.getVersion(nextcloudfileid, versionId);
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
              .put(Field.NAME.getName(), name)
              .put(
                  Field.OWNER_IDENTITY.getName(),
                  ExternalAccounts.getExternalEmail(userId, fileIds.getExternalId()))
              .put(Field.COLLABORATORS.getName(), new JsonArray()),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  @Override
  public void doAddAuthCode(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    String url = getRequiredString(segment, Field.URL.getName(), message, jsonObject);

    String graebertId = jsonObject.getString(Field.GRAEBERT_ID.getName());
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());

    if (url == null) {
      return;
    }

    try {
      JsonObject loginUrlResponse = NextcloudApi.getLoginUrl(url);

      sendOK(
          segment,
          message,
          new JsonObject().put("authUrl", loginUrlResponse.getString("authUrl")),
          mills);

      // user is redirected to login page, we need to poll url until we get 200
      // this will be happening only once, else we get 404 response
      // will wait for 15 minutes, then reject
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            vertx.setPeriodic(5000, periodicId -> {
              try {
                NextcloudApi.savePendingLogin(userId, url, periodicId);

                JsonObject pollAuthResponse = NextcloudApi.pollAuth(
                    loginUrlResponse.getString("pollUrl"), loginUrlResponse.getString("pollToken"));

                String email = pollAuthResponse.getString(Field.EMAIL.getName());
                String password = pollAuthResponse.getString(Field.PASSWORD.getName());
                String serverUrl = pollAuthResponse.getString("serverUrl");

                JsonObject metadataResponse =
                    new NextcloudApi(serverUrl, email, password).getUserMetadata(null);

                String connectionName =
                    Integer.toString(Math.abs(UUID.randomUUID().hashCode()));
                String externalId = storageType.name() + "_" + connectionName;

                Item externalAccount = ExternalAccounts.formExternalAccountItem(userId, externalId)
                    .withString(Field.EMAIL.getName(), email)
                    .withString(
                        Field.PASSWORD.getName(),
                        EncryptHelper.encrypt(
                            password, config.getProperties().getFluorineSecretKey()))
                    .withString(Field.USER_ID.getName(), userId)
                    .withString("connectionId", connectionName)
                    .withString(Field.EXTERNAL_ID_LOWER.getName(), externalId)
                    .withString(Field.F_TYPE.getName(), storageType.name())
                    .withString(Field.URL.getName(), serverUrl)
                    .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime())
                    // NC connection basically don't ever expire,
                    // so we set a long expiration date to avoid
                    // refresh scripts
                    .withLong(
                        Field.EXPIRES.getName(),
                        GMTHelper.utcCurrentTime() + TimeUnit.DAYS.toMillis(365 * 10));

                if (metadataResponse != null) {
                  externalAccount
                      .withLong("quotaFree", metadataResponse.getLong("quotaFree"))
                      .withLong("quotaTotal", metadataResponse.getLong("quotaTotal"))
                      .withLong("quotaUsed", metadataResponse.getLong("quotaUsed"))
                      .withString(
                          Field.DISPLAY_NAME.getName(),
                          metadataResponse.getString(Field.DISPLAY_NAME.getName()));
                }

                storageLog(
                    blockingSegment,
                    message,
                    userId,
                    graebertId,
                    sessionId,
                    email,
                    storageType.toString(),
                    externalId,
                    true,
                    intercomAccessToken);

                Sessions.updateSessionOnConnect(
                    externalAccount, userId, storageType.name(), externalId, sessionId);

                log.warn("Nextcloud integration done");
                vertx.cancelTimer(periodicId);
              } catch (NextcloudApi.NextcloudApiException ncError) {
                if (ncError
                    .getMessage()
                    .equals(NextcloudApi.NextcloudApiException.AUTH_NOT_FINISHED)) {
                  log.error("Waiting for Nextcloud integration");
                } else {
                  eb_send(
                      blockingSegment,
                      WebSocketManager.address + ".storageAddError",
                      new JsonObject()
                          .put(Field.USER_ID.getName(), userId)
                          .put(Field.SESSION_ID.getName(), sessionId)
                          .put(Field.STORAGE_TYPE.getName(), address));
                  log.error("Could not get userMetadata metadata after login", ncError);
                  vertx.cancelTimer(periodicId);
                }
              } catch (Exception e) {
                eb_send(
                    blockingSegment,
                    WebSocketManager.address + ".storageAddError",
                    new JsonObject()
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.SESSION_ID.getName(), sessionId)
                        .put(Field.STORAGE_TYPE.getName(), address));
                log.error("Error on saving account: " + e.getMessage(), e);
                vertx.cancelTimer(periodicId);
              }
            });
          });
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.NOT_FOUND, e);
    }
  }

  public void stopPoll(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String url = jsonObject.getString(Field.URL.getName());

    try {
      long periodicId = NextcloudApi.deletePendingLogin(userId, url);

      if (periodicId != 0L) {
        vertx.cancelTimer(periodicId);
      }

      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doGetFolderContent(
      Message<JsonObject> message,
      boolean trash) { // done. todo: except for folders with spec chars
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    if (trash) {
      sendNotImplementedError(message);
    }
    long mills = System.currentTimeMillis();
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      String userId = jsonObject.getString(Field.USER_ID.getName());
      String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
      Boolean full = jsonObject.getBoolean(Field.FULL.getName());
      if (full == null) {
        full = true;
      }
      Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
      @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
      if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
        fileFilter = emptyString;
      }
      List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
      if (folderId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IdMustBeSpecified"),
            HttpStatus.INTERNAL_SERVER_ERROR);
        return;
      }
      String device = jsonObject.getString(Field.DEVICE.getName());
      boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
          || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
      JsonObject messageBody = message
          .body()
          .put("isAccountThumbnailDisabled", api.getBoolean("isAccountThumbnailDisabled"));
      boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;

      JsonArray thumbnail = new JsonArray();
      List<NextCloudMapping> nextCloudMappingObjectsToSave = new ArrayList<>();
      Map<String, String> possibleIdsToIds = new HashMap<>();

      String path = NextCloudMapping.getResourcePath(api, folderId);
      List<DavResource> resources = new NextcloudApi(api).getFolderContent(path);

      XRayEntityUtils.putMetadata(segment, XrayField.RESOURCES_SIZE, resources.size());

      // lets parallize this as this can be expense when we store new ids in db
      List<JsonObject> foldersJson = resources.parallelStream()
          .map(res -> {
            if (res.getContentType().equals("httpd/unix-directory")) {
              Entity subsegment = XRayManager.createSubSegment(
                  operationGroup, segment, "NEXTCLOUD.iterateFolderResults");
              String rp = NextCloudMapping.getScopedPath(api, res.getPath());
              if (rp.equals(path)) {
                return null;
              }
              NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
              String resourceId = nextCloudMapping.getReturnId();
              if (nextCloudMapping.isShouldBeSaved()) {
                nextCloudMappingObjectsToSave.add(nextCloudMapping);
              }

              XRayManager.endSegment(subsegment);
              return getFolderJson(resourceId, res, new JsonObject(), folderId, externalId, null);
            }
            return null;
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList());

      Map<String, JsonObject> filesJson = resources.parallelStream()
          .map(res -> {
            if (!res.getContentType().equals("httpd/unix" + "-directory")) {
              Entity subsegment = XRayManager.createSubSegment(
                  operationGroup, segment, "NEXTCLOUD" + ".iterateFolderResults");
              String filename = getResourceName(res);
              if (Extensions.isValidExtension(extensions, filename)) {
                NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
                String resourceId = nextCloudMapping.getReturnId();
                if (nextCloudMapping.isShouldBeSaved()) {
                  nextCloudMappingObjectsToSave.add(nextCloudMapping);
                }
                XRayManager.endSegment(subsegment);

                return getFileJson(
                    resourceId,
                    res,
                    new JsonObject(),
                    folderId,
                    false,
                    isAdmin,
                    externalId,
                    userId,
                    null);
              }

              XRayManager.endSegment(subsegment);
            }
            return null;
          })
          .filter(Objects::nonNull)
          .filter(j -> !j.isEmpty() && j.containsKey(Field.WS_ID.getName()))
          // DK: For me locally Collectors.toMap threw NPE.
          // Apparently, there were some duplicated ids
          // caused by some weird tests I've done
          // Because of this - I've used this "workaround"
          .collect(HashMap::new, NextCloud::collectFiles, HashMap::putAll);
      NextCloudMapping.saveListOfMappings(nextCloudMappingObjectsToSave);
      Map<String, JsonObject> updatedFilesJson = new HashMap<>();
      filesJson.forEach((k, v) -> {
        String filename = v.getString(Field.FILE_NAME.getName());
        boolean createThumbnail = canCreateThumbnails;
        if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
          if (!v.getBoolean(Field.VIEW_ONLY.getName())) {
            possibleIdsToIds.put(k, k);
          }
          thumbnail.add(new JsonObject()
              .put(Field.FILE_ID.getName(), k)
              .put(
                  Field.VERSION_ID.getName(),
                  parseVersionId(v.getLong(Field.UPDATE_DATE.getName())))
              .put("serverUrl", api.getString(Field.URL.getName()))
              .put(Field.EXT.getName(), Extensions.getExtension(filename)));
        } else {
          createThumbnail = false;
        }

        // DK: ignore this for now for nextcloud
        // we should probably find a better way
        // maybe bulk get or something
        v.put(
            "thumbnailStatus",
            createThumbnail
                ? ThumbnailsManager.ThumbnailStatus.LOADING
                : ThumbnailsManager.ThumbnailStatus.UNAVAILABLE);
        // v.put("thumbnailStatus", getThumbnailStatus(v.getString(Field.WS_ID.getName()),
        // api.getString
        // (Field.STORAGE_TYPE.getName()), v.getString(Field.VER_ID.getName()), force,
        // createThumbnail));
        updatedFilesJson.put(k, v);
      });

      // DK: we won't enable until we have a proper way that wouldn't break nextcloud.
      if (!thumbnail.isEmpty() && canCreateThumbnails) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }

      if (full && !possibleIdsToIds.isEmpty()) {
        Map<String, JsonObject> newSharedLinksResponse =
            PublicLink.getSharedLinks(config, segment, userId, externalId, possibleIdsToIds);
        for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
          if (updatedFilesJson.containsKey(fileData.getKey())) {
            updatedFilesJson.put(
                fileData.getKey(),
                updatedFilesJson.get(fileData.getKey()).mergeIn(fileData.getValue()));
          }
        }
      }

      foldersJson.sort(
          Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
      List<JsonObject> filesList = new ArrayList<>(updatedFilesJson.values());
      filesList.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject response = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesList))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put(Field.NUMBER.getName(), updatedFilesJson.size() + foldersJson.size())
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      boolean isTouch = AuthManager.ClientType.TOUCH.name().equals(device);
      boolean useNewStructure = message.body().getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
      if (isTouch && useNewStructure && folderId.equals(ROOT_FOLDER_ID)) {
        ObjectPermissions permissions = new ObjectPermissions()
            .setBatchTo(
                List.of(
                    AccessType.canMoveFrom, AccessType.canCreateFiles, AccessType.canCreateFolders),
                true);
        response.put(Field.PERMISSIONS.getName(), permissions.toJson());
      }
      sendOK(segment, message, response, mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      log.error(e);
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      log.error(e);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doCreateFolder(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    if (name == null || parentId == null) {
      return;
    }
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
    for (String specialCharacter : specialCharacters) {
      if (name.contains(specialCharacter)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "specialCharacters"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      List<DavResource> resources = new NextcloudApi(api)
          .createDirectory(NextCloudMapping.getResourcePath(api, parentId), name);

      if (resources.isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotCreateNewFolder"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      NextCloudMapping nextCloudMapping = new NextCloudMapping(api, resources.get(0));
      String id = nextCloudMapping.getReturnId();
      if (nextCloudMapping.isShouldBeSaved()) {
        nextCloudMapping.save();
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id)),
          mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doMoveFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doMove(message, id);
  }

  @Override
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
    if (Field.MINUS_1.getName().equals(parentId)) {
      parentId = emptyString;
    }
    if (id.equals(Field.MINUS_1.getName())) {
      sendError(segment, message, "cannot move root folder", HttpStatus.BAD_REQUEST);
      return;
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      // from
      String sourcePath = NextCloudMapping.getResourcePath(api, id);

      // to
      String parentPath = NextCloudMapping.getResourcePath(api, parentId);

      if (Objects.isNull(sourcePath) || Objects.isNull(parentPath)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "PathNotFound"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String destinationPath = parentPath
          + (parentPath.endsWith("/") ? emptyString : "/")
          + Utils.getFileNameFromPath(sourcePath);

      new NextcloudApi(api).move(sourcePath, destinationPath);

      // lets update the path to id mapping.
      new NextCloudMapping(
              id,
              api.getString(Field.EXTERNAL_ID.getName()),
              NextCloudMapping.getScopedPath(api, destinationPath),
              null)
          .update();
      sendOK(segment, message, mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doRenameFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doRename(message, id);
  }

  @Override
  public void doRenameFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doRename(message, id);
  }

  private void doRename(Message<JsonObject> message, String id) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    for (String specialCharacter : specialCharacters) {
      if (name != null && name.contains(specialCharacter)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "specialCharacters"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }
    if (id == null || name == null || name.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (id.equals(Field.MINUS_1.getName())) {
      sendError(segment, message, "cannot rename root folder", HttpStatus.BAD_REQUEST);
      return;
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String source = NextCloudMapping.getResourcePath(api, id);
      new NextcloudApi(api).rename(source, name);

      // lets update the path to id mapping.
      String parent = Utils.getFilePathFromPathWithSlash(source);
      String destination = parent + name;
      NextCloudMapping nextCloudMapping = new NextCloudMapping(
          id,
          api.getString(Field.EXTERNAL_ID.getName()),
          NextCloudMapping.getScopedPath(api, destination),
          null);
      nextCloudMapping.update();

      String newId = Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id);
      sendOK(segment, message, new JsonObject().put(Field.ENCAPSULATED_ID.getName(), newId), mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doDeleteFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doDelete(message, id);
  }

  @Override
  public void doDeleteFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doDelete(message, id);
  }

  private void doDelete(Message<JsonObject> message, String id) { // done
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
    if (id.equals(Field.MINUS_1.getName())) {
      sendError(segment, message, "Cannot delete root folder", HttpStatus.BAD_REQUEST);
      return;
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      new NextcloudApi(api).delete(NextCloudMapping.getResourcePath(api, id));

      NextCloudMapping.unsetResourceId(api, id);
      sendOK(segment, message, mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doClone(Message<JsonObject> message) { // no ui
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    boolean doCopyComments = message.body().getBoolean(Field.COPY_COMMENTS.getName(), false);
    boolean doCopyShare = message.body().getBoolean(Field.COPY_SHARE.getName(), false);
    boolean isFile = true;
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      id = message.body().getString(Field.FOLDER_ID.getName());
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
    if (id.equals(Field.MINUS_1.getName())) {
      sendError(segment, message, "cannot clone root folder", HttpStatus.BAD_REQUEST);
      return;
    }
    String name = message.body().getString(Field.FILE_NAME_C.getName());
    if (name == null) {
      name = message.body().getString(Field.FOLDER_NAME.getName());
    }
    if (name == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FilenameOrFoldernameMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String path = NextCloudMapping.getResourcePath(api, id);
      List<DavResource> resources = new NextcloudApi(api).clone(path, name);

      if (resources.isEmpty()) {
        if (isFile) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotCloneFile"),
              HttpStatus.BAD_REQUEST);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotCloneFolder"),
              HttpStatus.BAD_REQUEST);
        }
        return;
      }

      NextCloudMapping nextCloudMapping = new NextCloudMapping(api, resources.get(0));
      String originalNewId = nextCloudMapping.getReturnId();
      nextCloudMapping.save();

      String parent = Utils.getFilePathFromPathWithSlash(resources.get(0).getPath());

      NextCloudMapping parentNextCloudMapping = new NextCloudMapping(api, parent);
      parent = Utils.getEncapsulatedId(
          StorageType.getShort(storageType), externalId, parentNextCloudMapping.getReturnId());

      String newId = originalNewId;
      if (doCopyComments) {
        String finalNewId = newId;
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
                  finalNewId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }
      newId = Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, newId);
      if (doCopyShare && Objects.nonNull(path)) {
        boolean finalIsFile = isFile;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_SHARE.getName())
            .run((Segment blockingSegment) -> {
              JsonArray editor = new JsonArray();
              JsonArray viewer = new JsonArray();
              try {
                JsonObject share = new NextcloudApi(api)
                    .getShares(NextCloudMapping.getPureFilePath(
                        path, api.getString(Field.EMAIL.getName())));
                editor.addAll(share.getJsonArray(Field.EDITOR.getName()));
                viewer.addAll(share.getJsonArray(Field.VIEWER.getName()));
              } catch (NextcloudApi.NextcloudApiException e) {
                log.error("Error occurred in getting shares : " + e);
                return;
              }
              message
                  .body()
                  .put(Field.FOLDER_ID.getName(), originalNewId)
                  .put(Field.IS_FOLDER.getName(), !finalIsFile);
              // copy editors
              editor.forEach(user -> {
                message
                    .body()
                    .put(Field.ROLE.getName(), "EDIT")
                    .put(
                        Field.EMAIL.getName(),
                        ((JsonObject) user).getString(Field.EMAIL.getName()));
                doShare(message, false);
              });
              // copy viewers
              viewer.forEach(user -> {
                message
                    .body()
                    .put(Field.ROLE.getName(), "VIEW")
                    .put(
                        Field.EMAIL.getName(),
                        ((JsonObject) user).getString(Field.EMAIL.getName()));
                doShare(message, false);
              });
            });
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(), newId)
              .put(Field.PARENT_ID.getName(), parent),
          mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      JsonObject api = connect(segment, message, json, false);
      if (api == null) {
        return false;
      }

      return new NextcloudApi(api).checkFile(NextCloudMapping.getResourcePath(api, fileId));
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void doGetInfo(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String id = jsonObject.getString(Field.FILE_ID.getName());
    if (id == null) {
      id = jsonObject.getString(Field.FOLDER_ID.getName());
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
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      }
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
    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }
    try {
      if (id.equals(ROOT_FOLDER_ID)) {
        sendOK(
            segment,
            message,
            getRootFolderInfo(
                storageType,
                externalId,
                new ObjectPermissions()
                    .setBatchTo(
                        List.of(
                            AccessType.canMoveFrom,
                            AccessType.canCreateFiles,
                            AccessType.canCreateFolders),
                        true)),
            mills);
        return;
      }
      boolean full = true;
      if (jsonObject.containsKey(Field.FULL.getName())
          && jsonObject.getString(Field.FULL.getName()) != null) {
        full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
      }

      String path = NextCloudMapping.getResourcePath(api, id);
      List<DavResource> resources = new NextcloudApi(api).getInfo(path);
      JsonObject json = new JsonObject();

      if (id.isEmpty()) {
        // getting root
        DavResource rootInfo = resources.get(0);
        OwncloudPermission permission = new OwncloudPermission(rootInfo);
        json = new JsonObject()
            .put(Field.ENCAPSULATED_ID.getName(), Field.MINUS_1.getName())
            .put(Field.NAME.getName(), "~")
            .put(
                Field.UPDATE_DATE.getName(),
                rootInfo.getModified() != null ? rootInfo.getModified().getTime() : 0)
            .put(Field.PARENT.getName(), emptyString)
            .put(Field.OWNER.getName(), getOwner(rootInfo))
            .put(Field.OWNER_EMAIL.getName(), emptyString)
            .put(Field.OWNER_ID.getName(), getOwnerId(rootInfo))
            .put(
                Field.CREATION_DATE.getName(),
                rootInfo.getCreation() != null ? rootInfo.getCreation().getTime() : 0)
            .put(Field.SHARED.getName(), permission.isShared())
            .put(Field.VIEW_ONLY.getName(), !permission.canCreate())
            .put(Field.IS_OWNER.getName(), permission.isOwner())
            .put(Field.CAN_MOVE.getName(), permission.canMove())
            .put(
                Field.SHARE.getName(),
                new JsonObject()
                    .put(Field.VIEWER.getName(), new JsonArray())
                    .put(Field.EDITOR.getName(), new JsonArray()));
      } else {
        String parent = path;
        if (Objects.nonNull(parent) && parent.lastIndexOf("/") >= 0) {
          parent = Utils.getFilePathFromPathWithSlash(parent);
        } else {
          parent = "/";
        }

        List<NextCloudMapping> nextCloudMappingObjectsToSave = new ArrayList<>();
        for (DavResource res : resources) {
          String resPath = NextCloudMapping.getScopedPath(api, res.getPath());
          if (resPath.equals(path)) {
            NextCloudMapping parentNextCloudMapping = new NextCloudMapping(api, parent);
            NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
            String resourceId = nextCloudMapping.getReturnId();

            JsonObject share = new NextcloudApi(api)
                .getShares(
                    NextCloudMapping.getPureFilePath(path, api.getString(Field.EMAIL.getName())));

            if (res.getContentType().equals("httpd/unix-directory")) {
              json = getFolderJson(
                  resourceId, res, share, parentNextCloudMapping.getReturnId(), externalId, api);
            } else {
              json = getFileJson(
                  resourceId,
                  res,
                  share,
                  parentNextCloudMapping.getReturnId(),
                  full,
                  isAdmin,
                  externalId,
                  userId,
                  api);
              // AS : Removing this temporarily until we have some server cache (WB-1248)
              //              json.put("thumbnailStatus",
              //
              // ThumbnailsManager.getThumbnailStatus(json.getString(Field.WS_ID.getName()),
              //                      api.getString(Field.STORAGE_TYPE.getName()),
              // json.getString(Field.VER_ID.getName()),
              //                      false, false));
            }
            if (parentNextCloudMapping.isShouldBeSaved()) {
              nextCloudMappingObjectsToSave.add(parentNextCloudMapping);
            }
            if (nextCloudMapping.isShouldBeSaved()) {
              nextCloudMappingObjectsToSave.add(nextCloudMapping);
            }

            NextCloudMapping.saveListOfMappings(nextCloudMappingObjectsToSave);
            break;
          }
        }
      }
      sendOK(segment, message, json, mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doGetFile(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = message.body().getString(Field.VER_ID.getName());
    Boolean latest = message.body().getBoolean(Field.LATEST.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    if (latest == null) {
      latest = false;
    }
    Boolean download = message.body().getBoolean(Field.DOWNLOAD.getName());
    if (download == null) {
      download = false;
    }
    Integer start = message.body().getInteger(Field.START.getName()),
        end = message.body().getInteger(Field.END.getName());

    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      NextcloudApi nextcloudApi = new NextcloudApi(api);

      String name = null;
      Long size = null;
      long modifiedTime = -1L;
      String finalUrl;

      if (latest || versionId == null) {
        finalUrl = NextCloudMapping.getResourcePath(api, fileId);
        List<DavResource> resources = nextcloudApi.getInfo(finalUrl);

        if (!resources.isEmpty()) {
          DavResource res = resources.get(0);
          modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
          name = getResourceName(res);
          size = res.getContentLength();
        }
      } else {
        // 1. get file-id
        // 2. check if the version exists
        // 3. get the version if not, get the current file. Also cannot use the version api of
        // the current version.

        String nextcloudfileid = NextCloudMapping.getResourceFileId(api, fileId);
        finalUrl = NextCloudMapping.getResourcePath(api, fileId);
        String currentVersion = emptyString;

        List<DavResource> resources = nextcloudApi.getInfo(finalUrl);
        if (!resources.isEmpty()) {
          DavResource res = resources.get(0);
          nextcloudfileid = NextCloudMapping.getFileId(res);
          name = getResourceName(res);
          size = res.getContentLength();
          currentVersion = parseVersionId(res.getModified().getTime());
        }

        if (!nextcloudfileid.isEmpty() && !currentVersion.equals(versionId)) {
          // http://nextcloud.dev.graebert.com/remote.php/dav/versions/{user}/versions/{file-id}/versionId
          String versionPath = "/remote.php/dav/versions/" + api.getString(Field.EMAIL.getName())
              + "/versions/" + nextcloudfileid + "/" + versionId;

          resources = nextcloudApi.getVersion(nextcloudfileid, versionId);
          if (!resources.isEmpty()) {
            finalUrl = versionPath;
            DavResource res = resources.get(0);
            modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
          }
        }
      }
      if (returnDownloadUrl && Utils.isStringNotNullOrEmpty(finalUrl)) {
        String encodedPath =
            nextcloudApi.encodePath(api.getString(Field.URL.getName()).concat(finalUrl));
        String credentials =
            api.getString(Field.EMAIL.getName()) + ":" + api.getString(Field.PASSWORD.getName());
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        sendDownloadUrl(
            segment, message, encodedPath, size, versionId, encodedCredentials, null, mills);
        return;
      }
      byte[] bytes;
      try (InputStream in = nextcloudApi.getObjectData(finalUrl)) {
        bytes = IOUtils.toByteArray(in);
      }
      finishGetFile(
          message,
          start,
          end,
          bytes,
          storageType,
          name,
          versionId != null ? versionId : parseVersionId(modifiedTime),
          downloadToken);
    } catch (SardineException e) {
      if (Utils.isStringNotNullOrEmpty(downloadToken)) {
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      }
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
      return;
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
      return;
    }
    log.warn("### doGetFile END ### " + fileId
        + (latest
            ? " latest "
            : (download ? " download " : ((start != null && start >= 0) ? " start " : " else "))));
    XRayManager.endSegment(segment);
  }

  @Override
  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();

    String name = body.getString(Field.NAME.getName());
    if (name != null) {
      String onlyDotsInName = name;
      onlyDotsInName = onlyDotsInName.replaceAll("\\.", emptyString);
      if (onlyDotsInName.equals("dwg")) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "onlyDotsInName"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      for (String specialCharacter : specialCharacters) {
        if (name.contains(specialCharacter)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "specialCharacters"),
              HttpStatus.BAD_REQUEST);
          return;
        }
      }
    }
    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
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
    String newFileId = fileId;
    // message not ok to make connect, because it contains buffer info
    JsonObject api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }
    String newName = name;
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      String versionId = null;
      long modifiedTime = 0;
      String oldName = name;

      if (parsedMessage.hasAnyContent()) {
        NextcloudApi nextcloudApi = new NextcloudApi(api);
        String path = NextCloudMapping.getResourcePath(api, folderId);

        if (fileId != null) {
          path = NextCloudMapping.getResourcePath(api, fileId);

          List<DavResource> resources = null;
          try {
            resources = nextcloudApi.getInfo(path);

          } catch (NextcloudApi.NextcloudApiException ex) {
            if (!ex.getMessage().contains("404 Not Found")) {
              sendError(
                  segment,
                  message,
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "SomethingWentWrong"),
                      ex.getLocalizedMessage()),
                  HttpStatus.BAD_REQUEST);
              return;
            }
          }
          if (Objects.nonNull(resources) && !resources.isEmpty()) {
            DavResource res = resources.get(0);
            versionId = parseVersionId(res.getModified().getTime());
            name = res.getName();

            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)
                && !new OwncloudPermission(res).canWrite()) {
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
          } else {
            conflictingFileReason =
                XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
            isConflictFile = true;
            fileNotFound = true;
          }
          if (!fileNotFound) {
            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
              isConflictFile =
                  isConflictingChange(userId, fileId, storageType, versionId, baseChangeId);
              if (isConflictFile) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
              }
            }
          }
          if (isConflictFile) {
            // create a new file and save it beside original one
            if (!Utils.isStringNotNullOrEmpty(oldName)) {
              Item metaData = FileMetaData.getMetaData(fileId, storageType.name());
              if (Objects.nonNull(metaData)) {
                oldName = metaData.getString(Field.FILE_NAME.getName());
              }
              if (oldName == null) {
                oldName = unknownDrawingName;
              }
            }
            newName = getConflictingFileName(oldName);
            path = NextCloudMapping.getResourcePath(api, folderId);
            if (!path.endsWith("/") && !path.isEmpty()) {
              path += "/";
            }
            path += newName;
          }
        } else {
          if (!path.endsWith("/") && !path.isEmpty()) {
            path += "/";
          }
          path += name;
        }

        List<DavResource> resources = nextcloudApi.upload(path, stream);
        if (!resources.isEmpty()) {
          NextCloudMapping nextCloudMapping = new NextCloudMapping(api, resources.get(0));
          if (nextCloudMapping.isShouldBeSaved()) {
            nextCloudMapping.save();
          }
          newFileId = nextCloudMapping.getReturnId();
          modifiedTime = resources.get(0).getModified().getTime();
        }
        if (isConflictFile) {
          handleConflictingFile(
              segment,
              message,
              body,
              oldName,
              newName,
              Utils.getEncapsulatedId(storageType, externalId, fileId),
              Utils.getEncapsulatedId(storageType, externalId, newFileId),
              xSessionId,
              userId,
              null,
              conflictingFileReason,
              fileSessionExpired,
              true,
              AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
        }
      }
      versionId = parseVersionId(modifiedTime);
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), newFileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }
      JsonObject response = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, newFileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              ThumbnailsManager.getThumbnailName(
                  storageType, newFileId, String.valueOf(modifiedTime)));
      if (isConflictFile && Utils.isStringNotNullOrEmpty(newName)) {
        response.put(Field.NAME.getName(), newName);
      }
      if (isConflictFile && Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
        response.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
      }

      // for file save-as case
      if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
        String finalCloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
            .getString(Field.FILE_ID.getName());
        String finalNewFileId = newFileId;
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
                  finalNewFileId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }

      sendOK(segment, message, response, mills);

      if (isFileUpdate && parsedMessage.hasAnyContent()) {
        eb_send(
            segment,
            WebSocketManager.address + ".newVersion",
            new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
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
    } catch (NextcloudApi.NextcloudApiException ex) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), ex.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();

    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String name = body.getString(Field.NAME.getName());
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
    String newFileId = fileId;
    // message not ok to make connect, because it contains buffer info
    JsonObject api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      long modifiedTime = 0;
      String path = NextCloudMapping.getResourcePath(api, fileId);
      List<DavResource> resources = new NextcloudApi(api).upload(path, stream);
      if (!resources.isEmpty()) {
        NextCloudMapping nextCloudMapping = new NextCloudMapping(api, resources.get(0));
        newFileId = nextCloudMapping.getReturnId();
        if (nextCloudMapping.isShouldBeSaved()) {
          nextCloudMapping.save();
        }
        modifiedTime = resources.get(0).getModified().getTime();
      }
      String versionId = parseVersionId(modifiedTime);
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), newFileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      }
      sendOK(
          segment, message, getVersionInfo(fileId, resources.get(0), null, true).toJson(), mills);
      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), newFileId)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FORCE.getName(), true)
              .put(Field.EMAIL.getName(), userEmail)
              .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (NextcloudApi.NextcloudApiException ex) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), ex.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
  }

  @Override
  public void doGetFileByToken(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());

    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    JsonObject api;

    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(item);
    if (api != null) {
      try {
        String name = null;
        long modifiedTime = 0, size = 0;

        NextcloudApi nextcloudApi = new NextcloudApi(api);
        String path = NextCloudMapping.getResourcePath(api, fileId);

        // get info
        List<DavResource> resources = nextcloudApi.getInfo(path);
        if (!resources.isEmpty()) {
          DavResource res = resources.get(0);
          size = res.getContentLength();
          modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
          name = getResourceName(res);
        }
        String versionId = parseVersionId(modifiedTime);
        if (returnDownloadUrl && Utils.isStringNotNullOrEmpty(path)) {
          String encodedPath =
              nextcloudApi.encodePath(api.getString(Field.URL.getName()).concat(path));
          String credentials =
              api.getString(Field.EMAIL.getName()) + ":" + api.getString(Field.PASSWORD.getName());
          String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
          sendDownloadUrl(
              segment,
              message,
              encodedPath,
              size,
              versionId,
              encodedCredentials,
              null,
              mills);
          return;
        }
        byte[] bytes;
        try (InputStream in = nextcloudApi.getObjectData(path)) {
          bytes = IOUtils.toByteArray(in);
        }
        finishGetFile(
            message,
            null,
            null,
            bytes,
            StorageType.getStorageType(storageType),
            name,
            versionId,
            downloadToken);

        message
            .body()
            .put(Field.VER_ID.getName(), versionId)
            .put(Field.DATA.getName(), bytes)
            .put(Field.NAME.getName(), name)
            .put(Field.UPDATE_DATE.getName(), modifiedTime)
            .put(Field.SIZE.getName(), size)
            .put(Field.CHANGER.getName(), emptyString)
            .put(Field.IS_OWNER.getName(), true)
            .put(Field.STORAGE_TYPE.getName(), storageType);
        message.reply(message.body());
        XRayManager.endSegment(segment);
        return;
      } catch (Exception e) {
        log.error("Error on getting nextcloud file's data by token", e);
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      }
    }
    sendError(
        segment,
        message,
        Utils.getLocalizedString(message, "CouldNotGetTheFileData"),
        HttpStatus.BAD_REQUEST);
  }

  @Override
  public void doGetThumbnail(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      long modifiedTime = 0;

      // get info
      List<DavResource> resources =
          new NextcloudApi(api).getInfo(NextCloudMapping.getResourcePath(api, fileId));
      String filename = emptyString;
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
        filename = res.getName();
      }
      if (modifiedTime == 0) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND);
        return;
      }
      String versionId = parseVersionId(modifiedTime);
      eb_send(
          segment,
          ThumbnailsManager.address + ".create",
          message
              .body()
              .put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(
                  Field.IDS.getName(),
                  new JsonArray()
                      .add(new JsonObject()
                          .put(Field.FILE_ID.getName(), fileId)
                          .put(Field.VERSION_ID.getName(), versionId)
                          .put(Field.EXT.getName(), Extensions.getExtension(filename))))
              .put(Field.FORCE.getName(), true));

      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, fileId, versionId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      fileId, api.getString(Field.STORAGE_TYPE.getName()), versionId, true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, false))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", false)),
          mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doGetInfoByToken(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String token = jsonObject.getString(Field.TOKEN.getName());
    String userId = jsonObject.getString(Field.OWNER_ID.getName());
    Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    String creatorId = jsonObject.getString(Field.CREATOR_ID.getName());
    String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, jsonObject);
    if (fileId == null) {
      return;
    }
    JsonObject api;
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    if (item != null) {
      api = connect(item);
      if (api != null) {
        try {
          long modifiedTime = 0;

          // get info
          List<DavResource> resources =
              new NextcloudApi(api).getInfo(NextCloudMapping.getResourcePath(api, fileId));
          String filename = emptyString;
          if (!resources.isEmpty()) {
            DavResource res = resources.get(0);
            modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
            filename = getResourceName(res);
          }
          String thumbnailName =
              StorageType.getShort(storageType) + "_" + fileId + "_" + modifiedTime + ".png";
          JsonObject json = new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, fileId))
              .put(Field.WS_ID.getName(), fileId)
              .put(
                  Field.SHARE.getName(),
                  new JsonObject()
                      .put(Field.VIEWER.getName(), new JsonArray())
                      .put(Field.EDITOR.getName(), new JsonArray()))
              .put(Field.VIEW_ONLY.getName(), true)
              .put(Field.PUBLIC.getName(), true)
              .put(Field.IS_OWNER.getName(), false)
              .put(Field.DELETED.getName(), false)
              .put(
                  Field.LINK.getName(),
                  config.getProperties().getUrl() + "file/" + fileId + "?token=" + token) // NON-NLS
              .put(
                  Field.FILE_NAME.getName(),
                  filename.isEmpty()
                      ? Field.UNKNOWN.getName()
                      : filename) // todo maybe check the name on the
              // server
              .put(Field.VERSION_ID.getName(), parseVersionId(modifiedTime))
              .put(Field.VER_ID.getName(), parseVersionId(modifiedTime))
              .put(Field.CREATOR_ID.getName(), creatorId)
              .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(Field.EXPORT.getName(), export)
              .put(Field.UPDATE_DATE.getName(), modifiedTime)
              .put(Field.CHANGER.getName(), emptyString);
          sendOK(segment, message, json, mills);
          return;
        } catch (Exception ignored) {
        }
      }
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  @Override
  public void doCreateSharedLink(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
    Boolean export = body.getBoolean(Field.EXPORT.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
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
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, body, storageType);
      if (externalId != null) {
        body.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String name = null;

      // get info
      List<DavResource> resources =
          new NextcloudApi(api).getInfo(NextCloudMapping.getResourcePath(api, fileId));
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        name = getResourceName(res);
      }
      if (name == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND);
        return;
      }

      List<String> collaboratorsList =
          Collections.singletonList(api.getString(Field.EMAIL.getName()));

      try {
        final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
        final String externalEmail = ExternalAccounts.getExternalEmail(userId, externalId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            externalId,
            userId,
            storageType,
            externalEmail,
            name,
            export,
            endTime,
            password);

        newLink.setCollaboratorsList(collaboratorsList);
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
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.EXPORT.getName(), newLink.getExport())
                  .put(Field.TOKEN.getName(), newLink.getToken()));
        }
      } catch (PublicLinkException ple) {
        sendError(segment, message, ple.toResponseObject(), HttpStatus.BAD_REQUEST);
      }

    } catch (NextcloudApi.NextcloudApiException e) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doDeleteSharedLink(Message<JsonObject> message) { // done
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
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST);
    }
  }

  @Override
  public void doGetFolderPath(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      JsonArray result = new JsonArray();
      JsonObject api = connect(segment, message);
      if (api == null) {
        return;
      }
      if (!id.equals(Field.MINUS_1.getName())) {
        String fullPath = NextCloudMapping.getResourcePath(api, id);

        if (fullPath.endsWith("/")) {
          fullPath = fullPath.substring(0, fullPath.length() - 1);
        }

        NextcloudApi nextcloudApi = new NextcloudApi(api);

        while ((!fullPath
            .substring(fullPath.lastIndexOf("/") + 1)
            .equals(api.getString(Field.EMAIL.getName())))) {
          DavResource res = nextcloudApi.getInfo(fullPath).get(0);

          result.add(new JsonObject()
              .put(Field.NAME.getName(), getResourceName(res))
              .put(Field.VIEW_ONLY.getName(), !new OwncloudPermission(res).canCreate())
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType,
                      api.getString(Field.EXTERNAL_ID.getName()),
                      new NextCloudMapping(api, res).getReturnId())));

          fullPath = Utils.getFilePathFromPath(fullPath);
        }
      }

      result.add(new JsonObject()
          .put(Field.NAME.getName(), "~")
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(
                  storageType, api.getString(Field.EXTERNAL_ID.getName()), Field.MINUS_1.getName()))
          .put(Field.VIEW_ONLY.getName(), false));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST);
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
    JsonObject api = connect(segment, message);
    if (api == null) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    NextcloudApi nextcloudApi = new NextcloudApi(api);
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream stream = new ZipOutputStream(bos);
    Set<String> fileNames = new HashSet<>();
    Set<String> folderNames = new HashSet<>();
    Set<String> filteredFileNames = new HashSet<>();
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
        ObjectType type =
            ObjectType.valueOf(object.getString(Field.OBJECT_TYPE.getName()).toUpperCase());

        String objectId = Utils.parseItemId(
                object.getString(Field.ID.getName()), Field.OBJECT_ID.getName())
            .getString(Field.OBJECT_ID.getName());
        boolean isFolder = type.equals(ObjectType.FOLDER);
        callables.add(() -> {
          Entity blockingSegment =
              XRayManager.createSubSegment(operationGroup, subSegment, "MultipleDownloadSegment");
          String nextcloudPath = NextCloudMapping.getResourcePath(api, objectId);
          if (!Utils.isStringNotNullOrEmpty(nextcloudPath)) {
            log.warn("NC path not found, skip the download for this item - " + objectId);
            XRayManager.endSegment(blockingSegment);
            return null;
          }
          List<DavResource> resources;
          try {
            resources = nextcloudApi.getInfo(nextcloudPath);
          } catch (NextcloudApi.NextcloudApiException err) {
            log.error("[ZIP] Nextcloud get info: " + nextcloudPath, err);
            return null;
          }
          if (!resources.isEmpty()) {
            DavResource res = resources.get(0);
            String name = getResourceName(res);
            if (isFolder) {
              name = Utils.checkAndRename(folderNames, name, true);
              folderNames.add(name);
              zipFolder(
                  stream, api, objectId, filter, recursive, name, new HashSet<>(), 0, request);
            } else {
              long fileSize = res.getContentLength();
              if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
                excludeFileFromRequest(request, name, ExcludeReason.Large);
                return null;
              }
              addZipEntry(
                  stream,
                  res,
                  nextcloudApi,
                  api,
                  emptyString,
                  filter,
                  filteredFileNames,
                  fileNames);
            }
          }
          XRayManager.endSegment(blockingSegment);
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

  @Override
  public void doRequestFolderZip(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String requestId = message.body().getString(Field.REQUEST_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    String filter = message.body().getString(Field.FILTER.getName());
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
    JsonObject api = connect(segment, message);
    if (api == null) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    ZipOutputStream stream = new ZipOutputStream(bos);
    try {
      Future<Void> futureTask = executorService.submit(() -> {
        Entity subSegment = XRayManager.createStandaloneSegment(
            operationGroup, segment, "NextCloudZipFolderSegment");
        zipFolder(
            stream, api, folderId, filter, recursive, emptyString, new HashSet<>(), 0, request);
        XRayManager.endSegment(subSegment);
        return null;
      });
      sendOK(segment, message);
      finishDownloadZip(message, segment, s3Regional, stream, bos, futureTask, request);
    } catch (Exception ex) {
      ZipRequests.setRequestException(message, request, ex);
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      JsonObject api,
      String folderId,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception { // done
    NextcloudApi nextcloudApi = new NextcloudApi(api);
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    String nextcloudPath = NextCloudMapping.getResourcePath(api, folderId);
    if (!Utils.isStringNotNullOrEmpty(nextcloudPath)) {
      log.warn("NC path not found, skip the download for this item - " + folderId);
      return;
    }
    List<DavResource> resources;
    try {
      resources = nextcloudApi.getFolderContent(nextcloudPath);
    } catch (NextcloudApi.NextcloudApiException err) {
      log.error("[ZIP] Nextcloud get folder content: " + nextcloudPath, err);
      return;
    }
    if (resources.isEmpty()
        || (resources.size() == 1 && resources.get(0).getName().equals(path))) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    List<NextCloudMapping> nextCloudMappingObjectsToSave = new ArrayList<>();
    for (DavResource res : resources) {
      String properPath = path.isEmpty() ? path : path + File.separator;
      String name = res.getName();
      if (res.getContentType().equals("httpd/unix-directory")) {
        NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
        String id = nextCloudMapping.getReturnId();
        if (nextCloudMapping.isShouldBeSaved()) {
          nextCloudMappingObjectsToSave.add(nextCloudMapping);
        }
        if (!folderId.equals(id) && recursive) {
          name = Utils.checkAndRename(folderNames, name, true);
          folderNames.add(name);
          if (recursionDepth <= MAX_RECURSION_DEPTH) {
            recursionDepth += 1;
            zipFolder(
                stream,
                api,
                id,
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
      } else {
        long fileSize = res.getContentLength();
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, res.getName(), ExcludeReason.Large);
          return;
        }
        addZipEntry(
            stream, res, nextcloudApi, api, properPath, filter, filteredFileNames, fileNames);
      }
    }
    NextCloudMapping.saveListOfMappings(nextCloudMappingObjectsToSave);
  }

  private void addZipEntry(
      ZipOutputStream stream,
      DavResource res,
      NextcloudApi nextcloudApi,
      JsonObject api,
      String properPath,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames)
      throws NextcloudApi.NextcloudApiException, IOException {
    String name = res.getName();
    byte[] bytes;
    try (InputStream in =
        nextcloudApi.getObjectData(NextCloudMapping.getScopedPath(api, res.getPath()))) {
      bytes = IOUtils.toByteArray(in);
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
      stream.write(bytes);
      stream.closeEntry();
      stream.flush();
    }
  }

  @Override
  public void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String query = getRequiredString(segment, Field.QUERY.getName(), message, message.body());
    boolean isAdmin = message.body().getBoolean(Field.IS_ADMIN.getName());
    if (userId == null || query == null) {
      return;
    }
    try {
      Iterator<Item> accounts =
          ExternalAccounts.getExternalAccountsByUserId(userId, storageType.name());

      List<Item> array = StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(accounts, 0), false)
          .collect(Collectors.toList());

      JsonArray result = new JsonArray(array.parallelStream()
          .map(nextcloudUser -> {
            final String externalId = nextcloudUser.getString(ExternalAccounts.sk);
            Entity subSegment = XRayManager.createSubSegment(operationGroup, segment, externalId);
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            JsonObject api = connect(nextcloudUser);
            if (api == null) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(Field.NAME.getName(), nextcloudUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            try {
              List<DavResource> resources = new NextcloudApi(api).search(query);
              List<NextCloudMapping> nextCloudMappingObjectsToSave = new ArrayList<>();
              for (DavResource res : resources) {
                String parent = res.getPath();
                if (parent.lastIndexOf("/") >= 0) {
                  parent = Utils.getFilePathFromPathWithSlash(parent);
                } else {
                  parent = "/";
                }
                boolean isFile = false;
                String fileName = res.getName();
                for (Object d : fileFormats) {
                  String format = (String) d;
                  if (fileName.endsWith(format)) {
                    isFile = true;
                    break;
                  }
                }
                NextCloudMapping parentNextCloudMapping = new NextCloudMapping(api, parent);
                NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
                String resourceId = nextCloudMapping.getReturnId();
                if (!isFile) {
                  JsonObject obj = getFolderJson(
                      resourceId,
                      res,
                      new JsonObject(),
                      parentNextCloudMapping.getReturnId(),
                      externalId,
                      null);
                  foldersJson.add(obj);
                } else {
                  JsonObject json = getFileJson(
                      resourceId,
                      res,
                      new JsonObject(),
                      parentNextCloudMapping.getReturnId(),
                      false,
                      isAdmin,
                      externalId,
                      userId,
                      null);
                  // AS : Removing this temporarily until we have some server cache (WB-1248)
                  //              json.put("thumbnailStatus",
                  //
                  // ThumbnailsManager.getThumbnailStatus(json.getString(Field.WS_ID.getName()),
                  //                      api.getString(Field.STORAGE_TYPE.getName()),
                  // json.getString(Field.VER_ID.getName()),
                  //                      false, false));
                  filesJson.add(json);
                }
                if (parentNextCloudMapping.isShouldBeSaved()) {
                  nextCloudMappingObjectsToSave.add(parentNextCloudMapping);
                }
                if (nextCloudMapping.isShouldBeSaved()) {
                  nextCloudMappingObjectsToSave.add(nextCloudMapping);
                }
              }
              NextCloudMapping.saveListOfMappings(nextCloudMappingObjectsToSave);
            } catch (NextcloudApi.NextcloudApiException e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.EXTERNAL_ID.getName(), nextcloudUser.getString(Field.SK.getName()))
                  .put(Field.NAME.getName(), nextcloudUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(Field.EXTERNAL_ID.getName(), nextcloudUser.getString(Field.SK.getName()))
                .put(Field.NAME.getName(), nextcloudUser.getString(Field.EMAIL.getName()))
                .put(Field.FILES.getName(), filesJson)
                .put(Field.FOLDERS.getName(), foldersJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  @Override
  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    try {
      if (collaborators.isEmpty()) {
        String externalId = findExternalId(segment, message, jsonObject, storageType);
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          collaborators.add(externalId);
        }
      }
      // take collaborator
      for (Object externalId : collaborators) {
        final String exId = (String) externalId;

        JsonObject xrefsCache = findCachedXrefs(
            segment, message, jsonObject, storageType, userId, (String) externalId, fileId, path);

        // try to find an account
        Item dbUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
        // account exists
        if (dbUser != null) {
          // create connection in api
          JsonObject api = connect(dbUser);
          if (api == null) {
            continue;
          }

          JsonArray results = new JsonArray();

          if (!xrefsCache.getJsonArray("unknownXrefs").isEmpty()) {
            // parsing current folder
            String currentFolder = null;
            if (requestFolderId != null) {
              currentFolder = NextCloudMapping.getResourcePath(api, requestFolderId);
              if (!currentFolder.endsWith("/")) {
                currentFolder += "/";
              }
            }
            if (currentFolder == null) {
              String filePath = NextCloudMapping.getResourcePath(api, fileId);
              if (filePath.lastIndexOf("/") == -1) {
                sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
                return;
              }
              currentFolder = filePath.substring(0, filePath.lastIndexOf("/") + 1);
            }
            List<String> pathList = xrefsCache.getJsonArray("unknownXrefs").getList();
            String finalCurrentFolder = currentFolder;
            results = new JsonArray(pathList.parallelStream()
                .map(pathStr -> {
                  // try to generate result path of a file
                  Entity subSegment =
                      XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
                  if (pathStr == null) {
                    XRayManager.endSegment(subSegment);
                    return null;
                  }
                  JsonArray pathFiles = new JsonArray();
                  String[] array = Utils.parseRelativePath(pathStr);
                  String folderPath =
                      finalCurrentFolder.substring(0, finalCurrentFolder.length() - 1);
                  String resultPath = null;
                  // filename (e.g. file.dwg)
                  String endPart = pathStr.contains("..")
                      ? pathStr.substring(pathStr.lastIndexOf("..") + 3)
                      : pathStr;
                  if (array.length == 1 || (array.length == 2 && array[0].trim().isEmpty())) {
                    resultPath = finalCurrentFolder + array[array.length - 1]; // pathStr;
                  } else {
                    for (String s : array) {
                      if (s.isEmpty()) {
                        continue;
                      }
                      // file in parent (e.g. ../file.dwg) - each .. removes one folder from
                      // folderPath
                      if ("..".equals(s)) {
                        try {
                          folderPath = folderPath.substring(0, folderPath.lastIndexOf("/"));
                        } catch (Exception e) {
                          log.error(e);
                          break;
                        }
                        // subfolder (e.g. subfolder/file.dwg)
                      } else {
                        resultPath = folderPath + "/" + endPart;
                        break;
                      }
                    }
                  }
                  // we have result path
                  if (resultPath != null) {
                    findFileInFolder(api, resultPath, pathFiles, exId);
                  }
                  // no result path, try to find in root folder
                  if (pathFiles.isEmpty()) {
                    String rootPath =
                        finalCurrentFolder.substring(0, finalCurrentFolder.length() - 1) + "/"
                            + endPart;
                    if (!rootPath.equals(resultPath)) {
                      findFileInFolder(api, rootPath, pathFiles, exId);
                    }
                  }
                  XRayManager.endSegment(subSegment);
                  saveXrefToCache(storageType, userId, exId, fileId, pathStr, pathFiles);
                  return new JsonObject()
                      .put(Field.PATH.getName(), pathStr)
                      .put(Field.FILES.getName(), pathFiles);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
          }

          results.addAll(xrefsCache.getJsonArray("foundXrefs"));
          sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
          return;
        }
      }
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private void findFileInFolder(
      JsonObject api, String resultPath, JsonArray pathFiles, String externalId) {
    try {
      List<DavResource> resources = new NextcloudApi(api).getInfo(resultPath);

      List<NextCloudMapping> nextCloudMappingObjectsToSave = new ArrayList<>();
      for (DavResource res : resources) {
        long updateDate = res.getModified() != null ? res.getModified().getTime() : 0;
        long size = res.getContentLength();
        String name = getResourceName(res);
        NextCloudMapping nextCloudMapping = new NextCloudMapping(api, res);
        String resultId = nextCloudMapping.getReturnId();
        if (nextCloudMapping.isShouldBeSaved()) {
          nextCloudMappingObjectsToSave.add(nextCloudMapping);
        }
        String id = Utils.getEncapsulatedId(storageType, externalId, resultId);

        pathFiles.add(new JsonObject()
            .put(Field.ENCAPSULATED_ID.getName(), id)
            .put(Field.OWNER.getName(), getOwner(res))
            .put(Field.IS_OWNER.getName(), new OwncloudPermission(res).isOwner())
            .put(Field.UPDATE_DATE.getName(), updateDate)
            .put(Field.CHANGER.getName(), emptyString)
            .put(Field.SIZE.getName(), Utils.humanReadableByteCount(size))
            .put(Field.SIZE_VALUE.getName(), size)
            .put(Field.NAME.getName(), name)
            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
        break;
      }

    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in NextCloud", e);
    }
  }

  @Override
  public void doCheckPath(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    if (fileId == null && folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String currentFolder = NextCloudMapping.getResourcePath(api, folderId);
      if (currentFolder == null) {
        String filePath = NextCloudMapping.getResourcePath(api, fileId);
        if (filePath.lastIndexOf("/") == -1) {
          sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
          return;
        }
        currentFolder = Utils.getFilePathFromPathWithSlash(filePath);
      }
      String finalCurrentFolder = currentFolder;

      List<String> pathList = path.getList();
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            try {
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
              String fId = finalCurrentFolder.substring(0, finalCurrentFolder.length() - 1);
              String resultPath = null;
              // filename (e.g. file.dwg)
              if (array.length == 1 || (array.length == 2 && array[0].trim().isEmpty())) {
                resultPath = finalCurrentFolder + "/" + pathStr;
              } else {
                for (String s : array) {
                  if (s.isEmpty()) {
                    continue;
                  }
                  // file in parent (e.g. ../file.dwg) - each .. removes one folder from folderId
                  if ("..".equals(s)) {
                    try {
                      fId = fId.substring(0, fId.lastIndexOf("/"));
                    } catch (Exception e) {
                      log.error(e);
                      break;
                    }
                  }
                  // subfolder (e.g. subfolder/file.dwg)
                  else {
                    resultPath = fId + "/"
                        + (pathStr.contains("..")
                            ? pathStr.substring(pathStr.lastIndexOf("..") + 3)
                            : pathStr);
                    break;
                  }
                }
              }
              if (resultPath == null) {
                XRayManager.endSegment(subSegment);
                return new JsonObject()
                    .put(Field.PATH.getName(), pathStr)
                    .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
              }
              List<DavResource> resources = new NextcloudApi(api).getInfo(resultPath);

              boolean exists = false;
              for (DavResource res : resources) {
                String resPath = NextCloudMapping.getScopedPath(api, res.getPath());
                if (resPath.endsWith("/")) {
                  resPath = resPath.length() == 1
                      ? emptyString
                      : resPath.substring(0, resPath.length() - 1);
                }
                if (resPath.equals(resultPath)) {
                  exists = true;
                  break;
                }
              }
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(
                      Field.STATE.getName(),
                      exists ? Field.UNAVAILABLE.getName() : Field.AVAILABLE.getName());
            } catch (Exception e) {
              XRayManager.endSegment(subSegment);
              boolean available = e instanceof SardineException
                  && ((SardineException) e).getStatusCode() == HttpStatus.NOT_FOUND;
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(
                      Field.STATE.getName(),
                      available ? Field.AVAILABLE.getName() : Field.UNAVAILABLE.getName());
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void doDisconnect(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);

    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String externalId = getRequiredString(segment, Field.ID.getName(), message, message.body());
    Item externalUser = ExternalAccounts.getExternalAccount(userId, externalId);
    if (externalUser == null) {
      XRayManager.endSegment(segment);
      return;
    }

    try {
      new NextcloudApi(
              externalUser.getString(Field.URL.getName()),
              externalUser.getString(Field.EMAIL.getName()),
              EncryptHelper.decrypt(
                  externalUser.getString(Field.PASSWORD.getName()),
                  config.getProperties().getFluorineSecretKey()))
          .deleteAppPassword();
    } catch (Exception e) {
      log.error("Error on deleting NextCloud password: " + e.getMessage(), e);
    }

    NextCloudMapping.removeAllMappings(externalId);
    ExternalAccounts.deleteExternalAccount(userId, externalId);
    XRayManager.endSegment(segment);
  }

  @Override
  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String versionId = jsonObject.getString(Field.VER_ID.getName());

    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }
    try {
      String nextcloudFileId = NextCloudMapping.getResourceFileId(api, fileId);
      String path = NextCloudMapping.getResourcePath(api, fileId);

      NextcloudApi nextcloudApi = new NextcloudApi(api);
      List<DavResource> resources = nextcloudApi.getInfo(path);

      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        nextcloudFileId = NextCloudMapping.getFileId(res);
      }

      if (!nextcloudFileId.isEmpty()) {
        nextcloudApi.promote(nextcloudFileId, versionId);
      }

      long newVersionId = 0;
      try {
        for (DavResource res : nextcloudApi.getInfo(path)) {
          String resPath = NextCloudMapping.getScopedPath(api, res.getPath());
          if (resPath.equals(path)) {
            newVersionId = res.getModified() != null ? res.getModified().getTime() : 0;
          }
        }
      } catch (Exception ignored) {
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), parseVersionId(newVersionId)),
          mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doDeleteVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doShare(Message<JsonObject> message) {
    doShare(message, true);
  }

  public void doShare(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String objectId = getRequiredString(segment, Field.FOLDER_ID.getName(), message, jsonObject);
    String emailOrId = getRequiredString(segment, Field.EMAIL.getName(), message, jsonObject);
    String role = getRequiredString(segment, Field.ROLE.getName(), message, jsonObject);
    boolean isFile = jsonObject.containsKey(Field.IS_FOLDER.getName())
        && !jsonObject.getBoolean(Field.IS_FOLDER.getName());
    boolean isUpdate =
        jsonObject.containsKey("isUpdate") ? jsonObject.getBoolean("isUpdate") : false;

    if (objectId == null || emailOrId == null || role == null) {
      return;
    }

    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(
          objectId, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }

    NextcloudApi.NextcloudPermission permission =
        role.equalsIgnoreCase(NextcloudApi.NextcloudPermission.EDIT.toString())
            ? NextcloudApi.NextcloudPermission.EDIT
            : NextcloudApi.NextcloudPermission.VIEW;

    if (isUpdate) {
      try {
        new NextcloudApi(api).updateShare(emailOrId, isFile, permission);
        if (reply) {
          sendOK(segment, message, mills);
        }
      } catch (Exception ex) {
        if (reply) {
          sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
        }
      }
    } else {
      try {
        String path = NextCloudMapping.getResourcePath(api, objectId);

        new NextcloudApi(api)
            .addShare(
                NextCloudMapping.getPureFilePath(path, api.getString(Field.EMAIL.getName())),
                isFile,
                permission,
                emailOrId);

        if (reply) {
          sendOK(segment, message, mills);
        }
      } catch (Exception ex) {
        if (reply) {
          sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
        }
      }
    }
  }

  @Override
  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String objectId = getRequiredString(segment, Field.FOLDER_ID.getName(), message, jsonObject);
    String shareId = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);

    if (objectId == null || shareId == null) {
      return;
    }

    try {
      JsonObject api = connect(segment, message, jsonObject, false);
      if (api == null) {
        super.deleteSubscriptionForUnavailableFile(
            objectId, jsonObject.getString(Field.USER_ID.getName()));
        return;
      }

      new NextcloudApi(api).deleteShare(shareId);

      sendOK(segment, message, mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  @Override
  public void doRestore(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  private VersionInfo getVersionInfo(
      String fileId, DavResource resource, String versionId, boolean isLatest) {
    String hash = resource.getEtag();
    if (Utils.isStringNotNullOrEmpty(hash)) {
      hash = hash.replaceAll("^\"|\"$", emptyString);
    }
    long size = resource.getContentLength();
    long date = resource.getModified().getTime();

    if (!Utils.isStringNotNullOrEmpty(versionId)) {
      versionId = parseVersionId(date);
    }

    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(!isLatest);
    versionPermissions.setCanDelete(false);
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    VersionInfo versionInfo = new VersionInfo(versionId, date, null);
    versionInfo.setSize(size);
    versionInfo.setPermissions(versionPermissions);
    versionInfo.setHash(hash);
    try {
      String thumbnailName = ThumbnailsManager.getThumbnailName(storageType, fileId, versionId);
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  @Override
  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();

    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    if (externalId == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
      return;
    }

    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }

    try {
      JsonArray results = new JsonArray();
      String nextcloudFileId = NextCloudMapping.getResourceFileId(api, fileId);

      NextcloudApi nextcloudApi = new NextcloudApi(api);
      List<DavResource> resources =
          nextcloudApi.getInfo(NextCloudMapping.getResourcePath(api, fileId));

      String name = null;
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        nextcloudFileId = NextCloudMapping.getFileId(res);
        name = getResourceName(res);
        results.add(getVersionInfo(fileId, res, null, true).toJson());
      }

      if (!nextcloudFileId.isEmpty()) {
        resources = nextcloudApi.getVersions(nextcloudFileId);

        String root = emptyString;
        for (DavResource res : resources) {
          if (res.getContentType().equals("httpd/unix-directory")) {
            root = res.getPath();
          } else {
            results.add(getVersionInfo(fileId, res, res.getPath().substring(root.length()), false)
                .toJson());
          }
        }
        String finalName = name;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("createThumbnailsOnGetVersions")
            .run((Segment blockingSegment) -> {
              String ext = Extensions.DWG;
              try {
                if (Utils.isStringNotNullOrEmpty(finalName)) {
                  ext = Extensions.getExtension(finalName);
                }
              } catch (Exception ex) {
                log.warn(
                    "[NEXTCLOUD] get versions: Couldn't get object info to get extension.", ex);
              }
              jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
              JsonArray requiredVersions = new JsonArray();
              String finalExt = ext;
              results.forEach(revision -> requiredVersions.add(new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(
                      Field.VERSION_ID.getName(),
                      ((JsonObject) revision).getString(Field.ID.getName()))
                  .put(Field.EXT.getName(), finalExt)));
              eb_send(
                  blockingSegment,
                  ThumbnailsManager.address + ".create",
                  jsonObject.put(Field.IDS.getName(), requiredVersions));
            });
      }

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (NextcloudApi.NextcloudApiException e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Override
  public void doEraseAll(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doEraseFolder(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doEraseFile(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();

    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    if (externalId == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
      return;
    }

    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }

    try {
      List<DavResource> resources =
          new NextcloudApi(api).getInfo(NextCloudMapping.getResourcePath(api, fileId));

      for (DavResource res : resources) {
        sendOK(
            XRayManager.createSegment(operationGroup, message),
            message,
            new JsonObject()
                .put(
                    Field.VERSION_ID.getName(), parseVersionId(res.getModified().getTime())),
            System.currentTimeMillis());
        return;
      }
      sendOK(
          XRayManager.createSegment(operationGroup, message),
          message,
          new JsonObject().put(Field.VERSION_ID.getName(), emptyString),
          System.currentTimeMillis());

    } catch (NextcloudApi.NextcloudApiException e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), e.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private String getOwnerId(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey("owner-id")) {
      return props.get("owner-id");
    }
    return emptyString;
  }

  private String getOwner(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey("owner-display-name")) {
      return props.get("owner-display-name");
    }
    return emptyString;
  }

  private String getResourceName(DavResource res) {
    if (Utils.isStringNotNullOrEmpty(res.getDisplayName())) {
      return res.getDisplayName();
    } else if (Utils.isStringNotNullOrEmpty(res.getName())) {
      return res.getName();
    } else {
      String[] splits = res.getPath().split("/");
      return splits[splits.length - 1];
    }
  }

  private JsonObject getFolderJson(
      String id,
      DavResource res,
      JsonObject share,
      String parentId,
      String externalId,
      JsonObject api) {
    OwncloudPermission permission = new OwncloudPermission(res);
    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setBatchTo(
            List.of(
                AccessType.canClone, AccessType.canViewPublicLink, AccessType.canManagePublicLink),
            false)
        .setBatchTo(List.of(AccessType.canViewPermissions, AccessType.canManagePermissions), true)
        .setPermissionAccess(AccessType.canCreateFiles, permission.canCreateFile())
        .setPermissionAccess(AccessType.canCreateFolders, permission.canCreateFolder())
        .setPermissionAccess(AccessType.canDelete, permission.canDelete())
        .setPermissionAccess(AccessType.canRename, permission.canRename())
        // let's allow move only for owners
        // for now
        .setBatchTo(
            List.of(AccessType.canMove, AccessType.canMoveFrom, AccessType.canMoveTo),
            permission.isOwner());
    String ownerId = getOwnerId(res), owner = emptyString, ownerEmail = emptyString;
    if (Utils.isStringNotNullOrEmpty(ownerId) && Utils.isJsonObjectNotNullOrEmpty(api)) {
      String baseUrl = api.getString(Field.URL.getName());
      if (Utils.isStringNotNullOrEmpty(baseUrl)) {
        try {
          JsonObject metadataResponse = new NextcloudApi(
                  baseUrl,
                  api.getString(Field.EMAIL.getName()),
                  api.getString(Field.PASSWORD.getName()))
              .getUserMetadata(ownerId);
          owner = metadataResponse.getString(Field.DISPLAY_NAME.getName());
          ownerEmail = metadataResponse.getString(Field.EMAIL.getName());
        } catch (NextcloudApi.NextcloudApiException e) {
          log.warn("Could not get user metadata for owner info of a folder : " + e.getResponse());
        }
      }
    }
    if (owner.isEmpty()) {
      owner = getOwner(res);
    }

    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
        .put(Field.NAME.getName(), getResourceName(res))
        .put(
            Field.UPDATE_DATE.getName(),
            res.getModified() != null ? res.getModified().getTime() : 0)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parentId))
        .put(Field.OWNER.getName(), owner)
        .put(Field.OWNER_EMAIL.getName(), ownerEmail)
        .put(Field.OWNER_ID.getName(), getOwnerId(res))
        .put(
            Field.CREATION_DATE.getName(),
            res.getCreation() != null ? res.getCreation().getTime() : 0)
        .put(Field.SHARED.getName(), permission.isShared() || !permission.isOwner())
        .put(Field.VIEW_ONLY.getName(), !permission.canCreate())
        .put(Field.IS_OWNER.getName(), permission.isOwner())
        .put(Field.CAN_MOVE.getName(), permission.canMove())
        .put(Field.PERMISSIONS.getName(), permissions.toJson())
        .put(Field.SHARE.getName(), share);
  }

  private JsonObject getFileJson(
      String resourceId,
      DavResource res,
      JsonObject share,
      String parentId,
      boolean full,
      boolean isAdmin,
      String externalId,
      String userId,
      JsonObject api) {

    OwncloudPermission permission = new OwncloudPermission(res);

    long modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
    String thumbnailName =
        ThumbnailsManager.getThumbnailName(storageType, resourceId, parseVersionId(modifiedTime));
    JsonObject plData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (full) {
      plData = findLinkForFile(resourceId, externalId, userId, false);
    }

    String ownerId = getOwnerId(res), owner = emptyString, ownerEmail = emptyString;
    if (Utils.isStringNotNullOrEmpty(ownerId) && Utils.isJsonObjectNotNullOrEmpty(api)) {
      String baseUrl = api.getString(Field.URL.getName());
      if (Utils.isStringNotNullOrEmpty(baseUrl)) {
        try {
          JsonObject metadataResponse = new NextcloudApi(
                  baseUrl,
                  api.getString(Field.EMAIL.getName()),
                  api.getString(Field.PASSWORD.getName()))
              .getUserMetadata(ownerId);
          owner = metadataResponse.getString(Field.DISPLAY_NAME.getName());
          ownerEmail = metadataResponse.getString(Field.EMAIL.getName());
        } catch (NextcloudApi.NextcloudApiException e) {
          log.warn("Could not get user metadata for owner info of a file : " + e.getResponse());
        }
      }
    }
    if (owner.isEmpty()) {
      owner = getOwner(res);
    }

    String filename = getResourceName(res);

    String previewId =
        ThumbnailsManager.getPreviewName(StorageType.getShort(storageType), resourceId);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, resourceId))
        .put(Field.WS_ID.getName(), resourceId)
        .put(Field.FILE_NAME.getName(), filename)
        .put(
            Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parentId))
        .put(Field.OWNER.getName(), owner)
        .put(Field.OWNER_EMAIL.getName(), ownerEmail)
        .put(Field.OWNER_ID.getName(), getOwnerId(res))
        .put(
            Field.CREATION_DATE.getName(),
            res.getCreation() != null ? res.getCreation().getTime() : 0)
        .put(Field.UPDATE_DATE.getName(), modifiedTime)
        .put(Field.VER_ID.getName(), parseVersionId(modifiedTime))
        .put(Field.VERSION_ID.getName(), parseVersionId(modifiedTime))
        .put(Field.CHANGER.getName(), emptyString)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(res.getContentLength()))
        .put(Field.SIZE_VALUE.getName(), res.getContentLength())
        .put(Field.SHARED.getName(), permission.isShared() || !permission.isOwner())
        .put(Field.VIEW_ONLY.getName(), !permission.canWrite())
        .put(Field.IS_OWNER.getName(), permission.isOwner())
        .put(Field.CAN_MOVE.getName(), permission.canMove())
        .put(Field.SHARE.getName(), share)
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PUBLIC.getName(), plData.getBoolean(Field.IS_PUBLIC.getName()))
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
        .put(Field.PREVIEW_ID.getName(), previewId);
    if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
      json.put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, false))
          .put(
              Field.GEOMDATA.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", false))
          .put(Field.PREVIEW.getName(), ThumbnailsManager.getPreviewURL(config, previewId, false));
    }
    if (plData.getBoolean(Field.IS_PUBLIC.getName())) {
      json.put(Field.LINK.getName(), plData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), plData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), plData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              plData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setBatchTo(
            List.of(
                AccessType.canViewPermissions,
                AccessType.canManagePermissions,
                AccessType.canViewPublicLink,
                AccessType.canManagePublicLink),
            true)
        .setBatchTo(List.of(AccessType.canCreateFiles, AccessType.canCreateFolders), false)
        .setPermissionAccess(AccessType.canDelete, permission.canDelete())
        .setPermissionAccess(AccessType.canRename, permission.canRename())
        // let's allow move only for owners
        // for now
        .setBatchTo(
            List.of(AccessType.canMove, AccessType.canMoveFrom, AccessType.canMoveTo),
            permission.isOwner());
    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  private String parseVersionId(long modifiedTime) {
    return ((Long) (modifiedTime / 100)).toString();
  }

  @Override
  public void connect(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, message.body(), reply);
  }

  private <T> JsonObject connect(Entity segment, Message<T> message) {
    return connect(segment, message, MessageUtils.parse(message).getJsonObject(), false);
  }

  private <T> JsonObject connect(
      Entity segment, Message<T> message, JsonObject json, boolean reply) { // done
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "NEXTCLOUD.Connect");

    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String nextcloudId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;

    XRayEntityUtils.putMetadata(
        subsegment,
        XrayField.CONNECTION_DATA,
        new JsonObject().put(Field.USER_ID.getName(), userId).put("nextcloudId", nextcloudId));

    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(StorageType.NEXTCLOUD, null, nextcloudId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (nextcloudId == null || nextcloudId.isEmpty()) {
      nextcloudId = findExternalId(segment, message, json, storageType);

      XRayEntityUtils.putMetadata(
          subsegment, XrayField.NEW_DAV_ID, new JsonObject().put(Field.ID.getName(), nextcloudId));
      if (nextcloudId == null) {
        logConnectionIssue(StorageType.NEXTCLOUD, userId, null, ConnectionError.NO_EXTERNAL_ID);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return null;
      }
    }

    Item nextcloudUser = ExternalAccounts.getExternalAccount(userId, nextcloudId);

    XRayEntityUtils.putMetadata(subsegment, XrayField.DAV_USER, nextcloudUser);

    if (nextcloudUser == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(StorageType.NEXTCLOUD, userId, null, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisNextCloudAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    return connect(segment, message, nextcloudUser, reply);
  }

  private <T> JsonObject connect(
      Entity segment, Message<T> message, Item nextcloudUser, boolean reply) {
    String password = nextcloudUser.getString(Field.PASSWORD.getName());
    try {
      password = EncryptHelper.decrypt(password, config.getProperties().getFluorineSecretKey());
      String path = nextcloudUser.hasAttribute(Field.PATH.getName())
          ? nextcloudUser.getString(Field.PATH.getName())
          : emptyString;
      String url = nextcloudUser.getString(Field.URL.getName());

      // lets trim url to keep the path
      if (url.endsWith(path)) {
        url = url.substring(0, url.length() - path.length());
      }
      if (path.endsWith("/")) {
        path = path.substring(0, path.length() - 1);
      }
      JsonObject api = new JsonObject()
          .put(Field.EMAIL.getName(), nextcloudUser.getString(Field.EMAIL.getName()))
          .put(Field.EXTERNAL_ID.getName(), nextcloudUser.getString(ExternalAccounts.sk))
          .put(Field.PASSWORD.getName(), password)
          .put(Field.URL.getName(), url)
          .put(Field.PATH.getName(), path)
          .put(Field.PREFIX.getName(), new URL(url).getPath())
          .put(Field.STORAGE_TYPE.getName(), storageType.name())
          .put(
              "isAccountThumbnailDisabled",
              nextcloudUser.hasAttribute("disableThumbnail")
                  && nextcloudUser.getBoolean("disableThumbnail"));
      XRayManager.endSegment(segment);
      if (reply) {
        sendOK(segment, message);
      }
      return api;
    } catch (Exception e) {
      XRayManager.endSegment(segment);
      log.error(e);
      logConnectionIssue(StorageType.NEXTCLOUD, null, null, ConnectionError.CONNECTION_EXCEPTION);
      if (reply) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InternalError"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            e);
      }
      return null;
    }
  }

  private JsonObject connect(Item nextcloudUser) {
    return connect(null, null, nextcloudUser, false);
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
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    if (externalId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
      return;
    }
    JsonObject api = connect(segment, message, jsonObject, false);
    if (api == null) {
      return;
    }
    try {
      String path = NextCloudMapping.getResourcePath(api, id);

      // if we didn't find it in mapping - it doesn't exist ?
      if (path.equals(api.getString(Field.PATH.getName()))) {
        // We can return true, because it is used for RF validation only
        // https://graebert.atlassian.net/browse/XENON-30048
        // should be changed if this function is required somewhere else in the future.
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.IS_DELETED.getName(), true)
                .put(
                    "nativeResponse",
                    new JsonObject()
                        .put(Field.MESSAGE.getName(), "Path equals default path")
                        .put(Field.PATH.getName(), api.getString(Field.PATH.getName()))),
            mills);
      }

      List<DavResource> resources = new NextcloudApi(api).getInfo(path);

      boolean isFileFound = false;
      for (DavResource res : resources) {
        String resPath = NextCloudMapping.getScopedPath(api, res.getPath());
        if (resPath.equals(path)) {
          isFileFound = !res.getContentType().equals("httpd/unix-directory");
          break;
        }
      }
      sendOK(
          segment, message, new JsonObject().put(Field.IS_DELETED.getName(), !isFileFound), mills);
    } catch (Exception e) {
      // We can return true, because it is used for RF validation only
      // https://graebert.atlassian.net/browse/XENON-30048
      // should be changed if this function is required somewhere else in the future.
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", e.getMessage()),
          mills);
    }
  }
}
