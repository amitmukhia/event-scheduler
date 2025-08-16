package com.fourkites.event.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.model.Event;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

/**
 * Fast Kafka service for managing timestamp processing queue. Optimized for high throughput (1000+
 * events/min) with batch publishing.
 */
@Service
public class TimestampProcessingKafkaService {

  private static final Logger log = LoggerFactory.getLogger(TimestampProcessingKafkaService.class);
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  private final String timestampProcessingTopic;

  public TimestampProcessingKafkaService(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Clock clock,
      com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
    var topics = appProps.kafka() != null ? appProps.kafka().topics() : null;
    this.timestampProcessingTopic =
        topics != null && topics.timestampProcessing() != null
            ? topics.timestampProcessing()
            : "timestamp-processing";
  }

  /**
   * Fast method: Publishes just the timestamp expiry to Kafka queue. The consumer will fetch events
   * from DB in batches for maximum efficiency.
   */
  public void publishTimestampExpiry(LocalDateTime timestamp) {
    try {
      Map<String, Object> message = new HashMap<>();
      message.put("type", "TIMESTAMP_EXPIRED");
      message.put("timestamp", timestamp.format(FORMATTER));
      message.put("publishedAt", LocalDateTime.now(clock).format(FORMATTER));

      String messageJson = objectMapper.writeValueAsString(message);
      String key = "timestamp:" + timestamp.format(FORMATTER);

      // Send async with callback for monitoring
      CompletableFuture<SendResult<String, String>> future =
          kafkaTemplate.send(timestampProcessingTopic, key, messageJson);

      future.whenComplete(
          (result, ex) -> {
            if (ex == null) {
              log.info("Successfully published timestamp expiry {} to Kafka", timestamp);
            } else {
              log.error("Failed to publish timestamp expiry {} to Kafka", timestamp, ex);
            }
          });

    } catch (Exception e) {
      log.error("Error publishing timestamp expiry to Kafka", e);
    }
  }

  /** Publishes events to Kafka for async processing */
  public void publishEventsForProcessing(List<Event> events, LocalDateTime timestamp) {
    try {
      for (Event event : events) {
        Map<String, Object> message = new HashMap<>();
        message.put("eventId", event.getEventId());
        message.put("timestamp", timestamp.format(FORMATTER));
        message.put("type", "EVENT");
        message.put("publishedAt", LocalDateTime.now(clock).format(FORMATTER));
        message.put("eventData", objectMapper.writeValueAsString(event));

        String messageJson = objectMapper.writeValueAsString(message);
        String key = "event:" + event.getEventId();

        CompletableFuture<SendResult<String, String>> future =
            kafkaTemplate.send(timestampProcessingTopic, key, messageJson);

        future.whenComplete(
            (result, ex) -> {
              if (ex == null) {
                log.debug(
                    "Successfully published event {} to Kafka topic {}",
                    event.getEventId(),
                    timestampProcessingTopic);
              } else {
                log.error(
                    "Failed to publish event {} to Kafka topic {}",
                    event.getEventId(),
                    timestampProcessingTopic,
                    ex);
                // Could implement retry logic or dead letter queue here
              }
            });
      }

    } catch (Exception e) {
      log.error("Error publishing events to Kafka", e);
    }
  }
}
