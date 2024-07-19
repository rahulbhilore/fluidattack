package com.graebert.storage.integration;

import com.graebert.storage.integration.hancom.Hancom;
import com.graebert.storage.util.Utils;
import org.jetbrains.annotations.NonNls;

public enum StorageType {
  INTERNAL,
  BOX,
  DROPBOX,
  GDRIVE,
  TRIMBLE,
  ONSHAPE,
  ONSHAPEDEV,
  ONSHAPESTAGING,
  ONEDRIVE,
  ONEDRIVEBUSINESS,
  SHAREPOINT,
  WEBDAV,
  NEXTCLOUD,
  SAMPLES,
  HANCOM,
  HANCOMSTG;

  public static StorageType getStorageType(String value) {
    if (!Utils.isStringNotNullOrEmpty(value)) {
      return SAMPLES;
    }
    if (value.trim().toUpperCase().equals(INTERNAL.toString())) {
      return SAMPLES;
    }
    final String formattedValue = value.trim().toUpperCase();
    for (StorageType t : StorageType.values()) {
      if (t.toString().equals(formattedValue)) {
        return t;
      }
    }
    if (formattedValue.startsWith(WEBDAV.toString())) {
      return WEBDAV;
    }
    if (formattedValue.startsWith(NEXTCLOUD.toString())) {
      return NEXTCLOUD;
    }
    // DK: just in case
    if (formattedValue.startsWith(ONEDRIVE.toString())) {
      return ONEDRIVE;
    }
    return SAMPLES;
  }

  public static String getAddress(StorageType type) {
    switch (type) {
      case BOX:
        return Box.address;
      case DROPBOX:
        return DropBox.address;
      case GDRIVE:
        return GDrive.address;
      case TRIMBLE:
        return TrimbleConnect.address;
      case ONSHAPE:
        return Onshape.addressProduction;
      case ONSHAPEDEV:
        return Onshape.addressDev;
      case ONSHAPESTAGING:
        return Onshape.addressStaging;
      case INTERNAL:
      case SAMPLES:
        return SimpleFluorine.address;
      case ONEDRIVE:
        return OneDrive.addressPersonal;
      case ONEDRIVEBUSINESS:
        return OneDrive.addressBusiness;
      case SHAREPOINT:
        return SharePoint.address;
      case WEBDAV:
        return WebDAV.address;
      case NEXTCLOUD:
        return NextCloud.address;
      case HANCOM:
        return Hancom.addressProd;
      case HANCOMSTG:
        return Hancom.addressStg;
    }
    return "";
  }

  public static String processFileIdForWebsocketNotification(StorageType type, String fileId) {
    switch (type) {
      case BOX:
      case DROPBOX:
      case GDRIVE:
      case TRIMBLE:
      case ONSHAPE:
      case ONSHAPEDEV:
      case ONSHAPESTAGING:
      case INTERNAL:
      case SAMPLES:
      case WEBDAV:
      case NEXTCLOUD:
        return fileId;
      case ONEDRIVE:
      case ONEDRIVEBUSINESS:
      case SHAREPOINT:
      case HANCOM:
      case HANCOMSTG:
        return Utils.urlencode(fileId);
    }
    return "";
  }

  @NonNls
  public static String getShort(StorageType type) {
    switch (type) {
      case INTERNAL:
      case SAMPLES:
        return "SF";
      case BOX:
        return "BX";
      case DROPBOX:
        return "DB";
      case GDRIVE:
        return "GD";
      case TRIMBLE:
        return "TR";
      case ONSHAPE:
        return "OS";
      case ONSHAPEDEV:
        return "OSDEV";
      case ONSHAPESTAGING:
        return "OSSTAGING";
      case ONEDRIVE:
        return "OD";
      case ONEDRIVEBUSINESS:
        return "ODB";
      case SHAREPOINT:
        return "SP";
      case WEBDAV:
        return "WD";
      case NEXTCLOUD:
        return "NC";
      case HANCOM:
        return "HC";
      case HANCOMSTG:
        return "HCS";
    }
    return "";
  }

  public static String getShort(String type) {
    StorageType t = getStorageType(type);
    if (t == WEBDAV) {
      return getShort(WEBDAV) + type.substring(WEBDAV.toString().length());
    } else if (t == NEXTCLOUD) {
      return getShort(NEXTCLOUD) + type.substring(NEXTCLOUD.toString().length());
    } else {
      return getShort(t);
    }
  }

  public static StorageType getByShort(@NonNls String name) {
    switch (name) {
      case "FL":
      case "SF":
        return SAMPLES;
      case "GD":
        return GDRIVE;
      case "BX":
        return BOX;
      case "DB":
        return DROPBOX;
      case "OS":
        return ONSHAPE;
      case "OSDEV":
        return ONSHAPEDEV;
      case "OSSTAGING":
        return ONSHAPESTAGING;
      case "TR":
        return TRIMBLE;
      case "OD":
        return ONEDRIVE;
      case "ODB":
        return ONEDRIVEBUSINESS;
      case "SP":
        return SHAREPOINT;
      case "WD":
        return WEBDAV;
      case "NC":
        return NEXTCLOUD;
      case "HC":
        return HANCOM;
      case "HCS":
        return HANCOMSTG;
    }
    if (name.startsWith("WD")) {
      return WEBDAV;
    }
    return null;
  }

  /**
   * Return display name for the storage
   *
   * @param type - type of storage
   * @return User-friendly name to display
   */
  public static String getDisplayName(StorageType type) {
    switch (type) {
      default:
        return "";
      case INTERNAL:
      case SAMPLES:
        return "ARES Kudo Drive";
      case BOX:
        return "Box";
      case DROPBOX:
        return "Dropbox";
      case GDRIVE:
        return "Google Drive";
      case TRIMBLE:
        return "Trimble Connect";
      case ONSHAPE:
        return "Onshape";
      case ONSHAPEDEV:
        return "Onshape development";
      case ONSHAPESTAGING:
        return "Onshape Staging";
      case ONEDRIVE:
        return "OneDrive";
      case ONEDRIVEBUSINESS:
        return "OneDrive for Business";
      case SHAREPOINT:
        return "SharePoint";
      case WEBDAV:
        return "WebDAV";
      case NEXTCLOUD:
        return "Nextcloud";
      case HANCOM:
        return "Hancom";
      case HANCOMSTG:
        return "Hancom Staging";
    }
  }

  public static String shortToLong(String storageType) {
    StorageType st = getByShort(storageType);
    if (st == WEBDAV) {
      return WEBDAV + storageType.substring(2);
    } else if (st == NEXTCLOUD) {
      return NEXTCLOUD + storageType.substring(2);
    } else {
      return st.toString();
    }
  }
}
