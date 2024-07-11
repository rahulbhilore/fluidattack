package com.graebert.storage.thumbnails;

public enum JobPrefixes {
  THUMBNAIL("THUMBNAIL#"),
  COMPARE("COMPARE#"),
  CONVERT("CONVERT#"),
  OTHER("OTHER#");

  public final String prefix;

  JobPrefixes(String prefix) {
    this.prefix = prefix;
  }
}
