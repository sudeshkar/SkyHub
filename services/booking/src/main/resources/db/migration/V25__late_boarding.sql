-- Boarding a passenger after the gate has closed (ADR-0076).
--
-- A late boarding is an ordinary boarded_passenger row with a reason: operations overruled the closed gate and said why. The reason is
-- kept when the boarding is taken back (ADR-0064), so the correction record still shows the passenger had been boarded late.
ALTER TABLE boarded_passenger ADD COLUMN late_reason VARCHAR(200);
ALTER TABLE boarding_reversal ADD COLUMN late_reason VARCHAR(200);
