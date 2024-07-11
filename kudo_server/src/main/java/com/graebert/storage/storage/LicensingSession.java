package com.graebert.storage.storage;

import com.amazonaws.services.dynamodbv2.document.Item;
import com.graebert.storage.gridfs.GMTHelper;
import com.graebert.storage.stats.logs.data.DataEntityType;
import com.graebert.storage.util.Field;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LicensingSession extends Dataplane {

  public static final String tableName = TableName.TEMP.name;
  public static final String pkPrefix = "LICENSING_SESSION";
  public static int sessionTTLInMinutes = 60;

  public static void createLicensingSession(String revision, Item session, String token) {
    Long timezoneOffset = null;
    if (session.hasAttribute(Field.PREFERENCES.getName())) {
      Map<String, Object> preferences = session.getMap(Field.PREFERENCES.getName());
      if (preferences.containsKey("timezoneOffset")) {
        timezoneOffset = ((BigDecimal) preferences.get("timezoneOffset")).longValue() / 1000;
      }
    }
    Item licensingSession = new Item()
        .withPrimaryKey(
            Dataplane.pk,
            LicensingSession.pkPrefix,
            Dataplane.sk,
            // to avoid duplicates
            LicensingSession.pkPrefix + session.getString(Field.SK.getName()))
        .withString("sessionPk", session.getString(Field.PK.getName()))
        .withString("sessionSk", session.getString(Field.SK.getName()))
        .withLong(
            Field.TTL.getName(),
            GMTHelper.utcCurrentTime() / 1000 + TimeUnit.MINUTES.toSeconds(sessionTTLInMinutes))
        .withLong("loggedIn", session.getLong("loggedIn"))
        .withString("revision", revision)
        .withString(Field.TOKEN.getName(), token);
    if (timezoneOffset != null) {
      licensingSession.withLong("timezone", timezoneOffset);
    }
    putItem(tableName, licensingSession, DataEntityType.SESSION);
  }
}
