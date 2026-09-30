# Spec Delta

## Purpose

Delivers notifications to recipients that are systems, by sending a signed HTTP request to the webhook URL stored for the recipient, safely and with the same retry semantics as every other channel.

## ADDED Requirements

### Requirement: Signed webhook request
For a `WEBHOOK` delivery the system SHALL send one HTTP `POST` per attempt to the recipient's `WEBHOOK` address with content type `application/json` and a JSON body containing `notificationId`, `deliveryId`, `eventId`, `sourceSystem`, `type`, `severity`, `priority`, `recipientId`, `subject`, `body`, `attempt`, and `sentAt`.
The request SHALL carry the headers `Idempotency-Key` (the delivery ID, identical on every attempt of the same delivery), `X-NMS-Timestamp` (the send time in Unix seconds), and `X-NMS-Signature` with the value `sha256=` followed by the lowercase hex HMAC-SHA256 of `<X-NMS-Timestamp>.<raw request body>`, keyed with the configured signing secret.
The system SHALL NOT follow redirects, and each call SHALL be bounded by the configured provider timeout. A `2xx` response SHALL mark the delivery `SENT`.

#### Scenario: Signed webhook delivered
- **WHEN** a `WEBHOOK` delivery is processed and the endpoint answers `200`
- **THEN** the endpoint received one `POST` with the JSON fields listed above, `Idempotency-Key` equal to the delivery ID, and an `X-NMS-Signature` that verifies with the signing secret over the timestamp and body
- **AND** the delivery becomes `SENT`

### Requirement: Webhook target policy
The system SHALL call a webhook URL only if it uses `https` and every address its host resolves to is public, meaning not loopback, private, link-local, or multicast. The check SHALL run right before each call. A URL that violates the policy or cannot be parsed SHALL fail the delivery as `INVALID_RECIPIENT` without any request being sent.
When `nms.webhook.allow-private-hosts` is true (default `false`), the system SHALL also allow `http` URLs and non-public addresses, for local development and tests.

#### Scenario: Plain http target rejected
- **WHEN** a recipient's webhook URL is `http://hooks.example.com/notify` and private hosts are not allowed
- **THEN** no request is sent and the delivery becomes `FAILED` with failure class `INVALID_RECIPIENT` after 1 attempt

#### Scenario: Private address target rejected
- **WHEN** a recipient's webhook URL is `https://127.0.0.1/notify` or its host resolves to `10.0.0.5`, and private hosts are not allowed
- **THEN** no request is sent and the delivery becomes `FAILED` with failure class `INVALID_RECIPIENT`

#### Scenario: Private hosts allowed for development
- **WHEN** `nms.webhook.allow-private-hosts` is true and a recipient's webhook URL is `http://localhost:9099/hooks/cust-3001`
- **THEN** the request is sent to that URL

### Requirement: Classify webhook outcomes
The system SHALL map each webhook call outcome to a failure class:
- connection error, unresolvable host, or `5xx` response: `TRANSIENT`
- no response within the provider timeout: `TIMEOUT`
- `429`: `RATE_LIMITED`, with the `Retry-After` header (seconds or HTTP date) as the minimum wait when present
- `401` or `403`: `AUTH_ERROR`
- `404` or `410`: `INVALID_RECIPIENT`
- any other `4xx` and any `3xx`: `PERMANENT_REJECTION`

Retry, backoff, and expiry SHALL then follow the delivery-processing rules for that class. Response bodies SHALL NOT be stored, audited, or logged.

#### Scenario: Server error retried
- **WHEN** the endpoint answers `503` and then `200`
- **THEN** the first attempt is classified `TRANSIENT`, a retry is scheduled, and the delivery becomes `SENT` with `attemptCount` = 2

#### Scenario: Unreachable endpoint retried
- **WHEN** the connection is refused or the webhook host does not resolve
- **THEN** the attempt is classified `TRANSIENT` and a retry is scheduled

#### Scenario: Slow endpoint times out
- **WHEN** the endpoint does not answer within the provider timeout
- **THEN** the attempt is classified `TIMEOUT` and a retry is scheduled

#### Scenario: Webhook rate limit honored
- **WHEN** the endpoint answers `429` with `Retry-After: 30`
- **THEN** the attempt is classified `RATE_LIMITED` and `nextAttemptAt` is at least 30 seconds after the failed attempt

#### Scenario: Endpoint rejects credentials
- **WHEN** the endpoint answers `401` or `403`
- **THEN** the delivery becomes `FAILED` with failure class `AUTH_ERROR` after 1 attempt

#### Scenario: Endpoint gone
- **WHEN** the endpoint answers `404` or `410`
- **THEN** the delivery becomes `FAILED` with failure class `INVALID_RECIPIENT` after 1 attempt

#### Scenario: Other client error not retried
- **WHEN** the endpoint answers `400`
- **THEN** the delivery becomes `FAILED` with failure class `PERMANENT_REJECTION` after 1 attempt

#### Scenario: Redirect not followed
- **WHEN** the endpoint answers `302` with a `Location` header
- **THEN** no request is sent to the `Location` URL and the delivery becomes `FAILED` with failure class `PERMANENT_REJECTION`

### Requirement: Webhook feature flag
The `WEBHOOK` channel SHALL be controlled by `nms.webhook.enabled` (default `false`). When it is true, startup SHALL fail with a clear message unless `nms.webhook.signing-secret` is set to a non-blank value. The signing secret SHALL never appear in logs, audit events, or API responses.
When it is false, the system SHALL send no webhook request: a `WEBHOOK` delivery that is claimed SHALL become `FAILED` with failure class `PERMANENT_REJECTION`, and its `DELIVERY_FAILED` audit event SHALL carry reason `CHANNEL_DISABLED`. With the flag off, the behavior of every other channel SHALL be unchanged.

#### Scenario: Signing secret required when enabled
- **WHEN** the service starts with `nms.webhook.enabled=true` and no signing secret
- **THEN** startup fails with a message naming `nms.webhook.signing-secret`

#### Scenario: Queued webhook delivery fails while disabled
- **WHEN** a `WEBHOOK` delivery is still `PENDING` and the service runs with `nms.webhook.enabled=false`
- **THEN** no request is sent, the delivery becomes `FAILED` with failure class `PERMANENT_REJECTION`, and `DELIVERY_FAILED` is audited with reason `CHANNEL_DISABLED`
