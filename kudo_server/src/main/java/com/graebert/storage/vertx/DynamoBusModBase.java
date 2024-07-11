package com.graebert.storage.vertx;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.KudoErrorCodes;
import com.graebert.storage.Entities.KudoFileException;
import com.graebert.storage.config.Properties;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.storage.StorageActions;
import com.graebert.storage.stats.logs.storage.StorageLog;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.StorageDAO;
import com.graebert.storage.storage.Users;
import com.graebert.storage.users.IntercomConnection;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.LogPrefix;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import kong.unirest.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NonNls;

public abstract class DynamoBusModBase extends ResponseHandler {
  @NonNls
  public static final Logger log = LogManager.getRootLogger();

  public static final String webSocketPrefix = "WEBSOCKET: ";
  protected static final String SQSPattern =
      "https://sqs.{region}.amazonaws.com/{accnumber}/{queue}";
  public static StatsDClient statsd = null;

  @NonNls
  protected static AmazonSQS sqs;

  protected boolean devMode = false;
  protected ServerConfig config = new ServerConfig();

  public DynamoBusModBase() {}

  @Override
  public void start() throws Exception {
    super.start();
    Properties properties = Properties.fromJson(config());
    config.init(properties);
    devMode = config.getProperties().getDevMode();
    if (instanceRegion == null) {
      if (!devMode) {
        Region currentRegion = Regions.getCurrentRegion();
        instanceRegion = currentRegion != null ? currentRegion.getName() : "local_dev";
      } else {
        instanceRegion = "local_dev";
      }
    }
    if (statsd == null) {
      try {
        statsd = new NonBlockingStatsDClient(
            config.getProperties().getStatsDKey(),
            config.getProperties().getStatsDHost(),
            config.getProperties().getStatsDPort());
      } catch (Exception ignore) {
      }
    }
    if (LogPrefix.getLogPrefix().isEmpty()) {
      String statsPrefix = config.getProperties().getStatsDPrefix();
      String revision = config.getProperties().getRevision();
      String host = null;
      try {
        host = java.net.InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException ignore) {
      }
      LogPrefix.updateLogPrefix(
          instanceRegion + " " + statsPrefix + " " + revision + (host != null ? " " + host : ""));
    }
    Dataplane.init(properties);

    if (sqs == null) {
      AmazonSQSClientBuilder builder = AmazonSQSClientBuilder.standard();
      builder.setRegion("eu-west-1");
      builder.setClientConfiguration(new ClientConfiguration().withMaxConnections(500));
      sqs = builder.build();
    }
  }

  @Override
  public void stop() {}

  protected void recordExecutionTime(@NonNls String name, long millis) {
    if (!devMode && statsd != null) {
      statsd.recordExecutionTime(config.getProperties().getStatsDPrefix() + "." + name, millis);
    }
  }

  protected void gauge(@NonNls String name, long count) {
    if (!devMode && statsd != null) {
      statsd.gauge(config.getProperties().getStatsDPrefix() + "." + name, count);
    }
  }

  protected <T> void execute(
      Message<T> message, String method, Class<?>[] paramTypes, Object[] args) {
    Entity segment = XRayManager.createSegment(OperationGroup.DYNAMO_BUS_MODE, message);
    try {
      Class<?>[] types = new Class[paramTypes.length + 2];
      types[0] = Entity.class;
      types[1] = Message.class;
      System.arraycopy(paramTypes, 0, types, 2, paramTypes.length);
      Method m = this.getClass().getDeclaredMethod(method, types);
      Object[] arguments = new Object[args.length + 2];
      arguments[0] = segment;
      arguments[1] = message;
      System.arraycopy(args, 0, arguments, 2, args.length);
      m.invoke(this, arguments);
    } catch (Exception e) {
      String text = e instanceof InvocationTargetException
          ? ((InvocationTargetException) e).getTargetException()
                  instanceof ProvisionedThroughputExceededException
              ? "The provisioned throughput is exceeded. Please try later"
              : ((InvocationTargetException) e).getTargetException().getMessage()
          : e.getMessage();
      sendError(segment, message, text, 400, e);
    }
  }

  public <T> String getRequiredString(
      Entity segment, @NonNls String fieldName, Message<T> message, JsonObject jsonObject) {
    String value = jsonObject.getString(fieldName);
    if (value == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "MustBeSpecified"), fieldName),
          400);
      return null;
    }
    // empty string is equivalent to empty
    if (value.isEmpty()) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "MustBeSpecified"), fieldName),
          400);
      return null;
    }
    return value;
  }

  public <T> Integer getRequiredInt(
      Entity segment,
      @NonNls String fieldName,
      Message<T> message,
      JsonObject jsonObject,
      int minValue) {
    Integer value = jsonObject.getInteger(fieldName);
    if (value == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "MustBeSpecified"), fieldName),
          400);
      return null;
    } else if (value < minValue) {
      sendError(
          segment,
          message,
          MessageFormat.format(
              Utils.getLocalizedString(message, "MustBeGreaterThanOrEqualTo"), fieldName, minValue),
          400);
      return null;
    } else {
      return value;
    }
  }

  public <T> Boolean getRequiredBoolean(
      Entity segment, @NonNls String fieldName, Message<T> message, JsonObject jsonObject) {
    Boolean value = jsonObject.getBoolean(fieldName);
    if (value == null) {
      sendError(
          segment,
          message,
          MessageFormat.format(Utils.getLocalizedString(message, "MustBeSpecified"), fieldName),
          400);
      return null;
    } else {
      return value;
    }
  }

  protected void storageLog(
      Entity parentEntity,
      Message<JsonObject> message,
      String userId,
      String graebertId,
      String sessionId,
      String username,
      String storageType,
      String externalId,
      boolean connect,
      String intercomAccessToken) {
    new ExecutorServiceAsyncRunner(
            executorService, OperationGroup.DYNAMO_BUS_MODE, parentEntity, message)
        .withName("storageLog")
        .run((Segment blockingSegment) -> {
          try {
            StorageLog storageLog = new StorageLog(
                userId,
                "NONE",
                StorageType.getStorageType(storageType),
                username,
                GMTHelper.utcCurrentTime(),
                true,
                connect ? StorageActions.CONNECT : StorageActions.DISCONNECT,
                externalId);
            storageLog.sendToServer();
          } catch (Exception ex) {
            log.error(ex);
          }
          if (graebertId != null && intercomAccessToken != null && !intercomAccessToken.isEmpty()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(Field.STORAGE_TYPE.getName(), storageType);
            metadata.put("date", GMTHelper.utcCurrentTime());
            metadata.put(Field.EXTERNAL_ID.getName(), externalId);
            String intercomAppId = null;
            if (Utils.isStringNotNullOrEmpty(userId)) {
              try {
                Item user = Users.getUserById(userId);
                if (user != null && user.hasAttribute(Field.INTERCOM_APP_ID.getName())) {
                  intercomAppId = user.getString(Field.INTERCOM_APP_ID.getName());
                }
              } catch (Exception ignored) {
              }
            }
            IntercomConnection.sendEvent(
                "Storage " + (connect ? "connect" : "disconnect"),
                graebertId,
                metadata,
                intercomAccessToken,
                intercomAppId);
          }
          eb_send(
              blockingSegment,
              WebSocketManager.address + (connect ? ".storageAdd" : ".storageRemove"),
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.SESSION_ID.getName(), sessionId));
          eb_send(
              blockingSegment,
              RecentFilesVerticle.address + ".updateRecentFile",
              new JsonObject()
                  .put(Field.USER_ID.getName(), userId)
                  .put(Field.EXTERNAL_ID.getName(), externalId)
                  .put(Field.STORAGE_TYPE.getName(), storageType));
        });
  }

  protected <T> String findExternalId(Entity segment, Message<T> message, StorageType storageType) {
    return findExternalId(
        segment, message, MessageUtils.parse(message).getJsonObject(), storageType);
  }

  protected <T> String findExternalId(
      Entity segment, Message<T> message, JsonObject json, StorageType storageType) {
    String externalId = json.getString(Field.EXTERNAL_ID.getName());
    String fileId = json.getString(Field.FILE_ID.getName());
    String userId =
        json.getString(Field.USER_ID.getName(), json.getString(Field.OWNER_ID.getName()));
    if (!Utils.isStringNotNullOrEmpty(externalId)
        && Utils.isStringNotNullOrEmpty(fileId)
        && Utils.isStringNotNullOrEmpty(userId)) {
      // in the case we have a fileId, but no externalId, this is coming from a file notification.
      // We should search for the file in all our connections,
      // similarly how this is handled in globalsearch. We can then decide if we want to cache this
      // info
      Item connection = ExternalAccounts.getCachedExternalAccount(userId, storageType, fileId);
      if (connection != null) {
        Item foreignUser = ExternalAccounts.getExternalAccount(
            userId, connection.getString(Field.EXTERNAL_ID_LOWER.getName()));
        if (foreignUser != null
            && (!foreignUser.hasAttribute(Field.ACTIVE.getName())
                || foreignUser.getBoolean(Field.ACTIVE.getName()))) {
          return connection.getString(Field.EXTERNAL_ID_LOWER.getName());
        }
      }

      Iterator<Item> accounts = ExternalAccounts.getExternalAccountsByUserId(userId, storageType);

      while (accounts.hasNext()) {
        Item account = accounts.next();
        log.info("Going to check: " + fileId + " (storage: " + storageType.name()
            + " ) with externalId: " + account.getString(Field.EXTERNAL_ID_LOWER.getName()));
        boolean fileCheckStatus = this.checkFile(
            segment,
            message,
            json.put(
                Field.EXTERNAL_ID.getName(), account.getString(Field.EXTERNAL_ID_LOWER.getName())),
            fileId);
        log.info("Checked file: " + fileId + " with externalId: "
            + account.getString(Field.EXTERNAL_ID_LOWER.getName()) + " result is: "
            + fileCheckStatus);
        if (fileCheckStatus) {
          // we should cache this
          ExternalAccounts.cacheExternalAccount(new Item()
              .withPrimaryKey(
                  Field.PK.getName(),
                  "externalid#" + userId,
                  Field.SK.getName(),
                  StorageType.getShort(storageType) + fileId)
              .withString(
                  Field.EXTERNAL_ID_LOWER.getName(),
                  account.getString(Field.EXTERNAL_ID_LOWER.getName())));

          return account.getString(Field.EXTERNAL_ID_LOWER.getName());
        }
      }
    }
    if (externalId != null && externalId.isEmpty()) {
      return null;
    }
    return externalId;
  }

  /**
   * @param segment xray segment
   * @param message eb message
   * @param json    json data (fileId, externalId, userId, etc.)
   * @param fileId  Id of file requested
   * @return true if file exists for this account
   */
  protected <T> boolean checkFile(
      final Entity segment, final Message<T> message, final JsonObject json, final String fileId) {
    return false;
  }

  protected enum LogLevel {
    INFO,
    ERROR,
    WARN
  }

  public <T> boolean isStorageDisabled(
      Entity segment, Message<T> message, StorageType storageType, String userId) {
    try {
      if (StorageDAO.isDisabled(storageType, userId)) {
        throw new KudoFileException(
            Utils.getLocalizedString(message, "StorageIsDisabled"),
            KudoErrorCodes.STORAGE_IS_DISABLED,
            HttpStatus.FORBIDDEN,
            "StorageIsDisabled");
      }
    } catch (KudoFileException kfe) {
      sendError(segment, message, kfe.toResponseObject(), kfe.getHttpStatus(), kfe.getErrorId());
      return true;
    }
    return false;
  }

  protected void finishGetFileVersionByToken(
      Message<JsonObject> message,
      byte[] result,
      String versionId,
      String name,
      boolean downloadLink) {
    Buffer jsonBuffer = new JsonObject()
        .put(Field.VER_ID.getName(), versionId)
        .put(Field.VERSION_ID.getName(), versionId)
        .put(Field.NAME.getName(), name)
        .put(Field.STATUS.getName(), OK)
        .put("downloadLink", downloadLink)
        .toBuffer();

    message.reply(Buffer.buffer()
        .appendInt(jsonBuffer.length())
        .appendBuffer(jsonBuffer)
        .appendBytes(result));
  }
}
