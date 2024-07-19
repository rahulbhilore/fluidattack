package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.stats.beans.HttpRequest;
import com.graebert.storage.stats.beans.HttpRequests;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import kong.unirest.GetRequest;
import kong.unirest.HttpRequestSummary;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.MetricContext;
import kong.unirest.Unirest;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.nio.client.HttpAsyncClient;
import org.jetbrains.annotations.NonNls;

public class AWSXRayUnirest {
  private static final Map<String, HttpRequests> statsBeans = new HashMap<>();
  private static final MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();

  // let's leave those public until HancomUnirest class refactored
  public static final RequestConfig customRequestConfiguration = RequestConfig.custom()
      .setConnectTimeout(ServerConfig.getUnirestConnectionTimeout())
      .setSocketTimeout(ServerConfig.getUnirestSocketTimeout())
      .setConnectionRequestTimeout(ServerConfig.getUnirestConnectionRequestTimeout())
      .build();
  public static final HttpAsyncClient asyncClient = AWSXRayTracedHttpAsyncClientBuilder.create()
      .setDefaultRequestConfig(customRequestConfiguration)
      .setMaxConnPerRoute(ServerConfig.getUnirestConcurrencyMaxPerRoute())
      .setMaxConnTotal(ServerConfig.getUnirestConcurrencyMax())
      .disableCookieManagement()
      .build();

  public static void init(boolean xRay) {
    System.out.println("UNIREST SETTINGS: \n" + " CONCURRENCY: per route: "
        + ServerConfig.getUnirestConcurrencyMaxPerRoute() + " / max: "
        + ServerConfig.getUnirestConcurrencyMax() + "\n TIMEOUTS: connection: "
        + ServerConfig.getUnirestConnectionTimeout() + " / socket: "
        + ServerConfig.getUnirestSocketTimeout() + " / connection request: "
        + ServerConfig.getUnirestConnectionRequestTimeout());
    DynamoBusModBase.log.info("UNIREST SETTINGS: \n" + " CONCURRENCY: per route: "
        + ServerConfig.getUnirestConcurrencyMaxPerRoute() + " / max: "
        + ServerConfig.getUnirestConcurrencyMax() + "\n TIMEOUTS: connection: "
        + ServerConfig.getUnirestConnectionTimeout() + " / socket: "
        + ServerConfig.getUnirestSocketTimeout() + " / connection request: "
        + ServerConfig.getUnirestConnectionRequestTimeout());
    if (xRay) {
      Unirest.config()
          .enableCookieManagement(false)
          .instrumentWith(AWSXRayUnirest::metricsHandler)
          .connectTimeout(ServerConfig.getUnirestConnectionTimeout())
          .socketTimeout(ServerConfig.getUnirestSocketTimeout())
          .concurrency(
              ServerConfig.getUnirestConcurrencyMax(),
              ServerConfig.getUnirestConcurrencyMaxPerRoute())
          .asyncClient(asyncClient);
    } else {
      Unirest.config()
          .enableCookieManagement(false)
          .connectTimeout(ServerConfig.getUnirestConnectionTimeout())
          .socketTimeout(ServerConfig.getUnirestSocketTimeout())
          .concurrency(
              ServerConfig.getUnirestConcurrencyMax(),
              ServerConfig.getUnirestConcurrencyMaxPerRoute());
    }
  }

  private static MetricContext metricsHandler(HttpRequestSummary request) {
    Entity subSegment = XRayManager.createSubSegment(
        OperationGroup.UNIREST, "AWSXRayUnirest.".concat(request.getHttpMethod().name()));
    XRayEntityUtils.putAnnotation(
        subSegment, XrayField.UNIREST_METHOD, request.getHttpMethod().name());

    XRayEntityUtils.putMetadata(
        subSegment, XrayField.UNIREST_METHOD, request.getHttpMethod().name());
    XRayEntityUtils.putMetadata(subSegment, XrayField.UNIREST_URL, request.getUrl());

    HttpRequest call = new HttpRequest(request.getUrl(), System.currentTimeMillis());
    String domain = call.getDomain();
    HttpRequests requestsStats;
    if (statsBeans.containsKey(domain)) {
      requestsStats = statsBeans.get(domain);
    } else {
      requestsStats = new HttpRequests();
      statsBeans.put(domain, requestsStats);
    }

    try {
      ObjectName objectName = new ObjectName("com.graebert.fluorine.main.http:name=" + domain);
      if (!platformMBeanServer.isRegistered(objectName)) {
        platformMBeanServer.registerMBean(requestsStats, objectName);
      }
    } catch (InstanceAlreadyExistsException e) {
      DynamoBusModBase.log.warn("Already existed instance : " + e.getLocalizedMessage());
    } catch (Exception e) {
      e.printStackTrace();
    }
    requestsStats.openedConnection();

    return (responseSummary, exception) -> {
      if (responseSummary != null) {
        XRayEntityUtils.putAnnotation(
            subSegment, XrayField.UNIREST_RESPONSE_STATUS, responseSummary.getStatus());
        XRayEntityUtils.putMetadata(
            subSegment, XrayField.UNIREST_RESPONSE_MESSAGE, responseSummary.getStatusText());
        if (exception != null) {
          XRayEntityUtils.putAnnotation(
              subSegment, XrayField.UNIREST_ERROR_STATUS, responseSummary.getStatus());
          XRayManager.endSegment(subSegment, exception);
        } else {
          XRayManager.endSegment(subSegment);
        }

        call.finishCall(responseSummary.getStatus(), System.currentTimeMillis());
        requestsStats.closedConnection(call);
      }
    };
  }

  public static void shutDown() {
    Unirest.shutDown();
  }

  public static GetRequest get(@NonNls String url, String name) {
    XRayManager.getCurrentSegment().ifPresent(segment -> {
      XRayEntityUtils.putAnnotation(segment, XrayField.UNIREST_REQUEST_NAME, name);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_URL, url);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_METHOD, "GET");
    });
    return Unirest.get(url);
  }

  public static HttpRequestWithBody post(@NonNls String url, String name) {
    XRayManager.getCurrentSegment().ifPresent(segment -> {
      XRayEntityUtils.putAnnotation(segment, XrayField.UNIREST_REQUEST_NAME, name);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_URL, url);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_METHOD, "POST");
    });
    return Unirest.post(url);
  }

  public static HttpRequestWithBody patch(String url, String name) {
    XRayManager.getCurrentSegment().ifPresent(segment -> {
      XRayEntityUtils.putAnnotation(segment, XrayField.UNIREST_REQUEST_NAME, name);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_URL, url);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_METHOD, "PATCH");
    });
    return Unirest.patch(url);
  }

  public static HttpRequestWithBody delete(String url, String name) {
    XRayManager.getCurrentSegment().ifPresent(segment -> {
      XRayEntityUtils.putAnnotation(segment, XrayField.UNIREST_REQUEST_NAME, name);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_URL, url);
      XRayEntityUtils.putMetadata(segment, XrayField.UNIREST_METHOD, "DELETE");
    });
    return Unirest.delete(url);
  }

  public static HttpRequestWithBody put(String url, String name) {
    return Unirest.put(url);
  }

  //  public static GetRequest get(@NonNls String url) {
  //    return Unirest.get(url);
  //  }
  //
  //  public static HttpRequestWithBody post(@NonNls String url) {
  //    return Unirest.post(url);
  //  }
  //
  //  public static HttpRequestWithBody patch(String url) {
  //    return Unirest.patch(url);
  //  }
  //
  //  public static HttpRequestWithBody delete(String url) {
  //    return Unirest.delete(url);
  //  }
  //
  //  public static HttpRequestWithBody put(String url) {
  //    return Unirest.put(url);
  //  }
}
