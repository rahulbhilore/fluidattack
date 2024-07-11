package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.ScanSpec;
import com.graebert.storage.integration.UsersPagination;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This is a class to work with the list of users to make things like get, delete, find, mention
 * users.
 */
public class UsersList extends Dataplane {
  private static Map<String, Item> usersMap = new ConcurrentHashMap<>();
  private static boolean isLoadingUsersList = false;

  /**
   * Reloads the list from DB.
   */
  public static void getNewUserList() {
    if (!config.getProperties().getScanUsersList()) {
      return;
    }
    // make sure we don't do this multiple times actually
    if (!isLoadingUsersList) {
      isLoadingUsersList = true;

      ItemCollection<ScanOutcome> itemCollection =
          scan(TableName.MAIN.name, Users.emailIndex, new ScanSpec(), DataEntityType.USERS);

      usersMap = new ConcurrentHashMap<>();
      itemCollection.forEach(item -> usersMap.put(item.getString(sk), item));

      isLoadingUsersList = false;
    }
  }

  /**
   * Updates specified user's info in the list.
   *
   * @param userId - id of user to update
   * @param item   - new data
   */
  public static void updateUserInList(String userId, Item item) {
    if (!Utils.isMapNotNullOrEmpty(usersMap)) {
      getNewUserList();
      return;
    }

    try {
      usersMap.put(userId, item);
    } catch (Exception ex) {
      log.error(
          String.format(
              "[UsersList] Error on updating user info. User obj: %s", item.toJSONPretty()),
          ex);
    }
  }

  /**
   * Removes user from the list (in case of delete).
   *
   * @param userId user to be deleted
   */
  public static void deleteFromUserList(String userId) {
    if (!Utils.isMapNotNullOrEmpty(usersMap)) {
      getNewUserList();
      return;
    }

    usersMap.remove(userId);
  }

  /**
   * Returns the list of users.
   *
   * @return list of users.
   */
  public static Iterator<Item> getUserList() {
    if (!Utils.isMapNotNullOrEmpty(usersMap)) {
      getNewUserList();
    }
    return usersMap.values().iterator();
  }

  /**
   * Get some portion of the list.
   *
   * @param pagination - UsersPagination object
   * @return iterator of sublist
   */
  public static Iterator<Item> getLimitedUserList(UsersPagination pagination) {
    if (!Utils.isMapNotNullOrEmpty(usersMap)) {
      getNewUserList();
      pagination.setLastIndexValue(String.valueOf(pagination.getLimit()));
      return usersMap.values().stream().limit(pagination.getLimit()).iterator();
    }
    int fromIndexValue = Integer.parseInt(pagination.getLastIndexValue());
    if (usersMap.size() <= fromIndexValue) {
      return Collections.emptyIterator();
    }
    pagination.setLastIndexValue(
        String.valueOf(Math.min(fromIndexValue + pagination.getLimit(), usersMap.size())));
    return usersMap.values().stream()
        .skip(fromIndexValue - 1)
        .limit(pagination.getLimit())
        .iterator();
  }

  /**
   * Find users by specified pattern.
   *
   * @param pattern Text to search by
   * @return Users matching the pattern
   */
  public static Iterator<Item> findUsers(String pattern) {
    if (!Utils.isMapNotNullOrEmpty(usersMap)) {
      getNewUserList();
    }

    return usersMap.values().stream()
        .filter(user -> (isPatternMatched(
            pattern,
            user.getString(Field.F_NAME.getName()),
            user.getString(Field.USER_EMAIL.getName()))))
        .collect(Collectors.toList())
        .iterator();
  }

  private static boolean isPatternMatched(String filter, String fname, String email) {
    Pattern pattern = Pattern.compile(Pattern.quote(filter), Pattern.CASE_INSENSITIVE);
    boolean isEmailFound =
        Utils.isStringNotNullOrEmpty(email) && pattern.matcher(email).find();
    boolean isNameFound =
        Utils.isStringNotNullOrEmpty(fname) && pattern.matcher(fname).find();
    return isEmailFound || isNameFound;
  }
}
