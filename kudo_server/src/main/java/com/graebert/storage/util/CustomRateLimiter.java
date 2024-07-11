package com.graebert.storage.util;

import io.vertx.core.http.HttpMethod;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

public class CustomRateLimiter {
  private static final Set<CustomRateLimiter> customRequests = new HashSet<>();
  private final String path;
  private final HttpMethod method;

  public CustomRateLimiter(String path, HttpMethod method, boolean addToList) {
    this.path = path;
    this.method = method;
    if (addToList) {
      customRequests.add(this);
    }
  }

  public static boolean isCustomRateLimiter(String path, HttpMethod method) {
    return checkIfPathNeedsToBeExcluded(path, method, customRequests);
  }

  public static boolean checkIfPathNeedsToBeExcluded(
      String path, HttpMethod method, Collection<CustomRateLimiter> collection) {
    if (collection.isEmpty()) {
      return false;
    }
    if (path.trim().equals("/")) {
      return false;
    }
    String[] paths = path.trim().split("/");
    return collection.stream().anyMatch(req -> {
      String reqPath = req.getPath();
      if (method.equals(req.getMethod())) {
        if (path.equals(reqPath)) {
          return true;
        }
        if (reqPath.substring(1).startsWith(paths[1])) {
          if (reqPath.endsWith(paths[paths.length - 1])) {
            return true;
          }
          String[] requestPath = reqPath.trim().split("/");
          if (requestPath.length == paths.length) {
            for (int i = 2; i < requestPath.length; i++) {
              if (!(requestPath[i].startsWith("{") && requestPath[i].endsWith("}"))) {
                if (requestPath[i].length() != paths[i].length()) {
                  return false;
                }
              } else {
                if (StringUtils.isAlpha(paths[i])) {
                  return false;
                }
              }
            }
            return true;
          }
        }
      }
      return false;
    });
  }

  public String getPath() {
    return path;
  }

  public HttpMethod getMethod() {
    return method;
  }
}
