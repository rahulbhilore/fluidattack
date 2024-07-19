package com.graebert.storage.stats.kinesis;

import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehose;
import com.amazonaws.services.kinesisfirehose.AmazonKinesisFirehoseClientBuilder;
import com.amazonaws.services.kinesisfirehose.model.PutRecordBatchRequest;
import com.amazonaws.services.kinesisfirehose.model.PutRecordRequest;
import com.amazonaws.services.kinesisfirehose.model.Record;
import com.graebert.storage.config.Properties;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.stats.logs.BasicLog;
import com.graebert.storage.vertx.DynamoBusModBase;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.commons.io.FileUtils;

public class AWSKinesisClient {
  private static final Charset charset = StandardCharsets.UTF_8;
  public static String sessionStream;
  public static String emailStream;
  public static String sharingStream;
  public static String mentionStream;
  public static String subscriptionStream;
  public static String storageStream;
  public static String filesStream;
  public static String blockLibraryStream;
  public static String dataplaneStream;
  private static AmazonKinesisFirehose client;
  private static int batchLimit;
  private static File logsFile;

  public static void init(Properties properties) {
    // init server configs
    final ServerConfig serverConfig = new ServerConfig();
    serverConfig.init(properties);

    // set region and log streams from configs
    String region = serverConfig.getProperties().getKinesisRegion();

    // set batch limit to each stream
    batchLimit = serverConfig.getProperties().getKinesisBatchLimit();

    // init default client to work with all streams
    client = AmazonKinesisFirehoseClientBuilder.standard().withRegion(region).build();

    // set stream names
    sessionStream = serverConfig.getProperties().getKinesisSessionLogStream();
    emailStream = serverConfig.getProperties().getKinesisEmailLogStream();
    sharingStream = serverConfig.getProperties().getKinesisSharingLogStream();
    mentionStream = serverConfig.getProperties().getKinesisMentionLogStream();
    subscriptionStream = serverConfig.getProperties().getKinesisSubscriptionLogStream();
    storageStream = serverConfig.getProperties().getKinesisStorageLogStream();
    filesStream = serverConfig.getProperties().getKinesisFilesLogStream();
    blockLibraryStream = serverConfig.getProperties().getKinesisBlockLibraryLogStream();
    dataplaneStream = serverConfig.getProperties().getKinesisDataplaneLogStream();

    if (batchLimit > 1) {
      try {
        logsFile = new File(serverConfig.getProperties().getKinesisFilePath());
        if (logsFile.createNewFile()) {
          if (FileUtils.readFileToByteArray(logsFile).length == 0) {
            FileUtils.write(logsFile, new JsonObject().encode(), charset);
          }
        }
      } catch (Exception e) {
        DynamoBusModBase.log.error("KINESIS file init error", e);
      }
    }
  }

  public static void putRecord(BasicLog log, String streamName) {
    if (batchLimit > 1) {
      putRecordBatch(log, streamName);
    } else {
      putRecordSingle(log, streamName);
    }
  }

  // 1 record: instant push
  private static void putRecordSingle(BasicLog log, String streamName) {
    client.putRecord(new PutRecordRequest()
        .withDeliveryStreamName(streamName)
        .withRecord(
            new Record().withData(ByteBuffer.wrap(log.toJson().toString().getBytes())))
        // Setting request timeout as 2 minutes
        .withSdkRequestTimeout(120 * 1000)
        .withSdkClientExecutionTimeout(120 * 1000));
  }

  // 1 record: batch push with limit
  private static void putRecordBatch(BasicLog log, String streamName) {
    DynamoBusModBase.log.info("KINESIS Going to insert requests from: "
        + log.getClass().getName() + " class into stream: " + streamName);
    try {
      JsonObject logs = checkForBatch(streamName);

      if (!logs.containsKey(streamName)) {
        logs.put(streamName, new JsonArray().add(log.toJson()));
      } else {
        logs.getJsonArray(streamName).add(log.toJson());
      }

      FileUtils.write(logsFile, logs.encode(), charset);
    } catch (Exception e) {
      DynamoBusModBase.log.error("KINESIS error", e);
    }
  }

  private static JsonObject checkForBatch(String streamName) {
    JsonObject logs;
    try {
      logs = new JsonObject(FileUtils.readFileToString(logsFile, charset));
    } catch (Exception e) {
      return new JsonObject();
    }

    if (logs.containsKey(streamName) && logs.getJsonArray(streamName).size() > batchLimit - 1) {
      PutRecordBatchRequest request = createRequest(streamName);
      for (Object o : logs.getJsonArray(streamName)) {
        Record record = new Record().withData(ByteBuffer.wrap(o.toString().getBytes()));
        request.withRecords(record);
      }

      client.putRecordBatch(request);

      logs.remove(streamName);
    }

    return logs;
  }

  private static PutRecordBatchRequest createRequest(String streamName) {
    return new PutRecordBatchRequest()
        .withRecords(new ArrayList<>())
        .withDeliveryStreamName(streamName);
  }

  public static void flushLogs() {
    JsonObject logs;
    try {
      logs = new JsonObject(FileUtils.readFileToString(logsFile, charset));
      FileUtils.write(logsFile, new JsonObject().encode(), charset);
    } catch (Exception e) {
      return;
    }

    logs.forEach(streamObject -> {
      String streamName = streamObject.getKey();
      JsonArray records = (JsonArray) streamObject.getValue();

      PutRecordBatchRequest request = createRequest(streamName);
      for (Object rec : records) {
        Record record = new Record().withData(ByteBuffer.wrap(rec.toString().getBytes()));
        request.withRecords(record);
      }

      client.putRecordBatch(request);
    });
  }
}
