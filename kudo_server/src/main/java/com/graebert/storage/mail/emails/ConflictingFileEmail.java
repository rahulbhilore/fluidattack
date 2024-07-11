package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.ConflictRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class ConflictingFileEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.CONFLICTINGFILE;

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      String objectId,
      String objectName,
      String newObjectId,
      String newObjectName,
      String editor,
      String owner,
      String conflictReason,
      boolean isSameFolder,
      String conflictingFileHelpUrl) {
    try {
      Item user;
      if (Utils.isEmail(owner)) {
        user = Users.getUserByEmail(owner).next();
      } else {
        user = Users.getUserById(owner);
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
            Utils.getLocalizedString(locale, "Conflictingversionsofyourdrawingaredetected");
        scopes.put("Title", title);
        scopes.put(
            "content",
            new ConflictRender(
                instanceURL,
                locale,
                objectId,
                objectName,
                newObjectId,
                newObjectName,
                editor,
                conflictReason,
                isSameFolder,
                conflictingFileHelpUrl));
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
