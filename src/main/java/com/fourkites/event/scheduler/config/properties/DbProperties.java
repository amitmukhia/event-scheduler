package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.db")
public class DbProperties {
  private int maxRetries;
  private int retryBaseDelayMs;

  public int getMaxRetries() {
    return maxRetries;
  }

  public void setMaxRetries(int maxRetries) {
    this.maxRetries = maxRetries;
  }

  public int getRetryBaseDelayMs() {
    return retryBaseDelayMs;
  }

  public void setRetryBaseDelayMs(int retryBaseDelayMs) {
    this.retryBaseDelayMs = retryBaseDelayMs;
  }
}
