package com.graebert.storage.mail.emails;

import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class AccountDeletedEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.ACCOUNTDELETED;

  public static JsonObject getEmailData(
      String proName, String locale, String greetingName, String email) {
    try {
      HashMap<String, Object> scopes = new HashMap<String, Object>();
      scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
      scopes.put(
          "HiFirstName",
          MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
      scopes.put(
          "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
      String title =
          MessageFormat.format(Utils.getLocalizedString(locale, "AccessToAresKudo"), proName);
      scopes.put(
          "YourAccountDeleted",
          Utils.getLocalizedString(
              locale, "YourAccountHasBeenDeleted" + Utils.getStringPostfix(proName)));
      scopes.put("Title", title);
      scopes.putAll(MailUtil.getColors(proName));
      String from = String.format(MailUtil.fromTeamFormat, MailUtil.sender);
      String executed = MailUtil.executeMustache(EMAIL_TEMPLATE_TYPE, proName, scopes);
      return new JsonObject()
          .put("from", from)
          .put("to", email)
          .put("subject", title)
          .put("html", executed);
    } catch (Exception e) {
      DynamoBusModBase.log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
    }
    return null;
  }
}
