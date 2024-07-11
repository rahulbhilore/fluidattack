package com.graebert.storage.Entities;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.ArrayList;
import java.util.List;

public class LongJob extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private final String status;
  private final String jobId;
  private final String name;

  public LongJob(final String name, final String initialStatus) {
    this.status = initialStatus;
    this.name = name;
    this.jobId = Utils.generateUUID();
    this.saveJob();
  }

  public static void setStatus(final String jobId, final String newStatus) {
    final String pk = "JOB_" + jobId;
    Item job = getItemFromCache(tableName, Field.PK.getName(), pk, DataEntityType.STATS);
    List<String> history = job.getList(Field.STATUS_HISTORY.getName());
    history.add(newStatus);
    job.withList(Field.STATUS_HISTORY.getName(), history);
    job.withString(Field.STATUS.getName(), newStatus);
    job.withLong(Field.TIMESTAMP.getName(), GMTHelper.utcCurrentTime());
    putItem(LongJob.tableName, job, DataEntityType.STATS);
  }

  public static String getJobStatus(final String jobId, final String name) {
    final String pk = "JOB_" + jobId;
    Item job = getItemFromCache(
        tableName, Field.PK.getName(), pk, Field.SK.getName(), name, DataEntityType.STATS);
    return job.getString(Field.STATUS.getName());
  }

  private void saveJob() {
    final String pk = "JOB_" + jobId;
    List<String> history = new ArrayList<>();
    history.add(status);
    Item job = new Item()
        .withPrimaryKey(Field.PK.getName(), pk, Field.SK.getName(), name)
        .withString(Field.NAME.getName(), name)
        .withString(Field.STATUS.getName(), status)
        .withLong(Field.CREATED.getName(), GMTHelper.utcCurrentTime())
        .withLong(Field.TIMESTAMP.getName(), GMTHelper.utcCurrentTime())
        .withList(Field.STATUS_HISTORY.getName(), history);
    putItem(tableName, pk, name, job, DataEntityType.STATS);
  }

  public String getJobId() {
    return jobId;
  }

  public void setStatus(final String newStatus) {
    final String pk = "JOB_" + jobId;
    Item job = getItemFromCache(
        tableName, Field.PK.getName(), pk, Field.SK.getName(), name, DataEntityType.STATS);
    List<String> history = job.getList(Field.STATUS_HISTORY.getName());
    history.add(newStatus);
    job.withList(Field.STATUS_HISTORY.getName(), history);
    job.withString(Field.STATUS.getName(), newStatus);
    job.withLong(Field.TIMESTAMP.getName(), GMTHelper.utcCurrentTime());
    putItem(LongJob.tableName, pk, name, job, DataEntityType.STATS);
  }
}
