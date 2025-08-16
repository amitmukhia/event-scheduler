package com.fourkites.event.scheduler.model;

import com.fourkites.event.scheduler.config.ClockAwareEntityListener;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers")
@EntityListeners(ClockAwareEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
public class Customer {

  @Id
  @GeneratedValue
  @org.hibernate.annotations.UuidGenerator
  @Column(name = "customer_id", columnDefinition = "uuid")
  private UUID customerId;

  private String name;
  private String email;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;
}
