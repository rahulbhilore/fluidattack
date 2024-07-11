package com.graebert.storage.integration.webdav;

public class SardineFactory {
  /**
   * Pass in a HTTP Auth username/password for being used with all
   * connections
   *
   * @param username Use in authentication header credentials
   * @param password Use in authentication header credentials
   * @param useXray
   */
  public static SardineImpl2 begin(String username, String password, boolean useXray) {
    return new SardineImpl2(username, password, useXray);
  }
}
