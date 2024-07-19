package com.graebert.storage.handler;

import com.amazonaws.xray.entities.TraceHeader;
import com.graebert.storage.logs.XRayLogger;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;
import java.util.Optional;

/**
 * AWSXRayHandler creates Segments for each request done to server
 * and checks if they should be sent to xray after request is handled
 */
public class AWSXRayHandler implements Handler<RoutingContext> {
  @Override
  public void handle(RoutingContext routingContext) {
    XRayManager.startMainSegment(routingContext)
        .ifPresentOrElse(
            segment -> {
              // save segment to rc
              routingContext.put("segment", segment);

              // save traceId to response header
              Optional.ofNullable(routingContext.response())
                  .ifPresent(response -> response.putHeader(
                      "X-Amzn-Trace-Id", new TraceHeader(segment.getTraceId()).toString()));

              // add end handler to finish Segment
              routingContext.addHeadersEndHandler(
                  v -> XRayManager.finishSegment(routingContext, segment));
            },
            () -> new XRayLogger().logError("AWSXRayHandler", "Could not start Segment"));

    routingContext.next();
  }
}
