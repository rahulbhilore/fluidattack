package com.graebert.storage.storage.ids;

import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class KudoFileId implements JsonConvertable {
  private final IdName idName;
  private String id;
  private String externalId;
  private StorageType storageType;
  private String encapsulationMode;

  private KudoFileId() {
    this.idName = IdName.OBJECT;
  }

  private KudoFileId(String id, IdName idName) {
    this.idName = idName;
    this.parseId(id);
  }

  private KudoFileId(JsonObject object, IdName idName) {
    this.idName = idName;
    this.externalId = object.getString(Field.EXTERNAL_ID.getName());
    this.storageType = StorageType.getStorageType(object.getString(Field.STORAGE_TYPE.getName()));
    this.id = object.getString(idName.getName());

    if (this.id.contains("+")) {
      this.parseId(this.id);
    }
  }

  private void parseId(String id) {
    if (Objects.nonNull(id)) {
      String[] arr = id.split("\\+");

      // not encapsulated id
      if (arr.length < 2) {
        this.id = id;
        this.encapsulationMode = "0";
      }

      // webdav id or requests with no externalId specified
      else if (arr.length == 2) {
        this.storageType = StorageType.getByShort(arr[0]);
        this.id = URLDecoder.decode(arr[1], StandardCharsets.UTF_8);
        this.encapsulationMode = "1";
      }

      // normal case
      else {
        this.storageType = StorageType.getByShort(arr[0]);
        this.externalId = arr[1];
        this.id = URLDecoder.decode(arr[2], StandardCharsets.UTF_8);
        this.encapsulationMode = arr.length == 4 ? arr[3] : "2";
      }
    }
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject()
        .put(this.idName.getName(), id)
        .put(Field.EXTERNAL_ID.getName(), this.externalId)
        .put(Field.STORAGE_TYPE.getName(), this.storageType);
  }

  public String getId() {
    return id;
  }

  public String getExternalId() {
    return externalId;
  }

  public StorageType getStorageType() {
    return storageType;
  }

  public IdName getIdName() {
    return idName;
  }

  public String getEncapsulationMode() {
    return encapsulationMode;
  }

  public boolean hasId() {
    return Utils.isStringNotNullOrEmpty(this.id);
  }

  public boolean hasExternalId() {
    return Utils.isStringNotNullOrEmpty(this.externalId);
  }

  public boolean hasStorageType() {
    return Objects.nonNull(this.storageType);
  }

  public static KudoFileId parseFullId(String id, IdName idName) {
    return new KudoFileId(id, idName);
  }

  public static KudoFileId parseFullId(String id) {
    return new KudoFileId(id, IdName.OBJECT);
  }

  public static KudoFileId fromJson(JsonObject jsonObject, IdName idName) {
    return new KudoFileId(jsonObject, idName);
  }

  public static KudoFileId fromJson(JsonObject jsonObject) {
    return new KudoFileId(jsonObject, IdName.OBJECT);
  }
}
