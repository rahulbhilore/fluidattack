package com.graebert.storage.storage.object.share;

import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;

public class ShareInfo implements JsonConvertable {
  private final String id;
  private final String name;
  private final String email;

  public ShareInfo(String id, String name, String email) {
    this.id = id;
    this.name = name;
    this.email = email;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getEmail() {
    return email;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject() {
      {
        put(Field.ENCAPSULATED_ID.getName(), id);
        put(Field.NAME.getName(), name);
        put(Field.EMAIL.getName(), email);
      }
    };
  }
}
