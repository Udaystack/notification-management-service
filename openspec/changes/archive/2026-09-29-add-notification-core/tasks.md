# Tasks

## 1. Project skeleton

- [x] 1.1 Create Spring Boot 3 / Java 21 Maven project (web, validation, jdbc, flyway, postgresql, actuator, springdoc, testcontainers, micrometer) and verify `mvn -q package -DskipTests` succeeds
- [x] 1.2 Add `docker-compose.yml` with PostgreSQL 16 and `application.yml` with all config properties from design (routing, retry, worker, idempotency retention, max recipients) bound to typed `@ConfigurationProperties`; verify `docker compose up -d` and app startup succeed
- [x] 1.3 Create package structure per design (`api`, `intake`, `routing`, `delivery`, `retry`, `channel`, `recipient`, `audit`, `security`, `common`) and a Testcontainers base integration test class; verify a context-loads test passes against Testcontainers Postgres
- [x] 1.4 Flyway V1 schema (tables, unique constraints, indexes, nullable `idempotency_key`/`request_hash`, `priority_rank`, nullable audit `notification_id`) and V2 seed data (api clients `billing`/`trading`, recipients with addresses, opt-outs, failure-marker addresses); verify migrations apply cleanly in the base test
- [x] 1.5 Start README with setup/run instructions (compose, run, test) and verify the documented commands work as written

## 2. Domain core (pure Java, unit tested)

- [x] 2.1 Enums: Channel, NotificationType, Severity, Priority (with rank), DeliveryStatus, NotificationStatus, FailureClass (with `retryable` flag), AuditEventType; verify by compiling with no Spring imports in the domain package
- [x] 2.2 `DeliveryStateMachine` with the allowed-transition table from specs + unit tests for every allowed and a representative set of invalid transitions (incl. `SENT` → `IN_FLIGHT`)
- [x] 2.3 `NotificationStatusDeriver` + unit tests for the derived statuses named in spec scenarios (`ACCEPTED`, `IN_PROGRESS`, `PARTIALLY_DELIVERED`)
- [x] 2.4 `RetryPolicy` (full-jitter backoff, max delay cap, retry-after floor, max attempts → `RETRIES_EXHAUSTED`, expiry cutoff → `EXPIRED`) + unit tests with an injected deterministic random source
- [x] 2.5 `AddressMasker` for email and phone + unit test (`jane.doe@example.com` → `j***@example.com`)
- [x] 2.6 Document the delivery state model and derived-status rules in the README and verify they match the state machine tests

## 3. Security

- [x] 3.1 `ApiKeyAuthFilter`: SHA-256 hash lookup in `api_client`, `401` Problem Details on missing/unknown/inactive key, source system placed in security context; API key never logged
- [x] 3.2 Source-system mismatch on submit → `403`; ownership filter on reads → `404`
- [x] 3.3 MockMvc tests: missing key (401), invalid key (401), mismatch (403), cross-tenant status and audit read (404); verify all pass
- [x] 3.4 Document API key auth (including the local-only plain-text demo keys for `billing`/`trading`), tenant isolation, and the OAuth2 production path in the README

## 4. Audit

- [x] 4.1 `AuditService` append-only writer that joins the caller's transaction (`MANDATORY` propagation) and a separate-transaction variant for rejection events; no update/delete methods exposed
- [x] 4.2 Audit read repository ordered by `id` for a notification, filtered by source system
- [x] 4.3 Details builder that only accepts non-sensitive fields (masked address, channel, reason codes, failure class); integration test asserting no subject, body, full address, or API key appears in any audit row

## 5. Routing

- [x] 5.1 `RecipientPreferenceRepository` reading addresses and opt-outs from `recipient_channel`; verify with an integration test against seed data
- [x] 5.2 `RoutingPolicy` with ordered rules (requested/default, severity escalation, opt-out, missing address, fallback) returning a per-recipient `RoutingDecision` with add/remove reasons (`SEVERITY_ESCALATION`, `RECIPIENT_OPT_OUT`, `NO_ADDRESS`, `FALLBACK`, `NO_ELIGIBLE_CHANNEL`, `UNKNOWN_RECIPIENT`)
- [x] 5.3 Unit tests for every channel-routing scenario (requested honored, critical escalation, opt-out with fallback, no channel for one recipient, unknown recipient, none eligible) and verify they pass
- [x] 5.4 Document the routing policy order and its configuration in the README

## 6. Submission + idempotency

- [x] 6.1 Request/response DTOs with Bean Validation; custom validators for timestamps (`expiresAt` > `scheduledAt`, `expiresAt` in future), recipient limit, and required `Idempotency-Key` header; unit tests for each rule
- [x] 6.2 Canonical request hashing (SHA-256, sorted-key JSON, normalized timestamps) + unit tests that field order does not change the hash and a changed field does
- [x] 6.3 `NotificationIntakeService` as ordered `IntakeStep`s in one transaction: `INSERT ... ON CONFLICT DO NOTHING` notification, route, create deliveries (`nextAttemptAt` = `scheduledAt` or now), status `ACCEPTED`, audit `NOTIFICATION_ACCEPTED`/`ROUTING_DECIDED`/`DELIVERY_QUEUED`
- [x] 6.4 Conflict handling: existing row with equal hash → `200` replay + `DUPLICATE_SUBMISSION` audit; different hash → `409`; zero deliveries → rollback + `422` + `NOTIFICATION_REJECTED(NO_ELIGIBLE_CHANNEL)` in a separate transaction
- [x] 6.5 `POST /api/v1/notifications` controller (`202` + `Location`) and global ProblemDetail handler for 400/401/403/409/422; validation failures write `NOTIFICATION_REJECTED`
- [x] 6.6 Integration tests for all submission and idempotency scenarios: valid accept, async response, each validation failure, scheduled notification, same key/same payload, same key/different payload, concurrent duplicates (parallel threads, exactly one row), same key across source systems; verify all pass
- [x] 6.7 Idempotency retention cleanup job (nulls key and hash after retention) + integration test that the key is reusable afterwards and the original notification still readable
- [x] 6.8 Document the idempotency boundary, replay/conflict semantics, retention, and the known limitation that resubmitting the same event with a new key delivers it twice (clients must reuse the key when retrying) in the README

## 7. Delivery processing

- [x] 7.1 `ChannelProvider` port and `DeliveryResult` (`Success` | `Failure(FailureClass, retryAfter)`) taking the delivery ID as provider idempotency key
- [x] 7.2 Simulated Email/Sms/Push providers with failure-injection markers (`+transient`, `+timeout`, `+ratelimit`, `+reject`, `+invalid`, `+auth`, `+flaky`) + unit test that `user+invalid@example.com` returns `INVALID_RECIPIENT`
- [x] 7.3 `DeliveryQueue` Postgres implementation: claim with `FOR UPDATE SKIP LOCKED`, lease, priority ordering, expired-lease reclaim; integration tests for priority order and two concurrent claimers never sharing a delivery
- [x] 7.4 `DeliveryWorker`: claim → expiry check (no provider call if expired) → full-address lookup from `recipient_channel` (missing → `FAILED` with `INVALID_RECIPIENT`, no provider call) → provider call outside tx (exceptions → `TRANSIENT`) → conditional outcome update (`status`+`version` guard, stale result discarded) → retry scheduling via `RetryPolicy` → status recompute → audit
- [x] 7.5 Scheduled poller dispatching to a virtual-thread executor bounded by configured concurrency: claims only `min(batchSize, free permits)`, graceful shutdown with configurable timeout, provider call timeout (→ `TIMEOUT`), startup check `lease > provider timeout`; verify via integration test that a submitted notification reaches `SENT`
- [x] 7.6 `AUTH_ERROR` handling emits ERROR log and metric; verify with an integration test that a `+auth` delivery becomes `FAILED` after 1 attempt, an ERROR log line is captured, and the auth-error metric increments
- [x] 7.7 Integration tests: success, each failure class via markers, transient-then-success (`attemptCount` = 2), retries exhausted (`RETRIES_EXHAUSTED`), rate-limit delay ≥ retry-after, expiry while waiting and claimed-after-expiry, lease-expiry reclaim with same provider key; verify all pass
- [x] 7.8 Document the queue design, retry policy, failure classes, and failure-injection markers in the README

## 8. Read APIs

- [x] 8.1 `GET /api/v1/notifications/{id}` returning overall status, selected channels, per-delivery status, attempt count, last failure class, masked addresses, and all timestamps
- [x] 8.2 `GET /api/v1/notifications/{id}/audit` returning chronological events with ownership check
- [x] 8.3 Tests for status and audit scenarios: in-progress, masked address, unknown ID 404, cross-tenant 404, full-lifecycle audit order (via `+flaky`); verify all pass

## 9. Operability

- [x] 9.1 Micrometer counters (`nms.notifications.accepted|rejected`, `nms.deliveries.sent|failed|retried|expired` tagged by channel/failure class) + test asserting counters increment
- [x] 9.2 Structured JSON logging with `notificationId`, `deliveryId`, correlation ID (MDC; read from `X-Correlation-Id` or generated, echoed in the response header; not propagated to worker logs); test that a processed notification's body text never appears in captured logs
- [x] 9.3 Actuator health (DB) and springdoc OpenAPI; verify `/actuator/health` is UP and `/swagger-ui.html` loads
- [x] 9.4 `requests.http` / curl demo script covering happy path, idempotent replay, conflict, retries, and failures; verify it runs end-to-end against a local instance and link it from the README

## 10. Integration checks

- [x] 10.1 k6 load test script; run it locally and record results (throughput, p95 accept latency, time-to-sent) in a README table, along with limitations and trade-offs from design
- [x] 10.2 Verify every spec scenario has a test named after it (scenario → test checklist) and `mvn verify` is green
- [x] 10.3 Run `openspec validate add-notification-core --strict` and verify it passes
