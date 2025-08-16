package com.fourkites.event.scheduler.repository;

import com.fourkites.event.scheduler.model.Event;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface EventRepository extends JpaRepository<Event, UUID> {

  // Find by customer and/or status for cancellation flows
  List<Event> findByCustomerId(UUID customerId);

  List<Event> findByCustomerIdAndStatusIn(UUID customerId, List<String> statuses);

  Page<Event> findAllByStatus(String status, Pageable pageable);

  /**
   * Optimized workflow selection query that: - Claims events due for processing with RETURNING -
   * Prevents stuck events by incrementing failure_count for PROCESSING events - Prevents missed
   * executions by precomputing next_run_time - Uses FOR UPDATE SKIP LOCKED for optimal concurrency
   */
  @Query(
      value =
          """
        UPDATE events
        SET status = 'PROCESSING',
            last_attempt_time = NOW(),
            updated_at = NOW(),
            failure_count = CASE
                WHEN status = 'PROCESSING' THEN failure_count + 1
                ELSE failure_count
            END
        WHERE event_id IN (
            SELECT event_id
            FROM events
            WHERE next_run_time <= :now
              AND status IN ('PENDING', 'PROCESSING')
              AND (expiration_time IS NULL OR expiration_time > :now)
              AND (status != 'PROCESSING' OR last_attempt_time < :stuckThreshold)
            ORDER BY next_run_time, created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
        )
        RETURNING *
        """,
      nativeQuery = true)
  List<Event> claimBatch(LocalDateTime now, int batchSize, LocalDateTime stuckThreshold);

  @Modifying
  @Transactional
  @Query(
      value =
          """
        UPDATE events
        SET status = 'PENDING',
            updated_at = NOW(),
            failure_count = failure_count + 1
        WHERE status = 'PROCESSING'
          AND last_attempt_time < :threshold
          AND failure_count < 3
        """,
      nativeQuery = true)
  int resetStuckEvents(LocalDateTime threshold);

  @Modifying
  @Transactional
  @Query(
      value =
          """
        DELETE FROM events
        WHERE status = 'COMPLETED'
          AND updated_at < :cutoffTime
        """,
      nativeQuery = true)
  int deleteCompletedEventsBefore(LocalDateTime cutoffTime);

  @Query(
      value =
          """
        SELECT * FROM events
        WHERE failure_count >= 3
          AND status <> 'FAILED'
        """,
      nativeQuery = true)
  List<Event> findEventsExceedingMaxRetries();

  @Modifying
  @Transactional
  @Query(
      value =
          """
        DELETE FROM event_history
        WHERE created_at < :cutoffTime
        """,
      nativeQuery = true)
  int deleteFromHistory(LocalDateTime cutoffTime);

  @Query(
      value =
          """
        SELECT DISTINCT next_run_time
        FROM events
        WHERE status = 'PENDING'
          AND next_run_time > :now
          AND next_run_time <= :oneHourFromNow
          AND (expiration_time IS NULL OR expiration_time > :now)
        ORDER BY next_run_time
        """,
      nativeQuery = true)
  List<LocalDateTime> findUniqueTimestampsWithinOneHour(
      LocalDateTime now, LocalDateTime oneHourFromNow);

  @Query(
      value =
          """
        SELECT * FROM events
        WHERE status = 'PENDING'
          AND next_run_time = :triggerTime
          AND (expiration_time IS NULL OR expiration_time > :now)
        ORDER BY next_run_time
        """,
      nativeQuery = true)
  List<Event> findEventsDueAtExactTime(LocalDateTime triggerTime, LocalDateTime now);

  /**
   * IMPROVED: Finds events due at or before the trigger time to catch up on missed events. This
   * ensures we don't miss events that should have been processed earlier. Used when Redis TTL keys
   * expire to process all overdue events, not just exact timestamp.
   */
  @Query(
      value =
          """
        SELECT * FROM events
        WHERE status = 'PENDING'
          AND next_run_time <= :triggerTime
          AND next_run_time <= :now
          AND (expiration_time IS NULL OR expiration_time > :now)
        ORDER BY next_run_time, created_at
        LIMIT 1000
        """,
      nativeQuery = true)
  List<Event> findEventsDueAtOrBefore(LocalDateTime triggerTime, LocalDateTime now);

  /**
   * Atomically claims a batch of PENDING events due at or before the given trigger time (and not
   * after now), marking them PROCESSING and returning the claimed rows. This eliminates race
   * conditions between fetch and update, and supports draining all newly-arrived events by iterating
   * until empty.
   */
  @Query(
      value =
          """
        UPDATE events
        SET status = 'PROCESSING',
            last_attempt_time = NOW(),
            updated_at = NOW()
        WHERE event_id IN (
            SELECT event_id
            FROM events
            WHERE next_run_time <= :triggerTime
              AND next_run_time <= :now
              AND status = 'PENDING'
              AND (expiration_time IS NULL OR expiration_time > :now)
            ORDER BY next_run_time, created_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
        )
        RETURNING *
        """,
      nativeQuery = true)
  List<Event> claimPendingBatchUpToTimestamp(
      LocalDateTime now, LocalDateTime triggerTime, int batchSize);

  @Query(
      value =
          """
        SELECT * FROM events
        WHERE status = 'PENDING'
          AND next_run_time = :triggerTime
          AND (expiration_time IS NULL OR expiration_time > :now)
        ORDER BY next_run_time
        """,
      nativeQuery = true)
  Page<Event> findEventsDueAtExactTimePaginated(
      LocalDateTime triggerTime, LocalDateTime now, Pageable pageable);

  @Query(
      value =
          """
        SELECT * FROM events
        WHERE status = :status
          AND next_run_time = :scheduledTime
          AND (expiration_time IS NULL OR expiration_time > NOW())
        ORDER BY next_run_time
        """,
      nativeQuery = true)
  List<Event> findByScheduledTimeAndStatus(LocalDateTime scheduledTime, String status);

  /**
   * Finds all unique timestamps for pending events within the specified time range. Used by Redis
   * TTL orchestration to create TTL keys for high-throughput processing.
   *
   * @param startTime The start of the time range (inclusive)
   * @param endTime The end of the time range (exclusive)
   * @return Set of unique timestamps within the range
   */
  @Query(
      value =
          """
        SELECT DISTINCT next_run_time
        FROM events
        WHERE status = 'PENDING'
          AND next_run_time >= :startTime
          AND next_run_time < :endTime
          AND (expiration_time IS NULL OR expiration_time > NOW())
        ORDER BY next_run_time
        """,
      nativeQuery = true)
  Set<LocalDateTime> findUniqueTimestampsInRange(LocalDateTime startTime, LocalDateTime endTime);

  /**
   * Resets stuck events for a specific customer to maintain isolation. Used by watchdog for
   * per-customer processing.
   */
  @Modifying
  @Transactional
  @Query(
      value =
          """
        UPDATE events
        SET status = 'PENDING',
            updated_at = NOW(),
            failure_count = failure_count + 1
        WHERE customer_id = :customerId
          AND status = 'PROCESSING'
          AND last_attempt_time < :threshold
          AND failure_count < 3
        """,
      nativeQuery = true)
  int resetStuckEventsForCustomer(UUID customerId, LocalDateTime threshold);

  /**
   * Counts the number of events currently stuck in PROCESSING state. Used by watchdog health
   * monitoring.
   */
  @Query(
      value =
          """
        SELECT COUNT(*)
        FROM events
        WHERE status = 'PROCESSING'
          AND last_attempt_time < :threshold
        """,
      nativeQuery = true)
  long countStuckEvents(LocalDateTime threshold);

  /**
   * Finds stuck events for detailed analysis and logging. Useful for monitoring and debugging stuck
   * event patterns.
   */
  @Query(
      value =
          """
        SELECT * FROM events
        WHERE status = 'PROCESSING'
          AND last_attempt_time < :threshold
        ORDER BY customer_id, last_attempt_time
        """,
      nativeQuery = true)
  List<Event> findStuckEvents(LocalDateTime threshold);

  /**
   * Finds stuck events for a specific customer. Used for customer-specific troubleshooting and
   * isolation.
   */
  @Query(
      value =
          """
        SELECT * FROM events
        WHERE customer_id = :customerId
          AND status = 'PROCESSING'
          AND last_attempt_time < :threshold
        ORDER BY last_attempt_time
        """,
      nativeQuery = true)
  List<Event> findStuckEventsForCustomer(UUID customerId, LocalDateTime threshold);

  /**
   * Gets statistics about stuck events grouped by customer. Useful for identifying problematic
   * customers or patterns.
   */
  @Query(
      value =
          """
        SELECT customer_id, COUNT(*) as stuck_count, MIN(last_attempt_time) as oldest_stuck
        FROM events
        WHERE status = 'PROCESSING'
          AND last_attempt_time < :threshold
        GROUP BY customer_id
        ORDER BY stuck_count DESC
        """,
      nativeQuery = true)
  List<Object[]> getStuckEventStatsByCustomer(LocalDateTime threshold);
}
