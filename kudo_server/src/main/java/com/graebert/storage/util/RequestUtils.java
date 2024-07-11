package com.graebert.storage.util;

import com.amazonaws.xray.entities.TraceHeader;
import com.amazonaws.xray.strategy.sampling.SamplingRequest;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import java.util.Locale;
import java.util.Optional;

public class RequestUtils {
  public static TraceHeader.SampleDecision parseSampleDecision(HttpServerRequest request) {
    SamplingRequest samplingRequest = new SamplingRequest(
        getSegmentName(request),
        getHost(request).orElse(null),
        getURI(request, false),
        getMethod(request),
        XRayManager.getRecorder().getOrigin());

    if (XRayManager.getRecorder()
        .getSamplingStrategy()
        .shouldTrace(samplingRequest)
        .isSampled()) {
      return TraceHeader.SampleDecision.SAMPLED;
    } else {
      return TraceHeader.SampleDecision.NOT_SAMPLED;
    }
  }

  public static String getLocale(RoutingContext routingContext) {
    if (Utils.isStringNotNullOrEmpty(
        routingContext.request().headers().get(RequestFields.LOCALE.getName()))) {
      return routingContext.request().headers().get(RequestFields.LOCALE.getName());
    }

    return Locale.ENGLISH.getLanguage();
  }

  public static Optional<String> getHost(HttpServerRequest request) {
    return Optional.ofNullable(request.getHeader(RequestFields.HOST.getName()));
  }

  public static String getURI(HttpServerRequest request, boolean absolute) {
    return absolute ? request.absoluteURI() : request.uri();
  }

  public static String getMethod(HttpServerRequest request) {
    return request.method().toString();
  }

  public static String getSegmentName(HttpServerRequest request) {
    try {
      return Optional.ofNullable(request.getHeader(RequestFields.HOST.getName()))
          .orElse("fluorine");
    } catch (Exception exception) {
      throw new RuntimeException(
          "AWSXRayServletFilter requires either a fixedName init-param or a SegmentNamingStrategy"
              + " be provided. Please change your web.xml or constructor call as necessary.",
          exception);
    }
  }

  public static Optional<String> getUserAgent(HttpServerRequest request) {
    String userAgentHeaderString = request.getHeader(RequestFields.USER_AGENT.getName());
    return null != userAgentHeaderString ? Optional.of(userAgentHeaderString) : Optional.empty();
  }

  public static Optional<String> getXForwardedFor(HttpServerRequest request) {
    String xForwardedForHeader = request.getHeader(RequestFields.X_FORWARDED_FOR.getName());

    if (xForwardedForHeader == null) {
      return Optional.empty();
    }

    return Optional.of(xForwardedForHeader.split(",")[0].trim());
  }

  public static Optional<String> getClientIp(HttpServerRequest request) {
    return Optional.ofNullable(request.remoteAddress().host());
  }

  public static Optional<Integer> getContentLength(HttpServerResponse response) {
    String contentLengthString = response.headers().get(RequestFields.CONTENT_LENGTH.getName());
    if (Utils.isStringNotNullOrEmpty(contentLengthString)) {
      try {
        return Optional.of(Integer.parseInt(contentLengthString));
      } catch (NumberFormatException ignored) {
      }
    }

    return Optional.empty();
  }
}
