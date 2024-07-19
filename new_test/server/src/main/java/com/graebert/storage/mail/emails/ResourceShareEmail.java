package com.graebert.storage.mail.emails;

import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.Role;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.mail.MailUtil;
import com.graebert.storage.resources.ResourceType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonObject;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

public class ResourceShareEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.RESOURCESHARED;

  public static JsonObject getEmailData(
      String instanceURL,
      String proName,
      String locale,
      String ownerName,
      String collaboratorName,
      String collaboratorEmail,
      ResourceType resourceType,
      String resourceName,
      String resourceThumbnail,
      String resourceParentId,
      ObjectType objectType,
      Role role) {
    try {
      HashMap<String, Object> scopes = new HashMap<>();
      scopes.put("CADInCloud", Utils.getLocalizedString(locale, "CADInCloud"));
      scopes.put(
          "HiFirstName",
          MessageFormat.format(Utils.getLocalizedString(locale, "HiFirstName"), collaboratorName));
      scopes.put(
          "AutomatedEmailDontReply", Utils.getLocalizedString(locale, "AutomatedEmailDontReply"));
      scopes.put("ResourceName", resourceName);
      scopes.put(
          "ResourcesLink", UIPaths.getResourceURL(instanceURL, resourceParentId, resourceType));
      String title = MessageFormat.format(
          Utils.getLocalizedString(
              locale,
              (role.equals(Role.EDITOR))
                  ? "UserHasSharedTheResourceWithYouForEditing"
                  : "UserHasSharedTheResourceWithYouForViewing"),
          ownerName,
          Utils.getLocalizedString(
                  locale,
                  Objects.nonNull(resourceType)
                      ? resourceType.translationName
                      : Field.UNKNOWN.getName())
              + " " + Utils.getLocalizedString(locale, StringUtils.capitalize(objectType.name())));
      scopes.put("UserHasSharedTheResourceWithYou", title);
      scopes.put("ResourceThumbnail", resourceThumbnail);
      scopes.put("thumbnailExists", Utils.isStringNotNullOrEmpty(resourceThumbnail));
      scopes.putAll(MailUtil.getColors(proName));
      ownerName = MailUtil.encodeName(ownerName);
      String from = String.format(MailUtil.fromFormat, ownerName, MailUtil.sender);
      String executed = MailUtil.executeMustache(EMAIL_TEMPLATE_TYPE, proName, scopes);

      return new JsonObject()
          .put("from", from)
          .put("to", collaboratorEmail)
          .put("subject", title)
          .put("html", executed);
    } catch (Exception e) {
      DynamoBusModBase.log.error(LogPrefix.getLogPrefix() + " Error on email sending: ", e);
    }
    return null;
  }
}
