package com.graebert.storage.mail.emails;

public enum EmailColor {
  KUDO_BACKGROUND("#CFCFCF"),
  KUDO_SECTION_COLOR("#141518"),
  KUDO_MAIN_TEXT_COLOR("#CFCFCF"),
  DS_BACKGROUND("#CFCFCF"),
  DS_SECTION_COLOR("#CFCFCF"),
  DS_MAIN_TEXT_COLOR("#333333");

  private final String hexColorCode;

  EmailColor(String hexColorCode) {
    this.hexColorCode = hexColorCode;
  }

  public String getHexColorCode() {
    return hexColorCode;
  }
}
