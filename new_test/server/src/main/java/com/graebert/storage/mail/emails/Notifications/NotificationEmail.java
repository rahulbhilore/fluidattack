package com.graebert.storage.mail.emails.Notifications;

import com.graebert.storage.mail.emails.EmailTemplateType;

public class NotificationEmail {
  public static final EmailTemplateType EMAIL_TEMPLATE_TYPE = EmailTemplateType.NOTIFICATION;

  // DK: Code temporarily moved to MailUtil, because we cannot use eventBus here.
  // TODO: find a way to moved it back here.

}
