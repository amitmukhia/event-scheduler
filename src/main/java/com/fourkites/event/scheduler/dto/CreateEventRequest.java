package com.fourkites.event.scheduler.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fourkites.event.scheduler.validation.JsonPayloadSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDateTime;
import java.util.UUID;

public record CreateEventRequest(
    @NotNull(message = "Customer ID is required") UUID customerId,

    // Optional; if null, the service will use current time
    LocalDateTime triggerTime,
    @NotNull(message = "Event mode is required")
        @Pattern(
            regexp = "^(ONCE|RECURRING)$",
            message = "Event mode must be either ONCE or RECURRING")
        String mode,
    @Valid Recurrence recurrence,
    @Future(message = "Expiration time must be in the future") LocalDateTime expirationTime,
    @NotNull(message = "Payload is required")
        @JsonPayloadSize(maxSizeKB = 64, message = "Payload size cannot exceed 64KB")
        JsonNode payload,
    @Valid CallbackConfig callback) {
  public static record Recurrence(
      @NotNull(message = "Infinite flag is required") Boolean infinite,
      @Min(value = 1, message = "Delay must be at least 1")
          @Max(value = 86400, message = "Delay cannot exceed 24 hours worth of seconds")
          Integer delay,
      @NotNull(message = "Time unit is required")
          @Pattern(
              regexp = "^(SECONDS|MINUTES|HOURS|DAYS)$",
              message = "Time unit must be one of: SECONDS, MINUTES, HOURS, DAYS")
          String timeUnit,
      @Min(value = 1, message = "Max occurrences must be at least 1 when not infinite")
          Integer maxOccurrences,
      @Min(value = 0, message = "Current occurrence count cannot be negative")
          Integer currentOccurrenceCount) { }
}
