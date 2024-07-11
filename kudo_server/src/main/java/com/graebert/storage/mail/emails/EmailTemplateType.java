package com.graebert.storage.mail.emails;

import org.jetbrains.annotations.NonNls;

public enum EmailTemplateType {
  SHARE("notifyshare"),
  DESHARE("notifydeshare"),
  NOTIFICATION("notification"),
  CONFLICTINGFILE("conflictingfilecreated"),
  OWNERSHIPCHANGED("notifyownershipchanged"),
  PASSWORDRESETREQUEST("passwordResetRequired"),
  ACCOUNTDELETED("accountDeleted"),
  ACCOUNTENABLED("accountEnabled"),
  OBJECTMOVED("objectMoved"),
  OBJECTMOVEDTOROOT("objectMovedToRootBecauseDeletedParent"),
  OBJECTDELETED("objectDeleted"),
  EMAILCHANGEREQUEST("changeEmail"),
  CONFIRMREGISTRATION("confirmRegistration"),
  NOTIFYADMINREGISTRATION("notifyAdminRegistration"),
  NOTIFYADMINUSERERASE("notifyAdminUserErase"),
  REQUESTFILEACCESS("requestFileA"),
  USERMENTIONED("userMentioned"),
  RESOURCESHARED("notifyResourceShare"),
  RESOURCEDESHARED("notifyResourceDeshare"),

  RESOURCEDELETED("notifyResourceDeleted"),
  CUSTOM("custom");

  @NonNls
  private final String templateName;

  EmailTemplateType(@NonNls String name) {
    this.templateName = name;
  }

  public String getTemplateName() {
    return this.templateName;
  }
}
