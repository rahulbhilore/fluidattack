package com.graebert.storage.integration.onedrive.graphApi;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.integration.onedrive.graphApi.exception.GraphException;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import io.vertx.core.eventbus.Message;

public class ODBApi extends GraphApi {
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
    ODBApi.HOME_DRIVE = HOME_DRIVE;
    ODBApi.CLIENT_ID = CLIENT_ID;
    ODBApi.CLIENT_SECRET = CLIENT_SECRET;
    ODBApi.REDIRECT_URI = REDIRECT_URI;
    ODBApi.FLUORINE_SECRET = FLUORINE_SECRET;
    ODBApi.CONFIG = CONFIG;
  }

  private ODBApi(String userId, String externalId) throws GraphException {
    super(
        ODBApi.HOME_DRIVE,
        ODBApi.CLIENT_ID,
        ODBApi.CLIENT_SECRET,
        ODBApi.REDIRECT_URI,
        ODBApi.FLUORINE_SECRET,
        ODBApi.CONFIG,
        StorageType.ONEDRIVEBUSINESS,
        userId,
        externalId);
  }

  private ODBApi(Item user) throws GraphException {
    super(
        ODBApi.HOME_DRIVE,
        ODBApi.CLIENT_ID,
        ODBApi.CLIENT_SECRET,
        ODBApi.REDIRECT_URI,
        ODBApi.FLUORINE_SECRET,
        ODBApi.CONFIG,
        StorageType.ONEDRIVEBUSINESS,
        user);
  }

  private ODBApi(String userId, String authCode, String sessionId) throws GraphException {
    super(
        ODBApi.HOME_DRIVE,
        ODBApi.CLIENT_ID,
        ODBApi.CLIENT_SECRET,
        ODBApi.REDIRECT_URI,
        ODBApi.FLUORINE_SECRET,
        ODBApi.CONFIG,
        StorageType.ONEDRIVEBUSINESS,
        authCode,
        userId,
        sessionId);
  }

  public static GraphApi connectAccount(String userId, String authCode, String sessionId)
      throws GraphException {
    return new ODBApi(userId, authCode, sessionId);
  }

  public static <T> GraphApi connect(Message<T> message) throws GraphException {
    ParsedMessage parsedMessage = MessageUtils.parse(message);
    return connectByUserId(
        parsedMessage.getJsonObject().getString(Field.USER_ID.getName()),
        parsedMessage.getJsonObject().getString(Field.EXTERNAL_ID.getName()));
  }

  public static GraphApi connectByUserId(String userId, String externalId) throws GraphException {
    return new ODBApi(userId, externalId);
  }

  public static GraphApi connectByItem(Item user) throws GraphException {
    return new ODBApi(user);
  }
}
