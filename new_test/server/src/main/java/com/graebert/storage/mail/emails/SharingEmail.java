package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.CollaboratorType;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;

public class SharingEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.SHARE;

  public static JsonObject getSharingUserFormat(
      String instanceURL,
      String proName,
      String locale,
      String ownerName,
      String collaborator,
      CollaboratorType collaboratorType,
      ObjectType objectType,
      StorageType storageType,
      String objectId,
      String objectName) {
    try {
      Item user = null;
      if (Utils.isEmail(collaborator)) {
        Iterator<Item> emailIterator = Users.getUserByEmail(collaborator);
        if (emailIterator.hasNext()) {
          user = emailIterator.next();
        }
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
        scopes.put("Filename", objectName);
        String fileLink;
        String sharingText;
        String title;
        String accountId = storageType.equals(StorageType.SAMPLES)
            ? SimpleStorage.getAccountId(user.getString(Field.SK.getName()))
            : user.getString(Field.SK.getName());
        if (objectType.equals(ObjectType.FILE)) {
          fileLink = UIPaths.getFileUrl(
              instanceURL, Utils.getEncapsulatedId(storageType, accountId, objectId));
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "UserSharedDocumentWithYou" + Utils.getStringPostfix(proName)),
              ownerName);
          if (collaboratorType.equals(CollaboratorType.EDITOR)) {
            sharingText = MessageFormat.format(
                Utils.getLocalizedString(locale, "ThisDocumentHasBeenSharedWithYouForEditingBy"),
                ownerName);
          } else {
            sharingText = MessageFormat.format(
                Utils.getLocalizedString(locale, "ThisDocumentHasBeenSharedWithYouForViewingBy"),
                ownerName);
          }
        } else {
          fileLink = UIPaths.getFolderUrl(instanceURL, storageType, accountId, objectId);
          title = MessageFormat.format(
              Utils.getLocalizedString(
                  locale, "UserSharedFolderWithYou" + Utils.getStringPostfix(proName)),
              ownerName);
          if (collaboratorType.equals(CollaboratorType.EDITOR)) {
            sharingText = MessageFormat.format(
                Utils.getLocalizedString(locale, "ThisFolderHasBeenSharedWithYouForEditingBy"),
                ownerName);
          } else {
            sharingText = MessageFormat.format(
                Utils.getLocalizedString(locale, "ThisFolderHasBeenSharedWithYouForViewingBy"),
                ownerName);
          }
        }
        scopes.put("FileLink", fileLink);
        scopes.put("ThisObjectHasBeenSharedWithYouForBy", sharingText);
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
