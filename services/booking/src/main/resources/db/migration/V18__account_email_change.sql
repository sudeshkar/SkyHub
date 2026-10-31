-- Changing an account's address (ADR-0039). The new address waits here, encrypted like the current one with its keyed hash beside
-- it, until the person who can read it uses the emailed link. Only the hash of that link's token is kept. All null when no change
-- is pending.
ALTER TABLE traveller_account
    ADD COLUMN pending_email       VARCHAR(500),
    ADD COLUMN pending_email_index VARCHAR(64),
    ADD COLUMN change_token_hash   VARCHAR(64),
    ADD COLUMN change_issued_at    TIMESTAMP WITH TIME ZONE,
    ADD COLUMN change_expires_at   TIMESTAMP WITH TIME ZONE;
CREATE UNIQUE INDEX uq_traveller_account_change ON traveller_account (change_token_hash) WHERE change_token_hash IS NOT NULL;
