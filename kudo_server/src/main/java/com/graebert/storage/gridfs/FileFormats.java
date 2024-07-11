package com.graebert.storage.gridfs;

public class FileFormats {

  public static String FileExtension(String format) {
    if (format.startsWith("PDF")) {
      return ".pdf";
    }
    if (format.startsWith("DXF")) {
      return ".dxf";
    }
    if (format.startsWith("DWG")) {
      return ".dwg";
    }
    return "";
  }

  public static String toString(DwgVersion version) {
    switch (version) {
      case R12:
        return "12";
      case R13:
        return "13";
      case R14:
        return "14";
      case R2000:
        return "2000";
      case R2004:
        return "2004";
      case R2007:
        return "2007";
      case R2010:
        return "2010";
      case R2013:
        return "2013";
      case R2018:
        return "2018";
    }
    return "";
  }

  // we support formats like DWG2018, DXF12

  public static DwgVersion DWGVersion(String format) {
    if (format.length() <= 3) {
      return DwgVersion.Unknown;
    }

    String version = format.substring(3);
    for (DwgVersion t : DwgVersion.values()) {
      if (toString(t).equals(version)) {
        return t;
      }
    }
    return DwgVersion.Unknown;
  }

  public static boolean IsValid(String format) {
    if (FileExtension(format).isEmpty()) {
      return false;
    }

    return DWGVersion(format) != DwgVersion.Unknown;
  }

  public enum DwgVersion {
    R12,
    R13,
    R14,
    R2000,
    R2004,
    R2007,
    R2010,
    R2013,
    R2018,
    Unknown
  }

  public enum FileType {
    DXF,
    DWG,
    PDF,
    Unknown
  }
}
