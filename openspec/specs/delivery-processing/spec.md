# delivery-processing Specification

## Purpose
Delivers queued notifications asynchronously through channel providers, classifying failures and retrying only what is retryable, within bounded attempts and before expiry.

## Requirements

### Requirement: Asynchronous delivery
The system SHALL run background workers that claim due deliveries (status `PENDING` or `RETRY_SCHEDULED` with `nextAttemptAt` ≤ now, or `IN_FLIGHT` with an expired lease), mark them `IN_FLIGHT`, call the channel provider without holding a database transaction open, and record the outcome.
Worker concurrency, batch size, poll interval, lease duration, and the priority aging interval SHALL be configurable.

Claims SHALL be ordered by effective rank (highest first), then by due time (oldest first):
- The due time is `nextAttemptAt` for a `PENDING` or `RETRY_SCHEDULED` delivery (so a retry's backoff never counts as waiting), and the lease expiry for a reclaimed `IN_FLIGHT` delivery.
- The effective rank is the notification's priority rank (`LOW` 0, `NORMAL` 1, `HIGH` 2) plus one for every full `nms.worker.priority-aging` interval (default 5 minutes) elapsed since the due time, capped at 2. With an interval of `0` the effective rank equals the priority rank. A negative interval SHALL fail startup.
- The stored priority SHALL never change; aging affects claim order only.
- Retries and first attempts SHALL be ordered by the same rule; neither is preferred.

Priority SHALL only order claims: the system gives no latency guarantee for any priority.

#### Scenario: Successful delivery
- **WHEN** a due `EMAIL` delivery is processed and the provider accepts it
- **THEN** the delivery becomes `SENT` with `completedAt` set and `DELIVERY_ATTEMPTED` and `DELIVERY_SUCCEEDED` audit events are recorded

#### Scenario: Priority ordering
- **WHEN** a `HIGH` priority and a `LOW` priority delivery are both due
- **THEN** the `HIGH` priority delivery is claimed first

#### Scenario: Waiting delivery gains priority
- **WHEN** the aging interval is 5 minutes, a `LOW` delivery has been due for 10 minutes, and a `HIGH` delivery became due 1 minute ago
- **THEN** the `LOW` delivery is claimed first, and its stored priority is still `LOW`

#### Scenario: Aging disabled
- **WHEN** the aging interval is `0`, a `LOW` delivery has been due for an hour, and a `HIGH` delivery became due 1 minute ago
- **THEN** the `HIGH` delivery is claimed first

#### Scenario: Retry competes with new work
- **WHEN** a `HIGH` retry and a new `NORMAL` delivery are both due, and separately a `NORMAL` retry due 2 minutes ago and a new `NORMAL` delivery due 1 minute ago are both due
- **THEN** the `HIGH` retry is claimed before the new `NORMAL` delivery, and the `NORMAL` retry before the newer `NORMAL` delivery

#### Scenario: Backoff does not count as waiting
- **WHEN** the aging interval is 5 minutes and a `LOW` retry whose previous attempt failed 20 minutes ago became due 1 minute ago
- **THEN** it competes with effective rank `LOW`

#### Scenario: Reclaimed lease ordered by lease expiry
- **WHEN** a `NORMAL` delivery's lease expired 2 minutes ago and another `NORMAL` delivery became due 1 minute ago
- **THEN** the reclaimed delivery is claimed first

#### Scenario: Negative aging interval rejected
- **WHEN** the service starts with `nms.worker.priority-aging=-1m`
- **THEN** startup fails with a message naming `nms.worker.priority-aging`

### Requirement: Classify delivery failures
Each channel provider SHALL map provider responses to one of these failure classes: `TRANSIENT`, `TIMEOUT`, `RATE_LIMITED`, `PERMANENT_REJECTION`, `INVALID_RECIPIENT`, `AUTH_ERROR`. Unrecognized errors SHALL be classified as `TRANSIENT`.

#### Scenario: Unexpected exception
- **WHEN** a provider throws an unexpected exception
- **THEN** the failure is classified as `TRANSIENT`

### Requirement: Bounded retry
The system SHALL retry only `TRANSIENT`, `TIMEOUT`, and `RATE_LIMITED` failures, using exponential backoff with full jitter (default base 2s, max delay 5m, max 5 attempts). For `RATE_LIMITED`, the next attempt SHALL be no earlier than the provider's retry-after value when supplied. `PERMANENT_REJECTION`, `INVALID_RECIPIENT`, and `AUTH_ERROR` SHALL fail the delivery immediately. `AUTH_ERROR` SHALL additionally emit an error log and metric.

#### Scenario: Transient failure then success
- **WHEN** the first attempt fails with `TRANSIENT` and the second succeeds
- **THEN** the delivery goes `IN_FLIGHT` → `RETRY_SCHEDULED` → `IN_FLIGHT` → `SENT` with `attemptCount` = 2 and a `RETRY_SCHEDULED` audit event

#### Scenario: Retries exhausted
- **WHEN** a delivery fails with `TIMEOUT` on all 5 attempts
- **THEN** the delivery becomes `FAILED` with reason `RETRIES_EXHAUSTED`

#### Scenario: Permanent failure is not retried
- **WHEN** a provider returns `INVALID_RECIPIENT`
- **THEN** the delivery becomes `FAILED` after 1 attempt and no retry is scheduled

#### Scenario: Rate limit honored
- **WHEN** a provider returns `RATE_LIMITED` with retry-after 30 seconds
- **THEN** `nextAttemptAt` is at least 30 seconds after the failed attempt

### Requirement: Expiration
The system SHALL NOT attempt a delivery after the notification's `expiresAt`, and SHALL NOT schedule a retry whose `nextAttemptAt` is after `expiresAt`.

#### Scenario: Expires while waiting to retry
- **WHEN** the next retry would occur after `expiresAt`
- **THEN** the delivery becomes `EXPIRED` and a `DELIVERY_EXPIRED` audit event is recorded

#### Scenario: Claimed after expiry
- **WHEN** a worker claims a due delivery whose notification has passed `expiresAt`
- **THEN** no provider call is made and the delivery becomes `EXPIRED`

### Requirement: Simulated channel providers
The system SHALL provide simulated `EMAIL`, `SMS`, and `PUSH` providers. Simulated providers SHALL support deterministic failure injection based on the recipient address so that every failure class can be demonstrated and tested. Markers: `+transient`, `+timeout`, `+ratelimit`, `+reject`, `+invalid`, `+auth`, and `+flaky` (fails transiently on the first attempt only).

#### Scenario: Injected failure
- **WHEN** an email delivery targets `user+invalid@example.com`
- **THEN** the simulated provider returns `INVALID_RECIPIENT`

### Requirement: Priority observability
The system SHALL record a timer `nms.deliveries.queue-wait`, tagged with `priority` and `channel`, measuring the time from a delivery's due time to its first claim. It SHALL be recorded once per delivery, on attempt 1 only.
The counter `nms.deliveries.expired` SHALL be tagged with `channel`, `priority`, and `neverAttempted` (`true` when the delivery expired before any attempt). A delivery that expires before any attempt SHALL additionally produce a WARN log with its notification ID, delivery ID, and priority, and no message content or address.

#### Scenario: Queue wait measured
- **WHEN** a `NORMAL` `EMAIL` delivery due at 10:05:00 is first claimed at 10:05:03
- **THEN** `nms.deliveries.queue-wait{priority=NORMAL, channel=EMAIL}` records 3 seconds, and later attempts of that delivery record nothing

#### Scenario: Never-attempted expiry flagged
- **WHEN** a `LOW` delivery is claimed after its notification's `expiresAt` and has never been attempted
- **THEN** `nms.deliveries.expired` is incremented with `priority=LOW` and `neverAttempted=true`, and a WARN log names its notification ID, delivery ID, and priority

#### Scenario: Expiry after an attempt
- **WHEN** a delivery expires while waiting to retry
- **THEN** `nms.deliveries.expired` is incremented with `neverAttempted=false` and no WARN is logged for it
