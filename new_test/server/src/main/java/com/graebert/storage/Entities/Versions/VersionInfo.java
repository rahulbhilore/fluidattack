package com.graebert.storage.Entities.Versions;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;

public class VersionInfo {
  private final String id;
  private final long creationTime;
  private final String name;
  private boolean isCustomName = false;
  private VersionModifier modifier = new VersionModifier();
  private VersionPermissions permissions = new VersionPermissions();
  private long size = 0;
  private String thumbnail = "";
  private String hash = "";

  public VersionInfo(final String id, final long creationTime, final String name) {
    this.id = id;
    this.creationTime = creationTime;
    if (Utils.isStringNotNullOrEmpty(name)) {
      this.name = name;
      this.isCustomName = true;
    } else {
      this.name = id;
    }
  }

  public void setThumbnail(String thumbnail) {
    this.thumbnail = thumbnail;
  }

  public void setModifier(VersionModifier modifier) {
    this.modifier = modifier;
  }

  public void setSize(long size) {
    this.size = size;
  }

  public String getId() {
    return id;
  }

  public void setPermissions(VersionPermissions permissions) {
    this.permissions = permissions;
  }

  public void setHash(String hash) {
    this.hash = hash;
  }

  public JsonObject toJson() {
    return new JsonObject()
        .put(Field.ID.getName(), id)
        .put("creationTime", creationTime)
        .put(Field.NAME.getName(), name)
        .put("isCustomName", isCustomName)
        .put("modifier", modifier.toJson())
        .put(Field.PERMISSIONS.getName(), permissions.toJson())
        .put(Field.SIZE.getName(), size)
        .put(Field.THUMBNAIL.getName(), thumbnail)
        .put(Field.HASH.getName(), hash);
  }
}
