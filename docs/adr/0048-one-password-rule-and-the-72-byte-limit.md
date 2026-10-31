# ADR-0048: One password rule, and the 72-byte limit

**Status:** Accepted
**Date:** 2026-11-03

## Context

Staff accounts (ADR-0017) and traveller accounts (ADR-0033) each decide for themselves what a good password is, and the two disagree. Travellers need 12 to 100 characters. Staff need at least 12, with no maximum in the service and a contract maximum of 200. The same rule is repeated a third time in the website's forms and server actions. ADR-0045 removed the other duplicated login rules and listed this one as its follow-up.

Looking at it turned up something worse than duplication. Passwords are hashed with BCrypt, which only uses the first 72 **bytes** of its input, and the encoder in use refuses anything longer: a password of 73 characters throws `IllegalArgumentException: password cannot be more than 72 bytes`. So the rules accept passwords that cannot be stored:

- a traveller who chooses a password of 73 to 100 characters passes every check and then gets a **server error** (500) instead of a message;
- the same for a staff password of 73 to 200 characters;
- the limit is in **bytes, not characters**: 50 accented letters are 100 bytes and fail, though they are well under "100 characters".

The contract's own comment says it "caps passwords well above what people type", which was true of the intent and wrong about BCrypt. No such password can exist today, since none could ever have been stored, so nothing already saved is affected.

## Options considered

1. **Leave the three copies and just lower the maxima to 72** - fixes the 500 for ASCII only; staff and travellers could drift again, and accented passwords would still fail.
2. **Hash a digest of the password with BCrypt (pre-hash, so any length works)** - removes the limit, but changes how every password is hashed, so existing hashes would need a marker and a migration. A real option, and a bigger change than this bug needs.
3. **One rule, in one place, that states the real limit** - 12 characters at least and 72 UTF-8 bytes at most, used by both services, the contract and the website.

## Decision

- **Option 3.** `PasswordPolicy` is a small domain class with the one rule: **at least 12 characters, at most 72 bytes in UTF-8**. It answers with a message or nothing, and each service throws its own exception from it (a 400 from either, with the same wording), so what travellers and staff are told no longer differs.
- **The message says the real rule** and why a long passphrase can be refused: "A password needs 12 to 72 characters, counting accents and symbols as more than one (72 bytes at most)."
- **The contract and the website say 72.** `SetPasswordRequest` and `CreateStaffRequest` have a `maxLength` of 72, the website's forms say so, and its action checks the same rule before calling the service, by bytes. The service still checks, because the contract's `maxLength` counts characters and cannot see the byte limit.
- **Signing in is not changed.** A sign-in with a longer password is simply a wrong password (it cannot match anything that could have been stored); it is still capped by the contract at its own length so that nobody can send an enormous one.
- **The first administrator** (`SKYHUB_BOOTSTRAP_ADMIN_PASSWORD`) follows the same rule, so a bootstrap password that cannot be stored is refused with the same message instead of failing at start-up with a stack trace.
- **Pre-hashing is not done now.** It is the way to allow longer passphrases and is a decision of its own, because it needs a hash format version and a migration.

## Consequences

- Positive: a password that cannot be stored is refused with a 400 and an explanation, instead of a 500 after the link or form was accepted.
- Positive: staff and travellers have one rule, stated once, so they cannot drift apart, and the website cannot promise something the service refuses.
- Positive: the limit is counted where BCrypt counts it, in bytes, so accented and non-Latin passwords are judged correctly.
- Negative: **a password manager that generates 100-character passwords is refused,** where the old contract implied it would be accepted (and then failed). The message says why. Pre-hashing would lift this.
- Negative: **a person who types a very long passphrase in non-Latin letters hits the limit sooner** (three bytes per character for many scripts), at about 24 characters.
- Negative: the limit is a property of BCrypt that the policy now encodes; if the hashing scheme changes (pre-hash, or another algorithm), this is the one place to change, but it is still a place that has to be remembered.
- Negative: the staff contract's maximum fell from 200 to 72. Nobody could have used the difference, but a script written against the old contract would see a 400 where it used to see a 500.
- Follow-up: pre-hash passwords so the limit can go; check new passwords against a list of common ones.
