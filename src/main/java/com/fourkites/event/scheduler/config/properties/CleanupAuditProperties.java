package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.cleanup.audit")
public class CleanupAuditProperties {
  private boolean enabled;
  private String schedule;
  private int batchSize;
  private int batchDelayMs;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean v) {
    this.enabled = v;
  }

  public String getSchedule() {
    return schedule;
  }

  public void setSchedule(String schedule) {
    this.schedule = schedule;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int v) {
    this.batchSize = v;
  }

  public int getBatchDelayMs() {
    return batchDelayMs;
  }

  public void setBatchDelayMs(int v) {
    this.batchDelayMs = v;
  }
}
