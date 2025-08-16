package com.fourkites.event.scheduler.controller;

import com.fourkites.event.scheduler.jobs.Cleanup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for cleanup operations monitoring and manual control.
 *
 * <p>Features: - Health check endpoints for rate limiter status - Manual cleanup trigger for
 * administrative operations - Rate limiter statistics for monitoring
 */
@RestController
@RequestMapping("/api/cleanup")
public class CleanupController {
  private static final Logger log = LoggerFactory.getLogger(CleanupController.class);

  private final Cleanup cleanup;

  public CleanupController(Cleanup cleanup) {
    this.cleanup = cleanup;
  }

  /**
   * Get cleanup system health and rate limiter statistics.
   *
   * @return CleanupHealth with current rate limiter states
   */
  @GetMapping("/health")
  public ResponseEntity<Cleanup.CleanupHealth> getCleanupHealth() {
    try {
      Cleanup.CleanupHealth health = cleanup.getCleanupHealth();
      log.debug(
          "Cleanup health check requested - rate limiting enabled: {}",
          health.isRateLimitingEnabled());
      return ResponseEntity.ok(health);
    } catch (Exception e) {
      log.error("Error getting cleanup health", e);
      return ResponseEntity.internalServerError()
          .body(new Cleanup.CleanupHealth(false, null, null, null, null));
    }
  }

  /**
   * Manually trigger cleanup operations. Useful for administrative operations or testing.
   *
   * @return CleanupResult with operation details
   */
  @PostMapping("/manual")
  public ResponseEntity<Cleanup.CleanupResult> runManualCleanup() {
    try {
      log.info("Manual cleanup triggered via REST API");
      Cleanup.CleanupResult result = cleanup.runManualCleanup();

      if (result.isSuccess()) {
        return ResponseEntity.ok(result);
      } else {
        return ResponseEntity.internalServerError().body(result);
      }
    } catch (Exception e) {
      log.error("Error during manual cleanup trigger", e);
      Cleanup.CleanupResult errorResult =
          new Cleanup.CleanupResult(false, 0, "Manual cleanup failed: " + e.getMessage());
      return ResponseEntity.internalServerError().body(errorResult);
    }
  }

  /**
   * Get detailed rate limiter statistics for monitoring dashboards.
   *
   * @return Detailed statistics about token buckets and sliding windows
   */
  @GetMapping("/stats")
  public ResponseEntity<?> getRateLimiterStats() {
    try {
      Cleanup.CleanupHealth health = cleanup.getCleanupHealth();

      return ResponseEntity.ok(
          new CleanupStatsResponse(
              health.isRateLimitingEnabled(),
              health.getHistoryTokenStats(),
              health.getHistoryWindowStats(),
              health.getDbTokenStats(),
              health.getDbWindowStats()));
    } catch (Exception e) {
      log.error("Error getting rate limiter stats", e);
      return ResponseEntity.internalServerError()
          .body("Error retrieving rate limiter statistics: " + e.getMessage());
    }
  }

  /** Response class for detailed cleanup statistics. */
  public static class CleanupStatsResponse {
    private final boolean rateLimitingEnabled;
    private final TokenBucketStatsResponse historyTokenBucket;
    private final SlidingWindowStatsResponse historyWindow;
    private final TokenBucketStatsResponse databaseTokenBucket;
    private final SlidingWindowStatsResponse databaseWindow;

    public CleanupStatsResponse(
        boolean rateLimitingEnabled,
        Cleanup.TokenBucketStats historyTokenStats,
        Cleanup.SlidingWindowStats historyWindowStats,
        Cleanup.TokenBucketStats dbTokenStats,
        Cleanup.SlidingWindowStats dbWindowStats) {
      this.rateLimitingEnabled = rateLimitingEnabled;
      this.historyTokenBucket =
          historyTokenStats != null ? new TokenBucketStatsResponse(historyTokenStats) : null;
      this.historyWindow =
          historyWindowStats != null ? new SlidingWindowStatsResponse(historyWindowStats) : null;
      this.databaseTokenBucket =
          dbTokenStats != null ? new TokenBucketStatsResponse(dbTokenStats) : null;
      this.databaseWindow =
          dbWindowStats != null ? new SlidingWindowStatsResponse(dbWindowStats) : null;
    }

    public boolean isRateLimitingEnabled() {
      return rateLimitingEnabled;
    }

    public TokenBucketStatsResponse getHistoryTokenBucket() {
      return historyTokenBucket;
    }

    public SlidingWindowStatsResponse getHistoryWindow() {
      return historyWindow;
    }

    public TokenBucketStatsResponse getDatabaseTokenBucket() {
      return databaseTokenBucket;
    }

    public SlidingWindowStatsResponse getDatabaseWindow() {
      return databaseWindow;
    }
  }

  public static class TokenBucketStatsResponse {
    private final long capacity;
    private final long refillRate;
    private final long currentTokens;
    private final double utilizationPercent;

    public TokenBucketStatsResponse(Cleanup.TokenBucketStats stats) {
      this.capacity = stats.getCapacity();
      this.refillRate = stats.getRefillRate();
      this.currentTokens = stats.getCurrentTokens();
      this.utilizationPercent = stats.getUtilizationPercent();
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
      return utilizationPercent;
    }
  }

  public static class SlidingWindowStatsResponse {
    private final long windowSizeSeconds;
    private final long maxRequests;
    private final long currentRequests;
    private final double utilizationPercent;

    public SlidingWindowStatsResponse(Cleanup.SlidingWindowStats stats) {
      this.windowSizeSeconds = stats.getWindowSizeSeconds();
      this.maxRequests = stats.getMaxRequests();
      this.currentRequests = stats.getCurrentRequests();
      this.utilizationPercent = stats.getUtilizationPercent();
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
      return utilizationPercent;
    }
  }
}
