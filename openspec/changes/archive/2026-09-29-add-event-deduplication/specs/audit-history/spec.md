# Spec Delta

## MODIFIED Requirements

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
