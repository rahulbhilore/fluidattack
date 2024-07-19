package com.graebert.storage.security;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.AccessType;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.TrimbleConnect;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.stats.logs.file.FileActions;
import com.graebert.storage.stats.logs.file.FileLog;
import com.graebert.storage.stats.logs.session.SessionLog;
import com.graebert.storage.stats.logs.session.SessionTypes;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.Recents;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.XenonSessions;
import com.graebert.storage.storage.XenonSessions.Mode;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.thumbnails.ThumbnailSubscription;
import com.graebert.storage.users.IntercomConnection;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.TtlUtils;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.HttpStatusCodes;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.RetryPolicy;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class XSessionManager extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.X_SESSION_MANAGER;

  @NonNls
  public static final String address = "sessionmanager";

  private static final String errorSeparator = " : ";
  protected static Logger log = LogManager.getRootLogger();

  public XSessionManager() {}

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".save", this::doSaveSession);
    eb.consumer(address + ".remove", this::doRemoveSession);
    eb.consumer(address + ".check", this::doCheckSession);
    eb.consumer(address + ".get", this::doGetSessions);
    eb.consumer(address + ".update", this::doUpdate);
    eb.consumer(address + ".request", this::doRequestEditSession);
    eb.consumer(address + ".deny", this::doDenyRequest);
    eb.consumer(address + ".getAccountFileSessions", this::doGetAccountFileSessions);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-xsession");
  }

  private void doUpdate(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String device = jsonObject.getString(Field.DEVICE.getName());
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    // Do not validate view-only and save-pending conditions if this is false
    boolean validateSession = jsonObject.getBoolean(Field.VALIDATE_SESSION.getName(), true);
    // if applicant presents in requests - it's downgrade for
    // someone's request. We need to check if it's still alive
    String applicant = jsonObject.getString(Field.APPLICANT_X_SESSION_ID.getName());
    if (Utils.isStringNotNullOrEmpty(applicant)) {
      Item applicantSession = XenonSessions.getSession(fileId, applicant);
      if (applicantSession == null
          || (applicantSession.getLong(Field.TTL.getName())
              < (GMTHelper.utcCurrentTime() / 1000))) {
        sendError(segment, message, HttpStatus.CONFLICT, "XSessionDoesNotExist");
        return;
      }
    }
    boolean shouldBeEditingSession = jsonObject.containsKey(Field.EDIT_REQUIRED.getName())
        ? jsonObject.getBoolean(Field.EDIT_REQUIRED.getName())
        : false;
    if (fileId == null) {
      return;
    }
    // need to decode fileId for DropBox
    StorageType storageType =
        StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName()));
    if (StorageType.DROPBOX.equals(storageType)) {
      fileId = URLDecoder.decode(fileId.replaceAll("%20", "\\+"), StandardCharsets.UTF_8);
    }

    @NonNls
    String userSessionId =
        getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);

    // for admin-session-id - it's always fine
    if (Sessions.AllowAdminSessionId() && userSessionId.equals("admin-session-id")) {
      sendOK(segment, message, new JsonObject().put(Field.FILE_ID.getName(), fileId), mills);
      return;
    }

    String id = jsonObject.getString(Field.X_SESSION_ID.getName());

    Boolean shouldSessionDowngrade = null;
    if (jsonObject.getString("invert") != null
        && Boolean.parseBoolean(jsonObject.getString(
            "invert"))) // to support old commander's code (can be removed in 1.61)
    {
      shouldSessionDowngrade = true;
    }
    if (jsonObject.getString("downgrade") != null) {
      shouldSessionDowngrade = Boolean.valueOf(jsonObject.getString("downgrade"));
    }

    String versionId = jsonObject.getString(Field.VERSION_ID.getName());
    Item item;
    if (id != null && !id.trim().isEmpty()) {
      item = XenonSessions.getSession(fileId, id);
    } else {
      item = XenonSessions.getEditingSession(fileId, userSessionId);
    }
    try {
      if (item == null
          || (shouldBeEditingSession
              && !Mode.getMode(item.getString(Field.MODE.getName())).equals(Mode.EDIT))) {
        boolean viewOnlyCase =
            item != null && Mode.getMode(item.getString(Field.MODE.getName())).equals(Mode.VIEW);

        JsonObject resultObject = new JsonObject();

        KudoErrorCodes errorCode = KudoErrorCodes.FILE_SESSION_EXPIRED;
        String errorStringId = emptyString;
        if (item == null) {
          if (!Utils.isStringNotNullOrEmpty(device)
              || !device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name())) {
            resultObject.put(
                Field.CONFLICTING_FILE_REASON.getName(),
                ConflictingFileReason.SESSION_NOT_FOUND.name());
          }
          errorStringId = "FileSessionHasExpired";
        } else if (viewOnlyCase) {
          log.debug("VIEWONLY - File will be saved to conflicting file for fileId - " + fileId
              + " | storageType - " + storageType + " | xSessionId - " + id + " | userId - "
              + item.getString(Field.USER_ID.getName()));
          if (!Utils.isStringNotNullOrEmpty(device)
              || !device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name())) {
            resultObject.put(
                Field.CONFLICTING_FILE_REASON.getName(),
                ConflictingFileReason.VIEW_ONLY_SESSION.name());
          }
          errorCode = KudoErrorCodes.VIEW_ONLY_SESSION;
          errorStringId = "viewOnlySessionFoundOnEditing";
        }
        if (!viewOnlyCase || validateSession) {
          throw new KudoFileException(
              Utils.getLocalizedString(message, errorStringId),
              errorCode,
              (Utils.isStringNotNullOrEmpty(device)
                      && device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name()))
                  ? HttpStatus.CONFLICT
                  : HttpStatus.BAD_REQUEST,
              "FileSessionHasExpired",
              resultObject);
        }
      }

      if (validateSession
          && item.hasAttribute(Field.STATE.getName())
          && item.getString(Field.STATE.getName())
              .equals(XenonSessions.SessionState.SAVE_PENDING.name())) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "FL19"),
            KudoErrorCodes.FILE_IS_SAVE_PENDING,
            (Utils.isStringNotNullOrEmpty(device)
                    && device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name()))
                ? HttpStatus.CONFLICT
                : HttpStatus.BAD_REQUEST,
            "FL19",
            (Utils.isStringNotNullOrEmpty(device)
                    && device.equalsIgnoreCase(AuthManager.ClientType.TOUCH.name()))
                ? null
                : new JsonObject()
                    .put(
                        Field.CONFLICTING_FILE_REASON.getName(),
                        ConflictingFileReason.FILE_IS_SAVE_PENDING.name()));
      }
      String sessionState = jsonObject.getString(Field.SESSION_STATE.getName());
      XenonSessions.SessionState state = XenonSessions.SessionState.getSessionState(sessionState);
      String newMode = null;
      boolean isModeUpdateRequired = false;
      if (shouldSessionDowngrade != null) {
        newMode = shouldSessionDowngrade
            ? Mode.VIEW.name().toLowerCase()
            : Mode.EDIT.name().toLowerCase();
        String mode = item.getString(Field.MODE.getName());
        if (!mode.equals(newMode)) {
          isModeUpdateRequired = true;
          // if we're trying to get editing session, check if there are no running editing sessions
          if (Mode.EDIT.equals(Mode.getMode(newMode))) {
            Iterator<Item> activeSessions = XenonSessions.getActiveSessions(fileId, Mode.EDIT);
            if (activeSessions.hasNext()) {
              JsonArray editors = new JsonArray();
              Item editor = activeSessions.next();
              editors.add(new JsonObject()
                  .put(Field.X_SESSION_ID.getName(), editor.getString(Field.SK.getName()))
                  .put(Field.EMAIL.getName(), editor.getString(Field.USERNAME.getName()))
                  .put(Field.USERNAME.getName(), emptyString)
                  .put(
                      "me",
                      jsonObject
                          .getString(Field.USER_ID.getName())
                          .equals(editor.getString(Field.USER_ID.getName()))));

              throw new KudoFileException(
                  Utils.getLocalizedString(message, "ThereIsExistingEditingSession"),
                  KudoErrorCodes.EXISTING_EDITING_SESSION,
                  HttpStatusCodes.PRECONDITION_FAILED,
                  "ThereIsExistingEditingSession",
                  new JsonObject().put(Field.EDITORS.getName(), editors));
            }
          }

          // if versionId is specified check if it's equal to latest file's version
          if (!shouldSessionDowngrade && versionId != null) {
            final String finalFileId = fileId;
            final Item finalItem = item;
            final String finalNewMode = newMode;
            eb_request(
                segment,
                StorageType.getAddress(storageType) + ".getLatestVersionId",
                jsonObject,
                event -> {
                  try {
                    if (event.succeeded()) {
                      String serverVersionId = ((JsonObject) event.result().body())
                          .getString(Field.VERSION_ID.getName());

                      if (!versionId.equals(serverVersionId)) {
                        throw new KudoFileException(
                            Utils.getLocalizedString(message, "VersionConflict"),
                            KudoErrorCodes.FILE_VERSION_CONFLICT,
                            HttpStatus.CONFLICT,
                            "VersionConflict");
                      }
                      update(
                          segment,
                          message,
                          finalFileId,
                          finalItem,
                          finalNewMode,
                          false,
                          true,
                          storageType,
                          state,
                          mills);
                    } else {
                      throw new KudoFileException(
                          event.cause().getLocalizedMessage(),
                          KudoErrorCodes.FILE_LATEST_VERSION_ERROR,
                          HttpStatus.BAD_REQUEST);
                    }
                  } catch (KudoFileException kfe) {
                    sendError(
                        segment,
                        message,
                        kfe.toResponseObject(),
                        kfe.getHttpStatus(),
                        kfe.getErrorId());
                  }
                });
            return;
          }
        }
      }
      update(
          segment,
          message,
          fileId,
          item,
          newMode,
          shouldSessionDowngrade,
          isModeUpdateRequired,
          storageType,
          state,
          mills);
    } catch (KudoFileException kfe) {
      JsonObject errorResponse = kfe.toResponseObject();
      if (!Utils.isStringNotNullOrEmpty(device)
          || device.equals(AuthManager.ClientType.COMMANDER.name())) {
        // https://graebert.atlassian.net/browse/XENON-59765
        errorResponse.remove(Field.MESSAGE.getName());
      }
      if (kfe.getResultObject() != null) {
        errorResponse.mergeIn(kfe.getResultObject());
      }
      sendError(segment, message, errorResponse, kfe.getHttpStatus(), kfe.getErrorId());
    }
  }

  private void update(
      Entity segment,
      Message<JsonObject> message,
      String fileId,
      Item xenonSession,
      String newMode,
      Boolean shouldSessionDowngrade,
      boolean isModeUpdateRequired,
      StorageType storageType,
      XenonSessions.SessionState state,
      long mills) {

    // if upgrade - try to checkout TC file; otherwise - checkin
    if (shouldSessionDowngrade != null && StorageType.TRIMBLE.equals(storageType)) {
      Future<String> lockFuture = checkLockForTrimbleConnect(
          segment,
          message,
          false,
          (shouldSessionDowngrade)
              ? TrimbleConnect.TCFileStatus.CHECKIN
              : TrimbleConnect.TCFileStatus.CHECKOUT);
      lockFuture
          .onSuccess(event -> update(
              segment,
              message,
              newMode,
              shouldSessionDowngrade,
              isModeUpdateRequired,
              fileId,
              xenonSession,
              false,
              state,
              mills))
          .onFailure(f -> manageTrimbleCheckError(segment, message, f, fileId));
    } else {
      update(
          segment,
          message,
          newMode,
          shouldSessionDowngrade,
          isModeUpdateRequired,
          fileId,
          xenonSession,
          false,
          state,
          mills);
    }
  }

  private void update(
      Entity segment,
      Message<JsonObject> message,
      String newMode,
      Boolean shouldSessionDowngrade,
      boolean isModeUpdateRequired,
      String fileId,
      Item xenonSession,
      boolean checkinFailed,
      XenonSessions.SessionState state,
      long mills) {
    Entity parent = XRayManager.createStandaloneSegment(operationGroup, segment, "update");
    JsonArray fileChangesInfo = message.body().getJsonArray(Field.FILE_CHANGES_INFO.getName());
    boolean changesAreSaved = message.body().getBoolean(Field.CHANGES_ARE_SAVED.getName());
    boolean isConflicted = message.body().getBoolean(Field.IS_CONFLICTED.getName(), false);
    String xSessionId = xenonSession.getString(Field.SK.getName());
    try {
      if (isConflicted) {
        XenonSessions.copyUnsavedChangesToNewFile(
            fileId, message.body().getString("oldFileId"), xSessionId);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName("copySavedChangesToNewFile")
            .runWithoutSegment(() -> {
              XenonSessions.copySavedChangesToNewFile(
                  fileId, message.body().getString("oldFileId"), xSessionId);
            });
      }
      if ((fileChangesInfo != null && !fileChangesInfo.isEmpty()) || changesAreSaved) {
        List<Map<String, Object>> changesToAdd = fileChangesInfo != null
            ? fileChangesInfo.stream()
                .map(change -> ((JsonObject) change).getMap())
                .collect(Collectors.toList())
            : List.of();

        List<Map<String, Object>> changesInfoInDb;

        Item unsavedCurrentChanges = XenonSessions.getUnsavedCurrentChanges(fileId, xSessionId);
        if (unsavedCurrentChanges != null) {
          changesInfoInDb = unsavedCurrentChanges.getList(Field.FILE_CHANGES_INFO.getName());
          if (!changesToAdd.isEmpty()) {
            changesInfoInDb.addAll(changesToAdd);
            XenonSessions.updateSessionChanges(fileId, xSessionId, changesInfoInDb);
          }
          if (changesAreSaved) {
            XenonSessions.markSessionStatusAsSaved(unsavedCurrentChanges, xSessionId);
          }
        } else {
          changesInfoInDb = new ArrayList<>();
          if (!changesToAdd.isEmpty()) {
            changesInfoInDb.addAll(changesToAdd);
            XenonSessions.addSessionChanges(fileId, xSessionId, changesInfoInDb, changesAreSaved);
          }
        }
      }
      XenonSessions.updateSessionState(fileId, xSessionId, state);
      boolean isTTLUpdated = false;
      if (isModeUpdateRequired && newMode != null) {
        log.info(
            "[SESSION] File session mode updated for id - " + xSessionId + ", fileId - " + fileId
                + ", userId - " + message.body().getString(Field.USER_ID.getName()) + ", newMode - "
                + newMode + ", existing session item - " + xenonSession.toJSONPretty());
        XenonSessions.setMode(fileId, xSessionId, newMode);
        boolean isCommander = AuthManager.ClientType.getClientType(
                message.body().getString(Field.DEVICE.getName()))
            .equals(AuthManager.ClientType.COMMANDER);
        boolean isViewMode = newMode.equalsIgnoreCase(Mode.VIEW.name());
        if (isCommander && isViewMode) {
          // https://graebert.atlassian.net/browse/XENON-66244
          XenonSessions.updateLastActivityAndTimeToLive(fileId, xSessionId, TtlUtils.inOneWeek());
          isTTLUpdated = true;
        }
      }

      if (!isTTLUpdated
          && xenonSession.hasAttribute("lastActivity")
          && GMTHelper.utcCurrentTime() - xenonSession.getLong("lastActivity")
              > XenonSessions.MIN_UPDATE_TIME) {
        XenonSessions.updateLastActivityAndTimeToLive(
            fileId, xSessionId, Sessions.getXSessionTTL());
      }
    } catch (ConditionalCheckFailedException e) {
      try {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "FileSessionHasExpired"),
            KudoErrorCodes.FILE_SESSION_EXPIRED,
            HttpStatus.BAD_REQUEST,
            "FileSessionHasExpired");
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      }
    }

    StorageType storageType = StorageType.getStorageType(
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body()));

    if (shouldSessionDowngrade != null && shouldSessionDowngrade && isModeUpdateRequired) {
      JsonObject body = message.body();
      String applicant = body.getString(Field.APPLICANT_X_SESSION_ID.getName());

      boolean downgradeForOwnedSession = body.getString(Field.USER_ID.getName())
          .equals(xenonSession.getString(Field.USER_ID.getName()));

      // if user is not downgrading his own session and
      // no applicant in request or request is dead
      // we find first applicant request in dynamo
      if (!downgradeForOwnedSession
          && (!Utils.isStringNotNullOrEmpty(applicant)
              || !XenonSessions.isRequestAlive(xenonSession, applicant))) {
        applicant = XenonSessions.getApplicantXSession(xenonSession);
      }

      if (Objects.nonNull(applicant)) {
        String finalApplicant = applicant;
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .withName(webSocketPrefix + "sessionDenied")
            .runWithoutSegment(() -> {
              XenonSessions.getAllPendingRequests(fileId, Collections.singleton(finalApplicant))
                  .parallelStream()
                  .forEach(request -> eb_send(
                      segment,
                      WebSocketManager.address + ".sessionDenied",
                      new JsonObject()
                          .put(
                              Field.FILE_ID.getName(),
                              StorageType.processFileIdForWebsocketNotification(
                                  storageType, fileId))
                          .put(Field.X_SESSION_ID.getName(), xSessionId)
                          .put(
                              Field.REQUEST_X_SESSION_ID.getName(), request.getString(Dataplane.sk))
                          .put(
                              Field.EMAIL.getName(),
                              message.body().getString(Field.EMAIL.getName()))
                          .put(
                              Field.USERNAME.getName(),
                              message
                                  .body()
                                  .getString(Field.NAME.getName())
                                  .concat(" ")
                                  .concat(message.body().getString(Field.SURNAME.getName())))));
            });
      }

      eb_send(
          parent,
          WebSocketManager.address + ".sessionDowngrade",
          new JsonObject()
              .put(Field.APPLICANT_X_SESSION_ID.getName(), applicant)
              .put(Field.MODE.getName(), xenonSession.getString(Field.MODE.getName()))
              .put(
                  Field.FILE_ID.getName(),
                  StorageType.processFileIdForWebsocketNotification(storageType, fileId))
              .put(Field.X_SESSION_ID.getName(), xSessionId)
              .put(Field.EMAIL.getName(), xenonSession.getString(Field.EMAIL.getName()))
              .put(
                  Field.USERNAME.getName(),
                  xenonSession
                      .getString(Field.NAME.getName())
                      .concat(" ")
                      .concat(xenonSession.getString(Field.SURNAME.getName()))));
    }

    XRayManager.endSegment(parent);

    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.STORAGE_TYPE.getName(), xenonSession.getString(Field.STORAGE_TYPE.getName()))
            .put(Field.EXTERNAL_ID.getName(), xenonSession.getString(Field.EXTERNAL_ID.getName()))
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.X_SESSION_ID.getName(), xSessionId)
            .put(Field.DEVICE.getName(), xenonSession.getString(Field.DEVICE.getName()))
            .put("checkinFailed", checkinFailed),
        mills);
  }

  private void doSaveSession(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject jsonObject = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, jsonObject);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, jsonObject);
    String fSessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, jsonObject);
    String userName = getRequiredString(segment, Field.USERNAME.getName(), message, jsonObject);
    String mode = getRequiredString(segment, Field.MODE.getName(), message, jsonObject);
    String storageType =
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, jsonObject);
    String externalId = jsonObject.getString(Field.EXTERNAL_ID.getName());
    String device = jsonObject.getString(Field.DEVICE.getName());
    String email = jsonObject.getString(Field.EMAIL.getName());
    String name = jsonObject.getString(Field.NAME.getName());
    String surname = jsonObject.getString(Field.SURNAME.getName());
    boolean force = jsonObject.containsKey(Field.FORCE.getName())
        ? jsonObject.getBoolean(Field.FORCE.getName())
        : false;
    boolean addToRecent = jsonObject.getBoolean("addToRecent");
    if (fileId == null
        || userId == null
        || fSessionId == null
        || userName == null
        || mode == null
        || storageType == null
        || !Mode.contains(mode)) {
      return;
    }
    if (Sessions.AllowAdminSessionId() && fSessionId.equals("admin-session-id")) {
      sendOK(segment, message, mills);
      return;
    }
    // check if user has permission to open the kudo drive file
    if (storageType.equals(StorageType.SAMPLES.name())) {
      if (!SimpleStorage.hasAccessToCreateSessionForKudoDrive(fileId, userId)) {
        try {
          throw new KudoFileException(
              MessageFormat.format(
                  Utils.getLocalizedString(message, "UserHasNoAccessWithTypeToFileWithId"),
                  AccessType.canOpen.label,
                  fileId),
              KudoErrorCodes.FILE_NOT_ACCESSIBLE,
              HttpStatus.FORBIDDEN,
              "UserHasNoAccessWithTypeToFileWithId");
        } catch (KudoFileException kfe) {
          sendError(
              segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        }
      }
    }
    Promise<Object> trashStatusPromise = Promise.promise();
    checkTrashStatusForSession(
        segment, jsonObject, externalId, fileId, userId, storageType, trashStatusPromise);
    trashStatusPromise
        .future()
        .onSuccess(event -> {
          boolean doEditingSessionsExist = false, replyError = false;
          Item activeSession = null;
          // check if there are running editing sessions (currently for all types of clients)
          if (Mode.EDIT.equals(Mode.getMode(mode))) {
            Iterator<Item> activeSessions = XenonSessions.getActiveSessions(fileId, Mode.EDIT);
            doEditingSessionsExist = activeSessions.hasNext();
            replyError = !force && doEditingSessionsExist;
            if (doEditingSessionsExist) {
              activeSession = activeSessions.next();
              if (config.getProperties().getNewSessionWorkflow()) {
                if (activeSession.hasAttribute(Field.STATE.getName())
                    && activeSession
                        .getString(Field.STATE.getName())
                        .equals(XenonSessions.SessionState.SAVE_PENDING.name())) {
                  Item finalActiveSession = activeSession;
                  Entity subsegment =
                      XRayManager.createSubSegment(operationGroup, segment, "checkSessionState");
                  // check the file save status for max 20 seconds with delays of 4 seconds
                  CircuitBreaker sessionStateBreaker = CircuitBreaker.create(
                          "vert.x-circuit-breaker-xsession-state",
                          vertx,
                          new CircuitBreakerOptions().setTimeout(20000).setMaxRetries(5))
                      .retryPolicy(RetryPolicy.constantDelay(4000L));
                  sessionStateBreaker
                      .execute(promise -> {
                        Item sessionToCheck = XenonSessions.getSession(
                            fileId, finalActiveSession.getString(Field.SK.getName()));
                        if (Objects.nonNull(sessionToCheck)
                            && sessionToCheck
                                .getString(Field.STATE.getName())
                                .equals(XenonSessions.SessionState.SAVE_PENDING.name())) {
                          promise.fail("File save is still pending");
                        } else {
                          promise.complete();
                        }
                      })
                      .onSuccess(s1 -> {
                        AtomicBoolean editingSession = new AtomicBoolean(true);
                        // check the file session status for max 5 seconds with delays of 1 second
                        CircuitBreaker createSessionBreaker = CircuitBreaker.create(
                                "vert.x-circuit-breaker-xsession-create",
                                vertx,
                                new CircuitBreakerOptions().setTimeout(5000).setMaxRetries(5))
                            .retryPolicy(retry -> 1000L);
                        createSessionBreaker
                            .execute(promise -> {
                              Item sessionToCheck = XenonSessions.getSession(
                                  fileId, finalActiveSession.getString(Field.SK.getName()));
                              if (sessionToCheck != null) {
                                promise.fail("Session is not removed yet");
                              } else {
                                editingSession.set(false);
                                promise.complete();
                              }
                            })
                            .onComplete(s2 -> {
                              boolean finalReplyError = !force && editingSession.get();
                              manageEditingSession(
                                  segment,
                                  message,
                                  fileId,
                                  storageType,
                                  finalActiveSession,
                                  userId,
                                  force,
                                  editingSession.get(),
                                  externalId,
                                  mode,
                                  fSessionId,
                                  userName,
                                  device,
                                  addToRecent,
                                  finalReplyError,
                                  mills,
                                  email,
                                  name,
                                  surname);
                            });
                      })
                      .onFailure(f -> {
                        try {
                          throw new KudoFileException(
                              Utils.getLocalizedString(message, "FL19"),
                              KudoErrorCodes.FILE_IS_SAVE_PENDING,
                              HttpStatus.PRECONDITION_FAILED,
                              "FL19");
                        } catch (KudoFileException kfe) {
                          sendError(
                              segment,
                              message,
                              kfe.toResponseObject(),
                              kfe.getHttpStatus(),
                              kfe.getErrorId());
                        }
                      })
                      .onComplete(c -> XRayManager.endSegment(subsegment));
                  return;
                }
              }
            }
          }
          manageEditingSession(
              segment,
              message,
              fileId,
              storageType,
              activeSession,
              userId,
              force,
              doEditingSessionsExist,
              externalId,
              mode,
              fSessionId,
              userName,
              device,
              addToRecent,
              replyError,
              mills,
              email,
              name,
              surname);
        })
        .onFailure(event -> {
          try {
            throw new KudoFileException(
                Utils.getLocalizedString(message, "FL23"),
                KudoErrorCodes.FILE_DELETED,
                HttpStatus.PRECONDITION_FAILED,
                "FL23");
          } catch (KudoFileException kfe) {
            sendError(
                segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
          }
        });
  }

  /**
   * @param segment    - current parent segment
   * @param message    - request message
   * @param fileStatus - Action on file | CHECKIN (release the lock) / CHECKOUT (apply the lock)
   */
  private Future<String> checkLockForTrimbleConnect(
      Entity segment,
      Message<JsonObject> message,
      boolean force,
      TrimbleConnect.TCFileStatus fileStatus) {
    Promise<String> promise = Promise.promise();
    TrimbleConnect.TCFileStatus[] fileStatusArray = {fileStatus};
    eb_request(
        segment,
        TrimbleConnect.address + "." + fileStatusArray[0].name().toLowerCase(),
        message.body(),
        event -> {
          if (event.succeeded()
              && OK.equals(
                  ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
            promise.complete();
          } else {
            final JsonObject[] errorBody = {getTrimbleCheckError(event.cause(), event.result())};
            if (force) {
              if (errorBody[0].containsKey(Field.ERROR_CODE.getName())
                  && errorBody[0]
                      .getString(Field.ERROR_CODE.getName())
                      .equals("FILE_ALREADY_CHECKED_OUT")) {
                // try to check in the file
                eb_request(
                    segment,
                    TrimbleConnect.address + "."
                        + TrimbleConnect.TCFileStatus.CHECKIN.name().toLowerCase(),
                    message.body(),
                    checkInEvent -> {
                      if (checkInEvent.succeeded()
                          && OK.equals(((JsonObject) checkInEvent.result().body())
                              .getString(Field.STATUS.getName()))) {
                        // Again try to check out the file
                        eb_request(
                            segment,
                            TrimbleConnect.address + "."
                                + TrimbleConnect.TCFileStatus.CHECKOUT.name().toLowerCase(),
                            message.body(),
                            checkOutEvent -> {
                              if (checkOutEvent.succeeded()
                                  && OK.equals(
                                      ((JsonObject) checkOutEvent.result().body())
                                          .getString(Field.STATUS.getName()))) {
                                promise.complete();
                              } else {
                                errorBody[0] = getTrimbleCheckError(
                                    checkOutEvent.cause(), checkOutEvent.result());
                                fileStatusArray[0] = TrimbleConnect.TCFileStatus.CHECKOUT;
                              }
                            });
                      } else {
                        errorBody[0] =
                            getTrimbleCheckError(checkInEvent.cause(), checkInEvent.result());
                        fileStatusArray[0] = TrimbleConnect.TCFileStatus.CHECKIN;
                      }
                    });
              }
            }
            if (promise.tryComplete()) {
              promise.fail(TrimbleConnect.TCFileStatus.getStatusErrorCode(fileStatusArray[0]) + ","
                  + TrimbleConnect.TCFileStatus.getStatusError(fileStatusArray[0])
                      .name()
                  + "," + errorBody[0].getString(Field.MESSAGE.getName()));
            }
          }
        });
    return promise.future();
  }

  private void manageEditingSession(
      Entity segment,
      Message<JsonObject> message,
      String fileId,
      String storageType,
      Item activeSession,
      String userId,
      boolean force,
      boolean doEditingSessionsExist,
      String externalId,
      String mode,
      String userSessionId,
      String userName,
      String device,
      boolean addToRecent,
      boolean replyError,
      long mills,
      String email,
      String name,
      String surname) {
    Item oldSession = null;
    try {
      if (doEditingSessionsExist) {
        // if another user is trying to open this file
        if (!userId.equals(activeSession.getString(Field.USER_ID.getName()))) {
          Item session = Sessions.getSessionById(activeSession.getString("fSessionId"));
          // check if the active file session user is loggedIn or not
          if (session != null) {
            throw new KudoFileException(
                Utils.getLocalizedString(message, "ThereIsExistingEditingSession"),
                KudoErrorCodes.EXISTING_EDITING_SESSION,
                HttpStatus.BAD_REQUEST,
                "ThereIsExistingEditingSession");
          } else if (XenonSessions.getSessionForUser(fileId, activeSession.getString("fSessionId"))
              != null) {
            XenonSessions.deleteSession(fileId, activeSession.getString(Field.SK.getName()));
            replyError = false;
          }
          // force get editing session from the same user
        } else if (force && !StorageType.TRIMBLE.equals(StorageType.getStorageType(storageType))) {
          // downgrade old session for user
          eb_send(
              segment,
              WebSocketManager.address + ".sessionDowngrade",
              new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      StorageType.processFileIdForWebsocketNotification(
                          StorageType.getStorageType(
                              activeSession.getString(Field.STORAGE_TYPE.getName())),
                          fileId))
                  .put(Field.X_SESSION_ID.getName(), activeSession.getString(Field.SK.getName()))
                  .put(Field.EMAIL.getName(), activeSession.getString(Field.EMAIL.getName()))
                  .put(
                      Field.USERNAME.getName(),
                      activeSession
                          .getString(Field.NAME.getName())
                          .concat(" ")
                          .concat(activeSession.getString(Field.SURNAME.getName()))));
          XenonSessions.downgradeSession(activeSession);
          // if there's an active editing session, downgrade an old one (for commander)
        } else if (device.equals(AuthManager.ClientType.COMMANDER.name())
            && userSessionId.equals(activeSession.getString("fSessionId"))
            && device.equals(activeSession.getString(Field.DEVICE.getName()))) {
          replyError = false;
          oldSession = activeSession;
          oldSession.withString(Field.MODE.getName(), Mode.VIEW.name().toLowerCase());
        }
      }
      if (replyError) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "ThereIsExistingEditingSession"),
            KudoErrorCodes.EXISTING_EDITING_SESSION,
            HttpStatus.BAD_REQUEST,
            "ThereIsExistingEditingSession");
      }

      if (Mode.EDIT.equals(Mode.getMode(mode))
          && StorageType.TRIMBLE.equals(StorageType.getStorageType(storageType))) {
        final Item oldTrimbleSession = (force) ? activeSession : oldSession;

        Future<String> lockFuture = checkLockForTrimbleConnect(
            segment, message, force, TrimbleConnect.TCFileStatus.CHECKOUT);
        lockFuture
            .onSuccess(s -> {
              if (force && oldTrimbleSession != null) {
                eb_send(
                    segment,
                    WebSocketManager.address + ".sessionDowngrade",
                    new JsonObject()
                        .put(
                            Field.FILE_ID.getName(),
                            StorageType.processFileIdForWebsocketNotification(
                                StorageType.getStorageType(
                                    oldTrimbleSession.getString(Field.STORAGE_TYPE.getName())),
                                fileId))
                        .put(
                            Field.X_SESSION_ID.getName(),
                            oldTrimbleSession.getString(Field.SK.getName()))
                        .put(
                            Field.EMAIL.getName(),
                            oldTrimbleSession.getString(Field.EMAIL.getName()))
                        .put(
                            Field.USERNAME.getName(),
                            oldTrimbleSession
                                .getString(Field.NAME.getName())
                                .concat(" ")
                                .concat(oldTrimbleSession.getString(Field.SURNAME.getName()))));
                XenonSessions.downgradeSession(oldTrimbleSession);
              }
              createSession(
                  segment,
                  message,
                  fileId,
                  userId,
                  userName,
                  Mode.getMode(mode),
                  userSessionId,
                  StorageType.getStorageType(storageType),
                  device,
                  externalId,
                  oldTrimbleSession,
                  addToRecent,
                  mills,
                  email,
                  name,
                  surname);
            })
            .onFailure(f -> manageTrimbleCheckError(segment, message, f, fileId));
        return;
      }
      createSession(
          segment,
          message,
          fileId,
          userId,
          userName,
          Mode.getMode(mode),
          userSessionId,
          StorageType.getStorageType(storageType),
          device,
          externalId,
          oldSession,
          addToRecent,
          mills,
          email,
          name,
          surname);
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    }
  }

  private void createSession(
      Entity segment,
      Message<JsonObject> message,
      String fileId,
      String userId,
      String userName,
      Mode mode,
      String fSessionId,
      StorageType storageType,
      String device,
      String externalId,
      Item oldSession,
      boolean addToRecent,
      long mills,
      String email,
      String name,
      String surname) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "create");
    Item metaData = FileMetaData.getMetaData(fileId, storageType.name());
    String fileName = null;
    if (metaData != null) {
      fileName = metaData.getString(Field.FILE_NAME.getName());
    }
    long ttl = Sessions.getXSessionTTL();
    if (mode.equals(Mode.VIEW) && device.equals(AuthManager.ClientType.COMMANDER.name())) {
      // for old commanders - we need to have longer ttl.
      // TODO: add version check
      ttl = TtlUtils.inOneWeek();
    }
    String sessionId = XenonSessions.createSession(
        fileId,
        userId,
        userName,
        mode,
        fSessionId,
        ttl,
        storageType,
        device,
        externalId,
        null,
        fileName,
        email,
        name,
        surname);

    if (oldSession != null && Utils.isStringNotNullOrEmpty(externalId)) {
      XenonSessions.downgradeSession(oldSession);
    }
    sendOK(
        segment,
        message,
        new JsonObject().put(Field.ENCAPSULATED_ID.getName(), sessionId).put("expiration", ttl),
        mills);

    if (device.equals(AuthManager.ClientType.COMMANDER.name())
        || device.equals(AuthManager.ClientType.TOUCH.name())) {
      // DK (14.12.19) - same for Touch
      // subscribe to notifications from Commander.
      // As far as commander does download for file open - there is no reliable way to add
      // subscription on open
      // except for the case when editing session is added for the first time
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("handleSubscriptionInCommander")
          .run((Segment blockingSegment) -> {
            eb_request(
                blockingSegment,
                Subscriptions.address + ".getSubscription",
                new JsonObject()
                    .put(Field.USER_ID.getName(), userId)
                    .put(Field.FILE_ID.getName(), fileId),
                event -> {
                  try {
                    JsonObject body = ((JsonObject) event.result().body());
                    boolean isFailed =
                        event.failed() || !OK.equals(body.getString(Field.STATUS.getName()));
                    if (isFailed) {
                      log.error("Wasn't able to check subscription from Commander's " + "editing "
                          + "session");
                    } else if (body.getJsonObject("subscription").isEmpty()) {
                      // no subscriptions found - let's create
                      eb_send(
                          blockingSegment,
                          Subscriptions.address + ".addSubscription",
                          new JsonObject()
                              .put(Field.FILE_ID.getName(), fileId)
                              .put(Field.USER_ID.getName(), userId)
                              .put(Field.STORAGE_TYPE.getName(), storageType)
                              .put(
                                  "scope",
                                  new JsonArray()
                                      .add(Subscriptions.subscriptionScope.GLOBAL.toString()))
                              .put("scopeUpdate", Subscriptions.scopeUpdate.REWRITE.toString()));
                    }
                  } catch (Exception ex) {
                    log.error("Wasn't able to check subscription from Commander's editing "
                        + "session. Exception: " + ex);
                  }
                });
          });
    }

    new ExecutorServiceAsyncRunner(executorService, operationGroup, subsegment, message)
        .run((Segment blockingSegment) -> {
          Item userInfo = Users.getUserById(userId);
          if (Objects.isNull(userInfo)) {
            blockingSegment.addException(new Exception());
            return;
          }
          if (userInfo.hasAttribute(Field.GRAEBERT_ID.getName())
              && userInfo.hasAttribute(Field.INTERCOM_ACCESS_TOKEN.getName())) {
            String graebertId = userInfo.getString(Field.GRAEBERT_ID.getName());
            String intercomAccessToken = userInfo.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
            if (Utils.isStringNotNullOrEmpty(graebertId)
                && Utils.isStringNotNullOrEmpty(intercomAccessToken)) {
              Map<String, Object> metadata = new HashMap<>();
              metadata.put(Field.STORAGE_TYPE.getName(), storageType);
              metadata.put("date", GMTHelper.utcCurrentTime());
              metadata.put(Field.EXTERNAL_ID.getName(), externalId);
              metadata.put(Field.FILE_ID.getName(), fileId);
              IntercomConnection.sendEvent(
                  "Xenon session created",
                  graebertId,
                  metadata,
                  intercomAccessToken,
                  userInfo.getString(Field.INTERCOM_APP_ID.getName()));
            }
          }

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

          String licenseType = userInfo.hasAttribute(Field.LICENSE_TYPE.getName())
              ? userInfo.getString(Field.LICENSE_TYPE.getName())
              : null;
          long licenseExpirationDate =
              userInfo.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())
                  ? userInfo.getLong(Field.LICENSE_EXPIRATION_DATE.getName())
                  : 0L;

          try {
            // record session log to kinesis
            SessionLog sessionLog = new SessionLog(
                userId,
                fileId,
                mode,
                storageType,
                userInfo.getString(Field.USER_EMAIL.getName()),
                GMTHelper.utcCurrentTime(),
                true,
                SessionTypes.XENON_SESSION,
                sessionId,
                region,
                device,
                null,
                GMTHelper.toUtcMidnightTime(GMTHelper.utcCurrentTime()),
                GMTHelper.utcCurrentTime(),
                GMTHelper.utcCurrentTime(),
                Utils.isStringNotNullOrEmpty(licenseType) ? licenseType : "no_license_found",
                licenseExpirationDate);
            sessionLog.sendToServer();
          } catch (Exception e) {
            log.error(e);
          }
          if (addToRecent) {
            eb_send(
                blockingSegment, RecentFilesVerticle.address + ".saveRecentFile", message.body());
          }
          // save thumbnail subscription on file open to make sure we have subscription for
          // thumbnail updates
          ThumbnailSubscription.save(fileId, userId);
          try {
            FileLog fileLog = new FileLog(
                userId,
                fileId,
                storageType,
                userName,
                GMTHelper.utcCurrentTime(),
                true,
                Mode.EDIT.equals(mode) ? FileActions.EDIT : FileActions.VIEW,
                GMTHelper.utcCurrentTime(),
                null,
                fSessionId,
                null,
                device,
                null);
            fileLog.sendToServer();
          } catch (Exception e) {
            log.error(LogPrefix.getLogPrefix() + " Error on creating file log: ", e);
          }
        });
    recordExecutionTime("saveXSession", System.currentTimeMillis() - mills);
    XRayManager.endSegment(subsegment);
  }

  private void doRemoveSession(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = message.body().getString(Field.FILE_ID.getName());
    String fSessionId = message.body().getString(Field.SESSION_ID.getName());
    String id = message.body().getString(Field.X_SESSION_ID.getName());
    String userId = message.body().getString(Field.USER_ID.getName());
    String name = message.body().getString(Field.NAME.getName());
    String surname = message.body().getString(Field.SURNAME.getName());
    String email = message.body().getString(Field.EMAIL.getName());
    try {
      if ((fileId == null || fileId.isEmpty() || fSessionId == null || fSessionId.isEmpty())
          && (id == null || id.isEmpty())) {
        throw new KudoFileException(
            Utils.getLocalizedString(
                message, "EitherFileidAndFsessionidOrSessionidMustBeSpecified"),
            KudoErrorCodes.FILE_SESSION_IDS_MISSING,
            HttpStatus.BAD_REQUEST,
            "EitherFileidAndFsessionidOrSessionidMustBeSpecified");
      }
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
      return;
    }
    if (Sessions.AllowAdminSessionId()
        && Objects.nonNull(fSessionId)
        && fSessionId.equals("admin-session-id")) {
      sendOK(segment, message, mills);
      return;
    }
    Item session;
    boolean isSessionRemovalAllowed = false;

    if (id != null && !id.isEmpty()) {
      session = XenonSessions.getSession(fileId, id);
      if (session != null) {
        isSessionRemovalAllowed = userId.equals(session.getString(Field.USER_ID.getName()));
      }
    } else {
      isSessionRemovalAllowed = true;
      session = XenonSessions.getSessionForUser(fileId, fSessionId);
      if (session != null) {
        id = session.getString(Field.SK.getName());
      }
    }

    String sessionMode = null;

    if (session != null && isSessionRemovalAllowed) {
      sessionMode = session.getString(Field.MODE.getName());
      XenonSessions.deleteSession(fileId, id);
      String finalId1 = id;
      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .run((Segment blockingSegment) -> {
            Item userInfo = Users.getUserById(userId);
            if (Objects.isNull(userInfo)) {
              blockingSegment.addException(new Exception());
              return;
            }
            if (userInfo.hasAttribute(Field.GRAEBERT_ID.getName())
                && userInfo.hasAttribute(Field.INTERCOM_ACCESS_TOKEN.getName())) {
              String graebertId = userInfo.getString(Field.GRAEBERT_ID.getName());
              String intercomAccessToken =
                  userInfo.getString(Field.INTERCOM_ACCESS_TOKEN.getName());
              if (Utils.isStringNotNullOrEmpty(graebertId)
                  && Utils.isStringNotNullOrEmpty(intercomAccessToken)) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("date", GMTHelper.utcCurrentTime());
                metadata.put(Field.FILE_ID.getName(), fileId);
                metadata.put(
                    Field.STORAGE_TYPE.getName(), session.getString(Field.STORAGE_TYPE.getName()));
                metadata.put(Field.SESSION_ID.getName(), finalId1);
                IntercomConnection.sendEvent(
                    "Xenon session deleted",
                    graebertId,
                    metadata,
                    intercomAccessToken,
                    userInfo.getString(Field.INTERCOM_APP_ID.getName()));
              }
            }
          });
      if (session.getString(Field.MODE.getName()).equals(Mode.EDIT.name().toLowerCase())
          && StorageType.TRIMBLE.equals(
              StorageType.getStorageType(session.getString(Field.STORAGE_TYPE.getName())))) {
        String finalId = id;
        Future<String> lockFuture = checkLockForTrimbleConnect(
            segment, message, false, TrimbleConnect.TCFileStatus.CHECKIN);
        lockFuture
            .onSuccess(s -> {
              eb_send(
                  segment,
                  WebSocketManager.address + ".sessionDeleted",
                  new JsonObject()
                      .put(Field.MODE.getName(), Mode.EDIT.name().toLowerCase())
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(Field.X_SESSION_ID.getName(), finalId)
                      .put(Field.EMAIL.getName(), email)
                      .put(Field.USERNAME.getName(), name.concat(" ").concat(surname)));

              sendOK(segment, message, new JsonObject().put("checkinFailed", false), mills);
            })
            .onFailure(f -> manageTrimbleCheckError(segment, message, f, fileId));
        recordExecutionTime("removeXSession", System.currentTimeMillis() - mills);
        return;
      }
    }

    StorageType storageType = StorageType.getStorageType(
        getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, message.body()));

    if (Objects.nonNull(session)) {
      log.info("[SESSION] File session closed for id - " + id + ", fileId - " + fileId + ", "
          + "deleted - " + GMTHelper.utcCurrentTime() + ", existing session item - "
          + session.toJSONPretty());
    } else {
      log.warn(
          "[SESSION] session not found while deleting for id - " + id + ", fileId - " + fileId);
    }

    sendOK(segment, message, mills);
    eb_send(
        segment,
        WebSocketManager.address + ".sessionDeleted",
        new JsonObject()
            .put(
                Field.FILE_ID.getName(),
                StorageType.processFileIdForWebsocketNotification(storageType, fileId))
            .put(Field.MODE.getName(), sessionMode)
            .put(Field.X_SESSION_ID.getName(), id)
            .put(Field.EMAIL.getName(), email)
            .put(Field.USERNAME.getName(), name.concat(" ").concat(surname)));
    recordExecutionTime("removeXSession", System.currentTimeMillis() - mills);
  }

  private void doCheckSession(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String fileId = body.getString(Field.FILE_ID.getName());
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    try {
      Item item;
      if (Utils.isStringNotNullOrEmpty(xSessionId)) {
        item = XenonSessions.getSession(fileId, xSessionId);
      } else {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "FL22"),
            KudoErrorCodes.FILE_SESSION_IDS_MISSING,
            HttpStatus.BAD_REQUEST,
            "FL22");
      }
      if (Objects.isNull(item)
          || (!Mode.getMode(item.getString(Field.MODE.getName())).equals(Mode.EDIT))) {
        boolean viewOnlyCase =
            item != null && Mode.getMode(item.getString(Field.MODE.getName())).equals(Mode.VIEW);
        KudoErrorCodes errorCode = KudoErrorCodes.FILE_SESSION_EXPIRED;
        String errorStringId = emptyString;
        if (Objects.isNull(item)) {
          errorStringId = "FileSessionHasExpired";
        } else if (viewOnlyCase) {
          errorCode = KudoErrorCodes.VIEW_ONLY_SESSION;
          errorStringId = "viewOnlySessionFoundOnEditing";
        }
        throw new KudoFileException(
            Utils.getLocalizedString(message, errorStringId),
            errorCode,
            HttpStatus.BAD_REQUEST,
            "FL20");
      }
      if (item.hasAttribute(Field.STATE.getName())
          && item.getString(Field.STATE.getName())
              .equals(XenonSessions.SessionState.SAVE_PENDING.name())) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "FL19"),
            KudoErrorCodes.FILE_IS_SAVE_PENDING,
            HttpStatus.BAD_REQUEST,
            "FL19");
      }
      sendOK(segment, message, mills);
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private void doGetSessions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String fSessionId = message.body().getString(Field.SESSION_ID.getName());
    if (fileId == null) {
      return;
    }
    JsonArray result = new JsonArray();
    for (Item session : XenonSessions.getAllSessionsForFile(fileId)) {
      // do not show editing session for current user session (to let commander start new one)
      // https://graebert.atlassian.net/browse/XENON-17799
      if (Mode.EDIT.name().toLowerCase().equals(session.getString(Field.MODE.getName()))
          && fSessionId != null
          && fSessionId.equals(session.getString("fSessionId"))
          && AuthManager.ClientType.COMMANDER
              .name()
              .equals(session.getString(Field.DEVICE.getName()))) {
        continue;
      }
      if (!session.hasAttribute(Field.STATE.getName())) {
        session.withString(Field.STATE.getName(), XenonSessions.SessionState.STALE.name());
      }

      List<Map<String, Object>> fileChanges =
          getAllFileSessionChanges(fileId, session.getString(Field.SK.getName()));

      session.withList("changes", fileChanges);
      session.removeAttribute("lastActivity");
      session.removeAttribute(Field.TTL.getName());

      result.add(new JsonObject(session.toJSON()));
    }
    sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), result), mills);
    recordExecutionTime("getXSessions", System.currentTimeMillis() - mills);
  }

  private JsonObject getTrimbleCheckError(Throwable cause, Message<Object> result) {
    JsonObject error = new JsonObject();
    error.put(Field.MESSAGE.getName(), emptyString);
    if (cause != null) {
      error.put(Field.MESSAGE.getName(), errorSeparator + cause.getLocalizedMessage());
    } else if (result != null && result.body() != null) {
      JsonObject messageObj = ((JsonObject) result.body()).getJsonObject(Field.MESSAGE.getName());
      if (Utils.isJsonObjectNotNullOrEmpty(messageObj)) {
        JsonObject innerObject = null;
        try {
          innerObject = new JsonObject(messageObj.getString(Field.MESSAGE.getName()));
        } catch (Exception ignore) {
        }
        if (innerObject != null) {
          if (innerObject.containsKey(Field.MESSAGE.getName())
              && innerObject.getValue(Field.MESSAGE.getName()) instanceof String) {
            error.put(
                Field.MESSAGE.getName(),
                errorSeparator + innerObject.getString(Field.MESSAGE.getName()));
          }
          if (innerObject.containsKey("errorcode")) {
            error.put(Field.ERROR_CODE.getName(), innerObject.getString("errorcode"));
          }
        } else {
          if (messageObj.containsKey("errorcode")) {
            error.put(Field.ERROR_CODE.getName(), messageObj.getString("errorcode"));
          }
          if (messageObj.containsKey(Field.MESSAGE.getName())) {
            error.put(
                Field.MESSAGE.getName(),
                errorSeparator + messageObj.getString(Field.MESSAGE.getName()));
          }
        }
      }
    }
    return error;
  }

  private List<Map<String, Object>> getAllFileSessionChanges(String fileId, String xSessionId) {
    Iterator<Item> savedChangesIterator = XenonSessions.getSavedCurrentChanges(fileId, xSessionId);
    List<Map<String, Object>> fileChanges = new ArrayList<>();
    savedChangesIterator.forEachRemaining(change -> {
      change
          .removeAttribute(Field.PK.getName())
          .removeAttribute(Field.SK.getName())
          .removeAttribute(Field.TTL.getName());
      change.withString("changesStatus", XenonSessions.SessionChangesStatus.SAVED.name());
      fileChanges.add(change.asMap());
    });
    Item currentChanges = XenonSessions.getUnsavedCurrentChanges(fileId, xSessionId);
    if (currentChanges != null) {
      currentChanges
          .removeAttribute(Field.PK.getName())
          .removeAttribute(Field.SK.getName())
          .removeAttribute(Field.TTL.getName());
      currentChanges.withString("changesStatus", XenonSessions.SessionChangesStatus.CURRENT.name());
      fileChanges.add(currentChanges.asMap());
    }
    return fileChanges;
  }

  private void manageTrimbleCheckError(
      Entity segment, Message<JsonObject> message, Throwable f, String fileId) {
    if (f == null
        || !Utils.isStringNotNullOrEmpty(f.getLocalizedMessage())
        || f.getLocalizedMessage().split(",").length != 3) {
      try {
        log.error("Error in checking TC file " + fileId + errorSeparator
            + ((f != null) ? f.getLocalizedMessage() : emptyString));
        throw new KudoFileException(
            Utils.getLocalizedString(message, "FL18"),
            KudoErrorCodes.UNABLE_TO_CHECK_FILE_LOCK,
            HttpStatus.BAD_REQUEST,
            "FL18");
      } catch (KudoFileException kfe) {
        sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
        return;
      }
    }
    String[] id = f.getLocalizedMessage().split(",");
    String errorId = id[0];
    String errorCode = id[1];
    String errorMessage = id[2];

    JsonObject response = null;
    if (errorCode.equals(KudoErrorCodes.UNABLE_TO_CHECKIN_FILE.name())) {
      response = new JsonObject().put("checkinFailed", true);
    }

    try {
      throw new KudoFileException(
          Utils.getLocalizedString(message, errorId) + errorMessage,
          KudoErrorCodes.valueOf(errorCode),
          HttpStatus.BAD_REQUEST,
          errorId,
          response);
    } catch (KudoFileException kfe) {
      sendError(
          segment,
          message,
          (kfe.getResultObject() != null)
              ? kfe.toResponseObject().mergeIn(kfe.getResultObject())
              : kfe.toResponseObject(),
          kfe.getHttpStatus(),
          kfe.getErrorId());
    }
  }

  private void doRequestEditSession(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    JsonObject body = message.body();

    String userId = body.getString(Field.USER_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String xSessionId = body.getString(Field.X_SESSION_ID.getName());
    String name = body.getString(Field.NAME.getName());
    String surname = body.getString(Field.SURNAME.getName());
    String email = body.getString(Field.EMAIL.getName());
    boolean isMySession = Boolean.parseBoolean(body.getString(Field.IS_MY_SESSION.getName()));

    // need to decode fileId for DropBox
    StorageType storageType =
        StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
    if (StorageType.DROPBOX.equals(storageType)) {
      fileId = URLDecoder.decode(fileId.replaceAll("%20", "\\+"), StandardCharsets.UTF_8);
    }

    if (!Utils.isStringNotNullOrEmpty(fileId) || !Utils.isStringNotNullOrEmpty(xSessionId)) {
      sendError(segment, message, HttpStatus.BAD_REQUEST, "FileidAndFsessionidMustBeSpecified");
      return;
    }

    Iterator<Item> activeSessions = XenonSessions.getActiveSessions(fileId, Mode.EDIT);
    if (activeSessions.hasNext()) {
      Item session = activeSessions.next();

      if (isMySession) {
        // notify editor about request
        eb_send(
            segment,
            WebSocketManager.address + ".sessionRequested",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    StorageType.processFileIdForWebsocketNotification(storageType, fileId))
                .put(Field.X_SESSION_ID.getName(), session.getString(Field.SK.getName()))
                .put(Field.REQUEST_X_SESSION_ID.getName(), xSessionId)
                .put(Field.IS_MY_SESSION.getName(), true)
                .put(Field.EMAIL.getName(), email)
                .put(Field.USERNAME.getName(), name.concat(" ").concat(surname)));

        sendOK(
            segment,
            message,
            new JsonObject().put("canUpdateNow", false).put("requestSent", true),
            mills);
        return;
      }

      if (!session.getString(Field.USER_ID.getName()).equals(userId)) {
        // if user has already requested in recent 5 minutes
        Item recentRequest = XenonSessions.getResentRequest(fileId, userId);
        if (recentRequest != null) {
          long timeToWait =
              recentRequest.getInt(Field.TTL.getName()) - (GMTHelper.utcCurrentTime() / 1000);
          sendError(
              segment,
              message,
              new JsonObject().put("timeToWait", timeToWait),
              HttpStatus.FORBIDDEN,
              "YouHaveAlreadyRequestedEditRecently");
          return;
        }

        Item request = XenonSessions.saveRequest(session, userId, xSessionId);

        sendOK(
            segment,
            message,
            new JsonObject()
                .put("canUpdateNow", false)
                .put("requestSent", true)
                .put(Field.TTL.getName(), request.getLong(Field.TTL.getName())),
            mills);

        // notify editor about request
        eb_send(
            segment,
            WebSocketManager.address + ".sessionRequested",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    StorageType.processFileIdForWebsocketNotification(storageType, fileId))
                .put(Field.X_SESSION_ID.getName(), session.getString(Field.SK.getName()))
                .put(Field.REQUEST_X_SESSION_ID.getName(), xSessionId)
                .put(Field.TTL.getName(), request.getLong(Field.TTL.getName()))
                .put(Field.EMAIL.getName(), email)
                .put(Field.USERNAME.getName(), name.concat(" ").concat(surname)));

        return;
      }
    }

    // no edit session or self request
    sendOK(
        segment,
        message,
        new JsonObject().put("canUpdateNow", true).put("requestSent", false),
        mills);
  }

  private void doDenyRequest(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    try {
      JsonObject body = message.body();

      String fileId = body.getString(Field.FILE_ID.getName());
      StorageType storageType =
          StorageType.getStorageType(body.getString(Field.STORAGE_TYPE.getName()));
      String xSessionId = body.getString(Field.X_SESSION_ID.getName());
      String requestXSessionId = body.getString(Field.REQUEST_X_SESSION_ID.getName());
      String name = body.getString(Field.NAME.getName());
      String surname = body.getString(Field.SURNAME.getName());
      String email = body.getString(Field.EMAIL.getName());

      List<JsonObject> wsMessages;

      if (requestXSessionId.equals("*")) {
        String finalFileId = fileId;
        wsMessages = XenonSessions.getAllPendingRequests(fileId, new HashSet<>()).parallelStream()
            .map(request -> {
              XenonSessions.denyRequest(request);
              return new JsonObject()
                  .put(
                      Field.FILE_ID.getName(),
                      StorageType.processFileIdForWebsocketNotification(storageType, finalFileId))
                  .put(Field.X_SESSION_ID.getName(), xSessionId)
                  .put(Field.REQUEST_X_SESSION_ID.getName(), request.getString(Dataplane.sk))
                  .put(Field.EMAIL.getName(), email)
                  .put(Field.USERNAME.getName(), name.concat(" ").concat(surname));
            })
            .collect(Collectors.toList());
      } else {
        // need to decode fileId for DropBox
        if (StorageType.DROPBOX.equals(storageType)) {
          fileId = URLDecoder.decode(fileId.replaceAll("%20", "\\+"), StandardCharsets.UTF_8);
        }

        if (!Utils.isStringNotNullOrEmpty(fileId)
            || !Utils.isStringNotNullOrEmpty(xSessionId)
            || !Utils.isStringNotNullOrEmpty(requestXSessionId)) {
          sendError(segment, message, HttpStatus.BAD_REQUEST, "FileidAndFsessionidMustBeSpecified");
          return;
        }

        // check if requested session is alive
        Item requestXSession = XenonSessions.getSession(fileId, requestXSessionId);
        if (requestXSession == null) {
          sendError(segment, message, HttpStatus.CONFLICT, "XSessionDoesNotExist");
          return;
        }

        Item request = XenonSessions.getRequest(fileId, requestXSessionId);
        // check that request was not denied, just in case
        if (Objects.nonNull(request)
            && request.hasAttribute("denied")
            && request.getBoolean("denied")) {
          sendError(segment, message, HttpStatus.CONFLICT, "XSessionRequestWasDenied");
          return;
        }
        // add 10 more seconds to let clients deny request on time
        if (Objects.isNull(request)
            || request.getLong(Dataplane.ttl) + 10 < GMTHelper.utcCurrentTime() / 1000) {
          sendError(segment, message, HttpStatus.CONFLICT, "XSessionRequestHasExpired");
          return;
        }

        XenonSessions.denyRequest(request);

        wsMessages = Collections.singletonList(new JsonObject()
            .put(
                Field.FILE_ID.getName(),
                StorageType.processFileIdForWebsocketNotification(storageType, fileId))
            .put(Field.X_SESSION_ID.getName(), xSessionId)
            .put(Field.REQUEST_X_SESSION_ID.getName(), requestXSessionId)
            .put(Field.EMAIL.getName(), email)
            .put(Field.USERNAME.getName(), name.concat(" ").concat(surname)));
      }

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName(webSocketPrefix + "sessionDenied")
          .runWithoutSegment(() -> {
            wsMessages.parallelStream()
                .forEach(wsMessage ->
                    eb_send(segment, WebSocketManager.address + ".sessionDenied", wsMessage));
          });

      sendOK(segment, message, mills);
    } catch (Exception e) {
      sendError(
          segment, message, "InternalError", HttpStatus.INTERNAL_SERVER_ERROR, "InternalError");
    }
  }

  private void doGetAccountFileSessions(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject body = message.body();
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String userId = body.getString(Field.USER_ID.getName());
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    if (!Utils.isStringNotNullOrEmpty(externalId) || !Utils.isStringNotNullOrEmpty(storageType)) {
      sendError(
          segment, message, HttpStatus.PRECONDITION_FAILED, "ExternalIdAndStorageTypeRequired");
      return;
    }
    JsonArray sessions = new JsonArray();
    try {
      Iterator<Item> sessionIterator =
          XenonSessions.getSessionForStorageAccount(externalId, userId, storageType);
      sessionIterator.forEachRemaining(ses -> sessions.add(sessionToJson(ses)));
      log.info("Sessions found for user - " + userId + " | storageType - " + storageType
          + " | externalId " + externalId + " => " + sessions.encodePrettily());
      sendOK(segment, message, new JsonObject().put(Field.RESULTS.getName(), sessions), mills);
    } catch (Exception ex) {
      sendError(segment, message, ex.getLocalizedMessage(), HttpStatus.BAD_REQUEST, ex);
    }
  }

  private JsonObject sessionToJson(Item ses) {
    String fileId = XenonSessions.getFileIdFromPK(ses.getString(Field.PK.getName()));
    String xSessionId = ses.getString(Field.SK.getName());
    ses.removeAttribute(Field.PK.getName());
    ses.removeAttribute(Field.SK.getName());
    JsonObject session = new JsonObject(ses.toJSON());
    session.put(Field.FILE_ID.getName(), fileId);
    session.put(Field.X_SESSION_ID.getName(), xSessionId);
    return session;
  }

  public enum ConflictingFileReason {
    GENERAL_STORAGE_ERROR,
    SESSION_NOT_FOUND,
    VIEW_ONLY_SESSION,
    NO_EDITING_RIGHTS,
    VERSIONS_CONFLICTED,
    FILE_IS_SAVE_PENDING,
    UNSHARED_OR_DELETED;

    public static ConflictingFileReason getReason(String value) {
      if (!Utils.isStringNotNullOrEmpty(value)) {
        return VERSIONS_CONFLICTED;
      }
      final String formattedValue = value.trim();
      for (ConflictingFileReason t : ConflictingFileReason.values()) {
        if (t.name().equalsIgnoreCase(formattedValue)) {
          return t;
        }
      }
      return GENERAL_STORAGE_ERROR;
    }

    public static String getConflictingError(
        String reason,
        String locale,
        String oldFileLink,
        String modifier,
        String newFileLink,
        boolean isSameFolder) {
      ConflictingFileReason conflictingFileReason = getReason(reason);
      switch (conflictingFileReason) {
        case SESSION_NOT_FOUND: {
          String hint =
              "<b>" + Utils.getLocalizedString(locale, "drawingWasntUpdatedForLongTime") + "</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(locale, "conflictingFileCreatedBecauseSessionNotFound"),
                  oldFileLink,
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        case VIEW_ONLY_SESSION: {
          String hint =
              "<b>" + Utils.getLocalizedString(locale, "drawingMightHaveBeenInactive") + "</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(
                      locale, "conflictingFileCreatedBecauseOfViewOnlySession"),
                  oldFileLink,
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        case NO_EDITING_RIGHTS: {
          String hint =
              "<b>" + Utils.getLocalizedString(locale, "checkIfYouHaveEditingAccess") + "</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(
                      locale, "conflictingFileCreatedBecauseOfNoEditingRights"),
                  oldFileLink,
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        case FILE_IS_SAVE_PENDING: {
          String hint = "<b>" + Utils.getLocalizedString(locale, "saveIsStillProcessing") + " "
              + Utils.getLocalizedString(locale, "tryAgainInSomeTime") + " ("
              + MessageFormat.format(Utils.getLocalizedString(locale, "nMinutes"), "10")
              + ")</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(locale, "conflictingFileCreatedBecauseSaveIsPending"),
                  oldFileLink,
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        case UNSHARED_OR_DELETED: {
          String hint =
              "<b>" + Utils.getLocalizedString(locale, "makeSureFileStillExists") + "</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(
                      locale, "conflictingFileCreatedBecauseOfUnshareOrRemoval"),
                  oldFileLink,
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        case VERSIONS_CONFLICTED: {
          String hint = "<b>"
              + Utils.getLocalizedString(locale, "drawingVersionHasChangedExternally") + "</b>";
          return MessageFormat.format(
                  Utils.getLocalizedString(
                      locale, "conflictingFileCreatedBecauseOfVersionsConflict"),
                  oldFileLink,
                  (Utils.isStringNotNullOrEmpty(modifier))
                      ? modifier
                      : Utils.getLocalizedString(locale, "anotheruser"),
                  newFileLink,
                  Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()))
              + "<br><br>" + hint;
        }
        default: {
          return MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "conflictingFileCreatedBecauseOfUnknownStorageError"),
              oldFileLink,
              newFileLink,
              Utils.getLocalizedString(locale, isSameFolder ? "same" : Field.ROOT.getName()));
        }
      }
    }
  }

  private void checkTrashStatusForSession(
      Entity segment,
      JsonObject jsonObject,
      String externalId,
      String fileId,
      String userId,
      String storageType,
      Promise<Object> trashStatusPromise) {
    JsonObject infoRequest = new JsonObject()
        .mergeIn(jsonObject)
        .put(Field.EXTERNAL_ID.getName(), externalId)
        .put(Field.FILE_ID.getName(), fileId)
        .put(Field.USER_ID.getName(), userId)
        .put(Field.STORAGE_TYPE.getName(), storageType);
    log.info("Create session validation: requesting info with data" + infoRequest.toString());
    // send request to storage to check if it's accessible or not
    eb_request(
        segment,
        StorageType.getAddress(StorageType.getStorageType(storageType)) + ".getTrashStatus",
        infoRequest,
        event -> {
          Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
          try {
            if (!event.succeeded() || event.result() == null || event.result().body() == null) {
              log.warn("Unsuccessful getting trash info for the file: " + fileId + " storage: "
                  + storageType);
            } else {
              JsonObject eventJson = (JsonObject) event.result().body();
              log.info("Create session validation: received info " + eventJson.toString());
              // if unsuccessful - remove from recent files
              if (!OK.equals(eventJson.getString(Field.STATUS.getName()))
                  || eventJson.getBoolean(Field.IS_DELETED.getName(), false)) {
                log.info("Create session validation: removing");
                Recents.deleteRecentFile(userId, StorageType.getStorageType(storageType), fileId);
                trashStatusPromise.fail("File is not accessible");
                XRayManager.endSegment(blockingSegment);
                return;
              }
            }
          } catch (Exception e) {
            log.error(
                LogPrefix.getLogPrefix()
                    + " Error on validation trash status while creating session: ",
                e);
            XRayEntityUtils.addException(segment, e);
          }
          trashStatusPromise.complete();
          XRayManager.endSegment(blockingSegment);
        });
  }
}
