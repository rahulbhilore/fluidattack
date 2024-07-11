package com.graebert.storage.Entities;

public enum FontErrorCodes {
  USER_NOT_FOUND(1),
  FONT_NOT_FOUND(2),
  UNSUPPORTED_FONT(3),
  INVALID_FONT_DATA(4),
  BAD_DB_CONNECTION(5);

  private final int code;

  FontErrorCodes(int code) {
    this.code = code;
  }

  public int getCode() {
    return this.code;
  }
}
