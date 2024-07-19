package com.graebert.storage.util;

import com.google.api.client.util.Lists;
import com.google.common.collect.Iterables;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CorsConfigOptions {

  private static final Set<HttpMethod> allowedMethods = new HashSet<>(Arrays.asList(
      HttpMethod.DELETE, HttpMethod.GET, HttpMethod.OPTIONS, HttpMethod.POST, HttpMethod.PUT));

  private static final List<String> userDefinedAllowedHeaders = Arrays.asList(
      Field.SESSION_ID.getName(),
      Field.FOLDER_ID.getName(),
      "Authenticate",
      Field.QUERY.getName(),
      Field.STORAGE_TYPE.getName(),
      Field.PAGE_TOKEN.getName(),
      Field.FULL.getName(),
      Field.LOCALE.getName(),
      Field.TOKEN.getName(),
      "trash",
      "googleTemporaryCode",
      "googleFluorineToken",
      "new",
      "confirmed",
      Field.FILE_FILTER.getName(),
      Field.IS_MY_SESSION.getName(),
      Field.PATTERN.getName(),
      Field.EXPORT.getName(),
      Field.X_SESSION_ID.getName(),
      "downgrade",
      "open",
      "SAMLResponse",
      "endtime",
      Field.PASSWORD.getName(),
      Field.APPLICANT_X_SESSION_ID.getName(),
      Field.REQUEST_X_SESSION_ID.getName(),
      "resetpassword",
      "temp",
      Field.FILE_ID.getName(),
      Field.TEMPLATE_TYPE.getName(),
      Field.ID.getName(),
      "ownertype",
      Field.OWNER_ID.getName(),
      "format",
      Field.DOWNLOAD_TOKEN.getName(),
      Field.DESCRIPTION.getName(),
      Field.NAME.getName(),
      "scope",
      Field.EXTERNAL_ID.getName(),
      "partToDownload",
      "updateUsage",
      "begin",
      Field.UPLOAD_REQUEST_ID.getName(),
      "objecttype",
      Field.PRESIGNED_UPLOAD_ID.getName(),
      Field.UPLOAD_TOKEN.getName(),
      Field.RETURN_DOWNLOAD_URL.getName());

  private static final List<String> predefinedAllowedHeaders = Arrays.asList(
      HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS.toString(),
      HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN.toString(),
      HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS.toString(),
      HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS.toString(),
      HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN.toString(),
      HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD.toString(),
      HttpHeaders.ORIGIN.toString(),
      HttpHeaders.CONTENT_TYPE.toString(),
      HttpHeaders.AUTHORIZATION.toString(),
      HttpHeaders.CONTENT_LENGTH.toString(),
      HttpHeaders.ACCEPT_RANGES.toString());

  public static Set<HttpMethod> getAllowedMethods() {
    return allowedMethods;
  }

  public static Set<String> getAllowedHeaders() {
    return new HashSet<>(
        Lists.newArrayList(Iterables.concat(predefinedAllowedHeaders, userDefinedAllowedHeaders)));
  }
}
