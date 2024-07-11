package com.graebert.storage.integration.onedrive.graphApi;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import io.vertx.core.eventbus.Message;

public class ODApi extends GraphApi {
  private static String HOME_DRIVE;
  private static String CLIENT_ID;
  private static String CLIENT_SECRET;
  private static String REDIRECT_URI;
  private static String FLUORINE_SECRET;
  private static ServerConfig CONFIG;

  public static void Initialize(
      String HOME_DRIVE,
      String CLIENT_ID,
      String CLIENT_SECRET,
      String REDIRECT_URI,
      String FLUORINE_SECRET,
      ServerConfig CONFIG) {
    ODApi.HOME_DRIVE = HOME_DRIVE;
    ODApi.CLIENT_ID = CLIENT_ID;
    ODApi.CLIENT_SECRET = CLIENT_SECRET;
    ODApi.REDIRECT_URI = REDIRECT_URI;
    ODApi.FLUORINE_SECRET = FLUORINE_SECRET;
    ODApi.CONFIG = CONFIG;
  }

  private ODApi(String userId, String externalId) throws GraphException {
    super(
        HOME_DRIVE,
        CLIENT_ID,
        CLIENT_SECRET,
        REDIRECT_URI,
        FLUORINE_SECRET,
        CONFIG,
        StorageType.ONEDRIVE,
        userId,
        externalId);
  }

  private ODApi(Item user) throws GraphException {
    super(
        HOME_DRIVE,
        CLIENT_ID,
        CLIENT_SECRET,
        REDIRECT_URI,
        FLUORINE_SECRET,
        CONFIG,
        StorageType.ONEDRIVE,
        user);
  }

  private ODApi(String userId, String authCode, String sessionId) throws GraphException {
    super(
        HOME_DRIVE,
        CLIENT_ID,
        CLIENT_SECRET,
        REDIRECT_URI,
        FLUORINE_SECRET,
        CONFIG,
        StorageType.ONEDRIVE,
        authCode,
        userId,
        sessionId);
  }

  public static ODApi connectAccount(String userId, String authCode, String sessionId)
      throws GraphException {
    return new ODApi(userId, authCode, sessionId);
  }

  public static <T> ODApi connect(Message<T> message) throws GraphException {
    ParsedMessage parsedMessage = MessageUtils.parse(message);
    return connectByUserId(
        parsedMessage.getJsonObject().getString(Field.USER_ID.getName()),
        parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName()));
  }

  public static ODApi connectByUserId(String userId, String externalId) throws GraphException {
    return new ODApi(userId, externalId);
  }

  public static ODApi connectByItem(Item user) throws GraphException {
    return new ODApi(user);
  }
}
