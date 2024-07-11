package com.graebert.storage.thumbnails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;

public class ThumbnailSubscription extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final String pkPrefix = "thumbnail_waiting#";

  public static boolean save(final String fileId, final String userId) {
    if (Utils.isStringNotNullOrEmpty(fileId) && Utils.isStringNotNullOrEmpty(userId)) {
      putItem(
          tableName,
          pkPrefix + fileId,
          userId,
          new Item()
              .withPrimaryKey(pk, pkPrefix + fileId, sk, userId)
              .withLong(Dataplane.ttl, TtlUtils.inOneDay()),
          DataEntityType.THUMBNAILS);
      return true;
    }
    return false;
  }

  public static ItemCollection<QueryOutcome> findSubscriptionsForFile(final String fileId) {
    return query(
        tableName,
        null,
        new QuerySpec()
            .withKeyConditionExpression("pk = :pk")
            .withValueMap(new ValueMap().withString(":pk", pkPrefix + fileId)),
        DataEntityType.THUMBNAILS);
  }
}
