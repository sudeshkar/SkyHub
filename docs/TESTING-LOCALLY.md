# Testing SkyHub end to end on your desktop

A walk-through that starts everything on one machine and then uses it the way a traveller and a member of staff would. Do the parts in order; each one says what you should see. Commands are for **Git Bash** on Windows (macOS and Linux work the same).

**You need:** JDK 25, Node.js 24, Docker Desktop (running), Python 3.11+ (only for part 9).

Ports used: website 3000, flight-search 8081, booking 8082, notification 8083, agent 8084, PostgreSQL 5432, Redis 6379, Kafka 9092, Mailpit inbox 8025, Grafana 3001 (optional).

---

## Part 1. Start the supporting servers

```bash
cd skyhub
docker compose up -d
docker compose ps          # postgres, redis, kafka, mailpit should be "running" / "healthy"
```

If your PostgreSQL volume is older than the notification service, create its database once:
`docker compose exec postgres psql -U skyhub -d postgres -c "CREATE DATABASE notification OWNER skyhub;"`

**Check:** open http://localhost:8025. You should see an empty Mailpit inbox.

## Part 2. Make your keys and a staff login

Use **one** Git Bash window for parts 2 and 3 so the variables are shared. Spring does not read `.env`.

```bash
export SKYHUB_PII_KEY=$(openssl rand -base64 32)
export SKYHUB_MANAGE_TOKEN_KEY=$(openssl rand -base64 32)
export SKYHUB_BOARDING_PASS_KEY=$(openssl rand -base64 32)
export SKYHUB_LINK_TOKEN_KEY=$(openssl rand -base64 32)   # the notification service needs this SAME value
export SKYHUB_BOOTSTRAP_ADMIN_USERNAME=admin
export SKYHUB_BOOTSTRAP_ADMIN_PASSWORD='ChooseA-LongPassword-12+'
```

Keep `SKYHUB_PII_KEY`: bookings saved with one key cannot be read with another. If you lose it, wipe the databases (`docker compose down -v`) and start again.

## Part 3. Start the services (one terminal each)

Every terminal that starts booking or notification must have the keys from part 2 exported.

```bash
# terminal A
./mvnw -pl services/flight-search spring-boot:run
# terminal B (keys from part 2)
./mvnw -pl services/booking spring-boot:run
# terminal C (needs SKYHUB_LINK_TOKEN_KEY, the same value)
./mvnw -pl services/notification spring-boot:run
```

**Check:** `curl http://localhost:9081/actuator/health` and `curl http://localhost:9082/actuator/health` each answer `{"status":"UP"}`. Booking refuses to start without its keys; the message names the missing one.

## Part 4. Start the website

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:3000. (Redis must be running; it rate-limits the site.)

---

## Part 5. The traveller's journey

1. **Search.** Pick DXB to LHR, a date a few days ahead, 2 passengers. You should see itineraries with times and prices. Try a route with a connection too.
2. **Choose seats.** Pick two economy seats. A **10-minute countdown** starts: the seats are held for you.
3. **Passenger details.** Type two names and a contact email, for example `mira@example.com`.
4. **Pay.** Three demo cards are offered:
   - Visa 4242: approved.
   - Visa 0002: declined. Try this first; you should get a clear message, no booking, and your seats still held.
   - Visa 9995: provider down. Same: no booking, no charge.
   Then pay with Visa 4242.
5. **Confirmation.** You get a booking reference (six characters).
6. **The email.** In Mailpit (http://localhost:8025) the confirmation arrives within a few seconds. If it does not, the notification service is not running; bookings still work and the email is sent when it starts.

**Things worth trying here**
- Let the countdown run out, then try to pay: it should refuse and free the seats.
- Open the same flight in a second browser, take the same seats: the second person is told they are taken (no double-selling).
- Click pay twice quickly: one booking, one charge.

## Part 6. Manage and check in

1. **Manage booking:** reference plus a passenger's last name. You should see the trip.
2. **Change a seat.** Try one that is taken: refused.
3. **Check in** is only accepted from 24 hours to 60 minutes before departure. For a flight further away it says so; that is correct. To test a flight inside the window, pick one that departs within a day in the search.
4. Checking in gives each passenger a **boarding pass with a QR code**; the email also carries it.
5. **Cancel** the booking: a refund is shown, and a cancellation email arrives in Mailpit.

## Part 7. A traveller account

1. Go to http://localhost:3000/account and sign up with an email.
2. Mailpit receives a "choose a password" email. Click its link (it opens the website). Set a password and sign in.
3. The account page lists bookings made with that email. Add a **saved passenger**, then start a new booking: the passenger step offers "fill from saved".
4. Try: a wrong password five times (the account locks for 15 minutes), "sign out everywhere", change email (a confirmation link is sent to the new address and an undo link to the old).

## Part 8. Staff: operations and the gate

1. Go to http://localhost:3000/ops and sign in with the admin you created in part 2.
2. **Create other staff.** There is no form for this; an administrator uses the API. Roles are `OPS`, `ADMIN` and `GATE`. In Git Bash:
   ```bash
   TOKEN=$(curl -s -X POST http://localhost:8082/api/v1/ops/sessions -H 'Content-Type: application/json' \
     -d '{"username":"admin","password":"ChooseA-LongPassword-12+"}' | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')
   curl -s -X POST http://localhost:8082/api/v1/ops/staff -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"username":"gate.one","password":"a-gate-password-12","role":"GATE"}'
   ```
   Usernames are lower case, 3 to 40 characters; passwords 12 or more. Use the password you exported in part 2. You should get `201`. Create `agent.ops` the same way with role `OPS` if you will do part 9.
3. **Record a disruption.** Enter a flight number and date (use one you booked) and mark it delayed or cancelled. Then:
   - Search for that flight: a cancelled flight is no longer offered; a delay is shown.
   - The affected traveller gets an email in Mailpit.
   - Booking that cancelled flight is refused.
4. **The gate.** Sign in as the GATE user, enter the flight and date, and **scan or type a boarding pass**. Try: a valid pass (accepted), the same pass again (already boarded), a pass for another flight (refused), a tampered pass (refused). Undo a boarding and board again.
   To scan with a camera, allow the camera in the browser; on a laptop you can instead show a pass on a phone.

## Part 9. The rebooking assistant (optional)

Proposes where affected travellers could go; a person approves. It needs Python and an OPS staff login for the agent. A Groq key is optional: without one it uses its rules-based planner.

```bash
cd services/disruption-agent
python -m venv .venv && source .venv/Scripts/activate
pip install -e ".[dev]"
python -m pytest                       # 98 tests, no key needed

export SKYHUB_AGENT_USERNAME=agent.ops     # create this OPS account first, see part 8
export SKYHUB_AGENT_PASSWORD='at-least-12-characters'
export SKYHUB_AGENT_API_TOKEN=$(openssl rand -base64 32)
python -m disruption_agent.web         # http://127.0.0.1:8084
```

Restart the website with the same secret so the ops page can reach the agent:

```bash
cd frontend
SKYHUB_AGENT_API_URL=http://localhost:8084 SKYHUB_AGENT_API_TOKEN='<the same value>' npm run dev
```

Record a **cancellation** for a flight with a booking, open /ops, and you should see proposals for the affected bookings with check boxes. Approve some: the traveller is moved and emailed. The booking service re-checks every rule, so a bad proposal is refused rather than applied.

---

## Part 10. The automatic tests

These are separate from the manual walk-through and none needs the stack, except the browser tests.

```bash
./mvnw clean verify                         # all Java services; integration tests start PostgreSQL in Docker
cd frontend
npm test                                    # unit and component tests (579)
npm run typecheck && npx eslint src
npx next build
cd ../services/disruption-agent && python -m pytest
```

**Browser tests** drive a real Chrome through the journeys above. They need parts 1 to 4 running, and the website's rate-limit counters cleared between runs (`docker compose exec redis redis-cli FLUSHALL`):

```bash
cd frontend
npx playwright install chromium            # once
npm run e2e
```

To also check emails in those tests: `SKYHUB_MAILPIT_URL=http://localhost:8025 npm run e2e`.

**Tracing check** (the website's traces; needs a build first): `cd frontend && npm run build && node scripts/trace-check.mjs`. It starts its own stub collector and does not need the stack.

## Part 11. Looking inside (optional)

```bash
export GRAFANA_ADMIN_PASSWORD='choose-one'    # before starting it
docker compose --profile observability up -d
```

Start each service with `TRACING_EXPORT_ENABLED=true` (and the website too) to send traces to Tempo. Open http://localhost:3001 (user `admin`), the *SkyHub overview* dashboard shows requests, bookings, holds and payments, and Explore shows logs (Loki) and traces (Tempo).

## Stopping and starting clean

- Stop services with Ctrl+C. `docker compose down` keeps the data; **`docker compose down -v` wipes all databases and Kafka**, which is the way to start from nothing. Do that if you change `SKYHUB_PII_KEY`.
- If a page says you are going too fast, you hit the rate limit: `docker compose exec redis redis-cli FLUSHALL`.

## When something is wrong

| You see | Likely cause |
|---|---|
| Booking service exits at start | A key from part 2 is not exported in that terminal |
| Search shows nothing | flight-search is not running, or the date is too far ahead |
| No email in Mailpit | notification is not running, or its `SKYHUB_LINK_TOKEN_KEY` differs from booking's |
| "Too many requests" | The rate limit; clear Redis as above |
| Check-in refused | Outside 24 h to 60 min before departure; correct behaviour |
| Ops page has no proposals panel | The agent is not running, or the website lacks `SKYHUB_AGENT_API_URL` and `SKYHUB_AGENT_API_TOKEN` |
| Port already in use | An earlier run is still alive; stop it or change the port |
| Runbooks for each alert | `docs/runbooks/README.md` |

## What this walk-through does not cover

There is no real payment provider (the cards above are demo tokens), no SMS, and the gateway and deployment are not built. The agent's Groq model has no live evaluation baseline yet (see `docs/ROADMAP.md`). I wrote this guide from reading the configuration and code; **I have not walked through it start to finish on a clean machine**, so if a step differs from what you see, tell me and I will correct it.
