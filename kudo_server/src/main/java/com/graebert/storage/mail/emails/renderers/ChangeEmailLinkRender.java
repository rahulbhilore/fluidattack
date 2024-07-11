package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.util.Utils;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.function.Function;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;

public class ChangeEmailLinkRender {
  String changeLink;
  String locale;

  public ChangeEmailLinkRender(
      String instanceURL, String locale, String userId, String newEmail, String secretKey)
      throws BadPaddingException, UnsupportedEncodingException, IllegalBlockSizeException,
          NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException {
    String url = UIPaths.getEmailChangeURL(instanceURL, userId, newEmail, secretKey);
    changeLink = "<a href='" + url + "' target='_blank'>" + url + "</a>";
    this.locale = locale;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return MessageFormat.format(
          Utils.getLocalizedString(locale, "FollowTheLinkToComplete"), changeLink);
    };
  }
}
