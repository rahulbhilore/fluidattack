package com.graebert.storage.storage.link.download;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.Entities.PublicLinkException;
import com.graebert.storage.integration.StorageType;
import com.graebert.storage.storage.link.LinkConfiguration;
import com.graebert.storage.storage.link.LinkType;
import com.graebert.storage.storage.link.VersionLink;
import com.graebert.storage.storage.link.VersionType;
import com.graebert.storage.util.Utils;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

public class DownloadVersionLink extends VersionLink {
  private boolean convertToPdf;

  protected DownloadVersionLink() {
    setType(LinkType.DOWNLOAD);
  }

  protected DownloadVersionLink(
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
      VersionType versionType,
      Boolean convertToPdf)
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
    setType(LinkType.DOWNLOAD);
    this.convertToPdf = Objects.nonNull(convertToPdf) ? convertToPdf : false;
  }

  public static DownloadVersionLink fromItem(Item item) {
    return new DownloadVersionLink() {
      {
        insertBaseData(item);
      }
    };
  }

  public static DownloadVersionLink createNew(
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
      VersionType versionType,
      Boolean convertToPdf)
      throws PublicLinkException {
    return new DownloadVersionLink(
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
        versionType,
        convertToPdf);
  }

  @Override
  protected void insertBaseData(Item item) {
    super.insertBaseData(item);
    this.convertToPdf = item.getBoolean("convertToPdf");
  }

  @Override
  public String getEndUserLink() {
    return String.format(
        "%sfile/%s/version/%s/link?token=%s%s",
        LinkConfiguration.getInstance().getUiUrl(),
        Utils.getEncapsulatedId(storageType, externalId, objectId),
        versionId,
        token,
        this.hasSheetId() ? String.format("&sheetId=%s", this.sheetId) : "");
  }

  @Override
  public JsonObject toJson() {
    return super.toJson().put("convertToPdf", this.convertToPdf);
  }

  @Override
  public Item toItem() {
    return super.toItem().withBoolean("convertToPdf", this.convertToPdf);
  }

  public boolean isConvertToPdf() {
    return convertToPdf;
  }

  public void setConvertToPdf(boolean convertToPdf) {
    this.convertToPdf = convertToPdf;
  }
}
