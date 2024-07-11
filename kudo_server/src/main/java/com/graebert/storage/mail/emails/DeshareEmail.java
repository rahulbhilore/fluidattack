package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class DeshareEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.DESHARE;

  public static JsonObject getEmailData(
      String proName,
      String locale,
      String ownerName,
      String collaborator,
      ObjectType objectType,
      String objectName) {
    try {
      Item user = null;
      if (Utils.isEmail(collaborator)) {
        user = Users.getUserByEmail(collaborator).next();
      } else {
        user = Users.getUserById(collaborator);
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
        String sharingText;
        String title;
        if (objectType.equals(ObjectType.FILE)) {
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "UserCancelledSharingDocumentWithYou" + Utils.getStringPostfix(proName)),
              ownerName);
          sharingText = MessageFormat.format(
              Utils.getLocalizedString(locale, "SharingOfDocumentHasBeenCancelledBy"),
              objectName,
              ownerName);
        } else {
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "UserCancelledSharingFolderWithYou" + Utils.getStringPostfix(proName)),
              ownerName);
          sharingText = MessageFormat.format(
              Utils.getLocalizedString(locale, "SharingOfFolderHasBeenCancelledBy"),
              objectName,
              ownerName);
        }
        scopes.put("SharingOfObjectHasBeenCancelledBy", sharingText);
        scopes.put("Title", title);
        scopes.putAll(MailUtil.getColors(proName));
        ownerName = MailUtil.encodeName(ownerName);
        String from = String.format(MailUtil.fromFormat, ownerName, MailUtil.sender);
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
