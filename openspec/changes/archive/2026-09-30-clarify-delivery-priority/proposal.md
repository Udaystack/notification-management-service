# Proposal

## Why

Priority today only sorts claims (`HIGH` before `NORMAL` before `LOW`, then oldest `nextAttemptAt`), and the specs say nothing else about it. That leaves five open questions: lower priorities can starve under sustained `HIGH` load; the ordering of retries against new work was never stated as intended; a delivery can expire while waiting, and nothing flags it; there is no way to see how long each priority waits; and nothing says whether priority is meant to be required. This change settles each question explicitly.

## What Changes

- **Priority aging (starvation fix).** A delivery's *effective* rank rises one level for every full `nms.worker.priority-aging` interval (default `5m`) it has been waiting since it became due, capped at `HIGH`. Claims are ordered by effective rank, then by due time (oldest first). Stored priority never changes. `0` disables aging (today's strict ordering); a negative value fails startup.
- **Due time is defined for every claimable row:** `nextAttemptAt` for first attempts and retries (so a retry's backoff never counts as waiting), and the lease expiry for a reclaimed `IN_FLIGHT` delivery. Reclaims therefore compete by lease expiry instead of sorting last within their priority, as they do today.
- **Retries vs new work, made explicit:** effective rank first, then due time; retries and first attempts are not treated differently. No behavior change beyond aging.
- **No latency guarantee:** the specs state that priority orders claims only. A new timer `nms.deliveries.queue-wait{priority, channel}` records first claim minus due time, once per delivery, so the wait per priority can be observed.
- **Expiry visibility:** `nms.deliveries.expired` gains the tags `priority` and `neverAttempted`, and a delivery that expires before any attempt also logs a WARN with its notification ID, delivery ID, and priority.
- **Priority stays required:** the spec states that every request must carry a priority and that there is no default, so each source system chooses deliberately. No API change.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `delivery-processing`: claim ordering by effective rank with aging and a defined due time, the explicit retry rule, no latency guarantee, the queue-wait metric, and expiry tagging and warning.
- `notification-submission`: priority is explicitly required, with no default.

## Impact

- Code: `PostgresDeliveryQueue` claim ordering, first-claim timing, and claim-time expiry; `DeliveryOutcomeRecorder` expiry; `DeliveryMetrics`; `NmsProperties.Worker` (`priority-aging`); `application.yml`; README (delivery processing, configuration, observability).
- No schema migration and no API contract change. The only changed metric is `nms.deliveries.expired`, which gains two tags; dashboards that sum it keep working.
- Existing behavior: with `nms.worker.priority-aging=0`, claim order equals today's except for reclaimed leases, which now compete by lease expiry.

### Out of Scope
- Limiting which source systems may use `HIGH`, deriving priority from type or severity, or per-source quotas.
- Latency targets or alerts per priority (only measurement).
- Reserved worker capacity per priority.
