package com.graebert.storage.integration.onedrive.graphApi;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.entity.CloneObjectResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.CreateFolderResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.DriveInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.GraphId;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.Permissions;
import com.graebert.storage.integration.onedrive.graphApi.entity.PromoteVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.RootInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.SiteInfo;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadFileResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.UploadVersionResult;
import com.graebert.storage.integration.onedrive.graphApi.entity.VersionList;
import com.graebert.storage.integration.onedrive.graphApi.exception.CheckFileException;
import com.graebert.storage.integration.onedrive.graphApi.exception.CloneObjectException;
import com.graebert.storage.integration.onedrive.graphApi.exception.CreateFolderException;
import com.graebert.storage.integration.onedrive.graphApi.exception.DeleteException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetFileContentException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetFolderContentException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetMyDriveException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectPermissionsException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetRootInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetSiteInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetSitesException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetVersionsException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.integration.onedrive.graphApi.exception.MoveException;
import com.graebert.storage.integration.onedrive.graphApi.exception.PromoteVersionException;
import com.graebert.storage.integration.onedrive.graphApi.exception.RemoveObjectPermissionException;
import com.graebert.storage.integration.onedrive.graphApi.exception.RenameException;
import com.graebert.storage.integration.onedrive.graphApi.exception.RestoreObjectException;
import com.graebert.storage.integration.onedrive.graphApi.exception.ShareObjectException;
import com.graebert.storage.integration.onedrive.graphApi.exception.SiteNameNotFoundException;
import com.graebert.storage.integration.onedrive.graphApi.exception.UnauthorizedException;
import com.graebert.storage.integration.onedrive.graphApi.exception.UploadFileException;
import com.graebert.storage.integration.onedrive.graphApi.exception.UploadVersionException;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.thumbnails.ThumbnailsDao;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHeaders;

public abstract class GraphApi {
  protected final String GRAPH_URL = "https://graph.microsoft.com/v1.0";
  protected final String homeDrive;
  protected final String clientId;
  protected final String clientSecret;
  protected final String redirectUri;
  protected final String fluorineSecret;
  protected final ServerConfig config;

  // externalId is a microsoft id
  protected final String userId, externalId, email;
  protected String displayName = Dataplane.emptyString;
  protected final StorageType storageType;
  protected final boolean isAccountThumbnailDisabled;
  protected String accessToken, refreshToken;
  protected Long expires;
  protected String siteUrl = "";

  protected GraphApi(
      String homeDrive,
      String clientId,
      String clientSecret,
      String redirectUri,
      String fluorineSecret,
      ServerConfig config,
      StorageType storageType,
      String userId,
      String externalId)
      throws GraphException {
    try {
      this.homeDrive = homeDrive;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.redirectUri = redirectUri;
      this.fluorineSecret = fluorineSecret;
      this.config = config;

      if (userId == null || storageType == null || externalId == null) {
        throw new Error("CouldNotConnectToGraphAPIDueToMissingParameters");
      }

      this.userId = userId;
      this.storageType = storageType;
      this.externalId = externalId;

      Item onedriveUser = ExternalAccounts.getExternalAccount(userId, externalId);

      if (onedriveUser.hasAttribute("siteURL")) {
        this.siteUrl = onedriveUser.getString("siteURL");
      }

      isAccountThumbnailDisabled = onedriveUser.hasAttribute("disableThumbnail")
          && onedriveUser.getBoolean("disableThumbnail");

      email = onedriveUser.getString(Field.EMAIL.getName());

      displayName = onedriveUser.hasAttribute(Field.DISPLAY_NAME.getName())
          ? onedriveUser.getString(Field.DISPLAY_NAME.getName())
          : Dataplane.emptyString;

      accessToken = EncryptHelper.decrypt(
          onedriveUser.getString(Field.ACCESS_TOKEN.getName()), fluorineSecret);

      refreshToken = EncryptHelper.decrypt(
          onedriveUser.getString(Field.REFRESH_TOKEN.getName()), fluorineSecret);

      expires = onedriveUser.getLong(Field.EXPIRES.getName());

    } catch (Error | Exception exception) {
      throw new UnauthorizedException(exception);
    }
    checkRefreshToken();
  }

  protected GraphApi(
      String homeDrive,
      String clientId,
      String clientSecret,
      String redirectUri,
      String fluorineSecret,
      ServerConfig config,
      StorageType storageType,
      Item onedriveUser)
      throws GraphException {
    try {
      this.homeDrive = homeDrive;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.redirectUri = redirectUri;
      this.fluorineSecret = fluorineSecret;
      this.config = config;

      String externalId = onedriveUser.getString(Field.EXTERNAL_ID_LOWER.getName());
      String userId = onedriveUser.getString(Field.FLUORINE_ID.getName());

      if (userId == null || storageType == null || externalId == null) {
        throw new Error("CouldNotConnectToGraphAPIDueToMissingParameters");
      }

      this.userId = userId;
      this.storageType = storageType;
      this.externalId = externalId;

      if (onedriveUser.hasAttribute("siteURL")) {
        this.siteUrl = onedriveUser.getString("siteURL");
      }

      isAccountThumbnailDisabled = onedriveUser.hasAttribute("disableThumbnail")
          && onedriveUser.getBoolean("disableThumbnail");

      email = onedriveUser.getString(Field.EMAIL.getName());

      accessToken = EncryptHelper.decrypt(
          onedriveUser.getString(Field.ACCESS_TOKEN.getName()), fluorineSecret);

      refreshToken = EncryptHelper.decrypt(
          onedriveUser.getString(Field.REFRESH_TOKEN.getName()), fluorineSecret);

      expires = onedriveUser.getLong(Field.EXPIRES.getName());

    } catch (Error | Exception exception) {
      throw new UnauthorizedException(exception);
    }
    checkRefreshToken();
  }

  // connect OD || ODB
  protected GraphApi(
      String homeDrive,
      String clientId,
      String clientSecret,
      String redirectUri,
      String fluorineSecret,
      ServerConfig config,
      StorageType storageType,
      String authCode,
      String userId,
      String sessionId)
      throws GraphException {
    try {
      this.homeDrive = homeDrive;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.redirectUri = redirectUri;
      this.fluorineSecret = fluorineSecret;
      this.config = config;

      // get tokens
      String body = "grant_type=authorization_code" + "&client_id=" + clientId + "&redirect_uri="
          + redirectUri + "&client_secret=" + clientSecret + "&code=" + authCode;

      HttpResponse<String> codeResponse = AWSXRayUnirest.post(
              "https://login.microsoftonline.com/common/oauth2/v2.0/token", "oneDrive.exchangeCode")
          .header("Content-type", "application/x-www" + "-form-urlencoded")
          .body(body)
          .asString();

      JsonObject codeBody = new JsonObject(codeResponse.getBody());
      if (!codeResponse.isSuccess() || codeResponse.getStatus() != HttpStatus.OK) {
        throw new Error(getErrorMessageFromResponse(codeBody));
      }

      this.userId = userId;
      this.storageType = storageType;
      this.accessToken = codeBody.getString(Field.ACCESS_TOKEN.getName());
      this.refreshToken = codeBody.getString(Field.REFRESH_TOKEN.getName());
      this.expires = GMTHelper.utcCurrentTime() + codeBody.getLong("expires_in") * 1000;

      // get info
      HttpResponse<String> infoResponse = AWSXRayUnirest.get(GRAPH_URL + "/me", ".getUserInfo")
          .header("Authorization", "Bearer " + this.accessToken)
          .asString();

      JsonObject infoBody = new JsonObject(infoResponse.getBody());
      if (!infoResponse.isSuccess() || infoResponse.getStatus() != HttpStatus.OK) {
        throw new Error(getErrorMessageFromResponse(infoBody));
      }

      this.externalId = infoBody.getString(Field.ID.getName());
      this.email = infoBody.containsKey("mail") && infoBody.getString("mail") != null
          ? infoBody.getString("mail")
          : infoBody.containsKey("userPrincipalName")
                  && infoBody.getString("userPrincipalName") != null
              ? infoBody.getString("userPrincipalName")
              : "";
      this.displayName =
          infoBody.containsKey("displayName") && infoBody.getString("displayName") != null
              ? infoBody.getString("displayName")
              : Dataplane.emptyString;

      Item externalAccount = ExternalAccounts.getExternalAccount(this.userId, this.externalId);
      if (externalAccount == null) {
        externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(),
                this.userId,
                Field.EXTERNAL_ID_LOWER.getName(),
                this.externalId)
            .withString(Field.F_TYPE.getName(), this.storageType.toString())
            .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());
      }
      externalAccount
          .withString(
              Field.ACCESS_TOKEN.getName(), EncryptHelper.encrypt(this.accessToken, fluorineSecret))
          .withString(
              Field.REFRESH_TOKEN.getName(),
              EncryptHelper.encrypt(this.refreshToken, fluorineSecret))
          .withLong(Field.EXPIRES.getName(), this.expires)
          .withString(Field.EMAIL.getName(), this.email);
      if (!this.displayName.isBlank()) {
        externalAccount.withString(Field.DISPLAY_NAME.getName(), this.displayName);
      }

      this.isAccountThumbnailDisabled =
          ThumbnailsDao.doThumbnailDisabledForStorageOrURL(externalAccount);

      // this will save account
      Sessions.updateSessionOnConnect(
          externalAccount, this.userId, storageType, this.externalId, sessionId);
    } catch (Error | Exception exception) {
      throw new UnauthorizedException(exception);
    }
  }

  // connect SP
  protected GraphApi(
      String homeDrive,
      String clientId,
      String clientSecret,
      String redirectUri,
      String fluorineSecret,
      ServerConfig config,
      StorageType storageType,
      String authCode,
      String userId,
      String sessionId,
      String externalPrefix)
      throws GraphException {
    try {
      this.homeDrive = homeDrive;
      this.clientId = clientId;
      this.clientSecret = clientSecret;
      this.redirectUri = redirectUri;
      this.fluorineSecret = fluorineSecret;
      this.config = config;

      // get tokens
      String body = "grant_type=authorization_code" + "&client_id=" + clientId + "&redirect_uri="
          + redirectUri + "&client_secret=" + clientSecret + "&code=" + authCode;

      HttpResponse<String> codeResponse = AWSXRayUnirest.post(
              "https://login.microsoftonline.com/common/oauth2/v2.0/token", "oneDrive.exchangeCode")
          .header("Content-type", "application/x-www" + "-form-urlencoded")
          .body(body)
          .asString();

      JsonObject codeBody = new JsonObject(codeResponse.getBody());
      if (!codeResponse.isSuccess() || codeResponse.getStatus() != HttpStatus.OK) {
        throw new Error(getErrorMessageFromResponse(codeBody));
      }

      this.userId = userId;
      this.storageType = storageType;
      this.accessToken = codeBody.getString(Field.ACCESS_TOKEN.getName());
      this.refreshToken = codeBody.getString(Field.REFRESH_TOKEN.getName());
      this.expires = GMTHelper.utcCurrentTime() + codeBody.getLong("expires_in") * 1000;

      // get info
      HttpResponse<String> infoResponse = AWSXRayUnirest.get(GRAPH_URL + "/me", ".getUserInfo")
          .header("Authorization", "Bearer " + this.accessToken)
          .asString();

      JsonObject infoBody = new JsonObject(infoResponse.getBody());
      if (!infoResponse.isSuccess() || infoResponse.getStatus() != HttpStatus.OK) {
        throw new Error(getErrorMessageFromResponse(infoBody));
      }

      this.externalId = externalPrefix + infoBody.getString(Field.ID.getName());

      Item externalAccount = ExternalAccounts.getExternalAccount(this.userId, this.externalId);
      if (externalAccount == null) {
        JsonArray searchedSites = new JsonArray();
        try {
          HttpResponse<String> sitesSearchResult = AWSXRayUnirest.get(
                  GRAPH_URL + "/sites?search=*", "sharePont.getSites")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
          if (sitesSearchResult.getStatus() == HttpStatus.OK) {
            searchedSites = new JsonObject(sitesSearchResult.getBody()).getJsonArray("value");
          } else {
            throw new GetSitesException(sitesSearchResult);
          }
        } catch (Exception e) {
          DynamoBusModBase.log.error("[GraphAPI] Error in searching sites : " + e);
        }

        JsonObject foundSite = (JsonObject) searchedSites.stream()
            .filter(siteInfo -> {
              JsonObject siteData = ((JsonObject) siteInfo);
              return (siteData.containsKey("siteCollection")
                  && siteData.getJsonObject("siteCollection").containsKey("hostname"));
            })
            .findAny()
            .orElse(null);

        externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(),
                this.userId,
                Field.EXTERNAL_ID_LOWER.getName(),
                this.externalId)
            .withString(Field.F_TYPE.getName(), this.storageType.toString())
            .withLong(Field.CONNECTED.getName(), GMTHelper.utcCurrentTime());

        if (foundSite != null) {
          String hostname = foundSite.getJsonObject("siteCollection").getString("hostname");
          if (Utils.isStringNotNullOrEmpty(hostname)) {
            externalAccount.withString("siteURL", hostname);
            this.siteUrl = hostname;
            // this isn't actually email, but let's use it for showing up.
            externalAccount.withString(Field.EMAIL.getName(), hostname);
          } else {
            throw new SiteNameNotFoundException();
          }
        } else {
          throw new SiteNameNotFoundException();
        }
      }
      this.displayName =
          infoBody.containsKey("displayName") && infoBody.getString("displayName") != null
              ? infoBody.getString("displayName")
              : Dataplane.emptyString;
      externalAccount
          .withString(
              Field.ACCESS_TOKEN.getName(), EncryptHelper.encrypt(this.accessToken, fluorineSecret))
          .withString(
              Field.REFRESH_TOKEN.getName(),
              EncryptHelper.encrypt(this.refreshToken, fluorineSecret))
          .withLong(Field.EXPIRES.getName(), this.expires)
          .withString(Field.DISPLAY_NAME.getName(), displayName);

      this.email = externalAccount.getString(Field.EMAIL.getName());
      this.isAccountThumbnailDisabled =
          ThumbnailsDao.doThumbnailDisabledForStorageOrURL(externalAccount);

      // this will save account
      Sessions.updateSessionOnConnect(
          externalAccount, this.userId, storageType, this.externalId, sessionId);
    } catch (Error | Exception exception) {
      throw new UnauthorizedException(exception);
    }
  }

  protected void checkRefreshToken() throws GraphException {
    try {
      if (GMTHelper.utcCurrentTime() > (expires - 1000 * 60 * 30)) {
        HttpResponse<String> response = AWSXRayUnirest.post(
                "https://login.microsoftonline.com/common/oauth2/v2.0/token", "oneDrive.token")
            .header("Content-type", "application/x-www-form" + "-urlencoded")
            .body("grant_type=refresh_token" + "&client_id=" + clientId
                // + "&scope=" + SCOPE
                + "&refresh_token=" + refreshToken + "&redirect_uri=" + redirectUri
                + "&client_secret=" + clientSecret)
            .asString();

        JsonObject body = new JsonObject(response.getBody());
        if (!response.isSuccess() || response.getStatus() != HttpStatus.OK) {
          throw new Error(getErrorMessageFromResponse(body));
        }

        if (!body.containsKey(Field.ACCESS_TOKEN.getName())
            || !body.containsKey(Field.REFRESH_TOKEN.getName())) {
          DynamoBusModBase.log.error(
              "[GraphAPI] [checkRefreshToken] body doesn't contain tokens. Received body: "
                  + body.encodePrettily());
          throw new Error("Token response doesn't contain tokens");
        }

        if (!body.containsKey("expires_in")) {
          DynamoBusModBase.log.warn(
              "[GraphAPI] [checkRefreshToken] body doesn't contain expiration time. Received body: "
                  + body.encodePrettily());
        }

        accessToken = body.getString(Field.ACCESS_TOKEN.getName());
        expires = GMTHelper.utcCurrentTime() + body.getLong("expires_in", 0L) * 1000;
        refreshToken = body.getString(Field.REFRESH_TOKEN.getName());

        // save new tokens
        ExternalAccounts.updateAccessTokens(
            userId,
            externalId,
            EncryptHelper.encrypt(accessToken, fluorineSecret),
            EncryptHelper.encrypt(refreshToken, fluorineSecret),
            expires);
      }
    } catch (Error | Exception exception) {
      throw new UnauthorizedException(exception);
    }
  }

  public CreateFolderResult createFolder(String folderId, String name) throws GraphException {
    checkRefreshToken();
    GraphUtils.checkName(name);

    GraphId ids = new GraphId(folderId);

    String url = ids.getDriveUrl(homeDrive) + "/"
        + (GraphUtils.isRootFolder(ids.getId()) ? Field.ROOT.getName() : "items/" + ids.getId())
        + "/children";

    HttpResponse<String> response = AWSXRayUnirest.post(url, "oneDrive.createFolder")
        .header("Authorization", "Bearer " + accessToken)
        .header("Content-Type", "application/json")
        .body(new JsonObject()
            .put(Field.NAME.getName(), name)
            .put(Field.FOLDER.getName(), new JsonObject())
            .toString())
        .asString();

    if (response.getStatus() != HttpStatus.CREATED) {
      throw new CreateFolderException(response);
    }

    return new CreateFolderResult(
        new JsonObject(response.getBody()), ids.getDriveId(), storageType);
  }

  public ObjectInfo getBaseObjectInfo(boolean isFile, String objectId, Message message)
      throws GraphException {
    return getObjectInfo(isFile, objectId, false, false, false, null);
  }

  public <T> ObjectInfo getObjectInfo(
      Message<T> message,
      boolean isFile,
      String id,
      boolean full,
      boolean addShare,
      boolean isAdmin,
      boolean force,
      boolean canCreateThumbnail)
      throws GraphException {
    if (!isFile) {
      if (id.equals(GraphUtils.ROOT_FOLDER_ID)) {
        return GraphUtils.getRootFolderInfo(
            storageType,
            externalId,
            id,
            message != null
                ? Utils.getLocalizedString(message, Field.MY_FILES_FOLDER.getName())
                : Utils.getLocalizedString("en", Field.MY_FILES_FOLDER.getName()));
      } else if (id.equals(GraphUtils.ROOT_FOLDER_FAKE_ID)) {
        return GraphUtils.getRootFakeFolderInfo(
            storageType,
            externalId,
            message != null
                ? Utils.getLocalizedString(message, Field.MY_FILES_FOLDER.getName())
                : Utils.getLocalizedString("en", Field.MY_FILES_FOLDER.getName()));
      } else if (GraphUtils.isSharedWithMeFolder(id)) {
        return GraphUtils.getSharedFolderInfo(
            storageType,
            externalId,
            message != null
                ? Utils.getLocalizedString(message, Field.SHARED_WITH_ME_FOLDER.getName())
                : Utils.getLocalizedString("en", Field.SHARED_WITH_ME_FOLDER.getName()));
      }
    }

    return getObjectInfo(isFile, id, full, addShare, isAdmin, message);
  }

  public ObjectInfo getObjectInfo(
      boolean isFile, String id, boolean full, boolean addShare, boolean isAdmin, Message message)
      throws GraphException {
    checkRefreshToken();

    GraphId ids = new GraphId(id);
    String additionalParameters = "";
    if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
      additionalParameters = "?expand=versions($select=id)";
    }
    String url = ids.getItemUrl(homeDrive) + additionalParameters;

    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetObjectInfoException(response);
    }

    JsonObject result = new JsonObject(response.getBody());
    result.put(Field.SHARED_LINKS_ID.getName(), id);
    return GraphUtils.getFileJson(
        config,
        this,
        result,
        !isFile,
        full,
        externalId,
        id,
        addShare,
        storageType,
        isAdmin,
        userId,
        message);
  }

  public String getDownloadUrl(String id, String versionId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(id);
    if (Utils.isStringNotNullOrEmpty(versionId)) {
      return getVersionContentUrl(ids, versionId);
    }
    if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
      return ids.getItemUrl(homeDrive) + "/content";
    } else {
      String url = ids.getItemUrl(homeDrive) + "?select=id,@microsoft.graph.downloadUrl";
      HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getDownloadUrl")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() != HttpStatus.OK) {
        throw new GetObjectInfoException(response);
      }
      JsonObject result = new JsonObject(response.getBody());
      return result.getString("@microsoft.graph.downloadUrl");
    }
  }

  public JsonObject globalSearch(String query, boolean isAdmin) throws GraphException {
    checkRefreshToken();

    JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();

    try {
      HttpResponse<String> response;

      // get all files
      JsonArray value = new JsonArray();
      String nextLink = null;
      do {
        try {
          response = AWSXRayUnirest.get(
                  nextLink == null
                      ? GRAPH_URL + homeDrive + "/drive/search" + "(q='" + Utils.urlencode(query)
                          + "')"
                      : nextLink,
                  "oneDrive.search")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
        } catch (Exception e) {
          nextLink = null;
          response = null;
          DynamoBusModBase.log.error("[GraphAPI] Error in global search : " + e);
        }
        if (response != null && response.getStatus() == HttpStatus.OK) {
          JsonObject body = new JsonObject(response.getBody());
          nextLink = body.getString("@odata.nextLink");
          value.addAll(body.getJsonArray("value"));
        }
      } while (nextLink != null);

      // some manipulations
      List<String> remoteIds = new ArrayList<>();
      JsonObject obj;
      for (Object item : value) {
        obj = (JsonObject) item;
        String remoteId = obj.containsKey(Field.REMOTE_ID.getName())
            ? obj.getJsonObject(Field.REMOTE_ID.getName()).getString(Field.ID.getName())
            : "";
        if (remoteIds.contains(remoteId)) {
          continue;
        }
        if (!remoteId.isEmpty()) {
          remoteIds.add(remoteId);
        }
        try {
          if (obj.containsKey(Field.FILE.getName())
              || (obj.containsKey(Field.REMOTE_ID.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FILE.getName()))) {
            obj.put(Field.SHARED_LINKS_ID.getName(), obj.getString(Field.ID.getName()));

            ObjectInfo objectInfo = GraphUtils.getFileJson(
                config,
                this,
                obj,
                false,
                true,
                externalId,
                null,
                false,
                storageType,
                isAdmin,
                userId,
                null);
            filesJson.add(objectInfo.toJson());
          } else if (obj.containsKey(Field.FOLDER.getName())
              || (obj.containsKey(Field.REMOTE_ID.getName())
                  && obj.getJsonObject(Field.REMOTE_ID.getName())
                      .containsKey(Field.FOLDER.getName()))) {
            ObjectInfo objectInfo = GraphUtils.getFileJson(
                config,
                this,
                obj,
                true,
                true,
                externalId,
                null,
                false,
                storageType,
                isAdmin,
                userId,
                null);
            foldersJson.add(objectInfo.toJson());
          }
        } catch (Exception e) {
          DynamoBusModBase.log.error("[GraphAPI] Error in getting object info : " + e);
        }
      }

      // return found folders and files
      return new JsonObject()
          .put(Field.STORAGE_TYPE.getName(), storageType.toString())
          .put(Field.EXTERNAL_ID.getName(), externalId)
          .put(Field.NAME.getName(), email)
          .put(Field.FILES.getName(), filesJson)
          .put(Field.FOLDERS.getName(), foldersJson);
    } catch (Exception ignore) {
      return new JsonObject()
          .put(Field.STORAGE_TYPE.getName(), storageType.toString())
          .put(Field.EXTERNAL_ID.getName(), externalId)
          .put(Field.NAME.getName(), email)
          .put(Field.FILES.getName(), filesJson)
          .put(Field.FOLDERS.getName(), foldersJson);
    }
  }

  public ObjectInfo getFileInfoByToken(String specificDriveId, String id) throws GraphException {
    checkRefreshToken();

    GraphId ids;
    if (specificDriveId != null) {
      ids = new GraphId(specificDriveId, id);
    } else {
      ids = new GraphId(id);
    }
    String url = ids.getItemUrl(homeDrive)
        + (storageType.equals(StorageType.ONEDRIVEBUSINESS) ? "?$expand=versions($select=id)" : "");

    HttpResponse<String> infoResponse = AWSXRayUnirest.get(url, "oneDrive.getInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (infoResponse.getStatus() != HttpStatus.OK) {
      throw new GetObjectInfoException(infoResponse);
    }

    JsonObject result = new JsonObject(infoResponse.getBody());
    result.put(Field.SHARED_LINKS_ID.getName(), id);

    return GraphUtils.getFileJson(
        config,
        this,
        result,
        false,
        false,
        externalId,
        null,
        false,
        storageType,
        false,
        userId,
        null);
  }

  public byte[] getFileContent(String fileId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(fileId);
    String url = ids.getItemUrl(homeDrive) + "/content";

    HttpResponse<byte[]> response = AWSXRayUnirest.get(url, "oneDrive.getFile")
        .header("Authorization", "Bearer " + accessToken)
        .asBytes();
    if (response.getStatus() != HttpStatus.FOUND && response.getStatus() != HttpStatus.OK) {
      throw new GetFileContentException(response);
    }
    if (response.getStatus() == HttpStatus.FOUND) {
      String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
      GetRequest getRequest = AWSXRayUnirest.get(location, "oneDrive.getData");
      response = getRequest.asBytes();
      if (response.getStatus() != HttpStatus.OK
          && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
        throw new GetFileContentException(response);
      }
    }

    return response.getBody();
  }

  public UploadVersionResult uploadVersion(String fileId, InputStream stream, Integer fileSize)
      throws GraphException, IOException {
    checkRefreshToken();
    GraphId ids = new GraphId(fileId);
    if (Objects.isNull(fileSize)) {
      throw new UploadVersionException(new Throwable("File content size is not provided"));
    }
    HttpResponse<String> uploadResponse;
    if (fileSize > 4 * 1000 * 1000) {
      String uploadRequestURL = ids.getItemUrl(homeDrive) + "/createUploadSession";
      uploadResponse = uploadLargeFile(uploadRequestURL, stream, fileSize);
    } else {
      String uploadURL = ids.getItemUrl(homeDrive) + "/content";
      uploadResponse = AWSXRayUnirest.put(uploadURL, "oneDrive.uploadFile")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .header(HttpHeaders.CONTENT_TYPE, "text/plain")
          .body(stream)
          .asString();
    }

    if (Objects.nonNull(uploadResponse)
        && uploadResponse.getStatus() != HttpStatus.CREATED
        && uploadResponse.getStatus() != HttpStatus.OK) {
      throw new UploadVersionException(uploadResponse);
    }

    return new UploadVersionResult(new JsonObject(uploadResponse.getBody()), ids);
  }

  public UploadFileResult uploadConflictingFile(
      String folderId, String folderName, String name, InputStream stream, Integer fileSize)
      throws GraphException, IOException {
    checkRefreshToken();
    GraphId ids = new GraphId(folderId);

    String path = folderName + Utils.urlencode(name) + ":";

    return uploadFile(path, ids, stream, fileSize);
  }

  public UploadFileResult uploadFile(
      String folderId, String fileId, String name, InputStream stream, Integer fileSize)
      throws GraphException, IOException {
    checkRefreshToken();
    GraphId folderIds = new GraphId(folderId);
    GraphId fileIds = new GraphId(fileId);
    String folderName =
        ((GraphUtils.isRootFolder(folderIds.getId())) ? "/root" : "/items/" + folderIds.getId())
            + ":/";

    String path = (fileIds.getId() != null
        ? "/items/" + fileIds.getId()
        : folderName + Utils.urlencode(name) + ":");

    return uploadFile(path, fileIds.getId() != null ? fileIds : folderIds, stream, fileSize);
  }

  private UploadFileResult uploadFile(
      String path, GraphId ids, InputStream stream, Integer fileSize)
      throws GraphException, IOException {
    checkRefreshToken();
    HttpResponse<String> uploadResponse;

    if (fileSize > 4 * 1000 * 1000) {
      String url = ids.getDriveUrl(homeDrive) + path + "/createUploadSession";
      uploadResponse = uploadLargeFile(url, stream, fileSize);
    } else {
      String url = ids.getDriveUrl(homeDrive) + path + "/content";
      uploadResponse = AWSXRayUnirest.put(url, "oneDrive.uploadFile")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
          .header(HttpHeaders.CONTENT_TYPE, "text/plain")
          .body(stream)
          .asString();
    }

    if (uploadResponse.getStatus() != HttpStatus.CREATED
        && uploadResponse.getStatus() != HttpStatus.OK) {
      throw new UploadFileException(uploadResponse);
    }

    return new UploadFileResult(new JsonObject(uploadResponse.getBody()), ids);
  }

  private HttpResponse<String> uploadLargeFile(
      String uploadSessionUrl, InputStream stream, Integer fileSize)
      throws UploadFileException, IOException {
    HttpResponse<String> sessionResponse = AWSXRayUnirest.post(
            uploadSessionUrl, "oneDrive.createUploadSession")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .asString();
    if (sessionResponse.getStatus() != HttpStatus.OK) {
      throw new UploadFileException(sessionResponse);
    }
    JsonObject body = new JsonObject(sessionResponse.getBody());
    String uploadUrl = body.getString("uploadUrl");
    if (uploadUrl == null || uploadUrl.isEmpty()) {
      throw new UploadFileException(sessionResponse);
    }
    return AWSXRayUnirest.put(uploadUrl, "oneDrive.uploadLargeFile")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
        .header(HttpHeaders.CONTENT_RANGE, "bytes 0-" + (fileSize - 1) + "/" + fileSize)
        .body(IOUtils.toByteArray(stream))
        .asString();
  }

  public byte[] getFileVersionContent(String fileId, String versionId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(fileId);
    String url = getVersionContentUrl(ids, versionId);

    HttpResponse<byte[]> response = AWSXRayUnirest.get(url, "oneDrive.getFileVersion")
        .header("Authorization", "Bearer " + accessToken)
        .asBytes();

    if (response.getStatus() != HttpStatus.FOUND && response.getStatus() != HttpStatus.OK) {
      try {
        return getFileContent(fileId);
      } catch (Exception exception) {
        throw new GetFileContentException(response);
      }
    }
    if (response.getStatus() == HttpStatus.FOUND) {
      String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
      GetRequest getRequest = AWSXRayUnirest.get(location, "oneDrive.getData");
      response = getRequest.asBytes();
      if (response.getStatus() != HttpStatus.OK
          && response.getStatus() != HttpStatus.PARTIAL_CONTENT) {
        throw new GetFileContentException(response);
      }
    }

    return response.getBody();
  }

  public void shareObject(String id, String emailOrId, String role) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(id);

    if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
      JsonArray body = new JsonArray();

      try {
        body = getPermissionsForObject(ids.getDriveId(), ids.getId());
      } catch (GraphException ignore) {
      }

      JsonArray editor = new JsonArray();
      JsonArray viewer = new JsonArray();
      String editorPermissionId = null, viewerPermissionId = null;

      for (Object p : body) {
        JsonObject permission = (JsonObject) p;
        if (permission.containsKey(Field.ROLES.getName())
            && !permission.getJsonArray(Field.ROLES.getName()).contains(Field.OWNER.getName())) {
          if (permission.containsKey("grantedToV2")) {
            JsonObject userObj = permission.getJsonObject("grantedToV2");
            if (userObj.containsKey(Field.USER.getName())
                && Utils.isJsonObjectNotNullOrEmpty(userObj.getJsonObject(Field.USER.getName()))
                && userObj.getJsonObject(Field.USER.getName()).containsKey(Field.EMAIL.getName())) {
              JsonObject user = userObj.getJsonObject(Field.USER.getName());
              if (user.getString(Field.EMAIL.getName()).equals(emailOrId)) {
                this.deletePermission(id, permission.getString(Field.ID.getName()));
                this.inviteUser(
                    id,
                    role,
                    new JsonArray().add(new JsonObject().put(Field.EMAIL.getName(), emailOrId)),
                    true);
                return;
              }
            }
          } else if (permission.containsKey("grantedToIdentitiesV2")) {
            JsonArray users = permission.getJsonArray("grantedToIdentitiesV2");
            if (!users.isEmpty()) {
              if (permission.getJsonArray(Field.ROLES.getName()).contains(GraphUtils.WRITE)) {
                editorPermissionId = permission.getString(Field.ID.getName());
              } else {
                viewerPermissionId = permission.getString(Field.ID.getName());
              }
              for (Object u : users) {
                JsonObject userObj = (JsonObject) u;
                if (userObj.containsKey(Field.USER.getName())
                    && userObj
                        .getJsonObject(Field.USER.getName())
                        .containsKey(Field.EMAIL.getName())) {
                  JsonObject emailObj = new JsonObject()
                      .put(
                          Field.EMAIL.getName(),
                          userObj
                              .getJsonObject(Field.USER.getName())
                              .getString(Field.EMAIL.getName()));
                  if (permission.getJsonArray(Field.ROLES.getName()).contains(GraphUtils.WRITE)) {
                    editor.add(emailObj);
                  } else {
                    viewer.add(emailObj);
                  }
                }
              }
            }
          }
        }
      }
      if (role.equals(GraphUtils.READ) || role.equals(GraphUtils.WRITE)) {
        this.inviteUser(
            id,
            role,
            new JsonArray().add(new JsonObject().put(Field.EMAIL.getName(), emailOrId)),
            true);
        if (role.equals(GraphUtils.WRITE)) {
          Optional<Object> doViewerExist = viewer.stream()
              .filter(v -> ((JsonObject) v).getString(Field.EMAIL.getName()).equals(emailOrId))
              .findAny();
          if (doViewerExist.isPresent()) {
            this.deletePermission(id, viewerPermissionId);
            viewer.remove(doViewerExist.get());
            if (!viewer.isEmpty()) {
              this.inviteUser(id, GraphUtils.READ, viewer, false);
            }
          }
        } else {
          Optional<Object> doEditorExist = editor.stream()
              .filter(v -> ((JsonObject) v).getString(Field.EMAIL.getName()).equals(emailOrId))
              .findAny();
          if (doEditorExist.isPresent()) {
            this.deletePermission(id, editorPermissionId);
            editor.remove(doEditorExist.get());
            if (!editor.isEmpty()) {
              this.inviteUser(id, GraphUtils.WRITE, editor, false);
            }
          }
        }
      }
    } else {
      // create or update permission
      if (emailOrId.matches(".+@.+\\.[a-zA-Z]+")) {
        this.inviteUser(
            id,
            role,
            new JsonArray().add(new JsonObject().put(Field.EMAIL.getName(), emailOrId)),
            true);
      } else {
        this.updatePermission(
            id, emailOrId, new JsonObject().put(Field.ROLES.getName(), new JsonArray().add(role)));
      }
    }
  }

  private JsonObject requestFolderContent(String url) throws GetFolderContentException {
    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getFolderContent")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (response.getStatus() != HttpStatus.OK) {
      throw new GetFolderContentException(response);
    }
    return new JsonObject(response.getBody());
  }

  public JsonArray getFolderContentArray(
      String folderId,
      String nextLinkOriginal,
      boolean trash,
      boolean isTouch,
      boolean pagination,
      boolean useNewStructure)
      throws GraphException {
    checkRefreshToken();
    String nextLink = nextLinkOriginal;
    JsonArray value = new JsonArray();
    GraphId ids = new GraphId(folderId);

    HttpResponse<String> response;
    // shared with me folder
    if ((!isTouch || useNewStructure) && GraphUtils.isSharedWithMeFolder(ids.getId())) {
      response = AWSXRayUnirest.get(
              GRAPH_URL + homeDrive + "/drive/sharedWithMe", "oneDrive.getSharedWithMe")
          .header("Authorization", "Bearer " + accessToken)
          .asString();
      if (response.getStatus() == HttpStatus.OK) {
        JsonObject body = new JsonObject(response.getBody());
        value.addAll(body.getJsonArray("value"));
      } else {
        throw new GetFolderContentException(response);
      }
    }
    // other options
    else {
      if (GraphUtils.isSharedWithMeFolder(ids.getId())) {
        // case if it's ARES TOUCH and want to get shared files but the useFolderStructure isn't
        // specified or false
        throw new GetFolderContentException(
            new Throwable("[ARES TOUCH] Please use new folder structure to get shared files"));
      }
      String path = GraphUtils.isRootFolder(ids.getId()) ? "/root" : "/items/" + ids.getId();
      JsonArray nonDeletedFiles = new JsonArray();
      do {
        String url = Utils.isStringNotNullOrEmpty(nextLink)
            ? nextLink
            : ids.getDriveUrl(homeDrive) + path + "/children";

        JsonObject body = requestFolderContent(url);
        nextLink = body.getString("@odata.nextLink");
        if (trash) {
          nonDeletedFiles.addAll(body.getJsonArray("value"));
        } else {
          value.addAll(body.getJsonArray("value"));
        }
      } while (!pagination && nextLink != null);

      if (trash) {
        JsonArray allFiles = new JsonArray();
        nextLink = nextLinkOriginal;
        do {
          String url = Utils.isStringNotNullOrEmpty(nextLink)
              ? nextLink
              : ids.getDriveUrl(homeDrive) + path + "/children?includeDeletedItems=true";

          JsonObject body = requestFolderContent(url);
          nextLink = body.getString("@odata.nextLink");
          allFiles.addAll(body.getJsonArray("value"));
        } while (!pagination && nextLink != null);
        List<Map> finalDeletedList = allFiles.getList();
        nonDeletedFiles.stream().forEach(item -> {
          JsonObject obj = (JsonObject) item;
          finalDeletedList.removeIf(
              del -> del.get(Field.ID.getName()).equals(obj.getString(Field.ID.getName())));
        });
        value.addAll(new JsonArray(finalDeletedList));
      }
    }

    return value;
  }

  public JsonArray getFolderContentArray(String driveId, String folderId) throws GraphException {
    checkRefreshToken();
    JsonArray value;
    GraphId ids = new GraphId(driveId, folderId);

    // root folder
    if (GraphUtils.isRootFolder(ids.getId())) {
      value = requestFolderContent(GRAPH_URL + homeDrive + "/drive/root/children")
          .getJsonArray("value");

      value.addAll(requestFolderContent(GRAPH_URL + homeDrive + "/drive/sharedWithMe")
          .getJsonArray("value"));
    }

    // shared folder
    else if (GraphUtils.isSharedWithMeFolder(ids.getId())) {
      value =
          requestFolderContent(GRAPH_URL + homeDrive + "/drive/sharedWithMe").getJsonArray("value");
    }

    // other folder
    else {
      value = requestFolderContent(ids.getItemUrl(homeDrive) + "/children").getJsonArray("value");
    }

    return value;
  }

  public JsonArray getFolderContent(GraphId ids) throws GraphException {
    checkRefreshToken();

    String url = ids.getItemUrl(homeDrive) + "/children";
    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getFolderContent")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetFolderContentException(response);
    }

    return new JsonObject(response.getBody()).getJsonArray("value");
  }

  public void removeObjectPermission(String id, String permissionId, JsonObject objectToRemove)
      throws Exception {
    checkRefreshToken();
    GraphId ids = new GraphId(id);

    if (storageType.equals(StorageType.ONEDRIVEBUSINESS)) {
      JsonArray body = new JsonArray();

      try {
        body = getPermissionsForObject(ids.getDriveId(), ids.getId());
      } catch (GraphException ignore) {
      }

      boolean isDeleted = false;

      JsonArray collaborators = new JsonArray();
      for (Object p : body) {
        JsonObject permission = (JsonObject) p;
        if (permissionId.equals(permission.getString(Field.ID.getName()))) {
          if (permission.containsKey("grantedToV2")) {
            this.deletePermission(id, permissionId);
            isDeleted = true;
            break;
          }
          if (permission.containsKey("grantedToIdentitiesV2")) {
            JsonArray users = permission.getJsonArray("grantedToIdentitiesV2");
            for (Object u : users) {
              JsonObject userObj = (JsonObject) u;
              if (userObj.containsKey(Field.USER.getName())
                  && userObj
                      .getJsonObject(Field.USER.getName())
                      .containsKey(Field.EMAIL.getName())) {
                String email =
                    userObj.getJsonObject(Field.USER.getName()).getString(Field.EMAIL.getName());
                if (!objectToRemove.containsKey(Field.EMAIL.getName())
                    || !email.equals(objectToRemove.getString(Field.EMAIL.getName()))) {
                  JsonObject emailObj = new JsonObject().put(Field.EMAIL.getName(), email);
                  collaborators.add(emailObj);
                }
              }
            }
          }
          String role = permission.getJsonArray(Field.ROLES.getName()).getString(0);
          this.deletePermission(id, permissionId);
          if (!collaborators.isEmpty()) {
            this.inviteUser(id, role, collaborators, false);
          }
          isDeleted = true;
          break;
        }
      }
      if (!isDeleted) {
        throw new Exception("Cannot remove the collaborator");
      }
    } else {
      this.deletePermission(id, permissionId);
    }
  }

  public Permissions getPermissions(String driveId, JsonObject object) throws GraphException {
    checkRefreshToken();
    String url = GRAPH_URL + "/drive" + (driveId == null ? "" : "s/" + driveId) + "/items/"
        + object.getString(Field.ID.getName()) + "/permissions";

    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getFolderContent")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetObjectPermissionsException(response);
    }

    return new Permissions(new JsonObject(response.getBody()).getJsonArray("value"));
  }

  public Permissions getPermissions(String objectId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(objectId);

    // String url = GRAPH_URL + "/drive" + (ids.getDriveId() == null ? "" : "s/" + ids.getDriveId
    // ()) + "/items/" + ids.getId() + "/permissions";
    String url = ids.getItemUrl(homeDrive) + "/permissions";

    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getFolderContent")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetObjectPermissionsException(response);
    }

    return new Permissions(new JsonObject(response.getBody()).getJsonArray("value"));
  }

  public RootInfo getRootInfo() throws GraphException {
    checkRefreshToken();
    HttpResponse<String> response = AWSXRayUnirest.get(
            GRAPH_URL + homeDrive + "/drive/root", "oneDrive.getRoot")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetRootInfoException(response);
    }

    return new RootInfo(new JsonObject(response.getBody()));
  }

  public void move(String objectId, String parentId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(objectId);

    GraphId parentIds =
        GraphUtils.isRootFolder(parentId) ? new GraphId(getRootInfo()) : new GraphId(parentId);

    if (storageType.equals(StorageType.ONEDRIVE)
        && !ids.getDriveId().equals(parentIds.getDriveId())) {
      throw new MoveException(new Exception("moveBetweenDrives"));
    }

    String field = GraphUtils.isRootFolder(parentId) ? Field.PATH.getName() : Field.ID.getName();
    String value = GraphUtils.isRootFolder(parentId) ? "/drive/root" : parentIds.getId();
    JsonObject parentReference = new JsonObject().put(field, value);
    if (parentIds.getDriveId() != null) {
      parentReference.put(Field.DRIVE_ID.getName(), parentIds.getDriveId());
    }

    HttpResponse<String> response = AWSXRayUnirest.patch(ids.getItemUrl(homeDrive), "oneDrive.move")
        .header("Authorization", "Bearer " + accessToken)
        .header("Prefer", "respond-async")
        .header("Content-Type", "application/json")
        .body(new JsonObject()
            .put(Field.PARENT_REFERENCE.getName(), parentReference)
            .toString())
        .asString();

    if (response.getStatus() != HttpStatus.OK && response.getStatus() != HttpStatus.ACCEPTED) {
      throw new MoveException(response);
    }
  }

  public void rename(String objectId, String name) throws GraphException {
    checkRefreshToken();
    GraphUtils.checkName(name);

    GraphId ids = new GraphId(objectId);

    HttpResponse<String> response = AWSXRayUnirest.patch(
            ids.getItemUrl(homeDrive), "oneDrive.rename")
        .header("Authorization", "Bearer " + accessToken)
        .header("Content-Type", "application/json")
        .body(new JsonObject().put(Field.NAME.getName(), name).toString())
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new RenameException(response);
    }
  }

  public DriveInfo getMyDrive() throws GraphException {
    checkRefreshToken();
    HttpResponse<String> myDrives = AWSXRayUnirest.get(
            GRAPH_URL + "/me/drives", "oneDrive.getMyDrives")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (myDrives.getStatus() == HttpStatus.OK) {
      return new DriveInfo(
          new JsonObject(myDrives.getBody()).getJsonArray("value").getJsonObject(0));
    } else {
      throw new GetMyDriveException(myDrives);
    }
  }

  public void delete(String objectId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(objectId);

    HttpResponse<String> response = AWSXRayUnirest.delete(
            ids.getItemUrl(homeDrive), "oneDrive.delete")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.NO_CONTENT) {
      throw new DeleteException(response);
    }
  }

  public CloneObjectResult cloneObject(String objectId, String name) throws GraphException {
    checkRefreshToken();
    GraphUtils.checkName(name);

    GraphId ids = new GraphId(objectId);

    ObjectInfo objectInfo = getBaseObjectInfo(true, objectId, null);

    String parentId = objectInfo.getParent();

    if (!GraphUtils.isRootFolder(parentId)) {
      // remove drive part
      parentId = parentId.substring(parentId.lastIndexOf(":") + 1);
    }

    String field = GraphUtils.isRootFolder(parentId) ? Field.PATH.getName() : Field.ID.getName();
    String value = GraphUtils.isRootFolder(parentId) ? "/drive/root" : parentId;
    JsonObject parentReference = new JsonObject().put(field, value);
    parentReference.put(Field.DRIVE_ID.getName(), ids.getDriveId());

    String url = ids.getItemUrl(homeDrive) + "/copy";

    HttpResponse<String> copyResponse = AWSXRayUnirest.post(url, "oneDrive.clone")
        .header("Authorization", "Bearer " + accessToken)
        .header("Prefer", "respond-async")
        .header("Content-Type", "application/json")
        .body(new JsonObject().put(Field.NAME.getName(), name).toString())
        .asString();

    if (copyResponse.getStatus() != HttpStatus.OK
        && copyResponse.getStatus() != HttpStatus.ACCEPTED) {
      throw new CloneObjectException(copyResponse);
    }

    String locationHeader = copyResponse.getHeaders().get("Location").get(0);

    long start = System.currentTimeMillis();

    // copying is done
    if (locationHeader.contains("/monitor/done/")) {
      String newObjectId = locationHeader.substring(locationHeader.lastIndexOf("/") + 1);
      if (newObjectId.contains("?")) {
        newObjectId = newObjectId.substring(0, newObjectId.indexOf("?"));
      }
      return new CloneObjectResult(
          (ids.getDriveId() != null ? ids.getDriveId() + ":" : "") + newObjectId,
          ids.getDriveId(),
          objectInfo.getParent());
    }

    // copying may not be done yet
    else {
      copyResponse = AWSXRayUnirest.get(locationHeader, "graphApi.checkLocation")
          .header("Prefer", "respond-async")
          .header("Content-Type", "application/json")
          .asString();

      while (copyResponse.getStatus() == HttpStatus.ACCEPTED) {
        // check for timeout 20 sec
        if (System.currentTimeMillis() - start > 20000) {
          throw new CloneObjectException(new Exception("copyTimeoutExpired"));
        }

        copyResponse = AWSXRayUnirest.get(locationHeader, "graphApi.checkLocation")
            .header("Prefer", "respond-async")
            .header("Content-Type", "application/json")
            .asString();

        try {
          Thread.sleep(500);
        } catch (Exception e) {
          throw new CloneObjectException(new Exception("timeoutException"));
        }
      }

      if (copyResponse.getStatus() != HttpStatus.OK) {
        throw new CloneObjectException(copyResponse);
      }

      return new CloneObjectResult(
          (ids.getDriveId() != null ? ids.getDriveId() + ":" : "")
              + (new JsonObject(copyResponse.getBody()).getString(Field.RESOURCE_ID.getName())),
          ids.getDriveId(),
          objectInfo.getParent());
    }
  }

  public VersionList getVersions(String fileId) throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(fileId);

    String url = ids.getItemUrl(homeDrive) + "/versions";

    HttpResponse<String> response = AWSXRayUnirest.get(url, "oneDrive.getVersions")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetVersionsException(response);
    }

    return new VersionList(
        new JsonObject(response.getBody()), storageType, externalId, ids.getDriveId(), ids.getId());
  }

  public PromoteVersionResult promoteVersion(String fileId, String versionId)
      throws GraphException {
    checkRefreshToken();
    GraphId ids = new GraphId(fileId);

    String promoteUrl = ids.getItemUrl(homeDrive) + "/versions/" + versionId + "/restoreVersion";

    // promote versionId
    HttpResponse<String> response = AWSXRayUnirest.post(promoteUrl, "oneDrive.promoteVersion")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.NO_CONTENT) {
      throw new PromoteVersionException(response);
    }

    try {
      return new PromoteVersionResult(
          ids.getId(), getBaseObjectInfo(true, fileId, null).getVersionId());
    } catch (Exception exception) {
      throw new PromoteVersionException(response);
    }
  }

  public String getLatestVersionId(String fileId) throws GraphException {
    checkRefreshToken();
    return getVersions(fileId).getLatestVersionId();
  }

  public JsonArray getPermissionsForObject(String driveId, String objectId) throws GraphException {
    checkRefreshToken();

    GraphId ids = new GraphId(driveId, objectId);
    String url = ids.getItemUrl(homeDrive) + "/permissions";

    HttpResponse<String> permissions = AWSXRayUnirest.get(url, "oneDrive.getPermissions")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (permissions.getStatus() != HttpStatus.OK) {
      throw new GetObjectPermissionsException(permissions);
    }

    return new JsonObject(permissions.getBody()).getJsonArray("value");
  }

  public void restoreObject(String id) throws GraphException {
    checkRefreshToken();

    GraphId ids = new GraphId(id);
    String url = ids.getItemUrl(homeDrive) + "/restore";

    HttpResponse<String> response = AWSXRayUnirest.post(url, "oneDrive.restore")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.NO_CONTENT) {
      throw new RestoreObjectException(response);
    }
  }

  public void checkFile(String fileId) throws GraphException {
    checkRefreshToken();

    GraphId ids = new GraphId(fileId);

    HttpResponse<String> response = AWSXRayUnirest.get(
            ids.getItemUrl(homeDrive), "oneDrive.getInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new CheckFileException(response);
    }
  }

  public SiteInfo getSiteInfo(final String siteId) throws GraphException {
    checkRefreshToken();

    HttpResponse<String> siteInfo = AWSXRayUnirest.get(
            GRAPH_URL + "/sites/" + siteId, "oneDrive.getSiteInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (siteInfo.getStatus() == HttpStatus.OK) {
      return new SiteInfo(new JsonObject(siteInfo.getBody()));
    } else {
      throw new GetSiteInfoException(siteInfo);
    }
  }

  private void updatePermission(String objectId, String permissionId, JsonObject requestBody)
      throws ShareObjectException {
    GraphId ids = new GraphId(objectId);
    String url = ids.getItemUrl(homeDrive).concat("/permissions/").concat(permissionId);

    HttpResponse<String> response = AWSXRayUnirest.patch(url, "oneDrive.updatePermission")
        .header("Authorization", "Bearer " + accessToken)
        .header("Content-Type", "application/json")
        .body(requestBody.encode())
        .asString();
    if (response.getStatus() != HttpStatus.OK) {
      throw new ShareObjectException(response);
    }
  }

  private void inviteUser(String objectId, String role, JsonArray emails, boolean sendInvitation)
      throws ShareObjectException {
    GraphId ids = new GraphId(objectId);

    JsonObject requestBody = new JsonObject()
        // requireSignIn cannot be false for folders
        .put("requireSignIn", true)
        .put("sendInvitation", sendInvitation)
        .put(Field.ROLES.getName(), new JsonArray().add(role))
        .put("recipients", emails);

    HttpResponse<String> response = AWSXRayUnirest.post(
            ids.getItemUrl(homeDrive).concat("/invite"), "oneDrive.share")
        .header("Authorization", "Bearer " + accessToken)
        .header("Content-Type", "application/json")
        .body(requestBody.toString())
        .asString();
    if (response.getStatus() != HttpStatus.OK) {
      throw new ShareObjectException(response);
    }
  }

  private void deletePermission(String objectId, String permissionId)
      throws RemoveObjectPermissionException {
    GraphId ids = new GraphId(objectId);

    HttpResponse<String> response = AWSXRayUnirest.delete(
            ids.getItemUrl(homeDrive).concat("/permissions/").concat(permissionId),
            "oneDrive.deletePermission")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (response.getStatus() != HttpStatus.NO_CONTENT) {
      throw new RemoveObjectPermissionException(response);
    }
  }

  private String getVersionContentUrl(GraphId ids, String versionId) {
    String url;
    try {
      url = ids.getItemUrl(homeDrive) + "/versions/"
          + URLEncoder.encode(versionId, StandardCharsets.UTF_8) + "/content";
    } catch (Exception exception) {
      url = ids.getItemUrl(homeDrive) + "/versions/" + versionId + "/content";
    }
    return url;
  }

  private String getErrorMessageFromResponse(JsonObject responseBody) {
    String nativeError = "Unauthorized";
    if (responseBody.containsKey("error_description")) {
      String errorDescription = responseBody.getString("error_description");
      String[] errors = errorDescription.split(":");
      if (errors.length > 1) {
        nativeError = errors[1].substring(0, errors[1].indexOf(".")).trim();
      } else {
        nativeError = errors[0].trim();
      }
    } else if (responseBody.containsKey("error")) {
      nativeError = responseBody.getString("error");
    }
    return nativeError;
  }

  // getters
  public String getUserId() {
    return userId;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public String getExternalId() {
    return externalId;
  }

  public boolean isAccountThumbnailDisabled() {
    return isAccountThumbnailDisabled;
  }

  public String getEmail() {
    return email;
  }

  public String getAccessToken() {
    return accessToken;
  }

  public String getSiteUrl() {
    return siteUrl;
  }

  public String getDisplayName() {
    return displayName;
  }
}
