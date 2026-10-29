-- Optimistic lock on the booking itself, so a cancel and a seat change cannot both succeed.
ALTER TABLE pnr ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
