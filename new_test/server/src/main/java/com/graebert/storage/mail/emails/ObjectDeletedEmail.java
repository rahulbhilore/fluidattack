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

public class ObjectDeletedEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.OBJECTDELETED;

  public static JsonObject getEmailData(
      String proName,
      String locale,
      String userId,
      // as far as done in loop - just pass owner's name directly
      String ownerName,
      ObjectType objectType,
      String objectName) {
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
        String title;
        String objectDeleted;
        if (objectType.equals(ObjectType.FILE)) {
          title = MessageFormat.format(
              Utils.getLocalizedString(locale, "FileHasBeenDeletedByOwner"), objectName);
          objectDeleted = MessageFormat.format(
              Utils.getLocalizedString(locale, "TheDocumentWasDeletedByOwner"),
              objectName,
              ownerName);
        } else {
          title = MessageFormat.format(
              Utils.getLocalizedString(locale, "FolderHasBeenDeletedByOwner"), objectName);
          objectDeleted = MessageFormat.format(
              Utils.getLocalizedString(locale, "TheFolderWasDeletedByOwner"),
              objectName,
              ownerName);
          scopes.put(
              "folderDeleteNote",
              Utils.getLocalizedString(locale, "ItemsAreAccessibleWhenOwnerRestoreOrErase"));
        }
        scopes.put("objectDeleted", objectDeleted);
        scopes.put("Title", title);
        scopes.putAll(MailUtil.getColors(proName));
        String from =
            String.format(MailUtil.fromFormat, MailUtil.encodeName(ownerName), MailUtil.sender);
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
