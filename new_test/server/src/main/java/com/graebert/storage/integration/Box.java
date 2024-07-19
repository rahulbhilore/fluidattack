package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxAPIRequest;
import com.box.sdk.BoxAPIResponseException;
import com.box.sdk.BoxCollaboration;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFileVersion;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxGlobalSettings;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxJSONResponse;
import com.box.sdk.BoxSearch;
import com.box.sdk.BoxSearchParameters;
import com.box.sdk.BoxSharedLink;
import com.box.sdk.BoxTrash;
import com.box.sdk.BoxUser;
import com.box.sdk.FileUploadParams;
import com.box.sdk.PartialCollection;
import com.google.common.collect.Lists;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.CollaboratorInfo;
import com.graebert.storage.Entities.Collaborators;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionModifier;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.xray.AWSXRayBoxFile;
import com.graebert.storage.integration.xray.AWSXRayBoxFolder;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.TempData;
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
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
import kong.unirest.UnirestException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class Box extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.BOX;

  @NonNls
  public static final String address = "box";

  private static final Logger log = LogManager.getRootLogger();
  private static final String ROOT = "0";
  private static final StorageType storageType = StorageType.BOX;
  private String CLIENT_ID, CLIENT_SECRET;
  private final String[] specialCharacters = {"/", "\\"};
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private S3Regional s3Regional = null;
  // private HttpClient client;

  public Box() {}

  public static boolean needsRefresh(BoxAPIConnection connection) {
    long now = GMTHelper.utcCurrentTime();
    long tokenDuration = now - connection.getLastRefresh();
    return tokenDuration >= connection.getExpires() - 60000L;
  }

  public static void refresh(BoxAPIConnection connection) {
    connection.setMaxRetryAttempts(2);
    // box ignores global timeout settings on refreshing tokens
    if (!connection.canRefresh()) {
      throw new IllegalStateException(
          "The BoxAPIConnection cannot be refreshed because it doesn't have a refresh token.");
    } else {
      URL url;
      try {
        url = new URL(connection.getTokenURL());
      } catch (MalformedURLException var7) {
        throw new RuntimeException("An invalid refresh URL indicates a bug in the SDK.", var7);
      }
      String urlParameters = String.format(
          "grant_type=refresh_token&refresh_token=%s&client_id=%s&client_secret=%s",
          connection.getRefreshToken(), connection.getClientID(), connection.getClientSecret());
      BoxAPIRequest request = new BoxAPIRequest(connection, url, "POST");
      //            request.shouldAuthenticate(false);
      request.setBody(urlParameters);
      request.setConnectTimeout(25 * 1000);
      request.setReadTimeout(25 * 1000);

      String json;
      BoxJSONResponse response = (BoxJSONResponse) request.send();
      json = response.getJSON();

      com.eclipsesource.json.JsonValue jsonValue = com.eclipsesource.json.Json.parse(json);
      com.eclipsesource.json.JsonObject jsonObject = jsonValue.asObject();
      connection.setAccessToken(jsonObject.get(Field.ACCESS_TOKEN.getName()).asString());
      connection.setRefreshToken(jsonObject.get(Field.REFRESH_TOKEN.getName()).asString());
      connection.setExpires(jsonObject.get("expires_in").asLong() * 1000L);
    }
    connection.setLastRefresh(
        GMTHelper.utcCurrentTime() /*toGmtTime(connection.getLastRefresh())*/);
  }

  @Override
  public void start() throws Exception {
    super.start();
    CLIENT_ID = config.getProperties().getBoxClientId();
    CLIENT_SECRET = config.getProperties().getBoxClientSecret();

    eb.consumer(address + ".storeConnection", this::storeConnection);
    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> event) -> doGetFolderContent(event, false));
    eb.consumer(address + ".createFolder", this::doCreateFolder);
    eb.consumer(address + ".renameFolder", this::doRenameFolder);
    eb.consumer(address + ".moveFolder", this::doMoveFolder);
    eb.consumer(address + ".deleteFolder", this::doDeleteFolder);
    eb.consumer(address + ".getFile", this::doGetFile);
    eb.consumer(address + ".getAllFiles", this::doGlobalSearch);
    eb.consumer(address + ".uploadFile", this::doUploadFile);
    eb.consumer(address + ".moveFile", this::doMoveFile);
    eb.consumer(address + ".deleteFile", this::doDeleteFile);
    eb.consumer(address + ".getVersions", this::doGetVersions);
    eb.consumer(address + ".uploadVersion", this::doUploadVersion);
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
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    //        log.info("Box api ip - " + InetAddress.getByName("api.box.com").getHostAddress());
    //        log.info("Box upload ip - " + InetAddress.getByName("upload.box.com")
    //        .getHostAddress());

    // todo: default value is 0 (infinity); check if it's worth to change
    BoxGlobalSettings.setConnectTimeout(30 * 1000);
    BoxGlobalSettings.setReadTimeout(30 * 1000);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    // client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-box");
  }

  private void storeConnection(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    long startTime = System.currentTimeMillis();

    Entity subsegment =
        XRayManager.createSubSegment(operationGroup, segment, "BOX.EncapsulationConnect");

    // DK - we actually don't care about userId much here.
    String userId = jsonObject.containsKey(Field.USER_ID.getName())
        ? jsonObject.getString(Field.USER_ID.getName())
        : emptyString;
    String authCode = jsonObject.getString("authCode");

    if (authCode == null || authCode.isEmpty()) {
      logConnectionIssue(storageType, userId, null, ConnectionError.NO_EXTERNAL_ID);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
      return;
    }
    BoxAPIConnection api = null;
    Item boxUser = null;
    // as far as authorization code is alive for couple seconds, first try to exchange it to
    // access and refresh tokens
    String box_error = emptyString;
    try {
      api = new BoxAPIConnection(CLIENT_ID, CLIENT_SECRET, authCode);
    } catch (BoxAPIException e) {
      box_error = e.getMessage();
      log.error(e);
      logConnectionIssue(
          storageType, "ENCAPSULATION_" + userId, authCode, ConnectionError.CONNECTION_EXCEPTION);
    }
    // save data to the db for future calls
    // always do this because tbh this is badly implemented
    if (api != null) {
      boxUser = new Item()
          .withPrimaryKey(Field.PK.getName(), "box#" + authCode, Field.SK.getName(), authCode)
          .withString(Field.EXTERNAL_ID_LOWER.getName(), authCode)
          .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());
      String state = api.save();
      try {
        state = EncryptHelper.encrypt(state, config.getProperties().getFluorineSecretKey());
      } catch (Exception e) {
        log.error(e);
        logConnectionIssue(
            storageType,
            "ENCAPSULATION_" + userId,
            authCode,
            ConnectionError.CANNOT_ENCRYPT_TOKENS);
      }
      boxUser.withString("fstate", state);
      TempData.putBoxUser(authCode, boxUser);
    }

    // try to find existing box connection
    if (Utils.isStringNotNullOrEmpty(userId)) {
      String boxUserId = jsonObject.getString("boxUserId");
      if (Utils.isStringNotNullOrEmpty(boxUserId)) {
        Item existingAccount = ExternalAccounts.getExternalAccount(userId, boxUserId);
        if (existingAccount != null) {
          String state = existingAccount.getString("fstate");
          try {
            state = EncryptHelper.decrypt(state, config.getProperties().getFluorineSecretKey());
            api = BoxAPIConnection.restore(CLIENT_ID, CLIENT_SECRET, state);
            if (needsRefresh(api)) {
              refresh(api);
            }
            String newState = api.save();
            if (!state.equals(newState)) {
              try {
                newState =
                    EncryptHelper.encrypt(newState, config.getProperties().getFluorineSecretKey());
              } catch (Exception e) {
                XRayManager.endSegment(subsegment);
                log.error(e);
                logConnectionIssue(
                    storageType, userId, boxUserId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
                sendError(
                    segment,
                    message,
                    Utils.getLocalizedString(message, "InternalError"),
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    e);
              }
              existingAccount.withString("fstate", newState);
              existingAccount.withLong(
                  Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + 60 * 60 * 1000);
              existingAccount.removeAttribute("refresh_expired");
              ExternalAccounts.saveExternalAccount(
                  existingAccount.getString(Field.FLUORINE_ID.getName()),
                  existingAccount.getString(Field.EXTERNAL_ID_LOWER.getName()),
                  existingAccount);
            }
            sendOK(segment, message, new JsonObject().put("existingBoxId", boxUserId), startTime);
            return;
          } catch (Exception e) {
            XRayManager.endSegment(subsegment);
            log.error(e);
            logConnectionIssue(
                storageType, userId, boxUserId, ConnectionError.CONNECTION_EXCEPTION);
          }
        }
      }
    }

    if (boxUser == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(
          storageType, "ENCAPSULATION_" + userId, authCode, ConnectionError.CONNECTION_EXCEPTION);
      sendError(segment, message, "Box Error: " + box_error, HttpStatus.FORBIDDEN);
      return;
    }
    System.out.println("BOX ecnapsulation connect successful in "
        + (System.currentTimeMillis() - startTime) + " ms");
    sendOK(segment, message);
  }

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String boxId = jsonObject.containsKey(Field.EXTERNAL_ID.getName())
        ? jsonObject.getString(Field.EXTERNAL_ID.getName())
        : emptyString; // authorization code in case of encapsulationMode = 0
    if (boxId == null || boxId.isEmpty()) {
      boxId = findExternalId(segment, message, jsonObject, storageType);
      if (boxId == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return;
      }
    }
    String finalExternalId = boxId;
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      if (folderId != null && folderId.equals(Field.MINUS_1.getName())) {
        folderId = ROOT;
      }
      String currentFolder = folderId;
      if (currentFolder == null) {
        BoxFile.Info file = boxFile(api, fileId).getInfo(Field.PARENT.getName());
        currentFolder = getParent(file, false);
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
              // check that current user is an owner or an editor
              BoxFolder.Info folder =
                  boxFolder(api, finalCurrentFolder).getInfo("owned_by,permissions");
              boolean available = (folder.getOwnedBy() != null
                      && folder.getOwnedBy().getID().equals(finalExternalId))
                  || (folder.getPermissions() != null
                      && folder
                          .getPermissions()
                          .contains(BoxFolder.Permission.CAN_SET_SHARE_ACCESS));
              // check for existing file
              if (available) {
                try {
                  if (finalCurrentFolder.equals(ROOT)) {
                    for (BoxItem.Info info : rootFolder(api).getChildren("id,name")) {
                      if (info instanceof BoxFile.Info) {
                        if (info.getName().equals(filename)) {
                          available = false;
                          break;
                        }
                      }
                    }
                  } else {
                    HttpResponse<String> response = AWSXRayUnirest.get(
                            api.getBaseURL() + "/search", "Box.search")
                        .header(
                            HttpHeaders.AUTHORIZATION.toString(), "Bearer " + api.getAccessToken())
                        .queryString(Field.QUERY.getName(), filename)
                        .queryString("ancestor_folder_ids", finalCurrentFolder)
                        .queryString("fields", Field.ID.getName())
                        .asString();
                    available = response.getStatus() == 200;
                    if (available) {
                      for (Object o : new JsonObject(response.getBody()).getJsonArray("entries")) {
                        JsonObject json = (JsonObject) o;
                        if (json.getString(Field.TYPE.getName()).equals(Field.FILE.getName())) {
                          BoxFile.Info itemInfo =
                              boxFile(api, json.getString(Field.ID.getName())).getFile()
                              .new Info(json.encode());
                          if (finalCurrentFolder.equals(getParent(itemInfo, false))
                              && itemInfo.getName().equals(filename)) {
                            available = false;
                            break;
                          }
                        }
                      }
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
            Map<String, BoxFolder.Info> foldersCache = new HashMap<>();
            Set<String> possibleFolders = new HashSet<>();
            possibleFolders.add(finalCurrentFolder);
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
                  if (ROOT.equals(possibleFolderId)) {
                    continue;
                  }
                  try {
                    BoxFolder.Info obj =
                        boxFolder(api, possibleFolderId).getInfo(Field.PARENT.getName());
                    adds.add(getParent(obj, false));
                    dels.add(possibleFolderId);
                  } catch (Exception exception) {
                    log.error(exception);
                  }
                } else {
                  try {
                    if (possibleFolderId.equals(ROOT)) {
                      for (BoxItem.Info info : rootFolder(api).getChildren("id,name,owned_by")) {
                        if (info instanceof BoxFolder.Info && info.getName().equals(array[i])) {
                          foldersCache.put(info.getID(), (BoxFolder.Info) info);
                          adds.add(info.getID());
                        }
                      }
                    } else {
                      HttpResponse<String> response = AWSXRayUnirest.get(
                              api.getBaseURL() + "/search", "Box.search")
                          .header(
                              HttpHeaders.AUTHORIZATION.toString(),
                              "Bearer " + api.getAccessToken())
                          .queryString(Field.QUERY.getName(), array[i])
                          .queryString("ancestor_folder_ids", possibleFolderId)
                          .queryString("fields", "id,owned_by,name,permissions")
                          .asString();
                      if (response.getStatus() == 200) {
                        JsonArray entries =
                            new JsonObject(response.getBody()).getJsonArray("entries");
                        if (entries != null) {
                          for (Object o : entries) {
                            JsonObject json = (JsonObject) o;
                            if (json.getString(Field.TYPE.getName()).equals(Field.FOLDER.getName())
                                && json.getString(Field.NAME.getName()).equals(array[i])) {
                              foldersCache.put(
                                  json.getString(Field.ID.getName()),
                                  boxFolder(api, json.getString(Field.ID.getName()))
                                      .getFolder()
                                  .new Info(json.encode()));
                              adds.add(json.getString(Field.ID.getName()));
                            }
                          }
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
                BoxFolder.Info possibleFolder = foldersCache.get(possibleFolderId);
                boolean available = (possibleFolder.getOwnedBy() != null
                        && finalExternalId.equals(possibleFolder.getOwnedBy().getID()))
                    || (possibleFolder.getPermissions() != null
                        && possibleFolder
                            .getPermissions()
                            .contains(BoxFolder.Permission.CAN_SET_SHARE_ACCESS));
                if (available) {
                  if (lastStep == -1) {
                    boolean add = true;
                    if (possibleFolderId.equals(ROOT)) {
                      for (BoxItem.Info info :
                          rootFolder(api).getChildren("id,name,modified_at,modified_by,owned_by")) {
                        if (info instanceof BoxFile.Info && info.getName().equals(filename)) {
                          add = false;
                          break;
                        }
                      }
                    } else {
                      HttpResponse<String> response = AWSXRayUnirest.get(
                              api.getBaseURL() + "/search", "Box.search")
                          .header(
                              HttpHeaders.AUTHORIZATION.toString(),
                              "Bearer " + api.getAccessToken())
                          .queryString(Field.QUERY.getName(), filename)
                          .queryString("ancestor_folder_ids", possibleFolderId)
                          .queryString("fields", "id,name,modified_at,modified_by,owned_by")
                          .asString();
                      if (response.getStatus() == 200) {
                        for (Object o :
                            new JsonObject(response.getBody()).getJsonArray("entries")) {
                          JsonObject json = (JsonObject) o;
                          if (json.getString(Field.TYPE.getName()).equals(Field.FILE.getName())
                              && json.getString(Field.NAME.getName()).equals(filename)) {
                            add = false;
                            break;
                          }
                        }
                      } else {
                        add = false;
                      }
                    }
                    if (add) {
                      folders.add(new JsonObject()
                          .put(Field.ID.getName(), possibleFolderId)
                          .put(
                              Field.OWNER.getName(),
                              possibleFolder.getOwnedBy() != null
                                  ? possibleFolder.getOwnedBy().getName()
                                  : emptyString)
                          .put(
                              Field.IS_OWNER.getName(),
                              possibleFolder.getOwnedBy() != null
                                  && possibleFolder.getOwnedBy().getID().equals(finalExternalId))
                          .put(
                              Field.UPDATE_DATE.getName(),
                              possibleFolder.getModifiedAt() != null
                                  ? possibleFolder.getModifiedAt().getTime()
                                  : 0)
                          .put(
                              Field.CHANGER.getName(),
                              possibleFolder.getModifiedBy() != null
                                  ? possibleFolder.getModifiedBy().getName()
                                  : emptyString)
                          .put(
                              Field.CHANGER_ID.getName(),
                              possibleFolder.getModifiedBy() != null
                                  ? possibleFolder.getModifiedBy().getID()
                                  : emptyString)
                          .put(Field.NAME.getName(), possibleFolder.getName())
                          .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                    }
                  } else {
                    folders.add(new JsonObject()
                        .put(Field.ID.getName(), possibleFolderId)
                        .put(
                            Field.OWNER.getName(),
                            possibleFolder.getOwnedBy() != null
                                ? possibleFolder.getOwnedBy().getName()
                                : emptyString)
                        .put(
                            Field.IS_OWNER.getName(),
                            possibleFolder.getOwnedBy() != null
                                && possibleFolder.getOwnedBy().getID().equals(finalExternalId))
                        .put(
                            Field.UPDATE_DATE.getName(),
                            possibleFolder.getModifiedAt() != null
                                ? possibleFolder.getModifiedAt().getTime()
                                : 0)
                        .put(
                            Field.CHANGER.getName(),
                            possibleFolder.getModifiedBy() != null
                                ? possibleFolder.getModifiedBy().getName()
                                : emptyString)
                        .put(
                            Field.CHANGER_ID.getName(),
                            possibleFolder.getModifiedBy() != null
                                ? possibleFolder.getModifiedBy().getID()
                                : emptyString)
                        .put(Field.NAME.getName(), possibleFolder.getName())
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
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    String encapsulationMode = jsonObject.getString("encapsulationMode");
    try {
      BoxAPIConnection api = null;
      Item boxUser = null;
      Iterator<Item> foreignUsers = Collections.emptyIterator();
      if (collaborators.isEmpty()) {
        String externalId = findExternalId(segment, message, jsonObject, storageType);
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          collaborators.add(externalId);
        }
      }
      for (Object externalId : collaborators) {
        if ("0".equals(encapsulationMode)) {
          // as far as authorization code is alive for couple seconds, first try to exchange it
          // to access and refresh tokens
          try {
            api = new BoxAPIConnection(CLIENT_ID, CLIENT_SECRET, (String) externalId);
          } catch (BoxAPIException ex) {
            log.error(ex);
          }
          // save data to the db for future calls
          if (api != null) {
            boxUser = new Item()
                .withPrimaryKey(
                    Field.PK.getName(), "box#" + externalId, Field.SK.getName(), externalId)
                .withString(Field.EXTERNAL_ID_LOWER.getName(), (String) externalId)
                .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());
            String state = api.save();
            try {
              state = EncryptHelper.encrypt(state, config.getProperties().getFluorineSecretKey());
            } catch (Exception ex) {
              log.error(ex);
            }
            boxUser.withString("fstate", state);
            TempData.putBoxUser((String) externalId, boxUser);
          }
          // look for existing access and refresh tokens
          if (api == null) {
            boxUser = TempData.getBoxUserFromCache((String) externalId);
          }
          if (boxUser != null) {
            foreignUsers = Collections.singletonList(boxUser).iterator();
          }
        } else {
          try {
            boxUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
            if (boxUser != null) {
              foreignUsers = Collections.singletonList(boxUser).iterator();
            }
          } catch (Exception e) {
            // todo form Exception in case we don't receive
          }
        }
        while (foreignUsers.hasNext()) {
          Item foreignUser = foreignUsers.next();
          if (api == null) {
            api = connect(foreignUser, encapsulationMode);
          }
          externalId =
              foreignUser.getString(Field.EXTERNAL_ID_LOWER.getName()); // ddidenko todo why?
          if (api != null) {

            if (requestFolderId != null && requestFolderId.equals(Field.MINUS_1.getName())) {
              requestFolderId = ROOT;
            }
            String currentFolder = requestFolderId;
            if (currentFolder == null) {
              BoxFile.Info file = boxFile(api, fileId).getInfo(Field.PARENT.getName());
              currentFolder = getParent(file, false);
            }
            BoxAPIConnection finalApi = api;
            String finalExternalId = (String) externalId;

            // RG: Refactored to not use global collections
            List<String> pathList = path.getList();
            String finalCurrentFolder = currentFolder;
            JsonArray results = new JsonArray(pathList.parallelStream()
                .map(pathStr -> {
                  Entity subSegment =
                      XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
                  if (pathStr == null) {
                    XRayManager.endSegment(subSegment);
                    return null; // continue;
                  }
                  JsonArray pathFiles = new JsonArray();
                  String[] array = Utils.parseRelativePath(pathStr);
                  String filename = array[array.length - 1];
                  Set<String> possibleFolders = new HashSet<>();
                  possibleFolders.add(finalCurrentFolder);
                  if (!(array.length == 1
                      || (array.length == 2 && array[0].trim().isEmpty()))) {
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
                          goUp(finalApi, folderId, adds);
                        } else {
                          goDown(finalApi, folderId, array[i], adds);
                        }
                      }
                      possibleFolders.removeAll(dels);
                      possibleFolders.addAll(adds);
                    }
                  }
                  // check in all possible folders
                  for (String folderId : possibleFolders) {
                    findFileInFolder(finalApi, folderId, filename, pathFiles, finalExternalId);
                  }
                  // check in the root if not found
                  if (pathFiles.isEmpty() && !possibleFolders.contains(finalCurrentFolder)) {
                    findFileInFolder(
                        finalApi, finalCurrentFolder, filename, pathFiles, finalExternalId);
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
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  private void goDown(BoxAPIConnection api, String folderId, String name, Set<String> adds) {
    try {
      Iterable<BoxItem.Info> childrenItems = boxFolder(api, folderId).getChildren("id,parent,name");
      for (BoxItem.Info info : childrenItems) {
        if (info instanceof BoxFolder.Info && info.getName().equals(name)) {
          adds.add(info.getID());
        }
      }
    } catch (Exception e) {
      log.error("Exception on going down in Box", e);
    }
  }

  private void goUp(BoxAPIConnection api, String folderId, Set<String> adds) {
    if (ROOT.equals(folderId)) {
      return;
    }
    try {
      BoxFolder.Info folder = boxFolder(api, folderId).getInfo(Field.PARENT.getName());
      adds.add(getParent(folder, false));
    } catch (Exception e) {
      log.error("Exception on going up in Box", e);
    }
  }

  private void findFileInFolder(
      BoxAPIConnection api,
      String folderId,
      String filename,
      JsonArray pathFiles,
      String externalId) {
    if (folderId == null) {
      return;
    }
    try {
      for (BoxItem.Info info : boxFolder(api, folderId)
          .getChildren("id,modified_at,size,parent,modified_by,name,owned_by")) {
        if (info instanceof BoxFile.Info && info.getName().equals(filename)) {
          pathFiles.add(new JsonObject()
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(storageType, externalId, info.getID()))
              .put(
                  Field.OWNER.getName(),
                  info.getOwnedBy() != null ? info.getOwnedBy().getName() : emptyString)
              .put(
                  Field.IS_OWNER.getName(),
                  info.getOwnedBy() != null && info.getOwnedBy().getID().equals(externalId))
              .put(Field.UPDATE_DATE.getName(), info.getModifiedAt().getTime())
              .put(Field.CHANGER.getName(), info.getModifiedBy().getName())
              .put(Field.CHANGER_ID.getName(), info.getModifiedBy().getID())
              .put(Field.SIZE.getName(), Utils.humanReadableByteCount(info.getSize()))
              .put(Field.SIZE_VALUE.getName(), info.getSize())
              .put(Field.NAME.getName(), filename)
              .put(Field.STORAGE_TYPE.getName(), storageType.name()));
        }
      }
    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in Box", e);
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
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      AWSXRayBoxFile file = boxFile(api, fileId);
      BoxFile.Info fileInfo = file.getInfo();
      BoxUser user = BoxUser.getCurrentUser(api);
      String currentUserLogin = user.getInfo("login").getLogin();
      boolean isSharedWithMe = false;

      if (fileInfo.getParent() == null
          && !fileInfo.getOwnedBy().getLogin().equals(currentUserLogin)) {
        for (BoxCollaboration.Info info : fileInfo.getResource().getAllFileCollaborations()) {
          if (info.getAccessibleBy().getLogin().equals(currentUserLogin)) {
            isSharedWithMe = true;
            break;
          }
        }
      }
      AWSXRayBoxFolder parent;
      if (isSharedWithMe) {
        parent = rootFolder(api);
      } else {
        parent = boxFolder(api, fileInfo.getParent().getID());
      }

      if (hasRights(parent, user, true)) {
        String boxUserId = fileInfo.getOwnedBy().getID();
        List<String> collaboratorsList = new ArrayList<>();
        collaboratorsList.add(boxUserId);
        if (!parent.getID().equals(ROOT) && parent.getCollaborations() != null) {
          for (BoxCollaboration.Info collaboration : parent.getCollaborations()) {
            if (collaboration.getAccessibleBy() != null) {
              collaboratorsList.add(collaboration.getAccessibleBy().getID());
            }
          }
        }
        BoxSharedLink link = fileInfo.getSharedLink();
        try {
          final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
          final String externalEmail = ExternalAccounts.getExternalEmail(userId, externalId);
          PublicLink newLink = super.initializePublicLink(
              fileId,
              externalId,
              userId,
              storageType,
              externalEmail,
              fileInfo.getName(),
              export,
              endTime,
              password);

          if (link != null) {
            newLink.setSharedLink(link.getDownloadURL());
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

      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.FORBIDDEN,
            "FL6");
      }
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private boolean hasRights(AWSXRayBoxFolder folder, @NonNls BoxUser user, boolean isEditor) {
    boolean isOwner = folder.getInfo().getOwnedBy() != null
        && folder.getInfo().getOwnedBy().getLogin().equals(user.getInfo("login").getLogin());
    if (isOwner) {
      return true;
    }
    if (!folder.getID().equals(ROOT) && folder.getCollaborations() != null) {
      for (BoxCollaboration.Info collaboration : folder.getCollaborations()) {
        if (collaboration.getAccessibleBy() != null
            && collaboration.getAccessibleBy().getID().equals(user.getID())) {
          if (!isEditor) {
            return true;
          }
          return BoxCollaboration.Role.EDITOR.equals(collaboration.getRole());
        }
      }
    }
    return false;
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
      BoxAPIConnection api = connect(segment, message, jsonObject, false);

      AWSXRayBoxFile boxFile = boxFile(api, fileIds.getId());
      BoxFile.Info file = boxFile.getInfo("file_version", Field.ID.getName(), Field.NAME.getName());

      message.body().put(Field.NAME.getName(), file.getName());

      String realVersionId = VersionType.parse(versionId).equals(VersionType.LATEST)
          ? file.getVersion().getID()
          : versionId;

      ByteArrayOutputStream stream = new ByteArrayOutputStream();

      if (realVersionId.equals(file.getVersion().getID())) {
        boxFile.download(stream);
      } else {
        for (BoxFileVersion version : boxFile.getVersions()) {
          if (version.getVersionID().equals(versionId)) {
            version.download(stream);
            break;
          }
        }
      }
      stream.close();

      byte[] data = stream.toByteArray();
      stream.close();

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
          file.getName(),
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
      BoxAPIConnection api = connect(segment, message, message.body(), false);
      if (api == null) {
        return;
      }

      BoxFile.Info file = boxFile(api, fileIds.getId())
          .getInfo("file_version", Field.ID.getName(), Field.NAME.getName());

      if (Objects.nonNull(versionId)
          && VersionType.parse(versionId).equals(VersionType.SPECIFIC)
          && boxFile(api, fileIds.getId()).getVersions().stream()
              .noneMatch(version -> version.getVersionID().equals(versionId))) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "VersionWithIdDoesNotExist"), versionId),
            HttpStatus.BAD_REQUEST);
        return;
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.NAME.getName(), file.getName())
              .put(
                  Field.OWNER_IDENTITY.getName(),
                  ExternalAccounts.getExternalEmail(userId, fileIds.getExternalId()))
              .put(Field.COLLABORATORS.getName(), new JsonArray()),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
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
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api;
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(item);
    if (api != null) {
      try {
        AWSXRayBoxFile file = boxFile(api, fileId);
        BoxFile.Info versionInfo = file.getInfo("file_version", "modified_at", "modified_by");
        String versionId = versionInfo.getVersion().getVersionID();
        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType), fileId, versionId);
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
            .put(Field.FILE_NAME.getName(), file.getInfo().getName())
            .put(Field.VERSION_ID.getName(), versionId)
            .put(Field.CREATOR_ID.getName(), creatorId)
            .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
            .put(
                Field.THUMBNAIL.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .put(Field.EXPORT.getName(), export)
            .put(
                Field.UPDATE_DATE.getName(),
                versionInfo.getModifiedAt() != null
                    ? versionInfo.getModifiedAt().getTime()
                    : 0L)
            .put(
                Field.CHANGER.getName(),
                versionInfo.getModifiedBy() != null
                    ? versionInfo.getModifiedBy().getName()
                    : Field.UNKNOWN.getName())
            .put(
                Field.CHANGER_ID.getName(),
                versionInfo.getModifiedBy() != null
                    ? versionInfo.getModifiedBy().getID()
                    : emptyString);
        sendOK(segment, message, json, mills);
        return;
      } catch (BoxAPIException ignored) {
      }
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      BoxFile.Info file = boxFile(api, fileId).getInfo("file_version");
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.VERSION_ID.getName(), file.getVersion().getVersionID()),
          mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      BoxFile.Info file = boxFile(api, fileId).getInfo("file_version", Field.ID.getName());
      String verId = file.getVersion().getID();
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
                          .put(Field.FILE_ID.getName(), file.getID())
                          .put(Field.VERSION_ID.getName(), verId)
                          .put(Field.EXT.getName(), Extensions.getExtension(file.getName()))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          StorageType.getShort(storageType), file.getID(), file.getVersion().getID());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      file.getID(), storageType.name(), verId, true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  @Override
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      BoxAPIConnection api = connect(segment, message, json, false);
      if (api != null) {
        BoxFile.Info fileInfo = boxFile(api, fileId)
            .getInfo(
                Field.PERMISSIONS.getName(),
                "file_version",
                "modified_at",
                "modified_by",
                Field.ID.getName(),
                "owned_by",
                Field.PARENT.getName(),
                "created_at",
                Field.NAME.getName(),
                Field.SIZE.getName(),
                "shared_link");
        return fileInfo != null;
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
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    // For files opened directly from Box we append "+0" at the end
    // Since 08/09/21 we rely on API to provide the correct _id
    // in response to the /info request.
    // Therefore - we need to keep "+0" for such cases
    String encapsulationMode = jsonObject.getString("encapsulationMode");
    boolean isOpenFromBox =
        Utils.isStringNotNullOrEmpty(encapsulationMode) && encapsulationMode.equals("0");
    if (fileId == null && folderId == null) {
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
    long m = System.currentTimeMillis();
    BoxAPIConnection api = connect(segment, message, jsonObject, false);
    System.out.println("Connected to box api in " + (System.currentTimeMillis() - m));
    if (api == null) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
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
      BoxFile.Info file = null;
      if (fileId != null) {
        file = boxFile(api, fileId)
            .getInfo(
                Field.PERMISSIONS.getName(),
                "file_version",
                "modified_at",
                "modified_by",
                Field.ID.getName(),
                "owned_by",
                Field.PARENT.getName(),
                "created_at",
                Field.NAME.getName(),
                Field.SIZE.getName(),
                "shared_link");
        if (file.getParent() != null) {
          folderId = getParent(file, false);
        }
      }
      //      boolean full = true;
      //      if (jsonObject.containsKey(Field.FULL.getName()) &&
      // jsonObject.getString(Field.FULL.getName()) != null) {
      //        full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
      //      }
      JsonObject json;
      m = System.currentTimeMillis();
      if (fileId != null) {
        json = getFileJson(api, file, externalId, true, isAdmin, userId, false, false, false);
        if (isOpenFromBox) {
          json.put(
              Field.ENCAPSULATED_ID.getName(),
              json.getString(Field.ENCAPSULATED_ID.getName()) + "+0");
          // enforce viewOnly for box file opened from Box native
          json.put(Field.VIEW_ONLY.getName(), true);
        }
        System.out.println("Converted box file info in " + (System.currentTimeMillis() - m));
      } else {
        AWSXRayBoxFolder folder = boxFolder(api, folderId);
        json = getFolderJson(
            api,
            folder.getInfo(
                Field.PERMISSIONS.getName(),
                "has_collaborations",
                Field.ID.getName(),
                "owned_by",
                Field.PARENT.getName(),
                "created_at",
                Field.NAME.getName()),
            externalId,
            true);
        System.out.println("Converted box folder info in " + (System.currentTimeMillis() - m));
      }
      System.out.println("doGetInfo " + (System.currentTimeMillis() - mills));
      sendOK(segment, message, json, mills);
    } catch (BoxAPIException e) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doClone(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = message.body().getString(Field.FILE_ID.getName());
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    String name = getRequiredString(
        segment,
        fileId != null ? Field.FILE_NAME_C.getName() : Field.FOLDER_NAME.getName(),
        message,
        message.body());
    boolean doCopyComments = message.body().getBoolean(Field.COPY_COMMENTS.getName(), false);
    boolean doCopyShare = message.body().getBoolean(Field.COPY_SHARE.getName(), false);
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
    String boxId = message.body().getString(Field.EXTERNAL_ID.getName());
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String parent, newId;
      boolean isFile = (fileId != null);
      if (isFile) {
        AWSXRayBoxFile file = boxFile(api, fileId);
        String parentFolderId = getParent(file.getInfo(), false);
        BoxFile.Info newFile = file.copy(boxFolder(api, parentFolderId), name);
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
                    newFile.getID(),
                    doIncludeResolvedComments,
                    doIncludeDeletedComments);
              });
        }
        newId = newFile.getID();
        parent = getEncapsulatedParent(file.getInfo(), boxId);
      } else {
        AWSXRayBoxFolder folder = boxFolder(api, folderId);
        String parentFolderId = getParent(folder.getInfo(), false);
        BoxFolder.Info newFolder = folder.copy(boxFolder(api, parentFolderId), name);
        newId = newFolder.getID();
        parent = getEncapsulatedParent(folder.getInfo(), boxId);
      }
      if (doCopyShare) {
        // copy sharing data
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_SHARE.getName())
            .runWithoutSegment(() -> {
              Collection<BoxCollaboration.Info> collaborations;
              if (isFile) {
                collaborations = boxFile(api, fileId).getCollaborations();
              } else {
                collaborations = boxFolder(api, folderId).getCollaborations();
              }
              List<CollaboratorInfo> collaborators = getItemCollaborators(collaborations);
              message
                  .body()
                  .put(Field.FOLDER_ID.getName(), newId)
                  .put(Field.IS_FOLDER.getName(), !isFile);
              collaborators.forEach(user -> {
                message
                    .body()
                    .put(Field.ROLE.getName(), user.storageAccessRole)
                    .put(Field.EMAIL.getName(), user.email);
                doShare(message, false);
              });
            });
      }
      String newEncapsulatedId = Utils.getEncapsulatedId(storageType, boxId, newId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  fileId != null ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(),
                  newEncapsulatedId)
              .put(Field.PARENT_ID.getName(), parent),
          mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      boxFile(api, fileId).rename(name);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      if (fileId != null) {
        new BoxTrash(api).restoreFile(fileId);
      } else {
        new BoxTrash(api).restoreFolder(folderId);
      }
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (folderId == null || name == null) {
      return;
    }
    Boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName());
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    boolean selfDeShare = name.equals(userId);
    String currentUserId = BoxUser.getCurrentUser(api).getID();
    try {
      Iterator<BoxCollaboration.Info> it;
      if (isFolder) {
        it = boxFolder(api, folderId).getCollaborations().iterator();
      } else {
        it = boxFile(api, folderId).getCollaborations().iterator();
      }

      BoxCollaboration.Info info;
      while (it.hasNext()) {
        info = it.next();
        String id = info.getAccessibleBy() != null ? info.getAccessibleBy().getID() : null;
        if (selfDeShare && currentUserId.equals(id) || name.equals(info.getID())) {
          new BoxCollaboration(api, info.getID()).delete();
          break;
        }
      }
      // deShare is currently not working - Throwing 500 from Box API
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.COLLABORATORS.getName(), new JsonArray().add(name))
              .put(Field.USERNAME.getName(), userId),
          mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doShare(Message<JsonObject> message) {
    doShare(message, true);
  }

  public void doShare(Message<JsonObject> message, boolean reply) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String email = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    String role = getRequiredString(segment, Field.ROLE.getName(), message, message.body());
    if (id == null || email == null || role == null) {
      return;
    }
    Boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName());
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      Iterator<BoxCollaboration.Info> it;
      if (isFolder) {
        it = boxFolder(api, id).getCollaborations().iterator();
        BoxFolder folder = boxFolder(api, id).getFolder();
        if (email.equalsIgnoreCase(folder.getInfo().getOwnedBy().getLogin())) {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "UnableToShareWithOwner"),
                HttpStatus.BAD_REQUEST);
          }
          return;
        }
      } else {
        it = boxFile(api, id).getCollaborations().iterator();
        BoxFile file = boxFile(api, id).getFile();
        if (email.equalsIgnoreCase(file.getInfo().getOwnedBy().getLogin())) {
          if (reply) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "UnableToShareWithOwner"),
                HttpStatus.BAD_REQUEST);
          }
          return;
        }
      }
      BoxCollaboration.Info info;
      BoxCollaboration.Role boxRole = BoxCollaboration.Role.valueOf(role);
      while (it.hasNext()) {
        info = it.next();
        if ((info.getAccessibleBy() != null
                && info.getAccessibleBy() instanceof BoxUser.Info
                && email.equalsIgnoreCase(info.getAccessibleBy().getLogin()))
            || email.equalsIgnoreCase(info.getInviteEmail())) { // for 'unknown'

          info.setRole(boxRole);
          new BoxCollaboration(api, info.getID()).updateInfo(info);
          if (reply) {
            sendOK(segment, message, mills);
          }
          return;
        }
      }
      if (isFolder) {
        boxFolder(api, id).collaborate(email, boxRole);
      } else {
        boxFile(api, id).collaborate(email, boxRole);
      }
      if (reply) {
        sendOK(segment, message, mills);
      }
    } catch (BoxAPIException e) {
      if (reply) {
        sendError(
            segment,
            message,
            e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
            e.getResponseCode() != HttpStatus.NOT_FOUND
                ? HttpStatus.BAD_REQUEST
                : e.getResponseCode(),
            e);
      }
    }
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      for (BoxFileVersion version : boxFile(api, fileId).getVersions()) {
        if (version.getVersionID().equals(versionId)) {
          version.delete();
          break;
        }
        sendOK(segment, message, mills);
      }
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      for (BoxFileVersion version : boxFile(api, fileId).getVersions()) {
        if (version.getVersionID().equals(versionId)) {
          version.promote();
          break;
        }

        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(
                    Field.VERSION_ID.getName(),
                    boxFile(api, fileId).getInfo().getVersion().getVersionID()),
            mills);
      }
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  // Transforms BoxFileVersion into our VersionInfo
  private VersionInfo getVersionInfo(
      String fileId, BoxFileVersion fileVersion, String lastVersionId, String boxUserID) {
    BoxUser.Info modifier = fileVersion.getModifiedBy();
    VersionModifier versionModifier = new VersionModifier();
    if (modifier != null) {
      versionModifier.setPhoto(modifier.getAvatarURL());
      versionModifier.setName(modifier.getName());
      versionModifier.setEmail(modifier.getLogin());
      versionModifier.setId(modifier.getID());
      if (Utils.isStringNotNullOrEmpty(boxUserID)) {
        versionModifier.setCurrentUser(modifier.getID().equals(boxUserID));
      }
    }
    long creationDate = GMTHelper.utcCurrentTime();
    if (fileVersion.getCreatedAt() != null) {
      creationDate = fileVersion.getCreatedAt().getTime();
    }
    VersionInfo versionInfo =
        new VersionInfo(fileVersion.getVersionID(), creationDate, fileVersion.getName());
    versionInfo.setSize(fileVersion.getSize());
    versionInfo.setModifier(versionModifier);
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setDownloadable(true);
    versionPermissions.setCanRename(false);
    versionPermissions.setCanPromote(!fileVersion.getVersionID().equals(lastVersionId));
    versionPermissions.setCanDelete(!fileVersion.getVersionID().equals(lastVersionId));
    versionInfo.setPermissions(versionPermissions);
    try {
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(storageType, fileId, fileVersion.getVersionID());
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    List<JsonObject> result = new ArrayList<>();
    try {
      String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
      String finalVersionId =
          boxFile(api, fileId).getInfo("file_version").getVersion().getVersionID();
      for (BoxFileVersion version : boxFile(api, fileId).getVersions()) {
        result.add(getVersionInfo(fileId, version, finalVersionId, externalId).toJson());
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              String name = boxFile(api, fileId).getInfo(Field.NAME.getName()).getName();
              if (Utils.isStringNotNullOrEmpty(name)) {
                ext = Extensions.getExtension(name);
              }
            } catch (Exception ex) {
              log.warn("[BOX] get versions: Couldn't get object info to get extension.", ex);
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            result.forEach(revision -> requiredVersions.add(new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(Field.VERSION_ID.getName(), revision.getString(Field.ID.getName()))
                .put(Field.EXT.getName(), finalExt)));
            eb_send(
                blockingSegment,
                ThumbnailsManager.address + ".create",
                jsonObject.put(Field.IDS.getName(), requiredVersions));
          });
      result.sort(Comparator.comparing(o -> o.getLong("creationTime")));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() == HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();

    String fileId = body.getString(Field.FILE_ID.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
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
    BoxAPIConnection api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      AWSXRayBoxFile file = boxFile(api, fileId);
      BoxFile.Info fileInfo = file.getInfo();
      file.uploadVersion(stream);
      // let's refresh version info to make sure we get latest info
      BoxFileVersion version = boxFile(api, fileId).getInfo("file_version").getVersion();
      String versionId = version.getVersionID();

      eb_send(
          segment,
          ThumbnailsManager.address + ".create",
          body.put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(
                  Field.IDS.getName(),
                  new JsonArray()
                      .add(new JsonObject()
                          .put(Field.FILE_ID.getName(), fileId)
                          .put(Field.VERSION_ID.getName(), versionId)
                          .put(Field.EXT.getName(), Extensions.getExtension(fileInfo.getName())))));
      sendOK(
          segment,
          message,
          getVersionInfo(fileId, version, versionId, externalId).toJson(),
          mills);

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
    } catch (BoxAPIException ex) {
      sendError(
          segment,
          message,
          ex.getResponse() != null ? ex.getResponse() : ex.getLocalizedMessage(),
          ex.getResponseCode(),
          ex);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doEraseAll(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {

      for (BoxItem.Info info : new BoxTrash(api)) {
        new BoxTrash(api).deleteFile(info.getID());
      }
      sendOK(segment, message, mills);

    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doEraseFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      new BoxTrash(api).deleteFile(fileId);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doDeleteFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      boxFile(api, fileId).delete();
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doMoveFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (fileId == null || parentId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String id = boxFile(api, fileId)
          .move(
              Field.MINUS_1.getName().equals(parentId) ? rootFolder(api) : boxFolder(api, parentId))
          .getID();
      sendOK(segment, message, new JsonObject().put(Field.FILE_ID.getName(), id), mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

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
    String folderId = body.getString(Field.FOLDER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    Boolean isAdmin = body.getBoolean(Field.IS_ADMIN.getName());
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
    BoxAPIConnection api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }

    String responseName = null;
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      String versionId;
      long updateDate = 0;
      if (parsedMessage.hasAnyContent()) {
        if (fileId == null) {
          BoxFile.Info info = (folderId.equals(Field.MINUS_1.getName())
                  ? rootFolder(api)
                  : boxFolder(api, folderId))
              .uploadFile(new FileUploadParams()
                  .setContent(stream)
                  .setName(name)
                  .setSize(
                      parsedMessage.hasByteArrayContent()
                          ? parsedMessage.getContentAsByteArray().length
                          : -1));
          fileId = info.getID();
          versionId = info.getVersion().getVersionID();
          updateDate = info.getModifiedAt().getTime();

        } else if (parsedMessage.getContentAsByteArray().length > 0) {
          AWSXRayBoxFile file = null;
          BoxFile.Info fileInfo = null;
          String originalFileId = fileId;
          try {
            file = boxFile(api, fileId);
            fileInfo = file.getInfo();
          } catch (BoxAPIResponseException ex) {
            if (Utils.isStringNotNullOrEmpty(ex.getResponse())) {
              JsonObject errorResponse = new JsonObject(ex.getResponse());
              if (errorResponse.containsKey(Field.MESSAGE.getName())) {
                String errorMessage = errorResponse.getString(Field.MESSAGE.getName());
                if (errorMessage.equalsIgnoreCase("Not Found")
                    || errorMessage.equalsIgnoreCase("Item is trashed")) {
                  conflictingFileReason =
                      XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
                  isConflictFile = true;
                  fileNotFound = true;
                }
              }
              if (!fileNotFound) {
                sendError(
                    segment,
                    message,
                    ex.getResponse() != null ? ex.getResponse() : ex.getLocalizedMessage(),
                    ex.getResponseCode(),
                    ex);
                return;
              }
            }
          }
          // check if user still has the access to edit this file
          if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            boolean isOwned =
                Objects.nonNull(fileInfo) && fileInfo.getOwnedBy().getID().equals(externalId);
            if (!isOwned
                && Objects.nonNull(file)
                && file.getCollaborations().stream()
                    .noneMatch(col -> (col.getRole().equals(BoxCollaboration.Role.EDITOR)
                            || col.getRole().equals(BoxCollaboration.Role.CO_OWNER))
                        && col.getAccessibleBy().getID().equals(externalId))) {
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
            // get latest version in external storage
            versionId =
                Objects.nonNull(fileInfo) ? fileInfo.getVersion().getVersionID() : emptyString;
            // if latest version in ex storage is unknown,
            // then save a new version as a file with a prefix beside the original file
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
            file.uploadVersion(stream);
            versionId = file.getInfo().getVersion().getVersionID();
          } else {
            String oldName = null, modifier = null, parent = ROOT;
            boolean isSameFolder = false;
            if (!fileNotFound) {
              if (Objects.nonNull(fileInfo)) {
                oldName = fileInfo.getName();
                if (fileInfo.getParent() != null) {
                  parent = getParent(fileInfo, false);
                  isSameFolder = true;
                }
              }
              if (Objects.nonNull(file) && Objects.nonNull(file.getInfo().getModifiedBy())) {
                modifier = file.getInfo().getModifiedBy().getName();
              }
            } else {
              Item metaData = FileMetaData.getMetaData(originalFileId, storageType.name());
              if (Objects.nonNull(metaData)) {
                oldName = metaData.getString(Field.FILE_NAME.getName());
              }
            }
            if (oldName == null) {
              oldName = unknownDrawingName;
            }
            // create a new file and save it beside original one
            String newName = getConflictingFileName(oldName);
            responseName = newName;
            BoxFile.Info newFile = boxFolder(api, parent)
                .uploadFile(new FileUploadParams()
                    .setContent(stream)
                    .setName(newName)
                    .setSize(
                        parsedMessage.hasByteArrayContent()
                            ? parsedMessage.getContentAsByteArray().length
                            : -1));
            fileId = newFile.getID();
            versionId = newFile.getVersion().getVersionID();

            handleConflictingFile(
                segment,
                message,
                body,
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
                AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
          }
        } else {
          AWSXRayBoxFile file = boxFile(api, fileId);
          file.uploadVersion(stream);
          versionId = file.getInfo().getVersion().getVersionID();
        }
      } else {
        versionId = boxFile(api, fileId).getInfo().getVersion().getVersionID();
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
                            .put(Field.FILE_ID.getName(), fileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }
      JsonObject result = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, fileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              StorageType.getShort(storageType) + "_" + fileId + "_" + versionId + ".png");

      if (isConflictFile && Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
        result.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
      }
      if (updateDate != 0) {
        result.put(Field.UPDATE_DATE.getName(), updateDate);
      }
      if (isConflictFile && Utils.isStringNotNullOrEmpty(responseName)) {
        result.put(Field.NAME.getName(), responseName);
      }

      // for file save-as case
      if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
        String finalFileId = fileId;
        cloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
            .getString(Field.FILE_ID.getName());
        String finalCloneFileId = cloneFileId;
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
                  finalFileId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }
      sendOK(segment, message, result, mills);

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
    } catch (BoxAPIException ex) {
      sendError(
          segment,
          message,
          ex.getResponse() != null ? ex.getResponse() : ex.getLocalizedMessage(),
          ex.getResponseCode(),
          ex);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String sharedLink = message.body().getString(Field.SHARED_LINK.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    BoxAPIConnection api;
    boolean success = false;
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    // try to get file using box account
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(item);
    if (api != null) {
      try {
        AWSXRayBoxFile file = boxFile(api, fileId);
        BoxFile.Info boxFile = file.getInfo();
        String versionId = boxFile.getVersion().getVersionID();
        if (returnDownloadUrl) {
          URL downloadUrl = file.getDownloadUrl();
          if (Objects.nonNull(downloadUrl)) {
            sendDownloadUrl(
                segment,
                message,
                downloadUrl.toString(),
                boxFile.getSize(),
                versionId,
                stream,
                mills);
            return;
          }
        }
        String name = boxFile.getName();
        boxFile(api, fileId).download(stream);
        stream.close();
        finishGetFile(
            message, null, null, stream.toByteArray(), storageType, name, versionId, downloadToken);
        success = true;
      } catch (Exception e) {
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      }
    }
    // try to get file using shared link (direct links are available for paid accounts only)
    if (!success && sharedLink != null) {
      if (returnDownloadUrl) {
        sendDownloadUrl(segment, message, sharedLink, null, null, stream, mills);
        return;
      }
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
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    Integer start = message.body().getInteger(Field.START.getName()),
        end = message.body().getInteger(Field.END.getName());
    if (fileId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      String name = null;
      AWSXRayBoxFile file = boxFile(api, fileId);
      if (latest
          || versionId == null
          || file.getInfo().getVersion().getVersionID().equals(versionId)) {
        BoxFile.Info boxFile = file.getInfo();
        if (!Utils.isStringNotNullOrEmpty(versionId)) {
          versionId = boxFile.getVersion().getVersionID();
        }
        if (returnDownloadUrl) {
          URL downloadUrl = file.getDownloadUrl();
          if (Objects.nonNull(downloadUrl)) {
            sendDownloadUrl(
                segment,
                message,
                downloadUrl.toString(),
                boxFile.getSize(),
                versionId,
                stream,
                mills);
            return;
          }
        }
        name = boxFile.getName();
        boxFile(api, fileId).download(stream);
      } else {
        Collection<BoxFileVersion> versions = file.getVersions();
        for (BoxFileVersion version : versions) {
          if (version.getVersionID().equals(versionId)) {
            name = version.getName();
            if (returnDownloadUrl) {
              String downloadUrl =
                  "https://api.box.com/2.0/files/" + fileId + "/content?version=" + versionId;
              sendDownloadUrl(
                  segment,
                  message,
                  downloadUrl,
                  version.getSize(),
                  versionId,
                  api.getAccessToken(),
                  stream,
                  mills);
              return;
            }
            version.download(stream);
            break;
          }
        }
      }
      stream.close();
      finishGetFile(
          message, start, end, stream.toByteArray(), storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (BoxAPIException e) {
      DownloadRequests.setRequestData(
          downloadToken,
          null,
          JobStatus.ERROR,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          null);
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  public void doEraseFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      new BoxTrash(api).deleteFolder(folderId);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doDeleteFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    if (folderId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      boxFolder(api, folderId).delete(true);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      boxFolder(api, folderId).rename(name);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doMoveFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String folderId =
        getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String parentId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (folderId == null || parentId == null) {
      return;
    }
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      AWSXRayBoxFolder folder = boxFolder(api, folderId);
      AWSXRayBoxFolder parentFolder =
          Field.MINUS_1.getName().equals(parentId) ? rootFolder(api) : boxFolder(api, parentId);
      folder.move(parentFolder);
      sendOK(segment, message, mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String parentId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      AWSXRayBoxFolder folder =
          Field.MINUS_1.getName().equals(parentId) ? rootFolder(api) : boxFolder(api, parentId);
      BoxFolder.Info info = folder.createFolder(name);
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(
                      StorageType.getShort(storageType), externalId, info.getID())),
          mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream stream = new ZipOutputStream(bos);
    Set<String> folderNames = new HashSet<>();
    Set<String> fileNames = new HashSet<>();
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
          BoxItem.Info info = (isFolder)
              ? boxFolder(api, objectId).getInfo(Field.NAME.getName())
              : boxFile(api, objectId).getInfo(Field.NAME.getName());
          if (isFolder) {
            String name = info.getName();
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(stream, api, objectId, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = info.getSize();
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, info.getName(), ExcludeReason.Large);
              return null;
            }
            addZipEntry(
                stream,
                api,
                objectId,
                emptyString,
                info.getName(),
                filter,
                filteredFileNames,
                fileNames);
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
    BoxAPIConnection api = connect(segment, message);
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
        Entity subSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "BoxZipFolderSegment");
        try {
          zipFolder(
              stream, api, folderId, filter, recursive, emptyString, new HashSet<>(), 0, request);
        } catch (BoxAPIException e) {
          throw new Exception(e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage());
        }
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

  AWSXRayBoxFolder boxFolder(BoxAPIConnection api, String folderId) {
    return new AWSXRayBoxFolder(new BoxFolder(api, folderId));
  }

  AWSXRayBoxFile boxFile(BoxAPIConnection api, String fileId) {
    return new AWSXRayBoxFile(new BoxFile(api, fileId));
  }

  AWSXRayBoxFolder rootFolder(BoxAPIConnection api) {
    return new AWSXRayBoxFolder(BoxFolder.getRootFolder(api));
  }

  private void zipFolder(
      ZipOutputStream stream,
      BoxAPIConnection api,
      String folderId,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws BoxAPIException, IOException {
    Iterable<BoxItem.Info> folder =
        boxFolder(api, folderId).getChildren(Field.ID.getName(), Field.NAME.getName());
    if (Lists.newArrayList(folder).isEmpty()) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    for (BoxItem.Info itemInfo : folder) {
      String name = itemInfo.getName();
      String properPath = path.isEmpty() ? path : path + File.separator;
      if (itemInfo instanceof BoxFolder.Info && recursive) {
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          zipFolder(
              stream,
              api,
              itemInfo.getID(),
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
      } else if (itemInfo instanceof BoxFile.Info) {
        long fileSize = itemInfo.getSize();
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, itemInfo.getName(), ExcludeReason.Large);
          return;
        }
        addZipEntry(
            stream, api, itemInfo.getID(), properPath, name, filter, filteredFileNames, fileNames);
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      BoxAPIConnection api,
      String objectId,
      String properPath,
      String name,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames)
      throws IOException {
    ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
    boxFile(api, objectId).download(arrayStream);
    arrayStream.close();
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
      stream.write(arrayStream.toByteArray());
      stream.closeEntry();
      stream.flush();
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    String pageToken = jsonObject.getString(Field.PAGE_TOKEN.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    Boolean full = jsonObject.getBoolean(Field.FULL.getName());
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    final String boxUserId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    final int pageLimit = trash ? 20 : 40;
    Pagination pagination = new Pagination(storageType, pageLimit, boxUserId, folderId);
    // get pagination info
    pagination.getPageInfo(pageToken);
    if (full == null) {
      full = true;
    }
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    String p = jsonObject.getString(Field.PAGINATION.getName());
    boolean shouldUsePagination = (!Utils.isStringNotNullOrEmpty(p) || Boolean.parseBoolean(p));
    if (!trash && folderId == null) {
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
    JsonArray thumbnail = new JsonArray();
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }

    JsonObject messageBody = message.body();
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;

    List<JsonObject> foldersJson = new ArrayList<>();
    Map<String, JsonObject> filesJson = new HashMap<>();
    Map<String, String> possibleIdsToIds = new HashMap<>();
    try {
      int offset = 0, limit = pageLimit; // lets reduce limit for India
      long totalAmount = -1;
      if (shouldUsePagination) {
        offset = pagination.getOffset();
        limit = pagination.getLimit();
      }
      Iterable<BoxItem.Info> folder;
      String[] fields = {
        Field.ID.getName(),
        Field.NAME.getName(),
        "created_at",
        "modified_at",
        "modified_by",
        "owned_by",
        Field.PARENT.getName(),
        Field.SIZE.getName(),
        "file_version",
        "has_collaborations",
        Field.PERMISSIONS.getName()
      };
      if (!trash) {
        AWSXRayBoxFolder awsxRayBoxFolder;
        if (Field.MINUS_1.getName().equals(folderId)) {
          awsxRayBoxFolder = rootFolder(api);
        } else {
          awsxRayBoxFolder = boxFolder(api, folderId);
        }
        if (shouldUsePagination) {
          PartialCollection<BoxItem.Info> collection =
              awsxRayBoxFolder.getChildrenRange(offset, limit, fields);
          totalAmount = collection.fullSize();
          folder = collection;
        } else {
          folder = awsxRayBoxFolder.getChildren(fields);
        }
        for (BoxItem.Info itemInfo : folder) {
          if (itemInfo instanceof BoxFile.Info) {
            String filename = itemInfo.getName();
            if (Extensions.isValidExtension(extensions, filename)) {
              boolean createThumbnail = canCreateThumbnails;
              if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                possibleIdsToIds.put(itemInfo.getID(), itemInfo.getID());
                thumbnail.add(new JsonObject()
                    .put(Field.FILE_ID.getName(), itemInfo.getID())
                    .put(
                        Field.VERSION_ID.getName(),
                        ((BoxFile.Info) itemInfo).getVersion().getVersionID())
                    .put(Field.EXT.getName(), Extensions.getExtension(filename)));
              } else {
                createThumbnail = false;
              }
              JsonObject json = getFileJson(
                  api,
                  (BoxFile.Info) itemInfo,
                  boxUserId,
                  false,
                  isAdmin,
                  userId,
                  false,
                  force,
                  createThumbnail);
              filesJson.put(itemInfo.getID(), json);
            }
          } else if (itemInfo instanceof BoxFolder.Info) {
            foldersJson.add(getFolderJson(api, (BoxFolder.Info) itemInfo, boxUserId, false));
          }
        }
      } else {
        JsonArray trashFolder = AWSXRayBoxFolder.getTrashFolder(offset, limit, api);
        for (int i = 0; i < trashFolder.size(); i++) {
          JsonObject json = trashFolder.getJsonObject(i);
          if (json.getString(Field.TYPE.getName()).equals(Field.FOLDER.getName())) {
            BoxFolder.Info trashFolderInfo =
                new BoxTrash(api).getFolderInfo(json.getString(Field.ID.getName()));
            foldersJson.add(getFolderJson(api, trashFolderInfo, boxUserId, false));
          } else if (json.getString(Field.TYPE.getName()).equals(Field.FILE.getName())) {
            BoxFile.Info trashFileInfo =
                new BoxTrash(api).getFileInfo(json.getString(Field.ID.getName()));
            String filename = trashFileInfo.getName();
            if (Extensions.isValidExtension(extensions, filename)) {
              boolean createThumbnail = canCreateThumbnails;
              if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                possibleIdsToIds.put(trashFileInfo.getID(), trashFileInfo.getID());
                thumbnail.add(new JsonObject()
                    .put(Field.FILE_ID.getName(), trashFileInfo.getID())
                    .put(
                        Field.VERSION_ID.getName(), (trashFileInfo).getVersion().getVersionID())
                    .put(Field.EXT.getName(), Extensions.getExtension(filename)));
              } else {
                createThumbnail = false;
              }
              JsonObject fileJson = getFileJson(
                  api,
                  trashFileInfo,
                  boxUserId,
                  false,
                  isAdmin,
                  userId,
                  true,
                  force,
                  createThumbnail);
              filesJson.put(trashFileInfo.getID(), fileJson);
            }
          }
        }
      }
      if (!thumbnail.isEmpty() && canCreateThumbnails) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }

      if (full && !possibleIdsToIds.isEmpty()) {
        Map<String, JsonObject> newSharedLinksResponse =
            PublicLink.getSharedLinks(config, segment, userId, boxUserId, possibleIdsToIds);
        for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
          if (filesJson.containsKey(fileData.getKey())) {
            filesJson.put(
                fileData.getKey(), filesJson.get(fileData.getKey()).mergeIn(fileData.getValue()));
          }
        }
      }

      foldersJson.sort(
          Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
      List<JsonObject> filesList = new ArrayList<>(filesJson.values());
      filesList.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      int size = filesJson.size() + foldersJson.size();
      JsonObject response = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesList))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put(Field.NUMBER.getName(), size)
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));

      // if extensions isn't empty - some filter has been applied.
      if (shouldUsePagination
          && (
          // if totalAmount is received and we know there are more files
          (totalAmount != -1 && offset + limit < totalAmount)
              ||
              // old way - for trash only
              (trash
                  && (!filesJson.isEmpty() || !foldersJson.isEmpty())
                  && (size >= pageLimit || !extensions.isEmpty())))) {
        String nextPageToken = pagination.saveNewPageInfo(pagination.getNextPageToken(), offset);
        response.put(Field.PAGE_TOKEN.getName(), nextPageToken);
      } else {
        response.remove(Field.PAGE_TOKEN.getName());
      }
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
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
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
    BoxAPIConnection api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      JsonArray result = new JsonArray();
      String currentUserId = BoxUser.getCurrentUser(api).getID();
      if (!(id.equals(Field.MINUS_1.getName()) || id.equals(ROOT))) {
        @NonNls AWSXRayBoxFolder f = boxFolder(api, id);
        BoxFolder.Info folder =
            f.getInfo("path_collection", Field.ID.getName(), Field.NAME.getName());
        boolean viewOnly;
        for (BoxFolder.Info info : folder.getPathCollection()) {
          if (ROOT.equals(info.getID())) {
            result.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, currentUserId, Field.MINUS_1.getName()))
                .put(Field.NAME.getName(), "~")
                .put(Field.VIEW_ONLY.getName(), false));
          } else {
            viewOnly = false;
            Collection<BoxCollaboration.Info> collaborations =
                boxFolder(api, info.getID()).getCollaborations();
            if (collaborations != null) {
              for (BoxCollaboration.Info collaboration : collaborations) {
                if (BoxCollaboration.Role.VIEWER.equals(collaboration.getRole())) {
                  if (collaboration.getAccessibleBy() != null
                      && currentUserId.equals(collaboration.getAccessibleBy().getID())) {
                    viewOnly = true;
                    break;
                  }
                }
              }
            }
            result.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, currentUserId, info.getID()))
                .put(Field.NAME.getName(), info.getName())
                .put(Field.VIEW_ONLY.getName(), viewOnly));
          }
        }
        viewOnly = false;
        if (f.getCollaborations() != null) {
          for (BoxCollaboration.Info collaboration : f.getCollaborations()) {
            if (BoxCollaboration.Role.VIEWER.equals(collaboration.getRole())) {
              if (collaboration.getAccessibleBy() != null
                  && currentUserId.equals(collaboration.getAccessibleBy().getID())) {
                viewOnly = true;
                break;
              }
            }
          }
        }
        result.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, currentUserId, folder.getID()))
            .put(Field.NAME.getName(), folder.getName())
            .put(Field.VIEW_ONLY.getName(), viewOnly));
        Collections.reverse(result.getList());
      } else {
        result.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, currentUserId, Field.MINUS_1.getName()))
            .put(Field.NAME.getName(), "~")
            .put(Field.VIEW_ONLY.getName(), false));
      }
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
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

      // RG: Refactored to not use global collection
      JsonArray result = new JsonArray(array.parallelStream()
          .map(boxUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            String state = boxUser.getString("fstate");
            try {
              state = EncryptHelper.decrypt(state, config.getProperties().getFluorineSecretKey());
            } catch (Exception ex) {
              log.error(ex);
              XRayManager.endSegment(subSegment);
              return null;
            }
            BoxAPIConnection api = BoxAPIConnection.restore(CLIENT_ID, CLIENT_SECRET, state);
            BoxUser.Info user = null;
            try {
              if (needsRefresh(api)) {
                refresh(api);
              }
              String newState = api.save();
              if (!state.equals(newState)) {
                try {
                  newState = EncryptHelper.encrypt(
                      newState, config.getProperties().getFluorineSecretKey());
                } catch (Exception e) {
                  sendError(
                      segment,
                      message,
                      Utils.getLocalizedString(message, "InternalError"),
                      HttpStatus.INTERNAL_SERVER_ERROR,
                      e);
                  return null;
                }
                boxUser.withString("fstate", newState);
                boxUser.withLong(
                    Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + 60 * 60 * 1000);
                boxUser.removeAttribute("refresh_expired");
                // todo maybe switch to execute blocking
                ExternalAccounts.saveExternalAccount(
                    boxUser.getString(Field.FLUORINE_ID.getName()),
                    boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                    boxUser);
              }
              user = new BoxUser(api, (boxUser.getString(Field.EXTERNAL_ID_LOWER.getName())))
                  .getInfo(Field.NAME.getName(), "login");
            } catch (BoxAPIException e) {
              log.error("[BOX GLOBAL SEARCH] Connection exception occurred - " + e);
            } catch (Exception ignore) {
            }
            if (user == null) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), boxUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            try {
              BoxSearch boxSearch = new BoxSearch(api);
              BoxSearchParameters searchParams = new BoxSearchParameters();
              searchParams.setQuery(URLEncoder.encode(query, StandardCharsets.UTF_8));
              searchParams.setContentTypes(Collections.singletonList(Field.NAME.getName()));
              searchParams.setFields(Arrays.asList(
                  ("id,name,created_at,modified_at,modified_by,owned_by,parent,size,file_version,"
                          + "has_collaborations,shared_link,permissions")
                      .split(",")));
              // 200 is the max limit according to docs
              PartialCollection<BoxItem.Info> searchResults =
                  boxSearch.searchRange(0, 200, searchParams);

              final String externalId = boxUser.getString(Field.EXTERNAL_ID_LOWER.getName());
              searchResults.forEach(objectInfo -> {
                if (objectInfo.getType().equals(Field.FILE.getName())) {
                  filesJson.add(getFileJson(
                      api,
                      (BoxFile.Info) objectInfo,
                      externalId,
                      false,
                      isAdmin,
                      userId,
                      false,
                      false,
                      false));
                } else {
                  foldersJson.add(
                      getFolderJson(api, (BoxFolder.Info) objectInfo, externalId, false));
                }
              });
            } catch (Exception ex) {
              log.error(ex);
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), boxUser.getString(Field.EMAIL.getName()))
                .put(Field.FILES.getName(), filesJson)
                .put(Field.FOLDERS.getName(), foldersJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void doAddAuthCode(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String graebertId = jsonObject.getString(Field.GRAEBERT_ID.getName());
    String username = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);
    String code = getRequiredString(segment, Field.CODE.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    if (userId == null || code == null || sessionId == null) {
      return;
    }
    BoxAPIConnection api;
    try {
      api = new BoxAPIConnection(CLIENT_ID, CLIENT_SECRET, code);
    } catch (BoxAPIException e) {
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
      return;
    }

    BoxUser.Info boxUser = BoxUser.getCurrentUser(api).getInfo();
    Item externalAccount = ExternalAccounts.getExternalAccount(userId, boxUser.getID());
    if (externalAccount == null) {
      externalAccount = new Item()
          .withPrimaryKey(
              Field.FLUORINE_ID.getName(),
              userId,
              Field.EXTERNAL_ID_LOWER.getName(),
              boxUser.getID())
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
          boxUser.getID(),
          true,
          intercomAccessToken);
    }
    String state = api.save();
    try {
      state = EncryptHelper.encrypt(state, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return;
    }
    externalAccount.withString("fstate", state);
    externalAccount.withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + 60 * 60 * 1000);
    externalAccount.withString(Field.EMAIL.getName(), boxUser.getLogin());
    externalAccount.removeAttribute("refresh_expired");
    try {
      Sessions.updateSessionOnConnect(
          externalAccount, userId, storageType, boxUser.getID(), sessionId);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
      return;
    }
    sendOK(segment, message, mills);
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, message.body(), replyOnOk);
  }

  private BoxAPIConnection connect(Entity segment, Message<JsonObject> message) {
    return connect(segment, message, message.body(), false);
  }

  private <T> BoxAPIConnection connect(
      Entity segment, Message<T> message, JsonObject json, boolean replyOnOk) {
    long startTime = System.currentTimeMillis();
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "BOX.Connect");
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String encapsulationMode = json.getString("encapsulationMode");
    String boxId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString; // authorization code in case of encapsulationMode = 0
    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, null, boxId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (boxId == null || boxId.isEmpty()) {
      boxId = findExternalId(segment, message, json, storageType);
      if (boxId == null) {
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
    BoxAPIConnection api = null;
    Item boxUser = null;
    if ("0".equals(encapsulationMode)) {
      // as far as authorization code is alive for couple seconds, first try to exchange it to
      // access and refresh tokens
      String box_error = emptyString;
      try {
        api = new BoxAPIConnection(CLIENT_ID, CLIENT_SECRET, boxId);
      } catch (BoxAPIException e) {
        box_error = e.getMessage();
        log.error(e);
        logConnectionIssue(
            storageType, "ENCAPSULATION_" + userId, boxId, ConnectionError.CONNECTION_EXCEPTION);
      }
      // save data to the db for future calls
      if (api != null) {
        boxUser = new Item()
            .withPrimaryKey(Field.PK.getName(), "box#" + boxId, Field.SK.getName(), boxId)
            .withString(Field.EXTERNAL_ID_LOWER.getName(), boxId)
            .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());
        String state = api.save();
        try {
          state = EncryptHelper.encrypt(state, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          log.error(e);
          logConnectionIssue(
              storageType, "ENCAPSULATION_" + userId, boxId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
        }
        boxUser.withString("fstate", state);
        TempData.putBoxUser(boxId, boxUser);
      }
      // look for existing access and refresh tokens
      if (api == null) {
        boxUser = TempData.getBoxUserFromCache(boxId);
      }

      if (boxUser == null) {
        boxUser = ExternalAccounts.getExternalAccount(userId, boxId);
      }

      if (boxUser == null) {
        XRayManager.endSegment(subsegment);
        logConnectionIssue(
            storageType, "ENCAPSULATION_" + userId, boxId, ConnectionError.CONNECTION_EXCEPTION);
        sendError(segment, message, "Box Error: " + box_error, HttpStatus.FORBIDDEN);
        return null;
      }
    } else {
      boxUser = ExternalAccounts.getExternalAccount(userId, boxId);
    }
    if (boxUser == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, userId, boxId, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisBoxAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    json.put(
        "isAccountThumbnailDisabled",
        boxUser.hasAttribute("disableThumbnail") && boxUser.getBoolean("disableThumbnail"));
    String boxUserId = boxUser.getString(Field.EXTERNAL_ID_LOWER.getName());
    String state = boxUser.getString("fstate");
    try {
      state = EncryptHelper.decrypt(state, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      XRayManager.endSegment(subsegment);
      log.error(e);
      logConnectionIssue(storageType, userId, boxId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    try {
      if (api == null) {
        api = BoxAPIConnection.restore(CLIENT_ID, CLIENT_SECRET, state);
      }
      if (needsRefresh(api)) {
        refresh(api);
      }
      String newState = api.save();
      if (!state.equals(newState)) {
        try {
          newState = EncryptHelper.encrypt(newState, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          XRayManager.endSegment(subsegment);
          log.error(e);
          logConnectionIssue(storageType, userId, boxId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return null;
        }
        boxUser.withString("fstate", newState);
        boxUser.withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + 60 * 60 * 1000);
        boxUser.removeAttribute("refresh_expired");
        if ("0".equals(encapsulationMode)) {
          TempData.putBoxUser(boxId, boxUser);
        } else {
          ExternalAccounts.saveExternalAccount(
              boxUser.getString(Field.FLUORINE_ID.getName()),
              boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
              boxUser);
        }
      }
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment);
      log.error(e);
      logConnectionIssue(storageType, userId, boxId, ConnectionError.CONNECTION_EXCEPTION);
      sendError(
          segment,
          message,
          e.getResponse() != null ? e.getResponse() : e.getLocalizedMessage(),
          e.getResponseCode() != HttpStatus.NOT_FOUND
              ? HttpStatus.BAD_REQUEST
              : e.getResponseCode(),
          e);
      return null;
    } catch (Exception e) {
      XRayManager.endSegment(subsegment);
      log.error(e);
      logConnectionIssue(storageType, userId, boxId, ConnectionError.CONNECTION_EXCEPTION);
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
      return null;
    }
    json.put("box_user_id", boxUserId);
    try {
      json.put("boxUploadIp", InetAddress.getByName("upload.box.com").getHostAddress());
      json.put("boxApiIp", InetAddress.getByName("api.box.com").getHostAddress());
    } catch (UnknownHostException ignore) {
    }
    XRayManager.endSegment(subsegment);
    if (replyOnOk) {
      sendOK(segment, message);
    }
    //        api.setBaseURL("https://api-test.box.com/2.0/"); // todo checking tls 1.1.
    recordExecutionTime("connectBox", System.currentTimeMillis() - startTime);
    return api;
  }

  private BoxAPIConnection connect(Item boxUser) {
    return connect(boxUser, Field.MINUS_1.getName());
  }

  private BoxAPIConnection connect(Item boxUser, String encapsulationMode) {
    if (boxUser == null) {
      return null;
    }
    String state = boxUser.getString("fstate");
    try {
      state = EncryptHelper.decrypt(state, config.getProperties().getFluorineSecretKey());
    } catch (Exception ex) {
      log.error(ex);
      return null;
    }
    BoxAPIConnection api;
    try {
      api = BoxAPIConnection.restore(CLIENT_ID, CLIENT_SECRET, state);
      if (needsRefresh(api)) {
        refresh(api);
      }
      String newState = api.save();
      try {
        newState = EncryptHelper.encrypt(newState, config.getProperties().getFluorineSecretKey());
      } catch (Exception ex) {
        log.error(ex);
        return null;
      }
      if (!state.equals(newState)) {
        boxUser.withString("fstate", newState);
        boxUser.withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + 60 * 60 * 1000);
        boxUser.removeAttribute("refresh_expired");
        if ("0".equals(encapsulationMode)) {
          TempData.putBoxUser(boxUser);
        } else {
          ExternalAccounts.saveExternalAccount(
              boxUser.getString(Field.FLUORINE_ID.getName()),
              boxUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
              boxUser);
        }
      }
    } catch (BoxAPIException e) {
      log.error("[BOX CONNECT] Connection exception occurred - " + e);
      return null;
    } catch (Exception e) {
      return null;
    }
    //        api.setBaseURL("https://api-test.box.com/2.0/"); // todo checking tls 1.1
    return api;
  }

  private String getParent(BoxItem.Info info, boolean replaceRoot) {
    String parentFolder = info.getParent() != null ? info.getParent().getID() : ROOT;
    if (replaceRoot && parentFolder.equals(ROOT)) {
      parentFolder = Field.MINUS_1.getName();
    }

    return parentFolder;
  }

  private String getEncapsulatedParent(BoxItem.Info info, String externalId) {
    return Utils.getEncapsulatedId(
        StorageType.getShort(storageType), externalId, getParent(info, true));
  }

  private JsonObject getFileJson(
      BoxAPIConnection api,
      BoxFile.Info fileInfo,
      String boxUserId,
      boolean addShare,
      boolean isAdmin,
      String userId,
      boolean isTrash,
      boolean force,
      boolean canCreateThumbnails) {
    boolean viewOnly = !(fileInfo.getPermissions() != null
        && fileInfo.getPermissions().contains(BoxFile.Permission.CAN_SET_SHARE_ACCESS));
    String verId = emptyString;
    if (fileInfo.getVersion() != null && fileInfo.getVersion().getVersionID() != null) {
      verId = fileInfo.getVersion().getVersionID();
    }

    long updateDate =
        fileInfo.getModifiedAt() != null ? fileInfo.getModifiedAt().getTime() : 0;
    String changer = fileInfo.getModifiedBy() != null
        ? fileInfo.getModifiedBy().getName()
        : Field.UNKNOWN.getName();
    String changerId =
        fileInfo.getModifiedBy() != null ? fileInfo.getModifiedBy().getID() : emptyString;
    boolean shared = false;
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    try {
      if (addShare) {
        Collection<BoxCollaboration.Info> collaborations =
            boxFile(api, fileInfo.getID()).getCollaborations();
        shared = !collaborations.isEmpty();
        List<CollaboratorInfo> collaboratorInfoList = getItemCollaborators(collaborations);
        Collaborators collaborators = new Collaborators(collaboratorInfoList);
        editor = Collaborators.toJsonArray(collaborators.editor);
        viewer = Collaborators.toJsonArray(collaborators.viewer);
      }
    } catch (BoxAPIException ignore) {
    }
    // if file is public
    boolean externalPublic = true;
    // fileInfo.getSharedLink() != null;
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (addShare) {
      PLData = findLinkForFile(fileInfo.getID(), boxUserId, userId, viewOnly);
    }

    String thumbnailName = ThumbnailsManager.getThumbnailName(
        StorageType.getShort(storageType),
        fileInfo.getID(),
        fileInfo.getVersion().getID());

    boolean isOwner =
        fileInfo.getOwnedBy() != null && fileInfo.getOwnedBy().getID().equals(boxUserId);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), boxUserId, fileInfo.getID()))
        .put(Field.WS_ID.getName(), fileInfo.getID())
        .put(Field.FILE_NAME.getName(), fileInfo.getName())
        .put(Field.FOLDER_ID.getName(), getEncapsulatedParent(fileInfo, boxUserId))
        .put(
            Field.OWNER.getName(),
            fileInfo.getOwnedBy() != null
                ? fileInfo.getOwnedBy().getName()
                : Field.UNKNOWN.getName())
        .put(
            Field.OWNER_EMAIL.getName(),
            fileInfo.getOwnedBy() != null ? fileInfo.getOwnedBy().getLogin() : emptyString)
        .put(
            Field.CREATION_DATE.getName(),
            fileInfo.getCreatedAt() != null ? fileInfo.getCreatedAt().getTime() : 0)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), changer)
        .put(Field.CHANGER_ID.getName(), changerId)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(fileInfo.getSize()))
        .put(Field.SIZE_VALUE.getName(), fileInfo.getSize())
        .put(Field.SHARED.getName(), shared)
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(Field.CAN_MOVE.getName(), isOwner)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.EDITOR.getName(), editor)
                .put(Field.VIEWER.getName(), viewer))
        .put(Field.EXTERNAL_PUBLIC.getName(), externalPublic)
        .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()))
        .put(Field.PREVIEW_ID.getName(), StorageType.getShort(storageType) + "_" + fileInfo.getID())
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName);

    if (Extensions.isThumbnailExt(config, fileInfo.getName(), isAdmin)) {
      // AS : Removing this temporarily until we have some server cache (WB-1248)
      //      .put("thumbnailStatus",
      //              ThumbnailsManager.getThumbnailStatus(fileInfo.getID(), storageType.name(),
      //              verId,
      //                  force,
      //                  canCreateThumbnails))
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
                  ThumbnailsManager.getPreviewName(
                      StorageType.getShort(storageType), fileInfo.getID()),
                  true));
    }
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
    ObjectPermissions permissions = new ObjectPermissions().setAllTo(true);
    if (fileInfo.getPermissions() != null) {
      EnumSet<BoxFile.Permission> boxPermissions = fileInfo.getPermissions();
      permissions
          .setPermissionAccess(
              AccessType.canManagePublicLink,
              !isTrash && boxPermissions.contains(BoxFile.Permission.CAN_SET_SHARE_ACCESS))
          .setPermissionAccess(
              AccessType.canManagePermissions,
              !isTrash && boxPermissions.contains(BoxFile.Permission.CAN_SHARE))
          .setPermissionAccess(
              AccessType.canDownload, boxPermissions.contains(BoxFile.Permission.CAN_DOWNLOAD))
          .setPermissionAccess(
              AccessType.canDelete, boxPermissions.contains(BoxFile.Permission.CAN_DELETE))
          .setPermissionAccess(
              AccessType.canRename, boxPermissions.contains(BoxFile.Permission.CAN_RENAME));
    } else {
      permissions
          .setPermissionAccess(AccessType.canManagePublicLink, !isTrash && !viewOnly)
          .setPermissionAccess(AccessType.canManagePermissions, !isTrash && (!viewOnly || isOwner));
    }
    permissions
        .setPermissionAccess(AccessType.canViewPermissions, !isTrash)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isTrash)
        .setBatchTo(
            List.of(
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false);
    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  private JsonObject getFolderJson(
      BoxAPIConnection api, BoxFolder.Info folderInfo, String externalId, boolean full) {
    boolean viewOnly = !(folderInfo.getPermissions() != null
        && folderInfo.getPermissions().contains(BoxFolder.Permission.CAN_SET_SHARE_ACCESS));
    boolean shared = folderInfo.getHasCollaborations(); // false;
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    try {
      if (full) {
        Collection<BoxCollaboration.Info> collaborations =
            boxFolder(api, folderInfo.getID()).getCollaborations();
        shared = !collaborations.isEmpty();
        List<CollaboratorInfo> collaboratorInfoList = getItemCollaborators(collaborations);
        Collaborators collaborators = new Collaborators(collaboratorInfoList);
        editor = Collaborators.toJsonArray(collaborators.editor);
        viewer = Collaborators.toJsonArray(collaborators.viewer);
      }
    } catch (BoxAPIException ignore) {
    }

    boolean isOwner =
        folderInfo.getOwnedBy() != null && folderInfo.getOwnedBy().getID().equals(externalId);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, folderInfo.getID()))
        .put(Field.NAME.getName(), folderInfo.getName())
        .put(
            Field.UPDATE_DATE.getName(),
            folderInfo.getModifiedAt() != null ? folderInfo.getModifiedAt().getTime() : 0)
        .put(Field.PARENT.getName(), getEncapsulatedParent(folderInfo, externalId))
        .put(
            Field.OWNER.getName(),
            folderInfo.getOwnedBy() != null
                ? folderInfo.getOwnedBy().getName()
                : Field.UNKNOWN.getName())
        .put(
            Field.OWNER_EMAIL.getName(),
            folderInfo.getOwnedBy() != null ? folderInfo.getOwnedBy().getLogin() : emptyString)
        .put(
            Field.CREATION_DATE.getName(),
            folderInfo.getCreatedAt() != null ? folderInfo.getCreatedAt().getTime() : 0)
        .put(Field.SHARED.getName(), shared)
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor));

    ObjectPermissions permissions = new ObjectPermissions().setAllTo(true);
    boolean isTrash = (folderInfo.getTrashedAt() != null);
    if (folderInfo.getPermissions() != null) {
      EnumSet<BoxFolder.Permission> boxPermissions = folderInfo.getPermissions();
      permissions
          .setPermissionAccess(
              AccessType.canManagePermissions,
              !isTrash && boxPermissions.contains(BoxFolder.Permission.CAN_INVITE_COLLABORATOR))
          .setPermissionAccess(
              AccessType.canDownload, boxPermissions.contains(BoxFolder.Permission.CAN_DOWNLOAD))
          .setPermissionAccess(
              AccessType.canDelete, boxPermissions.contains(BoxFolder.Permission.CAN_DELETE))
          .setPermissionAccess(
              AccessType.canRename, boxPermissions.contains(BoxFolder.Permission.CAN_RENAME));
    } else {
      permissions.setPermissionAccess(
          AccessType.canManagePermissions, !isTrash && (!viewOnly || isOwner));
    }
    permissions
        .setPermissionAccess(AccessType.canViewPermissions, !isTrash)
        .setBatchTo(List.of(AccessType.canViewPublicLink, AccessType.canManagePublicLink), false);

    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  private static List<CollaboratorInfo> getItemCollaborators(
      Collection<BoxCollaboration.Info> collaborations) {
    List<CollaboratorInfo> collaborators = new ArrayList<>();
    for (BoxCollaboration.Info collaboration : collaborations) {
      BoxCollaboration.Role boxRole = collaboration.getRole();
      String name = collaboration.getAccessibleBy() != null
          ? collaboration.getAccessibleBy().getName()
          : Field.UNKNOWN.getName();
      String email = collaboration.getAccessibleBy() != null
          ? collaboration.getAccessibleBy().getLogin()
          : Field.UNKNOWN.getName();
      Role role = BoxCollaboration.Role.VIEWER.equals(boxRole) ? Role.VIEWER : Role.EDITOR;
      CollaboratorInfo info =
          new CollaboratorInfo(collaboration.getID(), name, email, null, boxRole.name(), role);
      collaborators.add(info);
    }
    return collaborators;
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    if (fileId == null) {
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

    BoxAPIConnection api = connect(segment, message, jsonObject, false);
    // System.out.println("Connected to box api in " + (System.currentTimeMillis() - m));
    if (api == null) {
      return;
    }
    try {
      boxFile(api, fileId)
          .getInfo(
              Field.PERMISSIONS.getName(),
              "file_version",
              "modified_at",
              "modified_by",
              Field.ID.getName(),
              "owned_by",
              Field.PARENT.getName(),
              "created_at",
              Field.NAME.getName(),
              Field.SIZE.getName(),
              "shared_link");

      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), false), mills);
    } catch (BoxAPIException e) {
      // Box returns response->code=trashed for trashed files so we fall into this part
      // In other cases - we also can return true, because it is used for RF validation only
      // https://graebert.atlassian.net/browse/XENON-30048
      // should be changed if this function is required somewhere else in the future.
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put(
                  "nativeResponse",
                  Objects.nonNull(e.getResponse())
                      ? new JsonObject(e.getResponse())
                      : new JsonObject()),
          mills);
    }
  }
}
