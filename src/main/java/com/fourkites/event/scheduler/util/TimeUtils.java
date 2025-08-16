package com.fourkites.event.scheduler.util;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

public final class TimeUtils {
  private TimeUtils() { }

  /**
   * Ceil a timestamp to the next minute boundary with seconds=0. Examples: - 12:49:34 -> 12:50:00 -
   * 12:49:00 -> 12:49:00
   */
  public static LocalDateTime ceilToMinute(LocalDateTime t) {
    if (t == null) {
      return null;
    }
    LocalDateTime truncated = t.truncatedTo(ChronoUnit.MINUTES);
    if (t.getSecond() > 0 || t.getNano() > 0) {
      return truncated.plusMinutes(1);
    }
    return truncated;
  }
}
