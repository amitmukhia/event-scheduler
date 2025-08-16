package com.fourkites.event.scheduler.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dispatcher.Dispatcher;
import com.fourkites.event.scheduler.jobs.Cleanup;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.service.EventService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka listener that processes individual events from the processing queue. This allows events to
 * be processed independently without transaction rollbacks affecting other events in the same
 * batch.
 */
@Service
public class EventProcessingKafkaListener {
  private static final Logger log = LoggerFactory.getLogger(EventProcessingKafkaListener.class);
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

  private final EventRepository eventRepository;
  private final Dispatcher dispatcher;
  private final EventService eventService;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final Cleanup cleanup;
  private final Clock clock;

  public EventProcessingKafkaListener(
      EventRepository eventRepository,
      Dispatcher dispatcher,
      EventService eventService,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      Cleanup cleanup,
      Clock clock) {
    this.eventRepository = eventRepository;
    this.dispatcher = dispatcher;
    this.eventService = eventService;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.cleanup = cleanup;
    this.clock = clock;
  }

  /**
   * Processes individual events from the processing queue. Each event is processed in its own
   * transaction to prevent rollbacks from affecting others.
   */
  @KafkaListener(
      topics = "${kafka.topics.processing:process-kafka-message}",
      groupId = "${kafka.consumer.group-id:event-scheduler-processing-group}",
      concurrency = "${kafka.consumer.concurrency:10}")
  @Transactional
  public void processEvent(
      @Payload String message,
      @Header(KafkaHeaders.RECEIVED_KEY) String key,
      @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
      @Header(KafkaHeaders.RECEIVED_PARTITION) Integer partition,
      Acknowledgment acknowledgment) {
    try {
      log.debug(
          "Received processing message from topic: {}, partition: {}, key: {}",
          topic,
          partition,
          key);

      // Parse the message
      JsonNode messageNode = objectMapper.readTree(message);
      String type = messageNode.get("type").asText();

      if (!"PROCESS_EVENT".equals(type)) {
        log.warn("Received unknown message type: {}, ignoring", type);
        acknowledgment.acknowledge();
        return;
      }

      UUID eventId = UUID.fromString(messageNode.get("eventId").asText());
      String eventDataJson = messageNode.get("eventData").asText();
      Event event = objectMapper.readValue(eventDataJson, Event.class);

      log.info("Processing individual event: {}", eventId);

      // Fetch the latest event state from database to ensure we have current data
      Optional<Event> latestEventOpt = eventRepository.findById(eventId);
      if (latestEventOpt.isEmpty()) {
        log.error("Event {} not found in database, cannot process", eventId);
        acknowledgment.acknowledge(); // Acknowledge to prevent reprocessing
        return;
      }

      Event latestEvent = latestEventOpt.get();

      // Check if event is still in PROCESSING status
      if (!"PROCESSING".equals(latestEvent.getStatus())) {
        log.info(
            "Event {} is no longer in PROCESSING status (current: {}), skipping",
            eventId,
            latestEvent.getStatus());
        acknowledgment.acknowledge();
        return;
      }

      // Process the event
      boolean success = processIndividualEvent(latestEvent);

      if (success) {
        log.info("Successfully processed event: {}", eventId);
      } else {
        log.warn(
            "Failed to process event: {}, event remains PROCESSING - Watchdog will retry if stuck",
            eventId);
      }

      // Always acknowledge the message - let Watchdog handle stuck events
      acknowledgment.acknowledge();

    } catch (Exception e) {
      log.error(
          "Error processing event message: {}, acknowledging to prevent infinite retry - event remains PROCESSING for Watchdog",
          message,
          e);
      // Always acknowledge - Watchdog will handle stuck events
      acknowledgment.acknowledge();
    }
  }

  /**
   * Processes a single event within the current transaction. Returns true if successful, false if
   * failed (for retry).
   */
  private boolean processIndividualEvent(Event event) {
    try {
      // Step 1: Dispatch the event
      dispatcher.send(event);

      // Step 2: Update status based on event type
      if (!"RECURRING".equals(event.getEventMode())) {
        // One-time event - mark as completed and archive
        event.setStatus("COMPLETED");
        event.setFailureCount(0); // Reset failure count on success
        // Persist status update before archiving (entity still exists)
        eventRepository.save(event);
        // Archive and delete the event from the events table
        triggerCleanup(event);
        // Do NOT save the entity again after it's archived/deleted
        log.debug("One-time event {} completed and archived", event.getEventId());
        return true;
      } else {
        // Recurring event - increment occurrence count first
        JsonNode updatedRecurrence = eventService.incrementOccurrenceCount(event.getRecurrence());
        event.setRecurrence(updatedRecurrence);

        // Now check if it should continue with the updated count
        if (eventService.shouldContinueRecurrence(event)) {
          // Calculate next run time based on the later of scheduled time or now (UTC)
          LocalDateTime now = LocalDateTime.now(clock);
          LocalDateTime baseTime =
              event.getNextRunTime() != null && event.getNextRunTime().isAfter(now)
                  ? event.getNextRunTime()
                  : now;
          LocalDateTime nextRunTime =
              eventService.calculateNextRunTime(event.getRecurrence(), baseTime);

          if (nextRunTime != null) {
            // Update the same event for next run
            event.setNextRunTime(nextRunTime);
            event.setStatus("PENDING");
            event.setFailureCount(0);

            log.info(
                "Recurring event {} scheduled for next run at: {} (occurrence count: {})",
                event.getEventId(),
                nextRunTime,
                updatedRecurrence.has("currentOccurrenceCount")
                    ? updatedRecurrence.get("currentOccurrenceCount").asInt()
                    : 1);

            // Schedule Redis key if within orchestrator window (1 hour)
            try {
              eventService.scheduleRedisKeyIfWithinNextHour(nextRunTime);
            } catch (Exception ex) {
              log.warn("Failed to schedule Redis key for recurring event: {}", ex.getMessage());
            }
            // Persist the updated scheduling info for the recurring event
            saveWithRetry(event, 3);
            log.debug("Recurring event {} updated for next run", event.getEventId());
            return true;
          } else {
            log.error(
                "Failed to calculate next run time for recurring event {}", event.getEventId());
            return false;
          }
        } else {
          // Recurring series has ended - mark as completed and archive
          log.info(
              "Recurring event {} series has ended - marking as completed (final occurrence count: {})",
              event.getEventId(),
              updatedRecurrence.has("currentOccurrenceCount")
                  ? updatedRecurrence.get("currentOccurrenceCount").asInt()
                  : 1);
          event.setStatus("COMPLETED");
          event.setFailureCount(0);
          // Archive and delete; do not attempt to save afterwards
          triggerCleanup(event);
          log.debug("Recurring event {} completed and archived", event.getEventId());
          return true;
        }
      }

    } catch (Exception e) {
      log.error("Failed to process event {}: {}", event.getEventId(), e.getMessage(), e);

      try {
        // Handle the failure
        event.setFailureCount(event.getFailureCount() + 1);

        final int DEFAULT_MAX_RETRIES = 3;
        if (event.getFailureCount() >= DEFAULT_MAX_RETRIES) {
          // PHASE 1: Mark event as FAILED immediately (zero data loss guaranteed)
          log.error("Event {} exceeded max retries ({}), marking as FAILED", event.getEventId(), 3);

          event.setStatus("FAILED");
          saveWithRetry(event, 3);

          // PHASE 2: Try to move to failed_events table
          try {
            boolean moved =
                eventService.safelyMoveEventToFailedEvents(
                    event, "Max retries exceeded: " + e.getMessage());
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

          return true; // Acknowledge the message since it's permanently failed
        } else {
          // Keep in PROCESSING status - Watchdog will handle stuck events
          log.warn(
              "Event {} failed (attempt {}/{}), remains PROCESSING - Watchdog will retry if stuck",
              event.getEventId(),
              event.getFailureCount(),
              3);
          saveWithRetry(event, 3);
          return true; // Acknowledge the message - Watchdog will handle retry
        }

      } catch (Exception saveException) {
        log.error(
            "Failed to save event {} after processing failure", event.getEventId(), saveException);
        return true; // Acknowledge the message - event remains in PROCESSING for Watchdog
      }
    }
  }

  private void triggerCleanup(Event event) {
    try {
      // Trigger immediate cleanup for this specific completed event
      cleanup.archiveCompletedEvent(event);
    } catch (Exception e) {
      log.warn(
          "Immediate cleanup failed for event: {} - daily audit will handle it",
          event.getEventId(),
          e);
    }
  }

  private void saveWithRetry(Event event, int maxAttempts) {
    int attempts = 0;
    while (true) {
      try {
        eventRepository.save(event);
        return;
      } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
        attempts++;
        if (attempts >= maxAttempts) {
          log.warn("Optimistic lock retries exhausted for event {}", event.getEventId());
          throw ex;
        }
        try {
          Thread.sleep(50L * attempts);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw ex;
        }
        // reload latest and merge minimal fields if necessary could be added here
      }
    }
  }
}
