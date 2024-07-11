package com.graebert.storage.util;

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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import kong.unirest.HttpStatus;
import org.apache.http.HttpHeaders;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StreamHelper {
  private static final Logger log = LogManager.getRootLogger();

  public static void downloadToStream(
      RoutingContext routingContext,
      HttpClient client,
      JsonObject body,
      String errorId,
      int statusCode) {
    String errorMessage = Utils.getLocalizedString(RequestUtils.getLocale(routingContext), errorId);
    CompletableFuture<HttpResponse<InputStream>> future = initHttpClientAsyncRequest(client, body);
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
            String storageType = body.getString(Field.STORAGE_TYPE.getName());
            // Graph API version related issue, try to run on alternate url
            if (storageType.equals(StorageType.ONEDRIVE.name())
                || storageType.equals(StorageType.ONEDRIVEBUSINESS.name())
                || storageType.equals(StorageType.SHAREPOINT.name())) {
              String alternateUrl = null;
              String downloadUrl = body.getString(Field.DOWNLOAD_URL.getName());
              if (downloadUrl.contains("versions") && downloadUrl.contains("content")) {
                String preUrl = downloadUrl.substring(0, downloadUrl.indexOf("versions"));
                String postUrl = downloadUrl.substring(downloadUrl.indexOf("content"));
                alternateUrl = preUrl + postUrl;
              }
              if (Utils.isStringNotNullOrEmpty(alternateUrl)) {
                body.put(Field.DOWNLOAD_URL.getName(), alternateUrl);
                downloadToStream(
                    routingContext, client, body, errorId, org.apache.http.HttpStatus.SC_OK);
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
   * @param client - to handle GET request
   * @param body   - request body
   * @return InputStream
   */
  public static InputStream getStreamFromDownloadUrl(HttpClient client, JsonObject body)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = initHttpClientRequest(client, body);
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
      boolean isBearerToken = body.getBoolean("isBearerToken", true);
      builder.setHeader("Authorization", ((isBearerToken) ? "Bearer " : "Basic ") + accessToken);
    }
    return builder.build();
  }

  private static HttpResponse<InputStream> initHttpClientRequest(HttpClient client, JsonObject body)
      throws IOException, InterruptedException {
    HttpRequest request = buildRequest(body);
    return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
  }

  private static CompletableFuture<HttpResponse<InputStream>> initHttpClientAsyncRequest(
      HttpClient client, JsonObject body) {
    HttpRequest request = buildRequest(body);
    return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
  }
}
