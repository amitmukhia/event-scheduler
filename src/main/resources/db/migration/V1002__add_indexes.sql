-- Add useful indexes for query performance
-- Ensure names are unique and IF NOT EXISTS for idempotence

-- Events: common due scan and status filtering
CREATE INDEX IF NOT EXISTS idx_events_status_next_run_time
  ON events (status, next_run_time);

CREATE INDEX IF NOT EXISTS idx_events_next_run_time
  ON events (next_run_time);

CREATE INDEX IF NOT EXISTS idx_events_customer_status
  ON events (customer_id, status);

-- Event history: common lookups
CREATE INDEX IF NOT EXISTS idx_event_history_customer_completion
  ON event_history (customer_id, completion_time);

