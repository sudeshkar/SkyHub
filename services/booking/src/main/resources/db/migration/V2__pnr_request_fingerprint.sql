-- Bind each Idempotency-Key to the request it was first used with (SHA-256 hex).
-- Applied migrations are never edited; changes always arrive as a new version.
ALTER TABLE pnr ADD COLUMN request_fingerprint VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE pnr ALTER COLUMN request_fingerprint DROP DEFAULT;
