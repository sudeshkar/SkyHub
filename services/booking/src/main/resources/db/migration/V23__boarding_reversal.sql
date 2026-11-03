-- Taking back a wrong boarding scan (ADR-0064).
--
-- Append-only: a row is added when a boarding is undone and is never updated or deleted by any code. It keeps the boarding that was taken
-- back (who scanned, when) beside the correction (who, when, why), so a mistaken scan and its fix can both be answered for later.
-- The boarding itself is deleted from boarded_passenger in the same statement, so the passenger can board again.
CREATE TABLE boarding_reversal (
    id              BIGSERIAL   PRIMARY KEY,
    locator         VARCHAR(6)  NOT NULL REFERENCES pnr (locator),
    segment_index   INT         NOT NULL,
    passenger_index INT         NOT NULL,
    boarded_by      VARCHAR(40) NOT NULL,
    boarded_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    reversed_by     VARCHAR(40) NOT NULL,
    reversed_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    reason          VARCHAR(200) NOT NULL
);

CREATE INDEX ix_boarding_reversal_locator ON boarding_reversal (locator);
