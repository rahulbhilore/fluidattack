package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NonNls;

public class TempData extends Dataplane {
  @NonNls
  private static final String tableName = TableName.TEMP.name;

  private static final String pk = Field.PK.getName();
  private static final String sk = Field.SK.getName();

  // for stats
  private static final String statsTable = TableName.STATS.name;

  // nonce
  private static final String noncePrefix = "nonce#";

  // nonce
  public static void deleteNonce(String nonce) {
    deleteItem(tableName, pk, noncePrefix + nonce, sk, nonce, DataEntityType.TEMP_DATA);
  }

  public static Item getNonce(String nonce) {
    return getItem(tableName, pk, noncePrefix + nonce, sk, nonce, true, DataEntityType.TEMP_DATA);
  }

  public static void saveNonce(String nonce) {
    putItem(
        tableName,
        new Item()
            .withPrimaryKey(pk, noncePrefix + nonce, sk, nonce)
            .withLong(Field.TTL.getName(), (int)
                (new Date().getTime() / 1000 + TimeUnit.HOURS.toSeconds(2))),
        DataEntityType.TEMP_DATA);
  }

  // saml
  public static Item getSaml(String samlId) {
    return getItem(tableName, pk, "saml", sk, samlId, false, DataEntityType.TEMP_DATA);
  }

  public static void saveSaml(String token, String samlResponse) {
    putItem(
        new Item()
            .withPrimaryKey(pk, "saml", sk, token)
            .withLong(Field.TTL.getName(), (int) (new Date().getTime() / 1000 + 60 * 10))
            .withString("samlResponse", samlResponse),
        DataEntityType.TEMP_DATA);
  }

  public static void deleteSaml(String samlId) {
    deleteItem(tableName, pk, "saml", sk, samlId, DataEntityType.TEMP_DATA);
  }

  // box
  public static Item getBoxUserFromCache(String externalId) {
    return getItemFromCache(
        tableName, pk, "box#" + externalId, sk, externalId, DataEntityType.TEMP_DATA);
  }

  public static void putBoxUser(String boxId, Item boxUser) {
    putItem(tableName, "box#" + boxId, boxId, boxUser, DataEntityType.TEMP_DATA);
  }

  public static void putBoxUser(Item boxUser) {
    putItem(
        tableName, boxUser.getString(pk), boxUser.getString(sk), boxUser, DataEntityType.TEMP_DATA);
  }

  // stats
  public static Iterator<Item> getCachedLinks(boolean withJob, String jobId) {
    // TODO: SCAN stats
    if (withJob) {
      return query(
              tableName,
              null,
              new QuerySpec()
                  .withKeyConditionExpression("pk = :pk")
                  .withValueMap(new ValueMap().withString(":pk", "expired_links#" + jobId)),
              DataEntityType.TEMP_DATA)
          .iterator();
    } else {
      return scan(
              tableName,
              null,
              new ScanSpec()
                  .withFilterExpression("begins_with(pk, :pk)")
                  .withValueMap(new ValueMap().withString(":pk", "expired_links#")),
              DataEntityType.TEMP_DATA)
          .iterator();
    }
  }

  public static JsonArray getPerformanceStatsJson(String userId, long from, long to) {
    // TODO: SCAN stats
    StringBuilder filter = new StringBuilder("begins_with(pk, :pk)");
    ValueMap vm = new ValueMap().withString(":pk", "perf#");
    if (from != -1) {
      filter.append(" and created >= :from");
      vm.withLong(":from", from);
    }
    if (to != -1) {
      filter.append(" and created <= :to");
      vm.withLong(":to", to);
    }
    if (Utils.isStringNotNullOrEmpty(userId)) {
      filter.append(" and userId = :uid");
      vm.withString(":uid", userId);
    }
    JsonArray resultJson = new JsonArray();
    for (Item stat : scan(
        statsTable,
        null,
        new ScanSpec().withFilterExpression(filter.toString()).withValueMap(vm),
        DataEntityType.TEMP_DATA)) {
      resultJson.add(new JsonObject(stat.toJSON()));
    }

    return resultJson;
  }

  public static void savePerformanceStat(Item item) {
    putItem("perf_stats", item, DataEntityType.TEMP_DATA);
  }
}
