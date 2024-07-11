package com.graebert.storage.xray;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

public class XRayDataHandler implements Handler<RoutingContext> {
  private final String operationId;

  public XRayDataHandler(String operationId) {
    this.operationId = operationId;
  }

  private void putObjectId(Entity segment, RoutingContext rc, XrayField field) {
    String value = null;

    if (rc.pathParams().containsKey(field.getKey())
        && Utils.isStringNotNullOrEmpty(rc.pathParam(field.getKey()))) {
      value = rc.pathParam(field.getKey());
    } else if (Utils.isStringNotNullOrEmpty(rc.request().getHeader(field.getKey()))) {
      value = rc.request().getHeader(field.getKey());
    }

    if (Utils.isStringNotNullOrEmpty(value)) {
      JsonObject fields = Utils.parseObjectId(value);
      XRayEntityUtils.putAnnotation(segment, field, fields.getString(Field.OBJECT_ID.getName()));
      XRayEntityUtils.putAnnotation(
          segment, XrayField.STORAGE_TYPE, fields.getString(Field.STORAGE_TYPE.getName()));
      XRayEntityUtils.putAnnotation(
          segment, XrayField.EXTERNAL_ID, fields.getString(Field.EXTERNAL_ID.getName()));
    }
  }

  @Override
  public void handle(RoutingContext event) {
    XRayManager.getCurrentSegment().ifPresent(segment -> {
      //  event.pathParams()
      //    .forEach((key, value) -> XRayEntityUtils.putAnnotation(segment, key, value));

      putObjectId(segment, event, XrayField.FILE_ID);
      putObjectId(segment, event, XrayField.FOLDER_ID);
      putObjectId(segment, event, XrayField.ID);
      putObjectId(segment, event, XrayField.OBJECT_ID);

      XRayEntityUtils.putAnnotation(segment, XrayField.OPERATION_ID, operationId);
    });

    event.next();
  }
}
