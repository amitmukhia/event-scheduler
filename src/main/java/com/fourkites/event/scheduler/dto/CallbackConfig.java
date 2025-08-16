package com.fourkites.event.scheduler.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Callback configuration for event processing results. Allows users to specify message broker type
 * and topics for callbacks.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallbackConfig(
    @JsonProperty("includeMetadata") Boolean includeMetadata, // Optional, defaults to true
    @JsonProperty("messagebrokers")
        @Valid
        @Size(max = 5, message = "Maximum 5 message brokers allowed")
        List<MessageBroker> messagebrokers) {

  /** Constructor with default value for includeMetadata */
  public CallbackConfig {
    if (includeMetadata == null) {
      includeMetadata = true;
    }
  }

  /** Message broker configuration */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static record MessageBroker(
      @JsonProperty("type")
          @NotNull(message = "Message broker type is required")
          @Pattern(regexp = "^(SQS|KAFKA)$", message = "Type must be either SQS or KAFKA")
          String type,
      @JsonProperty("topics")
          @NotEmpty(message = "At least one topic must be specified")
          @Size(max = 10, message = "Maximum 10 topics allowed per broker")
List<
                  @NotBlank(message = "Topic name cannot be blank")
                  @Size(max = 255, message = "Topic name cannot exceed 255 characters") String>
              topics) { }

  /** Check if callbacks are configured */
  public boolean hasCallbacks() {
    return messagebrokers != null && !messagebrokers.isEmpty();
  }

  /** Check if SQS callbacks are configured */
  public boolean hasSQSCallbacks() {
    return messagebrokers != null
        && messagebrokers.stream().anyMatch(mb -> "SQS".equals(mb.type()));
  }

  /** Check if Kafka callbacks are configured */
  public boolean hasKafkaCallbacks() {
    return messagebrokers != null
        && messagebrokers.stream().anyMatch(mb -> "KAFKA".equals(mb.type()));
  }

  /** Get all SQS message brokers */
  public List<MessageBroker> getSQSBrokers() {
    return messagebrokers == null
        ? List.of()
        : messagebrokers.stream().filter(mb -> "SQS".equals(mb.type())).toList();
  }

  /** Get all Kafka message brokers */
  public List<MessageBroker> getKafkaBrokers() {
    return messagebrokers == null
        ? List.of()
        : messagebrokers.stream().filter(mb -> "KAFKA".equals(mb.type())).toList();
  }

  /** Get all SQS topics (flattened) */
  public List<String> getAllSQSTopics() {
    return getSQSBrokers().stream().flatMap(mb -> mb.topics().stream()).distinct().toList();
  }

  /** Get all Kafka topics (flattened) */
  public List<String> getAllKafkaTopics() {
    return getKafkaBrokers().stream().flatMap(mb -> mb.topics().stream()).distinct().toList();
  }
}
