package com.graebert.storage.util;

import com.graebert.storage.config.ServerConfig;
import io.vertx.core.json.JsonArray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Extensions {
  public static final Logger log = LogManager.getRootLogger();
  public static final String DWG = ".dwg";

  public static boolean isThumbnailExt(ServerConfig config, String name, Boolean isAdmin) {
    if (!Utils.isStringNotNullOrEmpty(name)) {
      log.error("extension string in Extensions.isThumbnailExt is null or empty");
      Thread.dumpStack();
      return false;
    }
    if (isAdmin == null) {
      isAdmin = false;
      log.error("isAdmin in Extensions.isThumbnailExt is null");
      Thread.dumpStack();
    }
    JsonArray extensions =
        new JsonArray(config.getProperties().getThumbnailExtensionsAsArray().encode());
    boolean isRevitAdmin = config.getProperties().getRevitAdmin();
    if (!isRevitAdmin || isAdmin) {
      extensions.addAll(config.getProperties().getThumbnailRevitExtensionsAsArray());
    }
    for (Object extension : extensions) {
      if (extension == null) {
        continue;
      }
      if (name.toLowerCase().endsWith((String) extension)) {
        return true;
      }
    }
    return false;
  }

  public static List<String> getFilterExtensions(
      ServerConfig config, String fileFilter, Boolean isAdmin) {
    if (isAdmin == null) {
      isAdmin = false;
      log.error("isAdmin in getFilterExtensions is null");
    }
    List<String> extensions = new ArrayList<>();
    if (fileFilter.equals("drawingsAndPdf") || fileFilter.equals("drawingsOnly")) {
      extensions = new ArrayList<String>(
          config.getProperties().getThumbnailExtensionsAsArray().getList());
      boolean isRevitAdmin = config.getProperties().getRevitAdmin();
      if (!isRevitAdmin || isAdmin) {
        extensions.addAll(
            config.getProperties().getThumbnailRevitExtensionsAsArray().getList());
      }
      if (fileFilter.equals("drawingsAndPdf")) {
        extensions.add(".pdf");
      }
    } else if (!fileFilter.isEmpty()) {
      extensions = Arrays.asList(fileFilter.toLowerCase().trim().split("\\s*,\\s*"));
    }
    return extensions;
  }

  public static boolean isValidExtension(List<String> extensions, String filename) {
    if (extensions.isEmpty()) {
      return true;
    }
    if (Utils.isStringNotNullOrEmpty(filename)) {
      for (String extension : extensions) {
        if (filename.toLowerCase().endsWith(extension)) {
          return true;
        }
      }
    }
    return false;
  }

  public static String getExtension(String fileName) {
    if (fileName == null) {
      return DWG;
    }
    int i = fileName.lastIndexOf(".");
    if (i == -1) {
      return DWG;
    }
    return fileName.substring(i);
  }

  public static JsonArray getExtensions(ServerConfig config) {
    try {
      JsonArray array = config.getProperties().getThumbnailExtensionsAsArray();
      config.getProperties().getThumbnailRevitExtensionsAsArray().forEach(val -> {
        if (!array.contains(val) && val != null) {
          array.add(val);
        }
      });
      return array;
    } catch (Exception e) {
      return new JsonArray();
    }
  }
}
