package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.ConfirmRegistrationRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class ConfirmRegistrationEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.CONFIRMREGISTRATION;

  public static JsonObject getEmailData(
      String instanceURL, String proName, String locale, String userId, String hash) {
    try {
      Item user = null;
      if (Utils.isEmail(userId)) {
        user = Users.getUserByEmail(userId).next();
      } else {
        user = Users.getUserById(userId);
      }
      if (user != null) {
        String greetingName = MailUtil.getDisplayName(user);
        String email = user.getString(Field.USER_EMAIL.getName());
        HashMap<String, Object> scopes = new HashMap<String, Object>();
        scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
        scopes.put(
            "HiFirstName",
            MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
        scopes.put(
            "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
        String title =
            Utils.getLocalizedString(locale, "Registration" + Utils.getStringPostfix(proName));
        scopes.put("Title", title);
        scopes.put("content", new ConfirmRegistrationRender(instanceURL, locale, userId, hash));
        scopes.put(
            "ThankYouForCreatingAccount",
            Utils.getLocalizedString(
                locale, "ThankYouForCreatingAccount" + Utils.getStringPostfix(proName)));
        scopes.putAll(MailUtil.getColors(proName));
        String from = String.format(MailUtil.fromTeamFormat, MailUtil.sender);
        String executed = MailUtil.executeMustache(EMAIL_TEMPLATE_TYPE, proName, scopes);
        return new JsonObject()
            .put("from", from)
            .put("to", email)
            .put("subject", title)
            .put("html", executed);
      }
    } catch (Exception e) {
      DynamoBusModBase.log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
    }
    return null;
  }
}
