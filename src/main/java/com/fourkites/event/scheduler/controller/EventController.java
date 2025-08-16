package com.fourkites.event.scheduler.controller;

import com.fourkites.event.scheduler.dto.CreateEventRequest;
import com.fourkites.event.scheduler.dto.EventResponse;
import com.fourkites.event.scheduler.infrastructure.redis.RedisCoordinator;
import com.fourkites.event.scheduler.model.Event;
import com.fourkites.event.scheduler.repository.EventRepository;
import com.fourkites.event.scheduler.service.EventService;
import com.fourkites.event.scheduler.service.EventSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Events", description = "Event scheduling and management API")
@RequiredArgsConstructor
public class EventController {

  // Inline request type to avoid creating a new file
  public static class RescheduleBody {
    public java.time.LocalDateTime triggerTime;
    public String mode; // Optional: ONCE or RECURRING
    public com.fourkites.event.scheduler.dto.CreateEventRequest.Recurrence
        recurrence; // Optional partial fields

    public boolean hasAny() {
      boolean recurrenceHasAny = false;
      if (recurrence != null) {
        recurrenceHasAny =
            recurrence.infinite() != null
                || recurrence.delay() != null
                || recurrence.timeUnit() != null
                || recurrence.maxOccurrences() != null
                || recurrence.currentOccurrenceCount() != null;
      }
      return triggerTime != null || mode != null || recurrenceHasAny;
    }
  }

  private final EventService eventService;
  private final EventRepository eventRepository;
  private final EventSubmissionService eventSubmissionService;
  private final RedisCoordinator eventRedisService;
  private final com.fourkites.event.scheduler.repository.CustomerRepository customerRepository;

  @PostMapping
  @Operation(
      summary = "Create a new event",
      description = "Schedule a new event with optional recurrence settings")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "201", description = "Event created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid request data"),
        @ApiResponse(responseCode = "404", description = "Customer not found")
      })
  public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
    Event event = eventService.create(request);
    EventResponse response = EventResponse.from(event);
    return ResponseEntity.created(java.net.URI.create("/api/events/" + event.getEventId()))
        .body(response);
  }

  @PostMapping("/v2")
  public ResponseEntity<EventResponse> createEventV2(
      @Valid @RequestBody CreateEventRequest request) {
    // Use enhanced submission service for better scalability
    Event event = eventSubmissionService.submitEvent(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(EventResponse.from(event));
  }

  @PostMapping("/batch")
  public ResponseEntity<Map<String, Object>> createEventsBatch(
      @Valid @RequestBody List<CreateEventRequest> requests) {
    // Batch event creation for high-throughput scenarios
    List<Event> events = new ArrayList<>();
    List<String> errors = new ArrayList<>();

    for (int i = 0; i < requests.size(); i++) {
      try {
        Event event = eventService.create(requests.get(i));
        events.add(event);
      } catch (Exception e) {
        errors.add("Request[" + i + "]: " + e.getMessage());
      }
    }

    Map<String, Object> response = new HashMap<>();
    response.put("created", events.size());
    response.put("failed", errors.size());
    response.put("errors", errors);
    response.put("events", events.stream().map(EventResponse::from).collect(Collectors.toList()));

    return ResponseEntity.ok(response);
  }

  @GetMapping("/{id}")
  @Operation(
      summary = "Get event by ID",
      description = "Retrieve detailed information about a specific event")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "Event found"),
        @ApiResponse(responseCode = "404", description = "Event not found")
      })
  public ResponseEntity<EventResponse> getEvent(
      @Parameter(description = "Event ID", required = true) @PathVariable UUID id) {
    return eventRepository
        .findById(id)
        .map(event -> ResponseEntity.ok(EventResponse.from(event)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  public ResponseEntity<Page<EventResponse>> listEvents(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "nextRunTime,asc") String sort) {

    String[] sortParams = sort.split(",");
    String sortField = sortParams[0];
    String sortDirection = sortParams.length > 1 ? sortParams[1] : "asc";

    Sort sorting = Sort.by(Sort.Direction.fromString(sortDirection), sortField);
    PageRequest pageRequest = PageRequest.of(page, size, sorting);

    Page<EventResponse> events = eventRepository.findAll(pageRequest).map(EventResponse::from);

    return ResponseEntity.ok(events);
  }

@PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> cancelById(
      @PathVariable UUID id, @RequestParam(name = "cancelledBy") String cancelledByEmail) {
    if (cancelledByEmail == null || cancelledByEmail.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              Map.of(
                  "error", "MISSING_EMAIL",
                  "message", "cancelledBy email is required"));
    }
    boolean exists = customerRepository.findByEmail(cancelledByEmail).isPresent();
    if (!exists) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              Map.of(
                  "error", "INVALID_EMAIL",
                  "message", "cancelledBy email not found in customers",
                  "email", cancelledByEmail));
    }
    boolean cancelled = eventService.cancelEventById(id, cancelledByEmail);
    Map<String, Object> body = new HashMap<>();
    body.put("eventId", id);
    body.put("cancelled", cancelled);
    return ResponseEntity.ok(body);
  }

@PostMapping(value = "/cancel/by-customer/{customerId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> cancelByCustomer(
      @PathVariable UUID customerId, @RequestParam(name = "cancelledBy") String cancelledByEmail) {
    if (cancelledByEmail == null || cancelledByEmail.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              Map.of(
                  "error", "MISSING_EMAIL",
                  "message", "cancelledBy email is required"));
    }
    boolean exists = customerRepository.findByEmail(cancelledByEmail).isPresent();
    if (!exists) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(
              Map.of(
                  "error", "INVALID_EMAIL",
                  "message", "cancelledBy email not found in customers",
                  "email", cancelledByEmail));
    }
    int count = eventService.cancelEventsByCustomer(customerId, cancelledByEmail);
    Map<String, Object> body = new HashMap<>();
    body.put("customerId", customerId);
    body.put("cancelledCount", count);
    return ResponseEntity.ok(body);
  }

  @PatchMapping("/{id}/reschedule")
  public ResponseEntity<EventResponse> reschedule(
      @PathVariable UUID id, @RequestBody RescheduleBody body) {
    if (body == null || !body.hasAny()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      return eventService
          .rescheduleEvent(id, body.triggerTime, body.mode, body.recurrence)
          .map(EventResponse::from)
          .map(ResponseEntity::ok)
          .orElseGet(() -> ResponseEntity.notFound().build());
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
  }

  @GetMapping("/redis/stats")
  public ResponseEntity<Map<String, Object>> getRedisStats() {
    // Get stats about Redis operations
    long imminentCount = eventRedisService.getImminentEventCount();
    long timestampKeyCount = eventRedisService.getTimestampKeyCount();

    Map<String, Object> stats = new HashMap<>();
    stats.put("imminentEventsInRedis", imminentCount);
    stats.put("timestampKeysInRedis", timestampKeyCount);
    stats.put("timestamp", java.time.LocalDateTime.now().toString());

    return ResponseEntity.ok(stats);
  }
}
