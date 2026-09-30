# Proposal

## Why

Some recipients are systems, not people: they need notifications pushed to an HTTP endpoint they own. Today the service only has simulated `EMAIL`, `SMS`, and `PUSH` providers. A real, signed webhook channel lets those recipients integrate without polling, and it is the first real outbound provider behind the existing `ChannelProvider` port.

## What Changes

- New channel `WEBHOOK`. A recipient's webhook URL is stored as its `WEBHOOK` address in the existing recipient preferences, with the same opt-out rules as other channels.
- A real HTTP adapter sends each webhook delivery as a signed JSON `POST` (HMAC-SHA256 over timestamp and body, `Idempotency-Key` = delivery ID). It does not follow redirects and is bounded by the existing provider timeout.
- HTTP outcomes map to the existing failure classes, so retries, rate limits, and expiry behave as for every other channel.
- Outbound target policy (SSRF protection): only `https` URLs, and the host must not resolve to a loopback, private, link-local, or multicast address. A violation fails the delivery as `INVALID_RECIPIENT`. `nms.webhook.allow-private-hosts` (default `false`) relaxes this for local development and tests.
- Routing: `WEBHOOK` is selected only when a request lists it in `channels`. Startup fails if `WEBHOOK` appears in the type defaults, severity escalation, or fallback configuration.
- Feature flag `nms.webhook.enabled` (default `false`):
  - Off: routing removes `WEBHOOK` with the new reason `CHANNEL_DISABLED` and then applies the existing fallback rule. Webhook deliveries that are already queued fail without an HTTP call (reason `CHANNEL_DISABLED`).
  - On: `nms.webhook.signing-secret` is required at startup and is never logged.
- Webhook URLs are masked as scheme and host only (`https://hooks.example.com/***`) in status responses, logs, and audit.
- **BREAKING (API contract, additive):** the new channel value `WEBHOOK` can appear in requests, `selectedChannels`, deliveries, and audit, and the new routing reason `CHANNEL_DISABLED` can appear in audit. Clients that switch exhaustively on channel values must handle `WEBHOOK`.
- Flyway migration after V3 widens the channel checks on `recipient_channel` and `delivery` and seeds recipient `cust-3001` with a `WEBHOOK` address and an `EMAIL` address.
- The local profile enables webhooks with a dev-only signing secret and private hosts, so the demo can deliver to a small local receiver.

## Capabilities

### New Capabilities
- `webhook-delivery`: signed HTTP delivery to a recipient's webhook URL, the outbound target policy, the mapping of HTTP outcomes to failure classes, and the `nms.webhook.enabled` feature flag.

### Modified Capabilities
- `channel-routing`: `WEBHOOK` only when requested, removal with reason `CHANNEL_DISABLED` while the feature is off (the fallback still applies), and the startup guard on routing configuration.
- `audit-history`: masking rule for webhook URLs; the webhook signing secret is covered as a credential that must never appear in audit or logs.

## Impact

- Layers touched: domain (`Channel`, `RoutingReason`), routing policy and its configuration, a new HTTP provider in `com.nms.channel`, claim data (the payload needs notification fields the worker does not carry today), worker and outcome recording (flag-off failure with a reason code), address masking, configuration (`nms.webhook.*`), a Flyway migration, seed data, README, demo script.
- Dependencies: no new runtime dependency (Spring `RestClient` on the JDK `HttpClient`). WireMock is added with test scope.
- Existing behavior: with `nms.webhook.enabled=false` (the default), every existing scenario behaves as before, proven by the full existing suite.
- Builds on the main specs of the archived `add-notification-core`. It is independent of the open `add-event-deduplication` change: the deltas touch different requirements, and deduplication applies to `WEBHOOK` like any other channel.

### Out of Scope
- Per-recipient signing secrets and secret rotation.
- An API for managing recipient webhook URLs (they are seeded, like the other addresses).
- Pinning connections to the validated IP address (see the design's DNS rebinding trade-off).
- Response body processing: webhook responses are only classified by status code.
- Failure injection markers for `WEBHOOK` (the simulated providers keep them; the webhook is tested against a stub server).
