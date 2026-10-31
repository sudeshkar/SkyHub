-- Signing out of a booking ends every open session of it (ADR-0057). A booking token carries the epoch it was issued under and is
-- valid only while that is the booking's current one. A row exists only for a booking someone has signed out of; no row means epoch 0,
-- so tokens issued before this existed stay valid until the first sign-out. Kept out of the pnr table on purpose: signing out must not
-- touch the booking's own row or its optimistic-lock version.
CREATE TABLE booking_session_epoch (
    locator VARCHAR(6) PRIMARY KEY REFERENCES pnr (locator),
    epoch   INT NOT NULL
);
