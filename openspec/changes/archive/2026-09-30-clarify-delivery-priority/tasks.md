# Tasks

## 1. Safety net

- [x] 1.1 Add characterization integration test `ClaimOrderCharacterizationIT` that pins today's claim order on unchanged code: `HIGH` before `NORMAL` before `LOW` regardless of due time; within a priority, oldest `nextAttemptAt` first, with retries and first attempts mixed; a reclaimable `IN_FLIGHT` row (expired lease) sorts last within its priority; verify it passes
- [x] 1.2 Add a characterization assertion that claim-time expiry and retry-time expiry each increment `nms.deliveries.expired{channel}`; run `mvn verify` and confirm the full suite is green before any change

## 2. Configuration

- [x] 2.1 `NmsProperties.Worker.priorityAging` (`@NotNull Duration`, default `5m` in `application.yml`; negative values fail with a message naming `nms.worker.priority-aging`; `0` allowed); add `nms.worker.priority-aging=5m` to the three context-runner tests; context-runner test named `negativeAgingIntervalRejected` plus a test that `0` and `5m` start; `mvn verify` green (no behavior change yet)

## 3. Claim ordering

- [x] 3.1 Claim query orders by effective rank (priority rank plus one per full aging interval since the due time, capped at 2; aging off at `0`), then by due time `COALESCE(next_attempt_at, locked_until)` ascending, using the application clock; stored priority is never written. Integration tests (fixed clock or backdated rows) named `waitingDeliveryGainsPriority` (also asserts stored priority is unchanged), `agingDisabled`, `retryCompetesWithNewWork`, `backoffDoesNotCountAsWaiting`, and `reclaimedLeaseOrderedByLeaseExpiry`; the existing `priorityOrdering` stays green
- [x] 3.2 Update `ClaimOrderCharacterizationIT` for the one intended diff (reclaims now order by lease expiry, not last) and confirm no other expectation changed; add a Maven profile `aging-off` that sets `nms.worker.priority-aging=0` for Surefire and Failsafe (like `dedup-off`) and document `mvn verify -Paging-off` in the README Tests section; verify the Failsafe reports show the property; run `mvn verify` and `mvn verify -Paging-off`, both green

## 4. Observability

- [x] 4.1 Timer `nms.deliveries.queue-wait{priority, channel}` recorded in `markInFlight` when `attempt_count` is 0 (first claim minus due time); integration test named `queueWaitMeasured` (a delivery due a known time before its first claim records that duration under the right tags; a retry of the same delivery records nothing more)
- [x] 4.2 `DeliveryMetrics.expired(channel, priority, neverAttempted)`: claim-time expiry passes `attempt_count == 0` and, when true, logs a WARN with notification ID, delivery ID, and priority (no content, no address); the outcome recorder's expiry passes `false`. Integration tests named `neverAttemptedExpiryFlagged` (counter tags plus captured WARN line, which contains no subject, body, or address) and `expiryAfterAnAttempt` (`neverAttempted=false`, no WARN); update the characterization assertion from 1.2 for the new tags

## 5. Required priority and docs

- [x] 5.1 Test named `priorityIsRequired`: a request without `priority` gets `400` naming `priority` and creates no notification (no code change expected)
- [x] 5.2 README: in Delivery processing, the claim order (effective rank, due time, aging with an example, reclaims, retries vs new work, no latency guarantee); `priority-aging` in the worker configuration table; the new and changed metrics in Observability, with how to watch queue wait per priority; priority is required and chosen by the source system in the submission section. Verify against the tests from groups 3–5

## 6. Verification

- [x] 6.1 Run `mvn verify`, `mvn verify -Pdedup-off`, and `mvn verify -Paging-off`; all green
- [x] 6.2 Regenerate `docs/scenario-coverage.md` so it covers this change's new and modified scenarios, each mapped to a test named after it
- [x] 6.3 Run `openspec validate clarify-delivery-priority --strict` and verify it passes
