package com.graebert.storage.gridfs;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

public class GMTHelper {

  public static long utcCurrentTime() {
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).getTimeInMillis();
  }

  public static String timestampToString(long time) {
    Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    c.setTimeInMillis(time);
    DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
    String strDate = dateFormat.format(c.getTime()) + " UTC";
    return strDate;
  }

  /**
   * Returns time using correct timezone
   *
   * @param time           time
   * @param timezoneOffset offset in ms (Java style e.g. SPb = 10800000, Rio de Janeiro = -10800000)
   * @return Date's string representation
   */
  public static String timestampToString(long time, int timezoneOffset) {
    // we should convert minutes to milliseconds
    String[] availableTimezones = TimeZone.getAvailableIDs(timezoneOffset);
    if (availableTimezones.length > 0) {
      // if we found a timezone suitable for offset - format time
      // otherwise - let's return UTC time
      TimeZone foundTimezone = TimeZone.getTimeZone(availableTimezones[0]);
      Calendar c = Calendar.getInstance(foundTimezone);
      c.setTimeInMillis(time);
      DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss z");
      return dateFormat.format(c.getTime());
    } else {
      return timestampToString(time);
    }
  }

  public static long utcMidnightTime() {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  public static long toUtcMidnightTime(long millis) {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    cal.setTimeInMillis(millis);
    cal.set(Calendar.HOUR_OF_DAY, 0);
    cal.set(Calendar.MINUTE, 0);
    cal.set(Calendar.SECOND, 0);
    cal.set(Calendar.MILLISECOND, 0);
    return cal.getTimeInMillis();
  }

  public static Date toGmtDate(Date date) {
    TimeZone myZone = TimeZone.getDefault();
    Date gmtDate = new Date(date.getTime() - myZone.getRawOffset());
    if (myZone.inDaylightTime(gmtDate)) {
      Date daylight = new Date(gmtDate.getTime() - myZone.getDSTSavings());
      if (myZone.inDaylightTime(daylight)) {
        gmtDate = daylight;
      }
    }
    return gmtDate;
  }

  public static long toGmtTime(long time) {
    TimeZone myZone = TimeZone.getDefault();
    Date gmtDate = new Date(time - myZone.getRawOffset());
    if (myZone.inDaylightTime(gmtDate)) {
      Date daylight = new Date(gmtDate.getTime() - myZone.getDSTSavings());
      if (myZone.inDaylightTime(daylight)) {
        gmtDate = daylight;
      }
    }
    return gmtDate.getTime();
  }
}
