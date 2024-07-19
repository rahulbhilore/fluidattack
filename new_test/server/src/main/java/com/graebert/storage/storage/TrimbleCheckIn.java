package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.stats.logs.data.DataEntityType;

public class TrimbleCheckIn extends Dataplane {

  private static final String tableName = TableName.TEMP.name;
  private static final String checkInField = "CheckIn_Request#";

  public static Item getTrimbleRequestToken(final String requestToken) {
    return getItem(
        tableName, pk, checkInField + requestToken, sk, requestToken, true, DataEntityType.FILES);
  }

  public static void deleteTrimbleRequestToken(final String requestToken) {
    deleteItem(tableName, pk, checkInField + requestToken, sk, requestToken, DataEntityType.FILES);
  }
}
