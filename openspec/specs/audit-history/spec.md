# audit-history Specification

## Purpose
Keeps a tamper-resistant, append-only record of every significant notification lifecycle action, readable by the owning source system and free of sensitive content.

## Requirements

### Requirement: Record significant actions
The system SHALL append an audit event for each of: `NOTIFICATION_ACCEPTED`, `NOTIFICATION_REJECTED`, `DUPLICATE_SUBMISSION`, `ROUTING_DECIDED`, `DELIVERY_QUEUED`, `DELIVERY_SUPPRESSED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED`, `DELIVERY_FAILED`, `RETRY_SCHEDULED`, `DELIVERY_EXPIRED`. Each event SHALL include event type, timestamp, notification ID (absent only for rejected requests that never produced a notification), delivery ID where applicable, reason code, and non-sensitive details.
`DELIVERY_SUPPRESSED` SHALL carry reason `DUPLICATE_EVENT` and, in its details, the original delivery ID and original notification ID, the recipient ID, the channel, and the masked address. A suppressed delivery SHALL NOT get a `DELIVERY_QUEUED` event.
Audit events SHALL be written atomically with the state change they describe, and SHALL be append-only; the application SHALL never update or delete them.

#### Scenario: Full lifecycle is auditable
- **WHEN** a notification is accepted, routed, fails once transiently, then succeeds
- **THEN** its audit history contains, in order: `NOTIFICATION_ACCEPTED`, `ROUTING_DECIDED`, `DELIVERY_QUEUED`, `DELIVERY_ATTEMPTED`, `RETRY_SCHEDULED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED`

#### Scenario: Suppression is auditable
- **WHEN** a delivery is suppressed as a duplicate event
- **THEN** its audit history contains `NOTIFICATION_ACCEPTED`, `ROUTING_DECIDED`, `DELIVERY_SUPPRESSED` with the original delivery ID, and no `DELIVERY_QUEUED` for that delivery

### Requirement: Retrieve audit history
The system SHALL expose `GET /api/v1/notifications/{id}/audit` returning the notification's audit events in chronological order, subject to the same ownership rules as the status API.

#### Scenario: Audit for unknown notification
- **WHEN** a client requests audit history for a nonexistent notification
- **THEN** the system responds `404 Not Found`

#### Scenario: Audit owned by another source system
- **WHEN** a client authenticated as `billing` requests audit history for a notification submitted by `trading`
- **THEN** the system responds `404 Not Found`

### Requirement: Protect sensitive data
Audit events and logs SHALL NOT contain message subject or body, full contact addresses, API keys, or provider credentials, including the webhook signing secret and request signatures. Contact addresses SHALL be masked (for example `j***@example.com`, `***-***-1234`); a webhook URL SHALL be masked to its scheme and host followed by `/***` (for example `https://hooks.example.com/***`), so that its user info, port, path, and query are never shown. The same masking SHALL apply wherever an address is returned by the API. The system MAY store a SHA-256 hash of the message body for traceability.

#### Scenario: Message content excluded
- **WHEN** a notification with body "Your balance is $12,345" is processed
- **THEN** no audit event or log line contains that text

#### Scenario: Address masked
- **WHEN** a delivery to `jane.doe@example.com` is audited
- **THEN** the audit event shows `j***@example.com`

#### Scenario: Webhook URL masked
- **WHEN** a `WEBHOOK` delivery to `https://user:pw@hooks.example.com:8443/notify?token=abc` is audited and its status is requested
- **THEN** the audit event and the status response show `https://hooks.example.com/***`

#### Scenario: Signing secret never logged
- **WHEN** a `WEBHOOK` delivery is sent with webhooks enabled
- **THEN** no audit event or log line contains the signing secret or the `X-NMS-Signature` value
