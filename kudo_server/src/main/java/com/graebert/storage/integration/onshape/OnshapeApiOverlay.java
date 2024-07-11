package com.graebert.storage.integration.onshape;

import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import kong.unirest.GetRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.MultipartBody;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.NonNls;

public class OnshapeApiOverlay {
  @NonNls
  public static final String FOLDER = "f:";

  @NonNls
  public static final String PROJECT = "p:";

  @NonNls
  private static final String BEARER = "Bearer ";

  @NonNls
  private static final String ELEMENT_ID = "elementId";

  public static String findWorkspaceForDocument(
      final String apiUrl, final String accessToken, final String documentId)
      throws OnshapeException {
    HttpResponse<JsonNode> response = AWSXRayUnirest.get(
            apiUrl + "/documents/" + documentId, "Onshape.getDocumentInfo")
        .header("Authorization", BEARER + accessToken)
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to find workspace", response);
    }
    return response
        .getBody()
        .getObject()
        .getJSONObject("defaultWorkspace")
        .getString(Field.ID.getName());
  }

  public static String findNameForFolderOrDocument(
      final String apiUrl, final boolean isFolder, final String accessToken, final String id)
      throws OnshapeException {
    HttpResponse<JsonNode> response = AWSXRayUnirest.get(
            apiUrl + (isFolder ? "/folders/" : "/documents/") + id, "Onshape.getDocumentInfo")
        .header("Authorization", BEARER + accessToken)
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to find name ", response);
    }
    return response.getBody().getObject().getString(Field.NAME.getName());
  }

  public static String findElementName(
      final String apiUrl,
      final String accessToken,
      final String documentId,
      final String workspaceId,
      final String elementId)
      throws OnshapeException {
    HttpResponse<JsonNode> response = AWSXRayUnirest.get(
            apiUrl + "/elements/" + documentId + "/workspace/" + workspaceId,
            "Onshape.getDocumentContent")
        .header("Authorization", BEARER + accessToken)
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to find element", response);
    }
    JSONObject obj;
    for (Object item : response.getBody().getArray()) {
      obj = (JSONObject) item;
      if (elementId.equals(obj.getString(ELEMENT_ID))) {
        return obj.getString(Field.NAME.getName());
      }
    }
    return null;
  }

  public static JSONObject createDocument(
      final String apiUrl,
      final String accessToken,
      final String name,
      final String folderId,
      final String externalId)
      throws OnshapeException {
    boolean createPublic = false;
    if (Utils.isStringNotNullOrEmpty(externalId)) {
      HttpResponse<JsonNode> accountResponse = getAccountInfo(apiUrl, accessToken, externalId);
      if (accountResponse.getStatus() == 200) {
        String activePlanId = accountResponse.getBody().getObject().getString("activePlanId");
        if (Utils.isStringNotNullOrEmpty(activePlanId) && activePlanId.contains("FREE")) {
          createPublic = true;
        }
      }
    }
    JSONObject requestBody = new JSONObject()
        .put(Field.NAME.getName(), name)
        .put(Field.IS_PUBLIC.getName(), createPublic)
        .put(Field.OWNER_TYPE.getName(), 0);
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      if (folderId.startsWith(FOLDER)) {
        // move document to the correct folder
        requestBody.put(Field.PARENT_ID.getName(), folderId.substring(2));
      } else if (folderId.startsWith(PROJECT)) {
        // add to project
        requestBody.put(Field.PROJECT_ID.getName(), folderId.substring(2));
      }
    }
    HttpResponse<JsonNode> response = AWSXRayUnirest.post(
            apiUrl + "/documents", "Onshape.createDocument")
        .header("Authorization", BEARER + accessToken)
        .header("Content-type", "application/json")
        .body(requestBody)
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to create document", response);
    }
    return response.getBody().getObject();
  }

  public static JsonNode uploadElement(
      final String url,
      final String accessToken,
      final InputStream stream,
      final String name,
      final String workspaceId,
      final String elementId,
      final String folderId)
      throws OnshapeException {
    HttpRequestWithBody request = AWSXRayUnirest.post(url, "Onshape.uploadFile")
        .header("Authorization", BEARER + accessToken);

    // DK: pretty sure it's not required, but let's have it for now
    if (workspaceId != null) {
      request = request.queryString("workspaceId", workspaceId);
    }
    if (elementId != null) {
      request = request.queryString(ELEMENT_ID, elementId);
    }

    final String finalName = name != null ? name : "version.dwg";
    MultipartBody multipartBody = request
        .field(Field.FILE.getName(), stream, finalName)
        .field("encodedFilename", URLEncoder.encode(finalName, StandardCharsets.UTF_8));

    //                if (newDocument) {
    //                    // if we want, we can eventually also set the owner to be the same as
    //                    the parentId
    //                    //formrequest = formrequest.field(Field.OWNER_TYPE.getName(), "1"); //
    //                    0 means user, 1 means organization
    //                    //formrequest = formrequest.field(Field.OWNER_ID.getName(),
    //                    "57f64d8829b05910044eebe2"); //  0 means user, 1 means organization
    //
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      if (folderId.startsWith(FOLDER)) {
        // move document to the correct folder
        multipartBody = multipartBody.field(Field.PARENT_ID.getName(), folderId.substring(2));
      } else if (folderId.startsWith(PROJECT)) {
        // add to project
        multipartBody = multipartBody.field(Field.PROJECT_ID.getName(), folderId.substring(2));
      }
    }
    //                }
    HttpResponse<JsonNode> response = multipartBody
        .field("createDrawingIfPossible", "false", ContentType.TEXT_PLAIN.toString())
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to upload element", response);
    }
    return response.getBody();
  }

  public static String getWorkspaceVersionId(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspaceId,
      final String elementId)
      throws OnshapeException {
    GetRequest request = AWSXRayUnirest.get(
            apiUrl + "/documents/d/" + folderId + "/w/" + workspaceId + "/currentmicroversion",
            "Onshape.getCurrentMicroVersion")
        .header("Authorization", BEARER + accessToken);
    if (elementId != null) {
      request.queryString(ELEMENT_ID, elementId);
    }
    HttpResponse<JsonNode> response = request.asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to find workspace versionID", response);
    }
    return response.getBody().getObject().getString("microversion");
  }

  public static void promoteVersion(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace,
      final String versionId)
      throws OnshapeException {
    HttpResponse<JsonNode> response = AWSXRayUnirest.post(
            apiUrl + "/documents/" + folderId + "/w/" + workspace + "/restore/m/" + versionId,
            "Onshape.restoreFileVersion")
        .header("Authorization", BEARER + accessToken)
        .header("Content-Type", "application/json")
        .asJson();

    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to promote version", response);
    }
  }

  public static JSONArray getWorkspaceVersions(
      final String apiUrl, final String accessToken, final String folderId, final String workspace)
      throws OnshapeException {
    HttpResponse<JsonNode> response = AWSXRayUnirest.get(
            apiUrl + "/documents/d/" + folderId + "/w" + "/" + workspace + "/documenthistory",
            "Onshape.getFileHistory")
        .header("Authorization", BEARER + accessToken)
        .header("Content-Type", "application/json")
        .asJson();
    if (response.getStatus() != 200) {
      throw new OnshapeException("Unable to get workspace versions", response);
    }
    return response.getBody().getArray();
  }

  public static String getFileBlobUrl(
      final String apiUrl,
      final String folderId,
      final String workspace,
      final String elementId,
      final String versionPart) {
    return apiUrl + "/blobelements/d/" + folderId
        + (Objects.nonNull(versionPart) ? versionPart : "/w/" + workspace) + "/e/" + elementId;
  }

  public static HttpResponse<byte[]> getFileBlob(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace,
      final String elementId,
      final String versionPart) {
    String url = getFileBlobUrl(apiUrl, folderId, workspace, elementId, versionPart);
    return AWSXRayUnirest.get(url, "Onshape.getFileBlob")
        .header("Authorization", BEARER + accessToken)
        .asBytes();
  }

  // I don't think we need this as this endpoint returns json response
  // https://cad.onshape.com/glassworks/explorer/#/AppElement/getSubElementContent
  public static HttpResponse<byte[]> getAppElementContent(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace,
      final String elementId,
      final String versionPart) {
    String url = apiUrl + "/appelements/d/" + folderId
        + (Objects.nonNull(versionPart) ? versionPart : "/w/" + workspace) + "/e/" + elementId
        + "/content";
    return AWSXRayUnirest.get(url, "Onshape.getAppElementContent")
        .header("Authorization", BEARER + accessToken)
        .asBytes();
  }

  public static HttpResponse<JsonNode> getElementFromDocumentContent(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace,
      final String elementId) {
    String url = apiUrl + "/documents/d/" + folderId + "/w/" + workspace + "/elements";
    return AWSXRayUnirest.get(url, "Onshape.getElementFromDocument")
        .queryString(ELEMENT_ID, elementId)
        .header("Authorization", BEARER + accessToken)
        .asJson();
  }

  public static HttpResponse<JsonNode> getDocumentContent(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace) {
    String url = apiUrl + "/documents/d/" + folderId + "/w/" + workspace + "/elements";
    return AWSXRayUnirest.get(url, "Onshape.getDocumentContent")
        .header("Authorization", BEARER + accessToken)
        .asJson();
  }

  public static HttpResponse<JsonNode> getDocumentInfo(
      final String apiUrl, final String accessToken, final String folderId, boolean isDocument) {
    String url = apiUrl + (isDocument ? "/documents/" : "/folders/") + folderId;
    return AWSXRayUnirest.get(url, "Onshape.getDocumentInfo")
        .header("Authorization", BEARER + accessToken)
        .asJson();
  }

  public static HttpResponse<JsonNode> getFileInfo(
      final String apiUrl,
      final String accessToken,
      final String folderId,
      final String workspace,
      final String fileId) {
    String url = apiUrl + "/elements/d/" + folderId + "/w/" + workspace + "/e/" + fileId;
    return AWSXRayUnirest.get(url, "Onshape.getFileInfo")
        .header("Authorization", BEARER + accessToken)
        .asJson();
  }

  public static HttpResponse<JsonNode> getAccountInfo(
      final String apiUrl, final String accessToken, final String externalId) {
    String url = apiUrl + "/users/" + externalId;
    return AWSXRayUnirest.get(url, "Onshape.getAccountInfo")
        .header("Authorization", BEARER + accessToken)
        .asJson();
  }
}
