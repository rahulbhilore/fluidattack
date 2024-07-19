package com.graebert.storage.util;

public class SimpleTimer {
  private long startTime;

  public SimpleTimer() {
    this.startTime = System.currentTimeMillis();
  }

  public void report(String marker) {
    System.out.println(
        marker + " Since last: " + (System.currentTimeMillis() - this.startTime) + "ms");
    this.startTime = System.currentTimeMillis();
  }

  public void report() {
    report("");
  }
}
