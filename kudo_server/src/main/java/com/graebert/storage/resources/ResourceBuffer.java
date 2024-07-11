package com.graebert.storage.resources;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graebert.storage.resources.integration.BaseResource;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ResourceBuffer {
  private String objectId;
  private String ownerType;
  private String ownerName;
  private String ownerId;
  private String userId;
  private String organizationId;
  private String name;
  private String description;
  private String parent;
  private String path;
  private String objectType;
  private String objectFilter;
  private String fileName;
  private long created;
  private long updated;
  private String s3Path;
  private String downloadToken;
  private String resourceType;
  private int fileSize;
  private byte[] fileContent;
  private String fileType;
  private List<Map<String, Object>> fontInfo;
  private List<String> fontFamilyNames;

  private boolean isRootFolder;

  public static JsonObject toJson(ResourceBuffer resource) {
    ObjectMapper obj = new ObjectMapper();
    try {
      String jsonString = obj.writeValueAsString(resource);
      JsonObject resourceObject = new JsonObject(jsonString);
      if (Objects.nonNull(resource.fileContent)) {
        resourceObject.put(Field.FILE_CONTENT.getName(), resource.getFileContent());
      }
      return resourceObject;
    } catch (IOException e) {
      BaseResource.log.error("Error in parsing resource object " + e.getLocalizedMessage());
      return null;
    }
  }

  public static ResourceBuffer itemToResource(Item item) throws JsonProcessingException {
    JsonObject resourceObject = new JsonObject(item.toJSON());
    resourceObject.remove(Dataplane.pk);
    resourceObject.remove(Dataplane.sk);
    resourceObject.remove("fileNameLower");
    return jsonObjectToResource(resourceObject);
  }

  public static ResourceBuffer jsonObjectToResource(JsonObject object)
      throws JsonProcessingException {
    byte[] fileContent = null;
    if (object.containsKey(Field.FILE_CONTENT.getName())) {
      fileContent = object.getBinary(Field.FILE_CONTENT.getName());
      object.remove(Field.FILE_CONTENT.getName());
    }
    object.remove(Field.TRACE_ID.getName());
    object.remove(Field.RECURSIVE.getName());
    object.remove(Field.SEGMENT_PARENT_ID.getName());
    ObjectMapper mapper = new ObjectMapper();
    ResourceBuffer buffer = mapper.readValue(object.toString(), ResourceBuffer.class);
    if (Objects.nonNull(fileContent)) {
      buffer.setFileContent(fileContent);
    }
    return buffer;
  }

  public String getFileType() {
    return fileType;
  }

  public void setFileType(String fileType) {
    this.fileType = fileType;
  }

  public List<Map<String, Object>> getFontInfo() {
    return fontInfo;
  }

  public void setFontInfo(List<Map<String, Object>> fontInfo) {
    this.fontInfo = fontInfo;
  }

  public List<String> getFontFamilyNames() {
    return fontFamilyNames;
  }

  public void setFontFamilyNames(List<String> fontFamilyNames) {
    this.fontFamilyNames = fontFamilyNames;
  }

  public String getObjectId() {
    return objectId;
  }

  public void setObjectId(String objectId) {
    this.objectId = objectId;
  }

  public String getOwnerType() {
    return ownerType;
  }

  public void setOwnerType(String ownerType) {
    this.ownerType = ownerType;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getParent() {
    return parent;
  }

  public void setParent(String parent) {
    this.parent = parent;
  }

  public String getObjectType() {
    return objectType;
  }

  public void setObjectType(String objectType) {
    this.objectType = objectType;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public int getFileSize() {
    return fileSize;
  }

  public void setFileSize(int fileSize) {
    this.fileSize = fileSize;
  }

  public byte[] getFileContent() {
    return fileContent;
  }

  public void setFileContent(byte[] fileContent) {
    this.fileContent = fileContent;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getObjectFilter() {
    return objectFilter;
  }

  public void setObjectFilter(String objectFilter) {
    this.objectFilter = objectFilter;
  }

  public String getDownloadToken() {
    return downloadToken;
  }

  public void setDownloadToken(String downloadToken) {
    this.downloadToken = downloadToken;
  }

  public long getCreated() {
    return created;
  }

  public void setCreated(long created) {
    this.created = created;
  }

  public long getUpdated() {
    return updated;
  }

  public void setUpdated(long updated) {
    this.updated = updated;
  }

  public String getS3Path() {
    return s3Path;
  }

  public void setS3Path(String s3Path) {
    this.s3Path = s3Path;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public void setOwnerName(String ownerName) {
    this.ownerName = ownerName;
  }

  public String getResourceType() {
    return resourceType;
  }

  public void setResourceType(String resourceType) {
    this.resourceType = resourceType;
  }

  public boolean isRootFolder() {
    return isRootFolder;
  }

  public void setRootFolder(boolean rootFolder) {
    isRootFolder = rootFolder;
  }

  public String getOrganizationId() {
    return organizationId;
  }

  public void setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
  }
}
