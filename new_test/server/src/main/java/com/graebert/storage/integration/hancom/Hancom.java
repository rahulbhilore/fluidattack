package com.graebert.storage.integration.hancom;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.Entities.Versions.VersionInfo;
import com.graebert.storage.Entities.Versions.VersionModifier;
import com.graebert.storage.Entities.Versions.VersionPermissions;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.Storage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.hancom.objects.BaseObject;
import com.graebert.storage.integration.hancom.objects.File;
import com.graebert.storage.integration.hancom.objects.Folder;
import com.graebert.storage.integration.hancom.objects.SharedObject;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Paths;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class Hancom extends BaseStorage implements Storage {
  private OperationGroup operationGroup;
  public static final String addressProd = "hancom";
  public static final String addressStg = "hancomstg";
  private static final Logger log = LogManager.getRootLogger();
  private static S3Regional s3Regional = null;
  private final String[] specialCharacters = {}; // TODO: find proper chars
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  ;
  private StorageType storageType = StorageType.HANCOM;
  private HancomAPI apiWrapper;

  public Hancom() {}

  @Override
  public void start() throws Exception {
    super.start();
    String address = addressProd;
    operationGroup = OperationGroup.HANCOM;
    String apiURL;
    String port;
    JsonObject proxy;
    if (config.getProperties().getHancomstg()) {
      address = addressStg;
      operationGroup = OperationGroup.HANCOMSTG;
      apiURL = "https://stg-api.malangmalang.com/space/mobile";
      storageType = StorageType.HANCOMSTG;
      port = config.getProperties().getHancomstgProxyPort();
      proxy = new JsonObject()
          .put(Field.URL.getName(), config.getProperties().getHancomstgProxyUrl())
          .put("port", Integer.parseInt(!port.isEmpty() ? port : "0"))
          .put("login", config.getProperties().getHancomstgProxyLogin())
          .put("pass", config.getProperties().getHancomstgProxyPass());
    } else {
      apiURL = "https://api.malangmalang.com/space/mobile";
      port = config.getProperties().getHancomProxyPort();
      proxy = new JsonObject()
          .put(Field.URL.getName(), config.getProperties().getHancomProxyUrl())
          .put("port", Integer.parseInt(!port.isEmpty() ? port : "0"))
          .put("login", config.getProperties().getHancomProxyLogin())
          .put("pass", config.getProperties().getHancomProxyPass());
    }
    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> event) -> doGetFolderContent(
            event, false)); // execute(event, "doGetFolderContent", new Class[]{boolean.class}, new
    // Object[]{false})
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
        address + ".getTrash", (Message<JsonObject> event) -> doGetFolderContent(event, true));
    eb.consumer(address + ".share", this::doShare);
    eb.consumer(address + ".deShare", this::doDeShare);
    eb.consumer(address + ".restore", this::doRestore);
    eb.consumer(address + ".getInfo", this::doGetInfo);
    eb.consumer(address + ".getThumbnail", this::doGetThumbnail);
    eb.consumer(address + ".doGetInfoByToken", this::doGetInfoByToken);
    eb.consumer(address + ".getFolderPath", this::doGetFolderPath);
    eb.consumer(address + ".eraseFile", this::doEraseFile);
    eb.consumer(address + ".eraseFolder", this::doEraseFolder);
    eb.consumer(address + ".connect", (Message<JsonObject> event) -> connect(event, true));
    eb.consumer(address + ".createSharedLink", this::doCreateSharedLink);
    eb.consumer(address + ".deleteSharedLink", this::doDeleteSharedLink);
    eb.consumer(address + ".requestFolderZip", this::doRequestFolderZip);
    eb.consumer(address + ".globalSearch", this::doGlobalSearch);
    eb.consumer(address + ".findXRef", this::doFindXRef);
    eb.consumer(address + ".checkPath", this::doCheckPath);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
    apiWrapper = new HancomAPI(apiURL, proxy);
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-hancom");
  }

  @Override
  public void stop() {
    apiWrapper.shutdown();
  }

  @Override
  public void doGetVersionByToken(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doCheckFileVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  private <T> void handleException(Entity segment, Message<T> message, Exception e) {
    e.printStackTrace();
    if (e instanceof HancomException) {
      HancomException hancomException = (HancomException) e;
      sendError(segment, message, hancomException.toJson(), hancomException.getStatusCode());
      return;
    }
    sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
  }

  @Override
  public void doAddAuthCode(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String graebertId = (jsonObject).getString(Field.GRAEBERT_ID.getName());
    String username = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);
    String hancomSessionId = getRequiredString(segment, Field.CODE.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    if (userId == null || hancomSessionId == null || sessionId == null) {
      return;
    }

    try {
      JsonObject userInfo =
          apiWrapper.getUserInfo(hancomSessionId).getJsonObject(Field.DATA.getName());
      String accountId = String.valueOf(userInfo.getInteger("user_id"));
      Item externalAccount = ExternalAccounts.getExternalAccount(userId, accountId);
      if (externalAccount == null) {
        externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(), userId, Field.EXTERNAL_ID_LOWER.getName(), accountId)
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
            accountId,
            true,
            intercomAccessToken);
      }
      try {
        hancomSessionId =
            EncryptHelper.encrypt(hancomSessionId, config.getProperties().getFluorineSecretKey());
      } catch (Exception e) {
        e.printStackTrace();
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InternalError"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            e);
        return;
      }
      externalAccount
          .withString(Field.ACCESS_TOKEN.getName(), hancomSessionId)
          .withString(Field.EMAIL.getName(), userInfo.getString("user_email"))
          .withString("foreign_user", userInfo.getString("user_name"));
      Sessions.updateSessionOnConnect(externalAccount, userId, storageType, accountId, sessionId);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  private String getSessionToken(final String userId, final String externalId) {
    if (Utils.isStringNotNullOrEmpty(userId) && Utils.isStringNotNullOrEmpty(externalId)) {
      Item account = ExternalAccounts.getExternalAccount(userId, externalId);
      if (account != null) {
        String accessToken = account.getString(Field.ACCESS_TOKEN.getName());
        return parseSessionToken(accessToken, userId, externalId);
      }
    }
    return emptyString;
  }

  private String parseSessionToken(String accessToken, String userId, String externalId) {
    try {
      return EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      e.printStackTrace();
      log.error(e);
      logConnectionIssue(storageType, userId, externalId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      return null;
    }
  }

  private String getExternalEmail(final String userId, final String externalId) {
    return ExternalAccounts.getExternalAccount(userId, externalId).getString(Field.EMAIL.getName());
  }

  private <T> String getSessionToken(Message<T> message, JsonObject jsonObject) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "getSessionToken");
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String regularToken = getSessionToken(userId, externalId);
    if (Utils.isStringNotNullOrEmpty(regularToken)) {
      XRayManager.endSegment(subsegment);
      return regularToken;
    } else {
      externalId = findExternalId(subsegment, message, jsonObject, storageType);
      XRayManager.endSegment(subsegment);
      if (Utils.isStringNotNullOrEmpty(externalId)) {
        return getSessionToken(userId, externalId);
      }
      return emptyString;
    }
  }

  private <T> String getSessionToken(Message<T> message) {
    return getSessionToken(message, MessageUtils.parse(message).getJsonObject());
  }

  @Override
  public void doGetFolderContent(Message<JsonObject> message, boolean isTrash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    Boolean full = jsonObject.getBoolean(Field.FULL.getName());
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    if (full == null) {
      full = true;
    }
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
    // TODO: Pagination
    // API supports start (offset in # of pages) and count (limit in # of pages)
    //        String pageToken = jsonObject.getString(Field.PAGE_TOKEN.getName());
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
    messageBody.put("isAccountThumbnailDisabled", apiWrapper.isAccountThumbnailDisabled());
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;
    JsonArray thumbnail = new JsonArray();
    if (folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "PathMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    List<JsonObject> foldersJson = new ArrayList<>();
    List<JsonObject> filesJson = new ArrayList<>();
    try {
      folderId = BaseObject.normalizeObjectId(folderId);
      JsonObject folderContent;
      JsonArray sharedItems = null;
      if (isTrash) {
        folderContent = apiWrapper.listTrashContent(getSessionToken(message));
      } else {
        folderContent = apiWrapper.listFolderContent(getSessionToken(message), folderId);
        if (folderId.equals(HancomAPI.ROOT_FOLDER_ID)) {
          JsonObject sharedObjectsList = apiWrapper.listSharedObjects(getSessionToken(message));
          if (sharedObjectsList != null && sharedObjectsList.containsKey("list")) {
            sharedItems = sharedObjectsList.getJsonArray("list");
          }
        }
      }
      JsonArray receivedItems = folderContent.containsKey(Field.DATA.getName())
          ? folderContent.getJsonArray(Field.DATA.getName())
          : null;
      if (receivedItems == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND);
        return;
      }
      String finalFolderId = folderId;
      receivedItems.forEach(meta -> {
        JsonObject objectInfo = (JsonObject) meta;
        if (BaseObject.isFolder(objectInfo)) {
          foldersJson.add(
              new Folder(objectInfo, finalFolderId, isTrash).toJson(externalId, storageType));
        } else {
          File file = new File(objectInfo, finalFolderId, isTrash);
          file.checkOwnerEmailAgainstUser(getExternalEmail(userId, externalId));
          if (Extensions.isValidExtension(extensions, file.getName())) {
            boolean createThumbnail = canCreateThumbnails;
            if (Utils.isStringNotNullOrEmpty(file.getId())
                && Extensions.isThumbnailExt(config, file.getName(), isAdmin)) {
              thumbnail.add(new JsonObject()
                  .put(Field.FILE_ID.getName(), file.getId())
                  .put(Field.VERSION_ID.getName(), file.getVersionId())
                  .put(Field.EXT.getName(), Extensions.getExtension(file.getName())));
            } else {
              createThumbnail = false;
            }
            filesJson.add(file.toJson(
                externalId,
                config,
                isAdmin,
                s3Regional,
                userId,
                storageType,
                force,
                createThumbnail));
          }
        }
      });
      if (sharedItems != null) {
        sharedItems.forEach(sharedMeta -> {
          JsonObject objectInfo = (JsonObject) sharedMeta;
          SharedObject sharedObject = new SharedObject(objectInfo);
          if (sharedObject.isFolder()) {
            foldersJson.add(sharedObject.toJson(
                externalId, config, isAdmin, s3Regional, userId, storageType, false, false));
          } else {
            if (Extensions.isValidExtension(extensions, sharedObject.getName())) {
              sharedObject.checkOwnerEmailAgainstUser(getExternalEmail(userId, externalId));
              boolean createThumbnail = canCreateThumbnails;
              if (Utils.isStringNotNullOrEmpty(sharedObject.getId())
                  && Extensions.isThumbnailExt(config, sharedObject.getName(), isAdmin)) {
                thumbnail.add(new JsonObject()
                    .put(Field.FILE_ID.getName(), sharedObject.getId())
                    .put(Field.VERSION_ID.getName(), String.valueOf(sharedObject.getVersionId()))
                    .put(Field.EXT.getName(), Extensions.getExtension(sharedObject.getName())));
              } else {
                createThumbnail = false;
              }
              filesJson.add(sharedObject.toJson(
                  externalId,
                  config,
                  isAdmin,
                  s3Regional,
                  userId,
                  storageType,
                  force,
                  createThumbnail));
            }
          }
        });
      }
      if (canCreateThumbnails && !thumbnail.isEmpty()) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }

      foldersJson.sort(
          Comparator.comparing(o2 -> o2.getString(Field.NAME.getName()).toLowerCase()));

      filesJson.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject response = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesJson))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson))
                  .put(Field.DELETED.getName(), new JsonArray()))
          .put(Field.NUMBER.getName(), filesJson.size() + foldersJson.size())
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      sendOK(segment, message, response, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, message.body(), replyOnOk);
  }

  public <T> String connect(
      Entity segment, Message<T> message, JsonObject json, Boolean replyOnOk) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "HANCOM.Connect");

    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String hancomId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, null, hancomId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (hancomId == null || hancomId.isEmpty()) {
      hancomId = findExternalId(segment, message, json, storageType);
      if (hancomId == null) {
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
    Item hancomAccount = ExternalAccounts.getExternalAccount(userId, hancomId);
    if (hancomAccount == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, userId, null, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisDropboxAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    apiWrapper.updateAccountThumbnailAccess(hancomAccount.hasAttribute("disableThumbnail")
        && hancomAccount.getBoolean("disableThumbnail"));
    String authCode = hancomAccount.getString(Field.ACCESS_TOKEN.getName());
    try {
      authCode = EncryptHelper.decrypt(authCode, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      e.printStackTrace();
      log.error(e);
      logConnectionIssue(storageType, userId, null, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    if (message.body() instanceof JsonObject) {
      ((JsonObject) message.body()).put("accessToken", authCode);
    }

    XRayManager.endSegment(subsegment);
    if (replyOnOk) {
      sendOK(segment, message);
    }
    return authCode;
  }

  @Override
  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String parentId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
    String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
    if (parentId == null) {
      return;
    }
    parentId = BaseObject.normalizeObjectId(parentId);
    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }

    try {

      JsonObject folderContent = apiWrapper.createFolder(getSessionToken(message), parentId, name);
      JsonObject receivedInfo = folderContent.getJsonObject(Field.RESULT.getName());

      if (receivedInfo != null && receivedInfo.containsKey(Field.ID.getName())) {
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(
                    Field.FOLDER_ID.getName(),
                    Utils.getEncapsulatedId(
                        StorageType.getShort(storageType),
                        externalId,
                        receivedInfo.getString(Field.ID.getName()))),
            mills);
      } else {
        sendError(
            segment, message, Utils.getLocalizedString(message, "ProblemsWithDropboxClient"), 403);
      }
    } catch (Exception e) {
      handleException(segment, message, e);
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
    String targetFolderId =
        getRequiredString(segment, Field.PARENT_ID.getName(), message, message.body());
    if (id == null || targetFolderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    targetFolderId = BaseObject.normalizeObjectId(targetFolderId);
    try {
      apiWrapper.moveObject(getSessionToken(message), id, targetFolderId);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
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

  private void doRename(Message<JsonObject> message, String objectId) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }
    if (objectId == null || name == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      apiWrapper.renameObject(getSessionToken(message), objectId, name);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
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
      apiWrapper.deleteObject(getSessionToken(message), id);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doClone(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    boolean isFile = true;
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      isFile = false;
      id = message.body().getString(Field.FOLDER_ID.getName());
    }
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String name = message.body().getString(Field.FILE_NAME.getName());
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
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    final String hancomSessionId = getSessionToken(message);
    try {
      // there's no specific clone operation in API - so let's just download and upload
      byte[] fileData = apiWrapper.getFileContent(hancomSessionId, id);
      JsonObject objectInfo = apiWrapper.getObjectInfo(hancomSessionId, id);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      if (receivedInfo == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), id),
            HttpStatus.NOT_FOUND);
        return;
      }
      File object = new File(receivedInfo, false);
      JsonObject uploadResponse = apiWrapper.uploadFile(
          hancomSessionId,
          BaseObject.normalizeObjectId(object.getParentFolderId()),
          null,
          name,
          fileData);
      JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
      String newId = Utils.getEncapsulatedId(
          storageType, externalId, newFileInfo.getString(Field.ID.getName()));
      sendOK(
          segment,
          message,
          new JsonObject().put(isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(), newId),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
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
    final String hancomSessionId = getSessionToken(message);
    try {
      XRayEntityUtils.putStorageMetadata(segment, message, storageType);
      String name;
      JsonObject objectInfo;
      byte[] data;
      if (latest || versionId == null) {
        try {
          objectInfo = apiWrapper.getObjectInfo(hancomSessionId, fileId);
          JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
          if (receivedInfo == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                HttpStatus.NOT_FOUND);
            return;
          }
          // get latest version in external storage
          versionId = String.valueOf(receivedInfo.getInteger("lastVersion"));
          name = receivedInfo.getString(Field.NAME.getName());
          data = apiWrapper.getFileContent(hancomSessionId, fileId);
        } catch (Exception e) {
          // if we still have some exception - we cannot proceed.
          // Let's log it and return error
          XRayEntityUtils.addException(segment, e);
          log.error("HC: Exception on trying to get file's info", e);
          handleException(segment, message, e);
          return;
        }
      } else if (Utils.isStringNotNullOrEmpty(versionId)) {
        try {
          objectInfo = apiWrapper.getObjectInfo(hancomSessionId, fileId);
          JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
          if (receivedInfo == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                HttpStatus.NOT_FOUND);
            return;
          }
          // get latest version in external storage
          name = receivedInfo.getString(Field.NAME.getName());
          // latest version cannot be received through "getFileVersion"
          if (Integer.parseInt(versionId) == receivedInfo.getInteger("lastVersion")) {
            data = apiWrapper.getFileContent(hancomSessionId, fileId);
          } else {
            data = apiWrapper.getFileVersion(hancomSessionId, fileId, versionId);
          }
        } catch (Exception e) {
          // if we still have some exception - we cannot proceed.
          // Let's log it and return error
          XRayEntityUtils.addException(segment, e);
          log.error("HC: Exception on trying to get file's info", e);
          handleException(segment, message, e);
          return;
        }
      } else {
        // not latest and no versionId
        log.error("HC: No latest and no versionId specified");
        handleException(segment, message, new Exception("No latest and no versionId specified"));
        return;
      }
      finishGetFile(message, start, end, data, storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      log.error(e);
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      handleException(segment, message, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  @Override
  public void doRequestMultipleObjectsZip(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    String name = parsedMessage.getJsonObject().getString(Field.NAME.getName());
    if (name != null) {
      try {
        this.validateObjectName(name, specialCharacters);
      } catch (IllegalArgumentException e1) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, e1.getMessage()),
            HttpStatus.BAD_REQUEST);
      }
    }
    String folderId = parsedMessage.getJsonObject().getString(Field.FOLDER_ID.getName());
    String fileId = parsedMessage.getJsonObject().getString(Field.FILE_ID.getName());
    String xSessionId = parsedMessage.getJsonObject().getString(Field.X_SESSION_ID.getName());
    String userId = parsedMessage.getJsonObject().getString(Field.USER_ID.getName());
    Boolean isAdmin = parsedMessage.getJsonObject().getBoolean(Field.IS_ADMIN.getName());
    String externalId = parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName());
    String baseChangeId = parsedMessage.getJsonObject().getString(Field.BASE_CHANGE_ID.getName());
    String userEmail = parsedMessage.getJsonObject().getString(Field.EMAIL.getName());
    String userName = parsedMessage.getJsonObject().getString(Field.F_NAME.getName());
    String userSurname = parsedMessage.getJsonObject().getString(Field.SURNAME.getName());
    if (folderId == null && fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if (folderId != null) {
      folderId = BaseObject.normalizeObjectId(folderId);
    }

    final String hancomSessionId = getSessionToken(message, parsedMessage.getJsonObject());

    String responseName = null, modifier;
    String conflictingFileReason =
        parsedMessage.getJsonObject().getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = false;
    try {
      String versionId = emptyString;
      if (parsedMessage.hasByteArrayContent()) {
        if (fileId != null) {
          JsonObject objectInfo = apiWrapper.getObjectInfo(hancomSessionId, fileId);
          JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
          if (receivedInfo == null) {
            sendError(
                segment,
                message,
                MessageFormat.format(
                    Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
                HttpStatus.NOT_FOUND);
            return;
          }
          File file = new File(receivedInfo, false);
          modifier = file.getEditorName();
          String originalFileId = fileId;
          // get latest version in external storage
          versionId = file.getVersionId();
          name = file.getName();

          // if latest version in ex storage is unknown,
          // then save a new version as a file with a prefix beside the original file
          isConflictFile =
              isConflictingChange(userId, fileId, storageType, versionId, baseChangeId);
          if (isConflictFile && !Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            conflictingFileReason =
                XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
          }

          if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            boolean isOwner = file.isOwner();
            if (!isOwner && file.isViewOnly()) {
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
          if (!isConflictFile) {
            try {
              JsonObject uploadResponse = apiWrapper.uploadFile(
                  hancomSessionId, folderId, fileId, name, parsedMessage.getContentAsByteArray());
              JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
              if (!newFileInfo.containsKey(Field.ID.getName())) {
                sendError(
                    segment,
                    message,
                    new JsonObject()
                        .put("nativeError", uploadResponse)
                        .put(
                            Field.MESSAGE.getName(),
                            Utils.getLocalizedString(message, "CouldNotUploadFile")),
                    HttpStatus.BAD_REQUEST);
                return;
              }
              fileId = newFileInfo.getString(Field.ID.getName());
              versionId = String.valueOf(newFileInfo.getInteger("lastVersion"));
            } catch (HancomException hancomException) {
              // we fall here if upload has failed for collaborator
              boolean isShared = file.isShared();
              if (isShared) {
                file.checkOwnerEmailAgainstUser(getExternalEmail(userId, externalId));
                if (!file.isOwner()) {
                  JsonObject uploadResponse = apiWrapper.uploadSharedFile(
                      hancomSessionId,
                      fileId,
                      HancomAPI.ROOT_FOLDER_ID,
                      name,
                      parsedMessage.getContentAsByteArray());
                  JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
                  if (!newFileInfo.containsKey(Field.ID.getName())) {
                    sendError(
                        segment,
                        message,
                        new JsonObject()
                            .put("nativeError", uploadResponse)
                            .put(
                                Field.MESSAGE.getName(),
                                Utils.getLocalizedString(message, "CouldNotUploadFile")),
                        400);
                    return;
                  }
                  fileId = newFileInfo.getString(Field.ID.getName());
                  versionId = String.valueOf(newFileInfo.getInteger("lastVersion"));
                } else {
                  throw hancomException;
                }
              } else {
                throw hancomException;
              }
            }
          } else {
            final String oldName = file.getName();
            String newName = getConflictingFileName(oldName);
            responseName = newName;
            JsonObject uploadResponse = apiWrapper.uploadFile(
                hancomSessionId, folderId, null, newName, parsedMessage.getContentAsByteArray());
            JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
            fileId = newFileInfo.getString(Field.ID.getName());
            versionId = String.valueOf(newFileInfo.getInteger("lastVersion"));
            handleConflictingFile(
                segment,
                message,
                parsedMessage.getJsonObject(),
                oldName,
                newName,
                Utils.getEncapsulatedId(storageType, externalId, originalFileId),
                Utils.getEncapsulatedId(storageType, externalId, fileId),
                xSessionId,
                userId,
                modifier,
                conflictingFileReason,
                fileSessionExpired,
                true,
                AuthManager.ClientType.getClientType(
                    parsedMessage.getJsonObject().getString(Field.DEVICE.getName())));
          }
        } else {
          JsonObject uploadResponse = apiWrapper.uploadFile(
              hancomSessionId, folderId, null, name, parsedMessage.getContentAsByteArray());
          JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
          fileId = newFileInfo.getString(Field.ID.getName());
          versionId = String.valueOf(newFileInfo.getInteger("lastVersion"));
        }
      }
      if ((name == null || Extensions.isThumbnailExt(config, name, isAdmin))
          && Utils.isStringNotNullOrEmpty(versionId)) {
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
      JsonObject json = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, fileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              StorageType.getShort(storageType) + "_" + fileId + "_" + versionId + ".png");

      if (isConflictFile && Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
        json.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
      }

      if (isConflictFile && Utils.isStringNotNullOrEmpty(responseName)) {
        json.put(Field.NAME.getName(), responseName);
      }
      sendOK(segment, message, json, mills);

      if (parsedMessage.hasByteArrayContent()) {
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
    } catch (Exception ex) {
      ex.printStackTrace();
      handleException(segment, message, ex);
    }
  }

  @Override
  public void doUploadVersion(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    String userEmail = parsedMessage.getJsonObject().getString(Field.EMAIL.getName());
    String userName = parsedMessage.getJsonObject().getString(Field.F_NAME.getName());
    String userSurname = parsedMessage.getJsonObject().getString(Field.SURNAME.getName());
    String name = parsedMessage.getJsonObject().getString(Field.NAME.getName());
    if (name != null) {
      try {
        this.validateObjectName(name, specialCharacters);
      } catch (IllegalArgumentException e1) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, e1.getMessage()),
            HttpStatus.BAD_REQUEST);
      }
    }
    String fileId = parsedMessage.getJsonObject().getString(Field.FILE_ID.getName());
    String externalId = parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName());
    if (fileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    final String hancomSessionId = getSessionToken(message, parsedMessage.getJsonObject());

    try {
      String versionId;
      JsonObject objectInfo = apiWrapper.getObjectInfo(hancomSessionId, fileId);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      if (receivedInfo == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }
      File object = new File(receivedInfo, false);

      // Do we need to use
      // https://stg-api.malangmalang.com/space/swagger/#/File%20related%20APIs/post_uploadNewVersion?
      JsonObject uploadResponse = apiWrapper.uploadFile(
          hancomSessionId,
          BaseObject.normalizeObjectId(object.getParentFolderId()),
          fileId,
          name,
          parsedMessage.getContentAsByteArray());
      JsonObject newFileInfo = uploadResponse.getJsonObject(Field.RESULT.getName());
      fileId = newFileInfo.getString(Field.ID.getName());
      // hancom treats lastVersion as the versionId for the next version actually.
      versionId = String.valueOf(newFileInfo.getInteger("lastVersion") - 1);
      eb_send(
          segment,
          ThumbnailsManager.address + ".create",
          parsedMessage
              .getJsonObject()
              .put(Field.FORCE.getName(), true)
              .put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(
                  Field.IDS.getName(),
                  new JsonArray()
                      .add(new JsonObject()
                          .put(Field.FILE_ID.getName(), fileId)
                          .put(Field.VERSION_ID.getName(), versionId)
                          .put(Field.EXT.getName(), Extensions.getExtension(name)))));
      JsonObject json = new JsonObject()
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, fileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              StorageType.getShort(storageType) + "_" + fileId + "_" + versionId + ".png");

      sendOK(segment, message, json, mills);

      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FORCE.getName(), true)
              .put(Field.EMAIL.getName(), userEmail)
              .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
    } catch (Exception ex) {
      ex.printStackTrace();
      handleException(segment, message, ex);
    }
  }

  private VersionInfo getVersionInfo(
      String fileId, String externalId, JsonObject versionData, String latestVersionId) {
    VersionModifier versionModifier = new VersionModifier();
    if (versionData.containsKey(Field.EDITOR.getName())) {
      JsonObject editor = versionData.getJsonObject(Field.EDITOR.getName());
      versionModifier.setCurrentUser(
          String.valueOf(editor.getInteger("user_id")).equals(externalId));
      versionModifier.setEmail(editor.getString("user_email"));
      versionModifier.setName(editor.getString("user_name"));
      versionModifier.setId(String.valueOf(editor.getInteger("user_id")));
    }
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(true);
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    String versionId = String.valueOf(versionData.getInteger("version"));
    if (Utils.isStringNotNullOrEmpty(latestVersionId) && latestVersionId.equals(versionId)) {
      versionPermissions.setCanDelete(false);
    }
    VersionInfo versionInfo = new VersionInfo(
        versionId, versionData.getLong("gmtModified"), versionData.getString(Field.NAME.getName()));
    if (versionData.containsKey(Field.SIZE.getName())) {
      versionInfo.setSize(versionData.getInteger(Field.SIZE.getName()));
    }
    versionInfo.setModifier(versionModifier);
    versionInfo.setPermissions(versionPermissions);
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
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    try {
      List<JsonObject> result = new ArrayList<>();
      JsonObject versionsObj = apiWrapper.getVersionsList(getSessionToken(message), fileId);
      JsonArray versionsArray = versionsObj.getJsonArray("list");
      String latestVersionId = null;
      if (!versionsArray.isEmpty()) {
        JsonObject latestVersion = versionsArray.getJsonObject(versionsArray.size() - 1);
        latestVersionId = latestVersion != null ? latestVersion.getString("version") : null;
      }
      String finalLatestVersionId = latestVersionId;
      versionsArray.forEach(revision -> result.add(getVersionInfo(
              fileId,
              message.body().getString(Field.EXTERNAL_ID.getName()),
              (JsonObject) revision,
              finalLatestVersionId)
          .toJson()));
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              File file = new File(
                  apiWrapper
                      .getObjectInfo(getSessionToken(message), fileId)
                      .getJsonObject(Field.INFO.getName()),
                  false);
              if (Utils.isStringNotNullOrEmpty(file.getName())) {
                ext = Extensions.getExtension(file.getName());
              }
            } catch (Exception ex) {
              log.warn("[HANCOM] Get versions: Couldn't get object info to get extension.", ex);
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
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());

    final String hancomSessionId = getSessionToken(message);
    if (fileId == null) {
      return;
    }
    try {
      JsonObject objectInfo = apiWrapper.getObjectInfo(hancomSessionId, fileId);
      String versionId =
          String.valueOf(objectInfo.getJsonObject(Field.INFO.getName()).getInteger("lastVersion"));
      sendOK(segment, message, new JsonObject().put(Field.VERSION_ID.getName(), versionId), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    try {
      final String hancomSessionId = getSessionToken(message);
      // https://stg-api.malangmalang.com/space/swagger/#/File%20related%20APIs/post_versions_restore
      JsonObject objectInfo = apiWrapper.restoreVersion(
          hancomSessionId, fileId, Integer.parseInt(versionId), "ARES Kudo restore " + versionId);

      String newVersionId = emptyString;
      try {
        newVersionId = String.valueOf(objectInfo.getLong(Field.LAST_MODIFIED.getName()));
      } catch (Exception ignore) {
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), newVersionId),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  @Override
  public void doDeleteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    final String hancomSessionId = getSessionToken(message);
    try {
      apiWrapper.deleteVersion(hancomSessionId, fileId, Integer.parseInt(versionId));
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    if (fileId == null) {
      return;
    }
    boolean success = false;
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    String sessionToken = null;
    try {
      sessionToken = EncryptHelper.decrypt(
          item.getString(Field.ACCESS_TOKEN.getName()),
          config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_DECRYPT_TOKENS);
    }
    if (sessionToken != null) {
      try {
        JsonObject objectInfo = apiWrapper.getObjectInfo(sessionToken, fileId);
        JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
        File fileInfo = new File(receivedInfo, false);
        byte[] data = apiWrapper.getFileContent(sessionToken, fileId);
        JsonObject fileJson = fileInfo.toJson(
            externalId, config, false, s3Regional, userId, storageType, false, false);
        finishGetFile(
            message,
            null,
            null,
            data,
            storageType,
            fileJson.getString(Field.FILE_NAME.getName()),
            fileJson.getString(Field.VER_ID.getName()),
            downloadToken);
        success = true;
      } catch (Exception err) {
        log.error("Error on getting Hancom file's data by token", err);
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, err.getLocalizedMessage(), null);
      }
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

  @Override
  public void doShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String email = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    String role = getRequiredString(segment, Field.ROLE.getName(), message, message.body());
    if (id == null || email == null || role == null) {
      return;
    }
    final String finalEmail = email.toLowerCase();
    try {
      String sessionToken = getSessionToken(message);

      // check if it's update
      JsonObject objectCollaborators = apiWrapper.getListOfCollaborators(sessionToken, id);
      JsonArray list = objectCollaborators.getJsonArray("list");
      JsonArray invitedList = objectCollaborators.getJsonArray("invitedList");
      boolean isInvite = false;
      JsonObject foundCollaborator = null;
      if (list.size() > 0) {
        foundCollaborator = (JsonObject) list.stream()
            .filter(collaborator ->
                ((JsonObject) collaborator).getString(Field.EMAIL.getName()).equals(finalEmail))
            .findFirst()
            .orElse(null);
      }
      if (foundCollaborator == null && invitedList.size() > 0) {
        foundCollaborator = (JsonObject) invitedList.stream()
            .filter(collaborator ->
                ((JsonObject) collaborator).getString(Field.EMAIL.getName()).equals(finalEmail))
            .findFirst()
            .orElse(null);
        if (foundCollaborator != null) {
          isInvite = true;
        }
      }
      if (foundCollaborator != null) {
        // update permissions
        if (!isInvite) {
          apiWrapper.updateShare(
              sessionToken,
              id,
              String.valueOf(foundCollaborator.getInteger(Field.USER_ID.getName())),
              BaseObject.getPermissionsSet(role));
        } else {
          apiWrapper.updateInvite(sessionToken, id, finalEmail, BaseObject.getPermissionsSet(role));
        }
      } else {
        apiWrapper.shareObject(
            sessionToken, id, finalEmail, null, BaseObject.getPermissionsSet(role));
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, body);
    Object name = body.getValue(Field.NAME.getName());
    String hancomUserId = null; // for shares - we receive id
    String email = null; // for invites - we get email
    if (name instanceof Integer) {
      hancomUserId = String.valueOf(name);
    } else {
      email = (String) name;
    }
    String externalId = getRequiredString(segment, Field.EXTERNAL_ID.getName(), message, body);
    if (id == null || (hancomUserId == null && email == null)) {
      return;
    }
    boolean selfDeShare = hancomUserId != null && hancomUserId.equals(externalId);
    String sessionToken = getSessionToken(message);
    try {
      if (!selfDeShare) {
        if (Utils.isStringNotNullOrEmpty(hancomUserId)) {
          apiWrapper.removeShare(sessionToken, id, hancomUserId);
        } else {
          apiWrapper.removeInvite(sessionToken, id, email);
        }
      } else {
        apiWrapper.removeOwnShare(sessionToken, id);
      }
      if (email == null) {
        email = getExternalEmail(body.getString(Field.USER_ID.getName()), externalId);
      }
      sendOK(segment, message, new JsonObject().put(Field.USERNAME.getName(), email), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    if (id == null) {
      id = message.body().getString(Field.FOLDER_ID.getName());
    }
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      // Doesn't work for some reason
      apiWrapper.restoreObject(getSessionToken(message), id);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doGetInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
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
    try {
      String hancomSessionId = getSessionToken(message);
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
      JsonObject objectInfo = apiWrapper.getObjectInfo(hancomSessionId, id);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      JsonObject json = new JsonObject();
      boolean isShared;
      BaseObject object;
      boolean isFolder = BaseObject.isFolder(receivedInfo);
      if (isFolder) {
        object = new Folder(id, receivedInfo, false);
        isShared = object.isShared();
      } else {
        object = new File(receivedInfo, false);
        isShared = object.isShared();
        ((File) object).fetchEditor(apiWrapper.getVersionsList(hancomSessionId, id));
      }
      if (isShared) {
        JsonObject collaboratorsInfo = apiWrapper.getListOfCollaborators(hancomSessionId, id);
        JsonArray listOfCollaborators = collaboratorsInfo
            .getJsonArray("list")
            .addAll(collaboratorsInfo.getJsonArray("invitedList"));
        if (listOfCollaborators.size() > 0) {
          object.updateShare(listOfCollaborators, externalId);
          if (isFolder) {
            json = ((Folder) object).toJson(externalId, storageType);
          } else {
            json = ((File) object)
                .toJson(externalId, config, isAdmin, s3Regional, userId, storageType, false, false);
          }
        }
      } else if (isFolder) {
        json = ((Folder) object).toJson(externalId, storageType);
      } else {
        json = ((File) object)
            .toJson(externalId, config, isAdmin, s3Regional, userId, storageType, false, false);
      }

      sendOK(segment, message, json, mills);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      handleException(segment, message, e);
    }
  }

  @Override
  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    try {
      JsonObject objectInfo = apiWrapper.getObjectInfo(getSessionToken(message), fileId);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      File fileInfo = new File(receivedInfo, false);
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
                          .put(Field.FILE_ID.getName(), fileInfo.getId())
                          .put(Field.VERSION_ID.getName(), fileInfo.getVersionId())
                          .put(Field.EXT.getName(), Extensions.getExtension(fileInfo.getName()))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          StorageType.getShort(storageType), fileInfo.getId(), fileInfo.getVersionId());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      fileInfo.getId(), storageType.name(), fileInfo.getVersionId(), true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
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
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    String sessionToken = null;
    try {
      sessionToken = EncryptHelper.decrypt(
          item.getString(Field.ACCESS_TOKEN.getName()),
          config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, creatorId, null, ConnectionError.CANNOT_DECRYPT_TOKENS);
    }
    try {
      if (sessionToken != null) {
        JsonObject objectInfo = apiWrapper.getObjectInfo(sessionToken, fileId);
        JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
        File fileInfo = new File(receivedInfo, false);
        fileInfo.fetchEditor(apiWrapper.getVersionsList(sessionToken, fileId));
        String versionId = fileInfo.getVersionId();
        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType), fileInfo.getId(), versionId);
        JsonObject json = new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, fileId))
            .put(Field.WS_ID.getName(), Utils.encodeValue(fileId))
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
            .put(Field.FILE_NAME.getName(), fileInfo.getName())
            .put(Field.VERSION_ID.getName(), versionId)
            .put(Field.CREATOR_ID.getName(), creatorId)
            .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
            .put(
                Field.THUMBNAIL.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .put(Field.EXPORT.getName(), export)
            .put(Field.UPDATE_DATE.getName(), fileInfo.getModifiedTime())
            .put(Field.CHANGER.getName(), fileInfo.getEditorName());
        sendOK(segment, message, json, mills);
        return;
      }
    } catch (Exception ignored) {
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  @Override
  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = jsonObject.getString(Field.ID.getName());
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
    try {
      JsonObject objectInfo =
          apiWrapper.getObjectInfo(getSessionToken(message), BaseObject.normalizeObjectId(id));
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      JsonArray json = new JsonArray();
      if (!receivedInfo.isEmpty()
          && receivedInfo.containsKey("pathID")
          && receivedInfo.containsKey(Field.PATH.getName())) {
        String[] pathIds = receivedInfo.getString("pathID").split("/");
        String[] pathNames = receivedInfo.getString(Field.PATH.getName()).split("/");
        if (pathIds.length != pathNames.length) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "PathNotFound"),
              HttpStatus.BAD_REQUEST);
          return;
        } else {
          // iterate from the end as we need reversed list historically
          for (int i = pathIds.length - 1; i >= 0; i--) {
            String denormalizedID = BaseObject.deNormalizeObjectId(
                URLDecoder.decode(pathIds[i], StandardCharsets.UTF_8));
            String folderName = pathNames[i];
            if (denormalizedID.equals(Field.MINUS_1.getName())) {
              folderName = "~";
            }
            json.add(new JsonObject()
                .put(Field.NAME.getName(), folderName)
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, externalId, denormalizedID)));
          }
        }
      }

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), json), mills);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      handleException(segment, message, e);
    }
  }

  @Override
  public void doEraseAll(Message<JsonObject> message) {}

  @Override
  public void doEraseFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doErase(message, id);
  }

  @Override
  public void doEraseFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doErase(message, id);
  }

  private void doErase(Message<JsonObject> message, String id) {
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
      // Doesn't work for some reason
      apiWrapper.eraseObject(getSessionToken(message), id);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doCreateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
    Boolean export = body.getBoolean(Field.EXPORT.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    final long endTime =
        body.containsKey(Field.END_TIME.getName()) ? body.getLong(Field.END_TIME.getName()) : 0L;
    final String password = body.getString(Field.PASSWORD.getName());
    final String userId = body.getString(Field.USER_ID.getName());
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
      externalId = findExternalId(segment, message, message.body(), storageType);
      if (externalId != null) {
        body.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    try {
      JsonObject objectInfo = apiWrapper.getObjectInfo(getSessionToken(message), fileId);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      File file = new File(receivedInfo, false);
      try {
        final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            externalId,
            userId,
            storageType,
            getExternalEmail(userId, externalId),
            file.getName(),
            export,
            endTime,
            password);
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
      handleException(segment, message, e);
    }
  }

  @Override
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
      handleException(segment, message, e);
    }
  }

  private void zipFolder(
      ZipOutputStream stream,
      String hancomSessionId,
      String folderId,
      String filter,
      String path,
      Set<String> filteredFileNames)
      throws Exception {
    JsonObject contentResponse = apiWrapper.listFolderContent(hancomSessionId, folderId);
    JsonArray receivedItems = contentResponse.containsKey(Field.DATA.getName())
        ? contentResponse.getJsonArray(Field.DATA.getName())
        : null;
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    if (receivedItems == null || receivedItems.isEmpty()) {
      return;
    }
    for (Object itemInfo : receivedItems) {
      JsonObject jsonInfo = (JsonObject) itemInfo;
      if (BaseObject.isFolder(jsonInfo)) {
        Folder folder = new Folder(jsonInfo, folderId, false);
        String name = folder.getName();
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        zipFolder(
            stream,
            hancomSessionId,
            folder.getId(),
            filter,
            (path.isEmpty() ? path : path + java.io.File.separator) + name,
            filteredFileNames);
      } else {
        File file = new File(jsonInfo, folderId, false);
        String name = file.getName();
        byte[] data = apiWrapper.getFileContent(hancomSessionId, file.getId());
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
          zipEntry = new ZipEntry((path.isEmpty() ? path : path + java.io.File.separator) + name);
        }
        if (zipEntry != null) {
          stream.putNextEntry(zipEntry);
          stream.write(data);
          stream.closeEntry();
        }
      }
    }
  }

  @Override
  public void doRequestFolderZip(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String requestId = message.body().getString(Field.REQUEST_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String folderId = message.body().getString(Field.FOLDER_ID.getName());
    String filter = message.body().getString(Field.FILTER.getName());
    Item request = ZipRequests.getZipRequest(userId, folderId, requestId);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String hancomSessionId = getSessionToken(message);
    if (!Utils.isStringNotNullOrEmpty(hancomSessionId)) {
      ZipRequests.setRequestStatus(
          request, JobStatus.ERROR, "could not connect to " + storageType.name());
      sendError(segment, message, "could not connect to Storage", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .runWithoutSegment(() -> {
          try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ZipOutputStream stream = new ZipOutputStream(bos);
            zipFolder(stream, hancomSessionId, folderId, filter, emptyString, new HashSet<>());
            stream.close();
            bos.close();
            ZipRequests.putZipToS3(
                request,
                s3Regional.getBucketName(),
                s3Regional.getRegion(),
                bos.toByteArray(),
                true,
                Utils.getLocaleFromMessage(message));
          } catch (Exception ex) {
            ZipRequests.setRequestException(message, request, ex);
          }
        });
    sendOK(segment, message);
  }

  @Override
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
          .map(hancomUser -> {
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            String sessionToken = parseSessionToken(
                hancomUser.getString(Field.ACCESS_TOKEN.getName()),
                hancomUser.getString(Field.FLUORINE_ID.getName()),
                hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            if (!Utils.isStringNotNullOrEmpty(sessionToken)) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), hancomUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            JsonArray foundFolders;
            JsonArray foundFiles;
            try {
              JsonObject searchResults = apiWrapper.search(sessionToken, query);
              foundFolders = searchResults
                  .getJsonObject(Field.FOLDERS.getName())
                  .getJsonArray(Field.DATA.getName());
              foundFiles = searchResults
                  .getJsonObject(Field.FILES.getName())
                  .getJsonArray(Field.DATA.getName());
            } catch (HancomException e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), hancomUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            String externalId = hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName());
            foundFiles.stream()
                .forEach(file -> filesJson.add(new File((JsonObject) file)
                    .toJson(
                        externalId,
                        config,
                        isAdmin,
                        s3Regional,
                        userId,
                        storageType,
                        false,
                        false)));
            foundFolders.stream()
                .forEach(folder -> foldersJson.add(
                    new Folder((JsonObject) folder).toJson(externalId, storageType)));
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    hancomUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), hancomUser.getString(Field.EMAIL.getName()))
                .put(Field.FILES.getName(), filesJson)
                .put(Field.FOLDERS.getName(), foldersJson)
                .put(Field.IDS.getName(), new JsonArray());
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
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
    boolean isFolderProvided = Utils.isStringNotNullOrEmpty(requestFolderId);
    try {
      for (Object externalId : collaborators) {

        JsonObject xrefsCache = findCachedXrefs(
            segment, message, jsonObject, storageType, userId, (String) externalId, fileId, path);

        Iterator<Item> foreignUsers = Collections.emptyIterator();
        try {
          Item hancomUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
          if (hancomUser != null) {
            foreignUsers = Collections.singletonList(hancomUser).iterator();
          }
        } catch (Exception e) {
          // todo form Exception in case we don't receive
        }
        while (foreignUsers.hasNext()) {
          Item foreignUser = foreignUsers.next();
          externalId = foreignUser.getString(Field.EXTERNAL_ID_LOWER.getName());
          String foundUserId = foreignUser.getString(Field.FLUORINE_ID.getName());

          JsonArray results = new JsonArray();

          if (xrefsCache.getJsonArray("unknownXrefs").size() > 0) {
            // find folder where request came from.
            // it can be found either by provided folderId
            // or by looking up parent of fileId
            String basePath;
            String hancomSession = parseSessionToken(
                foreignUser.getString(Field.ACCESS_TOKEN.getName()), foundUserId, (String)
                    externalId);
            // if folderId isn't provided - check by fileId
            JsonObject objectInfo = apiWrapper.getObjectInfo(
                hancomSession, isFolderProvided ? requestFolderId : fileId);
            JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
            if (receivedInfo.containsKey(Field.PATH.getName())) {
              basePath = receivedInfo.getString(Field.PATH.getName());
              if (!isFolderProvided) {
                basePath = Utils.safeSubstringToLastOccurrence(basePath, "/");
              }
            } else {
              sendError(segment, message, "Not found", HttpStatus.NOT_FOUND);
              return;
            }

            String finalExternalId = (String) externalId;
            List<String> pathList = xrefsCache.getJsonArray("unknownXrefs").getList();
            String finalBasePath = basePath;
            results = new JsonArray(pathList.parallelStream()
                .map(pathStr -> {
                  Entity subSegment =
                      XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
                  if (pathStr == null) {
                    XRayManager.endSegment(subSegment);
                    return null;
                  }
                  String originalPathStr = pathStr;
                  JsonArray response = new JsonArray();
                  pathStr = pathStr.replaceAll("[/\\\\]+", "/");
                  String targetPath = Paths.getAbsolutePath(finalBasePath, pathStr);
                  try {
                    JsonObject objectInfoByPath =
                        apiWrapper.getObjectInfoByPath(hancomSession, targetPath);
                    JsonObject fileInfo = objectInfoByPath.getJsonObject(Field.INFO.getName());
                    if (!fileInfo.isEmpty()) {
                      response.add(new File(fileInfo, false)
                          .toJson(
                              finalExternalId,
                              config,
                              false,
                              s3Regional,
                              foundUserId,
                              storageType,
                              false,
                              false));
                    }
                  } catch (Exception ex) {
                    XRayEntityUtils.addException(subSegment, ex);
                    log.error("Hancom error on looking for xref.", ex);
                  }

                  XRayManager.endSegment(subSegment);
                  saveXrefToCache(storageType, userId, finalExternalId, fileId, pathStr, response);
                  return new JsonObject()
                      .put(Field.PATH.getName(), originalPathStr)
                      .put(Field.FILES.getName(), response);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
          }

          results.addAll(xrefsCache.getJsonArray("foundXrefs"));
          sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
          return;
        }
      }
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    try {
      boolean isFolderProvided = Utils.isStringNotNullOrEmpty(folderId);
      String hancomSession = getSessionToken(message);
      JsonObject objectInfo =
          apiWrapper.getObjectInfo(hancomSession, isFolderProvided ? folderId : fileId);
      JsonObject receivedInfo = objectInfo.getJsonObject(Field.INFO.getName());
      String basePath;
      if (receivedInfo.containsKey(Field.PATH.getName())) {
        basePath = receivedInfo.getString(Field.PATH.getName());
        if (!isFolderProvided) {
          basePath = Utils.safeSubstringToLastOccurrence(basePath, "/");
        }
      } else {
        sendError(segment, message, "Not found", HttpStatus.NOT_FOUND);
        return;
      }
      // RG: Refactored to not use global collections
      List<String> pathList = path.getList();
      String finalBasePath = basePath;
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            if (pathStr == null) {
              XRayManager.endSegment(subSegment);
              return null;
            }
            String originalPathStr = pathStr;
            pathStr = pathStr.replaceAll("[/\\\\]+", "/");
            String targetPath = Paths.getAbsolutePath(finalBasePath, pathStr);
            try {
              JsonObject objectInfoByPath =
                  apiWrapper.getObjectInfoByPath(hancomSession, targetPath);
              JsonObject fileInfo = objectInfoByPath.getJsonObject(Field.INFO.getName());
              if (!fileInfo.isEmpty()) {
                return new JsonObject()
                    .put(Field.PATH.getName(), originalPathStr)
                    .put(Field.STATE.getName(), Field.AVAILABLE.getName());
              }
            } catch (Exception ex) {
              XRayEntityUtils.addException(subSegment, ex);
              log.error("Hancom error on looking for xref.", ex);
            }

            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.PATH.getName(), originalPathStr)
                .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public <T> boolean checkFile(Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      String hancomSession = getSessionToken(
          json.getString(Field.USER_ID.getName()), json.getString(Field.EXTERNAL_ID.getName()));
      log.info("Checking file for hancom. Session length: " + hancomSession.length());
      JsonObject object = apiWrapper.getObjectInfo(hancomSession, fileId);
      if (object != null && !object.isEmpty() && object.containsKey(Field.INFO.getName())) {
        log.info("Hancom file check ok");
        return true;
      }
    } catch (Exception e) {
      log.error(e);
      return false;
    }
    log.info("Hancom file check FALSE. FileId" + fileId);
    return false;
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonObject jsonObject = message.body();
      String id = jsonObject.getString(Field.FILE_ID.getName());
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      if (!Utils.isStringNotNullOrEmpty(id)) {
        log.error("HANCOM get trash status: id is null");
        sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), true), mills);
        return;
      }
      // it seems that all hancom objects starting with r:0 are deleted.
      boolean isDeleted = id.startsWith("r:0");
      if (isDeleted) {
        log.info("HANCOM get trash status: id starts with r:0 - deleted ");
        sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), true), mills);
        return;
      }
      if (!Utils.isStringNotNullOrEmpty(externalId)) {
        log.info("HANCOM externalId is empty ");
        externalId = findExternalId(segment, message, jsonObject, storageType);
        if (externalId != null) {
          jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
        }
      }
      if (externalId == null) {
        log.info("HANCOM externalId is not found ");
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return;
      }
      JsonObject object = apiWrapper.getObjectInfo(getSessionToken(message), id);
      if (object.isEmpty() || !object.containsKey(Field.INFO.getName())) {
        log.info("HANCOM objectInfo isn't correct:" + object);
        isDeleted = true;
      }
      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), isDeleted), mills);
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
}
