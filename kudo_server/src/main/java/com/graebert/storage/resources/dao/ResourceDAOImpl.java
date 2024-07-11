package com.graebert.storage.resources.dao;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.graebert.storage.Entities.FontType;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.resources.ObjectFilter;
import com.graebert.storage.resources.ResourceOwnerType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.collections4.IteratorUtils;

public class ResourceDAOImpl extends Dataplane implements ResourceDAO {

  public static final String resourcesTable = TableName.RESOURCES.name;
  private static final String resourcePrefix = "RESOURCE".concat(hashSeparator);
  private static final String resourceSharePrefix = "SHARED".concat(hashSeparator);
  private static final String valueColonPrefix = ":";
  private static final String skPkIndex = "sk-pk-index";

  public ResourceDAOImpl() {}

  //
  //  @Override
  //  public boolean shareObject(Item itemToShare, String userId) {
  //    itemToShare.withString(sk, makeSkValue(itemToShare.getString(Field.OWNER_ID.getName()),
  //        ResourceType.getType(itemToShare.getString(Field.RESOURCE_TYPE.getName())),
  //        ResourceOwnerType.SHARED, userId));
  //    itemToShare.withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime());
  //    itemToShare.withString(Field.USER_ID.getName(), userId);
  //    itemToShare.removeAttribute(Field.UPDATED.getName());
  //    resourceTable.putItem(itemToShare);
  //    return true;
  //  }
  //
  //  @Override
  //  public boolean deleteObject(String folderId, String objectId, String ownerId, String owner,
  //                              ResourceType resourceType, String userId) {
  //    ResourceOwnerType ownerType = ResourceOwnerType.getType(owner);
  //
  //    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
  //      ownerId = emptyString;
  // ownerId = emptyString;
  //    }
  //
  //    resourceTable.deleteItem(pk, makePkValue(objectId, folderId), sk,
  //        makeSkValue(ownerId, resourceType, ownerType, userId));
  //    return true;
  //  }
  //
  private static String makeSkValue(
      String ownerId, String parent, ResourceOwnerType ownerType, String userId) {
    if (Utils.isStringNotNullOrEmpty(parent)
        && Utils.isStringNotNullOrEmpty(userId)
        && ownerType.equals(ResourceOwnerType.SHARED)) {
      return resourceSharePrefix + parent + hashSeparator + userId;
    }
    String skValue = ownerType.label;

    if (ownerId.isEmpty() && parent.isEmpty()) {
      return skValue;
    } else if (!ownerId.isEmpty() && parent.isEmpty()) {
      return skValue + ownerId;
    } else if (ownerId.isEmpty()) {
      return skValue + parent;
    } else {
      return skValue + parent + hashSeparator + ownerId;
    }
  }

  public static String makePkValue(String objectId, ResourceType resourceType) {
    return resourcePrefix + resourceType.name() + hashSeparator + objectId;
  }

  private static String getOwnerName(String ownerId, ResourceOwnerType ownerType) {
    if (!ownerType.equals(ResourceOwnerType.PUBLIC)) {
      if (ownerType.equals(ResourceOwnerType.OWNED)) {
        return Users.getUserNameById(ownerId);
      } else if (ownerType.equals(ResourceOwnerType.ORG)) {
        Item org = Users.getOrganizationById(ownerId);
        if (org != null) {
          return org.getString(Field.COMPANY_NAME.getName());
        }
      }
    }
    return null;
  }

  public static String getName(Item resource) {
    return resource.getString(Field.NAME.getName());
  }

  public static String getParent(Item resource) {
    return resource.getString(Field.PARENT.getName());
  }

  @Override
  public Iterator<Item> getObjectsByFolderId(
      String parent,
      String owner,
      String ownerId,
      ObjectFilter objectFilter,
      String userId,
      ResourceType resourceType) {
    ResourceOwnerType ownerType = ResourceOwnerType.getType(owner);

    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
      ownerId = emptyString;
    }

    NameMap nameMap = new NameMap().with("#pk", pk).with("#sk", sk);
    ValueMap valueMap = new ValueMap()
        .withString(":pk", resourcePrefix + resourceType.name())
        .withString(":sk", makeSkValue(ownerId, parent, ownerType, userId));

    QuerySpec querySpec =
        new QuerySpec().withKeyConditionExpression("#sk = :sk and begins_with(#pk, :pk)");

    if (Objects.nonNull(objectFilter) && !objectFilter.equals(ObjectFilter.ALL)) {
      querySpec.withFilterExpression("#objectType = :objectType");
      nameMap.with("#objectType", Field.OBJECT_TYPE.getName());
      valueMap.withString(":objectType", objectFilter.type);
    }
    querySpec.withNameMap(nameMap).withValueMap(valueMap);

    return query(resourcesTable, skPkIndex, querySpec, DataEntityType.RESOURCES).iterator();
  }

  @Override
  public Iterator<Item> getFolderResourcesByName(
      String ownerId,
      String owner,
      String name,
      String userId,
      String parent,
      ObjectType objectType,
      ResourceType resourceType) {
    ResourceOwnerType ownerType = ResourceOwnerType.getType(owner);

    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
      ownerId = emptyString;
    }

    return query(
            resourcesTable,
            skPkIndex,
            new QuerySpec()
                .withKeyConditionExpression("#sk =:sk AND begins_with(#pk, :pk)")
                .withFilterExpression("#name = :name AND #objectType = :objectType")
                .withNameMap(new NameMap()
                    .with("#pk", pk)
                    .with("#sk", sk)
                    .with("#name", Field.NAME.getName())
                    .with("#objectType", Field.OBJECT_TYPE.getName()))
                .withValueMap(new ValueMap()
                    .withString(":pk", resourcePrefix + resourceType.name())
                    .withString(":sk", makeSkValue(ownerId, parent, ownerType, userId))
                    .withString(":name", name)
                    .withString(":objectType", objectType.name())),
            DataEntityType.RESOURCES)
        .iterator();
  }

  @Override
  public Item getObjectById(
      String objectId, ResourceType resourceType, String owner, String userId) {
    String updateExpression = "#pk =:pk";
    NameMap nameMap = new NameMap().with("#pk", pk);
    ValueMap valueMap = new ValueMap().withString(":pk", makePkValue(objectId, resourceType));
    boolean iterate = false;
    ResourceOwnerType ownerType = null;
    if (Utils.isStringNotNullOrEmpty(owner)) {
      ownerType = ResourceOwnerType.getType(owner);
      updateExpression += " AND begins_with(#sk, :sk)";
      nameMap.with("#sk", sk);
      valueMap.withString(":sk", ownerType.label);
    } else {
      iterate = true;
    }
    Iterator<Item> iterator = query(
            resourcesTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression(updateExpression)
                .withNameMap(nameMap)
                .withValueMap(valueMap),
            DataEntityType.RESOURCES)
        .iterator();
    // Logic here -
    // > Owner (NOT null/empty) and its shared, check if item is shared with the user else null
    // > Owner (null/empty), check if item is shared with user, if not get the main (owned) item,
    // else null
    if (iterate || ownerType.equals(ResourceOwnerType.SHARED)) {
      List<Item> items = IteratorUtils.toList(iterator);
      Optional<Item> optionalShared = items.stream()
          .filter(item -> {
            String skValue = item.getString(Field.SK.getName());
            return skValue.startsWith(ResourceOwnerType.SHARED.name()) && skValue.endsWith(userId);
          })
          .findAny();
      if (optionalShared.isPresent()) {
        return optionalShared.get();
      } else {
        if (iterate) {
          Optional<Item> optionalNotShared = items.stream()
              .filter(item ->
                  !item.getString(Field.SK.getName()).startsWith(ResourceOwnerType.SHARED.name()))
              .findAny();
          if (optionalNotShared.isPresent()) {
            return optionalNotShared.get();
          }
        }
      }
    } else if (iterator.hasNext()) {
      return iterator.next();
    }
    return null;
  }

  @Override
  public Item updateObject(
      String pkValue,
      String skValue,
      JsonObject dataToUpdate,
      ResourceType resourceType,
      List<Map<String, Object>> fontInfo) {
    List<String> expressionBuilder = new ArrayList<>();
    NameMap nameMap = new NameMap();
    ValueMap valueMap = new ValueMap();
    for (String dataField : dataToUpdate.fieldNames()) {
      expressionBuilder.add(hashSeparator + dataField + " = " + valueColonPrefix + dataField);
      nameMap.with(hashSeparator + dataField, dataField);
      valueMap.withString(valueColonPrefix + dataField, dataToUpdate.getString(dataField));
    }
    expressionBuilder.add("#updated = :updated");
    nameMap.with("#updated", Field.UPDATED.getName());
    valueMap.withLong(":updated", GMTHelper.utcCurrentTime());
    String updateExpression = "SET " + String.join(", ", expressionBuilder);

    if (resourceType.equals(ResourceType.FONTS)) {
      String fileName = dataToUpdate.getString(Field.FILE_NAME_C.getName());
      if (Utils.isStringNotNullOrEmpty(fileName)
          && Extensions.getExtension(fileName).equalsIgnoreCase(FontType.SHX.name())) {
        fontInfo = null;
        updateExpression += " remove #fontInfo";
        nameMap.with("#fontInfo", "fontInfo");
      }
      if (Utils.isListNotNullOrEmpty(fontInfo)) {
        updateExpression += ", #fontInfo = :fontInfo";
        nameMap.with("#fontInfo", "fontInfo");
        valueMap.withList(":fontInfo", fontInfo);
      }
    }

    return update(pkValue, skValue, updateExpression, nameMap, valueMap);
  }

  private Item update(
      String pkValue, String skValue, String updateExpression, NameMap nameMap, ValueMap valueMap) {
    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(pk, pkValue, sk, skValue)
        .withUpdateExpression(updateExpression)
        .withNameMap(nameMap)
        .withReturnValues(ReturnValue.ALL_NEW)
        .withValueMap(valueMap);

    UpdateItemOutcome outcome =
        updateItem(resourcesTable, updateItemSpec, DataEntityType.RESOURCES);
    return outcome.getItem();
  }

  @Override
  public void deleteObject(String pkValue, String skValue) {
    deleteItem(resourcesTable, pk, pkValue, sk, skValue, DataEntityType.RESOURCES);
  }

  @Override
  public void createObject(
      String objectId,
      String owner,
      String ownerId,
      String userId,
      ResourceType resourceType,
      String parent,
      JsonObject objectData,
      List<Map<String, Object>> fontInfo) {
    ResourceOwnerType ownerType = ResourceOwnerType.getType(owner);

    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
      ownerId = emptyString;
    }
    String ownerName = getOwnerName(ownerId, ownerType);

    Item object = new Item()
        .withString(Field.OBJECT_ID.getName(), objectId)
        .withString(pk, makePkValue(objectId, resourceType))
        .withString(sk, makeSkValue(ownerId, parent, ownerType, userId))
        .withString(Field.RESOURCE_TYPE.getName(), resourceType.name())
        .withString(Field.OWNER_TYPE.getName(), ownerType.name())
        .withString(Field.PARENT.getName(), parent)
        .withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime());

    if (Utils.isStringNotNullOrEmpty(ownerName)) {
      object.withString(Field.OWNER_NAME.getName(), ownerName);
    }

    if (Utils.isStringNotNullOrEmpty(userId)) {
      if (ownerType.equals(ResourceOwnerType.SHARED)) {
        object.withString(Field.USER_ID.getName(), userId);
      } else if (!ownerType.equals(ResourceOwnerType.OWNED)) {
        object.withString(Field.CREATED_BY.getName(), userId);
      }
    }

    if (Utils.isStringNotNullOrEmpty(ownerId)) {
      object.withString(Field.OWNER_ID.getName(), ownerId);
    }

    if (Utils.isListNotNullOrEmpty(fontInfo)) {
      object.withList("fontInfo", fontInfo);
    }

    for (String dataField : objectData.fieldNames()) {
      object.withString(dataField, objectData.getString(dataField));
    }
    putItem(resourcesTable, object, DataEntityType.RESOURCES);
  }
}
