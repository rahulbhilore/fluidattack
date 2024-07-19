package com.graebert.storage.tmpl;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.storage.Templates;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kong.unirest.HttpStatus;
import org.jetbrains.annotations.NonNls;

public class TmplVerticle extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.TEMPLATES;

  @NonNls
  public static final String address = "tmpl";

  private S3Regional s3Regional = null;

  public TmplVerticle() {}

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".create", this::doCreate);
    eb.consumer(address + ".update", this::doUpdate);
    eb.consumer(address + ".delete", this::doDelete);
    eb.consumer(address + ".deleteByType", this::doDeleteByType);
    eb.consumer(address + ".getTmpls", this::doGetTemplates);
    eb.consumer(address + ".getAllUserTmpls", this::doGetAllUserTemplates);
    eb.consumer(address + ".get", this::doGet);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
  }

  private String getIdFromSessionInfo(JsonObject sessionInfo, Templates.TemplateType templateType) {
    if (sessionInfo == null) {
      return null;
    }
    switch (templateType) {
      case PUBLIC:
      default: {
        return null;
      }
      case USER: {
        return Utils.getPropertyIfPresent(sessionInfo, Field.USER_ID.getName(), null);
      }
      case ORG: {
        return Utils.getPropertyIfPresent(sessionInfo, Field.ORGANIZATION_ID.getName(), null);
      }
      case GROUP: {
        return Utils.getPropertyIfPresent(sessionInfo, "groupId", null);
      }
    }
  }

  private void doGet(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String id = message.body().getString(Field.ID.getName());
    try {
      byte[] content = s3Regional.get(id);
      message.reply(content);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "NoDataForTemplateWithId"), id),
          HttpStatus.BAD_REQUEST);
    }
    XRayManager.endSegment(segment);
    recordExecutionTime("getTemplate", System.currentTimeMillis() - mills);
  }

  private void doGetTemplates(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String id = jsonObject.getString(Field.ID.getName());
    Templates.TemplateType templateType =
        Templates.TemplateType.getType(jsonObject.getString(Field.TEMPLATE_TYPE.getName()));
    if (!Utils.isStringNotNullOrEmpty(id)) {
      id = this.getIdFromSessionInfo(
          jsonObject.getJsonObject(Field.SESSION_INFO.getName()), templateType);
    }

    JsonArray results = new JsonArray();
    List<Map<String, Object>> templates = Templates.getTemplatesByType(id, templateType);
    for (Map<String, Object> tmpl : templates) {
      results.add(new JsonObject()
          .put(Field.ENCAPSULATED_ID.getName(), tmpl.get(Field.TEMPLATE_ID.getName()))
          .put(Field.NAME.getName(), tmpl.get(Field.F_NAME.getName())));
    }

    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    recordExecutionTime("getTemplates", System.currentTimeMillis() - mills);
  }

  private void doGetAllUserTemplates(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.USER_ID.getName());
    String orgId = message.body().getString("orgId");
    String groupId = message.body().getString("groupId");

    JsonArray results = new JsonArray();
    List<Map<String, Object>> templates = Templates.getAllUserTemplates(new JsonObject()
        .put(Field.USER_ID.getName(), userId)
        .put("orgId", orgId)
        .put("groupId", groupId));
    for (Map<String, Object> tmpl : templates) {
      results.add(new JsonObject()
          .put(Field.ENCAPSULATED_ID.getName(), tmpl.get(Field.TEMPLATE_ID.getName()))
          .put(Field.NAME.getName(), tmpl.get(Field.F_NAME.getName())));
    }

    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    recordExecutionTime("getTemplates", System.currentTimeMillis() - mills);
  }

  private void doDelete(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String templateId = jsonObject.getString(Field.TEMPLATE_ID.getName());
    Templates.TemplateType templateType =
        Templates.TemplateType.getType(jsonObject.getString(Field.TEMPLATE_TYPE.getName()));
    String id = jsonObject.getString(Field.ID.getName());
    if (!Utils.isStringNotNullOrEmpty(id)) {
      id = this.getIdFromSessionInfo(
          jsonObject.getJsonObject(Field.SESSION_INFO.getName()), templateType);
    }
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    if (!isAdmin && templateType.equals(Templates.TemplateType.PUBLIC)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThisIsAnAdminTool"),
          HttpStatus.FORBIDDEN);
      return;
    }
    try {
      if (!Templates.deleteTemplate(templateId, id, templateType)) {
        sendError(
            segment,
            message,
            MessageFormat.format(Utils.getLocalizedString(message, "NoTemplateWithId"), templateId),
            HttpStatus.BAD_REQUEST);
        return;
      }
      s3Regional.delete(templateId);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
      return;
    }
    sendOK(segment, message, mills);
    recordExecutionTime("deleteTemplate", System.currentTimeMillis() - mills);
  }

  private void doDeleteByType(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    Templates.TemplateType templateType =
        Templates.TemplateType.getType(jsonObject.getString(Field.TEMPLATE_TYPE.getName()));
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    if (!isAdmin && templateType.equals(Templates.TemplateType.PUBLIC)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThisIsAnAdminTool"),
          HttpStatus.FORBIDDEN);
      return;
    }

    // represents "entity" id - user/org/etc.
    String id = jsonObject.getString(Field.ID.getName());
    if (!Utils.isStringNotNullOrEmpty(id)) {
      id = this.getIdFromSessionInfo(
          jsonObject.getJsonObject(Field.SESSION_INFO.getName()), templateType);
    }
    String ids = jsonObject.getString(Field.IDS.getName());
    try {
      if (Utils.isStringNotNullOrEmpty(ids)) {
        Templates.deleteTemplatesBulk(id, Arrays.asList(ids.split(",")), templateType, s3Regional);
      } else {
        Templates.deleteTemplatesByType(id, templateType, s3Regional);
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
      return;
    }
    sendOK(segment, message, mills);
    recordExecutionTime("deleteTemplate", System.currentTimeMillis() - mills);
  }

  private void doUpdate(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    Templates.TemplateType templateType =
        Templates.TemplateType.getType(jsonObject.getString(Field.TEMPLATE_TYPE.getName()));
    String id = jsonObject.getString(Field.ID.getName());
    if (!Utils.isStringNotNullOrEmpty(id)) {
      id = this.getIdFromSessionInfo(
          jsonObject.getJsonObject(Field.SESSION_INFO.getName()), templateType);
    }
    String templateId = jsonObject.getString(Field.TEMPLATE_ID.getName());
    String name = jsonObject.getString(Field.NAME.getName());
    boolean isAdmin = jsonObject.getBoolean(Field.IS_ADMIN.getName());
    if (!isAdmin && templateType.equals(Templates.TemplateType.PUBLIC)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThisIsAnAdminTool"),
          HttpStatus.FORBIDDEN);
      return;
    }
    if (Utils.isStringNotNullOrEmpty(name)) {
      try {
        if (Utils.isObjectNameLong(name)) {
          throw new KudoFileException(
              Utils.getLocalizedString(message, "TooLongObjectName"),
              KudoErrorCodes.LONG_OBJECT_NAME,
              kong.unirest.HttpStatus.BAD_REQUEST,
              "TooLongObjectName");
        }
        if (!Templates.updateTemplate(templateId, id, templateType, name)) {
          sendError(
              segment,
              message,
              MessageFormat.format(Utils.getLocalizedString(message, "FL8"), templateId),
              HttpStatus.BAD_REQUEST);
          return;
        }
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }
      sendOK(segment, message, mills);
    } else {
      sendError(segment, message, Utils.getLocalizedString(message, "FL8"), HttpStatus.BAD_REQUEST);
    }
    recordExecutionTime("updateTemplate", System.currentTimeMillis() - mills);
  }

  private void doCreate(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    Templates.TemplateType templateType = Templates.TemplateType.getType(getRequiredString(
        segment, Field.TEMPLATE_TYPE.getName(), message, parsedMessage.getJsonObject()));
    String id = (templateType.equals(Templates.TemplateType.PUBLIC))
        ? emptyString
        : parsedMessage.getJsonObject().getString(Field.ID.getName());
    if (!Utils.isStringNotNullOrEmpty(id)) {
      id = this.getIdFromSessionInfo(
          parsedMessage.getJsonObject().getJsonObject(Field.SESSION_INFO.getName()), templateType);
    }
    String templateId = getRequiredString(
        segment, Field.TEMPLATE_ID.getName(), message, parsedMessage.getJsonObject());
    String name =
        getRequiredString(segment, Field.NAME.getName(), message, parsedMessage.getJsonObject());
    boolean isAdmin = getRequiredBoolean(
        segment, Field.IS_ADMIN.getName(), message, parsedMessage.getJsonObject());
    if (!isAdmin && templateType.equals(Templates.TemplateType.PUBLIC)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThisIsAnAdminTool"),
          HttpStatus.FORBIDDEN);
      return;
    }
    if (Utils.isObjectNameLong(name)) {
      try {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "TooLongObjectName"),
            KudoErrorCodes.LONG_OBJECT_NAME,
            kong.unirest.HttpStatus.BAD_REQUEST,
            "TooLongObjectName");
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      }
    }
    try {
      s3Regional.put(templateId, parsedMessage.getContentAsByteArray());
      Templates.saveTemplate(
          id, templateId, name, templateType, parsedMessage.getContentAsByteArray().length);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
      return;
    }
    sendOK(
        segment, message, new JsonObject().put(Field.ENCAPSULATED_ID.getName(), templateId), mills);
    recordExecutionTime("uploadTemplate", System.currentTimeMillis() - mills);
  }
}
