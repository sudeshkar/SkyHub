-- Check-in-open reminders (ADR-0029). One row per booking and flight once its reminder has been queued: the primary key is
-- what makes "at most one reminder" true even when several instances sweep at once.
CREATE TABLE check_in_reminder (
    locator       VARCHAR(6) NOT NULL REFERENCES pnr (locator),
    segment_index INT        NOT NULL,
    sent_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (locator, segment_index)
);

-- The sweep looks at the next day or two of flights; without this it would read every booking ever made.
CREATE INDEX ix_pnr_segment_date ON pnr_segment (departure_date, flight_number);
