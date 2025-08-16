package com.fourkites.event.scheduler.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@ControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Handle Bean Validation errors (JSR-303) */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    log.warn("Validation failed for request: {}", ex.getMessage());

    Map<String, Object> body = new HashMap<>();
    body.put("error", "VALIDATION_FAILED");
    body.put("message", "Input validation failed. Please check the provided data.");
    body.put("timestamp", LocalDateTime.now().toString());

    // Collect field-specific validation errors
    Map<String, String> fieldErrors = new HashMap<>();
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
    }
    body.put("fieldErrors", fieldErrors);

    // Log security-related validation failures
    for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
      if (fieldError.getDefaultMessage().contains("exceeds")
          || fieldError.getDefaultMessage().contains("size")
          || fieldError.getDefaultMessage().contains("depth")) {
        log.warn(
            "Security validation triggered - Field: {}, Error: {}",
            fieldError.getField(),
            fieldError.getDefaultMessage());
      }
    }

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** Handle constraint violations */
  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<Map<String, Object>> handleConstraintViolation(
      ConstraintViolationException ex) {
    log.warn("Constraint validation failed: {}", ex.getMessage());

    Map<String, Object> body = new HashMap<>();
    body.put("error", "CONSTRAINT_VIOLATION");
    body.put("message", "Data constraints violated");
    body.put("timestamp", LocalDateTime.now().toString());

    Map<String, String> violations =
        ex.getConstraintViolations().stream()
            .collect(
                Collectors.toMap(
                    violation -> violation.getPropertyPath().toString(),
                    ConstraintViolation::getMessage,
                    (existing, replacement) -> existing // Keep first message if duplicate paths
                    ));

    body.put("violations", violations);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** Handle type conversion errors (e.g., invalid UUID format) */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Map<String, Object>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    log.warn("Type conversion failed for parameter '{}': {}", ex.getName(), ex.getMessage());

    Map<String, Object> body = new HashMap<>();
    body.put("error", "INVALID_FORMAT");
    body.put(
        "message",
        String.format(
            "Invalid format for parameter '%s'. Expected type: %s",
            ex.getName(), ex.getRequiredType().getSimpleName()));
    body.put("timestamp", LocalDateTime.now().toString());
    body.put("parameter", ex.getName());
    body.put("providedValue", ex.getValue());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** Handle illegal argument exceptions (business logic validation) */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
    log.warn("Business rule validation failed: {}", ex.getMessage());

    Map<String, Object> body = new HashMap<>();
    body.put("error", "BUSINESS_RULE_VIOLATION");
    body.put("message", ex.getMessage());
    body.put("timestamp", LocalDateTime.now().toString());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** Handle all other exceptions */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
    log.error("Unexpected error occurred", ex);

    Map<String, Object> body = new HashMap<>();
    body.put("error", "INTERNAL_SERVER_ERROR");
    body.put("message", "An unexpected error occurred. Please try again later.");
    body.put("timestamp", LocalDateTime.now().toString());

    // Don't expose internal error details in production
    if (log.isDebugEnabled()) {
      body.put("debugMessage", ex.getMessage());
    }

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
  }
}
