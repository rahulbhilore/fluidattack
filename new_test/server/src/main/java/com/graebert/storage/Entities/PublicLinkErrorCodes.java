package com.graebert.storage.Entities;

public enum PublicLinkErrorCodes {
  TOKEN_IS_INVALID(0),
  LINK_EXPIRED(1),
  PASSWORD_IS_INCORRECT(2),
  LINK_DISABLED(3),
  USER_NOT_FOUND(4),
  LINK_NOT_FOUND(5),
  TOKEN_NOT_SPECIFIED(6),
  LINKS_NOT_ALLOWED_BY_COMPANY(7),
  UNABLE_TO_CHECK_LICENSE(8),
  KUDO_LICENSE_HAS_EXPIRED(9),
  PASSWORD_IS_REQUIRED(10),
  NONCE_IS_REQUIRED(11),
  PASSWORD_IS_INSECURE(12),
  XSESSION_IS_INVALID(13),
  PARAMETERS_ARE_INCOMPLETE(14);

  private final int code;

  PublicLinkErrorCodes(int code) {
    this.code = code;
  }

  public int getCode() {
    return this.code;
  }
}
