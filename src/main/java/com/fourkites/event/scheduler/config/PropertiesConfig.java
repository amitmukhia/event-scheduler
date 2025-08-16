package com.fourkites.event.scheduler.config;

import com.fourkites.event.scheduler.config.properties.CleanupAuditProperties;
import com.fourkites.event.scheduler.config.properties.CleanupRateLimitingProperties;
import com.fourkites.event.scheduler.config.properties.CustomerProperties;
import com.fourkites.event.scheduler.config.properties.DbProperties;
import com.fourkites.event.scheduler.config.properties.RedisTtlProperties;
import com.fourkites.event.scheduler.config.properties.WatchdogProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
  RedisTtlProperties.class,
  DbProperties.class,
  WatchdogProperties.class,
  CustomerProperties.class,
  CleanupAuditProperties.class,
  CleanupRateLimitingProperties.class
})
public class PropertiesConfig { }
