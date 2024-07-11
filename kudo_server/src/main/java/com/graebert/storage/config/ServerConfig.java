package com.graebert.storage.config;

public class ServerConfig {
  public static final int UNIREST_VERTICALS = 8;
  // Box, Onedrive, Onshape, Auth, Websockets, Users, Hancom, Hancom staging
  public static final int UNIREST_UNIQUE_HOSTS = 2;
  public static int WORKER_POOL_SIZE = 100;
  public static int WORKER_INSTANCE_COUNT = 100;
  // Certain routes are shared by multiple verticles
  public static int UNIREST_CONNECTION_TIMEOUT = 7000; // 7 seconds to establish connection
  public static int UNIREST_SOCKET_TIMEOUT = 25000; // 25 seconds to receive data
  public static int UNIREST_CONNECTION_REQUEST_TIMEOUT = 10000;
  // 10 seconds to get request from pool

  private Properties properties;
  private CustomProperties customProperties;

  public static int getUnirestConcurrencyMax() {
    return WORKER_INSTANCE_COUNT * UNIREST_VERTICALS * UNIREST_UNIQUE_HOSTS;
  }

  public static int getUnirestConcurrencyMaxPerRoute() {
    return WORKER_INSTANCE_COUNT * UNIREST_UNIQUE_HOSTS;
  }

  public static int getWebDAVConcurrencyMax() {
    return WORKER_INSTANCE_COUNT;
  }

  public static int getWebDAVConcurrencyPerRoute() {
    return WORKER_INSTANCE_COUNT;
  }

  public static int getUnirestConnectionTimeout() {
    return UNIREST_CONNECTION_TIMEOUT;
  }

  public static int getUnirestSocketTimeout() {
    return UNIREST_SOCKET_TIMEOUT;
  }

  public static int getUnirestConnectionRequestTimeout() {
    return UNIREST_CONNECTION_REQUEST_TIMEOUT;
  }

  public void init(Properties properties) {
    this.properties = properties;
  }

  public void initCustom(CustomProperties customProperties) {
    this.customProperties = customProperties;
  }

  public Properties getProperties() {
    return properties;
  }

  public CustomProperties getCustomProperties() {
    return customProperties;
  }
}
