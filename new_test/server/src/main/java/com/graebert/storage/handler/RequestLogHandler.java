package com.graebert.storage.handler;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.util.Field;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RequestLogHandler implements Handler<RoutingContext> {
  public static Logger log = LogManager.getRootLogger();
  private final String region;

  public RequestLogHandler(String region) {
    this.region = region;
  }

  @Override
  public void handle(RoutingContext ctx) {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.REQUEST_LOG_HANDLER, "RequestLogHandler");

    try {
      HttpServerRequest request = ctx.request();
      HttpServerResponse response = ctx.response();
      String uri = request.absoluteURI();
      if (uri.endsWith("sessionCount")
          || uri.endsWith("auth")
          || request.method().toString().equalsIgnoreCase(Field.OPTIONS.getName())) {
        XRayManager.endSegment(subsegment);

        ctx.next();
        return;
      }
      Map<String, Object> attributes = new HashMap<>();
      attributes.put(Field.URL.getName(), request.absoluteURI());
      attributes.put("method", request.method().toString());
      String userAgentHeaderString = request.getHeader("User-Agent");
      if (userAgentHeaderString != null) {
        attributes.put("user_agent", userAgentHeaderString);
      }

      String forwarded = request.getHeader("X-Forwarded-For");
      if (forwarded != null) {
        attributes.put("client_ip", forwarded.split(",")[0].trim());
      } else {
        String clientIp = request.remoteAddress().host();
        if (clientIp != null) {
          attributes.put("client_ip", clientIp);
        }
      }
      attributes.put("X-Amzn-Trace-Id", response.headers().get("X-Amzn-Trace-Id"));
      attributes.put("sessionid", request.headers().get("sessionid"));
      attributes.put("xsessionid", request.headers().get("xsessionid"));
      attributes.put(Field.TOKEN.getName(), request.headers().get(Field.TOKEN.getName()));
      JsonObject body = null;
      try {
        body = ctx.getBodyAsJson();
      } catch (Exception ignore) {
      }
      if (body != null) {
        body.remove("baseContent"); // do not log basecontent data
        body.remove("diffs");
        body.remove(Field.PASSWORD.getName());
        attributes.put("body", body.encode());
      }
      try {
        attributes.put("host", java.net.InetAddress.getLocalHost().getHostName());
      } catch (UnknownHostException ignore) {
      }
      attributes.put(Field.REGION.getName(), region);
      log.info(attributes);
    } catch (Exception e) {
      log.info("exception in the handler " + e.getLocalizedMessage());
    }

    XRayManager.endSegment(subsegment);

    ctx.next();
  }
}
