-- A passenger who has boarded is a passenger who is checked in (ADR-0069).
--
-- ADR-0060 made the service refuse to check out a boarded passenger. This makes the database refuse it too, so two things that
-- happen at the same instant (a gate scanning a pass, and a rebooking that checks the party out) cannot both succeed: the move
-- stops instead of leaving a boarding row that, being keyed by the flight's place on the booking, would read as boarding the new flight.
-- RESTRICT, not CASCADE: a check-in is never removed by taking the boarding with it.
ALTER TABLE boarded_passenger
    ADD CONSTRAINT fk_boarded_check_in FOREIGN KEY (locator, segment_index, passenger_index)
    REFERENCES check_in (locator, segment_index, passenger_index) ON DELETE RESTRICT;
