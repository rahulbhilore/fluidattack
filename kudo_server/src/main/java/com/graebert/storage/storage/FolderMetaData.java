package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;

public class FolderMetaData extends Dataplane {
  // this holds meta data stored for a folder

  public static void putMetaData(
      final String id, final String storageType, final String userId, final String metadata) {
    Item item =
        getItemFromCache("foldermetadata" + id, storageType, DataEntityType.FOLDER_METADATA);
    if (item == null) {
      item = new Item()
          .withPrimaryKey(pk, "foldermetadata" + id, sk, storageType)
          .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime())
          .withString(Field.CREATED_BY.getName(), userId);
    }
    item.withString("metadata", metadata)
        .withLong("modifiedDate", GMTHelper.utcCurrentTime())
        .withString(Field.MODIFIED_BY.getName(), userId);
    putItem(item, DataEntityType.FOLDER_METADATA);
  }

  public static Item getMetaData(String fileId, String storage) {
    return getItemFromCache("foldermetadata" + fileId, storage, DataEntityType.FOLDER_METADATA);
  }

  public static void eraseMetaData(String folderId, String storage) {
    deleteItem(
        TableName.MAIN.name,
        pk,
        "foldermetadata" + folderId,
        sk,
        storage,
        DataEntityType.FOLDER_METADATA);
  }
}
