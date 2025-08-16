package com.fourkites.event.scheduler.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(name = "aws.sqs.enabled", havingValue = "true", matchIfMissing = false)
public class SqsDispatcher {
  private static final Logger log = LoggerFactory.getLogger(SqsDispatcher.class);
  private final SqsClient sqsClient;
  private String queueUrl;

  public SqsDispatcher(SqsClient sqsClient) {
    this.sqsClient = sqsClient;
  }

  // Send to a queue by name provided at call-time (resolves URL each call)
  public void sendToQueueName(String explicitQueueName, String eventId, String message) {
    String url =
        sqsClient
            .getQueueUrl(GetQueueUrlRequest.builder().queueName(explicitQueueName).build())
            .queueUrl();
    SendMessageRequest request =
        SendMessageRequest.builder()
            .queueUrl(url)
            .messageBody(message)
            .messageAttributes(
                java.util.Map.of(
                    "event_id",
                    software.amazon.awssdk.services.sqs.model.MessageAttributeValue.builder()
                        .stringValue(eventId)
                        .dataType("String")
                        .build()))
            .build();
    sqsClient.sendMessage(request);
  }

  @Deprecated
  public void send(String message) {
    // Deprecated legacy method kept for binary compatibility only.
    // No default queue to send to; warn and no-op to avoid accidental sends.
    log.warn(
        "SqsDispatcher.send(String) is deprecated and does nothing. Use sendToQueueName(queueName, eventId, message) instead.");
  }
}
