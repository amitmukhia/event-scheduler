package com.fourkites.event.scheduler.jobs;

import com.fourkites.event.scheduler.config.properties.RedisTtlProperties;
import com.fourkites.event.scheduler.infrastructure.redis.RedisCoordinator;
import com.fourkites.event.scheduler.repository.EventRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Highly Scalable Redis-based Orchestrator for 1000+ events/minute: - Runs every hour to create
 * Redis TTL keys for next hour's unique timestamps - Uses Redis keyspace notifications for TTL
 * expiry triggering - Publishes expired timestamps to Kafka for distributed batch processing -
 * Supports max 60 Redis keys per hour (minute-level granularity)
 */
@Component
public class Orchestrator {
  private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

  private final RedisCoordinator eventRedisService;
  private final EventRepository eventRepository;
  private final Clock clock;
  private final RedisTtlProperties redisTtlProperties;

  public Orchestrator(
      RedisCoordinator eventRedisService,
      EventRepository eventRepository,
      Clock clock,
      RedisTtlProperties redisTtlProperties) {
    this.eventRedisService = eventRedisService;
    this.eventRepository = eventRepository;
    this.clock = clock;
    this.redisTtlProperties = redisTtlProperties;
  }

  /**
   * Highly scalable Redis TTL-based orchestration - runs every hour Creates Redis keys with TTL
   * expiry for next hour's unique event timestamps Supports 1000+ events/minute with max 60 Redis
   * keys per hour
   */
  @Scheduled(cron = "0 0 * * * *") // Run at the start of every hour
  public void scheduleNextHourTimestamps() {
    try {
      LocalDateTime orchestratorRunTime = LocalDateTime.now(clock);

      // Update the Redis service with our actual run time
      eventRedisService.updateOrchestratorRunTime(orchestratorRunTime);

      LocalDateTime currentHour = orchestratorRunTime.truncatedTo(ChronoUnit.HOURS);
      LocalDateTime nextHour = currentHour.plusHours(1);
      LocalDateTime endTime = nextHour.plusHours(redisTtlProperties.getLookAheadHours());

      log.info(
          "Starting Redis TTL orchestration at {} for time window: {} to {}",
          orchestratorRunTime,
          nextHour,
          endTime);

      // Step 1: Fetch all unique timestamps for events in the next hour
      Set<LocalDateTime> uniqueTimestamps =
          eventRepository.findUniqueTimestampsInRange(nextHour, endTime);

      if (uniqueTimestamps.isEmpty()) {
        log.info("No events scheduled for time window: {} to {}", nextHour, endTime);
        return;
      }

      log.info("Found {} unique timestamps to schedule in Redis TTL keys", uniqueTimestamps.size());

      // Step 2: Create Redis TTL keys for each unique timestamp
      int scheduledCount = 0;
      for (LocalDateTime timestamp : uniqueTimestamps) {
        try {
          boolean scheduled = eventRedisService.scheduleTimestampForProcessing(timestamp);
          if (scheduled) {
            scheduledCount++;
          }
        } catch (Exception e) {
          log.error("Failed to schedule timestamp {} in Redis: {}", timestamp, e.getMessage());
        }
      }

      log.info(
          "Redis TTL orchestration completed: {}/{} timestamps scheduled for processing",
          scheduledCount,
          uniqueTimestamps.size());

    } catch (Exception e) {
      log.error("Error during Redis TTL orchestration", e);
    }
  }
}
