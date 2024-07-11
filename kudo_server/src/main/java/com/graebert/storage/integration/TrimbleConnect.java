package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
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
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.TrimbleCheckIn;
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
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import kong.unirest.JsonNode;
import kong.unirest.UnirestException;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class TrimbleConnect extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.TRIMBLE;

  @NonNls
  public static final String address = "trimble";

  @NonNls
  private static final String FULL_ACCESS = "FULL_ACCESS";

  @NonNls
  private static final String REMOVED = "REMOVED";

  @NonNls
  private static final String USER = "USER";

  @NonNls
  private static final String FILE = "FILE";

  @NonNls
  private static final String FOLDER = "FOLDER";

  @NonNls
  private static final Logger log = LogManager.getRootLogger();

  private static final StorageType storageType = StorageType.TRIMBLE;
  private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  private final String[] specialCharacters = {"<", ">", "\"", "|", ":", "?", "*", "/", "\\"};
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private String authentication, tokenUrl, apiUrl, REDIRECT_URI;
  private S3Regional s3Regional = null;

  public TrimbleConnect() {}

  public enum TCFileStatus {
    CHECKIN,
    CHECKOUT;

    public static KudoErrorCodes getStatusError(TCFileStatus status) {
      return status.equals(CHECKIN)
          ? KudoErrorCodes.UNABLE_TO_CHECKIN_FILE
          : KudoErrorCodes.UNABLE_TO_CHECKOUT_FILE;
    }

    public static String getStatusErrorCode(TCFileStatus status) {
      return status.equals(CHECKIN) ? "FL17" : "FL5";
    }
  }

  @Override
  public void start() throws Exception {
    super.start();
    String clientId = config.getProperties().getTrimbleClientId(),
        clientSecret = config.getProperties().getTrimbleClientSecret();
    tokenUrl = config.getProperties().getTrimbleTokenUrl();
    apiUrl = config.getProperties().getTrimbleApiUrl();
    REDIRECT_URI = config.getProperties().getTrimbleRedirectUri();
    authentication = org.apache.commons.codec.binary.Base64.encodeBase64String(
        (clientId + ":" + clientSecret).getBytes());

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
    eb.consumer(address + ".getAllFiles", this::doGetAllFiles);
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
    eb.consumer(address + ".checkout", this::doCheckout);
    eb.consumer(address + ".checkin", this::doCheckin);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-trimble");
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
      String accessToken = connect(segment, message, message.body(), false);
      String apiUrl = message.body().getString("apiUrl");

      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + fileIds.getId(), "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();

      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        for (Object o : message.body().getJsonArray(Field.REGIONS.getName())) {
          JsonObject region = new JsonObject((String) o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (apiUrl.equals(regUrl)) {
            continue;
          }
          apiUrl = regUrl;
          response = AWSXRayUnirest.get(
                  apiUrl + "/files/" + fileIds.getId(), "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }

      if (response.getStatus() != HttpStatus.OK) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND,
            "FileNotFound");
        return;
      }

      JSONObject info = (JSONObject) new JsonNode(response.getBody()).getArray().get(0);

      message.body().put(Field.NAME.getName(), info.getString(Field.NAME.getName()));

      String realVersionId = VersionType.parse(versionId).equals(VersionType.LATEST)
          ? info.getString(Field.VERSION_ID.getName())
          : versionId;

      // download
      byte[] data = new byte[0];
      HttpResponse<String> downloadUrlRequest = AWSXRayUnirest.get(
              apiUrl + "/files/fs/" + fileIds.getId() + "/downloadurl?versionId=" + realVersionId,
              "TrimbleConnect.getData")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (downloadUrlRequest.getStatus() != HttpStatus.OK) {
        sendError(segment, message, downloadUrlRequest);
        return;
      } else {
        try {
          String downloadURL =
              new JsonObject(downloadUrlRequest.getBody()).getString(Field.URL.getName());
          if (Utils.isStringNotNullOrEmpty(downloadURL)) {
            HttpResponse<byte[]> fileDataRequest = AWSXRayUnirest.get(
                    downloadURL, "TrimbleConnect.download")
                .header("Authorization", "Bearer " + accessToken)
                .asBytes();
            if (fileDataRequest.getStatus() == HttpStatus.OK) {
              data = fileDataRequest.getBody();
            }
          }
        } catch (Exception ignored) {
        }
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
          info.getString(Field.NAME.getName()),
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
      String accessToken = connect(segment, message, message.body(), false);
      String apiUrl = message.body().getString("apiUrl");

      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + fileIds.getId(), "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();

      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        for (Object o : message.body().getJsonArray(Field.REGIONS.getName())) {
          JsonObject region = new JsonObject((String) o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (apiUrl.equals(regUrl)) {
            continue;
          }
          apiUrl = regUrl;
          response = AWSXRayUnirest.get(
                  apiUrl + "/files/" + fileIds.getId(), "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }

      if (response.getStatus() != HttpStatus.OK) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND,
            "FileNotFound");
        return;
      }

      JSONObject info = (JSONObject) new JsonNode(response.getBody()).getArray().get(0);

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          byte[] data = getFileContent(apiUrl, fileIds.getId(), versionId, accessToken);
          if (data.length == 0) {
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

      List<String> collaboratorsList = Collections.singletonList(
          info.getJSONObject(Field.CREATED_BY.getName()).getString(Field.ID.getName()));

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.NAME.getName(), info.getString(Field.NAME.getName()))
              .put(
                  Field.OWNER_IDENTITY.getName(),
                  ExternalAccounts.getExternalEmail(userId, fileIds.getExternalId()))
              .put(Field.COLLABORATORS.getName(), new JsonArray(collaboratorsList)),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
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

  public void doRestore(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    String accessToken = connect(segment, message);
    String apiUrl = message.body().getString("apiUrl");
    try {
      // download version's data
      byte[] data = getFileContent(apiUrl, fileId, versionId, accessToken);

      // upload data to new version
      HttpResponse<String> response;
      JsonNode body;
      response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      body = new JsonNode(response.getBody());
      JSONObject obj = (JSONObject) body.getArray().get(0);
      String projectId = obj != null ? obj.getString(Field.PARENT_ID.getName()) : null;
      String name = obj != null ? obj.getString(Field.NAME.getName()) : null;

      response = AWSXRayUnirest.post(
              apiUrl + "/files?fileId=" + fileId + "&parentId=" + projectId,
              "TrimbleConnect.uploadData")
          .header("Authorization", "Bearer " + accessToken)
          .field(
              Field.FILE.getName(),
              new ByteArrayInputStream(data),
              name != null ? name : "version.dwg") // NON-NLS
          .field("Content-Type", "multipart/form-data") // NON-NLS
          .asString();
      if (response.getStatus() != HttpStatus.CREATED && response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(
                  Field.VERSION_ID.getName(),
                  new JsonArray(response.getBody())
                      .getJsonObject(0)
                      .getString(Field.VERSION_ID.getName())),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private VersionInfo getVersionInfo(
      String fileId, JsonObject versionObject, String lastVersionId, String currentUserId) {
    long creationDate = 0;
    try {
      creationDate = sdf.parse(versionObject
              .getString("createdOn")
              .substring(0, versionObject.getString("createdOn").lastIndexOf("+")))
          .getTime();
    } catch (Exception ignore) {
    }
    JsonObject user = versionObject.getJsonObject(Field.MODIFIED_BY.getName());
    VersionModifier versionModifier = new VersionModifier();
    if (user != null) {
      versionModifier.setCurrentUser(user.getString(Field.ID.getName()).equals(currentUserId));
      versionModifier.setEmail(user.getString(Field.USER_EMAIL.getName()));
      versionModifier.setId(user.getString(Field.ID.getName()));
      versionModifier.setName(user.getString(Field.FIRST_NAME.getName()) + " "
          + user.getString(Field.LAST_NAME.getName()));
    }
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(
        !versionObject.getString(Field.VERSION_ID.getName()).equals(lastVersionId));
    versionPermissions.setCanDelete(false);
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    VersionInfo versionInfo = new VersionInfo(
        versionObject.getString(Field.VERSION_ID.getName()),
        creationDate,
        versionObject.getString(Field.NAME.getName()));
    versionInfo.setHash(versionObject.getString(Field.HASH.getName()));
    versionInfo.setSize(versionObject.getLong(Field.SIZE.getName()));
    versionInfo.setModifier(versionModifier);
    versionInfo.setPermissions(versionPermissions);
    try {
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          storageType, fileId, versionObject.getString(Field.VERSION_ID.getName()));
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    if (id == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    JsonObject jsonObject = message.body();
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> infoResponse = AWSXRayUnirest.get(
              apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (infoResponse.getStatus() != HttpStatus.OK) {
        sendError(segment, message, infoResponse);
        return;
      }
      String lastVersionId =
          new JsonNode(infoResponse.getBody()).getObject().getString(Field.VERSION_ID.getName());

      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + id + "/versions", "TrimbleConnect.getObjectVersions")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONArray arr = body.getArray();
      JsonArray results = new JsonArray();
      for (Object item : arr) {
        JSONObject obj = (JSONObject) item;
        results.add(getVersionInfo(id, new JsonObject(obj.toString()), lastVersionId, externalId)
            .toJson());
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              HttpResponse<String> response2 = AWSXRayUnirest.get(
                      apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
                  .header("Authorization", "Bearer " + accessToken)
                  .asString();
              if (response2.getStatus() == HttpStatus.OK) {
                String name = ((JSONObject)
                        new JsonNode(response.getBody()).getArray().get(0))
                    .getString(Field.NAME.getName());
                if (Utils.isStringNotNullOrEmpty(name)) {
                  ext = Extensions.getExtension(name);
                }
              }
            } catch (Exception ex) {
              log.warn("[TRIMBLE] Get versions: Couldn't get object info to get extension.", ex);
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            results.forEach(revision -> requiredVersions.add(new JsonObject()
                .put(Field.FILE_ID.getName(), id)
                .put(
                    Field.VERSION_ID.getName(),
                    ((JsonObject) revision).getString(Field.ID.getName()))
                .put(Field.EXT.getName(), finalExt)));
            eb_send(
                blockingSegment,
                ThumbnailsManager.address + ".create",
                jsonObject.put(Field.IDS.getName(), requiredVersions));
          });
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doClone(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
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
          .map(trimbleUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray foldersJson = new JsonArray(), filesJson = new JsonArray();
            String accessToken = trimbleUser.getString(Field.ACCESS_TOKEN.getName());
            try {
              accessToken =
                  EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
            } catch (Exception e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return null;
            }
            if (trimbleUser.hasAttribute(Field.SERVER.getName())) {
              apiUrl = "https:"
                  + new JSONObject(trimbleUser.getString(Field.SERVER.getName()))
                      .getString(Field.ORIGIN.getName())
                  + "/tc/api/2.0";
            }
            HttpResponse<String> response = null;
            try {
              response = AWSXRayUnirest.get(
                      apiUrl + "/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                          + "&type=FILE,FOLDER,PROJECT",
                      "TrimbleConnect.search")
                  // ,PROJECT
                  .header("Authorization", "Bearer " + accessToken)
                  .header("Content-type", "application/json")
                  .asString();
            } catch (UnirestException e) {
              log.error(e);
            }
            if (response == null || response.getStatus() != HttpStatus.OK) {
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), StorageType.TRIMBLE.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), trimbleUser.getString(Field.EMAIL.getName()))
                  .put(Field.FOLDERS.getName(), foldersJson)
                  .put(Field.FILES.getName(), filesJson);
            }
            JsonNode body = new JsonNode(response.getBody());
            JSONObject obj;
            JsonObject json;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            for (Object o : body.getArray()) {
              obj = ((JSONObject) o).getJSONObject("details");
              boolean isFile = ((JSONObject) o).getString(Field.TYPE.getName()).equals(FILE);
              boolean isFolder =
                  ((JSONObject) o).getString(Field.TYPE.getName()).equals(FOLDER);
              if (isFolder) {
                // if the path is empty it is really the root folder of a project
                if (obj.has(Field.PATH.getName())) {
                  if (obj.getJSONArray(Field.PATH.getName()).isEmpty()) { // bug ddidenko
                    continue;
                  }
                }
              }
              JsonArray viewer = new JsonArray();
              JsonArray editor = new JsonArray();
              String id = obj.getString(Field.ID.getName());
              String parent;
              if (obj.has(Field.PROJECT_ID.getName())) {
                if (obj.has(Field.PARENT_ID.getName())
                    && !obj.getJSONArray(Field.PATH.getName()).isEmpty()) {
                  parent = obj.getString(Field.PARENT_ID.getName());
                } else {
                  parent = obj.getString(Field.PROJECT_ID.getName()) + ":";
                }
              } else {
                parent = Field.MINUS_1.getName();
              }
              long createdOn = 0;
              if (obj.has("createdOn")) {
                String str = obj.getString("createdOn");
                if (str.contains(".")) {
                  str = str.substring(0, obj.getString("createdOn").lastIndexOf("."));
                }
                try {
                  createdOn = sdf.parse(str).getTime();
                } catch (ParseException e) {
                  log.error(e);
                }
              }
              long modifiedOn = 0;
              if (obj.has("modifiedOn")) {
                String str = obj.getString("modifiedOn");
                if (str.contains(".")) {
                  str = str.substring(0, obj.getString("modifiedOn").lastIndexOf("."));
                }
                try {
                  modifiedOn = sdf.parse(str).getTime();
                } catch (ParseException e) {
                  log.error(e);
                }
              }
              String fileId =
                  id + (obj.has("rootId") ? (":" + obj.getString("rootId")) : emptyString);
              json = new JsonObject()
                  .put(Field.WS_ID.getName(), fileId)
                  .put(
                      Field.ENCAPSULATED_ID.getName(),
                      StorageType.getShort(StorageType.TRIMBLE) + "+"
                          + trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()) + "+" + fileId)
                  .put(
                      !isFile ? Field.NAME.getName() : Field.FILE_NAME.getName(),
                      obj.getString(Field.NAME.getName()))
                  .put(
                      !isFile ? Field.PARENT.getName() : Field.FOLDER_ID.getName(),
                      StorageType.getShort(StorageType.TRIMBLE) + "+"
                          + trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()) + "+" + parent)
                  .put(Field.OWNER.getName(), emptyString)
                  .put(Field.CREATION_DATE.getName(), createdOn)
                  .put(Field.UPDATE_DATE.getName(), modifiedOn)
                  .put(Field.CHANGER.getName(), parseObjForModifierName(obj))
                  .put(Field.CHANGER_ID.getName(), parseObjForModifierId(obj))
                  .put(
                      Field.SIZE.getName(),
                      Utils.humanReadableByteCount(obj.getInt(Field.SIZE.getName())))
                  .put(Field.SIZE_VALUE.getName(), obj.getInt(Field.SIZE.getName()))
                  .put(Field.SHARED.getName(), false)
                  .put(Field.VIEW_ONLY.getName(), false)
                  .put(Field.IS_OWNER.getName(), false)
                  .put(
                      Field.SHARE.getName(),
                      new JsonObject()
                          .put(Field.VIEWER.getName(), viewer)
                          .put(Field.EDITOR.getName(), editor));
              if (!isFile && !isFolder) {
                json.put("icon", "https://icons.kudo.graebert.com/trimble-project.svg");
                json.put("icon_black", "https://icons.kudo.graebert.com/trimble-project.svg");
              }
              if (isFile) {
                filesJson.add(json);
              } else {
                foldersJson.add(json);
              }
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), StorageType.TRIMBLE.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), trimbleUser.getString(Field.EMAIL.getName()))
                .put(Field.FOLDERS.getName(), foldersJson)
                .put(Field.FILES.getName(), filesJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void doGetAllFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String query = ".";
    if (userId == null) {
      return;
    }
    try {
      Iterator<Item> accounts = ExternalAccounts.getExternalAccountsByUserId(userId, storageType);
      List<Item> array = StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(accounts, 0), false)
          .collect(Collectors.toList());

      // RG: Refactored to not access global collection
      JsonArray result = new JsonArray(array.parallelStream()
          .map(trimbleUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray foldersJson = new JsonArray(), filesJson = new JsonArray();
            String accessToken = trimbleUser.getString(Field.ACCESS_TOKEN.getName());
            try {
              accessToken =
                  EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
            } catch (Exception e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return null;
            }
            if (trimbleUser.hasAttribute(Field.SERVER.getName())) {
              apiUrl = "https:"
                  + new JSONObject(trimbleUser.getString(Field.SERVER.getName()))
                      .getString(Field.ORIGIN.getName())
                  + "/tc/api/2.0";
            }
            HttpResponse<String> response = null;
            try {
              response = AWSXRayUnirest.get(
                      apiUrl + "/search?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                          + "&type=FILE,PROJECT",
                      "TrimbleConnect.search") // ,PROJECT
                  .header("Authorization", "Bearer " + accessToken)
                  .header("Content-type", "application/json")
                  .header("Range", "items=0-100000")
                  .asString();
            } catch (UnirestException e) {
              log.error(e);
            }

            JsonNode body =
                new JsonNode(Objects.nonNull(response) ? response.getBody() : emptyString);
            JSONObject obj;

            for (Object o : body.getArray()) {
              obj = ((JSONObject) o).getJSONObject("details");
              boolean isFile = ((JSONObject) o).getString(Field.TYPE.getName()).equals(FILE);

              String name = obj.getString(Field.NAME.getName());
              String id = obj.getString(Field.ID.getName());

              if (isFile) {
                filesJson.add(
                    new JsonObject().put(Field.ID.getName(), id).put(Field.NAME.getName(), name));
              }
            }

            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), StorageType.TRIMBLE.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), trimbleUser.getString(Field.EMAIL.getName()))
                .put(Field.FOLDERS.getName(), foldersJson)
                .put(Field.FILES.getName(), filesJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  public void doCreateSharedLink(Message<JsonObject> message) {
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
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + fileId, "TrimbleConnect.getFileInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      JSONObject json = (JSONObject) new JsonNode(response.getBody()).getArray().get(0);
      List<String> collaboratorsList = Collections.singletonList(
          json.getJSONObject(Field.CREATED_BY.getName()).getString(Field.ID.getName()));
      try {
        final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
        final String externalEmail = ExternalAccounts.getExternalEmail(userId, externalId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            externalId,
            userId,
            storageType,
            externalEmail,
            json.getString(Field.NAME.getName()),
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

    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
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
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
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

        String fullId = object.getString(Field.ID.getName());
        String objectId = Utils.parseItemId(fullId, Field.OBJECT_ID.getName())
            .getString(Field.OBJECT_ID.getName());
        boolean isFolder = type.equals(ObjectType.FOLDER);
        String postUrl;
        if (isFolder) {
          if (objectId.contains(":")) {
            String projectId = objectId.split(":")[0];
            postUrl = "/projects/" + projectId;
          } else {
            postUrl = "/folders/" + objectId;
          }
        } else {
          postUrl = "/files/" + objectId;
        }
        String finalPostUrl = postUrl;
        callables.add(() -> {
          Entity blockingSegment =
              XRayManager.createSubSegment(operationGroup, subSegment, "MultipleDownloadSegment");
          HttpResponse<String> response = AWSXRayUnirest.get(
                  apiUrl + finalPostUrl, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer" + " " + accessToken)
              .asString();
          if (response.getStatus() == HttpStatus.OK) {
            JSONObject body = new JSONObject(response.getBody());
            String name = body.getString(Field.NAME.getName());
            if (isFolder) {
              name = Utils.checkAndRename(folderNames, name, true);
              folderNames.add(name);
              zipFolder(
                  stream,
                  accessToken,
                  apiUrl,
                  objectId,
                  filter,
                  recursive,
                  name,
                  new HashSet<>(),
                  0,
                  request);
            } else {
              long fileSize =
                  body.has(Field.SIZE.getName()) ? body.getInt(Field.SIZE.getName()) : 0;
              if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
                excludeFileFromRequest(request, name, ExcludeReason.Large);
                return null;
              }
              addZipEntry(
                  accessToken,
                  apiUrl,
                  stream,
                  body,
                  filter,
                  emptyString,
                  filteredFileNames,
                  fileNames);
            }
          } else {
            throw new Exception(response.getStatusText() + " : " + response.getBody());
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
    } catch (Exception ex) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, ex);
    }
    sendOK(segment, message);
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
    String apiUrl = message.body().getString("apiUrl");
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ExecutorService executorService = Executors.newSingleThreadExecutor();
    ZipOutputStream stream = new ZipOutputStream(bos);
    try {
      Future<Void> futureTask = executorService.submit(() -> {
        Entity subSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "TrimbleZipFolderSegment");
        zipFolder(
            stream,
            accessToken,
            apiUrl,
            folderId,
            filter,
            recursive,
            emptyString,
            new HashSet<>(),
            0,
            request);
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
      String apiUrl,
      String id,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    Set<String> fileNames = new HashSet<>();
    if (id.contains(":")) {
      id = id.split(":")[1]; // getting folderId from projectId
    }
    HttpResponse<String> response = AWSXRayUnirest.get(
            apiUrl + "/folders/" + id + "/items", "TrimbleConnect.getFolderContent")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (response.getStatus() != HttpStatus.OK) {
      throw new Exception(response.getStatus() + " " + response.getStatusText());
    }
    JsonNode body = new JsonNode(response.getBody());
    if (body.getArray().isEmpty()) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    JSONObject obj;
    for (Object item : body.getArray()) {
      obj = (JSONObject) item;
      String properPath = path.isEmpty() ? path : path + File.separator;
      if (obj.getString(Field.TYPE.getName()).equals(FOLDER) && recursive) {
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          zipFolder(
              stream,
              accessToken,
              apiUrl,
              obj.getString(Field.ID.getName()),
              filter,
              true,
              properPath + obj.getString(Field.NAME.getName()),
              filteredFileNames,
              recursionDepth,
              request);
        } else {
          log.warn(
              "Zip folder recursion exceeds the limit for path " + path + " in " + storageType);
        }
      } else {
        long fileSize = obj.getInt(Field.SIZE.getName());
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, obj.getString(Field.NAME.getName()), ExcludeReason.Large);
          return;
        }
        addZipEntry(
            accessToken, apiUrl, stream, obj, filter, properPath, filteredFileNames, fileNames);
      }
    }
  }

  private void addZipEntry(
      String accessToken,
      String apiUrl,
      ZipOutputStream stream,
      JSONObject object,
      String filter,
      String properPath,
      Set<String> filteredFileNames,
      Set<String> fileNames)
      throws IOException {
    String name = object.getString(Field.NAME.getName());
    String versionId = object.getString(Field.VERSION_ID.getName());
    String fileId = object.getString(Field.ID.getName());
    byte[] data = getFileContent(apiUrl, fileId, versionId, accessToken);
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

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String externalId = jsonObject.containsKey(Field.EXTERNAL_ID.getName())
        ? jsonObject.getString(Field.EXTERNAL_ID.getName())
        : emptyString; // authorization code in case of encapsulationMode = 0
    if (externalId == null || externalId.isEmpty()) {
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return;
      }
    }
    String finalExternalId = externalId;
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    final String[] apiUrl = {message.body().getString("apiUrl")};
    try {
      if (folderId != null && folderId.equals(Field.MINUS_1.getName())) { // should not happen
        sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
        return;
      }
      boolean isProject = false;
      if (folderId != null && folderId.contains(":")) {
        folderId = folderId.split(":")[0];
        isProject = true;
      }
      String currentFolder = folderId;
      if (currentFolder == null) {
        HttpResponse<String> response = AWSXRayUnirest.get(
                apiUrl[0] + "/files/" + fileId, "TrimbleConnect.getFileInfo")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        JsonNode body = new JsonNode(response.getBody());
        JSONObject obj = (JSONObject) body.getArray().get(0);
        currentFolder = obj.has(Field.PARENT_ID.getName())
            ? obj.getString(Field.PARENT_ID.getName())
            : Field.MINUS_1.getName();
        if (currentFolder.equals(Field.MINUS_1.getName())) { // should not happen
          sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
          return;
        }
      }
      String finalCurrentFolder = currentFolder;
      boolean finalIsProject = isProject;

      // RG: Refactored to not use global collections
      List<String> pathList = path.getList();
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            try {
              if (pathStr == null) {
                return null;
              }
              String[] array = pathStr.split("/");
              if (array.length == 0) {
                return new JsonObject()
                    .put(Field.PATH.getName(), pathStr)
                    .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
              }
              Entity subSegment =
                  XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
              String filename = array[array.length - 1];
              // current folder
              if (array.length == 1) {
                // check that current user is an owner or an editor
                HttpResponse<String> response = AWSXRayUnirest.get(
                        apiUrl[0]
                            + (finalIsProject ? "/projects/" : "/folders/")
                            + finalCurrentFolder,
                        "TrimbleConnect.getObjectInfo")
                    .header("Authorization", "Bearer " + accessToken)
                    .asString();
                if (response.getStatus() != HttpStatus.OK) {
                  XRayManager.endSegment(subSegment);
                  return new JsonObject()
                      .put(Field.PATH.getName(), pathStr)
                      .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                }
                JsonNode body = new JsonNode(response.getBody());
                JSONObject obj = body.getObject();
                boolean available = obj.has(Field.PERMISSION.getName())
                        && obj.getString(Field.PERMISSION.getName()).equals(FULL_ACCESS)
                    || obj.has(Field.ACCESS.getName())
                        && obj.getString(Field.ACCESS.getName()).equals(FULL_ACCESS);
                // check for existing file
                if (available) {
                  try {
                    response = AWSXRayUnirest.get(
                            apiUrl[0] + "/folders/" + finalCurrentFolder + "/items",
                            "TrimbleConnect.getFolderContent")
                        .header("Authorization", "Bearer " + accessToken)
                        .asString();
                    if (response.getStatus() != HttpStatus.OK
                        && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    }
                    body = new JsonNode(response.getBody());
                    for (Object item : body.getArray()) {
                      obj = (JSONObject) item;
                      if (obj.getString(Field.TYPE.getName()).equals(FILE)
                          && obj.getString(Field.NAME.getName()).equals(filename)) {
                        available = false;
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
              Map<String, JSONObject> foldersCache = new HashMap<>();
              Set<String> possibleFolders = new HashSet<>();
              possibleFolders.add(finalCurrentFolder);
              int lastStep = -1;
              for (int i = 0; i < array.length - 1; i++) {
                if (array[i].isEmpty()) {
                  continue;
                }
                Iterator<String> it = possibleFolders.iterator();
                Set<String> adds = new HashSet<>(), dels = new HashSet<>();
                while (it.hasNext()) {
                  adds.clear();
                  dels.clear();
                  String possibleFolderId = it.next();
                  if ("..".equals(array[i])) {
                    if (Field.MINUS_1.getName().equals(possibleFolderId)) {
                      return new JsonObject()
                          .put(Field.PATH.getName(), pathStr)
                          .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
                    }
                    try {
                      HttpResponse<String> response = AWSXRayUnirest.get(
                              apiUrl[0] + "/folders/" + possibleFolderId,
                              "TrimbleConnect.getObjectInfo")
                          .header("Authorization", "Bearer " + accessToken)
                          .asString();
                      if (response.getStatus() == HttpStatus.NOT_FOUND) {
                        response = AWSXRayUnirest.get(
                                apiUrl[0] + "/projects/" + possibleFolderId,
                                "TrimbleConnect.getObjectInfo")
                            .header("Authorization", "Bearer " + accessToken)
                            .asString();
                      }
                      if (response.getStatus() == HttpStatus.OK) {
                        JsonNode body = new JsonNode(response.getBody());
                        JSONObject obj = body.getObject();
                        if (obj.has(Field.PARENT_ID.getName())) {
                          adds.add(obj.getString(Field.PARENT_ID.getName()));
                        }
                        dels.add(possibleFolderId);
                      }
                    } catch (Exception exception) {
                      log.error(exception);
                    }
                  } else {
                    try {
                      HttpResponse<String> response = AWSXRayUnirest.get(
                              apiUrl[0] + "/folders/" + possibleFolderId + "/items",
                              "TrimbleConnect.getFolderContent")
                          .header("Authorization", "Bearer " + accessToken)
                          .asString();
                      if (response.getStatus() == HttpStatus.OK
                          || response.getStatus() == HttpStatus.PARTIAL_CONTENT) {
                        JsonNode body = new JsonNode(response.getBody());
                        for (Object item : body.getArray()) {
                          JSONObject obj = (JSONObject) item;
                          if (obj.getString(Field.TYPE.getName()).equals(FOLDER)
                              && obj.getString(Field.NAME.getName()).equals(array[i])) {
                            foldersCache.put(obj.getString(Field.ID.getName()), obj);
                            adds.add(obj.getString(Field.ID.getName()));
                          }
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
              // check if it's possible to either upload a file (if lastStep == -1) or create a path
              // starting from array[i] in possible folders
              JsonArray folders = new JsonArray();
              boolean possible = lastStep != -1;
              for (String possibleFolderId : possibleFolders) {
                // check that current user is an owner or an editor
                try {
                  JSONObject possibleFolder = foldersCache.get(possibleFolderId);
                  boolean available = possibleFolder.has(Field.PERMISSION.getName())
                          && possibleFolder
                              .getString(Field.PERMISSION.getName())
                              .equals(FULL_ACCESS)
                      || possibleFolder.has(Field.ACCESS.getName())
                          && possibleFolder.getString(Field.ACCESS.getName()).equals(FULL_ACCESS);
                  if (available) {
                    if (lastStep == -1) {
                      boolean add = true;
                      HttpResponse<String> response = AWSXRayUnirest.get(
                              apiUrl[0] + "/folders/" + possibleFolderId + "/items",
                              "TrimbleConnect.getFolderContent")
                          .header("Authorization", "Bearer " + accessToken)
                          .asString();
                      if (response.getStatus() == HttpStatus.OK
                          || response.getStatus() == HttpStatus.PARTIAL_CONTENT) {
                        JsonNode body = new JsonNode(response.getBody());
                        for (Object item : body.getArray()) {
                          JSONObject obj = (JSONObject) item;
                          if (obj.getString(Field.TYPE.getName()).equals(FILE)
                              && obj.getString(Field.NAME.getName()).equals(filename)) {
                            add = false;
                            break;
                          }
                        }
                      } else {
                        add = false;
                      }
                      if (add) {
                        folders.add(new JsonObject()
                            .put(Field.ID.getName(), possibleFolderId)
                            .put(
                                Field.OWNER.getName(),
                                possibleFolder.has(Field.CREATED_BY.getName())
                                    ? possibleFolder
                                            .getJSONObject(Field.CREATED_BY.getName())
                                            .getString(Field.FIRST_NAME.getName())
                                        + " "
                                        + possibleFolder
                                            .getJSONObject(Field.CREATED_BY.getName())
                                            .getString(Field.LAST_NAME.getName())
                                    : emptyString)
                            .put(
                                Field.IS_OWNER.getName(),
                                possibleFolder.has(Field.CREATED_BY.getName())
                                    && possibleFolder
                                        .getJSONObject(Field.CREATED_BY.getName())
                                        .getString(Field.ID.getName())
                                        .equals(finalExternalId))
                            .put(Field.UPDATE_DATE.getName(), possibleFolder)
                            .put(Field.CHANGER.getName(), parseObjForModifierName(possibleFolder))
                            .put(Field.CHANGER_ID.getName(), parseObjForModifierId(possibleFolder))
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
                              possibleFolder.has(Field.CREATED_BY.getName())
                                  ? possibleFolder
                                          .getJSONObject(Field.CREATED_BY.getName())
                                          .getString(Field.FIRST_NAME.getName())
                                      + " "
                                      + possibleFolder
                                          .getJSONObject(Field.CREATED_BY.getName())
                                          .getString(Field.LAST_NAME.getName())
                                  : emptyString)
                          .put(
                              Field.IS_OWNER.getName(),
                              possibleFolder.has(Field.CREATED_BY.getName())
                                  && possibleFolder
                                      .getJSONObject(Field.CREATED_BY.getName())
                                      .getString(Field.ID.getName())
                                      .equals(finalExternalId))
                          .put(Field.UPDATE_DATE.getName(), possibleFolder)
                          .put(Field.CHANGER.getName(), parseObjForModifierName(possibleFolder))
                          .put(Field.CHANGER_ID.getName(), parseObjForModifierId(possibleFolder))
                          .put(Field.NAME.getName(), possibleFolder.getString(Field.NAME.getName()))
                          .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                    }
                  }
                } catch (Exception exception) {
                  log.error(exception);
                }
              }
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
            } catch (UnirestException e) {
              log.error(e);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    //        String externalId = getRequiredString(segment, Field.EXTERNAL_ID.getName(), message,
    // jsonObject);
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    //        String accessToken = connect(segment, message);
    //        if (accessToken == null)
    //            return;
    //        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    try {
      if (collaborators.isEmpty()) {
        String externalId = findExternalId(segment, message, jsonObject, storageType);
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          collaborators.add(externalId);
        }
      }
      for (Object externalId : collaborators) {
        Item trimbleUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
        if (trimbleUser != null) {
          String apiUrl = trimbleUser.hasAttribute(Field.SERVER.getName())
              ? "https:"
                  + new JSONObject(trimbleUser.getString(Field.SERVER.getName()))
                      .getString(Field.ORIGIN.getName())
                  + "/tc/api/2.0"
              : this.apiUrl;
          String accessToken = trimbleUser.getString(Field.ACCESS_TOKEN.getName());
          externalId = trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName());
          //                    List<String> regions = user.getList(Field.REGIONS.getName());
          try {
            accessToken =
                EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
          } catch (Exception e) {
            log.error(e);
            break;
          }
          if (requestFolderId != null
              && requestFolderId.equals(Field.MINUS_1.getName())) { // should not happen
            sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
            return;
          }
          if (requestFolderId != null && requestFolderId.contains(":")) {
            requestFolderId = requestFolderId.split(":")[1];
          }
          String currentFolder = requestFolderId;
          HttpResponse<String> response;
          JSONObject obj;
          JsonNode body;
          if (currentFolder == null) {
            response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getFileInfo")
                .header("Authorization", "Bearer " + accessToken)
                .asString();
            if (response.getStatus() != HttpStatus.OK) {
              sendError(segment, message, response);
              return;
            }
            body = new JsonNode(response.getBody());
            obj = (JSONObject) body.getArray().get(0);
            currentFolder = obj.has(Field.PARENT_ID.getName())
                ? obj.getString(Field.PARENT_ID.getName())
                : Field.MINUS_1.getName();
          }
          JsonArray results = new JsonArray();
          path = new JsonArray(new ArrayList<>(new HashSet<String>(path.getList())));
          for (Object pathStr : path) {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            if (!(pathStr instanceof String)) {
              XRayManager.endSegment(subSegment);
              continue;
            }
            String originalPathStr = (String) pathStr;
            JsonArray pathFiles = new JsonArray();
            String[] array = Utils.parseRelativePath((String) pathStr);
            String filename = array[array.length - 1];
            Set<String> possibleFolders = new HashSet<>();
            possibleFolders.add(currentFolder);
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
                    goUp(apiUrl, accessToken, folderId, adds);
                  } else {
                    goDown(apiUrl, accessToken, folderId, array[i], adds);
                  }
                }
                possibleFolders.removeAll(dels);
                possibleFolders.addAll(adds);
              }
            }
            // check in all possible folders
            for (String folderId : possibleFolders) {
              findFileInFolder(
                  apiUrl, accessToken, folderId, filename, pathFiles, (String) externalId);
            }
            // check in the root folder if not found
            if (pathFiles.isEmpty() && !possibleFolders.contains(currentFolder)) {
              findFileInFolder(
                  apiUrl, accessToken, currentFolder, filename, pathFiles, (String) externalId);
            }
            results.add(new JsonObject()
                .put(Field.PATH.getName(), originalPathStr)
                .put(Field.FILES.getName(), pathFiles));
            XRayManager.endSegment(subSegment);
          }
          sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
          return;
        }
      }
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void goDown(
      String apiUrl, String accessToken, String folderId, String name, Set<String> adds) {
    try {
      // if folderId is -1 - get all projects
      boolean root = Field.MINUS_1.getName().equals(folderId);
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + (root ? "/projects" : "/folders/" + folderId + "/items"),
              root ? "TrimbleConnect.getProjects" : "TrimbleConnect.getFolderContent")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK
          && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
        log.error("Error on going down in TrimbleConnect", response.getBody());
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      for (Object item : body.getArray()) {
        JSONObject obj = (JSONObject) item;
        if (obj.getString(Field.NAME.getName()).equals(name)
            && (root || obj.getString(Field.TYPE.getName()).equals(FOLDER))) {
          adds.add(root ? obj.getString("rootId") : obj.getString(Field.ID.getName()));
        }
      }
    } catch (Exception e) {
      log.error("Error on going down in TrimbleConnect", e);
    }
  }

  private void goUp(String apiUrl, String accessToken, String folderId, Set<String> adds) {
    if (Field.MINUS_1.getName().equals(folderId)) {
      return;
    }
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/folders/" + folderId, "TrimbleConnect.getFolderInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        log.error("Error on going up in TrimbleConnect", response.getBody());
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj = body.getObject();
      adds.add(
          obj.has(Field.PARENT_ID.getName())
              ? obj.getString(Field.PARENT_ID.getName())
              : Field.MINUS_1.getName());
    } catch (Exception e) {
      log.error("Error on going up in TrimbleConnect", e);
    }
  }

  private void findFileInFolder(
      String apiUrl,
      String accessToken,
      String folderId,
      String filename,
      JsonArray pathFiles,
      String externalId) {
    try {
      if (folderId != null && !Field.MINUS_1.getName().equals(folderId)) {
        HttpResponse<String> response = AWSXRayUnirest.get(
                apiUrl + "/folders/" + folderId + "/items", "TrimbleConnect.getFolderContent")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          log.info(
              "Error on searching for xref in possible folder in TrimbleConnect",
              response.getBody());
          return;
        }
        JsonNode body = new JsonNode(response.getBody());
        for (Object item : body.getArray()) {
          JSONObject obj = (JSONObject) item;
          long updateDate = 0;
          if (obj.getString(Field.TYPE.getName()).equals(FILE)
              && obj.getString(Field.NAME.getName()).equals(filename)) {
            try {
              updateDate = sdf.parse(obj.getString("modifiedOn")
                      .substring(0, obj.getString("modifiedOn").lastIndexOf("+")))
                  .getTime();
            } catch (Exception ignore) {
            }
            pathFiles.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType, externalId, obj.getString(Field.ID.getName())))
                .put(
                    Field.OWNER.getName(),
                    obj.has(Field.CREATED_BY.getName())
                        ? obj.getJSONObject(Field.CREATED_BY.getName())
                                .getString(Field.FIRST_NAME.getName())
                            + " "
                            + obj.getJSONObject(Field.CREATED_BY.getName())
                                .getString(Field.LAST_NAME.getName())
                        : emptyString)
                .put(
                    Field.IS_OWNER.getName(),
                    obj.has(Field.CREATED_BY.getName())
                        && obj.getJSONObject(Field.CREATED_BY.getName())
                            .getString(Field.ID.getName())
                            .equals(externalId))
                .put(Field.UPDATE_DATE.getName(), updateDate)
                .put(Field.CHANGER.getName(), parseObjForModifierName(obj))
                .put(Field.CHANGER_ID.getName(), parseObjForModifierId(obj))
                .put(
                    Field.SIZE.getName(),
                    Utils.humanReadableByteCount(obj.getInt(Field.SIZE.getName())))
                .put(Field.SIZE_VALUE.getName(), obj.getInt(Field.SIZE.getName()))
                .put(Field.NAME.getName(), filename)
                .put(Field.STORAGE_TYPE.getName(), storageType.name()));
          }
        }
      }
    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in TrimbleConnect", e);
    }
  }

  public void doMoveFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doMove(message, id, false);
  }

  public void doMoveFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doMove(message, id, true);
  }

  private void doMove(Message<JsonObject> message, String id, boolean isFile) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (id == null || parentId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidAndParentidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (id.contains(":")) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ProjectCannotBeMoved"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      if (parentId.contains(":")) {
        parentId = parentId.split(":")[1];
      }
      HttpResponse<String> response = AWSXRayUnirest.patch(
              apiUrl + (isFile ? "/files/" : "/folders/") + id, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-type", "application/json")
          .body(new JSONObject().put(Field.PARENT_ID.getName(), parentId))
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName());
    if (!isFolder) {
      sendNotImplementedError(message);
      return;
    }
    if (id == null || name == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      boolean isProject = false;
      if (id.contains(":")) {
        isProject = true;
        id = id.split(":")[0];
      }
      HttpResponse<String> response;
      if (isProject) {
        response = AWSXRayUnirest.delete(
                apiUrl + "/projects/" + id + "/users/" + name, "TrimbleConnect.deleteCollaborator")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.NO_CONTENT) {
          sendError(segment, message, response);
        } else {
          sendOK(segment, message, mills);
        }
      } else {
        response = AWSXRayUnirest.get(
                apiUrl + "/folders/" + id + "/permissions", "TrimbleConnect.getCollaborators")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        JsonNode body = new JsonNode(response.getBody());
        JSONObject json = body.getObject();
        JSONArray permissions = new JSONArray();
        if (json.has(Field.PERMISSIONS.getName())) {
          permissions = json.getJSONArray(Field.PERMISSIONS.getName());
        }
        Iterator<Object> it = permissions.iterator();
        while (it.hasNext()) {
          if (((JSONObject) it.next()).getString(Field.ID.getName()).equals(name)) {
            it.remove();
            break;
          }
        }
        json.put(Field.PERMISSIONS.getName(), permissions);
        response = AWSXRayUnirest.post(
                apiUrl + "/folders/" + id + "/permissions", "TrimbleConnect.deleteCollaborator")
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-type", "application/json")
            .body(json)
            .asString();
        if (response.getStatus() != HttpStatus.CREATED) {
          sendError(segment, message, response);
          return;
        }
        sendOK(segment, message, mills);
        //                response = AWSXRayUnirest.delete(apiUrl + "/folders/" + id +
        //                "/permissions")
        //                        .header("Authorization", "Bearer " + accessToken)
        //                        .header("Content-Type", "application/json")
        //                        .body(new JSONObject().put(Field.PERMISSIONS.getName(),
        //                                new JSONArray().put(new
        // JSONObject().put(Field.ID.getName(), name)
        //                                .put(Field.TYPE.getName(), "USER"))))
        //                        .asJson();
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String email = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    String role = getRequiredString(segment, Field.ROLE.getName(), message, message.body());
    boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName());
    if (!isFolder) {
      sendNotImplementedError(message);
      return;
    }
    if (id == null || email == null || role == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      boolean isProject = false;
      if (id.contains(":")) {
        isProject = true;
        id = id.split(":")[0];
      }
      HttpResponse<String> response;
      if (isProject) {
        response = AWSXRayUnirest.post(
                apiUrl + "/projects/" + id + "/users", "TrimbleConnect.addCollaborator")
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-Type", "application/json")
            .body(
                new JSONObject().put(Field.EMAIL.getName(), email).put(Field.ROLE.getName(), USER))
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        sendOK(segment, message, mills);
      } else {
        response = AWSXRayUnirest.get(apiUrl + "/folders/" + id, "TrimbleConnect.getObjectInfo")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        JsonNode body = new JsonNode(response.getBody());
        JSONObject obj = body.getObject();
        String projectId = obj.getString(Field.PROJECT_ID.getName());
        response = AWSXRayUnirest.get(
                apiUrl + "/projects/" + projectId + "/users", "TrimbleConnect.getCollaborators")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        body = new JsonNode(response.getBody());
        String userId = null;
        for (Object o : body.getArray()) {
          if (email.equals(((JSONObject) o).getString(Field.EMAIL.getName()))) {
            userId = ((JSONObject) o).getString(Field.ID.getName());
            break;
          }
        }
        if (userId == null) {
          sendError(
              segment, message, Utils.getLocalizedString(message, "FolderCannotBeShared"), 404);
          return;
        }
        response = AWSXRayUnirest.get(
                apiUrl + "/folders/" + id + "/permissions", "TrimbleConnect.getCollaborators")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        body = new JsonNode(response.getBody());
        JSONObject json = body.getObject();
        JSONArray permissions = new JSONArray();
        if (json.has(Field.PERMISSIONS.getName())) {
          permissions = json.getJSONArray(Field.PERMISSIONS.getName());
        }
        permissions.put(new JSONObject()
            .put(Field.ID.getName(), userId)
            .put(Field.TYPE.getName(), USER)
            .put(Field.PERMISSION.getName(), role));
        json.put(Field.PERMISSIONS.getName(), permissions);
        response = AWSXRayUnirest.post(
                apiUrl + "/folders/" + id + "/permissions", "TrimbleConnect.addCollaborator")
            .header("Authorization", "Bearer " + accessToken)
            .header("Content-type", "application/json")
            .body(json)
            .asString();
        if (response.getStatus() != HttpStatus.CREATED) {
          sendError(segment, message, response);
          return;
        }
        sendOK(segment, message, mills);
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.ID.getName());
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
      XRayManager.endSegment(segment);
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    String location = message.body().getString(Field.LOCATION.getName());
    try {
      JsonArray result = new JsonArray();
      if (!id.equals(Field.MINUS_1.getName())) {
        boolean isProject = false;
        if (id.contains(":")) {
          isProject = true;
          id = id.split(":")[0];
        }
        HttpResponse<String> response;
        if (isProject) {
          response = AWSXRayUnirest.get(apiUrl + "/projects/" + id, "TrimbleConnect.getProjects")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() == HttpStatus.NOT_FOUND) {
            for (Object o : message.body().getJsonArray(Field.REGIONS.getName())) {
              JsonObject region = new JsonObject((String) o);
              @NonNls
              String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
              if (apiUrl.equals(regUrl)) {
                continue;
              }
              apiUrl = regUrl;
              location = region.getString(Field.LOCATION.getName());
              response = AWSXRayUnirest.get(
                      apiUrl + "/projects/" + id, "TrimbleConnect.getProjectContent")
                  .header("Authorization", "Bearer " + accessToken)
                  .asString();
              if (response.getStatus() != HttpStatus.NOT_FOUND) {
                break;
              }
            }
          }
          if (response.getStatus() != HttpStatus.OK) {
            sendError(segment, message, response);
            return;
          }
          JsonNode body = new JsonNode(response.getBody());
          JSONObject obj = body.getObject();
          result.add(new JsonObject()
              .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  obj.getString(Field.ID.getName()) + ":" + obj.getString("rootId"))
              .put(
                  Field.VIEW_ONLY.getName(),
                  !obj.getString(Field.ACCESS.getName()).equals(FULL_ACCESS)));
          result.add(new JsonObject()
              .put(Field.NAME.getName(), location)
              .put(Field.ENCAPSULATED_ID.getName(), location)
              .put(Field.VIEW_ONLY.getName(), false)
              .put(Field.SERVER.getName(), location));
        } else {
          // there is really no need to do this recursively, as we have the full path in the
          // response. TBD
          getPath(segment, accessToken, id, result, message, apiUrl, location);
        }
      }
      result.add(new JsonObject()
          .put(Field.NAME.getName(), "~")
          .put(Field.ENCAPSULATED_ID.getName(), Field.MINUS_1.getName())
          .put(Field.VIEW_ONLY.getName(), false));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void getPath(
      Entity segment,
      String accessToken,
      String id,
      JsonArray result,
      Message<JsonObject> message,
      String apiUrl,
      String location)
      throws UnirestException {
    HttpResponse<String> response = AWSXRayUnirest.get(
            apiUrl + "/folders/" + id, "TrimbleConnect.getObjectInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (response.getStatus() == HttpStatus.NOT_FOUND) {
      // try to find it in another region
      for (Object o : message.body().getJsonArray(Field.REGIONS.getName())) {
        JsonObject region = new JsonObject((String) o);
        @NonNls String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
        if (apiUrl.equals(regUrl)) {
          continue;
        }
        apiUrl = regUrl;
        location = region.getString(Field.LOCATION.getName());
        response = AWSXRayUnirest.get(apiUrl + "/folders/" + id, "TrimbleConnect.getObjectInfo")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.NOT_FOUND) {
          break;
        }
      }
    }
    if (response.getStatus() != HttpStatus.OK) {
      sendError(segment, message, response);
      return;
    }
    JsonNode body = new JsonNode(response.getBody());
    JSONObject obj = body.getObject();
    String objId = obj.getString(Field.ID.getName());
    if (!obj.has(Field.PARENT_ID.getName())) {
      objId = obj.getString(Field.PROJECT_ID.getName()) + ":" + objId;
    }
    result.add(new JsonObject()
        .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
        .put(Field.ENCAPSULATED_ID.getName(), objId)
        .put(
            Field.VIEW_ONLY.getName(),
            !obj.getString(Field.PERMISSION.getName()).equals(FULL_ACCESS)));
    if (obj.has(Field.PARENT_ID.getName())) {
      getPath(
          segment,
          accessToken,
          obj.getString(Field.PARENT_ID.getName()),
          result,
          message,
          apiUrl,
          location);
    } else {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), location)
          .put(Field.ENCAPSULATED_ID.getName(), location)
          .put(Field.VIEW_ONLY.getName(), false)
          .put(Field.SERVER.getName(), location));
    }
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj = (JSONObject) body.getArray().get(0);
      String verId =
          obj.has(Field.VERSION_ID.getName()) ? obj.getString(Field.VERSION_ID.getName()) : null;
      sendOK(segment, message, new JsonObject().put(Field.VERSION_ID.getName(), verId), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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
    String location = jsonObject.getString(Field.LOCATION.getName());
    if (fileId == null) {
      return;
    }
    Item user = ExternalAccounts.getExternalAccount(userId, externalId);
    String accessToken = connect(segment, message, user);
    if (accessToken == null) {
      return;
    }

    List<String> regions = user.getList(Field.REGIONS.getName());
    try {
      String url = null;
      for (String s : regions) {
        JsonObject region = new JsonObject(s);
        if (region.getString(Field.LOCATION.getName()).equals(location)) {
          url = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          break;
        }
      }
      if (url == null) {
        url = apiUrl;
      }
      HttpResponse<String> response = AWSXRayUnirest.get(
              url + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        for (String o : regions) {
          JsonObject region = new JsonObject(o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (url.equals(regUrl)) {
            continue;
          }
          url = regUrl;
          response = AWSXRayUnirest.get(url + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }
      if (response.getStatus() == HttpStatus.OK) {
        JsonNode body = new JsonNode(response.getBody());
        JSONObject obj = (JSONObject) body.getArray().get(0);
        String verId =
            obj.has(Field.VERSION_ID.getName()) ? obj.getString(Field.VERSION_ID.getName()) : null;
        String thumbnailName =
            ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), fileId, verId);

        long updateDate = 0;
        try {
          updateDate = sdf.parse(obj.getString("modifiedOn")
                  .substring(0, obj.getString("modifiedOn").lastIndexOf("+")))
              .getTime();
        } catch (Exception ignore) {
        }

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
            .put(Field.DELETED.getName(), false) // todo
            .put(
                Field.LINK.getName(),
                config.getProperties().getUrl() + "file/" + fileId + "?token=" + token) // NON-NLS
            .put(Field.FILE_NAME.getName(), obj.getString(Field.NAME.getName()))
            .put(Field.VERSION_ID.getName(), verId)
            .put(Field.CREATOR_ID.getName(), creatorId)
            .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
            .put(
                Field.THUMBNAIL.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .put(Field.EXPORT.getName(), export)
            .put(Field.UPDATE_DATE.getName(), updateDate)
            .put(Field.CHANGER.getName(), parseObjForModifierName(obj))
            .put(Field.CHANGER_ID.getName(), parseObjForModifierId(obj));
        sendOK(segment, message, json, mills);
        return;
      }
    } catch (Exception exception) {
      log.error(exception);
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        for (Object o : message.body().getJsonArray(Field.REGIONS.getName())) {
          JsonObject region = new JsonObject((String) o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (apiUrl.equals(regUrl)) {
            continue;
          }
          apiUrl = regUrl;
          response = AWSXRayUnirest.get(apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj = (JSONObject) body.getArray().get(0);
      String fileId = obj.getString(Field.ID.getName())
          + (obj.has("rootId") ? (":" + obj.getString("rootId")) : emptyString);
      String verId =
          obj.has(Field.VERSION_ID.getName()) ? obj.getString(Field.VERSION_ID.getName()) : null;
      String filename = obj.getString(Field.NAME.getName());
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
                          .put(Field.VERSION_ID.getName(), verId)
                          .put(Field.EXT.getName(), Extensions.getExtension(filename))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), fileId, verId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      fileId, storageType.name(), verId, true, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private void doCheckin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());

    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String requestToken = message.body().getString("requestToken");
    // only for the ttl cleanup xsession case
    if (Utils.isStringNotNullOrEmpty(requestToken)) {
      Item item = TrimbleCheckIn.getTrimbleRequestToken(requestToken);
      if (item != null) {
        userId = item.getString(Field.USER_ID.getName());
        externalId = item.getString(Field.EXTERNAL_ID.getName());
      } else {
        sendError(
            segment, message, Utils.getLocalizedString(message, "CheckInRequestNotFound"), 400);
        return;
      }
    }

    String accessToken = connect(segment, message, userId, externalId);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.post(
              apiUrl + "/files/" + id + "/checkin", "TrimbleConnect.checkin")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        JsonObject errorBody = new JsonObject(response.getBody());
        if (errorBody.getString("errorcode").equals("FILE_NOT_CHECKED_OUT")) {
          sendOK(segment, message, mills);
          return;
        }
        sendError(
            segment, message, errorBody.getString(Field.MESSAGE.getName()), response.getStatus());
      } else {
        if (Utils.isStringNotNullOrEmpty(requestToken)) {
          TrimbleCheckIn.deleteTrimbleRequestToken(requestToken);
        }
        sendOK(segment, message, mills);
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void doCheckout(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.post(
              apiUrl + "/files/" + id + "/checkout", "TrimbleConnect.checkout")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response.getBody(), response.getStatus());
      } else {
        sendOK(segment, message, mills);
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      String accessToken = connect(segment, message, json, false);
      if (accessToken != null) {
        String apiUrl = json.getString("apiUrl");
        HttpResponse<String> response = AWSXRayUnirest.get(
                apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() / 100 != 2) {
          for (Object o : json.getJsonArray(Field.REGIONS.getName())) {
            JsonObject region = new JsonObject((String) o);
            @NonNls
            String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
            if (!apiUrl.equals(regUrl)) {
              apiUrl = regUrl;
              response = AWSXRayUnirest.get(
                      apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
                  .header("Authorization", "Bearer " + accessToken)
                  .asString();
              // if 2xx response - file is found
              if (response.getStatus() / 100 == 2) {
                return true;
              }
            }
          }
        }
        return response.getStatus() / 100 == 2;
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
    String userId = jsonObject.getString(Field.USER_ID.getName());
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName(), false);
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
    boolean isProject = false;
    if (!isFile && id.contains(":")) {
      isProject = true;
      id = id.split(":")[0];
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
    String accessToken = connect(segment, message, jsonObject, false);
    if (accessToken == null) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      return;
    }
    if (!isFile && id.equals(ROOT_FOLDER_ID)) {
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
    String apiUrl = jsonObject.getString("apiUrl");
    String location = jsonObject.getString(Field.LOCATION.getName());
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + (isFile ? "/files/" : isProject ? "/projects/" : "/folders/") + id,
              "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        for (Object o : jsonObject.getJsonArray(Field.REGIONS.getName())) {
          JsonObject region = new JsonObject((String) o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (apiUrl.equals(regUrl)) {
            continue;
          }
          apiUrl = regUrl;
          location = region.getString(Field.LOCATION.getName());
          response = AWSXRayUnirest.get(
                  apiUrl + (isFile ? "/files/" : isProject ? "/projects/" : "/folders/") + id,
                  "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }
      if (response.getStatus() != HttpStatus.OK) {
        super.deleteSubscriptionForUnavailableFile(
            id, jsonObject.getString(Field.USER_ID.getName()));
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj;
      if (isFile) {
        obj = (JSONObject) body.getArray().get(0);
      } else {
        obj = body.getObject();
      }
      JsonObject json = getFileJson(
          obj,
          !isFile,
          full,
          jsonObject.getString(Field.EXTERNAL_ID.getName()),
          accessToken,
          apiUrl,
          location,
          full,
          isAdmin,
          userId,
          false,
          false);
      if (isProject) {
        json.put("icon", "https://icons.kudo.graebert.com/trimble-project.svg");
        json.put("icon_black", "https://icons.kudo.graebert.com/trimble-project.svg");
      }
      sendOK(segment, message, json, mills);
    } catch (Exception ex) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doDeleteFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.delete(
              apiUrl + "/files/" + id, "TrimbleConnect.deleteFile")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.NO_CONTENT) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.patch(
              apiUrl + "/files/" + fileId, "TrimbleConnect.renameFile")
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-Type", "application/json")
          .body(new JSONObject().put(Field.NAME.getName(), name))
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject requestBody = parsedMessage.getJsonObject();

    String name = requestBody.getString(Field.NAME.getName());
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
    Boolean isAdmin = requestBody.getBoolean(Field.IS_ADMIN.getName());
    String fileId = requestBody.getString(Field.FILE_ID.getName());
    String externalId = requestBody.getString(Field.EXTERNAL_ID.getName());
    String userEmail = requestBody.getString(Field.EMAIL.getName());
    String userName = requestBody.getString(Field.F_NAME.getName());
    String userSurname = requestBody.getString(Field.SURNAME.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message, requestBody, false);
    if (accessToken == null) {
      return;
    }
    String apiUrl = requestBody.getString("apiUrl");
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      HttpResponse<String> response;
      JsonNode body;
      String versionId;
      response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      body = new JsonNode(response.getBody());
      JSONObject obj = (JSONObject) body.getArray().get(0);
      String projectId = obj != null ? obj.getString(Field.PARENT_ID.getName()) : null;
      name = obj != null ? obj.getString(Field.NAME.getName()) : null;

      // if latest version in ex storage is unknown,
      // then save a new version as a file with a prefix beside the original file
      response = AWSXRayUnirest.post(
              apiUrl + "/files?fileId=" + fileId + "&parentId=" + projectId,
              "TrimbleConnect.uploadData")
          .header("Authorization", "Bearer " + accessToken)
          .field(Field.FILE.getName(), stream, name != null ? name : "version.dwg") // NON-NLS
          .field("Content-Type", "multipart/form-data") // NON-NLS
          .asString();
      if (response.getStatus() != HttpStatus.CREATED && response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }

      body = new JsonNode(response.getBody());

      int attempts = 5;
      if (!body.getArray().isEmpty()) {
        obj = (JSONObject) body.getArray().get(0);
      } else {
        while ((body.getArray().isEmpty()) && attempts > 0) {
          // for some reason we are not getting the newly created file. We can also not request
          // this too quickly
          Thread.sleep(1000);
          response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.OK
              && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
            sendError(segment, message, response);
            return;
          }
          body = new JsonNode(response.getBody());
          if (!body.getArray().isEmpty()) {
            break;
          }
          attempts--;
        }
      }
      versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;
      fileId = obj != null ? obj.getString(Field.ID.getName()) : null;
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            parsedMessage
                .getJsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      }
      try {
        HttpResponse<String> versionResponse = AWSXRayUnirest.get(
                apiUrl + "/files/" + fileId + "/versions", "TrimbleConnect.getVersions")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (versionResponse.getStatus() != HttpStatus.OK) {
          sendError(segment, message, versionResponse);
          return;
        }
        JsonNode versionBody = new JsonNode(versionResponse.getBody());
        JSONArray arr = versionBody.getArray();
        sendOK(
            segment,
            message,
            getVersionInfo(fileId, new JsonObject(arr.get(0).toString()), versionId, externalId)
                .toJson(),
            mills);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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

  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    JsonObject requestBody = parsedMessage.getJsonObject();

    String name = requestBody.getString(Field.NAME.getName());
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
    Boolean isAdmin = requestBody.getBoolean(Field.IS_ADMIN.getName());
    String folderId = requestBody.getString(Field.FOLDER_ID.getName());
    String fileId = requestBody.getString(Field.FILE_ID.getName());
    String originalFileId = fileId;
    String externalId = requestBody.getString(Field.EXTERNAL_ID.getName());
    String userId = requestBody.getString(Field.USER_ID.getName());
    String xSessionId = requestBody.getString(Field.X_SESSION_ID.getName());
    String baseChangeId = requestBody.getString(Field.BASE_CHANGE_ID.getName());
    boolean doCopyComments = requestBody.getBoolean(Field.COPY_COMMENTS.getName(), false);
    String userEmail = requestBody.getString(Field.EMAIL.getName());
    String userName = requestBody.getString(Field.F_NAME.getName());
    String userSurname = requestBody.getString(Field.SURNAME.getName());
    String cloneFileId = requestBody.getString(Field.CLONE_FILE_ID.getName());
    boolean newProject = false;
    String projectId = folderId;
    boolean isFileUpdate = Utils.isStringNotNullOrEmpty(fileId);
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String accessToken = connect(segment, message, requestBody, false);
    if (accessToken == null) {
      return;
    }
    String apiUrl = requestBody.getString("apiUrl");
    String responseName = null, modifier = null;
    String conflictingFileReason = requestBody.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      HttpResponse<String> response;
      JsonNode body;
      String versionId;
      long updateDate = -1L;

      if (fileId == null || parsedMessage.hasAnyContent()) {
        if (fileId == null) {
          if (folderId.equals(
              Field.MINUS_1
                  .getName())) { // create a new project, then upload the file to it's root folder
            response = AWSXRayUnirest.post(apiUrl + "/projects", "TrimbleConnect.createFile")
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .body(new JSONObject().put(Field.NAME.getName(), name))
                .asString();
            if (response.getStatus() != HttpStatus.CREATED
                && response.getStatus() != HttpStatus.OK) {
              sendError(segment, message, response);
              return;
            }
            body = new JsonNode(response.getBody());
            folderId = body.getObject().getString("rootId");
            projectId = body.getObject().getString(Field.ID.getName());
            newProject = true;
          }
          if (folderId.contains(":")) {
            folderId = folderId.split(":")[1];
          }
          response = AWSXRayUnirest.post(
                  apiUrl + "/files?parentId=" + folderId, "TrimbleConnect.uploadData")
              .header("Authorization", "Bearer " + accessToken)
              .field(Field.FILE.getName(), stream, name != null ? name : "version.dwg") // NON-NLS
              .field("Content-Type", "multipart/form-data") // NON-NLS
              // DK: WB-1459 fix
              .connectTimeout((int) TimeUnit.SECONDS.toMillis(25))
              .asString();
          if (response.getStatus() != HttpStatus.CREATED && response.getStatus() != HttpStatus.OK) {
            sendError(segment, message, response);
            return;
          }
          body = new JsonNode(response.getBody());
          JSONObject obj = null;
          int attempts = 5;
          if (!body.getArray().isEmpty()) {
            obj = (JSONObject) body.getArray().get(0);
          } else {
            while (obj == null && attempts > 0) {
              // for some reason we are not getting the newly created file. We can also not
              // request this too quickly
              Thread.sleep(1000);
              response = AWSXRayUnirest.get(
                      apiUrl + "/folders/" + folderId + "/items", "TrimbleConnect.getFolderContent")
                  .header("Authorization", "Bearer " + accessToken)
                  .asString();
              if (response.getStatus() != HttpStatus.OK
                  && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
                sendError(segment, message, response);
                return;
              }
              body = new JsonNode(response.getBody());
              for (Object item : body.getArray()) {
                JSONObject objt = (JSONObject) item;
                if (objt.getString(Field.NAME.getName())
                    .equals(name != null ? name : "version.dwg")) {
                  obj = objt;
                  break;
                }
              }
              attempts--;
            }
          }
          versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;
          fileId = obj != null ? obj.getString(Field.ID.getName()) : null;
          SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
          updateDate = obj != null
              ? sdf.parse(obj.getString("modifiedOn")
                      .substring(0, obj.getString("modifiedOn").lastIndexOf("+")))
                  .getTime()
              : -1L;

        } else if (parsedMessage.hasByteArrayContent()) {
          response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.OK) {
            JsonObject errorObject = new JsonObject(response.getBody());
            if (errorObject.containsKey("errorcode")
                && (errorObject
                        .getString("errorcode")
                        .equalsIgnoreCase("INVALID_OPERATION_FILE_DELETED")
                    || errorObject.getString("errorcode").equalsIgnoreCase("PERMISSION_DENIED"))) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
              isConflictFile = true;
              fileNotFound = true;
            } else {
              sendError(segment, message, response);
              return;
            }
          }
          JSONObject obj = null;
          if (!fileNotFound) {
            body = new JsonNode(response.getBody());
            obj = (JSONObject) body.getArray().get(0);
            if (obj.has(Field.MODIFIED_BY.getName())) {
              JSONObject modifiedBy = obj.getJSONObject(Field.MODIFIED_BY.getName());
              if (modifiedBy.has(Field.FIRST_NAME.getName())) {
                modifier = modifiedBy.getString(Field.FIRST_NAME.getName())
                    + (modifiedBy.has(Field.LAST_NAME.getName())
                        ? " " + modifiedBy.getString(Field.LAST_NAME.getName())
                        : emptyString);
              }
            }
            projectId = obj.getString(Field.PROJECT_ID.getName());
            folderId = obj.getString(Field.PARENT_ID.getName());
            name = obj.getString(Field.NAME.getName());
            versionId = obj.getString(Field.VERSION_ID.getName());

            // check if user still has the access to edit this file
            boolean isOwner = obj.getJSONObject(Field.CREATED_BY.getName())
                .getString(Field.ID.getName())
                .equals(externalId);
            if (!isOwner && !obj.getString(Field.PERMISSION.getName()).equals("FULL_ACCESS")) {
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
            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
              isConflictFile =
                  isConflictingChange(userId, fileId, storageType, versionId, baseChangeId);
              if (isConflictFile) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
              }
            }
          }
          if (!Utils.isStringNotNullOrEmpty(name)) {
            Item metaData = FileMetaData.getMetaData(fileId, storageType.name());
            if (Objects.nonNull(metaData)) {
              name = metaData.getString(Field.FILE_NAME.getName());
            }
            if (name == null) {
              name = "version.dwg";
            }
          }
          final String oldName = name;
          boolean isSameFolder = true;
          if (!isConflictFile) {
            response = AWSXRayUnirest.post(
                    apiUrl + "/files?fileId=" + fileId + "&parentId=" + folderId,
                    "TrimbleConnect.uploadData")
                .header("Authorization", "Bearer " + accessToken)
                .field(Field.FILE.getName(), stream, name) // NON-NLS
                .field("Content-Type", "multipart/form-data") // NON-NLS
                .asString();
          } else {
            // create a new file and save it beside original one
            String newName = getConflictingFileName(oldName);
            responseName = newName;

            if (Utils.isStringNotNullOrEmpty(folderId)) {
              String[] ids = folderId.split(":");
              if (ids.length == 2) {
                projectId = ids[0];
                folderId = ids[1];
              }
            }

            JsonArray responseArray = null;
            if (conflictingFileReason.equalsIgnoreCase(
                    XSessionManager.ConflictingFileReason.NO_EDITING_RIGHTS.name())
                || fileNotFound) {
              GetRequest projectsResponse = AWSXRayUnirest.get(
                      apiUrl + "/projects", "TrimbleConnect.getProjects")
                  .header("Authorization", "Bearer " + accessToken);
              if (projectsResponse == null
                  || (projectsResponse.asString().getStatus() != HttpStatus.CREATED
                      && projectsResponse.asString().getStatus() != HttpStatus.OK)) {
                sendError(segment, message, response);
                return;
              }
              responseArray = new JsonArray(projectsResponse.asString().getBody());
              if (Utils.isStringNotNullOrEmpty(projectId)) {
                for (Object project : responseArray) {
                  if (((JsonObject) project).getString(Field.ID.getName()).equals(projectId)) {
                    folderId = ((JsonObject) project).getString("rootId");
                    isSameFolder = false;
                    break;
                  }
                }
              }
            }
            if (!Utils.isStringNotNullOrEmpty(folderId) && Objects.nonNull(responseArray)) {
              folderId = responseArray.getJsonObject(0).getString("rootId");
              isSameFolder = false;
            }
            response = AWSXRayUnirest.post(
                    apiUrl + "/files?parentId=" + folderId, "TrimbleConnect.uploadData")
                .header("Authorization", "Bearer " + accessToken)
                .field(Field.FILE.getName(), stream, newName) // NON-NLS
                .field("Content-Type", "multipart/form-data") // NON-NLS
                .asString();
          }
          if (response.getStatus() != HttpStatus.CREATED && response.getStatus() != HttpStatus.OK) {
            sendError(segment, message, response);
            return;
          }

          body = new JsonNode(response.getBody());

          int attempts = 5;
          if (!body.getArray().isEmpty()) {
            obj = (JSONObject) body.getArray().get(0);
            projectId = obj.getString(Field.PROJECT_ID.getName());
          } else {
            while ((body.getArray().isEmpty()) && attempts > 0) {
              // for some reason we are not getting the newly created file. We can also not
              // request this too quickly
              Thread.sleep(1000);
              response = AWSXRayUnirest.get(
                      apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
                  .header("Authorization", "Bearer " + accessToken)
                  .asString();
              if (response.getStatus() != HttpStatus.OK
                  && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
                sendError(segment, message, response);
                return;
              }
              body = new JsonNode(response.getBody());
              if (!body.getArray().isEmpty()) {
                break;
              }
              attempts--;
            }
          }
          versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;
          String newName = obj != null ? obj.getString(Field.NAME.getName()) : null;
          fileId = obj != null ? obj.getString(Field.ID.getName()) : null;
          if (isConflictFile) {
            handleConflictingFile(
                segment,
                message,
                requestBody,
                oldName,
                newName,
                Utils.getEncapsulatedId(storageType, externalId, originalFileId),
                Utils.getEncapsulatedId(storageType, externalId, fileId),
                xSessionId,
                userId,
                modifier,
                conflictingFileReason,
                fileSessionExpired,
                isSameFolder,
                AuthManager.ClientType.getClientType(
                    requestBody.getString(Field.DEVICE.getName())));
          }
        } else {
          response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.OK) {
            sendError(segment, message, response);
            return;
          }
          body = new JsonNode(response.getBody());
          JSONObject obj = (JSONObject) body.getArray().get(0);
          versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;
        }
      } else {
        response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() != HttpStatus.OK) {
          sendError(segment, message, response);
          return;
        }
        body = new JsonNode(response.getBody());
        JSONObject obj = (JSONObject) body.getArray().get(0);
        versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;
      }
      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            requestBody
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }

      JsonObject json = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, fileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              StorageType.getShort(storageType) + "_" + fileId + "_" + versionId + ".png");

      if (newProject) {
        json.put(
                Field.FOLDER_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, projectId + ":" + folderId))
            .put("createdFolder", true);
      }
      if (updateDate != -1L) {
        json.put(Field.UPDATE_DATE.getName(), updateDate);
      }

      if (isConflictFile) {
        if (Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
          json.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
        }
        if (Utils.isStringNotNullOrEmpty(responseName)) {
          json.put(Field.NAME.getName(), responseName);
        }
        if (Utils.isStringNotNullOrEmpty(folderId) && Utils.isStringNotNullOrEmpty(projectId)) {
          folderId = projectId + ":" + folderId;
          json.put(
              Field.FOLDER_ID.getName(),
              Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, folderId));
        }
      }

      // for file save-as case
      if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
        String finalCloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
            .getString(Field.FILE_ID.getName());
        String finalFileId = fileId;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_COMMENTS_ON_SAVE_AS.getName())
            .run((Segment blockingSegment) -> {
              boolean doIncludeResolvedComments =
                  requestBody.getBoolean(Field.INCLUDE_RESOLVED_COMMENTS.getName(), false);
              boolean doIncludeDeletedComments =
                  requestBody.getBoolean(Field.INCLUDE_DELETED_COMMENTS.getName(), false);
              copyFileComments(
                  blockingSegment,
                  finalCloneFileId,
                  storageType,
                  finalFileId,
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
                .put(Field.FILE_ID.getName(), fileId)
                .put(Field.VERSION_ID.getName(), versionId)
                .put(Field.X_SESSION_ID.getName(), xSessionId)
                .put(Field.EMAIL.getName(), userEmail)
                .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
      }
    } catch (SocketTimeoutException ex) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "TimeOutResponse"),
          HttpStatus.REQUEST_TIMEOUT,
          ex);
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception exception) {
      sendError(
          segment, message, exception.getLocalizedMessage(), HttpStatus.BAD_REQUEST, exception);
    }
  }

  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String location = message.body().getString(Field.LOCATION.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    boolean success = false;
    Item user = ExternalAccounts.getExternalAccount(userId, externalId);
    List<String> regions = user.getList(Field.REGIONS.getName());
    String accessToken = connect(segment, message, user);
    if (accessToken == null) {
      return;
    }
    String url = null;
    try {
      if (Utils.isStringNotNullOrEmpty(location)) {
        for (String s : regions) {
          JsonObject region = new JsonObject(s);
          if (region.getString(Field.LOCATION.getName()).equals(location)) {
            url = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
            break;
          }
        }
      }
      if (url == null && user.hasAttribute(Field.SERVER.getName())) {
        try {
          url = new JsonObject(user.getString(Field.SERVER.getName())).getString("tc-api");
        } catch (Exception ignore) {
        }
      }
      if (url == null) {
        url = apiUrl;
      }
      if (url.endsWith("/")) {
        url = url.substring(0, url.length() - 1);
      }
      HttpResponse<String> response = AWSXRayUnirest.get(
              url + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        System.out.println("got 404, trying to look for object in other regions");
        for (String o : regions) {
          JsonObject region = new JsonObject(o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (url.equals(regUrl)) {
            continue;
          }
          url = regUrl;
          response = AWSXRayUnirest.get(url + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }

      if (response.getStatus() == HttpStatus.OK) {
        JsonNode body = new JsonNode(response.getBody());
        JSONObject obj = (JSONObject) body.getArray().get(0);
        String fileName =
            obj != null ? obj.getString(Field.NAME.getName()) : Field.UNKNOWN.getName();
        String versionId = obj != null ? obj.getString(Field.VERSION_ID.getName()) : null;

        if (returnDownloadUrl) {
          HttpResponse<String> downloadUrlRequest = AWSXRayUnirest.get(
                  url + "/files/fs/" + fileId + "/downloadurl?versionId=" + versionId,
                  "TrimbleConnect.getData")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (downloadUrlRequest.getStatus() != HttpStatus.OK) {
            sendError(segment, message, downloadUrlRequest);
            return;
          } else {
            String downloadURL =
                new JsonObject(downloadUrlRequest.getBody()).getString(Field.URL.getName());
            if (Utils.isStringNotNullOrEmpty(downloadURL)) {
              Long size = null;
              if (Objects.nonNull(obj)) {
                size = (long) obj.getInt(Field.SIZE.getName());
              }
              sendDownloadUrl(segment, message, downloadURL, size, versionId, null, mills);
              return;
            }
          }
        }
        byte[] dataBytes = getFileContent(url, fileId, versionId, accessToken);

        finishGetFile(
            message, null, null, dataBytes, storageType, fileName, versionId, downloadToken);
        success = true;
      }
    } catch (Exception ex) {
      log.error(ex);
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, ex.getLocalizedMessage(), null);
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
    String apiUrl = message.body().getString("apiUrl");
    try {
      HttpResponse<String> response;
      response = AWSXRayUnirest.get(apiUrl + "/files/" + fileId, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj = (JSONObject) body.getArray().get(0);
      String fileName = obj != null ? obj.getString(Field.NAME.getName()) : Field.UNKNOWN.getName();
      for (String specialCharacter : windowsSpecialCharacters) {
        if (fileName.contains(getNonRegexSpecialCharacter(specialCharacter))) {
          fileName = fileName.replaceAll(specialCharacter, "_");
        }
      }
      if (Objects.nonNull(obj) && (latest || versionId == null)) {
        versionId = obj.getString(Field.VERSION_ID.getName());
      }
      byte[] data = new byte[0];
      HttpResponse<String> downloadUrlRequest = AWSXRayUnirest.get(
              apiUrl + "/files/fs/" + fileId + "/downloadurl?versionId=" + versionId,
              "TrimbleConnect.getData")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (downloadUrlRequest.getStatus() != HttpStatus.OK) {
        sendError(segment, message, downloadUrlRequest);
        return;
      } else {
        try {
          String downloadURL =
              new JsonObject(downloadUrlRequest.getBody()).getString(Field.URL.getName());
          if (Utils.isStringNotNullOrEmpty(downloadURL)) {
            if (returnDownloadUrl) {
              Long size = null;
              if (Objects.nonNull(obj)) {
                size = (long) obj.getInt(Field.SIZE.getName());
              }
              sendDownloadUrl(segment, message, downloadURL, size, versionId, null, mills);
              return;
            }
            HttpResponse<byte[]> fileDataRequest = AWSXRayUnirest.get(
                    downloadURL, "TrimbleConnect.download")
                .header("Authorization", "Bearer " + accessToken)
                .asBytes();
            if (fileDataRequest.getStatus() == HttpStatus.OK) {
              data = fileDataRequest.getBody();
            }
          }
        } catch (Exception ignored) {
        }
      }
      finishGetFile(message, start, end, data, storageType, fileName, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception ex) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, ex.getLocalizedMessage(), null);
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
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
    String apiUrl = message.body().getString("apiUrl");
    try {
      boolean isProject = false;
      if (folderId.contains(":")) {
        isProject = true;
        folderId = folderId.split(":")[0];
      }
      HttpResponse<String> response = AWSXRayUnirest.delete(
              apiUrl + (isProject ? "/projects/" : "/folders/") + folderId,
              "TrimbleConnect.deleteFolder")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.NO_CONTENT) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
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
    String apiUrl = message.body().getString("apiUrl");
    try {
      boolean isProject = false;
      if (folderId.contains(":")) {
        isProject = true;
        folderId = folderId.split(":")[0];
      }
      HttpResponse<String> response = AWSXRayUnirest.patch(
              apiUrl + (isProject ? "/projects/" : "/folders/") + folderId,
              "TrimbleConnect.renameFolder")
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-Type", "application/json")
          .body(new JSONObject().put(Field.NAME.getName(), name))
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response);
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());

    if (parentId == null || name == null) {
      return;
    }

    final String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
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

    String accessToken = connect(segment, message);
    if (accessToken == null) {
      return;
    }
    String apiUrl = message.body().getString("apiUrl");
    try {
      boolean isProject = parentId.equals(Field.MINUS_1.getName());
      if (parentId.contains(":")) {
        parentId = parentId.split(":")[1];
      }
      JSONObject json = new JSONObject().put(Field.NAME.getName(), name);
      if (!isProject) {
        json.put(Field.PARENT_ID.getName(), parentId);
      }
      HttpResponse<String> response = AWSXRayUnirest.post(
              apiUrl + (isProject ? "/projects" : "/folders"), "TrimbleConnect.createFolder")
          .header("Authorization", "Bearer " + accessToken)
          .header("Content-Type", "application/json")
          .body(json)
          .asString();
      if (response.getStatus() != HttpStatus.CREATED) {
        sendError(segment, message, response);
        return;
      }
      JSONObject obj = new JsonNode(response.getBody()).getObject();
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType),
                      externalId,
                      obj.getString(Field.ID.getName())
                          + (obj.has("rootId") ? ":" + obj.getString("rootId") : emptyString))),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    if (trash) {
      sendNotImplementedError(message);
      return;
    }
    long mills = System.currentTimeMillis();
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    if (folderId == null) {
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
    if (root) {
      getAllProjects(segment, message, accessToken, mills);
    } else {
      getProjectContent(segment, message, accessToken, folderId, mills);
    }
  }

  private void getProjectContent(
      Entity segment, Message<JsonObject> message, String accessToken, String id, long mills) {
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
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
    //        JsonArray filesJson = new JsonArray();
    Map<String, JsonObject> filesJson = new HashMap<>();
    List<JsonObject> foldersJson = new ArrayList<>();
    String apiUrl = jsonObject.getString("apiUrl");
    String device = jsonObject.getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    JsonObject messageBody = message.body();
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;
    JsonArray thumbnail = new JsonArray();
    Map<String, String> possibleIdsToIds = new HashMap<>();
    try {
      if (id.contains(":")) {
        id = id.split(":")[1];
      }
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/folders/" + id + "/items", "TrimbleConnect.getFolderContent")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK
          && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
        sendError(segment, message, response);
        return;
      }
      JsonNode body = new JsonNode(response.getBody());
      JSONObject obj;
      for (Object item : body.getArray()) {
        obj = (JSONObject) item;
        if (obj.getString(Field.TYPE.getName()).equals(FOLDER)) {
          foldersJson.add(getFileJson(
              obj, true, full, externalId, accessToken, apiUrl, false, userId, false, false));
        } else {
          @NonNls String filename = obj.getString(Field.NAME.getName());
          if (Extensions.isValidExtension(extensions, filename)) {
            boolean createThumbnail = canCreateThumbnails;
            if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
              thumbnail.add(new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      obj.getString(Field.ID.getName())
                          + (obj.has("rootId") ? (":" + obj.getString("rootId")) : emptyString))
                  .put(
                      Field.VERSION_ID.getName(),
                      obj.has(Field.VERSION_ID.getName())
                          ? obj.getString(Field.VERSION_ID.getName())
                          : null)
                  .put(Field.EXT.getName(), Extensions.getExtension(filename)));
            } else {
              createThumbnail = false;
            }

            JsonObject json = getFileJson(
                obj,
                false,
                full,
                externalId,
                accessToken,
                apiUrl,
                isAdmin,
                userId,
                force,
                createThumbnail);
            filesJson.put(obj.getString(Field.ID.getName()), json);

            if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
              if (!json.getBoolean(Field.VIEW_ONLY.getName())) {
                possibleIdsToIds.put(
                    obj.getString(Field.ID.getName()), obj.getString(Field.ID.getName()));
              }
            }
          }
        }
      }
      if (canCreateThumbnails && !thumbnail.isEmpty()) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
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

      foldersJson.sort(
          Comparator.comparing(o2 -> o2.getString(Field.NAME.getName()).toLowerCase()));
      List<JsonObject> filesList = new ArrayList<>(filesJson.values());
      filesList.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject result = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesList))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put("number", filesJson.size() + foldersJson.size())
          //                    .put(Field.PAGE_TOKEN.getName(), emptyString)
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      sendOK(segment, message, result, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  private JsonObject getFileJson(
      JSONObject obj,
      boolean isFolder,
      boolean full,
      String externalId,
      String accessToken,
      String apiUrl,
      boolean isAdmin,
      String userId,
      boolean force,
      boolean canCreateThumbnails)
      throws UnirestException {
    return getFileJson(
        obj,
        isFolder,
        full,
        externalId,
        accessToken,
        apiUrl,
        emptyString,
        isAdmin,
        userId,
        force,
        canCreateThumbnails);
  }

  private JsonObject getFileJson(
      JSONObject obj,
      boolean isFolder,
      boolean full,
      String externalId,
      String accessToken,
      String apiUrl,
      String location,
      boolean isAdmin,
      String userId,
      boolean force,
      boolean canCreateThumbnails)
      throws UnirestException {
    return getFileJson(
        obj,
        isFolder,
        full,
        externalId,
        accessToken,
        apiUrl,
        location,
        true,
        isAdmin,
        userId,
        force,
        canCreateThumbnails);
  }

  private String parseObjForModifierName(JSONObject obj) {
    if (Objects.nonNull(obj) && obj.has(Field.MODIFIED_BY.getName())) {
      JSONObject modifiedBy = obj.getJSONObject(Field.MODIFIED_BY.getName());
      if (modifiedBy.has(Field.FIRST_NAME.getName()) && modifiedBy.has(Field.LAST_NAME.getName())) {
        return modifiedBy.getString(Field.FIRST_NAME.getName()) + " "
            + modifiedBy.getString(Field.LAST_NAME.getName());
      }
    }
    return emptyString;
  }

  private String parseObjForModifierId(JSONObject obj) {
    if (Objects.nonNull(obj) && obj.has(Field.MODIFIED_BY.getName())) {
      JSONObject modifiedBy = obj.getJSONObject(Field.MODIFIED_BY.getName());
      if (modifiedBy.has(Field.ID.getName())) {
        return modifiedBy.getString(Field.ID.getName());
      }
    }
    return emptyString;
  }

  private JsonObject getFileJson(
      JSONObject obj,
      boolean isFolder,
      boolean full,
      String externalId,
      String accessToken,
      String apiUrl,
      String location,
      boolean addShare,
      boolean isAdmin,
      String userId,
      boolean force,
      boolean canCreateThumbnails)
      throws UnirestException {
    long updateDate = 0;
    String verId =
        obj.has(Field.VERSION_ID.getName()) ? obj.getString(Field.VERSION_ID.getName()) : null;
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    if (full) {
      try {
        updateDate = sdf.parse(obj.getString("modifiedOn")
                .substring(0, obj.getString("modifiedOn").lastIndexOf("+")))
            .getTime();
      } catch (Exception ignore) {
      }
    }

    boolean viewOnly = obj.has(Field.PERMISSION.getName())
            && !obj.getString(Field.PERMISSION.getName()).equals(FULL_ACCESS)
        || obj.has(Field.ACCESS.getName())
            && !obj.getString(Field.ACCESS.getName()).equals(FULL_ACCESS);
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    boolean shared = false;
    boolean isProject = obj.has("rootId");
    if (addShare && isFolder) {
      HttpResponse<String> response;
      if (!isProject) {
        response = AWSXRayUnirest.get(
                apiUrl + "/folders/" + obj.getString(Field.ID.getName()) + "/permissions",
                "TrimbleConnect.getCollaborators")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() == HttpStatus.OK) {
          JsonNode body = new JsonNode(response.getBody());
          JSONArray permissions = body.getObject().has(Field.PERMISSIONS.getName())
              ? body.getObject().getJSONArray(Field.PERMISSIONS.getName())
              : new JSONArray();
          shared = !permissions.isEmpty();
          for (Object o : permissions) {
            JsonObject json = new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(), ((JSONObject) o).getString(Field.ID.getName()))
                .put(Field.NAME.getName(), ((JSONObject) o).getString(Field.NAME.getName()));
            if (((JSONObject) o).getString(Field.PERMISSION.getName()).equals(FULL_ACCESS)) {
              editor.add(json);
            } else {
              viewer.add(json);
            }
          }
        }
      } else {
        response = AWSXRayUnirest.get(
                apiUrl + "/projects/" + obj.getString(Field.ID.getName()) + "/users",
                "TrimbleConnect.getCollaborators")
            .header("Authorization", "Bearer " + accessToken)
            .asString();
        if (response.getStatus() == HttpStatus.OK
            || response.getStatus() == HttpStatus.PARTIAL_CONTENT) {
          JsonNode body = new JsonNode(response.getBody());
          for (Object o : body.getArray()) {
            String id = ((JSONObject) o).getString(Field.ID.getName());
            if (!obj.getJSONObject(Field.CREATED_BY.getName())
                    .getString(Field.ID.getName())
                    .equals(id)
                && !((JSONObject) o).getString(Field.STATUS.getName()).equals(REMOVED)) {
              editor.add(new JsonObject()
                  .put(Field.ENCAPSULATED_ID.getName(), id)
                  .put(Field.EMAIL.getName(), ((JSONObject) o).getString(Field.EMAIL.getName()))
                  .put(
                      Field.NAME.getName(),
                      ((JSONObject) o).getString(Field.FIRST_NAME.getName()) + " "
                          + ((JSONObject) o).getString(Field.LAST_NAME.getName())));
            }
          }
        }
      }
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (!isFolder && full && addShare) {
      PLData = findLinkForFile(obj.getString(Field.ID.getName()), externalId, userId, viewOnly);
    }

    long creationDate = 0;
    try {
      creationDate = sdf.parse(
              obj.getString("createdOn").substring(0, obj.getString("createdOn").lastIndexOf("+")))
          .getTime();
    } catch (Exception ignore) {
    }
    String parent = Field.MINUS_1.getName();
    if (obj.has(Field.PARENT_ID.getName())) {
      if (obj.has(Field.PATH.getName())
          && obj.getJSONArray(Field.PATH.getName()).length() == 1
          && obj.has(Field.PROJECT_ID.getName())) {
        // parent is a project
        parent = obj.getString(Field.PROJECT_ID.getName()) + ":"
            + obj.getString(Field.PARENT_ID.getName());
      } else {
        parent = obj.getString(Field.PARENT_ID.getName());
      }
    }
    boolean isOwner = obj.getJSONObject(Field.CREATED_BY.getName())
        .getString(Field.ID.getName())
        .equals(externalId);
    String id = obj.getString(Field.ID.getName())
        + (obj.has("rootId") ? (":" + obj.getString("rootId")) : emptyString);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id))
        .put(Field.WS_ID.getName(), id)
        .put(
            isFolder ? Field.NAME.getName() : Field.FILE_NAME.getName(),
            obj.getString(Field.NAME.getName()))
        .put(
            isFolder ? Field.PARENT.getName() : Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parent))
        .put(
            Field.OWNER.getName(),
            obj.getJSONObject(Field.CREATED_BY.getName()).getString(Field.FIRST_NAME.getName())
                + " "
                + obj.getJSONObject(Field.CREATED_BY.getName())
                    .getString(Field.LAST_NAME.getName()))
        .put(
            Field.OWNER_EMAIL.getName(),
            obj.getJSONObject(Field.CREATED_BY.getName()).getString(Field.EMAIL.getName()))
        .put(Field.CREATION_DATE.getName(), creationDate)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), parseObjForModifierName(obj))
        .put(Field.CHANGER_ID.getName(), parseObjForModifierId(obj))
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(obj.getInt(Field.SIZE.getName())))
        .put(Field.SIZE_VALUE.getName(), obj.getInt(Field.SIZE.getName()))
        .put(Field.SHARED.getName(), shared)
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(Field.CAN_MOVE.getName(), isOwner)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor))
        .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()))
        .put(Field.SERVER.getName(), location)
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(
            Field.PROJECT_ID.getName(),
            obj.has(Field.PROJECT_ID.getName())
                ? obj.getString(Field.PROJECT_ID.getName())
                : Field.MINUS_1.getName());
    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    if (!isFolder) {
      String thumbnailName = StorageType.getShort(storageType) + "_" + id + "_" + verId + ".png";
      String previewId = StorageType.getShort(storageType) + "_" + id;
      json.put(Field.PREVIEW_ID.getName(), previewId);
      json.put(Field.THUMBNAIL_NAME.getName(), thumbnailName);

      if (Extensions.isThumbnailExt(config, obj.getString(Field.NAME.getName()), isAdmin)) {
        // AS : Removing this temporarily until we have some server cache (WB-1248)
        //        json.put("thumbnailStatus",
        //                ThumbnailsManager.getThumbnailStatus(id, storageType.name(), verId, force,
        //                    canCreateThumbnails))
        json.put(
                Field.THUMBNAIL.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .put(
                Field.GEOMDATA.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
            .put(Field.PREVIEW.getName(), ThumbnailsManager.getPreviewURL(config, previewId, true));
      }
    }
    if (isFolder && isProject) {
      json.put("icon", "https://icons.kudo.graebert.com/trimble-project.svg");
      json.put("icon_black", "https://icons.kudo.graebert.com/trimble-project.svg");
    }
    if (verId != null) {
      json.put(Field.VER_ID.getName(), verId);
      json.put(Field.VERSION_ID.getName(), verId);
    }
    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setPermissionAccess(AccessType.canClone, false)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isFolder)
        .setPermissionAccess(AccessType.canViewPermissions, isFolder && isProject)
        .setPermissionAccess(AccessType.canManagePermissions, !viewOnly && isFolder && isProject)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isFolder);

    if (isProject) {
      permissions
          .setPermissionAccess(AccessType.canRename, isOwner)
          .setPermissionAccess(AccessType.canMove, false);
    }
    if (!isFolder) {
      permissions.setBatchTo(
          List.of(
              AccessType.canMoveFrom,
              AccessType.canMoveTo,
              AccessType.canCreateFiles,
              AccessType.canCreateFolders),
          false);
    }
    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  private void getAllProjects(
      Entity segment, Message<JsonObject> message, String accessToken, long mills) {
    JsonObject messageBody = message.body();
    String apiUrl = messageBody.getString("apiUrl");
    String pageToken = messageBody.getString(Field.PAGE_TOKEN.getName());
    Boolean full = messageBody.getBoolean(Field.FULL.getName());
    if (full == null) {
      full = true;
    }
    String p = messageBody.getString(Field.PAGINATION.getName());
    final String externalId = messageBody.getString(Field.EXTERNAL_ID.getName());
    boolean shouldUsePagination = p == null || Boolean.parseBoolean(p);
    final int pageLimit = 100;
    final String folderId = Field.MINUS_1.getName();
    Pagination pagination = new Pagination(storageType, pageLimit, externalId, folderId);
    // get pagination info
    pagination.getPageInfo(pageToken);
    List<JsonObject> foldersJson = new ArrayList<>();
    try {
      int offset = 0, limit = pageLimit;
      if (shouldUsePagination) {
        offset = pagination.getOffset();
        limit = pagination.getLimit();
      }
      long m = System.currentTimeMillis();
      @NonNls
      GetRequest gr = AWSXRayUnirest.get(apiUrl + "/projects", "TrimbleConnect.getProjects")
          .header("Authorization", "Bearer " + accessToken);
      if (shouldUsePagination) {
        gr.header("Range", "items=" + offset + "-" + (offset + limit - 1));
      }
      HttpResponse<String> response = gr.asString();
      System.out.println("got all projects in " + (System.currentTimeMillis() - m));
      if (response.getStatus() != HttpStatus.OK
          && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
        sendError(segment, message, response);
        return;
      }
      // parse content-range
      int length = -1;
      if (response.getStatus() == HttpStatus.PARTIAL_CONTENT) {
        List<String> range = response.getHeaders().get("Content-Range");
        if (!range.isEmpty()) {
          String value = range.get(0);
          String[] section = value.split("/");
          length = Integer.parseInt(section[1]);
          int end = Integer.parseInt(section[0].split("-")[1]);
          offset = offset + end + 1;
        }
      }
      JSONObject obj;
      JsonObject json;
      SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
      JsonNode body = new JsonNode(response.getBody());
      for (Object item : body.getArray()) {
        obj = (JSONObject) item;
        boolean containsCreatorInfo = obj.has(Field.CREATED_BY.getName());
        String owner = "";
        if (containsCreatorInfo) {
          owner = obj.getJSONObject(Field.CREATED_BY.getName())
                  .getString(Field.FIRST_NAME.getName())
              + " "
              + obj.getJSONObject(Field.CREATED_BY.getName()).getString(Field.LAST_NAME.getName());
        }

        boolean viewOnly = !obj.getString(Field.ACCESS.getName()).equals(FULL_ACCESS);
        boolean isOwner = containsCreatorInfo
            && obj.getJSONObject(Field.CREATED_BY.getName())
                .getString(Field.ID.getName())
                .equals(externalId);
        JsonArray viewer = new JsonArray();
        JsonArray editor = new JsonArray();
        //                if (full) { // removed collaborators from the result list
        //                    response = AWSXRayUnirest.get(apiUrl + "/projects/" + obj.getString
        //                    (Field.ID.getName()) + "/users")
        //                            .header("Authorization", "Bearer " + accessToken)
        //                            .asString();
        //                    if (response.getStatus() == HttpStatus.OK || response.getStatus()
        //                    == HttpStatus.PARTIAL_CONTENT) {
        //                        body = new JsonNode(response.getBody());
        //                        for (Object o : body.getArray()) {
        //                            String id = ((JSONObject) o).getString(Field.ID.getName());
        //                            if
        // (!obj.getJSONObject(Field.CREATED_BY.getName()).getString(Field.ID.getName()).equals(id)
        //                                    && !((JSONObject)
        // o).getString(Field.STATUS.getName()).equals
        //                                    (REMOVED))
        //                                editor.add(new
        // JsonObject().put(Field.ENCAPSULATED_ID.getName(), id)
        //                                        .put(Field.NAME.getName(), ((JSONObject)
        // o).getString
        //                                        (Field.EMAIL.getName())));
        //                        }
        //                    }
        //                }
        json = new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(
                    StorageType.getShort(storageType),
                    externalId,
                    obj.getString(Field.ID.getName()) + ":" + obj.getString("rootId")))
            .put(Field.NAME.getName(), obj.getString(Field.NAME.getName()))
            .put(Field.PARENT.getName(), Field.MINUS_1.getName())
            .put(Field.OWNER.getName(), owner)
            .put(
                Field.CREATION_DATE.getName(),
                sdf.parse(obj.getString("createdOn")
                        .substring(0, obj.getString("createdOn").lastIndexOf("+")))
                    .getTime())
            .put(
                Field.UPDATE_DATE.getName(),
                sdf.parse(obj.getString("modifiedOn")
                        .substring(0, obj.getString("modifiedOn").lastIndexOf("+")))
                    .getTime())
            .put(Field.CHANGER.getName(), parseObjForModifierName(obj))
            .put(Field.CHANGER_ID.getName(), parseObjForModifierId(obj))
            .put(
                Field.SIZE.getName(),
                Utils.humanReadableByteCount(obj.getInt(Field.SIZE.getName())))
            .put(Field.SIZE_VALUE.getName(), obj.getInt(Field.SIZE.getName()))
            .put(Field.SHARED.getName(), !editor.isEmpty())
            .put(Field.VIEW_ONLY.getName(), viewOnly)
            .put(Field.IS_OWNER.getName(), isOwner)
            .put(Field.CAN_MOVE.getName(), isOwner)
            .put(
                Field.SHARE.getName(),
                new JsonObject()
                    .put(Field.VIEWER.getName(), viewer)
                    .put(Field.EDITOR.getName(), editor))
            .put("icon", "https://icons.kudo.graebert.com/trimble-project.svg")
            .put("icon_black", "https://icons.kudo.graebert.com/trimble-project.svg")
            .put(Field.PROJECT_ID.getName(), obj.getString(Field.ID.getName()));
        ObjectPermissions permissions = new ObjectPermissions()
            .setAllTo(true)
            .setBatchTo(List.of(AccessType.canClone, AccessType.canMove), false)
            .setPermissionAccess(AccessType.canRename, isOwner)
            .setBatchTo(
                List.of(
                    AccessType.canViewPublicLink,
                    AccessType.canManagePublicLink,
                    AccessType.canManagePermissions),
                !viewOnly);
        json.put(Field.PERMISSIONS.getName(), permissions.toJson());
        foldersJson.add(json);
      }
      foldersJson.sort(
          Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
      JsonObject result = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray())
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put("number", foldersJson.size())
          .put(Field.FULL.getName(), full);
      if (shouldUsePagination && !foldersJson.isEmpty() && offset < length) {
        String nextPageToken = pagination.saveNewPageInfo(pagination.getNextPageToken(), offset);
        result.put(Field.PAGE_TOKEN.getName(), nextPageToken);
      } else {
        result.remove(Field.PAGE_TOKEN.getName());
      }
      sendOK(segment, message, result, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
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
      HttpResponse<String> response = AWSXRayUnirest.post(tokenUrl, "TrimbleConnect.token")
          .header("Authorization", "Basic " + authentication)
          .header("Content-type", "application/x-www-form-urlencoded")
          .body("grant_type=authorization_code&code=" + code + "&redirect_uri="
              + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8))
          .asString();
      if (response.getStatus() == HttpStatus.OK) {
        JsonNode body = new JsonNode(response.getBody());
        String jwt = body.getObject().getString("id_token");
        long expiresIn = body.getObject().getLong("expires_in");
        String refreshToken = body.getObject().getString(Field.REFRESH_TOKEN.getName());
        String accessToken = body.getObject().getString(Field.ACCESS_TOKEN.getName());
        // store raw for check requests
        final String rawAccessToken = accessToken;
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
        response = AWSXRayUnirest.get(apiUrl + "/users/me", "TrimbleConnect.getMe")
            .header("Authorization", "Bearer " + rawAccessToken)
            .asString();
        if (response.getStatus() == HttpStatus.OK) {
          body = new JsonNode(response.getBody());
          JSONObject result = body.getObject();
          String trimbleId = result.getString(Field.ID.getName());
          Item externalAccount =
              ExternalAccounts.getExternalAccount(userId, result.getString(Field.ID.getName()));
          if (externalAccount == null) {
            externalAccount = new Item()
                .withPrimaryKey(
                    Field.FLUORINE_ID.getName(),
                    userId,
                    Field.EXTERNAL_ID_LOWER.getName(),
                    result.getString(Field.ID.getName()))
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
                result.getString(Field.ID.getName()),
                true,
                intercomAccessToken);
          }
          externalAccount
              .withString(Field.EMAIL.getName(), result.getString(Field.EMAIL.getName()))
              .withString(Field.REFRESH_TOKEN.getName(), refreshToken)
              .withString(Field.ACCESS_TOKEN.getName(), accessToken)
              .withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + expiresIn);
          List<String> regions = new ArrayList<>();
          String serverLocation = null;
          List<String> regionLocations = new ArrayList<>();
          try {
            response = AWSXRayUnirest.get(apiUrl + "/regions", "TrimbleConnect.getRegions")
                .header("Authorization", "Bearer " + rawAccessToken)
                .header("Content-Type", "application/json")
                .asString();
            if (response.getStatus() == HttpStatus.OK) {
              JSONObject region;
              for (Object o : new JsonNode(response.getBody()).getArray()) {
                region = ((JSONObject) o);
                if (region.getBoolean("isMaster")) {
                  externalAccount.withString(Field.SERVER.getName(), region.toString());
                  serverLocation = region.getString(Field.LOCATION.getName());
                }
                regions.add(region.toString());
                regionLocations.add(region.getString(Field.LOCATION.getName()));
              }
            }
          } catch (Exception ignore) {
          }
          try {
            jwt = EncryptHelper.encrypt(jwt, config.getProperties().getFluorineSecretKey());
          } catch (Exception e) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "InternalError"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                e);
            return;
          }
          externalAccount.withString("jwt", jwt).withList(Field.REGIONS.getName(), regions);
          try {
            Sessions.updateSessionOnConnect(
                externalAccount,
                userId,
                storageType,
                trimbleId,
                sessionId,
                Utils.getStorageObject(storageType, trimbleId, serverLocation, regionLocations));
          } catch (Exception e) {
            sendError(
                segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
            return;
          }
          sendOK(segment, message, mills);
        } else {
          sendError(segment, message, response);
        }
      } else {
        sendError(segment, message, response);
      }
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    connect(null, message, message.body(), replyOnOk);
  }

  private <T> String connect(Entity segment, Message<T> message) {
    return connect(segment, message, MessageUtils.parse(message).getJsonObject(), false);
  }

  private <T> String connect(Entity segment, Message<T> message, String userId, String externalId) {
    JsonObject object = MessageUtils.parse(message).getJsonObject();
    return connect(
        segment,
        message,
        object.put(Field.USER_ID.getName(), userId).put(Field.EXTERNAL_ID.getName(), externalId),
        false);
  }

  private <T> String connect(
      Entity segment, Message<T> message, JsonObject json, boolean replyOnOk) {
    if (segment == null) {
      segment = XRayManager.createSegment(operationGroup, message);
    }
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String trimbleId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (userId == null) {
      logConnectionIssue(storageType, null, trimbleId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (trimbleId == null || trimbleId.isEmpty()) {
      trimbleId = findExternalId(segment, message, json, storageType);
      if (trimbleId == null) {
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
    String region = json.getString(Field.REGION.getName());
    Item trimbleUser = ExternalAccounts.getExternalAccount(userId, trimbleId);
    if (trimbleUser == null) {
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisTrimbleConnectAccount"),
          403);
      return null;
    }
    json.put(
        "isAccountThumbnailDisabled",
        trimbleUser.hasAttribute("disableThumbnail") && trimbleUser.getBoolean("disableThumbnail"));
    if (region != null && trimbleUser.hasAttribute(Field.REGIONS.getName())) {
      for (Object o : trimbleUser.getList(Field.REGIONS.getName())) {
        if (new JSONObject((String) o).getString(Field.LOCATION.getName()).equals(region)) {
          trimbleUser.withString(Field.SERVER.getName(), (String) o);
          ExternalAccounts.saveExternalAccount(
              trimbleUser.getString(Field.FLUORINE_ID.getName()),
              trimbleUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
              trimbleUser);
          break;
        }
      }
    }
    String accessToken = trimbleUser.getString(Field.ACCESS_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    json.put(
        "apiUrl",
        trimbleUser.hasAttribute(Field.SERVER.getName())
            ? "https:"
                + new JSONObject(trimbleUser.getString(Field.SERVER.getName()))
                    .getString(Field.ORIGIN.getName())
                + "/tc/api/2.0"
            : apiUrl); // NON-NLS
    json.put(
        Field.LOCATION.getName(),
        trimbleUser.hasAttribute(Field.SERVER.getName())
            ? new JSONObject(trimbleUser.getString(Field.SERVER.getName()))
                .getString(Field.LOCATION.getName())
            : emptyString);
    json.put(Field.REGIONS.getName(), new JsonArray(trimbleUser.getList(Field.REGIONS.getName())));

    long expires = trimbleUser.getLong(Field.EXPIRES.getName());
    if (GMTHelper.utcCurrentTime() >= expires) {
      accessToken = refreshToken(segment, message, userId, trimbleId);
    }
    if (replyOnOk) {
      sendOK(segment, message);
    }
    return accessToken;
  }

  private <T> String refreshToken(
      Entity segment, Message<T> message, String userId, String trimbleId) {
    Item account = ExternalAccounts.getExternalAccount(userId, trimbleId);
    String encodedRefreshToken = null;
    if (account == null) {
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.NO_ENTRY_IN_DB);
    } else if (!account.hasAttribute(Field.REFRESH_TOKEN.getName())) {
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.NO_REFRESH_TOKEN);
    } else {
      encodedRefreshToken = account.getString(Field.REFRESH_TOKEN.getName());
    }
    if (encodedRefreshToken == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.BAD_REQUEST);
      return null;
    }
    String refresh_token;
    try {
      refresh_token =
          EncryptHelper.decrypt(encodedRefreshToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.BAD_REQUEST,
          e);
      return null;
    }
    try {
      HttpResponse<String> response = AWSXRayUnirest.post(tokenUrl, "TrimbleConnect.token")
          .header("Authorization", "Basic " + authentication)
          .header("Content-type", "application/x-www-form-urlencoded")
          .body("grant_type=refresh_token&refresh_token=" + refresh_token)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_REFRESH_TOKENS);
        sendError(segment, message, response);
        return null;
      }
      JsonNode body = new JsonNode(response.getBody());
      String jwt = body.getObject().getString("id_token");
      long expiresIn = body.getObject().getLong("expires_in");
      Item item = ExternalAccounts.getExternalAccount(userId, trimbleId);
      String newRefreshToken = body.getObject().getString(Field.REFRESH_TOKEN.getName());
      String orgNewAccessToken = body.getObject().getString(Field.ACCESS_TOKEN.getName());
      String newAccessToken = orgNewAccessToken;
      try {
        newAccessToken =
            EncryptHelper.encrypt(newAccessToken, config.getProperties().getFluorineSecretKey());
        newRefreshToken =
            EncryptHelper.encrypt(newRefreshToken, config.getProperties().getFluorineSecretKey());
        jwt = EncryptHelper.encrypt(jwt, config.getProperties().getFluorineSecretKey());
      } catch (Exception e) {
        log.error(e);
        logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InternalError"),
            HttpStatus.BAD_REQUEST,
            e);
        return null;
      }
      item.withString(Field.REFRESH_TOKEN.getName(), newRefreshToken)
          .withString(Field.ACCESS_TOKEN.getName(), newAccessToken)
          .withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + (1000 * expiresIn))
          .withString("jwt", jwt);
      ExternalAccounts.saveExternalAccount(
          item.getString(Field.FLUORINE_ID.getName()),
          item.getString(Field.EXTERNAL_ID_LOWER.getName()),
          item);
      return orgNewAccessToken;
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_REFRESH_TOKENS);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
    return null;
  }

  @Override
  public void stop() {
    super.stop();
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
    String accessToken = connect(segment, message, jsonObject, false);
    if (accessToken == null) {
      return;
    }

    String apiUrl = jsonObject.getString("apiUrl");
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.NOT_FOUND) {
        System.out.println("got 404, trying to look for object in other regions");
        for (Object o : jsonObject.getJsonArray(Field.REGIONS.getName())) {
          JsonObject region = new JsonObject((String) o);
          @NonNls
          String regUrl = "https:" + region.getString(Field.ORIGIN.getName()) + "/tc/api/2.0";
          if (apiUrl.equals(regUrl)) {
            continue;
          }
          apiUrl = regUrl;
          response = AWSXRayUnirest.get(apiUrl + "/files/" + id, "TrimbleConnect.getObjectInfo")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (response.getStatus() != HttpStatus.NOT_FOUND) {
            break;
          }
        }
      }
      if (response.getStatus() != HttpStatus.OK) {
        // We can return true, because it is used for RF validation only
        // https://graebert.atlassian.net/browse/XENON-30048
        // should be changed if this function is required somewhere else in the future.
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.IS_DELETED.getName(), true)
                .put("nativeResponse", new JsonObject(response.getBody())),
            mills);
        return;
      }
      new JsonObject(new JsonNode(response.getBody()).getArray().get(0).toString());

      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), false), mills);
    } catch (Exception ex) {
      // We can return true, because it is used for RF validation only
      // https://graebert.atlassian.net/browse/XENON-30048
      // should be changed if this function is required somewhere else in the future.
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", ex.getMessage()),
          mills);
    }
  }

  private byte[] getFileContent(
      String apiUrl, String fileId, String versionId, String accessToken) {
    byte[] data = new byte[0];
    HttpResponse<String> downloadUrlRequest = AWSXRayUnirest.get(
            apiUrl + "/files/fs/" + fileId + "/downloadurl?versionId=" + versionId,
            "TrimbleConnect.getData")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (downloadUrlRequest.getStatus() == HttpStatus.OK) {
      try {
        String downloadURL =
            new JsonObject(downloadUrlRequest.getBody()).getString(Field.URL.getName());
        if (Utils.isStringNotNullOrEmpty(downloadURL)) {
          HttpResponse<byte[]> fileDataRequest = AWSXRayUnirest.get(
                  downloadURL, "TrimbleConnect.download")
              .header("Authorization", "Bearer " + accessToken)
              .asBytes();
          if (fileDataRequest.getStatus() == HttpStatus.OK) {
            data = fileDataRequest.getBody();
          }
        }
      } catch (Exception ignored) {
      }
    }
    return data;
  }

  private String connect(Entity segment, Message<JsonObject> message, Item user) {
    String accessToken = user.getString(Field.ACCESS_TOKEN.getName());
    String userId = user.getString(Field.FLUORINE_ID.getName());
    String trimbleId = user.getString(Field.EXTERNAL_ID_LOWER.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      logConnectionIssue(storageType, userId, trimbleId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetAccessToken"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return null;
    }
    try {
      HttpResponse<String> response = AWSXRayUnirest.get(
              apiUrl + "/users/me", "TrimbleConnect.getMe")
          .header("Authorization", "Basic " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "TrimbleServiceUnavailable"),
            HttpStatus.SERVICE_UNAVAILABLE);
        return null;
      } else if (response.getStatus() == HttpStatus.UNAUTHORIZED) {
        accessToken = refreshToken(segment, message, userId, trimbleId);
        return accessToken;
      } else if (response.getStatus() != HttpStatus.OK) {
        sendError(segment, message, response.getBody(), response.getStatus());
        return null;
      }
    } catch (Exception exception) {
      log.error(exception);
      return null;
    }
    return accessToken;
  }
}
