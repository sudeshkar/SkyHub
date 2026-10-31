-- Taking an account back after it was moved without its owner (ADR-0054). When an account moves to another address, the address it
-- had is kept (encrypted like every address) with the hash of a one-time token sent to it, for seven days. Only the latest move can be
-- undone; using the token clears all four columns.
ALTER TABLE traveller_account ADD COLUMN undo_email VARCHAR(500);
ALTER TABLE traveller_account ADD COLUMN undo_email_index VARCHAR(64);
ALTER TABLE traveller_account ADD COLUMN undo_token_hash VARCHAR(64);
ALTER TABLE traveller_account ADD COLUMN undo_expires_at TIMESTAMP WITH TIME ZONE;
CREATE UNIQUE INDEX uq_traveller_account_undo_token ON traveller_account (undo_token_hash) WHERE undo_token_hash IS NOT NULL;
