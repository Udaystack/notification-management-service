# Tasks

## 1. Safety net

- [ ] 1.1 Add characterization unit tests that pin, on the unchanged code, the full routing decision (selected channels, per-channel reasons, outcome) for the existing `RoutingPolicyTest` inputs plus a type-default and a `CRITICAL` escalation case, and the `AddressMasker` output for `EMAIL`, `SMS`, `PUSH`, blank, and malformed addresses; verify they pass
- [ ] 1.2 Run `mvn verify` and confirm the full suite is green before any change

## 2. Schema

- [ ] 2.1 Flyway V4: `recipient_channel_channel_check` and `delivery_channel_check` widened with `WEBHOOK`; seed `cust-3001` with `WEBHOOK` `http://localhost:9099/hooks/cust-3001` and `EMAIL` `integrations@example.com`; extend `MigrationIT` to assert V4 applied, both constraints accept `WEBHOOK` and still reject an unknown channel, and the seed rows exist
- [ ] 2.2 Run `mvn verify` against the migrated schema and confirm no behavior change (full suite green)

## 3. Channel, routing, masking, and flag (webhooks off)

- [ ] 3.1 Add `NmsProperties.Webhook(enabled, signingSecret, allowPrivateHosts)` with startup failure naming `nms.webhook.signing-secret` when enabled with a blank secret, and a `toString` that prints `signingSecret=***`; defaults in `application.yml` (`enabled: false`, `allow-private-hosts: false`, `signing-secret: ${NMS_WEBHOOK_SIGNING_SECRET:}`); context-runner test named `signingSecretRequiredWhenEnabled` plus a test that `toString` never contains the secret
- [ ] 3.2 Add `Channel.WEBHOOK` and `RoutingReason.CHANNEL_DISABLED`; `RoutingPolicy` removes disabled channels as rule 3 (before opt-out), then applies the unchanged fallback; `RoutingConfig` passes `{WEBHOOK}` as disabled when the flag is off; unit tests named `webhookRoutedWhenRequested`, `webhookNeverAddedByDefaults`, `disabledWebhookFallsBack`; the characterization tests from 1.1 show no diff
- [ ] 3.3 Routing configuration guard in `RoutingConfig`: startup fails naming the property when `WEBHOOK` is in `nms.routing.default-channels`, `severity-escalation`, or `fallback-channel`; context-runner test named `webhookInRoutingConfigurationRejected` covering all three settings
- [ ] 3.4 `AddressMasker` `WEBHOOK` case (`scheme://host/***`; `***` when unparsable); unit test covering user info, port, path, query, and fragment; the characterization tests from 1.1 show no diff
- [ ] 3.5 Register a `WebhookProvider` bean that, for now, only implements the disabled path: returns `PERMANENT_REJECTION` with reason code `CHANNEL_DISABLED` and no I/O. Add optional `reasonCode` to `DeliveryResult.Failure`, used by `DeliveryOutcomeRecorder` as the `DELIVERY_FAILED` reason. Integration test named `queuedWebhookDeliveryFailsWhileDisabled` (seed a `PENDING` `WEBHOOK` delivery, run the worker, assert `FAILED`, `PERMANENT_REJECTION`, reason `CHANNEL_DISABLED`, and no request to a WireMock stub; add WireMock with test scope here)
- [ ] 3.6 Integration test named `disabledWebhookFallsBack` at the API level: `cust-3001` with `channels: ["WEBHOOK"]` gets an `EMAIL` delivery and `ROUTING_DECIDED` lists `WEBHOOK` with `CHANNEL_DISABLED`; run `mvn verify` and confirm the full existing suite is green with webhooks off

## 4. Webhook provider (webhooks on)

- [ ] 4.1 Carry `eventId`, `type`, `severity`, and `priority` in the claim query, `ClaimedDelivery`, and `DeliveryRequest` (plus `notificationId`, `sourceSystem`, `recipientId`); keep both `toString` methods redacted; the existing delivery tests stay green
- [ ] 4.2 `WebhookTargetPolicy` with an injectable address resolver: `https` only, every resolved address public (loopback, any-local, site-local, link-local, multicast, `fc00::/7` rejected), `allowPrivateHosts` relaxes scheme and addresses, unparsable URL gives `INVALID_RECIPIENT`, unknown host gives `TRANSIENT`; unit tests named `plainHttpTargetRejected`, `privateAddressTargetRejected` (literal `127.0.0.1` and a host resolving to `10.0.0.5`), `privateHostsAllowedForDevelopment`, plus unknown-host and IPv6 cases
- [ ] 4.3 Signed request in `WebhookProvider`: `RestClient` on JDK `HttpClient` with redirects off and timeouts from `provider-timeout`; body serialized once and signed as `sha256=<hex HMAC-SHA256(secret, timestamp + "." + body)>`; headers `Idempotency-Key`, `X-NMS-Timestamp`, `X-NMS-Signature`; `sentAt` and the timestamp from the application `Clock`; the response body is never read. WireMock integration test named `signedWebhookDelivered` that checks every JSON field, the headers, and the signature by recomputing it
- [ ] 4.4 Outcome mapping (5xx, connection error, unknown host → `TRANSIENT`; timeout → `TIMEOUT`; 429 with `Retry-After` in seconds or HTTP date → `RATE_LIMITED`; 401/403 → `AUTH_ERROR`; 404/410 → `INVALID_RECIPIENT`; other 4xx and 3xx → `PERMANENT_REJECTION`). WireMock integration tests named `serverErrorRetried`, `unreachableEndpointRetried`, `slowEndpointTimesOut`, `webhookRateLimitHonored`, `endpointRejectsCredentials` (401 and 403), `endpointGone` (404 and 410), `otherClientErrorNotRetried`, and `redirectNotFollowed` (asserts no request to the `Location` URL); unit test for `Retry-After` parsing
- [ ] 4.5 Sensitive-data tests named `webhookUrlMasked` (audit and `GET` status show `https://hooks.example.com/***` for a URL with user info, port, path, and query) and `signingSecretNeverLogged` (captured logs and audit rows contain neither the secret nor the signature)

## 5. Docs, local profile, and demo

- [ ] 5.1 `application-local.yml`: `nms.webhook.enabled: true`, `allow-private-hosts: true`, `signing-secret: ${NMS_WEBHOOK_SIGNING_SECRET:local-dev-only-not-a-secret}` with a dev-only comment; verify the app starts with `--spring.profiles.active=local` and fails to start with the default profile plus `NMS_WEBHOOK_ENABLED=true` and no secret
- [ ] 5.2 README: `WEBHOOK` channel section (request format and headers, signature verification recipe, target policy and `allow-private-hosts`, outcome mapping, the flag and `CHANNEL_DISABLED` behavior, the additive contract change, the DNS rebinding limitation, and the rollback rule from the design's Migration Plan); update the routing, masking, and configuration tables; verify against the tests from groups 3 and 4
- [ ] 5.3 `scripts/webhook-receiver.py` (Python 3 standard library) on port 9099 that verifies `X-NMS-Signature` with the dev secret and prints the event; extend `scripts/demo.sh` to start it, submit a `WEBHOOK` notification to `cust-3001`, show `SENT` and the verified receipt, and stop the receiver on exit; verify it runs end-to-end against a local instance

## 6. Verification

- [ ] 6.1 Run `mvn verify` and `mvn verify -Pdedup-off`; both must be green
- [ ] 6.2 Regenerate `docs/scenario-coverage.md` so it covers this change's new and modified scenarios, and confirm each maps to a test named after it
- [ ] 6.3 Run `openspec validate add-webhook-channel --strict` and verify it passes
