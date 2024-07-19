package com.graebert.storage.handler;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.CustomRateLimiter;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.internal.AtomicRateLimiter;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * To add a new custom rate limiter for a particular request
 * NOTE: Add your custom handler below the main router.route() for RateLimitationHandler
 * 1. Add another route (with the request path) in StorageVerticle after creating routes
 * 2. Specify the time duration to refresh and limit of the requests within that time duration
 * 3. Set name of your rate limiter using setRateLimiterName() to keep the key unique in the map
 * and cache
 * 4. Create an object of CustomRateLimiter while adding the operation of the respected request
 */
public class RateLimitationHandler extends Dataplane implements Handler<RoutingContext> {
  private static final List<CustomRateLimiter> pathsToIgnore =
      List.of(new CustomRateLimiter("/fonts/{fontId}", HttpMethod.GET, false));
  private static final Logger log = LogManager.getRootLogger();
  private static final String rateLimiterNamePrefix = "rateLimiterName";
  private static final String logPrefix = "[RATE_LIMITER] ";
  private static final ConcurrentHashMap<String, RateLimiter> rateLimiterMap =
      new ConcurrentHashMap<>();
  private final RateLimiterConfig rateLimiterConfig;
  private String key;

  public RateLimitationHandler(Duration periodDuration, int rateLimit) {
    rateLimiterConfig = RateLimiterConfig.custom()
        .limitRefreshPeriod(periodDuration)
        .limitForPeriod(rateLimit)
        .timeoutDuration(Duration.ofMillis(100))
        .build();
  }

  // General Request => 100 requests per second (WB-1192)
  // Create S3 Presigned URL Request => 15 requests per minute

  public static String getKey(RoutingContext ctx) {
    String sessionId = ctx.request().headers().get(Field.SESSION_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(sessionId)) {
      sessionId = ctx.request().getParam(Field.SESSION_ID.getName());
    }
    String clientIp = ctx.request().getHeader("X-Forwarded-For");
    if (Objects.isNull(clientIp)) {
      clientIp = ctx.request().remoteAddress().host();
    }
    String finalKey = (Objects.nonNull(sessionId) ? sessionId : clientIp);
    if (Objects.nonNull(finalKey)) {
      finalKey += getCustomKey(ctx);
    }
    return finalKey;
  }

  private static String getCustomKey(RoutingContext ctx) {
    Map<String, Object> ctxData = ctx.data();
    if (ctxData.containsKey(rateLimiterNamePrefix)) {
      return "_" + ctxData.get(rateLimiterNamePrefix);
    }
    return Dataplane.emptyString;
  }

  public static void removeRateLimiterForKey(String key) {
    if (!Utils.isStringNotNullOrEmpty(key)) {
      return;
    }
    rateLimiterMap.remove(key);
    // deleteFromCache(key);
  }

  public void setRateLimiterName(RoutingContext ctx, String rateLimiterName) {
    ctx.data().put(rateLimiterNamePrefix, rateLimiterName);
  }

  @Override
  public void handle(RoutingContext ctx) {
    Entity subsegment = XRayManager.createSubSegment(
        OperationGroup.RATE_LIMITATION_HANDLER, "RateLimitationHandler");

    // check if path is disabled for rate limiter
    if (CustomRateLimiter.checkIfPathNeedsToBeExcluded(
        ctx.request().path(), ctx.request().method(), pathsToIgnore)) {
      proceed(subsegment, ctx);
      return;
    }

    // don't check the rerouted encoded request
    if (ctx.data().containsKey("encoded") && (Boolean) ctx.data().get("encoded")) {
      proceed(subsegment, ctx);
      return;
    }

    key = getKey(ctx);

    // don't do this for the other mount points
    if (Objects.isNull(key) || Objects.nonNull(ctx.mountPoint())) {
      proceed(subsegment, ctx);
      return;
    }

    // AS: Not using the memcached for now because of serialization issue, need to find another
    // solution

    // first check if cache has RateLimiter for the key
    // RateLimiter rateLimiter = (RateLimiter) getFromCache("rateLimiter." + key);
    // if (Objects.isNull(rateLimiter)) {
    // if not, get it from local map or create new
    // }

    RateLimiter rateLimiter = getRateLimiter(subsegment);

    if (Objects.isNull(rateLimiter)) {
      log.warn(logPrefix + "rateLimiter isn't configured for the key: " + key
          + ", proceeding without checking available permissions");
      proceed(subsegment, ctx);
      return;
    }

    if (!rateLimiter.acquirePermission()) {
      ctx.response()
          .putHeader(
              "Retry-After",
              String.valueOf(
                  ((AtomicRateLimiter) rateLimiter).getDetailedMetrics().getNanosToWait()
                      / 1000000));
      ctx.fail(429);
      log.info(logPrefix + "Refused a new request from " + key + ". Nanos to wait "
          + ((AtomicRateLimiter) rateLimiter).getDetailedMetrics().getNanosToWait());
      return;
    }

    log.info(
        logPrefix + "Metrics for key:" + key + " and path: " + ctx.request().method() + " - "
            + ctx.request().path() + "\nAvailable permissions: "
            + rateLimiter.getMetrics().getAvailablePermissions()
            + ", waiting threads: " + rateLimiter.getMetrics().getNumberOfWaitingThreads()
            + ", nanos to wait: "
            + ((AtomicRateLimiter) rateLimiter).getDetailedMetrics().getNanosToWait());

    // populateCache("rateLimiter." + key, rateLimiter, 0);
    rateLimiterMap.put(key, rateLimiter);
    proceed(subsegment, ctx);
  }

  private synchronized RateLimiter getRateLimiter(Entity subsegment) {
    RateLimiter rateLimiter;
    if (rateLimiterMap.containsKey(key)) {
      rateLimiter = rateLimiterMap.get(key);
    } else {
      try {
        rateLimiter = RateLimiter.of("fluorine." + key, rateLimiterConfig);
        rateLimiterMap.put(key, rateLimiter);
      } catch (Exception ex) {
        subsegment.addException(ex);
        return null;
      }
    }
    return rateLimiter;
  }

  private void proceed(Entity subsegment, RoutingContext ctx) {
    XRayManager.endSegment(subsegment);
    ctx.next();
  }
}
