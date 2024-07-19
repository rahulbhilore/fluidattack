package com.graebert.storage.handler;

import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.vertx.RequestsCounter;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Shutdown implements Handler<RoutingContext> {

  private static final Logger log = LogManager.getRootLogger();
  private boolean shutdown = false;

  @Override
  public void handle(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();
    // service is currently shutting down
    if (shutdown) {
      request.response().setStatusCode(503).end();
    } else {
      // shutdown request
      if (HttpMethod.POST.equals(request.method()) && request.path().equals("/exit")) { // NON-NLS
        log.info("### Shutdown... ###");
        System.out.println("### Shutdown... ###");
        shutdown = true;
        AWSXRayUnirest.shutDown();
        routingContext.vertx().setPeriodic(500, event -> {
          log.info("### Requests count is " + RequestsCounter.get() + "###");
          System.out.println("### Requests count is " + RequestsCounter.get() + "###");
          if (shutdown && RequestsCounter.get() == 0) {
            log.info("### Exit from container ###");
            System.out.println("### Exit from container ###");
            request.response().setStatusCode(200).end();
            routingContext.vertx().close();
          }
        });
      } else {
        RequestsCounter.increment();
        request.response().bodyEndHandler(event -> RequestsCounter.decrement());
        routingContext.next();
      }
    }
  }
}
