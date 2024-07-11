package com.graebert.storage.logs;

import com.graebert.storage.integration.StorageType;
import org.apache.logging.log4j.Level;

public class ConflictingFileLogger extends BaseLogger {
  @Override
  protected LogGroup getLogGroup() {
    return LogGroup.CONFLICTING_FILE;
  }

  public void log(
      Level level,
      String userId,
      String fileId,
      StorageType storageType,
      String messageType,
      String messageLog) {
    log(
        level,
        messageType,
        String.format(
            "Params: userId: %s, storageType: %s, fileId: %s. %s",
            userId, storageType.toString(), fileId, messageLog));
  }
}
