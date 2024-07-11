package com.graebert.storage.stats.beans;

import java.net.MalformedURLException;
import java.net.URL;

public class HttpRequest {
  private int status = -1;
  private final long startTime;
  private long endTime = -1;
  private final String url;

  public HttpRequest(String url, long timestamp) {
    this.url = url;
    this.startTime = timestamp;
  }

  public void finishCall(int statusCode, long timestamp) {
    this.status = statusCode;
    this.endTime = timestamp;
  }

  public String getUrl() {
    return url;
  }

  public int getStatus() {
    return status;
  }

  public long getExecutionTime() {
    return endTime - startTime;
  }

  public long getStartTime() {
    return startTime;
  }

  public long getEndTime() {
    return endTime;
  }

  public String getDomain() {
    try {
      return new URL(this.url).getHost();
    } catch (MalformedURLException e) {
      e.printStackTrace();
      return this.url;
    }
  }
}
