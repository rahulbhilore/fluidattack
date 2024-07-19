package com.graebert.storage.thumbnails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

public class ThumbnailsDao extends Dataplane {

  private static final String disableThumbnailPrefix = "thumbnail_disabled";
  private static final String tableName = TableName.MAIN.name;
  private static final String disabledUrlsListFieldName = "disabledUrls";
  private static final String disabledStorageListFieldName = Field.DISABLED_STORAGES.getName();
  private static Item disabledThumbnailsItem;

  // jsonKey is Field.CHUNK.getName() or Field.INFO.getName()
  // Map< String groupName, Map< String chunkId, Map< String jsonKey, Object jsonValue > > >
  private static ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>>
      thumbnailChunks;

  public static void saveThumbnailChunkOnServer(
      String groupName, String chunkId, JsonObject body, List<JsonObject> chunk, boolean add) {
    if (thumbnailChunks == null) {
      thumbnailChunks = new ConcurrentHashMap<>();
    }

    ConcurrentMap<String, ConcurrentMap<String, Object>> chunks =
        thumbnailChunks.containsKey(groupName)
            ? thumbnailChunks.get(groupName)
            : new ConcurrentHashMap<>();

    // add chunk to chunks of groupName
    if (add) {
      chunks.put(
          chunkId,
          new ConcurrentHashMap<>(new JsonObject()
              .put(Field.CHUNK.getName(), chunk)
              .put(Field.INFO.getName(), body)
              .getMap()));
      thumbnailChunks.put(groupName, chunks);
    }
    // remove chunk from chunks of groupName
    else {
      if (chunks.containsKey(chunkId)) {
        chunks.remove(chunkId);
        if ((thumbnailChunks.get(groupName)).isEmpty() || chunks.isEmpty()) {
          thumbnailChunks.remove(groupName);
        } else {
          thumbnailChunks.put(groupName, chunks);
        }
      }
    }
  }

  public static ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>>
      getAllThumbnailChunks() {
    return thumbnailChunks;
  }

  //    public static JsonObject getThumbnailChunkForGroupName(String groupName) {
  //        if (thumbnailChunks.containsKey(groupName)) {
  //            Map chunks = (Map) thumbnailChunks.get(groupName);
  //            //randomly getting any chunk for processing
  //            if (chunks.keySet().iterator().hasNext()) {
  //                String chunkId = (String) chunks.keySet().iterator().next();
  //                return new JsonObject().put("chunkId", chunkId).put(Field.CHUNK.getName(),
  // chunks.get(chunkId));
  //            }
  //        }
  //        return null;
  //    }

  public static boolean areNoChunksProcessing(String groupName, String storageType) {
    Iterator<Item> chunkRequests =
        SQSRequests.getThumbnailChunkRequestForGroupName(groupName, storageType);
    return !chunkRequests.hasNext();
  }

  public static void updateThumbnailGenerationCache(
      String url, String type, boolean doDisableThumbnail) {
    if (disabledThumbnailsItem == null) {
      List<ConcurrentMap<String, String>> urls;
      List<String> storages;
      disabledThumbnailsItem = getThumbnailCacheItem();
      if (disabledThumbnailsItem == null) {
        disabledThumbnailsItem =
            new Item().withPrimaryKey(pk, disableThumbnailPrefix, sk, disableThumbnailPrefix);
        if (url == null) {
          storages = new ArrayList<>();
          if (doDisableThumbnail) {
            storages.add(type);
          }
          disabledThumbnailsItem.withList(disabledStorageListFieldName, storages);
        } else {
          urls = new ArrayList<>();

          if (doDisableThumbnail) {
            urls.add(new ConcurrentHashMap<>() {
              {
                put(Field.URL.getName(), url);
                put(Field.TYPE.getName(), type);
              }
            });
          }
          disabledThumbnailsItem.withList(disabledUrlsListFieldName, urls);
        }
        putItem(disabledThumbnailsItem, DataEntityType.THUMBNAILS);
        return;
      }
    }
    setDisabledThumbnailCache(url, type, doDisableThumbnail);
  }

  private static void setDisabledThumbnailCache(
      String url, String type, boolean doDisableThumbnail) {
    String updateExpression = "";
    NameMap nameMap = new NameMap();
    ValueMap valueMap = new ValueMap();
    boolean updateRequired = false;
    if (url == null) {
      List<String> storages = disabledThumbnailsItem.hasAttribute(disabledStorageListFieldName)
          ? disabledThumbnailsItem.getList(disabledStorageListFieldName)
          : new ArrayList<>();
      if (doDisableThumbnail) {
        if (!storages.contains(type)) {
          storages.add(type);
          updateRequired = true;
        }
      } else {
        updateRequired = storages.remove(type);
      }

      if (updateRequired) {
        updateExpression = "set #storages = :storages";
        nameMap.with("#storages", Field.DISABLED_STORAGES.getName());
        valueMap.withList(":storages", storages);
      }

    } else {
      List<ConcurrentMap<String, String>> urls =
          disabledThumbnailsItem.hasAttribute(disabledUrlsListFieldName)
              ? disabledThumbnailsItem.getList(disabledUrlsListFieldName)
              : new ArrayList<>();

      ConcurrentMap<String, String> obj = new ConcurrentHashMap<>() {
        {
          put(Field.URL.getName(), url);
          put(Field.TYPE.getName(), type);
        }
      };

      if (doDisableThumbnail) {
        if (!urls.contains(obj)) {
          urls.add(obj);
          updateRequired = true;
        }
      } else {
        updateRequired = urls.remove(obj);
      }

      if (updateRequired) {
        updateExpression = "set #urls = :urls";
        nameMap.with("#urls", "disabledUrls");
        valueMap.withList(":urls", urls);
      }
    }

    if (!updateExpression.isEmpty()) {
      disabledThumbnailsItem = updateItem(
              tableName,
              new UpdateItemSpec()
                  .withPrimaryKey(pk, disableThumbnailPrefix, sk, disableThumbnailPrefix)
                  .withUpdateExpression(updateExpression)
                  .withNameMap(nameMap)
                  .withValueMap(valueMap),
              DataEntityType.THUMBNAILS)
          .getItem();
    }
  }

  private static JsonObject getDisabledThumbnailCache() {
    if (disabledThumbnailsItem == null) {
      Item item = getThumbnailCacheItem();
      if (item != null) {
        return new JsonObject(item.toJSON());
      }
      return null;
    }
    return new JsonObject(disabledThumbnailsItem.toJSON());
  }

  private static Item getThumbnailCacheItem() {
    return getItem(
        tableName,
        pk,
        disableThumbnailPrefix,
        sk,
        disableThumbnailPrefix,
        true,
        DataEntityType.THUMBNAILS);
  }

  public static void setThumbnailGeneration(Item item, final boolean disableThumbnail) {
    String userId = item.hasAttribute(Field.FLUORINE_ID.getName())
        ? item.getString(Field.FLUORINE_ID.getName())
        : ExternalAccounts.getUserIdFromPk(item.getString(Field.PK.getName()));
    ExternalAccounts.updateDisableThumbnail(
        userId, item.getString(Field.SK.getName()), disableThumbnail);
  }

  public static boolean isThumbnailGenerationDisabled(
      boolean isUserThumbnailDisabled, boolean isAccountThumbnailDisabled) {
    return (isUserThumbnailDisabled || isAccountThumbnailDisabled);
  }

  public static boolean doThumbnailDisabledForStorageOrURL(Item externalAccount) {
    JsonObject cache = ThumbnailsDao.getDisabledThumbnailCache();
    boolean doDisableThumbnail = false;
    if (cache != null) {
      if (cache.containsKey("disabledUrls") && externalAccount.hasAttribute(Field.URL.getName())) {
        List<JsonObject> disabledUrls = cache.getJsonArray("disabledUrls").getList();
        Predicate<JsonObject> condition = urlObj -> urlObj
                .getString(Field.URL.getName())
                .equals(externalAccount.getString(Field.URL.getName()))
            && urlObj
                .getString(Field.TYPE.getName())
                .equals(externalAccount.getString(Field.F_TYPE.getName()).toLowerCase());
        doDisableThumbnail = disabledUrls.stream().anyMatch(condition);
      }
      if (!doDisableThumbnail) {
        if (cache.containsKey(Field.DISABLED_STORAGES.getName())) {
          if (cache
              .getJsonArray(Field.DISABLED_STORAGES.getName())
              .contains(externalAccount.getString(Field.F_TYPE.getName()).toLowerCase())) {
            doDisableThumbnail = true;
          }
        }
      }
    }
    return doDisableThumbnail;
  }
}
