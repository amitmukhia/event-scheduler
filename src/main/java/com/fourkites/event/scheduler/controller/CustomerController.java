package com.fourkites.event.scheduler.controller;

import com.fourkites.event.scheduler.dto.CreateCustomerRequest;
import com.fourkites.event.scheduler.dto.CustomerResponse;
import com.fourkites.event.scheduler.model.Customer;
import com.fourkites.event.scheduler.repository.CustomerRepository;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

  private final CustomerRepository customerRepository;

  @PostMapping
  public ResponseEntity<CustomerResponse> createCustomer(
      @Valid @RequestBody CreateCustomerRequest request) {
    Customer customer = new Customer();
    customer.setName(request.name());
    customer.setEmail(request.email());

    Customer savedCustomer = customerRepository.save(customer);
    return ResponseEntity.status(HttpStatus.CREATED).body(CustomerResponse.from(savedCustomer));
  }

  @GetMapping("/{id}")
  public ResponseEntity<CustomerResponse> getCustomer(@PathVariable UUID id) {
    return customerRepository
        .findById(id)
        .map(customer -> ResponseEntity.ok(CustomerResponse.from(customer)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping
  public ResponseEntity<Page<CustomerResponse>> listCustomers(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "createdAt,desc") String sort) {

    String[] sortParams = sort.split(",");
    String sortField = sortParams[0];
    String sortDirection = sortParams.length > 1 ? sortParams[1] : "asc";

    Sort sorting = Sort.by(Sort.Direction.fromString(sortDirection), sortField);
    PageRequest pageRequest = PageRequest.of(page, size, sorting);

    Page<CustomerResponse> customers =
        customerRepository.findAll(pageRequest).map(CustomerResponse::from);

    return ResponseEntity.ok(customers);
  }
}
