package com.graebert.storage.mail.emails.Notifications;

import com.graebert.storage.util.Utils;
import java.util.function.Function;

public class ThreadMeta {
  String title;
  String timeString;
  String userName;
  String userShortName;
  String label;
  String labelColor;
  String text;
  Integer width;

  public ThreadMeta(String title, String timeString, String userName, String text) {
    this.title = title;
    this.timeString = timeString;
    this.userName = userName;
    this.text = text;
    this.width = 0;
    String[] userNameParts = userName.split(" ");
    if (userNameParts.length == 2) {
      this.userShortName =
          (userNameParts[0].charAt(0) + "" + userNameParts[1].charAt(0)).toUpperCase();
    } else if (userName.length() >= 2) {
      this.userShortName = (userName.charAt(0) + "" + userName.charAt(1)).toUpperCase();
    } else {
      this.userShortName = "UN";
    }
  }

  /**
   * Decorator for email template
   */
  public Function<Object, Object> contentRender() {
    return (obj) -> {
      return this.text;
    };
  }

  public void setLabel(String label) {
    if (Utils.isStringNotNullOrEmpty(label)) {
      this.label = label.toUpperCase();
      if (this.label.length() > 20) {
        this.width = 200;
      } else {
        this.width = 120;
      }
    } else {
      this.label = "";
    }
  }

  public void setLabelColor(String labelColor) {
    this.labelColor = labelColor;
  }

  public void setUserName(String userName) {
    this.userName = userName;
    String[] userNameParts = userName.split(" ");
    if (userNameParts.length == 2) {
      this.userShortName =
          (userNameParts[0].charAt(0) + "" + userNameParts[1].charAt(0)).toUpperCase();
    } else if (userName.length() >= 2) {
      this.userShortName = (userName.charAt(0) + "" + userName.charAt(1)).toUpperCase();
    } else {
      this.userShortName = "UN";
    }
  }

  public void setTimeString(String timeString) {
    this.timeString = timeString;
  }
}
