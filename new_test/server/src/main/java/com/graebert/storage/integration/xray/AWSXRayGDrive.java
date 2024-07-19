package com.graebert.storage.integration.xray;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.Permission;
import java.io.IOException;

/**
 * Created by robert on 3/1/2017.
 */
public class AWSXRayGDrive {
  private final Drive drive;

  public AWSXRayGDrive(Drive _drive) {
    drive = _drive;
  }

  public AWSXRayGDriveFiles files() {
    return new AWSXRayGDriveFiles(drive.files());
  }

  public Drive.Permissions permissions() {
    return (drive.permissions());
  }

  public void createPermission(String fileId, Permission permission) throws IOException {
    permissions().create(fileId, permission).setSupportsAllDrives(true).execute();
  }

  public void updatePermission(String fileId, String permissionId, Permission permission)
      throws IOException {
    permissions()
        .update(fileId, permissionId, permission)
        .setSupportsAllDrives(true)
        .execute();
  }

  public void deletePermission(String fileId, String permissionId) throws IOException {
    permissions().delete(fileId, permissionId).setSupportsAllDrives(true).execute();
  }

  public Drive.About about() {
    return (drive.about());
  }

  public Drive.Revisions revisions() {
    return (drive.revisions());
  }

  public Drive.Drives drives() {
    return (drive.drives());
  }

  public BatchRequest batch() {
    return drive.batch();
  }
}
