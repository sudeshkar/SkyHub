# ADR-0054: Taking an account back after it was moved without its owner

**Status:** Accepted (changes one decision of ADR-0040)
**Date:** 2026-11-03

## Context

Moving an account to another address needs the current password and a link to the new address (ADR-0039), and the old address is told (ADR-0040). But the notice only says "tell support". Someone who knows the password and controls a mailbox of their own can move an account away from its owner, and the owner's only recourse is a human. The roadmap named it: "a way for the owner to recover an account that was moved without them".

ADR-0040 considered an undo link and set it aside for two reasons: it is a second way to change an account, and it "would let anyone who reads an old mailbox (a former address that now belongs to someone else) take an account back". This ADR takes the first seriously by keeping the link narrow, and answers the second below rather than ignoring it.

## Options considered

1. **Keep telling support.** Nothing to abuse, and a human has to verify identity somehow, which we have no process for.
2. **Sign-in with the old address plus something.** The owner cannot sign in: they do not have the new password path, and the password is the thing that was used against them.
3. **A one-time link in the notice to the old address that undoes that one move.** Whoever can read the old mailbox can use it, which is the same proof the account was created with.

## Decision

**Option 3, narrowly.**

- When an account moves, the service keeps the address it had (encrypted, like every address) and the **hash** of a random one-time token, for **seven days**. The notice to the old address carries the token, **sealed on the topic** like every link token (ADR-0046), and the email has a link to `/account/undo-email-change?token=...`.
- `POST /api/v1/accounts/email-change-reversals` with that token needs **no sign-in** (the account may be lost). It:
  - puts the old address back;
  - **throws the password away**, because whoever moved the account may know it, and **ends every session**;
  - stops any link or pending change that was out, and clears a lock;
  - sends a link to the restored address to choose a new password (the existing reset path), so nobody, the owner included, can sign in until that link is used;
  - spends the token. It works once.
- **It can only undo the latest move.** A later move replaces the token. It can never make a move or reach any other account.
- **Every failure is the same 400:** wrong, used, expired, or an old address that another account has taken since (a unique address is what the database refuses).
- The website page needs a **button press**: opening the link changes nothing, so a mail scanner that opens links cannot take an account back.

## Consequences

- Positive: an owner who is told their account moved can take it back in two clicks, and the person who moved it is shut out at once, even if they know the password.
- Positive: it needs nothing the owner does not already hold (the old mailbox), and adds no new secret to remember.
- Negative: **it trusts the old mailbox for a week.** If someone *deliberately* moves their own account and a different person then reads the old mailbox within seven days (a mailbox handed to someone else), that person can take the account. What they would get today is thin: an email, no password they know, and the bookings listed under that address, which anyone reading that mailbox could already see by signing up at the freed address (bookings are found by address, ADR-0034). It must be revisited before accounts hold anything more, such as saved passengers (on the roadmap). The mitigation to add then is a "keep this change" button for the person who moved it.
- Negative: **it can lock a person out of their own deliberate move.** Using the link removes the password, so the account can then only be reached through the old mailbox. Seven days, and only for someone who can read that mailbox.
- Negative: a notice made before this change has no link; it still says to tell support.
- Negative: the token is in the URL of the link (as for every link here), so the page is kept out of search results and sends no referrer.
- A real mailbox that is read by two people is out of scope.
