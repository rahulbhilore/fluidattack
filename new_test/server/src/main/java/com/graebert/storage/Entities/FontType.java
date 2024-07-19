package com.graebert.storage.Entities;

import com.graebert.storage.util.Utils;

public enum FontType {
  TTF,
  TTC,
  SHX;

  public static FontType getFontTypeByExtension(String extension) throws FontException {
    try {
      return FontType.valueOf(extension.toUpperCase());
    } catch (Exception e) {
      throw new FontException("Unsupported font type", FontErrorCodes.UNSUPPORTED_FONT);
    }
  }

  public static FontType getFontTypeByFilename(String name) throws FontException {
    try {
      return FontType.valueOf(
          Utils.safeSubstringFromLastOccurrence(name, ".").toUpperCase().substring(1));
    } catch (Exception e) {
      throw new FontException("Unsupported font type", FontErrorCodes.UNSUPPORTED_FONT);
    }
  }
}
