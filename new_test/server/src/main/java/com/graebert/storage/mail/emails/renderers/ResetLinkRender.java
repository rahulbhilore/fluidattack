package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.util.Utils;
import java.text.MessageFormat;
import java.util.function.Function;

public class ResetLinkRender {
  String resetLink;
  String locale;

  public ResetLinkRender(String instanceURL, String locale, String userId, String hash) {
    String url = UIPaths.getResetPasswordURL(instanceURL, userId, hash);
    resetLink = "<a href='" + url + "' target='_blank'>" + url + "</a>";
    this.locale = locale;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return MessageFormat.format(Utils.getLocalizedString(locale, "FollowLinkToReset"), resetLink);
    };
  }
}
