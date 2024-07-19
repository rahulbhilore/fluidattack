package com.graebert.storage.Entities.Versions;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;

public class VersionModifier {
  private String id = null;
  private String email = null;
  private String photo = null;
  private boolean isCurrentUser = false;
  private String name = null;

  public VersionModifier() {}

  public void setId(String id) {
    this.id = id;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public void setPhoto(String photo) {
    this.photo = photo;
  }

  public void setCurrentUser(boolean currentUser) {
    isCurrentUser = currentUser;
  }

  public void setName(String name) {
    this.name = name;
  }

  public JsonObject toJson() {
    JsonObject response = new JsonObject()
        .put(Field.ID.getName(), id)
        .put(Field.EMAIL.getName(), email)
        .put("photo", photo)
        .put("isCurrentUser", isCurrentUser)
        .put(Field.NAME.getName(), name);
    if (!Utils.isStringNotNullOrEmpty(name)) {
      response.put(Field.NAME.getName(), email);
    }
    if (!Utils.isStringNotNullOrEmpty(id)) {
      response.put(Field.ID.getName(), email);
    }
    return response;
  }
}
