package com.graebert.storage.mail.emails.Notifications;

import com.github.difflib.text.DiffRow;
import com.github.difflib.text.DiffRowGenerator;
import com.graebert.storage.subscriptions.NotificationEvents;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Function;

public class Event {
  NotificationEvents.eventTypes eventType;
  String timeString;
  String userName;
  String userShortName;
  String label;
  String labelColor;
  JsonObject additionalData;
  Integer width;

  public Event(
      NotificationEvents.eventTypes eventType,
      String timeString,
      String userName,
      JsonObject additionalData,
      ResourceBundle messages) {
    this.eventType = eventType;
    this.timeString = timeString;
    this.userName = userName;
    this.additionalData = additionalData;
    this.width = 120;
    this.userShortName = Utils.getUserShortName(userName);

    switch (eventType) {
      case NEWCOMMENT:
        this.label = messages.getString("NewComment");
        this.labelColor = "#11893C";
        break;
      case MODIFIEDCOMMENT:
        this.label = messages.getString("Edited");
        this.labelColor = "#4A71E2";
        break;
      case DELETEDCOMMENT:
        this.label = messages.getString("Deleted");
        this.labelColor = "#B82115";
        break;
      case NEWTHREAD:
        this.label = messages.getString("NewThread");
        this.labelColor = "#11893C";
        break;
      case THREADRESOLVED:
        this.label = messages.getString("ThreadResolved");
        this.labelColor = "#11893C";
        break;
      case THREADREOPENED:
        this.label = messages.getString("ThreadReopened");
        this.labelColor = "#11893C";
        break;
      case THREADTITLEMODIFIED:
        this.label = messages.getString("ThreadTitleModified");
        this.labelColor = "#4A71E2";
        break;
      case THREADMODIFIED:
        this.label = messages.getString("ThreadModified");
        this.labelColor = "#4A71E2";
        break;
      case ENTITIESCHANGED:
        this.label = messages.getString("AssociatedEntitiesModified");
        this.labelColor = "#4A71E2";
        break;
      case THREADDELETED:
        this.label = messages.getString("ThreadDeleted");
        this.labelColor = "#B82115";
        break;
      case NEWMARKUP:
      case MARKUPACTIVATED:
        this.label = messages.getString("NewMarkup");
        this.labelColor = "#11893C";
        break;
      case MODIFIEDMARKUP:
        this.label = messages.getString("MarkupModified");
        this.labelColor = "#4A71E2";
        break;
      case MARKUPRESOLVED:
        this.label = messages.getString("MarkupResolved");
        this.labelColor = "#4A71E2";
        break;
      case MARKUPDELETED:
        this.label = messages.getString("MarkupDeleted");
        this.labelColor = "#B82115";
        break;
      case MODIFIEDFILE:
        this.label = messages.getString("FileModified");
        this.labelColor = "#11893C";
        break;
      default:
        this.label = this.eventType.toString();
        this.labelColor = "#4A71E2";
        break;
    }
    this.label = this.label.toUpperCase();
    if (this.label.length() > 20) {
      this.width = 200;
    }
  }

  @Override
  public String toString() {
    return userName + " @ " + this.timeString + " : " + this.eventType
        + this.additionalData.encode();
  }

  public String getTimeString() {
    return timeString;
  }

  public String getUserName() {
    return userName;
  }

  public String getUserShortName() {
    return userShortName;
  }

  public String getLabel() {
    return label;
  }

  public String getLabelColor() {
    return labelColor;
  }

  public JsonObject getAdditionalData() {
    return additionalData;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      // if possible - let's get the diff.
      // if not - we'll just show the entity as modified
      DiffRowGenerator generator = DiffRowGenerator.create()
          .showInlineDiffs(true)
          .mergeOriginalRevised(true)
          .inlineDiffByWord(true)
          .oldTag(f -> f ? "<del style='background-color: #FFB0B0;'>" : "</del>")
          .newTag(f -> f ? "<ins>" : "</ins>")
          .build();
      switch (this.eventType) {
        case NEWCOMMENT:
          return additionalData.getString("text");
        case MODIFIEDCOMMENT:
        case THREADMODIFIED:
          try {
            List<DiffRow> rows = generator.generateDiffRows(
                Arrays.asList(additionalData.getString("oldText")),
                Arrays.asList(additionalData.getString("text")));
            return rows.get(0).getOldLine();
          } catch (Exception e) {
            e.printStackTrace();
          }

          return String.format(
              "<del style='background-color: #FFB0B0;'>%s</del><ins>%s</ins>",
              additionalData.getString("oldText"), additionalData.getString("text"));
        case DELETEDCOMMENT:
          return String.format(
              "<del style='background-color: #FFB0B0;'>%s</del>", additionalData.getString("text"));
        case THREADTITLEMODIFIED:
          try {
            List<DiffRow> rows = generator.generateDiffRows(
                Arrays.asList(additionalData.getString("oldTitle")),
                Arrays.asList(additionalData.getString("title")));
            return rows.get(0).getOldLine();
          } catch (Exception e) {
            e.printStackTrace();
          }

          return String.format(
              "<del style='background-color: #FFB0B0;'>%s</del><ins>%s</ins>",
              additionalData.getString("oldTitle"), additionalData.getString("title"));
        case THREADDELETED:
        case MARKUPDELETED:
        case MARKUPRESOLVED:
        case MARKUPACTIVATED:
        case NEWMARKUP:
        case NEWTHREAD:
        case MODIFIEDMARKUP:
        case MODIFIEDFILE:
        case THREADRESOLVED:
        case THREADREOPENED:
        case ENTITIESCHANGED:
          return "";
        default:
          return eventType + " <br/> " + additionalData.getString(Field.DESCRIPTION.getName());
      }
    };
  }

  public Function<Object, Object> imageRender() {
    return (obj) -> {
      return null;
    };
  }
}
