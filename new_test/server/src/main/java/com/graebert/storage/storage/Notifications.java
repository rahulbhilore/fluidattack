package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.graebert.storage.stats.logs.data.DataEntityType;
import java.util.Iterator;

public class Notifications extends Dataplane {
  public static final String tableName = TableName.TEMP.name;

  public static void putNotification(Item notification) {
    putItem(tableName, notification, DataEntityType.NOTIFICATIONS);
  }

  public static Iterator<Item> getNotifications(QuerySpec querySpec) {
    return query(tableName, null, querySpec, DataEntityType.NOTIFICATIONS).iterator();
  }
}
