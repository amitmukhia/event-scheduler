package com.fourkites.event.scheduler.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fourkites.event.scheduler.config.ClockAwareEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "events")
@EntityListeners(ClockAwareEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Event {

  @Version
  @Column(name = "version")
  private Long version;

  @Id
  @Column(name = "event_id", columnDefinition = "uuid")
  private UUID eventId;

  @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
  private UUID customerId;

  @Column(name = "trigger_time", nullable = false)
  private LocalDateTime triggerTime;

  @Column(name = "next_run_time", nullable = false)
  private LocalDateTime nextRunTime;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private JsonNode payload;

  @Column(name = "failure_count", nullable = false)
  private int failureCount = 0;

  @Column(name = "last_attempt_time")
  private LocalDateTime lastAttemptTime;

  @Column(name = "expiration_time")
  private LocalDateTime expirationTime;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "status", nullable = false)
  private String status; // PENDING, PROCESSING, COMPLETED, FAILED

  @Column(name = "event_mode")
  private String eventMode; // ONCE, RECURRING

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "recurrence")
  private JsonNode recurrence; // Stores the recurrence configuration as JSON

  @Column(name = "canceled_at")
  private LocalDateTime canceledAt;

  @Column(name = "cancelled_by")
  private String cancelledBy;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "callback")
  private JsonNode callback; // Stores callback configuration as JSON

  // Timestamp handling moved to ClockAwareEntityListener for consistent UTC time

}
