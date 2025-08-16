package com.fourkites.event.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dispatcher.Dispatcher;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simplified, scalable batch processing service that implements the new architecture: 1. Claims
 * batches using optimized SQL with RETURNING 2. Batch processing with efficient dispatch 3. Status
 * updates (one-time → COMPLETED, recurring → next_run_time) Designed to handle 1000+ events per
 * minute with distributed processing.
 */
@Service
public class BatchEventProcessingService {
  private static final Logger log = LoggerFactory.getLogger(BatchEventProcessingService.class);
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final EventRepository eventRepository;
  private final Dispatcher dispatcher;
  private final EventService eventService;
  private final DatabaseErrorHandlingService dbErrorHandler;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;
  private final com.fourkites.event.scheduler.repository.FailedEventRepository
      failedEventRepository;
  private final Clock clock;

  @Value("${event-scheduler.batch-size:500}")
  private int batchSize;

  @Value("${event-scheduler.stuck-event-threshold-minutes:15}")
  private int stuckEventThresholdMinutes;

  @Value("${kafka.topics.processing:process-kafka-message}")
  private String processingTopic;

  public BatchEventProcessingService(
      EventRepository eventRepository,
      Dispatcher dispatcher,
      EventService eventService,
      DatabaseErrorHandlingService dbErrorHandler,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate,
      com.fourkites.event.scheduler.repository.FailedEventRepository failedEventRepository,
      Clock clock) {
    this.eventRepository = eventRepository;
    this.dispatcher = dispatcher;
    this.eventService = eventService;
    this.dbErrorHandler = dbErrorHandler;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.failedEventRepository = failedEventRepository;
    this.clock = clock;
  }

  /**
   * Simplified batch processing method using RETURNING query. This is the core of the new
   * simplified architecture.
   *
   * <p>Claims batches and sends them to Kafka for individual processing. This avoids transaction
   * rollback issues when individual events fail.
   */
  @Transactional
  public ProcessingResult processBatchWithReturning() {
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime stuckThreshold = now.minusMinutes(stuckEventThresholdMinutes);

    try {
      // Step 1: Claim batch of events using optimized SQL with RETURNING
      List<Event> claimedEvents = eventRepository.claimBatch(now, batchSize, stuckThreshold);

      if (claimedEvents.isEmpty()) {
        log.debug("No events to process at {}", now);
        return new ProcessingResult(true, 0, 0, null);
      }

      log.info("Claimed {} events for processing using RETURNING query", claimedEvents.size());

      // Step 2: Send each event to Kafka for individual processing
      int sentCount = 0;
      int failedCount = 0;

      for (Event event : claimedEvents) {
        try {
          sendEventToProcessingQueue(event);
          sentCount++;
        } catch (Exception e) {
          log.error(
              "Failed to send event {} to processing queue: {}",
              event.getEventId(),
              e.getMessage());
          // Increment failure count and keep in PROCESSING status for retry
          event.setFailureCount(event.getFailureCount() + 1);
          eventRepository.save(event);
          failedCount++;
        }
      }

      log.info("Sent {} events to processing queue, {} failed to send", sentCount, failedCount);
      return new ProcessingResult(true, sentCount, failedCount, null);

    } catch (Exception e) {
      log.error("Batch processing with RETURNING failed", e);
      return new ProcessingResult(false, 0, 0, e.getMessage());
    }
  }

  /**
   * Claims a batch of events within a separate transaction. This isolates the claiming logic from
   * individual event processing.
   */
  @Transactional
  public List<Event> claimBatchInTransaction(
      LocalDateTime now, int batchSize, LocalDateTime stuckThreshold) {
    return eventRepository.claimBatch(now, batchSize, stuckThreshold);
  }

  /**
   * Processes a single event within its own transaction. This ensures that if one event fails, it
   * doesn't affect others in the batch.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ProcessingResult processIndividualEvent(Event event) {
    try {
      // Step 1: Dispatch the event
      dispatcher.send(event);

      // Step 2: Mark event as completed
      // Note: Recurring events are now handled in EventProcessingKafkaListener
      event.setStatus("COMPLETED");
      eventRepository.save(event);

      log.debug("Successfully processed event {}", event.getEventId());
      return new ProcessingResult(true, 1, 0, null);

    } catch (Exception e) {
      log.error("Failed to process event {}: {}", event.getEventId(), e.getMessage());

      try {
        // Handle the failure within this transaction
        event.setFailureCount(event.getFailureCount() + 1);

        final int DEFAULT_MAX_RETRIES = 3;
        if (event.getFailureCount() >= DEFAULT_MAX_RETRIES) {
          // PHASE 1: Mark event as FAILED immediately (zero data loss guaranteed)
          log.error("Event {} exceeded max retries ({}), marking as FAILED", event.getEventId(), 3);

          event.setStatus("FAILED");
          eventRepository.save(event);

          // PHASE 2: Try to move to failed_events table
          try {
            boolean moved =
                moveToFailedEventsTable(event, "Max retries exceeded: " + e.getMessage());
            if (moved) {
              log.info(
                  "Event {} failed and successfully moved to failed_events table",
                  event.getEventId());
            } else {
              log.warn(
                  "Event {} marked as FAILED but move to failed_events failed - daily audit will retry",
                  event.getEventId());
            }
          } catch (Exception moveException) {
            log.error(
                "Exception moving failed event {} - marked as FAILED, daily audit will retry: {}",
                event.getEventId(),
                moveException.getMessage());
          }
        } else {
          // Keep in PROCESSING status - will be picked up in next cycle
          // DO NOT change status - leave as PROCESSING for next cycle pickup
          log.warn(
              "Event {} failed (attempt {}/{}), remains PROCESSING for next cycle pickup",
              event.getEventId(),
              event.getFailureCount(),
              3);
        }

        eventRepository.save(event);
        return new ProcessingResult(true, 0, 1, null);

      } catch (Exception saveException) {
        log.error(
            "Failed to save event {} after processing failure", event.getEventId(), saveException);
        return new ProcessingResult(
            false,
            0,
            1,
            "Failed to save event after processing failure: " + saveException.getMessage());
      }
    }
  }

  /**
   * Processes all events scheduled for a specific timestamp. This method is called from the Kafka
   * listener after distributed lock acquisition.
   *
   * <p>IMPORTANT: This method does NOT use @Transactional to avoid rollback affecting all events.
   * Each event is processed in its own transaction to ensure isolation.
   */
  public ProcessingResult processEventsForTimestamp(LocalDateTime timestamp) {
    int retryCount = 0;
    ProcessingResult result = null;

    while (retryCount < dbErrorHandler.getMaxRetries()) {
      try {
        log.info("Starting batch processing for timestamp: {}", timestamp);

        // Fetch all pending events for this exact timestamp
        List<Event> events =
            eventRepository.findEventsDueAtExactTime(timestamp, LocalDateTime.now(clock));

        if (events.isEmpty()) {
          log.debug("No events found for timestamp: {}", timestamp);
          return new ProcessingResult(true, 0, 0, null);
        }

        log.info("Processing {} events for timestamp: {}", events.size(), timestamp);

        int processedCount = 0;
        int failedCount = 0;

        // Send each event to Kafka processing queue for individual handling
        for (Event event : events) {
          try {
            // Update event status to PROCESSING to prevent duplicate processing
            event.setStatus("PROCESSING");
            event.setLastAttemptTime(LocalDateTime.now(clock));
            eventRepository.save(event);

            // Send to processing queue
            sendEventToProcessingQueue(event);
            processedCount++;
            log.debug("Sent event {} to processing queue", event.getEventId());
          } catch (Exception e) {
            log.error(
                "Failed to send event {} to processing queue: {}",
                event.getEventId(),
                e.getMessage());
            // Increment failure count and keep in PROCESSING status for retry
            event.setFailureCount(event.getFailureCount() + 1);
            eventRepository.save(event);
            failedCount++;
          }
        }

        log.info(
            "Batch processing completed for timestamp {}: {} succeeded, {} failed",
            timestamp,
            processedCount,
            failedCount);

        result = new ProcessingResult(true, processedCount, failedCount, null);
        break;

      } catch (DataAccessException ex) {
        retryCount++;
        DatabaseErrorHandlingService.ErrorClassification errorClass =
            dbErrorHandler.classifyError(ex);

        if (errorClass.shouldRetry() && retryCount < dbErrorHandler.getMaxRetries()) {
          Duration retryDelay = dbErrorHandler.calculateRetryDelay(retryCount);
          log.warn(
              "Retryable database error occurred. Attempt {} of {}. Retrying in {} ms: {}",
              retryCount,
              dbErrorHandler.getMaxRetries(),
              retryDelay.toMillis(),
              ex.getMessage());
          try {
            Thread.sleep(retryDelay.toMillis());
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
          }
        } else {
          log.error("Fatal database error occurred. Not retrying: {}", ex.getMessage(), ex);
          result = new ProcessingResult(false, 0, 0, ex.getMessage());
          break;
        }

      } catch (Exception e) {
        log.error("Critical error during batch processing for timestamp: {}", timestamp, e);
        result = new ProcessingResult(false, 0, 0, e.getMessage());
        break;
      }
    }

    return (result != null) ? result : new ProcessingResult(false, 0, 0, "Unknown error occurred");
  }

  /**
   * Sends an event to the processing queue for individual processing. This allows events to be
   * processed independently without transaction rollbacks.
   */
  private void sendEventToProcessingQueue(Event event) throws Exception {
    try {
      Map<String, Object> message = new HashMap<>();
      message.put("eventId", event.getEventId());
      message.put("customerId", event.getCustomerId());
      message.put("type", "PROCESS_EVENT");
      message.put("sentAt", LocalDateTime.now(clock).format(FORMATTER));
      message.put("eventData", objectMapper.writeValueAsString(event));

      String messageJson = objectMapper.writeValueAsString(message);
      String key = "process:" + event.getEventId();

      kafkaTemplate
          .send(processingTopic, key, messageJson)
          .whenComplete(
              (result, ex) -> {
                if (ex == null) {
                  log.debug("Successfully sent event {} to processing queue", event.getEventId());
                } else {
                  log.error("Failed to send event {} to processing queue", event.getEventId(), ex);
                  throw new RuntimeException("Failed to send event to processing queue", ex);
                }
              });

    } catch (Exception e) {
      log.error("Error creating processing message for event {}", event.getEventId(), e);
      throw e;
    }
  }

  /**
   * Handles retry processing for failed events at a specific timestamp. Called when a retry message
   * is received via Kafka.
   */
  @Transactional
  public void handleRetryProcessing(LocalDateTime timestamp, int retryCount, String previousError) {
    log.info(
        "Handling retry processing for timestamp: {}, attempt: {}, previous error: {}",
        timestamp,
        retryCount,
        previousError);

    try {
      // Find events that are still pending or failed for this timestamp
      List<Event> retryEvents = eventRepository.findByScheduledTimeAndStatus(timestamp, "PENDING");

      if (!retryEvents.isEmpty()) {
        log.info("Found {} events to retry for timestamp: {}", retryEvents.size(), timestamp);
        ProcessingResult result = processEventsForTimestamp(timestamp);

        if (result.isSuccess()) {
          log.info("Retry processing successful for timestamp: {}", timestamp);
        } else {
          log.error(
              "Retry processing failed for timestamp: {} - {}",
              timestamp,
              result.getErrorMessage());
        }
      } else {
        log.debug("No events found for retry at timestamp: {}", timestamp);
      }

    } catch (Exception e) {
      log.error("Error during retry processing for timestamp: {}", timestamp, e);
    }
  }

  /**
   * PHASE 2: Safely move a failed event to the failed_events table using insert-first approach.
   * This method uses a separate transaction to ensure isolation from the main processing logic.
   *
   * @param event The event to move to failed_events table
   * @param failureReason Reason for the failure
   * @return true if event was successfully moved, false if it remains in events table
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  private boolean moveToFailedEventsTable(Event event, String failureReason) {
    try {
      // Verify event still exists
      if (!eventRepository.existsById(event.getEventId())) {
        log.debug("Event {} no longer exists - already processed", event.getEventId());
        return false;
      }

      String detailedReason = failureReason + " | Moved at: " + LocalDateTime.now(clock);
      com.fourkites.event.scheduler.model.FailedEvent failedEvent =
          new com.fourkites.event.scheduler.model.FailedEvent(event, detailedReason);
      failedEventRepository.save(failedEvent);

      eventRepository.deleteById(event.getEventId());

      log.debug("Event {} successfully moved to failed_events table", event.getEventId());
      return true;
    } catch (Exception e) {
      log.error(
          "Exception moving event {} to failed_events table - remains in events table: {}",
          event.getEventId(),
          e.getMessage(),
          e);
      return false;
    }
  }

  public static class ProcessingResult {
    private final boolean success;
    private final int processedCount;
    private final int failedCount;
    private final String errorMessage;

    public ProcessingResult(
        boolean success, int processedCount, int failedCount, String errorMessage) {
      this.success = success;
      this.processedCount = processedCount;
      this.failedCount = failedCount;
      this.errorMessage = errorMessage;
    }

    public boolean isSuccess() {
      return success;
    }

    public int getProcessedCount() {
      return processedCount;
    }

    public int getFailedCount() {
      return failedCount;
    }

    public String getErrorMessage() {
      return errorMessage;
    }
  }
}
