package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
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
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.webdav.SardineFactory;
import com.graebert.storage.integration.webdav.SardineImpl2;
import com.graebert.storage.integration.webdav.WebDavMapping;
import com.graebert.storage.integration.xray.AWSXraySardine;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
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
import javax.xml.namespace.QName;
import kong.unirest.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.NonNls;

public class WebDAV extends BaseStorage implements Storage {
  public static final String address = "webdav";
  private static final OperationGroup operationGroup = OperationGroup.WEBDAV;
  private static final String nextCloudKriegeritURL = "https://nextcloud.kriegerit.de/";
  private static final String cloudKriegeritURL = "https://cloud.kriegerit.de/";

  private static final String namespaceUrl = "http://owncloud.org/ns";
  private static final StorageType storageType = StorageType.WEBDAV;
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
      log.error("WEBDAV - exception on collectFiles. Input object: " + v.encodePrettily(), ex);
    }
  }

  public static String encodePath(String path) {
    return URLEncoder.encode(path, StandardCharsets.UTF_8)
        .replaceAll("\\+", "%20")
        .replaceAll(" ", "%20")
        .replaceAll("%3A%2F%2F", "://") // do not encode this
        .replaceAll("%2F", S3Regional.pathSeparator); // do not encode these
  }

  private Sardine testConnection(String username, String password, URL url) {
    try {
      Sardine sardine =
          SardineFactory.begin(username, password, config.getProperties().getxRayEnabled());
      sardine.list(url.toString(), 1, Collections.emptySet());
      return sardine;
    } catch (IOException e) {
      return null;
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

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-webdav");

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
    final LinkType linkType = LinkType.parse(lt);

    try {
      JsonObject api = connect(segment, message, message.body(), false);
      if (api == null) {
        return;
      }

      String name = "unnamed.dwg";
      String url = api.getString(Field.URL.getName());
      String filePath;
      String finalUrl = null;

      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String webdavfileid = WebDavMapping.getResourceFileId(api, fileIds.getId());
      filePath = WebDavMapping.getResourcePath(api, fileIds.getId());
      Set<QName> props = new HashSet<>();
      String currentVersion = emptyString;
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + filePath), 0, props);
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        webdavfileid = WebDavMapping.getFileId(res);
        name = getResourceName(res);
        currentVersion = ((Long) (res.getModified().getTime() / 100)).toString();
      }

      message.body().put(Field.NAME.getName(), name);

      String realVersionId;
      if (VersionType.parse(versionId).equals(VersionType.LATEST)) {
        filePath = WebDavMapping.getResourcePath(api, fileIds.getId());
        realVersionId = currentVersion;
      } else {
        realVersionId = versionId;
      }

      if (!webdavfileid.isEmpty() && !currentVersion.equals(realVersionId)) {
        // http://nextcloud.dev.graebert.com/remote.php/dav/versions/{user}/versions/{file-id}/versionId
        String versionspath = "versions/" + api.getString(Field.EMAIL.getName()) + "/versions/"
            + webdavfileid + S3Regional.pathSeparator + versionId;
        // this url might contain /files.lets remove that.
        if (url.contains("files/")
            || url.startsWith(nextCloudKriegeritURL)
            || url.startsWith(cloudKriegeritURL)) {
          url = url.substring(0, url.lastIndexOf("files/"));
        }

        resources = AWSXraySardine.list(sardine, encodePath(url + versionspath), 1, props);
        if (!resources.isEmpty()) {
          finalUrl = url + versionspath;
          filePath = versionspath;
        }
      }

      byte[] data;
      try (InputStream in =
          AWSXraySardine.get(sardine, encodePath(finalUrl != null ? finalUrl : url + filePath))) {
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
          message, data, realVersionId, name, linkType.equals(LinkType.DOWNLOAD));
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

    String name = "unnamed.dwg";

    try {
      JsonObject api = connect(segment, message, message.body(), false);
      if (api == null) {
        return;
      }

      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String url = api.getString(Field.URL.getName());

      String webdavfileid = WebDavMapping.getResourceFileId(api, fileIds.getId());
      String filePath = WebDavMapping.getResourcePath(api, fileIds.getId());
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + filePath), 0, props);
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        webdavfileid = WebDavMapping.getFileId(res);
        name = getResourceName(res);
      }

      if (Objects.nonNull(versionId) && VersionType.parse(versionId).equals(VersionType.SPECIFIC)) {
        try {
          String versionspath = "versions/" + api.getString(Field.EMAIL.getName()) + "/versions/"
              + webdavfileid + S3Regional.pathSeparator + versionId;
          // this url might contain /files.lets remove that.
          if (url.contains("files/")
              || url.startsWith(nextCloudKriegeritURL)
              || url.startsWith(cloudKriegeritURL)) {
            url = url.substring(0, url.lastIndexOf("files/"));
          }

          AWSXraySardine.list(sardine, encodePath(url + versionspath), 1, props);
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

  private JsonObject tryToTransform(
      final Entity segment,
      final String originalUsername,
      final String originalPassword,
      final URL testURL,
      final URL originalURL) {
    Sardine sardine = testConnection(originalUsername, originalPassword, testURL);
    if (sardine != null) {
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      try {
        List<DavResource> resources = sardine.list(testURL.toString(), 1, props);
        if (!resources.isEmpty()) {
          String ownerId = resources.get(0).getCustomProps().get("owner-id");
          if (Utils.isStringNotNullOrEmpty(ownerId)) {
            log.warn("Trying to change the URL");

            // apparently nextcloud threat it like root folder = files/*username*
            // but for search we should request .../dav/ with "/files/*username*" provided in body
            // so we have to make sure path actually points to root dir
            final String path = "files/" + ownerId;

            // test urls
            URL urlHostPathRemoteDavFilesUser = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath(originalURL.getPath()
                    + (originalURL.getPath().endsWith(S3Regional.pathSeparator)
                        ? emptyString
                        : S3Regional.pathSeparator)
                    + "remote.php/dav/" + path));
            URL urlHostRemoteDavFilesUser = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath("/remote.php/dav/" + path));
            URL urlHostPathDavFilesUser = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath(originalURL.getPath()
                    + (originalURL.getPath().endsWith(S3Regional.pathSeparator)
                        ? emptyString
                        : S3Regional.pathSeparator)
                    + "dav/" + path));
            URL urlHostDavFilesUser = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath("/dav/" + path));

            // connection urls
            URL urlHostPathRemoteDavFiles = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath(originalURL.getPath()
                    + (originalURL.getPath().endsWith(S3Regional.pathSeparator)
                        ? emptyString
                        : S3Regional.pathSeparator)
                    + "remote.php/dav/"));
            URL urlHostRemoteDavFiles = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                "/remote.php/dav/");
            URL urlHostPathDavFiles = new URL(
                originalURL.getProtocol(),
                originalURL.getHost(),
                originalURL.getPort(),
                encodePath(originalURL.getPath()
                    + (originalURL.getPath().endsWith(S3Regional.pathSeparator)
                        ? emptyString
                        : S3Regional.pathSeparator)
                    + "dav/"));
            URL urlHostDavFiles = new URL(
                originalURL.getProtocol(), originalURL.getHost(), originalURL.getPort(), "/dav/");

            List<Connection> attempts = new ArrayList<>();
            attempts.add(new Connection(
                urlHostPathRemoteDavFilesUser, urlHostPathRemoteDavFiles.toString(), path));
            attempts.add(
                new Connection(urlHostRemoteDavFilesUser, urlHostRemoteDavFiles.toString(), path));
            attempts.add(
                new Connection(urlHostPathDavFilesUser, urlHostPathDavFiles.toString(), path));
            attempts.add(new Connection(urlHostDavFilesUser, urlHostDavFiles.toString(), path));

            Optional<Connection> foundConnection = attempts.parallelStream()
                .filter(connection -> {
                  Entity subsegment = XRayManager.createSubSegment(
                      operationGroup, segment, "WEBDAV.iterateConvertedPaths");
                  Sardine s = testConnection(ownerId, originalPassword, connection.testUrl);
                  XRayManager.endSegment(subsegment);

                  return s != null;
                })
                .findAny();

            if (foundConnection.isPresent()) {
              return new JsonObject()
                  .put("urlString", foundConnection.get().urlString)
                  .put(Field.PATH.getName(), foundConnection.get().path)
                  .put(Field.USERNAME.getName(), ownerId);
            }
          }
        }
      } catch (Exception ignored) {

      }
    }
    return null;
  }

  @Override
  public void doAddAuthCode(Message<JsonObject> message) { // done
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    String urlString = getRequiredString(segment, Field.URL.getName(), message, jsonObject);
    String username = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String password = getRequiredString(segment, Field.PASSWORD.getName(), message, jsonObject);
    if (urlString == null || username == null || password == null) {
      return;
    }
    try {
      String path = emptyString;
      boolean hasFoundProperConnection = false;
      // check if we can request root folder

      // we can check if we can figure out the path. Lets first try
      // 1. HOST/PATH/remote.php/dav/files/USER <- owncloud/nextcloud
      // 2. HOST/remote.php/dav/files/USER <- owncloud/nextcloud
      // 3. HOST/PATH/dav/files/USER
      // 4. HOST/dav/files/USER
      // 5. HOST/PATH/dav/
      // 6. HOST/dav/
      // 7. HOST/PATH/webdav/
      // 8. HOST/webdav/
      // 9. HOST/PATH
      URL url = new URL(urlString);
      String originalPassword = password;
      password = EncryptHelper.encrypt(password, config.getProperties().getFluorineSecretKey());
      // check user's connection first
      Sardine originalTest;
      try {
        originalTest = testConnection(username, originalPassword, url);
      } catch (IllegalArgumentException e) {
        url = new URL(encodePath(urlString));
        originalTest = testConnection(username, originalPassword, url);
      }
      URL testURL = null;
      final String originalUsername = username;
      if (originalTest == null) {
        // if initial test has failed - retry with different versions

        URL urlHostPathDav = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "dav/"));
        URL urlHostDav = new URL(url.getProtocol(), url.getHost(), url.getPort(), "/dav/");
        URL urlHostPathWebDav = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "webdav/"));
        URL urlHostWebDav = new URL(url.getProtocol(), url.getHost(), url.getPort(), "/webdav/");
        URL urlHostPath = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)));

        // test urls
        URL urlHostPathRemoteDavFilesUser = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "remote.php/dav/" + originalUsername));
        URL urlHostRemoteDavFilesUser = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath("/remote.php/dav/" + originalUsername));
        URL urlHostPathDavFilesUser = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "dav/" + originalUsername));
        URL urlHostDavFilesUser = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath("/dav/" + originalUsername));

        // connection urls
        URL urlHostPathRemoteDavFiles = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "remote.php/dav/"));
        URL urlHostRemoteDavFiles =
            new URL(url.getProtocol(), url.getHost(), url.getPort(), "/remote.php/dav/");
        URL urlHostPathDavFiles = new URL(
            url.getProtocol(),
            url.getHost(),
            url.getPort(),
            encodePath(url.getPath()
                + (url.getPath().endsWith(S3Regional.pathSeparator)
                    ? emptyString
                    : S3Regional.pathSeparator)
                + "dav/"));
        URL urlHostDavFiles = new URL(url.getProtocol(), url.getHost(), url.getPort(), "/dav/");

        List<Connection> attempts = new ArrayList<>();

        attempts.add(new Connection(
            urlHostPathRemoteDavFilesUser, urlHostPathRemoteDavFiles.toString(), originalUsername));
        attempts.add(new Connection(
            urlHostRemoteDavFilesUser, urlHostRemoteDavFiles.toString(), originalUsername));
        attempts.add(new Connection(
            urlHostPathDavFilesUser, urlHostPathDavFiles.toString(), originalUsername));
        attempts.add(
            new Connection(urlHostDavFilesUser, urlHostDavFiles.toString(), originalUsername));

        attempts.add(new Connection(urlHostPathDav, urlHostPathDav.toString(), emptyString));
        attempts.add(new Connection(urlHostDav, urlHostDav.toString(), emptyString));
        attempts.add(
            new Connection(urlHostPathWebDav, urlHostPathWebDav.toString(), emptyString)); // 7
        attempts.add(new Connection(urlHostWebDav, urlHostWebDav.toString(), emptyString)); // 8
        attempts.add(new Connection(urlHostPath, urlHostPath.toString(), emptyString));

        Optional<Connection> foundConnection = attempts.parallelStream()
            .filter(connection -> {
              Entity subsegment =
                  XRayManager.createSubSegment(operationGroup, segment, "WEBDAV.iteratePaths");
              Sardine s = testConnection(originalUsername, originalPassword, connection.testUrl);
              XRayManager.endSegment(subsegment);
              return s != null;
            })
            .findAny();

        // url - base webdav url
        // path - username part.
        // for nextcloud we should use for operations url+path, for search - url + path in request
        if (foundConnection.isPresent()) {
          urlString = foundConnection.get().urlString;
          path = foundConnection.get().path;
          testURL = foundConnection.get().testUrl;
          hasFoundProperConnection = true;
        }
      } else {
        hasFoundProperConnection = true;
        testURL = url;
      }

      if (!hasFoundProperConnection) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InvalidUrl"),
            HttpStatus.BAD_REQUEST);
        return;
      }

      // Transform 7,8 -> 1 for search support in nextcloud
      if (urlString.contains("webdav")) {
        JsonObject transformed =
            tryToTransform(segment, originalUsername, originalPassword, testURL, url);
        if (transformed != null) {
          urlString = transformed.getString("urlString");
          path = transformed.getString(Field.PATH.getName());
          username = transformed.getString(Field.USERNAME.getName());
        }
      }

      String connectionName = Integer.toString(Math.abs(UUID.randomUUID().hashCode()));

      // store connection
      Item externalAccount = ExternalAccounts.formExternalAccountItem(
              userId, storageType.name() + "_" + connectionName)
          .withString(Field.EMAIL.getName(), username)
          .withString(Field.PASSWORD.getName(), password)
          // duplicate this data as properties
          .withString(Field.USER_ID.getName(), userId)
          .withString("connectionId", connectionName)
          .withString(Field.F_TYPE.getName(), storageType.name())
          .withString(Field.URL.getName(), urlString)
          .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime())
          // WD connection basically don't ever expire,
          // so we set a long expiration date to avoid refresh
          // scripts
          .withLong(
              Field.EXPIRES.getName(),
              GMTHelper.utcCurrentTime() + TimeUnit.DAYS.toMillis(365 * 10));
      if (!path.isEmpty()) {
        externalAccount = externalAccount.withString(Field.PATH.getName(), path);
      }

      Sessions.updateSessionOnConnect(
          externalAccount,
          userId,
          storageType.name(),
          storageType.name() + "_" + connectionName,
          sessionId);
    } catch (MalformedURLException e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InvalidUrl"),
          HttpStatus.BAD_REQUEST,
          e);
      return;
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
      return;
    }
    sendOK(segment, message, mills);
  }

  @Override
  public void doGetFolderContent(
      Message<JsonObject> message,
      boolean trash) { // done. todo: except for folders with spec chars
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    if (trash) {
      sendError(segment, message, emptyString, HttpStatus.NOT_IMPLEMENTED);
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

      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String path = WebDavMapping.getResourcePath(api, folderId);
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      String encodedPath = encodePath(api.getString(Field.URL.getName()) + path);
      if (!encodedPath.endsWith(S3Regional.pathSeparator)) {
        encodedPath += S3Regional.pathSeparator;
      }
      List<DavResource> resources = AWSXraySardine.list(sardine, encodedPath, 1, props);

      Map<String, String> possibleIdsToIds = new HashMap<>();
      List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();

      XRayEntityUtils.putMetadata(segment, XrayField.RESOURCES_SIZE, resources.size());

      // lets parallize this as this can be expense when we store new ids in db
      List<JsonObject> foldersJson = resources.parallelStream()
          .map(res -> {
            if (res.getContentType().equals("httpd/unix-directory")) {
              Entity subsegment = XRayManager.createSubSegment(
                  operationGroup, segment, "WEBDAV.iterateFolderResults");
              String rp = WebDavMapping.getScopedPath(api, res.getPath());
              if (rp.equals(path)) {
                return null;
              }
              WebDavMapping webDavMapping = new WebDavMapping(api, res);
              String resourceId = webDavMapping.getReturnId();
              if (webDavMapping.isShouldBeSaved()) {
                webDavMappingObjectsToSave.add(webDavMapping);
              }
              XRayManager.endSegment(subsegment);
              return getFolderJson(resourceId, res, folderId, externalId);
            }
            return null;
          })
          .filter(Objects::nonNull)
          .collect(Collectors.toList());

      Map<String, JsonObject> filesJson = resources.parallelStream()
          .map(res -> {
            if (!res.getContentType().equals("httpd/unix" + "-directory")) {
              Entity subsegment = XRayManager.createSubSegment(
                  operationGroup, segment, "WEBDAV" + ".iterateFolderResults");
              String filename = getResourceName(res);
              if (Extensions.isValidExtension(extensions, filename)) {
                WebDavMapping webDavMapping = new WebDavMapping(api, res);
                String resourceId = webDavMapping.getReturnId();
                if (webDavMapping.isShouldBeSaved()) {
                  webDavMappingObjectsToSave.add(webDavMapping);
                }
                XRayManager.endSegment(subsegment);
                if (!Utils.isStringNotNullOrEmpty(resourceId)) {
                  log.error("WEBDAV - resourceId is " + "null"
                      + " " + "or empty: " + resourceId + " "
                      + "resource: " + res.getPath());
                  return null;
                }
                return getFileJson(resourceId, res, folderId, false, isAdmin, externalId, userId);
              }
              XRayManager.endSegment(subsegment);
            }
            return null;
          })
          .filter(j -> j != null
              && !j.isEmpty()
              && j.containsKey(Field.WS_ID.getName())
              && Utils.isStringNotNullOrEmpty(j.getString(Field.WS_ID.getName())))
          // DK: For me locally Collectors.toMap threw NPE.
          // Apparently, there were some duplicated ids
          // caused by some weird tests I've done
          // Because of this - I've used this "workaround"
          .collect(HashMap::new, WebDAV::collectFiles, HashMap::putAll);
      WebDavMapping.saveListOfMappings(webDavMappingObjectsToSave);
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
                  ((Long) (v.getLong(Field.UPDATE_DATE.getName()) / 100)).toString())
              .put("serverUrl", api.getString(Field.URL.getName()))
              .put(Field.EXT.getName(), Extensions.getExtension(filename)));
        } else {
          createThumbnail = false;
        }

        // DK: ignore this for now for webdav
        // we should probably find a better way
        // maybe bulk get or something
        v.put(
            "thumbnailStatus",
            createThumbnail
                ? ThumbnailsManager.ThumbnailStatus.LOADING
                : ThumbnailsManager.ThumbnailStatus.UNAVAILABLE);
        // v.put("thumbnailStatus", getThumbnailStatus(v.getString(Field.WS_ID.getName()), api
        // .getString
        // (Field.STORAGE_TYPE.getName()), v.getString(Field.VER_ID.getName()), force,
        // createThumbnail));
        updatedFilesJson.put(k, v);
      });

      // DK: we won't enable until we have a proper way that wouldn't break webdav.
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
          .put("number", updatedFilesJson.size() + foldersJson.size())
          .put(Field.FULL.getName(), full)
          .put(Field.FILE_FILTER.getName(), message.body().getString(Field.FILE_FILTER.getName()));
      sendOK(segment, message, response, mills);
    } catch (SardineException e) {
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
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String parentPath = WebDavMapping.getResourcePath(api, parentId);
      String path = parentPath;
      if (!parentPath.isEmpty() && !parentPath.endsWith(S3Regional.pathSeparator)) {
        path += (S3Regional.pathSeparator);
      }
      path += name;

      String encodedPath = encodePath(api.getString(Field.URL.getName()) + path);
      AWSXraySardine.createDirectory(sardine, encodedPath);
      if (!encodedPath.endsWith(S3Regional.pathSeparator)) {
        encodedPath += S3Regional.pathSeparator;
      }
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(sardine, encodedPath, 0, props);
      if (resources.isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotCreateNewFolder"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      WebDavMapping webDavMapping = new WebDavMapping(api, resources.get(0));
      String id = webDavMapping.getReturnId();
      if (webDavMapping.isShouldBeSaved()) {
        webDavMapping.save();
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.FOLDER_ID.getName(),
                  Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id)),
          mills);
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String sourcePath = WebDavMapping.getResourcePath(api, id);
      String parentPath = WebDavMapping.getResourcePath(api, parentId);
      String sourceName = Utils.getFileNameFromPath(sourcePath);

      String destinationPath = parentPath
          + (parentPath.endsWith(S3Regional.pathSeparator) ? emptyString : S3Regional.pathSeparator)
          + sourceName;
      String sourceEncodedPath = encodePath(api.getString(Field.URL.getName()) + sourcePath);
      if (message.body().containsKey(Field.FOLDER_ID.getName())
          && !sourceEncodedPath.endsWith(S3Regional.pathSeparator)) {
        // only needed for folders
        sourceEncodedPath += S3Regional.pathSeparator;
      }
      AWSXraySardine.move(
          sardine,
          sourceEncodedPath,
          encodePath(api.getString(Field.URL.getName()) + destinationPath));

      // lets update the path to id mapping.
      new WebDavMapping(
              id,
              api.getString(Field.EXTERNAL_ID.getName()),
              WebDavMapping.getScopedPath(api, destinationPath),
              null)
          .update();
      sendOK(segment, message, mills);
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      // before problem
      String source = WebDavMapping.getResourcePath(api, id);
      String parent = Utils.getFilePathFromPathWithSlash(source);
      String destination = parent + name;
      AWSXraySardine.move(
          sardine,
          encodePath(api.getString(Field.URL.getName()) + source),
          encodePath(api.getString(Field.URL.getName()) + destination)); // renameFilegetWrongHere
      // lets update the path to id mapping.
      WebDavMapping webDavMapping = new WebDavMapping(
          id,
          api.getString(Field.EXTERNAL_ID.getName()),
          WebDavMapping.getScopedPath(api, destination),
          null);
      webDavMapping.update();

      String newId = Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, id);
      sendOK(segment, message, new JsonObject().put(Field.ENCAPSULATED_ID.getName(), newId), mills);
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String path = WebDavMapping.getResourcePath(api, id);
      AWSXraySardine.delete(sardine, encodePath(api.getString(Field.URL.getName()) + path));
      WebDavMapping.unsetResourceId(api, id);
      sendOK(segment, message, mills);
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String source = WebDavMapping.getResourcePath(api, id);
      String parent = Utils.getFilePathFromPathWithSlash(source);
      String destination = parent + name;
      AWSXraySardine.copy(
          sardine,
          encodePath(api.getString(Field.URL.getName()) + source),
          encodePath(api.getString(Field.URL.getName()) + destination));

      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      String encodedPath = encodePath(api.getString(Field.URL.getName()) + destination);
      if (!isFile && !encodedPath.endsWith(S3Regional.pathSeparator)) {
        // only needed for folders
        encodedPath += S3Regional.pathSeparator;
      }
      List<DavResource> resources = AWSXraySardine.list(sardine, encodedPath, 0, props);
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
      WebDavMapping webDavMapping = new WebDavMapping(api, resources.get(0));
      String newId = webDavMapping.getReturnId();
      if (webDavMapping.isShouldBeSaved()) {
        webDavMapping.save();
      }
      NextCloudMapping parentNextCloudMapping = new NextCloudMapping(api, parent);
      parent = Utils.getEncapsulatedId(
          StorageType.getShort(storageType), externalId, parentNextCloudMapping.getReturnId());
      if (doCopyComments) {
        String finalId = id;
        String finalNewId = newId;
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
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(isFile ? Field.FILE_ID.getName() : Field.FOLDER_ID.getName(), newId)
              .put(Field.PARENT_ID.getName(), parent),
          mills);
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String path = WebDavMapping.getResourcePath(api, fileId);

      Set<QName> props = new HashSet<>();
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
      return !resources.isEmpty();
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
    boolean isFile = true;
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String path = WebDavMapping.getResourcePath(api, id);

      JsonObject json = new JsonObject();

      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      String encodedPath = encodePath(api.getString(Field.URL.getName()) + path);
      if (!isFile && !encodedPath.endsWith(S3Regional.pathSeparator)) {
        // only needed for folders
        encodedPath += S3Regional.pathSeparator;
      }
      List<DavResource> resources = AWSXraySardine.list(sardine, encodedPath, 0, props);
      if (id.isEmpty()) {
        // getting root
        DavResource rootInfo = resources.get(0);
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
            .put(Field.SHARED.getName(), isShared(rootInfo))
            .put(Field.VIEW_ONLY.getName(), !canWrite(rootInfo))
            .put(Field.IS_OWNER.getName(), isOwner(rootInfo))
            .put(Field.CAN_MOVE.getName(), canMove(rootInfo))
            .put(
                Field.SHARE.getName(),
                new JsonObject()
                    .put(Field.VIEWER.getName(), new JsonArray())
                    .put(Field.EDITOR.getName(), new JsonArray()));
      } else {
        String parent = path;
        if (parent.lastIndexOf(S3Regional.pathSeparator) >= 0) {
          parent = Utils.getFilePathFromPathWithSlash(parent);
        } else {
          parent = S3Regional.pathSeparator;
        }

        List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();
        for (DavResource res : resources) {
          String resPath = WebDavMapping.getScopedPath(api, res.getPath());
          if (resPath.equals(path)) {
            WebDavMapping parentWebDavMapping = new WebDavMapping(api, parent);
            WebDavMapping webDavMapping = new WebDavMapping(api, res);
            String resourceId = webDavMapping.getReturnId();

            if (res.getContentType().equals("httpd/unix-directory")) {
              json = getFolderJson(resourceId, res, parentWebDavMapping.getReturnId(), externalId);
            } else {
              json = getFileJson(
                  resourceId,
                  res,
                  parentWebDavMapping.getReturnId(),
                  full,
                  isAdmin,
                  externalId,
                  userId);
              // AS : Removing this temporarily until we have some server cache (WB-1248)
              //              json.put("thumbnailStatus",
              //                  ThumbnailsManager.getThumbnailStatus(json.getString(Field.WS_ID
              //                  .getName()),
              //                      api.getString(Field.STORAGE_TYPE.getName()), json.getString
              //                      (Field.VER_ID.getName()),
              //                      false, false));
            }
            if (parentWebDavMapping.isShouldBeSaved()) {
              webDavMappingObjectsToSave.add(parentWebDavMapping);
            }
            if (webDavMapping.isShouldBeSaved()) {
              webDavMappingObjectsToSave.add(webDavMapping);
            }

            WebDavMapping.saveListOfMappings(webDavMappingObjectsToSave);
            break;
          }
        }
      }
      sendOK(segment, message, json, mills);
    } catch (SardineException e) {
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
  public void doGetFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String versionId = message.body().getString(Field.VER_ID.getName());
    Boolean latest = message.body().getBoolean(Field.LATEST.getName());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
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
    JsonObject api = connect(segment, message);
    if (api == null) {
      return;
    }
    try {
      String name = null;
      long modifiedTime = -1L;
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String filePath;
      String url = api.getString(Field.URL.getName());
      String finalUrl = null;
      Long size = null;
      if (latest || versionId == null) {
        filePath = WebDavMapping.getResourcePath(api, fileId);
        // get info
        Set<QName> props = new HashSet<>();
        props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
        List<DavResource> resources = AWSXraySardine.list(
            sardine, encodePath(api.getString(Field.URL.getName()) + filePath), 0, props);
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

        String webdavfileid = WebDavMapping.getResourceFileId(api, fileId);
        filePath = WebDavMapping.getResourcePath(api, fileId);
        Set<QName> props = new HashSet<>();
        String currentVersion = emptyString;
        props.add(new QName(namespaceUrl, "fileid", "oc"));
        props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
        List<DavResource> resources = AWSXraySardine.list(
            sardine, encodePath(api.getString(Field.URL.getName()) + filePath), 0, props);
        if (!resources.isEmpty()) {
          DavResource res = resources.get(0);
          webdavfileid = WebDavMapping.getFileId(res);
          name = getResourceName(res);
          size = res.getContentLength();
          currentVersion = ((Long) (res.getModified().getTime() / 100)).toString();
        }

        if (!webdavfileid.isEmpty() && !currentVersion.equals(versionId)) {
          // http://nextcloud.dev.graebert.com/remote.php/dav/versions/{user}/versions/{file-id}/versionId
          String versionspath = "versions/" + api.getString(Field.EMAIL.getName()) + "/versions/"
              + webdavfileid + S3Regional.pathSeparator + versionId;
          // this url might contain /files.lets remove that.
          if (url.contains("files/")
              || url.startsWith(nextCloudKriegeritURL)
              || url.startsWith(cloudKriegeritURL)) {
            url = url.substring(0, url.lastIndexOf("files/"));
          }

          resources = AWSXraySardine.list(sardine, encodePath(url + versionspath), 1, props);
          if (!resources.isEmpty()) {
            finalUrl = url + versionspath;
            DavResource res = resources.get(0);
            modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
            filePath = versionspath;
          }
        }
      }

      String encodedPath = encodePath(finalUrl != null ? finalUrl : url + filePath);
      if (returnDownloadUrl) {
        String credentials =
            api.getString(Field.EMAIL.getName()) + ":" + api.getString(Field.PASSWORD.getName());
        String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
        sendDownloadUrl(
            segment, message, encodedPath, size, versionId, encodedCredentials, false, null, mills);
        return;
      }

      byte[] bytes;
      try (InputStream in = AWSXraySardine.get(sardine, encodedPath)) {
        bytes = IOUtils.toByteArray(in);
      }
      finishGetFile(
          message,
          start,
          end,
          bytes,
          storageType,
          name,
          versionId != null ? versionId : ((Long) (modifiedTime / 100)).toString(),
          downloadToken);
      XRayManager.endSegment(segment);
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
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
    log.warn("### doGetFile END ### " + fileId
        + (latest
            ? " latest "
            : (download ? " download " : ((start != null && start >= 0) ? " start " : " else "))));
    recordExecutionTime("getFile", System.currentTimeMillis() - mills);
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
    String userEmail = body.getString(Field.EMAIL.getName());
    String userName = body.getString(Field.F_NAME.getName());
    String userSurname = body.getString(Field.SURNAME.getName());
    String cloneFileId = body.getString(Field.CLONE_FILE_ID.getName());
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
        SardineImpl2 sardine = SardineFactory.begin(
            api.getString(Field.EMAIL.getName()),
            api.getString(Field.PASSWORD.getName()),
            config.getProperties().getxRayEnabled());

        String path = WebDavMapping.getResourcePath(api, folderId);

        if (fileId != null) {
          path = WebDavMapping.getResourcePath(api, fileId);
          Set<QName> props = new HashSet<>();
          List<DavResource> resources = null;
          try {
            resources = AWSXraySardine.list(
                sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
          } catch (SardineException ex) {
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
            versionId = ((Long) (res.getModified().getTime() / 100)).toString();
            name = res.getName();

            if (!Utils.isStringNotNullOrEmpty(conflictingFileReason) && !canWrite(res)) {
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
            path = WebDavMapping.getResourcePath(api, folderId);
            if (!path.endsWith(S3Regional.pathSeparator) && !path.isEmpty()) {
              path += S3Regional.pathSeparator;
            }
            path += newName;
          }
        } else {
          if (!path.endsWith(S3Regional.pathSeparator) && !path.isEmpty()) {
            path += S3Regional.pathSeparator;
          }
          path += name;
        }
        AWSXraySardine.put(
            sardine,
            encodePath(api.getString(Field.URL.getName()) + path),
            IOUtils.toByteArray(stream));
        Set<QName> props = new HashSet<>();
        props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
        List<DavResource> resources = AWSXraySardine.list(
            sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
        if (!resources.isEmpty()) {
          WebDavMapping webDavMapping = new WebDavMapping(api, resources.get(0));
          if (webDavMapping.isShouldBeSaved()) {
            webDavMapping.save();
          }
          newFileId = webDavMapping.getReturnId();
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
      versionId = ((Long) (modifiedTime / 100)).toString();
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
      if (isFileUpdate && parsedMessage.hasByteArrayContent()) {
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
    } catch (SardineException ex) {
      log.error("[WEBDAV] [UPLOAD_FILE] sardine exception", ex);
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), ex.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (IOException exception) {
      log.error("[WEBDAV] [UPLOAD_FILE] IO exception", exception);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception ex) {
      log.error("[WEBDAV] [UPLOAD_FILE] general exception", ex);
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
      SardineImpl2 sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String path = WebDavMapping.getResourcePath(api, fileId);
      AWSXraySardine.put(
          sardine,
          encodePath(api.getString(Field.URL.getName()) + path),
          IOUtils.toByteArray(stream));
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
      if (!resources.isEmpty()) {
        WebDavMapping webDavMapping = new WebDavMapping(api, resources.get(0));
        newFileId = webDavMapping.getReturnId();
        if (webDavMapping.isShouldBeSaved()) {
          webDavMapping.save();
        }
        modifiedTime = resources.get(0).getModified().getTime();
      }
      String versionId = ((Long) (modifiedTime / 100)).toString();
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
          segment,
          message,
          getVersionInfo(fileId, resources.get(0), null, api.getString(Field.URL.getName()))
              .toJson(),
          mills);

      eb_send(
          segment,
          WebSocketManager.address + ".newVersion",
          new JsonObject()
              .put(Field.FILE_ID.getName(), newFileId)
              .put(Field.VERSION_ID.getName(), versionId)
              .put(Field.FORCE.getName(), true)
              .put(Field.EMAIL.getName(), userEmail)
              .put(Field.USERNAME.getName(), userName.concat(" ").concat(userSurname)));
    } catch (SardineException ex) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "SomethingWentWrong"), ex.getLocalizedMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (IOException exception) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, exception.getMessage()),
          HttpStatus.BAD_REQUEST);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
  }

  @Override
  public void doGetFileByToken(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String downloadToken = message.body().getString(Field.DOWNLOAD_TOKEN.getName());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    if (fileId == null) {
      return;
    }
    boolean returnDownloadUrl =
        message.body().getBoolean(Field.RETURN_DOWNLOAD_URL.getName(), false);
    JsonObject api;

    Item item = ExternalAccounts.getExternalAccount(userId, externalId);
    api = connect(segment, item);
    if (api != null) {
      try {
        String name = null;
        Long size = null;
        long modifiedTime = 0;
        Sardine sardine = SardineFactory.begin(
            api.getString(Field.EMAIL.getName()),
            api.getString(Field.PASSWORD.getName()),
            config.getProperties().getxRayEnabled());
        String path = WebDavMapping.getResourcePath(api, fileId);

        // get info
        Set<QName> props = new HashSet<>();
        props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
        props.add(new QName(namespaceUrl, "fileid", "oc"));
        props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
        props.add(new QName(namespaceUrl, "owner-id", "oc"));
        props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
        List<DavResource> resources = AWSXraySardine.list(
            sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
        if (!resources.isEmpty()) {
          DavResource res = resources.get(0);
          modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
          name = getResourceName(res);
          size = res.getContentLength();
        }
        String versionId = ((Long) (modifiedTime / 100)).toString();
        String encodedPath = encodePath(api.getString(Field.URL.getName()) + path);
        if (returnDownloadUrl) {
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
              false,
              null,
              mills);
          return;
        }
        byte[] bytes;
        try (InputStream in = AWSXraySardine.get(sardine, encodedPath)) {
          bytes = IOUtils.toByteArray(in);
        }
        finishGetFile(message, null, null, bytes, storageType, name, versionId, downloadToken);
        XRayManager.endSegment(segment);
      } catch (Exception ignore) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotGetTheFileData"),
            HttpStatus.BAD_REQUEST);
      }
    }
    recordExecutionTime("getFileByToken", System.currentTimeMillis() - mills);
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

      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      // get info
      String path = WebDavMapping.getResourcePath(api, fileId);

      Set<QName> props = new HashSet<>();
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
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
      String versionId = ((Long) (modifiedTime / 100)).toString();
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

      String thumbnailName = ThumbnailsManager.getThumbnailName(
          storageType, fileId, ((Long) (modifiedTime / 100)).toString());
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
    } catch (SardineException e) {
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
      api = connect(segment, item);
      if (api != null) {
        try {

          long modifiedTime = 0;

          Sardine sardine = SardineFactory.begin(
              api.getString(Field.EMAIL.getName()),
              api.getString(Field.PASSWORD.getName()),
              config.getProperties().getxRayEnabled());

          // get info
          String path = WebDavMapping.getResourcePath(api, fileId);

          Set<QName> props = new HashSet<>();
          List<DavResource> resources = AWSXraySardine.list(
              sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
          String filename = emptyString;
          if (!resources.isEmpty()) {
            DavResource res = resources.get(0);
            modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
            filename = getResourceName(res);
          }
          String thumbnailName =
              StorageType.getShort(storageType) + "_" + fileId + "_" + modifiedTime + ".png";
          JsonObject json = new JsonObject()
              .put(Field.ENCAPSULATED_ID.getName(), fileId)
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
                  filename.equals(emptyString)
                      ? Field.UNKNOWN.getName()
                      : filename) // todo maybe check the name on
              // the server
              .put(Field.VERSION_ID.getName(), String.valueOf(modifiedTime))
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String filePath = WebDavMapping.getResourcePath(api, fileId);

      // get info
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + filePath), 0, props);
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

    } catch (SardineException e) {
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
        String path = WebDavMapping.getResourcePath(api, id);
        if (path.lastIndexOf(S3Regional.pathSeparator) == -1) {
          result.add(new JsonObject()
              .put(Field.NAME.getName(), path)
              .put(Field.VIEW_ONLY.getName(), false)
              .put(
                  Field.ENCAPSULATED_ID.getName(),
                  Utils.getEncapsulatedId(
                      storageType, api.getString(Field.EXTERNAL_ID.getName()), id)));
        } else {
          String userAccount = api.getString(Field.PATH.getName());
          String[] tmps = path.replaceAll(userAccount + S3Regional.pathSeparator, emptyString)
              .split(S3Regional.pathSeparator);
          StringBuilder currentPath = new StringBuilder();
          List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();
          for (String name : tmps) {
            currentPath.append(name).append(S3Regional.pathSeparator);
            WebDavMapping webDavMapping =
                new WebDavMapping(api, userAccount + S3Regional.pathSeparator + currentPath);
            result.add(new JsonObject()
                .put(Field.NAME.getName(), name)
                .put(Field.VIEW_ONLY.getName(), false)
                .put(
                    Field.ENCAPSULATED_ID.getName(),
                    Utils.getEncapsulatedId(
                        storageType,
                        api.getString(Field.EXTERNAL_ID.getName()),
                        webDavMapping.getReturnId())));
            if (webDavMapping.isShouldBeSaved()) {
              webDavMappingObjectsToSave.add(webDavMapping);
            }
          }
        }
      }
      Collections.reverse(result.getList());
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
      sendError(segment, message, "could not connect to Storage", 500);
      return;
    }
    Sardine sardine = SardineFactory.begin(
        api.getString(Field.EMAIL.getName()),
        api.getString(Field.PASSWORD.getName()),
        config.getProperties().getxRayEnabled());

    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    ZipOutputStream stream = new ZipOutputStream(bos);
    Set<String> fileNames = new HashSet<>();
    Set<String> folderNames = new HashSet<>();
    Set<String> filteredFileNames = new HashSet<>();
    List<Callable<Void>> callables = new ArrayList<>();
    Set<QName> props = new HashSet<>();
    try {
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
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
          Entity standaloneSegment = XRayManager.createStandaloneSegment(
              operationGroup, subSegment, "MultipleDownloadSegment");
          String webdavPath = WebDavMapping.getResourcePath(api, objectId);
          String encodedPath = encodePath(api.getString(Field.URL.getName()) + webdavPath);
          if (isFolder && !encodedPath.endsWith(S3Regional.pathSeparator)) {
            // only needed for folders
            encodedPath += S3Regional.pathSeparator;
          }
          List<DavResource> resources;
          try {
            resources = AWSXraySardine.list(sardine, encodedPath, 0, props);
          } catch (IOException ex) {
            log.error("[ZIP] Webdav get info " + encodedPath, ex);
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
                  stream, res, sardine, api, emptyString, filter, filteredFileNames, fileNames);
            }
          }
          XRayManager.endSegment(standaloneSegment);
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
        Entity standaloneSegment =
            XRayManager.createStandaloneSegment(operationGroup, segment, "WebDavZipFolderSegment");
        zipFolder(
            stream, api, folderId, filter, recursive, emptyString, new HashSet<>(), 0, request);
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
      JsonObject api,
      String folderId,
      String filter,
      boolean recursive,
      String path,
      Set<String> filteredFileNames,
      int recursionDepth,
      Item request)
      throws Exception {
    Sardine sardine = SardineFactory.begin(
        api.getString(Field.EMAIL.getName()),
        api.getString(Field.PASSWORD.getName()),
        config.getProperties().getxRayEnabled());
    String webdavPath = WebDavMapping.getResourcePath(api, folderId);
    Set<String> fileNames = new HashSet<>(), folderNames = new HashSet<>();
    Set<QName> props = new HashSet<>();
    props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
    String encodedPath = encodePath(api.getString(Field.URL.getName()) + webdavPath);
    if (!encodedPath.endsWith(S3Regional.pathSeparator)) {
      encodedPath += S3Regional.pathSeparator;
    }
    List<DavResource> resources;
    try {
      resources = AWSXraySardine.list(sardine, encodedPath, 1, props);
    } catch (IOException ex) {
      log.error("[ZIP] Webdav list " + encodedPath, ex);
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
    List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();
    String pathPrefix = path.isEmpty() ? path : path + File.separator;
    for (DavResource res : resources) {
      if (res.getContentType().equals("httpd/unix-directory")) {
        WebDavMapping webDavMapping = new WebDavMapping(api, res);
        String id = webDavMapping.getReturnId();
        if (webDavMapping.isShouldBeSaved()) {
          webDavMappingObjectsToSave.add(webDavMapping);
        }
        if (!folderId.equals(id) && recursive) {
          String name = res.getName();
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
                pathPrefix + name,
                filteredFileNames,
                recursionDepth,
                request);
          } else {
            log.warn("Zip folder recursion exceeds the limit : " + id);
          }
        }
      } else {
        long fileSize = res.getContentLength();
        if (fileSize > MAXIMUM_DOWNLOAD_FILE_SIZE) {
          excludeFileFromRequest(request, res.getName(), ExcludeReason.Large);
          return;
        }
        addZipEntry(stream, res, sardine, api, pathPrefix, filter, filteredFileNames, fileNames);
      }
    }
    WebDavMapping.saveListOfMappings(webDavMappingObjectsToSave);
  }

  private void addZipEntry(
      ZipOutputStream stream,
      DavResource res,
      Sardine sardine,
      JsonObject api,
      String pathPrefix,
      String filter,
      Set<String> filteredFileNames,
      Set<String> fileNames)
      throws IOException {
    String name = res.getName();
    String resPath = WebDavMapping.getScopedPath(api, res.getPath());
    byte[] bytes;
    try (InputStream in =
        AWSXraySardine.get(sardine, encodePath(api.getString(Field.URL.getName()) + resPath))) {
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
      zipEntry = new ZipEntry(pathPrefix + name);
    }
    if (zipEntry != null) {
      stream.putNextEntry(zipEntry);
      stream.write(bytes);
      stream.closeEntry();
      stream.flush();
    }
  }

  @Override
  public void doGlobalSearch(
      Message<JsonObject>
          message) { // todo jp webdav server returns , HttpStatus.NOT_IMPLEMENTED not implemented
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
          .map(webdavUser -> {
            final String externalId = webdavUser.getString(ExternalAccounts.sk);
            Entity subSegment = XRayManager.createSubSegment(operationGroup, segment, externalId);
            JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
            JsonObject api = connect(subSegment, webdavUser);
            if (api == null) {
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(Field.NAME.getName(), webdavUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            try {
              SardineImpl2 sardine = SardineFactory.begin(
                  api.getString(Field.EMAIL.getName()),
                  api.getString(Field.PASSWORD.getName()),
                  config.getProperties().getxRayEnabled());
              List<DavResource> resources = sardine.search(
                  api.getString(Field.URL.getName()), query, api.getString(Field.EMAIL.getName()));
              List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();
              for (DavResource res : resources) {
                String parent = res.getPath();
                if (parent.lastIndexOf(S3Regional.pathSeparator) >= 0) {
                  parent = Utils.getFilePathFromPathWithSlash(parent);
                } else {
                  parent = S3Regional.pathSeparator;
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
                WebDavMapping parentWebDavMapping = new WebDavMapping(api, parent);
                WebDavMapping webDavMapping = new WebDavMapping(api, res);
                String resourceId = webDavMapping.getReturnId();
                if (!isFile) {
                  JsonObject obj =
                      getFolderJson(resourceId, res, parentWebDavMapping.getReturnId(), externalId);
                  foldersJson.add(obj);
                } else {
                  JsonObject json = getFileJson(
                      resourceId,
                      res,
                      parentWebDavMapping.getReturnId(),
                      false,
                      isAdmin,
                      externalId,
                      userId);
                  // AS : Removing this temporarily until we have some server cache (WB-1248)
                  //              json.put("thumbnailStatus",
                  //
                  // ThumbnailsManager.getThumbnailStatus(json.getString(Field.WS_ID
                  //                  .getName()),
                  //                      api.getString(Field.STORAGE_TYPE.getName()),
                  // json.getString
                  //                      (Field.VER_ID.getName()),
                  //                      false, false));
                  filesJson.add(json);
                }
                if (parentWebDavMapping.isShouldBeSaved()) {
                  webDavMappingObjectsToSave.add(parentWebDavMapping);
                }
                if (webDavMapping.isShouldBeSaved()) {
                  webDavMappingObjectsToSave.add(webDavMapping);
                }
              }
              WebDavMapping.saveListOfMappings(webDavMappingObjectsToSave);
            } catch (IOException e) {
              log.error(e);
              XRayManager.endSegment(subSegment);
              return new JsonObject()
                  .put(Field.STORAGE_TYPE.getName(), storageType.name())
                  .put(Field.EXTERNAL_ID.getName(), webdavUser.getString(Field.SK.getName()))
                  .put(Field.NAME.getName(), webdavUser.getString(Field.EMAIL.getName()))
                  .put(Field.FILES.getName(), filesJson)
                  .put(Field.FOLDERS.getName(), foldersJson);
            }
            XRayManager.endSegment(subSegment);
            return new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), storageType.name())
                .put(Field.EXTERNAL_ID.getName(), webdavUser.getString(Field.SK.getName()))
                .put(Field.NAME.getName(), webdavUser.getString(Field.EMAIL.getName()))
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
    // DK: TODO: refactor all. It's hardly understandable
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
          JsonObject api = connect(segment, dbUser);
          if (api == null) {
            continue;
          }

          JsonArray results = new JsonArray();

          if (!xrefsCache.getJsonArray("unknownXrefs").isEmpty()) {
            // parsing current folder
            String currentFolder = null;
            if (requestFolderId != null) {
              currentFolder = WebDavMapping.getResourcePath(api, requestFolderId);
              if (!currentFolder.endsWith(S3Regional.pathSeparator)) {
                currentFolder += S3Regional.pathSeparator;
              }
            }
            if (currentFolder == null) {
              String filePath = WebDavMapping.getResourcePath(api, fileId);
              if (filePath.lastIndexOf(S3Regional.pathSeparator) == -1) {
                sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST);
                return;
              }
              currentFolder =
                  filePath.substring(0, filePath.lastIndexOf(S3Regional.pathSeparator) + 1);
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
                          folderPath = folderPath.substring(
                              0, folderPath.lastIndexOf(S3Regional.pathSeparator));
                        } catch (Exception e) {
                          log.error(e);
                          break;
                        }
                        // subfolder (e.g. subfolder/file.dwg)
                      } else {
                        resultPath = folderPath + S3Regional.pathSeparator + endPart;
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
                        finalCurrentFolder.substring(0, finalCurrentFolder.length() - 1)
                            + S3Regional.pathSeparator
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + resultPath), 1, props);

      List<WebDavMapping> webDavMappingObjectsToSave = new ArrayList<>();
      for (DavResource res : resources) {
        long updateDate = res.getModified() != null ? res.getModified().getTime() : 0;
        long size = res.getContentLength();
        String name = getResourceName(res);
        WebDavMapping webDavMapping = new WebDavMapping(api, res);
        String resultId = webDavMapping.getReturnId();
        if (webDavMapping.isShouldBeSaved()) {
          webDavMappingObjectsToSave.add(webDavMapping);
        }
        String id = Utils.getEncapsulatedId(storageType, externalId, resultId);

        pathFiles.add(new JsonObject()
            .put(Field.ENCAPSULATED_ID.getName(), id)
            .put(Field.OWNER.getName(), getOwner(res))
            .put(Field.IS_OWNER.getName(), isOwner(res))
            .put(Field.UPDATE_DATE.getName(), updateDate)
            .put(Field.CHANGER.getName(), emptyString)
            .put(Field.SIZE.getName(), Utils.humanReadableByteCount(size))
            .put(Field.SIZE_VALUE.getName(), size)
            .put(Field.NAME.getName(), name)
            .put(Field.STORAGE_TYPE.getName(), storageType.name()));
        break;
      }

    } catch (Exception e) {
      log.info("Error on searching for xref in possible folder in WebDAV", e);
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
      String currentFolder = WebDavMapping.getResourcePath(api, folderId);
      if (currentFolder == null) {
        String filePath = WebDavMapping.getResourcePath(api, fileId);
        if (filePath.lastIndexOf(S3Regional.pathSeparator) == -1) {
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
              String[] array = pathStr.split(S3Regional.pathSeparator);
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
                resultPath = finalCurrentFolder + S3Regional.pathSeparator + pathStr;
              } else {
                for (String s : array) {
                  if (s.isEmpty()) {
                    continue;
                  }
                  // file in parent (e.g. ../file.dwg) - each .. removes one folder from folderId
                  if ("..".equals(s)) {
                    try {
                      fId = fId.substring(0, fId.lastIndexOf(S3Regional.pathSeparator));
                    } catch (Exception e) {
                      log.error(e);
                      break;
                    }
                  }
                  // subfolder (e.g. subfolder/file.dwg)
                  else {
                    resultPath = fId
                        + S3Regional.pathSeparator
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
              Sardine sardine = SardineFactory.begin(
                  api.getString(Field.EMAIL.getName()),
                  api.getString(Field.PASSWORD.getName()),
                  config.getProperties().getxRayEnabled());
              Set<QName> props = new HashSet<>();
              props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
              List<DavResource> resources = AWSXraySardine.list(
                  sardine, encodePath(api.getString(Field.URL.getName()) + resultPath), 1, props);
              boolean exists = false;
              for (DavResource res : resources) {
                String resPath = WebDavMapping.getScopedPath(api, res.getPath());
                if (resPath.endsWith(S3Regional.pathSeparator)) {
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
    WebDavMapping.removeAllMappings(externalId);
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());
      String webdavFileId = WebDavMapping.getResourceFileId(api, fileId);
      String path = WebDavMapping.getResourcePath(api, fileId);
      String url = api.getString(Field.URL.getName());

      if (!url.startsWith(nextCloudKriegeritURL) && !url.startsWith(cloudKriegeritURL)) {
        sendError(segment, message, emptyString, HttpStatus.NOT_IMPLEMENTED);
        return;
      }

      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(sardine, encodePath(url + path), 0, props);

      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        webdavFileId = WebDavMapping.getFileId(res);
      }

      if (!webdavFileId.isEmpty()) {
        String versionsPath = "versions/" + api.getString(Field.EMAIL.getName()) + "/versions/"
            + webdavFileId + S3Regional.pathSeparator + versionId;
        String targetPath = "versions/" + api.getString(Field.EMAIL.getName()) + "/restore/target";

        if (url.startsWith(nextCloudKriegeritURL) || url.startsWith(cloudKriegeritURL)) {
          url = url.substring(0, url.lastIndexOf("files/"));
        }

        sardine.move(encodePath(url + versionsPath), encodePath(url + targetPath));
      }

      long newVersionId = 0;
      try {
        for (DavResource res : AWSXraySardine.list(
            sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props)) {
          String resPath = WebDavMapping.getScopedPath(api, res.getPath());
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
              .put(Field.VERSION_ID.getName(), ((Long) (newVersionId / 100)).toString()),
          mills);
    } catch (SardineException e) {
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
    sendNotImplementedError(message);
  }

  @Override
  public void doDeShare(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  @Override
  public void doRestore(Message<JsonObject> message) {
    sendNotImplementedError(message);
  }

  private VersionInfo getVersionInfo(
      String fileId, DavResource resource, String versionId, String url) {
    String hash = resource.getEtag();
    if (Utils.isStringNotNullOrEmpty(hash)) {
      hash = hash.replaceAll("^\"|\"$", emptyString);
    }
    long size = resource.getContentLength();
    long date = resource.getModified().getTime();
    boolean canPromote = url.startsWith(nextCloudKriegeritURL) || url.startsWith(cloudKriegeritURL);

    if (!Utils.isStringNotNullOrEmpty(versionId)) {
      versionId = String.valueOf(date / 100);
      canPromote = false;
    }

    VersionPermissions versionPermissions = new VersionPermissions();
    versionPermissions.setCanPromote(canPromote);
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      // some records already have this stored. But we needt to call the url to get the version
      // of the current version

      String webdavFileId = WebDavMapping.getResourceFileId(api, fileId);
      String path = WebDavMapping.getResourcePath(api, fileId);

      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);
      JsonArray results = new JsonArray();
      String name = null;
      if (!resources.isEmpty()) {
        DavResource res = resources.get(0);
        webdavFileId = WebDavMapping.getFileId(res);
        name = getResourceName(res);
        results.add(getVersionInfo(fileId, res, null, api.getString(Field.URL.getName()))
            .toJson());
      }

      if (!webdavFileId.isEmpty()) {
        // http://nextcloud.dev.graebert.com/remote.php/dav/versions/{user}/versions/{file-id}
        String versionsPath =
            "versions/" + api.getString(Field.EMAIL.getName()) + "/versions/" + webdavFileId;
        // this url might contain /files.lets remove that.
        String url = api.getString(Field.URL.getName());
        if (url.contains("files/")
            || url.startsWith(nextCloudKriegeritURL)
            || url.startsWith(cloudKriegeritURL)) {
          url = url.substring(0, url.lastIndexOf("files/"));
        }
        props = new HashSet<>();
        resources = AWSXraySardine.list(sardine, encodePath(url + versionsPath), 1, props);
        String root = emptyString;
        for (DavResource res : resources) {
          if (res.getContentType().equals("httpd/unix-directory")) {
            root = res.getPath();
          } else {
            results.add(getVersionInfo(
                    fileId,
                    res,
                    res.getPath().substring(root.length()),
                    api.getString(Field.URL.getName()))
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
                log.warn("[WEBDAV] Get versions: Couldn't get object info to get extension.", ex);
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
    } catch (SardineException e) {
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          config.getProperties().getxRayEnabled());

      String path = WebDavMapping.getResourcePath(api, fileId);

      Set<QName> props = new HashSet<>();
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);

      for (DavResource res : resources) {
        sendOK(
            XRayManager.createSegment(operationGroup, message),
            message,
            new JsonObject()
                .put(
                    Field.VERSION_ID.getName(),
                    ((Long) (res.getModified().getTime() / 100)).toString()),
            System.currentTimeMillis());
        return;
      }
      sendOK(
          XRayManager.createSegment(operationGroup, message),
          message,
          new JsonObject().put(Field.VERSION_ID.getName(), emptyString),
          System.currentTimeMillis());

    } catch (SardineException e) {
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

  private boolean isOwner(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey(Field.PERMISSIONS.getName())) {
      return !props.get(Field.PERMISSIONS.getName()).contains("S");
    }
    return true;
  }

  private boolean canMove(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey(Field.PERMISSIONS.getName())) {
      return props.get(Field.PERMISSIONS.getName()).contains("V");
    }
    return true;
  }

  private boolean canWrite(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey(Field.PERMISSIONS.getName())) {
      String permission = props.get(Field.PERMISSIONS.getName());
      // CK - for folders (can add files - C/folders - K)
      // W - for files (can write)
      return permission.isEmpty()
          || (permission.contains("C") && permission.contains("K"))
          || permission.contains("W");
    }
    return true;
  }

  private boolean isShared(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    if (props.containsKey("share-types")) {
      return !props.get("share-types").isEmpty();
    }
    return false;
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
      String[] splits = res.getPath().split(S3Regional.pathSeparator);
      return splits[splits.length - 1];
    }
  }

  private JsonObject getFolderJson(String id, DavResource res, String parentId, String externalId) {
    ObjectPermissions permissions = new ObjectPermissions()
        .setAllTo(true)
        .setBatchTo(
            List.of(
                AccessType.canClone,
                AccessType.canViewPublicLink,
                AccessType.canViewPermissions,
                AccessType.canManagePublicLink,
                AccessType.canManagePermissions),
            false);
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
        .put(Field.OWNER.getName(), getOwner(res))
        .put(Field.OWNER_EMAIL.getName(), emptyString)
        .put(Field.OWNER_ID.getName(), getOwnerId(res))
        .put(
            Field.CREATION_DATE.getName(),
            res.getCreation() != null ? res.getCreation().getTime() : 0)
        .put(Field.SHARED.getName(), isShared(res))
        .put(Field.VIEW_ONLY.getName(), !canWrite(res))
        .put(Field.IS_OWNER.getName(), isOwner(res))
        .put(Field.CAN_MOVE.getName(), canMove(res))
        .put(Field.PERMISSIONS.getName(), permissions.toJson())
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()));
  }

  private JsonObject getFileJson(
      String resourceId,
      DavResource res,
      String parentId,
      boolean full,
      boolean isAdmin,
      String externalId,
      String userId) {

    long modifiedTime = res.getModified() != null ? res.getModified().getTime() : 0;
    String thumbnailName = ThumbnailsManager.getThumbnailName(
        storageType, resourceId, ((Long) (modifiedTime / 100)).toString());
    JsonObject plData = new JsonObject().put(Field.IS_PUBLIC.getName(), false);
    if (full) {
      plData = findLinkForFile(resourceId, externalId, userId, false);
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
        .put(Field.OWNER.getName(), getOwner(res))
        .put(Field.OWNER_EMAIL.getName(), emptyString)
        .put(Field.OWNER_ID.getName(), getOwnerId(res))
        .put(
            Field.CREATION_DATE.getName(),
            res.getCreation() != null ? res.getCreation().getTime() : 0)
        .put(Field.UPDATE_DATE.getName(), modifiedTime)
        .put(Field.VER_ID.getName(), ((Long) (modifiedTime / 100)).toString())
        .put(Field.VERSION_ID.getName(), ((Long) (modifiedTime / 100)).toString())
        .put(Field.CHANGER.getName(), emptyString)
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(res.getContentLength()))
        .put(Field.SIZE_VALUE.getName(), res.getContentLength())
        .put(Field.SHARED.getName(), isShared(res))
        .put(Field.VIEW_ONLY.getName(), !canWrite(res))
        .put(Field.IS_OWNER.getName(), isOwner(res))
        .put(Field.CAN_MOVE.getName(), canMove(res))
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PUBLIC.getName(), plData.getBoolean(Field.IS_PUBLIC.getName()))
        .put(Field.PREVIEW_ID.getName(), previewId)
        .put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
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
                AccessType.canViewPublicLink,
                AccessType.canManagePermissions,
                AccessType.canManagePublicLink,
                AccessType.canMoveFrom,
                AccessType.canMoveTo,
                AccessType.canCreateFiles,
                AccessType.canCreateFolders),
            false);
    json.put(Field.PERMISSIONS.getName(), permissions.toJson());
    return json;
  }

  @Override
  public void connect(Message<JsonObject> message, boolean replyOnOk) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    connect(segment, message, message.body(), replyOnOk);
  }

  private <T> JsonObject connect(Entity segment, Message<T> message) {
    return connect(segment, message, MessageUtils.parse(message).getJsonObject(), false);
  }

  private <T> JsonObject connect(
      Entity segment, Message<T> message, JsonObject json, boolean replyOnOk) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "WEBDAV.Connect");
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String webdavId = json.containsKey(Field.EXTERNAL_ID.getName())
        ? json.getString(Field.EXTERNAL_ID.getName())
        : emptyString;
    XRayEntityUtils.putMetadata(
        subsegment,
        XrayField.CONNECTION_DATA,
        new JsonObject().put(Field.USER_ID.getName(), userId).put("webdavId", webdavId));

    if (userId == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(StorageType.WEBDAV, null, webdavId, ConnectionError.NO_USER_ID);
      return null;
    }
    if (webdavId == null || webdavId.isEmpty()) {
      webdavId = findExternalId(segment, message, json, storageType);
      XRayEntityUtils.putMetadata(
          subsegment, XrayField.NEW_DAV_ID, new JsonObject().put(Field.ID.getName(), webdavId));

      if (webdavId == null) {
        logConnectionIssue(StorageType.WEBDAV, userId, null, ConnectionError.NO_EXTERNAL_ID);
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "FL6"),
            HttpStatus.BAD_REQUEST,
            "FL6");
        return null;
      }
    }

    Item webdavUser = ExternalAccounts.getExternalAccount(userId, webdavId);

    XRayEntityUtils.putMetadata(subsegment, XrayField.DAV_USER, webdavUser);

    if (webdavUser == null) {
      XRayManager.endSegment(subsegment);
      logConnectionIssue(StorageType.WEBDAV, userId, null, ConnectionError.NO_ENTRY_IN_DB);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDidNotGrantAccessToThisWebDAVAccount"),
          HttpStatus.FORBIDDEN);
      return null;
    }
    return connect(segment, message, webdavUser, replyOnOk);
  }

  private <T> JsonObject connect(
      Entity segment, Message<T> message, Item webdavUser, boolean replyOnOk) {
    String password = webdavUser.getString(Field.PASSWORD.getName());
    try {
      password = EncryptHelper.decrypt(password, config.getProperties().getFluorineSecretKey());
      String path = webdavUser.hasAttribute(Field.PATH.getName())
          ? webdavUser.getString(Field.PATH.getName())
          : emptyString;
      String url = webdavUser.getString(Field.URL.getName());

      // lets trim url to keep the path
      if (url.endsWith(path)) {
        url = url.substring(0, url.length() - path.length());
      }
      if (path.endsWith(S3Regional.pathSeparator)) {
        path = path.substring(0, path.length() - 1);
      }

      JsonObject api = new JsonObject()
          .put(Field.EMAIL.getName(), webdavUser.getString(Field.EMAIL.getName()))
          .put(Field.EXTERNAL_ID.getName(), webdavUser.getString(ExternalAccounts.sk))
          .put(Field.PASSWORD.getName(), password)
          .put(Field.URL.getName(), url)
          .put(Field.PATH.getName(), path)
          .put(Field.PREFIX.getName(), new URL(url).getPath())
          .put(Field.STORAGE_TYPE.getName(), storageType.name())
          .put(
              "isAccountThumbnailDisabled",
              webdavUser.hasAttribute("disableThumbnail")
                  && webdavUser.getBoolean("disableThumbnail"));
      XRayManager.endSegment(segment);

      if (replyOnOk) {
        sendOK(segment, message);
      }
      return api;
    } catch (Exception e) {
      XRayManager.endSegment(segment);
      log.error(e);
      logConnectionIssue(StorageType.WEBDAV, null, null, ConnectionError.CONNECTION_EXCEPTION);
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
      return null;
    }
  }

  private JsonObject connect(Entity segment, Item webdavUser) {
    return connect(
        XRayManager.createSubSegment(operationGroup, segment, "connectByItem"),
        null,
        webdavUser,
        false);
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
      Sardine sardine = SardineFactory.begin(
          api.getString(Field.EMAIL.getName()),
          api.getString(Field.PASSWORD.getName()),
          XRayManager.isXrayEnabled());

      String path;

      path = WebDavMapping.getResourcePath(api, id);
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

      Set<QName> props = new HashSet<>();
      props.add(new QName(namespaceUrl, Field.ID.getName(), "oc"));
      props.add(new QName(namespaceUrl, "fileid", "oc"));
      props.add(new QName(namespaceUrl, "owner-display-name", "oc"));
      props.add(new QName(namespaceUrl, "owner-id", "oc"));
      props.add(new QName(namespaceUrl, Field.PERMISSIONS.getName(), "oc"));
      List<DavResource> resources = AWSXraySardine.list(
          sardine, encodePath(api.getString(Field.URL.getName()) + path), 0, props);

      //            String parent = path;
      //            if (parent.lastIndexOf(S3Regional.pathSeparator) >= 0)
      //                parent = Utils.getFilePathFromPathWithSlash(parent);
      //            else
      //                parent = "/";

      boolean isFileFound = false;
      for (DavResource res : resources) {
        String resPath = WebDavMapping.getScopedPath(api, res.getPath());
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

  protected enum Permissions {
    CanWrite, // W
    CanDelete, // D
    CanRename, // N
    CanMove, // V
    CanAddFile, // C
    CanAddSubDirectories, // K
    CanReshare, // R
    // Note: on the server, this means SharedWithMe, but in discoveryphase.cpp we also set
    // this permission when the server reports the any "share-types"
    IsShared, // S
    IsMounted, // M
    IsMountedSub, // m (internal: set if the parent dir has IsMounted)
    HasZSyncMetadata, // z (internal: set if remote file has zsync metadata property set)
  }

  private static class Connection {
    public URL testUrl;
    public String urlString;
    public String path;

    Connection(URL t, String s, String p) {
      testUrl = t;
      urlString = s;
      path = p;
    }
  }
}
