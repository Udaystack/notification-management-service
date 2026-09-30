# Spec Delta

## MODIFIED Requirements

### Requirement: Determine delivery channels
The system SHALL determine the channels for each recipient at submission time by applying an ordered, configurable routing policy:
1. Start with the requested channels, or the default channels for the notification type if none were requested.
2. Add escalation channels configured for the notification's severity (default: `CRITICAL` adds `SMS`).
3. Remove channels that are disabled (`WEBHOOK` while `nms.webhook.enabled` is false), with reason `CHANNEL_DISABLED`.
4. Remove channels the recipient has opted out of.
5. Remove channels for which the recipient has no address.
6. If no channel remains, apply the configured fallback channel if the recipient has an address for it and has not opted out of it.

`WEBHOOK` SHALL be selected only when the request lists it in `channels`; it is never a type default, a severity escalation, or the fallback channel.

The system SHALL create one delivery per remaining (recipient, channel) pair.

#### Scenario: Requested channel honored
- **WHEN** a `MEDIUM` notification requests `EMAIL` for a recipient with an email address and no opt-outs
- **THEN** exactly one `EMAIL` delivery is created for that recipient

#### Scenario: Critical severity escalates
- **WHEN** a `CRITICAL` notification requests only `EMAIL` for a recipient with email and phone
- **THEN** both `EMAIL` and `SMS` deliveries are created

#### Scenario: Opt-out respected with fallback
- **WHEN** a `LOW` notification requests `SMS` for a recipient who opted out of `SMS` and has an email address
- **THEN** no `SMS` delivery is created and the fallback `EMAIL` delivery is created

#### Scenario: No deliverable channel for one recipient
- **WHEN** no channel remains for a recipient after all rules, including fallback
- **THEN** no delivery is created for that recipient and the routing decision records reason `NO_ELIGIBLE_CHANNEL`

#### Scenario: No deliverable recipient at all
- **WHEN** no recipient in the request has any eligible channel
- **THEN** the system responds `422 Unprocessable Content` and records `NOTIFICATION_REJECTED` with reason `NO_ELIGIBLE_CHANNEL`

#### Scenario: Webhook routed when requested
- **WHEN** webhooks are enabled and a notification requests `WEBHOOK` for a recipient with a webhook address
- **THEN** exactly one `WEBHOOK` delivery is created for that recipient

#### Scenario: Webhook never added by defaults
- **WHEN** webhooks are enabled and a `CRITICAL` notification requests no channels for a recipient with a webhook address
- **THEN** no `WEBHOOK` delivery is created

#### Scenario: Disabled webhook falls back
- **WHEN** webhooks are disabled and a notification requests only `WEBHOOK` for a recipient with an email address
- **THEN** no `WEBHOOK` delivery is created, the routing decision records `WEBHOOK` with reason `CHANNEL_DISABLED`, and the fallback `EMAIL` delivery is created

## ADDED Requirements

### Requirement: Routing configuration guard
The service SHALL refuse to start if `WEBHOOK` appears in the configured type default channels, severity escalation channels, or fallback channel, and the error message SHALL name the offending setting.

#### Scenario: Webhook in routing configuration rejected
- **WHEN** the service starts with `nms.routing.fallback-channel=WEBHOOK`
- **THEN** startup fails with a message naming `nms.routing.fallback-channel`
