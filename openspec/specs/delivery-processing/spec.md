# delivery-processing Specification

## Purpose
Delivers queued notifications asynchronously through channel providers, classifying failures and retrying only what is retryable, within bounded attempts and before expiry.

## Requirements

### Requirement: Asynchronous delivery
The system SHALL run background workers that claim due deliveries (status `PENDING` or `RETRY_SCHEDULED` with `nextAttemptAt` ≤ now, or `IN_FLIGHT` with an expired lease), ordered by notification priority then `nextAttemptAt`, mark them `IN_FLIGHT`, call the channel provider without holding a database transaction open, and record the outcome.
Worker concurrency, batch size, poll interval, and lease duration SHALL be configurable.

#### Scenario: Successful delivery
- **WHEN** a due `EMAIL` delivery is processed and the provider accepts it
- **THEN** the delivery becomes `SENT` with `completedAt` set and `DELIVERY_ATTEMPTED` and `DELIVERY_SUCCEEDED` audit events are recorded

#### Scenario: Priority ordering
- **WHEN** a `HIGH` priority and a `LOW` priority delivery are both due
- **THEN** the `HIGH` priority delivery is claimed first

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
