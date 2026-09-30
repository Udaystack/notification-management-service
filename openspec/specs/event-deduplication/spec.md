# event-deduplication Specification

## Purpose
Prevents a recipient from receiving the same business event twice on the same channel when a source system re-emits it with a new idempotency key, while keeping every suppressed request visible in status and audit.

## Requirements

### Requirement: Event-level deduplication
When `nms.dedup.enabled` is true, the system SHALL suppress a new delivery if another delivery for the same `(sourceSystem, eventId, recipientId, channel)` was created within the configured dedup window (default 24 hours) and that delivery is not `FAILED`, `EXPIRED`, or `SUPPRESSED`. A suppressed delivery SHALL be stored with status `SUPPRESSED` and a reference to the original delivery, and SHALL never be sent. The dedup boundary is the source system: identical event IDs from different source systems are independent.
A submission is still accepted with `202 Accepted` when some or all of its deliveries are suppressed; when every routed delivery is suppressed, the response and the notification carry overall status `SUPPRESSED`. `422 Unprocessable Content` remains reserved for requests where routing finds no eligible channel for any recipient.

#### Scenario: Same event resubmitted with a new key
- **WHEN** a source system submits the same event ID for the same recipient and channel with a new idempotency key within 24 hours
- **THEN** a new notification is created whose matching delivery is `SUPPRESSED` and references the original delivery
- **AND** a `DELIVERY_SUPPRESSED` audit event is recorded with reason `DUPLICATE_EVENT`

#### Scenario: Partial overlap
- **WHEN** the original notification went to recipient A by `EMAIL` and the resubmission targets A and B by `EMAIL`
- **THEN** A's delivery is `SUPPRESSED` and B's delivery is created normally

#### Scenario: Different channel is not a duplicate
- **WHEN** the resubmission routes recipient A to `SMS` and the original went only by `EMAIL`
- **THEN** the `SMS` delivery is created normally

#### Scenario: Outside the window
- **WHEN** the same event is submitted 25 hours after the original
- **THEN** the delivery is not suppressed

#### Scenario: Original delivery failed
- **WHEN** the same event is resubmitted within 24 hours and the original delivery is `FAILED`
- **THEN** the new delivery is not suppressed, allowing a source system to re-send after a failure

#### Scenario: Different source systems
- **WHEN** `billing` and `trading` both submit event ID `evt-1` for the same recipient and channel
- **THEN** neither delivery is suppressed

#### Scenario: Concurrent duplicate events
- **WHEN** two submissions of the same event with different idempotency keys arrive concurrently
- **THEN** exactly one of the matching deliveries is created as deliverable and the other is `SUPPRESSED`

#### Scenario: Feature disabled
- **WHEN** `nms.dedup.enabled` is false and the same event is resubmitted with a new key
- **THEN** the delivery is created normally, matching the behavior before this change

### Requirement: Documented dedup window
The dedup window SHALL be configurable via `nms.dedup.window` and SHALL be a positive duration. The window, the dedup boundary, and the exclusions (`FAILED`, `EXPIRED`, and `SUPPRESSED` originals; different source systems; different channels) SHALL be documented in the README, together with the fact that delivery data is kept indefinitely.

#### Scenario: Window configured
- **WHEN** `nms.dedup.window` is set to `PT1H`
- **THEN** a resubmission 2 hours after the original is not suppressed
