# Live check of the account journey

Unit and integration tests prove each service against fakes, an in-process Kafka and a throwaway database. These two scripts prove the
**real chain** the way a person would meet it: booking → outbox → Kafka → notification → SMTP → Mailpit. Nothing is mocked. Use them
after a change to accounts, events, the notification service or the keys they share (`DEVELOPMENT.md`, section 6).

They need only Python 3 (standard library), Docker, and the booking and notification services running. They do **not** need the flight
search service or the website.

## Set up

```bash
docker compose up -d postgres kafka mailpit          # from the repository root

# Keys: the link-token key MUST be the same value for both services (ADR-0046). The others are the booking service's own.
export SKYHUB_PII_KEY=$(openssl rand -base64 32)
export SKYHUB_MANAGE_TOKEN_KEY=$(openssl rand -base64 32)
export SKYHUB_BOARDING_PASS_KEY=$(openssl rand -base64 32)
export SKYHUB_LINK_TOKEN_KEY=$(openssl rand -base64 32)

./mvnw -f services/booking/pom.xml spring-boot:run &          # http://localhost:8082, management 9082
./mvnw -f services/notification/pom.xml spring-boot:run &     # management 9083
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

## 2. The failure path (park, fix the key, replay)

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
- Bookings, payments and flights: they need the flight search service and are covered by the other tests and `npm run e2e`.
- Load or timing: this is a correctness check of one traveller's journey, not a benchmark.

URLs can be changed with `BOOKING_URL`, `MAILPIT_URL` and `NOTIFICATION_MGMT_URL`.
