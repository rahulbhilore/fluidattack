package com.graebert.storage.storage.common;

import io.vertx.core.json.JsonObject;

/**
 * Interface applies contract to objects that may be converted to json to send through api
 */
public interface JsonConvertable {
  JsonObject toJson();
}
