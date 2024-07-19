package com.graebert.storage.util;

public class LogPrefix {
  private static String logPrefix = "";

  public static void updateLogPrefix(String prefix) {
    logPrefix = prefix;
  }

  public static String getLogPrefix() {
    return logPrefix;
  }
}
