package com.fourkites.event.scheduler.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dispatcher.Dispatcher;
import com.fourkites.event.scheduler.infrastructure.redis.RedisCoordinator;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.service.TimestampProcessingKafkaService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Unified Redis message listener that handles: 1. TTL expired keys (keyspace notifications) →
 * Delegates to RedisCoordinator 2. Direct event processing messages → Processes immediately
 *
 * <p>This is the single Redis listener for the entire application.
 */
@Component
public class RedisEventListener implements MessageListener {

  private static final Logger log = LoggerFactory.getLogger(RedisEventListener.class);
  private static final DateTimeFormatter MINUTE_FORMATTER =
      DateTimeFormatter.ofPattern("dd-MM-yy-HH-mm");

  private final EventRepository eventRepository;
  private final Dispatcher dispatcher;
  private final RedisCoordinator eventRedisService;
  private final TimestampProcessingKafkaService timestampKafkaService;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public RedisEventListener(
      EventRepository eventRepository,
      Dispatcher dispatcher,
      RedisCoordinator eventRedisService,
      TimestampProcessingKafkaService timestampKafkaService,
      ObjectMapper objectMapper,
      Clock clock) {
    this.eventRepository = eventRepository;
    this.dispatcher = dispatcher;
    this.eventRedisService = eventRedisService;
    this.timestampKafkaService = timestampKafkaService;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public void onMessage(Message message, byte[] pattern) {
    try {
      String channel = new String(message.getChannel());
      String body = new String(message.getBody());

      log.debug("Received Redis message on channel: {} with body: {}", channel, body);

      if (channel.contains("expired")) {
        // Handle TTL expired key
        handleExpiredKey(body);
      } else if (channel.equals("events")) {
        // Handle direct event processing message
        handleEventMessage(body);
      }

    } catch (Exception e) {
      log.error("Error processing Redis message", e);
    }
  }

  /**
   * Handles TTL expired keys from Redis keyspace notifications Delegates to RedisCoordinator for
   * unified handling
   */
  private void handleExpiredKey(String expiredKey) {
    log.info("Handling expired Redis key: {}", expiredKey);

    // Delegate to the unified Redis service
    eventRedisService.handleExpiredKey(expiredKey);

    // For timestamp keys, also trigger Kafka processing
    if (expiredKey.startsWith("trigger:time:")) {
      String timestampStr = expiredKey.substring("trigger:time:".length());

      try {
        // Parse using minute format (DD-MM-YY-HH-MM)
        LocalDateTime triggerTime = LocalDateTime.parse(timestampStr, MINUTE_FORMATTER);
        log.info(
            "Timestamp expired: {} ({}), publishing to Kafka queue for batch processing",
            timestampStr,
            triggerTime);

        // Publish to Kafka for batch processing
        timestampKafkaService.publishTimestampExpiry(triggerTime);

      } catch (Exception e) {
        log.error("Failed to publish timestamp expiry to Kafka: {}", timestampStr, e);
      }
    }
  }

  /** Handles direct event processing messages */
  private void handleEventMessage(String messageBody) {
    try {
      JsonNode messageNode = objectMapper.readTree(messageBody);

      if (messageNode.has("type") && "expired_event".equals(messageNode.get("type").asText())) {
        // Handle expired event notification
        String eventId = messageNode.get("eventId").asText();
        handleExpiredEventNotification(eventId);
      } else {
        // Handle full event JSON
        Event event = objectMapper.readValue(messageBody, Event.class);
        processEventImmediately(event);
      }

    } catch (Exception e) {
      log.error("Failed to process event message: {}", messageBody, e);
    }
  }

  /** Handles expired event notification by fetching and processing the event */
  private void handleExpiredEventNotification(String eventIdStr) {
    try {
      UUID eventId = UUID.fromString(eventIdStr);
      Optional<Event> eventOpt = eventRepository.findById(eventId);

      if (eventOpt.isPresent()) {
        Event event = eventOpt.get();

        if ("PENDING".equals(event.getStatus())) {
          log.info("Processing expired event from notification: {}", eventId);
          processEventImmediately(event);
        }
      }

    } catch (Exception e) {
      log.error("Failed to handle expired event notification: {}", eventIdStr, e);
    }
  }

  /** Processes an event immediately through the dispatcher */
  private void processEventImmediately(Event event) {
    try {
      // Update event status to processing
      event.setStatus("PROCESSING");
      eventRepository.save(event);

      // Dispatch the event
      dispatcher.send(event);

      // Update event status to completed
      // Note: Recurring events are now handled in EventProcessingKafkaListener
      event.setStatus("COMPLETED");

      eventRepository.save(event);

      // Remove from Redis imminent events
      eventRedisService.removeImminentEvent(event);

      log.info("Successfully processed imminent event: {}", event.getEventId());

    } catch (Exception e) {
      log.error("Failed to process imminent event: {}", event.getEventId(), e);

      // Update failure count and reset status
      event.setFailureCount(event.getFailureCount() + 1);
      event.setStatus("PENDING"); // Reset for retry
      eventRepository.save(event);
    }
  }
}
