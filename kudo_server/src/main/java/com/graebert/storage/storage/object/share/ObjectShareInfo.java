package com.graebert.storage.storage.object.share;

import com.graebert.storage.storage.common.JsonConvertable;
import com.graebert.storage.util.Field;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ObjectShareInfo implements JsonConvertable {
  private final List<ShareInfo> editors;
  private final List<ShareInfo> viewers;

  public ObjectShareInfo() {
    this.editors = new ArrayList<>();
    this.viewers = new ArrayList<>();
  }

  public ObjectShareInfo addViewer(ShareInfo viewer) {
    this.viewers.add(viewer);
    return this;
  }

  public ObjectShareInfo addEditor(ShareInfo editor) {
    this.editors.add(editor);
    return this;
  }

  @Override
  public JsonObject toJson() {
    return new JsonObject() {
      {
        put(
            Field.VIEWER.getName(),
            viewers.stream().map(ShareInfo::toJson).collect(Collectors.toList()));
        put(
            Field.EDITOR.getName(),
            editors.stream().map(ShareInfo::toJson).collect(Collectors.toList()));
      }
    };
  }
}
