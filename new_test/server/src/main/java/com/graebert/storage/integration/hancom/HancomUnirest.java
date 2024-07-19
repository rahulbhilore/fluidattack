package com.graebert.storage.integration.hancom;

import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.xray.AWSXRayUnirest;
import com.graebert.storage.stats.beans.HttpRequest;
import com.graebert.storage.stats.beans.HttpRequests;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import kong.unirest.GetRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.UniMetric;
import kong.unirest.Unirest;
import kong.unirest.UnirestInstance;
import org.jetbrains.annotations.NonNls;

public class HancomUnirest {

  private static final MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
  private static final Map<String, HttpRequests> statsBeans = new HashMap<>();
  private final UnirestInstance instance = Unirest.spawnInstance();

  public HancomUnirest(boolean xRay, JsonObject proxy) {
    UniMetric uniMetric = request -> {
      HttpRequest call = new HttpRequest(request.getUrl(), System.currentTimeMillis());
      String domain = call.getDomain();
      HttpRequests requestsStats;
      if (statsBeans.containsKey(domain)) {
        requestsStats = statsBeans.get(domain);
      } else {
        requestsStats = new HttpRequests();
        statsBeans.put(domain, requestsStats);
      }
      HttpRequests finalRequestsStats = requestsStats;
      try {
        ObjectName objectName = new ObjectName("com.graebert.fluorine.main.http:name=" + domain);
        if (!platformMBeanServer.isRegistered(objectName)) {
          platformMBeanServer.registerMBean(finalRequestsStats, objectName);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
      finalRequestsStats.openedConnection();
      return (responseSummary, exception) -> {
        if (responseSummary != null) {
          call.finishCall(responseSummary.getStatus(), System.currentTimeMillis());
          finalRequestsStats.closedConnection(call);
        }
      };
    };
    if (xRay) {
      if (proxy != null
          && proxy.containsKey(Field.URL.getName())
          && Utils.isStringNotNullOrEmpty(proxy.getString(Field.URL.getName()))) {
        instance
            .config()
            .proxy(
                proxy.getString(Field.URL.getName()),
                proxy.getInteger("port"),
                proxy.getString("login"),
                proxy.getString("pass"))
            .instrumentWith(uniMetric)
            .concurrency(
                ServerConfig.getUnirestConcurrencyMax(),
                ServerConfig.getUnirestConcurrencyMaxPerRoute())
            .asyncClient(AWSXRayUnirest.asyncClient);
      } else {
        instance
            .config()
            .instrumentWith(uniMetric)
            .concurrency(
                ServerConfig.getUnirestConcurrencyMax(),
                ServerConfig.getUnirestConcurrencyMaxPerRoute())
            .asyncClient(AWSXRayUnirest.asyncClient);
      }
    } else {
      if (proxy != null
          && proxy.containsKey(Field.URL.getName())
          && Utils.isStringNotNullOrEmpty(proxy.getString(Field.URL.getName()))) {
        instance
            .config()
            .proxy(
                proxy.getString(Field.URL.getName()),
                proxy.getInteger("port"),
                proxy.getString("login"),
                proxy.getString("pass"))
            .instrumentWith(uniMetric)
            .connectTimeout(ServerConfig.getUnirestConnectionTimeout())
            .socketTimeout(ServerConfig.getUnirestSocketTimeout())
            .concurrency(
                ServerConfig.getUnirestConcurrencyMax(),
                ServerConfig.getUnirestConcurrencyMaxPerRoute())
            .asyncClient(AWSXRayUnirest.asyncClient);
      } else {
        instance
            .config()
            .instrumentWith(uniMetric)
            .connectTimeout(ServerConfig.getUnirestConnectionTimeout())
            .socketTimeout(ServerConfig.getUnirestSocketTimeout())
            .concurrency(
                ServerConfig.getUnirestConcurrencyMax(),
                ServerConfig.getUnirestConcurrencyMaxPerRoute())
            .asyncClient(AWSXRayUnirest.asyncClient);
      }
    }
  }

  public void shutDown() {
    instance.shutDown();
  }

  public GetRequest get(@NonNls String url, String name) {
    return instance.get(url);
  }

  public HttpRequestWithBody post(@NonNls String url, String name) {
    return instance.post(url);
  }

  public HttpRequestWithBody delete(String url, String name) {
    return instance.delete(url);
  }

  public HttpRequestWithBody put(String url, String name) {
    return instance.put(url);
  }

  public GetRequest get(@NonNls String url) {
    return instance.get(url);
  }

  public HttpRequestWithBody post(@NonNls String url) {
    return instance.post(url);
  }

  public HttpRequestWithBody delete(String url) {
    return instance.delete(url);
  }

  public HttpRequestWithBody put(String url) {
    return instance.put(url);
  }
}
