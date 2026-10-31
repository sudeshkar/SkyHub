-- Boarding, and a role that can only work the gate (ADR-0060).
--
-- A passenger boards once per flight. Kept out of the booking and check-in tables on purpose, so scanning a pass never touches either.
-- The primary key is what makes the first scan win when two gates scan the same pass at once.
CREATE TABLE boarded_passenger (
    locator         VARCHAR(6)  NOT NULL REFERENCES pnr (locator),
    segment_index   INT         NOT NULL,
    passenger_index INT         NOT NULL,
    boarded_by      VARCHAR(40) NOT NULL,
    boarded_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (locator, segment_index, passenger_index)
);

-- GATE may check a pass and board a passenger, and nothing else.
ALTER TABLE staff_account DROP CONSTRAINT ck_staff_role;
ALTER TABLE staff_account ADD CONSTRAINT ck_staff_role CHECK (role IN ('OPS', 'ADMIN', 'GATE'));
