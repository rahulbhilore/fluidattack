package com.graebert.storage.integration.kudoDrive;

import com.amazonaws.services.cloudfront.model.InvalidArgumentException;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;
import com.amazonaws.services.dynamodbv2.model.TransactWriteItem;
import com.amazonaws.services.dynamodbv2.model.TransactWriteItemsRequest;
import com.amazonaws.services.dynamodbv2.model.Update;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.common.collect.Iterables;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class SimpleStorage extends Dataplane {

  @NonNls
  public static final String fileIdPrefix = "i:";

  @NonNls
  public static final String folderIdPrefix = "o:";

  @NonNls
  private static final String tableName = TableName.SIMPLE_STORAGE.name;

  @NonNls
  private static final String userPrefix = "u:";

  private static final String ownerPrefix = "OWNER#";
  private static final String sharedPrefix = "SHARED#";
  private static final String folderPrefix = "A#";
  private static final String filePrefix = "B#";
  private static final String skInfoValue = Field.INFO.getName();
  private static final String newPKnewSKIndex = "newPK-newSK-index";
  private static final String parentStatusIndex = "parent-status-index";

  public static Item addFile(
      String newId,
      String owner,
      String name,
      String parent,
      String s3version,
      long size,
      String path,
      Set<String> editors,
      Set<String> viewers,
      String s3Region,
      boolean updateUsage) {
    long currentTime = GMTHelper.utcCurrentTime();
    Item item = new Item()
        .withString(pk, makeFileId(newId))
        .withString(sk, skInfoValue)
        .withString("newPK", ownerPrefix + stripPrefix(owner))
        .withString("newSK", ObjectStatus.NORMAL.label + newId)
        .withString(Field.TYPE.getName(), Field.FILE.getName())
        .withString(Field.ID.getName(), newId)
        .withString(Field.OWNER.getName(), owner)
        .withString(Field.NAME.getName(), name)
        .withString("namelowercase", name.toLowerCase())
        .withString(Field.PARENT.getName(), parent)
        .withLong(Field.CREATION_DATE.getName(), currentTime)
        .withBoolean(Field.DELETED.getName(), false)
        .withString(Field.LAST_MODIFIED_BY.getName(), owner)
        .withLong(Field.LAST_MODIFIED.getName(), currentTime)
        .withString("s3version", s3version)
        .withString(Field.VERSION_ID.getName(), makeVersionId(Long.toString(currentTime)))
        .withLong(Field.SIZE.getName(), size)
        .withString(
            Field.STATUS.getName(), ObjectStatus.NORMAL.label + filePrefix + name.toLowerCase())
        .withString(Field.PATH.getName(), path)
        .withString(Field.S3_REGION.getName(), s3Region);

    if (editors != null && !editors.isEmpty()) {
      item.withStringSet(Field.EDITORS.getName(), editors);
    }
    if (viewers != null && !viewers.isEmpty()) {
      item.withStringSet(Field.VIEWERS.getName(), viewers);
    }

    putItem(tableName, item, DataEntityType.FILES);
    if (updateUsage) {
      updateUsage(owner, size);
    }
    return item;
  }
  /*
      data models:
      Table: PK: pk, SK:sk
      Index: PK: parent, SK: status
      Index: PK: newPK, SK: newSK
          newPK:(OWNED/SHARED)#userId
          newSK:(normal/deleted)#id

      File:
      PK: i:fileid, SK: info
      type: file
      parent:
            root folder      : u:userid
            trash root folder: t:userid
            folder           : o:folderid
      status:
            (normal/deleted)#B#name.toLowerCase()

      FileVersion:
      PK: i:fileid, SK: e:timestamp
      type: version
      sk: e:timestamp
      name: s3 versionid

      FileLink:
      PK: i:fileid, SK: userId
      type: share
      parent:
            root folder      : u:userid
            folder           : o:folderid

      Folder:
      PK: u:folderid, SK: info
      type: folder
      parent:
            root folder      : u:userid
            trash root folder: t:userid
            folder           : o:folderid
      status:
            (normal/deleted)#A#name.toLowerCase()

      FolderLink:
      PK: i:fileid, SK: userId
      type: share
      parent:
            root folder      : u:userid
            folder           : o:folderid

      design consideration:
      - for folders, most important is to show the list of items in a folder.
      - index (parent, status), gives quick access to the child items of a folder with
      dedicated prefix for folders and files
      - index (newPK, newSK), used to differentiate and get files/folders which are owned OR
      shared
      - not use parentId of -1 for root folder but the userId to ensure the partitions are more
       balanced
      - search is by definition a scan operation, but want to only look at undeleted files
      - for now, we are not optimizing the untrashFolder and  older use cases with indexes, as
      they are rare actions.
      - we only save file version objects for overriden version. Latest version is part of file
       object
      - status is lowercase, so that we can check for names case insensitively
      - also we have namelowercase for queries
      - quota will apply to the owner for the latest version of a file and all items in trash.
      It does not apply for older versions or erased files.
      - files are stored in s3 under the person creating. Changing owner does not change the
      path of the file though as that would
        change version history and versionids.
      - delete files are marked deleted on s3 and expire automatically. So are the db records
      - erasing a file is only a db setting so that the data is not accessible via api anymore.
       Does not impact TTL of s3 files if the file was previously deleted

  */

  public static Item addFileVersion(
      Item file,
      S3Regional sampleS3,
      String bucket,
      String s3version,
      String changer,
      String newPath,
      long size,
      long ttlDays,
      boolean updateUsage) {
    long diffSize = size - getFileSize(file);
    // if it's a sample file and modified first time then add the full size to the usage
    if (isSampleFile(file) && !isSampleFileModified(file, sampleS3, bucket)) {
      diffSize = size;
    }
    // we save previous state as a separate version
    String saveVersionId = getLatestVersion(file);

    // and new state is the current one.
    long currentTime = GMTHelper.utcCurrentTime();
    String newVersionId = Long.toString(currentTime);
    log.info("[SF_VERSION] addFileVersion. FilePK: " + file.getString(pk) + " versionId: "
        + makeVersionId(newVersionId));

    // basically old versions have their own entries
    // current one is just the file itself
    Item item = new Item()
            .withString(pk, file.getString(pk))
            .withString(sk, makeVersionId(saveVersionId))
            .withString(Field.TYPE.getName(), "version")
            .withString(Field.ID.getName(), saveVersionId)
            .withString("s3version", file.getString("s3version"))
            .withString(Field.OWNER.getName(), file.getString(Field.OWNER.getName()))
            .withLong(Field.CREATION_DATE.getName(), Long.parseLong(saveVersionId))
            .withString(
                Field.CREATED_BY.getName(), file.getString(Field.LAST_MODIFIED_BY.getName()))
            .withString(Field.PATH.getName(), file.getString(Field.PATH.getName()))
            .withLong(Field.SIZE.getName(), file.getLong(Field.SIZE.getName()))
            .withLong(
                Field.TTL.getName(), (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
        // delete after ttl days
        ;

    log.info("[SF_VERSION] addFileVersion -> putItem. FilePK: " + file.getString(pk)
        + " versionId: " + makeVersionId(newVersionId));

    putItem(tableName, item, DataEntityType.FILES);
    if (newPath != null) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, file.getString(pk), sk, skInfoValue)
              .withUpdateExpression("SET lastModifiedBy = :lastModifiedBy, "
                  + "lastModified = :lastModified, versionId = "
                  + ":versionId, s3version = :s3version, #path "
                  + "= :path, #size = :size")
              .withValueMap(new ValueMap()
                  .withString(":lastModifiedBy", changer)
                  .withLong(":lastModified", currentTime)
                  .withString(":versionId", makeVersionId(newVersionId))
                  .withString(":s3version", s3version)
                  .withString(":path", newPath)
                  .withLong(":size", size))
              .withNameMap(new NameMap()
                  .with("#size", Field.SIZE.getName())
                  .with("#path", Field.PATH.getName())),
          DataEntityType.FILES);
    } else {

      log.info("[SF_VERSION] addFileVersion -> updateItem. FilePK: " + file.getString(pk)
          + " versionId: " + makeVersionId(newVersionId));
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, file.getString(pk), sk, skInfoValue)
              .withUpdateExpression("SET lastModifiedBy = :lastModifiedBy, "
                  + "lastModified = :lastModified, versionId = "
                  + ":versionId, s3version = :s3version, #size "
                  + "= :size")
              .withValueMap(new ValueMap()
                  .withString(":lastModifiedBy", changer)
                  .withLong(":lastModified", currentTime)
                  .withString(":versionId", makeVersionId(newVersionId))
                  .withString(":s3version", s3version)
                  .withLong(":size", size))
              .withNameMap(new NameMap().with("#size", Field.SIZE.getName())),
          DataEntityType.FILES);
    }

    // update info of all shares

    log.info("[SF_VERSION] addFileVersion -> updateShares. FilePK: " + file.getString(pk)
        + " versionId: " + makeVersionId(newVersionId));
    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", file.getString(pk)).with(":sk", userPrefix)),
        DataEntityType.FILES)) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, share.getString(pk), sk, share.getString(sk))
              .withUpdateExpression("SET lastModifiedBy = :lastModifiedBy, lastModified = "
                  + ":lastModified, versionId = :versionId, s3version = "
                  + ":s3version, #size = :size")
              .withValueMap(new ValueMap()
                  .withString(":lastModifiedBy", changer)
                  .withLong(":lastModified", currentTime)
                  .withString(":versionId", makeVersionId(newVersionId))
                  .withString(":s3version", s3version)
                  .withLong(":size", size))
              .withNameMap(new NameMap().with("#size", Field.SIZE.getName())),
          DataEntityType.FILES);
    }

    log.info("[SF_VERSION] addFileVersion -> updateUsage. FilePK: " + file.getString(pk)
        + " versionId: " + makeVersionId(newVersionId));
    if (updateUsage) {
      updateUsage(file.getString(Field.OWNER.getName()), diffSize);
    }

    log.info("[SF_VERSION] addFileVersion -> return. FilePK: " + getItemId(item) + " versionId: "
        + getLatestVersion(file));
    return item;
  }

  public static void addItemShare(Item item, String user, String parent) {
    Item share = new Item()
        .withString(pk, item.getString(pk))
        .withString(sk, user)
        .withString("newPK", sharedPrefix + stripPrefix(user))
        .withString("newSK", ObjectStatus.NORMAL.label + stripPrefix(item.getString(pk)))
        .withString(Field.PARENT.getName(), parent)
        .withString(Field.TYPE.getName(), Field.SHARE.getName())
        .withString(Field.ID.getName(), stripPrefix(item.getString(pk)))
        .withString(Field.OWNER.getName(), item.getString(Field.OWNER.getName()))
        .withString(Field.NAME.getName(), item.getString(Field.NAME.getName()))
        .withString("namelowercase", item.getString("namelowercase"))
        .withLong(Field.CREATION_DATE.getName(), item.getLong(Field.CREATION_DATE.getName()));

    if (item.hasAttribute(Field.LAST_MODIFIED.getName())) {
      share.withLong(Field.LAST_MODIFIED.getName(), item.getLong(Field.LAST_MODIFIED.getName()));
    }

    if (isFile(item)) {
      share
          .withString(
              Field.LAST_MODIFIED_BY.getName(), item.getString(Field.LAST_MODIFIED_BY.getName()))
          .withString("s3version", item.getString("s3version"))
          .withString(Field.VERSION_ID.getName(), item.getString(Field.VERSION_ID.getName()))
          .withLong(Field.SIZE.getName(), item.getLong(Field.SIZE.getName()))
          .withString(
              Field.STATUS.getName(),
              ObjectStatus.NORMAL.label
                  + filePrefix
                  + item.getString(Field.NAME.getName()).toLowerCase());
    } else {
      share.withString(
          Field.STATUS.getName(),
          ObjectStatus.NORMAL.label
              + folderPrefix
              + item.getString(Field.NAME.getName()).toLowerCase());
    }

    putItem(tableName, share, DataEntityType.FILES);
  }

  public static String addFolder(String owner, String name, String parent) {
    return addFolder(owner, name, parent, null, null);
  }

  public static String addFolder(
      String owner, String name, String parent, Set<String> editors, Set<String> viewers) {
    String objectId = makeNonRootFolderId(UUID.randomUUID().toString());
    String newId = stripPrefix(objectId);
    long currentTime = GMTHelper.utcCurrentTime();
    Item item = new Item()
        .withString(pk, objectId)
        .withString(sk, skInfoValue)
        .withString("newPK", ownerPrefix + stripPrefix(owner))
        .withString("newSK", ObjectStatus.NORMAL.label + newId)
        .withString(Field.TYPE.getName(), Field.FOLDER.getName())
        .withString(Field.ID.getName(), newId)
        .withString(Field.OWNER.getName(), owner)
        .withString(Field.NAME.getName(), name)
        .withString("namelowercase", name.toLowerCase())
        .withString(Field.PARENT.getName(), parent)
        .withLong(Field.CREATION_DATE.getName(), currentTime)
        .withBoolean(Field.DELETED.getName(), false)
        .withString(
            Field.STATUS.getName(), ObjectStatus.NORMAL.label + folderPrefix + name.toLowerCase())
        .withLong(Field.LAST_MODIFIED.getName(), currentTime);

    if (editors != null && !editors.isEmpty()) {
      item.withStringSet(Field.EDITORS.getName(), editors);
    }
    if (viewers != null && !viewers.isEmpty()) {
      item.withStringSet(Field.VIEWERS.getName(), viewers);
    }
    putItem(tableName, item, DataEntityType.FILES);
    return newId;
  }

  public static Item getItem(String id) {
    Item obj = getItemFromCache(tableName, pk, id, sk, skInfoValue, DataEntityType.FILES);
    if (obj == null || isErased(obj)) {
      // we should not access these files. We only track these for analytics
      return null;
    }
    return obj;
  }

  public static Item getItemEvenIfErased(String id) {
    return getItemFromCache(tableName, pk, id, sk, skInfoValue, DataEntityType.FILES);
  }

  public static Item getItemShare(String id, String user) {
    return getItemFromCache(tableName, pk, id, sk, user, DataEntityType.FILES);
  }

  public static Item getItemShare(Item item, String user) {
    return getItemShare(item.getString(pk), user);
  }

  public static boolean hasRights(Item obj, String user, boolean editor, AccessType accessType) {
    if (checkOwner(obj, user)) {
      return true;
    }
    if (isItemShare(obj)) {
      return obj.getString(sk).equals(user);
    } else {
      if (!editor) {
        if (obj.hasAttribute(Field.VIEWERS.getName())) {
          if (obj.getStringSet(Field.VIEWERS.getName()).contains(stripPrefix(user))) {
            if (Objects.nonNull(accessType)) {
              ObjectPermissions objectPermissions = new ObjectPermissions(false, true);
              List<String> customPermissions =
                  PermissionsDao.getCustomPermissions(stripPrefix(user), getItemId(obj));
              return objectPermissions.checkPermissions(accessType, customPermissions, isFile(obj));
            }
            return true;
          }
        }
      }
      if (obj.hasAttribute(Field.EDITORS.getName())) {
        if (obj.getStringSet(Field.EDITORS.getName()).contains(stripPrefix(user))) {
          if (Objects.nonNull(accessType)) {
            ObjectPermissions objectPermissions = new ObjectPermissions(false, false);
            List<String> customPermissions =
                PermissionsDao.getCustomPermissions(stripPrefix(user), getItemId(obj));
            return objectPermissions.checkPermissions(accessType, customPermissions, isFile(obj));
          }
          return true;
        }
      }
    }
    return false;
  }

  private static Item getOriginalItem(Item item) {
    return getItem(
        isFile(item) ? makeFileId(getItemId(item)) : makeFolderId(getItemId(item), getOwner(item)));
  }

  /**
   * Remove item access for the users followed by undelete, rename and update parent if needed
   *
   * @param item           - Item to be updated
   * @param users          - Remove item access for these users
   * @param doUpdateParent - Parent of the item should be updated or not
   */
  public static void removeSharedUsersAndUpdateItem(
      @NotNull Item item, JsonArray users, boolean doUpdateParent) {
    Item mainItem = getOriginalItem(item);
    boolean isOriginalItem = true;
    if (Objects.isNull(mainItem)) {
      mainItem = item;
      isOriginalItem = false;
    }
    Set<String> userSet = users.stream().map(user -> (String) user).collect(Collectors.toSet());
    userSet.forEach(userId -> removeItemShare(item, makeUserId(userId)));

    Set<String> fileEditors =
        getEditors(mainItem) != null ? new HashSet<>(getEditors(mainItem)) : new HashSet<>();
    Set<String> fileViewers =
        getViewers(mainItem) != null ? new HashSet<>(getViewers(mainItem)) : new HashSet<>();

    fileEditors.removeAll(userSet);
    fileViewers.removeAll(userSet);
    updateCollaborators(mainItem, fileEditors, fileViewers);

    // if deleted user was the last to modify file, we want to make other collaborators know
    // he was deleted
    Item finalMainItem = mainItem;
    Optional<String> changerOptional = userSet.stream()
        .filter(userId -> getLatestChanger(finalMainItem).equals(userId))
        .findAny();
    if (changerOptional.isPresent()) {
      String userId = changerOptional.get();
      Item user = Users.getUserById(userId);
      if (Objects.isNull(user)) {
        updateModifierAsDeleted(finalMainItem);
      }
    }
    if (doUpdateParent) {
      String parentId = getParentStr(mainItem);
      Item parent = getItem(tableName, pk, parentId, sk, skInfoValue, true, DataEntityType.FILES);
      boolean checkName = false;
      if (Objects.isNull(parent) || !getOwner(parent).equals(getOwner(mainItem))) {
        parentId = makeUserId(getOwner(mainItem));
        checkName = true;
      }
      if (isDeleted(mainItem)) {
        undelete(mainItem);
        if (isOriginalItem) {
          mainItem = getOriginalItem(item);
        } else {
          undelete(item);
          mainItem = getItem(
              tableName,
              pk,
              item.getString(pk),
              sk,
              item.getString(sk),
              true,
              DataEntityType.FILES);
        }
      }
      if (checkName) {
        boolean isFile = isFile(mainItem);
        Set<String> itemNames = getFolderItemNamesSet(parentId, isFile);
        if (!itemNames.isEmpty()) {
          String itemOriginalName = getName(mainItem);
          String newName = Utils.checkAndRename(itemNames, itemOriginalName, !isFile);
          if (!itemOriginalName.equals(newName)) {
            if (isFile) {
              updateFileName(mainItem, newName);
            } else {
              updateFolderName(mainItem, newName);
            }
          }
        }
      }
      updateParent(mainItem, parentId);
      log.info("ERASE : Item " + getItemId(mainItem)
          + " moved to its respective folder of user (owner) " + getOwner(mainItem));
    }
    checkParentAndUpdateModifiedTime(mainItem, getOwner(mainItem), GMTHelper.utcCurrentTime());
  }

  public static Set<String> getFolderItemNamesSet(String id, boolean isFile) {
    Set<String> itemNames = new HashSet<>();
    Iterator<Item> folderItemNames = getFolderItemNames(id, isFile);
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

  public static void moveFolderSharedWithUser(Item folder, String userId) {
    for (Item item : getFolderItems(makeFolderId(getItemId(folder), userId), null, null)) {
      if (!checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      if (checkOwner(item, makeUserId(userId))) {
        Set<String> folderCollaborators = SimpleFluorine.getAllObjectCollaborators(folder);
        Set<String> itemCollaborators = SimpleFluorine.getAllObjectCollaborators(item);
        if (!getOwner(folder).equals(userId)) {
          folderCollaborators.add(getOwner(folder));
          folderCollaborators.remove(userId);
        }
        List<String> commonSharedUsers = new ArrayList<>(folderCollaborators);
        commonSharedUsers.retainAll(itemCollaborators);
        removeSharedUsersAndUpdateItem(item, new JsonArray(commonSharedUsers), false);
      } else {
        removeSharedUsersAndUpdateItem(item, new JsonArray().add(userId), true);
      }
      if (isFolder(item)) {
        moveFolderSharedWithUser(item, userId);
      }
    }
  }

  public static void shareMovedFolderWithParentCollaborators(
      Item folder, String userId, Set<String> parentCollaborators) {
    for (Item item : getFolderItems(makeFolderId(getItemId(folder), userId), null, null)) {
      if (!checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      Set<String> folderCollaborators = new HashSet<>(parentCollaborators);
      Set<String> itemCollaborators = SimpleFluorine.getAllObjectCollaborators(item);
      if (!checkOwner(item, makeUserId(userId))) {
        folderCollaborators.remove(getOwner(item));
      }
      folderCollaborators.removeAll(itemCollaborators);
      for (String collaborator : folderCollaborators) {
        addItemShare(item, makeUserId(collaborator), makeFolderId(getItemId(folder), collaborator));
      }
      if (isFolder(item)) {
        shareMovedFolderWithParentCollaborators(item, userId, parentCollaborators);
      }
    }
  }

  public static boolean checkOwner(Item obj, String user) {
    if (!Utils.isStringNotNullOrEmpty(user)) {
      return false;
    }
    return obj.getString(Field.OWNER.getName()).equals(user);
  }

  /**
   * Check if user has access to this item
   * Scenarios =>
   * 1. User is the owner
   * 2. User is one of the collaborators (editor/viewer)
   * 3. Item is shared with the user explicitly (means making any changes to this item won't
   * affect the original item)
   *
   * @param item   file/folder to check
   * @param userId user to check
   * @return boolean
   */
  public static boolean checkIfUserHasAccessToItem(Item item, String userId) {
    if (Objects.isNull(item) || !Utils.isStringNotNullOrEmpty(userId)) {
      return false;
    }
    return checkOwner(item, makeUserId(userId))
        || isSharedWithUser(item, userId)
        || isItemShareForUser(item, userId);
  }

  public static boolean isSampleFile(Item obj) {
    return obj.hasAttribute("s3version") && obj.getString("s3version").equals("SAMPLE");
  }

  public static boolean isSampleFileModified(Item obj, S3Regional sampleS3, String sampleS3Bucket) {
    boolean isModified = obj.hasAttribute(Field.CREATION_DATE.getName())
        && obj.hasAttribute(Field.LAST_MODIFIED.getName())
        && !Objects.equals(
            obj.getLong(Field.CREATION_DATE.getName()), obj.getLong(Field.LAST_MODIFIED.getName()));
    if (!isModified) {
      return false;
    } else {
      ObjectMetadata metaData =
          sampleS3.getObjectMetadata(sampleS3Bucket, "samples/" + getName(obj));
      if (Objects.nonNull(metaData)) {
        return metaData.getContentLength() != getFileSize(obj);
      } else {
        log.error("[SAMPLE FILE] Object metadata is null for item - " + obj.toJSONPretty());
        return false;
      }
    }
  }

  public static boolean isDeleted(Item obj) {
    return obj.hasAttribute(Field.DELETED.getName()) && obj.getBoolean(Field.DELETED.getName());
  }

  public static boolean isErased(Item obj) {
    return obj.hasAttribute("erased") && obj.getBoolean("erased");
  }

  public static Set<String> getViewers(Item obj) {
    return obj != null && obj.hasAttribute(Field.VIEWERS.getName())
        ? (obj.getStringSet(Field.VIEWERS.getName()))
        : null;
  }

  public static boolean isShared(Item obj) {
    return !obj.getString(sk).equals(skInfoValue)
        || obj.hasAttribute(Field.VIEWERS.getName())
        || obj.hasAttribute(Field.EDITORS.getName());
  }

  public static Set<String> getEditors(Item obj) {
    return obj != null && obj.hasAttribute(Field.EDITORS.getName())
        ? (obj.getStringSet(Field.EDITORS.getName()))
        : null;
  }

  public static String getName(Item obj) {
    return obj.getString(Field.NAME.getName());
  }

  public static String getAccountId(String userId) {
    return "SF_" + userId;
  }

  public static String getOwner(Item obj) {
    return stripPrefix(obj.getString(Field.OWNER.getName()));
  }

  public static String generatePath(String creator, String fileId) {
    return creator + "/" + fileId;
  }

  public static String getPath(Item obj) {
    return obj.getString(Field.PATH.getName());
  }

  public static String getS3Region(Item obj) {
    return obj.getString(Field.S3_REGION.getName());
  }

  public static String getParent(Item obj, String userId) {
    String parent = getParentStr(obj);
    String ownerId = getOwner(obj);
    if (parent.startsWith(userPrefix)) {
      if (!ownerId.equals(userId)) {
        return SimpleFluorine.SHARED_FOLDER;
      } else {
        return SimpleFluorine.ROOT;
      }
    } else {
      return stripPrefix(parent);
    }
  }

  public static String getParentStr(Item obj) {
    String parent = obj.getString(Field.PARENT.getName());
    if (obj.hasAttribute("origparent")) {
      parent = obj.getString("origparent");
    }
    return parent;
  }

  public static long getCreationDate(Item obj) {
    return obj.hasAttribute(Field.CREATION_DATE.getName())
        ? obj.getLong(Field.CREATION_DATE.getName())
        : 0;
  }

  public static long getLatestChangeTime(Item obj) {
    return obj.hasAttribute(Field.LAST_MODIFIED.getName())
        ? obj.getLong(Field.LAST_MODIFIED.getName())
        : getCreationDate(obj);
  }

  public static @NotNull String getLatestVersion(Item obj) {
    return stripPrefix(obj.getString(Field.VERSION_ID.getName()));
  }

  public static String getVersionCreator(Item obj) {
    return obj.hasAttribute(Field.CREATED_BY.getName())
        ? stripPrefix(obj.getString(Field.CREATED_BY.getName()))
        : getOwner(obj);
  }

  public static String getLatestChanger(Item obj) {
    return obj.hasAttribute(Field.LAST_MODIFIED_BY.getName())
        ? stripPrefix(obj.getString(Field.LAST_MODIFIED_BY.getName()))
        : getOwner(obj);
  }

  public static String getS3Version(Item obj) {
    return obj.getString("s3version");
  }

  public static String getDeleteMarker(Item obj) {
    return obj.hasAttribute("deleteMarker") ? obj.getString("deleteMarker") : null;
  }

  public static long getFileSize(Item obj) {
    return obj.hasAttribute(Field.SIZE.getName()) ? obj.getLong(Field.SIZE.getName()) : 0;
  }

  public static boolean isFile(Item item) {
    return item.getString(pk).startsWith(fileIdPrefix);
  }

  public static boolean isItemShare(Item item) {
    return item.getString(sk).startsWith(userPrefix);
  }

  public static boolean isItemShareForUser(Item item, String userId) {
    return item.getString(sk).equals(userPrefix + userId);
  }

  public static boolean isSharedWithUser(Item item, String userId) {
    return (item.hasAttribute(Field.VIEWERS.getName())
            && item.getStringSet(Field.VIEWERS.getName()).contains(userId)
        || item.hasAttribute(Field.EDITORS.getName())
            && item.getStringSet(Field.EDITORS.getName()).contains(userId));
  }

  public static boolean isFolder(Item item) {
    return item.getString(pk).startsWith(folderIdPrefix);
  }

  public static String getItemId(Item obj) {
    return stripPrefix(obj.getString(pk));
  }

  public static String getItemType(Item obj) {
    return obj.getString(Field.TYPE.getName()).toUpperCase();
  }

  public static String getFileVersionId(Item obj) {
    return stripPrefix(obj.getString(sk));
  }

  public static boolean checkName(String parent, String name, boolean isFile, String sk) {
    String status;
    if (isFile) {
      status = ObjectStatus.NORMAL.label + filePrefix + name.toLowerCase();
    } else {
      status = ObjectStatus.NORMAL.label + folderPrefix + name.toLowerCase();
    }

    String filterExpression = "#type <> :type";
    String keyConditionExpression = "parent = :parent and #status = :status";
    NameMap nm =
        new NameMap().with("#status", Field.STATUS.getName()).with("#type", Field.TYPE.getName());
    ValueMap vm = new ValueMap()
        .with(":parent", parent)
        .with(":status", status)
        .withString(":type", Field.SHARE.getName());
    QuerySpec querySpec = new QuerySpec().withKeyConditionExpression(keyConditionExpression);
    if (Utils.isStringNotNullOrEmpty(sk)) {
      filterExpression += " and sk = :sk";
      vm.with(":sk", sk);
    }
    querySpec.withFilterExpression(filterExpression);
    querySpec.withValueMap(vm).withNameMap(nm);
    Iterator<Item> itemIterator =
        query(tableName, parentStatusIndex, querySpec, DataEntityType.FILES).iterator();
    return checkNameStringCase(itemIterator, name);
  }

  private static boolean checkNameStringCase(Iterator<Item> itemIterator, String name) {
    if (itemIterator != null && itemIterator.hasNext()) {
      Item item = itemIterator.next();
      if (item.hasAttribute(Field.NAME.getName())) {
        String objectName = item.getString(Field.NAME.getName());
        return !Utils.isStringNotNullOrEmpty(objectName) || !objectName.equals(name);
      }
    }
    return true;
  }

  public static void updateFolderName(Item item, String name) {
    // assumes file/folder is not deleted.
    if (isDeleted(item)) {
      throw new InvalidArgumentException("Only undeleted items can be renamed.");
    }

    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
            .withUpdateExpression(
                "set #name = :name, #status=:status, " + "namelowercase = :namelowercase")
            .withValueMap(new ValueMap()
                .withString(":name", name)
                .withString(":namelowercase", name.toLowerCase())
                .withString(
                    ":status", ObjectStatus.NORMAL.label + folderPrefix + name.toLowerCase()))
            .withNameMap(new NameMap()
                .with("#name", Field.NAME.getName())
                .with("#status", Field.STATUS.getName())),
        DataEntityType.FILES);

    // update name of all shares

    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", item.getString(pk)).with(":sk", userPrefix)),
        DataEntityType.FILES)) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, share.getString(pk), sk, share.getString(sk))
              .withUpdateExpression(
                  "set #name = :name, #status=:status, namelowercase = " + ":namelowercase")
              .withValueMap(new ValueMap()
                  .withString(":name", name)
                  .withString(":namelowercase", name.toLowerCase())
                  .withString(
                      ":status", ObjectStatus.NORMAL.label + folderPrefix + name.toLowerCase()))
              .withNameMap(new NameMap()
                  .with("#name", Field.NAME.getName())
                  .with("#status", Field.STATUS.getName())),
          DataEntityType.FILES);
    }
  }

  public static void updateFileName(Item item, String name) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
            .withUpdateExpression(
                "set #name = :name, #status=:status, " + "namelowercase = :namelowercase")
            .withValueMap(new ValueMap()
                .withString(":name", name)
                .withString(":namelowercase", name.toLowerCase())
                .withString(":status", ObjectStatus.NORMAL.label + filePrefix + name.toLowerCase()))
            .withNameMap(new NameMap()
                .with("#name", Field.NAME.getName())
                .with("#status", Field.STATUS.getName())),
        DataEntityType.FILES);

    // update name of all shares

    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", item.getString(pk)).with(":sk", userPrefix)),
        DataEntityType.FILES)) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, share.getString(pk), sk, share.getString(sk))
              .withUpdateExpression(
                  "set #name = :name, #status=:status, namelowercase = " + ":namelowercase")
              .withValueMap(new ValueMap()
                  .withString(":name", name)
                  .withString(":namelowercase", name.toLowerCase())
                  .withString(
                      ":status", ObjectStatus.NORMAL.label + filePrefix + name.toLowerCase()))
              .withNameMap(new NameMap()
                  .with("#name", Field.NAME.getName())
                  .with("#status", Field.STATUS.getName())),
          DataEntityType.FILES);
    }
  }

  public static void updateParent(Item item, String parent) {
    // invalidate cache?
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
            .withUpdateExpression("SET parent = :parentId")
            .withValueMap(new ValueMap().withString(":parentId", parent)),
        DataEntityType.FILES);
  }

  public static void updateCollaborators(Item item, Set<String> editors, Set<String> viewers) {
    // invalidate cache?
    if (editors != null && !editors.isEmpty()) {
      if (viewers != null && !viewers.isEmpty()) {
        updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
                .withUpdateExpression("SET viewers = :viewers, editors = :editors")
                .withValueMap(new ValueMap()
                    .withStringSet(":viewers", viewers)
                    .withStringSet(":editors", editors)),
            DataEntityType.FILES);
      } else {
        updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
                .withUpdateExpression("SET editors = :editors REMOVE viewers")
                .withValueMap(new ValueMap().withStringSet(":editors", editors)),
            DataEntityType.FILES);
      }
    } else {
      if (viewers != null && !viewers.isEmpty()) {
        updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
                .withUpdateExpression("SET viewers = :viewers REMOVE editors")
                .withValueMap(new ValueMap().withStringSet(":viewers", viewers)),
            DataEntityType.FILES);
      } else {
        updateItem(
            tableName,
            new UpdateItemSpec()
                .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
                .withUpdateExpression("REMOVE editors, viewers"),
            DataEntityType.FILES);
      }
    }
  }

  public static void updateOwner(Item item, String newOwner, boolean isMainItem) {
    // TODO Batchwrite. We are changing the usage, removing and & adding fileshares, updating the
    //  actual item

    // recheck item name in root folder of new owner
    if (isMainItem) {
      Set<String> itemNames = getFolderItemNamesSet(newOwner, isFile(item));
      if (!itemNames.isEmpty()) {
        String itemOriginalName = getName(item);
        String newName = Utils.checkAndRename(itemNames, itemOriginalName, isFolder(item));
        if (!itemOriginalName.equals(newName)) {
          if (isFile(item)) {
            updateFileName(item, newName);
          } else {
            updateFolderName(item, newName);
          }
        }
      }
    }

    // Logic here:
    // 1. If item is in root (-1). No access for User 2.
    //      Before: User 1 owns object. Parent = -1.
    //      After: User 2 owns object. Parent = -1. User 1 editor for object. Parent = -1
    //      Updates: 1. Owner - set to 2
    //               2. Parent - set to 2:-1
    //               3. Add User 1 to editors (share into -1)
    // 2. If item is in folder (X). No access for User 2
    //      Before: User 1 owns object. Parent = X.
    //      After: User 2 owns object. Parent = -1. User 1 editor for object. Parent = X
    //      Updates: 1. Owner - set to 2
    //               2. Parent - set to 2:-1
    //               3. Add User 1 to editors (share into X)
    // 3. If item is in folder (X). User 2 has access. Object in folder Y for User 2
    //      Before: User 1 owns object. Parent = X. User 2 collaborator. Parent = Y
    //      After: User 2 owns object. Parent = Y. User 1 editor for object. Parent = X
    //      Updates: 1. Owner - set to 2
    //               2. Parent - set to o:Y
    //               3. Add User 1 to editors (share into X)

    // UPDATED LOGIC -
    // https://docs.google.com/document/d/1ZzWbYhP7JQbmjVeALlDndF_t3msIbI2c32lGNSAw2Ow/edit#heading=h.xa73euwmzizd

    // updates sharing - remove user 2, add user 1
    Set<String> editors =
        getEditors(item) != null ? new HashSet<>(getEditors(item)) : new HashSet<>();
    editors.remove(stripPrefix(newOwner));
    editors.add(getOwner(item));

    Set<String> viewers =
        getViewers(item) != null ? new HashSet<>(getViewers(item)) : new HashSet<>();
    viewers.remove(stripPrefix(newOwner));

    // by default - set to 2:-1
    String newParent = newOwner;

    // if user 2 had access - set parent to o:Y
    Item share = getItemShare(item.getString(pk), newOwner);
    String parentId;
    boolean newParentIdExist = false;
    if (share != null) {
      parentId = share.getString(Field.PARENT.getName());
      if (Utils.isStringNotNullOrEmpty(parentId)) {
        newParentIdExist = true;
      }
    } else {
      parentId = item.getString(Field.PARENT.getName());
      if (Utils.isStringNotNullOrEmpty(parentId) && !parentId.startsWith(userPrefix)) {
        newParentIdExist = true;
      }
    }
    if (newParentIdExist) {
      Item parent = getItem(parentId);
      if (Objects.nonNull(parent)
          && (!isMainItem || isSharedWithUser(parent, stripPrefix(newOwner)))) {
        newParent = parentId;
      }
    }

    // parent, editor, owner are always updated. Viewers - can be removed.
    UpdateItemSpec update = new UpdateItemSpec()
        .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
        .withNameMap(new NameMap()
            .with("#owner", Field.OWNER.getName())
            .with("#parent", Field.PARENT.getName())
            .with("#newPK", "newPK"));
    ValueMap valueMap = new ValueMap()
        .withString(":newPK", ownerPrefix + stripPrefix((newOwner)))
        .withString(":newOwner", newOwner)
        .withStringSet(":editors", editors)
        .withString(":newParent", newParent);
    if (!viewers.isEmpty()) {
      valueMap.withStringSet(":viewers", viewers);
      update
          .withUpdateExpression(
              "SET #newPK = :newPK, #owner = :newOwner, #parent = :newParent, editors=:editors, "
                  + "viewers=:viewers")
          .withValueMap(valueMap);
    } else {
      update
          .withUpdateExpression(
              "SET #newPK = :newPK, #owner = :newOwner, #parent = :newParent, editors=:editors REMOVE "
                  + Field.VIEWERS.getName())
          .withValueMap(valueMap);
    }
    final String oldOwner = item.getString(Field.OWNER.getName());
    final String oldParent = item.getString(Field.PARENT.getName());
    // this is to copy item, so item = oldItem, itemForShare - item with updated fields
    final Item itemForShare = Item.fromMap(item.asMap())
        .withString(Field.OWNER.getName(), newOwner)
        .withString(Field.PARENT.getName(), newParent);
    updateItem(tableName, update, DataEntityType.FILES);
    // leave as is for user A
    addItemShare(itemForShare, oldOwner, oldParent);
    if (share != null) {
      removeItemShare(item, newOwner);
    }
    if (isFile(item)) {
      updateUsage(oldOwner, -1 * getFileSize(item));
      updateUsage(newOwner, getFileSize(item));
    }
  }

  public static void updateOwnerForFolders(String folderId, String userId, String newOwner) {
    Iterator<Item> items =
        getFolderItems(makeFolderId(folderId, userId), makeUserId(userId), null).iterator();
    Item item;
    while (items.hasNext()) {
      item = items.next();
      updateOwner(item, newOwner, false);
      if (isFolder(item)) {
        updateOwnerForFolders(getItemId(item), userId, newOwner);
      }
    }
  }

  private static Iterator<Item> getInfoAndShareItemsForObject(Item object) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withHashKey(pk, object.getString(pk))
                .withFilterExpression("attribute_not_exists(#erased) and" + " #type <> :type")
                .withNameMap(
                    new NameMap().with("#erased", "erased").with("#type", Field.TYPE.getName()))
                .withValueMap(new ValueMap().withString(":type", "version")),
            DataEntityType.FILES)
        .iterator();
  }

  /**
   * update modified time for the owned and all the shared items
   *
   * @param originalItem     - main item
   * @param lastModifiedTime - current time which is to be updated
   */
  public static void updateModifiedTime(Item originalItem, long lastModifiedTime) {
    Iterator<Item> allItemsIterator = getInfoAndShareItemsForObject(originalItem);
    allItemsIterator.forEachRemaining(item -> {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
              .withUpdateExpression("SET lastModified = :lastModified")
              .withValueMap(new ValueMap().withLong(":lastModified", lastModifiedTime)),
          DataEntityType.FILES);
    });
  }

  /**
   * update modifier for the owned and all the shared items
   *
   * @param originalItem - main item
   * @param userId       - latest modifier
   */
  public static void updateModifier(Item originalItem, String userId) {
    Iterator<Item> allItemsIterator = getInfoAndShareItemsForObject(originalItem);
    allItemsIterator.forEachRemaining(item -> {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
              .withUpdateExpression("SET lastModifiedBy = :lastModifiedBy")
              .withValueMap(new ValueMap().withString(":lastModifiedBy", makeUserId(userId))),
          DataEntityType.FILES);
    });
  }

  public static boolean checkQuota(String userId, long usage, JsonObject instanceOptions) {
    long quota = Users.getSamplesQuota(stripPrefix(userId), instanceOptions);
    if (quota == -1) {
      return true;
    }

    long currentUsage = Users.getSamplesUsage(stripPrefix(userId));
    return (currentUsage + usage) < quota;
  }

  public static void updateUsage(String userId, long usage) {
    Users.updateUsage(stripPrefix(userId), usage, false);
  }

  public static void setUsage(String userId, long usage) {
    Users.updateUsage(stripPrefix(userId), usage, true);
  }

  public static void hideItemShare(Item share, int ttlDays) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, share.getString(pk), sk, share.getString(sk))
            .withUpdateExpression("SET #ttl = :ttl, #status = :status, #newSK = :newSK")
            .withValueMap(new ValueMap()
                .withLong(":ttl", (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
                .withString(
                    ":status",
                    share
                        .getString(Field.STATUS.getName())
                        .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label))
                .withString(
                    ":newSK",
                    share
                        .getString("newSK")
                        .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label)))
            .withNameMap(new NameMap()
                .with("#status", Field.STATUS.getName())
                .with("#newSK", "newSK")
                .with("#ttl", Field.TTL.getName())),
        DataEntityType.FILES);
  }

  public static void hideItemShares(Item item, int ttlDays) {
    // hide shares

    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", item.getString(pk)).with(":sk", userPrefix)),
        DataEntityType.FILES)) {
      hideItemShare(share, ttlDays);
    }
  }

  public static void unhideItemShare(Item share) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, share.getString(pk), sk, share.getString(sk))
            .withUpdateExpression("SET #status = :status, #newSK = :newSK REMOVE #ttl")
            .withValueMap(new ValueMap()
                .withString(
                    ":status",
                    ObjectStatus.NORMAL.label
                        + (isFile(share) ? filePrefix : folderPrefix)
                        + getName(share).toLowerCase())
                .withString(
                    ":newSK", ObjectStatus.NORMAL.label + share.getString(Field.ID.getName())))
            .withNameMap(new NameMap()
                .with("#status", Field.STATUS.getName())
                .with("#newSK", "newSK")
                .with("#ttl", Field.TTL.getName())),
        DataEntityType.FILES);
  }

  public static void unhideItemShares(Item item) {
    // unhide shares
    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withFilterExpression("begins_with(#status, :status)")
            .withValueMap(new ValueMap()
                .with(":pk", item.getString(pk))
                .with(":sk", userPrefix)
                .with(":status", ObjectStatus.DELETED.label))
            .withNameMap(new NameMap().with("#status", Field.STATUS.getName())),
        DataEntityType.FILES)) {
      unhideItemShare(share);
    }
  }

  public static void delete(Item item, int ttlDays, String deleteMarker) {
    // invalidate cache?
    if (deleteMarker != null) {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
              .withUpdateExpression("SET #deleted = :deleted, #ttl = :ttl, deleteMarker "
                  + "= :deleteMarker, #status = :status, #newSK = :newSK")
              .withValueMap(new ValueMap()
                  .withBoolean(":deleted", true)
                  .withString(":deleteMarker", deleteMarker)
                  .withLong(":ttl", (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
                  .withString(
                      ":status",
                      item.getString(Field.STATUS.getName())
                          .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label))
                  .withString(
                      ":newSK",
                      item.getString("newSK")
                          .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label)))
              .withNameMap(new NameMap()
                  .with("#deleted", Field.DELETED.getName())
                  .with("#ttl", Field.TTL.getName())
                  .with("#newSK", "newSK")
                  .with("#status", Field.STATUS.getName())),
          DataEntityType.FILES);
    } else {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
              .withUpdateExpression(
                  "SET #deleted = :deleted, #ttl = :ttl, #status = :status, " + "#newSK = :newSK")
              .withValueMap(new ValueMap()
                  .withBoolean(":deleted", true)
                  .withLong(":ttl", (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
                  .withString(
                      ":status",
                      item.getString(Field.STATUS.getName())
                          .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label))
                  .withString(
                      ":newSK",
                      item.getString("newSK")
                          .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label)))
              .withNameMap(new NameMap()
                  .with("#deleted", Field.DELETED.getName())
                  .with("#ttl", Field.TTL.getName())
                  .with("#newSK", "newSK")
                  .with("#status", Field.STATUS.getName())),
          DataEntityType.FILES);
    }
    hideItemShares(item, ttlDays);
  }

  public static void deleteAndResetParent(Item item, int ttlDays, String deleteMarker) {
    String updateExpression =
        "SET #deleted = :deleted, parent = :parent, origparent= :origparent, #ttl = :ttl, #newSK "
            + "= :newSK";
    ValueMap valueMap = new ValueMap()
        .withBoolean(":deleted", true)
        .withLong(":ttl", (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
        .withString(":parent", "t:" + getOwner(item))
        .withString(":origparent", item.getString(Field.PARENT.getName()))
        .withString(
            ":newSK",
            item.getString("newSK").replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label));

    // invalidate cache?
    if (deleteMarker != null) {
      valueMap.withString(":deleteMarker", deleteMarker);
      updateExpression += ", deleteMarker =:deleteMarker";
    }

    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
        .withUpdateExpression(updateExpression)
        .withValueMap(valueMap)
        .withNameMap(new NameMap()
            .with("#deleted", Field.DELETED.getName())
            .with("#ttl", Field.TTL.getName())
            .with("#newSK", "newSK"));
    updateItem(tableName, updateItemSpec, DataEntityType.FILES);
    hideItemShares(item, ttlDays);
  }

  public static void undeleteAndResetParent(Item item) {
    // invalidate cache?
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
            .withUpdateExpression("SET #deleted = :deleted, #status = :status, "
                + "parent = :parent, #newSK = :newSK REMOVE "
                + "origparent, #ttl, deleteMarker")
            .withValueMap(new ValueMap()
                .withBoolean(":deleted", false)
                .withString(
                    ":status",
                    ObjectStatus.NORMAL.label
                        + (isFile(item) ? filePrefix : folderPrefix)
                        + getName(item).toLowerCase())
                .withString(
                    ":newSK", ObjectStatus.NORMAL.label + item.getString(Field.ID.getName()))
                .withString(":parent", item.getString("origparent")))
            .withNameMap(new NameMap()
                .with("#deleted", Field.DELETED.getName())
                .with("#ttl", Field.TTL.getName())
                .with("#newSK", "newSK")
                .with("#status", Field.STATUS.getName())),
        DataEntityType.FILES);

    unhideItemShares(item);
  }

  public static void undelete(Item item) {
    // invalidate cache?
    if (item.hasAttribute("origparent")) {
      undeleteAndResetParent(item);
    } else {
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, skInfoValue)
              .withUpdateExpression("SET #deleted = :deleted, #status = :status, "
                  + "#newSK = :newSK REMOVE #ttl, deleteMarker")
              .withValueMap(new ValueMap()
                  .withBoolean(":deleted", false)
                  .withString(
                      ":status",
                      ObjectStatus.NORMAL.label
                          + (isFile(item) ? filePrefix : folderPrefix)
                          + getName(item).toLowerCase())
                  .withString(
                      ":newSK", ObjectStatus.NORMAL.label + item.getString(Field.ID.getName())))
              .withNameMap(new NameMap()
                  .with("#deleted", Field.DELETED.getName())
                  .with("#ttl", Field.TTL.getName())
                  .with("#newSK", "newSK")
                  .with("#status", Field.STATUS.getName())),
          DataEntityType.FILES);
      unhideItemShares(item);
    }
  }

  public static boolean isEmptyFolder(String folderId) {
    ItemCollection<QueryOutcome> itemCollection = query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withKeyConditionExpression("parent = :parent and begins_with(#status, :status)")
            .withFilterExpression("#type <> :type")
            .withValueMap(new ValueMap()
                .withString(":parent", folderId)
                .withString(":status", ObjectStatus.NORMAL.label)
                .withString(":type", Field.SHARE.getName()))
            .withNameMap(new NameMap()
                .with("#status", Field.STATUS.getName())
                .with("#type", Field.TYPE.getName())),
        DataEntityType.FILES);
    return !itemCollection.iterator().hasNext();
  }

  public static List<Item> sortParentFoldersForCreationDate(
      Set<Item> parentFolders, String userId) {
    return parentFolders.stream()
        .filter(item -> item.hasAttribute(Field.CREATION_DATE.getName())
            && (Objects.isNull(userId) || checkOwner(item, makeUserId(userId))))
        .sorted(Comparator.comparing(item -> ((Item) item).getLong(Field.CREATION_DATE.getName()))
            .reversed())
        .collect(Collectors.toList());
  }

  public static void eraseFile(Item item, int ttlDays) {
    // we will just set TTL and set the file, so that it cannot be found. This helps us identify
    // files still in s3 before they are cycled off.
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
            .withUpdateExpression("SET #erased = :erased, #ttl = :ttl, #status = :status")
            .withValueMap(new ValueMap()
                .withLong(":ttl", (int) (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))
                .withBoolean(":erased", true)
                .withString(
                    ":status",
                    item.getString(Field.STATUS.getName())
                        .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label)))
            .withNameMap(new NameMap()
                .with("#erased", "erased")
                .with("#ttl", Field.TTL.getName())
                .with("#status", Field.STATUS.getName())),
        DataEntityType.FILES);
    removeItemShares(item);
  }

  public static void removeItemShare(Item item) {
    deleteItem(tableName, pk, item.getString(pk), sk, item.getString(sk), DataEntityType.FILES);
  }

  public static void removeItemShare(Item item, String user) {
    deleteItem(tableName, pk, item.getString(pk), sk, user, DataEntityType.FILES);
  }

  public static void removeItemShares(Item item) {
    for (Item share : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", item.getString(pk)).with(":sk", userPrefix)),
        DataEntityType.FILES)) {
      removeItemShare(share);
    }
  }

  public static void removeFolderItemShares(String folderId, String userId) {
    for (Item item : getFolderItems(makeFolderId(folderId, userId), null, null)) {
      if (!checkIfUserHasAccessToItem(item, userId)) {
        continue;
      }
      if (checkOwner(item, makeUserId(userId))) {
        removeItemShares(item);
      } else {
        removeSharedUsersAndUpdateItem(item, new JsonArray().add(userId), true);
      }
      if (isFolder(item)) {
        removeFolderItemShares(getItemId(item), userId);
      }
    }
  }

  public static void removeFileVersions(Item item) {
    for (Item version : query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", item.getString(pk)).with(":sk", "e:")),
        DataEntityType.FILES)) {
      deleteItem(
          tableName, pk, version.getString(pk), sk, version.getString(sk), DataEntityType.FILES);
    }
  }

  public static void removeFolder(Item item) {
    // invalidate cache?
    deleteItem(tableName, pk, item.getString(pk), sk, item.getString(sk), DataEntityType.FILES);
    removeItemShares(item);
  }

  public static void removeFileVersion(Item item) {
    deleteItem(tableName, pk, item.getString(pk), sk, item.getString(sk), DataEntityType.FILES);
  }

  public static String makeFileId(String id) {
    if (Objects.nonNull(id) && id.startsWith(fileIdPrefix)) {
      // just to make sure if we have used this function if not required
      return id;
    }
    return fileIdPrefix + id;
  }

  public static String makeNonRootFolderId(String id) {
    if (Objects.nonNull(id) && id.startsWith(folderIdPrefix)) {
      // just to make sure if we have used this function if not required
      return id;
    }
    return folderIdPrefix + id;
  }

  public static String makeFolderId(String id, String user) {
    if (id == null
        || id.equals(SimpleFluorine.ROOT)
        || id.equals(SimpleFluorine.SHARED_FOLDER)
        || id.equals(SimpleFluorine.ROOT_FOLDER_ID)) {
      if (Objects.nonNull(user) && user.startsWith(userPrefix)) {
        // just to make sure if we have used this function if not required
        return user;
      }
      return userPrefix + user;
    } else {
      if (id.startsWith(folderIdPrefix)) {
        // just to make sure if we have used this function if not required
        return id;
      }
      return folderIdPrefix + id;
    }
  }

  public static String makeTrashFolderId(String id, String user) {
    return id.equals(SimpleFluorine.ROOT_FOLDER_ID) ? "t:" + user : folderIdPrefix + id;
  }

  public static String makeUserId(String id) {
    if (Objects.nonNull(id) && id.startsWith(userPrefix)) {
      // just to make sure if we have used this function if not required
      return id;
    }
    return userPrefix + id;
  }

  public static String makeVersionId(String id) {
    return "e:" + id;
  }

  public static String stripPrefix(String id) {
    return id.substring(2);
  }

  // check recursively if parent is not a sub folder of moving folder
  public static boolean isNotChild(String parentId, String folderId) {
    if (parentId.equals(folderId)) {
      return true;
    }
    for (Item item : getFolderItems(folderId, null, null)) {
      if (isFolder(item)) {
        String id = getItemId(item);
        if (isNotChild(parentId, id)) {
          return true;
        }
      }
    }
    return false;
  }

  public static List<Item> getItems(QueryResult queryResult) {
    return ItemUtils.toItemList(queryResult.getItems());
  }

  public static String getContinuationToken(QueryResult queryResult) {
    if (Objects.nonNull(queryResult.getLastEvaluatedKey())) {
      Item keyItem = ItemUtils.toItem(queryResult.getLastEvaluatedKey());
      String originalString =
          keyItem.getString(pk) + hashSeparator + keyItem.getString(Field.STATUS.getName());
      return Base64.getEncoder().encodeToString(originalString.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }

  // all items in folder that are not deleted
  // not a fan of the filter expression, but some users might not have access to all child items.
  public static ItemCollection<QueryOutcome> getFolderItems(
      String folderId, String userId, String pageToken) {
    PrimaryKey startKey = decodePageTokenToPrimaryKey(folderId, pageToken);
    QuerySpec query = new QuerySpec()
        .withKeyConditionExpression("parent = :parent and begins_with(#status, :status)")
        .withNameMap(new NameMap().with("#newPK", "newPK").with("#status", Field.STATUS.getName()));

    ValueMap valueMap =
        new ValueMap().with(":parent", folderId).with(":status", ObjectStatus.NORMAL.label);

    if (userId == null) {
      query.withFilterExpression("begins_with(#newPK, :newPK)");
      valueMap.with(":newPK", ownerPrefix);
    } else {
      query.withFilterExpression("#newPK = :owner");
      valueMap.with(":owner", ownerPrefix + stripPrefix(userId));
    }
    query.withValueMap(valueMap);
    if (startKey != null) {
      query = query.withExclusiveStartKey(startKey);
    }
    return query(tableName, parentStatusIndex, query, DataEntityType.FILES);
  }

  public static Iterator<Item> getFolderItemNames(String folderId, boolean isFile) {
    return query(
            tableName,
            parentStatusIndex,
            new QuerySpec()
                .withKeyConditionExpression("#parent = :parent and begins_with(#status, :status)")
                .withFilterExpression("#type <> :type")
                .withNameMap(new NameMap()
                    .with("#parent", Field.PARENT.getName())
                    .with("#status", Field.STATUS.getName())
                    .with("#name", Field.NAME.getName())
                    .with("#type", Field.TYPE.getName()))
                .withValueMap(new ValueMap()
                    .withString(":parent", folderId)
                    .withString(
                        ":status", ObjectStatus.NORMAL.label + (isFile ? filePrefix : folderPrefix))
                    .withString(":type", Field.SHARE.getName()))
                .withProjectionExpression("#name"),
            DataEntityType.FILES)
        .iterator();
  }

  public static QueryResult getFolderItemsWithQueryResult(
      String folderId, String userId, String pageToken) {
    QueryRequest queryRequest = new QueryRequest()
        .withTableName(getTableForRead(tableName).getTableName())
        .withIndexName(parentStatusIndex)
        .withKeyConditionExpression("parent = :parent and begins_with(#status, :status)")
        .withExpressionAttributeNames(
            new NameMap().with("#newPK", "newPK").with("#status", Field.STATUS.getName()))
        .withLimit(SimpleFluorine.PAGE_SIZE);
    Map<String, AttributeValue> valueMap = new HashMap<>();
    valueMap.put(":parent", new AttributeValue().withS(folderId));
    valueMap.put(":status", new AttributeValue().withS(ObjectStatus.NORMAL.label));
    if (userId == null) {
      queryRequest.withFilterExpression("begins_with(#newPK, :newPK)");
      valueMap.put(":newPK", new AttributeValue().withS(ownerPrefix));
    } else {
      queryRequest.withFilterExpression("#newPK = :owner");
      valueMap.put(":owner", new AttributeValue().withS(ownerPrefix + stripPrefix(userId)));
    }
    queryRequest.withExpressionAttributeValues(valueMap);
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      PrimaryKey startKey = decodePageTokenToPrimaryKey(folderId, pageToken);
      if (Objects.nonNull(startKey)) {
        queryRequest.withExclusiveStartKey(ItemUtils.toAttributeValueMap(startKey));
      }
    }
    return query(queryRequest, DataEntityType.FILES);
  }

  public static QueryResult getSharedFolderItemsWithQueryResult(
      String userId, String pageToken, String parentFolderId) {
    Map<String, AttributeValue> valueMap = new HashMap<>();
    String folderId;
    if (Objects.nonNull(parentFolderId)) {
      folderId = makeFolderId(parentFolderId, userId);
    } else {
      folderId = makeFolderId(SimpleFluorine.SHARED_FOLDER, userId);
    }
    valueMap.put(":parent", new AttributeValue().withS(folderId));
    valueMap.put(":shared", new AttributeValue().withS(sharedPrefix + userId));
    valueMap.put(":status", new AttributeValue().withS(ObjectStatus.NORMAL.label));
    QueryRequest queryRequest = new QueryRequest()
        .withTableName(getTableForRead(tableName).getTableName())
        .withIndexName(parentStatusIndex)
        .withKeyConditionExpression("parent = :parent and begins_with(#status, :status)")
        .withFilterExpression("#newPK = :shared")
        .withExpressionAttributeNames(
            new NameMap().with("#newPK", "newPK").with("#status", Field.STATUS.getName()))
        .withExpressionAttributeValues(valueMap)
        .withLimit(SimpleFluorine.PAGE_SIZE);
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      PrimaryKey startKey = decodePageTokenToPrimaryKey(folderId, pageToken);
      if (Objects.nonNull(startKey)) {
        queryRequest.withExclusiveStartKey(ItemUtils.toAttributeValueMap(startKey));
      }
    }
    return query(queryRequest, DataEntityType.FILES);
  }

  public static List<Item> getFolderItemShares(String folderId, String userId, boolean fromTrash) {
    String parent;
    if (!folderId.startsWith(folderIdPrefix)) {
      parent = makeFolderId(folderId, userId);
    } else {
      parent = folderId;
    }
    QuerySpec query = new QuerySpec()
        .withKeyConditionExpression("#parent = :parent and begins_with(#status, :status)")
        .withFilterExpression("#newPK = :newPK")
        .withNameMap(new NameMap()
            .with("#parent", Field.PARENT.getName())
            .with("#status", Field.STATUS.getName())
            .with("#newPK", "newPK"))
        .withValueMap(new ValueMap()
            .with(":parent", parent)
            .with(":status", (!fromTrash) ? ObjectStatus.NORMAL.label : ObjectStatus.DELETED.label)
            .with(":newPK", sharedPrefix + userId));

    Iterator<Item> sharedItems =
        query(tableName, parentStatusIndex, query, DataEntityType.FILES).iterator();
    List<Item> finalList = new ArrayList<>();
    while (sharedItems.hasNext()) {
      Item item = sharedItems.next();
      Item originalItem = getItem(item.getString(pk));
      if (Objects.isNull(originalItem)
          || getParent(originalItem, userId).equals(getParent(item, userId))) {
        continue;
      }
      finalList.add(item);
    }
    return finalList;
  }

  private static PrimaryKey decodePageTokenToPrimaryKey(String folderId, String pageToken) {
    PrimaryKey startKey = null;
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      String decoded = new String(Base64.getDecoder().decode(pageToken));
      String[] components = decoded.split("#", 2);
      if (components.length > 1) {
        startKey = new PrimaryKey();
        startKey.addComponent(Field.PARENT.getName(), folderId); // HASH/PARTITION KEY
        startKey.addComponent(Field.STATUS.getName(), components[1]); // RANGE/SORT KEY
        startKey.addComponent(pk, components[0]);
        startKey.addComponent(sk, skInfoValue);
      }
    }
    return startKey;
  }

  public static ItemCollection<QueryOutcome> getSubFolder(
      String folderId, String name, String userId) {
    return query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withKeyConditionExpression("parent = :parent AND #status = :status")
            .withFilterExpression("#newPK = :owner OR #newPK = :shared")
            .withValueMap(new ValueMap()
                .with(":parent", folderId)
                .with(":owner", ownerPrefix + stripPrefix(userId))
                .with(":shared", sharedPrefix + stripPrefix(userId))
                .with(":status", ObjectStatus.NORMAL.label + folderPrefix + name.toLowerCase()))
            .withNameMap(
                new NameMap().with("#newPK", "newPK").with("#status", Field.STATUS.getName())),
        DataEntityType.FILES);
  }

  public static ItemCollection<QueryOutcome> getFileInFolder(
      String folderId, String name, String userId) {
    return query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withKeyConditionExpression("parent = :parent AND #status = :status")
            .withFilterExpression("#newPK = :owner OR #newPK = :shared")
            .withValueMap(new ValueMap()
                .with(":parent", folderId)
                .with(":owner", ownerPrefix + stripPrefix(userId))
                .with(":shared", sharedPrefix + stripPrefix(userId))
                .with(":status", ObjectStatus.NORMAL.label + filePrefix + name.toLowerCase()))
            .withNameMap(
                new NameMap().with("#status", Field.STATUS.getName()).with("#newPK", "newPK")),
        DataEntityType.FILES);
  }

  // all folder in folder that are not deleted
  public static ItemCollection<QueryOutcome> getSubFolders(String folderId, String userId) {
    return query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withKeyConditionExpression("parent = :parent AND begins_with(#status, :status)")
            .withFilterExpression("#newPK = :owner OR #newPK = :shared")
            .withValueMap(new ValueMap()
                .with(":parent", folderId)
                .with(":owner", ownerPrefix + stripPrefix(userId))
                .with(":shared", sharedPrefix + stripPrefix(userId))
                .with(":status", ObjectStatus.NORMAL.label + folderPrefix))
            .withNameMap(
                new NameMap().with("#status", Field.STATUS.getName()).with("#newPK", "newPK")),
        DataEntityType.FILES);
  }

  // all items in folder that are deleted. Should only be called on trash folder
  // folderId begins with t:
  public static QueryResult getTrashFolderItems(String folderId, String pageToken) {
    Map<String, AttributeValue> valueMap = new HashMap<>();
    valueMap.put(":parent", new AttributeValue().withS(folderId));
    QueryRequest queryRequest = new QueryRequest()
        .withTableName(getTableForRead(tableName).getTableName())
        .withIndexName(parentStatusIndex)
        .withKeyConditionExpression("parent = :parent")
        .withFilterExpression("attribute_not_exists(#erased)")
        .withExpressionAttributeNames(new NameMap().with("#erased", "erased"))
        .withExpressionAttributeValues(valueMap)
        .withLimit(SimpleFluorine.PAGE_SIZE);
    if (Utils.isStringNotNullOrEmpty(pageToken)) {
      PrimaryKey startKey = decodePageTokenToPrimaryKey(folderId, pageToken);
      if (Objects.nonNull(startKey)) {
        queryRequest.withExclusiveStartKey(ItemUtils.toAttributeValueMap(startKey));
      }
    }
    return query(queryRequest, DataEntityType.FILES);
  }

  // get all deleted items owned by user
  public static ItemCollection<QueryOutcome> getAllDeletedItemsOwnedByUser(
      String owner, boolean areFiles) {
    return query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec()
            .withKeyConditionExpression("#newPK = :newPK AND begins_with(#newSK, :newSK)")
            .withFilterExpression("attribute_not_exists(#erased) and #type = :type")
            .withValueMap(new ValueMap()
                .withString(":newPK", ownerPrefix + stripPrefix(owner))
                .withString(":newSK", ObjectStatus.DELETED.label)
                .withString(":type", (areFiles) ? "file" : "folder"))
            .withNameMap(new NameMap()
                .with("#newPK", "newPK")
                .with("#newSK", "newSK")
                .with("#erased", "erased")
                .with("#type", "type")),
        DataEntityType.FILES);
  }

  public static Map<String, ArrayList<Item>> eraseAllItems(
      Iterable<Item> items, String userId, long ttlDays) {
    Map<String, ArrayList<Item>> allItems = new HashMap<>();
    ArrayList<Item> files = new ArrayList<>();
    ArrayList<Item> folders = new ArrayList<>();
    try {
      ArrayList<Item> its = new ArrayList<>();
      for (Item item : items) {
        its.add(item);
      }

      HashMap<String, AttributeValue> expressionAttributeValues = new HashMap<>();
      expressionAttributeValues.put(":erased", new AttributeValue().withBOOL(true));
      expressionAttributeValues.put(":ttl", new AttributeValue().withN(String.valueOf((int)
              (new Date().getTime() / 1000 + 24 * 60 * 60 * ttlDays))));

      HashMap<String, String> expresionAttributesNames = new HashMap<>();
      expresionAttributesNames.put("#erased", "erased");
      expresionAttributesNames.put("#ttl", Field.TTL.getName());
      expresionAttributesNames.put("#status", Field.STATUS.getName());

      Utils.splitList(its, 25).forEach(l -> {
        AtomicLong freeUsage = new AtomicLong(0);

        Collection<TransactWriteItem> actions = new ArrayList<>();

        l.forEach(item -> {
          if (isFile(item)) {
            freeUsage.addAndGet(getFileSize(item));
            files.add(item);
          } else {
            folders.add(item);
          }
          expressionAttributeValues.put(
              ":status",
              new AttributeValue()
                  .withS(item.getString(Field.STATUS.getName())
                      .replace(ObjectStatus.NORMAL.label, ObjectStatus.DELETED.label)));
          HashMap<String, AttributeValue> itemKeys = new HashMap<>();
          itemKeys.put(pk, new AttributeValue(item.getString(pk)));
          itemKeys.put(sk, new AttributeValue(item.getString(sk)));

          Update updItem = createUpdate(
              tableName,
              "SET #erased = :erased, #ttl = :ttl, #status = :status",
              itemKeys,
              expresionAttributesNames,
              expressionAttributeValues);

          actions.add(new TransactWriteItem().withUpdate(updItem));
        });

        if (!actions.isEmpty()) {

          TransactWriteItemsRequest twir = createTWIR(actions);
          executeTransactWriteItems(twir);

          // update usage taken by deleted files
          //          if (freeUsage.get() != 0) {
          //            updateUsage(makeUserId(userId), -1 * freeUsage.get());
          //          }
        }
      });
      allItems.put(Field.FILES.getName(), files);
      allItems.put(Field.FOLDERS.getName(), folders);
    } catch (Exception ignored) {
    }
    return allItems;
  }

  // file versions
  public static ItemCollection<QueryOutcome> getFileVersions(String fileId) {
    return query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
            .withValueMap(new ValueMap().with(":pk", fileId).with(":sk", "e:"))
            .withScanIndexForward(false),
        DataEntityType.FILES);
  }

  public static Item getFileVersion(String fileId, String versionId) {
    return getItem(tableName, pk, fileId, sk, versionId, true, DataEntityType.FILES);
  }

  public static void deleteFileVersion(String fileId, String versionId) {
    deleteItem(tableName, pk, fileId, sk, versionId, DataEntityType.FILES);
  }

  // search
  public static Iterator<Item> searchForName(String ownerId, String query) {
    ItemCollection<QueryOutcome> ownedFiles = query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec()
            .withKeyConditionExpression("#newPK = :newPK AND begins_with(#newSK, :newSK)")
            .withFilterExpression("contains(#name, :q)")
            .withValueMap(new ValueMap()
                .withString(":newPK", ownerPrefix + stripPrefix(ownerId))
                .withString(":newSK", ObjectStatus.NORMAL.label)
                .withString(":q", query.toLowerCase()))
            .withNameMap(new NameMap()
                .with("#newPK", "newPK")
                .with("#newSK", "newSK")
                .with("#name", "namelowercase")),
        DataEntityType.FILES);

    ItemCollection<QueryOutcome> sharedFiles = query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec()
            .withKeyConditionExpression("#newPK = :newPK AND begins_with(#newSK, :newSK)")
            .withFilterExpression("contains(#name, :q)")
            .withValueMap(new ValueMap()
                .withString(":newSK", ObjectStatus.NORMAL.label)
                .withString(":newPK", sharedPrefix + stripPrefix(ownerId))
                .withString(":q", query.toLowerCase()))
            .withNameMap(new NameMap()
                .with("#name", "namelowercase")
                .with("#newSK", "newSK")
                .with("#newPK", "newPK")),
        DataEntityType.FILES);

    Iterable<Item> finalIterator = Iterables.concat(ownedFiles, sharedFiles);
    return finalIterator.iterator();
  }

  // all items in folder that are deleted. Should only be called on trash folder
  public static ItemCollection<QueryOutcome> getDeletedFolderItems(String folderId) {
    return query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withHashKey(Field.PARENT.getName(), folderId)
            .withFilterExpression("deleted = :deleted and attribute_not_exists(#erased)")
            .withValueMap(new ValueMap().withBoolean(":deleted", true))
            .withNameMap(new NameMap().with("#erased", "erased")),
        DataEntityType.FILES);
  }

  // all items in folder deleted/not deleted. Should only be called on trash folder
  // folderId begins with o:
  public static ItemCollection<QueryOutcome> getFolderWithDeletedItems(String folderId) {
    return query(
        tableName,
        parentStatusIndex,
        new QuerySpec()
            .withHashKey(Field.PARENT.getName(), folderId)
            .withFilterExpression("sk = :sk and attribute_not_exists(#erased)")
            .withValueMap(new ValueMap().withString(":sk", skInfoValue))
            .withNameMap(new NameMap().with("#erased", "erased")),
        DataEntityType.FILES);
  }

  // all files owned by user
  public static ItemCollection<QueryOutcome> getAllFilesOwnedByUser(String owner) {
    return query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec()
            .withHashKey("newPK", ownerPrefix + stripPrefix(owner))
            .withFilterExpression("begins_with(pk, :pk) AND attribute_not_exists(#erased)")
            .withNameMap(new NameMap().with("#erased", "erased"))
            .withValueMap(new ValueMap().withString(":pk", fileIdPrefix)),
        DataEntityType.FILES);
  }

  // all folders owned by user
  public static ItemCollection<QueryOutcome> getAllFoldersOwnedByUser(String owner) {
    return query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec()
            .withHashKey("newPK", ownerPrefix + stripPrefix(owner))
            .withFilterExpression("begins_with(pk, :pk) AND attribute_not_exists(#erased)")
            .withNameMap(new NameMap().with("#erased", "erased"))
            .withValueMap(new ValueMap().withString(":pk", folderIdPrefix)),
        DataEntityType.FILES);
  }

  // all items shared with user
  public static ItemCollection<QueryOutcome> getAllItemsSharedWithUser(String user) {
    return query(
        tableName,
        newPKnewSKIndex,
        new QuerySpec().withHashKey("newPK", sharedPrefix + stripPrefix(user)),
        DataEntityType.FILES);
  }

  // all shared items created along with original item
  public static Iterator<Item> getConcurrentSharedItems(Item item) {
    return query(
            tableName,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :pk " + "and" + " " + "begins_with" + "(sk, :sk)")
                .withFilterExpression("#cd < :cd")
                .withValueMap(new ValueMap()
                    .withString(":pk", item.getString(pk))
                    .withString(":sk", userPrefix)
                    .withLong(":cd", getCreationDate(item) + (5 * 1000)))
                .withNameMap(new NameMap().with("#cd", Field.CREATION_DATE.getName())),
            DataEntityType.FILES)
        .iterator();
  }

  // we want to know who owns the parent folder
  public static String getParentFolderOwner(Item obj) {
    String parent = obj.getString(Field.PARENT.getName());
    if (obj.hasAttribute("origparent")) {
      parent = obj.getString("origparent");
    }
    if (parent.startsWith(userPrefix)) {
      return stripPrefix(parent);
    }
    Item parentItem = getItem(obj.getString(Field.OWNER.getName()));
    return getOwner(parentItem);
  }

  /**
   * Iterate through top most parent and update modified time for each sub-parent
   *
   * @param item        - main item
   * @param userId      - current userId
   * @param currentTime - current time which is to be updated
   */
  public static void checkParentAndUpdateModifiedTime(Item item, String userId, long currentTime) {
    Item parentItem = item;
    while (Objects.nonNull(parentItem)
        && !getItemId(parentItem).equals(SimpleFluorine.ROOT_FOLDER_ID)) {
      updateModifiedTime(parentItem, currentTime);
      parentItem = getItem(makeFolderId(getParent(parentItem, userId), getOwner(parentItem)));
    }
  }

  /**
   * update modifier as deleted only for the current object
   *
   * @param item - main item
   */
  private static void updateModifierAsDeleted(Item item) {
    updateModifier(item, Field.DELETED.getName());
  }

  /**
   * Iterate through top most parent and update modifier for each sub-parent
   *
   * @param item   - main item
   * @param userId - current userId
   */
  public static void checkParentAndUpdateModifier(Item item, String userId) {
    Item parentItem = item;
    while (Objects.nonNull(parentItem)
        && !getItemId(parentItem).equals(SimpleFluorine.ROOT_FOLDER_ID)) {
      updateModifier(parentItem, userId);
      parentItem = getItem(makeFolderId(getParent(parentItem, userId), getOwner(parentItem)));
    }
  }

  public static void updateRegion(Item item, String s3Region, String s3version) {
    long currentTime = GMTHelper.utcCurrentTime();

    putItem(
        tableName,
        item.withLong(Field.CREATION_DATE.getName(), currentTime)
            .withLong(Field.LAST_MODIFIED.getName(), currentTime)
            .withString("s3version", s3version)
            .withString(Field.VERSION_ID.getName(), makeVersionId(Long.toString(currentTime)))
            .withString(Field.S3_REGION.getName(), s3Region),
        DataEntityType.FILES);
  }

  public static boolean isCollaboratorShareDowngrade(String targetUserId, Item object) {
    String ownerId = getOwner(object);
    if (ownerId.equals(targetUserId)) {
      return false;
    }

    for (String editorId : getEditors(object)) {
      if (editorId.equals(targetUserId)) {
        return true;
      }
    }
    return false;
  }

  public static String getExternalId(String userId) {
    return StorageType.getShort(StorageType.SAMPLES) + "_" + userId;
  }

  public static boolean hasAccessToCommentForKudoDrive(String fileId, String userId) {
    Item file = getItem(makeFileId(fileId));
    if (Objects.isNull(file)) {
      return false;
    }
    return hasRights(file, makeUserId(userId), false, AccessType.canComment);
  }

  public static boolean hasAccessToCreateSessionForKudoDrive(String fileId, String userId) {
    Item file = getItem(makeFileId(fileId));
    if (Objects.isNull(file)) {
      return false;
    }
    return hasRights(file, makeUserId(userId), false, AccessType.canOpen);
  }

  public enum ObjectStatus {
    NORMAL("normal#"),
    DELETED("deleted#");

    private final String label;

    ObjectStatus(String label) {
      this.label = label;
    }
  }
}
