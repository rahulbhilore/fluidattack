package com.graebert.storage.integration.onedrive;

import com.graebert.storage.integration.BaseStorage;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.integration.onedrive.graphApi.entity.ObjectInfo;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.xml.bind.DatatypeConverter;
import java.util.Base64;

public class Site {

  public static final String sitePrefix = "ST";
  protected StorageType storageType;
  protected String externalId;
  private final ObjectPermissions objectPermissions = new ObjectPermissions();
  private final String rawId;
  private final String originalId;
  private final String finalId;
  private long creationDate;
  private long modificationDate;
  private final String webUrl;
  private final String name;
  private String siteCollectionHostname = null;

  public String getName() {
    return name;
  }

  public Site(JsonObject siteData, StorageType storageType, String externalId) {
    String id = siteData.getString(Field.ID.getName());
    originalId = id;
    rawId = id.contains(",") ? id.split(",")[1] : id;
    finalId = getFinalIdFromRaw(rawId);
    creationDate = convertDateToTimestamp(siteData.getString("createdDateTime"));
    modificationDate =
        convertDateToTimestamp(siteData.getString(Field.LAST_MODIFIED_DATE_TIME.getName()));
    if (modificationDate <= 0) {
      if (creationDate > 0) {
        modificationDate = creationDate;
      } else {
        modificationDate = 0;
        creationDate = 0;
      }
    }
    name = siteData.containsKey(Field.DISPLAY_NAME.getName())
        ? siteData.getString(Field.DISPLAY_NAME.getName())
        : siteData.getString(Field.NAME.getName());
    webUrl = siteData.getString("webUrl");
    this.storageType = storageType;
    this.externalId = externalId;
    if (siteData.containsKey("siteCollection")
        && siteData.getJsonObject("siteCollection").containsKey("hostname")) {
      siteCollectionHostname = siteData.getJsonObject("siteCollection").getString("hostname");
    }
  }

  public static boolean isSiteId(String passedId) {
    return passedId.startsWith(sitePrefix);
  }

  public static boolean isCollectionId(String rawId) {
    return !rawId.contains(",");
  }

  public static boolean isSpecificSiteId(String rawId) {
    return !isCollectionId(rawId) && rawId.indexOf(",") == rawId.lastIndexOf(",");
  }

  public static String getCollectionId(String siteId) {
    if (isSpecificSiteId(siteId)) {
      return siteId.substring(0, siteId.indexOf(","));
    } else {
      throw new IllegalArgumentException("Site Id should be provided");
    }
  }

  public static String getFinalIdFromRaw(String rawId) {
    if (rawId.startsWith(sitePrefix)) return rawId;

    byte[] idBytes = rawId.getBytes();
    String encodedId = Base64.getEncoder().encodeToString(idBytes);
    return sitePrefix + encodedId;
  }

  public static String getRawIdFromFinal(String finalId) {
    if (!finalId.startsWith(sitePrefix)) return finalId;

    String pureId = finalId.substring(sitePrefix.length());
    return new String(Base64.getDecoder().decode(pureId));
  }

  public String getRawId() {
    return rawId;
  }

  private long convertDateToTimestamp(String dateString) {
    return DatatypeConverter.parseDateTime(dateString).getTime().getTime();
  }

  public JsonObject toJson() {
    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, finalId))
        .put(Field.WS_ID.getName(), finalId)
        .put(Field.NAME.getName(), name)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .put(BaseStorage.OWNER, webUrl)
        .put(Field.CREATION_DATE.getName(), creationDate)
        .put(Field.UPDATE_DATE.getName(), modificationDate)
        .put(Field.CHANGER.getName(), "")
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), false)
        .put(Field.VIEW_ONLY.getName(), false)
        .put(Field.IS_OWNER.getName(), true)
        .put(Field.IS_DELETED.getName(), false)
        .put(Field.CAN_MOVE.getName(), false)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
        .put(Field.PUBLIC.getName(), false);
  }

  public ObjectInfo toObjectInfo() {
    return new ObjectInfo(false)
        .withId(Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, finalId))
        .withWsId(finalId)
        .withName(name)
        .withParent(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .withOwner(webUrl)
        .withCreationDate(creationDate)
        .withUpdateDate(modificationDate)
        .withChanger("")
        .withSize(0)
        .withShared(false)
        .withViewOnly(false)
        .withIsOwner(true)
        .withIsDeleted(false)
        .withCanMove(false)
        .withCollaborators(new JsonArray(), new JsonArray())
        .withExternalPublic(true)
        .withPermissions(objectPermissions)
        .withIsPublic(false);
  }

  public JsonObject toJsonWithURL() {
    String idPart = rawId.substring(0, rawId.indexOf(","));
    String siteCollectionId = getFinalIdFromRaw(idPart);
    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, siteCollectionId))
        .put(Field.WS_ID.getName(), siteCollectionId)
        .put(Field.NAME.getName(), webUrl)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .put(BaseStorage.OWNER, webUrl)
        .put(Field.CREATION_DATE.getName(), creationDate)
        .put(Field.UPDATE_DATE.getName(), modificationDate)
        .put(Field.CHANGER.getName(), "")
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), false)
        .put(Field.VIEW_ONLY.getName(), false)
        .put(Field.IS_OWNER.getName(), true)
        .put(Field.IS_DELETED.getName(), false)
        .put(Field.CAN_MOVE.getName(), false)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
        .put(Field.PUBLIC.getName(), false);
  }

  public ObjectInfo toObjectInfoWithURL() {
    String idPart = rawId.contains(",") ? rawId.substring(0, rawId.indexOf(",")) : rawId;
    String siteCollectionId = getFinalIdFromRaw(idPart);

    return new ObjectInfo(false)
        .withId(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, siteCollectionId))
        .withWsId(siteCollectionId)
        .withName(webUrl)
        .withParent(Utils.getEncapsulatedId(
            StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .withOwner(webUrl)
        .withCreationDate(creationDate)
        .withUpdateDate(modificationDate)
        .withChanger("")
        .withSize(0)
        .withShared(false)
        .withViewOnly(false)
        .withIsOwner(true)
        .withIsDeleted(false)
        .withCanMove(false)
        .withCollaborators(new JsonArray(), new JsonArray())
        .withExternalPublic(true)
        .withPermissions(objectPermissions)
        .withIsPublic(false);
  }

  public JsonObject toJsonForSiteCollection() {
    String idPart = rawId.substring(0, rawId.lastIndexOf(","));
    String siteCollectionId = getFinalIdFromRaw(idPart);
    return new JsonObject()
        .put(
            Field.ENCAPSULATED_ID.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, siteCollectionId))
        .put(Field.WS_ID.getName(), siteCollectionId)
        .put(Field.NAME.getName(), name)
        .put(
            Field.PARENT.getName(),
            Utils.getEncapsulatedId(
                StorageType.getShort(storageType), externalId, Field.MINUS_1.getName()))
        .put(BaseStorage.OWNER, webUrl)
        .put(Field.CREATION_DATE.getName(), creationDate)
        .put(Field.UPDATE_DATE.getName(), modificationDate)
        .put(Field.CHANGER.getName(), "")
        .put(Field.SIZE.getName(), Utils.humanReadableByteCount(0))
        .put(Field.SIZE_VALUE.getName(), 0)
        .put(Field.SHARED.getName(), false)
        .put(Field.VIEW_ONLY.getName(), false)
        .put(Field.IS_OWNER.getName(), true)
        .put(Field.IS_DELETED.getName(), false)
        .put(Field.CAN_MOVE.getName(), false)
        .put(
            Field.SHARE.getName(),
            new JsonObject()
                .put(Field.VIEWER.getName(), new JsonArray())
                .put(Field.EDITOR.getName(), new JsonArray()))
        .put(Field.EXTERNAL_PUBLIC.getName(), true)
        .put(Field.PERMISSIONS.getName(), objectPermissions.toJson())
        .put(Field.PUBLIC.getName(), false);
  }

  public String getSiteCollectionHostname() {
    return siteCollectionHostname;
  }

  public String getRawIdUntilSiteCollectionId() {
    return rawId.substring(0, rawId.lastIndexOf(","));
  }
}
