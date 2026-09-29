# Spec Delta

## Purpose

Keeps a tamper-resistant, append-only record of every significant notification lifecycle action, readable by the owning source system and free of sensitive content.

## ADDED Requirements

### Requirement: Record significant actions
The system SHALL append an audit event for each of: `NOTIFICATION_ACCEPTED`, `NOTIFICATION_REJECTED`, `DUPLICATE_SUBMISSION`, `ROUTING_DECIDED`, `DELIVERY_QUEUED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED`, `DELIVERY_FAILED`, `RETRY_SCHEDULED`, `DELIVERY_EXPIRED`. Each event SHALL include event type, timestamp, notification ID (absent only for rejected requests that never produced a notification), delivery ID where applicable, reason code, and non-sensitive details.
Audit events SHALL be written atomically with the state change they describe, and SHALL be append-only; the application SHALL never update or delete them.

#### Scenario: Full lifecycle is auditable
- **WHEN** a notification is accepted, routed, fails once transiently, then succeeds
- **THEN** its audit history contains, in order: `NOTIFICATION_ACCEPTED`, `ROUTING_DECIDED`, `DELIVERY_QUEUED`, `DELIVERY_ATTEMPTED`, `RETRY_SCHEDULED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED`

### Requirement: Retrieve audit history
The system SHALL expose `GET /api/v1/notifications/{id}/audit` returning the notification's audit events in chronological order, subject to the same ownership rules as the status API.

#### Scenario: Audit for unknown notification
- **WHEN** a client requests audit history for a nonexistent notification
- **THEN** the system responds `404 Not Found`

#### Scenario: Audit owned by another source system
- **WHEN** a client authenticated as `billing` requests audit history for a notification submitted by `trading`
- **THEN** the system responds `404 Not Found`

### Requirement: Protect sensitive data
Audit events and logs SHALL NOT contain message subject or body, full contact addresses, API keys, or provider credentials. Contact addresses SHALL be masked (for example `j***@example.com`, `***-***-1234`). The system MAY store a SHA-256 hash of the message body for traceability.

#### Scenario: Message content excluded
- **WHEN** a notification with body "Your balance is $12,345" is processed
- **THEN** no audit event or log line contains that text

#### Scenario: Address masked
- **WHEN** a delivery to `jane.doe@example.com` is audited
- **THEN** the audit event shows `j***@example.com`
