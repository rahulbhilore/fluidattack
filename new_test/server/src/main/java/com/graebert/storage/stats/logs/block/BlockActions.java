package com.graebert.storage.stats.logs.block;

/**
 * Actions existing for blocks describing user interactions for stats tracking through Kinesis +
 * Quicksight.
 */
public enum BlockActions {
  SHARE_BLOCK,
  SHARE_LIBRARY,
  UNSHARE_BLOCK,
  UNSHARE_LIBRARY,
  UPLOAD_BLOCK,
  CREATE_LIBRARY,
  DELETE_BLOCK,
  DELETE_LIBRARY
}
