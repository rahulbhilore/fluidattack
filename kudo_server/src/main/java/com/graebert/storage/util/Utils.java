package com.graebert.storage.util;

import com.amirkhawaja.Ksuid;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.AsyncResult;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.RandomStringUtils;
import org.jetbrains.annotations.NonNls;

public class Utils {

  public static long MINUTE_IN_MS = 60 * 1000;
  private static final String emptyString = "";

  /**
   * Shorthand to check if string isn't null and isn't empty
   *
   * @param stringToCheck {String} - string that has to be validated
   * @return {boolean} - true if string isn't empty and null
   */
  public static boolean isStringNotNullOrEmpty(String stringToCheck) {
    return stringToCheck != null && !stringToCheck.trim().isEmpty();
  }

  public static <T> List<T> removeDuplicates(List<T> list) {
    return list.stream().distinct().collect(Collectors.toList());
  }

  /**
   * Shorthand to check if List isn't null and isn't empty
   *
   * @param listToCheck {List} - List to validate
   * @return {boolean} - true if List isn't empty and null
   */
  public static <T> boolean isListNotNullOrEmpty(Collection<T> listToCheck) {
    return listToCheck != null && !listToCheck.isEmpty();
  }

  /**
   * Shorthand to check if map isn't null and isn't empty
   *
   * @param mapToCheck {Map} - map to validate
   * @return {boolean} - true if map isn't empty and null
   */
  public static <T, K> boolean isMapNotNullOrEmpty(Map<T, K> mapToCheck) {
    return mapToCheck != null && !mapToCheck.isEmpty();
  }

  /**
   * Shorthand to check if json object isn't null and isn't empty
   *
   * @param objToCheck {JsonObject} - object to validate
   * @return {boolean} - true if object isn't empty and null
   */
  // we can further use this at more places
  public static boolean isJsonObjectNotNullOrEmpty(JsonObject objToCheck) {
    return objToCheck != null && !objToCheck.isEmpty();
  }

  public static String getEncapsulatedId(StorageType storageType, String objectId) {
    return getEncapsulatedId(StorageType.getShort(storageType), objectId);
  }

  public static String getEncapsulatedId(
      StorageType storageType, String accountId, String objectId) {
    return getEncapsulatedId(StorageType.getShort(storageType), accountId, objectId);
  }

  /**
   * Returns encapsulated id
   *
   * @param storageCode {String} -  short code for storage
   * @param accountId   {String} - id of an external account
   * @param objectId    {String} - id of object (file/folder)
   * @return {String} - encapsulated id
   */
  public static String getEncapsulatedId(String storageCode, String accountId, String objectId) {
    if (objectId.toLowerCase().contains(storageCode.toLowerCase())
        && objectId.toLowerCase().contains(accountId.toLowerCase())) {
      return objectId;
    }
    return storageCode + "+" + accountId + "+" + objectId;
  }

  public static String getEncapsulatedId(String storageCode, String objectId) {
    if (objectId.toLowerCase().startsWith(storageCode.toLowerCase())) {
      return objectId;
    }
    return storageCode + "+" + objectId;
  }

  // file deepcode ignore change~substring~java.security.SecureRandom~generateRandomPassword: We
  // don't care about this password security as we encourage changing it immediately
  public static String generateRandomPassword(final int passwordLength) {
    String upperCaseLetters = RandomStringUtils.random(5, 65, 90, true, true);
    String lowerCaseLetters = RandomStringUtils.random(5, 97, 122, true, true);
    String numbers = RandomStringUtils.randomNumeric(5);
    String totalChars = RandomStringUtils.randomAlphanumeric(5);
    String combinedChars =
        upperCaseLetters.concat(lowerCaseLetters).concat(numbers).concat(totalChars);
    List<Character> pwdChars =
        combinedChars.chars().mapToObj(c -> (char) c).collect(Collectors.toList());
    Collections.shuffle(pwdChars);
    return pwdChars.stream()
        .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
        .substring(0, passwordLength);
  }

  public static String getEncapsulatedId(JsonObject json) {
    if (json.containsKey("encapsulatedId")) {
      return json.getString("encapsulatedId");
    }
    return StorageType.getShort(json.getString(Field.STORAGE_TYPE.getName())) + "+"
        + json.getString(Field.EXTERNAL_ID.getName()) + "+"
        + (json.containsKey(Field.FILE_ID.getName())
            ? json.getString(Field.FILE_ID.getName())
            : json.getString(Field.FOLDER_ID.getName()));
  }

  public static <T> List<List<T>> splitList(List<T> items, int chunk) {
    List<List<T>> list = new ArrayList<>();
    if (items.size() > chunk) {
      for (int i = 0; i < (items.size() / chunk) + 1; i++) {
        list.add(items.subList(i * chunk, Math.min((i + 1) * chunk, items.size())));
      }
    } else {
      list.add(items);
    }
    return list.stream().filter(sublist -> !sublist.isEmpty()).collect(Collectors.toList());
  }

  public static <T> List<T[]> splitArray(T[] ids) {
    return splitArray(ids, 100);
  }

  public static <T> List<T[]> splitArray(T[] ids, int chunkSize) {
    List<T[]> list = new ArrayList<>();
    if (ids.length > chunkSize) {
      for (int i = 0; i < ids.length / chunkSize + 1; i++) {
        int length = Math.min(chunkSize, ids.length - i * chunkSize);
        T[] chunk = (T[]) new Object[length];
        System.arraycopy(ids, i * chunkSize, chunk, 0, length);
        list.add(chunk);
      }
    } else {
      list.add(ids);
    }
    return list;
  }

  public static String generateUUID() {
    try {
      return new Ksuid().generate();
    } catch (IOException e) {
      return UUID.randomUUID().toString();
    }
  }

  private static String getLocalizedString(final ResourceBundle resourceBundle, String stringId) {
    try {
      if (resourceBundle.containsKey(stringId)) {
        return resourceBundle.getString(stringId);
      } else {
        return Utils.getDefaultString(stringId);
      }
    } catch (Exception e) {
      DynamoBusModBase.log.error("No string found. String id: " + stringId + " language: "
          + resourceBundle.getLocale().getDisplayLanguage());
      return Utils.getDefaultString(stringId);
    }
  }

  public static String getLocalizedString(final String localeParam, String stringId) {
    ResourceBundle resourceBundle = getBundleForLocale(localeParam);
    return getLocalizedString(resourceBundle, stringId);
  }

  public static <T> String getLocalizedString(final Message<T> message, String stringId) {

    ResourceBundle resourceBundle = getBundleForLocale(message);
    return getLocalizedString(resourceBundle, stringId);
  }

  private static String getDefaultString(final String stringId) {
    ResourceBundle resourceBundle =
        ResourceBundle.getBundle("errorStrings", Locale.ENGLISH, new UTF8Control());
    if (Utils.isStringNotNullOrEmpty(stringId) && resourceBundle.containsKey(stringId)) {
      return resourceBundle.getString(stringId);
    } else {
      return emptyString;
    }
  }

  public static <T> ResourceBundle getBundleForLocale(final Message<T> message) {
    return getBundleForLocale(getLocaleFromMessage(message));
  }

  public static <T> String getLocaleFromMessage(final Message<T> message) {
    if (message != null && message.body() != null) {
      try {
        return MessageUtils.parse(message).getJsonObject().getString(Field.LOCALE.getName());
      } catch (Exception ignore) {
      }
    }

    return Locale.ENGLISH.toLanguageTag();
  }

  public static ResourceBundle getBundleForLocale(String localeParam) {
    ResourceBundle resourceBundle;
    try {
      if (localeParam == null || localeParam.isEmpty()) {
        resourceBundle = ResourceBundle.getBundle("errorStrings", new UTF8Control());
      } else {
        if (localeParam.contains("_")) {
          // this should work fine for most locales
          localeParam = localeParam.replace("_", "-");
        }
        // Express the user's preferences with a Language Priority List
        final String ranges = localeParam + ";q=1.0,en-GB;q=0.0";
        List<Locale.LanguageRange> languageRanges = Locale.LanguageRange.parse(ranges);
        List<Locale> availableLocales = Arrays.asList(
            Locale.ENGLISH,
            Locale.JAPAN,
            Locale.JAPANESE,
            Locale.KOREA,
            Locale.KOREAN,
            Locale.GERMAN,
            Locale.GERMANY,
            Locale.forLanguageTag("pl"));
        Locale locale = Locale.lookup(languageRanges, Arrays.asList(Locale.getAvailableLocales()));
        if (!availableLocales.contains(locale)) {
          locale = Locale.ENGLISH;
        }
        resourceBundle = ResourceBundle.getBundle("errorStrings", locale, new UTF8Control());
        if (resourceBundle == null || resourceBundle.keySet().isEmpty()) {
          resourceBundle = ResourceBundle.getBundle("errorStrings", new UTF8Control());
        }
      }
    } catch (Exception e) {
      resourceBundle = ResourceBundle.getBundle("errorStrings", Locale.ENGLISH, new UTF8Control());
    }
    return resourceBundle;
  }

  @NonNls
  public static String getStringPostfix(@NonNls final String proName) {
    switch (proName) {
      case "ARES Kudo":
        return "AresKudo";
      case "DraftSight":
        return proName;
      default:
        return emptyString;
    }
  }

  public static String getFileNameFromPath(final String path) {
    try {
      return path.substring(path.lastIndexOf("/") + 1);
    } catch (IndexOutOfBoundsException e) {
      return path;
    }
  }

  public static String getFilePathFromPath(final String path) {
    try {
      return path.substring(0, path.lastIndexOf("/"));
    } catch (IndexOutOfBoundsException e) {
      return emptyString;
    }
  }

  public static String getFilePathFromPathWithSlash(final String path) {
    try {
      return path.substring(0, path.lastIndexOf("/") + 1);
    } catch (IndexOutOfBoundsException e) {
      return "/";
    }
  }

  public static JsonObject mergeIn(JsonObject options, JsonObject strongerOptions) {
    Map<String, Object> optionsMap = new HashMap<>(options.getMap());
    for (Map.Entry<String, Object> strongerEntry : strongerOptions.getMap().entrySet()) {
      optionsMap.merge(strongerEntry.getKey(), strongerEntry.getValue(), (oldVal, newVal) -> {
        if (oldVal instanceof Map) {
          oldVal = new JsonObject((Map<String, Object>) oldVal);
        }
        if (newVal instanceof Map) {
          newVal = new JsonObject((Map<String, Object>) newVal);
        }
        if (oldVal instanceof JsonObject && newVal instanceof JsonObject) {
          return mergeIn((JsonObject) oldVal, (JsonObject) newVal);
        }
        if ((newVal.toString().equals("true") || newVal.toString().equals("false"))
            && (oldVal.toString().equals("true") || oldVal.toString().equals("false"))) {
          return Boolean.parseBoolean(oldVal.toString()) && Boolean.parseBoolean(newVal.toString());
        }
        return newVal;
      });
    }
    return new JsonObject(optionsMap);
  }

  public static JsonObject mergeOptions(JsonObject oldOptions, JsonObject newOptions) {
    Map<String, Object> newOptionsMap = new HashMap<>(oldOptions.getMap());
    for (Map.Entry<String, Object> newEntry : newOptions.getMap().entrySet()) {
      newOptionsMap.merge(newEntry.getKey(), newEntry.getValue(), (oldVal, newVal) -> {
        if (oldVal instanceof Map) {
          oldVal = new JsonObject((Map<String, Object>) oldVal);
        }
        if (newVal instanceof Map) {
          newVal = new JsonObject((Map<String, Object>) newVal);
        }
        if (oldVal instanceof JsonObject && newVal instanceof JsonObject) {
          return mergeOptions((JsonObject) oldVal, (JsonObject) newVal);
        }
        return newVal;
      });
    }
    return new JsonObject(newOptionsMap);
  }

  public static String safeSubstringFromLastOccurrence(String baseString, String stringToFind) {
    int index = baseString.lastIndexOf(stringToFind);
    if (index > -1) {
      return baseString.substring(index);
    }
    return baseString;
  }

  public static String safeSubstringToLastOccurrence(String baseString, String stringToFind) {
    int index = baseString.lastIndexOf(stringToFind);
    if (index > -1) {
      return baseString.substring(0, index);
    }
    return baseString;
  }

  public static String readFromInputStream(InputStream inputStream) throws IOException {
    StringBuilder resultStringBuilder = new StringBuilder();
    try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
      String line;
      while ((line = br.readLine()) != null) {
        resultStringBuilder.append(line).append("\n");
      }
    }
    return resultStringBuilder.toString();
  }

  public static String decode(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  public static boolean isEmail(String stringToCheck) {
    return Utils.isStringNotNullOrEmpty(stringToCheck)
        && stringToCheck.matches(".+@.+\\.[a-zA-Z]+");
  }

  public static boolean getBooleanValueFromJson(
      JsonObject jsonObject, String field, boolean defaultValue) {
    try {
      return jsonObject.getBoolean(field);
    } catch (Exception ignore) {
      try {
        return Boolean.parseBoolean(jsonObject.getString(field));
      } catch (Exception ignore2) {
        return defaultValue;
      }
    }
  }

  @NonNls
  public static String humanReadableByteCount(long bytes) {
    int unit = 1000;
    if (bytes < unit) {
      return bytes + " B";
    }
    int exp = (int) (Math.log(bytes) / Math.log(unit));
    @NonNls String pre = "kMGTPE".charAt(exp - 1) + emptyString;
    return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre); // NON-NLS
  }

  // Method to encode a string value using `UTF-8` encoding scheme
  public static String encodeValue(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  public static String urlencode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  // Id & fields parsers
  public static JsonObject parseObjectFields(
      String field,
      String id,
      RoutingContext routingContext,
      AsyncResult<Message<JsonObject>> message) {
    JsonObject fields = parseItemId(id, field);

    checkField(fields, Field.STORAGE_TYPE.getName(), routingContext, message);
    checkField(fields, Field.EXTERNAL_ID.getName(), routingContext, message);
    checkField(fields, field, routingContext, message);

    return fields;
  }

  public static JsonObject parseObjectFields(
      RoutingContext routingContext, AsyncResult<Message<JsonObject>> message) {
    JsonObject fields = new JsonObject();

    checkField(fields, Field.STORAGE_TYPE.getName(), routingContext, message);
    checkField(fields, Field.EXTERNAL_ID.getName(), routingContext, message);

    return fields;
  }

  private static void checkField(
      JsonObject fields,
      String field,
      RoutingContext routingContext,
      AsyncResult<Message<JsonObject>> message) {
    if (fields.getString(field) == null) {
      fields.put(field, routingContext.request().getHeader(field));
    }

    if (fields.getString(field) == null) {
      fields.put(field, message.result().body().getString(field));
    }
  }

  public static JsonObject parseItemId(String id, @NonNls String field) {
    if (id == null) {
      return new JsonObject().put(field, null);
    }
    String[] arr = id.split("\\+");
    if (arr.length < 2) {
      return new JsonObject().put(field, id);
    }
    if (arr.length == 2) {
      // webdav
      String value = arr[1];
      value = URLDecoder.decode(value, StandardCharsets.UTF_8);
      StorageType storageType = StorageType.getByShort(arr[0]);
      JsonObject result = new JsonObject().put(field, value);
      if (storageType != null) {
        result.put(Field.STORAGE_TYPE.getName(), StorageType.shortToLong(arr[0]));
      }
      result.put(Field.EXTERNAL_ID.getName(), emptyString); // delete the value
      return result;
    } else {
      String value = arr[2];
      value = URLDecoder.decode(value, StandardCharsets.UTF_8);
      String externalId = arr[1];
      StorageType storageType = StorageType.getByShort(arr[0]);
      String mode = null;
      if (arr.length == 4) {
        mode = arr[3];
      }
      JsonObject result = new JsonObject().put(field, value);
      if (storageType != null) {
        result
            .put(Field.STORAGE_TYPE.getName(), StorageType.shortToLong(arr[0]))
            .put(Field.EXTERNAL_ID.getName(), externalId);
      }
      result.put("encapsulationMode", mode == null ? "2" : mode).put("encapsulatedId", id);
      return result;
    }
  }

  public static JsonObject parseItemId(String id) {
    return parseItemId(id, Field.ID.getName());
  }

  public static JsonObject parseObjectId(String id) {
    return parseItemId(id, Field.OBJECT_ID.getName());
  }

  public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {

    Map<Object, Boolean> seen = new HashMap<>();
    return t -> keyExtractor.apply(t) != null
        && seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
  }

  public static JsonObject getBodyAsJson(RoutingContext routingContext) {
    try {
      if (Objects.nonNull(routingContext.body())
          && Objects.nonNull(routingContext.body().asJsonObject())) {
        return routingContext.body().asJsonObject();
      }
    } catch (Exception ignored) {
    }
    return new JsonObject();
  }

  public static JsonArray getBodyAsJsonArray(RoutingContext routingContext) {
    try {
      if (Objects.nonNull(routingContext.body())
          && Objects.nonNull(routingContext.body().asJsonArray())) {
        return routingContext.body().asJsonArray();
      }
    } catch (Exception ignored) {
    }
    return new JsonArray();
  }

  public static String getPropertyIfPresent(
      JsonObject object, String propertyName, String defaultValue) {
    try {
      if (object != null
          && Utils.isStringNotNullOrEmpty(propertyName)
          && object.containsKey(propertyName)
          && Utils.isStringNotNullOrEmpty(object.getString(propertyName))) {
        return object.getString(propertyName);
      }
    } catch (Exception ignore) {
    }
    return defaultValue;
  }

  public static String[] parseRelativePath(final String path) {
    String pathCopy = path;
    if (pathCopy.startsWith("./")) {
      pathCopy = pathCopy.substring("./".length());
    }
    pathCopy = pathCopy.replaceAll("[/\\\\]+", "/");
    while (pathCopy.startsWith("/")) {
      pathCopy = pathCopy.substring(1);
    }
    return pathCopy.split("/");
  }

  public static String getStorageObject(
      final String storageType,
      final String externalId,
      final String serverLocation,
      final List<String> regionLocations) {
    JsonObject storageObject = new JsonObject()
        .put(Field.STORAGE_TYPE.getName(), storageType)
        .put(Field.ID.getName(), externalId);
    if (Utils.isStringNotNullOrEmpty(serverLocation)) {
      storageObject.put(Field.SERVER.getName(), serverLocation);
    }
    if (Utils.isListNotNullOrEmpty(regionLocations)) {
      storageObject.put(Field.REGIONS.getName(), regionLocations);
    }
    return storageObject.encode();
  }

  public static String getStorageObject(
      final StorageType storageType,
      final String externalId,
      final String serverLocation,
      final List<String> regionLocations) {
    return getStorageObject(storageType.toString(), externalId, serverLocation, regionLocations);
  }

  public static <T> Stream<T> asStream(Iterator<T> sourceIterator) {
    return asStream(sourceIterator, false);
  }

  public static <T> Stream<T> asStream(Iterator<T> sourceIterator, boolean parallel) {
    Iterable<T> iterable = () -> sourceIterator;
    return StreamSupport.stream(iterable.spliterator(), parallel);
  }

  public static String getUserShortName(String userName) {
    String[] userNameParts = userName.split(" ");
    if (userNameParts.length == 2) {
      return (userNameParts[0].charAt(0) + Dataplane.emptyString + userNameParts[1].charAt(0))
          .toUpperCase();
    } else if (userName.length() >= 2) {
      return (userName.charAt(0) + Dataplane.emptyString + userName.charAt(1)).toUpperCase();
    } else {
      return "UN";
    }
  }

  public static boolean anyNull(Object... objects) {
    return Arrays.stream(objects).anyMatch(Objects::isNull);
  }

  public static boolean noNull(Object... objects) {
    return Arrays.stream(objects).noneMatch(Objects::isNull);
  }

  public static String checkAndRename(Set<String> names, String name) {
    return checkAndRename(names, name, false);
  }

  public static String checkAndRename(
      final Set<String> names, final String name, boolean isFolder) {
    if (!Utils.isStringNotNullOrEmpty(name)) {
      return emptyString;
    }
    final int dotIndex = name.lastIndexOf(".");
    final String extension = !isFolder && dotIndex != -1 ? name.substring(dotIndex) : emptyString;
    String newName = name;
    final String pureName = !isFolder && dotIndex != -1 ? name.substring(0, dotIndex) : name;
    int i = 1;
    while (names.contains(newName)) {
      newName = pureName + " (" + i + ")" + extension;
      i++;
    }
    return newName;
  }

  public static String convertIfUTF8Bytes(String input) {
    if (input.startsWith("\\x") && input.length() % 4 == 0) {
      String[] hexValues = input.split("\\\\x");
      boolean isValidHex = true;
      for (int i = 1; i < hexValues.length; i++) {
        if (!hexValues[i].matches("[0-9a-fA-F]+")) {
          isValidHex = false;
          break;
        }
      }
      if (isValidHex) {
        byte[] utf8Bytes = new byte[hexValues.length - 1];
        for (int i = 1; i < hexValues.length; i++) {
          int hexValue = Integer.parseInt(hexValues[i], 16);
          utf8Bytes[i - 1] = (byte) hexValue;
        }
        return new String(utf8Bytes, StandardCharsets.UTF_8);
      }
    }
    return input;
  }
}
