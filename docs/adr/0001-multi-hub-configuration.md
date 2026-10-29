# ADR-0001: Hubs and inter-airport transfers are configuration

**Status:** Accepted
**Date:** 2026-10-29

## Context

The airline operates from two airports in the same city: Dubai International (DXB) and Al Maktoum International (DWC). Operations are moving from DXB to DWC over several years. During the transition:

- some routes depart from DXB and others from DWC;
- passengers may arrive at one airport and depart from the other, which needs a ground transfer;
- routes move between airports on specific dates;
- eventually DXB may stop being a connecting hub.

If hub codes are hard-coded (`if (airport.equals("DXB"))`), every step of the move needs a code change, a release and a regression cycle.

## Options considered

1. **Hard-code hubs** — simplest today; every change in the transition needs a deployment.
2. **Hubs in a database table** — changeable at runtime; needs an admin API, caching and audit, which is too much for Phase 1.
3. **Hubs in application configuration** — no code change to add, deactivate or retune a hub; changes are reviewed in Git and applied with a restart or config refresh.

## Decision

Option 3. Hubs, their minimum connection times (MCT), inter-airport transfer times and the maximum layover are defined under `skyhub.*` and bound to a validated `@ConfigurationProperties` record. A domain class, `HubNetwork`, answers every hub question; no other class mentions an airport code.

Connection rule: same airport → that hub's MCT; different airports → transfer time + departure hub's MCT.

## Consequences

- Positive: moving a route or deactivating DXB is a configuration change, testable with a unit test.
- Positive: the transfer logic is isolated and covered by `HubNetworkTest`.
- Negative: a bad configuration could break search, so validation runs at startup and the service fails fast.
- Negative: changes need a restart until a runtime config source (e.g. Kubernetes ConfigMap reload) is added.
- Follow-up: date-effective hub assignment (route X moves to DWC from date D) — a future ADR, possibly moving to option 2.
