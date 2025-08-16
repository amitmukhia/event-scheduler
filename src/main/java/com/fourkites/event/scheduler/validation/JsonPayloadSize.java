package com.fourkites.event.scheduler.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom validation annotation to limit JSON payload size. This file also contains the validator
 * implementation to keep validation in a single file.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = JsonPayloadSize.Validator.class)
@Documented
public @interface JsonPayloadSize {
  String message() default "JSON payload exceeds maximum allowed size";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Maximum allowed size in kilobytes */
  int maxSizeKB() default 32;

  /**
   * Validator implementation for JsonPayloadSize annotation Declared as a nested type so it can
   * live in the same file.
   */
  public static class Validator implements ConstraintValidator<JsonPayloadSize, JsonNode> {

    private static final Logger log = LoggerFactory.getLogger(Validator.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private int maxSizeKB;

    @Override
    public void initialize(JsonPayloadSize constraintAnnotation) {
      this.maxSizeKB = constraintAnnotation.maxSizeKB();
    }

    @Override
    public boolean isValid(JsonNode jsonNode, ConstraintValidatorContext context) {
      if (jsonNode == null) {
        return true; // Let @NotNull handle null validation
      }

      try {
        // Convert JsonNode to string and measure size
        String jsonString = objectMapper.writeValueAsString(jsonNode);
        int sizeBytes = jsonString.getBytes("UTF-8").length;
        int maxSizeBytes = maxSizeKB * 1024;

        if (sizeBytes > maxSizeBytes) {
          log.warn(
              "JSON payload size {} bytes exceeds limit of {} bytes ({}KB)",
              sizeBytes,
              maxSizeBytes,
              maxSizeKB);

          // Add detailed message to context
          context.disableDefaultConstraintViolation();
          context
              .buildConstraintViolationWithTemplate(
                  String.format(
                      "JSON payload size %d bytes exceeds maximum allowed size of %dKB",
                      sizeBytes, maxSizeKB))
              .addConstraintViolation();
          return false;
        }

        // Additional security checks
        return performSecurityValidation(jsonNode, context);

      } catch (JsonProcessingException e) {
        log.error("Failed to process JSON payload for size validation", e);
        context.disableDefaultConstraintViolation();
        context
            .buildConstraintViolationWithTemplate("Invalid JSON payload format")
            .addConstraintViolation();
        return false;
      } catch (Exception e) {
        log.error("Unexpected error during JSON payload validation", e);
        return false;
      }
    }

    /** Perform additional security validation on JSON payload */
    private boolean performSecurityValidation(
        JsonNode jsonNode, ConstraintValidatorContext context) {
      try {
        // Check for deeply nested objects (JSON bomb protection)
        if (getMaxDepth(jsonNode) > 10) {
          context.disableDefaultConstraintViolation();
          context
              .buildConstraintViolationWithTemplate(
                  "JSON payload nesting depth exceeds maximum allowed (10 levels)")
              .addConstraintViolation();
          return false;
        }

        // Check for excessive number of fields
        if (countTotalFields(jsonNode) > 100) {
          context.disableDefaultConstraintViolation();
          context
              .buildConstraintViolationWithTemplate(
                  "JSON payload contains too many fields (maximum 100 allowed)")
              .addConstraintViolation();
          return false;
        }

        return true;

      } catch (Exception e) {
        log.error("Error during security validation of JSON payload", e);
        return false;
      }
    }

    /** Calculate maximum nesting depth of JSON */
    private int getMaxDepth(JsonNode node) {
      if (node == null || node.isValueNode()) {
        return 1;
      }

      int maxChildDepth = 0;
      if (node.isArray()) {
        for (JsonNode child : node) {
          maxChildDepth = Math.max(maxChildDepth, getMaxDepth(child));
        }
      } else if (node.isObject()) {
        var iterator = node.fields();
        while (iterator.hasNext()) {
          var entry = iterator.next();
          int depth = getMaxDepth(entry.getValue());
          maxChildDepth = Math.max(maxChildDepth, depth);
        }
      }

      return 1 + maxChildDepth;
    }

    /** Count total number of fields in JSON */
    private int countTotalFields(JsonNode node) {
      if (node == null || node.isValueNode()) {
        return 1;
      }

      int totalFields = 0;
      if (node.isArray()) {
        for (JsonNode child : node) {
          totalFields += countTotalFields(child);
        }
      } else if (node.isObject()) {
        totalFields = node.size(); // Count immediate fields
        var iterator = node.fields();
        while (iterator.hasNext()) {
          var entry = iterator.next();
          totalFields += countTotalFields(entry.getValue());
        }
      }

      return totalFields;
    }
  }
}
