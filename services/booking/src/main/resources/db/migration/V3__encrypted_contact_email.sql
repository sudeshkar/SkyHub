-- Contact email is now stored as AES-256-GCM ciphertext ("v1:" + Base64), which is longer than the address.
ALTER TABLE pnr ALTER COLUMN contact_email TYPE VARCHAR(512);
