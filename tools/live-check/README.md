# Live check of the account journey

Unit and integration tests prove each service against fakes, an in-process Kafka and a throwaway database. These two scripts prove the
**real chain** the way a person would meet it: booking → outbox → Kafka → notification → SMTP → Mailpit. Nothing is mocked. Use them
after a change to accounts, events, the notification service or the keys they share (`DEVELOPMENT.md`, section 6).

They need only Python 3 (standard library), Docker, and the services running. The account journey and the failure path need booking and
notification; the booking journey also needs the flight search service. None of them needs the website.

## Set up

```bash
docker compose up -d postgres kafka mailpit          # from the repository root

# Keys: the link-token key MUST be the same value for both services (ADR-0046). The others are the booking service's own.
export SKYHUB_PII_KEY=$(openssl rand -base64 32)
export SKYHUB_MANAGE_TOKEN_KEY=$(openssl rand -base64 32)
export SKYHUB_BOARDING_PASS_KEY=$(openssl rand -base64 32)
export SKYHUB_LINK_TOKEN_KEY=$(openssl rand -base64 32)

./mvnw -f services/flight-search/pom.xml spring-boot:run &    # http://localhost:8081 (only the booking journey needs it)
./mvnw -f services/booking/pom.xml spring-boot:run &          # http://localhost:8082, management 9082
./mvnw -f services/notification/pom.xml spring-boot:run &     # management 9083
```

**Use a fresh booking database for the booking journey.** It signs in as staff, and the bootstrap administrator is created only when the
database has no staff account at all, so a development database that already has staff will answer 401. Rather than delete anyone's
logins, point the booking service at a throwaway database:

```bash
docker compose exec postgres psql -U skyhub -d postgres -c "CREATE DATABASE booking_live OWNER skyhub;"
export DB_URL=jdbc:postgresql://localhost:5432/booking_live
export SKYHUB_BOOTSTRAP_ADMIN_USERNAME=live.admin SKYHUB_BOOTSTRAP_ADMIN_PASSWORD=live-admin-passphrase   # the script's defaults
# start the booking service in this shell, as above; drop the database when you are done
```

Throwaway keys are fine. A development database that holds bookings made under an earlier `SKYHUB_PII_KEY` will log, once per start, that
some old bookings could not be read; that is expected and harmless for this check.

## 1. The account journey (29 checks)

```bash
python tools/live-check/account_journey.py
```

Signs up with an emailed link, signs in, asks to change the address, confirms it from the new mailbox, and signs out everywhere, reading
every email from Mailpit and the real Kafka topic. It checks, among others, that:

- the topic holds `sealedToken` and **the plain token from the email appears nowhere on it** (ADR-0046);
- a wrong password when changing the address is a 403 and nothing changes until the link is used (ADR-0039);
- the **old** address is emailed a notice with the new address masked and no link (ADR-0040);
- the open session is refused at once after the move, and after "sign out everywhere" (ADR-0041, ADR-0047);
- a password of 100 bytes is a 400 with the rule, not a 500 (ADR-0048).

It exits non-zero if any check fails.

## 2. The booking journey (43 checks)

```bash
python tools/live-check/booking_journey.py
```

Finds a real one-stop trip (CMB to LHR) in flight search, then books it through the real chain and looks after it:

- **Booking:** quote, hold the seats, a declined card (402, seats stay held, no email), a changed price (409), the booking (201), asking again with the
  same key (200, no second email), the same key for a different booking (422), the confirmation email, the seat map.
- **As a guest:** a wrong last name (404, same as unknown), the right one in any case, a seat change.
- **As an account:** the traveller signs up *after* booking and Your bookings lists it (upcoming, not past) and opens it without the last name.
- **Operations:** staff cancel the second flight; the traveller is emailed, the flight can no longer be sold (409), and **flight search, following
  the disruption topic, stops offering it**. A bulk rebooking moves the booking to the next day's flight (MOVED, a seat each, emailed), asking
  again is UNCHANGED, and a replacement that lands after the onward flight has gone is REFUSED naming the flight it would miss.
- **Money:** the traveller cancels; the whole fare is refunded, the cancellation email says so, the seats are released, and the booking
  moves from upcoming to past.

It reads the flight, dates and seats from the real services each run, so it does not depend on fixed reference numbers. Seed data fact: only
SK001 flies DXB to LHR, so a replacement for a cancelled SK001 is the same flight on the next day.

## 3. The failure path (park, fix the key, replay)

This one needs two runs of the notification service, which a script cannot restart for you.

1. Start the notification service with a link-token key that does **not** match booking's (a different `openssl rand -base64 32`), then:

   ```bash
   python tools/live-check/failure_path.py park
   ```

   It asks for a sign-up link, waits for the retries to run out, and checks that the event was **parked, not sent**, and that the parked
   listing says why without showing the address or any token.

2. Restart the notification service with the **right** key, then:

   ```bash
   python tools/live-check/failure_path.py recover
   ```

   It follows the runbook (`NotificationDeadLettered`): replays the parked message, checks the link is now sent and that the traveller can
   use it, and that a second replay takes nothing more.

## What this does not cover

- The website (pages, forms, cookies): run `npm run e2e` in `frontend/` for the browser flows that exist.
- Check-in and boarding passes, holds that expire, the refund retry, and the disruption agent (they need time to pass or a model key).
- Load or timing: this is a correctness check of one traveller's journey, not a benchmark.

URLs can be changed with `FLIGHT_SEARCH_URL`, `BOOKING_URL`, `MAILPIT_URL` and `NOTIFICATION_MGMT_URL`; the staff login with `LIVE_STAFF_USERNAME`
and `LIVE_STAFF_PASSWORD`. The scripts share their helpers in `_live.py`.
