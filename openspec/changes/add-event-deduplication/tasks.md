# Tasks

> Paused 2026-09-29 at 11/25 (Docker Desktop stopped responding). 4.3–4.5 are written but unverified. Resume from [HANDOFF.md](HANDOFF.md).

## 1. Safety net

- [x] 1.1 Add characterization unit tests pinning every current `NotificationStatusDeriver` output (`ACCEPTED`, `IN_PROGRESS`, `COMPLETED`, `PARTIALLY_DELIVERED`, `FAILED`, `EXPIRED`) and the full current transition table of `DeliveryStateMachine`; verify they pass on the unchanged code
- [x] 1.2 Add an integration test showing that today the same `eventId` submitted with a new `Idempotency-Key` for the same recipient and channel creates two deliverable deliveries (documents the bug being fixed); verify it passes on the unchanged code
- [x] 1.3 Run `mvn verify` and confirm the full suite is green before any change

## 2. Schema

- [x] 2.1 Flyway V3: nullable `delivery.suppressed_by` referencing `delivery(id)`, `delivery_status_check` and `notification_status_check` widened with `SUPPRESSED`, indexes `ix_notification_src_event` and `ix_delivery_dedup`; extend `MigrationIT` to assert V3 applied, the column, both constraints accept `SUPPRESSED`, and both indexes exist
- [x] 2.2 Run `mvn verify` against the migrated schema and confirm no behavior change (full suite green)

## 3. Domain

- [x] 3.1 Add `SUPPRESSED` to `DeliveryStatus` (terminal) and `NotificationStatus`; `DeliveryStateMachine` allows no transition into or out of `SUPPRESSED`; unit test for the "Invalid transition rejected" scenario covering `SUPPRESSED` to every state
- [x] 3.2 Update `NotificationStatusDeriver` to ignore suppressed deliveries and return `SUPPRESSED` when all are suppressed; unit tests named after "Suppressed deliveries ignored" and "Fully suppressed notification"
- [x] 3.3 Re-run the characterization tests from 1.1 and confirm there are no diffs (suppression-free inputs must derive exactly as before); any diff must be reviewed and intentional
- [x] 3.4 Update the README state model (delivery states, `SUPPRESSED` as creation-only terminal state, derivation rules) and verify it matches the unit tests

## 4. Dedup step

- [x] 4.1 Add `nms.dedup.enabled` (default `true`) and `nms.dedup.window` (default `24h`, must be positive) to `NmsProperties` and `application.yml`; verify startup fails with a clear message for a zero or negative window (unit or context test)
- [x] 4.2 `EventDedupRepository` with the E3 lookup (application clock, window, `FAILED`/`EXPIRED`/`SUPPRESSED` excluded, oldest first); integration test against seeded rows covering match, other channel, other source system, excluded statuses, and outside window
- [x] 4.3 `EventDedupStep` (@25, after routing, before delivery creation): when enabled, takes `pg_advisory_xact_lock(hashtextextended(key, 0))` for each selected pair in sorted key order, looks up the original, and records `(originalNotificationId, originalDeliveryId)` for duplicates; does nothing when disabled
- [x] 4.4 `CreateDeliveriesStep` inserts duplicate pairs as `SUPPRESSED` (`suppressed_by`, `completed_at` = now, `next_attempt_at` NULL, `attempt_count` 0) and all others as `PENDING`; the `422` rule is unchanged; intake recomputes the notification status in the same transaction and returns it in the `202` body (`SUPPRESSED` when every delivery is suppressed)
- [x] 4.5 `AuditIntakeStep` writes `DELIVERY_SUPPRESSED` (reason `DUPLICATE_EVENT`; details `originalDeliveryId`, `originalNotificationId`, `recipientId`, `channel`, masked address) and no `DELIVERY_QUEUED` for suppressed deliveries; increment `nms.deliveries.suppressed{channel, sourceSystem}`; integration test named after "Suppression is auditable" plus a metric assertion
- [x] 4.6 Integration tests (flag on) named after every event-deduplication scenario: same event with a new key, partial overlap, different channel, outside the window (25h, by backdating `created_at`), original delivery failed, different source systems, concurrent duplicate events (parallel submissions with different keys: exactly one deliverable, the rest `SUPPRESSED`), feature disabled, and window configured (`PT1H`, resubmission 2h later); verify all pass
- [x] 4.7 Change the test from 1.2 to assert suppression when the flag is on, and keep a flag-off variant asserting the old behavior; verify both pass
- [x] 4.8 README: event deduplication section covering the boundary (source system), key, window and `nms.dedup.window`, exclusions, `202` with `SUPPRESSED`, data kept indefinitely, the `nms.dedup.enabled` flag, the additive contract change, "use a new event ID for intentional re-sends", and the rollback rule ("To disable deduplication, restart with `nms.dedup.enabled=false`. Do not roll back to a version without deduplication support once any delivery has been suppressed; see design, Migration Plan."); replace the "Known limitation: no event-level deduplication" note

## 5. Status API

- [x] 5.1 Add `suppressedBy` (`{notificationId, deliveryId}`, `null` otherwise) to `NotificationView.DeliveryView` and its query; confirm the OpenAPI document at `/v3/api-docs` shows the field
- [x] 5.2 API tests named after "Suppressed delivery shows its origin" (field present, same source system, `attemptCount` 0, `nextAttemptAt` null, `completedAt` set) and a fully suppressed notification returning overall status `SUPPRESSED` from both `POST` (`202`) and `GET`; verify they pass
- [x] 5.3 Update the README status and API sections (`SUPPRESSED` statuses, `suppressedBy`) and verify they match the responses from 5.2

## 6. Tooling

- [x] 6.1 Add a Maven profile `dedup-off` that runs the Surefire and Failsafe tests with `nms.dedup.enabled=false`; document `mvn verify -Pdedup-off` in the README Tests section; verify the profile actually sets the property (a test or log check)
- [x] 6.2 Extend `scripts/demo.sh` to resubmit the same event with a new key and show `SUPPRESSED` and `suppressedBy` in status plus `DELIVERY_SUPPRESSED` in audit; verify it runs end-to-end against a local instance

## 7. Integration checks

- [x] 7.1 Run `mvn verify` (flag on) and `mvn verify -Pdedup-off` (flag off); both must be green
- [x] 7.2 Regenerate `docs/scenario-coverage.md` so it covers the new and modified scenarios (main specs plus this change's deltas) and confirm every scenario maps to a test named after it
- [x] 7.3 Run `openspec validate add-event-deduplication --strict` and verify it passes
