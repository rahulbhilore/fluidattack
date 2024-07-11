package com.graebert.storage.integration;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.storage.Dataplane;
import com.graebert.storage.util.Field;
import com.graebert.storage.util.Utils;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class UsersPagination extends Dataplane {
  private static final String tableName = TableName.TEMP.name;
  private static final int pageLimit = 100;
  private String lastIndexValue;
  private Item currentPageInfo = null;

  private static long getPageTTL() {
    return (GMTHelper.utcCurrentTime() + TimeUnit.DAYS.toMillis(1)) / 1000;
  }

  public String getLastIndexValue() {
    return Optional.ofNullable(lastIndexValue).filter(val -> !val.isEmpty()).orElse("1");
  }

  public void setLastIndexValue(String lastIndexValue) {
    this.lastIndexValue = lastIndexValue;
  }

  private String getPK(String pageToken) {
    return "userspage#" + pageToken;
  }

  public void getPageInfo(String pageToken) {
    String token = pageToken;
    if (!Utils.isStringNotNullOrEmpty(pageToken)) {
      token = "0";
    }
    currentPageInfo = getItemFromCache(
        tableName, Dataplane.pk, getPK(token), Dataplane.sk, token, DataEntityType.USERS);
    if (currentPageInfo != null) {
      setLastIndexValue(currentPageInfo.getString("lastIndexValue"));
    }
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

  private String createInitialPage() {
    // if initial page already exists - no need to create the new one
    Item existingPage = getItemFromCache(
        tableName, Dataplane.pk, getPK("0"), Dataplane.sk, "0", DataEntityType.USERS);
    if (existingPage != null && existingPage.hasAttribute("nextPage")) {
      return existingPage.getString("nextPage");
    }
    String nextPageToken = Utils.generateUUID();
    Item page = new Item()
        .withString(Field.PK.getName(), getPK("0"))
        .withString(Field.SK.getName(), "0")
        .withString(Field.TOKEN.getName(), "0")
        .withInt("limit", pageLimit)
        .withString("nextPage", nextPageToken)
        .withLong(Field.TTL.getName(), getPageTTL())
        .withString("lastIndexValue", emptyString);
    putItem(tableName, page, DataEntityType.USERS);
    return nextPageToken;
  }

  public String saveNewPageInfo(String newPageToken) {
    String pageToken = Utils.generateUUID();
    if (Utils.isStringNotNullOrEmpty(newPageToken)) {
      // if there are no pages - create the first one
      if (newPageToken.equals("0")) {
        pageToken = createInitialPage();
      } else {
        pageToken = newPageToken;
      }
      Item existingPage = getItemFromCache(
          tableName, Dataplane.pk, getPK(pageToken), Dataplane.sk, pageToken, DataEntityType.USERS);
      // if page already exists - no need to create the new one
      if (existingPage != null) {
        return pageToken;
      }
    }

    String nextPageToken = Utils.generateUUID();
    Item page = new Item()
        .withString(Field.PK.getName(), getPK(pageToken))
        .withString(Field.SK.getName(), pageToken)
        .withString(Field.TOKEN.getName(), pageToken)
        .withInt("limit", pageLimit)
        .withString("nextPage", nextPageToken)
        .withLong(Field.TTL.getName(), getPageTTL())
        .withString("lastIndexValue", lastIndexValue);
    putItem(tableName, page, DataEntityType.USERS);
    return pageToken;
  }
}
