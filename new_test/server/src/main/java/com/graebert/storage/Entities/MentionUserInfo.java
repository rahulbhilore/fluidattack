package com.graebert.storage.Entities;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

public class MentionUserInfo {
  public String id;
  public String name;
  public String email;
  public String surname;
  public String username;

  @JsonIgnore
  public int rating = 0;

  @JsonIgnore
  public long updated = 0L;

  ObjectMapper objectMapper =
      new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

  public MentionUserInfo(final Map<String, Object> userMap) {
    this.id = (String) userMap.get(Field.ID.getName());
    this.name = (String) userMap.get(Field.NAME.getName());
    this.surname = (String) userMap.get(Field.SURNAME.getName());
    this.username = (String) userMap.get(Field.USERNAME.getName());
    this.email = (String) userMap.get(Field.EMAIL.getName());
    if (Objects.nonNull(userMap.get("rating"))) {
      this.rating = ((BigDecimal) userMap.get("rating")).intValue();
    }
    if (Objects.nonNull(userMap.get("tmstmp"))) {
      this.updated = ((BigDecimal) userMap.get("tmstmp")).longValue();
    }
  }

  public JsonObject toJson() {
    try {
      return new JsonObject(objectMapper.writeValueAsString(this));
    } catch (JsonProcessingException e) {
      return new JsonObject();
    }
  }

  public Map<String, Object> toMap() {
    Map<String, Object> map = objectMapper.convertValue(this, new TypeReference<>() {});
    map.put("tmstmp", this.updated);
    return map;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null || getClass() != object.getClass()) {
      return false;
    }
    MentionUserInfo userInfo = (MentionUserInfo) object;
    return Objects.equals(id, userInfo.id)
        && Objects.equals(name, userInfo.name)
        && Objects.equals(email, userInfo.email)
        && Objects.equals(surname, userInfo.surname)
        && Objects.equals(username, userInfo.username);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, email, surname, username);
  }
}
