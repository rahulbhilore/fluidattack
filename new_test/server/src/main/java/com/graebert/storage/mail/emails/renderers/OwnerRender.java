package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.util.Utils;
import java.text.MessageFormat;
import java.util.function.Function;

public class OwnerRender {
  String objectLink;
  String ownerName;
  String locale;
  ObjectType objectType;

  public OwnerRender(
      String locale, String objlink, String objectName, ObjectType objectType, String ownerName) {
    objectLink = "<a href='" + objlink + "' target='_blank'>" + objectName + "</a>";
    this.locale = locale;
    this.objectType = objectType;
    this.ownerName = ownerName;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return MessageFormat.format(
          Utils.getLocalizedString(
              locale,
              objectType.equals(ObjectType.FOLDER)
                  ? "OwnerAssignedFolderToYouDescription"
                  : "OwnerAssignedDocumentToYouDescription"),
          ownerName,
          objectLink);
    };
  }
}
