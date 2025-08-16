package com.fourkites.event.scheduler.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "event-scheduler.redis-ttl")
public class RedisTtlProperties {
  private int lookAheadHours;
  private int maxKeysPerHour;

  public int getLookAheadHours() {
    return lookAheadHours;
  }

  public void setLookAheadHours(int lookAheadHours) {
    this.lookAheadHours = lookAheadHours;
  }

  public int getMaxKeysPerHour() {
    return maxKeysPerHour;
  }

  public void setMaxKeysPerHour(int maxKeysPerHour) {
    this.maxKeysPerHour = maxKeysPerHour;
  }
}
