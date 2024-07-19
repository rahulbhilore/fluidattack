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
  private List<String> editors;
  private List<String> viewers;
  private boolean isOrganizationObject;
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
    if (object.containsKey(Field.DE_SHARE.getName())) {
      object.remove(Field.DE_SHARE.getName());
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

  public ResourceBuffer setFileType(String fileType) {
    this.fileType = fileType;
    return this;
  }

  public List<Map<String, Object>> getFontInfo() {
    return fontInfo;
  }

  public ResourceBuffer setFontInfo(List<Map<String, Object>> fontInfo) {
    this.fontInfo = fontInfo;
    return this;
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

  public ResourceBuffer setObjectId(String objectId) {
    this.objectId = objectId;
    return this;
  }

  public String getOwnerType() {
    return ownerType;
  }

  public ResourceBuffer setOwnerType(String ownerType) {
    this.ownerType = ownerType;
    return this;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public ResourceBuffer setOwnerId(String ownerId) {
    this.ownerId = ownerId;
    return this;
  }

  public String getParent() {
    return parent;
  }

  public ResourceBuffer setParent(String parent) {
    this.parent = parent;
    return this;
  }

  public String getObjectType() {
    return objectType;
  }

  public ResourceBuffer setObjectType(String objectType) {
    this.objectType = objectType;
    return this;
  }

  public String getPath() {
    return path;
  }

  public ResourceBuffer setPath(String path) {
    this.path = path;
    return this;
  }

  public String getFileName() {
    return fileName;
  }

  public ResourceBuffer setFileName(String fileName) {
    this.fileName = fileName;
    return this;
  }

  public int getFileSize() {
    return fileSize;
  }

  public ResourceBuffer setFileSize(int fileSize) {
    this.fileSize = fileSize;
    return this;
  }

  public byte[] getFileContent() {
    return fileContent;
  }

  public ResourceBuffer setFileContent(byte[] fileContent) {
    this.fileContent = fileContent;
    return this;
  }

  public String getName() {
    return name;
  }

  public ResourceBuffer setName(String name) {
    this.name = name;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public ResourceBuffer setDescription(String description) {
    this.description = description;
    return this;
  }

  public String getUserId() {
    return userId;
  }

  public ResourceBuffer setUserId(String userId) {
    this.userId = userId;
    return this;
  }

  public String getObjectFilter() {
    return objectFilter;
  }

  public ResourceBuffer setObjectFilter(String objectFilter) {
    this.objectFilter = objectFilter;
    return this;
  }

  public String getDownloadToken() {
    return downloadToken;
  }

  public ResourceBuffer setDownloadToken(String downloadToken) {
    this.downloadToken = downloadToken;
    return this;
  }

  public long getCreated() {
    return created;
  }

  public ResourceBuffer setCreated(long created) {
    this.created = created;
    return this;
  }

  public long getUpdated() {
    return updated;
  }

  public ResourceBuffer setUpdated(long updated) {
    this.updated = updated;
    return this;
  }

  public String getS3Path() {
    return s3Path;
  }

  public ResourceBuffer setS3Path(String s3Path) {
    this.s3Path = s3Path;
    return this;
  }

  public String getOwnerName() {
    return ownerName;
  }

  public ResourceBuffer setOwnerName(String ownerName) {
    this.ownerName = ownerName;
    return this;
  }

  public String getResourceType() {
    return resourceType;
  }

  public ResourceBuffer setResourceType(String resourceType) {
    this.resourceType = resourceType;
    return this;
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

  public ResourceBuffer setOrganizationId(String organizationId) {
    this.organizationId = organizationId;
    return this;
  }

  public List<String> getEditors() {
    return editors;
  }

  public void setEditors(List<String> editors) {
    this.editors = editors;
  }

  public List<String> getViewers() {
    return viewers;
  }

  public void setViewers(List<String> viewers) {
    this.viewers = viewers;
  }

  public boolean isOrganizationObject() {
    return isOrganizationObject;
  }

  public void setOrganizationObject(boolean organizationObject) {
    isOrganizationObject = organizationObject;
  }
}
