# SkyHub — TDD Plan (Phase 1)

## 1. The loop

1. **Red** — write one failing test.
2. **Green** — write the minimum code to pass it.
3. **Refactor** — clean up with the tests green.
4. **Commit** — e.g. `feat(search): reject connection shorter than MCT`.

Never write production code without a failing test that needs it.

## 2. Order: inside-out

Pure domain → application services → persistence → web → end-to-end. Domain tests run in milliseconds and need no Spring, so you get fast feedback where most of the logic lives.

| Layer | Tool | Share |
|---|---|---|
| Domain unit | JUnit + AssertJ | ~60% |
| Application service | JUnit + Mockito | ~20% |
| Persistence | `@DataJpaTest` (Testcontainers later) | ~10% |
| Web | `@WebMvcTest` + MockMvc | ~5% |
| End-to-end | `@SpringBootTest` | ~5% |

## 3. Conventions

- Test class per production class: `HubNetworkTest`.
- Method names: `should_<expected>_when_<condition>`.
- Body in **Given / When / Then** blocks.
- One behaviour per test. No logic (loops, ifs) in tests.
- Test data builders (e.g. `aSchedule().from("CMB").to("DXB").departing("02:00").build()`) once the same setup appears three times.
- Fixed `Clock` and fixed `LocatorGenerator` in tests — never `Instant.now()`.

## 4. flight-search test list

Tick each one as it goes green.

### HubNetwork
- [ ] 1. requires the hub MCT when arriving and departing at the same airport
- [ ] 2. requires transfer + departure-hub MCT across airports (DXB → DWC = 180m)
- [ ] 3. transfer time is the same in both directions
- [ ] 4. onward airports include the partner airport when it is active
- [ ] 5. onward airports exclude an inactive partner hub
- [ ] 6. an unknown airport is not a hub
- [ ] 7. invalid configuration (bad code, duplicate hub, transfer to unknown hub) fails at startup

### FlightSchedule / Itinerary
- [ ] 8. `operatesOn` respects operating days
- [ ] 9. `legOn` computes arrival across midnight
- [ ] 10. itinerary total duration = first departure → last arrival
- [ ] 11. itinerary total fare = sum of legs

### ItinerarySearchService (mocked repository)
- [ ] 12. returns a direct flight
- [ ] 13. excludes flights not operating on the date
- [ ] 14. builds a same-hub connection when layover ≥ MCT
- [ ] 15. rejects a connection shorter than MCT
- [ ] 16. builds a cross-airport connection flagged as requiring transfer
- [ ] 17. rejects a cross-airport connection shorter than transfer + MCT
- [ ] 18. rejects a layover longer than the maximum
- [ ] 19. finds an overnight connection departing the next day
- [ ] 20. never connects through an inactive hub
- [ ] 21. rejects origin equal to destination
- [ ] 22. sorts by departure, then total duration

### Persistence / web / end-to-end
- [ ] 23. `@DataJpaTest`: Flyway schema matches entities and seed data loads
- [ ] 24. `@WebMvcTest`: 200 with the expected JSON shape
- [ ] 25. `@WebMvcTest`: 400 ProblemDetail for `origin=cm`
- [ ] 26. `@WebMvcTest`: 400 ProblemDetail when `date` is missing
- [ ] 27. `@SpringBootTest`: CMB → JFK end to end, with transfer
- [ ] 28. second identical search does not hit the repository (cache)

## 5. booking test list

### Domain
- [ ] 1. locator is 6 characters from the allowed alphabet
- [ ] 2. `book` on an available seat → BOOKED with locator
- [ ] 3. `book` on a booked seat → `SeatUnavailableException`
- [ ] 4. seat layout produces 12 business and 156 economy seats

### BookingService
- [ ] 5. creates a PNR with the generated locator and fixed-clock timestamp
- [ ] 6. rejects duplicate seats within one request
- [ ] 7. rejects 0 or more than 9 passengers
- [ ] 8. rejects an unknown seat number
- [ ] 9. same idempotency key returns the existing PNR and books nothing
- [ ] 10. initialising inventory twice does not create duplicates

### Concurrency ⭐
- [ ] 11. 10 threads book the same seat at once → exactly 1 succeeds, 9 get a conflict

Sketch:
```java
// given: inventory exists, 10 threads, a CountDownLatch(1) as the start gun
// when:  all threads wait on the latch, latch.countDown(), each calls createPnr(seat 12A)
// then:  successes == 1, failures == 9, seat 12A is BOOKED by the winner's locator
```

### Web
- [ ] 12. `POST /pnrs` → 201 with `Location` header
- [ ] 13. seat taken → 409 ProblemDetail
- [ ] 14. unknown locator → 404 ProblemDetail
- [ ] 15. invalid email → 400 ProblemDetail
- [ ] 16. replay with the same `Idempotency-Key` → 200 with the same locator

## 6. Architecture tests (ArchUnit)

- [ ] `domain` classes do not depend on `org.springframework..`
- [ ] `web` does not access `persistence` directly
- [ ] controllers live in `web` and implement a generated `*Api` interface

## 7. Definition of done (every feature)

- [ ] Tests written first; all green
- [ ] OpenAPI spec updated **before** the code
- [ ] Errors return ProblemDetail
- [ ] Domain + application coverage ≥ 90% (JaCoCo)
- [ ] No new SonarQube issues
- [ ] ADR written if a design decision was made
- [ ] README updated if behaviour or setup changed

## 8. Schedule

| Week | Work |
|---|---|
| 1 | Review these docs, write the remaining ADRs, set up repo + Maven modules |
| 2 | OpenAPI specs; flight-search tests 1–11 |
| 3 | flight-search tests 12–28 |
| 4 | booking tests 1–10 |
| 5 | booking tests 11–16; ArchUnit; Docker Compose; CI |
| 6 | README, diagrams, demo video, code review |
