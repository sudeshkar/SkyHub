# ADR-0033: Traveller accounts: sign up and sign in with an emailed link

**Status:** Accepted
**Date:** 2026-10-31

## Context

A booking is found by its reference and last name (ADR-0011). That is right for a guest, but a traveller with several trips has nowhere to see them together. Accounts are the first step. They are also the first feature that lets a stranger choose to attach themselves to an email address, so the dangerous question is: **how do we know the person signing up owns the address?** Getting that wrong would later let someone read another traveller's bookings.

## Options considered

1. **Sign up with email and password, verify later** - the common design. Between signing up and verifying, whoever typed the address chose the password. If the real owner then clicks the verification link, the account has the stranger's password: the attacker pre-registers the victim's address and later signs in. (Often called pre-registration account takeover.)
2. **Sign up with email and password, forbid sign-in until verified** - fixes the sign-in half, but a second sign-up for an unverified address either overwrites the password (letting an attacker replace the victim's choice) or is refused (letting an attacker block the victim).
3. **Choose the password only after proving the address** - the person asks for a link; the link, which only the mailbox owner receives, leads to a page where they choose the password. Nobody can set a password for an address they cannot read. The same flow resets a forgotten password.

## Decision

- **Option 3.** `POST /accounts/password-links {email}` creates the account (without a password) if there is none, and emails a link. `POST /accounts/passwords {token, password}` spends the link, sets the password and marks the address confirmed. `POST /accounts/sessions` signs in; `GET /accounts/me` shows the account.
- **The same request signs up and resets.** The answer is always 202 and the email differs only by purpose ("finish creating your account" or "reset your password"), so nothing says whether an address already has an account.
- **The token is 256 bits, works once and for one hour.** Only its SHA-256 hash is stored, so a copy of the table cannot be used to take over an account. Asking again replaces it. A request within two minutes of the last sends nothing, so the form cannot be used to flood an inbox; the website also limits it to 5 per client per 15 minutes.
- **A wrong, used and expired link all give the same 400.** A short, long or missing password is refused before the link is looked at.
- **An account with no password cannot sign in,** and a password reset leaves the old password working until the new one is chosen, so someone who merely knows the address cannot lock the owner out.
- **Sign-in is built like staff sign-in (ADR-0017):** BCrypt, one answer for every failure (unknown address, wrong password, no password yet, locked), the same cost for an unknown address, and five wrong passwords lock the account for 15 minutes. Choosing a new password clears a lock.
- **The address is personal data, stored encrypted like every other.** To find an account by address without storing it in the clear, a **keyed hash** (HMAC-SHA256 of the lower-cased address) is stored beside it. Its key is derived from the personal-data key with a fixed label, so it is a different key and needs no new setting. The same index will find bookings by contact address in the next slice.
- **The account token is a JWT with scope `account`,** signed with the same key as the others but told apart by scope. A booking token (`manage`) or staff token (`staff`) cannot read an account, and an account token cannot do what those do (tested).
- **The email goes through the outbox, Kafka and the notification service** (ADR-0019) as a new event, `account.password-link`, with a shared example read by both services' tests. The link points at `SKYHUB_SITE_URL` (default `http://localhost:3000`). Sent-notification records for it have no booking reference, so that column became optional.
- **The website keeps the token in an httpOnly cookie scoped to `/account`** (like the staff and booking tokens). The set-password page is kept out of search results and sends no referrer, because its address holds the token.
- **Monitoring:** counters for links, passwords and sign-ins, an alert on a burst of failed sign-ins, and a runbook entry.

## Consequences

- Positive: no one can claim an address they cannot read, and no one can block or hijack the owner by signing up first.
- Positive: nothing reveals which addresses have accounts, by response, by email text, or by timing (an unknown address costs the same as a real one).
- Positive: forgotten-password handling comes free from the same flow.
- Negative: **signing up takes an email round trip before the first sign-in.** That is the price of option 3.
- Negative: **the one-time token passes through Kafka** in the event, and sits in the topic for its retention period (the payload is encrypted in the database, not on the topic). The token stops working after an hour or on first use, and the topic is not public, but anyone who can read it can take over an account that is still waiting for its first password. Putting only a reference in the event and fetching the token from the booking service would remove this.
- Negative: an account does not show bookings yet, and a traveller cannot change their email address. Both are next.
- Negative: the lock logic is copied from staff accounts; a shared class would remove the copy. (Removed since ADR-0045.)
- Negative: there is no "sign out everywhere" or token revocation: a token lasts 30 minutes (like the others, ADR-0011).
- Negative: the address is matched ignoring case, but not ignoring dots or `+tags` (so `a.b@x.com` and `ab@x.com` are different accounts).
