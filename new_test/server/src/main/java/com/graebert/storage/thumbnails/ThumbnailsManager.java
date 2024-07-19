package com.graebert.storage.thumbnails;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.config.ServerConfig;
import com.graebert.storage.gridfs.FileFormats;
import com.graebert.storage.gridfs.RecentFilesVerticle;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.CloudFieldFileVersion;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.DownloadRequests;
import com.graebert.storage.storage.ExternalAccounts;
import com.graebert.storage.storage.FileMetaData;
import com.graebert.storage.storage.JobStatus;
import com.graebert.storage.storage.Sessions;
import com.graebert.storage.storage.Users;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.util.message.MessageUtils;
import com.graebert.storage.util.message.ParsedMessage;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.ws.WebSocketManager;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;

public class ThumbnailsManager extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.THUMBNAILS;
  public static final String address = "thumbnails";
  protected static final String pattern = "https://sqs.{region}.amazonaws.com/{accnumber}/{queue}";
  private static final String compareSQSMessage =
      "%s;compare;https://s3.amazonaws.com/%s/dwg/%s%s;https://s3.amazonaws.com/%s/dwg/%s%s;"
          + "https://s3.amazonaws.com/%s/compare/%s;%d";
  private static final String thumbnailExtension = "png";
  private static final String thumbnailFolder = "png";
  private static final String previewFolder = Field.PREVIEW.getName();
  public static final String partsSeparator = "_";
  private static String bucket, queue, chunkQueue, responses;
  private static S3Regional s3Regional = null;

  private static final String logPrefix = "[THUMBNAILS]";
  private static final String notifyLogPrefix = "[THUMBNAILS_NOTIFICATION]";

  @Override
  public void start() throws Exception {
    super.start();
    bucket = config.getProperties().getCfDistribution();
    String pattern = ThumbnailsManager.pattern
        .replace("{region}", config.getProperties().getSqsRegion())
        .replace("{accnumber}", config.getProperties().getAwsAccountNumber());
    queue = pattern.replace("{queue}", config.getProperties().getSqsQueue());
    chunkQueue = pattern.replace("{queue}", config.getProperties().getSqsChunkQueue());
    responses = pattern.replace("{queue}", config.getProperties().getSqsResponses());

    // thumbnails stuff might take longer to complete, so just set execute time bigger than regular
    executor = vertx.createSharedWorkerExecutor(
        "vert.x-new-internal-blocking-thumbnails",
        ServerConfig.WORKER_POOL_SIZE,
        5,
        TimeUnit.MINUTES);

    eb.consumer(address + ".createForPublic", (Message<JsonObject> message) -> {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
          .withName("createPublicThumbnails")
          .runWithoutSegment(() -> processPublicThumbnails(message));
    });

    eb.consumer(address + ".createForRecent", this::requestRecentThumbnails);

    eb.consumer(address + ".createForCustom", (Message<JsonObject> message) -> {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
          .withName("createCustomThumbnails")
          .runWithoutSegment(() -> createThumbnailForCustomEntity(message));
    });

    eb.consumer(address + ".create", (Message<JsonObject> message) -> {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
          .withName("createThumbnails")
          .runWithoutSegment(() -> checkAndRequestThumbnails(message));
    });

    eb.consumer(address + ".convertFile", (Message<JsonObject> message) -> {
      new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
          .withName("convertFile")
          .runWithoutSegment(() -> doConvertFile(message));
    });

    eb.consumer(address + ".uploadPreview", this::doUploadPreview);

    // Drawing compare
    eb.consumer(address + ".compareDrawings", (Message<JsonObject> message) -> {
      Entity segment = XRayManager.createSegment(operationGroup, message);

      String requestId = JobPrefixes.COMPARE.prefix + Utils.generateUUID();
      String s3ComparisonId = getTargetS3IdForComparison(message);

      if (s3ComparisonId != null) {
        if (s3ComparisonId.contains(Field.LATEST.getName())
            && s3Regional.doesObjectExistInBucket(bucket, "compare/" + s3ComparisonId)) {
          // remove existing result
          s3Regional.deleteFromBucket(bucket, "compare/" + s3ComparisonId);
        }
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.ID.getName(), requestId)
                .put(
                    Field.URL.getName(),
                    s3Regional.generatePresignedUrl(
                        bucket,
                        "compare/" + getTargetS3IdForComparison(message),
                        true,
                        60 * Utils.MINUTE_IN_MS)),
            0);
      } else {
        sendError(segment, message, 400, "CD3");
      }

      new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
          .withName("compareDrawings")
          .runWithoutSegment(() -> compareDrawings(message, requestId));
    });

    eb.consumer(address + ".updateGeneration", this::doUpdateGeneration);

    eb.consumer(address + ".checkAndPostChunksToSQS", this::checkAndPostChunksToSQS);

    eb.consumer(address + ".handleThumbnailChunksFromSQS", this::handleThumbnailChunksFromSQS);

    eb.consumer(address + ".handleIncomingMessages", this::handleIncomingMessages);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
  }

  public static String getThumbnailName(String storageType, String fileId, String versionId) {
    return storageType + partsSeparator + fileId + partsSeparator + versionId + "."
        + thumbnailExtension;
  }

  public static String getThumbnailName(StorageType storageType, String fileId, String versionId) {
    return getThumbnailName(StorageType.getShort(storageType), fileId, versionId);
  }

  public static String getPreviewName(String storageType, String fileId) {
    return storageType + partsSeparator + fileId;
  }

  private static String getUrl(
      ServerConfig config, String thumbnailName, boolean encode, String folder) {
    if (!Utils.isStringNotNullOrEmpty(thumbnailName)) {
      return emptyString;
    }
    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    S3Regional s3Regional = new S3Regional(config, bucket, region);
    if (!thumbnailName.contains(folder + "/")) {
      thumbnailName = folder + "/" + thumbnailName;
    }
    return s3Regional.generatePresignedUrl(
        config.getProperties().getCfDistribution(), thumbnailName, encode);
  }

  public static String getThumbnailURL(ServerConfig config, String thumbnailName, boolean encode) {
    return getUrl(config, thumbnailName, encode, thumbnailFolder);
  }

  public static String getPreviewURL(ServerConfig config, String previewName, boolean encode) {
    return getUrl(config, previewName, encode, previewFolder);
  }

  public static String getPublicFileThumbnailS3Path(String fileS3Path) {
    // if it has some extension - it'll be replaced with png
    if (fileS3Path.matches(".*\\..+")) {
      return thumbnailFolder + "/"
          + fileS3Path.replaceFirst("(.*)\\..+", "$1." + thumbnailExtension);
    }
    return thumbnailFolder + "/" + fileS3Path + "." + thumbnailExtension;
  }

  public static String getPublicFilePreviewS3Path(String fileS3Path) {
    // if it has some extension - it'll be replaced with png
    if (fileS3Path.matches(".*\\..+")) {
      return previewFolder + "/" + fileS3Path.replaceFirst("(.*)\\..+", "$1." + thumbnailExtension);
    }
    return previewFolder + "/" + fileS3Path + "." + thumbnailExtension;
  }

  private static String getTargetS3IdForComparison(Message<JsonObject> message) {
    JsonObject data = message.body();
    if (!data.containsKey(Field.FILES.getName())) {
      log.error(logPrefix + " Incorrect request - no files");
      return null;
    }
    JsonArray filesToCompare = data.getJsonArray(Field.FILES.getName());
    if (filesToCompare.size() != 2) {
      log.error(logPrefix + " Incorrect request - # files: " + filesToCompare.size());
      return null;
    }
    JsonObject firstFile = filesToCompare.getJsonObject(0);
    JsonObject secondFile = filesToCompare.getJsonObject(1);
    return getTargetS3IdForComparison(
        firstFile.getString(Field.ID.getName()),
        firstFile.getString(Field.VERSION_ID.getName()),
        secondFile.getString(Field.ID.getName()),
        secondFile.getString(Field.VERSION_ID.getName()));
  }

  private static String getTargetS3IdForComparison(
      String fileId1, final String versionId1, String fileId2, final String versionId2) {
    fileId1 = fileId1.replace("+", "--");
    fileId2 = fileId2.replace("+", "--");
    String finalVersionId1 =
        Utils.isStringNotNullOrEmpty(versionId1) ? versionId1 : Field.LATEST.getName();
    String finalVersionId2 =
        Utils.isStringNotNullOrEmpty(versionId2) ? versionId2 : Field.LATEST.getName();
    if (fileId1.equals(fileId2)) {
      return fileId1 + partsSeparator + finalVersionId1 + partsSeparator + finalVersionId2 + "."
          + thumbnailExtension;
    }
    return fileId1 + partsSeparator + finalVersionId1 + partsSeparator + fileId2 + partsSeparator
        + finalVersionId2 + "." + thumbnailExtension;
  }

  private void createThumbnailForCustomEntity(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    JsonObject body = message.body();
    JsonArray ids = body.getJsonArray(Field.IDS.getName());
    JsonObject preferences = body.getJsonObject(Field.PREFERENCES.getName());
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    boolean force =
        body.containsKey(Field.FORCE.getName()) && body.getBoolean(Field.FORCE.getName());

    XRayEntityUtils.putMetadata(segment, XrayField.IDS, ids);
    XRayEntityUtils.putMetadata(segment, XrayField.PREFERENCES, preferences);
    XRayEntityUtils.putMetadata(segment, XrayField.STORAGE_TYPE, storageType);
    XRayEntityUtils.putAnnotation(segment, XrayField.STORAGE_TYPE, storageType);
    XRayEntityUtils.putMetadata(segment, XrayField.FORCE, force);

    int colour = preferences != null
            && preferences.containsKey("preferences_display")
            && preferences
                .getJsonObject("preferences_display")
                .containsKey("graphicswinmodelbackgrndcolor")
            && preferences
                .getJsonObject("preferences_display")
                .getString("graphicswinmodelbackgrndcolor")
                .equals("white")
        ? 1
        : 0;

    List<JsonObject> thumbnailsToGenerate = Arrays.stream(ids.stream().toArray())
        .filter(
            item -> checkIfThumbnailRequestRequired((JsonObject) item, force, storageType, true))
        .map(item -> (JsonObject) item)
        .collect(Collectors.toList());

    S3Regional currentSetS3 = new S3Regional(
        body.getString(Field.S3_BUCKET.getName()), body.getString(Field.S3_REGION.getName()));
    log.info(logPrefix + " " + thumbnailsToGenerate.size()
        + " thumbnails to generate for custom entities");
    if (!thumbnailsToGenerate.isEmpty()) {
      thumbnailsToGenerate.forEach((toGenerate) -> {
        String requestId = toGenerate.getString(Field.REQUEST_ID.getName());
        String extension = toGenerate.getString(Field.EXT.getName());
        String fileId = toGenerate.getString(Field.ID.getName());
        String versionId = toGenerate.getString(Field.VERSION_ID.getName());
        byte[] fileData = currentSetS3.get(toGenerate.getString(Field.SOURCE.getName()));
        String fullId = getFullFileId(fileId, storageType, versionId, true);
        s3Regional.putPublic(bucket, "dwg/" + fullId + extension, fileData);
        String sqsMessage =
            "%s;https://s3.amazonaws.com/%s/dwg/%s%s;https://s3.amazonaws.com/%s/%s/%s.%s;250;"
                + "250;0;%d";

        String msg = String.format(
            sqsMessage,
            requestId,
            bucket,
            fullId,
            extension,
            bucket,
            thumbnailFolder,
            fullId,
            thumbnailExtension,
            colour);
        sendThumbnailRequestToSQS(
            segment, message, msg, fullId + "." + thumbnailExtension, fileId, null, requestId);
      });
    }

    XRayManager.endSegment(segment);
  }

  public static boolean checkIfThumbnailRequestRequired(
      JsonObject thumbnailInfo, boolean force, String storageType, boolean isCustom) {
    String fileId =
        thumbnailInfo.getString(isCustom ? Field.ID.getName() : Field.FILE_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(fileId)) {
      log.error(
          logPrefix + " empty fileId: " + fileId + " Thumbnail info received: "
              + thumbnailInfo.encodePrettily() + " storage: " + storageType,
          new Exception("Empty fileId on checkIfThumbnailRequestRequired"));
    }
    String versionId = thumbnailInfo.getString(Field.VERSION_ID.getName());
    if (!Utils.isStringNotNullOrEmpty(versionId)) {
      log.error(
          logPrefix + " empty versionId: " + versionId + " Thumbnail info received: "
              + thumbnailInfo.encodePrettily() + " storage: " + storageType,
          new Exception("Empty versionId on checkIfThumbnailRequestRequired"));
    }
    String fullId = getFullFileId(fileId, storageType, versionId, isCustom);
    String requestId = JobPrefixes.THUMBNAIL.prefix + fullId;
    // check if the thumbnail for the version of the file exists
    boolean availableInS3 = checkThumbnailInS3(fullId, force, true);

    log.info(logPrefix + " file: " + fullId + " existance:" + availableInS3);
    // check if the thumbnail request status in DB
    Item dbJob = SQSRequests.findRequest(requestId);
    // regular not important thumbnail
    if (!force) {
      if (dbJob != null && dbJob.hasAttribute(Field.STATUS.getName())) {
        String thumbnailStatus = dbJob.getString(Field.STATUS.getName());
        log.info(logPrefix + " status for file " + fullId + " is " + thumbnailStatus);
        if (!thumbnailStatus.equals(ThumbnailStatus.UNAVAILABLE.name())) {
          if (thumbnailStatus.equals(ThumbnailStatus.AVAILABLE.name())) {
            if (availableInS3) {
              // job says it's available and it actually is
              return false;
            } else {
              // job says it's available but it actually isn't -> request
              SQSRequests.updateThumbnailRequest(requestId, ThumbnailStatus.UNAVAILABLE, null);
              // don't return as we'll create a job later
            }
          } else {
            // job says that thumbnail is loading or could not be generated - wait for it
            return false;
          }
        }
      } else {
        log.info(logPrefix + " file: " + fullId + " doesn't have db entry");
      }
    }
    // we must re-request or there's no job in DB
    if (availableInS3) {
      if (dbJob != null) {
        if (!dbJob.hasAttribute(Field.STATUS.getName())
            || !dbJob.getString(Field.STATUS.getName()).equals(ThumbnailStatus.AVAILABLE.name())) {
          SQSRequests.updateThumbnailRequest(requestId, ThumbnailStatus.AVAILABLE, null);
        }
      } else {
        SQSRequests.saveThumbnailRequest(requestId, ThumbnailStatus.AVAILABLE);
      }
      // Thumbnail is already in S3 - no need to generate
      return false;
    }
    // Thumbnail isn't in S3 - need to generate
    return true;
  }

  public static String getFullFileId(
      String fileId, String storageType, String versionId, boolean isCustom) {
    if (isCustom) {
      return storageType + partsSeparator + fileId + partsSeparator + versionId;
    }
    String encodedFileId = storageType.startsWith(StorageType.getShort(StorageType.WEBDAV))
        ? fileId.replaceAll(":", emptyString)
        : fileId;
    return StorageType.getShort(storageType)
        + partsSeparator
        + encodedFileId
        + partsSeparator
        + versionId;
  }

  public static boolean checkThumbnailInS3(
      String fullId, boolean force, boolean deleteExistingThumbnail) {
    if (s3Regional.doesObjectExistInBucket(
        bucket, thumbnailFolder + "/" + fullId + "." + thumbnailExtension)) {
      log.info(
          logPrefix + " file " + fullId + " exists in bucket " + bucket + " force is " + force);
      if (force) {
        if (deleteExistingThumbnail) {
          log.info(logPrefix + " file " + fullId + " deleting existing thumbnail");
          s3Regional.deleteObject(
              bucket, thumbnailFolder + "/" + fullId + "." + thumbnailExtension);
        } else {
          return false;
        }
      } else {
        return true;
      }
    }
    return false;
  }

  public void checkAndPostChunksToSQS(Message<JsonObject> message) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
        .withName("handleIncomingMessages")
        .run((Segment blockingSegment) -> {
          ConcurrentMap<String, ConcurrentMap<String, ConcurrentMap<String, Object>>>
              allThumbnailChunks = ThumbnailsDao.getAllThumbnailChunks();
          if (blockingSegment != null) {
            blockingSegment.putMetadata(
                "waitingChunks", allThumbnailChunks == null ? 0 : allThumbnailChunks.size());
          }
          if (Utils.isMapNotNullOrEmpty(allThumbnailChunks)) {
            for (String groupName : allThumbnailChunks.keySet()) {
              ConcurrentMap<String, ConcurrentMap<String, Object>> groupChunks =
                  allThumbnailChunks.get(groupName);
              Iterator<String> groupChunksIterator = groupChunks.keySet().iterator();
              if (groupChunksIterator.hasNext()) {
                String chunkId = groupChunksIterator.next();
                String storageType = chunkId.substring(0, chunkId.indexOf("_"));
                Map<String, Object> chunkData = groupChunks.get(chunkId);
                boolean canProcessChunk =
                    ThumbnailsDao.areNoChunksProcessing(groupName, storageType);
                if (blockingSegment != null) {
                  XRayEntityUtils.putAnnotation(
                      blockingSegment, XrayField.STORAGE_TYPE, storageType);
                  XRayEntityUtils.putMetadata(
                      blockingSegment, XrayField.CAN_PROCESS_CHUNK, canProcessChunk);
                }
                if (Utils.isMapNotNullOrEmpty(chunkData) && canProcessChunk) {
                  JsonObject info = (JsonObject) chunkData.get("info");
                  boolean force = info.containsKey("force") && info.getBoolean("force");
                  List<JsonObject> chunkFileIds = (List<JsonObject>) chunkData.get("chunk");
                  // recheck to make sure we don't request the already requested thumbnails
                  chunkFileIds = chunkFileIds.stream()
                      .filter(
                          item -> checkIfThumbnailRequestRequired(item, force, storageType, false))
                      .collect(Collectors.toList());
                  if (Utils.isListNotNullOrEmpty(chunkFileIds)) {
                    createThumbnailChunkRequestForSQS(
                        blockingSegment, message, chunkId, info, chunkFileIds, groupName);
                  }
                  log.info(logPrefix + " Going to remove chunk from the server : chunkId - "
                      + chunkId + " with groupName - " + groupName);
                  // removing the chunk from the server
                  ThumbnailsDao.saveThumbnailChunkOnServer(groupName, chunkId, null, null, false);
                }
              }
            }
          }
        });
  }

  /**
   * Retrieves SQS requests for thumbnails to pass them to generation threads
   * Executes every 14 seconds by Periodic
   */
  private void handleThumbnailChunksFromSQS(Message<JsonObject> message) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
        .withName("handleThumbnailChunksFromSQS")
        .run((Segment blockingSegment) -> {
          ReceiveMessageRequest request =
              new ReceiveMessageRequest(chunkQueue).withWaitTimeSeconds(10);
          request.setMaxNumberOfMessages(10);
          List<com.amazonaws.services.sqs.model.Message> messages =
              sqs.receiveMessage(request).getMessages();
          if (blockingSegment != null) {
            XRayEntityUtils.putMetadata(
                blockingSegment, XrayField.AMOUNT_OF_RECEIVED_SQS_MESSAGES, messages.size());
          }
          for (com.amazonaws.services.sqs.model.Message m : messages) {
            String body = m.getBody();
            final String messageReceiptHandle = m.getReceiptHandle();
            sqs.deleteMessage(new DeleteMessageRequest(chunkQueue, messageReceiptHandle));
            log.info(logPrefix + " (Chunks) Received following message: " + body);
            String[] splitBody = body.split("#");
            if (splitBody.length < 3) {
              log.warn(logPrefix + " Unknown SQS message received. Message body: " + body);
            } else {
              String chunkId = splitBody[1];
              String groupName = splitBody[2];
              groupName = URLDecoder.decode(groupName, StandardCharsets.UTF_8);
              String storageType = chunkId.substring(0, chunkId.indexOf("_"));
              log.info(logPrefix + " (Chunks) Received following params: ChunkId: " + chunkId
                  + " groupName: " + groupName);
              List<JsonObject> failedIds = new ArrayList<>();
              Item item = SQSRequests.getThumbnailChunkRequest(chunkId, groupName);
              if (item == null) {
                return;
              }

              JsonObject info = new JsonObject(item.getMap(Field.INFO.getName()));
              SQSRequests.updateThumbnailChunkRequest(
                  chunkId, groupName, SQSRequests.ThumbnailChunkStatus.PROCESSING);
              Promise<Boolean> promise = Promise.promise();
              String finalGroupName = groupName;
              Thread thumbnailsGenerationThread = new Thread(() -> {
                Entity subsegment = XRayManager.createIndependentStandaloneSegment(
                    operationGroup, "thumbnailsGenerationThread");

                List<Map<String, Object>> ids = item.getList("fileIds");

                XRayEntityUtils.putMetadata(
                    subsegment, XrayField.IDS, new JsonArray(ids).toString());

                AtomicInteger count = new AtomicInteger();
                ids.stream().map(JsonObject::new).forEach(obj -> checkSingleThumbnail(
                        subsegment, message, info, obj, storageType, info.getInteger("colour"))
                    .onComplete(event -> {
                      if (event.failed()) {
                        if (event.result() != null) {
                          failedIds.add(new JsonObject(event.result()));
                        }
                      }
                      count.addAndGet(1);
                      if (count.get() == ids.size()) {
                        promise.complete();
                      }
                    }));
                promise.future().onComplete(event -> {
                  if (!failedIds.isEmpty()) {
                    log.info(logPrefix + " Going to add failed chunks on the server : chunkId - "
                        + chunkId + " | chunk - " + failedIds);
                    ThumbnailsDao.saveThumbnailChunkOnServer(
                        finalGroupName, chunkId, info, failedIds, true);
                  }
                  SQSRequests.updateThumbnailChunkRequest(
                      chunkId, finalGroupName, SQSRequests.ThumbnailChunkStatus.PROCESSED);

                  XRayManager.endSegment(subsegment);
                });
              });
              thumbnailsGenerationThread.start();
            }
          }

          message.reply(null);
        });
  }

  private void handleConvertResult(String jobId, String result) {
    Entity subSegment = XRayManager.createSubSegment(operationGroup, "handleConvertResult");
    XRayEntityUtils.putMetadata(subSegment, XrayField.JOB_ID, jobId);
    XRayEntityUtils.putMetadata(subSegment, XrayField.RESULT, result);

    boolean isUpdateSuccessful = SQSRequests.updateRequestStatus(
        jobId, result.startsWith("fail") ? Field.ERROR.getName() : "success", result);
    String token = jobId.substring(JobPrefixes.CONVERT.prefix.length());

    JobStatus endStatus = JobStatus.ERROR;
    if (!result.startsWith("fail") && isUpdateSuccessful) {
      endStatus = JobStatus.SUCCESS;
      log.info(String.format(
          "[CONVERT] (handleConvertResult) successful conversion | token: %s , jobId: %s , result: %s",
          token, jobId, result));
    } else {
      log.error(String.format(
          "[CONVERT] (handleConvertResult) conversion failed | token: %s , jobId: %s , result: %s",
          token, jobId, result));
    }
    DownloadRequests.setRequestData(token, null, endStatus, result, null);
    if (!isUpdateSuccessful) {
      XRayEntityUtils.addException(subSegment, new Exception("Could not find job " + jobId));
      log.error("Could not find job " + jobId);
    }

    XRayManager.endSegment(subSegment);
  }

  private void handleCompareResult(String jobId) {
    Entity subSegment = XRayManager.createSubSegment(operationGroup, "handleCompareResult");
    XRayEntityUtils.putMetadata(subSegment, XrayField.JOB_ID, jobId);

    Item compareRequest = SQSRequests.findRequest(jobId);
    if (compareRequest != null) {
      eb_send(
          subSegment,
          WebSocketManager.address + ".comparisonReady",
          new JsonObject()
              .put("firstFile", compareRequest.getString("firstFile"))
              .put("secondFile", compareRequest.getString("secondFile"))
              .put(Field.USER_ID.getName(), compareRequest.getString(Field.USER_ID.getName()))
              .put(Field.JOB_ID.getName(), jobId));
    }

    XRayManager.endSegment(subSegment);
  }

  private void handleThumbnail(String jobId, String result) {
    Entity subSegment = XRayManager.createSubSegment(operationGroup, "handleThumbnail");
    // 17/2/21 - jobId is now in format prefix#storageCode__fileId__versionId
    // We need to change thumbnailName to fileId
    // jobId -> storageCode_externalId_fileId
    try {
      XRayEntityUtils.putMetadata(subSegment, XrayField.JOB_ID, jobId);
      XRayEntityUtils.putMetadata(subSegment, XrayField.RESULT, result);

      final String jobIdWithoutPrefix = jobId.substring(JobPrefixes.THUMBNAIL.prefix.length());
      String[] idParts = ThumbnailsManager.parseThumbnailName(jobIdWithoutPrefix);

      final String fileId = idParts[1];
      StorageType storageType = StorageType.getByShort(idParts[0]);
      if (storageType == null) {
        storageType = StorageType.SAMPLES;
      }

      final String thumbnailName = jobIdWithoutPrefix + "." + thumbnailExtension;

      log.info(notifyLogPrefix + " RECEIVED MESSAGE FOR : " + fileId + " " + storageType.name());
      if (result.startsWith("fail")) {
        String failedDetails = result.substring(result.indexOf(":") + 1).trim();
        SQSRequests.updateThumbnailRequest(jobId, ThumbnailStatus.UNAVAILABLE, failedDetails);
        return;
      } else {
        SQSRequests.updateThumbnailRequest(jobId, ThumbnailStatus.AVAILABLE, null);
      }

      // update thumbnail in metadata
      Item metadata = FileMetaData.getMetaData(fileId, storageType.name());
      if (metadata != null
          && (!metadata.hasAttribute(Field.THUMBNAIL.getName())
              || !metadata.getString(Field.THUMBNAIL.getName()).contains(jobIdWithoutPrefix))) {
        log.info(notifyLogPrefix + " Updating thumbnail in metadata");
        log.info("[ METADATA ] Update on thumbnail update");
        FileMetaData.putMetaData(
            fileId,
            storageType,
            metadata.getString(Field.FILE_NAME.getName()),
            thumbnailName,
            metadata.getString(Field.VERSION_ID.getName()),
            metadata.getString(Field.OWNER_ID.getName()));
      }

      // update recent files
      eb_send(
          subSegment,
          RecentFilesVerticle.address + ".updateRecentFile",
          new JsonObject()
              .put(Field.FILE_ID.getName(), fileId)
              .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
              .put(Field.STORAGE_TYPE.getName(), storageType.name()));

      // send WS events
      log.info(notifyLogPrefix + " Checking WS clients");
      for (Item item : ThumbnailSubscription.findSubscriptionsForFile(fileId)) {
        String userId = item.getString(Field.SK.getName());
        log.info(notifyLogPrefix + " Going to send WS notification for user " + userId);
        eb_send(
            subSegment,
            WebSocketManager.address + ".newThumbnail",
            new JsonObject()
                .put(Field.FILE_ID.getName(), fileId)
                .put(Field.USER_ID.getName(), userId)
                .put(
                    "thumbnail",
                    s3Regional.generatePresignedUrl(
                        config.getProperties().getCfDistribution(),
                        thumbnailFolder + "/" + thumbnailName,
                        true)));
      }
    } catch (Exception ex) {
      XRayEntityUtils.addException(subSegment, ex);
      log.error(notifyLogPrefix + " Exception on checking thumbnail", ex);
    }

    XRayManager.endSegment(subSegment);
  }

  /**
   * Retrieve up to 10 messages from sqs and redirect to proper handler
   * Executes every 30 seconds by Periodic
   */
  private void handleIncomingMessages(Message<JsonObject> message) {
    new ExecutorServiceAsyncRunner(executorService, operationGroup, null, message)
        .withName("handleIncomingMessages")
        .run((Segment blockingSegment) -> {
          ReceiveMessageRequest request =
              new ReceiveMessageRequest(responses).withWaitTimeSeconds(10);
          request.setMaxNumberOfMessages(10);
          List<com.amazonaws.services.sqs.model.Message> messages =
              sqs.receiveMessage(request).getMessages();
          if (blockingSegment != null) {
            XRayEntityUtils.putMetadata(
                blockingSegment, XrayField.AMOUNT_OF_RECEIVED_SQS_MESSAGES, messages.size());
          }
          for (com.amazonaws.services.sqs.model.Message m : messages) {
            String body = m.getBody();
            String[] arr = body.split(";");
            if (arr.length < 2) {
              log.warn("Unknown SQS message received. Message body: " + body);
            } else {
              String jobId = arr[0];
              String result = arr[1];
              if (jobId.startsWith(JobPrefixes.CONVERT.prefix)) {
                this.handleConvertResult(jobId, result);
              } else if (jobId.startsWith("PARSEERROR")) {
                this.handleConvertResult(result, "fail: SQS Error");
              } else if (jobId.startsWith(JobPrefixes.COMPARE.prefix)) {
                this.handleCompareResult(jobId);
              } else if (jobId.startsWith(JobPrefixes.THUMBNAIL.prefix)) {
                this.handleThumbnail(jobId, result);
              } else {
                log.warn("Unknown SQS message received. Message body: " + body);
              }
            }
            final String messageReceiptHandle = m.getReceiptHandle();
            sqs.deleteMessage(new DeleteMessageRequest(responses, messageReceiptHandle));
          }
          message.reply(null);
        });
  }

  private void processPublicThumbnails(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    JsonObject body = message.body();
    JsonArray items = body.getJsonArray("items");
    XRayEntityUtils.putMetadata(segment, XrayField.IDS, items.toString());

    items.getList().parallelStream().forEach(o -> {
      JsonObject jsonObject = (JsonObject) o;
      createForPublic(
          segment,
          message,
          StorageType.getStorageType(jsonObject.getString(Field.STORAGE_TYPE.getName())),
          jsonObject.getString(Field.FILE_ID.getName()),
          jsonObject.getString("s3bucket"),
          jsonObject.getString("s3region"),
          jsonObject.getString("s3path"),
          jsonObject.getString("thumbnailPath"),
          jsonObject.getInteger("colorCode") == null ? 0 : body.getInteger("colorCode"),
          jsonObject.getString(Field.USER_ID.getName()));
    });

    XRayManager.endSegment(segment);
  }

  private void createForPublic(
      Entity segment,
      Message<JsonObject> message,
      StorageType storageType,
      String fileId,
      String s3Bucket,
      String s3Region,
      String s3Path,
      String thumbnailPath,
      int colorCode,
      String userId) {
    if (!Utils.isStringNotNullOrEmpty(s3Path)) {
      log.error("No paths sent for createForPublic.");
      return;
    }
    if (!Utils.isStringNotNullOrEmpty(thumbnailPath)) {
      thumbnailPath = getPublicFileThumbnailS3Path(s3Path);
    }
    if (!Utils.isStringNotNullOrEmpty(fileId)) {
      fileId = Utils.generateUUID();
    }
    if (s3Regional.doesObjectExistInBucket(bucket, thumbnailPath)) {
      log.info("Thumbnail for public file " + fileId + " exists:" + thumbnailPath);
      return;
    }
    boolean doesFileExist = false;
    String finalPath = s3Path;
    if (Utils.isStringNotNullOrEmpty(s3Bucket) && Utils.isStringNotNullOrEmpty(s3Region)) {
      S3Regional objectS3 = new S3Regional(s3Bucket, s3Region);
      if (objectS3.doesObjectExist(s3Path)) {
        doesFileExist = true;
        if (!s3Regional.doesObjectExistInBucket(bucket, "publicFiles/" + s3Path)) {
          s3Regional.putToBucket(bucket, "publicFiles/" + s3Path, objectS3.get(s3Path));
        }
        finalPath = "publicFiles/" + s3Path;
      }
    } else if (s3Regional.doesObjectExist(s3Path)) {
      doesFileExist = true;
    }
    if (!doesFileExist) {
      log.error("Public file " + fileId + " data isn't found in :" + s3Path);
      return;
    }
    String sqsMessage =
        "%s;https://s3.amazonaws.com/%s/%s;https://s3.amazonaws.com/%s/%s;250;250;0;%d";
    String msg =
        String.format(sqsMessage, fileId, bucket, finalPath, bucket, thumbnailPath, colorCode);
    // save to send proper notification
    if (Utils.isStringNotNullOrEmpty(userId)) {
      ThumbnailSubscription.save(fileId, userId);
    }
    sendThumbnailRequestToSQS(segment, message, msg, thumbnailPath, fileId, storageType, null);
  }

  private void doUploadPreview(Message<Buffer> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();

    ParsedMessage parsedMessage = MessageUtils.parse(message);

    String fileId =
        getRequiredString(segment, Field.FILE_ID.getName(), message, parsedMessage.getJsonObject());
    JsonObject fileIds = Utils.parseItemId(fileId, Field.FILE_ID.getName());

    String filename = fileIds.getString(Field.STORAGE_TYPE.getName()) == null
        ? fileIds.getString(Field.FILE_ID.getName())
        : StorageType.getShort(
                StorageType.getStorageType(fileIds.getString(Field.STORAGE_TYPE.getName())))
            + "_" + fileIds.getString(Field.FILE_ID.getName());

    ObjectMetadata metadata = new ObjectMetadata();
    metadata.setContentLength(parsedMessage.getContentAsByteArray().length);
    s3Regional.putObject(new PutObjectRequest(
        bucket,
        "preview/" + filename,
        new ByteArrayInputStream(parsedMessage.getContentAsByteArray()),
        metadata));
    sendOK(
        segment,
        message,
        new JsonObject().put(Field.PREVIEW.getName(), "/previews/" + filename),
        mills);
  }

  private void doConvertFile(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    try {
      JsonObject json = message.body();

      String storageType = json.getString(Field.STORAGE_TYPE.getName());
      final String shortST = StorageType.getShort(storageType);
      String fileId = json.getString(Field.FILE_ID.getName());
      String path = json.getString(Field.PATH.getName());
      String versionId = json.getString(Field.VERSION_ID.getName());
      String format = json.getString("format");
      String token = json.getString(Field.TOKEN.getName());
      String jobId = JobPrefixes.CONVERT.prefix + token;
      String ext = json.getString(Field.EXT.getName());

      boolean isToPdfConvert = format.equals(FileFormats.FileType.PDF.name());
      FileFormats.DwgVersion convertToFormat = FileFormats.DWGVersion(format);

      // path that will be used to store temp file
      String tempFilePath = json.getString(
          "tempFilePath",
          String.join(
              partsSeparator, shortST, fileId, versionId, FileFormats.toString(convertToFormat)));

      // path that will be used to fut final converted file
      String finalFilePath =
          json.getString("finalFilePath", token + FileFormats.FileExtension(format));

      if (!isToPdfConvert || versionId.equals(VersionType.SPECIFIC.toString())) {
        // check if the file for the version of the file exists
        if (s3Regional.doesObjectExist(finalFilePath)) {
          log.info("Download for token path to " + finalFilePath + " exists in bucket "
              + s3Regional.getBucketName());
          return;
        }
        log.info("Download for token path to " + finalFilePath + " does not exist in bucket "
            + s3Regional.getBucketName());
      }

      // check if the download has already been requested
      Item dbJob = SQSRequests.findRequest(jobId);
      if (Objects.nonNull(dbJob)) {
        log.info("Download for token " + token + " has already been requested");
        return;
      }
      log.info("Download for token " + token + " has not been requested yet");
      SQSRequests.saveThumbnailRequest(jobId, ThumbnailStatus.LOADING);

      // upload data to the bucket
      saveVersionToS3(
              segment,
              StorageType.getStorageType(storageType),
              new JsonObject(new HashMap<>(message.body().getMap()))
                  .put(
                      Field.FILE_ID.getName(), Utils.isStringNotNullOrEmpty(fileId) ? fileId : path)
                  .put(Field.VER_ID.getName(), versionId),
              jobId,
              ext,
              tempFilePath)
          .onComplete(saveResult -> {
            if (saveResult.succeeded()) {
              String msg = String.format(
                  "%s;https://s3.amazonaws.com/%s/dwg/%s%s;https://s3.amazonaws.com/%s/%s%s;0",
                  jobId,
                  bucket,
                  tempFilePath,
                  ext,
                  s3Regional.getBucketName(),
                  finalFilePath,
                  isToPdfConvert ? emptyString : ";".concat(convertToFormat.toString()));
              requestDownload(segment, message, msg, tempFilePath, shortST, fileId);
            }
          });
    } catch (Exception e) {
      XRayEntityUtils.addException(segment, e);
      log.error(e);
    }
  }

  private void doUpdateGeneration(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    long mills = System.currentTimeMillis();
    JsonObject filters = message.body().getJsonObject("disableThumbnailFilters");
    boolean doDisableThumbnail = message.body().getBoolean("disableThumbnail");
    try {
      if (Utils.isJsonObjectNotNullOrEmpty(filters)) {
        boolean isStoragesProvided = (filters.containsKey(Field.STORAGES.getName())
            && Utils.isJsonObjectNotNullOrEmpty(filters.getJsonObject(Field.STORAGES.getName())));

        if (filters.containsKey(Field.USERS.getName())
            && Utils.isJsonObjectNotNullOrEmpty(filters.getJsonObject(Field.USERS.getName()))) {
          JsonObject users = filters.getJsonObject(Field.USERS.getName());
          String emailOrId = users.getString(Field.ID.getName());
          Item user = null;
          if (Utils.isEmail(emailOrId)) {
            Iterator<Item> emailIterator = Users.getUserByEmail(emailOrId);
            if (emailIterator.hasNext()) {
              user = emailIterator.next();
            }
          } else {
            user = Users.getUserById(emailOrId);
          }

          if (user != null) {
            String userId = user.getString(Field.SK.getName());
            if (isStoragesProvided) {
              JsonObject storages = filters.getJsonObject(Field.STORAGES.getName());
              if (storages.containsKey(Field.EXTERNAL_ID.getName())
                  && Utils.isStringNotNullOrEmpty(
                      storages.getString(Field.EXTERNAL_ID.getName()))) {
                // update for the user having any connection for this externalId
                Item externalAccount = ExternalAccounts.getExternalAccount(
                    userId, storages.getString(Field.EXTERNAL_ID.getName()));
                if (externalAccount != null) {
                  if (!externalAccount.hasAttribute("disableThumbnail")
                      || doDisableThumbnail != externalAccount.getBoolean("disableThumbnail")) {
                    ThumbnailsDao.setThumbnailGeneration(externalAccount, doDisableThumbnail);
                  }
                }
              } else { // if only storage type is provided, then update for all the user
                // connections for this storage
                Iterator<Item> externalAccounts =
                    ExternalAccounts.getAllExternalAccountsByUserAndStorageType(
                        userId,
                        StorageType.getStorageType(storages.getString(Field.TYPE.getName())));
                while (externalAccounts.hasNext()) {
                  Item externalAccount = externalAccounts.next();
                  if (!externalAccount.hasAttribute("disableThumbnail")
                      || doDisableThumbnail != externalAccount.getBoolean("disableThumbnail")) {
                    ThumbnailsDao.setThumbnailGeneration(externalAccount, doDisableThumbnail);
                  }
                }
              }
            } else { // if only users are provided - Update for the whole user
              String opt = user.getJSON(Field.OPTIONS.getName());
              JsonObject options = opt != null
                  ? new JsonObject(opt)
                  : config.getProperties().getDefaultUserOptionsAsJson();
              if (!options.containsKey("disableThumbnail")
                  || doDisableThumbnail != options.getBoolean("disableThumbnail")) {
                options.put("disableThumbnail", doDisableThumbnail);
                Users.setUserOptions(userId, options.encode());
              }
              // updating for all user active sessions to keep the disableThumbnail value updated
              new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                  .runWithoutSegment(() -> {
                    List<Item> sessionsToUpdate = new ArrayList<>();
                    Iterator<Item> sessions = Sessions.getAllUserSessions(userId);
                    while (sessions.hasNext()) {
                      Item session = sessions.next();
                      String sessionOpt = session.getJSON(Field.OPTIONS.getName());
                      JsonObject sessionOptions = sessionOpt != null
                          ? new JsonObject(sessionOpt)
                          : config.getProperties().getDefaultUserOptionsAsJson();
                      if (!sessionOptions.containsKey("disableThumbnail")
                          || doDisableThumbnail != sessionOptions.getBoolean("disableThumbnail")) {
                        sessionOptions.put("disableThumbnail", doDisableThumbnail);
                        session.withJSON(Field.OPTIONS.getName(), sessionOptions.encode());
                      } else {
                        continue;
                      }
                      sessionsToUpdate.add(session);
                    }
                    Dataplane.batchWriteListItems(
                        sessionsToUpdate, Sessions.tableName, DataEntityType.SESSION);
                  });
            }
          }
        } else { // if only storages are provided
          if (isStoragesProvided) {
            JsonObject storages = filters.getJsonObject(Field.STORAGES.getName());

            new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
                .runWithoutSegment(() -> {
                  List<Item> accountsToUpdate = new ArrayList<>();
                  ThumbnailsDao.updateThumbnailGenerationCache(
                      null, storages.getString(Field.TYPE.getName()), doDisableThumbnail);
                  // if only storage type is provided, then update for all the connections for this
                  // storage
                  Iterator<Item> externalAccounts =
                      ExternalAccounts.getAllExternalAccountsForStorageType(
                          StorageType.getStorageType(storages.getString(Field.TYPE.getName())));
                  while (externalAccounts.hasNext()) {
                    Item externalAccount = externalAccounts.next();
                    if (!externalAccount.hasAttribute("disableThumbnail")
                        || doDisableThumbnail != externalAccount.getBoolean("disableThumbnail")) {
                      externalAccount.withBoolean("disableThumbnail", doDisableThumbnail);
                    } else {
                      continue;
                    }
                    accountsToUpdate.add(externalAccount);
                  }
                  Dataplane.batchWriteListItems(
                      accountsToUpdate,
                      ExternalAccounts.tableName,
                      DataEntityType.EXTERNAL_ACCOUNTS);
                });
          }
        }
        sendOK(segment, message, mills);
      } else {
        sendError(segment, message, 400, "FL8");
      }
    } catch (Exception e) {
      log.error(e);
    }
  }

  private void requestDownload(
      Entity segment,
      Message<JsonObject> message,
      String msg,
      String id,
      String shortST,
      String fileId) {
    SendMessageRequest sendMessageRequest =
        new SendMessageRequest().withQueueUrl(queue).withMessageBody(msg);

    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .runWithoutSegment(() -> {
          sqs.sendMessage(sendMessageRequest);

          ListObjectsV2Request request = new ListObjectsV2Request()
              .withBucketName(bucket)
              .withPrefix("download/" + id.substring(0, id.lastIndexOf(partsSeparator)));
          for (S3ObjectSummary summary : s3Regional.listObjectsV2(request).getObjectSummaries()) {
            if (summary.getKey().startsWith(shortST + partsSeparator + fileId + partsSeparator)
                && !summary.getKey().startsWith(id)) {
              s3Regional.deleteObject(bucket, "download/" + summary.getKey());
            }
          }
        });
  }

  // Thumbnails
  private void checkAndRequestThumbnails(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    JsonObject body = message.body();
    String storageType = body.getString(Field.STORAGE_TYPE.getName());
    JsonArray ids = body.getJsonArray(Field.IDS.getName());
    JsonObject preferences = body.getJsonObject(Field.PREFERENCES.getName());
    boolean force =
        body.containsKey(Field.FORCE.getName()) && body.getBoolean(Field.FORCE.getName());
    int colour = preferences != null
            && preferences.containsKey("preferences_display")
            && preferences
                .getJsonObject("preferences_display")
                .containsKey("graphicswinmodelbackgrndcolor")
            && preferences
                .getJsonObject("preferences_display")
                .getString("graphicswinmodelbackgrndcolor")
                .equalsIgnoreCase("white")
        ? 1
        : 0;

    XRayEntityUtils.putMetadata(segment, XrayField.IDS, ids.toString());
    XRayEntityUtils.putMetadata(segment, XrayField.STORAGE_TYPE, storageType);
    XRayEntityUtils.putAnnotation(segment, XrayField.STORAGE_TYPE, storageType);
    XRayEntityUtils.putMetadata(segment, XrayField.FORCE, force);
    XRayEntityUtils.putMetadata(segment, XrayField.PREFERENCES, preferences);

    List<JsonObject> thumbnailsToGenerate = Arrays.stream(ids.stream().toArray())
        .filter(
            item -> checkIfThumbnailRequestRequired((JsonObject) item, force, storageType, false))
        .map(item -> (JsonObject) item)
        .collect(Collectors.toList());

    XRayEntityUtils.putMetadata(
        segment, XrayField.IDS_TO_GENERATE, new JsonArray(thumbnailsToGenerate).toString());

    int chunkSize = getThumbnailChunkSize(StorageType.getStorageType(storageType));
    // removing unwanted attributes from body (just excluding 2 for now)
    body.remove("accessToken");
    body.remove(Field.IDS.getName());
    log.info(logPrefix + " " + thumbnailsToGenerate.size()
        + " thumbnails to generate for storage - " + storageType);
    if (!thumbnailsToGenerate.isEmpty()) {
      // split by priority
      Map<Boolean, List<JsonObject>> thumbnailsByPriority = thumbnailsToGenerate.stream()
          .collect(
              Collectors.partitioningBy(item -> item.getBoolean(Field.PRIORITY.getName(), false)));
      // generate priority ones manually
      if (Utils.isListNotNullOrEmpty(thumbnailsByPriority.get(true))) {
        log.info(logPrefix + " Priority thumbnails size: "
            + thumbnailsByPriority.get(true).size());
        thumbnailsByPriority
            .get(true)
            .forEach(
                item -> checkSingleThumbnail(segment, message, body, item, storageType, colour));
      }
      if (Utils.isListNotNullOrEmpty(thumbnailsByPriority.get(false))) {
        log.info(logPrefix + " Low priority thumbnails size: "
            + thumbnailsByPriority.get(false).size());
        List<List<JsonObject>> thumbnails =
            Utils.splitList(thumbnailsByPriority.get(false), chunkSize);
        thumbnails.forEach(
            item -> handleThumbnailChunk(segment, message, item, body, storageType, colour));
      }
    }

    XRayManager.endSegment(segment);
  }

  private void requestRecentThumbnails(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    JsonObject thumbnailInfo = message.body().getJsonObject("thumbnailInfo");
    JsonObject preferences = message.body().getJsonObject(Field.PREFERENCES.getName());
    int colour = preferences != null
            && preferences.containsKey("preferences_display")
            && preferences
                .getJsonObject("preferences_display")
                .containsKey("graphicswinmodelbackgrndcolor")
            && preferences
                .getJsonObject("preferences_display")
                .getString("graphicswinmodelbackgrndcolor")
                .equalsIgnoreCase("white")
        ? 1
        : 0;

    checkSingleThumbnail(
        segment,
        message,
        message.body(),
        thumbnailInfo,
        thumbnailInfo.getString(Field.STORAGE_TYPE.getName()),
        colour);

    XRayManager.endSegment(segment);
  }

  private void handleThumbnailChunk(
      Entity segment,
      Message<JsonObject> message,
      List<JsonObject> chunk,
      JsonObject body,
      String storageType,
      int colour) {
    String groupName = storageType;
    if (Utils.isListNotNullOrEmpty(chunk) && chunk.get(0).containsKey("serverUrl")) {
      groupName = chunk.get(0).getString("serverUrl");
    }

    String chunkId = storageType + "_" + UUID.randomUUID();

    body.put("colour", colour);
    if (ThumbnailsDao.areNoChunksProcessing(groupName, storageType)) {
      createThumbnailChunkRequestForSQS(segment, message, chunkId, body, chunk, groupName);
    } else {
      log.info(logPrefix + " There are processing chunks for: " + groupName
          + " going to save chunk on the server with chunkId: " + chunkId);
      ThumbnailsDao.saveThumbnailChunkOnServer(groupName, chunkId, body, chunk, true);
    }
  }

  private void createThumbnailChunkRequestForSQS(
      Entity parentSegment,
      Message<JsonObject> message,
      String chunkId,
      JsonObject body,
      List<JsonObject> chunk,
      String groupName) {
    SQSRequests.saveThumbnailChunkRequest(chunkId, body, chunk, groupName);
    SendMessageRequest sendMessageRequest = new SendMessageRequest().withQueueUrl(chunkQueue);
    groupName = URLEncoder.encode(groupName, StandardCharsets.UTF_8);
    String sqsMessage = SQSRequests.thumbnailChunkPrefix + chunkId + "#" + groupName;
    log.info(logPrefix + " Going to send chunk request with message: " + sqsMessage);
    sendMessageRequest.withMessageBody(sqsMessage);
    String finalGroupName = groupName;

    new ExecutorServiceAsyncRunner(executorService, operationGroup, parentSegment, message)
        .withName("sendThumbnailChunkToSQS")
        .runWithoutSegment(() ->
            sendThumbnailChunkToSQS(chunkId, finalGroupName, body, chunk, sendMessageRequest));
  }

  private void sendThumbnailChunkToSQS(
      String chunkId,
      String groupName,
      JsonObject body,
      List<JsonObject> chunk,
      SendMessageRequest sendMessageRequest) {
    try {
      sqs.sendMessage(sendMessageRequest);
      log.info(logPrefix + " New thumbnail chunk message sent with id : " + chunkId);
    } catch (Exception ex) {
      log.error(
          logPrefix + " Error on sending SQS chunk message for thumbnails: " + ex.getMessage());
      // if exception, add chunk again on server and delete the SQS request
      ThumbnailsDao.saveThumbnailChunkOnServer(groupName, chunkId, body, chunk, true);
      SQSRequests.deleteThumbnailChunkRequest(chunkId, groupName);
    }
  }

  /**
   * Checks if Thumbnail request exists and thumbnail data should be saved
   *
   * @param parentSegment - Parent Entity. Should be closed in parent method
   * @param body          - Incoming message body
   * @param thumbnailInfo - thumbnail info
   * @param storageType   - has to be String because of WebDAV
   * @param colour        - color of background
   */
  private Future<String> checkSingleThumbnail(
      Entity parentSegment,
      Message<JsonObject> message,
      JsonObject body,
      JsonObject thumbnailInfo,
      String storageType,
      int colour) {
    Entity segment =
        XRayManager.createStandaloneSegment(operationGroup, parentSegment, "checkSingleThumbnail");

    Promise<String> promise = Promise.promise();
    try {
      XRayEntityUtils.putMetadata(
          parentSegment, XrayField.THUMBNAIL_INFO, thumbnailInfo.toString());

      String fileId = thumbnailInfo.getString(Field.FILE_ID.getName());
      String path = thumbnailInfo.getString(Field.PATH.getName());
      String driveId = thumbnailInfo.getString(Field.DRIVE_ID.getName());
      String versionId = thumbnailInfo.getString(Field.VERSION_ID.getName());
      String fullId = getFullFileId(fileId, storageType, versionId, false);
      String requestId = JobPrefixes.THUMBNAIL.prefix + fullId;
      String ext = thumbnailInfo.getString(Field.EXT.getName());

      // check if the thumbnail request status in DB
      Item dbJob = SQSRequests.findRequest(requestId);
      if (dbJob != null
          && dbJob.hasAttribute(Field.STATUS.getName())
          && (dbJob.getString(Field.STATUS.getName()).equals(ThumbnailStatus.UNAVAILABLE.name()))) {
        SQSRequests.updateThumbnailRequest(requestId, ThumbnailStatus.LOADING, null);
      } else {
        log.info(logPrefix + " for file " + fullId + " has not been requested yet");
        SQSRequests.saveThumbnailRequest(requestId, ThumbnailStatus.LOADING);
      }
      log.info(logPrefix + " Going to request single thumbnail for file " + fullId);

      String sqsMessage =
          "%s;https://s3.amazonaws.com/%s/dwg/%s%s;https://s3.amazonaws.com/%s/%s/%s.%s;250;250;"
              + "0;%d";

      StorageType storageType1 = StorageType.getStorageType(storageType);
      if (storageType1.equals(StorageType.ONEDRIVE)
          || storageType1.equals(StorageType.ONEDRIVEBUSINESS)
          || storageType1.equals(StorageType.SHAREPOINT)) {
        body.put(Field.DRIVE_ID.getName(), driveId);
      }
      saveVersionToS3(
              segment,
              StorageType.getStorageType(storageType),
              body.put(
                      Field.FILE_ID.getName(), Utils.isStringNotNullOrEmpty(fileId) ? fileId : path)
                  .put(Field.VER_ID.getName(), versionId)
                  .put(Field.LOCALE.getName(), body.getString(Field.LOCALE.getName())),
              requestId,
              ext,
              fullId)
          .onComplete(saveResult -> {
            if (saveResult.succeeded()) {
              promise.complete();
              String msg = String.format(
                  sqsMessage,
                  requestId,
                  bucket,
                  fullId,
                  ext,
                  bucket,
                  thumbnailFolder,
                  fullId,
                  thumbnailExtension,
                  colour);
              // save to send proper notification
              ThumbnailSubscription.save(fileId, body.getString(Field.USER_ID.getName()));
              sendThumbnailRequestToSQS(
                  segment,
                  message,
                  msg,
                  fullId + "." + thumbnailExtension,
                  fileId,
                  StorageType.getStorageType(storageType),
                  requestId);

              XRayManager.endSegment(segment);
            } else {
              promise.fail(thumbnailInfo.encode());
              XRayEntityUtils.addException(segment, "failedCheckSingleThumbnail");
              XRayManager.endSegment(segment);
            }
          });
    } catch (Exception e) {
      log.error(logPrefix + " Error in checking single thumbnail - " + thumbnailInfo.encode()
          + " | " + e);

      XRayEntityUtils.addException(segment, e);
      XRayManager.endSegment(segment);

      promise.fail(e.getCause());
    }
    return promise.future();
  }

  private void sendThumbnailRequestToSQS(
      Entity segment,
      Message<JsonObject> message,
      String msg,
      String thumbnailName,
      String fileId,
      StorageType storageType,
      String requestId) {
    SendMessageRequest sendMessageRequest =
        new SendMessageRequest().withQueueUrl(queue).withMessageBody(msg);

    // remove outdated thumbnails
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("sendThumbnailRequestToSQS")
        .run((Segment blockingSegment) -> {
          XRayEntityUtils.putMetadata(blockingSegment, XrayField.QUEUE_URL, queue);
          XRayEntityUtils.putMetadata(blockingSegment, XrayField.MESSAGE, msg);

          // it's blocking, so let's not make timeout here.
          // https://graebert.atlassian.net/browse/XENON-41355
          try {
            sqs.sendMessage(sendMessageRequest);
          } catch (Exception ex) {
            log.error(
                logPrefix + " Error on sending SQS message for thumbnails: " + ex.getMessage());
            if (Utils.isStringNotNullOrEmpty(requestId)) {
              SQSRequests.updateThumbnailRequest(
                  requestId, ThumbnailStatus.UNAVAILABLE, ex.getMessage());
            }
          }
        });

    if (storageType != null) {
      // update thumbnails info in recent files
      eb_send(
          segment,
          RecentFilesVerticle.address + ".updateRecentFile",
          new JsonObject()
              .put(Field.FILE_ID.getName(), Utils.parseItemId(fileId).getString(Field.ID.getName()))
              .put(Field.THUMBNAIL_NAME.getName(), thumbnailName)
              .put(Field.STORAGE_TYPE.getName(), storageType.name()));
    }
  }

  // Drawing compare
  private void compareDrawings(Message<JsonObject> message, String requestId) {
    Entity segment = XRayManager.createSegment(operationGroup, message);

    long mills = System.currentTimeMillis();
    JsonObject data = message.body();
    if (!data.containsKey(Field.FILES.getName())) {
      sendError(segment, message, 400, "CD1");
      return;
    }
    JsonArray filesToCompare = data.getJsonArray(Field.FILES.getName());
    if (filesToCompare.size() != 2) {
      sendError(segment, message, 400, "CD2");
      return;
    }
    String s3ComparisonId = getTargetS3IdForComparison(message);
    if (s3ComparisonId != null
        && (s3ComparisonId.contains(Field.LATEST.getName())
            || !s3Regional.doesObjectExistInBucket(bucket, "compare/" + s3ComparisonId))) {
      JsonObject firstFile = filesToCompare.getJsonObject(0);
      JsonObject secondFile = filesToCompare.getJsonObject(1);
      JsonObject parsedFirstFileId = Utils.parseObjectId(firstFile.getString(Field.ID.getName()));
      JsonObject parsedSecondFileId = Utils.parseObjectId(secondFile.getString(Field.ID.getName()));

      String firstFileRequestId = firstFile.getString(Field.ID.getName())
          + partsSeparator
          + firstFile.getString(Field.VERSION_ID.getName());
      SQSRequests.saveRequest(
          firstFileRequestId,
          data.getString(Field.USER_ID.getName()),
          parsedFirstFileId.getString(Field.EXTERNAL_ID.getName()));
      String secondFileRequestId = secondFile.getString(Field.ID.getName())
          + partsSeparator
          + secondFile.getString(Field.VERSION_ID.getName());
      SQSRequests.saveRequest(
          secondFileRequestId,
          data.getString(Field.USER_ID.getName()),
          parsedSecondFileId.getString(Field.EXTERNAL_ID.getName()));

      SQSRequests.saveCompareJobRequest(
          requestId,
          data.getString(Field.USER_ID.getName()),
          firstFile.getString(Field.ID.getName()),
          secondFile.getString(Field.ID.getName()));

      List<Future<String>> futures = new ArrayList<>();
      futures.add(saveVersionToS3(
          segment,
          StorageType.getStorageType(parsedFirstFileId.getString(Field.STORAGE_TYPE.getName())),
          data.put(Field.FILE_ID.getName(), parsedFirstFileId.getString(Field.OBJECT_ID.getName()))
              .put(Field.VER_ID.getName(), firstFile.getString(Field.VERSION_ID.getName()))
              .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName())),
          firstFileRequestId,
          firstFile.getString(Field.EXT.getName()),
          firstFileRequestId));
      futures.add(saveVersionToS3(
          segment,
          StorageType.getStorageType(parsedSecondFileId.getString(Field.STORAGE_TYPE.getName())),
          data.put(Field.FILE_ID.getName(), parsedSecondFileId.getString(Field.OBJECT_ID.getName()))
              .put(Field.VER_ID.getName(), secondFile.getString(Field.VERSION_ID.getName()))
              .put(Field.LOCALE.getName(), message.body().getString(Field.LOCALE.getName())),
          secondFileRequestId,
          secondFile.getString(Field.EXT.getName()),
          secondFileRequestId));

      TypedCompositeFuture.join(futures).onComplete(ar -> {
        // Ignore param as we don't send any message from those future's
        if (ar.succeeded()) {
          // send message to sqs
          String msg = String.format(
              compareSQSMessage,
              requestId, // job id
              bucket,
              firstFileRequestId,
              firstFile.getString(Field.EXT.getName()), // first file
              bucket,
              secondFileRequestId,
              secondFile.getString(Field.EXT.getName()), // second file
              bucket,
              s3ComparisonId,
              0);
          SendMessageRequest sendMessageRequest =
              new SendMessageRequest().withQueueUrl(queue).withMessageBody(msg);
          new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
              .withName("sendSQSForCompareDrawings")
              .runWithoutSegment(() -> sqs.sendMessage(sendMessageRequest));
        }
        sendOK(segment, message, mills);
      });
    } else {
      sendOK(segment, message);
    }
    XRayManager.endSegment(segment);
  }

  /**
   * Checks that file data is already saved to s3. If not, gets data and saves it
   *
   * @param segment     Parent Entity. Should be closed with parent method
   * @param storageType storageType of object
   * @param data        Json data for .getVersion request
   * @param requestId   thumbnail requestId
   * @param extension   object extension
   * @param fileId      object id
   * @return future of this task
   */
  private Future<String> saveVersionToS3(
      Entity segment,
      StorageType storageType,
      JsonObject data,
      String requestId,
      String extension,
      String fileId) {
    Promise<String> handler = Promise.promise();
    if (s3Regional.doesObjectExistInBucket(bucket, "dwg/" + fileId + extension)) {
      log.info(logPrefix + " DWG already existed in S3 for fileId " + fileId);
      handler.complete("EXISTS");
    } else {
      String versionId = data.getString(Field.VERSION_ID.getName(), emptyString);

      // support requests from version download by token endpoints
      if (versionId.equals(VersionType.LATEST.toString())) {
        data.remove(Field.VERSION_ID.getName());
        data.remove(Field.VER_ID.getName());
      } else if (versionId.equals(VersionType.LAST_PRINTED.toString())) {
        Item cloudVersion = CloudFieldFileVersion.getCloudFieldFileVersion(
            data.getString(Field.FILE_ID.getName()), storageType.toString());

        if (Objects.isNull(cloudVersion)) {
          data.remove(Field.VERSION_ID.getName());
          data.remove(Field.VER_ID.getName());
        } else {
          data.put(
              Field.VERSION_ID.getName(),
              cloudVersion.getString(Field.PRINTED_VERSION_ID.getName()));
          data.put(
              Field.VER_ID.getName(), cloudVersion.getString(Field.PRINTED_VERSION_ID.getName()));
        }
      }

      eb_request(segment, StorageType.getAddress(storageType) + ".getVersion", data, event -> {
        Entity subSegment = XRayManager.createStandaloneSegment(
            operationGroup, segment, "saveVersionToS3.getVersionResult");
        if (event.failed()) {
          SQSRequests.removeRequest(requestId);
          log.error(logPrefix, event.cause());
          handler.fail(event.cause());
          return;
        }

        ParsedMessage parsedMessage = MessageUtils.parse(event.result());

        if (Field.ERROR
            .getName()
            .equals(parsedMessage.getJsonObject().getString(Field.STATUS.getName()))) {
          SQSRequests.removeRequest(requestId);
          log.error(
              logPrefix + " : " + parsedMessage.getJsonObject().getValue(Field.MESSAGE.getName()));
          handler.fail(
              parsedMessage.getJsonObject().getValue(Field.MESSAGE.getName()).toString());
          return;
        }

        if (parsedMessage.hasInputStreamContent()) {
          parsedMessage.getJsonObject().put("setFileDownloadedAt", false);
        }

        try (InputStream stream = parsedMessage.getContentAsInputStream()) {
          log.info(logPrefix + " : saving img data to s3 for " + fileId + " storage is "
              + storageType.name());

          s3Regional.putPublic(bucket, "dwg/" + fileId + extension, IOUtils.toByteArray(stream));
          handler.complete("SUCCESS");
          XRayManager.endSegment(subSegment);
        } catch (Exception e) {
          SQSRequests.removeRequest(requestId);
          log.error(logPrefix, e);
          handler.fail(e);
        }
      });
    }
    return handler.future();
  }

  public enum ThumbnailStatus {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
    COULDNOTGENERATE
  }

  public static String getThumbnailStatus(
      String fileId,
      String storageType,
      String versionId,
      boolean force,
      boolean canCreateThumbnails) {
    String fullId = ThumbnailsManager.getFullFileId(fileId, storageType, versionId, false);
    Item request = SQSRequests.findRequest(JobPrefixes.THUMBNAIL.prefix + fullId);
    boolean availableInS3 = ThumbnailsManager.checkThumbnailInS3(fullId, force, false);
    if (request != null && request.hasAttribute(Field.STATUS.getName())) {
      if (request
          .getString(Field.STATUS.getName())
          .equals(ThumbnailsManager.ThumbnailStatus.AVAILABLE.name())) {
        if (availableInS3) {
          return ThumbnailsManager.ThumbnailStatus.AVAILABLE.name();
        }
      } else {
        return request.getString(Field.STATUS.getName());
      }
    }
    if (availableInS3) {
      return ThumbnailsManager.ThumbnailStatus.AVAILABLE.name();
    }

    if (canCreateThumbnails) {
      return ThumbnailsManager.ThumbnailStatus.LOADING.name();
    } else {
      return ThumbnailsManager.ThumbnailStatus.UNAVAILABLE.name();
    }
  }

  public static String[] parseThumbnailName(String thumbnailName) throws Exception {
    int first = thumbnailName.indexOf(partsSeparator);
    int last = thumbnailName.lastIndexOf(partsSeparator);

    if (first < 0) {
      log.error("Thumbnail name is malformed. ThumbnailName: " + thumbnailName);
      throw new Exception("parseThumbnailNameException: Thumbnail name is malformed");
    }

    if (first == last) {
      return thumbnailName.split(partsSeparator);
    }

    return new String[] {
      thumbnailName.substring(0, first),
      thumbnailName.substring(first + 1, last),
      thumbnailName.substring(last + 1)
    };
  }

  private int getThumbnailChunkSize(StorageType storageType) {
    switch (storageType) {
      case SAMPLES: {
        return config.getProperties().getSamplesThumbnailChunkSize();
      }
      case BOX: {
        return config.getProperties().getBoxThumbnailChunkSize();
      }
      case DROPBOX: {
        return config.getProperties().getDropboxThumbnailChunkSize();
      }
      case GDRIVE: {
        return config.getProperties().getGdriveThumbnailChunkSize();
      }
      case ONEDRIVE: {
        return config.getProperties().getOnedriveThumbnailChunkSize();
      }
      case ONEDRIVEBUSINESS: {
        return config.getProperties().getOnedriveBusinessThumbnailChunkSize();
      }
      case SHAREPOINT: {
        return config.getProperties().getSharepointThumbnailChunkSize();
      }
      case HANCOM: {
        return config.getProperties().getHancomThumbnailChunkSize();
      }
      case HANCOMSTG: {
        return config.getProperties().getHancomstgThumbnailChunkSize();
      }
      case ONSHAPE: {
        return config.getProperties().getOnshapeThumbnailChunkSize();
      }
      case ONSHAPEDEV: {
        return config.getProperties().getOnshapedevThumbnailChunkSize();
      }
      case ONSHAPESTAGING: {
        return config.getProperties().getOnshapestagingThumbnailChunkSize();
      }
      case NEXTCLOUD: {
        return config.getProperties().getNextcloudThumbnailChunkSize();
      }
      case WEBDAV: {
        return config.getProperties().getWebdavThumbnailChunkSize();
      }
      case TRIMBLE: {
        return config.getProperties().getTrimbleThumbnailChunkSize();
      }
      default: {
        return 10;
      }
    }
  }
}
