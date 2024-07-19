package com.graebert.storage.resources;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class KudoResourceException extends Exception {
  private final KudoResourceErrorCodes code;
  private final String errorId;
  private final int httpStatus;

  public KudoResourceException(
      String errorMessage, KudoResourceErrorCodes errorCode, int httpStatus, String errorId) {
    super(errorMessage);
    this.code = errorCode;
    this.errorId = errorId;
    this.httpStatus = httpStatus;
  }

  public KudoResourceErrorCodes getCode() {
    return code;
  }

  public int getHttpStatus() {
    return httpStatus;
  }

  public String getErrorId() {
    return errorId;
  }

  public JsonObject toResponseObject() {
    return new JsonObject()
        .put(Field.ERROR_CONSTANT.getName(), this.code.name())
        .put(Field.ERROR_CODE.getName(), this.code.getCode())
        .put(Field.ERROR_MESSAGE.getName(), this.getMessage());
  }
}
