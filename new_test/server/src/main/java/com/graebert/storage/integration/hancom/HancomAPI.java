package com.graebert.storage.integration.hancom;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import kong.unirest.ContentType;
import kong.unirest.HttpResponse;

public class HancomAPI {

  public static final String ROOT_FOLDER_ID = "m:0";

  private static final String sessionHeaderName = "x-zeropc-mapi-sid";
  private static final String contentType =
      ContentType.APPLICATION_FORM_URLENCODED + "; charset=" + StandardCharsets.UTF_8.name();
  private static final String expirationCode = "02.004";
  private final HancomUnirest instance;
  private String baseURL = "https://api.malangmalang.com/space/mobile";
  private boolean isAccountThumbnailDisabled;

  public HancomAPI(String url, JsonObject proxy) {
    instance = new HancomUnirest(false, proxy);
    if (Utils.isStringNotNullOrEmpty(url)) {
      baseURL = url;
    }
  }

  public void shutdown() {
    instance.shutDown();
  }

  private JsonObject sendGetRequest(final String apiUrl, final String sessionId)
      throws HancomException {
    HttpResponse<String> response =
        instance.get(baseURL + apiUrl).header(sessionHeaderName, sessionId).asString();
    String stringResponse = response.getBody();
    JsonObject responseObj = new JsonObject(stringResponse);
    this.checkResponseCode(
        responseObj.getString(Field.CODE.getName()), responseObj.getString(Field.REASON.getName()));
    return responseObj;
  }

  private JsonObject sendPostRequest(final String apiUrl, final String sessionId, final String data)
      throws HancomException {
    HttpResponse<String> response = instance
        .post(baseURL + apiUrl)
        .header(sessionHeaderName, sessionId)
        .header("Content-Type", contentType)
        .body(data)
        .asString();
    String stringResponse = response.getBody();
    JsonObject responseObj = new JsonObject(stringResponse);
    this.checkResponseCode(
        responseObj.getString(Field.CODE.getName()), responseObj.getString(Field.REASON.getName()));
    return responseObj;
  }

  private byte[] getBinaryData(final String apiUrl, final String sessionId) {
    HttpResponse<byte[]> response =
        instance.get(baseURL + apiUrl).header(sessionHeaderName, sessionId).asBytes();
    return response.getBody();
  }

  private JsonObject sendUploadRequest(
      final String apiUrl, final String sessionId, String objectName, final byte[] data)
      throws HancomException {
    HttpResponse<String> response = instance
        .post(baseURL + apiUrl)
        .header(sessionHeaderName, sessionId)
        .field(Field.FILE.getName(), new ByteArrayInputStream(data), objectName)
        .asString();
    String stringResponse = response.getBody();
    JsonObject responseObj = new JsonObject(stringResponse);
    this.checkResponseCode(
        responseObj.getString(Field.CODE.getName()), responseObj.getString(Field.REASON.getName()));
    return responseObj;
  }

  private void checkResponseCode(String responseCode, String reason) throws HancomException {
    if (!responseCode.equals(HancomException.ErrorCode.OK.getResponseCode())) {
      throw new HancomException(responseCode, reason);
    }
  }

  public JsonObject listFolderContent(final String sessionId, final String folderId)
      throws HancomException {
    return sendGetRequest("/list/ALL?folderId=" + folderId, sessionId);
  }

  public JsonObject listTrashContent(final String sessionId) throws HancomException {
    return sendGetRequest("/recyclebin/list", sessionId);
  }

  /**
   * Shared by me
   *
   * @param sessionId
   * @return
   * @throws HancomException
   */
  public JsonObject listSharedObjectsMy(final String sessionId) throws HancomException {
    return sendGetRequest("/shares/myShares", sessionId);
  }

  /**
   * Shared with me
   *
   * @param sessionId
   * @return
   * @throws HancomException
   */
  public JsonObject listSharedObjects(final String sessionId) throws HancomException {
    // return sendGetRequest("/shares/userShares", sessionId);
    return sendGetRequest(
        "/shares/list?folderId=root&count=50&listType=TAKE&itemType=ALL&orderBy=FILE_NAME&sortOrder=ASC",
        sessionId);
  }

  public void renameObject(final String sessionId, final String objectId, final String newName)
      throws HancomException {
    String renamed = newName;
    try {
      renamed = URLEncoder.encode(newName, StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
    }
    String requestData = "currentFileId=" + objectId + "&destinationName=" + renamed;
    sendPostRequest("/rename", sessionId, requestData);
  }

  public JsonObject getUserInfo(final String sessionId) throws HancomException {
    return sendGetRequest("/getUserInfo", sessionId);
  }

  public JsonObject createFolder(
      final String sessionId, final String parentFolderId, final String folderName)
      throws HancomException {
    String requestData = "parentFolderId=" + parentFolderId + "&folderName=" + folderName;
    return sendPostRequest("/createFolder", sessionId, requestData);
  }

  public JsonObject getObjectInfo(final String sessionId, final String objectId)
      throws HancomException {
    return sendGetRequest("/iteminfo?fileId=" + Utils.encodeValue(objectId), sessionId);
  }

  public JsonObject getObjectInfoByPath(final String sessionId, final String path)
      throws HancomException {
    return sendGetRequest("/pathinfo?path=" + Utils.encodeValue(path), sessionId);
  }

  public JsonObject getSharedObjectInfo(final String sessionId, final String objectId)
      throws HancomException {
    return sendGetRequest("/shares/iteminfo?fileId=" + objectId, sessionId);
  }

  public JsonObject getListOfCollaborators(final String sessionId, final String objectId)
      throws HancomException {
    return sendGetRequest("/shares/findFileRecipient?fileId=" + objectId, sessionId);
  }

  public JsonObject getUserPermissions(
      final String sessionId, final String objectId, final String userId) throws HancomException {
    return sendGetRequest(
        "/shares/getPermission?fileId=" + objectId + "&userId=" + userId, sessionId);
  }

  public JsonObject uploadFile(
      final String sessionId,
      final String folderId,
      final String fileId,
      final String name,
      final byte[] data)
      throws HancomException {
    if (Utils.isStringNotNullOrEmpty(fileId)) {
      return sendUploadRequest(
          "/uploadFid?fid=" + folderId + "&fileId=" + fileId + "&name=" + name,
          sessionId,
          name,
          data);
    } else {
      return sendUploadRequest(
          "/uploadFid?fid=" + folderId + "&name=" + name, sessionId, name, data);
    }
  }

  public JsonObject uploadSharedFile(
      final String sessionId,
      final String fileId,
      final String path,
      final String name,
      final byte[] data)
      throws HancomException {
    return sendUploadRequest(
        "/upload?path=" + path + "&name=" + name + "&fileId=" + fileId, sessionId, name, data);
  }

  public JsonObject deleteObject(final String sessionId, final String objectId)
      throws HancomException {
    String requestData = "sources=" + new JsonArray().add(objectId).encode();
    return sendPostRequest("/delete", sessionId, requestData);
  }

  public JsonObject eraseObject(final String sessionId, final String objectId)
      throws HancomException {
    String requestData = "sources=" + new JsonArray().add(objectId).encode() + "&recycle=false";
    return sendPostRequest("/delete", sessionId, requestData);
  }

  public JsonObject restoreObject(final String sessionId, final String objectId)
      throws HancomException {
    return sendGetRequest("/recyclebin/restore?fileId=" + objectId, sessionId);
  }

  public JsonObject moveObject(
      final String sessionId, final String objectId, final String destination)
      throws HancomException {
    String requestData =
        "sources=" + new JsonArray().add(objectId).encode() + "&destination=" + destination;
    return sendPostRequest("/move", sessionId, requestData);
  }

  public byte[] getFileContent(final String sessionId, final String fileId) {
    return getBinaryData("/content?fileId=" + fileId, sessionId);
  }

  public byte[] getFileVersion(
      final String sessionId, final String fileId, final String versionId) {
    return getBinaryData("/fileByVersion?fileId=" + fileId + "&version=" + versionId, sessionId);
  }

  public JsonObject shareObject(
      final String sessionId,
      final String objectId,
      final String email,
      final String hancomId,
      String permissionsSet)
      throws HancomException {
    String requestData = "fileId=" + objectId;
    if (Utils.isStringNotNullOrEmpty(hancomId)) {
      requestData += "&userIds=" + new JsonArray().add(hancomId).encode();
    } else {
      requestData += "&emails=" + new JsonArray().add(email).encode();
    }
    requestData += permissionsSet;
    return sendPostRequest("/shares/createShare", sessionId, requestData);
  }

  public JsonObject findUserByEmail(final String sessionId, final String email)
      throws HancomException {
    return sendGetRequest("/user/search?email=" + email, sessionId);
  }

  public JsonObject removeShare(
      final String sessionId, final String objectId, final String hancomId) throws HancomException {
    String requestData = "fileId=" + objectId;
    if (Utils.isStringNotNullOrEmpty(hancomId)) {
      requestData += "&userId=" + hancomId;
    }
    return sendPostRequest("/shares/removeShare", sessionId, requestData);
  }

  public JsonObject removeInvite(final String sessionId, final String objectId, final String email)
      throws HancomException {
    String requestData = "fileId=" + objectId;
    if (Utils.isStringNotNullOrEmpty(email)) {
      requestData += "&email=" + email;
    }
    return sendPostRequest("/shares/removeInvite", sessionId, requestData);
  }

  public JsonObject removeOwnShare(final String sessionId, final String objectId)
      throws HancomException {
    String requestData = "fileId=" + objectId;
    return sendPostRequest("/shares/removeOwnShare", sessionId, requestData);
  }

  public JsonObject updateShare(
      final String sessionId, final String objectId, final String hancomId, String permissionsSet)
      throws HancomException {
    String requestData = "fileId=" + objectId;
    if (Utils.isStringNotNullOrEmpty(hancomId)) {
      requestData += "&userId=" + hancomId;
    }
    requestData += permissionsSet;
    return sendPostRequest("/shares/updateShare", sessionId, requestData);
  }

  public JsonObject updateInvite(
      final String sessionId, final String objectId, final String email, String permissionsSet)
      throws HancomException {
    String requestData = "fileId=" + objectId;
    if (Utils.isStringNotNullOrEmpty(email)) {
      requestData += "&email=" + email;
    }
    requestData += permissionsSet;
    return sendPostRequest("/shares/updateInvite", sessionId, requestData);
  }

  public JsonObject getVersionsList(final String sessionId, final String objectId)
      throws HancomException {
    return sendGetRequest("/versions/list?fileId=" + objectId, sessionId);
  }

  public JsonObject search(final String sessionId, final String query) throws HancomException {
    return sendGetRequest("/search/ALL?phrase=" + query + "&nocache=true", sessionId);
  }

  public JsonObject restoreVersion(
      final String sessionId, final String fileId, final int versionId, final String tag)
      throws HancomException {
    String requestData = "fileId=" + fileId + "&version=" + versionId + "&tag=" + tag;
    return sendPostRequest("/versions/restore", sessionId, requestData);
  }

  public JsonObject deleteVersion(final String sessionId, final String fileId, final int versionId)
      throws HancomException {
    String requestData = "fileId=" + fileId + "&version=" + versionId;
    return sendPostRequest("/deleteFileByVersion", sessionId, requestData);
  }

  public boolean isAccountThumbnailDisabled() {
    return isAccountThumbnailDisabled;
  }

  public void updateAccountThumbnailAccess(boolean isAccountThumbnailDisabled) {
    this.isAccountThumbnailDisabled = isAccountThumbnailDisabled;
  }
}
