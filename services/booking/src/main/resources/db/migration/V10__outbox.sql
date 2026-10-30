-- Transactional outbox (ADR-0019): events are written here in the same transaction as the change they describe,
-- then published to Kafka by a relay. The payload holds personal data, so it is stored encrypted (PiiCipher).
CREATE TABLE outbox_event (
    id           UUID PRIMARY KEY,
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    aggregate_id VARCHAR(40)  NOT NULL,
    event_type   VARCHAR(60)  NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);

-- The relay only ever asks for the unpublished rows, oldest first.
CREATE INDEX ix_outbox_pending ON outbox_event (seq) WHERE published_at IS NULL;
-- The purge asks for old published rows.
CREATE INDEX ix_outbox_published ON outbox_event (published_at) WHERE published_at IS NOT NULL;
