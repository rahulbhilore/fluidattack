package com.graebert.storage.stats;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.xray.entities.Entity;
import com.amazonaws.xray.entities.Segment;
import com.graebert.storage.Entities.LongJob;
import com.graebert.storage.Entities.PublicLink;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.gridfs.S3Regional;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.stats.kinesis.AWSKinesisClient;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.storage.SubscriptionsDao;
import com.graebert.storage.storage.TempData;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.TypedCompositeFuture;
import com.graebert.storage.util.Utils;
import com.graebert.storage.util.async.ExecutorServiceAsyncRunner;
import com.graebert.storage.vertx.DynamoBusModBase;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class StatsVerticle extends DynamoBusModBase {
  private static final OperationGroup operationGroup = OperationGroup.STATS;
  public static final String address = "stats";
  private static final String jobName = "Stats_fetchLinks";
  private S3Regional s3Regional = null;

  @Override
  public void start() throws Exception {
    super.start();

    eb.consumer(address + ".getSubscriptionsLog", this::doGetSubscriptionsLog);
    eb.consumer(address + ".getOldLinks", this::doGetOldLinks);
    eb.consumer(address + ".getCachedLinks", this::doGetCachedLinks);
    eb.consumer(address + ".getJobStatus", this::doGetJobStatus);
    eb.consumer(address + ".flushLogs", this::doFlushLogs);
    eb.consumer(address + ".getMemcached", this::doGetMemcached);
    eb.consumer(address + ".deleteMemcached", this::doDeleteMemcached);
    eb.consumer(address + ".savePerformanceStat", this::doSavePerformanceStat);
    eb.consumer(address + ".getPerformanceStats", this::doGetPerformanceStats);

    String bucket = config.getProperties().getS3Bucket();
    String region = config.getProperties().getS3Region();
    s3Regional = new S3Regional(config, bucket, region);
  }

  private void doGetJobStatus(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    final JsonObject body = message.body();
    final String jobId = body.getString(Field.JOB_ID.getName());
    final String currentStatus = LongJob.getJobStatus(jobId, jobName);

    sendOK(
        segment,
        message,
        new JsonObject()
            .put(
                Field.RESULT.getName(),
                new JsonObject()
                    .put(Field.JOB_ID.getName(), jobId)
                    .put(Field.STATUS.getName(), currentStatus)),
        mills);
  }

  private void doGetCachedLinks(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    final Parameters parameters = new Parameters(message);
    long from = parameters.getFrom();
    long to = parameters.getTo();
    String userId = parameters.getUserId();
    String storageType = parameters.getStorageType();
    final JsonObject body = message.body();
    final String jobId = body.getString(Field.JOB_ID.getName());
    try {
      Iterator<Item> results;
      if (Utils.isStringNotNullOrEmpty(jobId)) {
        results = TempData.getCachedLinks(true, jobId);
      } else {
        results = TempData.getCachedLinks(false, null);
      }

      if (results.hasNext()) {
        Item jobData = results.next();
        String response = "";
        if (jobData.hasAttribute("s3")) {
          response = new String(s3Regional.get(jobData.getString("s3")));
        }
        JsonArray linksArray = new JsonArray(response);
        JsonArray resultArray = new JsonArray();
        linksArray.forEach(link -> {
          // filters come here
          boolean isValid = true;
          JsonObject linkJson = new JsonObject((String) link);
          if (Utils.isStringNotNullOrEmpty(userId)
              && linkJson.containsKey(Field.SK.getName())
              && !linkJson.getString(Field.SK.getName()).contains(userId)) {
            isValid = false;
          }
          // contains for Webdav
          if (isValid
              && Utils.isStringNotNullOrEmpty(storageType)
              && linkJson.containsKey(Field.STORAGE_TYPE.getName())
              && !linkJson.getString(Field.STORAGE_TYPE.getName()).contains(storageType)) {
            isValid = false;
          }
          if (isValid
              && from > 0
              && linkJson.containsKey(Field.CREATION_DATE.getName())
              && linkJson.getLong(Field.CREATION_DATE.getName()) < from) {
            isValid = false;
          }
          if (isValid
              && to > 0
              && linkJson.containsKey(Field.UPDATE_DATE.getName())
              && linkJson.getLong(Field.UPDATE_DATE.getName()) > to) {
            isValid = false;
          }
          if (isValid) {
            resultArray.add(linkJson);
          }
        });
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(Field.RESULT.getName(), new JsonObject().put("links", resultArray)),
            mills);
      } else {
        sendError(
            segment, message, new JsonObject().put(Field.ERROR.getName(), "No data found"), 404);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
      sendError(segment, message, new JsonObject().put(Field.ERROR.getName(), "Exception"), 500);
    }
  }

  private void doGetOldLinks(Message<JsonObject> message) {
    Entity segment = XRayManager.createSegment(operationGroup, message);
    long mills = System.currentTimeMillis();
    final Parameters parameters = new Parameters(message);
    String userId = parameters.getUserId();
    String storageType = parameters.getStorageType();

    LongJob fetchJob = new LongJob(jobName, "STARTED");

    // get all links from db and set received status to job
    JsonArray resultJson = PublicLink.getAllLinks(userId, storageType);
    fetchJob.setStatus("LINKS_RECEIVED");

    JsonArray brokenLinks = new JsonArray();
    JsonArray brokenExpiredLinks = new JsonArray();
    JsonArray aliveLinks = new JsonArray();
    JsonArray otherLinks = new JsonArray();

    JsonObject filesToCheckJson = buildStorageJson();
    JsonObject filesToSure = buildStorageJson();

    // find expired links and sort not expired to filesToCheckJson
    resultJson.forEach(linkObj -> {
      JsonObject link = (JsonObject) linkObj;
      if ((filesToCheckJson.containsKey(link.getString(Field.STORAGE_TYPE.getName()))
              || link.getString(Field.STORAGE_TYPE.getName()).equals(StorageType.SAMPLES.name()))
          && link.containsKey("expiryDate")
          && link.getLong("expiryDate") > 0
          && link.getLong("expiryDate") < GMTHelper.utcCurrentTime()) {
        brokenExpiredLinks.add(link.put(Field.REASON.getName(), "EXPIRED"));
      } else {
        String pk = link.getString(Field.PK.getName());
        String sk = link.getString(Field.SK.getName());
        String type = link.getString(Field.STORAGE_TYPE.getName());

        if (pk.contains("#") && sk.contains("#")) {
          final String externalId = sk.split("#")[0];
          final String linkOwner = sk.split("#")[1];
          final String fileId = pk.split("#")[0];

          // samples links are always alive if not expired
          if (type.equals(StorageType.SAMPLES.name())) {
            aliveLinks.add(link);
          }

          // here we have To Check FileLinks
          else if (filesToCheckJson.containsKey(type)) {

            if (!filesToCheckJson.getJsonObject(type).containsKey(externalId)) {
              filesToCheckJson.getJsonObject(type).put(externalId, new JsonArray());
            }

            filesToCheckJson
                .getJsonObject(type)
                .getJsonArray(externalId)
                .add(new JsonObject()
                    .put(Field.EXTERNAL_ID.getName(), externalId)
                    .put(Field.STORAGE_TYPE.getName(), link.getString(Field.STORAGE_TYPE.getName()))
                    .put(Field.USER_ID.getName(), linkOwner)
                    .put(Field.FILE_ID.getName(), fileId)
                    .put(Field.LINK.getName(), link));
          }

          // no use links
          else {
            otherLinks.add(link.put(Field.REASON.getName(), "NO USE"));
          }
        } else {
          brokenLinks.add(link.put(Field.REASON.getName(), "WRONG DATA"));
        }
      }
    });

    fetchJob.setStatus("CHECKING_CONNECTIONS");

    List<Future<JsonObject>> queue = new ArrayList<>();
    processFirstIteration(segment, queue, brokenLinks, aliveLinks, filesToCheckJson, filesToSure);

    TypedCompositeFuture.all(queue).onComplete(ar -> {
      fetchJob.setStatus("FIRST_ITERATION_FINISHED");
      try {
        // return firstlooked links
        sendOK(
            segment,
            message,
            new JsonObject()
                .put(
                    Field.RESULT.getName(),
                    new JsonObject()
                        .put(Field.JOB_ID.getName(), fetchJob.getJobId())
                        .put(Field.STATUS.getName(), "Done")
                        .put("jobName", jobName)
                        .put(
                            "s3id",
                            userId + (storageType == null ? "" : "_" + storageType) + "_links_")
                        .put(
                            Field.INFO.getName(),
                            new JsonObject()
                                .put("totalLinks", resultJson.size())
                                .put("aliveLinks", aliveLinks.size())
                                .put("brokenExpLinks", brokenExpiredLinks.size())
                                .put("brokenLinks", brokenLinks.size())
                                .put("otherLinks", otherLinks.size()))),
            mills);
        new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
            .run((Segment blockingSegment) -> {
              JsonArray brokenLinksFinal = new JsonArray();
              List<Future<JsonObject>> queue2 = new ArrayList<>();

              processSecondIteration(
                  blockingSegment, queue2, brokenLinksFinal, aliveLinks, filesToSure);

              TypedCompositeFuture.all(queue2).onComplete(ar2 -> {
                JsonObject finalResult = new JsonObject()
                    .put("totalLinksAm", resultJson.size())
                    .put("aliveLinksAm", aliveLinks.size())
                    .put("brokenExpLinksAm", brokenExpiredLinks.size())
                    .put("brokenLinksFinalAm", brokenLinksFinal.size())
                    .put("otherLinksAm", otherLinks.size())
                    .put("aliveLinks", aliveLinks)
                    .put("brokenExpLinks", brokenExpiredLinks)
                    .put("brokenLinksFinal", brokenLinksFinal);

                long timestamp = GMTHelper.utcCurrentTime();
                final String s3Id =
                    userId + (storageType == null ? "" : "_" + storageType) + "_links_" + timestamp;
                try {
                  s3Regional.put(s3Id, finalResult.toString().getBytes());
                  Item linksItem = new Item()
                      .withPrimaryKey(
                          Field.PK.getName(),
                          "expired_links#" + fetchJob.getJobId(),
                          Field.SK.getName(),
                          Long.toString(timestamp))
                      .withString("s3", s3Id);
                  Dataplane.putItem(
                      Dataplane.TableName.TEMP.name,
                      "expired_links",
                      timestamp,
                      linksItem,
                      DataEntityType.STATS);
                  fetchJob.setStatus("DONE");
                } catch (Exception ex) {
                  fetchJob.setStatus("ERROR");
                }
              });
            });
      } catch (Exception ex) {
        fetchJob.setStatus("ERROR");
        XRayManager.endSegment(segment);
      }
    });
  }

  private JsonObject buildStorageJson() {
    JsonObject ret = new JsonObject();

    for (StorageType value : StorageType.values()) {
      ret.put(value.name(), new JsonObject());
    }

    ret.remove(StorageType.INTERNAL.name());
    ret.remove(StorageType.SAMPLES.name());
    ret.remove(StorageType.WEBDAV.name());

    ret.remove(StorageType.ONSHAPE.name());
    ret.remove(StorageType.ONSHAPEDEV.name());
    ret.remove(StorageType.ONSHAPESTAGING.name());

    return ret;
  }

  private void processSecondIteration(
      Entity segment,
      List<Future<JsonObject>> queue,
      JsonArray brokenLinks,
      JsonArray aliveLinks,
      JsonObject filesToSure) {
    filesToSure.forEach(arr -> {
      JsonObject arrObj = new JsonObject(arr.getValue().toString());

      arrObj.forEach(storage -> {
        // here to modify

        JsonArray files = new JsonArray(storage.getValue().toString());
        files.forEach(fileObj -> {
          JsonObject fileInfo = (JsonObject) fileObj;

          Promise<JsonObject> trashStatusHandler = Promise.promise();
          queue.add(trashStatusHandler.future());
          eb_request(
              segment,
              StorageType.getAddress(
                      StorageType.getStorageType(fileInfo.getString(Field.STORAGE_TYPE.getName())))
                  + ".getTrashStatus",
              fileInfo,
              reply -> {
                Entity blockingSegment = XRayManager.createBlockingSegment(operationGroup, segment);
                try {
                  if (reply.succeeded()) {
                    JsonObject response = (JsonObject) reply.result().body();
                    if (response.getBoolean(Field.IS_DELETED.getName()).equals(true)) {
                      brokenLinks.add(fileInfo
                          .getJsonObject(Field.LINK.getName())
                          .put(Field.REASON.getName(), "OBJECT DELETED"));
                    } else {
                      aliveLinks.add(fileInfo.getJsonObject(Field.LINK.getName()));
                    }
                  } else {
                    brokenLinks.add(fileInfo
                        .getJsonObject(Field.LINK.getName())
                        .put(Field.REASON.getName(), "FAILED TO GET OBJECT"));
                  }
                } catch (Exception ex) {
                  log.error("Exception on checking public links: " + ex.getMessage());
                  log.error(ex);
                }
                trashStatusHandler.complete();
                XRayManager.endSegment(blockingSegment);
              });
        });
      });
    });
  }

  private void processFirstIteration(
      Entity segment,
      List<Future<JsonObject>> queue,
      JsonArray brokenLinks,
      JsonArray aliveLinks,
      JsonObject filesObj,
      JsonObject filesToSure) {
    filesObj.forEach(arr -> {
      String type = arr.getKey();
      JsonObject arrObj = new JsonObject(arr.getValue().toString());

      String searchQuery = buildSearchQuery(type);

      arrObj.forEach(storage -> {
        JsonArray files = new JsonArray(storage.getValue().toString());

        if (files.size() > 0) {
          JsonObject fileInfo = files.getJsonObject(0);

          Promise<JsonObject> getAllFilesHandler = Promise.promise();
          queue.add(getAllFilesHandler.future());
          eb_request(
              segment,
              StorageType.getAddress(StorageType.getStorageType(type)) + ".connect",
              fileInfo,
              connectReply -> {
                Entity blockingConnectSegment =
                    XRayManager.createBlockingSegment(operationGroup, segment);
                try {
                  // successfully connected to storage
                  if (connectReply.succeeded()
                      && ((JsonObject) connectReply.result().body())
                          .getString(Field.STATUS.getName())
                          .equals(Field.OK.getName())) {
                    eb_request(
                        segment,
                        StorageType.getAddress(StorageType.getStorageType(type)) + ".getAllFiles",
                        fileInfo
                            .put(Field.QUERY.getName(), searchQuery)
                            .put(Field.IS_ADMIN.getName(), true),
                        reply -> {
                          Entity blockingSegment =
                              XRayManager.createBlockingSegment(operationGroup, segment);
                          try {
                            if (reply.succeeded()) {
                              JsonObject response = (JsonObject) reply.result().body();

                              files.forEach(fileObj -> {
                                JsonObject file = (JsonObject) fileObj;
                                String fileIdToFind = file.getJsonObject(Field.LINK.getName())
                                    .getString(Field.PK.getName())
                                    .split("#")[1];

                                if (!response.toString().contains(fileIdToFind)) {
                                  brokenLinks.add(file.getJsonObject(Field.LINK.getName())
                                      .put(Field.REASON.getName(), "OBJECT DELETED"));

                                  // collecting files for second second shadow iteration
                                  if (filesToSure.containsKey(type)) {
                                    final String linkOwner = file.getJsonObject(
                                            Field.LINK.getName())
                                        .getString(Field.SK.getName())
                                        .substring(file.getJsonObject(Field.LINK.getName())
                                                .getString(Field.SK.getName())
                                                .indexOf("#")
                                            + 1);
                                    final String fileId = file.getJsonObject(Field.LINK.getName())
                                        .getString(Field.PK.getName())
                                        .substring(file.getJsonObject(Field.LINK.getName())
                                                .getString(Field.PK.getName())
                                                .indexOf("#")
                                            + 1);

                                    if (!filesToSure
                                        .getJsonObject(type)
                                        .containsKey(storage.getKey())) {
                                      filesToSure
                                          .getJsonObject(type)
                                          .put(storage.getKey(), new JsonArray());
                                    }

                                    filesToSure
                                        .getJsonObject(type)
                                        .getJsonArray(storage.getKey())
                                        .add(new JsonObject()
                                            .put(Field.EXTERNAL_ID.getName(), storage.getKey())
                                            .put(Field.STORAGE_TYPE.getName(), type)
                                            .put(Field.USER_ID.getName(), linkOwner)
                                            .put(Field.FILE_ID.getName(), fileId)
                                            .put(
                                                Field.LINK.getName(),
                                                file.getJsonObject(Field.LINK.getName())));
                                  }
                                } else {
                                  aliveLinks.add(file.getJsonObject(Field.LINK.getName()));
                                }
                              });
                              getAllFilesHandler.complete();
                            }
                          } catch (Exception ex) {
                            XRayEntityUtils.addException(blockingSegment, ex);

                            log.error("Exception on checking public links: " + ex.getMessage());
                            log.error(ex);
                          }
                          XRayManager.endSegment(blockingSegment);
                        });
                  }
                  // all links are broken cause of bad connection to storage
                  else {
                    files.forEach(fileObj -> {
                      JsonObject file = (JsonObject) fileObj;
                      brokenLinks.add(file.getJsonObject(Field.LINK.getName())
                          .put(Field.REASON.getName(), "Can't connect to storage"));
                    });
                    getAllFilesHandler.complete();
                  }
                } catch (Exception ex) {
                  XRayEntityUtils.addException(blockingConnectSegment, ex);
                  log.error("Exception on connecting to" + type + " storage: " + ex.getMessage());
                  log.error(ex);
                }
                XRayManager.endSegment(blockingConnectSegment);
              });
        }
      });
    });
  }

  private String buildSearchQuery(String type) {
    if (type.equals(StorageType.BOX.name())) {
      return config
          .getProperties()
          .getThumbnailExtensionsAsArray()
          .addAll(config.getProperties().getThumbnailRevitExtensionsAsArray())
          .toString()
          .replace("\",\"", " OR ")
          .substring(2)
          .replace("\"]", "")
          .replace("[\"", "");
    } else if (type.equals(StorageType.WEBDAV.name())) {

      return ".dwg";
    } else {
      return ".";
    }
  }

  private void doGetSubscriptionsLog(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final Parameters parameters = new Parameters(message);
    String userId = parameters.getUserId();

    JsonArray resultJson = new JsonArray();
    // get data for specific user
    if (Utils.isStringNotNullOrEmpty(userId)) {
      SubscriptionsDao.getStatsCommentsForUser(userId).forEachRemaining(subscriptionItem -> {
        String fileId =
            subscriptionItem.getString(Field.PK.getName()).substring("subscriptions#".length());
        resultJson.add(new JsonObject()
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.USER_ID.getName(), userId)
            .put(
                Field.TIMESTAMP.getName(),
                subscriptionItem.hasAttribute("tmstmp") ? subscriptionItem.getLong("tmstmp") : 0)
            .put(
                "scope",
                subscriptionItem.hasAttribute("scope")
                    ? Arrays.toString(subscriptionItem.getList("scope").toArray())
                    : "")
            .put(Field.STATE.getName(), subscriptionItem.getString(Field.STATE.getName())));
      });
    } else {
      // get all stats
      SubscriptionsDao.getStatsComments().forEachRemaining(subscriptionItem -> {
        String fileId =
            subscriptionItem.getString(Field.PK.getName()).substring("subscriptions#".length());
        resultJson.add(new JsonObject()
            .put(Field.FILE_ID.getName(), fileId)
            .put(Field.USER_ID.getName(), subscriptionItem.getString(Field.SK.getName()))
            .put(
                Field.TIMESTAMP.getName(),
                subscriptionItem.hasAttribute("tmstmp") ? subscriptionItem.getLong("tmstmp") : 0)
            .put(
                "scope",
                subscriptionItem.hasAttribute("scope")
                    ? Arrays.toString(subscriptionItem.getList("scope").toArray())
                    : "")
            .put(Field.STATE.getName(), subscriptionItem.getString(Field.STATE.getName())));
      });
    }
    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.RESULT.getName(), new JsonObject().put(Field.RESULT.getName(), resultJson)),
        mills);
  }

  private void doGetPerformanceStats(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    final Parameters parameters = new Parameters(message);
    long from = parameters.getFrom();
    long to = parameters.getTo();
    String userId = parameters.getUserId();

    sendOK(
        segment,
        message,
        new JsonObject()
            .put(
                Field.RESULT.getName(),
                new JsonObject()
                    .put(
                        Field.RESULT.getName(),
                        TempData.getPerformanceStatsJson(userId, from, to))),
        mills);
  }

  private void doFlushLogs(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("flushLogs")
        .runWithoutSegment(AWSKinesisClient::flushLogs);
    sendOK(segment, message, mills);
  }

  private void doDeleteMemcached(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);

    Dataplane.deleteMemcachedData(message.body().getString("memcachedKey"), DataEntityType.STATS);

    sendOK(segment, message, mills);
  }

  private void doGetMemcached(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);

    sendOK(
        segment,
        message,
        new JsonObject()
            .put(Field.DATA.getName(), Dataplane.getMemcachedData(DataEntityType.STATS)),
        mills);
  }

  private void doSavePerformanceStat(Message<JsonObject> message) {
    long mills = System.currentTimeMillis();
    Entity segment = XRayManager.createSegment(operationGroup, message);
    JsonObject jsonObject = message.body();
    JsonObject fluorineMeta = jsonObject.getJsonObject("fluorine");
    JsonObject xenonMeta = jsonObject.getJsonObject("xenon");
    JsonArray testResults = jsonObject.getJsonArray("tests");
    String userId = jsonObject.getString(Field.USER_ID.getName());
    String username = jsonObject.getString(Field.EMAIL.getName());
    final String xenonRevision = xenonMeta.containsKey("revision")
        ? xenonMeta.getString("revision")
        : ("FL_" + fluorineMeta.getString("revision"));
    long timestamp = GMTHelper.utcCurrentTime();
    final String pk = "perf#" + xenonRevision;

    new ExecutorServiceAsyncRunner(executorService, operationGroup, segment, message)
        .withName("savePerformanceStat")
        .runWithoutSegment(() -> {
          testResults.forEach(result -> {
            JsonObject resultObj = (JsonObject) result;
            String fileId = resultObj.getString(Field.FILE_ID.getName());
            String testSet = resultObj.getString("testSet");
            String sk = fileId + "#" + testSet + "#" + timestamp;
            Item testResult = new Item()
                .withPrimaryKey(Field.PK.getName(), pk, Field.SK.getName(), sk)
                .withString(
                    Field.FOLDER_ID.getName(), fluorineMeta.getString(Field.FOLDER_ID.getName()))
                .withString(Field.STORAGE_TYPE.getName(), fluorineMeta.getString("storage"))
                .withString(Field.USER_ID.getName(), userId)
                .withString(Field.USERNAME.getName(), username)
                .withString("fluorine_url", fluorineMeta.getString(Field.URL.getName()))
                .withString("xenon_url", xenonMeta.getString(Field.URL.getName()))
                .withString("testSet", testSet)
                .withString(Field.FILE_ID.getName(), fileId)
                .withLong(Field.CREATED.getName(), timestamp)
                .withString(
                    Field.FILE_NAME_C.getName(), resultObj.getString(Field.FILE_NAME_C.getName()))
                .withNumber("clientTime", resultObj.getLong("clientTime"))
                .withNumber("serverTime", resultObj.getLong("serverTime"));

            TempData.savePerformanceStat(testResult);
          });
        });
    sendOK(segment, message, mills);
  }

  private static class Parameters {
    private long from = -1;
    private long to = -1;
    private final String userId;
    private final String storageType;

    Parameters(Message<JsonObject> message) {
      final JsonObject body = message.body();
      final String fromStr = body.getString("from");
      final String toStr = body.getString("to");
      userId = body.getString(Field.USER_ID.getName());
      storageType = body.getString(Field.STORAGE_TYPE.getName());

      try {
        if (Utils.isStringNotNullOrEmpty(fromStr)) {
          from = Long.parseLong(fromStr);
        }
        if (Utils.isStringNotNullOrEmpty(toStr)) {
          to = Long.parseLong(toStr);
        }
        if (from != -1 && to != -1 && from >= to) {
          // normalize
          from = -1;
          to = -1;
        }
      } catch (Exception ignored) {
        // we basically ignore this exception
      }
    }

    public String getUserId() {
      return userId;
    }

    public String getStorageType() {
      return storageType;
    }

    public long getFrom() {
      return from;
    }

    public long getTo() {
      return to;
    }
  }
}
