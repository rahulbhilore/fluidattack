package com.graebert.storage.resources;

import com.graebert.storage.util.Utils;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum ResourceOwnerType {
  ORG("ORG#"),
  GROUP("GROUP#"),
  OWNED("OWNED#"),
  SHARED("SHARED#"),
  PUBLIC("PUBLIC#");

  public final String label;

  ResourceOwnerType(String label) {
    this.label = label;
  }

  public static List<String> getValues() {
    return Arrays.stream(ResourceOwnerType.values()).map(Enum::name).collect(Collectors.toList());
  }

  public static ResourceOwnerType getType(String value) {
    if (!Utils.isStringNotNullOrEmpty(value)) {
      return PUBLIC;
    }
    final String formattedValue = value.trim();
    for (ResourceOwnerType t : ResourceOwnerType.values()) {
      if (t.name().equalsIgnoreCase(formattedValue)) {
        return t;
      }
    }
    return PUBLIC;
  }
}
