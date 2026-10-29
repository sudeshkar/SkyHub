# ADR-0010: Encrypt personal data at rest; hash what is only compared

**Status:** Accepted
**Date:** 2026-10-29

## Context

PNRs hold personal data (contact email) and secrets supplied by clients (idempotency keys). A database dump or leaked backup must not expose them.

## Options considered

1. **Hash the email** — irreversible, so confirmations could never be sent.
2. **Database-level encryption only (disk/TDE)** — protects stolen disks, not SQL access or dumps.
3. **Application-level encryption for data we read back; hashing for data we only compare.**

## Decision

Option 3. Contact email is encrypted with AES-256-GCM (random 96-bit IV, 128-bit tag) by a JPA converter applied automatically to the `ContactEmail` value object. Values carry a `v1:` prefix so keys can be rotated. The key comes from the `SKYHUB_PII_KEY` environment variable, and the service refuses to start without a valid 256-bit key. Idempotency keys and request fingerprints are stored as SHA-256 hashes.

## Consequences

- Positive: dumps and backups reveal no email addresses or reusable keys; tampering is detected.
- Negative: encrypted columns cannot be searched or indexed by value.
- Negative: losing the key loses the data, so production keys must live in a secrets manager.
- Follow-up: key rotation (decrypt with the old key id, re-encrypt with the new) and a secrets manager.
