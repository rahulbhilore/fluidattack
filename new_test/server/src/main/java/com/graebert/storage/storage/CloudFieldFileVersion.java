package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import java.util.Objects;

public class CloudFieldFileVersion extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final String pkPrefix = "CLOUDFIELD_FILE_VERSION".concat(hashSeparator);

  public static void markFileVersionAsPrinted(
      String fileId, String storageType, String updatedBy, String printedVersionId) {
    Item item = getCloudFieldFileVersion(fileId, storageType);
    if (Objects.nonNull(item)) {
      String updateExpression = "SET #pvId = :pvId, #uBy = :uBy, #uDate = :uDate";
      NameMap nameMap = new NameMap()
          .with("#pvId", Field.PRINTED_VERSION_ID.getName())
          .with("#uBy", "updatedBy")
          .with("#uDate", Field.UPDATED.getName());
      ValueMap valueMap = new ValueMap()
          .withString(":pvId", printedVersionId)
          .withString(":uBy", updatedBy)
          .withLong(":uDate", GMTHelper.utcCurrentTime());
      updateItem(
          tableName,
          new UpdateItemSpec()
              .withPrimaryKey(pk, item.getString(pk), sk, item.getString(sk))
              .withUpdateExpression(updateExpression)
              .withNameMap(nameMap)
              .withValueMap(valueMap),
          DataEntityType.CLOUDFIELD);
    } else {
      item = new Item()
          .withPrimaryKey(pk, pkPrefix + fileId, sk, storageType)
          .withString(Field.FILE_ID.getName(), fileId)
          .withString("updatedBy", updatedBy)
          .withLong(Field.UPDATED.getName(), GMTHelper.utcCurrentTime())
          .withString(Field.PRINTED_VERSION_ID.getName(), printedVersionId);
      putItem(tableName, item, DataEntityType.CLOUDFIELD);
    }
  }

  public static Item getCloudFieldFileVersion(String fileId, String storageType) {
    return getItemFromCache(
        tableName, pk, pkPrefix + fileId, sk, storageType, DataEntityType.CLOUDFIELD);
  }
}
