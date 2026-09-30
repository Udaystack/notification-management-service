# Notification Management Service (NMS)

Accepts notification requests from source systems, routes them to channels (EMAIL, SMS, PUSH, WEBHOOK),
delivers them asynchronously with bounded retries, and exposes status and audit history.

Stack: Java 21, Spring Boot 3.5, Maven, PostgreSQL 16, Flyway, Docker Compose.
Tests: JUnit 5, MockMvc, Testcontainers, WireMock.

## Deliverables

| Deliverable | Where |
|---|---|
| Working prototype (runnable end-to-end) | [Setup](#setup), then [Demo](#demo) (`scripts/demo.sh` drives every feature against a running instance) and [Load test](#load-test) |
| Architecture overview | [Architecture overview](#architecture-overview): the service (components, control flow, key decisions) and how it was built (tools, execution approach) |
| Three scenarios: greenfield, brownfield, ambiguous | [Scenarios](#scenarios): decomposition, execution, and validation of each. One branch per scenario: [`greenfield`](https://github.com/Udaystack/notification-management-service/tree/greenfield) → [`brownfield`](https://github.com/Udaystack/notification-management-service/tree/brownfield) → [`ambiguous-requirements`](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements); each builds on the previous one |
| Setup instructions | [Setup](#setup) |
| Testing approach, limitations, and trade-offs | [Tests](#tests) and [Limitations and trade-offs](#limitations-and-trade-offs) |

This README describes the **`ambiguous-requirements`** branch. It contains all three scenarios.

## Architecture overview

### Service

| Component (package) | Responsibility |
|---|---|
| `api` | REST endpoints (`POST /api/v1/notifications`, `GET …/{id}`, `GET …/{id}/audit`), Problem Details errors, OpenAPI |
| `security` | `X-API-Key` filter: maps each key to one source system; reads are scoped to it |
| `intake` | Request validation, idempotency, and the intake pipeline: ordered steps in one transaction |
| `routing`, `recipient` | Routing policy (requested/default channels, escalation, opt-out, missing address, fallback) and recipient preferences |
| `dedup` | Duplicate-event lookup and transaction-scoped advisory locks |
| `delivery` | PostgreSQL queue (claim with `FOR UPDATE SKIP LOCKED`, priority aging), poller, worker, outcome recorder, state machine, derived status, metrics |
| `channel` | Provider port; simulated EMAIL/SMS/PUSH providers with failure injection; real signed `WEBHOOK` provider with SSRF guard |
| `retry` | Exponential backoff with full jitter, retry-after, expiry |
| `audit` | Append-only audit history; details builder that cannot hold content or raw addresses |
| `common` | Settings (`nms.*`), domain enums, address masking, correlation IDs |
| `db/migration` | Flyway: V1 schema, V2 seed data, V3 deduplication, V4 webhook channel |

**Control flow.**

1. **Submit:** the API key identifies the source system. The service validates the body, then
   checks the `Idempotency-Key`: a replay returns `200`, a conflicting body `409`. Then one
   transaction runs the intake steps:
   persist notification → route recipients → deduplicate events → create deliveries → audit.
   The client gets `202` without waiting for any provider.
2. **Deliver:** a poller hands free worker slots to the queue. A claim transaction selects due
   rows with `FOR UPDATE SKIP LOCKED`, in
   effective-priority order (with aging), marks them
   `IN_FLIGHT` under a lease, and audits the attempt.
3. **Send:** the provider is called outside any transaction, bounded by a timeout.
4. **Record:** a second transaction applies the outcome only if the row is still the claimed version.
   The delivery becomes `SENT`, `RETRY_SCHEDULED` (backoff), `FAILED`, or `EXPIRED`. The same
   transaction recomputes the notification's derived status and writes the audit event.
5. **Read:** status and audit endpoints read the stored state, scoped to the caller's source system,
   with addresses masked.

**Key decisions** (full rationale in each change's `design.md`):

- **PostgreSQL is the queue (no broker):** enqueueing is atomic with acceptance; `SKIP LOCKED` plus
  leases give safe concurrency across instances. The `DeliveryQueue` port is the exit path to
  Kafka or SQS.
- **Provider calls happen outside transactions,** and outcomes are version-checked: at-least-once
  delivery, with the delivery ID as the provider idempotency key.
- **Idempotency relies on a unique constraint,** not check-then-insert; replays compare a canonical
  request hash.
- **Overall status is derived from the deliveries,** and an explicit state machine rejects invalid
  transitions.
- **Failure classification lives in the provider adapters.** Only `TRANSIENT`, `TIMEOUT`, and
  `RATE_LIMITED` are retried.
- **Audit is append-only and written in the same transaction as the change it records;** content
  and raw addresses can't be written to it.
- **Deduplication (brownfield):** a transaction-scoped advisory lock per
  `(sourceSystem, eventId, recipient, channel)` makes concurrent duplicates race-safe. `SUPPRESSED`
  is a creation-only terminal state. It sits behind the `nms.dedup.enabled` flag.
- **Webhook (brownfield):** a real HTTP adapter behind the same port. Requests are HMAC-signed,
  limited to `https` and public addresses, and never follow redirects. The channel is off by default
  (`nms.webhook.enabled`).
- **Priority (ambiguous):** aging inside the claim SQL, so the order holds across instances; the
  stored priority is never changed; `nms.worker.priority-aging=0` restores strict order.

### How it was built

| Tool | Role |
|---|---|
| Maven, JUnit 5, MockMvc, Testcontainers, WireMock | Build and tests against a real PostgreSQL 16 and a stub HTTP server |
| Docker Compose, `scripts/demo.sh`, k6 | Local database, end-to-end demo, load test |
| Git, GitHub | One branch per scenario; grouped, reviewable commits |

## Scenarios

Each scenario is one or more OpenSpec changes, shown as **decomposition** (how the request became specs and tasks), **execution** (how it was built), and **validation** (how it was proven).

### 1. Greenfield: build the service from a written brief

Branch: `greenfield`. OpenSpec change: `add-notification-core` (archived in `openspec/changes/archive/2026-09-29-add-notification-core/`).

- **Decomposition:** the brief became 6 capabilities (submission, status, routing, idempotency,
  delivery processing, audit) with 47 scenarios, a design with 13 decisions (D1–D10), and 48 tasks
  in 10 groups: skeleton, domain, security, audit, routing, submission, delivery, read APIs,
  operability, integration checks. The first draft filled gaps in the brief with assumptions (for
  example how long idempotency keys are kept, and auditing of rejected requests). The user asked to
  be consulted instead, and "ask, don't assume" became the rule for all later work.
- **Execution:** 14 commits, one per layer (schema → domain → security → audit → routing →
  channels → intake → API → delivery → observability → end-to-end tests → docs → demo and load
  test).
- **Validation:** 117 tests (53 unit, 64 integration against PostgreSQL). Every one of the 47
  scenarios maps to a test named after it (`docs/scenario-coverage.md`). `scripts/demo.sh` exercises
  it end to end, and a k6 load test measured about 115 deliveries/s sustained with no backlog.

### 2. Brownfield: change a running system without breaking it

Branch: `brownfield`. Two changes, on top of the greenfield service and its specs:

| | `add-event-deduplication` | `add-webhook-channel` |
|---|---|---|
| Goal | Suppress the same event sent again with a new idempotency key | Add a real outbound channel: signed HTTP webhooks |
| Decomposition | 3 delta specs (1 new capability), 20 scenarios, 8 design decisions, 25 tasks | 3 delta specs (1 new capability), 27 scenarios, 12 design decisions, 21 tasks, after 17 clarifying questions (provider, SSRF policy, status mapping, flag behavior, …) |
| Safety | Flag `nms.dedup.enabled`; Flyway V3 is additive only; rollback rule documented | Flag `nms.webhook.enabled` (off by default); Flyway V4; rollback rule documented |
| Commits | 9, plus archive | 7, plus archive |

- **Execution:** both changes followed a safe change plan. First, characterization tests pinned
  current behavior, including a test showing the duplicate-delivery bug. Then the migration landed
  alone with the full suite green. Behavior changed only behind a feature flag, and docs and demo
  came last. When Docker failed mid-change, the work was paused with a handoff note and resumed
  later.
- **Validation:** 194 tests, green with the flags at their defaults and with `-Pdedup-off`. A
  mutation check removed the advisory lock and saw the concurrency test fail 3 out of 3 runs.
  Webhook behavior is tested against a WireMock stub, with the HMAC checked against `openssl`. The
  demo ran end to end, `openspec validate --strict` passed, every commit's suite passed on its own,
  and both changes were archived into the main specs.

### 3. Ambiguous: settle underspecified behavior before building it

Branch: `ambiguous-requirements`. OpenSpec change: `clarify-delivery-priority`.

- **Starting point:** a question ("what does priority do?") exposed five gaps in the specs: lower
  priorities can starve under `HIGH` load; retries vs new work was never stated; deliveries can
  expire unnoticed while waiting; there is no latency target; and it was unclear whether priority
  is required.
- **Decomposition:** each gap became a decision, with options and a recommendation. Terms were
  explained before choosing (for example what "queue wait" measures). Follow-up questions settled
  the details: aging interval, when a retry starts aging, an off switch, and one edge case found in
  the code (reclaimed leases). The result: 2 delta specs, 14 scenarios, 5 design decisions, 12
  tasks.
- **Execution:** characterization of today's claim order → config → aging in the claim SQL →
  observability (`nms.deliveries.queue-wait`, expiry tags, WARN) → docs; 7 commits plus archive.
  Midway, a tooling gap (a `-D` flag doesn't reach Failsafe) was surfaced to the user instead of
  worked around, which led to an `aging-off` profile.
- **Validation:** 208 tests, green in all three modes (default, `-Pdedup-off`, `-Paging-off`). The
  characterization test changed in exactly the one intended place (reclaim order). Every commit
  passes on its own, strict validation passed, and the change was archived into the main specs.

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
mvn verify                # event deduplication on (default)
mvn verify -Pdedup-off    # the same suite with nms.dedup.enabled=false
mvn verify -Paging-off    # the same suite with nms.worker.priority-aging=0 (strict priority)
```

The `dedup-off` profile proves that switching deduplication off restores the previous behavior;
deduplication tests set the flag they need themselves, so they pass in both runs. The `aging-off`
profile does the same for priority aging; claim-order tests pin their own aging interval. Unit tests run with Surefire (`*Test`); integration tests run with Failsafe (`*IT`) against a
throwaway PostgreSQL 16 Testcontainer, so Docker must be running. The local Compose database is
not used by tests.

Every spec scenario has a test named after it; see [docs/scenario-coverage.md](docs/scenario-coverage.md).

**Testing approach.**

- **Unit tests** cover the pure domain: the state machine, status derivation, routing policy, retry
  policy, masking, validation, the webhook target policy and signing.
- **Integration tests** boot the whole application against a real PostgreSQL 16 (Testcontainers)
  and drive it through the HTTP API (MockMvc) or the worker. They cover real SQL: `SKIP LOCKED`
  races, constraints, and migrations.
- **Stub HTTP server:** the webhook provider is tested against WireMock, covering every
  status-code mapping, timeouts, redirects, and signatures.
- **Characterization tests first:** before any behavior change, tests pin the current behavior, so
  every difference afterwards is a reviewed, intended diff.
- **Flag profiles:** the whole suite also runs with a feature switched off (`-Pdedup-off`,
  `-Paging-off`) and must stay green in every mode.
- **Traceability:** every spec scenario has a test named after it
  (`docs/scenario-coverage.md`).
- **End to end and load:** `scripts/demo.sh` against a running instance, and the k6 load test.

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
3. Remove disabled channels (`CHANNEL_DISABLED`): `WEBHOOK` while `nms.webhook.enabled` is false.
4. Remove channels the recipient opted out of (`RECIPIENT_OPT_OUT`).
5. Remove channels the recipient has no address for (`NO_ADDRESS`).
6. If nothing is left, add the fallback channel if the recipient has an address for it and has not
   opted out of it (`FALLBACK`).

One delivery is created per remaining (recipient, channel). A recipient left with nothing is
recorded as `NO_ELIGIBLE_CHANNEL`; an unknown recipient ID as `UNKNOWN_RECIPIENT`. If no recipient
gets any delivery, the request is rejected with `422` (`NO_ELIGIBLE_CHANNEL`). Duplicate channels or
recipients in one request are rejected with `400`.

Every decision is audited as one `ROUTING_DECIDED` event per recipient, listing the selected
channels, what each rule added, and what each rule removed (a channel added then removed appears
only under removed).

`WEBHOOK` is only used when a request lists it in `channels`: it is never a type default, an
escalation channel, or the fallback (see [Webhook channel](#webhook-channel)).

Severity drives routing (which channels); priority drives processing order (which deliveries are
claimed first). They are independent.

Configuration (`nms.routing` in `application.yml`):

| Setting | Default |
|---|---|
| `default-channels` | `TRANSACTIONAL: [EMAIL]`, `ALERT: [EMAIL, PUSH]`, `SECURITY: [EMAIL, SMS]`, `MARKETING: [EMAIL]` |
| `severity-escalation` | `CRITICAL: [SMS]` |
| `fallback-channel` | `EMAIL` |

The service refuses to start if any of these settings contains `WEBHOOK`.

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

`priority` is required and chosen by the source system (see [Priority](#priority)).
`channels`, `scheduledAt`, and `expiresAt` are optional. A `scheduledAt` in the past means "send
now". Unknown fields, duplicate recipients, and duplicate channels are rejected. Checks run in this
order: `401` → `403` → `400` (every invalid field listed) → idempotency → `400` if `expiresAt` has
passed → `202` or `422`. The `202` body is `{id, status, statusUrl}`; `status` is `ACCEPTED`, or
`SUPPRESSED` when every delivery was suppressed as a duplicate event.

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

Resubmitting the same business event with a *new* `Idempotency-Key` is a new submission; it is
caught by [event deduplication](#event-deduplication) instead.

## Event deduplication

Source systems often re-emit the same event with a new `Idempotency-Key` (restarts, replays,
at-least-once producers). The service suppresses such a repeat per recipient and channel, so the
recipient isn't sent the same alert twice.

- **Key:** `(sourceSystem, eventId, recipientId, channel)`. The boundary is the source system:
  the same `eventId` from `billing` and from `trading` is independent.
- **Window:** a delivery is suppressed when another delivery with the same key was created within
  the window, `nms.dedup.window` (default `24h`, must be a positive duration; startup fails
  otherwise).
- **Exclusions:** an earlier delivery that is `FAILED`, `EXPIRED`, or itself `SUPPRESSED` doesn't
  count, so a source system can re-send after a failure. A different channel or a different source
  system is never a duplicate. Only channels that routing selected are checked.
- **Result:** the resubmission is still accepted with `202`. The duplicate delivery is stored as
  `SUPPRESSED` with `suppressedBy` pointing at the original delivery, and is never sent;
  `DELIVERY_SUPPRESSED` (reason `DUPLICATE_EVENT`) is audited and `nms.deliveries.suppressed` is
  incremented. Other deliveries of the same submission are created normally. When every delivery is
  suppressed, the `202` body and the notification carry overall status `SUPPRESSED`. `422` still
  means only that routing found no eligible channel.
- **Concurrency:** submissions of the same key serialize on a transaction-scoped PostgreSQL advisory
  lock, so of several concurrent submissions exactly one delivery is deliverable.
- **Data:** delivery data is kept indefinitely; there is no purge policy.
- **Intentional re-sends** of the same event (for example reminders) must use a new `eventId`.
- **Contract change (additive):** new delivery and notification status `SUPPRESSED` and the new
  `suppressedBy` field. Clients that switch exhaustively on status values must handle `SUPPRESSED`.

**Feature flag.** `nms.dedup.enabled` (default `true`). With `false`, intake behaves exactly as
before event deduplication.

**Rollback.** To disable deduplication, restart with `nms.dedup.enabled=false`. Do not roll back to
a version without deduplication support once any delivery has been suppressed; see design, Migration
Plan.

## Reading status and audit

`GET /api/v1/notifications/{id}` returns the notification's `eventId`, `type`, `severity`,
`priority`, overall `status` (see [State model](#state-model)), `selectedChannels`, `createdAt`,
`scheduledAt`, `expiresAt`, and one entry per delivery: `id`, `recipientId`, `channel`, `address`
(masked, e.g. `j***@example.com`, or `https://hooks.example.com/***` for a webhook URL), `status`, `attemptCount`, `lastFailureClass`, `suppressedBy`,
`lastAttemptAt`, `nextAttemptAt`, and `completedAt`. Subject and body are never returned.

`suppressedBy` is `{notificationId, deliveryId}` of the original delivery for a `SUPPRESSED`
delivery and `null` for every other delivery. The original always belongs to the same source system.
A suppressed delivery has `attemptCount` 0, `nextAttemptAt` `null`, and `completedAt` equal to its
creation time:

```json
{
  "id": "…",
  "recipientId": "cust-1001",
  "channel": "EMAIL",
  "address": "j***@example.com",
  "status": "SUPPRESSED",
  "attemptCount": 0,
  "lastFailureClass": null,
  "suppressedBy": { "notificationId": "…", "deliveryId": "…" },
  "lastAttemptAt": null,
  "nextAttemptAt": null,
  "completedAt": "2026-09-29T10:00:00.123456Z"
}
```

`GET /api/v1/notifications/{id}/audit` returns `{notificationId, events}`, oldest first; each event
has `id`, `notificationId`, `deliveryId`, `eventType`, `reasonCode`, `details`, and `occurredAt`.

Both endpoints answer `404 Not Found` for an unknown ID and for a notification owned by another
source system.

## Delivery processing

**PostgreSQL is the queue.** Deliveries are rows, enqueued in the same transaction that accepts the
notification (no broker, no dual write). Workers claim due rows with `FOR UPDATE SKIP LOCKED` in one
short transaction, so concurrent workers and instances never claim the same row:

- Due = `PENDING`/`RETRY_SCHEDULED` with `nextAttemptAt <= now`, or `IN_FLIGHT` whose lease expired.
- Order: effective priority rank, then due time, oldest first (see [Priority](#priority) below).
- A claim sets `IN_FLIGHT`, a lease (`locked_until`), and counts an attempt (`DELIVERY_ATTEMPTED`).
  A delivery whose notification passed `expiresAt` becomes `EXPIRED` instead, without an attempt.
- The provider is called **outside** any transaction, bounded by the provider timeout. The outcome is
  recorded in a second transaction that only applies if the row is still the claimed version; a
  worker whose lease expired and whose delivery was reclaimed has its late result discarded.
- Processing is at-least-once. The delivery ID is the provider idempotency key, so a delivery
  reclaimed after a worker crash is not sent twice.
- The recipient's full address is read at send time; if it is gone, the delivery fails with
  `INVALID_RECIPIENT` without a provider call.

<a id="priority"></a>**Priority.** The source system sets `priority` (`LOW`, `NORMAL`, `HIGH`) on every request; it is
required and has no default. Priority only orders claims; there is no latency guarantee for any
priority.

- **Due time:** `nextAttemptAt` for a first attempt or a retry (so time spent in retry backoff never
  counts as waiting), or the lease expiry for a delivery reclaimed after a worker crash.
- **Aging (starvation protection):** the effective rank is the priority rank (`LOW` 0, `NORMAL` 1,
  `HIGH` 2) plus one for every full `nms.worker.priority-aging` interval (default 5m) since the due
  time, capped at `HIGH`. The stored priority never changes. `0` switches aging off (strict priority).
- **Ties** at the same effective rank go to the delivery that has been due longest. Retries and first
  attempts follow the same rule; neither is preferred.

With the default 5m, a `LOW` delivery due at 10:00 competes as `LOW` until 10:05, as `NORMAL` until
10:10, and as `HIGH` from 10:10. It then goes ahead of any `HIGH` delivery that became due after
10:00, so under sustained `HIGH` load a `LOW` delivery waits about 10 minutes rather than forever.

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
| `priority-aging` | 5m (`0` = strict priority; negative fails startup) |

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

## Webhook channel

`WEBHOOK` delivers to systems rather than people: the service sends a signed HTTP `POST` to the
recipient's webhook URL, stored as its `WEBHOOK` address (seeded like every other address; opt-out
works the same way). It is the only real outbound provider; the others are simulated.

**Turning it on.** Off by default. To enable it:

| Setting (`nms.webhook`) | Default | Meaning |
|---|---|---|
| `enabled` | `false` | While off, routing removes `WEBHOOK` with `CHANNEL_DISABLED` (the fallback still applies), and a webhook delivery that is already queued fails without any request: `FAILED`, failure class `PERMANENT_REJECTION`, `DELIVERY_FAILED` reason `CHANNEL_DISABLED`. |
| `signing-secret` | `${NMS_WEBHOOK_SIGNING_SECRET:}` | HMAC key. Required when `enabled` is true (startup fails otherwise). Never logged, audited, or returned. |
| `allow-private-hosts` | `false` | Also allow `http` and non-public addresses. For local development and tests only. |

The `local` profile enables webhooks with private hosts and a dev-only secret
(`local-dev-only-not-a-secret`, overridable with `NMS_WEBHOOK_SIGNING_SECRET`).

**Request.** One `POST` per attempt, `Content-Type: application/json`:

```json
{
  "notificationId": "…", "deliveryId": "…", "eventId": "INV-42", "sourceSystem": "billing",
  "type": "TRANSACTIONAL", "severity": "MEDIUM", "priority": "NORMAL", "recipientId": "cust-3001",
  "subject": "Invoice ready", "body": "Your invoice INV-42 is ready.",
  "attempt": 1, "sentAt": "2026-09-29T10:00:00.123456Z"
}
```

| Header | Value |
|---|---|
| `Idempotency-Key` | The delivery ID; the same on every attempt, so receivers can drop repeats |
| `X-NMS-Timestamp` | Send time in Unix seconds (the same instant as `sentAt`) |
| `X-NMS-Signature` | `sha256=` + lowercase hex HMAC-SHA256 of `<X-NMS-Timestamp>.<raw body>` with the signing secret |

To verify, recompute the HMAC over the raw bytes of the body (before parsing it), compare in
constant time, and reject stale timestamps to prevent replays. In Python:

```python
expected = "sha256=" + hmac.new(secret, f"{timestamp}.".encode() + raw_body, hashlib.sha256).hexdigest()
ok = hmac.compare_digest(expected, request.headers["X-NMS-Signature"])
```

`scripts/webhook-receiver.py` is a complete example.

**Target policy (SSRF protection).** Right before each call the URL must be `https`, and every
address its host resolves to must be public: not loopback (including `0.0.0.0`/`::`), private
(including IPv6 `fc00::/7`), link-local, or multicast. A URL that breaks this rule or cannot be parsed
fails as `INVALID_RECIPIENT` without a request. Redirects are never followed. `allow-private-hosts`
lifts the scheme and address rules.

**Outcomes.** Each call is bounded by `nms.worker.provider-timeout`; response bodies are never read.

| Outcome | Failure class | Result |
|---|---|---|
| `2xx` | — | `SENT` |
| Connection error, unresolvable host, `5xx` | `TRANSIENT` | retried |
| No response within the provider timeout | `TIMEOUT` | retried |
| `429` | `RATE_LIMITED` | retried, not before `Retry-After` (seconds or HTTP date) |
| `401`, `403` | `AUTH_ERROR` | `FAILED`, plus an ERROR log |
| `404`, `410` | `INVALID_RECIPIENT` | `FAILED` |
| Other `4xx`, any `3xx` | `PERMANENT_REJECTION` | `FAILED` |

**Masking.** Webhook URLs show only scheme and host (`https://hooks.example.com/***`) in the status
API, logs, and audit; user info, port, path, and query (which often hold tokens) are never shown.

**Contract change (additive).** `WEBHOOK` can appear as a channel in requests, `selectedChannels`,
deliveries, and audit, and `CHANNEL_DISABLED` as a routing reason. Clients that switch exhaustively
on channel values must handle `WEBHOOK`.

**Rollback.** To stop webhooks, restart with `nms.webhook.enabled=false`; queued webhook deliveries
then fail with `CHANNEL_DISABLED` and nothing more is sent. Do not roll back to a version without
webhook support: the V4 migration seeds a `WEBHOOK` address (`cust-3001`) right away, and older code
cannot read `WEBHOOK` rows (requests naming that recipient fail with `500`, and a queued webhook
delivery stalls the claim of its batch). See design, Migration Plan.

## Demo

`scripts/demo.sh` walks through the happy path, idempotent replay, conflict, event deduplication, a
signed webhook to a local receiver (`scripts/webhook-receiver.py` on port 9099, started and stopped by
the demo), a retry (`+flaky`), permanent failures, rate limiting, and the `400`/`401`/`403`/`422`/`404`
responses against a running instance started with the `local` profile. It needs `curl`, `jq`, and
`python3`.

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
  `nms.deliveries.retried{channel,failureClass}`,
  `nms.deliveries.expired{channel,priority,neverAttempted}`,
  `nms.deliveries.suppressed{channel,sourceSystem}`, and the timer
  `nms.deliveries.queue-wait{priority,channel}`. They are not exposed over HTTP by default; wire a
  registry (e.g. Prometheus) to export them.
- **Watching priority.** `nms.deliveries.queue-wait` is the time from a delivery's due time to its
  first claim, recorded once per delivery: it shows how long each priority waits in the queue
  (provider time and retries are excluded). With aging on, `LOW` should not wait much beyond
  `2 × priority-aging`. `nms.deliveries.expired{neverAttempted=true}` counts deliveries that expired
  while still waiting for their first attempt; each also logs a WARN with its notification ID,
  delivery ID, and priority.
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
- **Event deduplication is by event ID only.** The same content under a different `eventId`, and
  the same event from different source systems, are not deduplicated; deliveries already queued are
  never suppressed retroactively.
- **Simulated providers.** EMAIL, SMS, and PUSH have no real latency or error shapes;
  failure-injection markers cover every failure class. `WEBHOOK` is real.
- **Webhook DNS rebinding.** The target policy checks the resolved addresses right before each call,
  but the HTTP client resolves the host again when it connects, so a hostile DNS server could switch
  to a private address in between. Fixing this needs connections pinned to the checked address.
- **One webhook signing secret.** Shared by all receivers; per-recipient secrets and rotation are
  future work.
- **Audit growth.** `audit_event` is append-only and unpartitioned; partitioning and archival are
  future work. Rejection audits without a notification ID are not visible through the audit API.
- **Authentication.** Static per-source-system API keys (hashed); OAuth2 client credentials is the
  production path.
- **Spring Boot 3.5.** Its open-source support has ended; moving to Spring Boot 4.x is future work.
- **Load test.** Single run on a laptop; numbers are indicative, not a capacity guarantee.

**Process trade-offs.**

- **Asking before building** costs round-trips, but the specs record decisions the user actually
  made, not guesses.
- **Characterization tests and per-commit full-suite runs** make each change slower to land, but
  every commit is green on its own and every behavior change is an explicit diff.
- **AI-written code** is trusted only through tests, scenario coverage, and strict spec validation,
  not by inspection alone.
