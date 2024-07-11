package com.graebert.storage.resources;

import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;

public enum ObjectFilter {
  FILES("FILE"),
  FOLDERS("FOLDER"),
  ALL("ALL");

  public final String type;

  ObjectFilter(String type) {
    this.type = type;
  }

  public static ObjectFilter getType(String value) {
    if (Utils.isStringNotNullOrEmpty(value)) {
      final String formattedValue = value.trim();
      for (ObjectFilter t : ObjectFilter.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
    }
    DynamoBusModBase.log.warn("[RESOURCES] Invalid object filter: " + value);
    return null;
  }
}
