package com.graebert.storage.Entities;

import com.graebert.storage.util.Utils;

public enum ObjectType {
  FILE,
  FOLDER,
  RESOURCE;

  public static ObjectType getType(String value) {
    if (Utils.isStringNotNullOrEmpty(value)) {
      final String formattedValue = value.trim();
      for (ObjectType t : ObjectType.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
    }
    return null;
  }
}
