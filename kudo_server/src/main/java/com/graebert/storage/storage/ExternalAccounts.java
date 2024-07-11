package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import java.util.Iterator;
import java.util.List;

public class ExternalAccounts extends Dataplane {

  public static final String tableName = TableName.MAIN.name;
  public static final String prefix = "FOREIGN_USER#";
  private static final String tableIndex = "ftype-expires-index";

  public static void saveExternalAccount(
      final String userId, final String externalId, final Item accountInfo) {
    accountInfo.withString(Dataplane.pk, prefix + userId);
    accountInfo.withString(Dataplane.sk, externalId);
    putItem(tableName, accountInfo, DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static void updateAccessTokens(
      String userId, String externalId, String accessToken, String refreshToken, Long expires) {
    updateExternalAccount(
        userId,
        externalId,
        "set #n1 = :n1, #n2 = :n2, #n3 = :n3",
        new NameMap()
            .with("#n1", Field.REFRESH_TOKEN.getName())
            .with("#n2", Field.ACCESS_TOKEN.getName())
            .with("#n3", Field.EXPIRES.getName()),
        new ValueMap()
            .withString(":n1", refreshToken)
            .withString(":n2", accessToken)
            .withLong(":n3", expires));
  }

  public static void updateRootNameSpaceId(
      final String userId, final String externalId, String rootNamespaceId) {
    updateExternalAccount(
        userId,
        externalId,
        "set #rnsi = :rootNamespaceId",
        new NameMap().with("#rnsi", "rootNamespaceId"),
        new ValueMap().withString(":rootNamespaceId", rootNamespaceId));
  }

  public static void updateDisableThumbnail(
      final String userId, final String externalId, boolean disableThumbnail) {
    updateExternalAccount(
        userId,
        externalId,
        "set #dt = :dtVal",
        new NameMap().with("#dt", "disableThumbnail"),
        new ValueMap().withBoolean(":dtVal", disableThumbnail));
  }

  private static void updateExternalAccount(
      final String userId,
      final String externalId,
      final String updateExpression,
      final NameMap nameMap,
      final ValueMap valueMap) {

    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(Dataplane.pk, prefix + userId, Dataplane.sk, externalId)
        .withUpdateExpression(updateExpression);

    if (nameMap != null) {
      updateItemSpec.withNameMap(nameMap);
    }

    if (valueMap != null) {
      updateItemSpec.withValueMap(valueMap);
    }

    updateItem(tableName, updateItemSpec, DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static Item getExternalAccount(final String userId, final String externalId) {
    return getItemFromCache(
        tableName,
        Dataplane.pk,
        prefix + userId,
        Dataplane.sk,
        externalId,
        DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static void deleteExternalAccount(final String userId, final String externalId) {
    deleteItem(
        tableName,
        Dataplane.pk,
        prefix + userId,
        Dataplane.sk,
        externalId,
        DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static Item formExternalAccountItem(final String userId, final String externalId) {
    return new Item().withPrimaryKey(Dataplane.pk, prefix + userId, Dataplane.sk, externalId);
  }

  public static ItemCollection<QueryOutcome> getAllExternalAccountsForUser(final String userId) {
    return query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :userId")
            .withValueMap(new ValueMap().withString(":userId", prefix + userId)),
        DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static Iterator<Item> getExternalAccountsByUserId(
      final String userId, final StorageType storageType) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :val")
                .withFilterExpression("ftype = :ftype")
                .withValueMap(new ValueMap()
                    .withString(":val", prefix + userId)
                    .withString(":ftype", storageType.toString())),
            DataEntityType.EXTERNAL_ACCOUNTS)
        .iterator();
  }

  public static Iterator<Item> getExternalAccountsByUserId(
      final String userId, final String storagePrefix) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :val")
                .withFilterExpression("begins_with(ftype,:ftype)")
                .withValueMap(new ValueMap()
                    .withString(":val", prefix + userId)
                    .withString(":ftype", storagePrefix)),
            DataEntityType.EXTERNAL_ACCOUNTS)
        .iterator();
  }

  public static void batchRemoveAccounts(final List<PrimaryKey> toRemove) {
    batchWriteItem(
        tableName,
        new TableWriteItems(getTableName(tableName))
            .withPrimaryKeysToDelete(toRemove.toArray(new PrimaryKey[0])),
        DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static Iterator<Item> getAllExternalAccountsForStorageType(final StorageType storageType) {
    return query(
            tableName,
            tableIndex,
            new QuerySpec().withHashKey(Field.F_TYPE.getName(), storageType.toString()),
            DataEntityType.EXTERNAL_ACCOUNTS)
        .iterator();
  }

  public static Iterator<Item> getAllExternalAccountsByUserAndStorageType(
      final String userId, final StorageType storageType) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withHashKey(Field.PK.getName(), prefix + userId)
                .withFilterExpression("ftype = :ftype")
                .withValueMap(new ValueMap().withString(":ftype", storageType.toString())),
            DataEntityType.EXTERNAL_ACCOUNTS)
        .iterator();
  }

  public static void cacheExternalAccount(Item item) {
    putItem(item, DataEntityType.EXTERNAL_ACCOUNTS);
  }

  // didenko need to check this
  public static Item getCachedExternalAccount(
      String externalId, StorageType storageType, String fileId) {
    return getItemFromCache(
        "externalid#" + externalId,
        StorageType.getShort(storageType) + fileId,
        DataEntityType.EXTERNAL_ACCOUNTS);
  }

  public static String getUserIdFromPk(String pkValue) {
    return pkValue.substring(prefix.length());
  }

  public static String getExternalEmail(final String userId, final String externalId) {
    return getExternalAccount(userId, externalId).getString(Field.EMAIL.getName());
  }
}
