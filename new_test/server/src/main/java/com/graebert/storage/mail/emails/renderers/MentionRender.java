package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.storage.Comments;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;

public class MentionRender {
  String mentionedComment;

  public MentionRender(String commentText, String targetUserId) {
    this.mentionedComment = createMentionTextForEmail(commentText, targetUserId, true);
  }

  public static String createMentionTextForEmail(
      String text, String targetUserId, boolean useStyle) {
    String finalText = null;
    if (Utils.isStringNotNullOrEmpty(text)) {
      finalText = text;
      Matcher m = Comments.textMentionPattern.matcher(finalText);
      while (m.find()) {
        int index = finalText.indexOf(m.group());
        String beforeText = finalText.substring(0, index - 1);
        String afterText = finalText.substring(beforeText.length() + m.group().length() + 2);
        String[] values = m.group().split("\\|");
        if (values.length == 2) {
          String userId = values[1].trim();
          String name = Users.getUserInfo(userId).getString(Field.NAME.getName());
          String textStyle = (Objects.nonNull(targetUserId) && targetUserId.equals(userId))
              ? "\"font-weight:bold;color:blue;\""
              : "\"color:blue;\"";
          String mentionedUser;
          if (useStyle) {
            mentionedUser = "<span style=" + textStyle + ">@" + name + "</span>";
          } else {
            mentionedUser = "@" + name;
          }
          finalText = beforeText + mentionedUser + afterText;
        }
      }
    }
    return finalText;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> mentionedComment;
  }
}
