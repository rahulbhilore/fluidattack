package com.graebert.storage.Entities;

import io.vertx.core.json.JsonArray;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Collaborators {
  public List<CollaboratorInfo> editor;
  public List<CollaboratorInfo> viewer;

  public Collaborators(List<CollaboratorInfo> collaboratorInfoList) {
    editor = new ArrayList<>();
    viewer = new ArrayList<>();
    if (collaboratorInfoList.isEmpty()) {
      return;
    }
    collaboratorInfoList.forEach(obj -> {
      if (obj.role.equals(Role.VIEWER)) {
        viewer.add(obj);
      } else {
        editor.add(obj);
      }
    });
  }

  public static JsonArray toJsonArray(List<CollaboratorInfo> collabList) {
    return new JsonArray(
        collabList.stream().map(CollaboratorInfo::toJson).collect(Collectors.toList()));
  }
}
