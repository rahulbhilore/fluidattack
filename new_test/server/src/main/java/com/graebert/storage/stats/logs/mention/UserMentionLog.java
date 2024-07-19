package com.graebert.storage.stats.logs.mention;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.storage.Users;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

public class UserMentionLog extends BasicLog {

  private final String organizationId;
  private final String annotationId;
  private final String commentId;
  private final String device;
  private final String mentionedUserId;
  private final String annotationType;
  private String mentionedUserEmail;
  private String mentionedUserOrganizationId;

  public UserMentionLog(
      String userId,
      String objectId,
      StorageType storageType,
      String email,
      long timestamp,
      boolean isActive,
      String device,
      String organizationId,
      String annotationId,
      String commentId,
      String mentionedUserId,
      String annotationType) {
    super(userId, objectId, storageType, email, timestamp, isActive);
    this.organizationId = organizationId;
    this.annotationId = annotationId;
    this.device = device;
    this.annotationType = annotationType;
    this.commentId = commentId;
    this.mentionedUserId = mentionedUserId;

    Item mentionedUser = getMentionedUserInfo(mentionedUserId);
    if (Objects.nonNull(mentionedUser)) {
      if (mentionedUser.hasAttribute(Field.ORGANIZATION_ID.getName())) {
        this.mentionedUserOrganizationId = mentionedUser.getString(Field.ORGANIZATION_ID.getName());
      }
      if (mentionedUser.hasAttribute(Field.USER_EMAIL.getName())) {
        this.mentionedUserEmail = mentionedUser.getString(Field.USER_EMAIL.getName());
      }
      return;
    }
    this.mentionedUserOrganizationId = null;
    this.mentionedUserEmail = null;
  }

  private Item getMentionedUserInfo(String mentionedUserId) {
    return Users.getUserById(mentionedUserId);
  }

  @Override
  public JsonObject toJson() {
    return super.toJson()
        .put("organizationid", organizationId)
        .put("annotationid", annotationId)
        .put("annotationtype", annotationType)
        .put("commentid", commentId)
        .put(Field.DEVICE.getName(), device)
        .put("mentioneduserid", mentionedUserId)
        .put("mentioneduserorganizationid", mentionedUserOrganizationId)
        .put("mentioneduseremail", mentionedUserEmail);
  }

  @Override
  public void sendToServer() {
    AWSKinesisClient.putRecord(this, AWSKinesisClient.mentionStream);
  }
}
