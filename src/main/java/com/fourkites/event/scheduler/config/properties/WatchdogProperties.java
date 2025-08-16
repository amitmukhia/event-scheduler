package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.watchdog")
public class WatchdogProperties {
  private int stuckEventThresholdMinutes;
  private int checkIntervalMs;
  private int failedEventCheckIntervalMs;
  private int batchSize;
  private boolean enableCustomerIsolation;
  private boolean enableHealthMonitoring;

  public int getStuckEventThresholdMinutes() {
    return stuckEventThresholdMinutes;
  }

  public void setStuckEventThresholdMinutes(int v) {
    this.stuckEventThresholdMinutes = v;
  }

  public int getCheckIntervalMs() {
    return checkIntervalMs;
  }

  public void setCheckIntervalMs(int v) {
    this.checkIntervalMs = v;
  }

  public int getFailedEventCheckIntervalMs() {
    return failedEventCheckIntervalMs;
  }

  public void setFailedEventCheckIntervalMs(int v) {
    this.failedEventCheckIntervalMs = v;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int v) {
    this.batchSize = v;
  }

  public boolean isEnableCustomerIsolation() {
    return enableCustomerIsolation;
  }

  public void setEnableCustomerIsolation(boolean v) {
    this.enableCustomerIsolation = v;
  }

  public boolean isEnableHealthMonitoring() {
    return enableHealthMonitoring;
  }

  public void setEnableHealthMonitoring(boolean v) {
    this.enableHealthMonitoring = v;
  }
}
