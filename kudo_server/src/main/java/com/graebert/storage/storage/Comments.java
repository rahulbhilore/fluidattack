package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.comment.CommentVerticle;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.mail.emails.renderers.MentionRender;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Comments extends Dataplane {
  public static final String table = TableName.COMMENTS.name;
  // private static final int maxMentionCacheSize = 10;
  // pattern to get data between all [] brackets exclusively.
  public static final Pattern textMentionPattern = Pattern.compile("(?<=\\[).*?(?=\\])");
  private static final String authorIndex = "author-index";

  public static void saveMentionedUsers(
      StorageType storageType, String fileId, List<String> users) {
    if (!Utils.isListNotNullOrEmpty(users)) return;

    users.forEach(
        user -> Mentions.updateRating(Mentions.Activity.MENTIONED, user, fileId, storageType));
  }

  /**
   * To get the list of mentioned users from the comment text
   *
   * @param text - comment text
   * @return List of mentioned user ids
   */
  public static List<String> getMentionedUsersFromText(String text) {
    List<String> users = new ArrayList<>();
    if (!Utils.isStringNotNullOrEmpty(text)) {
      return users;
    }
    Matcher m = textMentionPattern.matcher(text);
    while (m.find()) {
      String[] values = m.group().split("\\|");
      if (values.length == 2) {
        String userId = values[1].trim();
        users.add(userId);
      }
    }
    return users;
  }

  /**
   * To compare the old and new commented text and get the list of new mentioned users, if any
   *
   * @param oldText - Old comment text
   * @param newText - Updated comment text
   * @return List of new mentioned user ids
   */
  public static List<String> getNewMentionedUsersFromUpdatedText(String oldText, String newText) {
    List<String> oldMentionedUsers = getMentionedUsersFromText(oldText);
    List<String> newMentionedUsers = getMentionedUsersFromText(newText);
    return newMentionedUsers.stream()
        .filter(userId -> !oldMentionedUsers.contains(userId))
        .collect(Collectors.toList());
  }

  // both for comments and markups
  public static Iterator<Item> getAllAnnotations(
      Long timeStamp, String fileId, Boolean returnDeleted, Boolean returnedResolved, String type) {
    QuerySpec qs = new QuerySpec().withHashKey(Field.PK.getName(), type + "#" + fileId);
    StringBuilder filterExpression = new StringBuilder();
    ValueMap valueMap = new ValueMap();
    NameMap nameMap = new NameMap();

    if (timeStamp != null) {
      // we check the comment timestamp or annotation timestamp
      filterExpression.append("(tmstmp > :time OR userDeletedTmstmp > :time)");
      valueMap.withLong(":time", timeStamp);
    }
    if (returnDeleted != null && !returnDeleted) {
      if (filterExpression.length() != 0) {
        filterExpression.append(" and ");
      }
      filterExpression.append("#s <> :deleted");
      valueMap.withString(":deleted", CommentVerticle.CommentState.DELETED.name());
      nameMap.with("#s", Field.STATE.getName());
    }
    if (returnedResolved != null && !returnedResolved) {
      if (filterExpression.length() != 0) {
        filterExpression.append(" and ");
      }
      filterExpression.append("#s <> :resolved");
      valueMap.withString(":resolved", CommentVerticle.CommentState.RESOLVED.name());
      nameMap.with("#s", Field.STATE.getName());
    }
    if (filterExpression.length() != 0) {
      qs.withFilterExpression(filterExpression.toString());
    }
    if (!valueMap.isEmpty()) {
      qs.withValueMap(valueMap);
    }
    if (!nameMap.isEmpty()) {
      qs.withNameMap(nameMap);
    }
    return query(table, null, qs, DataEntityType.COMMENTS).iterator();
  }

  // both for comments and markups
  public static Item getAnnotation(String fileId, String threadId, String type) {
    return getItemFromCache(table, pk, type + "#" + fileId, sk, threadId, DataEntityType.COMMENTS);
  }

  // both for comments and markups(Add, update and delete)
  public static void saveAnnotation(Item thread) {
    putItem(
        table,
        thread.getString(Field.PK.getName()),
        thread.getString(Field.SK.getName()),
        thread,
        DataEntityType.COMMENTS);
  }

  public static void setAnnotationText(String text, String pkValue, String skValue) {
    updateAnnotation(
        new PrimaryKey(pk, pkValue, sk, skValue),
        "set #tx = :t , tmstmp = :tm",
        new ValueMap().withString(":t", text).withLong(":tm", GMTHelper.utcCurrentTime()),
        new NameMap().with("#tx", Field.TEXT.getName()));
  }

  public static void setAnnotationState(String state, String pkValue, String skValue) {
    updateAnnotation(
        new PrimaryKey(pk, pkValue, sk, skValue),
        "set #st = :s , tmstmp = :tm",
        new ValueMap().withString(":s", state).withLong(":tm", GMTHelper.utcCurrentTime()),
        new NameMap().with("#st", Field.STATE.getName()));
  }

  public static void setAnnotationTimeStamp(String pkValue, String skValue) {
    updateAnnotation(
        new PrimaryKey(pk, pkValue, sk, skValue),
        "set tmstmp = :tm",
        new ValueMap().withLong(":tm", GMTHelper.utcCurrentTime()),
        null);
  }

  private static void updateAnnotation(
      PrimaryKey primaryKey, String updateExpression, ValueMap valueMap, NameMap nameMap) {
    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(primaryKey)
        .withUpdateExpression(updateExpression)
        .withValueMap(valueMap);

    if (nameMap != null) {
      updateItemSpec.withNameMap(nameMap);
    }

    updateItem(table, updateItemSpec, DataEntityType.COMMENTS);
  }

  // Get all comments for any annotation(markup or commentThread)
  public static Iterator<Item> getAnnotationComments(Long timeStamp, Item annotation, String id) {
    ValueMap vm = new ValueMap()
        .withString(":pk", annotation.getString(Field.PK.getName()))
        .withString(":sk", id + "#");
    QuerySpec qs = new QuerySpec().withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)");

    if (timeStamp != null) {
      qs.withFilterExpression("tmstmp > :time OR userDeletedTmstmp > :time");
      vm.withLong(":time", timeStamp);
    }
    qs.withValueMap(vm);
    return query(table, null, qs, DataEntityType.COMMENTS).iterator();
  }

  // both for comments and markups (check if there is any comments in annotation)
  public static boolean checkUserCommentsForAnnotation(String threadId, String userId, String id) {
    return query(
            table,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :pk and begins_with(sk, :sk)")
                .withFilterExpression("author = :user")
                .withValueMap(new ValueMap()
                    .withString(":pk", id)
                    .withString(":sk", threadId + "#")
                    .withString(":user", userId)),
            DataEntityType.COMMENTS)
        .iterator()
        .hasNext();
  }

  public static JsonObject threadToJson(Item thread) {
    JsonObject json = new JsonObject()
        .put(Field.ID.getName(), thread.getString(Field.SK.getName()).split("#")[0])
        .put(
            Field.TITLE.getName(),
            thread.hasAttribute(Field.TITLE.getName())
                ? thread.getString(Field.TITLE.getName())
                : emptyString)
        .put(Field.STATE.getName(), thread.getString(Field.STATE.getName()))
        .put(Field.AUTHOR.getName(), thread.getString(Field.AUTHOR.getName()))
        .put(Field.TIMESTAMP.getName(), thread.getLong(Field.TIMESTAMP_SHORT.getName()))
        .put("comments", new JsonArray());
    if (thread.hasAttribute("entityHandles")) {
      json.put("entityHandles", thread.getList("entityHandles"));
    }
    if (thread.hasAttribute("spaceId")) {
      json.put("spaceId", thread.getString("spaceId"));
    }
    if (thread.hasAttribute("viewportId")) {
      json.put("viewportId", thread.getString("viewportId"));
    }
    if (thread.hasAttribute("clonedBy")) {
      json.put("isCloned", true);
    }
    return json;
  }

  public static JsonObject commentToJson(Item comment, String userId, String userAgent) {
    JsonObject userObject = Users.getUserInfo(userId);
    return getCommentJson(comment, userObject, userAgent);
  }

  public static JsonObject commentToJson(Item comment, Item user, String userAgent) {
    JsonObject userObject = Users.getAuthorInfo(user);
    return getCommentJson(comment, userObject, userAgent);
  }

  private static JsonObject getCommentJson(Item comment, JsonObject userObject, String userAgent) {
    String text =
        checkClientVersionAndUpdateComment(comment.getString(Field.TEXT.getName()), userAgent);
    JsonObject json = new JsonObject()
        .put(Field.ID.getName(), comment.getString(Field.SK.getName()).split("#")[1])
        .put(Field.AUTHOR.getName(), userObject)
        .put(Field.TIMESTAMP.getName(), comment.getLong(Field.TIMESTAMP_SHORT.getName()))
        .put(Field.CREATED.getName(), comment.getLong(Field.CREATED.getName()))
        .put("application", comment.getString(Field.DEVICE.getName()))
        .put(Field.STATE.getName(), comment.getString(Field.STATE.getName()))
        .put(Field.TEXT.getName(), text);
    if (comment.hasAttribute("loc")) {
      json.put("loc", comment.getString("loc"));
    }
    if (comment.hasAttribute("clonedBy")) {
      json.put("isCloned", true);
    }

    return json;
  }

  public static JsonObject annotationToJson(Item annotation, String userAgent) {
    if (annotation.getString(Field.PK.getName()).startsWith("comments")) {
      return Comments.threadToJson(annotation);
    }
    return markupToJson(annotation, null, userAgent);
  }

  public static JsonObject markupToJson(Item markup, Item user, String userAgent) {
    JsonObject json = new JsonObject()
        .put(Field.ID.getName(), markup.getString(Field.SK.getName()).split("#")[0])
        .put(Field.TYPE.getName(), markup.getString(Field.TYPE.getName()))
        .put(Field.STATE.getName(), markup.getString(Field.STATE.getName()))
        .put(Field.TIMESTAMP.getName(), markup.getLong(Field.TIMESTAMP_SHORT.getName()))
        .put("comments", new JsonArray());
    if (markup.hasAttribute(Field.CREATED.getName())) {
      json.put(Field.CREATED.getName(), markup.getLong(Field.CREATED.getName()));
    }
    if (markup.hasAttribute("spaceId")) {
      json.put("spaceId", markup.getString("spaceId"));
    }
    if (markup.hasAttribute("viewportId")) {
      json.put("viewportId", markup.getString("viewportId"));
    }
    if (markup.hasAttribute("clonedBy")) {
      json.put("isCloned", true);
    }
    if (markup.hasAttribute(Field.AUTHOR.getName())) {
      JsonObject userObject;
      if (user != null) {
        userObject = Users.getAuthorInfo(user);
      } else {
        userObject = Users.getUserInfo(markup.getString(Field.AUTHOR.getName()));
      }

      json.put(Field.AUTHOR.getName(), userObject);
    }

    switch (CommentVerticle.MarkupType.getType(markup.getString(Field.TYPE.getName()))) {
      case ENTITY:
        if (markup.hasAttribute("color")) {
          json.put("color", markup.getLong("color"));
        }
        if (markup.hasAttribute("geometry")) {
          json.put("geometry", markup.getString("geometry"));
        }
        break;
      case VOICENOTE:
      case PICTURENOTE:
        if (markup.hasAttribute("posx")) {
          json.put(
              "position",
              new JsonArray()
                  .add(markup.getDouble("posx"))
                  .add(markup.getDouble("posy"))
                  .add(markup.getDouble("posz")));
        }
        if (markup.hasAttribute("notes")) {
          JsonArray jsonArray = new JsonArray(markup.getJSON("notes"));
          // we should review if we are passing all the content to the client
          json.put("notes", jsonArray);
        }
        break;
      case STAMP:
        if (markup.hasAttribute("geometry")) {
          json.put("geometry", markup.getString("geometry"));
        }
        if (markup.hasAttribute("stampId")) {
          json.put("stampId", markup.getString("stampId"));
        }
        if (markup.hasAttribute(Field.TEXT.getName())) {
          String text =
              checkClientVersionAndUpdateComment(markup.getString(Field.TEXT.getName()), userAgent);
          json.put(Field.TEXT.getName(), text);
        }
        if (markup.hasAttribute("color")) {
          json.put("color", markup.getLong("color"));
        }
        if (markup.hasAttribute("posx")) {
          json.put(
              "position",
              new JsonArray()
                  .add(markup.getDouble("posx"))
                  .add(markup.getDouble("posy"))
                  .add(markup.getDouble("posz")));
        }
        if (markup.hasAttribute("sizex")) {
          json.put(
              Field.SIZE.getName(),
              new JsonArray().add(markup.getDouble("sizex")).add(markup.getDouble("sizey")));
        }
        break;
    }

    return json;
  }

  public static JsonObject attachmentToJson(Item attachment, String locale) {
    JsonObject json = new JsonObject()
        .put(Field.ID.getName(), attachment.getString(Field.SK.getName()))
        .put(Field.TIMESTAMP.getName(), attachment.getLong(Field.TIMESTAMP_SHORT.getName()))
        .put(Field.CREATED.getName(), attachment.getLong(Field.CREATED.getName()))
        .put("contentType", attachment.getString("contentType"))
        .put(Field.SIZE.getName(), attachment.getLong(Field.SIZE.getName()))
        .put("etag", attachment.getString("etag"));
    if (attachment.getString(Field.AUTHOR.getName()) != null) {
      JsonObject userObject = Users.getUserInfo(attachment.getString(Field.AUTHOR.getName()));
      json = json.put(Field.AUTHOR.getName(), userObject);
    }

    json.put("tags", CommentVerticle.handleTags(attachment, locale));
    if (attachment.hasAttribute("transcript")) {
      json.put("transcript", new JsonArray(attachment.getJSON("transcript")));
    }
    if (attachment.hasAttribute("clonedBy")) {
      json.put("isCloned", true);
    }
    if (attachment.hasAttribute("preview300")) {
      json.put(Field.PREVIEW.getName(), true);
    } else {
      json.put(Field.PREVIEW.getName(), false);
    }
    return json;
  }

  public static Iterator<Item> updateDeletedUserComments(String userId) {
    QuerySpec qs = new QuerySpec()
        .withHashKey(Field.AUTHOR.getName(), userId)
        .withFilterExpression("begins_with(pk, :pk)")
        .withValueMap(new ValueMap().withString(":pk", "comments#"));

    Iterator<Item> commentsIterator =
        query(table, authorIndex, qs, DataEntityType.COMMENTS).iterator();
    List<Item> list = new ArrayList<>();
    while (commentsIterator.hasNext()) {
      Item comment = commentsIterator.next();
      comment.withLong("userDeletedTmstmp", GMTHelper.utcCurrentTime());
      list.add(comment);
      String[] id = comment.getString(Field.SK.getName()).split("#");
      if (id.length == 2) {
        String fileId = comment.getString(Field.PK.getName()).split("#")[1];
        String threadId = id[0];
        // also updating the comment thread to get new changes
        Item annotation = getAnnotation(fileId, threadId, "comments");
        annotation.withLong("userDeletedTmstmp", GMTHelper.utcCurrentTime());
        list.add(annotation);
      }
      if (list.size() % batchChunkSize == 0) {
        batchWriteItem(
            table,
            new TableWriteItems(getTableName(table)).withItemsToPut(Utils.removeDuplicates(list)),
            DataEntityType.COMMENTS);
        list.clear();
      }
    }
    if (Utils.isListNotNullOrEmpty(list)) {
      batchWriteItem(
          table,
          new TableWriteItems(getTableName(table)).withItemsToPut(Utils.removeDuplicates(list)),
          DataEntityType.COMMENTS);
    }

    return commentsIterator;
  }

  /**
   * To check and update comment text if the commander version is before 2024.2.1 for mention user
   *
   * @param text      - Comment text to be updated
   * @param userAgent - UserAgent to check the commander version
   * @return updated comment text
   */
  private static String checkClientVersionAndUpdateComment(String text, String userAgent) {
    String finalText = text;
    if (Objects.nonNull(userAgent) && userAgent.startsWith("ARES Commander")) {
      String[] commanderVersionFormat = userAgent.split("\\.");
      if (commanderVersionFormat.length > 2) {
        String versionYear = commanderVersionFormat[0];
        int versionNumber1 = Integer.parseInt(commanderVersionFormat[1]);
        int versionNumber2 = Integer.parseInt(commanderVersionFormat[2]);
        String[] versionYearFormats = versionYear.split(Users.spaceString);
        if (versionYearFormats.length > 2) {
          int year = Integer.parseInt(versionYearFormats[versionYearFormats.length - 1]);
          if (year < 24
              || (year == 24 && versionNumber1 < 2)
              || (year == 24 && versionNumber2 < 1)) {
            finalText = MentionRender.createMentionTextForEmail(finalText, null, false);
          }
        }
      }
    }
    return finalText;
  }
}
