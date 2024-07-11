package com.graebert.storage.stats.logs.block;

import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

/**
 * Kinesis log class for Blocks.
 */
public class BlockLog extends BasicLog {
  private final BlockActions actionType;
  private final long actionTime;
  private final String sessionId;
  private final String userId;
  private final String email;
  private final String device;

  /**
   * Constructor for Block library logs for Kinesis.
   *
   * @param userId     action initiator
   * @param email      user's email
   * @param timestamp  time when sent
   * @param actionType what action happened
   * @param actionTime when action occured
   * @param sessionId  user's session
   * @param device     user's device. {@link com.graebert.storage.security.AuthManager.ClientType}
   */
  public BlockLog(
      String userId,
      String email,
      long timestamp,
      BlockActions actionType,
      long actionTime,
      String sessionId,
      String device) {
    super(userId, null, null, email, timestamp, true);

    this.actionType = actionType;
    this.actionTime = actionTime;
    this.sessionId = sessionId;
    this.device = device;
    this.userId = userId;
    this.email = email;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject()
        .put("actiontype", getEnumValue(actionType))
        .put("actiontime", actionTime)
        .put("sessionid", sessionId)
        .put(Field.DEVICE.getName(), device)
        .put("userid", this.userId)
        .put(Field.EMAIL.getName(), this.email);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.blockLibraryStream);
  }
}
