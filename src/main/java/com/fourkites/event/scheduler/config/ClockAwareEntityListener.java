package com.fourkites.event.scheduler.config;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA Entity Listener that uses the centralized Clock bean for timestamps. This ensures all entity
 * timestamps use UTC consistently.
 */
@Component
public class ClockAwareEntityListener {

  private static Clock clock;

  /**
   * Spring will inject the Clock bean here. Static injection is needed because JPA creates entity
   * listener instances.
   */
  @Autowired
  public void setClock(Clock clock) {
    ClockAwareEntityListener.clock = clock;
  }

  @PrePersist
  public void onPrePersist(Object entity) {
    LocalDateTime now = LocalDateTime.now(clock != null ? clock : Clock.systemUTC());

    if (entity instanceof com.fourkites.event.scheduler.model.Event) {
      com.fourkites.event.scheduler.model.Event event =
          (com.fourkites.event.scheduler.model.Event) entity;
      if (event.getEventId() == null) {
        event.setEventId(java.util.UUID.randomUUID());
      }
      event.setCreatedAt(now);
      event.setUpdatedAt(now);
    } else if (entity instanceof com.fourkites.event.scheduler.model.EventHistory) {
      com.fourkites.event.scheduler.model.EventHistory history =
          (com.fourkites.event.scheduler.model.EventHistory) entity;
      if (history.getCreatedAt() == null) {
        history.setCreatedAt(now);
      }
      if (history.getCompletionTime() == null) {
        history.setCompletionTime(now);
      }
    } else if (entity instanceof com.fourkites.event.scheduler.model.FailedEvent) {
      com.fourkites.event.scheduler.model.FailedEvent failed =
          (com.fourkites.event.scheduler.model.FailedEvent) entity;
      if (failed.getFailedAt() == null) {
        failed.setFailedAt(now);
      }
    } else if (entity instanceof com.fourkites.event.scheduler.model.Customer) {
      com.fourkites.event.scheduler.model.Customer customer =
          (com.fourkites.event.scheduler.model.Customer) entity;
      if (customer.getCreatedAt() == null) {
        customer.setCreatedAt(now);
      }
    }
  }

  @PreUpdate
  public void onPreUpdate(Object entity) {
    LocalDateTime now = LocalDateTime.now(clock != null ? clock : Clock.systemUTC());

    if (entity instanceof com.fourkites.event.scheduler.model.Event) {
      com.fourkites.event.scheduler.model.Event event =
          (com.fourkites.event.scheduler.model.Event) entity;
      event.setUpdatedAt(now);
    }
  }
}
