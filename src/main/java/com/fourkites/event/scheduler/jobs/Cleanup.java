package com.fourkites.event.scheduler.jobs;

import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.service.EventService;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class Cleanup {
  private static final Logger log = LoggerFactory.getLogger(Cleanup.class);
  private final EventRepository repo;
  private final EventService svc;
  private final Clock clock;

  // Rate Limiting Configuration
  private final boolean rateLimitingEnabled;
  private final long historyTokenCapacity;
  private final long historyTokenRefillRate;
  private final long historyWindowMinutes;
  private final long historyMaxOperations;
  private final long dbTokenCapacity;
  private final long dbTokenRefillRate;
  private final long dbWindowMinutes;
  private final long dbMaxOperations;

  // Rate Limiters
  private TokenBucket historyTokenBucket;
  private SlidingWindow historyWindow;
  private TokenBucket dbTokenBucket;
  private SlidingWindow dbWindow;

  public Cleanup(EventRepository repo, EventService svc, Clock clock, com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.repo = repo;
    this.svc = svc;
    this.clock = clock;
    var rl = appProps.cleanup() != null ? appProps.cleanup().rateLimiting() : null;
    this.rateLimitingEnabled = rl != null && rl.enabled();
    this.historyTokenCapacity = rl != null && rl.historyTokenBucket() != null ? rl.historyTokenBucket().capacity() : 20;
    this.historyTokenRefillRate = rl != null && rl.historyTokenBucket() != null ? rl.historyTokenBucket().refillRate() : 3;
    this.historyWindowMinutes = rl != null && rl.historySlidingWindow() != null ? rl.historySlidingWindow().windowMinutes() : 1;
    this.historyMaxOperations = rl != null && rl.historySlidingWindow() != null ? rl.historySlidingWindow().maxOperations() : 150;
    this.dbTokenCapacity = rl != null && rl.databaseTokenBucket() != null ? rl.databaseTokenBucket().capacity() : 10;
    this.dbTokenRefillRate = rl != null && rl.databaseTokenBucket() != null ? rl.databaseTokenBucket().refillRate() : 2;
    this.dbWindowMinutes = rl != null && rl.databaseSlidingWindow() != null ? rl.databaseSlidingWindow().windowMinutes() : 5;
    this.dbMaxOperations = rl != null && rl.databaseSlidingWindow() != null ? rl.databaseSlidingWindow().maxOperations() : 100;
  }

  @PostConstruct
  private void initializeRateLimiters() {
    // Initialize rate limiters for history operations
    this.historyTokenBucket = new TokenBucket(historyTokenCapacity, historyTokenRefillRate);
    this.historyWindow = new SlidingWindow(historyWindowMinutes * 60, historyMaxOperations);

    // Initialize rate limiters for database operations
    this.dbTokenBucket = new TokenBucket(dbTokenCapacity, dbTokenRefillRate);
    this.dbWindow = new SlidingWindow(dbWindowMinutes * 60, dbMaxOperations);

    log.info(
        "Cleanup rate limiters initialized - History: {}t/{}ops, DB: {}t/{}ops",
        historyTokenCapacity,
        historyMaxOperations,
        dbTokenCapacity,
        dbMaxOperations);
  }

  /**
   * Archive a single completed event immediately when it completes. This is called directly from
   * EventProcessingKafkaListener when an event is marked COMPLETED.
   */
  @Async("cleanupTaskExecutor")
  public void archiveCompletedEvent(Event event) {
    try {
      // Check rate limits before processing
      if (rateLimitingEnabled && !canProcessHistoryOperation()) {
        log.debug(
            "Rate limit reached for immediate cleanup of event {}, will be handled by daily audit",
            event.getEventId());
        return;
      }

      // Archive the event immediately
      svc.writeHistoryAndDelete(event);

      // Record the operation in sliding window
      if (rateLimitingEnabled) {
        historyWindow.recordRequest();
      }

      log.debug("Immediately archived completed event {}", event.getEventId());

    } catch (Exception e) {
      log.warn(
          "Immediate archive failed for event {}: {}, will be handled by daily audit",
          event.getEventId(),
          e.getMessage());
      // Don't throw - let daily audit handle this event
    }
  }

  /**
   * Daily audit to find and archive completed events that were missed by immediate cleanup. Also
   * handles failed events that should be moved to failed_events table. Runs at 2 AM daily and
   * processes events in small batches to avoid performance impact.
   */
  @Scheduled(cron = "${event-scheduler.cleanup.audit.schedule:0 0 2 * * *}")
  @Async("cleanupTaskExecutor")
  public void dailyCleanupAudit() {
    log.info("Starting daily cleanup audit (completed events + failed events)");
    try {
      // PART 1: Handle completed events (existing logic)
      List<Event> completedEvents =
          repo.findAllByStatus("COMPLETED", Pageable.unpaged()).getContent();
      if (!completedEvents.isEmpty()) {
        log.info("Daily audit found {} completed events to archive", completedEvents.size());
        processCompletedEventsInBatches(completedEvents);
      } else {
        log.info("Daily audit: No completed events to archive");
      }

      // PART 2: Handle failed events that need to be moved to failed_events table
      List<Event> failedEvents = repo.findAllByStatus("FAILED", Pageable.unpaged()).getContent();
      if (!failedEvents.isEmpty()) {
        log.info(
            "Daily audit found {} FAILED events to retry moving to failed_events table",
            failedEvents.size());
        retryMovingFailedEvents(failedEvents);
      } else {
        log.info("Daily audit: No FAILED events need moving to failed_events table");
      }

      log.info("Daily cleanup audit completed");

    } catch (Exception e) {
      log.error("Daily cleanup audit failed", e);
    }
  }

  /** Process completed events in batches - archives them to event_history */
  private void processCompletedEventsInBatches(List<Event> completedEvents) {
    int batchSize = 100;
    int processedCount = 0;
    int failedCount = 0;

    for (int i = 0; i < completedEvents.size(); i += batchSize) {
      List<Event> batch =
          completedEvents.subList(i, Math.min(i + batchSize, completedEvents.size()));

      try {
        svc.writeHistoryAndDelete(batch);
        processedCount += batch.size();
        log.debug("Archived batch of {} completed events", batch.size());

      } catch (Exception e) {
        failedCount += batch.size();
        log.error(
            "Failed to archive batch starting with event {} - {} completed events in batch failed",
            batch.get(0).getEventId(),
            batch.size(),
            e);

        // Try processing individual events in failed batch to maximize success
        for (Event event : batch) {
          try {
            svc.writeHistoryAndDelete(event);
            processedCount++;
            failedCount--;
            log.debug("Individual retry succeeded for completed event {}", event.getEventId());
          } catch (Exception individualError) {
            log.warn(
                "Individual retry also failed for completed event {}: {}",
                event.getEventId(),
                individualError.getMessage());
          }
        }
      }

      // Small pause between batches to avoid system impact
      if (i + batchSize < completedEvents.size()) {
        try {
          Thread.sleep(50); // 50ms pause between batches
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Completed events batch processing interrupted");
          break;
        }
      }
    }

    log.info(
        "Completed events audit processing finished - {} archived, {} failed",
        processedCount,
        failedCount);
  }

  /** PHASE 2: Retry moving FAILED events to failed_events table during daily audit */
  private void retryMovingFailedEvents(List<Event> failedEvents) {
    int movedCount = 0;
    int preservedCount = 0;

    log.debug("Starting daily audit retry for {} FAILED events", failedEvents.size());

    // Process each FAILED event individually for perfect isolation (zero data loss guarantee)
    for (Event event : failedEvents) {
      try {
        boolean moved =
            svc.safelyMoveEventToFailedEvents(event, "Max retries exceeded - Daily audit retry");
        if (moved) {
          movedCount++;
          log.debug(
              "Daily audit successfully moved FAILED event {} to failed_events table",
              event.getEventId());
        } else {
          preservedCount++;
          log.warn(
              "Daily audit: FAILED event {} could not be moved - remains safely in events table",
              event.getEventId());
        }
      } catch (Exception e) {
        preservedCount++;
        log.error(
            "Daily audit: Exception moving FAILED event {} - remains safely in events table: {}",
            event.getEventId(),
            e.getMessage());
      }

      // Small pause between events to avoid database overload
      if (failedEvents.indexOf(event) % 10 == 0 && failedEvents.indexOf(event) > 0) {
        try {
          Thread.sleep(10); // 10ms pause every 10 events
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Failed events audit processing interrupted");
          break;
        }
      }
    }

    log.info(
        "Failed events daily audit finished - {} moved to failed_events, {} preserved in events table",
        movedCount,
        preservedCount);

    if (preservedCount > 0) {
      log.warn(
          "ATTENTION: {} FAILED events remain in events table and may need manual intervention",
          preservedCount);
    }
  }

  /** Rate-limited cleanup of old history records. */
  @Scheduled(fixedRate = 86400000) // 1 day (fixed typo from original)
  @Transactional
  public void cleanUpHistory() {
    long startTime = System.currentTimeMillis();

    try {
      // Check rate limits before database cleanup
      if (rateLimitingEnabled && !canProcessDatabaseOperation()) {
        log.debug("Database cleanup rate limit reached, skipping history cleanup");
        return;
      }

      int deleted = repo.deleteFromHistory(LocalDateTime.now(clock).minusDays(30));
      log.info(
          "Cleaned up {} old history records (took {}ms)",
          deleted,
          System.currentTimeMillis() - startTime);

      // Record the operation
      if (rateLimitingEnabled) {
        dbWindow.recordRequest();
      }

    } catch (Exception e) {
      log.error("Error during history cleanup", e);
    }
  }

  /**
   * Manual cleanup trigger with rate limiting - useful for testing/admin operations. Runs daily
   * audit (for completed events) and history cleanup (old records).
   */
  public CleanupResult runManualCleanup() {
    long startTime = System.currentTimeMillis();

    try {
      log.info("Manual cleanup triggered - running daily audit and history cleanup");

      // Run daily audit to archive any remaining completed events
      dailyCleanupAudit();

      // Run history cleanup to remove old records
      cleanUpHistory();

      long duration = System.currentTimeMillis() - startTime;
      CleanupResult result =
          new CleanupResult(true, duration, "Manual cleanup completed successfully");
      log.info("Manual cleanup completed: {}", result);
      return result;

    } catch (Exception e) {
      long duration = System.currentTimeMillis() - startTime;
      log.error("Manual cleanup failed after {}ms", duration, e);
      return new CleanupResult(false, duration, "Manual cleanup failed: " + e.getMessage());
    }
  }

  /** Check if we can process a history operation based on rate limits. */
  private boolean canProcessHistoryOperation() {
    if (!rateLimitingEnabled) {
      return true;
    }
    return historyTokenBucket.tryConsume() && historyWindow.tryRequest();
  }

  /** Check if we can process a database operation based on rate limits. */
  private boolean canProcessDatabaseOperation() {
    if (!rateLimitingEnabled) {
      return true;
    }
    return dbTokenBucket.tryConsume() && dbWindow.tryRequest();
  }

  /** Log rate limiter statistics for monitoring. */
  private void logRateLimiterStats() {
    log.debug(
        "Rate Limiter Stats - History: {} tokens available, {} window requests; DB: {} tokens available, {} window requests",
        historyTokenBucket.getAvailableTokens(),
        historyWindow.getCurrentCount(),
        dbTokenBucket.getAvailableTokens(),
        dbWindow.getCurrentCount());
  }

  /** Get cleanup health status for monitoring. */
  public CleanupHealth getCleanupHealth() {
    try {
      return new CleanupHealth(
          rateLimitingEnabled,
          historyTokenBucket.getStats(),
          historyWindow.getStats(),
          dbTokenBucket.getStats(),
          dbWindow.getStats());
    } catch (Exception e) {
      log.error("Error getting cleanup health", e);
      return new CleanupHealth(false, null, null, null, null);
    }
  }

  // ============ INNER CLASSES FOR RATE LIMITING ============

  /** Token Bucket Rate Limiter for burst control. */
  private static class TokenBucket {
    private final long capacity;
    private final long refillRate;
    private final AtomicLong tokens;
    private final AtomicReference<Instant> lastRefillTime;

    public TokenBucket(long capacity, long refillRate) {
      this.capacity = capacity;
      this.refillRate = refillRate;
      this.tokens = new AtomicLong(capacity);
      this.lastRefillTime = new AtomicReference<>(Instant.now());
    }

    public boolean tryConsume() {
      return tryConsume(1);
    }

    public boolean tryConsume(long tokensRequested) {
      if (tokensRequested <= 0) {
        return true;
      }
      if (tokensRequested > capacity) {
        return false;
      }

      refill();

      while (true) {
        long currentTokens = tokens.get();
        if (currentTokens < tokensRequested) {
          return false;
        }

        long newTokens = currentTokens - tokensRequested;
        if (tokens.compareAndSet(currentTokens, newTokens)) {
          return true;
        }
      }
    }

    public long getAvailableTokens() {
      refill();
      return tokens.get();
    }

    private void refill() {
      Instant now = Instant.now();
      Instant lastRefill = lastRefillTime.get();

      long elapsedMillis = now.toEpochMilli() - lastRefill.toEpochMilli();
      if (elapsedMillis <= 0) {
        return;
      }

      long tokensToAdd = (elapsedMillis * refillRate) / 1000;
      if (tokensToAdd <= 0) {
        return;
      }

      if (!lastRefillTime.compareAndSet(lastRefill, now)) {
        return;
      }

      while (true) {
        long currentTokens = tokens.get();
        long newTokens = Math.min(capacity, currentTokens + tokensToAdd);
        if (tokens.compareAndSet(currentTokens, newTokens)) {
          break;
        }
      }
    }

    public TokenBucketStats getStats() {
      refill();
      return new TokenBucketStats(capacity, refillRate, tokens.get());
    }
  }

  /** Sliding Window Rate Limiter for precise request counting. */
  private static class SlidingWindow {
    private final long windowSizeSeconds;
    private final long maxRequests;
    private final ConcurrentLinkedQueue<Long> requests;

    public SlidingWindow(long windowSizeSeconds, long maxRequests) {
      this.windowSizeSeconds = windowSizeSeconds;
      this.maxRequests = maxRequests;
      this.requests = new ConcurrentLinkedQueue<>();
    }

    public boolean tryRequest() {
      cleanup();

      if (requests.size() >= maxRequests) {
        return false;
      }

      requests.offer(System.currentTimeMillis());
      return true;
    }

    public void recordRequest() {
      cleanup();
      requests.offer(System.currentTimeMillis());
    }

    public long getCurrentCount() {
      cleanup();
      return requests.size();
    }

    private void cleanup() {
      long cutoff = System.currentTimeMillis() - (windowSizeSeconds * 1000);
      while (!requests.isEmpty() && requests.peek() < cutoff) {
        requests.poll();
      }
    }

    public SlidingWindowStats getStats() {
      cleanup();
      return new SlidingWindowStats(windowSizeSeconds, maxRequests, requests.size());
    }
  }

  // ============ RESULT AND STATS CLASSES ============

  public static class CleanupResult {
    private final boolean success;
    private final long durationMs;
    private final String message;

    public CleanupResult(boolean success, long durationMs, String message) {
      this.success = success;
      this.durationMs = durationMs;
      this.message = message;
    }

    public boolean isSuccess() {
      return success;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public String getMessage() {
      return message;
    }

    @Override
    public String toString() {
      return String.format(
          "CleanupResult{success=%s, duration=%dms, message='%s'}", success, durationMs, message);
    }
  }

  public static class CleanupHealth {
    private final boolean rateLimitingEnabled;
    private final TokenBucketStats historyTokenStats;
    private final SlidingWindowStats historyWindowStats;
    private final TokenBucketStats dbTokenStats;
    private final SlidingWindowStats dbWindowStats;

    public CleanupHealth(
        boolean rateLimitingEnabled,
        TokenBucketStats historyTokenStats,
        SlidingWindowStats historyWindowStats,
        TokenBucketStats dbTokenStats,
        SlidingWindowStats dbWindowStats) {
      this.rateLimitingEnabled = rateLimitingEnabled;
      this.historyTokenStats = historyTokenStats;
      this.historyWindowStats = historyWindowStats;
      this.dbTokenStats = dbTokenStats;
      this.dbWindowStats = dbWindowStats;
    }

    public boolean isRateLimitingEnabled() {
      return rateLimitingEnabled;
    }

    public TokenBucketStats getHistoryTokenStats() {
      return historyTokenStats;
    }

    public SlidingWindowStats getHistoryWindowStats() {
      return historyWindowStats;
    }

    public TokenBucketStats getDbTokenStats() {
      return dbTokenStats;
    }

    public SlidingWindowStats getDbWindowStats() {
      return dbWindowStats;
    }
  }

  public static class TokenBucketStats {
    private final long capacity;
    private final long refillRate;
    private final long currentTokens;

    public TokenBucketStats(long capacity, long refillRate, long currentTokens) {
      this.capacity = capacity;
      this.refillRate = refillRate;
      this.currentTokens = currentTokens;
    }

    public long getCapacity() {
      return capacity;
    }

    public long getRefillRate() {
      return refillRate;
    }

    public long getCurrentTokens() {
      return currentTokens;
    }

    public double getUtilizationPercent() {
      return ((double) (capacity - currentTokens) / capacity) * 100.0;
    }
  }

  public static class SlidingWindowStats {
    private final long windowSizeSeconds;
    private final long maxRequests;
    private final long currentRequests;

    public SlidingWindowStats(long windowSizeSeconds, long maxRequests, long currentRequests) {
      this.windowSizeSeconds = windowSizeSeconds;
      this.maxRequests = maxRequests;
      this.currentRequests = currentRequests;
    }

    public long getWindowSizeSeconds() {
      return windowSizeSeconds;
    }

    public long getMaxRequests() {
      return maxRequests;
    }

    public long getCurrentRequests() {
      return currentRequests;
    }

    public double getUtilizationPercent() {
      return ((double) currentRequests / maxRequests) * 100.0;
    }
  }
}
