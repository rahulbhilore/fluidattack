package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.Notifications.Event;
import com.graebert.storage.mail.emails.renderers.MentionRender;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class UserMentionEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.USERMENTIONED;
  protected static Logger log = LogManager.getRootLogger();

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      String objectId,
      Event mentionEvent,
      S3Regional s3Regional,
      String cloudFrontDistribution) {
    try {
      JsonObject additionalData = mentionEvent.getAdditionalData();
      String commenterName = mentionEvent.getUserName();
      String commenterEmail = additionalData.getString(Field.COMMENTER_EMAIL.getName());
      String targetUserId = additionalData.getString(Field.TARGET_USER_ID.getName());
      String externalId = additionalData.getString(Field.EXTERNAL_ID.getName());
      String fileName = additionalData.getString(Field.FILE_NAME_C.getName());
      String fileId = additionalData.getString(Field.FILE_ID.getName());
      String thumbnail = additionalData.getString(Field.THUMBNAIL.getName());
      String annotationTitle = additionalData.getString(Field.ANNOTATION_TITLE.getName());
      String commentText = additionalData.getString(Field.COMMENT_TEXT.getName());
      String threadId = additionalData.getString(Field.THREAD_ID.getName());
      String commentId = additionalData.getString(Field.COMMENT_ID.getName());
      StorageType storageType =
          StorageType.getStorageType(additionalData.getString(Field.STORAGE_TYPE.getName()));
      Item user = Users.getUserById(targetUserId);
      String email = user.getString(Field.USER_EMAIL.getName());
      String greetingName = user.getString(Field.F_NAME.getName()) == null
          ? Dataplane.emptyString
          : user.getString(Field.F_NAME.getName());
      String accountId = storageType.equals(StorageType.SAMPLES)
          ? SimpleStorage.getAccountId(user.getString(Field.SK.getName()))
          : externalId;
      String fileLink = UIPaths.getFileUrl(
              instanceURL, Utils.getEncapsulatedId(storageType, accountId, objectId))
          .replace("file/", "open/");
      String fileCommentLink = fileLink
          + ((Objects.nonNull(threadId)) ? "?tId=" + threadId : Dataplane.emptyString)
          + ((Objects.nonNull(commentId)) ? "&cId=" + commentId : Dataplane.emptyString);
      String commenter = commenterName
          + (Objects.nonNull(commenterEmail)
              ? " (" + commenterEmail + ") "
              : Dataplane.emptyString);
      String mentionedText =
          MessageFormat.format(Utils.getLocalizedString(locale, "UserHasMentionedYou"), commenter);
      String mentionedTitle = MessageFormat.format(
          Utils.getLocalizedString(locale, "UserHasMentionedYouFor"), commenterName, fileName);
      String emailThumbnailName = "kudo_email_thumbnail_placeholder.png";
      if (Objects.nonNull(thumbnail)) {
        byte[] thumbnailData = s3Regional.getFromBucket(cloudFrontDistribution, "png/" + thumbnail);
        if (Objects.nonNull(thumbnailData) && thumbnailData.length > 0) {
          // thumbnail string has ".png" extension in it. Let's modify filename
          String nameWithoutExtension = thumbnail.substring(0, thumbnail.lastIndexOf("."));
          String fileExtension = thumbnail.substring(thumbnail.lastIndexOf("."));
          emailThumbnailName = nameWithoutExtension + GMTHelper.utcCurrentTime() + fileExtension;
          s3Regional.putPublic(
              cloudFrontDistribution, "emails/" + emailThumbnailName, thumbnailData);
        }
      }
      thumbnail = instanceURL + "api/img/email/" + emailThumbnailName + "?email="
          + user.getString(Field.USER_EMAIL.getName()) + "&st=" + storageType + "&fid=" + fileId;
      log.info("MentionEmail : Thumbnail url - " + thumbnail);
      HashMap<String, Object> scopes = new HashMap<>();
      scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
      scopes.put(
          "HiFirstName",
          MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
      scopes.put(
          "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
      scopes.put("UserMentioned", mentionedText);
      scopes.put("Title", mentionedTitle);
      scopes.put("Filename", fileName);
      scopes.put("userShortName", mentionEvent.getUserShortName());
      scopes.put("userName", commenterName);
      scopes.put("label", mentionEvent.getLabel());
      scopes.put("labelColor", mentionEvent.getLabelColor());
      scopes.put("timeString", mentionEvent.getTimeString());
      scopes.put("FileLink", fileLink);
      scopes.put("FileCommentLink", fileCommentLink);
      scopes.put("FileThumbnail", thumbnail);
      scopes.put("ThreadTitle", annotationTitle);
      scopes.put("OpenCommentInDrawing", Utils.getLocalizedString(locale, "OpenCommentInDrawing"));
      scopes.put("content", new MentionRender(commentText, targetUserId));
      scopes.putAll(MailUtil.getColors(proName));
      String from = String.format(MailUtil.fromTeamFormat, MailUtil.sender);
      String executed = MailUtil.executeMustache(EMAIL_TEMPLATE_TYPE, proName, scopes);
      return new JsonObject()
          .put("from", from)
          .put("to", email)
          .put("subject", mentionedTitle)
          .put("html", executed);
    } catch (Exception e) {
      DynamoBusModBase.log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
    }
    return null;
  }
}
