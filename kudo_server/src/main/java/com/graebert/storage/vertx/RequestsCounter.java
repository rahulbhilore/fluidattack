package com.graebert.storage.vertx;

import java.util.concurrent.atomic.AtomicInteger;

public class RequestsCounter {
  private static final AtomicInteger count = new AtomicInteger();

  public static void increment() {
    count.incrementAndGet();
  }

  public static void decrement() {
    count.decrementAndGet();
  }

  public static int get() {
    return count.get();
  }
}
