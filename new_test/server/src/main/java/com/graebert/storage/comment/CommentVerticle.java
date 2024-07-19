package com.graebert.storage.comment;

import static com.amazonaws.services.s3.internal.Constants.MB;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.translate.AmazonTranslate;
import com.amazonaws.services.translate.AmazonTranslateClient;
import com.amazonaws.services.translate.model.TranslateTextRequest;
import com.amazonaws.services.translate.model.TranslateTextResult;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.google.common.collect.Iterables;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.comment.AccessCache.AccessCacheConstants;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.GridFSModule;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.stats.logs.mention.UserMentionLog;
import com.graebert.storage.storage.Attachments;
import com.graebert.storage.storage.Comments;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.Mentions;
import com.graebert.storage.storage.SubscriptionsDao;
import com.graebert.storage.storage.Users;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.subscriptions.Subscriptions;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.TypedIteratorUtils;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.vertx.ResponseHandler;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import kong.unirest.HttpStatus;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.IteratorUtils;

public class CommentVerticle extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.COMMENTS;
  public static String address = "comment";
  private static AmazonTranslate translate;
  private S3Regional s3Regional = null;

  public static JsonArray handleTags(Item attachment, String locale) {
    if (attachment.hasAttribute("tags")) {
      // need to verify the locale and language settings.
      // translate only wants the 2 letter codes.
      /* supported languages
         Afrikaans	af
         Albanian	sq
         Amharic	am
         Arabic	ar
         Azerbaijani	az
         Bengali	bn
         Bosnian	bs
         Bulgarian	bg
         Chinese (Simplified)	zh
         Chinese (Traditional)	zh-TW
         Croatian	hr
         Czech	cs
         Danish	da
         Dari	fa-AF
         Dutch	nl
         English	en
         Estonian	et
         Finnish	fi
         French	fr
         French (Canada)	fr-CA
         Georgian	ka
         German	de
         Greek	el
         Hausa	ha
         Hebrew	he
         Hindi	hi
         Hungarian	hu
         Indonesian	id
         Italian	it
         Japanese	ja
         Korean	ko
         Latvian	lv
         Malay	ms
         Norwegian	no
         Persian	fa
         Pashto	ps
         Polish	pl
         Portuguese	pt
         Romanian	ro
         Russian	ru
         Serbian	sr
         Slovak	sk
         Slovenian	sl
         Somali	so
         Spanish	es
         Spanish (Mexico)	es-MX
         Swahili	sw
         Swedish	sv
         Tagalog	tl
         Tamil	ta
         Thai	th
         Turkish	tr
         Ukrainian	uk
         Urdu	ur
         Vietnamese	vi
      */
      final String language;
      switch (locale) {
        case "cn": // simplified
          language = "zh";
          break;
        case "zh": // traditional
          language = "zh-TW";
          break;
        case "cs":
          language = "cs";
          break;
        case "de":
          language = "de";
          break;
        case "en":
          language = "en";
          break;
        case "es":
          language = "es";
          break;
        case "it":
          language = "it";
          break;
        case "fr":
          language = "fr";
          break;
        case "ja":
          language = "ja";
          break;
        case "ko":
          language = "ko";
          break;
        case "pl":
          language = "pl";
          break;
        case "pt":
          language = "pt";
          break;
        case "ru":
          language = "ru";
          break;
        case "tr":
          language = "tr";
          break;
        default:
          language = "en";
      }

      JsonArray tags = new JsonArray(attachment.getJSON("tags"));
      JsonArray results = new JsonArray();
      JsonArray translatedTags = new JsonArray();
      AtomicBoolean bChanged = new AtomicBoolean(false);
      tags.forEach(otag -> {
        JsonObject tag = (JsonObject) otag;
        if (tag.containsKey(Field.TEXT.getName())) {
          // update tag first.
          String origstring = tag.getString(Field.TEXT.getName());
          tag.put("text_en", origstring);
          tag.remove(Field.TEXT.getName());
          bChanged.set(true);
        }
        if (!tag.containsKey("text_" + language)) {
          String origstring = tag.getString("text_en");
          TranslateTextRequest request = new TranslateTextRequest()
              .withText(origstring)
              .withSourceLanguageCode("en")
              .withTargetLanguageCode(language);
          TranslateTextResult translateResult = translate.translateText(request);
          tag.put("text_" + language, translateResult.getTranslatedText());
          bChanged.set(true);
        }
        translatedTags.add(tag);
      });
      if (bChanged.get()) {
        Attachments.updateAttachmentTags(attachment, translatedTags);
      }
      translatedTags.forEach(otag -> {
        JsonObject tag = (JsonObject) otag;
        // clients expect users tag in text field right now.
        tag.put(Field.TEXT.getName(), tag.getString("text_" + language));
        results.add(tag);
      });
      return results;
    } else {
      return new JsonArray();
    }
  }

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(
        address + ".getCommentThreads",
        (Message<JsonObject> event) -> doGetAnnotations(event, "comments", "commentThreads"));
    eb.consumer(address + ".addCommentThread", this::doAddCommentThread);
    eb.consumer(
        address + ".getCommentThread",
        (Message<JsonObject> event) ->
            doGetAnnotation(event, "comments", Field.THREAD_ID.getName()));
    eb.consumer(address + ".updateCommentThread", this::doUpdateCommentThread);
    eb.consumer(
        address + ".deleteCommentThread",
        (Message<JsonObject> event) ->
            doDeleteAnnotation(event, "comments", Field.THREAD_ID.getName()));
    eb.consumer(
        address + ".addComment",
        (Message<JsonObject> event) -> doAddComment(event, "comments", Field.THREAD_ID.getName()));
    eb.consumer(
        address + ".updateComment",
        (Message<JsonObject> event) ->
            doUpdateComment(event, "comments", Field.THREAD_ID.getName()));
    eb.consumer(
        address + ".deleteComment",
        (Message<JsonObject> event) ->
            doDeleteComment(event, "comments", Field.THREAD_ID.getName()));

    eb.consumer(
        address + ".getMarkups",
        (Message<JsonObject> event) -> doGetAnnotations(event, "markups", "markups"));
    eb.consumer(address + ".addMarkup", this::doAddMarkup);
    eb.consumer(
        address + ".getMarkup",
        (Message<JsonObject> event) ->
            doGetAnnotation(event, "markups", Field.MARKUP_ID.getName()));
    eb.consumer(address + ".updateMarkup", this::doUpdateMarkup);
    eb.consumer(
        address + ".deleteMarkup",
        (Message<JsonObject> event) ->
            doDeleteAnnotation(event, "markups", Field.MARKUP_ID.getName()));
    eb.consumer(
        address + ".addMarkupComment",
        (Message<JsonObject> event) -> doAddComment(event, "markups", Field.MARKUP_ID.getName()));
    eb.consumer(
        address + ".updateMarkupComment",
        (Message<JsonObject> event) ->
            doUpdateComment(event, "markups", Field.MARKUP_ID.getName()));
    eb.consumer(
        address + ".deleteMarkupComment",
        (Message<JsonObject> event) ->
            doDeleteComment(event, "markups", Field.MARKUP_ID.getName()));

    eb.consumer(address + ".getAttachment", this::doGetAttachment);
    eb.consumer(address + ".getAttachmentDescription", this::doGetAttachmentDescription);
    eb.consumer(address + ".getAttachments", this::doGetAttachments);
    eb.consumer(address + ".addAttachment", this::doAddAttachment);

    // endpoints for notification emails
    eb.consumer(address + ".getThreadInfo", this::doGetThreadInfo);
    eb.consumer(address + ".getMarkupInfo", this::doGetMarkupInfo);
    eb.consumer(address + ".getAttachmentInfo", this::doGetAttachmentInfo);
    eb.consumer(address + ".getLatestFileComment", this::doGetLatestFileComment);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);

    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-comments");

    translate = AmazonTranslateClient.builder()
        .withRegion(config.getProperties().getTranslateRegion())
        .build();
  }

  private void doGetAttachmentInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String attachmentId = getRequiredString(segment, "attachmentId", message, json);
    String locale = json.getString(Field.LOCALE.getName());
    if (!Utils.isStringNotNullOrEmpty(locale)) { // fix for emails
      locale = Locale.ENGLISH.toLanguageTag();
    }
    if (fileId == null || attachmentId == null || locale == null) {
      return;
    }

    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "getAttachmentInfo");

    // lets not use the cache, as this gets updated from lambda
    Item attachment = Attachments.getAttachment(
        fileId,
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
        attachmentId);
    if (attachment == null) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "AttachmentNotFound");
      return;
    }
    JsonObject result = new JsonObject();
    result.put("tags", handleTags(attachment, locale));
    if (attachment.hasAttribute("transcript")) {
      result.put("transcript", new JsonArray(attachment.getJSON("transcript")));
    } else {
      result.put("transcript", new JsonArray());
    }
    if (attachment.hasAttribute("clonedBy")) {
      result.put("isCloned", true);
    }
    XRayManager.endSegment(subsegment);
    sendOK(segment, message, result, mills);
  }

  private void doGetMarkupInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject json = message.body();
    String userAgent = json.getString(Field.USER_AGENT.getName());
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    if (fileId == null || markupId == null) {
      return;
    }
    long mills = System.currentTimeMillis();

    Item annotation = Comments.getAnnotation(fileId, markupId, "markups");
    if (annotation == null) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
      return;
    }

    JsonObject annotationObject = Comments.annotationToJson(annotation, userAgent);

    Comments.getAnnotationComments(null, annotation, markupId)
        .forEachRemaining(comment -> annotationObject
            .getJsonArray("comments")
            .add(Comments.commentToJson(
                comment, comment.getString(Field.AUTHOR.getName()), userAgent)));

    ((List<JsonObject>) annotationObject.getJsonArray("comments").getList())
        .sort(Comparator.comparingLong(o -> o.getLong(Field.CREATED.getName())));

    sendOK(segment, message, annotationObject, System.currentTimeMillis() - mills);
  }

  private void doGetThreadInfo(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    if (threadId == null || fileId == null) {
      return;
    }

    Item threadInfo = Comments.getAnnotation(fileId, threadId, "comments");
    Item rootComment = Comments.getAnnotation(fileId, threadId + "#root", "comments");
    if (threadInfo == null || rootComment == null) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
      return;
    }
    String threadTitle = "Untitled";
    if (Utils.isStringNotNullOrEmpty(threadInfo.getString(Field.TITLE.getName()))) {
      threadTitle = threadInfo.getString(Field.TITLE.getName());
    }
    JsonObject result = new JsonObject()
        .put(Field.FILE_ID.getName(), fileId)
        .put(Field.THREAD_ID.getName(), threadId)
        .put(Field.TITLE.getName(), threadTitle)
        .put(Field.TEXT.getName(), rootComment.getString(Field.TEXT.getName()))
        .put(Field.TIMESTAMP_SHORT.getName(), rootComment.getLong(Field.TIMESTAMP_SHORT.getName()))
        .put(
            Field.AUTHOR.getName(),
            Users.getUserInfo(rootComment.getString(Field.AUTHOR.getName())));
    if (threadInfo.hasAttribute("clonedBy") || rootComment.hasAttribute("clonedBy")) {
      result.put("isCloned", true);
    }
    sendOK(segment, message, result, System.currentTimeMillis() - mills);
  }

  private void doGetAttachments(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String locale = getRequiredString(segment, Field.LOCALE.getName(), message, json);
    if (userId == null || fileId == null || locale == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "getAttachments");
        String filename = json.getString(
            Field.FILE_ID.getName()); // let's not use storageType here. It's unreliable
        ItemCollection<QueryOutcome> attachments;

        if (AccessCacheConstants.OK.getTitle().equals(accessValue)) {
          attachments = Attachments.getAttachments(
              filename,
              StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
              null);
        } else {
          attachments = Attachments.getAttachments(
              filename,
              StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
              userId);
        }
        JsonArray result = new JsonArray();
        if (attachments != null) {
          for (Item attachment : attachments) {
            result.add(Comments.attachmentToJson(attachment, locale));
          }
        }
        XRayManager.endSegment(subsegment);
        sendOK(segment, message, new JsonObject().put("attachments", result), mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doGetAttachment(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String locale = getRequiredString(segment, Field.LOCALE.getName(), message, json);
    Boolean preview = json.getBoolean(Field.PREVIEW.getName());
    String attachmentId = getRequiredString(segment, "attachmentId", message, json);
    if (userId == null || fileId == null || attachmentId == null || locale == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      // if the user has access to the file (token or regular), allow access to the attachments
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "getAttachment");
        String filename = json.getString(
            Field.FILE_ID.getName()); // let's not use storageType here. It's unreliable

        // lets not use the cache, as this gets updated from lambda
        Item attachment = Attachments.getAttachment(
            filename,
            StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
            attachmentId);
        if (attachment == null) {
          XRayManager.endSegment(subsegment);
          sendError(segment, message, HttpStatus.NOT_FOUND, "AttachmentNotFound");
          return;
        }
        Attachments.updateAttachmentTimestamp(attachment);
        byte[] data;
        if (attachment.hasAttribute("preview800") && preview) {
          data = s3Regional.get("previews/" + filename + "/_800_/" + attachmentId);
        } else {
          data = s3Regional.get("attachments/" + filename + "/" + attachmentId);
        }
        JsonObject result = new JsonObject()
            .put(Field.DATA.getName(), data)
            .put("contentType", attachment.getString("contentType"))
            .put("tags", handleTags(attachment, locale).encode())
            .put(
                "transcript",
                attachment.isPresent("transcript")
                    ? new JsonArray(attachment.getJSON("transcript"))
                    : new JsonArray());
        if (attachment.hasAttribute("clonedBy")) {
          result.put("isCloned", true);
        }
        XRayManager.endSegment(subsegment);
        sendOK(segment, message, result, mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doGetAttachmentDescription(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String locale = getRequiredString(segment, Field.LOCALE.getName(), message, json);
    String attachmentId = getRequiredString(segment, "attachmentId", message, json);
    if (userId == null || fileId == null || attachmentId == null || locale == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      // if the user has access to the file (token or regular), allow access to the attachments
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        Entity subsegment =
            XRayManager.createSubSegment(operationGroup, segment, "getAttachmentDescription");
        String filename = json.getString(
            Field.FILE_ID.getName()); // let's not use storageType here. It's unreliable

        // lets not use the cache, as this gets updated from lambda
        Item attachment = Attachments.getAttachment(
            filename,
            StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName())),
            attachmentId);
        if (attachment == null) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "AttachmentNotFound");
          return;
        }
        JsonObject result = new JsonObject();

        result.put("tags", handleTags(attachment, locale));
        if (attachment.hasAttribute("transcript")) {
          result.put("transcript", new JsonArray(attachment.getJSON("transcript")));
        } else {
          result.put("transcript", new JsonArray());
        }
        if (attachment.hasAttribute("clonedBy")) {
          result.put("isCloned", true);
        }
        XRayManager.endSegment(subsegment);
        sendOK(segment, message, result, mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doAddAttachment(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    String fileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, parsedMessage.getJsonObject());
    String storageType = getRequiredString(
        segment, Field.STORAGE_TYPE.getName(), message, parsedMessage.getJsonObject());
    String userId =
        getRequiredString(segment, Field.USER_ID.getName(), message, parsedMessage.getJsonObject());
    String locale =
        getRequiredString(segment, Field.LOCALE.getName(), message, parsedMessage.getJsonObject());
    if (fileId == null || userId == null || locale == null) {
      return;
    }

    String contentType =
        getRequiredString(segment, "contentType", message, parsedMessage.getJsonObject());
    if (contentType == null) {
      return;
    }

    if (parsedMessage.getContentAsByteArray().length > 10 * MB) {
      log.error(
          "Attachment is too large. Max allowed size: 10 MB (" + 10 * MB + ") . Attempted size: "
              + parsedMessage.getContentAsByteArray().length / MB + " MB ("
              + parsedMessage.getContentAsByteArray().length
              + "). ContentType: " + contentType);
      sendError(segment, message, HttpStatus.BAD_REQUEST, "AttachmentTooLarge");
      return;
    }

    hasAccess(segment, message, parsedMessage.getJsonObject(), event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "addAttachment");
        long timestamp = GMTHelper.utcCurrentTime();

        String filename = parsedMessage
            .getJsonObject()
            .getString(Field.FILE_ID.getName()); // let's not use storageType here. It's unreliable
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(parsedMessage.getContentAsByteArray().length);
        metadata.setContentType(contentType);
        metadata.addUserMetadata("x-amz-meta-userId", userId);
        String attachmentid;
        if (contentType.startsWith("audio/")
            && parsedMessage.getJsonObject().containsKey(Field.COUNTRY_LOCALE.getName())) {
          attachmentid = parsedMessage.getJsonObject().getString(Field.COUNTRY_LOCALE.getName())
              + "_" + UUID.randomUUID();
        } else {
          attachmentid = UUID.randomUUID().toString();
        }
        if (parsedMessage.getJsonObject().containsKey(Field.PREFERENCES.getName())) {
          JsonObject preferences =
              parsedMessage.getJsonObject().getJsonObject(Field.PREFERENCES.getName());
          boolean showVoiceRecordingTranscription =
              preferences.containsKey("showVoiceRecordingTranscription")
                  ? preferences.getBoolean("showVoiceRecordingTranscription")
                  : true;
          boolean showPictureRecordingTags = preferences.containsKey("showPictureRecordingTags")
              ? preferences.getBoolean("showPictureRecordingTags")
              : true;
          if ((contentType.startsWith("audio/") && !showVoiceRecordingTranscription)
              || (contentType.startsWith("image/") && !showPictureRecordingTags)) {
            attachmentid = "DONOTPROCESS" + attachmentid;
          }
        }
        s3Regional.putObject(new PutObjectRequest(
            s3Regional.getBucketName(),
            "attachments/" + filename + "/" + attachmentid,
            new ByteArrayInputStream(parsedMessage.getContentAsByteArray()),
            metadata));
        Item attachment = new Item()
            .withPrimaryKey(
                Field.PK.getName(), "attachments#" + filename, Field.SK.getName(), attachmentid)
            .withString(Field.AUTHOR.getName(), userId)
            .withLong(Field.TIMESTAMP_SHORT.getName(), timestamp)
            .withLong(Field.CREATED.getName(), timestamp)
            .withLong(Field.SIZE.getName(), parsedMessage.getContentAsByteArray().length)
            .withString("contentType", contentType)
            .withString("etag", DigestUtils.sha3_256Hex(parsedMessage.getContentAsByteArray()));
        Attachments.putAttachment(attachment);
        JsonObject response = Comments.attachmentToJson(attachment, locale);
        XRayManager.endSegment(subsegment);
        sendOK(segment, message, response, mills);
        updateLastCommentTimestamp(
            segment, message, fileId, storageType, attachment.getLong(Field.CREATED.getName()));
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doGetAnnotations(Message<JsonObject> message, String type, String resultObject) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String timestamp = json.getString(Field.TIMESTAMP.getName());
    Boolean returnDeleted = json.getBoolean("returnDeleted");

    if (fileId == null || userId == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)) {
        getAnnotations(
            segment, message, fileId, timestamp, returnDeleted, mills, null, type, resultObject);
      } else if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        getAnnotations(
            segment, message, fileId, timestamp, returnDeleted, mills, userId, type, resultObject);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void getAnnotations(
      Entity segment,
      Message<JsonObject> message,
      String fileId,
      String timestamp,
      Boolean returnDeleted,
      long mills,
      String userId,
      String type,
      String resultObject) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "getAnnotations");

    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    long timeStampValue = 0L;
    long latestTimestamp;
    if (timestamp == null) {
      latestTimestamp = 0L;
    } else {
      latestTimestamp = Long.parseLong(timestamp);
      timeStampValue = Long.parseLong(timestamp);
    }

    Iterator<Item> commentsIterator =
        Comments.getAllAnnotations(timeStampValue, fileId, returnDeleted, true, type);

    Map<String, JsonObject> threadsMap = new HashMap<>();
    Map<String, JsonArray> commentsMap = new HashMap<>();

    while (commentsIterator.hasNext()) {
      Item item = commentsIterator.next();
      String[] names = item.getString(Field.SK.getName()).split("#");
      // DK: I think that we should find better way if we need to format differently
      if (names.length == 1) {
        if (userId != null) {
          // in token mode we first check the author and skip all threads we did not author
          if (item.getString(Field.AUTHOR.getName()).equals(userId)) {
            threadsMap.put(
                item.getString(Field.SK.getName()), Comments.annotationToJson(item, userAgent));
          }
        } else {
          threadsMap.put(
              item.getString(Field.SK.getName()), Comments.annotationToJson(item, userAgent));
        }
      } else {
        // String comment = names[1];
        String annotation = names[0];
        JsonObject json =
            Comments.commentToJson(item, item.getString(Field.AUTHOR.getName()), userAgent);
        if (!commentsMap.containsKey(annotation)) {
          commentsMap.put(annotation, new JsonArray().add(json));
        } else {
          commentsMap.put(annotation, commentsMap.get(annotation).add(json));
        }
      }

      if (item.hasAttribute(Field.TIMESTAMP_SHORT.getName())
          && latestTimestamp < item.getLong(Field.TIMESTAMP_SHORT.getName())) {
        latestTimestamp = item.getLong(Field.TIMESTAMP_SHORT.getName());
      }
    }

    commentsMap.forEach((threadId, comments) -> {
      ((List<JsonObject>) comments.getList())
          .sort(Comparator.comparingLong(o -> o.getLong(Field.CREATED.getName())));
      if (threadsMap.containsKey(threadId)) {
        threadsMap.put(threadId, threadsMap.get(threadId).put("comments", comments));
      }
    });

    JsonArray result = new JsonArray();
    if (!threadsMap.isEmpty()) {
      result = new JsonArray(new ArrayList<>(threadsMap.values()));
    }
    XRayManager.endSegment(subsegment);
    sendOK(
        segment,
        message,
        new JsonObject().put(resultObject, result).put(Field.TIMESTAMP.getName(), latestTimestamp),
        mills);
  }

  private void doAddCommentThread(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String device = getRequiredString(segment, Field.DEVICE.getName(), message, json);
    String text = getRequiredString(segment, Field.TEXT.getName(), message, json);
    String spaceId = json.getString("spaceId");
    String viewportId = json.getString("viewportId");
    JsonArray ids = json.getJsonArray(Field.IDS.getName());
    String loc = json.getString("loc");
    String title = json.getString(Field.TITLE.getName());
    String userAgent = json.getString(Field.USER_AGENT.getName());
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || userId == null || device == null || text == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "addThread");
        String threadId = Utils.generateUUID();
        long timestamp = GMTHelper.utcCurrentTime();
        Item thread = new Item()
            .withPrimaryKey(Field.PK.getName(), "comments#" + fileId, Field.SK.getName(), threadId)
            .withString(Field.STATE.getName(), CommentState.ACTIVE.name())
            .withLong(Field.TIMESTAMP_SHORT.getName(), timestamp)
            .withString(Field.AUTHOR.getName(), userId)
            .withString(Field.DEVICE.getName(), device);
        if (title != null && !title.isEmpty()) {
          thread.withString(Field.TITLE.getName(), title);
        }
        if (ids != null && !ids.isEmpty()) {
          thread.withList("entityHandles", ids.getList());
        }
        if (spaceId != null && !spaceId.isEmpty()) {
          thread.withString("spaceId", spaceId);
        }
        if (viewportId != null && !viewportId.isEmpty()) {
          thread.withString("viewportId", viewportId);
        }
        Item comment = new Item()
            .withPrimaryKey(
                Field.PK.getName(), "comments#" + fileId, Field.SK.getName(), threadId + "#root")
            .withString(Field.STATE.getName(), CommentState.ACTIVE.name())
            .withLong(Field.TIMESTAMP_SHORT.getName(), timestamp)
            .withLong(Field.CREATED.getName(), timestamp)
            .withString(Field.AUTHOR.getName(), userId)
            .withString(Field.DEVICE.getName(), device)
            .withString(Field.TEXT.getName(), text);
        if (loc != null && !loc.isEmpty()) {
          comment.withString("loc", loc);
        }

        Item user = Users.getUserById(userId);
        if (user != null) {
          // for stats
          comment.withString(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()));
          thread.withString(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()));
        }

        Comments.saveAnnotation(thread);
        Comments.saveAnnotation(comment);

        JsonObject threadObject = Comments.threadToJson(thread);
        JsonObject obj = Comments.commentToJson(comment, user, userAgent);
        threadObject.put("comments", new JsonArray().add(obj));

        XRayManager.endSegment(subsegment);

        List<String> users = Comments.getMentionedUsersFromText(text);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".commentsUpdate",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(Field.THREAD_ID.getName(), threadId)
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "add")
                      .put(Field.AUTHOR.getName(), thread.getString(Field.AUTHOR.getName()))
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));
              JsonObject subscriptionObject = new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.USER_ID.getName(), userId);
              if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
                // limit scope for specific thread only for token users
                subscriptionObject
                    .put(
                        "scope",
                        new JsonArray()
                            .add(Subscriptions.subscriptionScope.THREAD + "#" + threadId))
                    .put("scopeUpdate", Subscriptions.scopeUpdate.APPEND.toString())
                    .put(Field.TOKEN.getName(), json.getString(Field.TOKEN.getName()));
              } else {
                subscriptionObject
                    .put(
                        "scope",
                        new JsonArray().add(Subscriptions.subscriptionScope.GLOBAL.toString()))
                    .put("scopeUpdate", Subscriptions.scopeUpdate.REWRITE.toString());
              }
              eb_send(
                  blockingSegment, Subscriptions.address + ".addSubscription", subscriptionObject);
              JsonObject notificationObject = new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.THREAD_ID.getName(), threadId);

              // they are actually optional, but let's save them in case we would need to show
              // original thread vs modified
              if (title != null && !title.isEmpty()) {
                notificationObject.put(Field.TITLE.getName(), title);
              }
              if (!text.isEmpty()) {
                notificationObject.put(Field.TEXT.getName(), text);
              }
              eb_send(
                  blockingSegment,
                  NotificationEvents.address + ".newCommentThread",
                  notificationObject.put("mentionedUsers", users));
            });
        getInAccessibleMentionedUsers(storageType, users, json).onComplete(e -> {
          JsonArray usersWithNoAccess;
          if (e.succeeded()) {
            usersWithNoAccess = e.result();
            shareFileWithMentionedUsers(segment, message, json, usersWithNoAccess);
          } else {
            if (!e.cause().getMessage().equals("NO DATA")) {
              sendError(segment, message, e.cause().getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
              return;
            }
            usersWithNoAccess = new JsonArray();
          }
          threadObject.put("newCollaborators", usersWithNoAccess);
          sendOK(segment, message, threadObject, mills);
          updateLastCommentTimestamp(
              segment,
              message,
              fileId,
              storageType.name(),
              comment.getLong(Field.CREATED.getName()));
          JsonObject mentionJson = json.copy();
          mentionJson.put(
              Field.TIMESTAMP_C.getName(), thread.getLong(Field.TIMESTAMP_SHORT.getName()));
          mentionJson.put(Field.THREAD_ID.getName(), threadId);
          mentionJson.put(Field.COMMENT_ID.getName(), Field.ROOT.getName());
          handleMentionsData(
              segment,
              message,
              usersWithNoAccess,
              NotificationEvents.eventTypes.NEWTHREAD,
              users,
              fileId,
              storageType,
              mentionJson,
              userId,
              null,
              true);
        });
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doAddMarkup(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String device = getRequiredString(segment, Field.DEVICE.getName(), message, json);
    String type = getRequiredString(segment, Field.TYPE.getName(), message, json);
    String spaceId = json.getString("spaceId");
    String viewportId = json.getString("viewportId");
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    String stampId = json.getString("stampId");
    String text = json.getString(Field.TEXT.getName());
    Long color = json.getLong("color");
    JsonArray position = json.getJsonArray("position");
    JsonArray size = json.getJsonArray(Field.SIZE.getName());
    JsonArray notes = json.getJsonArray("notes");
    String geometry = json.getString("geometry");
    String userAgent = json.getString(Field.USER_AGENT.getName());

    if (fileId == null || userId == null || device == null || type == null) {
      return;
    }

    if (null == MarkupType.getType(type)) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL15");
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "addMarkup");
        String markupId = Utils.generateUUID();
        long timestamp = GMTHelper.utcCurrentTime();
        Item markup = new Item()
            .withPrimaryKey(Field.PK.getName(), "markups#" + fileId, Field.SK.getName(), markupId)
            .withString(Field.STATE.getName(), CommentState.ACTIVE.name())
            .withLong(Field.TIMESTAMP_SHORT.getName(), timestamp)
            .withLong(Field.CREATED.getName(), timestamp)
            .withString(Field.AUTHOR.getName(), userId)
            .withString(Field.TYPE.getName(), type)
            .withString(Field.DEVICE.getName(), device);
        if (spaceId != null && !spaceId.isEmpty()) {
          markup.withString("spaceId", spaceId);
        }
        if (viewportId != null && !viewportId.isEmpty()) {
          markup.withString("viewportId", viewportId);
        }

        switch (MarkupType.getType(type)) {
          case ENTITY:
            if (geometry != null && !geometry.isEmpty()) {
              markup.withString("geometry", geometry);
            }
            if (color != null) {
              markup.withLong("color", color);
            }
            break;
          case VOICENOTE:
          case PICTURENOTE:
            if (position != null
                && position.size() == 3
                && position.getDouble(0) != null
                && position.getDouble(1) != null
                && position.getDouble(2) != null) {
              markup.withDouble("posx", position.getDouble(0));
              markup.withDouble("posy", position.getDouble(1));
              markup.withDouble("posz", position.getDouble(2));
            }
            if (notes != null && !notes.isEmpty()) {
              // we should validate that these notes belong to the user.
              markup.withJSON("notes", notes.encode());
            }
            break;
          case STAMP:
            if (stampId != null && !stampId.isEmpty()) {
              markup.withString("stampId", stampId);
            }
            if (text != null && !text.isEmpty()) {
              markup.withString(Field.TEXT.getName(), text);
            }
            if (color != null) {
              markup.withLong("color", color);
            }
            if (position != null
                && position.size() == 3
                && position.getDouble(0) != null
                && position.getDouble(1) != null
                && position.getDouble(2) != null) {
              markup.withDouble("posx", position.getDouble(0));
              markup.withDouble("posy", position.getDouble(1));
              markup.withDouble("posz", position.getDouble(2));
            }
            if (size != null
                && size.size() == 2
                && size.getDouble(0) != null
                && size.getDouble(1) != null) {
              markup.withDouble("sizex", size.getDouble(0));
              markup.withDouble("sizey", size.getDouble(1));
            }
            if (geometry != null && !geometry.isEmpty()) {
              markup.withString("geometry", geometry);
            }
            break;
        }

        Item user = Users.getUserById(userId);
        if (user != null) {
          markup.withString(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()));
        }

        Comments.saveAnnotation(markup);

        JsonObject markupObject = Comments.markupToJson(markup, user, userAgent);
        markupObject.put("comments", new JsonArray());

        XRayManager.endSegment(subsegment);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".markupsUpdate",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(Field.MARKUP_ID.getName(), markupId)
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "add")
                      .put(Field.AUTHOR.getName(), markup.getString(Field.AUTHOR.getName()))
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));
              JsonObject subscriptionObject = new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.USER_ID.getName(), userId);
              if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
                // limit scope for specific thread only for token users
                subscriptionObject
                    .put(
                        "scope",
                        new JsonArray()
                            .add(Subscriptions.subscriptionScope.MARKUP + "#" + markupId))
                    .put("scopeUpdate", Subscriptions.scopeUpdate.APPEND.toString())
                    .put(Field.TOKEN.getName(), json.getString(Field.TOKEN.getName()));
              } else {
                subscriptionObject
                    .put(
                        "scope",
                        new JsonArray().add(Subscriptions.subscriptionScope.GLOBAL.toString()))
                    .put("scopeUpdate", Subscriptions.scopeUpdate.REWRITE.toString());
              }
              eb_send(
                  blockingSegment, Subscriptions.address + ".addSubscription", subscriptionObject);
              eb_send(
                  blockingSegment,
                  NotificationEvents.address + ".newMarkup",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.MARKUP_ID.getName(), markupId)
                      .put(Field.TYPE.getName(), type));
            });

        sendOK(segment, message, markupObject, mills);
        updateLastCommentTimestamp(
            segment, message, fileId, storageType.name(), markup.getLong(Field.CREATED.getName()));
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doGetAnnotation(Message<JsonObject> message, String type, String idField) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String annotationId = getRequiredString(segment, idField, message, json);
    String timestamp = json.getString(Field.TIMESTAMP.getName());

    if (fileId == null || annotationId == null || userId == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)) {
        getAnnotation(segment, message, fileId, annotationId, timestamp, mills, null, type);
      } else if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        getAnnotation(segment, message, fileId, annotationId, timestamp, mills, userId, type);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void getAnnotation(
      Entity segment,
      Message<JsonObject> message,
      String fileId,
      String threadId,
      String timestamp,
      long mills,
      String userId,
      String type) {
    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "getAnnotation");
    String userAgent = message.body().getString(Field.USER_AGENT.getName());
    Item annotation = Comments.getAnnotation(fileId, threadId, type);
    if (annotation == null) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
      return;
    }
    if (userId != null && !annotation.getString(Field.AUTHOR.getName()).equals(userId)) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
      return;
    }
    JsonObject annotationObject = Comments.annotationToJson(annotation, userAgent);

    long timeStampValue = 0;
    try {
      timeStampValue = Long.parseLong(timestamp);
    } catch (Exception ignore) {
      timestamp = null;
    }

    Iterator<Item> comments = Comments.getAnnotationComments(
        timestamp == null ? null : timeStampValue, annotation, threadId);

    comments.forEachRemaining(comment -> annotationObject
        .getJsonArray("comments")
        .add(
            Comments.commentToJson(comment, comment.getString(Field.AUTHOR.getName()), userAgent)));

    ((List<JsonObject>) annotationObject.getJsonArray("comments").getList())
        .sort(Comparator.comparingLong(o -> o.getLong(Field.CREATED.getName())));

    XRayManager.endSegment(subsegment);
    sendOK(segment, message, annotationObject, mills);
  }

  private void doUpdateCommentThread(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String threadId = getRequiredString(segment, Field.THREAD_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String spaceId = json.getString("spaceId");
    String viewportId = json.getString("viewportId");
    String title = json.getString(Field.TITLE.getName());
    String state = json.getString(Field.STATE.getName());
    String text = json.getString(Field.TEXT.getName());
    JsonArray idsAdd = json.getJsonArray("idsAdd");
    JsonArray idsRemove = json.getJsonArray("idsRemove");
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || threadId == null || userId == null) {
      return;
    }

    if (CommentState.RESOLVED.name().equals(state)) {
      // we are resolving a comment thread. Old versions of ARES will send other fields as well.
      // Lets purge these
      idsAdd = idsRemove = null;
      spaceId = viewportId = null;
      text = title = null;
    }

    final String ftext = text, ftitle = title, fviewportId = viewportId, fspaceId = spaceId;
    final JsonArray fidsAdd = idsAdd, fidsRemove = idsRemove;

    boolean authorProtectedFields = title != null || text != null;

    if (!authorProtectedFields
        && (state == null || state.isEmpty())
        && (idsAdd == null || idsAdd.isEmpty())
        && (idsRemove == null || idsRemove.isEmpty())
        && spaceId == null
        && viewportId == null) {
      sendError(segment, message, HttpStatus.BAD_REQUEST, "FL8");
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "updateThread");

        Item thread = Comments.getAnnotation(fileId, threadId, "comments");
        if (thread == null) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
          return;
        }
        // token users can only threads they authored
        // all users can only change text and title if they authored
        if ((AccessCacheConstants.TOKEN.getTitle().equals(accessValue) || authorProtectedFields)
            && !thread.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }

        final String oldState = thread.getString(Field.STATE.getName());
        // deleted threads can't be changed. No undelete
        if (CommentState.DELETED.name().equals(oldState)) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
          return;
        }

        // resolved threads cannot be modified unless you are also modifying the state to active
        if (state == null && CommentState.RESOLVED.name().equals(oldState)) {
          sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
          return;
        }

        // only allow a user to change idsAdd, idsRemove, spaceId, viewportId if they are the
        // author of a
        // in the thread.
        // TO BE DISCUSSED IF THIS IS CORRECT OR IGF WE SHOULD ONLY ALLOW THREAD AUTHORS TO DO IT.
        if (!thread.getString(Field.AUTHOR.getName()).equals(userId)
            && ((fidsAdd != null && !fidsAdd.isEmpty())
                || (fidsRemove != null && !fidsRemove.isEmpty())
                || fspaceId != null
                || fviewportId != null)) {

          boolean check = Comments.checkUserCommentsForAnnotation(
              threadId, userId, thread.getString(Field.PK.getName()));

          if (!check) {
            sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
            return;
          }
        }

        boolean isDeleted = false;
        boolean isResolved = false;
        boolean isReopened = false;
        if (state != null && !state.isEmpty()) {
          CommentState threadState = CommentState.getState(thread.getString(Field.STATE.getName()));
          CommentState newState = CommentState.getState(state);
          /* allowed transitions:
                 active -> resolved (anyone)
                 active -> deleted (only author)
                 resolved -> active (anyone)
                 resolved -> deleted (only author)
          */
          if (CommentState.ACTIVE.equals(threadState)
                  && (CommentState.RESOLVED.equals(newState)
                      || CommentState.DELETED.equals(newState))
              || CommentState.RESOLVED.equals(threadState)
                  && (CommentState.ACTIVE.equals(newState)
                      || CommentState.DELETED.equals(newState))) {
            if (CommentState.DELETED.equals(newState)
                && !thread.getString(Field.AUTHOR.getName()).equals(userId)) {
              sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
              return;
            }
            if (CommentState.DELETED.equals(newState)) {
              isDeleted = true;
            } else if (CommentState.RESOLVED.equals(newState)) {
              isResolved = true;
            } else {
              isReopened = true;
            }

            thread.withString(Field.STATE.getName(), state.toUpperCase());
          } else {
            sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
            return;
          }
        }
        final String oldTitle = thread.getString(Field.TITLE.getName());
        if (ftitle != null) {
          if (!ftitle.isEmpty()) {
            thread.withString(Field.TITLE.getName(), ftitle);
          } else {
            thread.removeAttribute(Field.TITLE.getName());
          }
        }

        boolean didEntitiesChange = false;

        List<String> ids = thread.getList("entityHandles");
        if (ids == null) {
          ids = new ArrayList<>();
        }
        if (fidsAdd != null && !fidsAdd.isEmpty()) {
          ids.addAll(fidsAdd.getList());
          didEntitiesChange = true;
        }
        if (fidsRemove != null && !fidsRemove.isEmpty()) {
          ids.removeAll(fidsRemove.getList());
          didEntitiesChange = true;
        }
        if (!ids.isEmpty()) {
          thread.withList("entityHandles", ids);
        } else {
          thread.removeAttribute("entityHandles");
        }

        if (fspaceId != null) {
          if (fspaceId.trim().isEmpty()) {
            thread.removeAttribute("spaceId");
          } else {
            thread.withString("spaceId", fspaceId);
          }
        }

        if (fviewportId != null) {
          if (fviewportId.trim().isEmpty()) {
            thread.removeAttribute("viewportId");
          } else {
            thread.withString("viewportId", fviewportId);
          }
        }

        long timestamp = GMTHelper.utcCurrentTime();
        Item rootcomment = Comments.getAnnotation(fileId, threadId + "#root", "comments");
        if (rootcomment == null) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
          return;
        }
        // update root comment text
        boolean isRootCommentUpdated = false;
        final String oldRootComment = rootcomment.getString(Field.TEXT.getName());
        if (Utils.isStringNotNullOrEmpty(ftext) && !ftext.equals(oldRootComment)) {
          isRootCommentUpdated = true;
          // DK: I think that we should update root's comment data only if text has really changed
          Comments.setAnnotationText(
              ftext,
              rootcomment.getString(Field.PK.getName()),
              rootcomment.getString(Field.SK.getName()));
        }

        JsonObject result = new JsonObject();
        thread.withLong(Field.TIMESTAMP_SHORT.getName(), timestamp);
        Comments.saveAnnotation(thread);
        result.put(Field.TIMESTAMP.getName(), timestamp);

        XRayManager.endSegment(subsegment);
        final boolean fDeleted = isDeleted;
        final boolean fResolved = isResolved;
        final boolean fReopened = isReopened;
        final boolean fRootCommentUpdate = isRootCommentUpdated;
        final boolean fEntitiesChanged = didEntitiesChange;

        String newTitle = ftitle;
        if (!Utils.isStringNotNullOrEmpty(newTitle)) {
          newTitle = "Untitled";
        }
        final String fNewTitle = newTitle;
        final String fOldTitle = Utils.isStringNotNullOrEmpty(oldTitle) ? oldTitle : "Untitled";
        List<String> users = Comments.getNewMentionedUsersFromUpdatedText(oldRootComment, ftext);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".commentsUpdate",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(Field.THREAD_ID.getName(), threadId)
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "update")
                      .put(Field.AUTHOR.getName(), thread.getString(Field.AUTHOR.getName()))
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));
              if (fDeleted) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".threadDeleted",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId));
              } else if (fResolved) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".threadResolved",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId));
              } else if (fReopened) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".threadReopened",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId));
              } else if (!fNewTitle.equals(fOldTitle)) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".threadTitleChanged",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId)
                        .put(Field.TITLE.getName(), fNewTitle)
                        .put("oldTitle", fOldTitle));
              }
              if (fRootCommentUpdate) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".modifiedComment",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId)
                        .put(Field.TEXT.getName(), ftext)
                        .put("oldText", oldRootComment)
                        .put(Field.COMMENT_ID.getName(), Field.ROOT.getName())
                        .put("mentionedUsers", users));
              }
              if (fEntitiesChanged) {
                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".entitiesChanged",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.THREAD_ID.getName(), threadId));
              }
            });

        getInAccessibleMentionedUsers(storageType, users, json).onComplete(e -> {
          JsonArray usersWithNoAccess;
          if (e.succeeded()) {
            usersWithNoAccess = e.result();
            shareFileWithMentionedUsers(segment, message, json, usersWithNoAccess);
          } else {
            if (!e.cause().getMessage().equals("NO DATA")) {
              sendError(segment, message, e.cause().getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
              return;
            }
            usersWithNoAccess = new JsonArray();
          }
          result.put("newCollaborators", usersWithNoAccess);
          sendOK(segment, message, result, mills);
          JsonObject mentionJson = json.copy();
          mentionJson.put(Field.THREAD_ID.getName(), threadId);
          mentionJson.put(
              Field.TIMESTAMP_C.getName(), thread.getLong(Field.TIMESTAMP_SHORT.getName()));
          mentionJson.put(Field.COMMENT_ID.getName(), Field.ROOT.getName());
          handleMentionsData(
              segment,
              message,
              usersWithNoAccess,
              NotificationEvents.eventTypes.THREADMODIFIED,
              users,
              fileId,
              storageType,
              mentionJson,
              userId,
              null,
              false);
        });
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private StorageType getRealStorageType(StorageType storageType, JsonObject event) {
    StorageType realStorageType = storageType;
    if (event.containsKey(Field.STORAGE_TYPE.getName())) {
      realStorageType = StorageType.getStorageType(event.getString(Field.STORAGE_TYPE.getName()));
    }
    return realStorageType;
  }

  private void doUpdateMarkup(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String markupId = getRequiredString(segment, Field.MARKUP_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String spaceId = json.getString("spaceId");
    String viewportId = json.getString("viewportId");
    String state = json.getString(Field.STATE.getName());
    String stampId = json.getString("stampId");
    String text = json.getString(Field.TEXT.getName());
    Long color = json.getLong("color");
    JsonArray position = json.getJsonArray("position");
    JsonArray size = json.getJsonArray(Field.SIZE.getName());
    JsonArray notes = json.getJsonArray("notes");
    String geometry = json.getString("geometry");

    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || markupId == null || userId == null) {
      return;
    }

    final String fspaceId = spaceId,
        fviewportId = viewportId,
        fstampId = stampId,
        ftext = text,
        fgeometry = geometry;
    final Long fcolor = color;
    final Double fsizex = size != null ? size.getDouble(0) : null;
    final Double fsizey = size != null ? size.getDouble(1) : null;
    final Double fpositionx = position != null ? position.getDouble(0) : null;
    final Double fpositiony = position != null ? position.getDouble(1) : null;
    final Double fpositionz = position != null ? position.getDouble(2) : null;

    if ((state == null || state.isEmpty()) && spaceId == null && viewportId == null) {
      sendError(segment, message, HttpStatus.BAD_REQUEST, "FL8");
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "updateMarkup");

        Item markup = Comments.getAnnotation(fileId, markupId, "markups");

        // deleted markups can't be changed. No undelete
        if (markup == null
            || CommentState.DELETED.name().equals(markup.getString(Field.STATE.getName()))) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
          return;
        }

        final String author = markup.getString(Field.AUTHOR.getName());
        final boolean isCurrentUserAuthor = author.equals(userId);

        // token users can only see markup they authored
        if ((AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) && !isCurrentUserAuthor) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }

        // resolved threads cannot be modified unless you are also modifying the state to active
        if (state == null
            && CommentState.RESOLVED.name().equals(markup.getString(Field.STATE.getName()))) {
          sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
          return;
        }

        // only allow a user to change spaceId  if they are the author of a
        // in the thread.
        // TO BE DISCUSSED IF THIS IS CORRECT OR IF WE SHOULD ONLY ALLOW THREAD AUTHORS TO DO IT.
        if (!isCurrentUserAuthor && fspaceId != null && fviewportId != null) {
          boolean check = Comments.checkUserCommentsForAnnotation(
              markupId, userId, markup.getString(Field.PK.getName()));

          if (!check) {
            sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
            return;
          }
        }

        final CommentState oldState =
            CommentState.getState(markup.getString(Field.STATE.getName()));
        CommentState newState = oldState;
        if (state != null && !state.isEmpty()) {
          newState = CommentState.getState(state);
          /* allowed transitions:
                 active -> resolved (anyone)
                 active -> deleted (only author)
                 resolved -> active (anyone)
                 resolved -> deleted (only author)
          */
          if (CommentState.ACTIVE.equals(oldState)
                  && (CommentState.RESOLVED.equals(newState)
                      || CommentState.DELETED.equals(newState))
              || CommentState.RESOLVED.equals(oldState)
                  && (CommentState.ACTIVE.equals(newState)
                      || CommentState.DELETED.equals(newState))) {
            if (CommentState.DELETED.equals(newState)
                && !markup.getString(Field.AUTHOR.getName()).equals(userId)) {
              sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
              return;
            }
            markup.withString(Field.STATE.getName(), state.toUpperCase());
          } else {
            sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
            return;
          }
        }

        if (fspaceId != null) {
          if (fspaceId.trim().isEmpty()) {
            markup.removeAttribute("spaceId");
          } else {
            markup.withString("spaceId", fspaceId);
          }
        }

        if (fviewportId != null) {
          if (fviewportId.trim().isEmpty()) {
            markup.removeAttribute("viewportId");
          } else {
            markup.withString("viewportId", fviewportId);
          }
        }

        if (markup.getString(Field.TYPE.getName()).equals(MarkupType.STAMP.toString())) {
          if (fstampId != null) {
            if (fstampId.trim().isEmpty()) {
              markup.removeAttribute("stampId");
            } else {
              markup.withString("stampId", fstampId);
            }
          }

          if (fcolor != null) {
            markup.withLong("color", fcolor);
          }

          if (ftext != null) {
            if (ftext.trim().isEmpty()) {
              markup.removeAttribute(Field.TEXT.getName());
            } else {
              markup.withString(Field.TEXT.getName(), ftext);
            }
          }

          if (fsizex != null) {
            markup.withDouble("sizex", fsizex);
          }
          if (fsizey != null) {
            markup.withDouble("sizey", fsizey);
          }

          if (fpositionx != null) {
            markup.withDouble("posx", fpositionx);
          }
          if (fpositiony != null) {
            markup.withDouble("posy", fpositiony);
          }
          if (fpositionz != null) {
            markup.withDouble("posz", fpositionz);
          }
          if (fgeometry != null) {
            if (fgeometry.trim().isEmpty()) {
              markup.removeAttribute("geometry");
            } else {
              markup.withString("geometry", fgeometry);
            }
          }
        } else if (markup.getString(Field.TYPE.getName()).equals(MarkupType.ENTITY.toString())) {
          if (fcolor != null) {
            markup.withLong("color", fcolor);
          }

          if (fgeometry != null) {
            if (fgeometry.trim().isEmpty()) {
              markup.removeAttribute("geometry");
            } else {
              markup.withString("geometry", fgeometry);
            }
          }
        }
        Long timestamp = GMTHelper.utcCurrentTime();

        JsonObject result = new JsonObject();
        markup.withLong(Field.TIMESTAMP_SHORT.getName(), timestamp);

        Comments.saveAnnotation(markup);

        result.put(Field.TIMESTAMP.getName(), timestamp);

        XRayManager.endSegment(subsegment);

        final CommentState finalNewState = newState;
        final String markupType = markup.getString(Field.TYPE.getName());
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + ".markupsUpdate",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(Field.MARKUP_ID.getName(), markupId)
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "update")
                      .put(Field.AUTHOR.getName(), markup.getString(Field.AUTHOR.getName()))
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));
              String address = ".modifiedMarkup";
              if (finalNewState != null && !finalNewState.equals(oldState)) {
                if (finalNewState.equals(CommentState.DELETED)) {
                  address = ".markupDeleted";
                } else if (finalNewState.equals(CommentState.RESOLVED)) {
                  address = ".markupResolved";
                } else {
                  address = ".markupActive";
                }
              }
              eb_send(
                  blockingSegment,
                  NotificationEvents.address + address,
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.MARKUP_ID.getName(), markupId)
                      .put(Field.TYPE.getName(), markupType));
            });
        sendOK(segment, message, result, mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doDeleteAnnotation(Message<JsonObject> message, String type, String idField) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String annotationId = getRequiredString(segment, idField, message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String timestamp = getRequiredString(segment, Field.TIMESTAMP.getName(), message, json);
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || annotationId == null || userId == null || timestamp == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "deleteThread");
        Item annotation = Comments.getAnnotation(fileId, annotationId, type);
        if (annotation != null && !annotation.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }
        if (annotation == null
            || CommentState.DELETED.name().equals(annotation.getString(Field.STATE.getName()))) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
          return;
        }
        if (annotation.getLong(Field.TIMESTAMP_SHORT.getName()) > Long.parseLong(timestamp)) {
          sendError(segment, message, HttpStatus.CONFLICT, "FL11");
          return;
        }

        Comments.setAnnotationState(
            CommentState.DELETED.name(),
            annotation.getString(Field.PK.getName()),
            annotation.getString(Field.SK.getName()));

        eb_send(
            segment,
            WebSocketManager.address + "." + type + "Update",
            new JsonObject()
                .put(
                    Field.FILE_ID.getName(),
                    StorageType.processFileIdForWebsocketNotification(finalRealStorageType, fileId))
                .put(Field.TIMESTAMP.getName(), Long.parseLong(timestamp))
                .put(idField, annotationId)
                .put(Field.USER_ID.getName(), userId)
                .put(Field.TYPE.getName(), "delete")
                .put(Field.AUTHOR.getName(), annotation.getString(Field.AUTHOR.getName()))
                .put(Field.X_SESSION_ID.getName(), json.getString(Field.X_SESSION_ID.getName())));
        if (type.equals("comments")) {
          eb_send(
              segment,
              NotificationEvents.address + ".threadDeleted",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.THREAD_ID.getName(), annotationId));
        } else if (type.equals("markups")) {
          eb_send(
              segment,
              NotificationEvents.address + ".markupDeleted",
              new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.MARKUP_ID.getName(), annotationId)
                  .put(Field.TYPE.getName(), annotation.getString(Field.TYPE.getName())));
        }
        XRayManager.endSegment(subsegment);
        sendOK(segment, message, mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doAddComment(Message<JsonObject> message, String type, String idField) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String annotationId = getRequiredString(segment, idField, message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String text = getRequiredString(segment, Field.TEXT.getName(), message, json);
    String device = getRequiredString(segment, Field.DEVICE.getName(), message, json);
    String loc = json.getString("loc");
    String userAgent = json.getString(Field.USER_AGENT.getName());
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null
        || annotationId == null
        || userId == null
        || text == null
        || device == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "addComment");
        Item annotation = Comments.getAnnotation(fileId, annotationId, type);
        if (!isAnnotationAccessible(segment, message, annotation)) {
          return;
        }
        if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)
            && !annotation.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }

        long timestamp = GMTHelper.utcCurrentTime();
        String commentId = Utils.generateUUID();
        Item comment = new Item()
            .withPrimaryKey(
                Field.PK.getName(),
                type + "#" + fileId,
                Field.SK.getName(),
                annotationId + "#" + commentId)
            .withString(Field.STATE.getName(), CommentState.ACTIVE.name())
            .withLong(Field.TIMESTAMP_SHORT.getName(), timestamp)
            .withLong(Field.CREATED.getName(), timestamp)
            .withString(Field.AUTHOR.getName(), userId)
            .withString(Field.DEVICE.getName(), device)
            .withString(Field.TEXT.getName(), text);
        if (loc != null && !loc.isEmpty()) {
          comment.withString("loc", loc);
        }

        Comments.setAnnotationTimeStamp(
            annotation.getString(Field.PK.getName()), annotation.getString(Field.SK.getName()));
        Comments.saveAnnotation(comment);

        JsonObject obj = Comments.commentToJson(comment, userId, userAgent);

        List<String> users = Comments.getMentionedUsersFromText(text);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + "." + type + "Update",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(idField, annotationId)
                      .put(Field.COMMENT_ID.getName(), commentId)
                      .put(Field.AUTHOR.getName(), annotation.getString(Field.AUTHOR.getName()))
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "add")
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));
              JsonObject notificationObject = new JsonObject()
                  .put(Field.FILE_ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                  .put(Field.USER_ID.getName(), userId)
                  .put(idField, annotationId)
                  .put(Field.COMMENT_ID.getName(), commentId)
                  .put(Field.TEXT.getName(), text)
                  .put("mentionedUsers", users);
              eb_send(
                  blockingSegment, NotificationEvents.address + ".newComment", notificationObject);
            });

        XRayManager.endSegment(subsegment);
        getInAccessibleMentionedUsers(storageType, users, json).onComplete(e -> {
          JsonArray usersWithNoAccess;
          if (e.succeeded()) {
            usersWithNoAccess = e.result();
            shareFileWithMentionedUsers(segment, message, json, usersWithNoAccess);
          } else {
            if (!e.cause().getMessage().equals("NO DATA")) {
              sendError(segment, message, e.cause().getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
              return;
            }
            usersWithNoAccess = new JsonArray();
          }
          obj.put("newCollaborators", usersWithNoAccess);
          sendOK(segment, message, obj, mills);
          updateLastCommentTimestamp(
              segment,
              message,
              fileId,
              storageType.name(),
              comment.getLong(Field.CREATED.getName()));
          JsonObject mentionJson = json.copy();
          mentionJson.put(Field.TITLE.getName(), annotation.getString(Field.TITLE.getName()));
          mentionJson.put(Field.THREAD_ID.getName(), annotationId);
          mentionJson.put(Field.COMMENT_ID.getName(), commentId);
          mentionJson.put(
              Field.TIMESTAMP_C.getName(), comment.getLong(Field.TIMESTAMP_SHORT.getName()));
          handleMentionsData(
              segment,
              message,
              usersWithNoAccess,
              NotificationEvents.eventTypes.NEWCOMMENT,
              users,
              fileId,
              storageType,
              mentionJson,
              userId,
              type,
              true);
        });
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doUpdateComment(Message<JsonObject> message, String type, String idField) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String annotationId = getRequiredString(segment, idField, message, json);
    String commentId = getRequiredString(segment, Field.COMMENT_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String text = json.getString(Field.TEXT.getName());
    String state = json.getString(Field.STATE.getName());
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || annotationId == null || commentId == null || userId == null) {
      return;
    }

    if ((text == null || text.isEmpty()) && (state == null || state.isEmpty())) {
      sendError(segment, message, HttpStatus.BAD_REQUEST, "FL8");
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "updateComment");
        Item annotation = Comments.getAnnotation(fileId, annotationId, type);
        if (!isAnnotationAccessible(segment, message, annotation)) {
          return;
        }
        if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)
            && !annotation.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }
        Item comment;
        if (commentId.equals(Field.ROOT.getName())) {
          comment = annotation;
        } else {
          comment = Comments.getAnnotation(fileId, annotationId + "#" + commentId, type);
        }

        if (text != null
            && !text.isEmpty()
            && comment != null
            && !comment.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL13");
          return;
        }
        if (comment == null
            || CommentState.DELETED.name().equals(comment.getString(Field.STATE.getName()))) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL12");
          return;
        }

        Map<String, Object> attributes = comment.asMap();
        final String oldText = comment.getString(Field.TEXT.getName());
        // update only if the text is actually changed
        if (Utils.isStringNotNullOrEmpty(text) && !oldText.equals(text)) {
          comment.withString(Field.TEXT.getName(), text);
        }

        if (state != null && !state.isEmpty() && !state.equals(CommentState.DELETED.name())) {
          comment.withString(Field.STATE.getName(), state);
        }

        JsonObject result = new JsonObject();
        List<String> users = Comments.getNewMentionedUsersFromUpdatedText(oldText, text);
        if (!attributes.equals(comment.asMap())) {
          long timestamp = GMTHelper.utcCurrentTime();
          comment.withLong(Field.TIMESTAMP_SHORT.getName(), timestamp);
          if (annotation != comment) {
            Comments.saveAnnotation(comment);
          }

          Comments.setAnnotationTimeStamp(
              annotation.getString(Field.PK.getName()), annotation.getString(Field.SK.getName()));
          result.put(Field.TIMESTAMP.getName(), timestamp);

          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .run((Segment blockingSegment) -> {
                eb_send(
                    blockingSegment,
                    WebSocketManager.address + "." + type + "Update",
                    new JsonObject()
                        .put(
                            Field.FILE_ID.getName(),
                            StorageType.processFileIdForWebsocketNotification(
                                finalRealStorageType, fileId))
                        .put(Field.TIMESTAMP.getName(), timestamp)
                        .put(idField, annotationId)
                        .put(Field.COMMENT_ID.getName(), commentId)
                        .put(Field.AUTHOR.getName(), annotation.getString(Field.AUTHOR.getName()))
                        .put(Field.USER_ID.getName(), userId)
                        .put(Field.TYPE.getName(), "update")
                        .put(
                            Field.X_SESSION_ID.getName(),
                            json.getString(Field.X_SESSION_ID.getName())));

                eb_send(
                    blockingSegment,
                    NotificationEvents.address + ".modifiedComment",
                    new JsonObject()
                        .put(Field.FILE_ID.getName(), fileId)
                        .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                        .put(Field.USER_ID.getName(), userId)
                        .put(idField, annotationId)
                        .put(Field.COMMENT_ID.getName(), commentId)
                        .put(Field.TEXT.getName(), text)
                        .put("oldText", oldText)
                        .put(Field.STATE.getName(), state)
                        .put(Field.COMMENT_ID.getName(), commentId)
                        .put("mentionedUsers", users));
              });
        }
        XRayManager.endSegment(subsegment);
        getInAccessibleMentionedUsers(storageType, users, json).onComplete(e -> {
          JsonArray usersWithNoAccess;
          if (e.succeeded()) {
            usersWithNoAccess = e.result();
            shareFileWithMentionedUsers(segment, message, json, usersWithNoAccess);
          } else {
            if (!e.cause().getMessage().equals("NO DATA")) {
              sendError(segment, message, e.cause().getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
              return;
            }
            usersWithNoAccess = new JsonArray();
          }
          result.put("newCollaborators", usersWithNoAccess);
          sendOK(segment, message, result, mills);
          JsonObject mentionJson = json.copy();
          mentionJson.put(Field.TITLE.getName(), annotation.getString(Field.TITLE.getName()));
          mentionJson.put(Field.THREAD_ID.getName(), annotationId);
          mentionJson.put(Field.COMMENT_ID.getName(), commentId);
          mentionJson.put(
              Field.TIMESTAMP_C.getName(), comment.getLong(Field.TIMESTAMP_SHORT.getName()));
          handleMentionsData(
              segment,
              message,
              usersWithNoAccess,
              NotificationEvents.eventTypes.MODIFIEDCOMMENT,
              users,
              fileId,
              storageType,
              mentionJson,
              userId,
              type,
              false);
        });
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doDeleteComment(Message<JsonObject> message, String type, String idField) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String annotationId = getRequiredString(segment, idField, message, json);
    String commentId = getRequiredString(segment, Field.COMMENT_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));

    if (fileId == null || annotationId == null || commentId == null || userId == null) {
      return;
    }

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        StorageType finalRealStorageType = getRealStorageType(storageType, event);
        Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "deleteComment");

        Item annotation = Comments.getAnnotation(fileId, annotationId, type);
        if (!isAnnotationAccessible(segment, message, annotation)) {
          return;
        }
        if (AccessCacheConstants.TOKEN.getTitle().equals(accessValue)
            && !annotation.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL9");
          return;
        }
        Item comment = Comments.getAnnotation(fileId, annotationId + "#" + commentId, type);

        if (comment != null && !comment.getString(Field.AUTHOR.getName()).equals(userId)) {
          sendError(segment, message, HttpStatus.FORBIDDEN, "FL13");
          return;
        }
        if (comment == null
            || CommentState.DELETED.name().equals(comment.getString(Field.STATE.getName()))) {
          sendError(segment, message, HttpStatus.NOT_FOUND, "FL12");
          return;
        }

        long timestamp = GMTHelper.utcCurrentTime();
        // delete the annotation on deleting the root comment
        if (commentId.equals(Field.ROOT.getName())) {
          Comments.setAnnotationState(
              CommentState.DELETED.name(),
              annotation.getString(Field.PK.getName()),
              annotation.getString(Field.SK.getName()));
        } else {
          Comments.setAnnotationTimeStamp(
              annotation.getString(Field.PK.getName()), annotation.getString(Field.SK.getName()));
        }

        Comments.setAnnotationState(
            CommentState.DELETED.name(),
            comment.getString(Field.PK.getName()),
            comment.getString(Field.SK.getName()));

        XRayManager.endSegment(subsegment);
        sendOK(segment, message, new JsonObject().put(Field.TIMESTAMP.getName(), timestamp), mills);

        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              eb_send(
                  blockingSegment,
                  WebSocketManager.address + "." + type + "Update",
                  new JsonObject()
                      .put(
                          Field.FILE_ID.getName(),
                          StorageType.processFileIdForWebsocketNotification(
                              finalRealStorageType, fileId))
                      .put(Field.TIMESTAMP.getName(), timestamp)
                      .put(idField, annotationId)
                      .put(Field.COMMENT_ID.getName(), commentId)
                      .put(Field.AUTHOR.getName(), annotation.getString(Field.AUTHOR.getName()))
                      .put(Field.USER_ID.getName(), userId)
                      .put(Field.TYPE.getName(), "delete")
                      .put(
                          Field.X_SESSION_ID.getName(),
                          json.getString(Field.X_SESSION_ID.getName())));

              eb_send(
                  blockingSegment,
                  NotificationEvents.address + ".deletedComment",
                  new JsonObject()
                      .put(Field.FILE_ID.getName(), fileId)
                      .put(Field.STORAGE_TYPE.getName(), finalRealStorageType.toString())
                      .put(Field.USER_ID.getName(), userId)
                      .put(idField, annotationId)
                      .put(Field.COMMENT_ID.getName(), commentId)
                      .put(Field.TEXT.getName(), comment.getString(Field.TEXT.getName()))
                      .put(Field.COMMENT_ID.getName(), commentId));
            });
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private void doGetLatestFileComment(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    JsonObject json = message.body();
    String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
    String storageType = getRequiredString(segment, Field.STORAGE_TYPE.getName(), message, json);

    hasAccess(segment, message, json, event -> {
      final String accessValue = event.getString(Field.ACCESS.getName());
      if (AccessCacheConstants.OK.getTitle().equals(accessValue)
          || AccessCacheConstants.TOKEN.getTitle().equals(accessValue)) {
        // get comments
        Iterator<Item> comments = Comments.getAllAnnotations(null, fileId, false, true, "comments");

        // get markups
        Iterator<Item> markups = Comments.getAllAnnotations(null, fileId, false, true, "markups");

        ItemCollection<QueryOutcome> attachments;
        // get attachments
        if (AccessCacheConstants.OK.getTitle().equals(accessValue)) {
          attachments =
              Attachments.getAttachments(fileId, StorageType.getStorageType(storageType), null);
        } else {
          attachments =
              Attachments.getAttachments(fileId, StorageType.getStorageType(storageType), userId);
        }
        Stream<Item> itemStream = StreamSupport.stream(
            Iterables.concat(
                    IteratorUtils.toList(comments), IteratorUtils.toList(markups), attachments)
                .spliterator(),
            false);
        Comparator<Item> comparator = Comparator.comparing(
            u -> u.getLong(Field.CREATED.getName()), Comparator.reverseOrder());
        Optional<Item> latestItem = itemStream
            .filter(item -> item.hasAttribute(Field.CREATED.getName())
                && Objects.nonNull(item.get(Field.CREATED.getName())))
            .sorted(comparator)
            .limit(1)
            .findFirst();
        Long lastCommentedOn = null;
        if (latestItem.isPresent()) {
          Item latestComment = latestItem.get();
          if (latestComment.hasAttribute(Field.CREATED.getName())) {
            lastCommentedOn = latestComment.getLong(Field.CREATED.getName());
            updateLastCommentTimestamp(segment, message, fileId, storageType, lastCommentedOn);
          }
        }
        sendOK(segment, message, new JsonObject().put("lastCommentedOn", lastCommentedOn), mills);
      } else {
        sendError(segment, message, event, HttpStatus.BAD_REQUEST, "FL6");
      }
    });
  }

  private <T> void hasAccess(
      Entity segment, Message<T> message, JsonObject json, Handler<JsonObject> handler) {
    final StorageType storageType =
        StorageType.getStorageType(json.getString(Field.STORAGE_TYPE.getName()));
    final String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    final String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, json);
    final String token = json.getString(Field.TOKEN.getName());

    JsonObject responseObject = new JsonObject()
        .put(Field.ACCESS.getName(), AccessCacheConstants.ERROR.getTitle())
        .put(Field.STORAGE_TYPE.getName(), storageType.toString());

    if (storageType.equals(StorageType.SAMPLES)) {
      final String userId = getRequiredString(segment, Field.USER_ID.getName(), message, json);
      if (!Utils.isStringNotNullOrEmpty(token)
          && !SimpleStorage.hasAccessToCommentForKudoDrive(fileId, userId)) {
        handler.handle(responseObject
            .put(
                Field.ERROR_MESSAGE.getName(),
                MessageFormat.format(
                    Utils.getLocalizedString(message, "UserHasNoAccessToCommentForFileWithId"),
                    fileId))
            .put(
                Field.ERROR_CONSTANT.getName(),
                AccessErrorCodes.COMMENTING_IS_NOT_ACCESSIBLE.name())
            .put(
                Field.ERROR_CODE.getName(),
                AccessErrorCodes.COMMENTING_IS_NOT_ACCESSIBLE.getCode()));
        return;
      }
    }

    // we cannot do anything without fileId
    // for sessions - we don't support any commenting-related operations for non-logged in users
    if (sessionId == null || fileId == null) {
      // let's send an error to prevent timeouts
      handler.handle(responseObject
          .put(Field.ERROR_MESSAGE.getName(), "No sessionId or fileId")
          .put(Field.ERROR_CONSTANT.getName(), AccessErrorCodes.NOT_ENOUGH_PARAMS.name())
          .put(Field.ERROR_CODE.getName(), AccessErrorCodes.NOT_ENOUGH_PARAMS.getCode()));
      return;
    }

    // first - check cache for non-token case
    // for tokens we check separately

    if (!Utils.isStringNotNullOrEmpty(token)) {
      JsonObject accessStatus = AccessCache.getAccessStatus(fileId, sessionId);
      if (accessStatus != null) {
        String status = accessStatus.getString(Field.STATUS.getName());
        // if access was fine (OK) or file's not accessible (NO_ACCESS) - let's assume that
        // nothing has changed
        // for the cache duration
        if (status.equals(AccessCacheConstants.OK.getTitle())
            || status.equals(AccessCacheConstants.NO_ACCESS.getTitle())) {
          handler.handle(responseObject.put(Field.ACCESS.getName(), status));
          return;
        }
        // if we have a token access - we need to recheck it and it's done later on
        // if we've faced an error - let's retry to see if error has been fixed
      }
    }

    // if this is a shared link we should first check to know the storageType of the file and not
    // the session
    // this is not enough, as we need to find out the connection.
    // As this is missing for now, we will first treat all users as if they have no access to the
    // underlying file.
    if (token != null) {
      // DK: why do we need this? It's unused.
      // hasTokenAccess will check shared link itself - no need to get it twice

      // Item sharedLink = SharedLinks.getPublicLink(config, xRay,fileId, token);
      // if (sharedLink != null) {
      // storageType = StorageType.getStorageType(sharedLink.getString(Field.STORAGE_TYPE.getName
      // ()));
      // }

      // remove this call if we can determine if the user has access to the file as well
      hasTokenAccess(segment, message, json, handler);
      return;
    }

    JsonObject trashObject = new JsonObject()
        .put(Field.USER_ID.getName(), json.getString(Field.USER_ID.getName()))
        .put(Field.FILE_ID.getName(), fileId)
        .put(Field.EXTERNAL_ID.getName(), json.getString(Field.EXTERNAL_ID.getName()))
        .put(Field.OWNER_ID.getName(), json.getString(Field.OWNER_ID.getName()))
        .put(
            Field.ENCAPSULATION_MODE.getName(), json.getString(Field.ENCAPSULATION_MODE.getName()));
    // DK: as we need just to know if file is accessible - let's do similar to how we do in
    // recent files -> validate
    eb_request(
        segment, StorageType.getAddress(storageType) + ".getTrashStatus", trashObject, event -> {
          Entity dummy = XRayManager.createBlockingSegment(operationGroup, segment);
          try {
            if (!event.succeeded() || event.result() == null || event.result().body() == null) {
              // check was unsuccessful for some reason
              // we may want to retry at some point so let's use ERROR here
              log.info("Has access - unsuccessful getting trash info for the file: " + fileId
                  + " storage: " + storageType.name());
              AccessCache.saveAccessStatus(
                  fileId, sessionId, null, AccessCacheConstants.ERROR, storageType);
              handler.handle(responseObject
                  .put(Field.ACCESS.getName(), AccessCacheConstants.ERROR.getTitle())
                  .put(Field.ERROR_MESSAGE.getName(), "Couldn't get trash status")
                  .put(
                      Field.ERROR_CONSTANT.getName(),
                      AccessErrorCodes.UNABLE_TO_GET_TRASH_STATUS.name())
                  .put(
                      Field.ERROR_CODE.getName(),
                      AccessErrorCodes.UNABLE_TO_GET_TRASH_STATUS.getCode()));
            } else {
              // some info has been received - let's check
              JsonObject eventJson = (JsonObject) event.result().body();
              log.info("Has access: received info " + eventJson.toString());
              // if unsuccessful - assume that we don't have access
              if (!Field.OK.getName().equals(eventJson.getString(Field.STATUS.getName()))
                  || eventJson.getBoolean(Field.IS_DELETED.getName(), false)) {
                log.info("Has access: file deleted or no access");
                // most likely file was deleted or user doesn't have direct access anyway
                // let's set NO_ACCESS
                AccessCache.saveAccessStatus(
                    fileId, sessionId, null, AccessCacheConstants.NO_ACCESS, storageType);
                handler.handle(responseObject
                    .put(Field.ACCESS.getName(), AccessCacheConstants.NO_ACCESS.getTitle())
                    .put(Field.ERROR_MESSAGE.getName(), "File is deleted or no direct access")
                    .put(
                        Field.ERROR_CONSTANT.getName(),
                        AccessErrorCodes.FILE_IS_NOT_ACCESSIBLE.name())
                    .put(
                        Field.ERROR_CODE.getName(),
                        AccessErrorCodes.FILE_IS_NOT_ACCESSIBLE.getCode()));
              } else {
                // file seems to be fine
                AccessCache.saveAccessStatus(
                    fileId, sessionId, null, AccessCacheConstants.OK, storageType);
                handler.handle(
                    responseObject.put(Field.ACCESS.getName(), AccessCacheConstants.OK.getTitle()));
              }
            }
          } catch (Exception e) {
            // just in case we face any exception here - let's notify about this
            log.error(
                LogPrefix.getLogPrefix() + " Exception on checking access for commenting: ", e);
            AccessCache.saveAccessStatus(
                fileId, sessionId, null, AccessCacheConstants.ERROR, storageType);
            XRayEntityUtils.addException(segment, e);
            handler.handle(responseObject
                .put(Field.ACCESS.getName(), AccessCacheConstants.ERROR.getTitle())
                .put(Field.ERROR_MESSAGE.getName(), "Exception on checking trash status")
                .put(
                    Field.ERROR_CONSTANT.getName(),
                    AccessErrorCodes.EXCEPTION_DURING_GET_TRASH_STATUS.name())
                .put(
                    Field.ERROR_CODE.getName(),
                    AccessErrorCodes.EXCEPTION_DURING_GET_TRASH_STATUS.getCode()));
          }

          XRayManager.endSegment(dummy);
        });
  }

  private <T> void hasTokenAccess(
      Entity segment, Message<T> message, JsonObject json, Handler<JsonObject> handler) {
    final String token = json.getString(Field.TOKEN.getName());
    final String fileId = getRequiredString(segment, Field.FILE_ID.getName(), message, json);
    final String sessionId = getRequiredString(segment, Field.SESSION_ID.getName(), message, json);
    final String password = json.getString(Field.PASSWORD.getName());
    final String xSessionId = json.getString(Field.X_SESSION_ID.getName());
    String storageType = json.getString(Field.STORAGE_TYPE.getName());

    JsonObject responseObject = new JsonObject().put(Field.STORAGE_TYPE.getName(), storageType);
    // params aren't fine
    if (sessionId == null || token == null || fileId == null) {
      // let's send an error to prevent timeouts
      handler.handle(responseObject
          .put(Field.ACCESS.getName(), AccessCacheConstants.ERROR.getTitle())
          .put(Field.ERROR_MESSAGE.getName(), "No sessionId/fileId or token")
          .put(Field.ERROR_CONSTANT.getName(), AccessErrorCodes.NOT_ENOUGH_PARAMS.name())
          .put(Field.ERROR_CODE.getName(), AccessErrorCodes.NOT_ENOUGH_PARAMS.getCode()));
      return;
    }

    JsonObject accessStatus = AccessCache.getAccessStatusWithToken(fileId, sessionId, token);
    if (accessStatus != null) {
      handler.handle(responseObject
          .put(Field.ACCESS.getName(), accessStatus.getString(Field.STATUS.getName()))
          .put(Field.STORAGE_TYPE.getName(), accessStatus.getString(Field.STORAGE_TYPE.getName())));
      return;
    }

    Entity subsegment = XRayManager.createSubSegment(operationGroup, segment, "checkToken");

    PublicLink publicLink = new PublicLink(fileId);
    try {
      AccessCacheConstants checkStatus = AccessCacheConstants.NO_ACCESS;
      if (publicLink.findLinkByToken(token)) {
        if (Utils.isStringNotNullOrEmpty(xSessionId)
            && publicLink.validateXSession(token, xSessionId)) {
          checkStatus = AccessCacheConstants.TOKEN;
        } else if (publicLink.isValid(token, password)) {
          checkStatus = AccessCacheConstants.TOKEN;
        }
      }
      storageType = publicLink.getStorageType();
      responseObject.put(Field.STORAGE_TYPE.getName(), storageType);
      XRayManager.endSegment(subsegment);
      AccessCache.saveAccessStatus(
          fileId, sessionId, token, checkStatus, StorageType.getStorageType(storageType));
      handler.handle(responseObject.put(Field.ACCESS.getName(), checkStatus.getTitle()));
    } catch (PublicLinkException ple) {
      XRayManager.endSegment(subsegment);
      AccessCache.saveAccessStatus(
          fileId,
          sessionId,
          token,
          AccessCacheConstants.NO_ACCESS,
          StorageType.getStorageType(storageType));
      handler.handle(responseObject
          .put(Field.ACCESS.getName(), AccessCacheConstants.NO_ACCESS.getTitle())
          .mergeIn(ple.toResponseObject()));
    }
  }

  private boolean isAnnotationAccessible(
      Entity segment, Message<JsonObject> message, Item annotation) {
    if (annotation == null
        || CommentState.DELETED.name().equals(annotation.getString(Field.STATE.getName()))) {
      sendError(segment, message, HttpStatus.NOT_FOUND, "FL7");
      return false;
    }
    if (CommentState.RESOLVED.name().equals(annotation.getString(Field.STATE.getName()))) {
      sendError(segment, message, HttpStatus.BAD_REQUEST, "FL10");
      return false;
    }
    return true;
  }

  // get array of mentioned users who don't have access to the file
  private Future<JsonArray> getInAccessibleMentionedUsers(
      StorageType storageType, List<String> users, JsonObject json) {
    JsonArray usersWithNoAccess = new JsonArray();
    Promise<JsonArray> handler = Promise.promise();
    if (Utils.isListNotNullOrEmpty(users)) {
      Entity infoSegment =
          XRayManager.createSubSegment(operationGroup, "getInAccessibleMentionedUsers");
      JsonObject infoJson = json.copy();
      eb_request(infoSegment, StorageType.getAddress(storageType) + ".getInfo", infoJson, info -> {
        if (info.succeeded()) {
          JsonObject result = (JsonObject) info.result().body();
          if (ResponseHandler.OK.equals(result.getString(Field.STATUS.getName()))) {
            String ownerEmail = result.getString(Field.OWNER_EMAIL.getName());
            JsonObject share = result.getJsonObject(Field.SHARE.getName());
            if (share == null) {
              return;
            }
            JsonArray collaborators = share
                .getJsonArray(Field.VIEWER.getName())
                .addAll(share.getJsonArray(Field.EDITOR.getName()));
            List<JsonObject> collaboratorsList = collaborators.getList();
            users.forEach(u -> {
              Item item = Users.getUserById(u);
              if (item == null) {
                return;
              }
              boolean hasAccess = collaboratorsList.stream()
                      .anyMatch(
                          c -> Utils.isStringNotNullOrEmpty(c.getString(Field.EMAIL.getName()))
                              && (c.getString(Field.EMAIL.getName())
                                  .equals(item.getString(Field.USER_EMAIL.getName()))))
                  || (Utils.isStringNotNullOrEmpty(ownerEmail)
                      && ownerEmail.equals(item.getString(Field.USER_EMAIL.getName())));
              if (!hasAccess) {
                usersWithNoAccess.add(Users.formatUserForMention(item));
              }
            });
          }
          handler.complete(usersWithNoAccess);
        } else {
          handler.fail(info.cause());
        }
      });
      XRayManager.endSegment(infoSegment);
    } else {
      handler.fail("NO DATA");
    }
    return handler.future();
  }

  private void sendUserMentionNotification(
      Entity parentSegment,
      JsonObject json,
      NotificationEvents.eventTypes event,
      StorageType storageType,
      List<String> users,
      String fileId,
      String userId) {
    if (!Utils.isListNotNullOrEmpty(users)) return;

    JsonObject id = Utils.parseItemId(fileId, Field.FILE_ID.getName());
    Item metadata =
        FileMetaData.getMetaData(id.getString(Field.FILE_ID.getName()), storageType.toString());
    JsonObject emailJson = new JsonObject();
    // to get fileName
    if (metadata != null) {
      emailJson
          .put(Field.FILE_NAME_C.getName(), metadata.getString(Field.FILE_NAME.getName()))
          .put(Field.THUMBNAIL.getName(), metadata.getString(Field.THUMBNAIL.getName()));
    } else {
      JsonObject infoJson = json.copy();
      eb_request(
          parentSegment, StorageType.getAddress(storageType) + ".getInfo", infoJson, info -> {
            Entity infoSegment = XRayManager.createStandaloneSegment(
                operationGroup, parentSegment, "sendUserMentionNotification.getInfo.result");
            if (info.succeeded()) {
              JsonObject result = (JsonObject) info.result().body();
              if (ResponseHandler.OK.equals(result.getString(Field.STATUS.getName()))) {
                emailJson
                    .put(Field.FILE_NAME_C.getName(), result.getString(Field.FILE_NAME.getName()))
                    .put(
                        Field.THUMBNAIL.getName(),
                        result.getString(Field.THUMBNAIL_NAME.getName()));
                log.info("[ METADATA ] Update on doGetInfo");
                FileMetaData.putMetaData(
                    id.getString(Field.FILE_ID.getName()),
                    storageType,
                    result.getString(Field.FILE_NAME.getName()),
                    result.getString(Field.THUMBNAIL_NAME.getName()),
                    result.getString(Field.VERSION_ID.getName()),
                    result.getString(Field.OWNER_ID.getName()));
              }
            }
            XRayManager.endSegment(infoSegment);
          });
    }
    emailJson
        .put(Field.USER_ID.getName(), userId)
        .put(Field.FILE_ID.getName(), fileId)
        .put(Field.COMMENT_TEXT.getName(), json.getString(Field.TEXT.getName()))
        .put(Field.ANNOTATION_TITLE.getName(), json.getString(Field.TITLE.getName()))
        .put(Field.TIMESTAMP_C.getName(), json.getLong(Field.TIMESTAMP_C.getName()))
        .put("eventLabel", event.name())
        .put(Field.STORAGE_TYPE.getName(), storageType.toString())
        .put(Field.EXTERNAL_ID.getName(), json.getString(Field.EXTERNAL_ID.getName()))
        .put(Field.THREAD_ID.getName(), json.getString(Field.THREAD_ID.getName()))
        .put(Field.COMMENT_ID.getName(), json.getString(Field.COMMENT_ID.getName()));
    // send notification to mentioned users
    users.forEach(u -> {
      if (u.equals(userId)) {
        // skip email notification to current user (self)
        return;
      }
      eb_send(
          parentSegment,
          MailUtil.address + ".userMentioned",
          emailJson.put(Field.TARGET_USER_ID.getName(), u));
    });
  }

  // to subscribe those mentioned users who have access to the file but not subscribed
  private void subscribeMentionedUsers(
      JsonArray usersWithNoAccess, List<String> users, String fileId) {
    if (!Utils.isListNotNullOrEmpty(users)) return;

    List<String> usersWithAccess = new ArrayList<>(users);
    JsonObject id = Utils.parseItemId(fileId, Field.FILE_ID.getName());
    List<Item> subscribedUsers =
        TypedIteratorUtils.toTypedList(SubscriptionsDao.getFileSubscriptions(fileId));
    // excluding users with no access
    usersWithAccess.removeAll(((List<JsonObject>) usersWithNoAccess.getList())
        .stream().map(u -> u.getString(Field.ID.getName())).collect(Collectors.toList()));
    // excluding users who have access and are subscribed
    usersWithAccess.removeAll(subscribedUsers.stream()
        .map(s -> s.getString(Field.SK.getName()))
        .collect(Collectors.toList()));

    usersWithAccess.forEach(u -> {
      Item subscription = new Item()
          .withPrimaryKey(
              Field.PK.getName(),
              "subscriptions#" + id.getString(Field.FILE_ID.getName()),
              Field.SK.getName(),
              u)
          .withLong(Field.TIMESTAMP_SHORT.getName(), GMTHelper.utcCurrentTime())
          .withString(Field.STATE.getName(), Subscriptions.subscriptionState.ACTIVE.name())
          .withList(
              "scope",
              new JsonArray()
                  .add(Subscriptions.subscriptionScope.GLOBAL.toString())
                  .getList());
      SubscriptionsDao.putSubscription(subscription);
    });
  }

  private void handleMentionsData(
      Entity segment,
      Message<JsonObject> message,
      JsonArray usersWithNoAccess,
      NotificationEvents.eventTypes event,
      List<String> users,
      String fileId,
      StorageType storageType,
      JsonObject mentionJson,
      String userId,
      String type,
      boolean addComment) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("handleMentionsData")
        .run((Segment blockingSegment) -> {
          if (!Utils.isStringNotNullOrEmpty(type) || type.equals("comments")) {
            subscribeMentionedUsers(usersWithNoAccess, users, fileId);
            Comments.saveMentionedUsers(storageType, fileId, users);
            sendUserMentionNotification(
                blockingSegment, mentionJson, event, storageType, users, fileId, userId);
            if (addComment) {
              Mentions.updateRating(Mentions.Activity.ADD_COMMENT, userId, fileId, storageType);
            }
          }

          if (Utils.isListNotNullOrEmpty(users)) {
            users.forEach(mentionedUserId -> {
              UserMentionLog userMentionLog = new UserMentionLog(
                  userId,
                  fileId,
                  storageType,
                  mentionJson.getString(Field.EMAIL.getName()),
                  mentionJson.getLong(Field.TIMESTAMP_C.getName()),
                  true,
                  mentionJson.getString(Field.DEVICE.getName()),
                  mentionJson.getString(Field.ORGANIZATION_ID.getName()),
                  mentionJson.getString(Field.THREAD_ID.getName()),
                  mentionJson.getString(Field.COMMENT_ID.getName()),
                  mentionedUserId,
                  type);
              userMentionLog.sendToServer();
            });
          }
        });
  }

  private void updateLastCommentTimestamp(
      Entity segment, Message<?> message, String fileId, String storageType, Long lastCommentedOn) {
    if (Objects.isNull(lastCommentedOn)) {
      return;
    }
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("updateLastCommentTimestamp")
        .runWithoutSegment(
            () -> FileMetaData.updateLastCommentTimestamp(fileId, storageType, lastCommentedOn));
  }

  /**
   * Share the file with non-accessible mentioned users with view-only rights
   *
   * @param segment       - Xray segment
   * @param message       - Vertx message
   * @param body          - data
   * @param noAccessUsers - array containing users with which file needs to be shared
   */
  private void shareFileWithMentionedUsers(
      Entity segment, Message<JsonObject> message, JsonObject body, JsonArray noAccessUsers) {
    if (noAccessUsers.isEmpty()) {
      return;
    }
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("shareFileWithMentionedUsers")
        .run((Segment blockingSegment) -> {
          String fileId = body.getString(Field.FILE_ID.getName());
          String storageType = body.getString(Field.STORAGE_TYPE.getName());
          JsonArray viewers = new JsonArray(noAccessUsers.stream()
              .map(user -> ((JsonObject) user).getString(Field.EMAIL.getName()))
              .collect(Collectors.toList()));
          JsonObject share = new JsonObject()
              .put(Field.EDITOR.getName(), new JsonArray())
              .put(Field.VIEWER.getName(), viewers);
          eb_request(
              blockingSegment,
              GridFSModule.address + ".shareFile",
              new JsonObject()
                  .put(Field.LOCALE.getName(), Utils.getLocaleFromMessage(message))
                  .put(Field.ID.getName(), fileId)
                  .put(Field.STORAGE_TYPE.getName(), storageType)
                  .put(Field.USER_ID.getName(), body.getString(Field.USER_ID.getName()))
                  .put(Field.SHARE.getName(), share)
                  .put(Field.EXTERNAL_ID.getName(), body.getString(Field.EXTERNAL_ID.getName()))
                  .put(Field.USERNAME.getName(), body.getString(Field.USERNAME.getName()))
                  .put(Field.DEVICE.getName(), body.getString(Field.DEVICE.getName()))
                  .put(Field.SESSION_ID.getName(), body.getString(Field.SESSION_ID.getName())),
              event -> {
                if (event.failed()
                    || Objects.isNull(event.result())
                    || !ResponseHandler.OK.equals(
                        ((JsonObject) event.result().body()).getString(Field.STATUS.getName()))) {
                  log.error("[ MENTION ] Error occurred while sharing the file " + fileId
                      + " | storageType " + storageType + " : " + event.cause());
                }
              });
        });
  }

  private enum AccessErrorCodes {
    NOT_ENOUGH_PARAMS(0),
    FILE_STATUS_EXCEPTION(1),
    UNABLE_TO_GET_TRASH_STATUS(2),
    EXCEPTION_DURING_GET_TRASH_STATUS(3),
    FILE_IS_NOT_ACCESSIBLE(4),
    COMMENTING_IS_NOT_ACCESSIBLE(5);

    private final int code;

    AccessErrorCodes(int code) {
      this.code = code;
    }

    public int getCode() {
      return this.code;
    }
  }

  public enum CommentState {
    ACTIVE,
    DELETED,
    RESOLVED;

    public static CommentState getState(String value) {
      for (CommentState ct : CommentState.values()) {
        if (ct.name().equals(value.toUpperCase())) {
          return ct;
        }
      }
      return null;
    }
  }

  public enum MarkupType {
    STAMP,
    ENTITY,
    PICTURENOTE,
    VOICENOTE;

    public static MarkupType getType(String value) {
      for (MarkupType ct : MarkupType.values()) {
        if (ct.name().equals(value.toUpperCase())) {
          return ct;
        }
      }
      return STAMP;
    }
  }
}
