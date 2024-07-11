package com.graebert.storage.mail.emails.renderers;

import com.graebert.storage.Entities.UIPaths;
import com.graebert.storage.security.XSessionManager;
import com.graebert.storage.util.Utils;
import java.util.function.Function;

public class ConflictRender {
  String oldObjectLink;
  String newObjectLink;
  String locale;
  String editor;
  String conflictReason;
  String conflictingFileHelpLink;
  boolean isSameFolder;

  public ConflictRender(
      String instanceURL,
      String locale,
      String objectId,
      String objectName,
      String newObjectId,
      String newObjectName,
      String editor,
      String conflictReason,
      boolean isSameFolder,
      String conflictingFileHelpUrl) {
    oldObjectLink = "<a style=\"text-decoration: none;\" href='"
        + UIPaths.getFileUrl(instanceURL, objectId) + "' target='_blank'>" + objectName + "</a>";
    newObjectLink =
        "<a style=\"text-decoration: none;\" href='" + UIPaths.getFileUrl(instanceURL, newObjectId)
            + "' target='_blank'>" + newObjectName + "</a>";
    conflictingFileHelpLink = "<a style=\"text-decoration: none;\" href='"
        + UIPaths.getConflictingFileUrl(instanceURL, conflictingFileHelpUrl) + "' target='_blank'>"
        + Utils.getLocalizedString(locale, "whatIsConflictingFile") + "</a>";
    this.locale = locale;
    this.editor = editor;
    this.conflictReason = conflictReason;
    this.isSameFolder = isSameFolder;
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    String conflictReasonError = XSessionManager.ConflictingFileReason.getConflictingError(
        conflictReason, locale, oldObjectLink, editor, newObjectLink, isSameFolder);
    return (obj) -> conflictReasonError + "<br><br>" + conflictingFileHelpLink;
  }
}
