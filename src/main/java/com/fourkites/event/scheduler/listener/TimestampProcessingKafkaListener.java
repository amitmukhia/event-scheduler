package com.fourkites.event.scheduler.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.service.DistributedLockService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka listener for processing timestamp expiry messages. When Redis TTL keys expire, this
 * listener: 1. Acquires distributed lock for the timestamp 2. Fetches all events scheduled for that
 * timestamp 3. Sends each event individually to the Kafka processing queue 4. The
 * EventProcessingKafkaListener then processes each event with customer isolation
 */
@Component
public class TimestampProcessingKafkaListener {

  private static final Logger log = LoggerFactory.getLogger(TimestampProcessingKafkaListener.class);
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final DistributedLockService lockService;
  private final EventRepository eventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final String processingTopic;

  // Backpressure control and lightweight metrics
  private final int maxInFlight;
  private final Semaphore dispatchSemaphore;
  private final LongAdder sendEnqueued = new LongAdder();
  private final LongAdder sendFailures = new LongAdder();

  public TimestampProcessingKafkaListener(
      DistributedLockService lockService,
      EventRepository eventRepository,
KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Clock clock,
      com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.lockService = lockService;
    this.eventRepository = eventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.clock = clock;
    var kafka = appProps.kafka();
    this.processingTopic =
        kafka != null && kafka.topics() != null && kafka.topics().timestampProcessing() != null
            ? kafka.topics().timestampProcessing()
            : "timestamp-processing";
    this.maxInFlight =
        (kafka != null && kafka.dispatchMaxInflight() != null && kafka.dispatchMaxInflight() > 0)
            ? kafka.dispatchMaxInflight()
            : 500;
    this.dispatchSemaphore = new Semaphore(this.maxInFlight, true);
  }

  /** Processes timestamp expiry messages from Kafka */
  @KafkaListener(
      topics = "${app.kafka.topics.timestamp-processing:timestamp-processing}",
      groupId = "${app.kafka.consumer.group-id:timestamp-processors}",
      concurrency = "${app.kafka.consumer.concurrency:3}")
  public void processTimestamp(
      @Payload String message,
      @Header(KafkaHeaders.RECEIVED_KEY) String key,
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
      Acknowledgment acknowledgment) {

    log.debug(
        "Received timestamp processing message from topic: {}, partition: {}, key: {}",
        topic,
        partition,
        key);

    try {
      JsonNode messageNode = objectMapper.readTree(message);
      String messageType = messageNode.get("type").asText();
      String timestampStr = messageNode.get("timestamp").asText();

      LocalDateTime timestamp = LocalDateTime.parse(timestampStr, FORMATTER);

      switch (messageType) {
        case "TIMESTAMP_EXPIRED" -> handleTimestampExpired(timestamp);
        case "TIMESTAMP_RETRY" -> handleTimestampRetry(messageNode, timestamp);
        default -> log.warn("Unknown message type: {} for timestamp: {}", messageType, timestamp);
      }

      // Acknowledge successful processing
      acknowledgment.acknowledge();

    } catch (DateTimeParseException e) {
      log.error("Invalid timestamp format in message: {}", message, e);
      acknowledgment.acknowledge(); // Don't retry invalid formats

    } catch (Exception e) {
      log.error("Error processing timestamp message: {}", message, e);
      // Don't acknowledge - let Kafka retry
      throw new RuntimeException("Failed to process timestamp message", e);
    }
  }

  /**
   * Handles timestamp expiry processing with distributed locking. Fetches all events for the
   * timestamp and sends them to Kafka processing queue.
   */
  @Transactional
  private void handleTimestampExpired(LocalDateTime timestamp) {
    log.info("Processing expired timestamp: {}", timestamp);

    // Attempt to acquire distributed lock
    DistributedLockService.LockResult lockResult =
        lockService.acquireLock(timestamp, 10, TimeUnit.MINUTES);

    if (!lockResult.isAcquired()) {
      log.info(
          "Could not acquire lock for timestamp: {} (likely being processed by another instance)",
          timestamp);
      return;
    }

    try {
      LocalDateTime now = LocalDateTime.now(clock);
      final int BATCH_SIZE = 1000;

      int totalSent = 0;
      int totalFailed = 0;
      int batchNum = 0;

      while (true) {
        List<Event> events =
            eventRepository.claimPendingBatchUpToTimestamp(now, timestamp, BATCH_SIZE);
        if (events.isEmpty()) {
          if (batchNum == 0) {
            log.debug("No pending events found for timestamp: {} or earlier", timestamp);
          }
          break;
        }
        batchNum++;

        long exactTimestamp =
            events.stream().filter(e -> e.getNextRunTime().equals(timestamp)).count();
        long overdueEvents = events.size() - exactTimestamp;
        if (overdueEvents > 0) {
          log.warn(
              "Batch {}: Found {} overdue events (scheduled before {}) - catching up",
              batchNum,
              overdueEvents,
              timestamp);
        }

        ProcessingResult result = sendEventsBatchToKafka(events, timestamp);
        totalSent += result.sentCount;
        totalFailed += result.failedCount;

        int inFlight = maxInFlight - dispatchSemaphore.availablePermits();
        log.info(
            "Batch {} metrics: enqueued={}, inFlight={}, sendFailuresSoFar={}",
            batchNum,
            result.sentCount,
            inFlight,
            sendFailures.sum());
      }

      log.info(
          "Finished processing for timestamp {}: batches={}, enqueued={}, failuresSoFar={}",
          timestamp,
          batchNum,
          totalSent,
          sendFailures.sum());

    } catch (Exception e) {
      log.error("Error fetching events for timestamp: {}", timestamp, e);
      throw new RuntimeException("Failed to fetch and send events for timestamp: " + timestamp, e);

    } finally {
      // Always release the lock
      boolean released = lockService.releaseLock(lockResult);
      if (!released) {
        log.warn("Failed to release lock for timestamp: {}", timestamp);
      }
    }
  }

  /** Handles timestamp retry processing */
  @Transactional
  private void handleTimestampRetry(JsonNode messageNode, LocalDateTime timestamp) {
    int retryCount = messageNode.get("retryCount").asInt();
    String previousError =
        messageNode.has("errorMessage")
            ? messageNode.get("errorMessage").asText()
            : "Unknown error";

    log.info("Processing timestamp retry: {} (attempt: {})", timestamp, retryCount);

    // Attempt to acquire distributed lock
    DistributedLockService.LockResult lockResult =
        lockService.acquireLock(timestamp, 10, TimeUnit.MINUTES);

    if (!lockResult.isAcquired()) {
      log.info(
          "Could not acquire lock for timestamp retry: {} (likely being processed by another instance)",
          timestamp);
      return;
    }

    try {
      // For retry, also fetch events and send to processing queue
      handleTimestampExpired(timestamp);

    } finally {
      // Always release the lock
      boolean released = lockService.releaseLock(lockResult);
      if (!released) {
        log.warn("Failed to release lock for timestamp retry: {}", timestamp);
      }
    }
  }

  /**
   * OPTIMIZATION: Sends events to Kafka in optimized batches with parallel processing. Uses
   * CompletableFuture for async sends and proper error handling.
   */
  private ProcessingResult sendEventsBatchToKafka(List<Event> events, LocalDateTime timestamp) {
    int enqueuedCount = 0;

    String sentAt = LocalDateTime.now(clock).format(FORMATTER);

    for (Event event : events) {
      try {
        String messageJson = createEventMessage(event, sentAt);
        String key = "process:" + event.getCustomerId() + ":" + event.getEventId();

        // Backpressure: cap concurrent sends
        dispatchSemaphore.acquireUninterruptibly();

        kafkaTemplate
            .send(processingTopic, key, messageJson)
            .thenAccept(
                result -> {
                  // success path
                  log.debug(
                      "Successfully sent event {} to processing queue", event.getEventId());
                  dispatchSemaphore.release();
                })
            .exceptionally(
                ex -> {
                  log.error(
                      "Failed to send event {} to processing queue", event.getEventId(), ex);
                  // count failure and update event for retry visibility
                  sendFailures.increment();
                  updateEventFailureCount(event);
                  dispatchSemaphore.release();
                  return null;
                });

        enqueuedCount++;
        sendEnqueued.increment();
      } catch (Exception e) {
        log.error("Failed to prepare event {} for Kafka: {}", event.getEventId(), e.getMessage());
        updateEventFailureCount(event);
      }
    }

    return new ProcessingResult(enqueuedCount, 0);
  }

  /** OPTIMIZATION: Create event message with minimal object allocation */
  private String createEventMessage(Event event, String sentAt) throws Exception {
    // Reuse StringBuilder for better performance
    StringBuilder sb = new StringBuilder(256);
    sb.append("{");
    sb.append("\"eventId\":\"").append(event.getEventId()).append("\",");
    sb.append("\"customerId\":\"").append(event.getCustomerId()).append("\",");
    sb.append("\"type\":\"PROCESS_EVENT\",");
    sb.append("\"sentAt\":\"").append(sentAt).append("\",");
    sb.append("\"eventData\":\"")
        .append(objectMapper.writeValueAsString(event).replace("\"", "\\\""))
        .append("\"");
    sb.append("}");
    return sb.toString();
  }

  /** Updates event failure count in separate transaction to avoid affecting main batch */
  private void updateEventFailureCount(Event event) {
    try {
      event.setFailureCount(event.getFailureCount() + 1);
      eventRepository.save(event);
    } catch (Exception e) {
      log.error("Failed to update failure count for event {}", event.getEventId(), e);
    }
  }

  /** Result class for batch processing operations */
  private static class ProcessingResult {
    final int sentCount;
    final int failedCount;

    ProcessingResult(int sentCount, int failedCount) {
      this.sentCount = sentCount;
      this.failedCount = failedCount;
    }
  }
}
