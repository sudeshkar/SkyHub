"""Drives the account journey through the REAL chain: booking -> outbox -> Kafka -> notification -> SMTP -> Mailpit.
Nothing here is mocked. Prints PASS/FAIL per check and exits non-zero if any fails. See README.md in this folder for how to run it."""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

BOOKING = os.environ.get("BOOKING_URL", "http://localhost:8082")
MAILPIT = os.environ.get("MAILPIT_URL", "http://localhost:8025")
REPO = pathlib.Path(__file__).resolve().parents[2]   # where docker-compose.yml is, for reading the Kafka topic
PASSWORD = "a long enough passphrase"
run = uuid.uuid4().hex[:8]
OLD = f"live.old.{run}@example.com"
NEW = f"live.new.{run}@example.com"
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    return ok


def call(method, url, body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def wait_mail(to, subject_part, timeout=45):
    """The first message to this address whose subject contains the text, read from Mailpit."""
    end = time.time() + timeout
    while time.time() < end:
        status, listing = call("GET", f"{MAILPIT}/api/v1/search?query=" + urllib.request.quote(f"to:{to}"))
        for m in (listing or {}).get("messages", []):
            if subject_part.lower() in m["Subject"].lower():
                _, full = call("GET", f"{MAILPIT}/api/v1/message/{m['ID']}")
                return full
        time.sleep(1)
    return None


def link_token(mail, path):
    found = re.search(re.escape(path) + r"\?token=([A-Za-z0-9_%.\-]+)", mail["Text"])
    return urllib.request.unquote(found.group(1)) if found else None


def kafka_events():
    """Everything on the real booking events topic, as a person with access to the broker would read it."""
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
         "--topic", "skyhub.booking.events", "--from-beginning", "--timeout-ms", "6000"],
        capture_output=True, text=True, cwd=REPO)
    return out.stdout


# ---- 1. sign up: the link goes out by email, and what is on the topic is sealed ------------------------------------------------
status, _ = call("POST", BOOKING + "/api/v1/accounts/password-links", {"email": OLD})
check("sign-up link request accepted (202)", status == 202, status)
mail = wait_mail(OLD, "Finish creating")
check("the sign-up email arrived in Mailpit", mail is not None)
token = link_token(mail, "/account/set-password") if mail else None
check("the email carries a link to the set-password page with a token", bool(token))

topic = kafka_events()
mine = [line for line in topic.splitlines() if OLD in line]
check("the event reached the real Kafka topic", len(mine) >= 1, f"{len(mine)} events")
if mine and token:
    event = json.loads(mine[-1])
    check("the topic event holds sealedToken (v1:) and no plain token", event.get("sealedToken", "").startswith("v1:") and "token" not in event, list(event))
    check("the plain token from the email appears nowhere on the topic", token not in topic)

# ---- 2. choose a password with the link, sign in ----------------------------------------------------------------------------------
status, _ = call("POST", BOOKING + "/api/v1/accounts/passwords", {"token": token, "password": PASSWORD})
check("the emailed token sets the password (204)", status == 204, status)
status, _ = call("POST", BOOKING + "/api/v1/accounts/passwords", {"token": token, "password": PASSWORD})
check("the same link works only once (400)", status == 400, status)
status, session = call("POST", BOOKING + "/api/v1/accounts/sessions", {"email": OLD, "password": PASSWORD})
check("sign-in works (201)", status == 201, status)
first = session["accessToken"] if status == 201 else None
status, me = call("GET", BOOKING + "/api/v1/accounts/me", token=first)
check("the account shows its address", status == 200 and me["email"] == OLD, me)

# ---- 3. change the address: link to the NEW address, sealed on the topic -----------------------------------------------------------
status, _ = call("POST", BOOKING + "/api/v1/accounts/me/email-change", {"newEmail": NEW, "password": "wrong wrong wrong"}, first)
check("a wrong password is refused with 403, not 401", status == 403, status)
status, _ = call("POST", BOOKING + "/api/v1/accounts/me/email-change", {"newEmail": NEW, "password": PASSWORD}, first)
check("the change request is accepted (202)", status == 202, status)
change_mail = wait_mail(NEW, "Confirm your new")
check("the confirmation email arrived at the NEW address", change_mail is not None)
change_token = link_token(change_mail, "/account/confirm-email") if change_mail else None
check("it carries a confirm link with a token", bool(change_token))
topic = kafka_events()
check("the email-change token is not on the topic in the clear", bool(change_token) and change_token not in topic)
status, me = call("GET", BOOKING + "/api/v1/accounts/me", token=first)
check("nothing has changed until the link is used", status == 200 and me["email"] == OLD, me)

status, _ = call("POST", BOOKING + "/api/v1/accounts/me/email-change/confirmation", {"token": change_token}, first)
check("the link, used while signed in, moves the account (204)", status == 204, status)

# ---- 4. the old address is told; the old session is gone -----------------------------------------------------------------------------
notice = wait_mail(OLD, "was changed")
check("the OLD address was emailed a notice", notice is not None)
if notice:
    body = notice["Text"]
    check("the notice names the new address only masked", "l****@example.com" in body and NEW not in body, body[:200])
    check("the notice has no link and no token", "http" not in body and "token" not in body.lower())
status, _ = call("GET", BOOKING + "/api/v1/accounts/me", token=first)
check("the session that was open is refused at once (401)", status == 401, status)
status, _ = call("POST", BOOKING + "/api/v1/accounts/sessions", {"email": OLD, "password": PASSWORD})
check("the old address no longer signs in (401)", status == 401, status)
status, session = call("POST", BOOKING + "/api/v1/accounts/sessions", {"email": NEW, "password": PASSWORD})
check("the new address signs in (201)", status == 201, status)
second = session["accessToken"] if status == 201 else None
status, me = call("GET", BOOKING + "/api/v1/accounts/me", token=second)
check("the account now has the new address", status == 200 and me["email"] == NEW, me)

# ---- 5. sign out everywhere -----------------------------------------------------------------------------------------------------------
_, other = call("POST", BOOKING + "/api/v1/accounts/sessions", {"email": NEW, "password": PASSWORD})
status, _ = call("DELETE", BOOKING + "/api/v1/accounts/me/sessions", token=second)
check("sign out everywhere (204)", status == 204, status)
for label, t in (("the session that asked", second), ("another device", other["accessToken"])):
    status, _ = call("GET", BOOKING + "/api/v1/accounts/me", token=t)
    check(f"{label} is refused (401)", status == 401, status)
status, _ = call("POST", BOOKING + "/api/v1/accounts/sessions", {"email": NEW, "password": PASSWORD})
check("signing in again just works (201)", status == 201, status)

# ---- 6. a password the hashing cannot store is a 400 with a message, not a 500 --------------------------------------------------------------
status, _ = call("POST", BOOKING + "/api/v1/accounts/password-links", {"email": f"live.long.{run}@example.com"})
mail = wait_mail(f"live.long.{run}@example.com", "Finish creating")
long_token = link_token(mail, "/account/set-password") if mail else None
status, problem = call("POST", BOOKING + "/api/v1/accounts/passwords", {"token": long_token, "password": "é" * 50})
check("50 accented letters (100 bytes) is a 400 with the rule, not a 500", status == 400 and "72" in json.dumps(problem), (status, problem))

print()
print(f"{sum(results)} of {len(results)} checks passed")
sys.exit(0 if all(results) else 1)
