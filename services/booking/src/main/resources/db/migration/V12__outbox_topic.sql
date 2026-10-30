-- Each outbox event now says which Kafka topic it goes to (ADR-0025). Booking events keep the topic they always had.
ALTER TABLE outbox_event ADD COLUMN topic VARCHAR(100) NOT NULL DEFAULT 'skyhub.booking.events';
