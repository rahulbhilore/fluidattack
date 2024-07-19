package com.graebert.storage.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

public class HttpClientWrapper {
  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().build();

  public HttpClientWrapper() {}

  public <T> HttpResponse<T> send(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
      throws IOException, InterruptedException {
    HttpResponse<T> httpResponse = HTTP_CLIENT.send(request, responseBodyHandler);

    if (httpResponse.statusCode() == 302
        && httpResponse.headers().firstValue(Field.LOCATION.getName()).isPresent()) {
      return send(
          buildRedirectRequest(
              httpResponse.headers().firstValue(Field.LOCATION.getName()).get()),
          responseBodyHandler);
    }

    return httpResponse;
  }

  public <T> CompletableFuture<HttpResponse<T>> sendAsync(
      HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return send(request, responseBodyHandler);
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private HttpRequest buildRedirectRequest(String location) {
    return HttpRequest.newBuilder(URI.create(location)).GET().build();
  }
}
