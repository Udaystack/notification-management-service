# Design

## Context

Brownfield change on the service built by the archived `add-notification-core` (see proposal.md - Why). Relevant current code:

- `Channel` is an enum (`EMAIL`, `SMS`, `PUSH`). `ChannelProviders` refuses to start unless every enum value has a `ChannelProvider` bean. Today only `SimulatedProvider` beans exist.
- `DeliveryWorker` looks up the raw address, builds a `DeliveryRequest(idempotencyKey, channel, address, subject, body, attempt)`, and runs `provider.send` on a virtual thread bounded by `nms.worker.provider-timeout`. When the call exceeds the timeout the result is `TIMEOUT`; an unexpected exception gives `TRANSIENT`.
- `DeliveryOutcomeRecorder` audits `DELIVERY_FAILED` with reason `RETRIES_EXHAUSTED` or the failure class name. A claim has already counted an attempt and audited `DELIVERY_ATTEMPTED` before the provider is called.
- `ClaimedDelivery` carries `sourceSystem`, `recipientId`, `subject`, `body`, and `expiresAt`. It does not carry `eventId`, `type`, `severity`, or `priority`, which the webhook payload needs.
- `RoutingPolicy` applies requested/default channels, escalation, opt-out, missing-address removal, and fallback, with reasons from `RoutingReason`. `RequestValidator` parses channels with `Channel.valueOf`.
- `AddressMasker` is the single masking point (API, audit, logs), with a `switch` per channel.
- V1 created the channel checks inline and unnamed, so PostgreSQL named them `recipient_channel_channel_check` and `delivery_channel_check`. V3 (from `add-event-deduplication`) is the latest migration.
- `NmsProperties` is a validated record. Record `toString` prints every component.

## Goals / Non-Goals

**Goals:**
- A real, signed, SSRF-guarded webhook provider behind the existing `ChannelProvider` port, with no change to the worker's retry and expiry logic.
- No behavior change for existing channels. With the flag off (the default) the full existing suite passes unchanged.

**Non-Goals:**
- A generic HTTP provider framework, per-recipient secrets, or pinning connections to the validated IP (see proposal - Out of Scope).

## Decisions

### W1. Channel value and provider registration
`Channel.WEBHOOK` is added. The `WebhookProvider` bean is always registered, so the `ChannelProviders` completeness check keeps holding. Whether it may send is decided inside the provider (W5), not by bean presence.
- *Alternative:* register the bean only when enabled. Rejected: `ChannelProviders` would fail at startup with the flag off, and queued deliveries would have no provider to fail them cleanly.

### W2. Provider request carries notification metadata
The payload needs `notificationId`, `eventId`, `sourceSystem`, `type`, `severity`, `priority`, and `recipientId`. The claim query already joins `notification`, so it also selects `event_id`, `type`, `severity`, and `priority`. `ClaimedDelivery` and `DeliveryRequest` gain those fields. The simulated providers ignore them. `DeliveryRequest.toString` stays redacted (no address, no content).

### W3. HTTP client
Spring `RestClient` on `JdkClientHttpRequestFactory`, with a JDK `HttpClient` built with `followRedirects(NEVER)`, a connect timeout, and a read timeout from `nms.worker.provider-timeout`. The worker's outer timeout stays the authority for `TIMEOUT`; the adapter also maps `HttpTimeoutException` to `TIMEOUT`, so both paths agree. The request is sent with `exchange(...)`, which reads only the status code and `Retry-After` and never the response body. That keeps responses out of logs and audit, and 4xx/5xx responses don't throw.
- *Alternative:* Apache HttpClient 5 or OkHttp. Rejected: new runtime dependency with no benefit here, since IP pinning is out of scope (see Risks).

### W4. Payload and signature
The body is serialized once to bytes with the application `ObjectMapper`, and the same bytes are signed and sent. `sentAt` and `X-NMS-Timestamp` come from the application `Clock`, at the same instant (ISO-8601 in the body, Unix seconds in the header). The signature is `sha256=` followed by the lowercase hex HMAC-SHA256 (`javax.crypto.Mac`) of `timestamp + "." + body`, keyed with `nms.webhook.signing-secret` in UTF-8. Receivers verify by recomputing it over the raw body, and can reject stale timestamps to prevent replays.

### W5. Feature flag and disabled behavior
`NmsProperties.Webhook(boolean enabled, String signingSecret, boolean allowPrivateHosts)`:
- Its compact constructor fails with a message naming `nms.webhook.signing-secret` when `enabled` is true and the secret is blank.
- `toString()` is overridden to print `signingSecret=***`, because record `toString` would otherwise leak the secret into any log of the properties.
- When disabled, `WebhookProvider.send` returns a failure without any I/O: `PERMANENT_REJECTION` with reason code `CHANNEL_DISABLED`. `DeliveryResult.Failure` gets an optional `reasonCode`, and `DeliveryOutcomeRecorder` uses it for `DELIVERY_FAILED` instead of the class name when present. This keeps the worker channel-agnostic. The claim has already counted attempt 1 and audited `DELIVERY_ATTEMPTED`, so the delivery ends `FAILED` after 1 attempt without any request.
- *Alternative:* skip claiming `WEBHOOK` rows while disabled. Rejected by the user: switching the flag off must stop webhook traffic and settle queued deliveries, not park them.

### W6. Target policy (SSRF)
`WebhookTargetPolicy` checks each call right before it is sent:
1. The URL is parsed as a `URI`. It needs an absolute `https` URL with a host (`http` is also allowed when `allowPrivateHosts` is true). A parse failure or wrong scheme gives `INVALID_RECIPIENT`.
2. The host is resolved through an injectable `AddressResolver` (default `InetAddress.getAllByName`). `UnknownHostException` gives `TRANSIENT`, as the user decided.
3. Unless `allowPrivateHosts` is true, every resolved address must be public: not `isLoopbackAddress`, `isSiteLocalAddress`, `isLinkLocalAddress`, `isAnyLocalAddress`, or `isMulticastAddress`, and not IPv6 unique-local `fc00::/7` (the IPv6 private range). `isAnyLocalAddress` (`0.0.0.0`, `::`) counts as loopback because it reaches the local host. Any non-public address gives `INVALID_RECIPIENT`.

The resolver seam lets unit tests cover "resolves to `10.0.0.5`" without real DNS.

### W7. Outcome mapping
This implements the webhook-delivery spec table. `ResourceAccessException` caused by a connect error gives `TRANSIENT`; one caused by a timeout gives `TIMEOUT`. `Retry-After` is parsed as delta-seconds or an RFC 1123 date, converted to a non-negative `Duration`; an unparsable value is ignored.

### W8. Routing
`RoutingPolicy` takes a `Set<Channel> disabledChannels` (`{WEBHOOK}` when the flag is off, otherwise empty) and applies it as rule 3 with the new `RoutingReason.CHANNEL_DISABLED`, before opt-out, so the removal shows in `ROUTING_DECIDED`. The fallback rule runs unchanged afterwards. The guard lives in `RoutingConfig`: if `WEBHOOK` appears in `default-channels`, `severity-escalation`, or `fallback-channel`, it throws an `IllegalStateException` naming the property.

### W9. Masking
`AddressMasker` gets a `WEBHOOK` case: parse as a `URI` and return `scheme://host/***`. User info, port, path, query, and fragment are dropped, and an unparsable value gives `***`. Every existing API, audit, and log path already goes through the masker.

### W10. Migration (Flyway V4) and seed
```sql
ALTER TABLE recipient_channel DROP CONSTRAINT recipient_channel_channel_check,
  ADD CONSTRAINT recipient_channel_channel_check CHECK (channel IN ('EMAIL','SMS','PUSH','WEBHOOK'));
ALTER TABLE delivery DROP CONSTRAINT delivery_channel_check,
  ADD CONSTRAINT delivery_channel_check CHECK (channel IN ('EMAIL','SMS','PUSH','WEBHOOK'));
INSERT INTO recipient (id, display_name) VALUES ('cust-3001', 'Webhook integration');
INSERT INTO recipient_channel (recipient_id, channel, address, opted_out) VALUES
  ('cust-3001', 'WEBHOOK', 'http://localhost:9099/hooks/cust-3001', FALSE),
  ('cust-3001', 'EMAIL',   'integrations@example.com',              FALSE);
```
The seeded URL is `http` on localhost, so it can only be delivered with `allow-private-hosts` (the local profile). With the defaults it fails as `INVALID_RECIPIENT`, which is the intended safe behavior.

### W11. Configuration
- `application.yml`: `nms.webhook.enabled: false`, `allow-private-hosts: false`, `signing-secret: ${NMS_WEBHOOK_SIGNING_SECRET:}`.
- `application-local.yml`: `enabled: true`, `allow-private-hosts: true`, `signing-secret: ${NMS_WEBHOOK_SIGNING_SECRET:local-dev-only-not-a-secret}`, labelled dev-only in a comment.

### W12. Tests and demo
- WireMock (`org.wiremock:wiremock-standalone`, test scope) on a random localhost port.
- Webhook integration tests set `nms.webhook.enabled=true`, `allow-private-hosts=true`, a test secret, and a short provider timeout themselves. Every other test runs with the default (off).
- Policy tests use the resolver seam with `allow-private-hosts=false`.
- The demo starts `scripts/webhook-receiver.py` (Python 3 standard library only) on port 9099. It verifies the signature with the dev secret, prints the received event, and answers `200`. The demo then submits a `WEBHOOK` notification to `cust-3001` and stops the receiver on exit.

## Safe Change Plan

1. Characterization: pin current routing decisions and reasons, and the masking of every existing channel. Full suite green.
2. Land V4 alone and run the full suite (no behavior change; `cust-3001` isn't used by existing tests).
3. Domain, routing, masking, config guard, and flag with the flag off: full suite green, plus unit tests for the new rules.
4. The webhook provider, tested against WireMock with the flag on.
5. Disabled-path handling of queued deliveries, docs, and demo.

## Risks / Trade-offs

- [DNS rebinding: the JDK client resolves the host again when it connects, so a hostile DNS server could answer with a private IP after the check] → accepted and documented as a known limitation (user decision). The check runs right before each call, redirects are never followed, and the fix (pinning to the checked IP with correct TLS SNI) is listed as out of scope.
- [One global signing secret: a leak affects every receiver] → never logged or returned (redacted `toString`, masked audit); rotation is out of scope.
- [Message content leaves the service] → by design, a webhook sends `subject` and `body` to the recipient's own endpoint, only over `https` unless private hosts are explicitly allowed.
- [Slow receivers tie up worker slots] → bounded by `provider-timeout` like every other provider.
- [Clients switching exhaustively on channel values] → must handle `WEBHOOK`; documented as an additive contract change.
- [Seeded `http://localhost` URL] → deliverable only in the local profile; fails safely elsewhere.

## Migration Plan

Deploy = the application with V4 (applied by Flyway at startup). Webhooks stay off until `nms.webhook.enabled=true` and a signing secret are configured.

To turn webhooks off, restart with `nms.webhook.enabled=false`. Queued webhook deliveries then fail with `CHANNEL_DISABLED` and nothing more is sent. This is the supported way back.

Rolling back the **code** is safe for the schema, but not once any `WEBHOOK` row exists. V4 seeds one immediately (`cust-3001`), and the old code parses channel text with `Channel.valueOf`:
- Routing a request that names `cust-3001` loads its preferences and fails with a `500`.
- A queued `WEBHOOK` delivery makes the old claim query throw, which rolls back that claim batch, so delivery can stall.

Roll back the code only together with a manual cleanup of the `WEBHOOK` rows (no runbook provided, since `DELIVERY_*` audit rows reference those deliveries and are append-only). Otherwise use the flag. The README states this rule.
