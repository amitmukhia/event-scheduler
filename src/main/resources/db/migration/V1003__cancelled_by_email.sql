-- Convert cancelled_by from UUID to TEXT (email)
ALTER TABLE events
    ALTER COLUMN cancelled_by TYPE TEXT USING cancelled_by::text;
