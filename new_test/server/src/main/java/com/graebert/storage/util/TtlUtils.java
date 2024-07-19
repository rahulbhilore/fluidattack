package com.graebert.storage.util;

import java.util.concurrent.TimeUnit;

public class TtlUtils {
  public static long now() {
    return System.currentTimeMillis() / 1000;
  }

  public static long inCustomTime(TimeUnit timeUnit, int duration) {
    return now() + timeUnit.toSeconds(duration);
  }

  public static long inOneHour() {
    return inCustomTime(TimeUnit.HOURS, 1);
  }

  public static long inOneDay() {
    return inCustomTime(TimeUnit.DAYS, 1);
  }

  public static long inOneWeek() {
    return inCustomTime(TimeUnit.DAYS, 7);
  }

  public static long inOneMonth() {
    return inCustomTime(TimeUnit.DAYS, 30);
  }

  public static long inNHours(int duration) {
    return inCustomTime(TimeUnit.HOURS, duration);
  }

  public static long inNDays(int duration) {
    return inCustomTime(TimeUnit.HOURS, duration);
  }
}
