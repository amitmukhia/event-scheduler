package com.fourkites.event.scheduler.jobs;

import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.model.FailedEvent;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.repository.FailedEventRepository;
import com.fourkites.event.scheduler.service.DistributedLockService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Watchdog Mechanism for Event Scheduler
 *
 * <p>Role of the Watchdog: - Ensures events stuck in PROCESSING for too long are reset - Runs per
 * customer to maintain isolation - Prevents events from getting stuck indefinitely - Ensures events
 * can still be picked up by the next workflow run - Handles events that have exceeded max retry
 * limits
 */
@Component
public class Watchdog {
  private static final Logger log = LoggerFactory.getLogger(Watchdog.class);

  private final EventRepository eventRepository;
  private final FailedEventRepository failedEventRepository;
  private final DistributedLockService distributedLockService;
  private final Clock clock;

  // Configurable timeouts
  private final int stuckEventThresholdMinutes;
  private final long checkIntervalMs;
  private final long failedEventCheckIntervalMs;
  private final int batchSize;

  public Watchdog(
      EventRepository eventRepository,
      FailedEventRepository failedEventRepository,
      DistributedLockService distributedLockService,
      Clock clock,
      com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.eventRepository = eventRepository;
    this.failedEventRepository = failedEventRepository;
    this.distributedLockService = distributedLockService;
    this.clock = clock;
    var wd = appProps.watchdog();
    this.stuckEventThresholdMinutes = wd != null && wd.stuckEventThresholdMinutes() != null ? wd.stuckEventThresholdMinutes() : 15;
    this.checkIntervalMs = wd != null && wd.checkIntervalMs() != null ? wd.checkIntervalMs() : 300000L;
    this.failedEventCheckIntervalMs = wd != null && wd.failedEventCheckIntervalMs() != null ? wd.failedEventCheckIntervalMs() : 600000L;
    this.batchSize = wd != null && wd.batchSize() != null ? wd.batchSize() : 100;
    log.info("Watchdog initialized with distributed locking for multi-instance coordination");
  }

  /**
   * Single-cycle watchdog: - Phase 1: Move exhausted retries to failed_events - Phase 2: Reset
   * stuck but retryable PROCESSING events to PENDING Runs every 5 minutes by default under a single
   * distributed lock for clear, deterministic outcomes.
   */
  @Scheduled(fixedRateString = "${event-scheduler.watchdog.check-interval-ms:300000}")
  public void runWatchdogCycle() {
    boolean executed =
        distributedLockService.executeWithTaskLock(
            "watchdog-cycle",
            9, // lock for 9 minutes to cover both phases safely
            java.util.concurrent.TimeUnit.MINUTES,
            () -> {
              // Phase 1: Move failed first
              performFailedEventHandling();
              // Phase 2: Reset stuck but retryable
              performStuckEventReset();
            });
    if (!executed) {
      log.debug("Skipping watchdog cycle - another instance is handling it");
    }
  }

  /**
   * Performs the actual stuck event reset work. Separated for better testing and lock management.
   */
  @Transactional
  private void performStuckEventReset() {
    long startTime = System.currentTimeMillis();
    log.info("Starting watchdog stuck event reset check (this instance selected)");

    try {
      LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(stuckEventThresholdMinutes);

      // Reset stuck events using the existing repository method
      int resetCount = eventRepository.resetStuckEvents(threshold);

      long duration = System.currentTimeMillis() - startTime;

      if (resetCount > 0) {
        log.warn(
            "Watchdog reset {} stuck events older than {} (took {}ms)",
            resetCount,
            threshold,
            duration);

        // Log additional details about reset events for monitoring
        logStuckEventDetails(threshold);
      } else {
        log.debug("No stuck events found to reset (took {}ms)", duration);
      }

    } catch (Exception e) {
      log.error("Error during watchdog stuck event reset", e);
    }
  }

  /**
   * Resets events stuck in PROCESSING state per customer for isolation. This method can be called
   * manually or triggered for specific customers.
   */
  @Transactional
  public WatchdogResult resetStuckEventsPerCustomer(UUID customerId) {
    long startTime = System.currentTimeMillis();
    log.debug("Starting per-customer watchdog reset for customer: {}", customerId);

    try {
      LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(stuckEventThresholdMinutes);

      // Find stuck events for this specific customer
      int resetCount = eventRepository.resetStuckEventsForCustomer(customerId, threshold);

      long duration = System.currentTimeMillis() - startTime;

      if (resetCount > 0) {
        log.warn(
            "Watchdog reset {} stuck events for customer {} older than {} (took {}ms)",
            resetCount,
            customerId,
            threshold,
            duration);
      }

      return new WatchdogResult(true, resetCount, 0, duration, null);

    } catch (Exception e) {
      long duration = System.currentTimeMillis() - startTime;
      log.error("Error during per-customer watchdog reset for customer {}", customerId, e);
      return new WatchdogResult(false, 0, 0, duration, e.getMessage());
    }
  }

  // Note: Failed-event handling is now part of the single-cycle watchdog.
  // The logic is executed within runWatchdogCycle() under the same lock.

  /**
   * Performs the actual failed event handling work. MULTI-INSTANCE SAFE: Only executed by the
   * instance that acquires the lock.
   */
  @Transactional
  private void performFailedEventHandling() {
    long startTime = System.currentTimeMillis();
    log.info("Starting watchdog failed event check (this instance selected)");

    try {
      // Find events that have exceeded max retries
      List<Event> failedEvents = eventRepository.findEventsExceedingMaxRetries();

      if (failedEvents.isEmpty()) {
        log.debug("No events exceeding retry limits found");
        return;
      }

      // Group by customer for better logging and isolation
      Map<UUID, List<Event>> eventsByCustomer =
          failedEvents.stream().collect(Collectors.groupingBy(Event::getCustomerId));

      int totalProcessedEvents = 0;
      int successfullyMovedEvents = 0;
      int failedToMoveEvents = 0;

      for (Map.Entry<UUID, List<Event>> entry : eventsByCustomer.entrySet()) {
        UUID customerId = entry.getKey();
        List<Event> customerEvents = entry.getValue();

        log.warn(
            "Customer {} has {} events exceeding retry limits", customerId, customerEvents.size());

        for (Event event : customerEvents) {
          totalProcessedEvents++;
          log.error(
              "Event {} for customer {} exceeded max retries ({}), moving to failed_events",
              event.getEventId(),
              customerId,
              3);

          // Move failed event to failed_events table - each event is isolated
          boolean moveSuccessful = moveEventToFailedEvents(event, "Max retries exceeded");
          if (moveSuccessful) {
            successfullyMovedEvents++;
          } else {
            failedToMoveEvents++;
            log.warn(
                "Failed to move event {} for customer {} - marked for manual intervention",
                event.getEventId(),
                customerId);
          }
        }
      }

      long duration = System.currentTimeMillis() - startTime;
      log.warn(
          "Processed {} events across {} customers: {} successfully moved, {} failed to move (took {}ms)",
          totalProcessedEvents,
          eventsByCustomer.size(),
          successfullyMovedEvents,
          failedToMoveEvents,
          duration);

      // Log summary statistics for monitoring
      if (failedToMoveEvents > 0) {
        log.error(
            "ALERT: {} events failed to move to failed_events table and require manual intervention",
            failedToMoveEvents);
      }

    } catch (Exception e) {
      long duration = System.currentTimeMillis() - startTime;
      log.error("Error during watchdog failed event handling (took {}ms)", duration, e);
    }
  }

  /** Provides watchdog health check and statistics. */
  public WatchdogHealth getWatchdogHealth() {
    try {
      LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(stuckEventThresholdMinutes);

      // Count current stuck events
      long stuckEventsCount = eventRepository.countStuckEvents(threshold);

      // Count events exceeding retry limits
      long failedEventsCount = eventRepository.findEventsExceedingMaxRetries().size();

      return new WatchdogHealth(
          true,
          stuckEventsCount,
          failedEventsCount,
          stuckEventThresholdMinutes,
          Duration.ofMillis(checkIntervalMs),
          Duration.ofMillis(failedEventCheckIntervalMs));

    } catch (Exception e) {
      log.error("Error getting watchdog health", e);
      return new WatchdogHealth(
          false,
          -1,
          -1,
          stuckEventThresholdMinutes,
          Duration.ofMillis(checkIntervalMs),
          Duration.ofMillis(failedEventCheckIntervalMs));
    }
  }

  /** Manual trigger for watchdog operations - useful for testing or emergency operations. */
  public WatchdogResult runManualWatchdog() {
    long startTime = System.currentTimeMillis();
    log.info("Manual watchdog trigger initiated");

    try {
      // Reset stuck events
      performStuckEventReset();

      // Handle failed events
      performFailedEventHandling();

      long duration = System.currentTimeMillis() - startTime;
      log.info("Manual watchdog completed successfully (took {}ms)", duration);

      return new WatchdogResult(true, 0, 0, duration, "Manual watchdog completed successfully");

    } catch (Exception e) {
      long duration = System.currentTimeMillis() - startTime;
      log.error("Manual watchdog failed (took {}ms)", duration, e);
      return new WatchdogResult(false, 0, 0, duration, e.getMessage());
    }
  }

  /** Logs additional details about stuck events for monitoring and debugging. */
  private void logStuckEventDetails(LocalDateTime threshold) {
    try {
      // This could be enhanced with custom repository methods for detailed analysis
      log.debug("Stuck events were reset with threshold: {}", threshold);

      // You could add more detailed logging here, such as:
      // - Customer distribution of stuck events
      // - Event types that get stuck most often
      // - Time distribution of when events get stuck

    } catch (Exception e) {
      log.debug("Error logging stuck event details", e);
    }
  }

  /**
   * Moves a failed event from events table to failed_events table. This is a direct move approach -
   * events are archived in the failed_events table and removed from the main events table to
   * prevent duplicates.
   *
   * <p>ISOLATION: This method uses its own transaction boundary to ensure that failures in moving
   * one event don't affect the processing of other events.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public boolean moveEventToFailedEvents(Event event, String failureReason) {
    try {
      // Validate that the event still exists and is in the expected state
      if (!eventRepository.existsById(event.getEventId())) {
        log.warn(
            "Event {} no longer exists in events table, skipping move to failed_events",
            event.getEventId());
        return false;
      }

      // Create a detailed failure reason with current timestamp for better tracking
      String detailedReason =
          failureReason
              + " | Failed at: "
              + LocalDateTime.now(clock)
              + " | After "
              + event.getFailureCount()
              + " attempts"
              + " | Customer: "
              + event.getCustomerId();

      // Create failed event record with all original event data and detailed failure reason
      FailedEvent failedEvent = new FailedEvent(event, detailedReason);

      // Save to failed_events table first
      failedEventRepository.save(failedEvent);
      log.debug("Successfully saved event {} to failed_events table", event.getEventId());

      // Only delete from main events table after successful save
      eventRepository.deleteById(event.getEventId());

      log.info(
          "Successfully moved event {} to failed_events with reason: {}",
          event.getEventId(),
          detailedReason);
      return true;

    } catch (Exception e) {
      // Log the error but don't propagate it to avoid affecting other events
      log.error(
          "Failed to move event {} to failed_events: {} | Event will remain in events table",
          event.getEventId(),
          e.getMessage(),
          e);

      // Try to update the event status to indicate the move failed
      try {
        Event failedToMoveEvent = eventRepository.findById(event.getEventId()).orElse(null);
        if (failedToMoveEvent != null) {
          failedToMoveEvent.setStatus("MOVE_FAILED");
          failedToMoveEvent.setUpdatedAt(LocalDateTime.now(clock));
          eventRepository.save(failedToMoveEvent);
          log.info(
              "Marked event {} with status MOVE_FAILED for manual intervention",
              event.getEventId());
        }
      } catch (Exception updateEx) {
        log.error(
            "Failed to update event {} status after move failure: {}",
            event.getEventId(),
            updateEx.getMessage());
      }

      return false;
    }
  }

  /** Result class for watchdog operations. */
  public static class WatchdogResult {
    private final boolean success;
    private final int resetCount;
    private final int failedCount;
    private final long durationMs;
    private final String message;

    public WatchdogResult(
        boolean success, int resetCount, int failedCount, long durationMs, String message) {
      this.success = success;
      this.resetCount = resetCount;
      this.failedCount = failedCount;
      this.durationMs = durationMs;
      this.message = message;
    }

    // Getters
    public boolean isSuccess() {
      return success;
    }

    public int getResetCount() {
      return resetCount;
    }

    public int getFailedCount() {
      return failedCount;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public String getMessage() {
      return message;
    }
  }

  /** Health check class for watchdog monitoring. */
  public static class WatchdogHealth {
    private final boolean healthy;
    private final long currentStuckEvents;
    private final long currentFailedEvents;
    private final int stuckThresholdMinutes;
    private final Duration checkInterval;
    private final Duration failedCheckInterval;

    public WatchdogHealth(
        boolean healthy,
        long currentStuckEvents,
        long currentFailedEvents,
        int stuckThresholdMinutes,
        Duration checkInterval,
        Duration failedCheckInterval) {
      this.healthy = healthy;
      this.currentStuckEvents = currentStuckEvents;
      this.currentFailedEvents = currentFailedEvents;
      this.stuckThresholdMinutes = stuckThresholdMinutes;
      this.checkInterval = checkInterval;
      this.failedCheckInterval = failedCheckInterval;
    }

    // Getters
    public boolean isHealthy() {
      return healthy;
    }

    public long getCurrentStuckEvents() {
      return currentStuckEvents;
    }

    public long getCurrentFailedEvents() {
      return currentFailedEvents;
    }

    public int getStuckThresholdMinutes() {
      return stuckThresholdMinutes;
    }

    public Duration getCheckInterval() {
      return checkInterval;
    }

    public Duration getFailedCheckInterval() {
      return failedCheckInterval;
    }
  }
}
