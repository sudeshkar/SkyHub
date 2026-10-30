-- Events that belong to no booking (a link to choose an account password, ADR-0033) have no booking reference.
ALTER TABLE sent_notification ALTER COLUMN locator DROP NOT NULL;
