package com.graebert.storage.integration.onedrive.graphApi.exception;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.ResponseHandler;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;

public abstract class GraphException extends Exception {
  private static final String ERROR = Field.ERROR.getName();
  private final int httpStatus;
  private final String errorId;
  private final String emptyString = Dataplane.emptyString;
  private HttpResponse response;

  protected <T> GraphException(String errorId, HttpResponse<T> response) {
    super(Utils.getLocalizedString("en", errorId));
    this.httpStatus = response.getStatus();
    this.response = response;
    this.errorId = errorId;
  }

  protected GraphException(String errorId, Throwable throwable, int httpStatus) {
    super(Utils.getLocalizedString("en", errorId), throwable);
    this.errorId = errorId;
    this.httpStatus = httpStatus;
  }

  protected GraphException(String errorId, Throwable throwable) {
    super(Utils.getLocalizedString("en", errorId), throwable);
    this.errorId = errorId;
    this.httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
  }

  private String prepareStackMessage() {
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
      return emptyString;
    }
    return " (" + el.getFileName() + ":" + el.getMethodName() + ") ";
  }

  private <T> String prepareLogStringFromMessage(Message<T> message) {
    JsonObject jsonObject = MessageUtils.parse(message).getJsonObject();

    List<String> fields = List.of(
        Field.USER_ID.getName(),
        Field.EXTERNAL_ID.getName(),
        Field.SESSION_ID.getName(),
        Field.X_SESSION_ID.getName(),
        Field.FILE_ID.getName(),
        Field.FOLDER_ID.getName(),
        Field.ID.getName(),
        Field.USER_AGENT.getName(),
        Field.TRACE_ID.getName(),
        "boxUploadIp",
        "boxApiIp");

    return fields.stream()
        .filter(jsonObject::containsKey)
        .map(k -> String.format("%s : %s", k, jsonObject.getString(k, "")))
        .collect(Collectors.joining(", "));
  }

  // segment should be closed after
  public <T> void replyWithError(Entity segment, Message<T> message) {
    boolean no_debug_log =
        MessageUtils.parse(message).getJsonObject().getBoolean(Field.NO_DEBUG_LOG.getName(), false);
    String errorId = getErrorId();
    String errorMessage = createErrorMessage(message);
    JsonObject result = createErrorObject(errorMessage, errorId);

    sendErrorLog(segment, message, result, no_debug_log);

    message.reply(result);
  }

  public <T> String createErrorMessage(Message<T> message) {
    String errorMessage = (Objects.nonNull(this.getCause()))
        ? this.getCause().getLocalizedMessage()
        : this.getLocalizedMessage();
    if (!Utils.isStringNotNullOrEmpty(errorMessage) && Objects.nonNull(this.response)) {
      if (this.response.getBody() instanceof String) {
        JsonObject body = new JsonObject((String) this.response.getBody());
        if (body.containsKey(Field.ERROR.getName())) {
          JsonObject errorBody = body.getJsonObject(Field.ERROR.getName());
          if (errorBody.containsKey(Field.LOCALIZED_MESSAGE.getName())) {
            errorMessage = errorBody.getString(Field.LOCALIZED_MESSAGE.getName());
          } else if (errorBody.containsKey(Field.MESSAGE.getName())) {
            errorMessage = errorBody.getString(Field.MESSAGE.getName());
          } else {
            errorMessage = errorBody.getString(Field.CODE.getName());
          }
        }
      } else {
        errorMessage = this.response.getStatusText();
      }
    }
    String finalMessage = Utils.getLocalizedString(message, errorMessage);
    if (!Utils.isStringNotNullOrEmpty(finalMessage)) {
      finalMessage = errorMessage;
    }
    return finalMessage;
  }

  public JsonObject createErrorObject(String errorMessage, String errorId) {
    return (new JsonObject())
        .put(Field.STATUS.getName(), ERROR)
        .put(
            Field.MESSAGE.getName(),
            new JsonObject()
                .put(Field.MESSAGE.getName(), errorMessage)
                .put(Field.ERROR_ID.getName(), errorId))
        .put(Field.STATUS_CODE.getName(), this.httpStatus)
        .put(Field.ERROR_ID.getName(), errorId);
  }

  public <T> void sendErrorLog(
      Entity segment, Message<T> message, JsonObject result, boolean no_debug_log) {
    LogManager.getRootLogger()
        .error(
            LogPrefix.getLogPrefix() + prepareStackMessage()
                + (segment != null ? segment.getName() : emptyString) + " " + this
                + prepareLogStringFromMessage(message)
                + (!no_debug_log ? ", result: " + result : emptyString),
            this.getCause());
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public <T> HttpResponse<T> getResponse() {
    return response;
  }

  public String getErrorId() {
    return httpStatus == HttpStatus.INTERNAL_SERVER_ERROR ? "FL0" : this.errorId;
  }
}
