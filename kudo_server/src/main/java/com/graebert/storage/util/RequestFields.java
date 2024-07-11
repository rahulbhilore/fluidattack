package com.graebert.storage.util;

public enum RequestFields {
  HOST("Host"),
  X_FORWARDED_FOR("X-Forwarded-For"),
  CONTENT_LENGTH("Content-Length"),
  USER_AGENT("User-Agent"),
  LOCALE(Field.LOCALE.getName());

  private final String name;

  RequestFields(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
