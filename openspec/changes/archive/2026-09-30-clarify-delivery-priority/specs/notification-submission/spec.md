# Spec Delta

## MODIFIED Requirements

### Requirement: Submit a notification
The system SHALL expose `POST /api/v1/notifications` that accepts a notification request and returns `202 Accepted` with the notification ID, current status, and a status URL.
A request SHALL contain: source system, event/correlation ID, notification type (`TRANSACTIONAL`, `ALERT`, `SECURITY`, `MARKETING`), severity (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), priority (`LOW`, `NORMAL`, `HIGH`), one or more recipients, zero or more requested channels, message subject and body, and optional `scheduledAt` and `expiresAt` timestamps. The system SHALL assign the notification ID and the creation timestamp.
Priority is chosen by the submitting source system and SHALL be required: there is no default, so every source system decides it explicitly.

#### Scenario: Valid notification is accepted
- **WHEN** an authenticated source system submits a valid request with an `Idempotency-Key`
- **THEN** the system responds `202 Accepted` with a new notification ID, status `ACCEPTED`, and a `Location` header pointing to the status resource
- **AND** the notification, its routed deliveries, and audit events are persisted atomically (all or nothing)

#### Scenario: Processing is asynchronous
- **WHEN** a notification is accepted
- **THEN** the response is returned without waiting for any channel provider call

#### Scenario: Priority is required
- **WHEN** a request omits `priority`
- **THEN** the system responds `400` identifying the `priority` field, and no notification is created
