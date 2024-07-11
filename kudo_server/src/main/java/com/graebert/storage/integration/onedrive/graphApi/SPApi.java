package com.graebert.storage.integration.onedrive.graphApi;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.Drive;
import com.graebert.storage.integration.onedrive.Site;
import com.graebert.storage.integration.onedrive.graphApi.entity.GraphId;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetDriveInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetObjectInfoException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetSiteDrivesException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetSiteException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GetSitesException;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.integration.onedrive.graphApi.util.GraphUtils;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;

public class SPApi extends GraphApi {
  private static String HOME_DRIVE;
  private static String CLIENT_ID;
  private static String CLIENT_SECRET;
  private static String REDIRECT_URI;
  private static String FLUORINE_SECRET;
  private static ServerConfig CONFIG;

  public static void Initialize(
      String HOME_DRIVE,
      String CLIENT_ID,
      String CLIENT_SECRET,
      String REDIRECT_URI,
      String FLUORINE_SECRET,
      ServerConfig CONFIG) {
    SPApi.HOME_DRIVE = HOME_DRIVE;
    SPApi.CLIENT_ID = CLIENT_ID;
    SPApi.CLIENT_SECRET = CLIENT_SECRET;
    SPApi.REDIRECT_URI = REDIRECT_URI;
    SPApi.FLUORINE_SECRET = FLUORINE_SECRET;
    SPApi.CONFIG = CONFIG;
  }

  private SPApi(String userId, String externalId) throws GraphException {
    super(
        SPApi.HOME_DRIVE,
        SPApi.CLIENT_ID,
        SPApi.CLIENT_SECRET,
        SPApi.REDIRECT_URI,
        SPApi.FLUORINE_SECRET,
        SPApi.CONFIG,
        StorageType.SHAREPOINT,
        userId,
        externalId);
  }

  private SPApi(Item user) throws GraphException {
    super(
        SPApi.HOME_DRIVE,
        SPApi.CLIENT_ID,
        SPApi.CLIENT_SECRET,
        SPApi.REDIRECT_URI,
        SPApi.FLUORINE_SECRET,
        SPApi.CONFIG,
        StorageType.SHAREPOINT,
        user);
  }

  private SPApi(String userId, String authCode, String sessionId) throws GraphException {
    super(
        SPApi.HOME_DRIVE,
        SPApi.CLIENT_ID,
        SPApi.CLIENT_SECRET,
        SPApi.REDIRECT_URI,
        SPApi.FLUORINE_SECRET,
        SPApi.CONFIG,
        StorageType.SHAREPOINT,
        authCode,
        userId,
        sessionId,
        "SP_");
  }

  public static SPApi connectAccount(String userId, String authCode, String sessionId)
      throws GraphException {
    return new SPApi(userId, authCode, sessionId);
  }

  public static <T> SPApi connect(Message<T> message) throws GraphException {
    ParsedMessage parsedMessage = MessageUtils.parse(message);

    return connectByUserId(
        parsedMessage.getJsonObject().getString(Field.USER_ID.getName()),
        parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName()));
  }

  public static SPApi connectByUserId(String userId, String externalId) throws GraphException {
    return new SPApi(userId, externalId);
  }

  public static SPApi connectByItem(Item user) throws GraphException {
    return new SPApi(user);
  }

  public List<Site> getAllSites() throws GraphException {
    HttpResponse<String> sitesSearchResult = AWSXRayUnirest.get(
            GRAPH_URL + "/sites?search=*", "sharePont.getSites")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (sitesSearchResult.getStatus() == HttpStatus.OK) {
      return new JsonObject(sitesSearchResult.getBody())
          .getJsonArray("value").stream()
              .map((site) -> new Site((JsonObject) site, storageType, externalId))
              .collect(Collectors.toList());
    } else {
      throw new GetSitesException(sitesSearchResult);
    }
  }

  public JsonArray getAllSitesJson() throws GraphException {
    HttpResponse<String> sitesSearchResult = AWSXRayUnirest.get(
            GRAPH_URL + "/sites?search=*", "sharePont.getSites")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (sitesSearchResult.getStatus() == HttpStatus.OK) {
      return new JsonObject(sitesSearchResult.getBody()).getJsonArray("value");
    } else {
      throw new GetSitesException(sitesSearchResult);
    }
  }

  public Site getSite(String siteId) throws GraphException {
    HttpResponse<String> siteInfo = AWSXRayUnirest.get(
            GRAPH_URL + "/sites/" + Site.getRawIdFromFinal(siteId), "SharePoint.getSiteInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (siteInfo.getStatus() == HttpStatus.OK) {
      return new Site(new JsonObject(siteInfo.getBody()), storageType, externalId);
    } else {
      throw new GetSiteException(siteInfo);
    }
  }

  public List<Drive> getSiteDrives(String siteId) throws GraphException {
    HttpResponse<String> siteDrives = AWSXRayUnirest.get(
            GRAPH_URL + "/sites/" + Site.getRawIdFromFinal(siteId) + "/drives",
            "SharePoint.getSiteDrives")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (siteDrives.getStatus() == HttpStatus.OK) {
      return new JsonObject(siteDrives.getBody())
          .getJsonArray("value").stream()
              .map(drive -> new Drive((JsonObject) drive, storageType, externalId, siteId))
              .collect(Collectors.toList());
    } else {
      throw new GetSiteDrivesException(siteDrives);
    }
  }

  public JsonArray getSiteDrivesJson(String siteId) throws GraphException {
    HttpResponse<String> siteDrives = AWSXRayUnirest.get(
            GRAPH_URL + "/sites/" + Site.getRawIdFromFinal(siteId) + "/drives",
            "SharePoint.getSiteDrives")
        .header("Authorization", "Bearer " + accessToken)
        .asString();
    if (siteDrives.getStatus() == HttpStatus.OK) {
      return new JsonObject(siteDrives.getBody()).getJsonArray("value");
    } else {
      throw new GetSiteDrivesException(siteDrives);
    }
  }

  public JsonObject globalSearch(List<String> driveIds, String query, boolean isAdmin)
      throws GraphException {
    JsonArray filesJson = new JsonArray(), foldersJson = new JsonArray();
    HttpResponse<String> response;
    JsonArray value = new JsonArray();

    for (String driveId : driveIds) {
      String nextLink = null;

      do {
        try {
          response = AWSXRayUnirest.get(
                  nextLink == null
                      ? GRAPH_URL + "/drives/" + driveId + "/root/search(q='"
                          + Utils.urlencode(query) + "')"
                      : nextLink,
                  "SharePoint.search")
              .header("Authorization", "Bearer " + accessToken)
              .asString();
        } catch (Exception e) {
          nextLink = null;
          response = null;
          e.printStackTrace();
        }
        if (response != null && response.getStatus() == HttpStatus.OK) {
          JsonObject body = new JsonObject(response.getBody());
          nextLink = body.getString("@odata.nextLink");
          value.addAll(body.getJsonArray("value"));
        }
      } while (nextLink != null);
    }

    value = new JsonArray(value.stream()
        .filter(Utils.distinctByKey((o) -> ((JsonObject) o).getString(Field.ID.getName())))
        .collect(Collectors.toList()));

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

          filesJson.add(GraphUtils.getSPFileJson(
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
                  false,
                  false)
              .toJson());
        } else if (obj.containsKey(Field.FOLDER.getName())
            || (obj.containsKey(Field.REMOTE_ID.getName())
                && obj.getJsonObject(Field.REMOTE_ID.getName())
                    .containsKey(Field.FOLDER.getName()))) {
          foldersJson.add(GraphUtils.getSPFileJson(
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
                  false,
                  false)
              .toJson());
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    return new JsonObject()
        .put(Field.STORAGE_TYPE.getName(), storageType.toString())
        .put(Field.EXTERNAL_ID.getName(), externalId)
        .put(Field.NAME.getName(), email)
        .put(Field.FILES.getName(), filesJson)
        .put(Field.FOLDERS.getName(), foldersJson);
  }

  public ObjectInfo getObjectInfo(
      boolean isFile,
      String id,
      boolean full,
      boolean addShare,
      boolean isAdmin,
      boolean force,
      boolean canCreateThumbnail,
      boolean escapeRoot)
      throws GraphException {
    checkRefreshToken();

    GraphId ids = new GraphId(id);

    HttpResponse<String> response = AWSXRayUnirest.get(
            escapeRoot ? ids.getItemUrlEscapeRoot(homeDrive) : ids.getItemUrl(homeDrive),
            "sharePoint.getInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (response.getStatus() != HttpStatus.OK) {
      throw new GetObjectInfoException(response);
    }

    JsonObject result = new JsonObject(response.getBody());
    result.put(Field.SHARED_LINKS_ID.getName(), id);

    return GraphUtils.getSPFileJson(
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
        force,
        canCreateThumbnail);
  }

  @Override
  public ObjectInfo getFileInfoByToken(String specificDriveId, String id) throws GraphException {
    checkRefreshToken();

    GraphId ids;
    if (specificDriveId != null) {
      ids = new GraphId(specificDriveId, id);
    } else {
      ids = new GraphId(id);
    }

    HttpResponse<String> infoResponse = AWSXRayUnirest.get(
            ids.getItemUrl(homeDrive), "sharePoint.getInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (infoResponse.getStatus() != HttpStatus.OK) {
      throw new GetObjectInfoException(infoResponse);
    }

    JsonObject result = new JsonObject(infoResponse.getBody());
    result.put(Field.SHARED_LINKS_ID.getName(), id);

    return GraphUtils.getSPFileJson(
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
        false,
        false);
  }

  @Override
  public ObjectInfo getBaseObjectInfo(boolean isFile, String objectId, Message message)
      throws GraphException {
    return getObjectInfo(isFile, objectId, false, false, false, false, false, false);
  }

  @Override
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
      } else if (Site.isSiteId(id)) {
        Site site = getSite(id);
        if (Site.isCollectionId(id)) {
          return site.toObjectInfoWithURL();
        } else if (Site.isSpecificSiteId(id)) {
          return site.toObjectInfo();
        }
      }
    }

    return getObjectInfo(isFile, id, full, addShare, isAdmin, force, canCreateThumbnail, true);
  }

  public JsonObject getDriveInfo(String driveId) throws GetDriveInfoException {
    HttpResponse<String> driveInfo = AWSXRayUnirest.get(
            GRAPH_URL + "/drives/" + driveId + "?select=name,id,sharepointIds",
            "SharePoint.getDriveInfo")
        .header("Authorization", "Bearer " + accessToken)
        .asString();

    if (!driveInfo.isSuccess()) {
      throw new GetDriveInfoException(driveInfo);
    }

    return new JsonObject(driveInfo.getBody());
  }
}
