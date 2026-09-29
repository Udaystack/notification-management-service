# Proposal

## Why

Business and technical systems need one reliable way to send alerts to users. Without a shared service, each system integrates with email/SMS/push providers directly, duplicating routing, retry, and deduplication logic and producing inconsistent, unauditable delivery. This change introduces the initial Notification Management Service (NMS): it accepts notification requests, routes them to channels, delivers them asynchronously with bounded retries, and exposes status and audit history.

## What Changes

- New REST API to submit a notification (`POST /api/v1/notifications`), returning `202 Accepted`.
- New REST API to read overall notification status and per-recipient, per-channel delivery status (`GET /api/v1/notifications/{id}`).
- New REST API to read a notification's audit history (`GET /api/v1/notifications/{id}/audit`).
- Channel routing based on requested channels, severity, recipient preferences, and a configurable routing policy, with recorded reasons for every decision.
- Idempotent submission via the `Idempotency-Key` header, scoped per source system, plus safe (idempotent) reprocessing of queued deliveries.
- Asynchronous delivery using a PostgreSQL-backed work queue (`FOR UPDATE SKIP LOCKED`) with time-bounded leases.
- Failure classification and bounded retry with exponential backoff and full jitter; honoring provider retry-after and notification expiry.
- Append-only audit history that excludes message content and credentials; contact addresses masked everywhere they are exposed.
- Simulated channel providers (EMAIL, SMS, PUSH) with deterministic, address-based failure injection.
- Per-source-system API key authentication (`X-API-Key`) with tenant isolation on reads.

## Capabilities

### New Capabilities
- `notification-submission`: accept, validate, authenticate, and persist notification requests, including scheduled notifications.
- `notification-status`: expose overall and per-delivery status with timestamps, and the delivery/notification state model.
- `channel-routing`: decide delivery channels per recipient from requested channels, severity, preferences, and fallback, and record the reasons.
- `idempotency`: idempotency keys for submission and safe at-least-once reprocessing of deliveries.
- `delivery-processing`: asynchronous delivery, priority ordering, failure classification, bounded retry, expiry, and simulated providers.
- `audit-history`: record significant lifecycle actions append-only, expose them, and keep sensitive data out of audit and logs.

### Modified Capabilities
<!-- None: greenfield project, no existing specs. -->

## Impact

- New Spring Boot 3 service (Java 21, Maven) and a PostgreSQL 16 schema managed by Flyway.
- Only infrastructure dependency: PostgreSQL (Docker Compose for local runs; Testcontainers for tests).
- No real third-party providers; providers are simulated behind a provider port so real adapters can be added later.
- New public API surface (`/api/v1/notifications/**`) consumed by source systems holding issued API keys.

### Out of Scope
- Real email/SMS/push provider integrations.
- User-facing UI, template management, localization.
- Message brokers (Kafka, RabbitMQ), caches (Redis), multi-region deployment.
- OAuth2 / identity provider integration (documented as the production path).
- Event-level deduplication and suppression.
