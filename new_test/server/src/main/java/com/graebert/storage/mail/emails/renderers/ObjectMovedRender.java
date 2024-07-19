package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.ObjectType;
import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.util.Utils;
import java.text.MessageFormat;
import java.util.function.Function;

public class ObjectMovedRender {
  String objectLink;
  String folderLink;
  String userName;
  String locale;
  ObjectType objectType;

  public ObjectMovedRender(
      String instanceURL,
      String locale,
      ObjectType objectType,
      String objectId,
      String objectName,
      String targetFolderId,
      String targetFolderName,
      String userName) {
    objectLink = "<a href='"
        + (objectType.equals(ObjectType.FILE)
            ? UIPaths.getFileUrl(instanceURL, objectId)
            : UIPaths.getFolderUrl(instanceURL, objectId))
        + "' target='_blank'>" + objectName
        + "</a>";
    folderLink = "<a href='" + UIPaths.getFolderUrl(instanceURL, targetFolderId)
        + "' target='_blank'>" + targetFolderName + "</a>";
    this.locale = locale;
    this.objectType = objectType;
    this.userName = userName;
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
          folderLink,
          userName);
    };
  }
}
