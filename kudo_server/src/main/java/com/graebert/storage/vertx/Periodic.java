package com.graebert.storage.vertx;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.thumbnails.ThumbnailsManager;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.json.JsonObject;

/**
 * Created by maria.baboshina on 23-Feb-17.
 */
public class Periodic extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.PERIODIC;

  @Override
  public void start() throws Exception {
    super.start();
    executor = vertx.createSharedWorkerExecutor("vert.x-new-internal-blocking-periodic");

    vertx.setPeriodic(1000 * 60, event -> {
      // not sure we really want to do this on every host and rather do that centrally.
      try {
        Entity segment = XRayManager.createIndependentStandaloneSegment(
            operationGroup, "updateSessionsCountSessions");
        gauge("conc_users", Sessions.countActiveSessions(null));
        gauge("conc_users_browser", Sessions.countActiveSessions(AuthManager.ClientType.BROWSER));
        gauge(
            "conc_users_commander", Sessions.countActiveSessions(AuthManager.ClientType.COMMANDER));
        XRayManager.endSegment(segment);
      } catch (Exception e) {
        log.error(LogPrefix.getLogPrefix(), e);
      }
    });

    vertx.setPeriodic(1000 * 25, event -> {
      eb.request(ThumbnailsManager.address + ".checkAndPostChunksToSQS", new JsonObject());
    });

    vertx.setPeriodic(1000 * 30, event -> {
      eb.request(ThumbnailsManager.address + ".handleIncomingMessages", new JsonObject());
    });

    vertx.setPeriodic(1000 * 14, event -> {
      eb.request(ThumbnailsManager.address + ".handleThumbnailChunksFromSQS", new JsonObject());
    });

    // test
    vertx.setPeriodic(1000 * 10, event -> {
      long heapSize = Runtime.getRuntime().totalMemory();

      // Get maximum size of heap in bytes. The heap cannot grow beyond this size.// Any attempt
      // will result in an OutOfMemoryException.
      long heapMaxSize = Runtime.getRuntime().maxMemory();

      // Get amount of free memory within the heap in bytes. This size will increase // after
      // garbage collection and decrease as new objects are created.
      long heapFreeSize = Runtime.getRuntime().freeMemory();

      log.info("HeapMaxSize => " + heapMaxSize + "\n" + "heapSize => "
          + heapSize + "\n" + "heapFreeSize => "
          + heapFreeSize + "\n");
    });
  }
}
