package com.graebert.storage.Entities;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.Objects;

public class KudoFileException extends Exception {

  private final KudoErrorCodes code;
  private final String ERROR_ID = Field.ERROR_ID.getName();
  private final String STATUS_CODE = Field.STATUS_CODE.getName();
  private final String errorId;
  private final int httpStatus;
  private JsonObject resultObject;

  public KudoFileException(
      String errorMessage, KudoErrorCodes errorCode, int httpStatus, String errorId) {
    super(errorMessage);
    this.code = errorCode;
    this.errorId = (String) getErrorParameters(errorMessage, errorId, ERROR_ID);
    this.httpStatus = (int) getErrorParameters(errorMessage, httpStatus, STATUS_CODE);
  }

  public KudoFileException(String errorMessage, KudoErrorCodes errorCode, int httpStatus) {
    super(errorMessage);
    this.code = errorCode;
    this.errorId = (String) getErrorParameters(errorMessage, "", ERROR_ID);
    this.httpStatus = (int) getErrorParameters(errorMessage, httpStatus, STATUS_CODE);
  }

  public KudoFileException(
      String errorMessage,
      KudoErrorCodes errorCode,
      int httpStatus,
      String errorId,
      JsonObject resultObject) {
    super(errorMessage);
    this.code = errorCode;
    this.errorId = (String) getErrorParameters(errorMessage, errorId, ERROR_ID);
    this.httpStatus = (int) getErrorParameters(errorMessage, httpStatus, STATUS_CODE);
    this.resultObject = resultObject;
  }

  public static void checkAndThrowKudoFileException(
      Message message, JsonObject result, int status, KudoErrorCodes errorCode)
      throws KudoFileException {
    JsonObject messageObj = null;
    if (result.containsKey(Field.MESSAGE.getName())
        && result.getValue(Field.MESSAGE.getName()) instanceof JsonObject) {
      messageObj = result.getJsonObject(Field.MESSAGE.getName());
      if (messageObj.containsKey(Field.ERROR_CONSTANT.getName())
          && Arrays.asList(KudoErrorCodes.values())
              .contains(
                  KudoErrorCodes.valueOf(messageObj.getString(Field.ERROR_CONSTANT.getName())))) {
        // Kudo file exception
        throw new KudoFileException(
            messageObj.getString(Field.ERROR_MESSAGE.getName()),
            KudoErrorCodes.valueOf(messageObj.getString(Field.ERROR_CONSTANT.getName())),
            status,
            messageObj.getString(Field.ERROR_ID.getName()));
      }
    }
    if (Objects.nonNull(messageObj)) {
      String errorId = messageObj.getString(Field.ERROR_ID.getName());
      if (Utils.isStringNotNullOrEmpty(errorId)) {
        throw new KudoFileException(
            KudoFileException.getErrorMessage(message, result), errorCode, status, errorId);
      }
    }
    throw new KudoFileException(
        KudoFileException.getErrorMessage(message, result), errorCode, status);
  }

  public static String getErrorMessage(Message message, JsonObject errorMessageObj) {
    if (errorMessageObj.containsKey(Field.MESSAGE.getName())) {
      if (errorMessageObj.getValue(Field.MESSAGE.getName()) instanceof String) {
        return errorMessageObj.getString(Field.MESSAGE.getName());
      } else if (errorMessageObj.getValue(Field.MESSAGE.getName()) instanceof JsonObject) {
        JsonObject outerObj = errorMessageObj.getJsonObject(Field.MESSAGE.getName());
        if (outerObj.getValue(Field.MESSAGE.getName()) instanceof String) {
          return outerObj.getString(Field.MESSAGE.getName());
        } else if (outerObj.getValue(Field.MESSAGE.getName()) instanceof JsonObject) {
          JsonObject innerObj = errorMessageObj.getJsonObject(Field.MESSAGE.getName());
          if (innerObj.getValue(Field.MESSAGE.getName()) instanceof String) {
            return innerObj.getString(Field.MESSAGE.getName());
          }
        }
      }
    }
    if (Objects.nonNull(message)) {
      return Utils.getLocalizedString(message, "SomethingWentWrongForFiles");
    }
    return "";
  }

  public KudoErrorCodes getCode() {
    return code;
  }

  public JsonObject getResultObject() {
    return resultObject;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getErrorId() {
    return errorId;
  }

  public JsonObject toResponseObject() {
    String errorMessage = this.getMessage();
    try {
      JsonObject errorObject = new JsonObject(this.getMessage());
      errorMessage = getErrorMessage(null, errorObject);
    } catch (Exception ignored) {
    }
    return new JsonObject()
        .put(Field.ERROR_CONSTANT.getName(), this.code.name())
        .put(Field.ERROR_CODE.getName(), this.code.getCode())
        .put(Field.ERROR_MESSAGE.getName(), errorMessage)
        .put(Field.MESSAGE.getName(), errorMessage);
  }

  private static Object getErrorParameters(String errorMessage, Object defValue, String field) {
    try {
      JsonObject errorObject = new JsonObject(errorMessage);
      if (errorObject.containsKey(field)) {
        return errorObject.getValue(field);
      }
    } catch (Exception ignored) {
    }
    return defValue;
  }
}
