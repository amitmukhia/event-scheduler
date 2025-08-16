package com.fourkites.event.scheduler.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fourkites.event.scheduler.dto.CallbackConfig;
import com.fourkites.event.scheduler.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Dispatcher {
  private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
  private final KafkaDispatcher kafkaDispatcher;
  private final SqsDispatcher sqsDispatcher;
  private final ObjectMapper objectMapper;

  public Dispatcher(
      KafkaDispatcher kafkaDispatcher,
      @Autowired(required = false) SqsDispatcher sqsDispatcher,
      ObjectMapper objectMapper) {
    this.kafkaDispatcher = kafkaDispatcher;
    this.sqsDispatcher = sqsDispatcher;
    this.objectMapper = objectMapper;
  }

  public void send(Event e) {
    // Always use user-defined callback config; no fallback to application.yml
    final String key = e.getEventId().toString();

    if (e.getCallback() == null) {
      log.warn("No callback config present for event_id={}, nothing to dispatch", e.getEventId());
      return;
    }

    try {
      CallbackConfig cfg = objectMapper.treeToValue(e.getCallback(), CallbackConfig.class);
      if (cfg == null || !cfg.hasCallbacks()) {
        log.warn(
            "Callback config missing brokers for event_id={}, nothing to dispatch", e.getEventId());
        return;
      }

      // Build the outbound message body, optionally envelope with metadata
      String value = buildOutboundMessage(e, cfg);

      // Send to all Kafka topics
      for (String topic : cfg.getAllKafkaTopics()) {
        kafkaDispatcher.send(topic, key, value);
        log.info("Dispatched event_id={} to Kafka topic={}", e.getEventId(), topic);
      }

      // Send to all SQS queue names
      if (sqsDispatcher != null) {
        for (String queueName : cfg.getAllSQSTopics()) {
          sqsDispatcher.sendToQueueName(queueName, key, value);
          log.info("Dispatched event_id={} to SQS queueName={}", e.getEventId(), queueName);
        }
      }

    } catch (Exception ex) {
      log.error("FAILED dispatch for event_id={} error={}", e.getEventId(), ex.getMessage());
      throw new RuntimeException("Dispatch failed: " + ex.getMessage(), ex);
    }
  }

  private String buildOutboundMessage(Event e, CallbackConfig cfg) throws Exception {
    var root = objectMapper.createObjectNode();
    root.set("payload", e.getPayload());
    if (Boolean.TRUE.equals(cfg.includeMetadata())) {
      var meta = objectMapper.createObjectNode();
      meta.put("triggeredTime", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString());
      if (e.getNextRunTime() != null) {
        meta.put("scheduledTime", e.getNextRunTime().toString());
      } else if (e.getTriggerTime() != null) {
        meta.put("scheduledTime", e.getTriggerTime().toString());
      }
      int recurrenceCount = 0;
      if (e.getRecurrence() != null && e.getRecurrence().has("currentOccurrenceCount")) {
        recurrenceCount = e.getRecurrence().get("currentOccurrenceCount").asInt();
      }
      meta.put("recurrenceCount", recurrenceCount);

      root.set("metadata", meta);
    }
    root.put("sender", "event-scheduler");
    root.put("event_id", e.getEventId() != null ? e.getEventId().toString() : null);
    root.put("customer_id", e.getCustomerId() != null ? e.getCustomerId().toString() : null);
    return objectMapper.writeValueAsString(root);
  }
}
