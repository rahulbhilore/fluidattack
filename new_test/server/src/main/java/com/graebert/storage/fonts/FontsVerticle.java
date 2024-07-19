package com.graebert.storage.fonts;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.Entities.Font;
import com.graebert.storage.Entities.FontException;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import kong.unirest.HttpStatus;

public class FontsVerticle extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.FONTS;
  public static String address = "fonts";

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getFonts", this::doGetFonts);
    eb.consumer(address + ".getFontById", this::doGetFontById);
    eb.consumer(address + ".uploadFont", this::doUploadFont);
    eb.consumer(address + ".deleteFont", this::doDeleteFont);

    Font.init(config);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-fonts");
  }

  private JsonObject parseCompany(JsonObject jsonObject) {
    if (jsonObject.getJsonObject(Field.COMPANY.getName()) != null) {
      return jsonObject.getJsonObject(Field.COMPANY.getName());
    }
    return null;
  }

  private void doGetFonts(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    final long mills = System.currentTimeMillis();
    boolean isCompanyFont = getRequiredBoolean(segment, "isCompanyFont", message, json);
    boolean isUserFont = getRequiredBoolean(segment, "isUserFont", message, json);
    String userId = getUserId(message, segment, json);
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return;
    }
    JsonArray userFonts = new JsonArray();
    JsonArray companyFonts = new JsonArray();
    if (isUserFont) {
      userFonts = Font.getFontsJson(userId, false).getJsonArray("fonts");
    }
    if (isCompanyFont) {
      JsonObject company = parseCompany(json);
      if (company != null) {
        String companyId = company.getString(Field.ID.getName());
        companyFonts = Font.getFontsJson(companyId, true).getJsonArray("fonts");
      } else if (!isUserFont) {
        sendError(
            segment,
            message,
            HttpStatus.NOT_FOUND,
            Utils.getLocalizedString(message, "CompanyNotFound"));
      }
    }
    JsonObject object = new JsonObject();
    object = object.put("fonts", userFonts.addAll(companyFonts));
    sendOK(segment, message, object, mills);
  }

  private void doGetFontById(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String fontId = getRequiredString(segment, "fontId", message, json);
    String userId = getUserId(message, segment, json);
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return;
    }
    byte[] data;
    try {
      Font usersFont = Font.getFontById(userId, fontId, false);
      data = usersFont.getBytes();
    } catch (FontException e) {
      JsonObject company = parseCompany(json);
      if (company != null) {
        try {
          String companyId = company.getString(Field.ID.getName());
          Font usersFont = Font.getFontById(companyId, fontId, true);
          data = usersFont.getBytes();
        } catch (FontException exception) {
          sendError(
              segment,
              message,
              HttpStatus.NOT_FOUND,
              Utils.getLocalizedString(message, "FontNotFound"));
          return;
        }
      } else {
        sendError(
            segment,
            message,
            HttpStatus.NOT_FOUND,
            Utils.getLocalizedString(message, "FontNotFound"));
        return;
      }
    }

    if (data.length != 0) {
      message
          .body()
          .put(Field.DATA.getName(), data)
          .put(Field.STATUS.getName(), Field.OK.getName());
      message.reply(message.body());
    } else {
      sendError(
          segment,
          message,
          HttpStatus.NOT_FOUND,
          Utils.getLocalizedString(message, "FontNotFound"));
    }
  }

  private String getUserId(Message<JsonObject> message, Entity segment, JsonObject json) {
    String token = json.getString(Field.TOKEN.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String userId = null;
    log.info("[FONTS] Going to look for fonts for public link: " + fileId + " token: " + token);
    if (Utils.isStringNotNullOrEmpty(token) && Utils.isStringNotNullOrEmpty(fileId)) {
      PublicLink publicLink = new PublicLink(fileId);
      try {
        if (publicLink.isValid(token, null)) {
          log.info("[FONTS] Valid PL: " + fileId + " token: " + token);
          userId = publicLink.getLastUser();
        } else {
          log.info("[FONTS] Invalid PL: " + fileId + " token: " + token);
        }
      } catch (PublicLinkException publicLinkException) {
        sendError(
            segment, message, publicLinkException.toResponseObject(), HttpStatus.NOT_FOUND, "PL1");
      }
    } else {
      userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    }
    return userId;
  }

  private void doUploadFont(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    boolean isUploadForCompany = getRequiredBoolean(segment, "isUploadForCompany", message, json);
    String filename = message.body().getString(Field.FILE_NAME.getName());
    String authorId;
    byte[] bytes = message.body().getBinary("bytes");

    if (Utils.isObjectNameLong(filename)) {
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

    if (isUploadForCompany) {
      try {
        JsonObject company = parseCompany(json);
        if (company != null) {
          try {
            if (company.getBoolean(Field.IS_ADMIN.getName())) {
              authorId = company.getString(Field.ID.getName());
            } else {
              sendError(
                  segment,
                  message,
                  HttpStatus.BAD_REQUEST,
                  Utils.getLocalizedString(message, "UserIsNotCompanyAdmin"));
              return;
            }
          } catch (Exception e) {
            sendError(
                segment,
                message,
                HttpStatus.BAD_REQUEST,
                Utils.getLocalizedString(message, "ErrorOnUploadFont"));
            return;
          }
        } else {
          sendError(
              segment,
              message,
              HttpStatus.NOT_FOUND,
              Utils.getLocalizedString(message, "CompanyNotFound"));
          return;
        }
      } catch (Exception ex) {
        sendError(
            segment,
            message,
            HttpStatus.BAD_REQUEST,
            Utils.getLocalizedString(message, "ErrorOnUploadFont"));
        return;
      }
    } else {
      authorId = userId;
    }
    Font newFont;
    try {
      newFont = new Font(authorId, filename, isUploadForCompany, bytes);
      newFont.createOrUpdate();
    } catch (FontException ple) {
      sendError(segment, message, ple.toResponseObject(), HttpStatus.BAD_REQUEST);
      return;
    }

    JsonObject object =
        new JsonObject().put("fontName", filename).put("fontId", newFont.getFontId());
    sendOK(segment, message, object, mills);
  }

  private void doDeleteFont(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fontId = getRequiredString(segment, "fontId", message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    try {
      Font deleteFont = Font.getFontById(userId, fontId, false);
      if (deleteFont.getAuthorId().equals(userId)) {
        deleteFont.deleteFont();
      } else {
        sendError(
            segment,
            message,
            HttpStatus.BAD_REQUEST,
            Utils.getLocalizedString(message, "NoUserFont"));
        return;
      }
    } catch (FontException e) {
      JsonObject company = parseCompany(json);
      if (company != null) {
        try {
          String companyId = company.getString(Field.ID.getName());
          Boolean isOrgAdmin = company.getBoolean(Field.IS_ADMIN.getName());
          Font deleteFont = Font.getFontById(companyId, fontId, true);
          if (deleteFont.getAuthorId().equals(companyId) && isOrgAdmin) {
            deleteFont.deleteFont();
          } else {
            sendError(
                segment,
                message,
                HttpStatus.BAD_REQUEST,
                Utils.getLocalizedString(message, "CantDeleteCompanyFont"));
            return;
          }
        } catch (FontException exception) {
          sendError(
              segment,
              message,
              HttpStatus.NOT_FOUND,
              Utils.getLocalizedString(message, "FontNotFound"));
          return;
        }
      } else {
        sendError(
            segment,
            message,
            HttpStatus.NOT_FOUND,
            Utils.getLocalizedString(message, "FontNotFound"));
        return;
      }
    }
    JsonObject object = new JsonObject().put("fontId", fontId);
    sendOK(segment, message, object, mills);
  }
}
