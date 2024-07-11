package com.graebert.storage.Entities;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class PublicLinkException extends Exception {

  private PublicLinkErrorCodes code;

  public PublicLinkException(String errorMessage) {
    super(errorMessage);
  }

  public PublicLinkException(String errorMessage, Throwable rootCause) {
    super(errorMessage, rootCause);
  }

  public PublicLinkException(String errorMessage, PublicLinkErrorCodes errorCode) {
    super(errorMessage);
    this.code = errorCode;
  }

  public PublicLinkException(
      String errorMessage, Throwable rootCause, PublicLinkErrorCodes errorCode) {
    super(errorMessage, rootCause);
    this.code = errorCode;
  }

  public PublicLinkErrorCodes getCode() {
    return code;
  }

  public JsonObject toResponseObject() {
    return new JsonObject()
        .put(Field.ERROR_CONSTANT.getName(), this.code.name())
        .put(Field.ERROR_CODE.getName(), this.code.getCode())
        .put(Field.ERROR_MESSAGE.getName(), this.getMessage());
  }
}
