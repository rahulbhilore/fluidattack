package com.graebert.storage.mail.emails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.mail.emails.renderers.ObjectMovedRender;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;

public class ObjectMovedEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.OBJECTMOVED;

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      ObjectType objectType,
      StorageType storageType,
      String objectId,
      String objectName,
      String userId,
      // I don't think we should request owner's data for each email
      // let's just pass his name
      String ownerName,
      String folderId,
      String folderName) {
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
        String title = MessageFormat.format(
            Utils.getLocalizedString(
                locale,
                objectType.equals(ObjectType.FOLDER)
                    ? "FolderHasBeenMovedToAnotherFolder"
                    : "FileHasBeenMovedToAnotherFolder"),
            objectName);
        scopes.put("Title", title);
        String encapsulatedObjectId;
        String encapsulatedFolderId;
        if (storageType.equals(StorageType.SAMPLES)) {
          encapsulatedObjectId =
              Utils.getEncapsulatedId(storageType, SimpleStorage.getAccountId(userId), objectId);
          encapsulatedFolderId =
              Utils.getEncapsulatedId(storageType, SimpleStorage.getAccountId(userId), folderId);
        } else {
          encapsulatedObjectId = Utils.getEncapsulatedId(storageType, userId, objectId);
          encapsulatedFolderId = Utils.getEncapsulatedId(storageType, userId, folderId);
        }
        if (encapsulatedFolderId.contains("+")) {
          encapsulatedFolderId = encapsulatedFolderId.replaceAll("\\+", "/");
        }
        scopes.put(
            "content",
            new ObjectMovedRender(
                instanceURL,
                locale,
                objectType,
                encapsulatedObjectId,
                objectName,
                encapsulatedFolderId,
                folderName,
                ownerName));
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
