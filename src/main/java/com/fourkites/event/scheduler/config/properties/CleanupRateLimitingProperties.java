package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.cleanup.rate-limiting")
public class CleanupRateLimitingProperties {
  private TokenBucket history;
  private SlidingWindow historyWindow;
  private TokenBucket database;
  private SlidingWindow databaseWindow;

  public static class TokenBucket {
    private int capacity;
    private int refillRate;

    public int getCapacity() {
      return capacity;
    }

    public void setCapacity(int v) {
      this.capacity = v;
    }

    public int getRefillRate() {
      return refillRate;
    }

    public void setRefillRate(int v) {
      this.refillRate = v;
    }
  }

  public static class SlidingWindow {
    private int windowMinutes;
    private int maxOperations;

    public int getWindowMinutes() {
      return windowMinutes;
    }

    public void setWindowMinutes(int v) {
      this.windowMinutes = v;
    }

    public int getMaxOperations() {
      return maxOperations;
    }

    public void setMaxOperations(int v) {
      this.maxOperations = v;
    }
  }

  public TokenBucket getHistory() {
    return history;
  }

  public void setHistory(TokenBucket v) {
    this.history = v;
  }

  public SlidingWindow getHistoryWindow() {
    return historyWindow;
  }

  public void setHistoryWindow(SlidingWindow v) {
    this.historyWindow = v;
  }

  public TokenBucket getDatabase() {
    return database;
  }

  public void setDatabase(TokenBucket v) {
    this.database = v;
  }

  public SlidingWindow getDatabaseWindow() {
    return databaseWindow;
  }

  public void setDatabaseWindow(SlidingWindow v) {
    this.databaseWindow = v;
  }
}
