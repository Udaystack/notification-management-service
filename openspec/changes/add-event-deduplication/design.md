# Design

## Context

Brownfield change to the service built by the archived `add-notification-core` (see proposal.md - Why). Relevant current code:

- Intake runs ordered `IntakeStep` beans in one transaction (`PersistNotificationStep` @10, `RouteRecipientsStep` @20, `CreateDeliveriesStep` @30, `AuditIntakeStep` @40). `CreateDeliveriesStep` inserts one `PENDING` delivery per selected (recipient, channel) and throws `NoEligibleChannelException` (→ `422`) when routing selected nothing. The notification row is inserted with status `ACCEPTED`, and `NotificationIntakeService` returns `ACCEPTED`.
- `NotificationStatusDeriver` (pure) and `NotificationStatusUpdater` (recomputes the cached status in the caller's transaction) own overall status. `DeliveryStateMachine` holds the transition table.
- `PostgresDeliveryQueue` claims only `PENDING`/`RETRY_SCHEDULED` due rows and expired `IN_FLIGHT` leases, so any other status is never claimed.
- V1 created unnamed inline checks; PostgreSQL named them `delivery_status_check` and `notification_status_check`.
- Delivery data is never purged.

## Goals / Non-Goals

**Goals:**
- Suppress duplicate event deliveries per recipient and channel, race-safe across concurrent submissions and instances.
- Keep suppression visible in status and audit.
- Reversible through `nms.dedup.enabled` with no behavior change when off.
- No regression: the full existing suite passes with the flag on and off.

**Non-Goals:**
- Content-based or cross-source-system deduplication; retroactive suppression of queued deliveries; a delivery data retention policy.

## Decisions

### E1. Dedup key and placement
Key: `(source_system, event_id, recipient_id, channel)`. A new `EventDedupStep` (@25) runs after `RouteRecipientsStep` and before `CreateDeliveriesStep`, so only channels that routing actually selected are checked. It records, per selected pair, the original `(notificationId, deliveryId)` when the pair is a duplicate. `CreateDeliveriesStep` then inserts that pair as `SUPPRESSED` (with `suppressed_by`, `completed_at` = now, `next_attempt_at` NULL, `attempt_count` 0) and every other pair as `PENDING`. The `422` check stays as is: it fires only when routing selected no channel for any recipient, so a submission whose routed deliveries are all suppressed is still `202`.
- *Alternative:* a separate step after delivery creation that updates rows to `SUPPRESSED`. Rejected: `SUPPRESSED` is creation-only, and an insert-then-update would break that rule.

### E2. Race safety with transaction-scoped advisory locks
Before its lookups the step takes `pg_advisory_xact_lock(hashtextextended(key, 0))` for each selected pair, in sorted key order to avoid deadlocks between submissions that share several pairs. Submissions of the same event serialize on the key, and the locks release at commit. Under READ COMMITTED, the second transaction's lookup runs after the first commits and sees its delivery.
- 64-bit `hashtextextended` instead of the 32-bit `hashtext`: fewer collisions where unrelated keys briefly wait on each other. Correctness never depends on the hash, because the lookup compares full keys.
- *Alternative:* a partial unique index on the key. Rejected: it can't express the time window or the `FAILED`/`EXPIRED`/`SUPPRESSED` exclusions.

### E3. Lookup
```sql
SELECT d.id, d.notification_id FROM delivery d
JOIN notification n ON n.id = d.notification_id
WHERE n.source_system = :src AND n.event_id = :evt
  AND d.recipient_id = :rcp AND d.channel = :ch
  AND d.created_at >= :now - :window
  AND d.status NOT IN ('FAILED', 'EXPIRED', 'SUPPRESSED')
ORDER BY d.created_at LIMIT 1
```
`:now` comes from the application `Clock`, as elsewhere. Because suppressed rows are excluded, a suppressed delivery always points at a deliverable original, never at another suppressed row. An original that is `PENDING` (including scheduled for later), `IN_FLIGHT`, `RETRY_SCHEDULED`, or `SENT` counts. Idempotent replays are unaffected: they return before the intake transaction's steps run.

### E4. Migration (Flyway V3), backward compatible
```sql
ALTER TABLE delivery ADD COLUMN suppressed_by UUID NULL REFERENCES delivery (id);
ALTER TABLE delivery DROP CONSTRAINT delivery_status_check,
  ADD CONSTRAINT delivery_status_check CHECK (status IN
    ('PENDING','IN_FLIGHT','SENT','RETRY_SCHEDULED','FAILED','EXPIRED','SUPPRESSED'));
ALTER TABLE notification DROP CONSTRAINT notification_status_check,
  ADD CONSTRAINT notification_status_check CHECK (status IN
    ('ACCEPTED','IN_PROGRESS','COMPLETED','PARTIALLY_DELIVERED','FAILED','EXPIRED','SUPPRESSED'));
CREATE INDEX ix_notification_src_event ON notification (source_system, event_id);
CREATE INDEX ix_delivery_dedup ON delivery (notification_id, recipient_id, channel, created_at);
```
The spec's E4 widened only the delivery constraint. The notification constraint must widen too, because the new overall status `SUPPRESSED` is stored on `notification.status`. Everything is additive: a nullable column, new indexes, widened constraints, no backfill. Rolling back the code leaves a schema the old version works with, since it never writes `SUPPRESSED`.

### E5. State model and derived status
- `DeliveryStatus.SUPPRESSED` is terminal with no transitions in or out, and `NotificationStatus.SUPPRESSED` is added.
- `NotificationStatusDeriver` ignores suppressed deliveries. If every delivery is suppressed, the result is `SUPPRESSED`. Otherwise the existing rules apply to the non-suppressed deliveries: `ACCEPTED` while all of them are `PENDING`, `IN_PROGRESS` once any has left `PENDING` and one is non-terminal, then `COMPLETED`/`PARTIALLY_DELIVERED`/`FAILED`/`EXPIRED`.
- Intake derives the initial status instead of hardcoding `ACCEPTED`. The notification row is inserted as `ACCEPTED`, and after deliveries are created the status is recomputed in the same transaction, so it becomes `SUPPRESSED` when every delivery was suppressed. The `202` body returns that status.
- The worker never sees suppressed rows (E1 of the core design: the claim query filters by status).

### E6. Feature flag and window
- `nms.dedup.enabled` (default `true`) gates `EventDedupStep`. When it's off the step does nothing, and behavior is identical to the previous release.
- `nms.dedup.window` (default `24h`) must be a positive duration, validated at startup. There's no upper bound: delivery data is kept indefinitely.
- Regression proof: a Maven profile `dedup-off` runs the whole suite with `nms.dedup.enabled=false`. Both `mvn verify` and `mvn verify -Pdedup-off` must pass. Dedup tests set the flag they need themselves, so they pass in both runs.

### E7. Audit and metrics
- `DELIVERY_SUPPRESSED` is written in the intake transaction, with reason `DUPLICATE_EVENT` and details `originalDeliveryId`, `originalNotificationId`, `recipientId`, `channel`, and the masked address. A suppressed delivery gets no `DELIVERY_QUEUED`. `ROUTING_DECIDED` is unchanged: it still lists the channel as selected by routing.
- New counter `nms.deliveries.suppressed{channel, sourceSystem}`.

### E8. Status response
`DeliveryView` gains `suppressedBy: {notificationId, deliveryId}`, `null` for deliveries that aren't suppressed. A suppressed delivery shows `attemptCount` 0, `nextAttemptAt` null, and `completedAt` = its creation time. The original always belongs to the same source system, because the key includes it.

## Safe Change Plan

1. Characterization tests that pin the current status derivation, transitions, and intake behavior, including a test showing that today the same event with a new key is delivered twice.
2. Land V3 alone and run the full suite (no behavior change).
3. State model and deriver changes with unit tests. Any change to characterization tests must be a reviewed, intentional diff.
4. Dedup step behind the flag, with integration tests with the flag on, then the full suite with the flag off.
5. Status API, docs, demo script.

## Risks / Trade-offs

- [Advisory locks serialize same-event submissions] → only submissions with the same key wait on each other; unrelated traffic is unaffected. Locks are held only for the intake transaction.
- [Hash collisions] → with 64-bit keys they're rare; a collision only briefly serializes two unrelated submissions.
- [Clients switching exhaustively on status] → must handle `SUPPRESSED`; documented as an additive contract change.
- [Intentional re-sends of the same event (for example reminders)] → must use a new event ID; documented.
- [Code rollback after suppressed rows exist: the old worker can stall on partly suppressed notifications, and old replays of fully suppressed notifications return `500` (unknown enum value)] → switch off with the flag instead of rolling back; see Migration Plan.
- [Lookup cost as delivery data grows (never purged)] → bounded by the `(source_system, event_id)` index and the window condition.

## Migration Plan

Deploy = the application with V3 (applied by Flyway on startup).

To turn the behavior off, restart with `nms.dedup.enabled=false`; no code rollback is needed. This is the preferred way back.

Rolling back the code is safe for the **schema** (the old code never writes `SUPPRESSED` and ignores `suppressed_by`). It is **not** safe for the **data** once suppressed rows exist, because the old code converts stored status text to enums with `valueOf`, which throws on `SUPPRESSED`:
- `NotificationStatusUpdater` reads every delivery status of a notification when recomputing its overall status. For a partly suppressed notification, the old worker's claim or outcome transaction throws and rolls back the whole batch, including other notifications' deliveries; the same rows are picked again at the next poll, so delivery can stall.
- `NotificationRepository.findByIdempotencyKey` reads the notification status for idempotent replays, so replaying a fully suppressed notification returns `500` instead of `200`.
- Not affected: the status API (it returns the status as text, so `GET` shows `"SUPPRESSED"`), and the claim query (it never selects `SUPPRESSED` rows).

Roll back the code only before any suppression has happened. This change deliberately provides no data-rollback runbook: removing suppressed deliveries would require deleting or rewriting their `DELIVERY_SUPPRESSED` audit rows (foreign key from `audit_event.delivery_id`), which the append-only audit requirement forbids, and relabeling them would falsify history. The flag is the supported way back; the README states this rule.
