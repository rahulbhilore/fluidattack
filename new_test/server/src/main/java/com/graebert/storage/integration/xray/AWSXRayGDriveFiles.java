package com.graebert.storage.integration.xray;

import com.amazonaws.xray.entities.Entity;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.media.MediaHttpUploader;
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveRequest;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.graebert.storage.util.Field;
import com.graebert.storage.xray.OperationGroup;
import com.graebert.storage.xray.XRayManager;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by robert on 3/1/2017.
 */
public class AWSXRayGDriveFiles {
  public static Logger log = LogManager.getRootLogger();
  private final Drive.Files files;

  AWSXRayGDriveFiles(Drive.Files _files) {
    files = _files;
  }

  public AWSXRayDriveListRequest list(Integer pageSize) throws IOException {
    Drive.Files.List result = files
        .list()
        .setSupportsAllDrives(true)
        .setIncludeItemsFromAllDrives(true)
        .setCorpora("user,allTeamDrives");
    if (Objects.nonNull(pageSize)) {
      result.setPageSize(pageSize);
    }
    return new AWSXRayDriveListRequest("GDrive.Files.list", result);
  }

  public AWSXRayDriveListRequest listTrash(Integer pageSize) throws IOException {
    Drive.Files.List result = files.list().setCorpora(Field.USER.getName());
    if (Objects.nonNull(pageSize)) {
      result.setPageSize(pageSize);
    }
    return new AWSXRayDriveListRequest("GDrive.Files.listTrash", result);
  }

  public AWSXRayDriveGetRequest get(String fileId) throws IOException {
    Drive.Files.Get result = files.get(fileId).setSupportsAllDrives(true);
    result.getMediaHttpDownloader().setProgressListener(mediaHttpDownloader -> {
      switch (mediaHttpDownloader.getDownloadState()) {
        case MEDIA_IN_PROGRESS:
          System.out.println(mediaHttpDownloader.getProgress());
          break;
        case MEDIA_COMPLETE:
          System.out.println("Download is complete!");
      }
    });
    return new AWSXRayDriveGetRequest("GDrive.Files.get", result);
  }

  public AWSXRayDriveExportRequest export(String fileId, String mimeType) throws IOException {
    Drive.Files.Export result = files.export(fileId, mimeType);
    return new AWSXRayDriveExportRequest("GDrive.Files.export", result);
  }

  public AWSXRayDriveUpdateRequest update(String fileId, File content) throws IOException {
    Drive.Files.Update result =
        files.update(fileId, content).setSupportsAllDrives(true).setSupportsTeamDrives(true);
    return new AWSXRayDriveUpdateRequest("GDrive.Files.update", result);
  }

  public AWSXRayDriveRequest<Drive.Files.Update, File> update(
      String fileId, File content, AbstractInputStreamContent mediaContent) throws IOException {
    Drive.Files.Update result =
        files.update(fileId, content, mediaContent).setSupportsAllDrives(true);
    return new AWSXRayDriveRequest<>("GDrive.Files.update", result);
  }

  public AWSXRayDriveRequest<Drive.Files.Delete, Void> delete(String fileid) throws IOException {
    Drive.Files.Delete result = files.delete(fileid);
    return new AWSXRayDriveRequest<>("GDrive.Files.delete", result);
  }

  public AWSXRayDriveRequest<Drive.Files.EmptyTrash, Void> emptyTrash() throws IOException {
    Drive.Files.EmptyTrash result = files.emptyTrash();
    return new AWSXRayDriveRequest<>("GDrive.Files.emptyTrash", result);
  }

  public AWSXRayDriveRequest<Drive.Files.Copy, File> copy(String fileid, File content)
      throws IOException {
    Drive.Files.Copy result = files.copy(fileid, content);
    return new AWSXRayDriveRequest<>("GDrive.Files.copy", result);
  }

  public AWSXRayDriveRequest<Drive.Files.Create, File> create(File content) throws IOException {
    Drive.Files.Create result = files.create(content).setSupportsAllDrives(true);
    return new AWSXRayDriveRequest<>("GDrive.Files.create", result);
  }

  public AWSXRayDriveRequest<Drive.Files.Create, File> create(
      File content, AbstractInputStreamContent mediaContent) throws IOException {
    Drive.Files.Create result = files.create(content, mediaContent).setSupportsAllDrives(true);
    result
        .getMediaHttpUploader()
        .setProgressListener(new CustomProgressListener())
        .setDirectUploadEnabled(true);
    // turned off resumable upload because of XENON-17089 - Cannot upload big files to Google Drive
    return new AWSXRayDriveRequest<>("GDrive.Files.create", result);
  }

  public static class AWSXRayDriveRequest<R extends DriveRequest<S>, S> {
    public R request;
    String name;

    AWSXRayDriveRequest(String name_, R request_) {
      request = request_;
      name = name_;
    }

    public S execute() throws IOException {
      Entity subsegment = XRayManager.createSubSegment(OperationGroup.GDRIVE, name);
      try {
        S result = request.execute();
        if (XRayManager.isXrayEnabled() && subsegment != null) {
          HashMap<String, java.io.Serializable> responseAttributes = new HashMap<>();
          int responseCode = request.getLastStatusCode();
          switch (responseCode / 100) {
            case 4:
              subsegment.setError(true);
              if (responseCode == 429) {
                subsegment.setThrottle(true);
              }
              break;
            case 5:
              subsegment.setFault(true);
          }
          responseAttributes.put(Field.STATUS.getName(), responseCode);
          responseAttributes.put(Field.MESSAGE.getName(), request.getLastStatusMessage());
          subsegment.putHttp("response", responseAttributes);
          XRayManager.endSegment(subsegment);
        }
        return result;
      } catch (IOException e) {
        XRayManager.getCurrentSegment().ifPresent(segment -> {
          log.info("### Error on executing GDrive request. TraceId: "
              + segment.getTraceId().toString());
        });

        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public AWSXRayDriveRequest<R, S> setFields(String fields) {
      request.setFields(fields);
      return this;
    }
  }

  public static class AWSXRayDriveListRequest
      extends AWSXRayDriveRequest<Drive.Files.List, FileList> {
    AWSXRayDriveListRequest(String name, Drive.Files.List request_) {
      super(name, request_);
    }

    public AWSXRayDriveListRequest setQ(String fields) {
      request.setQ(fields);
      return this;
    }

    public AWSXRayDriveListRequest setOrderBy(String orderBy) {
      request.setOrderBy(orderBy);
      return this;
    }

    public void setIncludeItemsFromAllDrives() {
      request.setIncludeItemsFromAllDrives(true);
      request.setSupportsAllDrives(true);
      request.setCorpora("drive");
    }

    public AWSXRayDriveListRequest setFields(String fields) {
      super.setFields(fields);
      return this;
    }

    public String getURL() {
      return request.buildHttpRequestUrl().build();
    }

    public AWSXRayDriveListRequest setDriveId(String driveId) {
      request.setDriveId(driveId);
      return this;
    }

    public AWSXRayDriveListRequest setPageToken(java.lang.String pageToken) {
      request.setPageToken(pageToken);
      return this;
    }
  }

  public static class AWSXRayDriveExportRequest
      extends AWSXRayDriveRequest<Drive.Files.Export, Void> {
    AWSXRayDriveExportRequest(String name, Drive.Files.Export request_) {
      super(name, request_);
    }

    public void executeMediaAndDownloadTo(OutputStream outputStream) throws IOException {
      Entity subsegment = XRayManager.createSubSegment(OperationGroup.GDRIVE, name);
      try {
        request.executeMediaAndDownloadTo(outputStream);
        if (XRayManager.isXrayEnabled() && subsegment != null) {
          HashMap<String, java.io.Serializable> responseAttributes = new HashMap<>();
          int responseCode = request.getLastStatusCode();
          switch (responseCode / 100) {
            case 4:
              subsegment.setError(true);
              if (responseCode == 429) {
                subsegment.setThrottle(true);
              }
              break;
            case 5:
              subsegment.setFault(true);
          }
          responseAttributes.put(Field.STATUS.getName(), responseCode);
          responseAttributes.put(Field.MESSAGE.getName(), request.getLastStatusMessage());
          subsegment.putHttp("response", responseAttributes);
          XRayManager.endSegment(subsegment);
        }
      } catch (IOException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }
  }

  public static class AWSXRayDriveGetRequest extends AWSXRayDriveRequest<Drive.Files.Get, File> {
    AWSXRayDriveGetRequest(String name, Drive.Files.Get request_) {
      super(name, request_);
    }

    public void executeMediaAndDownloadTo(java.io.OutputStream outputStream)
        throws java.io.IOException {
      Entity subsegment = XRayManager.createSubSegment(OperationGroup.GDRIVE, name);
      try {
        request.executeMediaAndDownloadTo(outputStream);
        if (XRayManager.isXrayEnabled() && subsegment != null) {
          HashMap<String, java.io.Serializable> responseAttributes =
              new HashMap<String, java.io.Serializable>();
          int responseCode = request.getLastStatusCode();
          switch (responseCode / 100) {
            case 4:
              subsegment.setError(true);
              if (responseCode == 429) {
                subsegment.setThrottle(true);
              }
              break;
            case 5:
              subsegment.setFault(true);
          }
          responseAttributes.put(Field.STATUS.getName(), responseCode);
          responseAttributes.put(Field.MESSAGE.getName(), request.getLastStatusMessage());
          subsegment.putHttp("response", responseAttributes);
          XRayManager.endSegment(subsegment);
        }
      } catch (IOException e) {
        XRayManager.endSegment(subsegment, e);
        throw e;
      }
    }

    public AWSXRayDriveGetRequest setFields(String fields) {
      super.setFields(fields);
      return this;
    }

    public AWSXRayDriveGetRequest queue(BatchRequest batchRequest, JsonBatchCallback<File> callback)
        throws IOException {
      request.queue(batchRequest, callback);
      return this;
    }

    public AWSXRayDriveGetRequest setSupportsAllDrives(boolean doesSupport) {
      request.setSupportsAllDrives(doesSupport);
      return this;
    }
  }

  public static class AWSXRayDriveUpdateRequest
      extends AWSXRayDriveRequest<Drive.Files.Update, File> {
    AWSXRayDriveUpdateRequest(String name, Drive.Files.Update request_) {
      super(name, request_);
    }

    public AWSXRayDriveUpdateRequest setAddParents(String orderBy) {
      request.setAddParents(orderBy);
      return this;
    }

    public void setRemoveParents(String orderBy) {
      request.setRemoveParents(orderBy);
    }

    public AWSXRayDriveUpdateRequest setFields(String fields) {
      super.setFields(fields);
      return this;
    }
  }

  private static class CustomProgressListener implements MediaHttpUploaderProgressListener {
    public void progressChanged(MediaHttpUploader uploader) throws IOException {
      switch (uploader.getUploadState()) {
        case INITIATION_STARTED:
          System.out.println("Initiation has started!");
          break;
        case INITIATION_COMPLETE:
          System.out.println("Initiation is complete!");
          break;
        case MEDIA_IN_PROGRESS:
          if (uploader.getMediaContent().getLength() != -1) {
            System.out.println("Progress.. " + uploader.getProgress());
          } else {
            System.out.println("Bytes uploaded.. " + uploader.getNumBytesUploaded());
          }
          break;
        case MEDIA_COMPLETE:
          System.out.println("Upload is complete!");
      }
    }
  }
}
