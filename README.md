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

## Quickstart
```bash
# build using the Maven Wrapper (no global Maven install needed)
./mvnw -v
./mvnw clean verify

# run infra
docker compose up -d

# run the app
./mvnw spring-boot:run
```

## Testing
- Unit tests: JUnit 5 via Surefire (pattern: *Test.java, *Tests.java)
- Integration tests: Failsafe (pattern: *IT.java) using Testcontainers for Postgres/Kafka/Redis
- Coverage: JaCoCo report at target/site/jacoco/index.html

```bash
./mvnw -Ptest clean verify    # runs unit + integration tests
```

## Database Migrations (Liquibase)
- Changelogs: src/main/resources/db/changelog
- Best practices: immutable changesets with id/author, separate DDL/DML, contexts for test data
- Validate changesets:
```bash
./mvnw -Plocal liquibase:validate
```

## CI/CD
GitHub Actions workflow builds on Java 21, runs Checkstyle, SpotBugs, tests, and uploads reports.

## Contributing
- Checkstyle and SpotBugs run in verify phase and fail the build on violations.
- Follow .editorconfig and .gitattributes for consistent formatting and line endings.

## License
Apache-2.0. See LICENSE.
