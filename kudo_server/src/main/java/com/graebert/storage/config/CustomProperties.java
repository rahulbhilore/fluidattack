package com.graebert.storage.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

public class CustomProperties {
  private String host;
  private int serverPort;
  private Boolean enterprise;
  private String revision;
  private int maxUploadSize;
  private String securityHeaderSchema;
  private String securityCookieSchema;
  private Boolean newSessionWorkflow;
  private int rateLimit;
  private Boolean rateLimiterEnabled;
  private Boolean mountApi;
  private String domain;
  private String licensing;
  private String dynamoDBPrefix;
  private String uiUrl;
  private String websocketUrl;
  private String downloadRootFolders;

  private final String emptyString = "";

  public String getHost() {
    return (String) getPropertyValue(host);
  }

  public void setHost(String host) {
    this.host = host;
  }

  public Boolean getEnterprise() {
    return (Boolean) getPropertyValue(enterprise);
  }

  public void setEnterprise(Boolean enterprise) {
    this.enterprise = enterprise;
  }

  public String getRevision() {
    return (String) getPropertyValue(revision);
  }

  public void setRevision(String revision) {
    this.revision = revision;
  }

  public int getMaxUploadSize() {
    return (int) getPropertyValue(maxUploadSize);
  }

  public void setMaxUploadSize(int maxUploadSize) {
    this.maxUploadSize = maxUploadSize;
  }

  public String getSecurityHeaderSchema() {
    return (String) getPropertyValue(securityHeaderSchema);
  }

  public void setSecurityHeaderSchema(String securityHeaderSchema) {
    this.securityHeaderSchema = securityHeaderSchema;
  }

  public String getSecurityCookieSchema() {
    return (String) getPropertyValue(securityCookieSchema);
  }

  public void setSecurityCookieSchema(String securityCookieSchema) {
    this.securityCookieSchema = securityCookieSchema;
  }

  public Boolean getNewSessionWorkflow() {
    return (Boolean) getPropertyValue(true, false, newSessionWorkflow);
  }

  public void setNewSessionWorkflow(Boolean newSessionWorkflow) {
    this.newSessionWorkflow = newSessionWorkflow;
  }

  public int getRateLimit() {
    return (int) getPropertyValue(rateLimit);
  }

  public void setRateLimit(int rateLimit) {
    this.rateLimit = rateLimit;
  }

  public Boolean getRateLimiterEnabled() {
    return (Boolean) getPropertyValue(rateLimiterEnabled);
  }

  public void setRateLimiterEnabled(Boolean rateLimiterEnabled) {
    this.rateLimiterEnabled = rateLimiterEnabled;
  }

  public Boolean getMountApi() {
    return (Boolean) getPropertyValue(mountApi);
  }

  public void setMountApi(Boolean mountApi) {
    this.mountApi = mountApi;
  }

  public String getDomain() {
    return (String) getPropertyValue(true, emptyString, domain);
  }

  public void setDomain(String domain) {
    this.domain = domain;
  }

  public String getLicensing() {
    return (String) getPropertyValue(licensing);
  }

  public void setLicensing(String licensing) {
    this.licensing = licensing;
  }

  public String getDynamoDBPrefix() {
    return (String) getPropertyValue(dynamoDBPrefix);
  }

  public void setDynamoDBPrefix(String dynamoDBPrefix) {
    this.dynamoDBPrefix = dynamoDBPrefix;
  }

  public String getUiUrl() {
    return (String) getPropertyValue(uiUrl);
  }

  public void setUiUrl(String uiUrl) {
    this.uiUrl = uiUrl;
  }

  public String getDownloadRootFolders() {
    return (String) getPropertyValue(true, emptyString, downloadRootFolders);
  }

  public JsonObject getDownloadRootFoldersAsJson() {
    String jsonAsString = getDownloadRootFolders();
    return Objects.nonNull(jsonAsString) ? new JsonObject(jsonAsString) : new JsonObject();
  }

  public void setDownloadRootFolders(String downloadRootFolders) {
    this.downloadRootFolders = downloadRootFolders;
  }

  public int getServerPort() {
    return (int) getPropertyValue(true, 8080, serverPort);
  }

  public void setServerPort(int serverPort) {
    this.serverPort = serverPort;
  }

  public String getWebsocketUrl() {
    return websocketUrl;
  }

  public void setWebsocketUrl(String websocketUrl) {
    this.websocketUrl = websocketUrl;
  }

  private Object getPropertyValue(Object originalValue) {
    return getPropertyValue(false, null, originalValue);
  }

  private Object getPropertyValue(boolean optional, Object defaultValue, Object originalValue) {
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    if (stackTrace.length < 3) {
      throw new IllegalArgumentException("Could not get property in config");
    }
    String methodName = stackTrace[2].getMethodName();
    if (methodName.equals("getPropertyValue")) {
      methodName = stackTrace[3].getMethodName();
    }
    String fieldName = null;
    if (Utils.isStringNotNullOrEmpty(methodName)) {
      fieldName = methodName.substring("get".length());
    }
    try {
      if (!Utils.isStringNotNullOrEmpty(fieldName)) {
        throw new NoSuchFieldException();
      }
      String deCapitalizeField =
          Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
      this.getClass().getDeclaredField(deCapitalizeField);
    } catch (NoSuchFieldException e) {
      throw new IllegalArgumentException("Property \"" + fieldName + "\" not found in config");
    }
    if (optional) {
      if (Objects.nonNull(originalValue)) {
        return originalValue;
      } else {
        if (Objects.isNull(defaultValue)) {
          throw new IllegalArgumentException(
              "No default value assigned for the property \"" + fieldName + "\"");
        }
        return defaultValue;
      }
    } else {
      if (Objects.nonNull(originalValue)) {
        return originalValue;
      } else {
        throw new IllegalArgumentException(fieldName + " must be specified in config");
      }
    }
  }

  public static CustomProperties fromJson(JsonObject config) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper.readValue(config.encode(), CustomProperties.class);
  }
}
