package com.graebert.storage.resources.dao;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.resources.ObjectFilter;
import com.graebert.storage.resources.ResourceType;
import io.vertx.core.json.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public interface ResourceDAO {
  Iterator<Item> getObjectsByFolderId(
      String parent,
      String owner,
      String ownerId,
      ObjectFilter objectFilter,
      String userId,
      ResourceType resourceType);

  Iterator<Item> getFolderResourcesByName(
      String ownerId,
      String owner,
      String name,
      String userId,
      String parent,
      ObjectType objectType,
      ResourceType resourceType);

  Item getObjectById(String objectId, ResourceType resourceType, String owner, String userId);

  void createObject(
      String objectId,
      String owner,
      String ownerId,
      String userId,
      ResourceType resourceType,
      String parent,
      JsonObject objectData,
      List<Map<String, Object>> fontInfo);

  Item updateObject(
      String pkValue,
      String skValue,
      JsonObject dataToUpdate,
      ResourceType resourceType,
      List<Map<String, Object>> fontInfo);

  void deleteObject(String pkValue, String skValue);
}
