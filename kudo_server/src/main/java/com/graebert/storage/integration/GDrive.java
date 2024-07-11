package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenErrorResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.HttpUnsuccessfulResponseHandler;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.BackOff;
import com.google.api.client.util.BackOffUtils;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.Preconditions;
import com.google.api.client.util.Sleeper;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.About;
import com.google.api.services.drive.model.DriveList;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.File.Capabilities;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.Permission;
import com.google.api.services.drive.model.Revision;
import com.google.api.services.drive.model.User;
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
import com.graebert.storage.integration.gdrive.TeamDriveWrapper;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.xray.AWSXRayGDrive;
import com.graebert.storage.integration.xray.AWSXRayGDriveFiles;
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
import com.graebert.storage.storage.object.adaptor.GDriveObjectAdaptor;
import com.graebert.storage.storage.object.shortcut.ShortcutEntity;
import com.graebert.storage.storage.zipRequest.ExcludeReason;
import com.graebert.storage.storage.zipRequest.ZipRequests;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.users.IntercomConnection;
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
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import kong.unirest.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class GDrive extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.GDRIVE;

  @NonNls
  public static final String address = "gdrive";

  @NonNls
  private static final String READER = "reader";

  @NonNls
  private static final String WRITER = "writer";

  @NonNls
  private static final String OWNER = Field.OWNER.getName();

  @NonNls
  private static final String ROOT = Field.ROOT.getName();

  @NonNls
  private static final String ANYONE_WITH_LINK = "anyoneWithLink";

  @NonNls
  private static final String APPLICATION_VND_GOOGLE_APPS_FOLDER =
      "application/vnd.google-apps.folder";

  @NonNls
  private static final String APPLICATION_VND_GOOGLE_APPS_SHORTCUT =
      "application/vnd.google-apps.shortcut";

  @NonNls
  private static final String ANYONE = "anyone";

  @NonNls
  private static final String MY_DRIVE = "My Drive";

  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  private static String CLIENT_ID, CLIENT_SECRET;
  private static String OLD_CLIENT_ID;
  private static String OLD_CLIENT_SECRET;

  @NonNls
  private static String REDIRECT_URI;
  // = "https://oauth.dev.graebert.com/?type=google";//"http://localhost:8081";
  private static final Logger log = LogManager.getRootLogger();
  private static final StorageType storageType = StorageType.GDRIVE;
  private final String[] specialCharacters = {};
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private final String permissionURLRegex =
      "403 Forbidden[\n]?(POST|PUT|PATCH|DELETE)[\\s]+(http|https)://www.googleapis.com/drive/"
          + "(v2|v3)/files/[-\\w.]*/permissions/[\\d]*[?]?(.*)";
  private S3Regional s3Regional = null;
  private static final Integer folderContentPageSize = 50;

  public GDrive() {}

  private boolean isNewConnectionAvailable() {
    return Utils.isStringNotNullOrEmpty(CLIENT_ID) && Utils.isStringNotNullOrEmpty(CLIENT_SECRET);
  }

  private boolean shouldUseNewClient(Item gdriveUser) {
    boolean isNewConnectionAvailable = isNewConnectionAvailable();
    boolean doesAccountContainInfo = gdriveUser != null
        && gdriveUser.hasAttribute(Field.CLIENT_ID.getName())
        && Utils.isStringNotNullOrEmpty(gdriveUser.getString(Field.CLIENT_ID.getName()));

    // if there's data in account - use it
    if (doesAccountContainInfo) {
      String connectionClientId = gdriveUser.getString(Field.CLIENT_ID.getName());
      if (connectionClientId.equals(CLIENT_ID)) {
        return true;
      }
      if (connectionClientId.equals(OLD_CLIENT_ID)) {
        return false;
      }
    }
    return isNewConnectionAvailable;
  }

  private String getClientId(Item gdriveUser) {
    boolean isNewConnection = shouldUseNewClient(gdriveUser);
    if (isNewConnection) {
      return CLIENT_ID;
    } else {
      return OLD_CLIENT_ID;
    }
  }

  private String getClientSecret(Item gdriveUser) {
    boolean isNewConnection = shouldUseNewClient(gdriveUser);
    if (isNewConnection) {
      return CLIENT_SECRET;
    } else {
      return OLD_CLIENT_SECRET;
    }
  }

  private String getAccessToken(Item gDriveUser) throws Exception {
    return EncryptHelper.decrypt(
        gDriveUser.getString(Field.ACCESS_TOKEN.getName()),
        config.getProperties().getFluorineSecretKey());
  }

  @Override
  public void start() throws Exception {
    super.start();
    OLD_CLIENT_ID = config.getProperties().getGdriveClientId();
    OLD_CLIENT_SECRET = config.getProperties().getGdriveClientSecret();

    CLIENT_ID = config.getProperties().getGdriveNewClientId();
    CLIENT_SECRET = config.getProperties().getGdriveNewClientSecret();
    REDIRECT_URI = config.getProperties().getGdriveNewRedirectUri();
    if (!Utils.isStringNotNullOrEmpty(REDIRECT_URI)) {
      REDIRECT_URI = config.getProperties().getGdriveRedirectUri();
    }

    eb.consumer(address + ".addAuthCode", this::doAddAuthCode);
    eb.consumer(
        address + ".getFolderContent",
        (Message<JsonObject> message) -> doGetFolderContent(message, false));
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
        address + ".getTrash", (Message<JsonObject> message) -> doGetFolderContent(message, true));
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
    eb.consumer(address + ".connect", (Message<JsonObject> message) -> connect(message, true));
    eb.consumer(address + ".createSharedLink", this::doCreateSharedLink);
    eb.consumer(address + ".deleteSharedLink", this::doDeleteSharedLink);
    eb.consumer(address + ".requestFolderZip", this::doRequestFolderZip);
    eb.consumer(address + ".requestMultipleObjectsZip", this::doRequestMultipleObjectsZip);
    eb.consumer(address + ".globalSearch", this::doGlobalSearch);
    eb.consumer(address + ".findXRef", this::doFindXRef);
    eb.consumer(address + ".checkPath", this::doCheckPath);
    eb.consumer(address + ".getBatchPath", this::doGetBatchPath);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-gdrive");
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
      AWSXRayGDrive drive = connect(segment, message, message.body(), false);
      if (Objects.isNull(drive)) {
        return;
      }

      String fileId;

      if (TeamDriveWrapper.isTeamDriveId(fileIds.getId())) {
        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, fileIds.getId(), s3Regional);
        fileId = teamDriveWrapper.getItemId();
      } else {
        fileId = fileIds.getId();
      }

      // check if file exists and user has access
      File fileInfo = drive
          .files()
          .get(fileId)
          .setFields("id,owners,permissions,name,headRevisionId,mimeType,trashed")
          .execute();

      if (Objects.isNull(fileInfo) || fileInfo.getTrashed()) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileIds.getId()),
            HttpStatus.NOT_FOUND);
        return;
      }

      message.body().put(Field.NAME.getName(), fileInfo.getName());

      String realVersionId = VersionType.parse(versionId).equals(VersionType.LATEST)
          ? fileInfo.getHeadRevisionId()
          : versionId;

      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      drive
          .revisions()
          .get(fileId, realVersionId)
          .set("alt", "media")
          .executeMediaAndDownloadTo(stream);

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
          fileInfo.getName(),
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
      AWSXRayGDrive drive = connect(segment, message, message.body(), false);
      if (Objects.isNull(drive)) {
        return;
      }

      String fileId;

      if (TeamDriveWrapper.isTeamDriveId(fileIds.getId())) {
        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, fileIds.getId(), s3Regional);
        fileId = teamDriveWrapper.getItemId();
      } else {
        fileId = fileIds.getId();
      }

      File fileInfo = drive
          .files()
          .get(fileId)
          .setFields("id,owners,permissions,name,trashed")
          .execute();

      if (Objects.isNull(fileInfo) || fileInfo.getTrashed()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FileNotFound"),
            HttpStatus.NOT_FOUND,
            "FileNotFound");
        return;
      }

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          drive.revisions().get(fileIds.getId(), versionId).execute();
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
              .put(Field.NAME.getName(), fileInfo.getName())
              .put(
                  Field.OWNER_IDENTITY.getName(),
                  ExternalAccounts.getExternalEmail(userId, fileIds.getExternalId()))
              .put(
                  Field.COLLABORATORS.getName(),
                  fileInfo.getOwners().stream()
                      .map(User::getEmailAddress)
                      .collect(Collectors.toList())),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
    }
  }

  public void doCheckPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      if (folderId != null && folderId.equals(Field.MINUS_1.getName())) {
        folderId = ROOT;
      }
      String currentFolder = folderId;
      if (currentFolder == null) {
        File file = drive.files().get(fileId).setFields(Field.PARENTS.getName()).execute();
        currentFolder = (file.getParents() == null || file.getParents().isEmpty())
            ? ROOT
            : file.getParents().get(0);
      }
      String finalCurrentFolder = currentFolder;
      // RG: Refactored to not use global collections
      List<String> pathList = path.getList();
      JsonArray results = new JsonArray(pathList.parallelStream()
          .map(pathStr -> {
            Entity subSegment =
                XRayManager.createSubSegment(operationGroup, segment, Field.PATH.getName());
            try {
              if (!(pathStr instanceof String)) {
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
                File folder = drive
                    .files()
                    .get(finalCurrentFolder)
                    .setFields("permissions,ownedByMe")
                    .execute();
                boolean available = folder.getOwnedByMe();
                if (!available) {
                  for (Permission permission : folder.getPermissions()) {
                    if (permission.getEmailAddress() != null
                        && permission
                            .getEmailAddress()
                            .equals(message.body().getString(Field.USER_EMAIL.getName()))
                        && Arrays.asList(OWNER, WRITER).contains(permission.getRole())) {
                      available = true;
                      break;
                    }
                  }
                }
                // check for existing file
                if (available) {
                  FileList fileList = drive
                      .files()
                      .list(null)
                      .setQ("trashed = false and name = '" + filename + "' and '"
                          + finalCurrentFolder + "' in parents ")
                      .setFields("files(id)")
                      .execute();
                  available = fileList.getFiles().isEmpty();
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
              Map<String, File> foldersCache = new HashMap<>();
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
                    // to avoid NPE
                    if (Objects.equals(ROOT, possibleFolderId)) {
                      continue;
                    }
                    try {
                      File obj = drive
                          .files()
                          .get(possibleFolderId)
                          .setFields(Field.PARENTS.getName())
                          .execute();
                      String parent =
                          obj.getParents() != null && !obj.getParents().isEmpty()
                              ? obj.getParents().get(0)
                              : ROOT;
                      adds.add(parent);
                      dels.add(possibleFolderId);
                    } catch (Exception exception) {
                      log.error(exception);
                    }
                  } else {
                    FileList folders = drive
                        .files()
                        .list(null)
                        .setQ(
                            "trashed = false and name = '" + array[i] + "' and '" + possibleFolderId
                                + "' in parents " + " and mimeType = " + "'application/vnd"
                                + ".google-apps.folder'")
                        .setFields("files(id,name,owners,ownedByMe,permissions,"
                            + "modifiedTime,lastModifyingUser)")
                        .execute();
                    folders.getFiles().forEach(f -> {
                      foldersCache.put(f.getId(), f);
                      adds.add(f.getId());
                    });
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
                  boolean available = ROOT.equals(possibleFolderId);
                  File possibleFolder = foldersCache.get(possibleFolderId);
                  if (possibleFolder == null) {
                    possibleFolder = drive
                        .files()
                        .get(possibleFolderId)
                        .setFields("id,name,owners,ownedByMe,permissions,modifiedTime,"
                            + "lastModifyingUser")
                        .execute();
                  }
                  if (!available) {
                    available = possibleFolder.getOwnedByMe();
                    if (!available) {
                      for (Permission permission : possibleFolder.getPermissions()) {
                        if (permission.getEmailAddress() != null
                            && permission
                                .getEmailAddress()
                                .equals(message.body().getString(Field.USER_EMAIL.getName()))
                            && Arrays.asList(OWNER, WRITER).contains(permission.getRole())) {
                          available = true;
                          break;
                        }
                      }
                    }
                  }
                  if (available) {
                    if (lastStep == -1) {
                      FileList files = drive
                          .files()
                          .list(null)
                          .setQ("trashed = false and name = '" + filename + "' and '"
                              + possibleFolderId + "' in parents ")
                          .setFields("files(id)")
                          .execute();
                      if (files.getFiles().isEmpty()) {
                        folders.add(new JsonObject()
                            .put(
                                Field.ID.getName(),
                                ROOT.equals(possibleFolderId)
                                    ? Field.MINUS_1.getName()
                                    : possibleFolderId)
                            .put(
                                Field.OWNER.getName(),
                                possibleFolder.getOwners() != null
                                        && !possibleFolder.getOwners().isEmpty()
                                    ? possibleFolder.getOwners().get(0).getDisplayName()
                                    : emptyString)
                            .put(Field.IS_OWNER.getName(), possibleFolder.getOwnedByMe())
                            .put(
                                Field.UPDATE_DATE.getName(),
                                possibleFolder.getModifiedTime().getValue())
                            .put(
                                Field.CHANGER.getName(),
                                possibleFolder.getLastModifyingUser() != null
                                    ? possibleFolder.getLastModifyingUser().getDisplayName()
                                    : emptyString)
                            .put(
                                Field.CHANGER_EMAIL.getName(),
                                possibleFolder.getLastModifyingUser() != null
                                    ? possibleFolder.getLastModifyingUser().getEmailAddress()
                                    : emptyString)
                            .put(Field.NAME.getName(), possibleFolder.getName())
                            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                      }
                    } else {
                      folders.add(new JsonObject()
                          .put(
                              Field.ID.getName(),
                              ROOT.equals(possibleFolderId)
                                  ? Field.MINUS_1.getName()
                                  : possibleFolderId)
                          .put(
                              Field.OWNER.getName(),
                              possibleFolder.getOwners() != null
                                      && !possibleFolder.getOwners().isEmpty()
                                  ? possibleFolder.getOwners().get(0).getDisplayName()
                                  : emptyString)
                          .put(Field.IS_OWNER.getName(), possibleFolder.getOwnedByMe())
                          .put(
                              Field.UPDATE_DATE.getName(),
                              possibleFolder.getModifiedTime().getValue())
                          .put(
                              Field.CHANGER.getName(),
                              possibleFolder.getLastModifyingUser() != null
                                  ? possibleFolder.getLastModifyingUser().getDisplayName()
                                  : emptyString)
                          .put(
                              Field.CHANGER_EMAIL.getName(),
                              possibleFolder.getLastModifyingUser() != null
                                  ? possibleFolder.getLastModifyingUser().getEmailAddress()
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
            } catch (Exception e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(Field.STATE.getName(), Field.UNAVAILABLE.getName());
            }
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), results), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  // todo maybe it makes sense to group paths
  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    if (path == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetXref"),
          HttpStatus.FORBIDDEN,
          "CouldNotGetXref");
      return;
    }
    List<String> filteredPathValues = path.stream()
        .map(pathItem -> (String) pathItem)
        .filter(Utils::isStringNotNullOrEmpty)
        .collect(Collectors.toList());
    path = new JsonArray(filteredPathValues);
    if (path.isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotGetXref"),
          HttpStatus.FORBIDDEN,
          "CouldNotGetXref");
      return;
    }
    try {
      AWSXRayGDrive drive;
      if (collaborators.isEmpty()) {
        String externalId = findExternalId(segment, message, jsonObject, storageType);
        if (Utils.isStringNotNullOrEmpty(externalId)) {
          collaborators.add(externalId);
        }
      }
      for (Object externalId : collaborators) {
        JsonObject xrefsCache = findCachedXrefs(
            segment, message, jsonObject, storageType, userId, (String) externalId, fileId, path);
        Item gdriveUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
        if (gdriveUser != null) {
          drive = connect(gdriveUser);
          externalId = gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName());
          if (drive != null) {
            JsonArray results = new JsonArray();
            // let's bother about search only if those aren't cached already
            if (!xrefsCache.getJsonArray("unknownXrefs").isEmpty()) {
              boolean isTeamDrive = TeamDriveWrapper.isTeamDriveId(fileId);
              Map<String, File> foldersByCache = new HashMap<>();
              if (requestFolderId != null && requestFolderId.equals(Field.MINUS_1.getName())) {
                requestFolderId = ROOT;
              }
              String currentFolder = requestFolderId;
              if (currentFolder == null) {
                File file;
                if (isTeamDrive) {
                  TeamDriveWrapper teamDriveWrapper =
                      new TeamDriveWrapper(drive, config, fileId, s3Regional);
                  teamDriveWrapper.getInfo();

                  currentFolder = teamDriveWrapper.getParentId();
                  isTeamDrive = TeamDriveWrapper.isTeamDriveId(currentFolder);
                } else {
                  file = drive
                      .files()
                      .get(fileId)
                      .setFields(Field.PARENTS.getName())
                      .execute();
                  currentFolder =
                      (file.getParents() == null || file.getParents().isEmpty())
                          ? ROOT
                          : file.getParents().get(0);
                }
              }
              // check if it's the root folder
              if (!currentFolder.equals(ROOT) && !isTeamDrive) {
                File folder =
                    drive.files().get(currentFolder).setFields("name,parents").execute();
                if (folder.getName().equals(MY_DRIVE)) {
                  currentFolder = ROOT;
                } else {
                  foldersByCache.put(folder.getId(), folder);
                }
              }
              String finalCurrentFolder = currentFolder;
              AWSXRayGDrive finalDrive = drive;
              String finalExternalId = (String) externalId;

              // RG: Refactored to not use global collections
              List<String> pathList = xrefsCache.getJsonArray("unknownXrefs").getList();
              results = new JsonArray(pathList.parallelStream()
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
                            if (folderId.equals(Field.MINUS_1.getName())) {
                              adds.add(folderId);
                              continue;
                            }
                            boolean isLastUp = (!array[i + 1].equals(".."));
                            goUp(finalDrive, folderId, foldersByCache, adds, isLastUp);
                          } else {
                            goDown(finalDrive, folderId, array[i], foldersByCache, adds);
                          }
                        }
                        possibleFolders.removeAll(dels);
                        possibleFolders.addAll(adds);
                      }
                    }
                    // check in all possible folders
                    for (String folderId : possibleFolders) {
                      findFileInFolder(finalDrive, pathFiles, filename, folderId, finalExternalId);
                    }
                    // check in the root folder if file is not found
                    if (pathFiles.isEmpty() && !possibleFolders.contains(finalCurrentFolder)) {
                      findFileInFolder(
                          finalDrive, pathFiles, filename, finalCurrentFolder, finalExternalId);
                    }
                    XRayManager.endSegment(subSegment);
                    saveXrefToCache(
                        storageType, userId, finalExternalId, fileId, pathStr, pathFiles);
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
      }
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  private void goDown(
      AWSXRayGDrive drive,
      String folderId,
      String name,
      Map<String, File> foldersByCache,
      Set<String> adds) {
    try {
      if (folderId.equals(Field.MINUS_1.getName())) {
        if (name.equals("Shared with me")) {
          adds.add(SHARED_FOLDER);
        } else {
          File root = drive
              .files()
              .get(Field.ROOT.getName())
              .setFields("id,name,kind,capabilities")
              .execute();
          DriveList driveList = drive
              .drives()
              .list()
              .setFields("drives(id,kind,name,capabilities)")
              .execute();
          for (com.google.api.services.drive.model.Drive teamDrive : driveList.getDrives()) {
            if (teamDrive.getName().equals(name)) {
              adds.add(teamDrive.getId());
              break;
            }
          }

          if (adds.isEmpty()) {
            if (root.getName().equals(name)) {
              adds.add(root.getId());
            }
          }
          return;
        }
      }

      List<File> folders;

      if (TeamDriveWrapper.isTeamDriveId(folderId)) {
        AWSXRayGDriveFiles.AWSXRayDriveListRequest listRequest = drive.files().list(null);

        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, folderId, s3Regional);
        listRequest.setDriveId(teamDriveWrapper.getTeamDriveId());
        listRequest.setIncludeItemsFromAllDrives();
        StringBuilder teamDriveQ = new StringBuilder();
        teamDriveQ.append("mimeType = 'application/vnd.google-apps.folder' and '");
        if (Utils.isStringNotNullOrEmpty(teamDriveWrapper.getItemId())) {
          teamDriveQ.append(teamDriveWrapper.getItemId());
        } else {
          teamDriveQ.append(teamDriveWrapper.getTeamDriveId());
        }
        teamDriveQ.append("' in parents and name = '").append(name).append("'");

        listRequest.setQ(teamDriveQ.toString()).setFields("files(id,name,parents)");

        folders = listRequest.execute().getFiles();
      } else {
        String parentQ = folderId.equals(SHARED_FOLDER)
            ? "and sharedWithMe "
            : "and '" + folderId + "' in parents ";
        String q = ROOT.equals(folderId)
            ? "mimeType = 'application/vnd.google-apps.folder' and ((not 'me' in owners and "
                + "sharedWithMe) or 'root' in parents) and name = '" + name + "'"
            : "mimeType = 'application/vnd.google-apps.folder' " + parentQ + "and name = '" + name
                + "'";
        folders = drive
            .files()
            .list(null)
            .setQ(q)
            .setFields("files(id,name,parents)")
            .execute()
            .getFiles();
      }

      folders.forEach(folder -> {
        adds.add(folder.getId());
        foldersByCache.put(folder.getId(), folder);
      });
    } catch (Exception e) {
      log.error("Exception on going down in GDrive", e);
    }
  }

  private void goUp(
      AWSXRayGDrive drive,
      String folderId,
      Map<String, File> foldersByCache,
      Set<String> adds,
      boolean isLastUp) {
    try {
      File folder = foldersByCache.get(folderId);
      if (folder == null) {
        if (TeamDriveWrapper.isTeamDriveId(folderId)) {
          TeamDriveWrapper teamDriveWrapper =
              new TeamDriveWrapper(drive, config, folderId, s3Regional);
          teamDriveWrapper.getInfo();

          String parentId = teamDriveWrapper.getParentId();
          adds.add(parentId);
          return;
        } else {
          folder = drive.files().get(folderId).setFields("parents,name").execute();
          foldersByCache.put(folderId, folder);
        }
      }
      List<String> parents = folder.getParents();
      String parent = ROOT;
      if (Utils.isListNotNullOrEmpty(parents)) {
        parent = folder.getParents().get(0);
        File f = foldersByCache.get(parent);
        if (f == null) {
          f = drive.files().get(parent).setFields("parents,name").execute();
          foldersByCache.put(parent, f);
        }
        if (f.getName().equals(MY_DRIVE)) {
          parent = ROOT;
        }
      } else if (folder.getName().equals(MY_DRIVE)) {
        parent = Field.MINUS_1.getName();
      }
      if (parent.equals(ROOT)) {
        if (!isLastUp) {
          parent = Field.MINUS_1.getName();
        }
      }
      adds.add(parent);
    } catch (Exception e) {
      log.error("Exception on going up in GDrive", e);
    }
  }

  private void findFileInFolder(
      AWSXRayGDrive drive,
      JsonArray pathFiles,
      String filename,
      String folderId,
      String externalId) {
    try {
      List<File> list;

      if (TeamDriveWrapper.isTeamDriveId(folderId)) {
        AWSXRayGDriveFiles.AWSXRayDriveListRequest listRequest = drive.files().list(null);

        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, folderId, s3Regional);
        listRequest.setDriveId(teamDriveWrapper.getTeamDriveId());
        listRequest.setIncludeItemsFromAllDrives();
        StringBuilder teamDriveQ = new StringBuilder();
        teamDriveQ.append("trashed = false and '");
        if (Utils.isStringNotNullOrEmpty(teamDriveWrapper.getItemId())) {
          teamDriveQ.append(teamDriveWrapper.getItemId());
        } else {
          teamDriveQ.append(teamDriveWrapper.getTeamDriveId());
        }
        teamDriveQ.append("' in parents and name = '").append(filename).append("'");

        listRequest
            .setQ(teamDriveQ.toString())
            .setFields("files(id,modifiedTime,size,lastModifyingUser,name,owners,ownedByMe)");

        list = listRequest.execute().getFiles();
      } else {
        String parentQ = folderId.equals(SHARED_FOLDER)
            ? "and sharedWithMe "
            : "and '" + folderId + "' in parents ";

        String q = ROOT.equals(folderId)
            ? "trashed = false and ((not 'me' in owners and sharedWithMe) or 'root' in parents) and"
                + " name = '" + filename + "'"
            : "trashed = false " + parentQ + "and name = '" + filename + "'";
        list = drive
            .files()
            .list(null)
            .setQ(q)
            .setFields("files(id,modifiedTime,size,lastModifyingUser,name,owners,ownedByMe)")
            .execute()
            .getFiles();
      }

      for (File item : list) {
        pathFiles.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, item.getId()))
            .put(
                Field.OWNER.getName(),
                item.getOwners() != null && !item.getOwners().isEmpty()
                    ? item.getOwners().get(0).getDisplayName()
                    : emptyString)
            .put(Field.IS_OWNER.getName(), item.getOwnedByMe())
            .put(Field.UPDATE_DATE.getName(), item.getModifiedTime().getValue())
            .put(
                Field.CHANGER.getName(),
                item.getLastModifyingUser() != null
                    ? item.getLastModifyingUser().getDisplayName()
                    : emptyString)
            .put(
                Field.CHANGER_EMAIL.getName(),
                item.getLastModifyingUser() != null
                    ? item.getLastModifyingUser().getEmailAddress()
                    : emptyString)
            .put(Field.SIZE.getName(), Utils.humanReadableByteCount(item.getSize()))
            .put(Field.SIZE_VALUE.getName(), item.getSize())
            .put(Field.NAME.getName(), item.getName())
            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }
    } catch (IOException e) {
      log.error("Error on searching for xref in possible folder in GDrive", e);
    }
  }

  public void doDeleteSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
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
      externalId = findExternalId(segment, message, body, storageType);
      if (externalId != null) {
        body.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }

    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      boolean isTeamDriveItem = TeamDriveWrapper.isTeamDriveId(fileId);
      File file;
      List<String> collaboratorsList = null;
      if (isTeamDriveItem) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
        teamDriveWrapper.getInfo();
        file = teamDriveWrapper.getFileInfo();
        // shared drive not support owners concept currently, so no way to find list of
        // collaborators
      } else {
        file = drive
            .files()
            .get(fileId)
            .setFields("id,owners,webContentLink,permissions,name")
            .execute();
        collaboratorsList =
            file.getOwners().stream().map(User::getEmailAddress).collect(Collectors.toList());
      }
      try {
        if (file.getPermissions() != null) {
          for (Permission permission : file.getPermissions()) {
            if (collaboratorsList != null) {
              collaboratorsList.add(permission.getEmailAddress());
            }
          }
        }
      } catch (Exception ignore) {
      }
      if (collaboratorsList != null) {
        collaboratorsList.removeAll(Arrays.asList("", null, " "));
      }
      String link = file.getWebContentLink();

      try {
        final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
        final String externalEmail = ExternalAccounts.getExternalEmail(userId, externalId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            externalId,
            userId,
            storageType,
            externalEmail,
            file.getName(),
            export,
            endTime,
            password);

        if (link != null) {
          newLink.setSharedLink(link);
        }
        if (collaboratorsList != null) {
          newLink.setCollaboratorsList(collaboratorsList);
        }
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

  private void doGetBatchPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    JsonArray ids = message.body().getJsonArray("folderIds");

    if (ids == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FolderidIsRequired"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    Set<String> originalIds = new HashSet<>();
    Map<String, Set<AbstractMap.SimpleEntry<String, String>>> storageFilesMap = new HashMap<>();

    // RG: no need for parallelStream. Not calling API
    ids.forEach(id -> {
      JsonObject json = Utils.parseItemId((String) id, Field.ID.getName());
      if (!originalIds.contains(json.getString(Field.ID.getName()))) {
        originalIds.add(json.getString(Field.ID.getName()));
        Set<AbstractMap.SimpleEntry<String, String>> set =
            storageFilesMap.get(json.getString(Field.EXTERNAL_ID.getName()));
        if (set == null) {
          set = new HashSet<>();
        }
        set.add(new AbstractMap.SimpleEntry<>((String) id, json.getString(Field.ID.getName())));
        storageFilesMap.put(json.getString(Field.EXTERNAL_ID.getName()), set);
      }
    });
    JsonArray result = new JsonArray();
    String sharedWithMeName =
        Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName());
    boolean isTouch = AuthManager.ClientType.TOUCH
        .name()
        .equals(message.body().getString(Field.DEVICE.getName()));

    // RG: TODO: This is not using parallel stream, but probably should
    storageFilesMap.keySet().forEach(externalId -> {
      message.body().put(Field.EXTERNAL_ID.getName(), externalId);
      AWSXRayGDrive drive = connect(segment, message);
      if (drive != null) {
        try {
          String rootId = null;
          if (isTouch) {
            File root = drive
                .files()
                .get(Field.ROOT.getName())
                .setFields(Field.ID.getName())
                .execute();
            rootId = root.getId();
          }
          String finalRootId = rootId;
          storageFilesMap.get(externalId).forEach(entry -> {
            JsonArray path = new JsonArray();
            try {
              path = getPath(
                  drive,
                  externalId,
                  entry.getValue(),
                  new JsonArray(),
                  sharedWithMeName,
                  finalRootId);
            } catch (IOException e) {
              log.error("Error in getting path ", e);
            }
            result.add(new JsonObject()
                .put(Field.ENCAPSULATED_ID.getName(), entry.getKey())
                .put(Field.PATH.getName(), path));
          });
        } catch (Exception e) {
          log.error("Error in getting multiple paths ", e);
        }
      }
    });

    sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), millis);
  }

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.ID.getName());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      String sharedWithMeName =
          Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName());
      boolean isTouch = AuthManager.ClientType.TOUCH
          .name()
          .equals(message.body().getString(Field.DEVICE.getName()));
      String rootId = null;
      if (isTouch) {
        File root = drive
            .files()
            .get(Field.ROOT.getName())
            .setFields(Field.ID.getName())
            .execute();
        rootId = root.getId();
      }
      JsonArray result = getPath(drive, externalId, id, new JsonArray(), sharedWithMeName, rootId);
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  private JsonArray getPath(
      AWSXRayGDrive drive,
      String externalId,
      String id,
      JsonArray result,
      final String sharedWithMe,
      String rootId)
      throws IOException {
    boolean isShared = false;
    if (id.equals(Field.MINUS_1.getName())) {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), "~")
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, Field.MINUS_1.getName()))
          .put(Field.VIEW_ONLY.getName(), false));
    } else if (id.equals(SHARED_FOLDER)) {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), sharedWithMe)
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, SHARED_FOLDER))
          .put(Field.VIEW_ONLY.getName(), true));
    } else if (TeamDriveWrapper.isTeamDriveId(id)) {
      TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, id, s3Regional);
      if (!teamDriveWrapper.isInnerObject()) {
        com.google.api.services.drive.model.Drive teamDrive = drive
            .drives()
            .get(teamDriveWrapper.getTeamDriveId())
            .setFields("name,capabilities")
            .execute();
        result.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, id))
            .put(Field.NAME.getName(), teamDrive.getName())
            .put(Field.VIEW_ONLY.getName(), !teamDrive.getCapabilities().getCanAddChildren()));
      } else {
        File file = drive
            .files()
            .get(teamDriveWrapper.getItemId())
            .setFields("name,parents,capabilities")
            .setSupportsAllDrives(true)
            .execute();
        boolean viewOnly = !file.getCapabilities().getCanEdit();
        String name = file.getName();
        if (Utils.isStringNotNullOrEmpty(rootId) && id.equals(rootId)) {
          name = "~";
          id = Field.MINUS_1.getName();
        }
        result.add(new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, id))
            .put(Field.NAME.getName(), name)
            .put(Field.VIEW_ONLY.getName(), viewOnly));
        if (file.getParents() != null && !file.getParents().isEmpty()) {
          return getPath(
              drive,
              externalId,
              TeamDriveWrapper.getItemId(
                  teamDriveWrapper.getTeamDriveId(), file.getParents().get(0)),
              result,
              sharedWithMe,
              rootId);
        }
      }
    } else {
      File file = drive
          .files()
          .get(id)
          .setFields("name,parents,capabilities,shared")
          .setSupportsAllDrives(true)
          .execute();
      boolean viewOnly = !file.getCapabilities().getCanEdit();
      String name = file.getName();
      if (file.getShared()) {
        isShared = true;
      }

      if (Utils.isStringNotNullOrEmpty(rootId) && id.equals(rootId)) {
        name = "~";
        id = Field.MINUS_1.getName();
      }
      result.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, id))
          .put(Field.NAME.getName(), name)
          .put(Field.VIEW_ONLY.getName(), viewOnly));
      if (file.getParents() != null && !file.getParents().isEmpty()) {
        return getPath(drive, externalId, file.getParents().get(0), result, sharedWithMe, rootId);
      }
    }
    if (!Utils.isStringNotNullOrEmpty(rootId)
        && isShared
        && !SHARED_FOLDER.equals(
            result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), sharedWithMe)
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, SHARED_FOLDER))
          .put(Field.VIEW_ONLY.getName(), true));
    }
    if (!"~".equals(result.getJsonObject(result.size() - 1).getString(Field.NAME.getName()))) {
      result.add(new JsonObject()
          .put(Field.NAME.getName(), "~")
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, Field.MINUS_1.getName()))
          .put(Field.VIEW_ONLY.getName(), false));
    }
    return result;
  }

  public void doEraseFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doErase(message, id);
  }

  public void doEraseFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doErase(message, id);
  }

  private void doErase(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    //        String id = message.body(.getString(Field.FILE_ID.getName());
    //        boolean isFile = id != null;
    //        if (id == null)
    //            id = message.body(.getString(Field.FOLDER_ID.getName());
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      drive.files().delete(id).execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doEraseAll(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    AWSXRayGDrive drive = connect(segment, message);

    if (drive == null) {
      sendError(segment, message, Utils.getLocalizedString(message, "GD1"), HttpStatus.BAD_REQUEST);
      return;
    }

    try {
      drive.files().emptyTrash().execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doMoveFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doMove(message, id);
  }

  public void doMoveFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
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
      parentId = ROOT;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      File file = drive.files().get(id).setFields(Field.PARENTS.getName()).execute();
      if (TeamDriveWrapper.isTeamDriveId(parentId)) {
        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, parentId, s3Regional);
        if (teamDriveWrapper.isInnerObject()) {
          parentId = teamDriveWrapper.getItemId();
        } else {
          parentId = teamDriveWrapper.getTeamDriveId();
        }
      }
      AWSXRayGDriveFiles.AWSXRayDriveUpdateRequest updateRequest =
          drive.files().update(id, new File()).setAddParents(parentId);
      if (Utils.isListNotNullOrEmpty(file.getParents())) {
        updateRequest.setRemoveParents(file.getParents().get(0));
      }
      updateRequest.execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doClone(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    boolean isFile = true;
    String id = message.body().getString(Field.FILE_ID.getName());
    boolean doCopyComments = message.body().getBoolean(Field.COPY_COMMENTS.getName(), false);
    boolean doCopyShare = message.body().getBoolean(Field.COPY_SHARE.getName(), false);
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
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      boolean isTeamDrive = TeamDriveWrapper.isTeamDriveId(id);

      File newFile = drive
          .files()
          .copy(isTeamDrive ? TeamDriveWrapper.getObjectId(id) : id, new File().setName(name))
          .request
          .setSupportsAllDrives(true)
          .setFields(Field.ID.getName())
          .execute();

      String originalNewId = newFile.getId();
      String parent = Field.MINUS_1.getName();
      try {
        File fileInfo = drive
            .files()
            .get(originalNewId)
            .setFields("parents,ownedByMe,teamDriveId,driveId")
            .setSupportsAllDrives(true)
            .execute();
        if (fileInfo.getParents() != null) {
          parent = fileInfo.getParents().get(0);
        }
        boolean isOwner = !isTeamDrive && fileInfo.getOwnedByMe();
        if (isTeamDrive) {
          originalNewId = TeamDriveWrapper.getItemId(fileInfo.getDriveId(), originalNewId);
          parent = TeamDriveWrapper.getItemId(fileInfo.getDriveId(), parent);
        } else if (parent.equals(Field.MINUS_1.getName()) && !isOwner) {
          parent = SHARED_FOLDER;
        }
      } catch (Exception ignore) {
      }
      parent = Utils.getEncapsulatedId(storageType, externalId, parent);

      String newId = originalNewId;
      if (isFile) {
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
        newId = Utils.getEncapsulatedId(storageType, externalId, newId);
      }
      if (doCopyShare) {
        // copy sharing data
        String finalId = id;
        String finalNewId = originalNewId;
        boolean finalIsFile = isFile;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_SHARE.getName())
            .runWithoutSegment(() -> {
              File fileInfo;
              try {
                fileInfo = drive
                    .files()
                    .get(isTeamDrive ? TeamDriveWrapper.getObjectId(finalId) : finalId)
                    .setFields(Field.PERMISSIONS.getName())
                    .setSupportsAllDrives(true)
                    .execute();
              } catch (Exception ex) {
                log.error("Error occurred while getting original file info : " + ex);
                return;
              }
              List<CollaboratorInfo> collaboratorInfoList = getItemCollaborators(fileInfo);
              message
                  .body()
                  .put(Field.FOLDER_ID.getName(), finalNewId)
                  .put(Field.IS_FOLDER.getName(), !finalIsFile);
              collaboratorInfoList.forEach(user -> {
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
              .put(isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(), newId)
              .put(Field.PARENT_ID.getName(), parent),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doCreateShortcut(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();

    AWSXRayGDrive drive = connect(segment, message);

    try {
      String objectId = message.body().getString(Field.OBJECT_ID.getName());
      boolean createInCurrentFolder = message.body().getBoolean("createInCurrentFolder");

      boolean isTeamDrive = TeamDriveWrapper.isTeamDriveId(objectId);
      File fileInfo = drive
          .files()
          .get(isTeamDrive ? TeamDriveWrapper.getObjectId(objectId) : objectId)
          .setSupportsAllDrives(true)
          .setFields(
              "name,mimeType,headRevisionId,createdTime,id,lastModifyingUser,teamDriveId,driveId,parents")
          .execute();

      File shortcut = new File()
          .setName(message.body().getString(Field.NAME.getName()))
          .setMimeType("application/vnd.google-apps.shortcut")
          .setShortcutDetails(new File.ShortcutDetails().setTargetId(fileInfo.getId()));

      List<String> parents = fileInfo.getParents();
      String newParentId;

      try {
        if (createInCurrentFolder
            && parents != null
            && !parents.isEmpty()
            && !parents.get(0).contains(Field.SHARED.getName())) {
          shortcut.setParents(parents);
          newParentId = parents.get(0);
        }
        // file will be created in root
        else {
          newParentId = drive
              .files()
              .get(Field.ROOT.getName())
              .setFields(Field.ID.getName())
              .execute()
              .getId();
        }
      } catch (Exception exception) {
        newParentId = Field.MINUS_1.getName();
      }

      File shortcutFile = drive
          .files()
          .create(shortcut)
          .setFields("parents,ownedByMe,teamDriveId,driveId,id")
          .execute();

      String newId = shortcutFile.getId();

      if (createInCurrentFolder && isTeamDrive) {
        newId = TeamDriveWrapper.getItemId(fileInfo.getDriveId(), newId);
        newParentId = TeamDriveWrapper.getItemId(fileInfo.getDriveId(), newParentId);
      }

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType, message.body().getString(Field.EXTERNAL_ID.getName()), newId))
              .put(
                  Field.PARENT_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType,
                      message.body().getString(Field.EXTERNAL_ID.getName()),
                      newParentId)),
          millis);
    } catch (Exception e) {
      handleException(segment, message, e);
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
    email = email.toLowerCase();

    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      List<Permission> permissions;
      if (TeamDriveWrapper.isTeamDriveId(id)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, id, s3Regional);
        id = teamDriveWrapper.getItemId();
        permissions = teamDriveWrapper.listPermission(id).getPermissions();
      } else {
        File fileInfo =
            drive.files().get(id).setFields("id,permissions,ownedByMe").execute();
        permissions = fileInfo.getPermissions();
      }

      if (
      /*!fileInfo.getOwnedByMe() && */ permissions != null) {
        for (Permission permission : permissions) {
          if (permission.getEmailAddress() != null
              && permission.getEmailAddress().equalsIgnoreCase(email)) {
            if (permission.getRole().equals(OWNER)) {
              if (reply) {
                sendError(
                    segment,
                    message,
                    Utils.getLocalizedString(message, "UnableToShareWithOwner"),
                    HttpStatus.BAD_REQUEST);
              }
              return;
            }
            drive.updatePermission(
                id,
                permission.getId(),
                new Permission().setId(permission.getId()).setRole(role));
            if (reply) {
              sendOK(segment, message, mills);
            }
            return;
          }
        }
      }
      drive.createPermission(
          id, new Permission().setRole(role).setEmailAddress(email).setType(Field.USER.getName()));
      if (reply) {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      if (reply) {
        handleException(segment, message, e);
      }
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (id == null || name == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    boolean selfDeShare = name.equals(userId);
    if (drive == null) {
      return;
    }
    try {
      String userEmail = message.body().getString(Field.USER_EMAIL.getName());

      List<Permission> permissions;
      if (TeamDriveWrapper.isTeamDriveId(id)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, id, s3Regional);
        id = teamDriveWrapper.getItemId();
        permissions = teamDriveWrapper.listPermission(id).getPermissions();
      } else {
        File fileInfo = drive.files().get(id).setFields("id,permissions").execute();
        permissions = fileInfo.getPermissions();
      }
      if (!selfDeShare) {
        drive.deletePermission(id, name);
      } else {
        if (permissions != null) {
          for (Permission permission : permissions) {
            if (permission.getEmailAddress().equals(userEmail)) {
              drive.deletePermission(id, permission.getId());
              break;
            }
          }
        }
      }

      sendOK(segment, message, new JsonObject().put(Field.USERNAME.getName(), userEmail), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doRestore(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.FILE_ID.getName());
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
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
    // cannot find connection for the file
    AWSXRayGDrive drive = connect(segment, message, jsonObject, false);
    if (drive == null) {
      return;
    }
    try {
      drive.files().update(id, new File().setTrashed(false)).execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
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
    AWSXRayGDrive drive;
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    drive = connect(item);
    final String originalFileId = fileId;
    if (TeamDriveWrapper.isTeamDriveId(fileId)) {
      TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
      fileId = teamDriveWrapper.getItemId();
    }
    if (drive != null) {
      try {
        File fileInfo = drive
            .files()
            .get(fileId)
            .setFields("id,name,owners,permissions,shared,trashed,headRevisionId,"
                + "lastModifyingUser,modifiedTime")
            .execute();
        String versionId = fileInfo.getHeadRevisionId();
        String thumbnailName = ThumbnailsManager.getThumbnailName(
            StorageType.getShort(storageType), fileInfo.getId(), versionId);

        JsonObject json = new JsonObject()
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(
                    StorageType.getShort(storageType), externalId, originalFileId))
            .put(Field.WS_ID.getName(), originalFileId)
            .put(
                Field.SHARE.getName(),
                new JsonObject()
                    .put(Field.VIEWER.getName(), new JsonArray())
                    .put(Field.EDITOR.getName(), new JsonArray()))
            .put(Field.VIEW_ONLY.getName(), true)
            .put(Field.PUBLIC.getName(), true)
            .put(Field.IS_OWNER.getName(), false)
            .put(Field.DELETED.getName(), fileInfo.getTrashed())
            .put(
                Field.LINK.getName(),
                config.getProperties().getUrl() + "file/"
                    + Utils.getEncapsulatedId(
                        StorageType.getShort(storageType), externalId, originalFileId)
                    + "?token="
                    + token) // NON-NLS
            .put(Field.FILE_NAME.getName(), fileInfo.getName())
            .put(Field.VERSION_ID.getName(), versionId)
            .put(Field.CREATOR_ID.getName(), creatorId)
            .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
            .put(
                Field.THUMBNAIL.getName(),
                ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
            .put(Field.EXPORT.getName(), export)
            .put(
                Field.UPDATE_DATE.getName(),
                fileInfo.getModifiedTime() != null ? fileInfo.getModifiedTime().getValue() : 0L)
            .put(
                Field.CHANGER.getName(),
                fileInfo.getLastModifyingUser() != null
                    ? fileInfo.getLastModifyingUser().getDisplayName()
                    : null)
            .put(
                Field.CHANGER_EMAIL.getName(),
                fileInfo.getLastModifyingUser() != null
                    ? fileInfo.getLastModifyingUser().getEmailAddress()
                    : emptyString);
        sendOK(segment, message, json, mills);
        return;
      } catch (Exception ignored) {
      }
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          originalFileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }

    boolean isTeamDrive = TeamDriveWrapper.isTeamDriveId(fileId);
    try {
      if (isTeamDrive) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
        fileId = teamDriveWrapper.getItemId();
      }
      File file = drive.files().get(fileId).setFields("id,headRevisionId,name").execute();
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
                          .put(Field.FILE_ID.getName(), file.getId())
                          .put(Field.VERSION_ID.getName(), file.getHeadRevisionId())
                          .put(Field.EXT.getName(), Extensions.getExtension(file.getName()))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          StorageType.getShort(storageType), file.getId(), file.getHeadRevisionId());
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(
                      file.getId(), storageType.name(), file.getHeadRevisionId(), true, true))
              .put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
              /// *config.getProperties().getUrl() +
              // */"/thumbnails/" + thumbnailName)
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true)),
          mills); // "/geomdata/" + thumbnailName + ".json"), mills);
    } catch (IOException e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public <T> boolean checkFile(Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      AWSXRayGDrive drive = connect(segment, message, json, false);
      if (TeamDriveWrapper.isTeamDriveId(fileId)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
        fileId = teamDriveWrapper.getItemId();
      }
      if (drive != null) {
        try {
          File fileInfo = drive
              .files()
              .get(fileId) // id
              .setFields("id, trashed")
              .execute();
          if (fileInfo != null && !fileInfo.getTrashed()) {
            return true;
          }
        } catch (Exception e) {
          return false;
        }
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
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String email = jsonObject.getString(Field.EMAIL.getName());
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
    // cannot find connection for the file

    AWSXRayGDrive drive = connect(segment, message, jsonObject, false);
    if (drive == null) {
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
      boolean full = true;
      if (jsonObject.containsKey(Field.FULL.getName())
          && jsonObject.getString(Field.FULL.getName()) != null) {
        full = Boolean.parseBoolean(jsonObject.getString(Field.FULL.getName()));
      }
      JsonObject json;
      if (!isFile && id.equals(ROOT_FOLDER_ID)) {
        json = getRootFolderInfo(
            storageType,
            externalId,
            new ObjectPermissions().setPermissionAccess(AccessType.canMoveFrom, true));
      } else if (id.equals(SHARED_FOLDER)) {
        String sharedWithMeName =
            Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName());
        json = getSharedFolderInfo(storageType, externalId, sharedWithMeName);
      } else if (TeamDriveWrapper.isTeamDriveId(id)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, id, s3Regional);
        teamDriveWrapper.getInfo();
        json = teamDriveWrapper.toJson(full, true, isAdmin, externalId, userId, email);
      } else {
        File fileInfo = drive
            .files()
            .get(id) // id,name,owners,permissions,shared,parents,size
            .setFields("mimeType,headRevisionId,createdTime,id,lastModifyingUser,"
                + "modifiedTime,name,owners,ownedByMe,parents,permissions,"
                + "shared,size,trashed,capabilities,webContentLink,"
                + "teamDriveId,shortcutDetails")
            .execute();

        if (fileInfo.getMimeType().equals("application/vnd.google-apps.shortcut")) {
          json = GDriveObjectAdaptor.makeShortcutEntity(fileInfo, externalId).toJson();
        } else {
          json =
              getFileJson(fileInfo, !isFile, full, true, isAdmin, externalId, userId, false, false);
        }
      }
      sendOK(segment, message, json, mills);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      handleException(segment, message, e);
    }
  }

  // Transforms GDrive's Revision into out VersionInfo
  private VersionInfo getVersionInfo(String fileId, Revision revision, String lastRevisionId) {
    User modifier = revision.getLastModifyingUser();
    VersionModifier versionModifier = new VersionModifier();
    if (modifier != null) {
      versionModifier.setCurrentUser(modifier.getMe());
      versionModifier.setEmail(modifier.getEmailAddress());
      versionModifier.setName(modifier.getDisplayName());
      versionModifier.setPhoto(modifier.getPhotoLink());
    }
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(!revision.getId().equals(lastRevisionId));
    versionPermissions.setCanDelete(!revision.getId().equals(lastRevisionId));
    versionPermissions.setCanRename(false);
    versionPermissions.setDownloadable(true);
    VersionInfo versionInfo =
        new VersionInfo(revision.getId(), revision.getModifiedTime().getValue(), null);
    if (revision.getSize() != null) {
      versionInfo.setSize(revision.getSize());
    }
    versionInfo.setModifier(versionModifier);
    versionInfo.setPermissions(versionPermissions);
    if (Utils.isStringNotNullOrEmpty(revision.getMd5Checksum())) {
      versionInfo.setHash(revision.getMd5Checksum());
    }
    try {
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(storageType, fileId, revision.getId());
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (fileId == null || versionId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      drive.revisions().delete(fileId, versionId).execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      if (TeamDriveWrapper.isTeamDriveId(fileId)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
        fileId = teamDriveWrapper.getItemId();
      }
      List<JsonObject> result = new ArrayList<>();
      List<Revision> revisions = drive
          .revisions()
          .list(fileId)
          .setFields("revisions(id,lastModifyingUser,modifiedTime,size,md5Checksum)")
          .execute()
          .getRevisions();
      String lastRevisionId = revisions.get(revisions.size() - 1).getId();
      String finalFileId = fileId;
      revisions.forEach(revision ->
          result.add(getVersionInfo(finalFileId, revision, lastRevisionId).toJson()));
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              File file =
                  drive.files().get(finalFileId).setFields(Field.NAME.getName()).execute();
              if (Utils.isStringNotNullOrEmpty(file.getName())) {
                ext = Extensions.getExtension(file.getName());
              }
            } catch (IOException ex) {
              log.warn("GDRIVE get versions: Couldn't get object info to get extension.", ex);
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            revisions.forEach(revision -> requiredVersions.add(new JsonObject()
                .put(Field.FILE_ID.getName(), finalFileId)
                .put(Field.VERSION_ID.getName(), revision.getId())
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

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      File file = drive.files().get(fileId).setFields("headRevisionId").execute();
      sendOK(
          segment,
          message,
          new JsonObject().put(Field.VERSION_ID.getName(), file.getHeadRevisionId()),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doUploadVersion(Message<Buffer> message) {
    // For GDrive this is basically upload file
    // see https://support.google.com/a/users/answer/9308971?hl=en
    // This is a simplified version of doUploadFile
    // We can do it later if needed
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();

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
    AWSXRayGDrive drive = connect(segment, message, body, false);
    if (drive == null) {
      return;
    }

    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      File file = drive.files().get(fileId).setFields(Field.NAME.getName()).execute();
      drive
          .files()
          .update(fileId, file, new InputStreamContent(emptyString, stream))
          .execute();
      List<Revision> revisions = drive
          .revisions()
          .list(fileId)
          .setFields("revisions(id,lastModifyingUser,size,md5Checksum,modifiedTime)")
          .execute()
          .getRevisions();
      Revision lastRevision = revisions.get(revisions.size() - 1);
      VersionInfo versionInfo = getVersionInfo(fileId, lastRevision, lastRevision.getId());
      String versionId = versionInfo.getId();
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
                          .put(Field.EXT.getName(), Extensions.getExtension(file.getName())))));

      sendOK(segment, message, versionInfo.toJson(), mills);

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
      handleException(segment, message, ex);
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
    AWSXRayGDrive drive = connect(segment, message);
    try {
      // In GDrive - there's no "promote version" functionality for non-Google (doc, sheet, etc.)
      // files
      // Official doc suggests to just "upload new version"
      // see https://support.google.com/a/users/answer/9308971?hl=en
      ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
      drive.revisions().get(fileId, versionId).executeMediaAndDownloadTo(arrayStream);
      File file = drive
          .files()
          .update(
              fileId,
              drive.files().get(fileId).setFields(Field.NAME.getName()).execute(),
              new ByteArrayContent(emptyString, arrayStream.toByteArray()))
          .execute();

      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.VERSION_ID.getName(), file.getHeadRevisionId()),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
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
    AWSXRayGDrive drive;
    boolean success = false;
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    drive = connect(item);
    if (drive != null) {
      try {
        String gdriveNativeFileId = fileId;
        if (TeamDriveWrapper.isTeamDriveId(fileId)) {
          gdriveNativeFileId = TeamDriveWrapper.getObjectId(fileId);
        }
        File file = drive
            .files()
            .get(gdriveNativeFileId)
            .setFields("mimeType,headRevisionId,modifiedTime,name,lastModifyingUser,size,"
                + "ownedByMe,webContentLink")
            .execute();
        String versionId = file.getHeadRevisionId();
        if (returnDownloadUrl) {
          String downloadUrl = "https://www.googleapis.com/drive/v3/files/" + fileId
              + "?alt=media&supportsAllDrives=true";
          sendDownloadUrl(
              segment,
              message,
              downloadUrl,
              file.getSize() != null ? file.getSize() : 0,
              versionId,
              getAccessToken(item),
              true,
              stream,
              mills);
          return;
        }
        drive.files().get(gdriveNativeFileId).executeMediaAndDownloadTo(stream);
        stream.close();
        finishGetFile(
            message,
            null,
            null,
            stream.toByteArray(),
            storageType,
            file.getName(),
            versionId,
            downloadToken);
        success = true;
      } catch (Exception e) {
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
        log.error("Error on getting google file's data by token", e);
      }
    }
    if (!success && sharedLink != null) {
      if (returnDownloadUrl) {
        sendDownloadUrl(segment, message, sharedLink, null, null, null, true, stream, mills);
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
        finishGetFile(
            message,
            null,
            null,
            stream.toByteArray(),
            storageType,
            "Unknown",
            Field.LATEST.getName(),
            downloadToken);
        success = true;
      } catch (Exception e) {
        DownloadRequests.setRequestData(
            downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
        log.error("Error on getting google file's data via url by token", e);
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

  public void doGetFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = message.body().getString(Field.VER_ID.getName());
    Boolean latest = message.body().getBoolean(Field.LATEST.getName());
    if (latest == null) {
      latest = false;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    Integer start = message.body().getInteger(Field.START.getName()),
        end = message.body().getInteger(Field.END.getName());
    if (fileId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      String name;
      long size;
      boolean isTeamDriveItem = false;
      TeamDriveWrapper teamDriveWrapper = null;
      if (TeamDriveWrapper.isTeamDriveId(fileId)) {
        teamDriveWrapper = new TeamDriveWrapper(drive, config, fileId, s3Regional);
        fileId = teamDriveWrapper.getItemId();
        isTeamDriveItem = true;
      }
      if (latest || versionId == null) {
        File file;
        if (isTeamDriveItem) {
          teamDriveWrapper.getInfo();
          file = teamDriveWrapper.getFileInfo();
        } else {
          file = drive
              .files()
              .get(fileId)
              .setFields("mimeType,headRevisionId,size,modifiedTime,name,lastModifyingUser,"
                  + "ownedByMe,webContentLink")
              .execute();
        }
        if (file == null) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FileNotFound"),
              HttpStatus.NOT_FOUND);
          return;
        }
        versionId = file.getHeadRevisionId();
        size = file.getSize() != null ? file.getSize() : 0;
        if (returnDownloadUrl) {
          String downloadUrl = "https://www.googleapis.com/drive/v3/files/" + fileId
              + "?alt=media&supportsAllDrives=true";
          sendDownloadUrl(
              segment,
              message,
              downloadUrl,
              size,
              versionId,
              message.body().getString(Field.ACCESS_TOKEN.getName()),
              true,
              stream,
              mills);
          return;
        }
        name = file.getName();
        if (!file.getMimeType().startsWith("application/vnd.google-apps")) {
          if (size > 0) {
            drive.files().get(fileId).setSupportsAllDrives(true).executeMediaAndDownloadTo(stream);
          }
        } else {
          String mimeType = translateMimeType(file.getMimeType());
          if (mimeType.isEmpty()) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "unsupportedType"),
                HttpStatus.FORBIDDEN);
            return;
          }
          drive.files().export(fileId, mimeType).executeMediaAndDownloadTo(stream);
        }
      } else {
        if (returnDownloadUrl) {
          String downloadUrl = "https://www.googleapis.com/drive/v3/files/" + fileId + "/revisions/"
              + versionId + "?alt=media&supportsAllDrives=true";
          sendDownloadUrl(
              segment,
              message,
              downloadUrl,
              null,
              versionId,
              message.body().getString(Field.ACCESS_TOKEN.getName()),
              true,
              stream,
              mills);
          return;
        }
        drive
            .revisions()
            .get(fileId, versionId)
            .set("alt", "media")
            .executeMediaAndDownloadTo(stream);
        try {
          Revision revision = drive
              .revisions()
              .get(fileId, versionId)
              .setFields("size,modifiedTime,lastModifyingUser,originalFilename")
              .execute();
          name = revision.getOriginalFilename();
        } catch (GoogleJsonResponseException googleJsonResponseException) {
          // not a big issue - mostly happens for teamdrive files.
          // let's get info from regular file
          log.warn(googleJsonResponseException.getMessage());
          File file = drive
              .files()
              .get(fileId)
              .setFields(
                  "mimeType,headRevisionId,size,modifiedTime,name,lastModifyingUser," + "ownedByMe")
              .execute();
          name = file.getName();
        }
      }
      stream.close();
      finishGetFile(
          message, start, end, stream.toByteArray(), storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      handleException(segment, message, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  // based on gdrive api method
  public void doGetAllFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
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
    // cannot find connection for the file

    AWSXRayGDrive drive = connect(segment, message, jsonObject, false);
    if (drive == null) {
      return;
    }
    try {
      FileList fileInfo = drive
          .files()
          .list(null)
          .setQ("mimeType != 'application/vnd.google-apps.folder' and mimeType"
              + " != 'application/vnd.google-apps.map' and mimeType != "
              + "'application/vnd.google-apps.document' and mimeType != "
              + "'application/vnd.google-apps.spreadsheet'")
          .execute();

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), fileInfo), millis);
    } catch (Exception e) {
      // if exception happened - we don't really care why as far as we use this for RF validation
      // only
      // so return true anyway
      // https://graebert.atlassian.net/browse/XENON-30048
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", e.getMessage()),
          millis);
    }
  }

  private String translateMimeType(String mimeType) {
    switch (mimeType) {
      case "application/vnd.google-apps.document":
        return "application/vnd.oasis.opendocument.text";
      case "application/vnd.google-apps.drawing":
        return "image/png";
      case "application/vnd.google-apps.presentation":
        return "application/vnd.oasis.opendocument.presentation";
      case "application/vnd.google-apps.spreadsheet":
        return "application/x-vnd.oasis.opendocument.spreadsheet";
      case "application/vnd.google-apps.script":
        return "application/vnd.google-apps.script+json";
      default:
        return emptyString;
    }
  }

  public void doDeleteFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doDelete(message, id);
  }

  public void doDeleteFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doDelete(message, id);
  }

  private void doDelete(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    boolean trashed = message.body().getBoolean("trashed", true);
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      File updatedItem = new File();
      if (trashed) {
        updatedItem.setTrashed(true);
      } else {
        updatedItem.setParents(null);
      }
      drive.files().update(id, updatedItem).execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doRenameFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doRename(message, id);
  }

  public void doRenameFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doRename(message, id);
  }

  private void doRename(Message<JsonObject> message, String id) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
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
    if (id == null || name == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      drive.files().update(id, new File().setName(name)).execute();
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String parentId = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
    String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }
    if (parentId == null) {
      return;
    }
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    try {
      boolean isTeamDrive = TeamDriveWrapper.isTeamDriveId(parentId);
      File folder = new File().setName(name).setMimeType(APPLICATION_VND_GOOGLE_APPS_FOLDER);

      if (isTeamDrive) {
        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, parentId, s3Regional);
        folder.setDriveId(teamDriveWrapper.getTeamDriveId());
        if (teamDriveWrapper.getItemId() != null) {
          folder.setParents(Collections.singletonList(teamDriveWrapper.getItemId()));
        } else {
          folder.setParents(Collections.singletonList(teamDriveWrapper.getTeamDriveId()));
        }
      } else if (!Field.MINUS_1.getName().equals(parentId)) {
        folder.setParents(Collections.singletonList(parentId));
      }
      File res = drive.files().create(folder).execute();
      String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      String resultId = res.getId();
      if (isTeamDrive) {
        TeamDriveWrapper teamDriveWrapper =
            new TeamDriveWrapper(drive, config, parentId, s3Regional);
        resultId = TeamDriveWrapper.getItemId(teamDriveWrapper.getTeamDriveId(), resultId);
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, resultId)),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
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
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
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
          File file;

          try {
            if (TeamDriveWrapper.isTeamDriveId(objectId)) {
              TeamDriveWrapper teamDriveWrapper =
                  new TeamDriveWrapper(drive, config, objectId, s3Regional);
              teamDriveWrapper.getInfo();
              file = teamDriveWrapper.getFileInfo();
            } else {
              file = drive
                  .files()
                  .get(objectId)
                  .setFields("mimeType,headRevisionId,createdTime,id,lastModifyingUser,"
                      + "modifiedTime,name,owners,ownedByMe,parents,permissions,"
                      + "shared,size,trashed,capabilities,webContentLink,"
                      + Field.TEAM_DRIVE_ID.getName())
                  .execute();
            }
          } catch (Exception e) {
            log.warn("[GDRIVE] Error in getting file for objectId -" + objectId + " : " + e);
            excludeFileFromRequest(request, objectId, ExcludeReason.NotFound);
            return null;
          }
          if (isFolder) {
            String name = file.getName();
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(
                stream, drive, objectId, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = file.getSize() != null ? file.getSize() : 0;
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, file.getName(), ExcludeReason.Large);
              return null;
            }
            addZipEntry(
                stream,
                file,
                drive,
                file.getName(),
                filter,
                filteredFileNames,
                emptyString,
                fileNames);
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
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
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
        Entity standaloneSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "GDriveZipFolderSegment");
        zipFolder(
            stream, drive, folderId, filter, recursive, emptyString, new HashSet<>(), 0, request);
        XRayManager.endSegment(standaloneSegment);
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
      AWSXRayGDrive drive,
      String folderId,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    @NonNls String q;
    if (TeamDriveWrapper.isTeamDriveId(folderId)) {
      TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, folderId, s3Regional);
      StringBuilder teamDriveQ = new StringBuilder();
      teamDriveQ.append("trashed = false and '");
      if (Utils.isStringNotNullOrEmpty(teamDriveWrapper.getItemId())) {
        teamDriveQ.append(teamDriveWrapper.getItemId());
      } else {
        teamDriveQ.append(teamDriveWrapper.getTeamDriveId());
      }
      teamDriveQ.append("' in parents ");
      q = teamDriveQ.toString();
    } else {
      q = "'" + folderId + "' in parents and trashed = false";
    }
    List<File> files = drive
        .files()
        .list(null)
        .setQ(q)
        .setFields("files(mimeType,id,name,size)")
        .execute()
        .getFiles();
    if (files.isEmpty()) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    for (File file : files) {
      if (APPLICATION_VND_GOOGLE_APPS_SHORTCUT.equals(file.getMimeType())) {
        continue;
      }

      String name = file.getName();
      String properPath = path.isEmpty() ? path : path + java.io.File.separator;
      if (APPLICATION_VND_GOOGLE_APPS_FOLDER.equals(file.getMimeType()) && recursive) {
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          if (!(properPath + name).trim().isEmpty()) {
            zipFolder(
                stream,
                drive,
                file.getId(),
                filter,
                true,
                properPath + name,
                filteredFileNames,
                recursionDepth,
                request);
          } else {
            log.warn("[ZIP] empty name provided: " + properPath + name);
          }
        } else {
          log.warn(
              "Zip folder recursion exceeds the limit for path " + path + " in " + storageType);
        }
      } else {
        long fileSize = file.getSize() != null ? file.getSize() : 0;
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, file.getName(), ExcludeReason.Large);
          return;
        }
        addZipEntry(stream, file, drive, name, filter, filteredFileNames, properPath, fileNames);
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      File file,
      AWSXRayGDrive drive,
      String name,
      String filter,
      Set<String> filteredFileNames,
      String properPath,
      Set<String> fileNames)
      throws IOException {
    long size = file.getSize() != null ? file.getSize() : 0;
    ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
    if (!file.getMimeType().startsWith("application/vnd.google-apps")) {
      if (size > 0) {
        drive.files().get(file.getId()).executeMediaAndDownloadTo(arrayStream);
      }
    } else {
      String mimeType = translateMimeType(file.getMimeType());
      if (mimeType.isEmpty()) {
        arrayStream.close();
        return;
      }
      drive.files().export(file.getId(), mimeType).executeMediaAndDownloadTo(arrayStream);
    }
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
    String p = jsonObject.getString(Field.PAGINATION.getName());
    boolean pagination = p == null || Boolean.parseBoolean(p);
    String device = jsonObject.getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    boolean isTouch = AuthManager.ClientType.TOUCH.name().equals(device);
    boolean useNewStructure = jsonObject.getBoolean(Field.USE_NEW_STRUCTURE.getName(), false);
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    JsonArray thumbnail = new JsonArray();
    List<JsonObject> foldersJson = new ArrayList<>();
    List<ShortcutEntity> shortcuts = new ArrayList<>();
    Map<String, JsonObject> filesJson = new Hashtable<>();
    Map<String, String> possibleIdsToIds = new HashMap<>();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    AWSXRayGDrive drive = connect(segment, message);
    if (drive == null) {
      return;
    }
    JsonObject messageBody = message.body();
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;
    try {
      @NonNls StringBuilder q = new StringBuilder();

      if (!trash) {
        q.append("trashed = false").append(" and '").append(folderId).append("' in parents ");
      } else {
        List<File> folders = drive
            .files()
            .listTrash(folderContentPageSize)
            .setQ(
                "trashed = true and mimeType = 'application/vnd" + ".google" + "-apps" + ".folder'")
            .setFields("files(id)")
            .execute()
            .getFiles();
        q = new StringBuilder("trashed = true");
        if (!folders.isEmpty()) {
          for (File folder : folders) {
            q.append(" and not '").append(folder.getId()).append("' in parents");
          }
        }
      }

      do {
        AWSXRayGDriveFiles.AWSXRayDriveListRequest list;
        if (trash) {
          list = drive.files().listTrash(folderContentPageSize);
        } else {
          list = drive.files().list(folderContentPageSize);
        }
        if (folderId.equals(ROOT_FOLDER_ID) && !trash && (!isTouch || useNewStructure)) {
          File root = drive
              .files()
              .get(Field.ROOT.getName())
              .setFields("id,name,kind,capabilities")
              .execute();
          JsonArray listOfDrives = new JsonArray();
          DriveList driveList = null;
          try {
            Drive.Drives.List drivesListRequest = drive
                .drives()
                .list()
                .setFields("nextPageToken, drives(id,kind,name,capabilities)")
                .setPageSize(folderContentPageSize);
            if (pageToken != null) {
              XRayEntityUtils.putMetadata(segment, XrayField.PAGE_TOKEN, pageToken);
              XRayEntityUtils.putMetadata(segment, XrayField.FOLDER_ID, folderId);
              XRayEntityUtils.putMetadata(segment, XrayField.EXTERNAL_ID, externalId);
              XRayEntityUtils.putMetadata(segment, XrayField.QUERY, q.toString());
              drivesListRequest.setPageToken(pageToken);
            }
            driveList = drivesListRequest.execute();

            for (com.google.api.services.drive.model.Drive teamDrive : driveList.getDrives()) {
              ObjectPermissions tdPermissions = new ObjectPermissions();
              tdPermissions
                  .setAllTo(false)
                  .setBatchTo(
                      List.of(AccessType.canCreateFiles, AccessType.canCreateFolders),
                      teamDrive.getCapabilities().getCanAddChildren());
              listOfDrives.add(new JsonObject()
                  .put(
                      Field.ENCAPSULATED_ID.getName(),
                      Utils.getEncapsulatedId(
                          storageType,
                          externalId,
                          TeamDriveWrapper.getItemId(teamDrive.getId(), null)))
                  .put(Field.NAME.getName(), teamDrive.getName())
                  .put("kind", teamDrive.getKind())
                  .put(
                      "capabilities", new JsonObject(teamDrive.getCapabilities().toString()))
                  .put(Field.PERMISSIONS.getName(), tdPermissions.toJson())
                  .put(Field.IS_OWNER.getName(), false));
            }
          } catch (Exception e) {
            log.error("GDrive - exception on looking for drives", e);
            // if exception happened - let's try to still show "my files"/"shared with me"
          }

          try {
            ObjectPermissions rootPermissions = new ObjectPermissions()
                .setAllTo(false)
                .setBatchTo(
                    List.of(
                        AccessType.canCreateFiles,
                        AccessType.canCreateFolders,
                        AccessType.canMoveFrom,
                        AccessType.canMoveTo),
                    true);
            listOfDrives.add(new JsonObject()
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(storageType, externalId, root.getId()))
                .put(Field.NAME.getName(), root.getName())
                .put("kind", root.getKind())
                .put("capabilities", new JsonObject(root.getCapabilities().toString()))
                .put(Field.PERMISSIONS.getName(), rootPermissions.toJson())
                .put(Field.IS_OWNER.getName(), true)
                .put(Field.IS_ROOT.getName(), true));

            listOfDrives.add(getSharedFolderInfo(
                storageType,
                externalId,
                Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName())));

            JsonObject response = new JsonObject()
                .put(
                    Field.RESULTS.getName(),
                    new JsonObject()
                        .put(Field.FILES.getName(), new JsonArray())
                        .put(Field.FOLDERS.getName(), listOfDrives))
                .put("number", listOfDrives.size())
                .put(
                    Field.PAGE_TOKEN.getName(),
                    driveList != null ? driveList.getNextPageToken() : null)
                .put(Field.FULL.getName(), full)
                .put(
                    Field.FILE_FILTER.getName(),
                    message.body().getString(Field.FILE_FILTER.getName()));
            sendOK(segment, message, response, mills);
            return;
          } catch (Exception exception) {
            log.error("GDrive root - unprocessable exception", exception);
            sendError(segment, message, HttpStatus.INTERNAL_SERVER_ERROR, "GD1");
          }
        } else if (folderId.equals(ROOT_FOLDER_ID) && isTouch && !useNewStructure) {
          q = new StringBuilder("trashed = false");
          q.append(" and ((not 'me' in owners and sharedWithMe) or 'root' in parents)");
          list.setQ(q.toString());
        } else if (TeamDriveWrapper.isTeamDriveId(folderId)) {
          TeamDriveWrapper teamDriveWrapper =
              new TeamDriveWrapper(drive, config, folderId, s3Regional);
          list.setDriveId(teamDriveWrapper.getTeamDriveId());
          list.setIncludeItemsFromAllDrives();
          StringBuilder teamDriveQ = new StringBuilder();
          teamDriveQ.append("trashed = false and '");
          if (Utils.isStringNotNullOrEmpty(teamDriveWrapper.getItemId())) {
            teamDriveQ.append(teamDriveWrapper.getItemId());
          } else {
            teamDriveQ.append(teamDriveWrapper.getTeamDriveId());
          }
          teamDriveQ.append("' in parents ");
          list.setQ(teamDriveQ.toString());
        } else if (folderId.equals(SHARED_FOLDER)) {
          list.setQ("sharedWithMe");
        } else {
          if (!trash) {
            File folder = drive
                .files()
                .get(folderId)
                .setFields("id,name,parents,ownedByMe")
                .execute();
            if (Objects.nonNull(folder)) {
              if (folder.getName().equals(MY_DRIVE)
                  && folder.getOwnedByMe()
                  && Objects.isNull(folder.getParents())) {
                // root folder ("My Drive")
                q.append("and 'me' in owners");
              }
            }
          }
          list.setQ(q.toString());
        }

        list.setFields("nextPageToken, " + "files(mimeType,headRevisionId,createdTime,id,"
                + "lastModifyingUser,"
                + "modifiedTime,name,owners,ownedByMe,parents,permissions,shared,size,"
                + "trashed,capabilities,webContentLink,teamDriveId,shortcutDetails)")
            .setOrderBy("folder,name_natural");

        if (pageToken != null) {
          XRayEntityUtils.putMetadata(segment, XrayField.PAGE_TOKEN, pageToken);
          XRayEntityUtils.putMetadata(segment, XrayField.FOLDER_ID, folderId);
          XRayEntityUtils.putMetadata(segment, XrayField.EXTERNAL_ID, externalId);
          XRayEntityUtils.putMetadata(segment, XrayField.QUERY, q.toString());
          list.setPageToken(pageToken);
        }

        FileList fileList = null;
        try {
          fileList = list.execute();
        } catch (GoogleJsonResponseException gex) {
          // temporary change
          if (gex.getStatusCode() == HttpStatusCodes.STATUS_CODE_SERVER_ERROR
              && folderId.equals(ROOT_FOLDER_ID)
              && isTouch) {
            q = new StringBuilder("trashed = false");
            q.append(" and ('me' in owners and 'root' in parents)"); // only owned items
            list.setQ(q.toString());
            try {
              fileList = list.execute();
            } catch (GoogleJsonResponseException gexCheck) {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, gexCheck.getMessage()),
                  HttpStatus.BAD_REQUEST);
              return;
            }
          }
        }

        pageToken = Objects.nonNull(fileList) ? fileList.getNextPageToken() : null;
        List<File> files = Objects.nonNull(fileList) ? fileList.getFiles() : new ArrayList<>();
        // RG: removed parallelStream, as we are not calling an API and using global collections
        Boolean finalFull = full;
        files.forEach(file -> {
          if (filterMimetype(file.getMimeType())) {

            // handle shortcut
            if (file.getMimeType().equals(APPLICATION_VND_GOOGLE_APPS_SHORTCUT)) {
              try {
                shortcuts.add(GDriveObjectAdaptor.makeShortcutEntity(file, externalId));
              } catch (Exception e) {
                log.error("[GDrive] Error occurred while adding the shortcut entity " + e);
              }
              return;
            }

            if (APPLICATION_VND_GOOGLE_APPS_FOLDER.equals(file.getMimeType())) {
              JsonObject json = getFileJson(
                  file, true, finalFull, false, isAdmin, externalId, userId, false, false);
              foldersJson.add(json);
            } else {
              String filename = file.getName();
              if (Extensions.isValidExtension(extensions, filename)) {
                boolean createThumbnail = canCreateThumbnails;
                if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
                  if (file.getId() != null && !possibleIdsToIds.containsKey(file.getId())) {
                    if (Utils.isStringNotNullOrEmpty(file.getTeamDriveId())) {
                      String teamDriveFileId =
                          TeamDriveWrapper.getItemId(file.getTeamDriveId(), file.getId());
                      possibleIdsToIds.put(teamDriveFileId, teamDriveFileId);
                    } else {
                      possibleIdsToIds.put(file.getId(), file.getId());
                    }
                    thumbnail.add(new JsonObject()
                        .put(Field.FILE_ID.getName(), file.getId())
                        .put(Field.VERSION_ID.getName(), file.getHeadRevisionId())
                        .put(Field.EXT.getName(), Extensions.getExtension(filename)));
                  }
                } else {
                  createThumbnail = false;
                }

                JsonObject json = getFileJson(
                    file, false, true, false, isAdmin, externalId, userId, force, createThumbnail);
                if (file.getId() != null) {
                  filesJson.put(file.getId(), json);
                }
              }
            }
          }
        });
      } while (pageToken != null
          && (!pagination || (foldersJson.isEmpty() && filesJson.isEmpty())));

      if (!trash) {
        log.info("[THUMBNAILS] [GD] Will request thumbnails. CanCreateThumbnails:"
            + canCreateThumbnails + " thumbnails list: " + thumbnail.encode());
        if (canCreateThumbnails && !thumbnail.isEmpty()) {
          createThumbnails(
              segment,
              thumbnail,
              messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
        }

        if (full && !possibleIdsToIds.isEmpty()) {
          Map<String, JsonObject> newSharedLinksResponse =
              PublicLink.getSharedLinks(config, segment, userId, externalId, possibleIdsToIds);
          for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
            String key = fileData.getKey();
            if (TeamDriveWrapper.isTeamDriveId(fileData.getKey())) {
              try {
                key = TeamDriveWrapper.getObjectId(fileData.getKey());
              } catch (Exception e) {
                log.error(e);
              }
            }
            if (filesJson.containsKey(key)) {
              filesJson.put(key, filesJson.get(key).mergeIn(fileData.getValue()));
            }
          }
        }
      }
      foldersJson.sort(
          Comparator.comparing(o -> o.getString(Field.NAME.getName()).toLowerCase()));
      List<JsonObject> filesList = new ArrayList<>(filesJson.values());
      filesList.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject response = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesList))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson))
                  .put(
                      "shortcuts",
                      shortcuts.stream().map(ShortcutEntity::toJson).collect(Collectors.toList())))
          .put("number", filesJson.size() + foldersJson.size())
          .put(Field.PAGE_TOKEN.getName(), pageToken)
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));

      sendOK(segment, message, response, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doUploadFile(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);
    JsonObject body = parsedMessage.getJsonObject();
    String name = body.getString(Field.NAME.getName());
    String internalFolderId = body.getString(Field.FOLDER_ID.getName());
    String internalFileId = body.getString(Field.FILE_ID.getName());
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
    try {
      this.validateObjectName(name, specialCharacters);
    } catch (IllegalArgumentException e1) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, e1.getMessage()),
          HttpStatus.BAD_REQUEST);
    }
    boolean isFileUpdate = Utils.isStringNotNullOrEmpty(internalFileId);
    if (internalFolderId == null && internalFileId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    AWSXRayGDrive drive = connect(segment, message, body, false);
    if (drive == null) {
      return;
    }
    File file = null;
    String responseName = null;
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
    // For TeamDrives - if provided folderId or fileId is "teamdrive encapsulated id" - we have
    // to get native ids
    // But for thumbnails and stuff - use original values (encapsulated)
    boolean isFolderTeamDrive = Utils.isStringNotNullOrEmpty(internalFolderId)
        && TeamDriveWrapper.isTeamDriveId(internalFolderId);
    boolean isFileTeamDrive = Utils.isStringNotNullOrEmpty(internalFileId)
        && TeamDriveWrapper.isTeamDriveId(internalFileId);
    boolean isTeamDrive = isFolderTeamDrive || isFileTeamDrive;
    String nativeFileId = internalFileId;
    String nativeFolderId = internalFolderId;
    String teamDriveId = null;

    if (isTeamDrive) {
      TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(
          drive, config, isFolderTeamDrive ? internalFolderId : internalFileId, s3Regional);
      if (isFolderTeamDrive) {
        nativeFolderId = teamDriveWrapper.getItemId();
      } else {
        nativeFileId = teamDriveWrapper.getItemId();
      }
      teamDriveId = teamDriveWrapper.getTeamDriveId();
    }

    // if we don't update - ids are the same
    String newInternalFileId = internalFileId;
    String newNativeFileId = nativeFileId;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      String versionId;
      AbstractInputStreamContent content = new InputStreamContent(emptyString, stream);

      if (nativeFileId == null) {
        file = new File().setName(name);
        if (!Field.MINUS_1.getName().equals(nativeFolderId)) {
          if (isTeamDrive && nativeFolderId == null) {
            file.setParents(Collections.singletonList(teamDriveId));
          } else {
            file.setParents(Collections.singletonList(nativeFolderId));
          }
        }
        File newFile = drive.files().create(file, content).execute();
        newNativeFileId = newFile.getId();
        if (isTeamDrive && Utils.isStringNotNullOrEmpty(teamDriveId)) {
          newInternalFileId = TeamDriveWrapper.getItemId(teamDriveId, newNativeFileId);
        } else {
          newInternalFileId = newNativeFileId;
        }
      } else if (parsedMessage.hasAnyContent()) {
        File fileInfo = null;
        try {
          fileInfo = drive
              .files()
              .get(nativeFileId)
              .setFields("ownedByMe,capabilities,permissions")
              .execute();
        } catch (GoogleJsonResponseException ex) {
          if (Objects.nonNull(ex.getDetails())
              && Utils.isListNotNullOrEmpty(ex.getDetails().getErrors())) {
            GoogleJsonError.ErrorInfo errorInfo = ex.getDetails().getErrors().get(0);
            if (Objects.nonNull(errorInfo.getReason())
                && errorInfo.getReason().equalsIgnoreCase("notFound")) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
              isConflictFile = true;
              fileNotFound = true;
            }
          }
          if (!fileNotFound) {
            handleException(segment, message, ex);
            return;
          }
        }

        // check if user still has the access to edit this file
        if (!Utils.isStringNotNullOrEmpty(conflictingFileReason) && fileInfo != null) {
          Boolean isOwner = fileInfo.getOwnedByMe();
          if (isOwner != null
              && !isOwner
              && (fileInfo.getCapabilities() == null
                  || !fileInfo.getCapabilities().getCanEdit())) {
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
          versionId = drive
              .files()
              .get(nativeFileId)
              .setFields("headRevisionId")
              .execute()
              .getHeadRevisionId();
          // if latest version in ex storage is unknown,
          // then save a new version as a file with a prefix beside the original file
          if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
            isConflictFile =
                isConflictingChange(userId, internalFileId, storageType, versionId, baseChangeId);
            if (isConflictFile) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
            }
          }
        }

        if (!isConflictFile) {
          file = drive
              .files()
              .get(nativeFileId)
              .setFields(Field.NAME.getName())
              .execute(); // new File(); to get the name for thumbnail
          drive.files().update(nativeFileId, file, content).execute();
        } else {
          boolean isSameFolder = false;
          String oldName = null, modifier = null;
          if (!fileNotFound) {
            // create a new file and save it beside original one
            file = drive
                .files()
                .get(nativeFileId)
                .setFields("name,parents,lastModifyingUser")
                .execute();
            oldName = file.getName();
            modifier = file.getLastModifyingUser().getDisplayName();
          } else {
            Item metaData = FileMetaData.getMetaData(nativeFileId, storageType.name());
            if (Objects.nonNull(metaData)) {
              oldName = metaData.getString(Field.FILE_NAME.getName());
            }
          }
          if (oldName == null) {
            oldName = unknownDrawingName;
          }
          String newName = getConflictingFileName(oldName);
          responseName = newName;
          File newFile = new File().setName(newName);
          if (Objects.nonNull(file) && file.getParents() != null) {
            newFile.setParents(file.getParents());
            isSameFolder = true;
          }
          try {
            newNativeFileId = drive.files().create(newFile, content).execute().getId();
          } catch (Exception ex) {
            newFile.setParents(new ArrayList<>());
            newNativeFileId = drive.files().create(newFile, content).execute().getId();
          }
          if (isTeamDrive && Utils.isStringNotNullOrEmpty(teamDriveId)) {
            newInternalFileId = TeamDriveWrapper.getItemId(teamDriveId, newNativeFileId);
          } else {
            newInternalFileId = newNativeFileId;
          }
          handleConflictingFile(
              segment,
              message,
              body,
              oldName,
              newName,
              Utils.getEncapsulatedId(storageType, externalId, internalFileId),
              Utils.getEncapsulatedId(storageType, externalId, newInternalFileId),
              xSessionId,
              userId,
              modifier,
              conflictingFileReason,
              fileSessionExpired,
              isSameFolder,
              AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
        }
      }

      versionId = drive
          .files()
          .get(newNativeFileId)
          .setFields("headRevisionId")
          .execute()
          .getHeadRevisionId();
      List<Revision> revisions =
          drive.revisions().list(newNativeFileId).execute().getRevisions();

      if (name == null || Extensions.isThumbnailExt(config, name, isAdmin)) {
        eb_send(
            segment,
            ThumbnailsManager.address + ".create",
            body.put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(
                    Field.IDS.getName(),
                    new JsonArray()
                        .add(new JsonObject()
                            .put(Field.FILE_ID.getName(), newInternalFileId)
                            .put(Field.VERSION_ID.getName(), versionId)
                            .put(
                                Field.EXT.getName(),
                                Extensions.getExtension(file == null ? null : file.getName()))
                            .put(Field.PRIORITY.getName(), true))));
      }

      JsonObject response = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(
              Field.FILE_ID.getName(),
              Utils.getEncapsulatedId(
                  StorageType.getShort(storageType), externalId, newInternalFileId))
          .put(Field.VERSION_ID.getName(), versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              ThumbnailsManager.getThumbnailName(storageType, newInternalFileId, versionId))
          .put(
              Field.UPDATE_DATE.getName(),
              revisions.get(revisions.size() - 1).getModifiedTime().getValue());

      if (isConflictFile && Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
        response.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
      }

      if (isConflictFile && Utils.isStringNotNullOrEmpty(responseName)) {
        response.put(Field.NAME.getName(), responseName);
      }

      // for file save-as case
      if (doCopyComments && Utils.isStringNotNullOrEmpty(cloneFileId)) {
        String finalCloneFileId = Utils.parseItemId(cloneFileId, Field.FILE_ID.getName())
            .getString(Field.FILE_ID.getName());
        String finalNewInternalFileId = newInternalFileId;

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
                  finalNewInternalFileId,
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
                .put(Field.FILE_ID.getName(), newInternalFileId)
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
      handleException(segment, message, ex);
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
    String googleTemporaryCode =
        getRequiredString(segment, Field.CODE.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    if (userId == null || googleTemporaryCode == null || sessionId == null) {
      return;
    }
    GoogleTokenResponse tokenResponse;
    try {
      tokenResponse = new GoogleAuthorizationCodeTokenRequest(
              HTTP_TRANSPORT,
              JSON_FACTORY,
              "https://www.googleapis.com/oauth2/v4/token",
              getClientId(null),
              getClientSecret(null),
              googleTemporaryCode,
              REDIRECT_URI)
          .execute();
    } catch (IOException e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
      return;
    }
    if (tokenResponse != null) {
      String accessToken = tokenResponse.getAccessToken();
      if (accessToken == null) {
        sendError(segment, message, "access token is null", HttpStatus.BAD_REQUEST);
        return;
      }
      String refreshToken = tokenResponse.getRefreshToken();
      GoogleIdToken idToken;
      try {
        idToken = tokenResponse.parseIdToken();
      } catch (IOException e) {
        sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST, e);
        return;
      }
      GoogleIdToken.Payload payload = idToken.getPayload();
      String HD = payload.getHostedDomain() == null ? "NA" : payload.getHostedDomain();
      Map<String, Object> metadata = new HashMap<>();
      metadata.put(Field.HD.getName(), HD);
      IntercomConnection.sendEvent(
          "GDrive HD:",
          graebertId,
          metadata,
          intercomAccessToken,
          jsonObject.getString(Field.INTERCOM_APP_ID.getName()));
      String gId = payload.getSubject();
      Item externalAccount = ExternalAccounts.getExternalAccount(userId, gId);
      boolean didExist = externalAccount != null;
      if (externalAccount == null) {
        // try to access users gdrive
        try {
          Credential credential = new GoogleCredential.Builder()
              .setJsonFactory(JSON_FACTORY)
              .setTransport(HTTP_TRANSPORT)
              .setClientSecrets(getClientId(null), getClientSecret(null))
              .build()
              .setAccessToken(accessToken)
              .setRefreshToken(refreshToken);

          Drive.Builder builder = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
              .setApplicationName("ARES Kudo")
              .setHttpRequestInitializer(httpRequest -> {
                credential.initialize(httpRequest);
                // I don't think we care about requests > 28 seconds
                httpRequest.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(28));
                httpRequest.setReadTimeout((int) TimeUnit.SECONDS.toMillis(28));
                httpRequest.setUnsuccessfulResponseHandler(
                    new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff.Builder()
                            .setMaxElapsedTimeMillis((int) TimeUnit.SECONDS.toMillis(10))
                            .build())
                        .setBackOffRequired(
                            response -> response.getStatusCode() == HttpStatus.FORBIDDEN
                                || response.getStatusCode() / 100 == 5
                            /*HttpBackOffUnsuccessfulResponseHandler
                            .BackOffRequired.ALWAYS*/ ));
              });
          Drive drive = builder.build();

          drive
              .files()
              .get(Field.ROOT.getName())
              .setFields(Field.NAME.getName())
              .execute();
        } catch (Exception e) {
          handleException(segment, message, e);
          return;
        }

        externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(), userId, Field.EXTERNAL_ID_LOWER.getName(), gId)
            .withString(Field.F_TYPE.getName(), StorageType.GDRIVE.toString())
            .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime())
            .withString(Field.HD.getName(), HD)
            .withString(Field.CLIENT_ID.getName(), getClientId(null));
        storageLog(
            segment,
            message,
            userId,
            graebertId,
            sessionId,
            username,
            StorageType.GDRIVE.toString(),
            gId,
            true,
            intercomAccessToken);
      } else {
        externalAccount.withString(Field.CLIENT_ID.getName(), getClientId(null));
      }
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
      externalAccount.withString(Field.ACCESS_TOKEN.getName(), accessToken);
      if (payload.getEmail() != null) {
        externalAccount.withString(Field.EMAIL.getName(), payload.getEmail().toLowerCase());
      }
      if (payload.get("given_name") != null) {
        externalAccount.withString(Field.NAME.getName(), (String) payload.get("given_name"));
      }
      if (payload.get("family_name") != null) {
        externalAccount.withString(Field.SURNAME.getName(), (String) payload.get("family_name"));
      }
      if (tokenResponse.getExpiresInSeconds() != null) {
        externalAccount.withLong(
            Field.EXPIRES.getName(),
            GMTHelper.utcCurrentTime() + tokenResponse.getExpiresInSeconds() * 1000);
      }
      if (refreshToken != null) {
        externalAccount.withString(Field.REFRESH_TOKEN.getName(), refreshToken);
      }
      externalAccount.removeAttribute(Field.ACTIVE.getName());
      log.info(String.format(
          "[ GDRIVE CONNECT ] Did exist: %s Expires: %d Full json: %s",
          didExist, tokenResponse.getExpiresInSeconds(), externalAccount.toJSONPretty()));
      try {
        Sessions.updateSessionOnConnect(externalAccount, userId, storageType, gId, sessionId);
      } catch (Exception e) {
        sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
        return;
      }
      sendOK(segment, message, mills);
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InvalidTemporaryCode"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private JsonObject getFileJson(
      File fileInfo,
      boolean isFolder,
      boolean full,
      boolean addShare,
      boolean isAdmin,
      String externalId,
      String userId,
      boolean force,
      boolean canCreateThumbnails) {
    long updateDate = 0;
    String changer = emptyString, changerEmail = emptyString;
    boolean isTeamDrive = fileInfo.getTeamDriveId() != null;
    String driveId = fileInfo.getDriveId();
    if (isTeamDrive) {
      driveId = fileInfo.getTeamDriveId();
    }
    String verId = fileInfo.getHeadRevisionId();
    if (full) {
      if (fileInfo.getModifiedTime() != null) {
        updateDate = fileInfo.getModifiedTime().getValue();
      }
      if (fileInfo.getLastModifyingUser() != null) {
        changer = fileInfo.getLastModifyingUser().getDisplayName();
        changerEmail = fileInfo.getLastModifyingUser().getEmailAddress();
      }
    }
    boolean isOwner = !isTeamDrive && fileInfo.getOwnedByMe();
    boolean isFilesRoot = isFolder && isOwner && !Utils.isListNotNullOrEmpty(fileInfo.getParents());
    boolean viewOnly = true;
    if (fileInfo.getCapabilities() != null) {
      viewOnly = !fileInfo.getCapabilities().getCanEdit();
    }
    boolean externalPublic = true;
    // fileInfo.getWebContentLink() != null;
    JsonArray editor, viewer;
    if (fileInfo.getPermissions() != null && addShare) {
      // RG: no need for parallelStream. Not calling API
      List<CollaboratorInfo> collaboratorInfoList = getItemCollaborators(fileInfo);
      Collaborators collaborators = new Collaborators(collaboratorInfoList);
      editor = Collaborators.toJsonArray(collaborators.editor);
      viewer = Collaborators.toJsonArray(collaborators.viewer);
    } else {
      editor = new JsonArray();
      viewer = new JsonArray();
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (!isFolder && addShare) {
      PLData = findLinkForFile(fileInfo.getId(), externalId, userId, viewOnly);
    }
    String rawId = fileInfo.getId();
    if (isTeamDrive) {
      rawId = TeamDriveWrapper.getItemId(driveId, rawId);
    }
    String parent = Field.MINUS_1.getName();
    if (fileInfo.getParents() != null) {
      parent = fileInfo.getParents().get(0);
    }
    if (isTeamDrive) {
      parent = TeamDriveWrapper.getItemId(driveId, parent);
    } else if (parent.equals(Field.MINUS_1.getName()) && !isOwner) {
      parent = SHARED_FOLDER;
    }

    boolean isDeleted = fileInfo.getTrashed();

    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, rawId))
        .put(Field.WS_ID.getName(), rawId)
        .put(
            isFolder ? Field.NAME.getName() : Field.FILE_NAME.getName(),
            fileInfo.getName() != null ? fileInfo.getName() : emptyString)
        .put(
            isFolder ? Field.PARENT.getName() : Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, parent))
        .put(
            OWNER,
            Utils.isListNotNullOrEmpty(fileInfo.getOwners())
                ? fileInfo.getOwners().get(0).getDisplayName()
                : emptyString)
        .put(
            Field.OWNER_EMAIL.getName(),
            Utils.isListNotNullOrEmpty(fileInfo.getOwners())
                ? fileInfo.getOwners().get(0).getEmailAddress()
                : emptyString)
        .put(
            Field.CREATION_DATE.getName(),
            fileInfo.getCreatedTime() != null ? fileInfo.getCreatedTime().getValue() : 0)
        .put(Field.UPDATE_DATE.getName(), updateDate)
        .put(Field.CHANGER.getName(), changer)
        .put(Field.CHANGER_EMAIL.getName(), changerEmail)
        .put(
            Field.SIZE.getName(),
            Utils.humanReadableByteCount(fileInfo.getSize() != null ? fileInfo.getSize() : 0))
        .put(Field.SIZE_VALUE.getName(), fileInfo.getSize() != null ? fileInfo.getSize() : 0)
        .put(Field.SHARED.getName(), fileInfo.getShared())
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(Field.CAN_MOVE.getName(), isOwner)
        .put(Field.IS_DELETED.getName(), isDeleted)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor))
        .put(Field.EXTERNAL_PUBLIC.getName(), externalPublic)
        .put(Field.DRIVE_ID.getName(), driveId)
        .put(Field.IS_TEAM_DRIVE.getName(), isTeamDrive)
        .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()));
    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }
    if (!isFolder) {
      String thumbnailName = ThumbnailsManager.getThumbnailName(
          StorageType.getShort(storageType), fileInfo.getId(), fileInfo.getHeadRevisionId());
      String previewId =
          ThumbnailsManager.getPreviewName(StorageType.getShort(storageType), fileInfo.getId());
      json.put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put(Field.PREVIEW_ID.getName(), previewId);
      if (Extensions.isThumbnailExt(config, fileInfo.getName(), isAdmin)) {
        try {
          // AS : Removing this temporarily until we have some server cache (WB-1248)
          //          .put("thumbnailStatus",
          //              ThumbnailsManager.getThumbnailStatus(rawId, storageType.name(), verId,
          //              force,
          //                  canCreateThumbnails));
          json.put(
                  Field.THUMBNAIL.getName(),
                  ThumbnailsManager.getThumbnailURL(
                      config,
                      thumbnailName,
                      true)) /// *config.getProperties().getUrl() + */"/thumbnails/"
              // + thumbnailName)
              .put(
                  Field.GEOMDATA.getName(),
                  ThumbnailsManager.getThumbnailURL(config, thumbnailName, true)
                      + ".json") // "/geomdata/" + thumbnailName + ".json")
              .put(
                  Field.PREVIEW.getName(),
                  ThumbnailsManager.getPreviewURL(
                      config, previewId,
                      true)); // "/previews/" + StorageType.getShort(StorageType.GDRIVE) + "_" +
          // fileInfo.getId(), true);
        } catch (Exception ignore) {
        }
      }
    } else {
      json.put(Field.IS_FILES_ROOT.getName(), isFilesRoot);
    }
    if (verId != null) {
      json.put(Field.VER_ID.getName(), verId);
      json.put(Field.VERSION_ID.getName(), verId);
    }

    ObjectPermissions permissions = new ObjectPermissions();
    if (fileInfo.getCapabilities() != null) {
      Capabilities capabilities = fileInfo.getCapabilities();
      boolean sharingCapability = !isDeleted && capabilities.getCanShare();
      boolean canMoveFrom = capabilities.getCanMoveChildrenWithinDrive() != null
          ? capabilities.getCanMoveChildrenWithinDrive()
          : true;
      permissions
          .setPermissionAccess(
              AccessType.canRename,
              capabilities.getCanRename() != null ? capabilities.getCanRename() : true)
          .setPermissionAccess(
              AccessType.canCreateFiles,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : true)
          .setPermissionAccess(
              AccessType.canCreateFolders,
              capabilities.getCanAddChildren() != null ? capabilities.getCanAddChildren() : true)
          .setPermissionAccess(
              AccessType.canDelete,
              capabilities.getCanDelete() != null ? capabilities.getCanDelete() : true)
          .setPermissionAccess(
              AccessType.canDownload,
              capabilities.getCanDownload() != null ? capabilities.getCanDownload() : true)
          .setPermissionAccess(
              AccessType.canClone,
              !isFolder && (capabilities.getCanCopy() != null ? capabilities.getCanCopy() : true))
          .setPermissionAccess(AccessType.canManagePermissions, sharingCapability)
          .setPermissionAccess(AccessType.canMoveFrom, canMoveFrom)
          .setPermissionAccess(AccessType.canMoveTo, true);
    } else {
      permissions
          .setAllTo(true)
          .setPermissionAccess(
              AccessType.canManagePermissions, !isDeleted && (!viewOnly || isOwner))
          .setPermissionAccess(AccessType.canClone, !isFolder);
    }
    permissions
        .setPermissionAccess(AccessType.canMove, isOwner)
        .setPermissionAccess(AccessType.canViewPermissions, !isDeleted)
        .setPermissionAccess(AccessType.canManagePublicLink, !viewOnly && !isDeleted && !isFolder)
        .setPermissionAccess(AccessType.canViewPublicLink, !viewOnly && !isDeleted && !isFolder);
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

  private List<CollaboratorInfo> getItemCollaborators(File fileInfo) {
    List<CollaboratorInfo> collaborators = new ArrayList<>();
    fileInfo.getPermissions().forEach(permission -> {
      String name = permission.getDisplayName();
      if (Objects.nonNull(name)) {
        String email = emptyString;
        if (Objects.nonNull(permission.getEmailAddress())) {
          email = permission.getEmailAddress().toLowerCase();
        }
        String storageAccessRole = permission.getRole();
        if (!OWNER.equals(storageAccessRole)) {
          Role role = WRITER.equals(storageAccessRole) ? Role.EDITOR : Role.VIEWER;
          CollaboratorInfo info =
              new CollaboratorInfo(permission.getId(), name, email, null, storageAccessRole, role);
          collaborators.add(info);
        }
      }
    });
    return collaborators;
  }

  public boolean filterMimetype(String mimeType) {
    switch (mimeType) {
        // shortcut
      case APPLICATION_VND_GOOGLE_APPS_SHORTCUT:
      case APPLICATION_VND_GOOGLE_APPS_FOLDER:
      case "application/octet-stream":
      case "application/pdf":
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
      case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
      case "image/vnd.dwg":
      case "image/jpeg":
      default:
        return true;
      case "application/vnd.google-apps":
        return false;
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
          .map(gdriveUser -> {
            long millis = System.currentTimeMillis();
            System.out.println("started processing " + gdriveUser.getString(Field.EMAIL.getName())
                + " at " + millis);
            if (gdriveUser.hasAttribute(Field.ACTIVE.getName())
                && !gdriveUser.getBoolean(Field.ACTIVE.getName())) {
              return null;
            }
            Entity subSegment = XRayManager.createSubSegment(
                operationGroup, segment, gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()));
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            String accessToken = gdriveUser.get(Field.ACCESS_TOKEN.getName()) != null
                ? gdriveUser.getString(Field.ACCESS_TOKEN.getName())
                : emptyString;
            String refreshToken = gdriveUser.get(Field.REFRESH_TOKEN.getName()) != null
                ? gdriveUser.getString(Field.REFRESH_TOKEN.getName())
                : emptyString;
            try {
              accessToken =
                  EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
              refreshToken = EncryptHelper.decrypt(
                  refreshToken, config.getProperties().getFluorineSecretKey());
            } catch (Exception ex) {
              System.out.println(
                  "finished processing " + gdriveUser.getString(Field.EMAIL.getName()) + " in "
                      + (System.currentTimeMillis() - millis));
              log.error(ex);
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), StorageType.GDRIVE.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), gdriveUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }

            Credential credential = new GoogleCredential.Builder()
                .setJsonFactory(JSON_FACTORY)
                .setTransport(HTTP_TRANSPORT)
                .setClientSecrets(getClientId(gdriveUser), getClientSecret(gdriveUser))
                .build()
                .setAccessToken(accessToken)
                .setRefreshToken(refreshToken);

            AWSXRayGDrive drive =
                new AWSXRayGDrive(new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
                    .setApplicationName("ARES Kudo")
                    .setHttpRequestInitializer(credential)
                    .build());

            About about = null;
            try {
              about = drive.about().get().setFields(Field.USER.getName()).execute();
            } catch (Exception exception) {
              log.error(exception);
            }
            if (about == null) {
              System.out.println(
                  "finished processing " + gdriveUser.getString(Field.EMAIL.getName()) + " in "
                      + (System.currentTimeMillis() - millis));
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), StorageType.GDRIVE.toString())
                  .put(
                      Field.EXTERNAL_ID.getName(),
                      gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                  .put(Field.NAME.getName(), gdriveUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            Set<String> ids = new HashSet<>();
            String nextPageToken = null;
            do {
              FileList fileList;
              try {
                AWSXRayGDriveFiles.AWSXRayDriveListRequest list = drive
                    .files()
                    .list(folderContentPageSize)
                    .setQ("trashed = false and name contains '" + query + "'")
                    .setFields("files(mimeType,headRevisionId,createdTime,id,lastModifyingUser,"
                        + "modifiedTime,name,owners,ownedByMe,parents,permissions,shared,"
                        + "size,"
                        + "trashed,capabilities,webContentLink,teamDriveId,driveId)")
                    .setOrderBy("folder,name_natural");
                if (nextPageToken != null) {
                  list.setPageToken(nextPageToken);
                }
                fileList = list.execute();
              } catch (Exception exception) {
                System.out.println(
                    "finished processing " + gdriveUser.getString(Field.EMAIL.getName()) + " in "
                        + (System.currentTimeMillis() - millis));
                log.error(exception);
                return new JsonObject()
                    .put(Field.STORAGE_TYPE.getName(), StorageType.GDRIVE.toString())
                    .put(
                        Field.EXTERNAL_ID.getName(),
                        gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                    .put(Field.NAME.getName(), gdriveUser.getString(Field.EMAIL.getName()))
                    .put(Field.FILES.getName(), filesJson)
                    .put(Field.FOLDERS.getName(), foldersJson);
              }
              nextPageToken = fileList.getNextPageToken();
              List<File> files = fileList.getFiles();
              String externalId = gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName());

              // RG: no need for parallelStream. Not calling API
              files.forEach(file -> {
                if (filterMimetype(file.getMimeType())) {
                  boolean isFolder = APPLICATION_VND_GOOGLE_APPS_FOLDER.equals(file.getMimeType());
                  JsonObject json = getFileJson(
                      file, isFolder, true, true, isAdmin, externalId, userId, false, false);
                  ids.add(
                      json.containsKey(Field.PARENT.getName())
                          ? json.getString(Field.PARENT.getName())
                          : json.getString(Field.FOLDER_ID.getName()));
                  if (isFolder) {
                    foldersJson.add(json);
                  } else {
                    filesJson.add(json);
                  }
                }
              });
            } while (nextPageToken != null && !nextPageToken.trim().isEmpty());
            System.out.println("finished processing " + gdriveUser.getString(Field.EMAIL.getName())
                + " in " + (System.currentTimeMillis() - millis));
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), StorageType.GDRIVE.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), gdriveUser.getString(Field.EMAIL.getName()))
                .put(Field.FILES.getName(), filesJson)
                .put(Field.FOLDERS.getName(), foldersJson)
                .put(Field.IDS.getName(), new JsonArray(new ArrayList<>(ids)));
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
    }
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, message.body(), replyOnOk);
  }

  private AWSXRayGDrive connect(Entity segment, Message<JsonObject> message) {
    return connect(segment, message, message.body(), false);
  }

  private <T> AWSXRayGDrive connect(
      Entity segment, Message<T> message, JsonObject json, boolean replyOnOk) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "Drive.Connect");

    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String googleId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, null, googleId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (googleId == null || googleId.isEmpty()) {
      googleId = findExternalId(segment, message, json, storageType);
      if (googleId == null) {
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
    Item gdriveUser = ExternalAccounts.getExternalAccount(userId, googleId);
    if (gdriveUser == null
        || gdriveUser.hasAttribute(Field.ACTIVE.getName())
            && !gdriveUser.getBoolean(Field.ACTIVE.getName())) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, userId, googleId, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToTheirGoogleDriveAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    json.put(
        "isAccountThumbnailDisabled",
        gdriveUser.hasAttribute("disableThumbnail") && gdriveUser.getBoolean("disableThumbnail"));

    String accessToken = gdriveUser.getString(Field.ACCESS_TOKEN.getName());
    String refreshToken = gdriveUser.getString(Field.REFRESH_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
      refreshToken =
          EncryptHelper.decrypt(refreshToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      logConnectionIssue(storageType, userId, googleId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      log.error(e);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }

    if (message.body() instanceof JsonObject) {
      // required for stream downloading
      ((JsonObject) message.body()).put(Field.ACCESS_TOKEN.getName(), accessToken);
    }

    Credential credential = new GoogleCredential.Builder()
        .setJsonFactory(JSON_FACTORY)
        .setTransport(HTTP_TRANSPORT)
        .setClientSecrets(getClientId(gdriveUser), getClientSecret(gdriveUser))
        .build()
        .setAccessToken(accessToken)
        .setRefreshToken(refreshToken);

    boolean needRefresh = !isNewConnectionAvailable() || shouldUseNewClient(gdriveUser);
    if (needRefresh && gdriveUser.hasAttribute(Field.EXPIRES.getName())) {
      long expires = gdriveUser.getLong(Field.EXPIRES.getName());
      needRefresh = GMTHelper.utcCurrentTime() + 1000 * 60 * 60 > expires;
    }
    if (refreshToken != null && needRefresh) {
      try {
        // System.out.println("### Refreshing GDRIVE token");
        credential.refreshToken();
      } catch (TokenResponseException tokenResponseException) {
        logConnectionIssue(storageType, userId, googleId, ConnectionError.TOKEN_EXPIRED);
        TokenErrorResponse response = tokenResponseException.getDetails();
        if (response != null) {
          String error = response.getError() + " : " + response.getErrorDescription();
          log.warn("GDRIVE unable to refresh token: " + error);
        }
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "GoogleAccessTokenExpired"),
            HttpStatus.BAD_REQUEST);
        return null;
      } catch (IOException e) {
        log.error(e);
        logConnectionIssue(storageType, userId, googleId, ConnectionError.CANNOT_REFRESH_TOKENS);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "GoogleAccessTokenExpired"),
            HttpStatus.BAD_REQUEST);
        // According to refreshToken method documentation -
        // accessToken will be set to null anyway, so no reason to try to get it
        return null;
      }
    }
    String newAccessToken = credential.getAccessToken();
    if (newAccessToken == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, userId, googleId, ConnectionError.TOKEN_EXPIRED);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "GoogleAccessTokenExpired"),
          HttpStatus.BAD_REQUEST);
      return null;
    }
    String newRefreshToken = credential.getRefreshToken();
    try {
      newAccessToken =
          EncryptHelper.encrypt(newAccessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      logConnectionIssue(storageType, userId, googleId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
    if (!accessToken.equals(newAccessToken)) {
      gdriveUser.withString(Field.ACCESS_TOKEN.getName(), newAccessToken);
      if (newRefreshToken != null) {
        try {
          newRefreshToken =
              EncryptHelper.encrypt(newRefreshToken, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          logConnectionIssue(storageType, userId, googleId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return null;
        }
        gdriveUser.withString(Field.REFRESH_TOKEN.getName(), newRefreshToken);
      } else {
        gdriveUser.removeAttribute(Field.REFRESH_TOKEN.getName());
      }
      if (credential.getExpirationTimeMilliseconds() != null) {
        gdriveUser.withLong(Field.EXPIRES.getName(), credential.getExpirationTimeMilliseconds());
      } else {
        gdriveUser.removeAttribute(Field.EXPIRES.getName());
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("saveExternalAccount")
          .runWithoutSegment(() -> ExternalAccounts.saveExternalAccount(
              gdriveUser.getString(Field.FLUORINE_ID.getName()),
              gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
              gdriveUser));
    }
    Drive.Builder builder = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
        .setApplicationName("ARES Kudo")
        .setHttpRequestInitializer(httpRequest -> {
          credential.initialize(httpRequest);
          // I don't think we care
          // about requests > 28
          // seconds
          httpRequest.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(28));
          httpRequest.setReadTimeout((int) TimeUnit.SECONDS.toMillis(28));
          httpRequest.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
                  new ExponentialBackOff.Builder()
                      .setMaxElapsedTimeMillis((int) TimeUnit.SECONDS.toMillis(10))
                      .build())
              .setBackOffRequired(
                  response -> response.getStatusCode() == 403 || response.getStatusCode() / 100 == 5
                  /*HttpBackOffUnsuccessfulResponseHandler.BackOffRequired.ALWAYS*/ ));
        });
    Drive drive = builder.build();
    if (message.body() instanceof JsonObject) {
      ((JsonObject) message.body())
          .put(Field.USER_EMAIL.getName(), gdriveUser.getString(Field.EMAIL.getName()));
    }

    XRayManager.endSegment(subsegment);
    if (replyOnOk) {
      sendOK(segment, message);
    }
    return new AWSXRayGDrive(drive);
  }

  private AWSXRayGDrive connect(Item gdriveUser) {
    if (gdriveUser == null) {
      logConnectionIssue(storageType, null, null, ConnectionError.NO_ENTRY_IN_DB);
      return null;
    }
    if (gdriveUser.hasAttribute(Field.ACTIVE.getName())
        && !gdriveUser.getBoolean(Field.ACTIVE.getName())) {
      logConnectionIssue(storageType, null, null, ConnectionError.NO_ENTRY_IN_DB);
      return null;
    }
    String accessToken = gdriveUser.getString(Field.ACCESS_TOKEN.getName());
    String refreshToken = gdriveUser.getString(Field.REFRESH_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
      refreshToken =
          EncryptHelper.decrypt(refreshToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_DECRYPT_TOKENS);
      return null;
    }

    Credential credential = new GoogleCredential.Builder()
        .setJsonFactory(JSON_FACTORY)
        .setTransport(HTTP_TRANSPORT)
        .setClientSecrets(getClientId(gdriveUser), getClientSecret(gdriveUser))
        .build()
        .setAccessToken(accessToken)
        .setRefreshToken(refreshToken);

    if (refreshToken != null && (!isNewConnectionAvailable() || shouldUseNewClient(gdriveUser))) {
      try {
        credential.refreshToken();
      } catch (IOException e) {
        log.error(e);
        logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_REFRESH_TOKENS);
      }
    }
    String newAccessToken = credential.getAccessToken();
    if (newAccessToken == null) {
      logConnectionIssue(storageType, null, null, ConnectionError.NO_ACCESS_TOKEN);
      return null;
    }
    String newRefreshToken = credential.getRefreshToken();
    try {
      newAccessToken =
          EncryptHelper.encrypt(newAccessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_ENCRYPT_TOKENS);
      return null;
    }
    if (!accessToken.equals(newAccessToken)) {
      gdriveUser.withString(Field.ACCESS_TOKEN.getName(), newAccessToken);
      if (newRefreshToken != null) {
        try {
          newRefreshToken =
              EncryptHelper.encrypt(newRefreshToken, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_ENCRYPT_TOKENS);
          return null;
        }
        gdriveUser.withString(Field.REFRESH_TOKEN.getName(), newRefreshToken);
      } else {
        gdriveUser.removeAttribute(Field.REFRESH_TOKEN.getName());
      }
      ExternalAccounts.saveExternalAccount(
          gdriveUser.getString(Field.FLUORINE_ID.getName()),
          gdriveUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
          gdriveUser);
    }
    Drive drive = new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
        .setApplicationName("ARES Kudo")
        .setHttpRequestInitializer(httpRequest -> {
          credential.initialize(httpRequest);
          httpRequest.setConnectTimeout(300 * 60000);
          httpRequest.setReadTimeout(300 * 60000);
          httpRequest.setUnsuccessfulResponseHandler(new HttpBackOffUnsuccessfulResponseHandler(
                  new ExponentialBackOff.Builder()
                      .setMaxElapsedTimeMillis(15 * 1000)
                      .build())
              .setBackOffRequired(
                  response -> response.getStatusCode() == 403 || response.getStatusCode() / 100 == 5
                  /*HttpBackOffUnsuccessfulResponseHandler.BackOffRequired.ALWAYS*/ ));
        })
        .build();
    return new AWSXRayGDrive(drive);
  }

  private <T> void handleException(Entity segment, Message<T> message, Exception ex) {
    if (ex == null) {
      // just not to get NPE here
      sendError(segment, message, emptyString, HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    if (segment != null) {
      XRayEntityUtils.addException(segment, ex);
    }
    if (ex instanceof UnknownHostException) {
      sendError(
          segment,
          message,
          "Google Drive " + Utils.getLocalizedString(message, "isnotresponding"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          ex);
    } else if (ex instanceof GoogleJsonResponseException) {
      GoogleJsonResponseException googleJsonResponseException = (GoogleJsonResponseException) ex;
      // getDetails() can return null if response isn't JSON
      int responseCode = (googleJsonResponseException.getDetails() == null)
          ? googleJsonResponseException.getStatusCode()
          : googleJsonResponseException.getDetails().getCode();
      String errorMessage = (googleJsonResponseException.getDetails() == null)
          ? googleJsonResponseException.getMessage()
          : googleJsonResponseException.getDetails().getMessage();

      // if original permission error message contains only URL
      if (Pattern.matches(permissionURLRegex, errorMessage)) {
        errorMessage = Utils.getLocalizedString(message, "GD3");
      }
      // user did not grant access to his drive
      else if (errorMessage.contains("403 Forbidden") && errorMessage.contains("/files/root?")) {
        errorMessage = Utils.getLocalizedString(message, "GD4");
      }

      int first, last;
      if (errorMessage.contains("\"")
          && (first = errorMessage.indexOf("\"")) != (last = errorMessage.lastIndexOf("\""))) {
        errorMessage = errorMessage.substring(first + 1, last);
      }

      if (googleJsonResponseException.getDetails() == null) {
        sendError(segment, message, errorMessage, HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }
      if (responseCode != HttpStatus.NOT_FOUND) {
        responseCode = HttpStatus.BAD_REQUEST;
      }
      sendError(segment, message, errorMessage, responseCode, ex);
    } else if (ex instanceof HttpResponseException) {
      int code = ((HttpResponseException) ex).getStatusCode();
      if (code != HttpStatus.NOT_FOUND) {
        code = HttpStatus.BAD_REQUEST;
      }
      String error = ((HttpResponseException) ex).getStatusMessage();
      if (ex instanceof TokenResponseException) {
        TokenResponseException tokenResponseException = ((TokenResponseException) ex);
        TokenErrorResponse response = tokenResponseException.getDetails();
        if (response != null) {
          error = response.getError() + " : " + response.getErrorDescription();
        }
        sendError(segment, message, error, HttpStatus.BAD_REQUEST, LogLevel.INFO);
      } else {
        try {
          JsonObject json = new JsonObject(((HttpResponseException) ex).getContent());
          if (json.containsKey(Field.MESSAGE.getName())
              && !json.getString(Field.MESSAGE.getName()).isEmpty()) {
            error = json.getString(Field.MESSAGE.getName());
            int first, last;
            if (error.contains("\"")
                && (first = error.indexOf("\"")) != (last = error.lastIndexOf("\""))) {
              error = error.substring(first + 1, last);
            }
          }
        } catch (Exception ignore) {
        }
        sendError(segment, message, error, code, ex);
      }
    } else {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
  }

  public void doGetTrashStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String id = jsonObject.getString(Field.FILE_ID.getName());
    // we don't check folders for this - just send an error
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
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
    // cannot find connection for the file

    AWSXRayGDrive drive = connect(segment, message, jsonObject, false);
    if (drive == null) {
      return;
    }
    try {
      if (TeamDriveWrapper.isTeamDriveId(id)) {
        TeamDriveWrapper teamDriveWrapper = new TeamDriveWrapper(drive, config, id, s3Regional);
        id = teamDriveWrapper.getItemId();
      }
      File fileInfo = drive
          .files()
          .get(id)
          .setFields("trashed") // we only care about trash status here
          .execute();

      sendOK(
          segment,
          message,
          new JsonObject().put(Field.IS_DELETED.getName(), fileInfo.getTrashed()),
          millis);
    } catch (Exception e) {
      // if exception happened - we don't really care why as far as we use this for RF validation
      // only
      // so return true anyway
      // https://graebert.atlassian.net/browse/XENON-30048
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.IS_DELETED.getName(), true)
              .put("nativeResponse", e.getMessage()),
          millis);
    }
  }

  private static class HttpBackOffUnsuccessfulResponseHandler
      implements HttpUnsuccessfulResponseHandler {
    private final BackOff backOff;
    private com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler.BackOffRequired
        backOffRequired;
    private Sleeper sleeper;

    HttpBackOffUnsuccessfulResponseHandler(BackOff backOff) {
      this.backOffRequired = com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler
          .BackOffRequired.ON_SERVER_ERROR;
      this.sleeper = Sleeper.DEFAULT;
      this.backOff = Preconditions.checkNotNull(backOff);
    }

    public final BackOff getBackOff() {
      return this.backOff;
    }

    public final com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler.BackOffRequired
        getBackOffRequired() {
      return this.backOffRequired;
    }

    HttpBackOffUnsuccessfulResponseHandler setBackOffRequired(
        com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler.BackOffRequired
            backOffRequired) {
      this.backOffRequired = Preconditions.checkNotNull(backOffRequired);
      return this;
    }

    public final Sleeper getSleeper() {
      return this.sleeper;
    }

    public HttpBackOffUnsuccessfulResponseHandler setSleeper(Sleeper sleeper) {
      this.sleeper = Preconditions.checkNotNull(sleeper);
      return this;
    }

    public final boolean handleResponse(
        HttpRequest request,
        com.google.api.client.http.HttpResponse response,
        boolean supportsRetry)
        throws IOException {
      if (supportsRetry) {
        if (this.backOffRequired.isRequired(response)) {
          try {
            log.error("Google Drive exponential backoff: " + "request URL: "
                + request.getRequestMethod()
                + " " + request.getUrl().toString() + " " + "response: " + response.getStatusCode()
                + " " + response.getStatusMessage() + " "
                + IOUtils.toString(response.getContent(), StandardCharsets.UTF_8));
          } catch (Exception ignored) {
          }
          try {
            return BackOffUtils.next(this.sleeper, this.backOff);
          } catch (InterruptedException ignored) {
          }
        }
      }
      return false;
    }
  }
}
