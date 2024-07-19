package com.graebert.storage.main;

import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.VertxOptions;

public class Launcher extends io.vertx.core.Launcher {

  public static void main(String[] args) {
    new Launcher().dispatch(args);
  }

  public void beforeStartingVertx(VertxOptions options) {
    updateVertxOptions(options);
    DynamoBusModBase.log.info("Vertx options updated before starting the server");
  }

  public static void updateVertxOptions(VertxOptions options) {
    // to handle the non-blocking event loop tasks (30 secs)
    options.setMaxEventLoopExecuteTime(VertxOptions.DEFAULT_MAX_EVENT_LOOP_EXECUTE_TIME * 15);

    // checks event loop blocking status after every 60 secs
    options.setBlockedThreadCheckInterval(VertxOptions.DEFAULT_BLOCKED_THREAD_CHECK_INTERVAL * 60);

    // to handle the blocking/async tasks (90 secs)
    options.setMaxWorkerExecuteTime(90L * 1000 * 1000000);
  }

  protected String getMainVerticle() {
    return "com.graebert.storage.main.Server";
  }
}
