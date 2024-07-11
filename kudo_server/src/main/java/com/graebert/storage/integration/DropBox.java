package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxDownloader;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxHost;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.InvalidAccessTokenException;
import com.dropbox.core.PathRootErrorException;
import com.dropbox.core.RateLimitException;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.oauth.DbxOAuthException;
import com.dropbox.core.oauth.DbxRefreshResult;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.async.LaunchResultBase;
import com.dropbox.core.v2.common.PathRoot;
import com.dropbox.core.v2.common.PathRootError;
import com.dropbox.core.v2.files.CreateFolderResult;
import com.dropbox.core.v2.files.DbxUserListFolderBuilder;
import com.dropbox.core.v2.files.DeleteArg;
import com.dropbox.core.v2.files.DeleteBatchJobStatus;
import com.dropbox.core.v2.files.DeleteBatchLaunch;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.FileSharingInfo;
import com.dropbox.core.v2.files.FolderMetadata;
import com.dropbox.core.v2.files.FolderSharingInfo;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.ListRevisionsErrorException;
import com.dropbox.core.v2.files.ListRevisionsResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.RelocationErrorException;
import com.dropbox.core.v2.files.RelocationResult;
import com.dropbox.core.v2.files.SearchMatchV2;
import com.dropbox.core.v2.files.SearchV2Result;
import com.dropbox.core.v2.files.UploadErrorException;
import com.dropbox.core.v2.files.WriteError;
import com.dropbox.core.v2.files.WriteMode;
import com.dropbox.core.v2.sharing.AccessLevel;
import com.dropbox.core.v2.sharing.AddFolderMemberError;
import com.dropbox.core.v2.sharing.AddFolderMemberErrorException;
import com.dropbox.core.v2.sharing.AddMember;
import com.dropbox.core.v2.sharing.InviteeMembershipInfo;
import com.dropbox.core.v2.sharing.ListFileMembersErrorException;
import com.dropbox.core.v2.sharing.MemberSelector;
import com.dropbox.core.v2.sharing.RemoveMemberJobStatus;
import com.dropbox.core.v2.sharing.ShareFolderError;
import com.dropbox.core.v2.sharing.ShareFolderErrorException;
import com.dropbox.core.v2.sharing.ShareFolderJobStatus;
import com.dropbox.core.v2.sharing.ShareFolderLaunch;
import com.dropbox.core.v2.sharing.SharedFileMembers;
import com.dropbox.core.v2.sharing.SharedFileMetadata;
import com.dropbox.core.v2.sharing.SharedFolderAccessError;
import com.dropbox.core.v2.sharing.SharedFolderAccessErrorException;
import com.dropbox.core.v2.sharing.SharedFolderMembers;
import com.dropbox.core.v2.sharing.SharedFolderMetadata;
import com.dropbox.core.v2.sharing.SharingUserError;
import com.dropbox.core.v2.sharing.SharingUserErrorException;
import com.dropbox.core.v2.sharing.UserMembershipInfo;
import com.dropbox.core.v2.users.BasicAccount;
import com.dropbox.core.v2.users.FullAccount;
import com.dropbox.core.v2.userscommon.AccountType;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.CollaboratorInfo;
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
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.xray.AWSXRayDbxClient;
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
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.net.ssl.HttpsURLConnection;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class DropBox extends BaseStorage implements Storage {
  private static final OperationGroup operationGroup = OperationGroup.DROPBOX;

  @NonNls
  public static final String address = "dropbox";

  @NonNls
  private static final String ID_PREFIX = "id:";
  // = "https://oauth.dev.graebert.com/?type=dropbox";//"http://localhost:8181/?type=dropbox";
  private static final Logger log = LogManager.getRootLogger();
  private static final StorageType storageType = StorageType.DROPBOX;

  @NonNls
  private static String REDIRECT_URI;

  private final String[] specialCharacters = {"<", ">", "\"", "|", ":", "?", "*", "/", "\\"};
  private final String[] windowsSpecialCharacters = {
    "<", ">", "\"", "|", ":", "\\?", "\\*", "\\+", "/", "\\"
  };
  private String APP_KEY, APP_SECRET;
  private S3Regional s3Regional = null;

  public DropBox() {}

  @Override
  public void start() throws Exception {
    super.start();
    APP_KEY = config.getProperties().getDropboxAppKey();
    APP_SECRET = config.getProperties().getDropboxAppSecret();
    REDIRECT_URI = config.getProperties().getDropboxRedirectUrl();

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
    eb.consumer(address + ".moveFile", this::doMoveFile);
    eb.consumer(address + ".renameFile", this::doRenameFile);
    eb.consumer(address + ".deleteFile", this::doDeleteFile);
    eb.consumer(address + ".getVersions", this::doGetVersions);
    eb.consumer(address + ".getLatestVersionId", this::doGetLatestVersionId);
    eb.consumer(address + ".getVersion", this::doGetFile);
    eb.consumer(address + ".uploadVersion", this::doUploadVersion);
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
    eb.consumer(address + ".trashMultiple", this::doDeleteBatch);
    eb.consumer(address + ".getTrashStatus", this::doGetTrashStatus);

    eb.consumer(address + ".getVersionByToken", this::doGetVersionByToken);
    eb.consumer(address + ".checkFileVersion", this::doCheckFileVersion);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-dropbox");
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
      DbxClientV2 api = connect(segment, message);
      if (Objects.isNull(api)) {
        return;
      }

      FileMetadata metadata;
      String sharedFileUrl = null;
      String name;

      try {
        metadata = (FileMetadata) api.files().getMetadata(ID_PREFIX + fileIds.getId());
        name = metadata.getName();
      } catch (DbxException exception) {
        try {
          SharedFileMetadata meta = api.sharing().getFileMetadata(ID_PREFIX + fileIds.getId());
          metadata = new FileMetadata(
              meta.getName(),
              meta.getId(),
              meta.getTimeInvited(),
              meta.getTimeInvited(),
              "74353beb4dde7fb",
              0,
              "/",
              "/",
              meta.getParentSharedFolderId(),
              meta.getPreviewUrl(),
              null,
              null,
              null,
              false,
              null,
              null,
              false,
              null,
              null);
          sharedFileUrl = meta.getPreviewUrl();
          name = meta.getName();
        } catch (Exception e) {
          XRayEntityUtils.addException(segment, e);
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FileNotFound"),
              HttpStatus.NOT_FOUND,
              "FileNotFound");
          return;
        }
      }

      message.body().put(Field.NAME.getName(), name);

      VersionType versionType = VersionType.parse(versionId);
      String realVersionId = versionType.equals(VersionType.LATEST) ? metadata.getRev() : versionId;

      ByteArrayOutputStream stream = new ByteArrayOutputStream();

      if (versionType.equals(VersionType.LATEST) && Objects.nonNull(sharedFileUrl)) {
        api.sharing().getSharedLinkFile(sharedFileUrl).download(stream);
      } else {
        try (DbxDownloader<FileMetadata> dbxDownloader =
            api.files().download("rev:" + realVersionId)) {
          dbxDownloader.download(stream);
        }
      }

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
      DbxClientV2 api = connect(segment, message);
      if (Objects.isNull(api)) {
        return;
      }

      String name;

      List<String> collaboratorsList = List.of(fileIds.getExternalId());

      try {
        FileMetadata metadata = (FileMetadata) api.files().getMetadata(ID_PREFIX + fileIds.getId());
        name = metadata.getName();

        SharedFileMembers members =
            new AWSXRayDbxClient(api).sharing().listFileMembers(ID_PREFIX + fileIds.getId());

        collaboratorsList = members.getUsers().stream()
            .map(member -> member.getUser().getAccountId())
            .collect(Collectors.toList());

        if (Utils.isStringNotNullOrEmpty(fileIds.getExternalId())
            && !collaboratorsList.contains(fileIds.getExternalId())) {
          collaboratorsList.add(fileIds.getExternalId());
        }
      } catch (DbxException exception) {
        try {
          SharedFileMetadata meta = api.sharing().getFileMetadata(ID_PREFIX + fileIds.getId());
          name = meta.getName();
        } catch (Exception e) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FileNotFound"),
              HttpStatus.NOT_FOUND,
              "FileNotFound");
          return;
        }
      }

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          api.files().getMetadata("rev:" + versionId);
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
              .put(Field.COLLABORATORS.getName(), collaboratorsList),
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
    if (fileId != null) {
      fileId = ID_PREFIX + fileId;
    }
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String currentFolder = null;
      if (folderId != null) {
        folderId = folderId.equals(Field.MINUS_1.getName()) ? "/" : ID_PREFIX + folderId;
        FolderMetadata folderInfo =
            (FolderMetadata) new AWSXRayDbxClient(api).files().getMetadata(folderId);
        currentFolder = folderInfo.getPathLower();
      }
      if (currentFolder == null) {
        Metadata fileMetadata = new AWSXRayDbxClient(api).files().getMetadata(fileId);
        currentFolder = Utils.getFilePathFromPathWithSlash(fileMetadata.getPathLower());
      }

      String finalCurrentFolder = currentFolder;

      // RG: Refactored to not use global collections
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
                      fId = Utils.getFilePathFromPath(fId);
                    } catch (Exception e) {
                      log.error(e);
                      break;
                    }
                  }
                  // subfolder (e.g. subfolder/file.dwg)
                  else {
                    resultPath = fId + pathStr.substring(pathStr.lastIndexOf("..") + 2);
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
              FileMetadata fileInfo = null;
              try {
                fileInfo = (FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(resultPath);
              } catch (Exception exception) {
                log.error(exception);
              }
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.PATH.getName(), pathStr)
                  .put(
                      Field.STATE.getName(),
                      fileInfo != null ? Field.UNAVAILABLE.getName() : Field.AVAILABLE.getName());
            } catch (Exception e) {
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

  public void doFindXRef(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    String requestFolderId = jsonObject.getString(Field.FOLDER_ID.getName());
    JsonArray collaborators = jsonObject.getJsonArray(Field.COLLABORATORS.getName());
    String userId = jsonObject.getString(Field.USER_ID.getName());
    if (fileId != null && !fileId.startsWith(ID_PREFIX)) {
      fileId = ID_PREFIX + fileId;
    }

    JsonArray path = jsonObject.getJsonArray(Field.PATH.getName());
    if (collaborators.isEmpty()) {
      String externalId = findExternalId(segment, message, jsonObject, storageType);
      if (Utils.isStringNotNullOrEmpty(externalId)) {
        collaborators.add(externalId);
      }
    }
    if (segment != null) {
      if (requestFolderId != null) {
        segment.putAnnotation("requestFolderId", requestFolderId);
      }
      segment.putAnnotation(Field.COLLABORATORS.getName(), collaborators.toString());
      if (userId != null) {
        segment.putAnnotation(Field.USER_ID.getName(), userId);
      }
      if (path != null) {
        segment.putAnnotation(Field.PATH.getName(), path.toString());
      }
      if (fileId != null) {
        segment.putAnnotation(Field.FILE_ID.getName(), fileId);
      }
    }

    try {
      for (Object externalId : collaborators) {
        // get list of accounts
        Iterator<Item> foreignUsers = Collections.emptyIterator();
        try {
          Item dbUser = ExternalAccounts.getExternalAccount(userId, (String) externalId);
          if (dbUser != null) {
            foreignUsers = Collections.singletonList(dbUser).iterator();
          }
        } catch (Exception e) {
          // todo form Exception in case we don't receive
        }
        while (foreignUsers.hasNext()) {
          Item foreignUser = foreignUsers.next();
          externalId = foreignUser.getString(Field.EXTERNAL_ID_LOWER.getName());
          DbxClientV2 api = connect(foreignUser);
          if (api == null) {
            continue;
          }

          AWSXRayDbxClient client = new AWSXRayDbxClient(api);

          String currentFolder = null;
          if (requestFolderId != null) {
            requestFolderId =
                requestFolderId.equals(Field.MINUS_1.getName()) ? "/" : ID_PREFIX + requestFolderId;
            FolderMetadata folderInfo =
                (FolderMetadata) client.files().getMetadata(requestFolderId);
            currentFolder = folderInfo.getPathLower() + "/";
          }
          boolean isSharedFolder = false;
          if (currentFolder == null) {
            try {
              Metadata fileMetadata = client.files().getMetadata(fileId);
              currentFolder = Utils.getFilePathFromPathWithSlash(fileMetadata.getPathLower());
            } catch (Exception e) {
              // try to get as shared file
              SharedFileMetadata meta = client.sharing().getFileMetadata(fileId);
              currentFolder = meta.getParentSharedFolderId();
              isSharedFolder = true;
            }
          }
          List<String> pathList = path.getList();
          final Object finalExternalId = externalId;
          final String finalCurrentFolder = currentFolder;
          boolean finalIsSharedFolder = isSharedFolder;
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

                String resultPathOrId = finalCurrentFolder;

                if (array.length == 1 || (array.length == 2 && array[0].trim().isEmpty())) {
                  if (!finalIsSharedFolder) {
                    resultPathOrId = finalCurrentFolder + array[array.length - 1];
                  }
                } else {
                  for (String s : array) {
                    if (s.isEmpty()) {
                      continue;
                    }

                    if ("..".equals(s)) {
                      resultPathOrId = goUp(resultPathOrId);
                    } else {
                      resultPathOrId = goDown(resultPathOrId, s);
                    }
                  }
                }

                resultPathOrId = resultPathOrId.substring(0, resultPathOrId.length() - 1);
                findFileInFolder(api, resultPathOrId, pathFiles, (String) finalExternalId);

                if (pathFiles.isEmpty()) {
                  String rootPath = finalCurrentFolder + Utils.getFileNameFromPath(pathStr);
                  if (!rootPath.equals(resultPathOrId)) {
                    findFileInFolder(api, rootPath, pathFiles, (String) finalExternalId);
                  }
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
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  private String goDown(String folderId, String pathStr) {
    return folderId + pathStr + "/";
  }

  private String goUp(final String folderId) {
    if (folderId == null || !folderId.contains("/")) {
      return null;
    }
    try {
      String result = folderId.substring(0, folderId.lastIndexOf("/"));
      result = result.substring(0, result.lastIndexOf("/") + 1);

      return result;
    } catch (Exception e) {
      log.error("Error on going up in DropBox; folderId: " + folderId, e);
      return null;
    }
  }

  private void findFileInFolder(
      DbxClientV2 api, String resultPath, JsonArray pathFiles, String externalId) {
    String exceptionPrefix = "findFileInFolder - externalId: " + externalId + " resultPath: "
        + resultPath + " pathFiles: " + pathFiles;
    try {
      FileMetadata fileInfo =
          (FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(resultPath);
      long server = fileInfo.getServerModified().getTime();
      long client = fileInfo.getClientModified().getTime();
      long updateDate = Math.max(server, client);
      String resultId = fileInfoToId(fileInfo);
      String owner = emptyString;
      boolean isOwner = false;
      try {
        SharedFileMembers members =
            new AWSXRayDbxClient(api).sharing().listFileMembers(fileInfo.getId());
        for (UserMembershipInfo user : members.getUsers()) {
          String accountId = user.getUser().getAccountId();
          BasicAccount basicAccount = new AWSXRayDbxClient(api).users().getAccount(accountId);
          if (AccessLevel.OWNER.equals(user.getAccessType())) {
            isOwner = accountId.equals(externalId);
            owner = basicAccount.getName().getDisplayName();
            break;
          }
        }
      } catch (Exception e) {
        log.info("Error on searching for xref in possible folder in DropBox " + exceptionPrefix, e);
      }
      pathFiles.add(new JsonObject()
          .put(
              Field.ENCAPSULATED_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, resultId))
          .put(Field.OWNER.getName(), owner)
          .put(Field.IS_OWNER.getName(), isOwner)
          .put(Field.UPDATE_DATE.getName(), updateDate)
          .put(Field.CHANGER.getName(), emptyString)
          .put(Field.SIZE.getName(), Utils.humanReadableByteCount(fileInfo.getSize()))
          .put(Field.SIZE_VALUE.getName(), fileInfo.getSize())
          .put(Field.NAME.getName(), fileInfo.getName())
          .put(Field.STORAGE_TYPE.getName(), storageType.name()));
    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in DropBox " + exceptionPrefix, e);
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
    String fileIdWithoutPrefix =
        fileId.startsWith(ID_PREFIX) ? fileId.substring(ID_PREFIX.length()) : fileId;
    String fileIdWithPrefix = ID_PREFIX + fileIdWithoutPrefix;
    String linkOwnerIdentity = null;
    try {
      linkOwnerIdentity =
          super.deleteSharedLink(segment, message, fileIdWithPrefix, externalId, userId);
    } catch (Exception ignored) {
    }
    if (linkOwnerIdentity == null) {
      try {
        linkOwnerIdentity =
            super.deleteSharedLink(segment, message, fileIdWithoutPrefix, externalId, userId);
      } catch (Exception ex) {
        handleException(segment, message, ex);
        return;
      }
    }
    sendOK(
        segment,
        message,
        new JsonObject().put(Field.LINK_OWNER_IDENTITY.getName(), linkOwnerIdentity),
        mills);
  }

  public void doCreateSharedLink(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = jsonObject.getString(Field.FILE_ID.getName());
    Boolean export = jsonObject.getBoolean(Field.EXPORT.getName());
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    final long endTime = jsonObject.containsKey(Field.END_TIME.getName())
        ? jsonObject.getLong(Field.END_TIME.getName())
        : 0L;
    final String password = jsonObject.getString(Field.PASSWORD.getName());
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
      externalId = findExternalId(segment, message, jsonObject, storageType);
      if (externalId != null) {
        jsonObject.put(Field.EXTERNAL_ID.getName(), externalId);
      }
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      final String dwgId = fileId.startsWith(ID_PREFIX) ? fileId : (ID_PREFIX + fileId);
      SharedFileMembers members = new AWSXRayDbxClient(api).sharing().listFileMembers(dwgId);

      List<String> collaboratorsList = members.getUsers().stream()
          .map(member -> member.getUser().getAccountId())
          .collect(Collectors.toList());

      if (!collaboratorsList.contains(externalId)) {
        collaboratorsList.add(externalId);
      }
      FileMetadata metadata;
      try {
        metadata = (FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(dwgId);
      } catch (Exception e) {
        // try to get as shared file
        SharedFileMetadata meta = new AWSXRayDbxClient(api).sharing().getFileMetadata(dwgId);
        metadata = new FileMetadata(
            meta.getName(),
            meta.getId(),
            meta.getTimeInvited(),
            meta.getTimeInvited(),
            "74353beb4dde7fb",
            0,
            "/",
            "/",
            meta.getParentSharedFolderId(),
            meta.getPreviewUrl(),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null);
      }

      final String userId = jsonObject.getString(Field.USER_ID.getName());

      try {
        final boolean oldExport = getLinkExportStatus(fileId, externalId, userId);
        final String externalEmail = ExternalAccounts.getExternalEmail(userId, externalId);
        PublicLink newLink = super.initializePublicLink(
            fileId,
            externalId,
            userId,
            storageType,
            externalEmail,
            metadata.getName(),
            export,
            endTime,
            password);

        newLink.setPath(metadata.getPathLower());
        newLink.setCollaboratorsList(collaboratorsList);
        try {
          if (jsonObject.containsKey(Field.RESET_PASSWORD.getName())
              && jsonObject.getBoolean(Field.RESET_PASSWORD.getName())
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
    DbxClientV2 api;
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(item);
    if (api == null) {
      sendError(segment, message, emptyString, HttpStatus.FORBIDDEN);
      return;
    }
    try {
      FileMetadata metadata;
      final String dwgId = fileId.startsWith(ID_PREFIX) ? fileId : (ID_PREFIX + fileId);
      try {
        metadata = (FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(dwgId);
      } catch (Exception e) {
        SharedFileMetadata meta = new AWSXRayDbxClient(api).sharing().getFileMetadata(dwgId);
        metadata = new FileMetadata(
            meta.getName(),
            meta.getId(),
            meta.getTimeInvited(),
            meta.getTimeInvited(),
            "74353beb4dde7fb",
            0,
            "/",
            "/",
            meta.getParentSharedFolderId(),
            meta.getPreviewUrl(),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null);
      }
      String verId = metadata.getRev();
      String dbId = fileInfoToId(metadata);
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), dbId, verId);

      String changer = item.get("foreign_user").toString();
      String changerId = emptyString;

      FileSharingInfo sharing = metadata.getSharingInfo();
      if (sharing != null) {
        try {
          BasicAccount basicAccount =
              new AWSXRayDbxClient(api).users().getAccount(sharing.getModifiedBy());
          changer = basicAccount.getName().getDisplayName();
          changerId = basicAccount.getAccountId();
        } catch (Exception ignore) {
          // there can be exception for non existent accounts - it's not that important
        }
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
          .put(Field.DELETED.getName(), false)
          .put(
              Field.LINK.getName(),
              config.getProperties().getUrl() + "file/" + fileId + "?token=" + token) // NON-NLS
          .put(Field.FILE_NAME.getName(), metadata.getName())
          .put(VERSION_ID, verId)
          .put(Field.CREATOR_ID.getName(), creatorId)
          .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
          .put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
          .put(Field.EXPORT.getName(), export)
          .put(
              Field.UPDATE_DATE.getName(),
              Math.max(
                  metadata.getServerModified().getTime(),
                  metadata.getClientModified().getTime()))
          .put(Field.CHANGER.getName(), changer)
          .put(Field.CHANGER_ID.getName(), changerId);
      sendOK(segment, message, json, mills);
      return;
    } catch (Exception ignored) {
    }
    if (Utils.isStringNotNullOrEmpty(jsonObject.getString(Field.USER_ID.getName()))) {
      super.deleteSubscriptionForUnavailableFile(
          fileId, jsonObject.getString(Field.USER_ID.getName()));
    }
    sendError(
        segment, message, Utils.getLocalizedString(message, "FL6"), HttpStatus.FORBIDDEN, "FL6");
  }

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
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    if (!fileId.startsWith(ID_PREFIX)) {
      fileId = ID_PREFIX + fileId;
    }
    DbxClientV2 api;
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(item);
    if (api == null) {
      sendError(segment, message, emptyString, HttpStatus.FORBIDDEN);
      return;
    }
    try {
      FileMetadata metadata;
      String sharedFileUrl = null;
      try {
        metadata = (FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(fileId);
      } catch (Exception e) {
        SharedFileMetadata meta = new AWSXRayDbxClient(api).sharing().getFileMetadata(fileId);
        metadata = new FileMetadata(
            meta.getName(),
            meta.getId(),
            meta.getTimeInvited(),
            meta.getTimeInvited(),
            "74353beb4dde7fb",
            0,
            "/",
            "/",
            meta.getParentSharedFolderId(),
            meta.getPreviewUrl(),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null);
        sharedFileUrl = meta.getPreviewUrl();
      }
      String versionId = metadata.getRev();
      String name = metadata.getName();
      if (sharedFileUrl != null) {
        new AWSXRayDbxClient(api).sharing().getSharedLinkFile(sharedFileUrl).download(stream);
      } else {
        if (returnDownloadUrl) {
          String downloadUrl = new AWSXRayDbxClient(api).files().getDownloadUrl(fileId);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(
                segment, message, downloadUrl, metadata.getSize(), versionId, stream, mills);
            return;
          }
        }
        new AWSXRayDbxClient(api).files().download(fileId).download(stream);
      }
      finishGetFile(
          message, null, null, stream.toByteArray(), storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception exception) {
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, exception.getLocalizedMessage(), null);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL6"),
          HttpStatus.BAD_REQUEST,
          "FL6");
    }
    recordExecutionTime("getFileByToken", System.currentTimeMillis() - mills);
  }

  public void doDeleteVersion(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doGlobalSearch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String query = getRequiredString(segment, Field.QUERY.getName(), message, message.body());
    boolean isAdmin = message.body().getBoolean(Field.IS_ADMIN.getName());
    if (userId == null || query == null) {
      XRayManager.endSegment(segment);
      return;
    }
    try {
      Iterator<Item> accounts = ExternalAccounts.getExternalAccountsByUserId(userId, storageType);

      List<Item> array = StreamSupport.stream(
              Spliterators.spliteratorUnknownSize(accounts, 0), false)
          .collect(Collectors.toList());

      // RG: Refactored to not use global collection
      JsonArray result = new JsonArray(array.parallelStream()
          .map(dbUser -> {
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            String accessToken = dbUser.get(Field.ACCESS_TOKEN.getName()) != null
                ? dbUser.getString(Field.ACCESS_TOKEN.getName())
                : emptyString;
            try {
              accessToken =
                  EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
            } catch (Exception e) {
              log.error(e);
              return null;
            }
            String externalId = dbUser.getString(Field.EXTERNAL_ID_LOWER.getName());
            Entity subSegment = XRayManager.createSubSegment(operationGroup, segment, externalId);
            DbxRequestConfig config = new DbxRequestConfig("ares-kudo");
            DbxClientV2 api = new DbxClientV2(config, accessToken);
            boolean hasMore = false;
            String cursor = null;
            Map<String, String> parentsMap = new HashMap<>();
            do {
              SearchV2Result searchResult = null;
              try {
                if (hasMore && cursor != null) {
                  searchResult = api.files().searchContinueV2(cursor);
                } else {
                  searchResult = api.files().searchV2(query);
                }
              } catch (DbxException e) {
                log.error(e);
              }
              if (searchResult != null) {
                hasMore = searchResult.getHasMore();
                cursor = searchResult.getCursor();
                for (SearchMatchV2 searchMatch : searchResult.getMatches()) {
                  try {
                    Metadata metadata = searchMatch.getMetadata().getMetadataValue();
                    String parent = Field.MINUS_1.getName();
                    String folderPath = metadata.getPathLower();

                    if (Utils.isStringNotNullOrEmpty(folderPath)
                        &&
                        // DB will return null if file's shared
                        (folderPath.indexOf("/")
                            != folderPath.lastIndexOf("/")) // ignore folders in root (e.g. /dwgs)
                    ) {
                      folderPath = Utils.getFilePathFromPath(folderPath);
                      if (Utils.isStringNotNullOrEmpty(folderPath)) {
                        if (parentsMap.containsKey(folderPath)) {
                          parent = parentsMap.get(folderPath);
                        } else {
                          parent = fileInfoToId(
                              new AWSXRayDbxClient(api).files().getMetadata(folderPath));
                          parentsMap.put(folderPath, parent);
                        }
                      }
                    }
                    String storageOwner = dbUser.get("foreign_user").toString();
                    if (metadata instanceof FolderMetadata) {
                      foldersJson.add(getFolderJson(
                          (FolderMetadata) metadata, parent, api, externalId, null, false));
                    } else if (metadata instanceof FileMetadata) {
                      filesJson.add(getFileJson(
                          (FileMetadata) metadata,
                          parent,
                          api,
                          externalId,
                          false,
                          false,
                          isAdmin,
                          userId,
                          storageOwner,
                          new HashMap<>(),
                          false,
                          false,
                          false));
                    }
                  } catch (DbxException ex) {
                    log.error(ex);
                  } catch (Exception ex) { // let's catch all errors and ignore them if possible
                    log.error(ex);
                  }
                }
              } else {
                // just in case
                cursor = null;
                hasMore = false;
              }
            } while (hasMore);
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.toString())
                .put(
                    Field.EXTERNAL_ID.getName(),
                    dbUser.getString(Field.EXTERNAL_ID_LOWER.getName()))
                .put(Field.NAME.getName(), dbUser.getString(Field.EMAIL.getName()))
                .put(Field.FILES.getName(), filesJson)
                .put(Field.FOLDERS.getName(), foldersJson);
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), 0);
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
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
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
          Metadata meta;
          String folderId;
          if (isFolder && objectId.equals(ROOT_FOLDER_ID)) {
            folderId = emptyString;
          } else {
            folderId = ID_PREFIX + objectId;
          }
          meta = api.files().getMetadata(folderId);
          if (isFolder) {
            String name = meta.getName();
            name = Utils.checkAndRename(folderNames, name, true);
            folderNames.add(name);
            zipFolder(stream, api, folderId, filter, recursive, name, new HashSet<>(), 0, request);
          } else {
            long fileSize = ((FileMetadata) meta).getSize();
            if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
              excludeFileFromRequest(request, meta.getName(), ExcludeReason.Large);
              return null;
            }
            addZipEntry(stream, api, meta, filter, filteredFileNames, emptyString, fileNames);
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
    String filter = message.body().getString(Field.FILTER.getName());
    final String requestFolderId = message.body().getString(Field.FOLDER_ID.getName());
    boolean recursive = message.body().getBoolean(Field.RECURSIVE.getName());
    Item request = ZipRequests.getZipRequest(userId, requestFolderId, requestId);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    DbxClientV2 api = connect(segment, message);
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
            XRayManager.createStandaloneSegment(operationGroup, segment, "DropboxZipFolderSegment");
        String folderId = requestFolderId;
        if (folderId.equals(ROOT_FOLDER_ID)) {
          folderId = emptyString;
        } else {
          folderId = ID_PREFIX + requestFolderId;
        }
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
      DbxClientV2 api,
      String id,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws DbxException, IOException {
    ListFolderResult list =
        new AWSXRayDbxClient(api).files().listFolder(id); // listFolderBuilder(id).start();
    if (!Utils.isListNotNullOrEmpty(list.getEntries())) {
      ZipEntry zipEntry = new ZipEntry(path + S3Regional.pathSeparator);
      stream.putNextEntry(zipEntry);
      stream.write(new byte[0]);
      stream.closeEntry();
      return;
    }
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    for (Metadata meta : list.getEntries()) {
      String properPath = path.isEmpty() ? path : path + File.separator;
      if (meta instanceof FolderMetadata && recursive) {
        String name = meta.getName();
        name = Utils.checkAndRename(folderNames, name, true);
        folderNames.add(name);
        if (recursionDepth <= MAX_RECURSION_DEPTH) {
          recursionDepth += 1;
          zipFolder(
              stream,
              api,
              ((FolderMetadata) meta).getId(),
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
      } else if (meta instanceof FileMetadata) {
        long fileSize = ((FileMetadata) meta).getSize();
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, meta.getName(), ExcludeReason.Large);
          return;
        }
        addZipEntry(stream, api, meta, filter, filteredFileNames, properPath, fileNames);
      }
    }
  }

  private void addZipEntry(
      ZipOutputStream stream,
      DbxClientV2 api,
      Metadata meta,
      String filter,
      Set<String> filteredFileNames,
      String properPath,
      Set<String> fileNames)
      throws DbxException, IOException {
    String name = meta.getName();
    ByteArrayOutputStream arrayStream = new ByteArrayOutputStream();
    new AWSXRayDbxClient(api).files().download(meta.getPathLower()).download(arrayStream);
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

  public void doGetFolderPath(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.ID.getName());
    if (id == null || id.equals("undefined")) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IdMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    JsonArray result = new JsonArray();
    String accountId = message.body().getString(Field.EXTERNAL_ID.getName());
    if (!id.equals(Field.MINUS_1.getName())) {
      DbxClientV2 api = connect(segment, message);
      if (api == null) {
        return;
      }
      try {
        accountId = api.users().getCurrentAccount().getAccountId();
        getPath(api, ID_PREFIX + id, accountId, result);
      } catch (Exception e) {
        handleException(segment, message, e);
      }
    }
    result.add(new JsonObject()
        .put(Field.NAME.getName(), "~")
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(storageType, accountId, Field.MINUS_1.getName()))
        .put(Field.VIEW_ONLY.getName(), false));
    sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), result), mills);
  }

  private void getPath(DbxClientV2 api, String id, String accountId, JsonArray result)
      throws Exception {
    if (id.equals("/") || id.isEmpty() || id.equals(Field.MINUS_1.getName())) {
      return;
    }
    FolderMetadata metadata = (FolderMetadata) new AWSXRayDbxClient(api).files().getMetadata(id);
    FolderSharingInfo sharing = metadata.getSharingInfo();
    boolean viewOnly = sharing != null && sharing.getReadOnly();
    String name = metadata.getName();
    String folderId = fileInfoToId(metadata);

    String parentPath = metadata.getPathLower();
    parentPath = Utils.getFilePathFromPath(parentPath);
    if (parentPath.isEmpty()) {
      parentPath = Field.MINUS_1.getName();
    }

    result.add(new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(storageType, accountId, folderId))
        // NON-NLS
        .put(Field.NAME.getName(), name)
        .put(Field.VIEW_ONLY.getName(), viewOnly));
    getPath(api, parentPath, accountId, result);
  }

  public void doEraseAll(Message<JsonObject> message) {
    sendNotImplementedError(message);
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
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      id = ID_PREFIX + id;
      new AWSXRayDbxClient(api).files().permanentlyDelete(id);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doGetThumbnail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      id = ID_PREFIX + id;
      Metadata metadata = new AWSXRayDbxClient(api).files().getMetadata(id);
      String verId = ((FileMetadata) metadata).getRev();
      String dbId = fileInfoToId(metadata);
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
                          .put(Field.FILE_ID.getName(), dbId)
                          .put(VERSION_ID, verId)
                          .put(Field.PATH.getName(), id)
                          .put(Field.EXT.getName(), Extensions.getExtension(metadata.getName()))))
              .put(Field.FORCE.getName(), true));
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), dbId, verId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  "thumbnailStatus",
                  ThumbnailsManager.getThumbnailStatus(dbId, storageType.name(), verId, true, true))
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
  protected <T> boolean checkFile(
      Entity segment, Message<T> message, JsonObject json, String fileId) {
    try {
      DbxClientV2 api = connect(segment, message, json, false);
      if (api != null) {
        if (!fileId.contains(ID_PREFIX)) {
          fileId = ID_PREFIX + fileId;
        }
        Metadata regularFile = api.files().getMetadata(fileId);
        SharedFileMetadata sharedFile = api.sharing().getFileMetadata(fileId);
        return regularFile != null || sharedFile != null;
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
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
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
    DbxClientV2 api = connect(segment, message, jsonObject, false);
    if (api == null) {
      return;
    }
    try {
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
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);
      Metadata metadata;
      final String fileId = id.contains(":") ? id : (ID_PREFIX + id);
      if (isFile) {
        try {
          metadata = dbxClientV2.files().getMetadata(fileId);
        } catch (Exception e) {
          // try to get as shared file
          SharedFileMetadata meta = dbxClientV2.sharing().getFileMetadata(fileId);
          metadata = new FileMetadata(
              meta.getName(),
              meta.getId(),
              meta.getTimeInvited(),
              meta.getTimeInvited(),
              "74353beb4dde7fb",
              0,
              "/",
              "/",
              meta.getParentSharedFolderId(),
              meta.getPreviewUrl(),
              null,
              null,
              null,
              false,
              null,
              null,
              false,
              null,
              null);
        }
      } else {
        metadata = dbxClientV2.files().getMetadata(fileId);
      }
      FullAccount account = dbxClientV2.users().getCurrentAccount();
      JsonObject json;
      String folderPath = metadata.getPathLower();
      folderPath = Utils.getFilePathFromPath(folderPath);
      String parent;
      if (folderPath.isEmpty()) {
        parent = Field.MINUS_1.getName();
      } else {
        parent = fileInfoToId(new AWSXRayDbxClient(api).files().getMetadata(folderPath));
      }

      if (isFile) {
        String storageOwner = account.getName().getDisplayName();
        json = getFileJson(
            (FileMetadata) metadata,
            parent,
            api,
            account.getAccountId(),
            full,
            true,
            isAdmin,
            userId,
            storageOwner,
            new HashMap<>(),
            false,
            false,
            false);
      } else {
        json = getFolderJson(
            (FolderMetadata) metadata,
            parent,
            api,
            account.getAccountId(),
            account.getName().getDisplayName(),
            full);
      }
      if (!isFile && ((FolderMetadata) metadata).getSharedFolderId() == null) {
        if (metadata.getParentSharedFolderId() != null) {
          json.put("canShare", false);
        } else {
          String accessToken = jsonObject.getString("accessToken");
          HttpResponse<String> response = AWSXRayUnirest.post(
                  "https://" + DbxHost.DEFAULT.getApi() + "/2/sharing/validate_folder_path",
                  "Dropbox.validateFolderPath")
              .header("Authorization", "Bearer " + accessToken)
              .header("Content-type", "application/json")
              .body(
                  new JsonObject()
                      .put(Field.PATH.getName(), json.getString(Field.PATH.getName()))
                      .toString()
                  /*.put("actions", new JSONArray().put("invite_editor").put("invite_viewer").put("invite_viewer_no_comment"))*/ )
              .asString();
          json.put("canShare", response.getStatus() == HttpStatus.OK);
        }
      } else {
        json.put("canShare", true);
      }
      sendOK(segment, message, json, mills);
    } catch (Exception e) {
      super.deleteSubscriptionForUnavailableFile(id, jsonObject.getString(Field.USER_ID.getName()));
      handleException(segment, message, e);
    }
  }

  public void doDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FOLDER_ID.getName(), message, message.body());
    String name = getRequiredString(segment, Field.NAME.getName(), message, message.body());
    Boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName(), true);
    if (id == null || name == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      MemberSelector memberSelector;
      if (name.contains("@")) {
        memberSelector = MemberSelector.email(name);
      } else {
        memberSelector = MemberSelector.dropboxId(name);
      }
      id = ID_PREFIX + id;

      if (isFolder) {
        String folderId = ((FolderMetadata) new AWSXRayDbxClient(api).files().getMetadata(id))
            .getSharedFolderId();
        if (folderId != null) {
          LaunchResultBase launchResultBase =
              new AWSXRayDbxClient(api).sharing().removeFolderMember(folderId, memberSelector);

          String jobIdValue = launchResultBase.getAsyncJobIdValue();
          boolean inProgress = true;
          while (inProgress) {
            RemoveMemberJobStatus status =
                new AWSXRayDbxClient(api).sharing().checkRemoveMemberJobStatus(jobIdValue);
            inProgress = status.isInProgress();
            Thread.sleep(200);
          }
        }
      } else {
        new AWSXRayDbxClient(api).sharing().removeFileMember2(id, memberSelector);
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.USERNAME.getName(), message.body().getString(Field.EMAIL.getName())),
          mills);
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
    Boolean isFolder = message.body().getBoolean(Field.IS_FOLDER.getName(), true);
    if (id == null || email == null || role == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String folder;
      if (id.equals(Field.MINUS_1.getName())) {
        folder = "/";
      } else {
        folder = ID_PREFIX + id;
      }
      AccessLevel accessLevel = AccessLevel.valueOf(role);
      MemberSelector memberSelector = MemberSelector.email(email);
      AddMember addMember = new AddMember(memberSelector, accessLevel);
      if (isFolder) {
        FolderMetadata metadata =
            (FolderMetadata) new AWSXRayDbxClient(api).files().getMetadata(folder);
        String folderId = metadata.getSharedFolderId();
        if (folderId != null) {
          // 28.03.2017 with current version of DB API it's easier to just remove member and add
          // again with new access level,
          // instead of looking for existing member and changing it's al
          try {
            LaunchResultBase launchResultBase =
                new AWSXRayDbxClient(api).sharing().removeFolderMember(folderId, memberSelector);
            String jobIdValue = launchResultBase.getAsyncJobIdValue();
            boolean inProgress = true;
            while (inProgress) {
              RemoveMemberJobStatus status =
                  new AWSXRayDbxClient(api).sharing().checkRemoveMemberJobStatus(jobIdValue);
              inProgress = status.isInProgress();
            }
          } catch (Exception ignore) {
          }
        } else {
          try {
            ShareFolderLaunch shareFolderLaunch =
                new AWSXRayDbxClient(api).sharing().shareFolder(metadata.getPathDisplay());
            String jobIdValue = shareFolderLaunch.getAsyncJobIdValue();
            boolean inProgress = true;
            while (inProgress) {
              ShareFolderJobStatus status =
                  new AWSXRayDbxClient(api).sharing().checkShareJobStatus(jobIdValue);
              inProgress = status.isInProgress();
            }
          } catch (Exception ignore) {
          }
          folderId = ((FolderMetadata) new AWSXRayDbxClient(api).files().getMetadata(folder))
              .getSharedFolderId();
        }
        new AWSXRayDbxClient(api).sharing().addFolderMember(folderId, List.of(addMember));
      } else {
        try {
          new AWSXRayDbxClient(api).sharing().removeFileMember2(folder, memberSelector);
        } catch (Exception ignore) {
        }
        new AWSXRayDbxClient(api).sharing().addFileMember(folder, List.of(memberSelector));
      }
      if (reply) {
        sendOK(segment, message, mills);
      }
    } catch (Exception e) {
      if (reply) {
        handleException(segment, message, e);
      }
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
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);

      String originalId = id;
      id = ID_PREFIX + id;
      Metadata metadata = dbxClientV2.files().getMetadata(id);

      String folderPath = Utils.getFilePathFromPath(metadata.getPathLower());
      String parent;
      if (folderPath.isEmpty()) {
        parent = Field.MINUS_1.getName();
      } else {
        parent = fileInfoToId(dbxClientV2.files().getMetadata(folderPath));
      }
      parent = Utils.getEncapsulatedId(storageType, externalId, parent);

      String path = metadata.getPathLower();
      String newPath = Utils.getFilePathFromPath(path) + "/" + name;
      RelocationResult relResult = dbxClientV2.files().copyV2(id, newPath);
      Metadata copyMetadata = relResult.getMetadata();
      String newId = fileInfoToId(copyMetadata);
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
                  originalId,
                  storageType,
                  newId,
                  doIncludeResolvedComments,
                  doIncludeDeletedComments);
            });
      }
      if (doCopyShare) {
        boolean finalIsFile = isFile;
        String finalId = id;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(Field.COPY_SHARE.getName())
            .runWithoutSegment(() -> {
              List<CollaboratorInfo> collaboratorInfoList;
              try {
                collaboratorInfoList =
                    getItemCollaborators(dbxClientV2, metadata, finalId, finalIsFile);
              } catch (DbxException ex) {
                log.error("Error occurred in getting shares : " + ex);
                return;
              }
              message
                  .body()
                  .put(Field.FOLDER_ID.getName(), newId)
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
      String finalNewId = Utils.getEncapsulatedId(storageType, externalId, newId);
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(), finalNewId)
              .put(Field.PARENT_ID.getName(), parent),
          mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  @Override
  public void doCreateShortcut(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  public void doPromoteVersion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String rev = getRequiredString(segment, Field.VER_ID.getName(), message, message.body());
    if (id == null || rev == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      Metadata metadata = new AWSXRayDbxClient(api).files().getMetadata(ID_PREFIX + id);

      FileMetadata file = new AWSXRayDbxClient(api).files().restore(metadata.getPathLower(), rev);
      if (file == null) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "ProblemsWithDropboxClient"),
            HttpStatus.FORBIDDEN);
      } else {
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.FILE_ID.getName(), id)
                .put(Field.VERSION_ID.getName(), file.getRev()),
            mills);
      }
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doGetLatestVersionId(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (id == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String versionId = null;
      //            ListRevisionsResult res = new AWSXRayDbxClient(api).files()
      //            .listRevisions(ID_PREFIX + id);
      //            if (!res.getEntries().isEmpty())
      //                versionId = res.getEntries().get(0).getRev();
      Metadata metadata = new AWSXRayDbxClient(api).files().getMetadata(ID_PREFIX + id);
      if (metadata != null) {
        versionId = ((FileMetadata) metadata).getRev();
      }
      sendOK(segment, message, new JsonObject().put(VERSION_ID, versionId), mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  // Transforms Dropbox's FileMetadata into our VersionInfo
  private VersionInfo getVersionInfo(
      String fileId,
      FileMetadata fileMetadata,
      String lastVersionId,
      boolean isWorkable,
      boolean canPromote,
      BasicAccount modifierAccount,
      String currentUserId) {
    // apparently DB doesn't provide modifier's info
    VersionInfo versionInfo =
        new VersionInfo(fileMetadata.getRev(), fileMetadata.getServerModified().getTime(), null);
    versionInfo.setSize(fileMetadata.getSize());
    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setDownloadable(isWorkable && fileMetadata.getIsDownloadable());
    versionPermissions.setCanRename(false);
    versionPermissions.setCanPromote(
        canPromote && isWorkable && !fileMetadata.getRev().equals(lastVersionId));
    versionPermissions.setCanDelete(false);
    versionInfo.setPermissions(versionPermissions);

    VersionModifier versionModifier = new VersionModifier();
    if (modifierAccount != null) {
      versionModifier.setId(modifierAccount.getAccountId());
      versionModifier.setCurrentUser(currentUserId.equals(modifierAccount.getAccountId()));
      versionModifier.setEmail(modifierAccount.getEmail());
      versionModifier.setName(modifierAccount.getName().getDisplayName());
      versionModifier.setPhoto(modifierAccount.getProfilePhotoUrl());
    }
    versionInfo.setModifier(versionModifier);

    try {
      String thumbnailName =
          ThumbnailsManager.getThumbnailName(storageType, fileId, fileMetadata.getRev());
      versionInfo.setThumbnail(ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    } catch (Exception ignored) {
    }
    return versionInfo;
  }

  public void doGetVersions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    if (fileId == null) {
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    JsonObject jsonObject = message.body();
    List<JsonObject> result = new ArrayList<>();
    try {
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);
      List<FileMetadata> res =
          dbxClientV2.files().listRevisions(ID_PREFIX + fileId).getEntries();
      Map<String, BasicAccount> accounts = new HashMap<>();
      String lastVersionId = res.get(0).getRev();
      String currentUserId = emptyString;
      int maxVersionRestorePeriod = 30;
      try {
        FullAccount currentAccount = dbxClientV2.users().getCurrentAccount();
        currentUserId = currentAccount.getAccountId();
        AccountType accountType = currentAccount.getAccountType();
        if (accountType.equals(AccountType.PRO) || accountType.equals(AccountType.BUSINESS)) {
          maxVersionRestorePeriod = 180;
        }
      } catch (Exception ignored) {
      }
      for (FileMetadata version : res) {
        boolean canPromote = TimeUnit.MILLISECONDS.toDays(
                GMTHelper.utcCurrentTime() - version.getServerModified().getTime())
            <= maxVersionRestorePeriod;
        // In Dropbox, file versions are not available to restore after 30/180 days (depends on
        // account type) | XENON-54589

        BasicAccount modifierAccount = null;
        try {
          FileSharingInfo fileSharingInfo = version.getSharingInfo();
          String modifierId = currentUserId;
          if (fileSharingInfo != null) {
            modifierId = fileSharingInfo.getModifiedBy();
          }
          if (accounts.containsKey(modifierId)) {
            modifierAccount = accounts.get(modifierId);
          } else {
            modifierAccount = dbxClientV2.users().getAccount(modifierId);
            accounts.put(modifierId, modifierAccount);
          }
        } catch (Exception ignored) {
        }

        try {
          dbxClientV2.files().getMetadata("rev:" + version.getRev());
          result.add(getVersionInfo(
                  fileId, version, lastVersionId, true, canPromote, modifierAccount, currentUserId)
              .toJson());
        } catch (Exception ignored) {
          // we don't care too much about exception itself - just if there's an exception -
          // revision is "dead"
          // see https://help.dropbox.com/files-folders/restore-delete/version-history-overview
          // 25.01.2022 xenon-55367 Anuj asked to remove unworkable versions
          // isWorkable = false;
        }
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("createThumbnailsOnGetVersions")
          .run((Segment blockingSegment) -> {
            String ext = Extensions.DWG;
            try {
              Metadata metadata = api.files().getMetadata(ID_PREFIX + fileId);
              if (metadata != null && Utils.isStringNotNullOrEmpty(metadata.getName())) {
                ext = Extensions.getExtension(metadata.getName());
              }
            } catch (DbxException ex) {
              log.warn("[DROPBOX] get versions: Couldn't get object info to get extension.", ex);
            }
            jsonObject.put(Field.STORAGE_TYPE.getName(), storageType.name());
            JsonArray requiredVersions = new JsonArray();
            String finalExt = ext;
            result.forEach(revision -> {
              // some old versions cannot be downloaded, so no reason to try and fetch info
              if (revision
                  .getJsonObject(Field.PERMISSIONS.getName())
                  .getBoolean("isDownloadable")) {
                requiredVersions.add(new JsonObject()
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.VERSION_ID.getName(), revision.getString(Field.ID.getName()))
                    .put(Field.EXT.getName(), finalExt));
              }
            });
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

  public void doUploadVersion(Message<Buffer> message) {
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
    if (!fileId.startsWith(ID_PREFIX)) {
      fileId = ID_PREFIX + fileId;
    }
    DbxClientV2 api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      String versionId;
      Metadata metadata = new AWSXRayDbxClient(api).files().getMetadata(fileId);
      if (metadata == null) {
        sendError(
            segment,
            message,
            MessageFormat.format(
                Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
            HttpStatus.NOT_FOUND);
        return;
      }

      metadata = api.files()
          .uploadBuilder(metadata.getPathDisplay())
          .withMode(WriteMode.update(((FileMetadata) metadata).getRev()))
          .start()
          .uploadAndFinish(stream);
      fileId = fileInfoToId(metadata);
      versionId = ((FileMetadata) metadata).getRev();

      eb_send(
          segment,
          ThumbnailsManager.address + ".create",
          body.put(Field.STORAGE_TYPE.getName(), storageType.name())
              .put(
                  Field.IDS.getName(),
                  new JsonArray()
                      .add(new JsonObject()
                          .put(Field.FILE_ID.getName(), fileId)
                          .put(VERSION_ID, versionId)
                          .put(Field.EXT.getName(), Extensions.getExtension(metadata.getName())))));
      BasicAccount modifierAccount = null;
      String currentUserId = emptyString;
      try {
        // we know that currentUserId = api.users().getCurrentAccount()
        // but it returns FullAccount with no ability to transform to BasicAccount
        currentUserId = ((FileMetadata) metadata).getSharingInfo().getModifiedBy();
        modifierAccount = api.users().getAccount(currentUserId);
      } catch (Exception ignored) {
      }

      sendOK(
          segment,
          message,
          getVersionInfo(
                  fileId,
                  (FileMetadata) metadata,
                  versionId,
                  false,
                  false,
                  modifierAccount,
                  currentUserId)
              .toJson(),
          mills);

      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(VERSION_ID, versionId)
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

  public void doMoveFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    String fromPath = body.getString(Field.FILE_ID.getName());
    doMove(message, segment, fromPath);
  }

  public void doMoveFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    String fromPath = getRequiredString(segment, Field.FOLDER_ID.getName(), message, body);
    doMove(message, segment, fromPath);
  }

  private void doMove(Message<JsonObject> message, Entity segment, String fromId) {
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String toId = getRequiredString(segment, Field.PARENT_ID.getName(), message, body);
    if (Field.MINUS_1.getName().equals(toId)) {
      toId = emptyString;
    } else {
      toId = ID_PREFIX + toId;
    }

    fromId = ID_PREFIX + fromId;

    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String toPath = emptyString;
      if (!toId.isEmpty()) {
        Metadata metadata = api.files().getMetadata(toId);
        if (metadata != null) {
          toPath = metadata.getPathLower();
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotGetMetadata"),
              HttpStatus.FORBIDDEN);
          return;
        }
      }
      Metadata fromMeta = api.files().getMetadata(fromId);
      String fromPath = fromMeta.getPathDisplay();
      String name = Utils.safeSubstringFromLastOccurrence(fromPath, "/");
      toPath += name;

      RelocationResult result = null;
      boolean timeout;
      do {
        try {
          result = api.files()
              .moveV2Builder(fromPath, toPath)
              .withAllowSharedFolder(true)
              .withAutorename(true)
              .start();
          timeout = false;
        } catch (RateLimitException e) {
          timeout = true;
          Thread.sleep(e.getBackoffMillis());
        }
      } while (timeout);

      if (result != null && result.getMetadata() != null) {
        sendOK(
            segment,
            message,
            new JsonObject().put(Field.FILE_ID.getName(), fileInfoToId(result.getMetadata())),
            mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "ProblemsWithDropboxClient"),
            HttpStatus.FORBIDDEN);
      }
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
    if (folderId != null) {
      if (Field.MINUS_1.getName().equals(folderId)) {
        folderId = emptyString;
      } else {
        folderId = ID_PREFIX + folderId;
      }
    }
    if (fileId != null) {
      fileId = ID_PREFIX + fileId;
    }
    DbxClientV2 api = connect(segment, message, body, false);
    if (api == null) {
      return;
    }

    String responseName = null;
    String conflictingFileReason = body.getString(Field.CONFLICTING_FILE_REASON.getName());
    boolean fileSessionExpired = checkIfFileSessionExpired(conflictingFileReason);
    boolean isConflictFile = (conflictingFileReason != null), fileNotFound = false;
    try (InputStream stream = parsedMessage.getContentAsInputStream()) {
      String versionId;
      long updateDate = -1L;
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);
      if (parsedMessage.hasAnyContent()) {
        if (fileId != null) {
          Metadata metadata = null;
          try {
            metadata = dbxClientV2.files().getMetadata(fileId);
          } catch (GetMetadataErrorException ex) {
            if (ex.errorValue.getPathValue().isNotFound()) {
              conflictingFileReason =
                  XSessionManager.ConflictingFileReason.UNSHARED_OR_DELETED.name();
              isConflictFile = true;
              fileNotFound = true;
            }
            if (!fileNotFound) {
              sendError(segment, message, ex.errorValue.toString(), HttpStatus.BAD_REQUEST);
              return;
            }
          }

          boolean noEditingRights = false;
          // check if user still has the access to edit this file
          if (!fileNotFound) {
            SharedFileMembers members =
                dbxClientV2.sharing().listFileMembers(((FileMetadata) metadata).getId());
            noEditingRights = members.getUsers().stream()
                .noneMatch(user -> (user.getUser().getAccountId().equals(externalId)
                    && (user.getAccessType().equals(AccessLevel.OWNER)
                        || user.getAccessType().equals(AccessLevel.EDITOR))));
            if (noEditingRights && !members.getGroups().isEmpty()) {
              noEditingRights = members.getGroups().stream()
                  .noneMatch(group -> (group.getGroup().getIsMember()
                      && (group.getAccessType().equals(AccessLevel.OWNER)
                          || group.getAccessType().equals(AccessLevel.EDITOR))));
            }
          } else {
            Metadata folderMetadata = null;
            if (folderId != null) {
              try {
                folderMetadata = dbxClientV2.files().getMetadata(folderId);
              } catch (GetMetadataErrorException ex) {
                log.warn("Folder is no longer shared with the user " + folderId);
              }
            }
            if (folderMetadata != null) {
              SharedFolderMembers members = dbxClientV2
                  .sharing()
                  .listFolderMembers(((FolderMetadata) folderMetadata).getSharedFolderId());
              noEditingRights = members.getUsers().stream()
                  .noneMatch(user -> (user.getUser().getAccountId().equals(externalId)
                      && (user.getAccessType().equals(AccessLevel.OWNER)
                          || user.getAccessType().equals(AccessLevel.EDITOR))));
              if (noEditingRights && !members.getGroups().isEmpty()) {
                noEditingRights = members.getGroups().stream()
                    .noneMatch(group -> (group.getGroup().getIsMember()
                        && (group.getAccessType().equals(AccessLevel.OWNER)
                            || group.getAccessType().equals(AccessLevel.EDITOR))));
              }
            }
          }
          if (noEditingRights) {
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
          String originalFileId;
          if (!fileNotFound) {
            fileId = ((FileMetadata) metadata).getId();
            originalFileId = fileInfoToId(metadata);
            // get latest version in external storage
            versionId = ((FileMetadata) metadata).getRev();

            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
              isConflictFile =
                  isConflictingChange(userId, fileId, storageType, versionId, baseChangeId);
              if (isConflictFile) {
                conflictingFileReason =
                    XSessionManager.ConflictingFileReason.VERSIONS_CONFLICTED.name();
              }
            }
          } else {
            originalFileId = fileId;
          }

          if (!isConflictFile) {
            metadata = api.files()
                .uploadBuilder(metadata.getPathDisplay())
                .withMode(WriteMode.update(((FileMetadata) metadata).getRev()))
                .start()
                .uploadAndFinish(stream);
            fileId = fileInfoToId(metadata);
            versionId = ((FileMetadata) metadata).getRev();
          } else {
            boolean isSameFolder = false;
            String oldName = null, filePath;
            if (!fileNotFound) {
              oldName = metadata.getName();
            } else {
              Item metaData = FileMetaData.getMetaData(originalFileId, storageType.name());
              if (Objects.nonNull(metaData)) {
                oldName = metaData.getString(Field.FILE_NAME.getName());
              }
            }
            if (oldName == null) {
              oldName = unknownDrawingName;
            }
            if (noEditingRights || fileNotFound) {
              if (!dbxClientV2
                  .users()
                  .getCurrentAccount()
                  .getAccountType()
                  .equals(AccountType.BASIC)) {
                folderId = S3Regional.pathSeparator
                    + dbxClientV2.users().getCurrentAccount().getName().getDisplayName();
              } else {
                folderId = emptyString;
              }
              filePath = folderId + S3Regional.pathSeparator + oldName;
            } else {
              filePath = metadata.getPathLower();
              isSameFolder = true;
            }
            String newName = getConflictingFileName(oldName);
            String newFilePath = Utils.getFilePathFromPath(filePath) + "/" + newName;
            responseName = newName;
            metadata = api.files().upload(newFilePath).uploadAndFinish(stream);
            fileId = fileInfoToId(metadata);
            versionId = ((FileMetadata) metadata).getRev();
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
                null,
                conflictingFileReason,
                fileSessionExpired,
                isSameFolder,
                AuthManager.ClientType.getClientType(body.getString(Field.DEVICE.getName())));
          }
        } else {
          String folder = folderId;
          if (!folder.isEmpty()) {
            Metadata folderMetadata = api.files().getMetadata(folderId);
            folder = folderMetadata.getPathLower();
          }
          FileMetadata metadata;
          int retryCounter = 0;
          final int maxRetry = 5;
          while (true) { // no need statement here - there are breaks in loop
            try {
              metadata = api.files().upload(folder + "/" + name).uploadAndFinish(stream);
              if (metadata != null) {
                break;
              }
            } catch (RateLimitException rle) {
              retryCounter += 1;
              if (retryCounter >= maxRetry) {
                throw rle;
              } else {
                Thread.sleep(rle.getBackoffMillis());
              }
            }
          }
          fileId = fileInfoToId(metadata);
          versionId = metadata.getRev();
          long server = metadata.getServerModified().getTime();
          long client = metadata.getClientModified().getTime();
          updateDate = Math.max(server, client);
        }
      } else {
        if (!Utils.isStringNotNullOrEmpty(fileId)) {
          log.error("Dropbox: supplied empty fileId to upload. Has to be checked on client.");
          XRayEntityUtils.putMetadata(segment, XrayField.INCOMING_BODY, body);
          sendError(segment, message, HttpStatus.BAD_REQUEST, "DB1");
          return;
        } else {
          versionId =
              ((FileMetadata) new AWSXRayDbxClient(api).files().getMetadata(fileId)).getRev();
        }
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
                            .put(VERSION_ID, versionId)
                            .put(Field.EXT.getName(), Extensions.getExtension(name))
                            .put(Field.PRIORITY.getName(), true))));
      }
      JsonObject json = new JsonObject()
          .put(Field.IS_CONFLICTED.getName(), isConflictFile)
          .put(Field.FILE_ID.getName(), Utils.getEncapsulatedId(storageType, externalId, fileId))
          .put(VERSION_ID, versionId)
          .put(
              Field.THUMBNAIL_NAME.getName(),
              StorageType.getShort(storageType) + "_" + fileId + "_" + versionId + ".png");

      if (isConflictFile) {
        if (Utils.isStringNotNullOrEmpty(conflictingFileReason)) {
          json.put(Field.CONFLICTING_FILE_REASON.getName(), conflictingFileReason);
        }
        if (Utils.isStringNotNullOrEmpty(responseName)) {
          json.put(Field.NAME.getName(), responseName);
        }
        if (Utils.isStringNotNullOrEmpty(folderId)) {
          json.put(
              Field.FOLDER_ID.getName(),
              Utils.getEncapsulatedId(storageType, externalId, folderId));
        }
      }

      if (updateDate != -1L) {
        json.put(Field.UPDATE_DATE.getName(), updateDate);
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

      sendOK(segment, message, json, mills);

      if (isFileUpdate && parsedMessage.hasAnyContent()) {
        eb_send(
            segment,
            WebSocketManager.address + ".newVersion",
            new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(VERSION_ID, versionId)
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

    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      XRayEntityUtils.putStorageMetadata(segment, message, storageType);
      ByteArrayOutputStream stream = new ByteArrayOutputStream();
      AWSXRayDbxClient dbxClient = new AWSXRayDbxClient(api);
      FileMetadata metadata;
      String name = null;
      if (latest || versionId == null) {
        String sharedFileUrl = null;
        try {
          metadata = (FileMetadata) dbxClient.files().getMetadata(ID_PREFIX + fileId);
        } catch (DbxException e) {
          try {
            // try to get as shared file
            SharedFileMetadata meta = dbxClient.sharing().getFileMetadata(ID_PREFIX + fileId);
            metadata = new FileMetadata(
                meta.getName(),
                meta.getId(),
                meta.getTimeInvited(),
                meta.getTimeInvited(),
                "74353beb4dde7fb",
                0,
                "/",
                "/",
                meta.getParentSharedFolderId(),
                meta.getPreviewUrl(),
                null,
                null,
                null,
                false,
                null,
                null,
                false,
                null,
                null);
            sharedFileUrl = meta.getPreviewUrl();
          } catch (Exception ex) {
            // if we still have some exception - we cannot proceed.
            // Let's log it and return error
            XRayEntityUtils.addException(segment, ex);
            log.error("DB: Exception on trying to get file's info", ex);
            handleException(segment, message, ex);
            return;
          }
        }
        if (metadata == null) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "FileNotFound"),
              HttpStatus.NOT_FOUND);
          return;
        }
        versionId = metadata.getRev();
        if (sharedFileUrl != null) {
          dbxClient.sharing().getSharedLinkFile(sharedFileUrl).download(stream);
        } else {
          if (returnDownloadUrl) {
            String downloadUrl = dbxClient.files().getDownloadUrl(ID_PREFIX + fileId);
            if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
              sendDownloadUrl(
                  segment, message, downloadUrl, metadata.getSize(), versionId, stream, mills);
              return;
            }
          }
          dbxClient.files().download(ID_PREFIX + fileId).download(stream);
        }
        name = metadata.getName();
        for (String specialCharacter : windowsSpecialCharacters) {
          if (name.contains(getNonRegexSpecialCharacter(specialCharacter))) {
            name = name.replaceAll(specialCharacter, "_");
          }
        }
      } else {
        List<FileMetadata> res =
            dbxClient.files().listRevisions(ID_PREFIX + fileId).getEntries();
        long versionSize = 0L;
        for (FileMetadata version : res) {
          if (version.getRev().equals(versionId)) {
            name = version.getName();
            versionSize = version.getSize();
            break;
          }
        }
        if (returnDownloadUrl) {
          String downloadUrl = dbxClient.files().getDownloadUrl("rev:" + versionId);
          if (Utils.isStringNotNullOrEmpty(downloadUrl)) {
            sendDownloadUrl(segment, message, downloadUrl, versionSize, versionId, stream, mills);
            return;
          }
        }
        dbxClient.files().download("rev:" + versionId).download(stream);
      }
      stream.close();
      finishGetFile(
          message, start, end, stream.toByteArray(), storageType, name, versionId, downloadToken);
      XRayManager.endSegment(segment);
    } catch (Exception e) {
      log.error(e);
      DownloadRequests.setRequestData(
          downloadToken, null, JobStatus.ERROR, e.getLocalizedMessage(), null);
      handleException(segment, message, e);
    }
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
  }

  public void doGetAllFiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      XRayEntityUtils.putStorageMetadata(segment, message, storageType);
      AWSXRayDbxClient dbxClient = new AWSXRayDbxClient(api);

      JsonArray finalResult = new JsonArray();

      DbxUserListFolderBuilder listFolderBuilder = dbxClient.files().listFolderBuilder("");
      ListFolderResult result = listFolderBuilder.withRecursive(true).start();

      boolean more = true;

      do {
        for (Metadata entry : result.getEntries()) {
          finalResult.add(new JsonObject().put(Field.FILE.getName(), entry.toString()));
        }

        if (result.getHasMore()) {
          result = dbxClient.files().listFolderContinue(result.getCursor());
        } else {
          more = false;
        }

      } while (result.getHasMore() && more);

      sendOK(segment, message, new JsonObject().put(Field.RESULT.getName(), finalResult), mills);
    } catch (Exception e) {
      sendError(segment, message, "dropbox.getAllFiles unhandled error", HttpStatus.BAD_REQUEST, e);
      log.error(e);
      handleException(segment, message, e);
    }
  }

  private void doDeleteBatch(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonArray files = message.body().getJsonArray(Field.FILES.getName());
    JsonArray folders = message.body().getJsonArray(Field.FOLDERS.getName());
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      List<DeleteArg> ids = new ArrayList<>();
      if (files != null) {
        for (Object o : files) {
          String s = (String) o;
          s = Utils.parseItemId(s, Field.FILE_ID.getName()).getString(Field.FILE_ID.getName());
          ids.add(new DeleteArg(ID_PREFIX + s));
        }
      }
      if (folders != null) {
        for (Object o : folders) {
          String s = (String) o;
          s = Utils.parseItemId(s, Field.FOLDER_ID.getName()).getString(Field.FOLDER_ID.getName());
          ids.add(new DeleteArg(ID_PREFIX + s));
          // ids.add(new DeleteArg(ID_PREFIX + (String) o));
        }
      }
      DeleteBatchLaunch deleteBatchLaunch = new AWSXRayDbxClient(api).files().deleteBatch(ids);
      sendOK(segment, message, mills);

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            String asyncJobId = deleteBatchLaunch.getAsyncJobIdValue();
            try {
              DeleteBatchJobStatus status = api.files().deleteBatchCheck(asyncJobId);
              while (status.isInProgress()) {
                Thread.sleep(1000);
                status = api.files().deleteBatchCheck(asyncJobId);
              }
              if (status.isFailed()) {
                log.error(status.getFailedValue().name());
              } else {
                // RG: should be ok. Not using global collection
                status.getCompleteValue().getEntries().parallelStream().forEach(action -> {
                  if (action.isSuccess()) {
                    String id = fileInfoToId(action.getSuccessValue().getMetadata());
                    eb_send(
                        blockingSegment,
                        WebSocketManager.address + ".deleted",
                        new JsonObject().put(Field.FILE_ID.getName(), id));
                    eb_send(
                        blockingSegment,
                        RecentFilesVerticle.address + ".updateRecentFile",
                        new JsonObject()
                            .put(Field.FILE_ID.getName(), id)
                            .put(Field.ACCESSIBLE.getName(), false)
                            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
                  }
                });
              }
            } catch (DbxException | InterruptedException ex) {
              log.error(ex);
            }
          });
    } catch (Exception e) {
      handleException(segment, message, e);
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
    if (id == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FileidOrFolderidMustBeSpecified"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    DbxClientV2 api = connect(segment, message);

    if (api == null) {
      sendError(
          segment, message, Utils.getLocalizedString(message, "ApiIsNull"), HttpStatus.BAD_REQUEST);
      return;
    }
    try {
      new AWSXRayDbxClient(api).files().delete(ID_PREFIX + id);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doRestore(Message<JsonObject> message) {
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
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }

    AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);
    try {
      List<FileMetadata> revisions =
          dbxClientV2.files().listRevisions(ID_PREFIX + id).getEntries();
      if (revisions.isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "NoRevisions"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      dbxClientV2
          .files()
          .restore(revisions.get(0).getPathLower(), revisions.get(0).getRev());
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doRenameFile(Message<JsonObject> message) {
    String id = message.body().getString(Field.FILE_ID.getName());
    doRename(message, id, true);
  }

  public void doRenameFolder(Message<JsonObject> message) {
    String id = message.body().getString(Field.FOLDER_ID.getName());
    doRename(message, id, false);
  }

  private void doRename(Message<JsonObject> message, String id, boolean isFile) {
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
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String nameWithoutExt = name;
    if (isFile && nameWithoutExt.contains(".")) {
      nameWithoutExt = nameWithoutExt.substring(0, nameWithoutExt.lastIndexOf("."));
    }
    for (String specialCharacter : specialCharacters) {
      if (nameWithoutExt.contains(specialCharacter)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "specialCharacters"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      id = ID_PREFIX + id;
      Metadata fileInfo;
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);
      try {
        fileInfo = dbxClientV2.files().getMetadata(id);
      } catch (Exception e) {
        // try to get as shared file
        dbxClientV2.sharing().getFileMetadata(id);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CannotRenameSharedFile"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (fileInfo != null) {
        String path = fileInfo.getPathDisplay();
        String to = Utils.getFilePathFromPath(path) + "/" + name;

        if (path.equalsIgnoreCase(to)) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "DropboxCaseOnlyError"),
              HttpStatus.BAD_REQUEST);
          return;
        }

        RelocationResult result = api.files()
            .moveV2Builder(path, to)
            .withAllowOwnershipTransfer(true)
            .withAutorename(true)
            .start();

        // return the updated versionId after renaming the file
        id = fileInfoToId(result.getMetadata());

        JsonObject responseJson = new JsonObject()
            .put(Field.NAME.getName(), result.getMetadata().getName())
            .put(
                Field.ENCAPSULATED_ID.getName(),
                Utils.getEncapsulatedId(storageType, externalId, id));
        if (isFile) {
          responseJson.put(VERSION_ID, ((FileMetadata) result.getMetadata()).getRev());
        }

        sendOK(segment, message, responseJson, mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotGetMetadata"),
            HttpStatus.BAD_REQUEST);
      }
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doCreateFolder(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String parentPath = getRequiredString(segment, Field.PARENT_ID.getName(), message, jsonObject);
    String name = getRequiredString(segment, Field.NAME.getName(), message, jsonObject);
    if (parentPath == null) {
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
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      if (!Field.MINUS_1.getName().equals(parentPath)) {
        parentPath = ID_PREFIX + parentPath;
      } else {
        parentPath = emptyString;
      }

      if (!parentPath.isEmpty()) {
        Metadata metadata = new AWSXRayDbxClient(api).files().getMetadata(parentPath);
        if (metadata != null) {
          parentPath = metadata.getPathLower();
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "CouldNotGetMetadata"),
              HttpStatus.FORBIDDEN);
          return;
        }
      }
      CreateFolderResult result =
          new AWSXRayDbxClient(api).files().createFolderV2(parentPath + "/" + name, 0);
      if (result.getMetadata() != null) {
        String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(
                    Field.FOLDER_ID.getName(),
                    Utils.getEncapsulatedId(
                        StorageType.getShort(storageType),
                        externalId,
                        fileInfoToId(result.getMetadata()))),
            mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "ProblemsWithDropboxClient"),
            HttpStatus.FORBIDDEN);
      }
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void doGetFolderContent(Message<JsonObject> message, boolean trash) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    long metadataTimings = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String folderId = jsonObject.getString(Field.FOLDER_ID.getName());
    Boolean full = jsonObject.getBoolean(Field.FULL.getName());
    boolean force = message.body().containsKey(Field.FORCE.getName())
        && message.body().getBoolean(Field.FORCE.getName());
    final String userId = jsonObject.getString(Field.USER_ID.getName());
    if (full == null) {
      full = true;
    }
    Boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    @NonNls String fileFilter = jsonObject.getString(Field.FILE_FILTER.getName());
    String pageToken = jsonObject.getString(Field.PAGE_TOKEN.getName());
    if (fileFilter == null || fileFilter.equals(Field.ALL_FILES.getName())) {
      fileFilter = emptyString;
    }
    List<String> extensions = Extensions.getFilterExtensions(config, fileFilter, isAdmin);
    String device = jsonObject.getString(Field.DEVICE.getName());
    boolean isBrowser = AuthManager.ClientType.BROWSER.name().equals(device)
        || AuthManager.ClientType.BROWSER_MOBILE.name().equals(device);
    JsonArray thumbnail = new JsonArray();
    if (folderId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "PathMustBeSpecified"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    DbxClientV2 api = connect(segment, message);
    if (api == null) {
      return;
    }

    JsonObject messageBody = message.body();
    boolean canCreateThumbnails = canCreateThumbnails(messageBody) && isBrowser;

    List<JsonObject> foldersJson = new ArrayList<>();
    Map<String, JsonObject> filesJson = new Hashtable<>();
    Map<String, String> possibleIdsToIds = new HashMap<>();
    try {
      String path = emptyString;
      if (!Field.MINUS_1.getName().equals(folderId)) {
        path = ID_PREFIX + folderId;
      }
      AWSXRayDbxClient dbxClient = new AWSXRayDbxClient(api);
      // my and added files and folders
      ListFolderResult list;
      if (isBrowser && Utils.isStringNotNullOrEmpty(pageToken)) {
        list = dbxClient.files().listFolderContinue(pageToken);
      } else {
        if (trash) {
          DbxUserListFolderBuilder builder = dbxClient.files().listFolderBuilder("");
          list = builder
              .withIncludeDeleted(true)
              .withRecursive(false)
              .withIncludeMediaInfo(true)
              .start();
        } else {
          list = dbxClient.files().listFolder(path);
        }
      }
      XRayEntityUtils.putMetadata(
          segment,
          XrayField.TIMINGS,
          XrayField.ITEMS,
          System.currentTimeMillis() - metadataTimings);
      metadataTimings = System.currentTimeMillis();
      Set<Metadata> myItems = new HashSet<>(list.getEntries());

      // DK: I wasn't able to find out how pagination works
      // apparently Dropbox can have a pagination, but no hard limit
      // probable limit is 2000 per folder
      // see https://www.dropbox.com/developers/documentation/http/documentation#files-list_folder
      String nextPageToken = emptyString;
      if (!isBrowser) {
        while (list.getHasMore()) {
          String cursor = list.getCursor();
          list = dbxClient.files().listFolderContinue(cursor);
          myItems.addAll(list.getEntries());
        }
      } else if (list.getHasMore()) {
        nextPageToken = list.getCursor();
      }

      // for trash - we should get only deleted files
      if (trash) {
        myItems = myItems.stream()
            .filter((item) -> item instanceof DeletedMetadata)
            .collect(Collectors.toSet());
      }

      final Boolean[] finalFull = {full};

      String parent;
      if (path.isEmpty()) {
        parent = Field.MINUS_1.getName();
      } else {
        parent = path;
      }

      // Fix for AC
      if (parent.contains(ID_PREFIX)) {
        parent = parent.replace(ID_PREFIX, emptyString);
      }
      Item externalAccount = ExternalAccounts.getExternalAccount(userId, externalId);
      String storageOwner = externalAccount.get("foreign_user").toString();
      String finalParent = parent;
      Map<String, String> userNames = new HashMap<>();
      Date now = new Date();
      myItems.parallelStream().forEach(meta -> {
        Entity dropboxIterationSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "dropboxIteration");
        try {
          String filename = meta.getName();
          FileMetadata metadata = null;
          if (!trash) {
            if (meta instanceof FolderMetadata) {
              foldersJson.add(
                  getFolderJson((FolderMetadata) meta, finalParent, api, externalId, null, false));
            } else if (meta instanceof FileMetadata) {
              metadata = (FileMetadata) meta;
            }
          } else if (meta instanceof DeletedMetadata) { // excluding folders in trash because of
            // some limitations in DB
            ListRevisionsResult result = null;
            try {
              result = dbxClient.files().listRevisions(meta.getPathLower());
            } catch (ListRevisionsErrorException e) {
              // skip if the item is a folder
            }
            if (result == null
                || !result.getIsDeleted()
                || Duration.between(result.getServerDeleted().toInstant(), now.toInstant())
                        .toDays()
                    > 30) {
              return;
            }

            metadata = result.getEntries().get(0);
            finalFull[0] = false;
          }

          if (Extensions.isValidExtension(extensions, filename) && metadata != null) {
            String fileId = fileInfoToId(metadata);
            boolean createThumbnail = canCreateThumbnails;
            if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
              thumbnail.add(new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(VERSION_ID, metadata.getRev())
                  .put(Field.EXT.getName(), Extensions.getExtension(filename)));
            } else {
              createThumbnail = false;
            }

            JsonObject json = getFileJson(
                metadata,
                finalParent,
                api,
                externalId,
                finalFull[0],
                false,
                isAdmin,
                userId,
                storageOwner,
                userNames,
                force,
                createThumbnail,
                trash);
            filesJson.put(fileId, json);

            if (Extensions.isThumbnailExt(config, filename, isAdmin)) {
              if (!json.getBoolean(Field.VIEW_ONLY.getName())) {
                possibleIdsToIds.put(fileId, fileId);
                if (!fileId.startsWith(ID_PREFIX)) {
                  possibleIdsToIds.put(ID_PREFIX + fileId, fileId);
                }
              }
            }
          }
        } catch (Exception e) {
          log.error("Exception on handling folder's content", e);
        } finally {
          XRayManager.endSegment(dropboxIterationSegment);
        }
      });
      System.out.println(MessageFormat.format(
          "Time elapsed on items: {0}", System.currentTimeMillis() - metadataTimings));
      XRayEntityUtils.putMetadata(
          segment,
          XrayField.TIMINGS,
          XrayField.ITEMS,
          System.currentTimeMillis() - metadataTimings);
      if (canCreateThumbnails && !thumbnail.isEmpty()) {
        createThumbnails(
            segment, thumbnail, messageBody.put(Field.STORAGE_TYPE.getName(), storageType.name()));
      }

      if (full && !possibleIdsToIds.isEmpty()) {
        Map<String, JsonObject> newSharedLinksResponse =
            PublicLink.getSharedLinks(config, segment, userId, externalId, possibleIdsToIds);
        for (Map.Entry<String, JsonObject> fileData : newSharedLinksResponse.entrySet()) {
          if (fileData.getKey() != null) {
            if (filesJson.containsKey(fileData.getKey())) {
              filesJson.put(
                  fileData.getKey(), filesJson.get(fileData.getKey()).mergeIn(fileData.getValue()));
            }
          }
        }
      }

      foldersJson.sort(
          Comparator.comparing(o2 -> o2.getString(Field.NAME.getName()).toLowerCase()));
      List<JsonObject> filesList = new ArrayList<>(filesJson.values());

      filesList.sort(
          Comparator.comparing(o -> o.getString(Field.FILE_NAME.getName()).toLowerCase()));
      JsonObject response = new JsonObject()
          .put(
              Field.RESULTS.getName(),
              new JsonObject()
                  .put(Field.FILES.getName(), new JsonArray(filesList))
                  .put(Field.FOLDERS.getName(), new JsonArray(foldersJson)))
          .put("number", filesJson.size() + foldersJson.size())
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      if (Utils.isStringNotNullOrEmpty(nextPageToken)) {
        response.put(Field.PAGE_TOKEN.getName(), nextPageToken);
      }
      sendOK(segment, message, response, mills);
    } catch (Exception e) {
      if (e instanceof PathRootErrorException) {
        PathRootError pathRootError = ((PathRootErrorException) e).getPathRootError();
        if (pathRootError.isInvalidRoot()) {
          String rootId = pathRootError.getInvalidRootValue().getRootNamespaceId();
          Item dropboxUser = ExternalAccounts.getExternalAccount(userId, externalId);
          dropboxUser.withString("rootNamespaceId", rootId);
          ExternalAccounts.saveExternalAccount(userId, externalId, dropboxUser);
          log.info("Dropbox: updated rootNamespaceId. PRE: " + pathRootError);
        } else {
          log.error(
              "Dropbox PathRootErrorException! FolderId supplied: " + folderId + " pageToken: "
                  + pageToken + ", PRE: "
                  + ((PathRootErrorException) e).getPathRootError().toString(),
              e);
        }
      }
      handleException(segment, message, e);
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

    DbxRequestConfig dbxRequestConfig = new DbxRequestConfig("ares-kudo");
    try {
      @NonNls
      String urlParameters = "grant_type=authorization_code&client_id=" + APP_KEY
          + "&client_secret=" + APP_SECRET + "&redirect_uri=" + REDIRECT_URI + "&code=" + code;
      byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);
      int postDataLength = postData.length;
      @NonNls String request = "https://api.dropboxapi.com/oauth2/token";
      URL url = new URL(request);
      @NonNls HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
      conn.setDoOutput(true);
      conn.setInstanceFollowRedirects(false);
      conn.setRequestMethod("POST");
      conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      conn.setRequestProperty("charset", "utf-8");
      conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
      conn.setUseCaches(false);
      DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
      wr.write(postData);
      if (conn.getResponseCode() != HttpStatus.OK) {
        BufferedReader in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();
        while ((inputLine = in.readLine()) != null) {
          response.append(inputLine);
        }
        in.close();
        sendError(segment, message, response.toString(), HttpStatus.INTERNAL_SERVER_ERROR);
        return;
      }
      BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
      String inputLine;
      StringBuilder response = new StringBuilder();
      while ((inputLine = in.readLine()) != null) {
        response.append(inputLine);
      }
      in.close();
      JsonObject responseJson = new JsonObject(response.toString());
      String accessToken = responseJson.getString(Field.ACCESS_TOKEN.getName());
      String refreshToken = responseJson.getString(Field.REFRESH_TOKEN.getName());
      long expiresIn = responseJson.getLong("expires_in");
      String dropboxId = responseJson.getString("account_id");
      DbxClientV2 client = new DbxClientV2(dbxRequestConfig, accessToken);
      FullAccount account = client.users().getCurrentAccount();
      Item externalAccount = ExternalAccounts.getExternalAccount(userId, dropboxId);
      if (externalAccount == null) {
        externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(), userId, Field.EXTERNAL_ID_LOWER.getName(), dropboxId)
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
            dropboxId,
            true,
            intercomAccessToken);
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
      externalAccount
          .withString(Field.ACCESS_TOKEN.getName(), accessToken)
          .withString(Field.REFRESH_TOKEN.getName(), refreshToken)
          .withLong(Field.EXPIRES.getName(), GMTHelper.utcCurrentTime() + expiresIn * 1000)
          .withString(Field.EMAIL.getName(), account.getEmail())
          .withString("foreign_user", account.getName().getDisplayName());
      String rootNamespaceId = account.getRootInfo().getRootNamespaceId();
      if (rootNamespaceId != null && !rootNamespaceId.isEmpty()) {
        externalAccount.withString("rootNamespaceId", rootNamespaceId);
      }
      Sessions.updateSessionOnConnect(externalAccount, userId, storageType, dropboxId, sessionId);
      sendOK(segment, message, mills);
    } catch (Exception e) {
      handleException(segment, message, e);
    }
  }

  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, MessageUtils.parse(message).getJsonObject(), replyOnOk);
  }

  private <T> DbxClientV2 connect(Entity segment, Message<T> message) {
    return connect(segment, message, MessageUtils.parse(message).getJsonObject(), false);
  }

  private <T> DbxClientV2 connect(
      Entity segment, Message<T> message, JsonObject json, Boolean replyOnOk) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "Dropbox.Connect");

    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String dropboxId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, null, dropboxId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (dropboxId == null || dropboxId.isEmpty()) {
      dropboxId = findExternalId(segment, message, json, storageType);
      if (dropboxId == null) {
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
    Item dropboxUser = ExternalAccounts.getExternalAccount(userId, dropboxId);
    if (dropboxUser == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(storageType, userId, null, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisDropboxAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    json.put(
        "isAccountThumbnailDisabled",
        dropboxUser.hasAttribute("disableThumbnail") && dropboxUser.getBoolean("disableThumbnail"));
    DbxRequestConfig dbxRequestConfig = new DbxRequestConfig("ares-kudo");
    String accessToken = dropboxUser.getString(Field.ACCESS_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
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
      ((JsonObject) message.body())
          .put("accessToken", accessToken)
          .put(Field.EMAIL.getName(), dropboxUser.getString(Field.EMAIL.getName()));
    }
    XRayManager.endSegment(subsegment);
    if (replyOnOk) {
      sendOK(segment, message);
    }

    // may need to update tokens
    if (dropboxUser.hasAttribute(Field.REFRESH_TOKEN.getName())
        && dropboxUser.hasAttribute(Field.EXPIRES.getName())) {
      long expiresIn = dropboxUser.getLong(Field.EXPIRES.getName());
      if (GMTHelper.utcCurrentTime() + DbxCredential.EXPIRE_MARGIN > expiresIn) {
        accessToken = refreshToken(
            segment,
            subsegment,
            message,
            userId,
            dropboxId,
            dbxRequestConfig,
            accessToken,
            dropboxUser.getString(Field.REFRESH_TOKEN.getName()),
            expiresIn);
        if (accessToken == null) {
          return null;
        }
      }
    }
    DbxClientV2 client = new DbxClientV2(dbxRequestConfig, accessToken);
    try {
      String rootNamespaceId = dropboxUser.getString("rootNamespaceId");
      if (rootNamespaceId == null) {
        rootNamespaceId = client.users().getCurrentAccount().getRootInfo().getRootNamespaceId();
        ExternalAccounts.updateRootNameSpaceId(userId, dropboxId, rootNamespaceId);
      }
      client = client.withPathRoot(PathRoot.root(rootNamespaceId));
    } catch (DbxException e) {
      log.error("Error in Dropbox Client : " + e.getLocalizedMessage());
      return null;
    }
    return client;
  }

  private DbxClientV2 connect(Item dropboxUser) {
    DbxRequestConfig dbxRequestConfig = new DbxRequestConfig("ares-kudo");
    String accessToken = dropboxUser.getString(Field.ACCESS_TOKEN.getName());
    try {
      accessToken =
          EncryptHelper.decrypt(accessToken, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, null, null, ConnectionError.CANNOT_DECRYPT_TOKENS);
      return null;
    }

    if (dropboxUser.hasAttribute(Field.EXPIRES.getName())
        && GMTHelper.utcCurrentTime() + DbxCredential.EXPIRE_MARGIN
            > dropboxUser.getLong(Field.EXPIRES.getName())) {
      accessToken = refreshToken(
          dropboxUser, dbxRequestConfig, accessToken, dropboxUser.getLong(Field.EXPIRES.getName()));
      if (accessToken == null) {
        return null;
      }
    }

    DbxClientV2 client = new DbxClientV2(dbxRequestConfig, accessToken);
    try {
      String rootNamespaceId = dropboxUser.getString("rootNamespaceId");
      if (rootNamespaceId == null) {
        rootNamespaceId = client.users().getCurrentAccount().getRootInfo().getRootNamespaceId();
        ExternalAccounts.updateRootNameSpaceId(
            ExternalAccounts.getUserIdFromPk(dropboxUser.getString(Field.PK.getName())),
            dropboxUser.getString(Field.SK.getName()),
            rootNamespaceId);
      }
      client = client.withPathRoot(PathRoot.root(rootNamespaceId));
    } catch (DbxException e) {
      log.error("Error in Dropbox Client : " + e.getLocalizedMessage());
      return null;
    }
    return client;
  }

  private String fileInfoToId(Metadata fileInfo) {
    if (fileInfo instanceof FolderMetadata) {
      return ((FolderMetadata) fileInfo).getId().substring(3);
    }
    if (fileInfo instanceof FileMetadata) {
      return ((FileMetadata) fileInfo).getId().substring(3);
    }

    return fileInfo.getPathLower();
  }

  private JsonObject getFileJson(
      FileMetadata fileInfo,
      String parent,
      DbxClientV2 api,
      String currentAccountId,
      boolean full,
      boolean addShare,
      boolean isAdmin,
      String userId,
      String storageOwner,
      Map<String, String> userNames,
      boolean force,
      boolean canCreateThumbnails,
      boolean isTrash)
      throws DbxException {
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    String owner = emptyString, ownerEmail = emptyString;
    FileSharingInfo sharing = fileInfo.getSharingInfo();
    boolean shared = sharing != null;
    if (!shared) {
      owner = storageOwner;
    }
    boolean viewOnly = sharing != null && sharing.getReadOnly();
    boolean isOwner = !viewOnly;
    String fileId = fileInfoToId(fileInfo);
    if (addShare && full) {
      SharedFileMembers members =
          new AWSXRayDbxClient(api).sharing().listFileMembers(fileInfo.getId());
      shared = !members.getUsers().isEmpty() || !members.getInvitees().isEmpty();
      String accountId;
      JsonObject obj;
      BasicAccount basicAccount;
      for (UserMembershipInfo user : members.getUsers()) {
        accountId = user.getUser().getAccountId();
        basicAccount = new AWSXRayDbxClient(api).users().getAccount(accountId);
        // do we have to use userNames here?
        obj = new JsonObject()
            .put(Field.NAME.getName(), basicAccount.getName().getDisplayName())
            .put(Field.ENCAPSULATED_ID.getName(), accountId)
            .put(Field.EMAIL.getName(), basicAccount.getEmail());
        if (AccessLevel.OWNER.equals(user.getAccessType())) {
          isOwner = accountId.equals(currentAccountId);
          owner = basicAccount.getName().getDisplayName();
          ownerEmail = basicAccount.getEmail();
        } else if (AccessLevel.EDITOR.equals(user.getAccessType())) {
          if (accountId.equals(currentAccountId)) {
            viewOnly = false;
          }
          editor.add(obj);
        } else {
          if (accountId.equals(currentAccountId)) {
            viewOnly = true;
          }
          viewer.add(obj);
        }
      }
      for (InviteeMembershipInfo invitee : members.getInvitees()) {
        obj = new JsonObject()
            .put(Field.NAME.getName(), invitee.getInvitee().getEmailValue())
            .put(Field.ENCAPSULATED_ID.getName(), invitee.getInvitee().getEmailValue())
            .put(Field.EMAIL.getName(), invitee.getInvitee().getEmailValue());
        if (AccessLevel.EDITOR.equals(invitee.getAccessType())) {
          editor.add(obj);
        } else {
          viewer.add(obj);
        }
      }
      if (!Utils.isStringNotNullOrEmpty(owner)) {
        SharedFileMetadata fileMetadata =
            new AWSXRayDbxClient(api).sharing().getFileMetadata(fileInfo.getPathLower());
        FullAccount account = new AWSXRayDbxClient(api).users().getCurrentAccount();
        if (Objects.nonNull(fileMetadata)
            && Objects.nonNull(fileMetadata.getOwnerTeam())
            && !account.getAccountType().equals(AccountType.BASIC)) {
          owner = fileMetadata.getOwnerTeam().getName();
        }
      }
    }

    String changer = emptyString, changerId = emptyString;
    String verId = fileInfo.getRev();
    long updateDate = Math.max(
        fileInfo.getServerModified().getTime(), fileInfo.getClientModified().getTime());

    if (sharing == null) {
      changer = owner;
    } else {
      try {
        if (userNames.containsKey(sharing.getModifiedBy())) {
          changer = userNames.get(sharing.getModifiedBy());
        } else {
          BasicAccount basicAccount =
              new AWSXRayDbxClient(api).users().getAccount(sharing.getModifiedBy());
          changer = basicAccount.getName().getDisplayName();
          changerId = basicAccount.getAccountId();
          userNames.put(sharing.getModifiedBy(), changer);
        }
      } catch (Exception ignore) {
        // there can be exception for non existent accounts - it's not that important
      }
    }

    // if file is public
    JsonObject PLData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (addShare) {
      PLData = findLinkForFile(fileId, currentAccountId, userId, viewOnly);

      boolean doesPublicLinkExist = PLData.getBoolean(Field.IS_PUBLIC.getName());
      // check for old links if not found
      if (!doesPublicLinkExist) {
        String tempFileId = ID_PREFIX + fileId;
        PLData = findLinkForFile(tempFileId, currentAccountId, userId, viewOnly);

        if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
          fileId = tempFileId;
        }
      }
    }

    String thumbnailName =
        ThumbnailsManager.getThumbnailName(StorageType.getShort(storageType), fileId, verId);
    String previewId = ThumbnailsManager.getPreviewName(StorageType.getShort(storageType), fileId);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), currentAccountId, fileId))
        .put(Field.WS_ID.getName(), fileId)
        .put(Field.FILE_NAME.getName(), fileInfo.getName())
        .put(
            Field.FOLDER_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), currentAccountId, parent))
        .put(Field.OWNER.getName(), owner)
        .put(Field.OWNER_EMAIL.getName(), ownerEmail)
        .put(Field.CREATION_DATE.getName(), fileInfo.getServerModified().getTime())
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
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor))
        .put(Field.PUBLIC.getName(), PLData.getBoolean(Field.IS_PUBLIC.getName()))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PREVIEW_ID.getName(), previewId)
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName);

    if (Extensions.isThumbnailExt(config, fileInfo.getName(), isAdmin)) {
      // AS : Removing this temporarily until we have some server cache (WB-1248)
      //      json.put("thumbnailStatus",
      //              ThumbnailsManager.getThumbnailStatus(fileId, storageType.name(), verId,
      //              force, canCreateThumbnails))
      json.put(
              Field.THUMBNAIL.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName, true))
          .put(
              Field.GEOMDATA.getName(),
              ThumbnailsManager.getThumbnailURL(config, thumbnailName + ".json", true))
          .put(Field.PREVIEW.getName(), ThumbnailsManager.getPreviewURL(config, previewId, true));
    }
    json.put(Field.VER_ID.getName(), verId);
    json.put(VERSION_ID, verId);
    // for DB - id can contain ID_PREFIX
    if (Utils.isStringNotNullOrEmpty(verId)) {
      String idVersion = fileId;
      if (fileId.startsWith(ID_PREFIX)) {
        idVersion = idVersion.substring(ID_PREFIX.length());
      } else {
        idVersion = ID_PREFIX + idVersion;
      }
      FileMetaData.putMetaData(
          idVersion, storageType, fileInfo.getName(), thumbnailName, verId, null);
    }

    if (PLData.getBoolean(Field.IS_PUBLIC.getName())) {
      json.put(Field.LINK.getName(), PLData.getString(Field.LINK.getName()))
          .put(Field.EXPORT.getName(), PLData.getBoolean(Field.EXPORT.getName()))
          .put(Field.LINK_END_TIME.getName(), PLData.getLong(Field.LINK_END_TIME.getName()))
          .put(
              Field.PUBLIC_LINK_INFO.getName(),
              PLData.getJsonObject(Field.PUBLIC_LINK_INFO.getName()));
    }

    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setPermissionAccess(AccessType.canDownload, fileInfo.getIsDownloadable())
        .setPermissionAccess(AccessType.canViewPermissions, !isTrash)
        .setPermissionAccess(AccessType.canManagePermissions, (!viewOnly || isOwner) && !isTrash)
        .setBatchTo(
            List.of(AccessType.canViewPublicLink, AccessType.canManagePublicLink),
            !viewOnly && !isTrash)
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
      FolderMetadata folderInfo,
      String parent,
      DbxClientV2 api,
      String currentAccountId,
      String currentUserName,
      boolean full)
      throws DbxException {
    JsonArray editor = new JsonArray();
    JsonArray viewer = new JsonArray();
    String owner = emptyString, ownerEmail = emptyString;
    FolderSharingInfo sharing = folderInfo.getSharingInfo();
    boolean shared = sharing != null && sharing.getSharedFolderId() != null;
    boolean viewOnly = sharing != null && sharing.getReadOnly();
    boolean isOwner = !viewOnly;
    String sharedFolderId = sharing != null ? sharing.getSharedFolderId() : null;
    String accountId;
    JsonObject obj;
    BasicAccount basicAccount;
    if (full) {
      if (sharedFolderId != null) {
        SharedFolderMembers members =
            new AWSXRayDbxClient(api).sharing().listFolderMembers(sharing.getSharedFolderId());
        for (UserMembershipInfo user : members.getUsers()) {
          accountId = user.getUser().getAccountId();
          basicAccount = new AWSXRayDbxClient(api).users().getAccount(accountId);
          obj = new JsonObject()
              .put(Field.NAME.getName(), basicAccount.getName().getDisplayName())
              .put(Field.ENCAPSULATED_ID.getName(), accountId)
              .put(Field.EMAIL.getName(), basicAccount.getEmail());
          if (AccessLevel.OWNER.equals(user.getAccessType())) {
            owner = basicAccount.getName().getDisplayName();
            ownerEmail = basicAccount.getEmail();
            isOwner = currentAccountId.equals(accountId);
          } else if (AccessLevel.EDITOR.equals(user.getAccessType())) {
            editor.add(obj);
          } else {
            viewer.add(obj);
          }
        }
        for (InviteeMembershipInfo invitee : members.getInvitees()) {
          obj = new JsonObject()
              .put(Field.NAME.getName(), invitee.getInvitee().getEmailValue())
              .put(Field.ENCAPSULATED_ID.getName(), invitee.getInvitee().getEmailValue())
              .put(Field.EMAIL.getName(), invitee.getInvitee().getEmailValue());
          if (AccessLevel.EDITOR.equals(invitee.getAccessType())) {
            editor.add(obj);
          } else {
            viewer.add(obj);
          }
        }
        if (!Utils.isStringNotNullOrEmpty(owner)) {
          SharedFolderMetadata folderMetadata =
              new AWSXRayDbxClient(api).sharing().getFolderMetadata(sharedFolderId);
          FullAccount account = new AWSXRayDbxClient(api).users().getCurrentAccount();
          if (Objects.nonNull(folderMetadata)
              && Objects.nonNull(folderMetadata.getOwnerTeam())
              && !account.getAccountType().equals(AccountType.BASIC)) {
            owner = folderMetadata.getOwnerTeam().getName();
          }
        }
        shared = !isOwner || (editor.size() + viewer.size() > 0);
      }
      // suppose that if this folder is accessible and shared folder id is null, then current user
      // is an owner
      else {
        owner = currentUserName;
      }
    }
    String id = fileInfoToId(folderInfo);
    JsonObject json = new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), currentAccountId, id))
        .put(Field.NAME.getName(), folderInfo.getName())
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), currentAccountId, parent))
        .put(Field.OWNER.getName(), owner)
        .put(Field.OWNER_EMAIL.getName(), ownerEmail)
        .put(Field.CREATION_DATE.getName(), 0)
        .put(Field.SHARED.getName(), shared)
        .put(Field.PATH.getName(), folderInfo.getPathLower())
        .put(Field.VIEW_ONLY.getName(), viewOnly)
        .put(Field.IS_OWNER.getName(), isOwner)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), viewer)
                .put(Field.EDITOR.getName(), editor));

    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setPermissionAccess(AccessType.canManagePermissions, !viewOnly || isOwner)
        .setBatchTo(List.of(AccessType.canViewPublicLink, AccessType.canManagePublicLink), false);

    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  private <T> void handleException(Entity segment, Message<T> message, Exception e) {
    if (e instanceof InvalidAccessTokenException) {
      final JsonObject jsonObject = MessageUtils.parse(message).getJsonObject();
      final String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      final String userId = jsonObject.getString(Field.USER_ID.getName());
      logConnectionIssue(storageType, userId, externalId, ConnectionError.INVALID_ACCESS_TOKEN);
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL14"), HttpStatus.BAD_REQUEST);
      return;
    } else if (e instanceof RateLimitException) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "TooManyRequestsRetryLater"),
          HttpStatus.TOO_MANY_REQUESTS);
      return;
    } else if (e instanceof PathRootErrorException) {
      sendError(
          segment, message, Utils.getLocalizedString(message, "FL14"), HttpStatus.BAD_REQUEST);
    } else if (e instanceof SharedFolderAccessErrorException) {
      if (((SharedFolderAccessErrorException) e)
          .errorValue.equals(SharedFolderAccessError.EMAIL_UNVERIFIED)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourEmailAddressIsUnverified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof SharingUserErrorException) {
      if (((SharingUserErrorException) e).errorValue.equals(SharingUserError.EMAIL_UNVERIFIED)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourEmailAddressIsUnverified"),
            HttpStatus.PRECONDITION_FAILED);
        return;
      }
    } else if (e instanceof ShareFolderErrorException) {
      if (((ShareFolderErrorException) e).errorValue.equals(ShareFolderError.EMAIL_UNVERIFIED)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourEmailAddressIsUnverified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof AddFolderMemberErrorException) {
      if (((AddFolderMemberErrorException) e)
          .errorValue.equals(AddFolderMemberError.EMAIL_UNVERIFIED)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourEmailAddressIsUnverified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof UploadErrorException) {
      if (((UploadErrorException) e).errorValue.isPath()
          && ((UploadErrorException) e)
              .errorValue
              .getPathValue()
              .getReason()
              .equals(WriteError.INSUFFICIENT_SPACE)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YouDontHaveEnoughSpace"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof ListFileMembersErrorException) {
      if (!((ListFileMembersErrorException) e).errorValue.isAccessError()
          && ((ListFileMembersErrorException) e)
              .errorValue
              .getUserErrorValue()
              .equals(SharingUserError.EMAIL_UNVERIFIED)) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourEmailAddressIsUnverified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof RelocationErrorException) {
      if (((RelocationErrorException) e).errorValue.isDuplicatedOrNestedPaths()
          || ((RelocationErrorException) e).errorValue.isTo()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DuplicateName"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      if (((RelocationErrorException) e).errorValue.isCantNestSharedFolder()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "Sharedfolderscantbemovedtoothersharedfolders"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    } else if (e instanceof GetMetadataErrorException) {
      if (((GetMetadataErrorException) e).errorValue.getPathValue().isNotFound()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "PathNotFound"),
            HttpStatus.NOT_FOUND);
        return;
      }
    } else if (e instanceof DbxApiException) {
      if (Objects.nonNull(((DbxApiException) e).getUserMessage())) {
        sendError(
            segment,
            message,
            ((DbxApiException) e).getUserMessage().getText(),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }
    sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
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
    DbxClientV2 api = connect(segment, message, jsonObject, false);
    if (api == null) {
      return;
    }
    try {
      AWSXRayDbxClient dbxClientV2 = new AWSXRayDbxClient(api);

      // need to leave it here to get info and throw exception if required
      Metadata metadata;
      final String fileId = id.contains(":") ? id : (ID_PREFIX + id);
      try {
        metadata = dbxClientV2.files().getMetadata(fileId);
      } catch (Exception e) {
        // try to get as shared file
        SharedFileMetadata meta = dbxClientV2.sharing().getFileMetadata(fileId);
        metadata = new FileMetadata(
            meta.getName(),
            meta.getId(),
            meta.getTimeInvited(),
            meta.getTimeInvited(),
            "74353beb4dde7fb",
            0,
            "/",
            "/",
            meta.getParentSharedFolderId(),
            meta.getPreviewUrl(),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null);
      }
      boolean isDeleted = false;
      if (Objects.isNull(metadata)) {
        isDeleted = true;
      } else {
        FileMetadata fileMetadata = (FileMetadata) metadata;
        if (fileMetadata.getSize() == 0
            && ((Objects.isNull(fileMetadata.getPathLower())
                    || fileMetadata.getPathLower().replaceAll("/", emptyString).isEmpty())
                && !fileMetadata.getIsDownloadable())) {
          isDeleted = true;
        }
      }
      XRayEntityUtils.putMetadata(segment, XrayField.META_DATA, metadata.toString());
      sendOK(segment, message, new JsonObject().put(Field.IS_DELETED.getName(), isDeleted), mills);
    } catch (Exception e) {
      // DropBox returns response with access_error:invalid_file for trashed files so we fall
      // into this part
      // In other cases - we also can return true, because it is used for RF validation only
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

  private String refreshToken(
      Item dropboxUser, DbxRequestConfig dbxRequestConfig, String accessToken, long expiresIn) {
    String dropboxId = dropboxUser.hasAttribute(Field.EXTERNAL_ID.getName())
        ? dropboxUser.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    return refreshToken(
        null,
        null,
        null,
        dropboxUser.getString(Field.FLUORINE_ID.getName()),
        dropboxId,
        dbxRequestConfig,
        accessToken,
        dropboxUser.getString(Field.REFRESH_TOKEN.getName()),
        expiresIn);
  }

  private <T> String refreshToken(
      Entity segment,
      Entity subsegment,
      Message<T> message,
      String userId,
      String dropboxId,
      DbxRequestConfig dbxRequestConfig,
      String accessToken,
      String refresh_token,
      long expiresIn) {
    try {
      refresh_token =
          EncryptHelper.decrypt(refresh_token, config.getProperties().getFluorineSecretKey());
    } catch (Exception e) {
      log.error(e);
      logConnectionIssue(storageType, userId, dropboxId, ConnectionError.CANNOT_DECRYPT_TOKENS);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.BAD_REQUEST,
          e);
      return null;
    }
    DbxRefreshResult refreshResult;
    DbxCredential credential =
        new DbxCredential(accessToken, expiresIn, refresh_token, APP_KEY, APP_SECRET);
    try {
      refreshResult = credential.refresh(dbxRequestConfig);
    } catch (DbxOAuthException e) {
      XRayManager.endSegment(subsegment);
      log.error(e);
      logConnectionIssue(storageType, userId, dropboxId, ConnectionError.CANNOT_REFRESH_TOKENS);
      sendError(
          segment,
          message,
          e.getDbxOAuthError() != null ? e.getDbxOAuthError().getError() : e.getLocalizedMessage(),
          HttpStatus.BAD_REQUEST);
      return null;
    } catch (DbxException e) {
      log.error(e);
      logConnectionIssue(storageType, userId, dropboxId, ConnectionError.CONNECTION_EXCEPTION);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "DropboxAccessTokenExpired"),
          HttpStatus.BAD_REQUEST);
      return null;
    }

    if (refreshResult != null) {
      String newAccessToken = refreshResult.getAccessToken();
      expiresIn = refreshResult.getExpiresAt();
      if (newAccessToken == null) {
        XRayManager.endSegment(subsegment);
        logConnectionIssue(storageType, userId, dropboxId, ConnectionError.TOKEN_EXPIRED);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "DropboxAccessTokenExpired"),
            HttpStatus.BAD_REQUEST);
        return null;
      }
      if (!newAccessToken.equals(accessToken)) {
        accessToken = newAccessToken;
      }

      try {
        newAccessToken =
            EncryptHelper.encrypt(newAccessToken, config.getProperties().getFluorineSecretKey());
      } catch (Exception e) {
        logConnectionIssue(storageType, userId, dropboxId, ConnectionError.CANNOT_ENCRYPT_TOKENS);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InternalError"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            e);
        return null;
      }

      Item dropboxUser = ExternalAccounts.getExternalAccount(userId, dropboxId);
      dropboxUser.withLong(Field.EXPIRES.getName(), expiresIn);
      dropboxUser.withString(Field.ACCESS_TOKEN.getName(), newAccessToken);

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("saveExternalAccount")
          .runWithoutSegment(() -> {
            ExternalAccounts.saveExternalAccount(
                dropboxUser.getString(Field.FLUORINE_ID.getName()),
                dropboxUser.getString(Field.EXTERNAL_ID_LOWER.getName()),
                dropboxUser);
          });
    }
    return accessToken;
  }

  private List<CollaboratorInfo> getItemCollaborators(
      AWSXRayDbxClient dbxClientV2, Metadata metadata, String id, boolean isFile)
      throws DbxException {
    List<UserMembershipInfo> users = new ArrayList<>();
    List<InviteeMembershipInfo> invitees;
    if (isFile) {
      SharedFileMembers fileMembers = dbxClientV2.sharing().listFileMembers(id);
      users.addAll(fileMembers.getUsers());
      invitees = fileMembers.getInvitees();
    } else {
      FolderSharingInfo sharingInfo = ((FolderMetadata) metadata).getSharingInfo();
      SharedFolderMembers folderMembers =
          dbxClientV2.sharing().listFolderMembers(sharingInfo.getSharedFolderId());
      users.addAll(folderMembers.getUsers());
      invitees = folderMembers.getInvitees();
    }
    String accountId;
    BasicAccount basicAccount;
    List<CollaboratorInfo> collaboratorInfoList = new ArrayList<>();
    for (UserMembershipInfo user : users) {
      accountId = user.getUser().getAccountId();
      basicAccount = dbxClientV2.users().getAccount(accountId);
      // do we have to use userNames here?
      if (!AccessLevel.OWNER.equals(user.getAccessType())) {
        Role role = AccessLevel.EDITOR.equals(user.getAccessType()) ? Role.EDITOR : Role.VIEWER;
        CollaboratorInfo info = new CollaboratorInfo(
            accountId,
            basicAccount.getName().getDisplayName(),
            basicAccount.getEmail(),
            null,
            user.getAccessType().name(),
            role);
        collaboratorInfoList.add(info);
      }
    }
    for (InviteeMembershipInfo invitee : invitees) {
      Role role = AccessLevel.EDITOR.equals(invitee.getAccessType()) ? Role.EDITOR : Role.VIEWER;
      String email = invitee.getInvitee().getEmailValue();
      CollaboratorInfo info = new CollaboratorInfo(
          email, email, email, null, invitee.getAccessType().name(), role);
      collaboratorInfoList.add(info);
    }
    return collaboratorInfoList;
  }
}
