package com.graebert.storage.handler;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.ext.web.RoutingContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

public class RequestMetadataHandler implements Handler<RoutingContext> {

  private static final Pattern endodedPattern = Pattern.compile("^(?:[^%]*%[0-9a-fA-F]{2}[^%]*)+$");

  @Override
  public void handle(RoutingContext routingContext) {
    MultiMap headersMap = routingContext.request().headers();
    if (Objects.nonNull(headersMap) && !headersMap.isEmpty()) {
      headersMap.entries().stream()
          .filter(header -> {
            String headerKey = header.getKey();
            return headerKey.equalsIgnoreCase(Metadata.FILE_ID.label)
                || headerKey.equalsIgnoreCase(Metadata.FOLDER_ID.label)
                || headerKey.equalsIgnoreCase(Metadata.ID.label);
          })
          .forEach(header -> {
            String value = header.getValue();
            if (Utils.isStringNotNullOrEmpty(value)
                && endodedPattern.matcher(value).find()) {
              String decodedValue = URLDecoder.decode(value, StandardCharsets.UTF_8);
              routingContext.request().headers().set(header.getKey(), decodedValue);
            }
          });
    }

    MultiMap formAttributesMap = routingContext.request().formAttributes();
    if (Objects.nonNull(formAttributesMap) && !formAttributesMap.isEmpty()) {
      formAttributesMap.entries().stream()
          .filter(param -> {
            String paramKey = param.getKey();
            return paramKey.equalsIgnoreCase(Metadata.FILE_ID.label)
                || paramKey.equalsIgnoreCase(Metadata.FOLDER_ID.label)
                || paramKey.equalsIgnoreCase(Metadata.ID.label);
          })
          .forEach(param -> {
            String value = param.getValue();
            if (Utils.isStringNotNullOrEmpty(value)
                && endodedPattern.matcher(value).find()) {
              String decodedValue = URLDecoder.decode(value, StandardCharsets.UTF_8);
              routingContext.request().formAttributes().set(param.getKey(), decodedValue);
            }
          });
    }
    routingContext.next();
  }

  public enum Metadata {
    FILE_ID(Field.FILE_ID.getName()),
    FOLDER_ID(Field.FOLDER_ID.getName()),
    ID(Field.ID.getName());

    public final String label;

    Metadata(String label) {
      this.label = label;
    }
  }
}
