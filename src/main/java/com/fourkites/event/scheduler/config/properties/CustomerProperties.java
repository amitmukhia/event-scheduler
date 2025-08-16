package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.customer")
public class CustomerProperties {
  private int maxParallelProcessing;
  private int processingTimeoutMinutes;
  private boolean enablePerCustomerMetrics;
  private boolean batchGroupingEnabled;

  public int getMaxParallelProcessing() {
    return maxParallelProcessing;
  }

  public void setMaxParallelProcessing(int v) {
    this.maxParallelProcessing = v;
  }

  public int getProcessingTimeoutMinutes() {
    return processingTimeoutMinutes;
  }

  public void setProcessingTimeoutMinutes(int v) {
    this.processingTimeoutMinutes = v;
  }

  public boolean isEnablePerCustomerMetrics() {
    return enablePerCustomerMetrics;
  }

  public void setEnablePerCustomerMetrics(boolean v) {
    this.enablePerCustomerMetrics = v;
  }

  public boolean isBatchGroupingEnabled() {
    return batchGroupingEnabled;
  }

  public void setBatchGroupingEnabled(boolean v) {
    this.batchGroupingEnabled = v;
  }
}
