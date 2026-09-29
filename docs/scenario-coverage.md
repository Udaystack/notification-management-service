# Scenario coverage

Every scenario in `openspec/changes/add-notification-core/specs/` has at least one test named after it
(the scenario title in camelCase). Unit tests end in `Test`, integration tests in `IT`.

## audit-history

| Scenario | Test |
|---|---|
| Full lifecycle is auditable | `ReadApiIT.fullLifecycleIsAuditable` |
| Audit for unknown notification | `ReadApiIT.auditForUnknownNotification` |
| Audit owned by another source system | `ReadApiIT.auditOwnedByAnotherSourceSystem` |
| Message content excluded | `LoggingIT.messageContentExcluded`, `SensitiveDataIT.messageContentExcluded` |
| Address masked | `AddressMaskerTest.addressMasked`, `SensitiveDataIT.addressMasked` |

## channel-routing

| Scenario | Test |
|---|---|
| Requested channel honored | `RoutingPolicyTest.requestedChannelHonored` |
| Critical severity escalates | `RoutingPolicyTest.criticalSeverityEscalates` |
| Opt-out respected with fallback | `RoutingPolicyTest.optOutRespectedWithFallback` |
| No deliverable channel for one recipient | `RoutingPolicyTest.noDeliverableChannelForOneRecipient` |
| No deliverable recipient at all | `RoutingPolicyTest.noDeliverableRecipientAtAll`, `SubmissionIT.noDeliverableRecipientAtAll` |
| Decision reasons recorded | `RoutingPolicyTest.decisionReasonsRecorded`, `SubmissionIT.decisionReasonsRecorded` |
| Unknown recipient | `RoutingPolicyTest.unknownRecipient` |

## delivery-processing

| Scenario | Test |
|---|---|
| Successful delivery | `DeliveryProcessingIT.successfulDelivery` |
| Priority ordering | `DeliveryProcessingIT.priorityOrdering` |
| Unexpected exception | `UnexpectedProviderExceptionIT.unexpectedException` |
| Transient failure then success | `DeliveryProcessingIT.transientFailureThenSuccess` |
| Retries exhausted | `DeliveryProcessingIT.retriesExhausted` |
| Permanent failure is not retried | `DeliveryProcessingIT.permanentFailureIsNotRetried` |
| Rate limit honored | `DeliveryProcessingIT.rateLimitHonored` |
| Expires while waiting to retry | `DeliveryProcessingIT.expiresWhileWaitingToRetry` |
| Claimed after expiry | `DeliveryProcessingIT.claimedAfterExpiry` |
| Injected failure | `SimulatedProviderTest.injectedFailure` |

## idempotency

| Scenario | Test |
|---|---|
| Same key, same payload | `IdempotencyIT.sameKeySamePayload` |
| Same key, different payload | `IdempotencyIT.sameKeyDifferentPayload` |
| Concurrent duplicate submissions | `IdempotencyIT.concurrentDuplicateSubmissions` |
| Same key from different source systems | `IdempotencyIT.sameKeyFromDifferentSourceSystems` |
| Key reusable after retention | `IdempotencyRetentionIT.keyReusableAfterRetention` |
| Worker crashes mid-delivery | `DeliveryProcessingIT.workerCrashesMidDelivery` |
| Two workers race for one delivery | `DeliveryProcessingIT.twoWorkersRaceForOneDelivery` |

## notification-status

| Scenario | Test |
|---|---|
| Status of an in-progress notification | `NotificationStatusDeriverTest.statusOfAnInProgressNotification`, `ReadApiIT.statusOfAnInProgressNotification` |
| Addresses masked | `ReadApiIT.addressesMasked` |
| Unknown notification | `ReadApiIT.unknownNotification` |
| Notification owned by another source system | `ReadApiIT.notificationOwnedByAnotherSourceSystem` |
| Derived partial delivery | `NotificationStatusDeriverTest.derivedPartialDelivery` |
| Invalid transition rejected | `DeliveryStateMachineTest.invalidTransitionRejected` |

## notification-submission

| Scenario | Test |
|---|---|
| Valid notification is accepted | `SubmissionIT.validNotificationIsAccepted` |
| Processing is asynchronous | `SubmissionIT.processingIsAsynchronous` |
| Missing recipients | `RequestValidatorTest.missingRecipients`, `SubmissionIT.missingRecipients` |
| Unknown enum value | `RequestValidatorTest.unknownEnumValue`, `SubmissionIT.unknownEnumValue` |
| Expiry before schedule | `RequestValidatorTest.expiryBeforeSchedule`, `SubmissionIT.expiryBeforeSchedule` |
| Already expired | `SubmissionIT.alreadyExpired` |
| Missing idempotency key | `RequestValidatorTest.missingIdempotencyKey`, `SubmissionIT.missingIdempotencyKey` |
| Recipient limit exceeded | `RequestValidatorTest.recipientLimitExceeded`, `SubmissionIT.recipientLimitExceeded` |
| Rejection leaves only an audit trace | `SubmissionIT.rejectionLeavesOnlyAnAuditTrace` |
| Missing or invalid key | `ApiKeyAuthIT.missingOrInvalidKey` |
| Source system mismatch | `ApiKeyAuthIT.sourceSystemMismatch` |
| Future schedule | `SubmissionIT.futureSchedule` |
