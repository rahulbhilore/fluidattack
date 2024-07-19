package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.stats.logs.data.DataEntityType;

public class TemporaryData extends Dataplane {
  public static void putTemporaryGoogleData(String id, Item data) {
    putItem(
        TableName.TEMP.name,
        data.withPrimaryKey(pk, "google" + id, sk, id),
        DataEntityType.TEMP_DATA);
  }

  public static Item getTemporaryGoogleData(String id) {
    return getItemFromDB(TableName.TEMP.name, pk, "google" + id, sk, id, DataEntityType.TEMP_DATA);
  }

  public static void deleteTemporaryGoogleData(String id) {
    deleteItem(TableName.TEMP.name, pk, "google" + id, sk, id, DataEntityType.TEMP_DATA);
  }

  /* this is not done yet
  public static String getFolderCache(String userId, String encapsulatedId){
      Item result = getItemFromDB("temp",Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId);
      if (result == null)
          return null;
      return result.getString("subitems");
  }

  public static String getItemInfoCache(String userId, String encapsulatedId){
      Item result = getItemFromDB("temp",Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId);
      if (result == null)
          return null;
      return result.getString(Field.INFO.getName());
  }

  public static String getTrashCache(String userId, String encapsulatedId){
      Item result = getItemFromDB("temp",Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId);
      if (result == null)
          return null;
      return result.getString(Field.TRASH.getName());
  }

  public static String getFolderPathCache(String userId, String encapsulatedId){
      Item result = getItemFromDB("temp",Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId);
      if (result == null)
          return null;
      return result.getString(Field.FOLDER.getName());
  }

  public static void putFolderCache(String userId, String encapsulatedId, String data){
      if (data.length() < 300 * 1024) {
          getTableForWrite("temp").updateItem( new UpdateItemSpec().withPrimaryKey(Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId)
                  .withUpdateExpression("SET #ttl = :ttl, #field = :field")
                  .withValueMap(new ValueMap().withLong(":ttl", Utils.TTLOneDay())
                          .withString(":field", data))
                  .withNameMap(new NameMap().with("#field", "subitems").with("#ttl", Field.TTL.getName()))
          );
      }
      else{
          log.warn("Not caching result as it too large!");
      }
  }
  public static void putItemInfoCache(String userId, String encapsulatedId, String data){
      if (data.length() < 300 * 1024) {
          getTableForWrite("temp").updateItem( new UpdateItemSpec().withPrimaryKey(Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId)
                  .withUpdateExpression("SET #ttl = :ttl, #field = :field")
                  .withValueMap(new ValueMap().withLong(":ttl", Utils.TTLOneDay())
                          .withString(":field", data))
                  .withNameMap(new NameMap().with("#field", Field.INFO.getName()).with("#ttl", Field.TTL.getName()))
          );
      }
      else{
          log.warn("Not caching result as it too large!");
      }
  }

  public static void putTrashedCache(String userId, String encapsulatedId, String data){
      if (data.length() < 300 * 1024) {
          getTableForWrite("temp").updateItem( new UpdateItemSpec().withPrimaryKey(Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId)
                  .withUpdateExpression("SET #ttl = :ttl, #field = :field")
                  .withValueMap(new ValueMap().withLong(":ttl", Utils.TTLOneDay())
                          .withString(":field", data))
                  .withNameMap(new NameMap().with("#field", "trash").with("#ttl", Field.TTL.getName()))
          );
      }
      else{
          log.warn("Not caching result as it too large!");
      }
  }

  public static void putFolderPathCache(String userId, String encapsulatedId, String data){
      if (data.length() < 300 * 1024) {
          getTableForWrite("temp").updateItem( new UpdateItemSpec().withPrimaryKey(Field.PK.getName(), "cache#" + userId, Field.SK.getName(), encapsulatedId)
                  .withUpdateExpression("SET #ttl = :ttl, #field = :field")
                  .withValueMap(new ValueMap().withLong(":ttl", Utils.TTLOneDay())
                          .withString(":field", data))
                  .withNameMap(new NameMap().with("#field", Field.FOLDER.getName()).with("#ttl", Field.TTL.getName()))
          );
      }
      else{
          log.warn("Not caching result as it too large!");
      }
  }
   */
}
