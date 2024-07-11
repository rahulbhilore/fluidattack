package com.graebert.storage.integration.hancom;

import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.util.HashMap;
import java.util.Map;

public class HancomException extends Exception {
  private final ErrorCode errorCode;
  private final String rawResponseCode;

  public HancomException(String responseCode, String reason) {
    super(reason);
    this.rawResponseCode = responseCode;
    this.errorCode = ErrorCode.get(responseCode);
  }

  public String getExceptionType() {
    return errorCode.name();
  }

  public int getStatusCode() {
    return errorCode.getStatusCode();
  }

  public JsonObject toJson() {
    return new JsonObject()
        .put(Field.ERROR_CODE.getName(), errorCode.name())
        .put(Field.REASON.getName(), super.getMessage())
        .put(Field.MESSAGE.getName(), super.getMessage())
        .put(Field.RESPONSE_CODE.getName(), this.rawResponseCode);
  }

  public enum ErrorCode {
    OK("01.000", 200),
    TOKEN_EXPIRED("02.004", 400),
    UNKNOWN("03.022", 500);
    private static final Map<String, ErrorCode> lookup = new HashMap<String, ErrorCode>();

    static {
      for (ErrorCode d : ErrorCode.values()) {
        lookup.put(d.getResponseCode(), d);
      }
    }

    private final String responseCode;
    private final int statusCode;

    ErrorCode(String responseCode, int statusCode) {
      this.responseCode = responseCode;
      this.statusCode = statusCode;
    }

    public static ErrorCode get(String code) {
      ErrorCode foundCode = lookup.get(code);
      if (foundCode == null) {
        return UNKNOWN;
      }
      return foundCode;
    }

    public String getResponseCode() {
      return this.responseCode;
    }

    public int getStatusCode() {
      return this.statusCode;
    }
  }
}
