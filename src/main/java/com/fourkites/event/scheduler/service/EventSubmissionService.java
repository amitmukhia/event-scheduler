package com.fourkites.event.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dto.CreateEventRequest;
import com.fourkites.event.scheduler.model.Event;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Event submission service that supports both Kafka and SQS delivery mechanisms while maintaining
 * API compatibility and leveraging their scalability features.
 */
@Service
public class EventSubmissionService {

  private static final Logger log = LoggerFactory.getLogger(EventSubmissionService.class);

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final EventService fallbackEventService;

  private final String eventSubmissionTopic;
  private final String scheduledEventsTopic;

  public EventSubmissionService(
KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      EventService fallbackEventService,
      com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.fallbackEventService = fallbackEventService;
    var topics = appProps.kafka() != null ? appProps.kafka().topics() : null;
    this.eventSubmissionTopic =
        topics != null && topics.eventSubmission() != null
            ? topics.eventSubmission()
            : "event.submissions";
    this.scheduledEventsTopic =
        topics != null && topics.scheduledEvents() != null
            ? topics.scheduledEvents()
            : "event.scheduled";
  }

  /**
   * Submit event via Kafka for immediate processing or scheduling Maintains same API contract as
   * original service
   */
  public Event submitEvent(CreateEventRequest request) {
    UUID eventId = UUID.randomUUID();

    try {
      // Create event envelope for Kafka
      Map<String, Object> eventEnvelope = createEventEnvelope(eventId, request);
      String eventJson = objectMapper.writeValueAsString(eventEnvelope);

      // Determine if immediate or scheduled processing
      if (isImmediateProcessing(request.triggerTime())) {
        // Send to immediate processing topic
        kafkaTemplate.send(eventSubmissionTopic, eventId.toString(), eventJson);
        log.info("Event {} submitted for immediate processing", eventId);
      } else {
        // Send to scheduled events topic with delay
        kafkaTemplate.send(scheduledEventsTopic, eventId.toString(), eventJson);
        log.info("Event {} scheduled for {}", eventId, request.triggerTime());
      }

      // Return event representation (maintaining API compatibility)
      return createEventResponse(eventId, request);

    } catch (Exception e) {
      log.error("Failed to submit event {} via Kafka, falling back to database", eventId, e);
      // Fallback to original database-first approach
      return fallbackEventService.create(request);
    }
  }

  private Map<String, Object> createEventEnvelope(UUID eventId, CreateEventRequest request) {
    Map<String, Object> envelope = new HashMap<>();
    envelope.put("eventId", eventId.toString());
    envelope.put("customerId", request.customerId().toString());
    LocalDateTime effectiveTrigger =
        request.triggerTime() != null ? request.triggerTime() : LocalDateTime.now();
    envelope.put("triggerTime", effectiveTrigger.toString());
    envelope.put("eventMode", request.mode());
    if (request.recurrence() != null) {
      envelope.put("recurrence", request.recurrence());
    }
    // maxRetries removed from envelope; using code-defaults during processing
    envelope.put(
        "expirationTime",
        request.expirationTime() != null ? request.expirationTime().toString() : null);
    envelope.put("payload", request.payload());
    // deliveryMechanism removed; callbacks decide dispatch targets
    envelope.put("submittedAt", LocalDateTime.now().toString());
    envelope.put("status", "SUBMITTED");
    return envelope;
  }

  private boolean isImmediateProcessing(LocalDateTime triggerTime) {
    LocalDateTime effective = triggerTime != null ? triggerTime : LocalDateTime.now();
    return effective.isBefore(LocalDateTime.now().plusMinutes(1));
  }

  private Event createEventResponse(UUID eventId, CreateEventRequest request) {
    Event event = new Event();
    event.setEventId(eventId);
    event.setCustomerId(request.customerId());
    event.setTriggerTime(request.triggerTime());
    event.setNextRunTime(request.triggerTime());
    event.setPayload(request.payload());
    // maxRetries removed from entity; defaults handled in processing logic
    event.setFailureCount(0);
    event.setExpirationTime(request.expirationTime());
    event.setStatus("SUBMITTED"); // New status for Kafka-submitted events
    // deliveryMechanism removed from entity; callbacks decide dispatch targets
    event.setEventMode(request.mode());
    if (request.recurrence() != null) {
      try {
        event.setRecurrence(objectMapper.valueToTree(request.recurrence()));
      } catch (Exception e) {
        log.error("Failed to serialize recurrence configuration: {}", e.getMessage());
      }
    }
    return event;
  }
}
