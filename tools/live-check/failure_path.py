"""The failure path on the REAL chain (ADR-0046, ADR-0032): the notification service has the wrong link-token key, so a link must be
refused, retried, parked and NOT sent; then, with the right key, the runbook's 'fix the key, then replay' must recover it.
Run in two phases: `park` (notification has the wrong key) and `recover` (it has been restarted with the right one).
See README.md in this folder for how to run it."""
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request

BOOKING = os.environ.get("BOOKING_URL", "http://localhost:8082")
MAILPIT = os.environ.get("MAILPIT_URL", "http://localhost:8025")
NOTIFICATION_MGMT = os.environ.get("NOTIFICATION_MGMT_URL", "http://localhost:9083")
STATE = os.path.join(tempfile.gettempdir(), "skyhub-live-failure.json")   # remembers the parked request between the two phases
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))


def call(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def mail_to(address):
    _, listing = call("GET", f"{MAILPIT}/api/v1/search?query=" + urllib.request.quote(f"to:{address}"))
    return (listing or {}).get("messages", [])


if sys.argv[1] == "park":
    address = f"live.parked.{int(time.time())}@example.com"
    status, _ = call("POST", BOOKING + "/api/v1/accounts/password-links", {"email": address})
    check("the booking service accepts the sign-up request (202), unaware of the notification service's key", status == 202, status)
    print("  waiting for the retries to run out and the message to be parked ...")
    parked = None
    end = time.time() + 90
    while time.time() < end:
        _, listing = call("GET", NOTIFICATION_MGMT + "/actuator/deadletters?limit=200")
        mine = [m for m in (listing or {}).get("messages", []) if m["eventType"] == "account.password-link" and "could not be opened" in m["error"]]
        if mine:
            parked = mine[-1]
            break
        time.sleep(3)
    check("the link event was refused and PARKED on the dead-letter topic", parked is not None)
    check("no email was sent for it", len(mail_to(address)) == 0, mail_to(address))
    if parked:
        text = json.dumps(parked)
        check("the parked listing says why without the address or any token", address not in text and "v1:" not in text, text)
        check("the reason names the key setting only through the message, with nothing secret", "link token" in parked["error"].lower())
    json.dump({"address": address}, open(STATE, "w"))
else:
    address = json.load(open(STATE))["address"]
    check("before the replay there is still no email for the parked request", len(mail_to(address)) == 0)
    status, replay = call("POST", NOTIFICATION_MGMT + "/actuator/deadletters", {"limit": 500})
    check("the replay tool puts parked messages back (200)", status == 200 and replay.get("replayed", 0) >= 1, (status, replay))
    end = time.time() + 60
    mail = None
    while time.time() < end and not mail:
        found = mail_to(address)
        if found:
            _, mail = call("GET", f"{MAILPIT}/api/v1/message/{found[0]['ID']}")
        time.sleep(1)
    check("with the right key the replayed link is now sent", mail is not None)
    if mail:
        token = re.search(r"set-password\?token=([A-Za-z0-9_%.\-]+)", mail["Text"])
        check("it carries a real token", bool(token))
        status, _ = call("POST", BOOKING + "/api/v1/accounts/passwords",
                         {"token": urllib.request.unquote(token.group(1)), "password": "a long enough passphrase"})
        check("and the traveller can use it to choose a password (204)", status == 204, status)
    status, replay = call("POST", NOTIFICATION_MGMT + "/actuator/deadletters", {"limit": 500})
    check("a second replay takes nothing more", status == 200 and replay.get("replayed") == 0, (status, replay))

print()
print(f"{sum(results)} of {len(results)} checks passed")
sys.exit(0 if all(results) else 1)
