package com.graebert.storage.integration.nextcloud;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.graebert.storage.integration.webdav.SardineFactory;
import com.graebert.storage.integration.webdav.SardineImpl2;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.integration.xray.AWSXrayNextCloudSardine;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.HttpStatusCodes;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;
import kong.unirest.HttpResponse;
import org.apache.commons.io.IOUtils;

/**
 * The type Nextcloud api.
 */
public class NextcloudApi {
  private final String url;
  private final String username;
  private final String password;

  private Sardine sardine = null;
  private final Set<QName> props = new HashSet<>();

  /**
   * The enum Nextcloud permission.
   */
  public enum NextcloudPermission {
    // val  binary  meaning
    //
    // 1    00001   read
    // 2    00010   update
    // 4    00100   create
    // 8    01000   delete
    // 16   10000   share
    // 19   10011   read update share
    // 31   11111   all

    /**
     * Edit nextcloud permission.
     */
    EDIT,
    /**
     * View nextcloud permission.
     */
    VIEW;

    /**
     * Gets formatted.
     *
     * @param isFile the is file
     * @return the formatted
     */
    public int getFormatted(boolean isFile) {
      if (this == EDIT) {
        if (isFile) {
          return 19;
        }
        return 31;
      }
      return 1;
    }

    /**
     * Parse formatted nextcloud permission.
     *
     * @param permission the permission
     * @return the nextcloud permission
     */
    public static NextcloudPermission parseFormatted(String permission) {
      if (permission.equals("1")) {
        return VIEW;
      } else {
        return EDIT;
      }
    }
  }

  /**
   * The type Nextcloud api exception.
   */
  public static class NextcloudApiException extends Exception {
    /**
     * The constant NO_SUCH_SERVER.
     */
    public static final String NO_SUCH_SERVER = "NO_SUCH_SERVER";
    /**
     * The constant AUTH_NOT_FINISHED.
     */
    public static final String AUTH_NOT_FINISHED = "AUTH_NOT_FINISHED";
    /**
     * The constant NO_SUCH_USER.
     */
    public static final String NO_SUCH_USER = "NO_SUCH_USER";
    /**
     * The constant COULD_NOT_DELETE_APP_PASSWORD.
     */
    public static final String COULD_NOT_DELETE_APP_PASSWORD = "COULD_NOT_DELETE_APP_PASSWORD";
    /**
     * The constant COULD_NOT_GET_SHARES.
     */
    public static final String COULD_NOT_GET_SHARES = "COULD_NOT_GET_SHARES";
    /**
     * The constant COULD_NOT_GET_OCS_CODE.
     */
    public static final String COULD_NOT_GET_OCS_CODE = "COULD_NOT_GET_OCS_CODE";
    /**
     * The constant COULD_NOT_GET_DATA_BODY.
     */
    public static final String COULD_NOT_GET_DATA_BODY = "COULD_NOT_GET_DATA_BODY";
    /**
     * The constant COULD_NOT_DELETE_SHARE.
     */
    public static final String COULD_NOT_DELETE_SHARE = "COULD_NOT_DELETE_SHARE";
    /**
     * The constant COULD_NOT_UPDATE_SHARE.
     */
    public static final String COULD_NOT_UPDATE_SHARE = "COULD_NOT_UPDATE_SHARE";
    /**
     * The constant COULD_NOT_ADD_SHARE.
     */
    public static final String COULD_NOT_ADD_SHARE = "COULD_NOT_ADD_SHARE";
    /**
     * The constant COULD_NOT_SEARCH.
     */
    public static final String COULD_NOT_SEARCH = "COULD_NOT_SEARCH";
    /**
     * The constant COULD_NOT_GET_VERSIONS.
     */
    public static final String COULD_NOT_GET_VERSIONS = "COULD_NOT_GET_VERSIONS";
    /**
     * The constant COULD_NOT_GET_DATA.
     */
    public static final String COULD_NOT_GET_DATA = "COULD_NOT_GET_DATA";
    /**
     * The constant COULD_NOT_PROMOTE_VERSION.
     */
    public static final String COULD_NOT_PROMOTE_VERSION = "COULD_NOT_PROMOTE_VERSION";
    /**
     * The constant COULD_NOT_GET_INFO.
     */
    public static final String COULD_NOT_GET_INFO = "COULD_NOT_GET_INFO";
    /**
     * The constant COULD_NOT_GET_FOLDER_CONTENT.
     */
    public static final String COULD_NOT_GET_FOLDER_CONTENT = "COULD_NOT_GET_FOLDER_CONTENT";
    /**
     * The constant NC_SERVER_ERROR.
     */
    public static final String NC_SERVER_ERROR = "NC_SERVER_ERROR";
    /**
     * The constant NC_NOT_AUTHORIZED.
     */
    public static final String NC_NOT_AUTHORIZED = "NC_NOT_AUTHORIZED";
    /**
     * The constant NC_NOT_FOUND.
     */
    public static final String NC_NOT_FOUND = "NC_NOT_FOUND";
    /**
     * The constant NC_UNKNOWN_ERROR.
     */
    public static final String NC_UNKNOWN_ERROR = "NC_UNKNOWN_ERROR";
    /**
     * The constant COULD_NOT_CLONE_OBJECT.
     */
    public static final String COULD_NOT_CLONE_OBJECT = "COULD_NOT_CLONE_OBJECT";
    /**
     * The constant COULD_NOT_DELETE_OBJECT.
     */
    public static final String COULD_NOT_DELETE_OBJECT = "COULD_NOT_DELETE_OBJECT";
    /**
     * The constant COULD_NOT_RENAME_OBJECT.
     */
    public static final String COULD_NOT_RENAME_OBJECT = "COULD_NOT_RENAME_OBJECT";
    /**
     * The constant COULD_NOT_MOVE_OBJECT.
     */
    public static final String COULD_NOT_MOVE_OBJECT = "COULD_NOT_MOVE_OBJECT";
    /**
     * The constant COULD_NOT_CREATE_FOLDER.
     */
    public static final String COULD_NOT_CREATE_FOLDER = "COULD_NOT_CREATE_FOLDER";
    /**
     * The constant COULD_NOT_UPLOAD_DATA.
     */
    public static final String COULD_NOT_UPLOAD_DATA = "COULD_NOT_UPLOAD_DATA";
    /**
     * The constant COULD_NOT_UPLOAD_VERSION_DATA.
     */
    public static final String COULD_NOT_UPLOAD_VERSION_DATA = "COULD_NOT_UPLOAD_VERSION_DATA";

    private HttpResponse<String> response = null;

    /**
     * Instantiates a new Nextcloud api exception.
     *
     * @param message the message
     */
    public NextcloudApiException(String message) {
      super(message);
    }

    /**
     * Instantiates a new Nextcloud api exception.
     *
     * @param message the message
     */
    public NextcloudApiException(String message, Throwable error) {
      super(message, error);
    }

    /**
     * Instantiates a new Nextcloud api exception.
     *
     * @param message  the message
     * @param response the response
     */
    public NextcloudApiException(String message, HttpResponse<String> response) {
      super(message);
      this.response = response;
    }

    /**
     * Gets response.
     *
     * @return the response
     */
    public HttpResponse<String> getResponse() {
      return response;
    }
  }

  /**
   * Instantiates a new Nextcloud api.
   *
   * @param url      the url
   * @param username the username
   * @param password the password
   */
  public NextcloudApi(String url, String username, String password) {
    this.url = url;
    this.username = username;
    this.password = password;
  }

  /**
   * Instantiates a new Nextcloud api.
   *
   * @param api the api
   */
  public NextcloudApi(JsonObject api) {
    this.url = api.getString(Field.URL.getName());
    this.username = api.getString(Field.EMAIL.getName());
    this.password = api.getString(Field.PASSWORD.getName());
  }

  /**
   * Gets login url.
   *
   * @param serverUrl the server url
   * @return the login url
   * @throws NextcloudApiException the nextcloud api exception
   */
  public static JsonObject getLoginUrl(String serverUrl) throws NextcloudApiException {
    if (serverUrl.endsWith("/")) {
      serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
    }

    if (!serverUrl.endsWith("/index.php/login/v2")) {
      serverUrl = serverUrl.concat("/index.php/login/v2");
    }

    HttpResponse<String> authUrlResponse = AWSXRayUnirest.post(serverUrl, "nextCloud.getAuthUrl")
        .header("User-Agent", "Ares Kudo")
        .asString();

    if (authUrlResponse.getStatus() == HttpStatusCodes.OK) {
      JsonObject authUrlResponseBody = new JsonObject(authUrlResponse.getBody());

      return new JsonObject()
          .put("pollUrl", authUrlResponseBody.getJsonObject("poll").getString("endpoint"))
          .put(
              "pollToken",
              authUrlResponseBody.getJsonObject("poll").getString(Field.TOKEN.getName()))
          .put("authUrl", authUrlResponseBody.getString("login"));
    } else {
      throw new NextcloudApiException(NextcloudApiException.NO_SUCH_SERVER, authUrlResponse);
    }
  }

  /**
   * Poll auth json object.
   *
   * @param pollUrl   the poll url
   * @param pollToken the poll token
   * @return the json object
   * @throws NextcloudApiException the nextcloud api exception
   */
  public static JsonObject pollAuth(String pollUrl, String pollToken) throws NextcloudApiException {
    HttpResponse<String> pollAuthResponse = AWSXRayUnirest.post(pollUrl, "nextCloud.getAuthUrl")
        .queryString(Field.TOKEN.getName(), pollToken)
        .asString();

    if (pollAuthResponse.getStatus() == HttpStatusCodes.OK) {
      JsonObject pollAuthResponseBody = new JsonObject(pollAuthResponse.getBody());

      return new JsonObject()
          .put(Field.EMAIL.getName(), pollAuthResponseBody.getString("loginName"))
          .put(Field.PASSWORD.getName(), pollAuthResponseBody.getString("appPassword"))
          .put("serverUrl", pollAuthResponseBody.getString(Field.SERVER.getName()));
    } else {
      throw new NextcloudApiException(NextcloudApiException.AUTH_NOT_FINISHED);
    }
  }

  /**
   * Gets user metadata.
   *
   * @return the user metadata
   * @throws NextcloudApiException the nextcloud api exception
   */
  public JsonObject getUserMetadata(String userId) throws NextcloudApiException {
    String user;
    if (Utils.isStringNotNullOrEmpty(userId)) {
      user = userId;
    } else {
      user = this.username;
    }
    HttpResponse<String> metadataResponse = AWSXRayUnirest.get(
            url.concat("/ocs/v2.php/cloud/users/").concat(user), "nextCloud.getAuthUrl")
        .queryString("format", "json")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .basicAuth(username, password)
        .asString();

    int ocsResultStatusCode = getOcsResultStatusCode(metadataResponse);
    if (ocsResultStatusCode == 100 || ocsResultStatusCode == 200) {
      JsonObject userdata = getOcsResultObject(metadataResponse);

      JsonObject meta = new JsonObject()
          .put(Field.DISPLAY_NAME.getName(), userdata.getString("displayname"))
          .put(Field.EMAIL.getName(), userdata.getString(Field.EMAIL.getName()));

      if (userdata.containsKey(Field.QUOTA.getName())) {
        meta.put("quotaFree", userdata.getJsonObject(Field.QUOTA.getName()).getLong("free"))
            .put("quotaUsed", userdata.getJsonObject(Field.QUOTA.getName()).getLong("used"))
            .put("quotaTotal", userdata.getJsonObject(Field.QUOTA.getName()).getLong("total"));
      }

      return meta;
    } else {
      throw new NextcloudApiException(NextcloudApiException.NO_SUCH_USER, metadataResponse);
    }
  }

  /**
   * Delete app password.
   *
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void deleteAppPassword() throws NextcloudApiException {
    HttpResponse<String> authUrlResponse = AWSXRayUnirest.delete(
            url.concat("/ocs/v2.php/core/apppassword"), "nextCloud.deleteAppPassword")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .basicAuth(username, password)
        .asString();

    if (authUrlResponse.getStatus() != HttpStatusCodes.OK) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_DELETE_APP_PASSWORD);
    }
  }

  /**
   * Gets shares.
   *
   * @param filePath the file path
   * @return the shares
   * @throws NextcloudApiException the nextcloud api exception
   */
  public JsonObject getShares(String filePath) throws NextcloudApiException {
    HttpResponse<String> getSharesInfoResponse = AWSXRayUnirest.get(
            url.concat("/ocs/v2.php/apps/files_sharing/api/v1/shares"), "nextCloud.getShares")
        .queryString(Field.PATH.getName(), filePath)
        .queryString("format", "json")
        .queryString("reshares", "true")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .basicAuth(username, password)
        .asString();

    HttpResponse<String> getSharesInfoResponseWithMe = AWSXRayUnirest.get(
            url.concat("/ocs/v2.php/apps/files_sharing/api/v1/shares"), "nextCloud.getShares")
        .queryString(Field.PATH.getName(), filePath)
        .queryString("format", "json")
        .queryString("reshares", "true")
        .queryString("shared_with_me", "true")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .basicAuth(username, password)
        .asString();

    if (getSharesInfoResponse.getStatus() == HttpStatusCodes.OK
        && getSharesInfoResponseWithMe.getStatus() == HttpStatusCodes.OK) {
      JsonArray viewers = new JsonArray();
      JsonArray editors = new JsonArray();

      for (Object shareObj : getOcsResultArray(getSharesInfoResponse)
          .addAll(getOcsResultArray(getSharesInfoResponseWithMe))) {
        JsonObject share = (JsonObject) shareObj;

        if (share.getInteger("share_type") == 0) {
          JsonObject formattedShare = new JsonObject()
              .put(Field.EMAIL.getName(), share.getString("share_with"))
              .put(Field.NAME.getName(), share.getString("share_with_displayname"))
              .put(Field.USER_ID.getName(), share.getString("share_with"))
              .put(Field.ENCAPSULATED_ID.getName(), share.getString(Field.ID.getName()))
              .put(
                  "canModify",
                  share.getString("uid_file_owner").equals(username)
                      || share.getString("uid_owner").equals(username)
                      || share.getString("share_with").equals(username));

          if (NextcloudPermission.parseFormatted(
                  String.valueOf(share.getString(Field.PERMISSIONS.getName())))
              == NextcloudPermission.EDIT) {
            editors.add(formattedShare);
          } else {
            viewers.add(formattedShare);
          }
        }
      }

      return new JsonObject()
          .put(Field.EDITOR.getName(), editors)
          .put(Field.VIEWER.getName(), viewers);
    } else {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_SHARES);
    }
  }

  /**
   * Add share json object.
   *
   * @param filePath    the file path
   * @param isFile      the is file
   * @param permission  the permission
   * @param shareWithId the share with id
   * @return the json object
   * @throws NextcloudApiException the nextcloud api exception
   */
  public JsonObject addShare(
      String filePath, boolean isFile, NextcloudPermission permission, String shareWithId)
      throws NextcloudApiException {
    HttpResponse<String> addShareResponse = AWSXRayUnirest.post(
            url.concat("/ocs/v2.php/apps/files_sharing/api/v1/shares"), "nextCloud.addShare")
        .queryString("format", "json")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .header("Content-Type", "application/json")
        .body(new JsonObject()
            .put(Field.PATH.getName(), filePath)
            .put(Field.PERMISSIONS.getName(), permission.getFormatted(isFile))
            .put("shareType", 0)
            .put("shareWith", shareWithId)
            .toString())
        .basicAuth(username, password)
        .asString();

    if (addShareResponse.getStatus() == HttpStatusCodes.OK) {
      return getOcsResultObject(addShareResponse);
    } else {

      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_ADD_SHARE);
    }
  }

  /**
   * Update share json object.
   *
   * @param shareId    the share id
   * @param isFile     the is file
   * @param permission the permission
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void updateShare(String shareId, boolean isFile, NextcloudPermission permission)
      throws NextcloudApiException {
    HttpResponse<String> updateShareResponse = AWSXRayUnirest.put(
            url.concat("/ocs/v2.php/apps/files_sharing/api/v1/shares/".concat(shareId)),
            "nextCloud.updateShare")
        .queryString("format", "json")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .header("Content-Type", "application/json")
        .body(new JsonObject()
            .put(Field.PERMISSIONS.getName(), permission.getFormatted(isFile))
            .toString())
        .basicAuth(username, password)
        .asString();

    if (updateShareResponse.getStatus() == HttpStatusCodes.OK) {
      getOcsResultObject(updateShareResponse);
    } else {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_UPDATE_SHARE);
    }
  }

  /**
   * Delete share.
   *
   * @param shareId the share id
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void deleteShare(String shareId) throws NextcloudApiException {
    HttpResponse<String> deleteShareResponse = AWSXRayUnirest.delete(
            url.concat("/ocs/v2.php/apps/files_sharing/api/v1/shares/".concat(shareId)),
            "nextCloud.deleteShare")
        .queryString("format", "json")
        .header("User-Agent", "Ares Kudo")
        .header("OCS-APIREQUEST", "true")
        .basicAuth(username, password)
        .asString();

    if (deleteShareResponse.getStatus() != HttpStatusCodes.OK) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_DELETE_SHARE);
    }
  }

  /**
   * Search list.
   *
   * @param query the query
   * @return the list
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> search(String query) throws NextcloudApiException {
    requireSardine();

    String url = this.url.concat("/remote.php/dav/files/");

    try {
      return sardine.search(url, query, username);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_SEARCH, e);
    }
  }

  /**
   * Gets folder content.
   *
   * @param path the path
   * @return the folder content
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> getFolderContent(String path) throws NextcloudApiException {
    requireSardine();

    try {
      return AWSXrayNextCloudSardine.list(sardine, encodePath(url + path), 1, props);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_FOLDER_CONTENT, e);
    }
  }

  /**
   * Gets info.
   *
   * @param path the path
   * @return the info
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> getInfo(String path) throws NextcloudApiException {
    requireSardine();

    try {
      return AWSXrayNextCloudSardine.list(sardine, encodePath(url + path), 0, props);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_INFO, e);
    }
  }

  /**
   * Create directory list.
   *
   * @param parentPath the parent path
   * @param name       the name
   * @return the list with info
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> createDirectory(String parentPath, String name)
      throws NextcloudApiException {
    requireSardine();

    try {
      if (!parentPath.endsWith("/")) {
        parentPath += ("/");
      }
      parentPath += name;

      AWSXrayNextCloudSardine.createDirectory(sardine, encodePath(url + parentPath));

      return getInfo(parentPath);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_CREATE_FOLDER, e);
    }
  }

  /**
   * Move.
   *
   * @param fromPath the from path
   * @param toPath   the to path
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void move(String fromPath, String toPath) throws NextcloudApiException {
    requireSardine();

    try {
      AWSXrayNextCloudSardine.move(sardine, encodePath(url + fromPath), encodePath(url + toPath));
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_MOVE_OBJECT, e);
    }
  }

  /**
   * Rename.
   *
   * @param pathToRename the path to rename
   * @param name         the name
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void rename(String pathToRename, String name) throws NextcloudApiException {
    requireSardine();

    try {
      String parent = Utils.getFilePathFromPathWithSlash(pathToRename);
      String destination = parent + name;

      AWSXrayNextCloudSardine.move(
          sardine, encodePath(url + pathToRename), encodePath(url + destination));
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_RENAME_OBJECT, e);
    }
  }

  /**
   * Delete.
   *
   * @param path the path
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void delete(String path) throws NextcloudApiException {
    requireSardine();

    try {
      AWSXrayNextCloudSardine.delete(sardine, encodePath(url + path));
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_DELETE_OBJECT, e);
    }
  }

  /**
   * Clone list.
   *
   * @param pathToClone the path to clone
   * @param name        the name
   * @return the list with info
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> clone(String pathToClone, String name) throws NextcloudApiException {
    requireSardine();

    try {
      String parent = Utils.getFilePathFromPathWithSlash(pathToClone);
      String destination = parent + name;

      AWSXrayNextCloudSardine.copy(
          sardine, encodePath(url + pathToClone), encodePath(url + destination));

      return getInfo(destination);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_CLONE_OBJECT, e);
    }
  }

  /**
   * Upload list.
   *
   * @param path the path
   * @param stream the data
   * @return the list with info
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> upload(String path, InputStream stream) throws NextcloudApiException {
    SardineImpl2 sardine = SardineFactory.begin(username, password, false);

    try {
      AWSXrayNextCloudSardine.put(sardine, encodePath(url + path), IOUtils.toByteArray(stream));

      return getInfo(path);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_UPLOAD_DATA, e);
    }
  }

  /**
   * Check file boolean.
   *
   * @param pathToCheck the path to check
   * @return the boolean
   * @throws NextcloudApiException the nextcloud api exception
   */
  public boolean checkFile(String pathToCheck) throws NextcloudApiException {
    requireSardine();

    try {
      return !AWSXrayNextCloudSardine.list(
              sardine, encodePath(url + pathToCheck), 0, new HashSet<>())
          .isEmpty();
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Gets versions.
   *
   * @param fileId the file id
   * @return the versions
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> getVersions(String fileId) throws NextcloudApiException {
    requireSardine();

    String url = this.url
        .concat("/remote.php/dav/versions/")
        .concat(username)
        .concat("/versions/")
        .concat(fileId);

    try {
      return AWSXrayNextCloudSardine.list(sardine, encodePath(url), 1, new HashSet<>());
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_VERSIONS, e);
    }
  }

  /**
   * Gets version.
   *
   * @param fileId    the file id
   * @param versionId the version id
   * @return the version
   * @throws NextcloudApiException the nextcloud api exception
   */
  public List<DavResource> getVersion(String fileId, String versionId)
      throws NextcloudApiException {
    requireSardine();

    String url = this.url
        .concat("/remote.php/dav/versions/")
        .concat(username)
        .concat("/versions/")
        .concat(fileId)
        .concat("/")
        .concat(versionId);

    try {
      return AWSXrayNextCloudSardine.list(sardine, encodePath(url), 1, props);
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_VERSIONS, e);
    }
  }

  /**
   * Promote.
   *
   * @param fileId    the file id
   * @param versionId the version id
   * @throws NextcloudApiException the nextcloud api exception
   */
  public void promote(String fileId, String versionId) throws NextcloudApiException {
    requireSardine();

    String from = this.url
        .concat("/remote.php/dav/versions/")
        .concat(username)
        .concat("/versions/")
        .concat(fileId)
        .concat("/")
        .concat(versionId);

    String to =
        this.url.concat("/remote.php/dav/versions/").concat(username).concat("/restore/target");

    try {
      sardine.move(encodePath(from), encodePath(to));
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_PROMOTE_VERSION, e);
    }
  }

  /**
   * Gets object data.
   *
   * @param path the path
   * @return the object data
   * @throws NextcloudApiException the nextcloud api exception
   */
  public InputStream getObjectData(String path) throws NextcloudApiException {
    requireSardine();

    try {
      return AWSXrayNextCloudSardine.get(sardine, encodePath(url.concat(path)));
    } catch (IOException e) {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_DATA, e);
    }
  }

  // utils . . .

  private JsonArray getOcsResultArray(HttpResponse<String> response) throws NextcloudApiException {
    if (response.isSuccess()) {
      return new JsonObject(response.getBody())
          .getJsonObject("ocs")
          .getJsonArray(Field.DATA.getName());
    } else {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_DATA_BODY);
    }
  }

  private JsonObject getOcsResultObject(HttpResponse<String> response)
      throws NextcloudApiException {
    if (response.isSuccess()) {
      return new JsonObject(response.getBody())
          .getJsonObject("ocs")
          .getJsonObject(Field.DATA.getName());
    } else {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_DATA_BODY);
    }
  }

  private int getOcsResultStatusCode(HttpResponse<String> response) throws NextcloudApiException {
    if (response.isSuccess()) {
      int code = new JsonObject(response.getBody())
          .getJsonObject("ocs")
          .getJsonObject("meta")
          .getInteger("statuscode");

      switch (code) {
        case 100:
        case 200:
          return code;
        case 996:
          throw new NextcloudApiException(NextcloudApiException.NC_SERVER_ERROR);
        case 997:
          throw new NextcloudApiException(NextcloudApiException.NC_NOT_AUTHORIZED);
        case 998:
          throw new NextcloudApiException(NextcloudApiException.NC_NOT_FOUND);
        case 999:
        default:
          throw new NextcloudApiException(NextcloudApiException.NC_UNKNOWN_ERROR);
      }
    } else {
      throw new NextcloudApiException(NextcloudApiException.COULD_NOT_GET_OCS_CODE);
    }
  }

  public String encodePath(String path) {
    return URLEncoder.encode(path, StandardCharsets.UTF_8)
        .replaceAll("\\+", "%20")
        .replaceAll(" ", "%20")
        .replaceAll("%3A%2F%2F", "://") // do not encode this
        .replaceAll("%2F", "/"); // do not encode these
  }

  private void requireSardine() {
    if (this.sardine == null) {
      this.sardine = SardineFactory.begin(username, password, false);

      this.props.add(new QName("http://owncloud.org/ns", Field.ID.getName(), "oc"));
      this.props.add(new QName("http://owncloud.org/ns", "fileid", "oc"));
      this.props.add(new QName("http://owncloud.org/ns", "owner-id", "oc"));
      this.props.add(new QName("http://owncloud.org/ns", "owner-display-name", "oc"));
      this.props.add(new QName("http://owncloud.org/ns", Field.PERMISSIONS.getName(), "oc"));
    }
  }

  /**
   * Save pending login.
   *
   * @param userId     the user id
   * @param url        the url
   * @param periodicId the periodic id
   */
  public static void savePendingLogin(String userId, String url, long periodicId) {
    Dataplane.putItem(
        new Item()
            .withPrimaryKey(
                Field.PK.getName(), "LOGIN_POLL#".concat(userId), Field.SK.getName(), url)
            .withLong("periodicId", periodicId),
        DataEntityType.NEXTCLOUD_API);
  }

  /**
   * Delete pending login long.
   *
   * @param userId the user id
   * @param url    the url
   * @return the long
   */
  public static long deletePendingLogin(String userId, String url) {
    Item login = Dataplane.getItem(
        Dataplane.TableName.MAIN.name,
        Field.PK.getName(),
        "LOGIN_POLL#".concat(userId),
        Field.SK.getName(),
        url,
        true,
        DataEntityType.NEXTCLOUD_API);

    if (login != null) {
      Dataplane.deleteItem(
          Dataplane.TableName.MAIN.name,
          Field.PK.getName(),
          "LOGIN_POLL#".concat(userId),
          Field.SK.getName(),
          url,
          DataEntityType.NEXTCLOUD_API);
      return login.getLong("periodicId");
    }

    return 0L;
  }
}
