# Testing SkyHub end to end on your desktop

This guide starts the **whole system in containers** and then tests every feature built so far, the way a traveller, a member of staff and a gate agent would meet it. Follow the parts in order. Every step says what you should see; a ⚠ says what to do if you do not.

- Commands are for **Git Bash** on Windows (macOS and Linux work the same). Run them from the repository root unless a step says otherwise.
- Time: about 20 minutes to start and run the automatic check, then 2 to 3 hours for the full manual walk-through. Parts 3 to 12 are independent once the stack is running, so you can do them over several sittings.
- Tick the boxes (`[ ]`) as you go. The one-page version is the checklist at the end.

**You need:** Docker Desktop (running), Git Bash, Python 3.11+ (for the automatic check and part 14), Chrome or Firefox. (Safari does not accept the site's secure cookies on `http://localhost`, so sign-in would fail there.) Java and Node are only needed for part 14.

**Addresses used**

| What | Address |
|---|---|
| The website | http://localhost:3000 |
| Emails (Mailpit inbox) | http://localhost:8025 |
| Flight search API | http://localhost:8081 (management 9081) |
| Booking API | http://localhost:8082 (management 9082) |
| Notification management | http://localhost:9083 |
| Disruption agent | http://localhost:8084 |

---

## Part 1. Start the stack

### 1.1 The keys

The containers read their keys from the file `.env` in the repository root. These must all have a value (copy `.env.example` to `.env` if you have none):

```
SKYHUB_PII_KEY            SKYHUB_MANAGE_TOKEN_KEY    SKYHUB_BOARDING_PASS_KEY   SKYHUB_LINK_TOKEN_KEY
SKYHUB_AGENT_API_TOKEN    SKYHUB_AGENT_USERNAME      SKYHUB_AGENT_PASSWORD
SKYHUB_BOOTSTRAP_ADMIN_USERNAME                      SKYHUB_BOOTSTRAP_ADMIN_PASSWORD
GRAFANA_ADMIN_PASSWORD
```

Make a key with `openssl rand -base64 32` (use a different one for each of the first four). Use the username `agent.ops` for the agent and `admin` for the administrator, and passwords of 12 to 72 characters. **`GROQ_API_KEY` is optional** (part 10).

Check which are still empty, without printing the secrets:

```bash
for k in SKYHUB_PII_KEY SKYHUB_MANAGE_TOKEN_KEY SKYHUB_BOARDING_PASS_KEY SKYHUB_LINK_TOKEN_KEY SKYHUB_AGENT_API_TOKEN SKYHUB_AGENT_USERNAME SKYHUB_AGENT_PASSWORD SKYHUB_BOOTSTRAP_ADMIN_USERNAME SKYHUB_BOOTSTRAP_ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD; do
  v=$(grep -E "^$k=" .env | cut -d= -f2-); [ -n "$v" ] && echo "ok     $k" || echo "EMPTY  $k"; done
```

- [ ] No line says `EMPTY`.

> Never commit `.env` (it is git-ignored). `SKYHUB_PII_KEY` encrypts personal data: a database written with one key cannot be read with another. If you change it, start clean (part 16).

### 1.2 Start

```bash
docker compose -p skyhubtest -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.test.yml up -d --build
```

`-p skyhubtest` keeps this test stack and its data apart from anything else you run. `docker-compose.test.yml` is for desktop testing only: it publishes the service ports so you can use `curl`, and opens check-in and boarding 100 days ahead so the seed flights can be checked in to. The first start builds five images and takes several minutes.

```bash
docker compose -p skyhubtest -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.test.yml ps
```

- [ ] Nine containers show **(healthy)**: `booking`, `flight-search`, `notification`, `disruption-agent`, `website`, `postgres`, `kafka`, `redis`, `mailpit`.

⚠ A missing key stops the start with a message naming it. ⚠ `website` waits for the other four to be healthy, so give it a minute.

To save typing, make an alias for the rest of the session (it only lasts for this Git Bash window):

```bash
alias sk='docker compose -p skyhubtest -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.test.yml'
```

### 1.3 First look

```bash
curl -s http://localhost:9081/actuator/health ; echo     # {"groups":["liveness","readiness"],"status":"UP"}
curl -s http://localhost:3000/healthz ; echo               # {"status":"UP"}
```

- [ ] Open http://localhost:3000: the SkyHub home page appears.
- [ ] Open http://localhost:8025: an empty Mailpit inbox.

---

## Part 2. The automatic live check (about 5 minutes)

Before clicking anything, let the repository's own check drive the **real chain** (booking, outbox, Kafka, notification, email) with no mocks. It covers accounts, booking, payment, check-in, check-out, the gate, a cancelled flight, a rebooking that keeps a connection, a cancellation and refund.

```bash
set -a; . ./.env; set +a                    # loads your keys into THIS window only
export COMPOSE_PROJECT_NAME=skyhubtest
export LIVE_STAFF_USERNAME=$SKYHUB_BOOTSTRAP_ADMIN_USERNAME LIVE_STAFF_PASSWORD=$SKYHUB_BOOTSTRAP_ADMIN_PASSWORD
python tools/live-check/account_journey.py | tail -3
python tools/live-check/booking_journey.py | tail -3
```

- [ ] The account journey ends `41 of 41 checks passed`.
- [ ] The booking journey ends `83 of 83 checks passed`.

⚠ If a few checks fail, run it once more: it is built for a fresh stack, and one run in five once missed a check that was not captured (ADR-0083). If the same check fails twice, copy its `FAIL` line and the lines above it.

These two scripts create their own data (accounts, bookings, a cancelled flight). **To get a clean stack for the manual parts, reset it (part 16) and start again**, or carry on: the manual parts work either way, but the flight the script cancelled will stay cancelled.

---

## Part 3. A traveller books a trip

Open http://localhost:3000.

### 3.1 Search

- [ ] **Direct flight.** Search **DXB to LHR**, a date 3 days ahead, 2 passengers. One result, SK001, with times and a price from $540 per person.
- [ ] **A connection that changes airports.** Search **BOM to JFK**. SK201 lands at DXB and SK901 leaves from DWC: you should see itineraries that connect with a change of airport and a layover time.
- [ ] **Nothing found.** Search a route with no flights (for example DXB to DXB): a clear message, not an error page.
- [ ] **Bad input.** Open `http://localhost:3000/search?origin=DXB&destination=LHR&date=nonsense` : a friendly message.

### 3.2 Choose seats and hold them

1. Click **Select** on SK001 and continue to seats.
- [ ] A seat map appears. Pick two economy seats.
- [ ] Press **Continue to passengers**: a **10-minute countdown** starts. The seats are held for you.

### 3.3 Passenger details

- [ ] Enter two names and a contact email, for example `mira@example.com`. The page says to enter names as on the passport.
- [ ] If you are signed in to an account, a **fill from saved** option appears (part 7).

### 3.4 Pay (three demo cards)

| Card | Result |
|---|---|
| Visa ending **0002** | Declined: a clear message, **no booking**, your seats still held |
| Visa ending **9995** | Payment provider down: no booking, **no charge** |
| Visa ending **4242** | Approved |

- [ ] Pay with 0002 first, then 9995, then 4242.
- [ ] **Booking confirmed** appears with a six-character reference. **Write it down.**
- [ ] In Mailpit, a **booking confirmation** email arrives within a few seconds, with the reference.

### 3.5 Things that should *not* work

- [ ] **Hold expiry.** Start another booking, choose seats, wait for the countdown to reach zero, then pay: refused, and the seats are freed.
- [ ] **No double-selling.** Open the same flight in two browser windows (use a private window for the second), take the same seat in both: the second person is told it is taken.
- [ ] **No double charge.** Pay and click the button twice quickly: one booking, one email.
- [ ] **Rate limit on holds.** Start seat holds more than 6 times within a minute: the site tells you to slow down (this is the limit that stops a script locking a whole flight).

---

## Part 4. Manage, change seats, cancel and refund

Go to http://localhost:3000/manage. Enter the reference and a passenger's **last name**.

- [ ] The trip appears with flights, passengers and seats.
- [ ] **Wrong last name**: refused with the same message as an unknown reference (the site never says which was wrong).
- [ ] **Brute force.** Try 10 wrong lookups: the 11th is refused as too many attempts.
- [ ] **Change a seat** to a free one: it works, and a seat that is taken is refused.
- [ ] **Cancel.** Press **Cancel booking**, then **Yes, cancel booking**. The refund rule is simple (ADR-0016):
  - the flight is 24 hours or more away: **full refund** (the seed flights are a few days out, so expect this);
  - less than 24 hours before departure: **no refund**;
  - the airline cancelled a flight on the booking: **full refund whenever** (tested in part 8).
- [ ] A **cancellation email** arrives in Mailpit, with the refund.
- [ ] Open the booking again: it shows as cancelled, and the seats can be booked by someone else.

---

## Part 5. Check-in, boarding passes and check-out

Make a **new booking** first (part 3) with **two passengers**, because the cancelled one cannot be checked in. Check-in opens 100 days ahead in this test stack (24 hours in real life).

- [ ] On the manage page, press **Check in** for **one** passenger only. That passenger gets a **boarding pass with a QR code**; the other does not (per-passenger check-in, ADR-0058).
- [ ] A **check-in confirmation email** arrives, with that passenger's QR code attached as a picture.
- [ ] **A checked-in passenger's seat is frozen.** Try to change their seat: refused. The other passenger's seat can still be changed.
- [ ] Check in the second passenger. Each pass names its own passenger.
- [ ] **Check out** one passenger (the button is next to their name): their pass stops working and their seat is free to change again. Check them in again.
- [ ] **Check out for the whole flight** works until check-in closes.
- [ ] Keep one booking **checked in** with its boarding-pass text for part 9 (copy the text under the QR code).

The **check-in open reminder** email is sent by a background sweep. Because this stack opens check-in 100 days ahead, bookings are already "open", so a reminder email may arrive for older bookings.

---

## Part 6. Emails: failure and recovery

All emails go through a Kafka topic, so they are never lost when the mail server is down.

- [ ] **Booking works while notification is down.** `sk stop notification`, make a booking (part 3), confirm it. No email yet. `sk start notification`: the confirmation arrives in Mailpit within a minute. (`sk` is the alias from 1.2.)
- [ ] **No parked messages.** `curl -s http://localhost:9083/actuator/deadletters` answers `{"parked":0,"messages":[]}`.
- [ ] **(Optional, long) A mail outage.** `sk stop mailpit`, make a booking, wait. The email retries on delayed topics for about 22 minutes, then parks. `sk start mailpit`. If it parked, put it back with:
  ```bash
  curl -s -X POST http://localhost:9083/actuator/deadletters -H 'Content-Type: application/json' -d '{"limit": 100, "operator": "your.name"}'
  ```
  The email then arrives, and replaying twice sends nothing twice. Skipping a message that can never succeed is in `docs/runbooks/README.md` (NotificationDeadLettered).

---

## Part 7. A traveller account

Go to http://localhost:3000/account.

- [ ] **Sign up** with an email, for example `rana@example.com`. Mailpit gets a "choose a password" email.
- [ ] Open its link (it opens the site). Choose a password of **12 to 72 characters** (a shorter one is refused with the rule). You are signed in.
- [ ] **Your bookings.** Make a booking with the same email as the contact address (part 3): it appears under **Your bookings**, soonest flight first. Use the **upcoming / past** filter. Open one: you get the manage page without typing its reference.
- [ ] **Saved passengers.** Add a name on the account page (up to ten; names only, stored encrypted). Start a new booking: the passengers step offers **fill from saved** in one click.
- [ ] **Wrong password five times**: the account locks for 15 minutes, with the same message as any other failure.
- [ ] **Forgot password.** Request a reset: the email arrives; asking again within 2 minutes is quietly ignored.
- [ ] **Change email.** Enter your current password and a new address. A confirmation link goes to the **new** address; until you use it nothing changes. Using it moves the account, **ends every session** and sends the **old** address a notice that names the new one only in masked form, with an **undo** link.
- [ ] **Undo.** Use the undo link from the notice: the old address is restored, the password is thrown away, every session ends and a reset link arrives.
- [ ] **Sign out everywhere**: open the account in two browsers, press it in one: the other is refused on its next request.
- [ ] Bookings made with the old address follow the account when its address changes.

---

## Part 8. Staff: roles, disruptions and the operations page

### 8.1 Sign in and create more staff

- [ ] Open http://localhost:3000/ops and sign in as `admin` with the password from `.env` (`SKYHUB_BOOTSTRAP_ADMIN_PASSWORD`).
- [ ] The operations page appears. A wrong password five times locks the account for 15 minutes.

There is no form for creating staff. An administrator uses the API (roles: `OPS`, `ADMIN`, `GATE`). In a Git Bash window where you ran `set -a; . ./.env; set +a` (part 2):

```bash
TOKEN=$(curl -s -X POST http://localhost:8082/api/v1/ops/sessions -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SKYHUB_BOOTSTRAP_ADMIN_USERNAME\",\"password\":\"$SKYHUB_BOOTSTRAP_ADMIN_PASSWORD\"}" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')

curl -s -o /dev/null -w "gate agent  -> %{http_code}\n" -X POST http://localhost:8082/api/v1/ops/staff -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"gate.one","password":"a-gate-password-12","role":"GATE"}'
curl -s -o /dev/null -w "ops person  -> %{http_code}\n" -X POST http://localhost:8082/api/v1/ops/staff -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SKYHUB_AGENT_USERNAME\",\"password\":\"$SKYHUB_AGENT_PASSWORD\",\"role\":\"OPS\"}"
```

- [ ] Both answer `201`. (Usernames are lower case, 3 to 40 characters.) Running them twice answers 409.
- [ ] **Roles.** Sign out, then sign in as `gate.one`: you see **only the Gate** section. Sign in as `admin`: you see everything.

### 8.2 Record a delay

Use a flight with a booking (for example SK001 on the date you booked). Sign in as `admin`.

- [ ] In **Record a delay or cancellation**: flight, date, type *delay*, a revised departure a few hours later and a reason, then **Record**. A confirmation appears.
- [ ] **Search shows it.** Search the same flight: the revised time is shown.
- [ ] **A connection follows the delay.** A layover that was fine before and is now too short is no longer offered (search judges layovers on expected times).
- [ ] The traveller gets a **delay email** in Mailpit.
- [ ] **Change the boarding window.** The gate's boarding window follows the expected departure (part 9).

### 8.3 Cancel a flight

- [ ] Record a **cancellation** for a flight with a booking. Search no longer offers that flight, and booking it is refused (409).
- [ ] The traveller gets a **cancellation email**. Open their booking: it says *This flight is cancelled*.
- [ ] **Full refund whenever.** Cancel that booking as the traveller: a full refund, even if the flight is soon.

### 8.4 Withdraw a mistaken disruption

- [ ] In **Withdraw a disruption**, enter the flight and date, tick the confirmation and press **Withdraw**. The flight is sold again, and every traveller still holding it gets a **"disruption withdrawn" email**.
- [ ] **Refused once people were moved.** After part 10 has moved bookings off a cancelled flight, try to withdraw it: refused, because bookings were already moved.

---

## Part 9. The gate

Sign in as `gate.one` (or `admin`). You need a **checked-in** boarding pass from part 5 (the text under its QR code).

- [ ] **Check without boarding.** Paste the pass in **Gate** and use the check: it reports good, naming the passenger, flight and seat. Nobody is boarded by a check.
- [ ] **Board.** Press **Board passenger**: boarded, with a boarding number.
- [ ] **Board again**: *already boarded*. Only the first scan counts, and it keeps who scanned.
- [ ] **A pass for another flight**: refused with a clear reason.
- [ ] **A tampered pass** (change one character): refused as not ours.
- [ ] **A pass that was checked out or replaced** (part 5): refused, even though its signature is real.
- [ ] **A cancelled flight's pass**: refused.
- [ ] **Camera scan** (if you have a webcam, or use your phone's browser with this site over your network): press the camera button, allow the camera, point it at a QR code. It fills the pass field and stops; it **never boards anybody by itself**.
- [ ] **A boarded passenger cannot be checked out.**
- [ ] **Who has boarded.** Enter the flight and date: counts of checked in, boarded and waiting, and each boarding's reference and place, **with no names**. For operations and administrators only; the gate agent does not see it.
- [ ] **Undo a boarding.** (OPS or ADMIN, never the gate agent.) Copy a row from the *Who has boarded* list into **Undo a boarding**, give a reason, tick the confirmation. The passenger stays checked in and can board again. The correction is kept.
- [ ] **Board a late passenger.** For a pass after the gate closed and before the flight is due to have left, **Board late** needs a reason and your name; it is marked late in the list.
- [ ] **The gate agent cannot do operations things.** As `gate.one`, only the Gate appears, and calling an ops endpoint with their token answers 403.

> **The boarding window.** Real boarding opens 45 minutes and closes 10 minutes before the expected departure (a delay moves it). This test stack opens it 100 days ahead so you can board at any time, which means you **cannot see *too early* here**. To see the gate close, scan a pass for a flight that leaves within the next 10 minutes (seed flights leave at fixed UTC times each day, for example SK001 at 08:30 and SK002 at 14:00), or record a delay and an on-time cancellation so that the departure is imminent. A route can have its own window (ADR-0077).

---

## Part 10. Rebooking after a cancellation (the agent)

The agent proposes where affected travellers could go; **a person approves; the booking service re-checks every rule**. Make sure `agent.ops` exists (part 8.1).

Set up a case: make **four bookings** on SK001 for the same day (1 to 3 passengers each, one of them checked in), then record a **cancellation** for that flight (part 8.3).

- [ ] On the operations page, **Move affected bookings** shows a proposal for each booking, **grouped by the flight they would go to**, plus a *Needs a person* group. (Same-day alternatives are taken first; the group of a party stays together on one flight.)
- [ ] **Find a booking** by its reference in the search box.
- [ ] **Filters** show a count for each: not tried yet, moved, refused, failed.
- [ ] **Select all / none** per group, and tick single bookings with their checkboxes.
- [ ] Press **move** for the selected: each booking is moved or refused with a reason. **Up to 50 moves go in one request**, and each is all or nothing.
- [ ] The traveller gets a **rebooked email**. If they were checked in, it says the move **checked them out**, that their old boarding passes no longer work, and that they must check in again.
- [ ] **Refused ones can be tried again.** Use the *refused* filter to look at them, fix the cause (for example free a seat), and retry only those.
- [ ] **A rebooking keeps a connection.** Book SK501 (CMB to DXB) with a connection to SK001 (DXB to LHR) a day later, cancel SK501: the proposal does not land too late for the next flight. A replacement that would is refused.
- [ ] **A very long list is windowed.** (Optional.) With more than 100 proposals the page draws the first 100 and **Show more** adds 100 at a time; the counts and moves still cover every booking.
- [ ] **The audit.** Moves are recorded per booking with who made them (`select * from rebooking;` in the booking database, part 12).

By default the agent uses a rules-based planner (no key, no cost). **Using the AI model (optional):** put `GROQ_API_KEY=...` and `SKYHUB_AGENT_PLANNER=groq` in `.env`, then `sk up -d disruption-agent`. As of ADR-0080 the model is safe (nothing unsafe passes the guardrails) but packs far fewer bookings than the rules, so compare before trusting it. To run the model's exam: `cd services/disruption-agent && .venv/Scripts/python -m evals --live --verbose` with the key and `GROQ_MODEL` set.

---

## Part 11. What a traveller must never see (security spot checks)

- [ ] **The services are not reachable from the website's side without a token.** `curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8082/api/v1/ops/staff -H 'Content-Type: application/json' -d '{}'` answers **401**.
- [ ] **A traveller's booking token cannot do staff work, and a staff token cannot manage a booking.** Tokens carry a scope (`manage`, `staff`, `account`).
- [ ] **Cookies.** In the browser's developer tools, *Application → Cookies* for localhost:3000: the session cookies are **HttpOnly**, **SameSite=Strict** and limited to their own path (`/manage`, `/account`, `/ops`). Page scripts cannot read them.
- [ ] **Links with secrets are hidden.** The emailed set-password and confirm pages are not indexed and send no referrer (view the page source for `noindex`).
- [ ] **Passwords and tokens are stored as hashes.** In part 12, `staff_account.password_hash` is a long BCrypt string, never the password.
- [ ] **Nothing sensitive in the logs.** `sk logs booking notification | grep -i -E "password|@example.com"` should show nothing from real traffic.
- [ ] **Deny by default.** `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8082/api/v1/anything` answers 401 or 403, not 200.
- [ ] **Rate limits.** Search about 61 times in a minute (hold the refresh key): the site answers *Too many searches* with a wait time. (Clear it with `sk exec redis redis-cli FLUSHALL`.)

## Part 12. Look inside the data

Personal data must be **encrypted at rest**. Use a booking you made with a real-looking name and email:

```bash
sk exec -T postgres psql -U skyhub -d booking -c "select locator, contact_email, left(contact_email_index,12) as index from pnr order by id desc limit 3;"
sk exec -T postgres psql -U skyhub -d booking -c "select first_name, last_name from pnr_passenger order by id desc limit 3;"
sk exec -T postgres psql -U skyhub -d booking -c "select username, left(password_hash,7) as hash_starts, role from staff_account;"
```

- [ ] The contact email, names and saved passengers are **unreadable ciphertext**, not `mira@example.com`.
- [ ] `contact_email_index` is a keyed hash (so the account can find its bookings without decrypting).
- [ ] Password hashes begin `$2a$` or `$2b$` (BCrypt).
- [ ] Other tables worth a look: `payment`, `outbox_event` (events waiting for or past Kafka), `rebooking`, `boarded_passenger`, `boarding_reversal` (append-only), `disruption`.

## Part 13. Metrics and monitoring

The test override publishes the management ports, so you can read the metrics yourself:

```bash
curl -s http://localhost:9082/actuator/prometheus | grep "^skyhub_" | head -30
```

- [ ] Counters exist for bookings, payments, holds, sign-ins and notifications, and they **move** when you do things in the site.
- [ ] After part 6, `skyhub_notifications_total` has counted your emails.
- [ ] The alert rules and the dashboard are in `observability/`; the runbook for each alert is `docs/runbooks/README.md`.

> **Not yet working for the containers:** Prometheus (`docker compose --profile observability up`) scrapes `host.docker.internal`, so it does **not** see these containers, and the Grafana dashboard will be empty. That is a known gap (ADR-0083). The metrics above are the way to look for now.

## Part 14. The automatic test suites

None of these needs the stack, except the browser tests. Needs JDK 25, Node 24 and Python.

```bash
./mvnw -B clean verify                       # all three Java services (integration tests start PostgreSQL in Docker; several minutes)
cd frontend && npm ci && npm test && npm run typecheck && npx eslint src && npx next build && cd ..
cd services/disruption-agent && python -m venv .venv && source .venv/Scripts/activate && pip install -e ".[dev]" && python -m pytest && cd ../..
python -m pytest docker/checks               # the image and compose guards (28 tests; needs Docker)
```

- [ ] Java: `BUILD SUCCESS` for all three services.
- [ ] Frontend: 581 tests pass; typecheck, lint and the build are clean.
- [ ] Agent: all tests pass (these include the guardrail fuzz test and the offline evals, no key needed).
- [ ] Guards: 28 pass.

**Browser tests** (Playwright) drive a real Chrome through search, the seat hold, payment, check-in and check-out, closing a booking and the undo-email page. They need the stack running (parts 1 to 4):

```bash
sk exec redis redis-cli FLUSHALL            # clear the rate limits first, and again between runs
cd frontend && npx playwright install chromium && npm run e2e
```

- [ ] The browser tests pass. (They are not run in CI.) Disruption notices, the refund retry and the camera scan are **not** covered by them.

## Part 15. Looking at the container setup itself

- [ ] `docker compose -f docker-compose.yml -f docker-compose.app.yml config -q` fails with a message that names the first missing key if you remove one from `.env`. It never starts with an empty key.
- [ ] `docker compose up -d` (the infrastructure file alone) still starts only Postgres, Redis, Kafka and Mailpit, and does not ask for the app's keys.
- [ ] Only the website publishes a port in the real app file. **Run without the test file** (`-f docker-compose.test.yml` left out) and confirm `curl http://localhost:8082/...` is refused.
- [ ] Each service runs as a non-root user: `sk exec -T booking id` shows `uid=10001`.
- [ ] No `.env` inside any image: `sk exec -T booking sh -c 'find / -name ".env*" -not -path "/proc/*" 2>/dev/null'` prints nothing.

---

## Part 16. Stopping and starting clean

```bash
sk down            # stops the stack; the data is kept
sk down -v         # stops it and ERASES the databases and Kafka: the way to start from nothing
```

Do `sk down -v` if you change `SKYHUB_PII_KEY`, or before a fresh run of the walk-through. (`sk` is the alias from 1.2; if you opened a new window, make it again.) The test stack's data lives in volumes named `skyhubtest_*`; your own development volumes are not touched.

If a page says you are going too fast: `sk exec redis redis-cli FLUSHALL`.

## When something is wrong

| You see | Likely cause |
|---|---|
| Compose stops naming a variable | That key is empty in `.env` (1.1) |
| `website` stays *starting* | It waits for flight-search, booking and the agent to be healthy: `sk ps`, then `sk logs <name>` |
| A search shows nothing | flight-search is unhealthy, or the date is too far ahead |
| No email in Mailpit | notification is down, or its `SKYHUB_LINK_TOKEN_KEY` differs from booking's (they share one `.env` value, so this should not happen); see part 6 |
| Sign-in does nothing | You are in Safari (secure cookies on `http://`); use Chrome or Firefox |
| *Too many requests / searches* | The rate limit: flush Redis (part 16) |
| Check-in refused | You started without `docker-compose.test.yml` (real window: 24 hours); or the booking was cancelled |
| The ops page has no proposals panel | The agent is not healthy, or the website lacks its token (both come from `.env`) |
| Port already in use | An older stack is still running: `docker ps`, stop it, or use another `-p` name |
| Anything about an alert | `docs/runbooks/README.md` |

## What this guide does not cover

There is no real payment provider (the cards are demo tokens), no SMS, no gateway and no Kubernetes. The website's traces were not looked at in a real Tempo. The Grafana dashboard does not yet show the containers (part 13). **Honest note on how this guide was made:** the stack start, the health checks, the live account and booking journeys (41 and 83 checks), the parked-message listing and the staff endpoint's refusal were run for real while writing it. The browser steps in parts 3 to 10 were written from the code and the decision records (ADRs) and **have not been walked through start to finish by anyone**. If a button or message differs from what you see, tell me and I will correct the guide.

---

## The one-page checklist

- [ ] 1 Stack up, nine containers healthy, home page and Mailpit open
- [ ] 2 Live check: 41 of 41 and 83 of 83
- [ ] 3 Search (direct, airport-changing connection, no result, bad input); seats and 10-minute hold; three cards; confirmation and email; hold expiry; no double-selling; no double charge
- [ ] 4 Manage: lookup, wrong name, change seat, cancel with refund and email
- [ ] 5 Check in one passenger, QR pass and email, frozen seat, check out, whole-flight check-out
- [ ] 6 Email survives notification being down; no parked messages
- [ ] 7 Account: sign up, password link, My bookings and filters, saved passengers, lock-out, reset, change email and undo, sign out everywhere
- [ ] 8 Staff: sign in, create gate and agent users, roles; delay, cancellation, withdrawal; emails
- [ ] 9 Gate: check, board, again, wrong flight, tampered, checked-out pass, camera, who has boarded, undo, board late, role limits
- [ ] 10 Rebooking: proposals grouped, find, filters, move, emails, retry refused, connection kept
- [ ] 11 Security spot checks
- [ ] 12 Data is encrypted; hashes are hashes
- [ ] 13 Metrics move
- [ ] 14 Java, frontend, agent and guard tests pass; browser tests pass
- [ ] 15 Container rules hold (keys, ports, non-root, no `.env` in images)
- [ ] 16 Clean reset works
