package com.graebert.storage.util.urls;

import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.json.JSONObject;

public class ShortURL {
  private static String shortenServiceURL;
  private final String rawUrl;
  private final String shortURL;

  public ShortURL(final String urlToShorten) {
    rawUrl = urlToShorten;
    shortURL = shortenURL();
  }

  public static void setShortenServiceURL(final String serviceURL) {
    ShortURL.shortenServiceURL = serviceURL;
  }

  private String shortenURL() {
    if (!Utils.isStringNotNullOrEmpty(ShortURL.shortenServiceURL)) {
      return rawUrl;
    }
    try {
      HttpResponse<JsonNode> shortenResponse = AWSXRayUnirest.post(
              ShortURL.shortenServiceURL, "shortUrl.shortenURL")
          .body(new JSONObject().put(Field.URL.getName(), rawUrl))
          .asJson();
      if (shortenResponse.getStatus() == 200) {
        String shortenURL = shortenResponse.getBody().getObject().getString(Field.URL.getName());
        if (Utils.isStringNotNullOrEmpty(shortenURL)) {
          return shortenURL;
        }
      }
    } catch (Exception ignored) {
    }
    return rawUrl;
  }

  public String getShortURL() {
    return shortURL;
  }
}
