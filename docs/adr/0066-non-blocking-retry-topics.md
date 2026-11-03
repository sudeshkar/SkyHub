# ADR-0066: Non-blocking retry topics for notifications

**Status:** Accepted
**Date:** 2026-11-03

## Context

When an email could not be sent, the notification service retried the message in place: four attempts two seconds apart (ADR-0019), then it copied the message to `<topic>.DLT` (ADR-0032). That has two costs, both showing up in the first outage longer than a few seconds:

- **It holds up healthy mail.** While a message is being retried the consumer does nothing else on that partition. A new test (`NonBlockingRetryIntegrationTest`) failed against the old design for exactly this reason: a healthy event queued behind 20 failing ones did not arrive within 2 seconds.
- **It parks everything in a short outage.** Retries covered about six seconds. A mail server that was down for five minutes parked every event that arrived in that time, and a person had to replay them by hand (ADR-0032).

At 100 times the traffic the first cost becomes a queue that takes hours to clear, because each failing message costs six seconds of everyone's time.

## Options considered

1. **Keep blocking retries and lengthen them.** Rejected: the longer the wait, the longer healthy mail is held up.
2. **Hand-built retry topics** (our own recoverer, headers and a delay-aware consumer). Rejected: it is the same mechanism as the library's, with more code of ours to get wrong (pausing, delay arithmetic, partition handling).
3. **Spring Kafka `@RetryableTopic`.** Chosen. The library publishes a failed message to a delayed retry topic, and a consumer for each topic waits until the message is due without holding up the others. The configuration is declarative (attempts, delays, suffixes, exclusions), so it is changed by editing properties, not code.

## Decision

- **A failed message goes to a retry topic and the main consumer carries on.** Defaults: 5 attempts in all, waiting about 10 seconds, 1 minute, 6 minutes and then 15 minutes (the cap), roughly 22 minutes before a message is parked. A little random jitter (1 second) keeps a burst of failures from retrying in step. All of it is set under `skyhub.notifications.retry.*` (`attempts`, `delay-ms`, `multiplier`, `max-delay-ms`, `jitter-ms`, `partitions`).
- **Parked messages stay where they were: `<topic>.DLT`.** `dltTopicSuffix = ".DLT"`, and `autoStartDltHandler = "false"` so nothing in this service consumes the parked messages: the list, replay and skip tooling (ADR-0032, ADR-0037) reads them exactly as before.
- **A message that can never succeed is parked at once, with no retries** (`InvalidEventException` is excluded), as before.
- **Retry topics have as many partitions as the events topic** (`retry.partitions`, default 3, the broker default in `docker-compose.yml`), because a retried message keeps its partition number: fewer partitions would send it to one that does not exist. The integration test runs with 3 partitions for that reason.
- **The dead-letter counter moves to a listener of its own** (`DeadLetterCounter`), because no code of ours now sits at the moment a message is parked. It reads the DLT in its own consumer group and starts at the end of the topic, so it counts what is parked from now on and does not re-raise the alert for messages already waiting when the service starts. It takes nothing from the topic.
- **A new early-warning alert, `NotificationSendsFailing`:** three failed send attempts in 15 minutes, which one message that keeps failing causes in about 7 minutes and a one-off blip does not. Without it the first sign of an outage would be the dead-letter alert about 22 minutes late (the old design alerted in seconds). Runbook entries for both.
- **A defect found on the way, and fixed:** Spring Kafka 4 writes the headers `kafka_original-topic` and `kafka_exception-message` when it parks a message, not `kafka_dlt-original-topic` and `kafka_dlt-exception-message`. `ParkedMessage` read only the old names, and its tests planted old-style headers by hand, so for real parked messages the replay tool had been showing "unknown" and "no reason recorded". It now reads either name.
- The old blocking `DefaultErrorHandler` (`KafkaErrorHandling`) is removed.

## Consequences

- Positive: healthy mail is never held up behind failing mail, and an outage of up to about 22 minutes is ridden out without a person.
- Positive: work on the main consumer for a failing message is one fast failed attempt and one publish, so a large backlog of failing sends costs the healthy ones almost nothing. This was measured with 20 failing events, not 10,000; the claim at larger sizes rests on the mechanism, not a test.
- **Negative: ordering across events is no longer kept.** A message that fails is delivered later than the ones after it. A traveller could receive "your booking was cancelled" before a delayed "your booking is confirmed". Each email is complete and independent, so this is not wrong, only unordered; it would matter if an email assumed the previous one had been read.
- **Negative: duplicates remain possible and are handled the old way.** Kafka delivers at least once, so a message can be handled twice. Each event id is recorded once, so a repeat is not sent again; this is also what makes retrying safe.
- **Negative: time to park grows from about 6 seconds to about 22 minutes**, so anything watching only the dead-letter count sees an outage later. The new alert covers that; nothing was added for the number of messages waiting on the retry topics.
- **Negative: more topics** (`<topic>.retry-0` to `-3`, created by the service at start-up through the Kafka admin client). Where the service may not create topics, they must be created ahead with the same partition count.
- Negative: **the dead-letter counter is a count, not a record.** It starts at the end of the topic the first time its group runs, so a message parked just before that first start is not counted, and an alert evaluated while the service restarts can miss an increment. The list of parked messages (`/actuator/deadletters`) is the source of truth, not the counter.
- Negative: `kafka_original-topic` on a parked message names the topic it first came from, which is the events topic.
- Not proven: a restart or a rebalance **in the middle of the retries** (embedded Kafka can show a single process only), real-broker behaviour with several instances, and the 10,000-message case.

## Verification

Integration tests on a real in-process Kafka broker (3 partitions) and real PostgreSQL: a healthy event is recorded within 2 seconds while 20 failing ones wait out a 3-second retry delay, and the failed ones are all sent once the mail server is back; a message that fails twice then works makes exactly one email in three attempts; a message that always fails ends up on `<topic>.DLT` after its last attempt, is counted, and is listed by the replay tool; a message that can never succeed reaches the DLT within 2 seconds without any send being attempted. The existing replay, skip and duplicate-delivery tests pass unchanged. `ParkedMessage` has new tests for both header spellings. The full notification build ran with 151 tests and no failures; `promtool check rules` passes. The live check (`stack.py check`) was **not** run, and nothing was run against a real mail server.
