# Notification Management Service (NMS)

Accepts notification requests from source systems, routes them to channels (EMAIL, SMS, PUSH),
delivers them asynchronously with bounded retries, and exposes status and audit history.

Stack: Java 21, Spring Boot 3.5, Maven, PostgreSQL 16, Flyway, Docker Compose.
Tests: JUnit 5, MockMvc, Testcontainers.

## Setup

Prerequisites: JDK 21, Maven 3.9+, Docker (with Compose).

```bash
# Start PostgreSQL 16 (database/user/password: nms)
docker compose up -d --wait

# Build and run the service (Flyway applies the schema and seed data on startup)
mvn package -DskipTests
java -jar target/notification-management-service-0.1.0-SNAPSHOT.jar

# Check it is up
curl -s localhost:8080/actuator/health
```

The database connection can be overridden with `NMS_DB_URL`, `NMS_DB_USER`, and `NMS_DB_PASSWORD`.
All service settings live under `nms.*` in `src/main/resources/application.yml`.

## Tests

```bash
mvn verify
```

Unit tests run with Surefire (`*Test`); integration tests run with Failsafe (`*IT`) against a
throwaway PostgreSQL 16 Testcontainer, so Docker must be running. The local Compose database is
not used by tests.

Every spec scenario has a test named after it; see [docs/scenario-coverage.md](docs/scenario-coverage.md).

## Stopping

```bash
docker compose down        # keep data
docker compose down -v     # also delete the database volume
```

## State model

Each notification has one **delivery** per (recipient, channel). Delivery states:

| State | Terminal | Meaning |
|---|---|---|
| `PENDING` | no | Waiting for its first attempt (at `scheduledAt`, or immediately) |
| `IN_FLIGHT` | no | Claimed by a worker under a time-bounded lease |
| `RETRY_SCHEDULED` | no | A retryable attempt failed; waiting for `nextAttemptAt` |
| `SENT` | yes | The provider accepted it |
| `FAILED` | yes | Non-retryable failure, or retries exhausted |
| `EXPIRED` | yes | The notification's `expiresAt` passed before it could be delivered |
| `SUPPRESSED` | yes | Duplicate of an earlier delivery of the same event (see [Event deduplication](#event-deduplication)); assigned only at creation, never sent |

Allowed transitions (anything else is rejected and leaves the stored state unchanged):

```
PENDING         -> IN_FLIGHT | EXPIRED
IN_FLIGHT       -> SENT | RETRY_SCHEDULED | FAILED | EXPIRED | IN_FLIGHT (reclaim after lease expiry)
RETRY_SCHEDULED -> IN_FLIGHT | EXPIRED
```

`SUPPRESSED` is set only when a delivery is created; no transition leads into or out of it.

The notification's overall status is **derived** from its deliveries (and cached on the
notification in the same transaction as every delivery change). `SUPPRESSED` deliveries are ignored,
except in the last rule:

| Status | Rule |
|---|---|
| `ACCEPTED` | Every delivery is still `PENDING` |
| `IN_PROGRESS` | At least one delivery has left `PENDING` and at least one is not terminal |
| `COMPLETED` | All non-suppressed deliveries `SENT` (at least one) |
| `PARTIALLY_DELIVERED` | All terminal, at least one `SENT` and at least one `FAILED` or `EXPIRED` |
| `FAILED` | All terminal, none `SENT`, at least one `FAILED` |
| `EXPIRED` | All terminal, none `SENT`, none `FAILED` |
| `SUPPRESSED` | Every delivery is `SUPPRESSED` |

## Security

Every request under `/api/**` must carry an API key in the `X-API-Key` header. Each key maps to
exactly one source system. Keys are stored only as SHA-256 hashes (`api_client.key_hash`) and are
never logged or written to audit history.

| Situation | Result |
|---|---|
| Missing, unknown, or inactive key | `401 Unauthorized`, nothing processed |
| Body `sourceSystem` differs from the key's source system | `403 Forbidden` (checked before field validation; audited as `NOTIFICATION_REJECTED` / `SOURCE_SYSTEM_MISMATCH`) |
| Reading another source system's notification or audit | `404 Not Found` (existence is not revealed) |

Open without a key: `/actuator/health`, `/swagger-ui.html`, `/v3/api-docs`. No other actuator
endpoint is exposed over HTTP.

**Demo keys (local use only).** The seed migration stores only their hashes:

| Source system | `X-API-Key` | Active |
|---|---|---|
| `billing` | `billing-demo-key-7f3a9c2e5b8d4f1a` | yes |
| `trading` | `trading-demo-key-2c8e6a1f9d4b7e3c` | yes |
| `legacy` | `legacy-demo-key-5a1d8f3c7e2b9a6d` | no (demonstrates the inactive-key `401`) |

**Production path.** API keys are a prototype mechanism. In production, source systems should
authenticate with OAuth2 client credentials; the plain servlet filter would be replaced by Spring
Security's resource-server support, mapping the token's client ID to the source system.

## Routing

Channels are decided per recipient at submission time, by these rules in order:

1. Start with the requested `channels` (reason `REQUESTED`), or the notification type's default
   channels if none were requested (`TYPE_DEFAULT`).
2. Add the severity's escalation channels (`SEVERITY_ESCALATION`). A channel already present keeps
   its original reason.
3. Remove channels the recipient opted out of (`RECIPIENT_OPT_OUT`).
4. Remove channels the recipient has no address for (`NO_ADDRESS`).
5. If nothing is left, add the fallback channel if the recipient has an address for it and has not
   opted out of it (`FALLBACK`).

One delivery is created per remaining (recipient, channel). A recipient left with nothing is
recorded as `NO_ELIGIBLE_CHANNEL`; an unknown recipient ID as `UNKNOWN_RECIPIENT`. If no recipient
gets any delivery, the request is rejected with `422` (`NO_ELIGIBLE_CHANNEL`). Duplicate channels or
recipients in one request are rejected with `400`.

Every decision is audited as one `ROUTING_DECIDED` event per recipient, listing the selected
channels, what each rule added, and what each rule removed (a channel added then removed appears
only under removed).

Severity drives routing (which channels); priority drives processing order (which deliveries are
claimed first). They are independent.

Configuration (`nms.routing` in `application.yml`):

| Setting | Default |
|---|---|
| `default-channels` | `TRANSACTIONAL: [EMAIL]`, `ALERT: [EMAIL, PUSH]`, `SECURITY: [EMAIL, SMS]`, `MARKETING: [EMAIL]` |
| `severity-escalation` | `CRITICAL: [SMS]` |
| `fallback-channel` | `EMAIL` |

## Submitting and idempotency

`POST /api/v1/notifications` with headers `X-API-Key` and `Idempotency-Key` (required, 1–255
characters) and a JSON body:

```json
{
  "sourceSystem": "billing",
  "eventId": "INV-42",
  "type": "TRANSACTIONAL",
  "severity": "MEDIUM",
  "priority": "NORMAL",
  "recipients": ["cust-1001"],
  "channels": ["EMAIL"],
  "subject": "Invoice ready",
  "body": "Your invoice INV-42 is ready.",
  "scheduledAt": "2026-10-01T10:00:00Z",
  "expiresAt": "2026-10-02T10:00:00Z"
}
```

`channels`, `scheduledAt`, and `expiresAt` are optional. A `scheduledAt` in the past means "send
now". Unknown fields, duplicate recipients, and duplicate channels are rejected. Checks run in this
order: `401` → `403` → `400` (every invalid field listed) → idempotency → `400` if `expiresAt` has
passed → `202` or `422`.

**Idempotency boundary.** `(sourceSystem, Idempotency-Key)` identifies one submission. The service
stores a SHA-256 hash of the canonical request body (keys sorted, timestamps normalized to UTC;
field order, whitespace, and time-zone notation don't matter, list order does).

| Retry with the same key | Result |
|---|---|
| Identical body | `200 OK` with the original ID and current status; nothing new is created; `DUPLICATE_SUBMISSION` is audited on the original. This holds even if `expiresAt` has since passed. |
| Different body | `409 Conflict`; nothing is created (the original ID is not revealed) |
| Same key from another source system | Independent notification |
| Concurrent identical requests | Exactly one `202`; the others get `200` |

**Retention.** Keys are kept for 7 days (`nms.idempotency.retention`). An hourly job
(`nms.idempotency.cleanup-interval`) then clears the key and hash from the notification; the same
key is treated as new afterwards. The notification itself is kept.

**Known limitation: no event-level deduplication.** Resubmitting the same business event with a
*new* `Idempotency-Key` creates and delivers a second notification. Clients must reuse the same
`Idempotency-Key` when retrying an event.

## Delivery processing

**PostgreSQL is the queue.** Deliveries are rows, enqueued in the same transaction that accepts the
notification (no broker, no dual write). Workers claim due rows with `FOR UPDATE SKIP LOCKED` in one
short transaction, so concurrent workers and instances never claim the same row:

- Due = `PENDING`/`RETRY_SCHEDULED` with `nextAttemptAt <= now`, or `IN_FLIGHT` whose lease expired.
- Order: notification priority (`HIGH` > `NORMAL` > `LOW`), then `nextAttemptAt`.
- A claim sets `IN_FLIGHT`, a lease (`locked_until`), and counts an attempt (`DELIVERY_ATTEMPTED`).
  A delivery whose notification passed `expiresAt` becomes `EXPIRED` instead, without an attempt.
- The provider is called **outside** any transaction, bounded by the provider timeout. The outcome is
  recorded in a second transaction that only applies if the row is still the claimed version; a
  worker whose lease expired and whose delivery was reclaimed has its late result discarded.
- Processing is at-least-once. The delivery ID is the provider idempotency key, so a delivery
  reclaimed after a worker crash is not sent twice.
- The recipient's full address is read at send time; if it is gone, the delivery fails with
  `INVALID_RECIPIENT` without a provider call.

Exit path: the `DeliveryQueue` port can be reimplemented on a broker (Kafka, SQS) when throughput
outgrows PostgreSQL (roughly a few thousand deliveries per second).

**Failure classes and retry.**

| Failure class | Retried | Result |
|---|---|---|
| `TRANSIENT` (also any unexpected provider exception) | yes | `RETRY_SCHEDULED` |
| `TIMEOUT` (also a call exceeding the provider timeout) | yes | `RETRY_SCHEDULED` |
| `RATE_LIMITED` | yes, not before the provider's retry-after | `RETRY_SCHEDULED` |
| `PERMANENT_REJECTION`, `INVALID_RECIPIENT` | no | `FAILED` (reason = failure class) |
| `AUTH_ERROR` | no | `FAILED`, plus an ERROR log and `nms.deliveries.failed{failureClass=AUTH_ERROR}` |

Backoff is exponential with full jitter: `delay = random(0, min(maxDelay, base * 2^(attempt-1)))`,
then at least the retry-after. After the last attempt the delivery fails with `RETRIES_EXHAUSTED`.
A retry that would land after `expiresAt` makes the delivery `EXPIRED` instead.

| Setting (`nms.retry` / `nms.worker`) | Default |
|---|---|
| `base-delay` / `max-delay` / `max-attempts` | 2s / 5m / 5 |
| `concurrency` / `batch-size` | 20 / 20 (per instance; each poll claims only as many as there are free slots) |
| `poll-interval` | 500ms |
| `lease-duration` | 60s (must exceed `provider-timeout`, checked at startup) |
| `provider-timeout` | 10s |
| `shutdown-timeout` | 30s (graceful: stop claiming, let in-flight deliveries finish) |

**Simulated providers and failure injection.** EMAIL, SMS, and PUSH are simulated. An address
containing a marker decides the outcome:

| Marker | Outcome |
|---|---|
| `+transient` | `TRANSIENT` every attempt (ends in `RETRIES_EXHAUSTED`) |
| `+timeout` | `TIMEOUT` every attempt |
| `+ratelimit` | `RATE_LIMITED` with retry-after 30s |
| `+reject` | `PERMANENT_REJECTION` |
| `+invalid` | `INVALID_RECIPIENT` |
| `+auth` | `AUTH_ERROR` |
| `+flaky` | `TRANSIENT` on attempt 1, then success |

Seeded recipients `cust-2001` … `cust-2007` have the email addresses `user+transient@example.com`,
`user+timeout@…`, `user+ratelimit@…`, `user+reject@…`, `user+invalid@…`, `user+auth@…`, and
`user+flaky@…`.

## Demo

`scripts/demo.sh` walks through the happy path, idempotent replay, conflict, a retry (`+flaky`),
permanent failures, rate limiting, and the `400`/`401`/`403`/`422`/`404` responses against a running
instance. It needs `curl` and `jq`.

```bash
docker compose up -d --wait
java -jar target/notification-management-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
scripts/demo.sh                      # BASE_URL defaults to http://localhost:8080
```

## Observability

- `GET /actuator/health`: overall status, including the database.
- OpenAPI: `/swagger-ui.html` (UI) and `/v3/api-docs` (JSON). Authorize with `X-API-Key`.
- Metrics (Micrometer): `nms.notifications.accepted`, `nms.notifications.rejected{reason}`,
  `nms.deliveries.sent{channel}`, `nms.deliveries.failed{channel,failureClass}`,
  `nms.deliveries.retried{channel,failureClass}`, `nms.deliveries.expired{channel}`. They are not
  exposed over HTTP by default; wire a registry (e.g. Prometheus) to export them.
- Logs are Logstash-format JSON by default; run with `--spring.profiles.active=local` for
  human-readable logs. Request logs carry `correlationId` (from `X-Correlation-Id` when it is 1–64
  characters of `A-Z a-z 0-9 . _ -`, otherwise a generated UUID; echoed on every response) and
  `notificationId`; worker logs carry `notificationId` and `deliveryId`. Message subject/body, full
  addresses, and API keys are never logged.

## Load test

`k6/load-test.js` runs two scenarios in parallel against one instance:

- **submit**: arrival rate ramps from 0 to 100 submissions/s over 30s, then holds 100/s for 60s
  (one EMAIL notification to `cust-1001` each, always succeeds);
- **sampler**: 5 virtual users that submit and poll the status until `COMPLETED`, to measure
  time-to-sent under that load.

```bash
docker compose up -d --wait
java -jar target/notification-management-service-0.1.0-SNAPSHOT.jar
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 grafana/k6 run - < k6/load-test.js
```

Results (2026-09-29; one local instance on a MacBook, PostgreSQL 16 in Docker Desktop, default
settings: concurrency 20, batch 20, poll interval 500 ms; single run):

| Metric | Result |
|---|---|
| Submissions accepted | 8,763 (100% `202`, 0 failed requests) |
| Sustained submission rate | ~115/s (100/s submit + sampler) |
| Sustained delivery rate | ~115 sent/s, no backlog (every delivery `SENT` by the end of the run) |
| Accept latency | p50 4 ms, p95 38 ms, p99 180 ms |
| Time-to-sent (sampler) | p50 506 ms, p95 579 ms, p99 633 ms, max 1.4 s |
| Time-to-sent (all deliveries, from the database) | p50 293 ms, p95 537 ms, p99 1.0 s |

An earlier run exposed a ceiling of ~37 deliveries/s: the poller slept a full poll interval
whenever all slots were busy. It now waits only for the next free slot while a backlog exists, so
throughput is bounded by `concurrency` and per-delivery time instead of `batch-size / poll-interval`.

## Limitations and trade-offs

- **PostgreSQL as the queue.** No broker to run and enqueueing is atomic with acceptance, at the
  cost of a throughput ceiling (roughly a few thousand deliveries/s) and polling latency (up to one
  poll interval when idle). Exit path: reimplement the `DeliveryQueue` port on Kafka or SQS.
- **At-least-once delivery.** A delivery reclaimed after a worker crash is sent again under the same
  provider idempotency key (the delivery ID); a provider that ignores idempotency keys could send
  twice. The lease (60s) is kept well above the provider timeout (10s).
- **No event-level deduplication.** The same event resubmitted with a new `Idempotency-Key` is
  delivered twice; clients must reuse the key when retrying.
- **Simulated providers.** No real latency or error shapes; failure-injection markers cover every
  failure class.
- **Audit growth.** `audit_event` is append-only and unpartitioned; partitioning and archival are
  future work. Rejection audits without a notification ID are not visible through the audit API.
- **Authentication.** Static per-source-system API keys (hashed); OAuth2 client credentials is the
  production path.
- **Spring Boot 3.5.** Its open-source support has ended; moving to Spring Boot 4.x is future work.
- **Load test.** Single run on a laptop; numbers are indicative, not a capacity guarantee.
