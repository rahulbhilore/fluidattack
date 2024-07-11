package com.graebert.storage.integration.webdav;

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
import java.util.UUID;
import java.util.stream.Collectors;

public class WebDavMapping extends Dataplane {

  private static final String WEBDAV_MAPPING = "webdavmapping";
  private static final String tableName = TableName.MAIN.name;

  private String mappingId;
  private String externalId;
  private String path;
  private String fileId;
  private String returnId;

  private boolean shouldBeSaved = false;

  public WebDavMapping(String mappingId, String externalId, String path, String fileId) {
    this.mappingId = mappingId;
    this.externalId = externalId;
    this.path = path;
    this.fileId = fileId;
  }

  public WebDavMapping(JsonObject api, DavResource res) {
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

  public WebDavMapping(JsonObject api, String path) {

    this.returnId = getResourceId(api, path);
  }

  public static String getFileId(DavResource res) {
    Map<String, String> props = res.getCustomProps();
    // owncloud / nextcloud
    if (props.containsKey("fileid") && !props.get("fileid").isEmpty()) {
      return props.get("fileid");
    }
    return "";
  }

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

  public static void saveListOfMappings(List<WebDavMapping> list) {
    if (Utils.isListNotNullOrEmpty(list)) {
      new Thread(() -> {
            AWSXRay.beginSegment("WebDavMapping.saveListOfMappings");
            batchWriteListItems(
                list.stream().map(WebDavMapping::toDynamoDBItem).collect(Collectors.toList()),
                tableName,
                DataEntityType.WEBDAV_MAPPING);
            AWSXRay.endSegment();
          })
          .start();
    }
  }

  public static String getResourcePath(JsonObject api, String id) {
    if (!Utils.isStringNotNullOrEmpty(id)) {
      return api.getString(Field.PATH.getName());
    }
    if (id.equals(Field.MINUS_1.getName())) {
      return api.getString(Field.PATH.getName());
    }
    if (id.contains(":")) // old style id
    {
      return id.replaceAll(":", "/");
    }
    Item item = getCachedMapping(id, api.getString(Field.EXTERNAL_ID.getName()));
    if (item != null) {
      return item.getString(Field.PATH.getName());
    } else {
      // We cannot do this right now. Searching is only allowed for {http://owncloud.org/ns}fileid
      // and not {http://owncloud.org/ns}id
      // We could alternatively populate all fileIds
      /*
      try{
          SardineImpl2 sardine = SardineFactory.begin(api.getString(Field.EMAIL.getName()), api.getString(Field.PASSWORD.getName()));
          List<DavResource> results = sardine.searchForPath(api.getString(Field.URL.getName()), id, api.getString(Field.EMAIL.getName()));
          if (!results.isEmpty()){
              String path = getScopedPath(api, results.get(0).getPath());
              setResourceId(api, id, getScopedPath(api, path));
              return path;
          }
      }
      catch(IOException e) {
          e.printStackTrace();
      }
      */
      return api.getString(Field.PATH.getName());
    }
  }

  public static String getResourceFileId(JsonObject api, String id) {
    Item item = getCachedMapping(id, api.getString(Field.EXTERNAL_ID.getName()));
    if (item != null && item.hasAttribute("fileid")) {
      return item.getString("fileid");
    }
    return "";
  }

  public static void unsetResourceId(JsonObject api, String id) {
    deleteItem(
        tableName,
        Dataplane.pk,
        WEBDAV_MAPPING + id,
        Dataplane.sk,
        api.getString(Field.EXTERNAL_ID.getName()),
        DataEntityType.WEBDAV_MAPPING);
  }

  public static void removeAllMappings(String externalId) {
    Iterator<Item> mappings = getMappings(externalId);

    while (mappings.hasNext()) {
      Item mapping = mappings.next();
      deleteMapping(mapping);
    }
  }

  private static Item getCachedMapping(String id, String externalId) {
    return getItemFromCache(WEBDAV_MAPPING + id, externalId, DataEntityType.WEBDAV_MAPPING);
  }

  private static Iterator<Item> getMappings(String externalId) {
    return query(
            tableName,
            "sk-pk-index",
            new QuerySpec()
                .withKeyConditionExpression("sk = :sk and begins_with(pk, :pk)")
                .withValueMap(new ValueMap().with(":sk", externalId).with(":pk", WEBDAV_MAPPING)),
            DataEntityType.WEBDAV_MAPPING)
        .iterator();
  }

  private static void deleteMapping(Item mapping) {
    deleteItem(
        tableName,
        Dataplane.pk,
        mapping.getString(Field.PK.getName()),
        Dataplane.sk,
        mapping.getString(Field.SK.getName()),
        DataEntityType.WEBDAV_MAPPING);
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
                    .with(":pk", WEBDAV_MAPPING)
                    .with(":path", path))
                .withNameMap(new NameMap().with("#path", Field.PATH.getName())),
            DataEntityType.WEBDAV_MAPPING)
        .iterator();
  }

  public String getReturnId() {
    return returnId;
  }

  public boolean isShouldBeSaved() {
    return shouldBeSaved;
  }

  public void update() {
    this.shouldBeSaved = true;
    this.save();
  }

  public void save() {
    if (this.shouldBeSaved) {
      Item mapping = this.toDynamoDBItem();
      putItem(mapping, DataEntityType.WEBDAV_MAPPING);
    }
  }

  private String getResourceId(JsonObject api, String path) {

    if (path.startsWith(api.getString(Field.PREFIX.getName()))) {
      path = path.substring(api.getString(Field.PREFIX.getName()).length());
    }
    if (path.endsWith("/")) {
      path = path.length() == 1 ? "" : path.substring(0, path.length() - 1);
    }
    if (path.equals(api.getString(Field.PATH.getName()))) {
      return Field.MINUS_1.getName();
    }
    // we need to retrieve or generate a new id
    Iterator<Item> ids = getMappingsForPath(api.getString(Field.EXTERNAL_ID.getName()), path);
    if (ids.hasNext()) {
      // we found a record
      return ids.next().getString(Field.PK.getName()).substring("webdavmapping".length());
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
            Field.PK.getName(), WEBDAV_MAPPING + mappingId, Field.SK.getName(), externalId)
        .withString(Field.PATH.getName(), path);

    if (Utils.isStringNotNullOrEmpty(fileId)) {
      mapping.withString("fileid", fileId);
    }
    return mapping;
  }
}
