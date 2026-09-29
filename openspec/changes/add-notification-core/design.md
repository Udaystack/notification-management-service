# Design

## Context

Greenfield: the repository contains only OpenSpec scaffolding (see proposal.md - Why). This is a prototype built in 2–3 days that must still read as production-oriented, with minimal infrastructure.

Stack: Java 21, Spring Boot 3, Maven, PostgreSQL 16, Flyway, Docker Compose. Tests use JUnit 5, MockMvc, and Testcontainers. Requirements live in `specs/`; this document covers only how they are met.

## Goals / Non-Goals

**Goals:**
- Correct idempotency under concurrency without application-level locks.
- Safe multi-worker, multi-instance delivery processing using only PostgreSQL.
- Domain logic (routing, retry policy, state machine, status derivation, failure classification, masking) in plain Java with no Spring dependency, so it can be unit tested in isolation.
- A clear seam for adding real providers later (provider port).

**Non-Goals:**
- Throughput beyond what Postgres-as-queue sustains (a few thousand jobs/sec).
- Audit table partitioning/archival, OAuth2, multi-region (documented only).

## Architecture

Modular monolith, package by feature, ports and adapters.

```
Client ──► API (controllers, API-key filter, ProblemDetail errors)
             │
             ▼
          Intake pipeline: validate ──► idempotency ──► route ──► create deliveries ──► audit
             │                                  (one transaction)
             ▼
          PostgreSQL  (notification, delivery, audit_event, recipient*, api_client)
             ▲
          Worker (scheduled poll, SKIP LOCKED claim)
             │
             ▼
          ChannelProvider port ──► Email / Sms / Push (simulated)
             │  DeliveryResult
             ▼
          RetryPolicy ──► DeliveryStateMachine ──► persist outcome + status + audit (one transaction)
```

Packages: `api`, `intake`, `routing`, `delivery`, `retry`, `channel`, `recipient`, `audit`, `security`, `common`.

## Decisions

### D1. PostgreSQL is the queue (no broker)
Deliveries are rows. Workers claim in one short transaction:
```sql
SELECT d.id FROM delivery d JOIN notification n ON n.id = d.notification_id
WHERE (d.status IN ('PENDING','RETRY_SCHEDULED') AND d.next_attempt_at <= now())
   OR (d.status = 'IN_FLIGHT' AND d.locked_until < now())
ORDER BY n.priority_rank DESC, d.next_attempt_at
LIMIT :batch
FOR UPDATE OF d SKIP LOCKED;
-- then: status = 'IN_FLIGHT', locked_until = now() + :lease, version = version + 1
```
`priority_rank` (HIGH=2, NORMAL=1, LOW=0) is stored on `notification` so ordering is index-friendly.
- *Why:* enqueue is atomic with acceptance (no dual-write/outbox), works across instances, one less system to run.
- *Alternatives:* Kafka/RabbitMQ (extra infra, needs an outbox for atomicity); in-memory executor (loses work on crash, single instance only).
- *Exit path:* the `DeliveryQueue` port can be reimplemented on Kafka/SQS.

### D2. Provider calls happen outside DB transactions
Claim (tx 1) → provider call (no tx) → record outcome (tx 2, `UPDATE ... WHERE id = :id AND status = 'IN_FLIGHT' AND version = :v`). Avoids holding connections during slow I/O. If tx 2 updates zero rows (lease expired and another worker reclaimed), the result is discarded and logged; the provider idempotency key (delivery ID) prevents a double send.
- *Alternative:* call the provider inside the claim transaction — simpler, but pins a connection per in-flight call and makes the pool the throughput limit.

### D3. Idempotency via unique constraint, not check-then-insert
`UNIQUE (source_system, idempotency_key)` on `notification`. Insert with `INSERT ... ON CONFLICT (source_system, idempotency_key) DO NOTHING RETURNING id`. No row returned → load the existing row and compare `request_hash`: equal → `200` replay (+ `DUPLICATE_SUBMISSION` audit), different → `409`.
Using `ON CONFLICT` rather than catching a unique-violation exception keeps the Postgres transaction usable for the follow-up read and audit write.
- *Alternatives:* check-then-insert (racy); separate idempotency table (extra join, no benefit at this scale).
- Canonical hash: SHA-256 over the request DTO serialized as JSON with sorted keys and normalized timestamps (UTC ISO-8601), so field order, whitespace, and time-zone notation don't change the hash. Only the request body is hashed (headers excluded; `sourceSystem` + key already scope the record). List order (recipients, requested channels) is preserved, so a reordered list counts as a different payload.
- Retention: a scheduled job sets `idempotency_key` and `request_hash` to `NULL` on notifications older than the retention window (default 7 days). Postgres unique constraints ignore NULLs, so the key becomes reusable while the notification is kept.

### D4. Intake pipeline as ordered steps
Intake runs as an ordered list of `IntakeStep` beans inside one transaction: validate → idempotency → route → create deliveries → audit. Each step is a separate class, so it can be unit tested in isolation and new steps can be inserted without rewriting intake.
- *Alternative:* a single intake service method — less indirection, but harder to test steps in isolation and to extend.
- Validation of request shape (Bean Validation, enum parsing, timestamps, recipient limit, missing header) happens before the transaction. A rejection writes only a `NOTIFICATION_REJECTED` audit event, in its own transaction, with `notification_id = NULL`, `source_system` set, and a reason code.
- `422 NO_ELIGIBLE_CHANNEL`: routing produces zero deliveries → the intake transaction rolls back, then the rejection audit is written separately (same pattern as 400).
- 401 (unauthenticated) writes no audit — there is no trusted source system to attribute it to.

### D4a. Submission request rules
- Body: `sourceSystem` (≤ 64), `eventId` (1–128), `type`, `severity`, `priority`, `recipients` (list of recipient ID strings, each ≤ 64, no duplicates, 1..max), `channels` (optional, no duplicates), `subject` (≤ 200, not blank), `body` (≤ 10,000, not blank), `scheduledAt`, `expiresAt` (ISO-8601, optional). Unknown JSON fields are rejected with `400`. Header `Idempotency-Key`: required, 1–255 characters.
- Order of checks: authentication (`401`) → `sourceSystem` mismatch (`403`) → shape validation (`400`, every invalid field listed) → idempotency lookup (known key: identical body → `200` replay, different body → `409`) → time-based validation (`expiresAt` in the past → `400`) → intake transaction (`202`, or `422`). A retry of an accepted request therefore always replays, even after `expiresAt`.
- A `scheduledAt` in the past is accepted and delivered immediately.
- Rejection reason codes: `MALFORMED_REQUEST` (unreadable JSON), `VALIDATION_FAILED` (details list field names only), `SOURCE_SYSTEM_MISMATCH`, `IDEMPOTENCY_KEY_CONFLICT` (a separate row without notification ID; the original's history is untouched), `NO_ELIGIBLE_CHANNEL`.
- `202` and `200` bodies: `{"id", "status", "statusUrl"}` plus a `Location` header; both use the relative path `/api/v1/notifications/{id}`. The `409` Problem Details does not reveal the original notification ID.

### D5. Status is derived, not stored independently
Overall status is computed by `NotificationStatusDeriver` from delivery states and cached on `notification.status`, recomputed in the same transaction as every delivery change to avoid drift. The delivery state machine (transition table in specs/notification-status) is enforced in domain code; persistence updates are also guarded by `WHERE status = :expected`.
- "An attempt has started" means any delivery has left `PENDING` (claimed, or terminal without a provider call, e.g. expired or missing address). So a notification is `ACCEPTED` only while every delivery is `PENDING`; otherwise it is `IN_PROGRESS` until all deliveries are terminal.

### D6. Failure classification lives in adapters
Each `ChannelProvider` returns `DeliveryResult` (`Success` | `Failure(FailureClass, retryAfter?)`). The worker never inspects provider-specific errors; any exception escaping an adapter is caught by the worker and classified `TRANSIENT`. This is the seam for real providers.

### D6a. Attempts, outcomes, and simulated providers
- An attempt counts at claim: the claim transaction sets `IN_FLIGHT`, the lease, `attemptCount + 1`, `lastAttemptAt`, and writes `DELIVERY_ATTEMPTED`. A worker crash therefore still consumes an attempt, so a delivery that keeps crashing workers stops after max attempts.
- A delivery whose notification has passed `expiresAt` is set `EXPIRED` in the claim transaction (`DELIVERY_EXPIRED`); no attempt is counted and no provider is called.
- A missing address at send time: `IN_FLIGHT` → `FAILED` with `INVALID_RECIPIENT`; it counts as an attempt; no provider call.
- `DELIVERY_FAILED` reason codes: the failure class for non-retryable failures; `RETRIES_EXHAUSTED` (last failure class in details) when attempts run out. `RETRY_SCHEDULED` carries the failure class as reason.
- `completedAt` is set when a delivery reaches any terminal state (`SENT`, `FAILED`, `EXPIRED`).
- Simulated providers: a marker matches when the address contains it, on any channel. `+transient` always `TRANSIENT`; `+timeout` returns `TIMEOUT` immediately; `+ratelimit` returns `RATE_LIMITED` with retry-after 30 s; `+reject`/`+invalid`/`+auth` always the matching class; `+flaky` fails `TRANSIENT` on attempt 1 and succeeds afterwards. Each provider remembers delivery IDs it has sent; a repeat call with the same ID reports success without sending again.

### D7. Retry policy
Retryable: `TRANSIENT`, `TIMEOUT`, `RATE_LIMITED`.
`delay = random(0, min(maxDelay, base * 2^(attempt-1)))`, then `delay = max(delay, retryAfter)`. Defaults base 2s, max 5m, max 5 attempts (attempt count includes the first). If `now + delay > expiresAt` → `EXPIRED`. If attempts exhausted → `FAILED` with reason `RETRIES_EXHAUSTED`. The random source is injected so tests are deterministic.
- *Alternative:* equal/decorrelated jitter — full jitter chosen for best load spreading and simplest reasoning.

### D8. Priority vs severity
Severity drives *routing* (which channels). Priority drives *processing order* (which deliveries are claimed first). Kept independent and documented in the README.

### D8a. Routing decision recording
- Reason codes: starting set `REQUESTED` (caller asked) or `TYPE_DEFAULT` (type defaults); `SEVERITY_ESCALATION`, `FALLBACK` for additions; `RECIPIENT_OPT_OUT`, `NO_ADDRESS` for removals; `NO_ELIGIBLE_CHANNEL` / `UNKNOWN_RECIPIENT` when a recipient gets nothing.
- One `ROUTING_DECIDED` event per recipient (recipient ID, selected channels, added and removed channels with reasons) and one `DELIVERY_QUEUED` per delivery.
- A channel added then removed (e.g. escalated `SMS` the recipient opted out of) is listed only under removed, with the removing rule.
- Escalation of a channel already present keeps its original reason.
- Duplicate channels or duplicate recipients in one request are rejected with `400`.

### D9. Security
API key per source system in `X-API-Key`, stored as SHA-256 hash in `api_client` (keys are high-entropy random tokens, so an unsalted fast hash is adequate). A servlet filter authenticates and sets the source system in the security context; a missing key, an unknown key, or a key whose `api_client.active = false` all get `401`. V2 seeds only the hashes of fixed demo keys for `billing` and `trading`; the plain-text demo keys are listed in the README for local use only. Reads filter by `source_system`, so cross-tenant reads return 404. Only `/api/**` requires a key; `/actuator/health`, `/swagger-ui.html`, `/swagger-ui/**`, and `/v3/api-docs/**` are open, and no other actuator endpoint is exposed over HTTP. Authentication is a plain `OncePerRequestFilter` (no Spring Security) that stores the source system in a request attribute; moving to OAuth2 means adopting Spring Security's resource-server support. On submit, the `sourceSystem` check runs before field validation: a mismatch returns `403` and writes `NOTIFICATION_REJECTED` with the authenticated `source_system`, reason `SOURCE_SYSTEM_MISMATCH`, and only the declared source system in details. Addresses are masked by a single `AddressMasker` used for responses, logs, and audit. Masking rules: EMAIL → first character of the local part + `***@domain`; SMS → `***-***-` + last 4 digits; PUSH → `***` + last 4 characters. If there is too little to show (email without `@`, fewer than 4 phone digits, push token of 4 characters or fewer), the result is `***`. Production path: OAuth2 client credentials.

### D10. Concurrency model
A `@Scheduled` poller claims a batch and dispatches each delivery onto a virtual-thread executor bounded by a semaphore (configured concurrency). Virtual threads suit blocking provider I/O without pool tuning.
- **Claim only what can start:** each poll claims `min(batchSize, free semaphore permits)`, so no claimed row waits in memory while its lease runs down.
- **Drain a backlog without waiting:** if a poll fills all the slots it asked for, the poller claims again immediately; when all slots are busy it waits only for the next free slot (at most one poll interval). The poll interval applies only when no work is due. Throughput per instance is therefore bounded by `concurrency` and per-delivery time, not by `batch-size / poll-interval`.
- **Graceful shutdown:** on shutdown the poller stops claiming and waits up to a configurable timeout for in-flight deliveries; anything unfinished is reclaimed after its lease expires.
- **Provider call timeout:** each provider call has a configurable timeout (default 10s); exceeding it is classified `TIMEOUT`. Startup fails unless `lease duration > provider timeout`.
- *Alternatives:* claim a full batch and queue for permits (idle claimed rows, needless lease expiry); immediate shutdown (more reclaims and duplicate provider calls); no explicit timeout (a slow successful send can be reclaimed and re-attempted).

## Data Model (Flyway V1)

```
api_client        id, source_system UNIQUE, key_hash, active
recipient         id, display_name
recipient_channel recipient_id, channel, address, opted_out     PK(recipient_id, channel)
notification      id UUID, source_system, idempotency_key NULL, request_hash NULL, event_id,
                  type, severity, priority, priority_rank, subject, body, body_hash, status,
                  scheduled_at, expires_at, created_at, updated_at, version
                  UNIQUE(source_system, idempotency_key)
delivery          id UUID, notification_id FK, recipient_id, channel, address_masked,
                  status, attempt_count, next_attempt_at, locked_until,
                  last_failure_class, last_attempt_at, completed_at, created_at, version
                  UNIQUE(notification_id, recipient_id, channel)
                  INDEX(status, next_attempt_at)
audit_event       id BIGSERIAL, notification_id NULL, delivery_id NULL, source_system,
                  event_type, reason_code, details JSONB, occurred_at
                  INDEX(notification_id, id)
```
- Message body is kept on `notification` only for delivery and never copied to audit or logs. The worker reads the full address from `recipient_channel` at send time and passes it to the provider; `delivery` stores only the masked form. If the address (or the recipient's row for that channel) no longer exists at send time, no provider call is made and the delivery fails with `INVALID_RECIPIENT`. A changed address is used as-is (latest address wins).
- Audit order uses `id` (BIGSERIAL) as tiebreaker so events in the same transaction/timestamp keep insertion order.
- V2 seeds api clients (`billing`, `trading`), recipients with addresses/opt-outs, and failure-marker addresses.

## API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/notifications` | 202 new, 200 idempotent replay, 400, 401, 403, 409, 422 |
| GET | `/api/v1/notifications/{id}` | 200, 401, 404 |
| GET | `/api/v1/notifications/{id}/audit` | 200, 401, 404 |

Read responses: the status resource returns `id`, `eventId`, `type`, `severity`, `priority`, `status`, `selectedChannels` (distinct channels across deliveries), `createdAt`, `scheduledAt`, `expiresAt`, and `deliveries` (`id`, `recipientId`, `channel`, masked `address`, `status`, `attemptCount`, `lastFailureClass`, `lastAttemptAt`, `nextAttemptAt`, `completedAt`). Subject and body are never returned. Empty values are `null`. The audit resource returns `{"notificationId", "events": [...]}`, oldest first. A malformed ID in the path returns `404`.

OpenAPI via springdoc at `/swagger-ui.html`. Errors use RFC 9457 Problem Details.

## Configuration (`application.yml`)

Routing defaults per type, severity escalation map, fallback channel, idempotency retention and cleanup schedule, retry base/max/attempts, worker concurrency/batch/poll/lease, provider call timeout, shutdown timeout, max recipients.

Notification types are a fixed enum; an unknown type is rejected with `400` like any other enum field. Default channels per type (used when a request names no channels):

| Type | Default channels |
|---|---|
| `TRANSACTIONAL` | `EMAIL` |
| `ALERT` | `EMAIL`, `PUSH` |
| `SECURITY` | `EMAIL`, `SMS` |
| `MARKETING` | `EMAIL` |

Fallback channel: a single channel, default `EMAIL`.

Idempotency cleanup: retention default 7 days; job runs hourly by default (configurable). Every instance may run it — the `UPDATE ... SET idempotency_key = NULL, request_hash = NULL` is idempotent, so no locking is needed.

Worker defaults (per instance):

| Setting | Default |
|---|---|
| concurrency | 20 |
| batch size | 20 |
| poll interval | 500 ms |
| lease duration | 60 s |
| provider call timeout | 10 s |
| shutdown timeout | 30 s |

## Observability

Actuator health (DB). Micrometer counters: `nms.notifications.accepted`, `nms.notifications.rejected`, `nms.deliveries.sent|failed|retried|expired` tagged by channel and failure class; `AUTH_ERROR` failures also logged at ERROR. Structured JSON logs with `notificationId`, `deliveryId`, and request correlation ID (MDC, from `X-Correlation-Id` or generated as a UUID when absent, and echoed back in the `X-Correlation-Id` response header on every response, including errors). Logs use Spring Boot's Logstash JSON format by default; the `local` profile switches to human-readable console logs. The correlation ID is request-scoped only: it is not stored, and worker logs are traced via `notificationId`/`deliveryId`.

## Risks / Trade-offs

- [Postgres-as-queue throughput ceiling, polling latency] → documented exit path via `DeliveryQueue` port (D1); poll interval configurable.
- [No event-level dedup: a source system resubmitting the same event with a new `Idempotency-Key` gets it delivered twice] → accepted known limitation; source systems must reuse the same `Idempotency-Key` when retrying an event. Documented in the README.
- [Simulated providers lack real latency/error shapes] → failure-injection markers cover every failure class.
- [Audit table growth] → partitioning/archival noted as future work.
- [At-least-once: a provider without idempotency support could double-send after lease-expiry reclaim] → delivery ID passed as provider idempotency key; lease duration set well above provider timeout.
- [Rejection audits without notification ID are not readable via the audit API] → acceptable; they exist for operators, queryable by `source_system`.

## Testing Strategy

- Unit: routing rules, retry policy (fixed random), failure classification, state machine transitions, status derivation, masking, canonical hashing.
- Integration (Testcontainers Postgres): submission end-to-end, idempotency incl. concurrent duplicates, SKIP LOCKED with two workers, lease-expiry reclaim, each failure class via markers, expiry, audit completeness, no sensitive content in audit/logs.
- API: MockMvc contract tests for every status code.
- Every spec scenario maps to at least one test named after the scenario.

## Migration Plan

Greenfield — no data migration. Deploy = `docker compose up -d postgres` then run the service; Flyway applies V1/V2 on startup. Rollback = redeploy previous build; schema is new so dropping the database is safe in the prototype.
