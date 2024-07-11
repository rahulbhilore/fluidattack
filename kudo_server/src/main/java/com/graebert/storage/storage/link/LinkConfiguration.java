package com.graebert.storage.storage.link;

import com.graebert.storage.config.ServerConfig;
import io.vertx.core.json.JsonObject;

public class LinkConfiguration {
  private final boolean isEnterprise;
  private final String uiUrl;
  private final String instanceUrl;
  private final JsonObject companyOptions;
  private final String secretKey;

  public LinkConfiguration(
      boolean isEnterprise,
      String uiUrl,
      String instanceUrl,
      JsonObject companyOptions,
      String secretKey) {
    this.isEnterprise = isEnterprise;
    this.uiUrl = uiUrl;
    this.instanceUrl = instanceUrl;
    this.companyOptions = companyOptions;
    this.secretKey = secretKey;
  }

  public boolean isEnterprise() {
    return isEnterprise;
  }

  public String getUiUrl() {
    return uiUrl;
  }

  public String getInstanceUrl() {
    return instanceUrl;
  }

  public JsonObject getCompanyOptions() {
    return companyOptions;
  }

  public String getSecretKey() {
    return secretKey;
  }

  private static LinkConfiguration instance = null;

  public static void initialize(ServerConfig config) {
    String host = config.getProperties().getHost();
    String uiUrl = config.getProperties().getUrl();

    String instanceUrl = host.contains("localhost")
        ? String.format("http://%s:%s/", host, config.getProperties().getPort())
        : uiUrl.concat("api/");

    instance = new LinkConfiguration(
        config.getProperties().getEnterprise(),
        uiUrl,
        instanceUrl,
        config.getProperties().getDefaultCompanyOptionsAsJson(),
        config.getProperties().getFluorineSecretKey());
  }

  public static LinkConfiguration getInstance() {
    return instance;
  }
}
