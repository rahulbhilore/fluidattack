package com.graebert.storage.comment;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jetbrains.annotations.NonNls;

public class AccessCache extends Dataplane {

  private static String getCacheKey(String fileId, String sessionId, String token) {
    String cacheKey = AccessCacheConstants.CACHE_PREFIX.getTitle() + sessionId + "_" + fileId;
    if (Utils.isStringNotNullOrEmpty(token)) {
      cacheKey += "_" + token;
    }
    return cacheKey;
  }

  public static JsonObject getAccessStatus(String fileId, String sessionId) {
    return getAccessStatusWithToken(fileId, sessionId, null);
  }

  public static JsonObject getAccessStatusWithToken(String fileId, String sessionId, String token) {
    final String cacheKey = getCacheKey(fileId, sessionId, token);
    Object o = getFromCache(cacheKey, DataEntityType.COMMENTS);
    if (o == null) {
      return null;
    }
    return new JsonObject((String) o);
  }

  public static void clearAccessStatus(String fileId, String sessionId, String token) {
    final String cacheKey = getCacheKey(fileId, sessionId, token);
    clearAccessStatusByKey(cacheKey);
  }

  public static void clearAccessStatus(String fileId) {
    JsonArray keysArray = getFileCacheObject(fileId);
    if (keysArray != null && !keysArray.isEmpty()) {
      keysArray.forEach(key -> clearAccessStatusByKey((String) key));
      saveFileCacheObject(fileId, new JsonArray());
    }
  }

  private static void clearAccessStatusByKey(String cacheKey) {
    try {
      deleteFromCache("", cacheKey, null);
    } catch (Exception cacheException) {
      log.error(
          "Error on clearing access status from cache. Exception to fail gracefully",
          cacheException);
    }
  }

  private static JsonArray getFileCacheObject(String fileId) {
    String cacheKey = AccessCacheConstants.CACHE_PREFIX.getTitle() + fileId;
    Object o = getFromCache(cacheKey, DataEntityType.COMMENTS);
    if (o == null) {
      return null;
    }
    return new JsonArray((String) o);
  }

  private static void saveFileCacheObject(String fileId, JsonArray keysArray) {
    String cacheKey = AccessCacheConstants.CACHE_PREFIX.getTitle() + fileId;
    try {
      populateCache(cacheKey, keysArray.encode(), 2 * 60 * 60, DataEntityType.COMMENTS);
    } catch (Exception cacheException) {
      log.error(
          "Error on saving access status to cache. Exception to fail gracefully", cacheException);
    }
  }

  public static void saveAccessStatus(
      String fileId,
      String sessionId,
      String token,
      AccessCacheConstants accessStatus,
      StorageType storageType) {
    final String cacheKey = getCacheKey(fileId, sessionId, token);
    JsonObject accessData = new JsonObject()
        .put(Field.STATUS.getName(), accessStatus.getTitle())
        .put(Field.STORAGE_TYPE.getName(), storageType.toString());
    try {
      JsonArray keysArray = getFileCacheObject(fileId);
      if (keysArray == null) {
        keysArray = new JsonArray();
      }
      keysArray.add(cacheKey);
      saveFileCacheObject(fileId, keysArray);
      populateCache(cacheKey, accessData.encode(), 60 * 60, DataEntityType.COMMENTS);
    } catch (Exception cacheException) {
      log.error(
          "Error on saving access status to cache. Exception to fail gracefully", cacheException);
    }
  }

  public enum AccessCacheConstants {
    OK("OK"),
    /**
     * Error on processing request.
     * Retryable
     */
    ERROR("ERROR"),
    /**
     * File wasn't accessible. Shouldn't retry
     */
    NO_ACCESS("NO_ACCESS"),
    TOKEN("TOKEN"),
    CACHE_PREFIX("comments_access_cache_");

    private final String title;

    AccessCacheConstants(@NonNls String title) {
      this.title = title;
    }

    public String getTitle() {
      return this.title;
    }

    public String toString() {
      return this.getTitle();
    }
  }
}
