package com.graebert.storage.mail;

import com.amazonaws.xray.entities.Entity;
import com.graebert.storage.util.Field;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public class MessagingManager extends DynamoBusModBase { // todo maybe later support queues of msgs
  private static final OperationGroup operationGroup = OperationGroup.MESSAGING_MANAGER;

  @NonNls
  public static final String address = "messagingmanager";

  protected static Logger log = LogManager.getRootLogger();

  public MessagingManager() {}

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".send", this::doSend);
    eb.consumer(address + ".delete", this::doDelete);
    eb.consumer(address + ".read", this::doRead);
  }

  private void doRead(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String msgId = getRequiredString(segment, "msgId", message, message.body());
    if (userId == null || msgId == null) {
      return;
    }

    sendOK(segment, message, mills);
  }

  private void doDelete(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String userId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String msgId = getRequiredString(segment, "msgId", message, message.body());
    if (userId == null || msgId == null) {
      return;
    }
    sendOK(segment, message, mills);
  }

  private void doSend(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    String senderId = getRequiredString(segment, Field.USER_ID.getName(), message, message.body());
    String receiverId = getRequiredString(segment, "receiver", message, message.body());
    String msg = getRequiredString(segment, "msg", message, message.body());
    if (senderId == null || receiverId == null || msg == null) {
      return;
    }

    sendOK(segment, message, mills);
  }
}
