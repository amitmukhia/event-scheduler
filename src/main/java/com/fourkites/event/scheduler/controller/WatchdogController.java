package com.fourkites.event.scheduler.controller;

import com.fourkites.event.scheduler.jobs.Watchdog;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Watchdog monitoring and operations. Provides endpoints to monitor watchdog
 * health and manually trigger operations.
 */
@RestController
@RequestMapping("/api/watchdog")
@RequiredArgsConstructor
public class WatchdogController {

  private static final Logger log = LoggerFactory.getLogger(WatchdogController.class);

  private final Watchdog watchdog;

  /** Get watchdog health status and statistics. */
  @GetMapping("/health")
  public ResponseEntity<Watchdog.WatchdogHealth> getHealth() {
    try {
      Watchdog.WatchdogHealth health = watchdog.getWatchdogHealth();
      return ResponseEntity.ok(health);
    } catch (Exception e) {
      log.error("Error getting watchdog health", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Manually trigger a full watchdog cycle. */
  @PostMapping("/trigger")
  public ResponseEntity<Watchdog.WatchdogResult> triggerWatchdog() {
    try {
      log.info("Manual watchdog trigger requested via REST API");
      Watchdog.WatchdogResult result = watchdog.runManualWatchdog();

      if (result.isSuccess()) {
        return ResponseEntity.ok(result);
      } else {
        return ResponseEntity.internalServerError().body(result);
      }

    } catch (Exception e) {
      log.error("Error during manual watchdog trigger", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /** Reset stuck events for a specific customer. */
  @PostMapping("/reset-customer/{customerId}")
  public ResponseEntity<Watchdog.WatchdogResult> resetStuckEventsForCustomer(
      @PathVariable UUID customerId) {
    try {
      log.info("Manual watchdog reset requested for customer: {}", customerId);
      Watchdog.WatchdogResult result = watchdog.resetStuckEventsPerCustomer(customerId);

      if (result.isSuccess()) {
        return ResponseEntity.ok(result);
      } else {
        return ResponseEntity.internalServerError().body(result);
      }

    } catch (Exception e) {
      log.error("Error during customer-specific watchdog reset for customer {}", customerId, e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
