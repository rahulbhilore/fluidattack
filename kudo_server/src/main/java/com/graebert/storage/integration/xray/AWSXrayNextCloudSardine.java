package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.graebert.storage.integration.webdav.SardineImpl2;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayEntityUtils;
import com.graebert.storage.xray.XRayManager;
import com.graebert.storage.xray.XrayField;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import javax.xml.namespace.QName;

public class AWSXrayNextCloudSardine {
  public static List<DavResource> list(Sardine sardine, String path, int depth, Set<QName> props)
      throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.list");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);
      List<DavResource> result = sardine.list(path, depth, props);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

      return result;
    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static InputStream get(Sardine sardine, String path) throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.get");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      InputStream result = sardine.get(path);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

      return result;
    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static void put(SardineImpl2 sardine, String path, byte[] data) throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.put");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      // do not use expect Continue, as some webdav servers do not support it.
      sardine.put(path, data, null, false);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static void createDirectory(Sardine sardine, String path) throws IOException {
    Entity subsegment =
        XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.createDirectory");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      sardine.createDirectory(path);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static void move(Sardine sardine, String source, String destination) throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.move");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.SOURCE, source);
      XRayEntityUtils.putAnnotation(subsegment, XrayField.DESTINATION, destination);

      sardine.move(source, destination);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static void delete(Sardine sardine, String path) throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.delete");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.PATH, path);

      sardine.delete(path);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }

  public static void copy(Sardine sardine, String source, String destination) throws IOException {
    Entity subsegment = XRayManager.createSubSegment(OperationGroup.NEXTCLOUD, "WEBDAV.copy");
    try {
      XRayEntityUtils.putAnnotation(subsegment, XrayField.SOURCE, source);
      XRayEntityUtils.putAnnotation(subsegment, XrayField.DESTINATION, destination);

      sardine.copy(source, destination);

      //      XRayEntityUtils.putAnnotation(subsegment, Field.STATUS.getName(), result.getStatus());
      //      XRayEntityUtils.putAnnotation(subsegment, "statusTest", result.getStatusText());
      XRayManager.endSegment(subsegment);

    } catch (IOException e) {
      XRayManager.endSegment(subsegment, e);
      throw e;
    }
  }
}
