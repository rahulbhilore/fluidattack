package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class StorageDAO extends Dataplane {

  private static final String tableName = emptyString;
  public static final String storagePrefix = "STORAGE#";
  private static final String skValue = Field.INFO.getName();
  private static final String skPkIndex = "sk-pk-index";

  public static void createOrUpdateStorageAccess(
      Message<JsonObject> message,
      StorageType storageType,
      String userId,
      boolean disable,
      JsonArray newExcludedUsers,
      boolean overrideUsers,
      JsonArray errors) {
    List<String> usersToExclude = newExcludedUsers.stream()
        .map(u -> {
          if (Utils.isEmail((String) u)) {
            Iterator<Item> userIterator = Users.getUserByEmail((String) u);
            if (userIterator.hasNext()) {
              return userIterator.next().getString(sk);
            }
          } else {
            Item user = Users.getUserById((String) u);
            return user.getString(sk);
          }
          errors.add(new JsonObject()
              .put(Field.USER.getName(), u)
              .put(
                  Field.ERROR_MESSAGE.getName(),
                  Utils.getLocalizedString(message, "NoSuchUserInTheDatabase")));
          return null;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
    if (newExcludedUsers.size() != usersToExclude.size()) {
      log.warn(
          "ENABLE/DISABLE STORAGE : Few excluded users could not be added - " + newExcludedUsers);
    }
    Item storage = getStorage(storageType);
    if (Objects.isNull(storage)) {
      storage = new Item()
          .withString(pk, storagePrefix + storageType)
          .withString(sk, skValue)
          .withBoolean("disable", disable)
          .withList("excludedUsers", usersToExclude)
          .withLong("lastUpdated", GMTHelper.utcCurrentTime());

      if (Utils.isStringNotNullOrEmpty(userId)) {
        storage.withString(Field.USER_ID.getName(), userId);
      }
      putItem(tableName, storage, DataEntityType.STORAGES);
    } else {
      if (!overrideUsers
          && storage.hasAttribute("excludedUsers")
          && Utils.isListNotNullOrEmpty(storage.getList("excludedUsers"))) {
        usersToExclude.addAll(storage.getList("excludedUsers"));
      }
      NameMap nameMap = new NameMap().with("#lastUpdated", "lastUpdated");
      ValueMap valueMap = new ValueMap().withLong(":lastUpdated", GMTHelper.utcCurrentTime());
      String updateExpression = "set #lastUpdated = :lastUpdated";
      if (!storage.hasAttribute("disable") || disable != storage.getBoolean("disable")) {
        updateExpression += ", #disable = :disable";
        nameMap.with("#disable", "disable");
        valueMap.withBoolean(":disable", disable);
      }
      if ((!storage.hasAttribute(Field.USER_ID.getName())
              || !userId.equals(storage.getString(Field.USER_ID.getName())))
          && Utils.isStringNotNullOrEmpty(userId)) {
        updateExpression += ", #userId = :userId";
        nameMap.with("#userId", Field.USER_ID.getName());
        valueMap.withString(":userId", userId);
      }
      updateExpression += ", #excludedUsers = :excludedUsers";
      nameMap.with("#excludedUsers", "excludedUsers");
      valueMap.withList(
          ":excludedUsers", usersToExclude.stream().distinct().collect(Collectors.toList()));
      UpdateItemSpec updateItemSpec = new UpdateItemSpec()
          .withPrimaryKey(pk, storage.getString(pk), sk, skValue)
          .withUpdateExpression(updateExpression)
          .withNameMap(nameMap)
          .withReturnValues(ReturnValue.ALL_NEW)
          .withValueMap(valueMap);

      updateItem(tableName, updateItemSpec, DataEntityType.STORAGES);
    }
  }

  public static Iterator<Item> getAllDisabledStorages() {
    return query(
            tableName,
            skPkIndex,
            new QuerySpec()
                .withKeyConditionExpression("#sk = :sk and begins_with(#pk, :pk)")
                .withFilterExpression("#disable = :disable")
                .withNameMap(
                    new NameMap().with("#pk", pk).with("#sk", sk).with("#disable", "disable"))
                .withValueMap(new ValueMap()
                    .withString(":pk", storagePrefix)
                    .withString(":sk", skValue)
                    .withBoolean(":disable", true)),
            DataEntityType.STORAGES)
        .iterator();
  }

  private static Item getStorage(StorageType storageType) {
    return getItem(
        tableName, pk, storagePrefix + storageType, sk, skValue, true, DataEntityType.STORAGES);
  }

  public static boolean isDisabled(StorageType storageType, String userId) {
    Item storage = getStorage(storageType);
    if (Objects.nonNull(storage)
        && storage.hasAttribute("disable")
        && storage.getBoolean("disable")) {
      return !Utils.isStringNotNullOrEmpty(userId)
          || !storage.hasAttribute("excludedUsers")
          || !storage.getList("excludedUsers").contains(userId);
    }
    return false;
  }
}
