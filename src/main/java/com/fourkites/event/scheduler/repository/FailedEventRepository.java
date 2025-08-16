package com.fourkites.event.scheduler.repository;

import com.fourkites.event.scheduler.model.FailedEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface FailedEventRepository extends JpaRepository<FailedEvent, UUID> {

  /** Find failed events by customer for analysis. */
  List<FailedEvent> findByCustomerIdOrderByFailedAtDesc(UUID customerId);

  /** Clean up old failed events for storage management. */
  @Modifying
  @Transactional
  @Query(
      value =
          """
        DELETE FROM failed_events
        WHERE failed_at < :cutoffTime
        """,
      nativeQuery = true)
  int deleteFailedEventsBefore(LocalDateTime cutoffTime);

  /** Count failed events for monitoring. */
  @Query(
      value =
          """
        SELECT COUNT(*) FROM failed_events
        WHERE failed_at >= :since
        """,
      nativeQuery = true)
  long countFailedEventsSince(LocalDateTime since);
}
