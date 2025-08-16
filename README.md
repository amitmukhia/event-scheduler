# event-scheduler-uuid (Java 21, Spring Boot 3.3)

Aligned with provided DB design (UUIDs + JSONB + history). Replaces Pulsar with Kafka.

## Tables
- customers(customer_id UUID, name, email, created_at)
- events(event_id UUID, customer_id UUID, payload JSONB, status, delivery_mechanism, recurrence_cron, trigger_time, next_run_time, max_retries, failure_count, last_attempt_time, expiration_time, created_at, updated_at)
- event_history(history_id UUID, event_id UUID, customer_id UUID, payload JSONB, completion_time, created_at, status, delivery_mechanism)

## Features
- Batch claim with SKIP LOCKED; increments failure_count when re-picked
- Bounded parallel dispatch; Kafka producer wired
- Recurrence with cron-utils; precompute next_run_time before dispatch
- Watchdog uses last_attempt_time to reset stuck items
- Cleanup archives COMPLETED to event_history and deletes old completed rows

## Run
```bash
docker compose up -d      # start postgres + kafka + redis if added
mvn spring-boot:run