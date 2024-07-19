package com.graebert.storage.mail.emails.Notifications;

import com.graebert.storage.comment.CommentVerticle.MarkupType;
import java.util.List;
import java.util.function.Function;

public class MarkupMeta extends ThreadMeta {
  private final MarkupType markupType;
  private List<List<String>> pictureNotesData;

  public MarkupMeta(
      String title, String timeString, String userName, String text, MarkupType markupType) {
    super(title, timeString, userName, text);
    this.title = "";
    this.markupType = markupType;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      switch (this.markupType) {
        default:
        case STAMP:
        case VOICENOTE:
        case PICTURENOTE:
          return this.text;
      }
    };
  }

  public Function<Object, Object> imageRender() {
    return (obj) -> {
      if (this.markupType.equals(MarkupType.PICTURENOTE)) {
        String notes = "";
        for (List<String> noteData : this.pictureNotesData) {
          notes += String.format(
              "<a href='%s'><img src='%s' class='mso-img' width='150' height='150' style='max-width:150px; margin-right:5px; display:inline-block;'/></a><br></br><span style='font-size:12px;'>%s</span><br></br>",
              noteData.get(0), noteData.get(0), noteData.get(1));
        }
        return notes;
      }
      return null;
    };
  }

  public void setPictureNotesData(List<List<String>> pictureNotesData) {
    this.pictureNotesData = pictureNotesData;
  }
}
