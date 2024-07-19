package com.graebert.storage.mail;

import static com.graebert.storage.subscriptions.NotificationEvents.eventTypes.ENTITIESCHANGED;
import static com.graebert.storage.subscriptions.NotificationEvents.eventTypes.THREADMODIFIED;
import static com.graebert.storage.subscriptions.NotificationEvents.eventTypes.THREADTITLEMODIFIED;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.graebert.storage.Entities.CollaboratorType;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.Product;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.handler.EncodeReRouteHandler;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.emails.AccountDeletedEmail;
import com.graebert.storage.mail.emails.AccountEnabledEmail;
import com.graebert.storage.mail.emails.ChangeEmailRequest;
import com.graebert.storage.mail.emails.ConfirmRegistrationEmail;
import com.graebert.storage.mail.emails.ConflictingFileEmail;
import com.graebert.storage.mail.emails.DeshareEmail;
import com.graebert.storage.mail.emails.EmailColor;
import com.graebert.storage.mail.emails.EmailTemplateType;
import com.graebert.storage.mail.emails.Notifications.CommentThread;
import com.graebert.storage.mail.emails.Notifications.Event;
import com.graebert.storage.mail.emails.Notifications.MarkupEvent;
import com.graebert.storage.mail.emails.Notifications.MarkupMeta;
import com.graebert.storage.mail.emails.Notifications.NotificationEmail;
import com.graebert.storage.mail.emails.Notifications.ThreadMeta;
import com.graebert.storage.mail.emails.NotifyAdminRegistration;
import com.graebert.storage.mail.emails.NotifyAdminUserErase;
import com.graebert.storage.mail.emails.ObjectDeletedEmail;
import com.graebert.storage.mail.emails.ObjectMovedEmail;
import com.graebert.storage.mail.emails.ObjectMovedToRootEmail;
import com.graebert.storage.mail.emails.OwnershipChangedEmail;
import com.graebert.storage.mail.emails.RequestFileAccessEmail;
import com.graebert.storage.mail.emails.ResetPasswordRequest;
import com.graebert.storage.mail.emails.ResourceDeletedEmail;
import com.graebert.storage.mail.emails.ResourceDeshareEmail;
import com.graebert.storage.mail.emails.ResourceShareEmail;
import com.graebert.storage.mail.emails.SharingEmail;
import com.graebert.storage.mail.emails.UserMentionEmail;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.stats.logs.email.EmailActionType;
import com.graebert.storage.stats.logs.email.EmailErrorCodes;
import com.graebert.storage.stats.logs.email.EmailLog;
import com.graebert.storage.storage.Users;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.postmarkapp.postmark.Postmark;
import com.postmarkapp.postmark.client.ApiClient;
import com.postmarkapp.postmark.client.data.model.message.MessageResponse;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.mail.internet.MimeUtility;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import kong.unirest.HttpStatus;
import kong.unirest.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class MailUtil extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.MAIL;

  @NonNls
  public static final String address = "mailutil";

  public static String sender;
  public static String fromFormat = "%s (via ARES Kudo) <%s>";
  public static String fromTeamFormat = "ARES Kudo <%s>";

  @NonNls
  protected static Logger log = LogManager.getRootLogger();

  private String proName = "ARES Kudo ";
  private S3Regional s3Regional = null;
  private static ApiClient postmarkClient;

  public static String getNewEmailTemplate(String templateName, String proName) {
    try {
      String normalizedProName = proName.toLowerCase().replaceAll(" ", "_");
      if (!templateName.endsWith(".html")) {
        templateName += ".html";
      }
      String pathToRes = "/mailTmpls/" + normalizedProName + "/" + templateName;
      return Utils.readFromInputStream(MailUtil.class.getResourceAsStream(pathToRes));
    } catch (IOException ignore) {
      return emptyString;
    }
  }

  public static String getDisplayName(Item user) {
    String username = emptyString;
    if (user.get(Field.F_NAME.getName()) != null
        && !(user.getString(Field.F_NAME.getName())).trim().isEmpty()) {
      username = user.getString(Field.F_NAME.getName());
    }
    if (user.get(Field.SURNAME.getName()) != null
        && !(user.getString(Field.SURNAME.getName())).trim().isEmpty()) {
      username += " " + user.get(Field.SURNAME.getName());
    }
    if (username.trim().isEmpty()) {
      username = user.getString(Field.USER_EMAIL.getName());
    }
    return username;
  }

  public static String executeMustache(
      EmailTemplateType emailTemplateType, String proName, HashMap<String, Object> values) {
    MustacheFactory mf = new DefaultMustacheFactory();
    String html = MailUtil.getNewEmailTemplate(emailTemplateType.getTemplateName(), proName);
    Mustache mustache = mf.compile(new StringReader(html), "example");
    Writer writer = new StringWriter();
    mustache.execute(writer, values);
    html = writer.toString();
    return html;
  }

  public static String encodeName(String displayName) {
    String ownerName = displayName;
    try {
      ownerName = MimeUtility.encodeText(displayName);
    } catch (UnsupportedEncodingException e) {
      log.error("Error on encoding ownerName", e);
    }
    return ownerName;
  }

  public static HashMap<String, Object> getColors(String productName) {
    HashMap<String, Object> colors = new HashMap<>();
    if (Product.getByName(productName).equals(Product.KUDO)) {
      colors.put(Field.BACKGROUND_COLOR.getName(), EmailColor.KUDO_BACKGROUND.getHexColorCode());
      colors.put("sectionColor", EmailColor.KUDO_SECTION_COLOR.getHexColorCode());
      colors.put("mainTextColor", EmailColor.KUDO_MAIN_TEXT_COLOR.getHexColorCode());
    } else {
      colors.put(Field.BACKGROUND_COLOR.getName(), EmailColor.DS_BACKGROUND.getHexColorCode());
      colors.put("sectionColor", EmailColor.DS_SECTION_COLOR.getHexColorCode());
      colors.put("mainTextColor", EmailColor.DS_MAIN_TEXT_COLOR.getHexColorCode());
    }
    return colors;
  }

  @Override
  public void start() throws Exception {
    super.start();

    proName = config.getProperties().getSmtpProName();
    fromFormat = "%s (via " + proName + ") <%s>";
    fromTeamFormat = proName + " <%s>";
    sender = config.getProperties().getSmtpSender();
    final String postmarkClientId = config.getProperties().getPostmarkClientId();
    log.info("[POSTMARK] postmarkClientId: " + postmarkClientId);
    postmarkClient = Postmark.getApiClient(postmarkClientId);
    log.info("[POSTMARK] client base URL: " + postmarkClient.getBaseUrl());
    eb.consumer(address + ".confirmRegistration", this::doConfirmRegistration);
    eb.consumer(address + ".confirmEmail", this::doConfirmEmail);
    eb.consumer(address + ".notifyAdmins", this::doNotifyAdmins);
    eb.consumer(address + ".notifyUserErase", this::doNotifyUserRemoval);
    eb.consumer(address + ".requestAccess", this::doRequestAccess);
    eb.consumer(address + ".notifyDelete", this::doNotifyDelete);
    eb.consumer(address + ".notifyMoveForeign", this::doNotifyMoveForeign);
    eb.consumer(address + ".notifyMove", this::doNotifyMove);
    eb.consumer(address + ".notifyShare", this::doNotifyShare);
    eb.consumer(address + ".notifyDeShare", this::doNotifyDeShare);
    eb.consumer(address + ".notifyEnabled", this::doNotifyEnabled);
    eb.consumer(address + ".notifyDeleteAccount", this::doNotifyDeleteAccount);
    eb.consumer(address + ".resetRequest", this::doNotifyResetRequest);
    eb.consumer(address + ".notifyOwner", this::doNotifyOwner);
    eb.consumer(address + ".sendSharedLink", this::doSendSharedLink);
    eb.consumer(address + ".notifySaveFailed", this::doNotifySaveFailed);
    eb.consumer(address + ".notifyFileChanges", this::doNotifyFileChanges);
    eb.consumer(address + ".customEmail", this::doSendCustomEmail);
    eb.consumer(address + ".userMentioned", this::doMentionUser);

    eb.consumer(address + ".resourceShared", this::doNotifyResourceShare);
    eb.consumer(address + ".resourceUnshared", this::doNotifyResourceDeshare);
    eb.consumer(address + ".resourceDeleted", this::doNotifyResourceDeleted);

    eb.consumer(address + ".getEmailImage", this::getEmailImage);
    eb.consumer(address + ".getFileRedirectURL", this::getFileRedirectURL);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    // used for local debug
    eb.consumer(address + ".sendDemoMail", this::sendDemoMail);
  }

  private void sendDemoMail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    if (!config.getProperties().getSmtpEnableDemo()) {
      sendError(segment, message, "Demo emails aren't supported", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String fileName = json.getString(Field.FILE_NAME.getName());
    String thumbnail = json.getString(Field.THUMBNAIL.getName());
    JsonObject results = json.getJsonObject(Field.RESULTS.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    String externalId = json.getString(Field.EXTERNAL_ID.getName());

    EmailLog emailLog = new EmailLog(
        userId,
        fileId,
        StorageType.getStorageType(storageType),
        null,
        GMTHelper.utcCurrentTime(),
        true,
        NotificationEmail.EMAIL_TEMPLATE_TYPE);

    // dumb check to distinguish DS and AK
    if (!proName.contains("Kudo")) {
      emailLog.triggerError(
          EmailErrorCodes.PRODUCT_IS_INCORRECT, "Product isn't ARES Kudo " + proName);
      log.error("Attempt to send notification from non-AK product - " + proName);
      sendError(
          segment,
          message,
          "Attempt to send notification from non-AK product - " + proName,
          HttpStatus.NOT_IMPLEMENTED);
      return;
    }
    if (fileName == null || fileId == null || fileName.isEmpty() || fileId.isEmpty()) {
      emailLog.triggerError(
          EmailErrorCodes.INCOMPLETE_DATA,
          "Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ");
      log.error("Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ");
      sendError(
          segment,
          message,
          "Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ",
          HttpStatus.BAD_REQUEST);
      return;
    }
    Item user = Users.getUserById(userId);
    if (user == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3")
              .put("internalError", EmailErrorCodes.USER_NOT_FOUND),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    emailLog.setEmail(user.getString(Field.USER_EMAIL.getName()));

    JsonObject userPreferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
    final int timezoneOffset = userPreferences.containsKey("timezoneOffset")
        ? userPreferences.getInteger("timezoneOffset")
        : 0;

    final String shortFileId =
        Utils.parseItemId(fileId, Field.ID.getName()).getString(Field.ID.getName());
    JsonObject userOptions = new JsonObject(user.getJSON(Field.OPTIONS.getName()));

    try {
      if (!userOptions.containsKey(Field.EMAIL_NOTIFICATIONS.getName())
          || !Boolean.parseBoolean(userOptions.getString(Field.EMAIL_NOTIFICATIONS.getName()))) {
        emailLog.triggerError(
            EmailErrorCodes.CAPABILITY_IS_FALSE, "email_notifications capability is false");
        StackTraceElement el = Thread.currentThread().getStackTrace()[0];
        log.info("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
            + " isn't allowed to get notifications");
        // Info because this is capability message
        sendError(
            segment,
            message,
            new JsonObject()
                .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL16"))
                .put(Field.ERROR_ID.getName(), "FL16")
                .put("internalError", EmailErrorCodes.CAPABILITY_IS_FALSE),
            HttpStatus.INTERNAL_SERVER_ERROR,
            LogLevel.INFO);
        return;
      }
    } catch (Exception ignored) {
    }
    try {
      getEmailData(
              segment,
              message,
              config.getProperties().getUrl(),
              config.getProperties().getCfDistribution(),
              proName,
              storageType,
              externalId,
              fileId,
              shortFileId,
              fileName,
              thumbnail,
              true,
              user,
              userId,
              results,
              timezoneOffset)
          .onComplete(result -> {
            if (result.succeeded() && !result.result().containsKey(Field.ERROR.getName())) {
              Future<String> handler = sendEmail(
                  result.result(), NotificationEmail.EMAIL_TEMPLATE_TYPE, Promise.promise());
              handler.onComplete(sendEvent -> {
                StackTraceElement el = Thread.currentThread().getStackTrace()[1];
                if (sendEvent.succeeded()) {
                  try {
                    emailLog.setEmailSent(true);
                    emailLog.setEmailTime(GMTHelper.utcCurrentTime());
                    emailLog.setEmailId(sendEvent.result());
                    emailLog.sendToServer();
                  } catch (Exception e) {
                    log.error(e);
                  }
                  log.info("EMAIL: success " + sendEvent.result());
                  sendOK(segment, message, mills);
                } else {
                  emailLog.triggerError(
                      EmailErrorCodes.EMAIL_NOT_SENT, sendEvent.cause().getMessage());
                  log.error(
                      "(" + el.getClassName() + ":" + el.getMethodName() + ") "
                          + sendEvent.cause().getMessage(),
                      sendEvent.cause());
                  sendError(
                      segment,
                      message,
                      sendEvent.cause().getMessage(),
                      HttpStatus.BAD_REQUEST,
                      sendEvent.cause());
                }
              });
            } else {
              EmailErrorCodes errorCode =
                  EmailErrorCodes.valueOf(result.result().getString(Field.ERROR_CODE.getName()));
              String errorMessage = result.result().getString(Field.ERROR.getName());
              emailLog.triggerError(errorCode, errorMessage);
              StackTraceElement el = Thread.currentThread().getStackTrace()[0];
              log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
                  + " doesn't have access to the file: " + fileId + " cause: " + errorMessage);
              sendError(
                  segment,
                  message,
                  new JsonObject()
                      .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL6"))
                      .put(Field.ERROR_ID.getName(), "FL6"),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
    } catch (Exception mailException) {
      log.error("FILENOTIFY EXCEPTION", mailException);
    }
  }

  private void doNotifySaveFailed(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String filename = json.getString(Field.FILE_NAME.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String newName = json.getString("newName");
    String newFileId = json.getString("newFileId");
    String anotherUser = json.getString("anotherUser");
    String conflictReason = json.getString("conflictReason");
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    String url = config.getProperties().getUrl();
    String conflictingFileHelpUrl = config.getProperties().getConflictingFileHelpUrl();
    boolean isSameFolder = json.getBoolean("isSameFolder", true);
    JsonObject emailData = ConflictingFileEmail.getEmailData(
        url,
        proName,
        Utils.getLocaleFromMessage(message),
        fileId,
        filename,
        newFileId,
        newName,
        anotherUser,
        userId,
        conflictReason,
        isSameFolder,
        conflictingFileHelpUrl);

    if (Objects.isNull(emailData)) {
      return;
    }

    EmailLog emailLog = new EmailLog(
        userId,
        fileId,
        StorageType.getStorageType(storageType),
        emailData.getString("to"),
        GMTHelper.utcCurrentTime(),
        true,
        ConflictingFileEmail.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, ConflictingFileEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  /**
   * File notifications. Apparently we can't use EB inside non-verticle class. Will check
   * further later.
   **/
  private List<Event> beatifyModifiers(
      Message<JsonObject> message, JsonObject obj, Integer timezoneOffset) {
    List<Event> results = new ArrayList<>();

    if (obj.containsKey("_MODIFIERS_")) {
      JsonArray modifiers = obj.getJsonArray("_MODIFIERS_");
      modifiers.forEach(entry -> {
        JsonObject jevent = ((JsonObject) entry);
        String formattedTimestamp =
            GMTHelper.timestampToString(jevent.getLong("tmstmp"), timezoneOffset);
        final String userName =
            jevent.getJsonObject(Field.USER.getName()).getString(Field.NAME.getName());
        final JsonObject additionalData = jevent.getJsonObject(Field.DATA.getName());
        results.add(new Event(
            NotificationEvents.eventTypes.MODIFIEDFILE,
            formattedTimestamp,
            userName,
            additionalData,
            Utils.getBundleForLocale(message)));
      });
    }
    return results;
  }

  private CompositeFuture beatifyThreads(
      Entity segment,
      Message<JsonObject> message,
      String cloudfrontDistribution,
      JsonObject threads,
      Integer timezoneOffset,
      String fileId,
      String storageType) {
    // create list to send response when all threads are "beautified"
    List<Future<CommentThread>> queue = new ArrayList<>();

    threads.forEach(thread -> {
      if (thread.getKey().contains("_MARKUP_")) {
        final String markupId = thread.getKey().substring("_MARKUP_".length());
        Promise<CommentThread> handler = Promise.promise();
        queue.add(handler.future());

        formatMarkup(
            segment,
            message,
            cloudfrontDistribution,
            timezoneOffset,
            fileId,
            thread,
            markupId,
            handler,
            storageType);

      } else if (!thread.getKey().equals("_MODIFIERS_")) {
        final String threadId = thread.getKey();
        Promise<CommentThread> handler = Promise.promise();
        queue.add(handler.future());
        eb_request(
            segment,
            CommentVerticle.address + ".getThreadInfo",
            new JsonObject()
                .put(Field.THREAD_ID.getName(), threadId)
                .put(Field.FILE_ID.getName(), fileId),
            response -> {
              if (response.succeeded()) {
                JsonObject responseData = (JsonObject) response.result().body();
                String threadTitle =
                    Utils.isStringNotNullOrEmpty(responseData.getString(Field.TITLE.getName()))
                        ? responseData.getString(Field.TITLE.getName())
                        : "Untitled";
                ThreadMeta metaData = new ThreadMeta(
                    threadTitle,
                    GMTHelper.timestampToString(responseData.getLong("tmstmp"), timezoneOffset),
                    responseData
                        .getJsonObject(Field.AUTHOR.getName())
                        .getString(Field.NAME.getName()),
                    responseData.getString(Field.TEXT.getName()));

                List<Event> events = new ArrayList<>();
                JsonArray entries = (JsonArray) thread.getValue();
                entries.forEach(event -> {
                  JsonObject jevent = ((JsonObject) event);
                  final JsonObject additionalData = jevent.getJsonObject(Field.DATA.getName());
                  final String formattedTimestamp =
                      GMTHelper.timestampToString(jevent.getLong("tmstmp"), timezoneOffset);
                  final String userName =
                      jevent.getJsonObject(Field.USER.getName()).getString(Field.NAME.getName());
                  final NotificationEvents.eventTypes eventType =
                      NotificationEvents.eventTypes.valueOf(jevent.getString("event"));
                  // we should just show label in thread's header.
                  // See mockups and
                  // https://graebert.atlassian.net/browse/XENON-37HttpStatus.INTERNAL_SERVER_ERROR
                  if (eventType.equals(NotificationEvents.eventTypes.NEWTHREAD)) {
                    metaData.setLabel(Utils.getLocalizedString(message, "NewThread"));
                    metaData.setLabelColor("#11893C");
                  } else {
                    events.add(new Event(
                        eventType,
                        formattedTimestamp,
                        userName,
                        additionalData,
                        Utils.getBundleForLocale(message)));
                  }
                });
                handler.complete(new CommentThread(events, metaData));
              } else {
                handler.fail("Couldn't get thread info");
              }
            });
      }
    });

    return TypedCompositeFuture.join(queue);
  }

  private void iteratePictureNote(
      Entity segment,
      Message<JsonObject> message,
      String cloudfrontDistribution,
      String fileId,
      Promise<JsonObject> handler,
      String storageType,
      JsonObject note) {
    String noteId = note.getString(Field.ID.getName());
    byte[] pictureNoteData = s3Regional.getFromBucket(
        config.getProperties().getS3Bucket(), "attachments/" + fileId + "/" + noteId);
    String extension = s3Regional.getObjectExtension(
        config.getProperties().getS3Bucket(), "attachments/" + fileId + "/" + noteId);
    String emailAttachmentName =
        fileId + "_" + noteId + "_" + GMTHelper.utcCurrentTime() + extension;
    s3Regional.putPublic(
        cloudfrontDistribution, "emailAttachments/" + emailAttachmentName, pictureNoteData);
    String noteURL =
        s3Regional.getLink(cloudfrontDistribution, "emailAttachments/" + emailAttachmentName);
    JsonObject result = new JsonObject();
    result.put(Field.URL.getName(), noteURL);
    eb_request(
        segment,
        CommentVerticle.address + ".getAttachmentInfo",
        new JsonObject()
            .put("attachmentId", noteId)
            .put(
                Field.FILE_ID.getName(),
                fileId) // should be short id - it's guaranteed at this point, but be
            // careful with this
            .put(Field.LOCALE.getName(), Utils.getLocaleFromMessage(message))
            .put(Field.STORAGE_TYPE.getName(), storageType),
        attachmentResponse -> {
          if (attachmentResponse.succeeded()) {
            JsonObject attachmentMetadata =
                (JsonObject) attachmentResponse.result().body();
            JsonArray tags = attachmentMetadata.containsKey("tags")
                ? attachmentMetadata.getJsonArray("tags")
                : new JsonArray();
            String tagsText;
            if (!tags.isEmpty()) {
              tagsText = tags.stream()
                  .map(tag -> ((JsonObject) tag).getString(Field.TEXT.getName()))
                  .collect(Collectors.joining(", "));
            } else {
              tagsText = Utils.getLocalizedString(message, "NoTagsAvailable");
            }
            result.put("tags", tagsText);
          }
          handler.complete(result);
        });
  }

  private void formatMarkup(
      Entity segment,
      Message<JsonObject> message,
      String cloudfrontDistribution,
      Integer timezoneOffset,
      String fileId,
      Map.Entry<String, Object> entry,
      String markupId,
      Promise<CommentThread> handler,
      String storageType) {
    eb_request(
        segment,
        CommentVerticle.address + ".getMarkupInfo",
        new JsonObject()
            .put(Field.MARKUP_ID.getName(), markupId)
            .put(Field.FILE_ID.getName(), fileId),
        response -> {
          if (response.succeeded()) {
            JsonObject responseData = (JsonObject) response.result().body();
            final CommentVerticle.MarkupType markupType =
                CommentVerticle.MarkupType.getType(responseData.getString(Field.TYPE.getName()));
            String metadataText = responseData.containsKey(Field.TEXT.getName())
                    && Utils.isStringNotNullOrEmpty(responseData.getString(Field.TEXT.getName()))
                ? responseData.getString(Field.TEXT.getName())
                : emptyString;
            if (markupType.equals(CommentVerticle.MarkupType.PICTURENOTE)) {
              // Format: [Field.URL.getName(), "tags"]
              List<List<String>> pictureNotesData = new ArrayList<>();
              // pre-generate urls for further use and save it to the obj
              JsonArray notes = responseData.getJsonArray("notes");
              List<Future<JsonObject>> queue = new ArrayList<>();
              notes.forEach(noteObj -> {
                Promise<JsonObject> pictureNoteHandler = Promise.promise();
                queue.add(pictureNoteHandler.future());
                iteratePictureNote(
                    segment,
                    message,
                    cloudfrontDistribution,
                    fileId,
                    pictureNoteHandler,
                    storageType,
                    (JsonObject) noteObj);
              });
              TypedCompositeFuture.join(queue).onComplete(data -> {
                List<JsonObject> results = data.result().list(); // {url,tags}
                results.forEach(jsonObject -> {
                  ArrayList<String> pictureNoteData = new ArrayList<>();
                  pictureNoteData.add(jsonObject.getString(Field.URL.getName()));
                  pictureNoteData.add(jsonObject.getString("tags"));
                  pictureNotesData.add(pictureNoteData);
                });
                responseData.put("pictureNotesData", new JsonArray(pictureNotesData));
                finishMarkupFormatting(
                    message,
                    timezoneOffset,
                    entry,
                    handler,
                    responseData,
                    markupType,
                    metadataText,
                    pictureNotesData);
              });
            } else if (markupType.equals(CommentVerticle.MarkupType.VOICENOTE)) {
              try {
                if (responseData.containsKey("notes")) {
                  JsonArray notes = responseData.getJsonArray("notes");
                  JsonObject firstNote = notes.getJsonObject(0);
                  eb_request(
                      segment,
                      CommentVerticle.address + ".getAttachmentInfo",
                      new JsonObject()
                          .put("attachmentId", firstNote.getString(Field.ID.getName()))
                          .put(
                              Field.FILE_ID.getName(),
                              fileId) // should be short id - it's guaranteed at this
                          // point, but be careful with this
                          .put(Field.LOCALE.getName(), Utils.getLocaleFromMessage(message))
                          .put(Field.STORAGE_TYPE.getName(), storageType),
                      attachmentResponse -> {
                        if (attachmentResponse.succeeded()) {
                          JsonObject attachmentMetadata =
                              (JsonObject) attachmentResponse.result().body();
                          JsonArray transcript = attachmentMetadata.containsKey("transcript")
                              ? attachmentMetadata.getJsonArray("transcript")
                              : new JsonArray();
                          String newMetadataText;
                          if (!transcript.isEmpty()) {
                            newMetadataText = transcript.getString(0);
                          } else {
                            newMetadataText = Utils.getLocalizedString(message, "NoTranscript");
                          }
                          finishMarkupFormatting(
                              message,
                              timezoneOffset,
                              entry,
                              handler,
                              responseData,
                              markupType,
                              !newMetadataText.isEmpty() ? newMetadataText : metadataText,
                              null);
                        } else {
                          finishMarkupFormatting(
                              message,
                              timezoneOffset,
                              entry,
                              handler,
                              responseData,
                              markupType,
                              metadataText,
                              null);
                        }
                      });
                } else {
                  finishMarkupFormatting(
                      message,
                      timezoneOffset,
                      entry,
                      handler,
                      responseData,
                      markupType,
                      metadataText,
                      null);
                }
              } catch (Exception ignored) {
                finishMarkupFormatting(
                    message,
                    timezoneOffset,
                    entry,
                    handler,
                    responseData,
                    markupType,
                    metadataText,
                    null);
                // let's ignore this exception as we just won't have a transcript - it's not that
                // bad
              }
            } else {
              finishMarkupFormatting(
                  message,
                  timezoneOffset,
                  entry,
                  handler,
                  responseData,
                  markupType,
                  metadataText,
                  null);
            }

          } else {
            handler.fail("Couldn't get markup info");
          }
        });
  }

  private void finishMarkupFormatting(
      Message<JsonObject> message,
      Integer timezoneOffset,
      Map.Entry<String, Object> entry,
      Promise<CommentThread> handler,
      JsonObject responseData,
      CommentVerticle.MarkupType markupType,
      String metadataText,
      List<List<String>> pictureNotesData) {
    MarkupMeta metaData = new MarkupMeta(
        responseData.getString(Field.TYPE.getName()),
        GMTHelper.timestampToString(
            responseData.getLong(Field.TIMESTAMP.getName()), timezoneOffset),
        responseData.getJsonObject(Field.AUTHOR.getName()).getString(Field.NAME.getName()),
        metadataText,
        markupType);

    List<Event> events = new ArrayList<>();
    JsonArray entries = (JsonArray) entry.getValue();
    entries.forEach(event -> {
      JsonObject jevent = ((JsonObject) event);
      final String formattedTimestamp =
          GMTHelper.timestampToString(jevent.getLong("tmstmp"), timezoneOffset);
      final String userName =
          jevent.getJsonObject(Field.USER.getName()).getString(Field.NAME.getName());
      final NotificationEvents.eventTypes eventType =
          NotificationEvents.eventTypes.valueOf(jevent.getString("event"));
      if (markupType.equals(CommentVerticle.MarkupType.PICTURENOTE) && pictureNotesData != null) {
        metaData.setPictureNotesData(pictureNotesData);
      }
      if (eventType.equals(NotificationEvents.eventTypes.NEWMARKUP)) {
        metaData.setLabelColor("#11893C");
        switch (markupType) {
          case STAMP:
            metaData.setLabel(Utils.getLocalizedString(message, "NewStamp"));
            break;
          case PICTURENOTE:
            metaData.setLabel(Utils.getLocalizedString(message, "NewPictureRecording"));
            break;
          case VOICENOTE:
            metaData.setLabel(Utils.getLocalizedString(message, "NewVoiceRecording"));
            break;
          default:
            metaData.setLabel(Utils.getLocalizedString(message, "NewMarkup"));
            break;
        }
      } else {
        events.add(new MarkupEvent(
            eventType,
            formattedTimestamp,
            userName,
            Utils.getBundleForLocale(message),
            markupType,
            responseData));
      }
    });
    handler.complete(new CommentThread(events, metaData));
  }

  private String getSubjectForNotificationEmail(
      Message<JsonObject> message, JsonObject notifications, String fileName) {
    String userEmail = null;
    String userName = null;
    String eventType = null;
    String markupType = null;
    // XENON-37536
    List<NotificationEvents.eventTypes> groupingEvents =
        Arrays.asList(THREADMODIFIED, ENTITIESCHANGED, THREADTITLEMODIFIED);
    boolean areMultipleEditors = false;
    boolean areMultipleEvents =
        new JSONObject(notifications.toString()).keySet().size() > 1;
    boolean areGroupedEvents = true;
    if (!areMultipleEvents) {
      for (Map.Entry<String, Object> notification : notifications) {
        JsonArray notificationThread = (JsonArray) notification.getValue();
        for (Object notificationObject : notificationThread) {
          JsonObject notificationEntry = ((JsonObject) notificationObject);
          String event = notificationEntry.getString("event");
          if (event.contains("MARKUP")) {
            try {
              markupType = notificationEntry
                  .getJsonObject(Field.DATA.getName())
                  .getString(Field.TYPE.getName());
            } catch (Exception ignored) {
            }
          }
          if (eventType == null) {
            eventType = event;
          } else if (!eventType.equals(event)) {
            // grouping events - to group thread modifications (XENON-37536)
            // if events are not equal -
            // there are different event types and we should set generic subject
            areMultipleEvents = true;
            if (!groupingEvents.contains(NotificationEvents.eventTypes.valueOf(event))) {
              areGroupedEvents = false;
              break;
            }
          }

          // no point to check further if we know that there are multiple editors
          if (!areMultipleEditors) {
            JsonObject userObject = notificationEntry.getJsonObject(Field.USER.getName());
            if (userObject != null) {
              String newEmail = userObject.getString(Field.EMAIL.getName());
              String newUserName = userObject.getString(Field.NAME.getName());
              if (userEmail == null) {
                userEmail = newEmail;
                userName = newUserName;
              } else if (!userEmail.equals(newEmail)) {
                areMultipleEditors = true;
              }
            }
          }
        }
        if (areMultipleEvents) {
          break;
        }
      }

      if (areMultipleEvents && areGroupedEvents) {
        if (areMultipleEditors) {
          return MessageFormat.format(
              Utils.getLocalizedString(message, "ThreadsHaveBeenModifiedByMultipleUsers"),
              fileName);
        }
        return MessageFormat.format(
            Utils.getLocalizedString(message, "ThreadHasBeenModifiedByUser"), fileName, userName);
      }

      // recheck again to proceed
      if (!areMultipleEvents && eventType != null) {
        switch (NotificationEvents.eventTypes.valueOf(eventType)) {
          case NEWTHREAD: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "FileHasNewThreadsByMultipleUsers"), fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "FileHasNewThreadByUser"), fileName, userName);
          }
          case ENTITIESCHANGED:
          case THREADMODIFIED:
          case THREADTITLEMODIFIED: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "ThreadsHaveBeenModifiedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "ThreadHasBeenModifiedByUser"),
                fileName,
                userName);
          }
          case THREADRESOLVED: {
            return MessageFormat.format(
                Utils.getLocalizedString(message, "ThreadHasBeenResolved"), fileName);
          }
          case THREADREOPENED: {
            return MessageFormat.format(
                Utils.getLocalizedString(message, "ThreadHasBeenReopened"), fileName);
          }
          case THREADDELETED: {
            return MessageFormat.format(
                Utils.getLocalizedString(message, "ThreadHasBeenDeleted"), fileName);
          }
          case NEWMARKUP: {
            if (Utils.isStringNotNullOrEmpty(markupType)
                && CommentVerticle.MarkupType.getType(markupType) != null) {
              switch (CommentVerticle.MarkupType.getType(markupType)) {
                case STAMP:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(message, "NewStampsByMultipleUsers"), fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "NewStampByUser"), fileName, userName);
                case PICTURENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(message, "NewPictureRecordingsByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "NewPictureRecordingByUser"),
                      fileName,
                      userName);
                case VOICENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(message, "NewVoiceRecordingsByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "NewVoiceRecordingByUser"),
                      fileName,
                      userName);
                default:
                  break;
              }
            }
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "NewMarkupByMultipleUsers"), fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "NewMarkupByUser"), fileName, userName);
          }
          case MARKUPDELETED: {
            if (Utils.isStringNotNullOrEmpty(markupType)
                && CommentVerticle.MarkupType.getType(markupType) != null) {
              switch (CommentVerticle.MarkupType.getType(markupType)) {
                case STAMP:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(message, "StampsHaveBeenDeletedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "StampHasBeenDeletedByUser"),
                      fileName,
                      userName);
                case PICTURENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(
                            message, "PictureRecordingsHaveBeenDeletedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "PictureRecordingHasBeenDeletedByUser"),
                      fileName,
                      userName);
                case VOICENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(
                            message, "VoiceRecordingsHaveBeenDeletedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "VoiceRecordingHasBeenDeletedByUser"),
                      fileName,
                      userName);
                default:
                  break;
              }
            }
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "MarkupsHaveBeenDeletedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "MarkupHasBeenDeletedByUser"),
                fileName,
                userName);
          }
          case MARKUPRESOLVED:
          case MARKUPACTIVATED:
          case MODIFIEDMARKUP: {
            if (Utils.isStringNotNullOrEmpty(markupType)
                && CommentVerticle.MarkupType.getType(markupType) != null) {
              switch (CommentVerticle.MarkupType.getType(markupType)) {
                case STAMP:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(message, "StampsHaveBeenModifiedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "StampHasBeenModifiedByUser"),
                      fileName,
                      userName);
                case PICTURENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(
                            message, "PictureRecordingsHaveBeenModifiedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "PictureRecordingHasBeenModifiedByUser"),
                      fileName,
                      userName);
                case VOICENOTE:
                  if (areMultipleEditors) {
                    return MessageFormat.format(
                        Utils.getLocalizedString(
                            message, "VoiceRecordingsHaveBeenModifiedByMultipleUsers"),
                        fileName);
                  }
                  return MessageFormat.format(
                      Utils.getLocalizedString(message, "VoiceRecordingHasBeenModifiedByUser"),
                      fileName,
                      userName);
                default:
                  break;
              }
            }
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "MarkupsHaveBeenModifiedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "MarkupHasBeenModifiedByUser"),
                fileName,
                userName);
          }
          case NEWCOMMENT: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "FileHasNewCommentsByMultipleUsers"), fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "FileHasNewCommentByUser"), fileName, userName);
          }
          case MODIFIEDCOMMENT: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "CommentsHaveBeenModifiedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "CommentHasBeenModifiedByUser"),
                fileName,
                userName);
          }
          case MODIFIEDFILE: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "FileHasBeenModifiedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "FileHasBeenModifiedByUser"), fileName, userName);
          }
          case DELETEDCOMMENT: {
            if (areMultipleEditors) {
              return MessageFormat.format(
                  Utils.getLocalizedString(message, "CommentsHaveBeenDeletedByMultipleUsers"),
                  fileName);
            }
            return MessageFormat.format(
                Utils.getLocalizedString(message, "CommentHasBeenDeletedByUser"),
                fileName,
                userName);
          }
          default:
            return MessageFormat.format(
                Utils.getLocalizedString(message, "RecentChangesTitle"), fileName);
        }
      }
    }

    return MessageFormat.format(Utils.getLocalizedString(message, "RecentChangesTitle"), fileName);
  }

  private Future<Boolean> checkFileAvailability(
      Entity segment, final String userId, final String fileId, final String storageType) {
    Promise<Boolean> handler = Promise.promise();
    eb_request(
        segment,
        StorageType.getAddress(StorageType.getStorageType(storageType)) + ".getTrashStatus",
        new JsonObject().put(Field.USER_ID.getName(), userId).put(Field.FILE_ID.getName(), fileId),
        event -> {
          try {
            if (!event.succeeded() || event.result() == null || event.result().body() == null) {
              handler.fail("Get trash status isn't successful");
            } else {
              JsonObject eventJson = (JsonObject) event.result().body();
              // if unsuccessful - remove from recent files
              if (!Field.OK.getName().equals(eventJson.getString(Field.STATUS.getName()))
                  || eventJson.getBoolean(Field.IS_DELETED.getName(), false)) {
                handler.fail("File's not accessible: " + eventJson);
              } else {
                // otherwise - add into result array
                handler.complete(true);
              }
            }
          } catch (Exception e) {
            DynamoBusModBase.log.error(
                LogPrefix.getLogPrefix() + " Error on validation of recent files: ", e);
            XRayEntityUtils.addException(segment, e);
            if (!handler.tryComplete()) {
              handler.fail(e);
            }
          }
        });
    return handler.future();
  }

  private Future<String> checkSubscription(
      Entity segment, final String userId, final String fileId) {
    Promise<String> handler = Promise.promise();
    eb_request(
        segment,
        Subscriptions.address + ".getSubscription",
        new JsonObject()
            .put(Field.USER_ID.getName(), userId)
            .put(Field.FILE_ID.getName(), fileId)
            .put("validateLink", true),
        event -> {
          try {
            if (!event.succeeded() || event.result() == null || event.result().body() == null) {
              handler.fail("Unsuccessfull in getting subscription");
            } else {
              // we should get subscription info to see if there's a subscription and there is a
              // token to include or not
              String token;
              JsonObject subscriptionInfo =
                  ((JsonObject) event.result().body()).getJsonObject("subscription");
              if (subscriptionInfo.fieldNames().isEmpty()) {
                handler.fail("No subscription");
              } else if (subscriptionInfo.containsKey(Field.TOKEN.getName())
                  && Utils.isStringNotNullOrEmpty(subscriptionInfo.getString(Field.TOKEN.getName()))
                  && !subscriptionInfo
                      .getJsonArray("scope")
                      .contains(Subscriptions.subscriptionScope.GLOBAL.toString())) {
                token = subscriptionInfo.getString(Field.TOKEN.getName());
                handler.complete(token);
              } else {
                handler.complete(null);
              }
            }
          } catch (Exception e) {
            DynamoBusModBase.log.error(
                LogPrefix.getLogPrefix() + " Error on validation of recent files: ", e);
            XRayEntityUtils.addException(segment, e);
            if (!handler.tryComplete()) {
              handler.fail(e);
            }
          }
        });
    return handler.future();
  }

  private Future<String> checkIfNotificationsShouldBeSent(
      Entity segment, final String userId, final String fileId, final String storageType) {
    Promise<String> handler = Promise.promise();
    checkSubscription(segment, userId, fileId).onComplete(subscriptionCheck -> {
      if (subscriptionCheck.succeeded()) {
        String token = subscriptionCheck.result();
        // we don't have token - have to check an access
        if (!Utils.isStringNotNullOrEmpty(token)) {
          checkFileAvailability(segment, userId, fileId, storageType).onComplete(fileCheck -> {
            if (fileCheck.succeeded()) {
              handler.complete(null);
            } else {
              handler.fail(fileCheck.cause());
            }
          });
        } else {
          handler.complete(token);
        }
      } else {
        handler.fail(subscriptionCheck.cause());
      }
    });
    return handler.future();
  }

  public Future<JsonObject> getEmailData(
      Entity segment,
      Message<JsonObject> message,
      String instanceURL,
      String cloudfrontDistribution,
      String proName,
      String storageType,
      String externalId,
      String fileId,
      String shortFileId,
      String fileName,
      String thumbnail,
      boolean doesThumbnailExist,
      Item user,
      String userId,
      JsonObject results,
      int timezoneOffset) {
    Promise<JsonObject> handler = Promise.promise();
    checkIfNotificationsShouldBeSent(segment, userId, shortFileId, storageType)
        .onComplete(checkResult -> {
          if (checkResult.succeeded()) {
            String token = checkResult.result();
            String fileLink =
                instanceURL + "api/email/file/" + fileId + "?st=" + storageType.toUpperCase()
                    + (Utils.isStringNotNullOrEmpty(externalId)
                        ? "&externalId=" + externalId
                        : emptyString)
                    + "&email=" + user.getString(Field.USER_EMAIL.getName());
            if (Utils.isStringNotNullOrEmpty(token)) {
              fileLink += "&token=" + token;
            }
            HashMap<String, Object> scopes = new HashMap<>();
            scopes.put("FirstName", user.getString(Field.F_NAME.getName()));
            scopes.put("FileLink", fileLink);
            scopes.put("Filename", fileName);
            log.info("[ MAIL ] formatting notification email data. DoesThumbnailExist: "
                + doesThumbnailExist);
            String emailThumbnailName = "kudo_email_thumbnail_placeholder.png";
            if (doesThumbnailExist) {
              byte[] thumbnailData =
                  s3Regional.getFromBucket(cloudfrontDistribution, "png/" + thumbnail);
              // thumbnail string has ".png" extension in it. Let's modify filename
              String nameWithoutExtension = thumbnail.substring(0, thumbnail.lastIndexOf("."));
              String fileExtension = thumbnail.substring(thumbnail.lastIndexOf("."));
              emailThumbnailName =
                  nameWithoutExtension + GMTHelper.utcCurrentTime() + fileExtension;
              s3Regional.putPublic(
                  cloudfrontDistribution, "emails/" + emailThumbnailName, thumbnailData);
            }

            String thumbnailURL = instanceURL + "api/img/email/" + emailThumbnailName + "?email="
                + user.getString(Field.USER_EMAIL.getName()) + "&st=" + storageType + "&fid="
                + shortFileId;
            log.info("[ MAIL ] Final thumbnailURL: " + thumbnailURL);
            // String thumbnailURL = s3Regional.getLink(cloudfrontDistribution + "/emails",
            // emailThumbnailName);
            scopes.put("ThumbnailURL", thumbnailURL);
            scopes.put(
                "Unsubscribe", instanceURL + "files/unsubscribe/" + fileId + "?source=email");

            // Those are required for proper translations
            scopes.put(
                "ChangesForFilename",
                MessageFormat.format(
                    Utils.getLocalizedString(message, "ChangesForFilename"), fileName));
            scopes.put("CADInCloud", Utils.getLocalizedString(message, "CADInCloud"));
            scopes.put(
                "ThereAreRecentChangesIn",
                Utils.getLocalizedString(message, "ThereAreRecentChangesIn"));
            scopes.put("OpenDrawing", Utils.getLocalizedString(message, "OpenDrawing"));
            scopes.put(
                "UnsubscribeThisDrawing",
                Utils.getLocalizedString(message, "UnsubscribeThisDrawing"));
            scopes.put("ClickHere", Utils.getLocalizedString(message, "ClickHere"));
            scopes.put(
                "AutomatedEmailDontReply",
                Utils.getLocalizedString(message, "AutomatedEmailDontReply"));
            scopes.put(
                "HiFirstName",
                MessageFormat.format(
                    Utils.getLocalizedString(message, "HiFirstName"),
                    user.getString(Field.F_NAME.getName())));

            beatifyThreads(
                    segment,
                    message,
                    cloudfrontDistribution,
                    results,
                    timezoneOffset,
                    shortFileId,
                    storageType)
                .onComplete(threads -> {
                  scopes.put("threads", threads.result().list());
                  scopes.put("modifiers", beatifyModifiers(message, results, timezoneOffset));
                  scopes.putAll(MailUtil.getColors(proName));
                  String from = String.format(MailUtil.fromTeamFormat, MailUtil.sender);
                  String html = MailUtil.executeMustache(
                      NotificationEmail.EMAIL_TEMPLATE_TYPE, proName, scopes);
                  String subject = getSubjectForNotificationEmail(message, results, fileName);
                  String email = user.getString(Field.USER_EMAIL.getName());
                  handler.complete(new JsonObject()
                      .put("from", from)
                      .put("to", email)
                      .put("subject", subject)
                      .put("html", html));
                });
          } else {
            handler.complete(new JsonObject()
                .put(Field.ERROR_CODE.getName(), EmailErrorCodes.NO_FILE_ACCESS)
                .put(
                    Field.ERROR.getName(),
                    "No access to the file: " + checkResult.cause().getMessage()));
          }
        });
    return handler.future();
  }

  /**
   * File notifications block end.
   **/
  private void doNotifyFileChanges(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String fileName = json.getString(Field.FILE_NAME.getName());
    String thumbnail = json.getString(Field.THUMBNAIL.getName());
    JsonObject results = json.getJsonObject(Field.RESULTS.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    String externalId = json.getString(Field.EXTERNAL_ID.getName());
    boolean doesThumbnailExist = json.getBoolean("doesThumbnailExist", false);
    log.info("[ MAIL ] Going to send file notification for fileId: " + fileId
        + " doesThumbnailExist: " + doesThumbnailExist + " thumbnail: " + thumbnail);

    EmailLog emailLog = new EmailLog(
        userId,
        fileId,
        StorageType.getStorageType(storageType),
        null,
        GMTHelper.utcCurrentTime(),
        true,
        NotificationEmail.EMAIL_TEMPLATE_TYPE);

    // dumb check to distinguish DS and AK
    if (!proName.contains("Kudo")) {
      emailLog.triggerError(
          EmailErrorCodes.PRODUCT_IS_INCORRECT, "Product isn't ARES Kudo " + proName);
      log.error("Attempt to send notification from non-AK product - " + proName);
      sendError(
          segment,
          message,
          "Attempt to send notification from non-AK product - " + proName,
          HttpStatus.NOT_IMPLEMENTED);
      return;
    }
    if (fileName == null || fileId == null || fileName.isEmpty() || fileId.isEmpty()) {
      emailLog.triggerError(
          EmailErrorCodes.INCOMPLETE_DATA,
          "Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ");
      log.error("Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ");
      sendError(
          segment,
          message,
          "Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ",
          HttpStatus.BAD_REQUEST);
      return;
    }
    Item user = Users.getUserById(userId);
    if (user == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3")
              .put("internalError", EmailErrorCodes.USER_NOT_FOUND),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    emailLog.setEmail(user.getString(Field.USER_EMAIL.getName()));

    JsonObject userPreferences = new JsonObject(user.getJSON(Field.PREFERENCES.getName()));
    final int timezoneOffset = userPreferences.containsKey("timezoneOffset")
        ? userPreferences.getInteger("timezoneOffset")
        : 0;

    final String shortFileId =
        Utils.parseItemId(fileId, Field.ID.getName()).getString(Field.ID.getName());
    JsonObject userOptions = new JsonObject(user.getJSON(Field.OPTIONS.getName()));

    try {
      if (!userOptions.containsKey(Field.EMAIL_NOTIFICATIONS.getName())
          || !Boolean.parseBoolean(userOptions.getString(Field.EMAIL_NOTIFICATIONS.getName()))) {
        emailLog.triggerError(
            EmailErrorCodes.CAPABILITY_IS_FALSE, "email_notifications capability is false");
        StackTraceElement el = Thread.currentThread().getStackTrace()[0];
        log.info("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
            + " isn't allowed to get notifications");
        // Info because this is capability message
        sendError(
            segment,
            message,
            new JsonObject()
                .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL16"))
                .put(Field.ERROR_ID.getName(), "FL16")
                .put("internalError", EmailErrorCodes.CAPABILITY_IS_FALSE),
            HttpStatus.INTERNAL_SERVER_ERROR,
            LogLevel.INFO);
        return;
      }
    } catch (Exception ignored) {
    }
    try {
      getEmailData(
              segment,
              message,
              config.getProperties().getUrl(),
              config.getProperties().getCfDistribution(),
              proName,
              storageType,
              externalId,
              fileId,
              shortFileId,
              fileName,
              thumbnail,
              doesThumbnailExist,
              user,
              userId,
              results,
              timezoneOffset)
          .onComplete(result -> {
            if (result.succeeded() && !result.result().containsKey(Field.ERROR.getName())) {
              Future<String> handler =
                  sendEmail(result.result(), NotificationEmail.EMAIL_TEMPLATE_TYPE);
              handler.onComplete(sendEvent -> {
                StackTraceElement el = Thread.currentThread().getStackTrace()[1];
                if (sendEvent.succeeded()) {
                  try {
                    emailLog.setEmailSent(true);
                    emailLog.setEmailTime(GMTHelper.utcCurrentTime());
                    emailLog.setEmailId(sendEvent.result());
                    emailLog.sendToServer();
                  } catch (Exception e) {
                    log.error(e);
                  }
                  log.info("EMAIL: success " + sendEvent.result());
                  sendOK(segment, message, mills);
                } else {
                  emailLog.triggerError(
                      EmailErrorCodes.EMAIL_NOT_SENT, sendEvent.cause().getMessage());
                  log.error(
                      "(" + el.getClassName() + ":" + el.getMethodName() + ") "
                          + sendEvent.cause().getMessage(),
                      sendEvent.cause());
                  sendError(
                      segment,
                      message,
                      sendEvent.cause().getMessage(),
                      HttpStatus.BAD_REQUEST,
                      sendEvent.cause());
                }
              });
            } else {
              EmailErrorCodes errorCode =
                  EmailErrorCodes.valueOf(result.result().getString(Field.ERROR_CODE.getName()));
              String errorMessage = result.result().getString(Field.ERROR.getName());
              emailLog.triggerError(errorCode, errorMessage);
              StackTraceElement el = Thread.currentThread().getStackTrace()[0];
              log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + userId
                  + " doesn't have access to the file: " + fileId + " cause: " + errorMessage);
              sendError(
                  segment,
                  message,
                  new JsonObject()
                      .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL6"))
                      .put(Field.ERROR_ID.getName(), "FL6"),
                  HttpStatus.INTERNAL_SERVER_ERROR);
            }
          });
    } catch (Exception mailException) {
      log.error("FILENOTIFY EXCEPTION", mailException);
    }
  }

  private void doSendSharedLink(Message<JsonObject> message) {
    // 03/08/20 DK: Not sure what it's supposed to be. For now it seems to not be used anywhere.
    sendNotImplementedError(message);
  }

  private void doNotifyOwner(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String name = json.getString(Field.NAME.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    String url = config.getProperties().getUrl();
    String fileId = json.getString(Field.ID.getName());
    String newOwner = json.getString("newOwner");
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    JsonObject emailData = OwnershipChangedEmail.getEmailData(
        url,
        proName,
        Utils.getLocaleFromMessage(message),
        storageType,
        type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
        fileId,
        name,
        ownerId,
        newOwner);

    if (Objects.isNull(emailData)) {
      return;
    }

    // email log
    EmailLog emailLog = new EmailLog(
        ownerId,
        fileId,
        storageType,
        emailData.getString("to"),
        GMTHelper.utcCurrentTime(),
        true,
        OwnershipChangedEmail.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, OwnershipChangedEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doNotifyResetRequest(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String hash = message.body().getString(Field.HASH.getName());
    String userId = message.body().getString(Field.ID.getName());
    long timer = message.body().getLong("timer");
    JsonObject emailData = ResetPasswordRequest.getEmailData(
        config.getProperties().getUrl(),
        proName,
        Utils.getLocaleFromMessage(message),
        userId,
        hash,
        timer);

    if (Objects.isNull(emailData)) {
      return;
    }

    EmailLog emailLog = new EmailLog(
        userId,
        null,
        null,
        emailData.getString("to"),
        GMTHelper.utcCurrentTime(),
        true,
        ResetPasswordRequest.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, ResetPasswordRequest.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doNotifyDeleteAccount(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = message.body().getString(Field.ID.getName());
    String greetingName = message.body().getString("greetingName");
    String email = message.body().getString(Field.EMAIL.getName());

    JsonObject emailData = AccountDeletedEmail.getEmailData(
        proName, Utils.getLocaleFromMessage(message), greetingName, email);

    EmailLog emailLog = new EmailLog(
        userId,
        null,
        null,
        email,
        GMTHelper.utcCurrentTime(),
        true,
        AccountDeletedEmail.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, AccountDeletedEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doNotifyEnabled(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    final String userId = json.getString(Field.ID.getName());
    boolean isEnabled = json.getBoolean(Field.ENABLED.getName());
    JsonObject emailData = AccountEnabledEmail.getEmailData(
        proName, Utils.getLocaleFromMessage(message), userId, isEnabled);

    if (Objects.isNull(emailData)) {
      return;
    }

    EmailLog emailLog = new EmailLog(
        userId,
        null,
        null,
        emailData.getString("to"),
        GMTHelper.utcCurrentTime(),
        true,
        AccountEnabledEmail.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, AccountEnabledEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [account enable to user:" + userId
                + "]");
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doNotifyDeShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String name = json.getString(Field.NAME.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    JsonArray users = json.getJsonArray(Field.USERS.getName());
    String fileId = json.getString(Field.FILE_ID.getName());

    EmailLog emailLog = new EmailLog(
        ownerId,
        fileId,
        StorageType.getStorageType(storageType),
        null,
        GMTHelper.utcCurrentTime(),
        true,
        DeshareEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    String ownerName = getDisplayName(owner);
    String ownerEmail = owner.getString(Field.USER_EMAIL.getName());
    emailLog.setEmail(ownerEmail);

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();
    for (Object userObj : users) {
      String collaborator = (String) userObj;
      Promise<String> promise = Promise.promise();
      try {
        JsonObject emailData = DeshareEmail.getEmailData(
            proName,
            Utils.getLocaleFromMessage(message),
            ownerName,
            collaborator,
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            name);
        Future<String> handler = sendEmail(emailData, DeshareEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [deShare from owner:" + ownerId
                + " to user:" + collaborator + "]");
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    }
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      // 30/07/20 DK - should we send error for failed emails? Right now we don't use it at all.
      sendOK(segment, message, mills);
    });
  }

  private void doNotifyResourceShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String resourceId = json.getString(Field.RESOURCE_ID.getName());
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String collaboratorId = json.getString(Field.COLLABORATOR_ID.getName());
    ObjectType objectType = ObjectType.getType(json.getString(Field.OBJECT_TYPE.getName()));
    Role role = Role.getRole(json.getString(Field.ROLE.getName()));

    EmailLog emailLog = new EmailLog(
        collaboratorId,
        resourceId,
        null,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        ResourceShareEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    Item collaborator = Users.getUserById(collaboratorId);
    if (collaborator == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + collaboratorId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    String collaboratorEmail = collaborator.getString(Field.USER_EMAIL.getName());
    emailLog.setEmail(collaboratorEmail);
    String collaboratorName = getDisplayName(collaborator);
    String ownerName = getDisplayName(owner);
    String instanceUrl = config.getProperties().getUrl();
    String resourceThumbnail = json.getString(Field.RESOURCE_THUMBNAIL.getName());
    String resourceName = json.getString(Field.RESOURCE_NAME.getName());
    String resourceParentId = json.getString(Field.RESOURCE_PARENT_ID.getName());
    ResourceType resourceType = ResourceType.getType(json.getString(Field.RESOURCE_TYPE.getName()));
    JsonObject emailData = ResourceShareEmail.getEmailData(
        instanceUrl,
        proName,
        Utils.getLocaleFromMessage(message),
        ownerName,
        collaboratorName,
        collaboratorEmail,
        resourceType,
        resourceName,
        resourceThumbnail,
        resourceParentId,
        objectType,
        role);
    sendEmail(emailData, ResourceShareEmail.EMAIL_TEMPLATE_TYPE)
        .onComplete((res) -> sendOK(segment, message, mills));
  }

  private void doNotifyResourceDeshare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String resourceId = json.getString(Field.RESOURCE_ID.getName());
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String collaboratorId = json.getString(Field.COLLABORATOR_ID.getName());

    EmailLog emailLog = new EmailLog(
        collaboratorId,
        resourceId,
        null,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        ResourceDeshareEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    Item collaborator = Users.getUserById(collaboratorId);
    if (collaborator == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + collaboratorId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    String collaboratorEmail = collaborator.getString(Field.USER_EMAIL.getName());
    emailLog.setEmail(collaboratorEmail);
    String collaboratorName = getDisplayName(collaborator);
    String ownerName = getDisplayName(owner);
    String resourceName = json.getString(Field.RESOURCE_NAME.getName());
    ResourceType resourceType = ResourceType.getType(json.getString(Field.RESOURCE_TYPE.getName()));
    JsonObject emailData = ResourceDeshareEmail.getEmailData(
        proName,
        Utils.getLocaleFromMessage(message),
        ownerName,
        collaboratorName,
        collaboratorEmail,
        resourceType,
        resourceName);
    sendEmail(emailData, ResourceDeshareEmail.EMAIL_TEMPLATE_TYPE)
        .onComplete((res) -> sendOK(segment, message, mills));
  }

  private void doNotifyResourceDeleted(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String resourceId = json.getString(Field.RESOURCE_ID.getName());
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String collaboratorId = json.getString(Field.COLLABORATOR_ID.getName());

    EmailLog emailLog = new EmailLog(
        collaboratorId,
        resourceId,
        null,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        ResourceDeletedEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    Item collaborator = Users.getUserById(collaboratorId);
    if (collaborator == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + collaboratorId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    String collaboratorEmail = collaborator.getString(Field.USER_EMAIL.getName());
    emailLog.setEmail(collaboratorEmail);
    String ownerName = getDisplayName(owner);
    String resourceName = json.getString(Field.RESOURCE_NAME.getName());
    ResourceType resourceType = ResourceType.getType(json.getString(Field.RESOURCE_TYPE.getName()));
    JsonObject emailData = ResourceDeletedEmail.getEmailData(
        proName,
        Utils.getLocaleFromMessage(message),
        collaboratorId,
        ownerName,
        resourceType,
        resourceName);
    sendEmail(emailData, ResourceDeletedEmail.EMAIL_TEMPLATE_TYPE)
        .onComplete((res) -> sendOK(segment, message, mills));
  }

  private void doNotifyShare(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String name = json.getString(Field.NAME.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    String url = config.getProperties().getUrl();
    String fileId = json.getString(Field.ID.getName());
    JsonArray editors = json.getJsonArray(Field.EDITORS.getName());
    JsonArray viewers = json.getJsonArray(Field.VIEWERS.getName());
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    EmailLog emailLog = new EmailLog(
        ownerId,
        fileId,
        storageType,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        SharingEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    emailLog.setEmail(owner.getString(Field.USER_EMAIL.getName()));
    String ownerName = getDisplayName(owner);

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    for (Object editorObj : editors) {
      Promise<String> promise = Promise.promise();
      String editor = (String) editorObj;
      try {
        JsonObject emailData = SharingEmail.getSharingUserFormat(
            url,
            proName,
            Utils.getLocaleFromMessage(message),
            ownerName,
            editor,
            CollaboratorType.EDITOR,
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            storageType,
            fileId,
            name);
        Future<String> handler = sendEmail(emailData, SharingEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [Share from owner:" + ownerId
                + " to editor:" + editor + "]");
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    }
    for (Object viewerObj : viewers) {
      Promise<String> promise = Promise.promise();
      String viewer = (String) viewerObj;
      try {
        JsonObject emailData = SharingEmail.getSharingUserFormat(
            url,
            proName,
            Utils.getLocaleFromMessage(message),
            ownerName,
            viewer,
            CollaboratorType.VIEWER,
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            storageType,
            fileId,
            name);
        Future<String> handler = sendEmail(emailData, SharingEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [Share from owner:" + ownerId
                + " to viewer:" + viewer + "]");
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    }
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      // 30/07/20 DK - should we send error for failed emails? Right now we don't use it at all.
      sendOK(segment, message, mills);
    });
  }

  private void doNotifyMove(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String name = json.getString(Field.NAME.getName());
    String id = json.getString(Field.ID.getName());
    String folder = json.getString(Field.FOLDER.getName());
    String folderId = json.getString(Field.FOLDER_ID.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    String url = config.getProperties().getUrl();
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    JsonArray users = json.getJsonArray(Field.USERS.getName());

    EmailLog emailLog = new EmailLog(
        ownerId,
        id,
        storageType,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        ObjectMovedEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    emailLog.setEmail(owner.getString(Field.USER_EMAIL.getName()));

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    String ownerName = getDisplayName(owner);
    users.forEach(userId -> {
      Promise<String> promise = Promise.promise();
      try {
        JsonObject emailData = ObjectMovedEmail.getEmailData(
            url,
            proName,
            Utils.getLocaleFromMessage(message),
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            storageType,
            id,
            name,
            (String) userId,
            ownerName,
            folderId,
            folder);
        Future<String> handler =
            sendEmail(emailData, ObjectMovedEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [file moved from owner:" + ownerId
                + " to user:" + userId + "]");
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    });
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      sendOK(segment, message, mills);
    });
  }

  // happens if object has been moved to root because parent is deleted
  private void doNotifyMoveForeign(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String name = json.getString(Field.NAME.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    JsonArray users = json.getJsonArray(Field.USERS.getName());
    String parent = json.getString(Field.PARENT.getName());
    String objectId = json.getString(Field.OBJECT_ID.getName());
    String url = config.getProperties().getUrl();
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    // get emails
    users.forEach(userId -> {
      Promise<String> promise = Promise.promise();
      try {
        JsonObject emailData = ObjectMovedToRootEmail.getEmailData(
            url,
            proName,
            Utils.getLocaleFromMessage(message),
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            storageType,
            objectId,
            name,
            (String) userId,
            parent);
        Future<String> handler =
            sendEmail(emailData, ObjectMovedToRootEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    });
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      sendOK(segment, message, mills);
    });
  }

  private void doNotifyDelete(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String ownerId = json.getString(Field.OWNER_ID.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());
    String name = json.getString(Field.NAME.getName());
    Number type = json.getInteger(Field.TYPE.getName());
    JsonArray users = json.getJsonArray(Field.USERS.getName());

    EmailLog emailLog = new EmailLog(
        ownerId,
        fileId,
        StorageType.getStorageType(storageType),
        null,
        GMTHelper.utcCurrentTime(),
        true,
        ObjectMovedEmail.EMAIL_TEMPLATE_TYPE);

    // get owner's name
    Item owner = Users.getUserById(ownerId);
    if (owner == null) {
      emailLog.triggerError(EmailErrorCodes.USER_NOT_FOUND, "User not found");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName() + ") user " + ownerId
          + " doesn't exist");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    emailLog.setEmail(owner.getString(Field.USER_EMAIL.getName()));

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    users.forEach(userId -> {
      Promise<String> promise = Promise.promise();
      try {
        JsonObject emailData = ObjectDeletedEmail.getEmailData(
            proName,
            Utils.getLocaleFromMessage(message),
            (String) userId,
            getDisplayName(owner),
            type.intValue() == 1 ? ObjectType.FILE : ObjectType.FOLDER,
            name);
        Future<String> handler =
            sendEmail(emailData, ObjectDeletedEmail.EMAIL_TEMPLATE_TYPE, promise);
        queue.add(handler);
      } catch (Exception e) {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [delete file from owner:" + ownerId
                + " to user:" + userId + "]");
        promise.fail(e);
        log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
      }
    });
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      sendOK(segment, message, mills);
    });
  }

  private void doConfirmEmail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String newEmail = json.getString(Field.NEW_EMAIL.getName());
    String url = config.getProperties().getUrl();
    String secretKey = config.getProperties().getFluorineSecretKey();
    JsonObject emailData = ChangeEmailRequest.getEmailData(
        url, proName, Utils.getLocaleFromMessage(message), userId, newEmail, secretKey);

    EmailLog emailLog = new EmailLog(
        userId,
        null,
        null,
        newEmail,
        GMTHelper.utcCurrentTime(),
        true,
        ChangeEmailRequest.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, ChangeEmailRequest.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doConfirmRegistration(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String nonce = json.getString(Field.NONCE.getName());
    String username = json.getString(Field.USERNAME.getName());
    String email = json.getString(Field.EMAIL.getName());
    String url = config.getProperties().getUrl();

    EmailLog emailLog = new EmailLog(
        userId,
        null,
        null,
        email,
        GMTHelper.utcCurrentTime(),
        true,
        ConfirmRegistrationEmail.EMAIL_TEMPLATE_TYPE);

    if (email == null || userId == null || nonce == null || username == null) {
      emailLog.triggerError(
          EmailErrorCodes.INCOMPLETE_DATA, "Incomplete data for email (id: " + userId + ") ");
      StackTraceElement el = Thread.currentThread().getStackTrace()[0];
      log.error("(" + el.getClassName() + ":" + el.getMethodName()
          + ") email, userId, nonce or user name is not specified");
      sendError(
          segment,
          message,
          "Email, userId, nonce or user name is not specified",
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    JsonObject emailData = ConfirmRegistrationEmail.getEmailData(
        url, proName, Utils.getLocaleFromMessage(message), userId, nonce);
    Future<String> handler = sendEmail(emailData, ConfirmRegistrationEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending "
                + "[registration confirmation for " + "user:" + userId + "]");
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doNotifyAdmins(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String nonce = getRequiredString(segment, Field.HASH.getName(), message, message.body());
    Boolean withHash = message.body().getBoolean("withHash");
    if (withHash == null) {
      withHash = true;
    }
    if (userId == null) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    if (withHash && nonce == null) {
      sendError(segment, message, "nonce is not specified", HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    // check if user exists and is not enabled
    Item user = Users.getUserById(userId);
    if (user == null) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "UserDoesNotExist"),
          HttpStatus.NOT_FOUND);
      return;
    }
    if (withHash && !nonce.equals(user.getString(Field.NONCE.getName()))) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "TheHashIsWrong"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    if ((Boolean) user.get(Field.ENABLED.getName())) {
      sendError(
          segment,
          message,
          Utils.getLocalizedString(message, "TheAccountOfTheUserHasAlreadyBeenActivated"),
          HttpStatus.BAD_REQUEST);
      return;
    }
    Users.removeUserNonce(userId);

    notifyAdmins(message, message.body().getString(Field.USER_ID.getName()));
    sendOK(segment, message, mills);
  }

  private void doNotifyUserRemoval(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    // get all admins
    List<String> admins = Users.getAllAdmins();

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    if (Utils.isListNotNullOrEmpty(admins)) {
      admins.forEach(adminId -> {
        Item admin = Users.getUserById(adminId);
        if (admin == null) {
          return;
        }
        Promise<String> promise = Promise.promise();
        try {
          boolean shouldReceiveAdminEmails = false;
          if (admin.hasAttribute(Field.PREFERENCES.getName())) {
            String prefJson = admin.getJSON(Field.PREFERENCES.getName());
            if (Utils.isStringNotNullOrEmpty(prefJson)) {
              shouldReceiveAdminEmails =
                  new JsonObject(prefJson).getBoolean("receiveAdminEmails", false);
            }
          }
          // preference controlled
          if (!shouldReceiveAdminEmails) {
            return;
          }
          JsonObject emailData = NotifyAdminUserErase.getEmailData(
              proName, Utils.getLocaleFromMessage(message), admin, userId);
          if (emailData == null || emailData.isEmpty()) {
            log.error(String.format(
                "%s Error on email sending: email data is %s",
                LogPrefix.getLogPrefix(), emailData == null ? "NULL" : emailData.encodePrettily()));
          }
          Future<String> handler =
              sendEmail(emailData, NotifyAdminUserErase.EMAIL_TEMPLATE_TYPE, promise);
          queue.add(handler);
        } catch (Exception e) {
          promise.fail(e);
          log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
        }
      });
    }
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      sendOK(segment, message, mills);
    });
  }

  private void notifyAdmins(Message<JsonObject> message, String userId) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String url = config.getProperties().getUrl();
    if (userId == null) {
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }
    // get all admins
    List<String> admins = Users.getAllAdmins();

    // create list to monitor completion of all emails
    List<Future<String>> queue = new ArrayList<>();

    if (Utils.isListNotNullOrEmpty(admins)) {
      admins.forEach(adminId -> {
        Item admin = Users.getUserById(adminId);
        // will skip the user
        if (admin == null) {
          return;
        }

        Promise<String> promise = Promise.promise();
        try {
          boolean shouldReceiveAdminEmails = false;
          if (admin.hasAttribute(Field.PREFERENCES.getName())) {
            String prefJson = admin.getJSON(Field.PREFERENCES.getName());
            if (Utils.isStringNotNullOrEmpty(prefJson)) {
              shouldReceiveAdminEmails =
                  new JsonObject(prefJson).getBoolean("receiveAdminEmails", false);
            }
          }
          // preference controlled
          if (!shouldReceiveAdminEmails) {
            return;
          }
          JsonObject emailData = NotifyAdminRegistration.getEmailData(
              url, proName, Utils.getLocaleFromMessage(message), admin, userId);
          Future<String> handler =
              sendEmail(emailData, NotifyAdminRegistration.EMAIL_TEMPLATE_TYPE, promise);
          queue.add(handler);
        } catch (Exception e) {
          promise.fail(e);
          log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
        }
      });
    }
    TypedCompositeFuture.join(queue).onComplete(ar -> {
      // Ignore param as we don't send any message from those future's
      sendOK(segment, message, mills);
    });
  }

  private void doRequestAccess(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, message.body());
    String fileName = message.body().getString(Field.FILE_NAME.getName());

    EmailLog emailLog = new EmailLog(
        userId,
        fileId,
        null,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        RequestFileAccessEmail.EMAIL_TEMPLATE_TYPE);

    if (userId == null || fileId == null) {
      emailLog.triggerError(
          EmailErrorCodes.INCOMPLETE_DATA,
          "Incomplete data for email (id: " + fileId + ", userId: " + userId + ") ");
      sendError(
          segment,
          message,
          new JsonObject()
              .put(Field.MESSAGE.getName(), Utils.getLocalizedString(message, "FL3"))
              .put(Field.ERROR_ID.getName(), "FL3"),
          HttpStatus.INTERNAL_SERVER_ERROR);
      return;
    }

    // check if file exists
    Item file = SimpleStorage.getItem(fileId);
    if (file == null) {
      emailLog.triggerError(
          EmailErrorCodes.INCOMPLETE_DATA,
          "Incomplete data for email (id: " + fileId + ", name: " + fileName + ") ");
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "FileWithIdDoesNotExist"), fileId),
          HttpStatus.BAD_REQUEST);
      return;
    }

    // check if user already has access
    JsonObject share =
        file.getJSON("fshare") != null ? new JsonObject(file.getJSON("fshare")) : null;
    JsonArray editors =
        share != null ? share.getJsonArray(Field.EDITOR.getName()) : new JsonArray();
    JsonArray viewers =
        share != null ? share.getJsonArray(Field.VIEWER.getName()) : new JsonArray();
    if (userId.equals(file.get("fowner")) || editors.contains(userId) || viewers.contains(userId)) {
      emailLog.triggerError(
          EmailErrorCodes.ALREADY_HAS_ACCESS,
          "User already has access (id: " + fileId + ", name: " + fileName + ") ");
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "UserAlreadyHasAccessToFile"), fileId),
          HttpStatus.BAD_REQUEST);
      return;
    }

    JsonObject emailData = RequestFileAccessEmail.getEmailData(
        proName,
        Utils.getLocaleFromMessage(message),
        file.getString("fowner"),
        userId,
        fileId,
        fileName);

    if (Objects.isNull(emailData)) {
      return;
    }

    emailLog.setEmail(emailData.getString("to"));
    Future<String> handler = sendEmail(emailData, RequestFileAccessEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(
            EmailErrorCodes.EMAIL_NOT_SENT,
            LogPrefix.getLogPrefix() + " Error on email sending [request access user:" + userId
                + "]");
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doMentionUser(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = json.getString(Field.USER_ID.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String eventLabel = json.getString("eventLabel");
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    Item targetUser = Users.getUserById(json.getString(Field.TARGET_USER_ID.getName()));
    if (Objects.isNull(targetUser)) {
      XRayManager.endSegment(segment);
      return;
    }
    JsonObject userPreferences = new JsonObject(targetUser.getJSON(Field.PREFERENCES.getName()));
    final int timezoneOffset = userPreferences.containsKey("timezoneOffset")
        ? userPreferences.getInteger("timezoneOffset")
        : 0;
    String formattedTimeString =
        GMTHelper.timestampToString(json.getLong("timeStamp"), timezoneOffset);
    JsonObject commenterInfo = Users.getUserInfo(userId);
    if (commenterInfo.containsKey(Field.EMAIL.getName())) {
      json.put(Field.COMMENTER_EMAIL.getName(), commenterInfo.getString(Field.EMAIL.getName()));
    }
    Event mentionEvent = new Event(
        NotificationEvents.eventTypes.valueOf(eventLabel),
        formattedTimeString,
        commenterInfo.getString(Field.NAME.getName()),
        json,
        Utils.getBundleForLocale(message));

    String url = config.getProperties().getUrl();
    JsonObject emailData = UserMentionEmail.getEmailData(
        url,
        proName,
        Utils.getLocaleFromMessage(message),
        fileId,
        mentionEvent,
        s3Regional,
        config.getProperties().getCfDistribution());

    EmailLog emailLog = new EmailLog(
        userId,
        fileId,
        storageType,
        null,
        GMTHelper.utcCurrentTime(),
        true,
        UserMentionEmail.EMAIL_TEMPLATE_TYPE);

    Future<String> handler = sendEmail(emailData, UserMentionEmail.EMAIL_TEMPLATE_TYPE);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private void doSendCustomEmail(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    final long mills = System.currentTimeMillis();
    String email = body.getString(Field.EMAIL.getName()),
        html = body.getString("body"),
        subject = body.getString("subject");
    if (body.containsKey("email_property")) {
      email = config.getProperties().getFeedbackEmail();
    }
    EmailLog emailLog = new EmailLog(
        null, null, null, email, GMTHelper.utcCurrentTime(), true, EmailTemplateType.CUSTOM);
    Future<String> handler = sendEmail(
        new JsonObject()
            .put("from", sender)
            .put("to", email)
            .put("subject", subject)
            .put("html", html),
        EmailTemplateType.CUSTOM);
    handler.onComplete(event -> {
      if (event.succeeded()) {
        sendOK(segment, message, mills);
      } else {
        emailLog.triggerError(EmailErrorCodes.EMAIL_NOT_SENT, event.cause().getMessage());
        sendError(
            segment, message, event.cause().getMessage(), HttpStatus.BAD_REQUEST, event.cause());
      }
    });
  }

  private Future<String> sendEmail(JsonObject emailData, EmailTemplateType emailTemplate) {
    Promise<String> promise = Promise.promise();
    return sendEmail(emailData, emailTemplate, promise);
  }

  private Future<String> sendEmail(
      JsonObject emailData, EmailTemplateType emailTemplate, Promise<String> promise) {
    if (emailData != null && !emailData.isEmpty()) {
      com.postmarkapp.postmark.client.data.model.message.Message emailMessage =
          new com.postmarkapp.postmark.client.data.model.message.Message(
              emailData.getString("from"), emailData.getString("to"),
              emailData.getString("subject"), emailData.getString("html"));
      emailMessage.setMessageStream("outbound");
      emailMessage.setTag(emailTemplate.name());
      log.info("[POSTMARK] Going to send email with postmark to: " + emailMessage.getTo() + " tag: "
          + emailMessage.getTag());
      try {
        MessageResponse response = postmarkClient.deliverMessage(emailMessage);
        log.info(
            "[POSTMARK] EMAIL:" + emailTemplate.name() + " success " + response.getMessageId());
        promise.complete(response.getMessageId());
      } catch (Exception e) {
        StackTraceElement el = Thread.currentThread().getStackTrace()[1];
        log.error(
            "[POSTMARK] EMAIL:" + emailTemplate.name() + " fail " + "(" + el.getClassName() + ":"
                + el.getMethodName() + ") " + e.getMessage(),
            e);
        promise.fail(e);
      }
    } else {
      // DK: just to be sure we fail after return
      vertx.setTimer(100, event -> {
        log.info("[POSTMARK] No email data received in sendEmail");
        promise.tryFail("Email data is incorrect");
      });
    }
    return promise.future();
  }

  private void getEmailImage(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    String objectId = body.getString(Field.OBJECT_ID.getName());
    String fileId = body.getString(Field.FILE_ID.getName());
    String email = body.getString(Field.EMAIL.getName());
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    if (objectId.contains(EncodeReRouteHandler.exclamationMarkReplace)) {
      objectId = URLDecoder.decode(objectId, StandardCharsets.UTF_8);
    }
    byte[] imageData =
        s3Regional.getFromBucket(config.getProperties().getCfDistribution(), "emails/" + objectId);
    message.reply(imageData);
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("saveEmailLog")
        .runWithoutSegment(() -> {
          EmailLog emailLog = new EmailLog(
              null,
              fileId,
              StorageType.getStorageType(storageType),
              email,
              GMTHelper.utcCurrentTime(),
              true,
              EmailActionType.THUMBNAIL_ACCESSED,
              Field.EMAIL.getName());
          emailLog.sendToServer();
        });
    XRayManager.endSegment(segment);
  }

  private void getFileRedirectURL(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject body = message.body();
    String objectId = body.getString(Field.OBJECT_ID.getName());
    String email = body.getString(Field.EMAIL.getName());
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    String externalId = body.getString(Field.EXTERNAL_ID.getName());
    String token = body.getString(Field.TOKEN.getName());

    String shortId = Utils.parseItemId(objectId).getString(Field.ID.getName());
    String fullId;

    // if there's no externalId - ignore it
    if (!Utils.isStringNotNullOrEmpty(externalId)) {
      fullId = Utils.getEncapsulatedId(StorageType.getShort(storageType), shortId);
    } else {
      fullId = Utils.getEncapsulatedId(StorageType.getShort(storageType), externalId, shortId);
    }

    String redirectURL = config.getProperties().getUrl() + "open/" + fullId;

    if (Utils.isStringNotNullOrEmpty(token)) {
      redirectURL += "?token=" + token;
    }
    message.reply(redirectURL);
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("saveEmailLog")
        .runWithoutSegment(() -> {
          EmailLog emailLog = new EmailLog(
              null,
              objectId,
              StorageType.getStorageType(storageType),
              email,
              GMTHelper.utcCurrentTime(),
              true,
              EmailActionType.FILE_LINK_OPENED,
              Field.EMAIL.getName());
          emailLog.sendToServer();
        });
    XRayManager.endSegment(segment);
  }
}
