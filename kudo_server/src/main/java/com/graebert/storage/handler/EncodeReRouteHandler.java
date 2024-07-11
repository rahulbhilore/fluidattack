package com.graebert.storage.handler;

import com.graebert.storage.util.Utils;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class EncodeReRouteHandler implements Handler<RoutingContext> {
  private static final String slashReplace = "-s-";
  private static final String colonReplace = "-c-";
  private static final String percentReplace = "-p-";
  public static final String exclamationMarkReplace = "%21";

  private static String decodeURL(String url) {
    String decodedURL = URLDecoder.decode(
        url.replace("/", slashReplace)
            .replace(":", colonReplace)
            .replace("%", percentReplace)
            .replace("!", exclamationMarkReplace),
        StandardCharsets.UTF_8);
    return decodedURL
        .replace(slashReplace, "/")
        .replace(colonReplace, ":")
        .replace(percentReplace, "%")
        .replace(exclamationMarkReplace, "!");
  }

  public static String updateURL(String url) {
    String encodedURL = URLEncoder.encode(
        url.replace("/", slashReplace)
            .replace(":", colonReplace)
            .replace("%", percentReplace)
            .replace("!", exclamationMarkReplace),
        StandardCharsets.UTF_8);
    return encodedURL
        .replace(slashReplace, "/")
        .replace(colonReplace, ":")
        .replace(percentReplace, "%")
        .replace(exclamationMarkReplace, "!");
  }

  @Override
  public void handle(RoutingContext routingContext) {
    String url = routingContext.request().path();
    if (Utils.isStringNotNullOrEmpty(url)) {
      // DK: We have to check if it is already properly encoded or not
      String decodedURL = decodeURL(url);
      String updatedURL = updateURL(url);
      if (Utils.isStringNotNullOrEmpty(updatedURL) && !url.equals(updatedURL)) {
        if (Utils.isStringNotNullOrEmpty(decodedURL)) {
          String reEncodedURL = updateURL(decodedURL);
          if (Utils.isStringNotNullOrEmpty(reEncodedURL)
              && !reEncodedURL.equals(url)
              && !url.contains("!")) {
            routingContext.next();
            return;
          }
        }
        url = updatedURL;
        String query = routingContext.request().query();
        routingContext.data().put("encoded", true);
        if (Utils.isStringNotNullOrEmpty(query)) {
          routingContext.reroute(url + "?" + query);
        } else {
          routingContext.reroute(url);
        }
        return;
      }
    }
    routingContext.next();
  }
}
