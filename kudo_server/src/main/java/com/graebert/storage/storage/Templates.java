package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Templates extends Dataplane {

  private static final String tableName = TableName.MAIN.name;
  private static final String templatePrefix = "TEMPLATES#";
  private static final String templatesField = "templates";

  public static String getPkByTemplateType(String id, TemplateType templateType) {
    switch (templateType) {
      case ORG:
        return templatePrefix + TemplateType.ORG.label + id;
      case GROUP:
        return templatePrefix + TemplateType.GROUP.label + id;
      case USER:
        return templatePrefix + TemplateType.USER.label + id;
      case PUBLIC:
      default:
        return templatePrefix + TemplateType.PUBLIC.label;
    }
  }

  public static Map<String, Object> getTemplate(String templateId, String userId) {
    List<Map<String, Object>> templates = getAllUserTemplates(userId);
    return templates.stream()
        .filter(template -> template.get("templateId").equals(templateId))
        .findFirst()
        .orElse(null);
  }

  public static String getSkByTemplateType(String id, TemplateType templateType) {
    if (templateType.equals(TemplateType.PUBLIC)) {
      return templateType.name();
    }
    return id;
  }

  public static List<Map<String, Object>> getTemplatesByType(String id, TemplateType templateType) {
    List<Map<String, Object>> templates = new ArrayList<>();
    Item item = getTemplate(id, templateType);
    if (item != null) {
      templates = item.getList(templatesField);
    }
    return templates;
  }

  public static List<Map<String, Object>> getAllUserTemplates(JsonObject jsonObject) {
    String userId = jsonObject.getString(Field.USER_ID.getName());
    return getAllUserTemplates(userId);
  }

  public static List<Map<String, Object>> getAllUserTemplates(String userId) {
    //        For now we don't support  organizational and group templates - therefore commented it
    // out
    List<Map<String, Object>> templates = new ArrayList<>();

    if (Utils.isStringNotNullOrEmpty(userId)) {
      templates.addAll(getTemplatesByType(userId, TemplateType.USER));
    }
    templates.addAll(getTemplatesByType("", TemplateType.PUBLIC));

    return templates;
  }

  private static Item getTemplate(String id, TemplateType templateType) {
    if (!Utils.isStringNotNullOrEmpty(id) && !templateType.equals(TemplateType.PUBLIC)) {
      return null;
    }
    String pkValue = getPkByTemplateType(id, templateType);
    String skValue = getSkByTemplateType(id, templateType);
    return getItem(tableName, pk, pkValue, sk, skValue, true, DataEntityType.RESOURCES);
  }

  public static boolean deleteTemplate(String templateId, String id, TemplateType templateType) {
    Item item = getTemplate(id, templateType);
    if (item != null) {
      List<Map<String, Object>> templates = item.getList(templatesField);
      if (templates.removeIf(temp -> (temp.get("templateId").equals(templateId)))) {
        item.withList(templatesField, templates);
        putItem(item, DataEntityType.RESOURCES);
        return true;
      }
    }
    return false;
  }

  public static void saveTemplate(
      String id, String templateId, String name, TemplateType templateType, int length) {
    List<Map<String, Object>> templates = new ArrayList<>();
    String pkValue = getPkByTemplateType(id, templateType);
    String skValue = getSkByTemplateType(id, templateType);
    Item item = getItem(tableName, pk, pkValue, sk, skValue, true, DataEntityType.RESOURCES);
    Map<String, Object> template = new HashMap<>();
    template.put("templateId", templateId);
    template.put(Field.F_NAME.getName(), name);
    template.put("flength", length);

    if (item != null) {
      templates = item.getList(templatesField);
    } else {
      item = new Item().withPrimaryKey(pk, pkValue, sk, skValue);
    }

    templates.add(0, template);
    item.withList(templatesField, templates);
    putItem(item, DataEntityType.RESOURCES);
  }

  public static boolean updateTemplate(
      String templateId, String id, TemplateType templateType, String name) {
    Item item = getTemplate(id, templateType);
    if (item != null) {
      List<Map<String, Object>> templates = item.getList(templatesField);
      templates.stream()
          .filter(temp -> temp.get("templateId").equals(templateId))
          .forEach(temp -> temp.put(Field.F_NAME.getName(), name));

      item.withList(templatesField, templates);
      putItem(item, DataEntityType.RESOURCES);
      return true;
    }
    return false;
  }

  public static void deleteTemplatesBulk(
      String id, List<String> ids, TemplateType templateType, S3Regional s3Regional) {
    String pkValue = getPkByTemplateType(id, templateType);
    String skValue = getSkByTemplateType(id, templateType);
    Item item = getItem(tableName, pk, pkValue, sk, skValue, true, DataEntityType.RESOURCES);
    if (item != null) {
      List<Map<String, Object>> templates = item.getList(templatesField);
      boolean wasUpdated =
          templates.removeIf(template -> ids.contains((String) template.get("templateId")));
      if (wasUpdated) {
        item.withList(templatesField, templates);
        putItem(item, DataEntityType.RESOURCES);
        s3Regional.deleteMultiObjectsFromBucket(ids.toArray(new String[0]));
      }
    }
  }

  public static void deleteTemplatesByType(
      String id, TemplateType templateType, S3Regional s3Regional) {
    String pkValue = getPkByTemplateType(id, templateType);
    String skValue = getSkByTemplateType(id, templateType);
    Item item = getItem(tableName, pk, pkValue, sk, skValue, true, DataEntityType.RESOURCES);
    if (item != null) {
      List<Map<String, Object>> templates = item.getList(templatesField);
      List<String> templateIds = new ArrayList<>();
      for (Map<String, Object> tmpl : templates) {
        templateIds.add((String) tmpl.get("templateId"));
      }
      s3Regional.deleteMultiObjectsFromBucket(templateIds.toArray(new String[0]));
    }
    deleteItem(tableName, pk, pkValue, sk, skValue, DataEntityType.RESOURCES);
  }

  public enum TemplateType {
    ORG("ORG#"),
    GROUP("GROUP#"),
    USER("USER#"),
    PUBLIC("PUBLIC");

    public final String label;

    TemplateType(String label) {
      this.label = label;
    }

    public static TemplateType getType(String value) {
      if (!Utils.isStringNotNullOrEmpty(value)) {
        return PUBLIC;
      }
      final String formattedValue = value.trim();
      for (TemplateType t : TemplateType.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
      return PUBLIC;
    }
  }
}
