package com.fourkites.event.scheduler.dto;

import com.fourkites.event.scheduler.model.Customer;
import java.time.LocalDateTime;
import java.util.UUID;

public record CustomerResponse(
    UUID customerId, String name, String email, LocalDateTime createdAt) {
  public static CustomerResponse from(Customer customer) {
    return new CustomerResponse(
        customer.getCustomerId(), customer.getName(), customer.getEmail(), customer.getCreatedAt());
  }
}
