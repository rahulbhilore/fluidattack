package com.graebert.storage.storage.link;

public enum LinkType {
  //  FILE, todo transfer old links
  VERSION,
  DOWNLOAD;

  public static LinkType parse(String val) {
    return LinkType.valueOf(val.toUpperCase());
  }
}
