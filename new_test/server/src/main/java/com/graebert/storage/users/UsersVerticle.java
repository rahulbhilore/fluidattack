package com.graebert.storage.users;

import static com.graebert.storage.integration.StorageType.BOX;
import static com.graebert.storage.integration.StorageType.DROPBOX;
import static com.graebert.storage.integration.StorageType.GDRIVE;
import static com.graebert.storage.integration.StorageType.HANCOM;
import static com.graebert.storage.integration.StorageType.HANCOMSTG;
import static com.graebert.storage.integration.StorageType.INTERNAL;
import static com.graebert.storage.integration.StorageType.NEXTCLOUD;
import static com.graebert.storage.integration.StorageType.ONEDRIVE;
import static com.graebert.storage.integration.StorageType.ONEDRIVEBUSINESS;
import static com.graebert.storage.integration.StorageType.ONSHAPE;
import static com.graebert.storage.integration.StorageType.SAMPLES;
import static com.graebert.storage.integration.StorageType.TRIMBLE;
import static com.graebert.storage.integration.StorageType.WEBDAV;
import static com.graebert.storage.integration.StorageType.getAddress;
import static com.graebert.storage.integration.StorageType.getDisplayName;
import static com.graebert.storage.integration.StorageType.getShort;
import static com.graebert.storage.integration.StorageType.values;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.s3.model.MultiObjectDeleteException;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.common.collect.Iterables;
import com.google.common.net.InternetDomainName;
import com.graebert.storage.Entities.MentionUserInfo;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.NextCloud;
import com.graebert.storage.integration.Onshape;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.UsersPagination;
import com.graebert.storage.integration.WebDAV;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.main.Server;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Comments;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Mentions;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.StorageDAO;
import com.graebert.storage.storage.SubscriptionsDao;
import com.graebert.storage.storage.Templates;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.UsersList;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import kong.unirest.GetRequest;
import kong.unirest.HttpResponse;
import kong.unirest.HttpStatus;
import kong.unirest.JsonNode;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NonNls;

public class UsersVerticle extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.USERS;

  @NonNls
  public static final String address = Field.USERS.getName();

  @NonNls
  public static final String GOOGLE = "google";

  public static final String GRAEBERT = "graebert";
  public static final String SAML = "saml";
  public static final long RESET_TIMER = TimeUnit.HOURS.toMillis(6);

  @NonNls
  private static final String OK = Field.OK.getName();

  private static final String internalStorageObject =
      new JsonObject().put(Field.STORAGE_TYPE.getName(), INTERNAL.toString()).encode();
  private static final String sampleStorageObject =
      new JsonObject().put(Field.STORAGE_TYPE.getName(), SAMPLES.toString()).encode();
  // 6 hours - when request for password reset will expire
  private static final int maxMentionCacheSize = 10;
  private static final StorageType[] excludedConnections = {INTERNAL, HANCOM, HANCOMSTG};
  private S3Regional s3Regional = null;

  public UsersVerticle() {}

  public static boolean isStorageAvailable(
      String storageType, JsonObject userOptions, String userId) {
    boolean isStorageAvailable = true;
    if (userOptions != null) {
      try {
        if (userOptions.containsKey(Field.STORAGES.getName())
            && userOptions.getJsonObject(Field.STORAGES.getName()) != null
            && userOptions
                .getJsonObject(Field.STORAGES.getName())
                .containsKey(storageType.toLowerCase())
            && !userOptions
                .getJsonObject(Field.STORAGES.getName())
                .getBoolean(storageType.toLowerCase())) {
          isStorageAvailable = false;
        }
      } catch (Exception ignore) {
        // let's just ignore this. It can happen if config is weird
      }
    }
    if (StorageDAO.isDisabled(StorageType.getStorageType(storageType), userId)) {
      isStorageAvailable = false;
    }
    return isStorageAvailable;
  }

  // check filter for owner to add in mentions
  private static boolean isAddOwner(String suppliedPattern, JsonObject owner) {
    if (!Utils.isStringNotNullOrEmpty(suppliedPattern)) {
      return true;
    } else {
      return Users.isPatternMatched(
          suppliedPattern,
          owner.getString(Field.NAME.getName()),
          owner.getString(Field.SURNAME.getName()),
          owner.getString(Field.EMAIL.getName()));
    }
  }

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".createUser", this::doCreateUser);
    eb.consumer(address + ".createPortalUser", this::createPortalUser);
    eb.consumer(address + ".createUserByForeign", this::doCreateUserByForeign);
    eb.consumer(address + ".adminCreateUser", this::doAdminCreateUser);
    eb.consumer(address + ".updateProfile", this::doUpdateProfile);
    eb.consumer(address + ".updateAllProfiles", this::updateAllProfiles);
    eb.consumer(address + ".unlinkForeign", this::doUnlinkForeign);
    eb.consumer(address + ".deleteUser", this::doDeleteUser);
    eb.consumer(address + ".getUsers", this::doGetUsers);
    eb.consumer(address + ".changeRoles", this::doChangeRoles);
    eb.consumer(address + ".findUser", this::doFindUser);
    eb.consumer(address + ".getUsersToMention", this::doGetUsersToMention);
    eb.consumer(address + ".enableUser", this::doEnableUser);
    eb.consumer(address + ".getInfo", this::doGetInfo);
    eb.consumer(address + ".requestReset", this::doRequestReset);
    eb.consumer(address + ".reset", this::doReset);
    eb.consumer(address + ".tryReset", this::tryReset);
    eb.consumer(address + ".getStoragesSettings", this::doGetStoragesSettings);
    eb.consumer(address + ".getExternalAccounts", this::doGetExternalAccounts);
    eb.consumer(address + ".getFullExternalAccounts", this::doGetFullExternalAccounts);
    eb.consumer(address + ".getAvailableStorages", this::doGetAvailableStorages);
    eb.consumer(address + ".deleteExternalAccount", this::doDeleteExternalAccount);
    eb.consumer(address + ".switchExternalAccount", this::doSwitchExternalAccount);
    eb.consumer(address + ".changeEmail", this::doChangeEmail);
    eb.consumer(address + ".changeRegion", this::doChangeRegion);
    eb.consumer(address + ".getRegions", this::doGetRegions);
    eb.consumer(address + ".integration", this::integration);
    eb.consumer(address + ".checkEmail", this::doCheckEmail);
    eb.consumer(address + ".updateCompany", this::doUpdateCompany);
    eb.consumer(address + ".getCompany", this::doGetCompany);
    eb.consumer(address + ".updateCompliance", this::doUpdateCompliance);
    eb.consumer(address + ".addStorage", this::doAddStorage);
    eb.consumer(address + ".createSkeleton", this::doCreateSkeleton);
    eb.consumer(address + ".updateSkeleton", this::doUpdateSkeleton);
    eb.consumer(address + ".updateCapabilities", this::doUpdateCapabilities);
    eb.consumer(address + ".getCapabilities", this::doGetCapabilities);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-users");
    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
    //        DK: manually disabled this for now as we only want to check the list of users
    //        uncomment once need to be triggered for real users
    //        handleUsersQueue();
  }

  private void handleUsersQueue() {
    String pattern = SQSPattern.replace("{region}", config.getProperties().getSqsRegion())
        .replace("{accnumber}", config.getProperties().getAwsAccountNumber());
    String usersQueue = pattern.replace("{queue}", config.getProperties().getSqsUsersQueue());
    WorkerExecutor usersQueueExecutor =
        vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-users-sqs");
    // once in a week is enough?
    vertx.setPeriodic(
        TimeUnit.DAYS.toMillis(1),
        event -> usersQueueExecutor.executeBlocking(
            future -> {
              Entity independentStandaloneSegment = XRayManager.createIndependentStandaloneSegment(
                  operationGroup, "usersQueueExecutorPeriodic");
              ReceiveMessageRequest request =
                  new ReceiveMessageRequest(usersQueue).withWaitTimeSeconds(10);
              request.setMaxNumberOfMessages(10);
              List<com.amazonaws.services.sqs.model.Message> messages =
                  sqs.receiveMessage(request).getMessages();
              for (com.amazonaws.services.sqs.model.Message m : messages) {
                JsonObject requestBody = new JsonObject(m.getBody());
                if (requestBody.getString(Field.ACTION.getName()).equals("DELETE")
                    && requestBody.containsKey(Field.ENCAPSULATED_ID.getName())) {
                  if (!isUserLicenseValid(requestBody.getString(Field.ENCAPSULATED_ID.getName()))) {
                    // trigger user removal
                    eb_send(
                        independentStandaloneSegment,
                        address + ".deleteUser",
                        new JsonObject()
                            .put(
                                Field.USER_ID.getName(),
                                requestBody.getString(Field.ENCAPSULATED_ID.getName())));
                  }
                  // Delete from queue
                  final String messageReceiptHandle = m.getReceiptHandle();
                  sqs.deleteMessage(new DeleteMessageRequest(usersQueue, messageReceiptHandle));
                }
              }
              XRayManager.endSegment(independentStandaloneSegment);
              future.complete();
            },
            null));
  }

  private boolean isUserLicenseValid(String userId) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, "usersSQSProcessing");
    try {
      Item user = Users.getUserById(userId);
      if (user == null) {
        return false;
      }
      HttpResponse<String> licenseResponse = LicensingService.hasAnyKudo(
          user.getString(Field.USER_EMAIL.getName()),
          null,
          Objects.nonNull(subsegment) ? String.valueOf(subsegment.getTraceId()) : null);
      return Objects.nonNull(licenseResponse) && licenseResponse.getStatus() == HttpStatus.OK;
    } catch (Exception e) {
      return false;
    }
  }

  private void doGetCompany(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    boolean isEnterprise = config.getProperties().getEnterprise();
    String id = isEnterprise
        ? Users.ENTERPRISE_ORG_ID
        : getRequiredString(segment, Field.ID.getName(), message, jsonObject);
    JsonObject userCompany = jsonObject.getJsonObject(Field.COMPANY.getName());
    String organizationId = userCompany != null ? userCompany.getString(Field.ID.getName()) : null;
    Boolean isOrgAdmin =
        userCompany != null ? userCompany.getBoolean(Field.IS_ADMIN.getName()) : null;

    if (id == null) {
      return;
    }

    if (!id.equals(organizationId)
        || organizationId.equals(AuthManager.NON_EXISTENT_ORG)
        || isOrgAdmin == null
        || !isOrgAdmin) {
      sendError(segment, message, emptyString, HttpStatus.FORBIDDEN);
      return;
    }

    Item company = Users.getOrganizationById(id);
    if (company == null) {
      if (!isEnterprise) {
        sendError(segment, message, emptyString, HttpStatus.NOT_FOUND);
        return;
      } else {
        company = new Item()
            .withPrimaryKey(
                Field.PK.getName(),
                Users.getPkFromOrgId(Users.ENTERPRISE_ORG_ID),
                Field.SK.getName(),
                Users.ENTERPRISE_ORG_ID)
            .withString(Field.COMPANY_NAME.getName(), Users.MY_COMPANY);
        Users.updateOrganization(company);
      }
    }

    JsonObject resultOptions =
        new JsonObject(config.getProperties().getDefaultCompanyOptionsAsJson().encode());
    JsonObject options = company.hasAttribute(Field.OPTIONS.getName())
        ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
        : new JsonObject();
    resultOptions.mergeIn(options, true);

    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.ID.getName(), id)
            .put(Field.NAME.getName(), company.getString(Field.COMPANY_NAME.getName()))
            .put(Field.OPTIONS.getName(), resultOptions),
        mills);
  }

  private void doUpdateCompany(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    boolean isEnterprise = config.getProperties().getEnterprise();
    String id = isEnterprise
        ? Users.ENTERPRISE_ORG_ID
        : getRequiredString(segment, Field.ID.getName(), message, jsonObject);
    String name =
        isEnterprise ? Users.MY_COMPANY : jsonObject.getString(Field.COMPANY_NAME.getName());
    String currentUserId = jsonObject.getString(Field.USER_ID.getName());
    JsonObject options = jsonObject.getJsonObject(Field.OPTIONS.getName());
    String organizationId = jsonObject.getString(Field.ORGANIZATION_ID.getName());
    Boolean isOrgAdmin = jsonObject.getBoolean(Field.IS_ORG_ADMIN.getName());

    // get company info
    JsonObject userCompany = jsonObject.getJsonObject(Field.COMPANY.getName());

    // if organizationId isn't defined - fetch it from company info
    if (organizationId == null && userCompany != null) {
      organizationId = userCompany.getString(Field.ID.getName());
    }
    // same for org admin check
    if (isOrgAdmin == null && userCompany != null) {
      isOrgAdmin = userCompany.getBoolean(Field.IS_ADMIN.getName());
    }

    if (id == null) {
      return;
    }

    if ((name == null || name.isEmpty()) && (options == null || options.isEmpty())) {
      sendError(segment, message, Utils.getLocalizedString(message, "FL8"), HttpStatus.BAD_REQUEST);
      return;
    }

    Item company = Users.getOrganizationById(id);

    if (company == null) {
      if (name != null && !name.isEmpty()) {
        company = new Item()
            .withPrimaryKey(Field.PK.getName(), Users.getPkFromOrgId(id), Field.SK.getName(), id);
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CompanyNotFound"),
            HttpStatus.NOT_FOUND);
        return;
      }
    }

    if (name != null && !name.isEmpty()) {
      company.withString(Field.COMPANY_NAME.getName(), name);
    }

    if (options != null && !options.isEmpty()) {
      boolean doesHaveAccess = config.getProperties().getEnterprise()
          || id.equals(organizationId)
              && isOrgAdmin
              && !organizationId.equals(AuthManager.NON_EXISTENT_ORG);
      if (doesHaveAccess) {
        JsonObject companyOptions = company.hasAttribute(Field.OPTIONS.getName())
            ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
            : new JsonObject();
        log.info(String.format(
            "New options %s, company options %s", options.encode(), companyOptions.encode()));
        companyOptions.mergeIn(options, true);
        log.info(String.format("Result options %s", companyOptions.encode()));
        company.withJSON(Field.OPTIONS.getName(), companyOptions.encode());

        Item currentUser = Users.getUserById(currentUserId);
        JsonObject userOpt =
            currentUser != null && currentUser.hasAttribute(Field.OPTIONS.getName())
                ? new JsonObject(currentUser.getJSON(Field.OPTIONS.getName()))
                : new JsonObject();
        JsonObject originalUserOpt = new JsonObject(userOpt.encode());
        userOpt.mergeIn(options, true);

        if (!originalUserOpt.equals(userOpt)) {
          Users.setUserOptions(currentUserId, userOpt.encode());
        }
        // updated current user options for immediate UI update and updating other users as async.
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              List<Item> users = new ArrayList<>();
              Iterator<Item> organizationUsers = Users.getOrganizationUsers(id);
              while (organizationUsers.hasNext()) {
                String userId = organizationUsers.next().getString(Field.SK.getName());
                if (userId.equals(currentUserId)) {
                  continue;
                }
                Item user = Users.getUserById(userId);
                if (user == null) {
                  continue;
                }
                JsonObject userOptions = user.hasAttribute(Field.OPTIONS.getName())
                    ? new JsonObject(user.getJSON(Field.OPTIONS.getName()))
                    : new JsonObject();
                JsonObject originalUserOptions = new JsonObject(userOptions.encode());
                userOptions.mergeIn(options, true);
                // continue if nothing has changed
                if (originalUserOptions.equals(userOptions)) {
                  continue;
                }
                user.withJSON(Field.OPTIONS.getName(), userOptions.encode());
                users.add(user);
              }
              Dataplane.batchWriteListItems(users, Users.mainTable, DataEntityType.USERS);
            });
      } else {
        sendError(segment, message, emptyString, HttpStatus.FORBIDDEN);
        return;
      }
    }

    //        if (!attributes.equals(company.asMap()))
    Users.updateOrganization(company);
    sendOK(segment, message);
  }

  private void doCheckEmail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String email = message.body().getString(Field.EMAIL.getName());
    String syncToken = config.getProperties().getLicensingSyncToken();
    final long mills = System.currentTimeMillis();
    try {
      GetRequest request = AWSXRayUnirest.get(
          config.getProperties().getLicensingUrl() + "user/exists?email="
              + URLEncoder.encode(email, StandardCharsets.UTF_8),
          "LS.checkEmail");
      if (syncToken != null && !syncToken.isEmpty()) {
        request.header("x-sync-token", syncToken);
      }
      HttpResponse<String> response = request.asString();

      if (response.getStatus() != HttpStatus.OK)
      // email isn't registered
      {
        sendOK(segment, message, new JsonObject().put(Field.IS_AVAILABLE.getName(), true), mills);
      } else
      // user exists
      {
        sendOK(segment, message, new JsonObject().put(Field.IS_AVAILABLE.getName(), false), mills);
      }
    } catch (Exception e) {
      sendError(segment, message, emptyString, HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private void integration(Message<JsonObject> message) {
    String googleClientId = config.getProperties().getGdriveNewClientId();
    if (!Utils.isStringNotNullOrEmpty(googleClientId)) {
      googleClientId = config.getProperties().getGdriveClientId();
    }
    sendOK(
        null,
        message,
        new JsonObject()
            .put("box", config.getProperties().getBoxClientId())
            .put("google", googleClientId)
            .put("gdrive", googleClientId)
            .put("onshape", config.getProperties().getOnshapeClientId())
            .put("onshapedev", config.getProperties().getOnshapedevClientId())
            .put("onshapestaging", config.getProperties().getOnshapestagingClientId())
            .put("dropbox", config.getProperties().getDropboxAppKey())
            .put("trimble", config.getProperties().getTrimbleClientId())
            .put("onedrive", config.getProperties().getOnedriveClientId())
            .put("onedrivebusiness", config.getProperties().getOnedriveBusinessClientId()),
        0);
  }

  private void doSwitchExternalAccount(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String sessionId =
        getRequiredString(segment, Field.SESSION_ID.getName(), message, message.body());
    String externalId = message.body().getString(Field.ID.getName());
    StorageType type = StorageType.getStorageType(message.body().getString(Field.TYPE.getName()));
    if (userId == null) {
      return;
    }
    if (isStorageDisabled(segment, message, type, userId)) {
      return;
    }
    try {
      if (INTERNAL.equals(type) || SAMPLES.equals(type)) {
        @NonNls Item user = Users.getUserById(userId);
        if (user != null) {
          String fstorage = "{\"storageType\" : \"" + type + "\"}";
          user.withJSON(Field.F_STORAGE.getName(), fstorage);
          Users.setUserStorage(userId, fstorage);

          Sessions.setCurrentStorage(userId, sessionId, fstorage);
        }
        sendOK(segment, message, mills);
      } else {
        Item externalAccount = ExternalAccounts.getExternalAccount(userId, externalId);

        if (externalAccount != null) {
          // check if account integration is alive
          eb_request(
              segment,
              StorageType.getAddress(type) + ".connect",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(Field.REGION.getName(), message.body().getString(Field.SERVER.getName()))
                  .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName())),
              event -> {
                if (event.succeeded()
                    && ((JsonObject) event.result().body())
                        .getString(Field.STATUS.getName())
                        .equals(OK)) {
                  Entity subSegment = XRayManager.createSubSegment(
                      operationGroup, segment, Field.F_STORAGE.getName());
                  @NonNls Item user = Users.getUserById(userId);
                  JsonObject newStorageInfo = new JsonObject()
                      .put(Field.STORAGE_TYPE.getName(), type.toString())
                      .put(Field.ID.getName(), externalAccount.getString(Field.SK.getName()));
                  if (StorageType.TRIMBLE.equals(type)) {
                    Item newExternalAccount =
                        ExternalAccounts.getExternalAccount(userId, externalId);
                    if (newExternalAccount.hasAttribute(Field.SERVER.getName())) {
                      newStorageInfo.put(
                          Field.SERVER.getName(),
                          new JsonObject(newExternalAccount.getString(Field.SERVER.getName()))
                              .getString(Field.LOCATION.getName()));
                    }
                    if (newExternalAccount.hasAttribute(Field.REGIONS.getName())) {
                      List<String> regions =
                          newExternalAccount.getList(Field.REGIONS.getName()).stream()
                              .map(regionData -> new JsonObject((String) regionData)
                                  .getString(Field.LOCATION.getName()))
                              .collect(Collectors.toList());
                      JsonArray regionsArray = new JsonArray(regions);
                      newStorageInfo.put(Field.REGIONS.getName(), regionsArray);
                    }
                  }
                  String fstorage = newStorageInfo.encode();
                  user.withJSON(Field.F_STORAGE.getName(), fstorage);
                  Users.setUserStorage(userId, fstorage);
                  Sessions.setCurrentStorage(userId, sessionId, fstorage);

                  XRayManager.endSegment(subSegment);
                  sendOK(segment, message, mills);
                } else if (event.result() != null) {
                  sendError(segment, message, event.result());
                } else {
                  sendError(
                      segment,
                      message,
                      emptyString,
                      HttpStatus.INTERNAL_SERVER_ERROR,
                      event.cause());
                }
              });
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "NoSuchExternalAccount"),
              HttpStatus.BAD_REQUEST);
        }
      }
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotSwitchToExternalAccount"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
    }
  }

  private void doDeleteExternalAccount(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String graebertId = jsonObject.getString(Field.GRAEBERT_ID.getName());
    String username = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);
    String externalId = getRequiredString(segment, Field.ID.getName(), message, jsonObject);
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    StorageType type = StorageType.getStorageType(jsonObject.getString(Field.TYPE.getName()));
    if (userId == null || externalId == null || StorageType.INTERNAL.equals(type)) {
      return;
    }
    try {
      Item user = Users.getUserById(userId);
      // if it's current session's storage switch to first accessible
      @NonNls Item session = Sessions.getSessionById(sessionId);
      JsonObject storage = new JsonObject(session.getJSON(Field.F_STORAGE.getName()));
      StorageType currentStorageType =
          StorageType.getStorageType(storage.getString(Field.STORAGE_TYPE.getName()));
      String currentExternalId = storage.getString(Field.ID.getName());
      if (type.equals(currentStorageType) && externalId.equals(currentExternalId)) {
        JsonObject options = jsonObject.getJsonObject(Field.OPTIONS.getName());
        String endStorage = null;

        // find first valid storage to change to
        for (Item item : ExternalAccounts.getAllExternalAccountsForUser(userId)) {
          if (item.hasAttribute(Field.EXTERNAL_ID_LOWER.getName())
              && item.getString(Field.EXTERNAL_ID_LOWER.getName()).equals(externalId)) {
            continue;
          }
          StorageType itemType = StorageType.getStorageType(item.getString(Field.F_TYPE.getName()));
          String optionName = itemType.name().toLowerCase();
          boolean valid = isStorageAvailable(optionName, options, userId);
          if (!valid) {
            continue;
          }
          endStorage = new JsonObject()
              .put(Field.STORAGE_TYPE.getName(), item.getString(Field.F_TYPE.getName()))
              .put(Field.ID.getName(), item.getString(Field.EXTERNAL_ID_LOWER.getName()))
              .encode();
          break;
        }

        if (endStorage == null) {
          boolean isInternalValid = isStorageAvailable(INTERNAL.toString(), options, userId);
          boolean isSamplesValid = isStorageAvailable(SAMPLES.toString(), options, userId);
          StorageType selectedStorage = isInternalValid ? INTERNAL : null;
          if (selectedStorage == null && isSamplesValid) {
            selectedStorage = SAMPLES;
          }
          if (selectedStorage != null) {
            endStorage = new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), selectedStorage.toString())
                .encode();
          } else {
            endStorage = "{}";
          }
        }
        user.withJSON(Field.F_STORAGE.getName(), endStorage);
        Users.setUserStorage(userId, endStorage);
        Sessions.setCurrentStorage(userId, sessionId, endStorage);
        // update existing sessions in the same region (if storage was connected in another app)
        String finalFstorage = endStorage;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("updateSessionsStorage")
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  AuthManager.address + ".updateSessionsStorage",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), userId)
                      .put("storage", finalFstorage)
                      .put("removedStorage", jsonObject.getString(Field.TYPE.getName()))
                      .put("removedExternalId", externalId)
                      .put("currentSessionId", sessionId));
            });
      }
      if (StorageType.ONSHAPE.equals(type)) {
        eb_send(segment, Onshape.addressProduction + ".disconnect", jsonObject);
      } else if (StorageType.ONSHAPEDEV.equals(type)) {
        eb_send(segment, Onshape.addressDev + ".disconnect", jsonObject);
      } else if (StorageType.ONSHAPESTAGING.equals(type)) {
        eb_send(segment, Onshape.addressStaging + ".disconnect", jsonObject);
      } else if (StorageType.WEBDAV.equals(type)) {
        eb_send(segment, WebDAV.address + ".disconnect", jsonObject);
      } else if (StorageType.NEXTCLOUD.equals(type)) {
        eb_send(segment, NextCloud.address + ".disconnect", jsonObject);
      } else {
        ExternalAccounts.deleteExternalAccount(userId, externalId);
      }
      storageLog(
          segment,
          message,
          userId,
          graebertId,
          sessionId,
          username,
          type.name(),
          externalId,
          false,
          intercomAccessToken);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "CouldNotDeleteExternalAccount"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
    }
    sendOK(segment, message, mills);
  }

  private JsonObject getBaseAccountInfo(String storageName, String externalId, String username) {
    return new JsonObject()
        .put(storageName + Field.ENCAPSULATED_ID.getName(), externalId)
        .put(storageName + "_username", username)
        .put(
            "rootFolderId",
            String.format("%s+%s+-1", StorageType.getShort(storageName), externalId));
  }

  private JsonObject getBaseAccountInfo(
      String storageName, String externalId, String username, String displayName) {
    return getBaseAccountInfo(storageName, externalId, username)
        .put(storageName + "_displayName", displayName);
  }

  private void addAccountToList(
      JsonObject accounts, String storageName, String externalId, String username) {
    addCustomAccountFormatToList(
        accounts, storageName, getBaseAccountInfo(storageName, externalId, username));
  }

  private void addAccountToList(
      JsonObject accounts,
      String storageName,
      String externalId,
      String username,
      String displayName) {
    addCustomAccountFormatToList(
        accounts, storageName, getBaseAccountInfo(storageName, externalId, username, displayName));
  }

  private void addCustomAccountFormatToList(
      JsonObject accounts, String storageName, JsonObject accountData) {
    JsonArray existingAccounts = new JsonArray();
    if (accounts.containsKey(storageName)) {
      existingAccounts = accounts.getJsonArray(storageName);
    }
    existingAccounts.add(accountData);
    accounts.put(storageName, existingAccounts);
  }

  private JsonObject getAvailableStorages(String userId) {
    List<String> allStorages = Arrays.stream(values())
        .filter(storageType -> !StorageDAO.isDisabled(storageType, userId))
        .map(Enum::name)
        .collect(Collectors.toList());
    JsonArray listOfStoragesOnInstance = new JsonArray(allStorages);
    JsonObject instanceStorages = config.getProperties().getInstanceStoragesAsJson();
    if (!instanceStorages.isEmpty()) {
      instanceStorages.forEach(value -> {
        if (listOfStoragesOnInstance.contains(value.getKey().toUpperCase())
            && !Boolean.parseBoolean(value.getValue().toString())) {
          listOfStoragesOnInstance.remove(value.getKey().toUpperCase());
        }
      });
    }
    JsonArray listOfStoragesForUser = listOfStoragesOnInstance.copy();
    if (Utils.isStringNotNullOrEmpty(userId)) {
      Item user = Users.getUserById(userId);
      JsonObject options = new JsonObject(user.getJSON(Field.OPTIONS.getName()));
      if (options.containsKey(Field.STORAGES.getName())
          && options.getJsonObject(Field.STORAGES.getName()) != null) {
        JsonObject userOptions = options.getJsonObject(Field.STORAGES.getName());
        userOptions.forEach(value -> {
          if (!Boolean.parseBoolean(value.getValue().toString())) {
            listOfStoragesForUser.remove(value.getKey().toUpperCase());
          }
        });
      }
    }
    return new JsonObject()
        .put(Field.USER_STORAGES.getName(), listOfStoragesForUser)
        .put(Field.INSTANCE_STORAGES.getName(), listOfStoragesOnInstance);
  }

  private JsonObject getStoragesCapabilities() {
    InputStream is = Server.class.getResourceAsStream("/storagesCapabilities.json");
    if (is == null) {
      return new JsonObject();
    }
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    StringBuilder sb = new StringBuilder();
    String line;
    try {
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
    } catch (IOException ignore) {
      return new JsonObject();
    } finally {
      try {
        br.close();
      } catch (Exception ignore) {
      }
    }
    return new JsonObject(sb.toString());
  }

  private void doGetStoragesSettings(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    StorageType[] orderedArr = {
      SAMPLES, GDRIVE, DROPBOX, ONSHAPE, ONEDRIVE, WEBDAV, NEXTCLOUD, BOX, ONEDRIVEBUSINESS, TRIMBLE
    };
    List<StorageType> ordered = Arrays.asList(orderedArr);
    final StorageType[] allStorages = values();
    List<String> finalOrder = Arrays.stream(allStorages)
        .filter(v -> !StorageDAO.isDisabled(v, jsonObject.getString(Field.USER_ID.getName()))
            && !Arrays.asList(excludedConnections).contains(v))
        .sorted((a, b) -> {
          int indexOfA = ordered.indexOf(a);
          int indexOfB = ordered.indexOf(b);
          if (indexOfA == -1 && indexOfB == -1) {
            // compare name strings if it doesn't work
            return a.name().compareTo(b.name());
          }
          if (indexOfA == -1) {
            return 1;
          }
          if (indexOfB == -1) {
            return -1;
          }
          return indexOfA - indexOfB;
        })
        .map(Enum::name)
        .collect(Collectors.toList());

    String userId = jsonObject.containsKey(Field.USER_ID.getName())
        ? jsonObject.getString(Field.USER_ID.getName())
        : null;
    JsonObject listOfStorages = getAvailableStorages(userId);
    JsonObject response = new JsonObject().put("list", listOfStorages);

    JsonObject capabilities = getStoragesCapabilities();
    final JsonObject defaultCapabilities = capabilities.getJsonObject("default");
    JsonObject capabilitiesList = Arrays.stream(allStorages)
        .map(storageType -> {
          String storageStr = storageType.name();
          if (!listOfStorages
              .getJsonArray(Field.INSTANCE_STORAGES.getName())
              .contains(storageStr)) {
            return null;
          }
          JsonObject result = new JsonObject();
          JsonObject endCapabilities = defaultCapabilities.copy();
          if (capabilities.containsKey(storageStr)) {
            endCapabilities = endCapabilities.mergeIn(capabilities.getJsonObject(storageStr), true);
          }
          result.put(Field.NAME.getName(), storageStr);
          result.put(
              "config",
              new JsonObject()
                  .put("capabilities", endCapabilities)
                  .put(Field.CODE.getName(), getShort(storageType))
                  .put(
                      "isConnectable",
                      !storageType.equals(INTERNAL) && !storageType.equals(SAMPLES))
                  .put("serviceName", storageStr)
                  .put(Field.DISPLAY_NAME.getName(), getDisplayName(storageType)));
          return result;
        })
        .collect(Collector.of(
            JsonObject::new,
            (result, obj) -> {
              if (obj != null) {
                result.put(obj.getString(Field.NAME.getName()), obj.getJsonObject("config"));
              }
            },
            JsonObject::mergeIn));

    response.put("order", new JsonArray(finalOrder)).put(Field.INFO.getName(), capabilitiesList);
    sendOK(segment, message, response, mills);
  }

  private void doGetAvailableStorages(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    if (userId == null) {
      return;
    }
    sendOK(segment, message, getAvailableStorages(userId), mills);
  }

  private void doGetExternalAccounts(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    if (userId == null) {
      return;
    }
    JsonObject options = jsonObject.getJsonObject(Field.OPTIONS.getName());
    JsonObject accounts = new JsonObject();
    String externalId, username;
    for (Item account : ExternalAccounts.getAllExternalAccountsForUser(userId)) {
      externalId = account.getString(ExternalAccounts.sk);
      username = account.getString(Field.EMAIL.getName());
      StorageType type = StorageType.getStorageType(account.getString(Field.F_TYPE.getName()));
      if (Arrays.asList(excludedConnections).contains(type)) {
        continue;
      }
      String storageNameStr = type.name().toLowerCase();
      boolean valid = isStorageAvailable(storageNameStr, options, userId);
      if (!valid) {
        continue;
      }
      switch (type) {
        case GDRIVE:
          if (account.get(Field.ACTIVE.getName()) == null
              || account.getBoolean(Field.ACTIVE.getName())) { // todo ddidenko what is active??
            this.addAccountToList(accounts, storageNameStr, externalId, username);
          }
          break;
        case TRIMBLE:
          JsonObject json = getBaseAccountInfo(storageNameStr, externalId, username);
          if (account.hasAttribute(Field.SERVER.getName())) {
            json.put(
                Field.SERVER.getName(),
                new JSONObject(account.getString(Field.SERVER.getName()))
                    .getString(Field.LOCATION.getName()));
          }
          if (account.hasAttribute(Field.REGIONS.getName())) {
            List<String> regions = account.getList(Field.REGIONS.getName()).stream()
                .map(s -> new JSONObject((String) s).getString(Field.LOCATION.getName()))
                .collect(Collectors.toList());
            json.put(Field.REGIONS.getName(), regions);
          }
          this.addCustomAccountFormatToList(accounts, storageNameStr, json);
          break;
        case WEBDAV:
          String domainWebDav;
          try {
            domainWebDav = InternetDomainName.from(
                    new URL(account.getString(Field.URL.getName())).getHost())
                .toString();
          } catch (Exception e) {
            // we can hit exception for bad URL or IP usage
            log.error(
                "Issue with converting WEBDAV url: " + account.getString(Field.URL.getName()), e);
            domainWebDav = account.getString(Field.URL.getName());
          }
          this.addAccountToList(
              accounts, storageNameStr, externalId, username + " at " + domainWebDav);
          break;
        case NEXTCLOUD:
          String domainNextCloud;
          try {
            domainNextCloud = InternetDomainName.from(
                    new URL(account.getString(Field.URL.getName())).getHost())
                .toString();
          } catch (Exception e) {
            // we can hit exception for bad URL or IP usage
            log.error(
                "Issue with converting NEXTCLOUD url: " + account.getString(Field.URL.getName()),
                e);
            domainNextCloud = account.getString(Field.URL.getName());
          }

          if (account.hasAttribute(Field.DISPLAY_NAME.getName())) {
            this.addAccountToList(
                accounts,
                storageNameStr,
                externalId,
                username + " at " + domainNextCloud,
                account.getString(Field.DISPLAY_NAME.getName()));
          } else {
            this.addAccountToList(
                accounts, storageNameStr, externalId, username + " at " + domainNextCloud);
          }
          break;
        default:

          // most of the storages should be here
          this.addAccountToList(accounts, storageNameStr, externalId, username);
          break;
      }
    }
    username = MailUtil.getDisplayName(Users.getUserById(userId));
    boolean isInternalValid = isStorageAvailable(INTERNAL.toString(), options, userId);
    if (isInternalValid) {
      this.addAccountToList(accounts, INTERNAL.name().toLowerCase(), userId, username);
    }
    boolean isSamplesValid = isStorageAvailable(SAMPLES.toString(), options, userId);
    if (isSamplesValid) {
      long quota =
          Users.getSamplesQuota(userId, config.getProperties().getDefaultUserOptionsAsJson());
      long usage = Users.getSamplesUsage(userId);

      this.addCustomAccountFormatToList(
          accounts,
          SAMPLES.name().toLowerCase(),
          getBaseAccountInfo(
                  SAMPLES.name().toLowerCase(), SimpleStorage.getAccountId(userId), username)
              .put(Field.QUOTA.getName(), quota)
              .put(Field.USAGE.getName(), usage));
    }
    JsonObject response = new JsonObject().mergeIn(accounts);
    Arrays.stream(StorageType.values()).forEach(storageType -> {
      if (Arrays.asList(excludedConnections).contains(storageType)
          || StorageDAO.isDisabled(storageType, userId)) {
        // ignore
        return;
      }
      String formattedStorageName = storageType.name().toLowerCase();
      if (!response.containsKey(formattedStorageName)) {
        response.put(formattedStorageName, new JsonArray());
      }
      if (!storageType.equals(ONEDRIVEBUSINESS)) {
        response
            .put(formattedStorageName + "_name", getStorageProperties(storageType, "Name"))
            .put(formattedStorageName + "_code", StorageType.getShort(storageType))
            .put(formattedStorageName + "_icon", getStorageProperties(storageType, "Icon"))
            .put(
                formattedStorageName + "_icon_black",
                getStorageProperties(storageType, "IconBlack"))
            .put(formattedStorageName + "_icon_png", getStorageProperties(storageType, "IconPng"))
            .put(
                formattedStorageName + "_icon_black_png",
                getStorageProperties(storageType, "IconBlackPng"));
      } else {
        response
            .put("onedrivebusiness_name", config.getProperties().getOnedriveName())
            .put("onedrivebusiness_code", StorageType.getShort(ONEDRIVEBUSINESS))
            .put("onedrivebusiness_icon", config.getProperties().getOnedriveIcon())
            .put("onedrivebusiness_icon_black", config.getProperties().getOnedriveIconBlack())
            .put("onedrivebusiness_icon_png", config.getProperties().getOnedriveIconPng())
            .put(
                "onedrivebusiness_icon_black_png",
                config.getProperties().getOnedriveIconBlackPng());
      }
    });
    sendOK(segment, message, response, mills);
  }

  private void doGetFullExternalAccounts(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String userId = getRequiredString(segment, Field.TARGET_USER_ID.getName(), message, jsonObject);
    if (userId == null) {
      return;
    }
    JsonObject options = jsonObject.getJsonObject(Field.OPTIONS.getName());
    JsonObject accounts = new JsonObject();
    for (Item account : ExternalAccounts.getAllExternalAccountsForUser(userId)) {
      StorageType type = StorageType.getStorageType(account.getString(Field.F_TYPE.getName()));

      if (type.equals(INTERNAL)) {
        continue;
      }
      String storageNameStr = type.name().toLowerCase();
      boolean valid = isStorageAvailable(storageNameStr, options, userId);
      if (!valid) {
        continue;
      }
      JsonArray accountsPerStorage = accounts.containsKey(storageNameStr)
          ? accounts.getJsonArray(storageNameStr)
          : new JsonArray();
      accountsPerStorage.add(new JsonObject(account.toJSON()));
      accounts.put(storageNameStr, accountsPerStorage);
    }
    sendOK(segment, message, accounts, mills);
  }

  private void tryReset(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String hash = getRequiredString(segment, Field.HASH.getName(), message, message.body());
    if (userId == null || hash == null) {
      return;
    }
    Item user = Users.getUserById(userId);
    if (user != null) {
      if (user.get(Field.RESET_REQUEST.getName()) != null
          && user.getBOOL(Field.RESET_REQUEST.getName())) {
        String serverHash;
        try {
          serverHash = EncryptHelper.encodePassword(
              user.getString(Field.SK.getName()) + "@" + user.getString(Field.USER_EMAIL.getName())
                  + "@" + user.getString(Field.HA1_EMAIL.getName()));
        } catch (Exception e) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return;
        }
        if (hash.equals(serverHash)) {
          sendOK(segment, message, mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectHash"),
              HttpStatus.BAD_REQUEST);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserDidNotRequestResetPassword"),
            HttpStatus.BAD_REQUEST);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectKey"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private void doChangeEmail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String hash = getRequiredString(segment, Field.HASH.getName(), message, message.body());
    if (userId == null || hash == null) {
      return;
    }
    Item user = Users.getUserById(userId);
    if (user != null) {
      String dbNewEmail = user.getString(Field.NEW_EMAIL.getName());
      if (dbNewEmail != null) {
        String serverHash;
        try {
          serverHash =
              EncryptHelper.encrypt(dbNewEmail, config.getProperties().getFluorineSecretKey());
        } catch (Exception e) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return;
        }
        if (hash.equals(serverHash)) {
          try {
            Users.setUserEmail(userId, dbNewEmail, user.getString(Field.NEW_HA1_EMAIL.getName()));
          } catch (ConditionalCheckFailedException e) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "ThereIsAnExistingUserWithEmail"),
                HttpStatus.BAD_REQUEST);
            return;
          } catch (Exception ex) {
            sendError(
                segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
            return;
          }
          sendOK(segment, message, mills);
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectHash"),
              HttpStatus.BAD_REQUEST);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserDidNotChangeEmail"),
            HttpStatus.BAD_REQUEST);
      }

    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectKey"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private void doChangeRegion(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    JsonObject body = message.body();

    String userId = body.getString(Field.USER_ID.getName());
    String s3Region = body.getString(Field.S3_REGION.getName());
    boolean withFiles = body.containsKey("withFiles") && body.getBoolean("withFiles");

    Item obj = Users.getUserById(userId);

    if (obj == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
      return;
    }

    if (!S3Regional.isRegionValidAndSupported(s3Region)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "RegionIsNotSupported"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    if (obj.hasAttribute(Field.S3_REGION.getName())
        && obj.getString(Field.S3_REGION.getName()).equals(s3Region)) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "RegionIsAlreadyEnabled"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    obj.withString(Field.S3_REGION.getName(), s3Region);
    Users.setUserS3Region(userId, s3Region);

    JsonObject json = new JsonObject(obj.toJSON());
    sendOK(segment, message, new JsonObject().put("userInfo", json), mills);

    if (withFiles) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("transferFiles")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                SimpleFluorine.address + ".transferFiles",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.S3_REGION.getName(), s3Region));
          });
    }
  }

  private void doGetRegions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.REGIONS.getName(), config.getProperties().getSimpleStorageAsJson()),
        System.currentTimeMillis());
    XRayManager.endSegment(segment);
  }

  private void doReset(final Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String hash = getRequiredString(segment, Field.HASH.getName(), message, message.body());
    String newPass = getRequiredString(segment, Field.PASSWORD.getName(), message, message.body());
    if (userId == null || hash == null || newPass == null) {
      return;
    }
    Item user = Users.getUserById(userId);
    if (user != null) {
      if (user.get(Field.RESET_REQUEST.getName()) != null
          && user.getBoolean(Field.RESET_REQUEST.getName())) {
        String serverHash;
        try {
          serverHash = EncryptHelper.encodePassword(
              user.getString(Field.SK.getName()) + "@" + user.getString(Field.USER_EMAIL.getName())
                  + "@" + user.getString(Field.HA1_EMAIL.getName()));
        } catch (Exception e) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "InternalError"),
              HttpStatus.INTERNAL_SERVER_ERROR,
              e);
          return;
        }
        if (hash.equals(serverHash)) {
          String encodedPassword;
          try {
            encodedPassword = EncryptHelper.md5Hex(
                user.get(Field.USER_EMAIL.getName()) + ":" + AuthManager.realm + ":" + newPass);
          } catch (Exception e) {
            sendError(
                segment,
                message,
                Utils.getLocalizedString(message, "CouldNotEncodePassword"),
                HttpStatus.INTERNAL_SERVER_ERROR,
                e);
            return;
          }
          try {
            Users.resetUser(userId, encodedPassword);
          } catch (Exception ex) {
            sendError(
                segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
            return;
          }

          eb_request(segment, AuthManager.address + ".innerLogin", message.body(), event -> {
            if (((JsonObject) (event.result().body()))
                .getString(Field.STATUS.getName())
                .equals(OK)) {
              sendOK(segment, message, (JsonObject) event.result().body(), mills);
            } else {
              sendError(
                  segment,
                  message,
                  MessageFormat.format(
                      Utils.getLocalizedString(message, "PasswordHasBeenChangedButLoginFailed"),
                      ((JsonObject) (event.result().body())).getString(Field.MESSAGE.getName())),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
        } else {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "IncorrectHash"),
              HttpStatus.BAD_REQUEST);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "UserDidnNotRequestPasswordReset"),
            HttpStatus.BAD_REQUEST);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectKey"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private void doRequestReset(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String email = getRequiredString(segment, Field.EMAIL.getName(), message, message.body());
    if (email == null) {
      return;
    }
    Iterator<Item> items = Users.getUserByEmail(email);
    Item user = null;
    if (items.hasNext()) {
      user = items.next();
    }
    if (user != null) {
      String hash;
      try {
        hash = EncryptHelper.encodePassword(user.getString(Field.SK.getName()) + "@" + email + "@"
            + user.getString(Field.HA1_EMAIL.getName()));
      } catch (Exception e) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "InternalError"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            e);
        return;
      }
      final String id = user.getString(Field.SK.getName());
      Users.setUserResetRequest(id, true);
      vertx.setTimer(RESET_TIMER, event -> {
        Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);

        Users.removeResetRequests(id);

        XRayManager.endSegment(blockingSegment);
      });
      String username = emptyString;
      if (user.get(Field.F_NAME.getName()) != null) {
        username = user.getString(Field.F_NAME.getName());
      }
      if (user.get(Field.SURNAME.getName()) != null) {
        username += Users.spaceString + user.getString(Field.SURNAME.getName());
      }
      if (username.trim().isEmpty()) {
        username = user.getString(Field.USER_EMAIL.getName());
      }
      eb_send(
          segment,
          MailUtil.address + ".resetRequest",
          new JsonObject()
              .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
              .put(Field.EMAIL.getName(), email)
              .put(Field.HASH.getName(), hash)
              .put("timer", RESET_TIMER)
              .put(Field.USERNAME.getName(), username)
              .put(Field.ID.getName(), id));
      sendOK(segment, message);
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "IncorrectEmail"),
          HttpStatus.BAD_REQUEST);
    }
  }

  private void doGetInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.USER_ID.getName());
    String storageType = message.body().getString(Field.STORAGE_TYPE.getName());
    String externalId = message.body().getString(Field.EXTERNAL_ID.getName());
    JsonObject additional = message.body().getJsonObject("additional");
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
      return;
    }
    JsonObject json = Users.userToJsonWithGoogleId(
        user,
        config.getProperties().getEnterprise(),
        config.getProperties().getInstanceOptionsAsJson(),
        config.getProperties().getDefaultUserOptionsAsJson(),
        config.getProperties().getDefaultLocale(),
        config.getProperties().getUserPreferencesAsJson(),
        config.getProperties().getDefaultCompanyOptionsAsJson());

    Item account = null;
    JsonObject storage = new JsonObject()
        .put(Field.ID.getName(), externalId)
        .put(Field.STORAGE_TYPE.getName(), storageType);
    if (StorageType.INTERNAL.toString().equals(storageType)) {
      storage.put(Field.ID.getName(), userId);
      storage.put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()));
    } else if (StorageType.SAMPLES.toString().equals(storageType)) {
      storage.put(Field.ID.getName(), SimpleStorage.getAccountId(userId));
      storage.put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()));
      if (user.hasAttribute(Field.SAMPLE_USAGE.getName())) {
        storage.put(Field.USAGE.getName(), user.getLong(Field.SAMPLE_USAGE.getName()));
      } else {
        storage.put(Field.USAGE.getName(), 0);
      }
    } else if (StorageType.BOX.toString().equals(storageType)) {
      account = ExternalAccounts.getExternalAccount(userId, storage.getString(Field.ID.getName()));
    }
    if (account != null) {
      storage.put(Field.EMAIL.getName(), account.get(Field.EMAIL.getName()));
    }
    if (additional != null) {
      storage.put("additional", additional);
    }
    json.put("storage", storage);

    if (user.hasAttribute(Field.USER_EMAIL.getName())
        && user.hasAttribute(Field.F_NAME.getName())) {
      String username = (user.get(Field.F_NAME.getName()) != null
              ? user.get(Field.F_NAME.getName())
              : emptyString)
          + ((user.hasAttribute(Field.SURNAME.getName())
                  && user.get(Field.SURNAME.getName()) != null)
              ? Users.spaceString + user.get(Field.SURNAME.getName())
              : emptyString);
      if (username.trim().isEmpty()) {
        username = user.getString(Field.USER_EMAIL.getName());
      }
      json.put(Field.USERNAME.getName(), username);
    }

    sendOK(
        segment,
        message,
        new JsonObject().put(Field.RESULTS.getName(), new JsonArray().add(json)),
        mills);
    recordExecutionTime("getUserInfo", System.currentTimeMillis() - mills);
  }

  private void doEnableUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String id = json.getString(Field.ID.getName());
    Boolean enabled = json.getBoolean(Field.ENABLED.getName());

    Item user = Users.getUserById(id);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
      return;
    }
    Users.changeUserAvailability(id, enabled);

    // notify
    eb_send(segment, MailUtil.address + ".notifyEnabled", json);

    sendOK(segment, message, mills);
    recordExecutionTime("enableUser", System.currentTimeMillis() - mills);
  }

  private void doUpdateCompliance(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String id = json.getString(Field.ID.getName());
    String adminId = json.getString("adminId");
    String compliance = json.getString("complianceStatus");

    Item user = Users.getUserById(id);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
      return;
    }

    if (compliance.equals("override")) {
      compliance = "OVERRIDDEN by " + adminId;
    } else if (compliance.equals("clear")) {
      compliance = "CLEARED by " + adminId;
    } else {
      sendError(
          segment,
          message,
          "Invalid operation. Possible operations are 'override' and 'clear'",
          HttpStatus.NOT_FOUND);
      return;
    }

    Users.setUserComplianceStatus(id, compliance);

    sendOK(segment, message, mills);
    recordExecutionTime("updateCompliance", System.currentTimeMillis() - mills);
  }

  private void doCreateUserByForeign(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String type = jsonObject.getString(Field.TYPE.getName());
    String email = jsonObject.getString(Field.EMAIL.getName());
    String name = jsonObject.getString(Field.NAME.getName());
    String surname = jsonObject.getString(Field.SURNAME.getName());
    String externalId = jsonObject.getString("foreignId");
    String accessToken = jsonObject.getString(Field.ACCESS_TOKEN.getName());
    String refreshToken = jsonObject.getString(Field.REFRESH_TOKEN.getName());
    String locale = jsonObject.getString(Field.LOCALE.getName());
    Boolean editor = jsonObject.getBoolean(Field.EDITOR.getName());
    Boolean emailNotifications = jsonObject.getBoolean(Field.EMAIL_NOTIFICATIONS.getName());
    String licenseType = jsonObject.getString(Field.LICENSE_TYPE.getName());
    Long expirationDate = jsonObject.getLong("expirationDate");
    String intercomAccessToken = jsonObject.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
    String intercomAppId = jsonObject.getString(Field.INTERCOM_APP_ID.getName());
    String organizationId = jsonObject.getString(Field.ORGANIZATION_ID.getName());
    Boolean isOrgAdmin = jsonObject.getBoolean(Field.IS_ORG_ADMIN.getName());
    String complianceTestResult = jsonObject.getString("complianceTestResult");
    JsonObject utms = jsonObject.getJsonObject(Field.UTMC.getName());
    String country = jsonObject.getString("country");

    Boolean hasTouchLicense = null;
    Boolean hasCommanderLicense = null;
    try {
      if (jsonObject.containsKey(Field.HAS_TOUCH_LICENSE.getName())) {
        hasTouchLicense = jsonObject.getBoolean(Field.HAS_TOUCH_LICENSE.getName(), null);
      }

      if (jsonObject.containsKey(Field.HAS_COMMANDER_LICENSE.getName())) {
        hasCommanderLicense = jsonObject.getBoolean(Field.HAS_COMMANDER_LICENSE.getName(), null);
      }
    } catch (Exception ignored) {
    }
    int timezoneOffset = 0;
    try {
      timezoneOffset = jsonObject.getInteger("timezoneOffset");
    } catch (Exception e) {
      // here might be weird exceptions like NPE, so let's ignore it
    }

    if (email.trim().isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL4"),
          HttpStatus.BAD_REQUEST,
          "FL4");
      return;
    }

    if (type.equals(GOOGLE)) {
      // try to find existing user with this login/email
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

    // get country and proper s3region
    String s3Region = Users.getS3RegionByCountry(config, country);

    // create new user
    try {
      String id = UUID.randomUUID().toString();
      if (name == null || name.trim().isEmpty()) {
        name = email.substring(0, email.indexOf("@"));
      }

      JsonObject options = config.getProperties().getDefaultUserOptionsAsJson();
      boolean isInternalValid = isStorageAvailable(
          INTERNAL.toString(), options, jsonObject.getString(Field.USER_ID.getName()));
      boolean isSamplesValid = isStorageAvailable(
          SAMPLES.toString(), options, jsonObject.getString(Field.USER_ID.getName()));

      @NonNls String storage = new JsonObject().encode();

      if (type.equals(GOOGLE)) {
        storage = new JsonObject()
            .put(Field.STORAGE_TYPE.getName(), GDRIVE.toString())
            .put(Field.ID.getName(), externalId)
            .encode();
      } else if (isSamplesValid) {
        storage = sampleStorageObject;
      } else if (isInternalValid) {
        storage = internalStorageObject;
      }

      Item user = new Item()
          .withPrimaryKey(Field.PK.getName(), Users.getPkFromUserId(id), Field.SK.getName(), id)
          .withString(Field.F_NAME.getName(), name)
          .withString(Field.USER_EMAIL.getName(), email.toLowerCase())
          .withList(Field.ROLES.getName(), new ArrayList<>())
          .withBoolean(Field.ENABLED.getName(), !type.equals(GOOGLE))
          .withBoolean(Field.APPLICANT.getName(), type.equals(GOOGLE))
          .withString(
              Field.LOCALE.getName(),
              locale != null ? locale : config.getProperties().getDefaultLocale())
          .withJSON(Field.F_STORAGE.getName(), storage)
          .withString(Field.S3_REGION.getName(), s3Region)
          .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime());
      if (Utils.isStringNotNullOrEmpty(country)) {
        user.withString("country", country);
      }
      if (surname != null && !surname.isEmpty()) {
        user.withString(Field.SURNAME.getName(), surname);
      }
      if (type.equals(GOOGLE)) {
        user.withString("gId", externalId);
      } else if (type.equals(GRAEBERT)) {
        user.withString(Field.GRAEBERT_ID.getName(), externalId);
      }
      if (licenseType != null && !licenseType.trim().isEmpty()) {
        user.withString(Field.LICENSE_TYPE.getName(), licenseType);
      }
      if (expirationDate != null) {
        user.withLong(Field.LICENSE_EXPIRATION_DATE.getName(), expirationDate);
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
      }
      if (complianceTestResult != null && !complianceTestResult.isEmpty()) {
        user.withString("complianceTestResult", complianceTestResult);
      }
      if (utms != null) {
        user.withMap(Field.UTMC.getName(), utms.getMap());
      }

      if (options != null) {
        if (editor != null && editor) {
          options.put(Field.EDITOR.getName(), String.valueOf(editor));
        }
        if (emailNotifications != null) {
          options.put(Field.EMAIL_NOTIFICATIONS.getName(), String.valueOf(emailNotifications));
        }
        user.withJSON(Field.OPTIONS.getName(), options.encode());
      }

      JsonObject preferences;
      if (user.hasAttribute(Field.PREFERENCES.getName())) {
        preferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
      } else {
        preferences = new JsonObject();
      }
      preferences.put("timezoneOffset", timezoneOffset);
      if (hasCommanderLicense != null) {
        user.withBoolean(Field.HAS_COMMANDER_LICENSE.getName(), hasCommanderLicense);
      }
      if (hasTouchLicense != null) {
        user.withBoolean(Field.HAS_TOUCH_LICENSE.getName(), hasTouchLicense);
      }
      user.withJSON(Field.PREFERENCES.getName(), preferences.encode());

      try {
        Users.update(id, user);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }

      if (type.equals(GOOGLE)) {
        Item externalAccount = new Item()
            .withPrimaryKey(
                Field.FLUORINE_ID.getName(), id, Field.EXTERNAL_ID_LOWER.getName(), externalId)
            .withString(Field.ACCESS_TOKEN.getName(), accessToken)
            .withString(Field.EMAIL.getName(), email.toLowerCase())
            .withString(Field.NAME.getName(), name)
            .withString(Field.SURNAME.getName(), surname)
            .withString(Field.F_TYPE.getName(), StorageType.GDRIVE.toString());
        if (refreshToken != null) {
          externalAccount.withString(Field.REFRESH_TOKEN.getName(), refreshToken);
        } else {
          externalAccount.withBoolean(Field.ACTIVE.getName(), false);
        }
        ExternalAccounts.saveExternalAccount(id, externalId, externalAccount);
        // no need to notify admins for "prod" users
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("notifyAdmins")
            .run((Segment blockingSegment) -> {
              // notify admins
              eb_send(
                  blockingSegment,
                  MailUtil.address + ".notifyAdmins",
                  new JsonObject()
                      .put(Field.USER_ID.getName(), id)
                      .put("withHash", false)
                      .put(Field.LOCALE.getName(), jsonObject.getString(Field.LOCALE.getName())));
            });
      }
      // no need to create skeletons for temp accounts
      if (isInternalValid) {
        triggerSkeletonCreation(segment, message, INTERNAL, email, id);
      }
      if (isSamplesValid) {
        triggerSkeletonCreation(segment, message, SAMPLES, email, id);
      }

      sendOK(
          segment,
          message,
          new JsonObject().put(Field.USER_ID.getName(), id).put("item", user.toJSON()),
          mills);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
    }
  }

  private void doAdminCreateUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String email = jsonObject.getString(Field.EMAIL.getName());
    String name = jsonObject.getString(Field.NAME.getName());
    String surname = jsonObject.getString(Field.SURNAME.getName());
    String locale = jsonObject.getString(Field.LOCALE.getName());
    String complianceTestResult = jsonObject.getString("complianceTestResult");
    int timezoneOffset = 0;
    try {
      timezoneOffset = jsonObject.getInteger("timezoneOffset");
    } catch (Exception e) {
      // here might be weird exceptions like NPE, so let's ignore it
    }

    if (email.trim().isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "FL4"),
          HttpStatus.BAD_REQUEST,
          "FL4");
      return;
    }

    // try to find existing user with this login/email
    boolean exists = Users.getUserByEmail(email).hasNext();
    if (exists) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThereIsAnExistingUserWithEmail"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    // create new user
    try {
      String id = UUID.randomUUID().toString();
      if (name == null || name.trim().isEmpty()) {
        name = email.substring(0, email.indexOf("@"));
      }
      JsonObject options = config.getProperties().getDefaultUserOptionsAsJson();
      boolean isInternalValid = isStorageAvailable(
          INTERNAL.toString(), options, jsonObject.getString(Field.USER_ID.getName()));
      boolean isSamplesValid = isStorageAvailable(
          SAMPLES.toString(), options, jsonObject.getString(Field.USER_ID.getName()));

      @NonNls String storage;

      if (isSamplesValid) {
        storage = sampleStorageObject;
      } else if (isInternalValid) {
        storage = internalStorageObject;
      } else {
        storage = "NULL";
      }
      // we need to generate password
      final String password = Utils.generateRandomPassword(10);
      // generate ha1 for further use
      String ha1 = EncryptHelper.md5Hex(email + ":" + AuthManager.realm + ":" + password);
      Item user = new Item()
          .withPrimaryKey(Field.PK.getName(), Users.getPkFromUserId(id), Field.SK.getName(), id)
          .withString(Field.F_NAME.getName(), name)
          .withString(Field.USER_EMAIL.getName(), email)
          .withString(Field.HA1_EMAIL.getName(), ha1)
          .withList(Field.ROLES.getName(), new ArrayList<>())
          .withBoolean(Field.ENABLED.getName(), true)
          .withString(
              Field.LOCALE.getName(),
              locale != null ? locale : config.getProperties().getDefaultLocale())
          .withJSON(Field.F_STORAGE.getName(), storage)
          .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime());
      if (surname != null && !surname.isEmpty()) {
        user.withString(Field.SURNAME.getName(), surname);
      }
      if (complianceTestResult != null && !complianceTestResult.isEmpty()) {
        user.withString("complianceTestResult", complianceTestResult);
      }

      if (options != null) {
        user.withJSON(Field.OPTIONS.getName(), options.encode());
      }

      JsonObject preferences;
      if (user.hasAttribute(Field.PREFERENCES.getName())) {
        preferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
      } else {
        preferences = new JsonObject();
      }
      preferences.put("timezoneOffset", timezoneOffset);
      user.withJSON(Field.PREFERENCES.getName(), preferences.encode());

      try {
        Users.update(id, user);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }
      if (isInternalValid) {
        triggerSkeletonCreation(segment, message, INTERNAL, email, id);
      }
      if (isSamplesValid) {
        triggerSkeletonCreation(segment, message, SAMPLES, email, id);
      }
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.USER_ID.getName(), id)
              .put(Field.EMAIL.getName(), email)
              .put(Field.PASSWORD.getName(), password),
          mills);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e);
    }
  }

  private void createPortalUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String traceId = jsonObject.getString(Field.TRACE_ID.getName());
    String email = jsonObject.getString(Field.EMAIL.getName());
    String password = jsonObject.getString(Field.PASSWORD.getName());
    String firstName = jsonObject.getString(Field.FIRST_NAME.getName());
    String lastName = jsonObject.getString(Field.LAST_NAME.getName());
    Integer country = jsonObject.getInteger("country");
    Integer state = jsonObject.getInteger(Field.STATE.getName());
    String organization = jsonObject.getString("organization");
    String city = jsonObject.getString("city");
    String phone = jsonObject.getString("phone");
    Boolean agreedToEULA = jsonObject.getBoolean("agreedToEula");
    JsonObject body = new JsonObject()
        .put(Field.EMAIL.getName(), email)
        .put(Field.PASSWORD.getName(), password)
        .put(Field.FIRST_NAME.getName(), firstName)
        .put(Field.LAST_NAME.getName(), lastName)
        .put("country", country)
        .put(
            "pwdEncrypted",
            jsonObject.containsKey("pwdEncrypted") ? jsonObject.getBoolean("pwdEncrypted") : false);
    if (state != null && state > 0) {
      body.put(Field.STATE.getName(), state);
    }
    if (organization != null && !organization.trim().isEmpty()) {
      body.put(Field.COMPANY.getName(), organization);
    }
    if (city != null && !city.trim().isEmpty()) {
      body.put("city", city);
    }
    if (phone != null && !phone.trim().isEmpty()) {
      body.put("phone", phone);
    }
    if (agreedToEULA != null) {
      body.put("agreedToEula", agreedToEULA);
    }
    String url = new String(Base64.encodeBase64(
        (config.getProperties().getUrl() + "?activated=true").getBytes(StandardCharsets.UTF_8)));
    try {
      HttpResponse<String> request = AWSXRayUnirest.post(
              config.getProperties().getLicensingUrl() + "user/create?sendEmail=true&source=" + url,
              "Licensing.createUser")
          .header("Content" + "-type", "application/json")
          .header("X-Amzn-Trace-Id", "Root=" + traceId)
          .body(body.encodePrettily())
          .asString();
      if (request.getStatus() != HttpStatus.CREATED) {
        sendError(
            segment,
            message,
            new JsonObject().put(Field.MESSAGE.getName(), request.getBody()),
            request.getStatus());
        return;
      }
      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(segment, message, e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR, e);
    }
  }

  private void doCreateUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String email = jsonObject.getString(Field.EMAIL.getName());
    String password = jsonObject.getString(Field.PASSWORD.getName());
    String name = jsonObject.getString(Field.NAME.getName());
    String surname = jsonObject.getString(Field.SURNAME.getName());
    boolean pwdEncrypted =
        jsonObject.containsKey("pwdEncrypted") ? jsonObject.getBoolean("pwdEncrypted") : false;
    if (email.trim().isEmpty() || password.trim().isEmpty()) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "EmailPrPasswordNotProvided"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    // try to find existing user with this login/email
    boolean exists = Users.getUserByEmail(email).hasNext();
    if (exists) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThereIsAnExistingUserWithEmail"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    // create new user
    try {
      if (pwdEncrypted) {
        password = Arrays.toString(Base64.decodeBase64(password));
      }
      String id = UUID.randomUUID().toString();
      if (name == null || name.trim().isEmpty()) {
        name = email.substring(0, email.indexOf("@"));
      }
      String ha1 = EncryptHelper.md5Hex(email + ":" + AuthManager.realm + ":" + password);
      String nonce = Utils.generateUUID();

      JsonObject options = config.getProperties().getDefaultUserOptionsAsJson();
      boolean isInternalValid = isStorageAvailable(
          INTERNAL.toString(), options, jsonObject.getString(Field.USER_ID.getName()));
      boolean isSamplesValid = isStorageAvailable(
          SAMPLES.toString(), options, jsonObject.getString(Field.USER_ID.getName()));

      @NonNls String storage = new JsonObject().encode();

      if (isSamplesValid) {
        storage = sampleStorageObject;
      } else if (isInternalValid) {
        storage = internalStorageObject;
      }

      Item user = new Item()
          .withPrimaryKey(Field.PK.getName(), Users.getPkFromUserId(id), Field.SK.getName(), id)
          .withString(Field.NONCE.getName(), nonce)
          .withString(Field.F_NAME.getName(), name)
          .withString(Field.USER_EMAIL.getName(), email)
          .withString(Field.HA1_EMAIL.getName(), ha1)
          .withList(Field.ROLES.getName(), new ArrayList<>())
          .withBoolean(Field.ENABLED.getName(), false)
          .withBoolean(Field.APPLICANT.getName(), true)
          .withString(Field.LOCALE.getName(), config.getProperties().getDefaultLocale())
          .withJSON(Field.F_STORAGE.getName(), storage)
          .withLong(Field.CREATION_DATE.getName(), GMTHelper.utcCurrentTime());

      if (options != null) {
        user.withJSON(Field.OPTIONS.getName(), options.encode());
      }

      if (surname != null && !surname.trim().isEmpty()) {
        user.withString(Field.SURNAME.getName(), surname);
      }

      try {
        Users.update(id, user);
      } catch (Exception ex) {
        sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        return;
      }

      if (isStorageAvailable(
          INTERNAL.toString(), options, jsonObject.getString(Field.USER_ID.getName()))) {
        triggerSkeletonCreation(segment, message, INTERNAL, email, id);
      }
      if (isStorageAvailable(
          SAMPLES.toString(), options, jsonObject.getString(Field.USER_ID.getName()))) {
        triggerSkeletonCreation(segment, message, SAMPLES, email, id);
      }

      // send mail
      String finalName = name;
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("confirmRegistration")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                MailUtil.address + ".confirmRegistration",
                new JsonObject()
                    .put(Field.USER_ID.getName(), id)
                    .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
                    .put(Field.NONCE.getName(), nonce)
                    .put(
                        Field.USERNAME.getName(),
                        finalName + (surname != null ? Users.spaceString + surname : emptyString))
                    .put(Field.EMAIL.getName(), email));
          });
      message.body().remove(Field.PASSWORD.getName());
      message.body().remove("passconfirm");
      sendOK(segment, message, message.body().put(Field.USER_ID.getName(), id), mills);
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "InternalError"),
          HttpStatus.INTERNAL_SERVER_ERROR,
          e); // integration test fail here
    }
    recordExecutionTime("createUser", System.currentTimeMillis() - mills);
  }

  private void doUnlinkForeign(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(Field.USER_ID.getName());
    String password = body.getString(Field.PASSWORD.getName());
    Item user = Users.getUserById(userId);
    if (user != null) {
      if (user.getString("gId") != null) {
        try {
          Users.updateUserForeignPassword(
              userId,
              EncryptHelper.md5Hex(user.getString(Field.USER_EMAIL.getName()) + ":"
                  + AuthManager.realm + ":" + password));
          sendOK(segment, message, mills);
        } catch (Exception e) {
          sendError(segment, message, e.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
      } else {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "ThereIsNoLinkedForeignAccount"),
            HttpStatus.BAD_REQUEST);
      }
    } else {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
    }
  }

  private void updateAllProfiles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    JsonObject storages =
        body.getJsonObject(Field.OPTIONS.getName()).getJsonObject(Field.STORAGES.getName());
    // retrieve all users, update options and make batch write
    Iterator<Item> users = Users.getAllUsers();
    List<Item> toUpdate = new ArrayList<>();
    users.forEachRemaining(user -> {
      String str = user.getJSON(Field.OPTIONS.getName());
      JsonObject usersOptions;
      if (str == null || str.isEmpty()) {
        usersOptions = config.getProperties().getDefaultUserOptionsAsJson();
      } else {
        usersOptions = new JsonObject(user.getJSON(Field.OPTIONS.getName()));
      }
      JsonObject usersStorages = usersOptions.getJsonObject(Field.STORAGES.getName());
      JsonObject originalUsersStorages = new JsonObject().mergeIn(usersStorages);
      usersStorages.mergeIn(storages);
      if (str == null || str.isEmpty() || !originalUsersStorages.equals(usersStorages)) {
        user.withJSON(Field.OPTIONS.getName(), usersOptions.encode());
        toUpdate.add(user);
      }
    });
    Dataplane.batchWriteListItems(toUpdate, Users.mainTable, DataEntityType.USERS);
    sendOK(segment, message, mills);
  }

  private void doUpdateProfile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(Field.USER_ID.getName());
    String sessionId = body.getString(Field.SESSION_ID.getName());
    String currentPass = body.getString("currentPass");
    String name = body.getString(Field.NAME.getName());
    String surname = body.getString(Field.SURNAME.getName());
    String email = body.getString(Field.EMAIL.getName());
    String newPass = body.getString("newPass");
    String locale = body.getString("changeLocale");
    JsonObject storage = body.getJsonObject("storage");
    JsonObject options = body.getJsonObject(Field.OPTIONS.getName());
    JsonObject preferences = body.getJsonObject(Field.PREFERENCES.getName());
    Boolean isTrialShown = body.getBoolean(Field.IS_TRIAL_SHOWN.getName());
    Boolean showRecent = body.getBoolean(Field.SHOW_RECENT.getName());
    Long notificationBarShowed = body.getLong(Field.NOTIFICATION_BAR_SHOWED.getName());
    String fileFilter = body.getString(Field.FILE_FILTER.getName());
    String s3Region = body.getString(Field.S3_REGION.getName());

    @NonNls Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    // if user is trying to change email or password check if currentPass is correct
    if (email != null || newPass != null) {
      if (currentPass == null || currentPass.isEmpty()) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CurrentPasswordShouldBeSpecified"),
            HttpStatus.BAD_REQUEST);
        return;
      }
      String ha1email = EncryptHelper.md5Hex(
          user.getString(Field.USER_EMAIL.getName()) + ":" + AuthManager.realm + ":" + currentPass);
      if (!ha1email.equals(user.getString(Field.HA1_EMAIL.getName()))) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "WrongCurrentPassword"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }

    // check if email is unique
    if (email != null
        && !email.isEmpty()
        && !email.equals(user.getString(Field.USER_EMAIL.getName()))) {
      boolean exists = Users.getUserByEmail(email).hasNext();
      if (exists) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "EmailIsNotUnique"),
            HttpStatus.BAD_REQUEST);
        return;
      }
    }

    String currentLocale;
    try {
      currentLocale = body.getString(Field.LOCALE.getName());
    } catch (Exception ex) {
      currentLocale = user.getString(Field.LOCALE.getName());
    }

    try {
      if (S3Regional.isRegionValidAndSupported(s3Region)) {
        user.withString(Field.S3_REGION.getName(), s3Region);
      }
    } catch (IllegalArgumentException ignored) {
    }

    if (name != null && !name.trim().isEmpty()) {
      user.withString(Field.F_NAME.getName(), name);
    }
    if (surname != null) {
      if (surname.trim().isEmpty()) {
        user.removeAttribute(Field.SURNAME.getName());
      } else {
        user.withString(Field.SURNAME.getName(), surname);
      }
    }
    if (email != null) { // wait for confirmation
      user.withString(Field.NEW_EMAIL.getName(), email);
      user.withString(
          Field.NEW_HA1_EMAIL.getName(),
          EncryptHelper.md5Hex(email + ":" + AuthManager.realm + ":" + currentPass));
    }
    if (newPass != null && !newPass.isEmpty()) {
      try {
        user.withString(
            Field.HA1_EMAIL.getName(),
            EncryptHelper.md5Hex(
                user.get(Field.USER_EMAIL.getName()) + ":" + AuthManager.realm + ":" + newPass));
      } catch (Exception e) {
        sendError(
            segment,
            message,
            Utils.getLocalizedString(message, "CouldNotEncodePassword"),
            HttpStatus.INTERNAL_SERVER_ERROR,
            e);
      }
    }
    boolean send = true;
    if (storage != null) {
      String type = storage.getString(Field.TYPE.getName());
      String authCode = storage.getString("authCode");
      if (INTERNAL.toString().equals(type)) {
        user.withJSON(Field.F_STORAGE.getName(), internalStorageObject);
      } else if (SAMPLES.toString().equals(type)) {
        user.withJSON(
            Field.F_STORAGE.getName(),
            new JsonObject()
                .put(Field.STORAGE_TYPE.getName(), SAMPLES.toString())
                .encode());
      } else if (NEXTCLOUD.toString().equals(type)) {
        send = false;
        eb_request(
            segment,
            getAddress(StorageType.getStorageType(type)) + ".addAuthCode",
            new JsonObject()
                .put(Field.USER_ID.getName(), userId)
                .put(Field.SESSION_ID.getName(), sessionId)
                .put(Field.URL.getName(), storage.getString(Field.URL.getName())),
            event -> {
              JsonObject result = (JsonObject) event.result().body();
              if (OK.equals(result.getString(Field.STATUS.getName()))) {
                sendOK(
                    segment,
                    message,
                    new JsonObject().put("authUrl", result.getString("authUrl")),
                    mills);
              } else {
                sendError(segment, message, event.result());
              }
            });
      } else {
        if (!WEBDAV.toString().equals(type) && (authCode == null || authCode.isEmpty())) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "AuthCodeMustBeSpecified"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        send = false;
        eb_request(
            segment,
            getAddress(StorageType.getStorageType(type)) + ".addAuthCode",
            body.mergeIn(storage)
                .put(Field.CODE.getName(), authCode)
                .put(Field.EMAIL.getName(), storage.getString(Field.EMAIL.getName()))
                .put(Field.GRAEBERT_ID.getName(), user.getString(Field.GRAEBERT_ID.getName()))
                .put(Field.LOCALE.getName(), currentLocale),
            event -> {
              if (event.result() != null && event.result().body() != null) {
                if (OK.equals(
                    ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
                  sendOK(segment, message, mills);
                } else {
                  sendError(segment, message, event.result());
                }
              }
            });
      }
    }
    if (locale != null && !locale.trim().isEmpty()) {
      user.withString(Field.LOCALE.getName(), locale);
    }
    if (options != null) {
      try {
        if (options.containsKey(Field.QUOTA.getName())
            && user.hasAttribute(Field.SAMPLE_USAGE.getName())
            && options.getLong(Field.QUOTA.getName())
                < user.getLong(Field.SAMPLE_USAGE.getName())) {
          sendError(
              segment,
              message,
              Utils.getLocalizedString(message, "QuotaCannotBeLessThanUsage"),
              HttpStatus.BAD_REQUEST);
          return;
        }
        user.withJSON(Field.OPTIONS.getName(), options.encode());
        // check if current session storage has been disabled. if so switch to some other one
        // if options is updated by admin - no sessionId is provided
        String currentStorage = null;
        if (Utils.isStringNotNullOrEmpty(sessionId)) {
          Item session = Sessions.getSessionById(sessionId);
          if (session != null) {
            JsonObject storageObject = new JsonObject(session.getJSON(Field.F_STORAGE.getName()));
            if (!storageObject.isEmpty()) {
              currentStorage = StorageType.getStorageType(
                      storageObject.getString(Field.STORAGE_TYPE.getName()))
                  .name()
                  .toLowerCase();
            }
          }
        }
        if (currentStorage == null) {
          JsonObject storageObject = new JsonObject(user.getJSON(Field.F_STORAGE.getName()));
          if (!storageObject.isEmpty()) {
            currentStorage = StorageType.getStorageType(
                    new JSONObject(user.getJSON(Field.F_STORAGE.getName()))
                        .getString(Field.STORAGE_TYPE.getName()))
                .name()
                .toLowerCase();
          }
        }
        JsonObject optionStorages = options.getJsonObject(Field.STORAGES.getName());
        if (!Utils.isStringNotNullOrEmpty(currentStorage)
            || (optionStorages.containsKey(currentStorage)
                && !optionStorages.getBoolean(currentStorage))) { // disabled by options
          if (!optionStorages.containsKey("samples")
              || optionStorages.getBoolean("samples")) { // samples enabled
            user.withJSON(Field.F_STORAGE.getName(), sampleStorageObject);
            if (Utils.isStringNotNullOrEmpty(sessionId)) {
              Sessions.setCurrentStorage(userId, sessionId, sampleStorageObject);
            }
          } else {
            for (Item account : ExternalAccounts.getAllExternalAccountsForUser(userId)) {
              String sType = account.getString(Field.F_TYPE.getName()).toLowerCase();
              if (!optionStorages.containsKey(sType) || optionStorages.getBoolean(sType)) {
                String fstorage = "{\"storageType\" : \""
                    + account.getString(Field.F_TYPE.getName()) + "\", \"id\" : \""
                    + account.getString(Field.EXTERNAL_ID_LOWER.getName()) + "\"}";
                user.withJSON(Field.F_STORAGE.getName(), fstorage);
                if (Utils.isStringNotNullOrEmpty(sessionId)) {
                  Sessions.setCurrentStorage(userId, sessionId, fstorage);
                }
                break;
              }
            }
          }
        }

      } catch (Exception e) {
        sendError(segment, message, e.getLocalizedMessage(), HttpStatus.BAD_REQUEST, e);
      }
    }
    String bgColor = null;
    if (preferences != null) {
      if (preferences.isEmpty()) {
        user.removeAttribute(Field.PREFERENCES.getName());
      } else {
        try {
          final String EMAIL_NOTIFICATIONS = "frequencyOfGettingEmailNotifications";
          if (preferences.containsKey(EMAIL_NOTIFICATIONS)) {
            JsonObject checkFrequencyPreferences = new JsonObject()
                .put(
                    EMAIL_NOTIFICATIONS,
                    Arrays.asList("Hourly", "Daily", "Never", "Immediately_on_change"));
            if (!checkFrequencyPreferences
                .getJsonArray(EMAIL_NOTIFICATIONS)
                .contains(preferences.getString(EMAIL_NOTIFICATIONS))) {
              preferences.remove(EMAIL_NOTIFICATIONS);
            }
          }
          JsonObject userPreferences = user.hasAttribute(Field.PREFERENCES.getName())
              ? new JsonObject(user.getJSON(Field.PREFERENCES.getName()))
              : new JsonObject();
          final String preferencesDisplay = "preferences_display";
          final String bgColorString = "graphicswinmodelbackgrndcolor";
          if (preferences.containsKey(preferencesDisplay)) {
            String newBgColor =
                preferences.getJsonObject(preferencesDisplay).getString(bgColorString);
            if (userPreferences.containsKey(preferencesDisplay)) {
              String currentBgColor =
                  userPreferences.getJsonObject(preferencesDisplay).getString(bgColorString);
              if (Utils.isStringNotNullOrEmpty(currentBgColor)
                  && Utils.isStringNotNullOrEmpty(newBgColor)
                  && !currentBgColor.equals(newBgColor)) {
                bgColor = newBgColor;
              }
            }
          }
          user.withJSON(
              Field.PREFERENCES.getName(),
              userPreferences.mergeIn(preferences, true).encode());
        } catch (Exception ex) {
          sendError(
              segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
        }
      }
    }
    if (isTrialShown != null) {
      user.withBoolean(Field.IS_TRIAL_SHOWN.getName(), isTrialShown);
    }
    if (showRecent != null) {
      user.withBoolean(Field.SHOW_RECENT.getName(), showRecent);
    }
    if (notificationBarShowed != null) {
      user.withLong(Field.NOTIFICATION_BAR_SHOWED.getName(), notificationBarShowed);
    }
    if (fileFilter != null) {
      String opt = user.getJSON(Field.OPTIONS.getName());
      JsonObject json =
          opt != null ? new JsonObject(opt) : config.getProperties().getDefaultUserOptionsAsJson();
      json.put(Field.FILE_FILTER.getName(), fileFilter);
      user.withJSON(Field.OPTIONS.getName(), json.encode());
    }
    try {
      Users.update(userId, user);
      // not updating storage for other sessions
      user.removeAttribute(Field.F_STORAGE.getName());
      for (Iterator<Item> it = Sessions.getAllUserSessions(userId); it.hasNext(); ) {
        Item session = it.next();
        if (session != null) {
          Item newSessionInfo = Sessions.mergeSessionWithUserInfo(session, user);
          // updating many attributes together, using putItem here
          Sessions.saveSession(sessionId, newSessionInfo);
        }
      }
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    // confirm new email
    if (email != null) {
      eb_send(
          segment,
          MailUtil.address + ".confirmEmail",
          new JsonObject()
              .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName()))
              .put(Field.USER_ID.getName(), userId)
              .put(Field.NEW_EMAIL.getName(), email));
    }
    if (send) {
      sendOK(segment, message, mills);
    }
    recordExecutionTime("updateProfile", System.currentTimeMillis() - mills);
    if (locale != null && !locale.trim().isEmpty() || options != null) {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "updateProfile")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                WebSocketManager.address
                    + (options != null ? ".optionsChanged" : ".languageSwitch"),
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.SESSION_ID.getName(), sessionId));
          });
    }
    if (Objects.nonNull(bgColor)) {
      String finalBgColor = bgColor;
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "updateBackgroundColor")
          .run((Segment blockingSegment) -> {
            eb_send(
                blockingSegment,
                WebSocketManager.address + ".backgroundColorChanged",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.SESSION_ID.getName(), sessionId)
                    .put(Field.BACKGROUND_COLOR.getName(), finalBgColor));
          });
    }
  }

  private void doDeleteUser(Message<JsonObject> message) {
    String token = message.body().getString(Field.TOKEN.getName());
    if (token == null || token.trim().isEmpty()) {
      Entity segment = XRayManager.createSegment(operationGroup, message);
      String userId = message.body().getString(Field.USER_ID.getName());
      eb_send(
          segment,
          MailUtil.address + ".notifyUserErase",
          new JsonObject().put(Field.USER_ID.getName(), userId));
      deleteUser(segment, message);
    } else {
      deleteUserCheck(message);
    }
  }

  private void deleteUserCheck(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long millis = System.currentTimeMillis();
    String token = message.body().getString(Field.TOKEN.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    Item request = Users.getUserDeleteRequest(userId, token);
    if (request == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UnknownRequestToken"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    handleExistingDeleteRequest(message, segment, millis, token, userId, request);
  }

  private void handleExistingDeleteRequest(
      Message<JsonObject> message,
      Entity segment,
      long millis,
      String token,
      String userId,
      Item request) {
    @NonNls JobStatus status = JobStatus.findStatus(request.getString(Field.STATUS.getName()));
    if (JobStatus.IN_PROGRESS.equals(status)) {
      sendOK(
          segment,
          message,
          new JsonObject()
              .put(Field.STATUS_CODE.getName(), HttpStatus.ACCEPTED)
              .put(Field.TOKEN.getName(), token),
          millis);
      return;
    }
    if (JobStatus.ERROR.equals(status) || JobStatus.UNKNOWN.equals(status)) {
      sendError(
          segment,
          message,
          request.getString(Field.ERROR.getName()),
          HttpStatus.INTERNAL_SERVER_ERROR);
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("removeUserDeleteRequest")
          .runWithoutSegment(() -> Users.deleteUserDeleteRequest(userId, token));
      return;
    }
    Users.deleteUserDeleteRequest(userId, token);
    sendOK(segment, message);
  }

  private void cleanUpSession(Item session) {
    Sessions.cleanUpSession(this, session);
  }

  private void deleteUser(Entity segment, Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.USER_ID.getName());
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(segment, message, Utils.getLocalizedString(message, "FL3"), HttpStatus.NOT_FOUND);
      UsersList.getNewUserList();
      return;
    }
    String greetingName = MailUtil.getDisplayName(user);
    // if deletion has already been requested
    Iterator<Item> it = Users.checkUserDeleteRequest(userId);
    if (it.hasNext()) {
      Item deleteRequest = it.next();
      String token = deleteRequest.getString(Field.ENCAPSULATED_ID.getName());
      handleExistingDeleteRequest(message, segment, mills, token, userId, deleteRequest);
      return;
    }
    // create a token and release the client
    String uuid = Users.saveUserDeleteRequest(userId);
    sendOK(segment, message, new JsonObject().put(Field.TOKEN.getName(), uuid), mills);

    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("handleDeleteUser")
        .run((Segment blockingSegment) -> {
          try {
            // let's do this first to ensure that user is actually removed
            UsersList.deleteFromUserList(userId);
            eb_send(
                blockingSegment,
                SimpleFluorine.address + ".eraseUser",
                message.body().put(Field.USER_ID.getName(), userId));

            // erase external accounts
            Iterator<Item> accounts =
                ExternalAccounts.getAllExternalAccountsForUser(userId).iterator();
            List<PrimaryKey> accountsToErase = new ArrayList<>();
            accounts.forEachRemaining(account -> accountsToErase.add(new PrimaryKey(
                Field.PK.getName(),
                account.getString(Field.PK.getName()),
                Field.SK.getName(),
                account.getString(Field.SK.getName()))));
            if (!accountsToErase.isEmpty()) {
              List<List<PrimaryKey>> list = Utils.splitList(accountsToErase, 25);
              list.forEach(ExternalAccounts::batchRemoveAccounts);
            }
            // erase sessions
            Sessions.getAllUserSessions(userId).forEachRemaining(this::cleanUpSession);
            // erase new shared links
            PublicLink.deleteUserNewSharedLinks(userId);
            // erase user templates (Function to be implemented)
            Templates.deleteTemplatesByType(userId, Templates.TemplateType.USER, s3Regional);
            // delete user notifications
            Users.deleteUserNotifications(userId);
            // delete user subscriptions
            SubscriptionsDao.deleteUserSubscriptions(userId);
            // delete user from DB
            Users.deleteUser(userId);

            Users.updateUserDeleteRequest(userId, uuid, JobStatus.SUCCESS.name(), null);

            eb_send(
                blockingSegment,
                MailUtil.address + ".notifyDeleteAccount",
                new JsonObject()
                    .put(Field.ID.getName(), userId)
                    .put("greetingName", greetingName)
                    .put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()))
                    .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName())));

            Iterator<Item> comments = Comments.updateDeletedUserComments(userId);
            // sending ws notification for all comments change
            while (comments.hasNext()) {
              Item comment = comments.next();
              String fileId = comment
                  .getString(Field.PK.getName())
                  .substring(comment.getString(Field.PK.getName()).indexOf("#") + 1);
              String skValue = comment.getString(Field.SK.getName());
              String threadId =
                  skValue.contains("#") ? skValue.substring(0, skValue.indexOf("#")) : skValue;
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".commentsUpdate",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(Field.TIMESTAMP.getName(), GMTHelper.utcCurrentTime())
                      .put(Field.THREAD_ID.getName(), threadId)
                      .put(Field.DELETED_USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "update")
                      .put(Field.AUTHOR.getName(), comment.getString(Field.AUTHOR.getName())));
            }
          } catch (MultiObjectDeleteException ex) {
            // exception needs to be debug later
          } catch (Exception e) {
            String error =
                (e.getLocalizedMessage() == null) ? emptyString : e.getLocalizedMessage();
            error += ExceptionUtils.getStackTrace(e);
            log.error("Error on deleting user", e);
            Users.updateUserDeleteRequest(userId, uuid, JobStatus.ERROR.name(), error);
          } finally {
            // Always do this to make sure we have proper list
            UsersList.getNewUserList();
          }
        });
    recordExecutionTime("deleteUser", System.currentTimeMillis() - mills);
  }

  private void doGetUsers(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String newPageToken = null;
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String pageToken = jsonObject.getString(Field.PAGE_TOKEN.getName());
    UsersPagination pagination = new UsersPagination();
    pagination.getPageInfo(pageToken);
    Iterator<Item> users = Users.getLimitedUsers(pagination);
    if (users.hasNext()) {
      newPageToken = pagination.saveNewPageInfo(pagination.getNextPageToken());
    }
    JsonArray results = new JsonArray();
    Item user;
    while (users.hasNext()) {
      user = users.next();
      JsonObject json = Users.userToJsonWithoutGoogleId(
          user,
          config.getProperties().getEnterprise(),
          config.getProperties().getInstanceOptionsAsJson(),
          config.getProperties().getDefaultUserOptionsAsJson(),
          config.getProperties().getDefaultLocale(),
          config.getProperties().getUserPreferencesAsJson(),
          config.getProperties().getDefaultCompanyOptionsAsJson());

      if (user.hasAttribute(Field.USER_EMAIL.getName())
          && user.hasAttribute(Field.F_NAME.getName())) {
        String username = (user.get(Field.F_NAME.getName()) != null
                ? user.get(Field.F_NAME.getName())
                : emptyString)
            + ((user.hasAttribute(Field.SURNAME.getName())
                    && user.get(Field.SURNAME.getName()) != null)
                ? Users.spaceString + user.get(Field.SURNAME.getName())
                : emptyString);
        if (username.trim().isEmpty()) {
          username = user.getString(Field.USER_EMAIL.getName());
        }
        json.put(Field.USERNAME.getName(), username);
        if (user.hasAttribute("complianceStatus") || user.hasAttribute("complianceTestResult")) {
          JsonObject compliance = new JsonObject();
          if (user.hasAttribute("complianceStatus")) {
            compliance.put(Field.STATUS.getName(), user.getString("complianceStatus"));
          }
          if (user.hasAttribute("complianceTestResult")) {
            compliance.put("testResult", user.getString("complianceTestResult"));
          }
          json.put("compliance", compliance);
        }
        if (user.hasAttribute(Field.SAMPLE_USAGE.getName())) {
          json.put(Field.USAGE.getName(), user.getLong(Field.SAMPLE_USAGE.getName()));
        } else {
          json.put(Field.USAGE.getName(), 0);
        }
        results.add(json);
      }
    }
    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.RESULTS.getName(), results)
            .put(Field.PAGE_TOKEN.getName(), newPageToken),
        mills);
    recordExecutionTime("getUsers", System.currentTimeMillis() - mills);
  }

  private void doChangeRoles(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    if (userId == null) {
      return;
    }
    JsonArray add = message.body().getJsonArray("add");
    JsonArray remove = message.body().getJsonArray("remove");

    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.BAD_REQUEST);
      return;
    }

    Set<String> roles = new HashSet<>(user.getList(Field.ROLES.getName()));
    if (add != null) {
      roles.addAll(add.getList());
      Users.saveUserAdmin(userId);
    }
    if (remove != null) {
      roles.removeAll(remove.getList());
      List<String> admins = Users.getAllAdmins();
      if (Utils.isListNotNullOrEmpty(admins) && admins.contains(userId)) {
        Users.removeUserAdmin(userId);
      }
    }
    try {
      Users.setUserRoles(userId, new ArrayList<>(roles));
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    sendOK(segment, message, mills);
    recordExecutionTime("updateRoles", System.currentTimeMillis() - mills);
  }

  private void doFindUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    Pattern pattern;
    try {
      pattern = Pattern.compile(message.body().getString(Field.PATTERN.getName()));
    } catch (Exception e) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "ThePatternIsIncorrect"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    Iterator<Item> users = UsersList.findUsers(pattern.pattern());
    JsonArray results = new JsonArray();
    Item user;
    while (users.hasNext()) {
      user = users.next();
      user.removeAttribute(Field.HA1_EMAIL.getName());
      JsonObject json = Users.userToJsonWithoutGoogleId(
          user,
          config.getProperties().getEnterprise(),
          config.getProperties().getInstanceOptionsAsJson(),
          config.getProperties().getDefaultUserOptionsAsJson(),
          config.getProperties().getDefaultLocale(),
          config.getProperties().getUserPreferencesAsJson(),
          config.getProperties().getDefaultCompanyOptionsAsJson());
      if (user.hasAttribute(Field.USER_EMAIL.getName())
          && user.hasAttribute(Field.F_NAME.getName())) {
        String username = (user.get(Field.F_NAME.getName()) != null
                ? user.get(Field.F_NAME.getName())
                : emptyString)
            + ((user.hasAttribute(Field.SURNAME.getName())
                    && user.get(Field.SURNAME.getName()) != null)
                ? Users.spaceString + user.get(Field.SURNAME.getName())
                : emptyString);
        if (username.trim().isEmpty()) {
          username = user.getString(Field.USER_EMAIL.getName());
        }
        json.put(Field.USERNAME.getName(), username);
      }
      if (user.hasAttribute(Field.SAMPLE_USAGE.getName())) {
        json.put(Field.USAGE.getName(), user.getLong(Field.SAMPLE_USAGE.getName()));
      } else {
        json.put(Field.USAGE.getName(), 0);
      }
      results.add(json);
    }
    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    recordExecutionTime("findUser", System.currentTimeMillis() - mills);
  }

  private void doGetUsersToMention(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String suppliedPattern = message.body().getString(Field.PATTERN.getName());
    String fileId = message.body().getString(Field.FILE_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String storageType = message.body().getString(Field.STORAGE_TYPE.getName());
    if (isStorageDisabled(segment, message, StorageType.getStorageType(storageType), userId)) {
      return;
    }
    boolean includeMyself = message.body().getBoolean("includeMyself");
    boolean isMention = message.body().getBoolean("isMention");
    JsonArray results = new JsonArray();
    String ownerId = null;
    int limit = maxMentionCacheSize;
    try {
      if (Utils.isStringNotNullOrEmpty(suppliedPattern) && suppliedPattern.equals("@")) {
        // if client sends ONLY "@" for the empty pattern, then we should treat it as empty
        suppliedPattern = emptyString;
      }
      if (Utils.isStringNotNullOrEmpty(suppliedPattern)) {
        // to handle cases with space (firstName and lastName) in the pattern
        suppliedPattern = URLDecoder.decode(suppliedPattern, StandardCharsets.UTF_8);
      }
      // add owner at the top, only while mentioning
      if (!isMention) {
        includeMyself = false;
      } else {
        Item metadata = FileMetaData.getMetaData(fileId, storageType);
        if (metadata != null && metadata.hasAttribute(Field.OWNER_ID.getName())) {
          ownerId = metadata.getString(Field.OWNER_ID.getName());
          Item user = Users.getUserById(ownerId);
          if (user != null) {
            JsonObject owner = Users.formatUserForMention(user);
            if (!ownerId.equals(userId)) {
              if (isAddOwner(suppliedPattern, owner)) {
                results.add(owner);
              }
            } else if (includeMyself) {
              owner.put(
                  Field.USERNAME.getName(), owner.getString(Field.USERNAME.getName()) + " (You)");
              if (isAddOwner(suppliedPattern, owner)) {
                results.add(owner);
              }
            }
          }
        }
      }
      results.addAll(getUsersToMentionFromCache(
          fileId, limit - results.size(), storageType, userId, suppliedPattern, ownerId));
      // if no data in cache and no pattern, get data from usersList with same org
      if (suppliedPattern.isEmpty() && results.isEmpty()) {
        results = Users.getUserOrgList(userId, limit, null, null);
      }
      // if data in cache < 10, get remaining from usersList with same org
      if (results.size() < limit) {
        results.addAll(
            Users.getUserOrgList(userId, limit - results.size(), suppliedPattern, results));
      }
      // sort the list according to the rules when pattern is specified
      if (Utils.isStringNotNullOrEmpty(suppliedPattern)) {
        List<JsonObject> usersToMention = results.getList();
        // sort alphabetically
        Users.sortMentionUsersList(usersToMention);
        String finalSuppliedPattern = suppliedPattern;
        // sort index of pattern within name
        usersToMention.sort((obj1, obj2) -> sortValuesByIndexOfPattern(
            obj1.getString(Field.NAME.getName()),
            obj2.getString(Field.NAME.getName()),
            finalSuppliedPattern));
        // sort index of pattern within surname
        usersToMention.sort((obj1, obj2) -> sortValuesByIndexOfPattern(
            obj1.getString(Field.SURNAME.getName()),
            obj2.getString(Field.SURNAME.getName()),
            finalSuppliedPattern));
        // sort index of pattern within email
        usersToMention.sort((obj1, obj2) -> sortValuesByIndexOfPattern(
            obj1.getString(Field.EMAIL.getName()),
            obj2.getString(Field.EMAIL.getName()),
            finalSuppliedPattern));
        results = new JsonArray(usersToMention);
      }
      if (includeMyself) {
        int selfIndex = Iterables.indexOf(
            results,
            obj -> ((JsonObject) obj).getString(Field.USERNAME.getName()).endsWith("(You)"));
        if (selfIndex == -1) {
          Item user = Users.getUserById(userId);
          if (Objects.nonNull(user)
              && (!Utils.isStringNotNullOrEmpty(suppliedPattern)
                  || Users.isPatternMatched(
                      suppliedPattern,
                      user.getString(Field.F_NAME.getName()),
                      user.getString(Field.SURNAME.getName()),
                      user.getString(Field.USER_EMAIL.getName())))) {
            JsonObject userObject = Users.formatUserForMention(user);
            results.add(0, userObject);
            if (results.size() > limit) {
              results.remove(limit);
            }
          }
        } else {
          JsonObject selfObject = results.getJsonObject(selfIndex);
          String userName = selfObject.getString(Field.USERNAME.getName());
          selfObject.put(
              Field.USERNAME.getName(), userName.substring(0, userName.indexOf("(You)") - 1));
          results.set(selfIndex, selfObject);
        }
      }
      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }
    recordExecutionTime("mentionUser", System.currentTimeMillis() - mills);
  }

  private int sortValuesByIndexOfPattern(String value1, String value2, String pattern) {
    if (!Utils.isStringNotNullOrEmpty(value1) && !Utils.isStringNotNullOrEmpty(value2)) {
      return 0;
    }
    String finalValue1 = (Objects.nonNull(value1) ? value1.toLowerCase() : emptyString);
    String finalValue2 = (Objects.nonNull(value2) ? value2.toLowerCase() : emptyString);
    String finalPattern = pattern.toLowerCase();
    if (!finalValue1.contains(finalPattern) && !finalValue2.contains(finalPattern)) {
      return 0;
    } else if (!finalValue1.contains(finalPattern)) {
      return 1;
    } else if (!finalValue2.contains(finalPattern)) {
      return -1;
    }
    return finalValue1.indexOf(finalPattern) - finalValue2.indexOf(finalPattern);
  }

  private JsonArray getUsersToMentionFromCache(
      String fileId, int limit, String storage, String userId, String pattern, String ownerId) {
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return new JsonArray();
    }
    Set<MentionUserInfo> mentionedUsers = new LinkedHashSet<>();
    Set<MentionUserInfo> cachedUsersToUpdate = new HashSet<>();
    Set<String> cachedUsersToRemove = new HashSet<>();
    String fileIdSk = null, storageTypeSk = null;
    // query to check if user's file has cache.
    if (Utils.isStringNotNullOrEmpty(fileId)) {
      fileIdSk = Mentions.MentionsType.File.label + fileId;
      Set<MentionUserInfo> files = Mentions.getStorageOrFileCache(
          Mentions.MentionsType.User.label + userId,
          fileIdSk,
          cachedUsersToUpdate,
          cachedUsersToRemove,
          pattern);
      if (Utils.isListNotNullOrEmpty(files)) {
        mentionedUsers.addAll(files);
      }
    }

    // query to check if user's storage has cache.
    if (Utils.isStringNotNullOrEmpty(storage) && mentionedUsers.size() < limit) {
      storageTypeSk = Mentions.MentionsType.Storage.label + storage;
      Set<MentionUserInfo> storages = Mentions.getStorageOrFileCache(
          Mentions.MentionsType.User.label + userId,
          storageTypeSk,
          cachedUsersToUpdate,
          cachedUsersToRemove,
          pattern);
      if (Utils.isListNotNullOrEmpty(storages)) {
        mentionedUsers.addAll(storages);
      }
    }

    // query to check if whole user has cache.
    if (mentionedUsers.size() < limit) {
      Set<MentionUserInfo> users = Mentions.getUserCache(
          Mentions.MentionsType.User.label + userId,
          fileIdSk,
          storageTypeSk,
          cachedUsersToUpdate,
          cachedUsersToRemove,
          pattern);
      if (Utils.isListNotNullOrEmpty(users)) {
        mentionedUsers.addAll(users);
      }
    }

    updateUserMentionCache(userId, cachedUsersToUpdate, cachedUsersToRemove);

    if (!Utils.isListNotNullOrEmpty(mentionedUsers)) {
      return new JsonArray();
    }

    // not adding owner again if already added
    mentionedUsers.removeIf(
        user -> Utils.isStringNotNullOrEmpty(ownerId) && user.id.equals(ownerId));
    if (mentionedUsers.size() > limit) {
      int size = mentionedUsers.size();
      while (size > limit) {
        Optional<MentionUserInfo> optional =
            mentionedUsers.stream().reduce((first, second) -> second);
        optional.ifPresent(mentionedUsers::remove);
        size--;
      }
    }
    return new JsonArray(
        mentionedUsers.stream().map(MentionUserInfo::toJson).collect(Collectors.toList()));
  }

  private void doAddStorage(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    String traceId = message.body().getString(Field.TRACE_ID.getName());
    long mills = System.currentTimeMillis();
    String redirectURL = getRequiredString(segment, "redirectURL", message, message.body());
    final String clientId = getRequiredString(segment, "clientId", message, message.body());
    final StorageType storageType = StorageType.getStorageType(
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body()));
    if (isStorageDisabled(
        segment, message, storageType, message.body().getString(Field.USER_ID.getName()))) {
      return;
    }
    final String oauthURL = config.getProperties().getOauthUrl();
    String requestId = null;
    String finalURL = null;

    switch (storageType) {
      case BOX:
      case DROPBOX:
      case ONSHAPE:
      case ONSHAPEDEV:
      case ONSHAPESTAGING:
      case TRIMBLE:
      case ONEDRIVE:
      case ONEDRIVEBUSINESS:
        String storageName = storageType.name().toLowerCase();
        if (storageType.equals(ONEDRIVEBUSINESS)) {
          storageName = ONEDRIVE.name().toLowerCase();
        }
        HttpResponse<JsonNode> oauthRequest = AWSXRayUnirest.post(
                oauthURL + "?type=" + storageName + "&mode=register", "Oauth.Onedrive")
            .header("Content-type", "application/json")
            .header("X-Amzn-Trace-Id", "Root=" + traceId)
            .header(Field.URL.getName(), redirectURL)
            .asJson();
        requestId = oauthRequest.getBody().getObject().getString(Field.ENCAPSULATED_ID.getName());
        break;
      default:
        break;
    }
    String oauthRedirectURL =
        oauthURL + "?mode=storage&type=" + storageType.name().toLowerCase();
    String encodedURL = redirectURL;
    String encodedOauthURL = oauthURL;
    try {
      encodedURL = URLEncoder.encode(redirectURL, String.valueOf(StandardCharsets.UTF_8));
      oauthRedirectURL =
          URLEncoder.encode(oauthRedirectURL, String.valueOf(StandardCharsets.UTF_8));
      encodedOauthURL = URLEncoder.encode(oauthURL, String.valueOf(StandardCharsets.UTF_8));
    } catch (UnsupportedEncodingException e) {
      sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST);
    }
    switch (storageType) {
      case BOX:
        finalURL = "https://app.box.com/api/oauth2/authorize?response_type=code&client_id="
            + clientId + "&state=" + requestId;
        break;
      case GDRIVE:
        finalURL = oauthURL + "?type=google&mode=register&clientId=" + clientId
            + "&accessType=offline&url=" + encodedURL;
        break;
      case WEBDAV:
      case NEXTCLOUD:
        break;
      case DROPBOX:
        String properRedirectURL = oauthURL + "?type=dropbox";
        try {
          properRedirectURL =
              URLEncoder.encode(properRedirectURL, String.valueOf(StandardCharsets.UTF_8));
        } catch (UnsupportedEncodingException e) {
          sendError(segment, message, e.getMessage(), HttpStatus.BAD_REQUEST);
        }
        finalURL =
            "https://www.dropbox.com/oauth2/authorize?response_type=code&client_id=" + clientId
                + "&force_reapprove=true&state=" + requestId + "&redirect_uri=" + properRedirectURL
                + "&token_access_type=offline"; // just for a case if we use this
        break;
      case TRIMBLE:
        finalURL = config.getProperties().getTrimbleOauthUrl()
            + "/authorize?response_type=code&client_id=" + clientId + "&scope=openid&state="
            + requestId + "&redirect_uri=" + oauthRedirectURL;
        break;
      case ONSHAPEDEV:
        finalURL =
            "https://demo-c-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id="
                + clientId + "&state=" + requestId + "&redirect_uri=" + oauthRedirectURL;
        break;
      case ONSHAPESTAGING:
        finalURL =
            "https://staging-oauth.dev.onshape.com/oauth/authorize?response_type=code&client_id="
                + clientId + "&state=" + requestId + "&redirect_uri=" + oauthRedirectURL;
        break;
      case ONSHAPE:
        finalURL = "https://oauth.onshape.com/oauth/authorize?response_type=code&client_id="
            + clientId + "&state=" + requestId + "&redirect_uri=" + oauthRedirectURL;
        break;
      case HANCOM:
      case HANCOMSTG:
        finalURL = oauthURL + "?type=" + storageType.name().toLowerCase() + "&mode=register&url="
            + encodedURL;
        break;
      case ONEDRIVE:
        finalURL =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=" + clientId
                + "&response_type=code&redirect_uri=" + encodedOauthURL
                + "&response_mode=query&scope=openid%20User.Read%20files.readwrite"
                + ".all%20offline_access&state=" + requestId;
        break;
      case ONEDRIVEBUSINESS:
        finalURL =
            "https://login.microsoftonline.com/common/oauth2/v2.0/authorize?client_id=" + clientId
                + "&redirect_uri=" + encodedOauthURL
                + "&scope=openid%20User.Read%20sites.read.all%20sites.readwrite.all%20files"
                + ".read%20files.readwrite%20files.readwrite"
                + ".all%20offline_access&response_type=code&state=" + requestId;
        break;
      default:
        sendError(segment, message, "Unable to connect storage", HttpStatus.BAD_REQUEST);
        break;
    }
    sendOK(
        segment,
        message,
        new JsonObject()
            .put("clientId", clientId)
            .put(Field.STORAGE_TYPE.getName(), storageType)
            .put(Field.REQUEST_ID.getName(), requestId)
            .put("finalURL", finalURL),
        mills);
    recordExecutionTime("addStorage", System.currentTimeMillis() - mills);
  }

  private void doUpdateSkeleton(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String userId = getRequiredString(segment, Field.USER_ID.getName(), message, body);
    final StorageType storageType = StorageType.getStorageType(
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, body));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    final boolean confirm = body.containsKey("confirm") ? body.getBoolean("confirm") : false;
    final int version = body.containsKey("version") ? body.getInteger("version") : 0;
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "NoSuchUserInTheDatabase"),
          HttpStatus.NOT_FOUND);
      return;
    }
    int samplesVersion = 0;
    if (user.hasAttribute(Field.SAMPLE_CREATED.getName())
        && user.getBoolean(Field.SAMPLE_CREATED.getName())) {
      if (user.hasAttribute("samplesVersion")) {
        samplesVersion = user.getInt("samplesVersion");
      } else {
        samplesVersion = 1;
      }
    }
    if (!confirm && version != (samplesVersion + 1)) {
      // 412 - precondition failed
      sendError(
          segment,
          message,
          new JsonObject().put("samplesVersion", samplesVersion),
          HttpStatus.PRECONDITION_FAILED);
      return;
    }
    log.info("Starting skeleton update for user: " + user.getString(Field.USER_EMAIL.getName())
        + ", id: " + userId);
    long mills2 = System.currentTimeMillis();
    int finalSamplesVersion = samplesVersion;
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".updateSkeleton",
        new JsonObject().put(Field.USER_ID.getName(), userId),
        event -> {
          log.info("Skeleton update status: " + event.succeeded());
          log.info("Skeleton update finished in: " + (System.currentTimeMillis() - mills2));
          Users.setUserSamples(userId, true);
          Users.setUserSampleVersion(userId, finalSamplesVersion + 1);
          sendOK(segment, message, mills);
          recordExecutionTime("updateSkeleton", System.currentTimeMillis() - mills);
        });
  }

  private void doUpdateCapabilities(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String licenseResponse = body.getString("licenseResponse");
    String graebertId = body.getString(Field.GRAEBERT_ID.getName());
    try {
      Item user = Users.findUserByGraebertId(graebertId);
      JSONArray responseArray = new JSONArray(licenseResponse);
      List<JSONObject> capabilitiesList = responseArray.toList();
      if (user != null) {
        List<Map<String, Object>> finalList = capabilitiesList.stream()
            .map(cap -> new HashMap<String, Object>() {
              {
                put(Field.CREATED.getName(), cap.getLong(Field.CREATED.getName()));
                put(Field.UPDATED.getName(), cap.getLong(Field.UPDATED.getName()));
                put(Field.NAME.getName(), cap.getString(Field.NAME.getName()));
                put(Field.ID.getName(), cap.getInt(Field.ID.getName()));
                if (cap.get("gde") != null) {
                  put("gde", cap.getInt("gde"));
                }

                if (cap.has("capabilityDomains")
                    && !cap.getJSONArray("capabilityDomains").isEmpty()) {
                  List<Map<String, String>> capabilityDomains = (
                      // to minimize warnings here, we need to cast it to List<Object>
                      (List<Object>) cap.getJSONArray("capabilityDomains").toList())
                      .stream()
                          .map(obj -> new HashMap<String, String>() {
                            {
                              put(
                                  Field.ID.getName(),
                                  ((JSONObject) obj).getString(Field.ID.getName()));
                              put("domainName", ((JSONObject) obj).getString("domainName"));
                            }
                          })
                          .collect(Collectors.toList());

                  put("capabilityDomains", capabilityDomains);
                }

                if (cap.has("capabilityLanguages")
                    && !cap.getJSONArray("capabilityLanguages").isEmpty()) {
                  List<Map<String, String>> capabilityLanguages = (
                      // to minimize warnings here, we need to cast it to List<Object>
                      (List<Object>) cap.getJSONArray("capabilityLanguages").toList())
                      .stream()
                          .map(obj -> new HashMap<String, String>() {
                            {
                              put(
                                  Field.ID.getName(),
                                  ((JSONObject) obj).getString(Field.ID.getName()));
                              put("languageCode", ((JSONObject) obj).getString("languageCode"));
                            }
                          })
                          .collect(Collectors.toList());

                  put("capabilityLanguages", capabilityLanguages);
                }
              }
            })
            .collect(Collectors.toList());

        if (user.hasAttribute("capabilities")) {
          if (!user.getList("capabilities").equals(finalList)) {
            Users.setUserCapabilities(user.getString(Users.sk), finalList);
            log.info("User capabilities updated for userId - " + user.getString(Users.sk));
          }
        } else {
          Users.setUserCapabilities(user.getString(Users.sk), finalList);
          log.info("User capabilities added for userId - " + user.getString(Users.sk));
        }
      }
    } catch (Exception ex) {
      log.error(ex);
    }
    sendOK(segment, message, mills);
  }

  private void doGetCapabilities(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String userId = body.getString(Field.USER_ID.getName());
    Item user = Users.getUserById(userId);
    JsonArray results = new JsonArray();

    log.info("Get Capabilities for user " + userId + " | results - "
        + (user != null ? user.asMap().toString() : null));

    if (user != null && user.hasAttribute("capabilities")) {
      List<Map<String, Object>> capabilities = user.getList("capabilities");
      capabilities.forEach(cap -> results.add(new JsonObject(cap)));
    }
    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), results), mills);
  }

  private void doCreateSkeleton(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    final String userId = getRequiredString(segment, Field.USER_ID.getName(), message, body);
    final StorageType storageType = StorageType.getStorageType(
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, body));
    if (isStorageDisabled(segment, message, storageType, userId)) {
      return;
    }
    final boolean force =
        body.containsKey(Field.FORCE.getName()) ? body.getBoolean(Field.FORCE.getName()) : false;
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "NoSuchUserInTheDatabase"),
          HttpStatus.NOT_FOUND);
      return;
    }
    if (!force
        && user.hasAttribute(Field.SAMPLE_CREATED.getName())
        && user.getBoolean(Field.SAMPLE_CREATED.getName())) {
      // 412 - precondition failed
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "SamplesAlreadyCreated"),
          HttpStatus.PRECONDITION_FAILED);
      return;
    }
    log.info("Starting manual skeleton creation for user: "
        + user.getString(Field.USER_EMAIL.getName()) + ", id: " + userId);
    long mills2 = System.currentTimeMillis();
    eb_request(
        segment,
        StorageType.getAddress(storageType) + ".createSkeleton",
        new JsonObject().put(Field.USER_ID.getName(), userId),
        event -> {
          log.info("Skeleton creation status: " + event.succeeded());
          log.info("Skeleton creation finished in: " + (System.currentTimeMillis() - mills2));
          Users.setUserSamples(userId, true);
          sendOK(segment, message, mills);
          recordExecutionTime("createSkeleton", System.currentTimeMillis() - mills);
        });
  }

  /**
   * If Fluorine verticle doesn't exist - nothing will be done.
   * If Fluorine isn't available for the user - does it matter that it's created anyway?
   *
   * @param email  {String}
   * @param userId {String}
   */
  private void triggerSkeletonCreation(
      Entity parentSegment,
      Message<JsonObject> message,
      final StorageType storageType,
      final String email,
      final String userId) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, message)
        .withName("createSkeleton")
        .run((Segment blockingSegment) -> {
          long mills = System.currentTimeMillis();
          log.info("Starting skeleton creation for user: " + email + ", id: " + userId);
          eb_request(
              blockingSegment,
              StorageType.getAddress(storageType) + ".createSkeleton",
              new JsonObject().put(Field.USER_ID.getName(), userId),
              event -> {
                log.info("Skeleton creation status: " + event.succeeded());
                log.info("Skeleton creation finished in: " + (System.currentTimeMillis() - mills));
                Users.setUserSamples(userId, true);
              });
        });
  }

  private void updateUserMentionCache(
      String userId, Set<MentionUserInfo> cachedUsersToUpdate, Set<String> cachedUsersToRemove) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, null, null)
        .withName("updateUserMentionCache")
        .runWithoutSegment(() -> Mentions.updateUserMentionCache(
            Mentions.MentionsType.User.label + userId, cachedUsersToUpdate, cachedUsersToRemove));
  }

  private String getStorageProperties(StorageType storageType, String propertyType) {
    switch (storageType) {
      case SAMPLES: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getSamplesName();
          }
          case "Icon": {
            return config.getProperties().getSamplesIcon();
          }
          case "IconBlack": {
            return config.getProperties().getSamplesIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getSamplesIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getSamplesIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case BOX: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getBoxName();
          }
          case "Icon": {
            return config.getProperties().getBoxIcon();
          }
          case "IconBlack": {
            return config.getProperties().getBoxIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getBoxIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getBoxIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case GDRIVE: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getGdriveName();
          }
          case "Icon": {
            return config.getProperties().getGdriveIcon();
          }
          case "IconBlack": {
            return config.getProperties().getGdriveIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getGdriveIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getGdriveIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case DROPBOX: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getDropboxName();
          }
          case "Icon": {
            return config.getProperties().getDropboxIcon();
          }
          case "IconBlack": {
            return config.getProperties().getDropboxIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getDropboxIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getDropboxIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case TRIMBLE: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getTrimbleName();
          }
          case "Icon": {
            return config.getProperties().getTrimbleIcon();
          }
          case "IconBlack": {
            return config.getProperties().getTrimbleIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getTrimbleIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getTrimbleIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case WEBDAV: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getWebdavName();
          }
          case "Icon": {
            return config.getProperties().getWebdavIcon();
          }
          case "IconBlack": {
            return config.getProperties().getWebdavIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getWebdavIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getWebdavIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case NEXTCLOUD: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getNextcloudName();
          }
          case "Icon": {
            return config.getProperties().getNextcloudIcon();
          }
          case "IconBlack": {
            return config.getProperties().getNextcloudIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getNextcloudIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getNextcloudIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case SHAREPOINT: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getSharepointName();
          }
          case "Icon": {
            return config.getProperties().getSharepointIcon();
          }
          case "IconBlack": {
            return config.getProperties().getSharepointIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getSharepointIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getSharepointIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case ONEDRIVE: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getOnedriveName();
          }
          case "Icon": {
            return config.getProperties().getOnedriveIcon();
          }
          case "IconBlack": {
            return config.getProperties().getOnedriveIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getOnedriveIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getOnedriveIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case ONSHAPE: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getOnshapeName();
          }
          case "Icon": {
            return config.getProperties().getOnshapeIcon();
          }
          case "IconBlack": {
            return config.getProperties().getOnshapeIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getOnshapeIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getOnshapeIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case ONSHAPEDEV: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getOnshapedevName();
          }
          case "Icon": {
            return config.getProperties().getOnshapedevIcon();
          }
          case "IconBlack": {
            return config.getProperties().getOnshapedevIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getOnshapedevIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getOnshapedevIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case ONSHAPESTAGING: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getOnshapestagingName();
          }
          case "Icon": {
            return config.getProperties().getOnshapestagingIcon();
          }
          case "IconBlack": {
            return config.getProperties().getOnshapestagingIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getOnshapestagingIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getOnshapestagingIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case HANCOM: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getHancomName();
          }
          case "Icon": {
            return config.getProperties().getHancomIcon();
          }
          case "IconBlack": {
            return config.getProperties().getHancomIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getHancomIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getHancomIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      case HANCOMSTG: {
        switch (propertyType) {
          case "Name": {
            return config.getProperties().getHancomstgName();
          }
          case "Icon": {
            return config.getProperties().getHancomstgIcon();
          }
          case "IconBlack": {
            return config.getProperties().getHancomstgIconBlack();
          }
          case "IconPng": {
            return config.getProperties().getHancomstgIconPng();
          }
          case "IconBlackPng": {
            return config.getProperties().getHancomstgIconBlackPng();
          }
          default:
            return emptyString;
        }
      }
      default:
        return emptyString;
    }
  }
}
