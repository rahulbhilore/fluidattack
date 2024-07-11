package com.graebert.storage.xray;

import com.amazonaws.client.builder.AwsSyncClientBuilder;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.AWSXRayRecorder;
import com.amazonaws.xray.contexts.SegmentContextExecutors;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.Subsegment;
import com.amazonaws.xray.entities.TraceHeader;
import com.amazonaws.xray.entities.TraceID;
import com.amazonaws.xray.handlers.TracingHandler;
import com.graebert.storage.logs.XRayLogger;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.RequestUtils;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.message.MessageUtils;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.dropwizard.MetricsService;
import io.vertx.ext.web.RoutingContext;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class XRayManager {
  private static final AWSXRayRecorder recorder = AWSXRay.getGlobalRecorder();
  private static MetricsService metricsService;
  private static Boolean isXrayEnabled;
  private static Boolean isAwsTracingEnabled;

  private XRayManager() {}

  /**
   * Get current AWSXRayRecorder
   * @return AWSXRayRecorder
   */
  public static AWSXRayRecorder getRecorder() {
    return recorder;
  }

  /**
   * Initializes XRayManager class
   * @param isXrayEnabled - is xray enabled boolean
   * @param metricsService - metrics service
   */
  public static void initialize(boolean isXrayEnabled, MetricsService metricsService) {
    XRayManager.isXrayEnabled = isXrayEnabled;
    XRayManager.metricsService = metricsService;
    XRayManager.isAwsTracingEnabled = true;
  }

  /**
   * @return true if Xray is enabled by configuration, otherwise false
   */
  public static boolean isXrayEnabled() {
    return Objects.nonNull(isXrayEnabled) && isXrayEnabled;
  }

  /**
   * @return true if Aws Tracing (eg dynamo, s3 ... ) is enabled, otherwise false
   */
  public static boolean isAwsTracingEnabled() {
    return isXrayEnabled() && Objects.nonNull(isAwsTracingEnabled) && isAwsTracingEnabled;
  }

  /**
   * Applies tracing handler to aws supported sync clients if config enabled
   * @param builder - aws sync builder
   * @param <T> - generic
   * @param <K> - generic
   */
  public static <T extends AwsSyncClientBuilder<T, K>, K> void applyTracingHandler(
      AwsSyncClientBuilder<T, K> builder) {
    if (isAwsTracingEnabled()) {
      builder.setRequestHandlers(new TracingHandler(recorder));
    }
  }

  private static Segment createSegmentFromParent(Segment parent, String name) {
    try {
      Segment segment = createSegmentFromData(parent.getTraceId(), parent.getParentId(), name);

      newSegmentContextExecutor(segment);

      return segment;
    } catch (Exception e) {
      new XRayLogger().logError("createSegment", "Cant create segment from parent, name: " + name);

      return null;
    }
  }

  private static Segment createSegmentFromData(TraceID traceID, String parentId, String name) {
    if (!isXrayEnabled()) {
      return null;
    }

    try {
      return name != null
          ? recorder.beginSegment(name, traceID, parentId)
          : recorder.beginNoOpSegment(traceID);
    } catch (Exception e) {
      new XRayLogger()
          .logError(
              "createSegment",
              "Cant create segment from data, name: " + name + ", traceId: " + traceID.toString()
                  + " parentId: " + parentId);

      return null;
    }
  }
  // should have current segment in localStorage to work properly
  private static Segment createSegment(String name) {
    if (!isXrayEnabled()) {
      return null;
    }

    Optional<Segment> parent = recorder.getCurrentSegmentOptional();

    return parent.map(segment -> createSegmentFromParent(segment, name)).orElse(null);
  }

  /**
   * Creates new segment based on data passed in message (traceId, parent)
   * or data stored in LocalThreadStorage
   * @param operationGroup - operation group name
   * @param message - message, to create segment from
   * @return new segment
   * @param <T> is JsonObject or Buffer
   */
  public static <T> Segment createSegment(OperationGroup operationGroup, Message<T> message) {
    if (!isXrayEnabled()) {
      return null;
    }

    JsonObject jsonObject;

    try {
      jsonObject = MessageUtils.parse(message).getJsonObject();

      String traceIdString = jsonObject.getString(Field.TRACE_ID.getName());
      String parentId = jsonObject.getString(Field.SEGMENT_PARENT_ID.getName());

      Segment segment;

      if (Utils.isStringNotNullOrEmpty(traceIdString)) {
        segment =
            createSegmentFromData(TraceID.fromString(traceIdString), parentId, message.address());
      } else {
        new XRayLogger()
            .logError(
                "createSegment",
                "No data passed to create segment address: " + message.address() + " json: "
                    + jsonObject);
        segment = createSegment(message.address());
      }

      XRayEntityUtils.setEntityGroup(segment, operationGroup);

      String userId = jsonObject.getString(Field.USER_ID.getName());
      if (Utils.isStringNotNullOrEmpty(userId)) {
        XRayEntityUtils.putAnnotation(segment, XrayField.USER_ID, userId);
      }

      return segment;
    } catch (Exception exception) {
      new XRayLogger()
          .logError(
              "createSegment",
              "Cant create segment from message, message address: " + message.address());

      return null;
    }
  }

  /**
   * Creates new subsegment from passed Entity as parent.
   * @param operationGroup - operation group name
   * @param parent - parent entity to extended by created one
   * @param name - specific segment's name
   * @return new Created subsegment, extended from passed one
   */
  public static Entity createSubSegment(OperationGroup operationGroup, Entity parent, String name) {
    if (!isXrayEnabled() || Objects.isNull(parent)) {
      return null;
    }

    TraceID traceId = parent.getTraceId();

    Entity segment;

    Entity current = recorder.getCurrentSegmentOptional().orElse(null);
    if (Objects.isNull(current)) {
      segment = recorder.beginSegment(name, traceId, parent.getParentId());
      XRayEntityUtils.copyAnnotation(segment, parent, XrayField.FILE_ID);
    } else {
      if (traceId.equals(current.getTraceId())) {
        segment = recorder.beginSubsegment(name);
        segment.setTraceId(traceId);
        segment.setParent(current);
        XRayEntityUtils.copyAnnotation(segment, current, XrayField.FILE_ID);
      } else {
        recorder.clearTraceEntity();
        segment = recorder.beginSegment(name, traceId, parent.getParentId());
        XRayEntityUtils.copyAnnotation(segment, parent, XrayField.FILE_ID);
      }
    }

    XRayEntityUtils.setEntityGroup(segment, operationGroup);

    return segment;
  }

  /**
   * Creates new subsegment from context Entity. Should be used only in thread safe cases,
   * when Thread local storage contains info about just created segment
   * @param operationGroup - operation group name
   * @param name - specific segment's name
   * @return new Created subsegment, extended from segment, appearing in ThreadLocalStorage context
   */
  public static Subsegment createSubSegment(OperationGroup operationGroup, String name) {
    if (!isXrayEnabled()) {
      return null;
    }

    Subsegment subsegment;

    Entity current = recorder.getCurrentSegmentOptional().orElse(null);

    if (Objects.isNull(current)) {
      new XRayLogger()
          .logError("createSubsegmentByName", "No Segment found, requested name: " + name);
      return null;
    } else {
      subsegment = recorder.beginSubsegment(name);
    }

    XRayEntityUtils.setEntityGroup(subsegment, operationGroup);
    XRayEntityUtils.copyAnnotation(subsegment, current, XrayField.FILE_ID);
    XRayEntityUtils.copyAnnotation(subsegment, current, XrayField.USER_ID);

    return subsegment;
  }

  /**
   * Creates independent named segment and clears localThreadStorage
   * @param operationGroup - operation group name
   * @param name - name of new Segment
   * @return new Created segment
   */
  public static Segment createIndependentStandaloneSegment(
      OperationGroup operationGroup, String name) {
    if (!isXrayEnabled()) {
      return null;
    }

    recorder.getCurrentSegmentOptional().ifPresent(s -> recorder.clearTraceEntity());

    Segment segment = recorder.beginSegment(name);
    segment.setTraceId(TraceID.create());
    XRayEntityUtils.setEntityGroup(segment, operationGroup);
    return segment;
  }

  /**
   * Creates Segment called "*.blocking" from another Entity, passed as parent
   * Should be used in new executeBlocking(() -> *); mostly
   * @param operationGroup - operation group name
   * @param parent - parent entity to extended by created one
   * @return new Created segment, extended from parent
   */
  public static Segment createBlockingSegment(OperationGroup operationGroup, Entity parent) {
    return createStandaloneSegment(
        operationGroup, parent, XRayEntityUtils.makeBlockingName(parent));
  }

  /**
   * Creates named Segment from another Entity, passed as parent
   * @param operationGroup - operation group name
   * @param parent - parent entity to extended by created one
   * @param name - specific segment's name
   * @return new Created segment, extended from parent
   */
  public static Segment createStandaloneSegment(
      OperationGroup operationGroup, Entity parent, String name) {
    if (!isXrayEnabled() || Objects.isNull(parent)) {
      return null;
    }

    recorder.getCurrentSegmentOptional().ifPresent(s -> recorder.clearTraceEntity());

    Segment segment = recorder.beginSegment(name, parent.getTraceId(), parent.getId());
    XRayEntityUtils.setEntityGroup(segment, operationGroup);
    XRayEntityUtils.copyAnnotation(segment, parent, XrayField.FILE_ID);
    return segment;
  }

  /**
   * Starts main segment, right when it's received by server (AWSXrayHandler)
   * @param routingContext - http context
   * @return entity if xray enabled or null, wrapped with Optional
   */
  public static Optional<Entity> startMainSegment(RoutingContext routingContext) {
    if (!isXrayEnabled()) {
      return Optional.empty();
    }

    if (Objects.isNull(routingContext.request())) {
      return Optional.of(recorder.beginNoOpSegment(TraceID.create()));
    } else {
      HttpServerRequest request = routingContext.request();

      Entity createdSegment;
      TraceID traceId = TraceID.create();

      // --> Choosing way to create main segment:

      // Request's SampleDecision is SAMPLED
      if (RequestUtils.parseSampleDecision(request).equals(TraceHeader.SampleDecision.SAMPLED)) {
        createdSegment = recorder.beginSegment(RequestUtils.getSegmentName(request), traceId, null);
      }
      // Force sampling is enabled in config
      else if (recorder.getSamplingStrategy().isForcedSamplingSupported()) {
        Segment newSegment =
            recorder.beginSegment(RequestUtils.getSegmentName(request), traceId, null);
        newSegment.setSampled(false);
        createdSegment = newSegment;
      }
      // No-operational segment required
      else {
        createdSegment = recorder.beginNoOpSegment(traceId);
      }

      // Put all required HTTP params
      createdSegment.putHttp("request", new HashMap<String, Object>() {
        {
          put(Field.URL.getName(), RequestUtils.getURI(request, true));
          put("method", RequestUtils.getMethod(request));

          RequestUtils.getUserAgent(request).ifPresent(userAgent -> put("user_agent", userAgent));

          RequestUtils.getXForwardedFor(request)
              .ifPresentOrElse(
                  xForwardedFor -> {
                    put("client_ip", xForwardedFor);
                    put("x_forwarded_for", true);
                  },
                  () -> RequestUtils.getClientIp(request)
                      .ifPresent(clientIp -> put("client_ip", clientIp)));

          // put all headers
          request.headers().forEach(header -> {
            if (Utils.isStringNotNullOrEmpty(header.getKey())
                && Utils.isStringNotNullOrEmpty(header.getValue())) {
              put("header_" + header.getKey(), header.getValue());
            }
          });

          // put all body props
          Optional.ofNullable(Utils.getBodyAsJson(routingContext))
              .ifPresent(body -> put("body", body.toString()));
        }
      });

      new XRayLogger()
          .logInfo(
              "startMainSegment",
              String.format(
                  "Segment is starting, traceId: %s, segment: %s",
                  createdSegment.getTraceId(),
                  XRayEntityUtils.convertEntityToJson(createdSegment)));

      return Optional.of(createdSegment);
    }
  }

  /**
   * Finishes main segment, right when it's received by server (AWSXrayHandler)
   * @param routingContext - http context
   * @param entity - entity started by #startMainSegment(String) method
   */
  public static void finishSegment(RoutingContext routingContext, Entity entity) {
    if (!isXrayEnabled() || Objects.isNull(entity)) {
      return;
    }

    // Request end up with Segment
    if (entity instanceof Segment) {
      Segment segment = (Segment) entity;

      // Put all required HTTP response params if request exists
      Optional.ofNullable(routingContext.response())
          .ifPresent(response -> segment.putHttp("response", new HashMap<String, Object>() {
            {
              int status = routingContext.response().getStatusCode();

              segment.setError(status / 100 == 4);
              segment.setThrottle(status == 429);
              segment.setFault(status / 100 == 5);

              put(Field.STATUS.getName(), status);
              put(Field.URL.getName(), RequestUtils.getURI(routingContext.request(), true));

              try {
                put("host", java.net.InetAddress.getLocalHost().getHostName());
              } catch (UnknownHostException ignore) {
              }

              // put all headers
              routingContext.response().headers().forEach(header -> {
                if (Utils.isStringNotNullOrEmpty(header.getKey())
                    && Utils.isStringNotNullOrEmpty(header.getValue())) {
                  put("header_" + header.getKey(), header.getValue());
                }
              });

              // add user to request & metadata
              Optional.ofNullable(routingContext.user()).ifPresent(user -> {
                String userId = new JsonObject(user.attributes().getString(Field.USER.getName()))
                    .getString(Field.SK.getName());

                XRayEntityUtils.putMetadata(segment, XrayField.USER_ID, userId);
                XRayEntityUtils.putAnnotation(segment, XrayField.USER_ID, userId);
                put(Field.USER_ID.getName(), userId);
              });
            }
          }));

      new XRayLogger()
          .logInfo(
              "finishSegment",
              String.format(
                  "Segment is finishing, traceId: %s, segment: %s",
                  segment.getTraceId(), XRayEntityUtils.convertEntityToJson(segment)));

      // send segment if needed
      if (segment.end()) {
        recorder.sendSegment(segment);
      }

      // clear thread local storage
      recorder.clearTraceEntity();
    }
    // not sure why but we may get SubsegmentHere
    else {
      try {
        Subsegment subsegment = (Subsegment) entity;

        new XRayLogger()
            .logInfo(
                "finishSegment",
                String.format(
                    "Subsegment is finishing, traceId: %s, subsegment: %s",
                    subsegment.getTraceId(), XRayEntityUtils.convertEntityToJson(subsegment)));

        if (subsegment.end()) {
          recorder.sendSegment(entity.getParentSegment());
        } else {
          if (recorder.getStreamingStrategy().requiresStreaming(entity.getParentSegment())) {
            recorder
                .getStreamingStrategy()
                .streamSome(entity.getParentSegment(), recorder.getEmitter());
          }
        }
      } catch (Exception exception) {
        new XRayLogger().logError("finishSegment", "Cant finish Subsegment", exception);
      }
    }
  }

  /**
   * Checks if segment should be closed, and closes it.
   * However, clears out ThreadLocalStorage
   * @param current - Passed segment/subsegment
   */
  public static void endSegment(Entity current) {
    if (current != null && !current.isEmitted()) {
      endSegment(current, null);
    }
  }

  /**
   * Checks if segment should be closed, and closes it.
   * However, clears out ThreadLocalStorage
   * @param current - Passed segment/subsegment
   * @param throwable - Error to apply to entity. May be null if no error happened
   */
  public static void endSegment(Entity current, Throwable throwable) {
    if (!isXrayEnabled() || Objects.isNull(current)) {
      return;
    }

    if (throwable != null) {
      current.addException(throwable);
    }

    try {
      if (current instanceof Subsegment) {
        String s = "(" + Thread.currentThread().getId() + ")" + "Ending subsegment: "
            + current.getName() + " (" + current.getId() + ")";
        Subsegment currentSubsegment = (Subsegment) current;

        try {
          CompletableFuture.supplyAsync(() -> {
                if (currentSubsegment.end()) {
                  recorder.sendSegment(currentSubsegment.getParentSegment());
                } else {
                  if (recorder
                      .getStreamingStrategy()
                      .requiresStreaming(currentSubsegment.getParentSegment())) {
                    recorder
                        .getStreamingStrategy()
                        .streamSome(currentSubsegment.getParentSegment(), recorder.getEmitter());
                  }
                  SegmentContextExecutors.newSegmentContextExecutor(current.getParentSegment());
                }
                return true;
              })
              .get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
          new XRayLogger().logError("endSegment", "Timeout on xray end. " + s, e);
        }
      } else {
        String s =
            "(" + Thread.currentThread().getId() + ")" + "Ending segment: " + current.getName()
                + " (" + current.getId() + ")" + " for trace " + current.getTraceId();
        Segment segment = current.getParentSegment();

        try {
          CompletableFuture.supplyAsync(() -> {
                if (segment.end()) {
                  recorder.sendSegment(segment);
                }
                return true;
              })
              .get(5, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
          new XRayLogger().logError("endSegment", "Timeout on xray end. " + s, e);
        }
        // this only works if we are in the same thread as when we were created.
        recorder.getCurrentSegmentOptional().ifPresent(currentSegment -> {
          if (currentSegment == current) {
            recorder.clearTraceEntity();
          }
        });
      }
    } catch (Exception ignore) {
      new XRayLogger().logError("endSegment", "Cant end segment");
    }
  }

  /**
   * Forces new Entity to be set as current Segment/Subsegment
   * deprecated, but ok for now (did not find better solution yet)
   * @param entity - Passed segment/subsegment
   */
  public static void newSegmentContextExecutor(Entity entity) {
    if (entity instanceof Segment) {
      //      SegmentContextExecutors.newSegmentContextExecutor((Segment) entity);
      recorder.setTraceEntity(entity);
    }
  }

  public static Optional<Entity> getCurrentSegment() {
    if (!isXrayEnabled()) {
      return Optional.empty();
    }

    return Optional.ofNullable(recorder.getCurrentSegmentOptional().orElse(null));
  }

  public static Optional<Entity> getCurrentSubSegment() {
    if (!isXrayEnabled()) {
      return Optional.empty();
    }

    return Optional.ofNullable(recorder.getCurrentSubsegmentOptional().orElse(null));
  }
}
