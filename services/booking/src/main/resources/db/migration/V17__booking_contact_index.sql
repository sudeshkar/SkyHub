-- Find a traveller's bookings by address without decrypting any (ADR-0034). contact_email_index is the keyed hash of the contact
-- address (see PiiCipher.blindIndex), the same value traveller_account.email_index holds. Null only for bookings made before this
-- migration, until ContactEmailIndexBackfill has visited them.
ALTER TABLE pnr ADD COLUMN contact_email_index VARCHAR(64);

-- Serves "newest bookings for this address": equality on the address, already in created_at order.
CREATE INDEX ix_pnr_contact_email_index ON pnr (contact_email_index, created_at DESC) WHERE contact_email_index IS NOT NULL;

-- Serves the backfill's batches (ids in order, only unindexed rows). It empties as the backfill finishes.
CREATE INDEX ix_pnr_unindexed ON pnr (id) WHERE contact_email_index IS NULL;
