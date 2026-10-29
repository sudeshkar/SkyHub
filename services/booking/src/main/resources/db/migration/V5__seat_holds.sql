-- Seats can be HELD for a few minutes while a traveller completes a booking (ADR-0012).
ALTER TABLE seat_inventory ADD COLUMN hold_id    VARCHAR(36);
ALTER TABLE seat_inventory ADD COLUMN held_until TIMESTAMP WITH TIME ZONE;

-- The cleanup job looks for expired holds; both indexes are partial so they stay tiny.
CREATE INDEX ix_seat_held_until ON seat_inventory (held_until) WHERE status = 'HELD';
CREATE INDEX ix_seat_hold_id    ON seat_inventory (hold_id)    WHERE hold_id IS NOT NULL;
