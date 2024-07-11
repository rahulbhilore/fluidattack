package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.OwnerRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class OwnershipChangedEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.OWNERSHIPCHANGED;

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      StorageType storageType,
      ObjectType objectType,
      String objectId,
      String objectName,
      String oldOwnerId,
      String newOwnerId) {
    try {
      Item oldOwner = null;
      if (Utils.isEmail(oldOwnerId)) {
        oldOwner = Users.getUserByEmail(oldOwnerId).next();
      } else {
        oldOwner = Users.getUserById(oldOwnerId);
      }
      Item newOwner = null;
      if (Utils.isEmail(newOwnerId)) {
        newOwner = Users.getUserByEmail(newOwnerId).next();
      } else {
        newOwner = Users.getUserById(newOwnerId);
      }
      if (oldOwner != null && newOwner != null) {
        String greetingName = MailUtil.getDisplayName(newOwner);
        String email = newOwner.getString(Field.USER_EMAIL.getName());
        HashMap<String, Object> scopes = new HashMap<String, Object>();
        scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
        scopes.put(
            "HiFirstName",
            MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), greetingName));
        scopes.put(
            "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
        String fileLink;
        String title;
        String accountId = storageType.equals(StorageType.SAMPLES)
            ? SimpleStorage.getAccountId(newOwner.getString(Field.SK.getName()))
            : newOwner.getString(Field.SK.getName());
        if (objectType.equals(ObjectType.FILE)) {
          fileLink = UIPaths.getFileUrl(
              instanceURL, Utils.getEncapsulatedId(storageType, accountId, objectId));
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "OwnerAssignedDocumentToYou" + Utils.getStringPostfix(proName)),
              MailUtil.getDisplayName(oldOwner));
        } else {
          fileLink = UIPaths.getFolderUrl(instanceURL, storageType, accountId, objectId);
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "OwnerAssignedFolderToYou" + Utils.getStringPostfix(proName)),
              MailUtil.getDisplayName(oldOwner));
        }
        scopes.put("Title", title);
        scopes.put(
            "content",
            new OwnerRender(
                locale, fileLink, objectName, objectType, MailUtil.getDisplayName(oldOwner)));
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
