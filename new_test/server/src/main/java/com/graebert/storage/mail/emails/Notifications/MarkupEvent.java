package com.graebert.storage.mail.emails.Notifications;

import com.graebert.storage.comment.CommentVerticle.MarkupType;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ResourceBundle;
import java.util.function.Function;

public class MarkupEvent extends Event {
  private final MarkupType markupType;

  public MarkupEvent(
      NotificationEvents.eventTypes eventType,
      String timeString,
      String userName,
      ResourceBundle messages,
      MarkupType markupType,
      JsonObject markupInfo) {
    super(eventType, timeString, userName, markupInfo, messages);
    this.markupType = markupType;
    this.label = "";
    switch (this.eventType) {
      case NEWMARKUP:
        switch (this.markupType) {
          case PICTURENOTE:
            this.label = messages.getString("NewPictureRecording");
            break;
          case STAMP:
            this.label = messages.getString("NewStamp");
            break;
          case VOICENOTE:
            this.label = messages.getString("NewVoiceRecording");
            break;
          default:
            this.label = messages.getString("NewMarkup");
            break;
        }
        break;
      case MODIFIEDMARKUP:
        switch (this.markupType) {
          case PICTURENOTE:
            this.label = messages.getString("ModifiedPictureRecording");
            break;
          case STAMP:
            this.label = messages.getString("ModifiedStamp");
            break;
          case VOICENOTE:
            this.label = messages.getString("ModifiedVoiceRecording");
            break;
          default:
            this.label = messages.getString("ModifiedMarkup");
            break;
        }
        break;
      case MARKUPRESOLVED:
        switch (this.markupType) {
          case PICTURENOTE:
            this.label = messages.getString("PictureRecordingResolved");
            break;
          case STAMP:
            this.label = messages.getString("StampResolved");
            break;
          case VOICENOTE:
            this.label = messages.getString("VoiceRecordingResolved");
            break;
          default:
            this.label = messages.getString("MarkupResolved");
            break;
        }
        break;
      case MARKUPACTIVATED:
        switch (this.markupType) {
          case PICTURENOTE:
            this.label = messages.getString("PictureRecordingReopen");
            break;
          case STAMP:
            this.label = messages.getString("StampReopen");
            break;
          case VOICENOTE:
            this.label = messages.getString("VoiceRecordingReopen");
            break;
          default:
            this.label = messages.getString("MarkupReopen");
            break;
        }
        break;
      case MARKUPDELETED:
        switch (this.markupType) {
          case PICTURENOTE:
            this.label = messages.getString("DeletedPictureRecording");
            break;
          case STAMP:
            this.label = messages.getString("DeletedStamp");
            break;
          case VOICENOTE:
            this.label = messages.getString("DeletedVoiceRecording");
            break;
          default:
            this.label = messages.getString("DeletedMarkup");
            break;
        }
        break;
    }
    this.label = this.label.toUpperCase();
    if (this.label.length() > 20) {
      this.width = 200;
    }
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      switch (this.eventType) {
        case NEWMARKUP:
          return markupType;
        case MARKUPACTIVATED:
        case MARKUPRESOLVED:
          switch (this.markupType) {
            case VOICENOTE:
            case PICTURENOTE:
              return null;
            case STAMP:
              return additionalData.getString("text");
            default:
              return this.eventType.equals(NotificationEvents.eventTypes.MARKUPRESOLVED)
                  ? "resolved"
                  : "reopen";
          }
        case MARKUPDELETED:
          switch (this.markupType) {
            case PICTURENOTE:
            case VOICENOTE:
              return null;
            case STAMP:
              return String.format(
                  "<del style='background-color: #FFB0B0;'>%s</del>",
                  additionalData.getString("text"));
            default:
              return Field.DELETED.getName();
          }
        case MODIFIEDMARKUP:
        default:
          return eventType + " <br/> " + additionalData.getString(Field.DESCRIPTION.getName());
      }
    };
  }

  /**
   * Decorator for email template
   * For images we need a separate handling due to Outlook issues
   */
  public Function<Object, Object> imageRender() {
    return (obj) -> {
      if (this.markupType.equals(MarkupType.PICTURENOTE)) {
        switch (this.eventType) {
          case NEWMARKUP:
            return markupType;
          case MARKUPACTIVATED:
          case MARKUPRESOLVED: {
            String notes = "";
            for (Object noteData : additionalData.getJsonArray("pictureNotesData")) {
              JsonArray noteArray = (JsonArray) noteData;
              notes += String.format(
                  "<a href='%s'><img src='%s' width='150' height='150' class='mso-img' style='max-width:150px; margin-right:5px; display:inline-block;'/></a><br></br><span style='font-size:12px;'>%s</span><br></br>",
                  noteArray.getString(0), noteArray.getString(0), noteArray.getString(1));
            }
            return notes;
          }
          case MARKUPDELETED: {
            String notes = "";
            for (Object noteData : additionalData.getJsonArray("pictureNotesData")) {
              JsonArray noteArray = (JsonArray) noteData;
              notes += String.format(
                  "<a href='%s'><img src='%s' class='mso-img' width='150' height='150' style='max-width:150px; margin-right:5px; display:inline-block;'/></a><br></br><span style='font-size:12px;'>%s</span><br></br>",
                  noteArray.getString(0), noteArray.getString(0), noteArray.getString(1));
            }
            return String.format(
                "<del style='background-color:transparent !important;opacity: 0.3;'>%s</del>",
                notes);
          }
          case MODIFIEDMARKUP:
          default:
            return eventType + " <br/> " + additionalData.getString(Field.DESCRIPTION.getName());
        }
      } else {
        return null;
      }
    };
  }
}
