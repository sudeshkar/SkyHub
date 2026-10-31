"""What every live check shares: calling the real services, reading the real Mailpit inbox and Kafka topic, and counting checks.

Nothing here is mocked. URLs come from the environment so a stack that is not on the default ports can be checked.
"""
import json
import os
import pathlib
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SEARCH = os.environ.get("FLIGHT_SEARCH_URL", "http://localhost:8081")
BOOKING = os.environ.get("BOOKING_URL", "http://localhost:8082")
MAILPIT = os.environ.get("MAILPIT_URL", "http://localhost:8025")
NOTIFICATION_MGMT = os.environ.get("NOTIFICATION_MGMT_URL", "http://localhost:9083")
REPO = pathlib.Path(__file__).resolve().parents[2]   # where docker-compose.yml is, for reading the Kafka topic

_results = []


def check(name, ok, detail=""):
    """Records and prints one check. Returns whether it passed, so a later step can depend on it."""
    _results.append(bool(ok))
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))
    return bool(ok)


def info(text):
    print("  " + text)


def finish():
    print()
    print(f"{sum(_results)} of {len(_results)} checks passed")
    sys.exit(0 if all(_results) else 1)


def call(method, url, body=None, token=None, headers=None):
    """One HTTP call. Returns (status, parsed JSON or text or None); an error status is an answer, not an exception."""
    data = None if body is None else json.dumps(body).encode()
    all_headers = {"Content-Type": "application/json", **(headers or {})}
    if token:
        all_headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=data, method=method, headers=all_headers)
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


def wait_until(condition, timeout=45, interval=1):
    """The first truthy result of the condition within the time, or None."""
    end = time.time() + timeout
    while time.time() < end:
        found = condition()
        if found:
            return found
        time.sleep(interval)
    return None


def mails_to(address):
    """Every message Mailpit holds for this address, newest first, as a short listing."""
    _, listing = call("GET", f"{MAILPIT}/api/v1/search?query=" + urllib.parse.quote(f"to:{address}"))
    return (listing or {}).get("messages", [])


def wait_mail(to, subject_part, timeout=45):
    """The first message to this address whose subject contains the text, read in full from Mailpit, or None."""
    def find():
        for m in mails_to(to):
            if subject_part.lower() in m["Subject"].lower():
                return call("GET", f"{MAILPIT}/api/v1/message/{m['ID']}")[1]
        return None
    return wait_until(find, timeout)


def count_mails(to, subject_part):
    return sum(1 for m in mails_to(to) if subject_part.lower() in m["Subject"].lower())


def link_token(mail, path):
    """The token in the link a mail carries to this page of the website, decoded."""
    found = re.search(re.escape(path) + r"\?token=([A-Za-z0-9_%.\-]+)", mail["Text"])
    return urllib.parse.unquote(found.group(1)) if found else None


def kafka_events(topic="skyhub.booking.events"):
    """Everything on a real topic, as a person with access to the broker would read it."""
    out = subprocess.run(
        ["docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "localhost:9092",
         "--topic", topic, "--from-beginning", "--timeout-ms", "6000"],
        capture_output=True, text=True, cwd=REPO)
    return out.stdout
