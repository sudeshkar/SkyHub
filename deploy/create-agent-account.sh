#!/usr/bin/env bash
# Creates the disruption agent's OPS account in the booking service (ADR-0083, ADR-0085), signed in as the first administrator.
# Run once, after ./deploy/compose.sh up -d --build reports everything healthy:
#   ./deploy/create-agent-account.sh
# The booking service is not published, so the request is made from inside the agent's container, which can reach it.
# The passwords travel on standard input, never on a command line where another user of the server could see them.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a

read -r -d '' code <<'PY' || true
import json, sys, urllib.request, urllib.error

c = json.load(sys.stdin)
base = "http://booking:8082/api/v1/ops"

def post(path, body, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, json.dumps(body).encode(), headers, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        return error.code, {}

status, session = post("/sessions", {"username": c["admin_user"], "password": c["admin_password"]})
if status != 201:
    sys.exit(f"Signing in as the administrator failed ({status}). Is booking healthy, and is .env the one it started with?")
status, _ = post("/staff", {"username": c["agent_user"], "password": c["agent_password"], "role": "OPS"}, session["accessToken"])
if status == 201:
    print(f"Created the agent's account '{c['agent_user']}'.")
elif status == 409:
    print(f"The agent's account '{c['agent_user']}' already exists; nothing to do.")
else:
    sys.exit(f"Creating the agent's account failed ({status}).")
PY

printf '{"admin_user":"%s","admin_password":"%s","agent_user":"%s","agent_password":"%s"}' \
  "$SKYHUB_BOOTSTRAP_ADMIN_USERNAME" "$SKYHUB_BOOTSTRAP_ADMIN_PASSWORD" "$SKYHUB_AGENT_USERNAME" "$SKYHUB_AGENT_PASSWORD" \
  | ./deploy/compose.sh exec -T disruption-agent python -c "$code"
