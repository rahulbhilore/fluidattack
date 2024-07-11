package com.graebert.storage.blocklibrary;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class BlockLibraryDao extends Dataplane {

  private static final String blockLibraryTable = TableName.MAIN.name;
  public static final String emptyOwnerId = emptyString;
  private static final String blockLibrarySkPkIndex = "sk-pk-index";
  private static final String pkMainPrefix = "BL#";
  private static final String pkLibraryPublicValue = pkMainPrefix.concat("LIBRARY");
  private static final String pkLibraryOwnerPrefix =
      pkMainPrefix.concat("LIBRARY").concat(hashSeparator);
  private static final String pkLibrarySharePrefix =
      pkMainPrefix.concat("SHARE").concat(hashSeparator).concat("LIBRARY").concat(hashSeparator);
  private static final String pkBlockPublicValue = pkMainPrefix.concat("BLOCK");
  private static final String pkBlockOwnerPrefix =
      pkMainPrefix.concat("BLOCK").concat(hashSeparator);
  private static final String pkBlockSharePrefix =
      pkMainPrefix.concat("SHARE").concat(hashSeparator).concat("BLOCK").concat(hashSeparator);

  public enum BlockLibraryOwnerType {
    ORG("ORG#"),
    GROUP("GROUP#"),
    USER("USER#"),
    PUBLIC("PUBLIC");

    public final String label;

    BlockLibraryOwnerType(String label) {
      this.label = label;
    }

    public static List<String> getValues() {
      return Arrays.stream(BlockLibraryOwnerType.values())
          .map(Enum::name)
          .collect(Collectors.toList());
    }

    public static BlockLibraryOwnerType getType(String value) {
      if (!Utils.isStringNotNullOrEmpty(value)) {
        return PUBLIC;
      }
      final String formattedValue = value.trim();
      for (BlockLibraryOwnerType t : BlockLibraryOwnerType.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
      return PUBLIC;
    }
  }

  public enum ObjectType {
    BLOCK,
    LIBRARY
  }

  public enum AccessType {
    EDIT(Field.EDITOR.getName()),
    VIEW(Field.VIEWER.getName());

    public final String value;

    AccessType(String value) {
      this.value = value;
    }

    public static AccessType getAccessType(String label) {
      if (Utils.isStringNotNullOrEmpty(label)) {
        final String formattedValue = label.trim().toUpperCase();
        for (AccessType t : AccessType.values()) {
          if (t.name().equals(formattedValue) || formattedValue.startsWith(t.name())) {
            return t;
          }
        }
      }
      return VIEW;
    }
  }

  public enum PermissionType {
    UPLOAD,
    UPDATE,
    DELETE,
    SHARE,
    UNSHARE
  }

  public static Iterator<Item> getBlocksOrLibraries(
      String ownerId, String type, boolean isOwned, boolean isBlockLibrary) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      ownerId = emptyOwnerId;
    }

    return query(
            blockLibraryTable,
            null,
            new QuerySpec()
                .withHashKey(
                    pk,
                    isBlockLibrary
                        ? makeLibraryPkValue(ownerId, ownerType, isOwned)
                        : makeBlockPkValue(ownerId, ownerType, isOwned))
                .withFilterExpression(isOwned ? "#owner =:owner" : "begins_with(#owner, :owner)")
                .withNameMap(new NameMap().with("#owner", Field.OWNER.getName()))
                .withValueMap(new ValueMap()
                    .withString(":owner", isOwned ? ownerType.label + ownerId : ownerType.name())),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static void createBlockLibrary(
      String ownerId,
      String type,
      String name,
      String description,
      String s3Path,
      String libraryId) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    Item item = new Item()
        .withString(pk, makeLibraryPkValue(ownerId, ownerType))
        .withString(sk, libraryId)
        .withString(Field.OWNER.getName(), ownerType.label + ownerId)
        .withString(Field.OWNER_NAME.getName(), getOwnerNameById(ownerId, ownerType))
        .withString(Field.NAME.getName(), name)
        .withString(Field.DESCRIPTION.getName(), description)
        .withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime())
        .withLong(Field.MODIFIED.getName(), GMTHelper.utcCurrentTime())
        .withString(Field.S3_PATH.getName(), s3Path);

    putItem(blockLibraryTable, item, DataEntityType.RESOURCES);
  }

  public static void shareBlockLibrary(Item library, String collaboratorId, String mode) {
    String ownerType = getOwnerType(library.getString(Field.OWNER.getName()));
    library.withString(
        pk, makeLibraryPkValue(collaboratorId, BlockLibraryOwnerType.getType(ownerType), false));
    library.withString(Field.MODE.getName(), mode);
    putItem(blockLibraryTable, library, DataEntityType.RESOURCES);
  }

  public static void shareBlock(Item block, String collaboratorId, String mode) {
    String ownerType = getOwnerType(block.getString(Field.OWNER.getName()));
    block.withString(
        pk, makeBlockPkValue(collaboratorId, BlockLibraryOwnerType.getType(ownerType), false));
    block.withString(Field.MODE.getName(), mode);
    putItem(blockLibraryTable, block, DataEntityType.RESOURCES);
  }

  public static void updateSharedItem(String pkValue, String skValue, String mode) {
    updateItem(
        blockLibraryTable,
        new UpdateItemSpec()
            .withPrimaryKey(new PrimaryKey(pk, pkValue, sk, skValue))
            .withUpdateExpression("set #mode = :mode")
            .withNameMap(new NameMap().with("#mode", Field.MODE.getName()))
            .withValueMap(new ValueMap().withString(":mode", mode)),
        DataEntityType.RESOURCES);
  }

  public static Iterator<Item> getAllSharedItems(String libraryId, String blockId) {
    return query(
            blockLibraryTable,
            blockLibrarySkPkIndex,
            new QuerySpec()
                .withKeyConditionExpression("sk = :sk and begins_with(pk, :pk)")
                .withValueMap(new ValueMap()
                    .withString(
                        ":sk", (blockId != null) ? libraryId + hashSeparator + blockId : libraryId)
                    .withString(
                        ":pk", (blockId != null) ? pkBlockSharePrefix : pkLibrarySharePrefix)),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static Item updateBlockLibrary(
      String name, String description, String libraryId, String pkValue) {
    return updateItem(name, description, null, null, pkValue, libraryId);
  }

  public static void deleteBlockLibraryItem(String libraryId, String pkValue) {
    deleteItem(blockLibraryTable, pk, pkValue, sk, libraryId, DataEntityType.RESOURCES);
  }

  public static void deleteAllBlocks(Iterator<Item> blocks) {
    if (blocks.hasNext()) {
      List<PrimaryKey> keysToDelete = new ArrayList<>();
      blocks.forEachRemaining(block ->
          keysToDelete.add(new PrimaryKey(pk, block.getString(pk), sk, block.getString(sk))));
      if (!keysToDelete.isEmpty()) {
        deleteListItemsBatch(keysToDelete, blockLibraryTable, DataEntityType.RESOURCES);
      }
    }
  }

  public static Iterator<Item> getBlockLibraryByOwnerAndName(
      String ownerId, String type, String name) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      ownerId = emptyOwnerId;
    }

    return query(
            blockLibraryTable,
            null,
            new QuerySpec()
                .withHashKey(pk, makeLibraryPkValue(ownerId, ownerType))
                .withFilterExpression("#owner =:owner and #name =:name")
                .withNameMap(new NameMap()
                    .with("#owner", Field.OWNER.getName())
                    .with("#name", Field.NAME.getName()))
                .withValueMap(new ValueMap()
                    .withString(":owner", ownerType.label + ownerId)
                    .withString(":name", name)),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static Iterator<Item> getBlockLibraryByLibraryId(String libraryId) {
    return query(
            blockLibraryTable,
            blockLibrarySkPkIndex,
            new QuerySpec().withHashKey(sk, libraryId),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static void createBlock(
      String ownerId,
      String type,
      String name,
      String description,
      String s3Path,
      String libraryId,
      String blockId,
      boolean isOwned,
      String fileName) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    Item blockItem = new Item()
        .withString(pk, makeBlockPkValue(ownerId, ownerType, isOwned))
        .withString(sk, libraryId + hashSeparator + blockId)
        .withString(Field.BLOCK_ID.getName(), blockId)
        .withString(Field.LIB_ID.getName(), libraryId)
        .withString(Field.FILE_NAME_C.getName(), fileName)
        .withString(Field.OWNER.getName(), ownerType.label + ownerId)
        .withString(Field.OWNER_NAME.getName(), getOwnerNameById(ownerId, ownerType))
        .withString(Field.NAME.getName(), name)
        .withString(Field.DESCRIPTION.getName(), description)
        .withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime())
        .withLong(Field.MODIFIED.getName(), GMTHelper.utcCurrentTime())
        .withString(Field.S3_PATH.getName(), s3Path);

    putItem(blockLibraryTable, blockItem, DataEntityType.RESOURCES);
  }

  public static Iterator<Item> getBlocksByLibraryAndName(
      String ownerId, String type, String libraryId, String name, boolean isOwned) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      ownerId = emptyOwnerId;
    }

    return query(
            blockLibraryTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression("#pk =:pk AND begins_with(#sk, :sk)")
                .withFilterExpression("#name = :name")
                .withNameMap(new NameMap()
                    .with("#pk", pk)
                    .with("#sk", sk)
                    .with("#name", Field.NAME.getName()))
                .withValueMap(new ValueMap()
                    .withString(":pk", makeBlockPkValue(ownerId, ownerType, isOwned))
                    .withString(":sk", libraryId)
                    .withString(":name", name)),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static Iterator<Item> getBlocksByLibrary(
      String ownerId, String type, String libraryId, boolean isOwned) {
    BlockLibraryOwnerType ownerType = BlockLibraryOwnerType.getType(type);

    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      ownerId = emptyOwnerId;
    }

    return query(
            blockLibraryTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression("#pk =:pk AND begins_with(#sk, :sk)")
                .withNameMap(new NameMap().with("#pk", pk).with("#sk", sk))
                .withValueMap(new ValueMap()
                    .withString(":pk", makeBlockPkValue(ownerId, ownerType, isOwned))
                    .withString(":sk", libraryId)),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static Iterator<Item> getBlockByLibraryIdAndBlockId(String libraryId, String blockId) {
    return query(
            blockLibraryTable,
            blockLibrarySkPkIndex,
            new QuerySpec().withHashKey(sk, libraryId + hashSeparator + blockId),
            DataEntityType.RESOURCES)
        .iterator();
  }

  public static void deleteBlockItem(String libraryId, String blockId, String pkValue) {
    deleteItem(
        blockLibraryTable,
        pk,
        pkValue,
        sk,
        libraryId + hashSeparator + blockId,
        DataEntityType.RESOURCES);
  }

  public static Item updateBlock(
      String pkValue,
      String libraryId,
      String blockId,
      String name,
      String description,
      String fileName,
      String s3Path) {
    return updateItem(
        name, description, fileName, s3Path, pkValue, libraryId + hashSeparator + blockId);
  }

  public static void updateBlocksSharedForLibrary(String pkValue, String skValue) {
    updateItem(
        blockLibraryTable,
        new UpdateItemSpec()
            .withPrimaryKey(pk, pkValue, sk, skValue)
            .withUpdateExpression("SET blocksShared = :val1")
            .withValueMap(new ValueMap().with(":val1", true)),
        DataEntityType.RESOURCES);
  }

  private static Item updateItem(
      String name,
      String description,
      String fileName,
      String s3Path,
      String pkValue,
      String skValue) {
    List<String> expressionBuilder = new ArrayList<>();
    expressionBuilder.add("set #modified = :modified");
    NameMap nameMap = new NameMap().with("#modified", Field.MODIFIED.getName());
    ValueMap valueMap = new ValueMap().withLong(":modified", GMTHelper.utcCurrentTime());

    if (Utils.isStringNotNullOrEmpty(name)) {
      expressionBuilder.add("#name = :name");
      nameMap.with("#name", Field.NAME.getName());
      valueMap.withString(":name", name);
    }

    if (Utils.isStringNotNullOrEmpty(description)) {
      expressionBuilder.add("#desc = :desc");
      nameMap.with("#desc", Field.DESCRIPTION.getName());
      valueMap.withString(":desc", description);
    }

    if (Utils.isStringNotNullOrEmpty(fileName)) {
      expressionBuilder.add("#fileName = :fileName");
      nameMap.with("#fileName", Field.FILE_NAME_C.getName());
      valueMap.withString(":fileName", fileName);
    }

    if (Utils.isStringNotNullOrEmpty(s3Path)) {
      expressionBuilder.add("#s3Path = :s3Path");
      nameMap.with("#s3Path", Field.S3_PATH.getName());
      valueMap.withString(":s3Path", s3Path);
    }

    String updateExpression = String.join(" , ", expressionBuilder);

    if (Utils.isStringNotNullOrEmpty(updateExpression)) {
      UpdateItemSpec updateItemSpec = new UpdateItemSpec()
          .withPrimaryKey(pk, pkValue, sk, skValue)
          .withUpdateExpression(updateExpression)
          .withNameMap(nameMap)
          .withValueMap(valueMap);

      UpdateItemOutcome outcome =
          updateItem(blockLibraryTable, updateItemSpec, DataEntityType.RESOURCES);
      if (outcome.getItem() != null) {
        return outcome.getItem();
      }
    }
    return null;
  }

  public static JsonObject itemToJson(Item item, boolean full) {
    BlockLibraryOwnerType ownerType =
        BlockLibraryOwnerType.getType(getOwnerType(item.getString(Field.OWNER.getName())));
    JsonArray shares = new JsonArray();
    Iterator<Item> sharedIterator = Collections.emptyIterator();
    boolean isBlock = item.getString(pk).contains(ObjectType.BLOCK.name());
    JsonObject libObject = new JsonObject()
        .put(Field.NAME.getName(), item.getString(Field.NAME.getName()))
        .put(Field.DESCRIPTION.getName(), item.getString(Field.DESCRIPTION.getName()))
        .put(Field.OWNER_NAME.getName(), item.getString(Field.OWNER_NAME.getName()))
        .put(Field.OWNER_TYPE.getName(), ownerType.name())
        .put(Field.CREATED.getName(), item.getLong(Field.CREATED.getName()))
        .put(Field.MODIFIED.getName(), item.getLong(Field.MODIFIED.getName()));

    if (isBlock) {
      libObject
          .put(Field.LIB_ID.getName(), item.getString(Field.LIB_ID.getName()))
          .put(Field.ID.getName(), item.getString(Field.BLOCK_ID.getName()))
          .put(Field.FILE_NAME_C.getName(), item.getString(Field.FILE_NAME_C.getName()));
      if (full) {
        sharedIterator = getAllSharedItems(
            item.getString(Field.LIB_ID.getName()), item.getString(Field.BLOCK_ID.getName()));
      }
    } else {
      libObject.put(Field.ID.getName(), item.getString(sk));
      if (full) {
        sharedIterator = getAllSharedItems(item.getString(sk), null);
      }
    }

    sharedIterator.forEachRemaining(share -> {
      Item user = Users.getUserById(getIdFromPk(share.getString(pk)));
      if (user == null) {
        return;
      }
      if (!Utils.isStringNotNullOrEmpty(share.getString(Field.MODE.getName()))) {
        return;
      }
      shares.add(new JsonObject()
          .put(Field.USER_ID.getName(), user.getString(sk))
          .put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()))
          .put(Field.NAME.getName(), Users.getUserNameById(user.getString(sk)))
          .put(
              Field.MODE.getName(),
              AccessType.getAccessType(share.getString(Field.MODE.getName())).value));
    });
    if (full) {
      libObject.put("shares", shares);
    }

    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      libObject.put(Field.OWNER_ID.getName(), BlockLibraryOwnerType.PUBLIC.name());
    } else {
      libObject.put(Field.OWNER_ID.getName(), getOwnerId(item.getString(Field.OWNER.getName())));
    }

    String thumbnailId = item.getString(sk);
    if (isBlock) {
      thumbnailId =
          item.getString(Field.LIB_ID.getName()) + "_" + item.getString(Field.BLOCK_ID.getName());
    }
    String thumbnailName = ThumbnailsManager.getThumbnailName(
        "BL", thumbnailId, String.valueOf(item.getLong(Field.MODIFIED.getName())));
    libObject.put("thumbnailId", thumbnailId);
    libObject.put(Field.THUMBNAIL_NAME.getName(), thumbnailName);
    libObject.put(
        Field.THUMBNAIL.getName(), ThumbnailsManager.getThumbnailURL(config, thumbnailName, true));
    return libObject;
  }

  public static void deleteBatchBlocks(S3Regional s3Regional, String s3Path) {
    List<String> idsToDelete = new ArrayList<>();
    ListObjectsV2Result result;
    String nextToken = null;
    do {
      log.info("[BLD] deleteBatchBlocks. Path: " + s3Path);
      ListObjectsV2Request listObjectsV2Request =
          new ListObjectsV2Request().withBucketName(s3Regional.getBucketName()).withPrefix(s3Path);

      if (nextToken != null) {
        listObjectsV2Request.setContinuationToken(nextToken);
      }

      result = s3Regional.listObjectsV2(listObjectsV2Request);
      log.info("[BLD] deleteBatchBlocks Received items size: "
          + result.getObjectSummaries().size());
      for (S3ObjectSummary summary : result.getObjectSummaries()) {
        idsToDelete.add(summary.getKey());
      }
      log.info(
          "[BLD] deleteBatchBlocks Items to delete: " + Arrays.toString(idsToDelete.toArray()));

      if (result.isTruncated() && result.getNextContinuationToken() != null) {
        nextToken = result.getNextContinuationToken();
      }
    } while (nextToken != null);

    s3Regional.deleteListOfKeysFromBucket(idsToDelete);
  }

  /**
   * Make library pkValue for item based on other attributes
   *
   * @param ownerId   the ownerId.
   * @param ownerType the ownerType.
   * @param isOwned   current item is owned by user or not
   * @return the string
   */
  public static String makeLibraryPkValue(
      String ownerId, BlockLibraryOwnerType ownerType, boolean isOwned) {
    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      return pkLibraryPublicValue + ownerId;
    } else {
      if (isOwned) {
        return pkLibraryOwnerPrefix + ownerId;
      } else {
        return pkLibrarySharePrefix + ownerId;
      }
    }
  }

  /**
   * Make library pkValue for item based on other attributes
   *
   * @param ownerId   the ownerId.
   * @param ownerType the ownerType.
   * @return the string
   */
  private static String makeLibraryPkValue(String ownerId, BlockLibraryOwnerType ownerType) {
    return makeLibraryPkValue(ownerId, ownerType, true);
  }

  /**
   * Make block pkValue for item based on other attributes
   *
   * @param ownerId   the ownerId.
   * @param ownerType the ownerType.
   * @param isOwned   current item is owned by user or not
   * @return the string
   */
  public static String makeBlockPkValue(
      String ownerId, BlockLibraryOwnerType ownerType, boolean isOwned) {
    if (ownerType.equals(BlockLibraryOwnerType.PUBLIC)) {
      return pkBlockPublicValue + ownerId;
    } else {
      if (isOwned) {
        return pkBlockOwnerPrefix + ownerId;
      } else {
        return pkBlockSharePrefix + ownerId;
      }
    }
  }

  /**
   * Get ownerId by owner attribute
   *
   * @param owner the owner.
   * @return the string
   */
  public static String getOwnerId(String owner) {
    if (!owner.equalsIgnoreCase(BlockLibraryOwnerType.PUBLIC.name())) {
      return owner.substring(owner.indexOf(hashSeparator) + 1);
    }

    return BlockLibraryOwnerType.PUBLIC.name();
  }

  /**
   * Get ownerId/userId by pk attribute
   *
   * @param pkValue the pk value.
   * @return the string
   */
  public static String getIdFromPk(String pkValue) {
    return pkValue.substring(pkValue.lastIndexOf(hashSeparator) + 1);
  }

  /**
   * Get ownerId/userId by pk attribute and ownerType
   *
   * @param pkValue   the pk value.
   * @param ownerType the ownerType.
   * @return the string
   */
  public static String getIdFromPk(String pkValue, String ownerType) {
    if (ownerType.equalsIgnoreCase(BlockLibraryOwnerType.PUBLIC.name())) {
      return emptyOwnerId;
    }

    return pkValue.substring(pkValue.lastIndexOf(hashSeparator) + 1);
  }

  /**
   * Get ownerType by owner attribute
   *
   * @param owner the owner.
   * @return the string
   */
  public static String getOwnerType(String owner) {
    if (!owner.equalsIgnoreCase(BlockLibraryOwnerType.PUBLIC.name())) {
      return owner.substring(0, owner.indexOf(hashSeparator));
    }

    return BlockLibraryOwnerType.PUBLIC.name();
  }

  /**
   * Get ownerName by ownerId
   *
   * @param ownerId   the ownerId.
   * @param ownerType the ownerType enum.
   * @return the string
   */
  private static String getOwnerNameById(String ownerId, BlockLibraryOwnerType ownerType) {
    switch (ownerType) {
      case USER:
        return Users.getUserNameById(ownerId);
      case ORG: {
        Item org = Users.getOrganizationById(ownerId);
        if (org != null && org.hasAttribute(Field.COMPANY_NAME.getName())) {
          return org.getString(Field.COMPANY_NAME.getName());
        }
        return "Unknown";
      }
      default:
        return BlockLibraryOwnerType.PUBLIC.name();
    }
    // GROUP will be added later
  }

  /**
   * Check if object is owned by the user
   *
   * @param object the block or library item.
   * @return the boolean
   */
  public static boolean isOwned(Item object) {
    return !object.getString(BlockLibraryDao.pk).contains("SHARE");
  }

  /**
   * UnShare ORG/PUBLIC block or library for user
   *
   * @param isBlockLibrary the ownerType.
   * @param ownerType      the ownerType.
   * @param userId         the userId.
   * @param objectId       the item id.
   * @param addOrRemove    add OR remove to/from the excluded list.
   */
  public static void selfUnSharePublicAndOrgObject(
      boolean isBlockLibrary,
      String ownerType,
      String userId,
      String objectId,
      boolean addOrRemove) {
    Map<String, Map<String, List<String>>> selfUnSharedBlocks;
    ObjectType objectType = isBlockLibrary ? ObjectType.LIBRARY : ObjectType.BLOCK;
    Item user = Users.getUserById(userId);
    if (user != null && user.hasAttribute("selfUnSharedBlocks")) {
      selfUnSharedBlocks = user.getMap("selfUnSharedBlocks");
    } else {
      selfUnSharedBlocks = new HashMap<>();
    }
    Map<String, List<String>> selfUnSharedOwnerTypeBlocks;
    if (selfUnSharedBlocks.containsKey(ownerType)) {
      selfUnSharedOwnerTypeBlocks = selfUnSharedBlocks.get(ownerType);
    } else {
      selfUnSharedOwnerTypeBlocks = new HashMap<>();
    }
    List<String> ids;
    if (selfUnSharedOwnerTypeBlocks.containsKey(objectType.name())) {
      ids = selfUnSharedOwnerTypeBlocks.get(objectType.name());
    } else {
      ids = new ArrayList<>();
    }
    boolean updateUser = false;
    if (addOrRemove) {
      ids.add(objectId);
      selfUnSharedOwnerTypeBlocks.put(objectType.name(), ids);
      selfUnSharedBlocks.put(ownerType, selfUnSharedOwnerTypeBlocks);
      updateUser = true;
    } else {
      if (ids.contains(objectId)) {
        ids.remove(objectId);
        selfUnSharedOwnerTypeBlocks.put(objectType.name(), ids);
        selfUnSharedBlocks.put(ownerType, selfUnSharedOwnerTypeBlocks);
        updateUser = true;
      }
    }
    if (updateUser) {
      Users.updateUser(
          userId, "SET selfUnSharedBlocks = :val", new ValueMap().with(":val", selfUnSharedBlocks));
    }
  }

  /**
   * Get list of excluded blocks or libraries for user
   *
   * @param ownerType  the ownerType.
   * @param userId     the userId.
   * @param objectType the objectType.
   * @return the list of item ids
   */
  public static List<String> getExcludedLibrariesOrBlocks(
      String ownerType, String userId, ObjectType objectType) {
    if (ownerType.equalsIgnoreCase(BlockLibraryOwnerType.PUBLIC.name())
        || ownerType.equalsIgnoreCase(BlockLibraryOwnerType.ORG.name())) {
      Item user = Users.getUserById(userId);
      if (user != null && user.hasAttribute("selfUnSharedBlocks")) {
        Map<String, Map<String, List<String>>> selfUnSharedBlocks =
            user.getMap("selfUnSharedBlocks");
        if (selfUnSharedBlocks.containsKey(ownerType)) {
          Map<String, List<String>> selfUnSharedOwnerTypeBlocks = selfUnSharedBlocks.get(ownerType);
          if (selfUnSharedOwnerTypeBlocks.containsKey(objectType.name())) {
            return selfUnSharedOwnerTypeBlocks.get(objectType.name());
          }
        }
      }
    }
    return null;
  }
}
