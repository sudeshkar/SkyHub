-- Ending every open session when an account's credentials change (ADR-0041). Account tokens carry the epoch they were issued under
-- and are valid only while it is still the account's; changing the address or choosing a password adds one. Tokens issued before this
-- column existed carry no epoch and count as 0, so nobody is signed out by the upgrade.
ALTER TABLE traveller_account ADD COLUMN session_epoch INT NOT NULL DEFAULT 0;
