package com.fourkites.event.scheduler.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fourkites.event.scheduler.model.Event;
import java.time.LocalDateTime;
import java.util.UUID;

public record EventResponse(
    UUID eventId,
    UUID customerId,
    LocalDateTime triggerTime,
    LocalDateTime nextRunTime,
    JsonNode payload,
    int failureCount,
    LocalDateTime lastAttemptTime,
    LocalDateTime expirationTime,
    String status,
    String eventMode,
    JsonNode recurrence,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {
  public static EventResponse from(Event e) {
    return new EventResponse(
        e.getEventId(),
        e.getCustomerId(),
        e.getTriggerTime(),
        e.getNextRunTime(),
        e.getPayload(),
        e.getFailureCount(),
        e.getLastAttemptTime(),
        e.getExpirationTime(),
        e.getStatus(),
        e.getEventMode(),
        e.getRecurrence(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
