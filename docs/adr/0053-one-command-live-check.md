# ADR-0053: One command for the whole live check

Status: Accepted

## Context

The live check (ADR-0049) proves the real chain, but running it took a page of setup: start three containers, make four keys (one shared by two services), create a throwaway database, start three services in the right environment, run two scripts, then run the failure path in two halves with a *restart* of the notification service in between. Done by hand it was easy to get wrong (a development database with staff accounts, mismatched keys by accident) and so it was run less often than it should be.

## Decision

`tools/live-check/stack.py` starts and stops the stack, and `check` runs everything:

- `check`: start, run the account and booking journeys, restart notification with a link key that does not match booking's, run the failure path's `park`, restart it with the right key, run `recover`, then **always** tear down (also when a check fails or the script is interrupted). Exits non-zero if any check failed.
- `up`, `down`, `status` for running the stack by hand.

Safety rules, each tested:

- **Throwaway everything:** fresh random keys in a temp folder outside the repository, and the booking database `booking_live`, which is the only thing the tool ever drops. The development database is never touched.
- **Only stops what it started:** a postgres or kafka that was already running is left running.
- **Refuses to start if any of the service or management ports are taken**, so it can never run the checks against somebody's development services.
- Notification is given only the link key; it never receives booking's other secrets.

The scripts themselves are unchanged and still run on their own against any stack.

## Consequences

- Positive: `python tools/live-check/stack.py check` is the repeatable end-to-end proof, one command, with no leftovers.
- Positive: the decisions (keys, what each service is given, what may be removed) are plain functions with unit tests (`test_stack.py`); no Docker needed to run them.
- Negative: it is still **not in CI**: it needs Docker, a JDK and about ten minutes, and starts real services on fixed ports.
- Negative: if the tool is killed hard (power cut, task kill), services and containers may be left running. `stack.py down` cleans up from the recorded state; a stale state file is the only thing it trusts.
- Negative: the process control is tested only by running it, on one machine (Windows). The POSIX branch (process groups) is written but was not run.
