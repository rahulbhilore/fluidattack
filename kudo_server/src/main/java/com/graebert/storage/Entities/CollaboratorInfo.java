package com.graebert.storage.Entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;

public class CollaboratorInfo {
  public String _id;
  public String name;
  public String email;
  public String userId;

  @JsonIgnore
  public String storageAccessRole;

  @JsonIgnore
  public Role role;

  public CollaboratorInfo(
      final String _id,
      final String name,
      final String email,
      final String userId,
      final String storageAccessRole,
      final Role role) {
    this._id = _id;
    this.name = name;
    this.email = email;
    this.userId = userId;
    this.storageAccessRole = storageAccessRole;
    this.role = role;
  }

  public JsonObject toJson() {
    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    try {
      return new JsonObject(objectMapper.writeValueAsString(this));
    } catch (JsonProcessingException e) {
      return new JsonObject();
    }
  }
}
