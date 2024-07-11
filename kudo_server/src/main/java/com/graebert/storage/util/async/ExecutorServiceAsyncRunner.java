package com.graebert.storage.util.async;

import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.util.Utils;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ExecutorServiceAsyncRunner implements AsyncRunner {
  public static final long ASYNC_TIMEOUT = 120;

  private static final Logger LOGGER = LogManager.getRootLogger();
  private final ExecutorService executorService;
  private final OperationGroup operationGroup;
  private final Entity segment;
  private final Message<?> message;
  private String asyncName = null;

  public ExecutorServiceAsyncRunner(
      ExecutorService executorService,
      OperationGroup operationGroup,
      Entity segment,
      Message<?> message) {
    this.executorService = executorService;
    this.operationGroup = operationGroup;
    this.segment = segment;
    this.message = message;
  }

  public ExecutorServiceAsyncRunner withName(String asyncName) {
    this.asyncName = asyncName;
    return this;
  }

  public void run(Consumer<Segment> consumer) {
    String messageAddress = Objects.nonNull(message) ? message.address() : asyncName;

    Thread asyncThread = new Thread(() -> {
      Segment blockingSegment;
      if (Objects.nonNull(segment)) {
        blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
      } else {
        blockingSegment = XRayManager.createIndependentStandaloneSegment(operationGroup, asyncName);
      }
      if (Utils.isStringNotNullOrEmpty(asyncName)) {
        XRayEntityUtils.putMetadata(blockingSegment, XrayField.ASYNC_NAME, asyncName);
      }
      Promise<Void> asyncPromise = Promise.promise();
      try {
        long millis = System.currentTimeMillis();
        java.util.concurrent.Future<?> future = executorService.submit(() -> {
          java.util.concurrent.CompletableFuture<Integer> innerFuture = new CompletableFuture<>();
          blockingSegment.run(() -> {
            consumer.accept(blockingSegment);
            innerFuture.complete(0);
          });
          try {
            return innerFuture.get(ASYNC_TIMEOUT, TimeUnit.SECONDS);
          } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
          }
        });
        future.get(ASYNC_TIMEOUT, TimeUnit.SECONDS);
        millis = System.currentTimeMillis() - millis;
        if (!blockingSegment.isEmitted()) {
          blockingSegment.putMetadata("time", millis);
        }
      } catch (Throwable err) {
        XRayEntityUtils.addException(blockingSegment, err);
        asyncPromise.fail(err);
      } finally {
        XRayManager.endSegment(blockingSegment);
      }
      addThreadErrorHandler(asyncPromise, messageAddress);
    });
    asyncThread.start();
  }

  public void runWithoutSegment(Runnable runnable) {
    String messageAddress = Objects.nonNull(message) ? message.address() : asyncName;

    Thread asyncThread = new Thread(() -> {
      Promise<Void> asyncPromise = Promise.promise();
      try {
        java.util.concurrent.Future<?> future = executorService.submit(() -> {
          java.util.concurrent.CompletableFuture<Integer> innerFuture = new CompletableFuture<>();
          runnable.run();
          innerFuture.complete(0);
          try {
            return innerFuture.get(ASYNC_TIMEOUT, TimeUnit.SECONDS);
          } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
          }
        });
        future.get(ASYNC_TIMEOUT, TimeUnit.SECONDS);
      } catch (Throwable err) {
        asyncPromise.fail(err);
      }
      addThreadErrorHandler(asyncPromise, messageAddress);
    });
    asyncThread.start();
  }

  private void addThreadErrorHandler(Promise<Void> promise, String messageAddress) {
    promise.future().onFailure(cause -> {
      if (Objects.nonNull(cause)) {
        if (cause instanceof TimeoutException) {
          LOGGER.warn(String.format(
              "%s Timeout for message address: %s", this.getPrefixErrorString(), messageAddress));
        } else {
          LOGGER.error(String.format(
              "%s Error occurred for message address: %s - %s. \nStack trace: %s",
              this.getPrefixErrorString(),
              messageAddress,
              cause,
              ExceptionUtils.getStackTrace(cause)));
        }
      }
    });
  }

  private String getPrefixErrorString() {
    String errorPrefix = "[ ASYNC ]";
    if (Utils.isStringNotNullOrEmpty(asyncName)) {
      errorPrefix = errorPrefix.concat(" name: (" + asyncName + ") |");
    }
    return errorPrefix;
  }
}
