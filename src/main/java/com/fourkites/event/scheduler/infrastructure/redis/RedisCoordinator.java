package com.fourkites.event.scheduler.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.config.properties.RedisTtlProperties;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.service.TimestampProcessingKafkaService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Component;

@Component
public class RedisCoordinator {
  private static final Logger log = LoggerFactory.getLogger(RedisCoordinator.class);
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
  private static final DateTimeFormatter MINUTE_FORMATTER =
      DateTimeFormatter.ofPattern("dd-MM-yy-HH-mm");

  private static final String TIMESTAMP_KEY_PREFIX = "trigger:time:";
  private static final String IMMINENT_EVENT_PREFIX = "imminent:event:";

  private final RedisTemplate<String, Object> redisTemplate;
  private final StringRedisTemplate stringRedisTemplate;
  private final ObjectMapper objectMapper;
  private final ChannelTopic eventsTopic;
  private final TimestampProcessingKafkaService timestampKafkaService;
  private final Clock clock;
  private final RedisTtlProperties ttlProperties;

  private volatile LocalDateTime lastOrchestratorRunTime;

  public RedisCoordinator(
      RedisTemplate<String, Object> redisTemplate,
      StringRedisTemplate stringRedisTemplate,
      ObjectMapper objectMapper,
      ChannelTopic eventsTopic,
      TimestampProcessingKafkaService timestampKafkaService,
      Clock clock,
      RedisTtlProperties ttlProperties) {
    this.redisTemplate = redisTemplate;
    this.stringRedisTemplate = stringRedisTemplate;
    this.objectMapper = objectMapper;
    this.eventsTopic = eventsTopic;
    this.timestampKafkaService = timestampKafkaService;
    this.clock = clock;
    this.ttlProperties = ttlProperties;
  }

  public boolean scheduleTimestampForProcessing(LocalDateTime timestamp) {
    LocalDateTime minuteLevel = timestamp.truncatedTo(ChronoUnit.MINUTES);
    LocalDateTime eventSecond = timestamp.truncatedTo(ChronoUnit.SECONDS);
    String key = TIMESTAMP_KEY_PREFIX + minuteLevel.format(MINUTE_FORMATTER);

    LocalDateTime now = LocalDateTime.now(clock);
    long desiredTtlSeconds = ChronoUnit.SECONDS.between(now, eventSecond);
    if (desiredTtlSeconds <= 0) {
      log.warn(
          "Cannot schedule timestamp {} (event second {}) in the past or present, skipping",
          minuteLevel,
          eventSecond);
      return false;
    }

    Boolean exists = stringRedisTemplate.hasKey(key);
    if (Boolean.TRUE.equals(exists)) {
      Long currentTtlSeconds =
          stringRedisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
      long current = (currentTtlSeconds == null || currentTtlSeconds < 0) ? 0 : currentTtlSeconds;
      if (desiredTtlSeconds > current) {
        boolean updated =
            Boolean.TRUE.equals(
                stringRedisTemplate.expire(
                    key, desiredTtlSeconds, java.util.concurrent.TimeUnit.SECONDS));
        if (updated) {
          log.info(
              "Extended TTL for key {} to {} seconds (eventSecond: {})",
              key,
              desiredTtlSeconds,
              eventSecond);
        } else {
          log.warn("Failed to extend TTL for key {} to {} seconds", key, desiredTtlSeconds);
        }
      } else {
        log.debug(
            "Existing TTL for key {} ({}s) is >= desired ({}s); no change",
            key,
            current,
            desiredTtlSeconds);
      }
      return false;
    }

    stringRedisTemplate.opsForValue().set(key, minuteLevel.toString());
    boolean expirationSet =
        Boolean.TRUE.equals(
            stringRedisTemplate.expire(
                key, desiredTtlSeconds, java.util.concurrent.TimeUnit.SECONDS));
    if (expirationSet) {
      log.info(
          "Scheduled timestamp {} (minute-level: {} -> {}) with TTL of {} seconds (eventSecond: {})",
          timestamp,
          minuteLevel,
          minuteLevel.format(MINUTE_FORMATTER),
          desiredTtlSeconds,
          eventSecond);
    } else {
      log.warn("Failed to set TTL for key {} to {} seconds", key, desiredTtlSeconds);
    }
    return true;
  }

  public void updateOrchestratorRunTime(LocalDateTime runTime) {
    this.lastOrchestratorRunTime = runTime;
    log.debug("Updated orchestrator run time to: {}", runTime);
  }

  public boolean scheduleTimestampIfWithinHour(LocalDateTime timestamp) {
    LocalDateTime now = LocalDateTime.now(clock);
    if (lastOrchestratorRunTime == null) {
      LocalDateTime currentHour = now.truncatedTo(ChronoUnit.HOURS);
      LocalDateTime nextHour = currentHour.plusHours(1);
      if (timestamp.isAfter(now) && timestamp.isBefore(nextHour.plusHours(1))) {
        log.debug(
            "No orchestrator run time available, scheduling timestamp {} conservatively",
            timestamp);
        return scheduleTimestampForProcessing(timestamp);
      }
      return false;
    }
    LocalDateTime orchestratorWindow = lastOrchestratorRunTime.plusHours(1);
    LocalDateTime nextOrchestratorRun = orchestratorWindow.plusHours(1);
    if (timestamp.isBefore(now) || timestamp.isAfter(nextOrchestratorRun)) {
      log.debug(
          "Timestamp {} is outside orchestrator window ({} to {}), not scheduling dynamically",
          timestamp,
          orchestratorWindow,
          nextOrchestratorRun);
      return false;
    }
    long minutesUntilNextOrchestrator = ChronoUnit.MINUTES.between(now, nextOrchestratorRun);
    if (minutesUntilNextOrchestrator <= 5) {
      log.debug(
          "Timestamp {} is too close to next orchestrator run (in {} minutes), letting orchestrator handle it",
          timestamp,
          minutesUntilNextOrchestrator);
      return false;
    }
    return scheduleTimestampForProcessing(timestamp);
  }

  public void storeImminentEventIfNeeded(Event event) {
    if (event == null || event.getNextRunTime() == null) {
      return;
    }
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime eventTime = event.getNextRunTime();
    if (eventTime.isAfter(now.plusHours(1))) {
      log.debug("Event {} not imminent, skipping Redis storage", event.getEventId());
      return;
    }
    long ttlSeconds = ChronoUnit.SECONDS.between(now, eventTime);
    if (ttlSeconds <= 0) {
      log.info("Event {} is due now, publishing for immediate processing", event.getEventId());
      publishEventForProcessing(event);
      return;
    }
    try {
      String redisKey = IMMINENT_EVENT_PREFIX + event.getEventId();
      String eventJson = objectMapper.writeValueAsString(event);
      redisTemplate.opsForValue().set(redisKey, eventJson, Duration.ofSeconds(ttlSeconds));
      log.info(
          "Stored imminent event {} in Redis with TTL {} seconds", event.getEventId(), ttlSeconds);
    } catch (Exception e) {
      log.error("Failed to store imminent event {} in Redis", event.getEventId(), e);
    }
  }

  public void publishEventForProcessing(Event event) {
    try {
      String eventJson = objectMapper.writeValueAsString(event);
      redisTemplate.convertAndSend(eventsTopic.getTopic(), eventJson);
      log.info("Published event {} for immediate processing", event.getEventId());
    } catch (Exception e) {
      log.error("Failed to publish event {} for processing", event.getEventId(), e);
    }
  }

  public void removeImminentEvent(Event event) {
    if (event == null || event.getEventId() == null) {
      return;
    }
    String redisKey = IMMINENT_EVENT_PREFIX + event.getEventId();
    Boolean deleted = redisTemplate.delete(redisKey);
    if (Boolean.TRUE.equals(deleted)) {
      log.debug("Removed imminent event {} from Redis", event.getEventId());
    }
  }

  public void handleExpiredKey(String expiredKey) {
    if (expiredKey.startsWith(TIMESTAMP_KEY_PREFIX)) {
      handleExpiredTimestampKey(expiredKey);
    } else if (expiredKey.startsWith(IMMINENT_EVENT_PREFIX)) {
      handleExpiredImminentEventKey(expiredKey);
    } else {
      log.debug("Ignoring expired key with unknown prefix: {}", expiredKey);
    }
  }

  private void handleExpiredTimestampKey(String expiredKey) {
    String timestampStr = expiredKey.substring(TIMESTAMP_KEY_PREFIX.length());
    try {
      LocalDateTime triggerTime = LocalDateTime.parse(timestampStr, MINUTE_FORMATTER);
      log.info(
          "Timestamp key expired: {} ({}), triggering batch processing via Kafka",
          timestampStr,
          triggerTime);
      timestampKafkaService.publishTimestampExpiry(triggerTime);
    } catch (Exception e) {
      log.error("Failed to parse timestamp from expired key: {}", expiredKey, e);
    }
  }

  private void handleExpiredImminentEventKey(String expiredKey) {
    String eventId = expiredKey.substring(IMMINENT_EVENT_PREFIX.length());
    log.info("Imminent event key expired: {}, triggering processing", eventId);
    try {
      String notificationMessage =
          String.format("{\"type\":\"expired_event\",\"eventId\":\"%s\"}", eventId);
      redisTemplate.convertAndSend(eventsTopic.getTopic(), notificationMessage);
    } catch (Exception e) {
      log.error("Failed to handle expired imminent event {}", eventId, e);
    }
  }

  public long getImminentEventCount() {
    try {
      Set<String> keys = redisTemplate.keys(IMMINENT_EVENT_PREFIX + "*");
      return keys != null ? keys.size() : 0;
    } catch (Exception e) {
      log.error("Failed to get imminent event count", e);
      return 0;
    }
  }

  public long getTimestampKeyCount() {
    try {
      Set<String> keys = stringRedisTemplate.keys(TIMESTAMP_KEY_PREFIX + "*");
      return keys != null ? keys.size() : 0;
    } catch (Exception e) {
      log.error("Failed to get timestamp key count", e);
      return 0;
    }
  }
}
