package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.util.Utils;
import java.text.MessageFormat;
import java.util.function.Function;

public class NotifyAdminRender {
  String registrationLink;
  String usersPageLink;
  String locale;

  public NotifyAdminRender(String instanceURL, String locale, String userId) {
    String activationURL = UIPaths.getActivationURL(instanceURL, userId);
    String usersPageURL = UIPaths.getUsersPageURL(instanceURL);
    registrationLink = "<a href='" + activationURL + "' target='_blank'>" + activationURL + "</a>";
    usersPageLink = "<a href='" + usersPageURL + "' target='_blank'>" + usersPageURL + "</a>";
    this.locale = locale;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return MessageFormat.format(
          Utils.getLocalizedString(locale, "ToActivateFollowLink"),
          registrationLink,
          usersPageLink);
    };
  }
}
