package com.fourkites.event.scheduler.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fourkites.event.scheduler.config.ClockAwareEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "event_history")
@EntityListeners(ClockAwareEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class EventHistory {

  // In fresh schema, event_id is the primary key in event_history
  @Id
  @Column(name = "event_id", columnDefinition = "uuid")
  private UUID eventId;

  @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
  private UUID customerId;

  @Column(name = "trigger_time", nullable = false)
  private LocalDateTime triggerTime;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private JsonNode payload;

  @Column(name = "completion_time", nullable = false)
  private LocalDateTime completionTime;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "event_mode", nullable = false)
  private String eventMode;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "recurrence")
  private JsonNode recurrence;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "callback")
  private JsonNode callback;

  @Column(name = "cancelled_by")
  private String cancelledBy;

  // Getters/Setters
}
