package com.graebert.storage.xray;

import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.TraceID;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.logs.XRayLogger;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class XRayEntityUtils {
  // typed Cases
  public static void setEntityGroup(Entity entity, OperationGroup group) {
    try {
      putAnnotation(entity, XrayField.OPERATION_GROUP, group.getName());
    } catch (Exception exception) {
      new XRayLogger()
          .logError("XRayEntityUtils.setEntityGroup", "Cant set entity group", exception);
    }
  }

  public static void copyAnnotation(Entity to, Entity from, XrayField field) {
    if (Objects.nonNull(to)
        && Objects.nonNull(from)
        && from.getAnnotations().containsKey(field.getKey())) {
      putAnnotation(to, field, from.getAnnotations().get(field.getKey()));
    }
  }

  // these fields are indexed by aws to be searchable with filter expressions
  public static void putAnnotation(Entity entity, XrayField field, Object value) {
    if (XRayManager.isXrayEnabled() && Objects.nonNull(entity) && Objects.nonNull(value)) {
      try {
        if (value instanceof Boolean) {
          entity.putAnnotation(field.getKey(), (Boolean) value);
        } else if (value instanceof Number) {
          entity.putAnnotation(field.getKey(), (Number) value);
        } else {
          try {
            entity.putAnnotation(field.getKey(), (String) value);
          } catch (ClassCastException exception) {
            entity.putAnnotation(field.getKey(), value.toString());
          }
        }
      } catch (Exception exception) {
        new XRayLogger()
            .logError("XRayEntityUtils.putAnnotation", "Cant put annotation", exception);
      }
    }
  }

  // Something to be stored with segment, but not indexed
  public static void putMetadata(Entity entity, XrayField field, Object value) {
    if (XRayManager.isXrayEnabled() && Objects.nonNull(entity) && Objects.nonNull(value)) {
      try {
        entity.putMetadata(field.getKey(), value);
      } catch (Exception exception) {
        new XRayLogger().logError("XRayEntityUtils.putMetadata", "Cant put metadata", exception);
      }
    }
  }

  public static void putMetadata(
      Entity entity, XrayField namespace, XrayField field, Object value) {
    if (XRayManager.isXrayEnabled() && Objects.nonNull(entity) && Objects.nonNull(value)) {
      try {
        entity.putMetadata(namespace.getKey(), field.getKey(), value);
      } catch (Exception exception) {
        new XRayLogger().logError("XRayEntityUtils.putMetadata", "Cant put metadata", exception);
      }
    }
  }

  public static void putStorageMetadata(
      final Entity entity, final Message<JsonObject> vertxMessage, final StorageType storageType) {
    try {
      if (XRayManager.isXrayEnabled() && Objects.nonNull(entity)) {

        putAnnotation(entity, XrayField.STORAGE_TYPE, storageType.toString());

        for (Map.Entry<String, Object> entry : vertxMessage.body()) {
          entity.putMetadata(entry.getKey(), entry.getValue());
        }
      }
    } catch (Exception exception) {
      new XRayLogger()
          .logError("XRayEntityUtils.putStorageMetadata", "Cant put storage metadata", exception);
    }
  }

  public static String getTraceId(Entity entity) {
    return XRayManager.isXrayEnabled() && Objects.nonNull(entity)
        ? entity.getTraceId().toString()
        : null;
  }

  public static void addException(Entity entity, String message) {
    addException(entity, new Exception(message));
  }

  public static void addException(Entity entity, Throwable throwable) {
    if (XRayManager.isXrayEnabled() && Objects.nonNull(entity)) {
      putAnnotation(entity, XrayField.IS_SEGMENT_ERROR, true);
      putAnnotation(entity, XrayField.IS_OPERATIONAL_ERROR, true);
      entity.setError(true);

      if (Objects.nonNull(throwable)) {
        putMetadata(entity, XrayField.ERROR_MESSAGE, throwable.getMessage());
        entity.addException(throwable);
      }
    }
  }

  public static void addExceptionData(
      Entity entity, int statusCode, String errorId, String message) {
    addExceptionData(entity, statusCode, errorId, message, null);
  }

  public static void addExceptionData(
      Entity entity, int statusCode, String errorId, String message, Throwable throwable) {
    if (XRayManager.isXrayEnabled() && Objects.nonNull(entity)) {
      try {
        if (Objects.nonNull(throwable)) {
          entity.addException(throwable);
        }

        entity.setError(true);
        putAnnotation(entity, XrayField.STATUS_CODE, statusCode);
        putAnnotation(entity, XrayField.IS_SEGMENT_ERROR, true);

        if (Objects.nonNull(errorId)) {
          putMetadata(entity, XrayField.ERROR_ID, errorId);
        }
        if (Objects.nonNull(message)) {
          putMetadata(entity, XrayField.ERROR_MESSAGE, message);
        }
      } catch (Exception exception) {
        new XRayLogger()
            .logError("XRayEntityUtils.addExceptionData", "Cant put exception metadata", exception);
      }
    }
  }

  public static JsonObject convertEntityToJson(Entity entity) {
    if (entity == null) {
      return new JsonObject();
    }

    return new JsonObject(new HashMap<>() {
      {
        put(Field.ID.getName(), entity.getId());
        put(Field.TRACE_ID.getName(), entity.getTraceId());
        put(Field.PARENT_ID.getName(), entity.getParentId());
        put("http", entity.getHttp());
        put("annotations", entity.getAnnotations());
        put("metadata", entity.getMetadata());
        put("isError", entity.isError());
        put("isThrottle", entity.isThrottle());
        put("isFault", entity.isFault());
      }
    });
  }

  public static JsonObject getRequiredSegmentDataObject(Entity entity) {
    try {
      if (Objects.nonNull(entity)) {
        // traceId can come from parent
        TraceID traceID = entity.getTraceId();
        if (traceID == null && entity.getParent() != null) {
          traceID = entity.getParent().getTraceId();
        }
        JsonObject response = new JsonObject();
        if (traceID != null) {
          response.put(Field.TRACE_ID.getName(), traceID.toString());
        }
        if (entity.getId() != null) {
          response.put(Field.SEGMENT_PARENT_ID.getName(), entity.getId());
        }
        return response;
      }
    } catch (Exception exception) {
      new XRayLogger()
          .logError(
              "putRequiredSegmentDataToJson", "cant put required segment data to json", exception);
    }
    return new JsonObject();
  }

  /**
   * Generates name for blocking code segments
   *
   * @param parentEntity is a parent entity of wrapped code
   * @return name of blocking segment
   */
  public static String makeBlockingName(Entity parentEntity) {
    return Objects.nonNull(parentEntity)
        ? parentEntity.getName().concat(".blocking")
        : Dataplane.emptyString;
  }
}
