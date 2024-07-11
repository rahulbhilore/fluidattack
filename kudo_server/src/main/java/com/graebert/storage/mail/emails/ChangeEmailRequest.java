package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.ChangeEmailLinkRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class ChangeEmailRequest {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.EMAILCHANGEREQUEST;

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      String userId,
      String newEmail,
      String secretKey) {
    try {
      Item user = null;
      if (Utils.isEmail(userId)) {
        user = Users.getUserByEmail(userId).next();
      } else {
        user = Users.getUserById(userId);
      }
      if (user != null) {
        String greetingName = MailUtil.getDisplayName(user);
        HashMap<String, Object> scopes = new HashMap<String, Object>();
        scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
        scopes.put(
            "HiFirstName",
            MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
        scopes.put(
            "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
        String title = MessageFormat.format(
            Utils.getLocalizedString(locale, "ChangeEmailForProduct"), proName);
        scopes.put("Title", title);
        scopes.put(
            "content", new ChangeEmailLinkRender(instanceURL, locale, userId, newEmail, secretKey));
        scopes.put(
            "SomeoneAskedToChangeEmail",
            Utils.getLocalizedString(locale, "SomeoneAskedToChangeEmail"));
        scopes.put(
            "IfYouDidntRequestEmailChangeIgnore",
            Utils.getLocalizedString(locale, "IfYouDidntRequestEmailChangeIgnore"));
        scopes.putAll(MailUtil.getColors(proName));
        String from = String.format(MailUtil.fromTeamFormat, MailUtil.sender);
        String executed = MailUtil.executeMustache(EMAIL_TEMPLATE_TYPE, proName, scopes);
        return new JsonObject()
            .put("from", from)
            .put("to", newEmail)
            .put("subject", title)
            .put("html", executed);
      }
    } catch (Exception e) {
      DynamoBusModBase.log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
    }
    return null;
  }
}
