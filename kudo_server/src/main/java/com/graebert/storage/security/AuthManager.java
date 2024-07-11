package com.graebert.storage.security;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.ListTablesResult;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.api.client.util.Lists;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.integration.Saml;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.stats.logs.session.SessionLog;
import com.graebert.storage.stats.logs.session.SessionTypes;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.LicensingSession;
import com.graebert.storage.storage.Mentions;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.StorageDAO;
import com.graebert.storage.storage.TempData;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.UsersList;
import com.graebert.storage.users.IntercomConnection;
import com.graebert.storage.users.LicensingService;
import com.graebert.storage.users.UsersVerticle;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import com.onelogin.saml2.authn.SamlResponse;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.RoutingContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TimeZone;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import kong.unirest.UnirestException;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import ua_parser.Parser;

public class AuthManager extends DynamoBusModBase {
  @NonNls
  public static final String address = "authmanager";

  @NonNls
  public static final String adminId = "admin-id";

  @NonNls
  public static final String realm = "fluorine.de";
  // uses in ha1, shouldn't be changed without recalculating hashes!
  public static final String NON_EXISTENT_ORG = "NON_EXISTENT_ORG";
  private static final OperationGroup operationGroup = OperationGroup.AUTH_MANAGER;

  @NonNls
  private static final String ARES_COMMANDER = "ARES Commander";

  @NonNls
  private static final String ARES_TOUCH = "ARES Touch";

  @NonNls
  private static final String RADON_TOUCH = "Radon Touch";

  @NonNls
  private static final String MOBI = "Mobi";

  @NonNls
  private static final String OTHER = "Other";

  @NonNls
  private static final String admin = "admin";

  @NonNls
  private static final String adminSessionId = "admin-session-id";

  private static final Random random = new Random(100000);
  public static int maxUserSessions;

  public AuthManager() {}

  @NonNls
  public static String getAuthHeader() {
    String fmtDate = new SimpleDateFormat("yyyy:MM:dd:hh:mm:ss.SSS").format(new Date());
    int randomInt = random.nextInt();
    final String nonce = EncryptHelper.md5Hex(fmtDate + randomInt);
    TempData.saveNonce(nonce);
    String opaque = EncryptHelper.md5Hex(realm + nonce);
    return "Digest realm=\"" + realm + "\"," + "nonce=\"" + nonce + "\"," + "opaque=\"" + opaque
        + "\"";
  }

  private static String getHtml(String filename) {
    InputStream is = AuthManager.class.getResourceAsStream(filename);
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();

    String line;
    try {
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
    } catch (IOException ignore) {
      return emptyString;
    } finally {
      try {
        br.close();
      } catch (Exception ignore) {
      }
    }
    return sb.toString();
  }

  public static JsonObject getAuthData(RoutingContext routingContext) {
    User currentUser = routingContext.user();
    return new JsonObject()
        .put(
            Field.SESSION_ID.getName(),
            currentUser.principal().getString(Field.SESSION_ID.getName()))
        .put(Field.USER.getName(), currentUser.attributes().getString(Field.USER.getName()))
        .put("session", currentUser.attributes().getString("session"))
        .put("ip", routingContext.request().remoteAddress().host())
        .put(Field.USER_AGENT.getName(), routingContext.request().getHeader("User-Agent"))
        .put("new", routingContext.request().getHeader("new"));
  }

  @Override
  public void start() throws Exception {
    super.start();
    maxUserSessions = config.getProperties().getMaxUserSessions();

    eb.consumer(address + ".innerLogin", this::doInnerLogin);
    eb.consumer(address + ".additionalAuth", this::doAdditionalAuth);
    eb.consumer(address + ".logout", this::doLogout);
    eb.consumer(address + ".killSession", this::doKillSession);
    eb.consumer(address + ".digest", this::doDigestAuth);
    eb.consumer(address + ".foreignLogin", this::doForeignLogin);
    eb.consumer(address + ".graebertLogin", this::doGraebertLogin);
    eb.consumer(address + ".ssoLogin", this::doSSOLogin);
    eb.consumer(address + ".samlLogin", this::doSamlLogin);
    eb.consumer(address + ".adminCreateUser", this::doAdminCreateUser);
    eb.consumer(address + ".updateSessionsStorage", this::updateSessionsStorage);
    eb.consumer(address + ".updateStorageAccess", this::doAdminUpdateStorageAccess);
    eb.consumer(address + ".getAdminDisabledStorages", this::doGetAdminDisabledStorages);
    eb.consumer(address + ".saveSamlResponse", this::doSaveSamlResponse);
    eb.consumer(address + ".cognitoLogin", this::doVerifyCognito);
    eb.consumer(address + ".getNonce", this::doGetNonce);
    eb.consumer(address + ".getLongNonce", this::doGetLongNonce);

    eb.consumer(address + ".healthCheckup", this::doHealthCheckup);
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-auth");
  }

  private JSONObject convertToJson(HttpResponse<String> response) {
    JSONObject body = new JSONObject();
    try {
      body = new JSONObject(response.getBody());
    } catch (Exception e) {
      log.error("Exception on parsing LS login response " + response.getBody(), e);
    }
    return body;
  }

  private boolean sendLoginErrorMessage(
      Message<JsonObject> message, Entity segment, HttpResponse<String> loginRequest) {
    int status = loginRequest.getStatus();
    JSONObject body = convertToJson(loginRequest);
    if (status != HttpStatus.OK) {
      // allow expired Licence to log in
      if (status == HttpStatus.FORBIDDEN || status == HttpStatus.CONFLICT) {
        String userAgent = message.body().getString(Field.USER_AGENT.getName());
        ClientType clientType = getClientType(userAgent);
        if (ClientType.BROWSER.equals(clientType) || ClientType.BROWSER_MOBILE.equals(clientType)) {
          return false;
        }
        // error when license has expired - FL16. Message the same as FL2
        int statusCode = userAgent.contains("ARES Commander") && userAgent.contains("17.")
            ? HttpStatus.PRECONDITION_FAILED
            : HttpStatus.UNAUTHORIZED; // to prevent continuous login requests from AC 2017
        sendError(
            segment,
            message,
            new JsonObject()
                .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL2"))
                .put(Field.ERROR_ID.getName(), "FL16"),
            statusCode,
            LogLevel.INFO,
            "FL16");
        return true;
      }
      // allow login if LS server is not responding or status code is 5xx
      if (String.valueOf(loginRequest.getStatus()).startsWith("5")) {
        String errorId =
            body.has(Field.ERROR_ID.getName()) ? body.getString(Field.ERROR_ID.getName()) : "LS1";
        String errorMessage = body.has(Field.MESSAGE.getName())
            ? body.getString(Field.MESSAGE.getName())
            : Utils.getLocalizedString(message, "LS1");
        log.error("[Licensing Server] - errorId : " + errorId + ", status : "
            + loginRequest.getStatusText() + ", message : " + errorMessage);
        return false;
      }
      String errorMessage = Utils.getLocalizedString(message, "FL2");
      String errorId = "FL2";
      try {
        errorMessage = body.getString(Field.MESSAGE.getName());
        if (errorMessage.isEmpty()) {
          errorMessage = Utils.getLocalizedString(message, "FL2");
        }
        errorId = body.getString(Field.ERROR_ID.getName());
        if (errorId.isEmpty()) {
          errorId = "FL2";
        }
      } catch (Exception ex) {
        log.error("LS loginErrorMessage handler exception:" + ex.getMessage());
      }
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), errorMessage)
              .put(Field.ERROR_ID.getName(), errorId),
          status / 100 < 4 ? HttpStatus.INTERNAL_SERVER_ERROR : status,
          status == HttpStatus.UNAUTHORIZED ? LogLevel.INFO : LogLevel.ERROR,
          errorId);
      return true;
    } else {
      if (body.isEmpty()) {
        sendError(
            segment,
            message,
            new JsonObject()
                .put(Field.MESSAGE.getName(), "Invalid response from licensing service")
                .put(Field.ERROR_ID.getName(), "FATAL"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            LogLevel.ERROR,
            "FATAL");
        return true;
      }
    }
    return false;
  }

  private void doVerifyCognito(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    String traceId = message.body().getString(Field.TRACE_ID.getName());
    JsonObject body = message.body();
    String idToken = body.getString("idToken");
    //        String accessToken = body.getString("accessToken");
    DecodedJWT decoded;
    try {
      decoded = JWT.decode(idToken);
    } catch (Exception e) {
      e.printStackTrace();
      sendError(segment, message, "invalid jwt", HttpStatus.BAD_REQUEST, e);
      return;
    }
    String kid = decoded.getKeyId();
    // todo check if we already have JWKs
    String jwksUrl =
        "https://cognito-idp.{region}.amazonaws.com/{userPoolId}/.well-known/jwks.json";
    jwksUrl = jwksUrl.replace("{region}", config.getProperties().getCognitoRegion());
    jwksUrl = jwksUrl.replace("{userPoolId}", config.getProperties().getCognitoUserPoolId());
    JsonArray keys;
    try {
      String jwks =
          AWSXRayUnirest.get(jwksUrl, "authManager.doVerifyCognito").asString().getBody();
      keys = new JsonObject(jwks).getJsonArray("keys");
    } catch (UnirestException e) {
      e.printStackTrace();
      sendError(segment, message, "unable to retrieve jwks", HttpStatus.BAD_REQUEST, e);
      return;
    }
    if (keys == null) {
      sendError(segment, message, "unable to retrieve jwks", HttpStatus.BAD_REQUEST);
      return;
    }
    JsonObject jwkObj = null;
    for (Object o : keys) {
      if (((JsonObject) o).getString("kid").equals(kid)) {
        jwkObj = (JsonObject) o;
        break;
      }
    }
    if (jwkObj == null) {
      sendError(segment, message, "unknown kid", HttpStatus.BAD_REQUEST);
      return;
    }

    RSAPublicKey publicKey;
    //        RSAPrivateKey privateKey = null;
    try {
      publicKey = (RSAPublicKey) EncryptHelper.createRSA(jwkObj, true);
      //            privateKey = (RSAPrivateKey) EncryptHelper.createRSA(jwkObj, false);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
      e.printStackTrace();
      sendError(segment, message, "cannot get keys", HttpStatus.BAD_REQUEST, e);
      return;
    }
    if (publicKey == null /*|| privateKey == null*/) {
      sendError(segment, message, "cannot get keys", HttpStatus.BAD_REQUEST);
      return;
    }
    String iss = "https://cognito-idp.{region}.amazonaws.com/{userPoolId}";
    iss = iss.replace("{region}", config.getProperties().getCognitoRegion());
    iss = iss.replace("{userPoolId}", config.getProperties().getCognitoUserPoolId());
    try {
      Algorithm algorithm = Algorithm.RSA256(publicKey, null); // privateKey);
      JWTVerifier verifier = JWT.require(algorithm)
          .withIssuer(iss)
          .withAudience(config.getProperties().getCognitoAppClientId())
          .build();
      // check expiration, audience and user pool id (iss)
      verifier.verify(idToken);
    } catch (JWTVerificationException e) {
      e.printStackTrace();
      sendError(
          segment,
          message,
          "Invalid signature/claims: " + e.getMessage(),
          HttpStatus.BAD_REQUEST,
          e);
      return;
    }

    // todo check token_use
    /*
    If you are only accepting the access token in your web APIs, its value must be access.
    If you are only using the ID token, its value must be id.
    If you are using both ID and access tokens, the token_use claim must be either id or access.
             */
    // can be trusted
    Map<String, Claim> payloadClaims = decoded.getClaims();
    // todo check entitlement and so on
    String
        firstName =
            payloadClaims.containsKey("given_name")
                ? payloadClaims.get("given_name").asString()
                : emptyString,
        lastName =
            payloadClaims.containsKey("family_name")
                ? payloadClaims.get("family_name").asString()
                : emptyString,
        email =
            payloadClaims.containsKey(Field.EMAIL.getName())
                ? payloadClaims.get(Field.EMAIL.getName()).asString().toLowerCase()
                : emptyString;
    if (email.isEmpty()) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.MESSAGE.getName(), "email in cognito response is not provided or is empty"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    // check license
    HttpResponse<String> licenseResponse;
    try {
      licenseResponse = LicensingService.hasKudo(email, userAgent, traceId);
      // LS fallback
      if (Objects.nonNull(licenseResponse)
          && sendLoginErrorMessage(message, segment, licenseResponse)) {
        return;
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.BAD_REQUEST, e);
      return;
    }
    if (Objects.nonNull(licenseResponse)
        && !checkLicense(segment, message, licenseResponse, userAgent, firstName, lastName, null)) {
      return;
    }

    String intercomAccessToken = message.body().getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    String intercomAppId = message.body().getString(Field.INTERCOM_APP_ID.getName());

    Item user = Users.getUserByEmail(email).next();
    if (user == null) {
      eb_request(
          segment,
          UsersVerticle.address + ".createUserByForeign",
          new JsonObject()
              .put(Field.TYPE.getName(), UsersVerticle.SAML)
              .put(Field.EMAIL.getName(), email.toLowerCase())
              .put(Field.NAME.getName(), firstName)
              .put(Field.SURNAME.getName(), lastName)
              .put(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken)
              .put(Field.INTERCOM_APP_ID.getName(), intercomAppId)
              .put("timezoneOffset", 0),
          event -> {
            if (event.succeeded()) {
              if (Field.OK
                  .getName()
                  .equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
                Entity subSegment =
                    XRayManager.createSubSegment(operationGroup, segment, "createUserByForeign");
                Item newUser;
                String userId =
                    ((JsonObject) event.result().body()).getString(Field.USER_ID.getName());
                String item = ((JsonObject) event.result().body()).getString("item");
                if (item != null && !item.isEmpty()) {
                  newUser = Item.fromJSON(item);
                } else {
                  newUser = Users.getUserById(userId);
                }
                if (newUser != null) {
                  login(subSegment, message, newUser, mills);
                } else {
                  XRayManager.endSegment(subSegment);
                  sendError(
                      segment,
                      message,
                      new JsonObject()
                          .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
                          .put(Field.ERROR_ID.getName(), "FL3"),
                      HttpStatus.INTERNAL_SERVER_ERROR);
                }
              } else {
                sendError(segment, message, event.result());
              }
            } else {
              sendError(
                  segment,
                  message,
                  new JsonObject()
                      .put(Field.MESSAGE.getName(), event.cause().getLocalizedMessage()),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
    } else {
      List<String> intercomUpdate = new ArrayList<>();
      ValueMap valueMap = new ValueMap();
      if (Utils.isStringNotNullOrEmpty(intercomAccessToken)
          && !intercomAccessToken.equals(user.getString(Field.INTERCOM_ACCESS_TOKEN.getName()))) {
        user.withString(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken);
        intercomUpdate.add("set intercomAccessToken = :intercomToken");
        valueMap.withString(":intercomToken", intercomAccessToken);
      }
      if (Utils.isStringNotNullOrEmpty(intercomAppId)
          && !intercomAppId.equals(user.getString(Field.INTERCOM_APP_ID.getName()))) {
        user.withString(Field.INTERCOM_APP_ID.getName(), intercomAppId);
        if (intercomUpdate.isEmpty()) {
          intercomUpdate.add("set intercomAppId = :intercomId");
        } else {
          intercomUpdate.add("intercomAppId = :intercomId");
        }
        valueMap.withString(":intercomId", intercomAppId);
      }
      String updateExpression = String.join(",", intercomUpdate);
      if (Utils.isStringNotNullOrEmpty(updateExpression)) {
        Users.updateUser(user.getString(Field.SK.getName()), updateExpression, valueMap);
      }

      login(segment, message, user, mills);
    }
  }

  private void doSaveSamlResponse(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String samlResponse = body.getString("samlResponse");
    String token = Utils.generateUUID();
    TempData.saveSaml(token, samlResponse);
    sendOK(segment, message, new JsonObject().put("samlResponseId", token), mills);
  }

  private void doInnerLogin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (userId != null) {
      Item user = Users.getUserById(userId);
      if (user != null) {
        if (user.get(Field.APPLICANT.getName()) == null
            || !(Boolean) user.get(Field.APPLICANT.getName())) {
          if ((Boolean) user.get(Field.ENABLED.getName())) {
            login(segment, message, user, mills);
          } else {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "TheUserIsDisabled"),
                HttpStatus.FORBIDDEN);
          }
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "YourAccountHasNotBeenActivatedYet"),
              HttpStatus.LOCKED);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserDoesNotExist"),
            HttpStatus.NOT_FOUND);
      }
    }
    recordExecutionTime("login", System.currentTimeMillis() - mills);
  }

  private void login(Entity segment, Message<JsonObject> message, Item user, Long millis) {
    login(segment, message, user, null, millis);
  }

  private void login(
      Entity segment, Message<JsonObject> message, Item user, String jwt, Long millis) {
    String nameId = user.getString("nameId");
    String sessionIndex = user.getString("sessionIndex");
    if (!user.hasAttribute(Field.ENABLED.getName()) || !user.getBoolean(Field.ENABLED.getName())) {
      JsonObject response = new JsonObject()
          .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "TheUserIsDisabled"));
      if (nameId != null && sessionIndex != null) {
        response
            .put("nameId", user.getString("nameId"))
            .put("sessionIndex", user.getString(Field.SESSION_ID.getName()));
      }
      sendError(segment, message, response, HttpStatus.FORBIDDEN, "TheUserIsDisabled");
      return;
    }
    boolean shouldCheckExportCompliance = config.getProperties().getCheckExportCompliance();

    if (shouldCheckExportCompliance) {
      boolean userHadTestResult = user.hasAttribute("complianceTestResult");
      boolean userHasTestStatus = user.hasAttribute("complianceStatus");
      String complianceStatus =
          userHasTestStatus ? user.getString("complianceStatus") : "TO_BE_CHECKED";
      String complianceTestResult = checkExportCompliance(
          "",
          emptyString,
          emptyString,
          user.getString(Field.USER_EMAIL.getName()),
          segment.getTraceId().toString());

      if (!userHadTestResult
          || !user.getString("complianceTestResult").equals(complianceTestResult)) {
        // update with the latest result
        user.withString("complianceTestResult", complianceTestResult);
        Users.update(user.getString(Field.SK.getName()), user);
      }

      log.info(String.format("Compliance result %s", complianceTestResult));
      if (complianceTestResult.startsWith("FAILED")
          && (!userHasTestStatus || !complianceStatus.startsWith("OVERRIDDEN"))) {
        JsonObject response = new JsonObject()
            .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "TheUserIsDisabled"));
        if (nameId != null && sessionIndex != null) {
          response
              .put("nameId", user.getString("nameId"))
              .put("sessionIndex", user.getString(Field.SESSION_ID.getName()));
        }
        sendError(segment, message, response, HttpStatus.FORBIDDEN, "TheUserIsDisabled");
        return;
      } else {
        // this means we passed or we were overridden
        log.info(String.format("Compliance test passed with status %s", complianceStatus));
      }
    }

    final String sessionId = Utils.generateUUID();
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    String rawUserAgent = userAgent;
    String licenseType = message.body().getString(Field.LICENSE_TYPE.getName());
    /*
     * 08.08.2017
     * XENON-18003
     * Block starting user session from ARES Commander 2017 SP0 and SP1
     * */
    if (userAgent != null
        && (userAgent.startsWith("ARES Commander 2017 17.0")
            || userAgent.startsWith("ARES Commander 2017 17.1"))) {
      sendError(
          segment,
          message,
          "you are using an outdated version of Ares Commander",
          HttpStatus.PRECONDITION_FAILED,
          LogLevel.INFO);
      return;
    }
    userAgent = getClientType(userAgent).name();
    // removing the oldest session if number of user sessions exceeds limit
    Item oldestSession =
        Sessions.getUserDeviceSessionOverLimit(user.getString(Field.SK.getName()), userAgent);
    if (oldestSession != null) {
      Sessions.cleanUpSession(this, oldestSession);
      eb_send(
          segment,
          WebSocketManager.address + ".oldSessionRemoved",
          new JsonObject()
              .put(Field.USER_ID.getName(), user.getString(Field.SK.getName()))
              .put(Field.SESSION_ID.getName(), oldestSession.getString(Field.SK.getName())));
    }
    long loggedIn = GMTHelper.utcCurrentTime();

    Item session = new Item()
        .withPrimaryKey(
            Field.PK.getName(),
            Sessions.sessionField + user.getString(Field.SK.getName()),
            Field.SK.getName(),
            sessionId)
        .withLong("loggedIn", loggedIn)
        .withLong(Field.TTL.getName(), Sessions.getSessionTTL())
        .withLong("lastActivity", GMTHelper.utcCurrentTime())
        .withString(Field.USERNAME.getName(), user.getString(Field.USER_EMAIL.getName()))
        .withString(Field.DEVICE.getName(), userAgent)
        .withJSON(Field.F_STORAGE.getName(), user.getJSON(Field.F_STORAGE.getName()));
    if (user.getString("nameId") != null && user.getString("sessionIndex") != null) {
      session
          .withString("nameId", user.getString("nameId"))
          .withString("sessionIndex", user.getString("sessionIndex"));
    }
    if (rawUserAgent != null && !rawUserAgent.isEmpty()) {
      session.withString(Field.USER_AGENT.getName(), rawUserAgent);
    }
    if (jwt != null && !jwt.isEmpty()) {
      session.withString("jwt", jwt);
    }
    if (licenseType != null && !licenseType.isEmpty()) {
      session.withString(Field.LICENSE_TYPE.getName(), licenseType);
    }

    Item mergedSession = Sessions.mergeSessionWithUserInfo(session, user);
    Sessions.saveSession(sessionId, mergedSession);
    log.info("Session saved in DB for session " + sessionId + " and user "
        + user.getString(Field.SK.getName()));
    Sessions.updateDeviceSessionCount(userAgent, Sessions.SessionMode.ADDSESSION);
    // set lastLoggedIn date/time for statistics
    String finalUserAgent = userAgent;
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .run((Segment blockingSegment) -> {
          Users.setUserLoggedIn(user.getString(Users.sk), loggedIn);
          if (Utils.isStringNotNullOrEmpty(jwt)
              && message.body().containsKey(Field.TRACE_ID.getName())) {
            getAndUpdateUserCapabilities(
                blockingSegment,
                user.getString(Field.GRAEBERT_ID.getName()),
                jwt,
                finalUserAgent,
                message.body().getString(Field.TRACE_ID.getName()));
          }
          if (Utils.isStringNotNullOrEmpty(jwt)) {
            HttpResponse<String> activationResponse =
                LicensingService.activate(config.getProperties().getRevision(), mergedSession, jwt);
            // LS fallback
            if (Objects.nonNull(activationResponse)) {
              if (!activationResponse.isSuccess()) {
                log.warn(
                    "[LS_API] activate unsuccessful. Status code: " + activationResponse.getStatus()
                        + ". Session info: " + mergedSession.toJSONPretty());
              } else {
                JsonObject activationBody = new JsonObject(activationResponse.getBody());
                if (activationBody.containsKey(Field.TOKEN.getName())) {
                  String licensingToken = activationBody.getString(Field.TOKEN.getName());
                  Sessions.setLicensingToken(user.getString(Users.sk), sessionId, licensingToken);
                  LicensingSession.createLicensingSession(
                      config.getProperties().getRevision(), mergedSession, licensingToken);
                } else {
                  log.warn("[LS_API] activation body doesn't have token. Body: "
                      + activationBody.encodePrettily());
                }
              }
            }
          }
        });
    JsonObject storage = new JsonObject(user.getJSON(Field.F_STORAGE.getName()));
    String storageType = storage.getString(Field.STORAGE_TYPE.getName());
    String externalId = storage.getString(Field.ID.getName());
    JsonArray roles = new JsonArray(user.getList(Field.ROLES.getName()));

    JsonObject json = Users.userToJsonWithGoogleId(
            user,
            config.getProperties().getEnterprise(),
            config.getProperties().getInstanceOptionsAsJson(),
            config.getProperties().getDefaultUserOptionsAsJson(),
            config.getProperties().getDefaultLocale(),
            config.getProperties().getUserPreferencesAsJson(),
            config.getProperties().getDefaultCompanyOptionsAsJson())
        .put(Field.SESSION_ID.getName(), sessionId)
        .put(
            Field.USERNAME.getName(),
            user.getString(Field.F_NAME.getName()) + " " + user.getString(Field.SURNAME.getName()))
        .put(Field.USER_ID.getName(), user.getString(Field.SK.getName()))
        .put(Field.STORAGE_TYPE.getName(), storageType)
        .put(Field.EXTERNAL_ID.getName(), externalId)
        .put(Field.ROLES.getName(), roles)
        .put("expirationDate", message.body().getLong(Field.LICENSE_EXPIRATION_DATE.getName()))
        .put(Field.USER_AGENT.getName(), userAgent);
    sendOK(segment, message, json, millis);
    log.info("login successful for user " + user.getString(Field.SK.getName()));
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .run((Segment blockingSegment) -> {
          try {
            String region;
            try {
              Region currentRegion = Regions.getCurrentRegion();
              if (currentRegion != null && Utils.isStringNotNullOrEmpty(currentRegion.getName())) {
                region = currentRegion.getName();
              } else {
                region = "NA";
              }
            } catch (Exception e) {
              region = "NA";
            }

            // record session log
            SessionLog sessionLog = new SessionLog(
                user.getString(Field.SK.getName()),
                null,
                StorageType.getStorageType(storageType),
                user.getString(Field.USER_EMAIL.getName()),
                new JsonArray(user.getList(Field.ROLES.getName())),
                GMTHelper.utcCurrentTime(),
                true,
                SessionTypes.FLUORINE_SESSION,
                sessionId,
                region,
                finalUserAgent,
                rawUserAgent,
                GMTHelper.toUtcMidnightTime(loggedIn),
                loggedIn,
                loggedIn,
                Utils.isStringNotNullOrEmpty(licenseType) ? licenseType : "no_license_found",
                user.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())
                    ? user.getLong(Field.LICENSE_EXPIRATION_DATE.getName())
                    : 0L,
                !user.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())
                    || GMTHelper.utcCurrentTime()
                        > user.getLong(Field.LICENSE_EXPIRATION_DATE.getName()));
            sessionLog.sendToServer();
          } catch (Exception ex) {
            log.error(ex);
          } // tag storages in intercom
          String graebertId = user.getString(Field.GRAEBERT_ID.getName());
          String intercomAccessToken = user.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
          try {
            if (graebertId != null
                && intercomAccessToken != null
                && !intercomAccessToken.isEmpty()) {
              IntercomConnection.setToken(intercomAccessToken);
              JsonObject intercomUser = IntercomConnection.getUserInfo(graebertId);
              IntercomConnection.updateUserTags(
                  graebertId,
                  intercomUser,
                  ExternalAccounts.getAllExternalAccountsForUser(
                      user.getString(Field.SK.getName())));
            }
          } catch (Exception e) {
            log.error(
                String.format(
                    "%s error on tagging. Error: %s. Graebert ID: %s. Access token: %s",
                    IntercomConnection.logPrefix, e.getMessage(), graebertId, intercomAccessToken),
                e);
          }
          eb_send(
              segment,
              RecentFilesVerticle.address + ".validateRecentFiles",
              new JsonObject().put(Field.USER_ID.getName(), user.getString(Field.SK.getName())));

          if (!user.hasAttribute("filesLastValidated")
              || (user.getLong("filesLastValidated") + 15 * 24 * 60 * 60)
                  < GMTHelper.utcCurrentTime()) {
            // once in 15 days
            eb_send(
                segment,
                SimpleFluorine.address + ".validateFiles",
                new JsonObject().put(Field.USER_ID.getName(), user.getString(Field.SK.getName())));
          }

          // updating mentionedUsers and usersList at the time of login to maintain correct users
          // info
          Mentions.updateMentionedUsers(user.getString(Field.SK.getName()));
          UsersList.updateUserInList(user.getString(Field.SK.getName()), user);
        });
  }

  private ClientType getClientType(String userAgent) {
    ClientType clientType;
    if (userAgent == null || userAgent.isEmpty()) {
      clientType = ClientType.OTHER;
    } else if (userAgent.startsWith(ARES_COMMANDER)) {
      clientType = ClientType.COMMANDER;
    } else if (userAgent.startsWith(ARES_TOUCH)) {
      clientType = ClientType.TOUCH;
    } else if (userAgent.startsWith(RADON_TOUCH)) {
      clientType = ClientType.TOUCH;
    } else if (userAgent.contains(MOBI)) {
      clientType = ClientType.BROWSER_MOBILE;
    } else {
      if (new Parser().parse(userAgent).userAgent.family.equals(OTHER)) {
        clientType = ClientType.OTHER;
      } else {
        clientType = ClientType.BROWSER;
      }
    }
    return clientType;
  }

  private String checkExportCompliance(
      String company, String countryName, String countryCode, String email, String traceId) {
    String result;
    JsonObject requestBody = new JsonObject()
        .put(Field.COMPANY.getName(), company)
        .put("countryCode", countryCode)
        .put(Field.EMAIL.getName(), email.toLowerCase());

    HttpResponse<String> response = AWSXRayUnirest.post(
            "https://exportcompliance.app.draftsight.com", "ExportCheck")
        .header("Content-type", "application/json")
        .header("X-Amzn-Trace-Id", "Root=" + traceId)
        .body(requestBody.encode())
        .asString();
    if (response.getStatus() != HttpStatus.OK) {
      log.error("Did not get a HttpStatus.OK result");
      // todo create the user?
      result = "ERROR";
    } else {
      // strip out quotes
      result = response.getBody().replaceAll("^\"|\"$", emptyString);
    }
    result += " Country: " + countryName + " (" + countryCode + ") Company: " + company;
    return result;
  }

  private void doSamlLogin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String samlResponseId = message.body().getString("samlResponse");
    Item samlItem = TempData.getSaml(samlResponseId);
    if (samlItem == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "ThereIsNoSamlResponseWithIdInTheDb"),
              samlResponseId),
          HttpStatus.BAD_REQUEST);
      return;
    }
    String samlResponse = samlItem.getString("samlResponse");
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("deleteSaml")
        .runWithoutSegment(() -> TempData.deleteSaml(samlResponseId));
    SamlResponse response = Saml.parse(samlResponse);
    Map<String, List<String>> attributes = null;
    String nameId = null, sessionIndex = null;
    try {
      attributes = Objects.requireNonNull(response).getAttributes();
      nameId = response.getNameId();
      sessionIndex = response.getSessionIndex();
    } catch (Exception e) {
      log.error("Exception on parsing SAMLResponse: ", e);
      e.printStackTrace();
    }
    if (attributes == null || attributes.isEmpty()) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), "no attributes in saml response")
              .put("nameId", nameId)
              .put("sessionIndex", sessionIndex),
          HttpStatus.BAD_REQUEST);
      return;
    }
    boolean checkEntitled = config.getProperties().getSamlCheckEntitlement();
    String entitlement = config.getProperties().getSamlEntitlement();
    boolean checkDomain = config.getProperties().getSamlCheckDomain();
    JsonArray whitelist = config.getProperties().getSamlWhitelistAsArray();

    if (nameId == null || sessionIndex == null) {
      log.error("Couldn't get either nameId or sessionIndex from the saml response");
    }
    String firstName = emptyString, lastName = emptyString, email = null;
    String company = emptyString, countryName = emptyString, countryCode = emptyString;
    boolean entitled = !checkEntitled && !checkDomain; // will be true only if both set to false
    if (attributes.containsKey("First Name") && !attributes.get("First Name").isEmpty()) {
      firstName = attributes.get("First Name").get(0);
    }
    if (attributes.containsKey("Last Name") && !attributes.get("Last Name").isEmpty()) {
      lastName = attributes.get("Last Name").get(0);
    }
    if (attributes.containsKey("Company") && !attributes.get("Company").isEmpty()) {
      company = attributes.get("Company").get(0);
    }
    if (attributes.containsKey("CountryName") && !attributes.get("CountryName").isEmpty()) {
      countryName = attributes.get("CountryName").get(0);
    }
    if (attributes.containsKey("countryCode") && !attributes.get("countryCode").isEmpty()) {
      countryCode = attributes.get("countryCode").get(0);
    }
    if (!entitlement.isEmpty()
        && attributes.containsKey(entitlement)
        && !attributes.get(entitlement).isEmpty()) {
      entitled = "Y".equals(attributes.get(entitlement).get(0));
    }
    if (attributes.containsKey(Field.EMAIL.getName())
        && !attributes.get(Field.EMAIL.getName()).isEmpty()) {
      email = attributes.get(Field.EMAIL.getName()).get(0).toLowerCase();
    }

    if (email == null || email.isEmpty()) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(message, "EmailInSamlResponseIsNotProvidedOrIsEmpty"))
              .put("nameId", nameId)
              .put("sessionIndex", sessionIndex),
          HttpStatus.BAD_REQUEST);
      return;
    }

    if (checkDomain && !whitelist.isEmpty()) {
      for (Object domain : whitelist) {
        if (domain instanceof String) {
          if (email.toLowerCase().endsWith(((String) domain).toLowerCase())) {
            entitled = true;
          }
          if (entitled) {
            break;
          }
        }
      }
    }
    if (checkEntitled && !entitled) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(
                  Field.MESSAGE.getName(),
                  Utils.getLocalizedString(message, "TheUserIsNotEntitledToLoginViaSaml"))
              .put("nameId", nameId)
              .put("sessionIndex", sessionIndex),
          HttpStatus.FORBIDDEN);
      return;
    }

    // should we check compliance on signup for saml?

    String complianceTestResult = null;
    if (!company.isEmpty() && !countryCode.isEmpty()) {
      String traceId = message.body().getString(Field.TRACE_ID.getName());
      complianceTestResult =
          checkExportCompliance(company, countryName, countryCode, email, traceId);
    }

    Item user = Users.getUserByEmail(email).next();
    if (user == null) {
      String finalNameId = nameId;
      String finalSessionIndex = sessionIndex;
      eb_request(
          segment,
          UsersVerticle.address + ".createUserByForeign",
          new JsonObject()
              .put(Field.TYPE.getName(), UsersVerticle.SAML)
              .put(Field.EMAIL.getName(), email.toLowerCase())
              .put("complianceTestResult", complianceTestResult)
              .put(Field.NAME.getName(), firstName)
              .put(Field.SURNAME.getName(), lastName)
              .put("timezoneOffset", 0),
          event -> {
            if (event.succeeded()) {
              if (Field.OK
                  .getName()
                  .equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
                Entity subSegment =
                    XRayManager.createSubSegment(operationGroup, segment, "createUserByForeign");
                Item newUser;
                String userId =
                    ((JsonObject) event.result().body()).getString(Field.USER_ID.getName());
                String item = ((JsonObject) event.result().body()).getString("item");
                if (item != null && !item.isEmpty()) {
                  newUser = Item.fromJSON(item);
                } else {
                  newUser = Users.getUserById(userId);
                }
                if (newUser != null) {
                  if (finalNameId != null && finalSessionIndex != null) {
                    newUser
                        .withString("nameId", finalNameId)
                        .withString("sessionIndex", finalSessionIndex);
                  }
                  login(subSegment, message, newUser, mills);
                } else {
                  XRayManager.endSegment(subSegment);
                  sendError(
                      segment,
                      message,
                      new JsonObject()
                          .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
                          .put(Field.ERROR_ID.getName(), "FL3")
                          .put("nameId", finalNameId)
                          .put("sessionIndex", finalSessionIndex),
                      HttpStatus.INTERNAL_SERVER_ERROR);
                }
              } else {
                sendError(segment, message, event.result());
              }
            } else {
              sendError(
                  segment,
                  message,
                  new JsonObject()
                      .put(Field.MESSAGE.getName(), event.cause().getLocalizedMessage())
                      .put("nameId", finalNameId)
                      .put("sessionIndex", finalSessionIndex),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
    } else {
      if (nameId != null && sessionIndex != null) {
        user.withString("nameId", nameId).withString("sessionIndex", sessionIndex);
      }
      login(segment, message, user, mills);
    }
  }

  private void doAdminCreateUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();

    final String email = getRequiredString(segment, Field.EMAIL.getName(), message, jsonObject);
    String name = jsonObject.getString(Field.NAME.getName());
    String surname = jsonObject.getString(Field.SURNAME.getName());

    Iterator<Item> usersIterator = Users.getUserByEmail(email);

    if (!usersIterator.hasNext()) {
      eb_request(
          segment,
          UsersVerticle.address + ".adminCreateUser",
          new JsonObject()
              .put(Field.EMAIL.getName(), email)
              .put(Field.NAME.getName(), name)
              .put(Field.SURNAME.getName(), surname)
              .put("timezoneOffset", 0),
          event -> {
            if (event.succeeded()) {
              JsonObject userVerticleResponse = ((JsonObject) event.result().body());
              if (Field.OK
                  .getName()
                  .equals(userVerticleResponse.getString(Field.STATUS.getName()))) {
                // DO we need all of this?
                Entity subSegment =
                    XRayManager.createSubSegment(operationGroup, segment, "createUserByForeign");
                Item newUser;
                String userId = userVerticleResponse.getString(Field.USER_ID.getName());
                String item = userVerticleResponse.getString("item");
                if (item != null && !item.isEmpty()) {
                  newUser = Item.fromJSON(item);
                } else {
                  newUser = Users.getUserById(userId);
                }
                if (newUser != null) {
                  sendOK(segment, message, userVerticleResponse, mills);
                } else {
                  XRayManager.endSegment(subSegment);
                  sendError(
                      segment,
                      message,
                      new JsonObject()
                          .put(
                              Field.MESSAGE.getName(),
                              Utils.getLocalizedString(
                                  message, "CouldNotRegisterUsingForeignAccount"))
                          .put(Field.ERROR_ID.getName(), "DS1"),
                      HttpStatus.INTERNAL_SERVER_ERROR);
                }
              } else {
                sendError(segment, message, event.result());
              }
            } else {
              sendError(
                  segment,
                  message,
                  new JsonObject()
                      .put(Field.MESSAGE.getName(), event.cause().getLocalizedMessage()),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
    } else {
      // login should be a different endpoint
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), "User already exists.")
              .put(Field.ERROR_ID.getName(), "DS1"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          "DS1");
    }
  }

  private Map<String, Boolean> getCommanderAndTouchLicenses(
      String jwt, String traceId, String userAgent) {
    boolean hasCommanderLicense = false;
    boolean hasTouchLicense = false;
    try {
      HttpResponse<String> commanderAndTouchLicenses =
          LicensingService.hasCommanderAndTouch(jwt, traceId, userAgent);
      // LS fallback
      if (Objects.isNull(commanderAndTouchLicenses)) {
        hasTouchLicense = true;
        hasCommanderLicense = true;
      } else if (commanderAndTouchLicenses.isSuccess()) {
        JsonObject licensesBody = new JsonObject(commanderAndTouchLicenses.getBody());
        if (licensesBody.containsKey("ARES Touch") && licensesBody.getBoolean("ARES Touch")) {
          hasTouchLicense = true;
        }
        if (licensesBody.containsKey("ARES Commander")
            && licensesBody.getBoolean("ARES Commander")) {
          hasCommanderLicense = true;
        }
      }
    } catch (Exception ex) {
      log.error("Error on getting Commander+Touch licenses", ex);
    }
    Map<String, Boolean> licenses = new HashMap<>(2);
    licenses.put("commander", hasCommanderLicense);
    licenses.put("touch", hasTouchLicense);
    return licenses;
  }

  private void doSSOLogin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String traceId = message.body().getString(Field.TRACE_ID.getName());
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    try {
      JsonObject json = message.body();
      String stateFromSSO = json.getString(Field.STATE.getName());
      // LS fallback TODO
      // login
      HttpResponse<String> ssoLoginRequest =
          LicensingService.verifySSOState(stateFromSSO, userAgent, traceId);

      if (Objects.isNull(ssoLoginRequest)) {
        // authorize through sso? TODO
        throw new Exception("Unauthorized");
      }
      log.info("LS response for sso:" + ssoLoginRequest.getBody());

      // format login response
      JSONObject body = convertToJson(ssoLoginRequest);
      int timezoneOffset = 0;
      try {
        if (body.has("zone")) {
          JsonObject normalizedBody = new JsonObject(ssoLoginRequest.getBody());
          JsonObject zoneObject = normalizedBody.getJsonObject("zone");
          if (zoneObject != null) {
            String timezoneName = zoneObject.getString(Field.NAME.getName());
            if (timezoneName != null) {
              timezoneOffset = TimeZone.getTimeZone(timezoneName).getRawOffset();
            }
          }
        }
      } catch (Exception e) {
        // ignore
      }
      body.put("timezoneOffset", timezoneOffset);

      if (sendLoginErrorMessage(message, segment, ssoLoginRequest)) {
        return;
      }
      String jwt = checkJWT(message, segment, ssoLoginRequest);
      if (jwt == null) {
        return;
      }

      // if jwt isn't null - it'll be used for request, otherwise - deviceId and token
      HttpResponse<String> licenseResponse =
          LicensingService.hasKudo(jwt, traceId, null, null, userAgent);

      Map<String, Boolean> licenses = this.getCommanderAndTouchLicenses(jwt, traceId, userAgent);

      // if (sendLoginErrorMessage(message, segment, licenseResponse)) return;

      boolean editor = getCapability("kudo.editing", jwt, null, null, userAgent);
      boolean emailNotificationsCapability =
          getCapability("areskudo.notifications", jwt, null, null, userAgent);

      // LS fallback TODO
      if (Objects.isNull(licenseResponse)) {
        throw new Exception("Unauthorized");
      }
      finalizeLogin(
          segment,
          message,
          body,
          licenseResponse,
          jwt,
          editor,
          emailNotificationsCapability,
          licenses.get("touch"),
          licenses.get("commander"),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private boolean getCapability(
      String name, String jwt, String deviceId, String token, String userAgent) {
    HttpResponse<String> response =
        LicensingService.getCapability(name, jwt, deviceId, token, userAgent);
    if (response == null) {
      return false;
    }
    JSONObject body = Utils.isStringNotNullOrEmpty(response.getBody())
        ? convertToJson(response)
        : new JSONObject();
    log.info("LS capability request for name: " + name + "\nLS status code: " + response.getStatus()
        + "\nLS response for Get Capability:" + body.toString(2));
    return response.getStatus() == HttpStatus.OK;
  }

  private void finalizeLogin(
      Entity segment,
      Message<JsonObject> message,
      JSONObject userInfo,
      HttpResponse<String> licenseResponse,
      String jwt,
      boolean editor,
      boolean emailNotifications,
      Boolean hasTouchLicense,
      Boolean hasCommanderLicense,
      long mills) {
    String name;
    String surname;
    String locale;
    String organizationId = null;
    String organizationName;
    String country = null;
    String countryLocale = null;
    Boolean isOrgAdmin = null;
    long expirationDate;
    System.out.println("LICENSING INFO: ");
    System.out.println(userInfo.toString(2));
    System.out.println(licenseResponse.getBody());
    // get parameters from user's info
    if (userInfo.has(Field.FIRST_NAME.getName())) {
      name = userInfo.getString(Field.FIRST_NAME.getName());
    } else {
      name = emptyString;
    }
    if (userInfo.has(Field.LAST_NAME.getName())) {
      surname = userInfo.getString(Field.LAST_NAME.getName());
      if (surname.trim().isEmpty()) {
        surname = emptyString;
      }
    } else {
      surname = emptyString;
    }

    if (userInfo.has("language")
        && userInfo.get("language") != null
        && !userInfo.get("language").equals(JSONObject.NULL)) {
      locale = userInfo.getString("language");
    } else {
      locale = "en";
    }

    if (userInfo.has("country")
        && userInfo.get("country") != null
        && !userInfo.get("country").equals(JSONObject.NULL)) {
      JSONObject countryJson = userInfo.getJSONObject("country");
      if (countryJson.has("alpha2code")
          && countryJson.get("alpha2code") != null
          && !countryJson.get("alpha2code").equals(JSONObject.NULL)) {
        country = countryJson.getString("alpha2code");
      }
    }

    if (locale != null && country != null) {
      countryLocale = locale + "-" + country;
    }

    if (userInfo.has("organization")) {
      if (userInfo.get("organization") != null
          && !userInfo.get("organization").equals(JSONObject.NULL)) {
        JSONObject organization = userInfo.getJSONObject("organization");
        organizationId = String.valueOf(organization.get(Field.ID.getName()));
        organizationName = organization.getString(Field.NAME.getName());
        if (userInfo.has(Field.ROLES.getName())
            && userInfo.get(Field.ROLES.getName()) != null
            && !userInfo.get(Field.ROLES.getName()).equals(JSONObject.NULL)) {
          JSONArray roles = userInfo.getJSONArray(Field.ROLES.getName());
          for (Object o : roles) {
            if ("ORG_Admin".equals(((JSONObject) o).getString(Field.NAME.getName()))
                || "ORG_Manager".equals(((JSONObject) o).getString(Field.NAME.getName()))) {
              isOrgAdmin = true;
              break;
            }
          }
          if (isOrgAdmin == null) {
            isOrgAdmin = false;
          }
        }
        // update organization in the db
        eb_send(
            segment,
            UsersVerticle.address + ".updateCompany",
            new JsonObject()
                .put(Field.ID.getName(), organizationId)
                .put(Field.COMPANY_NAME.getName(), organizationName));
      } else {
        // organization is set to null
        // should update user's info in case he has been removed from the organization
        isOrgAdmin = false;
        organizationId = NON_EXISTENT_ORG;
      }
    }
    int timezoneOffset = 0;
    if (userInfo.has("timezoneOffset")) {
      try {
        timezoneOffset = userInfo.getInt("timezoneOffset");
      } catch (Exception e) {
        // let's retry with parsing if something goes wrong there
        timezoneOffset = Integer.parseInt(userInfo.get("timezoneOffset").toString());
      }
    }

    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    // check licenses
    if (!checkLicense(segment, message, licenseResponse, userAgent, name, surname, locale)) {
      return;
    }

    // get license and intercom data
    String licenseType;
    try {
      licenseType = message.body().getString(Field.LICENSE_TYPE.getName());
      Long suppliedExpirationDate = message.body().getLong(Field.LICENSE_EXPIRATION_DATE.getName());
      expirationDate = Objects.requireNonNullElse(suppliedExpirationDate, Long.MAX_VALUE);
    } catch (Exception ex) {
      log.error(ex.getMessage());
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL1"))
              .put("exception", ex.getMessage())
              .put(Field.ERROR_ID.getName(), "FL2"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    String intercomAccessToken = message.body().getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    String intercomAppId = message.body().getString(Field.INTERCOM_APP_ID.getName());

    String googleId = String.valueOf(userInfo.getInt(Field.ID.getName()));
    String email = userInfo.getString(Field.EMAIL.getName());
    // user is non-existent - no email or GraebertId
    if (googleId.isEmpty() || email == null || email.isEmpty()) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL1"))
              .put(Field.ERROR_ID.getName(), "FL1"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    JsonObject utms = new JsonObject();
    try {
      if (userInfo.has("utmContent") && !userInfo.isNull("utmContent")) {
        utms.put("utmContent", userInfo.getString("utmContent"));
      }
      if (userInfo.has("utmSource") && !userInfo.isNull("utmContent")) {
        utms.put("utmSource", userInfo.getString("utmSource"));
      }
      if (userInfo.has("utmTerm") && !userInfo.isNull("utmContent")) {
        utms.put("utmTerm", userInfo.getString("utmTerm"));
      }
      if (userInfo.has("utmCampaign") && !userInfo.isNull("utmContent")) {
        utms.put("utmCampaign", userInfo.getString("utmCampaign"));
      }
      if (userInfo.has("utmMedium") && !userInfo.isNull("utmContent")) {
        utms.put("utmMedium", userInfo.getString("utmMedium"));
      }
    } catch (Exception ignored) {
      // this doesn't really matter if some issue happens passing UTMs
    }
    // find user
    Item user = Users.findUserByGraebertIdOrEmail(googleId, email);

    // if user isn't found / user doesn't exist on LS
    if (user == null
        || !user.hasAttribute(Field.GRAEBERT_ID.getName())
        || !user.getString(Field.GRAEBERT_ID.getName()).equals(googleId)) {
      if (user == null
          || !user.hasAttribute(Field.USER_EMAIL.getName())
          || !user.getString(Field.USER_EMAIL.getName()).equals(email)) {
        // create a new user
        final String finalJwt = jwt;
        JsonObject newUserInfo = new JsonObject()
            .put(Field.TYPE.getName(), UsersVerticle.GRAEBERT)
            .put(Field.EMAIL.getName(), email)
            .put(Field.NAME.getName(), name)
            .put(Field.SURNAME.getName(), surname)
            .put(Field.LOCALE.getName(), locale)
            .put("timezoneOffset", timezoneOffset)
            .put("foreignId", googleId)
            .put(Field.EDITOR.getName(), editor)
            .put(Field.EMAIL_NOTIFICATIONS.getName(), emailNotifications)
            .put(Field.LICENSE_TYPE.getName(), licenseType)
            .put("expirationDate", expirationDate)
            .put(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken)
            .put(Field.INTERCOM_APP_ID.getName(), intercomAppId)
            .put(Field.ORGANIZATION_ID.getName(), organizationId)
            .put(Field.IS_ORG_ADMIN.getName(), isOrgAdmin)
            .put(Field.UTMC.getName(), utms);

        if (hasCommanderLicense != null) {
          newUserInfo.put(Field.HAS_COMMANDER_LICENSE.getName(), hasCommanderLicense);
        }
        if (hasTouchLicense != null) {
          newUserInfo.put(Field.HAS_TOUCH_LICENSE.getName(), hasTouchLicense);
        }
        eb_request(segment, UsersVerticle.address + ".createUserByForeign", newUserInfo, event -> {
          if (event.succeeded()) {
            if (Field.OK
                .getName()
                .equals(((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
              Entity subSegment =
                  XRayManager.createSubSegment(operationGroup, segment, "createUserByForeign");
              Item newUser;
              String userId =
                  ((JsonObject) event.result().body()).getString(Field.USER_ID.getName());
              String item = ((JsonObject) event.result().body()).getString("item");
              if (item != null && !item.isEmpty()) {
                newUser = Item.fromJSON(item);
              } else {
                newUser = Users.getUserById(userId);
              }
              if (newUser != null) {
                login(subSegment, message, newUser, finalJwt, mills);
              } else {
                XRayManager.endSegment(subSegment);
                sendError(
                    segment,
                    message,
                    new JsonObject()
                        .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
                        .put(Field.ERROR_ID.getName(), "FL3"),
                    HttpStatus.INTERNAL_SERVER_ERROR);
              }
            } else {
              sendError(segment, message, event.result());
            }
          } else {
            sendError(
                segment,
                message,
                event.cause().getLocalizedMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR);
          }
        });
      } else {
        // link account and login
        user.withString(Field.GRAEBERT_ID.getName(), googleId);
        if (name != null && !name.isEmpty()) {
          user.withString(Field.F_NAME.getName(), name);
        }
        if (!surname.isEmpty()) {
          user.withString(Field.SURNAME.getName(), surname);
        }
        if (locale != null && !locale.isEmpty()) {
          user.withString(Field.LOCALE.getName(), locale);
        }
        if (countryLocale != null && !locale.isEmpty()) {
          user.withString(Field.COUNTRY_LOCALE.getName(), countryLocale);
        }
        if (intercomAccessToken != null && !intercomAccessToken.isEmpty()) {
          user.withString(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken);
        }
        if (intercomAppId != null && !intercomAppId.isEmpty()) {
          user.withString(Field.INTERCOM_APP_ID.getName(), intercomAppId);
        }
        if (organizationId != null && !organizationId.isEmpty() && isOrgAdmin != null) {
          user.withString(Field.ORGANIZATION_ID.getName(), organizationId)
              .withBoolean(Field.IS_ORG_ADMIN.getName(), isOrgAdmin);
        } else {
          user.removeAttribute(Field.ORGANIZATION_ID.getName())
              .removeAttribute(Field.IS_ORG_ADMIN.getName());
        }
        JsonObject preferences;
        if (user.hasAttribute(Field.PREFERENCES.getName())) {
          preferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
        } else {
          preferences = new JsonObject();
        }
        preferences.put("timezoneOffset", timezoneOffset);
        user.withJSON(Field.PREFERENCES.getName(), preferences.encode());
        String opt = user.getJSON(Field.OPTIONS.getName());
        JsonObject options = opt != null
            ? new JsonObject(opt)
            : config.getProperties().getDefaultUserOptionsAsJson();
        options.put(Field.EDITOR.getName(), String.valueOf(editor));
        options.put(Field.EMAIL_NOTIFICATIONS.getName(), String.valueOf(emailNotifications));
        user.withJSON(Field.OPTIONS.getName(), options.encode());
        if (licenseType != null && !licenseType.trim().isEmpty()) {
          user.withString(Field.LICENSE_TYPE.getName(), licenseType);
        }
        user.withLong(Field.LICENSE_EXPIRATION_DATE.getName(), expirationDate);
        user.withMap(Field.UTMC.getName(), utms.getMap());
        if (hasCommanderLicense != null) {
          user.withBoolean(Field.HAS_COMMANDER_LICENSE.getName(), hasCommanderLicense);
        }
        if (hasTouchLicense != null) {
          user.withBoolean(Field.HAS_TOUCH_LICENSE.getName(), hasTouchLicense);
        }
        Users.update(user.getString(Field.SK.getName()), user);
        login(segment, message, user, jwt, mills);
      }
    } else {
      boolean emailUpdate = false;
      // updates from license system
      final Map<String, Object> attributes = user.asMap();
      if (name != null && !name.isEmpty() && !name.equals(user.getString(Field.F_NAME.getName()))
          || !surname.isEmpty() && !surname.equals(user.getString(Field.SURNAME.getName()))) {
        user.withString(Field.F_NAME.getName(), name).withString(Field.SURNAME.getName(), surname);
      }
      if (!email.equals(user.getString(Field.USER_EMAIL.getName()))) {
        user.withString(Field.USER_EMAIL.getName(), email);
        emailUpdate = true;
      }
      if (locale != null
          && !locale.isEmpty()
          && !locale.equals(user.getString(Field.LOCALE.getName()))) {
        user.withString(Field.LOCALE.getName(), locale);
      }
      if (countryLocale != null
          && !countryLocale.isEmpty()
          && !countryLocale.equals(user.getString(Field.COUNTRY_LOCALE.getName()))) {
        user.withString(Field.COUNTRY_LOCALE.getName(), countryLocale);
      }
      if (!user.hasAttribute(Field.LICENSE_TYPE.getName())
          || !user.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())
          || licenseType != null
              && !licenseType.isEmpty()
              && !licenseType.equals(user.getString(Field.LICENSE_TYPE.getName()))
          || expirationDate != user.getLong(Field.LICENSE_EXPIRATION_DATE.getName())) {
        user.withString(Field.LICENSE_TYPE.getName(), licenseType)
            .withLong(Field.LICENSE_EXPIRATION_DATE.getName(), expirationDate);
      }
      if (intercomAccessToken != null
          && !intercomAccessToken.isEmpty()
          && !intercomAccessToken.equals(user.getString(Field.INTERCOM_ACCESS_TOKEN.getName()))) {
        user.withString(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken);
      }
      if (intercomAppId != null
          && !intercomAppId.isEmpty()
          && !intercomAppId.equals(user.getString(Field.INTERCOM_APP_ID.getName()))) {
        user.withString(Field.INTERCOM_APP_ID.getName(), intercomAppId);
      }
      if (organizationId != null
          && !organizationId.isEmpty()
          && !organizationId.equals(user.getString("organizarionId"))) {
        user.withString(Field.ORGANIZATION_ID.getName(), organizationId);
      }
      if (isOrgAdmin != null && !isOrgAdmin.equals(user.get(Field.IS_ORG_ADMIN.getName()))) {
        user.withBoolean(Field.IS_ORG_ADMIN.getName(), isOrgAdmin);
      }
      String opt = user.getJSON(Field.OPTIONS.getName());
      JsonObject options =
          opt != null ? new JsonObject(opt) : config.getProperties().getDefaultUserOptionsAsJson();
      options.put(Field.EDITOR.getName(), String.valueOf(editor));
      options.put(Field.EMAIL_NOTIFICATIONS.getName(), String.valueOf(emailNotifications));
      user.withJSON(Field.OPTIONS.getName(), options.encode());
      if (user.getString(Field.LICENSE_TYPE.getName()).trim().isEmpty()) {
        user.withString(Field.LICENSE_TYPE.getName(), "FREE");
      }
      JsonObject preferences;
      if (user.hasAttribute(Field.PREFERENCES.getName())) {
        preferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
      } else {
        preferences = new JsonObject();
      }
      preferences.put("timezoneOffset", timezoneOffset);
      user.withJSON(Field.PREFERENCES.getName(), preferences.encode());
      user.withMap(Field.UTMC.getName(), utms.getMap());
      if (hasCommanderLicense != null) {
        user.withBoolean(Field.HAS_COMMANDER_LICENSE.getName(), hasCommanderLicense);
      }
      if (hasTouchLicense != null) {
        user.withBoolean(Field.HAS_TOUCH_LICENSE.getName(), hasTouchLicense);
      }
      if (!attributes.equals(user.asMap())) {
        if (emailUpdate) {
          boolean exists = Users.getUserByEmail(email).hasNext();
          if (exists) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "ThereIsAnExistingUserWithEmail"),
                HttpStatus.BAD_REQUEST);
            return;
          }
        }
        Users.update(user.getString(Field.SK.getName()), user);
      }
      // login
      login(segment, message, user, jwt, mills);
    }
  }

  private void doGraebertLogin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    HttpResponse<String> licenseResponse;
    String traceId = message.body().getString(Field.TRACE_ID.getName());
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    try {
      JsonObject json = message.body();
      String token = json.getString(Field.TOKEN.getName());
      String deviceId = json.getString("deviceId");
      String jwt = null;
      HttpResponse<String> loginRequest;
      String call;
      if (token == null
          || token.trim().isEmpty()
          || deviceId == null
          || deviceId.trim().isEmpty()) {
        // login
        loginRequest = LicensingService.login(
            json.getString(Field.EMAIL.getName()),
            json.getString(Field.PASSWORD.getName()),
            json.containsKey("pwdEncrypted") ? json.getBoolean("pwdEncrypted") : false,
            userAgent,
            traceId);
        // LS fallback TODO
        if (Objects.isNull(loginRequest)) {
          throw new Exception("Unauthorized");
        }
        if (sendLoginErrorMessage(message, segment, loginRequest)) {
          return;
        }
        // check JWT
        jwt = checkJWT(message, segment, loginRequest);
        if (jwt == null) {
          return;
        }
        call = "api/user/login {email:" + json.getString(Field.EMAIL.getName()) + "}";

      } else {
        // get user's info
        loginRequest = LicensingService.getOwner(deviceId, token, userAgent, traceId);
        // LS fallback TODO
        if (Objects.isNull(loginRequest)) {
          throw new Exception("Unauthorized");
        }

        call = "device/get-owner?deviceId=" + deviceId + "&token=" + token;
      }

      // format login response
      JSONObject body = convertToJson(loginRequest);
      log.info("LS request: " + call + "\nLS response for Graebert Login:" + body.toString(2));
      // validate that request was successful
      if (sendLoginErrorMessage(message, segment, loginRequest)) {
        return;
      }

      // if jwt isn't null - it'll be used for request, otherwise - deviceId and token
      licenseResponse = LicensingService.hasKudo(jwt, traceId, deviceId, token, userAgent);

      Map<String, Boolean> licenses = new HashMap<>();
      if (Utils.isStringNotNullOrEmpty(jwt)) {
        licenses = this.getCommanderAndTouchLicenses(jwt, traceId, userAgent);
      }
      // if (sendLoginErrorMessage(message, segment, licenseResponse)) return;
      boolean editor = getCapability("kudo.editing", jwt, deviceId, token, userAgent);
      boolean emailNotificationsCapability =
          getCapability("areskudo.notifications", jwt, deviceId, token, userAgent);

      // LS fallback TODO
      if (Objects.isNull(licenseResponse)) {
        throw new Exception("Unauthorized");
      }
      finalizeLogin(
          segment,
          message,
          body,
          licenseResponse,
          jwt,
          editor,
          emailNotificationsCapability,
          licenses.get("touch"),
          licenses.get("commander"),
          mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  @Nullable private String checkJWT(
      Message<JsonObject> message, Entity segment, HttpResponse<String> loginRequest) {
    String jwt;
    jwt = loginRequest.getHeaders().getFirst(LicensingService.X_AUTH_TOKEN);
    if (jwt == null || jwt.isEmpty()) {
      System.out.println("JWT error");
      // jwt error - FL1
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL1"))
              .put(Field.ERROR_ID.getName(), "FL1"),
          HttpStatus.BAD_REQUEST);
      return null;
    }
    return jwt;
  }

  private boolean checkLicense(
      Entity segment,
      Message<JsonObject> message,
      HttpResponse<String> licenseResponse,
      String userAgent,
      String name,
      String surname,
      String locale) {
    String licenseBodyStr = licenseResponse.getBody(),
        licenseType = null,
        intercomAccessToken = null,
        intercomAppId = null;
    Long permissionId = null, expirationDate = 0L;
    //        Boolean extendedTrial = false, possibleToExtend = false;
    boolean isLicenseExpired =
        true; // if there was any problem with the response, consider license as expired
    JSONObject licenseBody = new JSONObject();
    if (!licenseBodyStr.isEmpty()) {
      try {
        licenseBody = new JSONObject(licenseBodyStr);
      } catch (Exception ignore) {
        log.error("Exception on parsing LS response; Body: " + licenseBodyStr);
      }
      System.out.println("Received check license body: " + licenseBody);
      if (licenseBody.has("intercom")
          && licenseBody.get("intercom") instanceof JSONObject
          && licenseBody.getJSONObject("intercom").has(Field.INTERCOM_ACCESS_TOKEN.getName())) {
        intercomAccessToken =
            licenseBody.getJSONObject("intercom").getString(Field.INTERCOM_ACCESS_TOKEN.getName());
      }
      if (licenseBody.has("intercom")
          && licenseBody.get("intercom") instanceof JSONObject
          && licenseBody.getJSONObject("intercom").has(Field.INTERCOM_APP_ID.getName())) {
        intercomAppId =
            licenseBody.getJSONObject("intercom").getString(Field.INTERCOM_APP_ID.getName());
      }
      if (licenseBody.has(Field.TYPE.getName())
          && licenseBody.get(Field.TYPE.getName()) instanceof String) {
        licenseType = licenseBody.getString(Field.TYPE.getName());
      }
      if (licenseType == null) {
        licenseType = emptyString;
      }
      if (licenseBody.has(Field.ID.getName())
          && licenseBody.get(Field.ID.getName()) instanceof Number) {
        permissionId = licenseBody.getLong(Field.ID.getName());
      }
      if (licenseBody.has("expirationDate")
          && licenseBody.get("expirationDate") instanceof Number) {
        expirationDate = licenseBody.getLong("expirationDate");
      }
      //            if (licenseBody.has("extendedTrial") && (licenseBody.get("extendedTrial")
      //            instanceof Boolean || licenseBody.get("extendedTrial") instanceof String))
      //                extendedTrial = licenseBody.getBoolean("extendedTrial");
      isLicenseExpired = GMTHelper.utcCurrentTime() > expirationDate;
      //            possibleToExtend = (extendedTrial != null && !extendedTrial) && licenseType
      //            .equals("TRIAL") &&
      //                    expirationDate != 0 && isLicenseExpired;

      message.body().put(Field.LICENSE_TYPE.getName(), licenseType);
      message.body().put(Field.LICENSE_EXPIRATION_DATE.getName(), expirationDate);
      message.body().put(Field.INTERCOM_ACCESS_TOKEN.getName(), intercomAccessToken);
      message.body().put(Field.INTERCOM_APP_ID.getName(), intercomAppId);
    }
    // check license status
    if (licenseResponse.getStatus() != HttpStatus.OK) {
      if (licenseResponse.getStatus() == HttpStatus.FORBIDDEN
          || licenseResponse.getStatus() == HttpStatus.CONFLICT) {
        // expired trial license on log in to kudo (browser) XENON-25901, XENON-26370
        // all license types XENON-28485
        ClientType clientType = getClientType(userAgent);
        if (ClientType.BROWSER.equals(clientType)
            || ClientType.BROWSER_MOBILE.equals(clientType) && isLicenseExpired
        /*&& licenseType.equals("TRIAL")*/ ) {
          return true;
        }
        // error when license has expired - FL16. Message the same as FL2
        int statusCode = userAgent.contains("ARES Commander") && userAgent.contains("17.")
            ? HttpStatus.PRECONDITION_FAILED
            : HttpStatus.UNAUTHORIZED; // to prevent continuous login requests from AC 2017
        sendError(
            segment,
            message,
            new JsonObject()
                .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL2"))
                .put(Field.ERROR_ID.getName(), "FL16")
                //                        .put("possibleToExtend", possibleToExtend)
                .put("permissionId", permissionId)
                .put(Field.LICENSE_TYPE.getName(), licenseType)
                .put("expirationDate", expirationDate)
                .put("isLicenseExpired", isLicenseExpired)
                .put(Field.NAME.getName(), name)
                .put(Field.SURNAME.getName(), surname)
                .put(Field.LOCALE.getName(), locale),
            statusCode,
            LogLevel.INFO,
            "FL16");
      } else {
        // allow login if LS server is not responding or status code is 5xx
        if (String.valueOf(licenseResponse.getStatus()).startsWith("5")) {
          String errorId = licenseBody.has(Field.ERROR_ID.getName())
              ? licenseBody.getString(Field.ERROR_ID.getName())
              : "LS1";
          String errorMessage = licenseBody.has(Field.MESSAGE.getName())
              ? licenseBody.getString(Field.MESSAGE.getName())
              : Utils.getLocalizedString(message, "LS1");
          log.error("[Licensing Server] - errorId : " + errorId + ", status : "
              + licenseResponse.getStatusText() + ", message : " + errorMessage);
          return true;
        }
        // error when licensing server is broken or smth like this
        JsonObject result = new JsonObject()
            .put(Field.MESSAGE.getName(), licenseBody.getString(Field.MESSAGE.getName()));
        if (licenseBody.has(Field.ERROR_ID.getName())) {
          result.put(Field.ERROR_ID.getName(), licenseBody.getString(Field.ERROR_ID.getName()));
        }
        sendError(segment, message, result, licenseResponse.getStatus());
      }
      return false;
    }
    return true;
  }

  private void doForeignLogin(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (userId == null) {
      return;
    }

    Item user = Users.getUserById(userId);
    if (user != null) {
      if (user.get(Field.APPLICANT.getName()) == null
          || !user.getBoolean(Field.APPLICANT.getName())) {
        if ((Boolean) user.get(Field.ENABLED.getName())) {
          login(segment, message, user, mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "TheUserIsDisabled"),
              HttpStatus.FORBIDDEN);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "YourAccountHasNotBeenActivatedYet"),
            HttpStatus.LOCKED);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private void doDigestAuth(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    @NonNls String authHeader = getRequiredString(segment, "authHeader", message, message.body());
    if (authHeader == null) {
      return;
    }
    if (authHeader.startsWith("Digest")) {
      authHeader = authHeader.substring(authHeader.indexOf(" ") + 1).trim();
      HashMap<String, String> values = new HashMap<>();
      String[] keyValueArray = authHeader.split(",");
      for (String keyVal : keyValueArray) {
        if (keyVal.contains("=")) {
          String key = keyVal.substring(0, keyVal.indexOf("="));
          String value = keyVal.substring(keyVal.indexOf("=") + 1);
          values.put(key.trim(), value.replaceAll("\"", emptyString).trim());
        }
      }
      String nonce = values.get(Field.NONCE.getName());
      Item nonceDb = null;
      if (nonce != null) {
        nonceDb = TempData.getNonce(nonce);
        if (nonceDb != null) {
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("deleteNonce")
              .runWithoutSegment(() -> TempData.deleteNonce(nonce));
        }
      }
      if (nonce != null && nonceDb != null) {
        String username = values.get(Field.USERNAME.getName());
        if (username == null || username.isEmpty()) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "UsernameMustBeSpecified"),
              HttpStatus.BAD_REQUEST);
          return;
        } else {
          username = username.toLowerCase();
        }
        Iterator<Item> it = Users.getUserByEmail(username);
        if (it.hasNext()) {
          Item user = it.next();
          if (user.get(Field.APPLICANT.getName()) == null) {
            if (user.hasAttribute(Field.ENABLED.getName())
                && user.getBoolean(Field.ENABLED.getName())) {
              String ha1email = user.getString(Field.HA1_EMAIL.getName());
              String ha2 = EncryptHelper.md5Hex("POST:" + values.get("uri"));
              String serverResponseEmail = EncryptHelper.md5Hex(ha1email + ":" + nonce + ":" + ha2);
              String clientResponse = values.get("response");
              if (clientResponse.equals(serverResponseEmail)) {
                login(segment, message, user, mills);
              } else {
                sendError(
                    segment,
                    message,
                    Utils.getLocalizedString(message, "ResponsesAreNotEqual"),
                    HttpStatus.UNAUTHORIZED,
                    null,
                    getAuthHeader());
              }
            } else {
              sendError(
                  segment,
                  message,
                  Utils.getLocalizedString(message, "TheUserIsDisabled"),
                  HttpStatus.FORBIDDEN);
            }
          } else {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "YourAccountHasNotBeenActivatedYet"),
                HttpStatus.LOCKED);
          }
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectCredentials"),
              HttpStatus.UNAUTHORIZED);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UnknownNonce"),
            HttpStatus.BAD_REQUEST);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "OnlyDigestAuthenticationIsSupported"),
          HttpStatus.BAD_REQUEST);
    }
    recordExecutionTime("digestAuth", System.currentTimeMillis() - mills);
  }

  private void doAdditionalAuth(final Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    Item session = Item.fromJSON(jsonObject.getString("session"));
    log.info("Starting additional auth for session " + session.getString(Field.SK.getName()));
    Item user = Item.fromJSON(jsonObject.getString(Field.USER.getName()));
    final String sessionId = jsonObject.getString(Field.SESSION_ID.getName());
    String s = jsonObject.getString("new");
    boolean createNew = Boolean.parseBoolean(s);
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    try {
      String username, userId, jwt = null;
      if (Sessions.AllowAdminSessionId() && sessionId.equals(adminSessionId)) {
        username = admin;
        userId = adminId;
        session = user;
      } else {
        XRayEntityUtils.putMetadata(segment, XrayField.EXISTING_SESSION_ID, sessionId);
        XRayEntityUtils.putMetadata(segment, XrayField.SESSION_FOUND, session);
        username = session.getString(Field.USERNAME.getName());
        userId = Sessions.getUserIdFromPK(session.getString(Field.PK.getName()));
        jwt = session.getString("jwt");
        updateLastActivity(segment, message, session);
      }
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("putUserMetadata")
          .run((Segment blockingSegment) -> {
            XRayEntityUtils.putMetadata(blockingSegment, XrayField.CREATE_NEW_USER, createNew);
            XRayEntityUtils.putMetadata(blockingSegment, XrayField.USER_ID, userId);
            if (createNew) {
              XRayEntityUtils.putMetadata(blockingSegment, XrayField.USER_EXISTS, user != null);
            }
          });
      if (createNew) {
        log.info("New login request for user " + user.getString(Field.SK.getName()));
        login(segment, message, user, jwt, mills);
        return;
      }
      JsonObject storage = new JsonObject(
          session.getJSON(Field.F_STORAGE.getName())); // get storage from the session
      String storageType = storage.containsKey(Field.STORAGE_TYPE.getName())
          ? storage.getString(Field.STORAGE_TYPE.getName())
          : emptyString;
      String externalId = storage.containsKey(Field.ID.getName())
          ? storage.getString(Field.ID.getName())
          : emptyString;
      JsonObject json = Users.userToJsonWithGoogleId(
          session,
          config.getProperties().getEnterprise(),
          config.getProperties().getInstanceOptionsAsJson(),
          config.getProperties().getDefaultUserOptionsAsJson(),
          config.getProperties().getDefaultLocale(),
          config.getProperties().getUserPreferencesAsJson(),
          config.getProperties().getDefaultCompanyOptionsAsJson());

      log.info("Finished additional auth for session " + session.getString(Field.SK.getName()));

      XRayEntityUtils.putAnnotation(segment, XrayField.USER_ID, userId);

      sendOK(
          segment,
          message,
          json.put(
                  Field.STORAGE_TYPE.getName(),
                  StorageType.getStorageType(storageType)) // validate and
              // trim (e.g. for Webdav)
              .put(Field.EXTERNAL_ID.getName(), externalId)
              .put("additional", parseAdditionalStorageParameters(storage))
              .put(Field.USERNAME.getName(), username)
              .put(
                  Field.ORGANIZATION_ID.getName(),
                  session.getString(Field.ORGANIZATION_ID.getName()))
              .put("groupId", session.getString("groupId"))
              .put(Field.NAME.getName(), session.getString(Field.F_NAME.getName()))
              .put(Field.USER_ID.getName(), userId)
              .put(
                  Field.IS_ADMIN.getName(),
                  session.getList(Field.ROLES.getName()).contains("1"))
              .put(Field.SESSION_ID.getName(), sessionId)
              .put(Field.DEVICE.getName(), session.getString(Field.DEVICE.getName()))
              .put(Field.ROLES.getName(), new JsonArray(session.getList(Field.ROLES.getName())))
              .put(
                  Field.INTERCOM_ACCESS_TOKEN.getName(),
                  session.getString(Field.INTERCOM_ACCESS_TOKEN.getName()))
              .put(
                  Field.NO_DEBUG_LOG.getName(),
                  json.getJsonObject(Field.OPTIONS.getName())
                      .getBoolean(Field.NO_DEBUG_LOG.getName()))
              .put(Field.USER_AGENT.getName(), userAgent),
          mills);

    } catch (Exception ex) {
      sendError(segment, message, ex.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("additionalAuth", System.currentTimeMillis() - mills);
  }

  private void doGetLongNonce(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fmtDate = new SimpleDateFormat("yyyy:MM:dd:hh:mm:ss.SSS").format(new Date());
    int randomInt = random.nextInt();
    final String nonce = "L" + EncryptHelper.md5Hex(fmtDate + randomInt);
    // this nonce will live for 2 hours instead of 5 mins
    TempData.saveNonce(nonce);
    sendOK(segment, message, new JsonObject().put(Field.NONCE.getName(), nonce), mills);
  }

  private void doGetNonce(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    sendOK(segment, message, new JsonObject().put("auth", getAuthHeader()), mills);
  }

  private void doLogout(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String sessionId = message.body().getString(Field.SESSION_ID.getName());
    if (sessionId != null) {
      Item session = logout(segment, message, sessionId);
      if (session != null) {
        JsonObject response = new JsonObject();
        if (session.getString("nameId") != null && session.getString("sessionIndex") != null) {
          response
              .put("nameId", session.getString("nameId"))
              .put("sessionIndex", session.getString("sessionIndex"));
        }
        sendOK(segment, message, response, mills);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserIsNotLoggedIn"),
            HttpStatus.BAD_REQUEST,
            LogLevel.INFO);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "SessionIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST,
          LogLevel.INFO);
    }
  }

  private void doKillSession(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String sessionId = message.body().getString(Field.SESSION_ID.getName());
    if (sessionId == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "SessionIdMustBeSpecified"),
          HttpStatus.BAD_REQUEST,
          LogLevel.INFO);
      return;
    }
    if (logout(segment, message, sessionId) != null) {
      sendOK(segment, message, mills);
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "SessionDoesNotExist"),
          HttpStatus.BAD_REQUEST,
          LogLevel.INFO);
    }
  }

  private Item logout(Entity parentSegment, Message<JsonObject> message, String sessionId) {
    Item session = Sessions.getSessionById(sessionId);
    if (session != null) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, message)
          .withName("logoutUserSession")
          .runWithoutSegment(() -> {
            Sessions.updateDeviceSessionCount(
                session.getString(Field.DEVICE.getName()), Sessions.SessionMode.REMOVESESSION);
            Sessions.cleanUpSession(this, session);
          });
    }
    return session;
  }

  private void updateSessionsStorage(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName()), newStorage = json.getString("storage");
    StorageType removedStorage = StorageType.getStorageType(json.getString("removedStorage"));
    String removedExternalId = json.getString("removedExternalId");
    String currentSessionId = json.getString("currentSessionId");
    Iterator<Item> sessions = Sessions.getAllUserSessions(userId);
    List<Item> items = new ArrayList<>();
    Lists.newArrayList(sessions).stream()
        .filter(item -> {
          JsonObject currentSessionStorage =
              new JsonObject(item.getJSON(Field.F_STORAGE.getName()));
          StorageType type = StorageType.getStorageType(
              currentSessionStorage.getString(Field.STORAGE_TYPE.getName()));
          String externalId = currentSessionStorage.getString(Field.ID.getName());
          // not updating for current session again
          // update other sessions only if the deleted storage is their current one
          return (!item.getString(Field.SK.getName()).equals(currentSessionId)
              && type.equals(removedStorage)
              && removedExternalId.equals(externalId));
        })
        .forEach(item -> {
          item.withJSON(Field.F_STORAGE.getName(), newStorage);
          items.add(item);
          Sessions.removeFromCache(
              item.getString(Field.PK.getName()), item.getString(Field.SK.getName()));
        });
    Dataplane.batchWriteListItems(items, Sessions.tableName, DataEntityType.SESSION);
    sendOK(segment, message);
  }

  private void doHealthCheckup(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    try {
      ListTablesResult result = Dataplane.client.listTables();
      List<String> tables = result.getTableNames();
      if (Utils.isListNotNullOrEmpty(tables)) {
        String prefix = config.getProperties().getPrefix();
        if (tables.contains(prefix) && tables.contains(prefix + "_temp")) {
          sendOK(segment, message);
          return;
        }
      }
      sendError(segment, message, "Cannot access dynamodb", HttpStatus.FORBIDDEN);
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.FORBIDDEN);
    }
  }

  private void doAdminUpdateStorageAccess(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String storage = json.getString(Field.STORAGE_TYPE.getName());
    String userId = json.getString(Field.USER_ID.getName());
    JsonArray excludedUsers = json.getJsonArray("excludedUsers", new JsonArray());
    boolean disable = json.getBoolean("disable", false);
    boolean overrideUsers = json.getBoolean("overrideUsers", true);
    if (!Utils.isStringNotNullOrEmpty(storage)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "StorageTypeMustBeSpecified"),
          HttpStatus.PRECONDITION_FAILED,
          LogLevel.INFO);
      return;
    }
    StorageType storageType = StorageType.getStorageType(storage);
    try {
      JsonArray errors = new JsonArray();
      StorageDAO.createOrUpdateStorageAccess(
          message, storageType, userId, disable, excludedUsers, overrideUsers, errors);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "updateStorageAccess")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                WebSocketManager.address + ((disable) ? ".storageDisabled" : ".storageEnabled"),
                new JsonObject().put(Field.STORAGE_TYPE.getName(), storageType));
          });
      sendOK(segment, message, new JsonObject().put(Field.ERRORS.getName(), errors), mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST);
    }
  }

  private void doGetAdminDisabledStorages(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    try {
      JsonArray storages = new JsonArray();
      StorageDAO.getAllDisabledStorages().forEachRemaining(s -> {
        JsonObject storage = new JsonObject(s.toJSON());
        storage.put(
            Field.STORAGE_TYPE.getName(),
            storage
                .getString(Dataplane.pk)
                .substring(storage.getString(Dataplane.pk).indexOf(Dataplane.hashSeparator) + 1));
        storage.put("updatedBy", storage.getString(Field.USER_ID.getName()));
        storage.remove(Dataplane.pk);
        storage.remove(Dataplane.sk);
        storage.remove(Field.USER_ID.getName());
        storage.remove("disable");
        storages.add(storage);
      });
      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), storages), mills);
    } catch (Exception e) {
      sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST);
    }
  }

  private void updateLastActivity(Entity parentSegment, Message<JsonObject> message, Item session) {
    long lastActivityTimestamp = session.getLong("lastActivity");
    if (GMTHelper.utcCurrentTime() - lastActivityTimestamp > Sessions.MIN_UPDATE_TIME) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, message)
          .withName("updateLastActivityAndTTL")
          .run((Segment blockingSegment) -> {
            Sessions.updateLastActivityAndTTL(
                Sessions.getUserIdFromPK(session.getString(Field.PK.getName())),
                session.getString(Field.SK.getName()));
          });
    }
  }

  private JsonObject parseAdditionalStorageParameters(JsonObject storage) {
    JsonObject additional = new JsonObject();
    if (storage.containsKey(Field.SERVER.getName())) {
      additional.put(Field.SERVER.getName(), storage.getString(Field.SERVER.getName()));
    }
    if (storage.containsKey(Field.REGIONS.getName())) {
      additional.put(Field.REGIONS.getName(), storage.getJsonArray(Field.REGIONS.getName()));
    }
    return additional;
  }

  private void getAndUpdateUserCapabilities(
      Entity segment, String graebertId, String jwt, String userAgent, String traceId) {
    try {
      HttpResponse<String> lsResponse =
          LicensingService.getUserCapabilities(graebertId, jwt, userAgent, traceId);

      if (Objects.isNull(lsResponse)) {
        log.error("LS Response | Error in getting user capabilities");
        return;
      }
      if (lsResponse.getStatus() == HttpStatus.OK) {
        log.info("LS Response | Get user capabilities - " + lsResponse.getBody());
        eb_send(
            segment,
            UsersVerticle.address + ".updateCapabilities",
            new JsonObject()
                .put("licenseResponse", lsResponse.getBody())
                .put(Field.GRAEBERT_ID.getName(), graebertId));
      } else {
        log.error("LS Response | Error in getting user capabilities - " + lsResponse.getStatusText()
            + " : " + lsResponse.getBody());
      }

    } catch (Exception ex) {
      log.error(
          "LS Response | Exception in getting user capabilities - " + ex.getLocalizedMessage());
    }
  }

  public enum ClientType {
    COMMANDER,
    TOUCH,
    BROWSER,
    BROWSER_MOBILE,
    OTHER;

    public static ClientType getClientType(String value) {
      for (ClientType ct : ClientType.values()) {
        if (ct.name().equals(value)) {
          return ct;
        }
      }
      return OTHER;
    }
  }
}
