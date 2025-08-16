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
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "failed_events")
@EntityListeners(ClockAwareEntityListener.class)
public class FailedEvent {

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

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "failed_at", nullable = false)
  private LocalDateTime failedAt;

  @Column(name = "event_mode", nullable = false)
  private String eventMode;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "recurrence")
  private JsonNode recurrence;

  @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  @Column(name = "callback")
  private JsonNode callback;

  // Additional field for failure analysis
  @Column(name = "failure_reason", nullable = false)
  private String failureReason;

  // Constructors
  public FailedEvent() { }

  public FailedEvent(Event originalEvent, String failureReason) {
    this.eventId = originalEvent.getEventId();
    this.customerId = originalEvent.getCustomerId();
    this.triggerTime = originalEvent.getTriggerTime();
    this.payload = originalEvent.getPayload();
    this.createdAt = originalEvent.getCreatedAt();
    this.eventMode = originalEvent.getEventMode();
    this.recurrence = originalEvent.getRecurrence();
    this.callback = originalEvent.getCallback();
    this.failureReason = failureReason;
  }

  // Getters and Setters
  public UUID getEventId() {
    return eventId;
  }

  public void setEventId(UUID eventId) {
    this.eventId = eventId;
  }

  public UUID getCustomerId() {
    return customerId;
  }

  public void setCustomerId(UUID customerId) {
    this.customerId = customerId;
  }

  public LocalDateTime getTriggerTime() {
    return triggerTime;
  }

  public void setTriggerTime(LocalDateTime triggerTime) {
    this.triggerTime = triggerTime;
  }

  public JsonNode getPayload() {
    return payload;
  }

  public void setPayload(JsonNode payload) {
    this.payload = payload;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getFailedAt() {
    return failedAt;
  }

  public void setFailedAt(LocalDateTime failedAt) {
    this.failedAt = failedAt;
  }

  public String getEventMode() {
    return eventMode;
  }

  public void setEventMode(String eventMode) {
    this.eventMode = eventMode;
  }

  public JsonNode getRecurrence() {
    return recurrence;
  }

  public void setRecurrence(JsonNode recurrence) {
    this.recurrence = recurrence;
  }

  public JsonNode getCallback() {
    return callback;
  }

  public void setCallback(JsonNode callback) {
    this.callback = callback;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String failureReason) {
    this.failureReason = failureReason;
  }
}
