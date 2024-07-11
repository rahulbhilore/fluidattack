package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class RequestFileAccessEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.CONFIRMREGISTRATION;

  public static JsonObject getEmailData(
      String proName,
      String locale,
      String ownerId,
      String collaboratorId,
      String fileId,
      String fileName) {
    try {
      Item owner;
      if (Utils.isEmail(ownerId)) {
        owner = Users.getUserByEmail(ownerId).next();
      } else {
        owner = Users.getUserById(ownerId);
      }
      Item collaborator;
      if (Utils.isEmail(collaboratorId)) {
        collaborator = Users.getUserByEmail(collaboratorId).next();
      } else {
        collaborator = Users.getUserById(collaboratorId);
      }
      if (collaborator != null) {
        String greetingName = MailUtil.getDisplayName(owner);
        String email = owner.getString(Field.USER_EMAIL.getName());
        HashMap<String, Object> scopes = new HashMap<String, Object>();
        scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
        scopes.put(
            "HiFirstName",
            MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
        scopes.put(
            "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
        String title = MessageFormat.format(
            Utils.getLocalizedString(locale, "UserRequestsAccessToYourFile"),
            MailUtil.getDisplayName(collaborator));
        scopes.put("Title", title);
        // TODO: Make proper links. For now there just aren't any UI links
        scopes.put(
            "UserRequestsAccessToFile",
            MessageFormat.format(
                Utils.getLocalizedString(locale, "UserRequestsAccessToFile"),
                greetingName,
                fileName));
        scopes.put(
            "ToProvideEditAccess",
            MessageFormat.format(Utils.getLocalizedString(locale, "ToProvideEditAccess"), fileId));
        scopes.put(
            "ToProvideViewAccess",
            MessageFormat.format(Utils.getLocalizedString(locale, "ToProvideViewAccess"), fileId));
        scopes.put(
            "IgnoreEmailToNotProvideAccess",
            Utils.getLocalizedString(locale, "IgnoreEmailToNotProvideAccess"));
        scopes.putAll(MailUtil.getColors(proName));
        String from = String.format(
            MailUtil.fromFormat,
            MailUtil.encodeName(MailUtil.getDisplayName(collaborator)),
            MailUtil.sender);
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
