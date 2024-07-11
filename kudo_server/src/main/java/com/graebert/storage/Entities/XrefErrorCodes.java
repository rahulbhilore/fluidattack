package com.graebert.storage.Entities;

public enum XrefErrorCodes {
  FILE_ALREADY_ATTACHED(0),
  FILE_DOESNT_HAVE_XREF(1),
  FILE_ALREADY_DETACHED(2);

  private final int code;

  XrefErrorCodes(int code) {
    this.code = code;
  }

  public int getCode() {
    return this.code;
  }
}
