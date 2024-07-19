package com.graebert.storage.storage.link.view;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.link.LinkConfiguration;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionLink;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.util.Utils;

public class ViewVersionLink extends VersionLink {
  protected ViewVersionLink() {
    setType(LinkType.VERSION);
  }

  protected ViewVersionLink(
      String userId,
      String ownerIdentity,
      String sheetId,
      String objectId,
      String externalId,
      StorageType storageType,
      String objectName,
      String mimeType,
      long expireDate,
      Boolean export,
      String passwordHeader,
      String versionId,
      VersionType versionType)
      throws PublicLinkException {
    super(
        userId,
        ownerIdentity,
        sheetId,
        objectId,
        externalId,
        storageType,
        objectName,
        mimeType,
        expireDate,
        export,
        passwordHeader,
        versionId,
        versionType);
    setType(LinkType.VERSION);
  }

  public static ViewVersionLink fromItem(Item item) {
    return new ViewVersionLink() {
      {
        insertBaseData(item);
      }
    };
  }

  public static ViewVersionLink createNew(
      String userId,
      String ownerIdentity,
      String sheetId,
      String objectId,
      String externalId,
      StorageType storageType,
      String objectName,
      String mimeType,
      long expireDate,
      Boolean export,
      String passwordHeader,
      String versionId,
      VersionType versionType)
      throws PublicLinkException {
    return new ViewVersionLink(
        userId,
        ownerIdentity,
        sheetId,
        objectId,
        externalId,
        storageType,
        objectName,
        mimeType,
        expireDate,
        export,
        passwordHeader,
        versionId,
        versionType);
  }

  @Override
  public String getEndUserLink() {
    return String.format(
        "%sfile/%s?versionId=%s&token=%s%s",
        LinkConfiguration.getInstance().getUiUrl(),
        Utils.getEncapsulatedId(storageType, externalId, objectId),
        versionId,
        token,
        this.hasSheetId() ? String.format("&sheetId=%s", this.sheetId) : "");
  }
}
