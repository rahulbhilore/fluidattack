package com.graebert.storage.resources;

import com.graebert.storage.resources.integration.BlockLibrary;
import com.graebert.storage.resources.integration.Fonts;
import com.graebert.storage.resources.integration.Lisp;
import com.graebert.storage.resources.integration.Templates;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum ResourceType {
  FONTS,
  TEMPLATES,
  BLOCKS,
  LISP;

  public static ResourceType getType(String value) {
    if (Utils.isStringNotNullOrEmpty(value)) {
      final String formattedValue = value.trim();
      for (ResourceType t : ResourceType.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
    }
    return null;
  }

  public static List<String> getValues() {
    return Arrays.stream(ResourceType.values()).map(Enum::name).collect(Collectors.toList());
  }

  public static String getAddress(ResourceType type) {
    switch (type) {
      case BLOCKS:
        return BlockLibrary.address;
      case LISP:
        return Lisp.address;
      case FONTS:
        return Fonts.address;
      case TEMPLATES:
        return Templates.address;
    }
    return Dataplane.emptyString;
  }

  public static JsonArray getAllowedFileTypes(ResourceType type) {
    switch (type) {
      case BLOCKS:
        return BlockLibrary.supportedFileTypes;
      case LISP:
        return Lisp.supportedFileTypes;
      case FONTS:
        return Fonts.supportedFileTypes;
      case TEMPLATES:
        return Templates.supportedFileTypes;
    }
    return new JsonArray();
  }
}
