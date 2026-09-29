# notification-status Specification

## Purpose
Gives source systems a read view of a notification's overall status and each recipient/channel delivery, backed by a documented, enforced state model.

## Requirements

### Requirement: Retrieve notification status
The system SHALL expose `GET /api/v1/notifications/{id}` returning the overall status, selected channels, one entry per delivery (recipient + channel) with its status, attempt count, last failure class, and the timestamps `createdAt`, `scheduledAt`, `expiresAt`, `lastAttemptAt`, `nextAttemptAt`, and `completedAt` where applicable.
Recipient contact addresses SHALL be masked in the response.

#### Scenario: Status of an in-progress notification
- **WHEN** a client requests a notification that has one delivery `SENT` and one `RETRY_SCHEDULED`
- **THEN** the response shows overall status `IN_PROGRESS` and both deliveries with their statuses and timestamps

#### Scenario: Addresses masked
- **WHEN** a client requests a notification with a delivery to `jane.doe@example.com`
- **THEN** the response shows the address as `j***@example.com`

#### Scenario: Unknown notification
- **WHEN** a client requests a notification ID that does not exist
- **THEN** the system responds `404 Not Found`

#### Scenario: Notification owned by another source system
- **WHEN** a client authenticated as `billing` requests a notification submitted by `trading`
- **THEN** the system responds `404 Not Found` without revealing that the notification exists

### Requirement: Documented state model
The system SHALL use the following delivery states: `PENDING`, `IN_FLIGHT`, `SENT`, `RETRY_SCHEDULED`, `FAILED`, `EXPIRED`. `SENT`, `FAILED`, and `EXPIRED` are terminal. The only allowed delivery transitions SHALL be:
- `PENDING` → `IN_FLIGHT` | `EXPIRED`
- `IN_FLIGHT` → `SENT` | `RETRY_SCHEDULED` | `FAILED` | `EXPIRED` | `IN_FLIGHT` (lease-expiry reclaim)
- `RETRY_SCHEDULED` → `IN_FLIGHT` | `EXPIRED`

The overall notification status SHALL be derived from its deliveries:
- `ACCEPTED`: routed, no delivery attempted yet
- `IN_PROGRESS`: at least one delivery is non-terminal and at least one attempt has started
- `COMPLETED`: all deliveries are `SENT`
- `PARTIALLY_DELIVERED`: all deliveries terminal, at least one `SENT` and at least one `FAILED` or `EXPIRED`
- `FAILED`: all deliveries terminal, none `SENT`, and at least one `FAILED`
- `EXPIRED`: all deliveries terminal, none `SENT`, and none `FAILED`, with at least one `EXPIRED`

#### Scenario: Derived partial delivery
- **WHEN** a notification has one delivery `SENT` and one `FAILED`, both terminal
- **THEN** the overall status is `PARTIALLY_DELIVERED`

#### Scenario: Invalid transition rejected
- **WHEN** a delivery in `SENT` is asked to move to `IN_FLIGHT`
- **THEN** the transition is rejected and the stored state is unchanged
