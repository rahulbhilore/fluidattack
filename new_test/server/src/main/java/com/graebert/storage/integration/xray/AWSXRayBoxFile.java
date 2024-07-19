package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.box.sdk.BoxAPIException;
import com.box.sdk.BoxCollaboration;
import com.box.sdk.BoxFile;
import com.box.sdk.BoxFileVersion;
import com.box.sdk.BoxItem;
import com.box.sdk.BoxSharedLink;
import com.box.sdk.sharedlink.BoxSharedLinkRequest;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Created by robert on 2/23/2017.
 */
public class AWSXRayBoxFile {
  private final BoxFile file;

  public AWSXRayBoxFile(BoxFile _file) {
    file = _file;
  }

  public URL getDownloadUrl() {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.getDownloadUrl");

    try {
      XRayManager.endSegment(subsegment);
      return file.getDownloadURL();
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void download(OutputStream output) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.download");

    try {
      file.download(output, null);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFile.Info getInfo() {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.getInfo");

    try {
      BoxFile.Info result = file.getInfo();
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public Collection<BoxCollaboration.Info> getCollaborations() {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.getCollaborations");

    try {
      Collection<BoxCollaboration.Info> result = StreamSupport.stream(
              file.getAllFileCollaborations().spliterator(), false)
          .collect(Collectors.toCollection(ArrayList::new));
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxCollaboration.Info collaborate(String email, BoxCollaboration.Role role) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.collaborate");

    try {
      BoxCollaboration.Info result = file.collaborate(email, role, true, false);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFile.Info getInfo(String... fields) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.getInfo");

    try {
      BoxFile.Info result = file.getInfo(fields);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void delete() {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.delete");

    try {
      file.delete();
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxItem.Info move(AWSXRayBoxFolder destination) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.move");

    try {
      BoxItem.Info result = file.move(destination.getFolder(), null);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFile.Info copy(AWSXRayBoxFolder destination, String newName) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.copy");

    try {
      BoxFile.Info result = file.copy(destination.getFolder(), newName);
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void rename(String newName) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.rename");

    try {
      file.rename(newName);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public String getID() {
    return file.getID();
  }

  public BoxSharedLink createSharedLink(
      BoxSharedLink.Access access, Date unshareDate, BoxSharedLink.Permissions permissions) {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.createSharedLink");

    try {
      BoxSharedLinkRequest sharedLinkRequest = new BoxSharedLinkRequest();
      BoxSharedLink result = sharedLinkRequest
          .access(access)
          .unsharedDate(unshareDate)
          .permissions(permissions.getCanDownload(), permissions.getCanPreview())
          .asSharedLink();
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public Collection<BoxFileVersion> getVersions() {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.getVersions");

    try {
      Collection<BoxFileVersion> result = file.getVersions();
      XRayManager.endSegment(subsegment);
      return result;
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void uploadVersion(InputStream fileContent) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.uploadVersion");

    try {
      file.uploadNewVersion(fileContent, null);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void downloadRange(OutputStream output, long offset) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.downloadRange");

    try {
      file.downloadRange(output, offset, -1);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public void downloadRange(OutputStream output, long rangeStart, long rangeEnd) {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.BOX, "BOX.File.downloadRange");

    try {
      file.downloadRange(output, rangeStart, rangeEnd);
      XRayManager.endSegment(subsegment);
    } catch (BoxAPIException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public BoxFile getFile() {
    return file;
  }
}
