package com.graebert.storage.integration.nextcloud;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.xray.AWSXRay;
import com.github.sardine.DavResource;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The type Next cloud mapping.
 */
public class NextCloudMapping extends Dataplane {

  private static final String NEXTCLOUD_MAPPING = "nextcloudmapping";
  private static final String tableName = TableName.MAIN.name;

  private String mappingId;
  private String externalId;
  private String path;
  private String fileId;
  private String returnId;

  private boolean shouldBeSaved = false;

  /**
   * Instantiates a new Next cloud mapping.
   *
   * @param mappingId  the mapping id
   * @param externalId the external id
   * @param path       the path
   * @param fileId     the file id
   */
  public NextCloudMapping(String mappingId, String externalId, String path, String fileId) {
    this.mappingId = mappingId;
    this.externalId = externalId;
    this.path = path;
    this.fileId = fileId;
  }

  /**
   * Instantiates a new Next cloud mapping.
   *
   * @param api the api
   * @param res the res
   */
  public NextCloudMapping(JsonObject api, DavResource res) {
    String path = getScopedPath(api, res.getPath());

    Map<String, String> props = res.getCustomProps();
    // owncloud / nextcloud
    if (props.containsKey(Field.ID.getName()) && !props.get(Field.ID.getName()).isEmpty()) {
      String id = props.get(Field.ID.getName());
      this.mappingId = id;
      this.externalId = api.getString(Field.EXTERNAL_ID.getName());
      this.path = path;
      this.fileId = getFileId(res);
      this.returnId = id;
      this.shouldBeSaved = true;
    } else {
      this.returnId = getResourceId(api, path);
    }
  }

  /**
   * Instantiates a new Next cloud mapping.
   *
   * @param api  the api
   * @param path the path
   */
  public NextCloudMapping(JsonObject api, String path) {

    this.returnId = getResourceId(api, path);
  }

  /**
   * Gets file id.
   *
   * @param res the res
   * @return the file id
   */
  public static String getFileId(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    // owncloud / nextcloud
    if (props.containsKey("fileid") && !props.get("fileid").isEmpty()) {
      return props.get("fileid");
    }
    return "";
  }

  /**
   * Gets scoped path.
   *
   * @param api the api
   * @param url the url
   * @return the scoped path
   */
  public static String getScopedPath(JsonObject api, String url) {
    String path = url;
    if (path.startsWith(api.getString(Field.PREFIX.getName()))) {
      path = path.substring(api.getString(Field.PREFIX.getName()).length());
    }
    if (path.endsWith("/")) {
      path = path.length() == 1 ? "" : path.substring(0, path.length() - 1);
    }
    return path;
  }

  /**
   * Save list of mappings.
   *
   * @param list the list
   */
  public static void saveListOfMappings(List<NextCloudMapping> list) {
    if (Utils.isListNotNullOrEmpty(list)) {
      new Thread(() -> {
            AWSXRay.beginSegment("NextCloudMapping.saveListOfMappings");
            batchWriteListItems(
                list.stream()
                    .filter(Objects::nonNull)
                    .map(NextCloudMapping::toDynamoDBItem)
                    .collect(Collectors.toList()),
                tableName,
                DataEntityType.NEXTCLOUD_MAPPING);
            AWSXRay.endSegment();
          })
          .start();
    }
  }

  public static String getPureFilePath(String path, String email) {
    return path.replace("/remote.php/dav/files/", "").replace(email, "");
  }

  /**
   * Gets resource path.
   *
   * @param api the api
   * @param id  the id
   * @return the resource path
   */
  public static String getResourcePath(JsonObject api, String id) {
    String path = api.getString(Field.PATH.getName());
    if (!Utils.isStringNotNullOrEmpty(path)) {
      path = "/remote.php/dav/files/".concat(api.getString(Field.EMAIL.getName()));
    }

    if (!Utils.isStringNotNullOrEmpty(id) || id.equals(Field.MINUS_1.getName())) {
      return path;
    }

    // old style id
    if (id.contains(":")) {
      return id.replaceAll(":", "/");
    }

    Item item = getCachedMapping(id, api.getString(Field.EXTERNAL_ID.getName()));
    if (item != null) {
      return item.getString(Field.PATH.getName());
    } else {
      // We cannot do this right now. Searching is only allowed for {http://owncloud.org/ns}fileid
      // and not {http://owncloud.org/ns}id
      // We could alternatively populate all fileIds

      // todo try to search

      //      try{
      //        SardineImpl2 sardine = SardineFactory.begin(api.getString(Field.EMAIL.getName()),
      // api.getString(Field.PASSWORD.getName()));
      //        List<DavResource> results =
      // sardine.searchForPath(api.getString(Field.URL.getName()), id,
      // api.getString(Field.EMAIL.getName()));
      //        if (!results.isEmpty()){
      //          String path = getScopedPath(api, results.get(0).getPath());
      //          setResourceId(api, id, getScopedPath(api, path));
      //          return path;
      //        }
      //      } catch(IOException e) {
      //        e.printStackTrace();
      //      }
      return null;
    }
  }

  /**
   * Gets resource file id.
   *
   * @param api the api
   * @param id  the id
   * @return the resource file id
   */
  public static String getResourceFileId(JsonObject api, String id) {
    Item item = getCachedMapping(id, api.getString(Field.EXTERNAL_ID.getName()));
    if (item != null && item.hasAttribute("fileid")) {
      return item.getString("fileid");
    }
    return "";
  }

  /**
   * Unset resource id.
   *
   * @param api the api
   * @param id  the id
   */
  public static void unsetResourceId(JsonObject api, String id) {
    deleteItem(
        tableName,
        pk,
        NEXTCLOUD_MAPPING + id,
        sk,
        api.getString(Field.EXTERNAL_ID.getName()),
        DataEntityType.NEXTCLOUD_MAPPING);
  }

  /**
   * Remove all mappings.
   *
   * @param externalId the external id
   */
  public static void removeAllMappings(String externalId) {
    Iterator<Item> mappings = getMappings(externalId);

    while (mappings.hasNext()) {
      Item mapping = mappings.next();
      deleteMapping(mapping);
    }
  }

  private static Item getCachedMapping(String id, String externalId) {
    return getItemFromCache(NEXTCLOUD_MAPPING + id, externalId, DataEntityType.NEXTCLOUD_MAPPING);
  }

  private static Iterator<Item> getMappings(String externalId) {
    return query(
            tableName,
            "sk-pk-index",
            new QuerySpec()
                .withKeyConditionExpression("sk = :sk and begins_with(pk, :pk)")
                .withValueMap(
                    new ValueMap().with(":sk", externalId).with(":pk", NEXTCLOUD_MAPPING)),
            DataEntityType.NEXTCLOUD_MAPPING)
        .iterator();
  }

  private static void deleteMapping(Item mapping) {
    deleteItem(
        tableName,
        pk,
        mapping.getString(pk),
        sk,
        mapping.getString(sk),
        DataEntityType.NEXTCLOUD_MAPPING);
  }

  private static Iterator<Item> getMappingsForPath(String externalId, String path) {
    return query(
            tableName,
            "sk-pk-index",
            new QuerySpec()
                .withKeyConditionExpression("sk = :sk and begins_with(pk, :pk)")
                .withFilterExpression("#path = :path")
                .withValueMap(new ValueMap()
                    .with(":sk", externalId)
                    .with(":pk", NEXTCLOUD_MAPPING)
                    .with(":path", path))
                .withNameMap(new NameMap().with("#path", Field.PATH.getName())),
            DataEntityType.NEXTCLOUD_MAPPING)
        .iterator();
  }

  /**
   * Gets return id.
   *
   * @return the return id
   */
  public String getReturnId() {
    return returnId;
  }

  /**
   * Is should be saved boolean.
   *
   * @return the boolean
   */
  public boolean isShouldBeSaved() {
    return shouldBeSaved;
  }

  /**
   * Update.
   */
  public void update() {
    this.shouldBeSaved = true;
    this.save();
  }

  /**
   * Save.
   */
  public void save() {
    if (this.shouldBeSaved) {
      Item mapping = this.toDynamoDBItem();
      putItem(mapping, DataEntityType.NEXTCLOUD_MAPPING);
    }
  }

  private String getResourceId(JsonObject api, String path) {

    if (path.startsWith(api.getString(Field.PREFIX.getName()))) {
      path = path.substring(api.getString(Field.PREFIX.getName()).length());
    }
    if (path.endsWith("/")) {
      path = path.length() == 1 ? "" : path.substring(0, path.length() - 1);
    }
    if (path.endsWith("remote.php/dav/files/" + api.getString(Field.EMAIL.getName()))
        || path.equals(api.getString(Field.PATH.getName()))) {
      return Field.MINUS_1.getName();
    }
    // we need to retrieve or generate a new id
    Iterator<Item> ids = getMappingsForPath(api.getString(Field.EXTERNAL_ID.getName()), path);
    if (ids.hasNext()) {
      // we found a record
      return ids.next().getString(Field.PK.getName()).substring(NEXTCLOUD_MAPPING.length());
    } else {
      try {
        String id = UUID.randomUUID().toString();
        this.mappingId = id;
        this.externalId = api.getString(Field.EXTERNAL_ID.getName());
        this.path = path;
        this.shouldBeSaved = true;
        return id;
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    return "";
  }

  private Item toDynamoDBItem() {
    Item mapping = new Item()
        .withPrimaryKey(
            Field.PK.getName(), NEXTCLOUD_MAPPING + mappingId, Field.SK.getName(), externalId)
        .withString(Field.PATH.getName(), path);

    if (Utils.isStringNotNullOrEmpty(fileId)) {
      mapping.withString("fileid", fileId);
    }
    return mapping;
  }
}
