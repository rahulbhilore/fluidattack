package com.graebert.storage.resources.integration;

import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

public interface Resource {
  void doGetFolderContent(Message<JsonObject> message);

  void doCreateObject(Message<JsonObject> message);

  void doUpdateObject(Message<JsonObject> message);

  void doDeleteObject(Message<JsonObject> message);

  void doGetObjectInfo(Message<JsonObject> message);

  void doDownloadObject(Message<JsonObject> message);
}
