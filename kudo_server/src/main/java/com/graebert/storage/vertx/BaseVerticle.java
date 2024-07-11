package com.graebert.storage.vertx;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.util.message.MessageUtils;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.dropwizard.MetricsService;
import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NonNls;

/**
 * Created by robert on 3/1/2017.
 */
public class BaseVerticle extends AbstractVerticle {
  protected static final String emptyString = Strings.EMPTY;

  public static HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  protected static Logger log = LogManager.getRootLogger();

  @NonNls
  protected EventBus eb;

  protected MetricsService metricsService;
  protected WorkerExecutor executor;
  protected CircuitBreaker breaker;
  protected ExecutorService executorService;
  protected HttpClient client;

  @Override
  public void start() throws Exception {
    super.start();
    eb = vertx.eventBus();
    metricsService = MetricsService.create(vertx);
    executorService = Executors.newCachedThreadPool();
    client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  protected boolean isAddressAvailable(String address) {
    String addr = address.substring(0, address.lastIndexOf("."));
    return vertx.sharedData().getLocalMap("verticle.addresses").get(addr) != null;
  }

  /**
   * Send "send" event to consumer, with no response expectation
   *
   * @param segment            - Entity to merge inside request
   * @param address            - address of consumer
   * @param bufferOfJsonToSend - object to send
   * @param <T>                - JsonObject or Message to send
   * @return EventBus
   */
  public <T> EventBus eb_send(Entity segment, String address, T bufferOfJsonToSend) {
    if (isAddressAvailable(address)) {
      if (bufferOfJsonToSend instanceof JsonObject) {
        return eb.send(address, MessageUtils.putSegmentDataToJsonObject(segment, (JsonObject)
            bufferOfJsonToSend));
      } else if (bufferOfJsonToSend instanceof Buffer) {
        return eb.send(
            address, MessageUtils.putSegmentDataToBuffer(segment, (Buffer) bufferOfJsonToSend));
      }
    }

    return null;
  }

  /**
   * Send "request" event to consumer, and expect response
   *
   * @param segment            - Entity to merge inside request
   * @param address            - address of consumer
   * @param bufferOfJsonToSend - object to send
   * @param replyHandler       - reply handler
   * @param <T>                - JsonObject or Message to send
   * @param <K>                - JsonObject or Message to receive
   * @return EventBus
   */
  public <T, K> EventBus eb_request(
      Entity segment,
      String address,
      T bufferOfJsonToSend,
      Handler<AsyncResult<Message<K>>> replyHandler) {
    if (isAddressAvailable(address)) {
      DeliveryOptions options = getDeliveryOptionsForAddress(address);
      if (bufferOfJsonToSend instanceof JsonObject) {
        return eb.request(
            address,
            MessageUtils.putSegmentDataToJsonObject(segment, (JsonObject) bufferOfJsonToSend),
            options,
            replyHandler);
      } else if (bufferOfJsonToSend instanceof Buffer) {
        return eb.request(
            address,
            MessageUtils.putSegmentDataToBuffer(segment, (Buffer) bufferOfJsonToSend),
            options,
            replyHandler);
      }
    }

    replyHandler.handle(Future.failedFuture(
        new Exception(String.format("The %s is not available on this instance", address))));

    return null;
  }

  private DeliveryOptions getDeliveryOptionsForAddress(String address) {
    String eventAddress;
    int dotIndex = address.indexOf(".");
    if (dotIndex != -1) {
      eventAddress = address.substring(dotIndex + 1);
    } else {
      eventAddress = address;
    }
    DeliveryOptions deliveryOptions = new DeliveryOptions();
    long toMinutes = 60 * 1000;
    switch (eventAddress) {
      case "uploadFile":
      case "uploadVersion":
        {
          deliveryOptions.setSendTimeout(5 * toMinutes);
        }
        break;
      case "getFolderContent":
        {
          deliveryOptions.setSendTimeout(3 * toMinutes);
        }
        break;
      case "globalSearch":
        {
          deliveryOptions.setSendTimeout(2 * toMinutes);
        }
        break;
      case "clone":
        {
          deliveryOptions.setSendTimeout(toMinutes);
        }
        break;
    }
    return deliveryOptions;
  }
}
