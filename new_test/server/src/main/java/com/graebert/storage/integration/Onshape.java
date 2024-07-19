package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.api.client.util.Lists;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionModifier;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onshape.OnshapeApiOverlay;
import com.graebert.storage.integration.onshape.OnshapeException;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
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
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.UUID;
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
import kong.unirest.JsonNode;
import kong.unirest.UnirestException;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class Onshape extends BaseStorage implements Storage {
  private OperationGroup operationGroup;

  @NonNls
  public static final String addressProduction = "onshape";

  public static final String addressDev = "onshapedev";
  public static final String addressStaging = "onshapestaging";

  @NonNls
  public static final String ONSHAPE_APP_DRAWING = "onshape-app/drawing";

  @NonNls
  public static final String FOLDER = "f:";

  @NonNls
  public static final String PROJECT = "p:";

  @NonNls
  public static final String ROOT = Field.MINUS_1.getName();

  @NonNls
  public static final String TRASH = "-2";

  @NonNls
  private static final String OWNER = "OWNER";

  @NonNls
  private static final String DELETE = "DELETE";

  @NonNls
  private static final String FULL = "FULL";

  @NonNls
  private static final String RESHARE = "RESHARE";

  @NonNls
  private static final String READ = "READ";

  @NonNls
  private static final String WRITE = "WRITE";

  @NonNls
  private static final String DRAWING = "drawing";

  @NonNls
  private static final String BEARER = "Bearer ";

  @NonNls
  private static final String ELEMENT_ID = "elementId";

  @NonNls
  private static final Logger log = LogManager.getRootLogger();

  private String CLIENT_ID, CLIENT_SECRET, REDIRECT_URI;

  @NonNls
  private String apiUrl;

  private String oauthUrl;
  private StorageType storageType;
  private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  private final String[] specialCharacters = {};
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private S3Regional s3Regional = null;

  public Onshape() {}

  @Override
  public void start() throws Exception {
    super.start();
    String address = addressProduction;
    operationGroup = OperationGroup.ONSHAPE;
    if (config.getProperties().getOnshapedev()) {
      address = addressDev;
      operationGroup = OperationGroup.ONSHAPEDEV;
      CLIENT_ID = config.getProperties().getOnshapedevClientId();
      CLIENT_SECRET = config.getProperties().getOnshapedevClientSecret();

      REDIRECT_URI = config.getProperties().getOnshapedevRedirectUri();
      apiUrl = config.getProperties().getOnshapedevApiUrl();
      oauthUrl = config.getProperties().getOnshapestagingOauthUrl();
      storageType = StorageType.ONSHAPEDEV;
    } else if (config.getProperties().getOnshapestaging()) {
      address = addressStaging;
      operationGroup = OperationGroup.ONSHAPESTAGING;

      CLIENT_ID = config.getProperties().getOnshapestagingClientId();
      CLIENT_SECRET = config.getProperties().getOnshapestagingClientSecret();
      REDIRECT_URI = config.getProperties().getOnshapestagingRedirectUri();
      apiUrl = config.getProperties().getOnshapestagingApiUrl();
      oauthUrl = config.getProperties().getOnshapedevOauthUrl();
      storageType = StorageType.ONSHAPESTAGING;
    } else {
      CLIENT_ID = config.getProperties().getOnshapeClientId();
      CLIENT_SECRET = config.getProperties().getOnshapeClientSecret();
      REDIRECT_URI = config.getProperties().getOnshapeRedirectUri();
      apiUrl = config.getProperties().getOnshapeApiUrl();
      oauthUrl = config.getProperties().getOnshapeOauthUrl();
      storageType = StorageType.ONSHAPE;
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
    eb.consumer(address + ".getAllFiles", this::doGlobalSearch);
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
        address + ".getTrash", (Message<JsonObject> event) -> doGetFolderContent(event, true));
    eb.consumer(address + ".share", this::doShare);
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

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    //        Unirest.setProxy(new HttpHost("127.0.0.1", 51301, "https"));
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-onshape");
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
      String accessToken = connect(segment, message, jsonObject, false);
      if (accessToken == null) {
        return;
      }

      String[] args = fileIds.getId().split("_");
      String folderId = args[0];
      String workspace = args[1];
      String fileId = args[2];

      String name = emptyString;

      // check info & folders
      HttpResponse<JsonNode> infoResponse =
          OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);

      if (!isSuccessfulRequest(infoResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(
            fileId, jsonObject.getString(Field.USER_ID.getName()));
        sendError(segment, message, infoResponse);
        return;
      }

      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, folderId, workspace, fileId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(
            fileId, jsonObject.getString(Field.USER_ID.getName()));
        sendError(segment, message, contentResponse);
        return;
      }
      if (!contentResponse.getBody().getArray().isEmpty()) {
        JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
        name = obj.getString(Field.NAME.getName());
      }

      String verId = emptyString;
      try {
        verId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, folderId, workspace, fileId);
      } catch (OnshapeException ignored) {
      }

      VersionType versionType = VersionType.parse(versionId);

      String realVersionId = versionType.equals(VersionType.LATEST) ? verId : versionId;

      final String versionPart =
          versionType.equals(VersionType.LATEST) ? ("/w/" + workspace) : ("/m/" + versionId);
      HttpResponse<byte[]> blobResponse = OnshapeApiOverlay.getFileBlob(
          apiUrl, accessToken, folderId, workspace, fileId, versionPart);
      if (isSuccessfulRequest(blobResponse.getStatus())) {
        HttpResponse<byte[]> appElementResponse = OnshapeApiOverlay.getAppElementContent(
            apiUrl, accessToken, folderId, workspace, fileId, versionPart);
        if (isSuccessfulRequest(appElementResponse.getStatus())) {
          blobResponse = appElementResponse;
        }
      }

      HttpResponse<JsonNode> response =
          OnshapeApiOverlay.getFileInfo(apiUrl, accessToken, folderId, workspace, fileId);
      if (isSuccessfulRequest(response.getStatus())) {
        name = response.getBody().getObject().getString(Field.NAME.getName());
      }

      message.body().put(Field.NAME.getName(), name);

      byte[] data = blobResponse.getBody();

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
      String accessToken = connect(segment, message, message.body(), false);
      if (accessToken == null) {
        return;
      }

      String[] args = fileIds.getId().split("_");
      String folderId = args[0];
      String workspace = args[1];
      String fileId = args[2];

      String name = emptyString;

      // check info & folders
      HttpResponse<JsonNode> infoResponse =
          OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);

      if (!isSuccessfulRequest(infoResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(fileId, userId);
        sendError(segment, message, infoResponse);
        return;
      }

      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, folderId, workspace, fileId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(fileId, userId);
        sendError(segment, message, contentResponse);
        return;
      }
      if (!contentResponse.getBody().getArray().isEmpty()) {
        JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
        name = obj.getString(Field.NAME.getName());
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

  // according to OS email - 2xx responses are successful
  private boolean isSuccessfulRequest(int statusCode) {
    return statusCode / 100 == 2;
  }

  private boolean isReadOnly(String permission, JSONArray permissionSet) {
    if (checkPermission(WRITE, permissionSet) || checkPermission(OWNER, permissionSet)) {
      return false;
    }
    return !WRITE.equals(permission) && !FULL.equals(permission) && !OWNER.equals(permission);
  }

  private boolean isDeleteAllowed(JSONArray permissionSet) {
    return checkPermission(DELETE, permissionSet);
  }

  private boolean isOwner(String permission, JSONArray permissionSet) {
    return checkPermission(OWNER, permissionSet) || OWNER.equals(permission);
  }

  private boolean checkPermission(String permissionStr, JSONArray permissionSet) {
    return Lists.newArrayList(permissionSet).stream().anyMatch(perm -> perm.equals(permissionStr));
  }

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    @NonNls JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String currentFolder = folderId;
      String fileWorkspace = null;
      if (currentFolder == null) {
        String[] args = fileId.split("_");
        if (args.length != 3) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectFileid"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        currentFolder = args[0];
        fileWorkspace = args[1];
      }
      String finalCurrentFolder = currentFolder;
      String finalFileWorkspace = fileWorkspace;
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
            if (array.length != 1) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
            String filename = array[array.length - 1];
            // check that current user is an owner or an editor
            boolean available = false;
            JSONObject possibleFolder = null;
            try {
              HttpResponse<JsonNode> response =
                  OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
              String workspace = finalFileWorkspace;
              if (isSuccessfulRequest(response.getStatus())) {
                possibleFolder = response.getBody().getObject();
                String permission = getPermission(response);
                JSONArray permissionSet = getPermissionSet(response);
                available = !isReadOnly(permission, permissionSet);
                if (workspace == null) {
                  workspace = response
                      .getBody()
                      .getObject()
                      .getJSONObject("defaultWorkspace")
                      .getString(Field.ID.getName());
                }
              }
              // check for existing file
              if (available) {
                response = OnshapeApiOverlay.getDocumentContent(
                    apiUrl, accessToken, finalCurrentFolder, workspace);
                if (isSuccessfulRequest(response.getStatus())) {
                  for (Object item : response.getBody().getArray()) {
                    if (filename.equals(((JSONObject) item).getString(Field.NAME.getName()))) {
                      available = false;
                      break;
                    }
                  }
                }
              }
            } catch (Exception e) {
              XRayManager.endSegment(subSegment);
              sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
              return null;
            }
            JsonArray folders = new JsonArray();
            if (available) {
              String owner = emptyString;
              String permission = getPermission(possibleFolder);
              JSONArray permissionSet = getPermissionSet(possibleFolder);
              boolean isOwner = isOwner(permission, permissionSet);
              long updateDate = 0L;
              String changer = possibleFolder
                  .getJSONObject(Field.MODIFIED_BY.getName())
                  .getString(Field.NAME.getName());
              String changerId = possibleFolder
                  .getJSONObject(Field.MODIFIED_BY.getName())
                  .getString(Field.ID.getName());
              try {
                owner = possibleFolder
                    .getJSONObject(Field.OWNER.getName())
                    .getString(Field.NAME.getName());
                updateDate = sdf.parse(possibleFolder
                        .getString("modifiedAt")
                        .substring(0, possibleFolder.getString("modifiedAt").lastIndexOf(".")))
                    .getTime();
              } catch (Exception ignore) {
              }
              folders.add(new JsonObject()
                  .put(Field.ID.getName(), finalCurrentFolder)
                  .put(Field.OWNER.getName(), owner)
                  .put(Field.IS_OWNER.getName(), isOwner)
                  .put(Field.UPDATE_DATE.getName(), updateDate)
                  .put(Field.CHANGER.getName(), changer)
                  .put(Field.CHANGER_ID.getName(), changerId)
                  .put(Field.NAME.getName(), possibleFolder.getString(Field.NAME.getName()))
                  .put(Field.STORAGE_TYPE.getName(), storageType.name()));
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.PATH.getName(), pathStr)
                .put(
                    Field.STATE.getName(),
                    available ? Field.AVAILABLE.getName() : Field.UNAVAILABLE.getName())
                .put(Field.FOLDERS.getName(), folders);
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
    String ewquestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    try {
      String currentFolder = ewquestFolderId;
      //            String fileWorkspace = null;
      if (currentFolder == null) {
        String[] args = fileId.split("_");
        if (args.length != 3) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectFileid"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        currentFolder = args[0];
        //                fileWorkspace = args[1];
      }
      String accessToken;
      if (collaborators.isEmpty()) {
        String externalId = findExternalId(segment, message, jsonObject, storageType);
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          collaborators.add(externalId);
        }
      }
      for (Object externalId : collaborators) {
        Item onshapeUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
        if (onshapeUser != null) {
          accessToken = onshapeUser.getString(Field.ACCESS_TOKEN.getName());
          try {
            accessToken =
                EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
          } catch (Exception e) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "InternalError"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                e);
            return;
          }
          externalId = onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName());
          if (accessToken != null) {
            Object finalExternalId = externalId;

            // RG: Refactored to not use global collections
            List<String> pathList = path.getList();
            String finalCurrentFolder = currentFolder;
            String finalAccessToken = accessToken;
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
                  if (array.length != 1) {
                    XRayManager.endSegment(subSegment);
                    return new JsonObject()
                        .put(Field.PATH.getName(), pathStr)
                        .put(Field.FILES.getName(), pathFiles);
                  }
                  String filename = array[array.length - 1];
                  Set<String> possibleFolders = new HashSet<>();
                  possibleFolders.add(finalCurrentFolder);

                  for (String folderId : possibleFolders) {
                    findFileInFolder(
                        finalAccessToken, folderId, filename, pathFiles, (String) finalExternalId);
                  }
                  XRayManager.endSegment(subSegment);
                  return new JsonObject()
                      .put(Field.PATH.getName(), pathStr)
                      .put(Field.FILES.getName(), pathFiles);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
            sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
            return;
          }
        }
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private void findFileInFolder(
      String accessToken,
      String folderId,
      String filename,
      JsonArray pathFiles,
      String externalId) {
    if (folderId != null && !Field.MINUS_1.getName().equals(folderId)) {
      String workspace;
      HttpResponse<JsonNode> response;
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      try {
        response = OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
        if (!isSuccessfulRequest(response.getStatus())) {
          log.info(
              "Error on searching for xref in possible folder in Onshape : " + response.getBody());
          return;
        }
        JSONObject obj = response.getBody().getObject();
        workspace = obj.getJSONObject("defaultWorkspace").getString(Field.ID.getName());
        long updateDate = sdf.parse(obj.getString("modifiedAt")
                .substring(0, obj.getString("modifiedAt").lastIndexOf(".")))
            .getTime();
        String size =
            obj.has("sizeBytes") ? Utils.humanReadableByteCount(obj.getInt("sizeBytes")) : "0";
        response = OnshapeApiOverlay.getDocumentContent(apiUrl, accessToken, folderId, workspace);
        if (!isSuccessfulRequest(response.getStatus())) {
          log.info(
              "Error on searching for xref in possible folder in Onshape : " + response.getBody());
          return;
        }
        for (Object item : response.getBody().getArray()) {
          obj = (JSONObject) item;
          if (filename.equals(obj.getString(Field.NAME.getName()))) {
            String permission = getPermission(obj);
            JSONArray permissionSet = getPermissionSet(obj);

            boolean isOwner = isOwner(permission, permissionSet);

            pathFiles.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType,
                        externalId,
                        folderId + "_" + workspace + "_" + obj.getString(ELEMENT_ID)))
                .put(
                    Field.OWNER.getName(),
                    obj.has(Field.OWNER.getName())
                        ? obj.getJSONObject(Field.OWNER.getName()).getString(Field.NAME.getName())
                        : emptyString)
                .put(Field.IS_OWNER.getName(), isOwner)
                .put(Field.UPDATE_DATE.getName(), updateDate)
                .put(Field.CHANGER.getName(), emptyString)
                .put(Field.SIZE.getName(), size)
                .put(Field.SIZE_VALUE.getName(), obj.has("sizeBytes") ? obj.getInt("sizeBytes") : 0)
                .put(Field.NAME.getName(), filename)
                .put(Field.STORAGE_TYPE.getName(), storageType.name()));
          }
        }
      } catch (Exception e) {
        log.info("Error on searching for xref in possible folder in Onshape", e);
      }
    }
  }

  private void doDisconnect(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String onshapeId = getRequiredString(segment, Field.ID.getName(), message, message.body());
    Item onshapeUser = ExternalAccounts.getExternalAccount(userId, onshapeId);
    if (onshapeUser == null) {
      XRayManager.endSegment(segment);
      return;
    }
    String accessToken = onshapeUser.getString(Field.ACCESS_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return;
    }
    try {
      HttpResponse<String> response = AWSXRayUnirest.post(
              oauthUrl + "revoke?token=" + accessToken, "Onshape.revokeToken")
          .header("Content-type", "application/x-www-form-urlencoded")
          .asString();
      if (!isSuccessfulRequest(response.getStatus())) {
        log.error("revoke onshape token", response.getBody());
      }
    } catch (Exception exception) {
      log.error("revoke onshape token", exception.getLocalizedMessage(), exception);
    }
    ExternalAccounts.deleteExternalAccount(userId, onshapeId);
    XRayManager.endSegment(segment);
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    connect(segment, message, message.body(), replyOnOk);

    XRayManager.endSegment(segment);
  }

  private <T> String connect(Entity segment, Message<T> message) {
    return connect(segment, message, MessageUtils.parse(message).getJsonObject(), false);
  }

  private <T> String connect(
      Entity segment, Message<T> message, JsonObject json, boolean replyOnOk) {
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String onshapeId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (userId == null) {
      return null;
    }
    if (onshapeId == null || onshapeId.isEmpty()) {
      onshapeId = findExternalId(segment, message, json, storageType);
      if (onshapeId == null) {
        logConnectionIssue(storageType, userId, null, ConnectionError.NO_EXTERNAL_ID);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return null;
      }
    }
    Item onshapeUser = ExternalAccounts.getExternalAccount(userId, onshapeId);
    if (onshapeUser == null) {
      logConnectionIssue(storageType, userId, onshapeId, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisOnshapeAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    json.put(
        "isAccountThumbnailDisabled",
        onshapeUser.hasAttribute("disableThumbnail") && onshapeUser.getBoolean("disableThumbnail"));
    String accessToken = onshapeUser.getString(Field.ACCESS_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    accessToken =
        checkAndRefreshToken(segment, message, onshapeUser, userId, onshapeId, accessToken);
    if (replyOnOk) {
      sendOK(segment, message); // todo
    }
    return accessToken;
  }

  private <T> String connect(Entity segment, Message<T> message, Item onshapeUser) {
    String accessToken = onshapeUser.getString(Field.ACCESS_TOKEN.getName());
    String userId = onshapeUser.getString(Field.FLUORINE_ID.getName());
    String onshapeId = onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetAccessToken"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return null;
    }
    accessToken =
        checkAndRefreshToken(segment, message, onshapeUser, userId, onshapeId, accessToken);
    return accessToken;
  }

  private <T> String checkAndRefreshToken(
      Entity segment,
      Message<T> message,
      final Item onshapeUser,
      final String userId,
      final String onshapeId,
      String accessToken) {
    long expires = onshapeUser.getLong(Field.EXPIRES.getName());
    String newAccessToken = accessToken;
    if (GMTHelper.utcCurrentTime() >= expires) {
      newAccessToken = refreshToken(
          segment,
          message,
          userId,
          onshapeId,
          onshapeUser.getString(Field.REFRESH_TOKEN.getName()));
    } else {
      HttpResponse<JsonNode> response = AWSXRayUnirest.get(
              apiUrl + "/users/sessioninfo", "Onshape.getSessionInfo")
          .header("Authorization", BEARER + accessToken)
          .asJson();

      if (response.getStatus() == HttpStatus.UNAUTHORIZED) {
        newAccessToken = refreshToken(
            segment,
            message,
            userId,
            onshapeId,
            onshapeUser.getString(Field.REFRESH_TOKEN.getName()));
      }
    }
    return newAccessToken;
  }

  public void doEraseFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FolderidIsRequired"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      HttpResponse<JsonNode> response = AWSXRayUnirest.delete(
              apiUrl + "/documents/" + folderId, "Onshape.deleteDocument")
          .header("Authorization", BEARER + accessToken)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private boolean getFolderPath(
      JsonArray result,
      String id,
      String accessToken,
      Entity segment,
      Message<JsonObject> message) {
    JsonObject body = message.body();
    final String externalId = body.getString(Field.EXTERNAL_ID.getName());
    if (id.startsWith(FOLDER)) {
      HttpResponse<JsonNode> response = AWSXRayUnirest.get(
              apiUrl + "/globaltreenodes/folder/" + id.substring(2) + "?getPathToRoot=true",
              "Onshape.getFolderInfo")
          .header("Authorization", BEARER + accessToken)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return false;
      }
      JSONObject obj = response.getBody().getObject();
      JSONArray path = obj.getJSONArray("pathToRoot");
      for (Object item : path) {
        JSONObject json = (JSONObject) item;
        if (json.getString(Field.RESOURCE_TYPE.getName()).equals(Field.FOLDER.getName())) {
          result.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      externalId,
                      FOLDER + json.getString(Field.ID.getName())))
              .put(Field.NAME.getName(), json.getString(Field.NAME.getName())));
        }
        if (json.getString(Field.RESOURCE_TYPE.getName()).equals("project")) {
          result.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      externalId,
                      PROJECT + json.getString(Field.ID.getName())))
              .put(Field.NAME.getName(), json.getString(Field.NAME.getName())));
        }
      }
      return true;
    } else if (id.startsWith(PROJECT)) {
      HttpResponse<JsonNode> response = AWSXRayUnirest.get(
              apiUrl + "/globaltreenodes/project/" + id.substring(2) + "?getPathToRoot=true",
              "Onshape.getFolderInfo")
          .header("Authorization", BEARER + accessToken)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return false;
      }
      JSONObject obj = response.getBody().getObject();
      JSONArray path = obj.getJSONArray("pathToRoot");
      for (Object item : path) {
        JSONObject json = (JSONObject) item;
        if (json.getString(Field.RESOURCE_TYPE.getName()).equals(Field.FOLDER.getName())) {
          result.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      externalId,
                      FOLDER + json.getString(Field.ID.getName())))
              .put(Field.NAME.getName(), json.getString(Field.NAME.getName())));
        }
        if (json.getString(Field.RESOURCE_TYPE.getName()).equals("project")) {
          result.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      externalId,
                      PROJECT + json.getString(Field.ID.getName())))
              .put(Field.NAME.getName(), json.getString(Field.NAME.getName())));
        }
      }
      return true;
    } else if (!id.equals(Field.MINUS_1.getName())) {
      HttpResponse<JsonNode> response =
          OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, id, true);
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return false;
      }
      String permission = getPermission(response);
      JSONArray permissionSet = getPermissionSet(response);
      boolean viewOnly = isReadOnly(permission, permissionSet);
      String parentId = response.getBody().getObject().optString(Field.PARENT_ID.getName());
      result.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
          .put(Field.NAME.getName(), response.getBody().getObject().getString(Field.NAME.getName()))
          .put(Field.VIEW_ONLY.getName(), viewOnly));
      if (parentId != null && !parentId.isEmpty()) {
        return getFolderPath(result, FOLDER + parentId, accessToken, segment, message);
      }
    }
    return true;
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String id = body.getString(Field.ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      JsonArray result = new JsonArray();

      // we already did sendError, no need to handle else case
      if (getFolderPath(result, id, accessToken, segment, message)) {
        result.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(
                    StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
            .put(Field.NAME.getName(), "~")
            .put(Field.VIEW_ONLY.getName(), false));
        sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
      }

    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) // || folderId == null)
    {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      fileId = args[2];

      try {
        String versionId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, folderId, workspace, fileId);
        sendOK(
            segment, message, new JsonObject().put(Field.VERSION_ID.getName(), versionId), mills);
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String headerFolder = args[0];
      String workspace = args[1];
      fileId = args[2];
      HttpResponse<JsonNode> response =
          OnshapeApiOverlay.getDocumentContent(apiUrl, accessToken, headerFolder, workspace);
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      JSONObject obj;
      String versionId = null, filename = emptyString;
      for (Object item : response.getBody().getArray()) {
        obj = (JSONObject) item;
        if (obj.has(ELEMENT_ID) && fileId.equals(obj.getString(ELEMENT_ID))) {
          // versionId = obj.getString("microversionId");
          filename = obj.getString(Field.NAME.getName());
          break;
        }
        // we do not want the element verId but the workspace verId
        try {
          versionId = OnshapeApiOverlay.getWorkspaceVersionId(
              apiUrl, accessToken, headerFolder, workspace, fileId);
        } catch (OnshapeException ignored) {
        }
      }
      String id = headerFolder + "_" + workspace + "_" + fileId;
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
                          .put(Field.FILE_ID.getName(), id)
                          .put(Field.VERSION_ID.getName(), versionId)
                          .put(Field.EXT.getName(), Extensions.getExtension(filename))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          StorageType.getShort(storageType) + "_" + headerFolder + "_" + workspace,
          fileId,
          versionId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      id, storageType.name(), versionId, true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      String accessToken = connect(segment, message, json, false);
      if (accessToken != null) {
        boolean folder = fileId.startsWith(FOLDER);
        boolean project = fileId.startsWith(PROJECT);
        boolean success = false;
        String[] args = fileId.split("_");
        HttpResponse<JsonNode> response;
        if (args.length != 3) {
          // folder, document or project
          if (folder) {
            response =
                OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, fileId.substring(2), false);
            if (isSuccessfulRequest(response.getStatus())) {
              success = true;
            }
          } else if (project) {
            response = AWSXRayUnirest.get(
                    apiUrl + "/globaltreenodes/project/" + fileId.substring(2)
                        + "?getPathToRoot=true&sortColumn=name&sortOrder=asc"
                        + "&limit=50",
                    "Onshape.getDocumentInfo")
                .header("Authorization", BEARER + accessToken)
                .asJson();
            if (isSuccessfulRequest(response.getStatus())) {
              success = true;
            }
          } else {
            response = OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, fileId, true);
            if (isSuccessfulRequest(response.getStatus())) {
              success = true;
            }
          }
        } else {
          response = OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, args[0], true);
          if (isSuccessfulRequest(response.getStatus())) {
            success = true;
          }
        }
        return success;
      }
    } catch (Exception e) {
      return false;
    }
    return false;
  }

  public void doGetInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String workspace = null, headerFolder = null; // = message.body().getString("headerFolder");
    if (fileId == null && folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    externalId = checkAndGetExternalId(segment, message, jsonObject, externalId, fileId, userId);
    if (externalId == null) {
      return;
    }
    String accessToken = connect(segment, message, jsonObject, false);
    if (accessToken == null) {
      super.deleteSubscriptionForUnavailableFile(fileId, userId);
      return;
    }
    try {
      if (Utils.isStringNotNullOrEmpty(folderId) && folderId.equals(ROOT_FOLDER_ID)) {
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
      boolean isFile = fileId != null;
      if (isFile) {
        String[] args = fileId.split("_");
        if (args.length == 1) {
          // it is a document
          folderId = fileId;
          isFile = false;
        } else if (args.length != 3) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectFileid"),
              HttpStatus.BAD_REQUEST);
          return;
        } else {
          headerFolder = args[0];
          workspace = args[1];
          fileId = args[2];
        }
      }
      JsonArray editor = new JsonArray();
      JsonArray viewer = new JsonArray();
      boolean viewOnly = true,
          isOwner = false,
          canShare = false,
          canDelete = false,
          canUnshare = false;
      String name = null, verId = null;
      long size = 0;
      String owner = emptyString;
      HttpResponse<JsonNode> infoResponse;
      if (folderId != null) {
        if (folderId.startsWith(FOLDER)) {
          infoResponse =
              OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId.substring(2), false);
          if (!isSuccessfulRequest(infoResponse.getStatus())) {
            sendError(segment, message, infoResponse);
            return;
          }
          canShare = isSharingAllowed(infoResponse, false);
          name = infoResponse.getBody().getObject().getString(Field.NAME.getName());
          getDocumentSharedEntries(folderId.substring(2), accessToken, editor, viewer, false);
        } else if (folderId.startsWith(PROJECT)) {
          // workaround for projects. We get name but not owner for now.
          infoResponse = AWSXRayUnirest.get(
                  apiUrl + "/globaltreenodes/project/" + folderId.substring(2)
                      + "?getPathToRoot=true&sortColumn=name&sortOrder=asc" + "&limit=50",
                  "Onshape.getDocumentInfo")
              .header("Authorization", BEARER + accessToken)
              .asJson();
          if (!isSuccessfulRequest(infoResponse.getStatus())) {
            sendError(segment, message, infoResponse);
            return;
          }
          name = ((JSONObject)
                  infoResponse.getBody().getObject().getJSONArray("pathToRoot").get(0))
              .getString(Field.NAME.getName());
        } else {
          // if we see a filedId, just look at the document
          String[] args = folderId.split("_");
          if (args.length == 3) {
            folderId = args[0];
          }

          infoResponse = OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
          if (!isSuccessfulRequest(infoResponse.getStatus())) {
            sendError(segment, message, infoResponse);
            return;
          }
          canShare = isSharingAllowed(infoResponse, true);
          name = infoResponse.getBody().getObject().getString(Field.NAME.getName());
          getDocumentSharedEntries(folderId, accessToken, editor, viewer, true);
        }
      } else {
        infoResponse = OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, headerFolder, true);
        if (!isSuccessfulRequest(infoResponse.getStatus())) {
          super.deleteSubscriptionForUnavailableFile(
              fileId, jsonObject.getString(Field.USER_ID.getName()));
          sendError(segment, message, infoResponse);
          return;
        }

        HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
            apiUrl, accessToken, headerFolder, workspace, fileId);

        if (!isSuccessfulRequest(contentResponse.getStatus())) {
          super.deleteSubscriptionForUnavailableFile(
              fileId, jsonObject.getString(Field.USER_ID.getName()));
          sendError(segment, message, contentResponse);
          return;
        }

        if (!contentResponse.getBody().getArray().isEmpty()) {
          JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
          name = obj.getString(Field.NAME.getName());
        }
        // we do not want the element verId but the workspace verId
        try {
          verId = OnshapeApiOverlay.getWorkspaceVersionId(
              apiUrl, accessToken, headerFolder, workspace, fileId);
        } catch (OnshapeException ignored) {
        }
      }

      try {
        @NonNls String permission = getPermission(infoResponse);
        JSONArray permissionSet = getPermissionSet(infoResponse);
        viewOnly = isReadOnly(permission, permissionSet);
        canDelete = isDeleteAllowed(permissionSet);
        JSONObject infoObject = infoResponse.getBody().getObject();
        canUnshare = infoObject.optBoolean("canUnshare");
        owner = infoObject.getJSONObject(Field.OWNER.getName()).getString(Field.NAME.getName());
        isOwner = isOwner(permission, permissionSet);
        if (!Utils.isStringNotNullOrEmpty(owner) && infoObject.has(Field.CREATED_BY.getName())) {
          owner =
              infoObject.getJSONObject(Field.CREATED_BY.getName()).getString(Field.NAME.getName());
        }
      } catch (Exception ignore) {
      }
      String originalFileId = headerFolder + "_" + workspace + "_" + fileId;
      // if file is public
      JsonObject PLData = findLinkForFile(originalFileId, externalId, userId, viewOnly);
      String finalId = isFile ? originalFileId : folderId;
      JsonObject json = new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, finalId))
          .put(
              Field.SHARE.getName(),
              new JsonObject()
                  .put(Field.VIEWER.getName(), viewer)
                  .put(Field.EDITOR.getName(), editor))
          .put(Field.VIEW_ONLY.getName(), viewOnly)
          .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()))
          .put(isFile ? Field.FILE_NAME.getName() : Field.NAME.getName(), name)
          .put(Field.OWNER.getName(), owner)
          .put(Field.OWNER_EMAIL.getName(), emptyString)
          .put(Field.IS_OWNER.getName(), isOwner);
      if (isFile) {
        json.put(Field.FOLDER_ID.getName(), headerFolder)
            .put(Field.SIZE.getName(), Utils.humanReadableByteCount(size))
            .put(Field.SIZE_VALUE.getName(), size)
            .put(Field.WS_ID.getName(), originalFileId);
        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType) + "_" + headerFolder + "_" + workspace,
            fileId,
            verId);
        String previewId = ThumbnailsManager.getPreviewName(
            StorageType.getShort(storageType) + "_" + headerFolder + "_" + workspace, fileId);
        json.put(Field.PREVIEW_ID.getName(), previewId);
        json.put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
        json.put(Field.VER_ID.getName(), verId);
        json.put(Field.VERSION_ID.getName(), verId);

        if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
          json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
              .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
              .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
              .put(
                  Field.PUBLIC_LINK_INFO.getName(),
                  PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
        }

        if (Extensions.isThumbnailExt(config, name, isAdmin)) {
          // AS : Removing this temporarily until we have some server cache (WB-1248)
          //              .put("thumbnailStatus",
          //                  ThumbnailsManager.getThumbnailStatus(fileId, storageType.name()
          //                  , verId, false,
          //                   false))
          json.put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
              .put(
                  Field.PREVIEW.getName(),
                  ThumbnailsManager.getPreviewURL(config, previewId, true));
        }
      } else {
        if (folderId.startsWith(PROJECT)) {
          json.put("icon", "https://icons.kudo.graebert.com/onshape-project.svg");
          json.put("icon_black", "https://icons.kudo.graebert.com/onshape-project.svg");
        } else if (!folderId.startsWith(FOLDER)) {
          // for folders - no need for icons as default folder icons should be used
          json.put("icon", "https://icons.kudo.graebert.com/onshape-document.svg");
          json.put("icon_black", "https://icons.kudo.graebert.com/onshape-document.svg");
          json.put("allowSubfolders", false);
        }
      }
      // AS : We need to update collaboration for Onshape, then we can update permissions
      // accordingly.
      // Currently setting all to false - XENON-39424
      boolean canMove = Utils.isStringNotNullOrEmpty(folderId)
          ? folderId.startsWith(FOLDER)
          : headerFolder != null && headerFolder.startsWith(FOLDER);
      ObjectPermissions permissions = new ObjectPermissions()
          .setAllTo(true)
          .setBatchTo(List.of(AccessType.canViewPublicLink, AccessType.canManagePublicLink), isFile)
          .setBatchTo(
              List.of(AccessType.canViewPermissions, AccessType.canManagePermissions), canShare)
          .setPermissionAccess(AccessType.canMove, canMove)
          .setPermissionAccess(AccessType.canClone, false)
          .setPermissionAccess(AccessType.canDelete, canDelete);
      if (isFile) {
        permissions.setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false);
      }
      json.put(Field.PERMISSIONS.getName(), permissions.toJson().put("canUnShare", canUnshare));
      sendOK(segment, message, json, mills);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    if (!trash && folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    boolean root = Field.MINUS_1.getName().equals(folderId);
    boolean folder = folderId.startsWith(FOLDER);
    boolean project = folderId.startsWith(PROJECT);
    if (trash) {
      folderId = TRASH;
    }
    if (root || trash || folder || project) {
      getFolderContent(segment, message, accessToken, folderId, mills);
    } else {
      getDocumentContent(segment, message, accessToken, folderId, mills);
    }
  }

  private boolean isValidDataType(@NonNls String dataType) {
    if (dataType.equals("onshape/partstudio")) {
      return false;
    }
    if (dataType.equals("onshape/assembly")) {
      return false;
    }
    if (dataType.equals("onshape/featurestudio")) {
      return false;
    }
    if (dataType.equals("onshape-app/materials")) {
      return false;
    }
    if (dataType.equals("onshape/billofmaterials")) {
      return false;
    }
    if (dataType.equals("onshape-app/drawing")) {
      return false;
    }
    if (dataType.equals("onshape-app/com.onshape.api-explorer")) {
      return false;
    }

    if (dataType.equals("application/sldprt")) {
      return false;
    }
    if (dataType.equals("application/json")) {
      return false;
    }
    if (dataType.equals("text/plain")) {
      return false;
    }

    if (dataType.equals("application/dwg")) {
      return true;
    }
    if (dataType.equals("application/dxf")) {
      return true;
    }
    if (dataType.equals("application/dwt")) {
      return true;
    }
    if (dataType.equals("application/pdf")) {
      return true;
    }
    if (dataType.equals("image/jpeg")) {
      return true;
    }

    return true;
  }

  private void getDocumentContent(
      Entity segment,
      Message<JsonObject> message,
      String accessToken,
      String documentId,
      long mills) {
    List<JsonObject> filesJson = new ArrayList<>();
    JsonObject jsonObject = message.body();
    final String userId = message.body().getString(Field.USER_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    String device = jsonObject.getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    JsonObject messageBody = message.body();
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;
    JsonArray thumbnail = new JsonArray();
    try {
      HttpResponse<JsonNode> response =
          OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, documentId, true);
      debugLogResponse(apiUrl + "/documents/" + documentId, response, message);
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      String workspace = response
          .getBody()
          .getObject()
          .getJSONObject("defaultWorkspace")
          .getString(Field.ID.getName());
      String owner = emptyString;
      try {
        owner = response
            .getBody()
            .getObject()
            .getJSONObject(Field.OWNER.getName())
            .getString(Field.NAME.getName());
      } catch (Exception ignore) {
      }
      String permission = getPermission(response);
      JSONArray permissionSet = getPermissionSet(response);
      boolean viewOnly = isReadOnly(permission, permissionSet);
      boolean isOwner = isOwner(permission, permissionSet); // NON-NLS
      boolean shared = false; // todo
      JsonArray viewer = new JsonArray(); // todo
      JsonArray editor = new JsonArray(); // todo
      // we do not want the element verId but the workspace verId
      String verId;
      try {
        verId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, documentId, workspace, null);
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
        return;
      }
      response = OnshapeApiOverlay.getDocumentContent(apiUrl, accessToken, documentId, workspace);
      debugLogResponse(
          apiUrl + "/documents/d/" + documentId + "/w/" + workspace + "/elements",
          response,
          message);
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      JSONObject obj;
      JsonObject json;
      for (Object item : response.getBody().getArray()) {
        obj = (JSONObject) item;
        @NonNls String filename = obj.getString(Field.NAME.getName());
        @NonNls String dataType = obj.getString("dataType");
        if (!Extensions.isValidExtension(extensions, filename)) {
          continue;
        }
        if (!isValidDataType(dataType)) {
          continue;
        }
        long updateDate = 0;
        // verId = obj.getString("microversionId");
        if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
          thumbnail.add(new JsonObject()
              .put(
                  Field.FILE_ID.getName(),
                  documentId + "_" + workspace + "_" + obj.getString(Field.ID.getName()))
              .put(Field.VERSION_ID.getName(), verId)
              .put(Field.EXT.getName(), Extensions.getExtension(filename)));
        }

        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType) + "_" + documentId + "_" + workspace,
            obj.getString(Field.ID.getName()),
            verId);
        String fileId = documentId + "_" + workspace + "_" + obj.getString(Field.ID.getName());
        // if file is public
        JsonObject PLData = findLinkForFile(fileId, externalId, userId, viewOnly);
        json = new JsonObject()
            .put(Field.ENCAPSULATED_ID.getName(), fileId)
            .put(Field.WS_ID.getName(), fileId)
            .put(Field.FILE_NAME.getName(), obj.getString(Field.NAME.getName()))
            .put(Field.FOLDER_ID.getName(), documentId)
            .put(Field.OWNER.getName(), owner)
            .put(Field.CREATION_DATE.getName(), emptyString)
            .put(Field.UPDATE_DATE.getName(), updateDate)
            .put(Field.CHANGER.getName(), emptyString)
            .put(Field.SIZE.getName(), emptyString)
            .put(Field.SIZE_VALUE.getName(), 0)
            .put(Field.SHARED.getName(), shared)
            .put(Field.VIEW_ONLY.getName(), viewOnly)
            .put(Field.IS_OWNER.getName(), isOwner)
            .put(
                Field.SHARE.getName(),
                new JsonObject()
                    .put(Field.VIEWER.getName(), viewer)
                    .put(Field.EDITOR.getName(), editor))
            .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()))
            .put(Field.TYPE.getName(), obj.getString("dataType"))
            .put(
                "openable",
                "application/dwg".equals(obj.getString("dataType"))
                    || "application/dxf".equals(obj.getString("dataType"))
                    || "application/dwt".equals(obj.getString("dataType")));

        if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
          json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
              .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
              .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
              .put(
                  Field.PUBLIC_LINK_INFO.getName(),
                  PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
        }

        if (Extensions.isThumbnailExt(config, obj.getString(Field.NAME.getName()), isAdmin)) {
          //          .put("thumbnailStatus",
          //                  ThumbnailsManager.getThumbnailStatus(fileId, storageType.name(),
          //                  verId, force,
          //                      canCreateThumbnails))
          json.put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
              .put(
                  Field.PREVIEW.getName(),
                  ThumbnailsManager.getPreviewURL(
                      config,
                      StorageType.getShort(storageType) + "_" + documentId + "_" + workspace + "_"
                          + obj.getString(Field.ID.getName()),
                      true));
        }
        json.put(Field.VER_ID.getName(), verId);
        json.put(Field.VERSION_ID.getName(), verId);
        json.put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                storageType, externalId, json.getString(Field.ENCAPSULATED_ID.getName())));
        json.put(
            Field.PERMISSIONS.getName(),
            new ObjectPermissions()
                .setPermissionAccess(AccessType.canDownload, true)
                .setBatchTo(
                    List.of(
                        AccessType.canClone,
                        AccessType.canMove,
                        AccessType.canMoveFrom,
                        AccessType.canMoveTo),
                    false)
                .setBatchTo(List.of(AccessType.canRename, AccessType.canDelete), !viewOnly)
                .setBatchTo(
                    List.of(AccessType.canManagePublicLink, AccessType.canViewPublicLink), true)
                .toJson());
        filesJson.add(json);
      }
      if (canCreateThumbnails && !thumbnail.isEmpty()) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }

      filesJson.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject result = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesJson))
                  .put(Field.FOLDERS.getName(), new JsonArray()))
          .put(Field.NUMBER.getName(), filesJson.size())
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      sendOK(segment, message, result, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private String constructFolderQuery(String folderId, int offset, int limit) {
    String query = apiUrl;
    String params = emptyString;
    if (folderId.equals(TRASH)) {
      query += "/globaltreenodes/magic/4";
    } else if (folderId.equals(ROOT)) {
      query += "/globaltreenodes/magic/1";
    } else if (folderId.startsWith(PROJECT)) {
      query += "/globaltreenodes/project/" + folderId.substring(2);
    } else {
      query += "/globaltreenodes/folder/" + folderId.substring(2);
    }

    if (offset != 0) {
      params += "?offset=" + offset;
    }
    if (limit != 0) {
      params += (params.isEmpty() ? "?" : "&") + "limit=" + limit;
    }
    params += (params.isEmpty() ? "?" : "&") + "sortColumn=name&sortOrder=asc";

    return query + params;
  }

  private void processFolderGetDocumentsResponse(
      JSONObject object, List<JsonObject> foldersJson, String externalId, String accessToken) {
    JSONObject obj;
    JsonObject json;
    for (Object item : object.getJSONArray("items")) {
      obj = (JSONObject) item;
      @NonNls String permission = getPermission(obj);
      JSONArray permissionSet = getPermissionSet(obj);
      boolean viewOnly = isReadOnly(permission, permissionSet);
      boolean canDelete = isDeleteAllowed(permissionSet);
      boolean shared = false; // todo
      JsonArray viewer = new JsonArray(); // todo
      JsonArray editor = new JsonArray(); // todo
      long updateDate = 0, creationDate = 0;
      try {
        updateDate = sdf.parse(obj.getString("modifiedAt")
                .substring(0, obj.getString("modifiedAt").lastIndexOf(".")))
            .getTime();
        creationDate = sdf.parse(obj.getString("createdAt")
                .substring(0, obj.getString("createdAt").lastIndexOf(".")))
            .getTime();
      } catch (Exception ignore) {
      }
      String changer =
          obj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.NAME.getName());
      String changerId =
          obj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.ID.getName());
      String owner = obj.getJSONObject(Field.OWNER.getName()).getString(Field.NAME.getName());
      boolean isOwner = isOwner(permission, permissionSet);
      boolean canMove = obj.getBoolean(Field.CAN_MOVE.getName());
      if (!Utils.isStringNotNullOrEmpty(owner) && obj.has(Field.CREATED_BY.getName())) {
        owner = obj.getJSONObject(Field.CREATED_BY.getName()).getString(Field.NAME.getName());
      }
      boolean canShare = false, canUnshare = false;
      String type = "document";
      String id = obj.getString(Field.ID.getName());
      if (obj.getString("jsonType").equals(Field.FOLDER.getName())) {
        id = FOLDER + id;
        type = Field.FOLDER.getName();
      }
      if (obj.getString("jsonType").equals("project")) {
        id = PROJECT + id;
        type = "project";
      }

      if (type.equals("document") || type.equals(Field.FOLDER.getName())) {
        getDocumentSharedEntries(id, accessToken, editor, viewer, type.equals("document"));
        canShare = isSharingAllowed(obj, type.equals("document"));
        canUnshare = obj.optBoolean("canUnshare");
      }

      json = new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
          .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
          .put(Field.TYPE.getName(), type)
          .put(
              Field.PARENT.getName(),
              obj.optString(Field.PARENT_ID.getName()).isEmpty()
                  ? Field.MINUS_1.getName()
                  : FOLDER + obj.optString(Field.PARENT_ID.getName()))
          .put(Field.OWNER.getName(), owner)
          .put(Field.CREATION_DATE.getName(), creationDate)
          .put(Field.UPDATE_DATE.getName(), updateDate)
          .put(Field.CHANGER.getName(), changer)
          .put(Field.CHANGER_ID.getName(), changerId)
          .put(
              Field.SIZE.getName(),
              obj.has("sizeBytes") ? Utils.humanReadableByteCount(obj.getInt("sizeBytes")) : 0)
          .put(Field.SIZE_VALUE.getName(), obj.has("sizeBytes") ? obj.getInt("sizeBytes") : 0)
          .put(Field.SHARED.getName(), shared)
          .put(Field.VIEW_ONLY.getName(), viewOnly)
          .put(Field.IS_OWNER.getName(), isOwner)
          .put(Field.CAN_MOVE.getName(), canMove)
          .put(
              Field.SHARE.getName(),
              new JsonObject()
                  .put(Field.VIEWER.getName(), viewer)
                  .put(Field.EDITOR.getName(), editor));

      ObjectPermissions permissions = new ObjectPermissions()
          .setAllTo(true)
          .setBatchTo(
              List.of(
                  AccessType.canClone,
                  AccessType.canViewPublicLink,
                  AccessType.canManagePublicLink),
              false)
          .setPermissionAccess(AccessType.canMove, type.equals(Field.FOLDER.getName()))
          .setPermissionAccess(AccessType.canDelete, canDelete)
          .setBatchTo(
              List.of(AccessType.canViewPermissions, AccessType.canManagePermissions), canShare);
      if (type.equals("document")) {
        permissions.setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false);
      }
      json.put(Field.PERMISSIONS.getName(), permissions.toJson().put("canUnShare", canUnshare));

      if (obj.getString("jsonType").equals("document-summary")) {
        json.put("icon", "https://icons.kudo.graebert.com/onshape-document.svg");
        json.put("icon_black", "https://icons.kudo.graebert.com/onshape-document.svg");
        json.put("allowSubfolders", false);
      } else if (obj.getString("jsonType").equals("project")) {
        json.put("icon", "https://icons.kudo.graebert.com/onshape-project.svg");
        json.put("icon_black", "https://icons.kudo.graebert.com/onshape-project.svg");
      }
      foldersJson.add(json);
    }
  }

  private void getFolderContent(
      Entity segment,
      Message<JsonObject> message,
      String accessToken,
      String folderId,
      long mills) {
    String pageToken = message.body().getString(Field.PAGE_TOKEN.getName());
    String p = message.body().getString(Field.PAGINATION.getName());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    boolean shouldUsePagination = p == null || Boolean.parseBoolean(p);
    final int pageLimit = 20;
    Pagination pagination = new Pagination(storageType, pageLimit, externalId, folderId);
    // get pagination info
    pagination.getPageInfo(pageToken);
    List<JsonObject> foldersJson = new ArrayList<>();
    try {
      int offset = 0, limit = pageLimit;
      if (shouldUsePagination) {
        offset = pagination.getOffset();
        limit = pagination.getLimit();
        @NonNls String query = constructFolderQuery(folderId, offset, limit);
        HttpResponse<JsonNode> response = AWSXRayUnirest.get(query, "Onshape.getDocuments")
            .header("Authorization", BEARER + accessToken)
            .asJson();
        debugLogResponse(query, response, message);
        if (!isSuccessfulRequest(response.getStatus())) {
          int responseStatusCode = response.getStatus();
          // interestingly OS sends 409 if folder isn't found
          if (responseStatusCode == HttpStatus.CONFLICT) {
            responseStatusCode = HttpStatus.NOT_FOUND;
          }
          sendError(segment, message, response, responseStatusCode);
          return;
        }
        processFolderGetDocumentsResponse(
            response.getBody().getObject(), foldersJson, externalId, accessToken);
      } else {
        boolean bContinue;
        do {
          List<Future<HttpResponse<JsonNode>>> futures = new ArrayList<>();
          int parallelRequests = 10;
          for (int i = 0; i < parallelRequests; i++) {
            Future<HttpResponse<JsonNode>> future = AWSXRayUnirest.get(
                    constructFolderQuery(folderId, offset + limit * i, limit),
                    "Onshape.getDocuments")
                .header("Authorization", BEARER + accessToken)
                .asJsonAsync();
            futures.add(future);
          }

          List<HttpResponse<JsonNode>> responses = new ArrayList<>();
          for (int i = 0; i < parallelRequests; i++) {
            HttpResponse<JsonNode> response;
            try {
              response = futures.get(i).get();
            } catch (RuntimeException e) {
              sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
              return;
            }
            debugLogResponse(
                constructFolderQuery(folderId, offset + limit * i, limit), response, message);
            if (!isSuccessfulRequest(response.getStatus())) {
              sendError(segment, message, response);
              return;
            }
            if (response.getBody() != null) {
              processFolderGetDocumentsResponse(
                  response.getBody().getObject(), foldersJson, externalId, accessToken);
            }
            responses.add(response);
          }

          offset += parallelRequests * limit;
          if (responses.get(parallelRequests - 1).getBody() != null) {
            bContinue =
                !responses.get(responses.size() - 1).getBody().getObject().isNull("next");
          } else {
            bContinue = false;
          }
        } while (bContinue);
      }

      foldersJson.sort((o1, o2) -> {
        String t1 = o1.getString(Field.ENCAPSULATED_ID.getName()).substring(0, 2);
        String t2 = o2.getString(Field.ENCAPSULATED_ID.getName()).substring(0, 2);
        if (t1.equals(t2)) {
          return o1.getString(Field.NAME.getName())
              .toLowerCase()
              .compareTo(o2.getString(Field.NAME.getName()).toLowerCase());
        }
        if (t1.equals("p:")) {
          return -1;
        }
        if (t2.equals("p:")) {
          return 1;
        }
        if (t1.equals("f:")) {
          return -1;
        }
        return 1;
      });
      JsonObject result = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray())
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put(Field.NUMBER.getName(), foldersJson.size())
          .put(Field.FULL.getName(), true);
      log.info("foldersJson.size() is " + foldersJson.size());
      if (shouldUsePagination && !foldersJson.isEmpty() && foldersJson.size() >= pageLimit) {
        String nextPageToken = pagination.saveNewPageInfo(pagination.getNextPageToken(), offset);
        result.put(Field.PAGE_TOKEN.getName(), nextPageToken);
      } else {
        result.remove(Field.PAGE_TOKEN.getName());
      }
      String device = message.body().getString(Field.DEVICE.getName());
      boolean isTouch = AuthManager.ClientType.TOUCH.name().equals(device);
      boolean useNewStructure = message.body().getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
      // DK: Touch needs those permissions for root folders
      if (isTouch && useNewStructure && folderId.equals(ROOT_FOLDER_ID)) {
        ObjectPermissions permissions = new ObjectPermissions()
            .setBatchTo(
                List.of(
                    AccessType.canMoveFrom, AccessType.canCreateFiles, AccessType.canCreateFolders),
                true);
        result.put(Field.PERMISSIONS.getName(), permissions.toJson());
      }
      sendOK(segment, message, result, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private void getFolderContentForZip(
      List<JsonObject> foldersJson, String accessToken, String folderId) {
    boolean bContinue;
    int offset = 0, limit = 20;
    do {
      List<Future<HttpResponse<JsonNode>>> futures = new ArrayList<>();
      int parallelRequests = 10;
      for (int i = 0; i < parallelRequests; i++) {
        Future<HttpResponse<JsonNode>> future = AWSXRayUnirest.get(
                constructFolderQuery(folderId, offset + limit * i, limit), "Onshape.getDocuments")
            .header("Authorization", BEARER + accessToken)
            .asJsonAsync();
        futures.add(future);
      }
      List<HttpResponse<JsonNode>> responses = new ArrayList<>();
      for (int i = 0; i < parallelRequests; i++) {
        HttpResponse<JsonNode> response;
        try {
          response = futures.get(i).get();
        } catch (Exception e) {
          return;
        }
        if (!isSuccessfulRequest(response.getStatus())) {
          return;
        }
        if (response.getBody() != null) {
          processFolderGetDocumentsResponse(
              response.getBody().getObject(), foldersJson, emptyString, accessToken);
        }
        responses.add(response);
      }

      offset += parallelRequests * limit;
      if (responses.get(parallelRequests - 1).getBody() != null) {
        bContinue = !responses.get(responses.size() - 1).getBody().getObject().isNull("next");
      } else {
        bContinue = false;
      }
    } while (bContinue);

    foldersJson.sort(Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    JsonArray deShare = message.body().getJsonArray(Field.DE_SHARE.getName());
    if (id == null || deShare == null || deShare.isEmpty()) {
      return;
    }

    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }

    try {
      String[] args = id.split("_");
      String type = "document";
      if (args.length > 1) {
        id = args[0];
      }

      if (id.startsWith(FOLDER)) {
        type = Field.FOLDER.getName();
        id = id.substring(2);
      }

      JsonObject deShareObj = deShare.getJsonObject(0);
      if (!deShareObj.containsKey(Field.USER_ID.getName())
          || deShareObj.getString(Field.USER_ID.getName()).isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "OnshapeShareEntryIdIsNotProvided"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      HttpResponse<JsonNode> response = AWSXRayUnirest.delete(
              apiUrl + (type.equals("document") ? "/documents/" : "/folders/") + id + "/share/"
                  + deShareObj.getString(Field.USER_ID.getName()) + "?entryType=0",
              type.equals("document")
                  ? "Onshape" + ".unShareDocument"
                  : "Onshape" + ".unShareFolder")
          .header("Authorization", BEARER + accessToken)
          .header("Content-Type", "application/json")
          .asJson();

      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String email = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    String role = getRequiredString(segment, Field.ROLE.getName(), message, message.body());
    if (id == null || email == null || role == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = id.split("_");
      String type = "document";
      if (args.length > 1) {
        id = args[0];
      }

      if (id.startsWith(FOLDER)) {
        type = Field.FOLDER.getName();
        id = id.substring(2);
      }

      HttpResponse<JsonNode> response = AWSXRayUnirest.post(
              apiUrl + (type.equals("document") ? "/documents/" : "/folders/") + id + "/share",
              type.equals("document") ? "Onshape" + ".shareDocument" : "Onshape" + ".shareFolder")
          .header("Authorization", BEARER + accessToken)
          .header("Content-Type", "application/json")
          .body(new JSONObject()
              .put(type.equals("document") ? "documentId" : Field.FOLDER_ID.getName(), id)
              .put(Field.MESSAGE.getName(), emptyString)
              .put(
                  "entries",
                  new JSONArray()
                      .put(new JSONObject().put(Field.EMAIL.getName(), email).put("entryType", 0)))
              .put(Field.PERMISSION.getName(), Integer.valueOf(role)))
          .asJson();

      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doEraseAll(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doEraseFile(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doGetInfoByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    final String userId = jsonObject.getString(Field.OWNER_ID.getName());
    final String token = jsonObject.getString(Field.TOKEN.getName());
    final Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    final String creatorId = jsonObject.getString(Field.CREATOR_ID.getName());
    final String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    externalId = checkAndGetExternalId(segment, message, jsonObject, externalId, fileId, userId);
    if (externalId == null) {
      return;
    }
    Item onshapeUser = ExternalAccounts.getExternalAccount(userId, externalId);
    String accessToken = connect(segment, message, onshapeUser);
    if (accessToken == null) {
      return;
    }
    String fileName = emptyString,
        verId = emptyString,
        changer = emptyString,
        changerId = emptyString;
    boolean trashed;
    long updateDate = 0L;
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      String elementId = args[2];

      HttpResponse<JsonNode> infoResponse =
          OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
      if (!isSuccessfulRequest(infoResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(
            fileId, jsonObject.getString(Field.USER_ID.getName()));
        sendError(segment, message, infoResponse);
        return;
      }
      JSONObject docObj = infoResponse.getBody().getObject();
      trashed = docObj.optBoolean(Field.TRASH.getName());
      if (docObj.has("modifiedAt") && Objects.nonNull(docObj.get("modifiedAt"))) {
        String modifiedAt = docObj.getString("modifiedAt");
        updateDate =
            sdf.parse(modifiedAt.substring(0, modifiedAt.lastIndexOf("."))).getTime();
      }
      if (docObj.has(Field.MODIFIED_BY.getName())
          && Objects.nonNull(docObj.get(Field.MODIFIED_BY.getName()))) {
        changer = docObj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.NAME.getName());
        changerId = docObj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.ID.getName());
      }

      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, folderId, workspace, elementId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(
            fileId, jsonObject.getString(Field.USER_ID.getName()));
        sendError(segment, message, contentResponse);
        return;
      }
      if (!contentResponse.getBody().getArray().isEmpty()) {
        JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
        fileName = obj.getString(Field.NAME.getName());
      }
      try {
        verId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, folderId, workspace, elementId);
      } catch (OnshapeException ignored) {
        // ignored exception
      }

      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), fileId, verId);
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
          .put(Field.DELETED.getName(), trashed)
          .put(
              Field.LINK.getName(),
              config.getProperties().getUrl() + "file/"
                  + Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, fileId)
                  + "?token=" + token)
          .put(Field.FILE_NAME.getName(), fileName)
          .put(VERSION_ID, verId)
          .put(Field.CREATOR_ID.getName(), creatorId)
          .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
          .put(Field.EXPORT.getName(), export)
          .put(Field.UPDATE_DATE.getName(), updateDate)
          .put(Field.CHANGER.getName(), changer)
          .put(Field.CHANGER_ID.getName(), changerId);
      sendOK(segment, message, json, mills);
      return;
    } catch (Exception ignored) {
      // ignored exception
    }
    if (Utils.isStringNotNullOrEmpty(userId)) {
      super.deleteSubscriptionForUnavailableFile(fileId, userId);
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      // not working yet, might be API limitation
      JSONObject body = new JSONObject();
      if (folderId.startsWith(FOLDER)) {
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), Field.FOLDER.getName())
                    .put(Field.ID.getName(), folderId.substring(2))));
      } else if (folderId.startsWith(PROJECT)) {
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), "project")
                    .put(Field.ID.getName(), folderId.substring(2))));
      } else {
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), "document")
                    .put(Field.ID.getName(), folderId)));
      }
      HttpResponse<JsonNode> response;
      response = AWSXRayUnirest.post(apiUrl + "/globaltreenodes/restore", "Onshape.restoreObject")
          .header("Authorization", BEARER + accessToken)
          .header("Content-Type", "application/json")
          .body(body)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
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
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doCreateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String fileId = body.getString(Field.FILE_ID.getName());
    final long endTime =
        body.containsKey(Field.END_TIME.getName()) ? body.getLong(Field.END_TIME.getName()) : 0L;
    final String userId = body.getString(Field.USER_ID.getName());
    final String password = body.getString(Field.PASSWORD.getName());
    Boolean export = body.getBoolean(Field.EXPORT.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
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
    externalId = checkAndGetExternalId(segment, message, body, externalId, fileId, userId);
    if (externalId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      String elementId = args[2];

      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, folderId, workspace, elementId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(fileId, userId);
        sendError(segment, message, contentResponse);
        return;
      }
      String name = null;
      if (!contentResponse.getBody().getArray().isEmpty()) {
        JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
        name = obj.getString(Field.NAME.getName());
      }
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

        List<String> collaboratorsList = new ArrayList<>();
        JsonObject allUsers = getOwnerAndCollaborators(folderId, accessToken);
        // Adding other collaborators
        JsonArray collaborators = allUsers.getJsonArray(Field.COLLABORATORS.getName());
        for (Object colObj : collaborators) {
          JsonObject collaborator = (JsonObject) colObj;
          collaboratorsList.add(collaborator.getString(Field.ENCAPSULATED_ID.getName()));
        }
        // Adding owner
        JsonObject owner = allUsers.getJsonObject(Field.OWNER.getName());
        if (Utils.isJsonObjectNotNullOrEmpty(owner)) {
          collaboratorsList.add(owner.getString(Field.ID.getName()));
        }
        // Adding current user
        if (!collaboratorsList.contains(externalId)) {
          collaboratorsList.add(externalId);
        }
        newLink.setCollaboratorsList(collaboratorsList);
        try {
          if (body.containsKey(Field.RESET_PASSWORD.getName())
              && body.getBoolean(Field.RESET_PASSWORD.getName())
              && newLink.isPasswordSet()
              && !Utils.isStringNotNullOrEmpty(password)) {
            newLink.resetPassword();
          }
        } catch (Exception ignore) {
        }

        newLink.createOrUpdate();

        String endUserLink = newLink.getEndUserLink();

        sendOK(
            segment,
            message,
            newLink.getInfoInJSON().put(Field.LINK.getName(), endUserLink),
            mills);
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
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String versionId = jsonObject.getString(Field.VER_ID.getName());
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String name;
    if (fileId == null) {
      return;
    }
    String[] args = fileId.split("_");
    if (args.length != 3) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectFileid"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String folderId = args[0];
      String workspace = args[1];
      fileId = args[2];

      JSONArray items;
      try {
        OnshapeApiOverlay.promoteVersion(apiUrl, accessToken, folderId, workspace, versionId);
        name = OnshapeApiOverlay.findElementName(apiUrl, accessToken, folderId, workspace, fileId);
        items = OnshapeApiOverlay.getWorkspaceVersions(apiUrl, accessToken, folderId, workspace);
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
        return;
      }

      JsonObject versionInfo = new JsonArray(items.toString()).getJsonObject(0);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), versionInfo.getString("microversionId")),
          mills);
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            jsonObject
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), folderId + "_" + workspace + "_" + fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      }

    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    final String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    final String sharedLink = jsonObject.getString(Field.SHARED_LINK.getName());
    final String downloadToken = jsonObject.getString(Field.DOWNLOAD_TOKEN.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    externalId = checkAndGetExternalId(segment, message, jsonObject, externalId, fileId, userId);
    if (externalId == null) {
      return;
    }
    Item onshapeUser = ExternalAccounts.getExternalAccount(userId, externalId);
    String accessToken = connect(segment, message, onshapeUser);
    if (accessToken == null) {
      return;
    }
    boolean success = false;
    String name = null, versionId = null;
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      String elementId = args[2];

      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, folderId, workspace, elementId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        super.deleteSubscriptionForUnavailableFile(fileId, userId);
        sendError(segment, message, contentResponse);
        return;
      }
      if (!contentResponse.getBody().getArray().isEmpty()) {
        JSONObject obj = contentResponse.getBody().getArray().getJSONObject(0);
        name = obj.getString(Field.NAME.getName());
      }
      try {
        versionId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, folderId, workspace, elementId);
      } catch (OnshapeException ignored) {
        // ignored exception
      }
      if (returnDownloadUrl) {
        String downloadUrl =
            OnshapeApiOverlay.getFileBlobUrl(apiUrl, folderId, workspace, elementId, null);
        sendDownloadUrl(
            segment, message, downloadUrl, null, versionId, accessToken, null, mills);
        return;
      }
      HttpResponse<byte[]> blobResponse =
          OnshapeApiOverlay.getFileBlob(apiUrl, accessToken, folderId, workspace, elementId, null);
      if (!isSuccessfulRequest(blobResponse.getStatus())) {
        sendError(segment, message, blobResponse);
        return;
      }
      finishGetFile(
          message, null, null, blobResponse.getBody(), storageType, name, versionId, downloadToken);
      success = true;
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
    }
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    // try to get file using shared link (direct links are available for payed accounts only)
    if (!success && sharedLink != null) {
      try {
        stream.reset();
        URL url = new URL(sharedLink);
        try (InputStream in = new BufferedInputStream(url.openStream())) {
          byte[] buf = new byte[1024];
          int n;
          while (-1 != (n = in.read(buf))) {
            stream.write(buf, 0, n);
          }
          stream.close();
        }
        message.body().put(Field.DATA.getName(), stream.toByteArray());
        message.reply(message.body());
        success = true;
      } catch (Exception ex) {
        if (Objects.nonNull(segment)) {
          segment.addException(ex);
        }
        log.error(ex);
      }
    }
    if (!success) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetFileData"),
          HttpStatus.BAD_REQUEST);
    } else {
      XRayManager.endSegment(segment);
    }
    recordExecutionTime("getFileByToken", System.currentTimeMillis() - mills);
  }

  private VersionInfo getVersionInfo(
      String fileId, JsonObject versionObject, String currentUserId) {
    long creationDate = 0;
    try {
      creationDate = sdf.parse(versionObject
              .getString("date")
              .substring(0, versionObject.getString("date").lastIndexOf("+")))
          .getTime();
    } catch (Exception ignore) {
    }
    VersionModifier versionModifier = new VersionModifier();
    versionModifier.setCurrentUser(
        versionObject.getString(Field.USER_ID.getName()).equals(currentUserId));
    versionModifier.setName(versionObject.getString(Field.USERNAME.getName()));
    versionModifier.setId(versionObject.getString(Field.USER_ID.getName()));
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(versionObject.getBoolean("canBeRestored"));
    // delete isn't implemented
    versionPermissions.setCanDelete(false);
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    VersionInfo versionInfo =
        new VersionInfo(versionObject.getString("microversionId"), creationDate, null);
    versionInfo.setModifier(versionModifier);
    versionInfo.setPermissions(versionPermissions);
    try {
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          storageType, fileId, versionObject.getString("microversionId"));
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doGetVersions(Message<JsonObject> message) {

    // sadly there is no right api for getting a versionid per element yet.
    // Asked Onshape support to see if they can help

    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    if (fileId == null) {
      return;
    }
    String[] args = fileId.split("_");
    if (args.length != 3) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectFileid"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String folderId = args[0];
      String workspace = args[1];
      fileId = args[2];
      JSONArray items;
      try {
        items = OnshapeApiOverlay.getWorkspaceVersions(apiUrl, accessToken, folderId, workspace);
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
        return;
      }

      List<JsonObject> result = new ArrayList<>();
      for (Object item : items) {
        JSONObject obj = (JSONObject) item;
        if (obj.getString(Field.DESCRIPTION.getName()).equals("Created document")) {
          continue;
        }
        result.add(getVersionInfo(
                folderId + "_" + workspace + "_" + fileId,
                new JsonObject(obj.toString()),
                externalId)
            .toJson());
      }
      String finalFileId = fileId;
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              HttpResponse<JsonNode> response2 =
                  OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
              if (isSuccessfulRequest(response2.getStatus())) {
                String name = response2.getBody().getObject().getString(Field.NAME.getName());
                if (Utils.isStringNotNullOrEmpty(name)) {
                  ext = Extensions.getExtension(name);
                }
              }
            } catch (Exception ex) {
              log.warn("[ONSHAPE] Get versions: Couldn't get object info to get extension.", ex);
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            result.forEach(revision -> requiredVersions.add(new JsonObject()
                .put(Field.FILE_ID.getName(), folderId + "_" + workspace + "_" + finalFileId)
                .put(Field.VERSION_ID.getName(), revision.getString(Field.ID.getName()))
                .put(Field.EXT.getName(), finalExt)));
            eb_send(
                blockingSegment,
                ThumbnailsManager.address + ".create",
                jsonObject.put(Field.IDS.getName(), requiredVersions));
          });
      result.sort(Comparator.comparing(o -> o.getLong("creationTime")));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);

    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doMoveFile(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doClone(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doMoveFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (userId == null) {
      return;
    }
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      return;
    }
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (parentId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }

    // no moving of projects
    if (folderId.startsWith(PROJECT)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotMoveFolder"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    // no moving into documents
    if (!parentId.startsWith(FOLDER) && !parentId.startsWith(PROJECT) && !parentId.equals(ROOT)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotMoveFolder"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    try {
      HttpResponse<JsonNode> response;
      JSONObject body = new JSONObject();
      if (folderId.startsWith(FOLDER)) {
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), Field.FOLDER.getName())
                    .put(Field.ID.getName(), folderId.substring(2))));
      } else {
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), "document")
                    .put(Field.ID.getName(), folderId)));
      }
      if (parentId.startsWith(FOLDER)) {
        response = AWSXRayUnirest.post(
                apiUrl + "/globaltreenodes/folder/" + parentId.substring(2), "Onshape.setParent")
            .header("Authorization", BEARER + accessToken)
            .header("Content" + "-Type", "application/json")
            .body(body)
            .asJson();
      } else if (parentId.startsWith(PROJECT)) {
        response = AWSXRayUnirest.post(
                apiUrl + "/globaltreenodes/project/" + parentId.substring(2), "Onshape.setProject")
            .header("Authorization", BEARER + accessToken)
            .header("Content" + "-Type", "application/json")
            .body(body)
            .asJson();
      } else if (parentId.equals(ROOT)) {
        response = AWSXRayUnirest.post(apiUrl + "/globaltreenodes/magic/1", "Onshape.moveToRoot")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(body)
            .asJson();
      } else {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "UserIsNotAbleToChangeParentFolderWithId"),
                parentId),
            HttpStatus.INTERNAL_SERVER_ERROR);
        return;
      }
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRequestMultipleObjectsZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String requestId = json.getString(Field.REQUEST_ID.getName());
    String parentFolderId = json.getString(Field.PARENT_FOLDER_ID.getName());
    String userId = json.getString(Field.USER_ID.getName());
    String locale = json.getString(Field.LOCALE.getName());
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
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
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
          if (isFolder) {
            HttpResponse<JsonNode> infoResponse;
            String name;
            if (objectId.startsWith(FOLDER)) {
              name = OnshapeApiOverlay.findNameForFolderOrDocument(
                  apiUrl, true, accessToken, objectId.substring(2));
            } else if (objectId.startsWith(PROJECT)) {
              infoResponse = AWSXRayUnirest.get(
                      apiUrl + "/globaltreenodes/project/" + objectId.substring(2)
                          + "?getPathToRoot=true&sortColumn=name&sortOrder=asc" + "&limit=50",
                      "Onshape.getDocumentInfo")
                  .header("Authorization", BEARER + accessToken)
                  .asJson();
              if (!isSuccessfulRequest(infoResponse.getStatus())) {
                throw new Exception(infoResponse.getStatusText() + " : " + infoResponse.getBody());
              }
              name = ((JSONObject) infoResponse
                      .getBody()
                      .getObject()
                      .getJSONArray("pathToRoot")
                      .get(0))
                  .getString(Field.NAME.getName());
            } else {
              name = OnshapeApiOverlay.findNameForFolderOrDocument(
                  apiUrl, false, accessToken, objectId);
            }
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(stream, accessToken, objectId, filter, recursive, name, new HashSet<>(), 0);
          } else {
            addFileZipEntry(
                stream, accessToken, objectId, locale, filteredFileNames, fileNames, filter);
          }
          XRayManager.endSegment(blockingSegment);
          return null;
        });
        XRayManager.endSegment(subSegment);
      });
      if (callables.isEmpty()) {
        log.warn("Nothing to download, please check the logs for multiple downloads for requestId"
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
    String accessToken = connect(segment, message);
    if (accessToken == null) {
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
        Entity subSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "OnshapeZipFolderSegment");
        zipFolder(
            stream, accessToken, folderId, filter, recursive, emptyString, new HashSet<>(), 0);
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
      String accessToken,
      String id,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth)
      throws Exception {
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    String properPath = path.isEmpty() ? path : path + File.separator;
    if ((id.startsWith(FOLDER) || id.startsWith(PROJECT)) && recursive) {
      List<JsonObject> foldersJson = new ArrayList<>();
      getFolderContentForZip(foldersJson, accessToken, id);
      if (foldersJson.isEmpty()) {
        ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
        stream.putNextEntry(zipEntry);
        stream.write(new byte[0]);
        stream.closeEntry();
        return;
      }
      for (JsonObject obj : foldersJson) {
        JsonObject file =
            Utils.parseItemId(obj.getString(Field.ENCAPSULATED_ID.getName()), Field.ID.getName());
        String name = obj.getString(Field.NAME.getName());
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          zipFolder(
              stream,
              accessToken,
              file.getString(Field.ID.getName()),
              filter,
              true,
              properPath + name,
              filteredFileNames,
              recursionDepth);
        } else {
          log.warn(
              "Zip folder recursion exceeds the limit for path " + path + " in " + storageType);
        }
      }
    } else {
      addDocumentZipEntry(
          stream, accessToken, id, properPath, filter, filteredFileNames, fileNames);
    }
  }

  private void addDocumentZipEntry(
      ZipOutputStream stream,
      String accessToken,
      String id,
      String properPath,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames)
      throws Exception {
    String workspace = OnshapeApiOverlay.findWorkspaceForDocument(apiUrl, accessToken, id);
    HttpResponse<JsonNode> response =
        OnshapeApiOverlay.getDocumentContent(apiUrl, accessToken, id, workspace);
    if (!isSuccessfulRequest(response.getStatus())) {
      throw new Exception(response.getStatusText() + " " + response.getBody());
    }
    if (response.getBody().getArray() == null || response.getBody().getArray().isEmpty()) {
      ZipEntry zipEntry = new ZipEntry(properPath + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    for (Object item : response.getBody().getArray()) {
      // Only export blobs
      JSONObject jsonObject = (JSONObject) item;
      if (!isValidDataType(jsonObject.getString("dataType"))) {
        continue;
      }

      String elementId = jsonObject.has(ELEMENT_ID)
          ? jsonObject.getString(ELEMENT_ID)
          : jsonObject.getString(Field.ID.getName());

      HttpResponse<byte[]> blobResponse =
          OnshapeApiOverlay.getFileBlob(apiUrl, accessToken, id, workspace, elementId, null);
      if (isSuccessfulRequest(blobResponse.getStatus())) {
        String name = jsonObject.getString(Field.NAME.getName());
        addZipEntry(
            stream, name, filter, filteredFileNames, fileNames, properPath, blobResponse.getBody());
      }
    }
  }

  private void addFileZipEntry(
      ZipOutputStream stream,
      String accessToken,
      String fileId,
      String locale,
      Set<String> filteredFileNames,
      Set<String> fileNames,
      String filter)
      throws Exception {
    String[] args = fileId.split("_");
    if (args.length != 3) {
      throw new Exception(Utils.getLocalizedString(locale, "IncorrectFileid"));
    }
    String folderId = args[0];
    fileId = args[2];
    String workspace = OnshapeApiOverlay.findWorkspaceForDocument(apiUrl, accessToken, folderId);
    if (!Utils.isStringNotNullOrEmpty(workspace)) {
      workspace = args[1];
    }
    String name =
        OnshapeApiOverlay.findElementName(apiUrl, accessToken, folderId, workspace, fileId);
    if (Utils.isStringNotNullOrEmpty(name)) {
      HttpResponse<byte[]> blobResponse =
          OnshapeApiOverlay.getFileBlob(apiUrl, accessToken, folderId, workspace, fileId, null);
      if (isSuccessfulRequest(blobResponse.getStatus())) {
        addZipEntry(
            stream,
            name,
            filter,
            filteredFileNames,
            fileNames,
            emptyString,
            blobResponse.getBody());
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      String name,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames,
      String properPath,
      byte[] data)
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
      stream.write(data);
      stream.closeEntry();
      stream.flush();
    }
  }

  public void doDeleteFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) // || folderId == null)
    {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      fileId = args[2];

      HttpResponse<JsonNode> response = AWSXRayUnirest.delete(
              apiUrl + "/elements/d/" + folderId + "/w/" + workspace + "/e/" + fileId,
              "Onshape.deleteFile")
          .header("Authorization", BEARER + accessToken)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRenameFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
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
    if (fileId == null || name == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      HttpResponse<JsonNode> response;
      if (fileId.startsWith(FOLDER)) {
        JSONObject body = new JSONObject();
        body.put(Field.NAME.getName(), name);
        body.put(Field.ID.getName(), fileId.substring(2));

        response = AWSXRayUnirest.post(
                apiUrl + "/folders/" + fileId.substring(2), "Onshape.setName")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(body)
            .asJson();
      } else if (fileId.startsWith(PROJECT)) {
        JSONObject body = new JSONObject();
        body.put(Field.NAME.getName(), name);
        body.put(Field.ID.getName(), fileId.substring(2));

        response = AWSXRayUnirest.post(
                apiUrl + "/projects/" + fileId.substring(2), "Onshape.setName")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(body)
            .asJson();
      } else {
        String[] args = fileId.split("_");
        if (args.length != 3) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectFileid"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        String folderId = args[0];
        String workspace = args[1];
        fileId = args[2];

        HttpResponse<JsonNode> metadataResponse = AWSXRayUnirest.get(
                apiUrl + "/metadata/d/" + folderId + "/w/" + workspace + "/e/" + fileId,
                "Onshape.getFileMetadata")
            .header("Authorization", BEARER + accessToken)
            .asJson();

        if (!isSuccessfulRequest(metadataResponse.getStatus())) {
          sendError(segment, message, metadataResponse);
          return;
        }
        // To get/update metadata -
        // https://cad.onshape.com/glassworks/explorer#/Metadata/updateWVEMetadata
        JSONObject metadata = metadataResponse.getBody().getObject();
        String propertyId = null;
        if (metadata.has("properties")) {
          JSONArray properties = metadata.getJSONArray("properties");
          Optional optional = properties.toList().stream()
              .filter(obj -> ((JSONObject) obj).getString(Field.NAME.getName()).equals("Name"))
              .findAny();
          if (optional.isPresent()) {
            JSONObject nameProperty = (JSONObject) optional.get();
            if (nameProperty.has("propertyId") && nameProperty.get("propertyId") != null) {
              propertyId = nameProperty.getString("propertyId");
            }
          }
        }
        if (Objects.nonNull(propertyId)) {
          String requestBody = "{\"properties\":[{\"propertyId\" : \"" + propertyId + "\", "
              + "\"value\" : \"" + name + "\"}]}";
          // Example body - {"properties":[{"propertyId" : "57f3fb8efa3416c06701d60d", "value" :
          // "Hello6.dwg"}]}
          response = AWSXRayUnirest.post(
                  apiUrl + "/metadata/d/" + folderId + "/w/" + workspace + "/e/" + fileId,
                  "Onshape.putFileMetadata")
              .header("Authorization", BEARER + accessToken)
              .header("Content-Type", "application/json")
              .body(requestBody)
              .asJson();
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "propertyIdNotFound"),
              HttpStatus.BAD_REQUEST);
          return;
        }
      }
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject body = parsedMessage.getJsonObject();

    String name = body.getString(Field.NAME.getName());
    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }

    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());
    if (fileId != null) {
      folderId = fileId.split("_")[0];
    }
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message, body, false);
    if (accessToken == null) {
      return;
    }
    try {
      String initialFileId = fileId;
      String versionId, documentId, workspace = null;

      // find workspace
      if ((fileId != null || !folderId.equals(ROOT))
          && folderId != null
          && !folderId.startsWith(FOLDER)
          && !folderId.startsWith(PROJECT)) {
        try {
          workspace = OnshapeApiOverlay.findWorkspaceForDocument(apiUrl, accessToken, folderId);
        } catch (OnshapeException OSException) {
          handleOnshapeException(message, segment, OSException);
          return;
        }
      }
      String elementId = fileId != null ? fileId.split("_")[2] : null;
      StringBuilder url = new StringBuilder(apiUrl);

      // update existing
      // get name of the existing element
      try {
        name =
            OnshapeApiOverlay.findElementName(apiUrl, accessToken, folderId, workspace, elementId);
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
        return;
      }
      url.append("/blobelements/d/")
          .append(folderId)
          .append("/w/")
          .append(workspace)
          .append("/e/")
          .append(elementId);

      JsonNode uploadResult;
      try (InputStream stream = parsedMessage.getContentAsInputStream()) {
        uploadResult = OnshapeApiOverlay.uploadElement(
            url.toString(), accessToken, stream, name, workspace, elementId, null);
      } catch (IOException exception) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, exception.getMessage()),
            HttpStatus.BAD_REQUEST);
        return;
      } catch (OnshapeException OSException) {
        handleOnshapeException(message, segment, OSException);
        return;
      }

      documentId = folderId;
      fileId = uploadResult.getObject().getString(Field.ID.getName());
      // versionId = uploadResult.getObject().getString("microversionId");

      if (initialFileId == null) {
        initialFileId = documentId + "_" + workspace + "_" + fileId;
        // we created/updated a new element in an existing document
      }
      JsonObject versionInfo = new JsonObject();
      try {
        JSONArray items =
            OnshapeApiOverlay.getWorkspaceVersions(apiUrl, accessToken, folderId, workspace);
        versionInfo = new JsonArray(items.toString()).getJsonObject(0);
      } catch (OnshapeException ignore) {
      }

      versionId = versionInfo.getString("microversionId");
      if (versionId == null) {
        log.error("OS: versionId is null for file: " + documentId + "_" + workspace + "_" + fileId
            + " folderId: " + folderId + " OS URL: " + url);
        versionId = UUID.randomUUID().toString();
      }

      sendOK(
          segment,
          message,
          getVersionInfo(initialFileId, versionInfo, externalId).toJson(),
          mills);
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(
                                Field.FILE_ID.getName(),
                                documentId + "_" + workspace + "_" + fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      }

      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), initialFileId)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FORCE.getName(), true)
              .put(Field.EMAIL.getName(), userEmail)
              .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject body = parsedMessage.getJsonObject();

    String name = body.getString(Field.NAME.getName());

    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }
    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String baseChangeId = body.getString(Field.BASE_CHANGE_ID.getName());
    boolean doCopyComments = body.getBoolean(Field.COPY_COMMENTS.getName(), false);
    String cloneFileId = body.getString(Field.CLONE_FILE_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    boolean isFileUpdate = Utils.isStringNotNullOrEmpty(fileId);
    if (fileId != null) {
      folderId = fileId.split("_")[0];
    }
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());
    boolean newDocument = false,
        isConflictFile = conflictingFileReason != null,
        fileNotFound = false;
    String responseName = null, modifier = null;
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message, body, false);
    if (accessToken == null) {
      return;
    }
    try {
      String initialFileId = (fileId != null && folderId != null) ? fileId : null;
      String versionId = null, documentId = null, workspace = null;

      // find workspace
      if ((fileId != null || !folderId.equals(ROOT))
          && folderId != null
          && !folderId.startsWith(FOLDER)
          && !folderId.startsWith(PROJECT)) {
        try {
          workspace = OnshapeApiOverlay.findWorkspaceForDocument(apiUrl, accessToken, folderId);
        } catch (OnshapeException onshapeException) {
          logOnshapeException(onshapeException);
          try {
            throw new KudoFileException(
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                KudoErrorCodes.FILE_DOESNT_EXIST,
                HttpStatus.NOT_FOUND,
                "FileWithIdDoesNotExist");
          } catch (KudoFileException kfe) {
            sendError(
                segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
            return;
          }
        }
      }
      String elementId = fileId != null ? fileId.split("_")[2] : null;
      String oldName = null;
      boolean isSameFolder = true;

      if ((!parsedMessage.hasByteArrayContent() && !parsedMessage.hasInputStreamContent())
          || (fileId != null && parsedMessage.getContentAsByteArray().length == 0)) {
        // When we send no data, we just get microversionid of the workspace
        try {
          versionId = OnshapeApiOverlay.getWorkspaceVersionId(
              apiUrl, accessToken, documentId, workspace, fileId);
        } catch (OnshapeException ignored) {
        }
      } else {
        StringBuilder url = new StringBuilder(apiUrl);
        // update existing
        if (fileId != null) {
          // get name of the existing element
          try {
            versionId = OnshapeApiOverlay.getWorkspaceVersionId(
                apiUrl, accessToken, folderId, workspace, elementId);
            name = OnshapeApiOverlay.findElementName(
                apiUrl, accessToken, folderId, workspace, elementId);
          } catch (OnshapeException onshapeException) {
            logOnshapeException(onshapeException);
            try {
              throw new KudoFileException(
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                  KudoErrorCodes.FILE_DOESNT_EXIST,
                  HttpStatus.NOT_FOUND,
                  "FileWithIdDoesNotExist");
            } catch (KudoFileException kfe) {
              sendError(
                  segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
              return;
            }
          }
          if (name == null) {
            conflictingFileReason =
                XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
            isConflictFile = true;
            fileNotFound = true;
          }
          oldName = name;

          HttpResponse<JsonNode> response =
              OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
          if (!isSuccessfulRequest(response.getStatus())) {
            sendError(segment, message, response);
            return;
          }
          JSONObject object = response.getBody().getObject();
          if (object.has(Field.MODIFIED_BY.getName())) {
            modifier =
                object.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.NAME.getName());
          }
          // AS : Considering NO_EDITING_RIGHTS as the most priority reason for Onshape as we
          // don't have any access to the
          // document anymore, so we will create a new document in the ROOT folder for the
          // conflicting file
          @NonNls String permission = getPermission(object);
          JSONArray permissionSet = getPermissionSet(object);
          // check if user still has the access to edit this file
          if (isReadOnly(permission, permissionSet)) {
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
          if (!isConflictFile) {
            url.append("/blobelements/d/")
                .append(folderId)
                .append("/w/")
                .append(workspace)
                .append("/e/")
                .append(elementId);
          } else {
            if (fileNotFound) {
              Item metaData = FileMetaData.getMetaData(fileId, storageType.name());
              if (Objects.nonNull(metaData)) {
                name = metaData.getString(Field.FILE_NAME.getName());
              }
              if (name == null) {
                name = unknownDrawingName;
              }
            }
            name = getConflictingFileName(name);
            responseName = name;
            elementId = null;
            if (conflictingFileReason.equals(
                XSessionManager.ConflictingFileReason.NO_EDITING_RIGHTS.name())) {
              newDocument = true;
              try {
                folderId = ROOT;
                isSameFolder = false;
                JSONObject documentData = OnshapeApiOverlay.createDocument(
                    apiUrl, accessToken, name, folderId, externalId);
                documentId = documentData.getString(Field.ID.getName());
                workspace =
                    documentData.getJSONObject("defaultWorkspace").getString(Field.ID.getName());
              } catch (OnshapeException OSException) {
                handleOnshapeException(message, segment, OSException);
                return;
              }
              url.append("/blobelements/d/").append(documentId).append("/w/").append(workspace);
            } else if (workspace != null) {
              url.append("/blobelements/d/").append(folderId).append("/w/").append(workspace);
            } else {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "OnshapeFileWorkSpaceNotFound"),
                  HttpStatus.BAD_REQUEST);
              return;
            }
          }
        } else if (workspace != null) {
          // new element in existing workspace
          url.append("/blobelements/d/").append(folderId).append("/w/").append(workspace);
        } else {
          // new document
          newDocument = true;
          try {
            JSONObject documentData =
                OnshapeApiOverlay.createDocument(apiUrl, accessToken, name, folderId, externalId);
            documentId = documentData.getString(Field.ID.getName());
            workspace =
                documentData.getJSONObject("defaultWorkspace").getString(Field.ID.getName());
          } catch (OnshapeException OSException) {
            handleOnshapeException(message, segment, OSException);
            return;
          }
          url.append("/blobelements/d/").append(documentId).append("/w/").append(workspace);
        }
        JsonNode uploadResult;
        try (InputStream stream = parsedMessage.getContentAsInputStream()) {
          uploadResult = OnshapeApiOverlay.uploadElement(
              url.toString(), accessToken, stream, name, workspace, elementId, null);
        } catch (IOException exception) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, exception.getMessage()),
              HttpStatus.BAD_REQUEST);
          return;
        } catch (OnshapeException OSException) {
          handleOnshapeException(message, segment, OSException);
          return;
        }
        if (!Utils.isStringNotNullOrEmpty(documentId)) {
          documentId = folderId;
        }
        fileId = uploadResult.getObject().getString(Field.ID.getName());

        if (isConflictFile) {
          String newFileId = documentId + "_" + workspace + "_" + fileId;
          handleConflictingFile(
              segment,
              message,
              body,
              oldName,
              name,
              Utils.getEncapsulatedId(storageType, externalId, initialFileId),
              Utils.getEncapsulatedId(storageType, externalId, newFileId),
              xSessionId,
              userId,
              modifier,
              conflictingFileReason,
              fileSessionExpired,
              isSameFolder,
              AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
        }

        // Get the updated microVersionId of workspace
        versionId = OnshapeApiOverlay.getWorkspaceVersionId(
            apiUrl, accessToken, documentId, workspace, fileId);
        // versionId = uploadResult.getObject().getString("microversionId");

        if (versionId == null) {
          log.error("OS: versionId is null for file: " + documentId + "_" + workspace + "_" + fileId
              + " folderId: " + folderId + " OS URL: " + url);
          versionId = UUID.randomUUID().toString();
        }
      }
      String responseFileId;
      if (initialFileId == null) {
        initialFileId = documentId + "_" + workspace + "_" + fileId;
        if (!newDocument) {
          // we created/updated a new element in an existing document
          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      Utils.getEncapsulatedId(storageType, externalId, initialFileId))
                  .put(Field.VERSION_ID.getName(), versionId)
                  .put(
                      Field.THUMBNAIL_NAME.getName(),
                      StorageType.getShort(storageType) + "_" + documentId + "_" + workspace + "_"
                          + fileId + "_" + versionId + ".png")
                  .put("createdFolder", false),
              mills);
        } else {
          // we created/updated a new element AND created a new document
          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      Utils.getEncapsulatedId(storageType, externalId, initialFileId))
                  .put(
                      Field.FOLDER_ID.getName(),
                      Utils.getEncapsulatedId(storageType, externalId, documentId))
                  .put(Field.VERSION_ID.getName(), versionId)
                  .put(
                      Field.THUMBNAIL_NAME.getName(),
                      StorageType.getShort(storageType) + "_" + documentId + "_" + workspace + "_"
                          + fileId + "_" + versionId + ".png")
                  .put("createdFolder", true),
              mills);
        }
        responseFileId = initialFileId;
      } else {
        JsonObject response = new JsonObject()
            .put(Field.IS_CONFLICTED.getName(), isConflictFile)
            .put(Field.VERSION_ID.getName(), versionId)
            .put(
                Field.THUMBNAIL_NAME.getName(),
                StorageType.getShort(storageType) + "_" + documentId + "_" + workspace + "_"
                    + fileId + "_" + versionId + ".png")
            .put("createdFolder", false);
        if (isConflictFile) {
          if (Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            response.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
            if (newDocument) {
              response
                  .put(
                      Field.FOLDER_ID.getName(),
                      Utils.getEncapsulatedId(storageType, externalId, documentId))
                  .put("createdFolder", true);
            }
          }
          if (Utils.isStringNotNullOrEmpty(responseName)) {
            response.put(Field.NAME.getName(), responseName);
          }
          String newFileId = documentId + "_" + workspace + "_" + fileId;
          responseFileId = newFileId;
          response.put(
              Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, newFileId));
        } else {
          responseFileId = initialFileId;
          response.put(
              Field.FILE_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, initialFileId));
        }
        sendOK(segment, message, response, mills);
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
                  responseFileId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(
                                Field.FILE_ID.getName(),
                                documentId + "_" + workspace + "_" + fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }

      if (isFileUpdate && parsedMessage.hasAnyContent()) {
        eb_send(
            segment,
            WebSocketManager.address + ".newVersion",
            new JsonObject()
                .put(Field.FILE_ID.getName(), initialFileId)
                .put(Field.VERSION_ID.getName(), versionId)
                .put(Field.X_SESSION_ID.getName(), xSessionId)
                .put(Field.EMAIL.getName(), userEmail)
                .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void handleOSException(
      Message<JsonObject> message, Entity segment, OnshapeException OSException) {
    HttpResponse<JsonNode> response = OSException.getResponse();
    log.error(response.getStatus() + " "
        + (response.getBody() != null ? response.getBody().toString() : emptyString));
  }

  private HttpResponse<JsonNode> logOnshapeException(OnshapeException onshapeException) {
    HttpResponse<JsonNode> response = onshapeException.getResponse();
    log.error(response.getStatus() + " "
        + (response.getBody() != null ? response.getBody().toString() : emptyString));
    return response;
  }

  private <T> void handleOnshapeException(
      Message<T> message, Entity segment, OnshapeException onshapeException) {
    HttpResponse<JsonNode> response = logOnshapeException(onshapeException);
    sendError(segment, message, response);
  }

  public void doGetFile(Message<JsonObject> message) {
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
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String folderId = args[0];
      String workspace = args[1];
      fileId = args[2];
      String name = null;
      if (latest || versionId == null) {
        HttpResponse<JsonNode> response =
            OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, folderId, true);
        if (!isSuccessfulRequest(response.getStatus())) {
          sendError(segment, message, response);
          return;
        }
        JSONObject obj = response.getBody().getObject();
        workspace = obj.getJSONObject("defaultWorkspace").getString(Field.ID.getName());
        response = OnshapeApiOverlay.getElementFromDocumentContent(
            apiUrl, accessToken, folderId, workspace, fileId);
        if (isSuccessfulRequest(response.getStatus())) {
          if (!response.getBody().getArray().isEmpty()) {
            JSONObject object = response.getBody().getArray().getJSONObject(0);
            name = object.getString(Field.NAME.getName());
          }
          // we do not want the microversion of the element but of the workspace
          try {
            versionId = OnshapeApiOverlay.getWorkspaceVersionId(
                apiUrl, accessToken, folderId, workspace, fileId);
          } catch (OnshapeException ignored) {
          }
        }
      }
      final String versionPart = latest ? ("/w/" + workspace) : ("/m/" + versionId);
      if (returnDownloadUrl) {
        String downloadUrl =
            OnshapeApiOverlay.getFileBlobUrl(apiUrl, folderId, workspace, fileId, versionPart);
        sendDownloadUrl(
            segment, message, downloadUrl, null, versionId, accessToken, null, mills);
        return;
      }
      HttpResponse<byte[]> blobResponse = OnshapeApiOverlay.getFileBlob(
          apiUrl, accessToken, folderId, workspace, fileId, versionPart);
      if (!isSuccessfulRequest(blobResponse.getStatus())) {
        sendError(segment, message, blobResponse);
        return;
      }
      if (download) {
        HttpResponse<JsonNode> response =
            OnshapeApiOverlay.getFileInfo(apiUrl, accessToken, folderId, workspace, fileId);
        if (isSuccessfulRequest(response.getStatus())) {
          name = response.getBody().getObject().getString(Field.NAME.getName());
        }
      }
      finishGetFile(
          message, start, end, blobResponse.getBody(), storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  public void doDeleteFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      HttpResponse<JsonNode> response;
      if (folderId.startsWith(FOLDER)) {
        response = AWSXRayUnirest.delete(
                apiUrl + "/folders/" + folderId.substring(2), "Onshape.deleteFolder")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .asJson();
      } else if (folderId.startsWith(PROJECT)) {
        JSONObject body = new JSONObject();
        body.put(
            "itemsToMove",
            new JSONArray()
                .put(new JSONObject()
                    .put(Field.RESOURCE_TYPE.getName(), "project")
                    .put(Field.ID.getName(), folderId.substring(2))));
        response = AWSXRayUnirest.post(apiUrl + "/globaltreenodes/magic/4", "Onshape.deleteProject")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(body)
            .asJson();
      } else {
        response = AWSXRayUnirest.delete(
                apiUrl + "/documents/" + folderId, "Onshape.deleteDocument")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .asJson();
      }

      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doRenameFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
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
    if (folderId == null || name == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      HttpResponse<JsonNode> response;
      if (folderId.startsWith(FOLDER)) {
        response = AWSXRayUnirest.post(
                apiUrl + "/folders/" + folderId.substring(2), "Onshape.renameFolder")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(new JSONObject().put(Field.NAME.getName(), name))
            .asJson();
      } else if (folderId.startsWith(PROJECT)) {
        response = AWSXRayUnirest.post(
                apiUrl + "/projects/" + folderId.substring(2), "Onshape.renameProject")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(new JSONObject()
                .put(Field.NAME.getName(), name)
                .put(Field.ID.getName(), folderId.substring(2)))
            .asJson();
      } else {
        response = AWSXRayUnirest.post(apiUrl + "/documents/" + folderId, "Onshape.renameDocument")
            .header("Authorization", BEARER + accessToken)
            .header("Content-Type", "application/json")
            .body(new JSONObject().put(Field.NAME.getName(), name))
            .asJson();
      }
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String parentId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
    String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String onlyDotsInName = name;
    onlyDotsInName = onlyDotsInName.replaceAll("\\.", emptyString);
    if (onlyDotsInName.equals(emptyString)) {
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
    if (parentId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    try {
      // this used to create Onshape documents, but now it creates a folder.
      // HttpResponse<JsonNode> response = AWSXRayUnirest.post(apiUrl + "/documents", "Onshape
      // .createFolder")
      //        .header("Authorization", "Bearer " + accessToken)
      //        .header("Content-Type", "application/json")
      //        .body(new JSONObject().put(Field.NAME.getName(), name).put(Field.OWNER_TYPE
      //        .getName(), 0).put(Field.IS_PUBLIC.getName(),
      //        false))  //NON-NLS
      //        .asJson();
      JSONObject obj = new JSONObject()
          .put(Field.NAME.getName(), name)
          .put(Field.OWNER_TYPE.getName(), 0)
          .put(Field.IS_PUBLIC.getName(), false)
          .put(Field.OWNER_ID.getName(), externalId);
      if (parentId.startsWith(FOLDER)) {
        obj.put(Field.PARENT_ID.getName(), parentId.substring(2));
      } else if (parentId.startsWith(PROJECT)) {
        obj.put(Field.PROJECT_ID.getName(), parentId.substring(2));
      } else if (parentId.equals(Field.MINUS_1.getName())) {
        obj.put(Field.PARENT_ID.getName(), (String) null);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CannotCreateFolderInsideDocument"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      HttpResponse<JsonNode> response = AWSXRayUnirest.post(
              apiUrl + "/folders", "Onshape.createFolder")
          .header("Authorization", BEARER + accessToken)
          .header("Content-Type", "application/json")
          .body(obj)
          .asJson();

      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(
                    Field.FOLDER_ID.getName(),
                    Utils.getEncapsulatedId(
                        StorageType.getShort(storageType),
                        externalId,
                        FOLDER + response.getBody().getObject().getString(Field.ID.getName()))),
            mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String query = getRequiredString(segment, Field.QUERY.getName(), message, message.body());
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
          .map(onshapeUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray foldersJson = new JsonArray();
            String accessToken = onshapeUser.getString(Field.ACCESS_TOKEN.getName());
            try {
              accessToken =
                  EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
            } catch (Exception e) {
              XRayManager.endSegment(subSegment);
              log.error(e);
              return null;
            }
            String next = null;
            do {
              HttpResponse<JsonNode> response = null;
              try {
                response = AWSXRayUnirest.get(
                        Utils.isStringNotNullOrEmpty(next)
                            ? next
                            : (apiUrl + "/documents?q="
                                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                                + "&filter=0"),
                        "Onshape.search")
                    .header("Authorization", BEARER + accessToken)
                    .asJson();
              } catch (UnirestException e) {
                log.error(e);
              }
              if (response != null && isSuccessfulRequest(response.getStatus())) {
                JSONObject obj;
                JsonObject json = new JsonObject();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                next = response.getBody().getObject().getJSONArray("items").length() < 20
                    ?
                    // os limit is 20
                    null
                    : response.getBody().getObject().has("next")
                        ? response.getBody().getObject().getString("next")
                        : null;

                for (Object item : response.getBody().getObject().getJSONArray("items")) {
                  obj = (JSONObject) item;
                  String permission = getPermission(obj);
                  JSONArray permissionSet = getPermissionSet(obj);
                  boolean viewOnly = isReadOnly(permission, permissionSet);
                  boolean shared = false;
                  JsonArray viewer = new JsonArray();
                  JsonArray editor = new JsonArray();
                  long updateDate = 0;
                  try {
                    updateDate = sdf.parse(obj.getString("modifiedAt")
                            .substring(0, obj.getString("modifiedAt").lastIndexOf(".")))
                        .getTime();
                  } catch (ParseException e) {
                    log.error(e);
                  }
                  String changer = obj.getJSONObject(Field.MODIFIED_BY.getName())
                      .getString(Field.NAME.getName());
                  String changerId =
                      obj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.ID.getName());
                  String owner = emptyString;
                  boolean isOwner = isOwner(permission, permissionSet);
                  boolean canMove = obj.getBoolean(Field.CAN_MOVE.getName());
                  try {
                    owner =
                        obj.getJSONObject(Field.OWNER.getName()).getString(Field.NAME.getName());
                  } catch (Exception ignore) {
                  }
                  try {
                    json = new JsonObject()
                        .put(Field.WS_ID.getName(), obj.getString(Field.ID.getName()))
                        .put(
                            Field.ENCAPSULATED_ID.getName(),
                            Utils.getEncapsulatedId(
                                storageType,
                                onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                                obj.getString(Field.ID.getName())))
                        .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
                        .put(
                            Field.PARENT.getName(),
                            Utils.getEncapsulatedId(
                                storageType,
                                onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                                (!obj.optString(Field.PARENT_ID.getName()).isEmpty()
                                    ? FOLDER + obj.getString(Field.PARENT_ID.getName())
                                    : Field.MINUS_1.getName())))
                        .put(Field.OWNER.getName(), owner)
                        .put(
                            Field.CREATION_DATE.getName(),
                            sdf.parse(obj.getString("createdAt")
                                    .substring(0, obj.getString("createdAt").lastIndexOf(".")))
                                .getTime())
                        .put(Field.UPDATE_DATE.getName(), updateDate)
                        .put(Field.CHANGER.getName(), changer)
                        .put(Field.CHANGER_ID.getName(), changerId)
                        .put(
                            Field.SIZE.getName(),
                            obj.has("sizeBytes")
                                ? Utils.humanReadableByteCount(obj.getInt("sizeBytes"))
                                : 0)
                        .put(
                            Field.SIZE_VALUE.getName(),
                            obj.has("sizeBytes") ? obj.getInt("sizeBytes") : 0)
                        .put(Field.SHARED.getName(), shared)
                        .put(Field.VIEW_ONLY.getName(), viewOnly)
                        .put(Field.IS_OWNER.getName(), isOwner)
                        .put(Field.CAN_MOVE.getName(), canMove)
                        .put(
                            Field.SHARE.getName(),
                            new JsonObject()
                                .put(Field.VIEWER.getName(), viewer)
                                .put(Field.EDITOR.getName(), editor))
                        .put("icon", "https://icons.kudo.graebert.com/onshape-document.svg")
                        .put("icon_black", "https://icons.kudo.graebert.com/onshape-document.svg");
                  } catch (ParseException e) {
                    log.error(e);
                  }
                  foldersJson.add(json);
                }
              }
            } while (next != null && !next.isEmpty());

            // lets try the same for folders. Does not work yet
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), onshapeUser.getString(Field.EMAIL.getName()))
                .put(Field.FOLDERS.getName(), foldersJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
      /*
      next = null;
      do {
          HttpResponse<JsonNode> response = null;
          try {
              response = AWSXRayUnirest.get(
                      next != null && !next.isEmpty() ? next :
                              (apiUrl + "/folders?q=" + URLEncoder.encode(query,
                              "UTF-8") + "&filter=0"), "Onshape.search")
                      .header("Authorization", BEARER + accessToken)
                      .asJson();
          } catch (UnirestException | UnsupportedEncodingException e) {
              e.printStackTrace();
          }
          if (isSuccessfulRequest(response.getStatus())) {
              JSONObject obj;
              JsonObject json = new JsonObject();
              SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
              next = response.getBody().getObject().getJSONArray("items").length() < 20
               ? // os limit is 20
                      null :
                      response.getBody().getObject().has("next") ? response.getBody()
                      .getObject().getString("next") : null;
              for (Object item : response.getBody().getObject().getJSONArray("items")) {
                  obj = (JSONObject) item;
                  String permission = obj.optString(Field.PERMISSION.getName());
                  JSONArray permissionSet = obj.getJSONArray("permissionSet");
                  boolean viewOnly = isReadOnly(permission, permissionSet);
                  boolean shared = false;
                  JsonArray viewer = new JsonArray();
                  JsonArray editor = new JsonArray();
                  long updateDate = 0;
                  try {
                      updateDate = sdf.parse(obj.getString("modifiedAt").substring(0,
                      obj.getString("modifiedAt").lastIndexOf("."))).getTime();
                  } catch (ParseException e) {
                      e.printStackTrace();
                  }
                  String changer = obj.getJSONObject(Field.MODIFIED_BY.getName()).getString(Field.NAME.getName());
                  String owner = emptyString;
                  boolean isOwner = isOwner(permission, permissionSet);
                  try {
                      owner = obj.getJSONObject(Field.OWNER.getName()).getString(Field.NAME.getName());
                  } catch (Exception igonre) {
                  }
                  try {
                      json = new JsonObject()
                              .put(Field.WS_ID.getName(), obj.getString(Field.ID.getName()))
                              .put(Field.ENCAPSULATED_ID.getName(), Utils.getEncapsulatedId(storageType,
                                      onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                                      obj.getString(Field.ID.getName())))
                              .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
                              .put(Field.PARENT.getName(), Utils.getEncapsulatedId(storageType,
                                      onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                                      (!obj.optString(Field.PARENT_ID.getName()).isEmpty() ? FOLDER +
                                      obj.getString(Field.PARENT_ID.getName()) : Field.MINUS_1.getName())))
                              .put(Field.OWNER.getName(), owner)
                              .put(Field.CREATION_DATE.getName(), sdf.parse(obj.getString("createdAt")
                              .substring(0, obj.getString("createdAt").lastIndexOf(".")
                              )).getTime())
                              .put(Field.UPDATE_DATE.getName(), updateDate)
                              .put(Field.CHANGER.getName(), changer)
                              .put(Field.SIZE.getName(), obj.has("sizeBytes") ?
                              humanReadableByteCount(obj.getInt("sizeBytes")) : 0)
                              .put(Field.SIZE_VALUE.getName(), obj.has("sizeBytes") ? obj.getInt
                              ("sizeBytes") : 0)
                              .put(Field.SHARED.getName(), shared)
                              .put(Field.VIEW_ONLY.getName(), viewOnly)
                              .put(Field.IS_OWNER.getName(), isOwner)
                              .put(Field.CAN_MOVE.getName(), canMove)
                              .put(Field.SHARE.getName(), new JsonObject().put(Field.VIEWER.getName(), viewer).put
                              (Field.EDITOR.getName(), editor));
                  } catch (ParseException e) {
                      e.printStackTrace();
                  }
                  foldersJson.add(json);
              }
          }
      } while (next != null && !next.isEmpty());
      */
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
      HttpResponse<JsonNode> response = AWSXRayUnirest.post(
              oauthUrl + "token?grant_type=authorization_code&code=" + code + "&client_id="
                  + CLIENT_ID
                  + "&client_secret=" + CLIENT_SECRET + "&redirect_uri="
                  + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8),
              "Onshape.postAuthCode")
          .header("Content-type", "application/x-www-form-urlencoded")
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = response.getBody();
      String accessToken = body.getObject().getString(Field.ACCESS_TOKEN.getName());
      String refreshToken = body.getObject().getString(Field.REFRESH_TOKEN.getName());
      long expiresIn = body.getObject().getLong("expires_in");
      response = AWSXRayUnirest.get(apiUrl + "/users/sessioninfo", "Onshape.getSessionInfo")
          .header("Authorization", BEARER + accessToken)
          .asJson();
      if (!isSuccessfulRequest(response.getStatus())) {
        sendError(segment, message, response);
      } else {
        try {
          accessToken =
              EncryptHelper.encrypt(accessToken, config.getProperties().getFluorineSecretKey());
          refreshToken =
              EncryptHelper.encrypt(refreshToken, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return;
        }
        body = response.getBody();
        String onshapeId = body.getObject().getString(Field.ID.getName());
        String email = body.getObject().getString(Field.EMAIL.getName());
        Item externalAccount = ExternalAccounts.getExternalAccount(userId, onshapeId);
        if (externalAccount == null) {
          externalAccount = new Item()
              .withPrimaryKey(
                  Field.FLUORINE_ID.getName(), userId, Field.EXTERNAL_ID_LOWER.getName(), onshapeId)
              .withString(Field.F_TYPE.getName(), storageType.toString())
              .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());
          storageLog(
              segment,
              message,
              userId,
              graebertId,
              sessionId,
              username,
              storageType.toString(),
              onshapeId,
              true,
              intercomAccessToken);
        }
        externalAccount.withString(Field.EMAIL.getName(), email);
        externalAccount.withString(Field.ACCESS_TOKEN.getName(), accessToken);
        externalAccount.withString(Field.REFRESH_TOKEN.getName(), refreshToken);
        externalAccount.withLong(
            Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + (1000 * expiresIn));
        try {
          Sessions.updateSessionOnConnect(
              externalAccount, userId, storageType, onshapeId, sessionId);
        } catch (Exception e) {
          sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
          return;
        }
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String headerFolder, workspace;
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    externalId = checkAndGetExternalId(segment, message, jsonObject, externalId, fileId, userId);
    if (externalId == null) {
      return;
    }
    String accessToken = connect(segment, message, jsonObject, false);
    if (accessToken == null) {
      return;
    }
    try {
      String[] args = fileId.split("_");
      if (args.length == 1) {
        // it is a document
        // ignore?
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      } else if (args.length != 3) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "IncorrectFileid"),
            HttpStatus.BAD_REQUEST);
        return;
      } else {
        headerFolder = args[0];
        workspace = args[1];
        fileId = args[2];
      }
      HttpResponse<JsonNode> contentResponse = OnshapeApiOverlay.getElementFromDocumentContent(
          apiUrl, accessToken, headerFolder, workspace, fileId);
      if (!isSuccessfulRequest(contentResponse.getStatus())) {
        // We can return true, because it is used for RF validation only
        // https://graebert.atlassian.net/browse/XENON-30048
        // should be changed if this function is required somewhere else in the future.
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.IS_DELETED.getName(), true)
                .put("nativeResponse", contentResponse.getBody()),
            mills);
        return;
      }
      boolean isDeleted = contentResponse.getBody().getArray().length() <= 0;
      if (!isDeleted) {
        // check if document/folder is deleted
        HttpResponse<JsonNode> infoResponse =
            OnshapeApiOverlay.getDocumentInfo(apiUrl, accessToken, headerFolder, true);
        if (!isSuccessfulRequest(contentResponse.getStatus())
            || (Objects.nonNull(infoResponse.getBody())
                && infoResponse.getBody().getObject().has(Field.TRASH.getName())
                && infoResponse.getBody().getObject().getBoolean(Field.TRASH.getName()))) {
          sendOK(
              segment,
              message,
              new JsonObject()
                  .put(Field.IS_DELETED.getName(), true)
                  .put("nativeResponse", infoResponse.getBody()),
              mills);
          return;
        }
      }
      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), isDeleted), mills);
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

  private <T> String refreshToken(
      Entity segment, Message<T> message, String userId, String onshapeId, String refreshToken) {
    try {
      refreshToken =
          EncryptHelper.decrypt(refreshToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    try {
      HttpResponse<JsonNode> response = AWSXRayUnirest.post(
              oauthUrl + "token?grant_type" + "=refresh_token&refresh_token="
                  + refreshToken
                  + "&client_id=" + CLIENT_ID + "&client_secret="
                  + CLIENT_SECRET,
              "Onshape.refreshToken")
          .header("Content-type", "application/x-www-form-urlencoded")
          .asJson();

      debugLogResponse(oauthUrl + "token?grant_type=refresh_token", response, message);
      if (!isSuccessfulRequest(response.getStatus())) {
        logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_REFRESH_TOKENS);
        sendError(segment, message, response);
        return null;
      } else {
        Item onshapeUser = ExternalAccounts.getExternalAccount(userId, onshapeId);
        String newAccessToken =
            response.getBody().getObject().getString(Field.ACCESS_TOKEN.getName());
        String newAccessTokenEncr = newAccessToken;
        String newRefreshToken =
            response.getBody().getObject().getString(Field.REFRESH_TOKEN.getName());
        try {
          newAccessToken =
              EncryptHelper.encrypt(newAccessToken, config.getProperties().getFluorineSecretKey());
          newRefreshToken =
              EncryptHelper.encrypt(newRefreshToken, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          log.error(e);
          logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return null;
        }
        onshapeUser
            .withString(Field.ACCESS_TOKEN.getName(), newAccessToken)
            .withString(Field.REFRESH_TOKEN.getName(), newRefreshToken)
            .withLong(
                Field.EXPIRES.getName(),
                GMTHelper.utcCurrentTime()
                    + (1000 * response.getBody().getObject().getLong("expires_in")));
        ExternalAccounts.saveExternalAccount(
            onshapeUser.getString(Field.FLUORINE_ID.getName()),
            onshapeUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
            onshapeUser);
        return newAccessTokenEncr;
      }
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, onshapeId, ConnectionError.CANNOT_REFRESH_TOKENS);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
      return null;
    }
  }

  private JsonObject getDocumentSharedEntries(
      String id, String accessToken, JsonArray editor, JsonArray viewer, boolean isDocument) {
    JsonObject owner = null;
    HttpResponse<JsonNode> shareResponse = AWSXRayUnirest.get(
            apiUrl + (isDocument ? "/documents/" : "/folders/") + id + "/acl",
            "Onshape.getSharedEntries")
        .header("Authorization", BEARER + accessToken)
        .asJson();
    if (shareResponse.getStatus() == HttpStatus.OK) {
      JsonObject responseBody = new JsonObject(shareResponse.getBody().toString());
      if (responseBody.containsKey(Field.OWNER.getName())) {
        owner = responseBody.getJsonObject(Field.OWNER.getName());
      }
      JsonArray inheritedEntries = new JsonArray();
      if (responseBody.containsKey("inheritedAcls")) {
        JsonArray inheritedAcls = responseBody.getJsonArray("inheritedAcls");
        if (!inheritedAcls.isEmpty()) {
          JsonObject aclObject = inheritedAcls.getJsonObject(0);
          if (aclObject.containsKey("entries")) {
            inheritedEntries = aclObject.getJsonArray("entries").stream()
                .map(entry -> ((JsonObject) entry).put("inherited", true))
                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
          }
        }
      }
      JsonArray normalEntries = new JsonArray();
      if (responseBody.containsKey("entries")) {
        normalEntries = responseBody.getJsonArray("entries");
      }
      JsonArray entries = normalEntries.addAll(inheritedEntries);
      if (!entries.isEmpty()) {
        entries.stream().map(o -> (JsonObject) o).forEach(entry -> {
          JsonObject share = new JsonObject()
              .put(Field.ENCAPSULATED_ID.getName(), entry.getString("entryId"))
              .put(Field.EMAIL.getName(), entry.getString(Field.EMAIL.getName()));
          if (entry.containsKey(Field.NAME.getName())
              && !entry.getString(Field.NAME.getName()).isEmpty()) {
            share.put(Field.NAME.getName(), entry.getString(Field.NAME.getName()));
          } else {
            share.put(
                Field.NAME.getName(),
                entry
                    .getString(Field.EMAIL.getName())
                    .substring(0, entry.getString(Field.EMAIL.getName()).lastIndexOf("@")));
          }
          if (entry.containsKey("inherited")) {
            share.put("inherited", true);
          }
          int permission = entry.getInteger(Field.PERMISSION.getName());
          JsonArray permissionSet = entry.getJsonArray("permissionSet");
          if (!permissionSet.contains(OWNER)) {
            if (permissionSet.contains(WRITE)) {
              editor.add(share);
            } else if ((permission == 8 || permission == 16 || permission == 32)
                && permissionSet.contains(READ)) {
              viewer.add(share);
            }
          }
        });
      }
    } else {
      String errorMessage;
      if (Objects.nonNull(shareResponse.getBody())) {
        errorMessage = shareResponse.getBody().toPrettyString();
      } else if (Objects.nonNull(shareResponse.getParsingError())
          && shareResponse.getParsingError().isPresent()) {
        errorMessage = shareResponse.getParsingError().get().getOriginalBody();
      } else {
        errorMessage = shareResponse.getStatusText();
      }
      log.error(
          "Onshape : Error in getting shared entries for objectId - " + id + " | " + errorMessage);
    }
    return owner;
  }

  private String getPermission(HttpResponse<JsonNode> response) {
    return getPermission(response.getBody().getObject());
  }

  private String getPermission(JSONObject object) {
    return object.optString(Field.PERMISSION.getName());
  }

  private JSONArray getPermissionSet(HttpResponse<JsonNode> response) {
    return getPermissionSet(response.getBody().getObject());
  }

  private JSONArray getPermissionSet(JSONObject object) {
    return object.getJSONArray("permissionSet");
  }

  private boolean isSharingAllowed(HttpResponse<JsonNode> response, boolean isDocument) {
    return isSharingAllowed(response.getBody().getObject(), isDocument);
  }

  private boolean isSharingAllowed(JSONObject object, boolean isDocument) {
    String permission = getPermission(object);
    JSONArray permissionSet = getPermissionSet(object);
    boolean reShare = false;
    for (Object perm : permissionSet) {
      if (RESHARE.equals(perm)) {
        reShare = true;
        break;
      }
    }
    if (reShare) {
      if (isDocument) {
        return (permission.equals(FULL) || permission.equals(OWNER) || permission.equals(RESHARE));
      } else {
        return !Utils.isStringNotNullOrEmpty(object.optString(Field.PARENT_ID.getName()));
      }
    }
    return false;
  }

  private JsonObject getOwnerAndCollaborators(String id, String accessToken) {
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    JsonObject owner = getDocumentSharedEntries(id, accessToken, editor, viewer, true);
    return new JsonObject()
        .put(Field.OWNER.getName(), owner)
        .put(Field.COLLABORATORS.getName(), editor.addAll(viewer));
  }

  private String checkAndGetExternalId(
      Entity segment,
      Message<JsonObject> message,
      JsonObject jsonObject,
      String externalId,
      String fileId,
      String userId) {
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        message.body().put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    if (externalId == null) {
      super.deleteSubscriptionForUnavailableFile(fileId, userId);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
    }
    return externalId;
  }

  @Override
  public void stop() {
    super.stop();
  }
}
