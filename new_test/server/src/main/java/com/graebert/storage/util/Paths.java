package com.graebert.storage.util;

import java.util.ArrayList;
import java.util.Arrays;

public class Paths {
  /**
   * Should be used for xrefs to convert path
   *
   * @param basePath     path of folder where we start
   * @param relativePath path of object to check
   * @return absolutePath - from root folder to the object
   */
  public static String getAbsolutePath(String basePath, String relativePath) {
    String[] baseParts = basePath.split("/");
    String[] relativeParts = relativePath.split("/");
    ArrayList<String> resultPath = new ArrayList<>(Arrays.asList(baseParts));
    for (String part : relativeParts) {
      if (part.equals("..")) {
        if (resultPath.size() > 1) {
          resultPath.remove(resultPath.size() - 1);
        }
      } else {
        resultPath.add(part);
      }
    }
    return String.join("/", resultPath);
  }
}
