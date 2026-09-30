# Spec Delta

## MODIFIED Requirements

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
