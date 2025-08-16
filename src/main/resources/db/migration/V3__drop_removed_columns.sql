-- V2: Drop removed columns after code refactor (max_retries, delivery_mechanism)
-- Safe to run multiple times due to IF EXISTS guards

-- Events table
ALTER TABLE IF EXISTS events
  DROP COLUMN IF EXISTS max_retries,
  DROP COLUMN IF EXISTS delivery_mechanism;

-- Event history table
ALTER TABLE IF EXISTS event_history
  DROP COLUMN IF EXISTS delivery_mechanism;

