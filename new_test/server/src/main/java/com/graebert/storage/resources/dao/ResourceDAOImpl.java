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
import com.graebert.storage.resources.ResourceBuffer;
import com.graebert.storage.resources.ResourceOwnerType;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.resources.integration.BaseResource;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Extensions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.collections4.IteratorUtils;

public class ResourceDAOImpl extends Dataplane implements ResourceDAO {

  public static final String resourcesTable = TableName.RESOURCES.name;
  private static final String resourcePrefix = "RESOURCE".concat(hashSeparator);
  private static final String resourceSharePrefix = "SHARED".concat(hashSeparator);
  private static final String valueColonPrefix = ":";
  private static final String skPkIndex = "sk-pk-index";

  public ResourceDAOImpl() {}

  @Override
  public void shareObject(Item itemToShare, String userId) {
    itemToShare.withString(
        sk,
        makeSkValue(
            itemToShare.getString(Field.OWNER_ID.getName()),
            itemToShare.getString(Field.PARENT.getName()),
            ResourceOwnerType.SHARED,
            userId));
    itemToShare.withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime());
    itemToShare.withString(Field.USER_ID.getName(), userId);
    itemToShare.removeAttribute(Field.UPDATED.getName());
    itemToShare.withString(Field.OWNER_TYPE.getName(), ResourceOwnerType.SHARED.name());
    putItem(resourcesTable, itemToShare, DataEntityType.RESOURCES);
  }

  @Override
  public void unshareObject(String pkValue, String parent, String userId) {
    deleteObject(pkValue, makeSkValue(null, parent, ResourceOwnerType.SHARED, userId));
  }

  public static String makeSkValue(
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

    String filterExpression = "attribute_not_exists(#deleted)";
    NameMap nameMap =
        new NameMap().with("#pk", pk).with("#sk", sk).with("#deleted", Field.DELETED.getName());
    ValueMap valueMap = new ValueMap()
        .withString(":pk", resourcePrefix + resourceType.name())
        .withString(":sk", makeSkValue(ownerId, parent, ownerType, userId));

    QuerySpec querySpec =
        new QuerySpec().withKeyConditionExpression("#sk = :sk and begins_with(#pk, :pk)");

    if (Objects.nonNull(objectFilter) && !objectFilter.equals(ObjectFilter.ALL)) {
      filterExpression += " and #objectType = :objectType";
      nameMap.with("#objectType", Field.OBJECT_TYPE.getName());
      valueMap.withString(":objectType", objectFilter.type);
    }
    querySpec.withFilterExpression(filterExpression).withNameMap(nameMap).withValueMap(valueMap);

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
                .withKeyConditionExpression("#sk = :sk AND begins_with(#pk, :pk)")
                .withFilterExpression(
                    "#name = :name AND #objectType = :objectType and attribute_not_exists(#deleted)")
                .withNameMap(new NameMap()
                    .with("#pk", pk)
                    .with("#sk", sk)
                    .with("#name", Field.NAME.getName())
                    .with("#objectType", Field.OBJECT_TYPE.getName())
                    .with("#deleted", Field.DELETED.getName()))
                .withValueMap(new ValueMap()
                    .withString(":pk", resourcePrefix + resourceType.name())
                    .withString(":sk", makeSkValue(ownerId, parent, ownerType, userId))
                    .withString(":name", name)
                    .withString(":objectType", objectType.name())),
            DataEntityType.RESOURCES)
        .iterator();
  }

  @Override
  public Item getObjectById(String objectId, ResourceType resourceType, ResourceBuffer buffer) {
    return getObjectById(objectId, resourceType, buffer, true);
  }

  @Override
  public Item getObjectById(
      String objectId, ResourceType resourceType, ResourceBuffer buffer, boolean checkIfDeleted) {
    String updateExpression = "#pk = :pk";
    NameMap nameMap = new NameMap().with("#pk", pk);
    ValueMap valueMap = new ValueMap().withString(":pk", makePkValue(objectId, resourceType));
    boolean iterate = false;
    if (Utils.isStringNotNullOrEmpty(buffer.getOwnerType())) {
      ResourceOwnerType ownerType = ResourceOwnerType.getType(buffer.getOwnerType());
      updateExpression += " AND begins_with(#sk, :sk)";
      nameMap.with("#sk", sk);
      valueMap.withString(":sk", ownerType.label);
    } else {
      iterate = true;
    }
    QuerySpec querySpec =
        new QuerySpec().withKeyConditionExpression(updateExpression).withValueMap(valueMap);
    if (checkIfDeleted) {
      querySpec.withFilterExpression("attribute_not_exists(#deleted)");
      nameMap.with("#deleted", Field.DELETED.getName());
    }
    querySpec.withNameMap(nameMap);
    Iterator<Item> iterator =
        query(resourcesTable, null, querySpec, DataEntityType.RESOURCES).iterator();
    // Logic here -
    // > Owner (null/empty), check if item is shared with user, if not get the main (owned) item,
    // > Owner (NOT null/empty) and its shared, check if item is shared with the user
    // else null
    if (iterate) {
      List<Item> items = IteratorUtils.toList(iterator);
      Optional<Item> optional = items.stream()
          .filter(item -> {
            if (!item.hasAttribute(Field.USER_ID.getName())
                && !Utils.isStringNotNullOrEmpty(buffer.getUserId())
                && !Utils.isStringNotNullOrEmpty(buffer.getOrganizationId())) {
              // original item
              return true;
            }
            String skValue = item.getString(Field.SK.getName());
            if (skValue.startsWith(ResourceOwnerType.PUBLIC.name())) {
              return true;
            } else {
              if (Utils.isStringNotNullOrEmpty(buffer.getUserId())
                  && skValue.endsWith(buffer.getUserId())) {
                return true;
              }
              if (Utils.isStringNotNullOrEmpty(buffer.getOrganizationId())
                  && skValue.endsWith(buffer.getOrganizationId())) {
                buffer.setOrganizationObject(true);
                return true;
              }
            }
            return false;
          })
          .findAny();
      if (optional.isPresent()) {
        return optional.get();
      }
    } else if (iterator.hasNext()) {
      Item item = iterator.next();
      if (Utils.isStringNotNullOrEmpty(buffer.getOrganizationId())
          && item.getString(Field.SK.getName()).endsWith(buffer.getOrganizationId())) {
        buffer.setOrganizationObject(true);
      }
      return item;
    }
    return null;
  }

  public Iterator<Item> getAllObjectsById(String objectId, ResourceType resourceType) {
    return query(
            resourcesTable,
            null,
            new QuerySpec().withHashKey(pk, makePkValue(objectId, resourceType)),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public Iterator<Item> getSharedObjectsById(String objectId, ResourceType resourceType) {
    return query(
            resourcesTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression("#pk = :pk AND begins_with(#sk, :sk)")
                .withNameMap(new NameMap().with("#pk", pk).with("#sk", sk))
                .withValueMap(new ValueMap()
                    .withString(":pk", makePkValue(objectId, resourceType))
                    .withString(":sk", ResourceOwnerType.SHARED.label)),
            DataEntityType.RESOURCES)
        .iterator();
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
        nameMap.with("#fontInfo", Field.FONT_INFO.getName());
      }
      if (Utils.isListNotNullOrEmpty(fontInfo)) {
        updateExpression += ", #fontInfo = :fontInfo";
        nameMap.with("#fontInfo", Field.FONT_INFO.getName());
        valueMap.withList(":fontInfo", fontInfo);
      }
    }

    return update(pkValue, skValue, updateExpression, nameMap, valueMap);
  }

  public void markFolderAsDeleted(String pkValue, String skValue) {
    update(
        pkValue,
        skValue,
        "SET #deleted = :val",
        new NameMap().with("#deleted", Field.DELETED.getName()),
        new ValueMap().withBoolean(":val", true));
  }

  public void updateParent(Item object, ResourceType resourceType, String parent, String userId) {
    deleteObject(object.getString(pk), object.getString(sk));
    String skValue;
    if (parent.split(hashSeparator).length < 2) {
      skValue = makeSkValue(
          BaseResource.getOwner(object),
          parent,
          ResourceOwnerType.getType(BaseResource.getOwnerType(object)),
          userId);
    } else {
      skValue = parent;
      if (skValue.split(hashSeparator).length > 1) {
        parent = skValue.split(hashSeparator)[1];
      }
    }
    String newName = checkAndGetNewName(object, resourceType, parent, userId);
    if (Utils.isStringNotNullOrEmpty(newName)) {
      // updating the name of the object if objects with the same name exist in the targer folder
      object.withString(Field.NAME.getName(), newName);
    }
    object
        .withString(sk, skValue)
        .withString(Field.PARENT.getName(), parent)
        .withLong(Field.UPDATED.getName(), GMTHelper.utcCurrentTime());
    putItem(resourcesTable, object, DataEntityType.RESOURCES);
  }

  private String checkAndGetNewName(
      Item object, ResourceType resourceType, String parent, String userId) {
    boolean isFile = BaseResource.isFile(object);
    Set<String> itemNames = getFolderItemNamesSet(
        parent,
        BaseResource.getOwner(object),
        BaseResource.getOwnerType(object),
        userId,
        resourceType,
        isFile);
    if (!itemNames.isEmpty()) {
      String itemOriginalName = BaseResource.getName(object);
      String newName = Utils.checkAndRename(itemNames, itemOriginalName, !isFile);
      if (!itemOriginalName.equals(newName)) {
        return newName;
      }
    }
    return null;
  }

  private static Set<String> getFolderItemNamesSet(
      String parent,
      String ownerId,
      String ownerType,
      String userId,
      ResourceType resourceType,
      boolean isFile) {
    Set<String> itemNames = new HashSet<>();
    Iterator<Item> folderItemNames =
        getFolderItemNames(parent, ownerId, ownerType, userId, resourceType, isFile);
    if (folderItemNames.hasNext()) {
      folderItemNames.forEachRemaining(itemName -> {
        String name = itemName.getString(Field.NAME.getName());
        if (Utils.isStringNotNullOrEmpty(name)) {
          itemNames.add(name);
        }
      });
    }
    return itemNames;
  }

  private static Iterator<Item> getFolderItemNames(
      String parent,
      String ownerId,
      String ownerType,
      String userId,
      ResourceType resourceType,
      boolean isFile) {
    return query(
            resourcesTable,
            skPkIndex,
            new QuerySpec()
                .withKeyConditionExpression("#sk = :sk AND begins_with(#pk, :pk)")
                .withFilterExpression(
                    "#objectType = :objectType and attribute_not_exists(#deleted)")
                .withNameMap(new NameMap()
                    .with("#sk", Field.SK.getName())
                    .with("#pk", Field.PK.getName())
                    .with("#name", Field.NAME.getName())
                    .with("#objectType", Field.OBJECT_TYPE.getName())
                    .with("#deleted", Field.DELETED.getName()))
                .withValueMap(new ValueMap()
                    .withString(":pk", resourcePrefix + resourceType.name())
                    .withString(
                        ":sk",
                        makeSkValue(ownerId, parent, ResourceOwnerType.getType(ownerType), userId))
                    .withString(
                        ":objectType", isFile ? ObjectType.FILE.name() : ObjectType.FOLDER.name()))
                .withProjectionExpression("#name"),
            DataEntityType.FILES)
        .iterator();
  }

  public void updateCollaborators(Item object, Set<String> editors, Set<String> viewers) {
    String updateExpression;
    ValueMap valueMap = null;
    if (!editors.isEmpty()) {
      if (!viewers.isEmpty()) {
        updateExpression = "SET viewers = :viewers, editors = :editors";
        valueMap =
            new ValueMap().withStringSet(":viewers", viewers).withStringSet(":editors", editors);
      } else {
        updateExpression = "SET editors = :editors REMOVE viewers";
        valueMap = new ValueMap().withStringSet(":editors", editors);
      }
    } else {
      if (!viewers.isEmpty()) {
        updateExpression = "SET viewers = :viewers REMOVE editors";
        valueMap = new ValueMap().withStringSet(":viewers", viewers);
      } else {
        updateExpression = "REMOVE editors, viewers";
      }
    }
    String skValue;
    if (object.getString(sk).startsWith(ResourceOwnerType.SHARED.name())) {
      Item originalItem = getObjectById(
          BaseResource.getObjectId(object),
          ResourceType.getType(object.getString(Field.RESOURCE_TYPE.getName())),
          new ResourceBuffer());
      skValue = originalItem.getString(sk);
    } else {
      skValue = object.getString(sk);
    }
    update(object.getString(pk), skValue, updateExpression, null, valueMap);
  }

  public void updateUnsharedMembersForOrg(Item object, List<String> unsharedMembers) {
    update(
        object.getString(pk),
        object.getString(sk),
        "SET unsharedMembers = :val",
        null,
        new ValueMap().withList(":val", unsharedMembers));
  }

  private Item update(
      String pkValue, String skValue, String updateExpression, NameMap nameMap, ValueMap valueMap) {
    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(pk, pkValue, sk, skValue)
        .withUpdateExpression(updateExpression)
        .withReturnValues(ReturnValue.ALL_NEW);

    if (Objects.nonNull(nameMap)) {
      updateItemSpec.withNameMap(nameMap);
    }

    if (Objects.nonNull(valueMap)) {
      updateItemSpec.withValueMap(valueMap);
    }

    UpdateItemOutcome outcome =
        updateItem(resourcesTable, updateItemSpec, DataEntityType.RESOURCES);
    return outcome.getItem();
  }

  @Override
  public void deleteObject(String pkValue, String skValue) {
    deleteItem(resourcesTable, pk, pkValue, sk, skValue, DataEntityType.RESOURCES);
  }

  @Override
  public Item createObject(
      String objectId,
      String owner,
      String ownerId,
      String userId,
      ResourceType resourceType,
      String parent,
      JsonObject objectData,
      List<Map<String, Object>> fontInfo,
      boolean isOrganizationObject,
      String organizationId) {
    ResourceOwnerType ownerType = ResourceOwnerType.getType(owner);

    if (ownerType.equals(ResourceOwnerType.PUBLIC)) {
      ownerId = emptyString;
    } else if (ownerType.equals(ResourceOwnerType.SHARED)) {
      if (isOrganizationObject) {
        ownerType = ResourceOwnerType.ORG;
        ownerId = organizationId;
      } else {
        ownerType = ResourceOwnerType.OWNED;
        ownerId = userId;
      }
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
      if (!ownerType.equals(ResourceOwnerType.OWNED)) {
        object.withString(Field.CREATED_BY.getName(), userId);
      }
    }

    if (Utils.isStringNotNullOrEmpty(ownerId)) {
      object.withString(Field.OWNER_ID.getName(), ownerId);
    }

    if (Utils.isListNotNullOrEmpty(fontInfo)) {
      object.withList(Field.FONT_INFO.getName(), fontInfo);
    }

    for (String dataField : objectData.fieldNames()) {
      object.withString(dataField, objectData.getString(dataField));
    }
    putItem(resourcesTable, object, DataEntityType.RESOURCES);
    return object;
  }
}
