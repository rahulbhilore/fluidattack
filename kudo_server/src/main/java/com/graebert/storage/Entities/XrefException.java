package com.graebert.storage.Entities;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class XrefException extends Exception {

  private XrefErrorCodes code;

  public XrefException(String errorMessage) {
    super(errorMessage);
  }

  public XrefException(String errorMessage, Throwable rootCause) {
    super(errorMessage, rootCause);
  }

  public XrefException(String errorMessage, XrefErrorCodes errorCode) {
    super(errorMessage);
    this.code = errorCode;
  }

  public XrefException(String errorMessage, Throwable rootCause, XrefErrorCodes errorCode) {
    super(errorMessage, rootCause);
    this.code = errorCode;
  }

  public XrefErrorCodes getCode() {
    return code;
  }

  public JsonObject toResponseObject() {
    return new JsonObject()
        .put(Field.ERROR_CONSTANT.getName(), this.code.name())
        .put(Field.ERROR_CODE.getName(), this.code.getCode())
        .put(Field.ERROR_MESSAGE.getName(), this.getMessage());
  }
}
