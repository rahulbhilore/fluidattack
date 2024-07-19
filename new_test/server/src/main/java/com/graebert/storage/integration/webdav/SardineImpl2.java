package com.graebert.storage.integration.webdav;

import com.amazonaws.xray.proxies.apache.http.HttpClientBuilder;
import com.github.sardine.DavResource;
import com.github.sardine.impl.SardineImpl;
import com.github.sardine.impl.handler.MultiStatusResponseHandler;
import com.github.sardine.impl.methods.HttpSearch;
import com.github.sardine.model.Multistatus;
import com.github.sardine.model.Response;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.stats.beans.HttpRequest;
import com.graebert.storage.stats.beans.HttpRequests;
import com.graebert.storage.util.Field;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.StringEntity;

public class SardineImpl2 extends SardineImpl {

  private static final RequestConfig config = RequestConfig.custom()
      .setConnectTimeout(ServerConfig.getUnirestConnectionTimeout())
      .setConnectionRequestTimeout(ServerConfig.getUnirestConnectionRequestTimeout())
      .setSocketTimeout(ServerConfig.getUnirestSocketTimeout())
      .build();
  private static final MBeanServer platformMBeanServer = ManagementFactory.getPlatformMBeanServer();
  private static final Map<String, HttpRequests> statsBeans = new HashMap<>();
  private static final HttpRequestInterceptor requestInterceptor = (request, context) -> {
    String target = ((HttpRequestWrapper) request).getTarget().toString();
    String url = ((HttpRequestWrapper) request).getURI().toString();
    context.setAttribute(
        "logData",
        new JsonObject()
            .put(Field.URL.getName(), target + url)
            .put("startTime", System.currentTimeMillis()));
  };
  private static final HttpResponseInterceptor responseInterceptor = (response, context) -> {
    int statusCode = response.getStatusLine().getStatusCode();
    JsonObject logData = (JsonObject) context.getAttribute("logData");
    HttpRequest httpRequest =
        new HttpRequest(logData.getString(Field.URL.getName()), logData.getLong("startTime"));
    httpRequest.finishCall(statusCode, System.currentTimeMillis());
    String domain = httpRequest.getDomain();
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
    finalRequestsStats.closedConnection(httpRequest);
  };
  private static final org.apache.http.impl.client.HttpClientBuilder httpClient =
      org.apache.http.impl.client.HttpClientBuilder.create()
          .setDefaultRequestConfig(config)
          .setMaxConnTotal(ServerConfig.getWebDAVConcurrencyMax())
          .setMaxConnPerRoute(ServerConfig.getWebDAVConcurrencyPerRoute())
          .addInterceptorFirst(requestInterceptor)
          .addInterceptorLast(responseInterceptor);

  private static final HttpClientBuilder xrayHttpClient =
      (HttpClientBuilder) HttpClientBuilder.create()
          .setRecorder(XRayManager.getRecorder())
          .setDefaultRequestConfig(config)
          .setMaxConnTotal(ServerConfig.getWebDAVConcurrencyMax())
          .setMaxConnPerRoute(ServerConfig.getWebDAVConcurrencyPerRoute())
          .addInterceptorFirst(requestInterceptor)
          .addInterceptorLast(responseInterceptor);

  /**
   * @param username Use in authentication header credentials
   * @param password Use in authentication header credentials
   * @param useXray is xray enabled here on server
   */
  SardineImpl2(String username, String password, boolean useXray) {
    super(useXray ? xrayHttpClient : httpClient, username, password);
  }

  public void put(String url, byte[] data, String contentType, boolean expectContinue)
      throws IOException {
    ByteArrayEntity entity = new ByteArrayEntity(data);
    this.put(url, entity, contentType, expectContinue);
  }

  public List<DavResource> search(String url, String query, String username) throws IOException {
    if (url.endsWith("/files/") || url.contains("remote.php")) {
      // nextcloud
      String nextCloudSearch =
          "<d:searchrequest xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">"
              + "  <d:basicsearch>"
              + "     <d:select>"
              + "         <d:prop>"
              + "             <oc:id/>"
              + "             <oc:fileid/>"
              + "             <oc:size/>"
              + "             <d:displayname/>"
              + "             <d:getcontenttype/>"
              + "             <d:getetag/>"
              + "             <d:getlastmodified/>"
              + "             <d:getcontentlength/>"
              + "         </d:prop>"
              + "     </d:select>"
              + "     <d:from>"
              + "         <d:scope>"
              + "             <d:href>/files/USERNAME</d:href>"
              + "             <d:depth>infinity</d:depth>"
              + "         </d:scope>"
              + "     </d:from>"
              + "     <d:where>"
              + "         <d:like>"
              + "             <d:prop>"
              + "                 <d:displayname/>"
              + "             </d:prop>"
              + "             <d:literal>%SEARCH%</d:literal>"
              + "         </d:like>"
              + "     </d:where>"
              + "     <d:orderby/>"
              + "  </d:basicsearch>"
              + "</d:searchrequest>";

      String finalQuery = nextCloudSearch.replace("SEARCH", query).replace("USERNAME", username);

      // DK: not sure where it's coming from, but leaving for now
      if (url.contains("/files/")) {
        url = url.substring(0, url.lastIndexOf("files/"));
      }

      HttpEntityEnclosingRequestBase search = new HttpSearch(url);
      search.setEntity(new StringEntity(finalQuery, "UTF-8"));
      Multistatus multistatus = this.execute(search, new MultiStatusResponseHandler());
      List<Response> responses = multistatus.getResponse();
      List<DavResource> resources = new ArrayList<>(responses.size());
      for (Response response : responses) {
        try {
          resources.add(new DavResource(response));
        } catch (URISyntaxException ignored) {
        }
      }
      return resources;
    } else {
      return super.search(url, "", query);
    }
  }

  public List<DavResource> searchForPath(String url, String fileId, String username)
      throws IOException {
    if (url.endsWith("/files/")) {
      // nextcloud
      String nextCloudSearch =
          "<d:searchrequest xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\">"
              + "  <d:basicsearch>"
              + "     <d:select>"
              + "         <d:prop>"
              + "             <d:displayname/>"
              + "         </d:prop>"
              + "     </d:select>"
              + "     <d:from>"
              + "         <d:scope>"
              + "             <d:href>/files/USERNAME</d:href>"
              + "             <d:depth>infinity</d:depth>"
              + "         </d:scope>"
              + "     </d:from>"
              + "     <d:where>"
              + "         <d:eq>"
              + "             <d:prop>"
              + "                 <oc:fileid/>"
              + "             </d:prop>"
              + "             <d:literal>SEARCH</d:literal>"
              + "         </d:eq>"
              + "     </d:where>"
              + "     <d:orderby/>"
              + "  </d:basicsearch>"
              + "</d:searchrequest>";

      String finalQuery = nextCloudSearch.replace("SEARCH", fileId).replace("USERNAME", username);

      url = url.substring(0, url.length() - "/files/".length());
      HttpEntityEnclosingRequestBase search = new HttpSearch(url);
      search.setEntity(new StringEntity(finalQuery, "UTF-8"));
      Multistatus multistatus = this.execute(search, new MultiStatusResponseHandler());
      List<Response> responses = multistatus.getResponse();
      List<DavResource> resources = new ArrayList<>(responses.size());
      for (Response response : responses) {
        try {
          resources.add(new DavResource(response));
        } catch (URISyntaxException e) {
          // log.warning(String.format("Ignore resource with invalid URI %s",
          // response.getHref().get(0)));
        }
      }
      return resources;
    } else {
      return null;
    }
  }
}
