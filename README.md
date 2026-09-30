# Notification Management Service (NMS)

A notification service that accepts requests from source systems, routes them to channels (EMAIL,
SMS, PUSH, and later WEBHOOK), delivers them asynchronously with bounded retries, and exposes
status and audit history. It was built with Claude Code in three scenarios, one branch each, using
a spec-driven workflow (OpenSpec).

This `main` branch holds no code. Start with the branch for the scenario you want to see. Each
branch builds on the previous one, and its README documents that branch in full.

| Branch | Scenario | What it adds | Tests |
|---|---|---|---|
| [`greenfield`](https://github.com/Udaystack/notification-management-service/tree/greenfield) | 1. Greenfield | The service from a written brief: submission, routing, idempotency, PostgreSQL queue and worker, retries, status, audit | 117 |
| [`brownfield`](https://github.com/Udaystack/notification-management-service/tree/brownfield) | 2. Brownfield | Event deduplication and a real signed webhook channel, both behind flags, without breaking existing behavior | 194 |
| [`ambiguous-requirements`](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements) | 3. Ambiguous | Settles underspecified priority behavior (starvation, retries, expiry, latency, defaults) before building it | 208 |

## Deliverables

The links go to the most complete README, on `ambiguous-requirements`. Each branch's README has
the same sections for its own state.

| Deliverable | Where |
|---|---|
| Working prototype (runnable end-to-end) | [Setup](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#setup), [Demo](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#demo) (`scripts/demo.sh`), [Load test](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#load-test) |
| Architecture overview (components, tools, execution approach, control flow, key decisions) | [Architecture overview](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#architecture-overview) |
| Three scenarios, each with decomposition, execution, and validation | [Scenarios](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#scenarios) |
| Setup instructions | [Setup](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#setup) |
| Testing approach, limitations, and trade-offs | [Tests](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#tests), [Limitations and trade-offs](https://github.com/Udaystack/notification-management-service/tree/ambiguous-requirements#limitations-and-trade-offs) |

## Quick start

Prerequisites: JDK 21, Maven 3.9+, Docker (with Compose).

```bash
git checkout ambiguous-requirements        # or greenfield / brownfield
docker compose up -d --wait                # PostgreSQL 16 on localhost:5432
mvn verify                                 # build and run the full test suite
java -jar target/notification-management-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
scripts/demo.sh                            # end-to-end walkthrough against the running instance
```

## At a glance

- **Service:** Java 21, Spring Boot 3.5, PostgreSQL 16 (also the work queue, via
  `FOR UPDATE SKIP LOCKED`), Flyway. Provider calls happen outside transactions, delivery is
  at-least-once with idempotency keys, overall status is derived from the deliveries, and audit
  history is append-only.
