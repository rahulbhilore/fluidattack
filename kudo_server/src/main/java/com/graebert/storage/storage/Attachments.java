package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;

public class Attachments extends Dataplane {
  public static final String tableName = TableName.COMMENTS.name;
  public static final String attachmentsPrefix = "attachments" + hashSeparator;

  public static Item getAttachment(
      final String fileId, final StorageType storageType, final String attachmentId) {
    Item attachment = getItemFromDB(
        tableName,
        Field.PK.getName(),
        attachmentsPrefix + fileId,
        Field.SK.getName(),
        attachmentId,
        DataEntityType.COMMENTS);
    if (attachment == null) {
      // let's retry to find with storageType
      attachment = getItemFromDB(
          tableName,
          Field.PK.getName(),
          attachmentsPrefix + StorageType.getShort(storageType) + "_" + fileId,
          Field.SK.getName(),
          attachmentId,
          DataEntityType.COMMENTS);
      if (attachment != null) {
        // let's set proper format
        attachment.withString(Field.PK.getName(), attachmentsPrefix + fileId);
        putItem(
            tableName,
            attachment.getString(Field.PK.getName()),
            attachment.getString(Field.SK.getName()),
            attachment,
            DataEntityType.COMMENTS);
      }
    }
    return attachment;
  }

  public static void updateAttachmentTimestamp(final Item attachment) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(
                Field.PK.getName(),
                attachment.getString(Field.PK.getName()),
                Field.SK.getName(),
                attachment.getString(Field.SK.getName()))
            .withUpdateExpression("SET tmstmp = :tmstmp")
            .withValueMap(new ValueMap().withLong(":tmstmp", GMTHelper.utcCurrentTime())),
        DataEntityType.COMMENTS);
  }

  public static void updateAttachmentTags(final Item attachment, JsonArray tags) {
    updateItem(
        tableName,
        new UpdateItemSpec()
            .withPrimaryKey(
                Field.PK.getName(),
                attachment.getString(Field.PK.getName()),
                Field.SK.getName(),
                attachment.getString(Field.SK.getName()))
            .withUpdateExpression("SET #tags = :tags")
            .withValueMap(new ValueMap().withJSON(":tags", tags.encode()))
            .withNameMap(new NameMap().with("#tags", "tags")),
        DataEntityType.COMMENTS);
  }

  public static ItemCollection<QueryOutcome> getAttachments(
      final String fileId, final StorageType storageType, final String userId) {
    QuerySpec qs = new QuerySpec().withHashKey(Field.PK.getName(), attachmentsPrefix + fileId);

    if (Utils.isStringNotNullOrEmpty(userId)) {
      ValueMap valueMap = new ValueMap().withString(":author", userId);
      qs.withFilterExpression("author = :author").withValueMap(valueMap);
    }

    ItemCollection<QueryOutcome> attachmentIterator =
        query(tableName, null, qs, DataEntityType.COMMENTS);
    if (!attachmentIterator.iterator().hasNext()) {
      // cannot reuse QS
      QuerySpec qs2 = new QuerySpec();
      qs2.withHashKey(
          Field.PK.getName(), attachmentsPrefix + StorageType.getShort(storageType) + "_" + fileId);
      if (qs.getFilterExpression() != null) {
        qs2.withFilterExpression(qs.getFilterExpression());
        qs2.withValueMap(qs.getValueMap());
      }
      attachmentIterator = query(tableName, null, qs2, DataEntityType.COMMENTS);
    }
    return attachmentIterator;
  }

  public static void putAttachment(final Item attachment) {
    putItem(
        tableName,
        attachment.getString(Field.PK.getName()),
        attachment.getString(Field.SK.getName()),
        attachment,
        DataEntityType.COMMENTS);
  }
}
