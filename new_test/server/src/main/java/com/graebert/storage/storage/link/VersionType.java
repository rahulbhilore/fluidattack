package com.graebert.storage.storage.link;

import com.graebert.storage.util.Field;

public enum VersionType {
  LATEST(Field.LATEST.getName()),
  LAST_PRINTED("last_printed"),
  SPECIFIC("specific");

  private final String value;

  VersionType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }

  /**
   * Return type of version. If versionId cant be parsed - it means, version is SCPECIFIC
   * @param value - String having one of type or Field.VERSION_ID.getName()
   * @return one of enum values
   */
  public static VersionType parse(String value) {
    try {
      return VersionType.valueOf(value.toUpperCase());
    } catch (IllegalArgumentException e) {
      return SPECIFIC;
    }
  }
}
