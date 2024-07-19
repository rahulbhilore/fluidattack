package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.box.sdk.BoxAPIConnection;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxAPIRequest;
import com.box.sdk.BoxCollaboration;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFolder;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxJSONResponse;
import com.box.sdk.FileUploadParams;
import com.box.sdk.PartialCollection;
import com.eclipsesource.json.JsonArray;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;

/**
 * Created by robert on 2/23/2017.
 */
public class AWSXRayBoxFolder {
  private final BoxFolder folder;

  public AWSXRayBoxFolder(BoxFolder _folder) {
    folder = _folder;
  }

  public static io.vertx.core.json.JsonArray getTrashFolder(
      long offset, long limit, BoxAPIConnection api) { // there's no proper function in SDK, see
    // https://github.com/box/box-java-sdk/issues/838
    URL url = null;
    try {
      url = new URL(api.getBaseURL() + "folders/trash/items/?offset=" + offset + "&limit=" + limit);
    } catch (MalformedURLException e) {
    }
    BoxAPIRequest request = new BoxAPIRequest(api, url, "GET");
    BoxJSONResponse responseB = (BoxJSONResponse) request.send();
    com.eclipsesource.json.JsonObject responseJSON =
        com.eclipsesource.json.JsonObject.readFrom(responseB.getJSON());
    JsonArray jsonArray = responseJSON.get("entries").asArray();
    io.vertx.core.json.JsonArray result = new io.vertx.core.json.JsonArray(jsonArray.toString());
    return result;
  }

  public Iterable<BoxItem.Info> getChildren(final String... fields) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.getChildren");

    try {
      Iterable<BoxItem.Info> result = folder.getChildren(fields);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public PartialCollection<BoxItem.Info> getChildrenRange(
      long offset, long limit, String... fields) {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.getChildrenRange");

    try {
      PartialCollection<BoxItem.Info> result = folder.getChildrenRange(offset, limit, fields);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public Collection<BoxCollaboration.Info> getCollaborations() {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.getCollaborations");

    try {
      Collection<BoxCollaboration.Info> result = folder.getCollaborations();
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFolder.Info getInfo() {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.getInfo");

    try {
      BoxFolder.Info result = folder.getInfo();
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFolder.Info getInfo(String... fields) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.getInfo");

    try {
      BoxFolder.Info result = folder.getInfo(fields);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxCollaboration.Info collaborate(String email, BoxCollaboration.Role role) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.collaborate");

    try {
      BoxCollaboration.Info result = folder.collaborate(email, role);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFile.Info uploadFile(FileUploadParams uploadParams) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.uploadFile");

    try {
      BoxFile.Info result = folder.uploadFile(uploadParams);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void delete(boolean recursive) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.delete");

    try {
      folder.delete(recursive);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFolder.Info createFolder(String name) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.createFolder");

    try {
      BoxFolder.Info result = folder.createFolder(name);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxItem.Info move(AWSXRayBoxFolder destination) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.move");

    try {
      BoxItem.Info result = folder.move(destination.getFolder(), null);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFolder.Info copy(AWSXRayBoxFolder destination) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.copy");

    try {
      BoxFolder.Info result = folder.copy(destination.getFolder(), null);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFolder.Info copy(AWSXRayBoxFolder destination, String newName) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.copy");

    try {
      BoxFolder.Info result = folder.copy(destination.getFolder(), newName);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void rename(String newName) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.Folder.rename");

    try {
      folder.rename(newName);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public String getID() {
    return folder.getID();
  }

  public BoxFolder getFolder() {
    return folder;
  }
}
