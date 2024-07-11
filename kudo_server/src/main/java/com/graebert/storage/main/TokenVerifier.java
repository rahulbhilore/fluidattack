package com.graebert.storage.main;

import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.util.Utils;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import kong.unirest.HttpResponse;

public class TokenVerifier {
  private final JsonObject parameters = new JsonObject();
  private String token;

  TokenVerifier(RoutingContext routingContext) {
    MultiMap params = routingContext.request().params();
    MultiMap headers = routingContext.request().headers();
    params.forEach(map -> parameters.put(map.getKey(), map.getValue()));
    headers.forEach(map -> {
      String headerName = map.getKey();
      if (headerName.equals("x-api-token")) {
        this.token = map.getValue();
        this.fetchDetailsByToken();
      } else {
        parameters.put(map.getKey(), map.getValue());
      }
    });
  }

  private void fetchDetailsByToken() {
    if (Utils.isStringNotNullOrEmpty(token)) {
      HttpResponse<String> response = AWSXRayUnirest.get(
              "https://tokens.dev.graebert.com/verify", "tokenVerifier.fetchDetailsByToken")
          .header("x-api-key", token)
          .asString();
      if (response.isSuccess()) {
        JsonObject jsonObject = new JsonObject(response.getBody());
        parameters.mergeIn(jsonObject);
      }
    }
  }

  public JsonObject getParameters() {
    return parameters;
  }
}
