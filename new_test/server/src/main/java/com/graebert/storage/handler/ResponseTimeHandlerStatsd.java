package com.graebert.storage.handler;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Created by robert on 2/14/2017.
 */
public class ResponseTimeHandlerStatsd implements Handler<RoutingContext> {
  public void handle(RoutingContext ctx) {
    // don't do this for the other mount points
    if (ctx.mountPoint() != null) {
      ctx.next();
      return;
    }

    Entity subsegment = XRayManager.createSubSegment(
        OperationGroup.RESPONSE_TIME_HANDLER_STATS_D, "ResponseTimeHandlerStatsd");

    long start = System.currentTimeMillis();
    ctx.addHeadersEndHandler(v -> {
      long duration = System.currentTimeMillis() - start;
      if (DynamoBusModBase.statsd != null) {
        DynamoBusModBase.statsd.recordExecutionTime("api.responsetime", duration);
      }
    });

    XRayManager.endSegment(subsegment);

    ctx.next();
  }
}
