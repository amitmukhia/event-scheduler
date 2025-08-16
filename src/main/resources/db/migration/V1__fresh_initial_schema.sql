-- Event Scheduler Pro - Initial Schema (Simple & Consistent)
-- Fresh migration with minimal structure and consistent field names across tables

-- Enable UUIDs
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =========================
-- Customers
-- =========================
CREATE TABLE customers (
  customer_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name        VARCHAR(255) NOT NULL,
  email       VARCHAR(320) NOT NULL,
  created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- =========================
-- Events (active)
-- =========================
CREATE TABLE events (
  event_id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  customer_id         UUID NOT NULL REFERENCES customers(customer_id) ON DELETE CASCADE,
  trigger_time        TIMESTAMP NOT NULL,
  next_run_time       TIMESTAMP NOT NULL,
  payload             JSONB NOT NULL,
  max_retries         INT NOT NULL DEFAULT 3,
  failure_count       INT NOT NULL DEFAULT 0,
  last_attempt_time   TIMESTAMP,
  expiration_time     TIMESTAMP,
  created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
  status              VARCHAR(20) NOT NULL,             -- PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
  delivery_mechanism  VARCHAR(20) NOT NULL,             -- KAFKA or SQS
  event_mode          VARCHAR(20) NOT NULL,             -- ONCE or RECURRING
  recurrence          JSONB,                             -- Recurrence config when RECURRING
  canceled_at         TIMESTAMP,                         -- matches JPA Event.canceledAt
  cancelled_by        UUID                               -- matches JPA Event.cancelledBy
);

-- =========================
-- Event History (archive)
-- Stores COMPLETED, FAILED (final), and CANCELLED events
-- Uses event_id as the sole identifier (same as original event)
-- Field names match events (e.g., payload, trigger_time, created_at, event_mode, recurrence)
-- =========================
CREATE TABLE event_history (
  event_id           UUID PRIMARY KEY,                  -- same ID as in events
  customer_id        UUID NOT NULL,
  payload            JSONB NOT NULL,
  trigger_time       TIMESTAMP NOT NULL,                -- original trigger time
  completion_time    TIMESTAMP NOT NULL DEFAULT NOW(),  -- time when event completed/cancelled/failed
  created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
  status             VARCHAR(20) NOT NULL,              -- COMPLETED, FAILED, CANCELLED
  delivery_mechanism VARCHAR(20) NOT NULL,
  cancelled_by       VARCHAR(100),                      -- username/system for cancellations
  event_mode         VARCHAR(20) NOT NULL,              -- copied from events
  recurrence         JSONB                               -- copied from events
);

-- =========================
-- Failed Events (permanent failures after max retries)
-- Uses event_id as the sole identifier (same as original event)
-- Field names match events (e.g., payload, trigger_time, created_at, event_mode, recurrence)
-- =========================
CREATE TABLE failed_events (
  event_id         UUID PRIMARY KEY,                    -- same ID as in events
  customer_id      UUID NOT NULL,
  payload          JSONB NOT NULL,
  trigger_time     TIMESTAMP NOT NULL,
  created_at       TIMESTAMP NOT NULL,
  failure_reason   TEXT NOT NULL,
  failed_at        TIMESTAMP NOT NULL DEFAULT NOW(),
  event_mode       VARCHAR(20) NOT NULL,
  recurrence       JSONB
);

-- =========================
-- Performance-Critical Indexes
-- =========================

-- PRIMARY USE CASE INDEXES:

-- 1. Finding pending events by next_run_time (used by orchestrator and timestamp processing)
-- This partial index only includes PENDING events, making it much smaller and faster
CREATE INDEX idx_events_next_run_status ON events(next_run_time, status) 
WHERE status = 'PENDING';

-- 2. Finding stuck events in PROCESSING state (used by watchdog)
-- Partial index for quick identification of stuck events
CREATE INDEX idx_events_stuck ON events(last_attempt_time, status) 
WHERE status = 'PROCESSING';

-- 3. Finding events exceeding retry limits (used by watchdog for failure handling)
-- Helps quickly identify events that need to be moved to failed_events
CREATE INDEX idx_events_failure_count_status ON events(failure_count, status) 
WHERE failure_count > 0;

-- CUSTOMER ISOLATION INDEXES:

-- 4. Customer-specific event queries (supports per-customer processing)
-- Composite index for efficient customer-based filtering with status and time
CREATE INDEX idx_events_customer_status ON events(customer_id, status, next_run_time);

-- 5. Basic customer lookup (for customer-specific operations)
CREATE INDEX idx_events_customer ON events(customer_id);

-- EXPIRATION AND FILTERING INDEXES:

-- 6. Expiration time filtering (excludes expired events from processing)
-- Partial index only for events with expiration times
CREATE INDEX idx_events_expiration ON events(expiration_time) 
WHERE expiration_time IS NOT NULL;

-- 7. Status-based queries (for monitoring and reporting)
CREATE INDEX idx_events_status ON events(status);

-- BATCH PROCESSING OPTIMIZATION:

-- 8. Compound index for the claimBatch query (critical for high throughput)
-- Supports the FOR UPDATE SKIP LOCKED pattern efficiently
CREATE INDEX idx_events_batch_claim ON events(next_run_time, created_at) 
WHERE status IN ('PENDING', 'PROCESSING');

-- HISTORY AND CLEANUP INDEXES:

-- 9. Event history cleanup operations (for deleting old records)
CREATE INDEX idx_event_history_created ON event_history(created_at);

-- 10. Event history queries by completion time
CREATE INDEX idx_event_history_completion ON event_history(completion_time);

-- 11. Status-based history queries
CREATE INDEX idx_event_history_status_time ON event_history(status, completion_time);

-- 12. Customer-based history reporting
CREATE INDEX idx_event_history_customer_time ON event_history(customer_id, completion_time DESC);

-- FAILED EVENTS INDEXES:

-- 13. Failed events by timestamp (for audit and reporting)
CREATE INDEX idx_failed_events_failed_at ON failed_events(failed_at);

-- 14. Customer-specific failed events
CREATE INDEX idx_failed_events_customer_time ON failed_events(customer_id, failed_at DESC);

-- ADDITIONAL OPTIMIZATION INDEXES:

-- 15. Unique timestamp extraction (used by orchestrator to find unique event times)
-- Avoid using non-IMMUTABLE functions (NOW()) in partial index predicates.
-- Using a safe predicate that still keeps the index small and useful.
CREATE INDEX idx_events_unique_timestamps ON events(next_run_time)
WHERE status = 'PENDING' AND expiration_time IS NULL;

-- 16. Updated_at index for incremental syncing or monitoring
CREATE INDEX idx_events_updated ON events(updated_at);

-- Update table statistics for query planner optimization
ANALYZE events;
ANALYZE event_history;
ANALYZE failed_events;
ANALYZE customers;

