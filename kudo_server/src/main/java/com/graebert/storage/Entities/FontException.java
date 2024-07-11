package com.graebert.storage.Entities;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class FontException extends Exception {

  private FontErrorCodes code;

  public FontException(String errorMessage) {
    super(errorMessage);
  }

  public FontException(String errorMessage, Throwable rootCause) {
    super(errorMessage, rootCause);
  }

  public FontException(String errorMessage, FontErrorCodes errorCode) {
    super(errorMessage);
    this.code = errorCode;
  }

  public FontException(String errorMessage, Throwable rootCause, FontErrorCodes errorCode) {
    super(errorMessage, rootCause);
    this.code = errorCode;
  }

  public FontErrorCodes getCode() {
    return code;
  }

  public JsonObject toResponseObject() {
    return new JsonObject()
        .put(Field.ERROR_CONSTANT.getName(), this.code.name())
        .put(Field.ERROR_CODE.getName(), this.code.getCode())
        .put(Field.ERROR_MESSAGE.getName(), this.getMessage());
  }
}
