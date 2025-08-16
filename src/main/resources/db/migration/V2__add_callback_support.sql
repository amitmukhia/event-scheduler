-- Add callback support to events tables
-- This migration adds callback configuration to support custom SQS/Kafka topic routing

-- Add callback column to events table
ALTER TABLE events ADD COLUMN IF NOT EXISTS callback JSONB;

-- Add callback column to event_history table to preserve callback info
ALTER TABLE event_history ADD COLUMN IF NOT EXISTS callback JSONB;

-- Add callback column to failed_events table for debugging
ALTER TABLE failed_events ADD COLUMN IF NOT EXISTS callback JSONB;

-- Add index for events with callbacks (partial index for performance)
CREATE INDEX IF NOT EXISTS idx_events_callback ON events(event_id) 
WHERE callback IS NOT NULL;

-- Add comment to explain callback structure
COMMENT ON COLUMN events.callback IS 'Callback configuration: {"includeMetadata": bool, "messagebrokers": [{"type": "SQS|KAFKA", "topics": ["topic1", "topic2"]}]}';
COMMENT ON COLUMN event_history.callback IS 'Original callback configuration from event';
COMMENT ON COLUMN failed_events.callback IS 'Original callback configuration from event';
