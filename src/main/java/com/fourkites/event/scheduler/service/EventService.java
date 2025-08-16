package com.fourkites.event.scheduler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dto.CreateEventRequest;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.model.EventHistory;
import com.fourkites.event.scheduler.repository.EventHistoryRepository;
import com.fourkites.event.scheduler.repository.EventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class EventService {

  private final EventRepository repo;
  private final EventHistoryRepository historyRepo;
  private final com.fourkites.event.scheduler.repository.FailedEventRepository
      failedEventRepository;
  private final com.fourkites.event.scheduler.infrastructure.redis.RedisCoordinator
      eventRedisService;
  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbcTemplate;
  private final Clock clock;

  @PersistenceContext private EntityManager entityManager;

  public EventService(
      EventRepository repo,
      EventHistoryRepository historyRepo,
      com.fourkites.event.scheduler.repository.FailedEventRepository failedEventRepository,
      com.fourkites.event.scheduler.infrastructure.redis.RedisCoordinator eventRedisService,
      ObjectMapper objectMapper,
      JdbcTemplate jdbcTemplate,
      Clock clock) {
    this.repo = repo;
    this.historyRepo = historyRepo;
    this.failedEventRepository = failedEventRepository;
    this.eventRedisService = eventRedisService;
    this.objectMapper = objectMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.clock = clock;
  }

  @Transactional
  public Event create(CreateEventRequest req) {
    log.info("=== EventService.create() called for customer: {}", req.customerId());

    Event e = new Event();
    e.setEventId(UUID.randomUUID());
    e.setCustomerId(req.customerId());

    // Determine base time
    LocalDateTime now = LocalDateTime.now(clock);
    LocalDateTime baseTime;
    if (req.triggerTime() == null) {
      baseTime = com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(now);
    } else {
      LocalDateTime normalized =
          com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(req.triggerTime());
      if (!normalized.isAfter(now)) {
        throw new IllegalArgumentException("triggerTime must be in the future (minute precision)");
      }
      baseTime = normalized;
    }

    e.setTriggerTime(baseTime);
    e.setPayload(req.payload());
    e.setFailureCount(0);
    e.setExpirationTime(req.expirationTime());
    e.setStatus("PENDING");

    // Handle event mode and recurrence
    e.setEventMode(req.mode());
    if ("RECURRING".equals(req.mode()) && req.recurrence() != null) {
      try {
        JsonNode recurrenceJson = objectMapper.valueToTree(req.recurrence());
        e.setRecurrence(recurrenceJson);
        log.info("Set recurrence configuration for event: {}", recurrenceJson);
        // Compute nextRunTime from recurrence based on base time
        LocalDateTime computedNext = calculateNextRunTime(recurrenceJson, baseTime);
        e.setNextRunTime(computedNext != null ? computedNext : baseTime);
      } catch (Exception ex) {
        log.error("Failed to serialize recurrence configuration: {}", ex.getMessage());
        throw new RuntimeException("Invalid recurrence configuration", ex);
      }
    } else {
      // ONCE or no recurrence: nextRunTime equals base time
      e.setNextRunTime(baseTime);
    }

    // Handle callback configuration
    if (req.callback() != null && req.callback().hasCallbacks()) {
      try {
        JsonNode callbackJson = objectMapper.valueToTree(req.callback());
        e.setCallback(callbackJson);
        log.info("Set callback configuration for event: {}", callbackJson);
      } catch (Exception ex) {
        log.error("Failed to serialize callback configuration: {}", ex.getMessage());
        throw new RuntimeException("Invalid callback configuration", ex);
      }
    }

    log.info("=== About to save event with ID: {}", e.getEventId());
    Event savedEvent = repo.save(e);
    // Force flush to database
    entityManager.flush();
    log.info("=== Event saved successfully with ID: {} and flushed to DB", savedEvent.getEventId());

    // Create Redis TTL key if event is within next 1 hour (re-enabled)
    try {
      scheduleRedisKeyIfWithinNextHour(e.getNextRunTime());
    } catch (Exception ex) {
      log.error("Redis TTL scheduling failed, but event was saved: {}", ex.getMessage());
    }

    log.info("=== Returning saved event, transaction should commit now");
    return savedEvent;
  }

  /**
   * Creates Redis TTL key if the timestamp is within the current orchestrator window. This
   * maintains coordination with the orchestrator while solving the gap problem. The orchestrator
   * runs every hour and manages the next hour's events.
   */
  public void scheduleRedisKeyIfWithinNextHour(LocalDateTime eventTimestamp) {
    try {
      boolean scheduled = eventRedisService.scheduleTimestampIfWithinHour(eventTimestamp);
      if (scheduled) {
        log.info("Created Redis TTL key for event at timestamp: {}", eventTimestamp);
      } else {
        log.debug(
            "Redis TTL key not scheduled for timestamp: {} (outside orchestrator window or already exists)",
            eventTimestamp);
      }
    } catch (Exception e) {
      log.error(
          "Failed to create Redis TTL key for timestamp: {}, error: {}",
          eventTimestamp,
          e.getMessage());
    }
  }

  /** Calculate the next run time for a recurring event */
  public LocalDateTime calculateNextRunTime(JsonNode recurrence, LocalDateTime currentTime) {
    if (recurrence == null) {
      return null;
    }

    try {
      int delay = recurrence.get("delay").asInt();
      String timeUnit = recurrence.get("timeUnit").asText();
      LocalDateTime base = com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(currentTime);

      switch (timeUnit) {
        case "SECONDS":
          return com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(base.plusSeconds(delay));
        case "MINUTES":
          return com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(base.plusMinutes(delay));
        case "HOURS":
          return com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(base.plusHours(delay));
        case "DAYS":
          return com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(base.plusDays(delay));
        default:
          log.error("Unknown time unit: {}", timeUnit);
          return null;
      }
    } catch (Exception e) {
      log.error("Failed to calculate next run time from recurrence: {}", e.getMessage());
      return null;
    }
  }

  /** Check if a recurring event should continue to run */
  public boolean shouldContinueRecurrence(Event event) {
    if (!"RECURRING".equals(event.getEventMode()) || event.getRecurrence() == null) {
      return false;
    }

    try {
      JsonNode recurrence = event.getRecurrence();
      boolean infinite = recurrence.get("infinite").asBoolean();

      if (infinite) {
        return true;
      }

      // Check max occurrences
      if (recurrence.has("maxOccurrences")) {
        int maxOccurrences = recurrence.get("maxOccurrences").asInt();
        int currentCount =
            recurrence.has("currentOccurrenceCount")
                ? recurrence.get("currentOccurrenceCount").asInt()
                : 0;
        if (currentCount >= maxOccurrences) {
          return false;
        }
      }

      return true;
    } catch (Exception e) {
      log.error("Error checking recurrence continuation: {}", e.getMessage());
      return false;
    }
  }

  @Transactional
  public void writeHistoryAndDelete(Event e) {
    EventHistory h = new EventHistory();
    h.setEventId(e.getEventId());
    h.setCustomerId(e.getCustomerId());
    h.setTriggerTime(e.getTriggerTime());
    h.setPayload(e.getPayload());
    h.setStatus(e.getStatus());
    h.setEventMode(e.getEventMode());
    h.setRecurrence(e.getRecurrence());
    h.setCallback(e.getCallback());
    h.setCancelledBy(e.getCancelledBy());
    h.setCompletionTime(LocalDateTime.now(clock));
    historyRepo.save(h);

    // Delete the original event
    repo.delete(e);
    log.debug("Moved event {} to history and deleted from events table", e.getEventId());
  }

  @Transactional
  public void writeHistoryAndDelete(List<Event> events) {
    if (events.isEmpty()) {
      return;
    }

    try {
      // Create history records for all events
      List<EventHistory> historyRecords =
          events.stream()
              .map(
                  e -> {
                    EventHistory h = new EventHistory();
                    h.setEventId(e.getEventId());
                    h.setCustomerId(e.getCustomerId());
                    h.setTriggerTime(e.getTriggerTime());
                    h.setPayload(e.getPayload());
                    h.setStatus(e.getStatus());
                    h.setEventMode(e.getEventMode());
                    h.setRecurrence(e.getRecurrence());
                    h.setCallback(e.getCallback());
                    h.setCancelledBy(e.getCancelledBy());
                    h.setCompletionTime(LocalDateTime.now(clock));
                    return h;
                  })
              .collect(Collectors.toList());

      // Batch save to history
      historyRepo.saveAll(historyRecords);

      // Batch delete from events
      repo.deleteAll(events);

      log.debug("Moved {} events to history and deleted from events table", events.size());

    } catch (Exception e) {
      log.error("Failed to move events to history in batch", e);
      throw e; // Re-throw to trigger rollback
    }
  }

  /** Increment the occurrence count in the recurrence configuration */
  public JsonNode incrementOccurrenceCount(JsonNode recurrence) {
    if (recurrence == null) {
      return null;
    }

    try {
      com.fasterxml.jackson.databind.node.ObjectNode updatedRecurrence = recurrence.deepCopy();
      int currentCount =
          recurrence.has("currentOccurrenceCount")
              ? recurrence.get("currentOccurrenceCount").asInt()
              : 0;
      updatedRecurrence.put("currentOccurrenceCount", currentCount + 1);
      return updatedRecurrence;
    } catch (Exception e) {
      log.error("Failed to increment occurrence count: {}", e.getMessage());
      return recurrence;
    }
  }

  /**
   * Permanently cancel a single event by ID and move it to history. Returns true if an event was
   * cancelled.
   */
  @Transactional
  public boolean cancelEventById(UUID eventId, String cancelledByEmail) {
    log.info("Cancel-by-ID requested: eventId={}, cancelledByEmail={}", eventId, cancelledByEmail);
    Optional<Event> opt = repo.findById(eventId);
    if (opt.isEmpty()) {
      log.warn("Cancel-by-ID: event {} not found", eventId);
      return false;
    }
    Event e = opt.get();
    if ("COMPLETED".equals(e.getStatus())) {
      log.info("Cancel-by-ID: event {} already COMPLETED; skipping", eventId);
      return false;
    }
    if ("CANCELLED".equals(e.getStatus())) {
      log.info("Cancel-by-ID: event {} already CANCELLED; skipping duplicate", eventId);
      return false;
    }
    // Mark cancelled and archive
    e.setStatus("CANCELLED");
    e.setCanceledAt(LocalDateTime.now(clock));
    e.setCancelledBy(cancelledByEmail);
    log.info(
        "Cancel-by-ID: archiving eventId={} (customerId={}) by cancelledByEmail={}",
        e.getEventId(),
        e.getCustomerId(),
        cancelledByEmail);
    writeHistoryAndDelete(e);
    log.info("Cancel-by-ID: eventId={} archived to history", eventId);
    return true;
  }

  /**
   * Reschedule event: partial update for triggerTime, mode, and/or recurrence fields. nextRunTime
   * is auto-calculated based on the resulting mode/recurrence.
   */
  @Transactional
  public Optional<Event> rescheduleEvent(
      UUID eventId,
      LocalDateTime newTriggerTime,
      String newMode,
      com.fourkites.event.scheduler.dto.CreateEventRequest.Recurrence newRecurrence) {
    log.info(
        "Reschedule requested: eventId={}, newTriggerTime={}, newMode={}, hasRecurrence={}",
        eventId,
        newTriggerTime,
        newMode,
        (newRecurrence != null));

    Optional<Event> opt = repo.findById(eventId);
    if (opt.isEmpty()) {
      log.warn("Reschedule: event {} not found", eventId);
      return Optional.empty();
    }
    if (newTriggerTime == null && newMode == null && newRecurrence == null) {
      log.warn(
          "Reschedule: missing body fields for event {} (provide triggerTime/mode/recurrence)",
          eventId);
      throw new IllegalArgumentException("Provide triggerTime, mode or recurrence to reschedule");
    }

    Event e = opt.get();
    LocalDateTime now = LocalDateTime.now(clock);

    // Validate triggerTime if provided
    if (newTriggerTime != null
        && !com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(newTriggerTime)
            .isAfter(now)) {
      log.warn(
          "Reschedule: invalid triggerTime {} for event {} (must be > now={})",
          newTriggerTime,
          eventId,
          now);
      throw new IllegalArgumentException("triggerTime must be in the future");
    }

    // Apply triggerTime if provided
    LocalDateTime originalTrigger = e.getTriggerTime();
    LocalDateTime baseTime = originalTrigger;
    if (newTriggerTime != null) {
      LocalDateTime normalized =
          com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(newTriggerTime);
      e.setTriggerTime(normalized);
      baseTime = normalized;
      log.info(
          "Reschedule: triggerTime updated for event {} from {} to {}",
          eventId,
          originalTrigger,
          normalized);
    }

    // Apply mode change if provided
    if (newMode != null) {
      if (!"ONCE".equals(newMode) && !"RECURRING".equals(newMode)) {
        log.warn("Reschedule: invalid mode {} for event {}", newMode, eventId);
        throw new IllegalArgumentException("mode must be ONCE or RECURRING");
      }
      // If switching to RECURRING and no existing or incoming recurrence, reject
      if ("RECURRING".equals(newMode) && e.getRecurrence() == null && newRecurrence == null) {
        log.warn("Reschedule: switching to RECURRING requires recurrence for event {}", eventId);
        throw new IllegalArgumentException("recurrence is required when switching to RECURRING");
      }
      String oldMode = e.getEventMode();
      e.setEventMode(newMode);
      if ("ONCE".equals(newMode)) {
        e.setRecurrence(null);
      }
      log.info("Reschedule: mode updated for event {} from {} to {}", eventId, oldMode, newMode);
    }

    // Apply recurrence partial update if provided (only when event is RECURRING)
    if (newRecurrence != null) {
      if (!"RECURRING".equals(e.getEventMode())) {
        log.warn(
            "Reschedule: recurrence provided but mode is {} for event {}",
            e.getEventMode(),
            eventId);
        throw new IllegalArgumentException("Recurrence can only be updated when mode is RECURRING");
      }
      try {
        com.fasterxml.jackson.databind.node.ObjectNode before =
            (e.getRecurrence() != null && e.getRecurrence().isObject())
                ? e.getRecurrence().deepCopy()
                : objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode merged = before.deepCopy();
        if (newRecurrence.infinite() != null) {
          merged.put("infinite", newRecurrence.infinite());
        }
        if (newRecurrence.delay() != null) {
          merged.put("delay", newRecurrence.delay());
        }
        if (newRecurrence.timeUnit() != null) {
          merged.put("timeUnit", newRecurrence.timeUnit());
        }
        if (newRecurrence.maxOccurrences() != null) {
          merged.put("maxOccurrences", newRecurrence.maxOccurrences());
        }
        if (newRecurrence.currentOccurrenceCount() != null) {
          merged.put("currentOccurrenceCount", newRecurrence.currentOccurrenceCount());
        }
        e.setRecurrence(merged);
        log.info(
            "Reschedule: recurrence updated for event {} from {} to {}", eventId, before, merged);
      } catch (Exception ex) {
        log.warn("Reschedule: invalid recurrence for event {} - {}", eventId, ex.getMessage());
        throw new IllegalArgumentException("Invalid recurrence configuration", ex);
      }
    }

    // Compute nextRunTime automatically
    LocalDateTime prevNext = e.getNextRunTime();
    LocalDateTime newNext;
    if ("RECURRING".equals(e.getEventMode()) && e.getRecurrence() != null) {
      LocalDateTime computed = calculateNextRunTime(e.getRecurrence(), baseTime);
      newNext = (computed != null) ? computed : baseTime;
    } else {
      // ONCE or no recurrence
      newNext =
          (newTriggerTime != null)
              ? com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(newTriggerTime)
              : e.getTriggerTime();
    }

    if (!newNext.isAfter(now)) {
      log.warn(
          "Reschedule: computed nextRunTime {} is not in future (now={}) for event {}",
          newNext,
          now,
          eventId);
      throw new IllegalArgumentException("Resulting nextRunTime must be in the future");
    }

    e.setNextRunTime(com.fourkites.event.scheduler.util.TimeUtils.ceilToMinute(newNext));
    e.setStatus("PENDING");
    e.setFailureCount(0);
    e.setLastAttemptTime(null);

    log.info(
        "Reschedule: updating event {} nextRunTime from {} to {} and status=PENDING",
        eventId,
        prevNext,
        newNext);
    Event saved = repo.save(e);

    try {
      scheduleRedisKeyIfWithinNextHour(newNext);
      log.info("Reschedule: scheduled/verified Redis TTL for event {} at {}", eventId, newNext);
    } catch (Exception ex) {
      log.warn(
          "Reschedule: failed to schedule Redis key for event {} - {}", eventId, ex.getMessage());
    }

    return Optional.of(saved);
  }

  /**
   * Permanently cancel all events for a customer and move them to history. Returns number of events
   * cancelled.
   */
  @Transactional
  public int cancelEventsByCustomer(UUID customerId, String cancelledByEmail) {
    log.info(
        "Cancel-by-customer requested: customerId={}, cancelledByEmail={}",
        customerId,
        cancelledByEmail);
    // Consider only active statuses that can still execute
    List<String> statuses = java.util.Arrays.asList("PENDING", "PROCESSING");
    List<Event> toCancel = repo.findByCustomerIdAndStatusIn(customerId, statuses);
    if (toCancel.isEmpty()) {
      log.info("Cancel-by-customer: no active events found for customer {}", customerId);
      return 0;
    }
    for (Event e : toCancel) {
      e.setStatus("CANCELLED");
      e.setCanceledAt(LocalDateTime.now(clock));
      e.setCancelledBy(cancelledByEmail);
    }
    log.info(
        "Cancel-by-customer: archiving {} events for customerId={}", toCancel.size(), customerId);
    writeHistoryAndDelete(toCancel);
    log.info(
        "Cancel-by-customer: archived {} events for customerId={}", toCancel.size(), customerId);
    return toCancel.size();
  }

  /**
   * PHASE 2: Safely move a FAILED event to the failed_events table using insert-first approach.
   * This method uses a separate transaction to ensure isolation from other processing logic. Called
   * by daily audit process to retry moving events that couldn't be moved initially.
   *
   * @param event The event to move to failed_events table
   * @param failureReason Reason for the failure
   * @return true if event was successfully moved, false if it remains in events table
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean safelyMoveEventToFailedEvents(Event event, String failureReason) {
    try {
      // Verify event still exists
      if (!repo.existsById(event.getEventId())) {
        log.debug("Event {} no longer exists - already processed", event.getEventId());
        return false;
      }

      // Build detailed failure reason
      String detailedReason = failureReason + " | Daily audit at: " + LocalDateTime.now(clock);

      // Insert into failed_events first
      com.fourkites.event.scheduler.model.FailedEvent failedEvent =
          new com.fourkites.event.scheduler.model.FailedEvent(event, detailedReason);
      failedEventRepository.save(failedEvent);

      // Only delete after successful insert
      repo.deleteById(event.getEventId());

      log.debug(
          "Event {} successfully moved to failed_events table via daily audit", event.getEventId());
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
}
