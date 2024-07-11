package com.graebert.storage.integration;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;

public interface Storage {
  void doAddAuthCode(Message<JsonObject> message);

  void doGetFolderContent(Message<JsonObject> message, boolean isTrash);

  void connect(Message<JsonObject> message, boolean replyOnOk);

  void doCreateFolder(Message<JsonObject> message);

  void doMoveFolder(Message<JsonObject> message);

  void doMoveFile(Message<JsonObject> message);

  void doRenameFolder(Message<JsonObject> message);

  void doRenameFile(Message<JsonObject> message);

  void doDeleteFolder(Message<JsonObject> message);

  void doDeleteFile(Message<JsonObject> message);

  void doClone(Message<JsonObject> message);

  void doGetFile(Message<JsonObject> message);

  void doUploadFile(Message<Buffer> message);

  void doUploadVersion(Message<Buffer> message);

  void doGetVersions(Message<JsonObject> message);

  void doGetLatestVersionId(Message<JsonObject> message);

  void doGetFileByToken(Message<JsonObject> message);

  void doPromoteVersion(Message<JsonObject> message);

  void doDeleteVersion(Message<JsonObject> message);

  void doShare(Message<JsonObject> message);

  void doDeShare(Message<JsonObject> message);

  void doRestore(Message<JsonObject> message);

  void doGetInfo(Message<JsonObject> message);

  void doGetThumbnail(Message<JsonObject> message);

  void doGetInfoByToken(Message<JsonObject> message);

  void doGetFolderPath(Message<JsonObject> message);

  void doEraseAll(Message<JsonObject> message);

  void doEraseFolder(Message<JsonObject> message);

  void doEraseFile(Message<JsonObject> message);

  void doGetVersionByToken(Message<JsonObject> message);

  void doCheckFileVersion(Message<JsonObject> message);

  void doCreateSharedLink(Message<JsonObject> message);

  void doDeleteSharedLink(Message<JsonObject> message);

  void doRequestFolderZip(Message<JsonObject> message);

  void doRequestMultipleObjectsZip(Message<JsonObject> message);

  void doGlobalSearch(Message<JsonObject> message);

  void doFindXRef(Message<JsonObject> message);

  void doCheckPath(Message<JsonObject> message);

  void doGetTrashStatus(Message<JsonObject> message);

  void doCreateShortcut(Message<JsonObject> message);
}
