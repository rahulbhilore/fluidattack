package com.graebert.storage.storage.zipRequest;

public enum ExcludeReason {
  Large,
  NotFound;

  public String getKey() {
    return "excluded".concat(this.name()).concat("Files");
  }
}
