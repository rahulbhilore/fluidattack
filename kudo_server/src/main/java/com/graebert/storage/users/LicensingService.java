package com.graebert.storage.users;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import kong.unirest.GetRequest;
import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class LicensingService {
  @NonNls
  public static final String X_AUTH_TOKEN = "X-Auth-Token";

  @NonNls
  private static final String X_VERIFY_KUDO_TOKEN = "X-Verify-Kudo";

  private static final Logger logger = LogManager.getRootLogger();
  private static String url;
  private static String apiToken;
  private static String syncToken;
  private static String xKudoSecret;

  private static void logRequest(HttpRequest<?> request, HttpResponse<?> response) {
    logger.info(String.format(
        "[LS_REQUEST] %s %s Headers: %s Body: %s Status: %d Response body: %s",
        request.getHttpMethod().toString(),
        request.getUrl(),
        request.getHeaders().toString(),
        request.getBody().toString(),
        response.getStatus(),
        response.getBody()));
  }

  private static void logRequestError(HttpRequest<?> request, Exception exception) {
    logger.error(
        String.format(
            "[LS_REQUEST] %s %s Headers: %s Body: %s",
            request.getHttpMethod().toString(),
            request.getUrl(),
            request.getHeaders().toString(),
            request.getBody().toString()),
        exception);
  }

  public static void init(String url, String apiToken, String syncToken, String xKudoSecret) {
    LicensingService.url = url;
    LicensingService.apiToken = apiToken;
    LicensingService.syncToken = syncToken;
    LicensingService.xKudoSecret = xKudoSecret;
  }

  public static HttpResponse<String> hasKudo(String userEmail, String userAgent, String traceId) {
    GetRequest haskudoRequest = AWSXRayUnirest.get(
        LicensingService.url + "permission/has-kudo?apiKey=" + LicensingService.apiToken + "&email="
            + URLEncoder.encode(userEmail, StandardCharsets.UTF_8),
        "Licensing.hasKudo");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      haskudoRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      haskudoRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    if (Utils.isStringNotNullOrEmpty(LicensingService.syncToken)) {
      haskudoRequest.header("X-Sync-Token", LicensingService.syncToken);
    }
    try {
      HttpResponse<String> response = haskudoRequest.asString();
      logRequest(haskudoRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(haskudoRequest, unirestException);
      return null;
    }
  }

  /**
   * Verifies that user has valid Kudo permission
   *
   * @param userEmail email of the user
   * @param userAgent user agent for logs
   * @param traceId   Xray trace Id
   * @return Response received from LS
   */
  public static HttpResponse<String> hasAnyKudo(
      String userEmail, String userAgent, String traceId) {
    GetRequest hasAnyKudoPermissionRequest = AWSXRayUnirest.get(
        LicensingService.url + "permission/has-any-kudo?apiKey=" + LicensingService.apiToken
            + "&email=" + URLEncoder.encode(userEmail, StandardCharsets.UTF_8),
        "Licensing.hasKudo");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      hasAnyKudoPermissionRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      hasAnyKudoPermissionRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    if (Utils.isStringNotNullOrEmpty(LicensingService.syncToken)) {
      hasAnyKudoPermissionRequest.header("X-Sync-Token", LicensingService.syncToken);
    }
    try {
      HttpResponse<String> response = hasAnyKudoPermissionRequest.asString();
      logRequest(hasAnyKudoPermissionRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(hasAnyKudoPermissionRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> verifySSOState(
      String stateFromSSO, String userAgent, String traceId) {
    GetRequest ssoLoginRequest = AWSXRayUnirest.get(
            LicensingService.url + "user/verify-state", "Licensing.hasKudo")
        .queryString(Field.STATE.getName(), stateFromSSO);
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      ssoLoginRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      ssoLoginRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    if (Utils.isStringNotNullOrEmpty(LicensingService.syncToken)) {
      ssoLoginRequest.header("X-Sync-Token", LicensingService.syncToken);
    }
    try {
      HttpResponse<String> response = ssoLoginRequest.asString();
      logRequest(ssoLoginRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(ssoLoginRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> hasKudo(
      String jwt, String traceId, String deviceId, String token, String userAgent) {
    String checkURL =
        LicensingService.url + "permission/has-kudo?deviceId=" + deviceId + "&token=" + token;
    if (Utils.isStringNotNullOrEmpty(jwt)) {
      checkURL = LicensingService.url + "permission/has-kudo";
    }
    GetRequest haskudoRequest = AWSXRayUnirest.get(checkURL, "Licensing.hasKudo");

    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      haskudoRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      haskudoRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    if (Utils.isStringNotNullOrEmpty(jwt)) {
      haskudoRequest.header(X_AUTH_TOKEN, jwt);
    }
    if (Utils.isStringNotNullOrEmpty(syncToken)) {
      haskudoRequest.header("X-Sync-Token", syncToken);
    }
    try {
      HttpResponse<String> response = haskudoRequest.asString();
      logRequest(haskudoRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(haskudoRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> hasCommanderAndTouch(
      String jwt, String traceId, String userAgent) {
    String checkUrl = LicensingService.url + "permission/has-commander&touch";
    if (!Utils.isStringNotNullOrEmpty(jwt)) {
      throw new UnsupportedOperationException("Cannot be called without JWT");
    }
    GetRequest hasCommanderAndTouchRequest =
        AWSXRayUnirest.get(checkUrl, "Licensing.hasCommanderAndTouch");

    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      hasCommanderAndTouchRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      hasCommanderAndTouchRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    if (Utils.isStringNotNullOrEmpty(jwt)) {
      hasCommanderAndTouchRequest.header(X_AUTH_TOKEN, jwt);
    }
    if (Utils.isStringNotNullOrEmpty(syncToken)) {
      hasCommanderAndTouchRequest.header("X-Sync-Token", syncToken);
    }
    try {
      HttpResponse<String> response = hasCommanderAndTouchRequest.asString();
      logRequest(hasCommanderAndTouchRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(hasCommanderAndTouchRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> getCapability(
      String name, String jwt, String deviceId, String token, String userAgent) {
    if (!Utils.isStringNotNullOrEmpty(name)) {
      return null;
    }
    String checkURL = LicensingService.url + "capability/check-availability?token=" + token
        + "&deviceId=" + deviceId + "&name=" + name;
    if (jwt != null) {
      checkURL = LicensingService.url + "capability/check-availability?name=" + name;
    }

    GetRequest capabilityRequest = AWSXRayUnirest.get(checkURL, "Licensing.capability");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      capabilityRequest.header("x-original-ua", userAgent);
    }
    if (jwt != null) {
      capabilityRequest.header(X_AUTH_TOKEN, jwt);
    }
    try {
      HttpResponse<String> response = capabilityRequest.asString();
      logRequest(capabilityRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(capabilityRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> login(
      String email, String password, boolean pwdEncrypted, String userAgent, String traceId) {
    JsonObject requestBody = new JsonObject()
        .put(Field.EMAIL.getName(), email)
        .put(Field.PASSWORD.getName(), password)
        .put("encoded", pwdEncrypted);
    HttpRequestWithBody loginRequest = AWSXRayUnirest.post(
            LicensingService.url + "user/login", "Licensing.login")
        .header("Content-type", "application/json");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      loginRequest.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      loginRequest.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }

    try {
      HttpResponse<String> response = loginRequest.body(requestBody.encode()).asString();
      logRequest(loginRequest, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(loginRequest, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> getOwner(
      String deviceId, String token, String userAgent, String traceId) {
    GetRequest request = AWSXRayUnirest.get(
        LicensingService.url + "device/get-owner?deviceId=" + deviceId + "&token=" + token,
        "Licensing.device");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      request.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      request.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    try {
      HttpResponse<String> response = request.asString();
      logRequest(request, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(request, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> getUserCapabilities(
      String graebertId, String jwt, String userAgent, String traceId) {
    GetRequest request = AWSXRayUnirest.get(
            LicensingService.url + "capability/get-for-kudo-user?id=" + graebertId,
            "Licensing.getUserCapabilities")
        .header("Content-type", "application/json");
    if (Utils.isStringNotNullOrEmpty(userAgent)) {
      request.header("x-original-ua", userAgent);
    }
    if (Utils.isStringNotNullOrEmpty(traceId)) {
      request.header("X-Amzn-Trace-Id", "Root=" + traceId);
    }
    request.header(X_AUTH_TOKEN, jwt).header(X_VERIFY_KUDO_TOKEN, xKudoSecret);

    try {
      HttpResponse<String> response = request.asString();
      logRequest(request, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(request, unirestException);
      return null;
    }
  }

  private static JsonObject getActivationBody(String revision, Item session) {
    Long timezoneOffset = null;
    if (session.hasAttribute(Field.PREFERENCES.getName())) {
      Map<String, Object> preferences = session.getMap(Field.PREFERENCES.getName());
      if (preferences.containsKey("timezoneOffset")) {
        timezoneOffset = ((BigDecimal) preferences.get("timezoneOffset")).longValue() / 1000;
      }
    }
    JsonObject requestBody = new JsonObject()
        // any
        .put(Field.CODE.getName(), 5)
        // sessionId
        .put("deviceId", session.getString(Field.SK.getName()))
        // KUDO_sessionId
        // DK: the KUDO_ prefix was requested by Jitendra on 21/03/24
        .put("deviceName", "KUDO_" + session.getString(Field.SK.getName()))
        // constant
        .put("operatingSystem", "All desktop OS")
        // Unique Kudo Id
        .put("gde", 970)
        // constant
        .put("productVersion", "13.0")
        // current revision
        .put("build", revision);
    if (timezoneOffset != null) {
      requestBody.put("timezone", timezoneOffset);
    }
    return requestBody;
  }

  public static HttpResponse<String> activate(String revision, Item session, String jwt) {
    JsonObject requestBody = LicensingService.getActivationBody(revision, session);
    HttpRequestWithBody request = AWSXRayUnirest.post(
            LicensingService.url + "/activation/activate", "Licensing.activate")
        .header("Content-type", "application/json")
        .header(X_AUTH_TOKEN, jwt)
        .header(X_VERIFY_KUDO_TOKEN, xKudoSecret);

    try {
      HttpResponse<String> response = request.body(requestBody.encode()).asString();
      logRequest(request, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(request, unirestException);
      return null;
    }
  }

  public static HttpResponse<String> unbind(
      String revision, Item session, String jwt, String token) {
    JsonObject requestBody = LicensingService.getActivationBody(revision, session);
    HttpRequestWithBody request = AWSXRayUnirest.post(
            LicensingService.url + "/activation/unbind", "Licensing.unbind")
        .header("Content-type", "application/json");
    if (Utils.isStringNotNullOrEmpty(jwt)) {
      request.header(X_AUTH_TOKEN, jwt).header(X_VERIFY_KUDO_TOKEN, xKudoSecret);
    } else if (Utils.isStringNotNullOrEmpty(token)) {
      requestBody.put(Field.TOKEN.getName(), token);
    }

    try {
      HttpResponse<String> response = request.body(requestBody.encode()).asString();
      logRequest(request, response);
      return response;
    } catch (Exception unirestException) {
      logRequestError(request, unirestException);
      return null;
    }
  }
}
