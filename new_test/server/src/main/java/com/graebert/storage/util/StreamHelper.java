package com.graebert.storage.util;

import com.github.sardine.Sardine;
import com.github.sardine.impl.SardineImpl;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.S3PresignedUploadRequests;
import com.graebert.storage.vertx.HttpStatusCodes;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLSession;
import kong.unirest.HttpStatus;
import org.apache.http.HttpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StreamHelper {
  private static final Logger log = LogManager.getRootLogger();
  private static final HttpClientWrapper client = new HttpClientWrapper();

  public static void downloadToStream(
      RoutingContext routingContext, JsonObject body, String errorId, int statusCode) {
    String errorMessage = Utils.getLocalizedString(RequestUtils.getLocale(routingContext), errorId);
    StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
    CompletableFuture<HttpResponse<InputStream>> future;
    if (storageType.equals(StorageType.WEBDAV) || storageType.equals(StorageType.NEXTCLOUD)) {
      future = new CompletableFuture<>();
      downloadFileFromWebdavAccount(future, body);
    } else {
      future = initHttpClientAsyncRequest(body);
    }
    future
        .thenAccept(response -> {
          if (response.statusCode() == HttpStatus.OK) {
            routingContext
                .response()
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream");
            // not sure if we need versionId and size but just in case if required
            if (body.containsKey(Field.VERSION_ID.getName())) {
              routingContext
                  .response()
                  .putHeader(
                      Field.VERSION_ID.getName(), body.getString(Field.VERSION_ID.getName()));
            }
            if (body.containsKey(Field.FILE_SIZE.getName())) {
              String fileSize = String.valueOf(body.getLong(Field.FILE_SIZE.getName()));
              routingContext.response().putHeader(Field.FILE_SIZE.getName(), fileSize);
            }
            routingContext.response().setChunked(true);
            routingContext.response().setStatusCode(statusCode);
            InputStream stream = response.body();
            byte[] bytes = new byte[4096];
            int bytesRead;
            try {
              while ((bytesRead = stream.read(bytes)) != -1) {
                Buffer buffer = Buffer.buffer();
                buffer.appendBytes(bytes, 0, bytesRead);
                routingContext.response().write(buffer);
              }
              stream.close();
              routingContext.response().end();
            } catch (IOException ex) {
              log.error("[DOWNLOAD STREAM] Could not stream the data - " + ex);
              routingContext
                  .response()
                  .setStatusCode(HttpStatusCodes.BAD_REQUEST)
                  .end(new JsonObject()
                      .put(
                          Field.MESSAGE.getName(),
                          Objects.nonNull(ex.getCause()) ? ex.getCause().toString() : errorMessage)
                      .encodePrettily());
            }
          } else {
            // Graph API version related issue, try to run on alternate url
            if (storageType.equals(StorageType.ONEDRIVE)
                || storageType.equals(StorageType.ONEDRIVEBUSINESS)
                || storageType.equals(StorageType.SHAREPOINT)) {
              String alternateUrl = null;
              String downloadUrl = body.getString(Field.DOWNLOAD_URL.getName());
              if (downloadUrl.contains("versions") && downloadUrl.contains("content")) {
                String preUrl = downloadUrl.substring(0, downloadUrl.indexOf("versions"));
                String postUrl = downloadUrl.substring(downloadUrl.indexOf("content"));
                alternateUrl = preUrl + postUrl;
              }
              if (Utils.isStringNotNullOrEmpty(alternateUrl)) {
                body.put(Field.DOWNLOAD_URL.getName(), alternateUrl);
                downloadToStream(routingContext, body, errorId, org.apache.http.HttpStatus.SC_OK);
                return;
              }
            }

            String logDetails;
            try {
              logDetails = new String(response.body().readAllBytes());
            } catch (IOException e) {
              logDetails = response.toString();
            }

            log.error(String.format(
                "[DOWNLOAD STREAM] Http request failed with status code: %d | response: %s",
                response.statusCode(), logDetails));

            routingContext
                .response()
                .setStatusCode(response.statusCode())
                .end(new JsonObject()
                    .put(
                        Field.MESSAGE.getName(),
                        errorMessage + ": "
                            + Utils.getLocalizedString(
                                RequestUtils.getLocale(routingContext), "SomethingWentWrong"))
                    .encodePrettily());
          }
        })
        .exceptionally(ex -> {
          log.error("[DOWNLOAD STREAM] Http request failed - " + ex);
          routingContext
              .response()
              .setStatusCode(HttpStatusCodes.BAD_REQUEST)
              .end(new JsonObject()
                  .put(
                      Field.MESSAGE.getName(),
                      Objects.nonNull(ex.getCause()) ? ex.getCause().toString() : errorMessage)
                  .encodePrettily());
          return null;
        });
  }

  /**
   * Download stream from the url and also update the file download status in case of large file
   *
   * @param body   - request body
   * @return InputStream
   */
  public static InputStream getStreamFromDownloadUrl(JsonObject body)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = initHttpClientRequest(body);
    log.info("[DOWNLOAD STREAM] Response - " + response.statusCode() + ", Context body - " + body);
    if (response.statusCode() == HttpStatus.OK) {
      if (body.getBoolean("setFileDownloadedAt", true)) {
        S3PresignedUploadRequests.setFileDownloadedAt(
            body.getString(Field.PRESIGNED_UPLOAD_ID.getName()),
            body.getString(Field.USER_ID.getName()));
      }
      List<String> contentLength = response.headers().map().get("content-length");
      if (Objects.nonNull(contentLength) && !contentLength.isEmpty()) {
        body.put(Field.FILE_SIZE.getName(), Integer.valueOf(contentLength.get(0)));
      }
      return response.body();
    } else {
      log.error("[DOWNLOAD STREAM] Http request failed - " + response.statusCode()
          + " | response - " + response);
      throw new RuntimeException(Utils.getLocalizedString(
          body.getString(Field.LOCALE.getName()), "SomethingWentWrongForFiles"));
    }
  }

  private static HttpRequest buildRequest(JsonObject body) {
    String accessToken = body.getString(Field.ACCESS_TOKEN.getName());
    HttpRequest.Builder builder = HttpRequest.newBuilder().GET();
    builder.uri(URI.create(body.getString(Field.DOWNLOAD_URL.getName())));
    if (Utils.isStringNotNullOrEmpty(accessToken)) {
      builder.setHeader("Authorization", "Bearer " + accessToken);
    }
    return builder.build();
  }

  private static HttpResponse<InputStream> initHttpClientRequest(JsonObject body)
      throws IOException, InterruptedException {
    HttpRequest request = buildRequest(body);
    return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
  }

  private static CompletableFuture<HttpResponse<InputStream>> initHttpClientAsyncRequest(
      JsonObject body) {
    HttpRequest request = buildRequest(body);
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
  }

  private static void downloadFileFromWebdavAccount(
      CompletableFuture<HttpResponse<InputStream>> future, JsonObject body) {
    String accessToken = body.getString(Field.ACCESS_TOKEN.getName());
    String downloadUrl = body.getString(Field.DOWNLOAD_URL.getName());
    if (Utils.isStringNotNullOrEmpty(accessToken)) {
      String decodedToken = new String(Base64.getDecoder().decode(accessToken));
      String[] credentials = decodedToken.split(":");
      if (credentials.length == 2) {
        String username = credentials[0];
        String password = credentials[1];
        Sardine sardine = new SardineImpl(username, password);
        HttpRequest request =
            HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
        try {
          InputStream stream = sardine.get(downloadUrl);
          CustomHttpResponse response = new CustomHttpResponse(200, stream, request);
          future.complete(response);
          return;
        } catch (IOException e) {
          future.completeExceptionally(
              new Throwable("Something went wrong in downloading file from webdav account - " + e));
        }
      }
    }
    future.completeExceptionally(
        new Throwable("Invalid access token to download the file from webdav account - "
            + body.encodePrettily()));
  }

  static class CustomHttpResponse implements HttpResponse<InputStream> {
    private final int statusCode;
    private final InputStream body;
    private final HttpRequest request;

    public CustomHttpResponse(int statusCode, InputStream body, HttpRequest request) {
      this.statusCode = statusCode;
      this.body = body;
      this.request = request;
    }

    @Override
    public int statusCode() {
      return statusCode;
    }

    @Override
    public HttpRequest request() {
      return request;
    }

    @Override
    public Optional<HttpResponse<InputStream>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public java.net.http.HttpHeaders headers() {
      return java.net.http.HttpHeaders.of(new HashMap<>(), (k, v) -> true);
    }

    @Override
    public InputStream body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return request.uri();
    }

    @Override
    public HttpClient.Version version() {
      return null;
    }
  }
}
