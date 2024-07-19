package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.NotifyAdminRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class NotifyAdminRegistration {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE =
      EmailTemplateType.NOTIFYADMINREGISTRATION;

  public static JsonObject getEmailData(
      String instanceURL, String proName, String locale, Item admin, String userId) {
    try {
      Item user = null;
      if (Utils.isEmail(userId)) {
        user = Users.getUserByEmail(userId).next();
      } else {
        user = Users.getUserById(userId);
      }
      if (admin != null && user != null) {
        String greetingName = MailUtil.getDisplayName(admin);
        String email = admin.getString(Field.USER_EMAIL.getName());
        HashMap<String, Object> scopes = new HashMap<String, Object>();
        scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
        scopes.put(
            "HiFirstName",
            MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
        scopes.put(
            "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
        String title = Utils.getLocalizedString(locale, "NewAccountCreated");
        scopes.put("Title", title);
        scopes.put("NewAccountCreated", Utils.getLocalizedString(locale, "NewAccountCreated"));
        scopes.put(
            "Username",
            MessageFormat.format(
                Utils.getLocalizedString(locale, "Username"),
                user.getString(Field.USER_EMAIL.getName())));
        scopes.put(
            "UserId", MessageFormat.format(Utils.getLocalizedString(locale, "UserId"), userId));
        scopes.put("content", new NotifyAdminRender(instanceURL, locale, userId));
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
