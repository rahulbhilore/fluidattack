package com.graebert.storage.Entities;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.integration.objects.ObjectPermissions;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PermissionsDao extends Dataplane {

  private static final String tableName = TableName.TEMP.name;
  private static final String permissionPrefix = "permission#";

  public static void addOrUpdateObjectPermissionsForUser(
      String userId, String itemId, ObjectType type, Role role, List<?> customPermissions) {
    Item objectPermission = getObjectPermissionsForUser(userId, itemId);
    if (Objects.isNull(objectPermission)) {
      objectPermission = new Item()
          .withString(pk, permissionPrefix + itemId)
          .withString(sk, userId)
          .withString(Field.TYPE.getName(), type.name())
          .withString(Field.ROLE.getName(), role.name());
    }
    objectPermission.withList(Field.CUSTOM_PERMISSIONS.getName(), customPermissions);
    putItem(tableName, objectPermission, DataEntityType.PERMISSIONS);
  }

  private static Item getObjectPermissionsForUser(String userId, String itemId) {
    return getItem(
        tableName, pk, permissionPrefix + itemId, sk, userId, true, DataEntityType.PERMISSIONS);
  }

  public static List<String> getCustomPermissions(String userId, String itemId) {
    Item objectPermission = PermissionsDao.getObjectPermissionsForUser(userId, itemId);
    if (Objects.nonNull(objectPermission)
        && objectPermission.hasAttribute(Field.CUSTOM_PERMISSIONS.getName())) {
      return objectPermission.getList(Field.CUSTOM_PERMISSIONS.getName());
    }
    return null;
  }

  public static void deleteCustomPermissions(String userId, String itemId) {
    deleteItem(tableName, pk, permissionPrefix + itemId, sk, userId, DataEntityType.PERMISSIONS);
  }

  public static void setObjectPermissions(
      String userId, String itemId, Boolean isOwner, boolean isFile, JsonObject obj) {
    ObjectPermissions permissions;
    Item objectPermission = getObjectPermissionsForUser(userId, itemId);
    if (Objects.nonNull(objectPermission)
        && objectPermission.hasAttribute(Field.CUSTOM_PERMISSIONS.getName())) {
      permissions = new ObjectPermissions();
      List<String> customPermissions = objectPermission.getList(Field.CUSTOM_PERMISSIONS.getName());
      List<AccessType> accessTypes =
          customPermissions.stream().map(AccessType::valueOf).collect(Collectors.toList());
      if (isFile && !isViewOnly(obj) && !accessTypes.contains(AccessType.canEdit)) {
        obj.put(Field.VIEW_ONLY.getName(), true);
      }
      obj.put(
          Field.PERMISSIONS.getName(), permissions.setBatchTo(accessTypes, true).toJson());
    } else {
      permissions = new ObjectPermissions(isOwner, isViewOnly(obj));
      obj.put(
          Field.PERMISSIONS.getName(), permissions.getObjectPermissions(isFile).toJson());
    }
  }

  private static boolean isViewOnly(JsonObject object) {
    return (object.getBoolean(Field.VIEW_ONLY.getName(), true));
  }
}
