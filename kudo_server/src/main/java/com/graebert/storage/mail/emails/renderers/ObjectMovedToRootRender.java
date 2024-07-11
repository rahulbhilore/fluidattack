package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.util.Utils;
import java.text.MessageFormat;
import java.util.function.Function;

public class ObjectMovedToRootRender {
  String objectLink;
  String folderName;
  String locale;
  ObjectType objectType;

  public ObjectMovedToRootRender(
      String instanceURL,
      String locale,
      ObjectType objectType,
      String objectId,
      String objectName,
      String targetFolderName) {
    objectLink = "<a href='"
        + (objectType.equals(ObjectType.FILE)
            ? UIPaths.getFileUrl(instanceURL, objectId)
            : UIPaths.getFolderUrl(instanceURL, objectId))
        + "' target='_blank'>" + objectName
        + "</a>";
    this.locale = locale;
    this.objectType = objectType;
    this.folderName = targetFolderName;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return MessageFormat.format(
          Utils.getLocalizedString(
              locale,
              objectType.equals(ObjectType.FILE) ? "TheDocumentWasMovedTo" : "TheFolderWasMovedTo"),
          objectLink,
          folderName);
    };
  }
}
