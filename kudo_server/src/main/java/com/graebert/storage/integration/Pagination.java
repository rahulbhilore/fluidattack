package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.concurrent.TimeUnit;

public class Pagination extends Dataplane {

  private final String tableName = TableName.TEMP.name;
  private final StorageType storageType;
  private final String externalId;
  private final String folderId;
  private int pageLimit = 20;
  private Item currentPageInfo = null;

  public Pagination(StorageType storageType, int pageLimit, String externalId, String folderId) {
    if (pageLimit > 0) {
      this.pageLimit = pageLimit;
    }
    this.storageType = storageType;
    this.externalId = externalId;
    this.folderId = folderId;
  }

  private String getPK(String pageToken) {
    return "pt#" + StorageType.getShort(storageType) + "+" + externalId + "+" + pageToken;
  }

  public Item getPageInfo(String pageToken) {
    if (!Utils.isStringNotNullOrEmpty(pageToken)) {
      pageToken = "0";
    }
    if (Utils.isStringNotNullOrEmpty(folderId)) {
      currentPageInfo = getItemFromCache(
          tableName,
          Field.PK.getName(),
          getPK(pageToken),
          Field.SK.getName(),
          folderId,
          DataEntityType.PAGINATION);
    }
    return currentPageInfo;
  }

  public int getOffset() {
    if (currentPageInfo != null && currentPageInfo.hasAttribute("offset")) {
      return currentPageInfo.getInt("offset");
    }
    return 0;
  }

  public int getLimit() {
    if (currentPageInfo != null && currentPageInfo.hasAttribute("limit")) {
      return currentPageInfo.getInt("limit");
    }
    return pageLimit;
  }

  public String getNextPageToken() {
    if (currentPageInfo != null && currentPageInfo.hasAttribute("nextPage")) {
      return currentPageInfo.getString("nextPage");
    }
    return "0";
  }

  private long getPageTTL() {
    return (GMTHelper.utcCurrentTime() + TimeUnit.DAYS.toMillis(1)) / 1000;
  }

  private String createInitialPage() {
    // if initial page already exists - no need to create the new one
    Item existingPage = getItemFromCache(
        tableName,
        Field.PK.getName(),
        getPK("0"),
        Field.SK.getName(),
        folderId,
        DataEntityType.PAGINATION);
    if (existingPage != null && existingPage.hasAttribute("nextPage")) {
      return existingPage.getString("nextPage");
    }
    String nextPageToken = Utils.generateUUID();
    Item page = new Item()
        .withString(Field.PK.getName(), getPK("0"))
        .withString(Field.SK.getName(), folderId)
        .withString(Field.TOKEN.getName(), "0")
        .withInt("limit", pageLimit)
        .withString("nextPage", nextPageToken)
        .withLong(Field.TTL.getName(), this.getPageTTL())
        .withInt("offset", 0);
    putItem(tableName, page, DataEntityType.PAGINATION);
    return nextPageToken;
  }

  public String saveNewPageInfo(String newPageToken, int offset) {
    String pageToken = Utils.generateUUID();
    if (Utils.isStringNotNullOrEmpty(newPageToken)) {
      // if there are no pages - create the first one
      if (newPageToken.equals("0")) {
        pageToken = createInitialPage();
      } else {
        pageToken = newPageToken;
      }
      Item existingPage = getItemFromCache(
          tableName,
          Field.PK.getName(),
          getPK(pageToken),
          Field.SK.getName(),
          folderId,
          DataEntityType.PAGINATION);
      // if page already exists - no need to create the new one
      if (existingPage != null) {
        return pageToken;
      }
    }
    String nextPageToken = Utils.generateUUID();
    Item page = new Item()
        .withString(Field.PK.getName(), getPK(pageToken))
        .withString(Field.SK.getName(), folderId)
        .withString(Field.TOKEN.getName(), pageToken)
        .withInt("limit", pageLimit)
        .withString("nextPage", nextPageToken)
        .withLong(Field.TTL.getName(), this.getPageTTL())
        .withInt("offset", offset + pageLimit);
    putItem(tableName, page, DataEntityType.PAGINATION);
    return pageToken;
  }

  public String saveNewPageInfo(int offset) {
    return saveNewPageInfo(null, offset);
  }
}
