package com.fourkites.event.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    Kafka kafka,
    Watchdog watchdog,
    Cleanup cleanup,
    Db db,
    Aws aws,
    Swagger swagger) {

  public record Kafka(
      String bootstrapServers,
      Topics topics,
      Integer dispatchMaxInflight,
      Consumer consumer) {
    public record Topics(String eventSubmission, String scheduledEvents, String timestampProcessing) { }
    public record Consumer(String groupId, Integer concurrency) { }
  }

  public record Watchdog(
      Integer stuckEventThresholdMinutes, Long checkIntervalMs, Long failedEventCheckIntervalMs, Integer batchSize) { }

  public record Cleanup(
      RateLimiting rateLimiting) {
    public record RateLimiting(
        boolean enabled,
        Bucket historyTokenBucket,
        Window historySlidingWindow,
        Bucket databaseTokenBucket,
        Window databaseSlidingWindow) { }
    public record Bucket(long capacity, long refillRate) { }
    public record Window(long windowMinutes, long maxOperations) { }
  }

  public record Db(Integer maxRetries, Long retryBaseDelayMs) { }

  public record Aws(String region) { }

  public record Swagger(String applicationName, String serverPort) { }
}
