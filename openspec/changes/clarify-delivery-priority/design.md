# Design

## Context

See proposal.md - Why. Relevant current code:

- `PostgresDeliveryQueue.claim` selects due rows (`PENDING`/`RETRY_SCHEDULED` with `next_attempt_at <= now`, or `IN_FLIGHT` with `locked_until < now`) `ORDER BY n.priority_rank DESC, d.next_attempt_at LIMIT :limit FOR UPDATE OF d SKIP LOCKED`. A claim sets `next_attempt_at = NULL`, so a reclaimed `IN_FLIGHT` row has no `next_attempt_at` and sorts last within its rank (PostgreSQL puts `NULL`s last in ascending order).
- At intake, `next_attempt_at` is `scheduledAt` when it is in the future, otherwise the acceptance time. After a retryable failure it is the backoff target.
- Claim-time expiry (`expire`) and the outcome recorder's `Expire` decision both call `DeliveryMetrics.expired(channel)`. The `Due` row already carries `priority` and `attempt_count`, and `ClaimedDelivery` carries `priority` (added by `add-webhook-channel`).
- `NmsProperties.Worker` is a validated record. Three unit-test context runners (`DedupPropertiesTest`, `WebhookPropertiesTest`, `RoutingConfigGuardTest`) list every worker property explicitly.
- The only claim-related index is `ix_delivery_status_next_attempt (status, next_attempt_at)`. It serves the `WHERE`; the `ORDER BY` is already a sort, because `priority_rank` lives on `notification`.

## Goals / Non-Goals

**Goals:**
- Bounded wait for every priority under sustained higher-priority load, with a switch back to strict ordering.
- Priority behavior stated in the specs and observable through metrics.

**Non-Goals:**
- Latency guarantees, reserved capacity, and priority policy per source system (see proposal - Out of Scope).

## Decisions

### P1. Aging in the claim query
The ordering moves into SQL, so a single claim applies it across every instance:
```sql
ORDER BY LEAST(2, n.priority_rank + CASE WHEN :agingSeconds > 0
           THEN floor(extract(epoch FROM (:now - COALESCE(d.next_attempt_at, d.locked_until))) / :agingSeconds)
           ELSE 0 END) DESC,
         COALESCE(d.next_attempt_at, d.locked_until) ASC
```
- `COALESCE(next_attempt_at, locked_until)` is the due time: `next_attempt_at` for `PENDING`/`RETRY_SCHEDULED`, and the lease expiry for a reclaimable `IN_FLIGHT` row, whose `next_attempt_at` is `NULL`. The `WHERE` guarantees the due time is `<= :now`, so the elapsed time is never negative.
- `:now` is the application `Clock` instant already used by the claim, so tests can fix time.
- Stored `priority`/`priority_rank` are never written.
- *Alternative:* periodically bump `priority_rank` in a job. Rejected: it would change stored priority (the user ruled that out) and add a writer.
- *Alternative:* compute order in Java after fetching all due rows. Rejected: `LIMIT ... SKIP LOCKED` must see the final order, or instances would claim the wrong rows.

### P2. Configuration
`NmsProperties.Worker` gains `@NotNull Duration priorityAging`, with default `5m` in `application.yml`. The compact constructor throws `nms.worker.priority-aging must not be negative, but was <value>` for negative values, and `0` disables aging. The three context-runner tests get `nms.worker.priority-aging=5m` added to their property lists.

### P3. Queue-wait timer
`markInFlight` records `nms.deliveries.queue-wait` (a Micrometer `Timer`, tags `priority`, `channel`) with `now - next_attempt_at` when the row's `attempt_count` is 0, meaning attempt 1. A first attempt always has a non-null `next_attempt_at`. Reclaims and retries have `attempt_count >= 1` and are not recorded.

### P4. Expiry tagging and warning
`DeliveryMetrics.expired(channel, priority, neverAttempted)` replaces `expired(channel)`:
- Claim-time expiry passes `neverAttempted = attempt_count == 0` and, when true, logs WARN `Delivery {} of notification {} (priority {}) expired before any attempt`. No content or address is logged.
- The outcome recorder's `Expire` path follows a real attempt, so it passes `false`.

### P5. Required priority
No code change. `NotificationRequest.priority` is already `@NotNull` with an enum check; a validator test pins the `400` naming `priority`.

## Safe Change Plan

1. Characterization: pin today's claim order (priority, then due time; reclaims last within a rank) and today's expiry metric, on unchanged code.
2. Config property with validation (no behavior change).
3. Aging and due-time ordering in the claim query. The characterization test shows exactly one reviewed diff: reclaims now order by lease expiry. The full suite stays green with the default and with `0` (Maven profile `aging-off`, which sets the property for both Surefire and Failsafe; a plain `-D` only reaches Surefire).
4. Metrics, logging, and docs.

## Risks / Trade-offs

- [The sort expression can't use an index] → it can't today either (the order already spans two tables). The cost is a sort over the due rows per poll. At the measured ~115 deliveries/s the due set stays small, but a very large backlog makes each claim sort it. Mitigation: the `LIMIT` keeps it a top-N heapsort. Revisit together with the queue exit path noted in the README.
- [An aged `LOW` delivery can beat a fresh `HIGH` one] → intended: that is the starvation bound. It happens only after `2 × priority-aging` of waiting.
- [Reclaim order changes] → reclaims now compete by lease expiry instead of sorting last, as confirmed by the user; the characterization diff records it.
- [Metric tag change] → `nms.deliveries.expired` gains tags. Queries that sum over it keep working; queries matching the exact old tag set need the new tags.

## Migration Plan

No schema change. Deploy as usual. To get today's strict ordering back, restart with `nms.worker.priority-aging=0` (reclaims still order by lease expiry). Rolling back the code needs no data work.
