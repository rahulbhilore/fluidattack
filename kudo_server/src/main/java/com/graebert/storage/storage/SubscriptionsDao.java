package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NonNls;

public class SubscriptionsDao extends Dataplane {

  @NonNls
  public static final String tableName = TableName.MAIN.name;

  private static final String tableIndex = "sk-pk-index";

  public static Item getSubscription(String userId, String fileId) {
    return getItemFromCache(
        tableName,
        Field.PK.getName(),
        "subscriptions#" + fileId,
        Field.SK.getName(),
        userId,
        DataEntityType.SUBSCRIPTION);
  }

  public static JsonArray getSubscriptionsJson(String fileId, String requiredScope) {
    NameMap nm = new NameMap().with("#st", Field.STATE.getName());
    ValueMap vm = new ValueMap()
        .withString(":state", Subscriptions.subscriptionState.ACTIVE.name())
        .withString(":pk", "subscriptions#" + fileId);

    QuerySpec qs =
        new QuerySpec().withKeyConditionExpression("pk = :pk").withFilterExpression("#st = :state");

    if (requiredScope != null && !requiredScope.isEmpty()) {
      // apparently filter expressions are overridden on each call
      qs.withFilterExpression(
          "#st = :state and ( contains (#sc, :scope) or contains (#sc, :global) )");
      nm.with("#sc", "scope");
      vm.withString(":scope", requiredScope)
          .withString(":global", Subscriptions.subscriptionScope.GLOBAL.toString());
    }

    qs.withValueMap(vm).withNameMap(nm);
    JsonArray resultObject = new JsonArray();
    for (Item item : query(tableName, null, qs, DataEntityType.SUBSCRIPTION)) {
      resultObject.add(new JsonObject(item.toJSON()));
    }

    return resultObject;
  }

  public static Iterator<Item> getPublicSubscriptions(
      String fileId, String token, Subscriptions.subscriptionState state) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk=:pk")
                .withFilterExpression("#st=:state and #token=:token")
                .withNameMap(new NameMap()
                    .with("#st", Field.STATE.getName())
                    .with("#token", Field.TOKEN.getName()))
                .withValueMap(new ValueMap()
                    .withString(":state", state.name())
                    .withString(":token", token)
                    .withString(":pk", "subscriptions#" + fileId)),
            DataEntityType.SUBSCRIPTION)
        .iterator();
  }

  public static void putSubscription(Item subscription) {
    putItem(
        tableName,
        subscription.getString(Field.PK.getName()),
        subscription.getString(Field.SK.getName()),
        subscription,
        DataEntityType.SUBSCRIPTION);
  }

  public static void deleteSubscription(Item subscription) {
    deleteFromCache(
        tableName,
        subscription.getString(Field.PK.getName()),
        subscription.getString(Field.SK.getName()),
        DataEntityType.SUBSCRIPTION);
  }

  public static void deleteUserSubscriptions(String userId) {
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return;
    }
    deleteUserData(
        query(
                tableName,
                tableIndex,
                new QuerySpec()
                    .withKeyConditionExpression("sk = :user and begins_with(pk, :sub)")
                    .withValueMap(new ValueMap()
                        .withString(":user", userId)
                        .withString(":sub", "subscriptions")),
                DataEntityType.SUBSCRIPTION)
            .iterator(),
        tableName,
        DataEntityType.SUBSCRIPTION);
  }

  public static Iterator<Item> getStatsCommentsForUser(String userId) {
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return null;
    }
    return query(
            tableName,
            tableIndex,
            new QuerySpec()
                .withHashKey(sk, userId)
                .withFilterExpression("begins_with(pk, :pk)")
                .withValueMap(new ValueMap().withString(":pk", "subscriptions#")),
            DataEntityType.SUBSCRIPTION)
        .iterator();
  }

  public static Item updateScopeAndToken(
      List<String> oldScope,
      Subscriptions.scopeUpdate scopeUpdateMode,
      String token,
      Item oldSubscription) {
    List<String> scope = oldScope;
    if (scopeUpdateMode.equals(Subscriptions.scopeUpdate.APPEND)
        && oldSubscription.hasAttribute("scope")
        && oldSubscription.getList("scope") != null) {
      // merge scopes
      scope.addAll(oldSubscription.getList("scope"));
      // remove duplicates
      scope = scope.stream().distinct().collect(Collectors.toList());
    }
    String updateExpression = "set #sc = :s ";
    Item subscription = oldSubscription;
    ValueMap valueMap = new ValueMap().withList(":s", scope);
    NameMap nameMap = new NameMap().with("#sc", "scope");
    if (scope.contains(Subscriptions.subscriptionScope.GLOBAL.toString())
        && subscription.hasAttribute(Field.TOKEN.getName())) {
      // we should remove the token if access is global
      updateExpression += "remove #tk";
      nameMap.with("#tk", Field.TOKEN.getName());
    } else if (Utils.isStringNotNullOrEmpty(token)) {
      // we should update token if it has changed
      updateExpression += ",#tk = :t";
      valueMap.withString(":t", token);
      nameMap.with("#tk", Field.TOKEN.getName());
    }
    subscription = updateSubscription(
        new PrimaryKey(pk, subscription.getString(pk), sk, subscription.getString(sk)),
        updateExpression,
        valueMap,
        nameMap);
    return subscription;
  }

  public static Item setSubscriptionState(String state, String pkValue, String skValue) {
    return updateSubscription(
        new PrimaryKey(pk, pkValue, sk, skValue),
        "set #st = :s",
        new ValueMap().withString(":s", state),
        new NameMap().with("#st", Field.STATE.getName()));
  }

  private static Item updateSubscription(
      PrimaryKey primaryKey, String updateExpression, ValueMap valueMap, NameMap nameMap) {
    return updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(primaryKey)
                .withUpdateExpression(updateExpression)
                .withValueMap(valueMap)
                .withNameMap(nameMap),
            DataEntityType.SUBSCRIPTION)
        .getItem();
  }

  // to be removed
  public static Iterator<Item> getStatsComments() {
    // TODO: SCAN stats
    return scan(
            tableName,
            null,
            new ScanSpec()
                .withFilterExpression("begins_with(pk, :pk)")
                .withValueMap(new ValueMap().withString(":pk", "subscriptions#")),
            DataEntityType.SUBSCRIPTION)
        .iterator();
  }

  public static Iterator<Item> getFileSubscriptions(String fileId) {
    NameMap nm = new NameMap().with("#st", Field.STATE.getName()).with("#sc", "scope");

    ValueMap vm = new ValueMap()
        .withString(":state", Subscriptions.subscriptionState.ACTIVE.name())
        .withString(":pk", "subscriptions#" + fileId)
        .withString(":global", Subscriptions.subscriptionScope.GLOBAL.toString());

    QuerySpec qs = new QuerySpec()
        .withKeyConditionExpression("pk = :pk")
        .withFilterExpression("#st = :state and contains (#sc, :global)")
        .withValueMap(vm)
        .withNameMap(nm);

    return query(tableName, null, qs, DataEntityType.SUBSCRIPTION).iterator();
  }
}
