package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.google.common.collect.Iterables;
import com.graebert.storage.Entities.MentionUserInfo;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class Mentions extends Dataplane {
  public static final String mentionList = "usersToMention";
  private static final int minRating = 0;
  private static final int maxRating = 100;
  private static final String mainTable = TableName.MAIN.name;

  public enum MentionsType {
    User("MENTIONS#"),
    File("FILE#"),
    Storage("STORAGE#");

    public final String label;

    MentionsType(String label) {
      this.label = label;
    }
  }

  private static Item getMention(String userId, String id) {
    return getItem(
        mainTable, pk, MentionsType.User.label + userId, sk, id, false, DataEntityType.MENTIONS);
  }

  private static void getUpdatedRating(Item user, String targetUserId, int ratingPoint) {
    List<Map<String, Object>> mentioned = user.getList(mentionList);
    if (!Utils.isListNotNullOrEmpty(mentioned)) {
      return;
    }

    Map<String, Object> map = mentioned.stream()
        .filter(u -> u.get(Field.ID.getName()).equals(targetUserId))
        .findAny()
        .orElse(null);

    if (map == null) {
      Map<String, Object> userObject = getNewMentionListObject(targetUserId, ratingPoint);
      if (userObject != null) {
        mentioned.add(userObject);
      }
    } else {
      if (((Number) map.get("rating")).intValue() == maxRating) {
        mentioned.stream()
            .filter(u -> u.get(Field.ID.getName()).equals(targetUserId))
            .forEach(u -> u.replace("tmsmtp", GMTHelper.utcCurrentTime()));
        boolean ratingZero =
            mentioned.stream().anyMatch(u -> ((Number) u.get("rating")).intValue() == minRating);
        if (!ratingZero) {
          mentioned.stream()
              .filter(u -> !u.get(Field.ID.getName()).equals(targetUserId))
              .forEach(u -> {
                int rating = ((Number) u.get("rating")).intValue() - ratingPoint;
                u.put("rating", Math.max(rating, minRating));
              });
        }
      } else {
        mentioned.stream()
            .filter(f -> f.get(Field.ID.getName()).equals(targetUserId))
            .forEach(f -> {
              int rating = ((Number) f.get("rating")).intValue() + ratingPoint;
              f.put("rating", Math.min(rating, maxRating));
              f.replace("tmsmtp", GMTHelper.utcCurrentTime());
            });
      }
    }

    user.withList(mentionList, mentioned);
  }

  private static Map<String, Object> getNewMentionListObject(String targetUserId, int ratingPoint) {
    Item item = Users.getUserById(targetUserId);
    if (item == null) {
      return null;
    }

    Map<String, Object> userObject = Users.formatUserForMention(item).getMap();
    userObject.put("rating", ratingPoint);
    userObject.put("tmstmp", GMTHelper.utcCurrentTime());
    return userObject;
  }

  private static Set<MentionUserInfo> checkAndUpdateUserInfoFromCache(
      Set<MentionUserInfo> allMentionedList,
      Set<MentionUserInfo> cachedUsersToUpdate,
      Set<String> cachedUsersToRemove) {
    return allMentionedList.stream()
        .map(u -> {
          String cachedUserId = u.id;
          Optional<Item> optional = Users.getUserFromUsersList(cachedUserId);
          if (optional.isPresent()) {
            Item user = optional.get();
            // AS : check if the cache object is already updated after the user's last loggedIn, if
            // yes
            // then skip the update.
            if (user.hasAttribute("loggedIn")) {
              if (u.updated < user.getLong("loggedIn")) {
                if (Utils.isStringNotNullOrEmpty(user.getString(Field.USER_EMAIL.getName()))) {
                  u.email = user.getString(Field.USER_EMAIL.getName());
                }
                if (Utils.isStringNotNullOrEmpty(user.getString(Field.F_NAME.getName()))) {
                  u.name = user.getString(Field.F_NAME.getName());
                }
                if (Utils.isStringNotNullOrEmpty(user.getString(Field.SURNAME.getName()))) {
                  u.surname = user.getString(Field.SURNAME.getName());
                }
                String username = Users.getUserName(user);
                if (Objects.nonNull(username)) {
                  u.username = username;
                }
                u.updated = GMTHelper.utcCurrentTime();
                cachedUsersToUpdate.add(u);
              }
            } else {
              log.warn("[MENTIONS] User doesn't have loggedIn property - " + user.toJSONPretty());
            }
          } else {
            cachedUsersToRemove.add(cachedUserId);
            // AS: exclude the user if it doesn't exist in our users list.
            return null;
          }
          return u;
        })
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * @param targetUserId {String} - userId who has performed an activity like -
   *                     1. Commented in a drawing.
   *                     2. Any file has been shared with them.
   *                     3. Someone has mentioned them in a comment.
   *                     <p>
   *                     We update their rating in the usersToMention list according to their
   *                     activity (@enum Activity)
   */
  public static void updateRating(
      Activity activity, String targetUserId, String fileId, StorageType storageType) {
    // update rating for all the users who are subscribed to this drawing
    Iterator<Item> subscribedUsers = SubscriptionsDao.getFileSubscriptions(fileId);
    List<Item> finalListToUpdate = new ArrayList<>();
    subscribedUsers.forEachRemaining(user -> {
      if (user.getString(sk).equals(targetUserId)) {
        return;
      }

      Item storageItem =
          getMention(user.getString(sk), MentionsType.Storage.label + storageType.toString());
      if (storageItem == null) {
        Map<String, Object> userObject = getNewMentionListObject(targetUserId, activity.rating);
        if (userObject == null) {
          return;
        }
        storageItem = new Item()
            .withPrimaryKey(
                pk,
                MentionsType.User.label + user.getString(sk),
                sk,
                MentionsType.Storage.label + storageType)
            .withList(mentionList, List.of(userObject));
      } else {
        getUpdatedRating(storageItem, targetUserId, activity.rating);
      }

      Item fileItem = getMention(user.getString(sk), MentionsType.File.label + fileId);
      if (fileItem == null) {
        Map<String, Object> userObject = getNewMentionListObject(targetUserId, activity.rating);
        if (userObject == null) {
          return;
        }
        fileItem = new Item()
            .withPrimaryKey(
                pk,
                MentionsType.User.label + user.getString(sk),
                sk,
                MentionsType.File.label + fileId)
            .withList(mentionList, List.of(userObject));
      } else {
        getUpdatedRating(fileItem, targetUserId, activity.rating);
      }

      finalListToUpdate.add(storageItem);
      finalListToUpdate.add(fileItem);
    });
    batchWriteListItems(finalListToUpdate, mainTable, DataEntityType.MENTIONS);
  }

  /**
   * Sort the mentioned users list based on rating and last updated
   *
   * @return set of MentionUserInfo
   */
  public static Set<MentionUserInfo> sortMentionList(
      Iterator<Item> iterator, String pattern, String fileIdSk, String storageTypeSk) {
    if (!iterator.hasNext()) {
      return Collections.emptySet();
    }

    Comparator<Object> comparator = Comparator.comparingInt(u -> ((MentionUserInfo) u).rating)
        .thenComparingLong(u -> ((MentionUserInfo) u).updated)
        .reversed();

    Set<Map<String, Object>> allMentionedList = new LinkedHashSet<>();
    while (iterator.hasNext()) {
      Item item = iterator.next();
      if ((Objects.nonNull(fileIdSk) && item.getString(sk).equals(fileIdSk))
          || (Objects.nonNull(storageTypeSk) && item.getString(sk).equals(storageTypeSk))) {
        // not to process these items again in user cache
        continue;
      }
      List<Map<String, Object>> list = item.getList(mentionList);
      if (Utils.isListNotNullOrEmpty(list)) {
        allMentionedList.addAll(list);
      }
    }
    return allMentionedList.stream()
        .filter(Users::isEnabled)
        .map(MentionUserInfo::new)
        .filter(user -> filterUserInfoCache(user, pattern))
        .sorted(comparator)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Get suggestion cache for current file or storage
   *
   * @return set of MentionUserInfo
   */
  public static Set<MentionUserInfo> getStorageOrFileCache(
      String userid,
      String id,
      Set<MentionUserInfo> cachedUsersToUpdate,
      Set<String> cachedUsersToRemove,
      String pattern) {
    Iterator<Item> iterator = query(
            mainTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :val1 AND sk = :val2")
                .withValueMap(new ValueMap().withString(":val1", userid).withString(":val2", id)),
            DataEntityType.MENTIONS)
        .iterator();
    Set<MentionUserInfo> newMentionedList = sortMentionList(iterator, pattern, null, null);
    return checkAndUpdateUserInfoFromCache(
        newMentionedList, cachedUsersToUpdate, cachedUsersToRemove);
  }

  /**
   * Get overall suggestion cache for the user
   *
   * @return set of MentionUserInfo
   */
  public static Set<MentionUserInfo> getUserCache(
      String userId,
      String fileIdSk,
      String storageTypeSk,
      Set<MentionUserInfo> cachedUsersToUpdate,
      Set<String> cachedUsersToRemove,
      String pattern) {
    Set<MentionUserInfo> newMentionedList = sortMentionList(
        query(mainTable, null, new QuerySpec().withHashKey(pk, userId), DataEntityType.MENTIONS)
            .iterator(),
        pattern,
        fileIdSk,
        storageTypeSk);
    return checkAndUpdateUserInfoFromCache(
        newMentionedList, cachedUsersToUpdate, cachedUsersToRemove);
  }

  /**
   * Filter the suggestion list using the pattern provided
   *
   * @param userInfo - list of MentionUserInfo object which is to be filtered
   * @param pattern        - pattern to filter
   * @return filtered list of MentionUserInfo
   */
  public static boolean filterUserInfoCache(MentionUserInfo userInfo, String pattern) {
    if (Utils.isStringNotNullOrEmpty(pattern)) {
      return Users.isPatternMatched(pattern, userInfo.name, userInfo.surname, userInfo.email);
    }
    return true;
  }

  /**
   * Update mentioned user info inside the cache
   *
   * @param userId              - id of the user for which the cache is to be updated
   * @param cachedUsersToUpdate - set of user info to be updated in overall cache
   * @param cachedUsersToRemove - set of users to be removed from overall cache
   */
  public static void updateUserMentionCache(
      String userId, Set<MentionUserInfo> cachedUsersToUpdate, Set<String> cachedUsersToRemove) {
    List<Item> finalListToUpdate = new ArrayList<>();
    for (Item item :
        query(mainTable, null, new QuerySpec().withHashKey(pk, userId), DataEntityType.MENTIONS)) {
      List<Map<String, Object>> list = item.getList(mentionList);
      AtomicBoolean updateRequired = new AtomicBoolean(false);
      list = list.stream()
          .map(userObject -> {
            String cachedUserId = (String) userObject.get(Field.ID.getName());
            if (cachedUsersToRemove.contains(cachedUserId)) {
              if (!updateRequired.get()) {
                updateRequired.set(true);
              }
              return null;
            }
            Optional<MentionUserInfo> optional = cachedUsersToUpdate.stream()
                .filter(cachedUser -> cachedUser.id.equals(cachedUserId))
                .findAny();
            if (optional.isPresent()) {
              if (!updateRequired.get()) {
                updateRequired.set(true);
              }
              int rating = ((BigDecimal) userObject.get("rating")).intValue();
              userObject = optional.get().toMap();
              userObject.put("rating", rating);
            }
            return userObject;
          })
          .collect(Collectors.toList());
      if (updateRequired.get()) {
        Iterables.removeIf(list, Objects::isNull);
        item.withList(mentionList, list);
        finalListToUpdate.add(item);
      }
    }
    batchWriteListItems(finalListToUpdate, mainTable, DataEntityType.MENTIONS);
  }

  /**
   * Update mentioned users cache while login
   *
   * @param userId - id of the user for which the cache is to be updated
   */
  public static void updateMentionedUsers(String userId) {
    Iterator<Item> mentionIterator = query(
            mainTable,
            null,
            new QuerySpec().withHashKey(pk, Mentions.MentionsType.User.label + userId),
            DataEntityType.MENTIONS)
        .iterator();
    List<Item> finalListToUpdate = new ArrayList<>();
    mentionIterator.forEachRemaining(mention -> {
      List<Map<String, Object>> mentionedUsers = mention.getList(Mentions.mentionList);
      // remove all users who are deleted OR not found in DB
      mentionedUsers.removeIf(
          user -> Users.getUserById((String) user.get(Field.ID.getName())) == null);
      // updating each mentioned user's info
      mentionedUsers.forEach(user -> {
        Item item = Users.getUserById((String) user.get(Field.ID.getName()));
        if (Objects.nonNull(item)) {
          Map<String, Object> userObject = Users.formatUserForMention(item).getMap();
          userObject.put("rating", user.get("rating"));
          userObject.put("tmstmp", user.get("tmstmp"));
          mentionedUsers.set(mentionedUsers.indexOf(user), userObject);
        }
      });
      mention.withList(Mentions.mentionList, mentionedUsers);
      finalListToUpdate.add(mention);
    });
    batchWriteListItems(finalListToUpdate, mainTable, DataEntityType.MENTIONS);
  }

  public enum Activity {
    ADD_COMMENT(3),
    FILE_SHARED(2),
    MENTIONED(1);

    private final int rating;

    Activity(int rating) {
      this.rating = rating;
    }
  }
}
