package com.graebert.storage.vertx;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.AsyncResult;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import kong.unirest.JsonNode;
import kong.unirest.json.JSONObject;
import org.jetbrains.annotations.NonNls;

public class ResponseHandler extends BaseVerticle {
  @NonNls
  public static final String OK = Field.OK.getName();

  @NonNls
  private static final String ERROR = Field.ERROR.getName();

  protected static String instanceRegion;

  protected static <T> String prepareLogStringFromMessage(Message<T> message) {
    String userId = null,
        sessionId = null,
        xSessionId = null,
        fileId = null,
        folderId = null,
        objId = null,
        externalId = null,
        userAgent = null,
        boxUploadIp = null,
        boxApiIp = null,
        traceId = null;

    try {
      JsonObject jsonObject = MessageUtils.parse(message).getJsonObject();

      userId = jsonObject.getString(Field.USER_ID.getName());
      externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
      sessionId = jsonObject.getString(Field.SESSION_ID.getName());
      xSessionId = jsonObject.getString(Field.X_SESSION_ID.getName());
      fileId = jsonObject.getString(Field.FILE_ID.getName());
      folderId = jsonObject.getString(Field.FOLDER_ID.getName());
      objId = jsonObject.getString(Field.ID.getName());
      userAgent = jsonObject.getString(Field.USER_AGENT.getName());
      traceId = jsonObject.getString(Field.TRACE_ID.getName());
      boxUploadIp = jsonObject.getString("boxUploadIp");
      boxApiIp = jsonObject.getString("boxApiIp");
    } catch (Exception ignore) {
    }

    return (userId != null ? ", userId: " + userId : "")
        + (sessionId != null ? ", sessionId: " + sessionId : "")
        + (xSessionId != null ? ", xSessionId: " + xSessionId : "")
        + (externalId != null ? ", externalId: " + externalId : "")
        + (fileId != null ? ", fileId: " + fileId : "")
        + (folderId != null ? ", folderId: " + folderId : "")
        + (objId != null ? ", objId: " + objId : "")
        + (boxUploadIp != null ? ", boxUploadIp: " + boxUploadIp : "")
        + (boxApiIp != null ? ", boxApiIp: " + boxApiIp : "")
        + (userAgent != null ? ", userAgent: " + userAgent : "")
        + (traceId != null ? ", traceId: " + traceId : "");
  }

  protected static String prepareStackMessage() {
    StackTraceElement el = null;
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (int i = 1; i < stackTrace.length; i++) {
      if (!stackTrace[i].getClassName().equals(DynamoBusModBase.class.getName())
          && !stackTrace[i].getClassName().equals(ResponseHandler.class.getName())) {
        el = stackTrace[i];
        break;
      }
    }
    if (el == null) {
      return "";
    }
    return " (" + el.getFileName() + ":" + el.getMethodName() + ") ";
  }

  protected <T, K> void sendError(Entity segment, Message<T> message, Message<K> original) {
    Boolean no_debug_log = null;

    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    JsonObject originalJsonObject = MessageUtils.parse(original).getJsonObject();

    JsonObject messageObject = originalJsonObject
        .getJsonObject(Field.MESSAGE.getName())
        .put(Field.ERROR_ID.getName(), originalJsonObject.getString(Field.ERROR_ID.getName()));

    JsonObject result = new JsonObject()
        .put(Field.STATUS.getName(), ERROR)
        .put(Field.MESSAGE.getName(), messageObject)
        .put(
            Field.STATUS_CODE.getName(), originalJsonObject.getInteger(Field.STATUS_CODE.getName()))
        .put(Field.ERROR_ID.getName(), originalJsonObject.getString(Field.ERROR_ID.getName()));

    log.error(LogPrefix.getLogPrefix() + prepareStackMessage()
        + (segment != null ? segment.getName() : "")
        + " "
        + originalJsonObject
            .getJsonObject(Field.MESSAGE.getName())
            .getString(Field.MESSAGE.getName())
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + result : ""));

    XRayEntityUtils.addExceptionData(
        segment,
        originalJsonObject.getInteger(Field.STATUS_CODE.getName()),
        originalJsonObject.getString(Field.ERROR_ID.getName()),
        messageObject.toString());

    message.reply(result);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendError(
      Entity segment,
      Message<T> message,
      String error,
      int statusCode,
      DynamoBusModBase.LogLevel logLevel) {
    sendError(segment, message, error, statusCode, "", logLevel);
  }

  protected <T> void sendError(Entity segment, Message<T> message, String error, int statusCode) {
    sendError(segment, message, error, statusCode, DynamoBusModBase.LogLevel.ERROR);
  }

  protected <T> void sendError(Entity segment, Message<T> message, int statusCode, String errorId) {
    sendError(segment, message, Utils.getLocalizedString(message, errorId), statusCode, errorId);
  }

  protected <T> void sendError(
      Entity segment, Message<T> message, String error, int statusCode, String errorId) {
    sendError(segment, message, error, statusCode, errorId, DynamoBusModBase.LogLevel.ERROR);
  }

  protected <T> void sendError(
      Entity segment,
      Message<T> message,
      String error,
      int statusCode,
      String errorId,
      DynamoBusModBase.LogLevel logLevel) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }

    errorId = statusCode == 500 ? "FL0" : errorId;

    JsonObject messageObject =
        new JsonObject().put(Field.MESSAGE.getName(), error).put(Field.ERROR_ID.getName(), errorId);

    JsonObject result = new JsonObject()
        .put(Field.STATUS.getName(), ERROR)
        .put(Field.MESSAGE.getName(), messageObject)
        .put(Field.STATUS_CODE.getName(), statusCode)
        .put(Field.ERROR_ID.getName(), errorId);

    String str = LogPrefix.getLogPrefix() + prepareStackMessage()
        + (segment != null ? segment.getName() : "")
        + " " + error
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + result : "");

    if (DynamoBusModBase.LogLevel.ERROR.equals(logLevel)) {
      log.error(str);
    } else if (DynamoBusModBase.LogLevel.WARN.equals(logLevel)) {
      log.warn(str);
    } else {
      log.info(str);
    }

    XRayEntityUtils.addExceptionData(segment, statusCode, errorId, messageObject.toString());

    message.reply(result);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendError(Entity segment, Message<T> message, JsonObject error) {
    int statusCode = error.getInteger(Field.STATUS_CODE.getName());
    sendError(segment, message, error, statusCode, DynamoBusModBase.LogLevel.ERROR);
  }

  protected <T> void sendError(
      Entity segment, Message<T> message, JsonObject error, int statusCode) {
    sendError(segment, message, error, statusCode, DynamoBusModBase.LogLevel.ERROR);
  }

  protected <T> void sendError(
      Entity segment, Message<T> message, JsonObject error, int statusCode, String errorId) {
    sendError(segment, message, error, statusCode, DynamoBusModBase.LogLevel.ERROR, errorId);
  }

  protected <T> void sendError(
      Entity segment,
      Message<T> message,
      JsonObject error,
      int statusCode,
      DynamoBusModBase.LogLevel logLevel) {
    sendError(segment, message, error, statusCode, logLevel, "");
  }

  protected <T> void sendError(
      Entity segment,
      Message<T> message,
      JsonObject error,
      int statusCode,
      DynamoBusModBase.LogLevel logLevel,
      String errorId) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    JsonObject result = (new JsonObject())
        .put(Field.STATUS.getName(), ERROR)
        .put(Field.MESSAGE.getName(), error.put(Field.ERROR_ID.getName(), errorId))
        .put(Field.STATUS_CODE.getName(), statusCode)
        .put(Field.ERROR_ID.getName(), errorId);

    String str = LogPrefix.getLogPrefix() + prepareStackMessage()
        + (segment != null ? segment.getName() : "")
        + " " + statusCode + " " + error
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + result : "");

    XRayEntityUtils.addExceptionData(
        segment,
        statusCode,
        errorId,
        error.put(Field.ERROR_ID.getName(), errorId).toString());

    if (DynamoBusModBase.LogLevel.ERROR.equals(logLevel)) {
      log.error(str);
    } else {
      log.info(str);
    }
    message.reply(result);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendError(
      Entity segment, Message<T> message, String error, int statusCode, Throwable e) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    String error1 = error;
    try {
      JsonObject js = new JsonObject(error);
      if (js.getString("error_description") != null) {
        error1 = js.getString("error_description");
      } else if (js.getString(Field.MESSAGE.getName()) != null) {
        error1 = js.getString(Field.MESSAGE.getName());
      }
    } catch (Exception ignore) {
    }
    String errorId = statusCode == 500 ? "FL0" : "";

    JsonObject result = new JsonObject()
        .put(Field.STATUS.getName(), ERROR)
        .put(
            Field.MESSAGE.getName(),
            new JsonObject()
                .put(Field.MESSAGE.getName(), error1)
                .put(Field.ERROR_ID.getName(), errorId))
        .put(Field.STATUS_CODE.getName(), statusCode)
        .put(Field.ERROR_ID.getName(), errorId);

    XRayEntityUtils.addExceptionData(
        segment,
        statusCode,
        errorId,
        new JsonObject()
            .put(Field.MESSAGE.getName(), error1)
            .put(Field.ERROR_ID.getName(), errorId)
            .toString());

    log.error(
        LogPrefix.getLogPrefix() + prepareStackMessage()
            + (segment != null ? segment.getName() : "")
            + " " + error
            + prepareLogStringFromMessage(message)
            + (no_debug_log != null && !no_debug_log ? ", result: " + result : ""),
        e);

    message.reply(result);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendError(
      Entity segment,
      Message<T> message,
      String error,
      int statusCode,
      Throwable e,
      String authHeader) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }

    JsonObject result = new JsonObject()
        .put(Field.STATUS.getName(), ERROR)
        .put(Field.MESSAGE.getName(), new JsonObject().put(Field.MESSAGE.getName(), error))
        .put(Field.STATUS_CODE.getName(), statusCode)
        .put("authHeader", authHeader);

    XRayEntityUtils.addExceptionData(
        segment,
        statusCode,
        error,
        new JsonObject().put(Field.MESSAGE.getName(), error).toString());

    log.info(
        LogPrefix.getLogPrefix() + prepareStackMessage()
            + (segment != null ? segment.getName() : "")
            + " " + error
            + prepareLogStringFromMessage(message)
            + (no_debug_log != null && !no_debug_log ? ", result: " + result : ""),
        e);
    message.reply(result);
    XRayManager.endSegment(segment);
  }

  // with HttpResponse errors todo generify all HttpResponses
  protected <T, K> void sendError(Entity segment, Message<T> message, HttpResponse<K> response) {
    sendError(segment, message, response, -1);
  }

  protected <T, K> void sendError(
      Entity segment, Message<T> message, HttpResponse<K> response, int statusCode) {
    String text = response.getStatusText();
    if (response.getBody() != null) {
      if (response.getBody() instanceof JsonNode) {
        try {
          if (((JsonNode) response.getBody()).getObject().has(Field.MESSAGE.getName())) {
            text = ((JsonNode) response.getBody()).getObject().getString(Field.MESSAGE.getName());
          } else if (((JsonNode) response.getBody()).getObject().has("error_description")) {
            text = ((JsonNode) response.getBody()).getObject().getString("error_description");
          }
        } catch (Exception ignore) {
          text = response.getBody().toString();
        }
      } else if (response.getBody() instanceof String) {
        JSONObject object = null;
        try {
          object = new JsonNode((String) response.getBody()).getObject();
        } catch (Exception ignore) {
        }
        if (object != null) {
          try {
            text = object.getJSONObject(Field.ERROR.getName()).getString(Field.MESSAGE.getName());
          } catch (Exception ignore) {
          }
          try {
            text = object.getJSONObject(Field.ERROR.getName()).getString("error_description");
          } catch (Exception ignore) {
          }
          try {
            text = (String) object.get("error_description");
          } catch (Exception ignore) {
          }
          try {
            text = object.getString(Field.MESSAGE.getName());
          } catch (Exception ignore) {
          }
        }
      }
    }
    int code = statusCode;
    if (code == -1) {
      code = response.getStatus();
      if (code != 404 && code != 401) {
        code = 400;
      }
    }
    sendError(segment, message, text, (code != 401 ? code : 400));
  }

  protected <T> void sendOK(Entity segment, Message<T> message) {
    JsonObject response = new JsonObject().put(Field.STATUS.getName(), OK);
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    log.info(LogPrefix.getLogPrefix()
        + prepareStackMessage()
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + response : ""));
    message.reply(response);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendOK(Entity segment, Message<T> message, int statusCode, long mills) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    JsonObject response = new JsonObject()
        .put(Field.STATUS.getName(), OK)
        .put(Field.STATUS_CODE.getName(), statusCode);
    log.info(LogPrefix.getLogPrefix() + prepareStackMessage() + "was executed in "
        + (System.currentTimeMillis() - mills) + " millis"
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + response : ""));
    message.reply(response);
    XRayManager.endSegment(segment);
  }

  protected <T> void debugLogResponse(
      String url, HttpResponse<JsonNode> response, Message<T> message) {
    Boolean no_debug_log = null;

    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }

    if (no_debug_log != null && !no_debug_log) {
      log.info(LogPrefix.getLogPrefix() + prepareStackMessage()
          + prepareLogStringFromMessage(message) + "API call: url("
          + url + ") response: " + response.getStatus() + ". Result: "
          + (response.getBody() != null ? response.getBody().toString() : "[null]"));
    }
  }

  protected <T> void sendOK(Entity segment, Message<T> message, long mills) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    JsonObject response = new JsonObject().put(Field.STATUS.getName(), OK);
    log.info(LogPrefix.getLogPrefix() + prepareStackMessage() + "was executed in "
        + (System.currentTimeMillis() - mills) + " millis"
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + response : ""));
    message.reply(response);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendOK(
      Entity segment, Message<T> message, int statusCode, @NonNls JsonObject response, long mills) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    response.put(Field.STATUS.getName(), OK).put(Field.STATUS_CODE.getName(), statusCode);

    log.info(LogPrefix.getLogPrefix() + prepareStackMessage() + "was executed in "
        + (System.currentTimeMillis() - mills) + " millis"
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + response : ""));
    message.reply(response);
    XRayManager.endSegment(segment);
  }

  protected <T> void sendOK(
      Entity segment, Message<T> message, @NonNls JsonObject response, long mills) {
    Boolean no_debug_log = null;
    try {
      no_debug_log =
          MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName());
    } catch (Exception ignore) {
    }
    response.put(Field.STATUS.getName(), OK);

    log.info(LogPrefix.getLogPrefix() + prepareStackMessage() + "was executed in "
        + (System.currentTimeMillis() - mills) + " millis"
        + prepareLogStringFromMessage(message)
        + (no_debug_log != null && !no_debug_log ? ", result: " + response : ""));
    message.reply(response);
    XRayManager.endSegment(segment);
  }

  protected <T, K> void handleExternal(
      Entity segment, AsyncResult<Message<K>> event, Message<T> message, long mills) {
    XRayManager.newSegmentContextExecutor(segment);
    if (event.succeeded()) {
      if (OK.equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
        sendOK(segment, message, (JsonObject) event.result().body(), mills);
      } else {
        sendError(segment, message, event.result());
      }
    } else {
      sendError(segment, message, event.cause().getLocalizedMessage(), 500);
    }
  }

  protected <T> boolean isOk(AsyncResult<Message<T>> event) {
    return OK.equals(
        MessageUtils.parse(event.result()).getJsonObject().getString(Field.STATUS.getName()));
  }

  protected <T> void sendNotImplementedError(Message<T> message) {
    sendError(
        null,
        message,
        Utils.getLocalizedString(message, "NotImplemented"),
        HttpStatus.NOT_IMPLEMENTED);
  }
}
