package com.fourkites.event.scheduler.config.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
public class RedisOrchestrationHealthIndicator implements HealthIndicator {
  private final RedisConnectionFactory connectionFactory;

  public RedisOrchestrationHealthIndicator(RedisConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  @Override
  public Health health() {
    try (var conn = connectionFactory.getConnection()) {
      String pong = conn.ping();
      if (pong != null && !pong.isBlank()) {
        return Health.up().withDetail("redis", "reachable").build();
      }
      return Health.down().withDetail("redis", "no-pong").build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
