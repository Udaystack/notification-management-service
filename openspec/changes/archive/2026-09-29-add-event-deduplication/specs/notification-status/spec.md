# Spec Delta

## MODIFIED Requirements

### Requirement: Retrieve notification status
The system SHALL expose `GET /api/v1/notifications/{id}` returning the overall status, selected channels, one entry per delivery (recipient + channel) with its status, attempt count, last failure class, `suppressedBy` (the notification ID and delivery ID of the original delivery; non-null only for `SUPPRESSED` deliveries), and the timestamps `createdAt`, `scheduledAt`, `expiresAt`, `lastAttemptAt`, `nextAttemptAt`, and `completedAt` where applicable.
Recipient contact addresses SHALL be masked in the response.

#### Scenario: Status of an in-progress notification
- **WHEN** a client requests a notification that has one delivery `SENT` and one `RETRY_SCHEDULED`
- **THEN** the response shows overall status `IN_PROGRESS` and both deliveries with their statuses and timestamps

#### Scenario: Addresses masked
- **WHEN** a client requests a notification with a delivery to `jane.doe@example.com`
- **THEN** the response shows the address as `j***@example.com`

#### Scenario: Suppressed delivery shows its origin
- **WHEN** a client requests a notification with a `SUPPRESSED` delivery
- **THEN** that delivery includes `suppressedBy` identifying the original notification and delivery
- **AND** `suppressedBy` only references a notification owned by the same source system

#### Scenario: Unknown notification
- **WHEN** a client requests a notification ID that does not exist
- **THEN** the system responds `404 Not Found`

#### Scenario: Notification owned by another source system
- **WHEN** a client authenticated as `billing` requests a notification submitted by `trading`
- **THEN** the system responds `404 Not Found` without revealing that the notification exists

### Requirement: Documented state model
The system SHALL use the following delivery states: `PENDING`, `IN_FLIGHT`, `SENT`, `RETRY_SCHEDULED`, `FAILED`, `SUPPRESSED`, `EXPIRED`. `SENT`, `FAILED`, `SUPPRESSED`, and `EXPIRED` are terminal. `SUPPRESSED` is only ever assigned when a delivery is created; no transition leads into or out of it. The only allowed delivery transitions SHALL be:
- `PENDING` → `IN_FLIGHT` | `EXPIRED`
- `IN_FLIGHT` → `SENT` | `RETRY_SCHEDULED` | `FAILED` | `EXPIRED` | `IN_FLIGHT` (lease-expiry reclaim)
- `RETRY_SCHEDULED` → `IN_FLIGHT` | `EXPIRED`

The overall notification status SHALL be derived from its deliveries, ignoring `SUPPRESSED` deliveries except where stated:
- `ACCEPTED`: routed, no delivery attempted yet
- `IN_PROGRESS`: at least one delivery is non-terminal and at least one attempt has started
- `COMPLETED`: all non-suppressed deliveries are `SENT`, and at least one exists
- `PARTIALLY_DELIVERED`: all deliveries terminal, at least one `SENT` and at least one `FAILED` or `EXPIRED`
- `FAILED`: all deliveries terminal, none `SENT`, and at least one `FAILED`
- `EXPIRED`: all deliveries terminal, none `SENT`, and none `FAILED`, with at least one `EXPIRED`
- `SUPPRESSED`: every delivery is `SUPPRESSED`

#### Scenario: Derived partial delivery
- **WHEN** a notification has one delivery `SENT` and one `FAILED`, both terminal
- **THEN** the overall status is `PARTIALLY_DELIVERED`

#### Scenario: Suppressed deliveries ignored
- **WHEN** a notification has one delivery `SENT` and one `SUPPRESSED`
- **THEN** the overall status is `COMPLETED`

#### Scenario: Fully suppressed notification
- **WHEN** every delivery of a notification is `SUPPRESSED`
- **THEN** the overall status is `SUPPRESSED`

#### Scenario: Invalid transition rejected
- **WHEN** a delivery in `SENT` is asked to move to `IN_FLIGHT`, or a delivery in `SUPPRESSED` is asked to move to any state
- **THEN** the transition is rejected and the stored state is unchanged
