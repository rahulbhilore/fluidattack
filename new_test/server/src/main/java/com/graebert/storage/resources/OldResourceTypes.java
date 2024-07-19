package com.graebert.storage.resources;

import com.graebert.storage.util.Utils;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum OldResourceTypes {
  BLOCK("Block"),
  BLOCKLIBRARY("BlockLibrary");

  public final String translationName;

  OldResourceTypes(String translationName) {
    this.translationName = translationName;
  }

  public static OldResourceTypes getType(String value) {
    if (Utils.isStringNotNullOrEmpty(value)) {
      final String formattedValue = value.trim();
      for (OldResourceTypes t : OldResourceTypes.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
    }
    return null;
  }

  public static List<String> getValues() {
    return Arrays.stream(OldResourceTypes.values()).map(Enum::name).collect(Collectors.toList());
  }
}
