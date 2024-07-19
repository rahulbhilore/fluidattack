package com.graebert.storage.integration.onedrive.graphApi.entity;

import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;

public class GraphId {
  private final String GRAPH_URL = "https://graph.microsoft.com/v1.0";
  private String id = null;
  private String driveId = null;

  public GraphId(String id) {
    if (id != null) {

      String[] strings = id.split(":");
      if (strings.length == 2) {
        this.driveId = strings[0];
        id = strings[1];
      }

      this.id = id;
    }
  }

  public GraphId(String driveId, String id) {
    String[] strings = id.split(":");
    if (strings.length == 2) {
      this.driveId = strings[0];
      id = strings[1];
    } else if (Utils.isStringNotNullOrEmpty(driveId)) {
      this.driveId = driveId;
    }

    this.id = id;
  }

  public GraphId(RootInfo rootInfo) {
    this.id = rootInfo.getId();
    this.driveId = rootInfo.getDriveId();
  }

  public String getFullId() {
    return driveId != null ? driveId + ":" + id : id;
  }

  public String getId() {
    return id;
  }

  public String getDriveId() {
    return driveId;
  }

  public String getDriveUrl(String homeDrive) {
    return driveId == null ? GRAPH_URL + homeDrive + "/drive" : GRAPH_URL + "/drives/" + driveId;
  }

  public String getItemUrl(String homeDrive) {
    return driveId == null
        ? GRAPH_URL + homeDrive + "/drive"
        : GRAPH_URL + "/drives/" + driveId + "/items/" + id;
  }

  public String getItemUrlEscapeRoot(String homeDrive) {
    String url =
        driveId == null ? GRAPH_URL + homeDrive + "/drive" : GRAPH_URL + "/drives/" + driveId;

    if (!id.equals(Field.ROOT.getName())) {
      url += "/items/" + id;
    }

    return url;
  }
}
