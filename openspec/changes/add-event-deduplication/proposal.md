# Proposal

## Why

The service prevents duplicates only when a source system reuses the same `Idempotency-Key`. Upstream systems often re-emit the same business event with a new key (restarts, replays, at-least-once producers), and today each re-emission sends the recipient the same alert again. Avoidable duplicates must be prevented, and suppressed requests must be visible in status and audit.

## What Changes

- New intake step that suppresses a delivery when the same `(sourceSystem, eventId, recipientId, channel)` was already delivered or is in progress within a configurable window (default 24h). Suppressed deliveries are stored but never sent.
- **BREAKING (API contract, additive):** new delivery status `SUPPRESSED`, new overall notification status `SUPPRESSED`, and new field `suppressedBy` in the status response. Clients that switch exhaustively on status values must handle the new value.
- Overall status derivation ignores suppressed deliveries; a notification whose deliveries are all suppressed has overall status `SUPPRESSED` and is still accepted with `202`.
- New audit event `DELIVERY_SUPPRESSED` (reason `DUPLICATE_EVENT`, with the original delivery).
- Flyway V3 migration on existing tables: nullable `suppressed_by` column, widened status constraints, dedup indexes. No backfill.
- Feature flag `nms.dedup.enabled` (default `true`); with it off, behavior is identical to the previous release.
- New counter `nms.deliveries.suppressed`.

## Capabilities

### New Capabilities
- `event-deduplication`: detect and suppress duplicate event deliveries per recipient and channel within a configurable window.

### Modified Capabilities
- `notification-status`: state model, derived status, and status response include suppression (`SUPPRESSED`, `suppressedBy`).
- `audit-history`: new `DELIVERY_SUPPRESSED` event.

## Impact

- Layers touched: database schema, domain state model, intake pipeline, status API, audit, configuration, metrics, README, demo script.
- Existing data: no backfill; existing deliveries are never `SUPPRESSED`. Rolling back the code leaves the schema compatible.
- Existing behavior is preserved with `nms.dedup.enabled=false`, proven by running the full existing suite in that mode.
- Builds on the archived `add-notification-core` main specs.

### Out of Scope
- Content-based deduplication (same text, different event ID).
- Deduplication across source systems.
- Retroactive suppression of deliveries already queued.
- A delivery data retention/purge policy (delivery data is kept indefinitely).
