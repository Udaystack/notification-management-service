# Spec Delta

## Purpose

Guarantees that retried submissions never create duplicate notifications and that reprocessing queued deliveries never causes a provider to send twice.

## ADDED Requirements

### Requirement: Idempotent submission
The system SHALL treat `(sourceSystem, Idempotency-Key)` as the idempotency boundary. It SHALL store a SHA-256 hash of the canonical request body with the notification. Idempotency records SHALL be retained for a configurable period (default 7 days) and then cleared from the notification by a scheduled cleanup job, after which the same key is treated as new. The notification itself is kept.

#### Scenario: Same key, same payload
- **WHEN** a source system repeats a submission with the same idempotency key and identical body
- **THEN** the system responds `200 OK` with the original notification ID and current status
- **AND** no new notification or delivery is created
- **AND** a `DUPLICATE_SUBMISSION` audit event is recorded on the original notification

#### Scenario: Same key, different payload
- **WHEN** a source system reuses an idempotency key with a different body
- **THEN** the system responds `409 Conflict` and creates nothing

#### Scenario: Concurrent duplicate submissions
- **WHEN** two identical submissions with the same idempotency key arrive concurrently
- **THEN** exactly one notification is created and the other request receives the idempotent `200` response

#### Scenario: Same key from different source systems
- **WHEN** `billing` and `trading` each submit with idempotency key `abc-123`
- **THEN** two independent notifications are created

#### Scenario: Key reusable after retention
- **WHEN** a source system submits with a key whose idempotency record was cleared after the retention period
- **THEN** the submission is treated as new and a new notification is created
- **AND** the original notification remains readable

### Requirement: Safe reprocessing
Delivery processing SHALL be at-least-once with idempotent side effects. The system SHALL guarantee at most one delivery per `(notificationId, recipientId, channel)`, claim deliveries with a time-bounded lease, and pass the delivery ID to providers as the provider idempotency key.

#### Scenario: Worker crashes mid-delivery
- **WHEN** a worker claims a delivery and stops before recording the outcome
- **THEN** after the lease expires another worker may reclaim it
- **AND** the provider call uses the same provider idempotency key, so the provider does not send twice

#### Scenario: Two workers race for one delivery
- **WHEN** two workers poll at the same time
- **THEN** each delivery is claimed by at most one worker
