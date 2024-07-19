package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.NameMap;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.graebert.storage.Entities.PatternFields;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.UsersPagination;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.security.AuthManager;
import com.graebert.storage.security.EncryptHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.users.UsersVerticle;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.commons.collections4.IteratorUtils;
import org.jetbrains.annotations.NonNls;

public class Users extends Dataplane {
  public static final String ENTERPRISE_ORG_ID = "org_id";
  public static final String MY_COMPANY = "My company";
  public static final String mainTable = emptyString;

  public static final String spaceString = " ";
  public static final String emailIndex = "user-email-index";
  private static final String tempTable = TableName.TEMP.name;
  private static final String gIdIndex = "user-gid-index";
  private static final String userOrgIndex = "user-org-index";

  private static final String userField = "USERS#KUDO#";
  private static final String orgField = "ORG#";
  private static final String adminField = "USERADMINS";
  private static final String adminList = "admins";
  private static final String pendingMessagesField = "USERPENDINGMESSAGES";

  public static JsonObject getUserInfo(String userId) {
    Item user = getUserById(userId);
    JsonObject userObject = new JsonObject().put(Field.ID.getName(), userId);
    if (user != null) {
      return getAuthorInfo(user);
    }
    userObject.put(Field.NAME.getName(), "Deleted User");
    return userObject;
  }

  public static JsonObject getAuthorInfo(Item user) {
    if (user == null) {
      return new JsonObject();
    }

    String userId = user.getString(sk);
    JsonObject userObject = new JsonObject().put(Field.ID.getName(), userId);
    String name = (user.getString(Field.F_NAME.getName()) != null
            ? user.getString(Field.F_NAME.getName())
            : emptyString)
        + (user.getString(Field.SURNAME.getName()) != null
            ? spaceString + user.getString(Field.SURNAME.getName())
            : emptyString);
    if (name.trim().isEmpty()) {
      name = user.getString(Field.USER_EMAIL.getName());
    }
    String email = user.getString(Field.USER_EMAIL.getName());
    if (name != null) {
      userObject.put(Field.NAME.getName(), name).put(Field.EMAIL.getName(), email);
    } else {
      userObject.put(Field.NAME.getName(), "Deleted User");
    }

    return userObject;
  }

  public static JsonObject getUserInfo(Item user) {
    if (user == null) {
      return new JsonObject();
    }

    JsonObject json = new JsonObject();
    String usId = user.getString(sk);
    String name =
        (user.get(Field.F_NAME.getName()) != null ? user.get(Field.F_NAME.getName()) : emptyString)
            + (user.get(Field.SURNAME.getName()) != null
                ? spaceString + user.get(Field.SURNAME.getName())
                : emptyString);
    if (name.trim().isEmpty()) {
      name = user.getString(Field.USER_EMAIL.getName());
    }
    json.put(Field.ENCAPSULATED_ID.getName(), usId)
        .put(Field.NAME.getName(), name)
        .put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()))
        .put(
            Field.ENABLED.getName(),
            user.hasAttribute(Field.ENABLED.getName()) && user.getBoolean(Field.ENABLED.getName()))
        .put(
            Field.ACTIVATED.getName(),
            user.get(Field.APPLICANT.getName()) == null
                || !user.getBoolean(Field.APPLICANT.getName()));
    return json;
  }

  public static Iterator<Item> getUserByEmail(String email) {
    if (!Utils.isStringNotNullOrEmpty(email)) {
      return Collections.emptyIterator();
    }
    return query(
            mainTable,
            emailIndex,
            new QuerySpec().withHashKey(Field.USER_EMAIL.getName(), email),
            DataEntityType.USERS)
        .iterator();
  }

  public static Iterator<Item> getAllUsers() {
    return UsersList.getUserList();
  }

  public static Iterator<Item> getLimitedUsers(UsersPagination pagination) {
    return UsersList.getLimitedUserList(pagination);
  }

  public static List<String> getAllAdmins() {
    Item item = getItem(mainTable, pk, adminField, sk, adminField, true, DataEntityType.USERS);
    if (item != null && item.hasAttribute(adminList)) {
      return item.getList(adminList);
    }
    return null;
  }

  public static Item findUserByGraebertIdOrEmail(final String graebertId, final String email) {
    Iterator<Item> itemIterator = query(
            mainTable,
            emailIndex,
            new QuerySpec().withHashKey(Field.USER_EMAIL.getName(), email),
            DataEntityType.USERS)
        .iterator();

    if (!itemIterator.hasNext()) {
      itemIterator = query(
              mainTable,
              gIdIndex,
              new QuerySpec().withHashKey(Field.GRAEBERT_ID.getName(), graebertId),
              DataEntityType.USERS)
          .iterator();

      return (itemIterator.hasNext()) ? itemIterator.next() : null;
    } else {
      return itemIterator.next();
    }
  }

  public static Item findUserByGraebertId(final String graebertId) {
    Iterator<Item> itemIterator = query(
            mainTable,
            gIdIndex,
            new QuerySpec().withHashKey(Field.GRAEBERT_ID.getName(), graebertId),
            DataEntityType.USERS)
        .iterator();

    return (itemIterator.hasNext()) ? itemIterator.next() : null;
  }

  public static List<String> getUsersWithPendingMessages() {
    List<String> result = new ArrayList<>();
    for (Item pendingUser : query(
        mainTable,
        null,
        new QuerySpec().withHashKey(pk, pendingMessagesField),
        DataEntityType.USERS)) {
      result.add(pendingUser.getString(sk));
    }
    return result;
  }

  public static void saveUserPendingMessages(String userId) {
    Item item = new Item().withPrimaryKey(pk, pendingMessagesField, sk, userId);
    putItem(item, DataEntityType.USERS);
  }

  public static void removeUserPendingMessages(String userId) {
    deleteItem(emptyString, pk, pendingMessagesField, sk, userId, DataEntityType.USERS);
  }

  public static Map<String, JsonObject> getUserMapFluorine() {
    Iterator<Item> users = getAllUsers();
    Map<String, JsonObject> userMap = new HashMap<>();
    userMap.put(
        null,
        new JsonObject()
            .put(Field.NAME.getName(), Field.UNKNOWN.getName())
            .put(Field.EMAIL.getName(), Field.UNKNOWN.getName()));
    Item user;
    JsonObject json;
    while (users.hasNext()) {
      user = users.next();
      json = new JsonObject();
      String usId = user.getString(sk);
      String name = (user.get(Field.F_NAME.getName()) != null
              ? user.get(Field.F_NAME.getName())
              : emptyString)
          + (user.get(Field.SURNAME.getName()) != null
              ? spaceString + user.get(Field.SURNAME.getName())
              : emptyString);
      if (name.trim().isEmpty()) {
        name = user.getString(Field.USER_EMAIL.getName());
      }
      json.put(Field.ENCAPSULATED_ID.getName(), usId)
          .put(Field.NAME.getName(), name)
          .put(Field.EMAIL.getName(), user.getString(Field.USER_EMAIL.getName()))
          .put(
              Field.ENABLED.getName(),
              user.hasAttribute(Field.ENABLED.getName())
                  && user.getBoolean(Field.ENABLED.getName()))
          .put(
              Field.ACTIVATED.getName(),
              user.get(Field.APPLICANT.getName()) == null
                  || !user.getBoolean(Field.APPLICANT.getName()));
      if (usId != null && name != null) {
        userMap.put(usId, json);
      }
    }
    return userMap;
  }

  public static String getPkFromUserId(final String userId) {
    return userField + userId;
  }

  public static String getPkFromOrgId(final String orgId) {
    return orgField + orgId;
  }

  public static Item getUserById(final String userId) {
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      return null;
    }
    return getItemFromCache(
        mainTable, pk, getPkFromUserId(userId), sk, userId, DataEntityType.USERS);
  }

  public static boolean isUserPartOfOrganization(Item user, String organizationId) {
    return Objects.nonNull(user)
        && user.hasAttribute(Field.ORGANIZATION_ID.getName())
        && user.getString(Field.ORGANIZATION_ID.getName()).equals(organizationId);
  }

  public static boolean isOrgAdmin(final Item user, String organizationId) {
    return isUserPartOfOrganization(user, organizationId)
        && user.hasAttribute(Field.IS_ORG_ADMIN.getName())
        && user.getBoolean(Field.IS_ORG_ADMIN.getName());
  }

  /**
   * This is used for notifications - we shouldn't take data from cache as cache might have wrong
   * values
   *
   * @param userId user id
   * @return item from dynamodb
   */
  public static Item getUserByIdFromDB(final String userId) {
    Item user =
        getItem(mainTable, pk, getPkFromUserId(userId), sk, userId, false, DataEntityType.USERS);
    if (user != null) {
      // perform put to update cache
      updateInCache(
          mainTable, getPkFromUserId(userId), userId, user.toJSON(), DataEntityType.USERS);
    }
    return user;
  }

  // For some reason we store this in users table.
  public static Item getOrganizationById(final String organizationId) {
    return getItemFromCache(getPkFromOrgId(organizationId), organizationId, DataEntityType.USERS);
  }

  public static String getUserNameById(final String userId) {
    if (userId.equals(Field.DELETED.getName())) {
      return "Deleted user";
    }

    Item item = getItemFromCache(getPkFromUserId(userId), userId, DataEntityType.USERS);
    if (item == null) {
      return Field.UNKNOWN.getName();
    }

    return (item.get(Field.F_NAME.getName()) != null
            ? item.get(Field.F_NAME.getName())
            : emptyString)
        + (item.get(Field.SURNAME.getName()) != null
            ? spaceString + item.get(Field.SURNAME.getName())
            : emptyString);
  }

  // control what fields we send
  public static JsonObject userToJsonWithGoogleId(
      Item item,
      boolean isEnterprise,
      JsonObject instanceOptionsJson,
      JsonObject defaultUserOptionsJson,
      String defaultLocale,
      JsonObject instanceUserPreferencesJson,
      JsonObject defaultCompanyOptionsJson) {
    return userToJson(
        item,
        isEnterprise,
        true,
        instanceOptionsJson,
        defaultUserOptionsJson,
        defaultLocale,
        instanceUserPreferencesJson,
        defaultCompanyOptionsJson);
  }

  public static JsonObject userToJsonWithoutGoogleId(
      Item item,
      boolean isEnterprise,
      JsonObject instanceOptionsJson,
      JsonObject defaultUserOptionsJson,
      String defaultLocale,
      JsonObject instanceUserPreferencesJson,
      JsonObject defaultCompanyOptionsJson) {
    return userToJson(
        item,
        isEnterprise,
        false,
        instanceOptionsJson,
        defaultUserOptionsJson,
        defaultLocale,
        instanceUserPreferencesJson,
        defaultCompanyOptionsJson);
  }

  private static JsonObject userToJson(
      Item item,
      boolean isEnterprise,
      boolean getGoogleId,
      JsonObject instanceOptionsJson,
      JsonObject defaultUserOptionsJson,
      String defaultLocale,
      JsonObject instanceUserPreferencesJson,
      JsonObject defaultCompanyOptionsJson) {
    String userId = item.getString(sk);
    // this is a session object
    if (!Utils.isStringNotNullOrEmpty(userId)) {
      userId = Sessions.getUserIdFromPK(item.getString(Field.PK.getName()));
    }
    JsonObject json = new JsonObject();
    if (item.hasAttribute(Field.F_NAME.getName())) {
      json.put(Field.F_NAME.getName(), item.getString(Field.F_NAME.getName()));
    }
    if (item.hasAttribute(Field.GRAEBERT_ID.getName())) {
      json.put(Field.GRAEBERT_ID.getName(), item.getString(Field.GRAEBERT_ID.getName()));
    }
    if (item.hasAttribute(Field.SURNAME.getName())) {
      json.put(Field.SURNAME.getName(), item.getString(Field.SURNAME.getName()));
    }

    boolean isAdmin = item.getList(Field.ROLES.getName()) != null
        && item.getList(Field.ROLES.getName()).contains("1");
    json.put(Field.IS_ADMIN.getName(), isAdmin);

    /*
    options priority (lower to higher):
    - Default Company options
    - Default User options
    - Company options
    - User options
    = Instance options (only if false)

        |       co      |       uo        |    co-uo result   |
                t/f             t               t
                t/f             f               f

        |       co-uo result      |       io        |    final   |
                f                         t               f
                t                         t               t
                f                         f               f
                t                         f               f
     */

    JsonObject options;
    JsonObject userOptions;
    if (!item.hasAttribute(Field.OPTIONS.getName())) {
      options = Utils.mergeIn(defaultUserOptionsJson, instanceOptionsJson);
      userOptions = Utils.mergeIn(defaultUserOptionsJson, instanceOptionsJson);
    } else {
      // consider default and instance options
      userOptions = new JsonObject(item.getJSON(Field.OPTIONS.getName()));
      JsonObject instanceOptions = new JsonObject(instanceOptionsJson.encode());
      JsonObject defaultUserOptions = new JsonObject(defaultUserOptionsJson.encode());
      JsonObject defaultCompanyOptions = new JsonObject(defaultCompanyOptionsJson.encode());
      options = Utils.mergeOptions(defaultCompanyOptions, defaultUserOptions);
      options = Utils.mergeOptions(options, userOptions);
      options = Utils.mergeIn(options, instanceOptions);
    }
    // consider company options
    Item company = null;
    if (item.hasAttribute(Field.ORGANIZATION_ID.getName()) || isEnterprise) {
      String orgId =
          isEnterprise ? ENTERPRISE_ORG_ID : item.getString(Field.ORGANIZATION_ID.getName());
      if (!orgId.equals(AuthManager.NON_EXISTENT_ORG)) {
        company = getItemFromCache(getPkFromOrgId(orgId), orgId, DataEntityType.USERS);
        if (company != null) {
          String itemOptions = item.getJSON(Field.OPTIONS.getName());
          if (!Utils.isStringNotNullOrEmpty(itemOptions)) {
            userOptions = new JsonObject();
          } else {
            userOptions = new JsonObject(itemOptions);
          }
          JsonObject instanceOptions = new JsonObject(instanceOptionsJson.encode());
          JsonObject defaultUserOptions = new JsonObject(defaultUserOptionsJson.encode());
          JsonObject defaultCompanyOptions = new JsonObject(defaultCompanyOptionsJson.encode());
          JsonObject companyOptions = company.hasAttribute(Field.OPTIONS.getName())
              ? new JsonObject(company.getJSON(Field.OPTIONS.getName()))
              : new JsonObject();
          JsonObject resultCompanyOptions =
              Utils.mergeOptions(defaultCompanyOptions, companyOptions);
          JsonObject resultUserOptions = Utils.mergeOptions(defaultUserOptions, userOptions);
          options = Utils.mergeOptions(resultCompanyOptions, resultUserOptions);
          options = Utils.mergeIn(options, instanceOptions);
        }
      }
    }

    boolean sharedLinksAllowed = options.containsKey(Field.SHARED_LINKS.getName())
        ? options.getBoolean(Field.SHARED_LINKS.getName())
        : true;
    // for free accounts we don't support public links so need to set it to false
    if (sharedLinksAllowed && item.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())) {
      long licenseExpirationDate = item.getLong(Field.LICENSE_EXPIRATION_DATE.getName());
      boolean hasLicenseExpired =
          licenseExpirationDate > 0L && licenseExpirationDate <= GMTHelper.utcCurrentTime();
      if (hasLicenseExpired) {
        sharedLinksAllowed = false;
      }
    }
    options.put(Field.SHARED_LINKS.getName(), sharedLinksAllowed);

    if (userOptions.containsKey(Field.EMAIL_NOTIFICATIONS.getName())) {
      Boolean emailNotifications = false;
      try {
        emailNotifications = userOptions.getBoolean(Field.EMAIL_NOTIFICATIONS.getName());
      } catch (Exception ignore) {
        try {
          emailNotifications =
              Boolean.parseBoolean(userOptions.getString(Field.EMAIL_NOTIFICATIONS.getName()));
        } catch (Exception ignore2) {
          // nothing to do
        }
      }
      options.put(Field.EMAIL_NOTIFICATIONS.getName(), emailNotifications.toString());
    }
    json.put(Field.OPTIONS.getName(), options);
    json.put(Field.ENCAPSULATED_ID.getName(), userId);
    if (item.get(Field.LOCALE.getName()) == null
        || item.getString(Field.LOCALE.getName()).trim().isEmpty()) {
      json.put(Field.LOCALE.getName(), defaultLocale);
    } else {
      json.put(Field.LOCALE.getName(), item.getString(Field.LOCALE.getName()));
    }
    json.put(
        Field.ENABLED.getName(),
        item.hasAttribute(Field.ENABLED.getName()) && item.getBoolean(Field.ENABLED.getName()));

    if (item.hasAttribute(Field.USER_EMAIL.getName())) {
      json.put(Field.EMAIL.getName(), item.getString(Field.USER_EMAIL.getName()));
    }
    json.put(Field.NAME.getName(), item.getString(Field.F_NAME.getName()));

    if (getGoogleId) {
      if (item.hasAttribute("gId") && Utils.isStringNotNullOrEmpty(item.getString("gId"))) {
        Item googleAccount = ExternalAccounts.getExternalAccount(userId, item.getString("gId"));
        if (googleAccount != null) {
          json.put(Field.NAME.getName(), googleAccount.getString(Field.NAME.getName()))
              .put(Field.SURNAME.getName(), googleAccount.getString(Field.SURNAME.getName()))
              .put(Field.EMAIL.getName(), googleAccount.getString(Field.EMAIL.getName()));
          json.put("googleAccount", true);
        } else {
          log.error("no google account with id " + item.get("gId"));
          json.put("googleAccount", false);
        }
      } else {
        json.put("googleAccount", false);
      }
    }

    String fileFilters =
        json.getJsonObject(Field.OPTIONS.getName()).getString(Field.FILE_FILTER.getName());
    json.put(
        Field.FILE_FILTER.getName(), fileFilters != null ? fileFilters : Field.ALL_FILES.getName());

    if (item.hasAttribute(Field.LICENSE_EXPIRATION_DATE.getName())) {
      json.put(
          Field.LICENSE_EXPIRATION_DATE.getName(),
          item.getLong(Field.LICENSE_EXPIRATION_DATE.getName()));
    }
    if (item.hasAttribute(Field.LICENSE_TYPE.getName())) {
      json.put(Field.LICENSE_TYPE.getName(), item.getString(Field.LICENSE_TYPE.getName()));
    }
    if (item.hasAttribute(Field.COUNTRY_LOCALE.getName())) {
      json.put(Field.COUNTRY_LOCALE.getName(), item.getString(Field.COUNTRY_LOCALE.getName()));
    }

    if (item.hasAttribute(Field.GRAEBERT_ID.getName())) {
      json.put(Field.GRAEBERT_ID.getName(), item.getString(Field.GRAEBERT_ID.getName()));
      try {
        json.put(
            "graebertIdHash",
            EncryptHelper.intercomHash(
                item.getString(Field.GRAEBERT_ID.getName()),
                item.getString(Field.INTERCOM_APP_ID.getName())));
      } catch (Exception e) {
        log.error("Error on getting intercom hash ", e);
      }
    }

    if (!item.hasAttribute(Field.PREFERENCES.getName())) {
      json.put(Field.PREFERENCES.getName(), instanceUserPreferencesJson);
    } else {
      JsonObject configPreferences = new JsonObject(instanceUserPreferencesJson.encode());
      JsonObject userPreferences = new JsonObject(item.getJSON(Field.PREFERENCES.getName()));
      json.put(Field.PREFERENCES.getName(), configPreferences.mergeIn(userPreferences, true));
    }

    json.put(
        Field.INTERCOM_APP_ID.getName(),
        item.hasAttribute(Field.INTERCOM_APP_ID.getName())
            ? item.getString(Field.INTERCOM_APP_ID.getName())
            : emptyString);

    json.put(
        Field.IS_TRIAL_SHOWN.getName(),
        item.hasAttribute(Field.IS_TRIAL_SHOWN.getName())
            && item.getBoolean(Field.IS_TRIAL_SHOWN.getName()));
    json.put(
        Field.SHOW_RECENT.getName(),
        !item.hasAttribute(Field.SHOW_RECENT.getName())
            || item.getBoolean(Field.SHOW_RECENT.getName()));
    json.put(
        Field.NOTIFICATION_BAR_SHOWED.getName(),
        item.hasAttribute(Field.NOTIFICATION_BAR_SHOWED.getName())
            ? item.getLong(Field.NOTIFICATION_BAR_SHOWED.getName())
            : -1);

    json.put(
        Field.COMPANY.getName(),
        item.hasAttribute(Field.ORGANIZATION_ID.getName()) || isEnterprise
            ? new JsonObject()
                .put(
                    Field.ID.getName(),
                    isEnterprise
                        ? ENTERPRISE_ORG_ID
                        : item.getString(Field.ORGANIZATION_ID.getName()))
                .put(
                    Field.IS_ADMIN.getName(),
                    isEnterprise
                        ? isAdmin
                        : item.hasAttribute(Field.IS_ORG_ADMIN.getName())
                            && item.getBoolean(Field.IS_ORG_ADMIN.getName()))
                .put(
                    Field.COMPANY_NAME.getName(),
                    isEnterprise
                        ? MY_COMPANY
                        : company != null
                            ? company.getString(Field.COMPANY_NAME.getName())
                            : emptyString)
            : new JsonObject());
    try {
      if (item.hasAttribute(Field.UTMC.getName())) {
        json.put(Field.UTMC.getName(), new JsonObject(item.getMap(Field.UTMC.getName())));
      }
    } catch (Exception ignored) {

    }

    if (item.hasAttribute(Field.SAMPLE_USAGE.getName())) {
      json.put(Field.USAGE.getName(), item.getLong(Field.SAMPLE_USAGE.getName()));
    } else {
      json.put(Field.USAGE.getName(), 0);
    }
    if (item.hasAttribute(Field.SAMPLE_CREATED.getName())) {
      json.put(Field.SAMPLE_CREATED.getName(), item.getBoolean(Field.SAMPLE_CREATED.getName()));
    } else {
      json.put(Field.SAMPLE_CREATED.getName(), false);
    }
    if (item.hasAttribute(Field.IS_TEMP_ACCOUNT.getName())) {
      json.put(Field.IS_TEMP_ACCOUNT.getName(), item.getBoolean(Field.IS_TEMP_ACCOUNT.getName()));
    }
    json.put(
        Field.HAS_COMMANDER_LICENSE.getName(),
        item.hasAttribute(Field.HAS_COMMANDER_LICENSE.getName())
            && item.getBoolean(Field.HAS_COMMANDER_LICENSE.getName()));
    json.put(
        Field.HAS_TOUCH_LICENSE.getName(),
        item.hasAttribute(Field.HAS_TOUCH_LICENSE.getName())
            && item.getBoolean(Field.HAS_TOUCH_LICENSE.getName()));
    return json;
  }

  public static void setUserStorage(final String userId, final String storage) {
    updateUser(userId, "set fstorage = :val", new ValueMap().withJSON(":val", storage));
  }

  public static void removeUserNonce(final String userId) {
    updateUser(userId, "remove nonce");
  }

  public static void setUserOptions(final String userId, final String options) {
    updateUser(
        userId,
        "set #O = :val",
        null,
        new ValueMap().withJSON(":val", options),
        new NameMap().with("#O", Field.OPTIONS.getName()));
  }

  public static long setLastMessageSent(final String userId) {
    long currentTime = GMTHelper.utcCurrentTime();
    updateUser(userId, "set lastMessageSent = :val", new ValueMap().withLong(":val", currentTime));
    return currentTime;
  }

  public static void setUserEmail(
      final String userId, final String email, final String newHa1email) {
    updateUser(
        userId,
        "set userEmail = :val , ha1email = :val2 remove newEmail , newHa1email",
        "attribute_not_exists(userEmail)",
        new ValueMap().withString(":val", email).withString(":val2", newHa1email));
  }

  public static void setUserLoggedIn(final String userId, final long loggedIn) {
    updateUser(userId, "set loggedIn = :val", new ValueMap().withLong(":val", loggedIn));
  }

  public static void setUserS3Region(final String userId, final String region) {
    updateUser(userId, "set s3Region = :val", new ValueMap().withString(":val", region));
  }

  public static void setUserResetRequest(final String userId, final boolean resetRequest) {
    updateUser(userId, "set resetRequest = :val", new ValueMap().withBoolean(":val", resetRequest));
  }

  public static void setUserComplianceStatus(final String userId, final String compliance) {
    updateUser(
        userId, "set complianceStatus = :val", new ValueMap().withString(":val", compliance));
  }

  public static void setUserGId(final String userId, final String gId) {
    updateUser(userId, "set gId = :val", new ValueMap().withString(":val", gId));
  }

  public static void updateUserForeignPassword(final String userId, final String ha1email) {
    updateUser(
        userId, "set ha1email = :val remove gId", new ValueMap().withString(":val", ha1email));
  }

  public static void resetUser(final String userId, final String ha1email) {
    updateUser(
        userId,
        "set ha1email = :val remove resetRequest",
        new ValueMap().withString(":val", ha1email));
  }

  public static void setUserRoles(final String userId, final List<String> roles) {
    updateUser(
        userId,
        "set #R = :val",
        null,
        new ValueMap().withList(":val", roles),
        new NameMap().with("#R", Field.ROLES.getName()));
  }

  public static void setUserSamples(final String userId, final boolean samples) {
    updateUser(userId, "set samplesCreated = :val", new ValueMap().withBoolean(":val", samples));
  }

  public static void setUserSampleVersion(final String userId, final int version) {
    updateUser(userId, "set samplesVersion = :val", new ValueMap().withInt(":val", version));
  }

  public static void setLicenseExpirationDate(final String userId, final long expirationDate) {
    updateUser(
        userId,
        "set licenseExpirationDate = :val",
        new ValueMap().withLong(":val", expirationDate));
  }

  public static void setUserCapabilities(
      final String userId, final List<Map<String, Object>> capabilities) {
    updateUser(userId, "set capabilities = :val", new ValueMap().withList(":val", capabilities));
  }

  public static void updateUser(final String userId, final String updateExpression) {
    updateUser(userId, updateExpression, null, null, null);
  }

  public static void updateUser(
      final String userId, final String updateExpression, final ValueMap valueMap) {
    updateUser(userId, updateExpression, null, valueMap, null);
  }

  public static void updateUser(
      final String userId,
      final String updateExpression,
      final String conditionExpression,
      final ValueMap valueMap) {
    updateUser(userId, updateExpression, conditionExpression, valueMap, null);
  }

  public static void updateUser(
      final String userId,
      final String updateExpression,
      final String conditionExpression,
      final ValueMap valueMap,
      final NameMap nameMap) {
    UpdateItemSpec updateItemSpec = new UpdateItemSpec()
        .withPrimaryKey(pk, getPkFromUserId(userId), sk, userId)
        .withUpdateExpression(updateExpression);

    if (valueMap != null) {
      updateItemSpec.withValueMap(valueMap);
    }
    if (nameMap != null) {
      updateItemSpec.withNameMap(nameMap);
    }
    if (Utils.isStringNotNullOrEmpty(conditionExpression)) {
      updateItemSpec.withConditionExpression(conditionExpression);
    }

    UpdateItemOutcome outcome = updateItem(mainTable, updateItemSpec, DataEntityType.USERS);
    if (outcome.getItem() != null) {
      UsersList.updateUserInList(userId, outcome.getItem());
    }
  }

  public static void update(final String userId, final Item newUserInfo) {
    putItem(mainTable, newUserInfo, DataEntityType.USERS);
    UsersList.updateUserInList(userId, newUserInfo);
  }

  public static void updateUsage(final String userId, final long usage, boolean replace) {
    String updateExpression = (replace) ? "SET #usage = :usage" : "ADD #usage :usage";
    Item updatedItem = updateItem(
            mainTable,
            new UpdateItemSpec()
                .withPrimaryKey(pk, getPkFromUserId(userId), sk, userId)
                .withUpdateExpression(updateExpression)
                .withValueMap(new ValueMap().withLong(":usage", usage))
                .withNameMap(new NameMap().with("#usage", Field.SAMPLE_USAGE.getName())),
            DataEntityType.USERS)
        .getItem();
    if (Objects.nonNull(updatedItem)) {
      UsersList.updateUserInList(userId, updatedItem);
    }
  }

  public static void updateOrganization(final Item organizationInfo) {
    putItem(mainTable, organizationInfo, DataEntityType.USERS);
  }

  public static void deleteUser(final String userId) {
    deleteItem(mainTable, pk, getPkFromUserId(userId), sk, userId, DataEntityType.USERS);
  }

  public static void setFilesLastValidated(final String userId) {
    updateUser(
        userId,
        "set filesLastValidated = :val",
        new ValueMap().withLong(":val", GMTHelper.utcCurrentTime()));
  }

  public static void removeResetRequests(final String userId) {
    updateUser(userId, "remove resetRequest");
  }

  public static void changeUserAvailability(final String userId, final boolean isEnabled) {
    updateUser(
        userId,
        "set enabled = :yes" + (isEnabled ? " remove applicant" : emptyString),
        new ValueMap().withBoolean(":yes", isEnabled));
  }

  public static long getSamplesQuota(String userId, JsonObject instanceOptions) {
    Item user = Users.getUserById(userId);
    long quota = -1;

    if (Objects.nonNull(user) && user.hasAttribute(Field.OPTIONS.getName())) {
      JsonObject userOptions = new JsonObject(user.getJSON(Field.OPTIONS.getName()));
      if (userOptions.containsKey(Field.QUOTA.getName())) {
        quota = userOptions.getLong(Field.QUOTA.getName());
      } else {
        // check instance options
        if (instanceOptions != null && instanceOptions.containsKey(Field.QUOTA.getName())) {
          quota = instanceOptions.getLong(Field.QUOTA.getName());
        }
      }
    }
    return quota;
  }

  public static long getSamplesUsage(String userId) {
    Item user = Users.getUserById(userId);
    long currentUsage = 0;
    if (Objects.nonNull(user) && user.hasAttribute(Field.SAMPLE_USAGE.getName())) {
      currentUsage = user.getLong(Field.SAMPLE_USAGE.getName());
    }
    return currentUsage;
  }

  public static void deleteUserNotifications(String userId) {
    deleteUserData(
        query(
                tempTable,
                null,
                new QuerySpec().withHashKey(pk, "notificationevents#" + userId),
                DataEntityType.USERS)
            .iterator(),
        tempTable,
        DataEntityType.USERS);
  }

  public static Iterator<Item> checkUserDeleteRequest(String userId) {
    return query(
            tempTable,
            null,
            new QuerySpec()
                .withKeyConditionExpression("pk = :pk")
                .withValueMap(new ValueMap().withString(":pk", "delete_user#" + userId)),
            DataEntityType.USERS)
        .iterator();
  }

  public static Item getUserDeleteRequest(String userId, String token) {
    String id = "delete_user#" + userId;
    return getItemFromCache(tempTable, pk, id, sk, token, DataEntityType.USERS);
  }

  public static void saveUserAdmin(String userId) {
    List<String> admins = getAllAdmins();
    Item item = new Item().withPrimaryKey(pk, adminField, sk, adminField);
    if (!Utils.isListNotNullOrEmpty(admins)) {
      admins = new ArrayList<>();
    }

    admins.add(userId);
    item.withList(adminList, Utils.removeDuplicates(admins));
    putItem(item, DataEntityType.USERS);
  }

  public static void removeUserAdmin(String userId) {
    List<String> admins = getAllAdmins();
    Item item = new Item().withPrimaryKey(pk, adminField, sk, adminField);
    if (Utils.isListNotNullOrEmpty(admins)) {
      item.withList(
          adminList,
          Utils.removeDuplicates(
              admins.stream().filter(user -> !user.equals(userId)).collect(Collectors.toList())));
      putItem(item, DataEntityType.USERS);
    }
  }

  public static void deleteUserDeleteRequest(String userId, String token) {
    String id = "delete_user#" + userId;
    deleteItem(tempTable, pk, id, sk, token, DataEntityType.USERS);
  }

  public static String saveUserDeleteRequest(String userId) {
    String uuid = Utils.generateUUID();
    Item request = new Item()
        .withString(Field.SK.getName(), uuid)
        .withString(Field.PK.getName(), "delete_user#" + userId)
        .withString(Field.STATUS.getName(), JobStatus.IN_PROGRESS.name())
        .withLong(Field.TTL.getName(), (int) (new Date().getTime() / 1000 + 24 * 60 * 60));
    putItem(tempTable, request, DataEntityType.USERS);
    return uuid;
  }

  public static void updateUserDeleteRequest(
      String userId, String uuid, String status, String error) {
    Item request = new Item()
        .withString(Field.SK.getName(), uuid)
        .withString(Field.PK.getName(), "delete_user#" + userId)
        .withString(Field.STATUS.getName(), status)
        .withLong(Field.TTL.getName(), (int) (new Date().getTime() / 1000 + 24 * 60 * 60));
    if (error != null) {
      request.withString(Field.ERROR.getName(), error);
    }
    putItem(tempTable, request, DataEntityType.USERS);
  }

  // UserS3 getters
  public static S3Regional getS3Regional(
      Item object, String userId, S3Regional defS3, ServerConfig config, String bucket) {
    @NonNls final String propertyName = Field.S3_REGION.getName();
    String defaultS3Region = defS3.getRegion();
    if (object != null) {
      String objectRegion = SimpleStorage.getS3Region(object);
      if (objectRegion != null
          && !objectRegion.equals(defaultS3Region)
          && S3Regional.isRegionValidAndSupported(objectRegion)) {
        return new S3Regional(
            config,
            bucket.concat("-" + SimpleStorage.getS3Region(object)),
            SimpleStorage.getS3Region(object));
      }
    }

    if (Utils.isStringNotNullOrEmpty(userId)) {
      Item user = Users.getUserById(userId);
      if (user != null
          && user.hasAttribute(propertyName)
          && !user.getString(propertyName).equals(defaultS3Region)
          && S3Regional.isRegionValidAndSupported(user.getString(propertyName))) {
        return new S3Regional(
            config,
            bucket.concat("-" + user.getString(propertyName)),
            user.getString(propertyName));
      }
    }

    return defS3;
  }

  public static S3Regional getS3Regional(
      String objectId, String userId, S3Regional defS3, ServerConfig config, String bucket) {
    if (Utils.isStringNotNullOrEmpty(objectId)) {
      Item object = SimpleStorage.getItem(objectId);
      return getS3Regional(object, userId, defS3, config, bucket);
    }
    if (Utils.isStringNotNullOrEmpty(userId)) {
      return getS3Regional((Item) null, userId, defS3, config, bucket);
    }
    return defS3;
  }

  // get proper s3 for stated country
  public static String getS3RegionByCountry(ServerConfig config, String searchCountry) {
    try {

      JsonObject object = config.getProperties().getSimpleStorageAsJson();

      String s3region = object.getJsonObject("default").getString(Field.REGION.getName());
      if (Utils.isStringNotNullOrEmpty(searchCountry)) {
        String fieldName = searchCountry.length() == 2 ? "countries-short" : "countries";
        for (Map.Entry<String, Object> objectEntry : object) {
          JsonObject obj = new JsonObject(objectEntry.getValue().toString());
          if (objectEntry.getKey().equals("default")) {
            continue;
          }

          for (Object country : obj.getJsonArray(fieldName)) {
            if (((String) country).equalsIgnoreCase(searchCountry)) {
              return objectEntry.getKey();
            }
          }
        }
        log.warn("[Users.getS3RegionByCountry] there is no supported country with name: \""
            + searchCountry + "\"");
      }

      return s3region;
    } catch (Exception e) {
      log.error("Exception on trying to find s3 region.", e);
      // we cannot return null here.
      return "us-east-1";
    }
  }

  public static JsonObject formatUserForMention(Item user) {
    JsonObject responseObject = new JsonObject()
        .put(Field.ID.getName(), user.getString(sk))
        .put(
            Field.EMAIL.getName(),
            (!Utils.isStringNotNullOrEmpty(user.getString(Field.USER_EMAIL.getName())))
                ? emptyString
                : user.getString(Field.USER_EMAIL.getName()))
        .put(
            Field.NAME.getName(),
            (!Utils.isStringNotNullOrEmpty(user.getString(Field.F_NAME.getName())))
                ? emptyString
                : user.getString(Field.F_NAME.getName()))
        .put(
            Field.SURNAME.getName(),
            (!Utils.isStringNotNullOrEmpty(user.getString(Field.SURNAME.getName())))
                ? emptyString
                : user.getString(Field.SURNAME.getName()));
    String username = getUserName(user);
    if (Objects.nonNull(username)) {
      responseObject.put(Field.USERNAME.getName(), username);
    }
    return responseObject;
  }

  public static String getUserName(Item user) {
    if (user.hasAttribute(Field.USER_EMAIL.getName())
        && user.hasAttribute(Field.F_NAME.getName())) {
      String username = (user.get(Field.F_NAME.getName()) != null
              ? user.get(Field.F_NAME.getName())
              : emptyString)
          + ((user.hasAttribute(Field.SURNAME.getName())
                  && user.get(Field.SURNAME.getName()) != null)
              ? spaceString + user.get(Field.SURNAME.getName())
              : emptyString);
      if (username.trim().isEmpty()) {
        username = user.getString(Field.USER_EMAIL.getName());
      }
      return username;
    }
    return null;
  }

  public static Iterator<Item> getOrganizationUsers(String organizationId) {
    return query(
            mainTable,
            userOrgIndex,
            new QuerySpec().withHashKey(Field.ORGANIZATION_ID.getName(), organizationId),
            DataEntityType.USERS)
        .iterator();
  }

  public static JsonArray getUserOrgList(
      String userId, int limit, String filter, JsonArray usersToFilter) {
    Item item = Users.getUserById(userId);
    if (item != null
        && item.hasAttribute(Field.ORGANIZATION_ID.getName())
        && !item.getString(Field.ORGANIZATION_ID.getName()).equals(AuthManager.NON_EXISTENT_ORG)) {
      QuerySpec querySpec = new QuerySpec()
          .withHashKey(
              Field.ORGANIZATION_ID.getName(), item.getString(Field.ORGANIZATION_ID.getName()))
          .withFilterExpression("attribute_exists(#enabled) and #enabled = :enabled")
          .withNameMap(new NameMap().with("#enabled", Field.ENABLED.getName()))
          .withValueMap(new ValueMap().withBoolean(":enabled", true));

      Iterator<Item> orgUserIterator =
          query(mainTable, userOrgIndex, querySpec, DataEntityType.USERS).iterator();
      JsonArray orgUserList = new JsonArray();
      while (orgUserIterator.hasNext() && orgUserList.size() < limit) {
        Item userItem = orgUserIterator.next();
        JsonObject userObject = Users.formatUserForMention(userItem);
        // excluding self here, will add later if needed
        if (userId.equals(userObject.getString(Field.ID.getName()))) {
          continue;
        }
        if (!Utils.isStringNotNullOrEmpty(filter)
            || Users.isPatternMatched(
                filter,
                userObject.getString(Field.NAME.getName()),
                userObject.getString(Field.SURNAME.getName()),
                userObject.getString(Field.EMAIL.getName()))) {
          if (usersToFilter != null && usersToFilter.contains(userObject)) {
            continue;
          }
          orgUserList.add(userObject);
        }
      }
      // already sorting in the main function if filter is specified
      if (!Utils.isStringNotNullOrEmpty(filter)) {
        sortMentionUsersList(orgUserList.getList());
      }
      return orgUserList;
    }
    return new JsonArray();
  }

  public static boolean isPatternMatched(
      String filter, String firstName, String lastName, String email) {
    if (Objects.isNull(filter)
        || !Utils.isStringNotNullOrEmpty(email)
        || (!Utils.isStringNotNullOrEmpty(firstName) && !Utils.isStringNotNullOrEmpty(lastName))) {
      return false;
    }
    Pattern pattern = Pattern.compile(Pattern.quote(filter), Pattern.CASE_INSENSITIVE);
    String[] filterArray = filter.trim().split(spaceString);
    if (filterArray.length > 1) {
      PatternFields patternFields = new PatternFields(email, firstName, lastName);
      List<String> filters = new ArrayList<>(Arrays.asList(filterArray));
      for (String subFilter : filterArray) {
        if (!patternFields.isSpacedPatternMatched(subFilter)) {
          return false;
        }
      }
      // To filter the list if the filter has similar type of sub-patterns something similar to
      // slack.
      // For eg :- "Aman Am" or "Sonu Sonu"
      Map<String, List<String>> groupedByPrefix = filters.stream()
          .collect(Collectors.groupingBy(s -> s.substring(0, Math.min(s.length(), 1))));
      if (groupedByPrefix.values().stream().anyMatch(list -> list.size() > 1)) {
        return pattern.matcher(firstName).find()
            || pattern.matcher(lastName).find()
            || pattern.matcher(firstName + spaceString + lastName).find();
      }
      return true;
    }
    return pattern.matcher(email).find()
        || pattern.matcher(firstName).find()
        || pattern.matcher(lastName).find();
  }

  public static void sortMentionUsersList(List<JsonObject> users) {
    users.sort(Comparator.comparing(
            o -> ((JsonObject) o).getString(Field.NAME.getName()).toLowerCase())
        .thenComparing(o -> ((JsonObject) o).getString(Field.SURNAME.getName()).toLowerCase())
        .thenComparing(o -> ((JsonObject) o).getString(Field.EMAIL.getName())));
  }

  public static boolean isEnabled(Map<String, Object> userObj) {
    return isEnabled(new JsonObject(userObj));
  }

  public static Optional<Item> getUserFromUsersList(String userId) {
    List<Item> usersList = IteratorUtils.toList(UsersList.getUserList());
    return usersList.stream().filter(user -> user.getString(sk).equals(userId)).findAny();
  }

  private static boolean isEnabled(JsonObject userObj) {
    if (userObj.containsKey(Field.ID.getName())) {
      Optional<Item> optional = getUserFromUsersList(userObj.getString(Field.ID.getName()));
      if (optional.isPresent()) {
        Item user = optional.get();
        return user.hasAttribute(Field.ENABLED.getName())
            && user.getBoolean(Field.ENABLED.getName());
      }
    }
    return false;
  }

  public static boolean isStorageAvailable(RoutingContext context, boolean requireStorage) {
    if (requireStorage) {
      JsonObject attributes = context.user().attributes();
      // check if the current storage is valid on the instance for the user
      String optionName = attributes.containsKey(Field.STORAGE_TYPE.getName())
          ? attributes.getString(Field.STORAGE_TYPE.getName()).toLowerCase()
          : emptyString;
      JsonObject options = attributes.containsKey(Field.OPTIONS.getName())
          ? attributes.getJsonObject(Field.OPTIONS.getName())
          : null;
      // NPE check for older sessions
      if (options != null && options.containsKey(Field.STORAGES.getName())) {
        boolean valid = UsersVerticle.isStorageAvailable(
            optionName, options, attributes.getString(Field.USER_ID.getName()));
        if (!valid) {
          context
              .response()
              .setStatusCode(512)
              .end(new JsonObject()
                  .put(
                      Field.MESSAGE.getName(),
                      Utils.getLocalizedString(
                          context.request().headers().get(Field.LOCALE.getName()), "FL14"))
                  .encodePrettily());
          log.info(Utils.getLocalizedString(
              context.request().headers().get(Field.LOCALE.getName()), "FL14"));
          return false;
        }
      }
    }
    return true;
  }
}
