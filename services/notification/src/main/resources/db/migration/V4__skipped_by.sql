-- Who left a parked message out of the replay (ADR-0052). Self-declared: the management port has no login. Rows made before this
-- carry a marker rather than a guessed name.
ALTER TABLE skipped_dead_letter ADD COLUMN skipped_by VARCHAR(60) NOT NULL DEFAULT 'unknown (before ADR-0052)';
