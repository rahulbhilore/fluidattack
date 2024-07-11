package com.graebert.storage.util;

import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.integration.SimpleFluorine;
import com.graebert.storage.integration.kudoDrive.SimpleStorage;
import com.graebert.storage.storage.MultipleUploads;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class UploadHelper extends TimerTask {

  private final Timer timer;
  private final String uploadRequestId;
  private final String userId;
  private int retryIfNull;

  public UploadHelper(String uploadRequestId, String userId) {
    timer = new Timer();
    this.retryIfNull = 0;
    this.uploadRequestId = uploadRequestId;
    this.userId = userId;
  }

  public void start() {
    timer.schedule(this, 0, 30 * 1000);
  }

  @Override
  public void run() {
    Long lastUploadedItem =
        MultipleUploads.getLastUploadedTimeForMultipleFilesRequest(uploadRequestId);
    if (Objects.nonNull(lastUploadedItem) && lastUploadedItem != 0) {
      if ((lastUploadedItem + (1000 * 60)) > GMTHelper.utcCurrentTime()) {
        return;
      }
    } else {
      if (retryIfNull <= 5) {
        retryIfNull++;
        return;
      }
    }
    if (Objects.nonNull(MultipleUploads.getUploadRequestById(uploadRequestId, userId))) {
      SimpleFluorine simpleFluorine = new SimpleFluorine();
      long totalUploadedSize = simpleFluorine.calculateAndGetTotalUploadedSize(uploadRequestId);
      if (totalUploadedSize != 0) {
        SimpleStorage.updateUsage(SimpleStorage.makeUserId(userId), totalUploadedSize);
        simpleFluorine.sendSampleUsageWsNotification(null, null, userId, false);
      }
    }
    timer.cancel();
  }
}
