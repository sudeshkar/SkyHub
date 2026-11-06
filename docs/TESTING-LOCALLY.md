# Testing SkyHub end to end on your desktop

This guide starts the **whole system in containers** and then tests every feature built so far, the way a traveller, a member of staff and a gate agent would meet it. Follow the parts in order. Every step says what you should see; a ⚠ says what to do if you do not.

- Commands are for **PowerShell** on Windows (Windows PowerShell 5.1 and PowerShell 7 both work). Open it from the Start menu and go to the folder where you cloned SkyHub, for example `cd C:\Users\<you>\OneDrive\Desktop\Prepare\skyhub`. Run every command from that folder unless a step says otherwise.
- Time: about 20 minutes to start and run the automatic check, then 2 to 3 hours for the full manual walk-through. Parts 3 to 12 are independent once the stack is running, so you can do them over several sittings.
- Tick the boxes (`[ ]`) as you go. The one-page version is the checklist at the end.

**You need:** Docker Desktop (running), PowerShell, Python 3.11+ (for the automatic check and part 14), Chrome or Firefox. (Safari does not accept the site's secure cookies on `http://localhost`, so sign-in would fail there.) The JDK and Node are only needed for part 14.

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

## Part 0. Four helpers (paste once in every new PowerShell window)

PowerShell does not have `export`, `grep` or a usable `curl`, so these small functions do those jobs. They last only for the window you paste them into; **if a later step says a command is "not recognized", paste this block again.**

```powershell
# sk: the test stack's docker compose, with all three files and its own project name.
function sk { docker compose -p skyhubtest -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.test.yml @args }

# Import-DotEnv: loads every NAME=value line of .env into this window (never into a file, never printed).
function Import-DotEnv {
  param([string]$Path = '.env')
  Get-Content $Path | Where-Object { $_ -match '^\s*[A-Za-z_][A-Za-z0-9_]*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
  }
}

# Get-HttpCode: the status code of a request (200, 401, 409 ...) even when it is an error.
function Get-HttpCode {
  param([string]$Uri, [string]$Method = 'GET', [string]$Body, [hashtable]$Headers = @{})
  $call = @{ Uri = $Uri; Method = $Method; Headers = $Headers; UseBasicParsing = $true }
  if ($Body) { $call.Body = $Body; $call.ContentType = 'application/json' }
  try { [int](Invoke-WebRequest @call).StatusCode }
  catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { throw } }
}

# New-Key: a random 256-bit key, base64, for .env.
function New-Key { $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b) }
```

In this guide, always write `curl.exe` (with `.exe`): plain `curl` in Windows PowerShell 5.1 is a different command.

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

Make a key by running `New-Key` (use a different one for each of the first four, and one for `SKYHUB_AGENT_API_TOKEN`), then paste it into `.env`. Use the username `agent.ops` for the agent and `admin` for the administrator, and passwords of 12 to 72 characters. **`GROQ_API_KEY` is optional** (part 10).

Check which are still empty, without printing the secrets:

```powershell
foreach ($k in 'SKYHUB_PII_KEY','SKYHUB_MANAGE_TOKEN_KEY','SKYHUB_BOARDING_PASS_KEY','SKYHUB_LINK_TOKEN_KEY','SKYHUB_AGENT_API_TOKEN','SKYHUB_AGENT_USERNAME','SKYHUB_AGENT_PASSWORD','SKYHUB_BOOTSTRAP_ADMIN_USERNAME','SKYHUB_BOOTSTRAP_ADMIN_PASSWORD','GRAFANA_ADMIN_PASSWORD') {
  $m = Select-String -Path .env -Pattern "^$k=(.*)$"
  $v = if ($m) { $m.Matches[0].Groups[1].Value.Trim() } else { '' }
  if ($v) { "ok     $k" } else { "EMPTY  $k" }
}
```

- [ ] No line says `EMPTY`.

> Never commit `.env` (it is git-ignored). `SKYHUB_PII_KEY` encrypts personal data: a database written with one key cannot be read with another. If you change it, start clean (part 16).

### 1.2 Start

```powershell
sk up -d --build
```

`sk` is the helper from part 0: it adds `-p skyhubtest` (this test stack and its data stay apart from anything else you run) and the three compose files. `docker-compose.test.yml` is for desktop testing only: it publishes the service ports so you can call them from PowerShell, and opens check-in and boarding 100 days ahead so the seed flights can be checked in to. The first start builds five images and takes several minutes.

```powershell
sk ps
```

- [ ] Nine containers show **(healthy)**: `booking`, `flight-search`, `notification`, `disruption-agent`, `website`, `postgres`, `kafka`, `redis`, `mailpit`.

⚠ A missing key stops the start with a message naming it. ⚠ `website` waits for the other four to be healthy, so give it a minute.

### 1.3 First look

```powershell
curl.exe -s http://localhost:9081/actuator/health     # {"groups":["liveness","readiness"],"status":"UP"}
curl.exe -s http://localhost:3000/healthz              # {"status":"UP"}
```

- [ ] Open http://localhost:3000: the SkyHub home page appears.
- [ ] Open http://localhost:8025: an empty Mailpit inbox.

---

## Part 2. The automatic live check (about 5 minutes)

Before clicking anything, let the repository's own check drive the **real chain** (booking, outbox, Kafka, notification, email) with no mocks. It covers accounts, booking, payment, check-in, check-out, the gate, a cancelled flight, a rebooking that keeps a connection, a cancellation and refund.

```powershell
Import-DotEnv                                  # loads your keys into THIS window only
$env:COMPOSE_PROJECT_NAME = 'skyhubtest'
$env:LIVE_STAFF_USERNAME  = $env:SKYHUB_BOOTSTRAP_ADMIN_USERNAME
$env:LIVE_STAFF_PASSWORD  = $env:SKYHUB_BOOTSTRAP_ADMIN_PASSWORD
python tools\live-check\account_journey.py | Select-Object -Last 3
python tools\live-check\booking_journey.py | Select-Object -Last 3
Remove-Item Env:COMPOSE_PROJECT_NAME           # so later plain "docker compose" commands do not target the test stack
```

- [ ] The account journey ends `41 of 41 checks passed`.
- [ ] The booking journey ends `83 of 83 checks passed`.

⚠ If a few checks fail, run it once more: it is built for a fresh stack, and one run in five once missed a check that was not captured (ADR-0083). If the same check fails twice, copy its `FAIL` line and the lines above it (run without `| Select-Object -Last 3` to see them).
⚠ If `python` opens the Microsoft Store or says "Python was not found", use the project's own Python instead: `.\services\disruption-agent\.venv\Scripts\python` in place of `python`.

These two scripts create their own data (accounts, bookings, a cancelled flight). **To get a clean stack for the manual parts, reset it (part 16) and start again**, or carry on: the manual parts work either way, but the flight the script cancelled will stay cancelled.

---

## Part 3. A traveller books a trip

Open http://localhost:3000.

### 3.1 Search

- [ ] **Direct flight.** Search **DXB to LHR**, a date 3 days ahead, 2 passengers. One result, SK001, with times and a price from $540 per person.
- [ ] **A connection that changes airports.** Search **BOM to JFK**. SK201 lands at DXB and SK901 leaves from DWC: you should see itineraries that connect with a change of airport and a layover time.
- [ ] **Nothing found.** Search a route with no flights (for example DXB to DXB): a clear message, not an error page.
- [ ] **Bad input.** Open `http://localhost:3000/search?origin=DXB&destination=LHR&date=nonsense`: a friendly message.

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

- [ ] **Booking works while notification is down.** `sk stop notification`, make a booking (part 3), confirm it. No email yet. `sk start notification`: the confirmation arrives in Mailpit within a minute.
- [ ] **No parked messages.** `curl.exe -s http://localhost:9083/actuator/deadletters` answers `{"parked":0,"messages":[]}`.
- [ ] **(Optional, long) A mail outage.** `sk stop mailpit`, make a booking, wait. The email retries on delayed topics for about 22 minutes, then parks. `sk start mailpit`. If it parked, put it back with:
  ```powershell
  Invoke-RestMethod -Method Post -Uri http://localhost:9083/actuator/deadletters -ContentType 'application/json' -Body (@{ limit = 100; operator = 'your.name' } | ConvertTo-Json)
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

There is no form for creating staff. An administrator uses the API (roles: `OPS`, `ADMIN`, `GATE`). Paste this in PowerShell (the helpers from part 0 must be loaded):

```powershell
Import-DotEnv
$login = @{ username = $env:SKYHUB_BOOTSTRAP_ADMIN_USERNAME; password = $env:SKYHUB_BOOTSTRAP_ADMIN_PASSWORD } | ConvertTo-Json
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8082/api/v1/ops/sessions -ContentType 'application/json' -Body $login).accessToken
$auth  = @{ Authorization = "Bearer $token" }

$gate  = @{ username = 'gate.one'; password = 'a-gate-password-12'; role = 'GATE' } | ConvertTo-Json
Get-HttpCode http://localhost:8082/api/v1/ops/staff -Method Post -Body $gate -Headers $auth      # a gate agent

$agent = @{ username = $env:SKYHUB_AGENT_USERNAME; password = $env:SKYHUB_AGENT_PASSWORD; role = 'OPS' } | ConvertTo-Json
Get-HttpCode http://localhost:8082/api/v1/ops/staff -Method Post -Body $agent -Headers $auth     # the agent's own account
```

- [ ] Both answer `201`. (Usernames are lower case, 3 to 40 characters.) Running them twice answers `409`.
- [ ] **Roles.** Sign out, then sign in as `gate.one` (password `a-gate-password-12`): you see **only the Gate** section. Sign in as `admin`: you see everything.

### 8.2 Record a delay

Use a flight with a booking (for example SK001 on the date you booked). Sign in as `admin`.

- [ ] In **Record a delay or cancellation**: flight, date, type *delay*, a revised departure a few hours later and a reason, then **Record**. A confirmation appears.
- [ ] **Search shows it.** Search the same flight: the revised time is shown.
- [ ] **A connection follows the delay.** A layover that was fine before and is now too short is no longer offered (search judges layovers on expected times).
- [ ] The traveller gets a **delay email** in Mailpit.
- [ ] **The boarding window follows it.** The gate's boarding window moves with the expected departure (part 9).

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

> **The boarding window.** Real boarding opens 45 minutes and closes 10 minutes before the expected departure (a delay moves it). This test stack opens it 100 days ahead so you can board at any time, which means you **cannot see *too early* here**. To see the gate close, scan a pass for a flight that leaves within the next 10 minutes (seed flights leave at fixed UTC times each day, for example SK001 at 08:30 and SK002 at 14:00), or record a delay so that the departure is imminent. A route can have its own window (ADR-0077).

---

## Part 10. Rebooking after a cancellation (the agent)

The agent proposes where affected travellers could go; **a person approves; the booking service re-checks every rule**. Make sure `agent.ops` exists (part 8.1).

Set up a case: make **four bookings** on SK001 for the same day (1 to 3 passengers each, one of them checked in), then record a **cancellation** for that flight (part 8.3).

- [ ] On the operations page, **Move affected bookings** shows a proposal for each booking, **grouped by the flight they would go to**, plus a *Needs a person* group. (A party stays together on one flight.)
- [ ] **Find a booking** by its reference in the search box.
- [ ] **Filters** show a count for each: not tried yet, moved, refused, failed.
- [ ] **Select all / none** per group, and tick single bookings with their checkboxes.
- [ ] Press the move button for the selected: each booking is moved or refused with a reason. **Up to 50 moves go in one request**, and each is all or nothing.
- [ ] The traveller gets a **rebooked email**. If they were checked in, it says the move **checked them out**, that their old boarding passes no longer work, and that they must check in again.
- [ ] **Refused ones can be tried again.** Use the *refused* filter to look at them, fix the cause (for example free a seat), and retry only those.
- [ ] **A rebooking keeps a connection.** Book SK501 (CMB to DXB) with a connection to SK001 (DXB to LHR) a day later, cancel SK501: the proposal does not land too late for the next flight. A replacement that would is refused.
- [ ] **A very long list is windowed.** (Optional.) With more than 100 proposals the page draws the first 100 and **Show more** adds 100 at a time; the counts and moves still cover every booking.
- [ ] **The audit.** Moves are recorded per booking (`select * from rebooking;` in the booking database, see part 12).

By default the agent uses a rules-based planner (no key, no cost). **Using the AI model (optional):** put `GROQ_API_KEY=...` and `SKYHUB_AGENT_PLANNER=groq` in `.env`, then run `sk up -d disruption-agent`. As of ADR-0080 the model is safe (nothing unsafe passes the guardrails) but packs far fewer bookings than the rules, so compare before trusting it. To run the model's exam:

```powershell
Set-Location services\disruption-agent
$env:GROQ_API_KEY = '<your key>'
$env:GROQ_MODEL   = 'openai/gpt-oss-120b'
.\.venv\Scripts\python -m evals --live --verbose
Set-Location ..\..
```

---

## Part 11. What a traveller must never see (security spot checks)

- [ ] **The services refuse callers without a token.** `Get-HttpCode http://localhost:8082/api/v1/ops/staff -Method Post -Body '{}'` answers **401**.
- [ ] **Deny by default.** `Get-HttpCode http://localhost:8082/api/v1/anything` answers 401 or 403, never 200.
- [ ] **A traveller's booking token cannot do staff work, and a staff token cannot manage a booking.** Tokens carry a scope (`manage`, `staff`, `account`).
- [ ] **Cookies.** In the browser's developer tools (F12), *Application → Cookies* for localhost:3000: the session cookies are **HttpOnly**, **SameSite=Strict** and limited to their own path (`/manage`, `/account`, `/ops`). Page scripts cannot read them.
- [ ] **Links with secrets are hidden.** The emailed set-password and confirm pages are not indexed and send no referrer (view the page source for `noindex`).
- [ ] **Passwords and tokens are stored as hashes.** In part 12, `staff_account.password_hash` is a long BCrypt string, never the password.
- [ ] **No traveller data in the logs.** After you have made some bookings with `@example.com` addresses:
  ```powershell
  sk logs booking notification | Select-String -Pattern '@[a-z0-9-]+\.(com|example)|token='
  ```
  prints nothing. (The word *password* does appear in Kafka's own start-up lines, as `[hidden]`; that is harmless.)
- [ ] **Rate limits.** Search about 61 times in a minute (hold the refresh key): the site answers *Too many searches* with a wait time. Clear it with `sk exec redis redis-cli FLUSHALL`.

## Part 12. Look inside the data

Personal data must be **encrypted at rest**. Use a booking you made with a real-looking name and email:

```powershell
sk exec -T postgres psql -U skyhub -d booking -c "select locator, contact_email, left(contact_email_index,12) as index from pnr order by id desc limit 3;"
sk exec -T postgres psql -U skyhub -d booking -c "select first_name, last_name from pnr_passenger order by id desc limit 3;"
sk exec -T postgres psql -U skyhub -d booking -c "select username, left(password_hash,7) as hash_starts, role from staff_account;"
```

- [ ] The contact email, names and saved passengers are **unreadable ciphertext**, not `mira@example.com`.
- [ ] `contact_email_index` is a keyed hash (so the account can find its bookings without decrypting).
- [ ] Password hashes begin `$2a$` or `$2b$` (BCrypt).
- [ ] Other tables worth a look: `payment`, `outbox_event` (events waiting for or past Kafka), `rebooking`, `boarded_passenger`, `boarding_reversal` (append-only), `disruption`. List them with `sk exec -T postgres psql -U skyhub -d booking -c "\dt"`.

## Part 13. Metrics and monitoring

The test override publishes the management ports, so you can read the metrics yourself:

```powershell
curl.exe -s http://localhost:9082/actuator/prometheus | Select-String '^skyhub_' | Select-Object -First 30
```

- [ ] Counters exist for bookings, payments, holds, sign-ins and notifications, and they **move** when you do things in the site (run the command again after a booking).
- [ ] After part 6, `skyhub_notifications_total` has counted your emails.
- [ ] The alert rules and the dashboard are in `observability\`; the runbook for each alert is `docs\runbooks\README.md`.

> **Not yet working for the containers:** Prometheus (`docker compose --profile observability up`) scrapes `host.docker.internal`, so it does **not** see these containers, and the Grafana dashboard will be empty. That is a known gap (ADR-0083). The metrics above are the way to look for now.

## Part 14. The automatic test suites

None of these needs the stack, except the browser tests. Run them one block at a time, so you can see which one fails (Windows PowerShell 5.1 does not stop at the first error).

**Java** (needs a JDK 25 or newer; set `JAVA_HOME` to it for this window first; integration tests start PostgreSQL in Docker and take several minutes):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-26.0.2'      # change to where your JDK is
.\mvnw.cmd -B clean verify
```

**Website** (needs Node 24):

```powershell
Set-Location frontend
npm ci
npm test
npm run typecheck
npx eslint src
npx next build
Set-Location ..
```

**Disruption agent** (Python):

```powershell
Set-Location services\disruption-agent
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".[dev]"
.\.venv\Scripts\python -m pytest
Set-Location ..\..
```

**Image and compose guards** (needs Docker):

```powershell
.\services\disruption-agent\.venv\Scripts\python -m pytest docker/checks
```

- [ ] Java: `BUILD SUCCESS` for all three services.
- [ ] Website: 581 tests pass; typecheck, lint and the build are clean.
- [ ] Agent: all tests pass (these include the guardrail fuzz test and the offline evals, no key needed).
- [ ] Guards: 28 pass.

**Browser tests** (Playwright) drive a real Chrome through search, the seat hold, payment, check-in and check-out, closing a booking and the undo-email page. They need the stack running (parts 1.1 to 1.3):

```powershell
sk exec redis redis-cli FLUSHALL            # clear the rate limits first, and again between runs
Set-Location frontend
npx playwright install chromium
npm run e2e
Set-Location ..
```

- [ ] The browser tests pass. (They are not run in CI.) Disruption notices, the refund retry and the camera scan are **not** covered by them.

## Part 15. Looking at the container setup itself

- [ ] **A missing key refuses to start.** Compose stops and names a missing key rather than starting with an empty one:
  ```powershell
  Set-Content "$env:TEMP\empty.env" ''
  docker compose --env-file "$env:TEMP\empty.env" -f docker-compose.yml -f docker-compose.app.yml config -q
  Remove-Item "$env:TEMP\empty.env"
  ```
  You see `required variable ... is missing a value` (it names the first one it finds).
- [ ] **The infrastructure alone asks for none of the app's keys.** With only the Grafana password given, this succeeds (`0` below):
  ```powershell
  Set-Content "$env:TEMP\g.env" 'GRAFANA_ADMIN_PASSWORD=x'
  docker compose --env-file "$env:TEMP\g.env" -f docker-compose.yml config -q; $LASTEXITCODE
  Remove-Item "$env:TEMP\g.env"
  ```
- [ ] **Only the website is published in the real app file.** Restart without the test file, and the services are no longer reachable from your desktop:
  ```powershell
  docker compose -p skyhubtest -f docker-compose.yml -f docker-compose.app.yml up -d
  curl.exe -s -m 3 http://localhost:8082/actuator/health      # fails: "Failed to connect" (nothing is published)
  curl.exe -s http://localhost:3000/healthz                   # still answers
  sk up -d                                                    # put the test file back
  ```
- [ ] **Each service runs as a non-root user.** `sk exec -T booking id` shows `uid=10001`.
- [ ] **No `.env` inside any image.** This prints nothing:
  ```powershell
  sk exec -T booking sh -c "find / -name '.env*' -not -path '/proc/*' 2>/dev/null"
  ```

---

## Part 16. Stopping and starting clean

```powershell
sk down            # stops the stack; the data is kept
sk down -v         # stops it and ERASES the databases and Kafka: the way to start from nothing
```

Do `sk down -v` if you change `SKYHUB_PII_KEY`, or before a fresh run of the walk-through. (`sk` is the helper from part 0; paste it again in a new window.) The test stack's data lives in volumes named `skyhubtest_*`; your own development volumes are not touched.

If a page says you are going too fast: `sk exec redis redis-cli FLUSHALL`.

## When something is wrong

| You see | Likely cause |
|---|---|
| `sk` / `Import-DotEnv` / `Get-HttpCode` "is not recognized" | You are in a new window: paste part 0 again |
| Compose stops naming a variable | That key is empty in `.env` (1.1) |
| `website` stays *starting* | It waits for flight-search, booking and the agent to be healthy: `sk ps`, then `sk logs <name>` |
| A search shows nothing | flight-search is unhealthy, or the date is too far ahead |
| No email in Mailpit | notification is down, or its `SKYHUB_LINK_TOKEN_KEY` differs from booking's (they share one `.env` value, so this should not happen); see part 6 |
| Sign-in does nothing | You are in Safari (secure cookies on `http://`); use Chrome or Firefox |
| *Too many requests / searches* | The rate limit: flush Redis (part 16) |
| Check-in refused | You started without `docker-compose.test.yml` (real window: 24 hours); or the booking was cancelled |
| The ops page has no proposals panel | The agent is not healthy, or the website lacks its token (both come from `.env`) |
| Port already in use | An older stack is still running: `docker ps`, stop it, or use another `-p` name |
| `python` opens the Microsoft Store | Use `.\services\disruption-agent\.venv\Scripts\python` instead, or install Python from python.org |
| `npm` says "running scripts is disabled on this system" | Run once: `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned`, then open a new window |
| Maven says it needs a newer Java | Set `$env:JAVA_HOME` to a JDK 25 or newer (part 14) |
| Anything about an alert | `docs\runbooks\README.md` |

## What this guide does not cover

There is no real payment provider (the cards are demo tokens), no SMS, no gateway and no Kubernetes. The website's traces were not looked at in a real Tempo. The Grafana dashboard does not yet show the containers (part 13).

**Honest note on how this guide was made.** Run for real while writing it, in Windows PowerShell 5.1: the three helpers (`sk`, `Import-DotEnv`, `Get-HttpCode`) and `New-Key`; the stack start and health checks; the status-code, parked-message, metrics and database queries; the user and `.env` checks inside the containers; the failed-login request; the log check; the two config checks of part 15; and (earlier, on the same setup) the live account and booking journeys (41 and 83 checks). **Not run:** the JDK, website and agent test commands of part 14 as written here, the `-f` restart of part 15, the staff-creation block with the real password, and the browser steps of parts 3 to 10, which were written from the code and the decision records (ADRs) and have **not been walked through start to finish by anyone**. If a command or a button differs from what you see, tell me and I will correct the guide.

---

## The one-page checklist

- [ ] 0 Helpers pasted
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
- [ ] 14 Java, website, agent and guard tests pass; browser tests pass
- [ ] 15 Container rules hold (keys, ports, non-root, no `.env` in images)
- [ ] 16 Clean reset works
