#!/usr/bin/env bash
set -euo pipefail

# Ensure scripts are idempotent: create topics if not exists
BOOTSTRAP=${KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}
TOPICS=("event.submissions" "event.scheduled" "event.ready" "timestamp.processing" "process-kafka-message")

for t in "${TOPICS[@]}"; do
  if ! kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list | grep -q "^$t$"; then
    kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$t" --replication-factor 1 --partitions 3 || true
    echo "Ensured topic $t"
  else
    echo "Topic $t already exists"
  fi
done

