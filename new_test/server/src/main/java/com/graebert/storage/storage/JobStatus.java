package com.graebert.storage.storage;

import com.graebert.storage.util.Utils;

public enum JobStatus {
  SUCCESS,
  ERROR,
  IN_PROGRESS,
  UNKNOWN;

  public static JobStatus findStatus(String status) {
    if (!Utils.isStringNotNullOrEmpty(status)) {
      return UNKNOWN;
    }
    for (JobStatus s : JobStatus.values()) {
      if (s.name().equalsIgnoreCase(status)) {
        return s;
      }
    }
    return UNKNOWN;
  }
}
