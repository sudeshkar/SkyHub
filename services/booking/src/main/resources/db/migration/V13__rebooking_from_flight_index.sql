-- "Were any bookings moved off this flight?" is asked when a disruption is withdrawn (ADR-0026) and when a refund is
-- decided; without this it scans every rebooking ever made.
CREATE INDEX ix_rebooking_from_flight ON rebooking (from_flight, from_date);
