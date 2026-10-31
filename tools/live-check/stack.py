"""Starts and stops the stack the live checks need, so the whole check is one command (ADR-0053).

    python tools/live-check/stack.py check      # start everything, run every live check, always tear down
    python tools/live-check/stack.py up         # start it and leave it running
    python tools/live-check/stack.py status
    python tools/live-check/stack.py down

It uses throwaway keys and a throwaway booking database (`booking_live`), never the development database, and it only stops
containers that it started itself. It refuses to start if the ports are taken, so it can never check someone else's running services.
Needs Python 3 (standard library), Docker, and a JDK on the path or in JAVA_HOME. Does not need the website.
"""
import base64
import json
import os
import pathlib
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

REPO = pathlib.Path(__file__).resolve().parents[2]
HERE = pathlib.Path(__file__).resolve().parent
WORK = pathlib.Path(tempfile.gettempdir()) / "skyhub-live-stack"   # keys, logs and state: outside the repository, throwaway
DATABASE = "booking_live"
CONTAINERS = ["postgres", "kafka", "mailpit"]
# service -> (application port, management port)
SERVICES = {"flight-search": (8081, 9081), "booking": (8082, 9082), "notification": (None, 9083)}
MAILPIT_UI = 8025


# ---- decisions (tested in test_stack.py) -----------------------------------------------------------------------------------

def new_keys():
    """Four fresh keys. The link-token key is shared by booking and notification (ADR-0046); the rest are booking's own."""
    return {name: base64.b64encode(secrets.token_bytes(32)).decode() for name in ("pii", "manage", "boarding", "link")}


def service_env(service, keys, mismatched_link_key=False):
    """Only what this service needs: notification never receives booking's secrets."""
    if service == "booking":
        return {"SKYHUB_PII_KEY": keys["pii"], "SKYHUB_MANAGE_TOKEN_KEY": keys["manage"], "SKYHUB_BOARDING_PASS_KEY": keys["boarding"],
                "SKYHUB_LINK_TOKEN_KEY": keys["link"], "SKYHUB_SITE_URL": "http://localhost:3000",
                "DB_URL": f"jdbc:postgresql://localhost:5432/{DATABASE}",
                "SKYHUB_BOOTSTRAP_ADMIN_USERNAME": "live.admin", "SKYHUB_BOOTSTRAP_ADMIN_PASSWORD": "live-admin-passphrase"}
    if service == "notification":
        link = new_keys()["link"] if mismatched_link_key else keys["link"]
        return {"SKYHUB_LINK_TOKEN_KEY": link, "SKYHUB_SITE_URL": "http://localhost:3000"}
    if service == "flight-search":
        return {}
    raise ValueError("Unknown service: " + service)


def service_args(service):
    """Extra settings for `spring-boot:run`. Booking opens check-in 100 days before departure, so the seed flights (a few days out) can be
    checked in to; the closing time is untouched. This is for the live check only: a real window is 24 hours (ADR-0013)."""
    if service == "booking":
        return ["-Dspring-boot.run.arguments=--skyhub.booking.check-in.opens-before=PT2400H"]
    if service in ("notification", "flight-search"):
        return []
    raise ValueError("Unknown service: " + service)


def drop_database_sql():
    """The only thing this tool ever drops."""
    return f"DROP DATABASE IF EXISTS {DATABASE} WITH (FORCE);"


def containers_we_must_start(wanted, running):
    return [c for c in wanted if c not in running]


def containers_to_stop(started_by_us):
    """Only what this tool started: a postgres or kafka that was already running belongs to someone else."""
    return list(started_by_us)


def dump_state(state):
    return json.dumps(state, indent=2, sort_keys=True)


# ---- doing things ----------------------------------------------------------------------------------------------------------

def say(text):
    print(text, flush=True)


def run(args, **kw):
    return subprocess.run(args, cwd=REPO, capture_output=True, text=True, **kw)


def load_state():
    path = WORK / "state.json"
    return json.loads(path.read_text()) if path.exists() else {"pids": {}, "started_containers": [], "notification_key": "matching"}


def save_state(state):
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "state.json").write_text(dump_state(state))


def load_keys():
    path = WORK / "keys.json"
    if path.exists():
        return json.loads(path.read_text())
    WORK.mkdir(parents=True, exist_ok=True)
    keys = new_keys()
    path.write_text(json.dumps(keys))
    try:
        path.chmod(0o600)
    except OSError:
        pass
    return keys


def port_in_use(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def healthy(management_port):
    try:
        with urllib.request.urlopen(f"http://localhost:{management_port}/actuator/health", timeout=3) as response:
            return response.status == 200 and b'"UP"' in response.read()
    except (urllib.error.URLError, OSError):
        return False


def wait_for(what, condition, timeout):
    end = time.time() + timeout
    while time.time() < end:
        if condition():
            return
        time.sleep(2)
    raise SystemExit(f"Timed out waiting for {what}. Logs are in {WORK}")


def running_containers():
    out = run(["docker", "compose", "ps", "--services", "--status", "running"])
    return out.stdout.split()


def start_containers(state):
    need = containers_we_must_start(CONTAINERS, running_containers())
    if need:
        say("Starting containers: " + ", ".join(need))
        result = run(["docker", "compose", "up", "-d", *need])
        if result.returncode != 0:
            raise SystemExit("docker compose up failed:\n" + result.stderr)
        state["started_containers"] = sorted(set(state["started_containers"]) | set(need))
    wait_for("postgres", lambda: run(["docker", "compose", "exec", "-T", "postgres", "pg_isready", "-U", "skyhub"]).returncode == 0, 90)
    wait_for("kafka", lambda: run(["docker", "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh", "--bootstrap-server",
                                  "localhost:9092", "--list"]).returncode == 0, 120)
    wait_for("mailpit", lambda: port_in_use(MAILPIT_UI), 60)


def psql(sql):
    return run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "skyhub", "-d", "postgres", "-c", sql])


def start_service(name, state, mismatched=False):
    keys = load_keys()
    env = {**os.environ, **service_env(name, keys, mismatched_link_key=mismatched)}
    mvnw = str(REPO / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    log = open(WORK / f"{name}.log", "w")
    flags = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    process = subprocess.Popen([mvnw, "-B", "-f", f"services/{name}/pom.xml", "spring-boot:run", *service_args(name)], cwd=REPO, env=env,
                               stdout=log, stderr=subprocess.STDOUT, **flags)
    state["pids"][name] = process.pid
    save_state(state)


def stop_service(name, state):
    pid = state["pids"].pop(name, None)
    if pid is None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)
    else:
        try:
            os.killpg(os.getpgid(pid), 15)
        except ProcessLookupError:
            pass


def up():
    state = load_state()
    if state["pids"]:
        raise SystemExit("A live-check stack is already recorded as running. Run `stack.py down` first.")
    taken = [p for ports in SERVICES.values() for p in ports if p and port_in_use(p)]
    if taken:
        raise SystemExit(f"Ports already in use: {taken}. Stop what is using them: this tool will not check someone else's services.")
    WORK.mkdir(parents=True, exist_ok=True)
    start_containers(state)
    save_state(state)
    say(f"Creating the throwaway database {DATABASE}")
    psql(drop_database_sql())
    created = psql(f"CREATE DATABASE {DATABASE} OWNER skyhub;")
    if created.returncode != 0:
        raise SystemExit("Could not create the database:\n" + created.stderr)
    for name in SERVICES:
        say(f"Starting {name} (log: {WORK / (name + '.log')})")
        start_service(name, state)
    for name, (_, management) in SERVICES.items():
        wait_for(name, lambda m=management: healthy(m), 300)
        say(f"  {name} is up")


def restart_notification(mismatched):
    state = load_state()
    stop_service("notification", state)
    wait_for("the notification port to free", lambda: not port_in_use(SERVICES["notification"][1]), 60)
    state["notification_key"] = "wrong" if mismatched else "matching"
    start_service("notification", state, mismatched=mismatched)
    save_state(state)
    wait_for("notification", lambda: healthy(SERVICES["notification"][1]), 300)
    say("  notification restarted with a " + ("link key that does NOT match booking's" if mismatched else "matching link key"))


def down():
    state = load_state()
    for name in list(state["pids"]):
        say(f"Stopping {name}")
        stop_service(name, state)
    if running_containers():
        psql(drop_database_sql())
        say(f"Dropped {DATABASE}")
    stop = containers_to_stop(state["started_containers"])
    if stop:
        say("Stopping containers this tool started: " + ", ".join(stop))
        run(["docker", "compose", "stop", *stop])
    state.update({"pids": {}, "started_containers": [], "notification_key": "matching"})
    save_state(state)


def status():
    state = load_state()
    say("Recorded services: " + (", ".join(f"{n} (pid {p})" for n, p in state["pids"].items()) or "none"))
    for name, (_, management) in SERVICES.items():
        say(f"  {name}: {'up' if healthy(management) else 'not answering'}")
    say("Containers this tool started: " + (", ".join(state["started_containers"]) or "none"))


def script(name, *args):
    say(f"\n=== {name} {' '.join(args)}".rstrip())
    return subprocess.run([sys.executable, str(HERE / name), *args], cwd=REPO).returncode


def check():
    failed = []
    try:
        up()
        for name in ("account_journey.py", "booking_journey.py"):
            if script(name) != 0:
                failed.append(name)
        restart_notification(mismatched=True)
        if script("failure_path.py", "park") != 0:
            failed.append("failure_path.py park")
        restart_notification(mismatched=False)
        if script("failure_path.py", "recover") != 0:
            failed.append("failure_path.py recover")
    finally:
        down()
    say("\nEvery live check passed." if not failed else "\nFAILED: " + ", ".join(failed))
    return 1 if failed else 0


def main(argv):
    commands = {"up": up, "down": down, "status": status}
    if len(argv) != 2 or argv[1] not in {*commands, "check"}:
        print(__doc__)
        return 2
    if argv[1] == "check":
        return check()
    commands[argv[1]]()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
