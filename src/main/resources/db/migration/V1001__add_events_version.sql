-- Add version column for optimistic locking on events
ALTER TABLE events
  ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
