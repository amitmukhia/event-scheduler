package com.fourkites.event.scheduler.dispatcher;

import jakarta.annotation.PostConstruct;
import java.util.Properties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class KafkaDispatcher {
  private static final Logger logger = LoggerFactory.getLogger(KafkaDispatcher.class);

  private final String bootstrapServers;

  private Producer<String, String> producer;

  public KafkaDispatcher(com.fourkites.event.scheduler.config.AppProperties appProps) {
    this.bootstrapServers =
        appProps.kafka() != null && appProps.kafka().bootstrapServers() != null
            ? appProps.kafka().bootstrapServers()
            : "localhost:9092";
  }

  @PostConstruct
  public void init() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    this.producer = new KafkaProducer<>(props);
    logger.info("Kafka producer initialized with bootstrap servers: {}", bootstrapServers);
  }

  public void send(String topic, String key, String value) {
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
    producer.send(
        record,
        (metadata, exception) -> {
          if (exception == null) {
            logger.info(
                "Sent message to topic {} with key {} and offset {}",
                topic,
                key,
                metadata.offset());
          } else {
            logger.error("Error sending message to Kafka", exception);
          }
        });
  }
}
