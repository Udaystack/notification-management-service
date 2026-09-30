# notification-submission Specification

## Purpose
Lets authenticated source systems submit notification requests that are validated, persisted, and queued for asynchronous delivery, optionally at a scheduled time.

## Requirements

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

### Requirement: Validate notification requests
The system SHALL reject invalid requests with `400 Bad Request` and an RFC 9457 Problem Details body listing each invalid field. Nothing SHALL be persisted for a rejected request except a `NOTIFICATION_REJECTED` audit event that contains no message content.

#### Scenario: Missing recipients
- **WHEN** a request has an empty recipient list
- **THEN** the system responds `400` identifying the `recipients` field

#### Scenario: Unknown enum value
- **WHEN** a request has severity `URGENT`
- **THEN** the system responds `400` identifying the `severity` field and the allowed values

#### Scenario: Expiry before schedule
- **WHEN** `expiresAt` is earlier than or equal to `scheduledAt`
- **THEN** the system responds `400` explaining the timestamp conflict

#### Scenario: Already expired
- **WHEN** `expiresAt` is in the past at submission time
- **THEN** the system responds `400` explaining that the notification has already expired

#### Scenario: Missing idempotency key
- **WHEN** a request has no `Idempotency-Key` header
- **THEN** the system responds `400` identifying the missing header

#### Scenario: Recipient limit exceeded
- **WHEN** a request contains more recipients than the configured maximum (default 100)
- **THEN** the system responds `400` stating the limit

#### Scenario: Rejection leaves only an audit trace
- **WHEN** a request is rejected with `400`
- **THEN** no notification or delivery is persisted
- **AND** a `NOTIFICATION_REJECTED` audit event is recorded with a reason code and without subject or body

### Requirement: Authenticate source systems
The system SHALL require an API key on every API request (`/api/**`) via the `X-API-Key` header; the health endpoint and OpenAPI documentation SHALL be reachable without a key. The system SHALL map each key to exactly one source system, and reject requests whose body `sourceSystem` does not match the authenticated source system. API keys SHALL be stored hashed and SHALL never be logged or written to audit history.

#### Scenario: Missing or invalid key
- **WHEN** a request has no API key or an unknown API key
- **THEN** the system responds `401 Unauthorized` and processes nothing

#### Scenario: Source system mismatch
- **WHEN** a request authenticated as `billing` declares `sourceSystem` = `trading`
- **THEN** the system responds `403 Forbidden`

### Requirement: Scheduled notifications
The system SHALL hold a notification with a future `scheduledAt` and SHALL NOT attempt delivery before that time.

#### Scenario: Future schedule
- **WHEN** a notification is accepted with `scheduledAt` one hour in the future
- **THEN** its deliveries have status `PENDING` and `nextAttemptAt` equal to `scheduledAt`
- **AND** no delivery attempt occurs before `scheduledAt`
