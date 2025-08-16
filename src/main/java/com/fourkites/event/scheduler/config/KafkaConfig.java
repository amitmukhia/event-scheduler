package com.fourkites.event.scheduler.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Kafka configuration for manual acknowledgment mode. Required for the
 * TimestampProcessingKafkaListener to work properly.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

  private final String bootstrapServers;
  private final String groupId;

  public KafkaConfig(AppProperties appProps) {
    var kafka = appProps.kafka();
    this.bootstrapServers = kafka != null && kafka.bootstrapServers() != null ? kafka.bootstrapServers() : "localhost:9092";
    this.groupId = kafka != null && kafka.consumer() != null && kafka.consumer().groupId() != null ? kafka.consumer().groupId() : "timestamp-processors";
  }

  @Bean
  public ConsumerFactory<String, String> consumerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Disable auto-commit
    configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
    configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1024);
    configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);
    configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);

    return new DefaultKafkaConsumerFactory<>(configProps);
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());

    // Enable manual acknowledgment
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    // Set concurrency for better throughput
    factory.setConcurrency(3);

    return factory;
  }
}
